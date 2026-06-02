#!/usr/bin/env python3
"""
Shared Colab/Kaggle embedding runner.

Bin-first output:
  - local_embeddings.json (resume/checkpoint)
  - isaivazhi_embeddings.bin (final import file)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
from pathlib import Path
from typing import Any

import numpy as np

from embedding_config import normalize_split_count, window_positions
from ivz_format import write_ivz
from json_to_ivz import load_legacy_json

SAMPLE_RATE = 48000
WINDOW_SAMPLES = SAMPLE_RATE * 10
CONTENT_HASH_SECONDS = 30
AUDIO_EXTENSIONS = {".mp3", ".flac", ".wav", ".aac", ".m4a", ".ogg", ".opus", ".wma"}
CKPT_URL = "https://huggingface.co/lukewys/laion_clap/resolve/main/music_audioset_epoch_15_esc_90.14.pt"
CKPT_NAME = "music_audioset_epoch_15_esc_90.14.pt"


def _save_json_atomic(path: Path, data: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(data, f)
    tmp.replace(path)


def _scan_audio_files(songs_dir: Path) -> list[Path]:
    files: list[Path] = []
    for root, _, names in os.walk(songs_dir):
        for name in names:
            if Path(name).suffix.lower() in AUDIO_EXTENSIONS:
                files.append(Path(root) / name)
    files.sort()
    return files


def _build_phone_path(local_file: Path, songs_dir: Path, phone_base: str) -> str:
    rel = os.path.relpath(local_file, songs_dir)
    return f"{phone_base}/{rel.replace(chr(92), '/')}"


def _compute_content_hash(audio: np.ndarray) -> str:
    chunk = audio[: SAMPLE_RATE * CONTENT_HASH_SECONDS]
    int16_data = np.clip(chunk * 32767, -32768, 32767).astype(np.int16)
    return hashlib.sha256(int16_data.tobytes()).hexdigest()[:16]


def _extract_windows(audio: np.ndarray, positions: list[float]) -> list[np.ndarray]:
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


def _l2_normalize(vec: np.ndarray) -> np.ndarray:
    norm = np.linalg.norm(vec)
    return vec / norm if norm > 0 else vec


def _load_model(checkpoint: Path, device: str):
    import laion_clap

    if not checkpoint.is_file():
        print(f"Downloading CLAP checkpoint to {checkpoint} ...")
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(CKPT_URL, checkpoint)

    print(f"Loading CLAP HTSAT-base on {device} ...")
    model = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-base")
    model.load_ckpt(ckpt=str(checkpoint))
    model.eval()
    model.to(device)
    return model


def _export_bin_from_json(json_path: Path, bin_path: Path, split_count: int) -> None:
    dim, entries, path_index, vectors = load_legacy_json(json_path)
    write_ivz(bin_path, dim, entries, path_index, vectors, split_count=split_count)


def run_cloud_embedding(
    *,
    songs_dir: Path,
    output_dir: Path,
    phone_music_base: str,
    split_count: int,
    checkpoint_path: Path,
    checkpoint_every: int = 10,
) -> dict[str, Any]:
    import librosa
    import torch

    split_count = normalize_split_count(split_count)
    output_dir.mkdir(parents=True, exist_ok=True)
    phone_base = (phone_music_base or "/storage/emulated/0/Music").strip().rstrip("/")

    print(f"Python: {sys.version.split()[0]}")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Device: {device}")
    if device == "cuda":
        print(f"  GPU: {torch.cuda.get_device_name(0)}")
    else:
        print("WARNING: CUDA not available. Running on CPU (slower, but continues).")
    print(f"Splits: {split_count} ({window_positions(split_count)})")
    print(f"Songs:  {songs_dir}")
    print(f"Phone:  {phone_base}")
    print(f"Output: {output_dir}")

    model = _load_model(checkpoint_path, device)
    files = _scan_audio_files(songs_dir)
    print(f"Found {len(files)} audio files")
    if not files:
        raise SystemExit("No audio files found.")

    json_path = output_dir / "local_embeddings.json"
    bin_path = output_dir / "isaivazhi_embeddings.bin"

    if json_path.is_file():
        with json_path.open("r", encoding="utf-8") as f:
            data: dict = json.load(f)
        print(f"Resuming from {json_path}")
    else:
        data = {"_path_index": {}, "_split_count": split_count}
    data["_split_count"] = split_count

    known_paths = set(data.get("_path_index", {}).keys())
    changed = 0
    processed = reused = skipped = failed = 0
    started = time.time()

    for i, path in enumerate(files, start=1):
        phone_path = _build_phone_path(path, songs_dir, phone_base)
        if phone_path in known_paths:
            skipped += 1
            continue

        print(f"[{i}/{len(files)}] {path.name} ...", end=" ", flush=True)
        try:
            audio, _ = librosa.load(str(path), sr=SAMPLE_RATE, mono=True)
            if len(audio) == 0:
                print("EMPTY")
                failed += 1
                continue

            content_hash = _compute_content_hash(audio.astype(np.float32))
            if content_hash in data and isinstance(data[content_hash], dict):
                data.setdefault("_path_index", {})[phone_path] = content_hash
                known_paths.add(phone_path)
                reused += 1
                changed += 1
                print("REUSED")
            else:
                windows = _extract_windows(audio.astype(np.float32), window_positions(split_count))
                embs = []
                for w in windows:
                    with torch.no_grad():
                        emb = model.get_audio_embedding_from_data(x=[w], use_tensor=False)
                    embs.append(np.asarray(emb[0], dtype=np.float32))
                avg = _l2_normalize(np.mean(embs, axis=0).astype(np.float32))
                data[content_hash] = {
                    "embedding": avg.tolist(),
                    "content_hash": content_hash,
                    "filepath": phone_path,
                    "relativePath": os.path.relpath(path, songs_dir).replace("\\", "/"),
                    "filename": path.name,
                    "artist": "",
                    "album": "",
                    "timestamp": int(time.time() * 1000),
                }
                data.setdefault("_path_index", {})[phone_path] = content_hash
                known_paths.add(phone_path)
                processed += 1
                changed += 1
                elapsed = time.time() - started
                per = elapsed / max(processed, 1)
                print(f"OK (~{per:.1f}s/new)")

            if changed >= max(1, checkpoint_every):
                _save_json_atomic(json_path, data)
                changed = 0
        except Exception as e:
            failed += 1
            print(f"FAIL: {e}")

    _save_json_atomic(json_path, data)
    _export_bin_from_json(json_path, bin_path, split_count)
    elapsed = time.time() - started
    print(f"Done: {processed} new, {reused} reused, {skipped} skipped, {failed} failed")
    print(f"Bin: {bin_path} ({bin_path.stat().st_size / (1024*1024):.2f} MB)")

    return {
        "json_path": str(json_path),
        "bin_path": str(bin_path),
        "processed_new": processed,
        "reused": reused,
        "skipped": skipped,
        "failed": failed,
        "elapsed_sec": elapsed,
    }


def build_arg_parser(default_songs_dir: str, default_output_dir: str) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Generate IsaiVazhi embeddings (bin-first).")
    p.add_argument("--songs-dir", type=Path, default=Path(default_songs_dir))
    p.add_argument("--output-dir", type=Path, default=Path(default_output_dir))
    p.add_argument("--phone-base", type=str, default="/storage/emulated/0/Music")
    p.add_argument("--splits", type=int, default=7, choices=[3, 5, 7])
    p.add_argument("--checkpoint-path", type=Path, default=Path(default_output_dir) / CKPT_NAME)
    p.add_argument("--checkpoint-every", type=int, default=10)
    return p
