# Embedding Tools (Bin-First)

**PC / cloud workflow (this folder):** produces `isaivazhi_embeddings.bin` for import.
Uses the Hugging Face PyTorch checkpoint (`music_audioset_epoch_15_esc_90.14.pt`).

**On-phone embedding** uses separate ONNX files in `app/src/main/assets/` — see
[app/src/main/assets/README.md](../../app/src/main/assets/README.md).

All generators now target the same outcome:

- `local_embeddings.json` (resume/checkpoint)
- `isaivazhi_embeddings.bin` (**final import file for app**)

Python baseline across environments: **3.12**.

## Minimal Inputs

Most users only need:

1. `songs_dir` — where your songs are in that environment
2. `splits` — `7` recommended for your quality-first one-time run

`phone_base` defaults to `/storage/emulated/0/Music`.

## Quick Start

### Local (Windows / GTX 1650)

```powershell
powershell -ExecutionPolicy Bypass -File tools\embeddings\setup_laptop_embeddings.ps1
# Copy laptop_config.example.json to laptop_config.json and set songs_dir
powershell -ExecutionPolicy Bypass -File tools\embeddings\run_laptop_embeddings.ps1
```

### Kaggle (private dataset, GPU)

```bash
pip install -q numpy==1.26.4 laion-clap librosa soundfile mutagen
pip install -q torch==2.5.1+cu121 torchvision==0.20.1+cu121 torchaudio==2.5.1+cu121 --index-url https://download.pytorch.org/whl/cu121
python tools/embeddings/kaggle_embedding_generator.py --songs-dir /kaggle/input/your-private-dataset --splits 7
```

Output file: `/kaggle/working/isaivazhi_embeddings.bin`

### Colab (GPU)

```bash
pip install -q numpy==1.26.4 laion-clap librosa soundfile mutagen
pip install -q torch==2.5.1+cu121 torchvision==0.20.1+cu121 torchaudio==2.5.1+cu121 --index-url https://download.pytorch.org/whl/cu121
python tools/embeddings/colab_embedding_generator.py --songs-dir /content/drive/MyDrive/isaivazhi_songs --splits 7
```

Output file: `/content/isaivazhi_embeddings.bin`

## CPU Fallback

If CUDA is unavailable, scripts continue on CPU and print a clear warning at startup.

## Split Count (3 / 5 / 7)

Window centers are shared in `embedding_config.py`:

| Splits | Positions |
| --- | --- |
| 3 | 20%, 50%, 80% |
| 5 | 1/6, 2/6, 3/6, 4/6, 5/6 |
| 7 | 1/8 … 7/8 |

Use the same split count in the app import/runtime.

## Folder Rename Concern (Phone Path)

Embeddings are based on audio content hash, not folder name.  
`filepath` is stored for fast matching. If you later rename top-level folders, re-import + relink usually recovers mapping without full re-embed.

## Convert Existing JSON (No Re-Embedding)

```bash
cd tools/embeddings
python json_to_ivz.py ../../local_embeddings.json ../../isaivazhi_embeddings.bin --splits 7 --verify
```
