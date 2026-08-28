// Dailymotion parser — split verbatim out of the former parser-background.js.
import { log, tryParseJson, isOwnRequest, markOwnRequest, sendVariants, parseHlsMaster, enumerateMasterNative, cacheTabUrl, ensureTabId, registerSpaHandler, readFilteredJson } from './common.js';

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

    // Synchronous apiSeen claim — same backbone-race note as the embed
    // listener below.
    dmEmbedEntry(videoId).apiSeen = true;

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

    // Page-driven fetch path: claim apiSeen up front so the page's own
    // player fetching the master mid-flight defers to this titled emit.
    dmEmbedEntry(videoId).apiSeen = true;

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
async function processDailymotionData(details, data, videoId) {
    const origin = `https://www.dailymotion.com/video/${videoId}`;

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

    // Rename-proof fallback (the Instagram lesson): find the master by what it
    // IS — a Dailymotion-CDN .m3u8 string anywhere in the JSON — when the
    // qualities.auto path no longer holds it.
    if (!hlsUrl) {
        hlsUrl = findDailymotionHlsUrl(data);
    }

    if (!hlsUrl) {
        log("DAILYMOTION", `No HLS URL found`, { videoId, qualities: Object.keys(data.qualities || {}) });
        return;
    }

    const title = data.title || "";
    const duration = data.duration ? data.duration * 1000 : 0;

    // Pick best thumbnail
    let img = null;
    if (data.thumbnails) {
        const sizes = ["1080", "720", "480", "360", "240", "180", "120", "60"];
        for (const size of sizes) {
            if (data.thumbnails[size]) { img = data.thumbnails[size]; break; }
        }
    }

    // Feed the shared metadata cache + claim the emit, so the wire-master
    // backbone listener below doesn't re-emit the same session (and can enrich
    // a later signed-master refresh with this metadata).
    const cached = dmEmbedEntry(videoId);
    if (title) cached.title = title;
    if (duration) cached.duration = duration;
    if (img) cached.img = img;
    cached.emitted = true;

    await emitDailymotionHls(details, { hlsUrl, origin, title, duration, img });
}

/**
 * Shared HLS emit for BOTH capture surfaces (the legacy dailymotion.com page
 * path above and the embed-player path below) — one definition so the two can't
 * drift. Enumerates the signed master HERE, in the extension, with
 * parseHlsMaster (the JS twin of M3U8Parser) and emits sendVariants(skipProbe)
 * — never the metadatareader probe. The master is fetched in the BROWSER
 * context (credentials:include) because Dailymotion's CDN needs the page's
 * session cookies + UA + Referer; the server-side OkHttp fetch in
 * processHlsMaster gets rejected and falls back to the probe (the "still
 * probed" bug). Same shape as niconico (parse the master in JS, emit variants).
 */
async function emitDailymotionHls(details, { hlsUrl, origin, title, duration, img }) {
    const name = title.length > 40 ? title.slice(0, 40).replace(/\s+\S*$/, "") : title;

    // Headers Dailymotion's manifest/segment CDN (dailymotion.com/cdn/manifest,
    // dmcdn.net) expects — browser UA + Referer. Carried on the emit so they
    // reach every download sub-request (media playlist, segments) via the entity.
    const requestHeaders = [
        { name: "User-Agent", value: navigator.userAgent },
        { name: "Referer", value: "https://www.dailymotion.com/" },
        { name: "Accept", value: "*/*" },
        { name: "Accept-Language", value: "en-US,en;q=0.9" },
    ];

    try {
        markOwnRequest(hlsUrl);
        const resp = await fetch(hlsUrl, { credentials: "include", headers: { "Accept": "*/*" } });
        if (resp.ok) {
            const masterText = await resp.text();
            const variants = parseHlsMaster(masterText, hlsUrl);
            if (variants.length > 0) {
                log("DAILYMOTION", `enumerated ${variants.length} variant(s)`, { origin, name });
                sendVariants(details, {
                    variants, origin, description: title, name, img, duration,
                    requestHeaders, skipProbe: true, manifest: true
                });
                return;
            }
            log("DAILYMOTION", `master had no STREAM-INF variants`, { origin, head: masterText.slice(0, 60) });
        } else {
            log("DAILYMOTION", `master fetch failed`, { origin, status: resp.status });
        }
    } catch (e) {
        log("DAILYMOTION", `master fetch/parse error`, e.message);
    }

    // Fallback: hand the master URL to native enumeration (M3U8Parser, still
    // skipProbe if it can fetch it; only if THAT also fails does it probe).
    log("DAILYMOTION", `falling back to native enumeration`, { origin });
    enumerateMasterNative(details, { url: hlsUrl, origin, name, description: title, img, duration, requestHeaders });
}

