# Fetches the pinned upstream llama.cpp source for the runtime-llamacpp-spike module.
# Pin policy: exact tag + commit recorded here; bumping the pin is a reviewed change.
$ErrorActionPreference = "Stop"

$Tag = "b5199"
$Commit = "ced44be34290fab450f8344efa047d8a08e723b4"
$Dest = Join-Path $PSScriptRoot "..\runtime\runtime-llamacpp-spike\third_party\llama.cpp"

if (Test-Path (Join-Path $Dest "CMakeLists.txt")) {
    $actual = git -C $Dest rev-parse HEAD
    if ($actual -eq $Commit) {
        Write-Host "llama.cpp already present at $Tag ($Commit)"
        exit 0
    }
    Write-Host "llama.cpp present but at $actual; refetching $Tag"
    Remove-Item -Recurse -Force $Dest
}

git clone --depth 1 --branch $Tag https://github.com/ggml-org/llama.cpp.git $Dest
$actual = git -C $Dest rev-parse HEAD
if ($actual -ne $Commit) {
    throw "Pinned tag $Tag resolved to $actual, expected $Commit — investigate before building."
}
Write-Host "llama.cpp fetched at $Tag ($Commit)"
