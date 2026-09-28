$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$source = Join-Path $PSScriptRoot 'controls_url_policy_test.cpp'
$includeDir = Join-Path $PSScriptRoot '../../main/cpp'
$outputDir = Join-Path $repoRoot 'desktop-app/build/native/tests'
$executable = Join-Path $outputDir 'controls_url_policy_test.exe'

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$compiler = & (Join-Path $repoRoot '.github/scripts/fetch-native-toolchain.ps1')
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $compiler)) {
    throw 'Could not locate the pinned native toolchain compiler.'
}

& $compiler -std=c++17 -Wall -Wextra -Werror "-I$includeDir" $source -o $executable
if ($LASTEXITCODE -ne 0) {
    throw "Controls URL policy test compilation failed with exit code $LASTEXITCODE."
}

& $executable
if ($LASTEXITCODE -ne 0) {
    throw "Controls URL policy tests failed with exit code $LASTEXITCODE."
}

Write-Output 'Controls URL policy tests passed.'
