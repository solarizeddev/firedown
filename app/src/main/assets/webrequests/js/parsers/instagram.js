// Instagram parser — split verbatim out of the former parser-background.js.
// Also exports sendInstagramItem + the media-item walk helpers for the
// Threads parser (same backend, same item shape — see threads.js).
import { log, tryParseJson, isOwnRequest, markOwnRequest, sendVariants, cacheTabUrl, ensureTabId, registerSpaHandler, registerMessageHandler, readFilteredBody } from './common.js';

const QUEUE_MAX_LENGTH = 256;

const COOKIE_CACHE_KEY = "instagram_cookie_cache";
const COOKIE_CACHE_TTL = 5 * 60 * 1000;
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

// Instagram/Threads video_versions quality tiers. `type` is the only thing that
// distinguishes renditions on lean items (Threads logged-out: each version is
// just {type, url} — no width/height/bitrate). Lower type = higher quality; the
// best tier is the source rendition, so it carries the item's original_*.
const IG_QUALITY_TIER = { 101: "High", 102: "Medium", 103: "Low", 104: "Lowest" };

/**
 * Build sendVariants() variants from an IG/Threads video_versions array.
 * Prefers each rendition's own width/height; for lean items that ship only a
 * `type`, the best tier gets the item's real original_* resolution and the
 * lower tiers get a quality-name label, so the picker rows are distinct instead
 * of all blank or all showing the same source resolution.
 */
