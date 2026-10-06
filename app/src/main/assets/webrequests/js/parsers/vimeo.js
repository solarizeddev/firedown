// Vimeo parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, ensureTabId, enumerateMasterNative, isOwnRequest, markOwnRequest, ClaimSet } from './common.js';

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

const processedVimeoUrls = new ClaimSet(5_000, 256);
const VIMEO_FETCH_TIMEOUT_MS = 10_000;

async function listenerVimeo(details) {
    if (isOwnRequest(details.url)) return {};   // our own re-fetch below
    if (!details.url.includes("/video/")) return {};

    const urlKey = details.url.split('?')[0];
    if (!processedVimeoUrls.claim(urlKey)) return {};

    await ensureTabId(details);

    try {
        markOwnRequest(details.url);
        const response = await fetch(details.url, { credentials: "include", signal: AbortSignal.timeout(VIMEO_FETCH_TIMEOUT_MS) });
        const str = await response.text();

        let config = tryParseJson(str);
        if (!config) {
            const match = str.match(/\b(?:playerC|c)onfig\s*=\s*({.+?})\s*(?:;|\n|<\/script>)/);
            if (match?.[1]) config = tryParseJson(match[1]);
        }

        if (!config?.request?.files?.hls) return {};

        const { hls } = config.request.files;
        const videoUrl = hls.cdns?.[hls.default_cdn]?.avc_url;
        if (!videoUrl) return {};

        const jsonLd = extractVimeoJsonLd(str);

        const vid = config.video || {};
        const origin = vid.url || details.originUrl || details.url;

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
        log("VIMEO", `Found video`, { name, img, url: videoUrl.slice(0, 80) });
        enumerateMasterNative(details, { url: videoUrl, origin, name, description, img, duration });
    } catch (e) {
        log("VIMEO", `Error`, e.message);
    }

    return {};
}

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

