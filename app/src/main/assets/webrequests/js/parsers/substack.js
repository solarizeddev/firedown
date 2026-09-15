// Substack parser — podcast episodes + article voiceovers.
import { log, tryParseJson, sendNative, collectFilteredResponse, readFilteredBody, resolveTabId } from './common.js';

// ============================================================================
// Substack  —  substack.com / <pub>.substack.com / custom publication domains
// ============================================================================
//
// Substack audio comes in two shapes, both plain re-fetchable files, no DRM:
//
//   podcast episode   post.podcast_url
//                     = https://api.substack.com/api/v1/audio/upload/<uuid>/src
//                       (EXTENSIONLESS — redirects to the S3 object; the
//                        player's <audio> fetches it as audio/mpeg)
//   article voiceover post.audio_items[] = { type: "tts", audio_url:
//                     "https://substack-video.s3.amazonaws.com/video_upload/post/
//                      <postId>/tts/<uuid>/<voice>.mp3", status }
//                     (audio_url is null while paywalled)
//
// Both URLs — and every field that names them — live ONLY in
// application/json bodies the generic catcher rejects: the reader feeds
// (`/api/v1/reader/feed?…`, `/api/v1/reader/feed/profile/<id>`,
// `/api/v1/posts/by-id/<id>?as-feed-item=1`, HAR 26-09-15) and, on a post
// page, the document's inlined `window._preloads = JSON.parse("…")` blob.
// Each post object carries the episode's own metadata beside the URL:
//
//   { "id": 215647157, "title": "En tiempo real", "type": "podcast",
//     "canonical_url": "https://eldolmen.substack.com/p/en-tiempo-real",
//     "podcast_url": "https://api.substack.com/api/v1/audio/upload/<uuid>/src",
//     "podcast_duration": "110.44572",            // SECONDS (string or number)
//     "podcast_episode_image_url": "…", "cover_image": null, "podcast_art_url": "…",
//     "publishedBylines": [ { "name": "Carlos Sánchez Almeida", … } ],
//     "audio_items": [ { "type": "tts", "audio_url": "…mp3", "status": "…" } ] }
//   wrapped in a feed item { "publication": { "name": "…", "subdomain": "…" }, "post": {…} }
//
// What the generic catcher did with these: nothing until the user pressed
// play, then it captured the played `/src` URL and, since the URL is
// extensionless, enriched it with the PAGE's metadata — on a profile / feed
// page that is the profile title, identical for every episode, and the file
// name fell to the URL's last segment ("src"). So a feed of ten episodes came
// out as ten untitled captures. This parser reads the bodies that carry the
// titles and emits ONE titled audio entry per episode PRE-PLAY (the Apple
// Podcasts / Spotify shape: a feed captures whole), plus a WIRE BACKBONE for
// the case the feeds never cross the wire.
//
// Three producers, all feeding the same emit:
//   1. API JSON  — filterResponseData on `*://*.substack.com/api/v1/*`
//      (xmlhttprequest), gated on the body mentioning an audio key before it
//      is parsed, then a bounded SHAPE walk (a post = an object with a string
//      `title` and a `podcast_url` / `free_podcast_url` / `audio_items[]`
//      audio) — no wrapper knowledge, so a feed endpoint rename can't lose it.
//   2. Document — the same walk over `window._preloads` extracted from a
//      `*://*.substack.com/*` main_frame (a post page SSR-inlines its post
//      there; the profile page's preloads hold no posts and walk to nothing).
//   3. Wire backbone — the player's own fetch of `api.substack.com/api/v1/
//      audio/upload/*` or a `…/tts/*.mp3` voiceover. Consulted against the
//      metadata cache the first two populate (uuid/URL → title …); on a MISS
//      it asks the media's own frame for the page metadata (the catcher's
//      `get-page-metadata` responder — og:title on a post page IS the episode
//      title). This is what keeps a publication on a CUSTOM DOMAIN captured:
//      its post page is not a `*.substack.com` main_frame, so producers 1–2
//      never run there, and the media hosts are block-listed for the generic
//      catcher (parser-blocklist.js `substack`, the cardinal rule) — without
//      the backbone the block would silently LOSE those episodes.
//
// Ceiling: a paywalled episode's `podcast_url` is null in the feed (nothing to
// emit) and its voiceover `audio_url` is null (`status: "paywalled"`); a
// logged-in subscriber's session sees the real URLs and the parser emits them.
// Preview clips (`free_podcast_url`) are emitted as "(preview)" when they are
// the only URL a post carries.

