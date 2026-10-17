#!/bin/sh
# Drives the REAL Captured-sheet rules: CaptureSections (a tile for every
# capture but an image, then an "Images" header and the squares; the fold
# row for the earlier pages, closed or open, and where it lands when the list
# limit cuts first; the domain on earlier tiles only when they span several
# sites; which squares an "Images" header's select check takes, and which
# header a square repaints; the CC count on the video only, never on an audio
# capture in a video container; which subtitle rides with its video (a video
# with qualities is its door); the fold's preview picks; the filename test
# behind "Video on <site>"), CaptureMark (download
# row -> mark, the FILE_NOT_FOUND exemption, finishing / live, which of two
# marks wins), the URL matching that ties a download to its capture (own URL
# or any variant's), the batch card's counts, the origin list's cap,
# CaptureItemDiffCallback (a mark-only change is a payload rebind, a
# presentation change is not), and CaptureFacts (the quality a tap
# downloads, the size shown beside it — exact, the capture's own length, or
# bitrate x duration — a download row's resolution as a quality, and when a
# ladder mixes codec families).
#
#   sh scripts/capture-harness/run.sh
#
# Real classes are COPIED from app/src at run time, never re-implemented.
# BrowserDownloadEntity / FFmpegEntity / FFmpegTagEntity / FileUriHelper are
# stand-ins carrying only what the rules read; R is generated from the app's
# real res/ (gen_r.py). Needs a JDK and python3.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
J="$ROOT/app/src/main/java/com/solarized/firedown"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/com/solarized/firedown/data/capture" "$OUT/src/com/solarized/firedown/data/entity" \
         "$OUT/src/com/solarized/firedown/ui/diffs" "$OUT/src/com/solarized/firedown/utils"
cp "$J/data/capture/CaptureItem.java" "$J/data/capture/CaptureMark.java" \
   "$J/data/capture/CaptureSections.java" "$J/data/capture/CaptureFacts.java" \
   "$OUT/src/com/solarized/firedown/data/capture/"
cp "$J/data/entity/CaptureDownloadState.java" "$OUT/src/com/solarized/firedown/data/entity/"
cp "$J/data/Download.java" "$OUT/src/com/solarized/firedown/data/"
cp "$J/ui/diffs/CaptureItemDiffCallback.java" "$OUT/src/com/solarized/firedown/ui/diffs/"
cp "$J/utils/MessageHelper.java" "$OUT/src/com/solarized/firedown/utils/"
python3 "$HERE/gen_r.py" "$ROOT/app/src/main/res" "$OUT/src/com/solarized/firedown/R.java" > /dev/null
javac -nowarn -encoding UTF-8 -d "$OUT/classes" \
      $(find "$HERE/stub" "$OUT/src" "$HERE/src" -name '*.java')
java -cp "$OUT/classes" com.solarized.firedown.harness.CaptureHarness
