# Live terminal dashboard for laptop embedding runs.
# Reads embedding_progress.json (refreshes every 2s). Compatible with Windows PowerShell 5.1+.

param(
    [int]$IntervalSec = 2,
    [string]$OutputDir = ""
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($OutputDir) {
    $ProgressFile = Join-Path $OutputDir "embedding_progress.json"
} else {
    $RepoRoot = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
    $CustomOut = Join-Path $RepoRoot "embedding_output\embedding_progress.json"
    $LegacyOut = Join-Path $RepoRoot "output\embeddings\embedding_progress.json"
    if (Test-Path $CustomOut) { $ProgressFile = $CustomOut }
    elseif (Test-Path $LegacyOut) { $ProgressFile = $LegacyOut }
    else { $ProgressFile = $CustomOut }
}

function Format-Duration {
    param([double]$Seconds)
    if ($null -eq $Seconds -or $Seconds -lt 0) { return "n/a" }
    $ts = [TimeSpan]::FromSeconds([math]::Max(0, $Seconds))
    if ($ts.TotalHours -ge 1) {
        $h = [int]$ts.TotalHours
        return ($h.ToString() + "h " + $ts.Minutes.ToString() + "m")
    }
    if ($ts.TotalMinutes -ge 1) {
        $m = [int]$ts.TotalMinutes
        return ($m.ToString() + "m " + $ts.Seconds.ToString() + "s")
    }
    return ([int]$ts.TotalSeconds).ToString() + "s"
}

function Read-Progress {
    if (-not (Test-Path $ProgressFile)) { return $null }
    try {
        $raw = Get-Content $ProgressFile -Raw -Encoding UTF8
        return ($raw | ConvertFrom-Json)
    } catch {
        return $null
    }
}

Write-Host "Watching: $ProgressFile"
Write-Host "Press Ctrl+C to exit monitor (embedding keeps running in the other window)."
Write-Host ""

while ($true) {
    $p = Read-Progress
    Clear-Host
    Write-Host "=== IsaiVazhi Embedding Progress ===" -ForegroundColor Cyan
    $now = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host ("Updated: " + $now)
    Write-Host ""

    if ($null -eq $p) {
        Write-Host "Waiting for embedding run to start..." -ForegroundColor Yellow
        Write-Host "Start: powershell -File tools\embeddings\run_laptop_embeddings.ps1"
    } else {
        $status = $p.status
        $color = "Gray"
        if ($status -eq "completed") { $color = "Green" }
        elseif ($status -eq "interrupted") { $color = "Yellow" }
        elseif ($status -eq "running") { $color = "White" }

        Write-Host ("Status: " + $status) -ForegroundColor $color
        Write-Host ("Splits:  " + $p.split_count)
        Write-Host ""

        $total = [int]$p.total
        $done = [int]$p.completed
        $pending = [int]$p.pending
        if ($total -gt 0) {
            $pct = [math]::Min(100, [math]::Round(100.0 * $done / $total, 1))
            $barLen = 40
            $filled = [int][math]::Round($barLen * $done / $total)
            $empty = $barLen - $filled
            $bar = ("=" * $filled) + ("-" * $empty)
            Write-Host ("[" + $bar + "] " + $pct + "%")
        }

        Write-Host ""
        Write-Host ("  Total:     " + $total.ToString().PadLeft(8))
        Write-Host ("  Completed: " + $done.ToString().PadLeft(8)) -ForegroundColor Green
        Write-Host ("  Pending:   " + $pending.ToString().PadLeft(8)) -ForegroundColor Yellow
        Write-Host ("  New CLAP:  " + $p.processed_new.ToString().PadLeft(8))
        Write-Host ("  Reused:    " + $p.reused.ToString().PadLeft(8))
        Write-Host ("  Skipped:   " + $p.skipped.ToString().PadLeft(8))
        Write-Host ("  Failed:    " + $p.failed.ToString().PadLeft(8))
        if ($p.session_failed -gt 0) {
            Write-Host ("  Failed (session): " + $p.session_failed) -ForegroundColor Red
        }
        Write-Host ""
        Write-Host ("  Elapsed:   " + (Format-Duration $p.elapsed_sec))
        Write-Host ("  ETA:       " + (Format-Duration $p.eta_sec))
        if ($p.avg_sec_per_song) {
            $avg = [math]::Round([double]$p.avg_sec_per_song, 1)
            Write-Host ("  Avg/song:  " + $avg + "s (last 20 embeds)")
        }
        if ($p.songs_per_hour) {
            $rate = [math]::Round([double]$p.songs_per_hour, 0)
            Write-Host ("  Rate:      " + $rate + " new embeds/hour")
        }
        Write-Host ""
        $cur = $p.current_file
        if ($cur) {
            Write-Host ("  Current:   " + $cur) -ForegroundColor Cyan
        }
        if ($p.recent_failures -and $p.recent_failures.Count -gt 0) {
            Write-Host ""
            Write-Host "  Recent failures:" -ForegroundColor Red
            $p.recent_failures | Select-Object -Last 5 | ForEach-Object {
                $name = Split-Path $_.path -Leaf
                if (-not $name) { $name = $_.path }
                Write-Host ("    - " + $name + ": " + $_.error)
            }
        }
        if ($status -eq "completed") {
            Write-Host ""
            Write-Host "Run finished. Check native\embedding_output\isaivazhi_embeddings.bin" -ForegroundColor Green
        }
        if ($status -eq "interrupted") {
            Write-Host ""
            Write-Host "Interrupted - re-run run_laptop_embeddings.ps1 to resume." -ForegroundColor Yellow
        }
    }

    Write-Host ""
    Write-Host ("Refresh every " + $IntervalSec + "s (Ctrl+C to close monitor only)") -ForegroundColor DarkGray
    Start-Sleep -Seconds $IntervalSec
}
