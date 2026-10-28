#!/bin/sh
# GIF maker harness: the REAL app/src/main/cpp/ffmpegutils/gifmaker.c
# (compiled from app/src, never copied) driven through its JNI entry points
# by driver.c with a stub JNIEnv, on a host build of the FFmpeg version the
# app pins (firedown.ffmpegRelease), configured with the app's decoders,
# demuxers, the gif encoder/muxer and the filters gifmaker uses.
#
# Checks, on synthetic H.264 clips shaped like a phone download (576x1024,
# 30 fps, ~4 Mbit/s):
#   one-pass   a short range keeps the one-pass graph: every frame, the bar
#              never steps back, and it does not sit at 100% while the
#              encode burst runs (the burst counted from 0, not the range
#              start, and pinned the bar for any range not at 0)
#   streaming  a range past the buffer budget streams: memory stays flat
#              (the one-pass graph held every frame, ~4.6 GB for 8:51 at
#              320 px), every frame comes out, and the GIF matches the
#              one-pass GIF (PSNR, ffmpeg on the host, skipped without it)
#   cancel     interrupt() returns within half a second in the palette pass
#              and the streaming pass; an interrupt BEFORE start() is kept
#              (start used to clear the flag and run the whole GIF)
#   leaks      AddressSanitizer + LeakSanitizer over a complete run of each
#              mode, a cancel in each phase, a cancel before start and a
#              missing input (a cancel after the first frame leaked the
#              packet the gif muxer holds back, freed only by the trailer)
# Then MUTANTS that must each fail a check: the trailer skipped on a cancel,
# no streaming mode, the interrupt cleared in start, the burst progress
# counted from 0.
#
#   sh scripts/gifmaker-harness/run.sh          # the suite (~3 min warm)
#   sh scripts/gifmaker-harness/run.sh long     # + the 8:51 clip, timed
#   MUTANTS=0 sh scripts/gifmaker-harness/run.sh
#   SUITE=0 ONLY_MUTANT=never-stream sh scripts/gifmaker-harness/run.sh
#
# Needs gcc, make, nasm, git, and ffmpeg with libx264 on the host to make
# the test clips. FFmpeg's host build and the clips are cached under
# $FIREDOWN_HARNESS_CACHE/gifmaker (default ~/.cache/firedown-harness).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SRC="$ROOT/app/src/main/cpp/ffmpegutils"
CACHE=${FIREDOWN_HARNESS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/firedown-harness}/gifmaker
BUILD="$HERE/build"
MODE=${1:-suite}
JOBS=$(nproc 2>/dev/null || echo 4)
mkdir -p "$CACHE" "$BUILD/out" "$BUILD/mut"

# --- FFmpeg for the host (cached) ------------------------------------------
FFV=$(sed -n 's/^firedown.ffmpegRelease=v\([0-9.]*\)-.*/\1/p' "$ROOT/gradle.properties")
[ -n "$FFV" ] || { echo "no firedown.ffmpegRelease in gradle.properties" >&2; exit 2; }
FFSRC="$CACHE/ffmpeg-$FFV/src"
FF="$CACHE/ffmpeg-$FFV/inst"
if [ ! -f "$FFSRC/.ready" ]; then
  rm -rf "$FFSRC"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "n$FFV" https://github.com/FFmpeg/FFmpeg "$FFSRC"
  touch "$FFSRC/.ready"
fi
if [ ! -f "$FF/.ready" ]; then
  echo "gifmaker: building FFmpeg $FFV for the host (once)"
  rm -rf "$CACHE/ffmpeg-$FFV/build" "$FF"
  mkdir -p "$CACHE/ffmpeg-$FFV/build"
  if ! (cd "$CACHE/ffmpeg-$FFV/build" && "$FFSRC/configure" --prefix="$FF" \
        --disable-everything --disable-programs --disable-doc --enable-static --disable-shared \
        --disable-autodetect --disable-debug --enable-pthreads --enable-protocol=file \
        --enable-decoder=h264,hevc,vp9,mpeg4,mjpeg --enable-demuxer=mov,matroska,mpegts \
        --enable-parser=h264,hevc,vp9,mpeg4video --enable-encoder=gif --enable-muxer=gif \
        --enable-filter=palettegen,paletteuse,split,fps,scale,format,null,buffer,buffersink \
        --enable-swscale > configure.log 2>&1 && make -j"$JOBS" > make.log 2>&1 \
        && make install > install.log 2>&1); then
    echo "FFmpeg host build failed; logs in $CACHE/ffmpeg-$FFV/build" >&2
    exit 1
  fi
  touch "$FF/.ready"
