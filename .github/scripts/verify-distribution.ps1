param(
    [string] $ExpectedVersion,
    [switch] $RequireInstaller
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$distribution = Join-Path $repoRoot 'desktop-app/build/compose/binaries/main/app/Auras-Orbit'
$installerPath = Join-Path $repoRoot 'desktop-app/build/outputs/Auras-Orbit-Setup.exe'
$versionFile = Join-Path $repoRoot 'desktop-app/build/generated/installer/version.iss'

if ([string]::IsNullOrWhiteSpace($ExpectedVersion)) {
    $versionLine = Get-Content (Join-Path $repoRoot 'gradle.properties') |
        Where-Object { $_ -match '^APP_VERSION=' } |
        Select-Object -First 1
    if ($null -eq $versionLine) { throw 'APP_VERSION was not found in gradle.properties.' }
    $ExpectedVersion = ($versionLine -split '=', 2)[1].Trim()
}

$versionMatch = [regex]::Match((Get-Content -Raw -LiteralPath $versionFile), '#define\s+AppVersion\s+"([^"]+)"')
if (-not $versionMatch.Success -or $versionMatch.Groups[1].Value -ne $ExpectedVersion) {
    throw "Installer version.iss does not match expected version $ExpectedVersion."
}

function Get-NormalizedNumericVersion([string] $Value) {
    $match = [regex]::Match($Value, '\d+(?:\.\d+){2,3}')
    if (-not $match.Success) { return $null }
    return (($match.Value -split '\.') | ForEach-Object { [int]$_ }) -join '.'
}

function Assert-ExecutableVersion([string] $Path, [string] $Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Description is missing: $Path" }
    $versionInfo = (Get-Item -LiteralPath $Path).VersionInfo
    $reported = @($versionInfo.ProductVersion, $versionInfo.FileVersion) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $expectedNormalized = Get-NormalizedNumericVersion $ExpectedVersion
    $matchesExpected = $reported | ForEach-Object { Get-NormalizedNumericVersion $_ } |
        Where-Object { $_ -eq $expectedNormalized }
    if ($null -eq $expectedNormalized -or $null -eq $matchesExpected) {
        throw "$Description version metadata does not match $ExpectedVersion. Product='$($versionInfo.ProductVersion)' File='$($versionInfo.FileVersion)'"
    }
}

Assert-ExecutableVersion (Join-Path $distribution 'Auras-Orbit.exe') 'Portable app'

# The distribution is the exact tree copied into tester ZIPs and Windows installers.
# Fail closed if profile state, tracker credentials, or any other app-data payload
# ever appears in that tree.
if (-not (Test-Path -LiteralPath $distribution -PathType Container)) {
    throw "Portable distribution directory is missing: $distribution"
}
$privateDirectoryNames = @(
    'AurasOrbitData',
    'data', 'shared_prefs', 'profiles', 'Extensions', 'logs', 'downloads'
)
$privateFileName = '^(settings\.json|auth_tokens(?:\..*)?|tracker_credentials(?:\..*)?|.*\.(?:db|sqlite|sqlite3)(?:-shm|-wal)?)$'
$privateArtifacts = foreach ($item in Get-ChildItem -LiteralPath $distribution -Force -Recurse) {
    $relativePath = $item.FullName.Substring($distribution.Length).TrimStart('\', '/')
    $segments = $relativePath -split '[\\/]'
    $containsPrivateDirectory = @($segments | Where-Object { $privateDirectoryNames -contains $_ }).Count -gt 0
    $containsPrivateFile = -not $item.PSIsContainer -and $item.Name -match $privateFileName
    if ($containsPrivateDirectory -or $containsPrivateFile) { $relativePath }
}
if ($privateArtifacts) {
    throw "Private app data or tracker credentials must not be packaged. Remove these from the distribution and rebuild: $($privateArtifacts -join ', ')"
}

foreach ($legalFile in @(
    'AURAS-ORBIT-LICENSE.txt',
    'AURAS-ORBIT-NOTICE.txt',
    'THIRD-PARTY-NOTICES.txt',
    'CLOUDSTREAM-UPSTREAM-LICENSE.txt',
    'WEBVIEW2-SDK-LICENSE.txt',
    'WEBVIEW2-SDK-NOTICE.txt',
    'MPV-LICENSE.GPL.txt',
    'MPV-PROVENANCE.txt'
)) {
    $path = Join-Path $distribution "legal/$legalFile"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required packaged legal file is missing: $path" }
}

$nativeManifestPath = Join-Path $distribution 'app/resources/jni/native-build.json'
$nativeBinaryPath = Join-Path $distribution 'app/resources/jni/player_bridge.dll'
if (-not (Test-Path -LiteralPath $nativeManifestPath)) { throw 'Packaged native build manifest is missing.' }
$nativeManifest = Get-Content -Raw -LiteralPath $nativeManifestPath | ConvertFrom-Json
$sourceHash = (Get-FileHash -LiteralPath (Join-Path $repoRoot 'desktop-app/src/main/cpp/player_bridge.cpp') -Algorithm SHA256).Hash
$binaryHash = (Get-FileHash -LiteralPath $nativeBinaryPath -Algorithm SHA256).Hash
if ($sourceHash -ne $nativeManifest.sourceSha256 -or $binaryHash -ne $nativeManifest.binarySha256) {
    throw 'Packaged player bridge does not match the source build manifest.'
}

if ($RequireInstaller) {
    Assert-ExecutableVersion $installerPath 'Installer'
}

Write-Host "Verified Auras Orbit $ExpectedVersion distribution and legal inventory."
