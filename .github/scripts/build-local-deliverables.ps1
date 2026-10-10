param(
    [switch] $ForceArchives
)

$ErrorActionPreference = 'Stop'

# Local delivery plan:
# 1. Snapshot the complete tracked and non-ignored working-tree source.
# 2. Run formatting, compilation, JVM/native tests, and build the release portable distribution.
# 3. Compile and verify the versioned Windows installer.
# 4. Build the Android APK, create versioned archives, then verify all deliverables.
# 5. Print exact paths, sizes, and SHA-256 hashes without publishing a release.

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$versionLine = Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') |
    Where-Object { $_ -match '^APP_VERSION=' } |
    Select-Object -First 1
if ($null -eq $versionLine) { throw 'APP_VERSION was not found in gradle.properties.' }
$version = ($versionLine -split '=', 2)[1].Trim()
if ($version -notmatch '^\d+(?:\.\d+){2,3}$') { throw "Invalid APP_VERSION: $version" }

$androidSdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
if ($env:LOCALAPPDATA) {
    $androidSdkCandidates += Join-Path $env:LOCALAPPDATA 'Android/Sdk'
}
$androidSdk = $androidSdkCandidates |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) } |
    Select-Object -First 1
if ($null -eq $androidSdk) { throw 'Android SDK was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.' }
$androidSdk = (Resolve-Path -LiteralPath $androidSdk).Path
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk

$androidJavaCandidates = @($env:JAVA_HOME_17_X64, (Join-Path $repoRoot 'build/tools/temurin-17'))
if (Test-Path -LiteralPath 'C:\Program Files\Eclipse Adoptium') {
    $androidJavaCandidates += Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' | Select-Object -ExpandProperty FullName
}
if ($env:LOCALAPPDATA) {
    $userTemurin = Join-Path $env:LOCALAPPDATA 'Programs/Eclipse Adoptium'
    if (Test-Path -LiteralPath $userTemurin) {
        $androidJavaCandidates += Get-ChildItem -LiteralPath $userTemurin -Directory -Filter 'jdk-17*' | Select-Object -ExpandProperty FullName
    }
}
$androidJavaHome = $androidJavaCandidates |
    Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and
            (Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe') -PathType Leaf) -and
            (Get-Content -Raw -LiteralPath (Join-Path $_ 'release')) -match 'JAVA_VERSION="17\.'
    } |
    Select-Object -First 1
if ($null -eq $androidJavaHome) { throw 'Android APK builds require a JDK 17 installation. Set JAVA_HOME_17_X64.' }

$distribution = Join-Path $repoRoot 'desktop-app/build/compose/binaries/main/app/Auras-Orbit'
$outputDirectory = Join-Path $repoRoot 'desktop-app/build/outputs'
$portableZip = Join-Path $outputDirectory "Auras-Orbit-Portable-$version.zip"
$sourceZip = Join-Path $outputDirectory "Auras-Orbit-Source-$version.zip"
$androidApk = Join-Path $outputDirectory 'Auras-Orbit.apk'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

foreach ($archive in @($portableZip, $sourceZip)) {
    if ((Test-Path -LiteralPath $archive -PathType Leaf) -and -not $ForceArchives) {
        throw "Output already exists; move it aside or rerun with -ForceArchives: $archive"
    }
}

$sourceItems = [System.Collections.Generic.List[object]]::new()
$seenSourcePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$excludedPath = '(^|/)(\.git|\.gradle|\.kotlin|build|out|dist|node_modules|\.idea|AurasOrbitData|shared_prefs)(/|$)'
$excludedSecret = '(^|/)(\.env(?:\..*)?|local\.properties|keystore\.properties|settings\.json|auth_tokens(?:\..*)?|tracker_credentials(?:\..*)?|[^/]*\.(?:db|sqlite|sqlite3)(?:-shm|-wal)?|[^/]*\.(?:jks|keystore|p12|pfx|pem|key))$'

