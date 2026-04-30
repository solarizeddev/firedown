# Firedown

**Android browser & downloader.** Save video, audio, and images from any site. Built on GeckoView, ad-blocking via uBlock Origin, no telemetry, no Play Store.

→ [firedown.app](https://firedown.app)

## What it does

- Downloads video, audio, and images from YouTube, Twitch, Instagram, X, Facebook, Vimeo, and most websites
- 4K downloads and background YouTube playback, no Premium needed
- uBlock Origin ad blocking, configured out of the box
- Private Vault — PIN/biometric-locked encrypted storage for sensitive files
- GeckoView-based, not Chromium
- No accounts, no ads, no telemetry

## Install

- **[GitHub Releases](https://github.com/solarizeddev/firedown/releases)** — signed APKs with changelogs
- **[Direct download](https://firedown.app/download)** from the website
- **[Zapstore](https://zapstore.dev)** Coming soon
- **[Obtainium](https://github.com/ImranR98/Obtainium)** Coming soon

Not on the Play Store. Not currently submitted to F-Droid.

## Build

```bash
git clone https://github.com/solarizeddev/firedown.git
cd firedown
./gradlew assembleRelease
```

Requires JDK 17 and Android SDK 34. Resulting APK in `app/build/outputs/apk/release/` is unsigned.

## License
 
Firedown's own code is MIT-licensed. See [LICENSE](LICENSE).
 
Bundled or linked third-party components:
 
- [GeckoView](https://mozilla.github.io/geckoview/) — MPL-2.0
- [uBlock Origin](https://github.com/gorhill/uBlock) — GPL-3.0
- [FFmpeg](https://ffmpeg.org/) — LGPL-2.1+, custom build at [firedown-ffmpeg](https://github.com/solarizeddev/firedown-ffmpeg)
The combined APK is effectively GPL-3.0 due to bundled uBlock Origin. See [NOTICE](NOTICE).
 
## Support
 
Lightning ⚡ at [firedown.app](https://firedown.app).

## Notes

Parts of this codebase were reviewed and debugged with assistance from Claude (Anthropic).

