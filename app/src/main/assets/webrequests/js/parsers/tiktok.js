// TikTok parser (filterResponseData: item_list feeds + document SSR) — split
// verbatim out of the former parser-background.js.
import { log, tryParseJson, sendVariants, filterResponseText, cookieQueryForTab } from './common.js';

// ============================================================================
// TikTok
// ============================================================================

// Strip ?refer=embed from TikTok profile/video URLs before the page
// loads. With refer=embed present, TikTok's frontend renders the
// embed-preview layout and skips /api/post/item_list/ entirely — only
// /api/preload/item_list/ (FYP cold-start) fires, so the profile
// owner's actual posts never become visible to the capture hook.
// Removing the query param makes TikTok render the full profile and
// triggers the normal post-grid fetch.
browser.webRequest.onBeforeRequest.addListener(
    (details) => {
        if (details.type !== "main_frame") return {};
        try {
            const u = new URL(details.url);
            if (!u.hostname.endsWith("tiktok.com")) return {};
            if (!u.searchParams.has("refer")) return {};
            const refer = u.searchParams.get("refer");
            if (refer !== "embed" && refer !== "embeded") return {};
            u.searchParams.delete("refer");
            const clean = u.toString();
            log("TIKTOK", "stripping refer=embed", { from: details.url.slice(0, 100), to: clean.slice(0, 100) });
            return { redirectUrl: clean };
        } catch (_) {
            return {};
        }
    },
    { urls: ["*://www.tiktok.com/*", "*://m.tiktok.com/*"], types: ["main_frame"] },
    ["blocking"]
);

// Build the header set that lets v*-webapp-prime.tiktok.com /video/
// URLs replay successfully from the native downloader. Mirrors what
// Firefox itself sends on the page-driven media fetch (captured via
// the webrequests path): Origin/Referer/Sec-Fetch-* and — crucially
// — Cookie, which carries tt_chain_token (the URL's `tk=` param names
// this cookie as the auth source, so without it TikTok 403s).
async function buildTikTokHeaders(tabId) {
    let cookieHeader = "";
    let cookieCount = 0;
    try {
        // The jar of the TAB the item came from (a private tab's download must
        // not carry the regular session's tt_chain_token).
        const cookies = await browser.cookies.getAll(await cookieQueryForTab(tabId, { domain: "tiktok.com" }));
        cookieCount = cookies.length;
        cookieHeader = cookies.map(c => `${c.name}=${c.value}`).join("; ");
    } catch (e) {
        log("TIKTOK", `cookies.getAll failed`, e.message);
    }
    log("TIKTOK", `built headers`, { cookies: cookieCount, cookieLen: cookieHeader.length, ua: navigator.userAgent.slice(0, 60) });
    return [
        { name: "User-Agent",     value: navigator.userAgent },
        { name: "Accept",         value: "*/*" },
        { name: "Accept-Language", value: "en-US,en;q=0.9" },
        { name: "Origin",         value: "https://www.tiktok.com" },
        { name: "Referer",        value: "https://www.tiktok.com/" },
        { name: "Sec-Fetch-Dest", value: "empty" },
        { name: "Sec-Fetch-Mode", value: "cors" },
        { name: "Sec-Fetch-Site", value: "same-site" },
        { name: "Connection",     value: "keep-alive" },
        { name: "Cookie",         value: cookieHeader }
    ];
}

