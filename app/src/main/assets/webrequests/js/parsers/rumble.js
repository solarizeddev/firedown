// Rumble parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, isOwnRequest, markOwnRequest, sendVariants, enumerateMasterNative, cacheTabUrl, readFilteredBody, registerPlaceholderHandler, queuePlaceholderCapture } from './common.js';

// Rumble
// ----------------------------------------------------------------------------
// A Rumble watch page loads its player data from an embedJS endpoint:
//   rumble.com/embedJS/u3/?request=video&ver=2&v=<id>…  → JSON
// That JSON carries everything we need: title, author.name, duration (seconds),
// thumbnail (`i`), the watch permalink (`l`), and — crucially — a clean HLS
// master playlist at `ua.hls.auto.url` (== `u.hls.url`),
// e.g. https://rumble.com/hls-vod/<id>/playlist.m3u8. We emit that master as a
// single `media` URL; VariantProcessor/ffmpeg expands it
// into selectable qualities. The per-quality `ua.tar` entries are Rumble's
// video-only tarred HLS chunklists (separate `ua.audio`) accessed by byte
// range — not muxed, awkward to download standalone — so we don't use them.
//
// The matching `rumble.com/hls-vod/.*\.m3u8` block in parser-blocklist.js
// keeps the generic catcher from also grabbing that master (no duplicate).
// ============================================================================

const RUMBLE_EMBED_PATTERNS = [
    "*://rumble.com/embedJS/*",
    "*://*.rumble.com/embedJS/*"
];

// Shared emit: hand Rumble's HLS master to the Java M3U8 parser, which
// enumerates resolution-labelled qualities (text-only, no ffmpeg probe).
// enumerateMasterNative does its own origin
// dedup, so don't pre-mark here. Referer is set so the native OkHttp fetch of
// the master isn't rejected; on any fetch failure processHlsMaster falls back
// to the ffmpeg probe of the master (the previous behaviour).
async function emitRumbleHls(details, { hls, origin, title, author, thumb, durationSec }) {
    if (!hls || !origin) return;
    log("RUMBLE", "emitting HLS master", { origin, title, hls: hls.slice(0, 80) });
    enumerateMasterNative(details, {
        url: hls,
        origin,
        name: author,
        description: title,
        img: thumb,
        duration: durationSec > 0 ? Math.round(durationSec * 1000) : 0,
        requestHeaders: [{ name: "Referer", value: "https://rumble.com/" }]
    });
}

// Buffer a Rumble response body and hand the decoded text to onText. Shared by
// the JSON listeners (embedJS, service.php) and the shorts-page HTML reader.
function filterRumbleText(details, label, onText) {
    readFilteredBody(details, "RUMBLE", label, onText);
}

// Parse a Rumble JSON response and hand it to onParsed.
function filterRumbleJson(details, label, onParsed) {
    filterRumbleText(details, label, (text) => {
        const parsed = tryParseJson(text);
        if (!parsed) { log("RUMBLE", `${label} not JSON`, { bytes: text.length }); return; }
        onParsed(parsed);
    });
}

