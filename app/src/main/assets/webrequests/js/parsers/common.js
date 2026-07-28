// ============================================================================
// Shared parser infrastructure — split verbatim out of the former
// parser-background.js (the per-site parsers live in the sibling modules).
// Everything here is site-agnostic: logging, dedup, native messaging, the
// HLS-master enumeration helpers, response-filter capture, tab resolution,
// text utils, and the two registries (SPA navigation + message router) the
// site modules plug into.
// ============================================================================
import { DEBUG } from '../debug.js';

// ============================================================================
// Utilities
// ============================================================================

function log(category, message, data = null) {
    if (!DEBUG) return;
    const ts = new Date().toISOString().slice(11, 23);
    const prefix = `[${ts}][${category}]`;
    if (data !== null && data !== undefined) {
        console.log(prefix, message, typeof data === "object" ? JSON.stringify(data) : data);
    } else {
        console.log(prefix, message);
    }
}

function tryParseJson(str) {
    try { return JSON.parse(str); } catch { return null; }
}

// ============================================================================
// Dedup — keyed on origin URL (stable across CDN rotations)
// ============================================================================

const sentOrigins = new Set();
const SENT_ORIGIN_TTL = 30_000;

// Dedup is per (tabId, origin), NOT per origin alone. The same video opened in a
// SECOND tab must still capture — the repository dedups per tabId, so a global
// origin-only key wrongly suppressed the second tab for the whole 30s TTL (open
// tab A → captures; open tab B on the same URL within 30s → silently dropped;
// after the TTL it works again). Keying on the tab fixes that while still
// collapsing a single load's multiple emits (e.g. the progressive variant + the
// per-quality HLS masters a player exposes) and rapid same-tab refreshes. A
// missing/negative tabId (rare embed paths with no resolved tab) falls back to
// the bare origin — the old global behavior, no regression.
function sentKey(origin, tabId) {
    return (typeof tabId === "number" && tabId >= 0) ? (tabId + " " + origin) : origin;
}

// Mixed-attribution guard: two emits for the SAME video can resolve DIFFERENT
// tabIds when one path fails to attribute (resolveTabId → -1 → bare-origin key)
// while the other lands on the real tab — e.g. Bluesky's xrpc JSON reader and
// its wire-master listener both emit the same hls-master from different
// `details`. Tab-scoped keys alone would then miss each other and double-emit.
// So: a real-tab check ALSO treats a bare-origin mark as a hit (an unattributed
// emit already claimed this video), and an unattributed check treats ANY tab's
// mark as a hit (it cannot tell which tab it belongs to, so the conservative
// old global behavior applies — the scan is fine, the set is tiny under a 30s
// TTL). Genuine multi-tab captures are unaffected: two real tabs produce two
// tab-scoped keys and a bare key is never written for an attributed emit.
function alreadySent(origin, tabId) {
    if (sentOrigins.has(sentKey(origin, tabId))) return true;
    if (typeof tabId === "number" && tabId >= 0) {
        return sentOrigins.has(origin);
    }
    for (const key of sentOrigins) {
        if (key.endsWith(" " + origin)) return true;
    }
    return false;
}

function markSent(origin, tabId) {
    const key = sentKey(origin, tabId);
    sentOrigins.add(key);
    setTimeout(() => sentOrigins.delete(key), SENT_ORIGIN_TTL);
}

// ============================================================================
// Own-request tracking — prevents intercepting our own fetches
// ============================================================================

const ownRequests = new Map();
const OWN_REQUEST_TTL = 10_000;

function markOwnRequest(url) {
    ownRequests.set(url, Date.now());
    if (ownRequests.size > 50) {
        const now = Date.now();
        for (const [u, ts] of ownRequests) { if (now - ts > OWN_REQUEST_TTL) ownRequests.delete(u); }
    }
}

