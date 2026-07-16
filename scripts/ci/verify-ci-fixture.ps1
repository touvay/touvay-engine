[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$LockPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-BytesSha256([byte[]]$Bytes) {
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        ($hasher.ComputeHash($Bytes) | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        $hasher.Dispose()
    }
}

function Require-Digest([string]$Name, [string]$Value) {
    if ($Value -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "$Name is not a lowercase SHA-256 digest"
    }
}

function Set-CiOutput([string]$Name, [string]$Value) {
    if ($env:GITHUB_OUTPUT) {
        "$Name=$Value" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    }
}

$resolvedLock = (Resolve-Path -LiteralPath $LockPath).Path
$lock = Get-Content -LiteralPath $resolvedLock -Raw | ConvertFrom-Json
if ($lock.schemaVersion -ne 1) { throw "unsupported fixture-lock schema" }
if ($lock.engineCommit -notmatch '^[0-9a-f]{40}$') { throw "invalid Engine commit" }
Require-Digest "OCI digest" $lock.ociDigest
Require-Digest "pack digest" $lock.packDigest
Require-Digest "manifest digest" $lock.manifestDigest
if ($lock.keyId -notmatch '^[0-9a-f]{64}$') { throw "invalid publisher key ID" }
if ($lock.ociReference -ne "ghcr.io/touvay/touvay-engine-ci-rewrite-fixture@$($lock.ociDigest)") {
    throw "OCI reference is not pinned to the locked digest"
}

$modelIndependent = [int]$lock.expectedTestCounts.modelIndependent.total
$modelDependent = [int]$lock.expectedTestCounts.modelDependent.total
$declaredDependent = [int]$lock.expectedTestCounts.modelDependent.runtimeTck +
    [int]$lock.expectedTestCounts.modelDependent.rewriteEndToEnd +
    [int]$lock.expectedTestCounts.modelDependent.benchmarkSmoke +
    [int]$lock.expectedTestCounts.modelDependent.rewriteBenchmark
if ($modelIndependent -ne 3 -or $modelDependent -ne $declaredDependent -or
    $modelIndependent + $modelDependent -ne [int]$lock.expectedTestCounts.totalDeviceTests) {
    throw "fixture-lock test counts are inconsistent"
}

$lockDirectory = Split-Path -Parent $resolvedLock
$publicKeyPath = Join-Path $lockDirectory $lock.publicKeyFile
$publicKeyBase64 = (Get-Content -LiteralPath $publicKeyPath -Raw).Trim()
try {
    $publicKey = [Convert]::FromBase64String($publicKeyBase64)
} catch {
    throw "CI fixture public key is not valid Base64"
}
if ($publicKey.Length -ne 32 -or (Get-BytesSha256 $publicKey) -ne $lock.keyId) {
    throw "CI fixture public key does not match the locked key ID"
}

if (Test-Path -LiteralPath $OutputDirectory) {
    throw "fixture output directory already exists"
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$transportDirectory = Join-Path $OutputDirectory "oci"
$packDirectory = Join-Path $OutputDirectory "pack"
New-Item -ItemType Directory -Path $transportDirectory | Out-Null
New-Item -ItemType Directory -Path $packDirectory | Out-Null

Invoke-Checked "docker" @("pull", $lock.ociReference)
$containerOutput = @(& docker create $lock.ociReference /fixture/fixture-pack.tar)
if ($LASTEXITCODE -ne 0 -or $containerOutput.Count -eq 0) {
    throw "failed to create a stopped fixture verification container"
}
$containerId = $containerOutput[-1].Trim()
try {
    Invoke-Checked "docker" @("cp", "${containerId}:/fixture/.", $transportDirectory)
} finally {
    & docker rm -f $containerId | Out-Null
}

$archivePath = Join-Path $transportDirectory "fixture-pack.tar"
$metadataPath = Join-Path $transportDirectory "fixture-metadata.json"
$transportPublicKeyPath = Join-Path $transportDirectory "ci-fixture-publisher-public-key.b64"
foreach ($required in @($archivePath, $metadataPath, $transportPublicKeyPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "OCI fixture payload is incomplete"
    }
}
if ((Get-Sha256 $archivePath) -ne $lock.packDigest.Substring(7)) {
    throw "fixture pack digest mismatch"
}
if ((Get-Content -LiteralPath $transportPublicKeyPath -Raw).Trim() -ne $publicKeyBase64) {
    throw "OCI public key does not match the committed public key"
}

$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$metadataChecks = @(
    @("engineCommit", $metadata.engineCommit, $lock.engineCommit),
    @("runtimeVersion", $metadata.runtimeVersion, $lock.runtimeVersion),
    @("packId", $metadata.packId, $lock.packId),
    @("packVersion", $metadata.packVersion, $lock.packVersion),
    @("packDigest", $metadata.packDigest, $lock.packDigest),
    @("manifestDigest", $metadata.manifestDigest, $lock.manifestDigest),
    @("keyId", $metadata.keyId, $lock.keyId)
)
foreach ($check in $metadataChecks) {
    if ($check[1] -ne $check[2]) { throw "OCI metadata mismatch: $($check[0])" }
}

$expectedEntries = @(
    "./",
    "./files/",
    "./files/prompts/",
    "./files/prompts/text-rewrite-v1.pb",
    "./files/weights.gguf",
    "./manifest.pb",
    "./manifest.sig"
)
$entries = @(& tar -tf $archivePath)
if ($LASTEXITCODE -ne 0 -or $entries.Count -ne $expectedEntries.Count) {
    throw "fixture archive layout is invalid"
}
for ($index = 0; $index -lt $expectedEntries.Count; $index++) {
    if ($entries[$index] -ne $expectedEntries[$index]) {
        throw "fixture archive contains an unexpected entry"
    }
}
$verboseEntries = @(& tar -tvf $archivePath)
if ($LASTEXITCODE -ne 0 -or $verboseEntries.Count -ne $expectedEntries.Count) {
    throw "fixture archive types cannot be verified"
}
foreach ($entry in $verboseEntries) {
    if (-not $entry -or $entry[0] -notin @('-', 'd')) {
        throw "fixture archive contains a link or non-file entry"
    }
}
Invoke-Checked "tar" @("-xf", $archivePath, "-C", $packDirectory)

$manifestPath = Join-Path $packDirectory "manifest.pb"
$signaturePath = Join-Path $packDirectory "manifest.sig"
$promptPath = Join-Path $packDirectory "files/prompts/text-rewrite-v1.pb"
$weightsPath = Join-Path $packDirectory "files/weights.gguf"
foreach ($required in @($manifestPath, $signaturePath, $promptPath, $weightsPath)) {
    $item = Get-Item -LiteralPath $required
    $linkProperty = $item.PSObject.Properties["LinkType"]
    if (-not $item.PSIsContainer -and $linkProperty -and $linkProperty.Value) {
        throw "extracted fixture contains a link"
    }
}
if ((Get-Sha256 $manifestPath) -ne $lock.manifestDigest.Substring(7)) {
    throw "fixture manifest digest mismatch"
}
if ((Get-Item -LiteralPath $weightsPath).Length -le 0) {
    throw "fixture weights are empty"
}

$weightsSha256 = Get-Sha256 $weightsPath
Set-CiOutput "fixture-root" $packDirectory
Set-CiOutput "pack-id" $lock.packId
Set-CiOutput "pack-version" $lock.packVersion
Set-CiOutput "key-id" $lock.keyId
Set-CiOutput "public-key-base64" $publicKeyBase64
Set-CiOutput "weights-sha256" $weightsSha256
Write-Host "Verified CI fixture $($lock.packId)@$($lock.packVersion) by immutable OCI and pack digest."