// ---------------------------------------------------------------------------
// EMBED player API — geo.dailymotion.com/videos/<id> [+ /details].
//
// A Dailymotion video EMBEDDED on a third-party site (marca.com was the
// HAR-verified case) never touches any trigger above: the player is a
// geo.dailymotion.com/player/<cfg>.html?video=<id> IFRAME (never a
// main_frame), and it fetches its config from /videos/<id> (plural, no
// .json) — not the legacy /video/<id>.json the geo listener matches. With the
// media parser-block-listed (the cardinal rule's cdndirector/dmcdn rules),
// the generic catcher can't grab it either, so an embed was lost ENTIRELY —
// the same trap the Bluesky wire-master listener exists for: a block-listed
// site whose parser doesn't cover one of its surfaces captures nothing there.
//
// The embed API is self-sufficient: /videos/<id> carries the signed HLS
// master (stream.url) + posters_url; /videos/<id>/details carries
// info.title/info.duration. Both are read write-through (readFilteredJson)
// and merged per videoId; the emit goes through the shared
// emitDailymotionHls, so embed captures are byte-identical in shape to the
// dailymotion.com ones and the canonical /video/<id> origin dedups the two
// paths to one entity.
// ---------------------------------------------------------------------------

/** Per-videoId merge of the two embed-API bodies (order not guaranteed) —
 *  ALSO the metadata cache the wire-master backbone listener below enriches
 *  from (the Bluesky bskyMetaCache role). */
const dmEmbedCache = new Map();
// The entry carries the EMITTED-CLAIM and the title the wire-master backbone
// enriches from, and the player fetches the master at VIEW/PLAY time — which
// on an article page is minutes after the config landed (the user reads
// first). This TTL must outlive that whole gap: it shipped as 60s, and a
// play 1+ minute after load found an empty cache — fresh entry, apiSeen
// false, no emitted claim — so the backbone emitted the generic
// "Dailymotion video" for a video the API path had already captured titled
// (the second on-device generic-title report, HAR 26-08-28 13:58). Size is
// bounded by the cap below instead of a short TTL.
const DM_EMBED_TTL_MS = 30 * 60_000;
const DM_EMBED_CACHE_MAX = 256;

/**
 * Shape-based HLS-URL fallback for the config JSONs (the Instagram lesson:
 * the walk is the backbone, exact field paths are fast paths). Bounded BFS
 * over the parsed object for any string that IS a Dailymotion-CDN .m3u8 URL —
 * so a renamed wrapper/field (stream.url today, qualities.auto before it)
 * degrades nothing: the master is found by what it IS, not where it sits.
 */
