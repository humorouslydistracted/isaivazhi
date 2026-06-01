# Embedding Tools

Scripts produce **isaivazhi_embeddings.bin** (IVZ1) for fast import into the app.
Legacy **local_embeddings.json** is still supported but slow (~25 MB, 1–2 min import).

## Convert existing JSON (no re-embedding)

```bash
cd tools/embeddings
python json_to_ivz.py ../../local_embeddings.json ../../isaivazhi_embeddings.bin --verify
```

Copy `isaivazhi_embeddings.bin` to the phone and import from Settings or the AI page.

## IVZ1 format

Single file: magic `IVZ1`, header, small metadata JSON (`entries`, `pathIndex`, optional `splitCount`), then float32 LE vectors (512-dim CLAP, row-major).

## Split count (3 / 5 / 7)

Each song uses **N × 10 s** CLAP windows (not one full-file pass). Positions are shared in `embedding_config.py` and `EmbeddingWindowConfig.java`:

| Splits | Window centers (fraction of duration) |
| --- | --- |
| 3 | 20%, 50%, 80% (legacy Colab/mobile) |
| 5 | 1/6, 2/6, 3/6, 4/6, 5/6 |
| 7 | 1/8 … 7/8 |

- **Mobile:** AI & Library page → pick 3, 5, or 7 before embedding.
- **Python:** `json_to_ivz.py … --splits 7` tags the IVZ meta (vectors unchanged on convert).
- **Generators:** pass `--splits 3|5|7` when re-embedding (Colab/Kaggle/local scripts should import `embedding_config.window_positions`).

Do not mix 3-split and 7-split vectors in one library — re-embed everything after changing.

## Generators

| Script | Use case |
| --- | --- |
| `local_embedding_generator.py` | Desktop / CUDA |
| `colab_embedding_generator.py` | Google Colab |
| `kaggle_embedding_generator.py` | Kaggle GPU |
| `merge_local_embeddings.py` | Merge two JSON backups (IVZ merge: use `ivz_format` + script update) |

Generators will write `.bin` by default once updated; until then use `json_to_ivz.py` after JSON generation.
