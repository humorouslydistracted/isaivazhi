# Laptop embedding pipeline (GTX 1650, 7 splits)

End-to-end on your PC: scan songs → CLAP embeddings → `isaivazhi_embeddings.bin` for the app.

## Before you start

1. Copy your full library to the laptop (e.g. `C:\Music\isaivazhi_library`).
2. Know the **same folder path on Android** (from the app or a file manager), e.g.  
   `/storage/emulated/0/Music` or `/storage/emulated/0/songs_downloaded`.
3. Plug in power; disable sleep for long runs.

## Requirements

- **Python 3.12 or 3.11** (not 3.14 — PyTorch CUDA has no wheels for 3.14 yet)
- NVIDIA driver + **GTX 1650** (CUDA)
- ~5 GB free disk for venv + CLAP checkpoint

## One-time setup

From the `native` folder:

```powershell
powershell -ExecutionPolicy Bypass -File tools\embeddings\setup_laptop_embeddings.ps1
```

Copy and edit config:

```powershell
copy tools\embeddings\laptop_config.example.json tools\embeddings\laptop_config.json
notepad tools\embeddings\laptop_config.json
```

Example `laptop_config.json`:

```json
{
  "songs_dir": "C:\\Music\\isaivazhi_library",
  "phone_music_base": "/storage/emulated/0/Music",
  "split_count": 7,
  "output_dir": ""
}
```

`output_dir` empty → writes to `native/output/embeddings/`.

## Run (when songs are copied)

**Terminal 1 — embedding:**

```powershell
powershell -ExecutionPolicy Bypass -File tools\embeddings\run_laptop_embeddings.ps1
```

Or auto-open the monitor window:

```powershell
powershell -ExecutionPolicy Bypass -File tools\embeddings\run_laptop_embeddings.ps1 -Monitor
```

**Terminal 2 — live progress** (completed / pending / ETA / current file):

```powershell
powershell -ExecutionPolicy Bypass -File tools\embeddings\watch_embedding_progress.ps1
```

**Crash-safe resume:** After every song, `local_embeddings.json` and `embedding_progress.json` are saved (atomic write). If the PC crashes, loses power, or you press Ctrl+C, re-run the same `run_laptop_embeddings.ps1` command — completed paths are skipped via `_path_index`. Failed files are logged in `embedding_failures.jsonl` and retried on the next run.

**Outputs:**

| File | Purpose |
|------|---------|
| `output/embeddings/local_embeddings.json` | Checkpoint / resume (updated every song) |
| `output/embeddings/embedding_progress.json` | Live stats for the monitor script |
| `output/embeddings/embedding_failures.jsonl` | Per-file errors (optional review) |
| `output/embeddings/isaivazhi_embeddings.bin` | **Import on phone** (built when all paths complete) |

Note: `.bin` is created at the **end** of a full successful run. If interrupted mid-library, you still have resumable JSON — finish the run, then IVZ is exported automatically.

## On the phone

1. Set **7 splits** on AI & Library (should match this run).
2. Copy `isaivazhi_embeddings.bin` to the phone (USB, Drive, etc.).
3. Import via Settings or AI page.
4. Do **not** mix with an old 3-split library without re-embedding.

## Rough time (GTX 1650, 7 splits)

~4–8 s per song → thousands of tracks = **many hours to ~1–2 days**. Safe to run overnight.

## When songs are ready

Tell the agent:

- Laptop folder path (`songs_dir`)
- Android path prefix (`phone_music_base`)

The agent can fill `laptop_config.json` and start `run_laptop_embeddings.ps1` for you.
