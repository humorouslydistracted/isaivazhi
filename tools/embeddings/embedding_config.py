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
    """Legacy fixed centre positions (before stratified_v2). Kept for reference."""
    n = normalize_split_count(split_count)
    if n == 3:
        return list(POSITIONS_3)
    return [(k + 1) / (n + 1) for k in range(n)]


#: Window placement strategy; mirrors EmbeddingWindowConfig.STRATEGY on the phone.
STRATEGY = "stratified_v2"
EDGE_TRIM_FRACTION = 0.08
EDGE_TRIM_MAX_SECONDS = 30.0
SILENT_FRAME_SECONDS = 0.1
SILENT_FRAME_RMS = 0.004
MAX_SILENT_FRACTION = 0.5

_MASK64 = (1 << 64) - 1


def seed_from_content_hash(content_hash: str | None) -> int:
    if not content_hash:
        return 0
    try:
        return int(content_hash[:16], 16) & _MASK64
    except ValueError:
        return 0


def _splitmix64(x: int) -> int:
    z = (x + 0x9E3779B97F4A7C15) & _MASK64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & _MASK64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & _MASK64
    return z ^ (z >> 31)


def _unit_for(seed: int, index: int) -> float:
    return (_splitmix64((seed + index) & _MASK64) >> 11) * (2.0 ** -53)


def _usable_range(duration_sec: float, window_sec: float) -> tuple[float, float]:
    trim = min(duration_sec * EDGE_TRIM_FRACTION, EDGE_TRIM_MAX_SECONDS)
    lo, hi = trim, duration_sec - trim
    if hi - lo < window_sec:
        lo, hi = 0.0, duration_sec
    return lo, hi


def _clamp_start(start: float, duration_sec: float, window_sec: float) -> float:
    return max(0.0, min(start, max(0.0, duration_sec - window_sec)))


def plan_window_starts(duration_sec: float, window_sec: float, split_count: int, seed: int) -> list[float]:
    """Window start times in seconds (stratified_v2). Same as the phone."""
    if not duration_sec > window_sec:
        return [0.0]
    n = normalize_split_count(split_count)
    lo, hi = _usable_range(duration_sec, window_sec)
    section = (hi - lo) / n
    slack = section - window_sec
    out = []
    for i in range(n):
        sec_start = lo + i * section
        if slack > 0:
            start = sec_start + _unit_for(seed, i) * slack
        else:
            start = sec_start + section / 2.0 - window_sec / 2.0
        out.append(_clamp_start(start, duration_sec, window_sec))
    return out


def alternative_starts(duration_sec: float, window_sec: float, split_count: int, seed: int, index: int) -> list[float]:
    """Fallback spots inside window ``index``'s own section when it is silent."""
    if not duration_sec > window_sec:
        return []
    n = normalize_split_count(split_count)
    if index < 0 or index >= n:
        return []
    lo, hi = _usable_range(duration_sec, window_sec)
    section = (hi - lo) / n
    slack = section - window_sec
    if slack < 1.0:
        return []
    planned = plan_window_starts(duration_sec, window_sec, n, seed)[index]
    sec_start = lo + index * section
    out = []
    for c in (sec_start + slack / 2.0, sec_start + slack, sec_start):
        s = _clamp_start(c, duration_sec, window_sec)
        if abs(s - planned) >= 1.0:
            out.append(s)
    return out


def silent_fraction(samples, sample_rate: int) -> float:
    """Share of 100 ms frames quieter than SILENT_FRAME_RMS."""
    import numpy as np

    frame = max(1, int(round(sample_rate * SILENT_FRAME_SECONDS)))
    frames = len(samples) // frame
    if frames == 0:
        return 1.0
    x = np.asarray(samples[: frames * frame], dtype=np.float64).reshape(frames, frame)
    mean_sq = (x * x).mean(axis=1)
    return float((mean_sq < SILENT_FRAME_RMS * SILENT_FRAME_RMS).sum()) / frames


def extract_windows(audio, split_count: int, content_hash: str | None, sample_rate: int = 48000,
                    window_sec: int = 10) -> list:
    """Cut the CLAP windows out of fully decoded mono audio (stratified_v2)."""
    import numpy as np

    window_samples = sample_rate * window_sec
    total = len(audio)
    if total <= window_samples:
        padded = np.zeros(window_samples, dtype=np.float32)
        padded[:total] = audio[:total]
        return [padded]
    duration = total / sample_rate
    seed = seed_from_content_hash(content_hash)

    def cut(start_sec: float):
        s = int(round(start_sec * sample_rate))
        s = max(0, min(s, total - window_samples))
        return audio[s : s + window_samples].copy()

    windows = []
    for i, start in enumerate(plan_window_starts(duration, window_sec, split_count, seed)):
        best = cut(start)
        best_silent = silent_fraction(best, sample_rate)
        if best_silent > MAX_SILENT_FRACTION:
            for alt in alternative_starts(duration, window_sec, split_count, seed, i):
                cand = cut(alt)
                s = silent_fraction(cand, sample_rate)
                if s < best_silent:
                    best, best_silent = cand, s
                if best_silent <= MAX_SILENT_FRACTION:
                    break
        windows.append(best)
    return windows


def time_multiplier(split_count: int) -> float:
    """Rough ONNX work multiplier vs 3-split baseline."""
    n = normalize_split_count(split_count)
    return n / 3.0