// Rumble watch embedJS carries progressive MP4 renditions (keyed by height)
// alongside the HLS auto master. The MP4 set is the FALLBACK now (preferred path
// is the HLS master via M3U8Parser): the MP4 group often omits height, giving
// unlabelled variants, so we only use it when there's no HLS master.
function collectRumbleMp4(group) {
    const out = [];
    const mp4 = group && group.mp4;
    if (!mp4) return out;
    const entries = Array.isArray(mp4)
        ? mp4.map((e) => [null, e])
        : (typeof mp4 === "object" ? Object.entries(mp4) : []);
    for (const [key, e] of entries) {
        const url = typeof e === "string" ? e : (e && e.url);
        if (typeof url !== "string" || !/^https?:\/\//.test(url)) continue;
        const height = parseInt(key, 10) || e?.meta?.h || e?.h || e?.res || e?.resolution || e?.height || 0;
        let width = e?.meta?.w || e?.w || e?.width || 0;
        if (!width && height) width = Math.round(height * 16 / 9); // 16:9 estimate, label only
        out.push({ url, width, height, videoCodec: "h264" });
    }
    return out;
}

// Watch page: embedJS request=video carries one focal video.
function emitRumbleVideo(details, parsed) {
    const origin = parsed.l ? `https://rumble.com${parsed.l}`
        : (details.documentUrl || details.originUrl || details.url);
    const thumb = parsed.i || (Array.isArray(parsed.t) && parsed.t[0]?.i) || undefined;

    // Prefer the HLS master: the Java M3U8 parser enumerates resolution-labelled
    // qualities, whereas Rumble's progressive MP4 set frequently omits height →
    // unlabelled ("empty") variant rows.
    const hls = parsed?.ua?.hls?.auto?.url || parsed?.u?.hls?.url || parsed?.ua?.hls?.url;
    if (hls) {
        emitRumbleHls(details, {
            hls, origin, title: parsed.title, author: parsed.author?.name,
            thumb, durationSec: parsed.duration
        });
        return;
    }

    // Fallback: no HLS master — use the structured MP4 qualities (no ffprobe).
    // Dedup by URL.
    const seen = new Set();
    const variants = [];
    for (const v of [...collectRumbleMp4(parsed.ua), ...collectRumbleMp4(parsed.u)]) {
        if (seen.has(v.url)) continue;
        seen.add(v.url);
        variants.push(v);
    }
    if (variants.length > 0) {
        log("RUMBLE", `emitting ${variants.length} mp4 variant(s) (no HLS)`, { origin, title: parsed.title });
        sendVariants(details, {
            variants,
            origin,
            description: parsed.title,
            name: parsed.author?.name,
            img: thumb,
            duration: parsed.duration ? parsed.duration * 1000 : 0,
            skipProbe: true
        });
        return;
    }
    log("RUMBLE", "embedJS had no HLS master or MP4 set");
}

function listenerRumbleEmbed(details) {
    if (isOwnRequest(details.url)) return {};
    if (!details.url.includes("request=video")) return {};
    log("RUMBLE", "embedJS request intercepted", { url: details.url.slice(0, 100), tabId: details.tabId });
    filterRumbleJson(details, "embedJS", (parsed) => emitRumbleVideo(details, parsed));
    return {};
}

// Shorts feed: rumble.com/service.php?name=shorts.feed&offset=&limit=… returns
// a paginated list of shorts (video.full shape) as you scroll. Each item has
// its OWN title / by.name / thumb / duration and an HLS playlist under
// `videos[]`, so emitting per item gives correct per-short metadata — the
// generic catcher would otherwise tag every short with the stale SPA page
// title ("all shorts share one title"). We iterate the top-level feed list
// only and do NOT recurse into an item's related_video sub-list (the request
// asks for options=video.full,video.related_video).
// Emit one shorts.feed item. Prefer an HLS playlist (M3U8Parser, no ffprobe);
// shorts usually ship only direct MP4 variants in `videos[]` (type "mp4" with a
// `res`/`resolution` height), so those are the fallback — sent skipProbe with a
// 16:9 width estimate so the quality rows are labelled without a probe.
function emitRumbleFeedItem(details, item) {
    if (!item || !item.title) return false;
    const origin = item.url
        || (item.relative_url ? `https://rumble.com${item.relative_url}` : null);
    if (!origin) return false;

    const vids = Array.isArray(item.videos) ? item.videos : [];
    const mp4s = [];
    let hls = null;
    for (const v of vids) {
        if (!v || typeof v.url !== "string") continue;
        if (v.type === "hls" || /\.m3u8(?:[?#]|$)/.test(v.url)) {
            hls = hls || v.url;
        } else if (v.type === "mp4" || /\.mp4(?:[?#]|$)/.test(v.url)) {
            const height = v.res || v.resolution || 0;
            // width is label-only; estimate 16:9 when the feed omits it so the
            // quality row isn't blank (JsonHelper needs both w & h for a label).
            mp4s.push({
                url: v.url,
                height,
                width: height ? Math.round(height * 16 / 9) : 0,
                videoCodec: "h264"
            });
        }
    }

    // Prefer the HLS master (M3U8Parser → resolution-labelled, no ffprobe),
    // matching the watch page. Only fall back to the direct MP4 set when the
    // item ships no HLS playlist (common for shorts).
    if (hls) {
        emitRumbleHls(details, {
            hls, origin, title: item.title, author: item.by?.name,
            thumb: item.thumb, durationSec: item.duration
        });
        return true;
    }
    if (mp4s.length > 0) {
        // skipProbe: don't metadatareader-probe every feed item (it opened each
        // rumble.cloud mp4 just to read a resolution we already have from `res`).
        sendVariants(details, {
            variants: mp4s,
            origin,
            description: item.title,
            img: item.thumb,
            name: item.by?.name,
            duration: (item.duration || 0) * 1000,
            skipProbe: true
        });
        return true;
    }
    return false;
}

// Locate the feed list across the shapes we've seen:
//   service.php?name=shorts.feed → data.items[]
//   shorts page inline <rum-shorts> blob → items[] (top level)
//   watch-page video.full feed → data.videos[]
function rumbleFeedList(parsed) {
    if (Array.isArray(parsed?.data?.items)) return parsed.data.items;
    if (Array.isArray(parsed?.items)) return parsed.items;
    if (Array.isArray(parsed?.data?.videos)) return parsed.data.videos;
    return [];
}

function emitRumbleShortsFeed(details, parsed, label) {
    const list = rumbleFeedList(parsed);
    if (list.length === 0) return;
    let emitted = 0;
    for (const item of list) {
        if (emitRumbleFeedItem(details, item)) emitted++;
    }
    log("RUMBLE", `${label}: ${emitted}/${list.length} item(s) emitted`);
}

function listenerRumbleService(details) {
    if (isOwnRequest(details.url)) return {};
    // Only the shorts feed. The watch-page autoplay/related list uses the same
    // service.php endpoint; scoping to name=shorts.feed keeps us from emitting
    // a page's whole up-next lineup.
    if (!details.url.includes("name=shorts.feed")) return {};
    log("RUMBLE", "shorts.feed intercepted", { url: details.url.slice(0, 120), tabId: details.tabId });
    filterRumbleJson(details, "shorts.feed", (parsed) => emitRumbleShortsFeed(details, parsed, "shorts.feed"));
    return {};
}

// First short on a /shorts/ landing isn't fetched via service.php (the feed
// starts at offset>0) — it's embedded in the page HTML inside a <rum-shorts>
// element's <script type="application/json"> island ({items:[…]}, same item
// shape). Read it from the main_frame response (network-level).
const RUMBLE_JSON_SCRIPT_RE = /<script\b[^>]*type="application\/json"[^>]*>([\s\S]*?)<\/script>/g;

function listenerRumbleShortsPage(details) {
    if (details.type !== "main_frame") return;
    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);
    log("RUMBLE", "shorts page intercepted", { url: details.url.slice(0, 100), tabId: details.tabId });
    filterRumbleText(details, "shorts page", (html) => {
        let m;
        RUMBLE_JSON_SCRIPT_RE.lastIndex = 0;
        while ((m = RUMBLE_JSON_SCRIPT_RE.exec(html)) !== null) {
            const parsed = tryParseJson(m[1]);
            if (parsed && rumbleFeedList(parsed).length > 0) {
                emitRumbleShortsFeed(details, parsed, "shorts page");
            }
        }
    });
}

browser.webRequest.onBeforeRequest.addListener(
    listenerRumbleEmbed,
    { urls: RUMBLE_EMBED_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerRumbleService,
    { urls: ["*://rumble.com/service.php*"], types: ["xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerRumbleShortsPage,
    { urls: ["*://rumble.com/shorts/*"], types: ["main_frame"] },
    ["blocking"]
);

// ============================================================================

// CLICK-TO-LOAD placeholders (common.js `player-placeholders`): a Rumble
// embed the page has not loaded yet — rumble.com/embed/<id>/ (an optional
// `<prefix>.` before the id, yt-dlp's shape). The embedJS JSON the player
// would fetch, fetched anonymously and bounded, then the same
// emitRumbleVideo; the player's own embedJS after a tap dedups on the watch
// page origin (enumerateMasterNative / the native HLS_MASTER uid).
const RUMBLE_EMBED_ID_RE = /^https?:\/\/(?:www\.)?rumble\.com\/embed\/(?:[0-9a-z]+\.)?([0-9a-z]{4,16})\/?(?:[?#]|$)/i;
const RUMBLE_FETCH_TIMEOUT_MS = 10_000;

function handleRumblePlaceholder(url, details) {
    const m = RUMBLE_EMBED_ID_RE.exec(url);
    if (!m) return false;
    const id = m[1];
    queuePlaceholderCapture(details, `rumble:${id.toLowerCase()}`, async (stillOnPage) => {
        const apiUrl = `https://rumble.com/embedJS/u3/?request=video&ver=2&v=${encodeURIComponent(id)}`;
        let parsed = null;
        try {
            markOwnRequest(apiUrl);
            const resp = await fetch(apiUrl, {
                credentials: "omit",
                signal: AbortSignal.timeout(RUMBLE_FETCH_TIMEOUT_MS)
            });
            if (!resp.ok) {
                log("RUMBLE", "placeholder embedJS failed", { id, status: resp.status });
                return false;
            }
            parsed = tryParseJson(await resp.text());
        } catch (e) {
            log("RUMBLE", "placeholder embedJS error", { id, error: e.message });
            return false;
        }
        if (!parsed || typeof parsed !== "object" || !(parsed.ua || parsed.u)) return false;
        if (!(await stillOnPage())) return false;
        log("RUMBLE", "placeholder capture", { id });
        emitRumbleVideo(details, parsed);
        return true;
    });
    return true;
}

registerPlaceholderHandler(handleRumblePlaceholder);
