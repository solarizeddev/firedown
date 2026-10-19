// Vimeo parser.
//
// The player's config (window.playerConfig in the player.vimeo.com/video/<id>
// embed document, or the JSON its config request answers) names the HLS
// master, the title, owner, picture and duration. Three producers, one emit
// (the Dailymotion / Bluesky shape):
//
//  1. The player's OWN config response, read with filterResponseData as it
//     streams to the player. It used to be fetched a second time by the
//     extension: a duplicate request, sent without the page's Referer (a
//     domain-restricted embed refuses it) and outside the frame's partitioned
//     cookies. The re-fetch stays only as the fallback for a response that
//     could not be read.
//  2. Click-to-load placeholders (common.js `player-placeholders`): an embed
//     the page has not loaded yet, fetched anonymously.
//  3. The WIRE MASTER: the HLS master playlist the player fetches from
//     *.vimeocdn.com. Load-bearing, because Vimeo's streams are block-listed
//     for the generic catcher (parser-blocklist.js `vimeo`): a config this
//     parser could not read (a cached document, a shape it does not know)
//     would otherwise lose the video. Titled from what (1)/(2) cached for the
//     clip, else from the player frame's own page metadata.
//
// Every URL a config names (the HLS/DASH masters of each CDN and the
// progressive files) is claimed for the tab (requests.js claimPlayerMedia),
// and so is the player frame: the content script's scrape of the inline
// config and the player's progressive fetches are this clip again. The
// progressive host is deliberately NOT block-listed: sites play Vimeo's
// direct file links in their own <video> (progressive_redirect), with no
// Vimeo player and no config for this parser to read.
import {
    log, tryParseJson, ensureTabId, enumerateMasterNative, isOwnRequest, markOwnRequest,
    tabClaims, tabOfDetails, filterResponseText, MetaCache,
    registerPlaceholderHandler, queuePlaceholderCapture,
} from './common.js';
import { claimPlayerMedia, pickCaptureMeta } from '../requests.js';

// ============================================================================
// Vimeo
// ============================================================================

const VIMEO_FETCH_TIMEOUT_MS = 10_000;
// How long the wire master waits for a config read still in flight in its
// tab before it titles the capture from the frame instead.
let vimeoMasterGraceMs = 3_000;
const VIMEO_FRAME_META_MS = 1_500;

