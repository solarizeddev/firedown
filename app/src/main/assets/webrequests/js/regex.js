// Generic, CDN-agnostic capture-block patterns for the downloader@ catcher,
// compiled once into a single exclusion regex tested by matchInRegex.
//
// SCOPE: only junk that LOOKS like media to the classifier — URLs with a
// media-ish extension, or served with a media/video/audio/image content-type,
// that classifyXhr/classifyByUrl (requests.js) would otherwise capture. Pure
// telemetry/RPC endpoints (no media extension, JSON/text/204) are deliberately
// absent: the classifier already rejects them on content-type/extension.
// PARSER-DEDUP host/CDN blocks live in parser-blocklist.js, keyed by parser.
//
// BUNDLED-ONLY — there is no remote update mechanism. (The old 6h fetch of
// firedown-webrequests/regex-patterns.txt was removed: the endpoint 404s and
// this list is the single source of truth. Ship pattern changes in the APK,
// like any other capture logic.)
const DEFAULT_PATTERNS = [
  // YouTube — a .mp3 the catcher would otherwise grab as audio (extension match,
  // not caught by content-type alone).
  'youtube\\.com.*\\.mp3',

  // CloudFront (general media) — a broad CDN block, not tied to one parser.
  // (Twitch's own cloudfront VOD index playlists are a parser-dedup rule in
  // parser-blocklist.js.)
  'cloudfront\\.net.*\\.(ts|mp4)',

  // SoundCloud (init segment)
  '\\.soundcloud\\.cloud\\/.*\\/init\\.mp4',

  // StreamTheWorld (HLS radio segments)
  'live\\.streamtheworld\\.com.*\\.aac',

  // Akamai
  '\\.akamaized\\.net\\/.*\\/(init|cmaf-|chunk-|segment[-_]?)[^/]*\\.(mp4|m4s)',

  // Generic CMAF / fMP4 initialization segments. These hold the moov
  // box for an HLS / DASH stream and are referenced by the parent
  // playlist's #EXT-X-MAP: URI=... — they're never standalone media,
  // so probing one in isolation triggers the same
  // 'trun track id unknown, no tfhd was found' decoder dead-end as a
  // bare .m4s fragment. Filename is universally 'init' across CDNs
  // that ship CMAF (smoothpal, generic nginx setups, …); the
  // domain-specific entries above cover the cases where the path
  // doesn't follow that convention. Covers init / init01 / init-1 / init_0
  // and the common init extensions (this also generalizes the niconico
  // init01.cmfv case — niconico's own host rule in parser-blocklist.js still
  // covers its data segments).
  '\\/init[-_]?\\d*\\.(mp4|m4s|cmf[va]|ts|aac|m4a|webm)(?:[?#]|$)',

  // Generic numbered stream segments (seg5 / segment-5 / chunk_5 / frag12 …).
  // A digit right after seg/segment/chunk/frag is a strong "HLS/DASH fragment"
  // signal — never a standalone file — so these would only ever fan out junk
  // entries + wasted probes. The leading '/' keeps it from matching mid-word
  // (e.g. 'message5.ts' won't match).
  '\\/seg(?:ment)?[-_]?\\d+\\.(ts|m4s|mp4|aac)(?:[?#]|$)',
  '\\/chunk[-_]?\\d+\\.(ts|m4s|mp4)(?:[?#]|$)',
  '\\/frag(?:ment)?[-_]?\\d+\\.(ts|m4s|mp4)(?:[?#]|$)',

  // Bare-numbered HLS .ts segments — a path component that is JUST digits then
  // .ts (e.g. /720p60/7.ts, /18.ts, /20.ts). Many CDNs (AWS IVS / Kick,
  // among others) name segments with no seg/chunk/frag prefix, so the rules
  // above miss them. A pure-number .ts filename is essentially always an
  // MPEG-TS HLS fragment, never a standalone deliverable — capturing it just
  // fans out junk entries and wastes a metadatareader probe per segment (each
  // pulls the whole ~MBs segment before isValidMedia drops it as raw mpegts).
  // The leading '/' + digits-only filename keeps it off named files
  // ('message5.ts', 'v18.ts', 'ep01.ts' won't match — those carry letters).
  '\\/\\d+\\.ts(?:[?#]|$)',

  // NOTE: there is deliberately NO ".image" / ts-in-png URL rule here. MPEG-TS
  // video disguised as image/png (the series.ly / tiktokcdn ad-CDN trick) is
  // caught content-side, not by URL — the native probe runs the bytes through
  // TSInterceptor (strips the fake PNG header) and FFmpegMetaData.isValidMedia
  // drops it on format == "mpegts". That's CDN- and extension-independent, so a
  // per-CDN URL rule would just be cat-and-mouse. Don't re-add one. (See
  // GeckoInspectTask / TSInterceptor.)

  // fMP4 init segment with no standard extension — served as video/mp4, so the
  // classifier captures it on content-type; name it so it's dropped.
  '.*segment\\.init',

  // Google Maps raster tiles — google.<tld>/maps/vt?pb=… (base map + overlay
  // layers, served image/webp, extensionless → captured on the webRequest
  // 'image' type). Pure page furniture, and a FLOOD: every pan/zoom fetches
  // dozens of 256px tiles, which buried the actual place photos
  // (lh3.googleusercontent.com — those still capture) in the Captured sheet's
  // Images chip (HAR-verified 26-08-27: 20 vt tiles vs 11 real photos on one
  // place page). Host-qualified so a third-party site's own /maps/vt path
  // can't be swallowed; covers www.google.com and every google ccTLD.
  '\\/\\/(?:[a-z0-9-]+\\.)*google\\.[a-z.]+\\/maps\\/vt[?/]',
];

// Compile once into a single exclusion regex. Blank entries are filtered so a
// stray '' can't introduce an empty alternation (which would match everything
// and block all captures); an all-empty list yields null → matchInRegex false.
function buildRegex(patterns) {
  const filtered = patterns.filter((p) => p && p.trim());
  return filtered.length ? new RegExp(filtered.join('|')) : null;
}

const combinedRegex = buildRegex(DEFAULT_PATTERNS);

/**
 * Test a URL against the combined exclusion regex.
 */
function matchInRegex(string) {
  return combinedRegex ? combinedRegex.test(string) : false;
}

export { matchInRegex };
