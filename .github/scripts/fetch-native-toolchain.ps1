$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$toolRoot = Join-Path $repoRoot 'build/native-toolchain'
$compiler = Join-Path $toolRoot 'w64devkit/bin/g++.exe'
if (Test-Path -LiteralPath $compiler) { Write-Output $compiler; exit 0 }
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
$archive = Join-Path $toolRoot 'w64devkit-x64-2.10.0.7z.exe'
$expectedHash = '18d0a4c71a166f8401ab6305781bec5882b40b5e06ba9807c61cb5f3b3c6325e'
Invoke-WebRequest -Uri 'https://github.com/skeeto/w64devkit/releases/download/v2.10.0/w64devkit-x64-2.10.0.7z.exe' -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'Native toolchain checksum mismatch.' }
$sevenZip = (Get-Command 7z.exe -ErrorAction SilentlyContinue).Source
if (-not $sevenZip) { $sevenZip = 'C:\Program Files\7-Zip\7z.exe' }
if (-not (Test-Path -LiteralPath $sevenZip)) { throw '7-Zip is required to extract the pinned native toolchain.' }
& $sevenZip x $archive "-o$toolRoot" -y | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $compiler)) { throw 'Could not extract native toolchain.' }
Write-Output $compiler
