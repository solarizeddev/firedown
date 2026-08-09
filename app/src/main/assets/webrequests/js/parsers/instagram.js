// Instagram parser — split verbatim out of the former parser-background.js.
// Also exports sendInstagramItem + the media-item walk helpers for the
// Threads parser (same backend, same item shape — see threads.js).
import { log, tryParseJson, isOwnRequest, markOwnRequest, sendVariants, cacheTabUrl, ensureTabId, registerSpaHandler, registerMessageHandler, readFilteredBody, decodeHtmlEntities } from './common.js';

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

// ============================================================================
// Instagram — inline DASH manifest (video_dash_manifest)
// ============================================================================
// Meta's DASH is the Bilibili model: SegmentBase, each Representation is ONE
// whole-track fbcdn .mp4 BaseURL byte-range-fetched by the player — so a
// rendition downloads as a plain video+audio URL pair muxed natively by
// FFmpegMergeStrategy, no manifest handed to ffmpeg. This is not only the
// quality ladder (the progressive video_versions usually collapse to a single
// baseline file): on dash-eligible clips the "progressive" URL IS the DASH
// video-only track — HAR-verified: identical path to the avc1 Representation's
// BaseURL, 844-byte single-track init, and the live player range-fetches that
// very URL as its video track while pulling audio from a separate file. A
// progressive-only capture of those is a SILENT download, so when a manifest
// parses, the same-path progressive rows are dropped (see
// buildInstagramItemVariants).

const MPD_DURATION_RE = /mediaPresentationDuration="PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?"/;
const MPD_REPRESENTATION_RE = /<Representation\b([^>]*)>([\s\S]*?)<\/Representation>/g;
const MPD_ATTR_RE = /([\w:-]+)="([^"]*)"/g;

function parseMpdDurationMs(mpd) {
    const m = mpd.match(MPD_DURATION_RE);
    if (!m) return 0;
    const hours = parseInt(m[1] || "0", 10);
    const minutes = parseInt(m[2] || "0", 10);
    const seconds = parseFloat(m[3] || "0");
    const ms = Math.round(((hours * 60 + minutes) * 60 + seconds) * 1000);
    return Number.isFinite(ms) && ms > 0 ? ms : 0;
}

/**
 * Minimal reader for Meta's machine-generated MPD (regex on Representation
 * blocks — deliberately not a general XML parser: the manifest is one shape,
 * and this stays runnable under node for the HAR-replay tests). Returns
 * { videos: [{url,width,height,bandwidth}] height-desc, audio: {url,bandwidth}
 * | null, durationMs }. BaseURLs are XML-escaped (&amp;) — decoded here.
 */
