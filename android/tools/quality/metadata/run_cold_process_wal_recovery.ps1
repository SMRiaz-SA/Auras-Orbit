[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\Codex\Auras Cloud\WORKTREE_REV005_FROM_REV004_2026-11-10_A\AurasOrbit_Source',
    [string]$EvidenceRoot = 'D:\Codex\Auras Cloud\REV005_EVIDENCE_2026-11-10',
    [string]$AndroidSdk = 'C:\Users\Admin\AppData\Local\Android\Sdk',
    [string]$Serial = 'emulator-5554',
    [string]$GradleUserHome = '',
    [string]$TargetPackage = 'com.auras.orbit.queuerender',
    [string]$TestPackage = 'com.auras.orbit.test',
    [string]$RunnerComponent = 'com.auras.orbit.test/com.auras.orbit.testing.OrbitIsolatedAndroidJUnitRunner',
    [string]$ExpectedModel = ''
)

$ErrorActionPreference = 'Stop'
$AndroidRoot = Join-Path $WorkspaceRoot 'android'
$GradleWrapper = Join-Path $AndroidRoot 'gradlew.bat'
$Adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$TestClass = 'com.auras.orbit.metadata.store.OrbitMetadataColdProcessRestartInstrumentationTest'
$FixtureRelativeRoot = 'cache/p5-cold-process-restart'
$RunId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$RunDirectory = Join-Path $EvidenceRoot ("p5_cold_process_restart_" + $RunId)
New-Item -ItemType Directory -Path $RunDirectory -ErrorAction Stop | Out-Null
$HostLog = Join-Path $RunDirectory 'host_orchestration.log'
$PidLog = Join-Path $RunDirectory 'process_pids.log'
$InstallLog = Join-Path $RunDirectory 'gradle_install.log'
$PhaseOneLog = Join-Path $RunDirectory 'phase1_seed_instrumentation.log'
$PhaseTwoLog = Join-Path $RunDirectory 'phase2_verify_instrumentation.log'
$PhaseOneDeviceLogcat = Join-Path $RunDirectory 'phase1_device_logcat.txt'
$AfterStopDeviceLogcat = Join-Path $RunDirectory 'after_force_stop_device_logcat.txt'
$PhaseTwoDeviceLogcat = Join-Path $RunDirectory 'phase2_device_logcat.txt'
$MetadataPath = Join-Path $RunDirectory 'run_metadata.json'
$seedProcess = $null

function Add-EvidenceLine([string]$Message) {
    $line = "$( [DateTime]::UtcNow.ToString('o') ) $Message"
    Add-Content -LiteralPath $HostLog -Value $line -Encoding UTF8
    Add-Content -LiteralPath $PidLog -Value $line -Encoding UTF8
    Write-Host $line
}

function Get-RemotePids([string]$PackageName) {
    $raw = & $Adb -s $Serial shell pidof $PackageName 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $raw) { return @() }
    return @(("$raw" -split '\s+') | Where-Object { $_ -match '^\d+$' } | Sort-Object -Unique)
}

function Get-RemoteFileSize([string]$RelativePath) {
    $raw = & $Adb -s $Serial shell run-as $TargetPackage stat -c %s $RelativePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not stat app-private fixture file '$RelativePath': $raw"
    }
    $sizeText = ("$raw" -split '\s+' | Select-Object -First 1).Trim()
    $size = 0L
    if (-not [long]::TryParse($sizeText, [ref]$size)) {
        throw "ADB returned a nonnumeric size for '$RelativePath': $raw"
    }
    return $size
}

