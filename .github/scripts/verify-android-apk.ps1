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
$expectedApplicationId = switch ($metadata.variantName) {
    'stableRelease' { 'com.auras.orbit' }
    default { throw "Expected a stable Android APK, received '$($metadata.variantName)'." }
}
if ($metadata.applicationId -ne $expectedApplicationId) {
    throw "Unexpected Android application ID for $($metadata.variantName): '$($metadata.applicationId)'."
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
$aapt = Get-ChildItem -LiteralPath (Join-Path $androidSdk 'build-tools') -Filter 'aapt.exe' -Recurse -File |
    Sort-Object { [version] (Split-Path -Leaf (Split-Path -Parent $_.FullName)) } -Descending |
    Select-Object -First 1
if ($null -eq $aapt) { throw 'Android aapt was not found in the installed SDK.' }

$badging = @(& $aapt.FullName dump badging $resolvedApkPath)
if ($LASTEXITCODE -ne 0) { throw "Could not read the Android APK manifest with aapt (exit code $LASTEXITCODE)." }
$packageLine = $badging |
    Where-Object { $_ -match "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'" } |
    Select-Object -First 1
if ($null -eq $packageLine) { throw 'The Android APK manifest did not contain package metadata.' }
if ($packageLine -notmatch "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'") {
    throw 'Could not parse package metadata from the Android APK manifest.'
}
$actualApplicationId = $Matches[1]
$actualVersionCode = [long] $Matches[2]
$actualVersionName = $Matches[3]
if ($actualApplicationId -ne $expectedApplicationId) {
    throw "Android APK manifest application ID '$actualApplicationId' does not match expected '$expectedApplicationId'."
}
if ($actualApplicationId -ne $metadata.applicationId -or
    $actualVersionName -ne $element.versionName -or
    $actualVersionCode -ne [long] $element.versionCode) {
    throw 'Android APK manifest metadata does not match Gradle output-metadata.json.'
}
if ($actualVersionName -ne $ExpectedVersion) {
    throw "Android APK manifest version '$actualVersionName' does not match expected version '$ExpectedVersion'."
}
if ($actualVersionCode -ne $expectedVersionCode) {
    throw "Android APK manifest versionCode '$actualVersionCode' does not match expected versionCode '$expectedVersionCode'."
}

foreach ($requiredSigningVariable in @(
    'AURAS_RELEASE_STORE_FILE',
    'AURAS_RELEASE_STORE_PASSWORD',
    'AURAS_RELEASE_KEY_ALIAS',
    'AURAS_RELEASE_KEY_PASSWORD'
)) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredSigningVariable))) {
        throw "Expected Android release signing configuration in $requiredSigningVariable."
    }
}
$releaseStoreFile = [Environment]::GetEnvironmentVariable('AURAS_RELEASE_STORE_FILE')
if (-not (Test-Path -LiteralPath $releaseStoreFile -PathType Leaf)) {
    throw 'AURAS_RELEASE_STORE_FILE does not point to an existing signing keystore.'
}
$certificates = [System.Security.Cryptography.X509Certificates.X509Certificate2Collection]::new()
$certificates.Import(
    $releaseStoreFile,
    [Environment]::GetEnvironmentVariable('AURAS_RELEASE_STORE_PASSWORD'),
    [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
)
$privateKeyCertificates = @($certificates | Where-Object { $_.HasPrivateKey })
if ($privateKeyCertificates.Count -ne 1) {
    throw "Expected one private-key certificate in the Android signing keystore, found $($privateKeyCertificates.Count)."
}
$expectedSignerDigest = $privateKeyCertificates[0].GetCertHashString(
    [System.Security.Cryptography.HashAlgorithmName]::SHA256
).ToLowerInvariant()

$signatureOutput = @(& $apksigner.FullName verify --print-certs $resolvedApkPath)
if ($LASTEXITCODE -ne 0) { throw "Android APK signature verification failed with exit code $LASTEXITCODE." }
$signatureText = [string]::Join([Environment]::NewLine, $signatureOutput)
$signerMatch = [regex]::Match(
    $signatureText,
    'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)'
)
if (-not $signerMatch.Success) { throw 'Could not read the APK signer certificate digest from apksigner.' }
$actualSignerDigest = $signerMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant()
if ($actualSignerDigest -ne $expectedSignerDigest) {
    throw "Android APK signer certificate '$actualSignerDigest' does not match the configured release keystore."
}

function Get-AndroidElfLoadSegments([System.IO.Compression.ZipArchiveEntry] $Entry) {
    $entryStream = $Entry.Open()
    $memoryStream = [System.IO.MemoryStream]::new()
    try {
        $entryStream.CopyTo($memoryStream)
    } finally {
        $entryStream.Dispose()
    }
    $memoryStream.Position = 0
    $reader = [System.IO.BinaryReader]::new($memoryStream)
    try {
        $ident = $reader.ReadBytes(16)
        if ($ident.Length -ne 16 -or
            $ident[0] -ne 0x7f -or $ident[1] -ne 0x45 -or $ident[2] -ne 0x4c -or $ident[3] -ne 0x46) {
            throw "Native library is not an ELF file: $($Entry.FullName)."
        }
        if ($ident[4] -ne 2 -or $ident[5] -ne 1) {
            throw "Expected a little-endian 64-bit ELF library: $($Entry.FullName)."
        }

        $reader.BaseStream.Position = 18
        $machine = $reader.ReadUInt16()
        $expectedMachine = if ($Entry.FullName.StartsWith('lib/arm64-v8a/')) { 183 } else { 62 }
        if ($machine -ne $expectedMachine) {
            throw "Unexpected ELF machine '$machine' for $($Entry.FullName)."
        }

        $reader.BaseStream.Position = 32
        $programHeaderOffset = $reader.ReadUInt64()
        $reader.BaseStream.Position = 54
        $programHeaderSize = $reader.ReadUInt16()
        $programHeaderCount = $reader.ReadUInt16()
        if ($programHeaderSize -lt 56 -or $programHeaderCount -eq 0) {
            throw "ELF program headers are missing or malformed: $($Entry.FullName)."
        }
        if (($programHeaderOffset + ([uint64] $programHeaderSize * $programHeaderCount)) -gt [uint64] $reader.BaseStream.Length) {
            throw "ELF program headers extend beyond the library: $($Entry.FullName)."
        }

        $loadSegments = [System.Collections.Generic.List[object]]::new()
        for ($index = 0; $index -lt $programHeaderCount; $index++) {
            $headerOffset = [long] ($programHeaderOffset + ([uint64] $programHeaderSize * $index))
            $reader.BaseStream.Position = $headerOffset
            $type = $reader.ReadUInt32()
            if ($type -ne 1) { continue }

            $reader.BaseStream.Position = $headerOffset + 8
            $fileOffset = $reader.ReadUInt64()
            $virtualAddress = $reader.ReadUInt64()
            $reader.BaseStream.Position = $headerOffset + 48
            $alignment = $reader.ReadUInt64()
            $loadSegments.Add([pscustomobject] @{
                FileOffset = $fileOffset
                VirtualAddress = $virtualAddress
                Alignment = $alignment
            })
        }

        if ($loadSegments.Count -eq 0) {
            throw "ELF library has no LOAD segments: $($Entry.FullName)."
        }
        return $loadSegments.ToArray()
    } finally {
        $reader.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApkPath)
try {
    $verifiedNativeLibraryCount = 0
    foreach ($abi in @('arm64-v8a', 'x86_64')) {
        $nativeLibraries = @($archive.Entries | Where-Object {
            $_.FullName -match "^lib/$([regex]::Escape($abi))/.+\.so$"
        })
        if ($nativeLibraries.Count -eq 0) {
            throw "The stable APK has no native libraries for required 64-bit ABI '$abi'."
        }

        foreach ($nativeLibrary in $nativeLibraries) {
            $loadSegments = @(Get-AndroidElfLoadSegments $nativeLibrary)
            foreach ($segment in $loadSegments) {
                if ($segment.Alignment -lt 16384) {
                    throw "Unaligned 16 KB LOAD segment in $($nativeLibrary.FullName): alignment $($segment.Alignment)."
                }
                if (($segment.FileOffset % $segment.Alignment) -ne ($segment.VirtualAddress % $segment.Alignment)) {
                    throw "Inconsistent LOAD segment alignment in $($nativeLibrary.FullName)."
                }
            }
            $verifiedNativeLibraryCount++
        }
    }
} finally {
    $archive.Dispose()
}

$zipalign = Get-ChildItem -LiteralPath (Join-Path $androidSdk 'build-tools') -Filter 'zipalign.exe' -Recurse -File |
    Sort-Object { [version] (Split-Path -Leaf (Split-Path -Parent $_.FullName)) } -Descending |
    Select-Object -First 1
if ($null -eq $zipalign) { throw 'Android zipalign was not found in the installed SDK.' }
& $zipalign.FullName -c -P 16 4 $resolvedApkPath *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Android APK 16 KB ZIP alignment verification failed with exit code $LASTEXITCODE."
}
Write-Host "16 KB alignment verified for $verifiedNativeLibraryCount 64-bit native libraries and the APK ZIP layout."

$apkSha256 = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash
Write-Host "Android APK verified: $resolvedApkPath (package $actualApplicationId, version $actualVersionName, versionCode $actualVersionCode, signer $actualSignerDigest, SHA-256 $apkSha256)."
