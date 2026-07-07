// Declarative, per-parser media block-list for the generic catcher.
//
// THE CARDINAL RULE (see CLAUDE.md "Parser vs. generic catcher"): a site that
// has a dedicated parser in the `parser@` extension must be captured BY that
// parser — which emits rich metadata + quality variants — and NOT by this
// generic catcher (`downloader@`), which would emit a second, bare,
// metadata-less entry for the same video (a duplicate) and waste a
// `metadatareader` probe on it.
//
// To enforce that, every parser's emitted media URL (and the segments its
// player fetches for that media) is block-listed here, by host/CDN.
// `validateAndClassify` in requests.js tests this list BEFORE classifying or
// probing a URL and drops a match.
//
// WHY A SEPARATE FILE FROM regex.js. regex.js holds the generic, CDN-agnostic
// junk (telemetry/beacon endpoints, init/numbered HLS-DASH segment fragments).
// Both lists are bundled-only (regex.js's old 6h remote refetch was removed —
// the bundled list is the single source of truth), but they differ in KIND:
// the parser-dedup blocks pair 1:1 with a parser and change when a parser
// changes. Keeping them here, keyed by parser, makes the cardinal rule
// mechanical — adding or changing a parser means adding/adjusting its entry
// HERE, next to nothing else, instead of threading another line into the
// generic junk list.
//
// HOW TO ADD A PARSER: drop a new key below with the host/CDN pattern(s) for the
// media that parser emits. Each value is a JS-regex SOURCE string (the same
// dialect regex.js uses), tested against the full URL. Pick a pattern that
// matches exactly what the parser emits (plus the segments its player fetches),
// but is narrow enough not to swallow unrelated media on a shared CDN.
//
// ONLY FOR SITE-SPECIFIC PARSERS — NOT FOR THE GENERIC PAGE-STATE BRIDGE. A block
// rule belongs here only when SITE-SPECIFIC code emits the media: a dedicated
// per-site `parser@` module (Twitter, Instagram, Bluesky, niconico, Twitch, …) or
// a host-keyed branch of the page-state bridge (Bilibili.tv, Mega.nz). Media
// captured by the bridge's GENERIC, HOST-AGNOSTIC player readers (findPlayerMedia
// / readPlayerMedia / readDomMedia — Plyr/JWPlayer/Video.js DOM + player-API, the
// JSON-delegate / series.ly-krakenfiles class) must NOT get an entry: the bridge reads the
// source page-world and fires PRE-PLAY, so its rich capture lands BEFORE the
// player's on-play wire fetch and the repository dedups the two BY URL — a block
// would only suppress that play-time capture with no parser owning the (often
// shared / per-video-random) host. The accepted trade-off is a rare other-quality
// duplicate (a manually-picked non-default rendition whose URL differs from the
// bridge's primary) — same stance as TikTok's first-video case; these sites rely
// on the URL dedup ALONE. (Bilibili.tv / Mega keep their entries only because the
// catcher would otherwise emit a HARMFUL capture — an unplayable whole-track .m4s
// video/audio segment, or undecryptable AES-CTR ciphertext — not a benign
// same-URL dup.)
//
// NOTE: TikTok is deliberately ABSENT. Its `webapp-prime` media host is left
// un-blocked on purpose so the generic catcher can grab the cache-served first
// /foryou video the parser structurally cannot see (see the TikTok note in
// CLAUDE.md). Do not add a TikTok media block here.

