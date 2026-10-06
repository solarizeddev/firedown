// Niconico (nicovideo.jp) parser — split verbatim out of the former parser-background.js.
import { log, isOwnRequest, enumerateMasterNative, readFilteredJson, resolveTabId, cookieQueryForTab } from './common.js';

// Niconico (nicovideo.jp)
// ----------------------------------------------------------------------------
// Two passive response filters, no request replay / signing on our side:
//   1. www.nicovideo.jp/api/watch/v3_guest/<id> (or /v3/) — the watch metadata.
//      Cache title / duration / thumbnail keyed by video id.
//   2. nvapi.nicovideo.jp/v1/watch/<id>/access-rights/hls (POST, 201) — the
//      player mints the playable stream here; the response carries
//      data.contentUrl: a CloudFront-signed AES-128 HLS master on
//      delivery.domand. It is emitted as `type:"hls-master"`
//      (enumerateMasterNative): Java OkHttp-fetches the master text with the
//      Origin/Referer/Cookie domand validates and enumerates the renditions —
//      no ffmpeg probe, so the single-use AES key is never burned at capture
//      (see "Niconico domand AES key" in CLAUDE.md).
// parser-blocklist.js (`niconico`) keeps the generic catcher off the domand
// playlists and CMAF media.
//
// (A third, passive capture of the PLAYER's own master fetch used to sit here,
// keyed on a pending-master map that nothing ever filled — removed as dead
// code once the access-rights emit moved to Java enumeration.)
// ============================================================================

const NICO_META_TTL = 5 * 60 * 1000;
const NICO_META_MAX = 50;
const nicoMeta = new Map(); // videoId -> { title, durationMs, img, ts }

// Bounded two ways: expired entries are swept on insert, and the map is a FIFO
// at NICO_META_MAX. The sweep alone removed only EXPIRED entries, so a burst of
// more than NICO_META_MAX fresh watch-api responses grew past the cap until
// they aged out.
function nicoCacheMeta(id, meta) {
    nicoMeta.delete(id); // re-insert at the tail so FIFO order tracks recency
    nicoMeta.set(id, { ...meta, ts: Date.now() });
    if (nicoMeta.size > NICO_META_MAX) {
        const now = Date.now();
        for (const [k, v] of nicoMeta) { if (now - v.ts > NICO_META_TTL) nicoMeta.delete(k); }
        while (nicoMeta.size > NICO_META_MAX) nicoMeta.delete(nicoMeta.keys().next().value);
    }
}
function nicoGetMeta(id) {
    const m = id && nicoMeta.get(id);
    if (!m) return null;
    if (Date.now() - m.ts > NICO_META_TTL) { nicoMeta.delete(id); return null; }
    return m;
}

function nicoFilterJson(details, label, onParsed) {
    readFilteredJson(details, "NICO", label, (parsed, total) => {
        log("NICO", `${label}: parsed ${total} bytes`);
        onParsed(parsed);
    });
}

function nicoIdFromWatchApi(url) {
    const m = url.match(/\/api\/watch\/v3(?:_guest)?\/([A-Za-z0-9]+)/);
    return m ? m[1] : null;
}
function nicoIdFromAccess(url) {
    const m = url.match(/\/v1\/watch\/([A-Za-z0-9]+)\/access-rights/);
    return m ? m[1] : null;
}

function listenerNicoWatchApi(details) {
    if (isOwnRequest(details.url)) return {};
    const id = nicoIdFromWatchApi(details.url);
    log("NICO", "watch-api hit", { url: details.url.slice(0, 120), id, type: details.type });
    if (!id) return {};
    nicoFilterJson(details, "watch-api", (parsed) => {
        const v = parsed?.data?.video || {};
        const thumb = v.thumbnail || {};
        nicoCacheMeta(id, {
            title: v.title || null,
            durationMs: typeof v.duration === "number" ? v.duration * 1000 : 0,
            img: thumb.largeUrl || thumb.url || thumb.middleUrl || undefined
        });
        log("NICO", "cached metadata", { id, title: v.title });
    });
    return {};
}

// Hand the niconico signed master to native for OkHttp fetch + Java enumeration.
// Builds the headers domand validates (Origin/Referer + the domand cookie).
async function emitNicoMaster(details, id, contentUrl) {
    const origin = details.documentUrl || details.originUrl
        || (id ? `https://www.nicovideo.jp/watch/${id}` : details.url);
    let pageOrigin = "https://www.nicovideo.jp";
    try { pageOrigin = new URL(origin).origin; } catch (e) {}
    const requestHeaders = [
        { name: "Origin", value: pageOrigin },
        { name: "Referer", value: pageOrigin + "/" }
    ];
    try {
        // The capturing tab's jar (a private tab's domand cookie, not the
        // regular session's).
        const tabId = await resolveTabId(details);
        const cookies = await browser.cookies.getAll(await cookieQueryForTab(tabId, { url: contentUrl }));
        if (cookies && cookies.length) {
            requestHeaders.push({ name: "Cookie", value: cookies.map(c => `${c.name}=${c.value}`).join("; ") });
        }
    } catch (e) { log("NICO", "cookie read failed", { error: e.message }); }
    const meta = nicoGetMeta(id) || {};
    enumerateMasterNative(details, {
        url: contentUrl,
        origin,
        name: meta.title,
        description: meta.title,
        img: meta.img,
        duration: meta.durationMs,
        requestHeaders
    });
}

function listenerNicoAccessHls(details) {
    if (isOwnRequest(details.url)) return {};
    const id = nicoIdFromAccess(details.url);
    log("NICO", "access-hls hit", { url: details.url.slice(0, 120), id, method: details.method, type: details.type });
    nicoFilterJson(details, "access-hls", (parsed) => {
        const contentUrl = parsed?.data?.contentUrl;
        // The endpoint is hit twice: the &__retry=0 POST returns a 238-byte
        // "accept" envelope whose contentUrl is a bare query string
        // ("?accepted=true&data=…") — NOT a playable URL — while the real POST
        // (201) returns the absolute https://delivery.domand…m3u8 master.
        // Require an absolute http(s) URL so we ignore the accept envelope and
        // don't mark-sent on it (which would dedup-block the real one).
        if (!contentUrl || !/^https?:\/\//i.test(contentUrl)) {
            log("NICO", "access-hls: no usable contentUrl", {
                status: parsed?.meta?.status,
                contentUrl: contentUrl ? String(contentUrl).slice(0, 40) : null
            });
            return;
        }
        // Hand the signed master to native: Java OkHttp-fetches it (with the
        // Origin/Referer/Cookie domand validates) and M3U8Parser enumerates the
        // per-quality variants — no ffmpeg probe, so the single-use key is never
        // burned at capture. Java falls back to a media capture if it can't parse.
        emitNicoMaster(details, id, contentUrl);
    });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerNicoWatchApi,
    { urls: ["*://www.nicovideo.jp/api/watch/v3_guest/*", "*://www.nicovideo.jp/api/watch/v3/*"], types: ["xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerNicoAccessHls,
    { urls: ["*://nvapi.nicovideo.jp/v1/watch/*/access-rights/hls*"], types: ["xmlhttprequest"] },
    ["blocking"]
);

// ============================================================================
