const QUEUE_MAX_LENGTH = 256;
const DEBUG = true;
const COOKIE_CACHE_KEY = "instagram_cookie_cache";
const COOKIE_CACHE_TTL = 5 * 60 * 1000;

// ============================================================================
// Utilities
// ============================================================================

function log(category, message, data = null) {
    if (!DEBUG) return;
    const ts = new Date().toISOString().slice(11, 23);
    const prefix = `[${ts}][${category}]`;
    if (data !== null && data !== undefined) {
        console.log(prefix, message, typeof data === "object" ? JSON.stringify(data) : data);
    } else {
        console.log(prefix, message);
    }
}

function tryParseJson(str) {
    try { return JSON.parse(str); } catch { return null; }
}

// ============================================================================
// Dedup — keyed on origin URL (stable across CDN rotations)
// ============================================================================

const sentOrigins = new Set();
const SENT_ORIGIN_TTL = 30_000;

function alreadySent(origin) { return sentOrigins.has(origin); }

function markSent(origin) {
    sentOrigins.add(origin);
    setTimeout(() => sentOrigins.delete(origin), SENT_ORIGIN_TTL);
}

// ============================================================================
// Own-request tracking — prevents intercepting our own fetches
// ============================================================================

const ownRequests = new Map();
const OWN_REQUEST_TTL = 10_000;

function markOwnRequest(url) {
    ownRequests.set(url, Date.now());
    if (ownRequests.size > 50) {
        const now = Date.now();
        for (const [u, ts] of ownRequests) { if (now - ts > OWN_REQUEST_TTL) ownRequests.delete(u); }
    }
}

function isOwnRequest(url) {
    for (const [ownUrl, ts] of ownRequests) {
        if (Date.now() - ts > OWN_REQUEST_TTL) {
            ownRequests.delete(ownUrl);
            continue;
        }
        if (url === ownUrl || url.startsWith(ownUrl)) {
            ownRequests.delete(ownUrl);
            return true;
        }
    }
    return false;
}

// ============================================================================
// Instagram queue — holds requests waiting for cookies
// ============================================================================

const instagramQueue = new Map();

function addToInstagramQueue(details) {
    const key = details.shortcode;
    if (instagramQueue.has(key)) {
        log("QUEUE", `Already queued: ${key}`);
        return;
    }
    if (instagramQueue.size >= QUEUE_MAX_LENGTH) {
        const firstKey = instagramQueue.keys().next().value;
        instagramQueue.delete(firstKey);
        log("QUEUE", `Queue full, removed oldest: ${firstKey}`);
    }
    instagramQueue.set(key, details);
    log("QUEUE", `Added to queue: ${key}`, { queueSize: instagramQueue.size });
}

// ============================================================================
// Native messaging
// ============================================================================

async function sendNative(message) {
    try {
        // Resolve incognito state from tab if not already set
        if (message.incognito === undefined && message.tabId >= 0) {
            try {
                const tab = await browser.tabs.get(message.tabId);
                message.incognito = tab?.incognito || false;
            } catch (e) {
                message.incognito = false;
            }
        }
        log("NATIVE", `Sending message`, { url: message.url?.slice(0, 100), origin: message.origin, incognito: message.incognito });
        const response = await browser.runtime.sendNativeMessage("parser", message);
        log("NATIVE", `Received response`, response);
        return response;
    } catch (error) {
        log("NATIVE", `Error sending message`, error.message);
        return null;
    }
}

/**
 * Unified variant sender for Twitter, Instagram, and future parsers.
 * Handles dedup, sorting, and message construction.
 */
async function sendVariants(details, { variants, origin, description, img, name, duration }) {
    if (!Array.isArray(variants) || variants.length === 0) return;

    // Sort by height descending — best quality first
    variants.sort((a, b) => (b.height || 0) - (a.height || 0));

    // Dedup by origin
    if (alreadySent(origin)) {
        log("DEDUP", `Already sent for ${origin}, skipping`);
        return;
    }
    markSent(origin);

    log("VARIANTS", `Sending ${variants.length} variant(s)`, { origin });

    const tabId = await resolveTabId(details);

    // Resolve incognito state from the tab
    let incognito = false;
    if (tabId >= 0) {
        try {
            const tab = await browser.tabs.get(tabId);
            incognito = tab?.incognito || false;
        } catch (e) {}
    }

    const message = {
        url: variants[0].url,
        type: "variants",
        origin,
        tabId,
        request: details.requestId,
        variants,
        incognito
    };

    if (description) message.description = description;
    if (img) message.img = img;
    if (name) message.name = name;
    if (duration > 0) message.duration = duration;

    sendNative(message);
}

// ============================================================================
// Response filter (for intercepting Instagram API responses)
// ============================================================================

function collectFilteredResponse(details) {
    return new Promise((resolve, reject) => {
        let filter;
        try {
            filter = browser.webRequest.filterResponseData(details.requestId);
        } catch (e) {
            reject(new Error(`Failed to create filter: ${e.message}`));
            return;
        }

        const chunks = [];

        filter.ondata = (event) => {
            chunks.push(event.data);
            filter.write(event.data);
        };

        filter.onstop = () => {
            filter.close();
            const total = chunks.reduce((acc, c) => acc + c.byteLength, 0);
            const combined = new Uint8Array(total);
            let offset = 0;
            for (const chunk of chunks) {
                combined.set(new Uint8Array(chunk), offset);
                offset += chunk.byteLength;
            }
            resolve(new TextDecoder("utf-8").decode(combined));
        };

        filter.onerror = () => {
            filter.close();
            reject(new Error(`Filter error: ${filter.error}`));
        };
    });
}

// ============================================================================
// Tab ID resolution
// ============================================================================

const urlToTabCache = new Map();
const URL_TAB_CACHE_TTL = 30_000;

browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.url || changeInfo.status === "complete") {
        cacheTabUrl(tab.url, tabId);
    }
    // Process URLs on navigation start (loading) and completion
    const triggerUrl = changeInfo.url || (changeInfo.status === "complete" ? tab.url : null);
    if (triggerUrl) {
        checkAndProcessInstagramUrl(triggerUrl, tabId);
        checkAndProcessKickUrl(triggerUrl, tabId);
        checkAndProcessTwitchUrl(triggerUrl, tabId);
        checkAndProcessDailymotionUrl(triggerUrl, tabId);
    }
});

browser.tabs.onRemoved.addListener((tabId) => {
    for (const [url, entry] of urlToTabCache) {
        if (entry.tabId === tabId) urlToTabCache.delete(url);
    }
});

setInterval(() => {
    const now = Date.now();
    for (const [url, entry] of urlToTabCache) {
        if (now - entry.timestamp > URL_TAB_CACHE_TTL) urlToTabCache.delete(url);
    }
}, URL_TAB_CACHE_TTL);

function cacheTabUrl(url, tabId) {
    if (!url) return;
    urlToTabCache.set(url, { tabId, timestamp: Date.now() });
    try {
        const u = new URL(url);
        urlToTabCache.set(u.origin + u.pathname, { tabId, timestamp: Date.now() });
    } catch {}
}

