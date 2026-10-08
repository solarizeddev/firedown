// Parsers replay — drives the REAL registered listeners of every site parser
// that doesn't yet have its own dedicated fixture replay (instagram-replay.mjs
// and dailymotion-replay.mjs cover those two with sanitized-HAR fixtures;
// webrequests-smoke.mjs covers Twitter/Telegram/Spotify at the exported-helper
// level). Covered here, at the LISTENER level (registration + pattern match +
// filter/fetch + extraction + emit, end to end): TikTok, Bluesky, Facebook,
// Vimeo, Rumble, Kick, Twitch, Niconico, Apple Podcasts, News Over Audio,
// Videee, Deezer, Substack, Acast, and X/Twitter's GraphQL listener.
//
// The bodies are SYNTHETIC but SHAPE-FAITHFUL — built from the wire shapes
// each parser documents in its header comments (which came from real HARs).
// That makes this a REGRESSION net (a refactor can't silently break an
// extraction path or an emit field), not proof the shapes still match today's
// live sites — for a "site changed its API" bug, get a fresh HAR and follow
// CLAUDE.md's debugging order. Run: node scripts/parsers-replay.mjs (any cwd).
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const parserDir = join(scriptDir, "..", "app/src/main/assets/webrequests/js/parsers");

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
        sendMessage: async () => false,
        onMessage: evt("runtime.onMessage"),
        connectNative: () => ({ onMessage: evt("port.onMessage"), onDisconnect: evt("port.onDisconnect"), postMessage() {} }),
    },
    webRequest: {
        onBeforeRequest: evt("webRequest.onBeforeRequest"),
        onBeforeSendHeaders: evt("webRequest.onBeforeSendHeaders"),
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
        query: async () => [], get: async (id) => ({ id, incognito: false, url: "https://example.com/" }),
        sendMessage: async () => {},
    },
    cookies: { onChanged: evt("cookies.onChanged"), getAll: async () => [] },
    storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
};

// Several parsers read navigator.userAgent when building emit headers.
Object.defineProperty(globalThis, "navigator", {
    value: { userAgent: "Mozilla/5.0 (Android 12; Mobile; rv:154.0) Gecko/154.0 Firefox/154.0", languages: ["en-US"] },
    configurable: true,
});

// ---------------------------------------------------------------------------
// fetch stub — serves the API bodies the fetch-driven parsers request
// themselves (Vimeo config, Kick clips/channels, Twitch GQL, iTunes lookup).
// ---------------------------------------------------------------------------
const jsonResp = (obj) => ({ ok: true, status: 200, text: async () => JSON.stringify(obj), json: async () => obj });
const notFound = { ok: false, status: 404, text: async () => "", json: async () => ({}) };

const VIMEO_CONFIG = {
    request: { files: { hls: { default_cdn: "ak", cdns: { ak: { avc_url: "https://player.vimeo.com/play/master.m3u8?s=SCRUBBED" } } } } },
    video: {
        title: "Sample Vimeo clip", url: "https://vimeo.com/12345", duration: 90,
        owner: { name: "Sample Owner" }, thumbs: { "1280": "https://i.vimeocdn.example/t_1280.jpg" },
    },
};
const KICK_CLIP = { clip: {
    id: "clip_01ABC", title: "Sample clip title", duration: 30,
    thumbnail_url: "https://images.kick.example/clip.jpg",
    channel: { username: "streamer" },
    video_url: "https://clips.kick.example/c1/clip_01ABC/playlist.m3u8",
} };
const KICK_CHANNEL = {
    slug: "somestreamer",
    playback_url: "https://playback.live-video.example/api/video/v1/master.m3u8?token=SCRUBBED",
    user: { username: "SomeStreamer", profilepic: "https://images.kick.example/pic.jpg" },
    livestream: {
        is_live: true, session_title: "Playing games",
        categories: [{ name: "Just Chatting" }],
        thumbnail: { responsive: "https://images.kick.example/thumb/720.webp 720w, https://images.kick.example/thumb/360.webp 360w" },
    },
};
const TWITCH_CLIP_GQL = { data: { clip: {
    playbackAccessToken: { value: "{\"clip_uri\":\"\"}", signature: "sigSCRUBBED" },
    videoQualities: [
        { sourceURL: "https://production.assets.clips.twitchcdn.example/x/1080.mp4", quality: "1080", frameRate: 60 },
        { sourceURL: "https://production.assets.clips.twitchcdn.example/x/720.mp4", quality: "720", frameRate: 60 },
    ],
    broadcaster: { displayName: "Streamer", login: "streamer" },
    title: "Nice play", thumbnailURL: "https://clips-media.twitchcdn.example/thumb.jpg",
    durationSeconds: 29.9,
} } };
const TWITCH_LIVE_GQL = [
    { data: { user: {
        displayName: "SomeStreamer", login: "somestreamer",
        profileImageURL: "https://static.twitchcdn.example/profile.png",
        stream: { title: "Live title", previewImageURL: "https://static.twitchcdn.example/preview.jpg", game: { displayName: "Chess" } },
    } } },
    { data: { streamPlaybackAccessToken: { value: "tok", signature: "sig", __typename: "PlaybackAccessToken" } } },
];
const ITUNES_LOOKUP = { resultCount: 2, results: [
    { kind: "podcast", collectionName: "Sample Show" },
    { kind: "podcast-episode", trackId: 3003, trackName: "Deep-linked Episode", collectionName: "Sample Show",
      episodeUrl: "https://cdn.publisher.example/ep3.mp3", artworkUrl600: "https://is1-ssl.mzstatic.example/600.jpg",
      trackTimeMillis: 60000 },
] };

// Acast feeder lookups the wire backbone / show-page handler make themselves,
// keyed by request path; filled in by the Acast section below.
const ACAST_FEEDER = {};
const acastFeederCalls = [];

globalThis.fetch = async (url, opts) => {
    if (url.includes("player.vimeo.com/video/")) return jsonResp(VIMEO_CONFIG);
    if (url.includes("kick.com/api/v2/clips/")) return jsonResp(KICK_CLIP);
    if (url.includes("kick.com/api/v2/channels/")) return jsonResp(KICK_CHANNEL);
    if (url.includes("gql.twitch.tv/gql")) {
        const body = String(opts?.body || "");
        if (body.includes("VideoAccessToken_Clip")) return jsonResp(TWITCH_CLIP_GQL);
        return jsonResp(TWITCH_LIVE_GQL);
    }
    if (url.includes("itunes.apple.com/lookup")) return jsonResp(ITUNES_LOOKUP);
    if (url.startsWith("https://phoenix.prod.ateam.acast.cloud/api/v1/shows/")) {
        acastFeederCalls.push(url);
        const hit = ACAST_FEEDER[new URL(url).pathname];
        return hit ? jsonResp(hit) : notFound;
    }
    return notFound;
};

// Import each parser under test — real modules, real registrations. (Videee
// transitively imports requests.js; its <all_urls> generic-catcher listeners
// never match the pattern check below, so they can't contaminate dispatches.)
for (const mod of ["tiktok", "bluesky", "facebook", "vimeo", "rumble", "kick",
                   "twitch", "niconico", "apple-podcasts", "newsoveraudio", "videee",
                   "deezer", "substack", "acast", "twitter"]) {
    await import(pathToFileURL(join(parserDir, mod + ".js")));
}

let failures = 0;
function check(name, cond, extra) {
    if (cond) console.log("PASS", name);
    else { failures++; console.log("FAIL", name, extra ?? ""); }
}

// Real WebExtension match-pattern semantics: `*.host` matches host and any
// subdomain; the path glob matches across path + query. `<all_urls>` and other
// non-URL patterns don't parse and therefore never match (deliberate — it
// keeps requests.js's generic listeners out of these dispatches).
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

// A missing `types` filter means all types (Bluesky's xrpc listener, Twitch's
// CDN m3u8 listener register that way on purpose).
function listenersMatching(url, type) {
    return registrations.filter(r =>
        r.path === "webRequest.onBeforeRequest"
        && (!r.filter?.types || r.filter.types.includes(type))
        && r.filter?.urls?.some(p => patternMatches(p, url)));
}

const isCapture = (s) => ["variants", "hls-master", "media", "deezer"].includes(s.msg?.type);

// Dispatch a request through EVERY matching listener; if a body is given and a
// listener created a response filter, feed it. Returns the listeners' sync
// return values (for redirect checks) plus the capture emits that followed.
async function drive(url, type, tabId, requestId, body) {
    const matching = listenersMatching(url, type);
    const before = nativeSent.length;
    const returns = matching.map(r => r.fn({ url, type, tabId, requestId, method: "GET" }));
    if (body != null) {
        const f = filters.get(requestId);
        if (f) {
            f.ondata({ data: new TextEncoder().encode(body).buffer });
            f.onstop();
        }
    }
    await new Promise(r => setTimeout(r, 60));
    return { matched: matching.length, returns, emits: nativeSent.slice(before).filter(isCapture) };
}

