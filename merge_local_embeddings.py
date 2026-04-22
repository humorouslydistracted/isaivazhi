#!/usr/bin/env python3
"""
Merge two local_embeddings JSON files into one validated output file.

The script is intentionally strict:
- both inputs must be JSON objects in the app's embedding format
- every embedding entry key must match its content hash field
- `_path_index` values must reference real entries
- embedding dimensions must be consistent across both files
- the same filepath cannot point to different content hashes
- the same content hash cannot have different embedding vectors

Usage:
    python merge_local_embeddings.py file_a.json file_b.json merged.json
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Dict, List, Tuple


class ValidationError(Exception):
    """Raised when an embeddings file does not match the expected format."""


def _load_json(path: Path) -> dict:
    try:
        with path.open("r", encoding="utf-8-sig") as handle:
            data = json.load(handle)
    except FileNotFoundError as exc:
        raise ValidationError(f"{path}: file not found") from exc
    except json.JSONDecodeError as exc:
        raise ValidationError(f"{path}: invalid JSON ({exc})") from exc

    if not isinstance(data, dict):
        raise ValidationError(f"{path}: top-level JSON must be an object")
    return data


def _require_string(value, field_name: str, path: Path, key: str) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{path}: entry '{key}' field '{field_name}' must be a string")
    return value


def _normalize_entry(path: Path, key: str, raw_entry: dict, expected_dim: int | None) -> Tuple[dict, int]:
    if not isinstance(raw_entry, dict):
        raise ValidationError(f"{path}: entry '{key}' must be an object")

    if "embedding" not in raw_entry:
        raise ValidationError(f"{path}: entry '{key}' is missing 'embedding'")

    embedding = raw_entry["embedding"]
    if not isinstance(embedding, list) or not embedding:
        raise ValidationError(f"{path}: entry '{key}' has invalid 'embedding' list")

    normalized_embedding: List[float] = []
    for idx, value in enumerate(embedding):
        if not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValidationError(
                f"{path}: entry '{key}' embedding[{idx}] must be a finite number"
            )
        normalized_embedding.append(float(value))

    dim = len(normalized_embedding)
    if expected_dim is not None and dim != expected_dim:
        raise ValidationError(
            f"{path}: entry '{key}' has embedding dim {dim}, expected {expected_dim}"
        )

    content_hash = raw_entry.get("contentHash", raw_entry.get("content_hash", key))
    if not isinstance(content_hash, str) or not content_hash:
        raise ValidationError(f"{path}: entry '{key}' has invalid content hash")
    if content_hash != key:
        raise ValidationError(
            f"{path}: entry '{key}' content hash field '{content_hash}' does not match key"
        )

    content_hash_alt = raw_entry.get("contentHash")
    content_hash_snake = raw_entry.get("content_hash")
    if content_hash_alt is not None and not isinstance(content_hash_alt, str):
        raise ValidationError(f"{path}: entry '{key}' field 'contentHash' must be a string")
    if content_hash_snake is not None and not isinstance(content_hash_snake, str):
        raise ValidationError(f"{path}: entry '{key}' field 'content_hash' must be a string")
    if (
        isinstance(content_hash_alt, str)
        and isinstance(content_hash_snake, str)
        and content_hash_alt != content_hash_snake
    ):
        raise ValidationError(
            f"{path}: entry '{key}' has mismatched 'contentHash' and 'content_hash'"
        )

    filepath = raw_entry.get("filepath", "")
    filename = raw_entry.get("filename", "")
    artist = raw_entry.get("artist", "")
    album = raw_entry.get("album", "")

    if filepath != "":
        filepath = _require_string(filepath, "filepath", path, key)
    if filename != "":
        filename = _require_string(filename, "filename", path, key)
    if artist != "":
        artist = _require_string(artist, "artist", path, key)
    if album != "":
        album = _require_string(album, "album", path, key)

    timestamp = raw_entry.get("timestamp", 0)
    if not isinstance(timestamp, (int, float)) or not math.isfinite(timestamp):
        raise ValidationError(f"{path}: entry '{key}' field 'timestamp' must be numeric")

    normalized_entry = {
        "embedding": normalized_embedding,
        "content_hash": content_hash,
        "filepath": filepath,
        "filename": filename,
        "artist": artist,
        "album": album,
        "timestamp": int(timestamp),
    }
    return normalized_entry, dim


def _validate_file(path: Path) -> Tuple[Dict[str, dict], Dict[str, str], int]:
    raw = _load_json(path)

    raw_path_index = raw.get("_path_index", {})
    if raw_path_index is None:
        raw_path_index = {}
    if not isinstance(raw_path_index, dict):
        raise ValidationError(f"{path}: '_path_index' must be an object")

    entries: Dict[str, dict] = {}
    dim: int | None = None

    for key, raw_entry in raw.items():
        if key == "_path_index":
            continue
        if not isinstance(key, str) or not key:
            raise ValidationError(f"{path}: every entry key must be a non-empty string")
        normalized_entry, entry_dim = _normalize_entry(path, key, raw_entry, dim)
        if dim is None:
            dim = entry_dim
        entries[key] = normalized_entry

    if not entries:
        raise ValidationError(f"{path}: no embedding entries found")

    path_index: Dict[str, str] = {}
    for file_path, content_hash in raw_path_index.items():
        if not isinstance(file_path, str) or not file_path:
            raise ValidationError(f"{path}: '_path_index' keys must be non-empty strings")
        if not isinstance(content_hash, str) or not content_hash:
            raise ValidationError(
                f"{path}: '_path_index' value for '{file_path}' must be a non-empty string"
            )
        if content_hash not in entries:
            raise ValidationError(
                f"{path}: '_path_index' references missing content hash '{content_hash}'"
            )
        path_index[file_path] = content_hash

    for key, entry in entries.items():
        filepath = entry["filepath"]
        if not filepath:
            continue
        mapped = path_index.get(filepath)
        if mapped is not None and mapped != key:
            raise ValidationError(
                f"{path}: filepath '{filepath}' points to '{mapped}' in _path_index but "
                f"entry '{key}' claims the same filepath"
            )
        path_index[filepath] = key

    return entries, path_index, dim


def _embeddings_equal(a: List[float], b: List[float], tol: float = 1e-8) -> bool:
    if len(a) != len(b):
        return False
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def _prefer_text(existing: str, incoming: str) -> str:
    if existing and existing.lower() != "unknown":
        return existing
    return incoming or existing


def _merge_entries(
    base_entries: Dict[str, dict],
    extra_entries: Dict[str, dict],
) -> Dict[str, dict]:
    merged = {key: dict(value) for key, value in base_entries.items()}

    for key, incoming in extra_entries.items():
        if key not in merged:
            merged[key] = dict(incoming)
            continue

        existing = merged[key]
        if not _embeddings_equal(existing["embedding"], incoming["embedding"]):
            raise ValidationError(
                f"content hash '{key}' exists in both files with different embedding vectors"
            )

        if incoming["filepath"] and not existing["filepath"]:
            existing["filepath"] = incoming["filepath"]
        if incoming["timestamp"] > existing["timestamp"]:
            existing["timestamp"] = incoming["timestamp"]

        existing["filename"] = _prefer_text(existing["filename"], incoming["filename"])
        existing["artist"] = _prefer_text(existing["artist"], incoming["artist"])
        existing["album"] = _prefer_text(existing["album"], incoming["album"])

    return merged


def _merge_path_indexes(
    base_index: Dict[str, str],
    extra_index: Dict[str, str],
) -> Dict[str, str]:
    merged = dict(base_index)
    for file_path, content_hash in extra_index.items():
        existing = merged.get(file_path)
        if existing is not None and existing != content_hash:
            raise ValidationError(
                f"filepath '{file_path}' points to '{existing}' in one file and "
                f"'{content_hash}' in the other"
            )
        merged[file_path] = content_hash
    return merged


def _build_output(entries: Dict[str, dict], path_index: Dict[str, str]) -> dict:
    ordered = {"_path_index": {}}
    for file_path in sorted(path_index):
        ordered["_path_index"][file_path] = path_index[file_path]

    for key in sorted(entries):
        entry = entries[key]
        ordered[key] = {
            "embedding": entry["embedding"],
            "content_hash": key,
            "filepath": entry["filepath"],
            "filename": entry["filename"],
            "artist": entry["artist"],
            "album": entry["album"],
            "timestamp": entry["timestamp"],
        }
    return ordered


def merge_files(file_a: Path, file_b: Path, output: Path) -> None:
    entries_a, path_index_a, dim_a = _validate_file(file_a)
    entries_b, path_index_b, dim_b = _validate_file(file_b)

    if dim_a != dim_b:
        raise ValidationError(
            f"embedding dimension mismatch: {file_a} has {dim_a}, {file_b} has {dim_b}"
        )

    merged_entries = _merge_entries(entries_a, entries_b)
    merged_path_index = _merge_path_indexes(path_index_a, path_index_b)

    for key, entry in merged_entries.items():
        filepath = entry["filepath"]
        if filepath:
            existing = merged_path_index.get(filepath)
            if existing is not None and existing != key:
                raise ValidationError(
                    f"merged output would map filepath '{filepath}' to both '{existing}' and '{key}'"
                )
            merged_path_index[filepath] = key

    output_data = _build_output(merged_entries, merged_path_index)
    with output.open("w", encoding="utf-8") as handle:
        json.dump(output_data, handle, ensure_ascii=True, separators=(",", ":"))

    print(
        f"Merged {len(entries_a)} + {len(entries_b)} entries into {len(merged_entries)} entries"
    )
    print(f"Wrote merged file to: {output}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Merge two validated local_embeddings JSON files into one output file."
    )
    parser.add_argument("file_a", type=Path, help="first local_embeddings JSON file")
    parser.add_argument("file_b", type=Path, help="second local_embeddings JSON file")
    parser.add_argument("output", type=Path, help="output path for merged JSON file")
    args = parser.parse_args()

    try:
        merge_files(args.file_a, args.file_b, args.output)
    except ValidationError as exc:
        print(f"ERROR: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