function isOwnRequest(url) {
    for (const [ownUrl, ts] of ownRequests) {
        if (Date.now() - ts > OWN_REQUEST_TTL) {
            ownRequests.delete(ownUrl);
            continue;
        }
        if (url === ownUrl || url.startsWith(ownUrl)) {
            ownRequests.delete(ownUrl);
            return true;
        }
    }
    return false;
}

// ============================================================================
// Native messaging
// ============================================================================

async function sendNative(message) {
    try {
        // Resolve incognito state from tab if not already set
        if (message.incognito === undefined && message.tabId >= 0) {
            try {
                const tab = await browser.tabs.get(message.tabId);
                message.incognito = tab?.incognito || false;
            } catch (e) {
                message.incognito = false;
            }
        }
        log("NATIVE", `Sending message`, { url: message.url?.slice(0, 100), origin: message.origin, incognito: message.incognito });
        const response = await browser.runtime.sendNativeMessage("parser", message);
        log("NATIVE", `Received response`, response);
        return response;
    } catch (error) {
        log("NATIVE", `Error sending message`, error.message);
        return null;
    }
}

/**
 * Unified variant sender for Twitter, Instagram, and future parsers.
 * Handles dedup, sorting, and message construction.
 */
async function sendVariants(details, { variants, origin, description, img, name, duration, requestHeaders, skipProbe, manifest, dedupKey }) {
    if (!Array.isArray(variants) || variants.length === 0) return;

    // If the parser already has what the capture-time ffmpeg probe would supply
    // — a known duration on progressive (single-URL) variants — skip the probe.
    // Its other outputs are redundant here: the container is mp4, the codecs are
    // unused downstream, and it doesn't reject bad URLs anyway (it commits the
    // entity regardless). This is why Instagram/Threads/Facebook/TikTok (which
    // all carry url + resolution + duration from their APIs) no longer probe.
    // Variants with a separate audioUrl (e.g. Bilibili DASH) are EXCLUDED — they
    // mux at download and we don't trust split-track metadata blindly. Callers
    // that must force it (HLS renditions, niconico) still pass skipProbe = true.
    if (!skipProbe && duration > 0 && variants.every(v => !v.audioUrl)) {
        skipProbe = true;
    }

    // Sort by height descending — best quality first
    variants.sort((a, b) => (b.height || 0) - (a.height || 0));

    // Dedup by (tabId, KEY). The key is the page origin by default — correct for a
    // per-site parser, where a page is ONE video and origin-dedup collapses its
    // refresh/retry re-emits. But a generic page-state-bridge page can hold MANY
    // distinct clips (multiple <video>/players in one document), all sharing the
    // SAME page origin — origin-dedup would drop every clip after the first. Such
    // callers pass a per-CLIP `dedupKey` (the primary media URL) so each clip gets
    // through; cross-pass re-emit of the SAME clip is still prevented by the
    // bridge's own URL `sentKeys` and the repository's url-hash dedup. Resolve the
    // tab first so the key matches the tab the entity is attributed to.
    const tabId = await resolveTabId(details);
    const key = dedupKey || origin;

    if (alreadySent(key, tabId)) {
        log("DEDUP", `Already sent for ${key} (tab ${tabId}), skipping`);
        return;
    }
    markSent(key, tabId);

    log("VARIANTS", `Sending ${variants.length} variant(s)`, { origin });

    // Resolve incognito state from the tab
    let incognito = false;
    if (tabId >= 0) {
        try {
            const tab = await browser.tabs.get(tabId);
            incognito = tab?.incognito || false;
        } catch (e) {}
    }

    const message = {
        url: variants[0].url,
        type: "variants",
        origin,
        tabId,
        request: details.requestId,
        variants,
        incognito
    };

    if (description) message.description = decodeHtmlEntities(description);
    if (img) message.img = img;
    if (name) message.name = decodeHtmlEntities(name);
    if (duration > 0) message.duration = duration;
    if (Array.isArray(requestHeaders) && requestHeaders.length > 0) {
        message.requestHeaders = requestHeaders;
    }
    // skipProbe: parser already supplied codecs/duration, so the Java side must
    // not FFprobe (probing an AES-HLS variant burns a single-use key — niconico).
    if (skipProbe) message.skipProbe = true;
    // manifest: these variant URLs are HLS/DASH playlists ffmpeg must mux (not
    // progressive files). Declared by the enumerator so Java needn't guess from
    // the URL extension — robust against obfuscated/tokenized manifests and
    // #fragment / ?query tails. (Separate video+audio pairs are detected from
    // audioUrl and don't need this.)
    if (manifest) message.manifest = true;

    // Last JS link in the filename chain: exactly what native receives as the
    // capture's name. Java continues the trace under the FileNameTrace tag, so
    // a wrong download name can be pinned to one side of the bridge without
    // guessing. Truncated — a page title is unbounded page-controlled text.
    log("VARIANTS", "emit name", {
        name: message.name ? String(message.name).slice(0, 128) : "(none)",
        skipProbe: !!skipProbe
    });

    sendNative(message);
}

