// Vimeo parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, ensureTabId, enumerateMasterNative, isOwnRequest, markOwnRequest, tabClaims, tabOfDetails, registerPlaceholderHandler, queuePlaceholderCapture } from './common.js';

// ============================================================================
// Vimeo
// ============================================================================

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

// Per-tab player-config claims (the player can refetch its config); keyed by
// tab so the same video in a second tab captures too.
function vimeoClaims(details) {
    return tabClaims(tabOfDetails(details), "vimeo", 5_000, 64);
}
const VIMEO_FETCH_TIMEOUT_MS = 10_000;

async function listenerVimeo(details) {
    if (isOwnRequest(details.url)) return {};   // our own re-fetch below
    if (!details.url.includes("/video/")) return {};

    const urlKey = details.url.split('?')[0];
    if (!vimeoClaims(details).claim(urlKey)) return {};

    await ensureTabId(details);
    await captureVimeoConfig(details, details.url, { anonymous: false });
    return {};
}

/** The canonical vimeo.com/<id> page of a player.vimeo.com/video/<id> URL, or null. */
function vimeoCanonicalUrl(url) {
    const m = String(url).match(/player\.vimeo\.com\/video\/(\d+)/);
    return m ? `https://vimeo.com/${m[1]}` : null;
}

/**
 * Fetch a player.vimeo.com/video/<id> embed (or its JSON config) and emit its
 * HLS master. Shared by the player's own request (credentials: the session
 * the embed runs with) and a click-to-load placeholder (anonymous). Resolves
 * true when a master was handed on.
 */
async function captureVimeoConfig(details, fetchUrl, { anonymous, stillOnPage = null }) {
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
        const str = await response.text();

        let config = tryParseJson(str);
        if (!config) {
            const match = str.match(/\b(?:playerC|c)onfig\s*=\s*({.+?})\s*(?:;|\n|<\/script>)/);
            if (match?.[1]) config = tryParseJson(match[1]);
        }

        if (!config?.request?.files?.hls) return false;

        const { hls } = config.request.files;
        const videoUrl = hls.cdns?.[hls.default_cdn]?.avc_url;
        if (!videoUrl) return false;

        const jsonLd = extractVimeoJsonLd(str);

        const vid = config.video || {};
        // The video's own page names the capture. Without one (an unlisted or
        // private embed) the canonical vimeo.com/<id>: the EMBEDDING page's
        // URL used to stand in, and two such embeds on one page collapsed into
        // one capture under the page origin's dedup.
        const origin = vid.url || vimeoCanonicalUrl(fetchUrl) || details.originUrl || details.url;

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
                || null;
        }
        const duration = vid.duration > 0 ? Math.round(vid.duration * 1000) : 0;

        // avc_url is an HLS *master* playlist. Route it through the shared
        // master-enumeration path (Java OkHttp-fetches it, M3U8Parser enumerates
        // the renditions, skipProbe) — the same mechanism as niconico/Twitch/Kick
        // — instead of emitting a single media URL that the metadatareader probe
        // would open at capture time. No capture-time ffmpeg probe, and the user
        // gets a quality picker for free. enumerateMasterNative does its own
        // origin dedup and falls back to a plain media capture if enumeration
        // fails (which would then probe, as before).
        // A placeholder capture whose tab left the page meanwhile is not sent:
        // it would be filed under the next page.
        if (stillOnPage && !(await stillOnPage())) {
            log("VIMEO", `page gone before the emit`, { name });
            return false;
        }
        log("VIMEO", `Found video`, { name, img, url: videoUrl.slice(0, 80) });
        enumerateMasterNative(details, { url: videoUrl, origin, name, description, img, duration });
        return true;
    } catch (e) {
        log("VIMEO", `Error`, e.message);
        return false;
    }
}

// CLICK-TO-LOAD placeholders (common.js `player-placeholders`): a Vimeo embed
// the page loads only on a tap — an iframe waiting in data-src, a consent
// placeholder, a lite-vimeo card. The player's own embed page is fetched
// anonymously (the player in the iframe would run with the site's
// partitioned cookies, never the user's vimeo.com session) and read by the
// same captureVimeoConfig. An unlisted video's `h` hash rides along: without
// it the embed refuses. A domain-restricted embed refuses an extension fetch
// (no Referer); that capture fails, its claim is released, and the tap still
// captures through the player's own request.
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
        (stillOnPage) => captureVimeoConfig(details, playerUrl, { anonymous: true, stillOnPage }));
    return true;
}

registerPlaceholderHandler(handleVimeoPlaceholder);

// NOT blocking: the listener is async (it re-fetches the config/embed and
// parses it), and a blocking registration makes Gecko hold the page's OWN
// request until that promise settles — every Vimeo embed frame and config
// XHR used to wait a full duplicate round trip, and a stalled re-fetch
// stalled the player with it. Nothing here modifies or filters the request,
// so the observe-only registration is the right one.
browser.webRequest.onBeforeRequest.addListener(
    listenerVimeo,
    { urls: ["*://player.vimeo.com/*"], types: ["xmlhttprequest", "sub_frame"] }
);