// ---------------------------------------------------------------------------
// TikTok
// ---------------------------------------------------------------------------
{
    // refer=embed strip: the redirect listener must return a redirectUrl with
    // the param removed (with it present TikTok renders the embed layout and
    // never fires the item_list XHRs).
    const { returns } = await drive("https://www.tiktok.com/@sampleuser?refer=embed", "main_frame", 10, "ttRedir");
    const redir = returns.find(r => r && r.redirectUrl);
    check("tiktok: refer=embed stripped via redirect",
        !!redir && !redir.redirectUrl.includes("refer=embed"), JSON.stringify(returns));

    // item_list feed: bitrateInfo renditions + playAddr-only item.
    const item = (id, extra) => ({
        id, desc: "Sample caption line one\nsecond line", author: { uniqueId: "sampleuser" },
        video: { width: 576, height: 1024, duration: 15, cover: "https://p16.tiktokcdn.example/cover.jpg", ...extra },
    });
    const feedBody = JSON.stringify({ itemList: [
        item("7300000000000000001", { bitrateInfo: [
            { Bitrate: 1200000, PlayAddr: { Width: 576, Height: 1024, UrlList: ["https://v16-webapp-prime.tiktok.example/video/hq.mp4?tk=tt_chain_token"] } },
            { Bitrate: 600000, PlayAddr: { Width: 288, Height: 512, UrlList: ["https://v16-webapp-prime.tiktok.example/video/lq.mp4?tk=tt_chain_token"] } },
        ] }),
        item("7300000000000000002", { playAddr: "https://v16-webapp-prime.tiktok.example/video/single.mp4?tk=tt_chain_token" }),
    ] });
    const feed = await drive("https://www.tiktok.com/api/post/item_list/?aid=1988&msToken=SCRUBBED",
        "xmlhttprequest", 10, "ttFeed", feedBody);
    check("tiktok: one xhr listener", feed.matched === 1, feed.matched);
    check("tiktok: both feed items emitted", feed.emits.length === 2, feed.emits.length);
    if (feed.emits.length === 2) {
        const m = feed.emits[0].msg;
        check("tiktok: canonical @author/video origin",
            m.origin === "https://www.tiktok.com/@sampleuser/video/7300000000000000001", m.origin);
        check("tiktok: caption first line as name", m.name === "Sample caption line one", m.name);
        check("tiktok: duration s → ms", m.duration === 15000, m.duration);
        check("tiktok: bitrateInfo renditions best-first",
            JSON.stringify(m.variants.map(v => v.height)) === "[1024,512]", JSON.stringify(m.variants.map(v => v.height)));
        check("tiktok: replay headers carry the tiktok Origin",
            (m.requestHeaders || []).some(h => h.name === "Origin" && h.value === "https://www.tiktok.com"),
            JSON.stringify(m.requestHeaders?.map(h => h.name)));
    }

    // /newtab/ sub-segment feeds must match the item_list regex (≈half a
    // hashtag page's videos arrive there).
    const newtab = await drive("https://www.tiktok.com/api/challenge/item_list/newtab/?x=1",
        "xmlhttprequest", 11, "ttNewtab",
        JSON.stringify({ itemList: [item("7300000000000000003", { playAddr: "https://v16-webapp-prime.tiktok.example/video/nt.mp4" })] }));
    check("tiktok: /newtab/ sub-segment feed captured", newtab.emits.length === 1, newtab.emits.length);

    // Renamed wrapper → the deep-walk finds the first video-bearing array.
    const walked = await drive("https://www.tiktok.com/api/post/item_list/?renamed=1",
        "xmlhttprequest", 12, "ttWalk",
        JSON.stringify({ new_wrapper: { lists: [[item("7300000000000000004", { playAddr: "https://v16-webapp-prime.tiktok.example/video/dw.mp4" })]] } }));
    check("tiktok: renamed itemList found via deep-walk", walked.emits.length === 1, walked.emits.length);

    // Detail-page SSR: the __UNIVERSAL_DATA_FOR_REHYDRATION__ blob under a
    // video-detail scope, read from the DOCUMENT response.
    const ssrBlob = JSON.stringify({ __DEFAULT_SCOPE__: {
        "webapp.app-context": { language: "en" },
        "webapp.video-detail": { itemInfo: { itemStruct: item("7300000000000000009",
            { playAddr: "https://v16-webapp-prime.tiktok.example/video/ssr.mp4?tk=tt_chain_token" }) } },
    } });
    const ssrHtml = `<html><head><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">${ssrBlob}</script></head><body></body></html>`;
    const ssr = await drive("https://www.tiktok.com/@sampleuser/video/7300000000000000009",
        "main_frame", 13, "ttSsr", ssrHtml);
    check("tiktok: detail-page SSR item captured", ssr.emits.length === 1, ssr.emits.length);
    if (ssr.emits.length === 1) {
        check("tiktok: SSR origin from item id",
            ssr.emits[0].msg.origin === "https://www.tiktok.com/@sampleuser/video/7300000000000000009",
            ssr.emits[0].msg.origin);
    }
}

// ---------------------------------------------------------------------------
// Bluesky
// ---------------------------------------------------------------------------
{
    const PLAYLIST_A = "https://video.bsky.app/watch/did%3Aplc%3Aaaa/cid111/playlist.m3u8";
    const PLAYLIST_B = "https://video.bsky.app/watch/did%3Aplc%3Abbb/cid222/playlist.m3u8";
    const feedBody = JSON.stringify({ feed: [
        { post: {
            uri: "at://did:plc:aaa/app.bsky.feed.post/1",
            author: { handle: "alice.example.social", displayName: "Alice" },
            record: { text: "A sample caption about a clip" },
            embed: { $type: "app.bsky.embed.video#view", playlist: PLAYLIST_A,
                thumbnail: "https://video.bsky.app/watch/did%3Aplc%3Aaaa/cid111/thumbnail.jpg",
                aspectRatio: { width: 1080, height: 1920 } },
        } },
        // A QUOTED post: the video sits under record#viewRecord, which uses
        // author + value + embeds — the shape the old post-shape gate missed.
        { post: {
            uri: "at://did:plc:ccc/app.bsky.feed.post/2",
            author: { handle: "carol.example.social", displayName: "Carol" },
            record: { text: "look at this" },
            embed: { $type: "app.bsky.embed.record#view", record: {
                $type: "app.bsky.embed.record#viewRecord",
                author: { handle: "bob.example.social", displayName: "Bob" },
                value: { text: "Quoted clip caption" },
                embeds: [{ $type: "app.bsky.embed.video#view", playlist: PLAYLIST_B,
                    thumbnail: "https://video.bsky.app/watch/did%3Aplc%3Abbb/cid222/thumbnail.jpg" }],
            } },
        } },
    ] });
    const feed = await drive("https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed?feed=at%3A%2F%2Fsample",
        "xmlhttprequest", 30, "bskyFeed", feedBody);
    check("bsky: xrpc listener matched (typeless filter)", feed.matched === 1, feed.matched);
    check("bsky: both videos emitted (quoted-record shape included)", feed.emits.length === 2, feed.emits.length);
    if (feed.emits.length === 2) {
        const a = feed.emits.find(s => s.msg.url === PLAYLIST_A)?.msg;
        const b = feed.emits.find(s => s.msg.url === PLAYLIST_B)?.msg;
        check("bsky: hls-master emit, origin = playlist (per-video uid)",
            a?.type === "hls-master" && a?.origin === PLAYLIST_A, JSON.stringify([a?.type, a?.origin]));
        check("bsky: caption as name", a?.name === "A sample caption about a clip", a?.name);
        check("bsky: quoted video attributed to the QUOTED author + caption",
            b?.name === "Quoted clip caption" && (b?.description || "").includes("Bob"),
            JSON.stringify([b?.name, b?.description]));
        check("bsky: Referer rides the emit",
            (a?.requestHeaders || []).some(h => h.name === "Referer" && h.value === "https://bsky.app/"),
            JSON.stringify(a?.requestHeaders));
    }

    // Wire-master fallback: a master the JSON reader never saw → generic title.
    const unknown = await drive("https://video.bsky.app/watch/did%3Aplc%3Azzz/cid999/playlist.m3u8",
        "media", 31, "bskyWire1");
    check("bsky: unseen master emits off the wire with generic title",
        unknown.emits.length === 1 && unknown.emits[0].msg.name === "Bluesky video",
        JSON.stringify(unknown.emits.map(s => s.msg.name)));

    // Cache enrichment: the SPA-cache case — JSON was seen earlier (feed above),
    // the master hits the wire from a DIFFERENT tab → enriched, not generic.
    const enriched = await drive(PLAYLIST_A, "media", 32, "bskyWire2");
    check("bsky: cached metadata enriches the wire-master emit",
        enriched.emits.length === 1 && enriched.emits[0].msg.name === "A sample caption about a clip",
        JSON.stringify(enriched.emits.map(s => s.msg.name)));

    // Collapse: same master, same tab as the JSON capture → origin dedup.
    const dup = await drive(PLAYLIST_A, "media", 30, "bskyWire3");
    check("bsky: same-tab wire master collapses with the JSON capture", dup.emits.length === 0, dup.emits.length);

    // Our own native probe (tabId -1) must not re-capture.
    const own = await drive(PLAYLIST_B, "media", -1, "bskyWire4");
    check("bsky: tabId<0 master fetch ignored (own probe)", own.emits.length === 0, own.emits.length);
}

