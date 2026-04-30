#!/usr/bin/env bash
# scripts/sync-ffmpeg.sh — copies firedown-ffmpeg build output into external/ffmpeg/
# Run from firedown repo root.

set -euo pipefail

FFMPEG_BUILD_DIR="${1:-../../../Projects/firedown-ffmpeg}"

if [[ ! -d "$FFMPEG_BUILD_DIR/output" ]]; then
    echo "ERROR: $FFMPEG_BUILD_DIR/output not found" >&2
    echo "Build firedown-ffmpeg first: cd $FFMPEG_BUILD_DIR && ./ffmpeg-android-maker.sh" >&2
    exit 1
fi

DEST="../external/ffmpeg"
mkdir -p "$DEST/lib" "$DEST/include"

# Copy libraries per-ABI
for abi in arm64-v8a x86_64; do
    src="$FFMPEG_BUILD_DIR/output/lib/$abi"
    if [[ -d "$src" ]]; then
        echo "Copying lib/$abi"
        mkdir -p "$DEST/lib/$abi"
        cp "$src"/*.so "$DEST/lib/$abi/"
    else
        echo "WARNING: $src not found, skipping" >&2
    fi
done

# Copy headers (use arm64-v8a as canonical)
echo "Copying headers (from arm64-v8a)"
rm -rf "$DEST/include"
cp -r "$FFMPEG_BUILD_DIR/output/include/arm64-v8a" "$DEST/include"

# Capture version
if [[ -d "$FFMPEG_BUILD_DIR/.git" ]]; then
    cd "$FFMPEG_BUILD_DIR"
    DESCRIBE="$(git describe --always --dirty 2>/dev/null || echo unknown)"
    cd - > /dev/null
    echo "firedown-ffmpeg @ $DESCRIBE" > "$DEST/version.txt"
    echo "FFmpeg integrated: $DESCRIBE"
else
    echo "firedown-ffmpeg @ unknown" > "$DEST/version.txt"
fi

echo "Done. Don't forget to commit external/ffmpeg/."
