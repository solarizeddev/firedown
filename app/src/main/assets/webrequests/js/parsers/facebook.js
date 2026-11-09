// Facebook parser.
import {
    log, isOwnRequest, sendVariants, registerSpaHandler, filterResponseText,
    decodeHtmlEntities, MetaCache, tabClaims, tabOfDetails,
} from './common.js';
import { parseJsValue } from './js-literal.js';
import { pickCaptureMeta } from '../requests.js';

// Facebook
// ----------------------------------------------------------------------------
// A video node is any object that carries one of the player's URL fields
// (playable_url, browser_native_hd_url, video_versions…). Four producers feed
// one emit, and all four read TEXT with the same scanner, never a JSON walk
// with a depth cap:
//
//   1. GraphQL XHRs (/api/graphql/, the desktop Comet app). Often NDJSON,
//      often prefixed with the `for (;;);` anti-hijack guard.
//   2. Bloks XHRs (/async/wbloks/fetch/, the mobile "weblite" app): the reels
//      viewer's pagination answers JSON holding the NEXT reels as video nodes
//      (`playable_url`, `video_versions`, `video_duration` in ms, `user`,
//      `caption`, `cover_image_url`, `story_url`).
//   3. The DOCUMENT. A mobile reel page (/reel/<id>/) renders its reel
//      server-side and fires no API call for it: the node sits in an inline
//      script as a JS OBJECT LITERAL (unquoted keys), so JSON.parse cannot
//      read it; parseJsValue can. Its og:video is the reel's SD rendition,
//      paired with the node by the asset id both URLs carry.
//   4. The WIRE backbone: the progressive mp4 the player fetches from
//      video*.fbcdn.net. The block rule (parser-blocklist.js `facebook`)
//      keeps the generic catcher off that host, so a video the three
//      readers above never saw (an embed whose page we did not read, a
//      cached document, a renamed field) would otherwise be lost. It is
//      titled from what the readers cached for the same asset, else from the
//      player frame's own metadata, else "Facebook video". DASH tracks are
//      never captured here (a video-only track is a silent file).
//
// Caveats worth knowing:
//   - Many videos are login-walled. A gated response simply has no node.
//   - GraphQL operation names rotate; every response is scanned and the
//     node shape decides.
// ============================================================================

const FB_API_PATTERNS = [
    "*://*.facebook.com/api/graphql/*",
    "*://*.facebook.com/api/graphql",
    "*://*.facebook.com/async/wbloks/fetch/*",
];

const FB_DOC_PATTERNS = ["*://*.facebook.com/*"];

const FB_WIRE_PATTERNS = ["*://*.fbcdn.net/*"];

const FB_HD_FIELDS = ["playable_url_quality_hd", "browser_native_hd_url"];
const FB_SD_FIELDS = ["playable_url", "browser_native_sd_url"];
const FB_DASH_URL_FIELDS = ["playable_url_dash_hd", "playable_url_dash"];

// The keys that make an object a video node (scanner + node test agree).
const FB_NODE_KEYS = new Set([...FB_HD_FIELDS, ...FB_SD_FIELDS, ...FB_DASH_URL_FIELDS, "video_versions"]);

// A cheap gate before any scan: a body that names none of these holds no node.
const FB_MARKER_RE = /playable_url|browser_native_(?:hd|sd)_url|video_versions/;

const FB_WIRE_HOST_RE = /^video[.-][^/]*\.fbcdn\.net$/;

// Bounds on one scan: object starts collected, characters read.
const FB_MAX_NODES_PER_BODY = 64;
const FB_MAX_SCAN_CHARS = 8 * 1024 * 1024;

// How long the wire backbone waits for a reader still parsing a body in the
// same tab before falling back to the frame's metadata.
let fbWireGraceMs = 3000;
const FB_FRAME_META_MS = 1500;

