# On-device CLAP model assets

These files power **embedding on the phone** (`EmbeddingService`). They are **not**
used when you only import precomputed `isaivazhi_embeddings.bin` from a PC.

| File | Role |
| --- | --- |
| `clap_audio_encoder.onnx` | ONNX graph (small) |
| `clap_audio_encoder.onnx.data` | Model weights (~272 MB) |

Git does not store the weights (GitHub’s 100 MB file limit). Download them from a
**dedicated model release**, separate from normal app APK releases.

## After cloning the repo

From the project root (`native/`):

**Windows (PowerShell):**

```powershell
.\scripts\fetch_onnx_assets.ps1
```

**macOS / Linux:**

```bash
chmod +x scripts/fetch_onnx_assets.sh
./scripts/fetch_onnx_assets.sh
```

**Manual download:** [GitHub Releases — tag `onnx-model-v1`](https://github.com/humorouslydistracted/isaivazhi/releases/tag/onnx-model-v1)

Place both files in this folder (`app/src/main/assets/`), then build:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

## Related (different files)

- **PC / Kaggle / Colab embedding** uses the PyTorch checkpoint from Hugging Face  
  (`music_audioset_epoch_15_esc_90.14.pt`) — see `tools/embeddings/README.md`.  
  That `.pt` file is **not** the same as these ONNX files.

## Maintainers

When you replace the ONNX export, publish a new assets release (`onnx-model-v2`, …)
and update `ONNX_MODEL_TAG` in `scripts/fetch_onnx_assets.ps1` and
`scripts/fetch_onnx_assets.sh`. See `docs/ONNX_MODEL_RELEASE.md`.