fi

# --- test clips (cached) ----------------------------------------------------
MEDIA="$CACHE/media"
mkdir -p "$MEDIA"
make_clip() {
  if [ ! -f "$MEDIA/$1" ]; then
    command -v ffmpeg > /dev/null || { echo "ffmpeg (with libx264) is needed to make $1" >&2; exit 2; }
    ffmpeg -hide_banner -loglevel error -y -f lavfi \
      -i "testsrc2=s=576x1024:r=30,noise=alls=25:allf=t+u" -t "$2" \
      -c:v libx264 -preset veryfast -crf 26 -g 60 -bf 2 -pix_fmt yuv420p \
      -movflags +faststart "$MEDIA/$1.tmp.mp4"
    mv "$MEDIA/$1.tmp.mp4" "$MEDIA/$1"
  fi
}
make_clip portrait_60s.mp4 60
[ "$MODE" = long ] && make_clip portrait_531s.mp4 531

# --- build ------------------------------------------------------------------
JDK=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
LIBS=$(PKG_CONFIG_PATH="$FF/lib/pkgconfig" pkg-config --static --libs libavformat libavfilter libavcodec libswscale libavutil)
CFLAGS="-I $HERE/stub -I $SRC -I $FF/include -I $JDK/include -I $JDK/include/linux"
build() { # name source extra-cflags
  gcc -g -w $3 $CFLAGS -o "$BUILD/$1" "$HERE/driver.c" "$2" $LIBS -lpthread
}
build driver "$SRC/gifmaker.c" "-O2"
build driver-asan "$SRC/gifmaker.c" "-O1 -fsanitize=address,undefined -fno-omit-frame-pointer"
# The gifmaker.c own build must stay warning-free under -Wextra.
gcc -O2 -fsyntax-only -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare \
    -Wno-missing-field-initializers -Wno-unused-variable $CFLAGS "$SRC/gifmaker.c" 2> "$BUILD/warnings.txt" || true
sed -i '/gifmaker\.h/d' "$BUILD/warnings.txt"

FAILS=0
PASSES=0
pass() { PASSES=$((PASSES + 1)); echo "PASS $1"; }
fail() { FAILS=$((FAILS + 1)); echo "FAIL $1"; }
# The condition is evaluated as an if-test: under set -e a bare failing
# test inside $( ) ends the subshell before anything is echoed.
check() { # name condition-result(0/1) detail
  if [ "$2" = 0 ]; then pass "$1"; else fail "$1 ($3)"; fi
}
val() { sed -n "s/^$2=\([^ ]*\).*/\1/p" "$1" | head -1; }
lt() { awk "BEGIN{exit !($1 < $2)}"; }
frames() { # gif → frame count, empty without ffprobe
  command -v ffprobe > /dev/null || return 0
  ffprobe -v error -count_frames -select_streams v -show_entries stream=nb_read_frames -of csv=p=0 "$1"
}

CLIP="$MEDIA/portrait_60s.mp4"
# run <binary> <tag> <startMs> <endMs> [interrupt]  → $BUILD/out/<tag>.txt
run() {
  "$BUILD/$1" "$CLIP" "$BUILD/out/$2.gif" "$3" "$4" 12 320 $5 > "$BUILD/out/$2.txt" 2> "$BUILD/out/$2.err" || true
}

