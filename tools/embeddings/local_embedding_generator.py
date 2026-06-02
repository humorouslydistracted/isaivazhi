#!/usr/bin/env python3
"""
IsaiVazhi CLAP embedding generator — local laptop (Windows + NVIDIA CUDA).

Matches mobile EmbeddingService / embedding_config.py (48 kHz, 10 s windows,
content hash, L2-normalized 512-d mean).

Usage:
  python local_embedding_generator.py --config laptop_config.json
  python local_embedding_generator.py --songs-dir D:\\Music --phone-base /storage/emulated/0/Music --splits 7

Outputs (in output_dir, default: <repo>/output/embeddings):
  local_embeddings.json    — checkpoint + resume (saved after every song)
  embedding_progress.json    — live stats for watch_embedding_progress.ps1
  embedding_failures.jsonl   — per-file errors (failed paths retry on resume)
  isaivazhi_embeddings.bin   — IVZ1 for fast app import (end of run)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import sys
import time
import urllib.request
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from embedding_config import normalize_split_count, window_positions
from ivz_format import write_ivz

SAMPLE_RATE = 48000
WINDOW_SAMPLES = SAMPLE_RATE * 10
CONTENT_HASH_SECONDS = 30
AUDIO_EXTENSIONS = {".mp3", ".flac", ".wav", ".aac", ".m4a", ".ogg", ".opus", ".wma"}
CKPT_URL = "https://huggingface.co/lukewys/laion_clap/resolve/main/music_audioset_epoch_15_esc_90.14.pt"
CKPT_NAME = "music_audioset_epoch_15_esc_90.14.pt"
PROGRESS_FILENAME = "embedding_progress.json"
FAILURES_FILENAME = "embedding_failures.jsonl"
ROLLING_ETA_WINDOW = 20


def configure_console_utf8() -> None:
    """Avoid UnicodeEncodeError on Windows cp1252 when song titles use Tamil/etc."""
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def console_print(msg: str, **kwargs) -> None:
    try:
        print(msg, **kwargs)
    except UnicodeEncodeError:
        end = kwargs.get("end", "\n")
        flush = kwargs.get("flush", False)
        safe = msg.encode("ascii", errors="replace").decode("ascii")
        print(safe, end=end, flush=flush)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Generate IsaiVazhi embeddings on a local GPU/CPU.")
    p.add_argument("--config", type=Path, help="JSON config (songs_dir, phone_music_base, split_count, output_dir)")
    p.add_argument("--songs-dir", type=Path, help="Folder containing audio files on this PC")
    p.add_argument(
        "--phone-base",
        dest="phone_music_base",
        type=str,
        help="Android path prefix for the same files (MediaStore file paths)",
    )
    p.add_argument("--splits", type=int, default=7, choices=[3, 5, 7], help="CLAP windows per song (default: 7)")
    p.add_argument("--output-dir", type=Path, help="Writable output folder (default: <repo>/output/embeddings)")
    p.add_argument("--checkpoint", type=Path, help="CLAP .pt checkpoint (default: next to this script)")
    p.add_argument("--json-only", action="store_true", help="Skip IVZ export at end")
    p.add_argument("--checkpoint-every", type=int, default=1, help="Save JSON every N path updates (default: 1)")
    return p.parse_args()


def load_config(args: argparse.Namespace) -> dict:
    cfg: dict = {
        "split_count": 7,
        "output_dir": "",
        "songs_dir": "",
        "phone_music_base": "/storage/emulated/0/Music",
        "checkpoint_every": 1,
    }
    if args.config and args.config.is_file():
        with args.config.open(encoding="utf-8") as f:
            cfg.update(json.load(f))
    if args.songs_dir:
        cfg["songs_dir"] = str(args.songs_dir)
    if args.phone_music_base:
        cfg["phone_music_base"] = args.phone_music_base
    if args.output_dir:
        cfg["output_dir"] = str(args.output_dir)
    cfg["split_count"] = normalize_split_count(args.splits or cfg.get("split_count", 7))
    checkpoint_every = int(cfg.get("checkpoint_every", args.checkpoint_every))

    songs_dir = Path(cfg.get("songs_dir") or "")
    if not songs_dir.is_dir():
        raise SystemExit(f"songs_dir is not a directory: {songs_dir!r}\nEdit laptop_config.json or pass --songs-dir")

    phone_base = (cfg.get("phone_music_base") or "/storage/emulated/0/Music").strip().rstrip("/")

    repo_root = SCRIPT_DIR.parent.parent
    out = Path(cfg["output_dir"]) if cfg.get("output_dir") else repo_root / "output" / "embeddings"
    out.mkdir(parents=True, exist_ok=True)

    ckpt = args.checkpoint or SCRIPT_DIR / CKPT_NAME
    return {
        "songs_dir": songs_dir.resolve(),
        "phone_music_base": phone_base,
        "split_count": cfg["split_count"],
        "output_dir": out.resolve(),
        "checkpoint": ckpt.resolve(),
        "window_positions": window_positions(cfg["split_count"]),
        "checkpoint_every": max(1, checkpoint_every),
        "json_only": args.json_only,
    }


def atomic_write_json(path: Path, data: dict | list) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(data, f)
    tmp.replace(path)


def save_json(path: Path, data: dict) -> None:
    atomic_write_json(path, data)


def count_completed_paths(local_embeddings: dict) -> int:
    path_index = local_embeddings.get("_path_index")
    if isinstance(path_index, dict):
        return len(path_index)
    return 0


@dataclass
class RunStats:
    total: int = 0
    processed: int = 0
    reused: int = 0
    skipped: int = 0
    failed: int = 0
    session_failed: int = 0
    current_file: str = ""
    started_at_ms: int = 0
    embed_durations: deque = field(default_factory=lambda: deque(maxlen=ROLLING_ETA_WINDOW))
    recent_failures: list = field(default_factory=list)
    interrupted: bool = False
    status: str = "running"

    def rolling_avg_sec(self) -> float | None:
        if not self.embed_durations:
            return None
        return sum(self.embed_durations) / len(self.embed_durations)

    def record_failure(self, path: str, error: str) -> None:
        entry = {"path": path, "error": error, "timestamp_ms": int(time.time() * 1000)}
        self.recent_failures.append(entry)
        if len(self.recent_failures) > 10:
            self.recent_failures.pop(0)


def build_progress_payload(
    stats: RunStats,
    local_embeddings: dict,
    split_count: int,
) -> dict[str, Any]:
    completed = count_completed_paths(local_embeddings)
    pending = max(0, stats.total - completed)
    now_ms = int(time.time() * 1000)
    elapsed_sec = (now_ms - stats.started_at_ms) / 1000.0 if stats.started_at_ms else 0.0
    avg = stats.rolling_avg_sec()
    eta_sec = int(pending * avg) if avg is not None and pending > 0 else None
    songs_per_hour = (stats.processed / elapsed_sec * 3600.0) if elapsed_sec > 0 and stats.processed > 0 else None

    return {
        "status": stats.status,
        "split_count": split_count,
        "total": stats.total,
        "completed": completed,
        "pending": pending,
        "failed": stats.failed,
        "session_failed": stats.session_failed,
        "reused": stats.reused,
        "skipped": stats.skipped,
        "processed_new": stats.processed,
        "current_file": stats.current_file,
        "started_at_ms": stats.started_at_ms,
        "updated_at_ms": now_ms,
        "elapsed_sec": round(elapsed_sec, 1),
        "eta_sec": eta_sec,
        "avg_sec_per_song": round(avg, 2) if avg is not None else None,
        "songs_per_hour": round(songs_per_hour, 1) if songs_per_hour is not None else None,
        "recent_failures": list(stats.recent_failures),
    }


def write_progress(progress_path: Path, payload: dict) -> None:
    atomic_write_json(progress_path, payload)


def append_failure_log(failures_path: Path, phone_path: str, local_path: str, error: str) -> None:
    line = json.dumps(
        {
            "phone_path": phone_path,
            "local_path": local_path,
            "error": error,
            "timestamp_ms": int(time.time() * 1000),
        },
        ensure_ascii=False,
    )
    with failures_path.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def compute_content_hash(audio: np.ndarray) -> str:
    chunk = audio[: SAMPLE_RATE * CONTENT_HASH_SECONDS]
    int16_data = np.clip(chunk * 32767, -32768, 32767).astype(np.int16)
    return hashlib.sha256(int16_data.tobytes()).hexdigest()[:16]


def extract_windows(audio: np.ndarray, positions: list[float]) -> list[np.ndarray]:
    total = len(audio)
    if total <= WINDOW_SAMPLES:
        padded = np.zeros(WINDOW_SAMPLES, dtype=np.float32)
        padded[: min(total, WINDOW_SAMPLES)] = audio[: min(total, WINDOW_SAMPLES)]
        return [padded]
    windows: list[np.ndarray] = []
    for pos in positions:
        center = int(total * pos)
        start = max(0, center - WINDOW_SAMPLES // 2)
        if start + WINDOW_SAMPLES > total:
            start = total - WINDOW_SAMPLES
        windows.append(audio[start : start + WINDOW_SAMPLES].copy())
    return windows


def l2_normalize(vec: np.ndarray) -> np.ndarray:
    norm = np.linalg.norm(vec)
    return vec / norm if norm > 0 else vec


def build_phone_path(local_file: Path, songs_dir: Path, phone_base: str) -> str:
    rel = os.path.relpath(local_file, songs_dir)
    return f"{phone_base}/{rel.replace(chr(92), '/')}"


def load_model(checkpoint: Path, device: str):
    import laion_clap
    import torch

    if not checkpoint.is_file():
        print(f"Downloading CLAP checkpoint (~2.4 GB) to {checkpoint}...")
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(CKPT_URL, checkpoint)

    print(f"Loading CLAP HTSAT-base on {device}...")
    model = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-base")
    model.load_ckpt(ckpt=str(checkpoint))
    model.eval()
    model.to(device)
    return model


def get_window_embedding(model, window: np.ndarray, device: str) -> np.ndarray:
    import torch

    with torch.no_grad():
        emb = model.get_audio_embedding_from_data(x=[window], use_tensor=False)
    return np.asarray(emb[0], dtype=np.float32)


def scan_audio_files(songs_dir: Path) -> list[Path]:
    files: list[Path] = []
    for root, _, names in os.walk(songs_dir):
        for name in names:
            if Path(name).suffix.lower() in AUDIO_EXTENSIONS:
                files.append(Path(root) / name)
    files.sort()
    return files


def export_ivz_from_json(json_path: Path, bin_path: Path, split_count: int) -> None:
    from json_to_ivz import load_legacy_json

    dim, entries, path_index, vectors = load_legacy_json(json_path)
    write_ivz(bin_path, dim, entries, path_index, vectors, split_count=split_count)
    print(f"IVZ written: {bin_path} ({bin_path.stat().st_size / (1024 * 1024):.2f} MB)")


def persist_state(
    json_path: Path,
    progress_path: Path,
    local_embeddings: dict,
    stats: RunStats,
    split_count: int,
) -> None:
    save_json(json_path, local_embeddings)
    write_progress(progress_path, build_progress_payload(stats, local_embeddings, split_count))


def main() -> None:
    configure_console_utf8()
    args = parse_args()
    cfg = load_config(args)

    import librosa
    import torch

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Python: {sys.version.split()[0]}")
    print(f"Device: {device}")
    if device == "cuda":
        print(f"  GPU: {torch.cuda.get_device_name(0)}")
        print(f"  VRAM: {torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f} GB")
    else:
        print("WARNING: CUDA not available. Running on CPU (slower, but continues).")
    print(f"Splits: {cfg['split_count']} ({cfg['window_positions']})")
    print(f"Songs:  {cfg['songs_dir']}")
    print(f"Phone:  {cfg['phone_music_base']}")
    print(f"Output: {cfg['output_dir']}")
    print(f"Monitor: powershell -File tools\\embeddings\\watch_embedding_progress.ps1")

    json_path = cfg["output_dir"] / "local_embeddings.json"
    progress_path = cfg["output_dir"] / PROGRESS_FILENAME
    failures_path = cfg["output_dir"] / FAILURES_FILENAME

    model = load_model(cfg["checkpoint"], device)

    audio_files = scan_audio_files(cfg["songs_dir"])
    print(f"\nFound {len(audio_files)} audio files\n")
    if not audio_files:
        raise SystemExit("No audio files found.")

    if json_path.is_file():
        with json_path.open(encoding="utf-8") as f:
            local_embeddings: dict = json.load(f)
        n = len([k for k in local_embeddings if not k.startswith("_") and k != "_path_index"])
        print(f"Resuming {json_path} ({n} content hashes)")
    else:
        local_embeddings = {"_path_index": {}, "_split_count": cfg["split_count"]}

    local_embeddings["_split_count"] = cfg["split_count"]
    known_phone_paths: set[str] = set()
    path_index = local_embeddings.get("_path_index", {})
    if isinstance(path_index, dict):
        known_phone_paths.update(path_index.keys())

    stats = RunStats(
        total=len(audio_files),
        started_at_ms=int(time.time() * 1000),
    )

    # Load cumulative failed count from jsonl if present
    if failures_path.is_file():
        try:
            stats.failed = sum(1 for _ in failures_path.open(encoding="utf-8"))
        except OSError:
            pass

    write_progress(progress_path, build_progress_payload(stats, local_embeddings, cfg["split_count"]))

    def request_stop(signum: int, frame: object) -> None:
        stats.interrupted = True
        print("\nInterrupt received — saving checkpoint...", flush=True)

    signal.signal(signal.SIGINT, request_stop)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, request_stop)

    changed = 0
    loop_start = time.time()

    try:
        for i, filepath in enumerate(audio_files):
            if stats.interrupted:
                break

            filename = filepath.name
            phone_path = build_phone_path(filepath, cfg["songs_dir"], cfg["phone_music_base"])

            if phone_path in known_phone_paths:
                stats.skipped += 1
                if stats.skipped % 100 == 0:
                    write_progress(
                        progress_path,
                        build_progress_payload(stats, local_embeddings, cfg["split_count"]),
                    )
                continue

            stats.current_file = filename
            write_progress(progress_path, build_progress_payload(stats, local_embeddings, cfg["split_count"]))

            console_print(f"[{i + 1}/{len(audio_files)}] {filename}...", end=" ", flush=True)

            try:
                embed_start = time.time()
                audio, _ = librosa.load(str(filepath), sr=SAMPLE_RATE, mono=True)
                if len(audio) == 0:
                    print("EMPTY")
                    stats.session_failed += 1
                    stats.failed += 1
                    stats.record_failure(phone_path, "empty audio")
                    append_failure_log(failures_path, phone_path, str(filepath), "empty audio")
                    persist_state(json_path, progress_path, local_embeddings, stats, cfg["split_count"])
                    continue

                content_hash = compute_content_hash(audio.astype(np.float32))

                if content_hash in local_embeddings and isinstance(local_embeddings[content_hash], dict):
                    local_embeddings.setdefault("_path_index", {})[phone_path] = content_hash
                    known_phone_paths.add(phone_path)
                    stats.reused += 1
                    changed += 1
                    print("REUSED hash")
                else:
                    windows = extract_windows(audio.astype(np.float32), cfg["window_positions"])
                    embs = [get_window_embedding(model, w, device) for w in windows]
                    avg = l2_normalize(np.mean(embs, axis=0).astype(np.float32))

                    artist, album = "Unknown", "Unknown"
                    try:
                        import mutagen

                        meta = mutagen.File(str(filepath), easy=True)
                        if meta:
                            if meta.get("artist"):
                                artist = meta["artist"][0]
                            if meta.get("album"):
                                album = meta["album"][0]
                    except Exception:
                        pass

                    local_embeddings[content_hash] = {
                        "embedding": avg.tolist(),
                        "content_hash": content_hash,
                        "filepath": phone_path,
                        "filename": filename,
                        "artist": artist,
                        "album": album,
                        "timestamp": int(time.time() * 1000),
                    }
                    local_embeddings.setdefault("_path_index", {})[phone_path] = content_hash
                    known_phone_paths.add(phone_path)
                    stats.processed += 1
                    stats.embed_durations.append(time.time() - embed_start)
                    changed += 1
                    avg_sec = stats.rolling_avg_sec()
                    completed = count_completed_paths(local_embeddings)
                    pending = max(0, stats.total - completed)
                    eta_h = (pending * avg_sec / 3600.0) if avg_sec else 0.0
                    print(f"OK {len(windows)}w ~{avg_sec:.1f}s/song ~{eta_h:.1f}h left" if avg_sec else f"OK {len(windows)}w")

                if changed >= cfg["checkpoint_every"]:
                    persist_state(json_path, progress_path, local_embeddings, stats, cfg["split_count"])
                    changed = 0

            except Exception as e:
                err = str(e)
                print(f"FAIL: {err}")
                stats.session_failed += 1
                stats.failed += 1
                stats.record_failure(phone_path, err)
                append_failure_log(failures_path, phone_path, str(filepath), err)
                persist_state(json_path, progress_path, local_embeddings, stats, cfg["split_count"])

    finally:
        stats.current_file = ""
        completed = count_completed_paths(local_embeddings)
        if stats.interrupted:
            stats.status = "interrupted"
        elif completed >= stats.total:
            stats.status = "completed"
        else:
            stats.status = "running"

        persist_state(json_path, progress_path, local_embeddings, stats, cfg["split_count"])

    elapsed = time.time() - loop_start
    total_hashes = len([k for k in local_embeddings if not k.startswith("_") and k != "_path_index"])
    print(f"\n{stats.status.upper()}: {stats.processed} new, {stats.reused} reused, "
          f"{stats.skipped} skipped, {stats.session_failed} failed this session")
    print(f"Total paths indexed: {count_completed_paths(local_embeddings)} / {stats.total}")
    print(f"Content hashes: {total_hashes} | Session time: {elapsed / 3600:.2f} h")
    print(f"JSON: {json_path}")
    print(f"Progress: {progress_path}")

    if stats.interrupted:
        print("\nRe-run the same command to resume from saved checkpoints.")
        sys.exit(130)

    completed = count_completed_paths(local_embeddings)
    if completed < stats.total:
        print(f"\n{stats.total - completed} paths still pending — re-run to continue.")
        return

    stats.status = "completed"
    write_progress(progress_path, build_progress_payload(stats, local_embeddings, cfg["split_count"]))

    if not cfg["json_only"]:
        bin_path = cfg["output_dir"] / "isaivazhi_embeddings.bin"
        print("\nBuilding IVZ...")
        export_ivz_from_json(json_path, bin_path, cfg["split_count"])
        print(f"\n=== Copy to phone and import: {bin_path} ===")


if __name__ == "__main__":
    main()