function findDailymotionHlsUrl(root) {
    const seen = new Set();
    const queue = [{ v: root, d: 0 }];
    let budget = 2000;
    while (queue.length > 0 && budget-- > 0) {
        const { v, d } = queue.shift();
        if (v == null || d > 6) continue;
        if (typeof v === "string") {
            if (/^https?:\/\/[^\s"']*(?:dailymotion\.com|dmcdn\.net)\/[^\s"']*\.m3u8/i.test(v)) {
                return v;
            }
            continue;
        }
        if (typeof v !== "object" || seen.has(v)) continue;
        seen.add(v);
        for (const k of Object.keys(v)) {
            queue.push({ v: v[k], d: d + 1 });
        }
    }
    return null;
}

function dmEmbedEntry(videoId) {
    let entry = dmEmbedCache.get(videoId);
    if (!entry) {
        // apiSeen: an API listener OBSERVED a request for this video (set
        // SYNCHRONOUSLY, before any async body read) — the wire-master
        // backbone's signal that a titled emit is coming and it should wait
        // instead of racing ahead with the generic title.
        entry = { streamUrl: null, img: null, title: "", duration: 0, emitted: false, apiSeen: false };
        if (dmEmbedCache.size >= DM_EMBED_CACHE_MAX) {
            dmEmbedCache.delete(dmEmbedCache.keys().next().value); // FIFO trim
        }
        dmEmbedCache.set(videoId, entry);
        setTimeout(() => dmEmbedCache.delete(videoId), DM_EMBED_TTL_MS);
    }
    return entry;
}

/** Largest poster from the embed API's posters_url map (keys are heights). */
function bestDailymotionPoster(posters) {
    if (!posters || typeof posters !== "object") return null;
    const sizes = ["1080", "720", "480", "360", "240", "180", "120", "60"];
    for (const size of sizes) {
        if (posters[size]) return posters[size];
    }
    return null;
}

function listenerDailymotionEmbedApi(details) {
    if (isOwnRequest(details.url)) return {};
    const m = details.url.match(/geo\.dailymotion\.com\/videos\/([A-Za-z0-9]+)(\/details)?(?:[?#]|$)/);
    if (!m) return {};
    const videoId = m[1];
    const isDetails = !!m[2];

    // SYNCHRONOUS, before the async body read: tell the wire-master backbone
    // the API path is live for this video. The player fetches the HLS master
    // the instant it has the config, so the master listener otherwise races
    // ahead of this listener's parse + /details enrichment, emits the generic
    // "Dailymotion video" title, and its emitted-claim then SUPPRESSES the
    // titled emit — the on-device "embed title is just 'Dailymotion video'"
    // bug. The flag only ever delays the backbone (bounded grace); it never
    // disables it.
    dmEmbedEntry(videoId).apiSeen = true;

    readFilteredJson(details, "DAILYMOTION",
            `embed ${isDetails ? "details" : "config"} ${videoId}`, (data) => {
        const entry = dmEmbedEntry(videoId);
        if (isDetails) {
            if (data?.info?.title) entry.title = data.info.title;
            if (data?.info?.duration) entry.duration = data.info.duration * 1000;
        } else {
            // Exact path first, shape walk as the rename-proof fallback.
            const streamUrl = data?.stream?.url || findDailymotionHlsUrl(data);
            if (streamUrl) entry.streamUrl = streamUrl;
            const poster = bestDailymotionPoster(data?.media?.posters_url);
            if (poster) entry.img = poster;
        }
        maybeEmitDailymotionEmbed(details, videoId);
    });
    return {};
}

async function maybeEmitDailymotionEmbed(details, videoId) {
    const entry = dmEmbedEntry(videoId);
    if (!entry.streamUrl || entry.emitted) return;

    // /videos/<id> usually lands BEFORE /details; rather than emit untitled or
    // hold a timer, fetch the details ourselves when they haven't arrived —
    // deterministic enrichment with the untitled emit as the fallback.
    if (!entry.title) {
        try {
            const detailsUrl = `https://geo.dailymotion.com/videos/${videoId}/details`;
            markOwnRequest(detailsUrl);
            const resp = await fetch(detailsUrl, {
                credentials: "include",
                headers: { "Accept": "application/json" }
            });
            if (resp.ok) {
                const d = tryParseJson(await resp.text());
                if (d?.info?.title) entry.title = d.info.title;
                if (d?.info?.duration && !entry.duration) entry.duration = d.info.duration * 1000;
            }
        } catch (_) { /* best-effort — emit with what we have */ }
    }

    // Re-check after the await: the player's own /details body can arrive and
    // emit while our enrichment fetch was in flight.
    if (entry.emitted) return;
    entry.emitted = true;

    log("DAILYMOTION", `embed capture`, { videoId, title: entry.title.slice(0, 60) });
    await emitDailymotionHls(details, {
        hlsUrl: entry.streamUrl,
        origin: `https://www.dailymotion.com/video/${videoId}`,
        title: entry.title,
        duration: entry.duration,
        img: entry.img,
    });
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

// ---------------------------------------------------------------------------
// WIRE-MASTER backbone — the API-change-proof net (the Bluesky pattern).
//
// Every listener above keys on Dailymotion's CONFIG APIs, which are exactly
// what churns (qualities.auto → stream.url, /video/<id>.json → /videos/<id>
// already happened once). The one request that CANNOT change without breaking
// Dailymotion's own player is the HLS MASTER fetch itself — and with the
// media parser-block-listed, an API change would otherwise mean NOTHING
// captures (the marca embed bug's mechanism). So this read-only listener
// captures the master straight off the wire the moment any player fetches it
// — every surface, every player version, every future API shape — extracts
// the video id from the manifest URL itself, enriches from whatever metadata
// the API listeners DID manage to cache (dmEmbedCache), and falls back to a
// generic title when they saw nothing. The API paths stay the rich fast
// paths and claim the emit first (entry.emitted); this fires only when they
// didn't. Same canonical /video/<id> origin, so whichever path lands first
// wins the repository race and the rest dedup. Don't remove this "because
// the API listeners already cover it" — they cover it until Dailymotion
// ships a change, which is precisely the day this listener earns its keep.
// ---------------------------------------------------------------------------
// How long the backbone waits for a LIVE API path to finish its titled emit
// before falling back to the generic one. The API path's remaining work at
// master time is small (parse the already-received config + one /details
// round trip — the emitted claim is set BEFORE its own master fetch), so this
// only needs to cover a couple of RTTs; sized generously for slow mobile
// links. This is the sanctioned timer shape (CLAUDE.md's timer-vs-count
// rule): an unbounded external wait (the page's own network timing) with a
// correct fallback (the generic emit).
const DM_MASTER_GRACE_MS = 5000;
const DM_MASTER_POLL_MS = 150;

function listenerDailymotionMaster(details) {
    if (isOwnRequest(details.url)) return;   // our own emitDailymotionHls fetch
    const m = details.url.match(/\/cdn\/manifest\/video\/([A-Za-z0-9]+)\.m3u8/);
    if (!m) return;
    const videoId = m[1];

    const entry = dmEmbedEntry(videoId);
    if (entry.emitted) return;               // an API path already owns this one

    const key = `dm-master-${videoId}`;
    if (processedDailymotionUrls.has(key)) return;
    processedDailymotionUrls.add(key);
    setTimeout(() => processedDailymotionUrls.delete(key), 10_000);

    emitDailymotionMasterWhenApiSettles(details, videoId, entry);
}

// The backbone emit, RACE-AWARE. The player fetches the master the moment it
// has the config, so this listener fires while the API path is still parsing
// that config / fetching /details — emitting the generic title immediately
// both mislabels the capture AND (via the emitted claim) suppresses the
// titled emit that lands a second later (the shipped on-device bug). So:
// when the API path is LIVE for this video (apiSeen — its request crossed
// the wire), wait a bounded grace for it to claim the emit; only when it
// stays silent (a future API shape the parser can't read — the case this
// listener exists for) emit here, enriched with whatever metadata DID land
// meanwhile. apiSeen false = the API genuinely wasn't observable (the
// config request always precedes the master, so listener ordering can't
// fake this) → emit immediately, as before.
async function emitDailymotionMasterWhenApiSettles(details, videoId, entry) {
    if (entry.apiSeen) {
        const deadline = Date.now() + DM_MASTER_GRACE_MS;
        while (Date.now() < deadline) {
            if (entry.emitted) return;       // the API path delivered its titled emit
            await new Promise((r) => setTimeout(r, DM_MASTER_POLL_MS));
        }
        if (entry.emitted) return;
    }
    entry.emitted = true;
    log("DAILYMOTION", `wire-master capture`, {
        videoId, apiSeen: entry.apiSeen, title: entry.title.slice(0, 60) });
    emitDailymotionHls(details, {
        hlsUrl: details.url,
        origin: `https://www.dailymotion.com/video/${videoId}`,
        title: entry.title || "Dailymotion video",
        duration: entry.duration || 0,
        img: entry.img || null,
    });
}

// Embed player API (third-party-site embeds; see the block comment above).
// blocking: filterResponseData is only available on a blocking listener.
browser.webRequest.onBeforeRequest.addListener(
    listenerDailymotionEmbedApi,
    { urls: ["*://geo.dailymotion.com/videos/*"], types: ["xmlhttprequest"] },
    ["blocking"]
);

// Wire-master backbone — read-only, non-blocking; broad types on purpose
// (players fetch the master as fetch/XHR today, but a <video>-driven fetch
// reports 'media' and a worker-driven one can report 'other').
browser.webRequest.onBeforeRequest.addListener(
    listenerDailymotionMaster,
    { urls: ["*://*.dailymotion.com/cdn/manifest/video/*"], types: ["xmlhttprequest", "media", "other"] },
    []
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


// Tab-URL / SPA-navigation trigger (was the hardcoded call in tabs.onUpdated).
registerSpaHandler(checkAndProcessDailymotionUrl);