async function resolveTabId(details) {
    if (details.tabId >= 0) return details.tabId;
    if (details._resolvedTabId >= 0) return details._resolvedTabId;

    const urlsToCheck = [details.originUrl, details.url, details.documentUrl].filter(Boolean);

    // Check cache
    for (const url of urlsToCheck) {
        const cached = urlToTabCache.get(url);
        if (cached && Date.now() - cached.timestamp < URL_TAB_CACHE_TTL) {
            details._resolvedTabId = cached.tabId;
            return cached.tabId;
        }
        try {
            const u = new URL(url);
            const base = urlToTabCache.get(u.origin + u.pathname);
            if (base && Date.now() - base.timestamp < URL_TAB_CACHE_TTL) {
                details._resolvedTabId = base.tabId;
                return base.tabId;
            }
        } catch {}
    }

    // Query tabs API
    try {
        // First: for embeds/iframes, match the parent page URL (originUrl/documentUrl)
        // against all open tabs by origin. This avoids misattributing embedded content
        // to a same-domain tab when the embed is on a third-party site.
        const parentUrls = [details.originUrl, details.documentUrl].filter(Boolean);
        if (parentUrls.length > 0) {
            const allTabs = await browser.tabs.query({ currentWindow: true });
            for (const pUrl of parentUrls) {
                try {
                    const pOrigin = new URL(pUrl).origin;
                    // Skip if parent is same domain as the embed (not a cross-site embed)
                    const embedOrigin = details.url ? new URL(details.url).origin : null;
                    if (pOrigin !== embedOrigin) {
                        const match = allTabs.find(t => t.url && t.url.startsWith(pOrigin));
                        if (match) { details._resolvedTabId = match.id; return match.id; }
                    }
                } catch {}
            }
        }

        for (const url of urlsToCheck) {
            const hostname = new URL(url).hostname;
            let pattern;

            if (hostname.includes("instagram.com")) {
                const pathMatch = url.match(/instagram\.com(\/(?:reel|p|stories)\/[^/?#]+)/);
                if (pathMatch) {
                    pattern = `*://*.instagram.com${pathMatch[1]}*`;
                    const tabs = await browser.tabs.query({ url: pattern });
                    if (tabs.length > 0) {
                        const tabId = tabs[0].id;
                        details._resolvedTabId = tabId;
                        cacheTabUrl(url, tabId);
                        return tabId;
                    }
                }
                pattern = "*://*.instagram.com/*";
            } else if (hostname.includes("twitter.com") || hostname.includes("x.com")) {
                pattern = "*://*.x.com/*";
            } else if (hostname.includes("vimeo.com")) {
                pattern = "*://*.vimeo.com/*";
            } else if (hostname.includes("kick.com")) {
                pattern = "*://*.kick.com/*";
            } else if (hostname.includes("twitch.tv")) {
                pattern = "*://*.twitch.tv/*";
            } else if (hostname.includes("dailymotion.com")) {
                pattern = "*://*.dailymotion.com/*";
            } else {
                pattern = `*://${hostname}/*`;
            }

            const tabs = await browser.tabs.query({ url: pattern });
            if (tabs.length > 0) {
                const active = tabs.find(t => t.active);
                const tabId = active ? active.id : tabs[0].id;
                details._resolvedTabId = tabId;
                cacheTabUrl(url, tabId);
                return tabId;
            }
        }

        const activeTabs = await browser.tabs.query({ active: true, currentWindow: true });
        if (activeTabs.length > 0) {
            details._resolvedTabId = activeTabs[0].id;
            return activeTabs[0].id;
        }
    } catch (e) {
        log("TABS", `Error resolving tabId`, e.message);
    }

    return -1;
}

async function ensureTabId(details) {
    if (details.tabId < 0) {
        details._resolvedTabId = await resolveTabId(details);
    }
    return details;
}

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

const processedVimeoUrls = new Set();

async function listenerVimeo(details) {
    if (!details.url.includes("/video/")) return {};

    const urlKey = details.url.split('?')[0];
    if (processedVimeoUrls.has(urlKey)) return {};

    processedVimeoUrls.add(urlKey);
    setTimeout(() => processedVimeoUrls.delete(urlKey), 5000);

    await ensureTabId(details);

    try {
        const response = await fetch(details.url, { credentials: "include" });
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
        const tabId = await resolveTabId(details);

        const vid = config.video || {};
        const origin = vid.url || details.originUrl || details.url;

        const message = {
            url: videoUrl,
            type: "media",
            origin,
            tabId,
            request: details.requestId
        };

        // JSON-LD (available when response is HTML, e.g. embedded players)
        if (jsonLd[0]) {
            if (jsonLd[0].name) message.name = jsonLd[0].name;
            if (jsonLd[0].description) message.description = jsonLd[0].description;
            const thumb = jsonLd[0].thumbnailUrl
                || (Array.isArray(jsonLd[0].thumbnail) ? jsonLd[0].thumbnail[0]?.url : jsonLd[0].thumbnail?.url);
            if (thumb) message.img = thumb;
        }

        // config.video fields (always available in JSON config responses)
        if (!message.name && vid.title) message.name = vid.title;
        if (!message.description && vid.owner?.name) message.description = vid.owner.name;
        if (!message.img) {
            message.img = vid.thumbnail_url
                || vid.thumbs?.base || vid.thumbs?.["1280"] || vid.thumbs?.["640"]
                || null;
        }
        if (vid.duration > 0) message.duration = Math.round(vid.duration * 1000);

        log("VIMEO", `Found video`, { name: message.name, img: message.img, url: videoUrl.slice(0, 80), tabId });
        sendNative(message);
    } catch (e) {
        log("VIMEO", `Error`, e.message);
    }

    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerVimeo,
    { urls: ["*://player.vimeo.com/*"], types: ["xmlhttprequest", "sub_frame"] },
    ["blocking"]
);

// ============================================================================
// Twitter / X
// ============================================================================

const processedTwitterUrls = new Set();

function handleTwitterHeaders(details) {
    if (!details.url.includes("TweetResultByRestId") && !details.url.includes("TweetDetail")) return;

    const urlKey = details.url.split('&')[0];
    if (processedTwitterUrls.has(urlKey)) return;

    const headers = {};
    for (const h of details.requestHeaders) headers[h.name] = h.value;

    processedTwitterUrls.add(urlKey);
    setTimeout(() => processedTwitterUrls.delete(urlKey), 5000);
    if (processedTwitterUrls.size > 100) {
        processedTwitterUrls.delete(processedTwitterUrls.values().next().value);
    }

    fetchTwitterData(details, headers);
}

function extractScreenNameFromUrl(details) {
    const urls = [details.originUrl, details.url, details.documentUrl].filter(Boolean);
    for (const url of urls) {
        const match = url.match(/x\.com\/([A-Za-z0-9_]+)\/status\//);
        if (match?.[1] && match[1] !== "i") return match[1];
    }
    for (const [url] of urlToTabCache) {
        const match = url.match(/x\.com\/([A-Za-z0-9_]+)\/status\//);
        if (match?.[1] && match[1] !== "i") return match[1];
    }
    return null;
}

async function fetchTwitterData(details, headers) {
    await ensureTabId(details);

    try {
        const response = await fetch(details.url, { method: "GET", headers, credentials: "include" });
        const parsed = tryParseJson(await response.text());
        if (!parsed) return;

        // Unwrap TweetWithVisibilityResults or similar wrappers
        const rawResult = parsed.data?.tweetResult?.result || parsed.data?.tweet?.result;
        if (!rawResult) return;
        const tweetResult = rawResult.tweet || rawResult;

        const legacy = tweetResult.legacy;
        const userResult = tweetResult.core?.user_results?.result;
        if (!legacy?.extended_entities?.media) return;

        const screenName = userResult?.legacy?.screen_name
            || rawResult.core?.user_results?.result?.legacy?.screen_name
            || extractScreenNameFromUrl(details)
            || "unknown";
        const tweetId = tweetResult.rest_id || legacy.id_str;
        const originUrl = screenName !== "unknown"
            ? `https://x.com/${screenName}/status/${tweetId}`
            : `https://x.com/i/status/${tweetId}`;
        const videoText = legacy.full_text || "";

        // Resolve thumbnail
        let imageUrl = legacy.extended_entities.media[0]?.media_url_https;
        if (!imageUrl) {
            const bindings = tweetResult.card?.legacy?.binding_values;
            const keys = ["thumbnail_image_original", "player_image_large", "player_image",
                          "summary_photo_image_original", "thumbnail_image"];
            for (const key of keys) {
                const url = bindings?.find(b => b.key === key)?.value?.image_value?.url;
                if (url) { imageUrl = url; break; }
            }
        }

        let videoCount = 0;
        for (const media of legacy.extended_entities.media) {
            if (!media.video_info?.variants) continue;

            const variants = media.video_info.variants
                .filter(v => v.content_type === "video/mp4")
                .map(v => {
                    const m = v.url.match(/\/(\d+)x(\d+)\//);
                    return {
                        url: v.url,
                        width: m ? parseInt(m[1]) : 0,
                        height: m ? parseInt(m[2]) : 0
                    };
                });

            if (variants.length === 0) continue;
            videoCount += variants.length;

            sendVariants(details, {
                variants,
                origin: originUrl,
                description: videoText,
                img: imageUrl,
                name: screenName,
                duration: media.video_info.duration_millis || 0
            });
        }

        log("TWITTER", `Found ${videoCount} variant(s)`, { user: screenName, tweetId });
    } catch (e) {
        log("TWITTER", `Error`, e.message);
    }
}

browser.webRequest.onSendHeaders.addListener(
    handleTwitterHeaders,
    { urls: ["*://api.x.com/graphql/*"], types: ["xmlhttprequest"] },
    ["requestHeaders"]
);

// ============================================================================
// Kick
// ============================================================================

const processedKickUrls = new Set();

/**
 * Extract slug from Kick URLs.
 * Matches: kick.com/{streamer}, kick.com/{streamer}/clips/{clipId},
 *          kick.com/video/{videoId}
 */
function parseKickUrl(url) {
    // Clip: kick.com/{streamer}/clips/{clipId} or kick.com/clips/{clipId}
    let m = url.match(/kick\.com\/(?:([A-Za-z0-9_-]+)\/)?clips?\/([\w-]+)/);
    if (m) return { type: "clip", streamer: m[1] || null, clipId: m[2] };

    // VOD: kick.com/video/{uuid} or kick.com/{streamer}/videos/{uuid}
    m = url.match(/kick\.com\/(?:[A-Za-z0-9_-]+\/)?videos?\/([\w-]+)/);
    if (m) return { type: "video", videoId: m[1] };

    // Channel page: kick.com/{streamer} (not api, not static paths)
    // Must not have further path segments (avoid matching /streamer/videos etc)
    m = url.match(/kick\.com\/([A-Za-z0-9_][A-Za-z0-9_-]{0,24})(?:[?#]|$)/);
    if (m && !["api", "video", "videos", "clips", "categories", "search", "settings", "following", "stream",
               "browse", "terms", "privacy", "about", "help", "contact", "dmca", "community-guidelines",
               "responsible-gambling", "dashboard", "auth", "login", "signup", "invite"].includes(m[1].toLowerCase())) {
        return { type: "channel", streamer: m[1] };
    }

    return null;
}

/**
 * Pick best thumbnail from Kick's responsive thumbnail string.
 */
function pickKickThumbnail(thumbnail) {
    if (!thumbnail) return null;
    if (typeof thumbnail === "string") {
        // If it's a URL already
        if (thumbnail.startsWith("http")) return thumbnail;
        return null;
    }
    // Responsive srcset string — pick the largest
    const srcset = thumbnail.responsive || thumbnail.url || thumbnail.src;
    if (typeof srcset === "string" && srcset.includes("http")) {
        const urls = srcset.match(/https?:\/\/[^\s,]+/g);
        return urls ? urls[0] : null;
    }
    return null;
}

async function fetchKickChannel(details, streamer) {
    const key = `kick-channel-${streamer}`;
    if (processedKickUrls.has(key)) return;
    processedKickUrls.add(key);
    setTimeout(() => processedKickUrls.delete(key), 10_000);

    await ensureTabId(details);
    log("KICK", `Fetching channel`, { streamer });

    try {
        const apiUrl = `https://kick.com/api/v2/channels/${streamer}`;
        const resp = await fetch(apiUrl, {
            credentials: "include",
            headers: { "Accept": "application/json" }
        });
        log("KICK", `API response`, { status: resp.status });
        if (!resp.ok) return;

        const data = tryParseJson(await resp.text());
        if (!data) { log("KICK", `Not valid JSON`); return; }

        processKickChannelData(details, data);
    } catch (e) {
        log("KICK", `Channel fetch error`, e.message);
    }
}

async function fetchKickClip(details, clipId) {
    const key = `kick-clip-${clipId}`;
    if (processedKickUrls.has(key)) return;
    processedKickUrls.add(key);
    setTimeout(() => processedKickUrls.delete(key), 10_000);

    await ensureTabId(details);
    log("KICK", `Fetching clip`, { clipId });

    try {
        const apiUrl = `https://kick.com/api/v2/clips/${clipId}`;
        markOwnRequest(apiUrl);
        const resp = await fetch(apiUrl, {
            headers: { "Accept": "application/json" }
        });
        if (!resp.ok) return;

        const data = tryParseJson(await resp.text());
        const clip = data?.clip || data;
        if (!clip) return;

        const videoUrl = clip.video_url || clip.clip_url;
        if (!videoUrl) { log("KICK", `No video URL in clip`); return; }

        const origin = `https://kick.com/clips/${clipId}`;
        if (alreadySent(origin)) return;
        markSent(origin);

        const tabId = await resolveTabId(details);
        const name = clip.channel?.username || clip.creator?.username || "Kick Clip";
        const title = clip.title || "";
        const img = clip.thumbnail_url || pickKickThumbnail(clip.thumbnail) || null;
        const duration = Math.round((clip.duration || 0) * 1000);

        sendNative({
            url: videoUrl,
            type: "media",
            origin,
            tabId,
            request: details.requestId,
            name,
            description: title,
            img,
            ...(duration > 0 ? { duration } : {})
        });
        log("KICK", `Sent clip`, { clipId, name });
    } catch (e) {
        log("KICK", `Clip error`, e.message);
    }
}

async function fetchKickVideo(details, videoId) {
    const key = `kick-video-${videoId}`;
    if (processedKickUrls.has(key)) return;
    processedKickUrls.add(key);
    setTimeout(() => processedKickUrls.delete(key), 10_000);

    await ensureTabId(details);
    log("KICK", `Fetching video`, { videoId });

    try {
        const apiUrl = `https://kick.com/api/v1/video/${videoId}`;
        markOwnRequest(apiUrl);
        const resp = await fetch(apiUrl, {
            headers: { "Accept": "application/json" }
        });
        if (!resp.ok) return;

        const data = tryParseJson(await resp.text());
        if (!data) return;

        const videoUrl = data.source || data.livestream?.source;
        if (!videoUrl) { log("KICK", `No source URL in video`); return; }

        const origin = `https://kick.com/video/${videoId}`;
        if (alreadySent(origin)) return;
        markSent(origin);

        const tabId = await resolveTabId(details);
        const name = data.livestream?.channel?.user?.username || "Kick VOD";
        const title = data.livestream?.session_title || "";
        const img = pickKickThumbnail(data.livestream?.thumbnail) || null;
        const duration = Math.round((data.livestream?.duration || 0));

        sendNative({
            url: videoUrl,
            type: "media",
            origin,
            tabId,
            request: details.requestId,
            name,
            description: title,
            img,
            ...(duration > 0 ? { duration } : {})
        });
        log("KICK", `Sent VOD`, { videoId, name });
    } catch (e) {
        log("KICK", `Video error`, e.message);
    }
}

function listenerKickPage(details) {
    log("KICK", `Page request intercepted`, { url: details.url, type: details.type, tabId: details.tabId });
    if (details.type !== "main_frame") return;
    const parsed = parseKickUrl(details.url);
    log("KICK", `URL parsed`, parsed || "no match");
    if (!parsed) return;

    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);

    if (parsed.type === "channel") {
        fetchKickChannel(details, parsed.streamer);
    } else if (parsed.type === "clip" && parsed.clipId) {
        fetchKickClip(details, parsed.clipId);
    } else if (parsed.type === "video" && parsed.videoId) {
        fetchKickVideo(details, parsed.videoId);
    }
}

// Intercept Kick API responses — use onCompleted to re-fetch with cookies after browser succeeds
function listenerKickApiComplete(details) {
    // Only process the channel endpoint itself
    const channelMatch = details.url.match(/\/api\/v2\/channels\/([A-Za-z0-9_-]+)\/?(?:\?|$)/);
    if (!channelMatch) return;

    const streamer = channelMatch[1];
    log("KICK", `Browser API call completed`, { streamer, status: details.statusCode });

    if (details.statusCode !== 200) return;

    // Re-fetch with credentials to get the JSON (browser already cleared Cloudflare)
    const key = `kick-api-${streamer}`;
    if (processedKickUrls.has(key)) return;
    processedKickUrls.add(key);
    setTimeout(() => processedKickUrls.delete(key), 10_000);

    (async () => {
        try {
            const resp = await fetch(details.url, {
                credentials: "include",
                headers: { "Accept": "application/json" }
            });
            if (!resp.ok) { log("KICK", `Re-fetch failed`, { status: resp.status }); return; }

            const data = tryParseJson(await resp.text());
            if (!data) return;

            processKickChannelData(details, data);
        } catch (e) {
            log("KICK", `Re-fetch error`, e.message);
        }
    })();
}

function processKickChannelData(details, data) {
    const playbackUrl = data.playback_url;
    const livestream = data.livestream || data.recent_livestream;
    const slug = data.slug || data.user?.username;

    log("KICK", `Channel data`, {
        slug,
        username: data.user?.username,
        hasPlaybackUrl: !!playbackUrl,
        isLive: livestream?.is_live || false
    });

    if (!playbackUrl) {
        log("KICK", `Channel offline`, { slug });
        return;
    }

    const origin = `https://kick.com/${slug}`;
    if (alreadySent(origin)) return;
    markSent(origin);

    const title = livestream?.session_title || data.user?.username || slug;
    const img = pickKickThumbnail(livestream?.thumbnail)
        || data.user?.profilepic || data.user?.profile_pic || null;
    const name = data.user?.username || slug;
    const category = livestream?.categories?.[0]?.name || "";

    sendNative({
        url: playbackUrl,
        type: "media",
        origin,
        tabId: details.tabId >= 0 ? details.tabId : -1,
        request: details.requestId || `kick-${Date.now()}`,
        name,
        description: category ? `${title} — ${category}` : title,
        img
    });
    log("KICK", `Sent live stream`, { slug, name, title: title.slice(0, 50) });
}

browser.webRequest.onBeforeRequest.addListener(
    listenerKickPage,
    { urls: ["*://kick.com/*", "*://www.kick.com/*", "*://m.kick.com/*"], types: ["main_frame"] },
    []
);

browser.webRequest.onCompleted.addListener(
    listenerKickApiComplete,
    { urls: ["*://kick.com/api/v2/channels/*", "*://www.kick.com/api/v2/channels/*"], types: ["xmlhttprequest"] }
);

// ============================================================================
// Twitch
// ============================================================================

const TWITCH_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
const processedTwitchUrls = new Set();
let twitchAuthToken = null;
let twitchDeviceId = null;

// ---- CDN M3U8 capture + metadata rendezvous ----
//
// Strategy: instead of building our own usher URL (which Twitch fills with ads),
// we capture the M3U8 URLs that the browser's own Twitch player fetches.
// The player's requests go through Twitch's ad pipeline first, and by the time
// the variant playlist is being fetched from the CDN, ads have been negotiated.
//
// We capture URLs from:
//   - usher.ttvnw.net (master playlists)
//   - video-weaver.*.hls.ttvnw.net (variant/segment playlists)
//   - *.abs.hls.ttvnw.net (VOD playlists)
//   - d2nvs31859zcd8.cloudfront.net (alternate CDN)
//
// These are married with metadata from GQL (title, thumbnail, game, etc).
// Whichever side arrives second triggers sendNative.

const TWITCH_RENDEZVOUS_TTL = 30_000;
const twitchRendezvous = new Map();

function getTwitchRendezvous(key) {
    let entry = twitchRendezvous.get(key);
    if (entry && Date.now() - entry.timestamp > TWITCH_RENDEZVOUS_TTL) {
        twitchRendezvous.delete(key);
        entry = null;
    }
    if (!entry) {
        entry = { m3u8Url: null, metadata: null, details: null, timestamp: Date.now() };
        twitchRendezvous.set(key, entry);
    }
    return entry;
}

function tryCompleteTwitchRendezvous(key) {
    const entry = twitchRendezvous.get(key);
    if (!entry || !entry.m3u8Url || !entry.metadata) return;

    const { m3u8Url, metadata, details } = entry;
    twitchRendezvous.delete(key);

    const origin = metadata.origin;
    if (alreadySent(origin)) {
        log("TWITCH", `Rendezvous complete but already sent`, { key });
        return;
    }
    markSent(origin);

    log("TWITCH", `Rendezvous complete — sending`, { key, url: m3u8Url.slice(0, 120) });

    sendNative({
        url: m3u8Url,
        type: "media",
        origin,
        tabId: details?.tabId >= 0 ? details.tabId : (details?._resolvedTabId ?? -1),
        request: details?.requestId || `twitch-${Date.now()}`,
        name: metadata.name,
        description: metadata.description,
        img: metadata.img,
        ...(metadata.duration > 0 ? { duration: metadata.duration } : {})
    });
}

setInterval(() => {
    const now = Date.now();
    for (const [key, entry] of twitchRendezvous) {
        if (now - entry.timestamp > TWITCH_RENDEZVOUS_TTL) twitchRendezvous.delete(key);
    }
}, TWITCH_RENDEZVOUS_TTL);

/**
 * Resolve the Twitch channel login from a tab ID by looking up cached tab URLs.
 */
function resolveLoginFromTab(tabId) {
    if (tabId < 0) return null;
    for (const [url, entry] of urlToTabCache) {
        if (entry.tabId === tabId) {
            const m = url.match(/twitch\.tv\/([A-Za-z0-9_]{1,25})(?:[?#/]|$)/);
            if (m && !["directory", "videos", "settings", "subscriptions", "inventory",
                       "drops", "wallet", "search", "clips"].includes(m[1].toLowerCase())) {
                return m[1].toLowerCase();
            }
        }
    }
    return null;
}

function resolveVodIdFromTab(tabId) {
    if (tabId < 0) return null;
    for (const [url, entry] of urlToTabCache) {
        if (entry.tabId === tabId) {
            const m = url.match(/twitch\.tv\/videos\/(\d+)/);
            if (m) return m[1];
        }
    }
    return null;
}

/**
 * CDN M3U8 listener — captures any .m3u8 request from ttvnw.net.
 *
 * Instead of parsing the CDN URL (which changes across API versions and
 * CDN hostnames), we resolve the channel/VOD from the tab that initiated
 * the request.  The tab URL (twitch.tv/{login} or twitch.tv/videos/{id})
 * is the stable ground truth.
 */
function listenerTwitchCdnM3u8(details) {
    if (isOwnRequest(details.url)) return;

    const tabLogin = resolveLoginFromTab(details.tabId);
    const tabVodId = resolveVodIdFromTab(details.tabId);

    if (tabLogin) {
        const entry = getTwitchRendezvous(tabLogin);
        if (!entry.m3u8Url) {
            log("TWITCH-CDN", `Captured M3U8 for ${tabLogin}`, { tabId: details.tabId, url: details.url.slice(0, 120) });
            entry.m3u8Url = details.url;
            if (!entry.details && details.tabId >= 0) {
                entry.details = { tabId: details.tabId, _resolvedTabId: details.tabId, requestId: `cdn-${Date.now()}` };
            }
            tryCompleteTwitchRendezvous(tabLogin);
        }
        return;
    }

    if (tabVodId) {
        const rvKey = `vod-${tabVodId}`;
        const entry = getTwitchRendezvous(rvKey);
        if (!entry.m3u8Url) {
            log("TWITCH-CDN", `Captured M3U8 for VOD ${tabVodId}`, { tabId: details.tabId, url: details.url.slice(0, 120) });
            entry.m3u8Url = details.url;
            if (!entry.details && details.tabId >= 0) {
                entry.details = { tabId: details.tabId, _resolvedTabId: details.tabId, requestId: `cdn-${Date.now()}` };
            }
            tryCompleteTwitchRendezvous(rvKey);
        }
        return;
    }

    log("TWITCH-CDN", `M3U8 captured but no tab match`, { tabId: details.tabId, url: details.url.slice(0, 80) });
}

// Broad pattern — catches any M3U8 from any ttvnw.net subdomain
browser.webRequest.onBeforeRequest.addListener(
    listenerTwitchCdnM3u8,
    { urls: ["*://*.ttvnw.net/*.m3u8*"] },
    []
);

/**
 * Parse Twitch URLs.
 * Matches: twitch.tv/{channel}, twitch.tv/videos/{id}, twitch.tv/{channel}/clip/{slug}
 */
function parseTwitchUrl(url) {
    // Clip: twitch.tv/{channel}/clip/{slug} or clips.twitch.tv/{slug}
    let m = url.match(/twitch\.tv\/\w+\/clip\/([A-Za-z0-9_-]+)/) || url.match(/clips\.twitch\.tv\/([A-Za-z0-9_-]+)/);
    if (m) return { type: "clip", slug: m[1] };

    // VOD: twitch.tv/videos/{id}
    m = url.match(/twitch\.tv\/videos\/(\d+)/);
    if (m) return { type: "vod", vodId: m[1] };

    // Channel: twitch.tv/{channel}
    m = url.match(/twitch\.tv\/([A-Za-z0-9_]{1,25})(?:[?#/]|$)/);
    if (m && !["directory", "videos", "settings", "subscriptions", "inventory", "drops", "wallet", "search"].includes(m[1].toLowerCase())) {
        return { type: "channel", login: m[1] };
    }

    return null;
}

/**
 * Capture Client-ID, OAuth token, and Device-ID from Twitch GQL requests.
 */
let _lastTwitchAuthState = "";
function captureTwitchHeaders(details) {
    let changed = false;
    for (const h of details.requestHeaders) {
        const name = h.name.toLowerCase();
        if (name === "authorization" && h.value?.startsWith("OAuth ")) {
            if (twitchAuthToken !== h.value) changed = true;
            twitchAuthToken = h.value;
        } else if (name === "x-device-id" && h.value) {
            if (twitchDeviceId !== h.value) changed = true;
            twitchDeviceId = h.value;
        }
    }
    const state = `auth=${!!twitchAuthToken},device=${!!twitchDeviceId}`;
    if (changed || state !== _lastTwitchAuthState) {
        _lastTwitchAuthState = state;
        log("TWITCH", `Headers captured`, { hasAuth: !!twitchAuthToken, hasDeviceId: !!twitchDeviceId });
    }
}

browser.webRequest.onSendHeaders.addListener(
    captureTwitchHeaders,
    { urls: ["*://gql.twitch.tv/*"], types: ["xmlhttprequest"] },
    ["requestHeaders"]
);

function buildTwitchGqlHeaders() {
    const headers = {
        "Client-ID": TWITCH_CLIENT_ID,
        "Content-Type": "application/json"
    };
    if (twitchAuthToken) headers["Authorization"] = twitchAuthToken;
    if (twitchDeviceId) headers["X-Device-Id"] = twitchDeviceId;
    return headers;
}

/**
 * Fetch live stream metadata via GQL and store in rendezvous.
 * The actual M3U8 URL comes from the CDN listener (the browser's player).
 * Falls back to self-built usher URL if no CDN capture arrives within timeout.
 */
async function fetchTwitchLiveStream(details, login) {
    const key = `twitch-live-${login}`;
    if (processedTwitchUrls.has(key)) { log("TWITCH", `Already processing ${login}, skipping`); return; }
    processedTwitchUrls.add(key);
    setTimeout(() => processedTwitchUrls.delete(key), 30_000);

    await ensureTabId(details);
    const loginLower = login.toLowerCase();
    log("TWITCH", `Fetching live stream metadata`, { login });

    try {
        const headers = buildTwitchGqlHeaders();

        const resp = await fetch("https://gql.twitch.tv/gql", {
            method: "POST",
            headers,
            body: JSON.stringify([{
                operationName: "StreamMetadata",
                query: `query StreamMetadata($channelLogin: String!) {
                    user(login: $channelLogin) {
                        displayName
                        login
                        profileImageURL(width: 300)
                        stream {
                            title
                            previewImageURL(width: 1280, height: 720)
                            game { displayName }
                        }
                    }
                }`,
                variables: { channelLogin: login }
            }, {
                operationName: "PlaybackAccessToken_Template",
                query: `query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!) {
                    streamPlaybackAccessToken(channelName: $login, params: {platform: "web", playerBackend: "mediaplayer", playerType: $playerType}) @include(if: $isLive) { value signature __typename }
                    videoPlaybackAccessToken(id: $vodID, params: {platform: "web", playerBackend: "mediaplayer", playerType: $playerType}) @include(if: $isVod) { value signature __typename }
                }`,
                variables: { isLive: true, login, isVod: false, vodID: "", playerType: "site" }
            }])
        });

        const results = tryParseJson(await resp.text());
        if (!results || !Array.isArray(results)) {
            log("TWITCH", `Parse failed or not array`);
            return;
        }

        const userData = results[0]?.data?.user;
        if (!userData?.stream) {
            log("TWITCH", `Channel offline`, { login });
            processedTwitchUrls.delete(key);
            return;
        }

        const stream = userData.stream;
        const displayName = userData.displayName || login;
        const title = stream.title || login;
        const gameName = stream.game?.displayName || "";
        const previewUrl = stream.previewImageURL || null;
        const profileImg = userData.profileImageURL || null;

        const entry = getTwitchRendezvous(loginLower);
        entry.metadata = {
            origin: `https://www.twitch.tv/${login}`,
            name: displayName,
            description: gameName ? `${title} — ${gameName}` : title,
            img: previewUrl || profileImg,
            duration: 0
        };
        entry.details = details;

        log("TWITCH", `Metadata stored in rendezvous`, { login: loginLower, hasM3u8: !!entry.m3u8Url });
        tryCompleteTwitchRendezvous(loginLower);

        // Fallback: if CDN capture doesn't arrive within 10s, use self-built usher URL
        setTimeout(() => {
            const pending = twitchRendezvous.get(loginLower);
            if (pending && pending.metadata && !pending.m3u8Url) {
                log("TWITCH", `CDN capture timeout — using fallback usher URL`, { login });

                const accessToken = results[1]?.data?.streamPlaybackAccessToken;
                if (!accessToken?.value || !accessToken?.signature) {
                    log("TWITCH", `No access token for fallback`);
                    twitchRendezvous.delete(loginLower);
                    return;
                }

                const hlsUrl = `https://usher.ttvnw.net/api/channel/hls/${login}.m3u8`
                    + `?sig=${accessToken.signature}`
                    + `&token=${encodeURIComponent(accessToken.value)}`
                    + `&allow_source=true&allow_audio_only=true`
                    + `&p=${Math.floor(Math.random() * 999999)}`;

                pending.m3u8Url = hlsUrl;
                tryCompleteTwitchRendezvous(loginLower);
            }
        }, 10_000);

    } catch (e) {
        log("TWITCH", `Live error`, e.message);
    }
}

/**
 * Fetch VOD metadata via GQL and store in rendezvous.
 * Actual M3U8 URL comes from CDN listener; falls back to usher URL on timeout.
 */
async function fetchTwitchVod(details, vodId) {
    const key = `twitch-vod-${vodId}`;
    if (processedTwitchUrls.has(key)) return;
    processedTwitchUrls.add(key);
    setTimeout(() => processedTwitchUrls.delete(key), 30_000);

    await ensureTabId(details);
    const rvKey = `vod-${vodId}`;
    log("TWITCH", `Fetching VOD metadata`, { vodId });

    try {
        const headers = buildTwitchGqlHeaders();

        const resp = await fetch("https://gql.twitch.tv/gql", {
            method: "POST",
            headers,
            body: JSON.stringify([{
                operationName: "VideoMetadata",
                query: `query VideoMetadata($videoID: ID!) {
                    video(id: $videoID) {
                        title
                        lengthSeconds
                        previewThumbnailURL(width: 1280, height: 720)
                        owner { displayName login profileImageURL(width: 300) }
                        game { displayName }
                    }
                }`,
                variables: { videoID: vodId }
            }, {
                operationName: "PlaybackAccessToken_Template",
                query: `query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!) {
                    streamPlaybackAccessToken(channelName: $login, params: {platform: "web", playerBackend: "mediaplayer", playerType: $playerType}) @include(if: $isLive) { value signature __typename }
                    videoPlaybackAccessToken(id: $vodID, params: {platform: "web", playerBackend: "mediaplayer", playerType: $playerType}) @include(if: $isVod) { value signature __typename }
                }`,
                variables: { isLive: false, login: "", isVod: true, vodID: vodId, playerType: "site" }
            }])
        });

        const results = tryParseJson(await resp.text());
        if (!Array.isArray(results) || results.length < 2) return;

        const videoData = results[0]?.data?.video;
        const owner = videoData?.owner;
        const vodName = owner?.displayName || owner?.login || "Twitch VOD";
        const title = videoData?.title || `VOD ${vodId}`;
        const gameName = videoData?.game?.displayName || "";
        const previewUrl = videoData?.previewThumbnailURL
            ? videoData.previewThumbnailURL.replace("{width}", "1280").replace("{height}", "720")
            : null;
        const duration = videoData?.lengthSeconds ? videoData.lengthSeconds * 1000 : 0;

        const entry = getTwitchRendezvous(rvKey);
        entry.metadata = {
            origin: `https://www.twitch.tv/videos/${vodId}`,
            name: vodName,
            description: gameName ? `${title} — ${gameName}` : title,
            img: previewUrl,
            duration
        };
        entry.details = details;

        log("TWITCH", `VOD metadata stored in rendezvous`, { vodId, hasM3u8: !!entry.m3u8Url });
        tryCompleteTwitchRendezvous(rvKey);

        // Fallback
        setTimeout(() => {
            const pending = twitchRendezvous.get(rvKey);
            if (pending && pending.metadata && !pending.m3u8Url) {
                log("TWITCH", `VOD CDN capture timeout — using fallback usher URL`, { vodId });

                const accessToken = results[1]?.data?.videoPlaybackAccessToken;
                if (!accessToken?.value || !accessToken?.signature) {
                    twitchRendezvous.delete(rvKey);
                    return;
                }

                const hlsUrl = `https://usher.ttvnw.net/vod/${vodId}.m3u8`
                    + `?sig=${accessToken.signature}`
                    + `&token=${encodeURIComponent(accessToken.value)}`
                    + `&allow_source=true`
                    + `&p=${Math.floor(Math.random() * 999999)}`;

                pending.m3u8Url = hlsUrl;
                tryCompleteTwitchRendezvous(rvKey);
            }
        }, 10_000);

    } catch (e) {
        log("TWITCH", `VOD error`, e.message);
    }
}

/**
 * Fetch clip — clips are MP4, not HLS. Can send as variants.
 */
async function fetchTwitchClip(details, slug) {
    const key = `twitch-clip-${slug}`;
    if (processedTwitchUrls.has(key)) return;
    processedTwitchUrls.add(key);
    setTimeout(() => processedTwitchUrls.delete(key), 10_000);

    await ensureTabId(details);
    log("TWITCH", `Fetching clip`, { slug });

    try {
        const headers = buildTwitchGqlHeaders();

        const resp = await fetch("https://gql.twitch.tv/gql", {
            method: "POST",
            headers,
            body: JSON.stringify({
                operationName: "VideoAccessToken_Clip",
                query: `query VideoAccessToken_Clip($slug: ID!) {
                    clip(slug: $slug) {
                        playbackAccessToken(params: {platform: "web", playerType: "site"}) { value signature __typename }
                        videoQualities { sourceURL quality frameRate }
                        broadcaster { displayName login }
                        title
                        thumbnailURL
                        durationSeconds
                    }
                }`,
                variables: { slug }
            })
        });

        const results = tryParseJson(await resp.text());
        const clipData = results?.data?.clip;
        if (!clipData) { log("TWITCH", `No clip data`, { slug }); return; }

        const token = clipData.playbackAccessToken;
        const qualities = clipData.videoQualities;
        if (!token || !Array.isArray(qualities) || qualities.length === 0) return;

        // Clips are direct MP4 URLs with quality variants
        // Compute width from 16:9 ratio (standard Twitch aspect ratio)
        const variants = qualities.map(q => {
            const url = `${q.sourceURL}?sig=${token.signature}&token=${encodeURIComponent(token.value)}`;
            const height = parseInt(q.quality) || 0;
            const width = height > 0 ? Math.round(height * 16 / 9) : 0;
            return { url, width, height };
        });

        const origin = `https://clips.twitch.tv/${slug}`;
        const broadcaster = clipData.broadcaster;
        const title = clipData.title || "";
        const thumbnailUrl = clipData.thumbnailURL || null;
        const duration = Math.round((clipData.durationSeconds || 0) * 1000);

        sendVariants(details, {
            variants,
            origin,
            name: broadcaster?.displayName || broadcaster?.login || "Twitch Clip",
            description: title,
            img: thumbnailUrl,
            duration
        });
        log("TWITCH", `Sent clip`, { slug, qualities: qualities.length });
    } catch (e) {
        log("TWITCH", `Clip error`, e.message);
    }
}

function listenerTwitchPage(details) {
    log("TWITCH", `Page request intercepted`, { url: details.url, type: details.type, tabId: details.tabId });
    if (details.type !== "main_frame") return;
    const parsed = parseTwitchUrl(details.url);
    log("TWITCH", `URL parsed`, parsed || "no match");
    if (!parsed) return;

    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);

    if (parsed.type === "channel") {
        fetchTwitchLiveStream(details, parsed.login);
    } else if (parsed.type === "vod") {
        fetchTwitchVod(details, parsed.vodId);
    } else if (parsed.type === "clip") {
        fetchTwitchClip(details, parsed.slug);
    }
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitchPage,
    { urls: ["*://www.twitch.tv/*", "*://m.twitch.tv/*", "*://clips.twitch.tv/*"], types: ["main_frame"] },
    []
);

// ============================================================================
// Dailymotion
// ============================================================================

const processedDailymotionUrls = new Set();

/**
 * Parse Dailymotion page URLs.
 * Matches: dailymotion.com/video/{id}, dai.ly/{id}
 */
function parseDailymotionUrl(url) {
    let m = url.match(/dailymotion\.com\/video\/([A-Za-z0-9]+)/);
    if (m) return { videoId: m[1] };

    m = url.match(/dai\.ly\/([A-Za-z0-9]+)/);
    if (m) return { videoId: m[1] };

    return null;
}

/**
 * Intercept geo.dailymotion.com JSON responses to extract stream URLs and metadata.
 * These requests are made by the Dailymotion player to fetch video configuration.
 *
 * URL patterns:
 *   https://geo.dailymotion.com/video/{id}.json?...
 */
function listenerDailymotionGeoApi(details) {
    if (isOwnRequest(details.url)) return {};

    // Extract video ID from the geo API URL
    const match = details.url.match(/\/video\/([A-Za-z0-9]+)\.json/);
    if (!match) return {};

    const videoId = match[1];
    const key = `dm-geo-${videoId}`;
    if (processedDailymotionUrls.has(key)) return {};
    processedDailymotionUrls.add(key);
    setTimeout(() => processedDailymotionUrls.delete(key), 10_000);

    log("DAILYMOTION", `Intercepted geo API request`, { videoId, url: details.url.slice(0, 120) });

    // Use filterResponseData to read the response inline (same pattern as Instagram)
    let filter;
    try {
        filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (e) {
        log("DAILYMOTION", `Failed to create filter`, { error: e.message });
        // Fallback: re-fetch
        fetchDailymotionGeoApi(details, videoId);
        return {};
    }

    const chunks = [];

    filter.ondata = (event) => {
        chunks.push(new Uint8Array(event.data));
        filter.write(event.data);
    };

    filter.onstop = () => {
        filter.close();

        const total = chunks.reduce((acc, c) => acc + c.byteLength, 0);
        if (total === 0) return;

        const combined = new Uint8Array(total);
        let offset = 0;
        for (const chunk of chunks) {
            combined.set(chunk, offset);
            offset += chunk.byteLength;
        }

        const str = new TextDecoder("utf-8").decode(combined);
        const parsed = tryParseJson(str);
        if (!parsed) {
            log("DAILYMOTION", `JSON parse failed`, { firstChars: str.slice(0, 80) });
            return;
        }

        processDailymotionData(details, parsed, videoId);
    };

    filter.onerror = () => {
        filter.close();
        log("DAILYMOTION", `Filter error, falling back to re-fetch`, { videoId });
        processedDailymotionUrls.delete(key);
        fetchDailymotionGeoApi(details, videoId);
    };

    return {};
}

/**
 * Fallback: re-fetch the geo API JSON if filterResponseData is unavailable.
 */
async function fetchDailymotionGeoApi(details, videoId) {
    const key = `dm-fetch-${videoId}`;
    if (processedDailymotionUrls.has(key)) return;
    processedDailymotionUrls.add(key);
    setTimeout(() => processedDailymotionUrls.delete(key), 10_000);

    await ensureTabId(details);
    log("DAILYMOTION", `Fetching geo API`, { videoId });

    try {
        const apiUrl = `https://geo.dailymotion.com/video/${videoId}.json?legacy=true&geo=1`;
        markOwnRequest(apiUrl);
        const resp = await fetch(apiUrl, {
            credentials: "include",
            headers: { "Accept": "application/json" }
        });
        if (!resp.ok) {
            log("DAILYMOTION", `Geo API fetch failed`, { status: resp.status });
            return;
        }

        const data = tryParseJson(await resp.text());
        if (!data) return;

        processDailymotionData(details, data, videoId);
    } catch (e) {
        log("DAILYMOTION", `Geo API fetch error`, e.message);
    }
}

/**
 * Process Dailymotion video JSON data and send to native.
 * The JSON contains qualities.auto[] with HLS URLs, plus metadata.
 */
function processDailymotionData(details, data, videoId) {
    const origin = `https://www.dailymotion.com/video/${videoId}`;
    if (alreadySent(origin)) {
        log("DAILYMOTION", `Already sent`, { videoId });
        return;
    }

    // Extract HLS URL from qualities.auto
    let hlsUrl = null;
    if (data.qualities?.auto) {
        for (const entry of data.qualities.auto) {
            if (entry.type === "application/x-mpegURL" && entry.url) {
                hlsUrl = entry.url;
                break;
            }
        }
    }

    if (!hlsUrl) {
        log("DAILYMOTION", `No HLS URL found`, { videoId, qualities: Object.keys(data.qualities || {}) });
        return;
    }

    markSent(origin);

    const title = data.title || "";
    const name = title.length > 40 ? title.slice(0, 40).replace(/\s+\S*$/, "") : title;
    const duration = data.duration ? data.duration * 1000 : 0;

    // Pick best thumbnail
    let img = null;
    if (data.thumbnails) {
        const sizes = ["1080", "720", "480", "360", "240", "180", "120", "60"];
        for (const size of sizes) {
            if (data.thumbnails[size]) { img = data.thumbnails[size]; break; }
        }
    }

    const tabId = details.tabId >= 0 ? details.tabId : (details._resolvedTabId ?? -1);

    const message = {
        url: hlsUrl,
        type: "media",
        origin,
        tabId,
        request: details.requestId || `dm-${Date.now()}`,
        name,
        description: title,
    };

    if (img) message.img = img;
    if (duration > 0) message.duration = duration;

    log("DAILYMOTION", `Sending video`, { videoId, name, hasImg: !!img });
    sendNative(message);
}

/**
 * Page navigation listener — triggers fetch when user navigates to a Dailymotion video page.
 */
function listenerDailymotionPage(details) {
    if (details.type !== "main_frame") return;
    const parsed = parseDailymotionUrl(details.url);
    if (!parsed) return;

    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);

    log("DAILYMOTION", `Page navigation detected`, { videoId: parsed.videoId });
    fetchDailymotionGeoApi(details, parsed.videoId);
}

function checkAndProcessDailymotionUrl(url, tabId) {
    if (!url || !url.includes("dailymotion.com")) return;
    const parsed = parseDailymotionUrl(url);
    if (!parsed) return;

    log("DAILYMOTION", `SPA/tab navigation detected`, { videoId: parsed.videoId, url: url.slice(0, 80), tabId });
    const details = { tabId, url, _resolvedTabId: tabId, requestId: `tab-${tabId}-${Date.now()}` };
    fetchDailymotionGeoApi(details, parsed.videoId);
}

// Intercept geo API responses (filterResponseData to read inline)
browser.webRequest.onBeforeRequest.addListener(
    listenerDailymotionGeoApi,
    { urls: ["*://geo.dailymotion.com/video/*.json*"], types: ["xmlhttprequest"] },
    ["blocking"]
);

// Page navigations (main_frame)
browser.webRequest.onBeforeRequest.addListener(
    listenerDailymotionPage,
    { urls: [
        "*://www.dailymotion.com/video/*",
        "*://dailymotion.com/video/*"
    ], types: ["main_frame"] },
    []
);

// ============================================================================
// Instagram — helpers
// ============================================================================

const INSTAGRAM_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
    "X-Requested-With": "XMLHttpRequest",
    "Origin": "https://www.instagram.com",
    "Referer": "https://www.instagram.com",
    "Accept-Language": "en-us,en;q=0.5",
    "Accept-Encoding": "gzip, deflate, br",
    "Accept": "*/*"
};

function getInstagramThumbnail(item) {
    // API v1 shape: image_versions2.candidates[] sorted by width
    const candidates = item?.image_versions2?.candidates;
    if (Array.isArray(candidates) && candidates.length > 0) {
        return [...candidates].sort((a, b) => (b.width || 0) - (a.width || 0))[0].url;
    }

    // GraphQL shapes
    if (item?.display_url) return item.display_url;
    if (item?.thumbnail_src) return item.thumbnail_src;
    if (item?.thumbnail_url) return item.thumbnail_url;

    // Clips/reels cover frame
    if (item?.media_cropping_info?.thumbnails?.[0]?.url) return item.media_cropping_info.thumbnails[0].url;

    // Profile pic of the owner as last resort for stories
    if (item?.user?.profile_pic_url) return item.user.profile_pic_url;

    return null;
}

async function getInstagramCookies() {
    try {
        const cached = await browser.storage.local.get(COOKIE_CACHE_KEY);
        if (cached[COOKIE_CACHE_KEY]) {
            const { value, timestamp } = cached[COOKIE_CACHE_KEY];
            if (Date.now() - timestamp < COOKIE_CACHE_TTL) return value;
        }

        const cookies = await browser.cookies.getAll({ domain: ".instagram.com" });
        if (cookies.length === 0) return null;

        const cookieString = cookies.map(c => `${c.name}=${c.value}`).join("; ");
        await browser.storage.local.set({
            [COOKIE_CACHE_KEY]: { value: cookieString, timestamp: Date.now() }
        });

        log("INSTAGRAM", `Cached ${cookies.length} cookies`);
        return cookieString;
    } catch (e) {
        log("INSTAGRAM", `Failed to get cookies`, e.message);
        return null;
    }
}

async function fetchInstagramGraphQL(shortcode, cookieString) {
    const graphqlUrl = `https://www.instagram.com/graphql/query/?doc_id=8845758582119845&variables=${encodeURIComponent(JSON.stringify({ shortcode }))}`;
    markOwnRequest(graphqlUrl);

    const response = await fetch(graphqlUrl, {
        method: "GET",
        headers: new Headers({ ...INSTAGRAM_HEADERS, "Cookie": cookieString })
    });

    return tryParseJson(await response.text());
}

// ============================================================================
// Instagram — video extraction from different response shapes
// ============================================================================

/**
 * Extract and send videos from an Instagram media API item.
 */
function sendInstagramItem(details, item, originOverride) {
    const videoText = item.caption?.text || "";
    const author = item.user?.username || null;
    const code = item.code;
    const origin = originOverride || `https://www.instagram.com/p/${code}`;
    const img = getInstagramThumbnail(item);
    const duration = Math.round((item.video_duration || 0) * 1000);

    log("IG-ITEM", `sendInstagramItem called`, {
        code,
        author,
        origin,
        mediaType: item.media_type,
        hasVideoVersions: !!item.video_versions,
        videoVersionsCount: item.video_versions?.length || 0,
        hasCarousel: !!item.carousel_media,
        carouselCount: item.carousel_media?.length || 0,
        hasImg: !!img,
        imgSource: img ? (
            item?.image_versions2?.candidates?.length ? "image_versions2" :
            item?.display_url ? "display_url" :
            item?.thumbnail_src ? "thumbnail_src" :
            item?.thumbnail_url ? "thumbnail_url" :
            "other"
        ) : "none",
        duration
    });

    if (item.video_versions) {
        const variants = item.video_versions.map(v => ({
            url: v.url, width: v.width || 0, height: v.height || 0
        }));
        log("IG-ITEM", `Sending ${variants.length} video variant(s)`, { code, firstUrl: variants[0]?.url?.slice(0, 80) });
        sendVariants(details, { variants, origin, description: videoText, img, name: author, duration });
    }

    if (item.carousel_media) {
        let carouselVideos = 0;
        for (const media of item.carousel_media) {
            if (!media.video_versions) continue;
            carouselVideos++;
            const variants = media.video_versions.map(v => ({
                url: v.url, width: v.width || 0, height: v.height || 0
            }));
            const mediaImg = getInstagramThumbnail(media) || img;
            const mediaDuration = Math.round((media.video_duration || 0) * 1000);
            sendVariants(details, { variants, origin, description: videoText, img: mediaImg, name: author, duration: mediaDuration });
        }
        log("IG-ITEM", `Carousel: ${carouselVideos} video(s) in ${item.carousel_media.length} slides`, { code });
    }

    if (!item.video_versions && !item.carousel_media) {
        log("IG-ITEM", `Item has no video_versions and no carousel_media — nothing to send`, { code, mediaType: item.media_type });
    }
}

/**
 * Extract and send videos from an Instagram GraphQL response.
 */
function parseInstagramQuery(details, parsed) {
    const shortcodeMedia = parsed.data?.xdt_shortcode_media;
    if (shortcodeMedia) {
        const code = shortcodeMedia.shortcode;
        const origin = `https://www.instagram.com/p/${code}`;
        const text = shortcodeMedia.edge_media_to_caption?.edges?.[0]?.node?.text || "";
        const img = shortcodeMedia.display_url || shortcodeMedia.thumbnail_src || null;
        const duration = Math.round((shortcodeMedia.video_duration || 0) * 1000);
        const author = shortcodeMedia.owner?.username || null;

        if (shortcodeMedia.__typename === "XDTGraphSidecar") {
            for (const { node } of (shortcodeMedia.edge_sidecar_to_children?.edges || [])) {
                if (node?.__typename !== "XDTGraphVideo") continue;
                const nodeImg = node.display_url || img;
                const nodeDuration = Math.round((node.video_duration || 0) * 1000);
                sendVariants(details, {
                    variants: [{ url: node.video_url, width: node.dimensions?.width || 0, height: node.dimensions?.height || 0 }],
                    origin, description: text, img: nodeImg, name: author, duration: nodeDuration
                });
            }
        } else if (shortcodeMedia.video_url) {
            sendVariants(details, {
                variants: [{ url: shortcodeMedia.video_url, width: shortcodeMedia.dimensions?.width || 0, height: shortcodeMedia.dimensions?.height || 0 }],
                origin, description: text, img, name: author, duration
            });
        }
        return;
    }

    const timeline = parsed.data?.user?.edge_owner_to_timeline_media;
    if (timeline?.edges) {
        for (const { node } of timeline.edges) {
            if (node?.__typename !== "GraphVideo" || !node.video_url) continue;
            const code = node.shortcode;
            const origin = `https://www.instagram.com/p/${code}`;
            const text = node.edge_media_to_caption?.edges?.[0]?.node?.text || "";
            const nodeImg = node.display_url || node.thumbnail_src || null;
            const nodeDuration = Math.round((node.video_duration || 0) * 1000);
            const author = node.owner?.username || null;
            sendVariants(details, {
                variants: [{ url: node.video_url, width: node.dimensions?.width || 0, height: node.dimensions?.height || 0 }],
                origin, description: text, img: nodeImg, name: author, duration: nodeDuration
            });
        }
    }
}

// ============================================================================
// Instagram — fetch strategies
// ============================================================================

const pendingShortcodes = new Set();

/**
 * Primary entry point for fetching Instagram content by shortcode.
 * Uses GraphQL directly (media API needs numeric ID, not shortcode).
 */
async function fetchInstagramByShortcode(details, shortcode) {
    if (pendingShortcodes.has(shortcode)) {
        log("INSTAGRAM", `Already fetching shortcode, skipping`, { shortcode });
        return;
    }

    pendingShortcodes.add(shortcode);
    log("INSTAGRAM", `Fetching by shortcode`, { shortcode });

    await ensureTabId(details);
    const cookieString = await getInstagramCookies();

    if (!cookieString) {
        details.shortcode = shortcode;
        addToInstagramQueue(details);
        pendingShortcodes.delete(shortcode);
        return;
    }

    try {
        const parsed = await fetchInstagramGraphQL(shortcode, cookieString);
        if (parsed) {
            parseInstagramQuery(details, parsed);
        } else {
            log("INSTAGRAM", `GraphQL response failed to parse`);
        }
    } catch (e) {
        log("INSTAGRAM", `Shortcode fetch error`, e.message);
    } finally {
        pendingShortcodes.delete(shortcode);
        log("INSTAGRAM", `Finished processing shortcode`, { shortcode });
    }
}

/**
 * Fetch Instagram content by numeric media ID.
 * Falls back to GraphQL via shortcode if media API fails.
 */
async function fetchInstagramByMediaId(details, mediaId, shortcode) {
    log("INSTAGRAM", `Fetching media info`, { mediaId, shortcode });

    await ensureTabId(details);
    const cookieString = await getInstagramCookies();

    if (!cookieString) {
        details.shortcode = shortcode || mediaId;
        addToInstagramQueue(details);
        return;
    }

    const headers = new Headers({ ...INSTAGRAM_HEADERS, "Cookie": cookieString });

    try {
        const mediaUrl = `https://www.instagram.com/api/v1/media/${mediaId}/info/`;
        markOwnRequest(mediaUrl);

        const response = await fetch(mediaUrl, { method: "GET", headers });
        const parsed = tryParseJson(await response.text());

        if (parsed?.items?.[0]) {
            sendInstagramItem(details, parsed.items[0]);
            return;
        }

        log("INSTAGRAM", `Media API returned no items, trying GraphQL fallback`);

        if (shortcode) {
            const graphqlParsed = await fetchInstagramGraphQL(shortcode, cookieString);
            if (graphqlParsed) {
                parseInstagramQuery(details, graphqlParsed);
            }
        }
    } catch (e) {
        log("INSTAGRAM", `Media fetch error`, e.message);
    }
}

// ============================================================================
// Instagram — webRequest listeners (filterResponseData + content script fallback)
// ============================================================================

/**
 * Primary strategy: use filterResponseData to intercept API responses inline.
 * This reads the response as it streams through without replaying the request.
 * Falls back to content script injection if filter fails.
 */

const IG_API_PATTERNS = [
    "*://www.instagram.com/graphql/*",
    "*://www.instagram.com/api/graphql",
    "*://www.instagram.com/api/graphql?*",
    "*://www.instagram.com/api/graphql/*",
    "*://www.instagram.com/api/v1/media/*/info/",
    "*://www.instagram.com/api/v1/feed/*",
    "*://www.instagram.com/api/v1/clips/*",
    "*://www.instagram.com/api/v1/discover/*",
    "*://www.instagram.com/api/v1/reels/*"
];

function listenerInstagramApiFilter(details) {
    const url = details.url;

    if (isOwnRequest(url)) return {};

    log("IG-FILTER", `>>> onBeforeRequest fired`, {
        url: url.slice(0, 120),
        requestId: details.requestId,
        tabId: details.tabId,
        type: details.type
    });

    // Create the filter SYNCHRONOUSLY — before any async work
    let filter;
    try {
        filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (e) {
        log("IG-FILTER", `Failed to create filter`, { error: e.message, requestId: details.requestId });
        return {};
    }

    log("IG-FILTER", `Filter created for requestId ${details.requestId}`);

    const chunks = [];

    filter.ondata = (event) => {
        chunks.push(new Uint8Array(event.data));
        filter.write(event.data);  // Pass through unmodified
    };

    filter.onstop = () => {
        filter.close();

        const total = chunks.reduce((acc, c) => acc + c.byteLength, 0);
        log("IG-FILTER", `Response complete`, {
            requestId: details.requestId,
            totalBytes: total,
            chunks: chunks.length,
            url: url.slice(0, 80)
        });

        if (total === 0) {
            log("IG-FILTER", `Empty response, skipping`);
            return;
        }

        const combined = new Uint8Array(total);
        let offset = 0;
        for (const chunk of chunks) {
            combined.set(chunk, offset);
            offset += chunk.byteLength;
        }

        let str = new TextDecoder("utf-8").decode(combined);

        // Strip Instagram's "for (;;);" anti-hijacking prefix
        if (str.startsWith("for (;;);")) {
            str = str.slice(9);
            log("IG-FILTER", `Stripped anti-hijacking prefix`);
        }

        log("IG-FILTER", `Decoded body`, {
            length: str.length,
            preview: str.slice(0, 150),
            requestId: details.requestId
        });

        const parsed = tryParseJson(str);
        if (!parsed) {
            log("IG-FILTER", `JSON parse failed`, { firstChars: str.slice(0, 80) });
            return;
        }

        const topKeys = Object.keys(parsed);
        log("IG-FILTER", `Parsed OK`, { keys: topKeys.join(", "), url: url.slice(0, 80) });

        // Process in a microtask to avoid blocking
        Promise.resolve().then(() => processFilteredInstagramResponse(details, url, parsed));
    };

    filter.onerror = () => {
        log("IG-FILTER", `Filter error`, { error: filter.error, requestId: details.requestId });
        try { filter.close(); } catch (e) {}
    };

    return {};
}

function processFilteredInstagramResponse(details, url, parsed) {
    // Ensure we have a tabId
    ensureTabId(details);

    if (url.includes("graphql")) {
        log("IG-FILTER", `Routing: graphql`, {
            dataKeys: parsed.data ? Object.keys(parsed.data).join(", ") : "none",
            hasShortcodeMedia: !!parsed.data?.xdt_shortcode_media,
            hasTimeline: !!parsed.data?.user?.edge_owner_to_timeline_media,
            hasPrefetch: !!parsed.extensions?.all_video_dash_prefetch_representations
        });

        // Shortcode media or timeline (old GraphQL shape)
        if (parsed.data?.xdt_shortcode_media || parsed.data?.user?.edge_owner_to_timeline_media) {
            parseInstagramQuery(details, parsed);
            return;
        }

        // Scan all data keys for feed items (xdt_api__v1__feed, clips, etc.)
        // Skip prefetch — the actual video_versions are already in the feed edges
        if (parsed.data) {
            let found = 0;
            for (const [key, value] of Object.entries(parsed.data)) {
                if (!value || typeof value !== "object") continue;

                // Connection edges
                if (value.edges && Array.isArray(value.edges)) {
                    for (const edge of value.edges) {
                        // Unwrap the media from various nesting patterns:
                        // - Profile timeline: edge.node directly IS the media
                        // - Home timeline: edge.node.media, edge.node.explore_story.media
                        // - Stories/reels: edge.node is a reel container with .items[]
                        const candidates = [
                            edge.node?.media,
                            edge.node?.explore_story?.media,
                            edge.node
                        ];

                        for (const node of candidates) {
                            if (!node) continue;

                            // Direct video on the node
                            if (node.video_versions || node.media_type === 2 || node.video_url || node.carousel_media) {
                                sendInstagramItem(details, node);
                                found++;
                                break; // Don't double-count the same edge
                            }
                        }

                        // Stories/reels: edge.node.items[] contains the actual media
                        const reelNode = edge.node;
                        if (reelNode && Array.isArray(reelNode.items)) {
                            for (const item of reelNode.items) {
                                if (item && (item.video_versions || item.media_type === 2 || item.carousel_media)) {
                                    sendInstagramItem(details, item);
                                    found++;
                                }
                            }
                        }
                    }
                }

                // Direct items array
                if (value.items && Array.isArray(value.items)) {
                    for (const item of value.items) {
                        if (item && (item.video_versions || item.media_type === 2 || item.carousel_media)) {
                            sendInstagramItem(details, item);
                            found++;
                        }
                    }
                }

                // Single media wrapper
                if (value.media && typeof value.media === "object" && !Array.isArray(value.media)) {
                    const m = value.media;
                    if (m.video_versions || m.media_type === 2 || m.video_url) {
                        sendInstagramItem(details, m);
                        found++;
                    }
                }

                // Direct video object
                if (value.video_versions || (value.media_type === 2 && value.code)) {
                    sendInstagramItem(details, value);
                    found++;
                }
            }
            if (found > 0) {
                log("IG-FILTER", `GraphQL feed: sent ${found} video(s)`, { url: url.slice(0, 80) });
            }
        }
    } else if (url.includes("/api/v1/media") && url.includes("/info")) {
        const item = parsed?.items?.[0];
        if (item?.video_versions || item?.carousel_media) {
            sendInstagramItem(details, item);
            log("IG-FILTER", `Media info: sent`, { code: item.code });
        }
    } else if (url.includes("/api/v1/")) {
        // Feed endpoints
        processInstagramFeedItems(details, parsed, url);
    }
}

function processInstagramFeedItems(details, parsed, url) {
    let found = 0;

    if (Array.isArray(parsed.items)) {
        for (const item of parsed.items) {
            if (item && (item.video_versions || item.media_type === 2 || item.carousel_media)) {
                sendInstagramItem(details, item);
                found++;
            }
        }
    }

    if (Array.isArray(parsed.feed_items)) {
        for (const fi of parsed.feed_items) {
            const item = fi.media_or_ad;
            if (item && (item.video_versions || item.media_type === 2 || item.carousel_media)) {
                sendInstagramItem(details, item);
                found++;
            }
        }
    }

    if (Array.isArray(parsed.reels_media)) {
        for (const reel of parsed.reels_media) {
            if (Array.isArray(reel.items)) {
                for (const item of reel.items) {
                    if (item && (item.video_versions || item.media_type === 2)) {
                        sendInstagramItem(details, item);
                        found++;
                    }
                }
            }
        }
    }

    if (parsed.reels && typeof parsed.reels === "object" && !Array.isArray(parsed.reels)) {
        for (const reel of Object.values(parsed.reels)) {
            if (Array.isArray(reel.items)) {
                for (const item of reel.items) {
                    if (item && (item.video_versions || item.media_type === 2)) {
                        sendInstagramItem(details, item);
                        found++;
                    }
                }
            }
        }
    }

    if (Array.isArray(parsed.sectional_items)) {
        for (const section of parsed.sectional_items) {
            for (const m of (section.layout_content?.medias || [])) {
                if (m.media && (m.media.video_versions || m.media.media_type === 2 || m.media.carousel_media)) {
                    sendInstagramItem(details, m.media); found++;
                }
            }
            for (const m of (section.layout_content?.fill_items || [])) {
                if (m.media && (m.media.video_versions || m.media.media_type === 2 || m.media.carousel_media)) {
                    sendInstagramItem(details, m.media); found++;
                }
            }
        }
    }

    if (Array.isArray(parsed.media_info_list)) {
        for (const item of parsed.media_info_list) {
            if (item && (item.video_versions || item.media_type === 2)) {
                sendInstagramItem(details, item); found++;
            }
        }
    }

    if (found > 0) {
        log("IG-FILTER", `Feed: sent ${found} video(s)`, { url: url.slice(0, 80) });
    }
}

// Content script message handler (fallback if filterResponseData fails)
browser.runtime.onMessage.addListener((message, sender) => {
    if (message.type !== "instagram_intercept") return;

    const { payload } = message;
    if (!payload?.items?.length) return;

    const tabId = sender.tab?.id ?? -1;
    const details = {
        tabId,
        _resolvedTabId: tabId >= 0 ? tabId : undefined,
        url: payload.url || sender.tab?.url || "",
        requestId: `cs-${Date.now()}`
    };

    log("IG-CS", `Received ${payload.items.length} item(s) from content script`, {
        source: payload.source,
        url: payload.url?.slice(0, 80),
        tabId,
        types: payload.items.map(i => i.type).join(", ")
    });

    for (const item of payload.items) {
        try {
            processContentScriptItem(details, item);
        } catch (e) {
            log("IG-CS", `Error processing item`, { type: item.type, error: e.message });
        }
    }
});

function processContentScriptItem(details, item) {
    const { type, data } = item;
    if (!data) return;

    if (type === "shortcode_media") {
        parseInstagramQuery(details, { data: { xdt_shortcode_media: data } });
    } else if (type === "timeline_node") {
        if (data.video_url) {
            const code = data.shortcode;
            sendVariants(details, {
                variants: [{ url: data.video_url, width: data.dimensions?.width || 0, height: data.dimensions?.height || 0 }],
                origin: `https://www.instagram.com/p/${code}`,
                description: data.edge_media_to_caption?.edges?.[0]?.node?.text || "",
                img: data.display_url || null,
                name: data.owner?.username || null,
                duration: Math.round((data.video_duration || 0) * 1000)
            });
        }
    } else if (type === "prefetch" && data.video_id) {
        fetchInstagramByMediaId(details, data.video_id, null);
    } else if (data.video_versions || data.carousel_media || data.media_type === 2) {
        sendInstagramItem(details, data);
    }
}

// ---- Page navigation listener ----

function listenerInstagramPage(details) {
    if (details.type !== "main_frame") return;

    const url = details.url;
    if (details.tabId >= 0) cacheTabUrl(url, details.tabId);

    const match = url.match(/\/(?:reel|p)\/([A-Za-z0-9_-]+)/);
    if (match?.[1]) {
        log("IG-PAGE", `Shortcode found`, { shortcode: match[1] });
        fetchInstagramByShortcode(details, match[1]);
    }
}

// ---- Listener registrations ----

// filterResponseData on Instagram API — intercepts response inline
browser.webRequest.onBeforeRequest.addListener(
    listenerInstagramApiFilter,
    { urls: IG_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

// Page navigations (main_frame)
browser.webRequest.onBeforeRequest.addListener(
    listenerInstagramPage,
    { urls: [
        "*://www.instagram.com/reel/*",
        "*://www.instagram.com/p/*",
        "*://www.instagram.com/*/reel/*",
        "*://www.instagram.com/*/p/*"
    ], types: ["main_frame"] },
    []
);

// ============================================================================
// Instagram — cookie change handler (process queued requests)
// ============================================================================

browser.cookies.onChanged.addListener(async (changeInfo) => {
    if (!changeInfo.cookie.domain.includes("instagram.com")) return;

    await browser.storage.local.remove(COOKIE_CACHE_KEY);

    if (changeInfo.removed || instagramQueue.size === 0) return;

    log("COOKIES", `Processing ${instagramQueue.size} queued request(s)`);
    const cookieString = await getInstagramCookies();
    if (!cookieString) return;

    for (const [shortcode, queuedDetails] of instagramQueue) {
        if (!pendingShortcodes.has(shortcode)) {
            fetchInstagramByShortcode(queuedDetails, shortcode);
        } else {
            log("COOKIES", `Skipping queued ${shortcode}, already in flight`);
        }
    }
    instagramQueue.clear();
});

// ============================================================================
// Startup
// ============================================================================

async function handleExistingTabs() {
    try {
        const tabs = await browser.tabs.query({});
        for (const tab of tabs) {
            if (tab.url && tab.id >= 0) {
                cacheTabUrl(tab.url, tab.id);
                checkAndProcessInstagramUrl(tab.url, tab.id);
                checkAndProcessKickUrl(tab.url, tab.id);
                checkAndProcessTwitchUrl(tab.url, tab.id);
                checkAndProcessDailymotionUrl(tab.url, tab.id);
            }
        }
        log("INIT", `Cached ${urlToTabCache.size} URLs from ${tabs.length} existing tabs`);
    } catch (e) {
        log("INIT", `Error checking existing tabs`, e.message);
    }
}

function checkAndProcessInstagramUrl(url, tabId) {
    if (!url || !url.includes("instagram.com")) return;
    // Match both /reel/CODE, /p/CODE, and /username/reel/CODE (SPA navigation from profiles)
    const match = url.match(/instagram\.com\/(?:[A-Za-z0-9_.]+\/)?(?:reel|p)\/([A-Za-z0-9_-]+)/);
    if (match?.[1]) {
        log("IG-PAGE", `SPA navigation detected`, { shortcode: match[1], url: url.slice(0, 80), tabId });
        const details = { tabId, url, _resolvedTabId: tabId };
        fetchInstagramByShortcode(details, match[1]);
    }
}

function checkAndProcessKickUrl(url, tabId) {
    if (!url || !url.includes("kick.com")) return;
    const parsed = parseKickUrl(url);
    if (!parsed) return;

    log("KICK", `Tab URL detected`, { url: url.slice(0, 80), parsed });
    const details = { tabId, url, _resolvedTabId: tabId, requestId: `tab-${tabId}-${Date.now()}` };

    if (parsed.type === "channel") {
        fetchKickChannel(details, parsed.streamer);
    } else if (parsed.type === "clip" && parsed.clipId) {
        fetchKickClip(details, parsed.clipId);
    } else if (parsed.type === "video" && parsed.videoId) {
        fetchKickVideo(details, parsed.videoId);
    }
}

function checkAndProcessTwitchUrl(url, tabId) {
    if (!url || !url.includes("twitch.tv")) return;
    const parsed = parseTwitchUrl(url);
    if (!parsed) return;

    log("TWITCH", `Tab URL detected`, { url: url.slice(0, 80), parsed });
    const details = { tabId, url, _resolvedTabId: tabId, requestId: `tab-${tabId}-${Date.now()}` };

    if (parsed.type === "channel") {
        fetchTwitchLiveStream(details, parsed.login);
    } else if (parsed.type === "vod") {
        fetchTwitchVod(details, parsed.vodId);
    } else if (parsed.type === "clip") {
        fetchTwitchClip(details, parsed.slug);
    }
}

log("INIT", `Video parser extension loaded (Instagram, Twitter/X, Vimeo, Kick, Twitch, Dailymotion)`);
handleExistingTabs();