// ---------------------------------------------------------------------------
// Facebook
// ---------------------------------------------------------------------------
{
    const fbVideo = (id, extra) => ({
        id,
        playable_url_quality_hd: `https://video.xx.fbcdn.example/v/hd-${id}.mp4?_nc=1`,
        playable_url: `https://video.xx.fbcdn.example/v/sd-${id}.mp4?_nc=1`,
        playable_duration_in_ms: 32000,
        owner: { name: "Sample Page" },
        preferred_thumbnail: { image: { uri: "https://scontent.xx.fbcdn.example/t.jpg" } },
        savable_description: { text: "A sample description" },
        ...extra,
    });
    const single = await drive("https://www.facebook.com/api/graphql/", "xmlhttprequest", 40, "fbSingle",
        "for (;;);" + JSON.stringify({ data: { video: fbVideo("1000001") } }));
    check("fb: anti-hijack prefix stripped, video emitted", single.emits.length === 1, single.emits.length);
    if (single.emits.length === 1) {
        const m = single.emits[0].msg;
        check("fb: canonical watch origin", m.origin === "https://www.facebook.com/watch/?v=1000001", m.origin);
        check("fb: HD+SD variants best-first",
            JSON.stringify(m.variants.map(v => v.height)) === "[1080,480]", JSON.stringify(m.variants.map(v => v.height)));
        check("fb: owner + duration", m.name === "Sample Page" && m.duration === 32000,
            JSON.stringify([m.name, m.duration]));
    }

    // Streamed (NDJSON) GraphQL — each line an independent object.
    const nd = await drive("https://www.facebook.com/api/graphql/", "xmlhttprequest", 41, "fbNd",
        JSON.stringify({ data: { node: fbVideo("1000002") } }) + "\n"
        + JSON.stringify({ data: { node: fbVideo("1000003") } }));
    check("fb: NDJSON lines each emit", nd.emits.length === 2, nd.emits.length);

    // DASH-only node → the manifest URL as the single variant.
    const dash = await drive("https://www.facebook.com/api/graphql/", "xmlhttprequest", 42, "fbDash",
        JSON.stringify({ data: { video: {
            id: "1000004", playable_url_dash_hd: "https://video.xx.fbcdn.example/v/dash-1000004.mpd",
            owner: { name: "Sample Page" },
        } } }));
    check("fb: DASH-only node emits the manifest variant",
        dash.emits.length === 1 && dash.emits[0].msg.variants[0].url.endsWith("dash-1000004.mpd"),
        JSON.stringify(dash.emits.map(s => s.msg.variants?.[0]?.url)));
}

// ---------------------------------------------------------------------------
// Vimeo (config fetched by the parser itself; served by the fetch stub)
// ---------------------------------------------------------------------------
{
    const { matched, emits } = await drive("https://player.vimeo.com/video/12345?h=abc", "sub_frame", 45, "vimeo1");
    check("vimeo: player listener matched", matched === 1, matched);
    check("vimeo: hls master enumerated from config", emits.length === 1, emits.length);
    if (emits.length === 1) {
        const m = emits[0].msg;
        check("vimeo: hls-master emit with avc_url", m.type === "hls-master" && m.url.includes("master.m3u8"),
            JSON.stringify([m.type, m.url]));
        check("vimeo: canonical vimeo.com origin", m.origin === "https://vimeo.com/12345", m.origin);
        check("vimeo: title + owner + duration",
            m.name === "Sample Vimeo clip" && m.description === "Sample Owner" && m.duration === 90000,
            JSON.stringify([m.name, m.description, m.duration]));
    }
}

// ---------------------------------------------------------------------------
// Rumble
// ---------------------------------------------------------------------------
{
    // Watch embedJS with the HLS auto master (the preferred path).
    const hls = await drive("https://rumble.com/embedJS/u3/?request=video&ver=2&v=abc123",
        "xmlhttprequest", 50, "rumbleHls", JSON.stringify({
            title: "Sample video title", author: { name: "Sample Channel" }, duration: 62,
            i: "https://sp.rmbl.example/s8/1/thumb.jpg", l: "/v123abc-sample.html",
            ua: { hls: { auto: { url: "https://rumble.com/hls-vod/abc/playlist.m3u8" } } },
        }));
    check("rumble: embedJS emits the HLS master",
        hls.emits.length === 1 && hls.emits[0].msg.type === "hls-master",
        JSON.stringify(hls.emits.map(s => s.msg.type)));
    if (hls.emits.length === 1) {
        const m = hls.emits[0].msg;
        check("rumble: watch permalink origin + author + duration",
            m.origin === "https://rumble.com/v123abc-sample.html" && m.name === "Sample Channel" && m.duration === 62000,
            JSON.stringify([m.origin, m.name, m.duration]));
    }

    // No HLS → structured MP4 fallback (heights from the keys, skipProbe).
    const mp4 = await drive("https://rumble.com/embedJS/u3/?request=video&ver=2&v=def456",
        "xmlhttprequest", 51, "rumbleMp4", JSON.stringify({
            title: "MP4 only", author: { name: "Sample Channel" }, duration: 10, l: "/v456def-sample.html",
            u: { mp4: {
                "480": { url: "https://ak2.rmbl.example/def/480.mp4", meta: { w: 854, h: 480 } },
                "1080": { url: "https://ak2.rmbl.example/def/1080.mp4", meta: { w: 1920, h: 1080 } },
            } },
        }));
    check("rumble: mp4 fallback emits labelled variants best-first",
        mp4.emits.length === 1 && JSON.stringify(mp4.emits[0].msg.variants.map(v => v.height)) === "[1080,480]"
            && mp4.emits[0].msg.skipProbe === true,
        JSON.stringify(mp4.emits.map(s => s.msg.variants?.map(v => v.height))));

    // Shorts feed: per-item emit with its own metadata.
    const shorts = await drive("https://rumble.com/service.php?name=shorts.feed&offset=10&limit=10",
        "xmlhttprequest", 52, "rumbleShorts", JSON.stringify({ data: { items: [
            { title: "Short one", by: { name: "Shorts Author" }, thumb: "https://sp.rmbl.example/short1.jpg",
              duration: 21, url: "https://rumble.com/shorts/v999one",
              videos: [{ type: "mp4", url: "https://ak2.rmbl.example/short1/720.mp4", res: 720 }] },
        ] } }));
    check("rumble: shorts.feed item emits with its own title",
        shorts.emits.length === 1 && shorts.emits[0].msg.description === "Short one"
            && shorts.emits[0].msg.origin === "https://rumble.com/shorts/v999one",
        JSON.stringify(shorts.emits.map(s => [s.msg.description, s.msg.origin])));
}

// ---------------------------------------------------------------------------
// Kick (page-driven; APIs served by the fetch stub)
// ---------------------------------------------------------------------------
{
    const clip = await drive("https://kick.com/streamer/clips/clip_01ABC", "main_frame", 55, "kickClip");
    check("kick: clip page → API fetch → hls-master emit",
        clip.emits.length === 1 && clip.emits[0].msg.type === "hls-master",
        JSON.stringify(clip.emits.map(s => s.msg.type)));
    if (clip.emits.length === 1) {
        const m = clip.emits[0].msg;
        check("kick: clip origin + channel + title + duration",
            m.origin === "https://kick.com/clips/clip_01ABC" && m.name === "streamer"
                && m.description === "Sample clip title" && m.duration === 30000,
            JSON.stringify([m.origin, m.name, m.description, m.duration]));
    }

    const live = await drive("https://kick.com/somestreamer", "main_frame", 56, "kickLive");
    check("kick: channel page → live playback_url emitted", live.emits.length === 1, live.emits.length);
    if (live.emits.length === 1) {
        const m = live.emits[0].msg;
        check("kick: live title — category + srcset thumbnail",
            m.description === "Playing games — Just Chatting" && m.img === "https://images.kick.example/thumb/720.webp",
            JSON.stringify([m.description, m.img]));
    }

    // The same channel in a SECOND tab inside the burst window is that tab's
    // own capture (the claim used to be keyed by channel alone, so tab 58 got
    // nothing for 10 s).
    const live2 = await drive("https://kick.com/somestreamer", "main_frame", 58, "kickLive2");
    check("per-tab: kick — the same channel in a second tab still captures",
        live2.emits.length === 1 && live2.emits[0].msg.tabId === 58, JSON.stringify(live2.emits.map(s => s.msg.tabId)));
}