const PARSER_BLOCKLIST = {
  // Twitter / X — progressive + HLS on video.twimg.com; pscp.tv (Periscope)
  // audio for Spaces / live.
  twitter: [
    'video\\.twimg\\.com.*\\.(mp4|m4s|m3u8)',
    'pscp\\.tv.*\\.aac',
  ],

  // Instagram + Threads — same fbcdn hosts, so one rule covers both.
  instagram: [
    'instagram.*\\.mp4',
  ],

  // Bilibili.tv — the DASH video+audio .m4s baseUrls the page-state bridge emits
  // on the upos/bilivideo bstar CDN (iupxcodeboss path).
  bilibili: [
    'upos-.*(bilivideo\\.com|akamaized\\.net)\\/iupxcodeboss\\/.*\\.m4s',
  ],

  // Niconico — the signed HLS master is emitted from access-rights/hls; block
  // the delivery.domand playlists (master/media .m3u8) and the CMAF media on the
  // asset CDN (per-track init01.cmfv / init01.cmfa + data .cmfv / .cmfa
  // segments) so the catcher doesn't grab the bare master or the init segments
  // as standalone (unplayable) entries.
  niconico: [
    'delivery\\.domand\\.nicovideo\\.jp\\/.*\\.m3u8',
    'asset\\.domand\\.nicovideo\\.jp\\/.*\\.cmf[va]',
  ],

  // Dailymotion — the init segment, the media playlist, and the signed manifest
  // the parser emits (the manifest carries a #fragment, so there's no extension
  // anchor on the path).
  dailymotion: [
    'dmcdn\\.net.*init\\.mp4',
    'dmcdn\\.net.*manifest\\.m3u8',
    'dailymotion\\.com\\/cdn\\/manifest\\/video\\/.*\\.m3u8',
  ],

  // Twitch — the HLS master/media + segments the parser enumerates: ttvnw.net,
  // the live-video.net IVS edges (playlist/playback), and the cloudfront VOD
  // index playlists (index-dvr / index-muted).
  twitch: [
    '(ttvnw\\.net|hls\\.live-video).*\\.(m3u8|ts)',
    'cloudfront\\.hls\\.ttvnw\\.net\\/v1\\/segment.*',
    'twitchcdn.*\\.mp4',
    'cloudfront\\.net\\/.*\\/(index-dvr|index-muted-[^.]+)\\.m3u8',
    '(playlist|playback)\\.live-video\\.net.*\\.m3u8',
  ],

  // Kick — the kick@ parser emits the HLS master (data.source) for VODs, served
  // from the AWS-IVS-backed stream.kick.com CDN (the /ivs/v1/ path). Block the
  // master + media playlists (.m3u8) AND the .ts segments the player fetches from
  // that host: Kick's segments are BARE-NUMBERED (.../720p60/7.ts), so regex.js's
  // generic numbered-fragment rules (which require a seg/segment/chunk/frag
  // prefix) never match them. Without this entry the generic catcher captures
  // every segment, runs a metadatareader probe on each (~5 MB fetched per probe),
  // drops it as raw mpegts (isValidMedia), and — because a dropped capture is
  // never added to the repository — RE-probes the same buffered segments on every
  // player re-fetch, an unbounded probe loop. Scoped to .m3u8/.ts so the
  // thumbnails this host may serve (.jpg) are untouched.
  kick: [
    'stream\\.kick\\.com\\/.*\\.(m3u8|ts)',
  ],

  // Rumble — the parser emits the HLS master (watch pages, via embedJS) and the
  // MP4 shorts variants on the rumble.cloud CDN.
  rumble: [
    'rumble\\.com\\/hls-vod\\/.*\\.m3u8',
    'rumble\\.cloud\\/.*\\.mp4',
  ],

  // Mega.nz — the page-state bridge captures folder-link files (the share key is
  // page-world only) and the native MegaStrategy resolves + decrypts them. Block
  // the encrypted-byte download hosts (gfs*.[userstorage.]mega.co.nz) so the
  // generic catcher can't emit a second, bare, AES-CTR-ciphertext entry that no
  // amount of re-fetching could ever decrypt. (Playback is unaffected — the
  // block-list only governs capture, not the page's own requests.)
  mega: [
    'gfs[^/.]*\\.(userstorage\\.)?mega\\.co\\.nz\\/',
  ],

  // Bluesky — the bsky@ parser emits the HLS master
  // (video.bsky.app/watch/<did>/<cid>/playlist.m3u8) read from the AT-Proto
  // app-view JSON. Block the master + child playlists on video.bsky.app and the
  // .ts segments the player fetches from video.cdn.bsky.app, so the generic
  // catcher doesn't grab the bare master (a duplicate) or the child/segment
  // pieces as standalone unplayable entries. Scoped to .m3u8/.ts so the
  // thumbnails these same hosts serve (.jpg) are untouched.
  bsky: [
    'video(?:\\.cdn)?\\.bsky\\.app\\/(?:watch|hls)\\/.*\\.(?:m3u8|ts)',
  ],

  // YouTube — fully owned by the youtube@ parser (VOD = SABR, LIVE = HLS master,
  // both emitted with rich metadata + quality variants). Unlike the shared CDNs
  // above this is a HOST-level block: googlevideo.com is YouTube's EXCLUSIVE media
  // CDN (no unrelated media lives there), so scoping to an extension would only
  // risk missing one of YouTube's many URL shapes. The generic catcher must never
  // emit a googlevideo entry.
  //
  // The load-bearing case is LIVE. VOD is SABR (videoplayback chunks, no manifest
  // URL the catcher classifies as media), so it never tripped. But a live stream
  // plays over HLS, so a clean .m3u8 crosses the wire —
  // manifest.googlevideo.com/.../hls_variant/ (master) and .../hls_playlist/
  // (child) — which the catcher grabbed and emitted as type:"media". That fired a
  // metadatareader probe that opened the live segments on the rr*.googlevideo
  // chunk hosts, every one 403ing on the per-host n-param / live edge, spinning
  // ffmpeg's reload loop until the hls.c patch-0005 bail. This block drops the
  // capture before classify, so no probe. (The youtube@ parser separately emits
  // the master as type:"hls-master" → native enumerates the master text only, no
  // probe — these two together close both probe sources for live.)
  youtube: [
    'googlevideo\\.com\\/',
  ],

  // Telegram (t.me) — the parser reads the post page's <video>/og:video and
  // emits the progressive .mp4 served from Telegram's media CDN
  // (cdn*.cdn-telegram.org and the legacy cdn*.telesco.pe). Block that .mp4 so
  // the generic catcher doesn't grab a second, bare, metadata-less copy when
  // the player fetches the same URL on play. Scoped to /file/*.mp4 so the
  // poster .jpg these same hosts serve (the thumbnail) is untouched.
  telegram: [
    '(cdn-telegram\\.org|telesco\\.pe)\\/file\\/.*\\.mp4',
  ],

  // Telegram WEB APP (web.telegram.org/k & /a) — its media URLs are
  // ServiceWorker-virtual /stream/ paths backed by MTProto bytes decrypted
  // in-page. They are NOT re-fetchable by the native downloader (no SW, no
  // MTProto), so a generic-catcher capture of one is a HARMFUL entry that fails
  // on every download attempt (the /stream/ requests DO cross the wire as 206
  // video/mp4, so without this block the catcher would grab them). Block it to
  // keep those broken entries out of the Captured sheet — same "block a harmful
  // capture" rationale as Mega's undecryptable bytes, not the ordinary
  // cardinal-rule dedup. (An in-page-download button for this surface was tried
  // and removed — see the "Telegram WEB APP" note in CLAUDE.md; the web app is
  // not a supported download surface, this block just prevents broken captures.)
  // Matches /k/stream/, /a/stream/, /z/stream/ and a bare /stream/ on the
  // web(k|z).telegram.org hosts.
  'telegram-web': [
    'web(?:k|z)?\\.telegram\\.org\\/(?:[kaz]\\/)?stream\\/',
  ],

  // News Over Audio (NOA) — the "listen to this article" embed used across many
  // publishers (IEEE Spectrum, …). The parser reads api.newsoveraudio.com's
  // player JSON and emits the signed article narration .mp3 from NOA's audio CDN
  // (audios.newsoveraudio.com). Block that .mp3 so the generic catcher doesn't
  // grab a second, bare, metadata-less copy when the cross-origin embed player
  // fetches the same URL on play (the embed lives in an iframe whose <audio>
  // binding the top-frame metadata responder can't see — so the bare copy would
  // also land untitled).
  newsoveraudio: [
    'audios\\.newsoveraudio\\.com\\/.*\\.mp3',
  ],

  // Videee (videee.com) — the videee@ parser reads the Supabase /rest/v1/videos
  // JSON (each row carries title + thumbnail_url + a direct video_url) and emits
  // the progressive .mp4 served from Cloudflare-fronted media.videee.com, under
  // an owner-UUID path segment. Block that .mp4 so the generic catcher doesn't
  // grab a second, bare, metadata-less copy when the player fetches the same URL
  // on play (which it would otherwise enrich with the SITE og:title/og:image —
  // identical for every clip, the bug this parser fixes). Scoped to the 36-char
  // UUID path + .mp4 so the thumbnails this same host serves
  // (media.videee.com/thumbnails/<owner>/<n>.jpg) are untouched.
  videee: [
    'media\\.videee\\.com\\/[0-9a-f-]{36}\\/[^?#]+\\.mp4',
  ],

  // Spotify — the spotify@ embed parser reads open.spotify.com/embed/*'s
  // __NEXT_DATA__ and emits each track's ~30s non-DRM preview clip from
  // p.scdn.co/mp3-preview/<hash>, titled per track. Block that preview host so
  // the generic catcher doesn't ALSO grab a bare, untitled copy when the widget
  // fetches the same clip on play (the embed is a cross-origin iframe whose
  // <audio> binding the top-frame metadata responder can't see, and its
  // per-track title only exists post-play via MediaSession — so the bare copy
  // would land untitled / under the shared playlist name). Scoped to the
  // mp3-preview path; capture only — the widget's own playback is untouched.
  spotify: [
    'p\\.scdn\\.co\\/mp3-preview\\/',
  ],
};