// Processes one item_list JSON body, whatever its source. Source-agnostic
// because two producers feed it the SAME video-item shape:
//   * the wire filterResponseData listener (below) — the feed/grid feeds
//     (FYP, profile, hashtag/challenge, /related/, /newtab/);
//   * the main_frame document filterResponseData listener (below) — SSR
//     items inlined in the page HTML with no item_list XHR: /@user/video/<id>
//     detail pages, and the /foryou feed's SSR'd FIRST video (the item_list
//     XHRs carry only the rest, so the wire filter never sees the first one).
// origin-dedup (sendVariants → canonical origin) collapses any overlap.
//
// Why the wire path is filterResponseData and not a refetch or the old
// page-world inject:
//   1. A refetch of the item_list URL trips TikTok's single-use
//      msToken / X-Bogus signature → stripped response. filterResponseData
//      reads the page's OWN response passively, no refetch.
//   2. ServiceWorker-served endpoints (/related/item_list/) were once
//      untappable by filterResponseData — FIXED by the geckoview
//      ServiceWorker-visibility patch (0006), which is what let the
//      inject be retired.
//   3. The historical fear that filterResponseData perturbs the stream
//      into a "Something went wrong" overlay did NOT reproduce on-device
//      with byte-exact write-through (see filterResponseText).
async function handleTikTokItemList({ url, body, tabId, pageUrl }) {
    // Empty body slips through (preflight / cache-warm). Treat it as the
    // no-op it is without logging "parse failed" (misleading: there's
    // nothing to parse, not a malformed JSON).
    if (!body) return;
    if (tabId === undefined || tabId === null) tabId = -1;
    if (!pageUrl) pageUrl = "https://www.tiktok.com/";

    log("TIKTOK", `item_list body`, {
        url: (url || "").slice(0, 120),
        bodyLen: body.length,
        tabId,
        tabUrl: pageUrl.slice(0, 80)
    });

    const json = tryParseJson(body);
    if (!json) {
        log("TIKTOK", `JSON parse failed`, { head: body.slice(0, 200) });
        return;
    }

    // /api/preload/item_list/ and the various /api/*/item_list/
    // endpoints don't share a single response shape. Try the common
    // keys first, then fall back to a deep-walk for the first
    // video-bearing array.
    let items = null;
    let itemsSource = null;
    const candidates = [
        ['itemList', json.itemList],
        ['aweme_list', json.aweme_list],
        ['data.itemList', json?.data?.itemList],
        ['data.aweme_list', json?.data?.aweme_list],
        ['itemListResponse.itemList', json?.itemListResponse?.itemList],
        ['videos', json.videos],
    ];
    for (const [src, val] of candidates) {
        if (Array.isArray(val) && val.length > 0) {
            items = val;
            itemsSource = src;
            break;
        }
    }
    if (!items) {
        // Deep-walk: pick the first array whose first element looks
        // like a TikTok video item.
        const seen = new WeakSet();
        const walk = (obj, depth, path) => {
            if (items || !obj || typeof obj !== 'object' || depth > 6) return;
            if (seen.has(obj)) return;
            seen.add(obj);
            if (Array.isArray(obj)) {
                const first = obj[0];
                if (first && typeof first === 'object' && first.video
                        && (first.video.playAddr || first.video.downloadAddr
                            || first.video.bitrateInfo)) {
                    items = obj;
                    itemsSource = 'deep:' + path;
                    return;
                }
                for (let i = 0; i < obj.length && !items; i++) {
                    walk(obj[i], depth + 1, path + '[' + i + ']');
                }
            } else {
                for (const k of Object.keys(obj)) {
                    if (items) break;
                    walk(obj[k], depth + 1, path + '.' + k);
                }
            }
        };
        walk(json, 0, '$');
    }

    if (!Array.isArray(items)) {
        log("TIKTOK", `no itemList[] in body`, { topKeys: Object.keys(json).slice(0, 12), bodyLen: body.length });
        return;
    }
    if (items.length === 0) {
        log("TIKTOK", `empty itemList[]`, { source: itemsSource });
        return;
    }
    log("TIKTOK", "items found", { count: items.length, source: itemsSource, firstId: items[0] && items[0].id });

    const pathname = (() => {
        try { return new URL(url, pageUrl).pathname; }
        catch (_) { return url; }
    })();
    log("TIKTOK", `${items.length} item(s) from ${pathname}`);

    const headers = await buildTikTokHeaders(tabId);

    let sentCount = 0;
    let skippedNoVariants = 0;
    let skippedNoVideo = 0;
    for (const item of items) {
        const v = item?.video;
        if (!v) { skippedNoVideo++; continue; }

        const author = item.author?.uniqueId || item.author?.nickname;
        const caption = (item.desc || "").split("\n")[0].slice(0, 140);
        const canonical = author && item.id
            ? `https://www.tiktok.com/@${author}/video/${item.id}`
            : pageUrl;

        const variants = [];
        if (Array.isArray(v.bitrateInfo)) {
            for (const b of v.bitrateInfo) {
                const list = b?.PlayAddr?.UrlList;
                if (!Array.isArray(list) || list.length === 0) continue;
                variants.push({
                    url: list[0],
                    width: b.PlayAddr?.Width || v.width || 0,
                    height: b.PlayAddr?.Height || v.height || 0,
                    bitrate: b.Bitrate || 0,
                    videoCodec: "h264"
                });
            }
        }
        if (variants.length === 0 && (v.playAddr || v.downloadAddr)) {
            variants.push({
                url: v.playAddr || v.downloadAddr,
                width: v.width || 0,
                height: v.height || 0,
                videoCodec: "h264"
            });
        }
        if (variants.length === 0) { skippedNoVariants++; continue; }

        log("TIKTOK", `item -> sendVariants`, {
            id: item.id,
            author,
            variants: variants.length,
            topUrl: variants[0].url.slice(0, 80),
            name: caption.slice(0, 60)
        });

        // Synthetic details object: sendVariants only reads tabId,
        // requestId, documentUrl, originUrl, and url.
        const details = {
            tabId,
            documentUrl: pageUrl,
            originUrl: pageUrl,
            url: url,
            requestId: `tiktok-${item.id || Date.now()}`
        };

        sendVariants(details, {
            variants,
            origin: canonical,
            description: author ? "@" + author : undefined,
            img: v.cover || v.originCover,
            name: caption || (author ? `TikTok by @${author}` : "TikTok video"),
            duration: typeof v.duration === "number" ? v.duration * 1000 : 0,
            requestHeaders: headers
        });
        sentCount++;
    }
    log("TIKTOK", `batch done`, { sent: sentCount, skippedNoVideo, skippedNoVariants, total: items.length });
}

