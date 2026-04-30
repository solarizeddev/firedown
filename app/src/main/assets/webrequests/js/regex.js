// Remote regex pattern list URL
const REGEX_URL = 'https://raw.githubusercontent.com/solarizeddev/firedown-webrequests/main/regex-patterns.txt';
const REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000; // Refresh every 6 hours

// Bundled fallback patterns (used if remote fetch fails on first load)
const DEFAULT_PATTERNS = [
  // YouTube
  'youtube\\.com\\/(complete\\/search|api\\/stats)',
  'youtube\\.com.*\\.mp3',
  'youtube-nocookie\\.com\\/api\\/stats',
  '\\.youtube\\.com\\/api',

  // Google
  '\\.google.*\\/(async|verify)\\/',
  'googleapis\\.com\\/(\\$rpc|identitytoolkit)\\/',
  'ogads-pa\\.clients.*\\.google\\.com\\/\\$rpc\\/google\\.internal\\.onegoogle\\.asyncdata',
  'news\\.google\\.com\\/.*\\/jserror',

  // Bing / Microsoft
  'bing\\.com\\/(rewardsapp\\/reportActivity|notifications\\/handle|AS\\/Suggestions|ipv6test\\/test|videos\\/async|sharing\\/getsharecommoncontrol|images\\/detail\\/insights|images\\/search\\?view)',
  'microsoft-api\\.arkoselabs\\.com',
  'copilot\\.microsoft\\.com\\/(fd\\/ls\\/l|cl\\/eus2\\/collect)',

  // TikTok
  'tiktok\\.com\\/aweme\\/v1\\/report',
  'tiktokw.*\\/web\\/report',

  // Twitch
  '(ttvnw\\.net|hls\\.live-video).*\\.(m3u8|ts)',
  'cloudfront\\.hls\\.ttvnw\\.net\\/v1\\/segment.*',
  'twitchcdn.*\\.mp4',

  // CloudFront (general media)
  'cloudfront\\.net.*\\.(ts|mp4)',
  'cloudfront\\.net\\/.*\\/(index-dvr|index-muted-[^.]+)\\.m3u8',

  // Twitter / X
  'video\\.twimg\\.com.*\\.(mp4|m4s|m3u8)',
  'pscp\\.tv.*\\.aac',

  // Instagram
  'instagram.*\\.mp4',
  'instagram\\.com\\/(accounts\\/login|ajax)',

  // Dailymotion
  'dmcdn\\.net.*init\\.mp4',
  'dmcdn\\.net.*manifest\\.m3u8',
  'dailymotion\\.com\\/history\\/log\\/user',
  'dailymotion\\.com\\/cdn\\/manifest\\/video\\/.*\\.m3u8',

  // Live video platforms
  '(playlist|playback)\\.live-video\\.net.*\\.m3u8',

  // SoundCloud
  '\\.soundcloud\\.cloud\\/.*\\/init\\.mp4',


    // Akamai
  '\\.akamaized\\.net\\/.*\\/(init|cmaf-|chunk-|segment[-_]?)[^/]*\\.(mp4|m4s)',

  // Other
  'startpage\\.com\\/sp\\/cl',
  'rumble\\.com\\/service\\.php\\?name=video\\.watching-now',
  'midjourney\\.com\\/cdn-cgi\\/challenge-platform',
  '\\/cdn-cgi\\/challenge-platform\\/',
  '.*segment\\.init',
];

let combinedRegex = buildRegex(DEFAULT_PATTERNS);

/**
 * Build a single RegExp from an array of pattern strings.
 * Blank lines and lines starting with # are ignored.
 */
function buildRegex(patterns) {
  const filtered = patterns
    .map(p => p.trim())
    .filter(p => p && !p.startsWith('#'));
  if (filtered.length === 0) return null;
  return new RegExp(filtered.join('|'));
}

/**
 * Fetch patterns from remote, recompile regex.
 * Falls back silently to current regex on any error.
 */
async function refreshPatterns() {
  try {
    const res = await fetch(REGEX_URL, { cache: 'no-cache' });
    if (!res.ok) {
      console.warn(`[regex] Remote fetch failed: ${res.status}`);
      return;
    }
    const text = await res.text();
    const lines = text.split('\n');
    const newRegex = buildRegex(lines);
    if (newRegex) {
      combinedRegex = newRegex;
      console.log(`[regex] Updated ${lines.filter(l => l.trim() && !l.trim().startsWith('#')).length} patterns from remote`);
    }
  } catch (e) {
    console.warn('[regex] Remote fetch error:', e.message);
  }
}

// Initial fetch + periodic refresh
refreshPatterns();
setInterval(refreshPatterns, REFRESH_INTERVAL_MS);

/**
 * Test a URL against the combined exclusion regex.
 */
function matchInRegex(string) {
  return combinedRegex ? combinedRegex.test(string) : false;
}

export { matchInRegex };
