param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedVersion,

    [string] $Repository = 'SMRiaz-SA/Auras-Orbit'
)

$ErrorActionPreference = 'Stop'

function ConvertTo-LegacyAndroidVersionCode([string] $Version) {
    if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?$') {
        throw "Expected a numeric Android release version, received '$Version'."
    }

    $major = [long] $Matches[1]
    $minor = [long] $Matches[2]
    $patch = [long] $Matches[3]
    $build = if ($Matches[4]) { [long] $Matches[4] } else { 0L }
    if ($major -gt 21 -or $minor -gt 99 -or $patch -gt 99 -or $build -gt 9999) {
        throw "Android version '$Version' exceeds the legacy updater's accepted component bounds."
    }

    $code = $major * 100000000L + $minor * 1000000L + $patch * 10000L + $build
    if ($code -gt 2100000000L) {
        throw "Android version '$Version' exceeds the limit enforced by the Android release build and legacy updater."
    }
    return $code
}

$candidateCode = ConvertTo-LegacyAndroidVersionCode $ExpectedVersion
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$desktopPropertiesPath = Join-Path $repoRoot 'gradle.properties'
if (-not (Test-Path -LiteralPath $desktopPropertiesPath -PathType Leaf)) {
    throw "Desktop Gradle properties were not found at '$desktopPropertiesPath'."
}
$desktopVersionLines = @(Get-Content -LiteralPath $desktopPropertiesPath |
    Where-Object { $_ -cmatch '^APP_VERSION=' })
if ($desktopVersionLines.Count -ne 1) {
    throw "Expected one APP_VERSION entry in '$desktopPropertiesPath'; found $($desktopVersionLines.Count)."
}
$desktopVersion = ($desktopVersionLines[0] -split '=', 2)[1].Trim()
if ($ExpectedVersion -cne $desktopVersion) {
    throw "Android target '$ExpectedVersion' must match desktop APP_VERSION '$desktopVersion'."
}

$androidPropertiesPath = Join-Path $repoRoot 'android\gradle.properties'
if (-not (Test-Path -LiteralPath $androidPropertiesPath -PathType Leaf)) {
    throw "Android Gradle properties were not found at '$androidPropertiesPath'."
}
$androidVersionLines = @(Get-Content -LiteralPath $androidPropertiesPath |
    Where-Object { $_ -cmatch '^ORBIT_RELEASE_VERSION_NAME=' })
if ($androidVersionLines.Count -ne 1) {
    throw "Expected one ORBIT_RELEASE_VERSION_NAME entry in '$androidPropertiesPath'; found $($androidVersionLines.Count)."
}
$androidVersion = ($androidVersionLines[0] -split '=', 2)[1].Trim()
if ($ExpectedVersion -cne $androidVersion) {
    throw "Android target '$ExpectedVersion' must match android/gradle.properties ORBIT_RELEASE_VERSION_NAME '$androidVersion'."
}

$legacyVersionLines = @(Get-Content -LiteralPath $desktopPropertiesPath |
    Where-Object { $_ -cmatch '^LEGACY_ANDROID_APP_VERSION=' })
if ($legacyVersionLines.Count -ne 1) {
    throw "Expected one LEGACY_ANDROID_APP_VERSION entry in '$desktopPropertiesPath'; found $($legacyVersionLines.Count)."
}
$legacyVersion = ($legacyVersionLines[0] -split '=', 2)[1].Trim()
$legacyCode = ConvertTo-LegacyAndroidVersionCode $legacyVersion
if ($candidateCode -le $legacyCode) {
    throw "Android release '$ExpectedVersion' (versionCode $candidateCode) must be greater than the explicit legacy Android reference version '$legacyVersion' (versionCode $legacyCode)."
}

$headers = @{
    Accept = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent' = 'Auras-Orbit-release-version-gate'
}
if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
    $headers.Authorization = "Bearer $env:GITHUB_TOKEN"
}

$releaseUri = "https://api.github.com/repos/$Repository/releases?per_page=100"
$highestPublishedCode = -1L
$highestPublishedVersion = $null
$pageCount = 0
while (-not [string]::IsNullOrWhiteSpace($releaseUri)) {
    $pageCount++
    if ($pageCount -gt 100) { throw 'GitHub release pagination exceeded the safety limit.' }

    $response = Invoke-WebRequest -Uri $releaseUri -Headers $headers -Method Get
    $releases = @($response.Content | ConvertFrom-Json)
    foreach ($release in $releases) {
        if ($release.prerelease -or $release.draft) { continue }

        $publishedVersions = [System.Collections.Generic.List[string]]::new()
        if ($release.tag_name -match '^v?(\d+\.\d+\.\d+(?:\.\d+)?)$') {
            $publishedVersions.Add($Matches[1])
        }
        foreach ($asset in @($release.assets)) {
            if ($asset.name -match '^(\d+\.\d+\.\d+(?:\.\d+)?)\.apk$') {
                $publishedVersions.Add($Matches[1])
            }
        }

        foreach ($publishedVersion in $publishedVersions) {
            try {
                $publishedCode = ConvertTo-LegacyAndroidVersionCode $publishedVersion
            } catch {
                # Ignore unrelated historical tags/assets outside this updater's
                # supported version range; the updater itself would ignore them.
                continue
            }
            if ($publishedCode -gt $highestPublishedCode) {
                $highestPublishedCode = $publishedCode
                $highestPublishedVersion = $publishedVersion
            }
        }
    }

    $linkHeader = [string] $response.Headers['Link']
    $nextLink = [regex]::Match($linkHeader, '<([^>]+)>\s*;\s*rel="next"')
    $releaseUri = if ($nextLink.Success) { $nextLink.Groups[1].Value } else { $null }
}

if ($highestPublishedCode -lt 0) {
    throw "Could not find a published stable version in https://github.com/$Repository/releases."
}
if ($candidateCode -le $highestPublishedCode) {
    throw "Android release '$ExpectedVersion' (versionCode $candidateCode) must be greater than the highest published stable release '$highestPublishedVersion' (versionCode $highestPublishedCode)."
}

Write-Host "Android release version gate passed: $ExpectedVersion ($candidateCode) matches desktop APP_VERSION and ORBIT_RELEASE_VERSION_NAME, is higher than legacy Android reference $legacyVersion ($legacyCode), and is higher than published stable $highestPublishedVersion ($highestPublishedCode)."