// Document source — the SSR-inlined item on a /@user/video/<id> DETAIL page.
// Detail pages inline the video into the page document's
// __UNIVERSAL_DATA_FOR_REHYDRATION__ blob (under webapp.video-detail /
// webapp.reflow.video.detail) and fire NO /api/*item_list/ XHR, so there's
// nothing else to tap. (The /foryou feed does NOT SSR video — proven on-device,
// its blob holds only app/i18n/biz/seo scopes — so this runs on detail pages
// ONLY; the FYP first video is cache-served off-wire and comes from the generic
// catcher, see the TikTok note in regex.js. Don't re-add a /foryou branch here.)
//
// We read the DOCUMENT response (not the DOM) so the raw bytes are immune to
// React stripping the rehydration <script> during hydration — the Threads "read
// the network response, not the DOM" lesson. If TikTok's document is itself
// SW-synthesized, the 0006 patch is what makes this main_frame response tappable.
// (Cached/bfcache navigations serve no network response so this won't fire —
// acceptable: the item was captured on its first networked load.)
//
// Attribute-order-agnostic: TikTok emits the id either first or after
// type="application/json", and quoting can vary — so match any <script> whose
// attributes include id=…__UNIVERSAL_DATA_FOR_REHYDRATION__… rather than
// requiring id to lead. JSON escapes `<`, so the non-greedy body can't end early
// on a stray </script>.
const TIKTOK_REHYDRATION_RE = /<script\b[^>]*\bid=["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>([\s\S]*?)<\/script>/;
const TIKTOK_DETAIL_PATH_RE = /^\/@[^/]+\/video\/\d+/;

// Strong structural signature — an object with a string id and a video
// sub-object carrying a real address. Keeps the scope-agnostic walk off the
// app/i18n/seo context the blob is mostly made of.
function tiktokLooksLikeVideoItem(o) {
    return o && typeof o === "object"
        && typeof o.id === "string"
        && o.video && typeof o.video === "object"
        && (o.video.playAddr || o.video.downloadAddr || o.video.bitrateInfo);
}
// First matching item anywhere under obj (depth-capped).
function tiktokFindVideoItem(obj, depth) {
    if (!obj || typeof obj !== "object" || depth > 8) return null;
    if (tiktokLooksLikeVideoItem(obj)) return obj;
    if (Array.isArray(obj)) {
        for (const v of obj) {
            const f = tiktokFindVideoItem(v, depth + 1);
            if (f) return f;
        }
    } else {
        for (const k of Object.keys(obj)) {
            const f = tiktokFindVideoItem(obj[k], depth + 1);
            if (f) return f;
        }
    }
    return null;
}
// Pull the rehydration blob out of a detail-page document and return its single
// video item (as a one-element array), or [] if absent.
function extractTikTokSSRItems(html) {
    const m = TIKTOK_REHYDRATION_RE.exec(html);
    if (!m) {
        // Distinguish a regex miss from a blob-less document: is the marker even
        // present? markerPresent:true => tag exists but our pattern missed it
        // (tighten the regex); false => no rehydration blob at all.
        const markerPresent = html.indexOf("__UNIVERSAL_DATA_FOR_REHYDRATION__") >= 0;
        log("TIKTOK", `ssr: rehydration tag not matched`, { markerPresent, htmlLen: html.length });
        return [];
    }
    let data;
    try { data = JSON.parse(m[1]); }
    catch (e) { log("TIKTOK", `ssr: rehydration JSON parse failed`, e.message); return []; }
    const scope = data && data.__DEFAULT_SCOPE__;
    if (scope) {
        for (const k of Object.keys(scope)) {
            if (!/video[-.]detail/i.test(k)) continue;
            const found = tiktokFindVideoItem(scope[k], 0);
            if (found) return [found];
        }
    }
    log("TIKTOK", `ssr: detail blob has no video item`,
        { scopeKeys: scope ? Object.keys(scope).slice(0, 20) : null });
    return [];
}

browser.webRequest.onBeforeRequest.addListener(
    (details) => {
        if (details.type !== "main_frame") return {};
        let pathname;
        try { pathname = new URL(details.url).pathname; }
        catch (_) { return {}; }
        if (!TIKTOK_DETAIL_PATH_RE.test(pathname)) return {};
        log("TIKTOK", `ssr main_frame`, { url: details.url.slice(0, 120), tabId: details.tabId });
        const created = filterResponseText(details, (html) => {
            if (!html) {
                log("TIKTOK", `ssr: empty document`, { path: pathname });
                return;
            }
            const items = extractTikTokSSRItems(html);
            if (items.length === 0) {
                log("TIKTOK", `ssr: no items in document`, { path: pathname, htmlLen: html.length });
                return;
            }
            log("TIKTOK", `ssr items -> handler`, { count: items.length, firstId: items[0] && items[0].id, path: pathname });
            handleTikTokItemList({
                url: details.url,
                body: JSON.stringify({ itemList: items }),
                tabId: details.tabId,
                pageUrl: details.url
            }).catch(e => log("TIKTOK", `handler error (ssr)`, e.message));
        });
        if (!created) {
            log("TIKTOK", `ssr: filterResponseData unavailable`, { path: pathname });
        }
        return {};
    },
    { urls: ["*://www.tiktok.com/*", "*://m.tiktok.com/*"], types: ["main_frame"] },
    ["blocking"]
);

// Wire source — passive filterResponseData on the item_list XHR/fetch.
// Reads the page's OWN response byte-exact (filterResponseText writes
// every chunk straight through) — no refetch, so the single-use
// msToken/X-Bogus signature is untouched. This is only viable because
// the geckoview ServiceWorker-visibility patch (0006) makes SW-served
// responses fire http-on-examine-response, so onBeforeRequest +
// filterResponseData now reach the SW-intercepted /related/item_list/
// (and /newtab/ sub-feeds) that were previously invisible. Validated
// on-device: byte-exact passthrough does NOT trip the "Something went
// wrong" overlay, so this replaced the page-world inject entirely.
//
// The pattern allows item_list sub-segments — a hashtag page fires both
// /api/challenge/item_list/?… AND /api/challenge/item_list/newtab/?…;
// matching only the former would drop ~half the feed (the /newtab/ feed,
// ~30 items, confirmed captured on-device).
const TIKTOK_ITEMLIST_RE = /\/api\/[a-z_]+\/item_list(?:\/[a-z_]+)*\/?\?/i;
browser.webRequest.onBeforeRequest.addListener(
    (details) => {
        if (!TIKTOK_ITEMLIST_RE.test(details.url)) return {};
        log("TIKTOK", `filter onBeforeRequest`, {
            url: details.url.slice(0, 120),
            type: details.type,
            tabId: details.tabId,
            requestId: details.requestId
        });
        const created = filterResponseText(details, (body) => {
            if (!body) {
                log("TIKTOK", `filter empty body`, { url: details.url.slice(0, 100) });
                return;
            }
            log("TIKTOK", `filter body -> handler`, {
                url: details.url.slice(0, 100),
                bodyLen: body.length,
                tabId: details.tabId
            });
            handleTikTokItemList({
                url: details.url,
                body,
                tabId: details.tabId,
                pageUrl: details.documentUrl || details.originUrl
            }).catch(e => {
                log("TIKTOK", `handler error (filter)`, e.message);
            });
        });
        if (!created) {
            log("TIKTOK", `filterResponseData unavailable`, { url: details.url.slice(0, 100) });
        }
        return {};
    },
    { urls: ["*://www.tiktok.com/*", "*://m.tiktok.com/*"], types: ["xmlhttprequest"] },
    ["blocking"]
);