// Metadata about a VIDEO (true in every tab): asset id -> { node, extraUrls }.
const fbAssetCache = new MetaCache(256, 30 * 60 * 1000);

function fbPending(tabId) {
    return tabClaims(tabId, "fb-pending", 20 * 1000, 32);
}

function fbWireClaims(tabId) {
    return tabClaims(tabId, "fb-wire", 30 * 1000, 128);
}

function hasLive(claims) {
    for (const _ of claims.keys()) return true;
    return false;
}

function nonEmpty(s) {
    return typeof s === "string" && s.length > 0;
}

// Does this object look like a Facebook video node? One of the known URL
// fields being a non-empty string, or a video_versions list with a URL.
function isFacebookVideoNode(obj) {
    if (!obj || typeof obj !== "object" || Array.isArray(obj)) return false;
    for (const f of FB_HD_FIELDS) if (nonEmpty(obj[f])) return true;
    for (const f of FB_SD_FIELDS) if (nonEmpty(obj[f])) return true;
    for (const f of FB_DASH_URL_FIELDS) if (nonEmpty(obj[f])) return true;
    const versions = obj.video_versions;
    if (Array.isArray(versions)) {
        for (const v of versions) if (nonEmpty(v?.url)) return true;
    }
    return false;
}

// Keywords after which a `/` starts a regex literal, not a division.
const REGEX_AFTER_WORDS = new Set(["return", "typeof", "case", "in", "of", "do", "else", "void",
    "delete", "throw", "new", "instanceof", "yield", "await"]);

// ---- The scanner ----------------------------------------------------------
// One forward pass over JS or JSON text, tracking strings, comments, regex
// literals and the open {/[ stack. Every `{` that DIRECTLY holds one of the
// node keys (quoted or not, followed by `:`) is recorded; each is then read
// with parseJsValue. A node serialized INTO a string (a JSON payload kept
// as text) is skipped with the string; the wire backbone covers it. A
// depth-capped walk of the parsed tree was how the
// Threads parser missed items two levels too deep; this has no depth.
function findNodeStarts(src, out) {
    const stack = [];
    const n = Math.min(src.length, FB_MAX_SCAN_CHARS);
    let lastSig = "";       // last significant char outside strings/comments
    let lastWord = "";      // the identifier that char ended, if any
    let i = 0;
    const isIdStart = (c) => (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") || c === "_" || c === "$";
    const isId = (c) => isIdStart(c) || (c >= "0" && c <= "9");
    const followedByColon = (j) => {
        while (j < n && (src[j] === " " || src[j] === "\n" || src[j] === "\t" || src[j] === "\r")) j++;
        return src[j] === ":";
    };
    const record = () => {
        const top = stack[stack.length - 1];
        if (top && top.ch === "{" && !top.recorded) {
            top.recorded = true;
            out.push(top.at);
        }
    };
    while (i < n && out.length < FB_MAX_NODES_PER_BODY) {
        const c = src[i];
        if (c === '"' || c === "'" || c === "`") {
            const start = i + 1;
            i++;
            while (i < n && src[i] !== c) {
                if (src[i] === "\\") i++;
                i++;
            }
            const end = i;
            i++;
            if (end - start <= 40 && FB_NODE_KEYS.has(src.slice(start, end)) && followedByColon(i)) record();
            lastSig = c;
            lastWord = "";
            continue;
        }
        if (c === "/") {
            const next = src[i + 1];
            if (next === "/") {
                while (i < n && src[i] !== "\n") i++;
                continue;
            }
            if (next === "*") {
                const close = src.indexOf("*/", i + 2);
                i = close === -1 ? n : close + 2;
                continue;
            }
            // A regex literal can only start where a value can.
            if (lastSig === "" || "(,=:[!&|?{};".includes(lastSig) || REGEX_AFTER_WORDS.has(lastWord)) {
                i++;
                let inClass = false;
                while (i < n && src[i] !== "\n") {
                    const r = src[i];
                    if (r === "\\") { i += 2; continue; }
                    if (r === "[") inClass = true;
                    else if (r === "]") inClass = false;
                    else if (r === "/" && !inClass) break;
                    i++;
                }
                i++;
                lastSig = "/";
                lastWord = "";
                continue;
            }
            lastSig = c;
            lastWord = "";
            i++;
            continue;
        }
        if (c === "{" || c === "[") {
            stack.push({ ch: c, at: i, recorded: false });
            lastSig = c;
            lastWord = "";
            i++;
            continue;
        }
        if (c === "}" || c === "]") {
            stack.pop();
            lastSig = c;
            lastWord = "";
            i++;
            continue;
        }
        if (isIdStart(c)) {
            let j = i + 1;
            while (j < n && isId(src[j])) j++;
            if (j - i <= 40 && FB_NODE_KEYS.has(src.slice(i, j)) && followedByColon(j)) record();
            lastSig = src[j - 1];
            lastWord = src.slice(i, j);
            i = j;
            continue;
        }
        if (c !== " " && c !== "\n" && c !== "\t" && c !== "\r") { lastSig = c; lastWord = ""; }
        i++;
    }
    return out;
}

function nodesFromText(src) {
    const nodes = [];
    if (!FB_MARKER_RE.test(src)) return nodes;
    const starts = findNodeStarts(src, []);
    for (const at of starts) {
        let v;
        try { v = parseJsValue(src, { i: at, R: {} }); } catch (_) { continue; }
        if (isFacebookVideoNode(v)) nodes.push(v);
    }
    return nodes;
}

// Every inline <script> body of a document that names a node key.
function documentScripts(html) {
    const out = [];
    const re = /<script\b[^>]*>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const start = m.index + m[0].length;
        const end = html.indexOf("</script>", start);
        if (end <= start) continue;
        const body = html.slice(start, end);
        if (FB_MARKER_RE.test(body)) out.push(body);
        re.lastIndex = end;
    }
    return out;
}

