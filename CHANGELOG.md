## [Unreleased]

- Fixed a crash after a very long download session: Android stops a background download service after six hours, and the app now stops cleanly and leaves the unfinished downloads as retryable entries instead of crashing.
- Deezer: full tracks now capture and download from a logged-in session (the browser must be signed in to Deezer); the file is decrypted on the device. Logged out, only the 30-second preview is available.

## [1.1.94] - 2026-09-02

- Fixed: "Open link in new tab" on an app link (`intent://…`, e.g. Play Store's "Open in app" banner) created a tab stuck on about:blank. It now opens the link's web page in the new tab, or the open-in-app dialog when the link has none.
- Instagram: videos on a post page are captured again (Instagram moved the media into a new router endpoint the parser did not read)
- Fixed a crash on the Downloads screen for long download lists (a database read racing a delete)
- Captured media: Copy URL is now a one-tap action on the row and in the quality picker, and multi-select copies every selected URL; the Share and Open-in-another-app menu was removed (an app handed the bare URL cannot carry the headers most streams need)
- Captured media: the page's own video now stays pinned first after opening a page in a new tab
- Pages embedding several videos now show each video with its own thumbnail
- Deleting many downloads at once is now a single database write
- Screen readers announce the Captured row button by name

## [1.1.93] - 2026-09-01

- Updated the GeckoView browser engine to 155.0.20260826195058
- Captured media: the ⋮ on any capture now offers Copy URL, Share URL and Open in another app, and the quality picker is a compact grid with audio-track and caption chips
- Storage credit: card payments now complete (they were charged but never credited), and Reopen checkout keeps the payment screen in front
- Cloud Backup: sharper thumbnails for new backups; the Cloud screen no longer shows a full gauge on a well-funded account
- Minor bugfixes and improvements

## [1.1.92] - 2026-08-27

- Updated the GeckoView browser engine to 154.0.20260824154132
- Google Maps: place photos are now captured — including the full-size copy when you open one — and the map's own tiles no longer flood the Images list
- Dailymotion videos embedded on other sites (news sites like marca.com) are now captured, with title and qualities
- Back up a finished download to the cloud straight from its finished notification
- Fixed crashes: one from tapping a "new tab opened" snackbar after leaving the browser, one in the Downloads list on entries with no source URL

## [1.1.91] - 2026-08-19

- Updated the GeckoView browser engine to 154.0.20260814215756
- YouTube: long downloads that get re-checked repeatedly mid-stream now recover every time — a download is no longer failed after two successful recoveries, recovery works on slow devices and connections, and a dropped connection during recovery is reported as a network problem instead of a YouTube rejection
- Pages can no longer lock up the browser with endless popup dialogs — after three in quick succession the rest are dismissed automatically, and a file picker and a popup no longer trip over each other
- Fixed crashes: one from certain page dialogs, one when leaving the app right after opening a link from another app, and one while editing a filename in the save dialog
- The site security panel no longer presents a missing certificate as an expiring one
- Minor bugfixes and improvements

## [1.1.90] - 2026-08-12

- YouTube: long downloads no longer stop about a minute in and end up as short truncated files — when YouTube asks the app to re-authorize mid-download it now gets a genuinely new token, instead of retrying with the one that was just refused

## [1.1.89] - 2026-08-12

- Send directly: a dropped transfer now resumes where it left off instead of restarting from the beginning, and a share link stays live for its full 15 minutes so the reply comes back on its own
- A share link that no longer works now says the sender closed it, instead of blaming your Wi-Fi — and the transfer footer only claims a file never touched a server when that's actually been confirmed
- Scrub through a video by dragging: frames preview as you go, and the 10-second skip buttons sit better beside play/pause
- The update sheet keeps Install in reach, "Later" really does mean later, and it now speaks all 16 languages — plus Settings → About installs an update that's already downloaded rather than fetching it again
- Downloads that are backed up to the cloud are marked in the list
- Clearer, shorter wording throughout the app, in every language
- Updated the GeckoView browser engine to 153.0.20260810162159 and FFmpeg to 9.0.1
- Minor bugfixes and improvements

## [1.1.88] - 2026-08-09

- Play downloaded videos and music in the background — playback keeps going with the screen off or after leaving the app, with lock-screen controls. Picture-in-picture hands off to background playback, and dismissing the PiP window stops it
- YouTube: downloads default to the original-language audio track with a new track picker, prefer H264 up to 1080p so thumbnails work everywhere, and interrupted downloads now re-authorize and continue instead of ending up as short truncated files
- Instagram capture fixed for the current site — posts and reels again, plus carousels, the home feed, and clips whose video came without audio
- Duplicate tabs are archived automatically, keeping the most recently used copy — and tab archive retention is now stated in Settings
- Crash reports can be sent anonymously with one tap
- Player polish: opens faster, double-tap seeking accumulates per tap, controller fixes after picture-in-picture
- Updated the GeckoView browser engine to 153.0.20260803132010 and FFmpeg to 9.0
- Minor bugfixes and improvements

## [1.1.87] - 2026-08-01

- Access your backups from a computer — open firedown.app/backup and pair it by scanning a QR code instead of typing your recovery code; approve on the phone by matching the six-digit code both screens show
- Check for updates on demand from Settings → About
- Simplified Settings — the fingerprinting toggles moved into Security, and Licenses into About
- Cloud Backup encryption hardened — every encrypted chunk is now bound to its exact file and position
- Updated the GeckoView browser engine to 153.0.20260730155536
- Minor bugfixes and improvements

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
