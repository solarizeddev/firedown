// Instagram parser replay — drives the REAL registered listeners from
// app/src/main/assets/webrequests/js/parsers/instagram.js (imported under a
// stubbed `browser`, the webrequests-smoke.mjs pattern) with SANITIZED
// fixtures derived from real HARs (scripts/fixtures/instagram/ — structure,
// nesting depth and field shapes preserved byte-for-byte; identities,
// captions and URL signatures scrubbed). This is the regression net for the
// robustness architecture: wrapper churn, host churn, anti-hijack prefix
// churn, the legacy video_url shape, and per-slide carousel emits are each
// pinned here. Run: node scripts/instagram-replay.mjs (any cwd).
//
// Fixture provenance:
//   home-feed-doc.html   — logged-in home feed SSR (HAR 26-08-09): two plain
//                          video posts + a carousel with one video slide,
//                          inside the real data-sjs Relay nesting.
//   feed-graphql.json    — a feed pagination graphql/query body (same HAR):
//                          explore_story nesting, a carousel with TWO video
//                          slides, a plain media edge.
//   reel-doc.html        — logged-out reel page SSR (HAR 26-08-08): the
//                          gated xig_polaris_media shape with an inline DASH
//                          manifest (video+audio track pairing).
//   legacy-video-url.json— the old web-GraphQL video_url node under an
//                          UNKNOWN wrapper (the shape walk must find it).
//   (synthetic, section 8) — a Bloks-style item serialized as a JSON string,
//                          and an item nested past the walk's depth cap: the
//                          string-scan fallback's two blind-spot cases.
//   route-definition.ndjson — the Comet router body (POST /ajax/route-
//                          definition/, HAR 26-09-04): a `for (;;);`-prefixed
//                          first line + the media item on line 2 under a
//                          Relay preloader wrapper. The logged-out MOBILE
//                          permalink's ONLY carrier of the video — the
//                          document has just a lean upsell shape.
import { readFileSync } from "node:fs";
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(scriptDir, "..");
const fixtureDir = join(scriptDir, "fixtures", "instagram");

// ---------------------------------------------------------------------------
// browser stub + fake filterResponseData
// ---------------------------------------------------------------------------
const registrations = [];
function evt(path) {
    return { addListener(fn, filter, extra) { registrations.push({ path, fn, filter, extra }); } };
}
const nativeSent = [];
const filters = new Map();
// Every shortcode GraphQL fetch attempt reads the cookie jar first (no cookie
// → queued), so this counter is "how many times the SPA fallback tried".
let cookieReads = 0;

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
        query: async () => [], get: async () => ({ incognito: false, url: "https://www.instagram.com/" }),
        sendMessage: async () => {},
    },
    cookies: { onChanged: evt("cookies.onChanged"), getAll: async () => { cookieReads++; return []; } },
    storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
};

await import(pathToFileURL(join(repoRoot, "app/src/main/assets/webrequests/js/parsers/instagram.js")));

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
    const emits = nativeSent.slice(before).filter(s => s.msg?.variants?.length);
    return { fed: true, matched: 1, emits };
}
const originsOf = (emits) => emits.map(s => s.msg.origin || s.msg.originUrl || "");

// ---------------------------------------------------------------------------
// 1. URL-pattern coverage — host and endpoint churn
// ---------------------------------------------------------------------------
for (const url of [
    "https://www.instagram.com/graphql/query",
    "https://www.instagram.com/api/graphql",
    "https://www.instagram.com/api/v1/feed/reels_media/?media_id=1",
    "https://i.instagram.com/api/v1/clips/home/",
    "https://instagram.com/graphql/query",
    "https://www.instagram.com/ajax/route-definition/",
    "https://www.instagram.com/ajax/bulk-route-definitions/",
    "https://www.instagram.com/some/router/of/2027/?x=1",   // no URL knowledge: any path
]) {
    check(`pattern: xhr ${url.slice(8, 55)}`, listenersMatching(url, "xmlhttprequest").length === 1);
}
for (const url of [
    "https://www.instagram.com/",
    "https://www.instagram.com/reel/CqkwFBfLhUi/",
    "https://www.instagram.com/some.profile/",
    "https://instagram.com/explore/",
]) {
    check(`pattern: doc ${url.slice(8, 55)}`, listenersMatching(url, "main_frame").length === 1);
}

