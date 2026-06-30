// Videee (videee.com) parser — Supabase/PostgREST app-view JSON over the wire.
import { log, tryParseJson, filterResponseText, sendVariants } from './common.js';
import { getAmbientHeaders } from '../requests.js';

// ============================================================================
// Videee (videee.com) — a React/Vite SPA backed by Supabase (PostgREST)
// ============================================================================
//
// Every feed/profile/detail view is rendered from XHR JSON the app fetches from
// the Supabase REST endpoint (auth.videee.com/rest/v1). A "latest" feed is a
// /rest/v1/videos query whose rows carry the playable media DIRECTLY:
//
//   { "id": 235,
//     "title": "AInimals",
//     "thumbnail_url": "https://media.videee.com/thumbnails/<owner>/235.jpg",
//     "video_url": "https://media.videee.com/<owner>/<ts>-ainimals.mp4",
//     ... }
//
// `video_url` is a direct progressive .mp4 on Cloudflare-fronted media.videee.com
// (no query/signing — verified: the wire URL the player fetches is byte-identical
// to the JSON value), so we emit each row as a progressive variant via
// sendVariants (the mp4 is probed at capture for resolution/duration — the JSON
// has neither).
//
// WHY A PARSER (not the generic catcher). The rich per-clip metadata lives ONLY
// in the application/json body, which the generic catcher rejects (classifyXhr
// drops application/json) — and the page's og:title / og:image are the SITE
// headline + logo ("Videee — The creator-led home…" / lovable-uploads/OG.png),
// identical for every clip. So when the player fetched each .mp4 on the wire, the
// catcher captured it bare and enriched ALL of them with that one shared page
// card: same title + same thumbnail for every video. Reading the JSON gives each
// clip its own title + poster. (media.videee.com/<uuid>/*.mp4 is block-listed in
// parser-blocklist.js so the catcher no longer emits the bare duplicate.)
//
// TWO capture paths, the Bluesky pattern — because the JSON is often NOT on the
// wire. videee is an SPA backed by an in-memory query cache: opening a single
// video, navigating back to the feed, or revisiting a cached view fires NO
// /rest/v1/videos request, so the JSON reader has nothing to read. On its own
// that path would silently capture nothing for any cached/SPA view — and because
// the media host is block-listed, the catcher can't grab it either, so the video
// would be lost. The fix is a second listener that captures the .mp4 STRAIGHT off
// the wire the moment the player fetches it (always happens on view/play),
// read-only, enriched from videeeMetaCache (video_url → title/thumbnail, populated
// by every JSON response the reader DID see) and falling back to a generic
// "Videee video" when the JSON was never observed. Both emit a progressive
// variant deduped on the same video_url, so they collapse to one entity. Don't
// drop the wire listener "because the JSON reader covers it" — it doesn't, for the
// SPA-cache case, which is the common one once a session is warm.

// video_url -> { name, img }. Populated from every /rest/v1/videos response the
// reader parses; consulted by the wire-media listener when the same .mp4 hits the
// wire from a cached/SPA view whose JSON never crossed the wire.
const videeeMetaCache = new Map();
const VIDEEE_META_CACHE_MAX = 512;

function cacheVideeeMeta(videoUrl, meta) {
    if (!videoUrl) return;
    if (videeeMetaCache.has(videoUrl)) return;
    if (videeeMetaCache.size >= VIDEEE_META_CACHE_MAX) {
        videeeMetaCache.delete(videeeMetaCache.keys().next().value); // FIFO trim
    }
    videeeMetaCache.set(videoUrl, meta);
}

// The Supabase rich-feed query (the only /rest/v1/videos shape that carries the
// media): its select lists video_url. The other videos queries select owner_id
// only (no media), so gating on the literal "video_url" in the URL skips
// buffering them through filterResponseData for nothing. NOTE: the select list
// is URL-encoded (commas as %2C), so the column sits against the "C" of the
// preceding %2C — a \b word boundary before video_url would NOT match. Plain
// substring; the owner_id-only queries never contain it, so it can't false-match.
const VIDEEE_VIDEOS_RE = /\/rest\/v1\/videos\b.*video_url/;

