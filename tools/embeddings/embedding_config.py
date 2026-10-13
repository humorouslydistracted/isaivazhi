"""
Shared CLAP window configuration for IsaiVazhi (mobile + Python generators).

Only split counts 3, 5, and 7 are supported.
"""

from __future__ import annotations

import math

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
SHORT_TRACK_SECONDS = 30.0
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


def placement_range(duration_sec: float, window_sec: float,
                    audible_start: float, audible_end: float) -> tuple[float, float]:
    """Edge-trimmed span for long songs; the audible span for short tracks."""
    if duration_sec >= SHORT_TRACK_SECONDS:
        return _usable_range(duration_sec, window_sec)
    lo = max(0.0, min(audible_start, duration_sec))
    hi = max(lo, min(audible_end, duration_sec))
    if hi - lo <= 0.0:
        return 0.0, max(0.0, duration_sec)
    return lo, hi


def effective_window_count(range_sec: float, window_sec: float, split_count: int) -> int:
    """Windows that fit with at most 50% overlap, capped at the split count."""
    n = normalize_split_count(split_count)
    if not range_sec > window_sec:
        return 1
    fit = 1 + math.floor((range_sec - window_sec) / (window_sec / 2.0))
    return max(1, min(n, fit))


def _clamp_to_range(start: float, lo: float, hi: float, window_sec: float) -> float:
    return max(lo, min(start, max(lo, hi - window_sec)))


def plan_window_starts(duration_sec: float, window_sec: float, split_count: int, seed: int,
                       audible_start: float = 0.0, audible_end: float | None = None) -> list[float]:
    """Window start times in seconds (stratified_v2). Same as the phone."""
    if audible_end is None:
        audible_end = duration_sec
    lo, hi = placement_range(duration_sec, window_sec, audible_start, audible_end)
    length = hi - lo
    if not length > window_sec:
        return [lo]
    n = effective_window_count(length, window_sec, split_count)
    section = length / n
    slack = section - window_sec
    out = []
    if not slack > 0:
        # Sections narrower than a window: spread evenly from the first
        # to the last possible start so neighbours overlap at most 50%.
        span = length - window_sec
        for i in range(n):
            start = lo + span / 2.0 if n == 1 else lo + i * span / (n - 1)
            out.append(_clamp_to_range(start, lo, hi, window_sec))
        return out
    for i in range(n):
        start = lo + i * section + _unit_for(seed, i) * slack
        out.append(_clamp_to_range(start, lo, hi, window_sec))
    return out


def alternative_starts(duration_sec: float, window_sec: float, split_count: int, seed: int, index: int,
                       audible_start: float = 0.0, audible_end: float | None = None) -> list[float]:
    """Fallback spots inside window ``index``'s own section when it is silent."""
    if audible_end is None:
        audible_end = duration_sec
    lo, hi = placement_range(duration_sec, window_sec, audible_start, audible_end)
    length = hi - lo
    if not length > window_sec:
        return []
    n = effective_window_count(length, window_sec, split_count)
    if index < 0 or index >= n:
        return []
    section = length / n
    slack = section - window_sec
    if slack < 1.0:
        return []
    planned = plan_window_starts(duration_sec, window_sec, split_count, seed, audible_start, audible_end)[index]
    sec_start = lo + index * section
    out = []
    for c in (sec_start + slack / 2.0, sec_start + slack, sec_start):
        s = _clamp_to_range(c, lo, hi, window_sec)
        if abs(s - planned) >= 1.0:
            out.append(s)
    return out


def _frame_mean_squares(samples, sample_rate: int):
    import numpy as np

    frame = max(1, int(round(sample_rate * SILENT_FRAME_SECONDS)))
    frames = len(samples) // frame
    x = np.asarray(samples[: frames * frame], dtype=np.float64).reshape(frames, frame)
    return (x * x).mean(axis=1), frame


def silent_fraction(samples, sample_rate: int) -> float:
    """Share of 100 ms frames quieter than SILENT_FRAME_RMS."""
    ms, _ = _frame_mean_squares(samples, sample_rate)
    if len(ms) == 0:
        return 1.0
    return float((ms < SILENT_FRAME_RMS * SILENT_FRAME_RMS).sum()) / len(ms)


def audible_range(samples, sample_rate: int) -> tuple[float, float]:
    """Seconds between the first and last non-silent 100 ms frame."""
    import numpy as np

    total = len(samples) / sample_rate
    ms, frame = _frame_mean_squares(samples, sample_rate)
    loud = np.nonzero(ms >= SILENT_FRAME_RMS * SILENT_FRAME_RMS)[0]
    if len(loud) == 0:
        return 0.0, total
    first, last = int(loud[0]), int(loud[-1])
    end = total if last == len(ms) - 1 else (last + 1) * frame / sample_rate
    return first * frame / sample_rate, end


def cut_window(audio, sample_rate: int, start_sec: float, range_start: float, range_end: float,
               window_samples: int):
    """One window; a placement range shorter than a window is looped to fill it."""
    import numpy as np

    total = len(audio)
    out = np.zeros(window_samples, dtype=np.float32)
    if total <= 0:
        return out
    lo = max(0, min(total - 1, int(round(range_start * sample_rate))))
    hi = max(lo + 1, min(total, int(round(range_end * sample_rate))))
    if hi - lo < window_samples:
        seg = audio[lo:hi]
        reps = -(-window_samples // len(seg))
        return np.tile(seg, reps)[:window_samples].astype(np.float32)
    s = int(round(start_sec * sample_rate))
    s = max(0, min(s, total - window_samples))
    return audio[s : s + window_samples].astype(np.float32).copy()


def extract_windows(audio, split_count: int, content_hash: str | None, sample_rate: int = 48000,
                    window_sec: int = 10) -> list:
    """Cut the CLAP windows out of fully decoded mono audio (stratified_v2)."""
    window_samples = sample_rate * window_sec
    duration = len(audio) / sample_rate
    seed = seed_from_content_hash(content_hash)
    if duration < SHORT_TRACK_SECONDS:
        a_start, a_end = audible_range(audio, sample_rate)
    else:
        a_start, a_end = 0.0, duration
    lo, hi = placement_range(duration, window_sec, a_start, a_end)

    windows = []
    for i, start in enumerate(plan_window_starts(duration, window_sec, split_count, seed, a_start, a_end)):
        best = cut_window(audio, sample_rate, start, lo, hi, window_samples)
        best_silent = silent_fraction(best, sample_rate)
        if best_silent > MAX_SILENT_FRACTION:
            for alt in alternative_starts(duration, window_sec, split_count, seed, i, a_start, a_end):
                cand = cut_window(audio, sample_rate, alt, lo, hi, window_samples)
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