// Flatten every parser's patterns into one compiled RegExp — same approach and
// dialect as regex.js's buildRegex (an alternation of the source strings). Built
// once at module load; the list is bundled (not remote), so it never changes at
// runtime.
function buildParserRegex(map) {
  const all = [];
  const keys = Object.keys(map);
  for (let i = 0; i < keys.length; i++) {
    const list = map[keys[i]];
    for (let j = 0; j < list.length; j++) {
      all.push(list[j]);
    }
  }
  if (all.length === 0) return null;
  return new RegExp(all.join('|'));
}

const parserBlockRegex = buildParserRegex(PARSER_BLOCKLIST);

/**
 * True when the URL is a parser-owned media URL the generic catcher must not
 * capture/probe (the cardinal rule). Mirrors regex.js matchInRegex semantics.
 */
function matchInParserBlocklist(string) {
  return parserBlockRegex ? parserBlockRegex.test(string) : false;
}

// The matcher is what lets the page-state bridge's generic readers
// (handlePageStateHls / handlePageStateProgressive in js/parsers/page-state.js)
// skip media a dedicated parser already owns (e.g. Dailymotion), instead of
// emitting a second, duplicate entry the URL/origin dedup can't collapse.
// (The old globalThis.matchInParserBlocklist bridge for the classic
// parser-background.js was removed when the parsers became ES modules — they
// import this directly now.)
export { PARSER_BLOCKLIST, matchInParserBlocklist };
