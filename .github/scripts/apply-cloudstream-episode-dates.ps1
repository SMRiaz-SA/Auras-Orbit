$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$submoduleRoot = Join-Path $repoRoot 'android-reference'
$patchPath = Join-Path $repoRoot '.github/patches/cloudstream-episode-dates.patch'

if (-not (Test-Path -LiteralPath (Join-Path $submoduleRoot 'library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt'))) {
    throw 'CloudStream source is missing. Clone with submodules enabled or extract the complete source package.'
}
if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) {
    throw "The pinned CloudStream date patch is missing: $patchPath"
}

& git -C $submoduleRoot apply --unidiff-zero --reverse --check $patchPath 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host 'CloudStream episode date fixes are already applied.'
    return
}

& git -C $submoduleRoot apply --unidiff-zero --check $patchPath
if ($LASTEXITCODE -ne 0) {
    throw 'The pinned CloudStream date patch does not apply cleanly to the checked-out submodule revision.'
}

& git -C $submoduleRoot apply --unidiff-zero $patchPath
if ($LASTEXITCODE -ne 0) {
    throw 'Could not apply the pinned CloudStream date patch.'
}

Write-Host 'Applied the app-pinned CloudStream episode date fixes.'
