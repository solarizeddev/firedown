# external/

Third-party native dependencies. **Nothing under `external/ffmpeg/` is committed**
— it is fetched at Gradle sync time from a pinned GitHub Release.

## ffmpeg/

Custom FFmpeg build with an OkHttp-based HTTP/HTTPS backend, built by
[firedown-ffmpeg](https://github.com/solarizeddev/firedown-ffmpeg) and published
there as a Release asset (`firedown-ffmpeg-<tag>-android.tar.gz`).

```
external/ffmpeg/            # ignored — populated by scripts/fetch-prebuilts.sh
├── lib/<abi>/*.so          # arm64-v8a, x86_64 — what app/src/main/cpp links
├── include/                # the headers (arm64-v8a's copy, canonical)
└── version.txt             # "firedown-ffmpeg release <tag> (<git describe>)"
```

- **Pin:** `firedown.ffmpegRelease=<tag>` in `gradle.properties`.
- **Fetch:** automatic — `settings.gradle` runs `scripts/fetch-prebuilts.sh ensure`
  on every sync; it downloads only when `lib/` is missing. Manual:
  `scripts/fetch-prebuilts.sh ffmpeg [--force]`. Needs `gh auth login` once.
- **Local build under test:** `scripts/sync-ffmpeg.sh ../firedown-ffmpeg` copies a
  box-local build in here; the fetch then leaves it alone (warns that it differs
  from the pin). Publish it with firedown-ffmpeg's `scripts/publish-release.sh`
  and bump the pin when it is good.
- **Bump:** change the pin, commit; other machines pick it up on their next
  sync (`--force` to replace an older copy already present).

The GeckoView AAR follows the same scheme but lands in `~/.m2` (mavenLocal), not
here — see `docs/NEW-MACHINE.md`.