function Add-SourceFile([string] $BaseDirectory, [string] $RelativePath, [string] $ArchivePrefix) {
    $normalizedRelativePath = $RelativePath.Replace('\', '/')
    $isBundledWebView2Sdk = $normalizedRelativePath.StartsWith(
        'desktop-app/src/main/cpp/webview2/build/',
        [System.StringComparison]::OrdinalIgnoreCase
    )
    if (($normalizedRelativePath -match $script:excludedPath -and -not $isBundledWebView2Sdk) -or
        $normalizedRelativePath -match $script:excludedSecret) {
        return
    }

    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $RelativePath))
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { return }
    $repoRootWithSeparator = $script:repoRoot.TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($repoRootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Source path escaped the repository root: $fullPath"
    }

    $archivePath = "$ArchivePrefix$normalizedRelativePath"
    if ($script:seenSourcePaths.Add($archivePath)) {
        $script:sourceItems.Add([pscustomobject]@{ FullPath = $fullPath; ArchivePath = $archivePath })
    }
}

$rootSourcePaths = @(& git -C $repoRoot ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate working-tree source files.' }
foreach ($relativePath in $rootSourcePaths) {
    Add-SourceFile -BaseDirectory $repoRoot -RelativePath $relativePath -ArchivePrefix "Auras-Orbit-Source-$version/"
}

if ($sourceItems.Count -eq 0) { throw 'The source package file list is empty.' }
foreach ($requiredSource in @(
    "Auras-Orbit-Source-$version/gradlew.bat",
    "Auras-Orbit-Source-$version/desktop-app/build.gradle.kts",
    "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/include/WebView2.h",
    "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/x64/WebView2Loader.dll",
    "Auras-Orbit-Source-$version/desktop-app/src/main/kotlin/com/lagradost/cloudstream3/desktop/ui/screens/home/HomeDiscovery.kt",
    "Auras-Orbit-Source-$version/.github/scripts/build-local-deliverables.ps1",
    "Auras-Orbit-Source-$version/android-reference/LICENSE",
    "Auras-Orbit-Source-$version/android-reference/library/build.gradle.kts",
    "Auras-Orbit-Source-$version/android-reference/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt",
    "Auras-Orbit-Source-$version/android-reference/library/src/commonTest/kotlin/com/lagradost/cloudstream3/EpisodeDateTest.kt"
)) {
    if (-not ($sourceItems.ArchivePath -contains $requiredSource)) {
        throw "Required source file is absent from the source package plan: $requiredSource"
    }
}

$mpvDll = Join-Path $repoRoot 'desktop-app/appResources/windows/mpv/libmpv-2.dll'
$mpvHashExpected = '872827614ED0ADFCA11E68DEF5273BCFCAEA6ACF38BBF1950C35980B59F43A5F'
$mpvLicense = Join-Path $repoRoot 'desktop-app/appResources/legal/mpv-LICENSE.GPL.txt'
$mpvProvenance = Join-Path $repoRoot 'desktop-app/appResources/legal/MPV-PROVENANCE.txt'
$mpvReady = (Test-Path -LiteralPath $mpvDll -PathType Leaf) -and
    (Test-Path -LiteralPath $mpvLicense -PathType Leaf) -and
    (Test-Path -LiteralPath $mpvProvenance -PathType Leaf)
if (-not $mpvReady -or (Get-FileHash -LiteralPath $mpvDll -Algorithm SHA256).Hash -ne $mpvHashExpected) {
    & (Join-Path $PSScriptRoot 'fetch-mpv.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Pinned MPV runtime retrieval/verification failed.' }
}

Push-Location $repoRoot
try {
    $testDataDirectory = Join-Path $repoRoot 'build/local-deliverable-test-data'
    $testDataDirectoryForGradle = $testDataDirectory.Replace('\', '/')
    $testDataInitScript = Join-Path $repoRoot 'build/local-deliverables-test-data.init.gradle'
    @"
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        systemProperty 'auras.data.dir', '$testDataDirectoryForGradle'
    }
}
"@ | Set-Content -LiteralPath $testDataInitScript -Encoding utf8

    # Keep persistence and plugin-worker tests away from the developer's real app data.
    & (Join-Path $repoRoot 'gradlew.bat') '--init-script' $testDataInitScript 'spotlessCheck' ':desktop-app:compileKotlin' ':desktop-app:compileTestKotlin' 'test' ':library:jvmTest' ':desktop-app:nativeTest' ':desktop-app:createDistributable' '-PorbitDistribution=release' '--rerun-tasks' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build/test failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

$androidProject = Join-Path $repoRoot 'android-reference'
$previousJavaHome = $env:JAVA_HOME
$signingPropertiesPath = Join-Path $androidProject 'app/build/test-signing/local-signing.properties'
if (-not (Test-Path -LiteralPath $signingPropertiesPath -PathType Leaf)) {
    throw "Local Android signing properties were not found: $signingPropertiesPath"
}
$signingProperties = @{}
foreach ($line in Get-Content -LiteralPath $signingPropertiesPath) {
    if ($line -match '^\s*([^#!\s][^=]*)=(.*)$') {
        $signingProperties[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}
foreach ($requiredProperty in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if ([string]::IsNullOrWhiteSpace([string] $signingProperties[$requiredProperty])) {
        throw "Local Android signing properties are missing '$requiredProperty'."
    }
}
$localKeystorePath = [string] $signingProperties.storeFile
if (-not [System.IO.Path]::IsPathRooted($localKeystorePath)) {
    $localKeystorePath = Join-Path (Join-Path $androidProject 'app') $localKeystorePath
}
if (-not (Test-Path -LiteralPath $localKeystorePath -PathType Leaf)) {
    $localKeystorePath = Join-Path $androidProject 'app/build/test-signing/auras-orbit-local-test.p12'
}
if (-not (Test-Path -LiteralPath $localKeystorePath -PathType Leaf)) {
    throw 'The local Android test signing keystore was not found.'
}
$localReleaseSigning = @{
    AURAS_RELEASE_STORE_FILE = (Resolve-Path -LiteralPath $localKeystorePath).Path
    AURAS_RELEASE_STORE_PASSWORD = [string] $signingProperties.storePassword
    AURAS_RELEASE_KEY_ALIAS = [string] $signingProperties.keyAlias
    AURAS_RELEASE_KEY_PASSWORD = [string] $signingProperties.keyPassword
}
$previousReleaseSigning = @{}
foreach ($variableName in $localReleaseSigning.Keys) {
    $previousReleaseSigning[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
}

try {
    $env:JAVA_HOME = $androidJavaHome
    foreach ($variableName in $localReleaseSigning.Keys) {
        [Environment]::SetEnvironmentVariable($variableName, $localReleaseSigning[$variableName], 'Process')
    }
    Push-Location $androidProject
    try {
        & .\gradlew.bat ':app:canonicalStableReleaseApk' "-PAPP_VERSION=$version" '--no-daemon' '--console=plain' '--stacktrace' '--max-workers=2'
        if ($LASTEXITCODE -ne 0) { throw "Android APK build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }

    $builtAndroidApk = Join-Path $androidProject 'app/build/outputs/canonical/stableRelease/Auras-Orbit.apk'
    & (Join-Path $repoRoot '.github/scripts/verify-android-apk.ps1') -ExpectedVersion $version -ApkPath $builtAndroidApk
    Copy-Item -LiteralPath $builtAndroidApk -Destination $androidApk -Force
} finally {
    if ([string]::IsNullOrWhiteSpace($previousJavaHome)) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_HOME = $previousJavaHome
    }
    foreach ($variableName in $localReleaseSigning.Keys) {
        [Environment]::SetEnvironmentVariable($variableName, $previousReleaseSigning[$variableName], 'Process')
    }
}

$isccCandidates = [System.Collections.Generic.List[string]]::new()
$isccCandidates.Add((Join-Path $repoRoot 'build/tools/innosetup/install/ISCC.exe'))
if (${env:ProgramFiles(x86)}) {
    $isccCandidates.Add((Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6/ISCC.exe'))
}
if ($env:ProgramFiles) {
    $isccCandidates.Add((Join-Path $env:ProgramFiles 'Inno Setup 6/ISCC.exe'))
}
$iscc = $isccCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ($null -eq $iscc) {
    throw 'Inno Setup 6 compiler was not found. Install it or place ISCC.exe at build/tools/innosetup/install/ISCC.exe.'
}
& $iscc (Join-Path $repoRoot 'installer/setup.iss')
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed with exit code $LASTEXITCODE." }

& (Join-Path $PSScriptRoot 'verify-distribution.ps1') -ExpectedVersion $version -RequireInstaller
if ($LASTEXITCODE -ne 0) { throw 'Portable distribution and installer verification failed.' }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path -LiteralPath $portableZip) { Remove-Item -LiteralPath $portableZip -Force }
if (Test-Path -LiteralPath $sourceZip) { Remove-Item -LiteralPath $sourceZip -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $distribution,
    $portableZip,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true
)

$sourceArchive = [System.IO.Compression.ZipFile]::Open(
    $sourceZip,
    [System.IO.Compression.ZipArchiveMode]::Create
)
try {
    foreach ($sourceItem in ($sourceItems | Sort-Object ArchivePath)) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $sourceArchive,
            $sourceItem.FullPath,
            $sourceItem.ArchivePath,
            [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} finally {
    $sourceArchive.Dispose()
}

$portableArchiveCheck = [System.IO.Compression.ZipFile]::OpenRead($portableZip)
try {
    $portableEntries = @($portableArchiveCheck.Entries | ForEach-Object { $_.FullName })
    foreach ($requiredEntry in @(
        'Auras-Orbit/Auras-Orbit.exe',
        'Auras-Orbit/legal/AURAS-ORBIT-LICENSE.txt',
        'Auras-Orbit/legal/AURAS-ORBIT-NOTICE.txt',
        'Auras-Orbit/legal/THIRD-PARTY-NOTICES.txt',
        'Auras-Orbit/legal/CLOUDSTREAM-UPSTREAM-LICENSE.txt',
        'Auras-Orbit/legal/WEBVIEW2-SDK-LICENSE.txt',
        'Auras-Orbit/legal/WEBVIEW2-SDK-NOTICE.txt',
        'Auras-Orbit/legal/MPV-LICENSE.GPL.txt',
        'Auras-Orbit/legal/MPV-PROVENANCE.txt'
    )) {
        if ($portableEntries -notcontains $requiredEntry) { throw "Portable ZIP is missing $requiredEntry" }
    }
} finally {
    $portableArchiveCheck.Dispose()
}

$sourceArchiveCheck = [System.IO.Compression.ZipFile]::OpenRead($sourceZip)
try {
    $sourceEntries = @($sourceArchiveCheck.Entries | ForEach-Object { $_.FullName })
    foreach ($requiredSource in @(
        "Auras-Orbit-Source-$version/gradlew.bat",
        "Auras-Orbit-Source-$version/desktop-app/build.gradle.kts",
        "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/include/WebView2.h",
        "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/x64/WebView2Loader.dll",
        "Auras-Orbit-Source-$version/desktop-app/src/main/kotlin/com/lagradost/cloudstream3/desktop/ui/screens/home/HomeDiscovery.kt",
        "Auras-Orbit-Source-$version/.github/scripts/build-local-deliverables.ps1",
        "Auras-Orbit-Source-$version/android-reference/LICENSE",
        "Auras-Orbit-Source-$version/android-reference/library/build.gradle.kts",
        "Auras-Orbit-Source-$version/android-reference/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt",
        "Auras-Orbit-Source-$version/android-reference/library/src/commonTest/kotlin/com/lagradost/cloudstream3/EpisodeDateTest.kt"
    )) {
        if ($sourceEntries -notcontains $requiredSource) { throw "Source ZIP is missing $requiredSource" }
    }
    $privateMarkdown = @($sourceEntries | Where-Object {
        $_ -match '(^|/)[^/]*(?:PLAN|DESIGN|EXECUTION|DIRECTION|INTEGRATIONS)[^/]*\.md$'
    })
    if ($privateMarkdown.Count -gt 0) {
        throw "Source ZIP includes local-only planning/design documents: $($privateMarkdown -join ', ')"
    }
} finally {
    $sourceArchiveCheck.Dispose()
}

function Assert-ZipIntegrity([string] $ArchivePath) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        foreach ($entry in $archive.Entries) {
            $stream = $entry.Open()
            try { $stream.CopyTo([System.IO.Stream]::Null) }
            finally { $stream.Dispose() }
        }
    } finally {
        $archive.Dispose()
    }
}

Assert-ZipIntegrity $sourceZip
Assert-ZipIntegrity $portableZip

Write-Host "Portable directory: $distribution"
foreach ($artifact in @((Join-Path $outputDirectory 'Auras-Orbit-Setup.exe'), $portableZip, $sourceZip, $androidApk)) {
    $file = Get-Item -LiteralPath $artifact
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    Write-Host ("Artifact: {0} | {1:N0} bytes | SHA-256 {2}" -f $file.FullName, $file.Length, $hash)
}
Write-Host ("Source package files: {0}" -f $sourceItems.Count)
