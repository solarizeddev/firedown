// Facebook parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, isOwnRequest, sendVariants, registerSpaHandler, readFilteredBody } from './common.js';

// Facebook
// ----------------------------------------------------------------------------
// Filter the GraphQL response inline with filterResponseData, walk the JSON
// for video nodes, emit variants.
//
// Caveats worth knowing:
//   - Most videos are auth-walled. Logged-out users get gated responses that
//     either lack video fields or 302 to /login. The extraction still runs;
//     it just produces nothing, which is the right behaviour.
//   - GraphQL operation friendly_names rotate (CometSinglePostQuery,
//     VideoPlayerSubsequentResponsePayload, CometFocusedVideoContentQuery…).
//     Filtering by friendly_name would mean chasing renames; instead we
//     filter every /api/graphql/ response and let the video-node walker
//     decide. Cheap walk, harmless when there's no video.
//   - Responses are often newline-delimited JSON (streamed GraphQL).
//     Each line is a standalone JSON object — handle both NDJSON and
//     single-object shapes.
//   - Field names inside the video node change too. We check the union of
//     locations seen in the wild and treat absence as 'no video here'.
// ============================================================================

const FB_API_PATTERNS = [
    "*://www.facebook.com/api/graphql/*",
    "*://www.facebook.com/api/graphql"
];

const FB_VIDEO_URL_FIELDS = [
    "playable_url_quality_hd",
    "browser_native_hd_url",
    "playable_url",
    "browser_native_sd_url"
];

const FB_DASH_URL_FIELDS = [
    "playable_url_dash_hd",
    "playable_url_dash"
];

// Heuristic: does this object look like a Facebook video node? Any of the
// known URL fields being a non-empty string is enough — the field set has
// been stable across recent (~2 years) of GraphQL rotations even when other
// surrounding shape has changed.
function isFacebookVideoNode(obj) {
    if (!obj || typeof obj !== "object") return false;
    for (const f of FB_VIDEO_URL_FIELDS) {
        if (typeof obj[f] === "string" && obj[f].length > 0) return true;
    }
    for (const f of FB_DASH_URL_FIELDS) {
        if (typeof obj[f] === "string" && obj[f].length > 0) return true;
    }
    return false;
}

// Walk an arbitrary JSON tree collecting Facebook video nodes. Depth-capped
// and dedup'd by identity to keep cost bounded on News Feed payloads, which
// can be megabytes with deeply nested story_card / attachments / edges.
function collectFacebookVideos(root) {
    const out = [];
    const seen = new Set();
    const MAX_DEPTH = 12;
    const MAX_NODES = 5000;
    let visited = 0;

    function walk(node, depth) {
        if (visited++ > MAX_NODES) return;
        if (!node || typeof node !== "object" || depth > MAX_DEPTH) return;
        if (seen.has(node)) return;
        seen.add(node);

        if (isFacebookVideoNode(node)) {
            out.push(node);
            // Keep walking — a video node can itself contain related videos
            // (e.g. CometVideoHomeRootQuery returns a list under data.video.related)
        }

        if (Array.isArray(node)) {
            for (const child of node) walk(child, depth + 1);
        } else {
            for (const key in node) walk(node[key], depth + 1);
        }
    }

    walk(root, 0);
    return out;
}

function parseFacebookVideoUrl(url) {
    if (!url) return null;
    // /watch/?v=VIDEOID or /watch?v=VIDEOID
    let m = url.match(/facebook\.com\/watch\/?\?v=(\d+)/);
    if (m) return { type: "watch", videoId: m[1] };
    // /{user}/videos/{id}/
    m = url.match(/facebook\.com\/(?:[A-Za-z0-9.]+\/)?videos\/(?:[A-Za-z0-9._-]+\/)?(\d+)/);
    if (m) return { type: "video", videoId: m[1] };
    // /reel/{id}
    m = url.match(/facebook\.com\/reel\/(\d+)/);
    if (m) return { type: "reel", videoId: m[1] };
    // fb.watch/{code}
    m = url.match(/fb\.watch\/([A-Za-z0-9_-]+)/);
    if (m) return { type: "fbwatch", code: m[1] };
    return null;
}

function canonicalFacebookOrigin(video, fallbackUrl) {
    const videoId = video?.id || video?.video_id || video?.videoId;
    if (videoId) return `https://www.facebook.com/watch/?v=${videoId}`;
    return fallbackUrl || "https://www.facebook.com/";
}

