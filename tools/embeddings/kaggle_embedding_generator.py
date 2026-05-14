#!/usr/bin/env python3
"""
Kaggle runner for generating IsaiVazhi CLAP embeddings.

Typical Kaggle notebook setup:
    !pip install -q numpy==1.26.4 laion-clap librosa soundfile mutagen
    !python kaggle_embedding_generator.py \
        --songs-dir /kaggle/input/my-music \
        --phone-music-base /storage/emulated/0/songs_downloaded

The output is local_embeddings.json, which can be imported from the app's AI
management page.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
import urllib.request
from pathlib import Path

import laion_clap
import librosa
import numpy as np
import torch


SAMPLE_RATE = 48000
WINDOW_SECONDS = 10
WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS
WINDOW_POSITIONS = [0.20, 0.50, 0.80]
CONTENT_HASH_SECONDS = 30

CKPT_URL = "https://huggingface.co/lukewys/laion_clap/resolve/main/music_audioset_epoch_15_esc_90.14.pt"
CKPT_NAME = "music_audioset_epoch_15_esc_90.14.pt"

AUDIO_EXTENSIONS = {".mp3", ".flac", ".wav", ".aac", ".m4a", ".ogg", ".opus", ".wma"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate IsaiVazhi-compatible CLAP embeddings on Kaggle."
    )
    parser.add_argument(
        "--songs-dir",
        default=os.environ.get("ISAIVAZHI_SONGS_DIR", "/kaggle/input"),
        help="Folder containing the audio files mounted in Kaggle.",
    )
    parser.add_argument(
        "--phone-music-base",
        default=os.environ.get("ISAIVAZHI_PHONE_MUSIC_BASE", "/storage/emulated/0/songs_downloaded"),
        help="Folder where the same files live on the Android device.",
    )
    parser.add_argument(
        "--output-dir",
        default=os.environ.get("ISAIVAZHI_OUTPUT_DIR", "/kaggle/working"),
        help="Writable Kaggle output folder.",
    )
    parser.add_argument(
        "--output-file",
        default="local_embeddings.json",
        help="Output JSON filename.",
    )
    parser.add_argument(
        "--checkpoint-path",
        default="",
        help="Optional path to an existing HTSAT-base checkpoint.",
    )
    return parser.parse_args()


def compute_content_hash(audio_float32: np.ndarray) -> str:
    max_samples = SAMPLE_RATE * CONTENT_HASH_SECONDS
    chunk = audio_float32[:max_samples]
    int16_data = np.clip(chunk * 32767, -32768, 32767).astype(np.int16)
    return hashlib.sha256(int16_data.tobytes()).hexdigest()[:16]


def extract_windows(audio: np.ndarray) -> list[np.ndarray]:
    total = len(audio)
    if total <= WINDOW_SAMPLES:
        padded = np.zeros(WINDOW_SAMPLES, dtype=np.float32)
        padded[: min(total, WINDOW_SAMPLES)] = audio[: min(total, WINDOW_SAMPLES)]
        return [padded]

    windows = []
    for position in WINDOW_POSITIONS:
        center = int(total * position)
        start = max(0, center - WINDOW_SAMPLES // 2)
        if start + WINDOW_SAMPLES > total:
            start = total - WINDOW_SAMPLES
        windows.append(audio[start : start + WINDOW_SAMPLES].copy())
    return windows


def l2_normalize(vector: np.ndarray) -> np.ndarray:
    norm = np.linalg.norm(vector)
    if norm > 0:
        return vector / norm
    return vector


def load_checkpoint(path: Path) -> Path:
    if path.exists():
        return path

    print("Downloading CLAP HTSAT-base checkpoint. This is large and happens once per session.")
    path.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(CKPT_URL, path)
    return path


def load_model(checkpoint_path: Path):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Using device: {device}")
    if device == "cuda":
        print(f"GPU: {torch.cuda.get_device_name(0)}")

    checkpoint_path = load_checkpoint(checkpoint_path)
    model = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-base")
    model.load_ckpt(ckpt=str(checkpoint_path))
    model.eval()
    model = model.to(device)
    return model


def get_window_embedding(model, window: np.ndarray) -> np.ndarray:
    with torch.no_grad():
        embedding = model.get_audio_embedding_from_data(x=[window], use_tensor=False)
    return embedding[0]


def scan_audio_files(songs_dir: Path) -> list[Path]:
    files = []
    for root, _, names in os.walk(songs_dir):
        for name in names:
            if Path(name).suffix.lower() in AUDIO_EXTENSIONS:
                files.append(Path(root) / name)
    files.sort(key=lambda item: str(item).lower())
    return files


def build_phone_path(filepath: Path, songs_dir: Path, phone_music_base: str) -> str:
    relative = filepath.relative_to(songs_dir)
    return (Path(phone_music_base) / relative).as_posix()


def read_existing_embeddings(output_path: Path) -> dict:
    if not output_path.exists():
        return {"_path_index": {}}

    with output_path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if "_path_index" not in data or not isinstance(data["_path_index"], dict):
        data["_path_index"] = {}
    return data


def known_phone_paths(local_embeddings: dict) -> set[str]:
    known = set(local_embeddings.get("_path_index", {}).keys())
    for key, entry in local_embeddings.items():
        if key == "_path_index" or not isinstance(entry, dict):
            continue
        filepath = entry.get("filepath")
        if filepath:
            known.add(filepath)
    return known


def read_tags(filepath: Path) -> tuple[str, str]:
    try:
        import mutagen

        meta = mutagen.File(filepath, easy=True)
        if not meta:
            return "Unknown", "Unknown"
        artist = meta.get("artist", ["Unknown"])[0]
        album = meta.get("album", ["Unknown"])[0]
        return artist or "Unknown", album or "Unknown"
    except Exception:
        return "Unknown", "Unknown"


def save_embeddings(output_path: Path, local_embeddings: dict) -> None:
    with output_path.open("w", encoding="utf-8") as handle:
        json.dump(local_embeddings, handle)


def main() -> int:
    args = parse_args()
    songs_dir = Path(args.songs_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    checkpoint_path = Path(args.checkpoint_path).resolve() if args.checkpoint_path else output_dir / CKPT_NAME
    output_path = output_dir / args.output_file

    if not songs_dir.exists():
        raise SystemExit(f"Songs directory does not exist: {songs_dir}")

    audio_files = scan_audio_files(songs_dir)
    print(f"Found {len(audio_files)} audio files under {songs_dir}")
    if not audio_files:
        return 0

    model = load_model(checkpoint_path)
    local_embeddings = read_existing_embeddings(output_path)
    known_paths = known_phone_paths(local_embeddings)

    start_time = time.time()
    processed = 0
    skipped = 0
    reused_hash = 0
    failed = 0
    changed_since_save = 0

    for index, filepath in enumerate(audio_files, start=1):
        phone_path = build_phone_path(filepath, songs_dir, args.phone_music_base)
        if phone_path in known_paths:
            skipped += 1
            continue

        print(f"[{index}/{len(audio_files)}] {filepath.name}...", end=" ", flush=True)
        try:
            audio, _ = librosa.load(str(filepath), sr=SAMPLE_RATE, mono=True)
            if len(audio) == 0:
                print("EMPTY")
                failed += 1
                continue

            content_hash = compute_content_hash(audio)
            if content_hash in local_embeddings:
                local_embeddings["_path_index"][phone_path] = content_hash
                known_paths.add(phone_path)
                reused_hash += 1
                changed_since_save += 1
                print("REUSED existing embedding via content hash")
                continue

            window_embeddings = [get_window_embedding(model, window) for window in extract_windows(audio)]
            avg_embedding = np.mean(window_embeddings, axis=0).astype(np.float32)
            avg_embedding = l2_normalize(avg_embedding)
            artist, album = read_tags(filepath)

            local_embeddings[content_hash] = {
                "embedding": avg_embedding.tolist(),
                "content_hash": content_hash,
                "filepath": phone_path,
                "filename": filepath.name,
                "artist": artist,
                "album": album,
                "timestamp": int(time.time() * 1000),
            }
            local_embeddings["_path_index"][phone_path] = content_hash
            known_paths.add(phone_path)

            processed += 1
            changed_since_save += 1
            elapsed = time.time() - start_time
            per_song = elapsed / max(processed, 1)
            print(f"OK ({avg_embedding.shape[0]}D, {per_song:.1f}s/song)")

            if changed_since_save >= 10:
                save_embeddings(output_path, local_embeddings)
                print(f"Checkpoint saved to {output_path}")
                changed_since_save = 0
        except Exception as exc:
            print(f"FAILED: {exc}")
            failed += 1

    save_embeddings(output_path, local_embeddings)
    total = len([key for key in local_embeddings if key != "_path_index"])
    elapsed = time.time() - start_time

    print()
    print("=" * 60)
    print(f"Done: {processed} new, {reused_hash} reused, {skipped} skipped, {failed} failed")
    print(f"Total embeddings in file: {total}")
    print(f"Elapsed: {elapsed / 60:.1f} minutes")
    print(f"Saved to: {output_path}")
    print("Import local_embeddings.json from the IsaiVazhi AI page.")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
