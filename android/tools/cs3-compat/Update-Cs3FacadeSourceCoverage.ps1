[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path,
    [Parameter(Mandatory = $true)]
    [string]$OfficialCheckout
)

$ErrorActionPreference = 'Stop'
$tab = [char]9
$headCommit = '5024ace2922a148d38df2a816b483b529789cf58'
$stableCommit = '3796406e636356f556c16e04e1da4e37d6a19038'
$contractHash = '453c01d379a133a729c98e7a4f66ef7d46d2f7da309893c16db890783fd84308'
$supplementalHash = '78e83ab56e645828a7e05550bf6a863fb722f2626866978efc60b94b2be55f6a'
$ledgerPath = Join-Path $PSScriptRoot 'OrbitCs3FacadeSourceCoverage.tsv'
$contractPath = Join-Path $PSScriptRoot 'OrbitCs3FacadeContract.tsv'
$supplementalPath = Join-Path $PSScriptRoot 'OrbitCs3FacadeSupplementalAbi.tsv'
$mappingPath = Join-Path $PSScriptRoot 'OrbitCs3FacadeExactSourceMapping.tsv'
$orbitSourceRoot = Join-Path $RepositoryRoot 'android\app\src\main\java\com\lagradost\cloudstream3'
$orbitMainApiPath = Join-Path $orbitSourceRoot 'MainAPI.java'
$sourceCoverageHeader = 'source_set{0}kind{0}reference{0}orbit_source_path{0}orbit_source_symbol{0}orbit_mapping_status{0}official_source_path{0}official_source_symbol{0}official_mapping_status{0}signature_dependency_status{0}behavior_review{0}notes' -f $tab
$mappingHeader = 'source_set{0}kind{0}input_reference{0}reference_jvm_owner{0}reference_jvm_member{0}reference_jvm_descriptor{0}upstream_revision{0}upstream_declaration_source_owner{0}upstream_source_path{0}upstream_declaration_symbol{0}orbit_declaration_source_owner{0}orbit_source_path{0}orbit_declaration_symbol{0}expected_jvm_name{0}expected_jvm_descriptor{0}disposition{0}rationale' -f $tab
$sourceRoots = @(
    'app/src/main/java'
    'library/src/commonMain/kotlin/com/lagradost/cloudstream3'
    'library/src/androidMain/kotlin/com/lagradost/cloudstream3'
    'library/src/jvmCommonMain/kotlin/com/lagradost/cloudstream3'
    'library/src/jvmMain/kotlin/com/lagradost/cloudstream3'
)

function Get-Sha256Bytes([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha.ComputeHash($Bytes)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-FileSha256([string]$Path, [string]$Expected, [string]$Label) {
    $actual = Get-FileSha256 $Path
    if ($actual -ne $Expected) {
        throw "$Label SHA-256 is $actual; expected pinned input $Expected."
    }
}

function Invoke-GitText([string]$Checkout, [string[]]$Arguments) {
    $result = & git -C $Checkout @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git -C $Checkout $($Arguments -join ' ') failed: $($result -join [Environment]::NewLine)"
    }
    return [string]::Join([Environment]::NewLine, [string[]]$result)
}

function Get-GitBlobBytes([string]$Checkout, [string]$Commit, [string]$Path) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'git'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    [void]$psi.ArgumentList.Add('-C')
    [void]$psi.ArgumentList.Add($Checkout)
    [void]$psi.ArgumentList.Add('cat-file')
    [void]$psi.ArgumentList.Add('blob')
    [void]$psi.ArgumentList.Add(('{0}:{1}' -f $Commit, $Path))
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $memory = [System.IO.MemoryStream]::new()
    $process.StandardOutput.BaseStream.CopyTo($memory)
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw ('Could not read pinned Git object {0}:{1} : {2}' -f $Commit, $Path, $errorText)
    }
    $bytes = $memory.ToArray()
    $memory.Dispose()
    $process.Dispose()
    return ,$bytes
}

function Assert-GitBlob([string]$Checkout, [string]$Commit, [string]$Path, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.StartsWith('/') -or ($Path -split '/') -contains '..') {
        throw "$Label has an empty or unsafe repository path: $Path"
    }
    $objectPath = '{0}:{1}' -f $Commit, $Path
    & git -C $Checkout cat-file -e $objectPath 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "$Label is absent from pinned Git object $objectPath."
    }
}

