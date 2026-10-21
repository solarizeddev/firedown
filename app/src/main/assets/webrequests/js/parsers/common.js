// ============================================================================
// Shared parser infrastructure — split verbatim out of the former
// parser-background.js (the per-site parsers live in the sibling modules).
// Everything here is site-agnostic: logging, dedup, native messaging, the
// HLS-master enumeration helpers, response-filter capture, tab resolution,
// text utils, and the two registries (SPA navigation + message router) the
// site modules plug into.
// ============================================================================
import { DEBUG } from '../debug.js';
import { ClaimSet, MetaCache } from '../bounded.js';   // re-exported below
import { tabClaims, allTabStates, rememberTabUrl, tabIdForUrl, tabUrls, allTabUrls, __tabStateCount } from '../tab-state.js';

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

const SENT_ORIGIN_TTL = 30_000;
const SENT_ORIGINS_MAX = 1024;   // per tab

// Dedup is per (tabId, origin), NOT per origin alone. The same video opened in a
// SECOND tab must still capture — the repository dedups per tabId, so a global
// origin-only key wrongly suppressed the second tab for the whole 30s TTL (open
// tab A → captures; open tab B on the same URL within 30s → silently dropped;
// after the TTL it works again). Keying on the tab fixes that while still
// collapsing a single load's multiple emits (e.g. the progressive variant + the
// per-quality HLS masters a player exposes) and rapid same-tab refreshes. A
// missing/negative tabId (rare embed paths with no resolved tab) lands in the
// UNKNOWN pseudo-tab's set — the old bare-origin key, no regression.
//
// The sets live in each tab's TabState (tab-state.js), so a closed tab's marks
// go with it: a tab id reused for a fresh page within the TTL used to inherit
// the dead tab's 30 s suppression of the same origin.
function sentSet(tabId) {
    return tabClaims(tabId, "sent", SENT_ORIGIN_TTL, SENT_ORIGINS_MAX);
}

function* allSentSets() {
    for (const s of allTabStates()) {
        const c = s.claims.get("sent");
        if (c) yield c;
    }
}

// Mixed-attribution guard: two emits for the SAME video can resolve DIFFERENT
// tabIds when one path fails to attribute (resolveTabId → -1 → bare-origin
// key) while the other lands on the real tab (a parser's JSON reader and its
// wire-master listener emitting one master from different `details`).
// Tab-scoped keys alone would then miss each other. So a real-tab check ALSO
// treats a bare-origin mark as a hit, and an unattributed check treats ANY
// tab's mark as a hit. Genuine multi-tab captures are unaffected: two real
// tabs produce two tab-scoped keys, and a bare key is never written for an
// attributed emit.
function alreadySent(origin, tabId) {
    if (sentSet(tabId).has(origin)) return true;
    if (typeof tabId === "number" && tabId >= 0) {
        return sentSet(-1).has(origin);
    }
    for (const c of allSentSets()) {
        if (c.has(origin)) return true;
    }
    return false;
}

// A re-mark refreshes the TTL (it used to arm a second timer while the first
// still deleted the key at ITS deadline, so a second emit 20 s after the
// first was unmarked 10 s later).
function markSent(origin, tabId) {
    sentSet(tabId).add(origin);
}

// "Has ANYTHING been emitted for this page origin?" — the origin itself OR a
// per-clip `<origin>#<clip>` dedupKey under it (a carousel whose only videos
// are slides never marks the bare origin, only the slide keys). For a
// fallback fetcher deciding whether a page still needs a second capture path
// this is the right question; `alreadySent` is the emit-time exact-key check.
function alreadySentUnder(origin, tabId) {
    if (alreadySent(origin, tabId)) return true;
    const bare = origin + "#";
    const underIn = (c) => {
        for (const key of c.keys()) {
            if (key.startsWith(bare)) return true;
        }
        return false;
    };
    if (typeof tabId === "number" && tabId >= 0) {
        return underIn(sentSet(tabId)) || underIn(sentSet(-1));
    }
    for (const c of allSentSets()) {
        if (underIn(c)) return true;
    }
    return false;
}

// ============================================================================
// Own-request tracking — prevents intercepting our own fetches
// ============================================================================