// The og:video URLs a document declares (the reel's SD rendition).
function documentOgVideos(html) {
    const out = [];
    const re = /<meta\b[^>]*(?:property|name)="og:video(?::secure_url|:url)?"[^>]*>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const c = m[0].match(/\bcontent="([^"]+)"/i);
        if (!c) continue;
        const url = decodeHtmlEntities(c[1]);
        if (/^https:\/\//.test(url) && !out.includes(url)) out.push(url);
    }
    return out;
}

function collectFacebookDocVideos(html) {
    const nodes = [];
    for (const script of documentScripts(html)) {
        for (const node of nodesFromText(script)) nodes.push(node);
    }
    return { nodes, ogVideos: documentOgVideos(html) };
}

function collectFacebookBodyVideos(body) {
    let str = body;
    if (str.startsWith("for (;;);")) str = str.slice(9);
    return nodesFromText(str);
}

// ---- What a fbcdn video URL says about itself -----------------------------
// `efg` is base64url JSON: { vencode_tag, xpv_asset_id, duration_s, … }.
// vencode_tag "xpv_progressive.FACEBOOK..C3.720.dash_h264-basic-gen2_720p":
// a progressive file whose short side is 720.
function fbcdnInfo(url) {
    let u;
    try { u = new URL(url); } catch (_) { return null; }
    const efg = u.searchParams.get("efg");
    const info = { assetId: null, tag: "", durationS: 0, path: u.pathname, ranged: u.searchParams.has("bytestart") };
    if (!efg) return info;
    try {
        const b64 = efg.replace(/-/g, "+").replace(/_/g, "/");
        const json = JSON.parse(atob(b64 + "===".slice((b64.length + 3) % 4)));
        if (json.xpv_asset_id != null) info.assetId = String(json.xpv_asset_id);
        if (typeof json.vencode_tag === "string") info.tag = json.vencode_tag;
        if (typeof json.duration_s === "number") info.durationS = json.duration_s;
    } catch (_) {}
    return info;
}

