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
| `webrequests/`| `downloader@solarized.dev` | **ALL capture** — the former `parser@` extension was MERGED into this one. Two halves in one extension: (1) the per-site **parsers** (`js/parsers/` — one ES module per site: Twitter/X, Instagram, Threads, Facebook, Vimeo, Rumble, Bilibili.tv, Niconico, Kick, Twitch, Dailymotion, Apple Podcasts, News Over Audio, TikTok, Bluesky; emits entries **with metadata** — title, author, thumbnail, duration, quality variants) plus the page-state bridge (`js/page-state-bridge.js`); (2) the **generic catch-all** (`js/requests.js` + `js/content-script.js` — any media URL seen on the wire, no rich metadata). Also hosts `js/wasm-watch.js` (+ `js/wasm-probe.js`), the WASM-disabled detector — a settings feature, not capture. |
| `youtube/`    | `youtube@solarized.dev`  | YouTube (separate; uses `PoTokenGenerator` on the Java side). |
| `ublock/`     | uBlock Origin            | Ad blocking. |
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
  carries `audio_?url`/`enclosure` keys alongside the video ones; a
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
  `"0x720"`). **Request headers: the `<video>`-element MEDIA-REQUEST set, but NO
  Referer/Origin/Cookie.** These URLs are query-signed/self-authorizing (verified
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
- **Spotify — DECIDED AGAINST a parser** (don't re-litigate from a HAR). Logged
  out, the only thing that plays is a ~30s `p.scdn.co/mp3-preview/<hash>` clip
  (and even that often 304s from cache on an extensionless URL → unclassifiable);
  the full track/episode is auth-gated **DRM** (encrypted CDN). The preview's
  name/thumbnail DO sit in the `api-partner.spotify.com/pathfinder` GraphQL JSON,
  but capturing a 30s teaser via a per-site GraphQL parser isn't worth the
  maintenance treadmill. The realistic capture path for a Spotify-published
  podcast is its **YouTube** embed (existing `youtube@` parser).

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
   `PAGE-STATE`, `VARIANTS`,
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
(`ApplicationLifeCycleHandler.onTrimMemory`), and `App.onCreate` restores it
**once per install** (`restoreIfPending`, guarded by: a marker in
`backup_local.xml` — a prefs file *excluded* from backup so it can't follow a
restore; an empty-table check so an in-place update never re-imports; and
column-name-intersection row copy so a schema-version skew degrades per-row
instead of failing). `file_safe` is forced to 0 on restore — a tampered
mirror can't inject vault entries.

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
`decryptPublicMirror` → `importMirrorDatabase` (the same column-intersection
importer the Auto Backup restore uses; `file_safe` forced 0). The write side
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

**A third door, the detected-reinstall banner** (`RestoreBannerAdapter`, a
self-hiding ConcatAdapter header like the incognito in-flight hint): shown
only when a reinstall is *detected* and the automatic restore came back
empty — never speculatively to fresh installs (a no-transport reinstall is
bit-identical to a fresh install, and scoped storage forbids peeking at the
public folder to check for a `.fdbk`; those users rely on the empty-state
button, which shows at exactly that moment). Detection is a **sentinel
pair** (`detectReinstall`): a random UUID lives in BOTH the default prefs
(backed up) and `backup_local.xml` (excluded); present-but-mismatched after
a restore-at-install = reinstall. A bare "are default prefs non-empty" check
does NOT work — `App.onCreate` writes boot keys (history-purge timestamp)
before detection runs, so a fresh install's prefs are never empty. **Banner
policy: at most ONE informational banner at a time** — the restore banner
yields to the incognito header (`updateRestoreBannerVisibility`,
re-evaluated on every `getSafeCount` change), and both report into
`getLeadingHeaderCount` for the grid SpanSizeLookup. Banner state lives in
`backup_local.xml` on purpose: a dismissal must never ride a backup into
the next reinstall — which is exactly when the banner is needed again.
Retired permanently on dismiss or any completed restore (cleared at the
data layer in `restoreFromTree`, so the Settings door retires it too).

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

**Restore SKIPS rows whose file the user already deleted.** Without this, a
reinstall+restore resurrects deleted entries as dead rows: `restoreFromTree`
reads and SUMS *every* `.fdbk` in the folder (to survive an empty-newest
mirror), so a stale mirror written before the delete keeps bringing them back,
and the mirror is only refreshed on app-background anyway.
`importMirrorDatabase` skips a row when `RestoredFileAccess.isRestoredFileMissing`
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

**Stale mirrors are PRUNED after a decryptable restore.** A `.fdbk` that
decrypted but imported **zero** rows (every entry a deleted file, or a duplicate
covered by a kept mirror) can never restore anything again and only causes the
recurring "0 restored". `restoreFromTree` collects those during the read loop
and `DocumentsContract.deleteDocument`s them after (best-effort, under the WRITE
grant). A mirror that DID restore rows is never touched — it stays the live
backup until the next background re-write. Note this prune does **not** run on
`RESTORE_WRONG_DEVICE` (the method returns before it — those mirrors can't be
decrypted, so we can't know they're stale).

**The empty-state Restore button retires after an ATTEMPT, not just success.**
It's shown whenever the unfiltered list is empty, so after a restore that brings
nothing back (0 / wrong device / no backup) it would re-offer the same futile
tap forever. A `restore_attempted` flag (set at the top of `restoreFromTree`, so
it covers both doors and every outcome) hides it once set. The flag lives in
`backup_local.xml` — **excluded from backup** — so a genuine reinstall offers
restore afresh while a within-install re-tap is not re-offered; Settings →
"Restore previous downloads" stays as the deliberate retry door.

## "Send to browser" — LAN share (`lanshare/`)

The Downloads options sheet's quick-action row has a **Send** button
(finished, non-safe entries only — **the vault is never sendable**, same
contract as the backup mirror). It navigates to `LanShareFragment` — a
**full nav-graph destination** in `nav_graph_downloads` extending
`BaseFocusFragment`, same pattern as FrameGrabber/GifMaker (it was a bottom
sheet originally, promoted because the QR/PIN handover wants a whole page;
toolbar back / Stop both just pop the back stack) — which
runs `LanShareServer` — a dependency-free `ServerSocket` HTTP server whose
**lifetime is exactly the fragment's VIEW lifetime** (started in
onCreateView, stopped in onDestroyView, hotspot reservation closed with
it): no background service, no discovery
announcement, nothing listens when the sheet isn't open. Any browser on the
LAN (PC, phone, TV — and another Firedown, which downloads it through the
normal pipeline since Firedown *is* a browser) opens `http://<ip>:53317`
(ephemeral fallback if the port's taken) and gets Firedown-styled pages
(served from `assets/lanshare/` templates with `{{TOKEN}}` substitution —
the error-pages assets+Java pattern; firedown.app's design language).
**Served pages are localized per REQUEST from the receiver's own
`Accept-Language` header** (`resolveLang`/`strings`/`localize` in
`LanShareServer`; the sender's locale is irrelevant — the reader is the
other person): all user-visible strings live as `{{T_*}}` tokens resolved
from `assets/lanshare/i18n.json` (the app's 16 locales + English; values
may carry the trusted `<em>/<b>` markup and `{{DEVICE}}/{{N}}`
placeholders), with per-key English fallback. The files page's JS button
states come via `<body data-*>` attributes, not JS string literals (an
apostrophe in a translation would break a literal). Gotcha fixed once
already: inside the `.steps` flex `<li>`, the step text must be ONE
`<span>` — a bare text node + `<b>` become separate flex items and the
bold word drifts to the far edge.

**No common LAN — the direct-connection (hotspot) path.** Two phones on
cellular share no network, so the LAN flow can't work. When
`getLocalIpv4()` finds nothing, the sheet doesn't dead-end: it offers
"Start direct connection" → `WifiManager.startLocalOnlyHotspot()` (no
internet upstream, torn down with the sheet via `reservation.close()` in
`stopSharing`). The same flow is ALSO reachable from LAN mode via the
"Use direct connection instead" text button (`lan_share_use_direct`) —
the **AP-isolation escape**: guest/café Wi-Fi that blocks
client-to-client traffic is undetectable sender-side (the sheet looks
fine, the receiver times out). In that path the Wi-Fi STA keeps its
address, so step 2 resolves the URL with **`getHotspotIpv4(staIp)`**, not
`getLocalIpv4()`: preference inverted to the AP interface
(ap*/swlan*/softap* by name, or any address differing from the
pre-hotspot STA address for vendors that name the AP wlan1/wlan2) —
`getLocalIpv4()` would prefer the STA address, which a hotspot client
can't reach. Step 1 repurposes the same sheet views as the join screen
(URL chip = SSID, PIN slot = passphrase, QR = standard `WIFI:` payload any
camera app joins from); the **Next** button flips to the normal URL/PIN QR,
with the server bound on the hotspot's AP address. Platform tax: fine
location permission at runtime (an Android requirement for hotspot APIs —
manifest carries `ACCESS_FINE_LOCATION`+`CHANGE_WIFI_STATE` for exactly
this; the browser never reads location) and location services must be on.
Related, in `getLocalIpv4`: interface selection is an **allowlist**
(`wlan/ap/swlan/softap/eth`), NOT "any site-local address" — carrier-grade
NAT gives cellular `rmnet` interfaces 10.x addresses that pass
`isSiteLocalAddress()`, so the old open fallback showed an unreachable
carrier IP on a 5G-only device (and `tun*` VPN addresses are equally
unreachable). After the hotspot is up the same method returns the AP
address, because the wlan STA has none in that state. **VPN:** a
sender-side VPN shows a warning line (`TRANSPORT_VPN` on the active
network — block-LAN/kill-switch configs break local sockets); a
RECEIVER-side VPN is undetectable from the sender, the docs/warning text
is all we can do.

**Access control:** the bare URL only ever yields the PIN page; the 4-digit
PIN (shown big on the sender's sheet) sets a random `HttpOnly` session
cookie that gates the file list and bytes; **3 wrong attempts lock the
session permanently** (un-brute-forceable). The QR encodes `?pin=` so a
scan authenticates in one hop; the typed URL stays short.

**Transport: self-signed TLS by default (`LanShareTls`).** A per-install EC
P-256 identity generated by **AndroidKeyStore** (which mints the
self-signed X.509 with the keypair — no BouncyCastle, no hand-rolled DER,
key never leaves the keystore; per-install so the cert fingerprint is
stable and a browser's stored "proceed" exception keeps working). What it
buys: ECDHE forward secrecy, so a **passive sniffer gets only ciphertext**
— the realistic capture cases are open Wi-Fi and shared-PSK WPA2 (anyone
with the café password + your join handshake can decrypt your air);
WPA3 and the LocalOnlyHotspot (random per-session passphrase) were already
sniff-resistant. The **one port speaks both protocols**: the handler peeks
the first byte (TLS ClientHello = `0x16`), wraps TLS via a `PrereadSocket`
+ the layered `createSocket(Socket, host, port, autoClose)` overload (the
JDK's consumed-bytes overload doesn't exist on Android), and answers plain
HTTP with the **onboarding page** (`onboard.html`) — the receiver's
on-ramp: typed `ip:port` and the QR both land there over http (no warning),
it explains the upcoming interstitial with numbered steps, and Continue
goes to `https://`. **The QR carries the PIN in the URL FRAGMENT**
(`#p=NNNN`, never sent on the wire — nothing secret crosses the plaintext
hop); the onboarding JS forwards it as `?pin=` to the https side, and
without JS the receiver just types the PIN at the gate. If the keystore
can't produce the identity, the server **falls back to serving plain
HTTP** (degraded beats not sharing; the sheet's cert hint hides and the QR
reverts to direct `?pin=`). The keystore key MUST whitelist
`DIGEST_NONE` (BoringSSL signs the raw transcript digest — NONEwithECDSA;
without it every handshake dies with 'Incompatible digest'), and the key
managers are built explicitly (`FixedKeyManager`) — the default PKIX
factory silently selects no AndroidKeyStore alias. Costs, accepted
deliberately: the receiver clicks through ONE
"connection not private" interstitial (waylaid by the onboarding page and
explained on the **sender** sheet — `lan_share_cert_hint`), and an
**ACTIVE MITM can still present its own self-signed
cert** — indistinguishable to the receiver; cert-*pinned* verification is
the LocalSend-protocol phase. **Chromium gotcha (observed on Brave): the
download manager does NOT inherit the page's cert exception** — a native
`<a download>` navigation fails its own TLS handshake
(`CERTIFICATE_UNKNOWN`) before headers and dies as a 0-byte file named
from the URL path. So `files.html` intercepts the Download click and
fetches the bytes **in the page context** (which holds the exception) →
`Blob` → object-URL `<a download>` save; browsers disk-back large blobs.
On any failure it falls back to the native navigation (correct for the
plain-HTTP mode), and the file URL carries the name as a trailing segment
(`/f/<i>/<name>`, ignored server-side) so even that fallback names the
file correctly. Do NOT try to fix the sniffing ceiling with
in-page JS crypto instead: on an `http://<ip>` origin the browser disables
`crypto.subtle` and ServiceWorkers entirely (insecure context), and
routing GB-sized videos through JS decryption breaks the native download
manager — TLS is the only mechanism that keeps the plain `<a download>`
flow. File names are HTML-escaped in the served pages
(user/site-controlled); the `Content-Disposition` carries an ASCII
fallback + RFC 8187 UTF-8 name. QR via the existing zxing-core dependency.

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
  (illustration `ill_baloons` bottom at the vertical centre, message below) and
  same text styling (`sans-serif-medium`, `colorOnSurface`); don't substitute a
  `TextAppearance` style / `colorOnSurfaceVariant`.

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
- **"Backing up…" snackbar has a View action, no success snackbar.** Tapping
  "Back up to cloud" (`BaseDownloadFragment`) shows a "Backing up…" snackbar whose
  **View** action deep-links to the backed-up-files list (where the live per-item
  progress shows); there is deliberately NO terminal success snackbar (the list is
  the confirmation) — only a `FAILED` transfer surfaces an error snackbar
  (`CANCELLED` stays silent).

- **Status shows a progress bar.** `TransferStatusPreference` reveals an
  indeterminate `LinearProgressIndicator` in its widget slot while a transfer runs
  (transfers are indeterminate — the workers post an indeterminate notification),
  driven by the `CloudBackupManager.WORK_TAG` WorkManager observer. It is ONLY on
  the Cloud Backup status row (`CloudBackupSettingsFragment`, `setActive(active)`
  from the `WORK_TAG` observer) — the unified Sync screen's "Downloads backup" row
  deliberately shows just the live summary text ("Backing up your downloads…"), no
  bar.

- **Deep-links + back-nav.** The upload/restore notification opens the Cloud
  Backup status screen (`SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP`, Back → settings
  list). The **Downloads toolbar overflow** opens the backed-up-files list
  (`EXTRA_OPEN_CLOUD_BACKUP_FILES`) and replaces the settings list on the back
  stack (`popUpTo settings inclusive`), so Back returns to **Downloads** (the
  caller), not into the settings tree. A "Sync" row in the home + browser popups
  opens the Sync hub (`EXTRA_OPEN_SYNC`).

- **Sync IA — a THIN hub + per-feature screens (don't re-merge them).** The
  Settings → Sync screen (`SyncSettingsFragment` / `settings_sync.xml`) is an
  account HUB only: a **Bookmarks** nav row (→ `BookmarksSyncFragment`, summary =
  on?last-synced:off), a **Downloads backup** nav row (→ `CloudBackupSettingsFragment`,
  live usage / "backing up…" summary), the **shared recovery code** (show/export,
  device-auth gated, shown once the account exists = bookmarks on OR a download
  backed up), and the encryption FAQ. The bookmark-only *actions* — the master
  **toggle**, **Sync now**, **Delete bookmarks from server** — live on the focused
  `BookmarksSyncFragment` (`settings_bookmarks_sync.xml`), NOT the hub; that
  declutter is the whole point. The **bookmarks-list overflow + sync banner**
  deep-link straight to that focused screen (`EXTRA_OPEN_BOOKMARKS_SYNC`, past both
  the settings list and the hub), so the overflow and the hub's Bookmarks row land
  in the SAME place. Bookmarks sync is free, downloads backup is pay-per-use, but
  pricing is kept **implicit** in the copy (no badge). Don't pull the toggle /
  Sync-now / Delete back onto the hub, and don't route the bookmarks overflow at
  the hub (`EXTRA_OPEN_SYNC`) — that was the "too many options / confusing"
  state this replaced.

- **FGS type.** Both workers run as `dataSync` foreground workers; the app's
  manifest merges `foregroundServiceType="dataSync"` onto WorkManager's
  `SystemForegroundService` (without it, `setForegroundAsync` crashes with
  "foregroundServiceType 0x… is not a subset of 0x0").

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
  (a custom AVIO handler). It already does Range/206 properly: accepts 206 as
  success, parses `Content-Range`, range-**chunks** large files, honours
  ffmpeg `offset`/`end_offset`, and falls back on 416. So the stream path was
  never affected by the progressive-download truncation bug — only
  `HttpDownloadStrategy` was.

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
**cancel-only** — never evict on normal completion, seeks, or range-chunk
reconnects (the warm pool is the common-case win; an HLS download reopens the
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
`Range` for a resume (`pos>0`) or when chunking a confirmed-large file (>2 MB),
so a 16-byte, offset-0 AES key is never ranged for **any** site.

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
- **The generated mime fallback thumbnail (`MimeTypeThumbnail`) has two modes.**
  List/grid rows pass `fillBounds=true` so the tint fills the whole
  rounded-clipped slot (the list slot is ~1:1, 78×64dp). The **media viewer
  keeps the default 16:10 letterbox** (`fillBounds=false`) to match
  `PlayerView`'s `resize_mode="fit"` — don't make the fill unconditional, it
  would paint the player background edge-to-edge.
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
  (`joinWithSize`) — size is the one list-line fact with no other home in the
  grid; date stays out because the sort headers carry it.
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
  Captured — keep all four identical.** The pattern (Files-by-Google): the
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

## Thumbnails (native `thumbnailer.c`)

`FFmpegThumbnailer.getBitmap(streamPos)` reads one frame; `streamPos` is a
three-way contract: **`>0`** seeks to that mid-clip position (explicit mandate,
`AVSEEK_FLAG_ANY`); **`==0`** decodes the head frame (some callers need the
first frame exactly — GifMaker tiles it across the filmstrip, SaveFrame
fallback); **`<0`** means *no mandate*, so the native side auto-seeks
`THUMBNAIL_DEFAULT_OFFSET_US` (3s) in (`BACKWARD` to the enclosing keyframe) to
skip the usual black opening frame — applied only when the clip is longer than
the offset (a shorter clip decodes the head, frame 0 being fine there), and
falling back to the head on seek/decode failure.
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
