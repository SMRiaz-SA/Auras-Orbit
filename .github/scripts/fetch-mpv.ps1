$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mpvDirectory = Join-Path $repoRoot 'desktop-app/appResources/windows/mpv'
$legalDirectory = Join-Path $repoRoot 'desktop-app/appResources/legal'
$mpvDll = Join-Path $mpvDirectory 'libmpv-2.dll'
$mpvLicense = Join-Path $legalDirectory 'mpv-LICENSE.GPL.txt'
$mpvProvenance = Join-Path $legalDirectory 'MPV-PROVENANCE.txt'

$assetName = 'mpv-dev-x86_64-20260610-git-304426c.7z'
$assetUrl = 'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260610/mpv-dev-x86_64-20260610-git-304426c.7z'
$expectedArchiveSha256 = '8CBB25EA784F01AFBB3F904217CAB1317430A8BCFD5680FD827A866367F71CC9'
$expectedDllSha256 = '5C876D79E070529128331591B48F87846FB30557F19C11280DF9C6EE9B6DBAFA'
$mpvSourceCommit = '304426c39'
$licenseUrl = "https://raw.githubusercontent.com/mpv-player/mpv/$mpvSourceCommit/LICENSE.GPL"

New-Item -ItemType Directory -Force -Path $mpvDirectory, $legalDirectory | Out-Null
$installedDllHash = if (Test-Path -LiteralPath $mpvDll -PathType Leaf) {
    (Get-FileHash -LiteralPath $mpvDll -Algorithm SHA256).Hash.ToUpperInvariant()
} else {
    ''
}

if ($installedDllHash -ne $expectedDllSha256) {
    $temporaryRoot = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        [IO.Path]::GetTempPath()
    } else {
        $env:RUNNER_TEMP
    }
    $archivePath = Join-Path $temporaryRoot $assetName
    Invoke-WebRequest -Uri $assetUrl -OutFile $archivePath -ErrorAction Stop
    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($archiveHash -ne $expectedArchiveSha256) {
        throw "MPV archive SHA-256 mismatch: expected $expectedArchiveSha256, received $archiveHash."
    }

    $sevenZip = Get-Command 7z.exe -ErrorAction SilentlyContinue
    if ($null -ne $sevenZip) {
        $sevenZipPath = $sevenZip.Source
    } else {
        $sevenZipPath = Join-Path $env:ProgramFiles '7-Zip/7z.exe'
        if (-not (Test-Path -LiteralPath $sevenZipPath -PathType Leaf)) {
            throw '7-Zip is required to extract the pinned MPV runtime.'
        }
    }

    if (Test-Path -LiteralPath $mpvDll -PathType Leaf) {
        Remove-Item -LiteralPath $mpvDll -Force
    }
    & $sevenZipPath e $archivePath 'libmpv-2.dll' "-o$mpvDirectory" -r -y
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $mpvDll -PathType Leaf)) {
        throw 'Could not extract libmpv-2.dll from the pinned MPV archive.'
    }
}

$actualDllHash = (Get-FileHash -LiteralPath $mpvDll -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualDllHash -ne $expectedDllSha256) {
    throw "MPV DLL SHA-256 mismatch: expected $expectedDllSha256, received $actualDllHash."
}

Invoke-WebRequest -Uri $licenseUrl -OutFile $mpvLicense -ErrorAction Stop
if (-not (Select-String -LiteralPath $mpvLicense -Pattern 'GNU GENERAL PUBLIC LICENSE' -Quiet)) {
    throw 'The pinned MPV GPL license file was not downloaded correctly.'
}

$provenanceLines = @(
    'Auras Orbit MPV runtime provenance',
    "Vendor asset: $assetName",
    "Asset URL: $assetUrl",
    "Asset SHA-256: $expectedArchiveSha256",
    "Extracted libmpv-2.dll SHA-256: $expectedDllSha256",
    "mpv source commit: $mpvSourceCommit",
    "mpv source URL: https://github.com/mpv-player/mpv/tree/$mpvSourceCommit",
    "License: GNU GPL v2; license text: $licenseUrl",
    'Build recipe/provenance: https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20260610'
)
[IO.File]::WriteAllLines($mpvProvenance, $provenanceLines, [Text.UTF8Encoding]::new($false))

Write-Host "Verified pinned MPV asset and DLL: $assetName"
