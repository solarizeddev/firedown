// Twitch parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, isOwnRequest, sendVariants, enumerateMasterNative, cacheTabUrl, urlToTabCache, ensureTabId, registerSpaHandler, ClaimSet, MetaCache } from './common.js';

// ============================================================================
// Twitch
// ============================================================================

const TWITCH_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
const processedTwitchUrls = new ClaimSet(30_000, 256);
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
const twitchRendezvous = new MetaCache(64, TWITCH_RENDEZVOUS_TTL);   // TTL at lookup + FIFO cap

function getTwitchRendezvous(key) {
    let entry = twitchRendezvous.get(key);
    if (!entry) {
        entry = { m3u8Url: null, metadata: null, details: null, variants: null, bodyPending: false, bodyDone: false };
        twitchRendezvous.set(key, entry);
    }
    return entry;
}

function tryCompleteTwitchRendezvous(key) {
    const entry = twitchRendezvous.get(key);
    if (!entry || !entry.m3u8Url || !entry.metadata) return;

    const { m3u8Url, metadata, details } = entry;
    twitchRendezvous.delete(key);

    // Hand the master to native: Java OkHttp-fetches it and M3U8Parser enumerates
    // the qualities (no ffprobe), falling back to a media capture on failure.
    // enumerateMasterNative does its own origin dedup.
    log("TWITCH", `Rendezvous complete — enumerate master`, { key, url: m3u8Url.slice(0, 120) });
    enumerateMasterNative(details || { tabId: -1, requestId: `twitch-${Date.now()}` }, {
        url: m3u8Url,
        origin: metadata.origin,
        name: metadata.name,
        description: metadata.description,
        img: metadata.img,
        duration: metadata.duration
    });
}

/**
 * Resolve the Twitch channel login from a tab ID by looking up cached tab URLs.
 */
// Paths under twitch.tv/<x> that are NOT a channel login. One list for the
// URL parser and the tab resolver (they used to disagree).
const TWITCH_NON_CHANNEL = new Set(["directory", "videos", "settings", "subscriptions", "inventory",
    "drops", "wallet", "search", "clips", "p", "turbo", "downloads", "jobs", "store", "prime",
    "popout", "embed", "login", "signup", "u", "team", "event", "friends", "messages", "payments",
    "collections", "moderator", "dashboard"]);

// The tab's NEWEST cached URL wins. urlToTabCache is insertion-ordered and a
// set() on an existing key keeps its place, so after an SPA navigation from
// channel A to channel B (both cached for the 30 s TTL) the first match in
// iteration order was A — and B's master was filed under A's rendezvous.
function newestTabUrl(tabId, extract) {
    if (tabId < 0) return null;
    let best = null;
    let bestTs = -1;
    for (const [url, entry] of urlToTabCache) {
        if (entry.tabId !== tabId || entry.timestamp <= bestTs) continue;
        const v = extract(url);
        if (v) { best = v; bestTs = entry.timestamp; }
    }
    return best;
}

function resolveLoginFromTab(tabId) {
    return newestTabUrl(tabId, (url) => {
        const m = url.match(/twitch\.tv\/([A-Za-z0-9_]{1,25})(?:[?#/]|$)/);
        return (m && !TWITCH_NON_CHANNEL.has(m[1].toLowerCase())) ? m[1].toLowerCase() : null;
    });
}

function resolveVodIdFromTab(tabId) {
    return newestTabUrl(tabId, (url) => {
        const m = url.match(/twitch\.tv\/videos\/(\d+)/);
        return m ? m[1] : null;
    });
}

// Only usher serves a MASTER. The variant playlists the player refreshes
// every ~2 s (video-weaver.*.hls.ttvnw.net) match the same host pattern, and
// each one used to re-seed a just-completed rendezvous with a MEDIA playlist
// as its "master": a refresh within the window completed it with that URL,
// Java found no STREAM-INF, and the capture degraded to a probed single
// rendition. (A master the CDN moved off usher would still be covered by the
// self-built usher fallback on the metadata side.)
const TWITCH_MASTER_RE = /^https?:\/\/usher\.ttvnw\.net\//i;

/**
 * CDN M3U8 listener — captures any .m3u8 request from ttvnw.net.
 *
 * Instead of parsing the CDN URL (which changes across API versions and
 * CDN hostnames), we resolve the channel/VOD from the tab that initiated
 * the request.  The tab URL (twitch.tv/{login} or twitch.tv/videos/{id})
 * is the stable ground truth.
 */
// Record the master URL + tab context, then complete the rendezvous (which hands
// the master to native for OkHttp fetch + Java enumeration). No body capture
// here — Java fetches and parses it.
function captureTwitchMaster(key, details) {
    const entry = getTwitchRendezvous(key);
    if (entry.m3u8Url) return; // first .m3u8 only
    log("TWITCH-CDN", `Captured M3U8 for ${key}`, { tabId: details.tabId, url: details.url.slice(0, 120) });
    entry.m3u8Url = details.url;
    if (!entry.details && details.tabId >= 0) {
        entry.details = { tabId: details.tabId, _resolvedTabId: details.tabId, requestId: `cdn-${Date.now()}` };
    }
    tryCompleteTwitchRendezvous(key);
}

function listenerTwitchCdnM3u8(details) {
    if (isOwnRequest(details.url)) return;
    if (!TWITCH_MASTER_RE.test(details.url)) return;   // a media playlist refresh, not a master

    const tabLogin = resolveLoginFromTab(details.tabId);
    if (tabLogin) { captureTwitchMaster(tabLogin, details); return; }

    const tabVodId = resolveVodIdFromTab(details.tabId);
    if (tabVodId) { captureTwitchMaster(`vod-${tabVodId}`, details); return; }

    log("TWITCH-CDN", `M3U8 captured but no tab match`, { tabId: details.tabId, url: details.url.slice(0, 80) });
}

// Observe-only: the listener records the URL and hands it to Java, which
// fetches the body itself — nothing is filtered or modified here, so a
// blocking registration only held every playlist refresh behind the
// background page's event loop (a parser parsing a large body elsewhere
// could stall a live stream's buffering).
browser.webRequest.onBeforeRequest.addListener(
    listenerTwitchCdnM3u8,
    { urls: ["*://*.ttvnw.net/*.m3u8*"] }
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
    if (m && !TWITCH_NON_CHANNEL.has(m[1].toLowerCase())) {
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
    if (!processedTwitchUrls.claim(key)) { log("TWITCH", `Already processing ${login}, skipping`); return; }

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
            // Keep the 30 s claim: tabs.onUpdated ticks 3-4× per load, and
            // releasing it here re-POSTed the GQL query on every tick for an
            // offline channel (and for every non-channel path the parser
            // mistook for one).
            log("TWITCH", `Channel offline`, { login });
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
    if (!processedTwitchUrls.claim(key)) return;

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
    if (!processedTwitchUrls.claim(key, 10_000)) return;

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

// Tab-URL / SPA-navigation trigger (was the hardcoded call in tabs.onUpdated).
registerSpaHandler(checkAndProcessTwitchUrl);