// ---------------------------------------------------------------------------
// 2. Home-feed document — SSR capture incl. the carousel slide
// ---------------------------------------------------------------------------
{
    const html = readFileSync(join(fixtureDir, "home-feed-doc.html"), "utf8");
    const { fed, emits } = await feed("https://www.instagram.com/", "main_frame", 20, "docHome", html);
    check("home doc: filter fed", fed);
    const origins = originsOf(emits);
    for (const code of ["DbypfbRqAEm", "DbykUeDitnQ", "Db0LCkPCCh5"]) {
        check(`home doc: emits /p/${code}`, origins.some(o => o.includes(code)), JSON.stringify(origins));
    }
    check("home doc: exactly 3 emits (carousel slide not double-collected)",
        emits.length === 3, emits.length);
}

// ---------------------------------------------------------------------------
// 3. Feed graphql XHR — anti-hijack prefix + carousel + explore_story
// ---------------------------------------------------------------------------
{
    const body = readFileSync(join(fixtureDir, "feed-graphql.json"), "utf8");
    // a DIFFERENT prefix than the hardcoded "for (;;);" — the tolerant parse
    // must recover from any junk before the first JSON delimiter
    const { fed, emits } = await feed("https://www.instagram.com/graphql/query",
        "xmlhttprequest", 21, "gqlFeed", "while(1);" + body);
    check("feed gql: filter fed", fed);
    const origins = originsOf(emits);
    for (const code of ["Dbyj_lUKy3U", "DbeTHfZNmu0"]) {
        check(`feed gql: emits /p/${code}`, origins.some(o => o.includes(code)), JSON.stringify(origins));
    }
    const carousel = origins.filter(o => o.includes("DbzTzGEC"));
    check("feed gql: carousel emits BOTH video slides", carousel.length === 2, carousel.length);
}

// ---------------------------------------------------------------------------
// 4. Reel document — gated xig_polaris shape + DASH audio pairing
// ---------------------------------------------------------------------------
{
    const html = readFileSync(join(fixtureDir, "reel-doc.html"), "utf8");
    const { fed, emits } = await feed("https://www.instagram.com/reel/CqkwFBfLhUi/",
        "main_frame", 22, "docReel", html);
    check("reel doc: filter fed", fed);
    check("reel doc: one emit", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("reel doc: canonical origin", (m.origin || "").endsWith("/p/CqkwFBfLhUi"), m.origin);
        check("reel doc: DASH renditions carry audioUrl", m.variants.some(v => v.audioUrl),
            JSON.stringify(m.variants?.map(v => [v.height, !!v.audioUrl])));
        check("reel doc: duration from MPD", m.duration > 0, m.duration);
    }
}

// ---------------------------------------------------------------------------
// 4b. Permalink doc with PRELOADED suggested clips — only the addressed item
//     emits (a logged-in reel page inlines suggested/next reels drawn from
//     recent activity; capturing them attributes never-viewed videos to the
//     tab — the "video from my other tab" report)
// ---------------------------------------------------------------------------
{
    const html = readFileSync(join(fixtureDir, "reel-doc.html"), "utf8");
    const suggestedMedia = {
        code: "SUGGESTEDaa1", pk: "7770001", media_type: 2,
        video_versions: [{ type: 101, url: "https://instagram.example.fbcdn.net/v/suggested1.mp4?sig=S", width: 720, height: 1280 }],
        user: { username: "creator_sugg" }, video_duration: 8.8
    };
    const suggested = { require: [["s", "s", "s",
        [{ __bbox: { result: { data: { clips_tray: { media: suggestedMedia } } } } }]]] };
    const docWithSuggested = html.replace("</body>",
        `<script type="application/json" data-sjs>${JSON.stringify(suggested)}</script></body>`);
    const { fed, emits } = await feed("https://www.instagram.com/reel/CqkwFBfLhUi/",
        "main_frame", 26, "docReelSugg", docWithSuggested);
    check("permalink gate: filter fed", fed);
    const origins = originsOf(emits);
    check("permalink gate: addressed reel emitted", origins.some(o => o.endsWith("/p/CqkwFBfLhUi")),
        JSON.stringify(origins));
    check("permalink gate: preloaded suggested clip NOT emitted",
        !origins.some(o => o.includes("SUGGESTEDaa1")), JSON.stringify(origins));
}

