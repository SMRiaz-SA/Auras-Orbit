param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedVersion,

    [Parameter(Mandatory = $true)]
    [string] $ApkPath
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "Android APK was not found: $ApkPath"
}
$resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$metadataPath = Join-Path (Split-Path -Parent $resolvedApkPath) 'output-metadata.json'
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
    throw "Android APK metadata was not found: $metadataPath"
}

$metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
if ($metadata.variantName -ne 'stableRelease') {
    throw "Expected the stableRelease Android APK, received '$($metadata.variantName)'."
}
if ($metadata.applicationId -ne 'com.auras.orbit') {
    throw "Unexpected Android application ID: '$($metadata.applicationId)'."
}
$element = @($metadata.elements) |
    Where-Object { $_.outputFile -eq (Split-Path -Leaf $resolvedApkPath) } |
    Select-Object -First 1
if ($null -eq $element) { throw "APK is not listed in $metadataPath." }
if ($element.versionName -ne $ExpectedVersion) {
    throw "Android APK version '$($element.versionName)' does not match expected version '$ExpectedVersion'."
}

$versionParts = @($ExpectedVersion.Split('.') | ForEach-Object { [int] $_ })
if ($versionParts.Count -eq 3) { $versionParts += 0 }
if ($versionParts.Count -ne 4) { throw "Unsupported Android version format: $ExpectedVersion" }
$expectedVersionCode = [long] (
    $versionParts[0] * 100000000 +
    $versionParts[1] * 1000000 +
    $versionParts[2] * 10000 +
    $versionParts[3]
)
if ([long] $element.versionCode -ne $expectedVersionCode) {
    throw "Android APK versionCode '$($element.versionCode)' does not match expected versionCode '$expectedVersionCode'."
}

$androidSdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($androidSdk) -or -not (Test-Path -LiteralPath $androidSdk -PathType Container)) {
    $androidSdk = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($androidSdk) -or -not (Test-Path -LiteralPath $androidSdk -PathType Container)) {
    throw 'Android SDK was not found while verifying the APK signature.'
}
$apksigner = Get-ChildItem -LiteralPath (Join-Path $androidSdk 'build-tools') -Filter 'apksigner.bat' -Recurse -File |
    Sort-Object { [version] (Split-Path -Leaf (Split-Path -Parent $_.FullName)) } -Descending |
    Select-Object -First 1
if ($null -eq $apksigner) { throw 'Android apksigner was not found in the installed SDK.' }
& $apksigner.FullName verify --verbose $resolvedApkPath
if ($LASTEXITCODE -ne 0) { throw "Android APK signature verification failed with exit code $LASTEXITCODE." }

Write-Host "Android APK verified: $resolvedApkPath (version $ExpectedVersion, versionCode $expectedVersionCode)."
