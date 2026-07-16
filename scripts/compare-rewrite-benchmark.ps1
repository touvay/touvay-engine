param(
    [Parameter(Mandatory = $true)][string]$BaselineRewrite,
    [Parameter(Mandatory = $true)][string]$CandidateRewrite,
    [Parameter(Mandatory = $true)][string]$BaselineRuntime,
    [Parameter(Mandatory = $true)][string]$CandidateRuntime
)

$ErrorActionPreference = "Stop"
$baseline = Get-Content -Raw $BaselineRewrite | ConvertFrom-Json
$candidate = Get-Content -Raw $CandidateRewrite | ConvertFrom-Json
$baselineRuntimeJson = Get-Content -Raw $BaselineRuntime | ConvertFrom-Json
$candidateRuntimeJson = Get-Content -Raw $CandidateRuntime | ConvertFrom-Json
$violations = New-Object System.Collections.Generic.List[string]

if ($baseline.corpus.sha256 -ne $candidate.corpus.sha256) {
    $violations.Add("corpus SHA-256 differs")
}
if ([bool]$baseline.quick -or [bool]$candidate.quick) {
    $violations.Add("quick runs cannot be used for baseline comparison")
}
if ($baseline.device.model -ne $candidate.device.model -or
    $baseline.device.sdk -ne $candidate.device.sdk -or
    $baseline.device.totalRamBytes -ne $candidate.device.totalRamBytes) {
    $violations.Add("device identity/class differs")
}

function Maximum([string]$Name, [double]$Actual, [double]$Limit) {
    if ($Actual -gt $Limit) { $violations.Add("$Name=$Actual exceeds $Limit") }
}

function Minimum([string]$Name, [double]$Actual, [double]$Limit) {
    if ($Actual -lt $Limit) { $violations.Add("$Name=$Actual is below $Limit") }
}

Minimum "qualityCompositeMean" $candidate.summary.qualityCompositeMean `
    ($baseline.summary.qualityCompositeMean - 0.02)
Minimum "qualityCompositeMin" $candidate.summary.qualityCompositeMin 0.40
foreach ($property in $baseline.summary.categoryQualityMeans.PSObject.Properties) {
    $name = $property.Name
    $candidateValue = [double]$candidate.summary.categoryQualityMeans.$name
    Minimum "category.$name" $candidateValue ([double]$property.Value - 0.03)
}

Maximum "warmTtftP95Ms" $candidate.summary.warmTtftP95Ms `
    ($baseline.summary.warmTtftP95Ms * 1.10)
Maximum "coldTtftP95Ms" $candidate.summary.coldTtftP95Ms `
    ($baseline.summary.coldTtftP95Ms * 1.10)
Maximum "warmEndToEndP95Ms" $candidate.summary.warmEndToEndP95Ms `
    ($baseline.summary.warmEndToEndP95Ms * 1.10)
Maximum "peakEnginePssKb" $candidate.summary.peakEnginePssKb `
    ($baseline.summary.peakEnginePssKb * 1.10)
Maximum "cancellationP95Ms" $candidate.summary.cancellationP95Ms `
    ($baseline.summary.cancellationP95Ms + 50.0)

$baselineThroughput = [double]$baselineRuntimeJson.shortPromptWarm.decodeTokPerS
$candidateThroughput = [double]$candidateRuntimeJson.shortPromptWarm.decodeTokPerS
Minimum "decodeTokPerS" $candidateThroughput ($baselineThroughput * 0.92)
Minimum "decodeTokPerS.absolute" $candidateThroughput 8.0

$baselineEnergy = $baseline.resources.energyPerWarmCaseMWh
$candidateEnergy = $candidate.resources.energyPerWarmCaseMWh
if ($null -ne $baselineEnergy -and $null -ne $candidateEnergy) {
    Maximum "energyPerWarmCaseMWh" ([double]$candidateEnergy) ([double]$baselineEnergy * 1.10)
}

if (-not [bool]$candidate.acceptance.passed) {
    foreach ($item in $candidate.acceptance.violations) {
        $violations.Add("absolute acceptance: $item")
    }
}

if ($violations.Count -gt 0) {
    Write-Error ("Rewrite benchmark regression failed:`n - " + ($violations -join "`n - "))
    exit 1
}

Write-Host "Rewrite benchmark regression policy passed."
