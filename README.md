# Firedown

**Android browser & downloader.** Save video, audio, and images from any site. Built on GeckoView with uBlock Origin ad-blocking. No telemetry, no Play Store, no accounts.

→ [firedown.app](https://firedown.app)

<p align="center">
  <img src="https://raw.githubusercontent.com/solarizeddev/firedown/main/branding/screenshots/landing.png" width="30%" />
  <img src="https://raw.githubusercontent.com/solarizeddev/firedown/main/branding/screenshots/browsing.png" width="30%" />
  <img src="https://raw.githubusercontent.com/solarizeddev/firedown/main/branding/screenshots/capturing.png" width="30%" />
</p>

## What it does

- Downloads video, audio, and images from YouTube, Twitch, Instagram, X, Facebook, Vimeo, and most websites
- 4K downloads and background YouTube playback, no Premium needed
- Convert to MP3, AAC, or GIF
- uBlock Origin ad blocking, configured out of the box
- Private Vault — PIN/biometric-locked storage for sensitive files
- GeckoView-based, not Chromium
- No accounts, no ads, no telemetry

## Install

- **[GitHub Releases](https://github.com/solarizeddev/firedown/releases)** — signed APKs with changelogs
- **[Direct download](https://firedown.app/download)** from the website
- **F-Droid** — add the official repo `https://firedown.app/fdroid/repo` in your F-Droid client for automatic updates
- **[Zapstore](https://zapstore.dev/apps/com.solarized.firedown)**
- **[Obtainium](https://github.com/ImranR98/Obtainium)** — add repo `https://github.com/solarizeddev/firedown`

Not on the Play Store, and not in the main F-Droid repository — use the self-hosted F-Droid repo above.

## Build

```bash
git clone https://github.com/solarizeddev/firedown.git
cd firedown
./gradlew assembleRelease
```

Requires JDK 17 and the Android SDK 37 platform (compile-only; the app targets
SDK 36 and runs on Android 8.0+). Resulting APK in
`app/build/outputs/apk/release/` is unsigned.

## License

Firedown's own code is MIT-licensed. See [LICENSE](LICENSE).

Bundled or linked third-party components:

- [GeckoView](https://mozilla.github.io/geckoview/) — MPL-2.0
- [uBlock Origin](https://github.com/gorhill/uBlock) — GPL-3.0
- [FFmpeg](https://ffmpeg.org/) — LGPL-2.1+, custom build at [firedown-ffmpeg](https://github.com/solarizeddev/firedown-ffmpeg)

The combined APK is effectively GPL-3.0 due to bundled uBlock Origin. See [NOTICE](NOTICE).

## Support

Firedown is free and has no ads, accounts, or telemetry. If you'd like to support development:

- ☕ **[Buy Me a Coffee](https://buymeacoffee.com/solarized)**
- ⚡ Lightning at [firedown.app/donate](https://firedown.app/donate)

---

*Developed with AI-assisted code review.*
