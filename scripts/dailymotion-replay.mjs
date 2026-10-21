// Dailymotion parser replay — drives the REAL registered listeners from
// app/src/main/assets/webrequests/js/parsers/dailymotion.js (imported under a
// stubbed `browser`, the instagram-replay.mjs pattern) with SANITIZED fixtures
// derived from a real HAR (scripts/fixtures/dailymotion/ — structure, nesting
// and field shapes preserved; titles, identities and URL signatures scrubbed).
// This is the regression net for the robustness architecture: the embed-API
// capture (config + details merge, the enrichment details fetch, the untitled
// fallback), the rename-proof shape walk on BOTH config shapes, and the
// wire-master backbone (generic-title emit, metadata-cache enrichment, and the
// emitted-claim suppression). Run: node scripts/dailymotion-replay.mjs (any cwd).
//
// Fixture provenance (a publisher's embed, sanitized):
//   embed-config.json  — geo.dailymotion.com/videos/<id>: the signed HLS master
//                        in stream.url + posters_url (no title/duration here).
//   embed-details.json — geo.dailymotion.com/videos/<id>/details: info.title +
//                        info.duration (seconds).
//   master.m3u8        — the cdndirector signed master: three STREAM-INF
//                        renditions (720/480/288) + a SUBTITLES media group.
import { readFileSync } from "node:fs";
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(scriptDir, "..");
const fixtureDir = join(scriptDir, "fixtures", "dailymotion");

const configBody = readFileSync(join(fixtureDir, "embed-config.json"), "utf8");
const detailsBody = readFileSync(join(fixtureDir, "embed-details.json"), "utf8");
const masterBody = readFileSync(join(fixtureDir, "master.m3u8"), "utf8");

const FULL_TITLE = "Sample embed clip title long enough to exercise the forty character trim";
const POSTER_1080 = "https://s1.dmcdn.net/1/xPOSTERSCRUB1080/1920x1080f";
const masterUrlFor = (id, sec = "SCRUBBEDSECTOKEN") =>
    `https://cdndirector.dailymotion.com/cdn/manifest/video/${id}.m3u8?sec=${sec}`;

// ---------------------------------------------------------------------------
// browser stub + fake filterResponseData (the instagram-replay harness)
// ---------------------------------------------------------------------------
const registrations = [];
function evt(path) {
    return { addListener(fn, filter, extra) { registrations.push({ path, fn, filter, extra }); } };
}
const nativeSent = [];
const filters = new Map();

globalThis.browser = {
    runtime: {
        sendNativeMessage: async (app, msg) => { nativeSent.push({ app, msg }); return false; },
        onMessage: evt("runtime.onMessage"),
        connectNative: () => ({ onMessage: evt("port.onMessage"), onDisconnect: evt("port.onDisconnect"), postMessage() {} }),
    },
    webRequest: {
        onBeforeRequest: evt("webRequest.onBeforeRequest"),
        onSendHeaders: evt("webRequest.onSendHeaders"),
        onHeadersReceived: evt("webRequest.onHeadersReceived"),
        onResponseStarted: evt("webRequest.onResponseStarted"),
        onCompleted: evt("webRequest.onCompleted"),
        onErrorOccurred: evt("webRequest.onErrorOccurred"),
        filterResponseData(requestId) {
            const f = { ondata: null, onstop: null, onerror: null, write() {}, close() {} };
            filters.set(requestId, f);
            return f;
        },
    },
    webNavigation: { onHistoryStateUpdated: evt("webNavigation.onHistoryStateUpdated") },
    tabs: {
        onUpdated: evt("tabs.onUpdated"), onRemoved: evt("tabs.onRemoved"), onActivated: evt("tabs.onActivated"),
        query: async () => [],
        get: async (id) => {
            if (tabGone.has(id)) throw new Error(`no tab ${id}`);
            return { id, incognito: false, url: tabUrlOverride.get(id) || "https://embedder.example/article.html" };
        },
        sendMessage: async () => {},
    },
    cookies: { onChanged: evt("cookies.onChanged"), getAll: async () => [] },
    storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
};

// Section 9 moves a tab to another page / closes it under a queued capture.
const tabUrlOverride = new Map();
const tabGone = new Set();

// The parser reads navigator.userAgent when building the emit's requestHeaders.
Object.defineProperty(globalThis, "navigator", {
    value: { userAgent: "Mozilla/5.0 (Android 12; Mobile; rv:154.0) Gecko/154.0 Firefox/154.0", languages: ["en-US"] },
    configurable: true,
});