// The embed document (/video/<id>) and the config request (/video/<id>/config).
const VIMEO_CONFIG_PATH_RE = /^\/video\/\d+(?:\/config)?\/?$/;
// The HLS master of the adaptive layout (`…/v2/playlist/av/primary/[sub/<track>/]playlist.m3u8`);
// the per-rendition playlists live under `/v2/playlist/av/<x>/avf/<y>/media.m3u8`.
const VIMEO_MASTER_RE = /\/v2\/playlist\/av\/primary\/(?:[^?#]*\/)?playlist\.m3u8(?:[?#]|$)/;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Titles, owners, pictures and durations by clip (the uuid every CDN URL of
// one video carries): facts about the content, shared by every tab.
const vimeoMetaCache = new MetaCache(256, 30 * 60 * 1000);

// Per-tab player-config claims (the player can refetch its config); keyed by
// tab so the same video in a second tab captures too.
function vimeoClaims(details) {
    return tabClaims(tabOfDetails(details), "vimeo", 5_000, 64);
}
// Config reads in flight in a tab, which the wire master waits for.
function vimeoPending(tabId) {
    return tabClaims(tabId, "vimeo-pending", 8_000, 16);
}
// One wire-master decision per (tab, clip).
function vimeoMasterClaims(tabId) {
    return tabClaims(tabId, "vimeo-master", 30_000, 64);
}

function hasLive(claims) {
    for (const _ of claims.keys()) return true;
    return false;
}

function isVimeoConfigUrl(url) {
    let u;
    try { u = new URL(url); } catch (_) { return false; }
    return u.hostname === "player.vimeo.com" && VIMEO_CONFIG_PATH_RE.test(u.pathname);
}

/** The vimeo.com/<id> page of a player or watch URL, or null. */
function vimeoPageOf(url) {
    const s = String(url || "");
    let m = s.match(/^https?:\/\/player\.vimeo\.com\/video\/(\d+)/);
    if (!m) m = s.match(/^https?:\/\/(?:www\.)?vimeo\.com\/(?:channels\/[\w-]+\/)?(\d+)(?:[/?#]|$)/);
    return m ? `https://vimeo.com/${m[1]}` : null;
}

/** The clip uuid a vimeocdn URL carries as a path segment, or null. */
function vimeoClipOf(url) {
    let path;
    try { path = new URL(url).pathname; } catch (_) { return null; }
    for (const seg of path.split("/")) {
        if (UUID_RE.test(seg)) return seg.toLowerCase();
    }
    return null;
}

function extractVimeoJsonLd(html) {
    const regex = /<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
    const results = [];
    let match;
    while ((match = regex.exec(html)) !== null) {
        const parsed = tryParseJson(match[1].trim());
        if (parsed) results.push(parsed);
    }
    return results;
}

/**
 * Read a player config (the JSON, or the embed HTML that inlines it) into
 * { masterUrl, clip, meta, urls }, or null when it names no HLS master.
 * `configUrl` is the request it answered (the id of an unlisted video's page).
 */
function parseVimeoConfig(text, configUrl) {
    let config = tryParseJson(text);
    if (!config) {
        const match = text.match(/\b(?:playerC|c)onfig\s*=\s*({.+?})\s*(?:;|\n|<\/script>)/);
        if (match?.[1]) config = tryParseJson(match[1]);
    }
    const files = config?.request?.files;
    const hls = files?.hls;
    if (!hls?.cdns || typeof hls.cdns !== "object") return null;

    const cdnList = Object.values(hls.cdns).filter((c) => c && typeof c === "object");
    const masterUrl = hls.cdns[hls.default_cdn]?.avc_url
        || cdnList.find((c) => typeof c.avc_url === "string")?.avc_url
        || hls.cdns[hls.default_cdn]?.url;
    if (typeof masterUrl !== "string" || !/^https?:\/\//.test(masterUrl)) return null;

    // Every URL the player may fetch for this clip: each CDN's HLS and DASH
    // masters and the progressive files.
    const urls = [];
    const take = (v) => { if (typeof v === "string" && /^https?:\/\//.test(v)) urls.push(v); };
    for (const c of cdnList) { take(c.avc_url); take(c.url); take(c.captions); }
    for (const c of Object.values(files.dash?.cdns || {})) {
        if (c && typeof c === "object") { take(c.avc_url); take(c.url); }
    }
    for (const p of Array.isArray(files.progressive) ? files.progressive : []) take(p?.url);

    const vid = config.video || {};
    const jsonLd = extractVimeoJsonLd(text);
    // Display metadata: JSON-LD when the response is HTML (embedded players),
    // else the config.video fields.
    let name, description, img;
    if (jsonLd[0]) {
        if (jsonLd[0].name) name = jsonLd[0].name;
        if (jsonLd[0].description) description = jsonLd[0].description;
        const thumb = jsonLd[0].thumbnailUrl
            || (Array.isArray(jsonLd[0].thumbnail) ? jsonLd[0].thumbnail[0]?.url : jsonLd[0].thumbnail?.url);
        if (thumb) img = thumb;
    }
    if (!name && vid.title) name = vid.title;
    if (!description && vid.owner?.name) description = vid.owner.name;
    if (!img) {
        img = vid.thumbnail_url
            || vid.thumbs?.base || vid.thumbs?.["1280"] || vid.thumbs?.["640"]
            || undefined;
    }
    const duration = vid.duration > 0 ? Math.round(vid.duration * 1000) : 0;
    // The video's own page names the capture. Without one (an unlisted or
    // private embed) the canonical vimeo.com/<id>: the EMBEDDING page's URL
    // used to stand in, and two such embeds on one page collapsed into one
    // capture under the page origin's dedup. The wire master derives the same
    // vimeo.com/<id> from its frame, so both paths agree on the key.
    const origin = vid.url || vimeoPageOf(configUrl) || configUrl;

    return { masterUrl, clip: vimeoClipOf(masterUrl), meta: { origin, name, description, img, duration }, urls };
}

/**
 * Cache a parsed config's metadata, claim its URLs and frame for the tab, and
 * hand its HLS master to native (Java OkHttp-fetches it, M3U8Parser
 * enumerates the renditions, no capture-time ffmpeg probe; the same path as
 * niconico/Twitch/Kick). enumerateMasterNative owns the origin dedup, so the
 * wire master collapses with this. Resolves true when the master was handed on.
 */
async function deliverVimeoConfig(details, parsed, frameUrl, { stillOnPage = null } = {}) {
    if (parsed.clip) vimeoMetaCache.set(parsed.clip, parsed.meta);
    await ensureTabId(details);
    // A placeholder capture whose tab left the page meanwhile is not sent:
    // it would be filed under the next page.
    if (stillOnPage && !(await stillOnPage())) {
        log("VIMEO", `page gone before the emit`, { name: parsed.meta.name });
        return false;
    }
    claimPlayerMedia(tabOfDetails(details), frameUrl, parsed.urls);
    log("VIMEO", `Found video`, { name: parsed.meta.name, url: parsed.masterUrl.slice(0, 80) });
    enumerateMasterNative(details, { url: parsed.masterUrl, ...parsed.meta });
    return true;
}

/**
 * Fetch a player.vimeo.com/video/<id> embed (or its config) ourselves and
 * deliver it: the click-to-load placeholder path (anonymous), and the
 * fallback for a player response the filter could not read (with the
 * session the embed runs with). Resolves true when a master was handed on.
 */
async function fetchVimeoConfig(details, fetchUrl, { anonymous, stillOnPage = null, frameUrl = fetchUrl }) {
    try {
        markOwnRequest(fetchUrl);
        const response = await fetch(fetchUrl, {
            credentials: anonymous ? "omit" : "include",
            signal: AbortSignal.timeout(VIMEO_FETCH_TIMEOUT_MS)
        });
        if (!response.ok) {
            log("VIMEO", `config fetch failed`, { status: response.status });
            return false;
        }
        const parsed = parseVimeoConfig(await response.text(), fetchUrl);
        if (!parsed) return false;
        return await deliverVimeoConfig(details, parsed, frameUrl, { stillOnPage });
    } catch (e) {
        log("VIMEO", `Error`, e.message);
        return false;
    }
}

// The player's own embed document / config response. Blocking only to open a
// response filter; it returns synchronously, so the page's request is never
// held (it used to re-fetch the config before letting the embed load).
function listenerVimeo(details) {
    if (isOwnRequest(details.url)) return {};   // our own fallback fetch below
    if (!isVimeoConfigUrl(details.url)) return {};

    const urlKey = details.url.split('?')[0];
    if (!vimeoClaims(details).claim(urlKey)) return {};

    const pending = vimeoPending(tabOfDetails(details));
    pending.add(details.requestId);
    const done = () => pending.release(details.requestId);
    // The frame the player runs in: the embed document itself, or the page
    // whose player requested the config.
    const frameUrl = details.type === "xmlhttprequest"
        ? (details.documentUrl || details.originUrl || details.url)
        : details.url;
    const refetch = () => {
        fetchVimeoConfig(details, details.url, { anonymous: false, frameUrl }).finally(done);
    };

    const armed = filterResponseText(details, (text) => {
        // Unreadable (a filter error, a body over the cap): read it ourselves.
        if (text == null) { refetch(); return; }
        const parsed = parseVimeoConfig(text, details.url);
        // Read but no config (a private video, a challenge page): fetching
        // it again would read the same.
        if (!parsed) { log("VIMEO", `no config in the response`, { url: details.url.slice(0, 90) }); done(); return; }
        deliverVimeoConfig(details, parsed, frameUrl).catch((e) => log("VIMEO", `Error`, e.message)).finally(done);
    });
    if (!armed) refetch();
    return {};
}

// The player frame's own page metadata (the content script's responder):
// the embed carries the clip's JSON-LD and its title.
async function vimeoFrameMeta(details, tabId) {
    if (typeof tabId !== "number" || tabId < 0) return null;
    const frameId = typeof details.frameId === "number" ? details.frameId : 0;
    let page = null;
    try {
        page = await Promise.race([
            browser.tabs.sendMessage(tabId, { kind: "get-page-metadata", mediaUrl: details.url }, { frameId }),
            new Promise((resolve) => setTimeout(() => resolve(null), VIMEO_FRAME_META_MS)),
        ]);
    } catch (_) {
        page = null;
    }
    if (!page || typeof page !== "object") return null;
    const picked = pickCaptureMeta(page);
    const name = String(picked.name || "").replace(/\s+on Vimeo\s*$/i, "").trim();
    return {
        name: name || undefined,
        description: picked.description || undefined,
        img: picked.img || undefined,
        url: typeof page.url === "string" ? page.url : undefined,
    };
}

async function waitForVimeoMeta(clip, tabId) {
    const deadline = Date.now() + vimeoMasterGraceMs;
    while (Date.now() < deadline) {
        const hit = clip ? vimeoMetaCache.get(clip) : undefined;
        if (hit) return hit;
        if (!hasLive(vimeoPending(tabId))) return null;
        await new Promise((r) => setTimeout(r, 100));
    }
    return clip ? (vimeoMetaCache.get(clip) || null) : null;
}

async function captureVimeoMaster(details, tabId, clip) {
    let meta = clip ? vimeoMetaCache.get(clip) : undefined;
    if (!meta) meta = await waitForVimeoMeta(clip, tabId);
    let source = "cache";
    if (!meta) {
        source = "frame";
        const frame = await vimeoFrameMeta(details, tabId);
        const origin = vimeoPageOf(details.documentUrl) || vimeoPageOf(details.originUrl)
            || vimeoPageOf(frame?.url) || details.documentUrl || frame?.url || details.url;
        meta = {
            origin,
            name: frame?.name || "Vimeo video",
            description: frame?.description,
            img: frame?.img,
            duration: 0,
        };
    }
    log("VIMEO", `master on the wire (${source})`, { name: meta.name, url: details.url.slice(0, 90) });
    enumerateMasterNative(details, { url: details.url, ...meta });
}

// The HLS master as the player fetches it. Read-only (no filter, no
// blocking); one decision per (tab, clip), deduped against the config path's
// emit by the shared origin.
function listenerVimeoMaster(details) {
    if (details.tabId < 0) return {};            // the page's player only
    if (!VIMEO_MASTER_RE.test(details.url)) return {};
    const tabId = tabOfDetails(details);
    const clip = vimeoClipOf(details.url);
    if (!vimeoMasterClaims(tabId).claim(clip || details.url.split("?")[0])) return {};
    captureVimeoMaster(details, tabId, clip).catch((e) => log("VIMEO", `master capture failed`, e.message));
    return {};
}

// CLICK-TO-LOAD placeholders (common.js `player-placeholders`): a Vimeo embed
// the page loads only on a tap — an iframe waiting in data-src, a consent
// placeholder, a lite-vimeo card. The player's own embed page is fetched
// anonymously (the player in the iframe would run with the site's
// partitioned cookies, never the user's vimeo.com session) and read by the
// same parseVimeoConfig. An unlisted video's `h` hash rides along: without
// it the embed refuses. A domain-restricted embed refuses an extension fetch
// (no Referer); that capture fails, its claim is released, and the tap still
// captures through the player's own response.
function vimeoPlayerUrlOf(url) {
    let u;
    try { u = new URL(url); } catch (_) { return null; }
    const host = u.hostname.toLowerCase();
    let id = null;
    let hash = u.searchParams.get("h");
    if (host === "player.vimeo.com") {
        const m = u.pathname.match(/^\/video\/(\d{3,12})\/?$/);
        if (m) id = m[1];
    } else if (host === "vimeo.com" || host === "www.vimeo.com") {
        // vimeo.com/<id>, /<id>/<hash> (unlisted), /channels/<c>/<id>
        const m = u.pathname.match(/^\/(?:channels\/[\w-]+\/)?(\d{3,12})(?:\/([0-9a-f]{6,20}))?\/?$/i);
        if (m) {
            id = m[1];
            if (!hash && m[2]) hash = m[2];
        }
    }
    if (!id) return null;
    return `https://player.vimeo.com/video/${id}` + (hash && /^[0-9a-f]{6,20}$/i.test(hash) ? `?h=${hash}` : "");
}

function handleVimeoPlaceholder(url, details) {
    const playerUrl = vimeoPlayerUrlOf(url);
    if (!playerUrl) return false;
    queuePlaceholderCapture(details, `vimeo:${playerUrl.split("?")[0]}`,
        (stillOnPage) => fetchVimeoConfig(details, playerUrl, { anonymous: true, stillOnPage }));
    return true;
}

registerPlaceholderHandler(handleVimeoPlaceholder);

browser.webRequest.onBeforeRequest.addListener(
    listenerVimeo,
    { urls: ["*://player.vimeo.com/*"], types: ["main_frame", "sub_frame", "xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerVimeoMaster,
    { urls: ["*://*.vimeocdn.com/*"], types: ["xmlhttprequest", "media", "other"] }
);

// Test hook (scripts/parsers-replay.mjs) — the real grace is seconds.
function __setVimeoMasterGraceMs(ms) {
    vimeoMasterGraceMs = ms;
}

export { parseVimeoConfig, vimeoClipOf, vimeoPageOf, __setVimeoMasterGraceMs };
