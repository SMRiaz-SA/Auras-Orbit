[CmdletBinding()]
param(
    [string]$PreReleaseCheckout,
    [string]$StableCheckout,
    [string]$EvidenceDirectory,
    [string]$AndroidSdk,
    [string]$CorpusManifest
)

$ErrorActionPreference = 'Stop'
$PreReleaseCommit = '5024ace2922a148d38df2a816b483b529789cf58'
$StableCommit = '3796406e636356f556c16e04e1da4e37d6a19038'
$Variant = 'prereleaseDebug'
$tab = [char]9

if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $PSScriptRoot '..\..\artifacts\cs3-compatibility\2026-10-31' }
$EvidenceDirectory = [System.IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
$runId = [guid]::NewGuid().ToString('D')
$runStartedAt = (Get-Date).ToString('o')
$runStatusPath = Join-Path $EvidenceDirectory 'run-status.json'

function Write-RunStatus([string]$Status, [string]$ErrorMessage = $null, [string]$ErrorType = $null, [string]$ErrorStack = $null, [string]$ProvenanceSha256 = $null, [object[]]$Outputs = @()) {
    $now = (Get-Date).ToString('o')
    $document = [ordered]@{
        schemaVersion = 1
        runId = $runId
        status = $Status
        startedAt = $runStartedAt
        updatedAt = $now
        completedAt = $(if ($Status -in @('complete', 'failed')) { $now } else { $null })
        error = $(if ($ErrorMessage) { [ordered]@{ type = $ErrorType; message = $ErrorMessage; stack = $ErrorStack } } else { $null })
        provenance = $(if ($ProvenanceSha256) { [ordered]@{ path = (Join-Path $EvidenceDirectory 'inputs-and-tools.json'); sha256 = $ProvenanceSha256 } } else { $null })
        outputs = @($Outputs)
    }
    $json = $document | ConvertTo-Json -Depth 8
    $temporaryPath = "$runStatusPath.$runId.tmp"
    [System.IO.File]::WriteAllText($temporaryPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryPath -Destination $runStatusPath -Force
}

Write-RunStatus 'running'

try {
$codexRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..\..\..')).Path
if (-not $PreReleaseCheckout) { $PreReleaseCheckout = Join-Path $codexRoot 'cloudstream-cs3-closure-study' }
if (-not $StableCheckout) { $StableCheckout = Join-Path $codexRoot 'cloudstream-stable-reference' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME; if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT } }
if (-not $AndroidSdk) { throw 'Pass -AndroidSdk or set ANDROID_HOME/ANDROID_SDK_ROOT for the selected Android compile APIs.' }

$PreReleaseCheckout = (Resolve-Path $PreReleaseCheckout).Path
$StableCheckout = (Resolve-Path $StableCheckout).Path
$AndroidSdk = (Resolve-Path $AndroidSdk).Path
$env:ANDROID_HOME = $AndroidSdk

function Get-Head([string]$Checkout) {
    $value = (& git -C $Checkout rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Could not read source HEAD in $Checkout" }
    return $value
}

function Assert-Head([string]$Checkout, [string]$Expected, [string]$Label) {
    $actual = Get-Head $Checkout
    if ($actual -ne $Expected) { throw "$Label checkout is $actual; expected pinned commit $Expected" }
    return $actual
}

function Invoke-Gradle([string]$Checkout, [string[]]$Arguments) {
    Push-Location $Checkout
    try {
        & .\gradlew.bat @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed in $Checkout with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

function Get-AppClassesJar([string]$Checkout) {
    return Join-Path $Checkout 'app\build\intermediates\compile_app_classes_jar\prereleaseDebug\bundlePrereleaseDebugClassesToCompileJar\classes.jar'
}

function Get-LibraryJvmJar([string]$Checkout) {
    $matches = @(Get-ChildItem (Join-Path $Checkout 'library\build\libs') -Filter 'library-jvm-*.jar' -File -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { return $null }
    return $matches[0].FullName
}

function Write-ExternalCompileInputs([string]$Checkout, [string]$OutputPath, [string]$DiagnosticsPath) {
    $initScript = Join-Path $PSScriptRoot 'dump_compile_inputs.gradle'
    $gradleArgs = @(
        '-I', $initScript,
        ':app:dumpCs3CrosswalkCompileInputs',
        "-Pcs3CrosswalkOutput=$OutputPath",
        "-Pcs3CrosswalkDiagnostics=$DiagnosticsPath",
        "-Pcs3CrosswalkConfiguration=$($Variant)CompileClasspath",
        '--no-configuration-cache'
    )
    Invoke-Gradle $Checkout $gradleArgs
}

function Write-SurfaceManifest([string]$Output, [string]$AppJar, [string]$LibraryJar, [string]$Prefix, [string]$ExternalManifest) {
    $rows = [System.Collections.Generic.List[string]]::new()
    $rows.Add("app$tab$Prefix app compile classes$tab$AppJar")
    $rows.Add("library$tab$Prefix library JVM JAR$tab$LibraryJar")
    foreach ($line in [System.IO.File]::ReadAllLines($ExternalManifest)) {
        if ($line) { $rows.Add($line) }
    }
    [System.IO.File]::WriteAllLines($Output, $rows, [System.Text.UTF8Encoding]::new($false))
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-NoReparsePointPath([string]$Path, [string]$Label) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $volumeRoot = [System.IO.Path]::GetPathRoot($fullPath)
    if (-not $volumeRoot) { throw "$Label is not an absolute filesystem path: $fullPath" }
    $currentPath = $volumeRoot
    $volumeItem = Get-Item -LiteralPath $currentPath -Force -ErrorAction Stop
    if (($volumeItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label traverses a reparse point: $currentPath" }
    $segments = $fullPath.Substring($volumeRoot.Length).Split([char[]]@([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar), [System.StringSplitOptions]::RemoveEmptyEntries)
    foreach ($segment in $segments) {
        $currentPath = Join-Path $currentPath $segment
        $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue
        if ($null -eq $item) { continue }
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label traverses a reparse point: $currentPath" }
    }
}

function Resolve-CorpusPath([string]$Root, [string]$RelativePath, [string]$Label) {
    if ([System.IO.Path]::IsPathRooted($RelativePath)) { throw "$Label must be relative to the corpus manifest directory: $RelativePath" }
    $rootFull = [System.IO.Path]::GetFullPath($Root)
    if (-not [System.IO.Directory]::Exists($rootFull)) { throw "Corpus root does not exist as a directory: $rootFull" }
    Assert-NoReparsePointPath $rootFull 'Corpus root'
    $rootTrimmed = $rootFull.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    $volumeTrimmed = [System.IO.Path]::GetPathRoot($rootFull).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if ($rootTrimmed -eq $volumeTrimmed) { throw 'Corpus root cannot be a filesystem volume root.' }
    $rootFull = $rootTrimmed
    $full = [System.IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $prefix = $rootFull + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw "$Label escapes the corpus manifest directory: $RelativePath" }
    Assert-NoReparsePointPath $full $Label
    return $full
}

function Copy-CorpusDexSafely([string]$ArchivePath, [string]$DexOutputPath, [string]$Label) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'classes.dex' })
        if ($entries.Count -ne 1) { throw "$Label must contain exactly one root classes.dex entry; found $($entries.Count)." }
        $entry = $entries[0]
        if ($entry.Length -le 0 -or $entry.Length -gt 268435456) { throw "$Label DEX entry has an invalid size: $($entry.Length) bytes." }
        $parent = Split-Path -Parent $DexOutputPath
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
        Assert-NoReparsePointPath $parent "$Label extracted DEX parent"
        Assert-NoReparsePointPath $DexOutputPath "$Label extracted DEX target"
        $input = $entry.Open()
        $output = [System.IO.File]::Open($DexOutputPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
        $actualLength = (Get-Item -LiteralPath $DexOutputPath).Length
        if ($actualLength -ne $entry.Length) { throw "$Label extracted DEX length mismatch: ZIP=$($entry.Length), extracted=$actualLength." }
        return [ordered]@{ path = $DexOutputPath; bytes = $actualLength; sha256 = Get-Sha256 $DexOutputPath }
    } finally {
        $archive.Dispose()
    }
}

function Write-HashedExternalInputs([string]$Manifest, [string]$Output) {
    $rows = [System.Collections.Generic.List[string]]::new()
    $rows.Add("scope-and-coordinate$tab" + "bytes$tab" + "sha256$tab" + "path")
    foreach ($line in [System.IO.File]::ReadAllLines($Manifest)) {
        if (-not $line.StartsWith("external$tab")) { continue }
        $parts = $line.Split($tab, 3)
        if ($parts.Count -ne 3) { throw "Invalid external input row in $Manifest" }
        $item = Get-Item -LiteralPath $parts[2]
        $rows.Add($parts[1] + $tab + $item.Length + $tab + (Get-Sha256 $parts[2]) + $tab + $parts[2])
    }
    [System.IO.File]::WriteAllLines($Output, $rows, [System.Text.UTF8Encoding]::new($false))
}

function Get-VersionOutput([string]$Executable, [string[]]$Arguments) {
    $lines = @(& $Executable @Arguments 2>&1)
    return ($lines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
}

$preAppOnly = Join-Path $EvidenceDirectory 'prerelease-5024ace-app-only-classpath.tsv'
$stableAppOnly = Join-Path $EvidenceDirectory 'stable-v4.8.0-app-only-classpath.tsv'
if (-not (Test-Path -LiteralPath $preAppOnly) -or -not (Test-Path -LiteralPath $stableAppOnly)) {
    throw 'Preserved app-only classpath inventories are missing; keep them beside the app+library JVM union manifests.'
}

$preHead = Assert-Head $PreReleaseCheckout $PreReleaseCommit 'Pre-release'
$stableHead = Assert-Head $StableCheckout $StableCommit 'Stable'

$stableAppJar = Get-AppClassesJar $StableCheckout
$stableLibraryJar = Get-LibraryJvmJar $StableCheckout
if (-not (Test-Path -LiteralPath $stableAppJar) -or -not $stableLibraryJar) {
    Write-Host 'Stable static compile outputs are missing; generating only app compile classes and library JVM inputs.'
    Invoke-Gradle $StableCheckout @(
        ':app:bundlePrereleaseDebugClassesToCompileJar',
        ':library:jvmJar',
        '--no-configuration-cache'
    )
    $stableLibraryJar = Get-LibraryJvmJar $StableCheckout
}
if (-not (Test-Path -LiteralPath $stableAppJar)) { throw "Stable app compile-classes JAR is missing: $stableAppJar" }
if (-not $stableLibraryJar) { throw 'Expected exactly one stable library-jvm-*.jar output.' }

$preAppJar = Get-AppClassesJar $PreReleaseCheckout
$preLibraryJar = Get-LibraryJvmJar $PreReleaseCheckout
if (-not (Test-Path -LiteralPath $preAppJar) -or -not $preLibraryJar) {
    throw 'The existing pre-release app compile-classes or library JVM input is missing; this runner reuses those pinned study outputs.'
}

$preExternal = Join-Path $EvidenceDirectory 'prerelease-5024ace-classpath.tsv'
$stableExternal = Join-Path $EvidenceDirectory 'stable-v4.8.0-classpath.tsv'
$preResolutionDiagnostics = Join-Path $EvidenceDirectory 'prerelease-5024ace-resolution-diagnostics.json'
$stableResolutionDiagnostics = Join-Path $EvidenceDirectory 'stable-v4.8.0-resolution-diagnostics.json'
Write-ExternalCompileInputs $PreReleaseCheckout $preExternal $preResolutionDiagnostics
Write-ExternalCompileInputs $StableCheckout $stableExternal $stableResolutionDiagnostics
$preResolutionStatus = Get-Content $preResolutionDiagnostics -Raw | ConvertFrom-Json
$stableResolutionStatus = Get-Content $stableResolutionDiagnostics -Raw | ConvertFrom-Json
$preAppOnlyHashes = Join-Path $EvidenceDirectory 'prerelease-5024ace-app-only-artifact-hashes.tsv'
$stableAppOnlyHashes = Join-Path $EvidenceDirectory 'stable-v4.8.0-app-only-artifact-hashes.tsv'
$preUnionHashes = Join-Path $EvidenceDirectory 'prerelease-5024ace-union-artifact-hashes.tsv'
$stableUnionHashes = Join-Path $EvidenceDirectory 'stable-v4.8.0-union-artifact-hashes.tsv'
Write-HashedExternalInputs $preAppOnly $preAppOnlyHashes
Write-HashedExternalInputs $stableAppOnly $stableAppOnlyHashes
Write-HashedExternalInputs $preExternal $preUnionHashes
Write-HashedExternalInputs $stableExternal $stableUnionHashes

$preSurface = Join-Path $EvidenceDirectory 'pre-release-5024ace-surface.tsv'
$stableSurface = Join-Path $EvidenceDirectory 'stable-v4.8.0-surface.tsv'
Write-SurfaceManifest $preSurface $preAppJar $preLibraryJar 'pre-release' $preExternal
Write-SurfaceManifest $stableSurface $stableAppJar $stableLibraryJar 'stable-v4.8.0' $stableExternal

$analysisDir = Join-Path $env:TEMP ('cs3-crosswalk-analyzer-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $analysisDir | Out-Null
$analyzerSource = Join-Path $PSScriptRoot 'Cs3StaticCrosswalk.java'
& javac -encoding UTF-8 -d $analysisDir $analyzerSource
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the static crosswalk analyzer.' }

$inputRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\build\cs3-crosswalk')).Path
$plugins = @(
    @{
        Name = 'MegaProvider'
        Key = 'megaprovider'
        Archive = Join-Path $inputRoot 'plugin-f559adf4fc84924b9da2.cs3'
        Dex = Join-Path $inputRoot 'plugin-f559adf4fc84924b9da2.classes.dex'
    },
    @{
        Name = 'StreamPlay'
        Key = 'streamplay'
        Archive = Join-Path $inputRoot 'plugin-6da110846c7db7fdc022.cs3'
        Dex = Join-Path $inputRoot 'plugin-6da110846c7db7fdc022.classes.dex'
    }
)
$baselines = @(
    @{ Key = 'pre-release-5024ace'; Surface = $preSurface },
    @{ Key = 'stable-v4.8.0'; Surface = $stableSurface }
)

$corpusManifestData = $null
$corpusRoot = $null
$corpusIndexMetadata = @()
$corpusPackages = @()
if ($CorpusManifest) {
    $CorpusManifest = [System.IO.Path]::GetFullPath($CorpusManifest)
    Assert-NoReparsePointPath $CorpusManifest 'Corpus manifest'
    if (-not [System.IO.File]::Exists($CorpusManifest)) { throw "Corpus manifest does not exist: $CorpusManifest" }
    $corpusRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $CorpusManifest))
    if (-not [System.IO.Directory]::Exists($corpusRoot)) { throw "Corpus root does not exist as a directory: $corpusRoot" }
    Assert-NoReparsePointPath $corpusRoot 'Corpus root'
    $corpusManifestData = Get-Content -LiteralPath $CorpusManifest -Raw | ConvertFrom-Json
    if ($corpusManifestData.schemaVersion -ne 1) { throw "Unsupported corpus manifest schema: $($corpusManifestData.schemaVersion)" }
    if (@($corpusManifestData.artifacts).Count -eq 0) { throw 'The corpus manifest contains no artifacts.' }

    $indexSnapshots = @{}
    foreach ($snapshot in $corpusManifestData.indexSnapshots) {
        if (-not $snapshot.id -or $indexSnapshots.ContainsKey([string]$snapshot.id)) { throw "Missing or duplicate corpus index snapshot id: $($snapshot.id)" }
        $snapshotPath = Resolve-CorpusPath $corpusRoot ([string]$snapshot.path) "Index snapshot $($snapshot.id)"
        if (-not (Test-Path -LiteralPath $snapshotPath -PathType Leaf)) { throw "Corpus index snapshot is missing: $snapshotPath" }
        $snapshotHash = Get-Sha256 $snapshotPath
        if ($snapshotHash -ne ([string]$snapshot.sha256).ToLowerInvariant()) { throw "Corpus index snapshot SHA-256 mismatch: $snapshotPath" }
        $snapshotJson = Get-Content -LiteralPath $snapshotPath -Raw | ConvertFrom-Json
        $snapshotItems = @($snapshotJson)
        if ($snapshotJson -isnot [array] -and $snapshotJson.plugins) { $snapshotItems = @($snapshotJson.plugins) }
        if ($snapshotItems.Count -ne [int]$snapshot.recordCount) { throw "Corpus index record count mismatch for $($snapshot.id): expected $($snapshot.recordCount), found $($snapshotItems.Count)." }
        $indexSnapshots[[string]$snapshot.id] = [ordered]@{ metadata = $snapshot; path = $snapshotPath; sha256 = $snapshotHash; items = $snapshotItems }
        $corpusIndexMetadata += [ordered]@{ id = $snapshot.id; repository = $snapshot.repository; sourceCommit = $snapshot.sourceCommit; generatedFromSourceCommit = $snapshot.generatedFromSourceCommit; retrievedAtUtc = $snapshot.retrievedAtUtc; url = $snapshot.url; path = $snapshotPath; bytes = (Get-Item -LiteralPath $snapshotPath).Length; sha256 = $snapshotHash; recordCount = $snapshotItems.Count }
    }

    $seenCorpusIds = @{}
    foreach ($corpusArtifact in $corpusManifestData.artifacts) {
        $corpusId = [string]$corpusArtifact.id
        if ($corpusId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or $seenCorpusIds.ContainsKey($corpusId)) { throw "Invalid or duplicate corpus artifact id: $corpusId" }
        $seenCorpusIds[$corpusId] = $true
        $archivePath = Resolve-CorpusPath $corpusRoot ([string]$corpusArtifact.archivePath) "Archive for $corpusId"
        if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) { throw "Corpus archive is missing: $archivePath" }
        $archiveHash = Get-Sha256 $archivePath
        $archiveBytes = (Get-Item -LiteralPath $archivePath).Length
        if ($archiveHash -ne ([string]$corpusArtifact.expectedArchiveSha256).ToLowerInvariant()) { throw "Corpus archive SHA-256 mismatch for $corpusId." }
        if ($archiveBytes -ne [long]$corpusArtifact.expectedArchiveBytes) { throw "Corpus archive byte count mismatch for $corpusId." }

        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            $manifestEntries = @($archive.Entries | Where-Object { $_.FullName -ceq 'manifest.json' })
            if ($manifestEntries.Count -ne 1 -or $manifestEntries[0].Length -gt 1048576) { throw "Corpus archive $corpusId must contain exactly one small root manifest.json." }
            $manifestReader = [System.IO.StreamReader]::new($manifestEntries[0].Open(), [System.Text.Encoding]::UTF8)
            try { $pluginManifest = $manifestReader.ReadToEnd() | ConvertFrom-Json } finally { $manifestReader.Dispose() }
            $dexEntries = @($archive.Entries | Where-Object { $_.FullName -ceq 'classes.dex' })
            if ($dexEntries.Count -ne 1) { throw "Corpus archive $corpusId must contain exactly one root classes.dex entry." }
            $zipEntries = $archive.Entries.Count
            $resourceEntries = @($archive.Entries | Where-Object { $_.FullName.StartsWith('res/') -and -not $_.FullName.EndsWith('/') }).Count
            $resourcesTablePresent = @($archive.Entries | Where-Object { $_.FullName -ceq 'resources.arsc' }).Count -gt 0
        } finally {
            $archive.Dispose()
        }

        foreach ($field in @('name', 'version', 'pluginClassName', 'requiresResources')) {
            $actual = [string]$pluginManifest.$field
            $expected = [string]$corpusArtifact.expectedManifest.$field
            if ($actual -cne $expected) { throw "Corpus manifest field mismatch for ${corpusId}.${field}: expected '$expected', found '$actual'." }
        }

        $provenance = $corpusArtifact.provenance
        $indexRecord = $null
        $publishedHashStatus = 'unattributed'
        if ($provenance.recordUrl) {
            $indexSnapshot = $indexSnapshots[[string]$provenance.indexSnapshotId]
            if ($null -eq $indexSnapshot) { throw "Corpus artifact $corpusId refers to missing index snapshot $($provenance.indexSnapshotId)." }
            $recordMatches = @($indexSnapshot.items | Where-Object {
                $_.name -ceq [string]$provenance.recordName -and
                $_.url -ceq [string]$provenance.recordUrl -and
                [string]$_.version -ceq [string]$provenance.recordVersion
            })
            if ($recordMatches.Count -ne 1) { throw "Corpus artifact $corpusId source record did not resolve uniquely in its exact index snapshot." }
            $indexRecord = $recordMatches[0]
            if (-not $provenance.indexRecordMetadata) { throw "Corpus artifact $corpusId is missing captured source-record metadata." }
            foreach ($property in $provenance.indexRecordMetadata.PSObject.Properties) {
                $capturedValue = ConvertTo-Json -InputObject $property.Value -Depth 12 -Compress
                $snapshotValue = ConvertTo-Json -InputObject $indexRecord.($property.Name) -Depth 12 -Compress
                if ($capturedValue -cne $snapshotValue) { throw "Corpus artifact $corpusId captured index metadata differs from its exact snapshot at field '$($property.Name)'." }
            }
            if ($indexRecord.fileSize -and [long]$indexRecord.fileSize -ne $archiveBytes) { throw "Published fileSize disagrees with the retained archive for $corpusId." }
            if ($indexRecord.fileHash) {
                $publishedHash = ([string]$indexRecord.fileHash -replace '^sha256-', '').ToLowerInvariant()
                if ($publishedHash -ne $archiveHash) { throw "Published SHA-256 disagrees with the retained archive for $corpusId." }
                $publishedHashStatus = 'match'
            } else {
                $publishedHashStatus = 'not-published'
            }
        }
        $relatedRecord = $null
        if ($provenance.relatedRecord) {
            $relatedSnapshot = $indexSnapshots[[string]$provenance.relatedRecord.indexSnapshotId]
            if ($null -eq $relatedSnapshot) { throw "Corpus artifact $corpusId refers to missing related index snapshot." }
            $relatedMatches = @($relatedSnapshot.items | Where-Object {
                $_.name -ceq [string]$provenance.relatedRecord.name -and
                $_.url -ceq [string]$provenance.relatedRecord.url -and
                [string]$_.version -ceq [string]$provenance.relatedRecord.version
            })
            if ($relatedMatches.Count -ne 1) { throw "Corpus artifact $corpusId related index record did not resolve uniquely." }
            $relatedRecord = $relatedMatches[0]
            if (-not $provenance.relatedRecord.recordMetadata) { throw "Corpus artifact $corpusId is missing related-record metadata." }
            foreach ($property in $provenance.relatedRecord.recordMetadata.PSObject.Properties) {
                $capturedValue = ConvertTo-Json -InputObject $property.Value -Depth 12 -Compress
                $snapshotValue = ConvertTo-Json -InputObject $relatedRecord.($property.Name) -Depth 12 -Compress
                if ($capturedValue -cne $snapshotValue) { throw "Corpus artifact $corpusId related index metadata differs from its exact snapshot at field '$($property.Name)'." }
            }
        }

        $dexPath = Resolve-CorpusPath $corpusRoot ("extracted-dex/$corpusId.classes.dex") "Extracted DEX for $corpusId"
        $dexMetadata = Copy-CorpusDexSafely $archivePath $dexPath $corpusId
        $reportDirectory = Resolve-CorpusPath $corpusRoot 'reports' 'Corpus report directory'
        New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
        $corpusDetails = [ordered]@{
            id = $corpusId
            origin = $provenance.origin
            sampleDate = $provenance.sampleDate
            sourceArtifactPath = $provenance.sourceArtifactPath
            repository = $provenance.repository
            sourceBuildCommit = $provenance.sourceBuildCommit
            indexSnapshotId = $provenance.indexSnapshotId
            recordName = $(if ($indexRecord) { $indexRecord.name } else { $null })
            recordUrl = $(if ($indexRecord) { $indexRecord.url } else { $null })
            recordVersion = $(if ($indexRecord) { $indexRecord.version } else { $null })
            publishedHash = $(if ($indexRecord) { $indexRecord.fileHash } else { $null })
            publishedFileSize = $(if ($indexRecord) { $indexRecord.fileSize } else { $null })
            publishedHashStatus = $publishedHashStatus
            indexRecordMetadata = $(if ($indexRecord) { $indexRecord } else { $null })
            relatedCurrentRecord = $(if ($relatedRecord) { [ordered]@{ indexSnapshotId = $provenance.relatedRecord.indexSnapshotId; metadata = $relatedRecord } } else { $null })
            archive = [ordered]@{ path = $archivePath; bytes = $archiveBytes; sha256 = $archiveHash }
            manifest = [ordered]@{ name = $pluginManifest.name; pluginClassName = $pluginManifest.pluginClassName; version = $pluginManifest.version; requiresResources = [bool]$pluginManifest.requiresResources; resourceEntries = $resourceEntries; resourcesTablePresent = $resourcesTablePresent; archiveEntries = $zipEntries }
            dex = $dexMetadata
        }
        $plugins += @{
            Name = "$($corpusArtifact.displayName)"
            Key = $corpusId
            Archive = $archivePath
            Dex = $dexPath
            Corpus = $true
            CorpusDetails = $corpusDetails
            ReportDirectory = $reportDirectory
        }
        $corpusPackages += [ordered]@{ id = $corpusId; displayName = $corpusArtifact.displayName; details = $corpusDetails }
    }
}

foreach ($plugin in $plugins) {
    foreach ($baseline in $baselines) {
        $stem = "$($plugin.Key)-$($baseline.Key)"
        $reportDirectory = if ($plugin.Corpus) { $plugin.ReportDirectory } else { $EvidenceDirectory }
        $report = Join-Path $reportDirectory "$stem.tsv"
        $summary = Join-Path $reportDirectory "$stem-summary.json"
        $javaArgs = @(
            '-Xmx2g', '-cp', $analysisDir, 'Cs3StaticCrosswalk',
            '--plugin', $plugin.Name,
            '--baseline', $baseline.Key,
            '--archive', $plugin.Archive,
            '--dex', $plugin.Dex,
            '--classpath', $baseline.Surface,
            '--out', $report,
            '--summary', $summary
        )
        & java @javaArgs
        if ($LASTEXITCODE -ne 0) { throw "Static crosswalk failed for $($plugin.Name) against $($baseline.Key)." }
    }
}

$gitStableStatus = @(& git -C $StableCheckout status --short)
$gitPreStatus = @(& git -C $PreReleaseCheckout status --short)
$metadata = [ordered]@{
    generatedAtLocal = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')
    run = [ordered]@{
        id = $runId
        statusManifest = $runStatusPath
        statusManifestRelationship = 'Separate lifecycle envelope; its final hash is not embedded here to avoid circular hashing. The status manifest records this provenance hash and every generated output hash.'
    }
    analyzer = [ordered]@{
        source = $analyzerSource
        sourceSha256 = Get-Sha256 $analyzerSource
        version = '1.0.0'
        javacVersion = Get-VersionOutput 'javac' @('-version')
        javaVersion = Get-VersionOutput 'java' @('-version')
        execution = 'Static ZIP, DEX table, and JVM class-file parsing. No analyzed plugin code is loaded, instantiated, or executed.'
    }
    runner = [ordered]@{
        source = $PSCommandPath
        sourceSha256 = Get-Sha256 $PSCommandPath
        gradleInputDumper = Join-Path $PSScriptRoot 'dump_compile_inputs.gradle'
        gradleInputDumperSha256 = Get-Sha256 (Join-Path $PSScriptRoot 'dump_compile_inputs.gradle')
    }
    gradleWrapperVersions = [ordered]@{
        preRelease = Get-VersionOutput (Join-Path $PreReleaseCheckout 'gradlew.bat') @('--version')
        stable = Get-VersionOutput (Join-Path $StableCheckout 'gradlew.bat') @('--version')
    }
    androidSdkRoot = $AndroidSdk
    baselines = @(
        [ordered]@{
            name = 'pre-release-5024ace'
            commit = $preHead
            checkout = $PreReleaseCheckout
            appCompileClassesJar = [ordered]@{ path = $preAppJar; sha256 = Get-Sha256 $preAppJar }
            libraryJvmJar = [ordered]@{ path = $preLibraryJar; sha256 = Get-Sha256 $preLibraryJar }
            appOnlyExternalInputs = [ordered]@{ path = $preAppOnly; sha256 = Get-Sha256 $preAppOnly; artifactHashes = $preAppOnlyHashes; artifactHashesSha256 = Get-Sha256 $preAppOnlyHashes; records = @(Get-Content $preAppOnly | Where-Object { $_.StartsWith("external$tab") }).Count }
            externalCompileInputs = [ordered]@{ scope = 'Union of app prereleaseDebugCompileClasspath and library jvmCompileClasspath'; path = $preExternal; sha256 = Get-Sha256 $preExternal; artifactHashes = $preUnionHashes; artifactHashesSha256 = Get-Sha256 $preUnionHashes; records = @(Get-Content $preExternal | Where-Object { $_.StartsWith("external$tab") }).Count }
            artifactResolutionDiagnostics = [ordered]@{ path = $preResolutionDiagnostics; sha256 = Get-Sha256 $preResolutionDiagnostics; failureCount = $preResolutionStatus.failureCount; configurations = $preResolutionStatus.configurations }
            surfaceManifest = [ordered]@{ path = $preSurface; sha256 = Get-Sha256 $preSurface }
            checkoutStatus = $gitPreStatus
            note = 'Build outputs reused from the Android-only closure-study checkout. Its HEAD is the exact pin; the study worktree has the documented settings.gradle.kts cut and removed docs/desktopApp/Fastlane trees.'
        },
        [ordered]@{
            name = 'stable-v4.8.0'
            commit = $stableHead
            checkout = $StableCheckout
            appCompileClassesJar = [ordered]@{ path = $stableAppJar; sha256 = Get-Sha256 $stableAppJar }
            libraryJvmJar = [ordered]@{ path = $stableLibraryJar; sha256 = Get-Sha256 $stableLibraryJar }
            appOnlyExternalInputs = [ordered]@{ path = $stableAppOnly; sha256 = Get-Sha256 $stableAppOnly; artifactHashes = $stableAppOnlyHashes; artifactHashesSha256 = Get-Sha256 $stableAppOnlyHashes; records = @(Get-Content $stableAppOnly | Where-Object { $_.StartsWith("external$tab") }).Count }
            externalCompileInputs = [ordered]@{ scope = 'Union of app prereleaseDebugCompileClasspath and library jvmCompileClasspath'; path = $stableExternal; sha256 = Get-Sha256 $stableExternal; artifactHashes = $stableUnionHashes; artifactHashesSha256 = Get-Sha256 $stableUnionHashes; records = @(Get-Content $stableExternal | Where-Object { $_.StartsWith("external$tab") }).Count }
            artifactResolutionDiagnostics = [ordered]@{ path = $stableResolutionDiagnostics; sha256 = Get-Sha256 $stableResolutionDiagnostics; failureCount = $stableResolutionStatus.failureCount; configurations = $stableResolutionStatus.configurations }
            surfaceManifest = [ordered]@{ path = $stableSurface; sha256 = Get-Sha256 $stableSurface }
            checkoutStatus = $gitStableStatus
            generationCommand = ':app:bundlePrereleaseDebugClassesToCompileJar :library:jvmJar --no-configuration-cache'
            note = 'Generated static compile classes and library JVM inputs only. No app packaging/install/test task was run.'
        }
    )
    corpus = $(if ($CorpusManifest) {
        [ordered]@{
            manifestPath = $CorpusManifest
            manifestSha256 = Get-Sha256 $CorpusManifest
            corpusRoot = $corpusRoot
            corpusDate = $corpusManifestData.corpusDate
            indexSnapshots = @($corpusIndexMetadata)
            artifacts = @($corpusPackages)
            execution = 'Corpus DEX files were copied from the exact root classes.dex ZIP member after path/size validation; only DEX tables and JVM class files were parsed.'
        }
    } else { $null })
    plugins = @(
        foreach ($plugin in $plugins) {
            [ordered]@{
                name = $plugin.Name
                corpus = [bool]$plugin.Corpus
                corpusSource = $(if ($plugin.Corpus) { $plugin.CorpusDetails } else { $null })
                archive = [ordered]@{ path = $plugin.Archive; bytes = (Get-Item -LiteralPath $plugin.Archive).Length; sha256 = Get-Sha256 $plugin.Archive }
                dex = [ordered]@{ path = $plugin.Dex; bytes = (Get-Item -LiteralPath $plugin.Dex).Length; sha256 = Get-Sha256 $plugin.Dex }
                outputs = @(
                    foreach ($baseline in $baselines) {
                        $stem = "$($plugin.Key)-$($baseline.Key)"
                        $outputDirectory = if ($plugin.Corpus) { $plugin.ReportDirectory } else { $EvidenceDirectory }
                        foreach ($suffix in @('.tsv', '-summary.json')) {
                            $path = Join-Path $outputDirectory "$stem$suffix"
                            [ordered]@{ path = $path; sha256 = Get-Sha256 $path }
                        }
                    }
                )
            }
        }
    )
}
$jsonPath = Join-Path $EvidenceDirectory 'inputs-and-tools.json'
$json = $metadata | ConvertTo-Json -Depth 12
[System.IO.File]::WriteAllText($jsonPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
$outputNames = @(
    'prerelease-5024ace-classpath.tsv', 'stable-v4.8.0-classpath.tsv',
    'prerelease-5024ace-resolution-diagnostics.json', 'stable-v4.8.0-resolution-diagnostics.json',
    'prerelease-5024ace-app-only-artifact-hashes.tsv', 'stable-v4.8.0-app-only-artifact-hashes.tsv',
    'prerelease-5024ace-union-artifact-hashes.tsv', 'stable-v4.8.0-union-artifact-hashes.tsv',
    'pre-release-5024ace-surface.tsv', 'stable-v4.8.0-surface.tsv',
    'megaprovider-pre-release-5024ace.tsv', 'megaprovider-pre-release-5024ace-summary.json',
    'megaprovider-stable-v4.8.0.tsv', 'megaprovider-stable-v4.8.0-summary.json',
    'streamplay-pre-release-5024ace.tsv', 'streamplay-pre-release-5024ace-summary.json',
    'streamplay-stable-v4.8.0.tsv', 'streamplay-stable-v4.8.0-summary.json'
)
$outputPaths = [System.Collections.Generic.List[string]]::new()
foreach ($name in $outputNames) { $outputPaths.Add((Join-Path $EvidenceDirectory $name)) }
foreach ($plugin in $plugins | Where-Object { $_.Corpus }) {
    $outputPaths.Add($plugin.Dex)
    foreach ($baseline in $baselines) {
        $stem = "$($plugin.Key)-$($baseline.Key)"
        $outputPaths.Add((Join-Path $plugin.ReportDirectory "$stem.tsv"))
        $outputPaths.Add((Join-Path $plugin.ReportDirectory "$stem-summary.json"))
    }
}
$outputPaths.Add($jsonPath)
$statusOutputs = @(
    foreach ($path in $outputPaths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Expected generated output is missing before completion: $path" }
        [ordered]@{ path = $path; sha256 = Get-Sha256 $path }
    }
)
$provenanceSha256 = Get-Sha256 $jsonPath
Write-RunStatus 'complete' -ProvenanceSha256 $provenanceSha256 -Outputs $statusOutputs
Write-Host "Static crosswalk complete. Provenance: $jsonPath"
} catch {
    $caught = $_
    try {
        Write-RunStatus 'failed' -ErrorMessage $caught.Exception.Message -ErrorType $caught.Exception.GetType().FullName -ErrorStack $caught.ScriptStackTrace
    } catch {
        Write-Warning "Could not update run status manifest to failed: $($_.Exception.Message)"
    }
    throw $caught
}
