param(
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [Parameter(Mandatory = $true)]
    [string]$ModelVariant,
    [string]$ModelId = "touvay.demo.qwen2.5-0.5b-rewrite",
    [string]$ModelVersion = "1.0.0",
    [string]$Serial,
    [switch]$Quick,
    [switch]$EnforceThresholds,
    [switch]$SkipQuality
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$model = (Resolve-Path $ModelPath).Path
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $model)) { throw "model not found: $model" }

$adbPrefix = @()
if ($Serial) { $adbPrefix = @("-s", $Serial) }

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @adbPrefix @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
}

function Install-Apk([string]$Path) {
    if (-not (Test-Path $Path)) { throw "APK not found: $Path" }
    Invoke-Adb install -r $Path
}

$safeVariant = $ModelVariant -replace '[^A-Za-z0-9._-]', '_'
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$results = Join-Path $repo "benchmarks\results\$stamp-$safeVariant"
New-Item -ItemType Directory -Force -Path $results | Out-Null
$modelSha256 = (Get-FileHash $model -Algorithm SHA256).Hash.ToLowerInvariant()
$quickValue = if ($Quick) { "true" } else { "false" }

Push-Location $repo
try {
    .\gradlew.bat :apps:benchmark:assembleDebug :apps:benchmark:assembleDebugAndroidTest `
        :apps:demo:assembleDebug :apps:demo:assembleDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "benchmark APK build failed" }

    Install-Apk "$repo\apps\benchmark\build\outputs\apk\debug\benchmark-debug.apk"
    Install-Apk "$repo\apps\benchmark\build\outputs\apk\androidTest\debug\benchmark-debug-androidTest.apk"
    Install-Apk "$repo\apps\demo\build\outputs\apk\debug\demo-debug.apk"
    Install-Apk "$repo\apps\demo\build\outputs\apk\androidTest\debug\demo-debug-androidTest.apk"

    Invoke-Adb shell am start -W -n com.touvay.benchmark/.MainActivity
    Start-Sleep -Seconds 1
    Invoke-Adb shell am force-stop com.touvay.benchmark
    Invoke-Adb push $model /sdcard/Android/data/com.touvay.benchmark/files/model.gguf

    Invoke-Adb shell dumpsys batterystats --reset
    Invoke-Adb shell am instrument -w -r -e class com.touvay.benchmark.BenchmarkSmokeTest `
        -e quick $quickValue -e modelVariant $ModelVariant `
        com.touvay.benchmark.test/androidx.test.runner.AndroidJUnitRunner
    Invoke-Adb pull /sdcard/Android/data/com.touvay.benchmark/files/production-benchmark-result.json `
        (Join-Path $results "runtime-benchmark.json")
    if ($EnforceThresholds) {
        $runtimeResult = Get-Content -Raw (Join-Path $results "runtime-benchmark.json") | ConvertFrom-Json
        if ([double]$runtimeResult.shortPromptWarm.decodeTokPerS -lt 8.0) {
            throw "Runtime decode throughput is below 8 tokens/s"
        }
    }

    if (-not $SkipQuality) {
        Invoke-Adb shell am start -W -n com.touvay.demo/.MainActivity
        Start-Sleep -Seconds 2
        Invoke-Adb shell am force-stop com.touvay.demo
        Invoke-Adb push $model /sdcard/Android/data/com.touvay.demo/files/touvay-demo-pack/files/weights.gguf
        Invoke-Adb shell am instrument -w -r -e class com.touvay.demo.RewriteBenchmarkTest `
            -e quick $quickValue -e enforceThresholds false `
            -e modelId $ModelId -e modelVersion $ModelVersion -e modelSha256 $modelSha256 `
            com.touvay.demo.test/androidx.test.runner.AndroidJUnitRunner
        Invoke-Adb pull /sdcard/Android/data/com.touvay.demo/files/rewrite-benchmark-result.json `
            (Join-Path $results "rewrite-benchmark.json")
        if ($EnforceThresholds) {
            $rewriteResult = Get-Content -Raw (Join-Path $results "rewrite-benchmark.json") | ConvertFrom-Json
            if (-not [bool]$rewriteResult.acceptance.passed) {
                throw "Rewrite acceptance failed: $($rewriteResult.acceptance.violations -join '; ')"
            }
        }
    }

    (& $adb @adbPrefix shell dumpsys thermalservice) | Set-Content `
        -Encoding UTF8 (Join-Path $results "thermalservice.txt")
    (& $adb @adbPrefix shell dumpsys batterystats --checkin) | Set-Content `
        -Encoding UTF8 (Join-Path $results "batterystats-checkin.csv")
    (& $adb @adbPrefix shell getprop) | Set-Content `
        -Encoding UTF8 (Join-Path $results "device-properties.txt")

    [ordered]@{
        schemaVersion = 1
        timestamp = $stamp
        modelVariant = $ModelVariant
        modelId = $ModelId
        modelVersion = $ModelVersion
        modelSha256 = $modelSha256
        modelBytes = (Get-Item $model).Length
        quick = [bool]$Quick
        thresholdsEnforced = [bool]$EnforceThresholds
        qualitySkipped = [bool]$SkipQuality
    } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $results "run-manifest.json")

    Write-Host "Rewrite benchmark results: $results"
} finally {
    Pop-Location
}
