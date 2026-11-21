param(
    [switch] $ForceArchives,
    [switch] $SourceArchiveOnly
)

$ErrorActionPreference = 'Stop'

# Local delivery plan:
# 1. Snapshot the complete tracked and non-ignored working-tree source.
# 2. Run formatting, compilation, JVM/native tests, and build the release portable distribution.
# 3. Compile and verify the versioned Windows installer.
# 4. Build the Android APK, create versioned archives, then verify all deliverables.
# 5. Print exact paths, sizes, and SHA-256 hashes without publishing a release.
# -SourceArchiveOnly skips release checks, signing setup, builds, and tests; -ForceArchives is required to replace an existing ZIP.

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$versionLine = Get-Content -LiteralPath (Join-Path $repoRoot 'android/gradle.properties') |
    Where-Object { $_ -match '^ORBIT_RELEASE_VERSION_NAME=' } |
    Select-Object -First 1
if ($null -eq $versionLine) { throw 'ORBIT_RELEASE_VERSION_NAME was not found in android/gradle.properties.' }
$version = ($versionLine -split '=', 2)[1].Trim()
if ($version -notmatch '^\d+(?:\.\d+){2,3}$') { throw "Invalid ORBIT_RELEASE_VERSION_NAME: $version" }
if (-not $SourceArchiveOnly) {
    & (Join-Path $PSScriptRoot 'verify-android-release-version.ps1') -ExpectedVersion $version -Repository 'SMRiaz-SA/Auras-Orbit'
}