function Invoke-InstrumentationPhase([string]$Phase, [string]$Method, [string]$LogPath) {
    $selector = "$TestClass#$Method"
    $stdoutPath = Join-Path $RunDirectory ("$Phase.stdout.txt")
    $stderrPath = Join-Path $RunDirectory ("$Phase.stderr.txt")
    $arguments = @(
        '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $selector,
        '-e', 'p5_cold_phase', $Phase,
        $RunnerComponent
    )

    Add-EvidenceLine "START_PHASE phase=$Phase selector=$selector component=$RunnerComponent"
    $process = Start-Process -FilePath $Adb -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
        -WindowStyle Hidden
    $observedTargetPids = @()
    $observedTestPids = @()
    while (-not $process.HasExited) {
        $process.Refresh()
        $observedTargetPids += Get-RemotePids $TargetPackage
        $observedTestPids += Get-RemotePids $TestPackage
        Start-Sleep -Milliseconds 250
    }
    $process.WaitForExit()
    $process.Refresh()
    $observedTargetPids += Get-RemotePids $TargetPackage
    $observedTestPids += Get-RemotePids $TestPackage

    $stdout = if (Test-Path -LiteralPath $stdoutPath) {
        Get-Content -LiteralPath $stdoutPath -Raw
    } else { '' }
    $stderr = if (Test-Path -LiteralPath $stderrPath) {
        Get-Content -LiteralPath $stderrPath -Raw
    } else { '' }
    $combined = "=== STDOUT ===`r`n$stdout`r`n=== STDERR ===`r`n$stderr"
    Set-Content -LiteralPath $LogPath -Value $combined -Encoding UTF8
    $allOutput = $stdout + "`n" + $stderr
    if ($process.ExitCode -ne 0 -or $allOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Test run failed') {
        throw "Instrumentation phase '$Phase' failed (adb exit $($process.ExitCode)); see $LogPath"
    }
    if ($allOutput -notmatch 'INSTRUMENTATION_CODE:\s*-1') {
        throw "Instrumentation phase '$Phase' did not report a successful runner completion; see $LogPath"
    }

    $observedTargetPids = @($observedTargetPids | Sort-Object -Unique)
    $observedTestPids = @($observedTestPids | Sort-Object -Unique)
    Add-EvidenceLine "END_PHASE phase=$Phase adbExit=$($process.ExitCode) observedTargetPids=$($observedTargetPids -join ',') observedTestPids=$($observedTestPids -join ',') log=$LogPath"
    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
        TargetPids = $observedTargetPids
        TestPids = $observedTestPids
        LogPath = $LogPath
    }
}

function Start-SeedInstrumentationPhase {
    $selector = "$TestClass#seedDirtyWalAndLeaveDatabasesOpenUntilHostStopsProcess"
    $stdoutPath = Join-Path $RunDirectory 'seed.stdout.txt'
    $stderrPath = Join-Path $RunDirectory 'seed.stderr.txt'
    $arguments = @(
        '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $selector,
        '-e', 'p5_cold_phase', 'seed',
        $RunnerComponent
    )
    $process = Start-Process -FilePath $Adb -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
        -WindowStyle Hidden
    Add-EvidenceLine "START_PHASE phase=seed selector=$selector component=$RunnerComponent adbClientPid=$($process.Id)"
    return [PSCustomObject]@{
        Process = $process
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
        Selector = $selector
    }
}

function Save-DeviceLogcat([string]$Path) {
    & $Adb -s $Serial logcat -d -v threadtime | Set-Content -LiteralPath $Path -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw "Could not capture device logcat to $Path" }
}

function Write-RunHashManifest {
    $manifestPath = Join-Path $RunDirectory 'sha256_manifest.txt'
    $files = @(Get-ChildItem -LiteralPath $RunDirectory -File |
        Where-Object { $_.Name -ne 'sha256_manifest.txt' } | Sort-Object FullName)
    $lines = @()
    foreach ($file in $files) {
        $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
        $lines += "$digest  $($file.FullName)"
    }
    $sourceFiles = @(
        (Join-Path $WorkspaceRoot 'android\app\src\androidTest\java\com\auras\orbit\metadata\store\OrbitMetadataPersistenceQualificationInstrumentationTest.java'),
        (Join-Path $WorkspaceRoot 'android\app\src\androidTest\java\com\auras\orbit\metadata\store\OrbitMetadataColdProcessRestartInstrumentationTest.java'),
        $PSCommandPath
    )
    foreach ($sourceFile in $sourceFiles) {
        if (Test-Path -LiteralPath $sourceFile -PathType Leaf) {
            $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceFile).Hash
            $lines += "SOURCE $digest  $sourceFile"
        }
    }
    Set-Content -LiteralPath $manifestPath -Value $lines -Encoding UTF8
    $manifestDigest = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).Hash
    Write-Host "SHA256_MANIFEST=$manifestPath SHA256=$manifestDigest"
}