// ---------------------------------------------------------------------------
// 5. Legacy video_url node under an unknown wrapper — via the shape walk
// ---------------------------------------------------------------------------
{
    const body = readFileSync(join(fixtureDir, "legacy-video-url.json"), "utf8");
    const { fed, emits } = await feed("https://www.instagram.com/api/graphql",
        "xmlhttprequest", 23, "gqlLegacy", body);
    check("legacy: filter fed", fed);
    const origins = originsOf(emits);
    check("legacy: video_url node emitted via walk", origins.some(o => o.includes("LEGACYvid01")),
        JSON.stringify(origins));
    if (emits.length) {
        const m = emits.find(s => (s.msg.origin || "").includes("LEGACYvid01")).msg;
        check("legacy: variant is the video_url", m.variants[0].url.includes("legacy-clip.mp4"), m.variants[0].url);
        check("legacy: owner + caption fallbacks", m.name === "creator_legacy"
            && m.description === "Sample caption text", JSON.stringify([m.name, m.description]));
    }
}

// ---------------------------------------------------------------------------
// 6. NDJSON body with per-line junk — tolerant per-line parse
// ---------------------------------------------------------------------------
{
    const mkItem = (code, pk) => ({ data: { stream_wrapper: { media: {
        code, pk, media_type: 2,
        video_versions: [{ type: 101, url: `https://instagram.example.fbcdn.net/v/${code}.mp4?sig=S`, width: 720, height: 1280 }],
        user: { username: "creator_nd" }, caption: { text: "Sample caption text" }, video_duration: 3.2
    } } } });
    const body = JSON.stringify(mkItem("NDJSONaaa01", "111")) + "\n"
        + "for (;;);" + JSON.stringify(mkItem("NDJSONbbb02", "222"));
    const { emits } = await feed("https://www.instagram.com/api/graphql",
        "xmlhttprequest", 24, "gqlNd", body);
    const origins = originsOf(emits);
    check("ndjson: both lines emitted (incl. prefixed line)",
        origins.some(o => o.includes("NDJSONaaa01")) && origins.some(o => o.includes("NDJSONbbb02")),
        JSON.stringify(origins));
}

// ---------------------------------------------------------------------------
// 7. Comet router body — POST /ajax/route-definition/ (the logged-out mobile
//    permalink's only video carrier; HAR 26-09-04 missed it entirely)
// ---------------------------------------------------------------------------
{
    const body = readFileSync(join(fixtureDir, "route-definition.ndjson"), "utf8");
    const { fed, emits } = await feed("https://www.instagram.com/ajax/route-definition/",
        "xmlhttprequest", 26, "routeDef", body);
    check("route-definition: filter fed", fed);
    check("route-definition: exactly one emit", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("route-definition: canonical origin", (m.origin || "").endsWith("/p/RTDEFvid001"), m.origin);
        check("route-definition: DASH renditions carry audioUrl", m.variants.some(v => v.audioUrl),
            JSON.stringify(m.variants.map(v => ({ h: v.height, a: !!v.audioUrl }))));
        check("route-definition: username from the item", m.name === "creator_route", m.name);
    }
}

