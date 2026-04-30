import * as RegexMap from './regex.js';
import { handleCookieRequest } from './cookies.js';

// Configuration
const MAX_PENDING_REQUESTS = 1024;
const REQUEST_TIMEOUT_MS = 30000;
const TAB_ORIGIN_CACHE_MS = 5000;
const HEADER_CACHE_MAX = 2048;
const HEADER_CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes
const CONTENT_SCRIPT_DEDUPE_MAX = 5000;

// Set DEBUG to false to silence diagnostic logs in production
const DEBUG = true;

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
  }
});

browser.tabs.query({ active: true, currentWindow: true })
  .then((tabs) => {
    if (tabs[0]) lastActiveTabId = tabs[0].id;
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

function isMediaContentType(contentType) {
  return MEDIA_CONTENT_TYPES.some((type) => contentType.includes(type));
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

  if (type === 'media' || type === 'imageset' || type === 'image') {
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

async function processResponse(data, listenerName) {
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

  if (!validateAndClassify(data)) {
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

  if (interesting) {
    const hdrCount = (message.requestHeaders || []).length;
    dlog('forward', data.url, `tabId=${tabId} type=${data.type} headers=${hdrCount} listener=${listenerName}`);
  }

  try {
    browser.runtime.sendNativeMessage('browser', message);
  } catch (e) {
    console.warn('[req] sendNativeMessage failed:', e?.message);
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
    addPendingRequest(data);
  },
  { urls: ['<all_urls>'] },
  ['requestHeaders']
);

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