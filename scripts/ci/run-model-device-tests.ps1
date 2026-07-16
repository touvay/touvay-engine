[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$FixtureRoot,
    [Parameter(Mandatory = $true)]
    [string]$LockPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$lock = Get-Content -LiteralPath $LockPath -Raw | ConvertFrom-Json

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & adb @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function Install-Apk([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "required test APK is missing"
    }
    Invoke-Adb @("install", "-r", "-t", $Path)
}

function Push-VerifiedFile([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "required fixture file is missing"
    }
    $hostHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToLowerInvariant()
    $hostBytes = (Get-Item -LiteralPath $Source).Length
    Invoke-Adb @("push", $Source, $Destination)
    $deviceHashOutput = @(& adb shell sha256sum $Destination)
    if ($LASTEXITCODE -ne 0 -or $deviceHashOutput.Count -eq 0) {
        throw "device fixture hash failed"
    }
    $deviceHash = ($deviceHashOutput[0] -split '\s+')[0].ToLowerInvariant()
    $deviceBytesOutput = @(& adb shell stat -c '%s' $Destination)
    if ($LASTEXITCODE -ne 0 -or $deviceBytesOutput.Count -eq 0) {
        throw "device fixture size failed"
    }
    $deviceBytes = [long]$deviceBytesOutput[0].Trim()
    if ($deviceHash -ne $hostHash -or $deviceBytes -ne $hostBytes) {
        throw "device fixture copy does not match the verified host file"
    }
}

function Write-DiagnosticCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    Write-Host "::group::$Title"
    try {
        $diagnosticOutput = @(& adb @Arguments 2>&1)
        $diagnosticExitCode = $LASTEXITCODE
        $diagnosticOutput | ForEach-Object { Write-Host $_ }
        Write-Host "diagnostic adb exit code: $diagnosticExitCode"
    } catch {
        Write-Host "diagnostic command threw: $($_.Exception.GetType().FullName)"
    } finally {
        Write-Host "::endgroup::"
    }
}

function Write-RootCrashFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$NamePattern,
        [Parameter(Mandatory = $true)][string]$TitlePrefix
    )
    $entries = @(& adb shell ls -1 $Directory 2>&1)
    $listExitCode = $LASTEXITCODE
    Write-Host "$TitlePrefix directory listing exit code: $listExitCode"
    $entries | ForEach-Object { Write-Host $_ }
    if ($listExitCode -ne 0) {
        return
    }
    foreach ($entry in $entries) {
        $name = "$entry".Trim()
        if ($name -match $NamePattern) {
            Write-DiagnosticCommand `
                -Title "$TitlePrefix $name" `
                -Arguments @("shell", "cat", "$Directory/$name")
        }
    }
}

function Write-InstrumentationCrashDiagnostics {
    param(
        [Parameter(Mandatory = $true)][string]$Runner,
        [Parameter(Mandatory = $true)][int]$InstrumentationExitCode
    )
    Write-Host "instrumentation runner: $Runner"
    Write-Host "instrumentation adb exit code: $InstrumentationExitCode"
    Write-DiagnosticCommand -Title "crash logcat" `
        -Arguments @("logcat", "-b", "crash", "-d", "-v", "threadtime")
    Write-DiagnosticCommand -Title "full logcat" `
        -Arguments @("logcat", "-b", "all", "-d", "-v", "threadtime")
    Write-DiagnosticCommand -Title "application exit info" `
        -Arguments @("shell", "dumpsys", "activity", "exit-info", "com.touvay.runtime.llamacpp.test")
    foreach ($tag in @(
        "data_app_crash",
        "data_app_native_crash",
        "data_app_anr",
        "SYSTEM_TOMBSTONE"
    )) {
        Write-DiagnosticCommand -Title "DropBox $tag" `
            -Arguments @("shell", "dumpsys", "dropbox", "--print", $tag)
    }

    Write-Host "::group::adb root"
    $rootOutput = @(& adb root 2>&1)
    $rootExitCode = $LASTEXITCODE
    $rootOutput | ForEach-Object { Write-Host $_ }
    Write-Host "adb root exit code: $rootExitCode"
    Write-Host "::endgroup::"
    if ($rootExitCode -eq 0) {
        Write-DiagnosticCommand -Title "wait for rooted adb" -Arguments @("wait-for-device")
        Write-RootCrashFiles `
            -Directory "/data/tombstones" `
            -NamePattern '^tombstone_[0-9]+$' `
            -TitlePrefix "tombstone"
        Write-RootCrashFiles `
            -Directory "/data/anr" `
            -NamePattern '^(anr_|traces).*' `
            -TitlePrefix "ANR trace"
    }
}