function extractFacebookOwnerName(video) {
    return video?.owner?.name
        || video?.owner?.username
        || video?.creation_story?.actors?.[0]?.name
        || video?.savable_title
        || null;
}

function extractFacebookThumbnail(video) {
    return video?.preferred_thumbnail?.image?.uri
        || video?.thumbnailImage?.uri
        || video?.image?.uri
        || video?.thumbnail?.uri
        || null;
}

function extractFacebookDescription(video) {
    return video?.savable_description?.text
        || video?.message?.text
        || video?.title?.text
        || "";
}

function sendFacebookVideo(details, video, pageUrl) {
    const variants = [];

    // Progressive variants — preferred when both HD and SD are present.
    // Mark explicit heights so sendVariants' descending sort puts HD first.
    const hdUrl = video.playable_url_quality_hd || video.browser_native_hd_url;
    const sdUrl = video.playable_url || video.browser_native_sd_url;
    if (typeof hdUrl === "string" && hdUrl.length > 0) {
        variants.push({ url: hdUrl, width: 0, height: 1080 });
    }
    if (typeof sdUrl === "string" && sdUrl.length > 0 && sdUrl !== hdUrl) {
        variants.push({ url: sdUrl, width: 0, height: 480 });
    }

    // DASH manifest fallback. The Kotlin side's FFmpeg path handles DASH
    // manifests natively — we treat the manifest URL as a single variant.
    if (variants.length === 0) {
        const dashUrl = video.playable_url_dash_hd || video.playable_url_dash;
        if (typeof dashUrl === "string" && dashUrl.length > 0) {
            variants.push({ url: dashUrl, width: 0, height: 0 });
        }
    }

    if (variants.length === 0) return false;

    const origin = canonicalFacebookOrigin(video, pageUrl);
    sendVariants(details, {
        variants,
        origin,
        description: extractFacebookDescription(video),
        img: extractFacebookThumbnail(video),
        name: extractFacebookOwnerName(video),
        duration: Math.round((video.playable_duration_in_ms
            || (video.length_in_second ? video.length_in_second * 1000 : 0)
            || 0))
    });
    return true;
}

function processFacebookGraphqlBody(details, url, body) {
    // Facebook sometimes prefixes its responses with for(;;); to defeat
    // JSONP-style hijacking. Strip when present.
    let str = body;
    if (str.startsWith("for (;;);")) str = str.slice(9);

    // Streamed GraphQL: each line is an independent JSON object. We try the
    // whole body as a single parse first (covers single-shot responses);
    // if that fails, fall back to line-by-line.
    const candidates = [];
    const single = tryParseJson(str);
    if (single) {
        candidates.push(single);
    } else {
        for (const line of str.split(/\r?\n/)) {
            if (!line.trim()) continue;
            const obj = tryParseJson(line);
            if (obj) candidates.push(obj);
        }
    }

    if (candidates.length === 0) {
        log("FB-FILTER", `no JSON parsed from body`, { bytes: str.length, preview: str.slice(0, 80) });
        return;
    }

    let emitted = 0;
    for (const parsed of candidates) {
        const videos = collectFacebookVideos(parsed);
        for (const v of videos) {
            if (sendFacebookVideo(details, v, url)) emitted++;
        }
    }
    log("FB-FILTER", `processed ${candidates.length} chunk(s), emitted ${emitted} video(s)`, { url: url.slice(0, 80) });
}

function listenerFacebookApiFilter(details) {
    const url = details.url;
    if (isOwnRequest(url)) return {};

    readFilteredBody(details, "FB-FILTER", "graphql", (body) => {
        processFacebookGraphqlBody(details, url, body);
    });
    return {};
}

function checkAndProcessFacebookUrl(url, tabId) {
    if (!url) return;
    if (!url.includes("facebook.com") && !url.includes("fb.watch")) return;
    const parsed = parseFacebookVideoUrl(url);
    if (!parsed) return;
    // Facebook video extraction is response-driven (filter on GraphQL).
    // We only need the tabId cached so that when the GraphQL fires we
    // can resolve the originating tab — the actual emission happens
    // inside listenerFacebookApiFilter.
    log("FB-PAGE", `video page detected`, { parsed, url: url.slice(0, 80), tabId });
}

browser.webRequest.onBeforeRequest.addListener(
    listenerFacebookApiFilter,
    { urls: FB_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

// ============================================================================

// Tab-URL / SPA-navigation trigger (was the hardcoded call in tabs.onUpdated).
registerSpaHandler(checkAndProcessFacebookUrl);
