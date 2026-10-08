// Apple Podcasts parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, stripHtml, sendNative, collectFilteredResponse, markOwnRequest, resolveTabId, ensureTabId, tabClaims, tabOfDetails } from './common.js';

// ============================================================================
// Apple Podcasts
// ============================================================================
//
// Apple's web player is a React SPA that DOES NOT update the URL when the
// user picks an episode from a show page — pushState doesn't fire, the
// URL stays on the show route, and the audio is fetched directly from
// the publisher's CDN (ivoox, libsyn, megaphone, art19, …).
//
// What DOES fire on show-page load is one amp-api XHR that returns the
// full episode list inline:
//
//   GET https://amp-api.podcasts.apple.com/v1/catalog/{country}/podcasts/{id}
//        ?extend=availableEpisodeCount,editorialArtwork,feedUrl,…
//        &include=artists,episodes,genres,participants,reviews,trailers
//        &limit[episodes]=15
//        &with=entitlements,hlsVideo,showHero
//
// Response shape:
//   { "data": [ {
//       "id": "<podcastId>",
//       "type": "podcasts",
//       "attributes": { "name": "<show name>", "artwork": {...}, ... },
//       "relationships": {
//         "episodes": { "data": [ {
//           "id": "<episodeId>",
//           "type": "podcast-episodes",
//           "attributes": {
//             "name": "...",                   // episode title
//             "description": { "standard": "...", "short": "..." },
//             "assetUrl": "https://.../episode.mp3",   // direct audio URL
//             "artwork": { "url": "https://.../{w}x{h}bb.{f}", ... },
//             "durationInMilliseconds": 234000,
//             "artistName": "...",
//             "url": "https://podcasts.apple.com/.../...?i={episodeId}"
//           }
//         }, ... ] }
//       }
//     } ] }
//
// We filterResponseData on that XHR (same pattern as the Instagram path)
// and surface one media entry per episode. The user then picks which to
// download from the captured-media sheet.
//
// Episode pages reached via direct deep-link (URL contains ?i=<episodeId>)
// are also supported as a fallback — those use the public iTunes Lookup
// API since no amp-api XHR fires on a fresh nav (HTML response on first
// hit, the XHR only fires on the in-app SPA route).

