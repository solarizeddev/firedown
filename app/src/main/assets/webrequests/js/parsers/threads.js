// Threads parser — split verbatim out of the former parser-background.js.
// Same backend as Instagram; emits through sendInstagramItem (see the section
// comment below for why the parser must own Threads captures).
import { log, tryParseJson, isOwnRequest, cacheTabUrl, readFilteredBody } from './common.js';
import { sendInstagramItem, collectInstagramMediaItems } from './instagram.js';

// Threads
// ----------------------------------------------------------------------------
// Same backend as Instagram, same item shape (video_versions, image_versions2,
// carousel_media, user.username, code, media_type, caption). We always want
// the video to come from HERE (the parser), not the generic webrequest
// catcher — the catcher would emit a bare .mp4 with no title/author/thumbnail,
// and 'instagram.*\.mp4' is block-listed in webrequests/regex.js precisely so
// the two don't both fire. So the parser is the single source of Threads
// videos, and it must carry the metadata.
//
// The post JSON shows up in one of two places depending on session state:
//
//   1. Logged-in: Threads server-renders the post data inline in the page
//      HTML, inside <script data-sjs> Relay-prefetch blobs. We read it with
//      filterResponseData on the main_frame — the raw network response, immune
//      to the page bootstrap (ServerJSPayloadListener) that consumes those
//      scripts out of the DOM the instant they parse (which is why reading the
//      DOM from a content script, even at document_start, loses the race for
//      the ~200 KB media blob; and why a content-script fetch() can't help —
//      it can't set Sec-Fetch-Dest: document, so the server returns an emptied
//      shell).
//
//   2. Logged-out (the in-app browser's usual state): the document comes back
//      media-less and Threads fetches the post via a GraphQL/API XHR after
//      load. We filter those responses the same way the Instagram and Facebook
//      paths do and run the same media-item walk.
//
// Both paths funnel through emitThreadsItems → sendInstagramItem with a
// threads.com origin override (so the UI groups under the post URL and dedup
// collapses any logged-in overlap between the doc and a follow-up XHR).
// ============================================================================

const THREADS_PAGE_PATTERNS = [
    "*://www.threads.com/@*/post/*",
    "*://www.threads.net/@*/post/*"
];

// Threads (Barcelona) shares Instagram's GraphQL/REST surface. Match the
// GraphQL endpoints plus the v1 REST media/feed routes; the media-item walk
// ignores anything without a video, so over-matching is cheap.
const THREADS_API_PATTERNS = [
    "*://www.threads.com/api/graphql*",
    "*://www.threads.net/api/graphql*",
    "*://www.threads.com/graphql/*",
    "*://www.threads.net/graphql/*",
    "*://www.threads.com/api/v1/*",
    "*://www.threads.net/api/v1/*"
];

function extractThreadsUsernameFromUrl(url) {
    const m = (url || "").match(/threads\.(?:com|net)\/@([A-Za-z0-9._]+)\/post\//);
    return m?.[1] || null;
}

// The media-item deep walk (shape test, bounded recursion, richest-per-code
// fold) lives in instagram.js as collectInstagramMediaItems — one item shape,
// one walker, shared by both parsers so the two can't drift apart (the walk's
// depth cap alone once cost eight debugging rounds here; see the comment at
// the walker). Threads-specific behavior stays in this file: the origin
// override and the username fallback below.

// Emit every collected item with full metadata via the shared Instagram
// emitter, under a canonical threads.com post origin.
function emitThreadsItems(details, bestByCode, pageUrl, label) {
    const fallbackUser = extractThreadsUsernameFromUrl(pageUrl || details.url || "");
    log("THREADS", `${label}: ${bestByCode.size} item(s)`, { url: (pageUrl || "").slice(0, 120) });
    for (const [code, item] of bestByCode) {
        const username = item.user?.username || fallbackUser || "unknown";
        sendInstagramItem(details, item, `https://www.threads.com/@${username}/post/${code}`);
    }
}

// Generic streaming-response reader: buffer the body, decode, hand the raw
// string to onBody. Shared by the doc (main_frame HTML) and API (XHR JSON)
// listeners.
function filterThreadsResponse(details, label, onBody) {
    readFilteredBody(details, "THREADS", label, onBody);
}

// (1) Logged-in: read the post JSON inlined in the page HTML's data-sjs blobs.
function listenerThreadsPage(details) {
    if (details.type !== "main_frame") return;
    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);
    filterThreadsResponse(details, "doc filter", (html, bytes) => {
        const sjsRegex = /<script[^>]*\bdata-sjs\b[^>]*>([\s\S]*?)<\/script>/g;
        const bestByCode = new Map();
        let scriptCount = 0, m;
        while ((m = sjsRegex.exec(html)) !== null) {
            scriptCount++;
            const parsed = tryParseJson(m[1]);
            if (parsed) collectInstagramMediaItems(parsed, bestByCode);
        }
        log("THREADS", `doc filter: ${bytes} bytes, ${scriptCount} data-sjs script(s)`);
        emitThreadsItems(details, bestByCode, details.url, "doc");
    });
}

// (2) Logged-out: read the post JSON from the GraphQL/API XHR Threads fires
// after the (media-less) document loads.
function listenerThreadsApi(details) {
    if (isOwnRequest(details.url)) return {};
    filterThreadsResponse(details, "api filter", (body, bytes) => {
        let str = body;
        if (str.startsWith("for (;;);")) str = str.slice(9); // anti-hijacking prefix
        const bestByCode = new Map();
        // Threads streams some GraphQL as newline-delimited JSON objects, like
        // Facebook — try whole-body first, then fall back to per-line.
        const whole = tryParseJson(str);
        if (whole) {
            collectInstagramMediaItems(whole, bestByCode);
        } else {
            for (const line of str.split("\n")) {
                const obj = tryParseJson(line);
                if (obj) collectInstagramMediaItems(obj, bestByCode);
            }
        }
        log("THREADS", `api filter: ${bytes} bytes`, { url: details.url.slice(0, 100) });
        const pageUrl = details.documentUrl || details.originUrl || details.url;
        emitThreadsItems(details, bestByCode, pageUrl, "api");
    });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerThreadsPage,
    { urls: THREADS_PAGE_PATTERNS, types: ["main_frame"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerThreadsApi,
    { urls: THREADS_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

// Note: Threads needs no content script. The main_frame doc filter
// (listenerThreadsPage) reads the same <script data-sjs> blobs straight from
// the raw network response — stock GeckoView filterResponseData, no patch — and
// the API filter (listenerThreadsApi) covers the logged-out / SPA XHRs. The old
// threads-content.js only re-read the initial-load data-sjs from the DOM (a
// duplicate of the doc filter) and captured nothing on SPA nav, so it was
// removed (CLAUDE.md "prefer one capture mechanism per site").

// ============================================================================
