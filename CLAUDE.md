# CLAUDE.md

Guidance for Claude (and other agents) working in this repo. Read this before
touching the media parsers or debugging "video not picked up" issues.

## What Firedown is

Android browser + downloader built on **GeckoView** (not Chromium), with
uBlock Origin. Media capture is done by a set of **built-in WebExtensions**
bundled as assets and loaded via `GeckoRuntimeHelper.registerBuiltIn(...)`
(`app/src/main/java/com/solarized/firedown/geckoview/GeckoRuntimeHelper.java`).

### Capture requires a live GeckoSession — there is NO "paste a link → grab"

**Media capture ONLY happens while a page is actually loaded in a
`GeckoSession`.** The WebExtensions observe a *live* page — the wire
(`webRequest`/`filterResponseData`), the DOM (content script), and page-world JS
state — so there is **nothing to capture until the user navigates to the page and
it loads/plays in the GeckoView.** You cannot hand a bare URL to a background
fetcher and get rich media + variants: a plain fetch has no page context, no
player, no SW, no cookies/headers/anti-bot fingerprint, and most sites only
expose the real media URL after the page's own JS runs (often on play). So:

- **Do NOT design or propose a "paste a link and download it" home action / hero
  / flow.** It is not a feature this architecture can deliver. (A pasted URL can
  only do what the address bar already does — *open* it in a tab, which then
  captures normally.)
- The capture surface is the in-app browser itself (the Captured sheet fills as
  you browse), not a standalone URL-in/file-out box.
- This is a product-level invariant, not a missing feature to add. Don't
  re-suggest it in UX sketches or redesigns.

### The extensions (`app/src/main/assets/`)

| dir           | id                       | role |
|---------------|--------------------------|------|
| `webrequests/`| `downloader@solarized.dev` | **ALL capture** — the former `parser@` extension was MERGED into this one. Two halves in one extension: (1) the per-site **parsers** (`js/parsers/` — one ES module per site: Twitter/X, Instagram, Threads, Facebook, Vimeo, Rumble, Bilibili.tv, Niconico, Kick, Twitch, Dailymotion, Apple Podcasts, News Over Audio, TikTok, Bluesky, Telegram, Videee, Spotify; emits entries **with metadata** — title, author, thumbnail, duration, quality variants) plus the page-state bridge (`js/page-state-bridge.js`); (2) the **generic catch-all** (`js/requests.js` + `js/content-script.js` — any media URL seen on the wire, no rich metadata). Also hosts `js/wasm-watch.js` (+ `js/wasm-probe.js`), the WASM-disabled detector — a settings feature, not capture. |
| `youtube/`    | `youtube@solarized.dev`  | YouTube (separate; uses `PoTokenGenerator` on the Java side). |
| `ublock/`     | uBlock Origin            | Ad blocking. |
| `p2pshare/`   | `p2pshare@solarized.dev` | **Not capture** — the P2P direct-share WebRTC engine (see ""Send directly" — P2P share"). |
| `icons/`, `search/`, `db/`, `error/` | — | Support. |

There is **no separate `parser/` extension anymore** — it was merged into
`webrequests/` so the page-state bridge can consult `parser-blocklist.js`
directly (fixing the Dailymotion-class duplication where the bridge's generic
player readers re-captured a parser-owned site; the bridge's generic
`page-state-hls`/`page-state-progressive` handlers now skip a URL that matches
the block-list). `GeckoRuntimeHelper` explicitly **uninstalls the orphaned
`parser@solarized.dev` registration** on boot (`uninstallOrphanedExtension`)
because GeckoView persists built-in registrations across in-place updates.
Throughout this doc, "the parser" / a "`parser@` module" means a per-site
module under `webrequests/js/parsers/`, and a bare "`background.js`" in
parser/bridge context means that module tree (its history:
`parser/background.js` before the merge, then the monolithic
`parser-background.js` classic script until the per-site module split).

Background-page layout (`webrequests/background.html`): **everything is an ES
module** — `regex.js` / `parser-blocklist.js` / `requests.js` / `cookies.js`,
plus the per-site parsers under `js/parsers/` entered through
`js/parsers/index.js`. The parser tree: one module per site, `common.js` (the
shared infra: log/dedup/native messaging/`parseHlsMaster`/
`enumerateMasterNative`/tab resolution/`readFilteredBody`), `page-state.js`
(the page-state-bridge + Mega message handlers), `boot.js` (existing-tab
sweep; imported last by `index.js` so every site module has registered first).
Cross-module access is **explicit imports** — `page-state.js` imports
`matchInParserBlocklist` from `parser-blocklist.js` and `getAmbientHeaders()`
from `requests.js`; the old classic-vs-deferred execution-order trap and its
`typeof`-guarded `globalThis` bridges are gone. Site modules plug into two
registries in `common.js` instead of hardcoded fan-outs: the **message
router** (`registerMessageHandler(kind, fn)` — the ONE `runtime.onMessage`
listener on the parser side, keyed `message.kind ?? message.type`,
fire-and-forget by contract: it never returns a handler's value, so it can
never become the message's responder and race `requests.js`'s own listener)
and the **SPA registry** (`registerSpaHandler(fn)` — runs each handler on
every tab-URL change AND in `boot.js`'s existing-tab sweep; handlers must be
cheap and self-filtering, host test first). Adding a site = one new module in
`js/parsers/` + an `import` line in `index.js` (+ the block-list rule, see the
cardinal rule). **Verify any parser change with
`node scripts/webrequests-smoke.mjs`** — it imports the whole background
module graph under a stubbed `browser` (ESM link-checks every import/export),
asserts the listener-registration inventory, dispatches every router kind,
drives the SPA handlers, and unit-checks the pure helpers; it's also the
template for HAR-replay tests that run the REAL extraction code (import the
site module's walker directly — no more copy-pasted simulations).

Native bridge: the parser half still calls
`browser.runtime.sendNativeMessage("parser", …)` and the catcher half uses
`"browser"` — the merged extension's message delegate is registered under BOTH
nativeApp names (globally and per-session, mirroring the youtube/PoToken
multi-name pattern in `GeckoRuntimeHelper`). Java handles captures in
`GeckoRuntimeHelper.handleExtractionMessage` / `GeckoInspectTask`.

## Parser vs. generic catcher — the cardinal rule

**A site that has a dedicated parser must be captured by the parser, NOT the
generic catcher.** The two are mutually exclusive *by design*:

- The parser gives metadata + quality variants. The generic catcher gives a
  bare URL.
- To stop them both firing on the same video (which produces a **duplicate**
  download entry), the site's media URL is **block-listed** in
  `app/src/main/assets/webrequests/js/parser-blocklist.js` — a declarative,
  per-parser host/CDN table consulted pre-probe by `validateAndClassify` in
  `requests.js`. Examples already there: `instagram.*\.mp4` (covers Instagram
  **and** Threads — same fbcdn hosts), `video\.twimg\.com.*\.(mp4|m4s|m3u8)`
  (Twitter/X). (This is **separate from `regex.js`**, which holds only the
  generic, CDN-agnostic junk — telemetry beacons, init/numbered segment
  fragments; parser-dedup blocks pair 1:1 with a parser, so they live in their
  own file. See the split note below.)

Consequences when working on a parser:

- **MANDATORY: every new or changed parser MUST add a matching block rule to
  `app/src/main/assets/webrequests/js/parser-blocklist.js` for the media URL(s)
  it emits.** Add a key for the parser (or extend its existing key) in the
  `PARSER_BLOCKLIST` table. Without it, the generic catcher *also* captures the
  same media and you get a duplicate download entry (one rich from the parser,
  one bare from the catcher). Adding a parser is not done until its block rule
  exists. **This applies ONLY to site-specific parsers — a dedicated `parser@`
  module, or a host-keyed branch of the page-state bridge (Bilibili.tv, Mega).
  Media captured by the bridge's GENERIC, host-agnostic player readers
  (`findPlayerMedia`/`readPlayerMedia`/`readDomMedia` — the JSON-delegate /
  series.ly-krakenfiles class) gets NO block rule**: the bridge fires pre-play and
  the repository dedups by URL, so a block would only suppress the play-time
  capture with no parser owning the (often shared/random) host. See "Dedup on play"
  in the page-state-bridge section and the `parser-blocklist.js` header. Examples:
  - Instagram/Threads → `instagram.*\.mp4`
  - Twitter/X → `video\.twimg\.com.*\.(mp4|m4s|m3u8)`
  - Rumble → `rumble\.com\/hls-vod\/.*\.m3u8` (the HLS master the parser emits)
  - Bilibili.tv → `upos-.*(bilivideo\.com|akamaized\.net)\/iupxcodeboss\/.*\.m4s`
  Pick the pattern that matches exactly what the parser emits (and the segments
  the player fetches for it), but is narrow enough not to swallow unrelated media.

Some parsers read the page's own JS state instead of a network response —
**Bilibili.tv** is the example: the play page SSR-inlines the playurl into
`window.__initialState` (a devalue IIFE) and fires no playurl XHR, so it can be
read **only** from the page world. This is handled by the **generic page-state
bridge** (see below), not a per-site script: the bridge finds
`player.playUrl.dash.{video,audio}` and emits the two whole-track `.m4s` baseUrls
(DASH SegmentBase — each baseUrl is one complete track byte-range-fetched, *not*
a segment list) as video+audio variants, which `FFmpegMergeStrategy` muxes
natively (no ffmpeg.wasm).

### Page-world state — the generic `wrappedJSObject` bridge (no per-site scripts)

When a site inlines the playable media into a page-world JS global
(`window.__initialState`, `__NEXT_DATA__`, `__NUXT__`, a Redux/Apollo store, a
devalue blob …) and fires **no** playurl XHR, neither the wire nor the DOM can
see it — only page-world JS can. **Do NOT add a per-site content-script + WAR
inject pair for this** (the old Bilibili.tv approach, since removed). One
catch-all content script, `webrequests/js/page-state-bridge.js`, matched on
**`<all_urls>`** and **`all_frames: true`** (so it needs **no per-site `matches`
and no host permissions**, and it reaches embedded cross-origin player iframes —
see the HLS-master path below), covers every such site:

- It reads the page's real globals via Firefox's Xray **`window.wrappedJSObject`**
  waiver — the same mechanism `youtube/content.js` (PoToken `eval`) and
  `webrequests/content-script.js` already rely on in this GeckoView, so it's
  confirmed to work. **No `<script>` inject, no web-accessible resource, no
  page-CSP problem.**
- It runs a **bounded** generic search (`findDash`, depth/node-capped) for a DASH
  `{video[],audio[]}` slice, **copies it to plain data** with index loops
  (Xray-waived page arrays misbehave with `.some`/`.map`/`.filter` callbacks —
  use direct reads), builds video+audio variants, and posts them to the
  background.
- Background `kind:"page-state-media"` → `sendVariants` with a **generic** Referer
  = the page origin (covers bilibili's upos/bilivideo anti-leech without any
  per-site host check). Per-site **request/emit** specifics belong in
  `background.js`, never as new injected files.
- **It ALSO reads the URL the PLAYER WAS FED, from its live JS API**
  (`findPlayerMedia` → `readPlayerItem`, JWPlayer
  `jwplayer().getPlaylist()[].file`/`.sources[].file`), for sites whose player
  fetches a (often obfuscated/packed) source **only on PLAY** (`preload: none`) —
  e.g. series.ly's vibuxer/luluvdo/lulustream jw8 embeds. The wire can't see it
  pre-play, but the player must hold the **de-obfuscated** url at `setup()`
  (preload defers only the *fetch*, not setup), so reading the resolved source
  captures it without a play click — **agnostic to however the site packed the
  source** (we read the *result*, not the packed blob — *not* cat-and-mouse). This
  is the most precise capture and the **preferred** one when a player exposes its
  source on an API. It reads **both** an **HLS master** (`.m3u8`) **and progressive
  `.mp4`/`.m4v`/`.webm`** sources, each with its label/height (generalised from the
  old HLS-only `findPlayerHls`). This is **why** the bridge runs `all_frames` (the
  player is an embedded cross-origin iframe). Emit splits by kind: an HLS master →
  Background `kind:"page-state-hls"` → `enumerateMasterNative` (Java enumerates
  qualities, no probe); progressive → `kind:"page-state-progressive"` (see below).
  For the HLS master, **headers must byte-match a real browser fetch** — these
  stream CDNs run a strong anti-bot, and the native master fetch is synthetic (the
  wire never saw it, so there are no cached headers to reuse). On-device the ONLY
  difference between a 403 and a 200 was a missing `;q=0.9` on `Accept-Language`.
  So the `page-state-hls` emit sends the full browser set: `Origin` (explicit —
  `OriginInterceptor` only derives it same-site), full-path `Referer` = the embed
  iframe URL, the `Sec-Fetch-Dest/Mode/Site` trio (Sec-Fetch-Site *computed*
  same-origin/same-site/cross-site, not hardcoded), and `User-Agent` +
  `Accept-Language` read from the page's real `navigator` (UA verbatim; languages
  formatted WITH q-values, `en-US,ko-KR;q=0.9` — a bare `join(",")` is a bot tell).
  Rule: add a header only if the browser sends it and format it exactly. Ceiling
  worth knowing: this matches header *values*; the strongest systems also
  fingerprint TLS (JA3/JA4) + header order, which OkHttp can't mimic — header
  spoofing can't beat those. **No dup on play:** the source URL is signed/stable,
  so when the user does play, the wire sees the **same** URL and the repository
  dedups it by URL; the player's raw `.ts` segments are dropped natively
  (`isValidMedia` → `mpegts`). Add another player's API (Video.js/Plyr…) as a new
  block in `findPlayerMedia`, never a per-site file.
- **It ALSO reads ANY page-world player's MEDIA LIST — the generic FALLBACK**
  (`readPlayerMedia` → `collectPlayableMedia` → `resolveAndEmitPlayerMedia` →
  `emitOneGroup`), for players that DON'T expose their fed URL on a readable API
  like the one above — instead the fed value is a **config / source-list in a
  page-world global** (or a tokenized JSON delegate that only resolves to a URL
  after a fetch). Capture **whenever a site holds a playable media URL in a
  page-world JS global before the player fetches it on play** — a custom player
  config (`window.page_params`, `flashvars`…), a framework store
  (`__NEXT_DATA__`/`__NUXT__`/Redux…), or any plain object. Like `findPlayerMedia`
  it runs in **all frames** (the player is often a cross-origin iframe) and reads
  the player's **RESOLVED** page-world values, so a packed / `eval`-obfuscated
  source URL is read **post-resolution**, never the packed blob (read the result,
  not the packer — not cat-and-mouse).
  **How it matches (shape, never host):** walk the state tree (bounded — depth +
  30k-node budget + visited set, index-loop/primitive reads only, Xray-safe) and
  collect a URL only when it sits under a **media-ish KEY** (`videoUrl`/`url`/`src`/
  `file`/`hls`/`source`/`contentUrl`… — `MEDIA_KEY_RE`) AND its **VALUE is a real
  media URL**: the key says "a player", the value extension says "a url to play"
  (this key-proximity is what keeps it off the page's countless non-media URLs —
  share/canonical/next links carry no media extension). Three outcomes per hit:
  `.m3u8` → HLS master (`postHlsMaster` → `page-state-hls` — Java enumerates, no
  probe); `.mp4`/`.m4v`/`.webm` → progressive variant; **`.mp3`/`.m4a`/`.aac`/
  `.ogg`/`.opus`/`.flac`/`.wav`/`.weba`** (`AUDIO_RE`) → progressive AUDIO variant
  (no resolution) — podcast/article audio inlined in page state (e.g. podverse's
  `__NEXT_DATA__.mediaUrl`, a substack TTS `audio_url`); `MEDIA_KEY_RE` therefore
  carries `audio_?url`/`enclosure` keys alongside the video ones. **The audio
  variant is marked `audioOnly: true`** and `VariantProcessor`'s skip-probe
  branch honors it — the entity keeps the URL-derived audio mime + `audio` flag
  and downloads as raw FILE (`HttpDownloadStrategy`), instead of the branch's
  video/mp4 stamp (which once made a podverse podcast mp3 show as a VIDEO and
  save under a `.mp4` name). The same mark flows from every bridge reader:
  `mediaKindOf` has a third `"audio"` kind (an `audio/*` MIME or `AUDIO_RE`
  extension — so an `.mp3` also never falls into the obfuscated-master HLS
  default), and a DOM **`<audio>` element** types its sources audio regardless
  of extension (a `.mp4`-container m4a on `<audio>` is still audio); a
  **SAME-ORIGIN non-media URL beside a `format`/`quality`/`segmentFormats` hint**
  → a tokenized **media-list DELEGATE** (e.g. a player whose source is
  `…/media/mp4/?s=<token>` returning `[{quality, videoUrl:…mp4}]`), resolved with
  a **credentialed SAME-ORIGIN `fetch`** (`fetchMediaList`). Same-origin is the
  whole point: the bridge runs ON the page, so the resolve needs **no host
  permission and hits no CORS** — which is why it lives in the bridge, not
  `background.js` (which would need a per-host permission + CORS bypass).
- **Why a JSON delegate is invisible to everything else** (the case that
  motivated this): the real `.mp4` URLs live **only inside an `application/json`
  XHR body** the generic catcher rejects (`classifyXhr` drops `application/json`)
  and **never in the DOM** (the page carries only the tokenized delegate — no
  media extension — so the passive scrape and the manifest body-sniff skip it).
  The page fetches the delegate **on LOAD, not on play**, so nothing is captured
  until the user presses play and the wire finally sees a real `.mp4`. Reading the
  list page-world + resolving the delegate fixes exactly that.
- **Grouping (one entity per video, no merging, no related-list spam).**
  `collectPlayableMedia` returns **groups**, one per clip: (1) a media-LIST array
  under a `LIST_KEY` (`sources`/`mediaDefinitions`/`qualities`/`levels`/
  `renditions`/`formats`/`variants`) is **one** group (its entries are qualities
  of the SAME clip), and (2) a single player object's own media-key string(s) is
  one group. `LIST_KEY` is **deliberately narrow** — NOT `videos`/`items`/
  `playlist`, which hold *different* clips and must not merge. **Noise guard:**
  rule (2) is skipped for objects that are **entries of an array**, so a
  related/recommended-videos array doesn't turn every item into a capture (a main
  clip nested in an array still yields its own `LIST_KEY` source list via rule 1).
  Total groups are capped (`MAX_PLAYER_GROUPS`). Each group emits as its **own**
  entity.
- **Emit + metadata.** Progressive → Background `kind:"page-state-progressive"` →
  `sendVariants` (skip-probe auto-set from any page `duration`; the variant
  carries only `height`, `width:0` — `JsonHelper` renders `"720p"`, never
  `"0x720"`). **Request headers: the MEDIA-ELEMENT request set, but NO
  Referer/Origin/Cookie** — the `<video>`-element shape for video groups, and for
  a declared-audio group (primary variant `audioOnly`) the `<audio>`-element
  shape (Firefox's audio `Accept` string + `Sec-Fetch-Dest: audio` — a
  video-shaped header set on an mp3 is exactly the deviation a header-gating CDN
  rejects). These URLs are query-signed/self-authorizing (verified
  in practice: the real browser fetch carries no Referer/Origin/Cookie), so those are
  omitted — but some progressive CDNs still gate on the headers a real `<video>`
  fetch *always* carries: krakencloud's `/play/video/<token>` (series.ly) **404s a
  bare GET** (and a UA-only ffmpeg probe), while the **byte-identical** url+token
  plays in-browser as a **206** — the difference is `Accept: video/*` +
  `Sec-Fetch-Dest: video` + `User-Agent` + `Accept-Language`. So
  `handlePageStateProgressive` sends exactly that set (UA/Accept-Language from the
  bridge's `readNavigatorHints`), benign for self-authorizing CDNs since a real
  browser sends them too. Range is NOT sent up-front (`HttpDownloadStrategy`'s
  fresh request is no-Range by design) but its **403/404/416 retry adds
  `Range: bytes=0-`** carrying these same headers — so the retry replicates the
  player's exact request (headers + Range → 206). Ceiling: a CDN that also
  fingerprints **TLS (JA3/JA4)** is still unreachable to OkHttp, header-spoofing or
  not. (HLS-master CDNs get the fuller set incl. Origin/Referer — see
  `postHlsMaster`.) Title is the generic page title; `duration`/`poster` come from
  `DURATION_KEY_RE`/`IMG_KEY_RE` siblings of the source when present.
- **Dedup on play — NO `parser-blocklist.js` rule for generic-bridge sites.** The
  player's default quality is the entity's primary URL, and the bridge fires
  PRE-PLAY, so it dedups **by URL** against the play-time wire capture. A media
  captured by the bridge's **generic, host-agnostic** readers (the
  JSON-delegate / series.ly-krakenfiles class) **never gets a `parser-blocklist.js`
  entry** — a block there would only suppress the play-time capture with no parser
  owning the (often shared / per-video-random) host. The accepted trade-off is a
  rare *other*-quality duplicate (a manually-picked non-default rendition whose URL
  differs from the bridge's primary) — same stance as TikTok's first-video case.
  **The block-list is for SITE-SPECIFIC parsers only** (a dedicated `parser@`
  module, or a host-keyed bridge branch like Bilibili.tv / Mega — those keep an
  entry only because the catcher would otherwise emit a *harmful* capture: an
  unplayable whole-track `.m4s`, or undecryptable AES-CTR bytes — not a benign
  same-URL dup). See the header of `parser-blocklist.js`.
- **`readPlayerMedia` is NAME-AGNOSTIC — do NOT add global names.** It scans
  EVERY enumerable page-world global object (`Object.keys(wrappedJSObject)`), not a
  hand-kept list, so a config under any name is found: `page_params`,
  `flashvars_<videoid>` (an id-suffixed name no fixed list could
  match), `__NEXT_DATA__`, a Redux store, a one-off `var`. This is safe to scan
  broadly because **the precision is in `collectPlayableMedia`'s walk, not the
  global name**: a url only counts under a media-ish KEY (`MEDIA_KEY_RE`) with a
  media-extension VALUE or a same-origin delegate, so non-media globals are
  ignored. Bounded three ways: a `GLOBAL_DENY` denylist of huge native window
  members (document/location/…), `MAX_GLOBALS_SCANNED`, and a SHARED node budget
  (`SCAN_NODE_BUDGET`) across the whole pass (one giant store can't starve the
  rest). Known config names are scanned first (common case, found cheaply).
- **To extend:** add a key to `MEDIA_KEY_RE` / `LIST_KEY_RE` / `QUALITY_KEY_RE`
  (etc.) only if a player names its source/quality/duration FIELDS differently, and
  extend `fetchMediaList`'s wrapper-array keys for a new delegate JSON shape.
  **Never** a per-site file, **never** a global NAME (it's scanned automatically),
  **never** a host check in `background.js` or the bridge, and **never** a
  `parser-blocklist.js` rule for a generic-bridge host.
- Cheap on the ~all sites/frames with no such state: the scan reads object globals
  only (skipping primitives/functions/denylist), the walk is shared-budget-capped,
  and it no-ops instantly when nothing holds media (so ad/tracker iframes pay only
  a presence check); the
  persistent SPA-nav observer is armed **only after** a successful capture.

**Metadata: generic by default, host-keyed when richer fields exist in page
state.** `resolveMeta(root)` defaults to `og:title`/`document.title` + `og:image`;
a known host overrides it with a branch **in the bridge** (e.g. Bilibili.tv reads
`root.ogv` for the episode-precise `Season Episode` title + per-episode cover).
That branch lives in the bridge — **not** `background.js` — because the bridge is
the only place that holds the page-world `root`; the background can't read page
state. Adding a host here is still **not** a new content script. Reads in such a
branch must be index-loop / primitive only (Xray-waived page arrays misbehave
with iterator/callback methods). A site still needs its `parser-blocklist.js`
block rule for the media it emits (Bilibili.tv's `.m4s` rule is unchanged), same
cardinal rule as any parser.
- **Do not** "fix" a missing capture by removing/bypassing the block so
  the generic catcher grabs it. That reintroduces the duplicate and drops
  metadata. Fix the **parser** instead.
- **The two block lists are different in kind — put each rule in the right one:**
  - **`parser-blocklist.js`** (`PARSER_BLOCKLIST`) — the per-parser host/CDN
    dedup blocks (the cardinal rule). **Bundled-only**, shipped in the APK, keyed
    by parser. This is where a parser's media block goes.
  - **`regex.js`** (`DEFAULT_PATTERNS`) — generic, CDN-agnostic junk (telemetry
    beacons, init/numbered segment fragments). **Bundled-only**, shipped in the
    APK. (The old 6h remote fetch of `firedown-webrequests/regex-patterns.txt`
    was removed — the endpoint 404s and the bundled list is the single source of
    truth; ship pattern changes in the APK like any other capture logic.)
  Logic changes in `requests.js` ship in the APK.

  Note one thing that is **NOT** a URL rule in either file: **ts-in-png** (MPEG-TS
  video disguised as `image/png` — the series.ly / tiktokcdn ad-CDN trick). It's
  caught **content-side on the native probe**, not by URL: `TSInterceptor` strips
  the fake PNG header and `FFmpegMetaData.isValidMedia` drops it on
  `format == "mpegts"`. That's CDN- and extension-independent, so don't re-add a
  `.image`/`tiktokcdn` URL rule — it would just be cat-and-mouse. (Inspect bytes
  where they're already read: a captured segment is re-fetched+probed by the
  native pool anyway, so the format check there is free; the obfuscated-*manifest*
  body-sniff stays in JS `filterResponseData` because the classifier drops it and
  only the page ever reads it — see "Obfuscated manifests" below.)

### Telegram — public `t.me` parser (the logged-in web app is NOT a download surface)

There are TWO Telegram surfaces. **Public `t.me` pages** → `js/parsers/telegram.js`,
a normal parser: the post HTML carries a real, re-fetchable
`cdn-telegram.org`/`telesco.pe` `.mp4`, so it flows through the standard
capture→native-redownload pipeline (block-listed under `telegram`). **The
LOGGED-IN web app (`web.telegram.org/k` & `/a`)** is **NOT supported** — see the
"Why the web app isn't supported" note below (an in-page-download attempt was
built and removed).

The public `t.me` parser covers THREE layouts, all via `filterResponseData` (the
`<video src>` lives in different places in each — a HAR is the only reliable way
to see which):
- **Single post `t.me/<channel>/<id>`**: opening it serves a LANDING page
  (main_frame) with only `og:title`/`og:image` — NO `<video>`. The real
  `<video src>` is in the widget it embeds, `…?embed=1&mode=tme`, a **`sub_frame`**.
  So the listener reads `main_frame` AND `sub_frame`.
- **Channel feed `t.me/s/<channel>`** (no id, so `TELEGRAM_POST_RE` doesn't
  match it — `TELEGRAM_FEED_RE` + `listenerTelegramFeed` own it): the feed renders
  many posts and loads older batches via pagination XHRs
  (`POST t.me/s/<channel>?before=<id>`) whose body is a **JSON-encoded HTML
  string** (`unwrapFeedBody`). Split into per-post blocks at
  `tgme_widget_message_wrap` (`splitMessages`); each block's `data-post=
  "<channel>/<id>"` gives the per-clip origin, and `emitVideosFromHtml` runs the
  same per-post extraction on each (read `main_frame` + `xmlhttprequest`).
- The embed iframe / feed blocks carry **no `og:` tags**, so title/author/
  thumbnail come from the widget markup itself (`extractMessageText` →
  `tgme_widget_message_text`, `extractAuthor` → `tgme_widget_message_owner_name`,
  `collectThumbs` → `tgme_widget_message_video_thumb`), with `og:` as the
  fallback for the landing/single-`/s/` forms. The title is composed as
  **"`<Channel> — <post text first line>`"** (`titleFromPost`). Duration: the
  message `duration` field is **MILLISECONDS** (`parseClock` returns seconds, so
  the emit multiplies by 1000 — passing seconds showed a 0:32 clip as
  `00:00:00:03`).

**Why the web app isn't supported (and don't re-attempt it naively).** A web-app
media element's URL is a **ServiceWorker-virtual** `…/k/stream/<json>` (or
`/a/stream/…`) path the SW serves from **MTProto chunks decrypted in page JS**.
There is no plain native-reachable URL and no small key to hand native (unlike
Mega, where the page hands native a key and native fetches+decrypts a real CDN
URL) — a native OkHttp re-fetch can't route through the SW or speak MTProto. The
bytes exist **only inside the live page**. Consequences:

- **It can't go in the Captured sheet.** That sheet is capture-URL-now,
  native-download-later; a `/stream/` entry would be an always-broken download.
  The `/stream/` requests DO cross the wire (206 video/mp4), so `parser-blocklist.js`
  keeps a `'telegram-web'` rule to block them out of the sheet — purely to prevent
  broken captures (same "block a harmful capture" rationale as Mega, not dedup).
- **The only technically-possible path is downloading IN THE PAGE** (fetch the
  `/stream/` Range chunks in page world so they hit the SW + session, assemble a
  Blob, `<a download href="blob:…">` → caught by
  `GeckoComponents.onExternalResponse` → `GeckoStreamStrategy`). This was built
  (`js/telegram-web.js`, page-world engine via `window.wrappedJSObject.eval`, the
  `youtube/content.js` PoToken mechanism) and **REMOVED** at the maintainer's
  request: the only reliably-visible affordance on mobile Web-K was a **floating
  overlay button** (Telegram collapses its own action icons into the ⋮ overflow
  menu, hiding an injected button), and the overlay was unwanted UX. A native-
  looking menu-item inject was considered but is fragile (depends on the ⋮ menu
  DOM). If reviving: the technique (page-world `eval` + Range fetch + blob
  download) worked end-to-end; the blocker was purely the button's look/placement.
  Note the whole file is held as one in-memory Blob (large-video memory cost).

### Audio title/thumbnail enrichment — gating, MediaSession, embedded players

The generic catcher enriches a captured media URL's filename with page metadata
(title/description/thumbnail) by querying the content script
(`requests.js` `get-page-metadata`). For **standalone audio files**
(`urlIsStandaloneAudio` — `.mp3/.m4a/.aac/.wav/.weba/.opus/.flac/.oga/.mid`) this
is **GATED**, because most incidental sounds (notification dings, UI sfx,
background music) would otherwise inherit the page headline (the bug: series.ly's
`/audio/notification.mp3` came out named after a movie). Video, HLS/DASH
manifests and tokenized/extensionless URLs are **always** enriched
(`urlIsStandaloneAudio` is false for them).

- **The gate is `audioRole`**, computed in the content script for the captured
  URL: `'content'` (enrich) when the page **declares** it (a JSON-LD
  `AudioObject.contentUrl`/`og:audio` matching the URL), OR a `<audio>`/`<video>`
  DOM element is **bound** to it (hidden/controls-less custom players included —
  the test is the bound element's existence, NOT `controls`/visibility, because
  podcast players hide a native `<audio>` behind their own UI), OR the player
  published a **now-playing MediaSession title**. Otherwise `'unknown'` →
  suppressed. The genuinely-incidental case (`new Audio(url)` ding) binds no
  element and sets no MediaSession, so it stays suppressed — series.ly preserved.
- **Per-item metadata via `navigator.mediaSession.metadata`** — title + artist +
  artwork the player exposes for the OS/lock screen, the most precise per-clip
  source. `mediaSessionTitle` ranks just below a URL-matched declaration and
  ABOVE the page-level og/title in the consumer, so a page with several clips
  gives each its own title/thumbnail instead of one shared page card.
- **The responder runs in EVERY frame** (`content-script.js`, `all_frames`; the
  old `window===window.top` gate is gone) and the query is **frame-targeted**
  (`tabs.sendMessage(tabId, …, {frameId: data.frameId})`, `0` = top). So an
  **embedded player in a cross-origin iframe** (a "listen to this article" /
  podcast embed) answers for ITS OWN audio — its MediaSession, its `<audio>`
  binding, its og — which the top-frame responder could never see. `data.frameId`
  is the real webRequest frame on the wire path and `0` on the synthetic
  content-script path. Frame-targeting is what makes the all-frames responder
  safe (no cross-frame title contamination). Do **not** re-add a "subframe audio
  inherits the TOP page's og:title" heuristic — it stamped every clip on a page
  with one shared title (the thing this replaced).
- **News Over Audio** (`js/parsers/newsoveraudio.js`) is the dedicated case: a
  syndicated "listen to this article" widget embedded across publishers (IEEE
  Spectrum, …). Its audio + title live ONLY in `api.newsoveraudio.com/v1/player/
  article`'s `application/json` (which the catcher rejects) and play from a
  cross-origin iframe, so neither the wire, the DOM, nor MediaSession-without-a-
  parser fully cover it. `filterResponseData` on that JSON emits the signed
  full-length `.mp3` with `name`/`audioLength`(→ms)/publisher image (`skipProbe`);
  `audios.newsoveraudio.com/*.mp3` is block-listed (cardinal rule). The MediaSession
  path above covers the OTHER embed providers generically.
- **Spotify — EMBED parser (`js/parsers/spotify.js`).** What a Spotify widget
  plays — logged out, and even logged in, because embeds are preview-only by
  Spotify's design — is a ~30s, **non-DRM** MP3_96 clip per track from
  `p.scdn.co/mp3-preview/<hash>`. The full track/episode is auth-gated **DRM**
  (encrypted CDN) and never appears as a plain file; the preview is the only
  capturable audio. (This was previously DECIDED AGAINST via the
  `api-partner.spotify.com/pathfinder` GraphQL path — "not worth the maintenance
  treadmill for a 30s teaser". The **embed** `__NEXT_DATA__` path is cleaner —
  no GraphQL, no auth — and the "the generic catcher grabs a preview but can't
  NAME it" gap was real, so the decision was revisited.) Why a parser and not
  the catcher: the catcher DOES grab the preview on play (the wire sees the
  `p.scdn.co` 206), but the embed sets `navigator.mediaSession.metadata` only on
  `state_changed` (AFTER the MP3 is fetched) keyed by track URI, so the catcher's
  one-shot capture-time metadata query lands before the per-track title exists
  and falls back to the iframe's page-level `og:title` = the shared PLAYLIST
  name. The correct per-track mapping (`previewUrl → {title, artist, cover}`) is
  inlined pre-play in the embed HTML's `__NEXT_DATA__`
  (`props.pageProps.state.data.entity.trackList[]` — a `<script
  type="application/json">` the HTML parser keeps as raw text; Next.js
  unicode-escapes, no entities to decode). `listenerSpotifyEmbed`
  `filterResponseData`s the embed document (write-through, byte-exact — the
  Threads doc-filter pattern; the embed loads as `sub_frame`, also `main_frame`
  if opened directly) and emits **one titled audio entry per previewable track**
  (a "Top 100" playlist → 100 entries, capped at `MAX_SPOTIFY_TRACKS`=200; a
  single-track embed carries `audioPreview` on the entity itself). Emit is
  `type:"media"` with **NO duration and NO skipProbe** — the `trackList.duration`
  is the FULL track length (mislabels a 30s clip) and the preview URL is
  extensionless (so `processMediaSkipProbe` would fall back to the probe anyway),
  so let the native probe confirm audio + read the true ~30s. Each track emits
  under its own `open.spotify.com/<type>/<id>` origin (own entity); the
  repository dedups by URL so a re-read (refresh/SPA) can't duplicate.
  `p.scdn.co/mp3-preview` is block-listed (`parser-blocklist.js` `spotify`, the
  cardinal rule) so the catcher doesn't ALSO grab a bare untitled copy on play.
  The realistic capture path for a Spotify-published **podcast** in full is still
  its **YouTube** embed (existing `youtube@` parser) — Spotify only ever exposes
  the 30s clip here. Ceiling: the emitted entries are 30s previews, not the song.

### HLS-master sites — Java enumeration, no ffmpeg probe

niconico, Twitch, Kick and Bluesky emit `type:"hls-master"` from the parser
(`enumerateMasterNative` in `background.js`). (Bluesky reads the HLS master URL
straight from the AT-Proto app-view JSON — `app.bsky.embed.video#view.playlist`,
deduped per-video on that stable master URL, not the page origin, so a whole feed
of videos is captured rather than collapsing to one.

**Bluesky needs TWO capture paths, because the app-view JSON is often NOT on the
wire.** bsky.app is an SPA backed by an in-memory React-Query cache: navigating
*within* it (profile → post) or revisiting a cached view fires **no** xrpc
request, so the `app.bsky.feed.*` / `getPostThreadV2` response never crosses the
wire and the JSON reader (`listenerBskyApi`, a `filterResponseData` read of
`api.bsky.app`/`public.api.bsky.app`) has nothing to read. On its own that path
silently captures nothing for any cached/SPA view — and because the media is
parser-block-listed, the generic catcher can't grab it either, so the video is
lost entirely. The fix is a second listener, `listenerBskyMaster`, that captures
the HLS master (`video.bsky.app/watch/<did>/<cid>/playlist.m3u8`) **straight off
the wire** the moment the player fetches it (always happens on view/play),
read-only and gated to the master (not the per-quality children). It enriches the
capture from `bskyMetaCache` (playlist-URL → title/author/thumbnail, populated by
every xrpc response the JSON reader *did* see), falling back to a generic
"Bluesky video" title when the JSON was never observed. The JSON reader still
runs — it captures a whole feed/profile **pre-play** on a fresh load with rich
metadata; the wire-master listener is the reliability backbone that makes capture
independent of xrpc caching. Both emit `hls-master` deduped on the same master
URL, so they collapse to one entity when both fire. Don't drop the wire-master
listener "because the JSON reader already covers it" — it doesn't, for the SPA
cache case, which is the common one once a session is warm.) `GeckoInspectTask.processHlsMaster`
fetches the master with native OkHttp (`WebUtils.getString` — can set
Origin/Referer/Cookie, unlike a page `fetch()`), enumerates qualities with
`M3U8Parser.parseMaster` (text only — never opens a segment), and runs them
through `VariantProcessor` with `skipProbe=true`. So capture neither runs the
ffmpeg `metadatareader` probe nor decrypts anything — which for niconico avoids
burning the single-use AES key at capture time (see Niconico below). Routed via
`UrlType.HLS_MASTER`; still needs a `parser-blocklist.js` block rule like any parser; and
the capture is **deduped on the page origin** (a fresh signed master URL per
refresh would otherwise create duplicate entries) via `entity.setUid` in
`GeckoInspectTask`.

### Skip the capture probe when the parser already has the metadata

A parser that supplies url + resolution + duration does **not** need the ffmpeg
`metadatareader` probe — the only extra it yields is codecs, which are read
nowhere downstream (`getVideoCodec`/`getAudioCodec` live only inside
`VariantProcessor`). Don't add a probe just for codecs. How each shape skips:

- **Progressive variants** (Twitter/Instagram/Threads/Facebook/TikTok/Rumble-mp4):
  `sendVariants` auto-sets `skipProbe` when there's a `duration` and no per-variant
  `audioUrl`. Separate-audio (Bilibili DASH) is **excluded** — it still probes.
- **HLS masters** (Vimeo/Dailymotion/niconico/Twitch/Kick/Rumble-HLS): emit via
  `enumerateMasterNative` (`type:"hls-master"`), **never** `type:"media"` (which
  probes). The callee owns origin dedup — callers must NOT also `markSent`.
- **Audio** (Apple Podcasts): `type:"media"` + `skipProbe` (gated on duration) →
  `GeckoInspectTask.processMediaSkipProbe`; audio-only, falls back to the probe if
  the URL mime isn't recognisably audio (so an extensionless enclosure can't be
  misclassified).

`VariantProcessor`'s skipProbe branch sets the entity **type**: default **FILE**
(raw byte-exact `HttpDownloadStrategy`), **MEDIA** (ffmpeg) for a separate-audio
pair or a **declared** manifest — see "Manifest vs progressive — declared, never
URL-sniffed" under Downloading. Default is progressive so tokenized URLs (TikTok)
carry no `.mp4` and aren't needlessly remuxed.

### Post-download metadata refresh (the file is the ground truth)

`DownloadTask.refreshMetadataFromFile()` (called from `onRunComplete` on a
FINISHED download) probes the **finished local file** once (cheap, no network,
no keys — it never burns a single-use key the way a capture-time probe would)
and re-stamps the secondary metadatum the Downloads UI shows:

- **Duration** (audio/video) — re-probed **unconditionally**, and the probe
  result **overwrites** the capture-time value. Never trust the stored
  duration on a finished file: the user can hit Finish mid-download
  (`finishDownloadToExecutor` seals FINISHED + stops the runnable), leaving a
  file cut in half while the entity still claims the parser's full length.
  Only the bytes on disk are ground truth. When the probe can't read a
  duration at all (e.g. a progressive MP4 truncated before its moov atom),
  the stored value is **cleared** (`0`/`null`, the CompressTask convention)
  rather than left to lie. This also covers the original backfill case —
  HLS-master captures (Twitch/Kick/niconico) skip the capture probe, so a
  selected rendition finishes with no duration at all. (Known limit: a
  *faststart* MP4 truncated mid-download still reports the moov's full
  duration — players show the same, so that's accepted.)
- **Resolution** (image/SVG) — still **backfill-only** (probed when missing):
  covers an image saved via the browser long-press menu ("save image",
  `BrowserFragment` — a bare `DownloadRequest` that never went through the
  parser/`VariantProcessor`); an image can't be "shorter" than captured, a
  truncated one just fails to decode. Reuses
  `FFmpegMetaDataReader.getStreams()` so the `"WxH"` / SVG-encoded-size
  formatting matches the parser path exactly.
- **The refresh probe is de-duplicated and watchdog-bounded — keep both.** A
  strategy that ALREADY probed the finished output file hands the duration over
  via `DownloadCallback.onFileDurationProbed` (sealed-exempt, like
  `onFileSizeKnown`) and the refresh **skips its own probe** — SABR's
  inline-mux validation (`probePlayableDuration`) is the case: without the
  hand-off every SABR finish probed the same file twice within a second. For
  probes that still run, a **watchdog** (30 s, shared daemon scheduler in
  `DownloadTask`) calls `reader.stop()` — the non-blocking native AVIO
  interrupt, the same unwind a user Stop / tab-close uses on capture probes —
  with a lock ordering watchdog-stop before `release()` (the GeckoInspectTask
  rule: never `stop()` a freed reader). This is the sanctioned use of a timer
  per the timer-vs-count rule: an unbounded external wait (native/FUSE) with a
  correct fallback (keep/clear the stored duration). (History: the duplicate
  probe was first suspected of *wedging* when a user-finished download's row
  stuck on "Finishing…" forever — boundary logs later proved the pipeline
  completes in milliseconds and the row was stuck because the FINISHED write's
  **Room invalidation was lost**, see "Room invalidation" → the paging
  direct-invalidation belt. The de-dup and watchdog remain as efficiency +
  defense-in-depth.)

### HTML character references in scraped titles/descriptions

Captured metadata can carry HTML character references **verbatim** — most
notably from **JSON-LD**: the HTML parser treats a
`<script type="application/ld+json">` body as *raw text* and does **not** resolve
references inside it, so `JSON.parse` keeps `&#x41c;` (М) / `&amp;` literal and it
flows into the stored name/description (the info Downloads dialog then shows it
raw). `og:`/`twitter:` meta read via `getAttribute('content')` are already
decoded by the DOM, so this is a JSON-LD/JSON-source problem, not a meta-tag one.
Two layers, both best-effort with a **raw-text fallback** on error:

- **`webrequests/js/parsers/common.js` `decodeHtmlEntities`** — decimal `&#NNN;`, hex
  `&#xHHH;`, and the named refs that appear in titles/descriptions; applied to
  `name`/`description` at the emit choke points (`sendVariants`,
  `enumerateMasterNative`, `emitHlsMasterOrSingle`). Idempotent; unknown refs
  left untouched.
- **`InfoAdapter` `decodeHtml`** — display-layer catch-all via
  `HtmlCompat.fromHtml(FROM_HTML_MODE_LEGACY)` on the description field, so the
  generic catcher's JSON-LD descriptions (which never pass the parser emit) are
  covered too. `stripHtml` only strips tags/whitespace — it does **not** decode
  entities; don't conflate the two.

### YouTube / SABR

YouTube isn't HLS/DASH — it's Google's SABR (itag formats, a
`serverAbrStreamingUrl` + ustreamer config, and a PoToken minted by BotGuard via
`PoTokenGenerator`). The `youtube@` extension emits adaptive video+audio
itag-pair variants + the shared SABR data; routed via `UrlType.SABR`, downloaded
by `SabrStrategy`. `VariantProcessor` skips ffprobe for SABR variants (empty
media URLs) and trusts the JS codec/resolution/duration. Captions use the
separate `timedtext` path. **YouTube LIVE is the exception: HLS, not SABR** —
`isLive` → the `hlsManifestUrl` (n-param-transformed), emitted as
**`type:"hls-master"`, NOT `type:"media"`**. This matters: `type:"media"` →
`UrlType.MEDIA` runs the capture-time ffmpeg probe, which on a LIVE stream opens
the `rr*.googlevideo` segments — every one 403s (n-param/live edge) and the
demuxer reload loop spins until patch-0005 bails, all wasted on an item already
captured. `type:"hls-master"` → `processHlsMaster` fetches only the master text
from the lenient manifest host and `M3U8Parser` enumerates the qualities (no
segment opened, `skipProbe`), and the capture is **origin-deduped** (a re-signed
manifest URL per refresh won't dupe, unlike `MEDIA`'s url-hash uid). Same
no-probe master rule as Twitch/Kick/niconico. **The parser is only one of two
probe sources for live, though** — a live stream plays over HLS, so the player
also puts a clean `.m3u8` on the wire (`manifest.googlevideo.com/.../hls_variant/`
master + `.../hls_playlist/` child), which the **generic catcher** would grab and
probe as `type:"media"` (the cardinal-rule violation). So `googlevideo.com` is
host-block-listed in `parser-blocklist.js` (the `youtube` key) — YouTube is fully
parser-owned and googlevideo is its exclusive media CDN. VOD never tripped this
(SABR = `videoplayback` chunks, no `.m3u8`); only live did. Both the parser
`type:"hls-master"` emit AND the catcher block are needed to fully kill the live
probe.

### TikTok — filterResponseData (item_list feeds + document SSR)

TikTok capture is entirely on the wire/document side — **two** `filterResponseData`
producers in `background.js`, both feeding `handleTikTokItemList`. There is **no
TikTok content script** (it was deleted — `filterResponseData` covers capture, and
the Take-A-Break dismissal it used to do was dropped with it; see the throttle
note below).

1. **item_list XHRs — `filterResponseData` on `/api/*/item_list/`**: a passive
   `onBeforeRequest` listener reads the page's OWN response **byte-exact**
   (`filterResponseText` writes every chunk straight through — no refetch, so the
   single-use `msToken`/`X-Bogus` signing is untouched). Covers FYP, profile,
   hashtag/challenge, `/related/`, and `/newtab/`. Only viable because the
   geckoview **ServiceWorker-visibility patch (0006)** makes SW-synthesized
   responses fire `http-on-examine-response`, so `filterResponseData` now reaches
   the SW-served `/related/item_list/` feeds that were once untappable.
2. **Document SSR — `filterResponseData` on the `main_frame` HTML, DETAIL pages
   only**: `/@user/video/<id>` detail pages inline the video into the document's
   `__UNIVERSAL_DATA_FOR_REHYDRATION__` blob (under
   `webapp.video-detail`/`webapp.reflow.video.detail`) and fire **no** item_list
   XHR, so there's nothing else to tap. `extractTikTokSSRItems` pulls the blob from
   the HTML (`TIKTOK_REHYDRATION_RE`) and returns the single detail item to
   `handleTikTokItemList`. **Read the document RESPONSE, not the DOM** — raw bytes
   are immune to React stripping the rehydration `<script>` during hydration (the
   Threads "read the network response, not the DOM" lesson). If the document is
   SW-synthesized, 0006 is again what makes it tappable. The listener is gated to
   `TIKTOK_DETAIL_PATH_RE` — it does **not** run on `/foryou`, because the feed
   document's blob holds **no** video (**proven on-device**: only
   `webapp.app-context`/`i18n`/`biz`/`seo` scopes); the FYP feed is fully
   client-rendered. Don't re-add a `/foryou` branch — it would just read ~165 KB
   per load to find nothing.

**The first `/foryou` video — generic catcher, NOT the parser (deliberate
cardinal-rule exception).** TikTok renders the first FYP video from its **own
client-side cache**, so that video's metadata **never crosses the wire** — it's in
neither the document SSR (feed blob has no video, above) nor any item_list XHR
(verified from a HAR: the first-played storage id was in *neither* recommend nor
preload, only in the media fetch). So the parser structurally **cannot** capture
it. The decision (maintainer's call) is to let the **generic catcher**
(`downloader@`) grab it: TikTok's `webapp-prime` media host is **deliberately NOT
block-listed** (it has no key in `parser-blocklist.js`, and no media rule in
`regex.js` either) — the one parser-owned site without a media block, on
purpose. **Do not re-add a `webapp-prime`/`tiktokcdn` block rule** — it would
re-break first-video capture. Trade-off accepted: because the block is gone, the
generic catcher can also capture the *swipe* videos' media (which the parser
already has rich), so duplicates are possible where the played URL differs from
the parser's emitted variant URL (`BrowserDownloadRepository.isPresent` only
collapses exact/trivially-different URLs). A storage-id-gated media fallback that
captured *only* the uncaptured video was built and then **removed** at the
maintainer's request — don't reintroduce it.

**History — the page-world inject was retired.** TikTok capture *used* to depend
on a page-world inject (`tiktok-inject.js`, a moz-extension WAR hooking
`window.fetch`/`XMLHttpRequest`) because three things blocked `filterResponseData`:
refetch tripping `msToken`/`X-Bogus` (N/A to a passive read), SW-served feeds
being untappable (fixed by 0006), and a fear that the stream perturbation tripped
the "Something went wrong" overlay. That last one **did not reproduce on-device**
with byte-exact write-through, so the inject + its postMessage bridge were
removed. The detail-page SSR read *also* started in the content script
(`captureVideoDetailSSR`, DOM) but moved to the `main_frame` document filter (2.).
Don't reintroduce the inject, and don't move SSR reading back to the DOM.

- **The item_list PAT must allow sub-segments.** A hashtag page fires
  `/api/challenge/item_list/?…` AND `/api/challenge/item_list/newtab/?…`; the
  regex is `\/api\/[a-z_]+\/item_list(?:\/[a-z_]+)*\/?\?` so the `/newtab/` feed
  (≈half the videos) isn't dropped.
- **Tag/challenge pages SSR no video data** (the rehydration blob is only
  app/i18n/seo context) — the feed is client-rendered via the item_list XHRs, so
  the item_list listener is the only source there. The `main_frame` SSR listener
  runs on the detail (`/@user/video/<id>`) paths only.
- **The anti-bot throttle is the real gotcha.** TikTok withholds the item_list
  XHRs entirely (the `Take_A_Break` reminder shows, only `/api/preload/` fires)
  unless the page's **fingerprint stays unstable**. Globally that's
  `privacy.resistFingerprinting` — a user toggle that ships OFF and degrades
  every site. The previous mitigation was a per-site FPP override
  (`GeckoRuntimeHelper.applyTikTokFingerprintingOverride`) that scoped
  `+CanvasRandomization` to first-party `tiktok.com` via
  `privacy.fingerprintingProtection.granularOverrides` so the canvas readback
  noised per session and the fingerprint never stabilised (the read still
  succeeded — `+AllTargets` was the wrong knob: it enables the canvas-extraction
  *blocking* targets, breaking `webmssdk`'s read → "Something went wrong").
  **That override was REMOVED at the maintainer's request** (commit on
  `claude/happy-fermi-VY4tV`). Consequence to be aware of: with no per-site
  randomization, the throttle can re-engage and the item_list feeds may stop
  firing on some loads — which starves **both** capture sources (the inject and
  the new `filterResponseData` path), since neither can read a request the page
  never makes. The ServiceWorker-visibility patch (0006) does **not** help here
  — the throttle is server-side, not a response-visibility problem. If TikTok
  capture regresses to only `/api/preload/`, this removal is the first suspect;
  the fix is a *randomizing* (never blocking) FPP vector scoped to tiktok.com,
  not global RFP and not `+AllTargets`. **The `Take_A_Break` overlay is also no
  longer auto-dismissed** — the content script that did it was deleted (capture is
  all `filterResponseData` now). The overlay visually suppresses `/api/*` until
  closed, so if it mounts, feeds pause until the user dismisses it manually. If
  this becomes a problem, prefer a DOM-only dismissal — a content script that does
  *only* that, not capture — over reviving any page-world inject.

### Capture dedup

Three layers prevent duplicate entries for one video:
- **regex block** (cardinal rule) — keeps the generic catcher off a parser's media;
- **JS `sentOrigins`** (`js/parsers/common.js`) — per **(tabId, page origin)**,
  30s TTL. Tab-scoped on purpose: an origin-only key suppressed the same video
  opened in a SECOND tab for the whole TTL (the repository dedups per tab, so
  the global JS key was too coarse). A mixed-attribution guard in
  `alreadySent` collapses emits whose tab resolution diverged (one real tabId,
  one `-1` fallback — e.g. Bluesky's two hls-master listeners) without
  re-suppressing genuine multi-tab captures;
- **`BrowserDownloadRepository.isPresent`** — per `tabId`, then `uid` /
  exact-or-trivially-different URL / image perceptual hash. `uid` is
  `url.hashCode()`, except **HLS_MASTER** keys it on the page origin (signed
  master URLs rotate per refresh). First capture of a URL wins; a later capture of
  the same URL is dropped (the `GeckoInspectTask.contains()` pre-check skips it
  before it even probes). So when a parser/bridge and the generic catcher both see
  one URL, whichever lands first is the entry — no metadata merge.

### Capture "scanning" indicator

`PriorityTaskThreadPoolExecutor` exposes an in-flight task count
(`getInFlight()` — incremented at submit, decremented in the run `finally` so
aborts count too). `BrowserOptionFragment` observes it via
`BrowserDownloadViewModel.getInflight()` and shows a small brand-orange spinner
next to the grid/list toggle whenever busy — debounced (show-now + ~500 ms
hide-linger so it doesn't strobe; decoupled from the filter chips, so filtering
to an empty type doesn't hide it). The empty list uses the LCEE loading spinner.
Fills the gap where a slow capture (e.g. an HLS-master fetch) makes the sheet
look empty for seconds.

### Inspect task scheduling (`PriorityTaskThreadPoolExecutor`)

Captures run on a small priority pool. Each task carries a **base** priority
(urlType-derived — `HIGH`=1 for media/SABR/HLS_MASTER, `NORMAL`=10 for
image/SVG, `LOW`=100 for everything generic) plus its `tabId`; the executor
demotes it to `PRIORITY_BACKGROUND` (1000) unless its tab is the **current** one
(`-1`/unknown = treat as foreground). The demotion floor is **below every base
priority on purpose**: generic captures are already `LOW`, so flooring the
backlog at `LOW` too would let a tab you just switched into (whose own captures
are also `LOW`) merely *tie* with the previous tab's 200-item backlog — its new
captures would still wait behind them. `PRIORITY_BACKGROUND` is a level no
foreground task can hold, so the current tab's work always runs first regardless
of base. (Background tasks all share that one floor — relative order among them
is intentionally flat; what matters is foreground-beats-background.) Two mutators
keep the backlog relevant while browsing:

- **`setCurrentTab(tabId)`** (from `GeckoRuntimeHelper` onActivated/onUpdated)
  re-prioritizes the **whole pending queue** — drain → recompute each task's
  effective priority → re-offer (a `PriorityBlockingQueue` can't re-heapify in
  place when the comparator's external input changes) — so a tab you switch to
  jumps ahead of a heavy background tab's backlog.
- **`cancelTab(tabId)`** (onRemoved, beside `trimTabs`) drops a closed tab's
  queued tasks (decrementing in-flight per removed task) **and** interrupts its
  *running* tasks via `GeckoInspectTask.cancel()` (see "Bounding a wedged capture
  probe" below) — those still complete, so their run-finally clears the count.

Both are `synchronized` on the **same monitor** as `executeWaitingTask`, so a
switch and a close can't interleave (consistent queue + correct in-flight count
in either order) and the drain window can't be observed by a poll. `execute()`'s
offer stays lock-free (the queue is thread-safe; a task offered mid-drain just
coexists with the re-offered ones). *Re-prioritisation* only affects queued
tasks (a running task isn't reordered), but `cancelTab` **does** cooperatively
interrupt running ones. `executeWaitingTask` drains **all** free slots per call
(bounded loop, capped by `corePoolSize`) and rolls back the counters if a submit
is ever rejected, so a freed slot can't be lost.

Pool sizing: `NETWORK_CORE_POOL_SIZE = max(1, cores/2)` drives **both** the
thread pool and the submit gate, and `executeWaitingTask` submits while
`poolAvailable > 0` — i.e. **every** thread is usable. Don't reintroduce the old
`> 1` gate (it reserved one thread for a cancellation task that doesn't exist —
`cancelTab` runs synchronously on the caller): it left a thread permanently idle,
**halved** throughput on 4-core devices, and on a 2-core device (pool size 1)
stalled the pool entirely (`poolAvailable` never exceeded 1). The `max(1, …)`
floor also avoids `newFixedThreadPool(0)` throwing on a single-core device.
`cancelTab` deliberately does **not** reset `currentTabId` — closing the
foreground tab leaves it dangling only until the next `onActivated →
setCurrentTab` (always fired, bar closing the last tab, after which no captures
flow); resetting to `-1` would treat every task as foreground and surge a
background tab's backlog back to base priority.

### Bounding a wedged capture probe (live HLS/DASH)

A **live** HLS/DASH whose every segment fails to open (e.g. all 403) spins
ffmpeg's reload loop forever: the live edge keeps advancing so vanilla's
`max_reload`/`m3u8_hold_counters` never trip, and `find_stream_info` never
returns. Two independent layers bound it:

- **Demuxer** (firedown-ffmpeg, hls.c patch `0005`): bail after N *consecutive*
  segment-open failures (reset on any success) → propagate the error. Self-bounds
  the probe **and** the downloader, regardless of tab state.
- **App** (this executor): closing the tab → `cancelTab` →
  `GeckoInspectTask.cancel()` → `FFmpegMetaDataReader.stop()`, which sets the
  native AVIO interrupt flag (same path as a user Stop) so the probe unwinds at
  once. `stop()` only sets a flag (non-blocking, safe under the monitor);
  `release()` (closes contexts, can block) runs on the worker, never under the
  monitor. A per-task lock orders unregister-before-release so `cancel()` can't
  `stop()` a freed reader.

**Timer vs. failure-count — pick by what you're bounding.** A wall-clock timeout
was *rejected* for the probe loop: it can't tell "slow" from "broken", and the
real failure (a run of open failures) is **countable** — so count it. A timer is
the *right* tool only for an inherently-unbounded **external wait that has a
correct fallback** and no better signal. (Historical example: when the parser
was a separate extension, `handlePageStateHls` time-boxed a cross-extension
`get-ambient-headers` lookup with a 200 ms `Promise.race`, falling back to the
bridge's reconstructed `navigator` headers. The merge removed the external
dependency — the handler now calls `getAmbientHeaders()` (imported from
`requests.js`) synchronously — so the timer went with it, which is exactly the
point: the timer existed only because the dependency was external.) Rule: count a
detectable failure condition; time-box only a best-effort external dependency
with a fallback.

(YouTube **live** is HLS, not SABR — `youtube@` routes `isLive` → `hlsManifestUrl`;
SABR is VOD-only. It's emitted as `type:"hls-master"` so **capture** only fetches
the master text and enumerates — it does NOT probe the segments, so the
403-on-every-segment loop can no longer happen at capture time. A live
403-on-every-segment loop *at DOWNLOAD* is the HLS n-param transform not taking
effect, not a transport bug.)

## Debugging "video not captured" — do this, in order

This section exists because a Threads bug took ~8 rounds that should have taken
1. The failure mode was **theorizing about the transport while never running
   the actual extraction code against the bytes we already had.**

1. **Confirm it's a debug build.** All parser logs are gated on
   `BuildConfig.DEBUG`. The extension fetches it at boot via
   `get-debug-flag` (`background.js` top; answered in
   `GeckoRuntimeHelper` ~line 322). Release builds log nothing.

2. **Read the logs by category.** `adb logcat -s GeckoConsole:*` then grep the
   prefix: `TWITTER`, `INSTAGRAM`, `THREADS`, `THREADS-CS`, `FB-*`, `IG-*`,
   `RUMBLE`, `TWITCH`, `KICK`, `VIMEO`, `DAILYMOTION`, `TIKTOK`, `NOA`,
   `SPOTIFY`, `PAGE-STATE`, `VARIANTS`,
   `DEDUP`, `NATIVE`. The generic catcher logs under `[req]` (gated on its own
   `DEBUG`). Java-side variant probing is `VariantProcessor`.

3. **Get a HAR of the failing case** (the user can export one). Find the
   request that actually carries the video/metadata — search response bodies
   for `video_versions`, `playable_url`, `.mp4`, `.m3u8`.

4. **THE KEY STEP: reproduce the parser's *exact* algorithm against the HAR
   bytes — caps and all — before changing anything.** When output is empty but
   the input is present, the bug is almost always in extraction, not transport.
   - A throwaway verification script that uses an *uncapped* or *simplified*
     walk will "find" the item and **falsely exonerate** the extractor. Mirror
     the real code: same regex, same depth cap, same node budget, same field
     checks. (The Threads bug: items sat at JSON **depth 16–22**; the walk
     capped at `depth > 14` and bailed two levels short. The doc filter had
     been delivering the full HTML correctly for many rounds.)

5. **Only after** the extractor is ruled out, look at transport (did the
   listener fire? `filterResponseData` available? right `types`/url patterns?).

### Don'ts (each one cost a round on Threads)

- Don't assume `filterResponseData` on `main_frame` doesn't work in GeckoView —
  it does. (The first attempt only *looked* dead because of caching; see below.)
- Don't try to read inline page data (`<script data-sjs>` etc.) from a content
  script's DOM. Meta's bootstrap (`ServerJSPayloadListener.process`) **consumes
  those scripts the instant they parse**; by the time a content-script
  observer's microtask runs, the big blob is already empty/replaced. Read the
  **network response** (`filterResponseData`) instead.
- Don't try a content-script `fetch()` of the page to re-read it — JS can't set
  `Sec-Fetch-Dest: document` (forbidden header), so the server returns an
  emptied shell.
- Don't reach for a "logged-in vs logged-out" explanation without evidence; it
  was a red herring.

#### Threads has NO content script — two `filterResponseData` paths only

Threads capture lives entirely in `background.js`: `listenerThreadsPage`
(`main_frame` doc filter — reads the `<script data-sjs>` Relay blobs from the
**raw network response**, logged-in) + `listenerThreadsApi` (the GraphQL/`api/v1`
XHRs, logged-out / SPA). **No content script, no GeckoView patch** — stock
`filterResponseData` on the main_frame is all it takes. The old
`threads-content.js` (a `document_start` MutationObserver that snapshotted the
same `data-sjs` from the DOM) was **removed**: it only *duplicated* the doc
filter on initial load and captured nothing on SPA nav (it just logged). Its
header claim that "filterResponseData on main_frame never fired" was the caching
artifact above, not the truth. **Don't reintroduce a Threads content script** to
"fix" a missed capture — fix the doc/API filter or the media-walk depth cap
instead. The one case a content script alone could see (a *cached* main_frame
document, where the filter may not re-fire but the DOM parse still inserts the
scripts) is marginal for dynamic post pages and irrelevant to the in-app
browser's usual logged-out state.

## WebExtension loading & versioning (GeckoView gotcha)

`registerBuiltIn` → `WebExtensionController.ensureBuiltIn(uri, id)` caches the
extension **keyed by the manifest `version`**. If you change ANY of an
extension's files (JS, content scripts, the manifest itself) but **don't bump
`webrequests/manifest.json`'s `version`**, an in-place app update
(`adb install -r`) keeps the old registration and your change silently doesn't
load — a *removed* content script keeps running, an added one never starts. To
force a re-register: **bump the version**, or do a clean **uninstall + install**
(which wipes the registration so any version reloads). A REMOVED extension is
the harder case: dropping its `registerBuiltIn` call does NOT remove the
persisted registration — it fails to boot every launch with
`NS_ERROR_FILE_NOT_FOUND` until explicitly uninstalled
(`uninstallOrphanedExtension` in `GeckoRuntimeHelper` does this for the merged-
away `parser@`). Symptom of this trap: a
brand-new listener/content-script produces *no logs at all* (or a deleted one
still does).

## After changing a parser

- **Run `node scripts/webrequests-smoke.mjs`** — imports the whole background
  module graph under a stubbed `browser` (catches a broken import/export, a
  dropped listener registration, a duplicate router kind, syntax errors —
  ES modules need `node --input-type=module --check`, plain `node --check`
  rejects `import`).
- Re-run your HAR simulation with the **final** code (caps included) and confirm
  it finds the expected item(s) with `user`, `caption`, and `video_versions` —
  and since the split, **import the real walker from the site's module** in the
  simulation instead of copy-pasting it (a simplified copy is how the Threads
  depth-cap bug survived 8 rounds).
- **Bump `webrequests/manifest.json` `version`** if you changed any extension
  file (the `ensureBuiltIn` version-cache trap — see "WebExtension loading &
  versioning" below).
- **Confirm the `parser-blocklist.js` block rule exists** (the per-parser
  `PARSER_BLOCKLIST` entry) for the media this parser emits (see the cardinal
  rule above) — this is the #1 thing that gets forgotten and causes duplicate
  entries.
- Prefer one capture mechanism per site. Multiple (doc filter + API filter +
  content script) can all fire and, if origins differ, produce duplicate
  entries; origin-dedup (`sendVariants` `alreadySent`) only collapses identical
  origins.

## Logging discipline

**Every log statement — Java and JavaScript — must be gated behind the debug
flag. No unconditional logging ships.**

- **JavaScript (extensions):** never call `console.log`/`console.warn` directly.
  Use the extension's `log(...)` helper, which early-returns unless `DEBUG` is
  true. `DEBUG` is resolved at boot from the native `get-debug-flag` message,
  which returns `BuildConfig.DEBUG` (`GeckoRuntimeHelper`). So a release build
  logs nothing even though the JS contains `log(...)` calls. New parsers must
  route all logging through `log(category, message, data?)` with a short
  uppercase category (e.g. `RUMBLE`).
- **Java:** wrap log calls in `if (BuildConfig.DEBUG) { … }` (or an equivalent
  guarded helper). Do not leave bare `Log.d/​i/​w/​e` on hot paths in release.
- **Native (`app/src/main/cpp/`):** the `LOGI/LOGE/LOGW(level, …)` macros expand
  to `if (level <= LOG_LEVEL) { __android_log_print(…); }`, so the variadic args
  are evaluated *only* when the level passes — an inline `LOGE(1, "…%s", av_err2str(ret))`
  costs nothing at `LOG_LEVEL 0`. But a **dedicated logging helper FUNCTION** does
  NOT get that for free: calling it always marshals its args and runs its body
  (an internal `if (LOG_LEVEL < 1) return;` still paid the call + arg eval every
  time). On a per-tick/per-packet hot path, gate the **call site and the
  definition** with `#if LOG_LEVEL >= N` (as `downloader_log_progress` and the
  `av_dump_format` calls do) so it vanishes entirely at the default level — don't
  rely on a runtime early-return inside an unconditionally-called helper.
- Rationale: this is a privacy/no-telemetry app — logs can contain URLs, titles,
  cookies-adjacent data. Release builds must be silent.
- **Never log user-controlled text whole — truncate it.** The URL bar can hold
  an arbitrarily large paste (a user once pasted a multi-hundred-KB logcat dump);
  `AutoCompleteEditText` used to log the full before/after text on **every**
  text/focus event, multiplying the blob through logcat and churning the main
  thread. Its logs now go through `logPreview(...)` (128-char cap + length
  suffix). Apply the same cap to any new log of field content, page titles,
  clipboard text, etc.

## Room invalidation — persistent tracking mode (do NOT revert)

Every `Room.databaseBuilder` in `DatabaseModule` sets
**`setInMemoryTrackingMode(false)`**. Reason: Room's InvalidationTracker (what
makes LiveData/Paging re-query after a write) defaults to a per-connection
**TEMP** table (`room_table_modification_log`) + TEMP triggers — and the
framework can recycle the native SQLite connection underneath Room (Samsung
OneUI enables the idle-connection timeout system-wide; reproduced on an
SM-A536B/SDK 36 after the app sat idle). The recycled connection comes back
with no temp objects, and Room 2.7+ never re-creates them: the refresh path
**swallows** the `SQLiteException` and reports "nothing invalidated". Symptom:
`(1) no such table: room_table_modification_log` in logcat and a UI that loads
but never refreshes after writes — a deleted download that won't leave the
list (first delete removes the row, list stays; retries log
`deleteDownloads: rowsRemoved=0` on the ghost). On Room 2.6 the same root
cause **crashed** instead (`syncTriggers` on the `addObserver` path). Two
dead-ends, both tried: bumping Room (2.7.2 and 2.8.4 contain **no** fix —
release-notes-verified; an earlier commit message claiming 2.7.2 fixed it was
wrong) and treating the `rowsRemoved=0` delete log as a uid/Parcel bug (the
uid was fine; the row was already gone from a previous tap). Persistent mode
(`@ExperimentalRoomApi`, added in 2.7.0-alpha12 for exactly this —
b/185414040) puts the tracking table and triggers in the database file, immune
to connection recycling. Cost is a trigger write through the journal —
negligible here. Related: raw writes via `getOpenHelper()` (the backup
mirror's import) bypass Room's auto-refresh even with working tracking; the
persistent triggers still mark the change, which the next Room-managed write
flushes to observers.

**The Downloads paging list ALSO has a direct-invalidation belt — keep it.**
Persistent tracking mode did not close every invalidation hole: on-device
(Room 2.8.4, Samsung) the tracker was observed **dropping the LAST write of a
rapid burst** — a user-finished SABR download writes PROGRESS+PROCESSING then
FINISHED ~100 ms apart, the FINISHED row lands in the DB, but no new paging
generation ever fires, so the row sits on an indeterminate "Finishing…"
until the screen is re-entered (intermittent; diagnosed with the checkpoint
logs: `DownloadTask` "final write queued" present, `DownloadsViewModel`
"downloads: new paging generation" absent — that pair is the tell for this
class). The belt: `DownloadsViewModel.createPagingSource` registers every
source it creates via `DownloadDataRepository.registerActivePagingSource`,
and every repository write lambda on the DiskIO lane ends with
`invalidateActivePagingSources()` — a **direct `PagingSource.invalidate()`**
(public, thread-safe, idempotent; a no-op when Room's tracker already
invalidated first), so a fresh generation is guaranteed per write regardless
of tracker races. The registry is weak + cleared on each poke (an invalidated
source is dead; its replacement re-registers on creation). Known limit: plain
LiveData queries (the aggregates/section headers) have no invalidate() and
still depend on the tracker — a dropped notification there self-heals on the
next write; only the paging list has the belt.

**Persistent mode is a ONE-WAY door — never run a pre-fix binary on a
post-fix database.** The fixed build writes persistent triggers
(`room_table_modification_trigger_download_*`) into the DB file. A PRE-fix
binary opening that file drops the persistent tracking table at open
(`configureConnection` runs an unqualified `DROP TABLE IF EXISTS` and
recreates only a TEMP one) but never drops the persistent triggers — leaving
main-schema triggers referencing a missing main-schema table, which makes
**every write to `download` crash at statement compile**:
`no such table: main.room_table_modification_log` (the **`main.` prefix is
the tell** — the no-prefix variant of the error is the original
recycled-TEMP-table symptom instead). Observed in the wild on an SM-A426B
after dev builds with the same versionCode (1177) were installed over each
other in both orders. Installing the fixed build heals the DB automatically
at first open; the only other recovery is clearing app data. Consequences:
**bump `versionCode` when releasing the persistent-mode build** so Android's
downgrade protection makes the broken ordering impossible in the field, and
if `setInMemoryTrackingMode(false)` is ever reverted, the reverting build
must explicitly `DROP TRIGGER IF EXISTS` each
`room_table_modification_trigger_*` in the main schema or it recreates this
exact crash for itself.

## Auto Backup — the vault NEVER leaves the device

Downloads live in the **public** `Download/Firedown` (survive uninstall); the
download DB is app-private (wiped on uninstall). To survive a
reinstall-with-restore, the PUBLIC download list is backed up via a
**sanitized mirror**, never the database file: `download-db` holds the
safe-vault rows too (`file_safe = 1` — names/origins/paths of vaulted items),
and Auto Backup is file-granular, so backing up the DB would ship vault
metadata to the cloud. `DownloadBackupMirror` re-writes
`filesDir/backup/downloads-mirror.db` (non-safe + FINISHED rows only, via
`ATTACH … CREATE TABLE AS SELECT`) every time the app backgrounds
(`ApplicationLifeCycleHandler.onTrimMemory`). **Restore is PROMPT-FIRST — there
is NO boot-time auto-import at all** (there is no `restoreIfPending` /
`App.onCreate` import path anymore, and no reinstall-detection sentinel). On
Android 11+ the restored public files are foreign-owned (see the scoped-storage
caveat below), so a silent import would drop the user into a list of unopenable,
thumbnail-less entries. Instead the user restores DELIBERATELY from the
**Downloads empty-state button** or **Settings**, and that one flow
(`restoreFromTree`) takes the SAF folder grant AND imports in a single step, so
the files are openable the moment they appear. `file_safe` is forced to 0 on
import — a tampered mirror can't inject vault entries. (The
`downloads-mirror.db` file is still written + Auto-Backed-up, but nothing
imports it at boot; the SAF `.fdbk` flow reconstructs the same list with the
grant, so the Google-transport DB import path was removed to kill the silent
broken-list state.)

The backup surface is an **include-list** in BOTH rule files —
`backup_rules.xml` (API ≤ 30) and `data_extraction.xml` (API 31+, cloud +
device-transfer), kept in lockstep: shared prefs (minus `device.xml`,
`secret_shared_prefs.xml`, `backup_local.xml`) + the mirror file. **Nothing
else** — not `domain="database"`, not `filesDir` (the `files/safe/` vault
content). History: `data_extraction.xml` used to be **empty**, which on API
31+ means *no rules* = **default full backup of everything** (vault DB rows,
vault files, secret prefs) while the legacy exclusions were silently ignored
— if you touch these files, never leave the 31+ rules empty, and mirror any
change across both files. Scoped-storage caveat: restored entries point at
the surviving public files, but on Android 11+ a reinstalled app doesn't OWN
them (MediaStore attribution died with the uninstall), so a DIRECT
`File`/`FileProvider` open `EACCES`-es (the symptom: restored rows with no
thumbnail that play nothing). This is bridged by **`RestoredFileAccess`**: it
maps a download's absolute path to a `DocumentsContract` child URI under the
**persisted SAF tree grant** the restore flow already took
(`DownloadBackupMirror.rememberRestoreTree`/`getRestoreTree`), and every read
path tries the owned file FIRST, then that `content://` grant —
`openableUri` (a `file://` for owned, `content://` for foreign) and
`openReadOnly` (a `ParcelFileDescriptor`). Wired into the Glide `DownloadEntity`
loaders (PFD + Uri) and `GlideHelper`/`ImageViewerFragment`'s raw-path loads
(list/grid + viewer thumbnails), `MediaViewerFragment` (ExoPlayer via
`DefaultDataSource` + the `MediaMetadataRetriever` aspect probe),
`BaseFocusFragment.viewerUri` (text viewer / open-with) and `PlayerActivity`
share. Scoped to non-vault entries on the player path so encrypted/safe
playback is byte-identical. No new permission — it reuses the grant; a file on
removable storage (not primary) or with no grant still falls back to "no
access" gracefully.

**Transport-free recovery — the encrypted PUBLIC mirror.** Auto Backup needs
a backup transport (Google's = Play Services on stock devices; Seedvault on
de-Googled ROMs); the app must not *depend* on either. So `writeMirror` also
writes an **AES-256-GCM-encrypted** copy of the same sanitized mirror to the
public folder (`Download/Firedown/backup/downloads-mirror.fdbk`, format
`FDBK1 | 12-byte IV | ciphertext`), which survives uninstall like the media
files. The key is derived from **`ANDROID_ID` (SSAID)** — scoped per (app
signing key, device, user) since Android 8, so it survives a same-signed
reinstall, while every other app sees a *different* SSAID and cannot decrypt
the file; nothing secret is embedded in the APK. By design this makes the
file same-device-only (cross-device migration is the transport's job; a
factory reset orphans old mirrors — `decryptPublicMirror` just rejects what
GCM can't authenticate). After a reinstall the file is foreign-owned
(invisible to File API), so the planned restore path is a one-tap **SAF
folder grant** (`ACTION_OPEN_DOCUMENT_TREE` on `Download/Firedown`) →
`decryptPublicMirror` → `importMirrorDatabase` (column-intersection importer;
`file_safe` forced 0). The write side
handles the name collision a reinstall causes (foreign-owned old file at the
fixed name → fall back to a timestamped `.fdbk`; restore scans for all of
them and takes the newest it can decrypt). The SAF restore UI has **two
doors running one flow**: the Downloads **empty state** (`DownloadFragment`
— the LCEE empty view's built-in button, shown only when unfiltered) and
**Settings → Downloads → "Restore previous downloads"**
(`SettingsFragment`, `SETTINGS_RESTORE_DOWNLOADS`) — the latter exists
because a user with any downloads (or any filter active) can never see the
empty state. Flow: confirm dialog → `OpenDocumentTree` pre-pointed at
`Download/Firedown` (the scan accepts the `backup/` subfolder too) →
persist the grant (kept in `backup_local.xml` — `RestoredFileAccess` reads it
back as the content-URI access path for foreign-owned files, see the
scoped-storage caveat above) → `restoreFromTree` → snackbar with the count /
"no backup" / "different device". `importMirrorDatabase` dedups by
`file_path` against the live table, so the flow is idempotent — tapping
twice or restoring on a non-empty list never duplicates rows. Strings are
translated across the same 16 locales as the JIT toggle.

**Restore progress/result live in the ViewModel, NOT the fragment.**
`DownloadsViewModel.runRestore(...)` runs the SAF scan/decrypt/import on its own
executor and surfaces state through `getRestoreInFlight()` (LiveData<Boolean>,
drives the bottom indeterminate progress bar) + `getRestoreResult()` (a
single-shot `RestoreEvent`, drives the refresh + result snackbar). This is
load-bearing for the "leave Downloads and come back mid-restore" case: the work
is decoupled from the view, so a recreated view **replays** the in-flight value
(the bar re-appears) and **consumes** the pending result event (refresh + snackbar
fire on whatever view is current, even if the restore finished while the view was
gone). The previous design ran it on a disk executor and posted completion to
`getView()` — a null view (fragment left) silently dropped the refresh + snackbar
AND lost the progress bar. Two subtleties to keep: the in-flight observer shows on
`true` but does **not** hide on `false` (the initial/false replay would stomp a
legitimately-running task progress bar — the hide is done in the result observer,
which only fires on an actual restore completion); and `RestoreEvent.consume()`
yields the code exactly once (so a config change / re-entry after the snackbar
doesn't re-fire it, but a result posted with no observer attached is still
delivered to the first one that comes back).

Those **two doors are the whole story** — the empty-state button is presented
proactively on the (empty) post-reinstall list, so no extra prompt is needed.
**Don't add a second concurrent affordance**: an auto-popping restore *dialog*
plus the empty-state button showing the *same* message at once is redundant
UX. One affordance; the dialog appears only on an explicit tap of the button
(or the Settings row) to pick the folder.

**History — removed, don't reintroduce.** Earlier iterations auto-imported the
mirror at boot (`restoreIfPending` + a `detectReinstall` sentinel pair) and then
tried to *recover* the resulting broken (unopenable, thumbnail-less) list — first
with a `RestoreBannerAdapter` banner (dismissible "restore", then a persistent
"grant access" variant), then with a one-shot proactive dialog
(`KEY_RESTORE_PROMPT_PENDING` / `consumeRestorePrompt`). All of it is gone: the
banner adapter + layout, the reinstall-detection sentinels, the boot-time import,
and those prefs. The lesson that stuck: **never enter the broken state** (no
boot-time auto-import) rather than recover from it, and **one restore
affordance**, not two.

**Downloads chip-rail gotcha: there is NO "All" chip.** Unfiltered means no
chip is checked — `ChipGroup.getCheckedChipId()` returns `View.NO_ID`.
`R.id.chip_all` is only the ViewModel's no-filter *sentinel* (set in
`onCheckedChanged` when the checked list is empty); it is never a real
checked id. Any UI gated "only when unfiltered" must test `NO_ID` — gating
on `chip_all` silently never fires (this bug shipped twice: the empty-state
restore button, and the older empty-text line that showed "nothing of this
type" on an unfiltered empty list). Chip ORDER is frequency-first within the
rail's visible window — `Video · Audio · Images · GIF · Subtitle · Documents
· APK · Archives` (Images/GIF deliberately ahead of the rarely-filtered
Subtitle, which leads the long-tail half) — kept in lockstep across the
Downloads rail (`fragment_download_list_options.xml`) and the Captured sheet
(`fragment_dialog_browser_options_list_options.xml`). Reordering is safe
(all chip handling is id-keyed, never positional) but do it in both files.

**The missing-file sweep (`MediaListenerWorker`, runs on every
DownloadsActivity resume via unique work `media-existence-check`/KEEP) is
restore-aware — keep these three properties if you touch it:**
- **Trust is per path.** `File.exists() == false` also means "present but
  unreadable" — the state of every restored entry before the storage grant
  (≤ 12) or for foreign-owned files (13+). Missing is only *marked*
  (ERROR/`FILE_NOT_FOUND`) when the negative is trustworthy: app-private
  paths always, shared storage only while `READ_EXTERNAL_STORAGE` is held
  (≤ 32), null path always (broken data).
- **It self-heals.** An entry it errored whose file is readable again flips
  back to FINISHED — only its own error type, so genuine failures stay.
- **It never deletes rows.** `exists()` is not reliable enough for an
  irreversible action, and the record of what was downloaded is user data;
  removal is the user's call on the visible error entry.

**Deleting a RESTORED (foreign-owned) file — SAF, with a write-grant
re-prompt.** A restored entry's public file is foreign-owned on Android 11+, so
`File.delete()` can't remove it. `RestoredFileAccess` deletes via the persisted
SAF tree grant; if that grant is READ-only (an older restore took READ only),
the delete surfaces a **"Grant access"** snackbar → folder picker re-takes the
tree READ+**WRITE** → retries. The current restore flow takes READ+WRITE
up-front, so the delete is silent (no prompt) — which is correct, not a missed
prompt.

**Restore imports the NEWEST decryptable snapshot only — not a sum of every
`.fdbk`.** Every published mirror is a FULL snapshot of the finished, non-safe
rows (`writeMirror` gates out empty ones — `mirrorRows > 0`, so an empty
reinstall never publishes one), so a newer mirror supersets any older; there is
nothing an older candidate could add. `restoreFromTree` sorts candidates
newest-first and imports the **first one it can decrypt, then breaks**. This is
deliberately NOT the old "decrypt + import + SUM every candidate" loop: after a
reinstall the fixed-name `.fdbk` is foreign-owned, so each app-background dropped
a fresh **timestamped** mirror into the folder, and importing all of them
re-scanned the whole download table + probed every row's file over SAF once per
file — a folder full of accumulated mirrors made restore run for many seconds and
look like it **never ended** ("restoring is on a loop"). Bounding it to the
single newest snapshot fixes that regardless of how many stale files linger.
`writeMirror` ALSO prunes its own old timestamped mirrors (`pruneOwnedMirrors` —
File-API delete of every `*.fdbk` it owns except the one just written) so the
pile can't grow in the first place; only files the File API can see (our own) are
touched, so a foreign fixed-name file from a previous install is left as a single
harmless extra the newest-first pick skips.

**Restore SKIPS rows whose file the user already deleted.** Without this, a
reinstall+restore resurrects deleted entries as dead rows: a stale snapshot
written before the delete would bring them back (the mirror is only refreshed on
app-background). `importMirrorDatabase` skips a row when `RestoredFileAccess.isRestoredFileMissing`
proves the file is gone — gated on the SAF grant (restoreFromTree holds it),
never on the grantless `App.onCreate` auto-restore (a foreign file's absence is
untrustworthy there — same caveat as the missing-file sweep). **Gotcha that ate
a round:** probe existence by **opening** the document, not querying its
metadata — `ExternalStorageProvider.queryDocument` returns a row built from the
doc-id PATH without confirming the file is on disk (a deleted file looks
present). And the deleted-file signal is **not** a top-level
`FileNotFoundException`: the provider verifies the tree-child relationship first
and rethrows it wrapped in an `IllegalArgumentException` ("Failed to determine
if X is child of Y: java.io.FileNotFoundException: Missing file …"), so
`isRestoredFileMissing` scans the whole cause chain/message for the missing-file
signal (those are AOSP-internal English constants, stable).

**Accumulation is pruned at the WRITE side, not the restore side.** The old
approach pruned zero-row mirrors *after* a restore (`restoreFromTree` collecting
0-import candidates and `DocumentsContract.deleteDocument`-ing them). That was
removed with the sum-every-candidate loop — it conflated "empty mirror" with
"all rows already live" (an idempotent re-restore imports 0 rows from a perfectly
valid newest snapshot), so it could delete the live backup. The pile is now kept
small at the source instead: `writeEncryptedPublicMirror` calls
`pruneOwnedMirrors` on every background write, so at most one owned `.fdbk`
exists. Don't reintroduce a "prune mirrors that imported 0 rows" pass in restore
— 0 imported is a legitimate, common result (everything already present), not a
signal the mirror is worthless.

**The empty-state Restore button retires after an ATTEMPT, not just success.**
It's shown whenever the unfiltered list is empty, so after a restore that brings
nothing back (0 / wrong device / no backup) it would re-offer the same futile
tap forever. A `restore_attempted` flag (set at the top of `restoreFromTree`, so
it covers both doors and every outcome) hides it once set. The flag lives in
`backup_local.xml` — **excluded from backup** — so a genuine reinstall offers
restore afresh while a within-install re-tap is not re-offered; Settings →
"Restore previous downloads" stays as the deliberate retry door.

## "Send directly" — P2P share (`p2pshare/`, WebRTC)

The Downloads options sheet's quick-action row has a **Send** button
(finished, non-safe entries only — **the vault is never sendable**, same
contract as the backup mirror). It navigates to `P2pSendFragment` — a full
nav-graph destination in `nav_graph_downloads` extending `BaseFocusFragment`
(same pattern as FrameGrabber/GifMaker). The receiver opens **Downloads →
overflow → "Receive a file"** (`P2pReceiveFragment`). This REPLACED the old
LAN share (`lanshare/` — `LanShareServer`/`LanShareTls`/`assets/lanshare/`,
all deleted): the self-signed-TLS interstitial and the VPN/AP-isolation
failure modes were judged worse than requiring Firedown on both ends. With
LAN share went its manifest permissions (`ACCESS_WIFI_STATE`,
`CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`); the P2P
scanner added `CAMERA` (runtime-requested ONLY on the scan screen,
`uses-feature android.hardware.camera.any required=false`, paste fallback
covers camera-less devices).

**Transport: Gecko's own WebRTC DataChannel — deliberately NO libwebrtc
dependency** (~10 MB/ABI to duplicate what GeckoView ships).

**The WebRTC engine runs in a HIDDEN `GeckoSession`, NOT an extension
background page — load-bearing, do not move it back.** `RTCPeerConnection.
createOffer()` **HANGS FOREVER** in a WebExtension background page (it has no
docShell / browsing context) but works exactly like a normal tab in a real
content document — **proven on-device** (a datachannel sample works in a tab,
not in the background page). So the engine (`assets/p2pshare/engine-page.js`)
is a **page-world `<script>`** of the loopback page `http://127.0.0.1:<port>/engine`
(`engine.html`, served by `P2pLoopbackServer`), loaded in a hidden
`GeckoSession` that `P2pShareController` opens per share (mirrors
`PoTokenGenerator`'s hidden-session pattern — `attachRuntime` hands the
controller the runtime + `registerSession` registrar; `registerSession` runs
**before** `session.open()` so the content script + native port bind). A page
script has no `browser.runtime`, so a thin bridge **content script**
(`content.js`, matched on `http://127.0.0.1/*`, gated to `/engine`) opens ONE
native port (`"p2pshare"`) and relays page↔Java over `window.postMessage`
(events out = `{p2p:"evt"}`, commands in = `{p2p:"cmd"}`). `GeckoRuntimeHelper.
onConnect` hands that port to **`P2pShareController`** (the PoTokenGenerator
ownership pattern — the port never lands in `mPorts`). Java owns all UI, the
byte endpoints, the hidden session's lifecycle, and the post-transfer
bookkeeping; the page owns only WebRTC. Verify engine changes with
`node scripts/p2pshare-smoke.mjs` (vm-loads `engine-page.js` under a stubbed
`window`, exercises the `__init`/`ready` handshake, the code codec, soft
errors). Any change to `assets/p2pshare/` files needs the usual
`manifest.json` **version bump** (the `ensureBuiltIn` cache trap).

**Signaling is OFFLINE — there is no signaling server.** The offer/answer
travel as compressed codes (`FDS1.`/`FDR1.` + base64url(deflate-raw(JSON)),
~1–2 KB) shown as **QR codes** (zxing, same encoder the LAN share used) or
sent through any messenger via the share sheet; paste is the no-camera path.
Non-trickle ICE (gathering completes before the code is minted, 5 s cap) so
ONE code carries everything, including the file metadata (name/size/mime/
device) — the receiver previews and accepts **before anything connects** —
and the DTLS fingerprint, which makes the channel **authenticated** E2E (a
MITM can't survive a fingerprint carried out-of-band).

**The flow is REMOTE-FIRST — most pairs are on different networks, sharing
through a messenger — with two wrapper forms for each code:**
- **Messenger form: `https://firedown.app/s#<code>`** (`toHttpsLink`) — what
  the share sheet sends, for BOTH the offer and the reply. Chat apps auto-link
  https where a custom scheme is dead text; the code rides in the `#fragment`,
  which never reaches any server. A VERIFIED App Link (`autoVerify` filter on
  `DownloadsActivity`, `assetlinks.json` live on firedown.app carrying the
  RELEASE cert fingerprint) opens the app directly; unverified devices land on
  the static `/s` bouncer page (firedown-website `s.html`) whose button fires
  the firedown:// form.
- **QR / in-person form: `firedown://p2p/<code>`** (`toDeepLink`) — what the
  QR encodes, so any scanner (system camera included) offers "open in
  Firedown". The QR lives COLLAPSED behind a "Nearby? Show the QR" row on both
  screens — remote is the default, in-person the shortcut.
`handleP2pDeepLink` routes both forms by prefix: an **FDS1 offer** →
`p2p_receive` (`ARG_OFFER_CODE`); an **FDR1 reply** → the LIVE send session
(`provideExternalAnswer`; no session → an honest "share no longer open"
snackbar). So the remote round trip is share link → Accept → send reply →
sender TAPS the reply in the chat — no scanning, no pasting. The sender's
screen flips to a WAITING sub-stage after sharing (what-happens-next copy),
auto-picks an FDR1 reply off the CLIPBOARD on resume (each clip value tried
once, so a soft bad-code can't loop; Android's paste toast keeps the read
visible), and keeps Paste as the manual fallback. The receive REPLY stage is
shaped by HOW the offer arrived (`mArrivedRemote`): link/paste → "Send reply"
share-sheet primary + folded QR; in-app scan → QR expanded, remote widgets
hidden. `P2pShareController.stripDeepLink` unwraps both forms and bare codes
everywhere codes are read. The
only external party is **one STUN server** (address echo, no bytes),
user-chosen in Settings → Downloads: `settings_p2p_stun_entries/values` in
`arrays.xml` (Cloudflare default, Nextcloud:443 for UDP-3478-hostile
networks, "custom" sentinel → text dialog in `SettingsFragment`, stored in
`SETTINGS_P2P_STUN_CUSTOM`; resolve via `Preferences.getP2pStunServer`).
**`iceServers` come from exactly THREE sources — the user's STUN choice, the
user's optional custom TURN, and the first-party Firedown relay via FETCHED
ephemeral credentials. Never a hardcoded fallback list, never baked
credentials.** (A multi-STUN list queries every server on every share = IP
leak to all of them; static TURN creds in an open-source APK hand the relay
to the whole internet as an open proxy.) Same-LAN pairs connect on host
candidates without touching any of them. The entries:
- **STUN echo** — user-chosen (above).
- **Custom TURN, opt-in, default OFF**: `SETTINGS_P2P_TURN_URL`/`_USER`/`_CRED`
  (url+username+credential; typically the user's own coturn), edited via
  `SettingsFragment.showTurnDialog`, resolved by `Preferences.getP2pTurn`
  (null when unset).
- **The Firedown relay (turn.firedown.app) — free, on by default, NO baked
  secret** (maintainer decision: the relay fallback ships working; send-file
  is not monetized). Each share session fetches short-lived coturn REST creds
  from `Preferences.P2P_RELAY_CREDS_URL` (`api.firedown.app/v1/relay/creds` —
  an anonymous GET, no identifiers; server half + the coturn abuse rails live
  in firedown-api `handler_relay.go` + `deploy/turn-provision.sh`). The fetch
  is cached until near expiry, bounded by short timeouts, and **failure
  degrades gracefully** — the share proceeds direct+STUN-only, which is
  exactly the pre-relay behavior. Privacy delta, stated honestly: starting a
  share now touches api.firedown.app once (an unauthenticated creds GET); the
  relay itself carries only DTLS ciphertext, and ICE uses it only when no
  direct path exists.
All three are threaded into the start command by `putIceServers` and
assembled by the engine's `newPeerConnection(ice)` (`sanitizeIceServers`
guards the ctor). A genuinely-unreachable pair with the relay fetch failed —
CGNAT↔CGNAT, full-tunnel VPN — still fails honestly (`no-path` →
`p2p_error_no_path`). Don't reintroduce a multi-STUN fallback list, and never
replace the fetched-ephemeral-creds design with a static credential.

**The answer returns automatically — the human-relayed reply is the last
resort.** WebRTC needs an answer back (the receiver's candidates/DTLS
fingerprint don't exist until Accept), and the answer has three tiers:
1. **LAN return (`ans`)** — served by **`P2pAnswerServer`**, the ONLY thing
   Firedown ever listens for on a LAN interface, deliberately tiny: one
   token-gated `POST /answer` (16-byte token, carried only inside the offer
   code), 16 KB body cap, first-delivery-wins, lifetime = the sender's offer
   stage (binding prefers a `wlan*` site-local IPv4 so a VPN's tun address
   isn't advertised; no LAN address → no `ans`). Same-network, instant, never
   leaves the LAN.
2. **Rendezvous (`rvz`)** — the always-on api mailbox
   (`api.firedown.app/v1/p2p/a/<id>`, `Preferences.P2P_RENDEZVOUS_URL`; server
   half `firedown-api handler_rendezvous.go`). The sender mints a 128-bit id,
   embeds `<base>/a/<id>` in the offer and long-polls `…?wait=1`
   (`P2pSignalingClient.pollAnswer`); the receiver's Accept POSTs the answer
   there. This is what removes the reply step from a CROSS-NETWORK share —
   Accept → connect, no tapping. In-memory, 5-min TTL, single-use, nothing
   logged/persisted; the compressed-SDP answer transits RAM only, and the
   codes' DTLS fingerprints keep a tampering relay from MITMing.
3. **Human-relayed reply** — only reached via `deliverReplyFallback` when BOTH
   above fail: a share-sheet https reply link the sender taps, or a QR on scan
   arrival (see the remote-first flow above).

**LAN + rendezvous race with HAPPY-EYEBALLS, not a strict sequence
(`deliverAnswer`).** The receiver fires the LAN return immediately and, if it
hasn't delivered within `ANSWER_RENDEZVOUS_HEADSTART_MS` (700 ms), ALSO fires
the mailbox in parallel; first to arrive wins and the sender treats a later
duplicate answer as a soft no-op (`signalingState`). This preserves
"same-LAN never leaves the LAN" (a real listener answers in a few ms, so the
mailbox never fires) while a sender behind a full-tunnel VPN/proxy — unreachable
on its advertised `ans` endpoint — no longer pays that path's full ~4 s connect
timeout BEFORE the mailbox starts. **That delay was fatal on-device:** a proxied
sender is symmetric-NAT (relay is its ONLY usable candidate), and the answer
landing ~5 s late meant the sender installed its TURN permissions after the
peer's ICE had already given up — relay candidates on both sides, yet `no-path`.
Do NOT revert to the old sequential `deliverAnswer → rendezvousOrReply →
relayOrReply` chain (it serialized the dead LAN timeout in front of the mailbox).
`postAnswer` reports delivery (`Consumer<Boolean>`) so the race can tell success
from failure; the reply fallback fires only when BOTH push paths fail. The data
path itself needs no LAN-vs-TURN logic — ICE nominates the working pair (host↔host
fails on the VPN, relay↔relay wins) automatically, once the answer is timely.

**The OFFER can be brokered for a SHORT link (`FDO1.<id>`) — the offer mailbox.**
The self-contained offer (`FDS1.` = whole SDP + candidates + file meta, ~1–2 KB
base64) makes a ~1166-char link/QR. So the sender ALSO uploads the full offer to
the rendezvous **offer mailbox** (`/v1/p2p/o/<id>`, same id as its `/a/<id>`
answer poll; `P2pSignalingClient.uploadOffer`) and the "Send link" action shares
a short reference `https://firedown.app/s#FDO1.<id>` (`OFFER_REF_PREFIX`,
`getShareContent` flips to it only AFTER the upload lands — a tap before then
falls back to the full self-contained link, no regression). The receiver's
`DownloadsActivity.routeCode` recognizes `FDO1.` → `ARG_OFFER_REF` →
`startReceiveFromOfferRef` fetches the real offer from the mailbox
(`fetchOffer`), then runs the normal receive. The engine NEVER sees `FDO1.` —
Java resolves it to `FDS1.` before `recv-start`, so no engine change / no
manifest bump. **The QR keeps the full `FDS1.` code** (`toDeepLink`) — in-person
sharing needs no server and works offline. Offer mailbox: non-destructive read
(the receiver may retry; the id IS the share capability, exactly like the full
link — the single-use ANSWER still means only one party completes), 15-min TTL,
no long-poll (the short link is enabled only after the upload, so a tapped link's
offer is already there; unknown/expired → 204 → "link expired"). Privacy delta,
stated honestly: with a short link the offer now ALSO transits the api in memory
for its TTL (the answer already did) — still nothing logged/persisted, and the
DTLS fingerprints still block a MITM.

So same-LAN AND cross-network are both share/scan → Accept → transfer, with zero
reply step in the common case. The connect no-path timer arms only when
`connectionState` reaches "connecting" — NEVER at offer creation (signaling is
human-paced; the old at-creation timer failed senders who were merely waiting to
be scanned).

**The mDNS-obfuscation flip is load-bearing for same-LAN ICE.** Firefox
hides host ICE candidates behind mDNS `.local` names
(`media.peerconnection.ice.obfuscate_host_addresses`); Android peers
generally can't resolve those (no MulticastLock), so with obfuscation on a
same-LAN pair finds NO host pair and ICE fails outright ("add a TURN
server" — observed on-device). `P2pShareBaseFragment` flips it OFF for the
share session (`setWebRtcIceObfuscation(false)`, chained after `setWebRTC`
before `onEngineReady`) and restores ON in `onDestroyView` — browsing keeps
the privacy feature; the share QR already hands the LAN IP to the peer
physically. Don't remove the flip or the restore.

**WebRTC is ALWAYS ON — the user toggle was REMOVED** (maintainer decision):
the off-default broke real sites (Meet/Discord/WhatsApp calls) and forced the
share flow into a pref-flip dance, while the classic local-IP leak the toggle
guarded against is already covered by mDNS candidate obfuscation (kept ON for
browsing, flipped only inside a share session — see above). Boot sets
`setWebRTC(true)` unconditionally; the `SETTINGS_ENABLE_WEBRTC` key, Settings
row, and the `p2p_rtc_note` disclosure line are gone. If a disable-style
switch is ever reintroduced it needs a NEW pref key (the JIT/WASM inversion
rule). **The pref-gated-global trap still applies structurally**
(`RTCPeerConnection` is Pref-gated WebIDL evaluated **when a page global is
created** — a page loaded with the pref off has NO constructor even after it
flips on), which is why the machinery below stays even though the pref never
changes anymore:
1. **Start the engine only AFTER the pref write is applied.**
   `P2pShareBaseFragment` still writes `setWebRTC(true)` (idempotent
   belt-and-braces so a share can't race a cold boot's async pref write),
   chains the mDNS-obfuscation flip, and fires `onEngineReady()` on
   `.accept(...)` — `onEngineReady` drives the controller to
   `openEngineSession()`. Firing that synchronously after the writes would
   race them and load the page before the values are visible.
2. **A fresh session per share, torn down in `stopSession`.** `startSend`/
   `startReceive` call `stopSession` first (closing any prior engine session),
   then `ensureEngineSession` opens a new one loading `getEnginePageUrl()`. The
   page reports `{type:"ready", rtc:true}` once its script is live (via the
   `__init` bridge handshake); the queued command runs on that event, an
   `ENGINE_READY_TIMEOUT_MS` backstops a page that never comes up. Because the
   session is fresh each time, there is **no sticky-`rtc`-ready / dead-ICE-stack
   problem** (the old off→on cycle bug that needed a forced reload) — the whole
   `ensure`/`reload`/`mForceEngineReload`/`mAwaitingReload` dance is **gone**.
**Never fire the first engine command before the pref `GeckoResult` resolves,
and never move the engine back into a background page** (createOffer hangs
there — see the transport note above).

**Bytes never cross the native-messaging bridge** (that would be
base64-in-JSON per chunk). `P2pLoopbackServer` (127.0.0.1, ephemeral port,
16-byte token, lifetime = share screen, same "nothing listens when the
screen isn't open" contract as LAN share): the sender-side engine
`fetch()`es `GET /read` and pumps the stream into the DataChannel (64 KB
chunks, `bufferedAmountLow` backpressure, 4 MB high-water); the
receiver-side engine batches ~4 MB and `POST /write?off=` — offsets are
verified server-side so ordering on disk is guaranteed. The extension
manifest carries the `http://127.0.0.1/*` host permission for this (Firefox
match patterns ignore ports). `RestoredFileAccess.openReadOnly` serves the
read side so a restored (foreign-owned) download is still sendable. On-device
watch-item: the runtime sets `setLnaBlocking(true)` — if loopback fetches
ever get blocked by Local Network Access enforcement, the host permission is
the intended exemption; verify on a real device.

**Completion is receiver-ack'd, then the file becomes a normal download.**
The sender's `done` fires only on the receiver's `{"t":"rcvd"}` ack (sent
AFTER the last loopback write) — `bufferedAmount` hitting 0 proves nothing
about the far end. The receiver must **`waitBufferedDrain(dc)` before
`pc.close()`** — `pc.close()` aborts SCTP immediately and would drop a
still-buffered ack, hanging the sender; the sender also arms an
`ACK_TIMEOUT_MS` after eof so a genuinely lost ack fails honestly instead of
spinning forever. The receiver enforces the **accepted size**
(`offer.size + OVERRUN_SLACK`): a modified sender that advertised "2 MB" can't
stream tens of GB to fill the disk. The receiver writes to `<name>.part`, then
`P2pShareController.finalizeReceivedFile` (disk executor) verifies the byte
count, re-uniquifies (against disk AND the download table via `findByFilePath`
— a path free on disk can be owned by a queued/errored row) + renames, inserts
a `FINISHED` `DownloadEntity` (`file_url = "p2p://<device-slug>"` so the row's
`MIME · domain` meta line names the transport honestly; mime is derived from
the FILE, not the entity's stored label; Room invalidation refreshes the list,
no poke) and calls `GalleryPublisher.publish`. An aborted/failed receive
deletes the `.part`. **A bad scanned/pasted answer is SOFT** (`bad-code`): the
engine keys softness on `signalingState` and treats every decode/apply failure
before connect as recoverable (the offer QR stays valid) — never `fail()` the
session on a mangled paste.

**Scanner is a full-screen `DialogFragment` (`<dialog>` destination), NOT a
`<fragment>` — load-bearing.** A `<fragment>` scanner destination would
destroy the send/receive fragment's view on navigate →
`P2pShareBaseFragment.onDestroyView` → `controller.stop()`, killing the live
WebRTC session mid-handshake; the returning fragment mints a fresh offer the
scanned answer can never match (the QR-reply path would ALWAYS fail, only paste
worked). A dialog shows on top, leaving the caller's view — and its session —
intact. `P2pScanFragment` uses CameraX (`camera-core/camera2/lifecycle/view`,
the app's only camera use) + **zxing decode** of the Y plane (rowStride-
compacted into a REUSED buffer — a fresh ~1-2 MB array per frame at 15-30 fps
is needless GC churn; NO ML Kit / Play Services — de-Googled devices stay
first-class). Result returns via the previous back-stack entry's
`SavedStateHandle` (`P2pScanFragment.RESULT_CODE`, the
CancelOperationDialogFragment pattern), validated against the expected
`FDS1.`/`FDR1.` prefix before delivery. **Don't turn it back into a
`<fragment>`.**

**Session lifetime = view lifetime, but a back-press mid-transfer confirms
first** (`P2pShareBaseFragment` `OnBackPressedCallback` + toolbar nav both go
through `confirmThenClose`, gated on `controller.isTransferActive()` — set on
the first `progress` event) so an accidental swipe-back doesn't silently
discard an in-flight transfer. Clipboard paste goes through the shared
`ClipboardHelper.readTrimmedText` (single `getPrimaryClip` + `coerceToText` —
NOT a multi-call `getText` chain, which fires the Android-13 paste toast per
call and reads empty for `text/uri-list` clips from Chromium browsers). The
STUN setting is a **click-row chooser**, not a `ListPreference` (the latter
fires no change event when you re-pick the already-selected "Custom…", so the
URL became uneditable); the custom URL is scheme-validated (`stun:`/`turn:`)
before persisting so a typo can't fail every future share in the
`RTCPeerConnection` constructor.

Strings are `p2p_*`/`settings_p2p_stun*`, translated across the same 16
locales as the JIT toggle.

**BROWSER-RECIPIENT RECEIVE — the sanctioned way to reach someone without
Firedown installed (NOT a server-side share link).** This is the designed-but-
unbuilt alternative to public Cloud Backup share links (see "PUBLIC SHARE LINKS
— DECIDED AGAINST" in the Cloud Backup section for why that road is closed). The
idea: the RECEIVER is a web page. The sender's phone keeps serving bytes over the
existing WebRTC DataChannel; the recipient opens the same
`https://firedown.app/s#FDO1.<id>` link in ANY browser and a static page on
firedown-website does the receive. Nothing is ever stored server-side, signaling
stays RAM-only on its existing TTLs, and there is nothing publicly fetchable and
nothing to take down — Firedown stays not-a-host.

- **The SENDER side is untouched — that is the point.** It never learns its peer
  is a browser: same offer, same non-trickle ICE, same `FDO1.` upload to the
  offer mailbox, same long-poll on `/v1/p2p/a/<id>`. **No `engine-page.js`
  change, no `manifest.json` version bump, no Java change.**
- **Flow:** `s.html` (today only a `firedown://` bouncer) gains a second door,
  "Receive in this browser" → `GET /v1/p2p/o/<id>` → decode the `FDS1.` code →
  preview name/size/mime/device from the code (the offline-preview property
  survives) → Accept → ICE from the existing anonymous `GET /v1/relay/creds` →
  `setRemoteDescription` → `createAnswer` → wait ICE complete → encode `FDR1.` →
  `POST /v1/p2p/a/<id>`, which the sender is ALREADY long-polling. No reply step,
  no QR, no paste. Then the normal 64 KB chunk stream + `{"t":"eof",bytes}`, and
  `{"t":"rcvd"}` + `waitBufferedDrain` at the end — the sender's `done` still
  fires only on that ack, contract unchanged.
- **The codec is free in a browser.** `encodeCode`/`decodeCode` are base64url +
  `CompressionStream`/`DecompressionStream("deflate-raw")` — native Web APIs with
  no `browser.*` dependency, which is exactly why the port is cheap.
- **Writing to disk is the ONE genuinely new piece**, capability-detected in
  three tiers: **File System Access** (`showSaveFilePicker` →
  `FileSystemWritableFileStream`, true streaming at any size — Chrome/Edge incl.
  Android), else a **service worker streaming a `Response`** into the browser's
  own downloader (the StreamSaver/wormhole technique — Firefox, Safari 15.4+;
  tab must stay open), else a **Blob in RAM** with a size warning (dies on
  multi-GB).
- **It needs the API's FIRST CORS**, and that is a deliberate exception to
  firedown-api's "no CORS" scope rule, not a quiet addition: exactly
  `GET /v1/p2p/o/{id}`, `POST /v1/p2p/a/{id}`, `GET /v1/relay/creds`, scoped to
  `Origin: https://firedown.app`, plus preflight for the POST. No new storage, no
  auth change, no persistence.
- **The real cost is a SECOND IMPLEMENTATION OF THE WIRE PROTOCOL** in another
  repo — code codec, chunk framing, `eof`/`rcvd` control messages, backpressure,
  the `OVERRUN_SLACK` cap — which must stay in lockstep with `engine-page.js`.
  That is the duplication this file keeps recording (`compactDuration`, the three
  private QR encoders), so design against it from the start: the shared halves
  are pure browser JS, so extract ONE codec file and extend
  `scripts/p2pshare-smoke.mjs` to drive the receiver too. Don't hand-copy.
- **Other honest limits:** the sender must stay on the share screen for the whole
  transfer (session lifetime = view lifetime — fine at 50 MB, painful at 5 GB
  over a phone uplink); there is no resume, so a dropped connection restarts at
  byte 0 (true today, but a browser recipient on flaky wifi meets it more);
  Safari is the worst tier. Privacy delta: the recipient's IP now touches
  firedown.app and possibly the TURN relay — where today a non-Firedown recipient
  could not participate at all.
- **The DTLS fingerprint rides in the code**, so the browser page authenticates
  the peer exactly as the app does and a tampering mailbox still can't MITM. That
  property must survive any port — don't drop the fingerprint check "because the
  mailbox is ours".
- **What it deliberately does NOT buy: sender-offline delivery, and serving a
  file no longer on the device.** Those two ARE the hosting property; any design
  that delivers them puts bytes on a public URL and brings the whole abuse desk
  with it. Accept the loss explicitly rather than reopening it.

### Empty-focus "most visited" — the existing list, filled (not a new widget)

When the address bar is focused and EMPTY, a **most-visited strip** is shown
under the clipboard chip with top-frecency sites. The home screen stays bare by
product choice; this lives only in the focused panel.

- **It is its OWN view, NOT the suggestion list — this is load-bearing.** A
  horizontal **strip of favicon tiles** (`fragment_autocomplete_tile` +
  `MostVisitedTilesAdapter`, a plain `RecyclerView.Adapter`) in its own
  `most_visited_card`, **separate** from the suggestion `RecyclerView`
  (`search_view`, which stays history/tabs/bookmarks/search **only**, exactly as
  before). The empty↔typed transition is a pure **visibility flip**
  (`showEmpty()` reveals clipboard + strip; `hideAll()` hides them and shows the
  suggestion list) — **no shared adapter, no `ListAdapter` async diff, so no
  blink.** History: most-visited was first built as rows INSIDE the suggestion
  list (a flame-glyph attempt, then a labeled "MOST VISITED" badge-row section
  with a unified scaffold). Every variant **blinked** on the first keystroke
  because the single `ListAdapter` diffs the most-visited→typed swap on a
  background thread (a frame+ late), which **no** sync-header / local-first /
  debounce trick fully closed. The fix was architectural: split it into a second
  view. **Do not** re-merge most-visited into the suggestion adapter.
- **Tile:** favicon in a 52dp rounded badge (the same `bg_incognito_chip` /
  surfaceContainerHigh treatment as the clipboard icon) + the **page title**
  (like Chrome/Brave top-sites tiles), falling back to the short site label
  (`siteLabel` = the registrable domain's main label, e.g. `m.youtube.com` →
  "youtube") only when the row has no usable title. `mostVisited()` already skips
  blank-title rows, so the title is normally present; the fallback just covers an
  untitled entity. The label is single-line + ellipsized. The favicon is 32dp in
  the 52dp badge. Tap → `OnMostVisitedClickListener` → the host opens the entity's
  subtext URL (same as a history-suggestion tap). The strip has a small "MOST
  VISITED" label above it. **Strip layout cues:** the `most_visited_view`
  RecyclerView is `wrap_content` width + `center_horizontal` so a few tiles centre
  (no left-hugging dead space on a fresh profile) while an overflowing set caps at
  the parent width and scrolls; `requiresFadingEdge="horizontal"` draws a
  self-hiding right-edge fade as the "more →" scroll cue (native, no overlay view
  or scroll listener). The tile root carries a `contentDescription` (the shown
  label) so TalkBack announces it as a link.
- **Removal = HIDE via a blocklist, NOT a history delete (Chromium/Brave "Top
  Sites" model).** Long-press a tile → confirm dialog (owned by `AutoCompleteView`,
  it has the context) → the site's **host** is added to a standalone
  `MostVisitedBlockDatabase` (`most_visited_block`, keyed by HOST) via
  `MostVisitedBlockRepository`. Keyed by host (`blockKey` = `hostOf`, the SAME
  www-stripping the strip dedups tiles by), NOT the exact URL — otherwise hiding
  one canonical variant (`firedown.app`) lets another (`www.firedown.app`)
  resurface as a fresh tile, since the strip's per-host cap only collapses them
  for display. `AutoCompleteSearch.mostVisited()` reads the blocklist
  (`getBlockedHostsSync`, it already runs on a background executor) and **skips
  any candidate whose `blockKey` is blocked** — so the tile disappears but
  **history is untouched**
  (the site still appears in typed history suggestions and the History screen).
  The block + the strip re-query run on the SAME executor thread (ViewModel
  `hideFromMostVisited`), so the refresh excludes it. It's a STANDALONE DB on
  purpose — not a table in `WebHistoryDatabase` (whose tracking-mode history makes
  migrations there a minefield), and the blocklist is filtered in Java (no JOIN
  with history needed). `unblock`/`clear` exist on the repo for a future un-hide /
  reset. (This replaced an earlier `deleteByUrl` history-deletion approach — the
  Chromium split is: top-sites tiles HIDE, omnibox suggestions DELETE; the strip
  is the tile case.)
- **Visibility gating.** `AutoCompleteView.setMostVisited(list)` populates the
  strip adapter and calls `updateMostVisitedVisibility()`, which shows the card
  only when `mEmptyState` is active AND the strip has tiles — so a late
  most-visited post arriving mid-typing can't pop the strip open, and an empty
  list (no history / **incognito**) keeps it hidden (clipboard-chip-only empty
  state). The clipboard chip stays **empty-only** (hidden on typing, per browser
  convention). Both are separate views above the suggestion card, so the only
  motion on the first keystroke is two `setVisibility` calls.

- **Frecency without a visit counter.** `webhistory` has no visit-count column;
  the uid is `hash(url)+day`, so there's one row per (url, day), and
  `COUNT(*)` grouped by url = distinct days visited — a lightweight proxy.
  `WebHistoryDao.getMostVisited` orders by that count then recency, excludes
  `about:%`. The title/icon come from each url's **most recent visit** (a single
  `MAX(file_date)` in a subquery → SQLite's "bare columns in an aggregate query"
  rule picks that row; the latest visit usually holds the fully-loaded title,
  earlier mid-load visits often had none). `COUNT`/`MAX` are confined to the
  subquery so the outer projection is exactly the entity columns (no Room
  cursor-mismatch warning).
- **The query `limit` is a CANDIDATE pool, not the shown count.**
  `AutoCompleteSearch.mostVisited` over-fetches (`MOST_VISITED_CANDIDATES`) and
  then thins to `MAX_MOST_VISITED` rows: it **caps to one row per host**
  (`hostOf` — lowercased, leading `www.` stripped; other subdomains stay
  distinct) so a binged site's many deep links can't crowd out the rail (distinct
  SITES, like a new-tab "top sites" grid), and it **skips blank-title rows**
  (unlike typed search, which shows the URL as the title — a bare-URL row reads
  as broken next to titled rows, and a never-titled page is the weakest top-site
  candidate; the most-recent-visit title above already minimises these).
- **Separate LiveData stream.** `loadMostVisited()` posts to its OWN
  `mMostVisitedData` (own `mMostVisitedGen` guard), NOT the suggestion
  `mSearchData` — the whole point of the split, so the strip and the typed list
  never share state. The Home/Browser fragments observe `getMostVisited()` →
  `AutoCompleteView.setMostVisited(list)`. The empty-field entry points
  (focus-gain, delete-to-empty, clear button) call `loadMostVisited()` +
  `showEmpty()`; typing calls `search()` (suggestions) + the `getWebSearch`
  observer's `hideAll()`. `search()`/`runSearch()` are back to their plain form
  (no most-visited entanglement — no sync-header, no `mForceLocalFirst`).
- **Incognito is the gate, centralized.** `AutoCompleteSearch.mostVisited()`
  returns empty when `mIncognito`, so the strip stays hidden = the clipboard-only
  empty state. The incognito fragment doesn't even observe `getMostVisited()`.
  Never show most-visited in incognito.

Two hardenings in `AutoCompleteView.showClipboard()` (the clipboard suggestion
chip shown when the address bar gains focus) — both from one on-device episode:

- **`ClipboardManager.getPrimaryClip()` must not be fatal.** It's a binder call
  into system_server's clipboard service; when system_server is dying/restarting
  it throws `RuntimeException(DeadSystemException)` — observed killing the app
  from the URL-bar focus tap that raced the system's death (stack:
  `showClipboard ← showEmpty ← HomeFragment.onFocusChanged`). Some OEM clipboard
  services also throw `SecurityException` on background reads. The read is
  wrapped in try/catch(RuntimeException); on any failure the chip is simply not
  offered. Treat other "decorative" system-service binder reads the same way —
  a convenience UI affordance never gets to crash the app. (A `DeadSystemException`
  crash report means **the OS died**, not the app — the real cause is in
  system_server's logs, `adb logcat -b crash -b system` / `adb bugreport`.)
- **Don't volunteer giant clips.** A clip over `MAX_CLIP_SUGGESTION_LENGTH`
  (8192 chars — generous for any real URL or search phrase) is skipped: one tap
  on the chip would push the whole blob into the address bar, from where it gets
  carried across binder repeatedly (an EditText saves its **full text** in
  view-hierarchy saved state on every `onStop`; accessibility announcements ship
  the field text too) and churned through text-change handling and the
  history/suggestion path. A deliberate long-press paste still works — the cap
  only stops the app from *offering* the blob.

## Cloud Backup — encrypted per-file backup to `storage.firedown.app`

The optional **Cloud Backup** feature backs a finished download up to the
encrypted-storage service (`storage.firedown.app`, the `firedown-api`
`storage-api` binary). It is a SEPARATE feature from the local **Safe Folder**
(`file_safe`, which never leaves the device): user-facing copy always says
"Cloud Backup", internal classes keep the `Vault*` names
(`VaultEngine`/`VaultBackupWorker`/`VaultRestoreWorker`/`VaultThumbnail`/
`VaultManifest`/`VaultEntry`, facade `CloudBackupManager`). **The server NEVER
sees plaintext** — same cardinal rule as the bookmark sync: bytes are
client-encrypted (`VaultCrypto`, per-file AES-256-GCM DEK wrapped under the
storage master key) and uploaded phone↔R2 via presigned URLs; the server stores
opaque chunks + an opaque manifest blob.

- **Shared identity, no on/off switch.** Cloud Backup reuses the bookmark-sync
  **recovery code** (`SyncSecrets` → `SyncIdentity`) — one code derives the same
  account on every service, with a distinct storage content key (HKDF
  `firedown/storage/v1`). It is **action-driven**: a finished download's options
  sheet ("Back up to cloud") sets it up on demand (minting + showing the code
  first if none exists). `Preferences.CLOUD_BACKUP_ENABLED` only tracks "has
  data, keep the shared code" so signing out of one feature can't strand the
  other (symmetric wipes in `SyncManager.disable`/`CloudBackupManager
  .deleteAllData`).

- **Starter credit — register at CODE CREATION, label the trial honestly.** In
  metered mode the server grants a one-time free starter credit AT REGISTRATION
  (`FIREDOWN_STORAGE_STARTER_GRANT_GBM`, see firedown-api), so
  `CloudBackupManager.registerInBackground` registers right after the recovery
  code is created or adopted on the Cloud screen — not waiting for the first
  backup's `ensureRegistered` — and the hero's next quota load shows the granted
  runway (the roadmap's step ② check-off on server balance was already built).
  Best-effort: offline falls back to first-backup registration; both the client
  marker and the server grant are once-only, nothing double-applies. It is
  deliberately NOT folded into `loadStatus` — that also runs for bookmarks-ONLY
  codes, which must not get storage accounts minted. `Quota.starterGrantedAt`
  (from quota's `starter_granted_at`) drives the honest label: a balance the
  user never paid for renders roadmap step ② as `cloud_starter_credit_step`
  ("Free starter credit included", 16 locales) instead of "✓ Add storage
  credit" — a local `CLOUD_PLAN_*` purchase shape wins over the trial label.
- **Register ONCE per install, not per backup (Cloudflare rate limit).** The CF
  edge rate-limits ONLY the `/v1/account/register` + `/v1/register/challenge`
  endpoints (per-IP, anti-Sybil — see `firedown-api`). `VaultEngine.backupFile`
  used to call `api.register` on EVERY upload (2 requests to those exact endpoints
  each), so backing up several files burst them → **429**. Registration is now
  gated by `CloudBackupManager.ensureRegistered` — a per-account prefs marker under
  a **static lock** (so concurrent first-time backups serialize to ONE register,
  not N) — called by `VaultBackupWorker` before upload and by `deleteAllData`
  (which `clearRegistered`s after erasing). `backupFile` no longer registers; the
  manifest/object calls are signed and resolve against the already-created account.
  Don't re-add a per-op `api.register`. Defense in depth: `StorageApiClient` wraps
  a **derived** OkHttp client (`newBuilder().addInterceptor` — never the global
  client) with a small retry interceptor that backs off on 429/503 honouring
  `Retry-After` (OkHttp has NO built-in 429 handling — `retryOnConnectionFailure`
  is network-errors only). Keep the CF rule as-is; the fix is the client not
  over-calling register, not weakening the anti-Sybil limit.
- **The encrypted manifest is the file index** (`VaultManifest`, OCC-versioned,
  16 MiB server cap). One `VaultEntry` per backed-up file: objectId, wrapped DEK,
  name/mime/size/downloadedAt/chunkCount, **plus a tiny base64 JPEG `thumb`**.
  The manifest is gzip+GCM-encrypted (`BookmarkBlob`) before upload, so **the
  thumbnails DO travel to `storage.firedown.app` but only inside the E2E-encrypted
  blob — the server can't read them.** Kept small by design (`VaultThumbnail`:
  ≤160px longest side, JPEG q60) so a manifest of many files stays under the cap
  (~a few KB each → thousands of files fit). This is why a preview can show
  **offline, even after the local copy is deleted** (the whole point of backing
  up). `bookmarks-cipher.json`-style encryption details are client-only; the
  server implements none of it.
  - **Preview SIZE is TWO constants, and conflating them is what made the
    thumbnails look bad.** `VaultThumbnail.MAX_DIM` (**256**px longest side,
    JPEG **q80**) is the STORED one — it rides in the manifest, so it is bounded
    by the 16 MiB cap *and* by the manifest being pulled AND pushed on every
    OCC mutation over a metered store, which is the real cost. It was **160px
    q60**, a number chosen purely against that budget and never against the
    display: a list row is 78×64dp = **234×192 px** on a 3x phone and a grid tile
    ~172×110dp = **516×330 px**, so a 160px source was UPSCALED ~1.5x and ~3.2x
    respectively, with q60 artifacts on top — reported on-device as "the quality
    is very poor". At 256/q80 an entry is ~11 KB base64, so ~1400 files still fit
    the cap. Area scales with the SQUARE of this constant and every byte is paid
    per pull and per push, so redo that arithmetic before raising it again.
    `VaultThumbnail.DISPLAY_DIM` (**512**px) is the other one: a display-only
    bitmap decoded from the LOCAL file by `resolveLocalThumb`, which never enters
    the manifest, so the stored budget does not apply and there is no reason to
    hand the list an upscaled image when the real file is on disk. The
    cloud-object decode path in `resolveLocalThumb` deliberately stays at
    `MAX_DIM` — unlike the local paths it downloads and decrypts cloud bytes.
    **Existing entries are NOT re-encoded**, so this improves new backups (and
    any file re-backed-up, where `backupFile` rewrites the thumb without
    re-uploading); an old 160px entry stays soft until then.
  - **`VaultThumbnail` MUST keep its native-FFmpeg fallback — MMR alone silently
    produced NO stored preview.** `decodeVideoFrame` tries
    `MediaMetadataRetriever` and, when that cannot open the clip at all, falls
    through to `decodeVideoFrameNative` (`FFmpegThumbnailer`, path or the SAF
    descriptor, `setTargetSizeHint`, `streamPos = frameUs > 0 ? frameUs : -1`
    where **-1 means "no mandate"** so native picks its duration-aware offset —
    passing 0 would pin the black head frame). Without it, a codec the device's
    MMR lacks (AV1 is the documented one) made `generate` return null, the
    manifest stored no thumb, and **the failure was invisible on the device that
    did the backup**: its Backups row falls back to the local-file backfill
    (`thumbBitmapFor`: `entry.thumb == null` → `mResolvedThumbs`), which goes
    through the FFmpeg-capable Glide chain. A SECOND device on the same recovery
    code has no local file and, for video, no cloud-decode path (that one is
    image-gated), so it showed the mime glyph. That is the diagnostic signature
    to remember: **a thumbnail that appears on the uploading device and not on
    any other device sharing the code means the STORED thumb is null, not that
    the other device is broken.** The Downloads list never had this because it
    always had the fallback. `VaultBackupWorker` now logs the generated thumb's
    size (or an explicit NULL + what it implies) under `BuildConfig.DEBUG` —
    that path used to fail completely silently, which is what made the report
    hard to attribute.
  - **`maxDim` is threaded into every decoder, not just `scaleDown`.**
    `decodeImage`/`decodeVideoFrame`/`decodeVideoFrameNative`/`sampleSize` all
    take it, because `getScaledFrameAtTime`, the native size hint and
    `inSampleSize` each cap the decode independently — when they hardcoded
    `MAX_DIM`, the `DISPLAY_DIM` path was silently capped at the stored size and
    the whole point of the split was lost for the `generateBitmap` fallback (the
    primary local path, `GlideHelper.downloadThumbSync`, honoured it, which is
    what hid the mistake). `decodeAudioArt` keeps `MAX_DIM` for its sample hint
    only; `scaleDown` then applies the caller's bound.
    Consequences that came with it, in `CloudBackupFileAdapter`: `mResolvedThumbs`
    became a byte-bounded `LruCache` (8 MiB) because 512px bitmaps are ~670 KB
    each and it was an UNBOUNDED `HashMap`; `THUMB_CACHE_BYTES` went 2 → 4 MiB
    because a 256px stored thumb decodes to ~2.6x the bytes a 160px one did; and
    `trimThumbCache` now `trimToSize`s the resolved cache to a quarter instead of
    keeping all of it — holding 8 MiB of re-derivable bitmaps while the OS asks
    for memory is not defensible, but evicting all of them would leave permanent
    mime glyphs until the next manifest load (the reason it was kept whole).
    Both budgets are now DERIVED from `Runtime.maxMemory()` (`cacheBudget`,
    1/16 and 1/32 with clamps) rather than fixed constants — they land on the
    same 8/4 MiB for this app's ~128 MB heap, so it is a scaling fix, not a
    retune. That was the one fair part of "why not just use Glide's cache":
    Glide sizes its memory cache and bitmap pool from the device
    (`MemorySizeCalculator`, left at the default here), so two FIXED budgets sat
    beside a device-aware one and reserved the same megabytes on a 2 GB phone as
    on a 12 GB one.
  - **The list's thumbnails go through GLIDE — there are no hand-rolled bitmap
    caches on this screen any more.** It used to keep TWO `LruCache`s of
    bitmaps, and the argument for them did not survive review: (1) the
    manifest-preview cache existed only to amortise a base64+JPEG decode the
    bind was doing **on the main thread** in `onBindViewHolder` — Glide never
    would have decoded there; (2) the backfill cache held a MANDATORY COPY
    (`GlideHelper.downloadThumbSync` must copy, the pooled original returns to
    the bitmap pool) of an image Glide was **already caching under the Downloads
    list's key**, so one image occupied two budgets; and (3) the privacy line
    the caches were said to protect was already crossed — that same helper puts
    these previews in Glide's disk cache for any file still in Downloads.
    What replaced them:
    - `glide/VaultThumbModel` + `VaultThumbModelLoader` (registered in
      `GlideModule`) load the STORED manifest preview, keyed `ObjectKey(objectId)`
      — server-random and immutable, so it can neither collide nor go stale.
      **Always load it with `DiskCacheStrategy.NONE`**: a cloud-only entry's
      preview has no plaintext counterpart on the device, and writing one would
      create it. The fetch is a base64 decode of bytes already in memory, so a
      disk cache buys nothing anyway.
    - `CloudBackupManager.resolveLocalThumb` returns a **model**, not a Bitmap —
      a `DownloadEntity` (local copy) or a `VaultObjectModel` (cloud-only image).
      The adapter routes a `DownloadEntity` through `GlideHelper.load` so
      signature + options + override match the Downloads list byte-for-byte and
      the row is served from the entry that list already populated.
    - The mime glyph is `placeholder` AND `error`, with `dontAnimate()`. That is
      what preserves the no-flicker property: a cold bind shows exactly the state
      a thumbnail-less entry shows anyway (never a blank, never the previous
      row's image), and a memory-cache hit resolves inside `into()` with no
      placeholder frame. `into()` on the same ImageView cancels the previous
      request, so a recycled holder can't be painted by the old row's load — the
      null branch calls `Glide.clear` for the same reason.
    - The item sheet gets `ARG_LOCAL_PATH` for entries with no stored preview,
      because the list no longer holds a bitmap to hand it (and a Bundle was
      never the right carrier for one).
    Gone with them: `mDecodedThumbs`, `mResolvedThumbs`, `cacheBudget`,
    `trimThumbCache` and the fragment's `TrimMemoryListener` — Glide's own
    device-sized cache and bitmap pool now own every cached bitmap on this
    screen, and there is no second budget to keep in step.

- **Thumbnails reuse the Downloads list's EXACT frame.** `VaultThumbnail.generate`
  takes a `frameUs` and grabs that video frame with `OPTION_NEXT_SYNC` (first
  keyframe at/after the offset — skips the black `t=0` keyframe that
  `CLOSEST_SYNC` snaps back to). The frame is `GlideHelper.thumbnailFrameUs(entity)`
  — the SAME user-chosen/black-skipping position (µs) the list renders — fed
  through `VaultBackupWorker.KEY_FRAME_US` on the backup path and read straight
  from the entity on the display backfill. So the stored preview matches the list
  thumbnail precisely; don't revert to a guessed fixed offset (that's the
  fallback when no entity frame is available). Image → decoded bitmap, audio →
  embedded cover art, else null → the row shows the `MimeTypeThumbnail` fallback.

- **Display-time thumbnail backfill** (`CloudBackupManager.resolveLocalThumb`).
  Entries backed up before previews existed carry no `thumb`. The list fragment
  asks the manager to regenerate one from the **local copy if still present**
  (`DownloadDao.findByNameSize(name, size)` → file path → `VaultThumbnail`), on
  the heavy executor, and slots it into the row (`CloudBackupFileAdapter
  .setResolvedThumb`). **Display-only — the manifest is NOT re-written** (no
  re-upload, no OCC churn); the preview persists only once the file is backed up
  again. Files no longer on disk keep the mime glyph.

- **No duplicate backups — two layers.** (1) The enqueue is
  **`enqueueUniqueWork(KEEP)` keyed by file NAME+SIZE (content), NOT the path**
  (`BaseDownloadFragment`): the same video downloaded twice lands at two different
  paths with the same name+size, so a PATH key let both back up concurrently —
  on-device that meant **4 workers all uploading the same 665 MB file**, spamming
  "setProgressAsync must complete before Result" and making cancel useless. The
  content key matches the engine's own dedup key, so KEEP collapses every backup
  of the same content to ONE worker. (2) Because the start-time `findExisting`
  name+size check still runs against a *pulled snapshot*, the COMMIT is also
  **dedup-checked under the OCC mutate** (`VaultEngine.commitDeduped`): after
  upload it re-pulls the latest manifest and, if another object with the same
  name+size already committed, keeps THAT entry and drops its own just-uploaded
  object as an orphan (`deleteObject`, best-effort) — so the manifest can never
  gain a duplicate even if two uploads still race. Don't rely on KEEP or the
  start-time check alone; the commit-time dedup is the guarantee. **Belt-and-braces:**
  `VaultBackupWorker.publishProgress` skips when `isStopped()` (a cancelled
  worker's WorkSpec is being torn down — a late `setProgressAsync` logs the error
  above) and throttles to whole-percent steps; `observeTransfers` also collapses
  transfer rows by name so a transient multi-worker state shows ONE row.
  `VaultEngine.backupFile` also **backfills a missing `thumb` into the existing
  entry** (cheap manifest re-write, no re-upload) when re-backing up a file that
  predates previews.

- **Orphaned committed object on a commit-conflict — freed, not leaked.** The
  upload order is `createObject` → chunk PUTs → `completeObject` → `commitDeduped`
  (the manifest OCC push). If the push is **cleanly rejected on every OCC retry**
  (`mutateManifest` throws `VaultEngine.ManifestConflictException`), the entry
  DEFINITELY never committed — but `completeObject` already did, so the object is a
  **committed, manifest-unreferenced orphan**: it counts against the account cap /
  is billed, is invisible to the UI (not in the manifest), and NO reaper reclaims
  it (`ReapPending` keys on `state='pending'`; the server can't read the E2E
  manifest to know it's unreferenced). WorkManager's retry then re-uploads a *new*
  object → one orphan leaked per attempt. So `backupFile` catches
  `ManifestConflictException` specifically and best-effort `deleteObject`s the
  just-completed object before rethrowing (the retry re-uploads cleanly).
  **Crucially it does NOT catch a generic `IOException`** (a socket drop mid-push):
  that's AMBIGUOUS — the push may have committed with the response lost — and
  blind-deleting then would leave a **ghost** manifest entry pointing at a deleted
  object. `ManifestConflictException` is the typed "definitely not committed"
  signal (still an `IOException`, so the worker's retry branch is unchanged); the
  ambiguous-lost-push orphan is a rare documented residual (no client-safe cure;
  a future "list my objects" reconcile could close it). Don't widen the catch to
  bare `IOException`.

- **Large-file uploads REFRESH expired presigned URLs mid-flight (the real fix,
  not just a bound).** `create` hands out all chunk PUT URLs under the server's
  short `UploadPresignTTL`; a large file on a slow link outlives it and the later
  chunks 403. `StorageApiClient.putChunk` raises `PresignExpiredException` on a 403
  (distinct from `TransientException`), and `VaultEngine.backupFile` catches it,
  calls `api.refreshUploadUrls(objectId)` (→ `POST /objects/{id}/upload-urls`,
  which re-mints fresh URLs for the still-PENDING object; the server refuses once
  committed), swaps in the fresh URLs, and **retries the SAME chunk** — so a long
  upload resumes instead of re-running from chunk 0. Bounded by `MAX_URL_REFRESHES`
  (200; a refresh covers a whole TTL of chunks, so a real upload needs a handful —
  this is headroom, not a per-chunk cost). A non-403 (`TransientException`) still
  propagates → WorkManager retry, as before. **A PERSISTENT 403 fails fast as
  "presign REJECTED", not expired** (`MAX_SAME_CHUNK_EXPIRIES`, 2 consecutive
  per chunk, reset on success): a URL that 403s seconds after being freshly
  minted cannot have expired — R2 is rejecting the SIGNATURE (VPS clock skew,
  rolled R2 credentials, bucket/endpoint change), which no refresh fixes. The
  old loop burned all 200 refreshes (~400 round-trips) on one chunk and then
  reported the misleading "presign expired" (how a vault smoke-test failure
  first surfaced); the fail-fast throws an `IOException` naming the real cause
  + pointing at `storage-api --r2-check`. One consecutive re-expiry is still
  allowed for the link-so-slow-one-chunk-outlives-the-TTL edge. `putChunk`'s
  403 also carries R2's own error `<Code>` from the response body
  (`SignatureDoesNotMatch` vs an expiry) — a stale pre-write-once APK against
  the header-signing server 403'd every PUT and the bare "presign expired"
  message misread it for a round; the code names the real cause on sight.
- **`putChunk` sends `If-None-Match: *` ONLY when the presign signed it, and
  treats 412 as success (write-once chunks).** The server can SIGN
  `If-None-Match: *` into the chunk PUT presign (its
  `FIREDOWN_STORAGE_WRITE_ONCE_CHUNKS` flag) so R2 rejects a second write to a
  chunk key with 412 and a modified client can't drop the header to overwrite a
  committed chunk. The client mirrors the presign: it sends the header **iff the
  URL's `X-Amz-SignedHeaders` contains `if-none-match`** (`presignSignsIfNoneMatch`
  — the name only appears there when signed). **Sending it UNCONDITIONALLY was a
  bug** (on-device: `403 AccessDenied` on every chunk PUT): when the server did
  NOT sign the header (write-once OFF), an unsigned conditional `If-None-Match`
  is not a benign no-op — R2 rejects it `AccessDenied`. Mirroring the presign
  decouples the client from the server flag (either setting uploads cleanly).
  When signed, the header is sent verbatim (dropping it → 403). A **412** means
  the chunk is already uploaded — a retry after a lost 200, or a refresh URL for
  a chunk we already wrote — so it's treated as success, not an error. This is the client half
  of retiring the server's `ReconcileCommitted` sweep; the server flag stays off
  until R2's honoring of a presigned conditional PUT is verified live.
- **`createObject` DECLARES `chunk_size`, and it must equal what `putChunk`
  actually sends.** The server signs that length into each presigned chunk PUT's
  `Content-Length`, which is the only thing bounding how many bytes those URLs can
  write (an unbound presign accepts anything up to R2's ~5 GiB single-PUT
  maximum regardless of the declared `byte_size`). The value is
  `CHUNK_SIZE + CHUNK_OVERHEAD` — the ciphertext length of a FULL chunk — and the
  server derives the last chunk as `byte_size - (chunk_count-1)*chunk_size`, which
  is exactly what `encryptChunk` produces for the remainder. **If you ever change
  `CHUNK_SIZE` or `CHUNK_OVERHEAD`, this declaration changes with it or every PUT
  403s on a signature mismatch** (the same failure shape as the `If-None-Match`
  episode — `putChunk`'s 403 body carries R2's error `<Code>`, so
  `SignatureDoesNotMatch` names it on sight).
- **Paying a credit invoice from the user's OWN wallet — Nostr Wallet Connect
  (`nwc/`).** The buy screen's Lightning stage shows a BOLT11 + QR, which means
  leaving the app to pay it. NIP-47 closes that: the user connects a wallet
  (Alby Hub, Coinos, Mutiny…) once, and "Pay with connected wallet" settles the
  invoice in place. Entirely OPT-IN — the QR/copy path is untouched and stays
  the default; a user who never connects one sees only a quiet text link.
  - **It does NOT complete the purchase.** `payWithConnectedWallet` only asks
    the wallet to pay; the ALREADY-RUNNING settlement poll observes it and
    drives the state machine to SUCCESS, exactly as when the QR is paid from
    another app. That is what keeps this a shortcut rather than a second,
    parallel purchase implementation — and why `WalletPay` is its own small
    LiveData rather than a new `Phase`.
  - **A timeout is NOT a failed payment** (`buy_credit_wallet_unconfirmed`, and
    the SENT state leaves the button DISABLED). The wallet may settle after we
    stop listening, so the copy says "couldn't confirm … don't pay twice" and
    the poll keeps running. Presenting a timeout as a failure invites paying
    twice for a credit the user may already own. Nothing ever auto-fires: one
    tap, one attempt.
  - **Connecting VERIFIES before it stores** — a real `get_info` over the real
    relay, plus `supportsPayInvoice` on the reply. A parse alone accepts a
    revoked or READ-ONLY connection (a common Alby Hub option) and defers the
    failure to the moment money is being spent.
  - **The connection string is a SPENDING CAPABILITY** and is stored like one:
    `NwcWallet` → `SyncSecrets`'s named-blob API (Keystore-wrapped, in the
    backup-EXCLUDED `secret_shared_prefs`), so a cloud restore onto another
    phone can't carry the user's wallet with it. Never log it; `displayLabel()`
    is the loggable form.
  - **The crypto is hand-rolled and pinned to the published vectors —
    `NwcCryptoTest` is not optional.** BIP-340 Schnorr (BouncyCastle ships
    secp256k1 math but no BIP-340 primitive), NIP-04, and the NIP-01 canonical
    event form. **Every failure mode here is silent**: a wrong signature, a
    HASHED-instead-of-raw ECDH secret (what every ECDH helper gives you by
    default, and what NIP-04 must not use), or one character escaped
    differently in the canonical form all produce the same symptom — the wallet
    ignores the request and the user sees a timeout, with nothing pointing at
    the layer that is wrong. So the test carries the official
    `bip-0340/test-vectors.csv` rows verbatim (the 15 with 32-byte messages;
    Nostr only ever signs an event id) and asserts exact signature BYTES, not
    merely that they verify. It is a plain JVM unit test — everything under
    test is Android-free on purpose.
  - **It mirrors the mint's own Go client** (firedown-api `internal/mint/
    payment/nwc`) — same protocol, same canonical form, opposite end
    (`pay_invoice` here, `make_invoice`/`lookup_invoice` there). Keep them in
    step; a divergence shows up only as a wallet that never answers.
  - **The scanner is the P2P one AGAIN** — `P2pScanFragment` registered as a
    `<dialog>` with `ARG_TITLE_RES`, result consumed with `set(key, null)`
    (never `remove`). A scan lands back on the connect dialog PREFILLED rather
    than connecting straight through: a QR is a bearer secret pointed at a
    camera. Third caller of that screen now; don't fork it.
  - **The connect/manage link must NOT take its ink from
    `borderlessButtonStyle`'s default.** That default is `colorPrimary`, and
    the brand coral measures **2.56:1** on the light surface — below the 4.5:1
    text floor — while reading a comfortable 6.92:1 in dark. It shipped and
    looked perfectly fine on a dark-theme device, which is the whole trap: this
    is the SAME defect class as the home pill's "VIEW" label (1.68:1 light) and
    the transfer tile's state ink, i.e. *a defect that flips with the theme on
    a surface whose ground does not*. No coral clears 4.5:1 in both themes
    (`#CC524A` is 4.11/4.31; going darker fixes light and breaks dark), so the
    fix is not a better coral — it is `?attr/colorOnSurfaceVariant` (8.90:1 /
    10.90:1), which is what a quiet tertiary link should have been anyway. Any
    borderless/text button added on a themed surface needs an explicit
    `textColor` for the same reason.
  - **The manage link NAMES AN ACTION** ("Change or disconnect wallet"), not a
    state. It first shipped as "Connected wallet", which was two mistakes: a
    button labelled like a status line doesn't read as tappable, and it merely
    repeated the wallet identity already printed under the pay button. The
    status line above states WHICH wallet; the link is the door.
  - **Ceiling:** NIP-47 is moving toward NIP-44 encryption and kind-23197
    notifications. NIP-04 is what current wallets still accept. A wallet that
    rejects a request with an encryption error is the signal to add NIP-44 —
    not a transport bug.
- **A paid credit is NEVER discarded on a non-terminal error.**
  `PendingPurchase` is the only copy of the blinding secret + signature, so
  `BuyCreditViewModel` clears it ONLY on outcomes that prove no credit is owed:
  `credit-spent` at redeem (already applied), and `quote-expired`/`quote-refunded`
  at issue (`isDeadQuote` — no money taken, or it went back). Everything else
  keeps the record for `resumePendingIfAny`. This matters because
  `StorageApiClient` maps EVERY 4xx except 429 to `FatalException`, and some of
  those are transient — `unknown-keyset` in particular just means storage's
  mint-key cache hasn't caught up with a newly minted keyset (now a 503
  server-side, but old servers exist) — so the old blanket `clear()` silently
  destroyed money the user had already paid, unrecoverably. Don't widen the clear
  back to "any fatal".
- **Backup worker still has a retry ceiling** (`VaultBackupWorker.MAX_RUN_ATTEMPTS`,
  10, gated on `getRunAttemptCount()`) as the backstop for a backup that keeps
  failing for OTHER reasons (persistent network loss, a wedged manifest, or a chunk
  that 403s even with a fresh URL past `MAX_URL_REFRESHES`) — so it fails cleanly
  instead of re-uploading forever (battery + bandwidth churn + a fresh pending
  object per attempt). With URL-refresh above, the large-file case no longer relies
  on this ceiling. The remaining ceiling-worthy cases are genuine failures. (A
  future full per-chunk lazy-presign — mint each URL just before its PUT, tiny TTL
  — would also shrink the server-side overwrite window to near-zero.)

- **Restore skips a file already in Downloads.** `VaultRestoreWorker` probes
  `findByNameSize` (honoured only when the local file still **exists** on disk)
  and returns a no-op success with `KEY_ALREADY_PRESENT` instead of writing a
  uniquely-named duplicate; the list shows "Already in your downloads". Otherwise
  it restores into `Download/Firedown` (unique destination) and inserts a FINISHED
  `DownloadEntity` via the same repository every download uses.

- **`SavedStateHandle.remove(key)` DETACHES the cached LiveData — consume with
  `set(key, null)` instead.** The per-item sheet (`CloudBackupItemSheetDialogFragment`)
  returns Restore/Remove through the `NavBackStackEntry` saved-state handle. The
  list fragment's observer **must not** consume the result with `remove(RESULT)`:
  that detaches the `MutableLiveData` the handle caches for that key, so the
  observer stops receiving EVERY subsequent result (symptom: the 2nd remove/restore
  silently does nothing). Consume with `getSavedStateHandle().set(RESULT, null)`
  and guard the null tick — the same LiveData stays attached.

- **Optimistic remove** (`CloudBackupListFragment.removeOptimistic`): the row
  disappears immediately; the slow server delete runs in the background and only
  the failure path re-inserts the row (error snackbar). Closes the ~10s "nothing
  happens then a snackbar" gap.
  - **A delete-in-flight is tracked in `mPendingRemovals` so a racing `load()`
    can't RESURRECT the row.** The optimistic remove drops the row from the UI
    before the server delete's OCC push commits, so a `load()` that lands in that
    window (a transfer-finished reload, or re-entering the screen) pulls a manifest
    that STILL contains the entry and would re-add it — a ghost row for a file
    that's actually being deleted. `removeOptimistic`/`deleteSelected` add the
    objectId(s) to `mPendingRemovals`; `load()` filters them out of its result.
    `mLoadGen` only orders load-vs-load; this orders load-vs-delete. Main-thread
    only (all `CloudBackupManager` callbacks `main.post`), so the `Set` needs no
    synchronization.
  - **Clear the guard on delete-FAILURE, but let `load()` reconcile a delete-
    SUCCESS — never clear it eagerly in the success callback.** Eagerly clearing on
    success reopens the same ghost by the REVERSE ordering: a `load()` whose pull
    PRE-dated the delete can run *after* the success callback cleared the guard and
    re-add the (stale-pull) row. So the success path leaves the id in the set, and
    `load()` reconciles it with `mPendingRemovals.retainAll(pulledIds)` — an id the
    fresh pull no longer lists is confirmed gone and dropped; a still-listed id (a
    stale pull, or a failed delete that never committed) stays guarded. Object ids
    are server-random per create, so a cleared id can never wrongly match a future
    entry, and a lingering guarded id (no load since the delete) is a harmless
    no-op filter. Only delete-FAILURE clears the id directly (+ restores the row).
  - **Batch delete restores rows ADDITIVELY on failure, never via `load()` or a
    snapshot-clobber.** The batch path used to `load()` to "resync" — but OFFLINE
    (the common failure) that `load()` also fails and leaves the rows wrongly
    pruned; a snapshot-restore (`mEntries.clear(); addAll(snapshot)`) instead
    CLOBBERS a concurrent `load()`/finished-transfer that changed the list
    meanwhile. `deleteSelected` re-adds only the targets actually missing
    (`findEntry == null`) and clears their guards. Keep the two delete paths
    symmetric — never rely on a network `load()` to undo an optimistic removal, and
    never clobber the live list.

- **The Backups list header is TEXT ONLY — the top-up door is a toolbar
  OVERFLOW item, and it is the only thing that puts a ⋮ on that screen.**
  Header = inventory line ("14 files · 2.1 GB", prefixed "Backing up… · "
  during a transfer) + a status/trust line ("≈ 1 year of coverage · encrypted
  end-to-end" / "Read-only · …" / the beta's "of 11 GB included · …", status
  first so a wrap moves the boilerplate tail rather than the fact). "Add
  storage credit" lives in `menu_cloud_backup.xml` with `showAsAction="never"`,
  beside a second overflow entry, **"Cloud"** → the merged Cloud screen. That
  second entry is the ONLY route from this list to Cloud: for a set-up account
  the Downloads overflow lands on this list rather than on Cloud, so the
  recovery code, the two erasure rows and the FAQ were otherwise reachable only
  from the Home or Browser popup — from the very screen that most invites them,
  since it is where you are looking at the files you might erase. It is a
  separate entry rather than a repointing of "Add storage credit": an item that
  names an action has to perform it, and the coverage figure the Cloud hero
  would add is already on this screen's own header line. The nav action is a
  plain push (no `popUpTo`) so Back returns to the list, not to the caller.
  - **Why the door left the header.** It used to be a trailing coral text
    button in the header row, and on a 360dp phone "Add storage credit ›" ate
    ~170dp of ~340dp — the trust line truncated to "…encrypted e…"
    (on-device). **No width split fixes that**: a long translatable BUTTON and
    a long translatable SUBTITLE are two full-width demands that grow
    TOGETHER, so the worst case is always simultaneous (German
    "Speicherguthaben hinzufügen" beside "Ende-zu-Ende-verschlüsselt"), and
    line 1's transfer prefix makes it no safer a neighbour. Stacking the
    button on its own row was tried first and works, but costs ~48dp; moving
    the ACTION to the toolbar is better — it puts it with the screen's other
    actions (search, grid), and leaves only TEXT in the header, which can
    **wrap** where a button label cannot (`maxLines="2"` on line 2 degrades by
    growing, never by hiding a fact).
  - **STATUS stays in the header; only the ACTION moved.** The old chip was
    two things at once — a coverage/"Read-only" readout AND the buy door. A
    menu item is invisible until opened, and status you have to go looking for
    is not status (the read-only grace state especially). So `headerStatus()`
    keeps the phrase on line 2 and the overflow owns the door. Don't "finish
    the job" by moving the status in too.
  - **The status text carries NO colour.** The chip tinted its grace label
    with `backup_warning`, which is **2.09:1 on the light surface** — the
    state most needing to be read was the least readable, the same defect
    class as the old home pill's 1.37:1. The word "Read-only" carries the
    state on its own (WCAG 1.4.1 requires that regardless), so the fix was to
    drop the tint, not to hunt for a legible amber. **Watch item:**
    `backup_warning`'s other uses (the Cloud status hero, the credit meter)
    are inks on light surfaces too and have the same measurement — they were
    left alone here, but they are not safe.
- **UI parity with Downloads.** The backed-up-files row
  (`item_cloud_backup_file.xml` + `CloudBackupFileAdapter`) faithfully mirrors
  `fragment_download_item.xml`: `MaterialCardView` root (transparent until
  pressed, no stroke/elevation), 8dp-inset `ConstraintLayout`, the same
  `list_download_image` thumbnail (`mask_image_rounded` + `centerCrop` +
  `setClipToOutline(true)`) and **three** text lines — name / `MIME · size` /
  relative date (mime chip = `MimePrimaryLabel` via `FileUriHelper.getLongMimeText`,
  carrying its own trailing `· `). Three lines on purpose: two lines next to the
  64dp thumbnail leave dead space above/below the centred text — the third (date)
  line fills the row like the Downloads status line does. The **empty state
  mirrors `recycler_empty_layout` exactly** — same centered ConstraintLayout
  (illustration bottom at the vertical centre — `ill_cloud`, the restored
  P2P-send header art; this screen IS the cloud — message below) and
  same text styling (`sans-serif-medium`, `colorOnSurface`); don't substitute a
  `TextAppearance` style / `colorOnSurfaceVariant`.

- **The home cloud states are told apart by SHAPE and WORDS — never by a
  semantic hue. The chip and the card are `surfaceContainerHigh`; the resting
  line is naked text.**
  `HomeFragment.applyBackupPill` renders FOUR states on THREE surfaces — the
  read-only grace DEADLINE (metered credit out) on the CARD, "Backing up…"
  (RUNNING) and "Waiting to back up" (enqueued only) on the transfer CHIP
  (`home_backup_pill`), and the RESTING "6.6 GB backed up" total on the QUIET
  LINE (`home_backup_rest`) — and hides all of them otherwise. The deadline
  deliberately beats both transfer states: in grace every upload 402s at
  create, so a doomed queued backup rendering "Backing up…" would hide the one
  actionable fact. **At most ONE of chip / line / card is ever VISIBLE, and
  every branch sets ALL THREE** (they are persistent views that flip, so a
  one-sided set leaves the previous state on screen).
  - **The RESTING rung is why this slot, and not a third subtitle counter.**
    A "N GB backed up" counter was built on the hero subtitle line and
    REVERTED: three chips + two dots overran the 360dp line at real values, the
    `Flow` wrapped, and a separator (an ordinary Flow child) was left stranded
    at the end of row one — breaking the line's own "ONE line, never a second
    row" rule, whose stated reason is that a state-dependent extra row shifts
    the flame's resting position. The deeper reason it belongs here: the two
    subtitle counters are LOCAL (Room / uBlock — correct instantly and
    offline) while the cloud total is a NETWORK pull, and **a fixed one-line
    hero cannot host a value that arrives late** — every arrival is a reflow
    directly under the wordmark. A pill is built to appear, so a late value
    looks like the component working. Don't re-add it to the subtitle.
  - **Gating differs per rung, and the difference is the point.** The three
    non-resting states are EVIDENCE-based (a paused quota, a live tagged
    WorkInfo) and that evidence exists only because the user engaged with the
    feature — which is why "Backing up…" is deliberately **not** `isSetUp()`-
    gated: the very FIRST backup runs before `markEnabled` lands, and gating it
    would blank the pill for exactly the transfer that most wants reporting.
    The resting rung is a standing CLAIM with no such evidence, so it takes the
    strict gate: `isSetUp()` read **live at render** (never a cached copy — the
    erase path must be able to turn it off) **AND** a known non-zero total. A
    fresh install fails both; a set-up account that has backed up nothing shows
    nothing rather than "0 B".
  - **`mCloudTotalBytes` is -1 for UNKNOWN, and that is load-bearing twice.**
    A failed/absent pull renders no pill instead of "0 B" (the total is the one
    cloud fact that can't be derived locally, so an unknown stays silent), and
    a `loadStatus` that returns -1 KEEPS the previous figure so an offline
    resume can't blink the pill out and back. **The one case that must NOT keep
    it: `lastStatus()` returning null.** `deleteAllData` nulls the manager's
    cached snapshot but deliberately leaves `CLOUD_BACKUP_ENABLED` SET (the
    surviving paid balance is reachable only via the code), so `isSetUp()` is
    still true after an erase — a carried-over total would render as a
    confident, wrong "6.6 GB backed up" until the next pull, and for the whole
    session offline. A dropped cache IS the "don't trust the old number"
    signal, so `refreshCloudStatus` resets to -1 there.
  - **The RESTING state is a QUIET LINE, not the chip** (`home_backup_rest` —
    demoted after the maintainer flagged the resting chip as "the most
    important item on the home fragment" on two devices): the counters' own
    transparent-card construction, `onSurfaceVariant` ink, 12sp (one notch
    under the counters' 13sp, still the M3 floor), a 14dp plain cloud tinted
    the same, inner `minHeight=48dp` for the touch target. The chip treatment
    carried the only fill, the only icon and the only bounded shape in the
    brand stack — and sat in the lockup's CTA position (flame → wordmark →
    tagline → filled rounded shape reads as a hero with a button under it), so
    a standing total outranked everything on a deliberately bare screen.
    Tappable naked text is already this screen's contract (the two counters).
    The glyph is `cloud_24`, NOT `cloud_done_24` (the check asserts "all
    backed up" — a coverage claim a byte total can't make) and NOT
    `ic_cloud_upload_24` (an upload arrow on a total reads as a stuck
    transfer). **There is no rung below this one** — if the line still reads
    loud on-device, the next move is deleting the resting state (the Backups
    doors in the Downloads overflow and the Cloud screen remain), not a
    smaller chip.
  - **The CALM slot is HEIGHT-RESERVED (`home_backup_slot`) — the flame-shift
    fix.** `home_brand_mark` is centred by gravity
    (`layout_gravity="center_vertical"`), so a GONE→VISIBLE arrival in this
    slot grew the block and shifted the flame's resting position by ~half the
    slot — the exact defect the subtitle's "ONE line, never a second row" rule
    names, one slot lower — and the resting figure is a late NETWORK value, so
    it fired on ~every resume of a set-up account. The slot is a fixed-height
    (48dp) FrameLayout kept VISIBLE with INVISIBLE children whenever
    `isSetUp()`, so the resting line's arrival is a pure ~300 ms alpha fade
    (`fadeInRestLine` — no translation; skipped entirely when
    `ValueAnimator.areAnimatorsEnabled()` is false, i.e. animations off) with
    zero reflow. A fresh install keeps the slot GONE — the bare home is
    unchanged — and the grace CARD lives OUTSIDE the slot and may move the
    block (an alarm is allowed to). Chip and line are TWO sibling views
    flipped by visibility, not one restyled view: the presentations differ in
    ground/radius/padding/icon/type/ink, and per-state restyling is exactly
    the one-sided-set trap the visibility contract above exists for.
  - **The whole slot is behind ONE display preference, and it is deliberately
    NOT a "disable Cloud Backup" switch.** `SETTINGS_CLOUD_HOME_STATUS`
    (default TRUE, a self-persisting switch on the Cloud screen under the
    Backups row, set-up gated with it) gates `applyBackupPill` and nothing
    else — no cloud state changes, so it can never hide the Backups row, the
    Downloads-overflow routing, or a paid balance. The obvious alternative — a
    toggle that clears `CLOUD_BACKUP_ENABLED` — would do exactly that: hide the
    user's own doors to files still on the server next to their credit, the
    stranding `deleteAllData` is written to avoid (which is why that erase
    deliberately leaves the flag set). It gates the grace CARD too, not just
    the calm rungs: a control that says "show backup status on home" and still
    paints one would be lying, and the deadline is on the Cloud screen and the
    Backups list header regardless. Read LIVE per render, so returning from
    Settings applies it on the next resume with no observer.
  - **There is still no user-facing OFF switch for Cloud Backup itself, and
    that is intended** (it's action-driven — see "Shared identity, no on/off
    switch"). The surface is derived state, so it clears by having nothing to
    report: "Delete backed-up files" leaves the flag set but zeroes the total,
    so every rung falls away; a metered spent+empty account then auto-retires
    the flag on the next `loadStatus`. `SyncManager.disable` deliberately
    refuses to wipe the shared recovery code while `CLOUD_BACKUP_ENABLED` is
    set, so signing out of bookmarks can't lock a user out of backed-up
    downloads. No state is a dead end: paused clears by topping up or deleting
    the files, transfers by cancelling in the Backups list.
  - **The CHIP** (`home_backup_pill`) — a small centred filled chip, upload
    glyph, no action label, tap → the Backups list. It carries ONLY the two
    TRANSFER states ("Backing up…", "Waiting to back up") — fill is earned by
    WORK, transient and self-clearing, never by a standing state; the resting
    total is the quiet line above and the card carries the alarm. Its ground,
    ink and glyph are static XML now (the old "applyBackupPill repaints the
    ground" comment predated the attention state moving to the card). Note the
    ethos: home is no longer bare-at-rest for a SET-UP account (it keeps a
    permanent, quiet door to Backups), but a fresh install is exactly as bare
    as before.
  - **The deadline is a CARD** (`home_backup_card`) — full-width, two lines,
    `cloud_off_24`, "TOP UP" → the Cloud status screen (the only state whose
    tap goes anywhere other than the Backups list). It earns the extra weight
    from its CONTENT, not a colour: title "Backup paused" plus a **countdown**
    detail line (`home_cloud_grace_days`, a plural) computed from the quota's
    `graceUntil`. "3 days left before your files are removed" is what makes
    the state actionable where "Paused" only named it, and it needs no API
    change — the server already sends `grace_until`. It falls back to the
    title alone when that field is missing or unparseable (older server, clock
    skew), and clamps to ≥1 day so a past deadline reads "1 day" rather than a
    negative.
  - **Margins:** the card carries `layout_marginStart/End="@dimen/address_bar_inset"`
    so it lines up with the address bar above it, and its action button is
    `wrap_content` beside a weighted text column — a longer translation
    shrinks the text and wraps to the detail line's `maxLines="2"` instead of
    squeezing the verb out. The pill stays `wrap_content` + centred.
  - **Do NOT give either surface a semantic (amber/warning) container.** That
    shipped, and it was measured out again: on the light home the amber ground
    is **ΔE 30.7** from the page where every other elevated surface on that
    screen sits at 2–12, so a state that is *informational* — nothing is lost
    yet, the user has 30 days — wore the loudest treatment in the app. It also
    contradicted itself: the argument for the amber was that ink alone can't
    carry attention (the hardcoded `backup_warning` on the old peach pill was
    **1.37:1** in light theme, genuinely invisible), but the answer to an
    unreadable ink is a readable ink, not an escalated ground. Neutral
    (`surfaceContainerHigh` + `onSurface`) reads at **ΔE 6.3 / 11.1** and lets
    the shape+copy do the ranking. `backup_warning` itself is still correct
    where it's an ink on a SURFACE (the Cloud status hero, the credit meter);
    `backup_warning_container`/`_on_container` were deleted.
  - **The PILL has NO action label; the CARD keeps "TOP UP".** The pill's read
    "VIEW" on every state, so it conveyed only "tappable" — which the chip
    shape, the ripple, and the two subtitle counters right above it (both tap
    targets, neither with a verb or a chevron) already convey. It also cost
    ~50dp on a `wrap_content` pill that must fit "Waiting to back up" in every
    locale. It had already been trimmed once: hardcoded `?attr/colorPrimary`
    measured **1.68:1** light / **3.76:1** dark — the one word telling you the
    surface was tappable was the least readable thing on it — and the fix note
    was "bold + allCaps already say action". If the shape says it and the
    weight says it, the word is the third copy. The card's survives because it
    is a real verb for a real decision (spend money, against a deadline) and
    the only state whose tap goes anywhere other than the Backups list, so the
    asymmetry sharpens the pill/card split rather than blurring it. Its label
    is set in XML with a resolved `textColor` (no `tools:` placeholder now that
    only one surface carries one).
  - **Restores never reach the pill** — `hasBackupTag` filters WorkInfos to
    `VaultBackupWorker.TAG_NAME`, so a restore shows only as a live download row
    in the Downloads list. There is also **no error state** (a failed backup is
    a row in the Backups list + a snackbar) and **no reaped state** (after the
    server reap the 0-files reconcile retires the flag, so `isSetUp()` goes
    false and the pill simply hides). Those three absences are deliberate;
    "your backups were deleted" is a genuine gap, not an oversight to patch
    into this pill. (The reaped case is covered twice over now: the flag
    retires AND the total goes 0, so the resting rung can't outlive the data.)
- **The Backups list is sorted NEWEST BACKUP FIRST, on `VaultEntry.backedUpAt`.**
  The manifest is append-ordered (`VaultEngine.addToManifest` does
  `entries.add`), so the raw list is oldest-first and a fresh backup landed at
  the BOTTOM — the opposite of what you expect after pressing "Back up to
  cloud". `CloudBackupManager.sortNewestFirst` runs at the one `loadEntries`
  choke point (not in the fragment, so every consumer gets the same order).
  - **`backedUpAt` is NOT `downloadedAt` and the two must stay separate.**
    `downloadedAt` is the local download's own date and deliberately so: a
    restored file has to land in the same Downloads date section the original
    sat in (stamping backup time there once made restores jump to "Last 7
    days"). Sorting the Backups list on it would push a clip downloaded last
    year to the bottom of the very list that just gained it.
  - **The sort is reverse-then-stable, because legacy entries carry 0.**
    Reverse the manifest (append order = backup order, so reversed IS
    newest-first for legacy rows), then stable-sort by `backedUpAt` descending;
    Java's stable sort keeps the reversed order among the equal 0s. Correct by
    construction, not luck: a timestamp can only exist on an entry committed
    after this shipped, so every 0-valued entry genuinely is older.
  - The field is written to the manifest JSON **only when non-zero**, so a
    manifest of purely legacy entries serializes byte-identically and the OCC
    version doesn't churn. The thumb/origin **repair** path
    (`VaultEngine.findExisting`) carries the ORIGINAL `backedUpAt` through —
    re-backing-up an old file must not send it to the top, and `addToManifest`
    moving it to the end of the array is harmless now that order isn't the
    sort key. The three transient `new VaultEntry(...)` 9-arg call sites
    (`VaultRestoreWorker`, `VaultObjectModelLoader`,
    `CloudBackupStreamActivity`) build read-only entries that never enter the
    manifest, so 0 is right there.
- **Per-item upload progress (like the Downloads list) + cancel.** An upload in
  progress isn't in the manifest yet, so it renders as its own row at the TOP of
  the list (`CloudBackupFileAdapter` TYPE_TRANSFER, `item_cloud_backup_transfer.xml`)
  with a **determinate** `LinearProgressIndicator` + percent + a cancel button —
  the same shape as an in-flight Downloads row (incl. the indicator=primary /
  track=primary@0x33 colours). The row is **always determinate with the percent
  shown** (0% before the first byte report) — NOT indeterminate-until-first-chunk:
  hiding the percent when `done==0` shifted the bar's left margin frame-to-frame.
  Determinate because the bytes are
  reported end-to-end: `VaultEngine.backupFile(..., ProgressListener)` fires
  per-chunk → `VaultBackupWorker` republishes them via `setProgressAsync`
  (`KEY_NAME`/`KEY_MIME`/`KEY_PROGRESS_DONE`/`_TOTAL`) →
  `CloudBackupListFragment.observeTransfers` reads `WorkInfo.getProgress()` and
  builds a `Transfer` row. **The worker MUST drain the last `setProgressAsync`
  future before returning a `Result`** (`awaitLastProgress()` — keep the future,
  `.get()` it before every return) or WorkManager throws "Calls to
  setProgressAsync() must complete before a ListenableWorker signals completion"
  (the final per-chunk update races the return). **Don't revert to an indeterminate banner** — the bar
  was indeterminate only because nothing reported bytes. Only uploads of NEW files
  get a row: a transfer whose name is already a committed entry (`isCommitted` — a
  re-backup or a restore) keeps its existing row, no duplicate progress row.
  Cancel = `WorkManager.cancelWorkById` (the partial server object is orphaned but
  harmless — `completeObject`/the manifest write never ran). `setTransfers`
  rebinds ONLY the transfer rows on a progress tick (count unchanged →
  `notifyItemRangeChanged(0, n)`), so committed rows below don't re-decode their
  thumbnails every byte update. On the active→idle transition the fragment
  `load()`s the manifest so the finished file appears as a committed row.
  - **ONE `TransferVH` serves BOTH layouts, and they sit on OPPOSITE GROUNDS —
    never resolve the state line's ink from a theme attr at bind time.** The
    list row's state text is on the theme surface; the GRID tile's is over the
    fixed dark `#4A2120` mime-fallback ground (or a thumbnail), which is why
    that layout declares `#E0FFFFFF` (10.3:1). `bind()` used to stomp both with
    `colorOnSurfaceVariant`, which measures **1.47:1** on that ground in LIGHT
    theme and 8.06:1 in dark — so "Backing up…" was legible at night and
    invisible by day (reported on-device; the tell is a defect that flips with
    the theme on a surface whose ground does NOT). The failure branch had the
    identical bug via `colorError` (**1.95:1** light / 6.16:1 dark). Both inks
    are now resolved ONCE in the holder ctor: NORMAL is
    `state.getCurrentTextColor()` — whatever that layout declared, so each
    ground keeps its own correct ink — and ERROR is picked per surface by the
    `grid` ctor flag (`colorPrimaryContainer` on the tile, 5.83:1 light /
    4.69:1 dark — the same on-dark-ground ink `DownloadItemAdapter`'s grid
    `status_text` uses; the real `colorError` on the list). The `grid` flag is
    load-bearing, not cosmetic. General rule for any holder shared by a list
    row and a grid tile: a `?attr` ink is only correct on the surface-grounded
    one — see the twin `Theme.FireDown.More.Button` note under UI conventions.
- **`LCEERecyclerView` + a non-serial network executor.** The screen uses the
  app-standard `LCEERecyclerView` (`fragment_cloud_backup_files.xml` is just that
  view) for the loading spinner / content / empty-illustration states — same as
  Downloads/Bookmarks/History — which ALSO `disableChangeAnimations()` in its
  ctor, so per-item progress `notifyItemRangeChanged` ticks DON'T blink (don't
  hand-roll a recycler + TextView empty state again; the no-blink came free with
  LCEE). `render()` maps to `showLoading()` (initial fetch only — a refresh with
  rows present keeps the list, no spinner flash) / `hideAll()` (rows or an active
  transfer) / `showEmpty()`. Crucially, `CloudBackupManager`'s network ops
  (manifest pull/push, object delete) run on a **dedicated cached `netExecutor`,
  NOT `@Qualifiers.DiskIO`/`HeavyIO`** (both single-thread serial lanes): on a
  serial lane a list load queued behind two big-file deletes waited for both
  deletes' round-trips (the "dead slow Loading…" after deleting), and it also
  wrongly blocked the app's DB-write lane. The pool lets loads and deletes run
  concurrently (OCC handles any manifest-mutation conflict). It is **BOUNDED at 3
  threads** (`allowCoreThreadTimeOut`, 0 idle) — an unbounded cached pool let a big
  multi-select spawn a thread per delete and hammer the manifest with concurrent
  OCC mutations. **Concurrency invariants for this screen (each fixed a real bug):**
  - **`load()` is generation-guarded** (`mLoadGen`): two concurrent loads (a
    post-delete resync + a transfer-finished reload) complete in *network* order,
    not call order, so a stale earlier pull would otherwise overwrite a newer list
    (deleted entries reappear). The callback drops a result whose `gen != mLoadGen`.
  - **Batch delete = ONE manifest mutation** (`VaultEngine.deleteEntries` /
    `CloudBackupManager.deleteEntries`): a multi-select delete must NOT fire N
    concurrent `mutateManifest` calls — they contend on the manifest version and
    some exhaust the retries. `MAX_CONFLICT_RETRIES` is 8 (writers now run
    concurrently: a net-pool delete can race a worker-thread backup commit).
  - **Delete is unreference-FIRST**: remove from the manifest, THEN free the object
    (best-effort). The reverse risks a GHOST entry pointing at a deleted object;
    a failed object delete just leaks quota (server GC).
- **The Backups list is a NETWORK-backed list, and its affordances follow from
  that — four were added together after an audit against the Downloads list.**
  - **Pull-to-refresh** (`cb_swipe`). Downloads needs none (local DB + Room
    invalidation); this screen pulls a manifest, so a backup made on another
    device only appeared after leaving and re-entering. The
    `SwipeRefreshLayout` MUST carry an `setOnChildScrollUpCallback` pointed at
    the inner RecyclerView — its direct child is the LCEE container, not the
    scrollable view, so the default callback would fire a refresh mid-list.
    `stopRefreshing()` runs on BOTH load outcomes; a failed refresh that leaves
    the spinner turning reads as a hang.
  - **Batch restore + select-all** (`menu_cloud_backup_action.xml`, this
    screen's own selection menu rather than the shared `menu_action.xml`).
    Restoring is the whole point of cloud backup and was reachable only ONE
    FILE AT A TIME through the item sheet, while the destructive batch action
    already existed — an inverted asymmetry. Batch restore is deliberately
    NOT dialog-confirmed (constructive and reversible; the count + size are in
    the toolbar at the moment of the tap), unlike batch delete. `enqueueRestore`
    is split from `startRestore` so N restores don't fire N snackbars and N
    per-work observers. Select-all TOGGLES, and clearing drops to zero
    selected → `refreshSelection()` exits selection, the same as unticking the
    last row.
  - **The cloud-only fact lives at the REMOVE decision, NOT on the row — and
    the per-row marker is not to be reinstated in either direction.** It is
    still resolved in ONE batch per manifest load
    (`CloudBackupManager.resolveCloudOnly`) — never per bound row, which would
    re-query on every scroll and selection tick — but the fragment now KEEPS
    the set (`mCloudOnly`) instead of handing it to the adapter, and spends it
    in the two places a removal is decided: the item sheet's `cb_sheet_only_copy`
    line under its Remove row (`ARG_CLOUD_ONLY`), and the batch-delete
    confirmation, which appends a COUNT of last copies
    (`cloud_backup_delete_last_copies`) and only when the selection actually
    contains one.
    **History, and why the row marker died:** it shipped as a
    "· Not on this device" tail on the date line, argued for as the
    decision-relevant AND *rarer* state. The rarity claim was simply false —
    on an established install almost every backed-up file has since been
    cleared from Downloads, so it rendered on nearly every row (8 of 9 on the
    reporter's screen), and a marker present on ~90% of rows carries no
    information. That is the identical argument that keeps the OPPOSITE ("also
    on this device") badge off this list, so the row had no correct marker in
    either direction; which one is rarer is a property of the install, and a
    rule keyed to one install's data shape is wrong by construction (the same
    objection that killed the grid's lone-tile layouts). It also read as an
    asymmetry: silence for one state, words for the other. The lesson worth
    keeping: **a state worth warning about is not automatically worth
    labelling everywhere** — put it where it changes a decision, once. If a
    row here ever earns a glyph, the earned one is failed/stale backup state,
    which this surface still has no indicator for.
    Unchanged from the original: readability is probed with
    `RestoredFileAccess.openableUri`, NOT `File.exists()` — exists() is false
    for a readable foreign-owned restored file and would call a present file
    missing — and a lookup that THROWS leaves the id OUT of the set, so an
    unknown degrades to the plain wording and never to a false "only copy"
    claim. Single remove still has no confirmation dialog (it is optimistic by
    design), which is why the sheet carries the line rather than a new prompt.
  - **Removing an entry whose restore is IN FLIGHT warns first, then cancels
    that restore deterministically.** Restores deliberately don't render on this
    screen (they show as a live download row in the Downloads list), so a file
    being restored looked completely idle here — selectable and removable with
    no hint. Removing it was silent AND nondeterministic: `VaultRestoreWorker`
    carries its inputs (`objectId`/`wrappedDek`/`chunkCount`) and never re-reads
    the manifest, so it only discovered the removal when its next chunk GET 404'd
    — and whether it survived depended on how much had already downloaded. The
    failure then surfaced as a bare ERROR row in a DIFFERENT screen with no
    stated cause. Now: `enqueueRestore` tags the work
    `VaultRestoreWorker.TAG_OBJECT + objectId` (WorkInfo exposes tags, not input
    data — the same trick as the backup worker's `bname:`), the existing
    WorkInfo observer rebuilds `mRestoringObjectIds` on every emission (full set
    per tag, so a finished restore is simply absent — no removal bookkeeping),
    and BOTH remove paths consult it. The batch confirmation counts restores
    SEPARATELY from last copies — the two are independent (a file can be either,
    both or neither), so one merged sentence would misreport a mixed selection —
    and single remove, which is otherwise deliberately unconfirmed and
    optimistic, gains a confirmation for this one case only. `cancelRestores`
    runs BEFORE the manifest mutation + object free, which is what makes the
    outcome the same every time instead of a race. **`VaultRestoreWorker`'s
    IOException branch must keep its `isStopped()` guard**: a cancelled worker
    never runs its retry, so returning `Result.retry()` there would strand the
    Downloads row at PROGRESS forever — it resolves to ERROR instead.
  - **Selected size in the toolbar** ("N selected · 1.2 GB") — the number the
    user is actually deciding on. Composed from the existing string, no new
    translation.
- **The Backups screen has a ViewModel (`CloudBackupListViewModel`) — state
  does NOT live on the fragment.** It previously did: the manifest, the
  load/error flags, the in-flight-delete guard and the selection were all
  fragment fields, so every rotation destroyed them and RE-PULLED the manifest
  over the network. The VM owns entries + load state + status + cloud-only +
  selection, published as ONE `State` snapshot so the fragment can never render
  a torn combination (loading=false with the previous list still in place).
  `onViewCreated` only loads when the VM is fresh — re-pulling there would put
  the network straight back in the rotation path.
  - **The generation guard did NOT go away**, it moved. It is tempting to say a
    single observed stream subsumes it; that is true of a Flow, but
    `CloudBackupManager` is CALLBACK-based, so two concurrent pulls still
    complete in network order and a stale one would still overwrite a newer
    list. Same for `PendingRemovals` — both now live in the VM, which is their
    right home.
  - **Search and grid deliberately stayed on the fragment**: the search field is
    part of the borrowed activity toolbar and is torn down with the view anyway,
    and the grid choice is persisted in prefs. Neither is state a rotation loses.
  - **The adapter is now a pure renderer** — it is HANDED `submit()` /
    `setSelection()` / `setActionMode()` / `setCloudOnly()` and owns no
    selection state. That is what let `setActionMode`'s blanket
    `notifyDataSetChanged` become a bounded `notifyItemRangeChanged` over the
    committed rows (transfer rows show a cancel button in either mode, so they
    never needed rebinding to toggle a tick). `exitSelection` must call
    `mViewModel.clearSelection()` — the adapter no longer does it.
  - **Two `notifyDataSetChanged` remain and are correct**: `enableGrid` changes
    every row's VIEW TYPE (the RecycledViewPool keys holders by type, so nothing
    is reusable), and `setTransfers` on a COUNT change reshuffles every position
    below. Don't "fix" those.
- **List gutter + multi-select parity.** The recycler uses the same
  `EqualSpacingItemDecoration(list_spacing)` as the Downloads/Bookmarks/History
  lists (the rows already carry the matching 8dp card margins, so the thumbnail
  lands 16dp from the edge like Downloads — don't drop the decoration or the
  margins won't match). Multi-select uses the **same strategy as Downloads — the
  screen's existing toolbar, NOT a contextual `ActionMode`** (the app deliberately
  doesn't use `startSupportActionMode`). `CloudBackupListFragment` grabs the
  SettingsActivity toolbar (`R.id.toolbar`), and while selecting: a `MenuProvider`
  contributes the delete action (`menu_action`), the title shows "N selected"
  (`action_mode_selected`), and the toolbar Up button + an `OnBackPressedCallback`
  exit selection (restoring the activity's pop/finish Up behaviour on exit). The
  per-row tick lives in the **action-button slot** like the Downloads row: the row
  has a ⋮ `cb_action` button (opens the sheet) and the adapter swaps it for the
  check IN THE SAME SLOT (button INVISIBLE → no reflow) + the `SelectionStyling`
  primaryContainer wash on the card. Delete shows a confirmation dialog then
  optimistically removes the selected entries (`deleteEntry` per id, `load()`
  resync on any failure). In-progress transfer rows aren't selectable; selection
  is torn down in `onDestroyView`.
- **Per-item sheet has a rich header.** `CloudBackupItemSheetDialogFragment` shows
  the file's preview thumbnail + name + `MIME · size · date` (the list-row facts,
  passed as sheet args) over the Restore / Remove rows; "Remove from cloud" is
  styled with `Firedown.Widget.DialogOption.Final` (the app's option-sheet
  destructive treatment — colorPrimary text + tint, NOT colorError). Don't revert
  it to a bare title + two rows, and don't use colorError (the popup/option sheets
  mark destructive rows with `.Final`/colorPrimary, not red).
- **Discovery is ONE dismissible banner in the Downloads list — not a
  bottom-sheet promo, and not a second adapter.** Cloud Backup is
  action-driven (the download sheet's ⋮), so a user who never opens that sheet
  never learns it exists. `DownloadFragment` prepends a `SyncBannerAdapter`
  through the SAME `ConcatAdapter` as the incognito header, below it (live
  in-flight state outranks a one-time promo). Tap → the merged Cloud screen
  (`EXTRA_OPEN_CLOUD_BACKUP`); X → retired permanently
  (`Preferences.CLOUD_BACKUP_BANNER_DISMISSED`), as is setting Cloud Backup up
  — the same retire-on-both-paths shape as the bookmarks sync banner.
  - **`SyncBannerAdapter` is PARAMETERIZED (copy + glyph), never forked.** Two
    banners that differ only in three resource ids do not get two
    near-identical adapters + layouts — that is the duplication mistake this
    file keeps recording (`compactDuration`, the three private QR encoders).
    The class keeps its historical `Sync*` name for the same reason Cloud
    Backup keeps its internal `Vault*` names: the user-facing STRING says
    "Cloud Backup", not the type. The no-copy constructor is the bookmarks
    default, so `WebBookmarkFragment` is untouched.
  - **Visibility is driven from the AGGREGATES LiveData, not the paging
    load-state listener** — even though the latter is where the row count is
    already computed. That listener can fire while the RecyclerView is
    computing layout, and a `notifyItemInserted` on a SIBLING adapter of a
    `ConcatAdapter` throws there; `applyAggregates` is a plain observer and is
    safe. The rows gate itself is deliberate: promoting a backup feature on an
    empty Downloads list is noise, and the empty state already carries its own
    CTA (the SAF restore button). It is read BEFORE `applyAggregates`'s
    `mPendingPresentation` stash — that deferral keeps section-header COUNTS in
    step with the generation they label, and the banner labels nothing.
  - `getLeadingHeaderCount()` must count it (the grid `SpanSizeLookup` calls
    that per position, so a self-hiding header is fine), and `onResume`
    re-evaluates it because nothing else observes the set-up flag.
  - **A bottom-sheet "Cloud backups…" promo after install was considered and
    rejected**: a modal on a fresh install interrupts before the user has a
    single download to back up, and this list already hosts an announce-banner
    pattern that costs nothing until there is something to promote.
- **"Backing up…" snackbar has a View action, no success snackbar.** Tapping
  "Back up to cloud" (`BaseDownloadFragment`) shows a "Backing up…" snackbar whose
  **View** action deep-links to the backed-up-files list (where the live per-item
  progress shows); there is deliberately NO terminal success snackbar (the list is
  the confirmation) — only a `FAILED` transfer surfaces an error snackbar
  (`CANCELLED` stays silent).

- **Deep-links + back-nav.** The upload/restore notification AND the home
  status line open the merged Cloud screen
  (`SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP`) with `popUpTo settings inclusive`,
  so Back returns to the CALLER (home), never into the settings tree. The
  **Downloads toolbar overflow** opens the backed-up-files list
  (`EXTRA_OPEN_CLOUD_BACKUP_FILES`) and replaces the settings list on the back
  stack (`popUpTo settings inclusive`), so Back returns to **Downloads** (the
  caller), not into the settings tree. A "Cloud" row in the home + browser popups
  opens the same merged screen (`EXTRA_OPEN_SYNC`).

- **Cloud IA — ONE backup-first Cloud screen; everything Cloud lives on it.**
  The Settings → Cloud screen (`SyncSettingsFragment` / `settings_sync.xml`,
  toolbar title "Cloud") is the MERGED home of the paid downloads backup AND the
  bookmarks toggle — both the old thin-hub-plus-`CloudBackupSettingsFragment`
  two-level IA and the focused `BookmarksSyncFragment` were retired (maintainer
  calls: reaching the plan/files took 4 taps from home; a whole sub-screen for
  one switch was a tap tax). Order on the screen = priority: the
  `CloudStatusPreference` **status hero**, the **MORPHING CTA** directly
  under it (`CloudBuyButtonPreference` — the MaterialButton carries
  `@android:id/title` so the Preference TITLE drives its label: "Create recovery
  code" pre-key, "Add storage credit" after; the button is
  non-clickable/`duplicateParentState`, the row owns the click; **emphasis is
  state-dependent** — FILLED while the next step is genuinely "pay" (pre-key /
  unfunded / grace / quota-unknown-with-key), swapped to the OUTLINED
  `preference_cloud_buy_button_plain` layout once affirmatively funded (or on
  the unmetered beta, where there is nothing to sell) — a years-of-runway
  account shouldn't be stared down by a permanent filled sales button, the
  same restraint that keeps the home pill a quiet chip; `applyBuyEmphasis`
  binds cached-first then
  from the fresh load, like the hero), the
  **"I have a recovery code"** adopt door (shown in BOTH key states — see the
  adopt/replace note below), the **Backups** row (shown once set
  up; NO category headers on this screen AT ALL — "Manage backup" /
  "Recovery code" / "About" over one row each restated the row, the same
  taxonomy-noise call as the dissolved Cookies category, and the LAST header
  (Bookmarks, kept briefly as a paid-vs-free separator) had to go too: a
  Preference category header visually owns every row until the NEXT header,
  so with no header after it, it umbrella'd the code/FAQ/delete rows as
  "Bookmarks" children — the shipped "why is Delete backed-up files under
  Bookmarks?" bug. The screen is fully FLAT; order alone carries grouping),
  ONE secondary inline **Bookmarks SwitchPreferenceCompat** (key `SYNC_ENABLED`, never self-persists — the change
  listener returns false and `SyncManager` owns the pref; there is NO "Sync now"
  row anymore — sync is change-triggered + runs on toggle-on, the last-synced
  summary carries the signal), **ONE recovery-code row** (device-auth gated;
  the reveal dialog carries Copy AND Save-to-file — export lives inside the one
  authed reveal, on NON-DISMISSING buttons so the create-mode "I've saved it"
  gate survives the SAF round-trip; the old separate export row doubled both
  the rows and the auth prompts for one object), the FAQ, and LAST the TWO
  SCOPED erasure rows.
  - **The not-set-up hero is the onboarding ROADMAP**: ① Create your recovery
    code → ② Add storage credit → ③ back up from the download sheet ⋮, bound by
    `CloudStatusPreference.bindOnboardingSteps` (done = "✓" + muted ink, the
    next pending step keeps bold full-contrast ink; step ② checks off on the
    SERVER's metered balance when the quota is loaded — stale local plan prefs
    must not keep it checked for an expired/reaped account — with the prefs as
    the offline fallback). The steps reuse existing strings — no new
    translations. The morphing CTA always shows the NEXT step. The expiry
    lifecycle this completes: runout → 30 days of amber grace (hero alert +
    home "Paused" pill, both already built) → the server reap deletes objects
    AND manifest → the client's 0-files reconcile auto-retires the flag → this
    roadmap returns with step ② bold, pointing at top-up.
  - **Two scoped erasure rows, never one "delete all cloud data"**: "Delete
    bookmarks from server" (shown while sync is on) and "Delete backed-up files"
    (set-up gated). A combined row misled (the old title never touched
    bookmarks) and would couple wiping free bookmarks to destroying paid
    backups. **"Delete backed-up files" keeps the balance**: the server's
    `DeleteAccountData` deletes objects + manifest but KEEPS the quota row, and
    the client deliberately does NOT wipe the plan prefs / recovery code / the
    enabled flag on success (the old full-wipe cleanup stranded the surviving
    balance — the code is its only key; `loadStatus`'s reconcile owns the flag
    from server truth). `deleteAllData` also CANCELS every in-flight/queued
    transfer first (`cancelAllWorkByTag(WORK_TAG)` — backups AND restores carry
    it): without that, a running upload failed its complete against the wiped
    rows (spurious error + orphaned chunks) and a QUEUED backup ran after the
    wipe and quietly re-created a manifest.
  - The KEY-FIRST GATE survives: pre-key the CTA reads "Create recovery code"
    (with the mandatory "I've saved it" dialog), Manage rows are hidden, the
    Bookmarks switch is disabled (stops the keyless-purchase ghost account).
  - **Adopting a code works in BOTH key states — "I have a recovery code" is
    NOT pre-key-only, and re-gating it on `!hasKey` re-breaks the two-device
    case.** Two devices that each minted their own code have two separate
    accounts; putting them on one means one device adopts the other's code. The
    row used to hide the moment a key existed, and the second device usually
    HAS one by then (it created a code, or backed a file up, before the user
    decided to share the first device's account) — so the only route to one
    shared account was clearing app data. The key-first gate is untouched by
    this: that gate exists to stop a KEYLESS purchase, and adopting a code
    produces a key rather than bypassing one. Post-key the row's summary flips
    to the replace wording and `showLinkDialog` **confirms first**
    (`settings_sync_link_replace_*`, 16 locales) — the one place this screen
    asks twice, because the current code is the ONLY key to whatever sits under
    it: nothing is deleted server-side, it simply becomes unreachable from this
    device, so the copy points at the Recovery code row to save it first.
  - **The code travels by QR as well as by typing, and the SCANNER IS THE P2P
    ONE — do not fork it.** Device A's reveal dialog carries the same code as a
    QR (`dialog_sync_show_code.xml` `sync_code_qr*`), device B's adopt dialog
    gets a **Scan** neutral button → `P2pScanFragment`, which is registered as a
    `<dialog>` in `nav_graph_settings` too (same class; the two activities host
    different graphs). Reuse was nearly free because that screen's contract
    already generalised: `ARG_PREFIX` defaults to `""` (accept any decoded text),
    which is exactly right here since a recovery code has no prefix and is
    verifiable only by *trying* the decode — so the CALLER validates, via
    `SyncManager.looksLikeRecoveryCode` (Crockford + length, no IO, main-thread
    safe; SHAPE only, never authenticity). The one thing missing was a title, so
    `ARG_TITLE_RES` was added (0 = keep the old `ARG_REPLY` behaviour, so every
    P2P caller is untouched). Three properties are load-bearing:
    - **The QR panel SWAPS with the code panel in the SAME dialog — never added
      below it, never a second dialog.** Two failed shapes, both shipped:
      ADDING it below made the dialog taller than the window, and its button
      panel is stacked VERTICALLY (Material stacks when the labels don't fit a
      row, and there are three — Copy / Save to file / Done), so the overflow
      clipped "Save to file" off the bottom and made it unreachable (reported
      on-device); shrinking the image only defers that to a large font scale. A
      SEPARATE dialog fixed the height but read as a dialog stacked on a dialog,
      and **dismissing the reveal to avoid that is not available**: in create
      mode it is non-cancelable with Done gated on the "I've saved it" checkbox,
      so dismissing would let the user leave a freshly minted key without ever
      acknowledging they saved it — the exact hole that gate closes — and it
      would take Copy and Save-to-file away while the QR is up. Swapping keeps
      the height at max(code, QR) rather than their sum (which IS the clipping)
      and dismisses nothing. Both panels are set on every toggle tap, the same
      one-sided-set rule as the home cloud slot; the checkbox stays visible in
      the QR state because it is the create flow's only exit condition. The
      exposure argument is untouched — the QR starts hidden, so the payload is
      only on screen once asked for, one deliberate step past the device-auth
      gate. `bindCodeQr` encodes ONCE and hides the toggle when zxing declines
      the payload, so the swap can never land on an empty frame.
    - **A scan lands back on the INPUT dialog, prefilled — it never links
      straight through.** A QR is a bearer secret pointed at a camera; an
      accidental or wrong-device frame must not be able to swap the account with
      no confirmation, and the review step costs nothing because the dialog
      already exists. A payload that fails `looksLikeRecoveryCode` is reported
      and DROPPED rather than prefilled (it isn't a typo to correct, and
      prefilling would invite pressing Restore on it).
    - **The result is consumed with `set(key, null)`, never `remove(key)`** —
      remove() detaches the handle's cached LiveData and the SECOND scan of a
      session would silently never arrive (the Cloud Backup item-sheet trap, same
      mechanism). `observeScanResult` registers in `onViewCreated`, not lazily
      when the scanner opens, so a result still lands after a config change or
      process death while the scanner was up.
    The QR encoder is `utils/QrCodes` — **the app's ONE encoder**. P2P share and
    the buy-credit Lightning invoice carried byte-identical private copies before
    this; a third would have been the `compactDuration` drift mistake, so both
    were migrated. It is monochrome black-on-WHITE in both themes deliberately (a
    dark-theme inversion is what makes some readers fail), and returns null for a
    payload zxing declines so callers hide the view instead of showing an empty
    frame that reads as a broken scan target.
  - **A key swap must DROP every cached per-account value, or the new account
    renders the old one's numbers.** The stored code IS the account, so
    `linkWithCode` removes `SYNC_LAST_VERSION` (the bookmark doc's OCC version
    — stale, it would fight the adopted account's document), `SYNC_LAST_SYNCED_AT`
    / `SYNC_LAST_ERROR`, `CLOUD_PLAN_SIZE_GB` / `CLOUD_PLAN_DURATION_MONTHS`
    (the local purchase shape behind the roadmap's offline step-② check-off) and
    `CLOUD_LAST_TOTAL_BYTES`; the caller drops the in-memory snapshot via
    `CloudBackupManager.forgetCachedStatus()` BEFORE `updateState`, so no
    surface repaints the previous balance in between. That durable total is the
    dangerous one — the home resting line paints it pre-network, so leaving it
    would show the OTHER device's figure as this account's, confidently and for
    the whole session offline (the same class as the erase-path bug that made
    `refreshCloudStatus` reset to -1 on a null cache). `ensureRegistered` needs
    NO clearing — its marker is keyed by `accountBase32()`, so a new account
    simply misses it and registers. Nothing on the server is touched.
  - Deep links: `EXTRA_OPEN_SYNC`, `EXTRA_OPEN_CLOUD_BACKUP` AND
    `EXTRA_OPEN_BOOKMARKS_SYNC` (bookmarks-list overflow + sync banner) all land
    on the merged Cloud screen — kept as three extras because callers express
    different intents; the Downloads toolbar routes two-way (`isSetUp` → the
    files list, else → the Cloud screen). Bookmarks sync is free, downloads
    backup is pay-per-use, but pricing is kept **implicit** in the copy (no
    badge).
  - **The status hero binds the CACHED snapshot first.**
    `CloudBackupManager.lastStatus()` keeps the last *successful* `Status` for the
    singleton's lifetime; the screen paints it synchronously on entry and the
    async `loadStatus` result then UPDATES the hero in place — no empty-state
    flash / layout jump per screen entry (on-device complaint). `loadStatus` also
    serves the cache instead of unknowns on an offline/transient failure (the
    flag reconcile only ever runs on a successful load, so the cache can't mask
    it), and `deleteAllData` clears it. Don't bind the hero straight to a fresh
    network load again.
  - **The metered hero speaks ONE model — prepaid credit measured in TIME —
    and GB-months NEVER appears in user-facing text.** After several rounds of
    "the GB-months / two-bars screen is confusing," the diagnosis was that the
    hero spoke THREE models at once: a plan chip ("Up to 450 GB · 1 year" — a
    shape INVENTED by normalizing the balance to 12 months; there is no plan,
    purchases accumulate into one balance), a "% of your plan" bar against
    that invented cap, and the timeline. All plan fiction was DELETED
    (`effectivePlanSizeGb`/`roundToNiceGb`/`coverageMonths`, the chip's plan/
    balance text, the metered %-of-plan bar, the covered-until clamp): the
    metered hero is now headline ("854 MB backed up") + the compact **credit
    METER** (`bindMeter` + `cb_meter`) — a thin gauge of credit REMAINING
    labelled with the runway time from the SERVER's `projected_runout_at`
    (past-guarded for stale servers). It borrows the familiar Dropbox/Drive
    thin-bar SHAPE but is a **fuel gauge, NOT a used-of-cap bar** — metered
    mode has no cap, so a "% of your plan" fill would be a lie (the exact
    confusion the plan fiction created). The fill is credit LEFT, **saturating
    at a year** (`RUNWAY_FULL_MONTHS`) with NO denominator shown, so a
    well-funded account reads full and only visibly drains (then goes amber) as
    the last year nears. This **replaced the `CloudTimelineView` Today→date
    runway** (removed) at the maintainer's request for a simpler, more
    recognizable meter — same runout math + strings, no new translations. The
    "no metered % bar" rule stands: it's a runway gauge, never a percentage
    against an invented cap. No projection (nothing backed up /
    effectively-never runout — the server omits dates past its 30-year horizon)
    → a FULL meter + the `cloud_status_credit_active` label, so a funded
    account always shows its credit. The separate usage BAR (`cb_bar`) binds
    only in the unmetered beta, whose byte cap is a real denominator; the CHIP
    binds only for grace ("Read-only", amber) and the beta label. The raw ledger unit
    survives user-facing only inside the buy wizard (denomination fine print +
    its explainer — the one place the unit is introduced); the Backups-list
    header and the buy-success screen now say "≈ 1 year of coverage"
    (`CloudStatusPreference.coverageLabel`) / "Added to your backup credit"
    instead of a GB-months number. `setPlan`'s stored `CLOUD_PLAN_*` shape
    now backs ONLY the roadmap's offline step-② check-off and the
    starter-vs-purchase label. Don't reintroduce a plan chip, a metered %
    bar, or a GB-months string on any status surface.
  - **The Safe Folder exclusion is a STATED promise, not a silent gap.** Vault
    entries are excluded from Cloud Backup (the same "vault never leaves the
    device" contract as the mirror and P2P send) — deliberately NOT lifted:
    vault content is guarded by the device lock/biometric, while cloud data is
    keyed by the recovery code, so backing up a vault file would silently move
    it into a different trust domain (name/thumb on the un-gated Backups
    list, restorable by code alone, no biometric). The exclusion is surfaced
    in two places: FAQ q7/a7 on the sync help screen (why + the move-it-out
    workaround), and the multi-select backup action's snackbar
    (`cloud_backup_safe_excluded`) when a selection reduced to nothing
    BECAUSE of safe entries. If per-file vault backup is ever built it needs
    device-auth gating on safe entries in the Backups list, restore back INTO
    the vault, and explicit consent copy — all three together.

- **PUBLIC SHARE LINKS FOR A BACKED-UP FILE — DECIDED AGAINST. Do not build a
  server-side share endpoint.** The proposal: mint a unique URL for a backed-up
  object that anyone holding it can fetch. It is *technically* clean and that is
  exactly why it keeps coming up — every object already has its OWN DEK wrapped
  under the account master key, so sharing one file exposes only that file (had
  objects been encrypted directly under the master key, a share link would have
  been an account-wide key leak), and the key can ride in the URL **fragment**
  (`firedown.app/f/<shareId>#<dek>`), which never reaches a server, so "the
  server never sees plaintext" survives intact. Server-side it is one table
  (shareId → objectId, expiry) plus one unauthenticated endpoint returning
  short-TTL presigned chunk GETs — `handleGetObject` already presigns exactly
  that. The objection is NOT engineering; it is what the feature turns Firedown
  into, and it is decisive on four counts:
  - **The economic brake does not exist.** R2 charges **zero egress**; a served
    GB costs only Class B ops (~$0.000046/GB at the 8 MiB chunking). On S3,
    egress at ~$0.09/GB polices this by itself (a 5 GB file pulled 1000× ≈
    $450); on R2 the same abuse is free. Worse, the **starter grant** removes
    the storage brake too: recovery codes are client-minted, so accounts are
    free to farm (bounded only by PoW / per-IP / `MaxAccounts`), and the
    pipeline becomes farm → free grant → upload → public URL, never touching
    the mint. Check `FIREDOWN_STORAGE_STARTER_GRANT_GBM` before arguing this
    away — it defaults to 0/disabled in the provisioner, so it bites only where
    the operator enabled it.
  - **Moderation is impossible BY CONSTRUCTION, not by policy.** Per-file random
    DEKs mean PhotoDNA/NCMEC hash matching, malware scanning and duplicate
    detection can never run on shared content. Every E2E file host that survives
    (Mega) does so by hashing what it can plus a large abuse team; neither is
    available here. "We are structurally incapable of scanning what we publicly
    distribute" is the worst posture possible in front of Cloudflare Trust &
    Safety or a DSA notice-and-action obligation.
  - **The blast radius is the PAYING USERS.** Every backup — the abuser's shared
    file and every customer's archive — sits in one R2 bucket under one
    Cloudflare account, and Cloudflare terminates accounts over CSAM. The
    failure mode is not annoying email; it is every paying user's backup
    vanishing because a stranger shared a pirated film.
  - **US DMCA safe harbor needs a registered designated agent** — real name and
    physical address in a public Copyright Office directory. For a pseudonymous
    maintainer that is: doxx yourself, or run a public host with no safe harbor.
  Today there is nothing to take down, because nothing is publicly reachable and
  nothing is identifiable. That property is worth more than the feature.
  - **The app-to-app variant is redundant with P2P send** (maintainer's call).
    Its ONLY unique property is serving a file the sender has since DELETED
    locally — P2P can only send what is still on the device. Narrow; it does not
    earn a feature. The genuinely new capability is "recipient needs nothing
    installed, asynchronously", and that is precisely the *hosting* slice.
  - **The fragment-key privacy claim is thinner than it sounds.** The full URL
    including the DEK rests in WhatsApp/Telegram/Google message stores for most
    real sends. Note the asymmetry that makes this worse than the existing P2P
    link: an `FDS1.`/`FDO1.` fragment is a capability that dies with a 5–15 min
    mailbox TTL, whereas a share DEK is permanent. Don't oversell "the key never
    touches a server" — it touches everyone else's.
  - **If it is ever built anyway**, these are the guardrails, and the first two
    are the load-bearing ones: **gate shares on a PAID balance** (starter-grant
    accounts cannot mint links — restores the friction R2 removed and makes
    farming cost money), and **single-use claims burned on first completed
    fetch, NOT a download cap**. A cap of ~25 is not a transfer mechanism, it is
    a small distribution channel, and re-minting multiplies it back to unlimited;
    single-use kills redistribution by construction, the same taste as write-once
    chunks over a reconcile sweep. Then: expiry in HOURS (24–72 h, hard ceiling),
    revocation surfaced beside the file in the Backups list, per-IP limiting on
    the unauthenticated endpoint, per-share egress counters wired into the
    existing `--audit` + ntfy pipeline, and a `FIREDOWN_STORAGE_SHARES_ENABLED`
    kill switch (the realistic endgame is switching it off under pressure). And
    the takedown process gets decided BEFORE the endpoint ships, not after the
    first complaint — if nobody is willing to register an agent and answer abuse
    mail within 24 h, the feature is not shippable regardless of code quality.
  - **The alternative that captures most of the value with none of the exposure
    is a BROWSER-RECIPIENT P2P receive** — see the end of the P2P share section.

- **FGS type.** Both workers run as `dataSync` foreground workers; the app's
  manifest merges `foregroundServiceType="dataSync"` onto WorkManager's
  `SystemForegroundService` (without it, `setForegroundAsync` crashes with
  "foregroundServiceType 0x… is not a subset of 0x0").

- **There is now a SECOND client on the same manifest — the web client**
  (`firedown-website` `backup/`, served at `firedown.app/backup/`). It unlocks
  with the same recovery code and does list/restore/upload/remove against the
  same `storage.firedown.app` account, so **the vault wire format is no longer
  private to this app**. Anything here that changes the shared shape has to be
  mirrored there or the two silently diverge: the six-line canonical, the HKDF
  info strings in `SyncIdentity` (esp. `firedown/storage/v1`), the
  `FDSB1`/`FDVC1`/`FDVK1` framings in `BookmarkBlob`/`VaultCrypto`, the
  `VaultManifest` JSON field names, and `VaultEngine.CHUNK_SIZE` +
  `CHUNK_OVERHEAD` (the declared object size is derived from both). The web
  side pins all of it with the shared `firedown-api/tests/api-vectors/`
  fixtures — the same ones this app's `CryptoTest` uses — so run BOTH sides'
  tests when touching any of the above. Two things it deliberately does NOT do,
  so they stay app-only: **buying credit** (the blinding secret is the only
  proof of a paid credit and must not sit in browser storage across a payment
  redirect) and **minting a recovery code**. It also writes entries with a null
  `thumb`, so a file backed up from the web shows the mime glyph in the
  Backups list until the display-time backfill or a re-backup fills it in —
  that is expected, not a bug.

- **QR browser pairing — the web client is unlocked by a SCAN, and what
  travels is KEYS, not a token** (`sync/crypto/PairSeal.java`,
  `sync/PairClient.java`, the "Pair a browser" row on the Cloud screen). The
  browser needs the storage content key to decrypt anything, so no session
  token could ever log it in; the phone seals **exactly three values** —
  `account_id`, `auth_seed`, `storage_key` (`SyncIdentity.pairingKeys`) — to an
  ephemeral P-256 key the browser generated, and posts the ciphertext to the
  server's pairing mailbox. **The RECOVERY CODE NEVER TRAVELS**, and neither
  does the bookmark `fileKey()`, so a paired browser gets the storage account
  and only that; it cannot re-derive the code, which makes pairing **safer**
  than typing the code into a web page, not merely more convenient. Say it that
  way round in copy.
  - **Wire format is shared with the web client** (`backup/fd-pair.js`):
    `'FDPR1' | version(1) | phoneEphPub(65) | iv(12) | AES-256-GCM(ct||tag)`,
    key = HKDF-SHA256(ikm = ECDH, salt = empty, info = `firedown/pair/v1` ||
    pairId || browserPub || phonePub). The info binds the blob to THIS pairing
    and BOTH keys, so a captured blob can't be replayed into an attacker's
    session. Pinned by `PairSealTest`, whose vector is copied verbatim from
    `firedown-website/tools/pair-seal.json` (generated by `fd-pair-test.mjs`,
    which seals with node/OpenSSL — an implementation sharing code with
    neither client). **Regenerating that fixture means re-copying the hex
    here**; a one-byte divergence fails every pairing with no diagnostic. That
    fixture is **deterministic** — its browser keypair, phone ephemeral key and
    IV are constants on the web side — so it only moves when the FORMAT moves,
    and `fd-pair-test.mjs` fails once when it does, naming the four constants
    to re-copy. It was NOT always: those inputs were random per run, so the
    file changed after every `npm test` and silently drifted from this copy
    (the JSON said `697334` while this test asserted `103966`) — neither side
    noticed, because this test reads its own hardcoded hex, which is exactly
    why the pin has to be reproducible to mean anything.
  - **The verification code is a SAS over the COMPLETED handshake — it covers
    the PHONE's ephemeral key (`firedown/pair/verify/v2`), and moving it back
    to (pairId, browserPub) alone reopens a real hole.** Both of those live in
    the QR, so a code over them proves only "this is the session I started".
    The three checks below all guard the PHONE's direction; nothing guarded the
    browser's, and delivery to the mailbox is authenticated by nothing but
    knowledge of the pairing id. So the SERVER — or anyone who photographed the
    QR — could seal THEIR OWN account's keys to the page and win the delivery
    race. The browser decrypted them happily and the victim was silently signed
    in to the attacker's account, where everything they backed up next belonged
    to the attacker. (Found in the pre-production audit; `pairstore.go` still
    claimed "anti-MITM, including by US", which was true of reading the keys and
    false of substituting them.) Consequences that are easy to undo by accident:
    the browser shows **no code beside the QR** (the phone's key does not exist
    yet, so there is nothing honest to print), it shows the code only AFTER a
    blob arrives, and it **must not install keys until a human confirms** —
    decrypting proves only that the blob was sealed to a public key the QR
    prints. `PairSeal.newEphemeral()` exists so the key is minted BEFORE the
    approval dialog and reused verbatim by `seal`; generating one inside `seal`
    would show digits for a handshake that never happened. The QR prefix went
    `FDP1.` → `FDP2.` in lockstep so an app that predates the change fails to
    PARSE rather than computing a v1 code and telling the user their own pairing
    looks like an attack — **ship the APK before the web page**.
  - **THREE checks stand between a scan and handing over keys, and each covers
    what the others cannot.** (1) `PairClient` resolves ONLY against
    `Preferences.STORAGE_DEFAULT_BACKEND` — the QR carries an id and a key,
    **never an endpoint**, so a hostile QR can't point the phone at its own
    mailbox; this is what makes the server's origin attestation mean anything.
    (2) The server-recorded pubkey is compared against the SCANNED one, so a
    server that swapped it to read the blob is caught out-of-band. (3) The
    approval sheet shows a **six-digit verification code** the user must match
    against their browser. **(3) is not decoration** — the server cannot tell a
    real browser from a program sending the same `Origin`, so an attacker CAN
    mint a session that truthfully reports `firedown.app` and message its QR to
    a victim; the victim has no screen showing that session's code, so the
    comparison they're asked to make cannot succeed. Never reduce the copy to
    "check the site name".
  - **The approval sheet is a CUSTOM VIEW (`dialog_pair_confirm.xml`), not a
    `setMessage` string, and the ranking is the reason.** It used to
    concatenate site + code + warning with `"\n\n"` into one message, which set
    the six digits the user is asked to COMPARE — the only action the dialog
    exists to prompt — at body size mid-sentence, at the same weight as the
    boilerplate. A concatenated message cannot express that; a layout can. The
    code is the hero (30sp monospace, letter-spaced, grouped 3+3, in the
    dialog's only bounded surface), the site row carries **no verification
    badge** (a trust mark would assert exactly what the code exists to doubt —
    see the three-checks note above), and a new scope line states what is
    granted and that the recovery code does not travel. Exactly ONE coral
    element, the Allow button. Two colour rules that are easy to undo: the
    warning ink is **`?attr/colorError`** (5.72:1 light / 6.45:1 dark), never
    `@color/backup_warning` (2.09:1 on a light surface — the same
    invisible-in-daylight defect as the old home pill); and the code well is
    `colorSurfaceContainerHighest`, **not** `bg_sync_code`, whose
    secondaryContainer peach would put a second saturated element beside the
    coral button.
  - **A string whose FORMAT-ARG SHAPE changes needs a NEW key** — the same
    discipline as the pref-key inversion rule, for the same reason. Splitting
    the message turned `pair_confirm_site` ("Site: %1$s") and
    `pair_confirm_code` (…"%1$s") into static labels beside their own views, so
    they became `pair_confirm_site_label` / `pair_confirm_code_label` and the
    old keys were deleted in all 16 locales. Reusing the names would have left
    every translation's `%1$s` **rendering literally**: `getString(int)` does
    no formatting, so nothing would have failed at build time.
  - **Known residual, accepted:** the approval sheet is a plain
    `MaterialAlertDialogBuilder` dialog rather than a `DialogFragment`, so
    rotating the device while it is up leaks the window and drops the pairing
    (the user rescans). The lookup side is `isAdded()`-gated so nothing crashes,
    and a dropped pairing is safe — it just expires. Double-tapping Allow is
    guarded (`AtomicBoolean`), because two seal-and-deliver threads made the
    server's first-delivery-wins turn the second into a 409, so the user saw
    "paired" and then "expired".
  - **`PairSeal` is deliberately Android-free** (`java.util.Base64`, not
    `android.util.Base64`) so a plain JVM test exercises it — with
    `unitTests.returnDefaultValues` the Android one returns **null** instead of
    failing, which would silently hollow out the test.
  - `parse()` validates the point is ON P-256 (`decodePoint` + `isValid`)
    before anything multiplies by it — an invalid-curve point is the classic
    ECDH key-recovery attack.
  - **ONE scanner, two rows, told apart by the PAYLOAD** — `P2pScanFragment`
    again (fourth caller; don't fork it), and `observeScanResult` branches on
    `PairSeal.parse(...) != null` rather than a mode flag, which would have to
    survive the saved-state round trip and process death. Result still consumed
    with `set(key, null)`, never `remove` (the detach trap).
  - **The `firedown://pair/` deep link is DECIDED AGAINST, and the QR no longer
    carries the scheme.** The supported path is Cloud → "Pair a browser" →
    scan, which is what the web page's own instructions say. Registering a
    manifest intent-filter for `host="pair"` was considered and rejected:
    **the deliberate navigation is doing security work.** Today a pairing QR
    someone MAILS you cannot open the approval sheet at all — you would have to
    go find the scanner. With the filter, a QR in an email opens it cold, from
    a user who sought nothing, and the six-digit comparison becomes the only
    remaining gate (one the victim cannot perform, having no browser showing
    that code, but can certainly skip). The convenience bought is small — the
    user necessarily has the app installed, since they have a backup — and
    unreliable, because OEM system-camera QR handling varies. **The
    `firedown://p2p` precedent does NOT transfer**: a hostile P2P QR yields an
    unwanted file offer, previewed by name/size/mime before Accept; a hostile
    pairing QR yields read/write on the entire cloud backup.
    Consequences already applied: `backup/fd-pair.js` encodes the **bare
    `FDP1.<id>.<pubkey>`** payload, because the wrapper only advertised a path
    that dead-ends (a system-camera scan found no handler and read as a broken
    app) — a bare payload scans as opaque text, which is the truth, and drops
    the QR from version 8 to 7. `PairSeal.parse` still strips the old prefix
    when present, so both forms parse to the same `Ref` and the same
    verification code; reviving the deep link is a one-line change on the web
    side plus the filter. **If it is ever revived**, two conditions: demote
    **Allow** to a text button so the risky choice is not pre-selected for
    someone who did not go looking for this screen, and give the deep-link
    arrival its own copy line.

Back-end contract + server internals live in the `firedown-api` repo
(`docs/cloud-storage-spec.md`, `internal/storage/*`); don't add server/deploy
files to this Android repo.

## Browser chrome, pull-to-refresh & session recovery (Fenix-parity invariants)

The dynamic top/bottom bars, pull-to-refresh, and crash/kill recovery were
aligned with current Firefox for Android (mozilla-firefox/firefox `main`).
The invariants, each protecting against a shipped bug:

- **Only the TOP toolbar owns scroll detection** (`GeckoToolbarBehavior`, a
  port of `EngineViewScrollingGesturesBehavior`; `isScrollEnabled` defaults
  **false**). The bottom bar is a **passive follower** —
  `BottomNavigationBehavior` mirrors the toolbar's hidden *fraction*
  (proportional, survives unequal heights; re-syncs in `onLayoutChild` for
  GONE→VISIBLE). **Never reintroduce a second gesture listener on the bottom
  bar**: two independent half-height snaps can land one bar open and one
  closed. All force-show goes through the toolbar only
  (`GeckoToolbar.forceExpand/forceCollapse`).
- **One scroll-policy decision point** — `BrowserFragment.applyToolbarScrollPolicy`
  (the Fenix `ToolbarBehaviorController` equivalent): bars may hide only when
  `BROWSING && !mPageLoading && !IME && !touchExploration`. Loading truth is
  **per-tab** (`GeckoState.isLoading`, set ungated in onPageStart/Stop,
  cleared on crash/kill) because START/STOP observer events are
  foreground-gated and can't be re-derived after a tab switch.
- **Pull-to-refresh is gated per-gesture, never by scroll position**: the
  `canChildScrollUp` callback polls the LIVE `InputResultDetail`
  (`!canOverscrollTop()`, Fenix `SwipeRefreshFeature` parity) +
  `NestedGeckoView`'s disallow-intercept arbitration. **Don't re-add a
  `setEnabled(scrollY < N)` heuristic** — its stale inputs were the
  scroll-vs-refresh coin-flip. `isEnabled` is owned by lifecycle/fullscreen
  only. `GeckoSwipeRefreshLayout.onStartNestedScroll` returning false when
  enabled is **load-bearing** (`NestedScrollingChildHelper` climbs past it to
  the CoordinatorLayout; stock SRL would accept and starve the toolbar).
- **Stale-commit guard is ONE-SHOT** (`GeckoState.mPendingUserLoadUri`, armed
  by `openUri`): consumes itself on the first location change (match = the
  user's own same-document commit; mismatch = the single stale commit a
  cancelled load can produce). Also cleared by start/stop/equal-deny. **Never
  make it persistent** — a load that fires no progress events (`#fragment`
  nav, denied deeplink) would wedge it shut and swallow all SPA navigation.
- **`isCurrentGeckoState` is PER-REPO** — every UI observer that receives a
  `GeckoState` must also filter `isIncognito() != mIsIncognitoThemed`, or the
  other mode's background tab drives the visible chrome (PROGRESS and
  FULL_SCREEN now carry the state for exactly this).
- **Crash/kill recovery**: `discardGeckoSession` happens at the DATA layer
  (`GeckoComponents.onCrash/onKill`, before notify — must run even with no
  fragment view alive or the closed-session reference makes the reopened tab
  blank; `restoreState` replays only on fresh construction). The fragment
  reopens **only the visible tab** (background/cross-mode crashes must not
  steal the screen), and on a FOREGROUND kill of the visible tab it reopens
  **eagerly** (no lazy path can ever fire — observed on-device; Fenix does
  the same for the selected tab). `setGeckoViewSession` routes the previous
  session's deactivation through its `GeckoState` (`findGeckoStateBySession`)
  so the prompt-dismissal hook fires on the mCurrentId-drift re-attach too.

## Tab persistence — the sessions file (v3, Fenix + Chromium hybrid; issue #292 OOM)

The tab list persists to `filesDir/com_solarized_firedown_sessions.json`,
written by `GeckoStateObserver` and read at boot by
`GeckoStateDataRepository`. The current design exists because the old one
(org.json whole-file slurp + inline `data:`-URI preview/icon blobs persisted
verbatim per tab) produced a **deterministic `OutOfMemoryError` boot loop**
(issue #292): base64 images are unbounded *text*, the read held ~2–3× the file
(UTF-16 String + full JSONArray tree) against the 128 MB heap (no `largeHeap`),
and the parsed blobs then stayed resident in `mGeckoStates` forever — so the
OOM victim could be any later allocation (the reported stack was a 32-byte
main-thread alloc). Everything below mirrors Firefox for Android
(android-components `SessionStorage`/`BrowserStateWriter+Reader`); don't
regress any layer independently:

- **Versioned document, streamed both directions.** The file is
  `{"version":3,"tabs":[…]}` streamed with `android.util.JsonWriter`/`JsonReader`
  — never a whole-file String or parsed tree in either direction. The current
  shape is **writer-controlled and read STRICTLY** (`readEntityStrict` — exact
  types, unknown key/version THROWS → file moved aside; leniency there would
  only mask writer bugs, Fenix's `BrowserStateReader` stance). The strict
  reader accepts versions 2–3: **v2** carried each tab's session state INLINE
  (`session`); **v3** writes only a `session_ref` file reference (see the
  per-tab state-file bullet below) — a v2 file reads fully and the next
  persist externalizes it to v3. A legacy bare-ARRAY file (pre-v2 builds) is
  detected by the first token and read through the LENIENT per-field readers
  (`next*Safe` — total by design: a bad field falls to its default, never
  nukes the file; note `JsonReader.nextLong/nextInt` throw WITHOUT consuming
  the token, hence each catch's `skipValue()`). That lenient path is one-time
  migration — the next persist rewrites the current version. Adding a field =
  writer + strict reader together, bump `SESSION_FILE_VERSION` if not
  backward-readable (an APK downgrade across a bump = one-time tab reset,
  Fenix-accepted).
- **NO image data in the file, ever.** PREVIEW (og:image — shown nowhere in
  the tab UI) is not written and not restored. A `data:` favicon is
  externalized at persist time to a content-hash-named file under
  `filesDir/tab_icons/` (`TabIconStore`) and referenced by path; the store is
  pruned to the referenced set after every committed persist (empty list
  clears it, like `deleteThumbnails`). `GlideHelper`'s favicon loader has a
  local-path branch for these (with the `.svg`-mime flag). Fenix's session
  file likewise carries no icon/thumbnail/preview keys at all.
- **Atomic write with CORRECTLY-SCOPED failure containment.** tmp → fsync →
  rename, in two phases: a failure while WRITING deletes the torn `.tmp`
  (the boot read treats a present tmp as a fallback snapshot, so a torn one
  must not survive) — but a COMPLETE, fsynced tmp is **KEPT** when the
  rename dance fails. Landmine (shipped briefly): deleting the tmp on *any*
  non-commit destroys the last good copy in the worst interleaving (target
  deleted, second rename fails, process dies → neither file). That split is
  `AtomicFile`'s actual `failWrite`/`finishWrite` semantics.
- **Every OOM path is caught — the boot always completes.** `tryReadEntities`
  catches `OutOfMemoryError` (+ IO/Runtime) → moves the file aside to a
  `.corrupt` sibling (kept for post-mortem, pruned after 7 days);
  `initializeGeckoStates` and `GeckoStateObserver.persist` also catch
  `OutOfMemoryError` **deliberately** — an uncaught Error on the DiskIO
  executor reaches Android's default uncaught handler and KILLS THE PROCESS,
  which would resurrect the boot loop (Fenix's `SessionStorage.save` catches
  OOM for the same reason). `MAX_SESSION_FILE_BYTES` (256 MB) is a parse-TIME
  bound, not a memory bound — the streamed read's peak is one field; don't
  lower it into the range where it fires before the reader on recoverable
  legacy files (a 24 MB cap did exactly that).
- **Concurrency model: one thread, deep copies.** The boot read, every
  persist, and thumb writes all run on the single `@Qualifiers.DiskIO`
  executor (FIFO — and the repo provider enqueues `initializeGeckoStates` at
  DI time, so it always precedes the first persist; the tabs LiveData has no
  initial value, so `observeForever` can't fire a pre-init empty write).
  `notifyTabs` posts **deep-copied** entity snapshots, so persist never sees
  a mutating list or torn fields. No lock is held across file IO on the
  persist/boot-read paths; the ONE exception is the archive sweep
  (`archiveInactiveTabsLocked`), which has always done its Room
  insert/thumb-delete/purge IO under the `mGeckoStates` monitor on the disk
  executor — the v3 inline-resolve read rides that pre-existing pattern
  (one small immutable file per archived tab), it did not introduce it.
- **Persists are BATCHED — fixed-interval, latest-wins, flushed on detach
  (Fenix AutoSave parity, 2 s).** `onSessionStateChange` fires for scroll/form
  settle, not just navigation, so unbatched every emission rewrote + fsynced
  the whole metadata file. `GeckoStateObserver.onChanged` now just records the
  latest snapshot and arms ONE `PERSIST_BATCH_MS` (2 s) main-thread timer;
  `dispatchPending` hands the newest snapshot to the DiskIO executor. The
  timer is deliberately **fixed-interval, NOT a trailing-reset debounce** — a
  reset-on-every-event timer never fires under continuous scroll churn
  (starvation); this shape guarantees at most one persist per window and at
  latest one window after the first change. Kill-safety:
  `ApplicationLifeCycleHandler` calls `flush()` right BEFORE detaching the
  observer (pause AND destroy) — backgrounding is exactly when Android
  reclaims processes, so the pending snapshot lands first. Residual exposure
  is a mid-foreground hard kill losing ≤ one window of churn — the same trade
  Fenix ships. Batching state is main-thread-confined (LiveData delivers
  there; so do the lifecycle callbacks), no locks; `mHasPending` is a separate
  flag because `null` is a legitimate snapshot (deleteAll).
- **`session_ref` is re-anchored to THIS install's store dir at read time.**
  Refs persist as absolute paths (the THUMB/tab_icons precedent), but a
  user-profile transfer / OEM phone-clone migrates the files while
  `filesDir`'s prefix changes — a verbatim ref would miss and silently
  degrade every transferred tab to a URL-only restore. The strict reader's
  SESSION_REF case runs `SessionStateStore.resolve(context, ref)` (basename →
  current store dir; a normal install round-trips unchanged), and the next
  persist rewrites the corrected path. Purely defensive for same-install use.
- **Sessions are LAZY — only entities load at boot.** `initializeGeckoStates`
  builds `GeckoState` wrappers (the ctor stores the entity; no `GeckoSession`).
  A live session is created only by `getOrCreateGeckoSession()`, reachable
  solely through `BrowserFragment.openSession(oneTab)` — the current tab at
  startup (`ensureSessionConnected`) and a tab the user taps. Auto-archive
  (default ON, 1-week) removes stale tabs from the live list at init and
  closes any session they had. **Never add an eager create-sessions-for-all
  loop** — one Gecko content session at cold start is the design (Fenix's
  suspended-tabs model).
- **Per-tab session-state files (v3) — the Chromium model, with Chromium's
  bugs pre-fixed.** The remaining unbounded retention after v2 was every
  tab's serialized session-state string held in `mGeckoStates` (Fenix retains
  the same via `RecoverableTab.engineSessionState`). v3 removes it: the
  sessions file stores only a `session_ref` (absolute path into
  `SessionStateStore`, `filesDir/tab_states/`, content-hash-named `ss_<sha1>`
  files) and the string loads lazily inside `getOrCreateGeckoSession()`
  (`ensureSessionStateLoaded`) — **boot heap is O(opened tabs), not O(all
  tabs)**. This is Chromium-on-Android's `TabStateFileManager` /
  `TabPersistentStoreImpl` architecture (per-tab `tab<id>` files + a small
  metadata file), adopted with the fixes their history teaches
  (`SessionStateStore`'s class doc carries the full catalogue):
  - **Immutable content-hash files** (tmp→rename, never rewritten): kills
    Chromium's partial-overwrite corruption class outright, gives dirty-only
    saves for free (unchanged state → same hash → exists() → no write —
    their deduped save queue), and makes id-collision file clobbering
    impossible (their `tab<id>` naming needed duplicate-id dedupe logic).
  - **Per-file failure containment** (their `restoreTabState` → null → tab
    loads its URL): `SessionStateStore.read` returns `""` on any failure and
    the dangling ref is CLEARED, so `setGeckoViewSession`'s hasRestoredState
    check falls to its plain loadUri branch. One corrupt/pruned file loses
    one tab's history — never the tab, never the boot. The lazy read is
    synchronous on the UI thread by design (states are small — Gecko caps
    per-tab history ~50 entries; Chromium makes the same trade via
    `StrictMode.allowThreadDiskReads` for its critical-path restore).
  - **Prune consults every persisted reference domain** (their multi-window
    cleanup re-reads ALL other instances' metadata before deleting —
    crbug.com/40486025, "never destroy what you haven't proven
    unreferenced"): Firedown's second domain is the tab ARCHIVE, so
    `mapToArchivedEntity` **inlines the state string into the Room row at
    archive time** (resolving the ref if needed) — the archive NEVER holds
    refs, which makes the referenced set handed to `SessionStateStore.prune`
    complete by construction. Don't add a new persisted holder of
    `session_ref` without adding it to the referenced set.
  - **Grace-based deferred deletion, bounded in TIME and COUNT** (their
    `canTabStateBeDeleted` undo protection): prune touches referenced
    files' mtime and deletes unreferenced ones only after `RETENTION_MS`
    (24 h) — a close-undo finds its file intact; superseded states linger
    bounded hours, not forever; a stray `.tmp` from process death dies
    once stale. The grace alone is only a TIME bound, so prune ALSO caps
    the young unreferenced backlog at `MAX_UNREFERENCED_FILES` (512,
    oldest-mtime deleted first): onSessionStateChange fires for
    scroll/form churn too (a heavy day can pile thousands of young files
    before any ages out), and a backward clock jump gives files future
    mtimes that never look stale — the count cap bounds both. Don't
    remove either bound: they cover different failure shapes.
  - **Cleanup only after a committed persist, single-flight, THROTTLED**
    (their `cleanUpPersistentData` runs once after load, not per save):
    the state prune runs from `writeSessionFile` after the rename landed,
    on the single FIFO DiskIO executor — but only on the FIRST committed
    persist after boot and hourly thereafter
    (`STATE_PRUNE_INTERVAL_MS`), because its touch pass costs one utimes
    per referenced file + a dir scan and persists fire on every tab
    event. Safe under the grace math (referenced files are at most
    prune-interval stale, ≪ 24 h). Don't move the prune back to
    per-persist "for freshness" — that was pure inode churn. Cold-start
    IO is exactly TWO file reads regardless of tab count: the metadata
    file + the active tab's state file (more lazy than Chromium, which
    background-batch-reads every tab file after startup via
    `TabBatchLoader`; GeckoView needs a state only at session open, so
    the background sweep is skipped entirely).
  Entity semantics: `mSessionState` (live string) wins over
  `mSessionStateRef` when both are set; the observer externalizes a live
  string (re-hash → same file) and CARRIES a bare ref through without ever
  reading state bytes. The deep-copy in `notifyTabs` copies both fields, so
  a ref-only tab stays ref-only through persist.

## Tabs, sessions & delegate callbacks (foreground-only UI)

A tab is a `GeckoState` (+ its `GeckoSession`), **not** a separate fragment.
There is normally **one** `BrowserFragment` (plus an incognito one) driving
every tab. Gecko delegates (`NavigationDelegate`, `PromptDelegate`,
`ContentDelegate`, …) are attached to **every** session in
`connectSession` — so a **background tab keeps firing callbacks** (page loads,
deeplinks, JS `alert`, `beforeunload`, …).

`GeckoComponents`'s delegates fan those out to observers via
`mGeckoObserverRegistry.notifyObservers(...)`, and the registry calls **every**
registered `BrowserFragment` with no tab filter. So anything that shows UI from
a callback must first confirm the event came from the **foreground** tab,
otherwise a background tab's dialog/prompt pops over whichever tab is visible.

**The mechanism already exists: `isCurrentGeckoState(geckoState)` in
`GeckoComponents` (compares to the active tab id).** Gate UI-raising
notifications with it — as `START`, `STOP`, `PROGRESS`, `SECURITY`,
`THUMBNAIL`, `MEDIA_*` already do, and now `LOAD_REQUEST` (the "open in app"
deeplink), `PLAYSTORE_REDIRECT`, and the `PROMPT_*` prompts.

- For navigation callbacks that return allow/deny (`onLoadRequest`,
  Play-Store redirect): still `return GeckoResult.deny()` for a background tab
  — just skip the `notifyObservers` so no dialog shows.
- For prompts that owe Gecko a `GeckoResult` (every `PromptDelegate` method —
  alert/button/text/choice/color/date/auth/file/beforeunload/repost): a
  background tab must **dismiss** (`return GeckoResult.fromValue(prompt.dismiss())`)
  rather than skip — skipping leaves Gecko waiting forever. The pattern is to
  extend the existing `if (geckoState == null)` dismiss path to
  `if (geckoState == null || !isCurrentGeckoState(geckoState))`. All current
  prompts already do this.
- `onContext` (long-press menu) is inherently foreground — only the visible
  session is in the `GeckoView` to receive the touch — so it needs no guard.

Symptom this prevents: the "open in app" dialog (or an alert/file picker) from
a *previous* tab appearing after you switch tabs (repro: open bilibili.com,
switch tab mid-load; it fires a `bilibili://` deeplink from the background).

#### "Block app redirects" toggle — scoped to AUTOMATIC redirects only

One Security toggle, `SETTINGS_BLOCK_APP_REDIRECTS`, governs **both** anti-nag
paths: the Play Store install redirect (`PLAYSTORE_REDIRECT`,
`market://`/`play.google.com`) **and** generic "open in app" deeplinks
(`LOAD_REQUEST`, `bilibili://`/`intent://`/…). **Default ON** (app-install/open
nags are near-universally unwanted; sits with HTTPS-only / disk-cache-off). When
ON, the `NavigationDelegate` denial is taken **silently** (snackbar, +`goBack()`)
instead of prompting. When OFF: a generic deeplink shows the
`BrowserAppDialogFragment` "open in another app" prompt, while a Play Store
redirect just **loads the listing in-browser** (no prompt — it stays in the
browser, no app context-switch to confirm). There is **no** Play-Store-specific
dialog: the old 3-choice `BlockRedirectDialogFragment` ("Always block / Block
once / Open Play Store") was **removed** as redundant once the toggle defaulted
ON — its "Always block" merely duplicated the Settings switch, and it was only
ever reachable after the user had turned blocking *off* (so offering to re-enable
was incoherent). Don't reintroduce it; `onPlayStoreRedirect` handles both states
inline.

**Both paths gate on `!request.isDirectNavigation`** (a PAGE-initiated redirect,
not a typed/bookmarked URL):
- **Play Store**: `market://`/store URL → silent block. A store URL is never a
  deliberate in-browser destination, so this is pure win.
- **Generic deeplink** (`tiktok://`, `intent://`, …): silent block too, with two
  carve-outs — **user comms schemes** (`mailto:`/`tel:`/`sms:`/`geo:`,
  `UrlStringUtils.isUserCommsScheme`) are never blocked, and the snackbar's
  one-shot **"Open"** is the escape for a deliberately-tapped app link.
  **History/landmine:** an earlier version gated the generic path on
  `wasRedirector` (recent load **and** `canGoBackward()`) to spare deliberate
  taps — but that **missed TikTok**, which fires its deeplink on a first/cached
  view with **no back-entry**, so the "open in app" dialog leaked through with the
  pref ON. Lesson: GeckoView gives no reliable tap-vs-auto signal on `LoadRequest`
  (`isDirectNavigation` is false for *both* a link tap and a JS redirect, and
  there's no `hasUserGesture`), so the generic block uses `!isDirectNavigation`
  and leans on the comms carve-out + the "Open" escape rather than `wasRedirector`.
  Don't reintroduce a `wasRedirector`/`canGoBackward` *gate* — it silently drops
  first-load deeplink nags like TikTok's.

Every "Open" affordance is gated on `resolveActivity != null` (computed once as
`canOpen` in `onLoadRequest`): the block snackbar attaches its "Open" action only
when an app can handle the intent, and the non-blocking "open in another app"
dialog is **skipped entirely** when nothing can — no dead-end button, and no
follow-up "no app found" snackbar. This matters on de-Googled devices: an
uninstalled-app deeplink is rewritten to a Play Store intent, which doesn't
resolve without Play Store, so `canOpen` is correctly false. (The Play Store
path's "Open" is a `loadUri` web load, not an intent, so it isn't gated.)

`GeckoComponents` computes `autoRedirect`(=`!isDirectNavigation`) + `wasRedirector`
and passes both through the `LOAD_REQUEST` observer; `BrowserFragment.onLoadRequest`
blocks on `autoRedirect` (+ comms carve-out) and uses `wasRedirector` **only** to
decide whether to `goBack()` off a bounce. The pref
**key value keeps the legacy `…block.playstore.redirects` name** on purpose, and
flipping the **default** false→true needed **no new key**: there's no semantic
inversion (`true` still means "block") and the app never persists defaults (no
`setDefaultValues`), so an untouched install reads the new default while an
explicit toggler keeps their stored value. (The new-key rule is for enable→disable
*inversions* — see the JIT/WASM pattern below — which this isn't.)

### Media notification — start the service from the controller, not the UI

The `GeckoMediaPlaybackService` foreground notification is shown on a `MEDIA_PLAY`
intent. That intent must be sent by **`GeckoMediaController`** (which always knows
the truly-playing session and has seeded the metadata), **not** only by the gated
`BrowserFragment.onMediaPlay` observer. `GeckoComponents` fires that observer only
when `isCurrentGeckoState` is true at the instant GeckoView's `onPlay` arrives —
so an `onPlay` that beats the current-tab-id update (fresh start / restore-autoplay
/ resume / tab switch) was gated out with **no recovery** (`onMediaPosition` bails
when the service isn't running; `onMetadata` only updates), leaving media playing
with no notification. `onMediaPlay`/`onMediaPauseOrStop` call `refreshService()`
which starts/updates the service directly (the tell was that the controller already
*stopped* it directly via `stopService()` — only start was UI-delegated). So the
notification follows actual playback, including a background tab that autoplays.

### Text-selection highlight — the grey-selection wedge (window activation)

Web-page text selection painting **opaque grey `#AAAAAA`** instead of the brand
coral wash is NOT a color/theme bug — it's Gecko's **disabled (unfocused
document) selection state**, and on this app it gets **permanently wedged**.
The color chain has three layers, each fixed/mitigated separately:

1. **The accent itself.** `::selection` (ColorID::Highlight) = the Android
   theme's `android.R.attr.colorAccent` @ ~30% alpha, resolved by
   `GeckoAppShell.getSystemColors()` against the **application context's**
   theme. Android never applies the manifest `<application>` theme to that
   context (only Activities get it; `ContextImpl.getTheme()` falls back to
   platform DeviceDefault), so `App.onCreate` calls
   `setTheme(R.style.Theme_FireDown_SplashScreen)` and that theme carries
   `android:colorAccent = md_theme_primary`. **Both halves are required** —
   the theme item alone is a silent no-op. Verified by the selection HANDLES
   turning coral (they read the accent directly).
2. **The ON/DISABLED state machine.** The accent wash is painted only while
   the selection's document has DOM focus (`nsFrameSelection::
   WillFocusDocument` → SELECTION_ON); a blurred/never-focused document
   paints `TextSelectDisabledBackground` (`#AAAAAA`) — the same grey desktop
   Firefox shows for a background window's selection. Document focus on
   Android = `session.setFocused(true)` → chrome `browser.focus()`, driven
   solely by the GeckoView's **Android view focus**.
3. **The wedge (the real bug, lives in `widget/android/nsWindow.cpp`).**
   `nsWindow::Show(true)` unconditionally `BringToFront()`s a newly shown
   Gecko window — **every hidden GeckoSession this app opens (PoToken mint,
   P2P engine) deactivates the visible tab's window and blurs its
   document** — and `nsWindow::Destroy()` removes a window from
   `gTopLevelWindows` **without re-activating the next one**. So
   hidden-session churn leaves the tab window "list-top but not
   focus-manager-active": `UserActivity()` no-ops (already list-top),
   `GeckoView.requestFocus()` no-ops (already view-focused), and
   `setFocused(true)` → `browser.focus()` dies in
   `nsFocusManager::SetFocusInner` (`sendFocusEvent` requires
   `isElementInActiveWindow`). **No app-side API can exit this state.**
   Stock Fenix never trips it (no hidden-window churn), which is why the bug
   is invisible upstream.

Mitigation shipped here: `GeckoRuntimeHelper.applySelectionVisibilityPref()`
sets `ui.textSelectDisabledBackground` = `rgba(240,113,108,0.35)` (brand
coral, a hair off the active 78/255 alpha so `EnsureDifferentColors` doesn't
nudge it) — the disabled state becomes visually identical to the active one,
which is the right UX for a single-window phone browser anyway. **Root fix:
firedown-geckoview patch `0008-android-window-activation-wedge.sh`** (marker
`FIREDOWN-WINDOW-ACTIVATION`): tracks the activation holder explicitly,
`Destroy()` hands activation to the next visible top-level window (async),
`BringToFront()`'s guard requires the holder identity (not just "some active
window exists"), and `UserActivity()` self-heals on touch. Reaches the app
only via a GeckoView rebuild + `GECKOVIEW_BUILD_DATE` bump. The pref
mitigation stays even then (covers genuinely-unfocused paint). Diagnostic
probe (about:config, no build needed): set `ui.textSelectDisabledBackground`
to `#ff0000` — selection turning red proves the disabled-state wedge; a
selection that follows `ui.highlight` instead means focus is fine and the
accent pipeline is the suspect.

## Page titles, history, bookmarks & favicons

Aligned with current Firefox for Android. The spine is one invariant:

**A tab's title must always belong to the tab's current URL.** GeckoView fires
`onTitleChange` as a SEPARATE, LATER event than `onLocationChange`, so without
care a tab briefly (and on title-less SPA navigations, indefinitely) holds the
NEW url paired with the PREVIOUS page's title. That mismatch then gets persisted
(history row) and shown (autocomplete "recent tabs"), e.g. `elmundo.com`
displaying a Twitter post's title.

- **Clear the title on a cross-document navigation** —
  `GeckoState.updateVisit` (the one place that already detects a page-identity
  change for visit-id anchoring) clears `setEntityTitle(null)` (and
  `setEntityIcon(null)` on a HOST change) when the page-identity KEY changes. This
  ports android-components `ContentStateReducer`'s `UpdateUrlAction`
  (`title = if (!isUrlSame) ""` / `icon = if (!isHostEquals) null`). "Same
  document" is `pageIdentityKey` = normalized host + path/identity-query, FRAGMENT
  and noise-params IGNORED — so `#fragment`/pushState/tracking-param churn KEEPS
  the title (matches Firefox `isUrlSame`; ours is slightly more lenient by design,
  reusing the existing visit-id key). **Gate on `mCurrentPageKey != null`** so a
  fresh/restored tab keeps its title on its FIRST location change.
  - **Landmine (cost a round):** the clear MUST gate on `mCurrentPageKey`, NOT a
    re-derived "previous URL" from the entity — `GeckoComponents.NavigationDelegate.onLocationChange`
    writes the new URI into the entity (`setEntityUri`) BEFORE calling
    `geckoState.onLocationChange`, so by the time `updateVisit` runs the entity
    URI is ALREADY the new one and any entity-derived "previous" compares equal
    (the clear silently never fires). The location delegate notifies only the
    LOCATION observer (toolbar), NOT the tab list, so there's no "about:blank"
    flash; the tab list rebinds on `notifyTabs` (from `onTitleChange`) once the
    new title is set. `BrowserTabsAdapter` shows the URL when the title is empty,
    so a tab never renders a literal null.

- **History title — auto-update, url-keyed** (matches Firefox Places, which
  updates `moz_places.title` on every visit). `onTitleChange` →
  `WebHistoryDataRepository.updateTitle(url, title)` →
  `WebHistoryDao.updateTitleByUrl` (`WHERE file_url = :url`). **Keyed by
  `file_url`, NOT the tab's entity id** — a history row's uid is
  `generateId(url) = hash(url)+today`, so the old id-keyed update never matched
  the row and the repair was a silent no-op. `onHistoryStateChange` stores the
  entity title, but **null when it's the `about:blank` sentinel** (`getEntityTitle`
  returns `"about:blank"` for an unset title — never persist that as a real
  title); the url-keyed repair fills it in once the title arrives. The single-
  thread DiskIO executor serialises insert vs. repair, so either order converges.
  - **The ERROR PAGE must never donate its title — `GeckoState.mShowingErrorPage`
    exists for exactly this.** `onLoadError` hands Gecko our own
    `resource://android/assets/error/…` page, `errorPageScripts.js` sets
    `document.title` to the generic `browser_error_image_title`
    ("The Fire is Gone"), and the document keeps the **FAILED url** — so
    GeckoView fires `onTitleChange("The Fire is Gone")` against the real site's
    url and the url-keyed repair above stamps it onto that site's row.
    Permanently, and for every site that ever failed to load once, which is how
    a History list fills up with dozens of identical "The Fire is Gone" entries
    (reported on-device; the tell is that the rows show real, varied URLs).
    `onLoadError` sets the flag, `onPageStart` (plus crash/kill) clears it, and
    `onTitleChange` skips BOTH the history repair and the bookmark
    placeholder-backfill while it is set. The **attempted visit still belongs in
    history** — Firefox records failed loads too — so only the title is
    suppressed, and `setEntityTitle` still runs so an errored TAB reads sensibly
    in the tab list. Clear it on `onPageStart`, never on `onPageStop`: the error
    page's own `onTitleChange` fires between `onLoadError` and the next start,
    so a stop-time clear reopens the exact window the flag closes.

- **Bookmark title — placeholder-only backfill** (Firefox does NOT auto-update
  bookmark titles; a bookmark title is captured once and is user-editable). A
  bookmark saved mid-load snapshots an empty title, and there was no repair, so it
  read `About:blank` forever. `onTitleChange` →
  `WebBookmarkDataRepository.updateTitle(url, title)` →
  `WebBookmarkDao.updateTitleIfPlaceholder` whose `WHERE` only matches a row still
  holding a placeholder (`NULL`/`''`/`LOWER = 'about:blank'`) — so a RENAMED
  bookmark (`WebBookmarkEditFragment`) is NEVER overwritten. `add()` stores null
  (not the `About:blank` sentinel) for a mid-load title. The favicon has the
  analogous backfill already (`IconsRepository` → `WebBookmarkDao.updateIcon`,
  fires when the favicon loads, sync-set gated).

- **Bookmark import/export — Netscape HTML, the universal browser format.** The
  bookmarks-list toolbar overflow (`menu_web_bookmark_options.xml`,
  `action_import`/`action_export`, `WebBookmarkFragment`) reads/writes the
  Netscape Bookmark File Format every browser (Firefox/Chrome/…) uses. Format
  logic is the pure `utils/BookmarkHtml` (export/parse, no DB/Context dep beyond
  `Html` (un)escaping); IO + threading live in
  `WebBookmarkDataRepository.exportBookmarks(File)/importBookmarks(InputStream)`
  (disk executor, main-thread count callback). Import is **merge-by-URL, not
  append**: each parsed link gets the canonical `bookmarkIdFor(url)` uid and the
  set is `insertAll`'d in ONE transaction (REPLACE → re-importing the same file
  can't duplicate; one Room invalidation refreshes the Paging list). Import keeps
  **http/https only**, flattens any folder hierarchy (`<H3>` ignored), and reads
  `ADD_DATE`/`ICON_URI` when present.
  - **Export is NO picker — it writes straight to the public
    `Download/Firedown/backup/firedown_bookmarks.html`** via `FileOutputStream`
    (the same direct write `DownloadBackupMirror` uses for its `.fdbk` there;
    the app owns that subtree). That folder **survives uninstall**, so a manual
    bookmark backup rides alongside the download mirror and can be re-imported
    after a reinstall — which matters because **bookmarks are NOT in Android Auto
    Backup** (the backup include-list is prefs + the download mirror only, see
    "Auto Backup"). Overwrites latest-wins, like the mirror.
  - **Import IS a SAF picker** (`OpenDocument`): a reinstalled app must take a
    read grant on the now-foreign-owned file. The contract is overridden to force
    a **single `text/html` type** (the `*/*` + `EXTRA_MIME_TYPES` combo is ignored
    by many OEM pickers / the Recents view, which then show every png/mp4 in the
    download pile) and to pre-point `EXTRA_INITIAL_URI` at
    `Download/Firedown/backup` so the export file is right there. Both are
    best-effort at the SAF layer (a stubborn provider may ignore them).
  - Bookmarks are a single shared DB (incognito only themes the list), so both
    actions work in either mode — no incognito gate.

- **`about:blank`/blank titles never SHOWN** — Firefox displays the URL for a
  titleless entry (fenix#2163). `UrlStringUtils.isBlankTitle(title)` (null/empty/
  `about:blank` in EITHER casing — `isAboutBlank` is case-sensitive `startsWith`
  and missed the capitalized bookmark form) drives a URL fallback in
  `WebHistoryAdapter`, `WebBookmarkAdapter`, and the three `AutoCompleteSearch`
  suggestion builders. And a suggestion whose **URL itself** is `about:blank` is
  SKIPPED entirely (history/bookmark/tab) — the URL fallback can't rescue an
  about:blank URL, and an about:blank tab isn't caught by `isURLResouceLike`
  (`resource://` only).

- **Favicon updates — resolution policy + efficiency.** `IconsRepository.updateIcon`:
  when the resolution is unknown (`<= 0`) it HEAD-fetches and estimates from
  `Content-Length` (`estimateResolution`); then `WebHistoryDao.updateIconData` is
  ONE conditional UPDATE that (1) **keeps the higher-res icon** —
  `(file_icon_resolution <= 0 OR :res >= file_icon_resolution)` — and (2) skips a
  **no-op write** — `(file_icon IS NOT :icon OR file_icon_resolution IS NOT :res)`
  (`IS NOT` = null-safe). The no-op guard matters because an unconditional UPDATE
  fires Room invalidation on EVERY revisit, needlessly requerying the Paging list.
  Don't reintroduce the old `getResolution` read-then-write: it did two scans per
  signal and, worse, sampled ONE arbitrary row (`LIMIT 1`) then overwrote ALL rows
  — so it could DOWNGRADE a high-res row. The per-row gate fixes that. Bookmark
  `updateIcon` got the same `AND file_icon IS NOT :icon` no-op guard. Bookmarks
  have **no** resolution policy by design (no `file_icon_resolution` column —
  "always newest").
  - **`webhistory(file_url)` is INDEXED** (`@Index` + `MIGRATION_3_4`, version
    3→4, `CREATE INDEX` matching Room's exact DDL — same proven pattern as
    `DownloadDatabase.MIGRATION_10_11`). The uid PK is `hash(url)+day`, NOT the
    lookup key, so the url-keyed favicon/title updates would otherwise full-scan.

- **History is kept INDEFINITELY** (`HISTORY_RETENTION_INTERVAL = NEVER_INTERVAL`).
  Firefox expires history by storage size, not a fixed age; manual clear (the
  Delete-browsing dialog) still works. **`WebHistoryDataRepository.purgeDatabase`
  guards a non-positive window and skips the purge** — load-bearing: the cutoff is
  `now - interval`, so a NEVER (`-1`) value unguarded would be `now + 1ms` and the
  daily purge would delete the ENTIRE history. (The `file_url` index matters more
  now that the table is unbounded.)

## Downloading & networking

Two download paths, one shared OkHttp client (`NetworkModule`, with
`OriginInterceptor` — it derives **Origin from an existing Referer**, it does
not invent a Referer; Referer must come from the capture/emit layer).

**Filenames can contain periods** (a podcast titled `156. Valero y Juan`).
`FileUriHelper.checkFileExtension` only treats the tail after the last dot as an
extension when it actually looks like one (`isPlausibleExtension`: 1–4 chars,
alphanumeric, no whitespace) — don't revert to a naive `FilenameUtils` split or
such titles get truncated to their first segment (`156.mp3`).

**Filename cleaning is ONE definition, in `FileUriHelper` — and it never strips
supplementary-plane characters.** Every path that names a file resolves here:
`sanitizeFileName` (the native download, a `Content-Disposition` name, a P2P
receive, the Rename dialog), `replaceIllegalChars` +
`WebUtils.sanitizeTitleForFilename` (page-title shaping), and
`filenameInputFilter` (the Save/Rename fields). Three rules, each from a shipped
bug:

- **Non-BMP is REAL TEXT — do not "strip what filesystems can't cope with".**
  A `[^ -￿]` rule in `stripInvisible` deleted every code point above
  U+FFFF, which is emoji, CJK Extension B and the **Mathematical Alphanumerics**
  (U+1D400) that styled titles are written in. On-device a bilibili capture
  titled `𝙒𝙊𝙉𝘿𝙀𝙍𝙁𝙐𝙇 𝙉𝙄𝙂𝙃𝙏𝙈𝘼𝙍𝙀 | bilibili` saved as **`bilibili.mp4`** — the
  whole meaningful part of the name was the "unsupported" range. No filesystem
  this app writes to has that limitation (ext4 stores opaque UTF-8 bytes;
  FAT32/exFAT store UTF-16 and take surrogate pairs). The genuinely-invalid case
  is a **lone surrogate**, and `INVISIBLE_CHARS`'s `\p{Cs}` already catches
  exactly that and nothing else: Java regex matches by CODE POINT, so a
  well-formed pair is one supplementary code point and never matches.
- **Every length cap goes through `truncateWholeChars`, never a bare
  `substring`/`StringUtils.truncate`.** A supplementary code point is TWO UTF-16
  units, so cutting at the limit can leave half a pair — and `stripInvisible`
  (which would have removed it) already ran. Both caps in `sanitizeFileName`
  (150 chars, then the 255-BYTE loop) and `sanitizeTitleForFilename`'s own cap
  share the one helper so they can't drift.
- **Illegal characters become a SPACE, invisibles are removed** — and the
  illegal set stays the FAT/exFAT ∪ Windows one. Don't add punctuation to it
  (see the `Rock & Roll #1 (100% Live!)` note in `ILLEGAL_CHARS`).

Debugging a wrong filename: the chain is traced under one logcat tag,
`adb logcat -s FileNameTrace:*` (debug builds only) — the name as native
receives it, `prepareEntity`, `applyDisplayName`'s branch, then `decodeName`
→ `sanitizeFileName` → `checkFileExtension`. Two rounds were lost to guessing
which stage dropped the title; the trace named it in one. It is **Java-only
on purpose**: the JS half (a `sendVariants` emit log + a bridge
title-provenance log) was added at the same time and then removed, because
the native "emit:" line already carries the string JS produced, so the JS
copies only ever answered a question the Java trace had already answered.
If a name arrives wrong at `emit:`, THEN log inside the bridge.

- **Progressive HTTP — `HttpDownloadStrategy`.** Default request sends **no
  Range** (some servers require a range, others reject one). It **reacts to
  partial content**: if the body ends short of `Content-Length` — thrown
  "unexpected end of stream" *or* a clean early EOF — it re-requests
  `Range: bytes=<have>-` and appends until complete (bails on no-progress / a
  resume cap). It **also reacts to a rejected plain GET**: a fresh no-Range
  request answered with **403/404/416** is retried **once** with
  `Range: bytes=0-`, for streaming endpoints that *only* serve ranged requests
  (e.g. krakencloud's `/play/video/<token>` on series.ly — the browser plays it
  with `Range: bytes=0-` → 206, a plain GET is refused). Reactive on purpose: it
  fires only after the plain GET was rejected, so a range-**hostile** server
  (which answered the plain GET) is never sent a Range. One mechanism for CDN
  anti-leech truncation (e.g. Bilibili `upos/bilivideo` caps a plain 200 at
  ~1 MiB but serves 206 in full), chunked short reads, mid-stream disconnects,
  and range-required endpoints. Don't reintroduce an unconditional Range default
  — it's site-specific thinking and breaks range-hostile servers.
- **Streams (HLS/DASH/segments) — ffmpeg via `FFmpegOkhttp`.** ffmpeg's HTTP is
  **not** native `http.c`; it's bridged to our OkHttp client by `FFmpegOkhttp`
  (a custom AVIO handler). It does Range/206 properly: accepts 206 as success,
  parses `Content-Range`, honours ffmpeg `offset`/`end_offset`, and **resumes
  from `mReadPosition` on an early clean EOF** (the twin of
  `HttpDownloadStrategy`'s resume, so the stream path is covered against the
  same mid-stream truncation).
  - **The range-REQUIRED retry fires on 403/404/416, NOT 416 alone — keep it in
    lockstep with `HttpDownloadStrategy`.** A server that only serves ranged
    requests refuses the bare GET with whatever code its front end picks:
    krakencloud (series.ly) **404**s it, IIS anti-leech 416s it, others 403.
    Both paths meet the same endpoints — the `HttpDownloadStrategy` content
    backstop can hand a URL straight to `FFmpegMuxStrategy` — so a rule that
    holds on one and not the other is just a latent gap (the bridge was
    416-only until this was noticed). The cost on a genuine 403/404 is one
    extra request, one-shot per URLContext. The **opposite** branch (we sent a
    Range and it was refused) stays **416-only**: a 403/404 on a ranged request
    is an authorization or missing-resource answer, not a statement about
    ranges.
  - **The bridge is split CONFIG vs LEARNED STATE — keep it that way.**
    `DemuxerRequest` is what the demuxer asked (`seekable`, `offset`/
    `end_offset`), parsed **once** from ffmpeg's option map and then immutable;
    `RangeSupport` (`UNKNOWN`/`ACCEPTED`/`REQUIRED`/`REFUSED`) is what we have
    learned about the server, and is the only thing that moves. That split is
    load-bearing, not tidiness: the map arrives again on every re-open the 416
    branches make, so while these shared mutable fields a re-parse silently
    RESURRECTED the demuxer's values on top of state we had moved — clearing
    `seekable` to stop a branch re-firing did nothing, and it recursed one HTTP
    request per stack frame until `catch (Throwable)` caught the StackOverflow.
    Each `RangeSupport` transition is guarded on the current state, so it is
    one-shot by construction with no separate "already tried" flag to fall out
    of step. Don't reintroduce parallel booleans (`seekable`,
    `serverAcceptsRanges`, `demuxerRange`, `rangeRejected`, `forceFullRange`
    were five, and every bug in this file was two of them disagreeing), and
    don't re-read the option map after the first open.
  - **Every successful seek returns through `seekReached(targetPos)`**, which
    fails rather than report a position we are not actually at. The avio
    contract above is un-checkable at the call site and was rediscovered three
    times as three separate bugs; the helper is what stops a fourth.
  - **`seekable` is TRI-state and only `"0"` means no** — ffmpeg's AVOption is
    `0` disable / `1` enable / `-1` **auto** (the default). `setOptions` must
    read it that way. It once read `!"-1".equals(seek)`, which inverted exactly
    the two values that occur: hls.c passes its `http_seekable` default of `-1`
    (so every HLS segment was marked un-seekable and a long seek reopened with
    no Range while the position counter claimed the target offset), while
    dashdec.c passes `"0"` for live streams *specifically to suppress the Range
    header* — and that was read as "do send one". hls.c's own comment states
    the intent: "Some HLS servers don't like being sent the range header … set
    http_seekable = 0 to disable the range header."
  - **A DEMUXER-supplied Range makes the body a SLICE — deliver it verbatim.**
    `offset`/`end_offset` (hls.c:1403 for `#EXT-X-BYTERANGE`, dashdec.c:1738 for
    a DASH SegmentBase track — the Bilibili.tv whole-track `.m4s` path) mean the
    bridge's own two numbers stop describing the body: `mReadPosition` counts
    from the start of the SLICE (ffmpeg says so at hls.c:1444 — avio's
    "bookkeeping of file offset … is out-of-sync with the actual offset when
    'offset' AVOption is used"), and `mStreamLength` comes from Content-Range's
    TOTAL, i.e. the whole resource. So on such a connection (`demuxerRange`) the
    bridge invents no Range, never resumes — a slice read to completion is
    SHORTER than mStreamLength and would otherwise look truncated — and treats a
    416 as a real error instead of falling back to a Range-less GET, which would
    hand the demuxer the entire resource where it asked for one slice.
  - **The 416 fallback must be ONE-SHOT via its own flag, not via `seekable`.**
    Clearing `seekable` cannot guard it: the recursive re-open re-runs
    `setOptions` over the same options map, which sets `seekable` straight back
    to true, so a still-416ing Range re-enters the branch every time — one HTTP
    request per stack frame until the frame's own `catch (Throwable)` catches the
    StackOverflow. Hence `rangeRejected`. (This is only reachable once `seekable`
    is parsed correctly, so it shipped and was fixed in the same session as the
    parse fix; verified with a state-machine simulation of the three
    configurations — old parse: 1 request, new parse + old guard: unbounded, new
    parse + new guard: 1 request.)
  - **A seek CANNOT report its position — only success or failure — so reaching
    the target is on us.** `avio_seek` reads just the SIGN of the protocol
    seek's return, then sets its own `s->pos` to the offset it ASKED for
    (aviobuf.c: `if ((res = s->seek(...)) < 0) return res; … s->pos = offset;`).
    So after a Range-less reopen, returning 0 does **not** tell ffmpeg we are at
    0 — it records targetPos and reads bytes that start at 0. Every
    non-negative return must therefore mean the stream really is at targetPos;
    only a NEGATIVE return says "I could not".
  - **On a 416 we reopen Range-less and WALK to the target — ffmpeg will not do
    it for us.** Vanilla `http_seek_internal` restores the previous connection
    and returns the error on a failed reopen; it never retries without the
    Range. So the bridge owns that recovery, and per the rule above a bare
    reopen is not enough: `performSeek` reopens at byte 0 and then discards
    forward to targetPos (`skipForward`). That is ffmpeg's own technique — the
    `uint8_t discard[4096]` drain loop in `http_seek_internal`, and avio's
    `while (s->pos < offset) fill_buffer(s)` short-seek branch. Bounded by
    `MAX_NO_RANGE_SKIP_SIZE` (8 MiB) because a demuxer seeks repeatedly and each
    backward seek restarts the walk from 0; past the bound, and for a
    `demuxerRange` slice (whose offsets a reopen drops, hls.c:1448 declining the
    same seek), it returns `FFMPEG_AVERROR_ENOSYS` → `AVERROR(ENOSYS)`, the same
    "not seekable" the SEEK_END-without-length case already uses. Forward seeks
    walk on the LIVE connection first, so they never re-download. Don't replace
    the walk with a bare reopen "because the 416 branch already resets
    mReadPosition" — that reset is exactly the thing avio_seek ignores.
  - **There is NO range chunking, and it should not come back.** The bridge
    used to reopen every >2 MB body in bounded 10 MB `bytes=a-b` windows. It
    never did anything useful: it can only arm when `seekable`, which under the
    parse bug meant never for HLS and *inverted* for live DASH; the 2 MB
    threshold sat below the 10 MB window, so every body in between paid one
    extra reopen to fetch what one request already would have; and it made the
    connection pool churn on the one path (`FFmpegMuxStrategy`) that reopens the
    same host hundreds of times. Its one real function was accidental — the
    early-EOF resume was gated on the chunking flag, so truncation recovery
    silently didn't exist for anything chunking declined to arm for. That gate
    is now the resume's own precondition (declared length not yet reached +
    `seekable` + observed server range support + one attempt, with the reopen's
    206 verified), which is what it should always have been.

Headers (incl. any backfilled `Referer`) flow from the capture layer
(`webrequests/requests.js` for the generic catcher, or a parser's
`requestHeaders`) into both paths via `context.getHeaders()`.

### Cancel must evict idle pooled connections — the HTTP/2 discard loop

Cancelling a download/probe mid-stream only closes the response body, which on
HTTP/2 resets the **stream** (`RST_STREAM`) — the pooled **connection** stays
open. A server with an endless byte source (a live-stream CDN; Kick was the
on-device case) keeps pushing DATA frames for the dead stream, and OkHttp's
"correct" handling becomes a trap: discard the bytes, queue a `writeSynReset`
per frame, and **replenish the connection-level flow-control window** — so the
server is never throttled. ~2000 frames/sec until the ConnectionPool's
**5-minute** idle keep-alive finally sends GOAWAY (observed:
`Q10560 finished run in 312 s : OkHttp stream.kick.com`, ending exactly at the
pool closer). While it spins, the dead transfer saturates downlink bandwidth
and (on debug builds) log/string churn drives GC thrashing — the user-visible
symptom is "every tab keeps loading / app not responding", which looks like a
Gecko bug and isn't.

Fix shape: `NetworkModule.evictIdleConnections()` (`connectionPool().evictAll()`
— closes **idle** connections only, so an active concurrent download can never
be stopped by it; its worst case is one fresh TLS handshake on its *next*
request) is called on every **user-cancel** path, *after* the cancelled
connection has been released (it must be idle to be evictable):

- `FFmpegOkhttp.interruptedReturn()` + `okhttpClose()` (gated on the thread
  interrupt flag) — the Java-interrupt cancels.
- `FFmpegMuxStrategy`/`FFmpegMergeStrategy` after `downloader.start()` returns,
  gated on `stopped`/`context.isInterrupted()` — **required** because
  `RunnableManager.cancelAll()` signals ffmpeg through the **native** interrupt
  flag and never sets the worker's Java interrupt status, so the `FFmpegOkhttp`
  gates can't see that cancel.
- `HttpDownloadStrategy`'s `finally`, same gate — a cancelled progressive
  download of a large file has the same exposure.
- `GeckoInspectTask.cleanupFFmpeg()` gated on `isCancelled()` — a tab close
  interrupting a capture probe (`cancelTab` → `GeckoInspectTask.cancel()`).

Rules: keep it **host-agnostic** (no `kick.com` conditions — any HTTP/2 server
that keeps sending reproduces it; same transport rule as `FFmpegOkhttp`), and
**cancel-only** — never evict on normal completion, seeks, or resume
reopens (the warm pool is the common-case win; an HLS download reopens the
same host hundreds of times). The call is idempotent and cheap; multiple call
sites firing for one cancel is fine. Known limit: `evictAll()` closes what is
idle *at that instant* — a cancel racing the release window falls back to the
5-minute janitor, which is acceptable (the systematic hang is what's fixed).

### Capture-layer headers & cookies (how a re-download authenticates)

A captured media URL is re-fetched later by the native downloader, so it must
carry the headers + cookies the browser's original request had or the CDN 403s
it. How each is obtained (current architecture):

- **Request headers — `webRequest` is the backbone.** `requests.js` listens on
  `onSendHeaders` (with `['requestHeaders']`) + `onHeadersReceived` and **caches
  the request headers keyed by URL** (`cacheHeaders`/`getCachedHeaders`). When a
  media URL is emitted, its cached headers ride along on the `sendNative` message.
  Entries are tagged **page-context vs extension-context** (`fromExtensionContext`):
  headers from a request the *extension itself* issued get `Origin`/`Referer`/
  `Sec-Fetch-*` **sanitized** (`sanitizeHeadersForPage`) so we don't leak the
  moz-extension origin; page-context headers are used as-is. `Referer` is
  backfilled from the page URL when absent.
- **Cookies — `browser.cookies.getAll`, NOT `document.cookie`.** Session/auth
  cookies are usually `HttpOnly`, invisible to page JS. `cookies.js`
  `handleCookieRequest` answers the native `getCookiesForUrl` message by calling
  `browser.cookies.getAll({url})` (privileged → **includes HttpOnly**) and
  returns a built `Cookie` header string. So cookies are pulled from the browser
  jar on the native side's request, not scraped from the page.
- For headers on **content-script-discovered** URLs (next section), see the
  `HEAD`-probe backfill there.

The captured header set + cookie are reused for the whole stream — for HLS/DASH,
ffmpeg propagates them to every sub-request (master/playlist/segment/key); see
"Per-site request quirks" below.

### Three capture sources — wire, DOM, inject (webRequest is NOT enough alone)

The generic catcher has **two** sources, because `webRequest` alone misses media:

1. **Wire — `webRequest`** (`requests.js`): media fetched over HTTP. The backbone,
   and the only source that gets request headers + cookies "for free". But it
   **only fires for requests it actually observes** — it misses media that loaded
   from cache, before the listener attached, or that lives in a DOM attribute
   without a fresh request.
2. **DOM — content script** (`content-script.js`): scrapes the page for media the
   wire never showed. It reports `<img>` / `<source>` `src`/`srcset` **and
   `<video>`/`<audio>` `src`/`currentSrc`** URLs (with a `MutationObserver` on
   `src`/`srcset` for dynamically-added ones) to the background, **passively
   scrapes embedded media URLs from the page source** (see "Passive
   embedded-media scrape" below — this is what captures a video before the user
   presses play), and **separately scrapes JSON-LD `VideoObject` + `og:`/
   `twitter:` metadata** to enrich captures with accurate title/description (on
   video SPAs the `<title>`/`og:title` are usually the generic site name, so
   JSON-LD/`og:video:title` rank higher). A DOM-discovered URL has **no cached
   headers**, so `requests.js` does a `HEAD` `fetch(url, {credentials:'include',
   referrer: tab.url})` to populate the header cache via `onSendHeaders`, then
   forwards the (sanitized) result through the same emit path. So: content script
   *finds* it, the HEAD probe *authenticates* it.
3. **Page-world state** — read via the generic `wrappedJSObject` bridge
   (`webrequests/js/page-state-bridge.js`, `<all_urls>`), for media inlined into a page
   JS global with no XHR (Bilibili.tv). See "Page-world state — the generic
   `wrappedJSObject` bridge" above. This **replaced** per-site inject pairs;
   prefer `wrappedJSObject` from a content script over a `<script>`/WAR inject.
   (TikTok used an inject too, retired once `filterResponseData` + the 0006
   SW-visibility patch could read the feeds; see the TikTok section. A WAR inject
   is now only for what `wrappedJSObject` genuinely can't reach.)

**MSE / `blob:` is not special.** A `blob:` URL on a `<video>` is just a handle to
a `MediaSource`, never the download target — but MSE/HLS/DASH players still
`fetch`/XHR their manifest + segments over HTTP, so those are ordinary requests
the wire source already catches (and the content script catches `<source>`s). So
there's **no `blob:`-specific path**. An inject is only needed for what's
structurally invisible to *both* wire and DOM: ServiceWorker-*synthesized*
responses (the inject's `fetch`/XHR hook runs before the SW), segments assembled/
decrypted in JS with no network fetch, and single-use/signed URLs that capture
fine but can't be re-fetched (there you need the bytes, not the URL). Reach for an
inject only for those.

### Obfuscated manifests — body-sniff, never MIME-trust (`filterResponseData`)

GeckoView has **no native HLS/DASH** — every adaptive stream plays through
**hls.js / dash.js over MSE**, and those parse the manifest **body** (`#EXTM3U` /
`<MPD>`) and **ignore its HTTP `Content-Type`**. So a site can serve a real
playlist at an **extensionless URL with a bogus `text/html` mime** and it still
plays, while the header/extension classifier (`validateAndClassify`) correctly
drops it — a capture miss that **no MIME check can close** (the player never
trusts the MIME, so neither can we). The only ground truth is the bytes.

`requests.js` closes this with a **bounded** `filterResponseData` body-sniff
(`isManifestSniffCandidate` / `decideManifest`, registered on a dedicated
`onHeadersReceived` + `['responseHeaders','blocking']`):

- **Armed only for the obfuscated-manifest shape:** a `GET` the classifier
  rejects, `type` `xmlhttprequest`/`other` (a JS player fetch), **no media
  extension**, not regex/parser-blocked, and a **non-media, manifest-plausible**
  content-type (`text/html` without `nosniff`, `text/plain`, `octet-stream`,
  empty). Everything else fails the gate without ever touching a filter — cheap
  on the hot path.
- **Non-blocking, byte-exact, first-bytes-only — and never `disconnect()`s
  mid-stream.** Every chunk is written straight back (`filter.write`, the same
  write-through the parser's `filterResponseText` uses — no refetch, no
  perturbation); only the first `SNIFF_MAX_BYTES` (1 KB) is *decoded* to decide
  (`#EXTM3U` → HLS, `<MPD` → DASH, any other leading byte → not a manifest), after
  which it keeps passing the body through **unread** to `onstop`/`close()`. The
  early-`disconnect()` optimisation was dropped on purpose: a `disconnect()` over
  a **ServiceWorker-synthesized** response (the stream the geckoview `0006` patch
  exposes) has no confirmation it resumes the remainder cleanly, so we don't risk
  truncating the page's own `fetch`. A `Content-Length` over
  `SNIFF_MAX_DECLARED_BYTES` (4 MB) skips arming, so a big non-manifest body is
  never write-through'd just to read its head.
- **On a hit** it re-enters the normal emit via `processResponse(…, type:'media',
  skipClassify=true)` — header/cookie recovery, tab/meta, native dedup all apply;
  download is muxed by ffmpeg (and `HttpDownloadStrategy`'s `#EXTM3U`/`<MPD>`
  content backstop covers it even on the raw path). No new native plumbing — it's
  an ordinary media capture whose *only* novelty was being found by content.

This is the wire source's third gap-closer (alongside the DOM scrape and the
page-state bridge); it is **not** a reason to add a per-site inject. A site whose
manifest needs richer metadata still belongs in a parser (declared `manifest`),
same cardinal rule.

### Passive embedded-media scrape (capture without playback)

Many sites **inline the real progressive/HLS URL in the page source but only
*fetch* it on play** — so the wire source never fires and nothing is captured
until the user presses play. Example: El Periódico Mediterráneo
(`elperiodicomediterraneo.com`) ships the mp4 only inside
`window.dataLayer.push({video:{url:"…mp4"}})` — no `<source>` element, no
`og:video`, no playurl XHR. The content script's `scrapeEmbeddedMedia()` closes
this gap **passively** (no play needed), in two tiers:

- **Tier A (declared, low noise):** `<video>`/`<audio>` direct `src`,
  `og:video`/`og:video:secure_url`/`twitter:player:stream` meta tags (only when
  the content is itself a media-extension URL — an og:video that points at an
  embed *page* is left to the wire/HTML path), and JSON-LD
  `VideoObject.contentUrl` (`embedUrl` is a player page — skipped).
- **Tier B (targeted):** a media-extension URL inside an inline `<script>` that
  sits **next to a media-ish key** (`url`/`contentUrl`/`file`/`src`/`hls`/`dash`/
  `playable_url`/… — `SCRIPT_MEDIA_RE`). The key-proximity requirement is what
  makes the blind script scan targeted: it keeps us off the many unrelated
  absolute URLs in ad/analytics blobs, while the media extension itself already
  excludes most junk (a canonical page URL or poster `.jpg` next to `"url":`
  won't match). Handles escaped JSON slashes (`https:\/\/…`) and
  protocol-relative (`//host/…`) URLs. Bounded: a 4 MB total char budget, a
  40-URL/pass emit cap, and each inline script scanned at most once (`WeakSet`).

It runs at **DOMContentLoaded/load**, not `document_start` (the data lands during
parse) and **not on every mutation** (these SSR shapes are present at first load;
the element-level `scan()` + `MutationObserver` still cover SPA-injected
`<video>` nodes). Everything queued rides the **same `images-detected` path** as
DOM images: the background **reclassifies by extension** into a media capture
(`classifyByUrl`/`getTypeFromUrl`), **HEAD-probes** for headers/cookies, applies
the **parser block-list** (`validateAndClassify` → `matchInParserBlocklist` +
the generic `matchInRegex` — so a parser-owned site doesn't get a duplicate bare
capture; the cardinal rule still holds), and the **repository dedups** against a
later wire capture if the user *does* play. So no new
transport, no Java changes — discovery only. On by default; the precision/noise
trade-off is held by the Tier-B key-proximity filter, not a setting.

This is **not** the "don't read inline `<script>` data from the DOM" Threads
anti-pattern: that warning is specifically about **Meta's bootstrap**
(`<script data-sjs>`), which `ServerJSPayloadListener.process` *consumes the
instant it parses* — by the time a content-script observer runs, the blob is
gone. Ordinary SSR data blobs (`dataLayer`, Redux state, JSON-LD) are **not**
self-consumed and remain readable at DOMContentLoaded, which is why the passive
scrape works on them. Meta sites have dedicated parsers anyway.

### Manifest vs progressive — declared, never URL-sniffed

`DownloadTask.selectStrategy` routes from the entity: separate `audioUrl` →
`FFmpegMergeStrategy`; `UrlType.MEDIA`/`TS` (`usesFFmpeg()`) → `FFmpegMuxStrategy`
(HLS/DASH); else → `HttpDownloadStrategy` (raw). The MEDIA-vs-FILE decision for
skip-probe variants must **not** be guessed from the URL extension — obfuscated/
tokenized manifests carry no `.m3u8`/`.mpd`, and signed URLs append a `#fragment`
(Dailymotion: `…/manifest.m3u8#cell=cf3`). Two layers, defense in depth:

- **Declared (source of truth).** The code that enumerated the master marks it:
  `M3U8Parser`/`processHlsMaster` → `VariantProcessor(…, manifest=true)`, and the
  JS `parseHlsMaster` path sets `manifest:true` on the `sendVariants` message
  (→ `JsonHelper` → `GeckoInspectEntity` → `GeckoInspectTask` → `VariantProcessor`).
  `VariantProcessor` sets MEDIA on declared-manifest **or** separate-audio; the
  URL regex (`MANIFEST_URL`, `[?#]`-tolerant) is only a fallback. Progressive is
  the default so a tokenized extensionless mp4 (TikTok) isn't needlessly remuxed.
- **Content backstop (ground truth).** `HttpDownloadStrategy` peeks the response
  before writing — `#EXTM3U` (HLS), an `<MPD>` XML (DASH), or a manifest
  Content-Type → hand off to `FFmpegMuxStrategy` instead of saving the playlist
  text as the file. Catches anything misclassified onto the raw path, **esp. the
  generic catcher's obfuscated manifests** (no parser to declare them). `stop()`
  forwards to the delegate; checked only on a fresh (non-resume) request.

Don't reintroduce extension-only manifest detection as the load-bearing test —
it's a fallback at best. (`UrlStringUtils`' SVG/ICO/ADAPTATIVE patterns were also
`?`-only and missed `#fragment`s; now `[?#]`.)

### Progress reporting (`downloader_mux`)

Mode is decided once for the whole download: **TIME** (muxed position vs.
duration — the normal path, incl. HLS/DASH VOD), **SIZE** (bytes vs.
Content-Length, progressive only), or **NONE** (indeterminate). For TIME the
reported position is the **minimum** of the per-stream accumulators: with split
audio+video the two advance at different rates, so reporting the current
packet's stream made the bar jump backward — the min is monotonic and is the
position every track has reached. SIZE is never used for HLS/DASH because their
probe `Content-Length` is the *playlist* size, not the media.

The TIME position is clamped on **both** ends. The lower clamp (non-regress, in
`downloader_muxed_position`) keeps the bar from stepping backward when a stream
flips between stalled and advancing. The **upper** clamp (in the
`PROGRESS_TIME` branch) keeps it from exceeding the total: the denominator is
ffmpeg's *probe-estimated* duration (`input_format_ctx->duration`, fixed once),
but on a **discontinuity-spliced VOD (Twitch/Kick ad-stitching)** the MPEG-TS
PTS **resets** at every `EXT-X-DISCONTINUITY`, so `find_stream_info`'s pts-span
estimate **underestimates** the true length while `current_recording_time`
accumulates the real summed `pkt->duration` and grows **past** it — the
on-device symptom was progress reported at **165 %+ and climbing** on a Kick
m3u8 (both V and A accumulators agreed and overshot together, so it was the
denominator that was short, not the min/stall logic). The accumulator is the
better measure of muxed output, but it's capped at the total so the bar
saturates at 100 % (matching the `recording_time, recording_time` completion
callback) rather than showing a nonsensical >100 %. Don't "fix" it by switching
these to SIZE (playlist bytes) or NONE (indeterminate) — both are worse UX than
a saturated TIME bar.

### HLS master stream selection — pair audio to the video's PROGRAM

When the **generic catcher** captures a multi-variant HLS **master** (no
dedicated parser — e.g. X/Periscope `master_dynamic_*.m3u8` on `video.pscp.tv`),
the qualities are enumerated by ffmpeg-probing the master, and each quality is a
`(videoStreamNumber, audioStreamNumber)` pair fed to the native downloader, which
opens the master and captures those two stream indices. The audio index is paired
in `FFmpegMetaDataReader.getRelatedAudioNumber` **by bitrate equality** — and a
muxed master reports per-stream `bit_rate = 0` for every stream, so the match
collapses to the **first** audio stream. Every quality but the lowest then gets an
audio stream from a **different variant program** than its video (e.g. 2160p video
+ 480p audio). Two un-discarded streams in two different child playlists → ffmpeg
keeps **both** playlists live and demuxes them at once → `downloader_read`'s
type-match remap flip-flops between the two same-type streams and muxes two PTS
epochs into one track → unplayable file (the on-device tell: a flood of
`re-bind … (discontinuity remap)` **and** `clamping dts` alternating between two
timestamp ranges).

The fix is **not** in the read-loop remap (that faithfully muxes whatever streams
it's told to take) — it's in `downloader_find_streams`, made **program-aware the
way ffmpeg's own CLI is**: an HLS master puts each variant's streams in their own
`AVProgram`, and `av_find_best_stream(ctx, AUDIO, /*wanted*/-1, /*related*/<video>)`
scopes the search to the video's program (`av_find_program_from_stream`) and
returns the audio muxed with that rung. So when the Java-recommended audio index
lands in a **different program** than the selected video (a genuine `nb_programs
> 1` master, same input), we discard it and let `av_find_best_stream(related=video)`
pick the program-paired audio. Both selected streams then live in **one** program
→ only that child playlist is downloaded → clean single-rendition mux. Scoped so a
single-rendition child (Twitch/Kick via `processHlsMaster`, one program) and
separate-audio inputs (DASH/YouTube, different input) are untouched, and it falls
back to the recommended index if the video's program genuinely has no audio.

Two layers, defense in depth. The **enumeration** is fixed at the source too:
`FFmpegMetaDataReader.getRelatedAudioNumber`/`getRelatedAudioCodec` only trust the
bitrate pairing when the video's bitrate is **> 0**; on a muxed master (all
per-stream `bit_rate = 0`) they now return `UNKNOWN_STREAM` (**-1 = "auto"**, the
`FFmpegDownloader` contract) instead of confidently emitting the first audio. So
the quality list no longer carries a wrong audio index — native auto-selects the
program-paired audio. The native cross-program override above remains as a
backstop for any caller that still passes a non-`-1` audio index that crosses
programs. Don't reinstate the unconditional `bitrate == getBitRate()` match — the
0==0 collapse is exactly the bug.

**This did NOT make the `downloader_read` type-match remap redundant — keep it.**
It's tempting to think the Kick/Twitch remap and this Periscope corruption were
one bug (both fire the same `re-bind (discontinuity remap)` log), but they're
distinct root causes and the remap fixes one the audio selection can't touch:
- The remap follows **MPEG-TS AVStream index renumbering/reuse across an
  `EXT-X-DISCONTINUITY` WITHIN a single rendition** (a Kick preroll ad on pid
  0x101, then main content reusing index 1 for *video* → a frozen index map feeds
  H264 into the audio slot → `aac_adtstoasc` abort). Stream **selection** picks
  indices once at find time; it fundamentally **cannot** follow an index that gets
  reused mid-download — only matching by codec **type** + re-bind can.
- Twitch/Kick go through `processHlsMaster` (skipProbe) and download a **single
  child rendition = one program**, so they **never run** `getRelatedAudioNumber`
  and never had the cross-program audio bug. Their need for the remap is the
  within-rendition discontinuity above, unchanged by this fix.
- Periscope only *looked* like the Kick bug: the remap was the victim, fed **two
  concurrent programs** by the mis-paired audio. Fixing selection removes the
  second program from its input; the remap then (correctly) follows only genuine
  within-rendition discontinuities in the selected rendition. So both the
  audio-selection fix AND the remap are load-bearing, for different scenarios.

**The remap itself is program-gated for a multi-program master input — the
Reddit `v.redd.it` green-pixel bug.** The remap's own assumption ("a multi-
resolution ABR master is never one input here, each rendition is its own playlist
and we download one") is **false** when the generic catcher captures the HLS
**master** of a parser-less site: the download input is then the whole ABR ladder
in ONE `AVFormatContext`, N renditions each in its own `AVProgram`. ffmpeg
delivers packets from MORE playlists than the one we discarded down to (a
video-only Reddit CMAF master = 6 programs `Stream #0:0..#0:5`, 1080→220, no
audio), and a bare codec-**type** remap re-binds the single video capture slot to
**every** rendition in turn (`re-bind 0->1->2->3->4->5->0…`), muxing six
resolutions into one track → `Invalid NAL unit size` / `missing picture` /
`Packet corrupt` / a `clamping dts` storm → an **unplayable green file**. Fix
(`downloader_read`, mirrors the audio program-affinity above): when
`fmt_ctx->nb_programs > 1`, accept a packet only if its stream shares the
`AVProgram` of the stream we actually selected (`av_find_program_from_stream`);
a packet from another program is a different rendition we discarded — drop it.
Scoped to `nb_programs > 1`, so a single child rendition (Twitch/Kick via
`processHlsMaster`), a separate-audio DASH/YouTube input, and progressive files
are untouched and keep following the **within-rendition** TS renumber above
(the two are distinct and both load-bearing). Known remaining limit: ffmpeg
still *reads* the discarded sibling playlists (the per-stream `AVDISCARD_ALL`
isn't fully honored by the hls demuxer for this master shape), so the download
fetches the other renditions' bytes too — wasteful but harmless now that their
packets are dropped instead of muxed; a future hls.c discard fix in the fork
could avoid the extra fetches. The proper long-term cure is a Reddit **parser**
that emits one chosen rendition (a single child playlist = one program), which
sidesteps the master-as-input entirely — same reasoning as every other
parser-owned site.

### Per-site request quirks live in the parser, never the transport

`FFmpegOkhttp` / the fork's `http.c` (the ffmpeg↔OkHttp bridge) is **generic**
and must carry **no host-specific conditions**. Any header a site needs is
expressed as *data* in that site's parser `requestHeaders` (the `sendNative`
emit). ffmpeg then propagates those headers to **every** sub-request of the
download — master, media playlist, segment, **and the AES key** (hls.c fetches
the key via `open_url(..., &c->avio_opts, ...)`, and `avio_opts` is copied from
the master's options). So a header a site needs *only* on its key fetch still
belongs in the parser emit, not in a transport `if (url.contains(host))`.

The bridge also never needs host logic to keep a key fetch clean: it only adds a
`Range` for a resume (`pos>0`) or when the demuxer itself asked for one
(`offset`/`end_offset`), so a 16-byte, offset-0 AES key is never ranged for
**any** site.

#### Niconico domand AES key — the "endless probing / 720p hangs" bug
**Root cause: the domand AES key is SINGLE-USE per session.** The key endpoint
(`…/keys/<rendition>.key`, per-session signed URL) returns the real 16-byte key
only on the **first** fetch; every later fetch of the *same URL* returns a
different **garbage decoy** (HTTP 200, no error). Firedown opens the stream
**twice** per session — `metadatareader` probes it (burns the real key), then
`downloader` opens it again and gets a decoy. Wrong key → AES-CBC garbage (no
integrity check) → the `mov` demuxer reads a phantom multi-hundred-MB box and
`avio_skip`s it across the whole track → `find_stream_info` walks every segment
to EOF → the hang. It scales with rendition size, so tiny renditions tolerate
it while 480p/720p hang. Within one `avformat_open_input` the key is fetched
once and cached by URL, so the duplication is across the two separate opens.

**Fix (SHIPPED, in the fork — not an app-flow tweak):** a process-global AES-key
cache in `libavformat/hls.c` `read_key`, keyed by the **full signed key URL**,
first-writer-wins, FIFO-16, `AVMutex`-guarded — the probe's real key is reused
by the downloader instead of fetching a decoy. See `firedown-ffmpeg/CLAUDE.md`
and `firedown/patches/0004-hls-c-single-use-key-cache.patch` (generator +
`apply-firedown-patches.sh`, marker `FIREDOWN-HLS-KEYCACHE`); needs a `.so`
rebuild + `scripts/sync-ffmpeg.sh`. Confirmed on device.

**Keep it unconditional** — do not gate behind an AVOption. `metadatareader`
(open #1) is always the first key consumer, so it always gets the real key and
the item always shows in Capture *regardless of any option*; only `downloader`
(open #2) needs the cache. A gated cache not set on both opens gives the worst
UX — "shows in Capture, then hangs on download". It's URL-keyed (no cross-content
collision) and for a normal VOD the cached bytes equal a re-fetch, so reuse is
transparent. (If a site ever misbehaves, prefer opt-**out** over opt-in.)

**Rotating keys:** a new key URL per `#EXT-X-KEY` is fully handled (each URL is
its own entry). Same-URL/changing-bytes rotation is NOT (that's live HLS;
Firedown downloads VOD) — recovery there is app-level (re-run the parser to mint
a fresh session), not in hls.c. *Refresh-on-garbage* is DEFERRED: a blind
re-fetch returns a decoy and hls.c can't re-mint a session; it would need a
mov→hls feedback channel that doesn't exist yet.

**Diagnostic discipline (this bug ate ~10 rounds on confounds):**
- A clean test = a FRESH session where ffmpeg is the FIRST thing to touch the
  key. Any run that is the 2nd+ consumer gets a decoy and walks — it looks
  identical to the bug but proves nothing. (Most throwaway scripts self-poisoned
  by fetching/decrypting the key before ffmpeg ran.)
- Confounds tested and DISPROVEN — do **not** revisit: `X-Frontend-Id`, cookies,
  `Range`, seekability/`is_streamed`. (The `http.c` `is_streamed = (total<=0)`
  fix is a *separate* read_header-walk bug; it does not affect this
  find_stream_info key-walk.)
- The walk is ffmpeg's reaction to an undecryptable stream, not a demuxer bug:
  `probesize`/`analyzeduration` don't bound it (garbage produces no packets).
  Stock `ffmpeg -i <master>` on a PC reproduces it (no `X-Frontend-Id`) — same
  wrong-key cause, not a transport bug.

## Security toggles & default inversion (the JIT/WASM pattern)

Several "harden the browser at a cost" switches in the Security settings
category are **disable-X** toggles that default **OFF** (the feature is on by
default; turning the switch on hardens at a performance/compat cost):
`SETTINGS_DISABLE_WASM`, `SETTINGS_DISABLE_WEBGL`, `SETTINGS_DISABLE_JIT`.

JavaScript JIT is the canonical case. JIT widens the attack surface, so a
"disable JIT" control belongs in the advanced/Security section — but disabling
it globally noticeably degrades complex sites, so it must be **enabled by
default** (most users should never touch it; only the security-conscious turn it
off). `setJITCompiler(!disable)` is read at boot in `GeckoRuntimeHelper`
(inverted) and on change in `SettingsFragment`; it sets
`javascript.options.baselinejit` + `…wasm_baselinejit`; changing it restarts the
browser.

**When flipping an enable→disable default, always introduce a NEW preference
key** (`…enable.jit` → `…disable.jit`). The stored boolean can't be reused: an
old user who never touched the opt-in `enable` pref has `false` saved, which
under the new default-enabled semantics would read back as "JIT off" and silently
keep them on the old baseline. A fresh key lets existing installs fall to the new
default. (Same reasoning as the `SETTINGS_DISABLE_WASM` migration.) Rename the
string resources to match (`settings_jit_enabled*` → `settings_jit_disabled*`)
and update **all** locale files, not just English.

### UTC timezone spoofing toggle (FPP target, not a code patch)

`SETTINGS_SPOOF_TIMEZONE` is an **enable-style opt-in** (default OFF, like Resist
Fingerprinting — UTC clocks confuse calendar/scheduling sites). It does **not**
need a GeckoView patch: FPP is already enabled at boot
(`setFingerprintingProtection(true)`), so `JSDateTimeUTC` is a stock target.
`GeckoRuntimeHelper.setTimezoneSpoofing` just flips it via the **global**
`privacy.fingerprintingProtection.overrides` pref (`"+JSDateTimeUTC"` on, `""`
off) — read at boot and on change in `SettingsFragment` (no restart; applies on
next page load, like RFP). **Crucially, that GLOBAL `overrides` pref is distinct
from the per-site `granularOverrides`** pref. The latter is no longer set by
anything (it used to scope CanvasRandomization to tiktok.com via the now-removed
`applyTikTokFingerprintingOverride`); keep these two prefs distinct — if a
per-site override is ever reintroduced, it must not fold into this global
`overrides` pref. This is deliberately the no-patch route over IronFox's
`nsRFPService` code patch + custom bool pref (firedown-geckoview CLAUDE.md has the
rationale). New string keys (`settings_utc_timezone*`) are translated across the
same 16 locales the JIT toggle uses; the remaining (already-partial) locales fall
back to English (MissingTranslation isn't build-fatal here).

### GeckoRuntime hardening prefs (`applyHardeningPrefs`) — privacy vs. breakage

`GeckoRuntimeHelper.applyHardeningPrefs` sets a cluster of always-on IronFox-
derived privacy prefs at boot (distinct from the user-facing HTTPS-only / disk-
cache / Safe-Browsing toggles, which each have visible consequences and stay
opt-in). The selection rule is **"high privacy gain, near-zero site breakage"** —
these run for *every* user with no off switch, so a pref that silently breaks
real sites fails the rule even if its privacy value is real. Two lessons live
here:

- **Referer `XOriginPolicy` must be `0`, NOT `2` (the pixiv-images bug).** `2`
  means "send a cross-site Referer **only when the base domains (eTLD+1) match**",
  which **strips the Referer entirely** on any request to a different base domain.
  That silently breaks every site whose media/asset CDN lives on a **separate base
  domain behind Referer-based hotlink protection** — the CDN sees no Referer and
  returns **403**. The reported case was **pixiv**: the page is `www.pixiv.net`
  (base `pixiv.net`) but images load from `i.pximg.net` (base `pximg.net`), so with
  `XOriginPolicy=2` every `i.pximg.net` request 403'd while `s.pximg.net` static
  JS/CSS (no hotlink check) still loaded — the page **rendered but showed no
  artwork**, which reads as a Gecko/app bug, not a privacy pref. The fix is
  `XOriginPolicy=0` (send the Referer cross-site) **with `trimmingPolicy=2`
  retained** (trim to **origin only** — `https://www.pixiv.net/`, no path/query),
  which is exactly stock desktop Firefox's `strict-origin-when-cross-origin` and
  is the smallest change that satisfies the hotlink CDN. The privacy delta of `0`
  vs `2` is only that the bare **origin** is sent cross-site (never the path) —
  the same thing mainstream Firefox sends by default. **Never raise this back to
  `2`** to "harden" cross-site Referer: the origin-only trim (`trimmingPolicy=2`)
  is where the real privacy is; stripping the origin too just breaks hotlink CDNs.
  Diagnosed by diffing two HARs of the same page — desktop Firefox sent
  `Referer: https://www.pixiv.net/` → 200, Firedown sent no Referer → 403; the
  Firedown HAR carried a Referer on only 3 of 75 cross-base-domain requests vs
  Firefox's 95 of 102, the systematic tell of a strip-not-trim policy.
  - **The long-press "save image" download needs the SAME Referer, set
    separately.** The XOriginPolicy fix only covers what *GeckoView* fetches
    while rendering the page. Saving an image via the browser context menu
    (`BrowserFragment` `contextmenu_save_image`) builds a **bare native
    `DownloadRequest`** that goes straight to `HttpDownloadStrategy` with the
    URL + cookies but **no Referer** — so pixiv's `i.pximg.net` 403s it (the
    same hotlink check, now hit by OkHttp instead of Gecko). Fix: the save-image
    branch sets `.headers(BrowserHeaders.refererOriginHeaders(pageUri))`, where
    `pageUri` is the context element's `baseUri` (the document URL) — trimmed to
    **origin + "/"** (`https://www.pixiv.net/`, matching the browser's
    cross-origin Referer). It threads through `DownloadRequest.headers` →
    `DownloadContext` → every `HttpDownloadStrategy` request; `OriginInterceptor`
    does NOT promote it to an `Origin` (no `Sec-Fetch-Site: same-origin`), so it
    stays a Referer-only image GET like the browser's. Any other native
    re-fetch of a page sub-resource is exposed to the same hotlink 403 —
    reproduce the page's Referer, don't strip it.
  - **Third surface: Captured-sheet thumbnails via Glide.** The Captured
    sheet's remote image fetches (`GlideHelper.load(BrowserDownloadEntity…)`)
    used to load http images via a bare `Uri.parse` model — no headers — so
    every pixiv capture thumbnail 403'd into the broken-image fallback. Remote
    plain-IMAGE fetches (a parser thumbnail URL, or an image capture's own
    URL) now go through `buildGlideUrl(entity, source)`, which ships the
    capture's cached request headers and **backfills a missing `Referer` from
    the capture's page origin** (`BrowserHeaders.originWithSlash`). Video/audio
    without a thumbnail keep the `Uri` model on purpose — that's the
    `FFmpegUriDecoder` path (frame / embedded-art extraction), which reads its
    headers from `GlideRequestOptions.HEADERS` instead.
- **Weigh each pref against a mobile media browser's real use, not a desktop
  privacy checklist.** The "Cluster C fingerprinting belt-and-braces" hard-
  disables are **redundant with FPP/RFP when those are active**, so their only
  marginal gain is "protection persists if the user turns RFP off" — which does
  not justify removing user-visible functionality. Reviewed and split:
  - **`device.sensors.enabled` and `media.webspeech.synth.enabled` are kept
    ENABLED** (set `true` explicitly in `applyHardeningPrefs`, not omitted, so the
    IronFox base build's default can't turn them off). Hard-disabling them killed
    real features on a *mobile* browser: DeviceOrientation/Motion powers
    360°/panorama/tilt/AR content (more common on mobile than desktop), and
    SpeechSynthesis powers read-aloud / "listen to this article" /
    language-learning TTS — disabling it is an **accessibility regression**. Both
    are low-entropy vectors FPP/RFP already cover when active, so the privacy
    trade was net-negative.
  - **`dom.battery`, `dom.gamepad`, `dom.vr` stay DISABLED** — deprecated/niche
    APIs with near-zero real-site value here, so the hard-disable costs nothing.
  - **The Cluster B LNA blocking (`network.lna.*`) stays ON — do NOT disable it.**
    It is not merely a niche home-lab protection: mobile sites (notably
    Instagram/Facebook) have been caught opening **localhost/LNA connections from
    the page to the site's own natively-installed app** to de-anonymize and track
    the user across the app↔web boundary (the Meta/Yandex localhost-tracking
    technique). LNA blocking is a direct defense against that, which outweighs the
    rare legitimate local-network web app (Home Assistant / Jellyfin / router
    setup). It **interacts with the P2P loopback server** (see the
    `setLnaBlocking(true)` note in the P2P section — the `127.0.0.1` host
    permission is the intended exemption; verify on-device), but that is an
    exemption to arrange, not a reason to drop the protection.
  - `network.captive-portal-service.enabled=false` (Cluster A) can suppress the
    hotel/airport WiFi login page, mostly mitigated by Android's own OS-level
    captive-portal detection — left as-is, noted as the remaining watch item.
  When a "feature X silently does nothing" report comes in, check
  `applyHardeningPrefs` before assuming a code bug.

## UI conventions (Material 3)

- **Menu rows are M3 one-line list items: 56dp tall, 16sp text
  (`TitleMedium`), 16dp horizontal gutter, `onSurfaceVariant`.** Applies to
  every menu/sheet surface — Browser/Home popups (hand-built `LinearLayout`
  rows), the Security sheet + its blocked-ads/trackers detail dialogs and
  variant rows, the `OptionsAdapter` sheets (New tab / Web options / Downloads
  option, via the `Firedown.Widget.DialogOption` style → `minHeight=56dp` so a
  rare wrapped label can grow), and the search-engine list. The 16dp gutter is
  shared too — identity headers and sheet content insets all sit at 16dp (not
  the old 20/24dp). Two-line rows (e.g. Download info) stay at 72dp. Keep these
  in lockstep; don't reintroduce a denser 48dp, a 15sp override, or a 20/24dp
  gutter for one sheet.
- **The generated mime fallback thumbnail (`MimeTypeThumbnail`) has ONE
  ground for every list row and grid tile — ONE LITERAL COLOR,
  `COLOR_FALLBACK_GROUND = #4A2120`, in BOTH themes.**
  `generateDrawable(ctx, mime, true)` fills the slot with it, opaque, so
  nothing behind the tile (card colour, ripple, a previous frame) bleeds
  through as a veil.
  **It is deliberately NOT derived from the theme background any more, and
  must never be again.** The old form composited the ~12% brand wash over
  `colorBackground`, which resolved to **two** colors — `#FAE9EA` light,
  `#2D1E1F` dark. That is the root of a defect that presented as a text
  problem: **white caption text sits at 1.17:1 on the light pastel** (the
  floor is 4.5:1), so the grid tile had to fall back to theme ink — and
  with it lost the scrim, the text shadow and the white ⋮. Four
  differences, all downstream of one ground being two colors; uniform ink
  over a ground swinging 0.83 in luminance is unreachable by construction.
  Fixing the ground deleted all four (see `applyGridTileGround`). The
  fallback tile is not a card — it is a photo slot with no photo, and an
  empty photo slot is dark: white clears **13.7:1** on `#4A2120` and the
  coral glyph **4.8:1**, in both themes.
  Chosen over the dark theme's own old `#2D1E1F`, which would have made
  dark theme a literal no-op but preserved a latent bug — that value is
  **1.16:1 against the dark page background**, so the tile had no edge and
  dissolved into the page. `#4A2120` separates from both page grounds
  (1.35:1 dark, 13.1:1 light). Deeper (`#552724`) buys a firmer edge,
  lighter (`#3A2321`) more restraint; all clear the floors, so the tone
  inside that range is taste.
  History, and how to read it: an opaque dark duotone GRID ground and then
  a theme × surface split (dark duotone only on light-theme grids) were
  both removed at the maintainer's request — but **what was rejected there
  was the ROUTING** (a `gridTile`/theme flag), not darkness. A single
  un-routed colour satisfies "one ground everywhere" more literally than
  the theme-composited version ever did. Still don't reintroduce a routing
  flag. **The grid's top ⋮ scrim is
  gone too** (same request): the full-width 32dp `top_scrim` gradient both
  grids painted behind the corner more-button (Downloads FINISHED tiles +
  Captured variant tiles) was deleted outright — drawable, layout views,
  the adapters' visibility wiring and the `hasRealThumbnail` gate. The ⋮
  may wash out on rare bright artwork; that's accepted (the tile tap +
  long-press remain the primary doors). If legibility ever needs fixing,
  use a per-icon treatment (small circle/shadow behind the glyph), never a
  full-width dim band. **The bottom title scrim (`bottom_scrim`) stays, but
  ONLY over a photo.** Its single job is guaranteeing contrast over
  unknown, arbitrary-brightness artwork; on the generated ground — which we
  chose, and which carries white at 13.7:1 — it buys nothing and costs
  something, because a gradient over a FLAT colour is visible *as* a
  gradient (a vignette smudged across the bottom of an otherwise clean
  tile). A photo is busy enough to hide it; a solid field is not. So
  `applyGridTileGround` is now dim-only: scrim + text shadow for a real
  thumbnail **and nothing else** — `dim = status == FINISHED && realThumbnail`.
  The non-FINISHED states used to be included because their white title sat on
  the pale card background (**1.23:1** in light theme, so the scrim was the only
  thing holding it up); they all paint the generated ground now, so the title is
  13.73:1 and the gradient buys nothing. **PROGRESS paints the ground WITHOUT
  the glyph** (`MimeTypeThumbnail.groundColor()` as a `ColorDrawable`) — the
  ring is the focal element and a glyph behind it would compete; ERROR/QUEUED
  keep the full fallback via `loadFallback`. One consequence to keep: the grid
  ring resolves `android.R.attr.colorPrimary`, **not** `progress_indicator` —
  that resource exists for a bar on a LIGHT track, and on this dark ground the
  deeper tone is dark-on-dark (2.20:1 against its own track) while the brand
  reads at 3.17:1. Neither for the fallback — and it sets **no text colors at all**,
  because the layout's white title / `#E0FFFFFF` duration / MimePrimary
  `#F4F4F7` label now hold on every tile, so there is nothing to restore on
  recycle. If a fallback caption is ever unreadable, **the ground is wrong,
  not the ink**. The **Cloud Backup grid tile carries the same dim-only rule**
  (`CloudBackupFileAdapter.FileGridVH.applyGridDim`, keyed on whether the entry
  has a stored/backfilled preview) — its scrim used to be baked into the layout
  and never toggled, so a preview-less tile got a gradient over the flat
  ground. Any new grid surface that renders the mime fallback needs the same
  toggle. The grid cloud badge likewise dropped its `realThumbnail`
  split (always the white shadowed `cloud_badge`): the old
  `colorOnSurfaceVariant` branch existed for the pale pastel and would now
  paint a DARK glyph on the dark tile — the exact disappearance it was
  added to prevent. The **media viewer keeps the
  default 16:10 letterbox** (`generateDrawable(ctx, mime)`,
  `fillBounds=false`, still translucent — it sits on the player's own
  background) to match `PlayerView`'s `resize_mode="fit"` — don't make the
  fill unconditional, it would paint the player background edge-to-edge.
- **List-row meta line is `MIME · domain` — plain text, no domain icon.** Both
  list rows that show captured/downloaded media (`fragment_download_item.xml`
  and `fragment_browser_options_item_list.xml`, `row_meta` →
  `mime_text` + `file_url`) read `VÍDEO · youtube.com`. The mime label
  (`MimePrimaryLabel`, adapter appends a trailing `" · "`) doubles as the
  separator, so `file_url` follows directly with no leading margin. There is
  deliberately **no globe/favicon `ImageView`** between them — it was removed as
  decoration (identical on every row, redundant with both the domain text and
  the `·`). Don't reintroduce a domain icon here; if you ever do want a
  per-site favicon, that's a different, data-bound feature, not the old static
  globe. The third line (`size · date · duration/resolution/language`) is the
  informative density and stays. The two layouts and the grid tile are kept in
  lockstep — change the meta line in both list rows together.
- **Durations are TRIMMED for display, never re-formatted in storage.**
  `fileDurationFormatted` is stored padded to `HH:MM:SS`, so a 39-second clip
  spent two fields on zeros. `DownloadItemAdapter.compactDuration` drops
  zero-hours and the leading field's zero pad at render time — `00:00:39` →
  `0:39`, `01:10:27` → `1:10:27` (hours kept whenever nonzero, so nothing
  turns ambiguous); anything not in the three-field shape is returned verbatim
  rather than guessed at. It hangs off `secondaryMetaLabel`, the ONE place
  both surfaces read a duration, so the grid caption and the list's third line
  stay in step by construction. The info dialog deliberately keeps the full
  padded form — a detail view, not a scan surface. Don't push the trim into
  the entity or the parser emit: the stored value is also what
  `DownloadDiffCallback` compares and what the post-download metadata refresh
  rewrites. **It lives in `DateUtils`, shared, and must stay there** — the two
  surfaces read a duration from DIFFERENT sources (Downloads from the entity
  field via `secondaryMetaLabel`, Captured from a persisted `FFmpegTagEntity`
  via `BrowserOptionAdapter.bindSingleTag`), so a private copy in one adapter
  is exactly how they drifted apart the first time this shipped.
- **A selected FILTER CHIP and a selected SEGMENT of a segmented toggle need
  DIFFERENT tones — same visual role, opposite constraint.** The chip
  (`@color/chip_checked_*`, the brand coral) sits on a list rail with no
  primary action to be subordinate to, so it can afford the full brand. The
  buy-credit duration / pay-rail segments (`buy_segment_bg`/`buy_segment_text`
  → `?attr/colorSecondaryContainer`, the PEACH arm of the triad) sit directly
  above the Continue CTA, so they cannot. **Do not merge them under one
  "selected control" resource** — that was tried for exactly one commit and is
  how the buy screen ended up as three identical coral blocks: at **ΔE 8.2**
  (light) / **5.9** (dark) from the CTA, the checked segment and the button
  read as one colour, so nothing said which one committed the purchase.
  Separation is now **ΔE 39.3 / 28.1**. The dark figure is the deliberate cost
  of matching light theme (see the INVERT note below); it is still a hue AND
  lightness difference, and far from the ΔE 8.2 that caused the collapse.
  - The segments are **TONAL, never a brand fill**: selected reads from
    **fill-versus-outline** against the unchecked neighbour, which never needed
    saturation to carry it. Material's own segmented button is tonal for this
    reason, and `secondaryContainer` is exactly the token for it.
  - **The segments do NOT invert between themes — both are a light warm fill
    under the dark `#460005` ink.** They used to (dark peach fill / light label
    in dark), and that was wrong for a reason worth keeping: the same control
    read as two unrelated colours depending on theme, and no *dark* container
    ever looked like the light theme's peach — at that lightness a warm hue is
    muddy however saturated (two attempts, brown then rust). Matching the SHAPE
    across themes also aligns them with every other filled control in the app,
    which all share `#460005`. It is still TONAL, not a brand fill: selection
    reads from fill-versus-outline, and the fill stays under the CTA (5.49:1 vs
    6.92:1) so a segmented button never out-shouts the button it feeds.
  - **History — the token failed here once, and it was fixed at the source.**
    Dark theme had no real container tone (`secondaryContainer` was `#FFA8A0`
    at **L\* 77**, a *light* fill under a *dark* on-colour, the light-theme
    relationship copied into night), so the checked segment landed at
    **10.04:1** against the `#131315` page while the CTA was **6.44:1** — the
    mode selector out-shouting the button it feeds. A LIGHTNESS fault, not a
    hue one: the pre-rotation `#FAB186` measured 10.34:1, within 0.3. The
    workaround was a dedicated per-component colour pair; the real fix was
    giving dark theme genuine dark containers (see the triad section), after
    which the component pair was deleted and the segments went back to the
    token. Prefer that order in future: if a token is the right ROLE but the
    wrong VALUE, fix the value.
  - **The "−N%" savings badge sets no colour of its own**
    (`BuyCreditFragment.durationLabelWithBadge`) and inherits
    `buy_segment_text`, so it follows the check state for free. It used to
    carry a `ForegroundColorSpan` pinned to `colorPrimary`, resolved ONCE at
    build time — which made it invisible the moment its own segment was
    checked (**1.07:1** light, **1.22:1** dark; only 1.75/1.56 even on the old
    container fill). A span needs a concrete int, so a state-aware colour
    would mean re-setting every label from a check listener; bold + 0.82×
    already reads as a badge, and inheriting cannot desync.
- **The checked filter chip is the BRAND, via one theme overlay.**
  `Widget.Material3.Chip.Filter` paints its selected state from
  `colorSecondaryContainer`, which is the triad's PEACH arm (`#FFBF9B` /
  `#D8804A`) — the supporting hue, not the acting one, so the app's one
  permanently-visible "active" control wasn't wearing the brand. A checked chip
  is a state of the list, and per the triad's roles a state that reads as
  "active" belongs to coral. The chip is REMAPPED rather than the token
  retoned, because the token is correctly peach for every other consumer.
  `ThemeOverlay.App.Chip` maps `colorSecondaryContainer` →
  `colorPrimaryContainer` and `colorOnSecondaryContainer` →
  `colorOnPrimaryContainer`, and BOTH rails' styles use it —
  `Firedown.Widget.App.Chip.Filter` (Captured) and
  `Theme.FireDown.Download.Chip` (Downloads, whose `chip_download` selector
  also resolves `?attr/colorSecondaryContainer`). Remapped rather than
  reimplemented so the library keeps owning the enabled/checked/disabled
  selector logic, and the label follows the background automatically.
  **Never point it at `colorPrimary`:** `#ff716c` under a white label is
  **2.88:1**, below the 4.5:1 floor.
  The values are `@color/chip_checked_container` / `_on_container` rather than
  the `?attr` container tones, because the themes want different things. They
  are named for the CHIP, not for the role — the buy-credit segments do NOT
  share them (see the rule above).
  **Light** is `#EC7E78` (label 6.20:1) — the container tone one step
  down in lightness; two steps (`#DE7973`) crosses into a dustier red that
  stops reading as the brand. **Dark is `#DE615E`** (label 4.73:1, presence
  5.28:1): this palette's dark `primaryContainer` (`#F66A66`) is within a hair
  of `primary`, so the checked chip sat at **6.34:1 against the page** — the
  brightest thing on screen, competing with the artwork in the images mosaic.
  Same hue, lower lightness, the brand's own chroma (C\* 55.2).
  **It stays a LIGHT FILL WITH A DARK LABEL — that is the load-bearing part.**
  Every filled control in this app is light-fill/dark-label (the primary button,
  the light chip, all four sharing the same `#460005`). A dark fill forces a
  light label, which makes the chip the only inverted control on screen and
  reads as a different component family sitting right above the button — that
  was tried (`#B23A3C` + white, a much calmer 3.15:1) and rejected on sight.
  Consistency is bought with some of the calm. **If it ever needs to be
  quieter, lower the lightness only as far as the `#460005` label still clears
  4.5:1 (about L\* 57) — never by inverting it.** Two dead ends worth not
  repeating: chroma below ~C\* 50 at this lightness reads BROWN (brown is just
  low-chroma orange), and the gamut maximum (`#C8032B`) reads crimson, not
  Firedown coral. Both `values/` and `values-night/` define the overlay and the
  colours, so edit them together.
- **The palette is the LOGO TRIAD — three hues, each with a job. Don't flatten
  it.** Firedown's identity is not a choice made in `colors.xml`; it is the six
  colours in `ic_launcher_foreground.xml`: `#FF716C`/`#FF525B` **coral**,
  `#FFB58A`/`#FFA386` **peach**, `#E83A87`/`#B4225E` **magenta**. One warm arm
  (**+27°** to peach) and one cool arm (**−31°** to magenta) either side of the
  brand coral. The home shelf chips (`home_chip_downloads`/`_vault`/`_trackers`)
  have always mirrored it. The roles are what make three hues a *system* rather
  than more colours:
  - **primary = coral — ACTS.** The thing you press: FAB, filled buttons,
    Continue, progress, the checked filter chip. Never a passive container.
  - **secondary CONTAINER = peach — SUPPORTS.** Tonal ground behind content:
    the recovery-code box, the buy-credit segments, banners. Never fills a
    button. (The home backup pill used to take it and no longer does — see
    the cloud-states rule: those two surfaces are plain
    `surfaceContainerHigh`.) **`colorSecondary` itself stays CORAL** —
    `Theme.FireDown` sets no `colorControlActivated` and no
    `android:colorAccent`, so Material3 resolves the tint of every bare
    platform widget through it (the four unstyled `<ProgressBar>`s on the
    buy-credit and cloud-stream screens; any checkbox/switch/seekbar/cursor
    added later). Giving it the peach turned those spinners **dark brown** on
    the Add-storage-credit screen. The peach is a *container* role; a token
    that tints controls is an *accent*, and the accent is the acting hue.
  - **tertiary = magenta — IDENTIFIES.** "A different kind of thing": vault /
    private, cloud + sync state. Never competes with the CTA.

  **`md_theme_secondaryContainer` was once rotated onto the brand hue (h 56 →
  h 29) and that was a mistake** — justified at the time as removing "a hue
  that appears nowhere else", which was simply false: it appears in the app
  icon. It collapsed the triad's warm arm into the primary, and every hierarchy
  problem that followed traces to having only *lightness* left as an axis: a
  checked segment ΔE 8.2 from the Continue button, a checked chip out-shouting
  the artwork, a buy screen reading as three identical coral blocks. Each got
  hand-tuned; the triad gives the ranking away for free. Reverted to
  `#FFBF9B`/`#5D2E0D`.
  **Auditing lesson:** a palette can be internally consistent and still be
  wrong — check it against the *brand*, not just against itself.
- **Dark theme's container tones were inverted once, audited
  consumer-by-consumer — and `secondaryContainer` has since been inverted BACK.** `secondaryContainer`/`tertiaryContainer` in
  `values-night` were made genuinely dark (tone ~30) under light on-colours,
  which is what M3 expects; the palette previously copied the light-theme
  relationship into night (`#FAB186` at L\* 78 — a *light* fill under a *dark*
  on-colour). **`secondaryContainer` has since gone back to a light fill on
  purpose — see the exception below; `tertiaryContainer` is still a dark tone.**
  The original defect was never the inversion as such, it was the LIGHTNESS: a
  container-toned control measured **10.04:1** on the
  `#131315` page while the CTA beside it was **6.44:1**, and the home backup
  pill's grace state — which then painted a **hardcoded** amber `#e8a13d` as its
  ink — sat at **1.18:1** on the light fill versus **4.61:1** on the dark one.
  (That pill has since moved off this token entirely; the measurement is kept
  because it is what the inversion was justified against.)
  **The inversion is only safe because every live consumer is a paired
  fill+ink**: `bg_sync_code` (two layouts) and `chip_download` (remapped away
  by `ThemeOverlay.App.Chip`). Every
  `bg_icon_container_*` drawable and `rounded_secondary` are DEAD — no layout
  or Java references them — which is also why `tertiaryContainer` was free to
  become a proper pale/dark tone. **Re-run that audit before touching these
  again**: what an inversion breaks is a consumer pairing one of these tokens
  with a hardcoded ink.
  - **`secondaryContainer` is the EXCEPTION — dark theme's is a LIGHT fill
    (`#D8804A`) under the `#460005` ink, and that took three tries.** The dark
    tone stayed only for `tertiaryContainer`. History, because each step looks
    like the obvious fix for the last:
    1. `#5C3A22` (L\* 27.9 / **C\* 24.3**) read **brown** — brown is nothing but
       dark, low-chroma orange (same diagnosis as the filter chip's "C\* below
       ~50 reads BROWN"). Produced by darkening light's `#FFBF9B` without
       holding chroma; the gamut was never the constraint, that lightness
       allows C\* 48 and it used half.
    2. `#793A0D` (L\* 32 / **C\* 45**) fixed the chroma and still read **rust**.
       The lesson: chroma alone doesn't rescue a dark warm fill — at that
       lightness a warm hue is muddy however saturated, and it looked nothing
       like light theme, so one control read as two unrelated colours.
    3. `#D8804A` (L\* 62 / C\* 52 / h 56°) — light fill, dark ink, same SHAPE as
       light theme and as every other filled control in the app.
    **Bounded on both sides, and it sits ON the upper bound**: at **6.28:1**
    against the page it is the lightest value that still leaves the CTA (6.92:1)
    on top — a margin of 0.64, so **do not lighten it**. Light theme's own
    `#FFBF9B` here is **11.64:1**, 1.7× the button, the exact `#FFA8A0` defect
    again. The floor is the ink: `#460005` on the fill is **5.63:1**, and below
    L\* 58 it breaks 4.5:1. The whole usable band is L\* 58–62.
    It also fixes the label-prominence inversion for free: on a near-black page
    grey text gets ~10.9:1 for nothing, so a DARK fill could never make its label
    out-rank the unchecked neighbour (white on `#793A0D` caps at 8.66:1).
- **`colorPrimaryContainer` must NOT be retoned. This was attempted and
  REVERTED after it visibly broke the app.** The hero download FAB
  (`fragment_browser.xml`, `Widget.Material3.FloatingActionButton.Primary`)
  takes `colorPrimaryContainer` as its background and `colorOnPrimaryContainer`
  as its icon tint **from the Material default style** — the token name appears
  nowhere in this repo for it. Retoning turned the app's most prominent control
  into a pale pink disc in light theme and a dark maroon one in dark, and the
  page-load progress bar with it. **The lesson is about auditing, not colour:
  grepping for a token name CANNOT find the components that consume it through
  a library default style.** Any future palette change must be verified by
  running the app, not by enumerating references — three successive greps here
  (`?attr`, then `R.color.*`, then unfiltered) each found consumers the last one
  missed, and none of them could have found the FAB.
- **`colorPrimaryContainer` is also OVERLOADED.** It is read two incompatible ways: (1) a FILL, layered at 20% by
  `SelectionStyling` for selected rows on five screens plus the Bookmarks sync
  banner, the Downloads incognito banner and the active-tab chrome; and (2) an
  INK over dark grounds — the Downloads grid tile's `status_text`
  (ERROR/QUEUED/"Finishing…") on its scrim, and the Captured tile's selection
  check over artwork. In dark theme these want OPPOSITE tones: the textbook M3
  container (`#93000F`) makes the error message unreadable and the check vanish
  into the scrim, while the 20% wash collapses from 1.32:1 to 1.05:1. No hex
  satisfies both. The prerequisite for ever moving it is splitting use (2) onto
  its own on-dark ink resource. Two consumers *would* improve (the progress
  track and `bg_icon_container_primary`) — that is what makes it tempting.
- **A progress indicator can NEVER be `colorPrimary` in light theme — the
  maths forbids it.** A determinate bar has to separate from its own track by
  3:1 (WCAG 1.4.11). Against `#ff716c` that is unreachable with ANY lighter
  track: pure white tops out at **2.88:1**. So the fix is not a paler track, it
  is a darker indicator — `@color/progress_indicator`, `#CC524A` in light and
  the brand `#ff716c` in dark (which already clears it). Used by the Downloads
  in-flight row, the Cloud transfer row, the Cloud credit meter and the grid
  `ProgressOverlayView` ring; the track stays `colorPrimary@20%` (or
  `colorSurfaceVariant` on the meter). Don't "fix" a low-contrast bar by
  lightening its track — that direction is capped below the bar.
  **The light value is a measured CEILING, and the light/dark gap it leaves is
  not a bug to keep chasing.** It was `#C24941` and was reported on-device as
  reading far darker/heavier than dark theme's bar. That gap is real and only
  partly closable: against its own page light's bar is **4.11:1** where dark's
  brand bar is **6.92:1**, and no value closes it, because a brand-coral
  indicator in light theme is impossible at any track lightness (the 2.88:1
  white ceiling above). `#CC524A` is one step back toward the brand and is as
  far as it goes — the binding constraint is that this ONE resource meets TWO
  track shapes: `colorPrimary@20%` (#FCDEDE) at 3.42:1 and the meter's
  `colorSurfaceVariant` (#E1E2E9) at 3.34:1. `#D1564E` (3.23/3.16) is the
  absolute limit and was rejected as too thin a margin. **Re-measure against
  BOTH tracks before touching it** — the meter's is the tighter one and the
  easy one to forget. Darkening the meter's track to buy headroom does NOT
  work either: the track is *lighter* than the bar, so darkening it moves the
  two together and costs contrast (a mistake worth not repeating).
  `ProgressOverlayView` also stopped hardcoding `0xFFff716c` for its arc/track/
  label and resolves the resource, so it follows the theme like everything else.
  Note `progress_bar_horizontal.xml` is a DEAD drawable (no consumers) and its
  layer roles are inverted from what the name suggests — transparent background,
  `colorPrimaryContainer` progress. Don't reason about progress colours from it.
- **The 20% selection wash is FINE — measure it with ΔE, not contrast ratio.**
  It reads 1.17:1 against the light page, which looks alarming and was briefly
  taken for a defect. Contrast ratio is the wrong instrument: the wash differs
  from the page in HUE, not luminance, and near white the ratio is compressed
  by the +0.05 term. Perceptually it is **ΔE 10.8** (light) / **18.7** (dark),
  where >5 already reads as "obviously a different colour". Don't raise
  `WASH_ALPHA` on the strength of the contrast number.
- **Grid caption type is on the M3 scale — keep it there, and don't shrink
  it.** Title 12sp bold (`labelMedium`; bold over medium is deliberate for
  text sitting on a photo), mime label 11sp (`labelSmall`, `MimePrimary`),
  duration/size 11sp. That last one was **11.5sp** — a value M3's scale
  doesn't contain, sitting half a point above the 11sp label immediately to
  its left; snapped so the whole meta row is one size. 12sp is already the
  floor of the scale and has to survive a user's font-scale setting, so if a
  tile reads as crowded the fix is **fewer facts** (as dropping size under an
  active chip did), never smaller type. **The peer grid tiles are kept in
  lockstep** — Captured (`fragment_browser_options_item`), Cloud Backup file
  and transfer tiles all snapped off the off-scale 11.5sp with it. (The
  buy-credit screens still use 11.5sp; they aren't grid meta rows, so they
  were deliberately left alone rather than swept up.)
- **Grid tile title: hidden for self-identifying image tiles in BOTH Downloads
  and Captured.** The rule is *not* "images are clutter" — it's
  "drop the title only for the one type whose thumbnail fully identifies it."
  Both `DownloadItemAdapter` and `BrowserOptionAdapter` (Captured) hide
  `file_name` in the grid **iff** `isGrid && FileUriHelper.isImage(mimeType)`
  (covers GIF/SVG) — image thumbnails *are* the content and their names are
  almost always junk slugs, so the title is ink over the picture. Everything
  else keeps it: **audio is the load-bearing case** (no real thumbnail — hiding
  the title leaves an unidentifiable mime tile), and video/subtitle/doc
  thumbnails are too weak (black frames, generic glyphs) to discriminate without
  the name. The list always shows the title (both adapters). Keep this keyed on
  the **mime** (`isImage`), not on a filename-content heuristic: the old
  "name has no spaces ⇒ junk" test was removed because it misclassifies
  space-less scripts (CJK/Thai) and silently drops real titles. The mime chip
  labels the type in the UNFILTERED grid (where it varies row to row), incl.
  when the title is hidden — but it is **suppressed while a single-type filter
  chip is active** in BOTH surfaces (`setMimeSuppressed` in both adapters,
  wired to the chip rails): stamping "VIDEO" on every tile under an active
  Video filter merely repeats the checked chip. **Presentation flips
  (suppression/density/span) must land WITH the new list, never on the chip
  tap** — the requery is async, and an eager flip re-renders the OLD list in
  the NEW presentation first (on-device: the images mosaic collapsed to
  normal span-2 image tiles for a beat before the videos arrived). Captured
  sets the flags silently (`setPresentation`) and applies span+rebind in
  `submitList`'s commit callback (`submitWithPresentation`); Downloads defers
  to the paging load-state listener (`applyPendingPresentation` on refresh
  NotLoading). The rebind-on-change is still required either way: the differ
  won't rebind items that survived a filter flip unchanged. (Captured used to *always* show the title as a pre-download
  decision surface; that was changed to match Downloads — an image tile's slug
  name is noise on the decision surface too.)
- **Images/GIF filter + grid = the dense bare mosaic.** When the images or GIF
  chip is the active filter in grid mode, both surfaces switch to a denser
  span (`image_grid_dense_number`/`browser_grid_dense_number`, 1.5x) of square
  tiles with NO text/chrome at all (dedicated dense layouts + view types —
  `*_GRID_DENSE`/`TYPE_GRID_DENSE`, distinct types because the
  RecycledViewPool keys holders by view type). Images are the one type whose
  tile is self-identifying (title already hidden, chip already suppressed), so
  the density costs no information; do NOT extend the bare treatment to other
  types — their titles/facts are load-bearing (see the title rule above). The
  Downloads dense tile keeps the progress overlay, an ERROR scrim
  (`bindErrorInner` re-shows the hidden block), and the selection checkmark.
  Downloads reconfigures only on an actual density transition
  (`refreshGridDensityIfChanged`); the vault has no chip rail and keeps normal
  density. The Downloads grid meta row reads `[chip] duration · size`
  unfiltered and **`duration` alone under an active chip** (`joinWithSize`):
  size drops with the mime, the same redundancy rule one fact further down —
  it isn't what a grid is scanned for, and the list row + item sheet both keep
  it. It ALSO drops under `SORT_SIZE`, where the section header states the
  size bucket (the `setGroupingSort` rule the list row obeys) — two
  independent reasons, hence an OR rather than one flag. Date always stays
  out of the tile: the section header carries it, in grid as in list.
- **The grid gets the SAME date section headers as the list. Don't "fix" the
  half-empty rows.** A header is a full-span item, so a section holding one
  file renders one tile and an equal void beside it. That void is **not
  waste** — it is how a sectioned grid says *this group is small*, and every
  sectioned grid on the platform (Photos, Files, Finder, Explorer) renders it
  exactly this way. Framing it as "~40% of the pixels above the fold are
  empty" is the misleading version of the same observation: it turns
  information into a defect and then goes looking for a layout invention.
  Three were drawn and rejected on sight, and the reasons generalise —
  re-derive them before proposing a fourth:
  - **A lone tile spanning the row at its natural 16:10** is 4× the area of
    its neighbours and reads as a news-carousel hero, not a file.
  - **A lone tile spanning at fixed row height** keeps the rhythm but crops a
    16:9 frame to ~32:10, slicing the subject.
  - **A header occupying a grid cell** (so the lone tile sits beside its
    label) eliminates the void on odd-sized sections — and no shipping file
    manager does it, which for a file list is evidence, not timidity.
  - **The fatal objection to all three:** they key on "recent date sections
    hold one or two files", which is only true under `SORT_DATE`. Under
    `SORT_DOMAIN` or `SORT_SIZE` the identical rule turns a one-file domain or
    size bucket into a hero banner. A layout rule tuned to one sort mode's
    data shape is wrong by construction.
  (History: headers were briefly dropped in grid — first unconditionally, then
  only while a filter chip was active — and both were reverted. The
  machinery that went with it, a `mGridMode` stream plus a post-`cachedIn`
  `withSectionHeaders` transform, is gone; `applySeparators` is back inside
  the pre-`cachedIn` map where it started.)
- **A ViewHolder wrapped in a `ConcatAdapter` MUST report
  `getBindingAdapterPosition()`, NEVER `getAbsoluteAdapterPosition()`, in its
  click/long-click handlers.** When a list adapter is one child of a
  `ConcatAdapter` (a banner/header prepended at index 0 — the Bookmarks sync
  banner `SyncBannerAdapter`, the Downloads incognito/restore banners), the
  *absolute* position is the index in the WHOLE concat (offset by the header),
  while the listener feeds that value straight into the row adapter's OWN 0-based
  list (`setSelected(pos)`, `snapshot().get(pos)`). The two diverge by the header
  count, so every row selects/opens its neighbour and the LAST row runs off the
  end (IndexOutOfBounds swallowed → the gesture looks completely dead). This
  shipped as a dead long-click in `WebBookmarkFragment` the moment the sync
  banner was added — fixed by switching `WebBookmarkAdapter` to
  `getBindingAdapterPosition()` (the binding position is relative to the row
  adapter, immune to the header offset) + a `NO_POSITION` guard. `DownloadItemAdapter`
  already does this (it's banner-wrapped). `WebHistoryAdapter` and
  `BrowserOptionAdapter` still use `getAbsoluteAdapterPosition()` — that's only
  safe because their fragments set the adapter DIRECTLY (no `ConcatAdapter`); if
  you ever wrap one in a banner, switch it to the binding position FIRST.
- **List-row selection chrome is shared across Downloads / Bookmarks / History /
  Captured / Cloud Backup / the tab ARCHIVE — keep them identical.** The pattern
  (Files-by-Google): the
  `MaterialCardView` is the row ROOT (no outer `LinearLayout`, no external
  checkmark), the selection check overlays the more/action-button's OWN slot
  (the adapter swaps them on the action-mode toggle — button `INVISIBLE`, check
  `VISIBLE` — so the slot width holds and the row never reflows), and the
  selected state paints a tonal WASH on the card via
  `SelectionStyling.selectedCardWashOver(...)` (primaryContainer @ 20%), NOT a
  stroke border. Captured (`fragment_browser_options_item_list` +
  `BrowserOptionAdapter`) originally diverged — an external left checkmark inside
  a wrapping `LinearLayout` that shifted the whole row, plus a 2dp stroke instead
  of a wash — which read as a completely different selection UI; it was brought
  in line. The ONE deliberate difference Captured keeps is its resting card
  background (`colorSurfaceContainerLow`, for contrast on the non-lifting bottom
  sheet, vs Downloads' transparent card), so its wash layers over
  surfaceContainerLow. Grid/dense tiles legitimately keep a corner check + stroke
  (no more-button slot to borrow, and a full-tile wash fights the thumbnail) —
  the shared pattern is for the LIST rows. Don't reintroduce an external
  checkmark or a stroke-only selection on any list row.
  - **The tab ARCHIVE row (`fragment_tab_archive_item` + `TabArchiveAdapter`)
    was the last holdout and is now in line.** It had the exact shape this rule
    forbids: an outer `LinearLayout` with an EXTERNAL check beside the card (so
    the whole card slid right on every action-mode toggle) and a 2dp stroke over
    a `Widget.Material3.CardView.Filled` background instead of the wash. Its
    fragment also still used `CardViewListItemDecoration`, which emits spacing
    only on the first and last items — with a 2dp stroke that made adjacent
    selected rows read as one doubled border, the same defect that moved
    bookmarks/history/downloads onto `EqualSpacingItemDecoration`. All three
    fixed together; its `mUnChecked` also moved from `onSurfaceVariant` to
    `md_theme_primary` to match those rows.
  - **The check/radio is coral (`colorPrimary`) everywhere — but WHERE that
    tint comes from differs per adapter, which is a trap when auditing.**
    `ic_baseline_check_circle_24`'s own `fillColor` is **white**, and it is
    almost never used raw: `DownloadItemAdapter`, `WebBookmarkAdapter`,
    `WebHistoryAdapter`, `BrowserOptionAdapter` and `TabArchiveAdapter` all
    build pre-tinted `mChecked`/`mUnChecked` drawables with
    `Utils.tintDrawable(...)` and hand them to `setImageDrawable`, so the
    layout carries no `app:tint`. **`CloudBackupFileAdapter` is the exception**
    — it calls bare `setImageResource`, so both its layouts must carry
    `app:tint="?attr/colorPrimary"` themselves (the grid tile was missing it
    and rendered a white check while its own list row rendered coral). So an
    untinted `app:srcCompat` in a layout does NOT mean a white check —
    grep the adapter for `tintDrawable`/`setImageDrawable` before concluding
    anything about this glyph's colour. Note also that a layout `app:tint`
    **overrides** a pre-tinted drawable, which matters for the UNCHECKED
    radio: `TabArchiveAdapter` deliberately tints that one
    `onSurfaceVariant`, so adding a blanket `app:tint` to its layout would
    silently stomp it.
  - **The `Theme.FireDown.More.Button` style defaults `iconTint` to
    `@color/white`** — written for GRID tiles, where the ⋮ sits over artwork.
    LIST rows must override it to `?attr/colorOnSurfaceVariant`. Cloud Backup
    does that in XML on every item; Downloads does it at runtime
    (`setActionIcon`, which is already swapping the icon resource for QUEUED,
    with per-surface cached `ColorStateList`s); Captured does XML for list and
    runtime for grid. Three mechanisms, same result — don't "fix" one into
    another without a reason, but don't read the style default as the shipped
    colour either.

### The two media players — `Theme.FireDown.Play` gotchas

`PlayerActivity` (local file) and `CloudBackupStreamActivity` (a backed-up
object streamed + decrypted on read) share `Theme.FireDown.Play` and the same
`exo_media_viewer_controller`. Two traps, both of which shipped:

- **`DISPLAY_SHOW_TITLE` must be set in CODE — the theme suppresses it.**
  `Theme.FireDown.Play` points **`actionBarStyle`** at
  `Theme.FireDown.Play.Toolbar`, which is a **`ThemeOverlay`** (it is correct on
  `actionBarTheme`, which is also set to it — but `actionBarStyle` wants a
  `Widget.*.ActionBar` style). A ThemeOverlay declares none of the ActionBar
  *widget* attributes, so `displayOptions` resolves to **0** rather than the
  `showTitle` default, and title AND subtitle are suppressed. `setTitle(...)`
  then silently does nothing visible. `PlayerActivity` has always compensated
  with `setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE)`; the stream activity
  did not, and rendered a back arrow over an empty toolbar. `setDisplayOptions`
  **replaces** the flag set, so `setDisplayHomeAsUpEnabled(true)` must come
  AFTER it. Any new activity on this theme needs the same call.
- **A PlayerView needs all FOUR timebar colours, not just `played_color`.**
  media3's defaults are white (`played` 0xFFFFFFFF, `buffered` 0xCCFFFFFF,
  `unplayed` 0x33FFFFFF). Both players set only `played_color`, so the bar read
  as a WHITE line: on a fully-buffered file the whole track paints in the
  near-solid *buffered* colour, and early in a long clip the played sliver is
  invisible (on-device: a 1:05:36 stream at 00:10). `buffered_color` /
  `unplayed_color` / `scrubber_color` are now set too, from
  `player_scrubber_buffered` / `_unplayed` (coral at 50% / 15%). Those have **no
  night variant on purpose** — both players paint on an opaque black window in
  either theme, so the alphas composite over black and are theme-independent.
  Keep the two layouts in lockstep; they are documented as matching.

**The stream player says nothing in TEXT about being a stream — the buffering
spinner is the signal.** A "Streaming from cloud backup" ActionBar subtitle was
built and removed (maintainer call): the PlayerView's own `app:show_buffering`
already reports it, honestly and only when there is something to report, whereas
a subtitle restates it on every frame of every playback including the ones that
never stall. If this ever needs strengthening, it should stay in that register —
something subtle tied to actual buffering state, not a persistent label.

## Thumbnails (native `thumbnailer.c`)

`FFmpegThumbnailer.getBitmap(streamPos)` reads one frame; `streamPos` is a
three-way contract: **`>0`** decodes the first **keyframe at/after** that
mid-clip position (`av_seek_frame` flags `0` — MediaMetadataRetriever
`OPTION_NEXT_SYNC` parity; a position past the LAST keyframe falls back to the
keyframe before it, mirroring MMR's null there); **`==0`** decodes the head
frame (some callers need the first frame exactly — GifMaker tiles it across the
filmstrip, SaveFrame fallback); **`<0`** means *no mandate*, so the native side
auto-seeks `THUMBNAIL_DEFAULT_OFFSET_US` (3s) in with the same keyframe-at/after
seek, to skip the usual black opening frame — applied only when the clip is
longer than the offset (a shorter clip decodes the head, frame 0 being fine
there), and falling back to the head on seek/decode failure. **Never
`AVSEEK_FLAG_ANY`** (the pre-fix `>0` behaviour): an ANY seek lands on a
non-key sample, and dav1d (AV1) rejects every mid-GOP packet with
`AVERROR_INVALIDDATA`, so the internal retry-from-start turned EVERY explicit
position into the black t=0 head frame — AV1 being exactly what reaches the
FFmpeg fallback (MMR can't decode it on older devices either), the
Downloads-list fallback thumbnail was always black and "Regenerate thumbnail"
visibly did nothing (H.264 only escaped by discarding packets to the next
keyframe — the frame the keyframe seek now reaches directly). Verified by
mirroring `jni_extract_bitmap` byte-for-byte in a host harness over
H264/AV1(dav1d)/VP9 × mp4/webm/mkv at mid-GOP, past-last-keyframe, past-EOF,
truncated-faststart and auto positions.
The Glide decoders default a missing `GlideRequestOptions.LENGTH` to `-1`
(auto); an explicit `LENGTH` (Media/Image viewers pass the file size) is passed
through. **Don't extend the auto offset to `0`** — that breaks the head-frame
callers. For finished downloads the frame comes from Glide's built-in
MediaMetadataRetriever (the `DownloadEntity→ParcelFileDescriptor` path wins over
`FFmpegUriDecoder`); `GlideHelper` requests a small ~2s offset
(`effectiveThumbnailFrame`) with `VideoDecoder.FRAME_OPTION =
OPTION_NEXT_SYNC` — the first keyframe at/after the offset. The default
`OPTION_CLOSEST_SYNC` can snap back to the black t=0 keyframe on a sparse GOP;
`OPTION_CLOSEST` would decode the exact frame but walks the whole GOP (too heavy
for a scrolling list). NEXT_SYNC is a single-keyframe decode always past the
intro. Keep the offset **small** — NEXT_SYNC only needs to clear the opening,
and a large offset would clamp the many short clips this app captures to the
head frame.

## Settings IA — frequency-first root, doors for expert config

The root Settings screen is FOUR categories — General / Downloads / Privacy /
Firedown — after a simplification pass (was 7 categories, ~33 rows, ~5
screen-heights). The rule, borrowed from the chip-rail convention: the root is
**frequency-first**; once-ever expert config lives behind a door. Applied:

- **Security is a DOOR at the end of Privacy** (`SETTINGS_SECURITY_SCREEN` →
  `SecurityFragment` + `settings_security.xml`): the harden-at-a-cost toggles
  (Block JS / Disable JIT / Enable DRM / Disable WebGL) + the WASM door — the
  switches CLAUDE.md itself says "most users should never touch".
- **Direct share is a DOOR at the end of Downloads** (`SETTINGS_P2P_SCREEN` →
  `DirectShareFragment` + `settings_direct_share.xml`): the STUN chooser + TURN
  editor moved verbatim (they were raw `stun:` URLs on the root). Lives with
  Downloads because P2P share is a Downloads feature.
- **The Cookies category was dissolved into Privacy** (cookie policy row +
  Delete browsing data, destructive action last) — a 2-row category was
  taxonomy noise; cookie policy IS privacy (Fenix's grouping).
- Deliberately NOT full Fenix-style nesting (root = only doors): the project's
  own precedent is anti-tap-tax (the Cloud screen was UN-nested because
  reaching the plan took 4 taps). Nothing moved more than ONE level down.

**Sub-screens must apply their prefs to Gecko THEMSELVES** — SettingsFragment's
SharedPreferenceChangeListener is unregistered while a sub-screen is
foreground (the `WasmFragment` pattern). `SecurityFragment` carries its own
listener with the JS/JIT/DRM/WebGL branches; SettingsFragment keeps its
matching branches as the defensive twin (only one listener is registered at a
time, so no double-apply). **Keep the two in lockstep** — a semantics change
in one without the other makes the toggle behave differently depending on
which screen flipped it. Door keys are click-rows (`SETTINGS_SECURITY_SCREEN`,
`SETTINGS_P2P_SCREEN`); the underlying toggle/pref KEYS are unchanged, so no
migration. Door titles reuse the old category strings
(`if_preferences_security`, `settings_p2p_category`) — already translated,
zero new locale work.

## In-app donations RETIRED — "Support Firedown" is a website handoff

The native Value for Value donate screen (`DonateFragment` + the `donate/`
package: `LightningInvoiceFetcher`, `BitcoinAddressProvider`, Lightning
invoice/BTC QR plumbing, `fragment_donate.xml`, 8 donate-only drawables,
~26 `donate_*` strings) was **removed entirely** (maintainer decision). The
app's ONE money surface is the paid cloud-backup credit flow (the
`claude/intelligent-cannon-c5izfr` monetization work: anonymous
blind-signature credits, Lightning + Stripe rails) — a donate screen beside a
purchase screen is two competing money-asks (donors feel they "already paid";
would-be customers donate instead of buying credit), and the donate plumbing
shared nothing with the credit flow's rails (mint `payRequest` BOLT11 /
Stripe Checkout), so it was pure extra surface. Before this, the fiat "Card
or PayPal" (Buy Me a Coffee) card had already been dropped from the screen.

What remains: a **"Donate" row** (`settings_donate`) in Settings' app category
(key unchanged — `Preferences.SETTINGS_DONATE`, a click-row so no
key-inversion issue) that opens `settings_donate_url`
(https://firedown.app/donate — /support is the HELP page, not donations)
in a Firedown tab via the same OPEN_URI result handshake as the
GitHub-issues row. Title-only, NO summary — it sits in the app category
beside License/Help/About, which are all bare rows, and a lone summary
read as out of place there (on-device review). Title translated across
the 16 maintained locales. **The website must serve /donate** — the donate
rails (LN address, BTC, fiat) live there now (firedown-website repo), not in
the APK. Don't reintroduce an in-app donate/payment screen alongside the
credit flow; if a donation surface ever returns, it's a website page.

## Conventions

- Match the surrounding comment density — the parsers are heavily commented
  with *why*, including dead-ends not to retry. Keep that.
- Don't push to `main`; develop on a feature branch and open a PR only when
  asked.
- **One working branch per session — do NOT cut a new branch for every
  request.** Keep committing follow-up work to the branch already in play; only
  branch when starting genuinely unrelated work or when the user asks. If you
  did split something off, merge it back into the working branch rather than
  leaving a trail of one-commit branches.
- Commit messages: explain the root cause and how it was verified, not just the
  change.
- **Hilt: a class declaring `@Inject` fields must ITSELF be
  `@AndroidEntryPoint`** — the annotation is not inherited downward. Hilt's
  members-injection runs through the annotated class's generated injector;
  an UNANNOTATED subclass of an annotated base gets its own `@Inject` fields
  silently left **null** (no compile-time error — shipped as an NPE in
  `DownloadFragment.onRestoreTreePicked`, whose `mDiskExecutor` /
  `mDownloadDatabase` were never injected because only `BaseDownloadFragment`
  was annotated). The reverse direction is fine: `@Inject` fields on an
  unannotated BASE are injected when the leaf is annotated (that's why
  `BaseTabsFragment`/`BasePreferenceFragment` work). Rule of thumb: annotate
  the concrete fragment/activity you instantiate; with the Hilt Gradle
  plugin, annotating both a subclass and its annotated superclass is the
  supported shape. Symptom to recognize: an NPE on an `@Inject` field used
  only on a rare path, in a class whose base is annotated.
- **Java: NEVER write a fully-qualified class name inline in code — always add
  an `import` and use the simple name.** Not
  `com.solarized.firedown.phone.dialogs.LanShareDialogFragment f = …` or
  `android.net.Uri.encode(…)`; import the class and write
  `LanShareDialogFragment f = …` / `Uri.encode(…)`. Applies to types,
  static calls, constants (`KeyEvent.KEYCODE_BACK`, `Snackbar.LENGTH_LONG`),
  and generics (`ActivityResultLauncher<Uri>`). An inline FQN like
  `new org.bouncycastle.crypto.signers.Ed25519Signer()` is a defect — fix it,
  don't ship it. Javadoc `{@link}` targets may stay fully qualified.
  - **Same-simple-name collision is NOT a license to inline-qualify — RENAME
    your own symbol so the import works.** When a class you need collides with a
    local one (e.g. BC's `Ed25519Signer` vs our former wrapper of the same
    name), the fix is to rename the symbol YOU own — our wrapper became
    `Ed25519Identity` so `org.bouncycastle.crypto.signers.Ed25519Signer` imports
    by its simple name. Inline-qualifying is the **last resort**, reserved ONLY
    for a collision where you control neither name (two third-party types with
    the same simple name, or a platform type vs a third-party type you can't
    rename) — and even then, qualify only the loser, only at its use sites
    (e.g. `android.provider.Settings` vs a local `Settings`). Reach for the
    rename first.
- **C style (all native sources under `app/src/main/cpp/`): write standard,
  explicit, readable C.** This is a general rule for every `.c`/`.h` here, not
  about any one line. In particular:
  - One operation per statement: no assignments inside `if`/`while` conditions,
    no multiple side effects per line.
  - Don't chain an interrupt/error check, an assignment, and control flow
    (`goto`/`return`) together on a single line.
  - Always brace blocks; put the body on its own line(s).
  - Check return values explicitly: assign to a variable, then test it.
  - **Declare a function's working variables in the declaration block at the
    top of the function** (as the existing sources do — see `downloader_mux`),
    not interleaved with statements mid-function. A `for (int i = …)` counter
    or a temporary at the **start of a nested block** is fine; a fresh
    declaration after executable statements in the function body is not. If a
    block needs several locals, that's a sign it should be its own helper
    (e.g. `downloader_log_progress`).

  Parts of the existing code use a terser, condition-with-side-effects form
  (e.g. `if (x->interrupt || (err = f()) < 0) goto error;`). That is **not** the
  style to follow — do not propagate it. Write the explicit equivalent
  (`if (x->interrupt) goto error;` then `err = f(); if (err < 0) goto error;`)
  for anything you add or modify.
