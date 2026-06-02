#!/usr/bin/env bash
# Downloads on-device CLAP ONNX assets from a dedicated GitHub Release.
# Run from repo root (native/):  ./scripts/fetch_onnx_assets.sh

set -euo pipefail

ONNX_MODEL_TAG="onnx-model-v1"
REPO="humorouslydistracted/isaivazhi"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$ROOT/app/src/main/assets"
BASE_URL="https://github.com/${REPO}/releases/download/${ONNX_MODEL_TAG}"

FILES=(
  "clap_audio_encoder.onnx"
  "clap_audio_encoder.onnx.data"
)

mkdir -p "$ASSETS_DIR"

for name in "${FILES[@]}"; do
  dest="$ASSETS_DIR/$name"
  if [[ -f "$dest" && -s "$dest" ]]; then
    echo "Already present: $name ($(wc -c < "$dest") bytes) — skipping"
    continue
  fi
  url="${BASE_URL}/${name}"
  echo "Downloading $name ..."
  curl -fsSL -o "$dest" "$url"
  echo "  -> $dest ($(wc -c < "$dest") bytes)"
done

echo ""
echo "Done. Build with: ./gradlew :app:assembleDebug"
echo "Release: https://github.com/${REPO}/releases/tag/${ONNX_MODEL_TAG}"
