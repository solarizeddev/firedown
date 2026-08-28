// Parsers replay — drives the REAL registered listeners of every site parser
// that doesn't yet have its own dedicated fixture replay (instagram-replay.mjs
// and dailymotion-replay.mjs cover those two with sanitized-HAR fixtures;
// webrequests-smoke.mjs covers Twitter/Telegram/Spotify at the exported-helper
// level). Covered here, at the LISTENER level (registration + pattern match +
// filter/fetch + extraction + emit, end to end): TikTok, Bluesky, Facebook,
// Vimeo, Rumble, Kick, Twitch, Niconico, Apple Podcasts, News Over Audio,
// Videee.
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
    return notFound;
};

// Import each parser under test — real modules, real registrations. (Videee
// transitively imports requests.js; its <all_urls> generic-catcher listeners
// never match the pattern check below, so they can't contaminate dispatches.)
for (const mod of ["tiktok", "bluesky", "facebook", "vimeo", "rumble", "kick",
                   "twitch", "niconico", "apple-podcasts", "newsoveraudio", "videee"]) {
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

const isCapture = (s) => ["variants", "hls-master", "media"].includes(s.msg?.type);

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

console.log(failures ? `\nparsers-replay: ${failures} FAILURE(S)` : "\nparsers-replay: all checks passed");
process.exit(failures ? 1 : 0);