// ---- Shared HLS master enumeration ----------------------------------------
// Parse a master playlist into quality variants WITHOUT decoding, so a parser
// can populate the quality picker with no capture-time ffprobe. Handles muxed
// renditions (one STREAM-INF = full A/V → single-URL variant, e.g. Twitch/Kick)
// and split audio (EXT-X-MEDIA TYPE=AUDIO referenced via AUDIO="group"),
// resolves relative URLs, and skips I-frame trick-play streams (their tag is
// #EXT-X-I-FRAME-STREAM-INF, which our prefix test ignores). Returns [] if the
// text isn't a master (no STREAM-INF) — callers then fall back to the raw URL.
function resolveUrl(u, base) {
    try { return new URL(u, base).href; } catch (e) { return u; }
}

function parseHlsMaster(text, baseUrl) {
    if (typeof text !== "string" || !text.includes("#EXT-X-STREAM-INF")) return [];
    const lines = text.split(/\r?\n/);
    const audios = {};
    const streams = []; // every STREAM-INF, pre-dedup
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (line.startsWith("#EXT-X-MEDIA:") && /TYPE=AUDIO/.test(line)) {
            const gid = (line.match(/GROUP-ID="([^"]+)"/) || [])[1];
            const uri = (line.match(/URI="([^"]+)"/) || [])[1];
            if (gid && uri) audios[gid] = resolveUrl(uri, baseUrl);
            continue;
        }
        if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
        const res = line.match(/RESOLUTION=(\d+)x(\d+)/) || [];
        const audioGroup = (line.match(/AUDIO="([^"]+)"/) || [])[1] || null;
        const codecs = (line.match(/CODECS="([^"]+)"/) || [])[1] || "";
        // BANDWIDTH only — must not match AVERAGE-BANDWIDTH (preceded by '-').
        const bw = parseInt((line.match(/[,:]BANDWIDTH=(\d+)/) || [])[1], 10) || 0;
        // Variant URL = next non-blank, non-tag line.
        let url = null;
        for (let j = i + 1; j < lines.length; j++) {
            const t = lines[j].trim();
            if (!t) continue;
            if (t.startsWith("#")) break;
            url = t;
            break;
        }
        if (!url) continue;
        streams.push({
            url: resolveUrl(url, baseUrl),
            width: parseInt(res[1], 10) || 0,
            height: parseInt(res[2], 10) || 0,
            audioGroup,
            bw,
            codecs
        });
    }
    if (streams.length === 0) return [];

    // Rank audio groups by the highest bandwidth of the streams referencing them
    // (EXT-X-MEDIA carries no bitrate of its own), best-first.
    const groupMaxBw = {};
    for (const s of streams) {
        if (s.audioGroup && audios[s.audioGroup]) {
            if (groupMaxBw[s.audioGroup] === undefined || s.bw > groupMaxBw[s.audioGroup]) {
                groupMaxBw[s.audioGroup] = s.bw;
            }
        }
    }
    const audioRanked = Object.keys(groupMaxBw)
        .sort((a, b) => groupMaxBw[b] - groupMaxBw[a])
        .map((g) => audios[g]);

    // Dedup videos by URL (keep the highest-bandwidth occurrence), best-first.
    const byUrl = new Map();
    for (const s of streams) {
        const prev = byUrl.get(s.url);
        if (!prev || s.bw > prev.bw) byUrl.set(s.url, s);
    }
    const vids = Array.from(byUrl.values())
        .sort((a, b) => (b.height - a.height) || (b.bw - a.bw));

    // Tier proportionally: map each video's rank to an audio rank so a higher
    // video never gets worse audio than a lower one (monotonic). One audio →
    // every video gets it; no audio groups (muxed master) → single-URL variants.
    const A = audioRanked.length;
    const V = vids.length;
    const variants = [];
    for (let i = 0; i < V; i++) {
        const s = vids[i];
        const v = { url: s.url, width: s.width, height: s.height, bitrate: s.bw || 0 };
        if (A > 0) {
            const idx = Math.min(A - 1, Math.floor((i * A) / V));
            v.audioUrl = audioRanked[idx];
        }
        if (/\bavc1/.test(s.codecs)) v.videoCodec = "h264";
        else if (/\bhvc1|\bhev1/.test(s.codecs)) v.videoCodec = "hevc";
        if (/\bmp4a/.test(s.codecs)) v.audioCodec = "aac";
        variants.push(v);
    }
    return variants;
}

