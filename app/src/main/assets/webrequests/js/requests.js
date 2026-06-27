import * as RegexMap from './regex.js';
import * as ParserBlock from './parser-blocklist.js';
import { handleCookieRequest } from './cookies.js';

// Configuration
const MAX_PENDING_REQUESTS = 1024;
const REQUEST_TIMEOUT_MS = 30000;
const TAB_ORIGIN_CACHE_MS = 5000;
const HEADER_CACHE_MAX = 2048;
const HEADER_CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes
const CONTENT_SCRIPT_DEDUPE_MAX = 5000;

// Pulled from BuildConfig.DEBUG via native message on startup —
// release builds get DEBUG=false automatically and short-circuit
// argument evaluation in dlog() across all 100+ call sites.
let DEBUG = false;
browser.runtime.sendNativeMessage("browser", { kind: "get-debug-flag" })
    .then(r => { DEBUG = r === true; })
    .catch(() => {});

const pendingRequests = new Map();
const originTabCache = new Map();
const urlHeaderCache = new Map(); // url -> { headers, timestamp }
const contentScriptSeen = new Set(); // "tabId|url"
let lastActiveTabId = -1;

// Pre-compiled regex patterns
const PATTERNS = {
  youtubeTimedText: /^https?:\/\/[^/]*youtube\.com\/api\/timedtext/,
  rtveCdn: /^https?:\/\/[^/]*rtve[^/]*\.mpd/,
  subtitle: /^https?:\/\/[^/]+\/[^?#]*\.(?:vtt|srt)(?:[?#]|$)/i,
  html: /^https?:\/\/[^/]+\/[^?#]*\.(?:html?|aspx|css|js)(?:[?#]|$)/i,
  image: /^https?:\/\/[^/]+\/[^?#]*\.(?:gif|webp|png|jpe?g|avif|bmp|tiff?|apng|heic|heif)(?:[?#]|$)/i,
  media: /^https?:\/\/[^/]+\/[^?#]*\.(?:mp[34]|flv|avi|3gp|m4v|aac|mpe?g|wmv|mkv|mpd|m3u8?|webm|wav|midi|weba|opus|flac|m4a)(?:[?#]|$)/i,
  ts: /^https?:\/\/[^/]+\/[^?#]*\.ts(?:[?#]|$)/i,
  svg: /^https?:\/\/[^/]+\/[^?#]*\.svg(?:[?#]|$)/i,
  // CMAF fragment used by fMP4 HLS / DASH. Always a fragment of a master
  // playlist — never standalone media — so probing one in isolation
  // gets ffmpeg's mov demuxer to bail with 'trun track id unknown, no
  // tfhd was found' (the moov box lives in the init.mp4 sibling). Drop
  // these at classify-time so we don't fan out N concurrent probes to
  // the origin and trip its HTTP/2 stream limits, taking the real
  // playlist download down with PROTOCOL_ERROR resets.
  m4sSegment: /^https?:\/\/[^/]+\/[^?#]*\.m4s(?:[?#]|$)/i,
};

const MEDIA_CONTENT_TYPES = [
  'vnd.apple.mpegurl',
  'video/vnd.mpeg.dash.mpd',
  'x-mpegurl',
  'm3u',
  'mpegurl',
  'dash+xml',
  'ogg',
  'x-mpeg',
  'mpeg',
];

// ---------------------------------------------------------------------------
// Diagnostic helpers
// ---------------------------------------------------------------------------

function isInteresting(url, type) {
  if (!url) return false;
  if (type === 'stylesheet' || type === 'font' || type === 'script') return false;
  if (type === 'websocket') return false;
  if (PATTERNS.image.test(url)) return true;
  if (PATTERNS.media.test(url)) return true;
  if (PATTERNS.svg.test(url)) return true;
  if (PATTERNS.ts.test(url)) return true;
  if (PATTERNS.subtitle.test(url)) return true;
  if (type === 'image' || type === 'imageset' || type === 'media') return true;
  return false;
}

function dlog(tag, url, ...rest) {
  if (!DEBUG) return;
  console.log(`[req] ${tag}`, url, ...rest);
}

// ---------------------------------------------------------------------------
// URL header cache
// Stores request headers from onSendHeaders, keyed by URL, so content-script-
// reported images (which lack headers) can be enriched with the headers used
// by the original fetch.
// ---------------------------------------------------------------------------

function cacheHeaders(url, requestHeaders, fromExtensionContext = false) {
  if (!url || !requestHeaders || !requestHeaders.length) return;
  if (urlHeaderCache.size >= HEADER_CACHE_MAX) {
    const oldestKey = urlHeaderCache.keys().next().value;
    urlHeaderCache.delete(oldestKey);
  }
  // Prefer page-context headers over probe-context headers: if we already
  // have a page-context entry, don't overwrite with probe headers.
  const existing = urlHeaderCache.get(url);
  if (existing && !existing.fromExtensionContext && fromExtensionContext) {
    return;
  }
  urlHeaderCache.set(url, {
    headers: requestHeaders,
    timestamp: Date.now(),
    fromExtensionContext,
  });
}

function getCachedHeaders(url) {
  const entry = urlHeaderCache.get(url);
  if (!entry) return null;
  if (Date.now() - entry.timestamp > HEADER_CACHE_TTL_MS) {
    urlHeaderCache.delete(url);
    return null;
  }
  return entry;
}

// Headers that reflect the requesting context (extension vs page) and must
// be rewritten when serving probe-sourced cached headers to a page consumer.
const CONTEXT_SENSITIVE_HEADERS = new Set([
  'origin',
  'referer',
  'host',
  'sec-fetch-site',
  'sec-fetch-mode',
  'sec-fetch-dest',
  'sec-fetch-user',
]);

function sanitizeHeadersForPage(headers, pageUrl) {
  if (!headers || !headers.length) return headers;
  const filtered = headers.filter(
    (h) => !CONTEXT_SENSITIVE_HEADERS.has(h.name.toLowerCase())
  );
  if (pageUrl) {
    filtered.push({ name: 'Referer', value: pageUrl });
    try {
      filtered.push({ name: 'Origin', value: new URL(pageUrl).origin });
    } catch {
      // pageUrl was malformed — skip Origin
    }
  }
  return filtered;
}

// ---------------------------------------------------------------------------
// Tab tracking
// ---------------------------------------------------------------------------

browser.tabs.onRemoved.addListener((tabId) => {
  browser.runtime.sendNativeMessage('browser', {
    listener: 'onRemoved',
    id: tabId,
  });
  for (const [requestId, data] of pendingRequests) {
    if (data.tabId === tabId) {
      pendingRequests.delete(requestId);
    }
  }
  for (const [origin, entry] of originTabCache) {
    if (entry.tabId === tabId) {
      originTabCache.delete(origin);
    }
  }
  for (const key of contentScriptSeen) {
    if (key.startsWith(tabId + '|')) {
      contentScriptSeen.delete(key);
    }
  }
});

browser.tabs.onActivated.addListener((activeInfo) => {
  lastActiveTabId = activeInfo.tabId;
  browser.runtime.sendNativeMessage('browser', {
    listener: 'onActivated',
    id: activeInfo.tabId,
    previousId: activeInfo.previousTabId,
    windows: activeInfo.windowId,
  });
});

browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.url) {
    for (const [origin, entry] of originTabCache) {
      if (entry.tabId === tabId) {
        originTabCache.delete(origin);
      }
    }
    // A tab opened+focused by window.open / target=_blank (the embedded-player
    // "open in new tab" case — e.g. dailymotion's geo.dailymotion.com/player
    // embed) fires NO tabs.onActivated: that event only fires on a user tab
    // SWITCH. This navigation is its first observable event, so promote it here.
    // Otherwise Java's mTabId stays on the previous tab while the capture lands
    // under the new tab's real id, and BrowserDownloadViewModel.filter (strict
    // tabId equality) silently drops it from the Captured sheet.
    syncActiveTab();
  }
});

// Resolve the genuinely-active tab (the source of truth — not the event's
// tabId, which may be a BACKGROUND tab navigating) and, when it actually
// changed, emit the same synthetic 'onActivated' the bootstrap below uses so
// Java updates mTabId. Idempotent: no message when the active tab is unchanged,
// so a background tab can never steal the foreground id.
function syncActiveTab() {
  browser.tabs.query({ active: true, currentWindow: true })
    .then((tabs) => {
      if (!tabs[0] || tabs[0].id === lastActiveTabId) return;
      const previousId = lastActiveTabId;
      lastActiveTabId = tabs[0].id;
      browser.runtime.sendNativeMessage('browser', {
        listener: 'onActivated',
        id: tabs[0].id,
        previousId,
        windows: tabs[0].windowId,
      });
    })
    .catch(() => {});
}

browser.tabs.query({ active: true, currentWindow: true })
  .then((tabs) => {
    if (!tabs[0]) return;
    lastActiveTabId = tabs[0].id;
    // Bootstrap Java's GeckoRuntimeHelper.mTabId at extension load.
    // Java only updates mTabId from this native "onActivated" message,
    // and Gecko's onActivated event fires only on tab switches — never
    // on cold-start session restore. Without this synthetic message,
    // mTabId stays at DEFAULT_TAB_ID (10001) until the user manually
    // switches tabs, while captured content lands with the real Gecko
    // tabId. BrowserDownloadViewModel.filter compares the two for
    // strict equality and silently drops everything on the mismatch.
    browser.runtime.sendNativeMessage('browser', {
      listener: 'onActivated',
      id: tabs[0].id,
      previousId: -1,
      windows: tabs[0].windowId,
    });
  })
  .catch(() => {});

// ---------------------------------------------------------------------------
// Pending request store
// ---------------------------------------------------------------------------

function addPendingRequest(data) {
  if (pendingRequests.size >= MAX_PENDING_REQUESTS) {
    const oldestKey = pendingRequests.keys().next().value;
    pendingRequests.delete(oldestKey);
  }
  pendingRequests.set(data.requestId, {
    ...data,
    timestamp: Date.now(),
  });

  // Cache headers by URL for later content-script enrichment.
  // Tag entries from extension-context requests (our HEAD probe) so consumers
  // know to sanitize Origin/Referer/Sec-Fetch-* before forwarding.
  if (data.requestHeaders && /^https?:/i.test(data.url)) {
    const fromExt =
      (data.documentUrl && data.documentUrl.startsWith('moz-extension://')) ||
      (data.originUrl && data.originUrl.startsWith('moz-extension://'));
    cacheHeaders(data.url, data.requestHeaders, fromExt);
  }
}

function cleanupStaleEntries() {
  const now = Date.now();
  for (const [requestId, data] of pendingRequests) {
    if (now - data.timestamp > REQUEST_TIMEOUT_MS) {
      pendingRequests.delete(requestId);
    }
  }
  for (const [origin, entry] of originTabCache) {
    if (now - entry.timestamp > TAB_ORIGIN_CACHE_MS) {
      originTabCache.delete(origin);
    }
  }
  for (const [url, entry] of urlHeaderCache) {
    if (now - entry.timestamp > HEADER_CACHE_TTL_MS) {
      urlHeaderCache.delete(url);
    }
  }
}

setInterval(cleanupStaleEntries, REQUEST_TIMEOUT_MS);

// ---------------------------------------------------------------------------
// Tab resolution for service-worker-originated requests
// ---------------------------------------------------------------------------

async function resolveTabId(data) {
  if (data.tabId >= 0) return data.tabId;

  const refUrl = data.documentUrl || data.originUrl;
  if (!refUrl || !refUrl.startsWith('http')) {
    return lastActiveTabId;
  }

  let refOrigin;
  try {
    refOrigin = new URL(refUrl).origin;
  } catch {
    return lastActiveTabId;
  }

  const cached = originTabCache.get(refOrigin);
  if (cached && Date.now() - cached.timestamp < TAB_ORIGIN_CACHE_MS) {
    return cached.tabId;
  }

  let resolvedTabId = lastActiveTabId;

  try {
    const tabs = await browser.tabs.query({});
    const candidates = tabs.filter((t) => {
      if (!t.url) return false;
      try {
        return new URL(t.url).origin === refOrigin;
      } catch {
        return false;
      }
    });

    if (candidates.length === 1) {
      resolvedTabId = candidates[0].id;
    } else if (candidates.length > 1) {
      const active = candidates.find((t) => t.active);
      if (active) {
        resolvedTabId = active.id;
      } else {
        candidates.sort((a, b) => (b.lastAccessed ?? 0) - (a.lastAccessed ?? 0));
        resolvedTabId = candidates[0].id;
      }
    }
  } catch (e) {
    // fall through
  }

  originTabCache.set(refOrigin, {
    tabId: resolvedTabId,
    timestamp: Date.now(),
  });

  return resolvedTabId;
}

// ---------------------------------------------------------------------------
// Header / classification helpers
// ---------------------------------------------------------------------------

function getHeader(headers, name) {
  const lowerName = name.toLowerCase();
  const header = headers?.find((h) => h.name.toLowerCase() === lowerName);
  return header?.value ?? null;
}

function getTypeFromUrl(url) {
  if (PATTERNS.svg.test(url)) return 'svg';
  if (PATTERNS.ts.test(url)) return 'ts';
  if (PATTERNS.media.test(url)) return 'media';
  if (PATTERNS.image.test(url)) return 'image';
  if (PATTERNS.subtitle.test(url)) return 'subtitle';
  return null;
}

// True for a standalone AUDIO file URL (…/notification.mp3, …/ding.wav). These
// are almost always *incidental* on a generic page — a UI sound, a notification
// ding, background music — not the content the page is about. The generic
// catcher otherwise enriches every media capture's name with the page og:title
// (and GeckoInspectTask lets that name win over the URL filename), so such a
// sound gets captured looking "as if it was the file we are trying to capture":
// series.ly's /audio/notification.mp3 came out named after the movie ("The
// Breadwinner"). So the title override is gated for standalone audio: it is
// adopted ONLY when the page presents the URL as main content (content-script
// audioRole === 'content' — a declared AudioObject/og:audio or a user-facing
// <audio>/<video controls> bound to it; see the emit site below), and skipped
// otherwise so an incidental sound keeps its filename. VIDEO is always enriched
// (an .mp4 embedded in a news article inherits the headline), and so are
// HLS/DASH manifests (master.m3u8 / manifest.mpd — always generic) and
// tokenized/extensionless URLs (urlIsStandaloneAudio is false for them). This
// recovers the real-audio-content case (an interview/podcast clip in an article)
// the old extension-only suppression could not tell from a ding. (Audio sites
// with rich metadata — Apple Podcasts — go through a parser, not this path.)
const AUDIO_FILE_RE = /^https?:\/\/[^/]+\/[^?#]*\.(?:mp3|m4a|aac|wav|weba|opus|flac|oga|midi?)(?:[?#]|$)/i;
function urlIsStandaloneAudio(url) {
  return AUDIO_FILE_RE.test(url);
}

function isMediaContentType(contentType) {
  return MEDIA_CONTENT_TYPES.some((type) => contentType.includes(type));
}

// True when an <img>-loaded request (webRequest type 'image'/'imageset') looks
// like a telemetry beacon rather than a real image: a *definitively* non-image
// content-type (json / text/html / text/plain) AND no image/svg extension to
// vouch for it. This is the source of the manual telemetry exclusions in
// regex.js — pixel/beacon endpoints fired via <img> are captured on type alone
// (classifyByUrl's `data.type === 'image'` fallback) and then needlessly probed.
//
// Deliberately scoped to the image family ONLY: real media (m3u8/mpd/…) is
// loaded as type 'media' by a <video>/<audio> element, never via <img>, so this
// can't drop a stream with a disguised MIME (text/*, octet-stream). Ambiguous
// content-types (octet-stream / binary / missing) are NOT treated as beacons —
// some CDNs serve genuine images that way (and the content-script DOM scrape
// backs those up regardless).
function isNonImageBeacon(url, headers) {
  if (PATTERNS.image.test(url) || PATTERNS.svg.test(url)) return false;
  const ct = getHeader(headers, 'content-type');
  if (!ct) return false;
  const lower = ct.toLowerCase();
  if (lower.includes('image')) return false;
  return lower.includes('application/json')
      || lower.includes('text/html')
      || lower.includes('text/plain');
}

function validateAndClassify(data) {
  const { url, type, responseHeaders } = data;
  const interesting = isInteresting(url, type);

  if (!url || !/^https?:/i.test(url)) {
    if (interesting) dlog('reject:non-http', url);
    return false;
  }

  if (type === 'websocket' || type === 'web_manifest') {
    if (interesting) dlog('reject:ws-manifest', url);
    return false;
  }

  if (RegexMap.matchInRegex(url)) {
    if (interesting) dlog('reject:regex-block', url);
    return false;
  }

  // Parser-dedup (the cardinal rule): a site with a dedicated parser is captured
  // — with metadata + variants — by the parser@ extension, so the generic
  // catcher must not also probe/capture its media. These host/CDN blocks are
  // kept declarative and per-parser in parser-blocklist.js, separate from the
  // remote-managed generic junk above. See CLAUDE.md "Parser vs. generic catcher".
  if (ParserBlock.matchInParserBlocklist(url)) {
    if (interesting) dlog('reject:parser-block', url);
    return false;
  }

  if (type === 'media' || type === 'imageset' || type === 'image') {
    // Drop <img>-loaded telemetry beacons (non-image content-type, no image
    // extension) before they reach native + a wasted metadatareader probe.
    // Image family only — the 'media' type is left untrusted-by-content-type so
    // disguised streams (m3u8/mpd served as text/*, octet-stream) still pass.
    if ((type === 'image' || type === 'imageset') && isNonImageBeacon(url, responseHeaders)) {
      if (interesting) dlog('reject:image-beacon', url, 'content-type=', getHeader(responseHeaders, 'content-type'));
      return false;
    }
    const ok = classifyByUrl(data);
    if (interesting && !ok) dlog('reject:classifyByUrl(media/image)', url, 'type=', type);
    return ok;
  }

  if (type === 'xmlhttprequest') {
    const ok = classifyXhr(data, responseHeaders);
    if (interesting && !ok) {
      const ct = getHeader(responseHeaders, 'content-type');
      dlog('reject:classifyXhr', url, 'content-type=', ct);
    }
    return ok;
  }

  const ok = classifyByUrl(data);
  if (interesting && !ok) dlog('reject:classifyByUrl(fallback)', url, 'type=', type);
  return ok;
}

function classifyByUrl(data) {
  const { url } = data;

  if (PATTERNS.rtveCdn.test(url)) {
    data.url = url.replace('_drm', '');
    data.type = 'media';
    return true;
  }

  const detectedType = getTypeFromUrl(url);
  if (detectedType) {
    data.type = detectedType;
    return true;
  }

  if (data.type === 'media' || data.type === 'image') {
    return true;
  }

  return false;
}

function classifyXhr(data, headers) {
  const contentType = getHeader(headers, 'content-type');

  if (!contentType) {
    return classifyByUrl(data);
  }

  const lowerCT = contentType.toLowerCase();

  if (lowerCT.includes('application/vnd.yt-ump')) {
    return false;
  }

  if (lowerCT.includes('image')) {
    data.type = PATTERNS.svg.test(data.url) ? 'svg' : 'image';
    return true;
  }

  if (isMediaContentType(lowerCT)) {
    if (PATTERNS.rtveCdn.test(data.url)) {
      data.url = data.url.replace('_drm', '');
    }
    data.type = 'media';
    return true;
  }

  if (lowerCT.includes('video') || lowerCT.includes('audio')) {
    // CMAF .m4s fragments come back as video/mp4 — drop them, the parent
    // m3u8 / mpd already covers the stream.
    if (PATTERNS.m4sSegment.test(data.url)) {
      return false;
    }
    data.type = PATTERNS.ts.test(data.url) ? 'ts' : 'media';
    return true;
  }

  if (lowerCT.includes('text/html') || lowerCT.includes('text/plain')) {
    const noSniff = getHeader(headers, 'x-content-type-options');
    if (noSniff?.toLowerCase().includes('nosniff')) {
      return false;
    }
    if (PATTERNS.html.test(data.url)) {
      return false;
    }
    return classifyByUrl(data);
  }

  if (lowerCT.includes('octet-stream') || lowerCT.includes('binary')) {
    return classifyByUrl(data);
  }

  if (lowerCT.includes('application/json')) {
    if (PATTERNS.youtubeTimedText.test(data.url)) {
      data.type = 'timedtext';
      return true;
    }
    return false;
  }

  return classifyByUrl(data);
}

// ---------------------------------------------------------------------------
// Response processing
// ---------------------------------------------------------------------------

async function processResponse(data, listenerName, skipClassify = false) {
  // EARLY DIAGNOSTIC: log every interesting URL the listener sees, before
  // any filtering. If this never fires, listeners aren't being called.
  if (DEBUG && isInteresting(data.url, data.type)) {
    console.log(
      '[req] listener-saw',
      data.url,
      `[${listenerName}] tabId=${data.tabId} type=${data.type} doc=${data.documentUrl} orig=${data.originUrl}`
    );
  }

  // Drop events whose document/origin is the extension itself.
  // These come from our own HEAD probe (background page fetch). The probe
  // exists only to populate the URL header cache via onSendHeaders; its
  // response events must not be forwarded to native, otherwise Java sees
  // duplicate captures with originUrl=moz-extension://...
  // Synthetic content-script messages are exempt because they explicitly
  // set documentUrl and originUrl to tab.url (real https://).
  const docExt = data.documentUrl && data.documentUrl.startsWith('moz-extension://');
  const orgExt = data.originUrl && data.originUrl.startsWith('moz-extension://');
  if (docExt || orgExt) {
    if (DEBUG) {
      // Log every drop, not just "interesting" URLs, so we can see whether
      // page subresources are being collateral damage.
      console.log(
        '[req] drop:ext-context',
        data.url,
        `listener=${listenerName} type=${data.type} tabId=${data.tabId}`,
        `doc=${data.documentUrl}`,
        `orig=${data.originUrl}`
      );
    }
    return;
  }

  const interesting = isInteresting(data.url, data.type);

  if (interesting) {
    dlog('enter', data.url, `[${listenerName}] tabId=${data.tabId} type=${data.type}`);
  }

  // skipClassify: the manifest body-sniff (below) already proved this is a
  // playable HLS/DASH manifest from its bytes (#EXTM3U / <MPD>), so the
  // header/extension classifier — which correctly rejects it (no media
  // extension, lied-about text/html mime) — must be bypassed for it. data.type
  // is set to 'media' by the caller, so it rides the normal media-capture path.
  if (!skipClassify && !validateAndClassify(data)) {
    return;
  }

  let pending = pendingRequests.get(data.requestId);
  if (!pending) {
    if (interesting) dlog('synth-pending', data.url, `requestId=${data.requestId}`);
    let headers = data.requestHeaders;
    if (!headers || !headers.length) {
      const cached = getCachedHeaders(data.url);
      if (cached) {
        headers = cached.fromExtensionContext
          ? sanitizeHeadersForPage(cached.headers, data.documentUrl || data.originUrl)
          : cached.headers;
      }
    }
    pending = {
      requestId: data.requestId,
      tabId: data.tabId,
      url: data.url,
      method: data.method || 'GET',
      frameId: data.frameId,
      parentFrameId: data.parentFrameId,
      documentUrl: data.documentUrl,
      originUrl: data.originUrl,
      requestHeaders: headers || [],
      timestamp: Date.now(),
    };
  } else if (!pending.requestHeaders || !pending.requestHeaders.length) {
    // Existing pending entry but it lacks headers — try the URL cache
    const cached = getCachedHeaders(data.url);
    if (cached) {
      pending.requestHeaders = cached.fromExtensionContext
        ? sanitizeHeadersForPage(cached.headers, data.documentUrl || data.originUrl)
        : cached.headers;
    }
  }

  const tabId = await resolveTabId(data);

  let incognito = false;
  if (tabId >= 0) {
    try {
      const tab = await browser.tabs.get(tabId);
      incognito = tab?.incognito || false;
    } catch (e) {
      // tab closed in flight
    }
  }

  const message = {
    ...pending,
    tabId,
    url: data.url,
    originUrl: data.originUrl,
    type: data.type,
    listener: listenerName,
    incognito,
  };

  delete message.timestamp;

  // For media types, ask the page's content script for its live title +
  // meta description so the native side can build a descriptive filename
  // instead of the URL slug. We only do this for "media" (audio/video
  // elements) and "object" (some streaming players use <object> tags) —
  // images get their alt-text / URL slug, scripts/styles/etc. are not
  // user-saveable. The query is fire-and-forget with a short timeout;
  // if the content script isn't there yet or the page blocks messaging,
  // the message just goes without the enriched fields.
  if ((data.type === 'media' || data.type === 'object') && tabId >= 0) {
    try {
      // Pass the captured URL so the content script can classify a standalone
      // audio file's ROLE on the page (main content vs incidental) — see below.
      const meta = await Promise.race([
        browser.tabs.sendMessage(tabId, { kind: 'get-page-metadata', mediaUrl: data.url }),
        new Promise((resolve) => setTimeout(() => resolve(null), 300)),
      ]);
      if (meta) {
        // A standalone audio file is enriched with the page metadata ONLY when
        // the page presents it as main content (a declared AudioObject/og:audio,
        // or a user-facing <audio>/<video controls> bound to the URL — audioRole
        // 'content'). Otherwise it is an incidental sound (notification ding, UI
        // sfx, background music) that would be mislabelled by the page headline
        // (series.ly /audio/notification.mp3 → "The Breadwinner"), so it keeps
        // its URL filename. Video, HLS/DASH manifests and tokenized/extensionless
        // URLs are always enriched (urlIsStandaloneAudio is false for them).
        //
        // EMBEDDED-PLAYER EXCEPTION: a media-element request (type media/object)
        // from a SUBFRAME (frameId > 0) is an embedded audio player — e.g. a
        // cross-origin "listen to this article"/podcast iframe. Its <audio>
        // binding lives in the iframe, which the TOP-frame metadata responder
        // (window === window.top only) cannot see, so audioRole comes back
        // 'unknown' even though the audio is plainly content. Treat a subframe
        // media-element audio as content and let it inherit the top-frame
        // og:title (the page the embed sits on). A top-page incidental ding is
        // frameId 0 (played via `new Audio()` in the main document), so it is
        // NOT upgraded — the series.ly case is preserved. (NOA itself is now
        // parser-owned + block-listed; this covers the other embed providers.)
        const embeddedSubframeAudio = typeof data.frameId === 'number' && data.frameId > 0;
        const incidentalAudio = urlIsStandaloneAudio(data.url)
          && meta.audioRole !== 'content' && !embeddedSubframeAudio;
        if (!incidentalAudio) {
          // Prefer the most specific source first: a declared AudioObject (for
          // main-content audio), then video-specific sources — on video SPAs
          // (YouTube, etc.) the JSON-LD VideoObject / og:video:title carry the
          // real current name while <title>/og:title are generic ("YouTube") —
          // then the page-level chain.
          //   name → audioLd > videoLd(by-url) > videoLd(page) > og:video:title > og:title > twitter:title > title
          //   description → audioLd > videoLd(by-url) > videoLd(page) > meta description > og:description > twitter:description
          // videoLdMatch* is a VideoObject whose contentUrl IS this captured URL
          // (clip-specific), so it outranks the page-level videoLd/og — this is
          // what gives each clip on a multi-video page its own title.
          // Native side sanitises both; we keep them as the raw page strings here.
          const name = meta.audioLdName || meta.videoLdMatchName || meta.videoLdName
            || meta.ogVideoTitle || meta.ogTitle || meta.twitterTitle || meta.title || '';
          const description = meta.audioLdDescription || meta.videoLdMatchDescription
            || meta.videoLdDescription || meta.description || meta.ogDescription
            || meta.twitterDescription || '';
          // Thumbnail: the page's poster, ranked most-specific first. The native
          // side stores it as the entity's thumbnail (JsonHelper "img" →
          // setFileThumbnail), and GlideHelper then loads that image directly
          // instead of pointing FFmpeg at the media URL to decode a frame — a
          // plain JPEG fetch in place of a video-demux probe. The generic catcher
          // never sets img itself, so this is the only source for it; a dedicated
          // parser that already supplied img is not overwritten.
          // A per-URL VideoObject thumbnail (clip-specific) outranks even the
          // page poster — on a multi-video page the single <video poster> /
          // og:image would otherwise stamp every clip with the same image.
          const img = meta.videoLdMatchThumbnail || meta.poster || meta.videoLdThumbnail
            || meta.audioLdThumbnail || meta.ogImage || '';
          if (name && !message.name) message.name = name;
          if (description && !message.description) message.description = description;
          if (img && !message.img) message.img = img;
        }
      }
    } catch (e) {
      // Content script not loaded (file://, about:, restricted) — fine, skip.
    }
  }

  // Backfill a Referer for media downloads that lack one. Some CDNs —
  // notably Bilibili's upos/bilivideo (incl. its akamaized.net mirrors) —
  // serve exactly the first ~1 MiB then cut the connection ("unexpected end
  // of stream") when the request has no Referer. The cross-site <video> fetch
  // we captured usually had its Referer stripped by the page's referrer-policy,
  // so the captured headers carry Origin but no Referer. Use the page URL as
  // Referer — the same value sanitizeHeadersForPage injects for cached headers.
  // Only added when absent, so sites that supplied their own Referer are
  // untouched.
  if ((data.type === 'media' || data.type === 'object') && Array.isArray(message.requestHeaders)) {
    const pageUrl = data.documentUrl || data.originUrl;
    const hasReferer = message.requestHeaders.some((h) => h.name.toLowerCase() === 'referer');
    if (pageUrl && !hasReferer) {
      message.requestHeaders = message.requestHeaders.concat([{ name: 'Referer', value: pageUrl }]);
      dlog('referer-backfill', data.url, `referer=${pageUrl}`);
    }
  }

  if (interesting) {
    const hdrCount = (message.requestHeaders || []).length;
    dlog('forward', data.url, `tabId=${tabId} type=${data.type} headers=${hdrCount} listener=${listenerName}`);
  }

  try {
    browser.runtime.sendNativeMessage('browser', message);
  } catch (e) {
    if (DEBUG) console.warn('[req] sendNativeMessage failed:', e?.message);
  }
}

// ---------------------------------------------------------------------------
// webRequest listeners
// ---------------------------------------------------------------------------

browser.webRequest.onSendHeaders.addListener(
  (data) => {
    if (DEBUG && isInteresting(data.url, data.type)) {
      dlog('onSendHeaders', data.url, `tabId=${data.tabId} type=${data.type}`);
    }
    harvestAmbientHeaders(data.requestHeaders);
    addPendingRequest(data);
  },
  { urls: ['<all_urls>'] },
  ['requestHeaders']
);

// ---------------------------------------------------------------------------
// Ambient browser headers (cross-extension)
//
// Accept-Language and User-Agent are browser-GLOBAL — identical on every request
// Gecko makes — so any request we observe yields the EXACT strings the browser
// sends. The parser@ extension's page-world HLS capture (parser/page-state-bridge)
// must look like a real browser to the stream CDN's anti-bot, but it can't read
// these itself (its webRequest is host-scoped, and a content script can't read
// request headers), and RECONSTRUCTING Accept-Language is exactly what once cost
// a 403 (a missing ";q=0.9"). This catcher already sees every request on
// <all_urls>, so it harvests the real values and hands them to parser@ on
// request — no reconstruction, no widened permissions.
// ---------------------------------------------------------------------------

let ambientAcceptLanguage = null;
let ambientUserAgent = null;

function harvestAmbientHeaders(requestHeaders) {
  if (!requestHeaders) return;
  for (const h of requestHeaders) {
    const n = h.name.toLowerCase();
    if (n === 'accept-language') { if (h.value) ambientAcceptLanguage = h.value; }
    else if (n === 'user-agent') { if (h.value) ambientUserAgent = h.value; }
  }
}

// Direct intra-extension access to the harvested ambient headers. The parser
// background modules (js/parsers/) live in THIS extension's background page,
// so they import and read the values synchronously. (The old cross-extension
// get-ambient-headers onMessageExternal round-trip from the standalone
// parser@ extension was removed with the merge — runtime.sendMessage to one's
// own id goes to onMessage, never onMessageExternal, so that listener could
// never fire again; the interim globalThis.__getAmbientHeaders bridge went
// with the parsers' module conversion.) A getter (not a snapshot) so callers
// always see the latest values harvested from the wire.
export function getAmbientHeaders() {
  return {
    acceptLanguage: ambientAcceptLanguage,
    userAgent: ambientUserAgent
  };
}

browser.webRequest.onHeadersReceived.addListener(
  (data) => processResponse(data, 'onHeadersReceived'),
  { urls: ['<all_urls>'] },
  ['responseHeaders']
);

browser.webRequest.onResponseStarted.addListener(
  (data) => processResponse(data, 'onResponseStarted'),
  { urls: ['<all_urls>'] },
  ['responseHeaders']
);

browser.webRequest.onCompleted.addListener(
  (data) => pendingRequests.delete(data.requestId),
  { urls: ['<all_urls>'] }
);

browser.webRequest.onErrorOccurred.addListener(
  (data) => {
    if (DEBUG && isInteresting(data.url, data.type)) {
      dlog('onErrorOccurred', data.url, `error=${data.error}`);
    }
    pendingRequests.delete(data.requestId);
  },
  { urls: ['<all_urls>'] }
);

// ---------------------------------------------------------------------------
// Obfuscated-manifest body sniff (filterResponseData)
//
// HLS/DASH in GeckoView always play through hls.js / dash.js over MSE — those
// fetch the manifest and parse its BODY (#EXTM3U / <MPD>), ignoring the HTTP
// Content-Type entirely. So a site can serve a real playlist at an extensionless
// URL with a bogus `text/html` mime and it still plays, while the header/
// extension classifier (validateAndClassify) correctly drops it — a capture
// miss. This peeks the first bytes of exactly those rejected-but-suspect
// responses for the manifest magic and, on a hit, emits a normal media capture
// (download is muxed by ffmpeg; HttpDownloadStrategy's #EXTM3U/<MPD> content
// backstop covers it even if it lands on the raw path).
//
// Cost-bounded on purpose (per design): it only ARMS a filter for the narrow
// candidate set below (and skips a body a Content-Length declares too big to be
// a playlist). It INSPECTS at most SNIFF_MAX_BYTES, but stays a transparent
// write-through for the WHOLE body — every chunk is written straight back,
// byte-exact, and it NEVER disconnect()s mid-stream. That is deliberate: a
// disconnect() over a ServiceWorker-*synthesized* response (the stream the
// firedown-geckoview 0006 patch exposes) has no confirmation it resumes the
// remainder cleanly, so rather than risk truncating the page's own fetch we just
// stop reading after the first KB and let the rest flow through, then close() at
// onstop — the same proven pattern as the parser's filterResponseText. Most
// responses fail the candidate gate without ever touching a filter.
// ---------------------------------------------------------------------------

const SNIFF_MAX_BYTES = 1024;
// A real playlist is small; never arm on a response that DECLARES a body far
// larger than any manifest, so we don't write-through a big text/html /
// octet-stream body just to read its first KB. (Chunked / unknown-length still
// arms — those are usually small, and the inspection itself is capped anyway.)
const SNIFF_MAX_DECLARED_BYTES = 4 * 1024 * 1024;

// Debug-only running tally so an on-device logcat (`adb logcat -s GeckoConsole:*`,
// grep `manifest-sniff`) shows how often the filter arms and its hit/miss split.
// All of it is gated on DEBUG — release builds increment nothing and log nothing.
const sniffStats = { armed: 0, hls: 0, dash: 0, miss: 0, unavailable: 0 };
function logSniff(tag, url, verdict) {
  if (!DEBUG) return;
  dlog(
    `manifest-sniff:${tag}`,
    url,
    `verdict=${verdict} armed=${sniffStats.armed} hls=${sniffStats.hls} `
      + `dash=${sniffStats.dash} miss=${sniffStats.miss} unavail=${sniffStats.unavailable}`
  );
}

// The only shape an obfuscated manifest can take: a GET the classifier rejects,
// fetched by a JS player (xhr/fetch → 'xmlhttprequest'/'other'), with no media
// extension and a non-media, manifest-plausible content-type. Media/image/json
// content-types and media-extension URLs are already captured (or are data) on
// the normal path and must never be re-sniffed here.
function isManifestSniffCandidate(data) {
  if (data.method && data.method !== 'GET') return false;
  if (data.type !== 'xmlhttprequest' && data.type !== 'other') return false;
  if (getTypeFromUrl(data.url)) return false;            // real media extension → normal path
  if (RegexMap.matchInRegex(data.url)) return false;     // generic junk / blocked
  if (ParserBlock.matchInParserBlocklist(data.url)) return false; // parser-owned media
  const declaredLen = parseInt(getHeader(data.responseHeaders, 'content-length') || '', 10);
  if (Number.isFinite(declaredLen) && declaredLen > SNIFF_MAX_DECLARED_BYTES) return false;
  const ct = (getHeader(data.responseHeaders, 'content-type') || '').toLowerCase();
  if (ct.includes('text/html')) {
    // A server asserting nosniff on text/html means "this really is HTML" — the
    // classifier hard-rejects it too; don't bother sniffing.
    const noSniff = getHeader(data.responseHeaders, 'x-content-type-options');
    if (noSniff && noSniff.toLowerCase().includes('nosniff')) return false;
    return true;
  }
  // Empty / generic mimes an obfuscator hides a playlist behind. (text/plain is
  // also the honest mime some CDNs serve playlists with, yet the URL carried no
  // extension so classifyByUrl still dropped it.)
  return !ct
    || ct.includes('text/plain')
    || ct.includes('octet-stream')
    || ct.includes('application/binary');
}

// First-bytes verdict: 'hls' | 'dash' | 'no' | 'more'. HLS playlists MUST begin
// with #EXTM3U (after an optional BOM/whitespace); DASH MPDs carry a <MPD root
// (after an optional <?xml …?> prolog). Anything starting with another char
// (JSON '{'/'[', an HTML text node, a letter) is decided 'no' immediately.
function decideManifest(text) {
  const t = text.replace(/^\uFEFF/, '').replace(/^\s+/, '');
  if (!t) return 'more';
  if (t[0] === '#') {
    if (t.length < 7) return 'more';
    return t.startsWith('#EXTM3U') ? 'hls' : 'no';
  }
  if (t[0] === '<') {
    if (/<MPD[\s>]/.test(t)) return 'dash';
    return text.length >= SNIFF_MAX_BYTES ? 'no' : 'more'; // else still scanning past the prolog
  }
  return 'no';
}

browser.webRequest.onHeadersReceived.addListener(
  (data) => {
    if (!isManifestSniffCandidate(data)) return;
    let filter;
    try {
      filter = browser.webRequest.filterResponseData(data.requestId);
    } catch (e) {
      // Not interceptable (e.g. cached / non-2xx) — leave the stream alone.
      if (DEBUG) { sniffStats.unavailable++; logSniff('unavailable', data.url, 'n/a'); }
      return;
    }
    if (DEBUG) sniffStats.armed++;
    const decoder = new TextDecoder('utf-8', { fatal: false });
    let acc = '';
    let done = false;

    const finish = (kind) => {
      if (done) return;
      done = true;
      // Deliberately NO filter.disconnect() — see the header comment. We've
      // decided from the first <=1KB; the filter stays attached as a transparent
      // write-through to onstop/close so a (possibly SW-synthesized) response is
      // never truncated. Subsequent chunks are passed on unread.
      if (DEBUG) {
        if (kind === 'hls') sniffStats.hls++;
        else if (kind === 'dash') sniffStats.dash++;
        else sniffStats.miss++;
        logSniff(kind === 'hls' || kind === 'dash' ? 'hit' : 'miss', data.url, kind);
      }
      if (kind === 'hls' || kind === 'dash') {
        // Force the media classification we just proved by content, then ride
        // the normal emit path (header recovery, tab/meta, native dedup).
        processResponse({ ...data, type: 'media' }, 'manifestSniff', true);
      }
    };

    filter.ondata = (event) => {
      filter.write(event.data); // byte-exact pass-through, every chunk, to the end
      if (done) return;         // already decided — keep passing through, unread
      try {
        // Decode only enough bytes to reach the cap — never turn a large first
        // chunk into a large string. `acc` is therefore provably <=
        // SNIFF_MAX_BYTES; the full chunk was already written through above. We
        // only ever decode a truncated slice on the chunk that fills the cap,
        // after which `done` is set, so the stream decoder is never resumed past
        // a slice boundary.
        const view = new Uint8Array(event.data);
        const need = SNIFF_MAX_BYTES - acc.length;
        const slice = view.length > need ? view.subarray(0, need) : view;
        acc += decoder.decode(slice, { stream: true });
        const verdict = decideManifest(acc);
        if (verdict !== 'more') finish(verdict);
        else if (acc.length >= SNIFF_MAX_BYTES) finish('no');
      } catch (e) {
        // An inspection error must never stall the (already-written) pass-through
        // — just stop sniffing this response and let it stream to completion.
        done = true;
        if (DEBUG) dlog('manifest-sniff:err', data.url, e && e.message);
      }
    };
    filter.onstop = () => {
      // Short body that never tripped the cap — decide on what we have, then end
      // the (fully written-through) stream.
      if (!done) finish(decideManifest(acc));
      try { filter.close(); } catch (e) { /* nothing more to flush */ }
    };
    filter.onerror = () => { try { filter.close(); } catch (e) {} };
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders', 'blocking']
);

// ---------------------------------------------------------------------------
// Native port
// ---------------------------------------------------------------------------

const nativePort = browser.runtime.connectNative('browser');

nativePort.onMessage.addListener(async (msg) => {
  if (msg.type === 'getCookiesForUrl') {
    const result = await handleCookieRequest(msg);
    if (result) {
      nativePort.postMessage(result);
    }
  }
});

// ---------------------------------------------------------------------------
// Content script messages
// Catches images loaded from service-worker cache (invisible to webRequest)
// ---------------------------------------------------------------------------

browser.runtime.onMessage.addListener(async (msg, sender) => {
  // Content script told us a page tried to use WebAssembly while it's
  // disabled. Forward to native so BrowserFragment can surface the
  // "Enable for {host}?" snackbar scoped to the right tab.
  if (msg?.kind === 'wasm-unavailable') {
    try {
      browser.runtime.sendNativeMessage('browser', {
        listener: 'wasmUnavailable',
        url: msg.url,
        tabId: sender?.tab?.id ?? -1,
        detail: msg.detail || '',
      });
    } catch (e) {
      if (DEBUG) console.warn('[req] wasm-unavailable forward failed:', e?.message);
    }
    return;
  }

  if (msg?.kind !== 'images-detected') return;
  if (!Array.isArray(msg.urls)) return;

  const tab = sender.tab;
  if (!tab) return;

  for (const url of msg.urls) {
    if (!url || !/^https?:/i.test(url)) continue;

    const key = tab.id + '|' + url;
    if (contentScriptSeen.has(key)) continue;
    contentScriptSeen.add(key);
    if (contentScriptSeen.size > CONTENT_SCRIPT_DEDUPE_MAX) {
      const toRemove = [...contentScriptSeen].slice(0, CONTENT_SCRIPT_DEDUPE_MAX / 2);
      toRemove.forEach((k) => contentScriptSeen.delete(k));
    }

    // Try to recover headers from a previous webRequest pass
    let cached = getCachedHeaders(url);

    // If we don't have them, do a HEAD probe to populate the cache via
    // onSendHeaders. The browser attaches normal cookies/UA. The probe runs
    // in extension context so its cached entry is tagged
    // fromExtensionContext=true, and we sanitize Origin/Referer/Sec-Fetch-*
    // before forwarding.
    if (!cached) {
      if (DEBUG) dlog('cs-head-probe', url);
      try {
        await fetch(url, {
          method: 'HEAD',
          credentials: 'include',
          cache: 'no-store',
          referrer: tab.url,
        });
        cached = getCachedHeaders(url);
      } catch (e) {
        if (DEBUG) dlog('cs-head-failed', url, e?.message);
      }
    }

    let requestHeaders = null;
    if (cached) {
      requestHeaders = cached.fromExtensionContext
        ? sanitizeHeadersForPage(cached.headers, tab.url)
        : cached.headers;
    }

    // Belt-and-braces: if we ended up with non-sanitized page-context headers
    // that happen to lack Referer (some same-origin requests), backfill it.
    if (requestHeaders && tab.url) {
      const hasReferer = requestHeaders.some(
        (h) => h.name.toLowerCase() === 'referer'
      );
      if (!hasReferer) {
        requestHeaders = [
          ...requestHeaders,
          { name: 'Referer', value: tab.url },
        ];
      }
    }

    const synthetic = {
      requestId: 'cs-' + tab.id + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6),
      url,
      tabId: tab.id,
      type: 'image',
      method: 'GET',
      frameId: 0,
      parentFrameId: -1,
      documentUrl: tab.url,
      originUrl: tab.url,
      requestHeaders: requestHeaders || [],
      responseHeaders: [],
    };

    if (DEBUG) {
      const hc = requestHeaders ? requestHeaders.length : 0;
      dlog('cs-image', url, `tabId=${tab.id} headers=${hc}`);
    }
    processResponse(synthetic, 'contentScript');
  }
});