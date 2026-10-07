$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mpvDirectory = Join-Path $repoRoot 'desktop-app/appResources/windows/mpv'
$legalDirectory = Join-Path $repoRoot 'desktop-app/appResources/legal'
$mpvDll = Join-Path $mpvDirectory 'libmpv-2.dll'
$mpvLicense = Join-Path $legalDirectory 'mpv-LICENSE.GPL.txt'
$mpvProvenance = Join-Path $legalDirectory 'MPV-PROVENANCE.txt'

$assetReleaseTag = '20261007'
$assetName = 'mpv-dev-x86_64-20261007-git-eb0ee10315.7z'
$assetUrl = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$assetReleaseTag/$assetName"
$expectedArchiveSha256 = '3FD93055D437310AD094D3F80C9B04F9A56E8B138BCB7D9C5CF01870ABBFE5A5'
$expectedDllSha256 = '872827614ED0ADFCA11E68DEF5273BCFCAEA6ACF38BBF1950C35980B59F43A5F'
$mpvSourceCommit = 'eb0ee1031590b3f369a5b783e8aa91eeaaded7e6'
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
    "Build recipe/provenance: https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/$assetReleaseTag"
)
[IO.File]::WriteAllLines($mpvProvenance, $provenanceLines, [Text.UTF8Encoding]::new($false))

Write-Host "Verified pinned MPV asset and DLL: $assetName"