suite() { # binary prefix
  B=$1; P=$2

  # one-pass: 5 s .. 12 s (7 s = 84 frames, under the budget). Starting
  # past 0 is the point: the burst's progress used to add the start again.
  run "$B" "${P}one" 5000 12000
  T="$BUILD/out/${P}one.txt"
  check "${P}one-pass: completes" "$(if [ "$(val "$T" ret)" = 0 ]; then echo 0; else echo 1; fi)" "ret=$(val "$T" ret)"
  n=$(frames "$BUILD/out/${P}one.gif")
  [ -z "$n" ] || check "${P}one-pass: 84 frames" "$(if [ "$n" -ge 83 ] && [ "$n" -le 85 ]; then echo 0; else echo 1; fi)" "frames=$n"
  check "${P}one-pass: bar never steps back" "$(if [ "$(val "$T" backward_steps)" = 0 ]; then echo 0; else echo 1; fi)" "backward=$(val "$T" backward_steps)"
  check "${P}one-pass: bar not at 100% during the burst" "$(if [ "$(val "$T" full_before_finish)" -le 1 ] 2>/dev/null; then echo 0; else echo 1; fi)" "full_before_finish=$(val "$T" full_before_finish)"

  # streaming: the whole 60 s (720 frames, ~520 MB in the one-pass graph)
  run "$B" "${P}stream" 0 60000
  T="$BUILD/out/${P}stream.txt"
  check "${P}streaming: completes" "$(if [ "$(val "$T" ret)" = 0 ]; then echo 0; else echo 1; fi)" "ret=$(val "$T" ret)"
  rss=$(val "$T" peak_rss_mb)
  check "${P}streaming: peak memory under 200 MB" "$(if [ "${rss:-9999}" -lt 200 ]; then echo 0; else echo 1; fi)" "peak_rss_mb=$rss"
  n=$(frames "$BUILD/out/${P}stream.gif")
  [ -z "$n" ] || check "${P}streaming: 720 frames" "$(if [ "$n" -ge 719 ] && [ "$n" -le 721 ]; then echo 0; else echo 1; fi)" "frames=$n"
  check "${P}streaming: bar never steps back" "$(if [ "$(val "$T" backward_steps)" = 0 ]; then echo 0; else echo 1; fi)" "backward=$(val "$T" backward_steps)"
  echo "     streaming 60 s: $(val "$T" wall_s) s, $rss MB"

  # cancels
  run "$B" "${P}cpal" 0 60000 150
  T="$BUILD/out/${P}cpal.txt"
  lat=$(val "$T" interrupt_latency_s)
  check "${P}cancel in the palette pass: back within 0.5 s" "$(if lt "${lat:-9}" 0.5; then echo 0; else echo 1; fi)" "latency=$lat ret=$(val "$T" ret)"
  run "$B" "${P}cstream" 0 60000 4000
  T="$BUILD/out/${P}cstream.txt"
  lat=$(val "$T" interrupt_latency_s)
  check "${P}cancel while streaming: back within 0.5 s" "$(if lt "${lat:-9}" 0.5; then echo 0; else echo 1; fi)" "latency=$lat ret=$(val "$T" ret)"
  check "${P}cancel while streaming: interrupted" "$(if [ "$(val "$T" ret)" = -24 ]; then echo 0; else echo 1; fi)" "ret=$(val "$T" ret)"
  run "$B" "${P}cbefore" 0 60000 before
  T="$BUILD/out/${P}cbefore.txt"
  check "${P}cancel before start: kept" "$(if [ "$(val "$T" ret)" = -24 ] && lt "$(val "$T" wall_s)" 0.5; then echo 0; else echo 1; fi)" "ret=$(val "$T" ret) wall=$(val "$T" wall_s)"
}

asan_suite() { # binary prefix
  B=$1; P=$2
  for c in "complete-one 2000 6000" "complete-stream 0 20000" "cancel-burst 0 9000 4200" \
           "cancel-stream 0 20000 2500" "cancel-palette 0 20000 60" "cancel-before 0 9000 before" \
           "missing-input 0 9000"; do
    set -- $c
    tag=$1; shift
    if [ "$tag" = missing-input ]; then
      "$BUILD/$B" "$BUILD/no-such-file.mp4" "$BUILD/out/${P}a.gif" "$@" 12 320 > /dev/null 2> "$BUILD/out/${P}asan-$tag.err" || true
    else
      ASAN_OPTIONS=detect_leaks=1 "$BUILD/$B" "$CLIP" "$BUILD/out/${P}a.gif" $1 $2 12 320 $3 > /dev/null 2> "$BUILD/out/${P}asan-$tag.err" || true
    fi
    if grep -q "ERROR: \(AddressSanitizer\|LeakSanitizer\)\|runtime error:" "$BUILD/out/${P}asan-$tag.err"; then
      fail "${P}sanitizers: $tag ($(grep -m1 "SUMMARY\|runtime error" "$BUILD/out/${P}asan-$tag.err"))"
    else
      pass "${P}sanitizers: $tag"
    fi
  done
}

if [ "${SUITE:-1}" != 0 ]; then
echo "== real gifmaker.c (FFmpeg $FFV)"
if [ -s "$BUILD/warnings.txt" ]; then
  fail "warning-free under -Wextra ($(head -1 "$BUILD/warnings.txt"))"
