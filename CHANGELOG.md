## [1.1.86] - 2026-07-30

- Connect a Lightning wallet once and pay for storage credit in one tap — no more copying the invoice into another app. Works with Alby Hub, Coinos, Mutiny or any wallet that speaks Nostr Wallet Connect
- Share Firedown from Settings — a QR code someone standing next to you can scan, or a link you can send to anyone else
- Minor bugfixes and improvements

## [1.1.85] - 2026-07-30

- Pair a second device by QR — show your recovery code as a QR code on one phone and scan it on the other to share one cloud account. You can now also enter a code on a device that already has its own
- Cloud Backup previews are sharper, and now appear on your other devices — some videos silently backed up with no preview at all
- The home screen's backup status is a quiet line instead of a filled pill, and can be turned off in Cloud settings
- Removing a backed-up file while it's being restored now asks first, and cancels the restore cleanly
- Fixed hard-to-read text in light theme on the Backups screen
- The Downloads list now mentions Cloud Backup once, dismissibly, if you haven't set it up
- Minor bugfixes and improvements

## [1.1.84] - 2026-07-20

- Fixed a startup crash loop on profiles with many open tabs — each tab's state is now stored in its own small file and loaded only when the tab is opened, so startup stays fast and lean no matter how many tabs you keep
- Fixed images on pixiv and other hotlink-protected sites — pages, long-press saves and Captured thumbnails all load again
- Fixed downloads that could stick on "Finishing…" forever
- Send directly now connects when a phone is on a VPN, and explains when the transfer has to go through the relay
- Selecting text on a page now highlights in the app's accent color instead of grey
- Simplified Settings — Security and Direct share moved to their own screens under Privacy and Downloads
- Replaced the in-app donation screen with a Donate link that opens firedown.app/donate
- Minor bugfixes and improvements

## [1.1.83] - 2026-07-16

- Send directly — share any finished download straight to another phone over an encrypted, device-to-device connection. No upload, no account; scan a QR or tap a link to pair
- Updated the GeckoView browser engine to 152.0.20260713164047
- Minor bugfixes and improvements

## [1.1.82] - 2026-07-08

- Redesigned the browser menu — one-tap bookmark star and a compact two-row action layout
- Added Nostr (NIP-07) support via the Amber signer
- More reliable download restore after a reinstall
- Updated the GeckoView browser engine to 152.0.20260706120035
- Minor bugfixes and improvements

## [1.1.81] - 2026-06-29

- Save snapshot — archive a web page as a single self-contained HTML file you can reopen offline, in-app
- Sync your bookmarks across devices — end-to-end encrypted, no account needed
- Minor bugfixes and improvements

## [1.1.80] - 2026-06-24

- YouTube downloads now finish ready to play — correct length with working seek/scrub, and no extra processing step after the download
- Updated the GeckoView browser engine to 152.0.20260621191700
- Updated FFmpeg to 8.1.2
- Minor bugfixes and improvements

## [1.1.79] - 2026-06-18

- Fixed video capture on X / Twitter — single posts and the home feed reliably detect videos again, and capture now keeps working through X's frequent layout changes
- Fixed restoring your downloads from a backup
- Updated the GeckoView browser engine to 152.0.20260612001812
- Minor bugfixes and improvements

## [1.1.78] - 2026-06-16

- Home screen now labels the Saved counter even before your first download
- Fixed an unreadable help banner on the incognito Captured sheet
- Minor bugfixes and improvements

## [1.1.77] - 2026-06-08

- Download from Mega.nz — folder links, single file links, and embedded Mega videos
- Captured Mega files now show real thumbnails
- Minor bugfixes and improvements

## [1.1.76] - 2026-05-30

- Disk cache is now on by default for faster repeat visits; the memory-only option stays available for advanced users
- Fixed the cookie-notice blocker toggle that could appear to do nothing
- Cookie-notice toggle now responds instantly instead of stalling while filters rebuild
- Minor bugfixes and improvements

## [1.1.75] - 2026-05-30

- WebAssembly now enabled by default, with a Disable WebAssembly toggle and per-site exceptions
- Sites needing WebAssembly while disabled now reliably offer a one-tap enable
- Download videos from X / Twitter when signed in, and straight from the feed
- Tabs now remember the last page you visited after closing the app
- Fixed tabs that could stop responding or return blank
- Trimmed unused extension code and closed background resource leaks
- Minor bugfixes and improvements

## [1.1.74] - 2026-05-29

- Redesigned the home screen and a calmer incognito start page
- Material 3 address-bar suggestions with a cleaner clipboard card
- Simpler page-security sheet — blocked count plus protection toggles
- Polished the active-download, incognito and archived-tab banners
- Added Safe Folder, Downloads and History shortcuts to the menu
- Fixed tabs that could stop responding or return blank
- Fixed browser-menu and certificate-details dialog sizing
- Translations for the new strings
- Minor bugfixes and improvements

## [1.1.73] - 2026-05-25

- Added TikTok video and metadata capture
- Added Picture-in-Picture support for audio
- Block sites pushing the Play Store install prompt
- Minor bugfixes

## [1.1.72] - 2026-05-20

- Added Home cards style picker (Settings → Theme → Home cards)
- Added crash reporter with one-tap GitHub issue prefill
- Refreshed Downloads and captured-content layouts
- Pill-shaped filter chips and Tabs / Incognito selector
- Translations across 83 locales for the new strings
- Bump Gecko to version 151.0.20260513195118
- Minor bugfixes

## [1.1.71] - 2026-05-15

- Added double tap on video playing
- Added experimental webp support on libavcodec
- Minor bugfixes

## [1.1.69] - 2026-05-13

- Fix video playback transition
- Added option to disable WebAssembly
- Bump Gecko to version 150.0.20260511200624 (Mozilla Security Advisory 2026-45)

## [1.1.68] - 2026-05-11

- Added Pip mode in the video player
- Added AMOLED Theme
- Fixed a couple of deadlocks in the downloader
- Minor bugfixes and improvements

## [1.1.67] - 2026-05-09

- Fix Widevine plugin in setDRM

## [1.1.66] - 2026-05-09

- Bump Gecko to version 150.0.20260506140522
- Minor bugfixes and improvements
