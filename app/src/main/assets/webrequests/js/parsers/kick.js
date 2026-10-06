// Kick parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, markOwnRequest, isOwnRequest, alreadySent, emitHlsMasterOrSingle, cacheTabUrl, resolveTabId, ensureTabId, registerSpaHandler, ClaimSet } from './common.js';

// ============================================================================
// Kick
// ============================================================================

const processedKickUrls = new ClaimSet(10_000, 256);

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
    if (!processedKickUrls.claim(key)) return;

    await ensureTabId(details);
    log("KICK", `Fetching channel`, { streamer });

    try {
        const apiUrl = `https://kick.com/api/v2/channels/${streamer}`;
        // Marked, so listenerKickApiComplete below doesn't take our own fetch
        // for the page's and re-fetch + re-process the channel a second time.
        markOwnRequest(apiUrl);
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
    if (!processedKickUrls.claim(key)) return;

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
        const tabId = await resolveTabId(details);
        if (alreadySent(origin, tabId)) return;

        const name = clip.channel?.username || clip.creator?.username || "Kick Clip";
        const title = clip.title || "";
        const img = clip.thumbnail_url || pickKickThumbnail(clip.thumbnail) || null;
        const duration = Math.round((clip.duration || 0) * 1000);

        // Enumerate the HLS master into quality variants (no ffprobe); falls back
        // to the single URL if it isn't a parseable master.
        await emitHlsMasterOrSingle(details, { url: videoUrl, origin, tabId, name, title, img, duration });
        log("KICK", `Sent clip`, { clipId, name });
    } catch (e) {
        log("KICK", `Clip error`, e.message);
    }
}

async function fetchKickVideo(details, videoId) {
    const key = `kick-video-${videoId}`;
    if (!processedKickUrls.claim(key)) return;

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
        const tabId = await resolveTabId(details);
        if (alreadySent(origin, tabId)) return;

        const name = data.livestream?.channel?.user?.username || "Kick VOD";
        const title = data.livestream?.session_title || "";
        const img = pickKickThumbnail(data.livestream?.thumbnail) || null;
        const duration = Math.round((data.livestream?.duration || 0));

        // Enumerate the HLS master into quality variants (no ffprobe); falls back
        // to the single URL if it isn't a parseable master.
        await emitHlsMasterOrSingle(details, { url: videoUrl, origin, tabId, name, title, img, duration });
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
    if (isOwnRequest(details.url)) return;   // fetchKickChannel's own request
    // Only process the channel endpoint itself
    const channelMatch = details.url.match(/\/api\/v2\/channels\/([A-Za-z0-9_-]+)\/?(?:\?|$)/);
    if (!channelMatch) return;

    const streamer = channelMatch[1];
    log("KICK", `Browser API call completed`, { streamer, status: details.statusCode });

    if (details.statusCode !== 200) return;

    // Re-fetch with credentials to get the JSON (browser already cleared Cloudflare)
    const key = `kick-api-${streamer}`;
    if (!processedKickUrls.claim(key)) return;

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

    const title = livestream?.session_title || data.user?.username || slug;
    const img = pickKickThumbnail(livestream?.thumbnail)
        || data.user?.profilepic || data.user?.profile_pic || null;
    const name = data.user?.username || slug;
    const category = livestream?.categories?.[0]?.name || "";

    // The live playback_url is an HLS master — enumerate it via M3U8Parser
    // (no ffprobe), same as Kick VODs/clips and twitch/niconico. The raw
    // type:"media" emit used to send it to the metadatareader probe.
    // emitHlsMasterOrSingle / enumerateMasterNative own the origin dedup, so
    // don't pre-mark here.
    emitHlsMasterOrSingle(details, {
        url: playbackUrl,
        origin,
        tabId: details.tabId >= 0 ? details.tabId : -1,
        name,
        title: category ? `${title} — ${category}` : title,
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

// Tab-URL / SPA-navigation trigger (was the hardcoded call in tabs.onUpdated).
registerSpaHandler(checkAndProcessKickUrl);
