#!/usr/bin/env python3
"""
Convert local_embeddings.json (app / Colab format) to isaivazhi_embeddings.bin (IVZ1).

No re-embedding required — copies existing float vectors into binary form.

Usage:
    python json_to_ivz.py input.json output.bin
    python json_to_ivz.py input.json output.bin --verify
    python json_to_ivz.py input.json output.bin --dry-run
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Tuple

from embedding_config import normalize_split_count
from ivz_format import read_ivz, vec_at_row, write_ivz


def load_legacy_json(path: Path) -> Tuple[int, List[Dict[str, Any]], Dict[str, str], List[List[float]]]:
    with path.open("r", encoding="utf-8-sig") as f:
        root = json.load(f)
    if not isinstance(root, dict):
        raise SystemExit(f"{path}: top-level JSON must be an object")

    path_index_raw = root.get("_path_index", {})
    if path_index_raw is None:
        path_index_raw = {}
    if not isinstance(path_index_raw, dict):
        raise SystemExit(f"{path}: '_path_index' must be an object")

    path_index: Dict[str, str] = {}
    for fp, h in path_index_raw.items():
        if isinstance(fp, str) and isinstance(h, str) and fp and h:
            path_index[fp] = h

    entries: List[Dict[str, Any]] = []
    vectors: List[List[float]] = []
    dim: int | None = None

    keys = sorted(k for k in root.keys() if k != "_path_index")
    for key in keys:
        row = root[key]
        if not isinstance(row, dict):
            continue
        emb = row.get("embedding")
        if not isinstance(emb, list) or not emb:
            continue
        vec: List[float] = []
        for v in emb:
            if not isinstance(v, (int, float)) or not math.isfinite(v):
                raise SystemExit(f"{path}: entry '{key}' has non-finite embedding value")
            vec.append(float(v))
        if dim is None:
            dim = len(vec)
        elif len(vec) != dim:
            print(f"warning: skip '{key}' — dim {len(vec)} != {dim}", file=sys.stderr)
            continue

        content_hash = row.get("contentHash", row.get("content_hash", key))
        if not isinstance(content_hash, str) or not content_hash:
            content_hash = key

        entries.append(
            {
                "contentHash": content_hash,
                "filepath": row.get("filepath", "") if isinstance(row.get("filepath"), str) else "",
                "filename": row.get("filename", "") if isinstance(row.get("filename"), str) else "",
                "artist": row.get("artist", "") if isinstance(row.get("artist"), str) else "",
                "album": row.get("album", "") if isinstance(row.get("album"), str) else "",
                "timestamp": int(row.get("timestamp", 0)) if isinstance(row.get("timestamp"), (int, float)) else 0,
            }
        )
        vectors.append(vec)

    if dim is None or not entries:
        raise SystemExit(f"{path}: no valid embedding entries found")

    return dim, entries, path_index, vectors


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert local_embeddings.json to isaivazhi_embeddings.bin")
    parser.add_argument("input_json", type=Path, help="Source local_embeddings.json")
    parser.add_argument("output_bin", type=Path, help="Destination isaivazhi_embeddings.bin")
    parser.add_argument("--verify", action="store_true", help="Re-read output and sanity-check")
    parser.add_argument("--dry-run", action="store_true", help="Parse only, do not write")
    parser.add_argument(
        "--splits",
        type=int,
        default=3,
        choices=[3, 5, 7],
        help="Split count recorded in IVZ meta (vectors unchanged; for import warnings)",
    )
    args = parser.parse_args()
    split_count = normalize_split_count(args.splits)

    if not args.input_json.is_file():
        raise SystemExit(f"input not found: {args.input_json}")

    t0 = time.time()
    dim, entries, path_index, vectors = load_legacy_json(args.input_json)
    elapsed_load = time.time() - t0

    in_mb = args.input_json.stat().st_size / (1024 * 1024)
    est_out_mb = (20 + 4096 + len(entries) * dim * 4) / (1024 * 1024)

    print(f"Loaded {len(entries)} embeddings, dim={dim}, path_index={len(path_index)}")
    print(f"Input:  {args.input_json} ({in_mb:.2f} MB)")
    print(f"Output: {args.output_bin} (~{est_out_mb:.2f} MB estimated)")
    print(f"Parse time: {elapsed_load:.2f}s")

    if args.dry_run:
        return

    t1 = time.time()
    write_ivz(args.output_bin, dim, entries, path_index, vectors, split_count=split_count)
    elapsed_write = time.time() - t1
    out_mb = args.output_bin.stat().st_size / (1024 * 1024)
    print(f"Wrote {args.output_bin} ({out_mb:.2f} MB) in {elapsed_write:.2f}s")

    if args.verify:
        vdim, ventries, vpaths, vblob, v_splits = read_ivz(args.output_bin)
        if v_splits != split_count:
            raise SystemExit(f"verify failed: splitCount {v_splits} != {split_count}")
        if vdim != dim or len(ventries) != len(entries):
            raise SystemExit("verify failed: dim or row count mismatch")
        if len(vpaths) != len(path_index):
            print(f"warning: path_index count {len(vpaths)} vs source {len(path_index)}", file=sys.stderr)
        # spot-check first and last vector
        for idx in (0, len(entries) - 1):
            got = vec_at_row(vblob, idx, dim)
            exp = vectors[idx]
            if len(got) != len(exp):
                raise SystemExit(f"verify failed: row {idx} length")
            for a, b in zip(got, exp):
                if abs(a - b) > 1e-5:
                    raise SystemExit(f"verify failed: row {idx} vector mismatch")
        print("Verify OK (row count, first/last vectors)")


if __name__ == "__main__":
    main()