function parseApplePodcastsUrl(url) {
    const m = url.match(/podcasts\.apple\.com\/[^/]+\/podcast\/[^/?#]+\/id(\d+)(?:[?&]i=(\d+))?/);
    if (!m) return null;
    return { podcastId: m[1], episodeId: m[2] || null };
}

function buildAppleArtworkUrl(template) {
    if (typeof template !== "string" || !template) return null;
    return template
        .replace("{w}", "600")
        .replace("{h}", "600")
        .replace("{f}", "jpg");
}

// Per-tab: the three triggers name the same episode within seconds in ONE
// tab; a second tab is its own capture.
function appleClaims(details) {
    return tabClaims(tabOfDetails(details), "apple", 5_000, 256);
}

/**
 * Core lookup-and-dispatch path. Takes an Apple episode id plus a source
 * label and origin URL. Called from three triggers (see below) — they
 * differ in how they obtain the episode id but agree on what to do with
 * it. Dedups on the id so the same episode isn't looked up twice when
 * multiple triggers fire within 5 seconds.
 */
async function processApplePodcastEpisode(episodeId, details, originUrl, source) {
    const urlKey = "apple-episode:" + episodeId;
    if (!appleClaims(details).claim(urlKey)) return;

    if (details.requestId !== undefined) {
        await ensureTabId(details);
    }

    try {
        const lookupUrl = `https://itunes.apple.com/lookup?id=${episodeId}&entity=podcastEpisode`;
        markOwnRequest(lookupUrl);
        const response = await fetch(lookupUrl, { credentials: "omit" });
        const data = await response.json();

        if (!data?.results?.length) {
            log("APPLE_PODCAST", `No results for episode ${episodeId}`);
            return;
        }

        // First result may be the podcast (kind=podcast) followed by the
        // episode (kind=podcast-episode) depending on the API's mood;
        // explicitly pick the episode entry.
        const episode = data.results.find(r => r.kind === "podcast-episode")
                    || data.results.find(r => r.wrapperType === "podcastEpisode")
                    || data.results[0];

        if (!episode?.episodeUrl) {
            log("APPLE_PODCAST", `No episodeUrl in lookup result`, { trackId: episode?.trackId });
            return;
        }

        // webNavigation already gives us tabId directly; webRequest needs
        // resolveTabId for cases where the listener fires before the tab
        // url cache has caught up.
        const tabId = details.requestId !== undefined
            ? await resolveTabId(details)
            : details.tabId;

        const message = {
            url: episode.episodeUrl,
            type: "media",
            origin: originUrl,
            tabId,
            name: episode.trackName,
            description: episode.collectionName || stripHtml(episode.description) || undefined,
            img: episode.artworkUrl600 || episode.artworkUrl160 || episode.artworkUrl100,
            duration: typeof episode.trackTimeMillis === "number" ? episode.trackTimeMillis : undefined
        };
        if (details.requestId !== undefined) message.request = details.requestId;

        // Drop undefined fields so the native side sees clean payloads.
        for (const k of Object.keys(message)) {
            if (message[k] === undefined) delete message[k];
        }

        log("APPLE_PODCAST", `Found episode`, {
            name: message.name,
            series: episode.collectionName,
            url: message.url?.slice(0, 100),
            source,
            tabId
        });
        // We already have the duration from the lookup API; for an audio episode
        // the metadatareader probe added nothing else we need, so skip it. Gate
        // on duration — if the API gave none, keep probing to obtain it. Native
        // only honours skipProbe once it confirms the URL is audio, else it still
        // probes (so an extensionless enclosure can't be misclassified).
        if (typeof message.duration === "number" && message.duration > 0) message.skipProbe = true;
        sendNative(message);
    } catch (e) {
        log("APPLE_PODCAST", `Error`, e.message);
    }
}

/**
 * Trigger 1 & 2 — page URL has the episode id in ?i=... Used by
 * webRequest main_frame (initial load / refresh / deep-link) and by
 * webNavigation onHistoryStateUpdated (SPA pushState).
 */
async function processApplePodcastUrl(details, source) {
    const ids = parseApplePodcastsUrl(details.url);
    if (!ids || !ids.episodeId) return;   // show pages have no audio to download
    await processApplePodcastEpisode(ids.episodeId, details, details.url, source);
}

/**
 * Primary trigger — intercept the amp-api show XHR that returns the
 * embedded episode list. We filter the response body in-flight (same
 * filterResponseData pattern Instagram uses), iterate the episodes,
 * and surface each one as a media entry via sendNative. The episode's
 * `attributes.assetUrl` is the direct audio URL on the publisher's
 * CDN — exactly what the native download path needs.
 */
function listenerApplePodcastShow(details) {
    // Only filter the variants that actually include episode data —
    // Apple makes three back-to-back GETs for the same podcast id with
    // different query strings; only one carries include=episodes.
    const url = details.url;
    if (!/[?&]include=[^&]*\bepisodes\b/.test(url)) {
        return {};
    }

    collectFilteredResponse(details).then(text => {
        const json = tryParseJson(text);
        if (!json?.data?.[0]) {
            log("APPLE_PODCAST", `show response has no data`, { url: url.slice(0, 120) });
            return;
        }

        const show = json.data[0];
        const podcastId = show.id;
        const showName = show.attributes?.name;
        const episodes = show.relationships?.episodes?.data || [];

        log("APPLE_PODCAST", `show ${podcastId} has ${episodes.length} embedded episode(s)`, {
            name: showName
        });

        const originUrl = details.documentUrl || details.originUrl || details.url;
        dispatchAppleEpisodes(episodes, showName, originUrl, details, "amp-api/show");
    }).catch(e => {
        log("APPLE_PODCAST", `show filter error`, e.message);
    });

    return {};
}

/**
 * Second trigger — batch episode lookup. When the user presses Play on
 * a show page, the web player fires
 *
 *   GET amp-api.podcasts.apple.com/v1/catalog/{country}/podcast-episodes
 *       ?ids={id1},{id2},...&include=channel,podcast&fields=...,assetUrl,...
 *
 * to fetch the playback queue (clicked episode plus a handful pre-loaded
 * for continuous play). Same JSON:API shape as the show response, just
 * with multiple entries in data[] and the parent podcast inlined via
 * relationships.podcast.data[0].attributes. We filter the response and
 * surface each entry.
 *
 * Note the path is `/podcast-episodes` (no trailing /{id}) — Apple
 * batches by ids in the query string rather than path. My earlier
 * /podcast-episodes/{id} match pattern was a phantom and never fired.
 */
function listenerApplePodcastEpisodesBatch(details) {
    collectFilteredResponse(details).then(text => {
        const json = tryParseJson(text);
        if (!Array.isArray(json?.data)) {
            log("APPLE_PODCAST", `episodes-batch response has no data array`, {
                url: details.url.slice(0, 120)
            });
            return;
        }

        log("APPLE_PODCAST", `episodes-batch returned ${json.data.length} episode(s)`);

        const originUrl = details.documentUrl || details.originUrl || details.url;
        // Show name is on the first episode's relationships.podcast.data[0]
        // (it's the same parent podcast for every entry in a single batch).
        const podcastRel = json.data[0]?.relationships?.podcast?.data?.[0];
        const showName = podcastRel?.attributes?.name;

        dispatchAppleEpisodes(json.data, showName, originUrl, details, "amp-api/episodes-batch");
    }).catch(e => {
        log("APPLE_PODCAST", `episodes-batch filter error`, e.message);
    });

    return {};
}

/**
 * Shared episode dispatcher. Takes a JSON:API episodes array, the parent
 * show name (used as the description field), the URL the user is browsing
 * (used as origin), and the original webRequest details (for tabId +
 * requestId). Builds one media message per episode and sends each through
 * sendNative. Dedups on episodeId across both triggers so the user doesn't
 * see the same episode twice when both the show response and the batch
 * fire for it.
 */
function dispatchAppleEpisodes(episodes, showName, originUrl, details, source) {
    for (const episode of episodes) {
        const ep = episode?.attributes;
        if (!ep?.assetUrl) continue;

        const episodeId = episode.id;
        const dedupKey = "apple-episode:" + episodeId;
        if (!appleClaims(details).claim(dedupKey, 30000)) continue;

        // Each batch entry may also have its own relationships.podcast.data
        // (the batch listener relies on this). Prefer the per-entry name
        // when the caller didn't pass one in.
        const episodeShowName = showName
            || episode.relationships?.podcast?.data?.[0]?.attributes?.name
            || ep.artistName;

        const message = {
            url: ep.assetUrl,
            type: "media",
            origin: ep.url || originUrl,
            tabId: details.tabId,
            request: details.requestId,
            name: ep.name,
            description: episodeShowName,
            img: buildAppleArtworkUrl(ep.artwork?.url),
            duration: typeof ep.durationInMilliseconds === "number"
                ? ep.durationInMilliseconds
                : undefined
        };
        for (const k of Object.keys(message)) {
            if (message[k] === undefined) delete message[k];
        }

        log("APPLE_PODCAST", `Found episode`, {
            name: message.name,
            series: episodeShowName,
            url: message.url?.slice(0, 100),
            source
        });
        // Duration from the amp-api; skip the metadatareader probe for the audio
        // episode (see the lookup path above). Gated on duration; native still
        // probes unless it can confirm the URL is audio.
        if (typeof message.duration === "number" && message.duration > 0) message.skipProbe = true;
        sendNative(message);
    }
}

/**
 * Fallback trigger — direct deep-link / refresh into an episode page
 * URL (the kind that contains ?i={episodeId}). The amp-api show XHR
 * above doesn't always fire in this path because the SPA may be hydrated
 * directly into episode view, so we also keep an iTunes Lookup fallback
 * driven by main_frame + pushState events on /podcast/ URLs.
 */
async function listenerApplePodcasts(details) {
    await processApplePodcastUrl(details, "webRequest");
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerApplePodcastShow,
    {
        urls: [
            "*://amp-api.podcasts.apple.com/v1/catalog/*/podcasts/*",
            "*://amp-api-edge.podcasts.apple.com/v1/catalog/*/podcasts/*"
        ],
        types: ["xmlhttprequest"]
    },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerApplePodcastEpisodesBatch,
    {
        urls: [
            "*://amp-api.podcasts.apple.com/v1/catalog/*/podcast-episodes*",
            "*://amp-api-edge.podcasts.apple.com/v1/catalog/*/podcast-episodes*"
        ],
        types: ["xmlhttprequest"]
    },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerApplePodcasts,
    {
        urls: ["*://podcasts.apple.com/*/podcast/*"],
        types: ["main_frame"]
    },
    []
);

browser.webNavigation.onHistoryStateUpdated.addListener(
    (details) => {
        if (details.frameId !== 0) return;
        processApplePodcastUrl(details, "webNavigation");
    },
    {
        url: [{ hostEquals: "podcasts.apple.com", pathContains: "/podcast/" }]
    }
);

