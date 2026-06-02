# One-time setup: Python venv + CUDA PyTorch + embedding deps (GTX 1650)
# Run from repo:  powershell -ExecutionPolicy Bypass -File tools\embeddings\setup_laptop_embeddings.ps1

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir = Join-Path $ScriptDir ".venv-embeddings"

# PyTorch CUDA wheels require Python 3.10–3.12 (not 3.14).
$PyLauncher = "py"
$PyVersion = "-3.12"
$null = & $PyLauncher $PyVersion -c "import sys; print(sys.version)" 2>$null
if ($LASTEXITCODE -ne 0) {
    $PyVersion = "-3.11"
    $null = & $PyLauncher $PyVersion -c "import sys; print(sys.version)" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Need Python 3.12 or 3.11 (install from python.org). PyTorch has no CUDA build for 3.14."
    }
}

if (Test-Path $VenvDir) {
    Write-Host "Removing old venv (wrong Python version?) ..."
    Remove-Item -Recurse -Force $VenvDir
}

Write-Host "Creating venv at $VenvDir with $PyLauncher $PyVersion ..."
& $PyLauncher $PyVersion -m venv $VenvDir
$Python = Join-Path $VenvDir "Scripts\python.exe"
$Pip = Join-Path $VenvDir "Scripts\pip.exe"

& $Python -m pip install --upgrade pip

Write-Host "Installing PyTorch with CUDA 12.1 (GTX 1650)..."
& $Pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121

Write-Host "Installing embedding dependencies..."
& $Pip install -r (Join-Path $ScriptDir "requirements-embeddings.txt")

Write-Host ""
Write-Host "Verifying CUDA..."
& $Python -c "import torch; print('cuda:', torch.cuda.is_available()); print('device:', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'cpu')"

Write-Host ""
Write-Host "Setup complete. Next:"
Write-Host "  1. Copy laptop_config.example.json -> laptop_config.json"
Write-Host "  2. Edit songs_dir and phone_music_base"
Write-Host "  3. Run: powershell -File tools\embeddings\run_laptop_embeddings.ps1"