function Invoke-Instrumentation {
    param(
        [Parameter(Mandatory = $true)][string]$Runner,
        [Parameter(Mandatory = $true)][string]$Classes,
        [Parameter(Mandatory = $true)][int]$ExpectedCount,
        [string[]]$AdditionalArguments = @()
    )
    $arguments = @("shell", "am", "instrument", "-w", "-r", "-e", "class", $Classes)
    $arguments += $AdditionalArguments
    $arguments += $Runner
    & adb logcat -G 16M 2>&1 | ForEach-Object { Write-Host $_ }
    Write-Host "logcat resize exit code: $LASTEXITCODE"
    & adb logcat -c 2>&1 | ForEach-Object { Write-Host $_ }
    Write-Host "logcat clear exit code: $LASTEXITCODE"
    $output = @(& adb @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    $text = $output -join "`n"
    $expectedPattern = "OK \($ExpectedCount tests?\)"
    if ($exitCode -ne 0 -or $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' -or
        $text -notmatch $expectedPattern) {
        Write-InstrumentationCrashDiagnostics `
            -Runner $Runner `
            -InstrumentationExitCode $exitCode
        throw "instrumentation did not complete exactly $ExpectedCount tests"
    }
}

$manifest = Join-Path $FixtureRoot "manifest.pb"
$signature = Join-Path $FixtureRoot "manifest.sig"
$prompt = Join-Path $FixtureRoot "files/prompts/text-rewrite-v1.pb"
$weights = Join-Path $FixtureRoot "files/weights.gguf"
foreach ($required in @($manifest, $signature, $prompt, $weights)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "verified fixture root is incomplete"
    }
}

Install-Apk (Join-Path $repo "apps/demo/build/outputs/apk/debug/demo-debug.apk")
Install-Apk (Join-Path $repo "apps/demo/build/outputs/apk/androidTest/debug/demo-debug-androidTest.apk")
Install-Apk (Join-Path $repo "apps/benchmark/build/outputs/apk/debug/benchmark-debug.apk")
Install-Apk (Join-Path $repo "apps/benchmark/build/outputs/apk/androidTest/debug/benchmark-debug-androidTest.apk")
Install-Apk (Join-Path $repo "runtime/runtime-llamacpp/build/outputs/apk/androidTest/debug/runtime-llamacpp-debug-androidTest.apk")

Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.touvay.demo/.MainActivity")
Invoke-Adb @("shell", "am", "force-stop", "com.touvay.demo")
Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.touvay.benchmark/.FixtureProvisioningActivity")
Invoke-Adb @("shell", "am", "force-stop", "com.touvay.benchmark")
$runtimeFiles = "/sdcard/Android/data/com.touvay.runtime.llamacpp.test/files"
Invoke-Adb @(
    "shell", "am", "start", "-W", "-n",
    "com.touvay.runtime.llamacpp.test/com.touvay.runtime.llamacpp.FixtureProvisioningActivity"
)
Invoke-Adb @("shell", "am", "force-stop", "com.touvay.runtime.llamacpp.test")

$demoRoot = "/sdcard/Android/data/com.touvay.demo/files/touvay-demo-pack"
Push-VerifiedFile $manifest "$demoRoot/manifest.pb"
Push-VerifiedFile $signature "$demoRoot/manifest.sig"
Push-VerifiedFile $prompt "$demoRoot/files/prompts/text-rewrite-v1.pb"
Push-VerifiedFile $weights "$demoRoot/files/weights.gguf"
Push-VerifiedFile $weights "/sdcard/Android/data/com.touvay.benchmark/files/model.gguf"
Push-VerifiedFile $weights "$runtimeFiles/model.gguf"

$runtimeCount = [int]$lock.expectedTestCounts.modelDependent.runtimeTck
$benchmarkCount = [int]$lock.expectedTestCounts.modelDependent.benchmarkSmoke
$demoCount = [int]$lock.expectedTestCounts.modelDependent.rewriteEndToEnd +
    [int]$lock.expectedTestCounts.modelDependent.rewriteBenchmark
if ($runtimeCount + $benchmarkCount + $demoCount -ne
    [int]$lock.expectedTestCounts.modelDependent.total) {
    throw "model-dependent test counts are inconsistent"
}

Invoke-Instrumentation `
    -Runner "com.touvay.runtime.llamacpp.test/androidx.test.runner.AndroidJUnitRunner" `
    -Classes "com.touvay.runtime.llamacpp.LlamaCppTck" `
    -ExpectedCount $runtimeCount
Invoke-Instrumentation `
    -Runner "com.touvay.benchmark.test/androidx.test.runner.AndroidJUnitRunner" `
    -Classes "com.touvay.benchmark.BenchmarkSmokeTest" `
    -ExpectedCount $benchmarkCount `
    -AdditionalArguments @("-e", "quick", "true", "-e", "modelVariant", $lock.packId)
Invoke-Instrumentation `
    -Runner "com.touvay.demo.test/androidx.test.runner.AndroidJUnitRunner" `
    -Classes "com.touvay.demo.RewriteEndToEndTest,com.touvay.demo.RewriteBenchmarkTest" `
    -ExpectedCount $demoCount `
    -AdditionalArguments @(
        "-e", "quick", "true",
        "-e", "enforceThresholds", "false",
        "-e", "modelId", $lock.packId,
        "-e", "modelVersion", $lock.packVersion,
        "-e", "modelSha256", (Get-FileHash -LiteralPath $weights -Algorithm SHA256).Hash.ToLowerInvariant()
    )

Write-Host "Connected Model-Backed verified: $($lock.expectedTestCounts.modelDependent.total) tests."