// ---------------------------------------------------------------------------
// Twitch (clip via GQL; live via the metadata↔CDN-master rendezvous)
// ---------------------------------------------------------------------------
{
    const clip = await drive("https://clips.twitch.tv/SampleClipSlug", "main_frame", 60, "twClip");
    check("twitch: clip page emits mp4 quality variants", clip.emits.length === 1, clip.emits.length);
    if (clip.emits.length === 1) {
        const m = clip.emits[0].msg;
        check("twitch: clip origin + broadcaster + duration",
            m.origin === "https://clips.twitch.tv/SampleClipSlug" && m.name === "Streamer" && m.duration === 29900,
            JSON.stringify([m.origin, m.name, m.duration]));
        check("twitch: variants carry sig+token best-first",
            JSON.stringify(m.variants.map(v => v.height)) === "[1080,720]"
                && m.variants[0].url.includes("sig=sigSCRUBBED") && m.variants[0].url.includes("token="),
            JSON.stringify(m.variants.map(v => [v.height, v.url.slice(0, 60)])));
    }

    // Live rendezvous: the page visit fetches GQL metadata; the player's own
    // CDN master fetch (resolved to the channel via the tab-URL cache) then
    // completes the rendezvous.
    const page = await drive("https://www.twitch.tv/somestreamer", "main_frame", 61, "twLivePage");
    check("twitch: channel page alone emits nothing (waits for the CDN master)",
        page.emits.length === 0, page.emits.length);
    const master = await drive("https://usher.ttvnw.net/api/channel/hls/somestreamer.m3u8?sig=S&token=T",
        "xmlhttprequest", 61, "twLiveMaster");
    check("twitch: CDN master completes the rendezvous", master.emits.length === 1, master.emits.length);
    if (master.emits.length === 1) {
        const m = master.emits[0].msg;
        check("twitch: rendezvous marries GQL metadata to the captured master",
            m.type === "hls-master" && m.origin === "https://www.twitch.tv/somestreamer"
                && m.name === "SomeStreamer" && m.description === "Live title — Chess"
                && m.url.includes("usher.ttvnw.net"),
            JSON.stringify([m.type, m.origin, m.name, m.description]));
    }
}

// Twitch, two tabs on one live channel, tab 67's page loading while tab 66's
// rendezvous still waits for its master. Keyed by login alone, tab 67's GQL
// fetch was skipped as "already processing" and its master completed TAB 66's
// rendezvous — the emit went out under tab 66 — and tab 66's own master then
// had nothing to marry.
{
    await drive("https://www.twitch.tv/deltachan", "main_frame", 66, "twDelta66");
    await drive("https://www.twitch.tv/deltachan", "main_frame", 67, "twDelta67");
    const m67 = await drive("https://usher.ttvnw.net/api/channel/hls/deltachan.m3u8?sig=S&token=T67", "xmlhttprequest", 67, "twDeltaM67");
    check("per-tab: twitch — tab 67's master completes tab 67's rendezvous",
        m67.emits.length === 1 && m67.emits[0].msg.tabId === 67, JSON.stringify(m67.emits.map(s => s.msg.tabId)));
    const m66 = await drive("https://usher.ttvnw.net/api/channel/hls/deltachan.m3u8?sig=S&token=T66", "xmlhttprequest", 66, "twDeltaM66");
    check("per-tab: twitch — …and tab 66's own master still completes its own",
        m66.emits.length === 1 && m66.emits[0].msg.tabId === 66, JSON.stringify(m66.emits.map(s => s.msg.tabId)));
}

// ---------------------------------------------------------------------------
// Niconico
// ---------------------------------------------------------------------------
{
    // watch-api caches metadata (no emit of its own).
    const meta = await drive("https://www.nicovideo.jp/api/watch/v3_guest/sm12345?actionTrackId=x",
        "xmlhttprequest", 65, "nicoMeta", JSON.stringify({ data: { video: {
            title: "Sample nico video", duration: 120,
            thumbnail: { largeUrl: "https://nicovideo.cdn.example/large.jpg" },
        } } }));
    check("nico: watch-api caches metadata without emitting", meta.emits.length === 0, meta.emits.length);

    // access-rights/hls carries the signed master → titled hls-master emit.
    const access = await drive("https://nvapi.nicovideo.jp/v1/watch/sm12345/access-rights/hls?actionTrackId=x",
        "xmlhttprequest", 65, "nicoAccess", JSON.stringify({ meta: { status: 201 }, data: {
            contentUrl: "https://delivery.domand.nicovideo.jp/hlsbid/xyz/playlists/variants/master.m3u8?Policy=SCRUBBED",
        } }));
    check("nico: access-rights master emitted as hls-master", access.emits.length === 1, access.emits.length);
    if (access.emits.length === 1) {
        const m = access.emits[0].msg;
        check("nico: enriched from the watch-api cache",
            m.name === "Sample nico video" && m.duration === 120000
                && m.origin === "https://www.nicovideo.jp/watch/sm12345",
            JSON.stringify([m.name, m.duration, m.origin]));
        check("nico: Origin+Referer headers for domand",
            (m.requestHeaders || []).some(h => h.name === "Origin" && h.value === "https://www.nicovideo.jp"),
            JSON.stringify(m.requestHeaders));
    }

    // The &__retry accept envelope (contentUrl is a bare query string) must be
    // ignored — mark-sent on it would dedup-block the real master.
    const envelope = await drive("https://nvapi.nicovideo.jp/v1/watch/sm67890/access-rights/hls?__retry=0",
        "xmlhttprequest", 66, "nicoEnv", JSON.stringify({ meta: { status: 200 }, data: {
            contentUrl: "?accepted=true&data=SCRUBBED",
        } }));
    check("nico: accept envelope ignored (no emit)", envelope.emits.length === 0, envelope.emits.length);
}

// ---------------------------------------------------------------------------
// Apple Podcasts
// ---------------------------------------------------------------------------
{
    // Show XHR (include=episodes): one media emit per episode with assetUrl.
    const show = await drive(
        "https://amp-api.podcasts.apple.com/v1/catalog/us/podcasts/123?include=artists,episodes,genres&limit[episodes]=15",
        "xmlhttprequest", 70, "apShow", JSON.stringify({ data: [{
            id: "123", type: "podcasts", attributes: { name: "Sample Show" },
            relationships: { episodes: { data: [
                { id: "1001", type: "podcast-episodes", attributes: {
                    name: "Episode One", assetUrl: "https://cdn.publisher.example/ep1.mp3",
                    durationInMilliseconds: 1800000,
                    artwork: { url: "https://is1-ssl.mzstatic.example/image/{w}x{h}bb.{f}" },
                    url: "https://podcasts.apple.com/us/podcast/sample-show/id123?i=1001",
                } },
                { id: "1002", type: "podcast-episodes", attributes: { name: "No asset yet" } },
            ] } },
        }] }));
    check("apple: show XHR emits only the asset-bearing episode", show.emits.length === 1, show.emits.length);
    if (show.emits.length === 1) {
        const m = show.emits[0].msg;
        check("apple: episode fields + skipProbe",
            m.name === "Episode One" && m.description === "Sample Show" && m.duration === 1800000
                && m.skipProbe === true && m.origin.endsWith("?i=1001"),
            JSON.stringify([m.name, m.description, m.duration, m.skipProbe, m.origin]));
        check("apple: artwork template resolved",
            m.img === "https://is1-ssl.mzstatic.example/image/600x600bb.jpg", m.img);
    }

    // Batch episodes XHR (play-queue): show name from relationships.podcast.
    const batch = await drive(
        "https://amp-api.podcasts.apple.com/v1/catalog/us/podcast-episodes?ids=2001&include=channel,podcast",
        "xmlhttprequest", 71, "apBatch", JSON.stringify({ data: [{
            id: "2001", type: "podcast-episodes",
            attributes: { name: "Queued Episode", assetUrl: "https://cdn.publisher.example/ep2.mp3",
                durationInMilliseconds: 900000 },
            relationships: { podcast: { data: [{ attributes: { name: "Sample Show" } }] } },
        }] }));
    check("apple: batch episode emits with the parent show name",
        batch.emits.length === 1 && batch.emits[0].msg.description === "Sample Show",
        JSON.stringify(batch.emits.map(s => [s.msg.name, s.msg.description])));

    // Deep-link (?i=): main_frame trigger → iTunes Lookup fallback.
    const deep = await drive("https://podcasts.apple.com/us/podcast/sample-show/id123?i=3003",
        "main_frame", 72, "apDeep");
    check("apple: ?i= deep link resolves via iTunes Lookup",
        deep.emits.length === 1 && deep.emits[0].msg.name === "Deep-linked Episode"
            && deep.emits[0].msg.skipProbe === true,
        JSON.stringify(deep.emits.map(s => s.msg.name)));
}

