# Fetches the spike test model (see models/README.md for license and provenance).
$ErrorActionPreference = "Stop"

$Url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
$Sha256 = "74A4DA8C9FDBCD15BD1F6D01D621410D31C6FC00986F5EB687824E7B93D7A9DB"
$Dest = Join-Path $PSScriptRoot "..\models\qwen2.5-0.5b-instruct-q4_k_m.gguf"

New-Item -ItemType Directory -Force (Split-Path $Dest) | Out-Null

if (Test-Path $Dest) {
    $actual = (Get-FileHash $Dest -Algorithm SHA256).Hash
    if ($actual -eq $Sha256) {
        Write-Host "model already present and verified"
        exit 0
    }
    Write-Host "model present but hash mismatch; refetching"
    Remove-Item $Dest
}

curl.exe -sSL -o $Dest $Url
$actual = (Get-FileHash $Dest -Algorithm SHA256).Hash
if ($actual -ne $Sha256) {
    Remove-Item $Dest
    throw "Downloaded model hash $actual does not match pinned $Sha256"
}
Write-Host "model fetched and verified: $Dest"
