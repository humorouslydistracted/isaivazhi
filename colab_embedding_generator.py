# =============================================================================
# CLAP Embedding Generator for IsaiVazhi
# Run this in Google Colab (GPU runtime recommended for speed)
#
# Generates local_embeddings.json compatible with the mobile app.
# Copy the output file to your phone's app-private folder:
#   /storage/emulated/0/Android/data/com.isaivazhi.app/files/
# =============================================================================

# ===== CONFIGURATION - EDIT THESE =====

SONGS_DIR = '/content/drive/MyDrive/songs_downloaded'
OUTPUT_DIR = '/content/drive/MyDrive/music_app'

# Path where these same songs exist on your phone (from MediaStore scan)
PHONE_MUSIC_BASE = '/storage/emulated/0/songs_downloaded'

# =============================================================================
# STEP 1: Mount Drive & Install Dependencies
# =============================================================================

# Mount Google Drive (run this cell manually in Colab)
from google.colab import drive
drive.mount('/content/drive')

# Install dependencies
import subprocess
subprocess.check_call(['pip', 'install', '-q', 'numpy==1.26.4'])
subprocess.check_call(['pip', 'install', '-q', 'laion-clap', 'librosa', 'soundfile', 'mutagen'])

# =============================================================================
# STEP 2: Imports
# =============================================================================

import os
import json
import hashlib
import time
import numpy as np
import librosa
import torch
import laion_clap

# =============================================================================
# STEP 3: Constants (matching mobile EmbeddingService.java exactly)
# =============================================================================

SAMPLE_RATE = 48000
WINDOW_SECONDS = 10
WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS  # 480,000
WINDOW_POSITIONS = [0.20, 0.50, 0.80]
EMBEDDING_DIM = 512
CONTENT_HASH_SECONDS = 30

AUDIO_EXTENSIONS = {'.mp3', '.flac', '.wav', '.aac', '.m4a', '.ogg', '.opus', '.wma'}

# =============================================================================
# STEP 4: Load CLAP Model
# =============================================================================

# Download HTSAT-base checkpoint (same architecture as mobile ONNX model)
CKPT_URL = 'https://huggingface.co/lukewys/laion_clap/resolve/main/music_audioset_epoch_15_esc_90.14.pt'
CKPT_PATH = '/content/music_audioset_epoch_15_esc_90.14.pt'

if not os.path.exists(CKPT_PATH):
    print("Downloading CLAP HTSAT-base checkpoint (~2.4GB, one-time download)...")
    import urllib.request
    urllib.request.urlretrieve(CKPT_URL, CKPT_PATH)
    print("Download complete.")

print("Loading CLAP model (HTSAT-base, matching mobile ONNX)...")
model = laion_clap.CLAP_Module(enable_fusion=False, amodel='HTSAT-base')
model.load_ckpt(ckpt=CKPT_PATH)
model.eval()
device = 'cuda' if torch.cuda.is_available() else 'cpu'
print(f"Model loaded on {device}")

# =============================================================================
# STEP 5: Helper Functions (matching mobile app logic)
# =============================================================================


def compute_content_hash(audio_float32, sr=48000, seconds=30):
    """
    SHA-256 of first 30s of audio, converted to int16 bytes.
    Matches EmbeddingService.java computeContentHash() exactly:
      short s = (short) Math.max(-32768, Math.min(32767, audio[i] * 32767));
      bytes[i*2] = (byte)(s & 0xFF);        // little-endian
      bytes[i*2+1] = (byte)((s >> 8) & 0xFF);
    Returns first 16 hex chars of SHA-256.
    """
    max_samples = sr * seconds
    chunk = audio_float32[:max_samples]
    int16_data = np.clip(chunk * 32767, -32768, 32767).astype(np.int16)
    audio_bytes = int16_data.tobytes()
    full_hash = hashlib.sha256(audio_bytes).hexdigest()
    return full_hash[:16]