function isProgressiveTag(tag) {
    if (!tag) return true;                       // no tag: judged by the URL alone
    if (tag.startsWith("xpv_progressive")) return true;
    return !/dash|audio/i.test(tag);
}

function evenRound(x) {
    return Math.round(x / 2) * 2;
}

// A rendition's frame size from its tag's short side and the node's aspect
// (width / height). No aspect: the short side as the height.
function sizeFromTag(tag, aspect) {
    const m = (tag || "").match(/\.C\d+\.(\d{3,4})\./) || (tag || "").match(/_(\d{3,4})p\b/);
    if (!m) return null;
    const short = Number(m[1]);
    if (aspect > 0 && aspect < 1) return { width: short, height: evenRound(short / aspect) };
    if (aspect >= 1) return { width: evenRound(short * aspect), height: short };
    return { width: 0, height: short };
}

function nodeAspect(video) {
    if (typeof video.aspect_ratio === "number" && video.aspect_ratio > 0) return video.aspect_ratio;
    const w = video.original_width || video.width;
    const h = video.original_height || video.height;
    if (w > 0 && h > 0) return w / h;
    return 0;
}

function nodeAssetIds(video) {
    const ids = new Set();
    for (const url of nodeUrls(video)) {
        const id = fbcdnInfo(url)?.assetId;
        if (id) ids.add(id);
    }
    return ids;
}

function nodeUrls(video) {
    const urls = [];
    for (const f of [...FB_HD_FIELDS, ...FB_SD_FIELDS]) if (nonEmpty(video[f])) urls.push(video[f]);
    if (Array.isArray(video.video_versions)) {
        for (const v of video.video_versions) if (nonEmpty(v?.url)) urls.push(v.url);
    }
    return urls;
}

// ---- Metadata --------------------------------------------------------------
function parseFacebookVideoUrl(url) {
    if (!url) return null;
    let m = url.match(/facebook\.com\/watch\/?\?v=(\d+)/);
    if (m) return { type: "watch", videoId: m[1] };
    m = url.match(/facebook\.com\/(?:[A-Za-z0-9.]+\/)?videos\/(?:[A-Za-z0-9._-]+\/)?(\d+)/);
    if (m) return { type: "video", videoId: m[1] };
    m = url.match(/facebook\.com\/reel\/(\d+)/);
    if (m) return { type: "reel", videoId: m[1] };
    m = url.match(/fb\.watch\/([A-Za-z0-9_-]+)/);
    if (m) return { type: "fbwatch", code: m[1] };
    return null;
}