// ---------------------------------------------------------------------------
// News Over Audio
// ---------------------------------------------------------------------------
{
    const noa = await drive("https://api.newsoveraudio.com/v1/player/article?code=https%3A%2F%2Fpub.example%2Fstory",
        "xmlhttprequest", 75, "noa1", JSON.stringify({ message: "Article found", data: { article: {
            id: 4242, name: "Sample article title",
            audio: "https://audios.newsoveraudio.com/articles/medium/4242.mp3?Expires=1&Signature=SCRUBBED",
            audioLength: 1009.881, image: null,
            articleOriginUrl: "https://pub.example/story",
            publisher: { name: "Sample Publisher", largeImage: "https://images.newsoveraudio.example/pub.png" },
        } } }));
    check("noa: article audio emitted as titled media", noa.emits.length === 1, noa.emits.length);
    if (noa.emits.length === 1) {
        const m = noa.emits[0].msg;
        check("noa: name/publisher/origin/duration(s→ms)/skipProbe",
            m.name === "Sample article title" && m.description === "Sample Publisher"
                && m.origin === "https://pub.example/story" && m.duration === 1009881 && m.skipProbe === true,
            JSON.stringify([m.name, m.description, m.origin, m.duration, m.skipProbe]));
        check("noa: publisher image as thumbnail fallback",
            m.img === "https://images.newsoveraudio.example/pub.png", m.img);
    }
}

// ---------------------------------------------------------------------------
// Videee
// ---------------------------------------------------------------------------
{
    const MP4_A = "https://media.videee.com/123e4567-e89b-12d3-a456-426614174000/1712-ainimals.mp4";
    const MP4_B = "https://media.videee.com/123e4567-e89b-12d3-a456-426614174000/1713-other.mp4";
    const rows = await drive(
        "https://auth.videee.com/rest/v1/videos?select=id%2Ctitle%2Cthumbnail_url%2Cvideo_url&order=created_at.desc",
        "xmlhttprequest", 80, "vidRows", JSON.stringify([
            { id: 235, title: "AInimals", thumbnail_url: "https://media.videee.com/thumbnails/u/235.jpg", video_url: MP4_A },
        ]));
    check("videee: rest rows emit per-clip titled variants",
        rows.emits.length === 1 && rows.emits[0].msg.name === "AInimals" && rows.emits[0].msg.origin === MP4_A,
        JSON.stringify(rows.emits.map(s => [s.msg.name, s.msg.origin])));
    if (rows.emits.length === 1) {
        check("videee: <video>-element header shape",
            (rows.emits[0].msg.requestHeaders || []).some(h => h.name === "Sec-Fetch-Dest" && h.value === "video"),
            JSON.stringify(rows.emits[0].msg.requestHeaders));
    }

    // Wire fallback: an mp4 the JSON reader never saw → generic title.
    const wire = await drive(MP4_B, "media", 81, "vidWire1");
    check("videee: unseen media emits off the wire with generic title",
        wire.emits.length === 1 && wire.emits[0].msg.name === "Videee video",
        JSON.stringify(wire.emits.map(s => s.msg.name)));

    // Collapse: the JSON-captured clip's media on the SAME tab → deduped.
    const dup = await drive(MP4_A, "media", 80, "vidWire2");
    check("videee: same-tab wire media collapses with the JSON capture", dup.emits.length === 0, dup.emits.length);
}

// ---------------------------------------------------------------------------
// Deezer — the gw-light gateway listener: song walk + emit shape + the
// logged-out (no cookie) skip. Drives the REAL registered onBeforeRequest
// listener end to end (pattern match → filterResponseData → walk → cookie →
// sendNative).
// ---------------------------------------------------------------------------
{
    const GW = "https://www.deezer.com/ajax/gw-light.php?method=deezer.pageTrack&api_version=1.0";
    const gwBody = JSON.stringify({ error: [], results: { DATA: {
        SNG_ID: "3135556", SNG_TITLE: "Sample Track", ART_NAME: "Sample Artist",
        ALB_PICTURE: "aabbccddeeff00112233445566778899", DURATION: "215",
        TRACK_TOKEN: "tok-SCRUBBED", FILESIZE_FLAC: "0",
        FILESIZE_MP3_320: "8200000", FILESIZE_MP3_128: "3400000",
    } } });

    // Logged out: cookies.getAll returns nothing → the parser must NOT emit
    // (only the 30s preview exists for a logged-out visitor, and that's the
    // generic catcher's job).
    browser.cookies.getAll = async () => [];
    const out = await drive(GW, "xmlhttprequest", 40, "dz1", gwBody);
    check("deezer: logged-out (no cookie) emits nothing", out.emits.length === 0, out.emits.length);

    // Logged in: a real session cookie (arl is HttpOnly — cookies.getAll is the
    // privileged path that returns it).
    browser.cookies.getAll = async () => [
        { name: "arl", value: "SCRUBBED_ARL" }, { name: "sid", value: "fr9999" },
    ];
    const on = await drive(GW, "xmlhttprequest", 41, "dz2", gwBody);
    check("deezer: one gw-light listener", on.matched === 1, on.matched);
    check("deezer: logged-in emits the track", on.emits.length === 1, on.emits.length);
    if (on.emits.length === 1) {
        const m = on.emits[0].msg;
        check("deezer: type=deezer (routes to DeezerStrategy)", m.type === "deezer", m.type);
        check("deezer: synthetic track URL carries SNG_ID + best fmt",
            m.url === "https://www.deezer.com/track/3135556?fmt=MP3_320", m.url);
        check("deezer: origin is the bare track page", m.origin === "https://www.deezer.com/track/3135556", m.origin);
        check("deezer: name/artist", m.name === "Sample Track" && m.description === "Sample Artist",
            JSON.stringify([m.name, m.description]));
        check("deezer: duration s → ms", m.duration === 215000, m.duration);
        check("deezer: cover from ALB_PICTURE",
            typeof m.img === "string" && m.img.includes("/cover/aabbccddeeff00112233445566778899/"), m.img);
        check("deezer: session cookie rides the emit",
            typeof m.cookie === "string" && m.cookie.includes("arl=SCRUBBED_ARL"), (m.cookie || "").slice(0, 20));
        check("deezer: encrypted size carried for the sheet", m.size === 8200000, m.size);
    }

    // A second read of the same track on the same tab collapses (30s TTL dedup).
    const dup = await drive(GW, "xmlhttprequest", 41, "dz3", gwBody);
    check("deezer: same track re-read within TTL is deduped", dup.emits.length === 0, dup.emits.length);
}