const SUBSTACK_API_PATTERNS = ["*://*.substack.com/api/v1/*"];
const SUBSTACK_DOC_PATTERNS = ["*://*.substack.com/*"];
const SUBSTACK_MEDIA_PATTERNS = [
    "*://api.substack.com/api/v1/audio/upload/*",
    "*://substack-video.s3.amazonaws.com/*",
];
const SUBSTACK_AUDIO_KEY_RE = /"(?:podcast_url|free_podcast_url|audio_url)"\s*:\s*"https?:/;
const SUBSTACK_UPLOAD_RE = /\/api\/v1\/audio\/upload\/([0-9a-f-]{36})\//i;
const SUBSTACK_TTS_RE = /substack-video\.s3\.amazonaws\.com\/.*\/tts\/.*\.mp3/i;
const SUBSTACK_WALK_MAX_DEPTH = 24;
const SUBSTACK_WALK_NODE_BUDGET = 40000;
const SUBSTACK_MAX_POSTS_PER_BODY = 200;
const SUBSTACK_META_CACHE_MAX = 500;
const SUBSTACK_EMIT_TTL_MS = 30000;
const SUBSTACK_META_QUERY_MS = 300;

// uuid / URL → { name, description, img, duration, origin }. Populated by every
// JSON / document body the parser reads, consumed by the wire backbone.
const substackMetaCache = new Map();
// URLs emitted in the last 30 s — the feed and the wire fetch (and its Range
// re-requests) all see the same URL; the repository dedups by URL anyway, this
// just keeps the logs and the native bridge quiet.
const substackEmitted = new Map();

function rememberMeta(key, meta) {
    if (!key) return;
    if (substackMetaCache.size >= SUBSTACK_META_CACHE_MAX) {
        const oldest = substackMetaCache.keys().next().value;
        substackMetaCache.delete(oldest);
    }
    substackMetaCache.set(key, meta);
}

function recentlyEmitted(url) {
    const t = substackEmitted.get(url);
    if (t && Date.now() - t < SUBSTACK_EMIT_TTL_MS) return true;
    substackEmitted.set(url, Date.now());
    if (substackEmitted.size > 1000) {
        for (const [k, v] of substackEmitted) {
            if (Date.now() - v >= SUBSTACK_EMIT_TTL_MS) substackEmitted.delete(k);
        }
    }
    return false;
}

function isHttpUrl(v) {
    return typeof v === "string" && /^https?:\/\//i.test(v);
}

function uploadIdOf(url) {
    const m = SUBSTACK_UPLOAD_RE.exec(url || "");
    return m ? m[1].toLowerCase() : null;
}

function durationMsOf(v) {
    const n = typeof v === "number" ? v : parseFloat(v);
    return Number.isFinite(n) && n > 0 ? Math.round(n * 1000) : undefined;
}

// A Substack post object: a titled object that names an audio file. The shape
// test is deliberately narrow (title + one of the three audio keys) so the
// countless other titled objects in a feed (publications, sections, tabs) and
// the audio-less newsletters walk past.
function isSubstackPost(o) {
    if (!o || typeof o !== "object" || Array.isArray(o)) return false;
    if (typeof o.title !== "string" || !o.title.trim()) return false;
    if (isHttpUrl(o.podcast_url) || isHttpUrl(o.free_podcast_url)) return true;
    if (Array.isArray(o.audio_items)) {
        for (let i = 0; i < o.audio_items.length; i++) {
            const a = o.audio_items[i];
            if (a && isHttpUrl(a.audio_url)) return true;
        }
    }
    return false;
}

function bylineOf(post) {
    const bl = post.publishedBylines;
    if (Array.isArray(bl)) {
        for (let i = 0; i < bl.length; i++) {
            if (bl[i] && typeof bl[i].name === "string" && bl[i].name.trim()) return bl[i].name.trim();
        }
    }
    return null;
}

// One post → the audio entries it names: the episode (or its preview when
// that is all the post carries) and every available voiceover rendering.
function entriesOfPost(post, pubName) {
    const title = post.title.trim();
    const author = bylineOf(post) || pubName || null;
    const img = isHttpUrl(post.podcast_episode_image_url) ? post.podcast_episode_image_url
        : isHttpUrl(post.cover_image) ? post.cover_image
        : isHttpUrl(post.podcast_art_url) ? post.podcast_art_url
        : undefined;
    const origin = isHttpUrl(post.canonical_url) ? post.canonical_url : null;
    const out = [];
    if (isHttpUrl(post.podcast_url)) {
        out.push({ url: post.podcast_url, name: title, description: author || undefined, img,
            duration: durationMsOf(post.podcast_duration), origin });
    } else if (isHttpUrl(post.free_podcast_url)) {
        out.push({ url: post.free_podcast_url, name: `${title} (preview)`, description: author || undefined, img,
            duration: durationMsOf(post.free_podcast_duration), origin });
    }
    if (Array.isArray(post.audio_items)) {
        for (let i = 0; i < post.audio_items.length; i++) {
            const a = post.audio_items[i];
            if (!a || !isHttpUrl(a.audio_url)) continue;
            out.push({ url: a.audio_url, name: `${title} (voiceover)`, description: author || undefined, img,
                duration: undefined, origin });
        }
    }
    return out;
}

