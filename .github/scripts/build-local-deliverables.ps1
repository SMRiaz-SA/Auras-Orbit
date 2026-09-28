param(
    [switch] $ForceArchives
)

$ErrorActionPreference = 'Stop'

# Local delivery plan:
# 1. Snapshot tracked and non-ignored working-tree source, including the CloudStream submodule.
# 2. Run the desktop compile/tests and create the release-mode portable distribution (without clean).
# 3. Verify executable version and required notices, then create one portable ZIP.
# 4. Create one source ZIP from the current working tree and verify both archives.
# 5. Print exact paths, sizes, and SHA-256 hashes. Never publish or build an installer here.

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$versionLine = Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') |
    Where-Object { $_ -match '^APP_VERSION=' } |
    Select-Object -First 1
if ($null -eq $versionLine) { throw 'APP_VERSION was not found in gradle.properties.' }
$version = ($versionLine -split '=', 2)[1].Trim()
if ($version -notmatch '^\d+(?:\.\d+){2,3}$') { throw "Invalid APP_VERSION: $version" }

$distribution = Join-Path $repoRoot 'desktop-app/build/compose/binaries/main/app/Auras-Orbit'
$outputDirectory = Join-Path $repoRoot 'desktop-app/build/outputs'
$portableZip = Join-Path $outputDirectory "Auras-Orbit-Portable-$version.zip"
$sourceZip = Join-Path $outputDirectory "Auras-Orbit-Source-$version.zip"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

foreach ($archive in @($portableZip, $sourceZip)) {
    if ((Test-Path -LiteralPath $archive -PathType Leaf) -and -not $ForceArchives) {
        throw "Output already exists; move it aside or rerun with -ForceArchives: $archive"
    }
}

$sourceItems = [System.Collections.Generic.List[object]]::new()
$seenSourcePaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$excludedPath = '(^|/)(\.git|\.gradle|\.kotlin|build|out|dist|node_modules|\.idea|CloudStreamData|AurasData|AurasOrbitData|CloudStreamDesktop|AurasDesktop|AurasOrbit|shared_prefs)(/|$)'
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

$submoduleRoot = Join-Path $repoRoot 'android-reference'
if (Test-Path -LiteralPath (Join-Path $submoduleRoot '.git')) {
    $submoduleSourcePaths = @(& git -C $submoduleRoot ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate the CloudStream source submodule.' }
    foreach ($relativePath in $submoduleSourcePaths) {
        Add-SourceFile -BaseDirectory $submoduleRoot -RelativePath $relativePath -ArchivePrefix "Auras-Orbit-Source-$version/android-reference/"
    }
}

if ($sourceItems.Count -eq 0) { throw 'The source package file list is empty.' }
foreach ($requiredSource in @(
    "Auras-Orbit-Source-$version/gradlew.bat",
    "Auras-Orbit-Source-$version/desktop-app/build.gradle.kts",
    "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/include/WebView2.h",
    "Auras-Orbit-Source-$version/desktop-app/src/main/cpp/webview2/build/native/x64/WebView2Loader.dll",
    "Auras-Orbit-Source-$version/desktop-app/src/main/kotlin/com/lagradost/cloudstream3/desktop/ui/screens/home/HomeDiscovery.kt",
    "Auras-Orbit-Source-$version/.github/scripts/build-local-deliverables.ps1",
    "Auras-Orbit-Source-$version/android-reference/library/build.gradle.kts"
)) {
    if (-not ($sourceItems.ArchivePath -contains $requiredSource)) {
        throw "Required source file is absent from the source package plan: $requiredSource"
    }
}

$mpvDll = Join-Path $repoRoot 'desktop-app/appResources/windows/mpv/libmpv-2.dll'
$mpvHashExpected = '5C876D79E070529128331591B48F87846FB30557F19C11280DF9C6EE9B6DBAFA'
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
    & (Join-Path $repoRoot 'gradlew.bat') 'spotlessCheck' ':desktop-app:compileKotlin' ':desktop-app:compileTestKotlin' 'test' ':desktop-app:nativeTest' ':desktop-app:createDistributable' '-PorbitDistribution=release' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build/test failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

& (Join-Path $PSScriptRoot 'verify-distribution.ps1') -ExpectedVersion $version
if ($LASTEXITCODE -ne 0) { throw 'Portable distribution verification failed.' }

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
        "Auras-Orbit-Source-$version/android-reference/library/build.gradle.kts"
    )) {
        if ($sourceEntries -notcontains $requiredSource) { throw "Source ZIP is missing $requiredSource" }
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
foreach ($artifact in @($sourceZip, $portableZip)) {
    $file = Get-Item -LiteralPath $artifact
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    Write-Host ("Artifact: {0} | {1:N0} bytes | SHA-256 {2}" -f $file.FullName, $file.Length, $hash)
}
Write-Host ("Source package files: {0}" -f $sourceItems.Count)
