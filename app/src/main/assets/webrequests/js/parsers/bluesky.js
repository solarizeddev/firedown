// Bluesky (bsky.app) parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, filterResponseText, enumerateMasterNative } from './common.js';

// ============================================================================
// Bluesky (bsky.app) — AT-Protocol app-view JSON over the wire
// ============================================================================
//
// bsky.app is a React SPA: every post / feed / profile view is rendered from
// XHR JSON the app fetches from the AT-Proto app view (api.bsky.app and
// public.api.bsky.app). A post's video lives in its `embed` as an
// `app.bsky.embed.video#view`, which carries the HLS master URL DIRECTLY:
//
//   { "$type": "app.bsky.embed.video#view",
//     "playlist":  "https://video.bsky.app/watch/<did>/<cid>/playlist.m3u8",
//     "thumbnail": "https://video.bsky.app/watch/<did>/<cid>/thumbnail.jpg",
//     "aspectRatio": { "width": 1080, "height": 1920 } }
//
// `playlist` is a stock HLS multivariant playlist (360p/720p child playlists on
// video.bsky.app, .ts segments on video.cdn.bsky.app), so we hand it to
// enumerateMasterNative — native OkHttp fetch + M3U8Parser enumerates the
// qualities with skipProbe (no ffprobe), exactly like Twitch/Kick/niconico. The
// CDN is public: the master request carries no auth/cookie, only an Origin, so
// the sole header we backfill is a bsky.app Referer (OriginInterceptor derives
// the Origin from it).
//
// READ THE RESPONSE, NOT THE DOM (the Threads/TikTok lesson). The video#view
// surfaces in getFeed / getAuthorFeed / getTimeline / getActorLikes /
// getListFeed / getPostThread / getPostThreadV2 / searchPosts. The post object
// shape is uniform wherever it nests (feed[].post, thread[].value.post, a quoted
// record, recordWithMedia#view), so we walk the whole JSON for any post that
// carries a video#view and read its caption (record.text) + author.
//
// DEDUP: each video is keyed on its OWN playlist URL (the master has no session
// token — only the child playlists do, so it's stable across refreshes), passed
// as `origin`. So a feed of N distinct videos yields N entities (the Java side
// sets the HLS_MASTER uid from `origin`), while a refresh / a video repeated
// across feed pages collapses via enumerateMasterNative's origin dedup. (Were
// the page origin used instead, the whole feed would collapse to one entity.)

const BSKY_VIDEO_VIEW = "app.bsky.embed.video#view";

// Only the app-view methods that actually carry post objects. Skips
// getProfile / getConfig / labelers / events, so we don't buffer their bodies
// through filterResponseData just to find nothing.
const BSKY_POST_METHOD_RE =
    /\/xrpc\/(?:app\.bsky\.feed\.(?:getFeed|getAuthorFeed|getTimeline|getActorLikes|getListFeed|getPostThread|searchPosts|getPosts|getQuotes)|app\.bsky\.unspecced\.getPostThread\w*)\b/;

