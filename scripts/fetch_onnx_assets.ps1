# Downloads on-device CLAP ONNX assets from a dedicated GitHub Release.
# Run from repo root (native/):  .\scripts\fetch_onnx_assets.ps1

$ErrorActionPreference = "Stop"

$OnnxModelTag = "onnx-model-v1"
$Repo = "humorouslydistracted/isaivazhi"
$Root = Split-Path -Parent $PSScriptRoot
$AssetsDir = Join-Path $Root "app\src\main\assets"

$Files = @(
    "clap_audio_encoder.onnx",
    "clap_audio_encoder.onnx.data"
)

if (-not (Test-Path $AssetsDir)) {
    New-Item -ItemType Directory -Path $AssetsDir -Force | Out-Null
}

$BaseUrl = "https://github.com/$Repo/releases/download/$OnnxModelTag"

foreach ($name in $Files) {
    $dest = Join-Path $AssetsDir $name
    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)) {
        Write-Host "Already present: $name ($((Get-Item $dest).Length) bytes) — skipping"
        continue
    }
    $url = "$BaseUrl/$name"
    Write-Host "Downloading $name ..."
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    Write-Host "  -> $dest ($((Get-Item $dest).Length) bytes)"
}

Write-Host ""
Write-Host "Done. Build with: .\gradlew.bat assembleDebug"
Write-Host "Release: https://github.com/$Repo/releases/tag/$OnnxModelTag"
