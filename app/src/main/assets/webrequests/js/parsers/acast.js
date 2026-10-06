// Acast parser — podcast episodes from the Acast embed player (embed.acast.com,
// the iframe news sites use for their podcasts) and Acast's own show pages.
import { log, tryParseJson, sendNative, collectFilteredResponse, resolveTabId, markOwnRequest, isOwnRequest, decodeHtmlEntities, registerSpaHandler, ClaimSet, MetaCache } from './common.js';

// ============================================================================
// Acast  —  embed.acast.com / shows.acast.com / play.acast.com
// ============================================================================
//
// Acast hosts a large share of the news-publisher podcasts (EL MUNDO, …) and
// those publishers embed an episode as a CROSS-ORIGIN iframe:
//
//   <iframe src="https://embed.acast.com/<showId>/<episodeId>?cover=false&…">
//
// The embed document is an empty React shell (title "Acast Embed Player
// (<build hash>)", no og: tags, no <audio> until the app mounts). The app
// then fetches the episode from the feeder API — `window["acast/config"]
// .feederUrl`, today phoenix.prod.ateam.acast.cloud, formerly
// feeder.acast.com, same shape (HAR 26-10-06, elmundo.es):
//
//   GET https://phoenix.prod.ateam.acast.cloud/api/v1/shows/<show>/episodes/<ep>?showInfo=true
//   (application/json — which the generic catcher rejects)
//
//   { "id": "6ac2cca50d8a484144a38dde", "showId": "69e1e5…",
//     "title": "Sánchez, la vivienda y las elecciones",
//     "url": "https://sphinx.acast.com/p/open/s/<showId>/e/<epId>/media.mp3",
//     "contentType": "audio/mpeg", "contentLength": 11024927,
//     "duration": 689,                                  // SECONDS
//     "link": "https://shows.acast.com/el-mundo-al-dia/episodes/sanchez-…",
//     "image": "https://assets.pippa.io/shows/<showId>/….jpeg",
//     "images": { "x150": "…", "x350": "…", "x500": "…", "x1000": "…", "original": "…" },
//     "show": { "title": "EL MUNDO al día", "author": "El Mundo - Javier Attard",
//               "image": "…/show-cover.jpg", "images": { … }, … } }
//
// What the generic catcher did with it: nothing pre-play (the media URL lives
// only in that JSON body), and on play the audio is fetched from the iframe,
// whose <audio> binding / MediaSession the top-frame metadata responder never
// sees — so even a played episode landed untitled, with no cover. That is the
// "Acast links not detected for embedded audios" report. Same class as News
// Over Audio and the Spotify embed: read the body that carries the title and
// emit ONE titled audio entry per episode, PRE-PLAY.
//
// Three producers, one emit:
//   1. API JSON — filterResponseData on the feeder API (`*.acast.cloud/api/*`,
//      `feeder.acast.com/api/*`), gated on the body naming audio before it is
//      parsed, then a bounded SHAPE walk (an episode = an object with a string
//      `title` and an http `url` that is audio — by `contentType` or by
//      extension). No path knowledge beyond `/api/`, so a show/playlist embed
//      (a list of episodes) captures every episode it lists, and an endpoint
//      rename can't lose it (the Instagram lesson).
//   2. Wire backbone — the player's own fetch of `sphinx.acast.com/…/s/<show>/
//      e/<ep>/media.mp3` on play. The path carries both ids, so on a metadata
//      cache MISS the parser asks the feeder API for that episode itself
//      (public, anonymous, CORS `*`). This is what keeps capture working for
//      ANY player that plays an Acast-hosted episode — a publisher's own
//      player fed the RSS enclosure, a cached API response the filter never
//      saw — because sphinx is block-listed for the generic catcher
//      (parser-blocklist.js `acast`, the cardinal rule): without the backbone
//      the block would silently LOSE those plays.
//   3. Acast's own pages — `shows.acast.com/<show>/episodes/<ep>` and
//      `play.acast.com/s/<show>/<ep>` name the episode by slug, which the
//      feeder API accepts too, so the SPA handler resolves them pre-play
//      without depending on how those pages render (their SSR shape is not
//      HAR-verified; the API is).
//
// sphinx.acast.com 302s the player to a stitched (dynamic-ad) copy on another
// host. The generic catcher never emits a redirect hop, but it WOULD emit the
// target — so requests.js treats a parser-owned URL's redirect target as
// parser-owned too (same requestId; see `parserOwnedRequests` there). No guess
// about which host the stitcher lives on is needed.
//
// Ceiling: a premium (subscriber-only) episode's `url` is whatever the feeder
// hands an anonymous embed — the parser emits what the player would play.

