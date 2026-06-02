"""
IsaiVazhi IVZ1 single-file embedding store.

Layout (little-endian):
  0..3   magic b'IVZ1'
  4..7   version (uint32, must be 1)
  8..11  dim (uint32)
  12..15 row_count (uint32)
  16..19 meta_json_byte_length (uint32)
  20..   meta_json UTF-8 (JSON object: entries + pathIndex, no vectors)
  ...    row_count * dim * 4 bytes float32 LE (row-major, same order as entries)
"""

from __future__ import annotations

import json
import struct
from pathlib import Path
from typing import Any, Dict, List, Tuple

IVZ_MAGIC = b"IVZ1"
IVZ_VERSION = 1
HEADER_SIZE = 20
HEADER_STRUCT = struct.Struct("<4s4I")  # magic + version, dim, row_count, meta_len


def write_ivz(
    path: Path,
    dim: int,
    entries: List[Dict[str, Any]],
    path_index: Dict[str, str],
    vectors: List[List[float]],
    split_count: int = 3,
) -> None:
    if dim <= 0:
        raise ValueError("dim must be positive")
    if len(entries) != len(vectors):
        raise ValueError("entries and vectors length mismatch")
    for i, vec in enumerate(vectors):
        if len(vec) != dim:
            raise ValueError(f"row {i}: expected dim {dim}, got {len(vec)}")

    meta = {"entries": entries, "pathIndex": path_index, "splitCount": split_count}
    meta_bytes = json.dumps(meta, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    row_count = len(entries)

    vec_blob = bytearray(row_count * dim * 4)
    offset = 0
    for vec in vectors:
        struct.pack_into(f"<{dim}f", vec_blob, offset, *vec)
        offset += dim * 4

    header = HEADER_STRUCT.pack(IVZ_MAGIC, IVZ_VERSION, dim, row_count, len(meta_bytes))
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("wb") as f:
        f.write(header)
        f.write(meta_bytes)
        f.write(vec_blob)
    tmp.replace(path)


def read_ivz(path: Path) -> Tuple[int, List[Dict[str, Any]], Dict[str, str], bytes, int]:
    data = path.read_bytes()
    if len(data) < HEADER_SIZE:
        raise ValueError("file too short for IVZ header")
    magic, version, dim, row_count, meta_len = HEADER_STRUCT.unpack_from(data, 0)
    if magic != IVZ_MAGIC:
        raise ValueError(f"bad magic {magic!r}, expected IVZ1")
    if version != IVZ_VERSION:
        raise ValueError(f"unsupported IVZ version {version}")
    meta_start = HEADER_SIZE
    meta_end = meta_start + meta_len
    vec_start = meta_end
    expected_vec_len = row_count * dim * 4
    if len(data) < vec_start + expected_vec_len:
        raise ValueError("truncated vector blob")

    meta = json.loads(data[meta_start:meta_end].decode("utf-8"))
    entries = meta.get("entries", [])
    path_index = meta.get("pathIndex", {})
    split_count = int(meta.get("splitCount", 3))
    if not isinstance(entries, list) or not isinstance(path_index, dict):
        raise ValueError("invalid meta JSON shape")
    vec_blob = data[vec_start : vec_start + expected_vec_len]
    return dim, entries, path_index, vec_blob, split_count


def vec_at_row(vec_blob: bytes, row_index: int, dim: int) -> List[float]:
    offset = row_index * dim * 4
    return list(struct.unpack_from(f"<{dim}f", vec_blob, offset))
