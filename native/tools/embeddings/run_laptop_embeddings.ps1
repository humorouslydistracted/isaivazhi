# End-to-end: embed all songs (7 splits) -> local_embeddings.json -> isaivazhi_embeddings.bin
# Prerequisites: setup_laptop_embeddings.ps1 + laptop_config.json with your paths
#
# Live progress: open a SECOND terminal and run:
#   powershell -ExecutionPolicy Bypass -File tools\embeddings\watch_embedding_progress.ps1

param(
    [switch]$Monitor
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
$VenvPython = Join-Path $ScriptDir ".venv-embeddings\Scripts\python.exe"
$Config = Join-Path $ScriptDir "laptop_config.json"

if (-not (Test-Path $VenvPython)) {
    Write-Error "Missing venv. Run: powershell -ExecutionPolicy Bypass -File tools\embeddings\setup_laptop_embeddings.ps1"
}
if (-not (Test-Path $Config)) {
    Write-Error "Missing laptop_config.json. Copy laptop_config.example.json and set songs_dir + phone_music_base."
}

# Prefer laptop_config.json output_dir when present
$OutDir = Join-Path $RepoRoot "embedding_output"
if (Test-Path $Config) {
    try {
        $cfgJson = Get-Content $Config -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($cfgJson.output_dir -and (Test-Path $cfgJson.output_dir)) {
            $OutDir = $cfgJson.output_dir
        } elseif ($cfgJson.output_dir) {
            $OutDir = $cfgJson.output_dir
            New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
        }
    } catch { }
}
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

Write-Host "=== IsaiVazhi laptop embedding (config: $Config) ==="
Write-Host "Output folder: $OutDir"
Write-Host "Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ""
Write-Host "Live stats (second terminal):" -ForegroundColor Cyan
Write-Host "  powershell -ExecutionPolicy Bypass -File tools\embeddings\watch_embedding_progress.ps1"
Write-Host ""

if ($Monitor) {
    $watchScript = Join-Path $ScriptDir "watch_embedding_progress.ps1"
    Start-Process powershell -ArgumentList @(
        "-ExecutionPolicy", "Bypass",
        "-NoExit",
        "-File", $watchScript,
        "-OutputDir", $OutDir
    ) | Out-Null
    Write-Host "Opened progress monitor in a new window." -ForegroundColor Green
    Write-Host ""
}

Set-Location $ScriptDir
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"
& $VenvPython (Join-Path $ScriptDir "local_embedding_generator.py") --config $Config

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$Bin = Join-Path $OutDir "isaivazhi_embeddings.bin"
if (Test-Path $Bin) {
    $mb = [math]::Round((Get-Item $Bin).Length / 1MB, 2)
    Write-Host ""
    Write-Host "=== FINISHED ==="
    Write-Host "Import this file on your phone: $Bin ($mb MB)"
} else {
    Write-Error "Expected output not found: $Bin"
}