// Passive response-body text capture (filterResponseData). Returns false if a
// filter couldn't be created. onText receives the body, or null on error.
function filterResponseText(details, onText) {
    let filter;
    try { filter = browser.webRequest.filterResponseData(details.requestId); }
    catch (e) { return false; }
    const chunks = [];
    filter.ondata = (event) => { chunks.push(new Uint8Array(event.data)); filter.write(event.data); };
    filter.onstop = () => {
        filter.close();
        const total = chunks.reduce((a, c) => a + c.byteLength, 0);
        const buf = new Uint8Array(total);
        let off = 0;
        for (const c of chunks) { buf.set(c, off); off += c.byteLength; }
        Promise.resolve().then(() => onText(new TextDecoder("utf-8").decode(buf)));
    };
    filter.onerror = () => { try { filter.close(); } catch (_) {} onText(null); };
    return true;
}

// Passive write-through body capture for the per-site response filters — the
// shape every site's JSON/document filter shared (verbatim, modulo log tag)
// before the module split. Contract differs from filterResponseText above:
// a create failure logs and returns false; an EMPTY body is skipped with a
// log (no callback); a filter error logs and closes (no callback). onBody
// runs OFF the filter callback (microtask) with (text, totalBytes) so it
// never holds the stream stop. Keep new parsers on this unless they need an
// error fallback (dailymotion.js re-fetches on filter failure) or
// chunk-level diagnostics (instagram.js).
function readFilteredBody(details, tag, label, onBody) {
    let filter;
    try {
        filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (e) {
        log(tag, `${label}: filter create failed`, { error: e.message });
        return false;
    }
    const chunks = [];
    filter.ondata = (event) => {
        chunks.push(new Uint8Array(event.data));
        filter.write(event.data); // pass through unmodified
    };
    filter.onstop = () => {
        filter.close();
        const total = chunks.reduce((acc, c) => acc + c.byteLength, 0);
        if (total === 0) { log(tag, `${label}: 0 bytes`); return; }
        const buf = new Uint8Array(total);
        let offset = 0;
        for (const c of chunks) { buf.set(c, offset); offset += c.byteLength; }
        const text = new TextDecoder("utf-8").decode(buf);
        Promise.resolve().then(() => onBody(text, total));
    };
    filter.onerror = () => {
        log(tag, `${label}: filter error`);
        try { filter.close(); } catch (_) {}
    };
    return true;
}

// readFilteredBody + JSON.parse, skipping (with a log) bodies that aren't JSON.
function readFilteredJson(details, tag, label, onParsed) {
    return readFilteredBody(details, tag, label, (text, total) => {
        const parsed = tryParseJson(text);
        if (!parsed) { log(tag, `${label}: not JSON`, { bytes: total, head: text.slice(0, 100) }); return; }
        onParsed(parsed, total);
    });
}

// Emit enumerated HLS variants (no ffprobe) or, on ANY failure, the single media
// URL. For an .m3u8 master we fetch+parse it (unless `body` is supplied). Does
// its own dedup — callers must NOT pre-markSent the origin.
// Hand an HLS master URL to native: Java OkHttp-fetches it (any headers — unlike
// a page fetch() which can't set Origin/Referer) and M3U8Parser enumerates the
// qualities, re-dispatching as variants+skipProbe (or a plain media capture on
// failure). Used for master-only / single-use-key sites (niconico, Kick, Twitch)
// so capture needs no ffmpeg probe and never burns a single-use AES key.
async function enumerateMasterNative(details, { url, origin, name, description, img, duration, requestHeaders }) {
    if (!url) return;

    const tabId = await resolveTabId(details);
    if (origin && alreadySent(origin, tabId)) { log("HLS", "already sent", { origin, tabId }); return; }
    if (origin) markSent(origin, tabId);
    let incognito = false;
    if (tabId >= 0) {
        try { incognito = (await browser.tabs.get(tabId))?.incognito || false; } catch (e) {}
    }

    const message = { type: "hls-master", url, origin, tabId, request: details.requestId, incognito };
    if (name) message.name = decodeHtmlEntities(name);
    if (description) message.description = decodeHtmlEntities(description);
    if (img) message.img = img;
    if (duration > 0) message.duration = duration;
    if (Array.isArray(requestHeaders) && requestHeaders.length > 0) message.requestHeaders = requestHeaders;

    log("HLS", `enumerate master via native: ${String(url).slice(0, 90)}`);
    sendNative(message);
}

// An .m3u8 master is enumerated in native (no ffprobe); anything else (a direct
// progressive URL) is emitted as a single media capture.
async function emitHlsMasterOrSingle(details, { url, origin, tabId, name, title, img, duration }) {
    if (/\.m3u8(?:[?#]|$)/.test(url)) {
        enumerateMasterNative(details, { url, origin, name, description: title, img, duration });
        return;
    }
    if (alreadySent(origin, tabId)) return;
    markSent(origin, tabId);
    sendNative({
        url,
        type: "media",
        origin,
        tabId,
        request: details.requestId,
        name: decodeHtmlEntities(name),
        description: decodeHtmlEntities(title),
        img,
        ...(duration > 0 ? { duration } : {})
    });
}

/**
 * Emits one native message per subtitle track. Each becomes its own
 * BrowserDownloadEntity on the Kotlin side (mime text/vtt or text/srt)
 * and surfaces under the Subtitle filter chip. Sharing `origin` with the
 * parent video keeps them grouped wherever the UI clusters by origin.
 *
 * subtitles: [{ url, language?, label?, format? }]
 */
async function sendSubtitles(details, { subtitles, origin, requestHeaders }) {
    if (!Array.isArray(subtitles) || subtitles.length === 0) return;

    const tabId = await resolveTabId(details);
    let incognito = false;
    if (tabId >= 0) {
        try {
            const tab = await browser.tabs.get(tabId);
            incognito = tab?.incognito || false;
        } catch (e) {}
    }

    for (const sub of subtitles) {
        if (!sub || !sub.url) continue;
        const message = {
            url: sub.url,
            type: "subtitle",
            origin,
            tabId,
            request: details.requestId,
            incognito
        };
        if (sub.language) message.language = sub.language;
        if (sub.label) message.name = sub.label;
        if (Array.isArray(requestHeaders) && requestHeaders.length > 0) {
            message.requestHeaders = requestHeaders;
        }
        sendNative(message);
    }
}

// ============================================================================
// Response filter (for intercepting Instagram API responses)
// ============================================================================

function collectFilteredResponse(details) {
    return new Promise((resolve, reject) => {
        let filter;
        try {
            filter = browser.webRequest.filterResponseData(details.requestId);
        } catch (e) {
            reject(new Error(`Failed to create filter: ${e.message}`));
            return;
        }

        const chunks = [];

        filter.ondata = (event) => {
            chunks.push(event.data);
            filter.write(event.data);
        };

        filter.onstop = () => {
            filter.close();
            const total = chunks.reduce((acc, c) => acc + c.byteLength, 0);
            const combined = new Uint8Array(total);
            let offset = 0;
            for (const chunk of chunks) {
                combined.set(new Uint8Array(chunk), offset);
                offset += chunk.byteLength;
            }
            resolve(new TextDecoder("utf-8").decode(combined));
        };

        filter.onerror = () => {
            // Already in errored state — close() itself throws
            // NS_ERROR_FAILURE on Gecko, which surfaces as a noisy
            // console error even though the rejection is handled.
            try { filter.close(); } catch (_) {}
            reject(new Error(`Filter error: ${filter.error}`));
        };
    });
}

// ============================================================================
// Tab ID resolution
// ============================================================================

const urlToTabCache = new Map();
const URL_TAB_CACHE_TTL = 30_000;


browser.tabs.onRemoved.addListener((tabId) => {
    for (const [url, entry] of urlToTabCache) {
        if (entry.tabId === tabId) urlToTabCache.delete(url);
    }
});

setInterval(() => {
    const now = Date.now();
    for (const [url, entry] of urlToTabCache) {
        if (now - entry.timestamp > URL_TAB_CACHE_TTL) urlToTabCache.delete(url);
    }
}, URL_TAB_CACHE_TTL);

function cacheTabUrl(url, tabId) {
    if (!url) return;
    urlToTabCache.set(url, { tabId, timestamp: Date.now() });
    try {
        const u = new URL(url);
        urlToTabCache.set(u.origin + u.pathname, { tabId, timestamp: Date.now() });
    } catch {}
}

async function resolveTabId(details) {
    if (details.tabId >= 0) return details.tabId;
    if (details._resolvedTabId >= 0) return details._resolvedTabId;

    const urlsToCheck = [details.originUrl, details.url, details.documentUrl].filter(Boolean);

    // Check cache
    for (const url of urlsToCheck) {
        const cached = urlToTabCache.get(url);
        if (cached && Date.now() - cached.timestamp < URL_TAB_CACHE_TTL) {
            details._resolvedTabId = cached.tabId;
            return cached.tabId;
        }
        try {
            const u = new URL(url);
            const base = urlToTabCache.get(u.origin + u.pathname);
            if (base && Date.now() - base.timestamp < URL_TAB_CACHE_TTL) {
                details._resolvedTabId = base.tabId;
                return base.tabId;
            }
        } catch {}
    }

    // Query tabs API
    try {
        // First: for embeds/iframes, match the parent page URL (originUrl/documentUrl)
        // against all open tabs by origin. This avoids misattributing embedded content
        // to a same-domain tab when the embed is on a third-party site.
        const parentUrls = [details.originUrl, details.documentUrl].filter(Boolean);
        if (parentUrls.length > 0) {
            const allTabs = await browser.tabs.query({ currentWindow: true });
            for (const pUrl of parentUrls) {
                try {
                    const pOrigin = new URL(pUrl).origin;
                    // Skip if parent is same domain as the embed (not a cross-site embed)
                    const embedOrigin = details.url ? new URL(details.url).origin : null;
                    if (pOrigin !== embedOrigin) {
                        const match = allTabs.find(t => t.url && t.url.startsWith(pOrigin));
                        if (match) { details._resolvedTabId = match.id; return match.id; }
                    }
                } catch {}
            }
        }

        for (const url of urlsToCheck) {
            const hostname = new URL(url).hostname;
            let pattern;

            if (hostname.includes("instagram.com")) {
                const pathMatch = url.match(/instagram\.com(\/(?:reel|p|stories)\/[^/?#]+)/);
                if (pathMatch) {
                    pattern = `*://*.instagram.com${pathMatch[1]}*`;
                    const tabs = await browser.tabs.query({ url: pattern });
                    if (tabs.length > 0) {
                        const tabId = tabs[0].id;
                        details._resolvedTabId = tabId;
                        cacheTabUrl(url, tabId);
                        return tabId;
                    }
                }
                pattern = "*://*.instagram.com/*";
            } else if (hostname.includes("twitter.com") || hostname.includes("x.com")) {
                pattern = "*://*.x.com/*";
            } else if (hostname.includes("vimeo.com")) {
                pattern = "*://*.vimeo.com/*";
            } else if (hostname.includes("kick.com")) {
                pattern = "*://*.kick.com/*";
            } else if (hostname.includes("twitch.tv")) {
                pattern = "*://*.twitch.tv/*";
            } else if (hostname.includes("dailymotion.com")) {
                pattern = "*://*.dailymotion.com/*";
            } else {
                pattern = `*://${hostname}/*`;
            }

            const tabs = await browser.tabs.query({ url: pattern });
            if (tabs.length > 0) {
                const active = tabs.find(t => t.active);
                const tabId = active ? active.id : tabs[0].id;
                details._resolvedTabId = tabId;
                cacheTabUrl(url, tabId);
                return tabId;
            }
        }

        const activeTabs = await browser.tabs.query({ active: true, currentWindow: true });
        if (activeTabs.length > 0) {
            details._resolvedTabId = activeTabs[0].id;
            return activeTabs[0].id;
        }
    } catch (e) {
        log("TABS", `Error resolving tabId`, e.message);
    }

    return -1;
}

async function ensureTabId(details) {
    if (details.tabId < 0) {
        details._resolvedTabId = await resolveTabId(details);
    }
    return details;
}

function stripHtml(s) {
    if (typeof s !== "string") return "";
    return s.replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
}

// The named character references that actually turn up in titles/descriptions.
// (The numeric forms — &#NNN; / &#xHHH; — are handled generically below, so we
// only need to spell out the named ones.)
const HTML_NAMED_ENTITIES = {
    amp: "&", lt: "<", gt: ">", quot: "\"", apos: "'", nbsp: " ",
    hellip: "…", mdash: "—", ndash: "–",
    lsquo: "‘", rsquo: "’", ldquo: "“", rdquo: "”",
    laquo: "«", raquo: "»", middot: "·", bull: "•",
    deg: "°", trade: "™", copy: "©", reg: "®", euro: "€",
};

// Decode HTML character references in scraped metadata text. The HTML parser
// leaves character references RAW inside <script type="application/ld+json">
// (script content is "raw text"), so a JSON-LD name/description can reach us as
// the literal "&#x41c;" (М) or "&amp;" and would otherwise render verbatim in
// the capture's title/description and the Downloads info dialog. Decodes the
// decimal (&#NNN;) and hex (&#xHHH;) numeric forms plus the named refs above;
// an unknown ref is left untouched. Safe to call on already-clean text (no '&'
// → early return) and idempotent for these refs (decoded output has none left).
function decodeHtmlEntities(s) {
    if (typeof s !== "string" || s.indexOf("&") === -1) return s;
    try {
        return s.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);/g, (match, body) => {
            if (body[0] === "#") {
                let code;
                if (body[1] === "x" || body[1] === "X") {
                    code = parseInt(body.slice(2), 16);
                } else {
                    code = parseInt(body.slice(1), 10);
                }
                if (!Number.isFinite(code) || code < 0 || code > 0x10FFFF) return match;
                try {
                    return String.fromCodePoint(code);
                } catch (e) {
                    return match;
                }
            }
            const named = HTML_NAMED_ENTITIES[body];
            return named !== undefined ? named : match;
        });
    } catch (e) {
        // Best-effort — fall back to the original text on any decode failure.
        return s;
    }
}

/**
 * Apple's amp-api artwork URLs come with {w}x{h}bb.{f} placeholders
 * (e.g. https://is1-ssl.mzstatic.com/.../{w}x{h}bb.{f}). Pick a 600x600
 * jpeg — matches the artworkUrl600 we'd otherwise pull from iTunes
 * Lookup and is what the native side wants for the thumbnail surface.
 */

// ============================================================================
// SPA navigation registry
// ============================================================================
// Replaces the old hardcoded checkAndProcessXxxUrl fan-out: each site module
// that wants tab-URL/SPA-navigation triggers registers a handler here. A
// handler is (url, tabId) => void, runs for EVERY tab URL change, and must be
// cheap + self-filtering (host test first) — exactly the contract the old
// checkAndProcess functions already followed.

const spaHandlers = [];

function registerSpaHandler(handler) {
    spaHandlers.push(handler);
}

function runSpaHandlers(url, tabId) {
    for (const handler of spaHandlers) {
        try { handler(url, tabId); } catch (e) { log("SPA", `handler threw`, e.message); }
    }
}

browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.url || changeInfo.status === "complete") {
        cacheTabUrl(tab.url, tabId);
    }
    // Process URLs on navigation start (loading) and completion
    const triggerUrl = changeInfo.url || (changeInfo.status === "complete" ? tab.url : null);
    if (triggerUrl) {
        runSpaHandlers(triggerUrl, tabId);
    }
});

// ============================================================================
// Message router — the ONE runtime.onMessage listener for all parser modules
// ============================================================================
// Replaces the old scattered per-module addListener calls. Keyed on
// message.kind (the bridge messages) falling back to message.type (the
// Instagram content-script fallback). Every parser/bridge message is
// fire-and-forget — no handler returns a response (see handlePageStateHls's
// "must not turn this listener into a responder" note in page-state.js), so
// the router NEVER returns the handler's value: returning a Promise from
// onMessage would make this extension the message's responder and race the
// catcher's own listener in requests.js. If a parser message ever needs a
// reply, give it a dedicated key and an explicit return here.

const messageHandlers = new Map();

function registerMessageHandler(key, handler) {
    if (messageHandlers.has(key)) {
        throw new Error(`duplicate message handler: ${key}`);
    }
    messageHandlers.set(key, handler);
}

browser.runtime.onMessage.addListener((message, sender) => {
    const key = message?.kind ?? message?.type;
    if (key === undefined) return;
    const handler = messageHandlers.get(key);
    if (!handler) return; // not ours — requests.js has its own listener
    try { handler(message, sender); } catch (e) { log("ROUTER", `handler for ${key} threw`, e.message); }
});

export {
    log, tryParseJson, stripHtml, decodeHtmlEntities,
    alreadySent, markSent,
    markOwnRequest, isOwnRequest,
    sendNative, sendVariants, sendSubtitles,
    parseHlsMaster, enumerateMasterNative, emitHlsMasterOrSingle,
    filterResponseText, readFilteredBody, readFilteredJson, collectFilteredResponse,
    cacheTabUrl, resolveTabId, ensureTabId, urlToTabCache,
    registerSpaHandler, runSpaHandlers, registerMessageHandler,
};
