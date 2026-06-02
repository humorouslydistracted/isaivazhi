# Publishing on-device ONNX model assets

App releases (APK tags like `v2026.6.3`) stay separate from **model asset**
releases. Model assets change only when you re-export the CLAP ONNX encoder.

## Current asset release

| Item | Value |
| --- | --- |
| Tag | `onnx-model-v1` |
| Files | `clap_audio_encoder.onnx`, `clap_audio_encoder.onnx.data` |
| Target path in repo | `app/src/main/assets/` |

## Create or update the release (maintainer)

1. Ensure both files exist locally under `app/src/main/assets/`.
2. Create a **draft** release on GitHub with tag `onnx-model-v1` (create tag on publish).
3. Attach both files as release assets.
4. Optional: add checksums:

   ```powershell
   cd app\src\main\assets
   Get-FileHash clap_audio_encoder.onnx, clap_audio_encoder.onnx.data -Algorithm SHA256 |
     Format-Table -AutoSize
   ```

   Save output as `SHA256SUMS.txt` on the release.

5. Publish the release.

### Using GitHub CLI

```bash
cd app/src/main/assets
gh release create onnx-model-v1 \
  --title "On-device CLAP ONNX encoder (v1)" \
  --notes "Required for building APK with phone-side embedding. Not needed if you only import isaivazhi_embeddings.bin from PC." \
  clap_audio_encoder.onnx \
  clap_audio_encoder.onnx.data
```

## Bumping to v2

When the ONNX export changes:

1. Publish tag `onnx-model-v2` with the new files.
2. Update `ONNX_MODEL_TAG` in:
   - `scripts/fetch_onnx_assets.ps1`
   - `scripts/fetch_onnx_assets.sh`
3. Update links in `app/src/main/assets/README.md` and root `README.md`.