// The played media: media.videee.com/<owner-uuid>/<ts>-<name>.mp4. NOT the
// thumbnails (media.videee.com/thumbnails/<owner>/<n>.jpg) — the 36-char UUID
// path segment can't match "thumbnails".
const VIDEEE_MEDIA_RE =
    /^https?:\/\/media\.videee\.com\/[0-9a-f-]{36}\/[^?#]+\.mp4(?:[?#]|$)/i;

function cleanStr(s) {
    return (typeof s === "string" ? s : "").replace(/\s+/g, " ").trim();
}

// Walk a parsed PostgREST response (an array of rows, or a single row object, or
// anything nesting them) for every object carrying a string `video_url`,
// attributing each its own title + thumbnail. Bounded (node budget + depth) and
// dedups video_url within the one response. Plain JSON.parse output — ordinary
// objects, not Xray page proxies — so normal iteration is safe.
function collectVideeeVideos(root) {
    const out = [];
    const seen = new Set();
    let nodes = 0;
    const MAX_NODES = 100000;
    const MAX_DEPTH = 12;

    function visit(o, depth) {
        if (nodes++ > MAX_NODES || depth > MAX_DEPTH) return;
        if (Array.isArray(o)) {
            for (let i = 0; i < o.length; i++) visit(o[i], depth + 1);
            return;
        }
        if (!o || typeof o !== "object") return;

        const url = o.video_url;
        if (typeof url === "string" && /^https?:\/\//i.test(url) && !seen.has(url)) {
            seen.add(url);
            const title = cleanStr(o.title);
            const thumb = (typeof o.thumbnail_url === "string" && /^https?:\/\//i.test(o.thumbnail_url))
                ? o.thumbnail_url : undefined;
            out.push({
                url,
                name: title || "Videee video",
                img: thumb,
            });
        }

        for (const k in o) {
            const v = o[k];
            if (v && typeof v === "object") visit(v, depth + 1);
        }
    }

    visit(root, 0);
    return out;
}

// The <video>-element media-request header set the Cloudflare-fronted media CDN
// expects. Verified from a HAR: the working in-browser fetch carried NO
// Referer/Origin (referrer-policy strips it; media.videee.com is same-site with
// videee.com) and authenticated via a cf_clearance Cookie — which the native side
// pulls from the browser jar (cookies.js getCookiesForUrl), not from here. What a
// bare ffmpeg/OkHttp GET lacks and Cloudflare can gate on is the media-request
// shape: Accept: video/*, Sec-Fetch-Dest: video, plus the real UA + q-valued
// Accept-Language. Use the ambient harvested values (the exact strings Gecko
// sends) when available, same as the page-state-hls path.
function videeeRequestHeaders() {
    const headers = [
        { name: "Accept", value: "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5" },
        { name: "Sec-Fetch-Dest", value: "video" },
        { name: "Sec-Fetch-Mode", value: "no-cors" },
        { name: "Sec-Fetch-Site", value: "same-site" },
    ];
    try {
        const ambient = getAmbientHeaders();
        if (ambient && typeof ambient === "object") {
            if (typeof ambient.acceptLanguage === "string" && ambient.acceptLanguage) {
                headers.push({ name: "Accept-Language", value: ambient.acceptLanguage });
            }
            if (typeof ambient.userAgent === "string" && ambient.userAgent) {
                headers.push({ name: "User-Agent", value: ambient.userAgent });
            }
        }
    } catch (_) { /* ambient not yet harvested — the set above is still benign */ }
    return headers;
}

// Emit one video as a progressive variant. Per-CLIP dedup keyed on the media URL
// (a feed holds many clips under one page origin, so origin-dedup would drop all
// but the first); cross-pass / wire-vs-JSON re-emit of the SAME clip collapses on
// this same key (JS sentOrigins) and on the repository's url-hash uid.
function emitVideeeVideo(details, v) {
    sendVariants(details, {
        variants: [{ url: v.url, width: 0, height: 0 }],
        origin: v.url,
        name: v.name,
        description: v.name,
        img: v.img,
        requestHeaders: videeeRequestHeaders(),
        dedupKey: v.url,
    });
}

// Passive read of the page's OWN /rest/v1/videos response (no refetch, byte-exact
// pass-through), same as the Twitter/Threads/Bluesky paths. Captures the whole
// feed pre-play with rich per-clip metadata, and seeds videeeMetaCache for the
// wire fallback.
function listenerVideeeApi(details) {
    if (!VIDEEE_VIDEOS_RE.test(details.url)) return {};
    const ok = filterResponseText(details, (body) => {
        if (!body) return;
        const json = tryParseJson(body);
        if (!json) { log("VIDEEE", "response not JSON", { url: details.url.slice(0, 90) }); return; }
        const videos = collectVideeeVideos(json);
        log("VIDEEE", `parsed response: ${videos.length} video(s)`, { url: details.url.slice(0, 90) });
        for (const v of videos) {
            cacheVideeeMeta(v.url, { name: v.name, img: v.img });
            emitVideeeVideo(details, v);
        }
    });
    if (!ok) log("VIDEEE", "filter unavailable", { url: details.url.slice(0, 90) });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerVideeeApi,
    { urls: ["*://*.videee.com/rest/v1/videos*"] },
    ["blocking"]
);

// Wire-media fallback: capture the .mp4 the player fetches whenever a video is
// actually viewed/played, so capture never depends on the /rest/v1/videos JSON
// being on the wire (it often isn't — see videeeMetaCache). Read-only (no filter,
// no blocking) — the page's own player gets its response untouched. Enriched from
// the cache when the JSON was seen earlier; falls back to a generic title
// otherwise. Deduped on the media URL, so it collapses with the pre-play JSON
// capture when both fire for the same clip.
function listenerVideeeMedia(details) {
    if (details.tabId < 0) return {};            // page player only, not our own probe
    if (!VIDEEE_MEDIA_RE.test(details.url)) return {};
    const url = details.url.split(/[?#]/)[0];
    const meta = videeeMetaCache.get(url);
    log("VIDEEE", "media on wire", { url: url.slice(0, 100), cached: !!meta });
    emitVideeeVideo(details, {
        url,
        name: meta ? meta.name : "Videee video",
        img: meta ? meta.img : undefined,
    });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerVideeeMedia,
    { urls: ["*://media.videee.com/*"], types: ["media", "xmlhttprequest", "object", "other"] },
    []
);