function Wait-ForPackageStopped([string]$PackageName, [int]$TimeoutSeconds = 20) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $pids = @(Get-RemotePids $PackageName)
        if ($pids.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Package '$PackageName' still has process IDs: $($pids -join ',')"
}

try {
    if (-not (Test-Path -LiteralPath $GradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper not found: $GradleWrapper"
    }
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
        throw "ADB not found: $Adb"
    }
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    if (-not [string]::IsNullOrWhiteSpace($GradleUserHome)) {
        $env:GRADLE_USER_HOME = $GradleUserHome
    }
    $effectiveGradleUserHome = $env:GRADLE_USER_HOME
    if ([string]::IsNullOrWhiteSpace($effectiveGradleUserHome)) {
        $effectiveGradleUserHome = Join-Path $env:USERPROFILE '.gradle'
    }
    Add-EvidenceLine "GRADLE_USER_HOME=$effectiveGradleUserHome (inherited unless explicitly overridden)"
    if ($TargetPackage -ne 'com.auras.orbit.queuerender' -or $TestPackage -ne 'com.auras.orbit.test') {
        throw 'Use the isolated QueueRender target ID com.auras.orbit.queuerender and test ID com.auras.orbit.test.'
    }

    Add-EvidenceLine 'START_BUILD_INSTALL tasks=:app:installQueueRender,:app:installQueueRenderAndroidTest'
    Push-Location $AndroidRoot
    try {
        & $GradleWrapper :app:installQueueRender :app:installQueueRenderAndroidTest --no-daemon `
            '-PorbitQueueRenderAndroidTest=true' `
            '-PORBIT_RELEASE_VERSION_NAME=0.2.0.19' *>&1 |
            Tee-Object -FilePath $InstallLog
        $gradleExit = $LASTEXITCODE
    } finally { Pop-Location }
    Add-EvidenceLine "END_BUILD_INSTALL exitCode=$gradleExit log=$InstallLog"
    if ($gradleExit -ne 0) { throw "QueueRender test install failed; see $InstallLog" }

    $appApk = Join-Path $AndroidRoot 'app\build\outputs\apk\queueRender\app-queueRender.apk'
    $androidTestApk = Join-Path $AndroidRoot 'app\build\outputs\apk\androidTest\queueRender\app-queueRender-androidTest.apk'
    foreach ($apk in @($appApk, $androidTestApk)) {
        if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
            throw "Expected QueueRender APK is missing after the successful Gradle build: $apk"
        }
    }
    $appApkEvidence = Join-Path $RunDirectory 'app-queueRender.apk'
    $androidTestApkEvidence = Join-Path $RunDirectory 'app-queueRender-androidTest.apk'
    Copy-Item -LiteralPath $appApk -Destination $appApkEvidence -ErrorAction Stop
    Copy-Item -LiteralPath $androidTestApk -Destination $androidTestApkEvidence -ErrorAction Stop
    $appApkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $appApkEvidence).Hash
    $androidTestApkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $androidTestApkEvidence).Hash
    Add-EvidenceLine "APK_HASH app=$appApkSha256 androidTest=$androidTestApkSha256 evidenceCopies=true"

    $targetApk = & $Adb -s $Serial shell pm path $TargetPackage 2>&1
    if ($LASTEXITCODE -ne 0 -or -not $targetApk -or "$targetApk" -notmatch 'package:') {
        throw "QueueRender target package '$TargetPackage' was not installed by the Gradle tasks. ADB: $targetApk"
    }
    $testApk = & $Adb -s $Serial shell pm path $TestPackage 2>&1
    if ($LASTEXITCODE -ne 0 -or -not $testApk -or "$testApk" -notmatch 'package:') {
        throw "QueueRender test package '$TestPackage' was not installed by the Gradle tasks. ADB: $testApk"
    }
    Add-EvidenceLine "INSTALLED target=$TargetPackage apk=$targetApk test=$TestPackage apk=$testApk"

    & $Adb -s $Serial wait-for-device
    if ($LASTEXITCODE -ne 0) { throw "ADB did not reach device $Serial" }
    $apiLevel = (& $Adb -s $Serial shell getprop ro.build.version.sdk).Trim()
    $model = (& $Adb -s $Serial shell getprop ro.product.model).Trim()
    Add-EvidenceLine "DEVICE serial=$Serial api=$apiLevel model=$model target=$TargetPackage test=$TestPackage"
    if ($apiLevel -ne '36') {
        throw "Expected API 36; got API $apiLevel model '$model'"
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedModel) -and $model -ne $ExpectedModel) {
        throw "Expected model '$ExpectedModel'; got API $apiLevel model '$model'"
    }
    $expectedModelLabel = $ExpectedModel
    if ([string]::IsNullOrWhiteSpace($expectedModelLabel)) { $expectedModelLabel = '<none>' }
    Add-EvidenceLine "DEVICE_MODEL_CONSTRAINT=$expectedModelLabel"

    $seedInvocation = Start-SeedInstrumentationPhase
    $seedProcess = $seedInvocation.Process
    $stateRelativePath = "$FixtureRelativeRoot/fixture-state.properties"
    $seedDeadline = [DateTime]::UtcNow.AddSeconds(75)
    $seedReady = $false
    $targetPidsBeforeStop = @()
    $testPidsBeforeStop = @()
    while ([DateTime]::UtcNow -lt $seedDeadline) {
        $seedProcess.Refresh()
        if ($seedProcess.HasExited) {
            $seedStdout = if (Test-Path -LiteralPath $seedInvocation.StdoutPath) {
                Get-Content -LiteralPath $seedInvocation.StdoutPath -Raw
            } else { '' }
            $seedStderr = if (Test-Path -LiteralPath $seedInvocation.StderrPath) {
                Get-Content -LiteralPath $seedInvocation.StderrPath -Raw
            } else { '' }
            throw "Seed instrumentation exited before the host could stop its live process (adb exit $($seedProcess.ExitCode)): $seedStdout $seedStderr"
        }
        $targetPidsBeforeStop = @(Get-RemotePids $TargetPackage)
        $testPidsBeforeStop = @(Get-RemotePids $TestPackage)
        $markerProbe = & $Adb -s $Serial shell run-as $TargetPackage test -f $stateRelativePath 2>&1
        $markerExists = ($LASTEXITCODE -eq 0)
        $seedLogcat = & $Adb -s $Serial logcat -d -s 'P5ColdRestartTest:I' 2>&1
        $readyLogPresent = ("$seedLogcat" -match 'SEED_READY')
        if ($markerExists -and $readyLogPresent -and $targetPidsBeforeStop.Count -gt 0) {
            $seedReady = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $seedReady) {
        throw 'Seed did not publish its durable state and SEED_READY log while the target app PID remained alive.'
    }
    Save-DeviceLogcat $PhaseOneDeviceLogcat
    $oldPids = @($targetPidsBeforeStop + $testPidsBeforeStop | Sort-Object -Unique)
    Add-EvidenceLine "SEED_HELD_OPEN targetPids=$($targetPidsBeforeStop -join ',') testPids=$($testPidsBeforeStop -join ',') oldPids=$($oldPids -join ',')"

    $stateLines = & $Adb -s $Serial shell run-as $TargetPackage cat $stateRelativePath
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the app-private cold fixture state marker.' }
    $stateText = @($stateLines) -join "`n"
    $cachePageMatch = [regex]::Match("$stateText", '(?m)^cachePageSize=(\d+)')
    $decisionsPageMatch = [regex]::Match("$stateText", '(?m)^decisionsPageSize=(\d+)')
    $libraryPageMatch = [regex]::Match("$stateText", '(?m)^libraryPageSize=(\d+)')
    if (-not $cachePageMatch.Success -or -not $decisionsPageMatch.Success -or -not $libraryPageMatch.Success) {
        throw 'Cold fixture state marker is missing recorded SQLite page sizes.'
    }
    $walMinimums = @{
        cache = 32L + 24L + [long]$cachePageMatch.Groups[1].Value
        decisions = 32L + 24L + [long]$decisionsPageMatch.Groups[1].Value
        library = 32L + 24L + [long]$libraryPageMatch.Groups[1].Value
    }
    $walPaths = @{
        cache = "$FixtureRelativeRoot/orbit_metadata_cache.db-wal"
        decisions = "$FixtureRelativeRoot/orbit_metadata_user_decisions.db-wal"
        library = "$FixtureRelativeRoot/orbit_local_library.db-wal"
    }
    foreach ($name in @('cache', 'decisions', 'library')) {
        $size = Get-RemoteFileSize $walPaths[$name]
        if ($size -lt $walMinimums[$name]) {
            throw "Seed phase did not leave a complete uncheckpointed $name WAL frame (bytes=$size minimum=$($walMinimums[$name]))."
        }
        Add-EvidenceLine "SEED_WAL name=$name bytes=$size minimumFrameBytes=$($walMinimums[$name]) path=$($walPaths[$name])"
    }

    Add-EvidenceLine "FORCE_STOP target=$TargetPackage"
    & $Adb -s $Serial shell am force-stop $TargetPackage 2>&1 |
        Tee-Object -FilePath (Join-Path $RunDirectory 'force_stop_target.log')
    if ($LASTEXITCODE -ne 0) { throw "am force-stop failed for $TargetPackage" }
    Wait-ForPackageStopped $TargetPackage

    $remainingTestPids = @(Get-RemotePids $TestPackage)
    if ($remainingTestPids.Count -gt 0) {
        Add-EvidenceLine "FORCE_STOP_TEST_RUNNER package=$TestPackage pids=$($remainingTestPids -join ',')"
        & $Adb -s $Serial shell am force-stop $TestPackage 2>&1 |
            Tee-Object -FilePath (Join-Path $RunDirectory 'force_stop_test_package.log')
        if ($LASTEXITCODE -ne 0) { throw "am force-stop failed for test package $TestPackage" }
        Wait-ForPackageStopped $TestPackage
    }
    $livePids = @((Get-RemotePids $TargetPackage) + (Get-RemotePids $TestPackage) | Sort-Object -Unique)
    $survivors = @($oldPids | Where-Object { $livePids -contains $_ })
    if ($survivors.Count -gt 0) { throw "Old seed PID(s) survived the force-stop: $($survivors -join ',')" }
    Add-EvidenceLine "AFTER_FORCE_STOP oldPids=$($oldPids -join ',') liveTargetPids=$((Get-RemotePids $TargetPackage) -join ',') liveTestPids=$((Get-RemotePids $TestPackage) -join ',') result=OLD_PIDS_GONE"
    if (-not $seedProcess.WaitForExit(15000)) {
        Add-EvidenceLine "SEED_ADB_CLIENT_STUCK adbClientPid=$($seedProcess.Id); terminating local adb client after remote process death"
        Stop-Process -Id $seedProcess.Id -Force -ErrorAction SilentlyContinue
        $seedProcess.WaitForExit()
    }
    $seedStdout = if (Test-Path -LiteralPath $seedInvocation.StdoutPath) {
        Get-Content -LiteralPath $seedInvocation.StdoutPath -Raw
    } else { '' }
    $seedStderr = if (Test-Path -LiteralPath $seedInvocation.StderrPath) {
        Get-Content -LiteralPath $seedInvocation.StderrPath -Raw
    } else { '' }
    $seedCombined = "=== EXPECTED HOST-STOPPED SEED STDOUT ===`r`n$seedStdout`r`n=== STDERR ===`r`n$seedStderr"
    Set-Content -LiteralPath $PhaseOneLog -Value $seedCombined -Encoding UTF8
    $phaseOne = [PSCustomObject]@{
        ExitCode = $seedProcess.ExitCode
        TargetPids = @($targetPidsBeforeStop)
        TestPids = @($testPidsBeforeStop)
        LogPath = $PhaseOneLog
        HostStoppedExternally = $true
    }
    Add-EvidenceLine "END_PHASE phase=seed adbExit=$($seedProcess.ExitCode) expectedExternalStop=true log=$PhaseOneLog"

    foreach ($name in @('cache', 'decisions', 'library')) {
        $size = Get-RemoteFileSize $walPaths[$name]
        if ($size -lt $walMinimums[$name]) {
            throw "The uncheckpointed $name WAL did not survive target force-stop (bytes=$size minimum=$($walMinimums[$name]))."
        }
        Add-EvidenceLine "POST_STOP_WAL name=$name bytes=$size minimumFrameBytes=$($walMinimums[$name]) path=$($walPaths[$name])"
    }
    Save-DeviceLogcat $AfterStopDeviceLogcat

    $phaseTwo = Invoke-InstrumentationPhase 'verify' 'reopensWALAndDurableDataAfterExternalProcessDeath' $PhaseTwoLog
    if ($phaseTwo.TargetPids.Count -eq 0) {
        throw 'The verification phase did not run in an observable QueueRender target process.'
    }
    Save-DeviceLogcat $PhaseTwoDeviceLogcat
    Add-EvidenceLine "COLD_RESTART_VERIFIED phase1TargetPids=$($phaseOne.TargetPids -join ',') phase2TargetPids=$($phaseTwo.TargetPids -join ',') oldPidsGone=true physicalENOSP=NOT_TESTED"

    [PSCustomObject]@{
        runId = $RunId
        deviceSerial = $Serial
        apiLevel = $apiLevel
        model = $model
        expectedModelConstraint = $ExpectedModel
        targetPackage = $TargetPackage
        testPackage = $TestPackage
        runnerComponent = $RunnerComponent
        applicationIdSuffix = '.queuerender'
        gradleUserHome = $effectiveGradleUserHome
        phaseOne = $phaseOne
        phaseTwo = $phaseTwo
        applicationApkSha256 = $appApkSha256
        androidTestApkSha256 = $androidTestApkSha256
        oldPids = $oldPids
        result = 'PASS'
        physicalFilesystemEnospNotTested = $true
        hostLog = $HostLog
        pidLog = $PidLog
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $MetadataPath -Encoding UTF8
    Add-EvidenceLine "COMPLETE result=PASS metadata=$MetadataPath"
} catch {
    Add-EvidenceLine "FAILED message=$($_.Exception.Message)"
    throw
} finally {
    if ($null -ne $seedProcess) {
        $seedProcess.Refresh()
        if (-not $seedProcess.HasExited) {
            Add-EvidenceLine 'CLEANUP_STOPPING_SEED_AND_TEST_PACKAGES'
            & $Adb -s $Serial shell am force-stop $TargetPackage 2>&1 | Out-Null
            & $Adb -s $Serial shell am force-stop $TestPackage 2>&1 | Out-Null
            if (-not $seedProcess.WaitForExit(10000)) {
                Stop-Process -Id $seedProcess.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }
    Write-RunHashManifest
}
