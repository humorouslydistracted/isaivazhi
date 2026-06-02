#!/usr/bin/env python3
"""
Colab embedding runner (bin-first).

Example:
  python colab_embedding_generator.py \
    --songs-dir /content/drive/MyDrive/isaivazhi_songs \
    --phone-base /storage/emulated/0/Music \
    --splits 7
"""

from __future__ import annotations

from cloud_embedding_runner import build_arg_parser, run_cloud_embedding


def main() -> None:
    parser = build_arg_parser(
        default_songs_dir="/content/drive/MyDrive/isaivazhi_songs",
        default_output_dir="/content",
    )
    args = parser.parse_args()
    run_cloud_embedding(
        songs_dir=args.songs_dir,
        output_dir=args.output_dir,
        phone_music_base=args.phone_base,
        split_count=args.splits,
        checkpoint_path=args.checkpoint_path,
        checkpoint_every=args.checkpoint_every,
    )


if __name__ == "__main__":
    main()