const OWN_REQUEST_TTL = 10_000;
const ownRequests = new ClaimSet(OWN_REQUEST_TTL, 64);

function markOwnRequest(url) {
    ownRequests.add(url);
}

// Consumed on the first match: one mark answers one request.
function isOwnRequest(url) {
    for (const ownUrl of ownRequests.keys()) {
        if (url === ownUrl || url.startsWith(ownUrl)) {
            ownRequests.release(ownUrl);
            return true;
        }
    }
    return false;
}

// ============================================================================
// Cookie jar of a tab
// ============================================================================
// browser.cookies.getAll reads the DEFAULT jar unless told otherwise, so a
// parser that attached cookies to its emit for a capture made in a PRIVATE
// tab handed the REGULAR session's cookies to that download (and, where the
// download needs the private session's auth, the wrong ones). The store ids
// are Gecko's: "firefox-default" and "firefox-private" (ext-cookies.js).
// Returns undefined when the tab is unknown — the caller then omits storeId
// and keeps the old default-jar read.
async function cookieStoreIdForTab(tabId) {
    if (typeof tabId !== "number" || tabId < 0) return undefined;
    try {
        const tab = await browser.tabs.get(tabId);
        return tab && tab.incognito ? "firefox-private" : "firefox-default";
    } catch (_) {
        return undefined;
    }
}