// The parser fetches the signed master itself (emitDailymotionHls) and the
// /details enrichment when the player's own /details hasn't landed. Serve the
// master for ANY /cdn/manifest/video/<id>.m3u8 URL, and details only for the
// ids in `detailsServed` — a 404 there pins the untitled-emit fallback.
const detailsServed = new Set(["xb1k5fe", "xb9race"]);
// The legacy geo JSON the dailymotion.com PAGE path fetches itself
// (fetchDailymotionGeoApi) — served only for these ids.
const geoServed = new Set(["xbtab01"]);
// The embed CONFIG the placeholder path fetches itself (section 9) — served
// only for these ids, from the fixture with the id swapped in.
const configServed = new Set();
// Per-id hook run while a config request is in flight (a delay, the tab
// moving to another page).
const configHooks = new Map();
const fetched = [];
const fetchedOpts = [];
let ownFetchSeq = 0;
globalThis.fetch = async (url, opts) => {
    fetched.push(url);
    fetchedOpts.push({ url, credentials: opts?.credentials });
    // Firefox shows an extension its OWN requests: the parser's fetch reaches
    // its own onBeforeRequest listeners (tab -1), which is what consumes the
    // markOwnRequest mark. Without this a mark lingers, and as a PREFIX match
    // it would swallow the player's later request for the same endpoint.
    for (const r of listenersMatching(url, "xmlhttprequest")) {
        r.fn({ url, type: "xmlhttprequest", tabId: -1, requestId: `own${++ownFetchSeq}` });
    }
    if (/\/cdn\/manifest\/video\/[A-Za-z0-9]+\.m3u8/.test(url)) {
        return { ok: true, status: 200, text: async () => masterBody };
    }
    const g = url.match(/geo\.dailymotion\.com\/video\/([A-Za-z0-9]+)\.json/);
    if (g && geoServed.has(g[1])) {
        return { ok: true, status: 200, text: async () => JSON.stringify({
            title: `Sample page video ${g[1]}`,
            duration: 42,
            thumbnails: { "720": `https://s1.dmcdn.net/v/xTHUMB${g[1]}/x720` },
            qualities: { auto: [{ type: "application/x-mpegURL", url: masterUrlFor(g[1]) }] },
        }) };
    }
    const d = url.match(/geo\.dailymotion\.com\/videos\/([A-Za-z0-9]+)\/details/);
    if (d && detailsServed.has(d[1])) {
        return { ok: true, status: 200, text: async () => detailsBody.replaceAll("xb1k5fe", d[1]) };
    }
    const c = url.match(/geo\.dailymotion\.com\/videos\/([A-Za-z0-9]+)(?:[?#]|$)/);
    if (c && configHooks.has(c[1])) await configHooks.get(c[1])();
    if (c && configServed.has(c[1])) {
        return { ok: true, status: 200, text: async () => configBody.replaceAll("xb1k5fe", c[1]) };
    }
    return { ok: false, status: 404, text: async () => "" };
};

await import(pathToFileURL(join(repoRoot, "app/src/main/assets/webrequests/js/parsers/dailymotion.js")));

let failures = 0;
function check(name, cond, extra) {
    if (cond) console.log("PASS", name);
    else { failures++; console.log("FAIL", name, extra ?? ""); }
}

// Real WebExtension match-pattern semantics: `*.host` matches host and any
// subdomain; the path glob matches across path + query.
function patternMatches(pattern, url) {
    const m = pattern.match(/^(\*|https?):\/\/([^/]+)(\/.*)$/);
    if (!m) return false;
    const u = new URL(url);
    if (m[1] !== "*" && m[1] !== u.protocol.replace(":", "")) return false;
    let host = m[2];
    let optSub = "";
    if (host.startsWith("*.")) { optSub = "([^.]+\\.)*"; host = host.slice(2); }
    const hostSrc = optSub + host.replace(/\./g, "\\.").replace(/\*/g, "[^.]*");
    if (!new RegExp("^" + hostSrc + "$").test(u.hostname)) return false;
    const pathRe = "^" + m[3].replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$";
    return new RegExp(pathRe).test(u.pathname + u.search);
}

function listenersMatching(url, type) {
    return registrations.filter(r =>
        r.path === "webRequest.onBeforeRequest"
        && r.filter?.types?.includes(type)
        && r.filter?.urls?.some(p => patternMatches(p, url)));
}

const isCapture = (s) => s.msg?.type === "variants" || s.msg?.type === "hls-master";

// Feed a body through the ONE matching blocking listener's response filter.
async function feed(url, type, tabId, requestId, body) {
    const matching = listenersMatching(url, type);
    if (matching.length !== 1) return { fed: false, matched: matching.length, emits: [] };
    const before = nativeSent.length;
    matching[0].fn({ url, type, tabId, requestId });
    const f = filters.get(requestId);
    if (!f) return { fed: false, matched: 1, emits: [] };
    f.ondata({ data: new TextEncoder().encode(body).buffer });
    f.onstop();
    await new Promise(r => setTimeout(r, 40));
    return { fed: true, matched: 1, emits: nativeSent.slice(before).filter(isCapture) };
}

// Dispatch a read-only (non-blocking, no filter) listener — the wire-master.
// waitMs must outlast DM_MASTER_GRACE_MS for the cases where the backbone
// legitimately waits out a live-but-silent API path (apiSeen without an emit).
async function dispatch(url, type, tabId, requestId, waitMs = 40) {
    const matching = listenersMatching(url, type);
    if (matching.length !== 1) return { fed: false, matched: matching.length, emits: [] };
    const before = nativeSent.length;
    matching[0].fn({ url, type, tabId, requestId });
    await new Promise(r => setTimeout(r, waitMs));
    return { fed: true, matched: 1, emits: nativeSent.slice(before).filter(isCapture) };
}

// ---------------------------------------------------------------------------
// 1. URL-pattern coverage — every capture surface routes to exactly one listener
// ---------------------------------------------------------------------------
check("pattern: embed config xhr",
    listenersMatching("https://geo.dailymotion.com/videos/xb1k5fe?embedder=https%3A%2F%2Fembedder.example%2F", "xmlhttprequest").length === 1);
check("pattern: embed details xhr",
    listenersMatching("https://geo.dailymotion.com/videos/xb1k5fe/details?embedder=x", "xmlhttprequest").length === 1);
check("pattern: legacy geo .json xhr",
    listenersMatching("https://geo.dailymotion.com/video/xb1k5fe.json?legacy=true", "xmlhttprequest").length === 1);
for (const type of ["xmlhttprequest", "media", "other"]) {
    check(`pattern: wire master as ${type}`,
        listenersMatching(masterUrlFor("xb1k5fe"), type).length === 1);
}
check("pattern: dailymotion.com video page main_frame",
    listenersMatching("https://www.dailymotion.com/video/xb1k5fe", "main_frame").length === 1);
check("pattern: player iframe html NOT matched as xhr",
    listenersMatching("https://geo.dailymotion.com/player/xkdl1.html?video=xb1k5fe", "xmlhttprequest").length === 0);

// ---------------------------------------------------------------------------
// 2. Embed flow — config first (the HAR order), enrichment /details fetch,
//    one titled skipProbe+manifest emit, player's own /details deduped
// ---------------------------------------------------------------------------
{
    const { fed, emits } = await feed(
        "https://geo.dailymotion.com/videos/xb1k5fe?embedder=x", "xmlhttprequest", 20, "dmCfg1", configBody);
    check("embed: filter fed", fed);
    check("embed: enrichment details fetch fired",
        fetched.some(u => u.includes("/videos/xb1k5fe/details")), JSON.stringify(fetched));
    check("embed: exactly one emit", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("embed: variants emit", m.type === "variants", m.type);
        check("embed: canonical origin", m.origin === "https://www.dailymotion.com/video/xb1k5fe", m.origin);
        check("embed: the whole title is the name (no 40-character cut mid-sentence)", m.name === FULL_TITLE, m.name);
        check("embed: description keeps the full title", m.description === FULL_TITLE, m.description);
        check("embed: duration from details (s → ms)", m.duration === 51000, m.duration);
        check("embed: largest poster picked", m.img === POSTER_1080, m.img);
        check("embed: skipProbe + manifest declared", m.skipProbe === true && m.manifest === true,
            JSON.stringify([m.skipProbe, m.manifest]));
        check("embed: three renditions best-first", JSON.stringify(m.variants.map(v => v.height)) === "[720,480,288]",
            JSON.stringify(m.variants?.map(v => v.height)));
        check("embed: Referer rides the emit",
            (m.requestHeaders || []).some(h => h.name === "Referer" && h.value === "https://www.dailymotion.com/"),
            JSON.stringify(m.requestHeaders));
    }
    // The player's own /details lands afterwards — must not re-emit. (It may
    // be swallowed before the filter exists: the enrichment fetch marked the
    // bare /details URL as an own-request, which this URL startsWith. That
    // consume-once swallow is fine; what must hold is zero new captures.)
    const second = await feed(
        "https://geo.dailymotion.com/videos/xb1k5fe/details?embedder=x", "xmlhttprequest", 20, "dmDet1", detailsBody);
    check("embed: player's own /details body does not double-emit", second.emits.length === 0,
        second.emits.length);
}

// ---------------------------------------------------------------------------
// 3. Untitled fallback — /details unreachable, the capture still happens
// ---------------------------------------------------------------------------
{
    const body = configBody.replaceAll("xb1k5fe", "xb2nodet");
    const { fed, emits } = await feed(
        "https://geo.dailymotion.com/videos/xb2nodet?embedder=x", "xmlhttprequest", 21, "dmCfg2", body);
    check("no-details: filter fed", fed);
    check("no-details: emits anyway (untitled)", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("no-details: origin right", m.origin === "https://www.dailymotion.com/video/xb2nodet", m.origin);
        check("no-details: no invented title", !m.name, m.name);
        check("no-details: renditions still enumerated", m.variants?.length === 3, m.variants?.length);
    }
}

// ---------------------------------------------------------------------------
// 4. Shape fallback — stream.url RENAMED; the walk finds the master by what
//    it IS (a Dailymotion-CDN .m3u8), not where it sits
// ---------------------------------------------------------------------------
{
    const cfg = JSON.parse(configBody.replaceAll("xb1k5fe", "xb3shape"));
    cfg.playback = { map: { hls: cfg.stream.url } };
    delete cfg.stream;
    const { fed, emits } = await feed(
        "https://geo.dailymotion.com/videos/xb3shape?embedder=x", "xmlhttprequest", 22, "dmCfg3", JSON.stringify(cfg));
    check("shape walk: filter fed", fed);
    check("shape walk: renamed stream.url still captured", emits.length === 1, emits.length);
    if (emits.length === 1) {
        check("shape walk: origin right", emits[0].msg.origin === "https://www.dailymotion.com/video/xb3shape",
            emits[0].msg.origin);
    }
}

// ---------------------------------------------------------------------------
// 5. Legacy geo .json — the qualities.auto shape, and ITS shape fallback
// ---------------------------------------------------------------------------
{
    const body = JSON.stringify({
        title: "Sample legacy page video",
        duration: 51,
        thumbnails: { "360": "https://s1.dmcdn.net/v/xTHUMBSCRUB360/x360", "720": "https://s1.dmcdn.net/v/xTHUMBSCRUB720/x720" },
        qualities: { auto: [{ type: "application/x-mpegURL", url: masterUrlFor("xb4qual") }] },
    });
    const { fed, emits } = await feed(
        "https://geo.dailymotion.com/video/xb4qual.json?legacy=true", "xmlhttprequest", 23, "dmGeo1", body);
    check("legacy geo: filter fed", fed);
    check("legacy geo: one emit", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("legacy geo: title + duration + thumb",
            m.name === "Sample legacy page video" && m.duration === 51000 && (m.img || "").includes("x720"),
            JSON.stringify([m.name, m.duration, m.img]));
    }
}
{
    // qualities renamed → the same findDailymotionHlsUrl walk must rescue it.
    const body = JSON.stringify({
        title: "Sample legacy page video 2",
        duration: 10,
        media_streams: { hls_url: masterUrlFor("xb5qual2") },
    });
    const { emits } = await feed(
        "https://geo.dailymotion.com/video/xb5qual2.json?legacy=true", "xmlhttprequest", 24, "dmGeo2", body);
    check("legacy geo shape walk: renamed qualities still captured", emits.length === 1, emits.length);
}

// ---------------------------------------------------------------------------
// 6. Wire-master backbone — the API-change-proof net
// ---------------------------------------------------------------------------
{
    // a) A video NO API listener ever saw (a future API shape the parser can't
    //    read) → the master on the wire still captures, with the generic title.
    const { fed, emits } = await dispatch(masterUrlFor("xb6wire"), "media", 25, "dmWire1");
    check("backbone: dispatch fed", fed);
    check("backbone: unknown video emits off the wire", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("backbone: canonical origin from the manifest URL",
            m.origin === "https://www.dailymotion.com/video/xb6wire", m.origin);
        check("backbone: generic fallback title", m.name === "Dailymotion video", m.name);
        check("backbone: renditions enumerated", m.variants?.length === 3, m.variants?.length);
    }
}
{
    // b) The API listeners cached METADATA but never found a stream (a config
    //    shape gone unreadable) → the backbone emit is enriched from the cache.
    //    apiSeen is true here (the details request WAS observed), so the
    //    backbone waits out its full grace before concluding the API path
    //    stays silent — the dispatch wait must outlast DM_MASTER_GRACE_MS.
    const detBody = detailsBody.replaceAll("xb1k5fe", "xb7meta");
    const det = await feed(
        "https://geo.dailymotion.com/videos/xb7meta/details?embedder=x", "xmlhttprequest", 26, "dmDet7", detBody);
    check("backbone enrich: details alone emits nothing", det.fed && det.emits.length === 0, det.emits.length);
    const { emits } = await dispatch(masterUrlFor("xb7meta"), "media", 26, "dmWire2", 5800);
    check("backbone enrich: wire master emits", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("backbone enrich: cached title used (not the generic)", m.name === FULL_TITLE, m.name);
        check("backbone enrich: cached duration used", m.duration === 51000, m.duration);
    }
}
{
    // c) A video an API path already captured → suppressed (entry.emitted).
    //    Different sec= so the own-request marker from the emit's fetch can't
    //    be what suppresses it.
    const { fed, emits } = await dispatch(masterUrlFor("xb1k5fe", "ROTATEDSECTOKEN"), "media", 20, "dmWire3");
    check("backbone: API-captured video suppressed", fed && emits.length === 0, emits.length);
}
{
    // d) THE RACE — the player fetches the master the instant it has the
    //    config, so the wire-master listener fires in the SAME TICK as the
    //    config body, before the API path's async parse + /details enrichment.
    //    The backbone must defer (apiSeen grace) and let the TITLED emit win;
    //    an immediate generic emit would claim the clip and suppress it.
    const cfgUrl = "https://geo.dailymotion.com/videos/xb9race?embedder=x";
    const cfgListener = listenersMatching(cfgUrl, "xmlhttprequest")[0];
    const masterListener = listenersMatching(masterUrlFor("xb9race"), "media")[0];
    const before = nativeSent.length;
    cfgListener.fn({ url: cfgUrl, type: "xmlhttprequest", tabId: 28, requestId: "dmCfg9" });
    const f = filters.get("dmCfg9");
    f.ondata({ data: new TextEncoder().encode(configBody.replaceAll("xb1k5fe", "xb9race")).buffer });
    f.onstop();
    // No await between the two: the master hits the wire before any async
    // work of the config listener has run.
    masterListener.fn({ url: masterUrlFor("xb9race"), type: "media", tabId: 28, requestId: "dmWire9" });
    await new Promise(r => setTimeout(r, 1500));
    const emits = nativeSent.slice(before).filter(isCapture);
    check("race: exactly one emit when the master beats the API parse", emits.length === 1, emits.length);
    check("race: the TITLED emit wins (backbone deferred)",
        emits[0]?.msg?.name === FULL_TITLE, emits[0]?.msg?.name);
}

// ---------------------------------------------------------------------------
// 7. Negative — a config with no HLS anywhere emits nothing
// ---------------------------------------------------------------------------
{
    const cfg = JSON.parse(configBody.replaceAll("xb1k5fe", "xb8none"));
    delete cfg.stream;
    const { emits } = await feed(
        "https://geo.dailymotion.com/videos/xb8none?embedder=x", "xmlhttprequest", 27, "dmCfg8", JSON.stringify(cfg));
    check("noise: no emit from a masterless config", emits.length === 0, emits.length);
}

// ---------------------------------------------------------------------------
// Filter ERROR on the geo API → the re-fetch fallback actually runs. Gecko
// throws from close() on an errored filter (the fake mirrors that here); the
// private reader called it bare, so the fallback after it never ran and the
// capture fell to the backbone's generic title.
// ---------------------------------------------------------------------------
{
    const url = "https://geo.dailymotion.com/video/xerr001.json?embedder=https%3A%2F%2Fembedder.example%2F";
    const matching = listenersMatching(url, "xmlhttprequest");
    check("geo-error: one geo listener", matching.length === 1, matching.length);
    const before = fetched.length;
    matching[0].fn({ url, type: "xmlhttprequest", tabId: 77, requestId: "dmGeoErr" });
    const f = filters.get("dmGeoErr");
    f.close = () => { throw new Error("NS_ERROR_FAILURE"); };
    f.onerror();
    await new Promise(r => setTimeout(r, 60));
    const refetched = fetched.slice(before).some(u => u.startsWith("https://geo.dailymotion.com/video/xerr001.json"));
    check("geo-error: a filter error falls back to the parser's own geo fetch", refetched, JSON.stringify(fetched.slice(before)));
}

// ---------------------------------------------------------------------------
// 8. The emit claim is PER TAB — the same video in a second tab captures again.
//    A per-VIDEO claim would make processDailymotionData return silently in
//    any tab after the first, for the cache's whole TTL; the 10 s fetch claim
//    is per tab too. Metadata stays shared — it's a fact about the video.
// ---------------------------------------------------------------------------
{
    const page = "https://www.dailymotion.com/video/xbtab01";
    const first = await dispatch(page, "main_frame", 40, "dmPage1", 200);
    check("per-tab: first tab's page navigation captures", first.emits.length === 1, first.emits.length);
    check("per-tab: first capture is filed under its tab", first.emits[0]?.msg?.tabId === 40,
        first.emits[0]?.msg?.tabId);

    const again = await dispatch(page, "main_frame", 40, "dmPage1b", 200);
    check("per-tab: the same tab re-navigating does not re-emit", again.emits.length === 0, again.emits.length);

    const second = await dispatch(page, "main_frame", 41, "dmPage2", 200);
    check("per-tab: a SECOND tab on the same video captures it", second.emits.length === 1, second.emits.length);
    check("per-tab: second capture is filed under the second tab", second.emits[0]?.msg?.tabId === 41,
        second.emits[0]?.msg?.tabId);
    check("per-tab: second tab's capture keeps the title", second.emits[0]?.msg?.name === "Sample page video xbtab01",
        second.emits[0]?.msg?.name);

    const wireSame = await dispatch(masterUrlFor("xbtab01", "PLAYERSEC41"), "media", 41, "dmWireT41", 200);
    check("per-tab: the backbone in a tab whose API path emitted stays quiet", wireSame.emits.length === 0,
        wireSame.emits.length);

    // A third tab where only the PLAYER's master crossed the wire (no API path
    // ran there): emit at once — another tab's apiSeen must not hold it for the
    // grace — and enriched from the metadata the other tabs cached.
    const wireOther = await dispatch(masterUrlFor("xbtab01", "PLAYERSEC42"), "media", 42, "dmWireT42", 300);
    check("per-tab: the backbone in an untouched tab captures without waiting", wireOther.emits.length === 1,
        wireOther.emits.length);
    check("per-tab: and borrows the shared title", wireOther.emits[0]?.msg?.name === "Sample page video xbtab01",
        wireOther.emits[0]?.msg?.name);
}

{
    // The embed's signed stream URL is shared in the per-video cache; a tab
    // whose player's /details lands BEFORE its own config must not emit with
    // ANOTHER tab's signed master — it waits for its own config.
    const det = detailsBody.replaceAll("xb1k5fe", "xbcfg02");
    const cfg = configBody.replaceAll("xb1k5fe", "xbcfg02");
    const d50 = await feed("https://geo.dailymotion.com/videos/xbcfg02/details?embedder=x", "xmlhttprequest", 50, "dmD50", det);
    const c50 = await feed("https://geo.dailymotion.com/videos/xbcfg02?embedder=x", "xmlhttprequest", 50, "dmC50", cfg);
    check("per-tab config: first tab emits once its config lands", d50.emits.length + c50.emits.length === 1,
        d50.emits.length + c50.emits.length);
    const d51 = await feed("https://geo.dailymotion.com/videos/xbcfg02/details?embedder=x", "xmlhttprequest", 51, "dmD51", det);
    check("per-tab config: a second tab's early /details does NOT emit another tab's stream URL",
        d51.fed && d51.emits.length === 0, JSON.stringify([d51.fed, d51.emits.length]));
    const c51 = await feed("https://geo.dailymotion.com/videos/xbcfg02?embedder=x", "xmlhttprequest", 51, "dmC51", cfg);
    check("per-tab config: the second tab emits once ITS config lands", c51.emits.length === 1, c51.emits.length);
    check("per-tab config: filed under the second tab", c51.emits[0]?.msg?.tabId === 51, c51.emits[0]?.msg?.tabId);
}

// ---------------------------------------------------------------------------
// 9. Click-to-load PLACEHOLDERS: the page shows each clip as a picture and
//    loads the player only on a tap, so before the tap nothing crosses the
//    wire. The content script reports the player URL each placeholder
//    declares (`player-placeholders`); the parser fetches the embed config
//    itself, anonymously, and emits through the embed path. A later tap must
//    not capture the clip again.
// ---------------------------------------------------------------------------
{
    const PAGE = "https://embedder.example/article.html";
    const routers = registrations.filter(r => r.path === "runtime.onMessage");
    const sender = (tabId, page = PAGE, frameUrl = page) =>
        ({ tab: { id: tabId, url: page, incognito: false }, url: frameUrl, frameId: frameUrl === page ? 0 : 3 });
    async function placeholders(tabId, urls, waitMs = 250, page = PAGE, frameUrl = page) {
        const before = nativeSent.length;
        const fetchedBefore = fetched.length;
        for (const r of routers) r.fn({ kind: "player-placeholders", urls }, sender(tabId, page, frameUrl));
        await new Promise(r => setTimeout(r, waitMs));
        return {
            emits: nativeSent.slice(before).filter(isCapture),
            fetched: fetched.slice(fetchedBefore),
            opts: fetchedOpts.slice(fetchedBefore),
        };
    }
    for (const id of ["xbph001", "xbph002"]) { configServed.add(id); detailsServed.add(id); }
    const playerUrl = (id) => `https://geo.dailymotion.com/player/x1kd82.html?video=${id}&mute=true`;
    const title = (id) => JSON.parse(detailsBody.replaceAll("xb1k5fe", id)).info.title;

    const first = await placeholders(60, [
        playerUrl("xbph001"),
        "https://www.dailymotion.com/embed/video/xbph002",
        "https://platform.twitter.com/embed/Tweet.html?id=1",
    ]);
    check("placeholder: both Dailymotion placeholders capture before any tap", first.emits.length === 2,
        first.emits.length);
    check("placeholder: filed under the reporting tab", first.emits.every(e => e.msg.tabId === 60),
        JSON.stringify(first.emits.map(e => e.msg.tabId)));
    check("placeholder: each keeps its own canonical origin",
        first.emits.map(e => e.msg.origin).sort().join(" ")
            === "https://www.dailymotion.com/video/xbph001 https://www.dailymotion.com/video/xbph002",
        JSON.stringify(first.emits.map(e => e.msg.origin)));
    check("placeholder: titled from the clip's own /details", first.emits.some(e =>
        e.msg.description === title("xbph001")), JSON.stringify(first.emits.map(e => e.msg.description)));
    check("placeholder: the config fetch names the page ORIGIN as embedder",
        first.fetched.includes("https://geo.dailymotion.com/videos/xbph001?embedder=https%3A%2F%2Fembedder.example%2F"),
        JSON.stringify(first.fetched));
    check("placeholder: every own fetch is anonymous (config, details, master)",
        first.opts.length >= 6 && first.opts.every(o => o.credentials === "omit"),
        JSON.stringify(first.opts));
    check("placeholder: another site's placeholder fetches nothing",
        !first.fetched.some(u => u.includes("twitter.com")), JSON.stringify(first.fetched));

    const again = await placeholders(60, [playerUrl("xbph001")]);
    check("placeholder: a second report of the same clip fetches nothing",
        again.emits.length === 0 && again.fetched.length === 0, JSON.stringify(again.fetched));

    // The user taps: the player's own config + master cross the wire.
    const tapCfg = await feed("https://geo.dailymotion.com/videos/xbph001?embedder=https%3A%2F%2Fembedder.example%2Farticle.html",
        "xmlhttprequest", 60, "dmTap60", configBody.replaceAll("xb1k5fe", "xbph001"));
    check("placeholder: the tap's player config does not capture again", tapCfg.fed && tapCfg.emits.length === 0,
        JSON.stringify([tapCfg.fed, tapCfg.emits.length]));
    const tapWire = await dispatch(masterUrlFor("xbph001", "TAPSEC"), "media", 60, "dmTapW60", 200);
    check("placeholder: the tap's master does not capture again", tapWire.emits.length === 0, tapWire.emits.length);

    // A second tab on the same page is its own capture.
    const other = await placeholders(61, [playerUrl("xbph001")]);
    check("placeholder: a second tab captures the clip too", other.emits.length === 1 && other.emits[0].msg.tabId === 61,
        JSON.stringify(other.emits.map(e => e.msg.tabId)));

    // Capped per tab: 14 placeholders, 12 config fetches.
    const many = Array.from({ length: 14 }, (_, i) => `xbcap${String(i).padStart(2, "0")}`);
    // Served, so each capture succeeds and KEEPS its claim: the budget below
    // must hold against live claims, not claims a failure gave back.
    for (const id of many) { configServed.add(id); detailsServed.add(id); }
    const capped = await placeholders(62, many.map(playerUrl), 900);
    const configFetches = capped.fetched.filter(u => /\/videos\/xbcap\d+\?embedder=/.test(u));
    check("placeholder: at most 12 per page", configFetches.length === 12, configFetches.length);

    // The budget is per PAGE: the next article in the same tab has its own.
    tabUrlOverride.set(62, "https://embedder.example/next-article.html");
    configServed.add("xbnext1"); detailsServed.add("xbnext1");
    const nextPage = await placeholders(62, [playerUrl("xbnext1")], 250, "https://embedder.example/next-article.html");
    check("placeholder: the next page in the same tab has its own budget", nextPage.emits.length === 1,
        nextPage.emits.length);
    tabUrlOverride.delete(62);

    // A config that failed (a network blip, a 404) releases its claim: the
    // next report of the page (a reload) tries again.
    const failed = await placeholders(65, [playerUrl("xbretry")]);
    check("placeholder: a failed config emits nothing", failed.emits.length === 0
        && failed.fetched.some(u => u.includes("/videos/xbretry?")), JSON.stringify(failed.fetched));
    configServed.add("xbretry"); detailsServed.add("xbretry");
    const retried = await placeholders(65, [playerUrl("xbretry")]);
    check("placeholder: a reload after a failed config captures", retried.emits.length === 1, retried.emits.length);

    // A placeholder in a srcdoc frame names the PAGE's origin, never "null".
    configServed.add("xbsrcdoc"); detailsServed.add("xbsrcdoc");
    const srcdoc = await placeholders(66, [playerUrl("xbsrcdoc")], 250, PAGE, "about:srcdoc");
    check("placeholder: a srcdoc frame's placeholder names the page origin as embedder",
        srcdoc.fetched.includes("https://geo.dailymotion.com/videos/xbsrcdoc?embedder=https%3A%2F%2Fembedder.example%2F"),
        JSON.stringify(srcdoc.fetched));

    // The tab moved on (or closed) before the queued capture ran: dropped, or
    // it would be filed under the next page.
    configServed.add("xbph003"); detailsServed.add("xbph003");
    tabUrlOverride.set(63, "https://embedder.example/another-article.html");
    const moved = await placeholders(63, [playerUrl("xbph003")]);
    check("placeholder: a tab that left the page fetches and emits nothing",
        moved.emits.length === 0 && moved.fetched.length === 0, JSON.stringify(moved.fetched));
    tabUrlOverride.delete(63);
    const back = await placeholders(63, [playerUrl("xbph003")]);
    check("placeholder: coming back to the page captures what was skipped", back.emits.length === 1,
        back.emits.length);
    // The tab moved to another article WHILE the clip's fetches ran: nothing
    // is sent (it would be filed under the next page), and both claims go back,
    // so a return to the article captures it.
    configServed.add("xbmove1"); detailsServed.add("xbmove1");
    configHooks.set("xbmove1", async () => { tabUrlOverride.set(67, "https://embedder.example/other.html"); });
    const movedMid = await placeholders(67, [playerUrl("xbmove1")]);
    check("placeholder: a tab that moves during the fetches gets no emit", movedMid.emits.length === 0
        && movedMid.fetched.some(u => u.includes("/videos/xbmove1?")), JSON.stringify(movedMid.fetched));
    configHooks.delete("xbmove1");
    tabUrlOverride.delete(67);
    const backMid = await placeholders(67, [playerUrl("xbmove1")]);
    check("placeholder: and returning to the article captures it", backMid.emits.length === 1, backMid.emits.length);

    // One tab's stalled config does not hold another tab's capture.
    configServed.add("xbslow1"); detailsServed.add("xbslow1");
    configServed.add("xbfast1"); detailsServed.add("xbfast1");
    configHooks.set("xbslow1", () => new Promise(r => setTimeout(r, 600)));
    const startedAt = nativeSent.length;
    for (const r of routers) r.fn({ kind: "player-placeholders", urls: [playerUrl("xbslow1")] }, sender(68));
    for (const r of routers) r.fn({ kind: "player-placeholders", urls: [playerUrl("xbfast1")] }, sender(69));
    await new Promise(r => setTimeout(r, 200));
    const early = nativeSent.slice(startedAt).filter(isCapture);
    check("placeholder: a stalled tab does not hold up another tab", early.length === 1
        && early[0].msg.origin === "https://www.dailymotion.com/video/xbfast1",
        JSON.stringify(early.map(e => e.msg.origin)));
    await new Promise(r => setTimeout(r, 600));
    configHooks.delete("xbslow1");
    const late = nativeSent.slice(startedAt).filter(isCapture);
    check("placeholder: and the stalled one still lands", late.length === 2, late.length);

    // A query parameter added to the page after the report (a consent or
    // campaign rewrite) is the same page.
    configServed.add("xbquery"); detailsServed.add("xbquery");
    tabUrlOverride.set(70, "https://embedder.example/article.html?utm_source=x");
    const refined = await placeholders(70, [playerUrl("xbquery")]);
    check("placeholder: a query refinement of the page still captures", refined.emits.length === 1,
        refined.emits.length);
    tabUrlOverride.set(71, "https://embedder.example/other-article.html");
    configServed.add("xbother"); detailsServed.add("xbother");
    const otherPath = await placeholders(71, [playerUrl("xbother")]);
    check("placeholder: another path is another page", otherPath.emits.length === 0, otherPath.emits.length);

    tabGone.add(64);
    const closed = await placeholders(64, [playerUrl("xbph003")]);
    check("placeholder: a closed tab fetches and emits nothing",
        closed.emits.length === 0 && closed.fetched.length === 0, JSON.stringify(closed.fetched));
}

console.log(failures ? `\ndailymotion-replay: ${failures} FAILURE(S)` : "\ndailymotion-replay: all checks passed");
process.exit(failures ? 1 : 0);
