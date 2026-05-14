# Embedding Tools

IsaiVazhi can run without embeddings, but its similarity-based recommendation
features are best after generating CLAP audio embeddings for the local music
library.

These scripts produce `local_embeddings.json`, the import format understood by
the app's AI management page. They use LAION-CLAP HTSAT-base and mirror the
mobile app's embedding logic:

- 48 kHz mono audio
- three 10-second windows at 20%, 50%, and 80% of each track
- averaged and L2-normalized 512-dimensional vectors
- content hashes from the first 30 seconds of decoded audio
- `_path_index` mappings so duplicate filenames and duplicate audio can be
  handled safely

## Scripts

| Script | Use case |
| --- | --- |
| `kaggle_embedding_generator.py` | Kaggle notebook or Kaggle GPU session. |
| `colab_embedding_generator.py` | Google Colab workflow with Drive mounted. |
| `local_embedding_generator.py` | Laptop or desktop run, preferably with CUDA. |
| `merge_local_embeddings.py` | Strictly validate and merge two generated JSON files. |

## Kaggle

1. Upload your music folder as a Kaggle dataset.
2. Start a Kaggle notebook with GPU enabled.
3. Install dependencies:

```bash
pip install -q numpy==1.26.4 laion-clap librosa soundfile mutagen
```

4. Run the generator:

```bash
python kaggle_embedding_generator.py \
  --songs-dir /kaggle/input/my-music \
  --output-dir /kaggle/working \
  --phone-music-base /storage/emulated/0/songs_downloaded
```

Use `--songs-dir` as the folder whose relative paths should match the music
folder on the phone. The script scans recursively, so album subfolders are fine.

## Colab

Open `colab_embedding_generator.py` in Colab, set:

```python
SONGS_DIR = "/content/drive/MyDrive/songs_downloaded"
OUTPUT_DIR = "/content/drive/MyDrive/music_app"
PHONE_MUSIC_BASE = "/storage/emulated/0/songs_downloaded"
```

Then run the cells. A GPU runtime is strongly recommended because the HTSAT-base
checkpoint is large.

## Local

Install dependencies in a virtual environment:

```bash
pip install numpy==1.26.4 laion-clap librosa soundfile mutagen torch torchvision torchaudio
```

For NVIDIA GPU support, install the CUDA PyTorch build that matches your driver:

```bash
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
```

Edit `SONGS_DIR` and `PHONE_MUSIC_BASE` in `local_embedding_generator.py`, then
run:

```bash
python local_embedding_generator.py
```

## Import

Copy `local_embeddings.json` to the phone and import it from the IsaiVazhi AI
page. Advanced/manual app-private path:

```text
/storage/emulated/0/Android/data/com.isaivazhi.app.kt/files/
```

Generated checkpoints and embedding JSON files are intentionally ignored by Git.
