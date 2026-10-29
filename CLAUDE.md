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
| `webrequests/`| `downloader@solarized.dev` | **ALL capture** — the former `parser@` extension was MERGED into this one. Two halves in one extension: (1) the per-site **parsers** (`js/parsers/` — one ES module per site: Twitter/X, Instagram, Threads, Facebook, Vimeo, Rumble, Bilibili.tv, Niconico, Kick, Twitch, Dailymotion, Apple Podcasts, News Over Audio, TikTok, Bluesky, Telegram, Videee, Spotify, Deezer, Substack, Acast; emits entries **with metadata** — title, author, thumbnail, duration, quality variants) plus the page-state bridge (`js/page-state-bridge.js`); (2) the **generic catch-all** (`js/requests.js` + `js/content-script.js` — any media URL seen on the wire, no rich metadata). |
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

**Background-page state must be BOUNDED — the page lives as long as the app
process.** Two rules, from an audit of every parser module + `requests.js`
(2026-10): (1) **every long-lived Map/Set needs a removal path that runs
without anyone looking up the same key again**, and in the parser tree that
path is ONE of two primitives in `common.js`, never a hand-rolled copy:
**`ClaimSet(ttlMs, max)`** (check-and-claim in one synchronous step —
`claim(key)` is true only for a key that is absent or expired; `add` refreshes,
`has`/`release`/`keys()`) and **`MetaCache(max, ttlMs?)`** (`set`/`get`/`has`/
`delete`, iterable). Both bound the same way with NO timers: a TTL judged at
lookup plus a HARD FIFO cap on every insert (expired entries evicted first,
then the oldest; an insert of an existing key moves it to the tail, so the cap
never evicts a live entry as "oldest"). A key nobody asks about again can
linger only up to the cap — a bound in COUNT, which is all memory needs — and
a key someone does ask about is judged by the TTL. Every `processed*Urls`
set, the per-tab sent-origin sets, `ownRequests`, the meta caches, `twitchRendezvous`,
`dmEmbedCache`, the emit-claim maps and the SPA `seen` maps are instances;
the smoke's `claimset:`/`metacache:` section pins the semantics. History the
rule replaces: eight `Set`+`setTimeout`-per-entry copies, five FIFO caches,
three "sweeps" that deleted only EXPIRED entries and so were not caps, a
`setInterval`, and a cache whose TTL timer deleted a re-created entry — all
drifting copies of one idea (the `parserOwnedRequests`/`tokenCache` lesson:
a lookup-time TTL alone removes nothing for a key nobody asks about). (2)
**Every response-body reader is capped while it streams.** The shared readers
in `common.js` (`filterResponseText` / `readFilteredBody` /
`collectFilteredResponse`) write every chunk straight through but keep a copy
only under `FILTER_BODY_MAX_BYTES` (16 MB); past it the body passes through
unread and the reader reports it like any unreadable body (null / no callback
/ rejection). They used to buffer everything and only then look at sizes —
acast.js checked 8 MB on the DECODED string, after the body, a joined copy and
a UTF-16 decode were all in memory — while Substack's document filter matches
every `*.substack.com` main frame with no content gate. Don't add a reader
that buffers first and measures after; use these, or cap per chunk the way
`instagram.js` (8 MB) and `requests.js`' sniffers do. Pinned by the smoke's
`filter-cap:` section.

**Four more rules from the full leak/concurrency review that followed
(2026-10), each pinned by a replay or smoke check that FAILS on the code it
replaced.** (3) **A listener that only observes is never registered
`blocking`, and a blocking one carries the `types` its gate accepts.** A
blocking registration holds the page's own request until the listener
RETURNS — for an `async` listener, until its promise settles: Vimeo's
re-fetched the embed config before letting the embed load (it reads the
player's own response now, through a filter armed by a listener that
returns synchronously), Twitch's held
every playlist refresh behind the background event loop, and the catcher's
manifest sniff / `.vtt` arm, registered for every type on `<all_urls>`,
suspended every image, script and document in the browser behind whatever
that loop was doing. Only a listener that `filterResponseData`s or modifies
the request needs `blocking`. (4) **One native emit per response, and ONE decision per response.**
The catcher listens on both `onHeadersReceived` and `onResponseStarted`,
and both copies used to run the tab/metadata round trips and send a message
— two `GeckoInspectTask`s per capture, two probes on a multi-thread pool.
The emit is claimed on the chain record synchronously (see the decide/emit
split below). Since step 5, the record ALSO remembers the DECISION
`onHeadersReceived` took for a URL (`decided`), and `onResponseStarted` for
the same response returns on it — one Map lookup instead of a second run of
the whole table for every response in the browser; keyed by URL as well as
requestId because a redirect chain is two responses under one id (the
hop's rejection must never skip the target; pinned, with mutation). Why two
listeners at all: `onHeadersReceived` is backed by Firefox's
http-on-examine-response / -cached- / -merged- observers (network and cache
alike), while `onResponseStarted` is the channel's onStartRequest — the
plausible origin of the pair is a ServiceWorker-synthesized channel, which
skipped the examine notification before the geckoview 0006 patch and so
fired only the second event. Whether anything still reaches
`onResponseStarted` alone on a 0006 build has NEVER been measured, so the
belt stays, instrumented: `__listenerStats()` counts
`responseStartedSkipped` / `responseStartedOnly`, and a debug build logs
`diag:response-started-only` for an interesting URL the primary never saw.
**The rule for dropping it**: browse the CaptureLiveTest sites plus a
SW-heavy one (TikTok's `/related` feeds) on a debug build and grep for
`diag:response-started-only` lines whose URL then reaches `forward …
listener=onResponseStarted`; none → remove the listener (and this
paragraph's belt), any → it stays and the site is recorded here. Don't
remove it on reasoning alone — the whole point of the counter is that the
answer is empirical. Since the per-request
record refactor (step 2 of the 2026-10 restructuring) that claim, the
parser-owned mark, the `.vtt` body verdict and the onSendHeaders snapshot
are FIELDS of one `RequestRecord` per chain (`requestRecords`, a `MetaCache`
keyed by requestId: created by whichever event comes first, deleted at
`onCompleted`/`onErrorOccurred` and on the tab closing, TTL at lookup + FIFO
cap) — a fact about a request cannot be left behind by a map that forgot its
removal path, because there is one map and one lifecycle. And since step 4,
**`processResponse` is a synchronous `decideCapture()` followed by an async
`emitCapture()`**: the whole accept/reject table — extension-context drop,
classify, redirect hop, HLS child of a read master, player-claimed URL, the
sub-frame player-claim gate, then the emit claim LAST — runs with no await
and returns a VALUE (`{action:'reject', reason}` / `{action:'emit', hold}`)
the smoke's `decide:` section asserts on directly; `emitCapture` does the
slow part (the bounded player-claim hold, the `.vtt` verdict, tab
resolution, the frame's metadata query, the Referer backfill, the send) and
never re-decides. A decision to emit is the one thing that claims the chain,
and a rejection never does. Be precise about what the synchronous claim
buys: the two copies of a chain SERIALIZE on the record, so even a claim
taken at the send still emits once — what a late claim loses is the WORK,
the second copy running the whole tab + metadata round trip for nothing
(and parking a second waiter on a held sub-frame report). The hub check
therefore counts ONE metadata query per chain with the second copy arriving
20 ms behind a slowed query; a claim moved to the send fails it and the
`decide:` claim check. (5) **A cache that holds credentials lives in the TAB, never
keyed by URL alone.** The request-header cache carried a private tab's
`Cookie` into a regular tab's same-URL capture (and the reverse) for its
whole TTL; it is a field of the tab's `TabState` now
(`getCachedHeaders(tabId, url)`) — a tab has ONE browsing mode, so an entry
cannot cross modes by construction — and a probe entry (the extension's own
HEAD, the default jar) served to a private tab is stripped of credentials.
Likewise **`browser.cookies.getAll` reads the DEFAULT jar
unless given `storeId`** — a parser attaching cookies to its emit
(TikTok/Deezer/niconico) and the native `getCookiesForUrl` responder use the
capturing tab's jar via `cookieQueryForTab` / the Java-supplied `incognito`
(`firefox-private` / `firefox-default`). (6) **A capture whose tab closed
mid-await is dropped**, not sent under a dead tabId Java's `onRemoved` trim
has already run for. Own fetches carry `AbortSignal.timeout` (the HEAD probe,
Vimeo, Dailymotion, Instagram's shortcode fetch) so a stalled host can't hold
a claim or a batch for Necko's whole response timeout. (7) **Everything
remembered ABOUT a tab is a field of its `TabState`** (`js/tab-state.js`,
step 3 of the restructuring): the tab-URL cache (`rememberTabUrl` /
`tabIdForUrl` / `tabUrls`), the request-header cache, the HLS children a
master listed, the player claims (+ the parked sub-frame waiters), the frame
captions, the content-script scrape dedup, the snapshot gate, and every
parser's per-tab decisions through `tabClaims(tabId, name, ttl, max)` —
Instagram's SPA grace, Acast's emit/SPA claims, and `common.js`'s
`sentSet(tabId)` origin dedup. ONE `tabs.onRemoved` listener drops the
container whole (resolving parked waiters, clearing the snapshot timer), so
"tab closed → everything about it is gone" is structural rather than a purge
loop each map had to remember to join (five maps had none, and a tab id
reused within 30 s inherited the dead tab's sent-origin suppression of the
same page). `UNKNOWN_TAB` (-1) is a pseudo-tab for facts recorded without a
tab; the -1-is-a-wildcard lookups (HLS children, claimed URLs, sent origins)
check the tab's state plus the pseudo-tab's, or every state for a request
with no tab. The HEAD probe's `onSendHeaders` (tabId -1, moz-extension
origin) is filed under the tab that asked for it (`probeTargets`). Pinned by
the smoke's `tabstate:` section, teeth verified by mutation: a no-delete
`onRemoved` fails eight checks, an unfiled probe three, a shared sent set
two (incl. the older `caption: keyed per tab`), an unreleased waiter one.
(8) **A claim that GATES a capture is keyed by (tab, content), never by the
content alone** — a `tabClaims(tabOfDetails(details), …)` set, or a key that
starts with the tab. A content-only claim gives a SECOND tab on the same
video nothing for as long as the first tab's claim lives. It shipped as
"Dailymotion video detected in one tab, nothing in the next" (a 30-minute
per-video emit flag — it predates the restructuring) and
the sweep that followed found the same shape in ten more parsers: Twitter's
rich-captured mark (global, no TTL, so the wire backbone was suppressed in
EVERY tab once any tab had richly captured a media), Twitch's rendezvous
(keyed by login, so a second tab's master completed the FIRST tab's
rendezvous and the emit went out under the wrong tab), and the burst claims
of Kick, Apple Podcasts, Deezer, News Over Audio, Spotify, Substack, Vimeo
and Instagram's pending/queue sets. `tabOfDetails` (`common.js`) is the one
tab-of-a-request rule: the `ensureTabId`-resolved tab, else the webRequest
tab, else -1. What stays shared is METADATA (titles, thumbnails,
durations — facts about the content, true in every tab: the `*MetaCache`s)
and `ownRequests` (the extension's own fetches carry no tab). Pinned by
`parsers-replay.mjs` `per-tab:` checks (Kick, Twitch interleaved, Substack,
Twitter backbone) and `dailymotion-replay.mjs` section 8 — all five
`per-tab:` checks fail on the content-keyed code.

Native bridge: the parser half still calls
`browser.runtime.sendNativeMessage("parser", …)` and the catcher half uses
`"browser"` — the merged extension's message delegate is registered under BOTH
nativeApp names (globally and per-session, mirroring the youtube/PoToken
multi-name pattern in `GeckoRuntimeHelper`). Java handles captures in
`GeckoRuntimeHelper.handleExtractionMessage` / `GeckoInspectTask`.

### The bundled uBlock — a STRIPPED upstream build plus a small patch set

`ublock/` is the output of upstream's `make firefox` (currently **base
1.73.0**, manifest version `1.73.12` — a LOCAL revision, bumped for the
`ensureBuiltIn` version-cache trap like any extension change. **The local
version MUST stay three digit-only segments** (`1.73.10` → `1.73.11` → …,
like the old `1.71.12` on base 1.71.0): vapi-common flags the build a
DEVBUILD when `/^\d+\.\d+\.\d+\D/` matches the version — any non-digit after
the third numeric group, so a dotted 4th segment like `1.73.0.1` qualifies —
and a devbuild loads `assets.dev.json`, which the stripped stable bundle
does not ship, so **zero filter lists select** on a fresh profile; the smoke
pins this), with the dashboard/
settings/logger/picker UI, keyboard commands, and most locales removed, and a
small Firedown patch set on top. The patch set is exactly: `js/firedown.js`
(NEW — the `"ublock"` native-port bridge: ads/JS/media/fonts/cookie-notice
toggles + per-page block tallies), `js/vapi-background.js` (native firewall/
cookie state push on tab complete; null-safe title; browserAction removed),
`js/tab.js` (shield state on tab activation), `js/contextmenu.js` +
`js/document-blocked.js` + `document-blocked.html` (Firedown-branded blocked
page, `img/ill_*.svg`), `js/start.js` (drop the stripped `commands.js`
import), `background.html` (adds firedown.js), `manifest.json` (strip
popup/commands/options_ui/`menus`; keep `strict_min_version` 79; add
`geckoViewAddons`/`nativeMessaging`/`nativeMessagingFromContent`), and
`_locales/` (trimmed to the app's 16 + hand-made minimal `pt`/`zh` carrying
only the `docblocked*` strings). Everything else is stock upstream — keep it
that way: to update, build the new tag, re-apply that list verbatim, and
verify with **`node scripts/ublock-smoke.mjs`** (loads the real background
module graph under a stubbed `browser`, and behaviour-tests the CNAME-uncloak
decision table in the REAL `js/vapi-background-ext.js`). The DEVICE half of
the bridge — the `ensureBuiltIn` registration, the `connectNative("ublock")`
port transport, real webRequest blocking against the bundled lists, and the
per-page/cookie toggles' Java→JS→Java round trips — is covered by the
instrumented **`UblockBridgeLiveTest`**
(`app/src/androidTest/.../geckoview/`; `CaptureLiveTest` follows the same
pattern):
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.geckoview.UblockBridgeLiveTest`
with a device attached (t5 needs network). The node smoke owns JS semantics;
the live test owns transport — a toggle bug fails the former, a
port/registration bug only the latter. History the smoke
pins: the bundle long shipped a one-character local edit there —
`cnameIgnoreList.test(cn) === false` — that INVERTED the ignore-list guard
and silently disabled CNAME uncloaking (cloaked trackers rode first-party
subdomains straight through); the file is stock now and must stay stock.
Lazy `import()`s of stripped files (`benchmarks.js`, `static-dnr-filtering.js`
in `messaging.js`) are dead devtools paths and fine to leave unresolved.

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
  fragments, a player's placeholder file; parser-dedup blocks pair 1:1 with
  a parser, so they live in their
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
  packed-embed class) gets NO block rule**: the bridge fires pre-play and
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
  e.g. a jw8 embed on a streaming host. The wire can't see it
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
  `emitOneGroup`), for players that DON'T expose their fed URL on a readable
  API like the one above — instead the fed value is a **config / source-list
  in a page-world global** (or a tokenized JSON delegate that only resolves to
  a URL after a fetch). Capture **whenever a site holds a playable media URL
  in a page-world JS global before the player fetches it on play** — a custom
  player config (`window.page_params`, `flashvars`…), a framework store
  (`__NEXT_DATA__`/`__NUXT__`/Redux…), or any plain object. Like
  `findPlayerMedia` it runs in **all frames** (the player is often a
  cross-origin iframe) and reads the player's **RESOLVED** page-world values,
  so a packed / `eval`-obfuscated source URL is read **post-resolution**,
  never the packed blob (read the result, not the packer — not cat-and-mouse).
  **How it matches (shape, never host):** walk the state tree (bounded — depth
  + 30k-node budget + visited set, index-loop/primitive reads only, Xray-safe)
  and collect a URL only when it sits under a **media-ish KEY**
  (`videoUrl`/`url`/`src`/ `file`/`hls`/`source`/`contentUrl`… —
  `MEDIA_KEY_RE`) AND its **VALUE is a real media URL**: the key says "a
  player", the value extension says "a url to play" (this key-proximity is
  what keeps it off the page's countless non-media URLs — share/canonical/next
  links carry no media extension). Three outcomes per hit: `.m3u8` → HLS
  master (`postHlsMaster` → `page-state-hls` — Java enumerates, no probe);
  `.mp4`/`.m4v`/`.webm` → progressive variant; **`.mp3`/`.m4a`/`.aac`/
  `.ogg`/`.opus`/`.flac`/`.wav`/`.weba`** (`AUDIO_RE`) → progressive AUDIO
  variant (no resolution) — podcast/article audio inlined in page state (e.g.
  a podcast app's `__NEXT_DATA__.mediaUrl`, an article TTS `audio_url`);
  `MEDIA_KEY_RE` therefore carries `audio_?url`/`enclosure` keys alongside the
  video ones. **The audio variant is marked `audioOnly: true`** and
  `VariantProcessor`'s skip-probe branch honors it — the entity keeps the
  URL-derived audio mime + `audio` flag and downloads as raw FILE
  (`HttpDownloadStrategy`), instead of the branch's video/mp4 stamp (which
  made a podcast mp3 show as a VIDEO and save under a `.mp4` name). The same
  mark flows from every bridge reader: `mediaKindOf` has a third `"audio"`
  kind (an `audio/*` MIME or `AUDIO_RE` extension — so an `.mp3` also never
  falls into the obfuscated-master HLS default), and a DOM **`<audio>`
  element** types its sources audio regardless of extension (a
  `.mp4`-container m4a on `<audio>` is still audio); a **SAME-ORIGIN non-media
  URL beside a `format`/`quality`/`segmentFormats` hint** → a tokenized
  **media-list DELEGATE** (e.g. a player whose source is
  `…/media/mp4/?s=<token>` returning `[{quality, videoUrl:…mp4}]`), resolved
  with a **credentialed SAME-ORIGIN `fetch`** (`fetchMediaList`). Same-origin
  is the whole point: the bridge runs ON the page, so the resolve needs **no
  host permission and hits no CORS** — which is why it lives in the bridge,
  not `background.js` (which would need a per-host permission + CORS bypass).
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
- **Emit + metadata.** Progressive → Background
  `kind:"page-state-progressive"` → `sendVariants` (skip-probe auto-set from
  any page `duration`; the variant carries only `height`, `width:0` —
  `JsonHelper` renders `"720p"`, never `"0x720"`). **Request headers: the
  MEDIA-ELEMENT request set, but NO Referer/Origin/Cookie** — the
  `<video>`-element shape for video groups, and for a declared-audio group
  (primary variant `audioOnly`) the `<audio>`-element shape (Firefox's audio
  `Accept` string + `Sec-Fetch-Dest: audio` — a video-shaped header set on an
  mp3 is exactly the deviation a header-gating CDN rejects). These URLs are
  query-signed/self-authorizing (verified in practice: the real browser fetch
  carries no Referer/Origin/Cookie), so those are omitted — but some
  progressive CDNs still gate on the headers a real `<video>` fetch *always*
  carries: a `/play/video/<token>` streaming host **404s a bare GET** (and a
  UA-only ffmpeg probe), while the **byte-identical** url+token plays
  in-browser as a **206** — the difference is `Accept: video/*` +
  `Sec-Fetch-Dest: video` + `User-Agent` + `Accept-Language`. So
  `handlePageStateProgressive` sends exactly that set (UA/Accept-Language from
  the bridge's `readNavigatorHints`), benign for self-authorizing CDNs since a
  real browser sends them too. Range is NOT sent up-front
  (`HttpDownloadStrategy`'s fresh request is no-Range by design) but its
  **403/404/416 retry adds `Range: bytes=0-`** carrying these same headers —
  so the retry replicates the player's exact request (headers + Range → 206).
  Ceiling: a CDN that also fingerprints **TLS (JA3/JA4)** is still unreachable
  to OkHttp, header-spoofing or not. (HLS-master CDNs get the fuller set incl.
  Origin/Referer — see `postHlsMaster`.) Title is the player's own name for
  the clip when a reader has one (Brightcove's `mediainfo.name`, a JW item's
  `title`), else the generic page title; `duration` comes from a
  `DURATION_KEY_RE` sibling, and the picture is the group's own (`IMG_KEY_RE`
  sibling, a player's `poster()`/`image`) BEFORE the page's og:image —
  `emitOneGroup` had them the other way round, which put a news front page's
  logo on every clip.
- **Dedup on play — NO `parser-blocklist.js` rule for generic-bridge sites.** The
  player's default quality is the entity's primary URL, and the bridge fires
  PRE-PLAY, so it dedups **by URL** against the play-time wire capture. A media
  captured by the bridge's **generic, host-agnostic** readers (the
  JSON-delegate / packed-embed class) **never gets a `parser-blocklist.js`
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
  **Every `Object.keys` in the walks goes through `boundedKeys`** — the node
  budget counts nodes, not keys, and `Object.keys` materialises EVERY index
  of an array-like as a string: a wasm module's `HEAPU8`/`HEAP32` (tens of
  MB, plain window members in non-modularised Emscripten output) turned one
  node into millions of allocations and froze the content process. Typed
  arrays/buffers (`byteLength` is a number) are skipped outright, any other
  node is read through its first `MAX_KEYS_PER_NODE` keys.
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
    beacons, init/numbered segment fragments, a player's placeholder file
    such as `dummy.mp3`). **Bundled-only**, shipped in the
    APK. (The old 6h remote fetch of `firedown-webrequests/regex-patterns.txt`
    was removed — the endpoint 404s and the bundled list is the single source of
    truth; ship pattern changes in the APK like any other capture logic.)
  Logic changes in `requests.js` ship in the APK.

  Note one thing that is **NOT** a URL rule in either file: **ts-in-png** (MPEG-TS
  video disguised as `image/png` — an ad-CDN trick). It's
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

### X / Twitter — three layers, and the SSR document has TWO shapes

`js/parsers/twitter.js` captures in three layers, each a floor under the one
above: (1) the GraphQL XHRs (`TweetDetail`/`TweetResultByRestId` on
`api.x.com` logged-out and `x.com/i/api` signed-in, plus the timelines) read
by `filterResponseData`; (2) the single-tweet DOCUMENT (`x.com/<user>/status/
<id>`), because a status page can render the tweet server-side and fire no
GraphQL; (3) the wire-master backbone (`video.twimg.com/*/pl/<hash>.m3u8` as
the player fetches it, skipped when a rich layer already captured the
media-id). One shape-tolerant extractor serves (1) and (2)
(`media_entities2` / `legacy.extended_entities`, `details.full_text` /
`legacy.full_text`, `core.user_results`). `video.twimg.com` is
parser-block-listed, so a miss in every layer loses the video entirely.
Layer 3's thumbnail is the `<video poster>` URL the page fetched
(`twitterThumbCache`, by media id), kept WHOLE: X's posters are
extensionless (`amplify_video_thumb/<id>/img/<key>?format=webp&name=large`)
and the bare path 404s, so the query-stripped copy it used to keep could
never load (pinned by `parsers-replay.mjs`).

**Layer 3 titles a video from what any response said about its tweet**
(`twitterMetaCache`, by media id: title, @author, poster, duration —
the Bluesky `bskyMetaCache` design). Every capture context
`emitTweetMedia` sees is remembered, and a single-tweet view also
REMEMBERS, without emitting, every other tweet of the conversation
(`rememberTwitterTweetVideos`: replies and a thread's parents, which are
not captured before they play so they don't bury the tweet the user
opened). Layer 3 used to know only the page URL's handle, which names the
account the PAGE is about: a reply played on someone's status page was
credited to the page owner, and off x.com every miss read "X video". A clip
no response described keeps the old title. **And a tweet shown INSIDE
another is captured as its own tweet** (`shownTweets`): a quote shows the
quoted tweet, a repost shows its original (own author, text and id, not
the reposter's "RT @…" wrapper, which is only the fallback when the
original is unreadable). Layer 1 read only the outer tweet, so a quoted
video in a feed or on a status page reached the sheet through Layer 3 as
"X video" or under the quoting account. A nested tweet never takes the
page URL's handle as its author (`tweetScreenName` with no `details`).
Each video also carries its own poster (`media_url_https`), not the
tweet's first one. Pinned by `parsers-replay.mjs` (`twitter-nested:`;
planted bugs: outer tweet only, Layer 3 ignoring the cache, the
conversation not remembered, no repost fallback — each fails). The SSR
document path remembers the replies too: a streamed `TweetDetail` body in
the document (the third SSR shape, below) takes the same route as the XHR.

**Layer 2 must read BOTH SSR formats.** X moved from the
normalised Relay store (`<script class="$tsr">` + a flat `relayRecords` map
linked by `__ref`) to a STREAMED router: a `data-tsr-stream-part` script
declaring the shared capture table `$R["tsr"]` and `$_TSR.router=($R=>$R[0]=
{…})($R["tsr"])`, whose loader inlines the already-RESOLVED
`tweet_result_by_rest_id` result beside live JS (arrow functions, `new`, an
IIFE), followed by plain `<script>`s pushing the TweetDetail conversation
(focal + quoted + REPLIES) into the same table with `$R[n]` back-references
that cross script boundaries. The parser's old gate (`class="$tsr"` /
`relayRecords`) matched nothing, the listener logged `0/0 tweet(s)`, and
capture survived only because that logged-out page also fired `TweetDetail`.
Now: `extractTsrScripts` collects every script touching `$R["tsr"]`/`$_TSR`,
`parseTwitterSsrStreamValues` parses every `$R[n]=` capture across them with
ONE shared table, `parseRelayValue` skips an unknown token as a balanced JS
expression (so the object after an embedded function keeps parsing), and
`collectTwitterSsrTweets` falls through from the store shape to the stream
shape, collecting `tweet_result_by_rest_id` nodes ONLY — never the
conversation's `tweet_results` (replies are not the page's tweet).

**And a THIRD shape, read through the GraphQL path: the streamed router
with every body as a JSON STRING.** The same `GraphQLRequestStream.Completed`
messages, in the router's `dehydratedData.messagesBeforeDehydration` and in
the plain scripts after it, but `result:{kind:"Result.Ok",value:"{\"data\":
…}"}` — the body serialized as a string the key walk cannot see into
(Instagram's Bloks-payload class). `collectTwitterSsrBodies` parses every
such value that names a tweet result (a marker test before the parse, a
4 MB cap per body) and `processTwitterDocument` feeds each through
`processTwitterResponse` by `twitterBodyKind`: a focal result or a
conversation is a single-tweet view — the focal emitted
(`extractTwitterFocalResult` reads `data.tweet_result_by_rest_id` directly
and takes the focal id from the document's `/status/<id>` when there are
no request variables) and the replies remembered for Layer 3 — and
anything else is a timeline, emitted whole. That last case is why **the
document listener reads EVERY x.com / twitter.com main_frame, not only
`/status/`**: a logged-out PROFILE page renders its whole timeline into the
document and fires no GraphQL XHR at all (api.x.com answers only the
viewer and hashflag calls), so with the status-only match every video on
it reached the sheet through Layer 3 as "X video"; on the status page the
shape-(b) walk logged `0/0` and the clip arrived under the page's handle.
The marker gate keeps a document with no store cheap. All three shapes
are pinned in `scripts/webrequests-smoke.mjs` (`ssr:`, `ssr-stream:` —
the second with an embedded function, a cross-script back-ref and a reply
carrying its own video that must not be collected — and `ssr-string:`),
and the document listener end to end in `parsers-replay.mjs`
(`twitter-doc:`: the status document captured once, titled and credited,
its played reply titled from the remembered conversation, its own master
quiet; a document carrying only the focal query's body; a conversation
with no focal key and the reply listed first; the profile document
emitting every video; a store-less document; mutants — no string bodies,
a status-only match, no URL focal id, no direct focal key — each fail). A
"tweet not captured" report starts with `node scripts/webrequests-smoke.mjs`
and a HAR of the page: if the document has neither marker, X moved the SSR
again — fix the document parser, don't lean on Layer 1 having fired.

**Embedded tweets have their own endpoint — Layer 1b.** A tweet embedded in
another site (`<blockquote class="twitter-tweet">` + `widgets.js`) is rendered
by the embed iframe from one JSON call per tweet,
`cdn.syndication.twimg.com/tweet-result?id=…`, never GraphQL. Its
`mediaDetails` is the classic media-entity shape (`video_info.variants`,
`duration_millis`, `media_url_https`), `user.screen_name` and `text` sit at
the top level, and a quoted tweet rides inside as `quoted_tweet`.
`listenerTwitterSyndication` reads it (`extractSyndicationCapture`, then the
shared `emitTweetMedia`), so the emit marks the media rich-captured and the
wire fallback stays quiet on play. Without it, every embedded clip arrives
only through Layer 3, titled "X video". `quoted_tweet` and `parent` are in
`TWEET_NESTED_TWEET_KEYS` so the shape scan never credits a quoted video to
the quoting tweet. **Every tweet the body carries is its own capture**
(`syndicationTweets`: the embedded tweet, its `quoted_tweet` and, for a reply,
its `parent`, recursively to depth 2, each id once), titled and credited to
its OWN author under its own status origin. The `parent` matters because the
embed of a reply renders the tweet it answers above it, video included:
reading only the outer tweet and its quote, those parent videos reach the
sheet through Layer 3 as "X video". Pinned by `parsers-replay.mjs`
(`twitter-embed:` parent checks, which fail on the quote-only loop). Note that
the embeds only render at all because Gecko's social-tracker blocker is off
outside Strict (see the security sheet section).

**A clip is titled by its tweet's text, not its author** (`tweetTitle`: the
t.co links dropped, whitespace collapsed; `@handle` when the tweet has no
words, the handle is the description). The author's handle titles every clip
of an account the same: five identical tiles on one article. Same rule for
every layer that emits through `emitTweetMedia`.

**Each media of a tweet is its own capture — `emitTweetMedia` passes
`dedupKey: <origin>#<twimg media id>`.** Every video of a multi-video tweet
shares the tweet's origin, and `sendVariants`' origin-keyed default dropped
videos 2–4 as "already sent"; with their ids already in `twitterRichCaptured`
Layer 3 skipped them on play too, so they were lost entirely. The same media
seen again (TweetDetail + a timeline + the SSR document) still collapses —
its id is the same in every response. And a media is marked rich-captured
ONLY when it actually emitted (inside the loop, after the mp4-variant check):
an HLS-only media used to be marked and then skipped by Layer 3 as well.
Pinned by `parsers-replay.mjs` (`twitter:` checks).

### Audio title/thumbnail enrichment — gating, MediaSession, embedded players

The generic catcher enriches a captured media URL's filename with page metadata
(title/description/thumbnail) by querying the content script
(`requests.js` `get-page-metadata`). For **standalone audio files**
(`urlIsStandaloneAudio` — `.mp3/.m4a/.aac/.wav/.weba/.opus/.flac/.oga/.mid`) this
is **GATED**, because most incidental sounds (notification dings, UI sfx,
background music) would otherwise inherit the page headline (a
`/audio/notification.mp3` named after a movie). Video, HLS/DASH
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
  element and sets no MediaSession, so it stays suppressed.
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
  is the real webRequest frame on the wire path and the scrape's OWN frame on
  the content-script path (`sender.frameId`, since 2026-09; it used to be a
  hardcoded `0`), and a scan-reported clip's metadata is built in that same
  frame (`reportedMetaFor`). Frame-targeting is what makes the all-frames
  responder safe (no cross-frame title contamination). Do **not** re-add a
  "subframe audio inherits the TOP page's og:title" heuristic — it stamped every
  clip on a page with one shared title (the thing this replaced).
- **PER-URL declarations are the one thing a frame reads from its parents**
  (`declaringDocs` in `content-script.js`): the `application/firedown+json`
  block, a VideoObject whose `contentUrl` IS the captured URL, and the same for
  an AudioObject — this frame's document first, then each SAME-ORIGIN ancestor
  (explicit `window.origin` match; a cross-origin ancestor is skipped and the
  walk climbs past it; a sandboxed frame's opaque origin never matches; four
  levels at most). A relative `contentUrl` or thumbnail resolves against the
  DECLARING document's base, not the frame's. Exact-URL only, so it can never
  name the wrong clip; page-level fields (`<title>`, og:, the first
  VideoObject, og:audio) stay strictly per frame per the bullet above. Why:
  frame-targeting silently broke the case the block was built for. firedown.app
  declares its showcase clips in the HOMEPAGE head while they play inside the
  `/demo` iframe; the block reader was written top-frame-only (2026-06-26),
  the wire query moved to the owning frame a day later (`0dc1bc7e`, 1.1.81) and
  the scrape followed (`1bef447e`, 1.1.93), after which every demo clip was
  captured as "Firedown · demo". The website now also declares
  the clips inside the demo itself, which fixes installed builds; this rule is
  what makes a parent's declaration count at all. Every block in a document is
  read (it used to take the first, so a malformed block hid a valid one).
  **Verify with `node scripts/frame-meta-harness.mjs`** — the real content
  script in real Chromium frames (Playwright; SKIPs without it): same-origin
  parent, nested, srcdoc, cross-origin parent and middle frame, sandbox,
  relative URLs, own-frame precedence, page-level fields staying put, the
  scan-report path, and the news-front-page shapes of the seventh rule under
  "Generic catcher" (Video.js players, podcast cards, player chrome). On the
  pre-fix script 10 of its first 16 checks fail. Chromium
  runs it as a page script; the Firefox content script reads the parent
  through Xray, which only a device confirms. Residuals, not fixed: the
  page-state bridge's emits (`page-state-progressive`/`-hls`) are titled by the
  bridge and never consult declarations, so a recognised player (Video.js,
  Plyr, JW…) on a declaring page wins the URL dedup with the page title; and on
  a multi-video frame the page-level first VideoObject still names every
  undeclared clip.
- **News Over Audio** (`js/parsers/newsoveraudio.js`) is the dedicated case: a
  syndicated "listen to this article" widget embedded across publishers. Its
  audio + title live ONLY in `api.newsoveraudio.com/v1/player/ article`'s
  `application/json` (which the catcher rejects) and play from a cross-origin
  iframe, so neither the wire, the DOM, nor MediaSession-without-a-parser
  fully cover it. `filterResponseData` on that JSON emits the signed
  full-length `.mp3` with `name`/`audioLength`(→ms)/publisher image
  (`skipProbe`); `audios.newsoveraudio.com/*.mp3` is block-listed (cardinal
  rule). The MediaSession path above covers the OTHER embed providers
  generically.
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
  A video podcast's episode also lists a `videoPreview` (an mp4 on
  `smart-preview-clips.spotifycdn.com`, block-listed with the audio host):
  it is captured in place of the audio preview, the same clip with its
  picture. The page-state bridge used to post it with the frame's title.

### Substack — podcast episodes + article voiceovers (`js/parsers/substack.js`)

Substack audio is plain, re-fetchable, DRM-free files: a podcast episode is
`post.podcast_url` = `api.substack.com/api/v1/audio/upload/<uuid>/src`
(**extensionless**, redirects to S3) and an article voiceover is
`post.audio_items[].audio_url` = `substack-video.s3.amazonaws.com/…/tts/<uuid>/
<voice>.mp3`. **Both URLs and every field that names them live ONLY in
`application/json` bodies the generic catcher rejects** (the reader feeds
`/api/v1/reader/feed…`, `/api/v1/posts/by-id/<id>?as-feed-item=1`, and a post
page's SSR `window._preloads = JSON.parse("…")` blob). What the
catcher did: nothing until play, then it captured the played `/src` URL and,
the URL being extensionless (always-enrich), stamped it with the PAGE's og —
on a profile/feed page the profile title, identical for every episode, with
the file named "src". That is the "audios have no title or metadata" report.

- **Three producers, one emit.** (1) `listenerSubstackApi` —
  `collectFilteredResponse` on `*://*.substack.com/api/v1/*` XHRs, gated on
  the body mentioning an audio key before it is parsed, then a bounded SHAPE
  walk (`collectSubstackPosts`: a post = string `title` + one of
  `podcast_url` / `free_podcast_url` / `audio_items[].audio_url`; the feed
  item's `publication.name` is carried DOWN as author fallback; the walk
  stops at a matched post). No wrapper knowledge, so a feed-endpoint rename
  can't lose it (the Instagram lesson). (2) `listenerSubstackDocument` — the
  same walk over `_preloads` extracted from a `*.substack.com` main_frame
  (`extractSubstackPreloads` walks the JS string literal, then parses twice
  — the inner text is JSON-escaped inside the JS string). (3) The **wire
  backbone** `listenerSubstackMedia` on the two media hosts: consults the
  metadata cache (1)/(2) fill (uuid + URL keyed), and on a MISS asks the
  media's own frame through the catcher's `get-page-metadata` responder
  (og:title on a post page IS the episode title). **The backbone is
  load-bearing for a publication on a CUSTOM DOMAIN**: its post page is not a
  `*.substack.com` main_frame, so (1)/(2) never run, and the media hosts are
  block-listed for the catcher — without the backbone the block would
  silently lose those episodes. Same design as Bluesky's wire-master listener.
- **Emit shape**: `type:"media"`, `name` = post title (voiceovers
  `"<title> (voiceover)"`, previews `"<title> (preview)"` when
  `free_podcast_url` is all a post carries), `description` = first
  `publishedBylines[].name` else the publication name, `img` =
  `podcast_episode_image_url` → `cover_image` → `podcast_art_url`, `origin` =
  `canonical_url`, `duration` = `podcast_duration` **seconds → ms**, and
  `skipProbe` when a duration exists (the episode URL is extensionless, so
  `processMediaSkipProbe`'s audio gate falls back to the probe there anyway —
  the flag is honoured outright only for the `.mp3` voiceovers). Per-URL
  30 s emit TTL; the repository dedups by URL beyond that.
- **Cardinal rule**: `parser-blocklist.js` `substack` blocks
  `api.substack.com/api/v1/audio/upload/` and the `…/tts/….mp3` voiceovers.
  The S3 host's VIDEO uploads (`/video_upload/post/<id>/<uuid>/*.mp4`) are
  deliberately NOT blocked — the parser doesn't read them, they stay with the
  catcher. The API listener also early-returns on the upload path: it shares
  the `*.substack.com/api/v1/*` pattern, and a player fetching it as an XHR
  would otherwise be megabytes of audio buffered as "JSON".
- **Ceiling**: a paywalled episode's `podcast_url` is `null` in the feed and
  its voiceover `audio_url` is `null` (`status:"paywalled"`) — nothing to
  emit; a logged-in subscriber's session sees the real URLs. The
  `_preloads.post` shape on a post page is taken from Substack's known SSR
  (the HAR held only the profile page, whose preloads carry no post) — a
  miss there degrades to the wire backbone's page-og capture, never to an
  untitled one. Verified by `node scripts/parsers-replay.mjs` (feed emit
  shape, preloads doc, TTL dedup, backbone cache hit / og miss / no-answer
  generic title, S3 video not captured); on-device unverified at write time.

### Acast — embedded podcast episodes (`js/parsers/acast.js`)

News publishers embed their Acast podcast as a cross-origin iframe,
`embed.acast.com/<showId>/<episodeId>`: an empty React shell (no og:, no
`<audio>` until the app mounts) that fetches the episode from the feeder API —
`window["acast/config"].feederUrl`, today
`phoenix.prod.ateam.acast.cloud/api/v1/shows/<show>/episodes/<ep>?showInfo=true`
(formerly `feeder.acast.com`, same shape), `application/json` the catcher
rejects. That body is the ONLY place the episode's `url`
(`sphinx.acast.com/p/open/s/<show>/e/<ep>/media.mp3`), `title`, `duration`
(SECONDS), `images.{x150…x1000,original}` and nested `show.{title,author}`
live, so nothing was captured pre-play, and a played episode landed untitled
(the iframe's `<audio>`/MediaSession is invisible to the top frame) — "Acast
links not detected for embedded audios". The NOA/Substack shape, three
producers:

- **Feeder API** — `filterResponseData` on `*.acast.cloud/api/*` +
  `feeder.acast.com/api/*`, gated on the body naming audio, then a bounded
  SHAPE walk (episode = titled object whose http `url` is audio by
  `contentType`/extension; a show = titled object with `feedUrl`/`showUrl`/
  `episodes[]`, carried down as context), so a show embed's LIST captures
  every episode. Emit: `type:"media"`, `name` = title, `description` = show
  title (the series line), `img` = the 500px thumbor square (the original
  upload is often 3000px / megabytes), `origin` = the episode's
  `shows.acast.com` `link`, duration → ms + `skipProbe`.
- **Wire backbone** — `listenerAcastMedia` on `sphinx.acast.com`: the path
  carries both ids, so a metadata-cache MISS is resolved by the parser's OWN
  feeder lookup (public, anonymous; `markOwnRequest` keeps the API filter off
  it), falling back to a generic "Acast episode". Load-bearing: sphinx is
  block-listed, so for any player that never fetched the feeder JSON (a
  publisher's own player fed the RSS enclosure, a cached API response) the
  backbone is the only capture. Skips the extension's own requests (the
  catcher's HEAD probe).
- **Show pages** — SPA handler for `shows.acast.com/<show>/episodes/<ep>` and
  `play.acast.com/s/<show>/<ep>` resolves the SLUGS through the feeder (it
  accepts slugs — the embed relies on that for slug embed URLs); one lookup
  per (tab, page), not per `tabs.onUpdated` tick. Their SSR shape is NOT
  HAR-verified, deliberately not read.
- **Dedup**: one claim per (tab, episode id), check-and-claim BEFORE any await,
  30 s TTL — the API body, the media fetch and its Range re-requests all name
  the same episode, and a burst of Range requests must not each start a
  feeder lookup. A second tab is its own capture.
- **Cardinal rule**: `parser-blocklist.js` `acast` blocks
  `sphinx.acast.com/…/media.mp3`. sphinx 302s the player to a stitched
  (dynamic-ad) copy on another host, which needs NO rule: the catcher treats
  a block-listed URL's redirect TARGET as blocked (see the redirect-ownership
  bullet under "Generic catcher — HLS child playlists, redirect hops").
  Verified against the HAR's real API bytes (one entry, title/show/cover/
  689 s); `node scripts/parsers-replay.mjs` pins the API emit, list body,
  backbone hit/miss/feeder-down/HEAD-probe, the show-page lookup; on-device
  unverified at write time (the stitched host and its headers in particular).

### Deezer — full tracks via Blowfish decrypt-on-download (NOT DRM)

**Deezer is decrypt-on-download, the Mega shape — not the Spotify preview
shape.** Deezer is **not DRM** in the EME/Widevine sense: no license server, no
per-session key. A track streams as **Blowfish-CBC ciphertext** (every THIRD
2048-byte "stripe" enciphered, the other two-thirds plaintext — the
`BF_CBC_STRIPE` cipher `get_url` names) whose 16-byte key is **derived offline
and deterministically** from `MD5(SNG_ID)` XOR a hardcoded static secret. So a
captured CDN URL alone is undownloadable (the wire serves ciphertext), but WITH
the `SNG_ID` the bytes decrypt with nothing else — exactly Mega's model (a
capture that carries side-channel key material + a dedicated Java strategy that
resolves the real URL and decrypts). Modelled on `MegaStrategy`/`MegaCrypto`;
pure JDK crypto, no ffmpeg, no native, no GeckoView patch.

- **Capture (`js/parsers/deezer.js`).** `filterResponseData`s the gw-light
  gateway (`www.deezer.com/ajax/gw-light.php` — `deezer.pageTrack` /
  `song.getListData` / `deezer.pageAlbum` / `deezer.pagePlaylist` /
  `search.music`), **shape-walks** it for playable song objects
  (`isSongObject`: `SNG_ID` + `SNG_TITLE` + a `TRACK_TOKEN` — the token is what
  separates a real playable track from a search / related-item shell), and emits
  **one entity per track**. Deliberately does NOT bake the time-limited CDN URL
  or track token into the capture — both expire and the download can be much
  later; the strategy re-mints them at download time from the durable cookie +
  `SNG_ID`, so a deferred download can't go stale.
- **What rides on the emit** (Mega's `?fk=` trick): a **synthetic URL**
  `https://www.deezer.com/track/<SNG_ID>?fmt=<FMT>` (`SNG_ID` = decrypt input +
  stable dedup identity; `fmt` = the best rung the file EXISTS in per
  `FILESIZE_*`), plus the live `www.deezer.com` **session cookie** (arl + sid —
  read with the privileged `browser.cookies.getAll`, which returns the HttpOnly
  `arl` page JS can't). Cookie/`size` are plumbed
  `GeckoInspectEntity`→`JsonHelper`→`GeckoInspectTask`; the cookie reuses the
  existing `BrowserDownloadEntity.cookieHeader` field. `type:"deezer"` →
  `UrlParser` → `UrlType.DEEZER`; `processDeezer` stamps the audio entity with
  **no probe** (the stream is unopenable ciphertext).
- **Download (`DeezerStrategy` + `DeezerCrypto`).** Re-mints from the cookie +
  `SNG_ID`: `getUserData` (→ api token + `license_token`) → `song.getData` (→
  fresh `TRACK_TOKEN`) → `media.deezer.com/v1/get_url` (→ the encrypted CDN URL,
  **stepping the format ladder down** when the account isn't entitled —
  `FILESIZE` says the file exists, not that this account may fetch it), then
  streams the body through `DeezerCrypto.decryptStream` (THE stripe loop; the
  strategy only wires HTTP + progress + cancel into it, so the harness drives
  the real decrypt). `DeezerCrypto` holds the key derivation and is Android-free.
- **Cardinal rule.** The media CDN (`(e-)?cdns?-proxy-*.dzcdn.net`) is
  block-listed (`parser-blocklist.js` `deezer`) — **not for dedup but because a
  bare catcher capture there would save UNDECRYPTABLE ciphertext** (the "block a
  harmful capture" rationale, same as Mega). The 30s preview host
  (`cdns-preview-*.dzcdn.net`) and the images CDN are a DIFFERENT host and left
  alone — logged-out preview capture stays with the generic catcher.
- **Ceiling (stated in the parser header).** Needs a LOGGED-IN session — logged
  out, only the 30s preview exists (the Spotify case). 320/FLAC need
  Premium/HiFi; free = 128k. **The one liability is the static Blowfish
  `SECRET`** in `DeezerCrypto` — stable for years, but if Deezer rotates it every
  download decrypts to garbage; it is the FIRST suspect for a Deezer regression,
  not the transport. Verify with the crypto harness + parser replay above.

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
`GeckoInspectTask` — or on `origin#clipId` when the emit names one clip of
several on the page (the page-state bridge's `clipId`, see the seventh
rule's follow-up).

**Kick's live poster is the channel-id thumbnail**
(`web.kick.com/api/v1/channels/<id>/thumbnail.jpeg`, `kickChannelThumbnail`),
the frame Kick's own channel cards load. The API's `livestream.thumbnail`
(`stream.kick.com/thumbnails/livestream/…/thumb0.jpg`) answers S3
`AccessDenied` to everything: the device, and curl with a bare GET, a
browser User-Agent, a Referer or a full image header set, so
no header can fix it; it is only the fallback for a body with no `id`. The
channel `id`, not `user_id` (that one 404s); pinned by `parsers-replay.mjs`.

**Kick's ad filler is block-listed** (`static.kick.com/ads/`, under the
`kick` key): the player runs a 2-second black clip (`black_2s.mp4`) in its ad
slot, and the catcher captured it under the channel's title, a black "video"
beside the live stream. A harmful capture, the Mega/Deezer rationale, not
dedup.

**Twitch: only a `usher.ttvnw.net` URL is a master, and the tab's NEWEST
cached URL names the channel.** The CDN listener's host pattern also matches
the `video-weaver.*.hls.ttvnw.net` variant playlist the player refreshes
every ~2 s; each refresh used to re-seed a just-completed rendezvous with a
MEDIA playlist as its "master", and a re-trigger within the window completed
it with that URL (Java found no STREAM-INF → a probed single rendition).
`TWITCH_MASTER_RE` gates the listener; the self-built usher fallback on the
metadata side still covers a master the CDN ever moved. `resolveLoginFromTab`
picks the tab's newest-timestamp URL (`tabUrls(tabId)`, the tab's
`TabState.urls`): the cache is insertion-ordered and a re-set keeps an
existing key's place, so after an SPA navigation A→B the first match was A
and B's master was filed under A. The
offline path keeps its 30 s claim (it used to release it and re-POST GQL on
every `onUpdated` tick), and `TWITCH_NON_CHANNEL` is the one list of
non-login paths for both the URL parser and the tab resolver. **Bluesky
caches every video of a response BEFORE emitting any** — the per-video
`await enumerateMasterNative` loop cached video k only after k−1 tab round
trips, and a master the player fetched in that window found no cache entry,
emitted "Bluesky video" and won the origin dedup over the titled emit.
Both pinned in `parsers-replay.mjs`.

### Vimeo — the player's own config, a wire-master backbone, a narrow block rule

`js/parsers/vimeo.js`. The player's config (`window.playerConfig` inline in
the `player.vimeo.com/video/<id>` embed document, or the JSON its
`/video/<id>/config` request answers) names the HLS master, title, owner,
picture and duration. Three producers, one emit, as for Dailymotion:

- **The player's OWN response**, read with `filterResponseText` (blocking
  registration, synchronous return, types main_frame/sub_frame/xhr). It
  used to be fetched a second time by the extension: a duplicate request
  without the page's Referer (a domain-restricted embed refuses it) and
  outside the frame's partitioned cookies. The re-fetch is now only the
  fallback for a body that could not be READ; a body read with no config in
  it (a private video) is not fetched again.
- **Click-to-load placeholders**, fetched anonymously (see that section).
- **The wire master** (`listenerVimeoMaster`, observe-only, on
  `*.vimeocdn.com`, gated to `/v2/playlist/av/primary/…/playlist.m3u8`):
  load-bearing, because the adaptive stream is block-listed. Titled from
  `vimeoMetaCache` (by the clip uuid every CDN URL of one video carries),
  after waiting up to 3 s for a config read still in flight in that tab
  (the player can fetch the master before a streaming config body ends),
  else from the player frame's `get-page-metadata` answer, else "Vimeo
  video". Origin is `vimeo.com/<id>` on every path, so the origin dedup
  collapses them. One decision per (tab, clip).

**The block rule is narrow on purpose**: `parser-blocklist.js` `vimeo`
blocks `vimeocdn.com/…/v2/(playlist|remux|range)/`, the adaptive layout
only the Vimeo player streams. Without it, one embed puts five tiles in the
sheet: the parser's titled master, the catcher's copy of it, and the four
init segments. The PROGRESSIVE host (`vod-progressive-ak.vimeocdn.com`) is
NOT blocked: sites play Vimeo's direct file links in their own `<video>`
(`player.vimeo.com/progressive_redirect/…`), with no Vimeo player and no
config. Instead every config read **claims** its URLs (each CDN's HLS and
DASH masters, the progressive files) and the player frame for its tab
(`claimPlayerMedia`), so the player's own progressive fetch, the content
script's scrape of the inline config, and the page-state bridge reading
`playerConfig` (it found both CDN masters in a Chromium replay) add
nothing. Pinned by `parsers-replay.mjs` (`vimeo:` / `vimeo backbone:`;
mutants: no grace wait, no claims, no block rule, always re-fetch, no
per-clip master claim — each fails). On-device unverified at write time.

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

**An embed FACADE is captured before the tap, through a hand-over from the
capture extension.** A page that shows a YouTube embed as a thumbnail card
and creates the iframe only on the tap (a web component keeping the iframe's
HTML in an attribute, `<lite-youtube>`, a lazy iframe) has no youtube.com
frame for `youtube@`'s content script or webRequest listeners to see, and
googlevideo is parser-owned, so nothing was offered until the tap. The
webrequests content script reports such facades as placeholders (see
"Click-to-load player placeholders"), `js/parsers/youtube-facade.js`
claims the YouTube URLs and sends `runtime.sendMessage("youtube@solarized.dev",
{kind:"youtube-facade", videoId, tabId, pageUrl, embedUrl})`; `background.js`
listens on `runtime.onMessageExternal`, accepts ONLY `downloader@solarized.dev`
as sender, runs `trackVideo` + `processVideo` for that tab exactly as its
embed-frame path does (processVideo fetches the watch page itself, so a
video id and a tab are all it needs), and replies with whether it EMITTED
(`processVideo` now returns its `_exitMode`; `sent-variants` /
`sent-sabr-only` are emits) — a `false` lets the caller release its
placeholder claim and retry on the next report, a repeat within
`trackVideo`'s 60 s window replies `true` without a second run. A later tap
is quiet for that minute and dedups natively after it. Residual: the emit
lands under the tab's page at arrival, seconds after the hand-over; a tab
that moves meanwhile files the video under its next page. Pinned by the
solver smoke's section 6 (sender check, owned repeat, malformed id, a
no-streams run answering false), `parsers-replay.mjs`'s `youtube facade:`
checks and `placeholder-harness.mjs`'s `/youtube` page; `har-replay.mjs
--background` prints the hand-over for a real page. On-device unverified at
write time (the cross-extension message in GeckoView in particular; the
former cross-extension `get-ambient-headers` lookup is the precedent that
it works).

YouTube isn't HLS/DASH — it's Google's SABR (itag formats, a
`serverAbrStreamingUrl` + ustreamer config, and a PoToken minted by BotGuard via
`PoTokenGenerator`). The `youtube@` extension emits adaptive video+audio
itag-pair variants + the shared SABR data; routed via `UrlType.SABR`, downloaded
by `SabrStrategy`. `VariantProcessor` skips ffprobe for SABR variants (empty
media URLs) and trusts the JS codec/resolution/duration.

**Attestation (STREAM_PROTECTION_STATUS 3) recovers by RE-MINTING, and a
server-refused download ERRORS — it never finalizes.** The server can demand
attestation MID-STREAM (seen in the wild ~60 s into a download whose
start-time token it had accepted). `SabrDownloader` reacts by minting a fresh
per-video PO token through `PoTokenRefresher` (wired by `SabrStrategy` to
`mintFreshPoToken`, set unconditionally so a download whose initial mint
failed gets its chance here too) and resuming from position; after
`MAX_ATTESTATION_REFRESHES` (2) failed re-mints the token is being REJECTED,
not missing, and it throws `SabrException`. **That budget is CONSECUTIVE —
it resets on forward progress (`playerTimeMs > prevPlayerTime`).** It was a
lifetime cap, so a download challenged a third time was failed even though
both earlier recoveries had succeeded. Demands track MEDIA POSITION, not
elapsed time — the first lands around a minute of media, which on a fast
connection is a second or two into the transfer — so a long video
legitimately draws several within one short download (a 6 h video transfers
in minutes; do not reason about this in wall-clock). The reset must use the
SAME progress test the stall detector uses, not merely "segments arrived".

**Three separate bounds stop a status-3 → re-mint → status-3 loop, and all
three are load-bearing.** (1) The consecutive cap (2): the same token being
refused twice in a row with no progress between ends the download — more
minting cannot change that answer. (2) `attestationBudgetTotal()`, a
never-reset ceiling, because the consecutive cap resets on progress and a
server answering with segments AND status=3 together would otherwise
re-mint every pass forever; it is **scaled** (one per five minutes of
media, floor eight) rather than fixed, since a flat ceiling fails exactly
the long videos it would be meant to protect — a previous version used a
flat 10 and was wrong for that reason. (3) A mint that returns NOTHING
fails the download immediately instead of spending the second attempt: the
page was unreachable or the attestation timed out, which is a CONNECTION
failure, not a refused token. It used to report "PO token rejected" for
that, sending the next debugging round after YouTube instead of after the
network. Re-minting seconds later over the same broken network only repeats
it; the honest end state is an ERROR row the user retries with signal,
which restarts cleanly. **All of it is covered by
`sh scripts/sabr-harness/run.sh`**, which drives the REAL `SabrDownloader`
against a scripted SABR server (18 assertions, seconds, JDK only — the
`sabr` package touches just five external types, so `okhttp3.*` +
`android.util.Log`/`Base64` + `BuildConfig` are stubbed and OkHttp answers
from a queue the test writes). `SabrTestServer` builds genuine UMP frames:
the varint encoder mirrors the scheme documented on
`UmpReader.readUmpVarint` and assertion 0 round-trips it through the real
reader before anything else runs, while message bodies use the app's own
`ProtobufWire.Writer` — so a CODEC bug would cancel out, which is accepted
because what is under test is the downloader's state machine. Written
against the pre-fix downloader first, where exactly three assertions fail
(the network-vs-rejected message, a download killed at its third demand
after two SUCCESSFUL recoveries, and the old 2-mint bound where the scaled
budget allows 72). One case throwing is reported as a failed case, never an
aborted suite — the first version aborted at case 5 and silently hid every
case after it. Two shipped bugs guard this shape
— don't reintroduce either: the downloader used to log-and-RETURN the partial
`Result` on attestation, and `SabrStrategy`'s catch used to SALVAGE-mux
whatever raw segments existed — together they finalized a 62-second
truncation of a 100-minute video as a FINISHED entry, a broken-looking file
with no honest error anywhere (and it misdirected a whole debugging round
toward codecs and filenames). The deliberate partial-mux path is the USER's
stop/finish (`stopped`), never a `SabrException`.

**A SABR download is ONE file: the inline fMP4 muxer (`sabr/Fmp4Muxer`) is
the only path, and nothing else is written beside it (2026-10).**
`SabrDownloader.download()` hands every segment to the muxer, which
interleaves the two tracks into the output as they arrive (one `moov` with
both `trak`s renumbered 1/2, a `sidx` slot after it, each fragment's `tfhd`
rewritten, `styp` and per-segment `sidx` dropped, then at `close()` the
whole-file `sidx` and the real durations patched in). Until this switch the
downloader ALSO wrote the raw per-track streams to a `.sabr_<ts>/` directory
beside the output as the FFmpeg fallback's input: every download was written
twice, needed twice its size free while it ran, and a process death left the
folder behind in `Download/Firedown` and in the vault. Rules, each pinned by
`sh scripts/sabr-harness/run.sh` (the real muxer over real ISO-BMFF
fixtures, `Fmp4Fixtures`, built from the 14496-12 layouts and parsed back
box by box; 65 checks; five planted bugs each fail it — a fixed 128 KB slot,
no track-ID rewrite, durations left unpatched, pre-init fragments dropped,
the 16-bit `reference_count` unchecked):
- **The muxer is pure Java over bytes; no codec and no device API is
  involved.** It copies `stsd` verbatim and never decodes. What fails it,
  and only this: a non-ISO-BMFF init (WebM — unreachable, the youtube
  extension picks `avc1`/`av01` + `mp4a` only), encryption boxes (DRM,
  undownloadable either way), two `trak`s in one init, a missing `trex`, a
  64-bit box size, more than 16 media fragments before both inits, a torn
  fragment, an IO error, or more than 65 535 video fragments (a `sidx`
  `reference_count` is 16-bit; it used to be truncated silently). The
  probe (`probePlayableDuration`) is the bundled FFmpeg at demux level, so
  a codec the phone cannot decode passes it exactly as before, and the
  codec bytes are identical whichever muxer wrote them.
- **The `sidx` slot is sized from the clip's declared length**
  (`Fmp4Muxer.reserveFor`: one 12-byte reference per SECOND of media, a
  128 KB floor, a 16 MB cap; ~518 KB on a 12 h upload). The fixed 128 KB
  slot held ~10 900 references, which a long clip with short fragments
  overflowed into the FFmpeg fallback; there is no fallback to overflow
  into now, so the slot cannot run out on a real clip (YouTube cuts video
  fragments at ~5 s).
- **A failed probe REMUXES THE INLINE FILE ITSELF** (`SabrStrategy.
  remuxInline`): when the muxer gave up after its header (`hasHeader()` —
  the file is ISO-BMFF up to that point) or the probe refused the file,
  FFmpeg stream-copies it into a classic faststart MP4 as
  `.<name>.sabr-remux.mp4` beside it (same directory on purpose: the final
  move is a rename, never a copy across the public-folder/cache mounts),
  the copy is probed the same way, and it replaces the original. **The
  temp name MUST end in `.mp4`**: FFmpeg picks the output container from
  the file's extension, so the first name (`.sabr-remux`, no extension)
  gave it no muxer to pick and every remux failed with `-7` (could not
  create the output context) before a byte was written — the fallback
  shipped on the branch having never worked on a device, which the device
  test below found on its first run. Nothing
  else is left to try: an unusable result DELETES the file and errors —
  for a user finish, whose task is already sealed FINISHED and whose
  `onError` is a no-op, the missing file is what makes `onRunComplete`
  demote the row to ERROR/`FILE_NOT_FOUND`, retryable, instead of a
  FINISHED row over a broken file (the shape of the salvage bug above).
  `StoragePaths.cleanupSabrLeftovers` (boot, both folders) sweeps a remux
  temp a process death left, and still the `.sabr_*` dirs of older builds.
- **What the user gives up is container shape, nothing else.** The inline
  output is a fragmented MP4 with a `sidx`; the FFmpeg path wrote a classic
  faststart MP4 with a sample table. ExoPlayer, Android's MediaPlayer and
  MediaMetadataRetriever, Google Photos, VLC and mpv play fMP4; some older
  smart-TV / USB-stick players and the odd editor want an `stbl`. The
  FFmpeg path's AAC Main→LC relabel (`downloader.c`) does not run inline,
  which is moot here: YouTube ships `mp4a.40.2`/`40.5`, never Main.
- Progress bytes are the output file's length; `SabrDownloader.Result`
  carries segment and byte counts per track, no files. **The device half
  is `SabrInlineMuxDeviceTest`** (androidTest, `sabr/`): real H.264+AAC
  fragments (`assets/sabr/`, made with ffmpeg; the command is in the
  test's javadoc) through the real muxer, then the platform extractor,
  MediaMetadataRetriever, the app's FFmpeg probe and the app's media3
  player read the file — seekable (the player says so only when it found
  the `sidx`), a seek that lands, playback to the end — for a whole
  write, for the user-Finish shape (three fragments then close), and
  through the FFmpeg stream-copy the remux fallback runs. The harness
  checks the boxes; this checks the readers. **Each fragment is handed its
  OWN duration, read from its `trun`** (as the stream's media header names
  it): the muxer sums what it is handed into the patched track durations,
  and a flat second per fragment put nine seconds on an eight-second audio
  track (the AAC fragments run 975–1045 ms, the ninth a 12 ms sliver) —
  the second failure of the first device run (SM-A426B, Android 12), where
  the partial-write case passed. With both fixes all three cases pass on
  that device, the remux fallback's first green anywhere.

**The SABR / `videoplayback` token is bound to the VISITOR DATA; the
subtitles token to the VIDEO ID — two different tokens, never one shared
cache entry.** A PO token is minted over one identifier (BotGuard's content
binding) and the server checks it against the request it rides on: the
Google Video Server checks the `videoplayback` / SABR `poToken` against the
session's visitor (yt-dlp's GVS context — the logged-out WEB client, which
`SabrDownloader` forces, binds to `visitorData`), while the player request
and the `timedtext` `pot=` param check the VIDEO id (yt-dlp's PLAYER/SUBS
contexts; `get_webpo_content_binding` in `youtube/pot/utils.py` is the
reference). `content.js` used to mint EVERY token over `vid || vd`, so the
stream token was video-bound — and the failure of a wrong binding is
SILENT: the server accepts it unchecked most of the time and refuses it
only when it spot-checks the session. On-device that was
`STREAM_PROTECTION_STATUS 2` ("pending" — never validated) from the first
data response, status 3 at ~60 s, two genuinely fresh re-mints (~600 ms
each, full attestation) refused at once because they carried the same
wrong binding, then a restart of the app that worked with no protection
status at all. Recognise the shape: **an intermittent status-3 death where
FRESH re-mints are refused immediately is a BINDING problem, not a
token-TTL or network one** (a TTL problem tracks session age; a network
problem mints nothing). Now `PoTokenGenerator.Binding` names the binding
per caller — `generateForStream`/`generateFreshForStream`/`invalidateStream`
(`SabrStrategy`, `binding:"visitor"` on the mint message, cache key
`visitor:<visitorData>`, shared across every video of the session because
the server binds it to the visitor) versus `generate`/`generateFresh`/
`invalidate` (`TimedTextStrategy`, `binding:"video"`, cache key = videoId)
— and `content.js` picks the identifier from it (a message with no binding
is treated as video-bound, so the pre-field pairing still agrees on the
subtitles token). Don't collapse the two back into one videoId-keyed cache
"so subtitles reuse the stream token" — they were never the same token.
`invalidate*` now also marks the key so the NEXT mint runs `forceFresh`
(the page re-attests instead of re-minting through the minter whose output
was just refused), so a user's retry of an ERROR row never starts from the
refused token. Pinned by cases 8b–8n of `sh scripts/potoken-harness/run.sh`.

**The re-mint MUST bypass every token cache — a cache-first "refresh" is a
no-op that only looks like recovery.** The refresher goes through
`PoTokenGenerator.generateFresh`, NOT `generate`, because the token is cached
at TWO layers and on this path both hold exactly the token the server just
refused: (1) `PoTokenGenerator.tokenCache` (videoId → token, session-lived),
and (2) the page's cached `WebPoMinter` in `content.js` (`cm`, ~5 h) — bound
to ONE integrity token and minting over the identifier, so re-minting through
it reproduces the same refused bytes. **Clearing only (1) would still recover
nothing**, which is why `generateFresh` evicts its entry AND sets
`forceFresh` on the port `mint` message so `content.js` drops `cm` and re-runs
the full BotGuard attestation (att/get → interpreter VM → snapshot →
GenerateIT → new minter). Shipped bug: both "fresh PO token" attempts logged
`generate: cache hit ... (0ms)`, so a 100-minute download died ~4 s after the
demand having never once asked the page to mint anything — the logs read like
YouTube hard-rejecting us when the recovery had simply never run. Cost is a
real ~3 s attestation instead of ~100 ms, correct when the alternative is
failing the download. **The eviction happens BEFORE the mint** so a failed
refresh can't leave the rejected token behind as a future "hit", and
`SabrStrategy`'s `SabrException` catch calls `PoTokenGenerator.invalidate`
when `SabrDownloader.isAttestationRejected()` — a flag that is TRUE on both
attestation-wall endings, refused AND unmintable-over-a-dead-network, since
the cached token drew the status-3 either way — otherwise the stale token
survives ~5 h in the cache and the user's obvious next move (retry the ERROR
row) starts from the very token that just failed. Don't "simplify" the
refresher back to the plain cache-first mint, and don't add a cache layer
under it without a force-fresh path through it.

**The page must honour the integrity token's OWN lifetime, and every
PoTokenGenerator change is verified by `sh scripts/potoken-harness/run.sh`.**
`GenerateIT` answers `[integrityToken, estimatedTtlSecs, …]` and
`content.js` used only `[0]`, trusting the derived `WebPoMinter` for a
hardcoded 5 h. Every token a minter produces is bound to THAT integrity
token, so once the server expires it every later mint is refused — a
well-formed 120-char token the server rejects, intermittently, depending
only on how long the hidden session had been up. That is the shape to
recognise: **attestation failures that come and go with session age are a
TTL problem, not a token-content problem.** `cml` is now bounded by
`ij[1]` minus a 5-minute margin (the 5 h survives only as the
no-value-from-server fallback), the attestation is **single-flight**
(`cmp`) so a video and its subtitles don't each run their own att/get →
VM-fetch → GenerateIT, and `tokenCache` entries expire after 10 min.
**Note what is and isn't a prediction here.** A PO token's own lifetime
is NOT predictable — yt-dlp's guide puts observed validity anywhere from
~12 h to several months, and their issue asking for a figure is still
unanswered — so the only authority on whether a token works is the
server refusing it, which is what the status-3 recovery reacts to. The
integrity token is the opposite: the server STATES its TTL in
`GenerateIT`'s reply, and minting from one it has already retired
produces a token guaranteed to be refused. Honour `ij[1]`; never invent
a PO-token expiry. The 10-min `tokenCache` window is scope, not
prophecy: minting is DOWNLOAD-time only (`SabrStrategy` /
`TimedTextStrategy` are the sole callers — nothing mints at capture), so
it covers a download and its timedtext sibling seconds apart and
declines to reuse across unrelated later downloads, where a warm re-mint
is ~100 ms and no network. Four recovery paths were fixed with it, each invisible to the
class's own liveness test (`session != null && port != null`): a STALE
port's `onDisconnect` used to tear down the LIVE session it had just been
replaced on; a mint the page never answered left the session wedged for
its whole 5 h TTL (so `mint` now distinguishes no-answer → recycle from
an error REPLY → keep, since the page is alive and recycling would burn
~3 s to land in the same place — and the recycle is GUARDED on the port
the mint ran on still being current, because between the timeout and the
lock a sibling can have rebuilt a healthy session that an unguarded close
destroyed); `INIT_TIMEOUT_MS` was 3 s AND destroyed
a session that was merely still loading, so a slow connection restarted
from zero forever and never converged (now 10 s, with
`SESSION_INIT_GRACE_MS` bounding how long a not-yet-ready session is left
alone); and a `forceFresh` mint ran under the 15 s ceiling though the
page allows its snapshot step alone 10 s, so the recovery timed out
exactly on the slow devices needing it (now `MINT_FRESH_TIMEOUT_MS`, with
`content.js`'s own ceiling kept ABOVE it so Java gives up first — a
page-side timeout arrives as an error reply, which by the rule above must
NOT recycle). The harness compiles the REAL class against stubs and drives
all of it (36 assertions, ~80 s — five cases wait out real timeouts because
the constants' relationship to each other is what is under test); it was
written by running it against the pre-fix class first, where exactly the
8 assertions covering these defects fail. **Why none of this was caught
earlier: the class had no test at all, and every one of these bugs lives
in a RECOVERY path** — code that only runs once something else has already
gone wrong, which on-device looks like YouTube being flaky rather than a
bug. Reading the happy path, and reading a log of the happy path, can't
find them. Run the harness.

**Four resource leaks came out of the same audit — `LeakHarness` (the
second suite in `run.sh`) exists to keep them out.** An open
`GeckoSession` holds a content process, so the rule is that every session
opened must end up closed, and a session nothing references can never be
closed by anyone: (1) an exception ANYWHERE after `open()` — `setActive`,
`loadUri`, the registrar — left an opened session that was never stored,
so no later `closeSessionLocked` could reach it; it is now closed on the
failure path, which is why `s`/`opened` live outside the runnable's try.
(2) The create runnable is queued behind whatever else the main thread is
doing, so `ensureReady` can time out and give up (or `shutdown` can run,
or a later caller can start its own session) BEFORE it lands — it then
created a session nothing held; it now adopts only while
`readyFuture == future` and closes the orphan otherwise, and that
identity check is the whole mechanism, so don't replace it with a null
check. (3) `postMessage` throwing (Gecko's answer for a dead port)
escaped `mint()` past its `finally`, stranding the just-registered
`pending` entry and a future nobody would ever complete, and propagating
the throw into the download; it is caught, swept, and treated as a dead
session. (4) `tokenCache`'s TTL is enforced lazily on a read of the SAME
videoId, so a token for a video never asked about again was never
examined and never removed — browsing minted one per captured video and
the map only emptied when the session recycled hours later. Bounded now
by `pruneTokenCacheLocked` (`MAX_CACHED_TOKENS`) on insert. Known
residual, bounded and deliberately not fixed: each page-side
re-attestation orphans a BotGuard VM (we hold only the minter, not the
`BotGuardClient`, and shutting the VM down would break a minter still
closing over it) — at most a few per 5 h session, which the session
recycle then clears.

**The solvers EXECUTE the YouTube player inside the youtube@ background
page, so the player gets no live scheduler (`runPlayer` behind
`getFromPrepared`, the one `Function("_result", …)` site — the n-param solve
and both cipher solves go through it).** The solver's SETUP_CODE stubs
`setTimeout`/`setInterval`/`requestAnimationFrame` only when they are
`undefined`, which holds in the runtimes it was written for and never in a
background page, so whatever the player's init scheduled ran LIVE there after
the solve: on-device (2026-10) a stream of `SyntaxError: expected expression,
got end of script … background.js line 267 > Function` every ~4.6 ms (the
nested-timer clamp). **Read that signature correctly: it is not the solve
failing to compile.** Every solver caller sits inside try/catch and this
Gecko reports no caught exception (`dom.report_all_js_exceptions` is unset),
so an uncaught error at timer cadence attributed to the compiled function is
one of its CALLBACKS. A try/catch around `Function()` would have changed
nothing; it was proposed and dropped for exactly that reason. Now the four
schedulers are no-ops for the synchronous run (globals patched, restored in
`finally`, which also undoes a scheduler the player installs on
`globalThis`) and the same no-ops are the compiled function's parameters, so
a bare `setTimeout(…)` — a captured reference, a microtask after the run, a
later `n()`/`sig()` call — stays inert for the player's lifetime. The solve
sees nothing different (a deferred callback never ran before the probe, which
executes in the same synchronous call). Ids start at 2^30 so a later real
`clearTimeout(id)` cannot cancel one of the page's own timers, and a debug
build logs what was blocked (`[Solver] quarantined N scheduler call(s)`).
Residual: a chain the player starts after the run through a PROPERTY access
(`window.setTimeout`) still gets the real scheduler. **Verify with
`node scripts/youtube-solver-smoke.mjs`** — the real `background.js` in a vm,
scripted players starting chains through every door; on the pre-fix file it
counts 128 `SyntaxError`s in 80 ms and fails 8 of 13 checks. It needs no
network, so it pins the CONTAINMENT, not whether the solver still finds `n`
on today's `base.js`; that is a device question
(`adb logcat -s GeckoConsole:* | grep -E 'Solver|Cipher'`).

**Video codec pick prefers H264 over AV1 at equal heights** (both
`buildAdaptiveVariants` and `buildSabrOnlyVariants` sort with an avc-first
tie-break before bitrate). AV1's higher-bitrate rendition used to win every
rung, and AV1 files can't produce thumbnails on this app: Samsung's
MediaMetadataRetriever fails AV1 frame extraction (a42xq confirmed), and the
FFmpeg fallback hits the native `av1` decoder, a hwaccel-only stub that
returns `AVERROR(ENOSYS)` (-38) under the build's `--disable-hwaccels` — so
every AV1 download wore a permanent mime glyph and "Regenerate thumbnail"
visibly did nothing. AV1 still fills the >1080p rungs (1440p/4K), where
YouTube serves no H264. **The dav1d decode fix HAS SHIPPED**: release builds
are made with `--enable-libdav1d` (verified by `strings` on v1.1.91's
`libavcodec.so` — `libavcodec/libdav1d.c` is present), so the FFmpeg fallback
now software-decodes AV1 (thumbnails, frame grabs, and AVIF images — the
same decoder). MMR still fails AV1 on the affected Samsungs, but the
fallback catches it. The H264-over-AV1 tie-break was kept anyway (H264
hardware-decodes everywhere; a SW dav1d thumbnail is slower than an MMR
one) — revisiting it is a maintainer call, no longer a necessity.

**Multi-audio-track videos (auto-dubbing): the ORIGINAL-language track is the
default, never a dub.** A multi-track video repeats the SAME audio itags once
per track (one itag-140 per language, near-identical bitrates), YouTube lists
the viewer-locale dub first and marks IT `audioIsDefault` — so the old plain
best-bitrate pick downloaded the dub. `selectDefaultAudio` (`background.js`,
used by BOTH `buildAdaptiveVariants` and `buildSabrOnlyVariants`) scores
tracks: xtags `acont=original` (xtags in the player JSON is base64url
protobuf — decode before searching; locale-independent, the authoritative
signal) > displayName containing "original" (localized but latin locales keep
the word) > `audioIsDefault` (marker-less pre-autodub sets, where the default
IS the original); dubbed/descriptive markers push a track down (descriptive =
audio-description, never a wanted default) and a small DRC penalty prefers the
untouched rendition WITHIN a track. Single-track videos carry no `audioTrack`
field and reduce to the old best-bitrate pick. The chosen track's id rides on
EVERY variant as `audioTrackId`, and `JsonHelper.parseVariants` prefers that
variant-level field — its itag-keyed SABR format map is LAST-WINS across the
per-track duplicates of one itag, so the map's `audioTrackId` (and any
fallback field read from it) is an arbitrary track on multi-track videos; the
variant-level fields are authoritative (`SabrStrategy` sends the track id as
AbrState field 69 plus the track's own audio FormatId, which is what makes
the SABR server serve that track). The muxed itag-18 fallback can't choose (a
single pre-muxed stream with YouTube's default audio) — accepted. **Track
choice is its own SECTION in the quality sheet, never resolution×track
variant rows** (dozens of dubs on big channels would explode the sheet): the
emit carries `audioTracks` — one entry per distinct track (original first +
flagged, each entry the track's best non-DRC AAC rendition:
id/name/itag/lastModified/xtags/n-param-transformed url; empty for
single-track videos) — which rides
`JsonHelper.parseAudioTracks` → `GeckoInspectEntity` → `GeckoInspectTask` →
`BrowserDownloadEntity.mAudioTracks` (parcelled) into an "Audio track"
radio section in `BrowserOptionVariantsFragment` (between Quality and
Captions, `BrowserOptionAudioTrackAdapter` — self-contained clicks, NOT the
quality adapter's fragment round-trip: both lists reuse the same row layout
id, so a shared listener couldn't tell them apart). Selecting a non-default
track overlays the built request via `toBuilder()`: `audioUrl` (the
FFmpegMergeStrategy path) + the SABR audio FormatId/track id (SabrStrategy).
The default (position 0 = original) applies no override, so the no-pick path
is byte-identical to the pre-picker behavior. The picker strings
(`audio_track_*`) are deliberately base-locale-only, matching the whole
picker-sheet family (`quality_section_title`/`captions_section_title` are
untranslated too — translating only the new section would mix languages in
one sheet). Captions use the
separate `timedtext` path: `emitYouTubeCaptions` emits every track (the
video's title, the language, `-auto` for ASR). The PLAYER's own caption
fetch (`youtube.com/api/timedtext`, an `application/json` body) is
parser-block-listed under the `youtube` key: the catcher used to classify it
as a `timedtext` capture of its own (a special case in `classifyXhr`, now
gone — JSON is never a capture), and its URL never equals the parser's (the
player adds its own `pot`/client params), so every captioned video showed a
bare "timedtext" subtitle beside the parser's track in the Captured sheet.
Pinned by the smoke's `youtube:` and `decide:` checks, which fail on the old
catcher. **YouTube LIVE is the exception: HLS, not SABR** —
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

### Generic catcher — HLS child playlists, redirect hops, sprite VTTs

Three catcher rules for a news page of JW Player embeds, where each clip
showed four times, each wearing a "CC 1" badge and the mime glyph. All three
live in `requests.js` and are pinned end-to-end by
`scripts/webrequests-smoke.mjs` (the `e2e:` section drives the REAL recorded
listeners with a stubbed `filterResponseData`).

- **One response, one emit, and the blocking listeners are type-scoped** —
  see rules (3)–(6) under "Background-page state must be BOUNDED" (the
  `hub:` section of the smoke pins them: a response seen by both response
  listeners emits once, a closed tab's capture is dropped, a private tab's
  cached Cookie never reaches a regular capture).
- **An HLS MEDIA playlist listed by a master this tab already read is NOT a
  capture.** The master (`cdn.jwplayer.com/manifests/<id>.m3u8`) is captured
  as `type:media` and Java enumerates every rendition from it; the rendition
  playlists the player then fetches are the same video again — once per
  quality it switched to, and once more per re-fetch on CDNs that sign child
  URLs per master fetch (JW's
  `videos-cloudfront-usp.jwpsrv.com/<expiry>_<sig>/… =<bitrate>.m3u8` rotates
  the prefix; the master URL is stable and dedups by URL).
  `armHlsMasterReader` (in the blocking `onHeadersReceived` sniff listener,
  armed for every `.m3u8`/mpegurl response, write-through, 1 MB cap) parses
  `#EXT-X-STREAM-INF` URIs + `EXT-X-MEDIA`/`I-FRAME` `URI=` into the tab's
  `TabState.hlsChildren` (per TAB, bounded, gone with the tab), and
  `processResponse` drops a media capture whose URL is in it. Per-tab on
  purpose: a tab whose master came from cache (unreadable) never read the
  body, so its children still capture as before — degrade to today's
  duplicate, never to a lost video. **The same reader takes the init segment
  every playlist names in `#EXT-X-MAP:URI=`** (`parseHlsInitSegments`): on an
  fMP4 stream it is a few hundred bytes of moov served as `video/mp4` under a
  name no URL rule can know (Vimeo's `segment.mp4?sid=0`), and the native
  probe turned each one into a blank VIDEO or AUDIO tile (four on one Vimeo
  embed). Pinned by the smoke's `hls-init:` and `decide:` checks.
- **A REDIRECT hop (301/302/303/307/308) is never emitted.** `webRequest`
  fires the response events again, same requestId, for the target; the hop
  has no body. It shipped as a phantom subtitle: JW's thumbnail-strip track
  `cdn.jwplayer.com/strips/<id>-120.vtt` answers 301 → `assets-jpcust.jwpsrv.com`,
  the target was unreachable on-device, and the 301 URL alone landed as a
  SUBTITLE capture — the caption badge is keyed by ORIGIN
  (`CaptureSections.captionCounts`), so every clip in that iframe
  read "CC 1". Only the redirect statuses are gated: Gecko folds a 304 into
  the cached 200, and a 4xx media answer can still be a real stream behind a
  Range-only endpoint (a streaming host that 404s a bare GET).
- **A parser-owned URL's redirect TARGET is parser-owned too**
  (`parserOwnedRequests` in `requests.js`). The block-list matches a
  response's URL, but a media endpoint that redirects answers twice under ONE
  requestId — the block-listed hop, then the target on a host no rule names —
  and the catcher captured that target as a bare duplicate of the parser's
  titled entry (Acast: `sphinx.acast.com/…/media.mp3` → stitched dynamic-ad
  copy; Substack's extensionless `/src` → S3 is the same shape). So
  `validateAndClassify` remembers the requestId of a block-listed REDIRECT
  HOP and rejects every later response under it
  (`reject:parser-block-redirect`); the manifest-sniff gate consults it too.
  Content-script reports carry synthetic `cs-…` ids, so they can't collide.
  **Lifetime has three bounds, keep all three:** only a 3xx hop is marked (a
  block-listed 200 — nearly every hit: Twitter segments, Instagram mp4s — has
  no later response under its id; the first version marked those too, filled
  the map to its 512 cap with entries nothing would look up, and that FIFO
  churn could evict a live chain before its target arrived);
  `onCompleted`/`onErrorOccurred` forget the id at the chain's end; and
  `cleanupStaleEntries` sweeps past the 2-min TTL (a lookup-time TTL alone
  never removes an id nobody asks about again — the `tokenCache` lesson).
  Prefer this over block-listing a redirect target's host — it needs no guess
  about which host the hop lands on. Related: the DOM path no longer sends
  its header-recovery HEAD probe for a block-listed URL (a capture that can
  never happen, and a request a podcast host may count). Pinned by the smoke's
  `e2e:` redirect-target checks (the target under the hop's requestId is
  dropped; the same URL under an unrelated request still captures; completion
  and abort forget the id; a block-listed 200 is never remembered).
- **A `.vtt` is emitted only once its BODY says captions.** Players ship
  seek-bar thumbnail sprites as WebVTT (JW "strips", Video.js/Plyr
  storyboards — every cue is `sheet.jpg#xywh=…`). A blocking
  `onBeforeRequest` arm reads the body (write-through, 64 KB cap) and
  `processResponse` awaits its verdict (`vttVerdictFor`, 5 s ceiling):
  `sprite` drops, `text`/`unknown` (unreadable — cached/error) emit as
  before. The verdict is keyed by requestId and swept; nothing else reads
  `.vtt` bodies.

**The fourth rule, from the logcat of the same page: a PLAYER FRAME is ONE
clip, and the bridge CLAIMS it.** With the three rules above each JW clip
still landed three times: the bridge's HLS master (the player's resolved
source) plus the embed document's `og:video` and `twitter:player:stream`
mp4s, which the content script's passive scrape (Tier A declared media —
right on an article page, a duplicate inside a player frame) reported as two
more videos; the repository dedups by URL only. And the bridge ITSELF used
to emit a mixed group twice — a progressive entity AND a master entity for
the same clip (the page's own Brightcove clip: 4 pmp4 renditions + hls/dash
masters). Two halves:
- **Bridge (`emitOneGroup`): one entity per group — the HLS master wins.**
  A group holding both a master and progressive renditions posts the master
  only (Java enumerates its full ladder; an HLS download remuxes to the same
  mp4; a lone mp4 beside a master is usually the LOW fallback, so preferring
  it could lose rungs) and lists every folded URL as `siblings` on the
  `page-state-hls` payload (`page-state-progressive` carries them too).
  ONE master per group too: an `.m3u8` before a DASH `.mpd`, then https
  (Brightcove lists http/https × HLS/DASH, and the `.mpd` it sometimes lists
  first cannot be enumerated natively).
  `readPlayerItem` reads jw8's `allSources` beside `sources` — jw8 filters
  `sources` down to the provider it picked (HLS-only on a JW Platform embed),
  the mp4 ladder + m4a live only under `allSources` — so the fold has the
  whole set to claim. Progressive-only and HLS-only groups are unchanged.
- **Catcher (`requests.js` `claimPlayerMedia`, called by every page-state
  handler)**: two claims keyed on the emitting frame's document URL
  (`sender.url` = the bridge's `origin`), TTL-bounded. (1) Every URL of the
  group — a wire fetch of one on play, or a scrape of one off `og:video`, is
  rejected (`reject:player-claimed-url`), per tab like the HLS children (both
  fields of the tab's `TabState`).
  (2) The FRAME — a content-script VIDEO report from a claimed SUB-frame is
  the frame's clip under a URL the bridge never saw (`twitter:player:stream`'s
  `<id>-640.mp4` alias is in no JW source list) and is rejected
  (`reject:player-claimed-frame`). Top-frame reports are exempt (an article
  holds many clips, a player embed one) and standalone audio is exempt.
  **Order is not guaranteed** (scrape at DOMContentLoaded vs player setup on
  load + script + playlist; on-device the bridge won by 200 ms only because
  the scrape's HEAD probe was slow), so a sub-frame video report from a
  frame with no claim yet WAITS up to `playerClaimGraceMs` (4 s — spans the
  bridge's last `t4000` pass) for one: resolved the instant a claim lands,
  forwarded unchanged when none comes (`hold:player-claim` in the log). The
  sanctioned timer shape — a bounded wait for an external signal with a
  correct fallback; the only cost is a ≤4 s later capture of a sub-frame
  video no player claims. Pinned by the smoke's `claim:` section in both
  arrival orders, the wire-on-play case, the other-tab case, the no-claim
  forward and the top-frame exemption; teeth verified by mutation (a no-op
  `claimPlayerMedia` fails four).

**The fifth rule, the TITLE of a filename-titled embed comes from the HOST
PAGE — and only then.** A JW Platform embed titles its document with the
upload filename (`<title>`, `og:title` and `playback.json` `title` are all
`6aaaf5204d3859992c30a380.mp4`), so every rule above still produced eight
rows named by hash; the clip's real description is the live-blog paragraph
right before the `<figure>` holding the iframe — in the HOST page, which
the cross-origin frame's bridge and responder cannot read. So the TOP
frame's content script reports every http(s) iframe's caption
(`frame-captions`: the iframe's own title/aria-label/data-title, else the
enclosing `<figcaption>`, else the nearest preceding `p`/heading within a
few ancestors; debounced, re-run when the observer sees an iframe added or
re-`src`'d — the cookie wall unwraps these late), `requests.js` keeps them
in the tab's `TabState.frameCaptions` (per iframe src; a page-controlled
batch is capped per report), and `withFrameCaption` is the ONE
consumer: it replaces a title only when the frame's own title is
FILENAME-LIKE (`isFilenameLikeTitle` — a media extension, or a spaceless
≥12-char token with ≥3 digits) and a caption exists for that frame. Called
from the two page-state handlers and the catcher's metadata step for
SUB-frame captures only. Deliberately narrow, per the maintainer's "don't
break anything, just this case": a real embed title is never replaced, a
top-frame capture never consults it, and an uncaptioned frame keeps its
filename. Pinned by the smoke's `caption:` section (hash replaced, real
title kept, uncaptioned kept, per-tab). Known residual: a caption that
lands AFTER the frame's emit (an iframe present in the initial HTML whose
player set up before the top frame's first report) keeps the filename —
not seen on-device, where the cookie wall inserts the iframes late.

**The sixth rule, a clip on a MULTI-video page never takes the page's og:title
/ og:image.** The shape: a grid of clips whose per-clip title, poster and URLs
live only in a JSON feed the catcher rejects (and a query cache the bridge
can't reach), each card in the DOM an `aria-hidden`, poster-less `<video>`
beside a poster `<img>` (removed once the clip plays) and a `<button
aria-label="View Countryside Duo">` covering the card. Every clip read the
site's name with its banner as thumbnail. Fixed generically, no parser: when
the responding frame holds more than one `<video>` and the captured URL is
bound to one, the content-script responder reports `cardTitle`
(`cardTitleFor`: the element's own aria-label/title, else a wrapping labelled
link, else the longest labelled link/button/figcaption in its card — at most
four ancestors up, stopping at the first ancestor holding a second `<video>`,
control labels like "Play video"/"More options" skipped) and `posterlessClip`
(no poster of its own). `pickCaptureMeta` (`requests.js`, exported, the ONE
ranking) puts `cardTitle` below the URL-matched/MediaSession titles and above
the page-level chain, and for a `posterlessClip` keeps only a URL-matched
thumbnail — empty otherwise, so the native side decodes the clip's own frame.
A label is taken verbatim ("View Countryside Duo"): the site's wording is not
ours to strip. A single-video page is untouched (og:title/og:image are right
there). Pinned by the smoke's `capture-meta:` section (teeth by mutation: four
checks fail without the two fields); the DOM walk was verified in Chromium
against card shapes incl. two clips in one container (no label) and
control-only cards. **A DOM-reported clip carries its metadata IN the report —
no round trip.** The `get-page-metadata` query is answered on the page's MAIN
thread, and its 300 ms race is lost while the page is still busy loading:
clips reported during hydration answer in 300–400 ms and land titled with
their CDN hash, while the same query answers in 10–30 ms a few seconds later
(the `meta:none … reply=timeout` debug line names it). The timeout was NOT
raised (a longer wait holds every capture). Instead the responder's body is
one builder (`buildPageMetadata`), and the scanner's `flush()` runs it for
every MEDIA URL in the batch (a `<video>`/`<audio>`/ `<source>` src, or a
media extension — images carry nothing) and sends the results as
`images-detected`'s `meta` map; `reportedMetaFor` puts the URL's entry on the
synthetic record and `emitCapture` uses it instead of querying
(`meta:reported`). Same answer the responder would give, built in a timer task
with no deadline. Pinned by the smoke's `reported-meta:` section (a frame that
never answers; teeth by mutation: two checks fail when the report is ignored).
**A WIRE capture still queries, and a frame that misses the 300 ms deadline is
waited for, up to 2.5 s more** (`metaLateMs`, same query, not a second one).
The case: a front page's autoplaying loops are fetched while the page still
parses and runs scripts, so a wire capture sent at the deadline is named by
its URL ("Video on <host>") and that untitled copy wins the URL dedup over the
content script's titled report. Only a capture whose frame missed the deadline
pays the wait, which is why it is a second bound and not a raised first one; a
frame that never answers still lets the capture through. Pinned by the smoke's
`late-meta:` checks (two fail without the wait).

**The seventh rule, a clip is tied to its OWN player, and a podcast card names
its own episode.** The shape: a front page with ten Video.js players and a
row of podcast cards, where every video read the page's title with the first
player's poster and every podcast the page's title with the site logo. Each
player plays HLS through MSE, so its `<video>` shows no URL the wire saw and
`findBoundMedia` binds nothing; the unbound fallback was the single-video
assumption (first `<video poster>`, og:title). The players load after the
cookie wall, long after the bridge's last timed pass (how they are captured
is the follow-up below). Fixed generically in `buildPageMetadata`, no host
check:
- **`findPagePlayer`**: when no element plays the captured URL, the page's
  Video.js players (Brightcove's is one) are read through the Xray waiver —
  `getAllPlayers()`, each `currentSources()`/`currentSrc()`, primitives and
  index loops — and the one listing the URL binds its tech `<video>` (found by
  `id()` in the script's own document, so later reads are not waived), its
  `poster()`, and its `mediainfo.name` as **`playerTitle`** (multi-video pages
  only; ranked right after the URL-matched VideoObject, above MediaSession,
  which describes whatever is playing now). A query-only difference matches
  when exactly one player has the path. Verified against the real Video.js
  8.21 in Chromium (root id = `id()`, tech without the manifest URL, poster on
  the tech). JW and Plyr have no lookup here yet: their HLS clips stay
  unbound.
- **An unbound clip on a multi-video page takes no poster** (the first one is
  another clip's) and is `posterlessClip`, so the native side decodes its own
  frame — unless it is an audio file (nothing to decode; the page image
  stays) or a player published a MediaSession. A single-video page keeps the
  first-poster fallback.
- **The card walk** (`cardTitleFor`) gained a heading pass (today the title
  tier of `pickUnits`: the NEAREST heading BEFORE the clip; one after it only
  in a card-sized node, `CARD_TEXT_MAX` 400 chars of text; never one inside
  a section/aside/nav that does not hold the clip, a header or footer
  counting as part of the article or section it sits in — a node's FIRST
  heading would title an article's clip "Background" or "Most read"), and
  it starts at the PLAYER ROOT (`.video-js`/`.jwplayer`/`.plyr`) and skips
  every candidate inside one: a player's chrome is never a title (a JW
  control's "Rewind 10 Seconds", Video.js's "Video Player" root label).
  Starting at the root
  also keeps the four-level budget for the card, not the player's wrappers.
- **A bound `<audio>` on a page with another clip** gets `cardTitle` and
  **`cardImage`** (`cardImageFor`: the card's first visible http(s) `<img>`
  of 64 px or more, same walk; ranked after the poster, before the page-level
  images). The stop rule there is `holdsAnotherClip`: a second element for the
  SAME URL is not another clip — a card can carry its `<audio>` twice (once
  in the card, once in a cookie-wall template that unwraps beside it), which
  a count of media elements would read as two clips. A single-podcast page
  keeps og:title/og:image.
- The bridge's Video.js reader titles a group with `mediainfo.name`, drops
  the root's "Video Player" label (English or the player's `localize`), and
  `emitOneGroup` prefers the group's picture to og:image.
Pinned by `node scripts/frame-meta-harness.mjs` (the news-front-page and
single-clip sections; a mutant per piece — no same-URL skip, no chrome skip,
no heading pass, no player lookup, the first-poster fallback on multi-video
pages, a player title on single-video pages, no card image, the walk not
starting at the root — each fails at least one), `node
scripts/bridge-dom-harness.mjs` (`videojs:`) and the smoke's `capture-meta:`
ranking checks.

**The seventh rule's follow-up: two more causes, found by REPLAYING a HAR,
not by reading it.** With `preload=none` players a HAR holds no stream
request, so reasoning from the wire goes nowhere;
`node scripts/har-replay.mjs <har> --background` (Chromium, every response
from the HAR, the real content script and bridge injected, their messages
then fed to the real background graph) names them:
- **The bridge claimed the podcasts.** Its DOM read runs in every frame that
  `looksLikePlayerFrame` — on such a front page any `[data-player]`/`.video-js`
  qualifies — and posted each plain `<audio>` 450 ms in with the page's
  title and og:image. Its claim then rejected the content script's
  correctly named report (`reject:player-claimed-url`). Now
  `findPlayerMedia` leaves an element outside a player container
  (`PLAYER_CONTAINER_SEL`) to the content script when the frame holds more
  than one media element; inside a container, or alone in its frame (an
  embed), it is read as before.
- **Nothing read the players.** They are set up seconds in (consent, then
  a slider's lazy load), after the `t4000` pass, and nothing else could
  see them pre-play. The bridge now re-runs on `loadstart` (a capturing
  document listener; fired when a player hands its media element a
  source, whatever the preload and for an MSE blob as for a file), in a
  FIXED 250 ms window (the first event arms one read; a timer reset by each
  event never fired on a page that keeps starting media — an ad slot, an
  autoplay feed), twelve reads at that pace and then one per 5 s for as
  long as media keeps starting (a hard stop at twelve lost every player a
  long page set up later). The read is `final` only once the timed passes
  are over, so it never sends an SSR set the site-name deferral is holding.
- **Ten players on one page were one capture at most.** Every bridge master
  went through `enumerateMasterNative`'s per-PAGE dedup and the native
  HLS_MASTER uid, both keyed on the page origin (right for a single-player
  embed, whose signed master changes per load). A master now carries
  `clipId` — the player's own id for the clip (Brightcove's
  `mediainfo.id`, a JW item's `mediaid`), else, in a frame holding several
  clips OR once the page state already posted a DIFFERENT master (one player
  moving on to the next clip of a playlist, which the page key dropped AND
  claimed, refusing the play-time capture too), the master's host + path
  with signature-like segments left out (`clipKeyOf`: `=`/`~`/`,`, an
  `<expiry>_<sig>`, a 40-char token — a path-signed CDN made every reload a
  new clip) — and both the JS sent-set key and the native uid become
  `origin#clipId`. A master without one keeps the page-wide rule, and one
  whose key this page state already posted is that clip re-signed: skipped.
- **One master per group, HLS first.** Brightcove lists each clip's master
  over http and https as HLS and DASH; `emitOneGroup` posted them all and
  the page dedup kept whichever came first, a DASH `.mpd` when it was
  listed first, which the native HLS enumeration cannot parse. It now posts
  one: an `.m3u8` before anything else, then https.
- **A Video.js player's tech is the bridge's.** The player-API readers run
  before the DOM read, and a DOM group holding a URL an API group holds is
  dropped (it was posted first, page-titled, then the master: two rows); so
  is any DOM group whose element sits inside a player root an API reader
  described (found by its id in the bridge's own document): that element
  plays whatever the player hands it, a PRE-ROLL ad included (IMA/VAST on
  mobile), which was posted as a page-titled clip.
  The content script likewise skips a `<video>`/`<source>` inside a
  Video.js root whose player holds sources (`pagePlayerOwns`), and a bound
  tech still gets its player's name (`videoJsPlayerOf`) when a wire query
  asks.
- **`<audio src="">` is no media URL.** `el.src` reflects an empty
  attribute as the document's own URL, so the scan reported the page
  itself: a HEAD probe and a native probe of the HTML. `reportMediaEl`
  reads `el.src` only when the attribute is non-empty, and `reportImg`
  likewise (a hidden avatar placeholder `<img src="">`;
  `frame-meta-harness.mjs` checks both).
In Chromium a Brightcove tech takes the pmp4 file, while Firefox plays
through VHS (a blob) — the player API is the source that holds either way.
Pinned by
`bridge-dom-harness.mjs` (`late:` + a clip-id check; five mutants — no
loadstart, no clip id, no DOM subsumption, no player gate, no HLS ranking —
each fail), `frame-meta-harness.mjs` (tech mp4 left to the bridge, bound
tech named by its player, empty src) and the smoke's `clip:` checks (fail
with a page-only key).

**The eighth rule, a news card's clip is named by its story, never by its
credit.** The shape: a front page of looping clips as cards, a media link
around the `<video>` whose only caption is a CREDIT
(`<figcaption>Agency / Agency</figcaption>`), and beside it a second link to
the SAME story holding the headline in a `<span>`. The walk took the
figcaption, so every clip was named by its agency; the headline was five
levels up, past the four-level card budget, and in a span, not a heading.
Now:
- **`storyTwinTitleFor`** (`content-script.js`), tried first after the
  clip's own label: a clip inside a link (at most `STORY_LINK_MAX_LEVELS`, 6,
  up) is that card's picture link, and the OTHER link to the same target
  (resolved href, fragment dropped) within three levels of it names the clip:
  its own label (unless the story's URL contradicts it, `slugDenies`), else
  the text in it the page SHOWS as a title, else its plain text
  (`storyLinkTitle`; see "The card walk reads STRUCTURE, not words"
  below). The usual stop rule applies (no
  ancestor holding another clip).
- **A credit is never a title**: credit-shaped (`creditText`: a `©`, an
  `@handle`, names joined by " / "), fine print, or `itemprop`
  creditText/copyrightHolder/copyrightNotice/author. A caption is read WITHOUT
  its asides (`textOf`: a credit, metadata, smaller type than the caption's, a
  kicker), and `[itemprop=caption]` counts as a caption beside `<figcaption>`,
  so a gallery slide's caption that ends in its agency names the slide.
- `[itemprop=headline]` joins the heading pass.
Pinned by `frame-meta-harness.mjs` (news front page: twin headline in both
paths, per card, a captioned slide, a credit-only card; each mutant — no
twin, credits not skipped, caption read whole — fails at least one).

**The card walk ranks by WHAT names the clip, then by distance.** Three card
shapes a walk by labels first, headings by tag only and four ancestors
whatever they hold gets wrong: a card link labelled "Play: <title>" beside
an `<h2>`; a card titled in a plain `<p>`; a headline `<span>` five
single-child wrappers away. `cardTitleFor`, per
ancestor (nearest first, stopping at one that holds another clip), takes
the first of: the clip's own label, then a story twin (once, before the
walk); an item's own name; a TITLE; a CAPTION; the card's PLAIN text — and
only when the whole reach has none of those, a control's label, the text
the page keeps hidden, the picture's `alt` (several words, not a file name
like "still 1.jpg"), a kicker. What each tier IS, is
read from structure, not words: see "The card walk reads STRUCTURE, not
words" below. A label stays verbatim when it is all the card has ("View
Countryside Duo"). **Only an ancestor that adds something beside the clip's
branch spends one of the four levels** (`cardAncestors`, twelve hops at
most); `cardImageFor` walks the same ancestors. Pinned by the `cards.html`
page of `frame-meta-harness.mjs` — one card per idiom (a "Play: <title>"
control beside a heading, a title in a plain `<p>` repeated in a share
button's `data-title`, a title link between a kicker and a subtitle, a
headline marked only by a test id, a headline span in a link, microdata with
an untyped author and a typed Organization, aria-labelledby, six wrappers, a
"Video" label inside a headline box, a duration beside the title, image alt,
file-name alt, label-only card). A card that reuses another story's loop
is named by its own headline: the card's headline is the right answer.

**A label the page puts on three or more different things names none of them —
the language-independent rule.** A word list only knows the words someone
added (see "The card walk reads STRUCTURE, not words" below). The census
(`sharedLabel` in `content-script.js`) counts instead: the same label (case
and spacing folded, a carousel position left out) on links to three or more
different pages ("Watch now" on every story), on the controls of three or more
different clips (a mute button in each slide), on three or more different
clips' own `<video>`/`<audio>` (one example label on every clip), or as the
alt of three or more different pictures is a control's wording or a
placeholder, whatever the language. Rules that keep it from eating real
titles, each pinned:
- **Two is not enough.** A story's portrait and landscape cuts carry its
  title on two different clips, and keep it.
- **"Different" is the THING, not the element**: a link's target, a clip's
  file, a picture's file, an iframe's src, and for a control the clip or
  picture in its card (the nearest `video, audio, img` up to six levels) — so
  a carousel's cloned slides, and a story shown twice with the same picture,
  count once.
- **Kinds are counted apart** (link, control, media, picture, frame, and an
  item's own label — an article's, a figure's, a group's, like an unloaded
  player's `<div role="group" aria-label="Video player">`): a card can carry
  its title as a play button's label, its picture's alt and its link's text
  — three kinds, one story, not three things.
- **Only LABELS are judged** (`aria-label`, `title`, a link's or a control's
  text, an alt, an iframe's title): content the page writes as a name —
  headings, captions, `aria-labelledby` text, JSON and config titles (those
  have `distinctPlayerConfigTitle`), declared metadata — is not.
- **Iframe titles count from two, numbers left out** ("Embedded content 1" /
  "2", two "YouTube video player"s), and a control's numbers are folded too
  ("Slide 1" … "Slide 9" are one label).
- **A picture is not left nameless by it**: an alt shared by three photos (a
  gallery that writes the story's headline on each) yields to the photo's own
  caption or card, and still names a photo nothing else names.
- **A page of one or two clips**, where nothing can be counted, is read by
  structure instead: a control's place and size (`cardLabelFor`), the URL
  a link spells (`slugDenies`) — see the next section.
- **The TEXT census** (`sharedText`) is its twin for what a page SHOWS: the
  same short text in three or more different cards is template text (a
  kicker over every clip, a partner's mark, "Live" in each podcast card),
  read by the card walk's units, its captions and a
  caption's asides.
Where it reads: `ownCardLabel` (a clip's own label, the card's control
labels, a story twin's label), `storyLinkTitle`'s text, the card's and the
poster twin's alt, `buildImageMetadata`'s own labels, `captionForFrame`
(through `sharedLabelOf`), and in the bridge `domTitleFor`
(`sharedMediaLabel`, media labels only). Cost: one census per KIND per page
per second, built on the first question about that kind (`LABEL_CENSUS_SEL`,
5 000 elements at most), so a picture's alt never pays for the page's links;
on a synthetic front page of 2 000 story links, 300 pictures and 60 clips a
cold query costs 2–4 ms more in desktop Chromium. Pinned by
`frame-meta-harness.mjs` "distinct names" (in Dutch, Polish and Finnish,
which no list knows; mutants: no census, shared from two, counting
elements, kinds pooled, frame numbers kept, images ignoring it, a gallery
left nameless — each fails one) and `bridge-dom-harness.mjs` `shared label:`
(never shared and shared-from-two mutants fail).

**The card walk reads STRUCTURE, not words.** A word list needs a long list
per site language or developer language, so `content-script.js` carries
none: no control verbs, no picture-kind words, no generic iframe titles, no
credit or kicker phrases, no title / headline / caption / credit CLASS
tokens, no `data-title` attributes, no month names. What names a clip is
read from how the page SHOWS a text, the same in any language:
- **Text units** (`textUnitsIn`): the elements that carry text of their
  own, each read whole; a declared title — a heading, `role=heading`,
  `itemprop=headline`, the `itemprop=name` of a creative work — read as one.
  A control's subtree is skipped unless it holds the clip (then the control
  IS the card: an `<a role="button">` around its clip and caption), and so
  is a subtree the page hides from screen readers (`aria-hidden`: a credit
  line under each loop).
- **How a text is shown** (`factsOf`: computed style, read once per element
  per second). Larger or bolder than the body's text (`prominent`: 1.12×
  the body's size, or bold at 0.95×) is a title whatever its tag (a
  headline `<span>`); fine print (under 0.8×) of six words or fewer is a
  credit, a byline or a timestamp; four words or fewer in capitals (the
  stylesheet's or the text's own) or letter-spaced are a kicker
  (`kickerLike`), and one INSIDE a text is a kicker only when it is set
  apart from the text around it (`kickerApart`: a heading in capitals
  throughout holds none); a text the page does not render
  (`checkVisibility`) is a last resort. A body under 12 px (a 62.5% root)
  reads as 16. Text in separate boxes is read apart: a block tag, or any
  element the stylesheet lays out as a box (`factsOf().box`).
- **Per level** (`levelNames` → `pickUnits`, ancestors nearest first): an
  item's own name — `aria-labelledby`, or the `aria-label` of an article,
  figure, list item or group (`itemLabel`: a `role=group` around each
  player, whose error slate is text too); then a TITLE — in a card-sized
  node the most prominent shown or declared title, in a bigger one only the
  nearest declared heading before the clip; then a CAPTION (`figcaption`,
  `itemprop=caption`; never one word, credit-shaped, kicker-like or fine
  print); then the card's PLAIN text, two words or more, a phrase of three
  first (a title in a plain `<p>` names its card).
- **Shapes, in any language**: a carousel position is two numbers joined by
  a slash or any short word, the first no larger ("Slajd 2 z 5", "3 из 8");
  metadata is a text with no letter, a count with a short unit ("12 min",
  "1.2K") or mostly digits; a credit is a `©`, an `@handle` or a few names
  joined by " / " ("Agency / Agency"); an agency dateline is "Place, Place
  - Word Day[, Year]: " whatever the month's language; a picture's credit
  tail is a parenthesis after a sentence, a `©` tail, or "Word: Names" with
  capitalised names only.
- **The text census** (`sharedText`): the same short text shown in three or
  more different cards is template text (see the census above).
- **The URL a link spells** (`slugDenies`): a short text a link carries — a
  label of four words or fewer, a plain link text of two — sharing no word
  (a five-letter stem) with the slug of its story's URL is a control's
  wording ("Leer la noticia relacionada", "Czytaj więcej"). Never a text the
  page shows as a title: headlines are rewritten after the URL is fixed, so
  a headline can share no word with its slug. A URL with no slug says
  nothing.
- **The page's own vocabulary** (`developerLabel`): a label of three words or
  fewer made only of the words its class names, ids, test ids and custom
  elements' names are built from, shadow roots included (BEM, kebab, snake and
  camelCase split; a token with a digit, a CSS module's hash, skipped) is the
  developer's name for a ROLE, in the developer's language: a poster's "Hero
  video poster" under `.hero` / `.video` / `.poster` classes, "Video kapak"
  under `.video-kapak`, "Video player" in a `<x-video-feed>` beside
  `data-testid="video-player"`. Three words at most: a CMS can write a story's
  tags into class names (WordPress' `tag-taylor-swift`).
- **The template around a label** (`stripLabelAffix`): a run of one to
  three words that four in five of the titles of one shape (tag and role),
  three at least, begin or end with is the page's wrapping, and left out
  when two words or more remain: "<title> video", "Vertical <title> (2 de
  10)". Developer and shared labels are not
  counted; a picture's alt never loses words; the cut keeps the title's own
  punctuation ("Is A.I. a Bubble?"). The bridge's twin,
  `stripTitleTemplate`, does the same for the player titles of one pass.
- **A control's place and size** (`cardLabelFor`): a control's label names
  its card when the control holds the clip or a picture, lies over half the
  clip or more, or reads five words or more; any other control's label only
  on a page of three clips or more, where the label census has already
  dropped what every card repeats. A small mute button on a page of two
  clips names nothing ("Sesi aç kapat").
- **A poster's file name** drops a template's words at its edges (one to
  three words three or more DIFFERENT pictures of the page begin or end
  with; one photo in several crops counts once), and is no name when the
  rest is only class-name words. **A mark** — shown
  under 64 px on its shorter side, or an SVG whose size the page does not
  declare — is named by its own alt, title or label, never by the card
  walk: the text around a logo is a footer's links, an ad's pitch, "Un
  proyecto con" before a sponsor's logo (an SVG of a declared picture size,
  a 400×400 game glyph, is a picture like any other). **An
  iframe** is captioned by its figure first, then by its own title unless
  that title, with the frame's host words taken out, is only a number or
  class-name words.

What stays is standards and library vocabulary, which every site spells
alike because programs read it: HTML elements, ARIA roles and attributes,
schema.org types and properties, the player roots `.video-js` / `.jwplayer`
/ `.plyr` and Video.js's own "Video Player" label (with its `localize`), the
JSON keys of player configs and APIs (the bridge's `MEDIA_KEY_RE` family,
`climbJsonClip`).

Cost on a synthetic front page (2 000 story links, 300 captioned pictures,
60 clip cards, with CSS; timed in desktop Chromium): the first scan report
in 0.54–0.63 s, a clip query in 5–6.5 ms, a query after the censuses
expired in 14–16 ms.

Pinned by `frame-meta-harness.mjs` "no word list" — a Polish and a Turkish
page whose class names are CSS-module hashes no rule knows, plus real
stylesheets on two news-page fixtures; and 26 mutants, one per rule, each
failing at least one check: no prominence, no fine print, no kickers, no count
shape, no credit shape, no class-word filter, no slug, the slug judging
headlines too, no text census, a card's own header read as another section,
small controls naming cards, no overlay, the census never speaking, the old
joining words, a month list, no colon credit, no item label, aria-hidden text
read, marks walked, every SVG a mark, a mark's size by its width alone, a
mark's one-word alt refused, the frame's title before its figure's caption,
one-word captions, plain text never naming, hidden text first.

Residuals: typography needs the page's stylesheet, so a replay without CSS
(the device audit's `around.json` holds markup, not computed style) is
approximate — a show name can beat its headline there; a lone one-word CMS
default alt in a language the markup does not use ("Imagen", on fewer than
three pictures) still names its picture; a unique action label of four
words or fewer on a small button names its clip on a page of three clips or
more when nothing else in the card does; a CMS that writes a story's words
into class names can make an alt of exactly those words read as a role
(a "<Site> Logo" alt is one); a provider's generic iframe title ("YouTube
video player", one frame, on a page whose class names do not use those
words) is still a caption, which matters only where a frame caption is
used at all (a sub-frame capture whose own title is a file name); the
bridge's JSON key lists are the one list left, of field names rather than
words people read.

**To extend: a structural signal — a shape, a size, a place, a count — with
a fixture in a language and class naming no rule knows (the `nolist-*`
pages), never a word, a class token or a host check.**

Regressions pinned against other page shapes: the `loadstart` scheduler
(`late:` churn + many, 2), a player's next clip lost (`swap:`, 2), a pre-roll
ad posted as a clip (`preroll:`), an SSR set named after the site
(`sitename:`), a signed path as clip key and JW's `mediaid` (`signed
path:`/`jw:`) — eight bridge checks in all; and in the content script an
article's subheading or a "Most read" box as the clip's title and an inline
icon as a podcast's picture (`articles:` in `frame-meta-harness.mjs`, 3). A
card picture now needs a KNOWN size of 64 px or more (unloaded images without
a width attribute, and SVGs, are skipped): an unknown size is what an inline
icon looks like.

**A player's config JSON names its clip.** The shape: a player that keeps each
clip's config as JSON in an attribute of the `<video>` itself,
`data-opts='{"title":"…","src":"…mp4",…}'`, exposes no `videojs` global, and
puts no heading in the module, so the bridge's DOM read (`domTitleFor`) and
the content script's card walk had nothing and fell back to the page title.
`configTitleFor` (the bridge, and the content script's twin, which feeds
`playerTitle` on a multi-video page) reads ANY `data-*` attribute of the media
element, or of its player root, that holds a JSON object: a string `title` /
`videoTitle` / `headline` at the top, or a `title` / `name` under `media` /
`video` / `metadata` / `mediainfo` / `clip`. The attribute's name is no rule
(Video.js reads `data-setup`, Plyr `data-plyr-config`, others `data-opts`); a
top-level `name` is not a title (a player's own instance name,
`"heroPlayer"`). And a card image's alt made only of the page's own class-name
words is no title (`developerLabel`: an `<img class="posterImage…" alt="Hero
video poster">` in `.player… .vjs-video-container` in `.hero…`). Pinned by
`bridge-dom-harness.mjs` (`config:`) and `frame-meta-harness.mjs` (`player
config:`); mutants: a top-level `name` taken, the class-word filter dropped,
each fails one.

**More naming and dedup rules, each pinned by a check that fails on the
code it replaced**: the device-audit sections of `frame-meta-harness.mjs`
(mutants for every rule), `bridge-dom-harness.mjs` `state:`, the smoke's
`race:` / `frame-named:` / `refined:` (each fix's mutant fails one):
- **The MediaSession names only the clip that is playing.** On a page of
  several videos `buildPageMetadata` reads it for the bound element only when
  that element is not paused: a page that autoplays one clip above a
  carousel gave every card the playing clip's title.
- **A control's wording is never a name, in any language** ("Watch now" on
  every story): the label census, the text census and a control's place
  decide it ("The card walk reads STRUCTURE, not words"). When nothing else
  names a clip on a multi-video page, its POSTER's file name read as words
  is the last per-clip name (`posterSlugTitle` → `posterTitle`, ranked after
  `cardTitle` in `pickCaptureMeta`): three letter-only words or more, a
  template's words at its edges dropped (see "The card walk reads
  STRUCTURE"), so a hash or "thumb-1024x576" is never one. A
  label that is a title prefixed by a verb ("Play <title>") is
  still taken verbatim, the "View Countryside Duo" rule.
- **A config title several clips share is a placeholder**
  (`distinctPlayerConfigTitle`): a data layer that gives every vertical clip
  one test title; the card heading names each. A second element for the same
  file is the same clip, not another.
- **Hidden text names a clip last**: a control or caption with the `hidden`
  attribute or an inline `display:none`/`visibility:hidden` is no candidate
  (`hiddenNode`), and a card's text the page does not render is its last
  resort, after a control's label. A podcast player that keeps a "Live"
  title element in every card, shown only live, is named by its play
  button's label.
- **A card's size is the text a reader sees** (`visibleTextLength`: no
  script, style, template, SVG or media fallback text, nothing hidden).
  A video card holding an inline JSON-LD script and an SVG icon is still
  card-sized, so its heading after the clip is not refused as an article's
  subheading.
- **A teaser is named by its story even alone**: the page's only `<video>`,
  inside a link to ANOTHER page, takes the card walk (`inStoryLink`) — a front
  page's one loop. An article's own clip, in no link, keeps the page's title.
- **The JSON a clip sits in names it** — one climb, `climbJsonClip` in the
  content script and `clipMetaFromPath` in the bridge: from the object holding
  the URL up to the clip's own object, through a list of its RENDITIONS (the
  entries carry a rendition's keys, or the list's name says so) but never
  into a list of clips (an entry with a clip's keys), and never through an
  object holding another clip beside this one under a key that is not a
  format name (`hls` beside `cmaf` is one clip; `hero` beside `promo` is
  two). A title (a string, or `{primary|basic|…}`), else the headline the
  page shows for the story the entry links to (`storyTitleFor`), else the
  poster; a bare `name` counts only in words. Where it is read: a list in an
  ancestor's `data-*` (a shorts carousel, titles blank, `productUrl` set),
  and every `<script type="application/json">`, which the scrape reads AS
  JSON and reports once per clip, its manifest preferred
  (`scrapeJsonScripts`; a `__NEXT_DATA__` listing a master and an mp4 per
  clip is one row per clip, not two titled with the page). In the bridge the
  same climb names state groups, merges groups whose climb ends at the same
  object (a clip's HLS and CMAF masters listed apart), and reads an array of
  renditions whatever its key (`looksLikeRenditionList`, e.g. `videoAssets`);
  `MAX_PLAYER_GROUPS` is 40.
- **Shadow DOM is scanned**: the scanner registers the shadow roots of custom
  elements (closed ones through Firefox's `openOrClosedShadowRoot`), scans
  and observes each (`adoptShadowRoots`, swept again a second after the DOM
  changes), and the binding and card walk cross into them (`queryDeep`,
  `parentOf`, `anchorIn`): a front page that plays every clip inside a
  custom element's shadow root reported none. A `<video>` listing a
  manifest among its `<source>`s is reported by the manifest alone (beside
  a child playlist and the mp4s). A kicker inside a headline box
  ("Opinion") is left out of the title, and text in separate blocks is
  spaced.
- **One clip, one capture, when the wire and the bridge race.** The catcher
  notes every media URL it decides to send in the tab's `TabState.captured`;
  a page-state post whose URL (or a manifest folded beside it) the catcher
  already sent is dropped (`catcherCaptured`, parsers/page-state.js). The
  wire sees the master the moment the player fetches it, the bridge reads
  the player ~150 ms later, and each passed Java's check before the other
  landed, listing every clip twice. A
  progressive sibling the wire fetched does not count: the master's ladder
  is not its duplicate. The bridge's claim still covers the opposite order.
  **Every URL key drops the fragment AND an explicit default port**
  (`urlKey`, the one key function in `requests.js`): the wire never carries
  a `:443`/`:80` (a request URL is normalised before it is sent), but a URL
  read from page state can (a player config spelling its live master with
  `:443`), and keyed as written the two were two files in BOTH orders — the
  bridge's post of a stream the wire had just sent was not the catcher's
  copy, and the wire's fetch of a stream the bridge had claimed was not the
  claimed clip: a live radio master, twice, each with the page's title. The
  page-state handlers also post the bridge's URLs without the port
  (`canonicalPayload`, so the native side holds the wire's spelling), and
  `CaptureRenditions.trivial` drops it natively. Pinned by the smoke's
  `race:` default-port checks (both orders; a `dropDefaultPort` that keeps
  the port fails three) and the capture harness's `renditions:` port check.
- **A bridge clip with only the page's title asks the content script.** The
  bridge flags a group it could not name (`titleFromPage`); the handler asks
  the emitting frame (`askFrameMeta`, the catcher's query with its two
  deadlines) and takes the answer only when it names the clip itself
  (`clipMetaOf`: a declaration of its URL, its player, the clip playing, its
  card, its poster's words): a podcast player otherwise posts with the
  site's title.
- **A claimed URL with a refined query is the claimed file**
  (`sameFileQueryRefined`: same origin and path, every parameter of one in
  the other with the same value, both with a query): a player read before
  its script appended `&dist=…` to the `<audio>` is one row, not two.

**Redirects, native renaming and placeholders — each pinned by a check that
fails on the code it replaced**: the device-audit sections of
`frame-meta-harness.mjs` (a mutant per rule, the literal scan's included),
`bridge-dom-harness.mjs` `state:`, the smoke's `redirect:`,
`race:`/`page-titled:` and block-list checks, the capture harness's
`clip names:` and the judge selftest's `cuts:`:
- **A redirect's target is the file its hop names.** A page lists
  `cdn.jwplayer.com/videos/<id>-<template>.mp4` (the scan reports it with its
  card's name) and the player is sent on to a signed copy elsewhere; the copy,
  bound to no element, was captured again with the page's title — every
  carousel clip twice. The catcher keeps each
  chain's first hop on its record (`hopFrom`), rejects a target whose hop it
  captured (`reject:redirect-of-captured`), and files hop → target in the
  tab's `TabState.redirects`, so a scan report of the hop that comes after the
  target goes out under the target's URL (`redirect-hop-reported`): one row,
  which takes the report's name natively (next bullet).
- **A name for the clip replaces the page's title, natively.** An emit whose
  name is only the page's title says so (`pageTitled`: the catcher's when
  `pickCaptureMeta` gave the page-level name, `sendVariants` /
  `enumerateMasterNative`'s when the bridge had nothing better; through
  `JsonHelper` → `GeckoInspectEntity` → `GeckoInspectTask` →
  `BrowserDownloadEntity`, parcelled and copied). `fillPageNameLocked` gives
  a later copy's name, description and picture to a listed capture with no
  name at all, or with only the page's title when the copy names the clip
  itself — never a page's title over another page's, never over a clip's own
  name. A wire copy of a JW master that kept the page's title while the
  player said what the clip was: when the catcher sent a URL first, the
  bridge sends its player's name as a second message for the same
  URL (`nameCatcherCopy`, carrying the catcher's cached request headers so
  it probes the same way should it land first).
- **A control's label is no name**: a mute button on each slide (the label
  census) and a "Read the related story" link whose story URL spells
  something else (`slugDenies`). A carousel position is no title ("2 de
  10", "Slide 3 of 8": two numbers joined by a slash or any short word) and
  is cut from the end of one ("Vertical <title> (2 de 10)"); an untranslated
  i18n key ("player.share_clip") is no label.
- **A slide with no title takes its picture's twin's** (`posterTwinTitleFor`,
  multi-video pages, after the card walk): a lightbox plays each clip under
  `poster.jpg?width=720` with nothing but its mute button around it, and the
  carousel card that opens it shows `poster.jpg?width=480` with an
  aria-label. Same origin and path is the same picture; twins naming different
  titles answer nothing.
- **A lone clip in a card that links to its story is a teaser**
  (`cardStoryLink`, beside `inStoryLink`): a text-less labelled link laid
  over the card (`<a aria-label="<title>">` over a loop) or a title element
  that is or holds the link (a podcast card). Card-sized nodes only; an
  article's own clip keeps the page's title.
- **The page's only `<audio>` is named by its player's card** when the page's
  title does not already carry that heading (`ownCardHeadingFor`): a
  podcast card, a sticky radio player. The card is read whole
  (`levelNames(…, wholeCard)`): a radio player can keep its programme in an
  `<article>` beside the `<audio>`, which the sectioning rule would take for
  another story. On the episode's own page the page's title names it.
- **A query-refined element URL binds** (`refinedUrl`, the content script's
  twin of `sameFileQueryRefined`): a player that appends `&dist=…` to the
  `<audio>` it was handed. Two elements refining one URL differently bind
  neither.
- **A list of player configs is not a rendition list** (bridge
  `looksLikeRenditionList` + `qualityOf`): renditions differ in a quality or
  format dimension (height, bitrate, a quality label, the container); two
  `{src, type: "audio/mpeg"}` podcast players are two tiles, not one.
- **The JS state a script assigns names the URLs the regex scan found**
  (`eachJsonLiteral` / `literalSpans`): podcast episodes kept in
  `globalContent={…}` in a classic script, not a JSON one. The
  literals only NAME (they never add a URL the scan did not find) and the
  cost is linear in the script whatever it holds: ONE pass pairs the brackets
  (strings of all three quote kinds skipped, so a `'"'` in a bundle cannot
  turn the state inside out), an opener that cannot start JSON is not
  recorded, past 50 000 spans the smallest go, a literal inside one that
  parsed is skipped, at most twice the script's length reaches JSON.parse,
  and only literals that name a found URL count toward the 8 per script.
  `node` measured 2.5 MB of pathological openers at ~75 ms.
- **A file the page links to is named by the link's card** (`linkToFile`,
  file name pre-filtered before any URL parse): a podcast widget that shows
  the episode beside `<a class="button_download" href="…/download.mp3"
  aria-label="player.download_episode">`.
- **An iframe's generic title is no caption** (`genericFrameTitle`, and a
  figure's caption is read first): pym.js titles every graphic "Embedded
  content 1"; a provider's "YouTube video player"
  is the same kind — with the frame's host words taken out, a number or the
  page's class-name words are all that is left.
- **Dailymotion's LIVE master is the parser's** (`/cdn/live/video/` beside
  `/cdn/manifest/video/` in `parser-blocklist.js`, or the catcher adds two
  "Dailymotion Video Player" tiles beside the parser's) and the parser's
  name is the whole title, never cut at 40 characters.
- **A player's placeholder file is junk** (`regex.js`: `dummy`, `silence`,
  `silent`, `blank`, `empty` as the WHOLE file name, with a media extension):
  a player's `core/test/dummy.mp3`.
- **Tooling.** The judge reads two tiles with one name and DIFFERENT shapes as
  two cuts of one story, a note (`f_vert_…` and `f_mo_…` masters,
  `1080x1920` and `1920x1080` playlists); a tile whose shape is unknown
  keeps its group a duplicate. `SiteAuditTest` tries the tab lookup three
  times (one unanswered call would lose the site).
Residuals, deliberately not fixed: one loop shipped as a `desk` and a
`mobile` file (two tiles, one name, same shape); a live channel with two
masters named by the page (the player's own and an ad-stitched one); podcasts
that name nothing near them; a widget's `play.mp3` and `download.mp3` (two
URLs of one episode); promo cards carrying only the page's title; example
clips sharing one label.

**Slideshows, late names and every JW player — each pinned by a check that
fails on the code it replaced**: the device-audit sections of
`frame-meta-harness.mjs` (a mutant per rule), `bridge-dom-harness.mjs`
`jw:`, the smoke's `clip-named:` (mutants without the redirect lookup, the
capture gate or the once-claim each fail) and the judge selftest:
- **A slideshow's caption names the slide it shows** (`slideshowAround`,
  clips only). An ancestor holding another clip ends the card walk, unless
  it is a slideshow showing this clip: no other clip of it shown
  (`checkVisibility` with opacity, half the box) and the other slides with
  no text of their own: a front page that leads with clips and photos
  crossfading under one caption. A caption or a card's text loses a credit
  after its last sentence ("… across France. (Agency Video)") and its
  closing full stop (`clipText`).
  Pictures are not walked this way: three photos of a static gallery under
  one caption name none.
- **A slide not shown is named by the slideshow's own list**
  (`slideListedCaptionFor`). Such a slideshow can keep every slide's
  caption in an inline script of its own, in slide order, writing one into
  the caption box as the slides change — so a slide not shown holds no
  text, and its clip was page-titled until it played (a slide that never
  played stayed so). The list is told by the slides that show a PICTURE:
  at each such slot the entry at the same index carries the picture's alt,
  the one text the page writes twice, so the list is the slides' own and
  its entry at the clip's slot names the clip — the entry itself, or the
  entry key the pictures matched; no key is known in advance, and one
  mismatched picture refuses the list. Read from the scripts inside the
  slideshow's ancestors and the inline scripts next to them (the script
  finds its slideshow as the element before itself), never the page's;
  parsed as DATA by `parseJsData` (strings in any quote, identifier keys,
  comments, trailing commas; an expression or a call ends the parse) once
  per script and bounded (200 KB, a node budget, twice the text walked at
  most). Pinned by `frame-meta-harness.mjs` (`apgallery`: a slide not
  shown named by its entry, each by its own; `apgallery2`, the same
  slideshow with no list the pictures place it in: the page's title, then
  named on play); the mutant without the listing fails two.
- **A clip is named once it plays.** A clip reported with nothing of its
  own is remembered (`unnamedClips`); its `playing` event (listened to on
  the document and on every adopted shadow root: the event is not
  composed) looks again after `RENAME_SETTLE_MS` (1 s, so a crossfade is
  over; a clip paused by then has moved on), and a name found goes to the
  background as `clip-named`. `nameCapturedClip` (requests.js) sends it to
  the native side for a capture this tab sent (the catcher's, under its
  redirect target too, or the bridge's claim), once per (tab, URL, name),
  where `fillPageNameLocked` gives it to a listing with only the page's
  title. It names hidden slides as they show, and players that say "Video
  player" until they load their clip.
- **The label census counts an item's own label** and the vocabulary reads
  test ids, custom elements and shadow roots (see "distinct names" and
  "The card walk reads STRUCTURE" above): two "Video player" tiles name
  nothing. **A label's template is left out** (`stripLabelAffix`, four in
  five of one shape): "<title> video" on every player, a "Vertical"
  prefix.
- **Every JW Player is read** (`readJwPlayers`): `jwplayer()` answers the
  first player only, and a front page can run one per clip. `jwplayer(i)`
  until jw8's stub with no `getPlaylist`; a page's own `jwplayer()` that
  ignores the index answers one player, read once. A suffix every title
  shares is the template `stripTitleTemplate` leaves out.
- **An `<audio>`'s card holds its player's parts.** On a page with other
  clips a radio player's programme panel (an `<article>` beside the
  `<audio>`) is not another story: an audio's walk reads a section beside
  it as the card's part (`wholeCard`), as the single-audio path does,
  unless the section links to other pages (`linksElsewhere`: a "Latest"
  list is never a player's part). An empty `<video>` the page does not
  render (kept for a video stream) is no other clip.
- **The page's only `<audio>` with no heading takes a long control label**
  (`ownCardHeadingFor`, `LONE_LABEL_MIN_WORDS` 8): a podcast in a block
  the page keeps hidden, named only on its play button ("Play <title>"). A
  shorter label ("Listen to this story") says what the button does, and the
  page's title stays.
- **A heading in capitals**: a kicker inside a text must be set
  apart from it (`kickerApart`), and its two `<span class="block">` lines
  are spaced (`factsOf().box`): "Sign up and get your extra discount", not
  "Sign up and get your" or "…yourextra discount".
- **A download link with parameters of its own is the file's**
  (`linkAddsQuery`: same origin and path, every parameter of the file's URL
  in the link's): a widget button linking `…/download.mp3?dl=true&…`. A
  JSON entry listing the file with no title does not keep the link from
  naming it (`jsonNames`).
- **A poster's template words go only at its edges**
  (`pictureFileAffixes`): a word several stories share ("prize") is
  content, not template.
- **Judge**: a file captured under its redirect target is no miss (followed
  through the request log), nor is a file the catcher drops as junk
  (`regex.js`, a `dummy.mp3?dist=…`).
Residuals: a live player and a preview clip keeping the page's title (a
radio stream played through a hidden MSE element binds to nothing the
content script can see, so the station and programme on its skin never
reach it). A slide that never plays keeps the page's title when its
slideshow keeps no list the pictures place it in.

**Players handed their own sources, widget state, wrapper frames — each
pinned by a check that fails on the code it replaced**: the smoke's
`redirect:`, `clip-named:`, `caption:` and `spotify:` checks,
`frame-meta-harness.mjs` "widget state, file ids…" (+ `copeplayer4`),
`bridge-dom-harness.mjs` `state:` (thirteen mutants, each failing one):
- **A redirect target whose hop the bridge claimed is that clip too**
  (`reject:player-claimed-url` with the hop): a JW player handed one of the
  clip's own mp4s (`cdn.jwplayer.com/videos/<id>-<template>.mp4`, folded as
  a sibling of the master) is sent on to a signed copy, which was captured
  again with the page's title — every clip of a front page twice. When the
  target is sent before the claim lands, the bridge names that row
  (`catcherCopiesOf` → `nameCatcherCopies`).
- **A play-time name goes to the URL that was listed** (`nameCapturedClip`):
  the hop when the scan's report of it was the capture (its target dropped
  as `redirect-of-captured`), the target only when the catcher sent it in
  the hop's place. It went to the target either way, a new row beside the
  page-titled one.
- **A MediaSession title another clip's element or player carries in its
  own label is that clip's** (`sessionTitleOfOtherClip`: a `<video title>`,
  an `aria-label`, its player root's): a page player publishes its session
  whether or not it is the element playing, and a gallery slide playing
  beside it took its title.
- **A scan report the content script built clip metadata for is media**,
  whatever its extension (a `<video>` playing a `.pmp4`): typed `image` it
  was never noted as captured, and its redirect target was captured again.
- **A JSON literal after a `,` is read too** (`literalSpans`): a widget's
  state passed as a later argument of a call (`widget("render", "box",
  {"episode": {…}})`) lists the episode under `download_url` and
  `playback_url`; one report, named by the episode, with its picture and
  its JSON `description` (`jsonDescription`, ranked after a declared
  description). Both URLs used to be reported alone, named by the frame's
  title and so dropped by the audio gate: "Audio on <site>" twice per
  episode.
- **An element whose `data-*` attribute holds the file's id names the
  file** (`elementForFileId`: the file name without its extension, six
  characters or more with a digit, one element on the page): a radio's
  bulletin list, each a play button carrying `data-audioid="<id>"` beside
  its name.
- **An audio player's own item beside its controls names it** when the
  level holding both is too long to read whole (`playerItemBeside`: the one
  `<article>` just before the player's branch, card-sized, linking to no
  other page): a radio player whose schedule sits under its "now on air"
  card.
- **The bridge walks a JSON STRING** (`jsonValueOf`, ≤ 200 000 characters,
  100 per pass): a player's options holding its sources as `videoSrc:
  '[{"src": …m3u8}, {…1200.mp4}, {…650.mp4}]'` are one clip, its master
  posted with the files as siblings, named by the options' `videoTitle`.
  The scrape had reported each mp4 alone.
- **A frame handed its file in its own URL is captioned by its card**
  (`frameHoldsMediaUrl` → `frameCardTitleOf` in the scanner;
  `frameHandedFile` in `withFrameCaption`): `…/iframe-video/?src=<mp4>`
  wraps any file, so its page title ("Iframe para mostrar vídeo") is the
  wrapper's, and the host page's caption replaces it for a capture whose
  name is only that title. Other frames keep the narrow filename-only rule.
Residuals: a video podcast's preview and episode named alike on two
providers; a background loop under a list of story links named by one of
them.

**Absorbed targets, folded files, refined copies, carousel previews, keyed
names, player instances, templates — each pinned by a check that fails on
the code it replaced**: the smoke's `redirect:`, `refined:` and `template:`
checks, the capture harness's `siblings:`, `frame-meta-harness.mjs` "widget
state, file ids…", `bridge-dom-harness.mjs` `state:` (mutants: no
absorbed-target rejection, no siblings on the emit, siblings ignored
natively, no name taken over, no refined match, the bridge naming its own
URL, no button test, a held label taken whatever its length, no alt in the
label, no keyed name (nested and flat), ids read as formats, no template
flag, no word floor, no template drop — each fails one):
- **A later request of its own for an absorbed target is that file too**
  (`TabState.absorbed`, `reject:redirect-target-of-captured`): a player sent
  from a captured or claimed hop to a signed copy asks for the copy's next
  range under a request of its own, which no hop marks. The target is
  remembered per tab when its hop's chain is rejected.
- **A master names the files it folded** (`siblings` on the `hls-master`
  emit → `JsonHelper` → `GeckoInspectEntity` → `BrowserDownloadEntity
  .siblingUrls`, parcelled and copied): when the scan reported one of them
  before the bridge read the player (a card's preview of the clip),
  `CaptureRenditions.covers` lets the master take that capture's place,
  whatever its rendition count, and its name when the master had only the
  page's title; in the other order `listsUrl` makes the file a duplicate.
- **The catcher's copy under a refined query is the same file**
  (`capturedKeyOf` behind `catcherCaptured` / `catcherCopiesOf`): a scan
  that reported `…&dist=…` before the bridge read the bare URL from the
  player's state is one row, and the bridge's name goes to the copy under
  the URL it was sent with (`nameCopiesOf`), never as a capture of the
  bridge's own URL.
- **The page's only `<video>` inside a `<button>` is a card's preview**
  (`inCardButton` makes it a teaser, so the card walk runs): a carousel of
  vertical videos plays the current card's muted preview inside the card's
  button, its title only on the button's label and the card picture's alt.
  **The control that holds the clip is its card** (`heldControlName`, at
  the item tier): its picture's alt when the label holds it ("Preview:
  <title>. Play full screen" → "<title>"), else the label when it has five
  words or more; a short label ("Play video", a player wrapping its
  `<video>` in a button) is wording, and the walk goes on.
- **A key named like the media's names it** (`twinTitleOf` in the bridge's
  climb): `audioName` beside `audioUrl`, `videoTitle` beside `video_src` —
  the prefix the two keys share. A radio's API answer cached in page state
  (`{audioName, audioUrl: {audioUrl, …}}`), nested or flat; in words only.
- **A format value holding an identifier is no format** (`qualityOf`): a
  list of player INSTANCES (`$PLAYERS[i]`, each with `container:
  "audio_<uuid>"`, the id of its element) read as two formats of one clip
  merged two episodes into one tile titled by the page. A UUID, a long hex
  or digit run in a string dimension is left out; each instance's config
  then names its own episode.
- **A player template's example clip is no capture** (`templateClip` in the
  content script's metadata, `reject:template-clip` in `emitCapture`): a
  `<video>` that has never played, beside a text slot whose whole text is
  one bracketed phrase of three words or more ("[Título de vídeo en
  reproducción]") — a reel modal loaded hidden with its template's own file.
  A bracketed tag ("[VIDEO]") beside a real clip is not one.
Residuals: a slide that never plays; a site whose podcast or bulletin list
names its audio nowhere the bridge or the content script reads.

The **thumbnail** half of the same report is Java: `GlideHelper.load(Browser
DownloadEntity…)` chains a frame decode from the media URL as the `.error()`
request of a video's poster fetch, so a poster the page named but the device
cannot fetch (a JW thumbnail host the player itself shows no poster from)
degrades to the decoded frame, not the glyph. `fallbackListener` now logs the
failed model + root causes, and which model a picture came from (poster or
decoded frame, so a BLACK tile can be told apart too), with the chained frame
decode logged by its own listener — in debug builds and in the diagnostic
release build (`-Pfiredown.diagnostic=true`, see "Logging discipline"): `adb
logcat -s GlideHelper:* CaptureBind:*`. **When the frame decode is chained, IT
owns the tile's outcome** (the poster gets `posterListener`, which logs and
returns true without painting or reporting; the decode gets the reporting
`fallbackListener`). A poster listener that painted the glyph and reported
`onFallback(true)` before the decode started would draw a decoded frame under
the placeholder inks, or leave a blank slot with no report when the decode
failed (a live tile whose poster 403s). **A poster the server REFUSED is not
asked for again for 10 minutes** (`sRefusedPosters`, LRU 256, keyed by URL; a
4xx other than 408/429 found in the root causes): Glide caches successes only,
so every rebind of that tile re-sent the request that had just been refused.
While remembered, the tile goes straight to the frame decode (or the glyph
when there is none). Not cached: 5xx, timeouts, no status. `CaptureBind`
(`BrowserOptionAdapter.logBind` / `logOutcome`, same gate, literal tag) logs
every tile bind with its uid and the picture-or-glyph outcome, and says when
the holder was rebound before its load returned. The repeated requests come
from the FOLD row, not the tile: every capture that changes the fold's count
(a live page streams emotes and badges as captures) is a full rebind of the
fold, which reloads its three previews; a good preview is a memory-cache hit
(not logged), a refused poster a fresh 403 each time. With the refused-poster
skip, the tile and the fold both reach the decoded frame ("poster skipped,
refused recently -> frame decode", then `picture`).

### Captured images are named by the page, not their URL

Without a page name a captured picture takes its URL's slug as its name
(`2026-09-29t084140z-1203290914-rc2usnaifcu…`, `Image%202026-10-19%20at…`,
a random UUID for a URL ending in its host): the catcher asks the page only
for media and `applyDisplayName` leaves images alone. The page names nearly
every picture: an alt, a figure caption, or the card it sits in. Three
layers:

- **Content script (`buildImageMetadata`).** The scanner remembers the
  element each image URL was reported from (`imageEls`: an `<img>`, the
  `<img>` of a `<picture>` for its `<source>` files, the element of an
  inline `background-image`) and its flush builds `{kind:'image', name,
  description}` beside the clips' metadata in the `images-detected`
  report. The name, first usable of: the alt, the title, an aria-label or
  aria-labelledby, the caption of the figure the picture is the ONLY picture
  of (an aria-hidden caption counts: a page can hide its captions because
  the alt says the same), then the card walk (`cardTitleFor` with
  the element's own label skipped and `holdsOtherImage` as the stop rule:
  a node holding another content picture — not an icon of a known width
  under 64 px, an SVG, a data: placeholder or a copy of the same file).
  Usable means: a dateline dropped ("SAN MARCOS, TEXAS - OCTOBER 15: ",
  "San Diego, California - September 04: ", in any month's language), a
  trailing credit dropped (a parenthesis after a sentence's end, "…inundada.
  (LP)", "…2026. (Photo by … / Agency)"; a "Word: Names" tail after a
  sentence, capitalised names only; a © tail — a parenthesis inside the
  sentence, "La Marina Alta (Alicante)", is content),
  closing punctuation dropped, and then not a role label made of the page's
  class-name words (`developerLabel`, shared with the clip walk), a carousel
  position, a credit, a file name, a URL, a spaceless machine token or text
  with no letter; a MARK (an icon, a logo: shown under 64 px on its
  shorter side, or an SVG whose size the page does not declare) is named
  by its own alt, title or label and never by the card walk. Cut at a
  word to 100 characters. The description is what the name left out: the
  caption with its credit, the whole alt when the name was cut (alts can
  run to 900 characters), the picture's own title when it says more than
  the alt (alt "Petrolero", title "Un buque petrolero"), else the card's
  title, never a control's label ("Open main menu"). Card walks are
  budgeted per batch (60 for names, 30 for descriptions, none for a
  known-tiny picture): the naming pass adds ~21 ms to a 384-URL batch, in
  the scanner's timer task.
- **Background (`requests.js`).** `emitCapture` sends an image's reported
  name and description (`pickImageMeta`: strings only, whitespace folded,
  a URL or file name is no name, capped at 150 / 1000). A WIRE capture of an
  image asks the page nothing: a front page holds hundreds of pictures, a
  query each would be a main-thread round trip per picture, and the scan
  reports the same URL with its name a moment later. An image entry on a
  media URL is never taken as the clip's metadata.
- **Java.** `applyDisplayName` keeps an IMAGE or SVG emit's name as is (its
  one-word rule would have turned "Petrolero" + description into "Petrolero
  - <description>"). The entity carries `namedByPage` (the emit had a
  name; parcelled, copied). The wire's unnamed copy usually wins the race to
  the repository, so a later copy the page NAMES gives its name and
  description to the listed one, never the reverse:
  `BrowserDownloadRepository.adoptPageName` for a report `contains()`
  refused before its probe, the same rule in `addValue` for one that was
  probed (`fillPageNameLocked`: not over a forced name or another page
  name, a COPY replaces the listed entity because it may be on screen and
  the diff compares names; media get the same rule plus `pageTitled`), and
  the larger-image
  upgrade keeps a page name its newcomer lacks (`keepPageName`).

Pinned by `frame-meta-harness.mjs` (`images:`, the shapes above), the
smoke's `image-meta:`, and the capture harness's `names:` (the REAL
repository). Mutants, each failing at least one: no credit strip, a
gallery caption taken, no class-word filter, the own title walked again,
no dateline strip, relative background URLs left unresolved, control labels
as descriptions (content script); no image branch, no media guard
(`requests.js`); no adopt, no name kept on upgrade, a fill that mutates the
listed entity, a fill on any type (repository). `har-replay.mjs --images`
lists every reported picture with its name, and the replay prints the page's
long tasks for a cost comparison with `--cs`. An inline `background-image`
with a RELATIVE `url()` is resolved against the document (the CSSOM keeps
the value as written and `queue()` takes http(s) only). Residual: an alt
that is an agency ID followed by words ("DRW5M3 Crowds on the Hong Kong
MTR", "clipped thumbnail - - <Network> ID 23080782 - 00:00:33;16") is taken
as written.

### Capture dedup

Three layers prevent duplicate entries for one video:
- **regex block** (cardinal rule) — keeps the generic catcher off a parser's media;
- **JS sent-origin sets** (`js/parsers/common.js` `sentSet(tabId)` — a
  `tabClaims` set in the tab's `TabState`, dropped with the tab) — per
  **(tabId, page origin)**, 30s TTL. Tab-scoped on purpose: an origin-only key suppressed the same video
  opened in a SECOND tab for the whole TTL (the repository dedups per tab, so
  the global JS key was too coarse). A mixed-attribution guard in
  `alreadySent` collapses emits whose tab resolution diverged (one real tabId,
  one `-1` fallback — e.g. Bluesky's two hls-master listeners) without
  re-suppressing genuine multi-tab captures;
- **`BrowserDownloadRepository.isPresent`** — per `tabId`, then `uid` /
  exact-or-trivially-different URL / image perceptual hash. `uid` is
  `url.hashCode()`, except **HLS_MASTER** keys it on the page origin (signed
  master URLs rotate per refresh), plus the emit's `clipId` when a page holds
  several streams. First capture of a URL wins; a later capture of the same
  URL is dropped (the `GeckoInspectTask.contains()` pre-check skips it before
  it even probes). So when a parser/bridge and the generic catcher both see
  one URL, whichever lands first is the entry — no metadata merge. **ONE
  exception: an IMAGE pHash-duplicate that is materially LARGER upgrades the
  entry in place** (`addValue`'s `imagePixels` comparison — resolution parsed
  from the probed stream's "WxH" info). First-wins inverts quality on gallery
  pages that load a photo's THUMBNAIL first and the full-size copy only when
  the user opens it: the hero would be probed and then discarded as a
  "duplicate" of its own thumb. The pre-probe `contains()` only matches
  url/uid (pHash is 0 before the probe), so the larger copy always reaches the
  comparison; a same-URL dup has equal pixels and never churns. **And a later
  copy the page NAMES gives its name to an unnamed listed capture, and a
  clip's own name to one listed with only the page's title** (see "Captured
  images are named by the page"); the listed capture keeps its place and
  pixels. (Related: `google.<tld>/maps/vt` raster tiles are blocked in
  `regex.js` — dozens of 256px tiles per pan/zoom flooded the sheet's images
  and buried the real photos, which are `lh3.googleusercontent.com` and still
  capture.) **And a VIDEO arriving at two resolutions is one capture, not
  first-wins** — see "Two resolutions of one video are ONE capture" under the
  Captured sheet: a capture with renditions takes the place of the single
  captures of its own renditions, a single of a listed rendition is a
  duplicate, and two singles of one clip fold.

### Captured pin — visit ids, and the PRE-COMMIT re-stamp

The Captured sheet pins "this page's" captures first by a per-tab
navigation **visit id** (`GeckoState.updateVisit`, moved only when the
page-identity key changes; captures are stamped with `visitIdForTab` in
`GeckoRuntimeHelper.handleExtractionMessage`; `BrowserDownloadViewModel.filter`
anchors on the current tab's id). **A document-filter parser emits BEFORE
the navigation commits** — VisitTrace on-device: YouTube's capture (read out
of the main document response) was stamped ~20 ms before `onLocationChange`
allocated the new id, so the video carried the PREVIOUS page's id (0 on a
fresh tab), every later thumbnail carried the new one, and the video sorted
LAST. Same class for Instagram/Threads/TikTok-detail/Telegram. The fix is a
**load sequence**: `onPageStart` bumps `GeckoState.mLoadSeq`, a capture
stamped while a load is pending also carries `pendingLoadSeq`
(`GeckoInspectEntity` → `GeckoInspectTask` → `BrowserDownloadEntity`,
parcelled), and when the commit MOVES the id `GeckoComponents` calls
`BrowserDownloadRepository.resolvePendingVisit(tab, seq, id)`, which
re-stamps matching entries AND remembers the resolution per tab so a capture
still being probed (they land seconds later) is re-stamped on `addValue`.
Don't move `updateVisit` to `onPageStart` instead: a load that never commits
(a download link, a blocked deeplink) would re-anchor the sheet and clear the
tab title for a page that never changed. **A query REFINEMENT keeps the
visit** (`isQueryRefinement`): same host + path (trailing slash normalized)
and one identity query a subset of the other — params only added or only
removed, no value changed — registers the new key as an ALIAS of the current
id with no title reset. VisitTrace on-device: Instagram's "Continue on web"
pushStates `/p/<code>` → `/p/<code>/?l=1`, which used to allocate visit 2
and demote the video captured under visit 1 below the one thumbnail
captured after; same class as a YouTube Mix appending `list=/index=`. A
changed value (`watch?v=A` → `watch?v=B`) still moves the id, with ONE
exception: a **list POSITION** (`POSITION_PARAMS`, today only `index`) may
change while everything else refines, as long as another parameter still
names the item on both sides. VisitTrace on-device (2026-10-15, a Mix opened
fresh on m.youtube.com): the video was stamped under `watch?v=X&list=RDX&
index=1`, the page rewrote itself to `…&index=2` on the same video ~300 ms
later, that allocated visit 2, and the video sat behind "Earlier in this
tab" under the thumbnails captured after. The guard keeps a position-only
query (`viewer?index=1` → `?index=2`, where the position IS the item)
moving as before, and `index` stays in the identity key, so the duplicate-
tab sweep still tells two playlist positions apart. **The
empty→non-empty case is bounded: a ONE-segment path gaining its first query
is a hub→item navigation, not a refinement** (`facebook.com/watch/` →
`/watch/?v=123`, `/photo/?fbid=`, `index.php?id=` — the query IS the item;
the bare subset test accepted it because the empty set is a subset of
everything, pinning the hub's captures over the item's). The two cases the
rule exists for both survive: Instagram's `/p/<code>` has two segments, the
Mix has params on both sides (`pathSegments` in `GeckoState`).
**`img_index` is a position too** (Instagram rewrites it as a carousel post
is swiped, `/p/<code>/?img_index=2` → `=3`), and a position may move with
no other parameter when the PATH names the item (two segments or more,
the same bound the empty→non-empty rule uses); a position-only query on a
one-segment path (`viewer?index=1` → `=2`) still moves the visit.
**A map's camera is not part of the page**: a map site writes the view as
a path segment (`/@40.41,-3.69,17z`) and the panel state as another
(`/data=!3m1!4b1…`) and rewrites both on every pan, zoom and photo while
`/maps/place/<name>` stays, so each moved the visit and the place's photos
fell behind the fold one by one. `pageKeyTail` drops segments matching
`MAP_CAMERA_SEGMENT` (`@<lat>,<lng>…` or `data=!…` — a shape, never a
host; `/@user` handles have no coordinate pair and stay).
**The sheet has a SECOND test for "this page": the capture's origin**
(`GeckoState.originNamesPage`, used by `BrowserDownloadViewModel
.currentPage`). The visit stamp alone cannot handle the feed-then-open
case, which is not URL churn at all: a feed or a profile captures its
items before the user opens one (Instagram's profile grid, TikTok's
item_list, X's timeline), opening it moves the visit, and the item keeps
the feed's stamp — a re-capture cannot re-stamp it, because the parser's
sent-origin dedup and the repository's first-capture-wins both drop it.
So the page's own video sat behind "Earlier in this tab", or under a
closed fold when the item page had captured an avatar. Parsers stamp
each item with its OWN page (Instagram `/p/<code>`, TikTok
`/@user/video/<id>`, X `/<user>/status/<id>`, Facebook
`/watch/?v=<id>`), so a capture also counts as this page when its
origin's identity key equals the tab's or is a query refinement of it.
It is additive (it only adds captures to the page, the visit stays the
anchor), compared by key so m./www., a trailing slash and tracking
params can't split them, parsed once per distinct origin per snapshot,
and decided ONCE into an identity set the sections, counts and fold all
read. Generic-catcher captures carry the page URL they were taken on,
which re-anchoring already covers, so for them it changes nothing.
Known residuals, deliberately not built: a page whose URL is an ALIAS of
the item's canonical one (Instagram's `/reel/<code>/` and
`/reels/<code>/` viewers against the `/p/<code>` origin, Facebook's
`/reel/<id>` against `/watch/?v=<id>`) — swiping reels still files the
prefetched next reels under the reel on screen; and X's photo viewer
(`/status/<id>/photo/1`) is another page until Back re-anchors. Matching
those would need per-site alias rules or a content id beside the origin.
Diagnose with `adb logcat -s VisitTrace:*` — the `stamp` line's
`pendingLoad=`, the `visit new/re-anchor/alias` line, the
`resolve … restamped=N` line, and the sheet dump, where `*` marks a
capture on this page by visit and `o` one on it by origin.
**Verify a visit-rule change with `sh scripts/visit-harness/run.sh`**: it
splices the REAL `updateVisit` / `pageIdentityKey` / `isQueryRefinement`
/ `originNamesPage` out of `GeckoState` (stubbed `android.net.Uri`, JDK +
python3 only) and replays URL sequences: the Mix report, Mix entry from a
plain watch page, autoplay to the next video, Back re-anchoring, the
position-only viewer, another video or list at the same position, the
Instagram `?l=1` and carousel cases, the Facebook hub, a Maps place
panned and browsed photo by photo, handles that look like cameras, and
the origin test (post / TikTok / tweet / Facebook / Mix on their pages,
the profile, hub, photo viewer and another item not). 60 checks; five
mutants must fail: no position params (14), no `img_index` (2), no item
guard (2), no map-camera filter (7), an origin test that never matches (6).

### History row ⋮ — the shared web-options sheet, not a bare delete

The History row's action slot is a ⋮ opening `WebOptionSheetDialogFragment`
(`dialog_web_options`, the same sheet the Bookmarks row uses): Open in new
tab / **Bookmark page** / Share / Delete. It used to be a close glyph that
deleted on tap — the one list whose action slot was a verb rather than a
menu (issue #306, item 10). The sheet is shared by ARGS: `Keys.EDIT` true =
a bookmark's own sheet (edit set), false = a history row (plain set plus the
"Bookmark page" row, inserted in code only when the URL isn't already
bookmarked — `WebBookmarkViewModel.containsUrl`; an already-bookmarked URL
shows no row rather than a second delete door). `Keys.TITLE`/`Keys.ICON`
ride along so the bookmark is built from the row's own facts, mirroring
`WebBookmarkDataRepository.add(GeckoState)` (blank title → null, the URL
fallback). **Delete acts in the sheet's own domain only** (bookmark id vs
history id — the id spaces never meet): the old delete-from-both was a
no-op on the other table except that, with bookmark sync ON, the bookmark
side soft-deleted and fired a sync push for a nonexistent row on every
history delete. Zero new strings: the row reuses
`browser_menu_bookmark_this_page_2` and the `browser_bookmark_saved_toast`
snackbar.

### Captured sheet — the Downloads tile, squares for images, earlier pages folded

The sheet is ONE mixed list since the 2026-10 redesign (canvas "Captured
Content sheet": option A, then board F, then board G after two device
tests). Every capture but an image is a TILE, and the tile is the Downloads
grid tile ITSELF: `BrowserOptionAdapter` inflates
`fragment_download_item_grid.xml`, `capture_tile_columns` to a line (2 on a
phone). Images (GIF and SVG included) are SQUARES, `capture_columns` to a
line (3), under an "Images / N files" header. Captures from the tab's
earlier pages sit behind ONE FOLD row at the end. The grid's span count is
the least common multiple of the two counts (6 on a phone), so both divide
it; keep every qualifier's pair of integers in step. There is no grid/list
toggle and no `Preferences.SORT_LIST`: the layout follows each capture's
type, not a mode.

- **Why the Downloads tile, verbatim (board G, 2026-10).** Board F drew its
  own video tile (a 16:9 picture with the name UNDER it, quality and
  duration on it) beside full-width rows for audio and compact rows for
  subtitles. On-device the maintainer called it "unpolished, breaks
  consistency with downloads layout": a third tile style that exists nowhere
  else, a lone video leaving half a row empty, three kinds of gap. G reuses
  the one tile the app already has — 16:10, the title and
  "VIDEO · 2:01:32 · CC 1" on the bottom scrim, the ⋮ top-end — for video,
  audio, subtitles and files alike, so a page's video and its audio sit
  side by side and the row fills. **A change to that layout is a change to
  both screens**, on purpose; the sheet relies on its ids (listed in the
  layout's header comment). Don't fork a Captured copy of it. The sheet
  paints in code what differs: the card tone (`colorSurfaceContainerLow`,
  the sheet's — the layout's `surfaceContainerHigh` vanishes into the sheet
  while a picture loads), the download badge, and the ⋮ only for a capture
  with qualities to pick (`TileHolder` reports it as `R.id.item_download_more`,
  now declared in `ids.xml`, since the layout's own id is
  `item_download_action`).
- **The tile binds the way Downloads binds its grid tile.** Mime label in
  `mime_text` unfiltered only; facts in `mime_duration` (a video's quality,
  then duration — no size; an audio's size and duration; the CC count on a
  video; the primary fact and size for a file; the domain on earlier pages
  from several sites — see "A capture states what a tap downloads" below);
  `applyTileGround` is
  Downloads' `applyGridTileGround` (scrim + the layout's own white inks on a
  picture, read from the views in the holder ctor; no scrim + FallbackInks
  on the mime placeholder), flipped by the `GlideHelper.load` fallback
  callback. Saved / queued / failed trail the meta row as the self-coloured
  `capture_badge_*` glyphs (`CloudMark.trailingBadge`, the same span
  machinery as Downloads' backup cloud). Selection: the card's 2dp stroke
  plus the corner check, ⋮ hidden.
- **Two things the tile does NOT take from Downloads, both painted in the
  adapter, never in the shared layout** (canvas boards K and Q, picked
  2026-10-15 after the chips went):
  - **A video or audio wears its label in `brandInk` with a play or note
    mark before it** (`capture_kind_video` / `capture_kind_audio`, a
    compound drawable sized to the label and tinted with its ink). With no
    chips nothing else said which tile is the page's media, and on a
    YouTube page most "images" are video covers, the video's own among them,
    so the video read as one more picture. On a picture the label also gets a
    darker, wider halo (`MEDIA_SHADOW`): coral has far less contrast than
    white where a bright poster shows through the scrim's lighter middle —
    measured over a pure-white frame, about 2:1 behind the label against
    white's 5.8:1, fine on real thumbnails. On the placeholder the ink is
    `FallbackInks.status` (the brand coral is 2.2:1 on the light wash). An
    audio-flagged mp4 is labelled AUDIO (the mime alone said VIDEO beside
    the note). Downloads keeps its plain label ON PURPOSE: everything there is
    a file already and its chips filter by type, so colouring one type would
    single it out for nothing — don't "restore consistency" by colouring
    Downloads, or by dropping the colour here.
  - **A running download keeps its picture**: a 3dp bar along the bottom
    edge (`capture_tile_progress`, the squares' `square_progress`, added to
    the inflated tile in `TileHolder` so the Downloads grid carries no view it
    never shows) and "720p · Downloading · 21%" / "Finishing…" IN
    PLACE of the facts line, the way a Downloads row being backed up reads
    "Backing up… · 32%" (the quality is the PICKED rendition's — see the
    next-but-one bullet; an audio or file shows its size there instead).
    The tile used to take Downloads' in-progress shape (the picture swapped
    for `MimeTypeThumbnail.groundColor` and the `ProgressOverlayView` ring),
    which on the page's only video, spanning the row, was a large flat brown
    field with a small ring and no VIDEO label (on-device). Downloads swaps
    the picture because its thumbnail is made from the file being written;
    the sheet has the poster already. A mark payload is now never a full bind:
    facts line + bar only, so a progress tick never reloads the picture.
- **A filename is not a title.** A video or audio tile whose name is a
  filename — `CaptureSections.isFilenameLike`: after stripping a media
  extension, no whitespace and either a long token with 3+ digits (a hash,
  the catcher's `isFilenameLikeTitle` rule) or one short lowercase word
  (`src`, `master`, `videoplayback`) — reads "Video on news.example" /
  "Audio on …" (`capture_video_on_site` / `capture_audio_on_site`, 16
  locales). A name with a space, a capital or a separator is somebody's
  title and stays. A subtitle tile is titled by its LANGUAGE when the parser
  named one, and then carries the file's name in its meta row so two
  "English" tracks of two videos stay told apart.
- **A subtitle its video already offers is NOT an item of the sheet**
  (canvas row "Subtitles ride with their video", board J). A video
  with quality variants wears the ⋮, and the ⋮'s picker has a Captions
  section listing every subtitle of that page (same tab, same origin,
  `subtitlesForOrigin`), with the device language pre-ticked. So the track's
  own tile was a second door to the same file, and the one that cost the
  most room: a whole 16:10 tile for a glyph, and under a page's only video
  it put back the half-empty row the wide tile removes (on-device).
  `CaptureSections.captionDoors` (the origins of videos with variants) and
  `ridesWithVideo` decide it; the view model drops those tracks in
  `snapshot()` BEFORE anything is counted, so the fold's "N files" and the
  empty-page rule count what is shown. **The Subtitle chip went with it**
  (and then the whole rail, see the no-chips bullet below): for one build
  the tracks were hidden only while no chip was checked and listed under
  the Subtitle chip, and the maintainer read it as broken: a filter chip
  only ever narrows, so a chip showing items the unfiltered list lacks
  says the list lost them. Two consistent answers were weighed, show
  every track again or offer them only through the video, and the
  maintainer took the second. What stays: the video's "CC n" (counted over the whole tab,
  unchanged), and the tile of a track with NO door — no video of its page
  captured, or a video with one quality and so no ⋮ (the generic catcher's
  mp4 + `.vtt` is the common case, and it keeps the half row). Showing the ⋮ on a
  captioned one-quality video (a picker with only the Captions section)
  would close that last case; not built yet. Costs, accepted: a riding
  track downloads only beside its video (the picker has no captions-only
  download), its URL can no longer be copied from the sheet, and
  select-all never picks it up. Don't list the riding tracks anywhere else
  in this sheet; the Downloads rail keeps its Subtitle chip, where a
  subtitle is a file.
  Rows were the other answer and are why this exists instead: board F drew
  subtitles as compact rows and on-device they read as a third tile style.
- **Earlier pages are FOLDED, not divided.** F ended the current page with a
  full-width line and a TitleMedium "Earlier in this tab N" heading, then
  every earlier capture; on-device it read as a seam in the list, not as a
  place, and put a whole tab's history in the scroll. Now
  `fragment_browser_options_fold.xml` is one card: three stacked
  thumbnails (`CaptureSections.preview` — pictures first, anything else
  only to fill), "Earlier in this tab", "29 files · youtube.com,
  firedown.app" and a chevron; a tap (or the screen reader's EXPAND /
  COLLAPSE action, so no strings of its own) opens the earlier captures in
  place under it, in the same tiles and squares. **The open state lives in
  the view model** (`toggleEarlier`, which is the sheet's own, so every
  opening of the sheet starts over and a rotation keeps it): closed by
  default; OPEN whenever the page shown now lists nothing — no page, or
  nothing caught yet
  (`CaptureSections.earlierOpen`, the one rule, pinned by the harness) —
  WHATEVER the user chose before, and then it is not a toggle at all: the
  row is drawn as a section heading in the Images heading's shape
  (`collapsible` false → the adapter's `VIEW_EARLIER_HEADING`, no card, no
  chevron, no expand action), because the earlier captures are all the
  list holds and closing them would leave a lone card on a blank sheet.
  That shipped, while the sheet still had filter chips: a fold closed under
  one chip stayed closed under the Video chip on a page with no video, and
  the sheet showed the fold card and nothing else (on-device). That opening LATCHES (`mEarlierOpen = true`,
  over a closed choice too), so a capture landing a moment later doesn't
  snap the list shut under the user. Earlier tiles carry their domain only when the earlier
  pages span more than one site; with one, the fold names it. When the list
  limit cuts before any earlier capture, the fold still ends the list.
  Select-all selects what is shown — with the fold closed, the current page.
  **A TAP that opens the fold scrolls it to the top** once the opened list
  is committed (`BrowserOptionFragment.onFoldClick` → `revealFold`, a
  SNAP_TO_START smooth scroll): the fold usually ends the list at the bottom
  of the screen, so the earlier captures opened out of sight and the tap
  read as doing nothing (on-device). Only the tap scrolls: the view model's
  own auto-open must not move the list, and closing scrolls nothing. The
  flag is set BEFORE `toggleEarlier()`, which publishes the rebuilt list
  synchronously.
- **The VIEW MODEL builds the items** (`CaptureSections.build` →
  `CaptureItem`): the sections, the header and fold facts, each item's
  type-label and domain flags, the CC count and the download mark all ride
  inside the items, so they land in ONE submit with the list they belong
  to. This replaced the adapter-side presentation flags and the
  `submitWithPresentation` commit-callback dance (with its
  `mPresentationDirty`), which existed so a chip tap couldn't paint the old
  list in the new presentation; that bug class is now structurally gone.
  Don't put presentation state back on the adapter.
- **A page's ONLY video spans the row — on a two-column grid only**
  (`CaptureItem.onlyVideo`, set by `CaptureSections.build` when the current
  group holds exactly one video; an audio-flagged
  mp4 counts as audio; `BrowserOptionAdapter.spanSize` widens it only when
  `capture_tile_columns` is 2). On-device a single-video page (Twitch,
  YouTube, a clip or status page — the common case) showed its video in
  one column with the other half of the row empty (canvas row "Portrait: a
  page's only video spans the row", board H chosen). What it deliberately
  does NOT do: widen the FIRST of several videos (on a feed or a live blog
  no video is "the" one, and the first is just the most recent capture, so
  the wide slot would move each time a capture lands); widen whatever is
  left alone in a row (a lone subtitle at four times its size, and a tile
  resizing as the count goes odd and even); widen in landscape or on a
  tablet (3+ tile columns: height is the short side, and a full-width 16:10
  tile would be taller than the visible sheet); widen an earlier page's
  video or an audio-only page's tile (a wide audio tile is mostly an empty
  placeholder). The one reflow it costs is a single-video page whose second
  embed is captured later: the first shrinks back to a column, once. It is
  still the Downloads tile, just spanning, decoded at 1024x640
  (`WIDE_THUMB_*`, applied after GlideHelper's 512x320 so it wins) rather
  than upscaled from the half-width size. The CLAUDE.md Downloads-grid
  rule against a spanning lone tile ("reads as a hero") is about a file
  list's date sections; here the hero reading is the point, which is why
  it is limited to this one tile. No "Video" heading over it (board I):
  the tiles group mixes types and each tile already says VIDEO.
- **Order inside each group**: video, audio, subtitle, other, then images
  (`CaptureSections.typeRank`, for both groups), then most recent first.
  Ranks 0–3 are all tiles; the images must be contiguous for the header.
  The header's and the fold's facts are the whole group's BEFORE the list
  limit, so they don't climb 15 → 25 as the user scrolls in more; the fold's
  sites are parsed once per distinct page, not per capture (it runs on every
  emission). Every tile carries its type label (VIDEO, AUDIO…).
- **The Images header is Downloads' section header** (LabelLarge title in
  onSurface over a BodySmall "N files" from `downloads_group_files`), so the
  two screens' headers read alike as their tiles do.
- **The sheet has NO filter chips; selecting a whole section is the Images
  header's check** (2026-10, after the Subtitle chip went: "makes me wonder
  if chips here are unnecessary"). The rail was Video · Audio · Images · GIF
  over a list that already orders video, audio, other, then the images
  under their header, so on a typical page (one video, its audio, a few
  pictures) a chip only restated the order, and GIF is a kind of image,
  labelled on its square. The one thing a chip did that the order can't,
  Images + Select all, moved onto the header (Google Photos' date-header
  check): while selecting, an "Images" header shows the squares' own
  `brandInk` check or ring at its end, and a tap selects every square under
  it, or clears them when all are selected (`CaptureSections.squaresUnder` /
  `headerAbove`, `BrowserOptionAdapter.toggleSection`; a square's own
  toggle repaints its header, and the screen reader gets "Select all" /
  "Deselect all" as the header's click action). At rest the header is a
  heading and nothing more (not clickable, no check); the earlier-captures
  heading never takes the check. The toolbar's Select all still takes the
  whole list. With the chips went the per-type empty states (one empty
  state), Back-clears-a-chip, `Sorting`'s browser half, `CaptureItem`'s
  `showType` (type labels always show) and the
  `Firedown.Widget.App.Chip.Filter` style. Don't bring a rail back to this
  sheet: a filter on a list this short costs a row to save a scroll, the
  header covers the bulk action, and a chip invites the "chip shows what
  the list lacks" bug the Subtitle chip shipped. The Downloads rail keeps
  its chips; a long file list is where a filter earns its row.
- **A SQUARE** shows the dimensions bottom-left (led by GIF / SVG; the
  maintainer's call: it is the one fact a picture's thumbnail doesn't show),
  then the file's size when the response stated one, and the mark
  bottom-right.
  **Text over a picture follows the app's photo-tile convention, never a
  pill**: `bottom_scrim` plus white text with the grid caption's shadow, and
  self-coloured badge glyphs with a baked shadow
  (`capture_badge_saved` / `_pending` / `_failed`, built like
  `cloud_badge`). The canvas sketches drew black rounded pills behind the
  labels; that shape exists nowhere in the app and was not built. On the
  mime placeholder the scrim drops and FallbackInks take over.
- **Transparent images sit on a checkerboard; small SVGs are icons.** A
  loaded square gets `CheckerboardDrawable` (two mid greys, no night
  variant: black icons read at 4.6:1 or better, white at 4.0:1) behind it,
  set only once the picture is there so a loading square never flashes
  checks. YouTube's player SVGs have no fill colour and drew black on the
  dark tile. An SVG no larger than 96px a side, or of unknown size, is drawn
  inside `capture_icon_inset` with fitCenter instead of stretched over the
  square.
- **Download marks**: `DownloadDao.getCaptureStatesLive(safe, origins)` (a
  projection, `@Transaction`) returns every download that came from the
  shown pages, and `CaptureSections` ties a download to a capture by URL:
  the capture's own, or ANY of its variants' (`DownloadRequest.from` swaps
  the picked variant's URL in; a YouTube download keeps the capture URL).
  When several match, downloading > pending > saved > failed (the user
  retried). A private tab matches vault rows only (`file_safe`), since its
  downloads go to the Safe Folder. An ERROR row with `FILE_NOT_FOUND` gets
  NO mark: that is a finished file deleted elsewhere, not a failed download.
  Origins are capped at `MAX_ORIGINS` (900), under SQLite's 999
  bound-variable limit on Android below 11. A mark-only change is a
  `PAYLOAD_MARK` rebind, so a progress tick never reloads a thumbnail.
- **Batch card** (above the list): after a multi-select download of two or
  more captures, "Saved 3 of 20 · 4 downloading · 13 pending (· 2 failed)",
  with the overall progress along its bottom edge; a tap opens Downloads
  (the Safe Folder in a private tab). The batch is held in the VIEW MODEL as
  entities, because the list limit or a closed fold can hide a member and
  it must still count. A member with no row yet is pending; one whose row was seen
  and is now gone was deleted from Downloads and leaves the batch. It lives
  as long as the sheet.
- **Spacing** is `CaptureSpacingDecoration.attach`: 16dp gutter (the
  sheet's), ONE 8dp gap between tiles and between squares (`list_spacing`,
  the Downloads grid's), 12dp above and below the fold, every column the
  same width. **No item offset depends on its column**: every item gets
  half the gap on each side and the rest of the gutter is the
  RecyclerView's own horizontal padding (set by `attach`, with the
  scrollbar moved outside it). The first G build split the offsets by span
  index, and on-device tiles and squares landed 12dp off one side and flush
  with the screen on the other as captures arrived (measured from the
  screenshot: exactly the other column's insets). RecyclerView caches an
  item's insets until the item is REBOUND, and a capture inserted above
  shifts every item after it into the other column without a rebind. Don't
  reintroduce column-dependent offsets here. Downloads had the same bug
  (`EqualSpacingItemDecoration` then computed each item's edges from its
  position and span index) and moved to that class's `attachUniform`:
  half the spacing on every item, the other half on the RecyclerView's
  padding. Then every other list did too — the tab grid (a tab opened,
  closed or dragged moves every card after it), the Backups list and
  grid (a finished upload inserts a row), Bookmarks, History, the tab
  archive, Storage review and Licenses (in a single-column list an entry
  added or removed at either end changes which row is first or last,
  and the old per-position edge left an uneven gap there) — and the
  per-position mode was DELETED: `attachUniform` is the class's only
  door. Don't bring the per-position mode back. **A list's padding has
  two owners, and both go through `ui/RecyclerPadding`**: the decoration
  sets the EXTRA (`setExtra`, the edge around the items) and the inset
  listener sets the BASE (`setBase`, or `setBaseBottom` for a listener
  that only reserves the navigation bar, the settings list's), and the
  padding is their sum. The Downloads switch shipped without it for one
  commit, and the inset listener every `BaseFocusFragment` puts on its
  list did `setPadding(insets.left, 0, insets.right, insets.bottom)`
  after the decoration had padded the list (insets arrive after attach),
  erasing the edge: tiles 4dp from the screen instead of 8, and a
  re-attach on the grid/list switch did the reverse and dropped the
  nav-bar inset. An inset listener on a list never calls `setPadding`
  directly.
- **A capture states what a tap downloads: a video its quality, everything
  else its size, and the quality picker each rendition's size.** The
  1.2.2 redesign (board G) kept only the duration on a video tile, which
  dropped the "1080p" the old tile led with, and nothing in the sheet or the
  quality picker ever showed a size — reported from ntfy on 1.2.2: two
  copies of one video in two resolutions could only be told apart by
  downloading both. `data/capture/CaptureFacts` is the one rule (pure, in
  the capture harness): the quality is the default rendition's label (the
  first in `FFmpegEntityComparator` order, i.e. what `DownloadRequest.from`
  picks; a copy is sorted, never the entity's list) else the
  `TYPE_QUALITY` tag; the size is, in order, the rendition's EXACT byte
  size (`FFmpegEntity.size` — YouTube's video + audio `contentLength`,
  TikTok's `PlayAddr.DataSize`), the capture's own length (one file — the
  catcher's response), else an ESTIMATE, the rendition's bitrate (bits/s)
  × the capture's duration, shown with a `~` (`capture_size_estimate`).
  Bitrates come from Twitter's variants, HLS masters (AVERAGE-BANDWIDTH,
  else BANDWIDTH — the peak — in both `M3U8Parser` and `parseHlsMaster`),
  Instagram's MPD (video + audio bandwidth) and the probe (all streams of a
  ONE-video file, which is downloaded whole; `singleFileBitrate`). No size
  is ever invented: neither known → none shown. **A VIDEO TILE SHOWS NO
  SIZE** (maintainer, on-device: "don't show the size on videos, makes no
  sense"): it shipped for one build
  as quality · size · duration · CC, and on a video the size is nearly always
  the `~` estimate, a rough number in the one fact line a half-width tile
  has, beside the quality that already tells two copies apart. The size
  stays where it decides something: the picker's row per rendition, an
  audio tile, a file tile, a square. Video order on the tile is
  quality · duration · CC — two copies of one clip share the duration, so it
  goes last; on a half-width tile the tail ellipsizes first. Don't put the
  size back on a video tile, at rest or downloading.
  **The catcher's size** (`requests.js` `responseTotalBytes`, sent as the
  emit's `size`, read by `JsonHelper` into the capture's length): a 206's
  total after Content-Range's slash (the player's ranged fetch — never that
  response's Content-Length, which is the window), a 200's Content-Length
  unless the body is compressed, and nothing for a manifest (URL or
  mpegurl / dash+xml type — the playlist's length); the DOM path takes it
  from its HEAD probe (`probedSize`). Java clears it again when the probe
  or `processHlsMaster` finds a manifest (`GeckoInspectTask`). **The picker
  tile's sub-label is the rendition's size**, with the codec family beside
  it only when the ladder mixes families (`mixedVideoCodecs` — YouTube's
  AV1 rungs above 1080p) and the old codec pair only when no size is known.
  **A running download names the PICKED quality**: `DownloadRequest` stamps
  a video rendition's frame size as the download's `file_resolution`
  ("1280x720"; an image's own tag wins), `getCaptureStatesLive` projects it
  into `CaptureMark.resolution`, and the tile finds that rendition by its
  frame size for the label and size; a row naming none on a capture with a
  choice shows no quality (an audio-only pick must not read as the best
  video). The same column puts "Resolution" in the Downloads info sheet for
  a video downloaded from a quality list. Pinned by the capture harness's
  `facts:`/`mark:` checks (mutants: the capture's length ignored, an
  estimate not marked, the entity's list sorted in place — each fails
  one), the smoke's `size:` and `e2e:` checks, `parsers-replay.mjs`'s
  TikTok size check and `instagram-replay.mjs`'s DASH bitrate check.
  On-device unverified at write time.
- **Two resolutions of one video are ONE capture — the quality ⋮ exists
  only on a capture with renditions.** The same 1.2.2 report, which the
  maintainer then pinned down: the reporter's button was not PRESENT. The ⋮
  shows only when `getHasVariants()`, and a video whose renditions arrived
  as SEPARATE captures had none — two bare tiles, "choose one at random or
  all of them". Two producers made that shape and both are closed:
  - **The bridge reads a DECLARED quality ladder on any page**
    (`page-state-bridge.js` `findPlayerMedia` / `isQualityLadder`). Its DOM
    read used to run only in a frame that looked like a player
    (`looksLikePlayerFrame`), so on a plain page a `<video>` with
    `<source size="1080">` + `<source size="720">` reached the background
    only through the content script, one `<source>` per capture. Outside a
    player it now keeps an element whose video sources name two or more
    heights (`size`/`label`/`data-quality`, or a `720p`-style file name);
    format fallbacks (webm + mp4, no heights), a lone src and two separate
    `<video>`s are untouched. The emit is the ordinary
    `page-state-progressive` group (claims, media-element headers — the
    same no-Referer request shape player frames already ship). Pinned by
    `node scripts/bridge-dom-harness.mjs` (the REAL bridge in Chromium;
    4 of its 8 checks fail on the previous bridge).
  - **The repository keeps one capture per clip whatever arrives first**
    (`data/capture/CaptureRenditions`, pure, used by
    `BrowserDownloadRepository`). (1) `listsUrl`: a capture of a URL a
    listed capture offers as a rendition is a duplicate (in `isPresent`, so
    the pre-probe `contains()` skips it). (2) `covers`: a capture WITH
    renditions takes the place of single captures of its own URL or its
    renditions' (the content script's `<source>`s landed before the
    bridge's list); `contains(entity, renditions)` lets a capture offering
    several qualities through past a listed single of the same URL, or the
    pre-probe check would stop it. (3) `otherRendition`: two single
    progressive captures FOLD into the first (it keeps its uid, place,
    title and thumbnail; each rendition gets its file's URL and the
    capture's length as its size; the capture-level length and quality tag
    go) only when every piece of evidence agrees — same tab, same page,
    same host, URLs whose paths differ ONLY in a resolution token
    (`_720p`/`_1080p`, `/720/`, `1280x720`, `hd`/`sd`; ladder values only,
    so a 3-4 digit clip id is never taken for one), different heights, the
    same frame shape, durations within 1% (0.25–1 s). Hash-named renditions
    with no declared ladder stay two captures — each tile states its
    quality and size, which is the floor. The fold COPIES (the listed
    entity and its streams may be on screen; `FFmpegEntity` has a copy
    constructor for it, keep it in step with the Parcel). Pinned by the
    capture harness's `renditions:`/`repo:` checks, which run the REAL
    repository against inert Handler/LiveData stand-ins; mutants: no fold,
    `contains` ignoring renditions, `covers` never true, the URL test
    without the resolution token, a fold that mutates the listed streams —
    each fails. On-device unverified at write time.
- Verify with `sh scripts/capture-harness/run.sh` (the REAL
  `CaptureSections` / `CaptureMark` / `CaptureItem` /
  `CaptureItemDiffCallback` / `CaptureFacts` / `CaptureRenditions` /
  `BrowserDownloadRepository`; 116 checks; mutants that ignore the fold's open
  state, show the domain with one site, drop the short-word filename rule,
  skip pictures-first in the preview, drop the fold when the limit cuts
  first, count an audio-flagged mp4 as a video, let an earlier "closed"
  choice win over an empty page, leave `collapsible` or `onlyVideo` out of
  the diff, widen the first of several videos, widen an earlier page's
  video, count a one-quality video as a caption door, keep every track
  in the list, let a header's check take squares past the fold, let a
  square find a header past a tile, or drop the header over the page's
  images each fail it). On-device
  unverified at write time.
- The bare "timedtext" subtitle that sat beside YouTube's titled caption
  tracks was the generic catcher capturing the player's own caption fetch;
  it is parser-block-listed now (see the YouTube section).

### Captured row action slot — the ⋮ only; Copy is multi-select; Share/Open DECIDED AGAINST

The Captured tile has ONE action slot (`BrowserOptionAdapter` +
`BrowserOptionFragment.onItemClick`; it is the Downloads tile's top-end ⋮,
reported as `R.id.item_download_more`) and it shows
ONLY the ⋮ of a multi-variant capture → the quality picker (whose toolbar
carries NO actions — a Copy URL icon lived there and was REMOVED: it copied
the root/page URL, never the highlighted tile, which on a "Download quality"
sheet read as "copy this rendition", and on YouTube it copied the watch-page
link already in the address bar). Every other row shows NO button. **Copy URL
is long-press → multi-select** (`menu_capture_action`, every selected URL one
per line), tiles and squares alike; the URL is `CaptureUrlActions.externalUrl` —
the ROOT manifest / page URL, never the highlighted tile (bitrate belongs to
whatever the URL is handed to). History, two rounds: issue #302 (an
internet-radio user pasting a stream into VLC) first got a per-row COPY
glyph on every list row with a plain http(s) URL, one tap, no menu — the grid
never had it (a glyph stamped on every tile over the artwork was too heavy
on-device) — and it was then REMOVED from the list too at the maintainer's
request: a sheet where most rows are page images nobody copies wore a glyph
on every one of them, the same noise the grid call had already named. The
multi-select door is one long-press away and was the reporter's workflow
anyway. The slot state still carries a `contentDescription` — the reporter's
screen reader announced the old button as a bare "Button"; keep it set in
every bind. **1.1.93 shipped a per-item in-sheet menu page (Copy / Share /
Open in another app / Select quality) and it was REMOVED**: a capture is
URL + headers + cookies, and neither a share text nor an ACTION_VIEW intent
can carry the headers a gated URL depends on, so Open failed silently on
anything signed, double-played the stream the page was playing, and needed
its own mime-inference round; Share is one tap away inside the clipboard/
share flow the copied URL already feeds. Don't reintroduce the menu page,
Share, Open, or a per-row copy glyph without a header channel to hand them
(the `<queries>` http/https VIEW entry that Open needed is gone with it).
The reporter's follow-up asks (a View-URL dialog, an `.m3u` hand-off,
stopping our playback on Open) are a resource-inspector feature set, not
this sheet's job.

### Capture "scanning" indicator

`PriorityTaskThreadPoolExecutor` exposes an in-flight task count
(`getInFlight()` — incremented at submit, decremented in the run `finally` so
aborts count too). `BrowserOptionFragment` observes it via
`BrowserDownloadViewModel.getInflight()` and shows a small brand-orange spinner
in the toolbar whenever busy — debounced (show-now + ~500 ms
hide-linger so it doesn't strobe; decoupled from whether the list is empty, so
an empty sheet still shows work arriving). The empty list uses the LCEE loading spinner.
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

### Start with the device audit — the real app browses the sites itself

A news-site report goes: browse on the phone, see a tile named after the
page or by its URL, export a HAR, debug the HAR.
**`scripts/device-audit.sh` does the browsing on the phone and the judging on
the laptop**, for every site in `app/src/androidTest/assets/site-audit/sites.txt`
(front pages and video sections of ~50 news sites in six languages, plus the
pages past reports came from) or the URLs given:

- **Nothing is simulated.** `SiteAuditTest` (androidTest,
  `com.solarized.firedown.siteaudit`) loads each site in a real GeckoView in
  `SiteAuditActivity` (debug source set: a status line over the view, screen
  kept on, shown over the keyguard), with the app's runtime, the app's tab
  session settings, every bundled extension (catcher, parsers, bridge, uBlock,
  youtube@) and the real native pipeline down to `BrowserDownloadRepository`.
  The phone's fingerprint, network and Gecko are what the site sees, so a bot
  wall or a Gecko-only code path is simply what happens. **This replaced a
  Chromium simulator** (`site-audit.mjs`: Playwright's Chromium dressed as
  Firefox for Android, with the content scripts and the background graph fed
  by hand). It was removed (2026-10-20, maintainer: "either we hit Cloudflare
  anti bot or completely fails"): too many sites served a headless datacenter
  browser a challenge or nothing, and what it did capture came from a
  re-implementation of the wiring, not from the app. Don't bring it back; the
  HAR replays (`har-replay.mjs`, `har-wire-replay.mjs`) stay for the
  offline, one-page case.
- **Per site** it agrees to the consent wall (known CMP buttons, then any
  "accept/aceptar/akzeptieren…" button, every frame, up to three rounds),
  waits, scrolls the page through, waits, then waits until the tab's captures
  and capture messages stop changing (6 s quiet, 45 s at most), and writes to
  the app's external files (`site-audit/<run>/<NN-site>/`):
  `entities.json` (the repository's own list for the tab, with the title the
  sheet SHOWS — `BrowserOptionAdapter.tileTitle`, made public for this — so
  the dedup and the "Video on <host>" rule are the app's, not a copy),
  `emits.json` (every capture message the extensions sent, the copies the
  dedup dropped included, through the debug-only
  `GeckoRuntimeHelper.setCaptureTap`; cookies, tokens and auth headers
  redacted), `census.json` (every frame's media elements, embeds,
  click-to-load placeholders and page names), `around.json` (for each
  captured clip, the elements whose attributes or inline scripts hold its
  URL, file name or poster, with their card's markup and the data-* config
  beside them), `requests.json` (the tab's requests and the extensions' own,
  with status, error and redirect), `screen.jpg`, `site.json` (timings,
  consent clicks).
- **The page work is a DEBUG-ONLY extension**, `app/src/debug/assets/siteaudit`
  (`siteaudit@solarized.dev`): a background page that answers the test over
  the `siteaudit` native port (tabs, frames, the request log; `run` sends an
  op to every frame's content script) and a content script that is inert
  until asked (`consent`, `scroll`, `census`, `around`). Nothing in it
  blocks a request. The app never registers it: the test removes any
  leftover registration, installs it fresh (so a changed content.js is never
  shadowed by the `ensureBuiltIn` version cache, and the version can stay
  1.0), and uninstalls it at the end. Release builds don't contain it.
- **The phone collects, the laptop judges** (`scripts/device-audit/judge.mjs`,
  so a rule can change and a run be judged again with no phone). Per tile:
  `untitled` (a file name, so the sheet shows "Video on <host>"), `generic`
  ("Vimeo video", "Video Player", "Reproducir"), `site-name`, `page-title`
  (the page's title on a page with several clips),
  `duplicate` (two tiles, one name and one shape: a portrait and a landscape
  cut of one story are a note); from the emits, the lost name ("a copy
  named … came 400 ms later via catcher (contentScript) and was dropped");
  from the census, possible misses (a `<video>`/`<audio>`
  file nothing captured, with what became of its request — "blocked
  (NS_ERROR_ABORT, uBlock)", an HTTP status — more streaming players than
  video tiles, an embed or click-to-load placeholder with no capture). For
  each problem it prints the names the page has around the clip ("page has:
  "<title>…" (data-shorts JSON on <ul>)"), which is usually the
  fix's whole question. A bot wall or a page that never answered is reported
  as such and not judged. `--compare <older run>` says what changed (renamed,
  new, gone, problem count) per site, matched by URL: run before a fix,
  run after. Identical problems collapse to one line with a count, a
  player's own chrome ("Progreso de la
  reproducción", "Tiempo actual: 00:00") is never offered as a name, the
  needles skip a thumbnail several tiles share (the site's logo) and generic
  file stems ("playlist", "manifest"), and `around` skips the `<head>`'s meta
  and link tags, which spent every needle's budget on the logo.
  `node scripts/device-audit/selftest.mjs` pins the rules (29
  checks on synthetic runs; mutants — no lost name, no page-title rule, no
  wall, captured files counted as misses, embed tokens ignored, no data-*
  JSON names, no player-chrome filter, no collapsing, no cuts split, an
  unknown shape taken for a cut — each fail it).
- **The loop**: phone on USB, `scripts/device-audit.sh [--only site]`, read
  the report, fix the rule (generic, never a host check — the card-walk and
  image-naming sections say where), rerun with `--compare` on the same sites.
  The run folder (`build/device-audit/<run>/`, gitignored) also holds the
  whole `logcat.txt` of the run, where the catcher's reject reasons are.
- **What it costs and assumes**: about a minute per site; the media volume is
  muted for the run (`--keep-volume` to leave it) and stay-awake turned on and
  back off; the phone must be unlocked or have no secure lock. Each site is a
  fresh tab on the app's own profile, so a site sees the phone's cookies (a
  consent already given is remembered, a login is present). The test runs
  in the app's process, which `am instrument` restarts: close the app first
  if a download is running. Not covered: a play tap (a clip the page only
  fetches on play stays a "possible miss"), login walls.

1. **Confirm it's a debug build.** All parser logs are gated on
   `BuildConfig.DEBUG`. The extension fetches it at boot via
   `get-debug-flag` (`background.js` top; answered in
   `GeckoRuntimeHelper` ~line 322). Release builds log nothing.

2. **Read the logs by category.** `adb logcat -s GeckoConsole:*` then grep the
   prefix: `TWITTER`, `INSTAGRAM`, `THREADS`, `THREADS-CS`, `FB-*`, `IG-*`,
   `RUMBLE`, `TWITCH`, `KICK`, `VIMEO`, `DAILYMOTION`, `TIKTOK`, `NOA`,
   `SPOTIFY`, `SUBSTACK`, `ACAST`/`ACAST-WIRE`/`ACAST-PAGE`, `PAGE-STATE`, `VARIANTS`,
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

   - For the GENERIC catcher and the page-state bridge (no parser), the
     "algorithm" is the page's own JS plus our content scripts, so replay
     the page: `node scripts/har-replay.mjs <har> --background` serves the
     HAR to Chromium, injects the real content script and bridge, and feeds
     what they report to the real background graph, printing every native
     emit with its name and picture. `--init file.js` stands in for what the
     HAR lacks (a consent SDK the device blocked, a player script loaded
     late). It answers in one run what reading a HAR with no stream request
     cannot. It also prints
     click-to-load placeholder reports and the background's own fetches,
     and takes the page from the HAR's page record (`--url` overrides): a
     HAR's first entry can be an embed the page prefetched.
   - For the WIRE (junk or duplicate tiles), `node scripts/har-wire-replay.mjs
     <har>` needs no browser: it walks the HAR's requests in order through
     the real webRequest listeners (catcher and every parser, real match
     patterns, the type taken from `Sec-Fetch-Dest`, each body fed to the
     filters a listener opened) and prints every native capture, so each
     junk or duplicate tile is attributed to its listener. The two replays are
     complementary: neither runs the other's half. `har-replay.mjs
     --images` also lists every reported picture with the name the content
     script gave it.

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

#### Instagram — SSR doc filter first; the shortcode GraphQL fetch is only a fallback

A post/reel page (2025 shape, HAR-verified logged-out) SSR-inlines the media
item into the document's `<script data-sjs>` Relay blobs —
`result.data.xig_polaris_media.if_not_gated_logged_out` is the item
(`video_versions` with type-only tiers 101/102/103, usually the SAME url —
the lean-item shape `buildInstagramVariants` dedups; `user.username`,
`caption.text`, `image_versions2`, no `video_duration`) — and the page's own
graphql XHRs carry ONLY experiments + Bloks login-wall payloads. So
`listenerInstagramPage` is a **main_frame doc filter** (`readFilteredBody`,
the Threads pattern — read the network response, never the DOM) running the
shared media-item walk; the parser's own GraphQL fetch by shortcode
(`fetchInstagramByShortcode`, `doc_id`-based — the thing that broke when the
API changed) fires ONLY when the doc yielded no media. The XHR filter's
graphql scan also unwraps the `if_not_gated_logged_out` gating wrapper for
SPA navigations. **The media-item walk lives in `instagram.js`**
(`collectInstagramMediaItems` + `walkInstagramMediaItems` /
`instagramItemRichness`) **and `threads.js` imports it** — one walker for the
one Meta item shape, so the depth-cap class of bug can't reappear in a
drifted copy (the import direction is threads→instagram, matching the
existing `sendInstagramItem` import; don't fork it back). The new fbcdn
`o1/v/…AQ….mp4?…` URLs still match the `instagram.*\.mp4` block rule
(HAR-verified), so the cardinal rule holds unchanged — and it covers the
DASH track URLs below too (same host/extension shape).

**Inline DASH (`video_dash_manifest`) is the primary source when present —
and on dash-eligible clips the "progressive" URL is a SILENT video-only
file.** Meta's MPD is the Bilibili model: SegmentBase, each Representation
one whole-track fbcdn `.mp4` BaseURL (XML-escaped — `decodeHtmlEntities`),
so a rendition downloads as a video+audio URL pair merged by
`FFmpegMergeStrategy` — no manifest is handed to ffmpeg, no `manifest` flag.
HAR-verified: the reel's `video_versions` URL has the IDENTICAL PATH to the
MPD's 720p avc1 Representation BaseURL (844-byte single-track init), and the
live player range-fetches that very URL as its video track while pulling
audio from a separate file — i.e. `video_versions` on these clips IS the
video-only DASH track, and a progressive-only capture downloads no audio.
`buildInstagramItemVariants` (used for the item AND each carousel entry)
therefore emits the DASH renditions (each paired with the best audio
rendition's URL) and appends only progressive rows that are genuinely
distinct files — same-path rows (the silent track) and height-duplicates are
dropped; with no/unparseable MPD it reduces exactly to the old
progressive-only list, and a video-only MPD (no audio AdaptationSet) emits
unpaired renditions. The MPD's `mediaPresentationDuration` fills in
`duration` for the lean SSR item shape (which carries no `video_duration`);
separate-audio pairs still probe at capture (the `sendVariants` rule), so
codecs/duration land regardless. `parseInstagramDashManifest` is a regex
reader over Representation blocks on purpose — the manifest is one
machine-generated shape, and this keeps it runnable under node for the
HAR-replay tests (no DOMParser). `isInstagramMediaItem` also matches
DASH-only items (`video_dash_manifest` with no `video_versions`), so the
shape walk can't miss them.

**Robustness architecture — the shape walk is the backbone, wrappers are
fast paths.** The item shape (`video_versions`/`carousel_media` + `code`)
has been stable across Meta surfaces for years; the WRAPPERS (query names,
gating objects, edge nesting, endpoint paths) churn every few months, and
every historical break was a wrapper change. So the parser is built so a
wrapper change degrades metadata precision at worst, never loses the video:
- **`walkAndSend` (the bounded shape walk) runs on EVERY filtered API
  response, after the specific handlers** — not only when they found
  nothing, because one response can mix a known shape with a new wrapper.
  The walk matches every item family by SHAPE: `video_versions`,
  DASH-only (`video_dash_manifest`, top-level or `dash_info`-nested),
  carousel parents, AND the old web-GraphQL `video_url` node — so a
  wrapper rename can't lose any of them. `parseInstagramQuery` stays for
  its precise sidecar-child semantics (per-node thumbnails/durations),
  not as the only door to the `video_url` shape anymore. Overlap is
  collapsed by the sent-origin dedup: both paths emit the same
  canonical origin, so a walk re-find of a handler-sent item is a no-op.
  Handlers emit FIRST so their richer records win the repository race.
- **Item identity is pk-first** (`instagramItemKey`: `pk || id || code ||
  shortcode`): pk is the canonical media id both the rich record and the
  lean Relay fragments of one clip carry, so richness folding survives a
  copy that lacks `code`; a code-less item still emits (origin falls back
  to `/p/<pk>` — origins are identity, not fetchable URLs). Field
  fallbacks in `sendInstagramItem` span both families (`caption.text` /
  `edge_media_to_caption`, `user.username` / `owner.username`,
  `code`/`shortcode`). The walk does NOT descend into a matched item's
  `carousel_media` — slides emit via the parent's per-slide `dedupKey`,
  and collecting them standalone would double-emit each slide.
- **`IG_API_PATTERNS` carries NO URL knowledge — `*://*.instagram.com/*`,
  every XHR/fetch on every instagram.com host — and the gate is CONTENT.**
  History that forced it: the list was `/graphql*` + `/api/*`, and a HAR
  (logged-out MOBILE `/p/<code>`) showed the item had moved to
  the Comet router, `POST /ajax/route-definition/` (line 2 of its NDJSON
  body), while the document SSR-inlined only a lean "landing page upsell"
  shape (`video_image`, NO `video_versions`). The shape walk found the item
  at depth 5 the moment it saw the bytes — the miss was the path gate, and
  every endpoint rename repeats that class (the doc_id GraphQL fetch broke
  the same way earlier). Now: the filter is pass-through, bodies over
  `IG_MAX_BODY_BYTES` (8 MB) are passed through unread, a body that neither
  starts like JSON nor carries an item marker (`IG_ITEM_MARKER_RE`) is
  skipped before parsing, and the walk no-ops on media-less JSON. Don't
  re-add a path list. The doc filter is NOT always the permalink's primary
  any more; whichever body carries the item is. Fixture
  `route-definition.ndjson` in the replay pins the router case.
- **Behind the walk sits a STRING-SCAN fallback (`scanEmbeddedItems`)** —
  runs ONLY when a body contains an item marker and the walk emitted
  nothing, so the common path never pays for it. It closes the two things
  a JSON walk structurally cannot see: (1) an item serialized as a STRING
  inside the JSON (Meta's Bloks `{"payload":"{\"…\"}"}` shape) — the
  escaped marker locates the enclosing string token, which is JSON-decoded
  and parsed as a body of its own, recursively and bounded; (2) an item past
  the walk's depth cap / node budget or under a container it doesn't
  descend — the plain marker is brace-matched outward (string-aware) to the
  smallest parseable enclosing object, **bounded per candidate and per call**
  (`IG_SCAN_MAX_SPAN` 512 KB, `IG_SCAN_CHAR_BUDGET` 4 MB): an item is tens
  of KB, a span past the cap is a wrapper the walk already parsed, and
  without the bound each of the 64 candidate `{`s scanned to the END of an
  unbalanced 8 MB body — up to 64 full passes per marker on the background
  page, which delays every capture on the tab (measured: ~3 s → 48 ms on a
  synthetic 8 MB body). Everything it finds still flows
  through `walkAndSend` → `sendInstagramItem` (same dedup, permalink gate,
  metadata rules), and it logs under `IG-SCAN` so a HAR that needed it says
  "walk missed, scan caught". Section 8 of `instagram-replay.mjs` pins both
  blind spots (teeth verified by mutation: with the scan disabled, both
  fail). This is the answer to "should we just regex?" — a bare regex over
  the body is no more wrapper-proof than the walk (same keys), loses the
  item (no code/author/DASH pairing, no permalink gate), false-positives on
  JS bundles that mention the keys, and would not have seen a body the
  filter never opened; the scan keeps regex to LOCATING and hands the
  object to the same parser.
- **NDJSON fallback + prefix tolerance**: a body that fails whole-JSON
  parse is re-parsed per line (Meta streams deferred GraphQL payloads
  newline-delimited), and both passes go through `tolerantParseJson` —
  on a parse failure it retries from the first JSON delimiter, so an
  anti-hijack prefix change (`for (;;);` today, `while(1);` elsewhere)
  can't kill the parse.
- **Doc filter hedge**: if the `data-sjs` pass finds nothing, rescan every
  `type="application/json"` script (attribute-rename insurance), and only
  then fall back to the shortcode GraphQL fetch.
- **A PERMALINK document emits only the ADDRESSED item** (the doc-filter
  gate on the URL's shortcode): a logged-in reel/post page preloads
  SUGGESTED/next clips into the same SSR blobs, drawn from recent activity
  — on-device that surfaced as "a video from my other Instagram tab
  appears in this tab's Captured sheet" (the suggestion engine had picked
  the just-watched clip). Suggested reels the user actually swipes to
  arrive as XHRs the API filter captures then, so nothing viewed is lost.
  Falls back to emitting all collected items when NO item matches the
  shortcode (a wrapper that renamed `code` must degrade to over-capture,
  not a miss); feed/profile/explore docs (no shortcode) keep
  capture-everything.
- **The doc filter runs on ALL `www.instagram.com` main_frames, not just
  `/p/`+`/reel/`**: the logged-in HOME FEED (and profile/explore) documents
  SSR-inline the first screenful of posts into the same `data-sjs` blobs
  (HAR-verified, `xdt_api__v1__feed__timeline__connection` — the
  scroll-time copies arrive as `graphql/query` XHRs the API filter reads,
  but the SSR ones exist nowhere else on the wire, and with fbcdn media
  block-listed a path-gated registration lost them entirely). The GraphQL
  fetch fallback stays shortcode-gated, so a feed/profile document never
  fires it.
- **Carousel slides emit with a per-CLIP `dedupKey`**
  (`origin#<slide pk>`): all slides share the post's `/p/<code>` origin, and
  `sendVariants`' origin-dedup dropped every carousel video after the first
  (HAR-verified: a 20-slide carousel with two videos emitted one). The pk is
  stable across re-reads where the signed URL rotates, so refresh dedup
  still holds.
- `sendInstagramItem`/`parseInstagramQuery` return their EMIT COUNT — that
  is what gates the fetch-path fallbacks; keep the returns accurate.
- **The SPA handler (`checkAndProcessInstagramUrl`) is the LAST-RESORT
  path — one DEFERRED decision per (tab, shortcode), never a fetch per
  tick.** `tabs.onUpdated` fires for the same URL 3-4 times per load (the
  url change, then every `status=complete`), and the handler used to run the
  shortcode GraphQL fetch on each one, on top of the capture the doc/router
  filter had already emitted — a logcat full of "SPA navigation detected /
  Fetching by shortcode" that was reported as "something is stuck in a
  loop" (it wasn't; the other lines in that log were the host-wide filter
  logging Instagram's `/ajax/bz` beacon every ~2 s, now silent for empty
  bodies, and the generic catcher's `[req]` reject lines per DASH byte-range
  chunk, pre-existing). Now: `spaSeen` collapses ticks per (tab, shortcode)
  for 30 s, and the decision runs after `IG_SPA_GRACE_MS` (2.5 s) — fetch
  only if `alreadySentUnder(origin, tab)` is false, i.e. neither the origin
  nor any `origin#slide` dedupKey was emitted (a carousel whose only videos
  are slides never marks the bare origin). A sanctioned timer: the wait is
  on the filters' response, and the fallback (fetch anyway) is correct
  either way. Pinned by replay section 9 (four ticks → one fetch after the
  grace; a router-captured page → zero; a second tab → its own; a
  media-less permalink DOCUMENT fires no immediate fetch and exactly one
  deferred one). **The doc filter's "no media" branch goes through the same
  `scheduleShortcodeFallback`**, never a direct fetch: a photo post used to
  cost TWO credentialed doc_id requests per view (the doc filter's immediate
  one plus the SPA handler's 2.5 s later, nothing having been sent under the
  origin), and on a logged-out mobile permalink the immediate one raced the
  route-definition XHR the API filter was about to emit from. The
  `pendingShortcodes` claim is released by a single `finally` that covers the
  tab/cookie awaits too, and the fetch carries a 15 s `AbortSignal.timeout`.
Verified by HAR replay driving the real registered listeners: unknown
wrappers/endpoints/NDJSON/renamed-attribute shapes all still capture, the
HAR's real login-wall Bloks bodies emit nothing (their payloads are
serialized STRINGS, which the walk correctly can't see into), and mixed
known+unknown responses emit each item exactly once. **The regression net
is `node scripts/instagram-replay.mjs`** — it drives the real registered
listeners (real match-pattern semantics included) with SANITIZED fixtures
under `scripts/fixtures/instagram/` (structure/nesting from real HARs;
identities, captions, locations and URL signatures scrubbed): home-feed
SSR doc, feed graphql with carousel + explore_story nesting, the gated
reel doc with inline DASH, the legacy `video_url` node under an unknown
wrapper, prefixed NDJSON. Run it alongside `webrequests-smoke.mjs` after
any Instagram/Threads walker change.

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
- **For Instagram/Threads walker changes, ALSO run
  `node scripts/instagram-replay.mjs`** — the fixture-backed replay of the
  real registered listeners (see the Instagram section).
- **For click-to-load placeholder changes** (the content script's scan, the
  `common.js` queue, any site's placeholder handler), ALSO run
  `node scripts/placeholder-harness.mjs` (Chromium) plus the replay of the
  site whose handler changed — see "Click-to-load player placeholders".
- **For Dailymotion changes, ALSO run `node scripts/dailymotion-replay.mjs`**
  — same fixture-backed pattern (a sanitized publisher embed HAR under
  `scripts/fixtures/dailymotion/`): pins the embed config+details capture,
  the rename-proof shape walk on both config shapes, and the wire-master
  backbone (generic title, cache enrichment, emitted-claim suppression, and
  the master-beats-API RACE — the player fetches the master the instant it
  has the config, so the backbone must defer via the synchronous `apiSeen`
  claim + bounded grace, or it emits "Dailymotion video" and suppresses the
  titled emit; shipped on-device before the grace existed). Its `geo-error:`
  check pins the filter-ERROR fallback: the geo reader is the shared capped
  `filterResponseText` now — the private reader it replaced buffered without
  a cap and called `filter.close()` bare in `onerror` (Gecko throws there),
  so its "falling back to re-fetch" branch never ran. The `/details`
  enrichment fetch is bounded BELOW the backbone's grace
  (`DM_DETAILS_TIMEOUT_MS` 3 s < `DM_MASTER_GRACE_MS` 5 s) — a slower one
  let the backbone take the emit with the generic title and drop the titled
  one. Its `per-tab:` checks pin that **the emit and API-seen claims are
  per TAB** (`dmEmitClaims` / `dmApiSeen`, `tabClaims` in the tab's
  `TabState`) while the title/duration/poster in `dmEmbedCache` stay shared:
  they used to be fields of the per-VIDEO entry, so once any tab captured a
  video, every other tab's API path returned SILENTLY and its backbone
  skipped too, for the cache's whole 30-min TTL (on-device 2026-10: a fresh
  tab logged "Fetching geo API" and then nothing). The tell in a log is that
  silence — every other exit after the geo fetch logs. An embed's signed
  stream URL is still shared, so a tab emits from it only after ITS OWN
  config arrived (`dmConfigSeen`) — never another tab's possibly-expired
  master. Teeth by mutation: one shared claim set fails seven checks, no
  config gate three. Its `placeholder:` checks pin the click-to-load path
  (see "Click-to-load player placeholders").
- **For TikTok / Bluesky / Facebook / Vimeo / Rumble / Kick / Twitch /
  Niconico / Apple Podcasts / News Over Audio / Videee / Deezer / Substack /
  Acast changes, ALSO
  run `node scripts/parsers-replay.mjs`** — listener-level replays of those
  parsers with SYNTHETIC shape-faithful bodies (built from each parser's
  documented wire shapes; no HAR fixtures). It's a regression net — a
  refactor can't silently break an extraction path, a dedup/enrichment
  cache, or an emit field — NOT proof the shapes still match the live site
  (a "site changed its API" bug needs a fresh HAR + this section's steps).
  Teeth verified by mutation: capping TikTok's deep-walk depth fails it.
- **For Deezer decrypt changes, ALSO run `sh scripts/deezer-harness/run.sh`**
  — a JDK-only harness driving the REAL `DeezerCrypto` (copied from app/src,
  same package) against Blowfish key-derivation known-answer vectors from an
  INDEPENDENT implementation (node crypto), a full encrypt→`decryptStream`
  round trip (every-third-stripe rule, verbatim tail), the stripe-alignment
  invariant (a 1-byte-per-read stream must yield the identical file), and the
  progress-hook abort. `DeezerCrypto` is deliberately Android-free so it runs
  with no stubs. The Blowfish SECRET breaking silently is the documented
  Deezer failure mode — this pins the derivation.
- **"Did the SITE change?" has two nets, both live-network and NEVER in CI:**
  - **`node scripts/live-canary.mjs`** (any normal network, not the agent
    sandbox — its egress blocks these hosts): fetches TODAY'S real endpoints
    for the session-free sites (Dailymotion embed config→details→signed
    master, Bluesky searchPosts, Vimeo player config, Apple iTunes Lookup,
    Rumble embedJS with a homepage-discovered id) and runs the REAL
    listeners on the live bodies. Statuses: PASS / FAIL (reachable +
    media-bearing but the real code extracted nothing = shape moved) / SKIP
    (network/pin problems — inconclusive, exit 0). Pinned ids override via
    `DM_ID`/`VIMEO_ID`/`APPLE_ID` env vars.
  - **`CaptureLiveTest`** (`app/src/androidTest/.../geckoview/`, the
    UblockBridgeLiveTest pattern; device + network):
    `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.geckoview.CaptureLiveTest`
    — boots the real runtime + extensions, LOADS real pages in a real
    GeckoSession and asserts entities land in `BrowserDownloadRepository`:
    t1 pins the webrequests `ensureBuiltIn` version against the bundled
    manifest (the version-cache trap), then Telegram post / Vimeo watch
    page / Apple Podcasts show page / a Wikimedia Commons `<video>` page
    (the generic catcher's DOM-scrape path). Login-walled and
    play-tap-gated sites are deliberately excluded — a red test there would
    measure the wall, not the parser.
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

## Crash reporting — one-tap anonymous Send; never an auto-GitHub relay

The crash sheet (`crash/CrashReportSheet`, shown on next launch from
`filesDir/crashes/`) has FOUR actions: **Send report** (the filled hero) plus
the demoted-to-outlined Report (pre-filled GitHub issue URL) / Copy / Dismiss.
Send is `CrashUploader`: an anonymous POST of the stored report JSON —
byte-identical to what the sheet displays — to `SYNC_DEFAULT_BACKEND` +
`/v1/crash` (the firedown-api collector; its CLAUDE.md carries the server
half). No account, no identifiers; the server dedups by a trace signature it
computes itself and pings the operator's private ntfy on NEW signatures only.
The send is HASHCASH-GATED (spam pricing): `CrashUploader` fetches
`/v1/crash/challenge`, solves with the SAME `sync/crypto/Pow` class the sync
registration uses — on the OkHttp callback thread, off main — and carries
challenge+nonce in the POST body. A 404 challenge = gate disabled server-side
→ the POST goes bare; a spoofed difficulty above 26 bits fails the send
rather than spin the CPU (the `RELAY_POW_MAX_BITS` rule).
Design points that are easy to undo:

- **A server-side relay into public GitHub issues was DECIDED AGAINST** — an
  anonymous endpoint feeding the public tracker is a spam gateway, and crash
  traces routinely embed the URL the user was on in the exception message.
  Reports stay operator-private, retention-bounded server-side; publishing one
  to GitHub is a human act. Don't "finish the automation" by having the server
  file issues.
- **The `crash_sheet_send_note` caption under the button is the privacy
  claim** ("only the report above, no identifiers") — it sits AT the button
  that acts on it, and the honest-copy rule applies: if what's sent ever
  changes, the caption changes with it, in all 16 locales.
- **Failure keeps the sheet up and the pending files on disk** — only a
  dismissal sweeps, and only success dismisses; Copy/Report stay available as
  the fallback. The button disables for the in-flight window (double-tap =
  double POST otherwise).
- **A collected trace is obfuscated, so every release UPLOADS its
  `mapping.txt` to the crash collector, which retraces reports server-side.**
  This is AUTOMATED: the `uploadReleaseMapping` task in `app/build.gradle`
  finalizes `assembleRelease`/`bundleRelease` and PUTs the gzipped mapping to
  `https://api.firedown.app/v1/mapping/<versionCode>` (Bearer = the crash
  page's `FIREDOWN_CRASH_ADMIN_TOKEN`, read from the USER's
  `~/.gradle/gradle.properties` as `firedown.mappingUploadToken` — never this
  repo; `firedown.mappingUploadUrl` overrides the endpoint,
  `-Pfiredown.mappingUpload=false` skips a throwaway build). firedown-api
  keeps it beside the reports (`<DataDir>/mappings`, tarred by the backup
  job) and RETRACES every report of that build on read — the `/v1/crashes`
  page shows the readable trace with the raw one folded under it, and the
  ntfy push names the real exception class plus the first app frame — so no
  laptop keeps an archive, and a report that arrived BEFORE its mapping
  decodes the moment the mapping lands. Keyed by versionCode because the
  report carries it (compat-mode frames carry no `r8-map-id`); the mapping's
  `pg_map_id` is recorded for cross-checking. Two loud failure modes, on
  purpose: a missing token or a non-2xx FAILS the task (the mapping is still
  in `app/build/outputs/mapping/release/` — re-run
  `./gradlew uploadReleaseMapping`, or on the VPS
  `firedown-api --mapping-import <versionCode>:mapping.txt`), and a
  DIFFERENT mapping under an already-uploaded versionCode is a 409. That
  guard protects a SHIPPED build (replacing its mapping breaks the retrace
  of every report it already sent — bump versionCode), but a release is
  rebuilt under one versionCode many times before it ships and the newest
  build is the one that matters there, so `firedown.mappingForce=true`
  (`-P` on the command line, or set once in `~/.gradle/gradle.properties`)
  sends `?force=1` and the collector replaces the stored mapping. **This
  repo's `gradle.properties` sets it `true`** (maintainer call), so a plain
  `assembleRelease` always replaces; `-Pfiredown.mappingForce=false` restores
  the 409 guard for one build. The task is `onlyIf`-gated on `minifyReleaseWithR8`
  having run without failure in THIS build: `finalizedBy` fires on a FAILED
  build too, and a failed release leaves the previous build's `mapping.txt`
  in `outputs/`, which would be uploaded under the current version + commit
  as if it decoded this build. (This replaced a local `~/firedown-mappings`
  archive — a mapping on one laptop is a mapping nobody else can use, and
  the crash page could never read it.) The retracer is
  `internal/shared/retrace` in firedown-api (pure Go, unit-tested against
  R8's inline chains and horizontal merging); for a one-off local decode
  pull the file with `GET /v1/mapping/<versionCode>` and use
  `$ANDROID_HOME/cmdline-tools/latest/bin/retrace <mapping> <trace>`. Release builds
  are `minifyEnabled true` with no `-dontobfuscate` — the targeted
  `-keep class` rules in `proguard-rules.pro` (Gecko, Rhino, a few
  ffmpegutils classes) keep *those* classes, not everything, so a report
  arrives as `o71.d(...)` and is undecodable without the matching mapping.
  Match a report to its build by the `versionCode`/`versionName` the report
  already carries. `-keepattributes SourceFile,LineNumberTable` IS set, so
  the file and line in each frame are true and readable on sight; only the
  class needs retracing. **That rule works ONLY because gradle.properties
  sets `android.enableR8.fullMode=false` — the two lines are a pair; never
  remove either alone.** Since AGP 8.12, R8 FULL mode (the default) replaces
  every SourceFile with `r8-map-id-<hash>` and compresses line numbers
  whenever obfuscation/optimization is on, silently ignoring the
  keepattributes rule (only compat mode honors it — AGP 9 release notes).
  This shipped: v1.1.91 was built with the keepattributes rule present
  (AGP 9.3.1 / R8 9.3.16, full mode) and still emitted `r8-map-id-…:44`
  frames — proven by extracting the release APK's classes.dex (r8-map-id
  marker present, zero `.java` strings) and by its embedded
  `version-control-info.textproto` naming a build commit that contained the
  rule. The `r8-map-id` hash in such a trace IS the mapping file's
  `pg_map_id` header, so it names exactly which archived mapping decodes it.
  `-renamesourcefileattribute` is deliberately NOT
  set — it hides file names, which protects nothing in an open-source app
  and would throw away the one readable part of a frame. **Retrace by
  METHOD, never by class name:** R8's horizontal merging puts a method in an
  unrelated class's slot, so grepping the mapping for the obfuscated class
  gives a confident wrong answer (a `SavedStateRegistryImpl.performSave`
  frame resolved to `TrackingPermissionDao_Impl` this way); match the
  method's line range instead.

### Send feedback — the crash send's twin, for words

Settings → Firedown → **Send feedback** (`SETTINGS_FEEDBACK` →
`FeedbackFragment`, a full-page `<fragment>` in `nav_graph_settings`) posts a
free-text note plus an OPTIONAL reply-to the user types to `/v1/feedback` on
`SYNC_DEFAULT_BACKEND`. Added because the app had no channel short of a
GitHub issue and the backup/payment numbers showed we don't know what users
value. Same posture as the crash send; the server half (operator page
`GET /v1/feedback`, ntfy push per note, retention) is in firedown-api's
CLAUDE.md.

- **ONE anonymous-send implementation: `crash/AnonymousPost`.** The
  challenge → solve-off-main → POST flow (the 404 = gate-off bare POST, the
  26-bit `POW_MAX_BITS` refusal) was lifted out of `CrashUploader`, which now
  only builds the report JSON. A third anonymous send uses it too — don't
  copy the flow. Each route has its OWN PoW resource string (`"crash"`,
  `"feedback"`) that must match the server byte-for-byte.
- **`feedback_note` is the privacy claim** (message, optional contact, app
  version, Android version, phone model — "nothing else"), shown under the
  fields at the moment of sending. The `crash_sheet_send_note` rule: if the
  payload changes, the string changes in all 16 locales.
- **A full page, not a dialog.** It shipped as a dialog for one build: ~270dp
  of width cut the message placeholder and the contact label mid-sentence
  (a floating hint is single-line), and a 4-line box is too small to write
  in. The message prompt is the EditText's own wrapping hint
  (`hintEnabled=false`), the contact description a TextView above its box,
  and insets include the IME so Send stays above the keyboard.
- **Only success leaves.** A failure keeps the typed text and states the
  error in the note's place; the button is disabled and Back swallowed for
  the in-flight window (no double post, no orphaned result).

## Update ping — the monthly-rotating client token (`X-App-Client`)

The 24 h update check (`UpdateWorker` → `www.firedown.app/status.json`) is
the ONLY regular contact every install makes, and the website counts DAU/MAU
from its nginx log (firedown-website repo, `ops/stats/firedown_stats.py` —
committed but never served). It used to
unique clients by a salted IP hash, which is wrong both ways — carrier NAT
folds phones together, mobile IP churn splits one apart — so the dashboard
could only bracket MAU between the peak day and the inflated month figure.
The ping now carries **`X-App-Client` = `base64url(HMAC-SHA256(secret,
"YYYY-MM"))[:16]`** (`UpdatePingToken`): stable within a UTC calendar month
(exact DAU and MAU), unrelated across months (the secret never leaves the
device, so nothing on the server can join two months — the SAME linkability
the per-month-salted IP hash already allowed, moved client-side, not
widened). **Never make this a persistent install id** — a stable identifier
arriving daily with IP, version and country is a tracking beacon, which the
stats pipeline's deleted-salt design exists to prevent. The secret lives in
`backup_local.xml` (excluded from Auto Backup — a restore onto a new phone is
a new install, not a cloned id); the header rides ONLY the firedown.app
origin request, never the GitHub Raw fallback. Honest-copy rule: the
website's privacy page discloses what the ping carries; if the header set
changes, that page changes with it. The server side (nginx column, the
stats script's token-first/IP-fallback uniquing, the ntfy digest) is
documented in firedown-website's CLAUDE.md under `ops/`.

**What to expect in the numbers as the APK ships.** The server uniques a
tokened ping by the token and an untokened one by IP, in disjoint
namespaces, and reports the tokened share as "N% exact". IP uniquing was
wrong both ways — churn made one install look like 10–20 users a month
(Sept 2026: MAU 19,206 against a peak DAU of 1,276), NAT folded phones
together — so as installs update, **MAU drops sharply and that is
correctness arriving, not users leaving**; DAU rises slightly; the IP-era
and token-era months are not comparable. Residuals by design: a reinstall or
a restore onto a new phone mints a new secret (counts twice that month), and
nothing follows one install across a month boundary. If the token
derivation ever changes (header name, month format, truncation), the
server's `TOKEN_RE` and the privacy bullet change in the same release — a
malformed token silently degrades that ping to IP uniquing, which looks like
adoption stalling, not like a bug.

## Releasing — `bumpVersion` and `releaseStatus` (app/release.gradle)

Version bump and `status.json` (what the in-app update check reads) are Gradle
tasks, not hand edits:

- **`./gradlew bumpVersion`** — `versionCode` + 1 in `app/build.gradle`, and
  the `versionName` DERIVED from it: **the name IS the code written as
  major.minor.patch** (1202 ↔ `1.2.2`, 1210 ↔ `1.2.10`, 1300 ↔ `1.3.0`), so
  the About screen, the F-Droid filename, `status.json`'s release tag and
  the mapping upload all say one number; plus an empty
  `## [x.y.z] - <today>` entry at the top of `CHANGELOG.md`. The hundredth
  patch rolls into the next minor by construction (`1.2.99` → `1.3.0`); to
  start a minor or major earlier, `-Pfiredown.versionName=1.3.0` (canonical
  form only — no padded patch) and the CODE follows the name (→ 1300). The
  code is the only thing Android upgrades on and stays the mapping-upload
  and update-check key; it just can no longer disagree with the name. A
  `build.gradle` whose two values disagree FAILS both this task and
  `releaseStatus` rather than being silently "fixed", so the scheme can't
  drift by hand. Its ceiling (a one-digit minor, a hundred patches per
  minor) is far beyond the release cadence. History: every release through
  1.2.01 had a ZERO-PADDED patch (`1.2.01`, `1.1.99`) so the name read as
  the code digit for digit; the maintainer dropped the pad from 1202 on
  because it looked like a typo beside every other app's version. The
  padded form of a code is still accepted as the CURRENT name (that is what
  lets the first bump leave `1.2.01` behind), never written. Before the
  derivation existed the task added one to the patch as an INTEGER, which is
  how the name and the code came apart. Pinned by
  `sh scripts/release-harness/run.sh` — the REAL `app/release.gradle` under
  a scratch Gradle project (no Android plugin, no SDK; `$GRADLE`, else
  `gradle`, else the wrapper): the bump from `1.2.01`, a patch past nine,
  both rollovers, the explicit-name path and its refusals (incl. a padded
  explicit name), a disagreeing file left untouched — 10 of its 13 checks
  fail on the padded task. Run it alone: it rewrites a file Gradle already
  read.
- **`releaseStatus`** finalizes `assembleRelease` and writes `status.json` for
  the APK just built: versionCode, `"Firedown <name>"`, the GitHub release URL,
  the F-Droid fallback, the APK's sha256 and the "What's new" bullets from
  this version's CHANGELOG entry (byte-identical to the retired
  `scripts/update-status-changelog.mjs`). It FAILS when the CHANGELOG's newest
  entry isn't this version or has no bullets, and when it can't find a SIGNED
  APK of this versionCode — it reads `output-metadata.json` in the Studio
  wizard's `app/release/` (and the standard outputs dir), skips `*-unsigned`,
  takes the newest. Re-run alone with `./gradlew releaseStatus`; skip with
  `-Pfiredown.releaseStatus=false`. Writing the file publishes nothing —
  committing it after the APK is on GitHub and in the F-Droid repo is still the
  release step, since a pushed status.json pointing at a missing APK is an
  update every user taps into a 404.

### CHANGELOG entries — short, important changes only (HARD RULE)

Every bullet ships verbatim as the in-app "What's new" (`releaseStatus` →
`status.json`) and as the release notes, read on a phone. So:

- **One short sentence per bullet: about 15 words, never more than 25.**
  What changed for the user, in plain words. Not how, not why, not what it
  used to do.
- **Only changes a user would notice and care about**: a new feature, a fix
  for something they could hit, a removal. Refactors, tests, harnesses,
  docs, logging, renames and small visual polish get NO bullet. Related
  fixes share one bullet.
- **No theater**: no root causes, no history ("used to", "instead of"), no
  reassurances, no lists of examples or screens, no parentheticals, no
  class or file names.
- **Never a specific site.** A bullet states the general, architectural
  change ("videos in news cards are named by their headline", "embedded
  podcasts appear once"), never the site it was found on or fixed for. A
  per-site parser fix ships under the behaviour it restores; the site and
  the HAR belong in the commit message. Eight bullets naming X, Vimeo,
  Dailymotion, Kick and Spotify were collapsed into three general ones
  before 1.2.3 shipped.
- **An engine update is the FIRST bullet**, in the fixed form "Updated the
  GeckoView browser engine to <version>" (plus "and FFmpeg to <version>"
  when both moved). The maintainer lands a bump on `main` as a bare
  `- Bump to <version>` commit that touches only `gradle.properties` (and
  the Gradle wrapper), with no changelog line, so the release pass has to
  find it: diff `firedown.geckoviewVersion` and `firedown.ffmpegRelease`
  against the previous release tag and write the bullet. A wrapper or
  dependency bump alone gets none.
- **Before a release, the entry is REWRITTEN from `git log v<previous>..main`**,
  commit by commit (the bullets `bumpVersion` opened were written as each
  change landed, before the whole release could be seen): drop what the
  rules above exclude, merge related fixes, fold a fix for a change that is
  itself new in this release into that change's bullet (no bullet for a
  bug that never shipped), and date the entry with the release day.
- The explanation belongs in the commit message and in this file, never in
  the changelog.

Wrong (1.2.2's first draft, 150 words in one bullet): "Captured content:
one list instead of a grid/list switch, laid out like Downloads. Videos,
audio, subtitles and files use the same tiles as the Downloads grid…".
Right: "Captured content: redesigned to match Downloads, with earlier pages
folded into one row". Wrong (a site): "Captured content: Dailymotion videos
keep their whole title". Right: "Captured content: videos keep their whole
title".

## Logging discipline

**Every log statement — Java and JavaScript — must be gated behind the debug
flag. No unconditional logging ships.**

- **JavaScript (extensions):** never call `console.log`/`console.warn` directly.
  Use the extension's `log(...)` helper, which early-returns unless `DEBUG` is
  true. `DEBUG` is resolved at boot from the native `get-debug-flag` message,
  which returns `BuildConfig.DEBUG` (`GeckoRuntimeHelper`). So a release build
  logs nothing even though the JS contains `log(...)` calls. New parsers must
  route all logging through `log(category, message, data?)` with a short
  uppercase category (e.g. `RUMBLE`). **This covers `youtube@` too**: its
  `background.js` resolves `DEBUG` the same way (the MessageDelegate answers
  `get-debug-flag` before dispatching on the nativeApp name, so the
  `"youtube"` app answers it) and logs only through `log`/`warn`/`logError`;
  `content.js` resolves it inside the robots.txt#fd-native branch only
  (ordinary youtube.com frames send nothing) and mirrors it to the
  page-world runner as `window.__fdDebug`, since the runner can't see the
  content script's binding. 137 bare `console.*` calls shipped in release
  builds before this.
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
- **The one sanctioned exception is a build nobody ships:** the DIAGNOSTIC
  release build, `-Pfiredown.diagnostic=true` (`-Pfiredown.geckoNetLog=true`,
  its first name, still works), for a problem seen on a device that runs
  only release builds. It sets `BuildConfig.DIAGNOSTIC`, which turns on
  Gecko's own network log (MOZ_LOG, to a file under the app's external
  files dir) and the app logs gated on `DEBUG || DIAGNOSTIC` (today the
  thumbnail loads in `GlideHelper`), and it leaves out
  `proguard-strip-logs.pro`, the `-assumenosideeffects android.util.Log`
  rule every published release applies, without which those lines would
  be stripped however they are gated. The flag defaults off, and the
  mapping upload and `status.json` skip themselves for such a build. Gate a
  new diagnostic log on `DEBUG || DIAGNOSTIC`; a bare `DEBUG` gate is dead
  code in this build too. And give it a LITERAL tag: R8 still renames
  classes in this build, so a `Foo.class.getSimpleName()` tag comes out
  obfuscated and `adb logcat -s Foo:*` shows nothing (GlideHelper's did,
  on the first diagnostic build). See the "every page stays loading after a
  resume" note.
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

**Every UNBOUNDED list read is `@Transaction` — a multi-CursorWindow query
outside a transaction is the 1.1.93 crash.** `SELECT * FROM download` on a
long-running install no longer fits one ~2 MB CursorWindow (~290 rows of
signed URLs + header JSON), so the Cursor pages: the first fill counts every
row and keeps rows 0..N-1, and stepping past N RE-EXECUTES the statement for
the next window. Outside a transaction those executions see different
snapshots — the batch delete USED TO remove rows one autocommit statement at
a time on the DiskIO lane while each delete's invalidation re-ran the
aggregates LiveData (`getAllRegularLive`) on Room's query thread — so the
refill held
fewer rows than the cached count, and reading the missing row throws
`IllegalStateException: Couldn't read row 292, col 0 from CursorWindow`
inside `RoomTrackingLiveData` ("Exception while computing database live
data"), which is fatal. The tell: the row number is exactly the first row
past the initial window and col is 0 — NOT an oversized blob (that class
throws `SQLiteBlobTooBigException`, and the tab archive already projects its
`file_state` out for it). Room's `@Transaction` javadoc names this case;
the Paging sources were never affected because `LimitOffsetPagingSource`
loads each page in a transaction. So every DAO list read without a small
literal LIMIT — sync `List<…>` and `LiveData<List<…>>` alike, across all six
DAOs — carries `@Transaction` (the DownloadDao "One-shot Queries" comment is
the canonical write-up). A new unbounded read gets it too; a `LIMIT 3`
autocomplete query does not need it. The batch delete itself is now ONE list
`@Delete` (`deleteSyncEntities`, a single Room transaction → one commit, one
invalidation) with all the file IO done in a pass BEFORE it — SAF deletes are
slow binder calls and must not run under the write lock. The read-side
`@Transaction` stays load-bearing regardless: single deletes, vault moves
and the missing-file sweep still write concurrently.

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
source is dead; its replacement re-registers on creation). **The Bookmarks
and History paging lists carry the SAME belt** (`WebBookmarkDataRepository` /
`WebHistoryDataRepository`, registering inside their own `get()`/
`getAlphabetical()`/`getSearch()` so the view models needed no change):
Bookmarks shipped without it and the maintainer hit exactly this class on
a single-row delete — "delete item, nothing happens, back out and re-enter
and it is gone" — with the row provably deleted (the re-entry proves the
write landed; only the generation was missing). Any NEW Room paging list
gets the belt from day one. Known limit: plain LiveData queries (the
aggregates/section headers) have no invalidate() and still depend on the
tracker — a dropped notification there self-heals on the next write; only
the paging lists have the belt.

**`file_safe` has TWO migration histories, and 8→9 / 10→11 guard for the
old one — keep the guard.** Builds in the wild created `download-db`
version 8 WITHOUT `file_safe` and added it in 8→9; the migrations were
later rewritten so 7→8 adds it and 8→9 became a no-op. An install whose
version-8 file came from the old lineage then reached 10→11 — the paging
indices, ON `file_safe` — still without the column and crashed at open
(`no such column: file_safe … CREATE INDEX IF NOT EXISTS
index_download_file_safe_file_date`, reported from a device 2026-10;
`fallbackToDestructiveMigration(false)` is right, so that was a crash
LOOP until app data was cleared). `DownloadDatabase.ensureFileSafeColumn`
(`PRAGMA table_info` + conditional `ALTER TABLE ADD`) runs in 8→9 and
again in 10→11 before the index; a no-op on the current lineage. The
lesson generalises: a migration that REWRITES history (moves a column add
to an earlier step) must leave the later step able to repair a database
that took the old path — databases in the field remember the schema they
were created with, not the one the code now claims for their version.

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

**Downloads toolbar is Search · View · ⋮ (two `ifRoom` icons), the overflow
is Sort · Storage · Backups · Receive a file, and the vault's door is the
SAFE FOLDER ROW at the top of the list — never a toolbar glyph, never an
overflow row.** `SafeFolderHeaderAdapter` + `item_safe_folder_header` ride
the list's `ConcatAdapter` at position 0: lock glyph, "Safe Folder", a
subtitle, chevron, tap → `VaultActivity`. That is the big-player shape
(Files by Google's "Safe folder" row in Browse, Samsung My Files' Secure
Folder row, Google Photos' Locked Folder tile — none put the vault in the
chrome). History, three shapes in one day: (1) an overflow row, buried under
an `ifRoom` Sort that lost its bar slot on 360dp phones — the maintainer's
"overloaded" report; (2) a fourth bar glyph pinned `always` — too crowded
on-device, and pinning it SPENT one of appcompat's two `ifRoom` slots
(`abc_max_action_buttons`) so Toggle view fell into the overflow; pinning
all three fixed that and was still too crowded; (3) the row. Two bar icons
+ ⋮ is the bar's budget; don't mix `always` with `ifRoom` in it.
- **It is a PLAIN ROW, not a card, and its geometry never changes.** A
  fixed 56dp one-line row (the menu-row spec: 16dp gutter, TitleMedium),
  lock + "Safe Folder" + chevron on the bare list surface. It shipped for a
  day as a tonal `MaterialCardView` (`colorSurfaceContainerHigh`, the brand
  wash when live) with the in-flight count as a SUBTITLE, and both halves
  were wrong on their own merits: a tonal container with glyph + chevron is
  the cloud banner's exact shape (two "promos" could stack), and a row that
  grows a line when something happens inside it shoves the whole list under
  the thumb. The big players' element for a locked/archived compartment at
  the top of a content list is a bare row with a TRAILING COUNT — WhatsApp
  Locked chats / Archived, Telegram Archived Chats, Files by Google Safe
  folder — so that is the shape. A "Locked · stays on this device" subtitle
  also shipped for a day and was cut (a reassurance sentence under a door).
- **The row ABSORBED the incognito-in-progress card — ONE lock row, and the
  live state is a PILL.** The old card (lock + chevron into the same vault,
  shown only while incognito-tab downloads were in flight) would have
  stacked as a second lock row above the first. Now
  `TaskViewModel#getSafeCount` drives only the trailing pill
  (`safe_folder_pill`, `bg_safe_folder_pill` in the `chip_checked_*` pair
  so the screen's two "active" marks share one colour; the
  `safe_folder_downloading` plural, "N downloading", 16 locales — short on
  purpose, it lives in a 20dp badge). GONE at rest, VISIBLE while live; no
  wash, no subtitle, no height change. The count never hides the row.
- **Stacking policy for the top of the list: at most TWO cards, furniture
  first.** Position 0 is the Safe Folder row (permanent, state-carrying),
  position 1 the Backup announce banner (dismissible, one stage). The
  permanent row leads so it never shifts when the promo comes or goes; the
  promo is the only card with an X. Anything new that wants the top of this
  list must EITHER fold its state into one of these two (the way the
  incognito card folded into the row) or replace the banner's stage — a third
  card is not available; the two rows + "Today" header already spend a
  third of a 360dp screen.
- **The row shows ONLY once the vault HOLDS something, and only on the
  resting list** (`updateSafeFolderRowVisibility`: `DownloadDao.
  getSafeRowCountLive` > 0 or an in-flight vault download, AND no chip
  checked, no search, no selection). The content gate came from the
  maintainer's "isn't a permanent card invasive?" — it is, for the many
  users who never touch the vault; gated, they see the list exactly as it
  was before the row existed, while a user with vault files gets the door
  where they would look for it. Discovery of the feature stays where vault
  content is created (incognito browser, home popup). Not dismissible (a
  door you can lose is worse than none) and never back in the overflow. A filtered/searched list is a question about the downloads
  and the door is not an answer to it; in selection a navigation row above
  the ticked rows is a mis-tap. The chip case flips WITH the new generation
  (`applyPendingPresentation`, the same rule as mime suppression), search
  open/close and action-mode enter/exit flip it directly. On an EMPTY list
  the LCEE empty view replaces the recycler, so the row is not there — the
  vault stays reachable from the home/browser popups and the in-progress
  notification; that gap is accepted over re-adding an overflow row.
  `getLeadingHeaderCount` counts it (the grid `SpanSizeLookup`).

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
Subtitle, which leads the long-tail half), in
`fragment_download_list_options.xml`. The Captured sheet has no chip rail at
all (see "The sheet has NO filter chips" under the Captured sheet).
Reordering is safe: all chip handling is id-keyed, never positional.

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

**The 2026-10 redo (direction A of `sketches/p2p-share-redo.html`): the
SESSION is the controller's and a foreground service carries it; the
screens are windows onto it; nearby is a LIST of phones, not a QR.** Two
things the first design got wrong on-device: the sender had to sit on the
share screen for the whole transfer ("session lifetime = view lifetime"),
and the in-person path was a QR to aim across a table. Now:
- **`P2pShareController` owns the session and publishes it as a
  `ShareState` snapshot** (`getState()`, a LiveData republished on every
  change: role, stage `PREPARING → READY/OFFERED → CONNECTING → CONNECTED →
  TRANSFERRING → DONE/ERROR`, the file, the peer's device name, the offer
  code + link-shared flag, the receiver's fallback reply code, progress,
  the relayed/direct fact, the error, the received row, a one-shot
  `notice`). `P2pSendFragment` / `P2pReceiveFragment` keep NO session state:
  they `render(state)` the whole stage from each snapshot, so a screen
  opened onto a running session (the notification's tap, a rotation) paints
  what the one that started it would. The Send screen ATTACHES when a live
  SEND state names the same file path, else starts; the Receive screen's
  deep-link args start a receive only when `savedInstanceState == null`.
  A terminal DONE/ERROR snapshot stays (engine and servers already gone)
  until a screen dismisses it (`dismissFinished`) or a new start replaces it.
- **`P2pShareService` (`dataSync` FGS, the `RunnableManager` shape)** is
  started by the controller the moment a session must outlive its screen —
  `startSend` (the offer waits for an accept) and `acceptOffer` — observes
  the same state, repaints a progress notification on every stage/peer/
  whole-percent change (via `startForeground`, exempt from the
  POST_NOTIFICATIONS grant), carries a Cancel action (`ACTION_CANCEL` →
  `controller.stop()`), and stops ITSELF when the state goes null or
  terminal (the final outcome stays, detached). Its tap reopens the live
  screen through `DownloadsActivity.EXTRA_OPEN_P2P` (`openLiveShare` — no
  second copy if the screen is already on top). `onTimeout` (the Android 15
  6 h cap) fails the share honestly (`failForSystemTimeout`); a refused
  `startForeground` never crashes — the share keeps running for as long as
  its screen is open, the pre-service behaviour. Leaving a screen ends
  NOTHING that is running; what a screen releases on leaving is its own:
  the receiver's nearby visibility, a pre-accept preview (declined), a
  shown outcome. Back and the toolbar's up simply leave; only the explicit
  Cancel (screen — confirmed while bytes move — or notification) stops a
  session.
- **A minted offer lives `OFFER_LIFETIME_MS` (15 min, the mailbox's offer
  TTL and the poll's deadline) and then ends the session with
  `ERROR_TIMEOUT`** — needed now that nobody has to be on the screen; a
  forgotten share would otherwise hold the service, the hidden session and
  the LAN listener until the app died. The poll's own null callback is NOT
  the signal: it also fires at once on an unexpected mailbox status, which
  would end a healthy LAN-only share.
- **NEARBY = NSD.** While the Receive screen is open and idle the controller
  binds the `/offer` route of `P2pLanServer` (the generalised
  `P2pAnswerServer`: two token-gated POSTs, `/answer` for the sender,
  `/offer` for the receiver, 16 KB bodies, 204/409) and advertises it as
  DNS-SD `_firedown._tcp` through the SYSTEM `NsdManager`
  (`P2pNearbyAdvertiser`: name = the user's device name from
  `Settings.Global.DEVICE_NAME`, else `Build.MODEL`; TXT `t` = the
  per-session token, `d` = the name, `v` = 1; Android may rename on a
  conflict and the registered name is what `getVisibleAs()` reports —
  "Visible as <name>" on the screen, null = not visible). No new
  permission: the system responder does the mDNS, so no MulticastLock, no
  `CHANGE_WIFI_MULTICAST_STATE`, and NSD is not in the `NEARBY_WIFI_DEVICES`
  set (Wi-Fi Aware/Direct are) — a device check still owed on Android 13+.
  The Send screen's `P2pNearbyDiscovery` lists found services (resolves
  SERIALISED — a second concurrent `resolveService` fails with
  `FAILURE_ALREADY_ACTIVE`) in first-seen order, and lists a service only
  when it resolved to an IPv4 (`P2pLanServer.reachableHost`): the receiver
  binds site-local v4 only, and before API 34 NSD reports ONE host — a
  scoped link-local IPv6 (`fe80::…%wlan0`) there is not even a valid URL
  host, and building the request threw on the main thread (the request is
  now also built inside a try). A tap `sendOfferToNearby` POSTs the whole
  `FDS1.` code to `http://<host>:<port>/offer?t=<token>` and the state
  names the device ("Sent · waiting"). The receiver previews it exactly
  like a scanned code (`arrivedNearby`) and its Accept returns over the
  offer's own `ans`, i.e. the sender's `/answer` — the same LAN. 409 =
  that phone is busy with another transfer (`mNearbyBusy`, set for any
  live session), connect failure = unreachable; both are one-shot
  `notice`s and the offer stays valid for another phone or a link. A
  Decline POSTs `declined` to `/answer` (`P2pLanServer.DECLINED_BODY`) so
  the sender goes back to choosing instead of waiting out the lifetime;
  leaving the Receive screen with a request pending declines it. Nothing is
  ever advertised without the screen saying so, and visibility stops
  (`setNearbyListening(false)`) the moment a session is live IN EITHER
  ROLE — a phone in a transfer vanishes from senders' lists rather than
  answering 409 (a live SEND used to keep it visible). Visibility follows
  the screen being SEEN: `onStart` decides, `onStop` withdraws — Home keeps
  the view (onStop, not onDestroyView), and a phone used to advertise its
  name for hours from the background. **Nearby has NO authentication, by
  design, and the copy must never imply one:** anyone on the same network
  who sees the name can POST an offer, and what protects the user is that
  an offer is only a PREVIEW until they tap Accept (name, size and the
  sender's device name are shown first; nothing connects or downloads
  before). The `/offer` token in the TXT record is a capability against
  blind scanning, not proof of who sent it, and the "From <device>" line is
  whatever the sender's phone claims. The LAN server handles connections on
  a pool of `MAX_CONCURRENT` (4) workers with ONE deadline per connection
  (`CONNECTION_DEADLINE_MS`, 10 s, `DeadlineInputStream` — a per-read
  timeout alone resets on every byte, and a client trickling a byte every
  few seconds used to hold the server's only thread for hours, so no one
  else on the network could offer or answer); a connection beyond the pool
  is closed at once, the token compare is `MessageDigest.isEqual`, and the
  handler decides atomically (`mNearbyBusy`/`mAnswerTaken` are
  `AtomicBoolean`s, several workers can ask at once).
- **Screens:** Send = the file card, NEARBY (the device rows, inflated
  from `item_p2p_device.xml`, with the empty line "No one yet. On the other
  phone, open Downloads → Receive a file"), then ANYWHERE (Send link,
  Private link, and the QR folded under "Show QR" with the scan/paste
  reply fallbacks). Receive = the "Visible as <name>" card and a "Got a
  code or link?" fold (Scan · Paste); the request card (Accept/Decline);
  the human-relayed reply only when both push paths failed
  (`ShareState.answerCode`). Both carry "You can leave this screen. The
  transfer continues in the background." on the progress card. The old
  "Nearby? Show the QR" fold, the scan/paste entry card and the reply
  stage's "One step left" copy are retired (base + every `values-*`).

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
ownership pattern — the port never lands in `mPorts`). Java owns the
session (the controller) and all UI (the screens render its snapshots), the
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
  Firedown". The QR is folded under "Show QR" in the sender's ANYWHERE card
  — the in-person path that needs NO network at all; two phones on one
  Wi-Fi use the nearby list instead (the redo note above).
`handleP2pDeepLink` routes both forms by prefix: an **FDS1 offer** →
`p2p_receive` (`ARG_OFFER_CODE`); an **FDR1 reply** → the LIVE send session
(`provideExternalAnswer`; no session → an honest "share no longer open"
snackbar). So the remote round trip is share link → Accept → send reply →
sender TAPS the reply in the chat — no scanning, no pasting. The sender's
screen shows a WAITING card after sharing (`ShareState.linkShared`, kept on
the state so a sender who leaves and returns still sees it), auto-picks an
FDR1 reply off the CLIPBOARD on window focus (each clip value tried once, so
a soft bad-code can't loop; Android's paste toast keeps the read visible),
and keeps Paste as the manual fallback. The receiver shows a reply ONLY
when both push paths (LAN return, mailbox) failed (`ShareState.answerCode`):
"Send reply" for a sender reached by link plus the QR for one standing
there; `arrivedRemote` still shapes the no-path error copy. `P2pShareController.stripDeepLink` unwraps both forms and bare codes
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
  is bounded (`RELAY_CREDS_BUDGET_MS`, 20 s, with one retry of a transient
  step and a skip when the phone has no VALIDATED internet) and **failure
  degrades gracefully** — the share proceeds direct+STUN-only, which is
  exactly the pre-relay behavior — but it SAYS so: `ShareState.relayAvailable`
  carries whether credentials were in hand, and a `no-path` without them
  reads `p2p_error_no_path_norelay` instead of "try the same Wi-Fi". **A
  cached credential is reused only with `RelayCredsPolicy.MIN_REMAINING_MS`
  (30 min) of life left** (`expiryFor` = TTL minus a 60 s margin; a fresh
  fetch otherwise, with the still-valid old one as the fallback when that
  fetch fails). It used to be reused until a minute before expiry, and coturn
  checks the credential's timestamp on allocation REFRESHES too, so a share
  started late in a one-hour credential lost its relay mid-transfer — "fails
  even with the TURN server" — and with a minted offer now waiting up to 15
  min for an accept the allocation could expire before the receiver accepted.
  The server's `FIREDOWN_TURN_TTL_SECONDS` (default 3600) is what bounds a
  relayed transfer's length (a credential cannot be swapped under a live
  allocation); raise it there for hour-plus relayed transfers. Privacy delta,
  stated honestly: starting a share now touches api.firedown.app once (an
  unauthenticated creds GET); the relay itself carries only DTLS ciphertext,
  and ICE uses it only when no direct path exists.
All three are threaded into the start command by `putIceServers` and
assembled by the engine's `newPeerConnection(ice)` (`sanitizeIceServers`
guards the ctor). A genuinely-unreachable pair with the relay fetch failed —
CGNAT↔CGNAT, full-tunnel VPN — still fails honestly (`no-path` →
`p2p_error_no_path`). Don't reintroduce a multi-STUN fallback list, and never
replace the fetched-ephemeral-creds design with a static credential.

**The footer's "never touches a server" claim is set in exactly ONE place —
`onTransport(relayed=false)` — and every other surface defaults to the
path-NEUTRAL `p2p_footer_connecting`.** The engine's `reportTransport` reads
the selected ICE candidate pair from `getStats` after `connectionState:
connected` and posts `{type:"transport", relayed}`; both fragments then choose
`p2p_footer` (direct) or `p2p_footer_relayed` (through firedown.app, still
E2E). That probe is **best-effort and silent on failure** — it bails when
`getStats` is missing, when its promise rejects, and when no candidate-pair
matches the transport pointer or the `selected`/`nominated` fallbacks. The two
layouts and both stage methods used to default to `p2p_footer`, so each of
those silent failures left a **relayed** transfer asserting the file never
touched a server — a false privacy claim, at full confidence, on the one
surface where the user is deciding whether to trust the transfer. The rule
that fixes it generalises: **an unverified claim degrades to the WEAKER
statement, never the stronger one.** `p2p_footer_connecting` ("Encrypted
end-to-end.") is true on every path, so it costs nothing to show while
unknown. Note this also applies to the RESTING entry screen, whose layout
default is the neutral string too: with the relay on by default,
"never touches a server" isn't reliably true as a general product claim
either. Adding a new footer state means adding it to BOTH layouts and BOTH
`onTransport`s.

Diagnosing "did this touch a server?" from a log: `typ srflx` among the
gathered candidates only proves **STUN** answered (it reveals the public
address; it carries no bytes), and an answer delivered via
`api.firedown.app/v1/p2p/a/<id>` is the rendezvous mailbox carrying ~1 KB of
SDP, not the file. The file path is the **selected pair**, which
`reportTransport` logs as `selected pair: host <-> host` / `srflx <-> srflx` /
anything containing `relay` (and `selected pair: unknown` when it couldn't
tell — the case that keeps the neutral footer). `relayed` alone can't
distinguish host from srflx, which is why the pair types are logged
separately.

**A LINK-delivered offer outlives the sender's session, so a `no-path` there
means "sender gone", not "bad network".** The `FDS1.` code is self-contained
(name/size/mime + the sender's ICE candidates + DTLS fingerprint, all minted
while the share screen was open) and the offer mailbox serves it for its whole
TTL with a NON-destructive read. So a link keeps producing a full, convincing
preview long after `P2pShareBaseFragment.onDestroyView` → `controller.stop()`
released those ports and destroyed the key behind that fingerprint: Accept
succeeds, connectivity checks reach nothing, and `CONNECT_TIMEOUT_MS` (30s)
later the receiver was told to "try the same Wi-Fi with any VPN off" — advice
for a live peer it can't reach, when the peer isn't there at all.
`P2pReceiveFragment.errorText` overrides `no-path` to
`p2p_error_no_path_link` when `mArrivedRemote`. Keep it scoped to that flag:
the QR path is the opposite case (the sender is standing there with the screen
open, so a no-path really is the network), and a VPN on the receiver still
wins, being more specific and directly actionable. The copy says "may have
closed" rather than naming the cause — a real CGNAT↔CGNAT pair still lands
here, and the next step is the same either way (same degrade-to-the-weaker-
claim rule as the transfer footer).

**The sender LISTENS for the whole window its link advertises —
`P2pSignalingClient.POLL_DEADLINE_MS` mirrors the server's `offerTTL` (15 min)
and must stay in step with it.** It was 150 s, justified by a comment blaming
a "~3 min relay TTL" that does not exist: the server's `waiter()`
(firedown-api `handler_rendezvous.go` — READ THE SERVER before trusting a
client comment about it) CREATES, or re-creates when expired, the answer
mailbox entry on every poll and parks 25 s, so there is no server-side poll
cutoff, and the client's "404 = session gone" branch described a response the
server never sends (200/204/400/503 only). The answer store's 5-min TTL bites
only an answer nobody collects; a live long-poll is woken instantly on
delivery. Consequence of the old value: a remote share's ONLY automatic
answer path was dead from minute 2.5 to minute 15 — the link previewed fine
and Accept no-pathed even with the sender sitting on the share screen. A poll
that gives up is a silent no-op on the sender (LAN return and the
human-relayed reply still stand), which is why extending it changed no UI.
Since the redo the offer's own `OFFER_LIFETIME_MS` (same 15 min) ends a
session nobody accepted — the one bound a screen-less sender needs.

**Failing FAST on a dead link (rather than merely honestly) was DECIDED
AGAINST — but for TWO reasons, not the four first written down.** The two
that hold: it only buys the ~30 s `CONNECT_TIMEOUT_MS` wait on a path that
fails either way (the valuable half — saying the right thing — is the
`p2p_error_no_path_link` fix, which cost nothing), and a liveness signal can
never GATE Accept (a false "gone" blocks a working transfer; a false "maybe
there" costs 30 s — so it degrades to an advisory, i.e. the same honest copy
shown earlier). Two reasons originally given were WRONG and are recorded so
they don't get re-derived: "rollout breaks old senders" and "periodic wakeup
per share" both assumed the heartbeat must be new client behavior — in fact
the server can derive liveness from whether anyone is currently long-polling
`/a/<id>` (the connection already exists; old APKs poll too), and with the
poll window now spanning the offer TTL that presence signal would be accurate
for the whole window. Still unbuilt because the two surviving reasons stand.
Note what the signal actually is, if ever exposed: ANSWER-PATH liveness, not
sender liveness. Don't fake fast-fail by shortening `CONNECT_TIMEOUT_MS` —
and doing it for the link case specifically is BACKWARDS: a link-delivered
offer is more likely cross-network/relayed and needs MORE connect time.

**Known residual, recorded not decided: the sender never RETRACTS a brokered
offer.** `P2pSignalingClient` has no DELETE — `stopSession()` releases the
ports and destroys the DTLS key but leaves the offer serving from
`/v1/p2p/o/<id>` for the rest of its TTL, which is what makes ghost links
preview convincingly. A best-effort DELETE at teardown (plus draining a stale
answer from `/a/<id>`) would turn a deliberately-closed share into the clean
"link expired" flow instantly, pre-Accept, with a perfect failure asymmetry
(retraction is affirmative — a crash that skips it just degrades to today's
honest-slow copy). Needs the firedown-api endpoint; if built, prefer it over
any liveness scheme. A "wanted marker" (receiver's dead-link tap flags the
id; sender polls its own ids on foreground) was considered and is WEAK: the
back-channel already exists — a link-delivered share arrived through a
messenger, and "link's dead" travels back through the same thread faster than
any app surface.

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
server" — observed on-device). So it is switched OFF, but only for the
ICE-GATHERING windows (`P2pShareController.setObfuscation`): Gecko reads the
pref when a peer connection starts gathering, so the sender turns it off
before its engine page opens (`applyWebRtcPrefs(true, …)`, chained after the
`setWebRTC(true)` belt, both awaited) and back on the moment its offer is
minted (`onOfferReady` — the code carries every candidate); the receiver
leaves the engine alone at open and turns it off only around Accept
(`recv-accept` waits for the write) and back on when its answer is minted.
`stopSession` restores it whatever happened. It used to stay off for the
whole SESSION — up to 15 minutes waiting for an accept plus the transfer,
usually while the user browsed in other tabs, and any page that created an
RTCPeerConnection in that time got raw LAN IPs. The pref is a saved USER pref,
so a process death inside a window would leave browsing without it:
`GeckoRuntimeHelper.restoreWebRtcObfuscation` puts it back at boot (no share
runs then). Don't remove the flip, the restore, or the boot restore, and
don't widen the window back to the session.

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
   `ensureEngineSession` runs `applyWebRtcPrefs` — `setGeckoPref(webrtc,
   true)` (idempotent belt-and-braces so a share can't race a cold boot's
   async pref write) chained with the mDNS-obfuscation flip — and opens
   `openEngineSession()` only on the chain's `.accept(...)`. Opening
   synchronously after the writes would race them and load the page before
   the values are visible.
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
16-byte token, lifetime = the share SESSION — stopped in `stopSession`
whether or not a screen is open): the sender-side engine
`fetch()`es `GET /read` and pumps the stream into the DataChannel (64 KB
chunks, `bufferedAmountLow` backpressure, 4 MB high-water); the
receiver-side engine batches ~4 MB and `POST /write?off=` — offsets are
verified server-side so ordering on disk is guaranteed. The extension
manifest carries the `http://127.0.0.1/*` host permission for this (Firefox
match patterns ignore ports). `RestoredFileAccess.openReadOnly` serves the
read side so a restored (foreign-owned) download is still sendable. The
runtime enforces Local Network Access blocking (`setLnaEnabled(true)` +
`setLnaBlocking(true)`), and that does not touch this path: a top-level
navigation to `http://127.0.0.1` and the page's own same-origin loopback
fetches are loopback-to-loopback, never a public page reaching a more private
address. Device-verified 2026-10 (`MediaNotificationTest`: a loopback page
loaded, fetched its audio and artwork from the same server, and played, with
both switches on). The `http://127.0.0.1/*` host permission stays as the
exemption for the extension's own fetches.

**Completion is receiver-ack'd, then the file becomes a normal download.**
The sender's `done` fires only on the receiver's `{"t":"rcvd"}` ack (sent
AFTER the last loopback write) — `bufferedAmount` hitting 0 proves nothing
about the far end. The receiver must **`waitBufferedDrain(dc)` before
`pc.close()`** — `pc.close()` aborts SCTP immediately and would drop a
still-buffered ack, hanging the sender; the sender arms `ACK_TIMEOUT_MS`
(`armAckAfterDrain`) only once the eof has actually LEFT its SCTP buffer —
it is queued behind up to `BUFFER_HIGH` of data, which on a slow path takes
longer than the ack window, and arming at the moment eof was queued failed
slow transfers at 100 % with "no delivery confirmation" — so a genuinely
lost ack still fails honestly instead of spinning forever. The receiver enforces the **accepted size**
(`offer.size + OVERRUN_SLACK`): a modified sender that advertised "2 MB" can't
stream tens of GB to fill the disk. The receiver writes to `<name>.part`, then
`P2pShareController.finalizeReceivedFile` (disk executor) verifies the byte
count, re-uniquifies (against disk AND the download table via `findByFilePath`
— a path free on disk can be owned by a queued/errored row) + renames, inserts
a `FINISHED` `DownloadEntity` (`file_url = "p2p://<device-slug>"` so the row's
`MIME · domain` meta line names the transport honestly; mime is derived from
the FILE, not the entity's stored label; Room invalidation refreshes the list,
no poke) and calls `GalleryPublisher.publish`. An aborted/failed receive
KEEPS a non-empty `.part` (only an empty one is deleted) — it is the resume
capital, see below. **A bad scanned/pasted answer is SOFT** (`bad-code`): the
engine keys softness on `signalingState` and treats every decode/apply failure
before connect as recoverable (the offer QR stays valid) — never `fail()` the
session on a mangled paste.

**The transfer is WATCHED, and every way it can die has a name — the
2026-10 fragility pass ("transfers fail or stall even with the TURN
server").** Four things the engine lacked, each pinned by
`node scripts/p2pshare-smoke.mjs` with ms-scale timers (`window.__p2pTuning`,
read once at load; teeth proven by mutation — an ack armed without the drain
wait fails two checks, a no-op `startWatchdog` three, a `failed` that always
says no-path one, a dropped backlog cap one):
- **A stall/lost WATCHDOG from DataChannel open to close** (`startWatchdog`,
  `WATCH_TICK_MS` 5 s): `flowMark` is the bytes that genuinely moved — the
  receiver's `done`, the sender's `done` minus `bufferedAmount` — and no
  change for `STALL_TIMEOUT_MS` (60 s) fails the session as `stalled`;
  a pc `disconnected` for `LOST_TIMEOUT_MS` (60 s) fails it as `lost`. The
  ack phase (eof drained, waiting for the receiver's disk ack) is exempt —
  flow is legitimately zero there and `ACK_TIMEOUT_MS` owns it. Before, a
  transfer whose path died quietly (ICE consent kept passing, bytes stopped)
  sat at the same percent forever with the service notification up.
- **`failed` after bytes moved is `lost`, before any is `no-path`**
  (`watchConnection`, `s.flowStarted`): the copy differs — re-share the same
  file to RESUME (the `.part` is kept), not "try the same Wi-Fi". Both codes
  are `ShareState.ERROR_LOST` / `ERROR_STALLED` → `P2pShareCopy.errorText`.
- **The receiver's unflushed backlog is bounded** (`RECV_BACKLOG_MAX`,
  256 MB: queued + in-flight loopback POST bytes) — a loopback write that
  wedged used to let SCTP deliver the whole file into page-world memory.
- **The ack timer arms after the DRAIN** (the completion paragraph above).
On the Java side of the same pass: `GeckoSession.setPriorityHint(PRIORITY_HIGH)`
on the hidden engine session (with the screen gone its content process was
the first thing Android trimmed under pressure — "engine disconnected" at
some percent) and the TRANSFER LOCKS (`acquireTransferLocks`): a PARTIAL
wake lock (the foreground service keeps the process alive, not the CPU — a
suspended CPU stops sending and the watchdog then calls it "stalled"), timed
at the dataSync 6-hour cap so a leak can't outlive it, plus a
`WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`; Wi-Fi power save with
the screen off turned background transfers into a crawl — deprecated in API
34 and possibly inert there, kept for the releases where it works, the wake
lock carries the rest). Held only while bytes can MOVE — from the sender's
engine applying an answer / the receiver's Accept to `stopSession` — never
through the up-to-15-minute wait for an accept or a preview, where holding
them was pure battery (they used to be held from `startLoopback`). The LAN
plumbing has its own JDK harness, **`sh scripts/p2p-harness/run.sh`** (the
REAL `P2pHttp` request-head parser shared by both tiny servers, `P2pLanServer`
bound on the loopback through its package-private `start(InetAddress)` door
— token/method/route gates, the 16 KB body cap, first-answer-wins, the
`declined` body, a busy receiver's 409, exact body bytes, a slow client, the
connection deadline, the worker pool and the exact-bytes token compare —
`RelayCredsPolicy`'s boundaries and network gate, `P2pFileNames`,
`P2pSessionPolicy` and `reachableHost`; 116 checks, ~15 s). Known residual:
non-trickle ICE means a receiver that accepts a 10-minute-old link answers
candidates minted 10 minutes ago; a NAT binding that expired meanwhile
shows as `no-path` on an otherwise fine network — the relay candidate is
what survives it, which is one more reason the credential policy above
matters. And coturn's `external-ip` is set only when `turn-provision.sh`
is given `PUBLIC_IP`; on a NATed VPS without it every relay candidate
carries the private address and the relay is silently unusable.

**Transfers RESUME across a dropped connection — re-share the same file,
re-accept, and only the remainder streams.** No new UI: the receiver's kept
`.part` is found automatically at the next Accept (same offer → same
uniquified target name → same part path) and the progress bar simply starts
at the resumed position. The handshake is versioned by FIELD PRESENCE, so
every old↔new APK pairing keeps working:
- The offer (`FDS1`) carries `res:1` (this sender serves ranged loopback
  reads and verifies a tail). The answer (`FDR1`) carries `off` (bytes on
  disk) + `tail` (base64url SHA-256 of the part's last 64 KB) — computed by
  JAVA at accept time (`tailHash` in the controller; it owns the file), only
  when the offer had `res` (an old sender can't serve ranges and must never
  be asked). The sender hashes ITS OWN bytes at the same range (ranged
  `GET /read?from=&len=` + `crypto.subtle` — the loopback origin is
  potentially-trustworthy, so subtle exists) and opens the DataChannel with
  `{"t":"begin","off":X}`: X = the offset when the tails match, 0 when they
  don't (a DIFFERENT file behind the same name — resuming would splice two
  files; restart instead). `begin` is sent ONLY when the answer requested a
  resume, so an old receiver never sees an unknown control message; ordered
  channel = begin precedes every chunk, and a chunk arriving before it fails
  the transfer (`data before begin`) rather than guessing offsets.
- **The tail-window size lives in TWO constants that must stay equal** —
  engine `RESUME_TAIL` and controller `RESUME_TAIL_BYTES` (both 64 KB). They
  hash "the last min(window, offset) bytes"; unequal windows hash different
  ranges, so every resume would LOOK like a mismatch and silently restart
  from 0 — a working-but-never-resuming state with no error anywhere.
- **Restart-from-0 needs no extra round trip**: the loopback write target is
  armed at the kept byte count (`setWriteTarget(part, keepBytes)`), and its
  offset check has ONE sanctioned exception — `off=0` against a non-empty
  target truncates and restarts (the engine's POSTs are promise-chained and
  never retried, so a mid-transfer `off=0` cannot recur). That also
  self-heals the old-sender case: Java arms a resume it can't know the
  engine discarded (the `res` gate lives engine-side), and the plain
  from-zero stream just truncates through it.
- **Every byte in a kept `.part` is a correct prefix, even after a
  mid-batch cut**: the channel is reliable+ordered and the loopback writes
  the body sequentially as it reads, so a torn last POST leaves a SHORTER
  correct prefix, never wrong bytes — file length IS the resume offset, no
  journal needed. (OS page-cache loss on power failure is accepted; the
  tail hash catches any corruption by restarting.)
- Retention is bounded: `pruneStaleParts` (7 days) sweeps `*.part` in the
  download dir from TWO triggers — every accept, AND `MediaListenerWorker`
  on every DownloadsActivity resume. The second trigger is load-bearing:
  accept-only pruning kept a failed receive's partial FOREVER for a user who
  never accepts another share, and that user is exactly the likely one after
  a big transfer died near the end (a ~98 GB `.part` with no DownloadEntity
  row — invisible to the Downloads UI and the missing-file sweep, reclaimable
  only via a file manager). Safe from anywhere because ONLY this feature
  writes `.part` there (verified; the download pipeline doesn't) and the age
  gate means a live session's minutes-old partial is never touched. So the
  honest policy answer is: a never-resumed partial lives 7 days past its
  last write, reclaimed the next time the user opens Downloads. `eof.bytes`
  and `done.bytes` remain the file TOTAL (progress counts from the resume
  point), so `finalizeReceivedFile`'s byte-count verify is unchanged.
- Engine changes ride `assets/p2pshare/` → the usual `manifest.json` version
  bump (3.0). The wire-protocol delta (`res`/`off`/`tail`/`begin`) must be
  mirrored in any future browser-recipient receive page — it is part of the
  shared wire format the "SECOND IMPLEMENTATION" warning below records.

**The 2026-10 review of the redo (`5ca54f3` + `2fe6759`) — invariants, each
closing a bug that review found in shipped code.** Pinned where they can run
off-device: the JDK harness above and `node scripts/p2pshare-smoke.mjs`
(the oid binding, the queued second answer and the early-failure classification), teeth proven by
mutation (each reintroduced bug fails at least one check). The rest lives in
the controller and needs a device.
- **Every async callback is pinned to its SESSION by a generation**
  (`mSessionGen`, bumped by every `stopSession`; callbacks capture it and
  act only while `isCurrent(gen)`). The relay-creds fetch, the rendezvous
  and relay polls, the offer upload, the nearby POST, the answer POSTs and
  their fallbacks, the disk hops and the pref writes all used to check only
  "a session of this role exists" — so a cancel followed by a new share
  within a slow callback's window let the OLD session's answer, short link
  or error land in the NEW one. A new async path takes a `gen`.
- **The sender closes its answer paths only once the engine APPLIED an
  answer** (`onAnswerApplied`, on the engine's `connecting` event, posted
  right after `setRemoteDescription`). `provideAnswer` used to tear down the
  LAN listener, the offer lifetime and the polls the moment ANY code was
  handed in, and move to CONNECTING — so an unreadable or wrong answer (a
  truncated paste, a link a messenger cut) left the sender on "Connecting"
  with no paste or scan button, no lifetime timer and the service held
  until Cancel. A soft refusal now REOPENS what consumed it
  (`onSenderBadCode`: the LAN listener takes the next answer instead of
  409ing it, a rendezvous poll that returned it polls again).
- **An answer names its offer (`oid`, optional, versioned by presence).**
  Each offer carries 9 random bytes; a receiver of this build echoes them;
  the sender refuses, SOFTLY, an answer carrying another offer's id ("answer
  is for another share"). A reply left in the clipboard from an earlier
  share used to be applied and die in ICE 30 s later as `no-path`. An answer
  without `oid` (an older receiver) is accepted as before. The sender's
  clipboard auto-pickup also ignores a clip copied before this share
  started (`ShareState.startedAt` vs `ClipDescription.getTimestamp`).
  Part of the shared wire format the browser-recipient note below warns
  about.
- **A new share never silently kills a running transfer.**
  `P2pSessionPolicy` decides what a share screen does with the session
  already there: the Send screen ATTACHES to its own send session (live, or
  its outcome — the finished notification opens the screen with no file,
  and both that and a rotation used to START a brand-new share of the same
  file, a fresh 15-minute offer, instead of showing "Sent"), STARTS for a
  new file, and refuses with `p2p_busy_other_transfer` while another session
  is CONNECTING/CONNECTED/TRANSFERRING (`blocksNewSession`); the Receive
  screen's paste/scan/link entries check `isBusyWithTransfer()` the same
  way, and the controller re-checks in `startSend`/`beginReceiveSession`. A
  share still waiting for an accept, or a finished one, is replaced as
  before — the newest wins. A screen recreated after process death starts
  nothing (`restored` → NOTHING).
- **A notice is shown ONCE.** `noticeSeq` comes from a controller counter
  that is never reset (a per-state counter restarted at 1 in each session
  and collided with what a screen had already shown), and a screen that
  shows one calls `consumeNotice(seq)`, so a screen attaching later
  (rotation, the notification) doesn't show it again.
- **A preview nobody answers is declined after `OFFER_LIFETIME_MS`**
  (`mPreviewTimeout`): a pushed nearby offer to a screen in the background
  held the session, the service and the busy flag indefinitely.
- **The relay fetch is skipped only when certain to fail** —
  `RelayCredsPolicy.networkAllowsFetch`: no active network, or a captive
  portal. It used to require `NET_CAPABILITY_VALIDATED`, which a network
  shown as "connected, no internet" never gets even when it does reach the
  internet (probe servers blocked, some VPNs, firewalls), so those shares
  lost the relay every time.
- **A received file's name is bounded and correct without an extension**
  (`P2pFileNames`): the old loop over `UrlParser.parseFilePath` could only
  put `(n)` before a dot, so a taken extensionless name (or an empty one,
  which resolved to the download directory itself) returned the same path
  forever — on the shared disk executor, stopping every Room write in the
  app. Now `base(n).ext` with a plausible extension (1–8 alphanumerics, not
  a dot-file), continuing a trailing `(k)`, 9 999 numbered tries, then a
  random suffix; an empty name becomes `received-file`.
- **Only the hidden engine session's page may drive a share**
  (`onPortConnected` checks `port.sender.session`): the bridge content
  script matches `http://127.0.0.1/*`, so any other tab that loaded a
  loopback page would otherwise hand over a port. The loopback server's
  write target is an `AtomicReference`, so closing it never waits on a
  write in flight.
- **A share link tapped while the Receive screen is ON TOP is handed to
  that screen** (`DownloadsActivity.receiveScreenOnTop` →
  `P2pReceiveFragment.onArrival`), never navigated to as a second copy. The
  copy used to stack, and FragmentManager brings the new fragment up before
  taking the old one down, so the old one's `onDestroyView` saw the session
  the link had just started (PREPARING/OFFERED) and DECLINED it, and its
  `onStop` took the phone off the nearby list after the new screen's
  `onStart`. Found by the Fable re-review of the fix commit; the same
  no-second-copy rule the notification's `openLiveShare` already followed.
- **No answer is ever dropped, and no answer path stays consumed by
  garbage.** The engine queues an answer that arrives while another is
  still being checked (`queuedAnswer`, tried if the first is refused,
  discarded if it applies) — it used to be dropped, so a stale clipboard
  reply racing the real LAN answer lost the real one for good. A body that
  is not an `FDR1.` code at all reopens the LAN listener and the mailbox
  poll the same way an engine refusal does (`reopenAnswerPaths`), without a
  notice (the user handed in nothing).
- **The share notification refreshes at most once a second** (a monotonic
  clock, `SystemClock.elapsedRealtime`) and `P2pShareService.start` is
  guarded like the rest of the app's FGS starts.

**Scanner is a full-screen `DialogFragment` (`<dialog>` destination), NOT a
`<fragment>`.** History: a `<fragment>` scanner destination destroyed the
send/receive fragment's view on navigate, and when the session still died
with the view that killed the live WebRTC session mid-handshake (the
returning fragment minted a fresh offer the scanned answer could never
match — the QR-reply path ALWAYS failed, only paste worked). The session is
the controller's now and would survive, but the dialog stays: the Receive
screen's nearby visibility and the scan-result handshake are the SCREEN's,
and a dialog shows on top, leaving the caller's view intact. `P2pScanFragment` uses CameraX (`camera-core/camera2/lifecycle/view`,
the app's only camera use) + **zxing decode** of the Y plane (rowStride-
compacted into a REUSED buffer — a fresh ~1-2 MB array per frame at 15-30 fps
is needless GC churn; NO ML Kit / Play Services — de-Googled devices stay
first-class). Result returns via the previous back-stack entry's
`SavedStateHandle` (`P2pScanFragment.RESULT_CODE`, the
CancelOperationDialogFragment pattern), validated against the expected
`FDS1.`/`FDR1.` prefix before delivery. **Don't turn it back into a
`<fragment>`.**

**Session lifetime is the CONTROLLER's, not the view's (the redo note at
the top of this section); the explicit Cancel confirms mid-transfer**
(`P2pShareBaseFragment.confirmCancel`, gated on
`controller.isTransferActive()` — true while the state is TRANSFERRING) so
an accidental tap doesn't silently discard an in-flight transfer, while back
and the toolbar's up just leave (the notification carries the transfer and
brings the user back). Clipboard paste goes through the shared
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
unbuilt alternative to a hosted share link for a backed-up file (the hosted
Cloud Backup refused to build one — see the history note at the top of the
Backup section — and the folder backup has no server to hang one on). The
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
  over a phone uplink); the app-to-app flow resumes a dropped connection (the
  `res`/`off`/`tail`/`begin` handshake above), but a BROWSER recipient only
  gets that if the page implements the same handshake AND has somewhere
  durable to keep the partial — File System Access can, the service-worker
  and Blob tiers cannot, so those still restart at byte 0; Safari is the
  worst tier. Privacy delta: the recipient's IP now touches
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

### Home — the address bar and the mark (home v6, frame N2): the field IS the browser's bar, nothing moves

Both homes (`fragment_home.xml`, `fragment_home_incognito.xml`) are ONE
composition: the search pill EXACTLY where the browser's address bar is
(the same 64dp toolbar rect under the status strip, the pill 8dp inside
it), the mark CENTRED in the page under it (the 48dp flame + the 22sp
wordmark + the blocked line; the 128dp privacy illustration + "Private
Browsing" in incognito), and the bottom navigation bar — nothing else at
rest except the ember behind the flame while a download runs. The cloud
transfer chip and deadline card went earlier (see "The home carries NO
cloud state" under Backup). The brief it answers: a calm identity
surface, not a dashboard — no top sites or recents (Chrome's and Brave's
shape), no download status (the Downloads screen's job). **ONE figure: the
ad blocker's lifetime count as a bare line under the wordmark** (frame H2,
see the line bullet) — the "blocked · saved" PAIR that sat there through
home v5 is GONE, and so is the lifetime trackers sheet that was its only
door (`TrackersInfoSheet`, `TopTrackersAdapter`, `TopTrackerDiffCallback`,
`MaxHeightRecyclerView`, `RecentDownloadsViewModel`, their two layouts, the
`TrackersInfo*` styles, the `MaxHeightView` attr and 17 strings in every
locale): on a 360dp phone at normal font scale in Spanish the two chips
plus the dot needed 304dp of a 296dp line and wrapped onto two rows
(reported as "blocked and download stack on top of each other on some
devices"), and no inset or glyph trim buys the long locales enough. **The
blocked-ads stats live in Settings → Privacy → "Trackers and ads blocked"**
(maintainer: "move the blocked ads stats to the settings"): a root row
whose summary is the LIVE lifetime count (`SettingsFragment.bindBlockedStats`,
"Protection active" at 0) over a `BlockedStatsFragment` sub-screen — the
`BlockedStatsPreference` hero (blocked since install / estimated data saved
at ~50 KB per request / blocked today, flat above the cards like the Backup
hero), a "Today by request type" card of read-only `CountPreference` rows
(title + a trailing count), a "Most blocked domains today" card rebuilt
from the pushed top-10 and hidden under three rows, and a footnote naming
the midnight reset and the estimate. All of it reads `GeckoUblockHelper`'s
uBlock tallies live, exactly what the retired sheet read; the per-PAGE
count stays on the security sheet, and Gecko's own ETP count stays on the
Enhanced Tracking Protection screen, a separate measurement never shown
beside this one. The home line is that screen's second door
(`SettingsActivity.EXTRA_OPEN_BLOCKED_STATS`); the old
`EXTRA_OPEN_TRACKING` deep link went with the sheet's "Manage protection"
button. The rules from its 2026-10 pass (canvas "Blocked lists,
unified"), each easy to undo:

- **Labels say what is MEASURED.** firedown.js buckets a blocked request
  by its request TYPE (script / image, beacon or ping / frame / anything
  else), never by purpose, so the rows read Scripts · Images and beacons ·
  Frames · Other requests. They read "Ads", "Analytics & telemetry",
  "Tracking pixels and beacons" and "Embedded frames", which claimed what
  the requests were FOR — no counter knows that. Both lists are today's
  and say so; the domain list counts every blocked host, the site's own
  included (m.youtube.com's stats endpoint — there is no third-party
  filter), so it is "Most blocked domains", not "Top trackers". The
  renames went to NEW string keys (`trackers_info_type_*`,
  `trackers_info_section_*_today`, `trackers_info_note`) so a locale left
  untranslated falls back to the true English, never keeps the old false
  label; the old keys were deleted in all 51 locales that had them.
- **One row anatomy for both blocked-host lists.** The Settings domain
  rows and the security sheet's "Blocked on this page" drill-in
  (`item_blocked_tracker_host`, `BlockedTrackerDetailAdapter`) are both a
  56dp grouped card, a 14sp host (body-medium; `HostCountPreference` in
  Settings) through `ui/HostLabel` — the registrable domain in onSurface,
  the subdomain before it in onSurface at 64%, ONE weight, the split by
  colour only (Chrome's omnibox) — and a plain tabular count, ALWAYS
  shown, 1 included. 16sp with a medium-weight domain shipped for one
  build and read as the loudest thing in the sheet. The subdomain
  shipped first in onSurfaceVariant, which is 1.32:1 from onSurface in
  dark theme, and the split was invisible on-device; 64% of the theme's
  own ink is ~2:1 (dark) / 3:1 (light) from the domain and still 4.5:1+
  on every ground incl. OLED and the incognito purple (measured in the
  class comment). The sheet was
  13sp monospace lines (~30dp) in one card with "×3" counts and nothing
  for a single block. The domain is uBlock's own PSL answer
  (`domainOf` in firedown.js, shipped beside every host in both lists);
  there is no PSL on the Java side, and a host whose domain is missing or
  doesn't split on a label boundary takes the domain style whole. The
  adapter re-shapes the bound rows on every list change
  (`PAYLOAD_SHAPE`): the list re-sorts by count as blocks land, so a row's
  first/last role moves without its content changing. No site icons on
  these rows, ever: fetching one contacts the tracker.
- **Counts change in place, with NO change animation, on both lists.**
  The extension pushes on every burst of blocks and every minute, and
  RecyclerView's default change animation cross-faded every row whose
  count moved — "the list flashes on every update". Both lists turn off
  `setSupportsChangeAnimations` (inserts and moves still animate), and the
  Settings domain group updates its rows in place (row i takes host i;
  rows are added or removed only when the length changes) instead of
  `removeAll()` + ten fresh rows per push.
- **The shield marks a count; `ads_click` marks the switch.** Every count
  surface wears `ic_shield_24` — the home line, the address bar, the
  security sheet's count row, the Settings door, the drill-in's header —
  and `ads_click_24` is left to the "Block ads and trackers" switch, the
  one control. The Settings door and the drill-in header both wore
  ads_click. The drill-in's header is one line, the count itself
  (`blocked_ads_summary`, "23 requests blocked on this page"); it was a
  "Blocked ads" title over that line. With nothing blocked the header
  hides and the EMPTY STATE stands alone: the shield in a 56dp tonal disc
  (`bg_empty_disc`, surfaceContainerHighest) over "Nothing blocked on this
  page yet" — no illustration in a bottom sheet.
- **The three hero numbers are onSurface in light theme, coral in dark**
  (`@color/blocked_stats_value`, values + values-night): the coral is
  2.3:1 on the light tile (22sp text needs 4.5:1), and a deeper coral
  that clears it reads brown (the `brandInk` history). Dark keeps the
  coral (6.1:1). "Data saved" carries the "~" of an estimate
  (`capture_size_estimate`).
- **An ill_ illustration belongs to this screen's ZERO state, not its top.**
  With numbers on screen a picture pushes them and the lists below the
  fold, and no data screen in the app has one (Backup and Storage lead
  with facts). On a fresh install the hero shows "Protection active"
  instead of three zeros; an `ill_*` picture in the ill_cloud /
  ill_couple family would sit there the way those sit on the Downloads
  and Backups empty views. Not drawn yet.

- **The pill is the REAL `GeckoToolbar`, in the browser's rect — never a
  second fake field, and never translated.** Because it is the real
  `AutoCompleteEditText`, a tap opens the IME natively (no
  `showSoftInput`), each home's `onFocusChanged` is the only hook (every
  blur path — back, outside tap, commit — ends there), the pill's colour
  lift and the home's search-engine glyph are untouched, and no second
  accessibility node exists. The hand-off to the browser costs nothing:
  `openUri` navigates with the toolbar laid out at a top MARGIN of the
  status inset — the same rect the browser's root-padded toolbar occupies
  — so the fragment swap is a seamless cut. Layout rules, both homes: the
  toolbar is a DIRECT child of the `CoordinatorLayout` (no `AppBarLayout`),
  declared AFTER the scroll view (equal elevation → child order decides z,
  and the `NestedScrollView` would otherwise take the tap); the status
  inset is a top MARGIN set in the fragment's insets listener, never
  padding (a self-padded box would be inset + 64dp tall, not the browser's
  bar); the content's `NestedScrollView` pads top AND bottom insets itself
  and its FrameLayout pads `address_bar_height` on top, so the centred mark
  group centres in the space UNDER the bar in portrait and landscape alike
  — no per-orientation dimens (`home_field_rest` / `home_mark_offset` are
  gone with the dock); the overlay's behavior is `AutoCompleteViewBehavior`
  installed in code (as in the browser), positioning off the toolbar's
  laid-out BOTTOM (`top + translationY + height − child.top`, which in the
  browser reduces to the old sum). The home toolbar carries no hairline
  (`toolbar_divider` is keyed on `!mHomeEnabled`): the page under it is the
  same tone, and a line would cut the mark off from its bar.
- **The focus motion is the pill's own colour/corner animation plus the
  suggestions FADING IN under it, on one clock.** `AutoCompleteView.
  showFadingIn(GeckoToolbar.FOCUS_ANIMATION_MS)` (225 ms, Chrome's
  URL-focus duration, fast-out-slow-in, on a hardware layer — alpha on a
  deep ViewGroup is an offscreen pass per frame otherwise); a hide is a
  plain `updateVisibility(false)` cut, which also cancels a running fade
  and resets alpha, so the home never sits under a half-transparent overlay
  while the keyboard closes. The browser keeps its cut both ways. Nothing
  translates and the mark does not fade.
- **Why nothing moves — the field-inside-the-page compositions, all three
  failed on-device (maintainer, 2026-10-12).** The day's history, kept so
  it is not re-derived: (1) the mark-over-field stack with the toolbar
  DOCKED mid-screen over a `Space` in the content and raised to the top on
  focus — Chrome's fakebox model, `HomeToolbarDock` (translation resolved
  from window coordinates, a 0→1 fraction tween, the mark fading out and
  the overlay fading in with the fraction); the ~350dp trip read weak next
  to Chrome ("their animation is way smoother, also their bar is not at
  the middle, is way upper but not at the top, so the animation does not
  take that long path"); (2) the stack anchored near the top (170dp trip)
  — "the logo and firedown look weird there": measured from the screenshot
  the mark spanned 93–256dp and 485 of 853dp were empty under it, a splash
  screen pinned to the top; (3) P1 — the field alone 88dp down (an 80dp
  rise), the mark at the true centre, picked from canvas frames P0–P4 —
  "no does not work. In chrome works because above the bar we have a
  toolbar and in the bottom the recent sites … in our case we have bottom
  navigation bar so we break harmony we need to keep it above". The take
  that settled it: the field is CHROME. Firedown's chrome lives on the two
  edges — the address bar on top, the navigation bar below — and the home
  is the page between; a field inside the page is chrome stranded in
  content, which Chrome's NTP affords only because its fakebox has a
  toolbar row above it and shortcut tiles below. Frames N1 (the field
  docked over the bottom bar — right only if the address bar moves to the
  bottom app-wide), N2 (this), N3 (N2 whose pill EXPANDS into the
  suggestions sheet on focus, Material's search-bar → search-view morph);
  the maintainer picked N2, N3 stays the optional polish. The dock, the
  slot `Space`, the rest-resolve and the fraction tween are retired code;
  don't rebuild a field-in-page composition over a bottom bar.
- **The blocked line — frame H2's shield line, BARE (maintainer,
  2026-10-12: "bring the blocked shield glyph and text back to home screen
  below the logo like H2", then, after one build on a card, "make it
  transparent").** One line: `ic_shield_24` (16dp) + "N trackers blocked"
  (`tracking_status_blocked` — zero new strings, the words name no engine),
  glyph and text both `colorOnSurfaceVariant` (H2's grey), 6dp gap, 13sp,
  the text 16dp under the wordmark, `maxLines=2` so a long locale wraps
  rather than ellipsizes the number. NO container: it shipped for one
  build on a `colorSurfaceContainerHigh` pill ("and probably put in a
  material card background") and the on-device screenshot named the fault
  — that fill is the address field's own token (#292A2C in dark, both
  measured identical) in the field's own stadium shape, so the card read
  as a second, smaller search field, and a glyph + label in a pill is
  Material's chip/button shape, an ACTION, on a page whose point is one
  action ("takes attention from the address bar"). The take that settled
  it: the line is a FACT, a caption of the mark — Brave prints its new-tab
  stats as plain text, no big browser puts a pill under its logo. A
  COLOURED card was weighed and rejected (louder, not quieter: the flame
  is the page's only colour, and a fill is earned by work, a tally gets
  none — the chip-era rule); an OUTLINED chip is the recorded fallback if
  the bare line ever reads as dead text on-device, still a control shape,
  so not the first move. Bound in `HomeFragment.onViewCreated` from
  `GeckoUblockHelper.getCumulativeBlockedLive()` with the settings row's two
  states — the locale-formatted count, else "Protection active"
  (`trackers_info_idle`) — so it is never empty and never GONE. Tap →
  `SettingsActivity.EXTRA_OPEN_BLOCKED_STATS` → `settings_blocked_stats`,
  replacing the settings list on the back stack (the `EXTRA_OPEN_SYNC`
  contract) so Back returns to the home; the `MaterialCardView`
  (`home_blocked_line`) survives ONLY as the touch target — transparent,
  zero elevation, no stroke — for its rounded ripple bounded to the line,
  H2's border-radius button; the resting affordance may be invisible
  because the Settings row is the primary door. The "saved" half stays
  gone (the two-row wrap above), and the incognito home carries no line (a
  figure there leaks activity).
- **What stays a cut, deliberately:** the home → browser swap (same rect,
  no shared element needed). Not built: N3's expand morph, a dedicated
  fakebox, any change to `GeckoToolbarBehavior` (browser only). The pill
  keeps the toolbar's own 8dp inset (344dp on a 360dp phone) — the canvas
  frames drew 16dp.
- **Sizes are MEASURED, not picked (maintainer, 2026-10-12: "is the logo
  too big and the text?").** It shipped at a 56dp flame over a 26sp
  wordmark; measured against the 48dp pill in the device screenshot, the
  stacked pair was ~100dp of mark over the field — about twice Chrome's
  logo-to-fakebox ratio — and the NAME led the eye before the field. Now
  48dp + 22sp (the glow is the flame's size; the incognito title follows the
  wordmark): the flame stays the subject (40dp reads as an app icon, which
  it was when a stat line shared the block), the name is a label. Don't
  grow either without re-measuring the block against the pill.
- **Verified on-device** (2026-10-12): the real toolbar taking the tap on
  the home (the mechanism N2 keeps), the three field-in-page builds — each
  read wrong, see the history bullet — and N2 itself (the mark measured
  centred in the page under the bar) with the blocked line on its one-build
  card (read as a second search field, see the line bullet). Not yet: the
  bare line, the overlay fade, landscape.

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

## Backup — encrypted per-file backup into a FOLDER the user picks (SAF)

The optional **Backup** feature backs a finished download up, encrypted, into
a folder the user chose once through the system picker
(`ACTION_OPEN_DOCUMENT_TREE`): Google Drive, Nextcloud, an SD card, the
phone itself — any provider whose roots take a folder pick, which not every
cloud app's do (see the picker bullets below). **There is no
Firedown server in the path**: the folder IS the truth, the provider's own
app does whatever syncing it does, and Firedown never learns whether a byte
left the phone. It is a SEPARATE feature from the local **Safe Folder**
(`file_safe`, which never leaves the device): user-facing copy says "Backup",
internal classes keep the `Cloud*`/`Vault*` names of the hosted service this
replaced (`CloudBackupManager`, `VaultEngine`/`VaultBackupWorker`/
`VaultRestoreWorker`/`VaultThumbnail`/`VaultEntry`, `CloudBackupListFragment`,
`CloudBuyButtonPreference`, the `cloud_*`/`settings_cloud_*`/`home_cloud_*`
string keys, the `…cloud.backup.*` pref keys) — the shapes survived the
server. The one cardinal rule is unchanged: **whoever holds the bytes never
sees plaintext** — every file is encrypted on the device (`VaultCrypto`,
per-file AES-256-GCM DEK wrapped under the storage master key) before it is
written, and the folder holds opaque ciphertext under opaque names.

The place can also be a **WebDAV server** the user signs in to or an **S3
bucket** the user brings keys for (`WebDavStore`, `S3Store`, below). There
Firedown itself talks to the server, so "Firedown never learns whether a
byte left the phone" holds for a folder only; what stays true for all
three is that it is the USER's storage, there is still no Firedown server
in the path, and the bytes are ciphertext before they leave the phone.

**History (2026-10): the hosted service is GONE, deleted in one release.**
Until 1.2.01 this was "Cloud Backup" against `storage.firedown.app` (the
`firedown-api` `storage-api` binary + R2), paid by anonymous blind-signed
credits from `mint.firedown.app` (Lightning + on-chain Bitcoin), with a
browser client at `firedown.app/backup/` unlocked by QR pairing. All of it —
`StorageApiClient`, `CloudWatchWorker` (the lapse notifications), the
payments stack (`MintClient`/`BlindSignature`/`CreditPurchase`/
`CreditSettlement`/`CreditSettleWorker`/`PendingPurchase`/`BuyCredit*`), the
pairing stack (`PairSeal`/`PairClient`/`PairApprovalViewModel`), their
layouts, tests and ~160 strings — was deleted on the same branch that
shipped the folder backup, with no read-only phase and no date (the
maintainer's call: "the delete date is irrelevant"). `docs/SERVER-SUNSET-
PLAN.md` is the checklist for what follows on the server side (`api.
firedown.app` moves to the www host under its NAME; the cloud VPS is
destroyed; old APKs get `410 Gone`). The git history carries the old
design and its rationale (the public-share-link refusal, the pricing
model, the pairing SAS) — there is nothing to re-derive here because there
is no server to attach any of it to: **don't build a share endpoint, a
credit, a quota, a pairing or a sync server for this feature**. A
"browser-recipient" of a backup is the P2P share's job (see that section).
What carried over UNCHANGED, and is why `VaultCryptoTest` and the shared
vector still matter: the on-disk chunk format (FDVC2, 8 MiB plaintext per
chunk, 34 bytes overhead, (objectId, index) as GCM AAD), the sidecar's
`BookmarkBlob` framing, the storage key derivation (HKDF
`firedown/storage/v1` off the shared recovery code), and every Backups-list
and Storage-screen invariant below.

- **Shared identity, no on/off switch.** Backup reuses the bookmark-sync
  **recovery code** (`SyncSecrets` → `SyncIdentity`): one code derives the
  storage key that encrypts every object AND the account id
  (`accountBase32`) that names the folder's sub-directory. It is
  **action-driven**: a finished download's options sheet ("Back up to
  <provider>") sets it up on demand. `Preferences.CLOUD_BACKUP_ENABLED` only
  tracks "a folder was chosen, keep the shared code" so signing out of one
  feature can't strand the other. **`isSetUp()` = a folder is chosen AND a
  code exists** — a code present only because bookmark sync is on does NOT
  count, and a folder with no code is not addressable (the key gate below).
  `SyncManager.linkWithCode` (adopting another device's code) drops every
  cached per-account value — `SYNC_LAST_VERSION`, the last-synced/error
  pair, `CLOUD_LAST_TOTAL_BYTES` / `CLOUD_LAST_FILE_COUNT` — and the caller
  calls `CloudBackupManager.forgetCachedStatus()` BEFORE `updateState`, so
  no surface paints the previous account's figures: a different code is a
  different directory in the same folder, and the previous account's files
  survive there untouched under their own code, which is the only key to
  them (hence the replace-confirm the adopt dialog shows post-key).

### `FolderStore` — the folder, its layout, and the one failure it names

`sync/FolderStore.java` is the ONLY thing that touches the picked tree
(`FOLDER_BACKUP_TREE_URI`, a persisted read+write grant taken in `adopt`
and released in `forget`/on re-pick; the old folder's files are never
touched). Layout, visible to the user in their Drive/file manager (so named,
not hidden):

```
<picked folder>/Firedown Backup/<account base32>/objects/
    <objectId>.fdvc    the ciphertext: FDVC2 chunks, back to back
    <objectId>.entry   the encrypted sidecar: ONE VaultEntry (name, size, mime,
                       wrapped DEK, thumbnail, origin, downloadedAt, backedUpAt)
```

- **The sidecar is the commit marker, written LAST.** An object without one
  is a backup that never finished and is swept once older than
  `ORPHAN_MIN_AGE_MS` (24 h); a sidecar without its object is SKIPPED and
  never deleted (a provider that has not synced the big file yet, or a
  conflict copy — see below). Everything the server used to hold encrypted
  (the manifest) is now these sidecars, under the same `BookmarkBlob`
  framing and the same storage key, one per object, capped at
  `MAX_ENTRY_BYTES` (512 KB — a sidecar is a few KB; refuse a stray multi-MB
  file rather than read it into memory). **"The manifest" in the Backups
  list code means this listing** (`VaultEngine.loadManifest()` = every
  sidecar, decrypted) — the name stayed, the mechanism is a directory walk.
- **Why sidecars, not ONE index file.** A sync-lagged folder has no
  optimistic concurrency: two phones writing one index would overwrite each
  other's entries through the provider's own conflict handling, silently.
  One small file per object has no shared state to lose — the listing is the
  index, rebuilt on every load, and a provider's conflict copy of a sidecar
  (`<id> (1).entry`, `<id>.entry (1)`) is matched to its id (`objectIdOf`)
  and never makes a second row. Per id the EXACTLY named file wins
  (`Listing.add`, whatever order the place lists them in: a server's key
  order puts `<id>.entry (1)` after `<id>.entry`, and last-wins let a stale
  copy show old fields or a corrupt one hide the backup); the copies are
  kept in `entryCopies` and tried in turn when the chosen sidecar does not
  open (`collectEntries`, one batch per round). FolderStore's single delete
  removes the copies too. Don't
  reintroduce an index file "for speed" — it reintroduces the lost-update
  class the hosted OCC existed to prevent, with no server to arbitrate.
- **`DocumentsContract`, not `DocumentFile`**: one query per directory
  listing instead of a stat per file (the same contract the download-mirror
  restore speaks). Objects are created under a mime no provider maps to an
  extension (`OBJECT_MIME` = `application/vnd.firedown.vault`): the
  external-storage provider appends the mime's extension to a name whose own
  extension does not map back, and `application/octet-stream` turned
  `<id>.fdvc` into `<id>.fdvc.bin`.
- **`UnreachableException` is the one named failure** (grant revoked — the
  provider app signed out/updated/uninstalled, the SD card left; the tree
  gone; the provider not answering), distinct from a plain `IOException` (a
  failed write on a reachable folder). Every consumer treats it as "the
  FOLDER, not the network": `VaultBackupWorker` fails terminally with
  `SLUG_FOLDER` and posts the folder-lost notification ONCE (tap → the Backup
  screen), the Backups list's transfer row names it
  (`folder_backup_transfer_lost`), and the Backup screen's hero shows the
  lost state with the CTA reading "Choose the folder again".
  `isReachable()` = the persisted grant is still held with read+write AND
  the tree's root document answers a query — one resolver round trip, OFF
  the main thread only (Drive answers a cold listing from the network).
- **`providerLabel()`** names WHERE the folder lives for "Back up to Google
  Drive" / "Firedown Backup on Google Drive": the provider app's label, or
  for the system providers the device's own words (this phone / SD card /
  Downloads); falls back to the authority, never to an empty string.
- `BackupLayout.newObjectId()` mints the 32-char lowercase hex id (16
  random bytes) the AAD binds chunks to — client-minted now, same shape the
  server issued.
- **The layout and its rules are not FolderStore's own any more** — they
  are `BackupLayout`'s (names, sidecar codec, `collectEntries`), shared with
  `WebDavStore` (next section). FolderStore is the Storage Access Framework
  half: the grant, the picker-side write check, the labels, and the
  `DocumentsContract` calls behind `BackupStore`.

### `BackupStore` — one layout, three places: a folder, a WebDAV server or an S3 bucket

`sync/BackupStore` is the contract `VaultEngine` and `VaultObjectReader`
talk to: list, write a sidecar, open a NEW object for exactly N bytes, open
an object as a stream or for positional reads, delete one, delete an
account, plus reachability and labels. `BackupStore.UnreachableException`
lives here (it was `FolderStore.UnreachableException`): "the place needs the
user", against a plain `IOException`, "a retry may fix it", and
`BackupStore.FullException`, "the place is out of space" (terminal, its own
slug). Three implementations: `FolderStore` (a picked SAF tree),
`WebDavStore` (a WebDAV server the user signs in to: Nextcloud, ownCloud, a
NAS) and `S3Store` (an S3-compatible bucket, see below).

- **ONE layout, in `BackupLayout`** — the names, the sidecar framing
  (`encodeEntry`/`decodeEntry`), and `collectEntries` (the commit rule: an
  object without a sidecar is swept after 24 h, a sidecar without its
  object is skipped and never deleted, a sidecar this key cannot open is
  skipped, a conflict copy's file name wins over the id inside it). So a
  Nextcloud folder synced to the phone by its app (read through
  `FolderStore`) and the same Nextcloud reached over WebDAV read the same
  files the same way. Don't put a layout rule in a store.
- **`BackupStores.open` opens the STORED kind of place**
  (`BackupStores.Place`: folder / webdav / s3, one key in the backup-excluded
  secret prefs; an install from before the key migrates to webdav when a
  server is saved, else folder — it used to be inferred, "a saved WebDAV
  sign-in wins", which a third kind made ambiguous). Choosing one forgets
  the others (`adoptFolder` / `adoptWebDav` / `adoptS3` → `choose` clears
  the other servers' settings, a server releases the tree's grant), and
  none touches the files already in the place left behind. The network
  stores are cached per process and rebuilt when their settings'
  `generation()` moves. `CloudBackupManager.store()` is OFF the main thread
  only (the first call unwraps the keys and loads the trust store);
  `place`/`isSetUp`/`hasFolder`/`providerLabel`/`locationLabel` read the
  settings directly because the Backup screen asks them on the main thread.
- **`VaultEngine` declares the object's exact length up front**
  (`objectLength` = size + 34 per chunk) and opens the source BEFORE the
  object, so a source that will not open costs no remote request; the
  cleanup delete runs only once an object may exist (`attempted`).

### `WebDavStore` — backups on a server the user signs in to

Built on 2026-10-13 behind the canvas boards 5–8 ("Where to back up" gains
a "WebDAV server" row → sign in → trust a self-made certificate →
connected); the screen is described in the next section.
`CloudBackupManager.checkAndAdoptWebDav(address, user, password, pin,
onResult)` is its one entry point, and the result's `kind` maps 1:1 to the
board's error states. Pieces:
`WebDavClient` (the wire), `WebDavStore` (the layout on top), `WebDavCheck`
(find and test a server before saving), `WebDavConfig`, `WebDavSettings`
(the saved sign-in, in the backup-EXCLUDED `secret_shared_prefs` beside the
recovery code, the password sealed under the same keystore key through
`SyncSecrets.seal`/`open`).

- **One PUT per object, streamed, exact length.** The engine writes into an
  okio `Pipe` that a one-shot `RequestBody` drains on its own thread; the
  `Content-Length` is the declared length; `close()` waits for the answer
  and throws unless it was 2xx. A stream closed short (a cancel, a file that
  shrank) cancels the call, a longer write is refused, and the server
  stores nothing either way (Nextcloud and Apache write uploads to a
  temporary name). No temporary copy on the phone. The costs, stated: a
  dropped connection restarts that FILE from zero (no resume, no Nextcloud
  chunking v2), and a proxy that caps request bodies caps backups
  (Cloudflare's free plan: 100 MB).
- **Sign-in is preemptive Basic, to the server's own origin only** (an
  application interceptor; OkHttp drops the header on a cross-host or
  cross-scheme redirect). A streamed body cannot be re-sent after a 401
  challenge, so waiting for one is not an option. No Digest. No
  `Expect: 100-continue` (a server that ignores it makes every upload wait
  out the read timeout). The client is the app's (DNS, pool) with EVERY
  interceptor removed and cookies off.
- **Trust: the phone's store first, then ONE pinned leaf** (`ServerTrust`,
  shared with `S3Client` since the S3 store). A
  `X509ExtendedTrustManager` wraps the system one (so Android's network
  security config and its hostname-aware checks run exactly as before); a
  chain it refuses passes only when the leaf's SHA-256 equals
  `WebDavConfig.pinSha256`, and the same pin covers a name mismatch (a NAS
  reached by IP). A refused leaf is recorded (`rejectedCert()`) so the
  check can show subject, issuer and the fingerprint as servers print it
  (`4F:9A:…`) — the board's "Trust this server?" dialog. A pinned
  certificate that CHANGES is an `UnreachableException`: backups stop until
  the user looks, which is what the board promises.
- **Failure classes** (`statusError`/`translate`): 401, 403, a refused
  certificate, the collection gone (404 on the base, checked whenever the
  objects collection 404s) → `UnreachableException`, so the worker fails
  with `SLUG_FOLDER` and the folder-lost notification; 507 (out of space)
  → `BackupStore.FullException`, terminal with `SLUG_FULL` (it used to be
  retried until "the write kept failing"); timeouts and other 5xx → plain
  `IOException`, retried. Every object and sidecar
  write first PROPFINDs the objects collection (Depth 0) and MKCOLs the
  three levels when it is missing — one cheap request instead of a cache of
  "already made" that a delete on the server would make stale (409s).
- **A listing is one Depth-1 PROPFIND plus a GET per sidecar not seen at
  that version** (the ETag, else size + date). Decoded sidecars sit in a
  16 MB LRU keyed by account and id (`SidecarCache`, shared with
  `S3Store`), shared with whoever listed them, so
  the Downloads marks, the status and the Backups list don't each re-read
  every sidecar; a first listing fetches four at a time. A sidecar that
  404s between the listing and the GET is skipped; any OTHER read failure
  fails the listing — a network list is never silently short.
- **Streaming reads** are one ranged GET per 8 MiB chunk (`getRange`); a
  server that ignores Range (200) is still read right, by skipping; a 206
  whose `Content-Range` starts elsewhere is refused before decryption.
- **PROPFIND parsing** is namespace-aware SAX over a body capped at 32 MB;
  a DOCTYPE is refused outright (no multistatus has one, and it is the door
  to entity expansion on whatever parser Android ships); only 2xx propstats
  count (Nextcloud's 404 propstat lists the props a collection lacks,
  empty); hrefs resolve against the URL that answered (relative, absolute
  path or full URL); a Depth-0 answer whose one href is spelled oddly is
  still the resource asked about.
- **`WebDavCheck`**: a bare host gets `https://`, `davs://`/`dav://` map to
  https/http; the typed address is tried, then (unless it already names
  `remote.php`) `remote.php/dav/files/<user>/` and the legacy
  `remote.php/webdav/` (which also works for an account that signs in with
  an email address); a 401 on one candidate is not final; the address SAVED
  is the one that answered after redirects, and an https→http downgrade is
  not accepted. Then the write check (FolderStore's twin): make
  `Firedown Backup` when missing, write a small file, delete it, remove the
  folder again if a later step failed. Kinds: `OK`, `BAD_ADDRESS`,
  `UNREACHABLE`, `UNTRUSTED_CERT` (+ `CertInfo`), `AUTH_FAILED`,
  `NOT_WEBDAV`, `READ_ONLY`, `FULL`, `MOVED`.
- **A redirect to ANOTHER host is never saved — `MOVED`.** The check
  saved the URL a redirect landed on, any host, and the store then sent the
  password preemptively to that origin on every request: a hijacked name, a
  captive portal or a compromised redirect was one sign-in away from the
  credential (found by the fault harness, 2026-10). Now a candidate whose
  final URL is on another host is skipped, and if it answered as a WebDAV
  folder the result is `MOVED` with `movedTo`; the page says "This address
  forwards to <url>. If that is your server, enter that address instead"
  (`webdav_error_moved`, 16 locales) and saves nothing. A same-host move
  (http→https, another port, another path) is followed as before; an
  https→http downgrade stays refused. OkHttp sends no Authorization across
  a cross-host hop by itself. User info in a typed address
  (`https://alex:pw@nas/dav/`) is dropped by `normalize`: the URL sits in
  plain prefs and is printed.
- **Backup and restore work carry a CONNECTED constraint while WebDAV is
  the place** (`BackupStores.transferConstraints`, both request builders);
  a folder stays unconstrained (it may be on the phone or a card).
- **Nextcloud moves deletes to its trash bin**, where they count against
  the quota until emptied. That is the server's policy; nothing a client
  can skip, worth one line in the screen's copy.
- Not supported, by decision for now: Digest, resumable/chunked uploads,
  client certificates, Nextcloud Login Flow v2 (the user types an app
  password).
- **The screen** (canvas boards 5–8). "Where to back up" has a **"WebDAV
  server"** row (`dns_24`, after Cloud storage) that opens
  `WebDavConnectFragment` (`settings_webdav`, a full page for the feedback
  page's reasons: three fields, wrapping helpers, the keyboard up the whole
  time) with `WebDavConnectViewModel` holding the state across rotation.
  Every failure lands on the field it is about: a refused sign-in under the
  password, everything about the server (not found, not WebDAV, read-only,
  full, not an address, unreachable) under the address. A plain `http://`
  address swaps the address helper for "isn't encrypted" and the button for
  "Connect anyway", in words, no warning colour. An `UNTRUSTED_CERT`
  answer opens **"Trust this server?"** (`dialog_webdav_trust`: the host,
  the SHA-256 in the colon form servers print, twelve bytes a line,
  selectable, and "if it changes, backups stop"); Trust re-runs the check
  with that pin. The view model keeps the certificate while the dialog is
  torn down with the view, so a rotation asks again rather than dropping
  the question. On OK the page pops back with the folder pick's own
  snackbar. **A RECONNECT is one tap**: opened while a server is saved,
  the page is prefilled with its address and user, and an empty password
  means the saved one. That reuse is `WebDavCheck.runReusing`, and it is
  bound: the saved password goes only to the same scheme + host + port AND
  the same user (`sameServer`), anything else with no password is
  `AUTH_FAILED` before a byte is sent; a null pin reuses the certificate
  already trusted for that server, and a changed one comes back as a new
  trust prompt. The saved sign-in is unwrapped on the folder pool, never on
  main. The field watchers attach in `onViewStateRestored`, after the
  fields get their text back: a restore is a setText, and an earlier
  watcher wiped the error shown under a field on every rotation.
- **The Backup screen speaks about a SERVER when the place is one.** The
  folder row wears `dns_24` and reads "<host> · WebDAV"
  (`webdav_folder_row`, untranslatable); the hero's lost state reads "Can't
  reach your backup server" with a body naming offline / a changed password
  or certificate (`CloudStatusPreference.setPlace`); the CTA in that state
  is **"Reconnect"** and opens the prefilled sign-in page directly, not
  the chooser; the worker fails a server with **`SLUG_SERVER`** (its own
  words on the row, the snackbar and the notification, and the Backups
  header's lost line). The lost notification names the PLACE: it used to
  be passed the FILE name, so the folder's "Firedown can't reach %1$s"
  read "can't reach video.mp4"; it takes `store.providerLabel()` now, for
  folders and servers alike.
- **A server to test against: `scripts/webdav-server/DavServer.java`.** One
  file, JDK only (Android Studio's JBR runs it), no build:
  `java scripts/webdav-server/DavServer.java --root ~/dav-test` serves a
  directory on 0.0.0.0:8080 with Basic sign-in alex/secret and prints the
  LAN addresses to type on the phone. `--layout nextcloud` serves it where
  Nextcloud serves a user's files (`/remote.php/dav/files/<user>/` and
  `/remote.php/webdav/`) with a site root that is not WebDAV, so typing
  host:port exercises the discovery; `--tls` makes a self-signed EC
  certificate on first use (kept in `--keystore`, so the pin survives a
  restart) and prints its SHA-256 to compare with the phone's trust prompt;
  `--read-only` and `--quota BYTES` produce READ_ONLY and FULL. It is WebDAV
  class 1 minus PROPPATCH: PROPFIND (Depth 0/1, a named prop it lacks in a
  404 propstat like Nextcloud, allprop for an empty body), GET/HEAD with a
  single range, PUT to a hidden temporary file renamed into place (an
  interrupted upload never leaves half a file), MKCOL, DELETE, COPY, MOVE;
  traversal, encoded slashes, symlinks out of the root and fragments are
  refused. litmus: basic 16/16, copymove 13/13, http 4/4; props 11/14, the
  three failures being PROPPATCH, which Firedown never sends. Under a
  non-UTF-8 locale the JVM cannot name non-ASCII files (it says so at
  start); Firedown's own names are ASCII. It is a TEST server (one fixed
  keystore password, no rate limits): never ship it, never expose it beyond
  a home network.

### `S3Store` — backups in an S3-compatible bucket the user brings keys for

Built on 2026-10-18: "Where to back up" gains an **"S3 storage"** card
(`database_24`) → `S3ConnectFragment` → the bucket is THE place. Any
service that speaks S3: Backblaze B2, Cloudflare R2, Wasabi, MinIO,
Hetzner, Scaleway, IDrive e2, Storj, AWS. The layout is `BackupLayout`
unchanged, under `<prefix>Firedown Backup/<account>/objects/` in the
bucket; VaultCrypto, FDVC2, the chunk constants and the sidecar rules are
untouched, so a bucket mounted elsewhere reads the same files the same way.
Pieces: `S3Signer` (SigV4, pure JDK), `S3Client` (the wire and the failure
classes), `S3Store` (the layout on top), `S3Check` (find and test a bucket
before saving), `S3Config`, `S3Settings` (the saved bucket). Direct sign-in
to Google Drive / Dropbox / OneDrive is OUT of scope by decision (OAuth and
provider SDKs); those go through the folder picker. Don't add an S3 SDK
either: everything is plain REST through the app's OkHttp.

- **Signing: SigV4 by hand, the REAL payload SHA-256 on every request — no
  `UNSIGNED-PAYLOAD`, no `aws-chunked` streaming signatures.** Plain SigV4
  is what every S3 implementation must accept; the two alternatives are
  extras providers support unevenly. A signed body also lets the server
  refuse a corrupted part instead of storing it (a plain-http MinIO at
  home), and because the part is already in memory, resending it after a
  dropped connection or a `SlowDown` costs nothing. The cost is one 8 MiB
  buffer during a backup and a SHA-256 pass. The S3 flavour: the path is
  URI-encoded ONCE and never normalised, the canonical query is sorted by
  encoded name then value, `x-amz-content-sha256` always travels, the Host
  header is set explicitly to the value signed, and the query sent IS the
  canonical query, so what is signed and what is sent cannot differ.
  Redirects are NOT followed (a redirected request carries a signature for
  the wrong host, and S3's 301/307 mean "wrong region", an answer).
  Verified against AWS's published S3 examples (including the presigned GET
  firedown-api's Go presigner pins) and 66 botocore vectors.
- **Uploads: one part in memory at a time, parts on FDVC chunk
  boundaries.** An object up to one chunk (8 MiB + 34 B) is ONE PUT; a
  larger one is a multipart upload whose parts are whole chunks
  (`partSize`: one chunk per part up to 10 000 chunks, ~78 GiB; then two,
  and so on), so every part but the last has the same length (R2 requires
  it) and is at least 5 MiB (everyone requires it), and every length is
  known before it is sent. Never a temporary copy of the object. A stream
  closed short (a cancel, a file that shrank) or any failure ABORTS the
  multipart upload, so a running process leaves no billed parts; a part
  whose body dropped mid-send is sent again. **Stray uploads from a process
  death**: at most once a day per store, a listing aborts this account's
  unfinished uploads older than 24 h that this store does not have open
  (`sweepUploads`, `ListMultipartUploads` scoped to the account's prefix).
  A lifecycle rule for incomplete uploads on the bucket is the belt (R2
  aborts them after 7 days by default; on B2, Wasabi or MinIO the user adds
  one).
- **Reads**: a restore is one GET; the stream player one Range GET per
  8 MiB chunk (`getRange`, after a HEAD for the length); a 206 whose
  `Content-Range` starts elsewhere is refused before decryption; a 200 to a
  ranged request is read by skipping.
- **Listings**: `ListObjectsV2` pages under the account prefix (sizes and
  dates from the listing, no HEAD per object; a listing past 1000 pages is
  refused rather than read short), then a GET per sidecar not seen at that
  ETag through `SidecarCache` — the 16 MB decoded-sidecar LRU WebDAV had
  inline, now one class both stores share. A sidecar that vanished between
  the listing and the GET is skipped; any other read failure fails the
  listing. `deleteAccount` deletes sidecars first, then the rest, through
  `DeleteObjects` in batches of 1000 (Content-MD5, quiet), falling back to
  single deletes for a provider that answers it with 501, 405 or
  `NotImplemented`.
- **Only calls B2, R2, Wasabi and MinIO all implement**: object PUT / GET
  (Range) / HEAD / DELETE, `ListObjectsV2`, `DeleteObjects` (+ the
  fallback), the four multipart calls and `ListMultipartUploads`. Nothing
  relies on checksum headers, tagging, ACLs or object lock.
- **Failure classes** (`S3Client.classify`): wrong or rotated keys
  (`InvalidAccessKeyId`, `SignatureDoesNotMatch`, 401), a gone bucket
  (`NoSuchBucket`), access denied (`AccessDenied`, 403), a moved region
  (`PermanentRedirect`, 301/307, `AuthorizationHeaderMalformed` naming
  another region) and a changed pinned certificate are `RefusedException`,
  an `UnreachableException` (made non-final for it): the worker fails with
  `SLUG_SERVER`, the Reconnect wording and the one notification. 5xx (not
  501/507), 429, `SlowDown`, `RequestTimeout`, `InternalError` and dropped
  connections are retried in the client up to 3 times with backoff,
  honouring `Retry-After` (seconds or an HTTP date, capped at 30 s), then
  thrown as a plain `IOException` the worker retries. **A storage cap or
  quota** (507, `QuotaExceeded`, B2's "cap exceeded", MinIO's storage-full
  codes) is the new **`BackupStore.FullException`** → `SLUG_FULL` ("Storage
  full", its own sentence, terminal, one notification); WebDAV's 507 moved
  onto it from "retry, then write kept failing". **A 404 is never a
  `FileNotFoundException`** except on the reads that allow a missing
  object: the worker reads that exception as "the SOURCE file is gone".
  An `<Error>` inside a 200 to CompleteMultipartUpload is a failure.
- **Clock skew**: `RequestTimeTooSkewed` is answered once by re-signing
  with an offset from the server's `Date` header (else its `ServerTime`),
  kept for the store's lifetime — a phone with a wrong clock pays one
  request per process.
- **`S3Check`** before anything is saved: endpoint normalised (`https://`
  added; a bucket typed into the host is taken out), the region guessed
  from known hosts when empty (B2, Wasabi, AWS, Hetzner, Scaleway,
  DigitalOcean; `auto` for R2; else `us-east-1`), path-style then
  virtual-hosted (virtual first for AWS or a bucket-in-host address, never
  for an IP / `localhost` / a non-DNS bucket name; the override is the
  Addressing dropdown), a region the server NAMES is followed once and
  saved, then the write check under the prefix: list, PUT a small object,
  GET it back (a mismatch is `NOT_S3`), DELETE. Kinds map 1:1 to the page's
  fields: `OK`, `BAD_ADDRESS`, `NOT_S3`, `UNREACHABLE`, `UNTRUSTED_CERT`,
  `AUTH_FAILED`, `NO_SUCH_BUCKET`, `ACCESS_DENIED`, `WRONG_REGION`, `FULL`.
  **Reconnect** (`runReusing`): an empty secret means the saved one ONLY
  for the same endpoint (scheme, host, port), the same bucket AND the same
  access key ID — anything else with no secret is `AUTH_FAILED` before a
  byte is sent; a null pin reuses the certificate trusted for that endpoint.
- **Trust is `ServerTrust`**, lifted out of `WebDavClient` and shared: the
  phone's store first, then ONE pinned leaf; the WebDAV page's "Trust this
  server?" dialog (`dialog_webdav_trust`) is reused as is (a home MinIO with
  a self-made certificate).
- **Secrets**: `S3Settings` keeps BOTH keys (access key ID and secret)
  sealed with `SyncSecrets.seal` in the backup-excluded
  `secret_shared_prefs`, like the WebDAV password; endpoint, region,
  bucket, prefix and addressing are plain (labels and prefill). The sign-in
  page therefore reads the saved bucket off the main thread
  (`loadS3Prefill`, the view model's `saved()`). Choosing another place
  `clear()`s every field; NOTHING in the bucket is touched. A key that no
  longer unwraps reads as not set up.
- **The kind of place is STORED, never inferred** (`BackupStores.Place`,
  `firedown.backup.place` in the same backup-excluded prefs): `open`
  switches on it, `choose` records it and clears the OTHER servers'
  settings, and `adoptFolder` / `adoptWebDav` / `adoptS3` each forget the
  rest. An install from before the stored kind migrates on its first read
  to exactly what it had: webdav when a server is saved, else folder. In
  the backup-EXCLUDED prefs on purpose: a restore onto a new phone carries
  no sign-in and migrates like an old install instead of naming a place
  whose keys stayed behind. The workers carry a CONNECTED constraint while
  the place is a server or a bucket (`transferConstraints` = `place !=
  FOLDER`).
- **Logging: none.** No S3 class logs anything: a request carries the access
  key ID and a signature as good as the secret for the minutes it is valid,
  and a presigned URL is the same. Keep it that way; a debug log of a
  request is a credential in logcat.
- **The page** (`settings_s3`, `S3ConnectViewModel` across rotation): the
  WebDAV page's shape. Endpoint, bucket, key ID, secret; prefix, region and
  addressing under "More options", which opens by itself for a saved bucket
  that uses one of them and on a `WRONG_REGION` answer. Failures land on
  their field (endpoint: not an address / unreachable / not S3; bucket:
  missing / full; secret: refused keys; key ID: no rights; region: wrong).
  A plain-http endpoint gets its OWN warning ("the secret key is never
  sent, but others on the network can see the requests" — the WebDAV line
  says the password travels in the open, which is false for SigV4) and
  "Connect anyway". The secret's helper says to use a key limited to this
  bucket. **The Backup screen** follows the kind: the folder row reads
  "<bucket> · S3 · <provider>" (`s3_folder_row`, untranslatable; the
  provider is a known name, else the host), the hero's lost state "Can't
  reach your bucket" with a body about keys and permissions
  (`CloudStatusPreference.setPlace` / `lostTitle`, shared with the Backups
  header), and "Reconnect" opens the prefilled S3 page.
- **What a hostile or broken server can no longer do (the 2026-10 review;
  each pinned by `scripts/backup-fault-harness` or
  `scripts/backup-sim-harness`, see Verifying).** (1) **Throw past the
  client**: OkHttp's own retry step parses a 503's `Retry-After` with
  `Integer.valueOf`, so `Retry-After: 4294967296` threw
  NumberFormatException out of `Call.execute()` — a terminal backup
  failure, and on the folder pool or WebDAV's upload thread a process
  crash. `S3Client` / `WebDavClient` wrap a RuntimeException from OkHttp as
  an IOException (then classified as usual), the streamed PUT's thread
  catches it, and the manager's two check lambdas answer UNREACHABLE on any
  RuntimeException. (2) **Drip bytes forever**: the read timeout is per
  READ; `StallGuard` (installed on every response by
  `ServerTrust.newBuilder`) fails a body that delivers under 16 KB per two
  minutes of time spent WAITING inside reads (a reader that pauses is not
  blamed), as a SocketTimeoutException, i.e. transient. (3) **Loop
  listings**: `listUploads` stops after two empty truncated pages and
  refuses past 100 pages; a truncated `ListObjectsV2` page with no
  continuation token is an error, never a silent end (a short list hides
  backups from dedup). (4) **Exhaust memory within the byte caps**: an S3
  answer is refused past 200 000 elements, a PROPFIND past 100 000
  resources. (5) **Fake a delete**: a 200 to `DeleteObjects` whose root is
  `<Error>` (or anything but `DeleteResult`) fails. (6) **Look like a wrong
  key on a wrong clock**: a HEAD answer has no body, so `RequestTimeTooSkewed`
  arrives as a bare 403; a bare 403 whose `Date` is more than 10 minutes off
  the signing time is treated as skew (learned once, re-signed). (7) **Turn
  a rate limit into "Storage full"**: the quota message match is skipped for
  429 and 5xx. Also from the review, the user's input: a prefix's `.` and
  `..` segments are dropped (OkHttp resolved them out of object URLs but
  not out of the listing's `prefix`, so backups were write-only); the
  endpoint's base path is re-encoded per segment with AWS's UriEncode (a
  `(`, `!` or `*` in it broke every signature); user info in the endpoint is
  dropped; an explicit VIRTUAL with an IP/local endpoint is `BAD_ADDRESS`
  and with a non-DNS bucket `NO_SUCH_BUCKET` (the URL could not even be
  built); `getRange` no longer sleeps after its last attempt; and
  `BackupStores.choose` writes the kind BEFORE clearing the other kinds, so
  a process death between the writes leaves the new place configured.
- **Residuals, deliberately not built.** (1) No resume of a multipart
  upload across process death: the FILE restarts from zero (the stale
  upload is swept a day later). (2) The stale-upload sweep cannot tell
  another phone's upload from a dead one: a second phone on the same
  account whose single upload has been running for over 24 hours would
  lose it (it then restarts that file). (3) Sharing one bucket between two
  phones works like a shared folder (the sidecar-per-object layout has no
  index to conflict over), but the recovery-code warning applies: a
  different code is a different account directory. (4) moto does not
  verify signatures, so its round trip proves the protocol, not the
  signing; MinIO and the FakeS3 do verify. (5) B2, Wasabi and AWS were not
  reachable from the machine that built this: their behaviour comes from
  the protocol and their published docs, not a live run — see
  `docs/S3-LIVE-TEST.md` for the device steps against a real B2 or R2.
  From the 2026-10 review, pinned by the simulation harness as current
  behaviour: (6) two phones backing up the same file at the same moment
  both pass dedup (two rows); (7) a delete-all landing between another
  phone's object and its sidecar leaves a lone sidecar until the next
  delete-all; (8) two phones backfilling one entry at once: the later write
  wins, and what was lost returns on the next backup of the file; (9) a
  dropped answer to CompleteMultipartUpload costs a full re-upload of that
  file (correct end state); (10) a HEAD on a deleted bucket reads as "not
  in the bucket", a GET says NO_SUCH_BUCKET; (11) WebDAV ages orphans by
  the phone's clock (S3 by the server's), and a phone a day ahead could
  sweep another phone's sidecar-less object, a window of the upload's
  length; (12) `listAll` holds up to 1000 pages of keys in memory before
  refusing; (13) any 404 on an object GET reads as missing, a proxy's HTML
  404 included.

### `VaultEngine` + the workers — same pipeline, a folder underneath

- **The chunk format is the hosted one, byte for byte** — `CHUNK_SIZE` 8 MiB,
  `CHUNK_OVERHEAD` 34, FDVC2's position-binding AAD (`objectId` hex-decoded ||
  `chunkIndex` uint32-BE), `VaultCryptoTest` pinning the exact blob — so a
  folder written by this build is readable by any later one, and
  `VaultObjectReader` maps a plaintext offset to a byte range of the object
  file with no index (chunk `i` starts at `i × (CHUNK_SIZE + CHUNK_OVERHEAD)`),
  which is what the stream player relies on. **Never change the two
  constants** — every existing backup folder is addressed by them.
- **Dedup by name+size, and a repair is not a new backup.** `backupStream`
  first looks for an entry with the same name and size; if found, no object
  is written — a missing preview or origin is BACKFILLED into the existing
  sidecar (cheap, no re-encrypt) carrying the ORIGINAL `backedUpAt`, so a
  re-backup of an old file does not jump to the top of the list. The
  `enqueueUniqueWork(KEEP)` key is name+size too (`VaultBackupWorker.enqueue`,
  the ONE place the request shape lives — the options sheet, multi-select
  and the notification receiver all call it), so two taps on the same
  content collapse to one worker.
- **A failed backup cleans its own object** (`deleteObject` on the way out
  of `backupStream`), so a retry writes a fresh id and the orphan sweep is
  only the backstop for a process death mid-write.
- **`cancel()` is cooperative** — WorkManager does not interrupt a Worker's
  thread, so without the per-chunk `checkCancelled` a cancelled backup kept
  writing a multi-GB object to the end.
- **`VaultBackupWorker`** runs foreground (`dataSync`, the app's declared
  type) with **no network constraint for a folder**: the folder is written
  through its provider (a local copy for Drive/Dropbox/Nextcloud, which
  upload on their own; an SD card needs nothing), so a backup works offline.
  A WebDAV server or an S3 bucket is the exception
  (`BackupStores.transferConstraints` adds CONNECTED, restores too): there
  every byte is a request. It loads the
  shared identity, drives the engine, publishes per-chunk progress
  (`KEY_PROGRESS_DONE`/`_TOTAL`, throttled to whole percents, skipped when
  `isStopped()`, and the last `setProgressAsync` future DRAINED before every
  `Result` — WorkManager throws "must complete before a ListenableWorker
  signals completion" otherwise), and names EVERY terminal cause with a
  slug on its output data: `SLUG_FOLDER` (above), `SLUG_SERVER` (the same
  for a WebDAV server or an S3 bucket, with "Reconnect" wording),
  `SLUG_FULL` (the place answered out of space: S3 quota/cap, WebDAV 507;
  "Storage full", terminal), `SLUG_NOT_SET_UP` (a job
  queued before the folder was forgotten), `SLUG_FILE` (the file could not
  be read), `SLUG_WRITE` (a write on a reachable folder failed past the
  retry ceiling) and `SLUG_ERROR` (anything else). **The slug → words
  mapping lives ONCE, in the worker** (`failureSummaryRes` the sentence,
  `failureLabelRes` the row's short label, `failureFixedInBackupScreen`
  whether the snackbar carries a door to the Backup screen); the Backups
  list's row + snackbar, the Downloads snackbar and the notification all
  read it, and a null/unknown slug gets the GENERIC sentence, never
  silence. **Every terminal failure is also a NOTIFICATION**: the folder's
  is the folder-lost one (tap → the Backup screen), every other is
  "Couldn't back up <name>" with the slug's sentence (tap → the Backups
  list, where the failed row sits with its ✕; fixed id, a failing batch
  replaces rather than stacks). It exists because both in-app surfaces are
  conditional — the Downloads snackbar needs that fragment alive, the
  Backups row needs the list opened — so a backup started from the
  finished-download notification and left alone used to fail in silence. A
  STOPPED worker announces nothing (the stop was the user's). The request carries `TAG_NAME`/`TAG_MIME`/`TAG_SIZE`
  so the Backups list can render a transfer row for an ENQUEUED worker
  (WorkInfo exposes tags, not input data, and progress is empty until the
  worker starts — without the tags the list looked EMPTY right after "Back
  up", at the exact moment the snackbar's View lands the user there).
  `MAX_RUN_ATTEMPTS` still bounds a backup that keeps failing for other
  reasons so it fails cleanly instead of re-encrypting forever.
- **`VaultRestoreWorker`** restores into `Download/Firedown` (unique
  destination), inserts a FINISHED `DownloadEntity` through the same
  repository every download uses, and **skips a file already in Downloads**
  (`findByNameSize`, honoured only when the local file still exists →
  `KEY_ALREADY_PRESENT`, "Already in your downloads"). A restored row whose
  sidecar carries no origin (backed up before it was stored) gets
  `backup://folder` as its origin, the P2P `p2p://<device>` convention —
  name the transport, never leave the domain blank. Tagged
  `TAG_OBJECT + objectId` so the list can tell WHICH entry a running restore
  belongs to. **Its IOException branch must keep its `isStopped()` guard**: a
  cancelled worker never runs its retry, so `Result.retry()` there would
  strand the Downloads row at PROGRESS forever — it resolves to ERROR.

### `CloudBackupManager` — the facade; status is two LOCAL facts

- **There is no server, so there is no status to fetch.** `Status` is
  `(setUp, reachable, fileCount, totalBytes, providerLabel)`: `loadStatus`
  reads the folder off the manager's own BOUNDED 3-thread pool
  (`folderExecutor`, idle threads time out — NOT the DiskIO/HeavyIO serial
  lanes: a provider can block on its own network, a list load must not queue
  behind a delete, and a big multi-select must not spawn a thread per
  delete), caches the last SUCCESSFUL answer in memory (`lastStatus()`, bound
  cached-first by every screen so nothing flashes empty on entry) and mirrors
  the totals to prefs (`CLOUD_LAST_TOTAL_BYTES` / `_FILE_COUNT`) so the
  folder-LOST state can say what was there ("Last seen …"). A reachable
  folder whose listing nevertheless failed (a provider hiccup) reports
  unreachable-with-last-known rather than invent a number.
- **`cachedBackedUpKeys()` / `loadBackedUpKeys`** are the content-key set
  (name+size, `contentKey`) behind the Downloads cloud mark, the review
  list's mark and the Storage screen's offload card — cached-first from the
  last successful listing, kept in step with the manager's own deletes,
  answering a FAILED listing with the EMPTY set (never "backed up" on no
  evidence), never persisted to disk (another phone on the same folder can
  change it; stale marks across process death would have no read to correct
  them until the folder answers).
- **`deleteAllData` = "delete the files", not "leave"**: erases this
  account's sub-directory (objects, sidecars, the directory) off the folder
  pool and KEEPS the folder grant and the recovery code — the next backup
  writes into the same folder again. Every in-flight/queued transfer is
  cancelled FIRST (`cancelAllWorkByTag(WORK_TAG)` — backups AND restores
  carry it), or a queued backup would re-create the directory seconds after
  the user erased it; the cached status is dropped and the totals stored as
  KNOWN-empty (0), not unknown.
- **`cancelLegacyWatch()` runs once at boot** (`App.onCreate`): the hosted
  service's periodic account watch (`CloudWatchWorker`, unique work
  `cloud-watch`) is a periodic WorkManager request that survives an update
  until something cancels it, and the class it names no longer exists.
- `isAlreadyDownloaded` / `resolveCloudOnly` (the name is historical —
  "folder-only": an entry whose file is NOT on this phone, probed with
  `RestoredFileAccess.openableUri`, never `File.exists()`, which is false for
  a readable foreign-owned restored file; a lookup that THROWS leaves the id
  OUT of the set, so an unknown degrades to the plain wording, never to a
  false "only copy" claim), `resolveLocalThumbs` (ONE batch per load) and
  `recoveryCodeForDisplay` are unchanged in contract.

### Thumbnails — the stored preview, the local file, and Glide

- **The sidecar carries a tiny base64 JPEG `thumb`** (`VaultThumbnail`,
  `MAX_DIM` 512 px longest side, JPEG q85, the MMR frame decoded at 2x then
  bilinear-downscaled) so the Backups list shows a preview **offline and
  after the local copy is deleted** — the whole point of backing up. The
  size is a budget even without the server's 16 MiB manifest cap: every
  sidecar is one file READ per listing (a cold Drive listing fetches each),
  so a bigger preview is paid on every load of the list. History, each step
  calibrated against the DISPLAY: 160/q60 read "very poor", 256/q80 still
  upscaled ~2x on the grid tile, 384/q80 still showed q80's blocking in the
  dark gradients video frames are made of — the fix attacked the ENCODE
  (512 = the grid tile's own pixels, q85, the 2:1 decode headroom), not the
  dimension alone. `DISPLAY_DIM` (512) is the SEPARATE constant for a
  display-only bitmap decoded from the LOCAL file; `maxDim` is threaded
  into EVERY decoder (`decodeImage`/`decodeVideoFrame`/
  `decodeVideoFrameNative`/`sampleSize`) because each caps the decode
  independently. **Existing sidecars are not re-encoded** — a "still looks
  bad" report must first check the entry is NEW.
- **`VaultThumbnail` MUST keep its native-FFmpeg fallback.** MMR alone
  silently stored NO preview for a codec the device lacks (AV1): the
  backing-up phone never noticed (its row falls back to the local file),
  and a SECOND phone on the same folder showed the mime glyph. That is the
  diagnostic signature: **a thumbnail that appears on the phone that backed
  up and not on another phone sharing the folder means the STORED thumb is
  null.** `streamPos = frameUs > 0 ? frameUs : -1` (-1 = no mandate; 0 would
  pin the black head frame).
- **The stored preview is the Downloads list's EXACT frame**
  (`GlideHelper.thumbnailFrameUs(entity)` → `KEY_FRAME_US`,
  `OPTION_NEXT_SYNC`), never a guessed fixed offset.
- **Display prefers the LOCAL file; the stored preview is the fallback**
  (`backfillThumbnails` → `resolveLocalThumb` → `thumbModelFor`): a present
  local copy renders through the SAME `GlideHelper.load(DownloadEntity)` path
  as the Downloads list, so both lists show the identical frame. Display-
  only — the sidecar is not rewritten.
- **Everything goes through GLIDE; there are no hand-rolled bitmap caches
  on this screen.** `glide/VaultThumbModel` + `VaultThumbModelLoader` load
  the stored preview keyed `ObjectKey(objectId)` (random, immutable);
  **always `DiskCacheStrategy.NONE`** — a folder-only entry's preview has no
  plaintext counterpart on the device and writing one would create it.
  `resolveLocalThumb` returns a MODEL (a `DownloadEntity`, or a
  `VaultObjectModel` for a folder-only IMAGE, decrypted on read through
  `VaultObjectModelLoader`), never a Bitmap. The mime glyph is `placeholder`
  AND `error` with `dontAnimate()` (no flicker, no previous row's image); a
  local-file load uses the STORED preview as its `thumbnail()` request so
  the grid never drops to the glyph while the MMR decode runs. **Never hand
  Glide one of its own drawables back as a placeholder** (`thumb.
  getDrawable()` is pool-managed and recycles under the user); hand it a
  model. The backfill is ONE batch (`resolveLocalThumbs` → `setThumbModels`,
  one `notifyItemRangeChanged`).

### The Backups list (`CloudBackupListFragment` + `CloudBackupListViewModel`)

- **State lives in the ViewModel** — entries, load/error flags, the
  in-flight-delete guard and the selection, published as ONE `State`
  snapshot; `onViewCreated` loads only on a fresh VM (a re-list on rotation
  would put a Drive round trip back in the rotation path). The adapter is a
  pure renderer (`submit`/`setSelection`/`setActionMode`/`setCloudOnly`).
  **The generation guard stayed** (`mLoadGen`): the manager is CALLBACK-
  based, two concurrent listings complete in provider order and a stale one
  would overwrite a newer list. Search and grid stay on the fragment.
- **Optimistic remove + `PendingRemovals`** (`sync/PendingRemovals`, pinned
  by `PendingRemovalsTest`): the row leaves at once, the folder delete runs
  on the pool, only FAILURE re-inserts the row and clears its guard; a
  load() that lands before the sidecar is gone would otherwise RESURRECT
  the row, so `load()` filters the guarded ids and reconciles with
  `retainAll(listedIds)` — an id the fresh listing no longer holds is
  confirmed gone. Never clear the guard eagerly on SUCCESS (the reverse
  ordering reopens the ghost). Batch delete is ONE `deleteEntries` call on
  the pool, restores rows ADDITIVELY on failure, never via a `load()` (which
  fails too when the folder is unreachable) and never by snapshot-clobber.
- **Sorted NEWEST BACKUP FIRST on `backedUpAt`** (`sortNewestFirst` at the
  one `loadEntries` choke point), reverse-then-stable so legacy entries
  carrying 0 keep their order; `backedUpAt` is NOT `downloadedAt` (a restore
  must land in the date section the original sat in). The field is written
  only when non-zero.
- **Per-item transfer rows** (`TYPE_TRANSFER`, always determinate with the
  percent shown — hiding the percent at 0 shifted the bar's margin frame to
  frame): built from `WorkInfo.getProgress()` + the `bname:` tags, ONLY for
  uploads of NEW files (a re-backup keeps its committed row), collapsed by
  name, cancel = `cancelWorkById`. `setTransfers` rebinds only the transfer
  rows on a tick. On SUCCEEDED the list reloads ONCE per finished worker
  (`mReloadedTransferIds`) so a finished upload's committed row appears at
  once rather than when the whole batch goes idle. **ONE `TransferVH` serves
  both layouts on DIFFERENT GROUNDS** — the GRID tile takes `FallbackInks`
  for every caption line (it sits on the placeholder ground in every state),
  the LIST keeps its layout inks; a `?attr` ink resolved at bind time
  measured 1.47:1 in light theme and 8.06:1 in dark (the flips-with-theme
  defect class). The `grid` flag is load-bearing.
- **`LCEERecyclerView`**: `showLoading()` on the initial fetch only (the
  first fetch holds the spinner until the listing lands, so the transfer row
  and the files appear TOGETHER — a transfer row alone for a beat was the
  reported two-transitions-where-one-belongs), `hideAll()` with rows or an
  active transfer, `showEmpty()` otherwise; the error empty-state when the
  last listing FAILED (`mLoadFailed`), never "no backups yet" for an
  unreachable folder. Change animations are off in its ctor, so progress
  ticks don't blink.
- **Pull-to-refresh** (`cb_swipe`, with `setOnChildScrollUpCallback` pointed
  at the inner RecyclerView — the direct child is the LCEE container;
  `stopRefreshing()` on BOTH outcomes): another phone on the same folder
  adds entries this phone only sees on a re-list.
- **Batch restore + select-all** (`menu_cloud_backup_action.xml`): restore is
  the point of a backup and used to be one-file-at-a-time; NOT dialog-
  confirmed (constructive, reversible, count + size in the toolbar);
  `enqueueRestore` split from `startRestore` so N restores don't fire N
  snackbars. **"Restore all to this phone"** (`action_restore_all`, visible
  only when something is folder-only and not already restoring) IS
  confirmed — gigabytes the user did not pick — and states count, total and
  free space (`StatFs` on the download path), DISABLING the positive button
  with the shortfall when it does not fit (N restores dying on a full disk
  would be N unexplained ERROR rows). It is the way back before changing
  or giving up a folder; the hosted service's grace-card deep link into it
  went with that service.
- **The folder-only fact lives at the REMOVE decision, NOT on the row**: the
  item sheet's `cb_sheet_only_copy` line (`ARG_CLOUD_ONLY`) and the batch
  confirm's count of last copies (`cloud_backup_delete_last_copies`, only
  when the selection holds one). A per-row marker shipped and died: on an
  established install nearly every backed-up file has since left Downloads,
  so it rendered on ~90% of rows and carried no information — and the
  opposite marker is wrong by the same argument. **A state worth warning
  about is not automatically worth labelling everywhere**; put it where it
  changes a decision, once.
- **Removing an entry whose restore is IN FLIGHT warns first, then
  `cancelRestores` BEFORE the sidecar + object delete** — a restore left
  running would read an object about to disappear and fail mid-file,
  surfacing as a bare ERROR row on a different screen. The WorkInfo
  observer rebuilds `mRestoringObjectIds` from the `robj:` tags on every
  emission. Single remove, otherwise unconfirmed and optimistic, confirms
  for this one case.
- **Selected size in the toolbar** ("N selected · 1.2 GB"), composed from the
  existing string. Multi-select is the Downloads strategy — the screen's own
  toolbar + a `MenuProvider`, never `startSupportActionMode`; the tick in the
  ⋮ slot, the `SelectionStyling` wash.
- **The header is TEXT ONLY, two lines**: the inventory ("14 files · 2.1 GB",
  prefixed "Backing up… · " during a transfer) and STATUS · trust —
  "Firedown Backup on Google Drive · encrypted end-to-end", or "Can't reach
  your backup folder · …", status first so a wrap moves the boilerplate
  tail (`maxLines=2`, degrades by growing). No control in the header
  (`fragment_cloud_backup_files.xml` carries the width-fight history), no
  colour on the status text (`backup_warning` is 2.09:1 on the light
  surface — the words carry the state). The toolbar OVERFLOW holds "Restore
  all" and **"Backup"** (→ the Backup screen) — the ONLY route from this
  list to the folder, the code and the erasure door: for a set-up folder
  the Downloads overflow lands here, not on the Backup screen.
- **UI parity with Downloads**: the row mirrors `fragment_download_item.xml`
  (card root, 8dp inset, `list_download_image`, three lines — name /
  `MIME · size` / relative date), the empty state mirrors
  `recycler_empty_layout` with `ill_cloud` (the folder's provider IS a
  cloud for most users; the art stayed), `EqualSpacingItemDecoration`
  (list_spacing), grid caption type on the M3 scale.
- **The per-item sheet** (`CloudBackupItemSheetDialogFragment`): rich
  header (preview + name + `MIME · size · date`, `ARG_LOCAL_PATH` for an
  entry with no stored preview), **Open** for a folder-only entry →
  `CloudBackupStreamActivity` (plays/shows WITHOUT restoring: `VaultDataSource`
  into ExoPlayer, each 8 MiB chunk read from the object file and decrypted
  on demand, a seek costing one chunk; the recovery code loaded and the DEK
  unwrapped off-main; `EXTRA_HIDE_SHARE` on the player — nothing on disk to
  share), Restore, and Remove styled `Firedown.Widget.SheetRowText.Final`
  (colorPrimary, never colorError). **Consume its result with
  `getSavedStateHandle().set(RESULT, null)`, never `remove(RESULT)`** —
  remove() detaches the cached LiveData and the SECOND remove/restore of a
  session silently never arrives.

### The Backup screen (`SyncSettingsFragment` / `settings_sync.xml`, title "Backup")

ONE screen hosts the downloads backup AND the bookmarks switch (the
two-level IA and the focused bookmarks screen were retired as tap tax).
Order = priority: the **status hero** (`CloudStatusPreference`, flat), the
**morphing CTA** (`CloudBuyButtonPreference` — historical name, nothing is
bought; the MaterialButton carries `@android:id/title` so the Preference
TITLE is its label), then three UNLABELLED card groups: KEY (Recovery code
reveal + "I have a recovery code"), USE (Backups + the Bookmarks switch),
last (the "How sync & encryption work" link → `firedown.app/security` + the
"Delete backup data" door). The KEY and USE groups SWAP by life stage
(`SETTINGS_CLOUD_GROUP_KEY`/`_USE`): KEY above USE until set up (the adopt
door is the second way in, right under the create-CTA), USE above KEY once
set up — groups, not rows, so each card stays whole. NO category labels
(the last header umbrella'd the code/help/delete rows as "Bookmarks"
children — the card edges do the grouping).

- **The hero has THREE states** (`CloudStatusPreference`): set up + folder
  reachable (the cloud-upload glyph, `cloud_upload_24` — THE backup glyph
  on every door and notification, the maintainer's pick over the earlier
  folder — "Firedown Backup" over "On <provider › folder>" (the location
  label, below), the backed-up size headline, a file-count caption; the
  ⋮-menu hint while
  nothing is backed up yet; "Transfer in progress…" while a worker runs);
  set up + folder LOST (the cloud-off glyph, `cloud_off_24` — the healthy
  glyph's struck twin, so the pair stays one family; the same glyph is the
  folder-lost notification's small icon — "Can't reach your backup
  folder", the LAST SEEN totals, the body line saying what to do — the card
  states the fact, the CTA acts); not set up → the dashed ROADMAP ① Create
  recovery code → ② Choose backup folder → ③ Back up a finished download
  from its ⋮ (done = "✓" + muted ink, the current step bold). Each step is
  a HANGING row (`cb_step_*_mark` + `cb_step_*`): the marker in a column
  under the header's 28dp glyph (a MINIMUM width, so a large font scale
  widens it rather than clipping), the text in a column under the title,
  so a wrapped line continues under its text. It was one TextView per
  step with "2 ·  " typed in front, and step ②'s second line sat under
  the number (reported on-device, 2026-10-13); the row carries the spoken
  line, the two columns are hidden from TalkBack. There is no
  quota, meter or chip: the folder is the user's own storage, so the only
  facts the card can state are what is in it and whether it answers.
  `mReachable` defaults TRUE so a cold bind never flashes the lost state;
  the hero binds `lastStatus()` first, then the fresh read.
- **The CTA shows ONLY while a step is owed, and the FOLDER is a ROW**
  (`applyCta` / `applyFolderRow`, 2026-10-13, the maintainer's "open folder
  or change folder is still confusing for cloud"): no key → "Create
  recovery code" (FILLED; with the mandatory "I've saved it" dialog), key
  but no folder → "Choose backup folder" (FILLED), folder lost → "Choose
  the folder again" (FILLED), healthy → the button is HIDDEN. It stayed as
  an outlined "Change folder" for one release and that was the screen's
  one visible action on a healthy backup: a verb with no object, which
  asked "change it to what, from what?". The answer is the **"Backup
  folder" row** at the top of the USE group (`folder_24`, key
  `cloud.backup.folder`), whose summary STATES the folder —
  `FolderStore.locationLabel()`, "Google Drive › Backups": the provider,
  then the picked folder's own display name, remembered from the same
  root-document query `isReachable()` already makes (pref
  `FOLDER_BACKUP_TREE_NAME`, cleared with the tree; `Status.location`
  carries it so the hero and the row repaint from the read that named it).
  That is what Signal, Aegis and Syncthing do with a SAF folder: a row
  with the path, tap to change — none has a standalone change button, and
  none offers "open folder" (opening the tree shows `objects/` full of
  random-named `.fdvc` files; the Backups list IS the folder's readable
  view, deliberately the only one). Tapping the row runs `changeFolder`:
  while the folder HOLDS files it confirms first (`folder_backup_change_*`:
  new backups go to the next folder, what is already in <location> stays
  there and leaves the Backups list until that folder is chosen again,
  restore first to bring it along — the consequence that used to live only
  in this file), else straight to the chooser. **ONE `OpenDocumentTree`
  launcher serves first choice, change and re-choose** (plus
  `mVolumePicker`, a `StartActivityForResult` for a removable volume's own
  picker intent — same ACTION_OPEN_DOCUMENT_TREE, same tree grant, same
  `onFolderPicked`): `onFolderPicked` → the write check (below) →
  `adoptFolder` (takes the persistable grant; a previous folder's grant is released, its files
  untouched), drops the screen's own last answer, re-reads — a folder that
  already holds this account's backups (a reinstall pointed back at its
  Drive folder) lists them straight away — and snackbars "Backup folder:
  <provider>". **Changing the folder moves nothing**: the old folder keeps
  its files under the same code; "Restore all" first is the way to bring
  them along.
- **The picker is INSTRUCTED before and GUARDED after — the phone already
  has a folder named `backup`.** `Download/Firedown/backup/` is the
  download-LIST mirror (`DownloadBackupMirror`'s `.fdbk` + the bookmark
  export): automatic, device-keyed, the same-phone reinstall recovery, and
  it cannot move (every old install's `.fdbk` is there and the restore scan
  is keyed to the name). So a user browsing the phone in the system picker
  sees an app-made folder literally called backup and picks it, and the
  "backup" lands beside the originals on the same storage. Two answers, in
  `SyncSettingsFragment`: (1) `showPlaceChooser` — **"Where to back up"
  is a BOTTOM SHEET with one card per KIND of place, always shown, and a
  second page for the cloud step** (`BackupPlacesSheetDialogFragment`,
  `fragment_dialog_backup_places`, a `<dialog>` destination of
  `nav_graph_settings`; the card row is `Firedown.Widget.SheetPlaceRow`/
  `Icon`/`Text`/`Title`/`Sub`/`Chevron`: a title, ONE short line, a
  chevron). Page 1, the places: "Cloud storage" (`cloud_outline_24` in
  `brandInk`, "Google Drive, Nextcloud and more"), "WebDAV server"
  ("Nextcloud, ownCloud or a NAS", → the sign-in page), the SD card / USB
  drive only while one is mounted (`sync/BackupPlaces.removable`:
  `StorageVolume.createOpenDocumentTreeIntent()` on API 29+ opens the
  picker right on it), "Somewhere else" ("Any other folder"). Page 2,
  opened by the cloud card, "Find your cloud app": a back arrow, a
  PICTURE of the system picker (`layout_backup_picker_guide`: the ☰ ringed
  in brandInk inside a brand-wash halo, the drawer open on Google Drive
  above Nextcloud, a skeleton title and third entry so it promises no
  folder and no other app; decorative, the line under it carries it for
  TalkBack), "Tap ☰, pick your app, then a folder.", "Not there? Sign in to
  the app first." (Drive with no account lists nothing under ☰, which was
  the maintainer's own "cloud is not showing"), and "Open the picker",
  which opens the plain picker with NO toast (the page said it a moment
  ago). Back on page 2 returns to page 1 (an `OnBackPressedCallback` on
  the dialog, enabled only there); the page survives a rotation. The
  sheet only CHOOSES: the pickers are registered on
  `SyncSettingsFragment`, so the pick (`RESULT`, a `PLACE_*` int) goes
  back on settings_sync's own back-stack entry, set AFTER the sheet pops
  (the WebDAV branch navigates with an action of settings_sync, which
  only resolves once it is current again), consumed with `set(null)` and
  handled on the next frame (`observePlaceResult` → `openPlace`, which
  asks `BackupPlaces.removable` again, so a card pulled meanwhile falls
  back to the plain picker). **The copy is a name or an instruction,
  nothing else** (maintainer, on "You sign in here; no app needed":
  "empty theater, worthless text, keep the copy simple and direct"): no
  reassurance, no explaining how the app works, no consequence a later
  screen already states. History, all on 2026-10-13: a two-paragraph
  dialog that said all this in words; then per-app rows (see below); then
  NO dialog at all without a card (the picker straight away, a toast the
  only instruction), rejected as too little for someone who has never
  opened that drawer; then a centred `MaterialAlertDialog` of three-line
  rows with a miniature of the picker's top bar under the cloud row and a
  footnote; then that dialog as a one-page sheet, reported "looks
  horrible": rows that were paragraphs, the ☰ instruction said three times
  (row text, miniature, footnote), and a miniature that read as a search
  field inside a button. Canvas option C (two pages) was chosen over a
  compact one-page list (A) and a cloud-first hero card (B): the ☰ step is
  where people get stuck, so it gets a page right before the picker opens,
  at the cost of one tap on the most common path. **A KIND row is
  not an app row**: it needs no detection and no list, names as examples
  only apps verified to take a folder pick (Drive, Nextcloud; never
  Dropbox or OneDrive, which are unverified), and promises nothing about
  what the picker will list. **There are NO per-app cloud rows, and there must not be
  a list of cloud apps — both were built and removed on 2026-10-13.** First
  detection (`queryIntentContentProviders` on `DOCUMENTS_PROVIDER`): it
  listed Files, Proton Drive, System Tracing and Termux as places to back
  up, because a provider of LOCAL content answers the same intent under the
  same `MANAGE_DOCUMENTS` permission as a cloud, and the two facts that
  differ — a root's `FLAG_LOCAL_ONLY` and `FLAG_SUPPORTS_IS_CHILD` — live in
  the roots, which only the system may query (no manifest fact, app
  category, permission or account type separates them, and hiding system
  apps hides Drive, which ships preinstalled). Then a verified-package
  allowlist ANDed with detection: correct, but a list to keep, and it
  bought nothing — there is no documented way to open the picker INSIDE a
  given app (no root id for `EXTRA_INITIAL_URI`), so a "Google Drive" row
  launched the same picker as "Somewhere else" plus a toast naming the app.
  An icon and a name were not worth a hand-kept list; the manifest's
  `<queries>` entry went with it. For support questions, the evidence
  gathered then: Nextcloud, Seafile and Infomaniak kDrive declare
  `FLAG_SUPPORTS_IS_CHILD` in their open-source providers; MEGA (root flags
  0), ownCloud and Proton do not; Cryptomator and Filen ship no provider;
  Drive was seen working on-device once signed in; Dropbox, OneDrive and
  the other closed apps are unverified. **What protects a pick instead is
  the WRITE CHECK, which tests the folder, not the app** (below).
  `launchPicker` carries the `ActivityNotFoundException` catch the restore
  picker has. (2) `onFolderPicked` runs `FolderStore.treeIsWithin(tree,
  StoragePaths.getDownloadPath())` — the external-storage provider's tree
  id mapped to a path (`primary` → the shared root, any other volume →
  `/storage/<id>`; a cloud provider's tree has no path and is never
  questioned) — and a tree that is the app's download directory or under
  it gets the `folder_backup_guard_*` dialog: "Choose another" relaunches
  the chooser, "Use it anyway" adopts like any other pick. Nothing is
  refused: a backup on the phone is a legitimate (weak) choice, the point
  is that it is not picked by accident. (3) **Every pick passes the WRITE
  CHECK before it is adopted** (`FolderStore.checkWritable` via
  `CloudBackupManager.checkAndAdoptFolder`, on the folder pool, "Checking
  the folder…" meanwhile): in the tree's `Firedown Backup` directory
  (created when missing) it creates a small file, writes it through the
  SAME `openWrite` a backup uses and deletes it. It runs on the grant the
  picker's result carries, BEFORE `takePersistableUriPermission`, so a
  refused pick leaves the current folder, its grant and its cached status
  untouched; the `folder_backup_unwritable_*` dialog names where
  (`providerLabelOf`, the picked tree's provider) and offers the chooser
  again. A provider that lists folders but refuses to create, write or
  delete used to surface as the FIRST backup failing, long after the
  choice. Delete is part of the check on purpose (a failed backup cleans up
  after itself, and Remove deletes); a READ-BACK is deliberately not — a
  provider that uploads on close can serve stale bytes for a moment, and a
  check that fails a working folder is worse than none. The adopt runs in
  the manager, so a pick lands even if the user left the screen mid-check. The setup copy
  (`cloud_backup_setup_message`) names the kinds of places too, so the
  first pick is steered before the chooser opens; roadmap step ②
  (`folder_backup_step_folder`) is "Choose where to back up", the sheet's
  own title, with no examples (it named Dropbox, which the kind-row rule
  above excludes, and wrapped on a phone). The two folders stay as they are: list recovery for a same-phone
  reinstall vs file copies elsewhere are different jobs, and renaming the
  mirror's folder buys nothing the guard doesn't.
- **The folder picker lists ONLY providers that declare
  `Root.FLAG_SUPPORTS_IS_CHILD` — a cloud app that shows in the Files app
  and in a file picker can still be absent here, and nothing in our intent
  can change that.** The picker is the stock `OpenDocumentTree` contract
  (`ACTION_OPEN_DOCUMENT_TREE`, no `EXTRA_LOCAL_ONLY`, no MIME filter), and
  DocumentsUI's `ProvidersAccess.getMatchingRoots` skips, for a tree pick
  only, every root without `supportsChildren()` — that flag, meaning the
  provider implements `isChildDocument`, which a subtree grant needs —
  while browse mode and `ACTION_OPEN_DOCUMENT` list the same root. Proton
  Drive is the shipped case (2026-10-13, the maintainer's phone: Proton in
  Files and in Bookmarks → Import's single-file picker, absent from the
  backup picker): its open-source provider (`ProtonDriveApps/android-drive`
  3.1.0, `drive/documentsprovider/data/…/extension/Account.kt`) sets the
  root flags to `FLAG_SUPPORTS_CREATE` alone and `DriveDocumentsProvider`
  overrides no `isChildDocument`, so Android hides it from EVERY app's
  folder picker (Joplin's sync-folder picker has the same report). The
  source carries no F-Droid flavor (dev/alpha/beta/prod), so a build from
  it behaves the same wherever it was installed from, and the repo has
  Issues disabled ("No contributions are accepted for now") — the ask
  (declare the flag and implement `isChildDocument`; their documents
  already carry `FLAG_DIR_SUPPORTS_CREATE`/`SUPPORTS_WRITE`/`DELETE`, so a
  tree would work) goes through Proton's feedback forum or support, not
  GitHub. The flag lives in the roots, which an app cannot read, so nothing
  in the app can see it either (the chooser history above): a user who
  taps "Cloud storage" simply does not find Proton under ☰, and the write
  check never runs because there was nothing to pick. The diagnostic for the next such report is
  the Import-picker test above.
- **The KEY-FIRST GATE**: until a recovery code exists the CTA reads "Create
  recovery code" (not choose-a-folder), the Backups row is hidden and the
  Bookmarks switch is disabled — a folder can only be chosen once there is
  a key to encrypt into it with. A code, once created, forces the "I've
  saved it" checkbox before Done (non-cancelable in create mode).
- **Adopting a code works in BOTH key states** — post-key the row's summary
  flips to the replace wording and `showLinkDialog` CONFIRMS first
  (`settings_sync_link_replace_*`): the current code is the ONLY key to
  whatever sits under it in the folder. Re-gating it on `!hasKey` re-breaks
  the two-phones-two-codes case. **The code alone joins nothing, and every
  string on this door says so.** Under the hosted service one code WAS one
  backup; now the code is the key plus the NAME of the account directory
  (`Firedown Backup/<account>/`) inside whatever folder each device chose,
  so two devices share a backup only when they also use the same folder.
  The door's copy shipped for the whole 1.2.01 cycle as "Both devices will
  share one backup" / "scan this from your other device to share this
  backup" / "Code restored. Your backups can be read on this device" —
  the hosted model's truth, which sends a second phone to an empty Backups
  list expecting files that live in a folder it never picked. Now the row
  summaries, the input dialog, the replace confirm and the QR hint all end
  in "then choose the same backup folder", and the link snackbar splits on
  `hasFolder()` (`settings_sync_link_ok` / `_ok_choose_folder`) so the
  next step is named. Don't write "share a backup" about the code again;
  it is the folder that is shared. The 17 `settings_sync_*` strings of the
  retired focused-bookmarks screen (`_setup_*`, `_restore_*`, `_now_*`,
  `_sign_out_summary`, `_downloads_active`, …) went with this audit — dead
  resources whose text still described a server ("Der Server sieht sie
  nie") are exactly what a later wire-up would ship unread.
- **The code travels by QR as well as by typing, and the SCANNER IS THE P2P
  ONE** (`P2pScanFragment`, registered as a `<dialog>` in
  `nav_graph_settings` too; `ARG_PREFIX` empty, `ARG_TITLE_RES` added; the
  CALLER validates with `SyncManager.looksLikeRecoveryCode` — shape only).
  The reveal dialog SWAPS its code panel for the QR panel in the SAME dialog
  (adding it below clipped "Save to file" off the bottom; a second dialog
  read as a dialog on a dialog and would have dismissed the create-mode
  gate); a scan lands back on the INPUT dialog prefilled, never links
  straight through (a QR is a bearer secret pointed at a camera); a payload
  that fails the shape check is reported and DROPPED. The result is consumed
  with `set(key, null)`, registered in `onViewCreated`. The encoder is
  `utils/QrCodes`, the app's ONE encoder (P2P share's private copy was
  migrated into it; a third copy is the `compactDuration` drift mistake).
- **The reveal dialog carries Copy AND Save-to-file on NON-DISMISSING
  buttons** (export is something you do while LOOKING at the code), behind
  one device-auth prompt; the old separate export row doubled both.
- **"Delete backup data" is ONE door → a SUB-SCREEN with two SCOPED rows**
  (`CloudDeleteFragment`, `settings_cloud_delete.xml`): "Delete bookmarks
  from server" (while sync is on) and "Delete backed-up files" (while set
  up — erases this account's directory in the folder, keeps the code and the
  folder grant). Each row's SUMMARY states its consequence before the
  confirm (a chooser dialog shipped for one commit and could only name the
  two); a row hides when it does not apply, the door hides when neither
  does. Never one "delete everything" that couples free bookmarks to the
  backed-up files.
- Deep links: `EXTRA_OPEN_SYNC`, `EXTRA_OPEN_CLOUD_BACKUP` and
  `EXTRA_OPEN_BOOKMARKS_SYNC` all land here (three extras because callers
  express different intents) with `popUpTo settings inclusive`, so Back
  returns to the CALLER; `EXTRA_OPEN_CLOUD_BACKUP_FILES` (the Downloads
  overflow for a set-up folder) lands on the Backups list the same way. The
  Downloads overflow routes two-way (`isSetUp` → the list, else → this
  screen). The home carries NO backup state (see "The home carries NO
  cloud state" below).

### Where backing up is OFFERED, and what is excluded

- **The download sheet's "Back up to <provider>" row** (`cloud_backup_action_to`
  once a folder is chosen, the bare "Back up" before — `DownloadsOptionDialog
  Fragment`), the multi-select backup action (`BaseDownloadFragment`, whose
  snackbar names the Safe Folder exclusion when a selection reduced to
  nothing BECAUSE of safe entries — `cloud_backup_safe_excluded`), and the
  **download-finished notification's "Back up" action** (`RunnableManager`
  → `CloudBackupNotificationReceiver`, explicit intent, request code = the
  row id; carries only the ROW ID and RE-CHECKS every gate at tap time —
  FINISHED, not vault, still set up — because a notification outlives the
  state it was built against; feedback is the worker's own foreground
  notification replacing the finished one). The action is gated on
  `isSetUp()` — a notification cannot run the first-time setup. All three
  call `VaultBackupWorker.enqueue`. **"Backing up…" snackbar with a View
  action, NO success snackbar** — the list is the confirmation; only a
  FAILED transfer surfaces an error snackbar, naming the cause through the
  worker's one slug mapping (see `VaultBackupWorker` above).
- **A backup in flight shows ON ITS ROW, never as the Safe Folder's bottom
  bar.** That bar (`handleTaskStart`) dims and freezes the whole list with a
  Cancel until a LOCAL task ends — right for encrypt-into-vault, audio,
  GIF, compress, where the rows are changing under the user and it is
  seconds. A backup is a COPY that leaves the list untouched, runs in a
  foreground worker for seconds to minutes and survives leaving the
  screen; owning the screen for it is the P2P "sit on the share screen"
  mistake. Files by Google draws the same line (a blocking dialog for its
  Safe Folder move, per-item progress for Drive uploads). The gap it
  closes: after the "Backing up…" snackbar faded, Downloads said nothing
  until the solid mark landed on success, and the notification is the
  only other surface — muted on the maintainer's own phone. So the row's
  cloud mark has an IN-FLIGHT form: the same silhouette at
  `CloudMark.PENDING_ALPHA` (a ghost of the mark it becomes; a second
  glyph smudges at text size and the words carry the state) beside
  "Backing up… · 32%" (`cloud_backup_transfer_uploading` + the app's
  `%d%%`), IN PLACE of the facts for the duration — list: leading, grid:
  the meta row alone with the mime label hidden (the full row overran the
  tile in long locales and clipped the trailing mark). Plumbing:
  `BaseDownloadFragment.observeBackupCompletions` maps every WORK_TAG
  emission's unfinished backup work to `VaultBackupWorker.progressKey` →
  percent (-1 before the first byte) and hands it to
  `DownloadItemAdapter.setBackupProgress`, which rebinds ONLY the rows
  whose entry changed with `PAYLOAD_BACKUP` (a tick is one facts line; a
  row whose backup ENDED falls through to the full bind so every
  presentation rule re-applies from its one owner). **The key is the
  TRUNCATED-name content key on both sides**: a queued work's only
  identity is its `bname:` tag (capped at 120 chars), progress data
  carries the full name only once the worker runs, and the row has the
  full name — `progressKey` truncates all three the same way, or a long
  filename would silently never show progress. Two doors to cancel ride
  the same state: the sheet's backup row reads "Cancel backup"
  (`Keys.BACKUP_IN_FLIGHT`, handed over at open; the tap re-checks the
  live state), and the worker's foreground notification — DETERMINATE
  now, re-posted per whole percent through `publishProgress`'s throttle,
  with WorkManager's own cancel intent as its action — covers a user who
  left the app. The notification is not optional: a foreground service
  must post one, and this transfer is the kind the user walks away from.
- **The Downloads banner is ONE stage** (`SyncBannerAdapter`, parameterized
  by copy + glyph, never forked; prepended through the same `ConcatAdapter`
  as the Safe Folder row, below it): shown while NOT set up, the list has
  rows and `CLOUD_BACKUP_BANNER_DISMISSED` is unset; retired permanently by
  dismissal or by setup. The hosted service had a second "your free credit
  is waiting" activation stage keyed on a server balance; a folder has no
  balance and nothing to activate. Visibility is driven from the AGGREGATES
  LiveData, not the paging load-state listener (a `notifyItemInserted` on a
  sibling adapter during layout throws); `onResume` re-evaluates it.
- **The Safe Folder exclusion is a STATED promise.** Vault entries are
  excluded (the same "vault never leaves the device" contract as the mirror
  and P2P send) — deliberately NOT lifted: vault content is guarded by the
  device lock, the folder is keyed by the recovery code, so backing up a
  vault file would silently move it into a different trust domain. If
  per-file vault backup is ever built it needs device-auth gating on safe
  entries in the Backups list, restore back INTO the vault, and explicit
  consent copy — all three together.
- **The home carries NO cloud state** (maintainer, 2026-10-12): the transfer
  chip and the grace card that sat under the mark through home v5 are gone,
  and with the hosted service so are the states they showed. A transfer
  lives in the Backups list's row; a lost folder is told by the worker's
  one notification and the Backup screen's hero. Rules that survive:
  evidence-based states only, restores never count as transfers
  (`VaultBackupWorker.TAG_NAME` filters them), no semantic amber container
  on an informational state, no user-facing OFF switch — a state clears by
  having nothing to report. Don't bring any of it back to the home.
- **FGS type.** Both workers run as `dataSync` foreground workers; the
  manifest merges `foregroundServiceType="dataSync"` onto WorkManager's
  `SystemForegroundService` (without it `setForegroundAsync` crashes with
  "foregroundServiceType 0x… is not a subset of 0x0").

### Verifying a Backup change

- `./gradlew testDebugUnitTest`: `VaultCryptoTest` (the FDVC2 format pin —
  the exact blob sealed with node:crypto; every existing folder holds this
  layout), `CryptoTest` (the canonical/Crockford/HKDF vectors shared with
  `firedown-api/tests/api-vectors/`), `PendingRemovalsTest`.
- On-device, the whole loop, because no JVM test can drive a
  `DocumentsProvider`: pick a Drive folder → back up → kill the app →
  Backups list shows the entry with its preview → delete the local copy →
  Open streams it → Restore brings it back (and "Already in your downloads"
  on a second restore) → revoke the grant (sign out of Drive, or pull the
  SD card) → the hero shows the lost state, a backup attempt posts the
  folder-lost notification once → re-pick → everything lists again. Then
  the same folder from a second phone adopting the same code. The hosted
  service's `CloudBackupLiveTest` went with the server; a folder live test
  would need a provider on the test device and was not written.
- Any change to the sidecar's fields: `VaultEntry` JSON is read by every
  later build, so a new field is optional on read and written only when
  set (the `backedUpAt`/`origin` precedent) — never a bump that strands
  existing folders.
- **Any change to `BackupStore`, `BackupLayout`, the WebDAV classes or the
  engine: `sh scripts/webdav-harness/run.sh`.** It copies the REAL classes
  out of app/src, compiles them against the REAL OkHttp/Okio (fetched from
  Maven Central into a cache, sha1-checked) with Android stubs, and drives
  them against an in-JVM fake WebDAV server with a switch for every failure
  above (wrong password, read-only, full, a dropped or cancelled upload, a
  file that shrank, Range ignored, a wrong 206, a 500, the collection
  gone, a redirect, absolute hrefs, a site root that is not WebDAV, two
  self-made certificates for the trust path), then — when wsgidav is
  installed (`pip install wsgidav cheroot`, or `WSGIDAV=`) — the same
  backup/list/restore/stream/delete round trip against a REAL WebDAV
  server, checked on its disk. It also starts `DavServer.java` from source
  as a separate process (always: JDK only) and runs that round trip
  against it plain and over TLS, plus a 64 MB file, an empty file, a
  cancelled upload leaving no temporary file, allprop, the Nextcloud layout
  found from host:port alone, read-only, a quota at the check and
  mid-backup, and the pinned certificate surviving a restart; and, when
  litmus is installed (`apt install litmus`), the server's basic, copymove
  and http conformance suites; and the reconnect rules (`runReusing`: the
  saved password reused only for the same server and user, never sent
  elsewhere, a typed one winning, the trusted certificate kept). 137
  checks with wsgidav and litmus, 121 without, ~2 min.
  Teeth proven by mutation: each of nine planted bugs fails it (a
  `sameServer` that always matches, which sends the saved password to
  another server; a refused
  certificate not mapped to unreachable, a 401 treated as retryable, no
  sidecar cache, the 206 range unchecked, a vanished collection read as
  empty, the pin ignored, no preemptive sign-in, the sidecar written before
  the object). What it cannot prove: Android's trust manager and SAX
  parser, the screen itself, and a real Nextcloud/NAS — on-device, with
  `DavServer.java` on a laptop as the first target.
- **Any change to the S3 classes, `ServerTrust`, `SidecarCache`,
  `BackupStores` / the stored kind, or `S3Settings`:
  `sh scripts/s3-harness/run.sh`** (run the WebDAV harness too for the
  shared classes). Same shape: the REAL classes copied out of app/src, the
  REAL OkHttp/Okio, Android stubs (a reversible `SyncSecrets` stand-in,
  stub prefs). It checks `S3Signer` against AWS's published S3 examples and
  66 botocore vectors (`fixtures/botocore-vectors.tsv`, regenerated by
  `gen-botocore-vectors.py`: unicode and emoji keys, every reserved
  character, empty values, an IPv6 host); the stored kind, the migration,
  `choose` clearing the other servers, both keys sealed; and drives
  `S3Client` / `S3Store` / `S3Check` with the real `VaultEngine` /
  `VaultObjectReader` against **FakeS3**, an in-JVM S3 that verifies every
  SigV4 signature with its OWN code and enforces R2's multipart rules,
  with a switch for each failure class (wrong or rotated keys, a gone
  bucket, access denied, a moved region, clock skew, 500 / SlowDown / 429
  with Retry-After, a part dropped mid-body, a cancelled or shrunk upload
  leaving nothing, an `<Error>` inside a 200, quota, Range ignored, a
  wrong 206, pagination, no DeleteObjects, a vanished or unreadable
  sidecar) plus two self-made certificates for the pin. Then, when present,
  the same round trip against **moto** (`pip install 'moto[server]'` or
  `MOTO=`; it does NOT verify signatures, so a wrong-secret case is printed,
  not counted) and a **real MinIO** (`MINIO=`; `go install
  github.com/minio/minio@latest` builds one), which does verify signatures,
  names a wrong region and serves the Date header the skew fix reads; what
  landed in the bucket is listed by **boto3** (`BOTO_PYTHON=`), an
  independent client. 198 checks with moto and MinIO, 171 without, ~25 s
  plain. Then 16 planted bugs (`mutants.py`, `MUTANTS=0` skips them; each
  must fail the harness: a '+' treated as unreserved, no skew retry, no
  transient retry, Retry-After ignored, a 206 for another range accepted,
  one big PUT, no abort, a sweep that aborts an open upload, quota read as
  denied, a saved secret reused for any endpoint, a constant sidecar
  validator, a migration that always picks the folder, the secret stored
  unsealed, `choose` keeping the other keys, no region follow, no
  virtual-hosted fallback). What it cannot prove: the page, Android's
  trust manager, and real B2 / R2 / Wasabi behaviour — `docs/S3-LIVE-TEST.md`
  has the device steps.
- **Two more harnesses for the remote stores, from the 2026-10 review.
  Run both after any change to S3, WebDAV, `ServerTrust`, `StallGuard`,
  `SidecarCache`, `BackupLayout` or `VaultEngine`.**
  `sh scripts/backup-fault-harness/run.sh` (hostile and broken SERVERS: a
  raw-socket interposer in front of FakeS3 and FakeDav, plus standalone
  hostile servers): truncations and resets at many offsets (listings,
  sidecars, ranged chunks, the restore stream, mid-upload — the upload
  aborted, nothing committed), Content-Length lies, garbage / DOCTYPE /
  enormous XML, endless pagination, redirects to another host and to http
  (what the second host received is recorded), 503/429 storms with every
  odd Retry-After, a wrong or missing Content-Range, `<Error>` inside a
  200, 404s mid-restore, a moved ETag, a pinned certificate changing
  mid-session, a cancel at every chunk boundary, a server dripping one byte
  per 400 ms (StallGuard's window shortened to a second), a 2-million-node
  listing and a 700k-response multistatus in a 128 MB child JVM, and a sweep
  of every log line, exception chain and toString for a secret. 149 checks,
  11 planted bugs, ~3 min. `sh scripts/backup-sim-harness/run.sh` ("two
  phones, one place": each phone its own class loader, so its own statics,
  prefs, OkHttp, store and sidecar cache, through the REAL check → settings
  → `BackupStores` path): concurrent backups, a listing during another
  phone's half-done upload, a pending sidecar vs the 24 h orphan sweep, a
  delete under another phone's stream, the sidecar cache after another
  phone's backfill or delete, a delete-all under another phone's upload, two
  recovery codes in one place, conflict copies (corrupt and stale), the
  stale-upload sweep, one phone's clock wrong (a HEAD first included), a
  dot-segment prefix, paginated listings, corrupted objects, cancel, a
  backfill race, a random three-phone stress, and backup → list → stream →
  restore byte-compared, for S3 and WebDAV. 128 checks, 13 planted bugs,
  ~2 min. Both were written against the pre-fix code, where 14 and 4 of
  their checks failed (the findings above).

## Browser chrome, pull-to-refresh & session recovery (Fenix-parity invariants)

The dynamic top/bottom bars, pull-to-refresh, and crash/kill recovery were
aligned with current Firefox for Android (mozilla-firefox/firefox `main`).
The invariants, each protecting against a shipped bug:

- **The browser toolbar carries a bottom HAIRLINE, the bottom bar's top
  divider mirrored** (`toolbar_divider` in `browser_address_bar.xml`, tinted
  in `GeckoToolbar.updateTheme` with the same `bottom_bar_divider_*` pair,
  dark = incognito OR system night). Both bars are the flat SURFACE tone
  (the browser passes `tonalHolder=false` too), so without it the chrome
  dissolved into a page whose top is the same tone — issue #306, item 11.
  Keyed on `!mHomeEnabled`, never on the holder tone: Home merges with its
  canvas on purpose. The loading bar sits at an elevation and paints over it.
- **Host-only address bar at rest** (`SETTINGS_ADDRESS_BAR_HOST_ONLY`,
  **default OFF**, General settings — it shipped ON for one build and was
  flipped back on-device: the maintainer wants the full URL in the pill at
  rest, which is what Brave shows and what the bar showed before the switch
  existed; Chrome's host-only rendering stays as an opt-in. The flip needed
  no new key — `true` still means host-only and defaults are never persisted
  — but `settings.xml`'s `defaultValue` and `DEFAULT_ADDRESS_BAR_HOST_ONLY`
  must agree): `AutoCompleteEditText.setHostOnlyDisplay`
  paints the location's HOST (lowercased, `www.` stripped, other subdomains
  kept — Chrome's host, at the START of the pill, Chrome's placement too)
  while UNFOCUSED, and swaps in the full URL, selected, on focus. **It
  shipped CENTRED for one build (Safari's placement) and was reverted on
  sight** (maintainer call: the host floating mid-pill between the shield
  and the reload glyph read as a title, not an address); the
  `applyDisplayGravity` machinery that kept the centring keyed on the
  painted text went with it. Don't bring the centring back — issue #306's
  2026-10-11 follow-up asked for it again with a Chrome screenshot, and
  the maintainer's call stands. `mLocationUri`
  is never the host form; only the PAINTED resting text is transformed
  (`displayTextFor`), through every path that repaints it (`setLocation`,
  blur, `reset`) — so `GeckoToolbar.setUri(uri, false)` now routes through
  `setLocation` rather than a bare `setText`, which would paint the full URL
  once and forget the location. The focus-time swap uses `setText(…, false)`
  so the text watcher stays quiet (no suggestions search for the URL).
  Non-http(s) locations render in full. `BrowserFragment` re-reads the pref
  in `onResume` (Settings is another Activity). Issue #306, item 4.
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
- **OPEN (2026-10-17): every page stays loading after a resume.**
  Reported on a Pixel, Android 17, release 1.2.2: back from ~2 minutes
  in the background, a typed URL shows the progress bar and never
  finishes, in every tab, until the app is force-stopped. The bar moves
  only on Gecko's own `onProgressChange`, so Gecko STARTED each load;
  the stall is below the app. Release builds strip every
  `android.util.Log` call (`proguard-strip-logs.pro`), so the app's own
  log says nothing; Android's events buffer does. In the stuck session it
  shows Android freezing ALL of Firedown's processes, the main one
  included, 20 s after it went to the background (`am_freeze`, twice: 53
  s and 96 s), unfreezing every one of them on each resume
  (`am_unfreeze`), Gecko starting a new content process 7 s after the
  last resume (the typed page), and the user swiping the app away 13 s
  after that resume. So no process was left frozen. The first suspect,
  the ONE extension process every built-in extension shares (uBlock
  answers a blocking `onBeforeRequest` for every request and parks them
  while it loads its lists), is also out as a FROZEN process: freezing
  each content process in turn, the two started at app launch included,
  stalled no load of a site no tab had opened. The whole-app freeze on
  its own is out too: `background` let Android freeze every process
  (`am_freeze`, its own decision each time) for 120 s, three rounds, on
  the network-log build below, and every load typed after the resume
  finished (2026-10-17, Pixel). What the stuck session had that those
  runs lacked is not known yet: a locked screen, a network change while
  frozen, a longer stay, or something open in a tab. Reproduce and
  capture with **`scripts/stuck-repro.sh`**
  (laptop, adb, release build, no root). `background` repeats the stuck
  session: HOME, wait for Android's own `am_freeze` of the main process
  (or freeze every process by hand when nothing comes: a download or
  media keeps an app awake), stay frozen `--away` s, back from the
  launcher, the user types a site. A `geckolog` mode (GeckoView reads
  `/data/local/tmp/<pkg>-geckoview-config.yaml` when the app is the
  Android debug app) was built and REMOVED the same day: in a release
  build it crashes Firedown on every start. R8 moves SnakeYAML into the
  unnamed package, `TypeDescription`'s static initializer reads its own
  package name and gets null (`ExceptionInInitializerError` at
  `DebugConfig.fromFile`), and GeckoView builds one before it opens the
  file, so deleting the file does not help, only `am clear-debug-app`
  does; the script now clears that state at start. Gecko's network log
  comes from a DIAGNOSTIC build instead: `./gradlew assembleRelease
  -Pfiredown.diagnostic=true` sets `BuildConfig.DIAGNOSTIC`, and
  `GeckoRuntimeHelper.geckoNetLogExtras` hands GeckoView `env0`/`env1`
  extras (`GeckoRuntimeSettings.Builder.extras`; GeckoView makes each
  `envN` an environment variable before Gecko starts, the path the YAML
  config fed, minus SnakeYAML): `MOZ_LOG` for nsHttp, nsSocketTransport,
  nsHostResolver, DocumentChannel, nsIOService and
  nsAndroidNetworkLinkService at level 4, `rotate:64`, and
  `MOZ_LOG_FILE` = `Android/data/<pkg>/files/geckolog/net-%PID`, which
  adb can pull. `%PID` matters: with rotate Gecko deletes the file it is
  about to write, so a fixed name would lose the stalled run's log at
  the start after the force-stop. Nothing persists in `prefs.js`, so a
  normal build logs nothing. Such an APK logs every URL and is never
  published: `uploadReleaseMapping` and `releaseStatus` skip themselves
  for it (the mapping would replace the shipped build's under the same
  versionCode). The script copies the folder into its output at the end
  of every run. `freeze` freezes each content process in turn (`am
  freeze --sticky`, Android 14+), each probe on a site no tab has opened
  (Fission gives every site its own process; the first run re-probed
  example.com and its one stall was the probe page's own renderer,
  started 1.2 s after the baseline opened it), and freezes a process
  that stalls a load a second time with another new site, so only one
  that stalls both counts as one every load waits on. `crash` and
  `crashloop` crash the content processes (the latter past Gecko's limit
  of 5 extension-process crashes in 30 s); `watch` records while the
  user reproduces it by hand, and can be started AFTER a stall: the
  capture begins with what the phone's log buffers already hold, and the
  network log is on the phone (each process keeps its last 64 MB, four
  16 MB files). Every mode streams the full logcat (all buffers) with
  `FDSTUCK` marker lines. The frozen flag is read from
  `dumpsys activity processes` (the record's `isFrozen=`; the first
  parser printed `?` for every process on the Pixel, and ending a record
  at a blank or shallow line is one way to get that, so the record now
  ends only at the next header), with the process's cgroup v2
  `cgroup.events` as the fallback. Its adb calls read `/dev/null` on
  purpose: `adb shell` swallows the stdin of the loop it runs in (a
  mutant without it hangs). Related, verified, not this bug: Firedown
  sets no `WebExtensionController.setExtensionProcessDelegate`, so once
  Gecko stops respawning the extension process
  (`onDisabledProcessSpawning`), nothing calls
  `enableExtensionProcessSpawning()`; pages then load, but without ad
  blocking or capture for the rest of the session.

## Tab persistence — the sessions file (v5, Fenix + Chromium hybrid; issue #292 OOM)

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
  `{"version":5,"tabs":[…]}` streamed with `android.util.JsonWriter`/`JsonReader`
  — never a whole-file String or parsed tree in either direction. The current
  shape is **writer-controlled and read STRICTLY** (`readEntityStrict` — exact
  types, unknown key/version THROWS → file moved aside; leniency there would
  only mask writer bugs, Fenix's `BrowserStateReader` stance). The strict
  reader accepts versions 2–5: **v2** carried each tab's session state INLINE
  (`session`); **v3** writes only a `session_ref` file reference (see the
  per-tab state-file bullet below); **v4** retired two per-tab keys,
  `icon_resolution` (its only reader, the history row's higher-res gate, is
  gone) and `tracking_protection` (the per-site ETP switch is gone); **v5**
  retired `thumb` — a tab's screenshot is found in the `TabThumbnailStore` by
  the tab's id (see "Tab thumbnails" below), so no path travels in the file.
  A current tab is therefore exactly what `writeEntity` emits. A v2 file
  reads fully and the next persist rewrites it as the current version. **The
  version must precede the tabs** (every writer has led with it): what
  counts as a known per-tab key depends on it — `RETIRED_KEYS`
  (`GeckoStateDataRepository`, a name → retiring-version map, deliberately
  NOT in `GeckoStateEntity.KEYS`, which lists what the writer emits and
  nothing else) is consulted in the strict reader's `default` branch: a key
  retired in a LATER version than the file's is skipped (the file's own
  writer emitted it), from the retiring version on it is an unknown key like
  any other. Don't reuse any retired name for a new key — files in the wild
  still carry them, and the reader would skip the new key's value there. A
  legacy bare-ARRAY file (pre-v2 builds) is detected by the first token and
  read through the LENIENT per-field readers (`next*Safe` — total by design:
  a bad field falls to its default, never nukes the file; note
  `JsonReader.nextLong/nextInt` throw WITHOUT consuming the token, hence each
  catch's `skipValue()`; its `default` skips any key it doesn't know, so the
  retired keys need no case there). That lenient path is one-time migration
  — the next persist rewrites the current version. Adding a field = writer +
  strict reader together, bump `SESSION_FILE_VERSION` if not
  backward-readable (an APK downgrade across a bump = one-time tab reset,
  Fenix-accepted). **Retiring a field = stop writing it AND bump, adding the
  name to `RETIRED_KEYS` with the new version** — that is what the bump
  buys: the strict reader stops tolerating the key in the current version
  without moving every older file aside as corrupt. A bare removal with no
  bump (how `tracking_protection` was first dropped, and `icon_resolution`
  for one commit) leaves the reader tolerating the key in the current
  version forever, which is a format that says one thing and accepts
  another. **Verify with `sh scripts/sessions-harness/run.sh`**: `splice.py`
  lifts the REAL `readDocumentStrict`/`readEntityStrict`/`readEntity`/
  `next*Safe`, the real `writeEntity`, `RETIRED_KEYS` and the real `KEYS` out
  of app/src (never a copy — the Threads depth-cap lesson) and compiles them
  against Gson's `JsonReader`/`JsonWriter` (the API `android.util`'s was
  derived from); 36 checks across v2/v3/v4/v5/legacy-array + the version
  envelope, then two mutants that must fail (no version gate → every
  retired-key rejection fails; no skip → every load of an older file fails).
- **NO image data in the file, ever.** PREVIEW (og:image — shown nowhere in
  the tab UI) is not written and not restored. A `data:` favicon is
  externalized at persist time to a content-hash-named file under
  `filesDir/tab_icons/` (`TabIconStore`) and referenced by path; the store is
  pruned to the referenced set after every committed persist (empty list
  clears it). `GlideHelper`'s favicon loader has a local-path branch for
  these (with the `.svg`-mime flag). A tab's SCREENSHOT is not in the file
  either — not as a path (v5 retired `thumb`), never as bytes: the
  `TabThumbnailStore` finds it by the tab id the file already carries.
  Fenix's session file likewise carries no icon/thumbnail/preview keys at
  all.
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
- **Concurrency model: one thread, deep copies.** The boot read and every
  persist run on the single `@Qualifiers.DiskIO` executor (screenshot
  writes are the `TabThumbnailStore`'s, on `HeavyIO` — an encode never
  queues in front of a DB write; FIFO — and the repo provider enqueues `initializeGeckoStates` at
  DI time, so it always precedes the first persist; the tabs LiveData has no
  initial value, so `observeForever` can't fire a pre-init empty write).
  `notifyTabs` posts **deep-copied** entity snapshots, so persist never sees
  a mutating list or torn fields. No lock is held across file IO on the
  persist/boot-read paths; the ONE exception is the archive sweep
  (`archiveInactiveTabsLocked`), which has always done its Room
  insert/purge IO under the `mGeckoStates` monitor on the disk executor
  (its screenshot removal is a non-blocking `TabThumbnailStore.remove`) — the v3 inline-resolve read rides that pre-existing pattern
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
- **Tab order is LIST order, user-arranged — there is no boot-time sort.**
  The switcher reorders by long-press drag (`BaseTabsFragment`'s one
  `ItemTouchHelper` owns swipe-to-close AND drag; `getDragDirs`/`canDropOver`
  exclude the banner row). Every crossing commits to the REPOSITORY by id
  (`moveGeckoState(fromId, toId)` on both repos — remove-then-insert at the
  target's index), never to an adapter-local copy: `notifyTabs` can fire
  mid-drag for an unrelated tab (title/thumbnail) and would snap a local
  move back, while a repository commit makes every emission carry the
  dragged order; persistence is batched, so one file write per drag. The
  sessions file is written in list order, so file order IS the order —
  `initializeGeckoStates` used to re-sort by creation date after loading,
  a no-op for every list it ever saw (tabs append in creation order,
  undo-close restores at its old index) that would have reset the
  arrangement on every cold start. It was removed; don't reintroduce it,
  and don't add a sort anywhere between `loadEntities` and `notifyTabs`.
- **The archive sweep is TWO passes: inactivity + DUPLICATES (the Brave
  model).** The duplicate pass (`SETTINGS_TABS_ARCHIVE_DUPLICATES`, default
  ON, its own toggle on the tabs settings screen) archives same-page copies
  keeping the most recently used one — the case the inactivity timer
  structurally can't catch, because re-opening a duplicate REFRESHES its
  `lastAccess`. Grouping is by `GeckoState.getPageIdentityKey()` (the
  visit-identity normalization — fragment + tracking-noise params ignored),
  so share-link copies differing only in `?utm_*` collapse; a null key
  (opaque host) never dupe-matches. Exclusions mirror the inactivity pass
  (home/incognito never participate); the ACTIVE tab joins its group but
  always wins it. The pass is deliberately INDEPENDENT of the interval —
  it runs even with the interval on "Never", so every sweep gate is
  `threshold > 0 || duplicates` (init in `DatabaseModule`, the settings
  screen's immediate sweeps, the tabs sheet's 6-hour debounced trigger).
  The archive's retention (90-day age purge + 200-entry cap,
  `purgeSync`) is DISCLOSED on the settings screen via a footnote row
  whose summary is formatted from the SAME `Preferences` constants
  `purgeSync` enforces — the honest-copy rule; don't hardcode the figures
  into the string.
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

### Tab thumbnails — `TabThumbnailStore`: one store by tab id, no bitmap on the entity

The tab grid's screenshots live in `data/TabThumbnailStore` (Hilt
`@Singleton`, the `FaviconStore` shape): a byte-bounded memory LRU in front
of a disk tier of `cacheDir/thumbs/<tabId>.webp`, keyed by TAB ID — the
Fenix (`browser-thumbnails`, WEBP files by tab id) / Chromium
(`TabContentManager`, a count-bounded memory cache over JPEG files by tab
id) shape. The entity carries NO thumbnail: not a bitmap, not a path. A
repository snapshot stamps `thumbVersion` (the store's version of that tab's
screenshot at emission time, snapshot-only, never persisted or parcelled),
the diff compares that int, and `BrowserTabsAdapter.bindThumb` reads from
the store by id. History, and why the old shape went: a `transient Bitmap
cachedThumb` rode on `GeckoState` AND on every emitted entity copy, the diff
compared it by reference, the regular repo wrote a PNG at quality 100 on the
DB lane and then cleared the bitmap on the disk thread with no notify (the
next unrelated emission rebound the tile from disk through a null
placeholder — a blink), it deleted the old file before writing the new one,
incognito hand-rolled a count-capped trim, and `BaseTabsFragment.closeTab`
cleared a field the live entity never held. Rules, each pinned by
`sh scripts/thumb-harness/run.sh` (the REAL class against stubbed
`android.graphics.Bitmap` / `android.util.LruCache`, a hand-cranked executor
so the store is examined BETWEEN a put and its write; a mutant that sizes
every entry as one byte must fail the budget check):

- **The just-captured tab is a SYNCHRONOUS memory hit.** `put` stores into
  the memory tier before anything is written and the entry stays until
  evicted — no memory-to-disk handoff, nothing to race. `bindThumb` sets a
  `peek` hit on the view directly (after `Glide.clear`, so an in-flight load
  for the holder's previous tab can't paint over it); only a miss goes to
  Glide, loading `fileFor(id)` with `.signature(ObjectKey(thumbVersion))` and
  `DiskCacheStrategy.NONE` (the store IS the disk tier). `hasFile` answers
  from an index built once at construction, optimistic until it lands, so a
  bind never stats on the main thread.
- **Incognito never touches disk — `persist` is the CALL's choice.** Both
  repositories expose `updateThumb(state, bitmap)` (`BrowserFragment`'s two
  capture sites call one or the other; the fragment no longer pokes
  `GeckoState`); the incognito one passes `persist=false`, so eviction
  degrades a private tab to the surface placeholder until its next
  contentful paint — the trade that was always made, now under one policy.
  The adapter never loads a file for an incognito entity.
- **Memory is ONE byte budget shared by both modes** — an eighth of the heap
  clamped to [4, 24] MB (~16 MB on the 128 MB heap, about six half-res
  ARGB_8888 shots), evicted least-recently-USED (a bind counts). That
  replaced "clear every non-active regular tab on switch" + incognito's cap
  of 8. Nothing recycles a bitmap: an evicted one may still be on a tile.
- **The disk tier is lossy WEBP at 80 on `HeavyIO`**, atomic tmp→rename,
  ONE file per tab overwritten in place — several times faster to encode
  and 5–10× smaller than the PNG it replaces, and never on the DB lane.
  `version` comes from one monotonic clock per store, so a remove/re-put of
  the same id can never collide in Glide's memory cache.
- **Lifecycle is per id, never a store-wide clear**: `remove` on close,
  archive and both `deleteAll`s (the memory tier is shared, a regular
  delete-all must not blank the incognito grid; an undo-close reopens the
  tab without a screenshot until its next paint, as before). `prune(liveIds)`
  runs from the observer after a committed persist, under the hourly
  state-prune throttle, and is GRACE-based: files are written on capture,
  not on persist, so an unreferenced file younger than 10 min is a tab the
  last snapshot missed and is kept; older unreferenced ones, the legacy
  `<id>_<ts>.png` files from before the store, torn tmps and foreign files
  are deleted. The observer's `deleteThumbnails` dir wipe is gone.
- **Upgrade cost, one-time and cosmetic:** a tab restored from a pre-store
  sessions file shows no screenshot until revisited (its PNG is under the
  old name; the first prune reclaims it).

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

**Prompt plumbing: a prompt and its `GeckoResult` travel TOGETHER — there is
no shared response slot, and it must never come back.** The delegate used to
park each prompt's result in one `mPromptResponse` field (one delegate
instance, all tabs, all prompt types) that whatever dialog answered next
completed. Concurrent prompts clobbered it — a file picker is an external
Activity, so the page's JS keeps running and can fire an `alert()` while it's
open, after which the file pick either answered the ALERT's result or found
the slot consumed and silently dropped the pick. The refactor threads `res`
through the whole chain (delegate → `GeckoObserverInvoker` → observer →
`GeckoPromptManager` → a per-prompt handler lambda), the file round-trip
keeps prompt+result on the launching `GeckoState`
(`setPendingFilePrompt`/`setPendingFileResult`, answered to the state that
LAUNCHED, never `peekCurrentGeckoState` — the user can switch tabs while the
picker is open), and the permission path uses
`GeckoComponents.PermissionResult`, whose one-shot latch is load-bearing: the
permission dialog answers TWICE by construction (button click, then the
dismiss listener's no-choice DENY default), which only the old slot's
consume-once behavior made survivable. Invariants, each closing a real hole:
- **Every gate that declines to show a dialog COMPLETES the result**
  (`dismissPrompt` / `VALUE_PROMPT`). `prompt.dismiss()` alone only BUILDS
  the response — a gate that discards it leaves the page's JS blocked in
  `alert()` forever (all nine manager gates did exactly that, and the
  permission gate's bare `return` hung `getUserMedia`).
- **The delegate runs a post-notify sweep** (`promptResultOrDismiss`):
  `notifyObservers` is synchronous, so on return the prompt is either on
  screen (`isPromptDisplaying`, set before `show()`), answered
  (`isComplete`), or consumed by nobody — no browser fragment registered,
  mode filter skipped it, an overlay owns the screen — and then the delegate
  answers with a dismissal itself rather than return a result nobody will
  complete. The file variant checks `getFilePrompt() == prompt` (the
  fragment stores it before launching) to confirm the picker actually
  launched.
- **A null `PromptResponse` is never completed into a result**
  (`GeckoPromptManager.completeResponse`, the ONE completer):
  `confirm()`/`dismiss()` return null when the prompt was already answered,
  and `res.complete(null)` crashes GeckoView's PromptController on
  `null.dispatch()` — the 1.1.88 crash (`createAlertDialog`'s OK button also
  passed a literal null; an alert's only response is `dismiss()`).
- **Prompt observers filter `isIncognito() != mIsIncognitoThemed`** like
  every other UI observer — `isCurrentGeckoState` is per-repo, so the
  current tab of EACH mode passes the delegate gate, and without the filter
  a background incognito tab's timer-fired prompt popped its dialog over
  regular browsing. Skipping is safe only BECAUSE of the sweep.
- **Flood breaker** (`GeckoState.recordPromptShown`/`isPromptFlooding`,
  delegating to `PromptFloodGate` — its own class so the harness can run the
  REAL ring; fed by the manager's single `showDialog` door): after 3 dialogs
  inside 10 s on one tab, further prompts are auto-dismissed until the window
  drains — a `while(true) alert()` page can't hold the browser hostage.
  Self-healing, uptime-based, no navigation hook needed.
- **Verify prompt-manager changes with `sh scripts/prompt-harness/run.sh`**:
  it copies the REAL `GeckoPromptManager` + `PromptFloodGate` from app/src,
  compiles them against stubs (the factory stub mirrors only the dialog
  CONTRACT — answers on dismissal, responds once) and drives a fake
  malicious page: burst flood (first prompt shows, the other nine are
  answered-dismissed synchronously, zero new dialogs), the nag loop (exactly
  3 dialogs per window, then auto-dismissals), window drain, per-tab flood
  isolation, sheet/non-browser destination, tab-switch dismissal, and a
  final every-result-completed leak sweep (the stub `GeckoResult` throws on
  double completion, like the real one). 14 assertions, seconds, JDK only.
  Teeth proven by mutation: stripping the flood gate fails the nag cases;
  reverting a gate to the old discard-`dismiss()` fails seven, including
  `leaked=15` — the old hang bug made visible.
- Bottom sheets need no extra visibility check: they are `<dialog>` nav
  destinations, so while one is up `getCurrentDestination()` is not
  `R.id.browser` and `canShowPrompt` already declines (and completes).

Symptom this prevents: the "open in app" dialog (or an alert/file picker) from
a *previous* tab appearing after you switch tabs (repro: open bilibili.com,
switch tab mid-load; it fires a `bilibili://` deeplink from the background).

#### Long-press context menu — glyphs are index-paired arrays, image rows get a thumbnail

`BrowserContentDialogFragment` zips `context_link`/`context_image` (the
string arrays, whose ids are the DISPATCH keys `BrowserFragment` switches
on) with `context_link_icon`/`context_image_icon` (`arrays.xml`) — keep the
pairs in lockstep when adding a row. The glyph rides `OptionItem.iconRes`,
presentation only. An image element's header shows a 56dp thumbnail beside
the URL, fetched with the page-origin Referer + an image Accept (a bare
Glide GET 403s on a hotlink-gated CDN); `data:` sources load
as strings. A failed load leaves the slot hidden; link-only menus never
show it. Issue #306, item 6. **Its rows are the sheets' grouped cards and
the dialog is INSET, not edge to edge** (the issue's follow-up comment,
with screenshots: "Material Design 3 cards" for this menu, "center this
menu so it doesn't touch the edges"). `fragment_dialog_content_item` is a
`SheetRowCard` + `SheetRowText` like `fragment_dialog_options_item`, kept
as its own layout for ONE override — `maxLines="2"` + 8dp vertical padding
— because the dialog is 36dp narrower than a sheet on a 360dp phone and
carries the app's longest menu labels ("Copiar ubicación de la imagen"),
which the shared row's one-line ellipsis would cut; on a linked image the
link rows and the image rows are two groups split by an
`OptionItem.separator()` (the 12dp gap, corners closed and reopened by
`OptionsAdapter`) where a hairline `ItemDecoration` ran (that class,
`HorizontalDividerItemDecoration`, had no other user and is gone). The
width is set in `onResume` — 90% of the screen capped at
`max_dialog_width`, the Rename/Save dialogs' rule — because the window is
transparent and the root paints the surface, so a dialog with no explicit
width is as wide as its widest child: the old rows declared
`layout_width="500dp"` (the retired `dialog_width` dimen), which is what
pushed it edge to edge on every phone. `@drawable/popup`, the surface, is
the 28dp M3 dialog corner now (it was 8dp, smaller than the 16dp card
corners inside it).

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

**"Open link in new tab" on an APP link (`intent://…`) opens the link's WEB
form, never a tab that can't load.** On-device: Play Store's own "Open in
app" banner is an `intent://play.google.com/…#Intent;scheme=https;…;
S.browser_fallback_url=…;end` link; long-press → open in new tab → Switch
produced a tab stuck on about:blank. Two defects stacked: a tab can only load
http(s) (the delegate denies every other scheme), AND that first load ran
inside `setGeckoViewSession` BEFORE the tail that makes the new tab current,
so the deny's UI was gated out by `isCurrentGeckoState` — no dialog, no
snackbar, nothing. Now `AppLinkUseCases.webUrlForAppLink` resolves the link
Chrome's way (`browser_fallback_url` if http(s), else the intent's own data
URI when `scheme=` is http(s)) and the tab opens on that; a link with no web
form is routed to `onLoadRequest(current, uri, false, false)` — the
open-in-app flow a deliberate tap gets — and no tab is created. And the
generic-deeplink branch of `onLoadRequest` notifies the observer for a
DIRECT navigation regardless of current-tab state: a direct navigation is
the app's own `loadUri`, always the tab being put on screen, so its
open-in-app dialog must not depend on the ordering inside
`setGeckoViewSession`. Page-initiated deeplinks keep the current-tab gate.

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

**The artwork and the title are the CONTROLLER's too (issue #308).** The
page's metadata — the title and the artwork a MediaSession-API page
publishes — reaches `GeckoMediaController.onMetaData` for every tab,
ungated; the picture used to be fetched only in
`BrowserFragment.onMediaMetadata`, which runs only while the tab is current
and the browser UI is alive, and a page sends its metadata the moment the
clip loads (before the tab is current on a fresh open) and from the
background when the next clip autoplays. So the entry held a title with no
picture; the service's fallback (`fetchBitmap`, the page's favicon) ran only
on a `MEDIA_METADATA` action and had no URL anyway, because an entry created
FROM the metadata carried no page facts; and a PLAY posted a notification
with no artwork and never asked again — a letter tile, or the previous
clip's picture, where the reporter expected the video's. Now `onMetaData`
seeds the page facts first (`ensureMetaExists`), takes a new title only when
it is non-empty, drops the stale picture when the clip changed, fetches the
artwork itself at `ARTWORK_SIZE` (512 — the 48 it asked for was a favicon
blurred across the lock screen's artwork slot) and pushes `MEDIA_METADATA`
to a running service so the title changes at once; the service fetches its
fallback whenever an entry has no picture, whatever the action, once per
session (`mArtworkFetches`), and a late fallback never replaces the page's
own artwork. On Android 13+ the system draws the notification from the
session's `MediaMetadata` (`setMetadata`: title, artist, art) and ignores
the builder's own title and large icon — never carry a fact on the builder
alone. `MediaNotificationTest` (androidTest, `geckoview/media/`) is the
device check, with no network: a loopback page plays a tone and sets
`navigator.mediaSession.metadata` (title, artist, a solid-colour PNG) on a
tab registered in the real repository with the real `GeckoComponents`
delegates and no browser fragment, then moves on to a second clip; the
test reads the `MEDIA_ID` notification's title and picture and the
controller's bitmap by colour, for both clips. The maintainer could not
reproduce the report by hand. **Its page server binds `127.0.0.1` BY
NAME**: on Android `InetAddress.getLoopbackAddress()` is the IPv6 `::1`,
and a server bound there answers nothing at the `http://127.0.0.1` URL the
page is loaded from — the first device run showed the engine's own error
page ("Problem loading page", connection refused), the share engine's
loopback server's lesson re-made. Every loopback test server here binds the
IPv4 literal (the stress test's, the pipeline test's), and this test's
navigation delegate now records a load error's code in the assertion so the
next such failure names itself. With the bind fixed it passes on that device
(SM-A426B, Android 12): the title, the red and then the blue artwork, the
second clip's title on the controller — #308's fix is device-verified.

### Web content color scheme follows the APP theme (issue #306, item 14)

`prefers-color-scheme` for pages is derived from the theme PREF in ONE place,
`Preferences.getPreferredColorScheme` (Dark/OLED → `COLOR_SCHEME_DARK`,
Light → `LIGHT`, follow-system → `COLOR_SCHEME_SYSTEM`, which Gecko tracks
itself): read into the runtime builder at boot and re-applied live by
`GeckoRuntimeHelper.applyPreferredColorScheme()` from `ThemeFragment` after
each pick (the sub-screen-applies-its-own-prefs rule) and from
`BrowserFragment.onConfigurationChanged`. History: the runtime booted on
`COLOR_SCHEME_SYSTEM` and the only later writer was that
`onConfigurationChanged`, which maps `newConfig.uiMode` — but `uiMode` is not
in the manifest's `configChanges`, so a theme switch recreates the activity
and the callback never runs for it. App-Dark on an OS-Light phone rendered
every page light; reported as "add a global dark mode for all tabs". Don't
re-derive the scheme from `uiMode` anywhere — the pref is the truth and the
callback only covers rotation. What this is NOT: a force-darken of light
sites (Chrome's auto-dark). Gecko has no such engine feature; the only
honest version would be injected CSS filters, which was declined.

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

### Password autofill comes from the GeckoView WIDGET — a display refactor kills it silently

Bitwarden / 1Password / Proton Pass fill login forms **inside pages** today,
and the app contains **no autofill code for it**: no `Autofill` import, no
`setAutofillDelegate`, no `getAutofillSession`. It works because
`NestedGeckoView extends GeckoView` and `BrowserFragment.setGeckoViewSession`
calls `mGeckoView.setSession(...)` — and `GeckoView.setSession` is where the
widget installs its OWN delegate (`if (mAutofillEnabled) session
.setAutofillDelegate(mAutofillDelegate)`, and `mAutofillEnabled` defaults to
`true`). The widget also implements `onProvideAutofillVirtualStructure()` /
`autofill(SparseArray)`, and its inner `AndroidAutofillDelegate` drives
`AutofillManager.notifyViewEntered/notifyViewExited/notifyValueChanged/cancel/
commit`. `minSdkVersion 26` is the Autofill framework's own minimum, so
there's no version gate.

**The trap: anything that stops routing the session through the `GeckoView`
widget** — driving a `GeckoDisplay` into a `SurfaceView`/`TextureView`
directly, or wrapping the session in a custom view — takes the delegate with
it. There is no crash, no log, no error: password managers just quietly stop
offering to fill, which reads as a bug in the user's password manager. If
that refactor ever happens, the session needs
`session.setAutofillDelegate(...)` + the two `View` overrides re-implemented
by hand. Same for `android:importantForAutofill="no"`: it's set on the app's
own EditTexts (URL bar, rename/save dialogs, list search fields) on purpose
and must never land on the GeckoView.

**Three things deliberately NOT built.** (1) A per-app autofill off switch
(`setAutofillEnabled(false)`). The privacy cost is real — the delegate hands
the page's field structure and value-change events to whatever app is the
autofill service — but that service is the user's own password manager and
the SYSTEM already owns that control, which the Settings → "Passwords &
autofill" row links straight to; a second switch duplicating a system setting
is the pref-that-gets-misread that killed the WebRTC toggle. (2) Disabling
autofill for incognito: logging into a second account in a private tab is a
primary use of private browsing, and Chrome/Firefox both keep filling enabled
there. (3) Firedown stores NO logins of its own — no
`Autocomplete.StorageDelegate` is set, so GeckoView never persists a password
and the system autofill service is the only credential path. Keep it that
way; a built-in password store means encryption, backup and a security
surface a downloader has no business owning.

## Page titles, history, bookmarks & favicons

Aligned with current Firefox for Android. The spine is one invariant:

**A tab's title must always belong to the tab's current URL.** GeckoView fires
`onTitleChange` as a SEPARATE, LATER event than `onLocationChange`, so without
care a tab briefly (and on title-less SPA navigations, indefinitely) holds the
NEW url paired with the PREVIOUS page's title. That mismatch then gets persisted
(history row) and shown (autocomplete "recent tabs"), e.g. a news site's
URL displaying a social post's title.

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

- **Favicon updates — NEWEST WINS, no resolution.** `IconsRepository.updateIcon`
  writes the icon URL a page reported to its history row
  (`WebHistoryDao.updateIcon`) and bookmark row, ONE conditional UPDATE each,
  whose only gate is the **no-op guard** `file_icon IS NOT :icon` (`IS NOT` =
  null-safe): an unconditional UPDATE fires Room invalidation on EVERY
  revisit, needlessly requerying the Paging list. History used to carry a
  `file_icon_resolution` column and a "keep the higher-res icon" gate on it
  (fed first by a per-icon HEAD request estimating from `Content-Length`,
  later by the favicon store's byte length); both are GONE (`webhistory-db`
  5→6, `MIGRATION_5_6`): the gate was Firedown-only (Firefox keeps every
  size and picks at read time, Chrome replaces on update), it PINNED a stale
  icon whenever a site moved to a smaller or undeclared-size one, and it
  made history disagree with bookmarks, which were always newest-wins.
  `GeckoRuntimeHelper.iconPixels` still exists for `iconScore` — choosing the
  best icon AMONG a page's declared ones is a different question from
  gating across visits. Don't reintroduce a read-then-write
  (`getResolution` once sampled ONE arbitrary row then overwrote ALL rows).
  - **`MIGRATION_5_6` is a table RECREATE** (SQLite on minSdk 26 has no
    `DROP COLUMN`): Room's exact entity DDL, copy, `DROP TABLE`, rename, then
    the `file_url` index and the three FTS sync triggers are recreated — the
    DROP takes them with the old table, while `webhistory_fts` keeps its rows
    (`DROP TABLE` fires no row triggers; docid = uid is unchanged by the
    copy). Room's own persistent-mode invalidation triggers go the same way
    and Room recreates them at open, after the migration. Pinned by a SQLite
    replay of the real SQL strings (fixture = the real 1→5 DDL + FTS,
    populated; checks columns/types/PK, rows, index, triggers, FTS sync on
    insert/update/delete, and the DAO's queries on the result).
  - **The sessions file's `icon_resolution` key is RETIRED in v4 of the
    file** (the name lives only in the strict reader's `RETIRED_KEYS` map,
    not in `GeckoStateEntity.KEYS`): the writer no longer emits it, a v2/v3
    file carrying it loads (skipped), a v4+ file carrying it is rejected
    like any unknown key — see
    "Tab persistence" for the retire-by-bump rule and the harness that pins
    it. The tab entity's `iconResolution` field (never set from the icons
    message — dead data, only copied into the history insert) went with it,
    Parcel read AND write.
  - **`webhistory(file_url)` is INDEXED** (`@Index` + `MIGRATION_3_4`, version
    3→4, `CREATE INDEX` matching Room's exact DDL — same proven pattern as
    `DownloadDatabase.MIGRATION_10_11`). The uid PK is `hash(url)+day`, NOT the
    lookup key, so the url-keyed favicon/title updates would otherwise full-scan.
  - **A visit never ERASES a page's favicon** (`WebHistoryDataRepository
    .keepKnownIcon`). Every `onHistoryStateChange` REPLACEs the (url, day) row
    with the TAB's icon at that instant, and that is often none: a host change
    clears it (`updateVisit`) and only the icons extension's document_end
    message restores it, and only for the exact url it names. A Back restored
    from bfcache never re-runs that script, and an SPA url can move before or
    after it runs. Since the most-visited query reads each url's LATEST row,
    the tile flipped between the favicon and the generated letter depending on
    how the last visit went (reported on-device for x.com). Now a visit with no
    icon inherits the newest one any earlier visit of the SAME url stored
    (`getLatestWithIcon`, indexed, LIMIT 1). The strip also
    falls back at read time (`AutoCompleteSearch.tileIcon`: the url's newest
    stored icon, then another page of the same host's), which covers rows
    written before the carry-over and urls that never got an icon. A
    `TabIconStore` sidecar path (`/…`, a restored tab's data: favicon) is never
    stored in history; it is pruned with its tab.
  - **`icons.js` resends on a bfcache restore** (`pageshow` with
    `persisted`). A restored page is the same document, so the document_end
    send never runs again, while `updateVisit` cleared the tab's favicon when
    the tab left the site: the tab stayed iconless after a cross-host Back.
    The resend re-reads the head at restore time; a normal load's `pageshow`
    (`persisted` false) does not double-send. The script has no console
    output (it logged every page's reply in release builds). Pinned by
    `node scripts/icons-smoke.mjs` (the real script in a vm; on the old file
    the resend checks fail). Any change to `assets/icons/` bumps its
    manifest `version` (the `ensureBuiltIn` trap applies to it too).
  - **Favicons live in Firedown's OWN store, not in Glide's cache**
    (`data/FaviconStore`, `filesDir/favicons/`, one file per icon URL named
    by its SHA-1, the file's mtime = last fetch). This is the Firefox
    (favicons.sqlite) / Chrome (Favicons db) model. Before it, icons lived only
    in Glide's 250 MB disk cache, shared with every captured thumbnail. So
    favicons were evicted by unrelated images (letters offline), and an icon
    replaced behind the same URL never updated (no expiry). It is a **Hilt
    `@Singleton`** (`@Inject` constructor; `IconsRepository`,
    `WebHistoryDataRepository` and the two UI clearers inject it, `GlideModule`
    and the static `GlideHelper` reach it through their existing entry
    points) — never a static `get()`; the `(File, Executor)` constructor is
    the harness's door only. **The DATABASE is unchanged**: history and
    bookmark rows and the tab entity keep the icon URL, which IS the store's
    key (Firefox's `moz_icons_to_pages` shape — a page row references its icon
    by URL, the bytes live in the icon store). The one schema change is the
    DROPPED `file_icon_resolution` column (see "Favicon updates — NEWEST
    WINS"); no per-icon HEAD request exists any more, and an icon not stored
    (a private tab's) costs no request at all. The rules:
    - **Display reads the store** through `glide/FaviconModelLoader`
      (`GlideHelper.load(icon, url, …)`, the one loader every page-favicon
      surface uses), `DiskCacheStrategy.NONE`. A stored icon never touches
      the network to display. A miss fetches once (`data/FaviconFetch`: page
      Referer + image Accept, body capped at 512 KB while it streams) and
      stores it. The model's key carries `cacheVersion(icon)`, which changes
      when the stored bytes change or the store is cleared, so Glide's memory
      cache can't keep painting an old bitmap. There is deliberately NO
      fallback to Glide's pre-store disk-cache copy: one request path, and
      that cache also held private tabs' icons (the old loader wrote
      everything with `DiskCacheStrategy.ALL`). The one-time cost is that the
      first display after the update refetches each visible favicon.
    - **Refresh is VISIT-driven, like the big browsers.** `IconsRepository`
      refetches when a page reports its icon and the stored copy is missing
      or older than 7 days. Single-flight, disk executor for the stat, OkHttp
      async for the fetch. Identical bytes only reset the clock. A failed
      fetch keeps the old bytes, and so does a non-image answer
      (`looksLikeImage`: PNG/GIF/JPEG/ICO/BMP/WEBP/AVIF-HEIF brands/SVG, an
      HTML page, even one with an inline `<svg>`, JSON or an mp4 is refused).
      So an icon the site stopped serving keeps showing.
    - **Private tabs never write it.** The visit refresh is gated on the
      SENDING SESSION being a regular tab (`setIcon`'s `regularTab`, from
      `sender.session`), not on the url match: a private tab whose SPA url
      moved matches no tab by url. Surfaces that can show a private tab (tab
      grid, browser popup, security sheet, autocomplete) load with
      `persist=false`: they read the store but never write a fetch into it.
    - **Only the TOP frame's icon message counts** (`onMessage`'s
      `sender.isTopLevel()` gate). `icons.js` runs in every frame, and a
      frame with no icon link falls back to `<origin>/favicon.ico`. Every ad
      iframe used to cost a HEAD to its origin per page load, and would now
      fetch and store its icon.
    - **Cleared with history and cache.** Deleting all history (incl. quit
      "history") clears it. A range delete removes the icons fetched inside
      the range (`deleteFetchedSince`). "Delete browsing data" and quit
      "cache" clear it too.
    - **Bounded:** 2000 files / 24 MB, enforced every 32 writes, oldest fetch
      first.
    - Verify with `sh scripts/favicon-harness/run.sh` (the real class on a
      temp dir, JDK only, 35 checks). Mutation-checked: dropping the image
      gate fails 8, a range delete that deletes everything fails 1.
    - `TabIconStore` (`filesDir/tab_icons/`) stays SEPARATE on purpose: it
      externalizes a tab's inline `data:` favicon out of the sessions file
      and mirrors the live tab set (pruned to what the file references). The
      favicon store is keyed by fetchable URL and cleared with history; a
      tab's data: icon is neither. Both display through the one
      `GlideHelper.load(icon, url, …)` (`data:` → inline decode, `/…` → the
      sidecar file, http(s) → the store).
    - Known residual: Glide's OLD disk cache still holds favicons written
      before the store, including private tabs'. Nothing reads them any more;
      they age out under LRU (or go with "Delete browsing data"'s image-cache
      clear if that is ever extended to Glide's cache).

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

**No request goes out as `okhttp/x.y`.** `UserAgentInterceptor` (first on the
shared client, and on the DoH bootstrap client) gives a request with NO
User-Agent the app's Firefox one, `BrowserHeaders.getDefaultUserAgentString()`
(desktop shape, version from the bundled GeckoView). It only fills a missing
header: a captured page UA, ffmpeg's, SABR's YouTube client UA and every
explicit header keep theirs. Clients derived with `newBuilder()` inherit it;
`WebDavClient`, which drops the app's interceptors, keeps this one alone. Before
it, Glide posters, raw downloads whose capture cached no UA
(`DownloadContext.getUserAgent()` was never called), the native master fetch,
Deezer/Mega, P2P, crash/feedback and DoH all sent OkHttp's default. The known
risk: a bot-scoring CDN that compares the UA with the TLS handshake sees a
Firefox UA over OkHttp's TLS, the same pairing every ffmpeg download already
sends. Don't add per-call User-Agent code for the default case; set one only
where a request must claim a DIFFERENT client.

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

**A `data:` URL is the file — its payload never crosses Binder and never
reaches a row (`utils/DataUris`, `manager/DownloadIntents`).** An inline
image saved from the long-press menu, or a `download` link to a `data:`
URL, is a `DownloadRequest` whose URL is megabytes of base64. It shipped as
`TransactionTooLargeException: data parcel size 4570572 bytes` out of
`startService` (Binder's buffer is 1 MB for the whole process, and a String
parcels as UTF-16), and the same string went into the row's `file_url` and
`file_origin_url` (a column that does not fit a CursorWindow breaks every
list read) and into the context dialog's nav args (saved state, the same
exception on stop). Now: every start goes through `DownloadIntents.start`
(the Intent carries a request only while its parcel is under
`MAX_INLINE_BYTES`, else the batch is parked in-process and the Intent
carries a token; the engine reads both through `DownloadIntents.read`; a
token nothing is parked under — a start the system redelivered into a new
process, an evicted batch — adds nothing and the engine posts `MSG_STOP` so
the service it started does not sit there; a `startService` the system
refuses un-parks its batch), `DownloadTask.initialize` and the
`WebResponse` entity keep `DataUris.stripPayload` — the header,
`data:image/png;base64,` — as the URL AND the origin, the context bundle
carries the element `withoutDataPayload()` (src, link and base alike: a tab
opened on a data: image has it as its base) plus the TAB's id (read by
PRESENCE, not sign: a tab id is a `hashCode`, negative half the time), and
the dialog takes the whole element back from THAT tab (`isStrippedCopyOf`),
never from whichever tab is current. `handleDataUrl` decodes through
`DataUris.decode`: percent-decoded, then base64 when `;base64` closes the
metadata — read to the REAL comma (a capped prefix wrote a long-parameter
payload out as text) — with the `#fragment` dropped as the WHATWG processor
does (the old `split(",")[1]` + `Base64.decode` broke on a percent-encoded
SVG, and `isDataUrl`'s regex missed a URL with no `;`). A data: download's
origin is its PAGE, not the header, or every data: download shares one
"page". A retry of a `data:` row has no bytes to re-read and reports the
file gone, through `HttpDownloadStrategy` and `GeckoStreamStrategy` alike.
**Copy link / Copy image location / Share link hand the URL to another
process** (the clipboard, a chooser Intent) over the same Binder:
`BrowserContextActions.tooLargeToHandOff` (64 K chars) answers with a
snackbar instead of a crash. **Rows written BEFORE the fix hold the
payload** and crashed their own tap and delete:
`DownloadDatabase.MIGRATION_13_14` cuts `file_url` / `file_origin_url` to
the header and points `file_img` back at the file — after dropping the
persistent tracking triggers when `room_table_modification_log` is missing
(the one-way-door state of the Room section): a migration's UPDATE fires
them before Room's onOpen can recreate the table, which would turn a
database that used to heal at open into a crash loop. The transport belt is
generic on purpose: whatever field grows (a cookie jar, a header set), the
Intent stays small — but it covers `DOWNLOAD_START` only; the
`DownloadEntity` lists of a multi-select (delete, restart, lock, and the
nav Bundles carrying them) are chunked by `requestDelete` alone and remain
a known gap. Pinned by `sh scripts/datauri-harness/run.sh` (the REAL
`DataUris`, JDK only; mutants: a strip that keeps the payload, a decoder
that skips percent-decoding, an uncapped header, a base64 flag read from a
capped prefix, a payload keeping its fragment — each fails) and the engine
harness's section 13 (`DownloadIntents` under a stubbed `Parcel`: inline,
list, token round trip, a token read twice, a refused start, eviction).
On-device unverified at write time: the dialog taking the element back, the
saved file's name, the migration on a real pre-fix row.
Device tests: `DownloadIntentsTest` (the carrier a request takes and the
one-shot token), `DownloadPipelineTest` (a 200 KB `data:` download through
the real service: the row keeps the header, the file the bytes),
`DownloadDatabaseMigrationTest` (`MIGRATION_13_14` on a real v13 file, the
orphaned-trigger guard included) and `ContextElementEntityTest`; JVM:
`DataUrisTest`, `BrowserContextActionsTest`. See "Device tests you can debug
from Android Studio" under the download engine.

**A vault download is written into the app-private safe folder — the ENGINE
picks the directory, and the strategies write where the context says.** A
private tab's download (`saveToVault`) was meant for `files/safe/` and never
got there: `DownloadTask` swapped the directory on the row, but
`DownloadContext.getOutputFile()` rebuilt the path from the file's NAME under
the PUBLIC download folder, every strategy wrote there, and
`onFilePathResolved` pointed the row back at the public file — a `file_safe`
row over a file any file manager could read, in every release before 1.2.5
(the Safe Folder listed and played it, so nothing looked wrong). Now
`DownloadEngine.getFilePathForUrl` takes the directory from `isSaveToVault()`,
so its collision loop runs against the folder the file will be in,
`DownloadTask` keeps the path as given, `getOutputFile()` IS the context's
path (a resume continues at its own partial file wherever the row says,
instead of restarting under whatever the download folder is today), and
`DownloadRunnable` makes that file's directory (`ensureOutputDirectory`)
before the strategy runs. Otherwise only the user's Lock (`InSafeTask`) and
Unlock (`OutSafeTask`) move files between the two folders. **Rows from before
are repaired by `MediaListenerWorker`** on the next Downloads resume: a
FINISHED or ERROR `file_safe` row whose file exists outside the safe folder
is moved in (a rename, else copy + delete) and re-pointed — never while the
download engine runs (a vault download in flight has claimed its path in
the safe folder, and a file moved onto it would be truncated by the strategy
opening its output), under a name free on disk AND in the table (an ERROR
row with no file still owns its path), only a file the app can delete
(`canWrite`: a foreign-owned file after a reinstall would be copied and the
copy thrown away on every resume) and only with room for it on the private
volume (`MOVE_SPACE_MARGIN_BYTES`); a file the app cannot see stays where it
is. `StoragePaths.cleanupSabrLeftovers` sweeps the safe folder too: a vault
SABR download's remux temp sits beside its file. **`UrlParser.parseFilePath`
puts its counter on the file NAME, before the extension, a name with no
extension included**: the old whole-path regex found "the last dot"
anywhere in the path, so an extensionless file under
`…/com.solarized.firedown/files/safe/` got its counter inside the package
name, and an extensionless name in a dotless folder came back unchanged,
which spun the engine's collision loop forever. Pinned by the engine
harness (`10c`: a vault start's path is in the stub's safe folder, a regular
one's in the download folder). On-device unverified at write time: the move
of a pre-fix file, playback from the safe folder.
`DownloadPipelineTest` lands a vault download in the safe folder and a
regular one in the download folder on a device; `MediaListenerWorkerTest`
runs the real worker's repair (the move, its `(1)` collision, the untouched
rows, the missing-file mark and heal); `UrlParserFilePathTest` pins the
counter on the JVM.

**"Add to media gallery" is ONE gated door (`utils/GalleryPublisher`), and
every producer of a public media file goes through it.** The switch
(`SETTINGS_GALLERY`, default OFF) has been in Settings since the initial
commit with no caller; the 2026-05 commit that "actually publish[ed]
completed downloads" hung the publish off `DownloadCallback.onFinished()`,
which NO strategy has ever called — each ends with
`onStatusChanged(FINISHED)`, and a user Finish seals the task — so the
switch never did anything (issue #309: downloads in a file manager, in no
gallery or picker). The publish now runs in `DownloadTask.onRunComplete`,
the one place every finished download passes once its file is on disk;
`onFinished()` is kept as the interface's no-op. The gate lives IN the
publisher (`publishIfEnabled` reads the preference; the caller only owns
the vault exclusion — a vault file is never passed in), and a row whose
stored type is not a media type (a CDN's `application/octet-stream`) is
scanned under its file name's type. The other producers call the same
door: the GIF, audio and saved-frame converters, an extracted archive,
Unlock (`OutSafeTask`), a restored backup, a received P2P share (which
used to publish regardless of the switch). `forget` / `forgetAll` — a scan
of paths with no file, ONE request per batch (`deleteDownloads` collects
the removed rows' paths; a scan per row on a select-all delete was seconds
of binder churn) — needs no switch, it only removes a stale row: on delete,
on rename, on Lock (`InSafeTask`) and when the vault repair moves a file.
It is SHARED-storage only (a vault path handed to the scanner is a private
file name in the framework's own logcat lines, which the app's log
stripping cannot reach) and runs only below Android 11: from there the
app's file operations go through MediaProvider's FUSE layer, which updates
the rows itself. **The switch turned on AFTER the downloads were made
publishes them once** (`MediaListenerWorker.backfillGallery`: one `scanFile`
of every finished public media file on the next Downloads resume,
remembered in `GALLERY_BACKFILLED`, reset when the switch goes off) —
without it the reporter's existing files would never appear. **A rename
stays in the file's own folder and never replaces another file**
(`RenameFileDialog`): it used to build the target under the download
folder — a silent no-op for a vault or SD-card file, since rename(2) does
not cross filesystems, and a move into the fallback download folder when
external storage is not mounted — and rename(2) overwrites its target, so
a name another row already carries is refused with `error_file_exists` (a
case-only rename of the same file on a case-folding volume is allowed) and
the picture path follows the file. Two facts to answer a report with: the
"SD card" download location falls back to
`Android/data/<pkg>/files/Download` when no removable root is writable,
which MediaStore never indexes for other apps, whatever the switch says;
and the OS may index raw writes to `Download/` on its own on recent
versions — the switch is what the APP promises, and before this it was an
empty promise. Verify on a device: switch on, download a video, open the
system Files picker from another app. Not verified on a device at write
time.
`GalleryPublisherTest` pins `mediaTypeFor` and, with the switch on, lands a
PNG in MediaStore (skipped where the download folder is not shared storage).

- **Progressive HTTP — `HttpDownloadStrategy`.** Default request sends **no
  Range** (some servers require a range, others reject one). It **reacts to
  partial content**: if the body ends short of `Content-Length` — thrown
  "unexpected end of stream" *or* a clean early EOF — it re-requests
  `Range: bytes=<have>-` and appends until complete (bails on no-progress / a
  resume cap). It **also reacts to a rejected plain GET**: a fresh no-Range
  request answered with **403/404/416** is retried **once** with
  `Range: bytes=0-`, for streaming endpoints that *only* serve ranged requests
  (e.g. a `/play/video/<token>` streaming host — the browser plays it
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
    one streaming host **404**s it, IIS anti-leech 416s it, others 403.
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

### DASH manifests are parsed in Java (`FFmpegXml`) — the fork has no libxml2

FFmpeg's DASH demuxer (`dashdec.c`) reads an MPD as a small DOM, and in the
fork that tree comes from `ffmpegutils/FFmpegXml.parse(ByteBuffer)`, not from
libxml2: the fork's `libavformat/fdxml.c` gives `dashdec.c` libxml2's names
and makes ONE JNI call per manifest — on the probe, the download, and every
live `refresh_manifest`. Native hands the manifest (already fetched through
`FFmpegOkhttp`) as a direct ByteBuffer over its own memory; `parse()` reads it
with `Xml.newPullParser()` (KXmlParser) and returns the tree as one `byte[]`
in the `FDX1` wire format its javadoc documents, which `fdxml.c` rebuilds.
Why, the libxml2 semantics it reproduces, and the build side are in
firedown-ffmpeg's CLAUDE.md ("DASH manifests: XML from Java (fdxml)"). The
app-side rules:

- **Called only from native, by name** — `proguard-rules.pro` keeps the class
  and `parse(ByteBuffer)`. A rename or a dropped keep rule fails no build: R8
  strips it, `FindClass` fails in `fdxml.c`, and every DASH manifest is
  refused (`fdxml: jfields init failed` in a debug log).
- **It never throws back into native code.** Any failure, an
  `OutOfMemoryError` included, is a `null` return, which `dashdec.c` reports
  as invalid data (as it did when libxml2 failed): a pending exception on
  those attached threads is a CheckJNI abort, the `http.c` rule.
- **The wire format and the name rule are shared with `fdxml.c`** — change
  one side, change the other, and run firedown-ffmpeg's
  `sh scripts/dashxml-harness/run.sh` (it compiles THIS file into its JVM side
  and diffs `dashdec.c`'s parse results against libxml2 over ~120 manifests).
  Prefixes are resolved here, with the parser's namespace processing OFF,
  because KXmlParser's namespace mode rejects a whole manifest over an
  undeclared prefix (PlayReady `mspr:pro`) that libxml2 only warns about.
  A DOCTYPE is refused on purpose.
- **Release ordering:** an FFmpeg `.so` built with fdxml needs this class; an
  app carrying it works with either `.so`. Ship the app side first or with
  the `.so` — never a `firedown.ffmpegRelease` pin ahead of it.
- **What exercises it on a device:** the generic catcher's DASH captures
  (`.mpd` URLs and `<MPD` body sniffs, then `FFmpegMuxStrategy`) and RTVE (the
  `rtveCdn` rewrite in `requests.js`). The big sites never reach `dashdec.c`
  — YouTube is SABR, Instagram reads its own MPD, Bilibili emits track pairs,
  Vimeo/Twitch/Kick are HLS — so a DASH regression shows on the long tail
  first.

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

### The download queue is `DownloadEngine`; `RunnableManager` is only its host

The queue — thread pool, active/queued task lists, the `MSG_*` state machine,
every add/restart/finish/delete/cancel — lives in **`DownloadEngine`**, a plain
class with its own handler thread. **`RunnableManager`** is the Android host:
a `dataSync` foreground service that forwards intents (`engine.dispatch`) and
implements `DownloadEngine.Host` — three callbacks, all delivered on the
engine thread: `onForegroundNeeded(safe, regular)` (→ `startForeground` with
the vault/Downloads tap-target split), `onDownloadFinished(task)` (→ the
finished notification), `onIdle()` (→ `stopForeground` +
`stopSelfResult(lastStartId)` — the newest start outranks an idle decided
before it arrived, which a bare `stopSelf()` would take down with the
service). The
split exists so the host MECHANISM can change without rewriting the queue:
the sanctioned escape from the 6 h cap below is a user-initiated data
transfer job (`JobInfo.setUserInitiated(true)`, API 34+, uncapped, the
mechanism Google added for exactly this), which would be a second `Host`
implementation with the FGS kept for < 34. `DownloadTask`/`DownloadContext`
take the engine (and `engine.getContext()`, the application context) — never
the service. Threading contract (in the engine's class doc): one owner
thread for the lists; the two CALLER-thread exceptions are `cancelAll`
(service `onDestroy`) and `sealTasksAsSystemStopped` (the timeout), which
must complete before the service finishes tearing down and so can't wait on
the handler — and the task lists are `CopyOnWriteArrayList` for exactly that
reason: the harness caught a `ConcurrentModificationException` (1 run in 4)
where a download thread unwinding during the timeout seal had the engine
thread `removeIf` the list `cancelAll` was walking. Don't "optimise" them
back to `ArrayList`. Don't add a fourth `Host` callback for something the
engine can decide itself, and don't let notification code creep back into
the engine. **The engine thread is per service INSTANCE, and `onDestroy`
must `shutdown()` it after `cancelAll()`**: the service stops itself at idle
and is recreated on the next download, each instance builds its own engine,
and the `HandlerThread` was never quit — so every start/idle cycle parked
one more thread in `Looper.loop()` for the life of the process.
`quitSafely` drains what is queued (a late MSG_FINISH still recycles) and
ends the loop; the harness's leak sweep (14c) asserts no engine thread
survives, and fails without the quit.

**Four races the stress harness found in the queue, each now closed — don't
reopen them by "simplifying":**
- **No task-object pooling.** `recycleTask` used to offer the task back to a
  pool that the next download `initialize()`d on the same object. A runnable
  of the OLD download can still be unwinding — or, for a queued one the pool
  had just dequeued when delete/finish called `pool.remove()`, only just
  STARTING — and every callback it makes (`onStarted` → MSG_STARTED,
  `onRunComplete` → MSG_FINISH) then lands on a task that belongs to a
  DIFFERENT download: listed twice, or recycled before it ran. A task is an
  entity plus a few atomics; one per download costs nothing.
- **`pool.remove()`'s return value is load-bearing.** A task sits in
  `mActiveTasks` on the submit-time `getActiveCount()` ESTIMATE, so a user
  Finish/delete can hit an "active" task the pool never picked up; when
  `remove()` returns true no thread will ever unwind and send MSG_FINISH, so
  both active branches recycle it on the spot (as the queued branches always
  did). Left alone it was a phantom entry: MSG_STOP never saw empty lists,
  the service never stopped, the foreground notification never cleared —
  and on Android 15 the dataSync timeout killed the process 6 h later. This
  is the most plausible real-world path to the original crash.
- **Restart dedups on the ENTITY id.** `resumeDownloadTaskToExecutor` tested
  `checkTaskExists` against a freshly generated id (never in the lists), so a
  retry tapped while the errored task was still listed ran the same row twice.
- **`DownloadRunnable.run()` neither resets the stop/delete flags nor trusts
  an unexplained interrupt.** It used to zero `stopped`/`deleted` at the top,
  wiping a cancellation that landed before the thread got going (the queued
  case above ran to completion, delete included — recreating the file). And
  the engine's `Thread.interrupt()` aimed at a download can land on a pool
  thread that has already moved on to the NEXT download (it reads
  `getCurrentThread()`, the thread finishes and clears, the interrupt lands
  after): `run()` now clears any flag BEFORE publishing its thread (nothing
  can be aiming at it yet), and an `InterruptedException` with no
  stop/delete pending reports `onError` instead of exiting quietly — the
  quiet exit left a PROGRESS row that never moved, no error, no notification.
  A recycled task's late unwind (`context == null`) also writes nothing:
  it used to resurrect a batch-deleted row as a ghost ERROR entry.

**Deletion is SINGLE-OWNER, and a deleted task's path stays reserved until
its file is actually gone** (found on-device by `DownloadEngineStressTest`
as a FINISHED row with no file, then by the JVM stress as a path reserved
forever). It used to run three things at once: the batch delete (row + file
by PATH, on the disk executor), a second delete from the task's own unwind,
and an immediate release of the path at intent time — so a new download
with the same name could claim the path in between, create its file, and
lose it to the predecessor's late cleanup; and a delete landing on a task
whose thread had ALREADY unwound (MSG_FINISH still queued) had no unwind
left to release the path. Now: `cancelDownloadTask` only flags a LIVE task
(seal ERROR, stop/delete, interrupt) and returns whether the entity is an
orphan; only orphans (finished/errored rows not in the lists — the common
delete) go to the batch, whose completion releases their paths.
`recycleTask` — reached through MSG_FINISH after the unwind, or directly
for one that never ran — deletes a deleted task's row + file ONCE via
`task.deleteRepository(onComplete)` and releases the path in that callback.
`DownloadTask.onRunComplete` writes and deletes nothing for a deleted task.
Two related guards: **user Finish never re-seals** (`finishOneDownload`
skips a task that is already sealed — errored, timed out, deleted, or
finished once — so a Finish tapped on an errored row can't stamp FINISHED
over it), and **Finish on a download that never wrote a byte is
ERROR/`FILE_NOT_FOUND`, not FINISHED** (`finishNeverRan`): a FINISHED row
pointing at no file is what the missing-file sweep flips to exactly that
error later, so say it now and keep the row retryable. The UI offers Finish
on PROGRESS rows only, so this is the submit-time-estimate window and a
resumed row that has not started yet. `sealTasksAsSystemStopped` carries the
same never-re-seal rule as `cancelAll`. The same rule holds at the unwind:
**`DownloadTask.onRunComplete` demotes FINISHED to ERROR/`FILE_NOT_FOUND`
when the file does not exist or is empty** — a Finish landing while the
thread was still in the HTTP connect stopped it before the strategy ever
opened the output (on-device: a FINISHED row with no file whose path a
later, deleted row had reused). A FINISHED-without-file row frees its path
for a successor, which is why **`resumeDownloadTaskToExecutor` re-paths a
restart whose path is meanwhile owned by a live task or another row**
(otherwise the resume appends to a stranger's file); a legit resume keeps
its path — its own partial file and its own row are excluded.

**The QUEUED → PROGRESS transition is `DownloadTask.markRunningIfQueued()`,
synchronized with the seal paths — don't inline it back into the engine.**
The download thread's first breath sends MSG_STARTED and a strategy that
fails at once (an immediate 404) calls `onError` a moment later; the engine
thread used to read QUEUED, lose the race, set PROGRESS over the fresh ERROR
seal and write it LAST — a PROGRESS row that never moved again (stress
harness, seed 7). `onError`/`onStatusChanged`/`sealWithStatus`/
`sealWithError` are `synchronized` on the task for the same reason.

**Verify any engine change with `sh scripts/engine-harness/run.sh`** (the
prompt/sabr-harness pattern: the REAL `DownloadEngine` copied from app/src,
compiled against stubs, JDK only, seconds). `android.os.Handler`/`Looper`/
`HandlerThread` are a REAL queue-backed loop there — the threading contract
is part of what is tested (the host callbacks are asserted to arrive on the
engine thread, `filePathInTasks` to refuse the main thread) — and
`DownloadTask`/`DownloadRunnable` are collaborator stubs mirroring only the
contract the engine relies on (the `sealed` latch, `terminalMessageSent`,
MSG_STARTED from the thread's first breath / MSG_FINISH from its finally),
because the real ones drag in every strategy and Room. It drives: start →
active → foreground → natural finish → finished notification → idle; the
pool bound (POOL active, the next QUEUED, promoted on a finish); user
Finish; delete of an active and of a queued task; restart of an ERROR row; a
strategy error (MSG_ERROR once, no duplicate MSG_FINISH); the FGS-timeout
seal (ERROR + `SYSTEM_TIMEOUT` written synchronously, actives stopped not
deleted, the queued runnable pulled from the pool, and `cancelAll` after it
NOT stamping FINISHED); the vault/regular split; the filename-collision
loop incl. a DB-owned path; the legacy intent shape; and a leak sweep
(every started thread unwound, no handler exception swallowed). 44
assertions. Teeth proven by mutation: an unconditional `sealWithStatus
(FINISHED)` in `cancelAll` fails 8e/8g; a seal that leaves the pool and path
set alone fails 8c/8d. A harness exception is reported as a failure, never a hang — the
pool threads are non-daemon, so a `main()` dying with a blocked download
thread alive would otherwise keep the JVM up forever (it did, once).

**`sh scripts/engine-harness/run.sh stress [seconds] [seed]`** is the
randomized storm on the same real engine (`EngineStress`): four "UI" threads
fire a random mix of start / natural finish / user Finish / delete (1–3 per
intent) / strategy error / restart at it, names drawn from a pool of three so
the collision loop runs constantly, live queue capped at 40; phase A then
drains to rest and checks lists empty, path set empty, idle reported, every
row FINISHED/ERROR/deleted; phase B lands the FGS-timeout seal + `cancelAll`
mid-storm and checks the sealed rows are ERROR/`SYSTEM_TIMEOUT` and nothing
was stamped FINISHED. Both phases: no engine-thread exception, no task listed
twice, every started thread unwound, no two live tasks on one path. The fake
`DownloadRunnable` touches its file on disk at start and the fake repository
answers `findByFilePath` from its own rows — the two production collision
guards, without which the storm reports false path clashes. The seed fixes the
op mix only; scheduling stays nondeterministic, which is the point — run it a
few times with different seeds after any engine change. It is what found the
four races above (first run: 8 failures; each fix removed a distinct symptom
class, traced through the fake task's per-event trace). The fake repository
deletes the file at the entity's path like the real one, so the "path
reserved until the file is gone" contract is exercised, not assumed.

**The on-device counterpart is `DownloadEngineStressTest`**
(`app/src/androidTest/.../manager/`):
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.manager.DownloadEngineStressTest`
(no network needed; `rounds`/`perRound`/`seed` instrumentation args). It
drives the REAL `RunnableManager` with real intents — the JVM harness stubs
`DownloadTask` and the transport, so it proves the queue; this proves the
seams: `HttpDownloadStrategy`, Room, the service lifecycle — against a local
`ServerSocket` byte server that throttles, cuts a third of the connections at
40 % (so the Range resume runs under load) and serves a per-URL salted byte
pattern (so a resume that appended at the wrong offset fails by CONTENT, not
just length). Two threads fire the bursts, then a random Finish/delete/restart
mix; at rest it asserts no row stuck PROGRESS/QUEUED, every non-user-finished
FINISHED file byte-exact at the served size, no ERROR rows (a local server
never fails — an ERROR is a pipeline bug, its type printed), no shared paths,
deleted rows gone, and `RunnableManager.isRunning()` false once idle (the
foreground leak the phantom-task bug caused). Needs `androidx.test:core`
(`ActivityScenario` foregrounds the app so the service may go foreground on
Android 12+). Files land in the real download folder and are deleted through
the service at the end.

**Device tests you can debug from Android Studio.** Every class under
`app/src/androidTest` runs on the debug variant from the IDE (right-click
the class or a method → Run/Debug with a device attached; breakpoints work
in the test AND in the app code it drives, both run in the app's process) or
from the shell with
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`;
the JVM tests under `app/src/test` run with `./gradlew testDebugUnitTest` or
the same right-click. Beside the live, stress and audit tests above:
`manager/DownloadIntentsTest` (no service runs — `startService` is
intercepted and the Intent read in place), `manager/DownloadPipelineTest`
(three downloads through the real service against the stress test's
`ByteServer`: a vault one, a regular one, an oversized `data:` URL),
`data/workers/MediaListenerWorkerTest` (the real worker through
WorkManager's `TestListenableWorkerBuilder`, the repository handed to its
constructor as Hilt would; it asserts the engine is idle, since the worker
moves nothing while a download runs), `data/DownloadDatabaseMigrationTest`
(`MigrationTestHelper` on its own database name, built from the exported
schemas that `sourceSets.androidTest.assets` adds — a schema bump needs its
JSON exported before the test can build that version),
`utils/GalleryPublisherTest`, `data/entity/ContextElementEntityTest`,
`sabr/SabrInlineMuxDeviceTest` (the inline fMP4 muxer's output read by the
device's media stack and the app's player; needs no network, plays ~8 s
of tone at zero volume), `geckoview/media/MediaNotificationTest` (the
playback notification's title and artwork from a page's MediaSession;
needs no network, boots the real runtime, plays a near-silent tone for
~15 s). JVM:
`utils/DataUrisTest`, `utils/BrowserContextActionsTest`,
`manager/UrlParserFilePathTest`. The pure-Java classes were run under a JDK
when written; the device classes were parse-checked only (no SDK on the box
that wrote them) and have ALL since run green on a device (SM-A426B,
Android 12; `SabrInlineMuxDeviceTest` and `MediaNotificationTest` each
found one real bug on their first run, see their sections). A new device
test is still unverified until it has run on a phone.

### The download service is a `dataSync` FGS — Android 15 times it out at 6 h

`RunnableManager` runs foreground as `dataSync`, which Android 15 (API 35)
caps at a **cumulative 6 hours per 24-hour window** (the timer resets when the
user brings the app to the foreground). At the cap the system calls
`Service.onTimeout(int, int)` **on the main thread** and gives a few seconds to
leave the foreground; miss it and the process dies with
`RemoteServiceException$ForegroundServiceDidNotStopInTimeException` — a shipped
crash. The base `onTimeout` is EMPTY, so not overriding it is not a no-op, it
is the crash. Two paths reach the cap: a genuinely long transfer, and a task
that never reported completion (nothing sends `MSG_STOP`, so the service sits
foreground with an "ongoing" notification forever).

Invariants in `RunnableManager.stopForForegroundLimit()`, each load-bearing:

- **Seal the in-flight tasks BEFORE `stopSelf()`, synchronously**
  (`DownloadEngine.sealTasksAsSystemStopped`). `stopSelf` → `onDestroy` →
  `engine.cancelAll`, which seals whatever is left as **FINISHED** — a
  partial file wearing a FINISHED row, which nothing offers to resume.
  Posting the seal to the engine thread would race that, so it runs on the
  timeout's own thread (every step is a field write or a non-blocking flag
  flip; `repository.add` snapshots onto the disk executor). `cancelAll`
  additionally skips tasks that are already sealed, so it can never stamp
  FINISHED over a deliberate terminal status.
- **The seal is ERROR + `MessageHelper.SYSTEM_TIMEOUT`, never FINISHED** — the
  bytes on disk are a prefix, the restart path resumes from them, and the row
  states the real cause (`error_system_timeout`) instead of a generic I/O
  error. No notification is posted: an errored download has never notified,
  only a finished one does.
- **`startForeground` is guarded, and `mForegroundWithdrawn` latches.** Once
  the quota is spent, a *new* `startForeground` throws
  `ForegroundServiceStartNotAllowedException` until the user next foregrounds
  the app — so a handler message landing after the timeout must not swap one
  crash for another. The catch runs the same teardown rather than letting the
  process die. The flag is per service INSTANCE on purpose: the user's retry
  foregrounds the app, which resets the quota, and the restart gets a fresh
  service.

`TaskManager` declares the same type but never calls `startForeground`, so it
is not subject to this; WorkManager's `SystemForegroundService` (the Backup
workers) handles its own timeout since 2.10.

### The task service (`TaskManager`) — foreground while a task runs, one worker for the process

`manager/tasks/TaskManager` runs the media and vault tasks (GIF, audio, frame,
archive, extract, vault lock and unlock), one at a time. It was a plain
started service, and that shipped as "a GIF stuck at 99% that neither Cancel
nor Back → Stop can stop": Android stops a plain background service about
60 s after its app leaves the screen, and caches and freezes the process.
The task's HandlerThread outlived the stop, so the task kept running inside
a destroyed service; the Cancel intent then CREATED a new TaskManager, with
its own thread and no running task, and did nothing. The invariants:
- **One worker thread for the process** (`sWorker`, never quit) and the
  running task in a static (`sCurrentRunnable`), so a cancel reaches the
  task whatever instance Android delivers it to. `onDestroy` stops a task
  its instance still had running instead of orphaning it.
- **Foreground while a task runs**, through `ServiceCompat.startForeground`
  with a progress notification (`NotificationID.TASK_ID`) and a Cancel
  (`ACTION_CANCEL_CURRENT`), plus a PARTIAL wake lock for the run (the
  screen-off case). The start comes from the user's tap, so the app is in
  the foreground and Android 12's background-start ban does not apply; a
  refused `startForeground` leaves the task running as a background service,
  as before. Type `mediaProcessing` (Android 15+, its own 6 h budget) for
  GIF/audio/frame, `dataSync` otherwise; `onTimeout` (both overloads) stops
  the task and leaves. Re-posts go through `startForeground`, which needs
  no POST_NOTIFICATIONS grant to SUCCEED; without the grant the service is
  still foreground but its notification never reaches the shade (only the
  system's list of active apps). Don't go back to a plain service.
- **The notification is the other ongoing notifications' shape**: what is
  happening as the title (`task_notification_*`, "Creating GIF" — the
  in-app "…, please wait" strings read oddly in the shade) and the FILE
  under it, like "Backing up" + the name. Two rules, each a mutant in the
  harness: the line is computed ONCE, when the start is accepted
  (`Job.notificationText`), because tasks rename the entities they are
  handed (a GIF's row becomes the `.gif`, and the line followed it
  mid-task); and a Safe Folder file's name never goes to the shade or the
  lock screen — the vault's own tasks and a task on a vault file show
  "1 file" / "3 files" (the background-playback rule).
- **Notifications off is said, once, and has a door.** The first-run
  priming sheet asks once, and a denied or skipped POST_NOTIFICATIONS is
  never asked again by Android, so a long task's notification can be
  invisible. `utils/AppNotifications` is the one place for it: `enabled`
  (the app's switch or the Android 13 grant, and the Downloads channel not
  turned off), `openSettings` (the Downloads channel's page when only it is
  off, else the app's page, whose switch grants the permission; then app
  info), and `claimTaskHint` (once per install, `backup_local.xml`). The
  Downloads screen's `handleTaskStart` shows "Turn on notifications…" with
  a Settings action the first time a task starts with them off (not for a
  frame grab), and Settings → General → Notifications opens the system
  page with the live state as its summary. The old post-denial dialog
  (`NotificationsManagerDialog`) was registered and never opened; it is
  gone.
- **Each start carries its own inputs** (`Job`: the entity list, the GIF
  args, the frame position). They lived in service fields every start
  overwrote at once on the main thread while the running task read them.
  The task runs inside `handleMessage`, never `post()`ed (a posted task ran
  after a queued start, when "current task" already named the next one).
- **The service stops itself, the tasks never do** (`onJobEnded` →
  `stopSelfResult(latest startId)` under `mLifecycleLock`); tasks used to
  call `stopSelf()`, which ended the service under a queued start.
- **A cancel before the task begins is kept**: `TaskRunnable.run()` no
  longer resets the stopped flag, `jni_gifmaker_start` no longer clears the
  native interrupt (it returns `-ERROR_INTERRUPTED` at once), and a task's
  `stop()` null-checks its native object (`AudioEncodeTask` NPE'd on a
  Cancel before the encoder existed). `FFmpegGifMaker`/`FFmpegEncoder`
  serialize `interrupt()` with `free()` (native interrupt reads the struct
  pointer then writes through it: a free between was a use-after-free).
- **One task at a time, said in the UI**: `TaskRepository.isTaskInFlight()`
  (the service's own count, process-wide) gates every start in
  `BaseDownloadFragment` (`refuseWhileTaskRuns`, the `task_busy` snackbar);
  a start that slips in first is queued behind it safely. Never gate on
  `mOperationActive`: task events are a `SingleLiveEvent`, delivered to ONE
  observer, so a task that finishes while the Safe Folder screen observes
  leaves the Downloads fragment's flag set.
- **Only vault lock/unlock locks the list** (`mListLocked`, `locksList`):
  those move rows out of it. The dim layer is CLICKABLE and hides the list
  from TalkBack and keyboard focus — it used to only paint, and taps went
  through it to the rows. GIF/audio/frame/archive tasks leave the list live
  (they only add a file); the bottom bar reports them, and the list's
  bottom padding grows by the bar's height while it shows
  (`RecyclerPadding.setCoverBottom`, `BottomProgressView.OnCoverChangedListener`).
- **Every task event reaches the UI**: `TaskRepository.sendEvent` posts
  each event to the main thread on its own (`Handler.post` + `setValue`).
  It was `postValue`, which keeps only the newest value until main runs:
  a Started followed by a Progress while main was busy arrived as the
  Progress alone (no bottom bar, no Cancel), and a task's Finished followed
  by the next task's Started lost the first result (18% of results lost in
  the stress run).
- **A task's own Cancel stops only its kind of task** (`cancels` /
  `startActionFor`); the notification's `ACTION_CANCEL_CURRENT` stops
  whatever runs. The vault bars' Cancel sent `START_ENCRYPTION` /
  `START_DECRYPTION`: the first went to the download service and cancelled
  nothing, the second queued another unlock of `[null]`, which crashed the
  app when the first ended. They send `CANCEL_*` now, and `entitiesOf`
  drops null entries (an exception on the worker ends the process).
- **The vault tasks count what they moved**, file by file (`copyFile` on
  `TaskRunnable`: both streams closed on every path, the partial output
  deleted unless the copy finished). The count read every entity's status
  at the end, and finished downloads arrive FINISHED, so a cancel or an
  error reported files that never moved; a cancel left two descriptors
  open; a read error left a partial file with no row (in the PUBLIC folder
  for an unlock) and abandoned the rest of the batch. A file that fails is
  skipped, the rest still move.
- **The GIF and audio tasks look at the stop flag again right before the
  native start**: `stop()` sets the flag, then interrupts the native object
  if it exists, so a cancel while it was being built (native init, the
  first library load) interrupted nothing and the whole encode ran.
- **A new instance goes foreground at once even while an old one's task
  unwinds** (`running.service != this`): that instance's foreground went
  with it, and asking only from the worker, later, is refused once the user
  has left.
- Verify with `sh scripts/task-harness/run.sh` (the real TaskManager,
  TaskRunnable, TaskRepository and every task on a JVM, against stubs that
  model the service lifecycle, foreground refusal and LiveData delivery;
  703 checks, ~60 s; `stress [sec] [seed]` for the randomized run). 19 of
  its checks failed on the code before these fixes; six mutants (the
  static current task as an instance field, getters reading the newest
  start, `run()` resetting the stop flag, `AudioEncodeTask.stop()` without
  its null check, a Safe Folder name in the notification, the file line
  read at every re-post) must each fail it. Residuals it reports as INFO: a start
  queued behind a task whose instance the system destroyed (or that hit
  `onTimeout`) is dropped with no event; a frame grab cannot be
  interrupted, so its cancel waits out the grab.

### GIF maker (`gifmaker.c`) — one pass for short clips, palette first for long ones

The one-pass graph (`fps,scale,split → palettegen + paletteuse`) holds EVERY
sampled frame as RGB32 at the output size until the source ends: palettegen
emits its palette only at EOF and paletteuse waits for it. An 8:51 portrait
clip at 320 px is ~6,400 frames, ~4.6 GB, then one encode burst of every
frame at once (the device report: a GIF "stuck at 99%", ART blocking on
NativeAlloc GCs). Now `gifmaker_wants_streaming` keeps the one pass only
while the frames fit `BUFFERED_FRAMES_BUDGET_BYTES` (128 MB, ~15 s of a
portrait clip, ~48 s of a landscape one) — byte-identical output to before —
and past it builds the palette FIRST (`gifmaker_build_palette`: a second
open of the file, up to `MAX_PALETTE_SAMPLES` keyframes spread over the
range decoded on their own, every frame sampled by time when the range has
fewer than `MIN_PALETTE_SAMPLES` keyframes), then streams the frames through
`paletteuse` with that palette from a second buffer source (`[pal]`;
paletteuse holds its palette input forever, `before = after =
EXT_INFINITY`). Measured on the harness (x86, 576×1024 H.264 at 4 Mbit/s):
60 s → 678 MB / 35 s before, 68 MB / 25 s after, same frame count, PSNR
within 0.01 dB of the one-pass GIF against the source; the 8:51 clip
streams in ~135 MB. What remains of memory is paletteuse's own colour cache
(one entry per distinct colour it sees, never evicted, capped by the colour
space at ~130 MB, far less on real video). Also fixed: the trailer is
written on every exit once the header is out (the gif muxer holds back the
last packet and frees it only in `write_trailer` — a GIF cancelled after
its first frame leaked that frame), and the burst's progress counts from
the first filtered frame (it counted from 0, which added the range start
again and pinned the bar at 100% for any range not at 0). Where the time goes now (60 s clip):
`sierra2_4a` error-diffusion dithering ~45%, decode + scale ~35%, the GIF
encoder ~20%. **A GIF is at most 30 s** (`FFmpegGifMaker.MAX_DURATION_MS`): every
sampled frame is a whole frame in the file, so length is size (a whole
9-minute video was ~640 MB and minutes of encoding, from a trim that
defaulted to the whole video). The trim starts at the first 30 s and
`FilmstripTrimSlider.setMaxTrimMs` keeps the range under it by CARRYING the
other handle (the end dragged right moves the range later, the start
dragged left earlier): a plain clamp would leave a long video's later part
unreachable, the start stopped by the end and the end by the limit. The
range label adds "max 0:30" on a longer video. **A video longer than two
limits shows as a 60 s WINDOW, not whole** (`WINDOW_MAX_RANGES`): on a
whole 9-minute strip the 30 s range was 6% of it, the two handles inside
one touch target. The range is then half the strip; dragging inside it
moves it, dragging the film outside it scrolls (with fling), a tap on the
film puts the range there, and the range or a handle held near an edge
rides the window through the video (never a handle pinned against the
other: the pair would leave the screen under the finger). A 3dp bar along
the bottom shows the window and the range in the whole video. A shorter
video is shown whole, as before. The strip's thumbnails are SLOTS over the
whole video (about one per 64dp of a window, at most 120), and the view
asks for the ones on screen plus half a window each side
(`OnThumbnailsNeededListener`): the fragment's one worker takes the next
untried slot of the latest ask, nearest its middle, so a scroll is answered
first and a long video is never decoded whole. Three
MediaMetadataRetriever misses with no hit switch every slot to one FFmpeg
frame, as before. Verify the view with `sh scripts/filmstrip-harness/run.sh`
(the real class against stubbed Android views: the window, scroll, fling,
tap, edge rides, the pinned handle, a storm of random gestures on eleven
lengths checking range, window, cells and asked slots; four mutants).
`GifMakerTask` caps any
other caller (`cappedEndMs`, an unset end included), and
`gifmaker_range_us` treats an end past the video as its end, so a capped
request on a short clip still fills the bar. Verify with
`sh scripts/gifmaker-harness/run.sh` (the real file on a host build of the
pinned FFmpeg: one-pass, an end past the video, streaming, cancel latency
in each pass, a cancel before start, AddressSanitizer + LeakSanitizer on
every exit path, a mutant for each fix).

### Capture-layer headers & cookies (how a re-download authenticates)

A captured media URL is re-fetched later by the native downloader, so it must
carry the headers + cookies the browser's original request had or the CDN 403s
it. How each is obtained (current architecture):

- **Request headers — `webRequest` is the backbone.** `requests.js` listens on
  `onSendHeaders` (with `['requestHeaders']`) + `onHeadersReceived` and **caches
  the request headers keyed by URL within the requesting TAB's `TabState`**
  (`cacheHeaders(tabId, …)` / `getCachedHeaders(tabId, url)`). When a
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
  jar on the native side's request, not scraped from the page. **Which jar:**
  the message carries the tab's `incognito` (from `GeckoState`, via
  `setCookieContext`), mapped to `storeId` `firefox-private` /
  `firefox-default` — without it `getAll` reads the DEFAULT jar, so a
  long-press download from a private tab carried the regular session's
  cookies. Parsers that attach cookies to an emit use `cookieQueryForTab`
  (`common.js`) for the same reason.
- For headers on **content-script-discovered** URLs (next section), see the
  `HEAD`-probe backfill there.

The captured header set + cookie are reused for the whole stream — for HLS/DASH,
ffmpeg propagates them to every sub-request (master/playlist/segment/key); see
"Per-site request quirks" below.

**The cookie is PERSISTED inside `file_headers` for resume/retry, and it goes
through the same map round-trip the reader uses — `DownloadTask.withCookie` /
`cookieFrom`.** The persisted header string is `Utils.mapToString`'s
`k=v&k=v` URL-encoded form (`DownloadContext` and `Utils.stringToMap` split on
`&`/`=` and decode). It used to be appended as `"\r\nCookie=" + rawCookie`,
which on the way back made the last pre-cookie header's VALUE
`"...\r\nCookie"` (a CR/LF in a header value — OkHttp refuses to build the
request) and truncated the cookie at its first `=` (`arl` for a Deezer
session). So every cookie-gated download lost its cookie on exactly the
resume it was persisted for; the first run never noticed because it takes
the cookie separately through `buildContext`. `DownloadTask.resume` also
hands it back as `request.cookieHeader` — `DeezerStrategy` reads THAT (the
session cookie is its re-mint input), not the context map — and the strategy
falls back to the context's `Cookie` for belt and braces. Verified by
round-tripping the real `Utils` methods on a JVM; a Deezer retry after an
ERROR row is the on-device check.

### Three capture sources — wire, DOM, inject (webRequest is NOT enough alone)

The generic catcher has **two** sources, because `webRequest` alone misses media:

1. **Wire — `webRequest`** (`requests.js`): media fetched over HTTP. The backbone,
   and the only source that gets request headers + cookies "for free". But it
   **only fires for requests it actually observes** — it misses media that loaded
   from cache, before the listener attached, or that lives in a DOM attribute
   without a fresh request.
2. **DOM — content script** (`content-script.js`): scrapes the page for media the
   wire never showed. It reports `<img>` / `<source>` `src`/`srcset`, **inline
   `style` background-images** (`reportBgImage` — the Google-Maps class: an
   app-like gallery renders each photo as `<div style="background-image:
   url(…)">` with an EXTENSIONLESS CDN URL (`lh3.googleusercontent.com/p/…=w426`),
   so no `<img>` exists and a SW/cache-served copy never crosses webRequest;
   INLINE style only, by design — computed style would cost getComputedStyle
   per element, and a class-styled background is a design asset, not content)
   **and `<video>`/`<audio>` `src`/`currentSrc`** URLs (with a
   `MutationObserver` on `src`/`srcset`/`style` for dynamically-added ones) to
   the background, **passively
   scrapes embedded media URLs from the page source** (see "Passive
   embedded-media scrape" below — this is what captures a video before the user
   presses play), and **separately scrapes JSON-LD `VideoObject` + `og:`/
   `twitter:` metadata** to enrich captures with accurate title/description (on
   video SPAs the `<title>`/`og:title` are usually the generic site name, so
   JSON-LD/`og:video:title` rank higher). A DOM-discovered URL has **no cached
   headers**, so `requests.js` does a `HEAD` `fetch(url, {credentials:'include',
   referrer: tab.url})` to populate the tab's header cache via `onSendHeaders`
   (the probe carries no tab of its own, so `probeTargets` files it under the
   asking tab), then
   forwards the (sanitized) result through the same emit path. So: content script
   *finds* it, the HEAD probe *authenticates* it. **Except a URL a blocker
   refused**: when the page's own request for it ended in `onErrorOccurred` with
   `NS_ERROR_ABORT` (uBlock's cancel; a page abort is `NS_BINDING_ABORTED` and
   doesn't count) or a URL-classifier `*_URI` error (tracking protection), the
   tab's `blocked` set records it and the scrape skips it — no probe, no
   forward. The probe runs in extension context, which Firefox hides from
   every other extension's webRequest, so uBlock can't stop it, and a forward
   makes the native capture probe fetch it again: on-device, TikTok's
   `monitor/collect` telemetry beacon was refused by uBlock and then requested
   twice by us, cookies included. Residuals: a blocked URL the page hasn't
   requested YET (lazy, below the fold) and uBlock `$redirect` neutering (no
   error fires) still probe — closing those means asking uBlock's filter
   engine, outside the bundle's patch set. Pinned by the smoke's `blocked:`
   section.
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
until the user presses play. Example: a news page that ships the mp4 only
inside `window.dataLayer.push({video:{url:"…mp4"}})` — no `<source>` element, no
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
  won't match). Handles escaped JSON slashes (`https:\/\/…`), `\uXXXX`
  escapes (Vimeo's inline config writes every `&` as `\u0026`; left in, the
  URL matched neither the parser's claim nor the wire's copy) and
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

### Click-to-load player placeholders (capture before the tap)

A page often shows a clip as a picture with a play button and loads the
player iframe only on a TAP, or holds a lazy iframe that loads its player only
when scrolled into view. The shape: a carousel of Dailymotion clips, each an
`<a data-embed-url="https://geo.dailymotion.com/player/<cfg>.html?video=<id>">`
around a `dmcdn.net` poster. Until then no player exists, so the wire, the
bridge and the DOM scrape see nothing. This is NOT a "paste a link" path: the
page is loaded in a tab and the placeholder is part of it; this only moves the
capture from the tap (or the scroll) to the page load. The split follows the
cardinal rule:

- **Content script — generic, any host** (`reportPlaceholders`). It reports
  the player URL each placeholder DECLARES, as `kind: "player-placeholders"`,
  at DOMContentLoaded/load and again (debounced 500 ms) when nodes are added;
  each URL once per document PAGE (the set resets when an SPA moves to
  another URL), 24 in all, 2 KB a URL. Two kinds, the explicit ones FIRST so a
  page's lazy iframes can never crowd them out of the budget:
  - explicit facades: `[data-embed-url]`, `<lite-vimeo videoid
    [videohash]>` (turned into `player.vimeo.com/video/<id>[?h=]`),
    `<lite-youtube videoid>` and WP Rocket's `.rll-youtube-player[data-id]`
    (turned into `youtube.com/embed/<id>`), and **the player's iframe kept
    as HTML in an attribute** (`html`, `data-html`, `data-embed`,
    `data-embed-html`, `data-iframe` — a web component that writes the
    iframe into the card on the tap; the `src`/`data-src` it names is the
    URL when it looks like a player's, 8 KB of HTML read at most, the
    consent markers checked since a consent blocker stores an embed the
    same way);
  - lazy iframes: `iframe[data-src]` (lazysizes, lozad, a3) and
    `iframe[data-lazy-src]` (WP Rocket) whose `src` is not loaded yet
    (absent, empty, `about:blank`, a `data:` placeholder — a3 puts a gif
    there), and only when the URL looks like a player (`embed`, `player`,
    `video`, `watch`, `clip`, `podcast`, `episode` in host+path): lazy AD
    slots use the same attribute.
- **Consent-gated embeds are NEVER read.** A consent tool's placeholder is
  the site holding a provider back until the user agrees to it; fetching the
  provider's config ourselves would contact it anyway. So the consent tools'
  own attributes (`data-cookieblock-src`, `data-src-cmplz`, `uc-src`,
  `suppressedsrc`…) are not in the selector, and a lazy iframe carrying a
  consent marker (Klaro `data-name`, OneTrust `optanon-category-*`, Cookiebot
  `data-cookieconsent`, Complianz, Didomi `data-vendor`, iubenda,
  Usercentrics, CookieYes, Borlabs) is skipped. The markers are checked on
  lazy iframes only: generic names like `data-category` can sit on a site's
  own card. The same reasoning keeps X embeds out (a `blockquote.twitter-tweet`
  that never rendered was blocked by a consent tool or by Strict tracking
  protection).
- **Background — one registry, one queue** (`common.js`).
  `registerPlaceholderHandler(fn)` (the SPA registry's shape:
  `(url, details) => boolean`, true = "mine", host test first); the router
  offers each URL to the handlers until one claims it. A handler never
  fetches on the spot: it calls `queuePlaceholderCapture(details, clipKey,
  capture)`, which owns every rule a site needs, so a new site cannot get
  them wrong — one claim per (tab, PAGE, clip), at most 12 captures per (tab,
  page) counted through `keys()` (`ClaimSet.size` counts expired entries);
  at most 3 captures at once and never two of one tab (a stalled site holds
  its own tab, not every tab — one global chain gave 23 s per clip of
  head-of-line blocking); a bounded queue (96); a capture runs only while
  the tab is on the reporting page and is handed `stillOnPage()` to ask again
  RIGHT BEFORE it emits (Java stamps a capture with the tab's page at
  arrival); "same page" is the URL without its fragment or a query
  refinement of it; and a capture that did not happen RELEASES its claim, so
  a reload or a return tries again (a claim kept for its TTL turned one
  network blip into thirty minutes without the clip).
- **Handlers today, each reusing its parser's own lookup and emit, every own
  fetch anonymous (`credentials: "omit"`) and time-bounded:**
  Dailymotion (`geo…/player/…?video=`, `/embed/video/`, `dai.ly`) fetches
  the embed config `geo.dailymotion.com/videos/<id>?embedder=<page
  origin>/` and feeds the EMBED path (`/details` title, signed master,
  per-tab emit claim, released if the emit is skipped); Vimeo
  (`player.vimeo.com/video/<id>[?h=]`, `vimeo.com/<id>[/<hash>]`,
  `/channels/<c>/<id>`) the player page through `captureVimeoConfig`, the
  listener's own reader; Acast (`embed.acast.com/[$/]<show>/<episode>`) the
  feeder lookup + emit claim; Apple Podcasts
  (`embed.podcasts.apple.com/…/id<show>?i=<episode>`) the iTunes lookup,
  under the episode's podcasts.apple.com page; Rumble
  (`rumble.com/embed/[<x>.]<id>`) the embedJS JSON; Spotify TRACK/EPISODE
  embeds only (a lazy playlist would put a hundred previews in the sheet);
  **YouTube** (`js/parsers/youtube-facade.js`: `youtube.com` /
  `youtube-nocookie.com` `/embed/`, `/shorts/`, `/live/`, `/v/`, `/e/`,
  `/watch?v=`, `youtu.be/<id>`; a playlist embed names no video) is the
  one handler whose capture is ANOTHER extension's — see the YouTube
  section's facade paragraph: it hands `{kind:"youtube-facade", videoId,
  tabId, pageUrl, embedUrl}` to `youtube@solarized.dev` through
  `runtime.sendMessage(extensionId, …)` after `stillOnPage()`, keeps the
  claim on a `true` reply (emitted, or already owned in that tab), releases
  it on `false` (no streams) and keeps it when no reply comes in 45 s.
  A later tap is quiet: each parser's own per-tab claim or origin dedup sees
  the clip. Show-only, playlist or channel embeds name no single clip and
  are not read. An own fetch is marked with `markOwnRequest` only when a
  listener of that parser will see it (Spotify's watches frames, so its XHR
  is NOT marked: an unconsumed prefix mark would swallow the embed's load).
  The embedder is the page's ORIGIN (an `about:srcdoc` frame's is the tab's),
  never the article URL: the page's own poster requests already carry it.
  Vimeo's origin fallback is the canonical `vimeo.com/<id>` now (an unlisted
  embed's config has no `video.url`, and the embedding page as origin
  collapsed two such embeds into one capture).
- **To add a site**: a handler in its module (host test, `clipKey`
  `<site>:<id>`, the lookup the player would make, anonymous, bounded,
  `stillOnPage()` before the emit, resolve true/false), its block-list rule
  as usual, and a `placeholder` check in its replay. Candidates left out on
  purpose: Instagram/Facebook/Threads (login-walled, credentialed only),
  TikTok (signed requests), niconico (single-use key), Twitch VOD/live (the
  usher rendezvous needs the player), Bluesky/Telegram (low prevalence),
  JW/Brightcove/Wistia (no parser; account ids).

Verified by `dailymotion-replay.mjs` section 9 (both URL shapes, titles,
anonymous fetches, origin embedder, the tap staying quiet, a second tab, the
per-PAGE budget, a failed fetch retried, a tab moving during the fetches, a
stalled tab not holding another, a query refinement; mutants — one global
chain, a per-tab budget, no re-check before the emit, no claim release, no
query refinement — each fail), `parsers-replay.mjs` (Vimeo, Acast, Apple,
Rumble, Spotify placeholders), and `placeholder-harness.mjs` (the real
content script in Chromium: carousels, lazy shapes, consent gating, ad slots,
the crowd budget, SPA pages; mutants — no consent gate, no player shape,
lazy first, a `data:` src as loaded — each fail). Replaying such a HAR
(`har-replay.mjs --background`), the real page reports every card ~0.7 s in
and the real background fetches each config. The live provider answers were
not reachable from the machine that built it; on-device
unverified at write time. Residuals: a placeholder that names its video only
in script state or a click handler (Embed Privacy keeps the iframe in a
page-world global; Borlabs v2 in a template), a domain-restricted Vimeo embed
(it validates the Referer an extension fetch cannot send; the tap still
captures). Vimeo's block rule and wire backbone came after: see "Vimeo —
the player's own config" above.

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
dedicated parser — e.g. a live-replay `master_dynamic_*.m3u8`),
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

**This did NOT make the `downloader_read` type-match remap redundant — keep
it.** It's tempting to think the Kick/Twitch remap and this cross-program
corruption were one bug (both fire the same `re-bind (discontinuity remap)`
log), but they're distinct root causes and the remap fixes one the audio
selection can't touch:
- The remap follows **MPEG-TS AVStream index renumbering/reuse across an
  `EXT-X-DISCONTINUITY` WITHIN a single rendition** (a Kick preroll ad on pid
  0x101, then main content reusing index 1 for *video* → a frozen index map
  feeds H264 into the audio slot → `aac_adtstoasc` abort). Stream
  **selection** picks indices once at find time; it fundamentally **cannot**
  follow an index that gets reused mid-download — only matching by codec
  **type** + re-bind can.
- Twitch/Kick go through `processHlsMaster` (skipProbe) and download a **single
  child rendition = one program**, so they **never run** `getRelatedAudioNumber`
  and never had the cross-program audio bug. Their need for the remap is the
  within-rendition discontinuity above, unchanged by this fix.
- The cross-program case only *looked* like the Kick bug: the remap was the
  victim, fed **two concurrent programs** by the mis-paired audio. Fixing
  selection removes the second program from its input; the remap then
  (correctly) follows only genuine within-rendition discontinuities in the
  selected rendition. So both the audio-selection fix AND the remap are
  load-bearing, for different scenarios.

**The remap itself is program-gated for a multi-program master input — the
green-pixel bug.** The remap's own assumption ("a multi-resolution ABR master
is never one input here, each rendition is its own playlist and we download
one") is **false** when the generic catcher captures the HLS **master** of a
parser-less site: the download input is then the whole ABR ladder in ONE
`AVFormatContext`, N renditions each in its own `AVProgram`. ffmpeg delivers
packets from MORE playlists than the one we discarded down to (a video-only
CMAF master = 6 programs `Stream #0:0..#0:5`, 1080→220, no audio), and a bare
codec-**type** remap re-binds the single video capture slot to **every**
rendition in turn (`re-bind 0->1->2->3->4->5->0…`), muxing six resolutions
into one track → `Invalid NAL unit size` / `missing picture` / `Packet
corrupt` / a `clamping dts` storm → an **unplayable green file**. Fix
(`downloader_read`, mirrors the audio program-affinity above): when
`fmt_ctx->nb_programs > 1`, accept a packet only if its stream shares the
`AVProgram` of the stream we actually selected
(`av_find_program_from_stream`); a packet from another program is a different
rendition we discarded — drop it. Scoped to `nb_programs > 1`, so a single
child rendition (Twitch/Kick via `processHlsMaster`), a separate-audio
DASH/YouTube input, and progressive files are untouched and keep following the
**within-rendition** TS renumber above (the two are distinct and both
load-bearing). Known remaining limit: ffmpeg still *reads* the discarded
sibling playlists (the per-stream `AVDISCARD_ALL` isn't fully honored by the
hls demuxer for this master shape), so the download fetches the other
renditions' bytes too — wasteful but harmless now that their packets are
dropped instead of muxed; a future hls.c discard fix in the fork could avoid
the extra fetches. The proper long-term cure is a site **parser** that emits
one chosen rendition (a single child playlist = one program), which sidesteps
the master-as-input entirely — same reasoning as every other parser-owned
site.

### AAC "Main" mislabel — the muxer relabels the config as LC (Android can't decode Main)

A downloaded MP4 that every desktop player opens but that the app's player
refuses with "This device can't decode this video's format" — error code
`ERROR_CODE_DECODING_FAILED`, cause `Decoder failed: c2.android.aac.decoder`
+ a bare `CodecException 0x80000000`, audio format `mp4a.40.1` — is the
**AAC Main profile mislabel**. Some streaming encoders write the ADTS
`profile` field as 0 (= AudioObjectType 1, "Main") for a plain LC / HE-AAC
stream; `aac_adtstoasc` copies it faithfully into the AudioSpecificConfig
(`0b88` = Main, 22050 Hz, mono on the Pixel-10 case; LC would be `1388`), so
the mp4 declares a profile Android's AAC decoder (FDK) does NOT implement,
and it fails the first frame. FFmpeg decodes Main (a superset it implements,
SBR/PS sniffed from the frames), which is why the capture probe, the
thumbnail and ffprobe on a PC all look fine — ffprobe even reports "HE-AAC"
and only hints at it with "Parametric Stereo signaled to be not-present but
was found in the bitstream". Real AAC Main streams have not been produced in
~two decades; every one seen is a mislabelled LC, so `downloader.c` relabels
UNCONDITIONALLY, changing only the five object-type bits (sample-rate index,
channel config and any PCE stay as derived, which keeps implicit SBR/PS
signalling intact). Two entry points, because the config has two sources:

- **Container-sourced** (fMP4 / DASH / MP4 input carries its own ASC):
  `downloader_aac_fix_codecpar` on the output stream right after
  `avcodec_parameters_copy` in `downloader_init_output_streams`.
- **ADTS-sourced** (MPEG-TS / HLS / raw `.aac`, the common case): the config
  does not exist until `aac_adtstoasc` derives it from the first ADTS
  header, and when the mp4 muxer auto-inserts that filter the config travels
  filter→muxer as `AV_PKT_DATA_NEW_EXTRADATA` side data the app never sees.
  So the mux thread runs the filter ITSELF (`downloader_aac_filter_packet`,
  per capture slot, decided on the first packet by the ADTS sync word; the
  packets then reach movenc already raw, so it inserts nothing) and patches
  the config in BOTH places movenc can read it from — the first packet's
  side data (what becomes the `esds`) and the filter's `par_out`. Any setup
  failure falls back to the muxer's own filter, i.e. today's behaviour.
  Verified on a host ffmpeg: an ADTS stream with its profile bits relabelled
  to Main remuxes to exactly the `0b88` config from the device report, and
  the FFmpeg 8.0 source confirms the filter emits the config as first-packet
  side data (`object_type = profile + 1`) and movenc adopts that side data
  when the track had no config at init.

The **Copy** action on the player's error snackbar (`PlaybackDebugInfo`) is
what surfaced this — the hex init data is the AudioSpecificConfig, and its
first five bits name the profile on sight. Files downloaded BEFORE the fix
keep their Main config (nothing rewrites finished files); re-download, or
patch the one byte on a PC.

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

## Built-in translator — Gecko's Firefox Translations, wired, not built

Page translation is **GeckoView's own engine** (Bergamot running on-device in
WASM; toolkit `translations` component + the bundled CLD2 language detector),
exposed through `org.mozilla.geckoview.TranslationsController`. The
firedown-geckoview build ships it untouched: `--enable-geckoview-lite` strips
ONLY the Glean dependency, `toolkit/components/translations/moz.build` is
unconditional, and stock `geckoview-prefs.js` defaults
`browser.translations.enable=true` — so Gecko was already detecting every
page's language and firing `onOfferTranslate` into the void before the app
installed a delegate. **Nothing you read leaves the device**; the ONE network
touch is the per-language model download from Mozilla's Remote Settings +
attachment CDN the first time a language is translated (tens of MB per
language). State that honestly in copy, as the Translations screen's
notice row (`settings_translations_notice`, a static non-selectable
`<Preference>` under the two switches — NOT the root Settings row's summary,
which is the one-line door label "Translate pages on this device") and the
sheet's download-size hint do. What the app owns, and where:

- **Delegate**: `GeckoComponents.TranslationsDelegate`, attached to every
  session in `BrowserFragment.connectSession`
  (`setTranslationsSessionDelegate`). Same rule as every other delegate: the
  per-tab state is written ungated (`GeckoState.setTranslationState`, read
  later by the popup and the sheet), the UI notifications
  (`TRANSLATION_OFFER` / `TRANSLATION_STATE`) are gated on
  `isCurrentGeckoState`. A background tab's offer is DROPPED, not queued —
  Gecko offers once per host per session, and the popup row stays as the
  door. `onPageStart` clears the tab's state (a new document has none until
  Gecko reports on it), or the popup would still read "Show original".
- **Surfaces — the Chrome/Firefox shape, four of them, all reading ONE
  state.** (1) The **offer card** (`browser_translate_offer.xml`, included
  in `fragment_browser.xml` with the snackbar's `AnchorBehavior` so it rides
  the bottom bar; `BrowserFragment.showTranslateOffer`): shown on
  `onTranslationOffer`, a real surface in the snackbar's slot — coral glyph,
  "Translate to <target>?", "This page is in <source>", Not now / Translate
  (opens the picker — never translates blind, the user confirms the pair and
  sees the download size first), and a ⋮ that opens the SAME sheet — its
  options section is where "Always translate <lang>" / "Never translate
  <lang>" / "Never translate <site>" live. That is Firefox for Android's
  shape (the offer's overflow leads to the translation options inside the
  sheet), chosen over Chrome/Brave's anchored menu: a `PopupMenu` hung off
  the card was shipped first and read as foreign next to the M3 card, and
  it duplicated three switch rows the sheet already had (the menu, its
  `translateNow` "always + translate this page now" shortcut and the
  `translate_choose_language` string are gone — don't bring the menu
  back). The three option rows carry NO leading glyph — Firefox's
  options sheet and its language lists don't either: the switch is the
  state, the section label the scope. Two glyph sets were tried and
  rejected: refresh / block / crossed-globe (on-device: "say nothing about
  translating"), then a `translate_off` composed in Material Symbols' own
  `_off` construction — Material ships none, and rendered in headless
  Chromium the corner-to-corner slash runs along the 文's own diagonals
  and leaves stubs, unreadable at 24dp. Don't try a third; the one
  translate glyph is `translate_24` (rebased to the Material Symbols
  outline, a 960-box vector with a +960 y shift) on the doors: address
  bar, popup row, offer card, sheet header, Settings entry. The site
  row's label WRAPS — a middle-ellipsis on "Never translate
  www.<long-host>.es" hid the very host the switch acts on. The
  TRANSLATED face's Show original / Change languages are two EQUAL-WIDTH
  outlined buttons spanning the row (wrap_content left both hugging the
  start edge). Hidden on Not now, on any navigation
  (`onStart(GeckoState)`), on a tab switch (`applyOpenUriUi`), when a
  translation takes, and whenever the sheet opens. It REPLACED a one-action
  snackbar ("Translate this page from X?" → TRANSLATE) that named nothing
  and offered no opt-out — every big browser's offer does both. (2) The
  **address-bar glyph** (`translate_button` in `browser_address_bar.xml`,
  `GeckoToolbar.setPageTranslated`): a STATUS mark, shown ONLY while the
  page shows a translation — NOT a standing door. Chrome and Firefox keep
  a quiet glyph on every untranslated foreign-language page; that shipped
  first (a QUIET colorOnSurface state off `GeckoState.isTranslatable()`)
  and was retired (maintainer call): Firedown's bar already carries the
  shield and the reload button with the download FAB right below, and a
  glyph standing for a state the user never asked for read as noise —
  the offer card announces such a page once and the popup's "Translate
  page" row is the permanent door. Retiring it also settles the
  never-translate case for the toolbar with no logic: an untranslated
  page has no glyph to hide. What remains is the reload button's TWIN —
  the same 48dp slot, the same 24dp glyph, directly before it with a
  -8dp end margin so the two glyphs sit 16dp apart (Material's dense
  40dp-container rhythm; two flush 48dp slots left 24dp, reported as too
  much gap) while both keep a ≥40dp exclusive target — tinted
  `@color/progress_indicator` in the layout (the contrast-safe coral:
  3.70:1 on the light pill where the brand coral measures 2.30:1, under
  the 3:1 glyph floor; Chrome's blue-glyph shape for "translated"). It
  shipped first as a 40dp filled coral disc with a 22dp glyph, which read
  as an over-sized foreign control beside the reload arrow — never a fill
  here. GONE otherwise, and hidden like the reload button while the field
  has focus or find-in-page owns the slot. Derived by
  `BrowserFragment.refreshTranslateGlyph` from the tab's STORED
  `isPageTranslated()` (not the event) on every state change, page start
  and tab switch, so a tab switched onto later paints the right glyph
  though the observer events are foreground-only. Tap → the sheet's
  TRANSLATED face. **Never-translate follows Chrome/Brave, not Firefox**
  (maintainer call): a page under "Never translate <site>" or "Never
  translate <its language>" gets NO automatic surface — no card, no glyph
  — and only the deliberate doors remain (the popup row, the sheet's
  Translate). Firefox's model would still OFFER through its panel (never
  = never AUTO-translate; Fenix keeps the switches on a separate options
  page). Gecko itself skips `onOfferTranslate` under either rule;
  `BrowserFragment.showOfferUnlessNever` re-reads both stores
  (`getNeverTranslateSiteSetting` + `TranslationLanguageSettings.get`)
  before showing the card as belt and braces for a rule flipped after the
  page loaded, shows it in the callback only if the tab is still current
  on the same document, and degrades a failed read to the plain
  Gecko-gated offer (never to a lost one). An already-translated page
  stays translated (and lit) when a never rule is set — the rule is about
  future offers, as in Chrome. (3)
  The **translate sheet** (`TranslateSheetDialogFragment`,
  `fragment_dialog_translate`): ONE sheet, TWO FACES chosen from
  `isPageTranslated()` at open — PICKER (From ⇄ To side by side with a
  swap, the hint line, the options, Cancel/Translate) and TRANSLATED (a lit
  header "Translated to <to> · From <from>", Show original, Change
  languages — flips to the picker in place, preselected with the pair in
  use — and the same options). The From/To lists come from
  `listSupportedLanguages()`, preselected from the tab's `TranslationState`
  (document language → From, `userLangTag` else device locale → To, matched
  by exact code then language subtag since Gecko's model list carries bare
  codes); `checkPairDownloadSize` fills the hint; `translate(from, to,
  downloadModel=true)` then closes. **The OPTIONS are three switches over
  Gecko's OWN stores, never a pref of ours**: "Always translate <lang>" /
  "Never translate <lang>" write the per-language setting
  (`TranslationLanguageSettings`, below — mutually exclusive by
  construction: one on clears the other, both off = `offer`), labelled for
  the FROM pick (else the detected language, hidden when none is known),
  and "Never translate <host>" writes `setNeverTranslateSiteSetting`.
  Painted optimistically, re-read from Gecko on a failed write; never
  closes the sheet — "never translate Spanish" is a preference, the user
  may still translate THIS page. The popup's **"Translate page" / "Show
  original"** text row stays (one row, two meanings — label and action
  follow `isPageTranslated()`; hidden on non-web documents and when the
  master switch is off; it sits in the New tab / New private tab group, NOT
  as a lone row between the icon grid and the divider, which shipped once
  and read as a stray). (4) **Settings → General → Translations**
  (`TranslationsFragment`): the master switch
  (`SETTINGS_TRANSLATIONS_ENABLED` → `browser.translations.enable`, OFF
  also stops the per-page detection and all Mozilla contact), "Offer to
  translate" (`SETTINGS_TRANSLATIONS_OFFER` →
  `GeckoRuntimeSettings.setTranslationsOfferPopup`, Gecko's own gate on the
  offer), then FOUR live lists: **Always translate** and **Never translate**
  (the per-language store — tap a row to put it back to `offer`, "Add
  language" picks from the supported list; Gecko holds ONE state per
  language, so adding to one list moves it out of the other), the
  downloaded models (`listModelDownloadStates` filtered to `isDownloaded`,
  tap → `manageLanguageModel(DELETE, LANGUAGE)`, plus a delete-all) and the
  never-translate sites (`getNeverTranslateSiteList`, tap →
  `setNeverTranslateSpecifiedSite(false, origin)`). All lists are re-read on
  every resume; a cached list would lie about what is on disk. The
  sub-screen applies its own prefs to Gecko (the sub-screen pattern —
  `SettingsFragment`'s listener is unregistered while it is foreground).
  **The sheet OBSERVES the tab's state, it does not snapshot it**: Gecko's
  detection is asynchronous (a content actor samples the visible text, CLD2
  classifies it in a worker, `<html lang>` is the fallback when it isn't
  confident), so a sheet opened from the popup on a just-loaded page can
  find no `docLangTag` yet. The delegate posts the changed tab to a
  repository LiveData (`notifyTranslationState` → `getTranslationStateChanges()`
  on both state view models, emitted for EVERY tab, ungated like the state
  write — the sheet compares identity), and `applyDetectedLanguages` fills
  From (and To, if still empty) in place, showing `translate_detecting` in
  the hint line until Gecko has posted a detection RESULT (`hasLanguageDetectionResult` — a `detectedLanguages` block, even one with a null tag: an inconclusive run must end the hint too); a field the user
  already picked is never overwritten, and a replayed LiveData value is a
  no-op by construction (it only ever fills empty fields). Don't turn the
  sheet into a `GeckoObserver` for this — the interface has no defaults and
  the sheet would carry forty no-op stubs; the cert LiveData is the
  precedent for a per-tab signal read by one surface.
- **Per-language "always / never" is GECKO'S store, read through
  `TranslationLanguageSettings` — never a SharedPreference.**
  `RuntimeTranslation.getLanguageSettings()` / `setLanguageSettings(code,
  state)` keep three states per bare language code (`always` / `offer` /
  `never`), and Gecko ACTS on them itself: `never` stops
  `onOfferTranslate` for that language's pages, `always` translates them on
  load (the state change lands on the tab like a user request, so the
  glyph lights and the card retires with no code of ours). The offer's ⋮,
  the sheet's switches and the Settings lists are three views of that one
  store — none of them keeps a copy, so none can drift. The helper reduces
  a document tag to its subtag before reading/writing (`pt-BR` → `pt`, the
  key shape Gecko's model list uses) and normalises casing; a language with
  no row reads `offer`. Don't add a mirror pref "so the card can decide
  faster" — the card doesn't decide, Gecko does, and a mirror is exactly
  what would let the four surfaces disagree.

- **Only a FAILED translation is announced** (`onTranslationStateChange`
  with an `error` and a `requestedTranslationPair`). Success is the page
  visibly changing; the pre-translate states are read, not shown.
- **Language names** go through `TranslationLanguages` (Gecko's
  `localizedDisplayName` when a `Language` is at hand, else
  `Locale.forLanguageTag(tag).getDisplayName()`) so the three surfaces
  agree. `RuntimeTranslation` calls are `@HandlerThread` — main thread only
  — and every `GeckoResult` callback re-checks the view/fragment is alive.
- **Interactions to keep in mind.** The "Disable WebAssembly" switch does
  NOT break the engine: Gecko defaults `javascript.options.wasm_trustedprincipals`
  to true and nothing here changes it, so system-privileged WASM keeps
  running when pages lose it (verify on-device if that pref ever moves).
  Language coverage is whatever Mozilla ships models for (~30 languages,
  mostly to/from English); a cloud API tier for the rest was considered and
  is only acceptable opt-in with the user's OWN key — never a baked key,
  never page text to a third party by default.
- **Verify** with a device: `adb logcat -s GeckoConsole:* | grep -i translat`
  shows the engine/model fetches; the CLI has no test for this — it's all
  GeckoView API plumbing plus UI.

## "Save snapshot" — a SingleFile-style archive of the rendered DOM, frames included

The browser popup's **Save** row archives the current page to ONE
self-contained `.html` (`webrequests/js/snapshot.js`, a content script;
trigger `GeckoRuntimeHelper.captureSnapshot()` → the `"browser"` port →
`requests.js` relays `snapshot-capture` to the tab; delivery is a blob
`<a download>` click → `onExternalResponse` → the normal download pipeline,
so the file lands as a real Downloads row). It freezes the LIVE post-JS DOM,
inlines every sub-resource as a data: URI through the background's
privileged fetch (`snapshot-fetch` — a content-script fetch is CORS-bound),
strips scripts, and is opened by `HtmlViewerActivity` (a JS-off,
network-blocked WebView). The invariants, each from a shipped bug:

- **It runs in EVERY frame (`all_frames: true`), and the top frame embeds
  each child iframe's archive as `srcdoc`.** The content of many pages is
  not in the top document: a hosted ePDF reader is a 340-byte shell whose
  whole content is a cross-origin iframe, and a top-frame-only archive of it
  is an EMPTY document. The
  top asks each child through `iframe.contentWindow.postMessage`
  (`fd-snapshot-frame-request` + a request id) and the child's own copy of
  the script answers with its serialized HTML — element-to-content mapping
  by construction, cross-origin included. The child honours only
  `window.parent`, and the ANSWER travels child → background → the parent
  frame's content script (see the next bullet). Nested frames recurse to
  `MAX_FRAME_DEPTH`, a child gets a quarter of the resource budget, and a
  frame that never answers keeps its original `src`. **The relay addresses
  `frameId: 0`** — a fan-out to all frames would start one archive per
  iframe.
- **The frame handshake is GATED on a live capture and never replies by
  `window.postMessage` — the envelope is page-forgeable, the reply was
  page-readable.** The first design honoured the request on its nonce alone
  and answered with `window.parent.postMessage(html, '*')`, which the parent
  PAGE's scripts receive too: any page could post the envelope into a
  cross-origin iframe it embeds, read the child's serialized DOM (form
  values, tokens, logged-in content) and have this background fetch the
  child's sub-resources with credentials — on demand, no user action. Now
  `requests.js` arms the tab's `TabState.snapshot` when it relays the popup
  trigger (cleared by the top frame's `snapshot-done`, the tab closing, or a
  5-minute TTL); a child asks `snapshot-frame-allowed` before serializing and
  returns its ack and archive through `snapshot-frame-relay`, which the
  background forwards (`webNavigation.getFrame` → `parentFrameId`) as
  `snapshot-frame-result` to the parent frame's content script only,
  correlated by the request id the parent minted; `snapshot-fetch` is served
  only to a tab with a live capture. **The privileged fetch is read THROUGH
  its cap under a 20 s timeout** (`readBodyCapped`: a declared
  `Content-Length` over `SNAPSHOT_MAX_RESOURCE_BYTES` is refused before a
  byte is read, a stream is cancelled at the cap) — it used to be
  `resp.text()`/`resp.blob()` with the size checked afterwards and no
  AbortController, so a 1 GB `<video>` was buffered whole and a live
  `<audio>` stream never resolved, growing the background page to OOM while
  the top frame's `capturing` flag stayed set. A frame with no content
  script (same-origin `about:blank`/`srcdoc`) is skipped outright, every
  other child must ACK within `FRAME_ACK_TIMEOUT_MS` (2 s) before the long
  wait starts (dead frames used to cost 60 s each, four at a time), and a
  child receives its parent's `deadline` so a grandchild chain can't outlive
  the ancestor waiting on it. Pinned by the smoke's `snapshot:` checks.
- **`<noscript>` and `<meta http-equiv=refresh>` are STRIPPED.** The archive
  opens with JS off, which makes every noscript block LIVE — on a
  JS-rendered page that is a "redirect to the real page" / "enable
  JavaScript" placeholder. The ePDF shell's noscript meta-refresh fired in
  the viewer, navigated it to the network, and the user saw WebView's
  `net::ERR_CACHE_MISS` page instead of the archive. `HtmlViewerActivity`
  additionally refuses every top-level navigation away from the loaded
  file (`shouldOverrideUrlLoading` — `setBlockNetworkLoads` only blocks
  sub-resources); a tapped http(s) link is handed to Firedown's own
  browser (`ACTION_VIEW` pinned to this package).
- **Shadow DOM and constructed stylesheets are serialized** (the paired
  walk `syncDynamicState`): `cloneNode`/`outerHTML` see only LIGHT DOM, so a
  component app archived as its chrome with empty content. Every shadow
  root — open AND closed, via Firefox's extension-only
  `openOrClosedShadowRoot` — becomes a declarative
  `<template shadowrootmode>` (Chromium 111+, i.e. the WebView viewer; the
  old `shadowroot` spelling is set too), with the root's
  `adoptedStyleSheets` (Lit keeps every component's CSS there, in no
  `<style>` at all) flattened into a `<style>` at its head, and the
  document's own adopted sheets into `<head>`. Every inlining pass runs
  through `qsa()` = the clone PLUS every template's content, because
  `querySelectorAll` never enters template contents; canvases and iframes
  are handled from the (live, clone) PAIRS the walk collects, since two
  independent `querySelectorAll`s can't be index-aligned across shadow
  trees.
- **The background re-fetch carries the PAGE's Referer** (`snapshot-fetch`
  sends the frame's `location.href`; `snapshotReferers` + the ONE blocking
  `onBeforeSendHeaders` set it on the wire — full URL same-origin,
  origin-only cross-origin, Gecko's default trimming). An extension-page
  fetch has no page Referer and the `referrer` fetch option is dropped for
  a cross-origin value, so a signed CDN that also gates on Referer (a
  reader's rasterized page images; the hotlink class) 403s, the image stays
  an absolute URL, and the archive's pages are blank in the network-blocked
  viewer. Same
  rule as the save-image download: reproduce the page's Referer, don't
  strip it.
- **Deferred content is scrolled into existence BEFORE the freeze**
  (`loadDeferredContent`, the first step of `serializeDocument` in every
  frame): the document scroller plus the largest `overflow:auto/scroll`
  elements are stepped start-to-end on EACH overflowing axis (a horizontal
  pager has no vertical overflow) a viewport at a time, via
  `scrollTo(…, instant)`, with a settle
  after each step (IntersectionObserver / scroll-listener / `loading=lazy`
  loaders all fire on a real scrollTop change), the original position is
  restored, then `waitForQuiet` holds until no `<img>` is incomplete and
  the resource-timing count stops moving. SingleFile's "load deferred
  images" pass. **The scan and the quiet wait walk SHADOW ROOTS
  (`liveElements`, open and closed) — never `document.querySelectorAll('*')`
  or `document.images`.** Those stop at every shadow boundary: a reader
  whose whole UI, pager included, sits under ONE shadow root while its host
  document has no overflow at all (`position: fixed` viewer) gives a
  document-level pass nothing to scroll, and the archive holds an `<img>`
  only in the pages the viewer had loaded, a spinner in every other. Bounded
  three ways — time
  (`LAZY_MAX_MS_TOP` 20 s / `LAZY_MAX_MS_FRAME` 20 s; the parent's
  `FRAME_REPLY_TIMEOUT_MS` 60 s must exceed a child's budget plus its
  serialization, and the whole thing must fit the Java snackbar's 90 s),
  step count and container count — so an infinite-scroll feed gets ONE pass
  to its current bottom, never forever. A page with nothing to scroll pays
  ~half a second of quiet-wait.
- **The pass HARVESTS image sources as it goes (`harvestImageSources`),
  because a virtualizing viewer unloads what it just loaded.** It keeps
  every page's `<img>` and clears its source outside a ~5-page window around
  the current page, so a pass that reached every page ends with real images
  only in the last window.
  So after every settled step (and once at the end) every `<img>` with a
  real source is recorded by element identity, plus a structural child-index
  path (crossing shadow hosts) as the fallback for a re-created node, and
  the paired walk's IMG branch takes the harvested source for an image whose
  live source is empty; the ordinary inlining pass then re-fetches it (the
  page's Referer, `force-cache`). Reset per pass; costs one walk per step.
- **Known limits, deliberate:** a virtualized list that REMOVES its rows as
  they scroll away is captured as the window mounted around the restored
  scroll position (the harvest needs a surviving element or a re-created
  one at the same path; a node that no longer exists is gone); a
  cross-origin-tainted canvas stays blank (a viewer that draws its page
  image onto a canvas can't be read from a content script — nothing here
  can fix that); adaptive manifests are never inlined (a data: manifest
  crashes the reopened HLS player).
- Any change here is an extension-file change → bump
  `webrequests/manifest.json` `version` (the `ensureBuiltIn` trap). There
  is no node test for the serializer (it needs a DOM); `node --check` the
  file and verify on-device with `adb logcat -s GeckoConsole:* | grep
  snapshot` (debug builds).

## Security toggles & default inversion (the JIT/WASM pattern)

Several "harden the browser at a cost" switches in the Security settings
category are **disable-X** toggles that default **OFF** (the feature is on by
default; turning the switch on hardens at a performance/compat cost):
`SETTINGS_DISABLE_WASM`, `SETTINGS_DISABLE_WEBGL`, `SETTINGS_DISABLE_JIT`.

**The WASM switch is a plain GLOBAL switch now — the per-site allowlist was
REMOVED, don't bring it back.** "Disable WebAssembly" once came with a
detector content script (`wasm-watch.js` + a page-world `wasm-probe.js`),
an "Enable for {host}?" snackbar, its own Room DB (`wasm-allowlist-db`, an
incognito in-memory twin), a `WasmFragment` sub-screen and a
per-navigation pref flip in `NavigationDelegate.onLocationChange`. All of
it was built when WASM was OFF by default and every user hit broken sites;
once the default flipped to ON it only served the few who deliberately
turn WASM off — the same audience the JIT and WebGL switches serve with no
escape hatch. Now it is one `SwitchPreferenceCompat` on the Security
screen applied by `SecurityFragment`'s listener (`setWebAssembly`), and
`App.onCreate` deletes the orphaned DB file once. Gecko's
`javascript.options.wasm_trustedprincipals` (default true, untouched) keeps
privileged WASM — the built-in translator's engine — running while pages
lose it.

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

### The security sheet — one switch (uBlock), ETP is GLOBAL, M3 cards as rows

`SecurityStateSheetDialogFragment` (`fragment_dialog_security.xml`, the
shield tap) is four rows and ONE switch: "Connection is secure ›" (first
row under the identity header — it is the page's most important fact and
drills into the certificate), "N blocked on this page ›" (uBlock's
per-page tally → `BlockedAdsDetailDialogFragment`), the **"Block ads and
trackers"** switch, and "Clear browsing data ›". Decisions, each a
maintainer call after sketches on the design canvas:

- **The per-site "Tracking protection" switch is GONE, with its whole
  exception machinery.** It flipped `GeckoSessionSettings.
  useTrackingProtection` per tab off a per-domain Room table
  (`tracking-db`, `TrackingPermissionDatabase`/Dao/entity/repository, an
  incognito in-memory twin, the two view-models' `isTrackingProtected`/
  `toggleTrackingProtection`, `GeckoState.setTrackingProtection`, the
  entity's persisted `useTrackingProtection` flag and the toolbar's
  `mTrackingEnabled` half of the shield). Two switches on one sheet were
  two engines saying "trackers" for the same thing, and "break this site"
  needed BOTH off. uBlock's per-host whitelist (`GeckoRuntimeHelper.setAds`
  → firedown.js `toggleNetFilteringSwitch`) is the one per-site control
  now, titled for what it does. Gecko's ETP stays ON at its global level in
  Settings → Privacy (default **Standard** — `settings_tracking.xml`
  `tracking.default` true, `Preferences.getEnhancedTrackingProtectionLevel`
  falls to it; this was verified, not changed), where cookie partitioning /
  purging — engine-only features uBlock has no equivalent of — live anyway.
  **Gecko blocks tracker LOADS only in Strict and in private tabs — the
  Firefox rule** (`GeckoRuntimeHelper.blocksTrackingContent`, read from the
  runtime's ETP level when a session is created; a level change in
  `TrackingFragment` is applied to open tabs through
  `GeckoStateDataRepository.setTrackingContentBlocking`). In Standard Gecko
  marks trackers and blocks their cross-site cookies but lets them load,
  and uBlock blocks the ads and analytics. Every session used to be built
  with `.useTrackingProtection(true)`, which made Standard Firefox's Strict,
  and the removal above rested on a false premise: Gecko's lists are NOT a
  subset of uBlock's. The default set includes Disconnect's SOCIAL list
  (`social-track-digest256`, which names twitter.com and x.com) and no
  bundled uBlock list blocks `platform.twitter.com/widgets.js`, so embedded
  tweets never rendered on any news site, with uBlock off for the site and
  no setting that could bring them back (every `widgets.js` request
  cancelled before leaving the phone). **There are
  TWO Gecko blockers and both follow Strict.** The per-tab flag gates only
  the main tracker blocker (on Android `UrlClassifierFeatureTrackingProtection`
  checks the load context's `UseTrackingProtection`). The SOCIAL-tracker
  blocker (`UrlClassifierFeatureSocialTrackingProtection`, the
  Facebook/LinkedIn/Twitter lists of
  `urlclassifier.features.socialtracking.blacklistTables`) ignores the tab
  and blocks every third-party load while
  `privacy.trackingprotection.socialtracking.enabled` is on, which is
  GeckoView's `strictSocialTrackingProtection`. Firedown set it `true` for
  everyone at boot, so the first fix (the per-tab flag alone) changed
  nothing on device; it now follows the level at boot and in
  `TrackingFragment`, as Firefox's `stp`, which only its Strict feature set
  carries. Don't make regular tabs block loads in Standard again, don't
  turn the social blocker on outside Strict, and don't re-add a per-site
  ETP switch: with loads unblocked in Standard, uBlock's whitelist really is
  the per-site door.
- **Migration leftovers that must stay:** the sessions file's `tracking_
  protection` key is tolerated in files BELOW v4 (its name lives only in the
  strict reader's `RETIRED_KEYS` map — not in `GeckoStateEntity.KEYS`, which
  lists what the writer emits; `icon_resolution`, dropped with the history
  row's higher-res gate, was retired in the same v4, and `thumb` in v5 —
  see "Tab persistence") — a v2/v3 file in the wild still holds it and must
  not be moved aside as corrupt over it; and
  `App.onCreate` sweeps the orphaned `tracking-db` file beside the WASM
  allowlist. `BlockedTrackersDetailDialogFragment` (the per-category ETP
  drill-in) went with the switch, and so did the whole engine-side tracker
  COUNT pipeline it fed: `TrackingCategory`, `GeckoState`'s per-page
  count/host maps, the `ContentBlocking.Delegate` (`onContentBlocked`)
  that filled them — no session registers one any more — the two
  repositories' `postBlockedTrackerCounts` LiveData, the view-models'
  getters, the five category strings and the `blocked_trackers_summary`
  plural. `BlockedTrackerDetailAdapter` stays as a hosts-only adapter (the
  "Blocked on this page" drill-in feeds it `HostRow`s, in the Settings
  domain rows' anatomy — see the blocked-stats rules under Home; its
  category `Header` row type and `item_blocked_tracker_category_header`
  are gone). If a per-page
  "trackers" number is ever wanted again, it is uBlock's tally that
  should carry it — one engine, one count — not a revived ETP delegate.
- **Every row is a selectable `MaterialCardView`, grouped** — not a flat
  `LinearLayout` with a ripple (the old sheet, which read as a 2019 list
  with no touch surface). Rows sit on `colorSurfaceContainerLow` over the
  sheet's `surfaceContainerHigh`, 2dp apart, and a group's outer corners
  are 16dp with 4dp inner ones (`ShapeAppearanceOverlay.FireDown.
  SheetGroupTop/Bottom`, the Material 3 Expressive / Android-16-Settings
  grouping), no dividers. The switch row's whole card toggles the switch
  (`ads_card` click → `toggle()`), and the ViewModel bind is guarded by
  `mBindingAdsSwitch` rather than `isPressed()` — a card tap is not a
  press on the switch, so the old guard would have treated the user's own
  toggle as a programmatic echo. The count is `onSurface`, not coral:
  the brand coral is 2.3:1 on the light surface, the one number the
  sheet exists to show was its least readable text.
- **The FRAME moved to stock Material 3 for EVERY sheet** (they share
  `Theme.FireDown.BottomSheetDialogStyle` + the `dialog_drag_handle.xml`
  include): the style now extends `Widget.Material3.BottomSheet.Modal`
  (28dp top corners from the M3 shape system, `backgroundTint`
  `colorSurfaceContainerHigh`) instead of a hand-drawn `dialog_rounded_top`
  drawable (deleted), and the handle is Material's
  `BottomSheetDragHandleView` (32×4dp, 40% ink, accessible — announces
  and acts as a drag affordance) instead of a bare 2dp `View`. **Its
  band is OURS: 10dp above the pill, 10dp below, `minHeight` 0 — 24dp,
  set in the one include.** Material's default band measured ~36dp on
  device (the pill 10dp from the top, ~22dp of nothing under it), and
  with the popup identity row's own centring the first content sat ~38dp
  below the pill — the void issue #306's follow-up outlined in red
  ("remove that empty space and move the elements up"). The band carries
  no touch semantics worth the space: a sheet drags from anywhere on it,
  and the view is clickable only under touch exploration, where TalkBack
  activates the focused node whatever its size. Every sheet's content
  moved 12dp closer to the pill with that one edit; don't give a sheet
  its own handle spacing back. **The 24dp band is a DECIDED value
  (maintainer, 2026-10-12, against the big players), not a first guess
  to tune.** The industry splits in two: Google's spec band (Compose
  `ModalBottomSheet`: 22dp above and below the pill, 48dp in all) is a
  TAP TARGET — the handle toggles expand/collapse there and is a
  screen-reader node — while every browser treats the pill as a HINT
  and starts content one row gap under it: Chrome/Brave/Edge's shared
  Chromium sheet (thin bar near the edge, header right under), Firefox
  for Android's 2024 menu (tight handle, then grouped card groups — the
  closest relative of this app's sheets), Samsung Internet (pill ~10dp
  down, title right under) and iOS (36×5pt grabber 5pt from the top).
  A browser's sheet drags from anywhere, so the target argument does
  not apply; the band stays in the browser camp. If it is ever revised,
  the one knob is the include's bottom padding, moved for every sheet
  at once and kept under ~16dp — never back to 22. **And a sheet's FIRST content adds no top
  spacing of its own — the include's 10dp under the pill IS the gap**:
  an identity row, a header row or a label sits at 10dp, a card at 12dp
  (its own 2dp row gap), on every sheet. The follow-up round (2026-10-11,
  six more sheets outlined in red on a build that predated the trim)
  found three sheets stacking their own spacing on the band even after
  it: the options sheet's recycler carried a 10dp top margin meant as
  the gap under the quick-action card, which made the History /
  Bookmarks / Safe Folder ⋮ sheets (quick row GONE) open 22dp under the
  pill while the Home popup opened 12dp — the margin moved onto the
  quick card's bottom; the browser popup's identity row centred inside
  a 72dp `minHeight` with 12dp of top padding (favicon 26dp under the
  pill, the security sheet's at 10dp) — both gone, 12dp under it kept;
  the Backups item sheet's header had 14dp of top padding, the
  search-engine / Sort / Download-info sheets 4dp each — all zero now.
  The Captured sheet is the one exception by design: its toolbar
  overlaps the band (a FrameLayout, `content_frame` 10dp from the top),
  the pill floating in the toolbar's top edge. A TITLE over a group is not the band case: the group keeps its style's 10dp (12dp to the first card, what the search-engine and places sheets have); the Sort and the player's Subtitles sheets copied the first-content 0dp override under their titles, 2dp from label to card. **The
  style's `padding*/margin*SystemWindowInsets` flags are all `false` on
  purpose**: `BaseBottomSheetDialogFragment` applies the window insets as
  padding on the sheet view itself, and the M3 defaults would add a second
  copy. The handle include keeps `@+id/divider` because two sheets
  (`BrowserOptionHolderSheetDialogFragment`, `BrowserOptionFragment`) look
  the view up by that id. **The behaviour's max height is the CONTENT cap
  plus that bottom padding** (`applyBottomSheetMaxHeight` adds the inset
  the listener applied, and re-applies once insets land — they are not
  known in `onStart`): the behaviour measures the sheet ROOT, so a bare
  `resolveMaxHeightPx()` took the nav-bar band out of the content. It
  shipped on the browser popup, whose `popup_content` is clamped to
  EXACTLY that cap (`applyContentMaxHeight`): the sheet opened one inset
  below the toolbar it is sized to meet and clipped its last row under
  the gesture bar, while every wrap_content sheet hid it (their content
  shrinks into the padded band) and the Capture sheet opts out of the
  cap. A sheet that sets an EXACT content height must get its ceiling
  from `resolveMaxHeightPx()` and let the base add the padding — never
  add the inset in the subclass too.
- **`cardPreventCornerOverlap` is OFF on every grouped card, in the
  `Firedown.Widget.SheetRowCard` style — the first and last rows of a
  group were 65dp tall and their content 4.6dp further in than the rows
  between them.** CardView's default (`true`) makes `MaterialCardView` pad
  its CONTENT by `(1 − cos 45°) × corner` on every side whenever its four
  corners are not all equal — exactly the `SheetGroupTop` / `SheetGroupBottom`
  shapes (16dp outer, 4dp inner → ~4.6dp), never the uniform `Middle` /
  `Single` ones. It is computed ONCE at inflate from the XML shape and a
  runtime `setShapeAppearanceModel` never recomputes it, so the rows the
  adapters shape at bind (`OptionsAdapter`, the search-engine list,
  `InfoAdapter`) measured a clean 56dp while every XML-overlay row at a
  group's edge measured 65dp with its icon and label indented — on the
  2026-10-11 screenshots (1.75 px/dp): browser popup library group
  65 / 56 / 65dp, every two-row group of the Home popup 65dp, the
  adapter sheets 56dp throughout. The reporter saw it as "the History
  row is not centred" in the browser popup's three-row group, the one
  Middle card between two edge cards. The style sets the flag, the
  security sheet's four cards now USE the style instead of an inline copy
  of its attributes (the copy is how a style fix would have missed them),
  and `SheetGroups.apply` / `applyCorners` set it again as a belt on any
  card they shape. Don't "tidy" the attribute away as redundant with the
  M3 default — the default is the bug.
- **The card rows are the rule for EVERY sheet now, not the security
  sheet's own look** (maintainer request after the v3 security sheet:
  "check every bottom sheet, apply the same material card approach").
  Shared pieces, so no sheet forks them: `utils/SheetGroups`
  (`applyCorners(group)` recomputes a group's outer corners from the
  VISIBLE `MaterialCardView` children; `shapeFor`/`apply` for adapters),
  the `Firedown.Widget.SheetRowCard` / `SheetRowText(.Final)` /
  `SheetGroup` / `SheetRowRadioRow`+`Label`+`Button` styles, the
  `SheetGroupTop/Middle/Bottom/Single` overlays and the four `sheet_*`
  dimens (16dp outer / 4dp inner corners, 2dp between rows, 12dp between
  groups; the parent's 16dp padding or the group's 16dp margin is the
  inset, never a card margin). Converted: the Browser + Home ⋮ popups
  (three UNLABELLED groups — a "Library" caption over Bookmarks/History/
  Safe Folder shipped for a day and was cut on sight, the rows name
  themselves; Translate / Quit / the mode-swapped Downloads-vs-Safe
  Folder row hide, so both fragments call `applyCorners` after their
  visibility passes; the Browser popup's two quick-action icon rows sit
  in ONE non-clickable card at the groups' inset, so the grid belongs
  to the menu rather than floating under the identity header — and
  `QuickRowLabels.ROW_HORIZONTAL_PADDING_DP` carries that card's 32dp,
  move them together), the whole `OptionsAdapter` family (New tab, Web
  options for bookmarks AND history, Downloads ⋮ incl. the Media-tools
  sub-list — the adapter sets corners per position and a separator item
  is a 10dp group break, not a hairline; guarded on the root being a card,
  though every item layout handed to it is one now, the long-press
  context DIALOG's included — see "Long-press context menu"; its
  Share / Open with / Rename / Send strip is ONE non-clickable card too,
  with hairline VERTICAL dividers between the four buttons
  (`SheetQuickRowDivider`) — a single-row strip of equal columns on a
  shared ground has no other cue where one target ends, whereas the
  Browser popup's two stacked rows read as a grid and carry none), the
  Backups item sheet (Open is revealed late → `refreshActionCorners`),
  the Backup screen's "Where to back up" places sheet (two-line cards with
  a chevron, the removable-volume card hides → `applyCorners`; its second
  page is a guide, not a menu),
  the Translate sheet's switch rows (the card toggles the switch), the
  Sort-by and search-engine radio sheets (label + TRAILING non-clickable
  radio; the card is the touch, the fragment/adapter checks the radio —
  an RTL-flipped CompoundButton was tried first and dropped), the
  certificate sheet (a group label over ONE card per section, the
  collapsible sections' header ids ON the card, empty rows hidden inside
  it), and the blocked-ads + download-info lists (one card around the
  host list, swapped with the empty state; one card per info fact with
  the OK button outside the group). **Rules that fall out of it:** a row
  that can HIDE never relies on a static shape — toggle, then
  `applyCorners`; every hairline divider between sheet rows is gone (the
  groups do the sectioning — don't add one back "for clarity");
  `Firedown.Widget.DialogOption` and `CertSectionHeader` are deleted, use
  the Sheet* styles. The settings screens wear the same cards
  (same `SheetGroups.shape`, same dimens) — see "Settings cards" under
  Settings IA. **Deliberately NOT converted**, because they are not
  menus: the notifications priming sheet, the update sheet, the crash
  report sheet, the buy-bitcoin help sheet, the trackers-info sheet and
  the Captured sheet holder (a list with its own row chrome). Every
  `<dialog>` destination that is not a sheet (Delete*/Rename/Save/
  Clipboard/Enroll/BrowserApp/BrowserDownload/ClearBrowsing/
  DeleteBrowsing) is a centred `MaterialAlertDialog` and stays one; the
  long-press context dialog (BrowserContent) is a centred CUSTOM dialog
  whose rows are the card rows (the one centred surface that is a menu).

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

- **Referer `XOriginPolicy` must be `0`, NOT `2`.** `2` means "send a
  cross-site Referer **only when the base domains (eTLD+1) match**", which
  **strips the Referer entirely** on any request to a different base domain.
  That silently breaks every site whose media/asset CDN lives on a **separate
  base domain behind Referer-based hotlink protection** — the CDN sees no
  Referer and returns **403**. The shape: the page is `www.<site>.net` (base
  `<site>.net`) but images load from `i.<cdn>.net` (base `<cdn>.net`), so with
  `XOriginPolicy=2` every image request 403s while the static JS/CSS on the
  same CDN (no hotlink check) still loads — the page **renders but shows no
  artwork**, which reads as a Gecko/app bug, not a privacy pref. The fix is
  `XOriginPolicy=0` (send the Referer cross-site) **with `trimmingPolicy=2`
  retained** (trim to **origin only** — `https://www.<site>.net/`, no
  path/query), which is exactly stock desktop Firefox's
  `strict-origin-when-cross-origin` and is the smallest change that satisfies
  the hotlink CDN. The privacy delta of `0` vs `2` is only that the bare
  **origin** is sent cross-site (never the path) — the same thing mainstream
  Firefox sends by default. **Never raise this back to `2`** to "harden"
  cross-site Referer: the origin-only trim (`trimmingPolicy=2`) is where the
  real privacy is; stripping the origin too just breaks hotlink CDNs. The
  tell, diffing two HARs of the same page: desktop Firefox sends the origin
  Referer → 200, Firedown none → 403, and Firedown's HAR carries a Referer on
  almost no cross-base-domain request (strip, not trim).
  - **The long-press "save image" download needs the SAME Referer, set
    separately.** The XOriginPolicy fix only covers what *GeckoView* fetches
    while rendering the page. Saving an image via the browser context menu
    (`BrowserFragment` `contextmenu_save_image`) builds a **bare native
    `DownloadRequest`** that goes straight to `HttpDownloadStrategy` with the
    URL + cookies but **no Referer** — so the image CDN 403s it (the
    same hotlink check, now hit by OkHttp instead of Gecko). Fix: the save-image
    branch sets `.headers(BrowserHeaders.refererOriginHeaders(pageUri))`, where
    `pageUri` is the context element's `baseUri` (the document URL) — trimmed to
    **origin + "/"** (`https://www.<site>.net/`, matching the browser's
    cross-origin Referer). It threads through `DownloadRequest.headers` →
    `DownloadContext` → every `HttpDownloadStrategy` request; `OriginInterceptor`
    does NOT promote it to an `Origin` (no `Sec-Fetch-Site: same-origin`), so it
    stays a Referer-only image GET like the browser's. Any other native
    re-fetch of a page sub-resource is exposed to the same hotlink 403 —
    reproduce the page's Referer, don't strip it.
  - **Third surface: Captured-sheet thumbnails via Glide.** The Captured
    sheet's remote image fetches (`GlideHelper.load(BrowserDownloadEntity…)`)
    would 403 on a hotlink-gated CDN with a bare `Uri.parse` model (no
    headers), falling to the broken-image fallback. Remote
    plain-IMAGE fetches (a parser thumbnail URL, or an image capture's own
    URL) go through `buildGlideUrl(entity, source)`, which ships the
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
  - **The Cluster B LNA blocking (`network.lna.*`) stays ON — do NOT disable
    it (maintainer, re-confirmed 2026-10-24: "we don't want tracking like the
    one Facebook did").** The two builder switches are not opposites:
    `setLnaEnabled` turns the PROTECTION on (requests classified by address
    space, the permission model applied), `setLnaBlocking` makes a violation
    BLOCKED rather than only observed — Firefox shipped the feature in that
    report-only stage first, hence two prefs. `true` + `true` is the fully
    enforcing state; neither means "local network access enabled".
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

- **A scrolling screen hosted under the SettingsActivity toolbar roots on
  `NestedScrollView`, never a plain `ScrollView`.** The activity's
  `AppBarLayout` lifts on scroll through Material 3's default `liftOnScroll`,
  which is fed by nested-scroll events reaching the CoordinatorLayout. A
  preference screen's RecyclerView is a nested-scrolling child and gets the
  lift for free; a plain `ScrollView` is not, so the toolbar stayed flat while
  the Share-Firedown page (and the since-removed Add-storage-credit page)
  slid under it (reported on-device — both were `ScrollView`). `CloudBackupListFragment` is the one
  screen that bridges the lift MANUALLY, because its own inner
  CoordinatorLayout swallows the events; a plain page needs no such code,
  just the right root.
- **Menu rows are M3 one-line list items: 56dp tall, 16sp text
  (`TitleMedium`), 16dp horizontal gutter, `onSurfaceVariant`.** Applies to
  every menu/sheet surface — Browser/Home popups (hand-built `LinearLayout`
  rows), the Security sheet + its blocked-ads/trackers detail dialogs and
  variant rows, the `OptionsAdapter` sheets (New tab / Web options / Downloads
  option, via the `Firedown.Widget.SheetRowText` style → `minHeight=56dp` so a
  rare wrapped label can grow), and the search-engine list — and every one
  of them is a grouped `SheetRowCard` (see the sheet-card rule under the
  security sheet). The 16dp gutter is
  shared too — identity headers and sheet content insets all sit at 16dp (not
  the old 20/24dp). Two-line rows (e.g. Download info) stay at 72dp. Keep these
  in lockstep; don't reintroduce a denser 48dp, a 15sp override, or a 20/24dp
  gutter for one sheet.
- **The generated mime fallback thumbnail (`MimeTypeThumbnail`) paints the
  SELECTION WASH as its ground, and a grid caption's ink FOLLOWS the tile it
  sits on.** The ground is not a resource: `groundColor(ctx)` returns
  `SelectionStyling.selectedCardWashOver(ctx, colorSurface)` —
  `colorPrimaryContainer` at 20% over the surface, the exact colour a
  selected list row's card takes (maintainer's call: "the mime thumbnail's
  background is the same as the selected item", for every row, not only
  selected ones). One derivation for both, so a selected row and its
  placeholder slot are one surface by construction, and the resting
  placeholder is the same quiet tint everywhere — list slot, grid tile,
  both themes: light `#FCE2E2` (1.17:1 / ΔE 10.8 off the page), dark
  `#402425` (1.32:1 / ΔE 18.7) — the wash's own established separations.
  Under DARK ink in light (Google's tinted-container principle — Files by
  Google, Drive, Photos — with OUR hue) and the layouts' white ink in dark.
  The glyph is the brand coral in BOTH themes (`?attr/brandInk` — 4.87:1 on
  the dark wash, 2.2:1 on the light one, under the 3:1 line floor and
  accepted: see the `brandInk` rule below for why no deeper coral survived
  on-device), capped at `MAX_FILL_ICON_DP` = 32dp (the list
  slot's own half-of-64dp, so list and grid glyphs match). Never peach,
  never grey, never Google's blue (each shipped for a commit; see below).
  **If `WASH_ALPHA` or `primaryContainer` ever move, re-measure every
  `mime_fallback_*` ink** — they are bound to that derivation.
  `generateDrawable(ctx, mime, true)` fills the slot opaque, so nothing
  behind the tile (card colour, ripple, a previous frame) bleeds through.
  **Why the wash, and what it must never go back to.** The old form composited the ~12% brand wash over
  `colorBackground`, which resolved to a pale pink in light theme that
  nobody chose and no ink fit (white 1.17:1), so the grid tile dropped to
  theme ink and lost the scrim, shadow and white ⋮ as four separate
  patches. That was replaced by ONE neutral dark literal in both themes
  (`#2E2F31`), which was forced, not chosen: a white caption pinned on the
  tile allows only a dark ground, and a dark warm ground is BROWN (the first
  literal, `#4A2120` at chroma 22, read so on-device — the same
  dark-warm-low-chroma trap as the buy-credit segments and the checked chip),
  so neutral was the only survivor. Two things were wrong with it: on the
  light page five dark slabs above the fold were the loudest element on the
  screen (12.8:1 off the page, where every other elevated surface sits at
  1.2–1.4), and the neutral was hue 272° — BLUE — which read cool beside the
  coral glyph. Letting the caption follow the tile is what made a light
  placeholder possible at all. The first light ground was peach at 40–50%
  over the page (`#FDDECE`/`#FDD7C2`) with a warm-charcoal dark (`#342C2B`,
  hue 30° at chroma 4 — the most warmth a dark ground takes before the
  brown trap and before the status ink drops under 4.5:1); it lasted a day:
  the peach and the selection wash are the same warm pastel family and read
  as two patches on a selected row, and the maintainer's answer was to make
  the ground THE wash. A bare-glyph list slot (no ground at all, the Drive
  shape) shipped for one commit in between and was not what was asked.
  **Every ink on the placeholder is measured against ITS ground, in
  `FallbackInks`** (`ui/FallbackInks`, resolved once per adapter from the
  resources): title onSurface 14.0:1 · meta onSurfaceVariant 7.6:1 · mime
  label onSurface · status `#A63D37` 5.12:1 (the brand hue darkened until it
  clears 4.5:1 on the light wash; `colorPrimary` is 2.3:1 there and 5.23:1
  on the dark wash, where the resource IS colorPrimary — so status is coral
  in both themes) · ⋮ onSurfaceVariant; dark theme's values are the layouts'
  own white set, so the flip is a no-op by value there. **Never resolve a
  theme attr for a grid caption at bind time** — the tile's ground does not
  follow the theme surface, so a surface ink is wrong on it in one theme or
  the other (the Backups `TransferVH` lesson). The photo inks are captured
  from the layout in the holder ctor (`getCurrentTextColor()`), never
  hardcoded twice.
  **ONE flag drives dim AND ink, in all three grid adapters.** A REAL
  picture gets the `bottom_scrim` + text shadow + the white set; the
  placeholder gets no scrim (a gradient over a FLAT colour is visible *as* a
  gradient — a vignette smudged across the bottom of a clean tile), no
  shadow, and `FallbackInks`. Downloads: `applyGridTileGround(holder, isGrid,
  status, realThumbnail)`, `photo = FINISHED && realThumbnail`, runs LAST in
  the bind so it owns the final ink; the PROGRESS / ERROR / QUEUED branches
  set `status_text` to `FallbackInks.status` on the grid (they all paint the
  placeholder ground — PROGRESS as a bare `groundColor(ctx)` `ColorDrawable`
  with no glyph, the ring being the focal element). **The ⋮ tint reads
  `holder.photoTile`, which the ground pass writes** — the SELECTION-PAYLOAD
  rebind re-tints the ⋮ through `setActionIcon` without re-running the
  ground pass, and a hardcoded grid-white there shipped as "after a
  multi-select round every placeholder tile's ⋮ turns white, except the one
  row that got a full bind" (on-device, light theme, after a compress).
  Any per-tile ink a partial rebind touches must read the holder's stored
  ground state, never assume the photo case. A selected LIST row needs no
  special case at all: its card takes the wash and its placeholder slot IS
  the wash, so the two merge by construction (the glyph floats on the row,
  the check sits at the far end). Captured tiles and squares
  (`BrowserOptionAdapter.applyTileGround` / `applySquareGround`): this
  adapter cannot know up
  front whether a capture's poster/frame will load, so the bind starts in
  the photo state and `GlideHelper.load(entity, options, image,
  FallbackCallback)` flips it from the Glide outcome — synchronously on the
  short-circuit-to-glyph paths, from the listener otherwise — guarded on
  `holder.boundUid` (the callback can land after a recycle). Backups
  (`FileGridVH.applyGridDim(hasPreview)`, plus a `bindThumb` fallback
  reporter for a model that fails to load; the transfer tile ALWAYS shows
  the glyph, so `TransferVH` takes the placeholder inks once in its grid
  ctor). Any new grid surface that renders the mime fallback needs the same
  flag. The grid RING (`ProgressOverlayView`) resolves `progress_indicator`
  now, not `colorPrimary` — it sits on the cream in light theme, where the
  brand is 2.1:1. History worth knowing when reading old comments: the
  "dim-only, sets no text colors" era and its "if a caption is unreadable
  the ground is wrong, not the ink" rule were the one-literal-ground design;
  the two-ink split is DESIGNED now (a measured ground per theme, inks
  measured against it, one flag), not the accidental four-patch state that
  rule was written against. The glyph history, three hues in three commits,
  each a maintainer call: grey (Firefox/AOSP-picker shape) carried no brand;
  the launcher PEACH `#FFB58A` read "too big and too yellow" — on a neutral
  ground the warm arm has no coral neighbour to read against and lands as
  orange-yellow; so coral, made a MARK rather than a subject by the 32dp
  cap. Per-TYPE hue (Drive / Files by Google) was sketched and set aside —
  worth it only as a full SYSTEM (chip glyph + tile glyph + caption glyph
  sharing the hue) across four adapters; if ever built, brand hues only.
  **The grid's top ⋮ scrim is gone** (maintainer request): the full-width
  32dp `top_scrim` gradient both grids painted behind the corner more-button
  was deleted outright. The ⋮ may wash out on rare bright artwork; that's
  accepted. If legibility ever needs fixing, use a per-icon treatment
  (small circle/shadow behind the glyph), never a full-width dim band. The
  grid cloud badge dropped its `realThumbnail` split (always the white
  shadowed `cloud_badge`) — it lives only on the DENSE bare tile, which
  never shows a caption. The **media viewer keeps the default 16:10
  letterbox** (`generateDrawable(ctx, mime)`, `fillBounds=false`, the coral
  glyph at its own larger size + the translucent 12% wash — one glyph on a
  player background, no grid to multiply it) to match `PlayerView`'s
  `resize_mode="fit"` — don't make the fill unconditional, it would paint
  the player background edge-to-edge.
- **List-row meta line is `MIME · domain` — plain text, no domain icon.** The
  Downloads list row (`fragment_download_item.xml`, `row_meta` → `mime_text` +
  `file_url`) reads `VÍDEO · youtube.com` (the Captured sheet had a twin row
  until 2026-10; it shows the Downloads TILE now). The mime label
  (`MimePrimaryLabel`, adapter appends a trailing `" · "`) doubles as the
  separator, so `file_url` follows directly with no leading margin. There is
  deliberately **no globe/favicon `ImageView`** between them — it was removed as
  decoration (identical on every row, redundant with both the domain text and
  the `·`). Don't reintroduce a domain icon here; if you ever do want a
  per-site favicon, that's a different, data-bound feature, not the old static
  globe. The third line (`duration/resolution/language · size · date`) is the
  informative density and stays — the secondary metadatum LEADS, matching the
  grid caption's `duration · size` order (they used to disagree: grid
  `3:51 · 40,1 MB`, list `40,1 MB · 3:51` — same screen, same facts, opposite
  orders). The Downloads CLOUD-BACKED mark is INLINE TEXT on the captioned
  surfaces and a corner overlay only where no text exists — the big-player
  convention (top corners belong to controls; status lives at the bottom, in
  a corner or the metadata line): the LIST row LEADS its facts line with a
  cloud glyph (`withLeadingCloud`, the Drive placement — that line opens with
  a variable-width duration, so there is no column for the mark to break); the
  GRID tile TRAILS its meta row with it (`withTrailingCloud` —
  `VIDEO · 3:51 · 40,1 MB ☁`; the maintainer's call after the top-START
  corner overlay was rejected on-device for mirroring the ⋮ and reading as
  tile chrome — and since the tags line runs most of the tile's width, its
  end IS the tile's bottom-end corner without reserving any width on unbadged
  tiles; the row stays visible for the mark alone when SORT_SIZE-under-a-chip
  drops every text fact); only the bare DENSE mosaic tile keeps the white
  shadowed `cloud_badge` overlay, at the tile's BOTTOM-END corner (the Google
  Photos placement; a bare tile has no text line to carry the mark). The
  inline glyphs are `CenteredImageSpan`s tinted to their line's own ink and
  sized to its text, cached PER SURFACE — **`ui/CloudMark` is the ONE
  implementation** (`leading`/`trailing`; the cache is keyed by the
  TextView's text size + ink, so list, grid and the Storage review list each
  get their own without knowing which cache they are). It was three private
  pieces inside `DownloadItemAdapter` until the review list needed the same
  glyph — a copy would have been the `compactDuration` drift mistake. Exactly ONE
  gutter (after a leading mark, before a trailing one) and a spoken
  contentDescription (the span is invisible to TalkBack). The glyph is a BARE
  cloud, no check mark: at text size the tick is mush, and presence already
  says "done". Also deliberately NOT coral: brand coral on the light surface
  is ~2.9:1 (under the 3:1 glyph floor — the flips-with-theme defect class),
  and a marker on ~90% of a heavy backup user's rows should be quiet, not
  brand-loud.
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
  via `BrowserOptionAdapter.durationFact`), so a private copy in one adapter
  is exactly how they drifted apart the first time this shipped.
- **A selected FILTER CHIP and a selected SEGMENT of a segmented toggle need
  DIFFERENT tones — same visual role, opposite constraint.** (The buy
  screen is GONE with the hosted Cloud Backup service, and `buy_segment_*`
  with it; the rule and its measurements are kept because the next
  segmented control beside a CTA meets the same constraint.) The chip
  (`@color/chip_checked_*`, the brand coral) sits on a list rail with no
  primary action to be subordinate to, so it can afford the full brand. The
  buy-credit duration / pay-rail segments (`buy_segment_bg`/`buy_segment_text`
  → `?attr/colorSecondaryContainer`, the PEACH arm of the triad) sat directly
  above the Continue CTA, so they could not. **Do not merge them under one
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
  - **The "−N%" savings badge set no colour of its own** (the retired buy
    screen's `durationLabelWithBadge`) and inherited `buy_segment_text`, so
    it followed the check state for free — the lesson outlives the screen. It used to
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
  `colorOnPrimaryContainer`, and the Downloads rail's style uses it —
  `Theme.FireDown.Download.Chip`, whose `chip_download` selector also
  resolves `?attr/colorSecondaryContainer` (the Captured sheet's rail,
  which used it too, is gone). Remapped rather than
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
  brand coral. (The old home shelf chips mirrored it in three tinted fills;
  the shelf is gone and the dead `home_chip_*` colours were deleted with the
  counter-glyph change below — don't resurrect them as "tonal counters".)
  The roles are what make three hues a *system* rather than more colours:
  - **primary = coral — ACTS.** The thing you press: FAB, filled buttons,
    Continue, progress, the checked filter chip. Never a passive container.
  - **secondary CONTAINER = peach — SUPPORTS.** Tonal ground behind content:
    the recovery-code box, banners. Never fills a
    button. (The home backup pill used to take it and no longer does — see
    the cloud-states rule: those two surfaces are plain
    `surfaceContainerHigh`.) **`colorSecondary` itself stays CORAL** —
    `Theme.FireDown` sets no `colorControlActivated` and no
    `android:colorAccent`, so Material3 resolves the tint of every bare
    platform widget through it (the unstyled `<ProgressBar>`s — the Backups
    stream screen's today; any checkbox/switch/seekbar/cursor added later).
    Giving it the peach turned those spinners **dark brown** on the
    since-removed Add-storage-credit screen. The peach is a *container* role;
    a token
    that tints controls is an *accent*, and the accent is the acting hue.
  - **tertiary = magenta — IDENTIFIES.** "A different kind of thing": vault /
    private, backup + sync state. Never competes with the CTA.

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
  is a darker indicator — `@color/progress_indicator`, `#DB4444` in light and
  the brand `#ff716c` in dark (which already clears it). Used by the Downloads
  in-flight row, the Backups transfer row and the grid `ProgressOverlayView`
  ring; the track stays `colorPrimary@20%` (the hosted service's credit
  meter, since removed, ran it on `colorSurfaceVariant` — the second track
  shape the value is still measured against below). Don't "fix" a low-contrast bar by
  lightening its track — that direction is capped below the bar.
  **The light value is a measured CEILING, and the light/dark gap it leaves is
  not a bug to keep chasing.** It was `#C24941` and was reported on-device as
  reading far darker/heavier than dark theme's bar. That gap is real and only
  partly closable: against its own page light's bar is **4.16:1** where dark's
  brand bar is **6.92:1**, and no value closes it, because a brand-coral
  indicator in light theme is impossible at any track lightness (the 2.88:1
  white ceiling above). `#DB4444` is as far as it goes on LIGHTNESS — the
  binding constraint is that this ONE resource meets TWO track shapes:
  `colorPrimary@20%` (#FCDEDE) at 3.38:1 and `colorSurfaceVariant`
  (#E1E2E9) at 3.30:1 (the retired meter's track, kept as the bound so a
  future gauge on that surface needs no re-measure). `#E04A45` (3.18/3.11) is about
  the limit and was rejected as too thin a margin. **The lever that IS free
  is CHROMA, and it is what fixed the "brownish" report**: `#CC524A` (the
  value before this one, C\* 56 against the brand's C\* 61) darkened the
  coral without holding its saturation, and a dark warm low-chroma colour is
  brown — the same trap recorded for the checked chip and the buy segments.
  `#DB4444` keeps the brand's hue (30°) at C\* 68 and the same L\* 51, so it
  reads as a deeper coral rather than a rust; every floor is still met. If
  it ever reads brown again, raise chroma, don't raise lightness. **Re-measure against
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
  lockstep** — Backups file and transfer tiles snapped off the off-scale
  11.5sp with it (the Captured sheet's old grid tile did too; the sheet now
  inflates the Downloads tile itself).
- **Grid tile title: hidden for self-identifying image tiles** (Downloads; the
  Captured sheet's image squares carry no title at all since its redesign,
  the same reasoning). The rule is *not* "images are clutter" — it's
  "drop the title only for the one type whose thumbnail fully identifies it."
  `DownloadItemAdapter` hides
  `file_name` in the grid **iff** `isGrid && FileUriHelper.isImage(mimeType)`
  (covers GIF/SVG) — image thumbnails *are* the content and their names are
  almost always junk slugs, so the title is ink over the picture. Everything
  else keeps it: **audio is the load-bearing case** (no real thumbnail — hiding
  the title leaves an unidentifiable mime tile), and video/subtitle/doc
  thumbnails are too weak (black frames, generic glyphs) to discriminate without
  the name. The list always shows the title. Keep this keyed on
  the **mime** (`isImage`), not on a filename-content heuristic: the old
  "name has no spaces ⇒ junk" test was removed because it misclassifies
  space-less scripts (CJK/Thai) and silently drops real titles. The mime chip
  labels the type in the UNFILTERED grid (where it varies row to row), incl.
  when the title is hidden — but it is **suppressed while a single-type filter
  chip is active** (`setMimeSuppressed`, wired to the chip rail): stamping "VIDEO" on every tile under an active
  Video filter merely repeats the checked chip. **Presentation flips
  (suppression/density/span) must land WITH the new list, never on the chip
  tap** — the requery is async, and an eager flip re-renders the OLD list in
  the NEW presentation first (on-device: the images mosaic collapsed to
  normal span-2 image tiles for a beat before the videos arrived). The
  Captured sheet (no chips now) builds its presentation INTO its items in
  the view model, so it lands with the list by construction; Downloads defers
  to the paging load-state listener (`applyPendingPresentation` on refresh
  NotLoading). The rebind-on-change is still required either way: the differ
  won't rebind items that survived a filter flip unchanged. (Captured used to *always* show the title as a pre-download
  decision surface; that was changed to match Downloads — an image tile's slug
  name is noise on the decision surface too.)
- **Images/GIF filter + grid = the dense bare mosaic** (Downloads; the
  Captured sheet's equivalent is its always-on image squares). When the images or GIF
  chip is the active filter in grid mode, Downloads switches to a denser
  span (`image_grid_dense_number`, 1.5x) of square
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
  already does this (it's banner-wrapped), and so does `BrowserOptionAdapter`.
  `WebHistoryAdapter` still uses `getAbsoluteAdapterPosition()` — that's only
  safe because its fragment sets the adapter DIRECTLY (no `ConcatAdapter`); if
  you ever wrap it in a banner, switch it to the binding position FIRST.
- **List-row selection chrome is shared across Downloads / Bookmarks / History /
  Captured / Backups / the tab ARCHIVE — keep them identical.** The pattern
  (Files-by-Google): the
  `MaterialCardView` is the row ROOT (no outer `LinearLayout`, no external
  checkmark), the selection check overlays the more/action-button's OWN slot
  (the adapter swaps them on the action-mode toggle — button `INVISIBLE`, check
  `VISIBLE` — so the slot width holds and the row never reflows), and the
  selected state paints a tonal WASH on the card via
  `SelectionStyling.selectedCardWashOver(...)` (primaryContainer @ 20%), NOT a
  stroke border. Captured (`fragment_browser_options_item_list` +
  `BrowserOptionAdapter`, before the sheet went to tiles and squares in
  2026-10) originally diverged — an external left checkmark inside
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
  - **Every piece of selection chrome — check, radio ring, grid stroke, the
    active tab's border — is drawn in ONE theme token, `?attr/brandInk`, and
    every adapter gets it through `SelectionStyling`** (`selectionInk(ctx)`,
    `checkedDrawable(ctx)`, `uncheckedDrawable(ctx)`), never by tinting its
    own drawables. `brandInk` is "the brand as a MARK", and it resolves to
    the BRAND CORAL in both themes (`@color/brand_ink` = `md_theme_primary`
    in light, `#ff716c` in dark; the incognito overlay and the Vault theme
    map it to `incognito_primary`). **The token exists for CONSISTENCY, not
    contrast**: the tick once wore `colorPrimary` while the placeholder glyph
    beside it wore the deeper progress tone, two visibly different reds on
    one row — one token makes every mark the same red by construction.
    **The 3:1 line floor is deliberately RELAXED for these marks** (2.56:1
    on the light page, 2.2:1 on the wash): two deeper corals that met it
    were tried on-device — `#CC524A` (C\* 56) and then `#DB4444` (the
    brand's own hue at C\* 68, ruling out the low-chroma explanation) —
    and the maintainer read BOTH as brown, because every coral that clears
    the floor on the pale wash sits at L\* ≤ 55 and any coral that dark
    does. A check on the wash, a ring beside a title and a 32dp glyph are
    not colour-alone signals (the wash and the tick SHAPE carry the state),
    so the brand wins there. **`progress_indicator` is NOT an alias any
    more** — a bar against its own track has no shape cue and keeps the
    deeper `#DB4444` (the bars, the grid ring, the address-bar translate
    glyph); `mime_fallback_glyph` still aliases `brand_ink`. If the marks
    ever read brown again, the answer is not a deeper coral — both deeper
    corals were tried; it would have to be a different treatment (a tonal
    disc behind the glyph, say), never a rust-red brand. History: the tick
    was `colorPrimary` in five adapters (each building `mChecked`/
    `mUnChecked` with `Utils.tintDrawable`), `progress_indicator` for one
    commit in each (the "cheap fix on adapters" the maintainer rejected),
    then this token at `#CC524A`, then `#DB4444`, then the brand. Consumers, so an audit is one
    grep for `brandInk` + `SelectionStyling.`: `DownloadItemAdapter`,
    `StorageReviewAdapter`, `WebBookmarkAdapter`, `WebHistoryAdapter`,
    `TabArchiveAdapter`, `BrowserOptionAdapter` (per bind — the Captured
    sheet inflates under the incognito overlay in a private tab),
    `BrowserTabsAdapter` (the active-tab stroke, resolved off the
    non-incognito context on purpose so the purple grid keeps its coral
    border), `CloudBackupFileAdapter` (the stroke; its two layouts carry
    `app:tint="?attr/brandInk"` on the check because it calls bare
    `setImageResource`), and the Captured square layout's check
    (`app:tint="?attr/brandInk"` — a layout tint OVERRIDES a pre-tinted
    drawable, which is why the adapter-only swap had silently not taken on
    the grid). **A new theme must define `brandInk`** (a layout reading
    `?attr/brandInk` under a theme without it fails at inflate) — it lives in
    `Theme.FireDown` (values AND values-night), `Theme.FireDown.Vault` and
    `ThemeOverlay.FireDown.Incognito`. Never add a `colorPrimary`-tinted check,
    stroke or glyph again; if a surface needs the brand as a line, it needs
    this token.
  - **The `Theme.FireDown.More.Button` style defaults `iconTint` to
    `@color/white`** — written for GRID tiles, where the ⋮ sits over artwork.
    LIST rows must override it to `?attr/colorOnSurfaceVariant`. The Backups
    list does that in XML on every item; Downloads does it at runtime
    (`setActionIcon`, which is already swapping the icon resource for QUEUED,
    with per-surface cached `ColorStateList`s); Captured does XML for list and
    runtime for grid. Three mechanisms, same result — don't "fix" one into
    another without a reason, but don't read the style default as the shipped
    colour either.

### The two media players — `Theme.FireDown.Play` gotchas

`PlayerActivity` (local file) and `CloudBackupStreamActivity` (a backed-up
object streamed + decrypted on read) share `Theme.FireDown.Play` and the same
`exo_media_viewer_controller`. Two traps, both of which shipped:

- **A playback error's snackbar carries a "Copy" action — keep it on both
  players.** `PlaybackDebugInfo.describe` builds the diagnosis from what the
  player already holds (error code + cause chain incl. MediaCodec diagnostic
  info and the decoder tried, every track's `Format` with codecs / sample
  rate / channels and the codec init data in HEX — for AAC that is the
  AudioSpecificConfig, whose signaling is what an OEM decoder trips on —
  plus file, device and build) and `copy` puts it on the clipboard. It
  exists because a release build on a device without USB debugging has NO
  other channel: the typed cause went only to logcat and the user saw one
  generic line. The snackbar is INDEFINITE for this reason — the player is
  dead anyway, and a LONG timeout raced the tap. Nothing is probed or
  fetched; it costs nothing until the error.
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
- **The local player's controller runs with `animation_enabled="false"` —
  load-bearing, don't re-enable.** `PlayerControlViewLayoutManager`'s show/hide
  animations move `exo_progress` with its OWN translation animators, separate
  from `exo_bottom_bar` (built for media3's stock layout where the timebar
  sits OUTSIDE the bar; ours nests it inside the pinned 44dp row). A
  `hideController()` across a PiP transition stranded that translationY and
  the row CLIPPED the scrubber — after PiP exit the controller showed the
  time texts but no progress bar (reported on-device). This is the same
  animation machinery the exo_media_viewer_controller.xml comments (#100–#105)
  already pinned two workarounds against; with animations off, show/hide is a
  plain visibility flip and the whole stranded-animation class is gone.
  Related gesture UX in `MediaViewerFragment.setupDoubleTapSeek`: zones are
  THIRDS (left/right seek, middle double-tap = play/pause), and seeking uses
  a YouTube-style STREAK — after a double-tap, every further tap in the same
  zone within `SEEK_STREAK_WINDOW_MS` seeks again with a cumulative "−20 s"
  badge. The streak is a fix, not polish: a bare `onDoubleTap` classifier
  consumes taps in PAIRS (3 rapid taps = 1 seek, 4 = 2), which read as
  "sometimes it only seeks 10 s". Continuation taps arrive on ALTERNATING
  callbacks (`onSingleTapUp` for odd taps, `onDoubleTap` for even), so both
  handlers feed `continueSeekStreak()` — keep both wired.
- **A raw file path becomes a Uri via `Uri.fromFile()`, NEVER
  `Uri.parse(path)`.** parse() treats the string as ALREADY-ENCODED, so a
  `%` in a filename acts as an escape introducer: "4% Of" decodes through
  `Uri.getPath()` into U+FFFD replacement chars and media3's FileDataSource
  throws FileNotFoundException("Invalid file path") on a file that exists
  (`#` would truncate the path as a fragment marker the same way). Shipped:
  a YouTube title containing '%' was unplayable on EVERY device, and the
  hunt went through codecs (AV1), filename sanitizing, and a real-but-
  unrelated SABR truncation before the log's mojibake path
  (`4���Of`) named it — `%` is legal in filenames on every filesystem this
  app writes, so the sanitizer rightly passes it through. Fixed in
  MediaViewerFragment + FrameGrabberFragment + GifMakerFragment (all three
  had the identical line). The diagnostic tell for this class:
  ERROR_CODE_IO_FILE_NOT_FOUND on a file the Downloads list shows, with
  replacement characters in the logged path.
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

### Background playback (local player) — `PlaybackHub` + `PlayerPlaybackService`

Playing a downloaded file keeps playing with the screen off / activity
backgrounded. Architecture: the ExoPlayer stays OWNED BY `MediaViewerFragment`
(no MediaSessionService refactor); a `mediaPlayback` FGS
(`phone/player/PlayerPlaybackService`, modeled on `GeckoMediaPlaybackService`)
attaches to that player via the main-thread singleton
`phone/player/PlaybackHub` and shows the MediaSession/MediaStyle notification
(notification id `PLAYER_MEDIA_ID`, distinct from browser media's `MEDIA_ID`
so both can notify at once). Invariants, each load-bearing:

- **PiP ↔ background is ONE coordinated contract, not two features:** Home
  while playing (screen on) → **PiP** (the on-screen continuation,
  onUserLeaveHint), with background as the FALLBACK when PiP entry is
  denied; screen off / lock — from fullscreen OR from inside PiP → the
  **background service** (the off-screen continuation); **PiP closed with
  X → the session ENDS** — no background arm, no orphan notification;
  notification tap → reopen + ADOPT. Exactly one continuation mode is ever
  active, and the modes hand off (PiP → screen off → background → tap →
  fullscreen → Home → PiP). PiP dismissal (X button AND drag-to-dismiss)
  needs THREE defenses because OEMs disagree on the teardown ordering: an
  `mPipTeardown` flag (set on onPictureInPictureModeChanged(false), cleared
  on onResume — an EXPAND passes through the same window but always
  resumes) gates the arm; the **in-PiP interactive-display gate** — while
  `isInPictureInPictureMode()`, the arm additionally requires
  `!PowerManager.isInteractive()`, because Samsung One UI's DRAG-TO-DISMISS
  delivers onStop still in PiP, not finishing, with no mode-change(false)
  beforehand (byte-identical to screen-off-in-PiP) and then destroys the
  activity WITHOUT the finishing flag — the display state is the only
  OEM-proof discriminator (a dismissal gesture necessarily happens with the
  screen ON; screen-off is the one legitimate in-PiP background arm); and a
  FINISHING `onDestroy` sweeps any service that still raced through (never
  on a system destroy — that's the case background playback must outlive).
  Without these, dismissing PiP left audio + a notification behind, and
  tapping it reopened a torn-down session that restarted from zero.
- **The decision lives in `PlayerActivity.onStop`, BEFORE `super.onStop()`** —
  FragmentActivity's super dispatches the fragments' onStop, and
  `MediaViewerFragment.onStop` stops the player unless
  `PlaybackHub.isBackgroundActive()` is already armed. Finishing paths (back
  press, PiP X-close) never arm it, so they keep the immediate-stop behavior.
  `onStart` disarms + stops the
  service AFTER `super.onStart()` (see the adopt ordering below).
- **`MediaViewerFragment` has deliberately NO onPause pause any more.** The
  old unconditional pause is what killed screen-off playback, and it also
  paused on dialogs/split-screen. "Another app started playing" is covered
  properly now: the player is built with
  `setAudioAttributes(..., handleAudioFocus=true)` +
  `setHandleAudioBecomingNoisy(true)` + `setWakeMode(C.WAKE_MODE_LOCAL)` —
  media3 runs the whole focus machine, so `PlayerPlaybackService` carries
  NEITHER the audio-focus state machine NOR the noisy receiver its Gecko twin
  hand-rolls. Don't re-add either there.
- **`onStart` re-`prepare()`s an IDLE player.** `onStop`'s `stop()` parks the
  player in IDLE with position + playWhenReady retained, and before this
  feature NOTHING re-prepared — returning after screen-off showed a dead
  player. The guard (`getPlaybackState() == STATE_IDLE`) makes it a no-op on
  first launch and on the background-playback path.
- **Ownership: exactly one release, ever — and it is an owner IDENTITY, not
  a flag.** The fragment owns `release()`; when it is destroyed while
  background playback runs (system reclaimed the backgrounded activity), it
  `markOwnerDetached()`s instead of releasing and the service releases in its
  own onDestroy. The notification tap reopens `PlayerActivity` (singleTask →
  onNewIntent) and the fresh fragment **ADOPTS** the live player
  (`PlaybackHub.adopt`, matched on file path) instead of layering a second
  player over the audio — skipping only
  setMediaSource/prepare/setPlayWhenReady; the poster still self-hides
  because `onRenderedFirstFrame` re-fires per new surface. The activity's
  onStart stops the service only after super has run that replacement, so
  the service's onDestroy sees ownership re-attached and leaves the player
  alone. **The hub records WHICH fragment owns release duty
  (`attach(player, entity, owner)` / `isOwner`), because the replace runs
  with `setReorderingAllowed(true)` — the NEW fragment can come up (adopt +
  attach) BEFORE the OLD one's onDestroy runs.** Without the identity, the
  old teardown marked the just-adopted player owner-detached and the
  service's shutdown released it under the live UI — every control click
  after that hit media3's dead internal thread ("sending message to a
  Handler on a dead thread", shipped bug: background → pause in
  notification → tap notification → play dead). The old fragment's
  teardown branches on `hub.player() == mExoPlayer`: same player + not
  owner = adopted by the successor (hands off entirely); different player =
  the successor built its own (foreground switch to another file), so this
  one is unregistered and MUST still be released or it leaks and keeps
  playing. The fragment's Player.Listener is a FIELD so the transfer can
  `removeListener` without releasing (an orphaned inline listener would leak
  the activity).
- **Leak audit invariants (each fixed a real hole — re-verify all three if
  you touch the lifecycle):** (1) the ExoPlayer and its
  `DefaultDataSource.Factory` are built on the APPLICATION context, never
  `mActivity` — the Builder's components retain the raw context, and this
  player by design outlives the activity (static hub + service), so an
  activity-context build pins the destroyed PlayerActivity for the whole
  background session. (2) `PlaybackHub.attach` RELEASES a detached
  predecessor player it supersedes (different player + `sOwnerDetached`):
  in the background-A-then-open-B flow with the old fragment tearing down
  first, the old player is owner-detached and attach resets the very flag
  the service releases through — without the attach-release nobody ever
  frees A. A living owner's player is deliberately not touched (its own
  fragment's orphan branch releases it, either transaction order). (3) The
  `backgroundActive` DISARM lives in `stopPlaybackAndSelf` (notification
  dismiss / task removed / ENDED / null-player start), `clearIfHolds`, and
  the activity's onStart — deliberately NOT in the service's onDestroy: a
  rapid resume→re-background cycle processes the previous instance's
  destroy AFTER the new session armed, and a blanket disarm there left the
  new session running unflagged (a later fragment reclaim then released the
  player under the live service). STATE_ENDED routes through
  `stopPlaybackAndSelf` for the same reason — an armed flag surviving the
  service means a later reclaim marks the player detached with no service
  left to release it. `startForeground` is guarded (Gecko-service stance):
  promotion denied → pause + stop, never crash or play unprotected.
- **Vault (safe/encrypted) entries are excluded on purpose**
  (`isBackgroundPlaybackEligible`): background playback puts the file NAME on
  the lock screen and shade, and vault content is device-auth gated — same
  trust-domain contract as the backup mirror and P2P send. They keep the old
  stop-on-background behavior.
- Notification X / swipe-from-recents pause playback and stop the service
  (the paused player stays in memory, so returning resumes at position);
  playback ENDED stops the service by itself. `CloudBackupStreamActivity`
  has its own player and is NOT covered — a backed-up stream still stops in
  background (candidate follow-up, same hub pattern would apply).

### Subtitles in the local player — side-loaded downloads, one sheet, add a file

The local player (`MediaViewerFragment`) shows subtitles: the downloaded
subtitles that belong to the video are attached as side-loaded tracks, the
toolbar's **Subtitles** button (`action_subtitles`, video only) opens
`SubtitleSheetDialogFragment` (Off, every text track, "Add a subtitle file"),
and tracks the video file itself carries (mkv/mp4 text streams, which
`DefaultExtractorsFactory` already transcoded) are listed beside them as
"In the video". Before it the player never attached a subtitle and had no
subtitle UI at all: a YouTube video and its downloaded captions could not
be played together (the report that started it). Rules:

- **Matching is by PAGE, showing is by evidence.** Candidates are the
  finished downloads with the video's `file_origin_url` (the YouTube watch
  URL both the video and its caption tracks carry) or linked to it by
  `file_parent_id` (set when a file is added from the player; the column
  was unused for downloads) — `DownloadDao.findPlayerCompanions`, LIMIT 64,
  same vault side only, encrypted rows never. Every subtitle candidate is
  listed in the sheet, but one shows BY ITSELF (SubtitleConfiguration
  `SELECTION_FLAG_DEFAULT`) only when it surely belongs to this video:
  linked, or its name is the video's name plus a language tag ("Title
  [en].srt" beside "Title.mp4" — `PlayerSubtitles.stem` folds the tag and
  " (2)" counters), or no other video came from that page. A news page with
  five embeds would otherwise put one clip's captions over another. Among
  those, `pickDefault` takes the YouTube picker's order: the phone's
  language written by a person, then its speech-recognition track, then any
  written track, then the first — downloading a subtitle is asking to see
  it, so another language still shows when it is all there is.
- **The video waits for the lookup and is prepared ONCE.** `onViewCreated`
  sets the player, then `loadMediaWithSubtitles` queries on a worker thread
  and `setMediaWithSubtitles` hands over the item; preparing first and
  re-setting the source after would restart the extractor under the first
  frame. It prepares only while the fragment is STARTED (a lookup landing
  after onStop must not start background playback); `onStart` prepares
  otherwise and skips a player with no media yet
  (`getMediaItemCount() > 0`). Audio files skip the lookup. A generation
  counter drops a lookup overtaken by a newer one.
- **One media path: `DefaultMediaSourceFactory(DefaultDataSource, extractors)`**
  with the item's mime pinned to `application/octet-stream` so it always
  takes the progressive source. It parses each subtitle file during
  extraction (media3 1.11's default — the legacy in-renderer decoding is
  disabled, so never hand-build a `SingleSampleMediaSource`) and merges it
  with the video. `DefaultDataSource` reads a `file://` uri through the same
  `FileDataSource` the player always used and also speaks `content://`,
  which a restored subtitle needs next to an owned video.
- **The sheet reads the PLAYER, not fragment state.** Options come from
  `getCurrentTracks()`; a side-loaded track's id is `fdsub:<file name>`
  (`MergingMediaPeriod` prefixes `n:`), which gives the sheet its detail
  line and lets an ADOPTED player's new fragment (notification tap) list
  the same tracks with no lookup. Picking a track is a
  `TrackSelectionOverride`; "Off" disables the text type.
- **"Off" is remembered** (`Preferences.PLAYER_SUBTITLES_ON`, default
  true): on the next video the DEFAULT flag is ignored
  (`setIgnoredTextSelectionFlags`), so nothing shows by itself while forced
  subtitles and the system caption-language setting still apply. Picking a
  track turns it back on.
- **"Add a subtitle file"** pauses first (a playing player reads the picker
  as leaving: PiP on the leave hint, background playback on onStop), opens
  `OpenDocument` with a mime LIST (providers disagree on what a .srt is; a
  picker that ignores the list shows everything), checks the file by name,
  provider mime, then its first bytes (`PlayerSubtitles.sniffMime`), and
  then: a file that is already a download (phone storage / Downloads
  provider ids map to paths) is LINKED; anything else is copied next to the
  video as "<video name> [lang].srt" (counter before the tag, so the tag
  stays last) and inserted as a FINISHED row with `file_parent_id` = the
  video, `file_url` = the file itself (the page URL belongs to the video's
  capture, and Captured ties downloads to captures by URL), origin = the
  video's. The worker waits for the row write before the reload, which
  restarts at the current position and selects the new track
  (`mPendingSubtitleId`, applied in `onTracksChanged`). Not offered for a
  file opened from outside (no Downloads row to link to) or an encrypted
  vault file. Capped at 8 MB.
- The sheet is a plain `BottomSheetDialogFragment` in the fragment's child
  manager (PlayerActivity is not a BaseActivity and has no NavHost), asks
  its parent (`Host`) for options whenever its view is built, and is
  dismissed on entering PiP.
- Verify with `sh scripts/player-subtitles-harness/run.sh` (the REAL
  `PlayerSubtitles`, JDK only, 40 checks; three mutants must fail: a
  subtitle that always belongs, a pick blind to the phone's language, a
  stem that keeps the tag). The media3 calls were type-checked against the
  1.11.1 source; playback itself is on-device only. The same harness runs
  `PlaybackSpeeds` (next section): 51 checks, a fourth mutant (0.75× written
  as one decimal) must fail.

### Playback speed — a toolbar button and a radio sheet, in both players

The local player and the backup stream player have a **Playback speed**
toolbar button (`action_speed`, `speed_24`, ifRoom) opening
`PlaybackSpeedSheetDialogFragment`: one radio card per speed in
`PlaybackSpeeds.SPEEDS` (0.25× to 2×, "Normal" for 1×), the Subtitles
sheet's shape and row layout. Asked for by a user who wanted 0.75× and 0.5×;
media3's stock controller has a speed row in its settings menu, and the
app's own controller layout leaves that menu out. Rules:
- **The speed lives on the PLAYER, nowhere else**: `setPlaybackSpeed`
  (pitch kept, media3's default). So it lasts while that file plays —
  rotation (config changes are handled), PiP, background playback and an
  adopted player keep it — and a new file starts at normal speed. Not
  saved as a preference on purpose: a speed that carries into the next
  video is invisible state that reads as a broken player.
- **Shown for video and audio** (a podcast at 1.5× is the common case);
  hidden for images. On a ~360–411dp phone the toolbar holds three
  buttons and Share is `always`, so on a video with PiP and Subtitles the
  speed button lands in the ⋮ overflow, with its label. Don't fix that by
  pinning it `always` (the slot rule under the Downloads toolbar).
- **The sheet's Host is the fragment that showed it, else the activity**:
  MediaViewerFragment shows it in its child manager (dismissed with the
  Subtitles sheet on entering PiP, `dismissPlayerSheets`);
  CloudBackupStreamActivity, which has no fragment, shows it in its own
  manager and is the Host. Its button appears once a stream has a player.
- `PlayerPlaybackService` refreshes the session on
  `onPlaybackParametersChanged`, so the lock-screen scrubber runs at the
  chosen speed.
- The labels are `NumberFormat` of the locale plus "×" (`0,75×` in
  German); the speed list and the labels are pinned by the harness above.

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
  switches CLAUDE.md itself says "most users should never touch". The
  **fingerprinting pair (Resist fingerprinting + UTC timezone) lives behind
  this door too** — default-off, opt-in, breaks real sites, exactly the class
  the door's "at the cost of some site compatibility" summary names. (Disable
  Safe Browsing stays on the root: its motivation is privacy — stop sending
  hash prefixes to Google — not hardening, so it belongs in Privacy.)
- **Direct share is a DOOR at the end of Downloads** (`SETTINGS_P2P_SCREEN` →
  `DirectShareFragment` + `settings_direct_share.xml`): the STUN chooser + TURN
  editor moved verbatim (they were raw `stun:` URLs on the root). Lives with
  Downloads because P2P share is a Downloads feature.
- **The Cookies category was dissolved into Privacy** (cookie policy row +
  Delete browsing data, destructive action last) — a 2-row category was
  taxonomy noise; cookie policy IS privacy (Fenix's grouping).
- Deliberately NOT full Fenix-style nesting (root = only doors): the project's
  own precedent is anti-tap-tax (the Backup screen was UN-nested because
  reaching the hosted service's plan took 4 taps). Nothing moved more than ONE level down.

**A link row in Settings opens its page through
`SettingsActivity.openInBrowser(activity, url)`, never `setResult` +
`finish` by hand** (Help, Donate, GeckoView, the Backup screen's "How sync &
encryption work"). Handing the URL back as the activity result works only
when the BROWSER opened Settings for a result (the home and browser popups,
whose result callback passes it to the intent handler). Most doors open
Settings with a plain `startActivity` (the Bookmarks overflow and sync
banner, Downloads, Storage, the tab settings, the backup notifications), and
there the result reaches nobody: the row just closed Settings. It shipped
that way: Home → Bookmarks → ⋮ Sync bookmarks → "How sync works" returned to
Bookmarks with no page. `openInBrowser` keeps the result for a browser
caller (`getCallingActivity()`) and otherwise starts `BrowserActivity`
(singleTask) with the same `OPEN_URI` intent, which clears the activities
above it and opens the tab in `onNewIntent`.

**Sub-screens must apply their prefs to Gecko THEMSELVES** — SettingsFragment's
SharedPreferenceChangeListener is unregistered while a sub-screen is
foreground (the `WasmFragment` pattern). `SecurityFragment` carries its own
listener with the JS/JIT/DRM/WebGL/RFP/UTC-timezone branches; SettingsFragment
keeps its matching branches as the defensive twin (only one listener is
registered at a time, so no double-apply). **Keep the two in lockstep** — a semantics change
in one without the other makes the toggle behave differently depending on
which screen flipped it. Door keys are click-rows (`SETTINGS_SECURITY_SCREEN`,
`SETTINGS_P2P_SCREEN`); the underlying toggle/pref KEYS are unchanged, so no
migration. Door titles reuse the old category strings
(`if_preferences_security`, `settings_p2p_category`) — already translated,
zero new locale work.

The **Firedown category** at the end of the root is five bare title-only rows:
Share Firedown / Send feedback / Donate / Help / About (feedback right under
Share Firedown, the maintainer's placement; it sat beside Help for one build).
- **Licenses lives INSIDE About** (`settings_about.xml` +
  `action_about_to_license`), not on the root — the Fenix pattern (About
  carries the legal links); the About screen was otherwise a thin two-row
  destination (version + Gecko build). Key unchanged, no migration.
- **"Share Firedown" is deliberately NOT shortened to "Share"**: Downloads
  already has a "Direct share" row (the P2P file share), so a bare "Share"
  would make share-the-app vs share-a-file confusable. The object in the
  title is disambiguation, not padding. Same reason the row wears the
  `qr_code_24` glyph (ShareAppFragment is QR-centric) rather than
  `ic_share_24`, which the Direct share door wears.

### Settings cards — the sheets' grouped cards, one adapter for every screen

Every settings screen renders its rows as the bottom sheets' grouped cards
(the security-sheet shape: 16dp outer / 4dp inner corners, 2dp between rows,
`SheetGroups.shape` + the `sheet_*` dimens), through ONE adapter —
`BasePreferenceFragment.onCreateAdapter` returns `CardPreferenceGroupAdapter`
— never a layout per screen. Sketched and approved on the design canvas
(2026-10) against Brave's grouped settings. The rules, each easy to undo:

- **A group is a run of consecutive card rows in ADAPTER order.** A
  `PreferenceCategory` ends one: a titled category is the grey label above
  the next group (`preference_category_card.xml` via the theme's
  `preferenceCategoryStyle`), a TITLE-LESS category is a bare 16dp break (the
  Backup screen's three unlabelled groups). A `FlatPreference` also ends one
  and keeps its own full-width layout: the Backup hero + CTA, the tracking
  count, the DoH server field, and `FootnotePreference` (the summary-only
  disclosure lines — translations' Mozilla-download notice, the tab archive's
  retention note), which sit flat under the cards like an Android Settings
  footer. Card-vs-flat is decided per CLASS, because view types are keyed by
  layout + class: a holder never has to undo the other kind's margins.
- **Corners follow the NEIGHBOURS, so a visibility toggle must rebind rows
  that did not change.** The stock adapter answers every hierarchy/visibility
  change with `notifyDataSetChanged` (it only diffs when a
  `PreferenceComparisonCallback` is installed — none is), and the adapter's
  own observer rebinds the whole list on any insert/remove/move, so the
  settings twin of "toggle, then `applyCorners`" needs no call at the call
  site. Don't install a comparison callback without keeping that observer.
- **The card is a background, not a view.** Margins (the 16dp gutter + the
  2dp gap as the TOP margin; bottom margin ZERO — the first build passed the
  gutter as the bottom margin and shipped every row 18dp apart, floating
  tiles instead of one block, caught on-device against the sheet) go on the
  item's `RecyclerView.LayoutParams`; the row's start/end padding is set to
  the sheet row's 16dp explicitly, because the stock row's
  `?android:listPreferredItemPaddingStart` measured ~12dp and put icons and
  titles ~4dp left of the sheet rows'; the background is a
  `RippleDrawable` over a `MaterialShapeDrawable` with NO mask layer, so the
  opaque shape masks the ripple and a press never spills past the corners.
  `PreferenceViewHolder.resetState()` restores the inflated background before
  every bind, so the card background is set on EVERY bind (the
  `settings_card_background` tag only saves rebuilding the drawable). The
  stock 56dp icon frame is tightened to 40dp in cards (title 72dp from the
  screen edge — exactly where a flat footnote's text starts with the stock
  frame, so footnote and rows align).
- **Tinted cards, page unchanged.** Cards are `colorSurfaceContainerHigh` on
  the existing page tone in all three themes (OLED's overlay gives
  `#1C1C1F` on black). "White cards on a grey page" (Android 16's light
  shape) was sketched and NOT chosen: it changes the page colour behind the
  Settings toolbar, and the activity's non-preference pages (feedback,
  share) would then sit on a different ground than the lists
  beside them.
- **Labels and icons stay grey.** Category labels are `colorOnSurfaceVariant`,
  not the old coral `colorAccent` (brand coral is ~2.3:1 on the light page,
  under the 4.5:1 text floor, and the cards now do the grouping); row icons
  keep the existing `tintIcons` grey — Brave's peach icons were sketched and
  would put ~30 coral glyphs on one screen. The stock hairline dividers are
  off (`setDivider(null)`); the card gaps replace them.

## Storage screen — Signal-style per-type bar (Downloads overflow)

Downloads ⋮ → **Storage** (`StorageFragment`, `nav_graph_downloads`
`@id/storage`): headline total, device free space, a stacked per-type bar
(`ui/StorageBarView` — one rounded pill, 2dp surface gaps, 4dp minimum per
non-zero segment) and a labelled legend. Data is ONE grouped query
(`DownloadDao.getRegularUsageByMimeLive` — finished, non-vault, GROUP BY mime,
so it stays a handful of rows); the mime→type fold is in Java with the SAME
predicates as the Downloads filter chips, so a row's size is exactly what its
chip then lists. The screen is a DOOR, not a second file browser: a legend row
returns its chip id through `IntentActions.STORAGE_FILTER` on the Downloads
entry's SavedStateHandle (`BaseDownloadFragment` resume observer →
`applyStorageFilter`).

**"Review storage" opens a REVIEW LIST (`StorageReviewFragment`, nav id
`storage_review_list` — the button keeps `storage_review`), never a re-sort
of the Downloads list.** It shipped as "persist `SORT_SIZE` as the user's
Downloads sort preference and pop back", which on-device read as "the button
does nothing but go back" (a user already sorted by size saw literally
nothing) and silently rewrote a preference the button never mentioned. Every
comparable storage screen has a purpose-built triage surface instead —
Signal "Review storage", WhatsApp "Larger than 5 MB", Files "Large files",
iOS "Review Large Attachments" — and they share one shape, which this is:
finished non-vault downloads whose file is really on disk (the offload
scan's rule), LARGEST FIRST; **selection is the resting state** (the check is
in the row's trailing slot from the first frame, the row is the tap target;
select-all in the toolbar); the toolbar subtitle carries the running
"N selected · 1.2 GB" (else "N files · total"); and ONE verb, a bottom-bar
filled "Delete N files · 1.2 GB" that is **GONE at zero and slides in on the
first tick** (`showBar`). Rows are their own layout, `item_storage_review`,
bound by `StorageReviewAdapter` (a plain `ListAdapter`, NOT
`DownloadItemAdapter` — one state, one presentation, one job) reusing the
Downloads row's PARTS: `GlideHelper.load`, `DownloadItemAdapter.domainLabel`
(made static for it), the inline cloud mark on the facts line so "safe to
delete" is visible at the decision point, and the **SHARED selection chrome
verbatim** — the coral check-circle / ring (`ic_baseline_check_circle_24` /
`radio_button_unchecked_24` tinted `colorPrimary` via `tintDrawableColor`,
the same pair `DownloadItemAdapter` builds) in the same 24dp trailing slot the
Downloads ⋮ occupies, plus the `SelectionStyling` wash. **The SIZE is its own
right-aligned column at title weight**, between the text and the check — the
row's loudest number, because it is the sort key and the decision; the facts
line is `[cloud] duration · date`. The confirm dialog COUNTS the backed-up
files ("N of them are also in your backup folder and stay there") rather than
implying every file is. Delete is the normal `TaskRepository.requestDelete`
in 50-entity chunks, optimistic (rows leave now, the resume re-scan
reconciles). **History (on-device, two rounds):** it first shipped as the
Downloads row verbatim — the size as the first token of the 11sp facts line
(read as a Downloads list rather than a size list) and a permanently docked
disabled Delete (~70dp saying nothing). The second cut over-corrected: a
`MaterialCheckBox` on the LEFT in the Files-by-Google shape, argued from "the
empty ring reads as a radio button" — but the Downloads long-press list uses
that same ring and is never misread (the filled check-circle is Google
Photos' multi-select glyph), and side by side the two screens read as two
apps. The check went back to the shared slot; the size column and the
on-selection bar are what the review shape earned. **Don't diverge a list
row's selection chrome again for one screen** — the shared rule under UI
conventions applies to a list whose selection is permanent as much as to one
that enters action mode. Not built: a type
chip rail (the legend rows are that door) and WhatsApp's "forwarded many
times"-style secondary buckets. GIF/subtitle/APK/archives fold into a neutral grey,
non-tappable "Other" — a residual bucket, never extra hues. The `storage_*`
colours were VALIDATED as a set per theme (dataviz `validate_palette.js`);
coral+peach and coral+teal fail colourblind separation — re-run it before
changing one. The vault is excluded on purpose (this screen isn't auth-gated;
the footnote says so).
**Two doors, one screen: the Downloads overflow and Settings → Downloads →
Storage** (`SETTINGS_STORAGE_SCREEN`, a click-row → `DownloadsActivity` with
`EXTRA_OPEN_STORAGE`, which pushes `R.id.storage` over the list on a FRESH
start only — a rotation restores the back stack itself). The Settings row
exists because every other app keeps storage in Settings and a user looking
there found nothing; it stays a hop into the Downloads graph rather than a
settings-graph twin, because the screen's legend taps hand a filter back to
the Downloads list. Keep it a door in both places, never a copy.

**"Free up space" (Signal's Optimize storage) lives on the same screen.** An
outlined card, shown ONLY when some finished downloads are already in the
backup folder: it removes their PHONE copies through the normal download
delete (`TaskRepository.requestDelete`, chunked 50 per intent against Binder
limits) and they stay in Backups (stream / restore). Candidates = the
folder's content keys (`CloudBackupManager.loadBackedUpKeys`, the name+size
match the Downloads cloud mark uses) ∩ downloads whose file is really on
disk (a missing or foreign-owned file frees nothing). Two load-bearing
safety rules: the card appears only on POSITIVE evidence (an unreachable or
not-set-up folder answers the empty set, never "backed up"), and the folder
is RE-LISTED at confirm time — only confirmed ∩ still-backed-up is deleted,
so a backup removed meanwhile (another phone on the same folder, the Backups
list) can't turn this into deleting a last copy, and an unreachable folder
deletes nothing (`storage_offload_unverified`). The confirm names the
provider (`storage_offload_confirm_message_on`); there is no metered wording
any more — the folder is the user's own storage and nothing expires. **The
card paints CACHED-FIRST**: `CloudBackupManager.cachedBackedUpKeys()` is the
key set of the last SUCCESSFUL listing, session-lived like `lastStatus()`
and kept in step with the manager's own mutations (`deleteEntries` removes
its keys, `deleteAllData` / `forgetCachedStatus` drop it). `refreshOffload`
scans the cached keys at once (the card lands with the rest of the screen
instead of a listing beat later — the reported "takes time to load") and
the fresh listing then updates it in place under the same generation; the
Downloads cloud marks and the review list mark rows from it the same way. A
cache hit is positive evidence from a real listing; what it cannot see is a
change made elsewhere (another phone on the same folder), which the re-list
covers — and the CONFIRM path never reads the cache, it still acts only on
the fresh answer. Don't persist the keys to disk: a folder edited from
another phone would then paint stale marks across process death with no
listing to correct them until the folder answers, and `loadBackedUpKeys`
still answers a FAILED listing with the empty set (so with the folder
unreachable the cached card can show and then hide — honest, and the
confirm would delete nothing anyway). Deliberately NOT built: Signal's
in-place placeholder (the Downloads row survives as "in backup", tap to
fetch) — that needs a new download state every file-open path would have
to honour; the Backups list already is the folder-only view.

**The Storage screen ALSO carries the Backup UPSELL for a user without it —
Signal's "Save space with backups" card, placed where the pain is.** The
rule used to be "never a pitch for users without Backup (the Downloads
banner owns that)"; it was revised on the big-player comparison: Signal
puts its upsell on ITS storage screen, the one you open because the phone
is full, which is when "back it up, then free it" is an answer rather than
a pitch. `storage_upsell_card` shows iff `!isSetUp()` AND the finished
non-vault total is > 0 (`refreshUpsell`, read live on every render and on
resume, so choosing a folder and coming back swaps it for the offload
card); not dismissible (a screen visited on purpose, not a feed); tap → the
Backup screen. The Downloads list banner keeps its one stage unchanged.

**"Restore all to this phone" is the counterpart — Signal's "Download your
backup data" — and it is why the offload confirm can stay honest.** Signal's
Optimize-storage toggle promises media "can be downloaded from your backup
anytime" and makes good on it by AUTO-downloading offloaded media when the
subscription ends. Firedown has no auto path, and no subscription to end —
the risk now is the FOLDER going away (a provider signed out, a card
pulled, a Drive account closed) — so a batch **restore-all** carries it:
`CloudBackupListFragment.confirmRestoreAll` restores every FOLDER-ONLY
entry (`mCloudOnly` ∩ not already restoring — positive evidence only, so a
failed lookup hides the action rather than restoring files already
present), reachable from the Backups overflow (`action_restore_all`,
visible only when it would do something). Unlike the selection batch
restore it IS confirmed — gigabytes the user did not pick — and the dialog
states count, total and free space (`StatFs` on the download path); when it
does not fit the positive button is DISABLED with the shortfall stated
(Signal's "Free up %s on this device"), because N restores dying on a full
disk would be N unexplained ERROR rows in Downloads. The hosted service's
grace card (RESTORE · TOP UP) and its `EXTRA_RESTORE_ALL` deep link into
this went with the service: a folder has no grace period to count down.

**There is no account WATCH any more.** The hosted service's
`CloudWatchWorker` (a 6-hourly periodic worker that read the metered quota
and posted the lapse notifications — grace entered, reap in 3 days) is
GONE: a folder has no balance to run out and no reap to warn of. What
replaced it is event-driven and local: a backup that finds the folder gone
posts ONE folder-lost notification (see the Backup section), and the Backup
screen's hero shows the lost state. A periodic WorkManager request outlives
the class that scheduled it, so `CloudBackupManager.cancelLegacyWatch()`
cancels the `cloud-watch` unique work once at boot (`App.onCreate`) — keep
that call until no install from before 1.2.01 can plausibly remain.

## In-app donations RETIRED — "Support Firedown" is a website handoff

The native Value for Value donate screen (`DonateFragment` + the `donate/`
package: `LightningInvoiceFetcher`, `BitcoinAddressProvider`, Lightning
invoice/BTC QR plumbing, `fragment_donate.xml`, 8 donate-only drawables,
~26 `donate_*` strings) was **removed entirely** (maintainer decision). At
the time the app's ONE money surface was the paid cloud-backup credit flow
(anonymous blind-signature credits, Lightning + on-chain Bitcoin rails —
itself gone with the hosted service in 2026-10; the app sells nothing now)
— a donate screen beside a purchase screen was two competing money-asks
(donors feel they "already paid"; would-be customers donate instead of
buying credit), and the donate plumbing shared nothing with the credit
flow's rails, so it was pure extra surface. Before this, the fiat "Card
or PayPal" (Buy Me a Coffee) card had already been dropped from the screen.

What remains: a **"Donate" row** (`settings_donate`) in Settings' app category
(key unchanged — `Preferences.SETTINGS_DONATE`, a click-row so no
key-inversion issue) that opens `settings_donate_url`
(https://firedown.app/donate — /support is the HELP page, not donations)
in a Firedown tab through `SettingsActivity.openInBrowser`, like the
Help row. Title-only, NO summary — it sits in the app category
beside License/Help/About, which are all bare rows, and a lone summary
read as out of place there (on-device review). Title translated across
the 16 maintained locales. **The website must serve /donate** — the donate
rails (LN address, BTC, fiat) live there now (firedown-website repo), not in
the APK. Don't reintroduce an in-app donate/payment screen; if a donation
surface ever returns, it's a website page.

**The occasional donation sheet (`DonationPromptSheetDialogFragment`,
2026-10) only ASKS; Donate opens that same page.** `ill_forest` (the child
holding out a flower, shared with the notifications priming sheet; `ill_kid` is in
the repo but not used yet), "Support Firedown", one sentence, Not now / Donate (`translate_not_now`, `settings_donate`). Shown
by `DownloadFragment.checkDonationPrompt` as a `<dialog>` of
`nav_graph_downloads`, when `donation/DonationPromptPolicy` (pure Java) says
it is due: installed for 21 days, 15 finished regular downloads, one of them
finished in the last hour (`DownloadTask.onRunComplete` stamps it, vault
downloads never), 182 days since the last ask, 3 asks in all, never after a
Donate tap, here or on the Settings row (both `markDonated`). And the screen
must be free: the resting Downloads list (no selection, no search, no
restore running), nothing else on screen (the crash and update sheets sit
outside the nav graph, hence the fragment-manager scan), online. An ask is
counted when it SHOWS, so a sheet killed under the user still counts. The
state lives in `backup_local.xml` (excluded from backup: a new phone starts
over) and the install date is the package's `firstInstallTime`; nothing
is sent. A title with the user's own download count was sketched and
dropped (it reads as "you owe us"). Verify with `sh
scripts/donation-harness/run.sh` (the real policy on a JDK, 19 checks incl.
five years of Not now = 3 asks; mutants without the age gate, the recent
download, the gap, the cap or the Donate tap each fail). On-device
unverified at write time.

## Prebuilt native dependencies — GitHub Releases, pinned, fetched at sync

The app links two things it does not build: the FFmpeg `.so` set
(firedown-ffmpeg, `external/ffmpeg/`) and the patched GeckoView AAR
(firedown-geckoview, `~/.m2` via the `exclusiveContent { mavenLocal() }` pin
in `settings.gradle`). Both are compiled on ONE box (hours; Docker for Gecko)
and used to exist nowhere else — a second machine could not build the app at
all. Now they travel as **GitHub Release assets of their own repos**, pinned
here and fetched on demand; the whole scheme is one script per side plus two
lines of `gradle.properties`. `docs/NEW-MACHINE.md` is the checklist.

- **Pins live in `gradle.properties`, nowhere else:** `firedown.ffmpegRelease`
  (a firedown-ffmpeg tag, `v<ffmpeg-version>-<n>`) and
  `firedown.geckoviewVersion` (the Maven version, which is ALSO the
  firedown-geckoview release tag `geckoview-<version>` and its
  `config/build.env` `GECKOVIEW_VERSION`). `app/build.gradle` reads
  `geckoviewVersion` from the property — don't put the literal back.
- **`settings.gradle` runs `scripts/fetch-prebuilts.sh ensure` at settings
  time, on every sync.** Settings time on purpose: GeckoView must be in
  mavenLocal BEFORE the first dependency resolution (an IDE sync resolves at
  once; a task hook is too late and fails with "package org.mozilla.geckoview
  does not exist"), and CMake's configure can run during sync too. Its output
  is relayed through `println`, NOT `inheritIO()` — under the daemon that goes
  to the daemon log and a download reads as a hang. `-Pfiredown.fetchPrebuilts=false`
  skips it.
- **The script is a no-op in the steady state and NEVER clobbers a local
  build.** FFmpeg: `external/ffmpeg/lib` present → done; if `version.txt`
  names something other than the pin (a `scripts/sync-ffmpeg.sh` copy of a
  box-local build under test) it is left alone with a warning, `--force`
  replaces. GeckoView: `~/.m2/…/geckoview/*/<version>/*.aar` present → done —
  a local `./build.sh build` lands in the same place under the same version,
  so a box that builds Gecko never fetches it. Assets are sha256-verified
  against the `.sha256` the publish scripts upload beside them; a mismatch
  refuses and keeps whatever was there.
- **Transport is `gh`** (the repos are private; `gh auth login` once per
  machine is the only credential), with a `GITHUB_TOKEN` REST fallback for a
  box without the CLI. Releases over LFS/Packages/committing: the AAR is
  ~165 MB and only changes on a rebuild — LFS keeps every revision in every
  clone and meters bandwidth on a private repo, Packages' free private tier
  holds ~3 AARs, a Release asset is free, unmetered and 2 GB.
- **Publishing is the library repo's job**: firedown-ffmpeg
  `scripts/publish-release.sh v9.0.2-<n>` (tags HEAD, packages `output/` —
  `lib/<abi>/`, `include/` from arm64-v8a exactly as `sync-ffmpeg.sh` always
  did, `version.txt` — refuses a dirty tree, a tag whose FFmpeg version is
  not `SOURCE_VALUE`, or an ABI missing one of the six libs CMake imports) and
  firedown-geckoview `./build.sh publish` (tars the `~/.m2` GAV dir for the
  pinned version relative to `org/mozilla/geckoview`, refuses the half-installed
  states 04-build.sh documents — no `.aar`, `<packaging>pom</packaging>` — and
  refuses to re-publish an existing coordinate: bump `GECKOVIEW_BUILD_DATE`
  instead, a version is published once). Then bump the pin here and commit.
- **Bumping is three steps** (rebuild → publish → change the pin). A machine
  holding an older copy is not silently switched: FFmpeg warns (version.txt
  mismatch), GeckoView just gains the new version dir beside the old one.
- What git still does NOT carry, and the doc lists for copying by hand: the
  release signing key, `~/.gradle/gradle.properties`'s
  `firedown.mappingUploadToken`, and the `gh` login. The Gecko source /
  toolchain caches and FFmpeg's `build/`/`sources/` are deliberately NOT part
  of the migration — `./build.sh docker` recreates them, and an app-only
  machine never needs them.

## Conventions

- Match the surrounding comment density — the parsers are heavily commented
  with *why*, including dead-ends not to retry. Keep that.
- **Code comments name the page SHAPE, never the site where a case was
  seen.** No publisher names or hosts, no HAR dates, no device-audit runs,
  no "used to" history: write what the page does ("a carousel whose only
  label is its mute button"), not where it was found. A site parser module
  or a host-keyed branch may name its own host (its code matches it), and
  library or standard names (Video.js, JW Player, schema.org, `twitter:`
  meta tags) are not sites. The site and the HAR go in the commit message.
  Keep comments short: why the code is shaped this way, nothing more.
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
- **A `javac` parse-check is not a compile.** On a box without the Android
  SDK the Java files only parse; a method deleted or moved in one file
  still has callers in others, and nothing here sees them (the #308
  controller commit dropped its accessor/internals block and shipped 18
  compile errors to the maintainer's build). After moving or deleting
  methods, grep every removed definition for callers before committing.
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