// ---------------------------------------------------------------------------
// Substack — the reader-feed JSON listener (shape walk + per-episode emit),
// the post-page `_preloads` document filter, and the wire backbone (metadata
// cache hit → no duplicate emit; miss → the page's own og metadata). Drives
// the REAL registered onBeforeRequest listeners end to end.
// ---------------------------------------------------------------------------
{
    const EP = "https://api.substack.com/api/v1/audio/upload/0f4b3f1e-2a7c-4c1d-9b6e-1234567890ab/src";
    const TTS = "https://substack-video.s3.amazonaws.com/video_upload/post/215647157/tts/9a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d/es-female.mp3";
    const post = {
        id: 215647157, title: "En tiempo real", type: "podcast",
        canonical_url: "https://samplepub.substack.com/p/en-tiempo-real",
        podcast_url: EP, podcast_duration: "110.44572",
        podcast_episode_image_url: "https://substackcdn.example/image/episode.jpg",
        cover_image: null, podcast_art_url: "https://substackcdn.example/image/art.jpg",
        publishedBylines: [{ id: 1, name: "Sample Author" }],
        audio_items: [{ type: "tts", audio_url: TTS, status: "completed" }],
    };
    const feedBody = JSON.stringify({ items: [
        { type: "post", publication: { name: "Sample Publication", subdomain: "samplepub" }, post },
        // A newsletter post without audio must walk past.
        { type: "post", publication: { name: "Sample Publication" }, post: { id: 1, title: "Text only", podcast_url: null, audio_items: [] } },
    ], more: false });
    const FEED = "https://substack.com/api/v1/reader/feed/profile/12345?limit=20";

    const out = await drive(FEED, "xmlhttprequest", 90, "ss1", feedBody);
    check("substack: feed JSON listener matched", out.matched >= 1, out.matched);
    check("substack: one episode + one voiceover emitted", out.emits.length === 2,
        JSON.stringify(out.emits.map(s => s.msg.name)));
    const ep = out.emits.find(s => s.msg.url === EP)?.msg;
    const tts = out.emits.find(s => s.msg.url === TTS)?.msg;
    check("substack: episode carries title/author/cover/canonical origin",
        !!ep && ep.type === "media" && ep.name === "En tiempo real" && ep.description === "Sample Author"
            && ep.img === post.podcast_episode_image_url && ep.origin === post.canonical_url,
        JSON.stringify(ep));
    check("substack: podcast_duration seconds → ms + skipProbe",
        !!ep && ep.duration === 110446 && ep.skipProbe === true, JSON.stringify([ep?.duration, ep?.skipProbe]));
    check("substack: voiceover titled '(voiceover)', no duration",
        !!tts && tts.name === "En tiempo real (voiceover)" && tts.duration === undefined && tts.skipProbe === undefined,
        JSON.stringify(tts));

    // Re-read of the same feed (refresh / pagination overlap) → deduped.
    const dup = await drive(FEED, "xmlhttprequest", 90, "ss2", feedBody);
    check("substack: same episode re-read within TTL is deduped", dup.emits.length === 0, dup.emits.length);
    // …but the same feed in a SECOND tab is that tab's own capture.
    const other = await drive(FEED, "xmlhttprequest", 91, "ss2b", feedBody);
    check("per-tab: substack — the same feed in a second tab emits both entries",
        other.emits.length === 2 && other.emits.every(s => s.msg.tabId === 91), JSON.stringify(other.emits.map(s => s.msg.tabId)));

    // The wire backbone on a URL the feed already described: the repository
    // would dedup by URL anyway; the parser must not re-emit within the TTL.
    const hit = await drive(EP, "media", 90, "ss3");
    check("substack: wire fetch of a feed-described episode does not re-emit", hit.emits.length === 0, hit.emits.length);

    // A post page on a substack.com host: the SSR `_preloads` blob carries the
    // post (JS string literal → JSON text → JSON), with a different episode.
    const post2 = { ...post, id: 215647158, title: 'Segundo "episodio"',
        canonical_url: "https://samplepub.substack.com/p/segundo",
        podcast_url: "https://api.substack.com/api/v1/audio/upload/11111111-2222-4333-8444-555555555555/src",
        audio_items: [] };
    const preloads = { post: post2, pub: { name: "Sample Publication" } };
    const html = `<html><head><title>x</title></head><body><script>window._preloads = JSON.parse(${JSON.stringify(JSON.stringify(preloads))})</script></body></html>`;
    const doc = await drive("https://samplepub.substack.com/p/segundo", "main_frame", 91, "ss4", html);
    check("substack: post page _preloads emits the episode", doc.emits.length === 1
        && doc.emits[0].msg.name === 'Segundo "episodio"' && doc.emits[0].msg.description === "Sample Author",
        JSON.stringify(doc.emits.map(s => s.msg)));

    // Wire backbone MISS (a custom-domain publication: no feed, no substack.com
    // document): the parser asks the media's frame for the page metadata.
    const prevSend = browser.tabs.sendMessage;
    browser.tabs.sendMessage = async (tabId, msg) => (msg?.kind === "get-page-metadata"
        ? { url: "https://custom.example/p/episode-three", ogTitle: "Episode three", ogDescription: "About it",
            ogImage: "https://custom.example/cover.jpg", title: "Episode three - Custom" }
        : undefined);
    const EP3 = "https://api.substack.com/api/v1/audio/upload/99999999-8888-4777-8666-555555555555/src";
    const miss = await drive(EP3, "media", 92, "ss5");
    browser.tabs.sendMessage = prevSend;
    check("substack: unseen wire episode is titled from its page's og metadata",
        miss.emits.length === 1 && miss.emits[0].msg.name === "Episode three"
            && miss.emits[0].msg.img === "https://custom.example/cover.jpg"
            && miss.emits[0].msg.origin === "https://custom.example/p/episode-three",
        JSON.stringify(miss.emits.map(s => s.msg)));

    // Wire backbone with NO page answer at all → generic title, still captured
    // (the media hosts are block-listed, so this is the only capture there).
    browser.tabs.sendMessage = async () => undefined;
    const EP4 = "https://api.substack.com/api/v1/audio/upload/77777777-6666-4555-8444-333333333333/src";
    const bare = await drive(EP4, "media", 93, "ss6");
    browser.tabs.sendMessage = prevSend;
    check("substack: wire episode with no page metadata still captures (generic title)",
        bare.emits.length === 1 && bare.emits[0].msg.name === "Substack audio", JSON.stringify(bare.emits.map(s => s.msg.name)));

    // A video upload on the S3 host is not the parser's (stays with the catcher).
    const vid = await drive("https://substack-video.s3.amazonaws.com/video_upload/post/1/abc/720p.mp4", "media", 93, "ss7");
    check("substack: S3 video upload is not captured by the parser", vid.emits.length === 0, vid.emits.length);
}