def extract_windows(audio, sr=48000):
    """
    Extract 3 windows at positions 0.20, 0.50, 0.80 of audio.
    Matches EmbeddingService.java extractWindows() exactly.
    Each window is 10s = 480,000 samples.
    """
    total = len(audio)
    if total <= WINDOW_SAMPLES:
        padded = np.zeros(WINDOW_SAMPLES, dtype=np.float32)
        padded[:min(total, WINDOW_SAMPLES)] = audio[:min(total, WINDOW_SAMPLES)]
        return [padded]

    windows = []
    for pos in WINDOW_POSITIONS:
        center = int(total * pos)
        start = max(0, center - WINDOW_SAMPLES // 2)
        if start + WINDOW_SAMPLES > total:
            start = total - WINDOW_SAMPLES
        window = audio[start:start + WINDOW_SAMPLES].copy()
        windows.append(window)
    return windows


def get_window_embedding(model, window):
    """Get CLAP embedding for a single 10s audio window."""
    with torch.no_grad():
        emb = model.get_audio_embedding_from_data(
            x=[window],
            use_tensor=False
        )
    return emb[0]


def l2_normalize(vec):
    """L2 normalize a vector (matching Java l2Normalize)."""
    norm = np.linalg.norm(vec)
    if norm > 0:
        return vec / norm
    return vec


def build_phone_path(filepath):
    """
    Convert a Colab-side file path into the matching phone-side path.
    This must stay aligned with where the same songs are copied on the device.
    """
    rel_path = os.path.relpath(filepath, SONGS_DIR)
    return os.path.join(PHONE_MUSIC_BASE, rel_path).replace('\\', '/')


def save_local_embeddings(output_path, local_embeddings):
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(local_embeddings, f)


# =============================================================================
# STEP 6: Scan Audio Files
# =============================================================================

print(f"\nScanning {SONGS_DIR} for audio files...")
audio_files = []
for root, dirs, files in os.walk(SONGS_DIR):
    for f in files:
        ext = os.path.splitext(f)[1].lower()
        if ext in AUDIO_EXTENSIONS:
            audio_files.append(os.path.join(root, f))

audio_files.sort()
print(f"Found {len(audio_files)} audio files")

# =============================================================================
# STEP 7: Generate Embeddings
# =============================================================================

os.makedirs(OUTPUT_DIR, exist_ok=True)

output_path = os.path.join(OUTPUT_DIR, 'local_embeddings.json')
if os.path.exists(output_path):
    with open(output_path, 'r', encoding='utf-8') as f:
        local_embeddings = json.load(f)
    existing_count = len([k for k in local_embeddings if k != '_path_index'])
    print(f"Loaded existing embeddings: {existing_count} entries (will skip known paths and reuse existing hashes)")
else:
    local_embeddings = {'_path_index': {}}

# Resume by exact phone path, not filename. This keeps duplicate filenames in
# different folders safe. Also collect entry.filepath values in case an older
# file did not have a fully populated _path_index.
known_phone_paths = set()
path_index = local_embeddings.get('_path_index', {})
for phone_path in path_index:
    known_phone_paths.add(phone_path)
for key, entry in local_embeddings.items():
    if key == '_path_index' or not isinstance(entry, dict):
        continue
    entry_path = entry.get('filepath')
    if entry_path:
        known_phone_paths.add(entry_path)

start_time = time.time()
processed = 0
skipped = 0
reused_hash = 0
failed = 0
changed_since_save = 0

for i, filepath in enumerate(audio_files):
    filename = os.path.basename(filepath)
    phone_path = build_phone_path(filepath)

    # Exact-path resume support for interrupted / batched runs.
    if phone_path in known_phone_paths:
        skipped += 1
        continue

    print(f"[{i+1}/{len(audio_files)}] {filename}...", end=' ', flush=True)

    try:
        audio, sr = librosa.load(filepath, sr=SAMPLE_RATE, mono=True)

        if len(audio) == 0:
            print("EMPTY")
            failed += 1
            continue

        content_hash = compute_content_hash(audio)

        # If identical audio content was already embedded in an earlier batch,
        # do not recompute CLAP. Just add the new phone path -> content_hash
        # mapping so the app can link this file through _path_index.
        if content_hash in local_embeddings:
            if '_path_index' not in local_embeddings:
                local_embeddings['_path_index'] = {}
            local_embeddings['_path_index'][phone_path] = content_hash
            known_phone_paths.add(phone_path)
            reused_hash += 1
            changed_since_save += 1
            print("REUSED existing embedding via content hash")

            if changed_since_save >= 10:
                save_local_embeddings(output_path, local_embeddings)
                print(f"  [Checkpoint saved: {processed} new + {reused_hash} reused + {skipped} skipped]")
                changed_since_save = 0
            continue

        windows = extract_windows(audio)

        window_embeddings = []
        for window in windows:
            emb = get_window_embedding(model, window)
            window_embeddings.append(emb)

        avg_embedding = np.mean(window_embeddings, axis=0).astype(np.float32)
        avg_embedding = l2_normalize(avg_embedding)

        artist = 'Unknown'
        album = 'Unknown'
        try:
            import mutagen
            meta = mutagen.File(filepath, easy=True)
            if meta:
                if 'artist' in meta:
                    artist = meta['artist'][0]
                if 'album' in meta:
                    album = meta['album'][0]
        except Exception:
            pass

        entry = {
            'embedding': avg_embedding.tolist(),
            'content_hash': content_hash,
            'filepath': phone_path,
            'filename': filename,
            'artist': artist,
            'album': album,
            'timestamp': int(time.time() * 1000),
        }
        local_embeddings[content_hash] = entry
        if '_path_index' not in local_embeddings:
            local_embeddings['_path_index'] = {}
        local_embeddings['_path_index'][phone_path] = content_hash
        known_phone_paths.add(phone_path)

        processed += 1
        changed_since_save += 1
        elapsed = time.time() - start_time
        per_song = elapsed / processed if processed > 0 else 0
        remaining = (len(audio_files) - i - 1 - skipped) * per_song
        print(f"OK ({avg_embedding.shape[0]}D, {per_song:.1f}s/song, ~{remaining/60:.0f}m left)")

        if changed_since_save >= 10:
            save_local_embeddings(output_path, local_embeddings)
            print(f"  [Checkpoint saved: {processed} new + {reused_hash} reused + {skipped} skipped]")
            changed_since_save = 0

    except Exception as e:
        print(f"FAILED: {e}")
        failed += 1

save_local_embeddings(output_path, local_embeddings)

elapsed = time.time() - start_time
total = len([k for k in local_embeddings if k != '_path_index'])
print(f"\n{'='*60}")
print(f"Done! {processed} new, {reused_hash} reused via content hash, {skipped} skipped, {failed} failed")
print(f"Total embeddings in file: {total}")
print(f"Time: {elapsed/60:.1f} minutes ({elapsed/max(processed, 1):.1f}s per song)")
print(f"\nSaved to: {output_path}")
print(f"\nNext steps:")
print(f"1. Copy local_embeddings.json to your phone:")
print(f"   /storage/emulated/0/Android/data/com.isaivazhi.app/files/")
print(f"2. If local_embeddings.bin and local_embeddings_meta.json already exist there, delete both so the app rebuilds from JSON.")
print(f"3. Open the app - embeddings will be loaded automatically")
print(f"{'='*60}")
