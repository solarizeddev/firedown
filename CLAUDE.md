# CLAUDE.md

Guidance for Claude (and other agents) working in this repo. Read this before
touching the media parsers or debugging "video not picked up" issues.

## What Firedown is

Android browser + downloader built on **GeckoView** (not Chromium), with
uBlock Origin. Media capture is done by a set of **built-in WebExtensions**
bundled as assets and loaded via `GeckoRuntimeHelper.registerBuiltIn(...)`
(`app/src/main/java/com/solarized/firedown/geckoview/GeckoRuntimeHelper.java`).

### The extensions (`app/src/main/assets/`)

| dir           | id                       | role |
|---------------|--------------------------|------|
| `webrequests/`| `downloader@solarized.dev` | **ALL capture** — the former `parser@` extension was MERGED into this one. Two halves in one extension: (1) the per-site **parsers** (`js/parsers/` — one ES module per site: Twitter/X, Instagram, Threads, Facebook, Vimeo, Rumble, Bilibili.tv, Niconico, Kick, Twitch, Dailymotion, Apple Podcasts, TikTok, Bluesky; emits entries **with metadata** — title, author, thumbnail, duration, quality variants) plus the page-state bridge (`js/page-state-bridge.js`); (2) the **generic catch-all** (`js/requests.js` + `js/content-script.js` — any media URL seen on the wire, no rich metadata). Also hosts `js/wasm-watch.js` (+ `js/wasm-probe.js`), the WASM-disabled detector — a settings feature, not capture. |
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
  probe); `.mp4`/`.m4v`/`.webm` → progressive variant; a
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
   `RUMBLE`, `TWITCH`, `KICK`, `VIMEO`, `DAILYMOTION`, `TIKTOK`, `VARIANTS`,
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
- Rationale: this is a privacy/no-telemetry app — logs can contain URLs, titles,
  cookies-adjacent data. Release builds must be silent.
- **Never log user-controlled text whole — truncate it.** The URL bar can hold
  an arbitrarily large paste (a user once pasted a multi-hundred-KB logcat dump);
  `AutoCompleteEditText` used to log the full before/after text on **every**
  text/focus event, multiplying the blob through logcat and churning the main
  thread. Its logs now go through `logPreview(...)` (128-char cap + length
  suffix). Apply the same cap to any new log of field content, page titles,
  clipboard text, etc.

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
the surviving public files, but on Android 13+ a reinstalled app doesn't OWN
them (MediaStore attribution died with the uninstall), so playback may need
a permission story even though the list+metadata are intact.

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
them and takes the newest it can decrypt). The SAF restore UI is NOT built
yet — only the write side + decrypt/import plumbing ship today.

## URL-bar clipboard chip & system-service binder calls

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
  space-less scripts (CJK/Thai) and silently drops real titles. The mime chip is
  always present in both grids, so type stays labelled even when the title is
  hidden. (Captured used to *always* show the title as a pre-download decision
  surface; that was changed to match Downloads — an image tile's slug name is
  noise on the decision surface too.)

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