// ---------------------------------------------------------------------------
// Acast — the embed player's feeder API JSON (the elmundo.es HAR shape, with
// sample values), the sphinx wire backbone (cache hit → no duplicate; miss →
// the parser's own feeder lookup; feeder down → generic title), a show-embed
// episode LIST, and Acast's own show page via the SPA handler.
// ---------------------------------------------------------------------------
{
    const SHOW = "5f0c0ffee0ddba11ad5eed01";
    const EPA = "6a0000000000000000000001";
    const media = (ep) => `https://sphinx.acast.com/p/open/s/${SHOW}/e/${ep}/media.mp3`;
    const thumb = (n, path) => `https://thumborcdn.acast.example/sig${n}=/${n}x${n}/${encodeURIComponent("https://assets.pippa.example/" + path)}`;
    const images = (path) => ({ x150: thumb(150, path), x350: thumb(350, path), x500: thumb(500, path),
        x1000: thumb(1000, path), original: "https://assets.pippa.example/" + path });
    const show = {
        title: "Sample Show &amp; Friends", author: "Sample Network - Host Name", language: "es",
        link: "https://publisher.example/", image: "https://assets.pippa.example/show-cover.jpg",
        images: images("show-cover.jpg"), feedUrl: `https://feeds.acast.example/public/shows/${SHOW}`,
        showUrl: "sample-show", showId: SHOW, categories: ["News"],
        description: "<p>Show <strong>summary</strong></p>",
    };
    const episode = (id, title, extra = {}) => ({
        showId: SHOW, id, url: media(id), contentLength: 11024927, contentType: "audio/mpeg",
        link: `https://shows.acast.example/sample-show/episodes/${id}`, title,
        description: "<strong>Episode</strong> notes", image: `https://assets.pippa.example/${id}.jpeg`,
        duration: 689, explicit: false, publishDate: "2026-10-04T22:01:08.000Z", isAcast: true, premium: false,
        acastSettings: "SCRUBBED", images: images(id + ".jpeg"), episodeUrl: "slug-" + id, episodeType: "full",
        cleanTitle: title, metadataUrl: media(id).replace("media.mp3", "media.json"), ...extra,
    });
    const API = `https://phoenix.prod.ateam.acast.cloud/api/v1/shows/${SHOW}/episodes/${EPA}?showInfo=true`;
    const embedDoc = `https://embed.acast.com/${SHOW}/${EPA}?cover=false&bgColor=ff8822`;

    // 1. The embed player's episode lookup (showInfo=true nests the show).
    const body = JSON.stringify(episode(EPA, "Sánchez, la vivienda y las elecciones", { show }));
    const out = await drive(API, "xmlhttprequest", 70, "ac1", body);
    check("acast: feeder API listener matched", out.matched >= 1, out.matched);
    const m = out.emits[0]?.msg;
    check("acast: one titled audio entry per episode",
        out.emits.length === 1 && m.type === "media" && m.url === media(EPA)
            && m.name === "Sánchez, la vivienda y las elecciones",
        JSON.stringify(out.emits.map(s => s.msg)));
    check("acast: show title is the description (entities decoded), episode 500px cover is the thumbnail",
        !!m && m.description === "Sample Show & Friends" && m.img === thumb(500, EPA + ".jpeg"),
        JSON.stringify([m?.description, m?.img]));
    check("acast: duration seconds → ms + skipProbe, canonical episode page as origin",
        !!m && m.duration === 689000 && m.skipProbe === true
            && m.origin === `https://shows.acast.example/sample-show/episodes/${EPA}` && m.tabId === 70,
        JSON.stringify([m?.duration, m?.skipProbe, m?.origin, m?.tabId]));

    const dup = await drive(API, "xmlhttprequest", 70, "ac2", body);
    check("acast: same episode re-read within TTL is deduped", dup.emits.length === 0, dup.emits.length);

    // 2. The player's media fetch for that episode: already emitted → silent.
    const hit = await drive(media(EPA), "media", 70, "ac3");
    check("acast: wire fetch of an API-described episode does not re-emit or look it up",
        hit.matched >= 1 && hit.emits.length === 0 && acastFeederCalls.length === 0,
        JSON.stringify([hit.matched, hit.emits.length, acastFeederCalls]));

    // A second TAB playing the same episode is its own capture.
    const tab2 = await drive(media(EPA), "media", 71, "ac3b");
    check("acast: the same episode in another tab still captures (from the cache)",
        tab2.emits.length === 1 && tab2.emits[0].msg.name === "Sánchez, la vivienda y las elecciones"
            && tab2.emits[0].msg.tabId === 71 && acastFeederCalls.length === 0,
        JSON.stringify(tab2.emits.map(s => s.msg)));

    // 3. Wire MISS (a publisher's own player fed the RSS enclosure): the
    //    parser asks the feeder for that episode by the ids in the path.
    const EPB = "6a0000000000000000000002";
    ACAST_FEEDER[`/api/v1/shows/${SHOW}/episodes/${EPB}`] = episode(EPB, "Episodio dos", { show });
    const miss = await drive(media(EPB) + "?ref=publisher", "media", 72, "ac4");
    const mm = miss.emits[0]?.msg;
    check("acast: unseen wire episode is resolved through the feeder API",
        miss.emits.length === 1 && mm.name === "Episodio dos" && mm.description === "Sample Show & Friends"
            && mm.url === media(EPB) && mm.duration === 689000
            && acastFeederCalls.length === 1 && acastFeederCalls[0].includes(`/shows/${SHOW}/episodes/${EPB}?showInfo=true`),
        JSON.stringify([miss.emits.map(s => s.msg), acastFeederCalls]));
    // Range re-requests of the same play don't each look it up again.
    const again = await drive(media(EPB), "media", 72, "ac5");
    check("acast: Range re-request of the same play is silent", again.emits.length === 0 && acastFeederCalls.length === 1,
        JSON.stringify([again.emits.length, acastFeederCalls.length]));

    // 4. Feeder unreachable → still captured (sphinx is block-listed for the
    //    catcher, so this is the only capture), named generically.
    const EPC = "6a0000000000000000000003";
    const bare = await drive(media(EPC), "media", 73, "ac6");
    check("acast: wire episode with no feeder answer still captures (generic title)",
        bare.emits.length === 1 && bare.emits[0].msg.name === "Acast episode" && bare.emits[0].msg.url === media(EPC),
        JSON.stringify(bare.emits.map(s => s.msg)));

    // The catcher's HEAD probe of a scraped <audio src> is the extension's own
    // request, not a play.
    const probeBefore = nativeSent.length;
    for (const r of listenersMatching(media("6a0000000000000000000009"), "media")) {
        r.fn({ url: media("6a0000000000000000000009"), type: "media", tabId: -1, requestId: "ac7", method: "HEAD",
            documentUrl: "moz-extension://uuid/background.html" });
    }
    await new Promise(r => setTimeout(r, 60));
    check("acast: the extension's own HEAD probe is not captured", nativeSent.slice(probeBefore).filter(isCapture).length === 0);

    // 5. A show embed's episode LIST (no nested show; the show is the parent).
    const listBody = JSON.stringify({ ...show, episodes: [
        episode("6a0000000000000000000004", "Lista uno"),
        episode("6a0000000000000000000005", "Lista dos"),
        { title: "Trailer page", link: "https://shows.acast.example/x" },   // no audio → walks past
    ] });
    const list = await drive(`https://phoenix.prod.ateam.acast.cloud/api/v1/shows/${SHOW}?showInfo=true`, "xmlhttprequest", 74, "ac8", listBody);
    check("acast: a show list emits every episode, the parent show as description",
        list.emits.length === 2 && list.emits.every(e => e.msg.description === "Sample Show & Friends")
            && list.emits.map(e => e.msg.name).join("|") === "Lista uno|Lista dos",
        JSON.stringify(list.emits.map(s => s.msg)));

    // A non-episode acast.cloud API body never emits.
    const other = await drive("https://phoenix.prod.ateam.acast.cloud/api/v1/settings", "xmlhttprequest", 74, "ac9",
        JSON.stringify({ title: "Settings", url: "https://acast.example/settings" }));
    check("acast: a non-audio API body emits nothing", other.emits.length === 0, other.emits.length);

    // 6. Acast's own episode page: the SPA handler resolves the slugs through
    //    the feeder (once per page, however many tabs.onUpdated ticks fire).
    ACAST_FEEDER["/api/v1/shows/sample-show/episodes/episodio-tres"] = episode("6a0000000000000000000006", "Episodio tres", { show });
    const PAGE = "https://shows.acast.com/sample-show/episodes/episodio-tres";
    const calls = acastFeederCalls.length;
    const spaBefore = nativeSent.length;
    for (const r of registrations.filter(r => r.path === "tabs.onUpdated")) {
        r.fn(75, { url: PAGE }, { id: 75, url: PAGE, incognito: false });
        r.fn(75, { status: "complete" }, { id: 75, url: PAGE, incognito: false });
        r.fn(75, { status: "complete" }, { id: 75, url: PAGE, incognito: false });
    }
    await new Promise(r => setTimeout(r, 80));
    const spa = nativeSent.slice(spaBefore).filter(isCapture);
    check("acast: show page resolves its episode once via the feeder",
        spa.length === 1 && spa[0].msg.name === "Episodio tres" && spa[0].msg.tabId === 75
            && acastFeederCalls.length === calls + 1,
        JSON.stringify([spa.map(s => s.msg.name), acastFeederCalls.slice(calls)]));
}

// ---------------------------------------------------------------------------
// Audit regression net (2026-10): leak / race fixes across the parsers.
// ---------------------------------------------------------------------------
const regOf = (fnName) => registrations.find(r => r.path === "webRequest.onBeforeRequest" && r.fn.name === fnName);
const micro = async (n) => { for (let i = 0; i < n; i++) await null; };

// Observe-only listeners must not be registered blocking: a blocking async
// listener holds the page's own request until its promise settles (Vimeo
// re-fetched the embed before letting it load), and a blocking sync one holds
// every playlist refresh behind the background event loop (Twitch).
{
    const vimeo = regOf("listenerVimeo");
    check("audit: Vimeo listener registered (observe-only, not blocking)",
        !!vimeo && !(vimeo.extra || []).includes("blocking"), JSON.stringify(vimeo?.extra));
    const twitchCdn = regOf("listenerTwitchCdnM3u8");
    check("audit: Twitch CDN listener registered (observe-only, not blocking)",
        !!twitchCdn && !(twitchCdn.extra || []).includes("blocking"), JSON.stringify(twitchCdn?.extra));
}

// X: every video of a multi-video tweet is its own capture, and a media that
// emits nothing (HLS-only, no mp4 variant) is NOT marked rich-captured, so the
// wire-master fallback still takes it on play.
{
    const mp4 = (id, h) => ({ content_type: "video/mp4", bitrate: h * 1000,
        url: `https://video.twimg.com/ext_tw_video/${id}/pu/vid/avc1/${Math.round(h * 9 / 16)}x${h}/clip.mp4?tag=12` });
    const m3u8 = (id) => ({ content_type: "application/x-mpegURL", url: `https://video.twimg.com/ext_tw_video/${id}/pu/pl/abc.m3u8?tag=12` });
    const media = (id, variants) => ({ id_str: id, media_key: `13_${id}`, type: "video",
        media_url_https: `https://pbs.twimg.com/ext_tw_video_thumb/${id}/pu/img/t.jpg`,
        video_info: { duration_millis: 12000, variants } });
    const tweet = (restId, mediaList) => ({ __typename: "Tweet", rest_id: restId,
        core: { user_results: { result: { legacy: { screen_name: "sampleuser" } } } },
        legacy: { id_str: restId, full_text: "two clips in one post", extended_entities: { media: mediaList } } });
    const detail = (t) => JSON.stringify({ data: { threaded_conversation_with_injections_v2: { instructions: [
        { type: "TimelineAddEntries", entries: [{ entryId: `tweet-${t.rest_id}`, content: { itemContent: { tweet_results: { result: t } } } }] } ] } } });
    const url = (focal) => `https://x.com/i/api/graphql/abc123/TweetDetail?variables=${encodeURIComponent(JSON.stringify({ focalTweetId: focal }))}`;

    const two = await drive(url("9001"), "xmlhttprequest", 70, "twMulti",
        detail(tweet("9001", [media("500001", [mp4("500001", 720), mp4("500001", 480)]), media("500002", [mp4("500002", 720)])])));
    check("twitter: BOTH videos of a two-video tweet are emitted", two.emits.length === 2, two.emits.length);
    if (two.emits.length === 2) {
        const [a, b] = two.emits.map(s => s.msg);
        check("twitter: the two clips share the tweet origin but are distinct captures",
            a.origin === b.origin && a.origin === "https://x.com/sampleuser/status/9001" && a.url !== b.url,
            JSON.stringify([a.origin, b.origin, a.url, b.url]));
        check("twitter: each clip's variants are its own", a.variants.length === 2 && b.variants.length === 1,
            JSON.stringify([a.variants.length, b.variants.length]));
    }
    // The second clip's master on the wire → rich parser owns it → no duplicate.
    const dup = await drive("https://video.twimg.com/ext_tw_video/500002/pu/pl/abc.m3u8?tag=12", "xmlhttprequest", 70, "twDup");
    check("twitter: a richly-captured clip's master is NOT re-captured off the wire", dup.emits.length === 0, dup.emits.length);
    // The same clip played in ANOTHER tab (a cached/SPA view whose GraphQL
    // never crossed the wire there) is that tab's only capture: the rich mark
    // is per tab, it used to be global and suppressed the backbone everywhere.
    const otherTab = await drive("https://video.twimg.com/ext_tw_video/500002/pu/pl/abc.m3u8?tag=12", "xmlhttprequest", 74, "twOtherTab");
    check("per-tab: twitter — the backbone captures in a tab where the rich parser did not",
        otherTab.emits.length === 1 && otherTab.emits[0].msg.tabId === 74, JSON.stringify(otherTab.emits.map(s => [s.msg.type, s.msg.tabId])));

    // HLS-only media: nothing emitted by the rich parser → NOT marked → Layer 3 captures it on play.
    const hlsOnly = await drive(url("9002"), "xmlhttprequest", 71, "twHlsOnly",
        detail(tweet("9002", [media("500003", [m3u8("500003")])])));
    check("twitter: an HLS-only media emits no progressive variants", hlsOnly.emits.length === 0, hlsOnly.emits.length);
    const wire = await drive("https://video.twimg.com/ext_tw_video/500003/pu/pl/abc.m3u8?tag=12", "xmlhttprequest", 71, "twHlsWire");
    check("twitter: …and its master IS captured by the wire fallback (was lost: marked rich-captured without an emit)",
        wire.emits.length === 1 && wire.emits[0].msg.type === "hls-master", JSON.stringify(wire.emits.map(s => s.msg.type)));
}