function buildInstagramVariants(videoVersions, ow, oh) {
    // Threads commonly lists the SAME progressive URL several times under
    // different `type` ids (101/102/103) — verified by HAR to be byte-identical
    // URLs, i.e. NOT distinct renditions (Threads ships one progressive file
    // with no per-rendition dimensions). Dedup by URL so the picker shows one
    // row per actual file instead of N identical ones. When a post genuinely
    // does carry distinct URLs per type, they survive and are labelled by tier.
    const seenUrl = new Set();
    const versions = [];
    for (const v of videoVersions) {
        if (!v || typeof v.url !== "string" || seenUrl.has(v.url)) continue;
        seenUrl.add(v.url);
        versions.push(v);
    }
    let bestType = Infinity;
    for (const vv of versions) {
        if (typeof vv?.type === "number" && vv.type < bestType) bestType = vv.type;
    }
    return versions.map(v => {
        const variant = { url: v.url };
        if (v.width && v.height) {
            variant.width = v.width;
            variant.height = v.height;
        } else if (typeof v.type === "number" && v.type === bestType && ow && oh) {
            variant.width = ow;
            variant.height = oh;
        } else if (typeof v.type === "number") {
            variant.label = IG_QUALITY_TIER[v.type] || ("Quality " + v.type);
        }
        return variant;
    });
}

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
        const variants = buildInstagramVariants(
            item.video_versions, item.original_width || 0, item.original_height || 0);
        log("IG-ITEM", `Sending ${variants.length} video variant(s)`, { code, firstUrl: variants[0]?.url?.slice(0, 80) });
        sendVariants(details, { variants, origin, description: videoText, img, name: author, duration });
    }

    if (item.carousel_media) {
        let carouselVideos = 0;
        for (const media of item.carousel_media) {
            if (!media.video_versions) continue;
            carouselVideos++;
            const variants = buildInstagramVariants(
                media.video_versions, media.original_width || 0, media.original_height || 0);
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

// ============================================================================
// Instagram/Threads media-item deep walk — shared by the Instagram SSR doc
// filter (below) and the Threads doc/API filters (threads.js imports these).
// Meta Relay payloads nest the item deep (require[..].__bbox.require[..]
// .__bbox.result.data… puts video_versions at depth ~15-22), so the caps are
// generous on purpose — a depth cap of 14 once cost eight debugging rounds
// on Threads (the walk bailed two levels short of the item).
// ============================================================================

function isInstagramMediaItem(obj) {
    if (!obj || typeof obj !== "object") return false;
    if (Array.isArray(obj.video_versions) && obj.video_versions.length > 0) return true;
    if (Array.isArray(obj.carousel_media)
        && obj.carousel_media.some(m => Array.isArray(m?.video_versions) && m.video_versions.length > 0)) {
        return true;
    }
    return false;
}

function walkInstagramMediaItems(node, onItem, depth, seen, counter) {
    if (!node || typeof node !== "object" || depth > 40) return;
    if (counter.visited++ > 50000) return;
    if (seen.has(node)) return;
    seen.add(node);
    if (isInstagramMediaItem(node)) onItem(node);
    if (Array.isArray(node)) {
        for (const v of node) walkInstagramMediaItems(v, onItem, depth + 1, seen, counter);
    } else {
        for (const k in node) walkInstagramMediaItems(node[k], onItem, depth + 1, seen, counter);
    }
}

// The same item is often inlined several times — a canonical record (user +
// caption + duration + thumbnails) and lean Relay fragments carrying only
// video_versions. Keep the richest candidate per code so metadata isn't lost
// to a lean copy.
function instagramItemRichness(item) {
    let score = 0;
    if (item?.user?.username) score += 2;
    if (item?.caption?.text) score += 1;
    if (item?.video_duration) score += 1;
    if (item?.image_versions2?.candidates?.length) score += 1;
    if (Array.isArray(item?.carousel_media) && item.carousel_media.length) score += 1;
    return score;
}

// Walk one parsed JSON value, folding every video item into bestByCode keyed
// on post code, keeping the richest record per code.
function collectInstagramMediaItems(parsed, bestByCode) {
    walkInstagramMediaItems(parsed, (item) => {
        const code = item.code;
        if (!code) return;
        const prev = bestByCode.get(code);
        if (!prev || instagramItemRichness(item) > instagramItemRichness(prev)) {
            bestByCode.set(code, item);
        }
    }, 0, new WeakSet(), { visited: 0 });
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

                // xig_polaris_media (2025): the media sits under a gating
                // wrapper — data.xig_polaris_media.if_not_gated_logged_out is
                // the item itself (SSR-verified shape; covered here too for
                // SPA navigations that fetch the same query as an XHR).
                const gated = value.if_not_gated_logged_out;
                if (gated && typeof gated === "object" && !Array.isArray(gated)
                        && (gated.video_versions || gated.media_type === 2 || gated.carousel_media)) {
                    sendInstagramItem(details, gated);
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

// Content script message handler (fallback if filterResponseData fails).
// Registered on the shared router (common.js) — keyed on message.type.
registerMessageHandler("instagram_intercept", (message, sender) => {
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

// ---- Page navigation listener (SSR doc filter + GraphQL fallback) ----

/**
 * Post/reel pages SSR-inline the media item into the document's
 * <script data-sjs> Relay blobs (2025 shape: result.data.xig_polaris_media
 * .if_not_gated_logged_out — verified by HAR on a logged-out reel, where the
 * page's own graphql XHRs carry only experiments + Bloks login-wall payloads
 * and the video exists NOWHERE else on the wire). Read the document RESPONSE
 * with filterResponseData — never the DOM: Meta's bootstrap consumes the
 * data-sjs scripts the instant they parse (the Threads lesson). The item walk
 * is shape-based (video_versions/carousel_media), so it finds the media
 * wherever the query name of the day nests it.
 *
 * The old behavior — an own GraphQL fetch by shortcode — is kept only as the
 * FALLBACK for documents whose SSR carries no media (some logged-in shapes);
 * it broke as the primary path when the API changed (the fetch's doc_id
 * query stopped returning media for logged-out sessions).
 */
function listenerInstagramPage(details) {
    if (details.type !== "main_frame") return;

    const url = details.url;
    if (details.tabId >= 0) cacheTabUrl(url, details.tabId);

    const match = url.match(/\/(?:reel|p)\/([A-Za-z0-9_-]+)/);
    const shortcode = match?.[1] || null;

    const filtering = readFilteredBody(details, "IG-PAGE", "doc filter", (html, bytes) => {
        const sjsRegex = /<script[^>]*\bdata-sjs\b[^>]*>([\s\S]*?)<\/script>/g;
        const bestByCode = new Map();
        let scriptCount = 0;
        let m;
        while ((m = sjsRegex.exec(html)) !== null) {
            scriptCount++;
            const parsed = tryParseJson(m[1]);
            if (parsed) collectInstagramMediaItems(parsed, bestByCode);
        }
        log("IG-PAGE", `doc filter: ${bytes} bytes, ${scriptCount} data-sjs script(s), ${bestByCode.size} video item(s)`, { url: url.slice(0, 100) });

        for (const item of bestByCode.values()) {
            sendInstagramItem(details, item);
        }

        if (bestByCode.size === 0 && shortcode) {
            log("IG-PAGE", `doc had no media, GraphQL fallback`, { shortcode });
            fetchInstagramByShortcode(details, shortcode);
        }
    });

    // Filter creation failed (shouldn't happen on stock GeckoView) — keep the
    // old fetch-by-shortcode path so the page still captures.
    if (!filtering && shortcode) {
        log("IG-PAGE", `doc filter unavailable, GraphQL fallback`, { shortcode });
        fetchInstagramByShortcode(details, shortcode);
    }
}

// ---- Listener registrations ----

// filterResponseData on Instagram API — intercepts response inline
browser.webRequest.onBeforeRequest.addListener(
    listenerInstagramApiFilter,
    { urls: IG_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

// Page navigations (main_frame) — SSR doc filter, needs "blocking" for
// filterResponseData (the old registration passed [] because it only fired
// a side fetch; the doc read is why it now must block).
browser.webRequest.onBeforeRequest.addListener(
    listenerInstagramPage,
    { urls: [
        "*://www.instagram.com/reel/*",
        "*://www.instagram.com/p/*",
        "*://www.instagram.com/*/reel/*",
        "*://www.instagram.com/*/p/*"
    ], types: ["main_frame"] },
    ["blocking"]
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

// Tab-URL / SPA-navigation trigger (was the hardcoded call in tabs.onUpdated).
registerSpaHandler(checkAndProcessInstagramUrl);

export { sendInstagramItem, isInstagramMediaItem, walkInstagramMediaItems, instagramItemRichness, collectInstagramMediaItems };
