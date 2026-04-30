# external/

Third-party native dependencies. Most contents here are **fetched at build time**, not committed to git.

## ffmpeg/

Custom FFmpeg build with an OkHttp-based HTTP/HTTPS backend. Built from
[firedown-ffmpeg](https://github.com/solarizeddev/firedown-ffmpeg).

Contents (after fetch):

```
external/
├── ffmpeg-version.txt          # tracked: pins which firedown-ffmpeg release to use
└── ffmpeg/
    ├── lib/<abi>/*.so          # ignored: shared libraries per ABI
    └── include/                # ignored: shared headers
```

Run `scripts/fetch-ffmpeg.sh` (also invoked automatically by Gradle) to download
the release tarball matching `ffmpeg-version.txt` and extract it into `ffmpeg/`.

To bump the FFmpeg version: edit `ffmpeg-version.txt`, commit, and re-fetch.