// Bounded shape walk — depth + node budget + visited set. A feed item wraps
// the post beside its `publication`, so the publication name is carried DOWN
// as context (the post itself only names its bylines).
function collectSubstackPosts(root) {
    const found = [];
    const seen = new Set();
    let budget = SUBSTACK_WALK_NODE_BUDGET;
    const walk = (node, depth, pubName) => {
        if (budget-- <= 0 || depth > SUBSTACK_WALK_MAX_DEPTH || found.length >= SUBSTACK_MAX_POSTS_PER_BODY) return;
        if (!node || typeof node !== "object") return;
        if (seen.has(node)) return;
        seen.add(node);
        if (Array.isArray(node)) {
            for (let i = 0; i < node.length; i++) walk(node[i], depth + 1, pubName);
            return;
        }
        const pub = node.publication;
        if (pub && typeof pub === "object" && typeof pub.name === "string" && pub.name.trim()) pubName = pub.name.trim();
        if (isSubstackPost(node)) {
            found.push({ post: node, pubName });
            // A matched post's own subtree holds no further posts (its
            // attachments/restacks would be their own feed items).
            return;
        }
        for (const k in node) {
            if (Object.prototype.hasOwnProperty.call(node, k)) walk(node[k], depth + 1, pubName);
        }
    };
    walk(root, 0, null);
    return found;
}

async function emitSubstackEntries(entries, details, tag) {
    if (entries.length === 0) return 0;
    const tabId = await resolveTabId(details);
    let emitted = 0;
    for (const e of entries) {
        const meta = { name: e.name, description: e.description, img: e.img, duration: e.duration, origin: e.origin };
        rememberMeta(e.url, meta);
        rememberMeta(uploadIdOf(e.url), meta);
        if (recentlyEmitted(e.url)) continue;
        const message = {
            url: e.url,
            type: "media",
            origin: e.origin || details.documentUrl || details.originUrl || details.url,
            tabId,
            request: details.requestId,
            name: e.name,
            description: e.description,
            img: e.img,
            duration: e.duration,
        };
        for (const k of Object.keys(message)) {
            if (message[k] === undefined) delete message[k];
        }
        // Duration came from the feed, so the probe has nothing to add. The
        // episode URL is extensionless, so the native side's audio gate
        // (processMediaSkipProbe) falls back to the probe there anyway — the
        // flag is honoured outright only for the .mp3 voiceovers.
        if (typeof message.duration === "number" && message.duration > 0) message.skipProbe = true;
        log(tag, `Found audio`, { name: message.name, url: message.url.slice(0, 100), tabId });
        sendNative(message);
        emitted++;
    }
    return emitted;
}

function handleSubstackJson(json, details, tag) {
    const posts = collectSubstackPosts(json);
    if (posts.length === 0) {
        log(tag, `no audio posts in body`, { url: details.url.slice(0, 120) });
        return;
    }
    const entries = [];
    for (const { post, pubName } of posts) entries.push(...entriesOfPost(post, pubName));
    emitSubstackEntries(entries, details, tag).then((n) => {
        log(tag, `posts=${posts.length} entries=${entries.length} emitted=${n}`, { url: details.url.slice(0, 120) });
    }).catch((e) => log(tag, `emit failed`, e.message));
}

// 1. The reader / post API JSON.
function listenerSubstackApi(details) {
    // api.substack.com/api/v1/audio/upload/<uuid>/src matches the API pattern
    // too; if a player ever fetches it as an XHR, that is megabytes of audio,
    // not JSON — leave it to the wire backbone, never buffer it here.
    if (SUBSTACK_UPLOAD_RE.test(details.url)) return {};
    collectFilteredResponse(details).then((text) => {
        if (!text || !SUBSTACK_AUDIO_KEY_RE.test(text)) return;
        const json = tryParseJson(text);
        if (!json) return;
        handleSubstackJson(json, details, "SUBSTACK");
    }).catch((e) => log("SUBSTACK", `api filter error`, e.message));
    return {};
}