function parseInstagramDashManifest(mpd) {
    const videos = [];
    let audio = null;
    let m;
    MPD_REPRESENTATION_RE.lastIndex = 0;
    while ((m = MPD_REPRESENTATION_RE.exec(mpd)) !== null) {
        const attrs = {};
        let a;
        MPD_ATTR_RE.lastIndex = 0;
        while ((a = MPD_ATTR_RE.exec(m[1])) !== null) attrs[a[1]] = a[2];

        const base = m[2].match(/<BaseURL>([\s\S]*?)<\/BaseURL>/);
        if (!base) continue;
        const url = decodeHtmlEntities(base[1].trim());
        if (!/^https?:\/\//.test(url)) continue;

        const mime = attrs.mimeType || "";
        const codecs = attrs.codecs || "";
        const bandwidth = parseInt(attrs.bandwidth || "0", 10) || 0;

        if (mime.startsWith("audio/") || (!mime && /^(mp4a|opus|ec-3|ac-3)/i.test(codecs))) {
            // Best (highest-bandwidth) audio rendition wins
            if (!audio || bandwidth > audio.bandwidth) {
                audio = { url, bandwidth };
            }
        } else if (mime.startsWith("video/") || attrs.width) {
            videos.push({
                url,
                width: parseInt(attrs.width || "0", 10) || 0,
                height: parseInt(attrs.height || "0", 10) || 0,
                bandwidth
            });
        }
    }
    videos.sort((a, b) => (b.height - a.height) || (b.bandwidth - a.bandwidth));
    return { videos, audio, durationMs: parseMpdDurationMs(mpd) };
}

/** URL identity without the (rotating, signed) query string. */
function urlPath(u) {
    return typeof u === "string" ? u.split("?")[0] : "";
}

/**
 * tryParseJson with anti-hijack-prefix tolerance. Meta prepends junk to JSON
 * bodies ("for (;;);" today; "while(1);" and variants have shipped on other
 * Meta surfaces) — when the whole-string parse fails, retry from the first
 * JSON delimiter instead of pattern-matching the prefix of the day.
 */
function tolerantParseJson(str) {
    const parsed = tryParseJson(str);
    if (parsed) return parsed;
    const obj = str.indexOf("{");
    const arr = str.indexOf("[");
    const start = (obj < 0) ? arr : (arr < 0 ? obj : Math.min(obj, arr));
    if (start > 0) return tryParseJson(str.slice(start));
    return null;
}

/**
 * Full variant list for one media item: DASH renditions (each a video URL +
 * the best audio URL, merged at download) when a manifest is present and
 * parses, plus any progressive video_versions that are genuinely distinct
 * files. Progressive rows whose PATH matches a DASH video rendition are the
 * video-only track itself (a silent download — see the section comment) and
 * are dropped; so are height-duplicates of a DASH rendition. With no or an
 * unparseable manifest this reduces exactly to the old progressive-only list.
 * Returns { variants, durationMs } — durationMs from the MPD, a fallback for
 * items that carry no video_duration (the lean xig_polaris_media shape).
 */
function buildInstagramItemVariants(item) {
    let progressive = Array.isArray(item.video_versions) && item.video_versions.length > 0
        ? buildInstagramVariants(item.video_versions, item.original_width || 0, item.original_height || 0)
        : [];

    // Old web-GraphQL node shape: a single `video_url` string + `dimensions`
    // (no video_versions array). parseInstagramQuery reads it under its two
    // known wrappers; building it here as well lets the shape WALK emit it
    // from any wrapper (see isInstagramMediaItem).
    if (progressive.length === 0 && typeof item.video_url === "string" && /^https?:\/\//.test(item.video_url)) {
        progressive = [{
            url: item.video_url,
            width: item.dimensions?.width || item.original_width || 0,
            height: item.dimensions?.height || item.original_height || 0
        }];
    }

    // api-v1 items carry the MPD at top level; GraphQL nodes nest it under
    // dash_info.
    const mpd = item.video_dash_manifest || item.dash_info?.video_dash_manifest;
    if (typeof mpd !== "string" || mpd.indexOf("<MPD") === -1) {
        return { variants: progressive, durationMs: 0 };
    }

    const dash = parseInstagramDashManifest(mpd);
    if (dash.videos.length === 0) {
        return { variants: progressive, durationMs: dash.durationMs };
    }

    const variants = dash.videos.map(v => {
        const variant = { url: v.url, width: v.width, height: v.height };
        if (dash.audio) variant.audioUrl = dash.audio.url;
        return variant;
    });

    const dashPaths = new Set(dash.videos.map(v => urlPath(v.url)));
    const dashHeights = new Set(dash.videos.map(v => v.height));
    for (const p of progressive) {
        if (dashPaths.has(urlPath(p.url))) continue;         // the video-only track
        if (p.height && dashHeights.has(p.height)) continue; // quality already covered
        variants.push(p);
    }

    log("IG-DASH", `manifest: ${dash.videos.length} video rendition(s), audio=${!!dash.audio}, +${variants.length - dash.videos.length} progressive, duration=${dash.durationMs}ms`);
    return { variants, durationMs: dash.durationMs };
}

/**
 * Extract and send videos from an Instagram media API item.
 * Returns the number of sendVariants emits (0 = the item carried no video),
 * so callers can decide whether a fallback extraction is still needed.
 */
function sendInstagramItem(details, item, originOverride) {
    // Field fallbacks span the two item families: api-v1/mobile (caption.text,
    // user.username, code) and old web-GraphQL nodes (edge_media_to_caption,
    // owner.username, shortcode). A code-less item still gets a stable origin
    // from its pk/id — origins are identity, not URLs to fetch.
    const videoText = item.caption?.text
        || item.edge_media_to_caption?.edges?.[0]?.node?.text || "";
    const author = item.user?.username || item.owner?.username || null;
    const code = item.code || item.shortcode;
    const origin = originOverride
        || `https://www.instagram.com/p/${code || item.pk || item.id}`;
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

    let sent = 0;

    const main = buildInstagramItemVariants(item);
    if (main.variants.length > 0) {
        log("IG-ITEM", `Sending ${main.variants.length} video variant(s)`, { code, firstUrl: main.variants[0]?.url?.slice(0, 80) });
        sendVariants(details, {
            variants: main.variants, origin, description: videoText, img, name: author,
            // The lean SSR item shape carries no video_duration — the MPD's
            // mediaPresentationDuration fills it in.
            duration: duration || main.durationMs
        });
        sent++;
    }

    if (item.carousel_media) {
        let carouselVideos = 0;
        for (const media of item.carousel_media) {
            const cv = buildInstagramItemVariants(media);
            if (cv.variants.length === 0) continue;
            carouselVideos++;
            const mediaImg = getInstagramThumbnail(media) || img;
            const mediaDuration = Math.round((media.video_duration || 0) * 1000);
            sendVariants(details, {
                variants: cv.variants, origin, description: videoText, img: mediaImg, name: author,
                duration: mediaDuration || cv.durationMs,
                // Per-CLIP dedup key: carousel slides all share the post's
                // origin, and sendVariants' origin-dedup dropped every video
                // after the first (HAR-verified: a 20-slide carousel with two
                // videos emitted one). The slide's pk is stable across
                // re-reads (the signed URL rotates, so it dedups a refresh
                // where the raw URL wouldn't); the URL path is the fallback.
                dedupKey: `${origin}#${media.pk || media.id || urlPath(cv.variants[0].url)}`
            });
            sent++;
        }
        log("IG-ITEM", `Carousel: ${carouselVideos} video(s) in ${item.carousel_media.length} slides`, { code });
    }

    if (sent === 0) {
        log("IG-ITEM", `Item has no downloadable video — nothing to send`, { code, mediaType: item.media_type });
    }

    return sent;
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
    // An item can be DASH-only (video_dash_manifest with no video_versions)
    if (typeof obj.video_dash_manifest === "string" && obj.video_dash_manifest.indexOf("<MPD") !== -1) {
        return true;
    }
    // Old web-GraphQL node shape: a bare video_url string (+ dimensions/
    // shortcode). Historically reachable only through parseInstagramQuery's
    // two hardcoded wrappers — matching the SHAPE here frees it from wrapper
    // churn like every other item form.
    if (typeof obj.video_url === "string" && /^https?:\/\//.test(obj.video_url)) return true;
    if (Array.isArray(obj.carousel_media)
        && obj.carousel_media.some(m =>
            (Array.isArray(m?.video_versions) && m.video_versions.length > 0)
            || (typeof m?.video_dash_manifest === "string" && m.video_dash_manifest.indexOf("<MPD") !== -1))) {
        return true;
    }
    return false;
}

function walkInstagramMediaItems(node, onItem, depth, seen, counter) {
    if (!node || typeof node !== "object" || depth > 40) return;
    if (counter.visited++ > 50000) return;
    if (seen.has(node)) return;
    seen.add(node);
    const matched = isInstagramMediaItem(node);
    if (matched) onItem(node);
    if (Array.isArray(node)) {
        for (const v of node) walkInstagramMediaItems(v, onItem, depth + 1, seen, counter);
    } else {
        for (const k in node) {
            // A matched item's carousel slides are emitted BY the item
            // (sendInstagramItem's carousel loop, per-slide dedupKey) — don't
            // also collect them as standalone items, which would double-emit
            // every slide under a second identity.
            if (matched && k === "carousel_media") continue;
            walkInstagramMediaItems(node[k], onItem, depth + 1, seen, counter);
        }
    }
}

// The same item is often inlined several times — a canonical record (user +
// caption + duration + thumbnails) and lean Relay fragments carrying only
// video_versions. Keep the richest candidate per code so metadata isn't lost
// to a lean copy.
function instagramItemRichness(item) {
    let score = 0;
    if (item?.user?.username || item?.owner?.username) score += 2;
    if (item?.caption?.text || item?.edge_media_to_caption?.edges?.length) score += 1;
    if (item?.video_duration) score += 1;
    if (item?.image_versions2?.candidates?.length) score += 1;
    if (Array.isArray(item?.carousel_media) && item.carousel_media.length) score += 1;
    return score;
}

// Stable identity for folding duplicate inlines of one clip. pk (the media
// id) is the canonical key — the rich record and the lean Relay fragments of
// the SAME clip all carry it, so richness folding works even when only one
// copy has the code. code/shortcode are the fallbacks (old GraphQL nodes);
// an item with none of these is dropped (nothing to key or attribute it by).
function instagramItemKey(item) {
    return item.pk || item.id || item.code || item.shortcode || null;
}

// Walk one parsed JSON value, folding every video item into bestByCode keyed
// on the item identity, keeping the richest record per clip.
function collectInstagramMediaItems(parsed, bestByCode) {
    walkInstagramMediaItems(parsed, (item) => {
        const key = instagramItemKey(item);
        if (!key) return;
        const prev = bestByCode.get(key);
        if (!prev || instagramItemRichness(item) > instagramItemRichness(prev)) {
            bestByCode.set(key, item);
        }
    }, 0, new WeakSet(), { visited: 0 });
}

/**
 * Shape-based catch-all: walk a whole parsed response for anything that IS a
 * media item, wherever the wrapper of the day nests it, and emit each. This
 * is the robustness backbone — the item shape (video_versions/carousel_media
 * + code) has been stable across Meta surfaces for years while the wrappers
 * (query names, gating objects, edge nesting) churn every few months; keying
 * on the shape means a renamed query can't lose the video. Runs AFTER the
 * specific handlers on every path: their emits land first (precise
 * metadata/origins win the repository race) and the sentOrigins dedup in
 * sendVariants collapses any overlap, so this can only ADD captures the
 * hand-coded shapes missed. Returns the number of items emitted.
 */
function walkAndSend(details, parsed, label) {
    const bestByCode = new Map();
    collectInstagramMediaItems(parsed, bestByCode);
    for (const item of bestByCode.values()) {
        sendInstagramItem(details, item);
    }
    if (bestByCode.size > 0) {
        log("IG-WALK", `${label}: ${bestByCode.size} media item(s) via shape walk`);
    }
    return bestByCode.size;
}

/**
 * Extract and send videos from an Instagram GraphQL response (the old web
 * GraphQL node shape — video_url + dimensions, NOT the mobile item shape the
 * generic walk matches). Returns the number of emits so callers can fall
 * back to the shape walk when this recognized nothing.
 */
function parseInstagramQuery(details, parsed) {
    let sent = 0;
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
                sent++;
            }
        } else if (shortcodeMedia.video_url) {
            sendVariants(details, {
                variants: [{ url: shortcodeMedia.video_url, width: shortcodeMedia.dimensions?.width || 0, height: shortcodeMedia.dimensions?.height || 0 }],
                origin, description: text, img, name: author, duration
            });
            sent++;
        }
        return sent;
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
            sent++;
        }
    }
    return sent;
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
            if (parseInstagramQuery(details, parsed) === 0) {
                // Query shape changed but media may still be in there somewhere
                walkAndSend(details, parsed, "shortcode fetch");
            }
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
            if (graphqlParsed && parseInstagramQuery(details, graphqlParsed) === 0) {
                walkAndSend(details, graphqlParsed, "mediaId fetch");
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

// Deliberately BROAD (the Threads-parser stance): all of graphql + the whole
// /api/ REST surface on EVERY instagram.com host, instead of a hand-kept
// endpoint/host list — a new endpoint name (the /api/v1/clips/-of-next-year)
// or a host shuffle (www → i → a future subdomain; `*.instagram.com` also
// matches the bare apex) must not be a capture miss. Over-matching is cheap:
// the filter passes bytes through unmodified and the shape walk ignores
// anything without a video. `/graphql*` covers /graphql, /graphql?…, and
// /graphql/query alike (the path glob matches across the query string).
const IG_API_PATTERNS = [
    "*://*.instagram.com/graphql*",
    "*://*.instagram.com/api/*"
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

        const parsed = tolerantParseJson(str);
        if (!parsed) {
            // Meta streams some GraphQL as newline-delimited JSON objects
            // (deferred @stream payloads — the Facebook/Threads pattern).
            // Whole-body parse fails on those; process each line instead.
            const lines = str.split("\n");
            let lineObjs = 0;
            for (const line of lines) {
                const obj = tolerantParseJson(line);
                if (obj) {
                    lineObjs++;
                    Promise.resolve().then(() => processFilteredInstagramResponse(details, url, obj));
                }
            }
            if (lineObjs === 0) {
                log("IG-FILTER", `JSON parse failed`, { firstChars: str.slice(0, 80) });
            } else {
                log("IG-FILTER", `NDJSON body: ${lineObjs} object(s)`, { url: url.slice(0, 80) });
            }
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

    // Emits recognized by the SPECIFIC handlers below. Whatever they miss,
    // the shape-based catch-all walk at the end picks up — the specific
    // handlers exist for precise metadata (and the old web-GraphQL node
    // shape the walk can't match), not as the only door.
    let found = 0;

    if (url.includes("graphql")) {
        log("IG-FILTER", `Routing: graphql`, {
            dataKeys: parsed.data ? Object.keys(parsed.data).join(", ") : "none",
            hasShortcodeMedia: !!parsed.data?.xdt_shortcode_media,
            hasTimeline: !!parsed.data?.user?.edge_owner_to_timeline_media,
            hasPrefetch: !!parsed.extensions?.all_video_dash_prefetch_representations
        });

        // Shortcode media or timeline (old GraphQL shape — video_url nodes,
        // which the item-shape walk can NOT match, so this stays load-bearing)
        found += parseInstagramQuery(details, parsed);

        // Scan all data keys for feed items (xdt_api__v1__feed, clips, etc.)
        // Skip prefetch — the actual video_versions are already in the feed edges
        if (parsed.data) {
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
            found += sendInstagramItem(details, item);
            log("IG-FILTER", `Media info: sent`, { code: item.code });
        }
    } else if (url.includes("/api/v1/")) {
        // Feed endpoints
        found += processInstagramFeedItems(details, parsed, url);
    }

    // Shape-based catch-all — the robustness backbone (see walkAndSend). Runs
    // on EVERY response, not only when found === 0: a response can mix a
    // recognized shape with a new wrapper the handlers miss, and the
    // sentOrigins dedup collapses re-emits of what the handlers already sent.
    walkAndSend(details, parsed, `api catch-all (${found} via handlers)`);
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
    return found;
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

        // Hedge against Meta renaming the data-sjs attribute: when that pass
        // yields nothing, rescan EVERY application/json script (superset of
        // the data-sjs set in today's markup). Post/reel pages only, and only
        // on a miss, so the extra JSON parses cost nothing in the common case.
        if (bestByCode.size === 0) {
            const jsonRegex = /<script[^>]*type="application\/json"[^>]*>([\s\S]*?)<\/script>/g;
            while ((m = jsonRegex.exec(html)) !== null) {
                scriptCount++;
                const parsed = tryParseJson(m[1]);
                if (parsed) collectInstagramMediaItems(parsed, bestByCode);
            }
        }

        log("IG-PAGE", `doc filter: ${bytes} bytes, ${scriptCount} script(s) scanned, ${bestByCode.size} video item(s)`, { url: url.slice(0, 100) });

        // A PERMALINK document addresses exactly one post — emit only the
        // item whose code matches the URL's shortcode. A logged-in reel/post
        // page preloads SUGGESTED/next clips into the same SSR blobs (drawn
        // from recent activity, so a clip just watched in another tab shows
        // up here), and capturing those attributes never-viewed videos to
        // this tab. Suggested reels the user actually swipes to arrive as
        // XHRs the API filter captures then. Feed/profile/explore documents
        // (no shortcode in the URL) keep capture-everything, and if NO item
        // matches the shortcode (a wrapper that renamed code), fall back to
        // emitting all — a miss is worse than an extra.
        let docItems = [...bestByCode.values()];
        if (shortcode) {
            const addressed = docItems.filter(it => (it.code || it.shortcode) === shortcode);
            if (addressed.length > 0 && addressed.length < docItems.length) {
                log("IG-PAGE", `permalink doc: ${docItems.length - addressed.length} non-addressed item(s) skipped`, { shortcode });
            }
            if (addressed.length > 0) docItems = addressed;
        }
        for (const item of docItems) {
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
//
// ALL www.instagram.com documents, not just /p/ and /reel/: the logged-in
// HOME FEED (and profile/explore pages) SSR-inline the first screenful of
// posts into the same data-sjs Relay blobs (HAR-verified 26-08-09: the feed
// document carries video items under xdt_api__v1__feed__timeline__connection
// — the scroll-time copies arrive as graphql XHRs the API filter reads, but
// the SSR ones exist NOWHERE else on the wire, and the fbcdn media is
// parser-block-listed, so a path-gated registration lost them entirely).
// Same broad-match stance as IG_API_PATTERNS: the walk ignores documents
// without media, and the GraphQL fetch fallback stays shortcode-gated so a
// feed/profile document (no shortcode in the URL) never fires it.
browser.webRequest.onBeforeRequest.addListener(
    listenerInstagramPage,
    { urls: ["*://*.instagram.com/*"], types: ["main_frame"] },
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