// The HLS master a video plays from. Matches ONLY the multivariant master
// (.../watch/<did>/<cid>/playlist.m3u8), not the per-quality child playlists
// (.../<cid>/360p/video.m3u8) — those have an extra path segment.
const BSKY_MASTER_RE =
    /^https:\/\/video\.bsky\.app\/watch\/[^/]+\/[^/]+\/playlist\.m3u8(?:[?#]|$)/;

// playlist-URL -> { name, description, img }. Populated from every xrpc response
// we parse (feed/profile/thread). bsky is an SPA backed by an in-memory
// React-Query cache, so navigating within it (profile -> post) or revisiting a
// cached view fires NO xrpc request — the app-view JSON never crosses the wire,
// so listenerBskyApi can't see it. But the player ALWAYS fetches the HLS master
// off the wire when a video is viewed/played, so listenerBskyMaster captures
// that directly and enriches it from this cache when we did see the JSON earlier.
const bskyMetaCache = new Map();
const BSKY_META_CACHE_MAX = 512;

function cacheBskyMeta(playlist, meta) {
    if (!playlist) return;
    if (bskyMetaCache.has(playlist)) return;
    if (bskyMetaCache.size >= BSKY_META_CACHE_MAX) {
        bskyMetaCache.delete(bskyMetaCache.keys().next().value); // FIFO trim
    }
    bskyMetaCache.set(playlist, meta);
}

// Collapse whitespace/newlines to single spaces so a multi-line caption becomes
// one clean title line.
function cleanBskyText(s) {
    return (typeof s === "string" ? s : "").replace(/\s+/g, " ").trim();
}

// A caption/alt is only usable as a title if it carries an actual letter or
// number (any script) — this drops empty captions and emoji-only ones
// (e.g. "🎧🫶"), which make poor titles, so we fall back to the author instead.
function isUsableTitle(s) {
    return /[\p{L}\p{N}]/u.test(s);
}

// Titles can be long (full post captions); trim to a filename-friendly length on
// a word boundary with an ellipsis.
function truncateBskyTitle(s, max) {
    if (s.length <= max) return s;
    const cut = s.slice(0, max);
    const sp = cut.lastIndexOf(" ");
    return (sp > max * 0.6 ? cut.slice(0, sp) : cut).trim() + "…";
}

function buildBskyVideo(view, author, text) {
    author = author || {};
    const handle = author.handle || "";
    const displayName = author.displayName || handle || "Bluesky";
    const caption = cleanBskyText(text);           // the owning post's text
    const alt = cleanBskyText(view.alt);           // the video's alt/description

    // bsky posts have no title. Prefer the post caption; fall back to the video
    // alt text (often a rich description); finally the author so it's never blank.
    let name;
    if (isUsableTitle(caption)) name = truncateBskyTitle(caption, 120);
    else if (isUsableTitle(alt)) name = truncateBskyTitle(alt, 120);
    else name = `${displayName} on Bluesky`;

    return {
        playlist: view.playlist,
        thumbnail: view.thumbnail || null,
        name,
        description: handle ? `${displayName} (@${handle})` : displayName,
    };
}

// Walk the app-view JSON (plain JSON.parse output — ordinary objects, not Xray
// page proxies) for EVERY app.bsky.embed.video#view node, attributing each to
// the nearest enclosing author + post text. Carrying that context down the walk
// (instead of matching a fixed "author+record+embed" post shape) is what makes
// it catch videos that DON'T sit on a top-level post: a QUOTED post puts the
// video under app.bsky.embed.record#viewRecord, which uses `author`+`value`+
// `embeds` (note: value/embeds, not record/embed) — the old shape gate missed
// those entirely. recordWithMedia#view (its `media` is the video#view) and any
// deeper nesting are covered for free. Bounded (depth + node budget) and dedups
// playlist URLs within the one response.
function collectBskyVideos(root) {
    const out = [];
    const seen = new Set();
    let nodes = 0;
    const MAX_NODES = 200000;   // threads can be large (hundreds of posts)
    const MAX_DEPTH = 40;       // quoted-post nesting adds several levels

    function visit(o, depth, author, text) {
        if (nodes++ > MAX_NODES || depth > MAX_DEPTH) return;
        if (Array.isArray(o)) {
            for (let i = 0; i < o.length; i++) visit(o[i], depth + 1, author, text);
            return;
        }
        if (!o || typeof o !== "object") return;

        // Update the nearest-post context. A new author means a new post/quoted
        // record, so reset the caption to that post's own text.
        let a = author;
        let t = text;
        if (o.author && typeof o.author === "object") {
            a = o.author;
            t = "";
        }
        // The caption lives on the record (normal post: record.text; the record
        // node itself: .text) or on the quoted record's value (value.text).
        if (typeof o.text === "string") t = o.text;
        else if (o.record && typeof o.record.text === "string") t = o.record.text;
        else if (o.value && typeof o.value.text === "string") t = o.value.text;

        if (o["$type"] === BSKY_VIDEO_VIEW && o.playlist && !seen.has(o.playlist)) {
            seen.add(o.playlist);
            out.push(buildBskyVideo(o, a, t));
        }

        for (const k in o) {
            const v = o[k];
            if (v && typeof v === "object") visit(v, depth + 1, a, t);
        }
    }

    visit(root, 0, null, "");
    return out;
}

async function processBskyResponse(details, json) {
    const videos = collectBskyVideos(json);
    log("BSKY", `parsed response: ${videos.length} video(s)`, { url: details.url.slice(0, 90) });
    if (videos.length === 0) return;

    // Public CDN; the only header the master fetch needs is a page Referer so
    // OriginInterceptor stamps the bsky.app Origin the CDN expects.
    const requestHeaders = [{ name: "Referer", value: "https://bsky.app/" }];

    // Cache EVERY video first, synchronously, then emit. The loop used to
    // cache-and-await per video, so video k was cached only after k-1 tab
    // round trips; a master the player fetched in that window found no
    // entry in listenerBskyMaster, emitted "Bluesky video", and won the
    // origin dedup over this titled emit.
    for (const v of videos) {
        cacheBskyMeta(v.playlist, { name: v.name, description: v.description, img: v.thumbnail || undefined });
    }
    for (const v of videos) {
        enumerateMasterNative(details, {
            url: v.playlist,
            origin: v.playlist, // stable per-video uid (master carries no token)
            name: v.name,
            description: v.description,
            img: v.thumbnail || undefined,
            requestHeaders,
        });
    }
}

// Passive read of the page's OWN authenticated app-view response (no refetch,
// byte-exact pass-through), same as the Twitter/Threads paths.
function listenerBskyApi(details) {
    if (!BSKY_POST_METHOD_RE.test(details.url)) return {};
    const ok = filterResponseText(details, (body) => {
        if (!body) return;
        const json = tryParseJson(body);
        if (!json) { log("BSKY", "response not JSON", { url: details.url.slice(0, 90) }); return; }
        processBskyResponse(details, json).catch((e) => log("BSKY", "process failed", e?.message));
    });
    if (!ok) log("BSKY", "filter unavailable", { url: details.url.slice(0, 90) });
    return {};
}

// Match ALL bsky subdomains (api / public.api / any future appview host) and ALL
// request types (no `types` filter) — a narrow exact-host + xmlhttprequest-only
// filter is why a webRequest listener can silently never fire here. The path is
// gated to /xrpc/ so it stays off the hundreds of image/media requests.
browser.webRequest.onBeforeRequest.addListener(
    listenerBskyApi,
    { urls: ["*://*.bsky.app/xrpc/*"] },
    ["blocking"]
);

// Wire-master fallback: capture the HLS master the player fetches whenever a
// video is actually viewed/played, so capture never depends on the xrpc JSON
// being on the wire (it often isn't — see bskyMetaCache). Read-only (no filter,
// no blocking) — the page's own player still gets its response untouched.
// Deduped on the master URL (origin), so it collapses with a richer pre-play
// capture from listenerBskyApi when both fire for the same video.
function listenerBskyMaster(details) {
    if (details.tabId < 0) return {};            // page player only, not our own probe
    if (!BSKY_MASTER_RE.test(details.url)) return {};
    const playlist = details.url.split(/[?#]/)[0];
    const meta = bskyMetaCache.get(playlist);
    log("BSKY", "master on wire", { url: playlist.slice(0, 100), cached: !!meta });
    enumerateMasterNative(details, {
        url: playlist,
        origin: playlist,
        name: meta ? meta.name : "Bluesky video",
        description: meta ? meta.description : "bsky.app",
        img: meta ? meta.img : undefined,
        requestHeaders: [{ name: "Referer", value: "https://bsky.app/" }],
    });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerBskyMaster,
    { urls: ["*://video.bsky.app/watch/*"], types: ["xmlhttprequest", "media", "object", "other"] },
    []
);