function Get-OfficialSnapshot([string]$Checkout, [string]$Commit, [string[]]$Roots) {
    $arguments = @('ls-tree', '-r', '--name-only', $Commit, '--') + $Roots
    $raw = Invoke-GitText $Checkout $arguments
    [string[]]$paths = @($raw.Split([char]10) | ForEach-Object { $_.TrimEnd([char]13) } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $manifest = [System.Text.StringBuilder]::new()
    foreach ($path in $paths) {
        $objectId = Invoke-GitText $Checkout @('rev-parse', ('{0}:{1}' -f $Commit, $path))
        [void]$manifest.Append($path).Append($tab).Append($objectId.Trim()).Append([char]10)
    }
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($manifest.ToString())
    return [pscustomobject]@{
        Count = $paths.Count
        Sha256 = Get-Sha256Bytes $bytes
        Roots = $Roots -join ','
    }
}

function Get-OrbitSnapshot([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw "Orbit source root not found: $Root"
    }
    $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File)
    [string[]]$relativePaths = @($files | ForEach-Object { $_.FullName.Substring($Root.Length + 1).Replace('\', '/') })
    [Array]::Sort($relativePaths, [StringComparer]::Ordinal)
    $manifest = [System.Text.StringBuilder]::new()
    foreach ($relativePath in $relativePaths) {
        $file = Join-Path $Root ($relativePath.Replace('/', '\'))
        [void]$manifest.Append($relativePath).Append($tab).Append((Get-FileSha256 $file)).Append([char]10)
    }
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($manifest.ToString())
    return [pscustomobject]@{
        Count = $relativePaths.Count
        Sha256 = Get-Sha256Bytes $bytes
        Root = 'android/app/src/main/java/com/lagradost/cloudstream3'
    }
}

function Get-CandidateOrbitPaths([string]$Candidates) {
    foreach ($candidate in ($Candidates -split ' \| ')) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if ($candidate.StartsWith('/') -or ($candidate -split '/') -contains '..') {
            throw "Unsafe Orbit candidate path in coverage ledger: $candidate"
        }
        $candidatePath = Join-Path $RepositoryRoot ($candidate.Replace('/', '\'))
        if (Test-Path -LiteralPath $candidatePath -PathType Leaf) {
            Write-Output $candidate
        }
    }
}

function Get-CandidateOfficialPaths([string]$Candidates, [string]$Commit) {
    foreach ($candidate in ($Candidates -split ' \| ')) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        Assert-GitBlob $OfficialCheckout $Commit $candidate 'Official candidate path'
        Write-Output $candidate
    }
}

function Parse-Reference([string]$Kind, [string]$Reference) {
    if ($Kind -eq 'type') {
        if ($Reference -notmatch '^L(?<owner>[^;]+);$') { throw "Invalid type reference: $Reference" }
        return [pscustomobject]@{
            Owner = $Matches.owner
            Member = '<type>'
            Descriptor = $Reference
            ExpectedName = $Matches.owner
            ExpectedDescriptor = $Reference
        }
    }
    if ($Reference -notmatch '^L(?<owner>[^;]+);->(?<member>[^(:]+)(?<tail>.+)$') {
        throw "Invalid member reference: $Reference"
    }
    $owner = $Matches.owner
    $member = $Matches.member
    $tail = $Matches.tail
    if ($Kind -eq 'field') {
        if ($tail -notmatch '^:(?<descriptor>.+)$') { throw "Invalid field reference: $Reference" }
        $descriptor = $Matches.descriptor
    } elseif ($Kind -eq 'method') {
        if ($tail -notmatch '^(?<descriptor>\(.*\).+)$') { throw "Invalid method reference: $Reference" }
        $descriptor = $Matches.descriptor
    } else {
        throw "Unsupported descriptor kind '$Kind' for $Reference."
    }
    return [pscustomobject]@{
        Owner = $owner
        Member = $member
        Descriptor = $descriptor
        ExpectedName = $member
        ExpectedDescriptor = $descriptor
    }
}

if (-not (Test-Path -LiteralPath $RepositoryRoot -PathType Container)) {
    throw "Repository root not found: $RepositoryRoot"
}
if (-not (Test-Path -LiteralPath $OfficialCheckout -PathType Container)) {
    throw 'Pass -OfficialCheckout as the pinned CloudStream checkout; this updater does not assume a mutable checkout path.'
}
$actualHead = (Invoke-GitText $OfficialCheckout @('rev-parse', 'HEAD')).Trim()
if ($actualHead -ne $headCommit) {
    throw "Official source checkout is $actualHead; expected pinned HEAD $headCommit."
}
$actualStable = (Invoke-GitText $OfficialCheckout @('rev-parse', 'refs/tags/v4.8.0^{commit}')).Trim()
if ($actualStable -ne $stableCommit) {
    throw "Stable v4.8.0 resolves to $actualStable; expected pinned commit $stableCommit."
}

Assert-FileSha256 $contractPath $contractHash 'Contract'
Assert-FileSha256 $supplementalPath $supplementalHash 'Supplemental ABI'
$contractRows = @(Import-Csv -LiteralPath $contractPath -Delimiter $tab)
$supplementalRows = @(Import-Csv -LiteralPath $supplementalPath -Delimiter $tab)
if ($contractRows.Count -ne 413 -or $supplementalRows.Count -ne 5) {
    throw "Unexpected descriptor input counts: contract=$($contractRows.Count), supplemental=$($supplementalRows.Count)."
}

# These supplementary references came from an installed package outside the
# nineteen-archive crosswalk. Verify the owner source declarations in the pinned
# CloudStream checkout before recording their supplemental source mappings.
$cloudStreamAppReference = 'Lcom/lagradost/cloudstream3/CloudStreamApp$Companion;->setKey(Ljava/lang/String;Ljava/lang/Object;)V'
$reloadHomeReference = 'Lcom/lagradost/cloudstream3/MainActivity$Companion;->getReloadHomeEvent()Lcom/lagradost/cloudstream3/utils/Event;'
if (-not ($supplementalRows | Where-Object { $_.kind -eq 'method' -and $_.reference -eq $cloudStreamAppReference })) {
    throw 'Supplemental ABI is missing the installed-package CloudStreamApp.Companion.setKey reference.'
}
if (-not ($supplementalRows | Where-Object { $_.kind -eq 'method' -and $_.reference -eq $reloadHomeReference })) {
    throw 'Supplemental ABI is missing the installed-package MainActivity.Companion.getReloadHomeEvent reference.'
}
$cloudStreamAppSource = [System.Text.UTF8Encoding]::new($false, $true).GetString(
    (Get-GitBlobBytes $OfficialCheckout $headCommit 'app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt'))
if ($cloudStreamAppSource -notmatch '(?s)companion\s+object\s*\{.*?fun\s*<T>\s+setKey\s*\(\s*path:\s*String\s*,\s*value:\s*T\s*\)') {
    throw 'Pinned CloudStreamApp source does not contain the expected companion setKey(path, value) overload.'
}
$mainActivitySource = [System.Text.UTF8Encoding]::new($false, $true).GetString(
    (Get-GitBlobBytes $OfficialCheckout $headCommit 'app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt'))
if ($mainActivitySource -notmatch '(?s)companion\s+object\s*\{.*?val\s+reloadHomeEvent\s*=\s*Event<Boolean>\s*\(\s*\)') {
    throw 'Pinned MainActivity source does not contain the expected reloadHomeEvent companion property.'
}

$ledgerRows = @(Import-Csv -LiteralPath $ledgerPath -Delimiter $tab)
$descriptorExpected = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($row in $contractRows) {
    [void]$descriptorExpected.Add(('contract{0}{1}{0}{2}' -f $tab, $row.kind, $row.reference))
}
foreach ($row in $supplementalRows) {
    [void]$descriptorExpected.Add(('supplemental{0}{1}{0}{2}' -f $tab, $row.kind, $row.reference))
}
$descriptorActual = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$ledgerByKey = @{}
$contextRows = @()
foreach ($row in $ledgerRows) {
    if ($row.source_set -eq 'context') {
        $contextRows += $row
        continue
    }
    if ($row.source_set -notin @('contract', 'supplemental')) { continue }
    $key = '{0}{1}{2}{1}{3}' -f $row.source_set, $tab, $row.kind, $row.reference
    if (-not $descriptorActual.Add($key)) { throw "Duplicate descriptor row in coverage ledger: $key" }
    $ledgerByKey[$key] = $row
}
if ($descriptorActual.Count -ne 418 -or $contextRows.Count -ne 1) {
    throw "Coverage ledger has $($descriptorActual.Count) descriptor rows and $($contextRows.Count) context rows; expected 418 and 1."
}
foreach ($key in $descriptorExpected) {
    if (-not $descriptorActual.Contains($key)) { throw "Coverage ledger is missing descriptor row: $key" }
}
foreach ($key in $descriptorActual) {
    if (-not $descriptorExpected.Contains($key)) { throw "Coverage ledger contains a row outside the pinned inputs: $key" }
}

$orbitTypePathExpectations = @{
    'Lcom/lagradost/cloudstream3/CloudStreamApp$Companion;' = 'android/app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.java'
    'Lcom/lagradost/cloudstream3/LoadResponse$Companion;' = 'android/app/src/main/java/com/lagradost/cloudstream3/LoadResponse.java'
    'Lcom/lagradost/cloudstream3/MainActivity$Companion;' = 'android/app/src/main/java/com/lagradost/cloudstream3/MainActivity.java'
    'Lcom/lagradost/cloudstream3/MainActivityKt;' = 'android/app/src/main/java/com/lagradost/cloudstream3/MainActivityKt.java'
    'Lcom/lagradost/cloudstream3/MainAPI$Companion;' = 'android/app/src/main/java/com/lagradost/cloudstream3/MainAPI.java'
    'Lcom/lagradost/cloudstream3/MainAPIKt;' = 'android/app/src/main/java/com/lagradost/cloudstream3/MainAPIKt.java'
    'Lcom/lagradost/cloudstream3/Score$Companion;' = 'android/app/src/main/java/com/lagradost/cloudstream3/Score.java'
    'Lcom/lagradost/cloudstream3/utils/ExtractorApiKt;' = 'android/app/src/main/java/com/lagradost/cloudstream3/utils/ExtractorApiKt.java'
    'Lcom/lagradost/cloudstream3/utils/M3u8Helper$Companion;' = 'android/app/src/main/java/com/lagradost/cloudstream3/utils/M3u8Helper.kt'
}
foreach ($reference in $orbitTypePathExpectations.Keys) {
    $key = 'contract{0}type{0}{1}' -f $tab, $reference
    if ($ledgerByKey[$key].orbit_source_path -ne $orbitTypePathExpectations[$reference]) {
        throw "Orbit source candidate for $reference must remain narrowed to $($orbitTypePathExpectations[$reference])."
    }
}
$webViewTypeKey = 'contract{0}type{0}Lcom/lagradost/cloudstream3/network/WebViewResolver;' -f $tab
$webViewAndroidSource = 'library/src/androidMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.android.kt'
if ($ledgerByKey[$webViewTypeKey].official_source_path -ne $webViewAndroidSource) {
    throw "WebViewResolver upstream type candidate must be scoped to the Android actual source: $webViewAndroidSource."
}

$orbitSnapshot = Get-OrbitSnapshot $orbitSourceRoot
$officialSnapshot = Get-OfficialSnapshot $OfficialCheckout $headCommit $sourceRoots
$stableAesPath = 'library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/helper/AesHelper.kt'
$stableAesBytes = Get-GitBlobBytes $OfficialCheckout $stableCommit $stableAesPath
$stableAesHash = Get-Sha256Bytes $stableAesBytes
$stableAesText = [System.Text.UTF8Encoding]::new($false, $true).GetString($stableAesBytes)
if ($stableAesText -notmatch '(?s)fun\s+cryptoAESHandler\s*\(\s*data:\s*String\s*,\s*pass:\s*ByteArray\s*,\s*encrypt:\s*Boolean\s*=\s*true\s*,\s*padding:\s*Boolean\s*=\s*true\s*,') {
    throw "Pinned v4.8.0 AesHelper source does not contain the expected Boolean-padding overload: $stableAesPath."
}
$orbitMainApiHash = Get-FileSha256 $orbitMainApiPath

$mappingRows = [System.Collections.Generic.List[object]]::new()
foreach ($sourceSet in @('contract', 'supplemental')) {
    $inputRows = if ($sourceSet -eq 'contract') { $contractRows } else { $supplementalRows }
    foreach ($inputRow in $inputRows) {
        $key = '{0}{1}{2}{1}{3}' -f $sourceSet, $tab, $inputRow.kind, $inputRow.reference
        $ledgerRow = $ledgerByKey[$key]
        $reference = Parse-Reference $inputRow.kind $inputRow.reference
        $isGmPlayer = $inputRow.reference -match '^Lcom/lagradost/cloudstream3/extractors/GMPlayer;'
        $isStableAes = $inputRow.reference -eq 'Lcom/lagradost/cloudstream3/extractors/helper/AesHelper;->cryptoAESHandler$default(Lcom/lagradost/cloudstream3/extractors/helper/AesHelper;Ljava/lang/String;[BZZILjava/lang/Object;)Ljava/lang/String;'
        $isInheritedAuthUser = $inputRow.reference -eq 'Lcom/lagradost/cloudstream3/syncproviders/SyncRepo;->authUser()Lcom/lagradost/cloudstream3/syncproviders/AuthUser;'
        $isAmbiguousLibrary = $inputRow.reference -eq 'Lcom/lagradost/cloudstream3/syncproviders/SyncRepo;->library-IoAF18A(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'
        $isCloudStreamAppSetKey = $inputRow.reference -eq $cloudStreamAppReference
        $isReloadHomeEvent = $inputRow.reference -eq $reloadHomeReference

        $orbitPaths = @(Get-CandidateOrbitPaths $ledgerRow.orbit_source_path)
        if ($orbitPaths.Count -eq 0) { throw "No existing Orbit source candidate for $key." }
        $officialPaths = @()
        $upstreamRevision = $headCommit
        $upstreamOwner = $reference.Owner
        $upstreamSymbol = $ledgerRow.official_source_symbol
        $disposition = 'CANDIDATE'
        $rationale = 'Pinned contract and supplemental inputs provide the expected reference name and descriptor. Existing upstream and Orbit paths are source candidates copied from the ledger; this updater does not validate declaration ownership or signatures, compiler output, or bytecode.'

        if ($isGmPlayer) {
            $disposition = 'intentional Orbit-only supplemental'
            $upstreamRevision = 'absent from pinned HEAD and v4.8.0'
            $upstreamOwner = ''
            $upstreamSymbol = ''
            $rationale = 'The reviewed ledger classifies GMPlayer as absent from both official revisions and intentionally declared in Orbit only. This updater preserves that Orbit-only disposition but does not independently search declarations.'
        } elseif ($isStableAes) {
            $upstreamRevision = $stableCommit
            $upstreamOwner = $reference.Owner
            $officialPaths = @($stableAesPath)
            $upstreamSymbol = 'cryptoAESHandler(String, ByteArray, Boolean, Boolean) stable v4.8.0 source overload'
            $rationale = 'The pinned stable v4.8.0 source contains the Boolean-padding cryptoAESHandler overload, which is distinct from HEAD suspend overloads. The supplemental reference is retained as a source candidate; this does not prove the generated default-wrapper descriptor or Orbit bytecode.'
        } elseif ($isInheritedAuthUser) {
            $upstreamOwner = 'com/lagradost/cloudstream3/syncproviders/AuthRepo'
            $officialPaths = @('app/src/main/java/com/lagradost/cloudstream3/syncproviders/AuthRepo.kt')
            $upstreamSymbol = 'AuthRepo.authUser() declaration at pinned source line 109; inherited by symbolic owner SyncRepo'
            $rationale = 'The ledger records AuthRepo.authUser as the source candidate for the symbolic SyncRepo.authUser reference, preserving the inherited-owner distinction. This updater checks the candidate path exists but does not prove the source declaration or inheritance.'
        } elseif ($isAmbiguousLibrary) {
            $disposition = 'ambiguous compiler mangling'
            $upstreamSymbol = 'suspend fun library(): Result<LibraryMetadata>'
            $rationale = 'The ledger identifies suspend library declarations as source candidates in Orbit and pinned upstream. Source candidates cannot establish Kotlin Result value-class mangling to library-IoAF18A; the reference name/descriptor remain unresolved at compiler-output level.'
        } elseif ($isCloudStreamAppSetKey) {
            $upstreamSymbol = 'CloudStreamApp.Companion.setKey(path: String, value: T) delegates to context?.setKey(path, value)'
            $rationale = 'The pinned CloudStreamApp companion declares the one-key generic overload. Orbit maps the descriptor while retaining its documented Orbit-owned scalar-only preference policy; this does not claim pinned JSON serialization parity.'
        } elseif ($isReloadHomeEvent) {
            $upstreamSymbol = 'MainActivity.Companion.reloadHomeEvent: Event<Boolean> generates getReloadHomeEvent()'
            $rationale = 'The pinned MainActivity companion declares a process-wide Event<Boolean> property for home reload notifications. Orbit supplies a matching process-wide event accessor; this source mapping does not establish that every Orbit home reload path emits it.'
        }

        if ($sourceSet -eq 'supplemental' -and -not $isGmPlayer -and -not $isStableAes -and -not $isCloudStreamAppSetKey -and -not $isReloadHomeEvent) {
            throw "Unexpected supplemental row not covered by the reviewed mapping rules: $key."
        }
        if (-not $isGmPlayer -and $officialPaths.Count -eq 0) {
            $officialPaths = @(Get-CandidateOfficialPaths $ledgerRow.official_source_path $headCommit)
            if ($officialPaths.Count -eq 0) { throw "No pinned upstream source candidate for $key." }
        } elseif ($isInheritedAuthUser) {
            foreach ($path in $officialPaths) { Assert-GitBlob $OfficialCheckout $headCommit $path 'Inherited AuthRepo declaration' }
        } elseif ($isStableAes) {
            Assert-GitBlob $OfficialCheckout $stableCommit $stableAesPath 'Stable v4.8.0 AES declaration'
        }

        $orbitOwner = $reference.Owner
        $mappingRows.Add([pscustomobject]@{
            source_set = $sourceSet
            kind = $inputRow.kind
            input_reference = $inputRow.reference
            reference_jvm_owner = $reference.Owner
            reference_jvm_member = $reference.Member
            reference_jvm_descriptor = $reference.Descriptor
            upstream_revision = $upstreamRevision
            upstream_declaration_source_owner = $upstreamOwner
            upstream_source_path = ($officialPaths -join ' | ')
            upstream_declaration_symbol = $upstreamSymbol
            orbit_declaration_source_owner = $orbitOwner
            orbit_source_path = ($orbitPaths -join ' | ')
            orbit_declaration_symbol = $ledgerRow.orbit_source_symbol
            expected_jvm_name = $reference.ExpectedName
            expected_jvm_descriptor = $reference.ExpectedDescriptor
            disposition = $disposition
            rationale = $rationale
        })
    }
}

$context = $contextRows[0]
$contextOrbitPaths = @(Get-CandidateOrbitPaths $context.orbit_source_path)
$contextOfficialPaths = @(Get-CandidateOfficialPaths $context.official_source_path $headCommit)
if ($contextOrbitPaths.Count -ne 2 -or $contextOfficialPaths.Count -ne 1) {
    throw "The separately excluded source-context row has unexpected path counts: Orbit=$($contextOrbitPaths.Count), upstream=$($contextOfficialPaths.Count)."
}
$mappingRows.Add([pscustomobject]@{
    source_set = 'context'
    kind = 'source_context'
    input_reference = $context.reference
    reference_jvm_owner = ''
    reference_jvm_member = ''
    reference_jvm_descriptor = ''
    upstream_revision = $headCommit
    upstream_declaration_source_owner = 'com/lagradost/cloudstream3/MainAPI'
    upstream_source_path = ($contextOfficialPaths -join ' | ')
    upstream_declaration_symbol = $context.official_source_symbol
    orbit_declaration_source_owner = 'com/lagradost/cloudstream3/MainAPI | com/lagradost/cloudstream3/OrbitMainApiDefaults'
    orbit_source_path = ($contextOrbitPaths -join ' | ')
    orbit_declaration_symbol = $context.orbit_source_symbol
    expected_jvm_name = ''
    expected_jvm_descriptor = ''
    disposition = 'excluded context row'
    rationale = 'Retained as a source-only context record; it matches neither the contract nor supplemental descriptor inputs and is excluded from the 418 descriptor mappings.'
})

$candidateCount = @($mappingRows | Where-Object { $_.source_set -ne 'context' -and $_.disposition -eq 'CANDIDATE' }).Count
$ambiguousCount = @($mappingRows | Where-Object { $_.disposition -eq 'ambiguous compiler mangling' }).Count
$orbitOnlyCount = @($mappingRows | Where-Object { $_.disposition -eq 'intentional Orbit-only supplemental' }).Count
if ($mappingRows.Count -ne 419 -or $candidateCount -ne 415 -or $ambiguousCount -ne 1 -or $orbitOnlyCount -ne 2) {
    throw "Generated disposition counts unexpected: rows=$($mappingRows.Count), candidate=$candidateCount, ambiguous=$ambiguousCount, Orbit-only=$orbitOnlyCount."
}

$ledgerRawBytes = [System.IO.File]::ReadAllBytes($ledgerPath)
$ledgerHasBom = $ledgerRawBytes.Length -ge 3 -and $ledgerRawBytes[0] -eq 0xEF -and $ledgerRawBytes[1] -eq 0xBB -and $ledgerRawBytes[2] -eq 0xBF
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$ledgerText = $utf8Strict.GetString($ledgerRawBytes, $(if ($ledgerHasBom) { 3 } else { 0 }), $ledgerRawBytes.Length - $(if ($ledgerHasBom) { 3 } else { 0 }))
$crlf = [string][char]13 + [char]10
$lf = [string][char]10
$newline = if ($ledgerText.Contains($crlf)) { $crlf } else { $lf }
$ledgerLines = @($ledgerText.Replace($crlf, $lf).Split([char]10))
$headerIndex = [Array]::IndexOf($ledgerLines, $sourceCoverageHeader)
if ($headerIndex -lt 0) { throw 'Coverage ledger column header was not found.' }
$dataLines = @($ledgerLines | Select-Object -Skip ($headerIndex + 1) | Where-Object { $_.Contains([string]$tab) })
if ($dataLines.Count -ne 419) { throw "Coverage ledger row text count is $($dataLines.Count); expected 419." }
$ledgerMetadata = @(
    '# artifact=CS3 facade source-coverage candidate-path ledger'
    '# purpose=source file/name candidates only; this ledger does not establish exact JVM owner/signature mapping, compiler output, bytecode, behavior, or runtime compatibility'
    '# descriptor_rows=418 (contract=413; supplemental=5); excluded_context_rows=1; the context row is separately retained and outside descriptor inputs'
    ('# pinned_official_head=cloudstream-upstream-study@{0}; stable_v4.8.0={1}' -f $headCommit, $stableCommit)
    ('# contract_sha256={0}' -f $contractHash)
    ('# supplemental_abi_sha256={0}' -f $supplementalHash)
    ('# orbit_source_snapshot_root={0}; files={1}; manifest_sha256={2}; manifest=SHA256(sorted relative-path<TAB>file-SHA256<LF>)' -f $orbitSnapshot.Root, $orbitSnapshot.Count, $orbitSnapshot.Sha256)
    ('# official_head_source_snapshot_roots={0}; files={1}; manifest_sha256={2}; manifest=SHA256(sorted pinned-path<TAB>Git-blob-object-id<LF>)' -f $officialSnapshot.Roots, $officialSnapshot.Count, $officialSnapshot.Sha256)
    ('# stable_v4.8.0_AesHelper_path={0}; file_sha256={1}' -f $stableAesPath, $stableAesHash)
    ('# orbit_MainAPI.java_sha256={0}' -f $orbitMainApiHash)
    '# withdrawn_selection=the prior exact 42-row selection remains withdrawn as a closure claim; this ledger does not reconstruct it'
    '# boundaries=no bytecode proof, no behavior/runtime parity claim, no universal CloudStream compatibility claim; new setKey retains Orbit scalar-only storage policy; reloadHomeEvent emission by all host reload paths remains unverified; Magnetz remains deferred; this update ran no build, test, emulator, canary, or plugin load'
    '# mapping_artifact=OrbitCs3FacadeExactSourceMapping.tsv is a deterministic row-level source-candidate record; expected references come from pinned inputs, and existing paths do not prove declaration ownership or signatures'
)
$newLedgerText = [string]::Join($newline, (@($ledgerMetadata) + @($sourceCoverageHeader) + $dataLines))
$ledgerOutputEncoding = if ($ledgerHasBom) { [System.Text.UTF8Encoding]::new($true) } else { [System.Text.UTF8Encoding]::new($false) }
[System.IO.File]::WriteAllBytes($ledgerPath, $ledgerOutputEncoding.GetBytes($newLedgerText))

$mappingMetadata = @(
    '# artifact=CS3 facade source candidate mapping'
    '# row_semantics=reference owner/member/descriptor and expected JVM name/descriptor are parsed/copied from the pinned contract or supplemental input; recorded declaration owners, symbols, and existing paths are source candidates and are not validated against declarations or signatures'
    ('# inputs=contract=413 rows sha256={0}; supplemental=5 rows sha256={1}' -f $contractHash, $supplementalHash)
    ('# upstream_head={0}; stable_v4.8.0={1}; AES supplemental source is pinned to stable v4.8.0' -f $headCommit, $stableCommit)
    ('# orbit_source_snapshot_root={0}; files={1}; manifest_sha256={2}' -f $orbitSnapshot.Root, $orbitSnapshot.Count, $orbitSnapshot.Sha256)
    ('# official_head_source_snapshot_roots={0}; files={1}; manifest_sha256={2}' -f $officialSnapshot.Roots, $officialSnapshot.Count, $officialSnapshot.Sha256)
    ('# stable_v4.8.0_AesHelper_path={0}; file_sha256={1}' -f $stableAesPath, $stableAesHash)
    ('# descriptor_rows=418; candidate={0}; ambiguous_compiler_mangling={1}; intentional_Orbit_only_supplemental={2}; excluded_context_rows=1' -f $candidateCount, $ambiguousCount, $orbitOnlyCount)
    '# proof_limit=path existence and ledger symbols do not establish declaration owner/signature, exact JVM/DEX ABI, behavior, or runtime compatibility; bytecode verification is a later phase'
    '# generation=Update-Cs3FacadeSourceCoverage.ps1; deterministic TSV rows preserve contract input order followed by supplemental input order, then the separate context row'
)
$mappingObjects = @($mappingRows | ForEach-Object {
    $properties = [ordered]@{}
    foreach ($name in $mappingHeader.Split($tab)) { $properties[$name] = [string]$_.PSObject.Properties[$name].Value }
    [pscustomobject]$properties
})
$mappingLines = @($mappingObjects | ConvertTo-Csv -Delimiter $tab -NoTypeInformation)
$mappingLines[0] = $mappingHeader
$mappingText = [string]::Join($lf, (@($mappingMetadata) + $mappingLines))
[System.IO.File]::WriteAllBytes($mappingPath, [System.Text.UTF8Encoding]::new($false).GetBytes($mappingText))

Write-Output "Updated $ledgerPath"
Write-Output "Generated $mappingPath"
Write-Output "Validated upstream HEAD=$actualHead; stable v4.8.0=$actualStable; contract=$($contractRows.Count); supplemental=$($supplementalRows.Count); exact descriptor rows=418; excluded context rows=1."
Write-Output "Disposition counts: CANDIDATE=$candidateCount; ambiguous compiler mangling=$ambiguousCount; intentional Orbit-only supplemental=$orbitOnlyCount."
Write-Output "Orbit source snapshot: files=$($orbitSnapshot.Count), manifest_sha256=$($orbitSnapshot.Sha256); official pinned source snapshot: files=$($officialSnapshot.Count), manifest_sha256=$($officialSnapshot.Sha256); stable AES file_sha256=$stableAesHash."