if (-not $SourceArchiveOnly) {
    $androidProject = Join-Path $repoRoot 'android'
    $localSigningPropertiesPath = Join-Path $repoRoot 'android-reference/app/build/test-signing/local-signing.properties'
    $localSigningProperties = @{}
    if (Test-Path -LiteralPath $localSigningPropertiesPath -PathType Leaf) {
        foreach ($propertyLine in Get-Content -LiteralPath $localSigningPropertiesPath) {
            if ($propertyLine -match '^\s*([^#!][^=]*)\s*=\s*(.*?)\s*$') {
                $localSigningProperties[$matches[1].Trim()] = $matches[2].Trim()
            }
        }
    }

    $releaseSigningNames = @(
        'AURAS_RELEASE_STORE_FILE',
        'AURAS_RELEASE_STORE_PASSWORD',
        'AURAS_RELEASE_KEY_ALIAS',
        'AURAS_RELEASE_KEY_PASSWORD'
    )
    $providedReleaseSigning = @{}
    foreach ($variableName in $releaseSigningNames) {
        $providedReleaseSigning[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
    }
    $storeFileFromEnvironment = [string] $providedReleaseSigning.AURAS_RELEASE_STORE_FILE
    if ([string]::IsNullOrWhiteSpace($storeFileFromEnvironment)) {
        $storeFileFromProperties = [string] $localSigningProperties.storeFile
        if (-not [string]::IsNullOrWhiteSpace($storeFileFromProperties)) {
            if ([System.IO.Path]::IsPathRooted($storeFileFromProperties)) {
                $providedReleaseSigning.AURAS_RELEASE_STORE_FILE = $storeFileFromProperties
            } else {
                $providedReleaseSigning.AURAS_RELEASE_STORE_FILE = Join-Path (Split-Path -Parent $localSigningPropertiesPath) $storeFileFromProperties
            }
        }
    }
    foreach ($propertyName in @(
        @{ Environment = 'AURAS_RELEASE_STORE_PASSWORD'; Property = 'storePassword' },
        @{ Environment = 'AURAS_RELEASE_KEY_ALIAS'; Property = 'keyAlias' },
        @{ Environment = 'AURAS_RELEASE_KEY_PASSWORD'; Property = 'keyPassword' }
    )) {
        if ([string]::IsNullOrWhiteSpace([string] $providedReleaseSigning[$propertyName.Environment])) {
            $providedReleaseSigning[$propertyName.Environment] = [string] $localSigningProperties[$propertyName.Property]
        }
    }
    if (@($providedReleaseSigning.Values | Where-Object { [string]::IsNullOrWhiteSpace([string] $_) }).Count -gt 0) {
        throw 'The signed Android release needs the existing local-signing.properties file or all four AURAS_RELEASE_* environment variables.'
    }
    $localKeystorePath = [string] $providedReleaseSigning.AURAS_RELEASE_STORE_FILE
    if (-not [System.IO.Path]::IsPathRooted($localKeystorePath)) {
        $localKeystorePath = Join-Path $repoRoot $localKeystorePath
    }
    if (-not (Test-Path -LiteralPath $localKeystorePath -PathType Leaf)) {
        throw 'AURAS_RELEASE_STORE_FILE does not point to an existing Android release keystore.'
    }
    $localReleaseSigning = @{
        AURAS_RELEASE_STORE_FILE = (Resolve-Path -LiteralPath $localKeystorePath).Path
        AURAS_RELEASE_STORE_PASSWORD = [string] $providedReleaseSigning.AURAS_RELEASE_STORE_PASSWORD
        AURAS_RELEASE_KEY_ALIAS = [string] $providedReleaseSigning.AURAS_RELEASE_KEY_ALIAS
        AURAS_RELEASE_KEY_PASSWORD = [string] $providedReleaseSigning.AURAS_RELEASE_KEY_PASSWORD
    }

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
}

$distribution = Join-Path $repoRoot 'desktop-app/build/compose/binaries/main/app/Auras-Orbit'
$outputDirectory = Join-Path $repoRoot 'desktop-app/build/outputs'
$portableZip = Join-Path $outputDirectory "Auras-Orbit-Portable-$version.zip"
$sourceZip = Join-Path $outputDirectory "Auras-Orbit-Source-$version.zip"
$androidApk = Join-Path $outputDirectory 'Auras-Orbit.apk'
$versionedAndroidApk = Join-Path $outputDirectory "$version.apk"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$archiveTargets = if ($SourceArchiveOnly) { @($sourceZip) } else { @($portableZip, $sourceZip) }
foreach ($archive in $archiveTargets) {
    if ((Test-Path -LiteralPath $archive -PathType Leaf) -and -not $ForceArchives) {
        throw "Output already exists; move it aside or rerun with -ForceArchives: $archive"
    }
}

$sourceItems = [System.Collections.Generic.List[object]]::new()
$seenSourcePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
# Keep the source bundle focused; omit archived project material, local evidence, and generated artifacts.
$excludedPath = '(^|/)(\.git|\.gradle|\.kotlin|build|out|dist|node_modules|\.idea|AurasOrbitData|shared_prefs|Independence|artifacts)(/|$)'
$excludedLocalEvidence = '(^|/)(Independence|artifacts)(/|$)'
# These two top-level paths are local decompiler residue, not source inputs.
$excludedTopLevelResidue = '^(?:app-debug|-)(?:/|$)'
$excludedSecret = '(^|/)(\.env(?:\..*)?|local\.properties|keystore\.properties|settings\.json|auth_tokens(?:\..*)?|tracker_credentials(?:\..*)?|[^/]*\.(?:db|sqlite|sqlite3)(?:-shm|-wal)?|[^/]*\.(?:jks|keystore|p12|pfx|pem|key))$'
$excludedLocalOnlyMarkdown = '(^|/)[^/]*(?:PLAN|DESIGN|EXECUTION|DIRECTION|INTEGRATIONS)[^/]*\.md$'
$excludedStatusReport = '(^|/)PROJECT_STATUS_REPORT_\d{4}-\d{2}-\d{2}\.md$'

function Add-SourceFile([string] $BaseDirectory, [string] $RelativePath, [string] $ArchivePrefix) {
    $normalizedRelativePath = $RelativePath.Replace('\', '/')
    $isBundledWebView2Sdk = $normalizedRelativePath.StartsWith(
        'desktop-app/src/main/cpp/webview2/build/',
        [System.StringComparison]::OrdinalIgnoreCase
    )
    # This checked-in .p12 is a synthetic Jellyfin instrumentation fixture, not release signing material.
    $isJellyfinInstrumentationFixtureKeystore = $normalizedRelativePath -eq
        'android/app/src/androidTest/assets/jellyfin-fixture-keystore.p12'
    if (($normalizedRelativePath -match $script:excludedPath -and -not $isBundledWebView2Sdk) -or
        ($normalizedRelativePath -match $script:excludedSecret -and -not $isJellyfinInstrumentationFixtureKeystore) -or
        $normalizedRelativePath -match $script:excludedLocalOnlyMarkdown -or
        $normalizedRelativePath -match $script:excludedStatusReport -or
        $normalizedRelativePath -match $script:excludedTopLevelResidue) {
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
$requiredPackageSources = @(
    "Auras-Orbit-Source-$version/gradle.properties",
    "Auras-Orbit-Source-$version/gradlew.bat",
    "Auras-Orbit-Source-$version/desktop-app/build.gradle.kts",
    "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/include/WebView2.h",
    "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/x64/WebView2Loader.dll",
    "Auras-Orbit-Source-$version/desktop-app/src/main/kotlin/com/lagradost/cloudstream3/desktop/ui/screens/home/HomeDiscovery.kt",
    "Auras-Orbit-Source-$version/.github/scripts/build-local-deliverables.ps1",
    "Auras-Orbit-Source-$version/.github/scripts/verify-android-apk.ps1",
    "Auras-Orbit-Source-$version/.github/scripts/verify-android-release-version.ps1",
    "Auras-Orbit-Source-$version/android-reference/app/build.gradle.kts",
    "Auras-Orbit-Source-$version/android-reference/library/build.gradle.kts",
    "Auras-Orbit-Source-$version/android-reference/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt",
    "Auras-Orbit-Source-$version/android/README.md",
    "Auras-Orbit-Source-$version/android/THIRD-PARTY-NOTICES.md",
    "Auras-Orbit-Source-$version/android/gradlew.bat",
    "Auras-Orbit-Source-$version/android/gradle/wrapper/gradle-wrapper.jar",
    "Auras-Orbit-Source-$version/android/settings.gradle.kts",
    "Auras-Orbit-Source-$version/android/gradle.properties",
    "Auras-Orbit-Source-$version/android/app/build.gradle.kts",
    "Auras-Orbit-Source-$version/android/app/libs/torrentserver-orbit-loopback.aar",
    "Auras-Orbit-Source-$version/android/app/src/androidTest/assets/jellyfin-fixture-keystore.p12"
)
foreach ($requiredSource in $requiredPackageSources) {
    if (-not ($sourceItems.ArchivePath -contains $requiredSource)) {
        throw "Required source file is absent from the source package plan: $requiredSource"
    }
}

# Keep private workspace records linked from the local Markdown while removing those links from
# the distributable copy, whose source filter deliberately excludes local status/evidence files.
function Get-SourceArchiveText([object] $SourceItem) {
    $repoRootWithSeparator = $script:repoRoot.TrimEnd('\') + '\'
    $relativePath = $SourceItem.FullPath.Substring($repoRootWithSeparator.Length).Replace('\', '/')
    $content = [System.IO.File]::ReadAllText($SourceItem.FullPath)
    $replacements = switch -Exact ($relativePath) {
        'README.md' {
            @(@{
                Find = '[Current Orbit status and release evidence](PROJECT_STATUS_REPORT_2026-11-21.md)'
                Replace = 'Current Orbit status and release evidence (workspace-only; excluded from this source archive)'
            })
        }
        'CHANGELOG.md' {
            @(
                @{ Find = '[current Orbit status report](PROJECT_STATUS_REPORT_2026-11-21.md)'; Replace = 'current Orbit status report (workspace-only)' },
                @{ Find = '[local deliverables record](artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md)'; Replace = 'local deliverables record (workspace-only)' },
                @{ Find = '[subtitle audit](artifacts/release-evidence/SUBTITLE_COMPATIBILITY_AUDIT_2026-11-20.md)'; Replace = 'subtitle audit (workspace-only)' }
            )
        }
        'android/README.md' {
            @(
                @{ Find = '[subtitle audit](../artifacts/release-evidence/SUBTITLE_COMPATIBILITY_AUDIT_2026-11-20.md)'; Replace = 'subtitle audit (workspace-only)' },
                @{ Find = '[local deliverables record](../artifacts/release-evidence/LOCAL_DELIVERABLES_2026-11-18.md)'; Replace = 'local deliverables record (workspace-only)' },
                @{ Find = '[current Orbit status report](../PROJECT_STATUS_REPORT_2026-11-21.md)'; Replace = 'current Orbit status report (workspace-only)' }
            )
        }
        'android/CHANGELOG.md' {
            @(@{
                Find = '[Settings acceptance](artifacts/settings-acceptance-2026-11-18/README.md)'
                Replace = 'Settings acceptance (workspace-only)'
            })
        }
        default { @() }
    }

    foreach ($replacement in @($replacements)) {
        if (-not $content.Contains($replacement.Find)) {
            throw "Expected workspace-only link was not found in packaged Markdown: $relativePath"
        }
        $content = $content.Replace($replacement.Find, $replacement.Replace)
    }
    return $content
}

function Add-SourceArchiveItem([object] $Archive, [object] $SourceItem) {
    $relativePath = $SourceItem.FullPath.Substring($script:repoRoot.TrimEnd('\').Length + 1).Replace('\', '/')
    if ($relativePath -notmatch '\.md$') {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $Archive,
            $SourceItem.FullPath,
            $SourceItem.ArchivePath,
            [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
        return
    }

    $content = Get-SourceArchiveText -SourceItem $SourceItem
    $entry = $Archive.CreateEntry($SourceItem.ArchivePath, [System.IO.Compression.CompressionLevel]::Optimal)
    $entry.LastWriteTime = (Get-Item -LiteralPath $SourceItem.FullPath).LastWriteTime
    $entryStream = $entry.Open()
    $writer = [System.IO.StreamWriter]::new($entryStream, [System.Text.UTF8Encoding]::new($false))
    try { $writer.Write($content) } finally { $writer.Dispose() }
}

if ($SourceArchiveOnly) {
    if (Test-Path -LiteralPath $sourceZip -PathType Leaf) {
        if (-not $ForceArchives) {
            throw "Source ZIP already exists; rerun with -ForceArchives to replace it: $sourceZip"
        }
        Remove-Item -LiteralPath $sourceZip -Force
    }

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sourceArchive = [System.IO.Compression.ZipFile]::Open(
        $sourceZip,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        foreach ($sourceItem in ($sourceItems | Sort-Object ArchivePath)) {
            Add-SourceArchiveItem -Archive $sourceArchive -SourceItem $sourceItem
        }
    } finally {
        $sourceArchive.Dispose()
    }

    $sourceArchiveCheck = [System.IO.Compression.ZipFile]::OpenRead($sourceZip)
    try {
        $sourceEntries = @($sourceArchiveCheck.Entries | ForEach-Object { $_.FullName })
        foreach ($requiredSource in $requiredPackageSources) {
            if ($sourceEntries -notcontains $requiredSource) { throw "Source ZIP is missing $requiredSource" }
        }
        $forbiddenEntries = @($sourceEntries | Where-Object {
            $_ -match $script:excludedLocalEvidence -or $_ -match $script:excludedStatusReport
        })
        $privateMarkdown = @($sourceEntries | Where-Object {
            $_ -match $script:excludedLocalOnlyMarkdown
        })
        if ($forbiddenEntries.Count -gt 0 -or $privateMarkdown.Count -gt 0) {
            $excludedEntries = @($forbiddenEntries + $privateMarkdown)
            throw "Source ZIP includes local project/evidence files: $($excludedEntries -join ', ')"
        }
        foreach ($entry in $sourceArchiveCheck.Entries) {
            $stream = $entry.Open()
            try { $stream.CopyTo([System.IO.Stream]::Null) }
            finally { $stream.Dispose() }
        }
    } finally {
        $sourceArchiveCheck.Dispose()
    }

    $sourceZipFile = Get-Item -LiteralPath $sourceZip
    $sourceZipHash = (Get-FileHash -LiteralPath $sourceZip -Algorithm SHA256).Hash
    Write-Host ("Source archive: {0} | {1:N0} bytes | SHA-256 {2}" -f $sourceZipFile.FullName, $sourceZipFile.Length, $sourceZipHash)
    Write-Host ("Source package files: {0}" -f $sourceItems.Count)
    return
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
    & (Join-Path $repoRoot 'gradlew.bat') '--init-script' $testDataInitScript 'spotlessCheck' ':desktop-app:compileKotlin' ':desktop-app:compileTestKotlin' 'test' ':library:jvmTest' ':desktop-app:nativeTest' ':desktop-app:createDistributable' "-PAPP_VERSION=$version" '-PorbitDistribution=release' '--rerun-tasks' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build/test failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

$previousJavaHome = $env:JAVA_HOME
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
        & .\gradlew.bat ':app:assembleRelease' "-PORBIT_RELEASE_VERSION_NAME=$version" '--no-daemon' '--no-configuration-cache' '--console=plain' '--stacktrace' '--max-workers=2'
        if ($LASTEXITCODE -ne 0) { throw "Orbit Android APK build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }

    $androidReleaseDirectory = Join-Path $androidProject 'app/build/outputs/apk/release'
    $androidMetadataPath = Join-Path $androidReleaseDirectory 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $androidMetadataPath -PathType Leaf)) {
        throw "Orbit Android release metadata was not found: $androidMetadataPath"
    }
    $androidMetadata = Get-Content -Raw -LiteralPath $androidMetadataPath | ConvertFrom-Json
    $androidReleaseElements = @($androidMetadata.elements)
    if ($androidMetadata.variantName -ne 'release' -or $androidReleaseElements.Count -ne 1) {
        throw "Expected one Orbit Android release APK in $androidMetadataPath."
    }
    $androidOutputFile = [string] $androidReleaseElements[0].outputFile
    if ([string]::IsNullOrWhiteSpace($androidOutputFile) -or
        [System.IO.Path]::GetFileName($androidOutputFile) -ne $androidOutputFile) {
        throw "Invalid Orbit Android APK output filename in $androidMetadataPath."
    }
    $builtAndroidApk = Join-Path $androidReleaseDirectory $androidOutputFile
    if (-not (Test-Path -LiteralPath $builtAndroidApk -PathType Leaf)) {
        throw "Orbit Android release APK was not found: $builtAndroidApk"
    }
    & (Join-Path $repoRoot '.github/scripts/verify-android-apk.ps1') -ExpectedVersion $version -ApkPath $builtAndroidApk
    Copy-Item -LiteralPath $builtAndroidApk -Destination $androidApk -Force
    Copy-Item -LiteralPath $builtAndroidApk -Destination $versionedAndroidApk -Force
    $namedApkHash = (Get-FileHash -LiteralPath $androidApk -Algorithm SHA256).Hash
    $versionedApkHash = (Get-FileHash -LiteralPath $versionedAndroidApk -Algorithm SHA256).Hash
    if ($namedApkHash -ne $versionedApkHash) {
        throw 'The named and numeric Android updater APK copies do not have matching SHA-256 hashes.'
    }
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
        Add-SourceArchiveItem -Archive $sourceArchive -SourceItem $sourceItem
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
    foreach ($requiredSource in $requiredPackageSources) {
        if ($sourceEntries -notcontains $requiredSource) { throw "Source ZIP is missing $requiredSource" }
    }
    $privateMarkdown = @($sourceEntries | Where-Object {
        $_ -match '(^|/)[^/]*(?:PLAN|DESIGN|EXECUTION|DIRECTION|INTEGRATIONS)[^/]*\.md$'
    })
    $localProjectAndEvidence = @($sourceEntries | Where-Object {
        $_ -match $script:excludedLocalEvidence -or $_ -match $script:excludedStatusReport
    })
    if ($privateMarkdown.Count -gt 0 -or $localProjectAndEvidence.Count -gt 0) {
        $excludedDesign = @($privateMarkdown + $localProjectAndEvidence)
        throw "Source ZIP includes local-only planning/design documents: $($excludedDesign -join ', ')"
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
foreach ($artifact in @((Join-Path $outputDirectory 'Auras-Orbit-Setup.exe'), $portableZip, $sourceZip, $androidApk, $versionedAndroidApk)) {
    $file = Get-Item -LiteralPath $artifact
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    Write-Host ("Artifact: {0} | {1:N0} bytes | SHA-256 {2}" -f $file.FullName, $file.Length, $hash)
}
Write-Host ("Source package files: {0}" -f $sourceItems.Count)