// Bluesky: every video of a response is cached BEFORE any emit awaits, so a
// master the player fetches during the emit round trips is enriched, not
// generic (the generic emit used to win the origin dedup).
{
    const PL1 = "https://video.bsky.app/watch/did%3Aplc%3Ar1/cidr1/playlist.m3u8";
    const PL2 = "https://video.bsky.app/watch/did%3Aplc%3Ar2/cidr2/playlist.m3u8";
    const post = (uri, handle, text, playlist) => ({ post: { uri, author: { handle, displayName: handle },
        record: { text }, embed: { $type: "app.bsky.embed.video#view", playlist } } });
    const body = JSON.stringify({ feed: [post("at://r1", "r1.example", "First clip", PL1), post("at://r2", "r2.example", "Second clip", PL2)] });
    const realGet = browser.tabs.get;
    browser.tabs.get = (id) => new Promise(r => setTimeout(() => r({ id, incognito: false }), 5)); // a real IPC round trip
    const before = nativeSent.length;
    const xrpc = "https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed?feed=race";
    const matching = listenersMatching(xrpc, "xmlhttprequest");
    matching[0].fn({ url: xrpc, type: "xmlhttprequest", tabId: 33, requestId: "bskyRace", method: "GET" });
    const f = filters.get("bskyRace");
    f.ondata({ data: new TextEncoder().encode(body).buffer });
    f.onstop();
    await micro(8);                       // the body is parsed; the first emit is awaiting tabs.get
    for (const r of listenersMatching(PL2, "media")) r.fn({ url: PL2, type: "media", tabId: 33, requestId: "bskyRaceWire", method: "GET" });
    await new Promise(r => setTimeout(r, 80));
    browser.tabs.get = realGet;
    const emits = nativeSent.slice(before).filter(isCapture).map(s => s.msg);
    const second = emits.find(m => m.url === PL2);
    check("bsky: a master fetched mid-emit is enriched from the cache (not 'Bluesky video')",
        !!second && second.name === "Second clip", JSON.stringify(emits.map(m => [m.url.slice(-20), m.name])));
    check("bsky: the race still yields exactly one capture per video", emits.length === 2, emits.length);
}

// Kick: the parser's own channel API fetch is marked, so the onCompleted
// listener doesn't take it for the page's request and re-fetch + re-process.
{
    const realFetch = globalThis.fetch;
    let channelFetches = 0;
    globalThis.fetch = async (url, opts) => { if (String(url).includes("kick.com/api/v2/channels/")) channelFetches++; return realFetch(url, opts); };
    await drive("https://kick.com/ownfetchstreamer", "main_frame", 57, "kickOwn");
    // The browser observes our own fetch completing — onCompleted fires for it.
    for (const r of registrations.filter(r => r.path === "webRequest.onCompleted")) {
        r.fn({ url: "https://kick.com/api/v2/channels/ownfetchstreamer", type: "xmlhttprequest", tabId: -1, requestId: "kickOwnDone", statusCode: 200 });
    }
    await new Promise(r => setTimeout(r, 60));
    globalThis.fetch = realFetch;
    check("kick: a channel visit costs ONE API fetch (own request not re-fetched)", channelFetches === 1, channelFetches);
}

// Twitch: only a usher MASTER seeds the rendezvous (a video-weaver media
// playlist used to complete it with a non-master URL), and the tab's NEWEST
// cached URL names the channel after an SPA navigation.
{
    await drive("https://www.twitch.tv/gammachan", "main_frame", 63, "twGammaPage");
    const mediaPl = await drive("https://video-weaver.ams03.hls.ttvnw.net/v1/playlist/abcdef.m3u8", "xmlhttprequest", 63, "twGammaMedia");
    check("twitch: a media-playlist refresh does NOT complete the rendezvous", mediaPl.emits.length === 0, mediaPl.emits.length);
    const gammaMaster = await drive("https://usher.ttvnw.net/api/channel/hls/gammachan.m3u8?sig=S&token=T", "xmlhttprequest", 63, "twGammaMaster");
    check("twitch: the usher master then completes it", gammaMaster.emits.length === 1
        && gammaMaster.emits[0].msg.origin === "https://www.twitch.tv/gammachan", JSON.stringify(gammaMaster.emits.map(s => s.msg.origin)));

    await drive("https://www.twitch.tv/alphachan", "main_frame", 64, "twAlphaPage");
    await drive("https://www.twitch.tv/betachan", "main_frame", 64, "twBetaPage");   // SPA navigation, same tab
    const betaMaster = await drive("https://usher.ttvnw.net/api/channel/hls/betachan.m3u8?sig=S&token=T", "xmlhttprequest", 64, "twBetaMaster");
    check("twitch: after A→B in one tab the master is filed under B (newest tab URL), not A",
        betaMaster.emits.length === 1 && betaMaster.emits[0].msg.origin === "https://www.twitch.tv/betachan",
        JSON.stringify(betaMaster.emits.map(s => s.msg.origin)));
}

// Cookie jar: a parser that attaches cookies to its emit reads the jar of the
// tab the capture came from — a private tab's download must not carry the
// regular session (cookies.getAll reads the default jar unless told).
{
    const queries = [];
    const realGetAll = browser.cookies.getAll;
    const realGet = browser.tabs.get;
    browser.cookies.getAll = async (q) => { queries.push(q); return []; };
    browser.tabs.get = async (id) => ({ id, incognito: id === 90, url: "https://example.com/" });
    const ttBody = JSON.stringify({ itemList: [{ id: "7300000000000000009", desc: "jar test", author: { uniqueId: "jaruser" },
        video: { width: 576, height: 1024, duration: 5, playAddr: "https://v16-webapp-prime.tiktok.example/video/jar.mp4?tk=tt_chain_token" } }] });
    await drive("https://www.tiktok.com/api/post/item_list/?aid=1988&msToken=JAR1", "xmlhttprequest", 90, "ttJarPriv", ttBody);
    const privQ = queries.find(q => q.domain === "tiktok.com");
    check("cookies: a PRIVATE tab's TikTok emit reads the private jar", privQ?.storeId === "firefox-private", JSON.stringify(privQ));
    queries.length = 0;
    await drive("https://www.tiktok.com/api/post/item_list/?aid=1988&msToken=JAR2", "xmlhttprequest", 10, "ttJarReg",
        ttBody.replace("7300000000000000009", "7300000000000000010"));
    const regQ = queries.find(q => q.domain === "tiktok.com");
    check("cookies: a regular tab's TikTok emit reads the default jar", regQ?.storeId === "firefox-default", JSON.stringify(regQ));
    queries.length = 0;
    const dzBody = JSON.stringify({ results: { data: [{ SNG_ID: "99001", SNG_TITLE: "Jar", ART_NAME: "X", TRACK_TOKEN: "t", DURATION: "200", FILESIZE_MP3_128: "1" }] } });
    await drive("https://www.deezer.com/ajax/gw-light.php?method=deezer.pageTrack&api_version=1.0", "xmlhttprequest", 90, "dzJar", dzBody);
    const dzQ = queries.find(q => q.url === "https://www.deezer.com/");
    check("cookies: a PRIVATE tab's Deezer session read targets the private jar", dzQ?.storeId === "firefox-private", JSON.stringify(dzQ));
    browser.cookies.getAll = realGetAll;
    browser.tabs.get = realGet;
}

console.log(failures ? `\nparsers-replay: ${failures} FAILURE(S)` : "\nparsers-replay: all checks passed");
process.exit(failures ? 1 : 0);