else
  pass "warning-free under -Wextra"
fi
suite driver ""
asan_suite driver-asan ""

# Quality: the streaming GIF against the one-pass GIF of the same range
# (the one-pass graph forced by a budget nothing exceeds).
if command -v ffmpeg > /dev/null; then
  sed 's/^#define BUFFERED_FRAMES_BUDGET_BYTES .*/#define BUFFERED_FRAMES_BUDGET_BYTES (1ll << 62)/' \
      "$SRC/gifmaker.c" > "$BUILD/mut/onepass.c"
  build driver-onepass "$BUILD/mut/onepass.c" "-O2"
  run driver-onepass onepass-ref 0 60000
  psnr=$(ffmpeg -hide_banner -i "$BUILD/out/onepass-ref.gif" -i "$BUILD/out/stream.gif" \
         -lavfi "[0][1]psnr" -f null - 2>&1 | sed -n 's/.*average:\([0-9.]*\).*/\1/p' | tail -1)
  check "streaming matches the one-pass GIF (PSNR >= 30 dB)" "$(if lt 29.999 "${psnr:-0}"; then echo 0; else echo 1; fi)" "psnr=$psnr"
  echo "     one-pass 60 s: $(val "$BUILD/out/onepass-ref.txt" wall_s) s, $(val "$BUILD/out/onepass-ref.txt" peak_rss_mb) MB (psnr $psnr dB)"
fi

if [ "$MODE" = long ]; then
  CLIP="$MEDIA/portrait_531s.mp4"
  run driver long 0 531000
  T="$BUILD/out/long.txt"
  check "8:51 clip: completes" "$(if [ "$(val "$T" ret)" = 0 ]; then echo 0; else echo 1; fi)" "ret=$(val "$T" ret)"
  check "8:51 clip: peak memory under 200 MB" "$(if [ "$(val "$T" peak_rss_mb)" -lt 200 ] 2>/dev/null; then echo 0; else echo 1; fi)" "peak_rss_mb=$(val "$T" peak_rss_mb)"
  echo "     8:51 clip: $(val "$T" wall_s) s, $(val "$T" peak_rss_mb) MB"
  CLIP="$MEDIA/portrait_60s.mp4"
fi
fi

# --- mutants: each must fail at least one check ---------------------------
if [ "${MUTANTS:-1}" != 0 ]; then
  mutant() { # name sed-expression binary-kind(suite|asan)
    [ -z "$ONLY_MUTANT" ] || [ "$ONLY_MUTANT" = "$1" ] || return 0
    sed "$2" "$SRC/gifmaker.c" > "$BUILD/mut/$1.c"
    if cmp -s "$SRC/gifmaker.c" "$BUILD/mut/$1.c"; then
      fail "mutant $1 did not change the source"
      return
    fi
    before=$FAILS
    passes_before=$PASSES
    if [ "$3" = asan ]; then
      build "mut-$1" "$BUILD/mut/$1.c" "-O1 -fsanitize=address,undefined -fno-omit-frame-pointer"
      asan_suite "mut-$1" "[$1] " > "$BUILD/mut/$1.log"
    else
      build "mut-$1" "$BUILD/mut/$1.c" "-O2"
      suite "mut-$1" "[$1] " > "$BUILD/mut/$1.log"
    fi
    caught=$((FAILS - before))
    FAILS=$before
    PASSES=$passes_before
    if [ "$caught" -gt 0 ]; then
      pass "mutant $1 caught ($caught check(s) fail; $BUILD/mut/$1.log)"
    else
      fail "mutant $1 NOT caught"
    fi
  }
  echo "== mutants"
  mutant trailer-on-success-only 's/^    if (gif->header_written) {$/    if (gif->header_written \&\& err >= 0) {/' asan
  mutant never-stream 's/^#define BUFFERED_FRAMES_BUDGET_BYTES .*/#define BUFFERED_FRAMES_BUDGET_BYTES (1ll << 62)/' suite
  mutant interrupt-cleared-in-start '/the GIF then ran to the end\. \*\//{n;N;N;N;s/.*/    gif->interrupt = FALSE;/}' suite
  mutant burst-progress-from-zero 's/gifmaker_publish_progress(env, gif, gif->start_us + since_first,/gifmaker_publish_progress(env, gif, gif->start_us + av_rescale_q(gif->filtered_frame->pts, filter_tb, AV_TIME_BASE_Q),/' suite
fi

echo "== $PASSES passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
