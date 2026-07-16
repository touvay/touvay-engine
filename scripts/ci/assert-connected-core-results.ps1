[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$LockPath,
    [Parameter(Mandatory = $true)]
    [string]$ResultDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$lock = Get-Content -LiteralPath $LockPath -Raw | ConvertFrom-Json
$expected = [int]$lock.expectedTestCounts.modelIndependent.total
$reports = @(Get-ChildItem -LiteralPath $ResultDirectory -Filter "TEST-*.xml" -File)
if ($reports.Count -eq 0) { throw "Connected Core produced no JUnit reports" }

$tests = 0
$failures = 0
$errors = 0
$skipped = 0
$classes = @()
foreach ($report in $reports) {
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
    $suite = $xml.testsuite
    $tests += [int]$suite.tests
    $failures += [int]$suite.failures
    $errors += [int]$suite.errors
    $skipped += [int]$suite.skipped
    $classes += @($suite.testcase | ForEach-Object { $_.classname })
}

$allowedClasses = @(
    "com.touvay.demo.EchoCrossProcessTest",
    "com.touvay.demo.EngineResilienceCrossProcessTest"
)
$unexpected = @($classes | Where-Object { $_ -notin $allowedClasses } | Select-Object -Unique)
if ($tests -ne $expected -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0 -or
    $unexpected.Count -ne 0 -or ($classes | Select-Object -Unique).Count -ne $allowedClasses.Count) {
    throw "Connected Core result mismatch: tests=$tests failures=$failures errors=$errors skipped=$skipped"
}
Write-Host "Connected Core verified: $tests tests, zero failures, errors, or skips."