// `base` is a cookies.getAll() filter ({ url } or { domain }); the tab's store
// is added when it is known.
async function cookieQueryForTab(tabId, base) {
    const storeId = await cookieStoreIdForTab(tabId);
    return storeId ? { ...base, storeId } : { ...base };
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
 * Unified variant sender for every parser.
 * Handles dedup, sorting, and message construction.
 */
async function sendVariants(details, { variants, origin, description, img, name, duration, requestHeaders, skipProbe, manifest, dedupKey, pageTitled }) {
    if (!Array.isArray(variants) || variants.length === 0) return;

    // If the parser already has what the capture-time ffmpeg probe would supply
    // — a known duration on progressive (single-URL) variants — skip the probe.
    // Its other outputs are redundant here: the container is mp4, the codecs are
    // unused downstream, and it doesn't reject bad URLs anyway. Variants with a
    // separate audioUrl (DASH track pairs) are EXCLUDED — they mux at download
    // and we don't trust split-track metadata blindly. Callers that must force
    // it (HLS renditions) still pass skipProbe = true.
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
    // not FFprobe (probing an AES-HLS variant can burn a single-use key).
    if (skipProbe) message.skipProbe = true;
    // manifest: these variant URLs are HLS/DASH playlists ffmpeg must mux (not
    // progressive files). Declared by the enumerator so Java needn't guess from
    // the URL extension — robust against obfuscated/tokenized manifests and
    // #fragment / ?query tails. (Separate video+audio pairs are detected from
    // audioUrl and don't need this.)
    if (manifest) message.manifest = true;
    // pageTitled: the name is only the page's title; a later copy of the same
    // file that names the clip renames it (BrowserDownloadRepository).
    if (pageTitled && name) message.pageTitled = true;

    sendNative(message);
}

// ---- Shared HLS master enumeration ----------------------------------------
// Parse a master playlist into quality variants WITHOUT decoding, so a parser
// can populate the quality picker with no capture-time ffprobe. Handles muxed
// renditions (one STREAM-INF = full A/V → single-URL variant) and split audio
// (EXT-X-MEDIA TYPE=AUDIO referenced via AUDIO="group"), resolves relative
// URLs, and skips I-frame trick-play streams (their tag is
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
        const avg = parseInt((line.match(/AVERAGE-BANDWIDTH=(\d+)/) || [])[1], 10) || 0;
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
            avg,
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
        // bitrate feeds the quality picker's size estimate (x duration):
        // AVERAGE-BANDWIDTH when stated — BANDWIDTH is the peak and overstates
        // a VBR rendition. Ranking above stays on BANDWIDTH.
        const v = { url: s.url, width: s.width, height: s.height, bitrate: s.avg || s.bw || 0 };
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

// ============================================================================
// Response-body readers (filterResponseData) — ALL bounded by one byte cap
// ============================================================================
// Every reader below writes each chunk straight back to the page (byte-exact,
// unconditionally) and keeps its OWN copy only while the body is under
// FILTER_BODY_MAX_BYTES; past it the body passes through unread and the
// reader reports it like any unreadable body. Some filter patterns are
// host-wide (a document filter on every main frame of a host, with no
// content gate), so a size check after buffering would hold a large body
// whole. The bodies these readers exist for (feed JSON, SSR documents,
// gateway responses) run from kilobytes to a few megabytes; 16 MB is a
// ceiling, not a budget. A parser with a host-wide XHR filter may keep its
// own smaller chunk-level cap (same shape).
const FILTER_BODY_MAX_BYTES = 16 * 1024 * 1024;

function boundedBody() {
    const chunks = [];
    let bytes = 0;
    let overflow = false;
    return {
        add(data) {
            bytes += data.byteLength;
            if (overflow) return;
            if (bytes > FILTER_BODY_MAX_BYTES) {
                overflow = true;
                chunks.length = 0;   // release what was kept; keep passing through
                return;
            }
            chunks.push(new Uint8Array(data));
        },
        get overflow() { return overflow; },
        get bytes() { return bytes; },
        text() {
            const buf = new Uint8Array(bytes);
            let off = 0;
            for (const c of chunks) { buf.set(c, off); off += c.byteLength; }
            chunks.length = 0;
            return new TextDecoder("utf-8").decode(buf);
        },
    };
}

// Passive response-body text capture (filterResponseData). Returns false if a
// filter couldn't be created. onText receives the body, or null on error or
// when the body is over FILTER_BODY_MAX_BYTES.
function filterResponseText(details, onText) {
    let filter;
    try { filter = browser.webRequest.filterResponseData(details.requestId); }
    catch (e) { return false; }
    const body = boundedBody();
    filter.ondata = (event) => { body.add(event.data); filter.write(event.data); };
    filter.onstop = () => {
        filter.close();
        if (body.overflow) {
            log("FILTER", `body over ${FILTER_BODY_MAX_BYTES} bytes, passed through unread`, { url: (details.url || "").slice(0, 100) });
            Promise.resolve().then(() => onText(null));
            return;
        }
        Promise.resolve().then(() => onText(body.text()));
    };
    filter.onerror = () => { try { filter.close(); } catch (_) {} onText(null); };
    return true;
}

// Passive write-through body capture for the per-site response filters.
// Contract differs from filterResponseText above: a create failure logs and
// returns false; an EMPTY body, or one over FILTER_BODY_MAX_BYTES, is skipped
// with a log (no callback); a filter error logs and closes (no callback).
// onBody runs OFF the filter callback (microtask) with (text, totalBytes) so
// it never holds the stream stop. Keep new parsers on this unless they need
// an error fallback (a re-fetch on filter failure) or chunk-level
// diagnostics.
function readFilteredBody(details, tag, label, onBody) {
    let filter;
    try {
        filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (e) {
        log(tag, `${label}: filter create failed`, { error: e.message });
        return false;
    }
    const body = boundedBody();
    filter.ondata = (event) => {
        body.add(event.data);
        filter.write(event.data); // pass through unmodified
    };
    filter.onstop = () => {
        filter.close();
        const total = body.bytes;
        if (total === 0) { log(tag, `${label}: 0 bytes`); return; }
        if (body.overflow) {
            log(tag, `${label}: ${total} bytes, over ${FILTER_BODY_MAX_BYTES} — passed through unread`);
            return;
        }
        const text = body.text();
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
// failure). Used for master-only / single-use-key sites, so capture needs no
// ffmpeg probe and never burns a single-use AES key.
//
// The page is the capture's identity (a fresh signed master per load must not
// be a new capture), which holds for a page with ONE stream. `clipId` names
// one clip of several on a page (the page-state bridge sets it) — dedup is
// then per (page, clip), here and in the native uid. `siblings` are the
// clip's progressive files folded beside the master: a single capture of one
// of them is this capture (the repository replaces it, or refuses it).
async function enumerateMasterNative(details, { url, origin, name, description, img, duration, requestHeaders, clipId, pageTitled, siblings }) {
    if (!url) return;

    const tabId = await resolveTabId(details);
    const key = (origin && clipId) ? `${origin}#${clipId}` : origin;
    if (key && alreadySent(key, tabId)) { log("HLS", "already sent", { key, tabId }); return; }
    if (key) markSent(key, tabId);
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
    if (clipId) message.clipId = clipId;
    if (pageTitled && name) message.pageTitled = true;
    if (Array.isArray(siblings)) {
        const files = siblings.filter((u) => typeof u === "string" && /^https?:\/\//i.test(u)).slice(0, 32);
        if (files.length) message.siblings = files;
    }

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

// Promise form of the same capture: resolves with the body text; rejects when
// no filter could be created, on a filter error, or when the body is over
// FILTER_BODY_MAX_BYTES (every caller's .catch logs it).
function collectFilteredResponse(details) {
    return new Promise((resolve, reject) => {
        let filter;
        try {
            filter = browser.webRequest.filterResponseData(details.requestId);
        } catch (e) {
            reject(new Error(`Failed to create filter: ${e.message}`));
            return;
        }

        const body = boundedBody();

        filter.ondata = (event) => {
            body.add(event.data);
            filter.write(event.data);
        };

        filter.onstop = () => {
            filter.close();
            if (body.overflow) {
                reject(new Error(`body over ${FILTER_BODY_MAX_BYTES} bytes, passed through unread`));
                return;
            }
            resolve(body.text());
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

// The tab-URL cache is each tab's TabState.urls (tab-state.js): dropped with
// the tab, TTL + cap there. These are the parser-facing names.
function cacheTabUrl(url, tabId) {
    rememberTabUrl(tabId, url);
}

async function resolveTabId(details) {
    if (details.tabId >= 0) return details.tabId;
    if (details._resolvedTabId >= 0) return details._resolvedTabId;

    const urlsToCheck = [details.originUrl, details.url, details.documentUrl].filter(Boolean);

    // Check the tabs' URL caches
    for (const url of urlsToCheck) {
        let tabId = tabIdForUrl(url);
        if (tabId < 0) {
            try {
                const u = new URL(url);
                tabId = tabIdForUrl(u.origin + u.pathname);
            } catch {}
        }
        if (tabId >= 0) {
            details._resolvedTabId = tabId;
            return tabId;
        }
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

// The tab a parser decision belongs to: the tab a -1 request was resolved to
// (ensureTabId), else the webRequest tab, else UNKNOWN_TAB (-1). A claim that
// gates a capture is keyed by THIS tab and the content, never by the content
// alone: a content-only claim gives a second tab on the same video nothing
// for as long as the first tab's claim lives. Per-tab claims live in the
// tab's TabState (tabClaims) and go with the tab.
function tabOfDetails(details) {
    if (details && typeof details._resolvedTabId === "number") return details._resolvedTabId;
    return details && typeof details.tabId === "number" ? details.tabId : -1;
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
// message.kind (the bridge messages) falling back to message.type (older
// type-keyed messages). Every parser/bridge message is
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

// ============================================================================
// Player placeholders — click-to-load embeds the page has not loaded yet
// ============================================================================
// A page often shows a video as a picture with a play button and loads the
// real player iframe only on a tap (a carousel card, an
// <a data-embed-url="…/player/…?video=<id>">). Until the tap no player
// exists, so neither the wire nor the bridge sees anything. The content
// script reports the player URLs such placeholders DECLARE
// (`player-placeholders`, any host, capped per document); a site module that
// owns the host registers a handler here and captures the clip from its own
// API. A handler is (url, details) => boolean: true = "mine, handled" (the
// URL is offered to no other handler), false = not this site's. Same shape as
// the SPA registry: cheap and self-filtering, host test first. The page is
// live in a tab, so this stays inside the capture-on-a-loaded-page model; it
// only moves the capture from the tap to the page load.
//
// A handler does not fetch on the spot: it hands its capture to
// queuePlaceholderCapture, which owns the rules every site needs, so a second
// site cannot get them wrong:
// - one claim per (tab, PAGE, clip), so a second article in the same tab has
//   its own budget and a clip on a page already queued is not queued again;
// - at most PLACEHOLDER_PAGE_MAX captures per (tab, page), all sites together
//   (the claims are counted through keys(), which skips expired ones);
// - at most PLACEHOLDER_WORKERS captures at once, never two of the same tab:
//   a site that stalls holds up its own tab's queue, not every tab's. The
//   queue is bounded (PLACEHOLDER_QUEUE_MAX);
// - a capture runs only while the tab is still on the page that reported it,
//   and the capture is handed `stillOnPage` to ask again RIGHT BEFORE it
//   emits: Java stamps a capture with the tab's page at ARRIVAL, so a clip
//   whose tab moved during its fetches would be filed under the next page.
//   "The same page" is the visit rule's: the URL without its fragment, or a
//   query refinement of it (same origin and path, one query a subset of the
//   other: a consent or campaign parameter added after load);
// - a capture that did not happen (the page moved, the tab closed, a fetch
//   failed, nothing to emit) RELEASES its claim, so a reload or a return to
//   the page tries again.

const placeholderHandlers = [];
const MAX_PLACEHOLDER_URLS = 64;   // per message; the content script's own caps are lower
const PLACEHOLDER_PAGE_MAX = 12;
const PLACEHOLDER_CLAIM_TTL_MS = 30 * 60_000;
const PLACEHOLDER_CLAIM_MAX = 256;   // per tab; a hard cap behind the per-page one
const PLACEHOLDER_WORKERS = 3;
const PLACEHOLDER_QUEUE_MAX = 96;
const placeholderQueue = [];               // { tab, run } in report order
const placeholderActiveTabs = new Set();   // tabs with a capture running; ≤ PLACEHOLDER_WORKERS
const placeholderIdleWaiters = [];         // test hook (placeholderQueueIdle)

function registerPlaceholderHandler(handler) {
    placeholderHandlers.push(handler);
}

function placeholderClaims(tabId) {
    return tabClaims(tabId, "placeholder", PLACEHOLDER_CLAIM_TTL_MS, PLACEHOLDER_CLAIM_MAX);
}

/** The page a placeholder report came from: the tab's URL then, without its fragment. */
function placeholderPageKey(details) {
    return String(details?.tabUrl || "").split("#")[0];
}

/** Same page for a queued capture: equal, or a query refinement (same origin + path). */
function samePlaceholderPage(now, then) {
    if (!now || !then) return false;
    if (now === then) return true;
    let a;
    let b;
    try {
        a = new URL(now);
        b = new URL(then);
    } catch (_) {
        return false;
    }
    if (a.origin !== b.origin || a.pathname.replace(/\/$/, "") !== b.pathname.replace(/\/$/, "")) return false;
    const subset = (small, large) => {
        for (const [key, value] of small) {
            if (!large.getAll(key).includes(value)) return false;
        }
        return true;
    };
    return subset(a.searchParams, b.searchParams) || subset(b.searchParams, a.searchParams);
}

/** Is the tab still on the page that reported the placeholder? */
async function placeholderPageOpen(details) {
    try {
        const tab = await browser.tabs.get(tabOfDetails(details));
        return samePlaceholderPage(String(tab?.url || "").split("#")[0], placeholderPageKey(details));
    } catch (_) {
        return false;   // tab closed
    }
}

function pumpPlaceholderQueue() {
    while (placeholderActiveTabs.size < PLACEHOLDER_WORKERS) {
        const next = placeholderQueue.findIndex((job) => !placeholderActiveTabs.has(job.tab));
        if (next < 0) break;
        const [job] = placeholderQueue.splice(next, 1);
        placeholderActiveTabs.add(job.tab);
        job.run().finally(() => {
            placeholderActiveTabs.delete(job.tab);
            pumpPlaceholderQueue();
        });
    }
    if (placeholderQueue.length === 0 && placeholderActiveTabs.size === 0) {
        while (placeholderIdleWaiters.length) placeholderIdleWaiters.shift()();
    }
}

/**
 * Queue one placeholder capture. `clipKey` names the clip across sites
 * ("<site>:<id>"); `capture(stillOnPage)` is async and resolves true
 * once the clip was captured (or another path in this tab already owns it),
 * false when a later report should try again; it awaits `stillOnPage()` right
 * before it emits and does not emit when that is false. Returns whether it
 * was queued.
 */
function queuePlaceholderCapture(details, clipKey, capture) {
    const tab = tabOfDetails(details);
    const page = placeholderPageKey(details);
    if (tab < 0 || !page) return false;
    const claims = placeholderClaims(tab);
    const claimKey = `${page}\n${clipKey}`;
    let onPage = 0;
    for (const key of claims.keys()) {
        if (key === claimKey) return false;
        if (key.startsWith(page + "\n")) onPage++;
    }
    if (onPage >= PLACEHOLDER_PAGE_MAX) {
        log("PLACEHOLDER", `page budget reached`, { tab, clipKey });
        return false;
    }
    if (placeholderQueue.length >= PLACEHOLDER_QUEUE_MAX) {
        log("PLACEHOLDER", `queue full`, { tab, clipKey });
        return false;
    }
    claims.add(claimKey);
    const stillOnPage = () => placeholderPageOpen(details);
    placeholderQueue.push({ tab, run: async () => {
        let done = false;
        try {
            if (await stillOnPage()) {
                done = (await capture(stillOnPage)) === true;
            } else {
                log("PLACEHOLDER", `page gone, skipped`, { tab, clipKey });
            }
        } catch (e) {
            log("PLACEHOLDER", `capture threw`, { clipKey, error: e?.message });
        }
        if (!done) claims.release(claimKey);
    } });
    pumpPlaceholderQueue();
    return true;
}

/** The origin a player embedded at this placeholder would name as its embedder. */
function placeholderEmbedderOrigin(details) {
    for (const candidate of [details?.url, details?.tabUrl]) {
        try {
            const u = new URL(candidate);
            if (u.protocol === "https:" || u.protocol === "http:") return u.origin;
        } catch (_) { /* about:srcdoc, about:blank, empty */ }
    }
    return null;
}

/** Resolves once no placeholder capture is queued or running (for tests). */
function placeholderQueueIdle() {
    if (placeholderQueue.length === 0 && placeholderActiveTabs.size === 0) return Promise.resolve();
    return new Promise((resolve) => placeholderIdleWaiters.push(resolve));
}

registerMessageHandler("player-placeholders", (message, sender) => {
    const tabId = sender?.tab?.id;
    if (typeof tabId !== "number" || tabId < 0) return;
    const urls = Array.isArray(message.urls) ? message.urls.slice(0, MAX_PLACEHOLDER_URLS) : [];
    const details = {
        tabId, _resolvedTabId: tabId,
        url: typeof sender.url === "string" ? sender.url : (sender.tab.url || ""),
        tabUrl: sender.tab.url || "",
        frameId: typeof sender.frameId === "number" ? sender.frameId : 0,
        requestId: `placeholder-${tabId}-${Date.now()}`,
    };
    for (const url of urls) {
        if (typeof url !== "string" || !/^https?:\/\//i.test(url)) continue;
        for (const handler of placeholderHandlers) {
            try {
                if (handler(url, details)) break;
            } catch (e) {
                log("ROUTER", `placeholder handler threw`, e.message);
            }
        }
    }
});

export {
    ClaimSet, MetaCache,
    log, tryParseJson, stripHtml, decodeHtmlEntities,
    alreadySent, markSent, alreadySentUnder,
    markOwnRequest, isOwnRequest,
    cookieStoreIdForTab, cookieQueryForTab,
    sendNative, sendVariants, sendSubtitles,
    parseHlsMaster, enumerateMasterNative, emitHlsMasterOrSingle,
    filterResponseText, readFilteredBody, readFilteredJson, collectFilteredResponse,
    FILTER_BODY_MAX_BYTES,
    cacheTabUrl, resolveTabId, ensureTabId, tabOfDetails, tabUrls, allTabUrls, tabIdForUrl, tabClaims, __tabStateCount,
    registerSpaHandler, runSpaHandlers, registerMessageHandler,
    registerPlaceholderHandler, queuePlaceholderCapture, placeholderEmbedderOrigin, placeholderQueueIdle,
};