// The document's `window._preloads = JSON.parse("<js string literal>")`: find
// the literal, walk it as a JS string (backslash escapes), JSON-parse the
// literal to get the inner JSON text, then parse that. Two parses on purpose —
// the inner text is itself JSON-escaped inside the JS string.
function extractSubstackPreloads(html) {
    const at = html.search(/_preloads\s*=\s*JSON\.parse\(\s*"/);
    if (at < 0) return null;
    const start = html.indexOf('"', at);
    let i = start + 1;
    while (i < html.length) {
        const c = html.charCodeAt(i);
        if (c === 0x5c) { i += 2; continue; }   // backslash: skip the escaped char
        if (c === 0x22) break;                    // the closing quote
        i++;
    }
    if (i >= html.length) return null;
    const inner = tryParseJson(html.slice(start, i + 1));
    return typeof inner === "string" ? tryParseJson(inner) : null;
}

// 2. A post page on a substack.com host.
function listenerSubstackDocument(details) {
    readFilteredBody(details, "SUBSTACK-DOC", "document filter", (html) => {
        if (!html || !SUBSTACK_AUDIO_KEY_RE.test(html.replace(/\\"/g, '"'))) return;
        const preloads = extractSubstackPreloads(html);
        if (!preloads) {
            log("SUBSTACK-DOC", `no _preloads in document`, { url: details.url.slice(0, 120) });
            return;
        }
        handleSubstackJson(preloads, details, "SUBSTACK-DOC");
    });
    return {};
}

// 3. The wire backbone: the player's fetch of the media itself.
function listenerSubstackMedia(details) {
    const url = details.url;
    if (!SUBSTACK_UPLOAD_RE.test(url) && !SUBSTACK_TTS_RE.test(url)) return;
    if (recentlyEmitted(url)) return;
    (async () => {
        const tabId = await resolveTabId(details);
        let meta = substackMetaCache.get(url) || substackMetaCache.get(uploadIdOf(url)) || null;
        let source = "cache";
        if (!meta) {
            source = "page";
            meta = await pageMetaFor(details, tabId);
        }
        const message = {
            url,
            type: "media",
            origin: (meta && meta.origin) || details.documentUrl || details.originUrl || url,
            tabId,
            request: details.requestId,
            name: (meta && meta.name) || "Substack audio",
            description: meta ? meta.description : undefined,
            img: meta ? meta.img : undefined,
            duration: meta ? meta.duration : undefined,
        };
        for (const k of Object.keys(message)) {
            if (message[k] === undefined) delete message[k];
        }
        if (typeof message.duration === "number" && message.duration > 0) message.skipProbe = true;
        log("SUBSTACK-WIRE", `Captured played audio (${source})`, { name: message.name, url: url.slice(0, 100), tabId });
        sendNative(message);
    })().catch((e) => log("SUBSTACK-WIRE", `failed`, e.message));
}

// Ask the media's own frame what the page says about itself — the generic
// catcher's responder (content-script.js `get-page-metadata`). On a post page
// og:title is the episode title and og:image its cover, which is exactly what
// a custom-domain publication needs and the feeds never delivered.
async function pageMetaFor(details, tabId) {
    if (typeof tabId !== "number" || tabId < 0) return null;
    const frameId = typeof details.frameId === "number" ? details.frameId : 0;
    let page = null;
    try {
        page = await Promise.race([
            browser.tabs.sendMessage(tabId, { kind: "get-page-metadata", mediaUrl: details.url }, { frameId }),
            new Promise((resolve) => setTimeout(() => resolve(null), SUBSTACK_META_QUERY_MS)),
        ]);
    } catch {
        page = null;
    }
    if (!page || typeof page !== "object") return null;
    const pick = (...vals) => {
        for (const v of vals) if (typeof v === "string" && v.trim()) return v.trim();
        return undefined;
    };
    const name = pick(page.mediaSessionTitle, page.audioLdName, page.ogTitle, page.twitterTitle, page.title);
    if (!name) return null;
    return {
        name,
        description: pick(page.mediaSessionArtist, page.audioLdDescription, page.ogDescription, page.description),
        img: pick(page.mediaSessionArtwork, page.audioLdThumbnail, page.ogImage),
        duration: undefined,
        origin: pick(page.url),
    };
}

browser.webRequest.onBeforeRequest.addListener(
    listenerSubstackApi,
    { urls: SUBSTACK_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerSubstackDocument,
    { urls: SUBSTACK_DOC_PATTERNS, types: ["main_frame"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerSubstackMedia,
    { urls: SUBSTACK_MEDIA_PATTERNS, types: ["media", "xmlhttprequest", "other"] }
);

export { collectSubstackPosts, entriesOfPost, extractSubstackPreloads, isSubstackPost };