// ---------------------------------------------------------------------------
// 8. String-scan fallback — the two blind spots of a JSON walk
// ---------------------------------------------------------------------------
{
    const item = (code, pk) => ({
        code, pk, media_type: 2,
        video_versions: [{ type: 101, url: `https://instagram.example.fbcdn.net/v/${code}.mp4?sig=S`, width: 720, height: 1280 }],
        user: { username: "creator_scan" }, caption: { text: "Sample caption text" }, video_duration: 4.5
    });
    // (a) Bloks-style: the item is a JSON STRING inside the JSON (the walk
    //     sees one opaque string). Nested one level deeper for good measure.
    const innerDoc = JSON.stringify({ layout: { bloks_payload: { data: { media: item("BLOKSvid001", "501") } } } });
    const body = JSON.stringify({ data: { fetch_bloks_payload_for_comet: { payload: innerDoc } } });
    const a = await feed("https://www.instagram.com/api/graphql", "xmlhttprequest", 27, "scanBloks", body);
    check("scan: string-embedded item emitted", originsOf(a.emits).some(o => o.includes("BLOKSvid001")),
        JSON.stringify(originsOf(a.emits)));
    // (b) Beyond the walk's depth cap (40): the item at nesting 45.
    let deep = item("DEEPvid0001", "502");
    for (let i = 0; i < 45; i++) deep = { wrap: deep };
    const b = await feed("https://www.instagram.com/api/graphql", "xmlhttprequest", 28, "scanDeep", JSON.stringify(deep));
    check("scan: item past the walk's depth cap emitted", originsOf(b.emits).some(o => o.includes("DEEPvid0001")),
        JSON.stringify(originsOf(b.emits)));
    // (c) A JS-like body that merely mentions the key must not produce
    //     anything (the gate lets it through on the marker; the scan finds
    //     no parseable object around it).
    const c = await feed("https://www.instagram.com/some/script-ish/", "xmlhttprequest", 29, "scanJs",
        '(function(){var k="video_versions";return {k:k};})()');
    check("scan: JS-like body emits nothing", c.emits.length === 0, c.emits.length);
    // (d) The gate: a plain-text body with no marker is skipped before parse.
    const d = await feed("https://www.instagram.com/ajax/bz", "xmlhttprequest", 30, "gateText", "ok");
    check("gate: text beacon emits nothing", d.fed && d.emits.length === 0, JSON.stringify(d));
}

// ---------------------------------------------------------------------------
// 9. SPA handler — one deferred fallback per (tab, shortcode), skipped once
//    a filter has captured the page (the "stuck in a loop" log: tabs.onUpdated
//    ticks 3-4× per load and each tick used to fire the GraphQL fetch).
// ---------------------------------------------------------------------------
{
    const onUpdated = registrations.filter(r => r.path === "tabs.onUpdated").map(r => r.fn);
    const tick = (tabId, url) => { for (const fn of onUpdated) fn(tabId, { url }, { id: tabId, url, incognito: false }); };
    const settle = (ms) => new Promise(r => setTimeout(r, ms));

    // (a) An UNCAPTURED permalink: four ticks → one fetch, and only after the grace.
    cookieReads = 0;
    const urlA = "https://www.instagram.com/p/SPAuncap01/?l=1";
    tick(41, urlA); tick(41, urlA); tick(41, urlA); tick(41, urlA);
    await settle(300);
    check("spa: no fetch before the grace", cookieReads === 0, cookieReads);
    await settle(2600);
    check("spa: four ticks collapse to ONE fallback fetch", cookieReads === 1, cookieReads);

    // (b) A permalink the router filter captured first: the deferred fallback is skipped.
    cookieReads = 0;
    const routeBody = readFileSync(join(fixtureDir, "route-definition.ndjson"), "utf8");
    const urlB = "https://www.instagram.com/p/RTDEFvid001/";
    tick(42, urlB);
    const r = await feed("https://www.instagram.com/ajax/route-definition/", "xmlhttprequest", 42, "spaRoute", routeBody);
    check("spa: router filter captured the page", r.emits.length >= 1, r.emits.length);
    await settle(2700);
    check("spa: captured page fires NO fallback fetch", cookieReads === 0, cookieReads);

    // (c) The same shortcode in ANOTHER tab is its own decision (tab-scoped key).
    cookieReads = 0;
    tick(43, urlA);
    await settle(2700);
    check("spa: second tab gets its own fallback", cookieReads === 1, cookieReads);
}

// ---------------------------------------------------------------------------
// 10. Negative — a media-less response emits nothing
// ---------------------------------------------------------------------------
{
    const { emits } = await feed("https://www.instagram.com/api/v1/web/data/shared_data/",
        "xmlhttprequest", 25, "gqlNoise",
        JSON.stringify({ config: { csrf_token: "x" }, entry_data: {}, hostname: "www.instagram.com" }));
    check("noise: no emits from media-less body", emits.length === 0, emits.length);
}

console.log(failures ? `\ninstagram-replay: ${failures} FAILURE(S)` : "\ninstagram-replay: all checks passed");
process.exit(failures ? 1 : 0);