const ACAST_API_PATTERNS = ["*://*.acast.cloud/api/*", "*://feeder.acast.com/api/*"];
const ACAST_MEDIA_PATTERNS = ["*://sphinx.acast.com/*"];
const ACAST_FEEDER_BASE = "https://phoenix.prod.ateam.acast.cloud/api/v1/shows/";
// …/s/<show>/e/<ep>/media.mp3 — the leading `/p/<publisher>/` segment ("open"
// in the HAR) is optional and ignored.
const ACAST_MEDIA_RE = /^https?:\/\/sphinx\.acast\.com\/(?:[^?#]*\/)?s\/([^/?#]+)\/e\/([^/?#]+)\/media\.mp3/i;
const ACAST_SHOWS_PAGE_RE = /^https?:\/\/shows\.acast\.com\/([^/?#]+)\/episodes\/([^/?#]+)/i;
const ACAST_PLAY_PAGE_RE = /^https?:\/\/play\.acast\.com\/s\/([^/?#]+)\/([^/?#]+)/i;
// Cheap pre-parse gate: an episode body names an audio file somewhere.
const ACAST_AUDIO_GATE_RE = /\.mp3|"contentType"\s*:\s*"audio\//;
const ACAST_AUDIO_URL_RE = /\.(?:mp3|m4a|aac|ogg|opus)(?:[?#]|$)/i;
const ACAST_WALK_MAX_DEPTH = 16;
const ACAST_WALK_NODE_BUDGET = 40000;
const ACAST_MAX_EPISODES_PER_BODY = 200;
const ACAST_META_CACHE_MAX = 500;
const ACAST_EMIT_TTL_MS = 30000;
const ACAST_EMITTED_MAX = 1000;   // hard cap on (tab, episode) claims
const ACAST_SPA_SEEN_MAX = 200;    // hard cap on (tab, page) SPA decisions
const ACAST_FETCH_TIMEOUT_MS = 5000;

// episode id (and media URL) → entry. Filled by every body the parser reads,
// consumed by the wire backbone.
const acastMetaCache = new MetaCache(ACAST_META_CACHE_MAX);
// "<tab>|<episode key>" → time of the emit (or of the claim, for an emit whose
// metadata fetch is still in flight). The API body, the player's media fetch
// and its Range re-requests all name the same episode; the repository dedups
// by URL anyway, this keeps the native bridge and the logs quiet and stops a
// burst of Range requests from each starting its own API lookup.
const acastEmitted = new ClaimSet(ACAST_EMIT_TTL_MS, ACAST_EMITTED_MAX);
// "<tab>|<show>/<ep>" for the SPA handler — tabs.onUpdated fires 3-4 times per
// load for the same URL (the Instagram lesson: one decision per page, never a
// fetch per tick).
const acastSpaSeen = new ClaimSet(ACAST_EMIT_TTL_MS, ACAST_SPA_SEEN_MAX);

function isHttpUrl(v) {
    return typeof v === "string" && /^https?:\/\//i.test(v);
}

function rememberMeta(key, entry) {
    if (key) acastMetaCache.set(key, entry);
}

function emitKey(tabId, key) {
    return (typeof tabId === "number" && tabId >= 0 ? tabId : -1) + "|" + key;
}

// True when this (tab, episode) was emitted/claimed within the TTL; otherwise
// claims it now and returns false. Check-and-claim in one step so concurrent
// producers (the API body and the media fetch, or two Range requests) can't
// both pass.
function claimEmit(tabId, key) {
    return !acastEmitted.claim(emitKey(tabId, key));
}

function episodeIdOfMediaUrl(url) {
    const m = ACAST_MEDIA_RE.exec(url || "");
    return m ? decodeURIComponent(m[2]).toLowerCase() : null;
}

function durationMsOf(v) {
    const n = typeof v === "number" ? v : parseFloat(v);
    return Number.isFinite(n) && n > 0 ? Math.round(n * 1000) : undefined;
}

// An Acast episode: a titled object whose `url` is audio. The `show` object
// beside it carries a title and links (`link`, `feedUrl`) but no audio `url`,
// so it walks past; so do the countless other titled objects a show/playlist
// response may hold.
function isAcastEpisode(o) {
    if (!o || typeof o !== "object" || Array.isArray(o)) return false;
    if (typeof o.title !== "string" || !o.title.trim()) return false;
    if (!isHttpUrl(o.url)) return false;
    if (typeof o.contentType === "string" && /^audio\//i.test(o.contentType)) return true;
    return ACAST_AUDIO_URL_RE.test(o.url);
}

// A show object (the parent of a show embed's episode list, or an episode's
// own `show`): a titled object that names its feed, never an audio `url`.
function isAcastShow(o) {
    if (!o || typeof o !== "object" || Array.isArray(o)) return false;
    if (typeof o.title !== "string" || !o.title.trim()) return false;
    return isHttpUrl(o.feedUrl) || typeof o.showUrl === "string" || Array.isArray(o.episodes);
}

function pickImage(o) {
    if (!o || typeof o !== "object") return undefined;
    const imgs = o.images && typeof o.images === "object" ? o.images : {};
    // x500 is the thumbor-resized square closest to a grid tile; `image` is
    // the original upload (often 3000 px, megabytes) — the fallback only.
    const candidates = [imgs.x500, imgs.x1000, imgs.x350, o.image, imgs.original];
    for (const c of candidates) {
        if (isHttpUrl(c)) return c;
    }
    return undefined;
}

function trimmed(v) {
    return typeof v === "string" && v.trim() ? decodeHtmlEntities(v.trim()) : undefined;
}

// One episode → the emit entry. The episode's own `show` (showInfo=true) wins
// over the show context carried down the walk (a show embed's list).
function entryOfEpisode(ep, showCtx) {
    const show = isAcastShow(ep.show) ? ep.show : showCtx;
    const id = typeof ep.id === "string" && ep.id ? ep.id.toLowerCase() : episodeIdOfMediaUrl(ep.url);
    return {
        key: id || ep.url,
        id,
        url: ep.url,
        name: trimmed(ep.title),
        // The show is the "series" — what a podcast row's second line names
        // (Apple Podcasts' collectionName, NOA's publisher).
        description: (show && (trimmed(show.title) || trimmed(show.author))) || undefined,
        img: pickImage(ep) || pickImage(show),
        duration: durationMsOf(ep.duration),
        origin: isHttpUrl(ep.link) ? ep.link : null,
    };
}

// Bounded shape walk — depth + node budget + visited set. A matched episode's
// subtree holds no further episodes (its `show` is context, not a list).
function collectAcastEpisodes(root) {
    const found = [];
    const seen = new Set();
    let budget = ACAST_WALK_NODE_BUDGET;
    const walk = (node, depth, showCtx) => {
        if (budget-- <= 0 || depth > ACAST_WALK_MAX_DEPTH || found.length >= ACAST_MAX_EPISODES_PER_BODY) return;
        if (!node || typeof node !== "object") return;
        if (seen.has(node)) return;
        seen.add(node);
        if (Array.isArray(node)) {
            for (let i = 0; i < node.length; i++) walk(node[i], depth + 1, showCtx);
            return;
        }
        if (isAcastEpisode(node)) {
            found.push(entryOfEpisode(node, showCtx));
            return;
        }
        if (isAcastShow(node)) showCtx = node;
        for (const k in node) {
            if (Object.prototype.hasOwnProperty.call(node, k)) walk(node[k], depth + 1, showCtx);
        }
    };
    walk(root, 0, null);
    return found;
}

function rememberEntry(e) {
    rememberMeta(e.id, e);
    rememberMeta(e.url, e);
}

function emitAcastEntry(e, details, tabId, tag, source) {
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
        if (message[k] === undefined || message[k] === null) delete message[k];
    }
    // Duration came from the API, so the capture probe has nothing to add; the
    // URL is a recognisable .mp3, so the native audio gate honours the flag.
    if (typeof message.duration === "number" && message.duration > 0) message.skipProbe = true;
    log(tag, `Found episode (${source})`, {
        name: message.name, show: message.description, url: message.url.slice(0, 100), tabId,
    });
    sendNative(message);
}

async function emitAcastEntries(entries, details, tag) {
    const tabId = await resolveTabId(details);
    let emitted = 0;
    for (const e of entries) {
        rememberEntry(e);
        if (claimEmit(tabId, e.key)) continue;
        emitAcastEntry(e, details, tabId, tag, "api");
        emitted++;
    }
    return emitted;
}

// 1. The feeder API JSON the embed player fetches.
function listenerAcastApi(details) {
    if (isOwnRequest(details.url)) return {};   // our own backbone / SPA lookup
    collectFilteredResponse(details).then((text) => {
        if (!text || !ACAST_AUDIO_GATE_RE.test(text)) return;
        const json = tryParseJson(text);
        if (!json) return;
        const entries = collectAcastEpisodes(json);
        if (entries.length === 0) {
            log("ACAST", `no episodes in body`, { url: details.url.slice(0, 120) });
            return;
        }
        emitAcastEntries(entries, details, "ACAST").then((n) => {
            log("ACAST", `episodes=${entries.length} emitted=${n}`, { url: details.url.slice(0, 120) });
        }).catch((e) => log("ACAST", `emit failed`, e.message));
    }).catch((e) => log("ACAST", `api filter error`, e.message));
    return {};
}

// The feeder lookup the backbone and the SPA handler share. `show`/`episode`
// are ids (the sphinx path) or slugs (the show pages) — the feeder accepts
// both, which is what the embed itself relies on for slug embed URLs.
async function fetchAcastEpisode(show, episode) {
    const apiUrl = ACAST_FEEDER_BASE + encodeURIComponent(show)
        + "/episodes/" + encodeURIComponent(episode) + "?showInfo=true";
    markOwnRequest(apiUrl);
    const opts = { credentials: "omit" };
    if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function") {
        opts.signal = AbortSignal.timeout(ACAST_FETCH_TIMEOUT_MS);
    }
    try {
        const resp = await fetch(apiUrl, opts);
        if (!resp.ok) {
            log("ACAST", `feeder lookup HTTP ${resp.status}`, { show, episode });
            return null;
        }
        const json = tryParseJson(await resp.text());
        const entries = json ? collectAcastEpisodes(json) : [];
        if (entries.length === 0) return null;
        rememberEntry(entries[0]);
        return entries[0];
    } catch (e) {
        log("ACAST", `feeder lookup failed`, e.message);
        return null;
    }
}

// 2. The wire backbone: the player's fetch of the episode audio itself.
function listenerAcastMedia(details) {
    // The extension's own requests (the catcher's HEAD probe of a scraped
    // <audio src>) are not a play and belong to no tab.
    const ext = (u) => typeof u === "string" && u.startsWith("moz-extension://");
    if (details.method === "HEAD" || ext(details.documentUrl) || ext(details.originUrl)) return;
    const m = ACAST_MEDIA_RE.exec(details.url);
    if (!m) return;
    const show = decodeURIComponent(m[1]);
    const epId = decodeURIComponent(m[2]).toLowerCase();
    (async () => {
        const tabId = await resolveTabId(details);
        if (claimEmit(tabId, epId)) return;
        let entry = acastMetaCache.get(epId) || acastMetaCache.get(details.url) || null;
        let source = "cache";
        if (!entry) {
            source = "feeder";
            entry = await fetchAcastEpisode(show, epId);
        }
        if (!entry) {
            // Still captured — sphinx is block-listed for the generic catcher,
            // so this is the only capture of the play. Named generically.
            source = "generic";
            entry = { key: epId, id: epId, url: details.url, name: "Acast episode", origin: null };
        }
        emitAcastEntry(entry, details, tabId, "ACAST-WIRE", source);
    })().catch((e) => log("ACAST-WIRE", `failed`, e.message));
}

function parseAcastPageUrl(url) {
    const m = ACAST_SHOWS_PAGE_RE.exec(url || "") || ACAST_PLAY_PAGE_RE.exec(url || "");
    if (!m) return null;
    return { show: decodeURIComponent(m[1]), episode: decodeURIComponent(m[2]) };
}

// 3. Acast's own episode pages.
function checkAndProcessAcastUrl(url, tabId) {
    if (!url || !url.includes("acast.com")) return;
    const parsed = parseAcastPageUrl(url);
    if (!parsed) return;
    const seenKey = emitKey(tabId, (parsed.show + "/" + parsed.episode).toLowerCase());
    if (!acastSpaSeen.claim(seenKey)) return;
    const now = Date.now();
    const details = { tabId, url, _resolvedTabId: tabId, requestId: `tab-${tabId}-${now}` };
    fetchAcastEpisode(parsed.show, parsed.episode).then((entry) => {
        if (!entry) return;
        if (claimEmit(tabId, entry.key)) return;
        emitAcastEntry({ ...entry, origin: entry.origin || url }, details, tabId, "ACAST-PAGE", "page");
    }).catch((e) => log("ACAST-PAGE", `failed`, e.message));
}

browser.webRequest.onBeforeRequest.addListener(
    listenerAcastApi,
    { urls: ACAST_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerAcastMedia,
    { urls: ACAST_MEDIA_PATTERNS, types: ["media", "xmlhttprequest", "other"] }
);

registerSpaHandler(checkAndProcessAcastUrl);

export { collectAcastEpisodes, isAcastEpisode, parseAcastPageUrl, episodeIdOfMediaUrl };
