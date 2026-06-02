"""
Shared CLAP window configuration for IsaiVazhi (mobile + Python generators).

Only split counts 3, 5, and 7 are supported.
"""

from __future__ import annotations

ALLOWED_SPLIT_COUNTS = (3, 5, 7)

# Legacy exact positions (must match historical Colab/mobile vectors).
POSITIONS_3 = [0.20, 0.50, 0.80]


def normalize_split_count(split_count: int) -> int:
    if split_count not in ALLOWED_SPLIT_COUNTS:
        raise ValueError(f"split_count must be one of {ALLOWED_SPLIT_COUNTS}, got {split_count}")
    return split_count


def window_positions(split_count: int) -> list[float]:
    """Center positions (fraction of duration) for each 10 s window."""
    n = normalize_split_count(split_count)
    if n == 3:
        return list(POSITIONS_3)
    return [(k + 1) / (n + 1) for k in range(n)]


def time_multiplier(split_count: int) -> float:
    """Rough ONNX work multiplier vs 3-split baseline."""
    n = normalize_split_count(split_count)
    return n / 3.0