// The video's own page: the node's story_url (a reel's /reel/<id>/, the URL
// the tab shows while it plays), else the watch page of its id.
function canonicalFacebookOrigin(video, fallbackUrl) {
    const story = video?.story_url;
    if (nonEmpty(story) && /^https:\/\/(?:www|m|web)\.facebook\.com\//.test(story)) return story;
    const videoId = video?.id || video?.video_id || video?.videoId;
    if (videoId) return `https://www.facebook.com/watch/?v=${videoId}`;
    return fallbackUrl || "https://www.facebook.com/";
}

function extractFacebookOwnerName(video) {
    return video?.owner?.name
        || video?.user?.name
        || video?.owner?.username
        || video?.creation_story?.actors?.[0]?.name
        || null;
}

function extractFacebookThumbnail(video) {
    return video?.preferred_thumbnail?.image?.uri
        || video?.cover_image_url
        || video?.thumbnailImage?.uri
        || video?.image?.uri
        || video?.thumbnail?.uri
        || video?.first_frame_thumbnail
        || null;
}

function extractFacebookText(video) {
    return video?.title?.text
        || video?.caption?.text
        || video?.savable_description?.text
        || video?.message?.text
        || "";
}

// A clip is titled by its own words (its title, else its caption's first
// line), not by its author: an account's reels would otherwise all read the
// same. The author is then the description; a clip with no words takes the
// author's name.
function facebookTitle(video) {
    const firstLine = String(extractFacebookText(video)).split(/\n/).map(s => s.trim()).find(Boolean) || "";
    let title = firstLine.replace(/\s+/g, " ");
    if (title.length > 120) {
        const cut = title.slice(0, 120);
        const space = cut.lastIndexOf(" ");
        title = (space > 60 ? cut.slice(0, space) : cut) + "…";
    }
    const owner = extractFacebookOwnerName(video);
    if (title) return { name: title, description: owner || "" };
    return { name: owner || video?.savable_title || null, description: "" };
}

function facebookDuration(video, urls) {
    let ms = video.playable_duration_in_ms
        || (video.length_in_second ? video.length_in_second * 1000 : 0)
        || 0;
    if (!ms && typeof video.video_duration === "number" && video.video_duration > 0) {
        // The mobile app's node gives milliseconds (47850 for a 47 s reel).
        ms = video.video_duration >= 1000 ? video.video_duration : video.video_duration * 1000;
    }
    if (!ms) {
        for (const url of urls) {
            const s = fbcdnInfo(url)?.durationS;
            if (s > 0) { ms = s * 1000; break; }
        }
    }
    return Math.round(ms);
}

// ---- Emit --------------------------------------------------------------------
// The node's renditions, best first: HD and SD fields, video_versions, plus
// extra URLs of the same asset (a document's og:video). One per file (path)
// and per frame size. Sizes come from each URL's own tag; a URL without one
// keeps the field's nominal height.
function facebookVariants(video, extraUrls = []) {
    const aspect = nodeAspect(video);
    const out = [];
    const seenPaths = new Set();
    const seenSizes = new Set();
    const add = (url, nominal) => {
        if (!nonEmpty(url)) return;
        const info = fbcdnInfo(url);
        const path = info?.path || url.split("?")[0];
        if (seenPaths.has(path)) return;
        seenPaths.add(path);
        const size = sizeFromTag(info?.tag, aspect) || { width: 0, height: nominal };
        const sizeKey = `${size.width}x${size.height}`;
        if (size.height > 0 && seenSizes.has(sizeKey)) return;
        seenSizes.add(sizeKey);
        out.push({ url, width: size.width, height: size.height });
    };
    for (const f of FB_HD_FIELDS) add(video[f], 1080);
    for (const f of FB_SD_FIELDS) add(video[f], 480);
    if (Array.isArray(video.video_versions)) {
        for (const v of video.video_versions) add(v?.url, 0);
    }
    for (const url of extraUrls) add(url, 0);
    return out;
}

// Keyed by asset id, and by file path for a URL with no efg (the played URL
// can differ from the node's only by its signature parameters).
function rememberFacebookNode(video, extraUrls) {
    const entry = { video, extraUrls };
    for (const id of nodeAssetIds(video)) fbAssetCache.set(id, entry);
    for (const url of nodeUrls(video)) {
        const info = fbcdnInfo(url);
        if (info && !info.assetId) fbAssetCache.set(info.path, entry);
    }
}

function sendFacebookVideo(details, video, pageUrl, extraUrls = []) {
    let variants = facebookVariants(video, extraUrls);
    let manifest = false;

    // DASH manifest fallback: the native side muxes a manifest itself.
    if (variants.length === 0) {
        const dashUrl = video.playable_url_dash_hd || video.playable_url_dash;
        if (nonEmpty(dashUrl)) {
            variants = [{ url: dashUrl, width: 0, height: 0 }];
            manifest = true;
        }
    }
    if (variants.length === 0) return false;

    rememberFacebookNode(video, extraUrls);
    const origin = canonicalFacebookOrigin(video, pageUrl);
    const { name, description } = facebookTitle(video);
    sendVariants(details, {
        variants,
        origin,
        description,
        img: extractFacebookThumbnail(video),
        name,
        duration: facebookDuration(video, variants.map(v => v.url)),
        manifest,
    });
    return true;
}

function emitNodes(details, url, nodes, ogVideos = []) {
    let emitted = 0;
    const seen = new Set();
    for (const node of nodes) {
        const key = node.id || node.video_id || nodeUrls(node)[0];
        if (key && seen.has(key)) continue;
        if (key) seen.add(key);
        // og:video belongs to the node whose asset it names.
        const ids = nodeAssetIds(node);
        const extra = ogVideos.filter(u => ids.has(fbcdnInfo(u)?.assetId));
        if (sendFacebookVideo(details, node, url, extra)) emitted++;
    }
    return emitted;
}

function processFacebookApiBody(details, url, body) {
    const nodes = collectFacebookBodyVideos(body);
    const emitted = emitNodes(details, url, nodes);
    if (nodes.length) log("FB-FILTER", `${nodes.length} node(s), emitted ${emitted}`, { url: url.slice(0, 80) });
}

function processFacebookDocument(details, url, html) {
    const { nodes, ogVideos } = collectFacebookDocVideos(html);
    const emitted = emitNodes(details, url, nodes, ogVideos);
    if (nodes.length) log("FB-DOC", `${nodes.length} node(s), emitted ${emitted}`, { url: url.slice(0, 80) });
}

// Open a write-through filter on a response and run `process` on its text;
// the tab's pending set holds the request until it is read, so the wire
// backbone can wait for it.
function readFacebookResponse(details, process) {
    const pending = fbPending(tabOfDetails(details));
    pending.add(details.requestId);
    const armed = filterResponseText(details, (text) => {
        try {
            if (text && FB_MARKER_RE.test(text)) process(text);
        } catch (e) {
            log("FB-FILTER", `extraction failed`, e.message);
        } finally {
            pending.release(details.requestId);
        }
    });
    if (!armed) pending.release(details.requestId);
}

function listenerFacebookApiFilter(details) {
    if (isOwnRequest(details.url)) return {};
    readFacebookResponse(details, (text) => processFacebookApiBody(details, details.url, text));
    return {};
}

function listenerFacebookDocument(details) {
    if (isOwnRequest(details.url)) return {};
    readFacebookResponse(details, (text) => processFacebookDocument(details, details.url, text));
    return {};
}

// ---- Wire backbone ---------------------------------------------------------
function isMetaSibling(url) {
    try {
        const h = new URL(url).hostname;
        return /(^|\.)instagram\.com$/.test(h) || /(^|\.)threads\.(net|com)$/.test(h);
    } catch (_) {
        return false;
    }
}

async function facebookFrameMeta(details, tabId) {
    if (typeof tabId !== "number" || tabId < 0) return null;
    const frameId = typeof details.frameId === "number" ? details.frameId : 0;
    let page = null;
    try {
        page = await Promise.race([
            browser.tabs.sendMessage(tabId, { kind: "get-page-metadata", mediaUrl: details.url }, { frameId }),
            new Promise((resolve) => setTimeout(() => resolve(null), FB_FRAME_META_MS)),
        ]);
    } catch (_) {
        page = null;
    }
    if (!page || typeof page !== "object") return null;
    const picked = pickCaptureMeta(page);
    return {
        name: picked.name || undefined,
        description: picked.description || undefined,
        img: picked.img || undefined,
        url: typeof page.url === "string" ? page.url : undefined,
    };
}

async function waitForFacebookAsset(key, tabId) {
    const deadline = Date.now() + fbWireGraceMs;
    while (Date.now() < deadline) {
        const hit = fbAssetCache.get(key);
        if (hit) return hit;
        if (!hasLive(fbPending(tabId))) return null;
        await new Promise((r) => setTimeout(r, 100));
    }
    return fbAssetCache.get(key) || null;
}

async function captureFacebookWire(details, tabId, info) {
    const pageUrl = details.documentUrl || details.originUrl || "";
    const key = info.assetId || info.path;
    let hit = fbAssetCache.get(key);
    if (!hit) hit = await waitForFacebookAsset(key, tabId);
    if (hit) {
        // Emitted under the node's own origin, so a reader's earlier emit
        // in this tab already covers it (the origin dedup).
        log("FB-WIRE", `played video titled from its node`, { asset: info.assetId });
        sendFacebookVideo(details, hit.video, pageUrl, [...hit.extraUrls, details.url]);
        return;
    }
    const frame = await facebookFrameMeta(details, tabId);
    const parsedPage = parseFacebookVideoUrl(pageUrl);
    const origin = parsedPage?.type === "reel"
        ? `https://www.facebook.com/reel/${parsedPage.videoId}/`
        : (pageUrl || frame?.url || details.url);
    const size = sizeFromTag(info.tag, 0);
    log("FB-WIRE", `played video with no node`, { asset: info.assetId, url: details.url.slice(0, 90) });
    sendVariants(details, {
        variants: [{ url: details.url, width: size?.width || 0, height: size?.height || 0 }],
        origin,
        name: frame?.name || "Facebook video",
        description: frame?.description,
        img: frame?.img,
        duration: info.durationS > 0 ? info.durationS * 1000 : 0,
        dedupKey: `${origin}#${key}`,
        // A reader that finishes after the wait names the same file; the
        // repository then renames this copy.
        pageTitled: true,
    });
}

// The progressive file as the player fetches it. Observe-only; one decision
// per (tab, asset).
function listenerFacebookWire(details) {
    if (details.tabId < 0) return {};                 // our own probes / downloads
    if (isOwnRequest(details.url)) return {};
    let host;
    try { host = new URL(details.url).hostname; } catch (_) { return {}; }
    if (!FB_WIRE_HOST_RE.test(host)) return {};
    if (!/\.mp4(?:$|\?)/.test(details.url.split("#")[0])) return {};
    if (isMetaSibling(details.documentUrl) || isMetaSibling(details.originUrl)) return {};
    const info = fbcdnInfo(details.url);
    if (!info || info.ranged || !isProgressiveTag(info.tag)) return {};
    const tabId = tabOfDetails(details);
    if (!fbWireClaims(tabId).claim(info.assetId || info.path)) return {};
    captureFacebookWire(details, tabId, info).catch((e) => log("FB-WIRE", `capture failed`, e.message));
    return {};
}

function checkAndProcessFacebookUrl(url, tabId) {
    if (!url) return;
    if (!url.includes("facebook.com") && !url.includes("fb.watch")) return;
    const parsed = parseFacebookVideoUrl(url);
    if (!parsed) return;
    // Extraction is response-driven (the filters above); this only logs.
    log("FB-PAGE", `video page detected`, { parsed, url: url.slice(0, 80), tabId });
}

browser.webRequest.onBeforeRequest.addListener(
    listenerFacebookApiFilter,
    { urls: FB_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerFacebookDocument,
    { urls: FB_DOC_PATTERNS, types: ["main_frame", "sub_frame"] },
    ["blocking"]
);

browser.webRequest.onBeforeRequest.addListener(
    listenerFacebookWire,
    { urls: FB_WIRE_PATTERNS, types: ["media", "xmlhttprequest", "other"] },
    []
);

// ============================================================================

registerSpaHandler(checkAndProcessFacebookUrl);

function __setFacebookWireGraceMs(ms) {
    fbWireGraceMs = ms;
}

// Exported for the replays (the real extractors on real bytes).
export {
    collectFacebookDocVideos, collectFacebookBodyVideos, facebookVariants, facebookTitle,
    fbcdnInfo, __setFacebookWireGraceMs,
};
