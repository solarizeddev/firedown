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
    id: 4242, user_id: 5151,
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

const vimeoFetches = [];
const placeholderFetches = [];
globalThis.fetch = async (url, opts) => {
    if (url.includes("player.vimeo.com/video/")) {
        vimeoFetches.push({ url, credentials: opts?.credentials });
        if (url.includes("/video/404")) return notFound;
        const unlisted = url.match(/\/video\/(55\d)/);
        if (unlisted) {
            // An unlisted embed's config: no public page in config.video.url.
            const cfg = JSON.parse(JSON.stringify(VIMEO_CONFIG));
            delete cfg.video.url;
            return jsonResp(cfg);
        }
        return jsonResp(VIMEO_CONFIG);
    }
    if (url.includes("kick.com/api/v2/clips/")) return jsonResp(KICK_CLIP);
    if (url.includes("kick.com/api/v2/channels/")) return jsonResp(KICK_CHANNEL);
    if (url.includes("gql.twitch.tv/gql")) {
        const body = String(opts?.body || "");
        if (body.includes("VideoAccessToken_Clip")) return jsonResp(TWITCH_CLIP_GQL);
        return jsonResp(TWITCH_LIVE_GQL);
    }
    if (url.includes("itunes.apple.com/lookup")) {
        placeholderFetches.push({ url, credentials: opts?.credentials });
        return jsonResp(ITUNES_LOOKUP);
    }
    if (url.startsWith("https://rumble.com/embedJS/u3/")) {
        placeholderFetches.push({ url, credentials: opts?.credentials });
        return jsonResp({
            title: "Placeholder rumble clip", author: { name: "Sample Channel" }, duration: 30,
            i: "https://sp.rmbl.example/thumb9.jpg", l: "/v9zz-placeholder.html",
            ua: { hls: { auto: { url: "https://rumble.com/hls-vod/9zz/playlist.m3u8" } } },
        });
    }
    if (url.startsWith("https://open.spotify.com/embed/")) {
        placeholderFetches.push({ url, credentials: opts?.credentials });
        const next = { props: { pageProps: { state: { data: { entity: {
            type: "track", name: "Placeholder Song", uri: "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            title: "Placeholder Song", subtitle: "Sample Artist",
            audioPreview: { url: "https://p.scdn.example/mp3-preview/abc123" },
            coverArt: { sources: [{ url: "https://i.scdn.example/cover640.jpg", width: 640 }] },
        } } } } } };
        return { ok: true, status: 200, text: async () =>
            `<html><body><script id="__NEXT_DATA__" type="application/json">${JSON.stringify(next)}</script></body></html>` };
    }
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
                   "deezer", "substack", "acast", "youtube-facade", "twitter", "spotify"]) {
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

// Dispatch a `player-placeholders` report from a page in `tabId` through the
// parser router and return the capture emits that followed.
async function placeholderDispatch(tabId, urls, page = "https://example.com/") {
    const before = nativeSent.length;
    for (const r of registrations.filter(r => r.path === "runtime.onMessage")) {
        r.fn({ kind: "player-placeholders", urls }, { tab: { id: tabId, url: page, incognito: false }, url: page, frameId: 0 });
    }
    await new Promise(r => setTimeout(r, 150));
    return nativeSent.slice(before).filter(isCapture);
}

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
            { Bitrate: 1200000, PlayAddr: { Width: 576, Height: 1024, DataSize: "2250000", UrlList: ["https://v16-webapp-prime.tiktok.example/video/hq.mp4?tk=tt_chain_token"] } },
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
        // The picker shows each rendition's size: TikTok's DataSize is exact
        // (a string on the wire); a rendition without one carries 0 and the
        // bitrate the estimate falls back to.
        check("tiktok: rendition size from DataSize, bitrate kept",
            m.variants[0].size === 2250000 && m.variants[1].size === 0 && m.variants[1].bitrate === 600000,
            JSON.stringify(m.variants.map(v => [v.size, v.bitrate])));
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
// Vimeo — the player's OWN config response (the embed document inlining
// window.playerConfig), the claims a
// config read makes for the generic catcher, the re-fetch fallback, and the
// wire master backbone the block-list rule depends on.
// ---------------------------------------------------------------------------
const { decideCapture } = await import(pathToFileURL(join(parserDir, "..", "requests.js")));
const { __setVimeoMasterGraceMs } = await import(pathToFileURL(join(parserDir, "vimeo.js")));
{
    const CLIP = "9090a8ca-8fec-467e-a1f9-e38a4f091b8b";
    const master = (clip, cdn = "vod-adaptive-ak") =>
        `https://${cdn}.vimeocdn.com/exp=1792421528~acl=%2F${clip}%2Fpsid%3DSCRUB%2F%2A~hmac=SCRUB/${clip}/psid=SCRUB/v2/playlist/av/primary/sub/1-en-x-autogen/playlist.m3u8?ext-subs=1&omit=av1-hevc-opus&pathsig=SCRUB&r=dXM%3D&sf=fmp4`;
    const progressive = (clip) => `https://vod-progressive-ak.vimeocdn.com/exp=1792421528~acl=SCRUB~hmac=SCRUB/vimeo-prod-skyfire-std-us/01/1/4/119929944/${clip.slice(0, 8)}.mp4`;
    const config = (clip, id, title) => ({
        request: { files: {
            hls: { default_cdn: "akfire", cdns: {
                akfire: { avc_url: master(clip), url: master(clip).replace("av1-hevc-opus", "opus") },
                fastly: { avc_url: master(clip, "skyfire") } } },
            dash: { default_cdn: "akfire", cdns: { akfire: { avc_url: master(clip).replace("playlist.m3u8", "playlist.json") } } },
            progressive: [{ quality: "360p", url: progressive(clip) }],
        } },
        video: { id, title, url: `https://vimeo.com/${id}`, duration: 74, owner: { name: "Short of the Week" },
            thumbnail_url: "https://i.vimeocdn.com/video/507723229-d" },
    });
    // The real document's shape: '&' written as \u0026 inside the inline
    // JSON, the JSON-LD object after it.
    const embedHtml = (clip, id, title) => `<!DOCTYPE html><html><head><title>${title} on Vimeo</title></head><body>
<div id="player" class="player"></div>
<script>window.playerConfig = ${JSON.stringify(config(clip, id, title)).replace(/&/g, "\\u0026")}</script>
<script type="application/ld+json">{"embedUrl":"https://player.vimeo.com/video/${id}","thumbnailUrl":"https://i.vimeocdn.com/video/507723229-d?f=webp","name":"${title}","duration":"PT74S","@type":"VideoObject"}</script>
</body></html>`;
    const PAGE = "https://nofilmschool.example/2015/02/article";
    async function wire(url, type, tabId, requestId, extra = {}, body) {
        const matching = listenersMatching(url, type);
        const before = nativeSent.length;
        const returns = matching.map(r => r.fn({ url, type, tabId, requestId, method: "GET", frameId: 2,
            documentUrl: PAGE, originUrl: PAGE, ...extra }));
        if (body != null) {
            const f = filters.get(requestId);
            if (f) { f.ondata({ data: new TextEncoder().encode(body).buffer }); f.onstop(); }
        }
        await new Promise(r => setTimeout(r, 80));
        return { matched: matching.length, returns, emits: nativeSent.slice(before).filter(isCapture) };
    }

    const PLAYER = "https://player.vimeo.com/video/119929944?h=b2029bac3b";
    const fetchedBefore = vimeoFetches.length;
    const doc = await wire(PLAYER, "sub_frame", 45, "vimeo1", {}, embedHtml(CLIP, 119929944, "Welcome to Short of the Week"));
    check("vimeo: the embed document routes to one listener", doc.matched === 1, doc.matched);
    check("vimeo: the listener returns synchronously (the embed load is never held)",
        doc.returns.length === 1 && !(doc.returns[0] instanceof Promise), String(doc.returns[0]));
    check("vimeo: the player's own response is read, no second request", vimeoFetches.length === fetchedBefore,
        JSON.stringify(vimeoFetches.slice(fetchedBefore)));
    check("vimeo: one hls-master emit from the inline config", doc.emits.length === 1 && doc.emits[0].msg.type === "hls-master",
        JSON.stringify(doc.emits.map(e => e.msg.type)));
    if (doc.emits.length === 1) {
        const m = doc.emits[0].msg;
        check("vimeo: the default CDN's master, its \\u0026 decoded", m.url === master(CLIP), m.url);
        check("vimeo: canonical vimeo.com origin", m.origin === "https://vimeo.com/119929944", m.origin);
        check("vimeo: title from the JSON-LD, owner + duration from the config",
            m.name === "Welcome to Short of the Week" && m.description === "Short of the Week" && m.duration === 74000,
            JSON.stringify([m.name, m.description, m.duration]));
    }

    // The config's other URLs are this clip for the generic catcher too: the
    // player's progressive fetch and the content script's scrape of the
    // inline config (a sub-frame report) are rejected, the other CDN's
    // master as well.
    const cdata = (url, extra = {}) => ({ requestId: `vc-${Math.random()}`, url, type: "media", method: "GET", tabId: 45,
        frameId: 0, documentUrl: PLAYER, originUrl: PLAYER, statusCode: 200,
        responseHeaders: [{ name: "content-type", value: "video/mp4" }], ...extra });
    let r = decideCapture(cdata(progressive(CLIP)), "onHeadersReceived", false);
    check("vimeo: the player's progressive fetch is claimed (not a second capture)",
        r.action === "reject" && r.reason === "player-claimed-url", JSON.stringify(r));
    r = decideCapture(cdata(progressive(CLIP), { tabId: 46 }), "onHeadersReceived", false);
    check("vimeo: …per tab: the same file in a tab with no config read still captures", r.action === "emit", JSON.stringify(r));
    r = decideCapture(cdata("https://vod-progressive-ak.vimeocdn.com/exp=1~acl=x~hmac=y/vimeo-prod/01/2/x.mp4",
        { requestId: "cs-vimeo-frame", frameId: 2, frameUrl: PLAYER }), "contentScript", false);
    check("vimeo: a scraped report from the claimed player frame is rejected",
        r.action === "reject" && r.reason === "player-claimed-frame", JSON.stringify(r));
    r = decideCapture(cdata(master(CLIP).replace("vod-adaptive-ak", "skyfire"), { tabId: 46,
        responseHeaders: [{ name: "content-type", value: "application/vnd.apple.mpegurl" }] }), "onHeadersReceived", false);
    check("vimeo: the adaptive stream is block-listed for the catcher in any tab", r.action === "reject", JSON.stringify(r));
    r = decideCapture(cdata(`https://vod-adaptive-ak.vimeocdn.com/exp=1~acl=x~hmac=y/${CLIP}/psid=z/v2/remux/avf/4480c26b-53e3-4010-ad68-760990ff74d3/segment.mp4?pathsig=s&sid=0&st=audio`, { tabId: 46 }),
        "onHeadersReceived", false);
    check("vimeo: an init segment is block-listed in any tab", r.action === "reject", JSON.stringify(r));

    // The wire master: the same clip again in the same tab is deduped; in a
    // second tab (no config read there) it captures, titled from the cache.
    const same = await wire(master(CLIP), "xmlhttprequest", 45, "vimeoM1", { documentUrl: PLAYER, originUrl: PLAYER });
    check("vimeo backbone: the master the config path already sent is not sent again", same.matched === 1 && same.emits.length === 0,
        JSON.stringify([same.matched, same.emits.length]));
    const child = await wire(master(CLIP).replace("primary/sub/1-en-x-autogen/playlist.m3u8", "9090a8ca/avf/4480c26b/media.m3u8"),
        "xmlhttprequest", 47, "vimeoChild", { documentUrl: PLAYER, originUrl: PLAYER });
    check("vimeo backbone: a rendition playlist is not a master", child.emits.length === 0, child.emits.length);
    const other = await wire(master(CLIP), "xmlhttprequest", 48, "vimeoM2", { documentUrl: PLAYER, originUrl: PLAYER });
    check("vimeo backbone: a second tab's master captures, titled from the clip's cached config",
        other.emits.length === 1 && other.emits[0].msg.name === "Welcome to Short of the Week"
            && other.emits[0].msg.origin === "https://vimeo.com/119929944" && other.emits[0].msg.tabId === 48,
        JSON.stringify(other.emits.map(e => [e.msg.name, e.msg.origin, e.msg.tabId])));

    // A config the parser never saw (cached document, unknown shape): the
    // master still lands, titled from the player frame's own metadata, under
    // the vimeo.com/<id> page its frame names.
    const CLIP2 = "11111111-2222-4333-8444-555555555555";
    const realSend = browser.tabs.sendMessage;
    const asked = [];
    browser.tabs.sendMessage = async (tabId, msg, opts) => {
        asked.push([tabId, msg?.kind, opts?.frameId]);
        return { title: "Frame clip on Vimeo", videoLdName: "Frame clip", ogImage: "https://i.vimeocdn.com/video/2-d" };
    };
    const lone = await wire(master(CLIP2), "xmlhttprequest", 49, "vimeoM3",
        { documentUrl: "https://player.vimeo.com/video/222333?h=abc", originUrl: "https://player.vimeo.com/video/222333?h=abc" });
    check("vimeo backbone: an unread config still captures, named by the player frame",
        lone.emits.length === 1 && lone.emits[0].msg.name === "Frame clip" && lone.emits[0].msg.img === "https://i.vimeocdn.com/video/2-d"
            && lone.emits[0].msg.origin === "https://vimeo.com/222333",
        JSON.stringify(lone.emits.map(e => [e.msg.name, e.msg.img, e.msg.origin])));
    check("vimeo backbone: the metadata query targets the master's own frame", asked.some(a => a[0] === 49 && a[2] === 2),
        JSON.stringify(asked));
    // The player fetches the master again (an ABR restart, the other CDN):
    // one decision per (tab, clip), so no second wait and no second query.
    const again = await wire(master(CLIP2, "skyfire"), "xmlhttprequest", 49, "vimeoM3b",
        { documentUrl: "https://player.vimeo.com/video/222333?h=abc", originUrl: "https://player.vimeo.com/video/222333?h=abc" });
    check("vimeo backbone: the same clip's master again in that tab is not decided twice",
        again.emits.length === 0 && asked.filter(a => a[0] === 49).length === 1, JSON.stringify([again.emits.length, asked]));
    browser.tabs.sendMessage = async () => undefined;
    const bare = await wire(master("33333333-2222-4333-8444-555555555555"), "xmlhttprequest", 50, "vimeoM4",
        { documentUrl: "https://player.vimeo.com/video/444555", originUrl: "https://player.vimeo.com/video/444555" });
    check("vimeo backbone: …and with no frame answer, a generic title",
        bare.emits.length === 1 && bare.emits[0].msg.name === "Vimeo video" && bare.emits[0].msg.origin === "https://vimeo.com/444555",
        JSON.stringify(bare.emits.map(e => [e.msg.name, e.msg.origin])));
    browser.tabs.sendMessage = realSend;

    // Order: the player can fetch the master before the config read is done
    // (a config request whose body is still streaming). The backbone waits
    // for it and the one capture is titled.
    __setVimeoMasterGraceMs(2000);
    const CLIP3 = "44444444-2222-4333-8444-555555555555";
    const CFG_URL = "https://player.vimeo.com/video/555666/config?h=abc&s=SCRUB";
    const before = nativeSent.length;
    for (const reg of listenersMatching(CFG_URL, "xmlhttprequest")) reg.fn({ url: CFG_URL, type: "xmlhttprequest", tabId: 51,
        requestId: "vimeoCfgLate", method: "GET", frameId: 0, documentUrl: "https://vimeo.com/555666", originUrl: "https://vimeo.com/555666" });
    for (const reg of listenersMatching(master(CLIP3), "xmlhttprequest")) reg.fn({ url: master(CLIP3), type: "xmlhttprequest", tabId: 51,
        requestId: "vimeoM5", method: "GET", frameId: 0, documentUrl: "https://vimeo.com/555666", originUrl: "https://vimeo.com/555666" });
    await new Promise(res => setTimeout(res, 300));
    const fLate = filters.get("vimeoCfgLate");
    fLate.ondata({ data: new TextEncoder().encode(JSON.stringify(config(CLIP3, 555666, "Late config clip"))).buffer });
    fLate.onstop();
    await new Promise(res => setTimeout(res, 300));
    const late = nativeSent.slice(before).filter(isCapture);
    check("vimeo backbone: a master ahead of its config waits, one titled capture",
        late.length === 1 && late[0].msg.name === "Late config clip" && late[0].msg.origin === "https://vimeo.com/555666",
        JSON.stringify(late.map(e => [e.msg.name, e.msg.origin])));

    // Fallbacks of the config read.
    let fb = vimeoFetches.length;
    const errBefore = nativeSent.length;
    for (const reg of listenersMatching("https://player.vimeo.com/video/12345?h=abc", "sub_frame")) reg.fn({
        url: "https://player.vimeo.com/video/12345?h=abc", type: "sub_frame", tabId: 52, requestId: "vimeoErr", method: "GET", frameId: 3 });
    filters.get("vimeoErr").onerror();
    await new Promise(res => setTimeout(res, 120));
    const errEmits = nativeSent.slice(errBefore).filter(isCapture);
    check("vimeo: an unreadable response falls back to fetching the config, with the session",
        vimeoFetches.length === fb + 1 && vimeoFetches[fb].credentials === "include" && errEmits.length === 1
            && errEmits[0].msg.origin === "https://vimeo.com/12345",
        JSON.stringify([vimeoFetches.slice(fb), errEmits.map(e => e.msg.origin)]));
    fb = vimeoFetches.length;
    const priv = await wire("https://player.vimeo.com/video/777888", "sub_frame", 53, "vimeoPriv", {},
        "<!DOCTYPE html><html><body>Sorry, because of its privacy settings, this video cannot be played here.</body></html>");
    check("vimeo: a response read but holding no config is not fetched again", priv.emits.length === 0 && vimeoFetches.length === fb,
        JSON.stringify([priv.emits.length, vimeoFetches.slice(fb)]));
    const api = await wire("https://player.vimeo.com/api/player.js", "xmlhttprequest", 53, "vimeoApi");
    check("vimeo: a player.vimeo.com request that is not a config opens no filter", !filters.has("vimeoApi"), String(filters.has("vimeoApi")));
}

// Vimeo click-to-load placeholders (common.js `player-placeholders`): the
// embed the page has not loaded yet is fetched anonymously and read like the
// player's own; the unlisted hash rides along; a failure releases the claim.
{
    const routers = registrations.filter(r => r.path === "runtime.onMessage");
    const PAGE = "https://example.com/";
    async function placeholders(tabId, urls) {
        const before = nativeSent.length;
        const fetchedBefore = vimeoFetches.length;
        for (const r of routers) r.fn({ kind: "player-placeholders", urls },
            { tab: { id: tabId, url: PAGE, incognito: false }, url: PAGE, frameId: 0 });
        await new Promise(r => setTimeout(r, 120));
        return { emits: nativeSent.slice(before).filter(isCapture), fetches: vimeoFetches.slice(fetchedBefore) };
    }
    const lazy = await placeholders(46, ["https://player.vimeo.com/video/777?h=abc123def&autoplay=1"]);
    check("vimeo placeholder: a lazy embed captures before the tap", lazy.emits.length === 1
        && lazy.emits[0].msg.type === "hls-master" && lazy.emits[0].msg.tabId === 46,
        JSON.stringify(lazy.emits.map(e => [e.msg.type, e.msg.tabId])));
    check("vimeo placeholder: the embed is fetched anonymously, unlisted hash kept, player options dropped",
        lazy.fetches.length === 1 && lazy.fetches[0].credentials === "omit"
            && lazy.fetches[0].url === "https://player.vimeo.com/video/777?h=abc123def",
        JSON.stringify(lazy.fetches));
    const page = await placeholders(47, ["https://vimeo.com/888/0a1b2c3d4e"]);
    check("vimeo placeholder: a vimeo.com/<id>/<hash> link maps to the player with its hash",
        page.fetches.length === 1 && page.fetches[0].url === "https://player.vimeo.com/video/888?h=0a1b2c3d4e",
        JSON.stringify(page.fetches));
    const other = await placeholders(48, ["https://vimeo.com/about", "https://player.vimeo.com/api/player.js"]);
    check("vimeo placeholder: Vimeo pages that are not a video fetch nothing", other.fetches.length === 0,
        JSON.stringify(other.fetches));
    const twoUnlisted = await placeholders(50, ["https://player.vimeo.com/video/551?h=aaaaaa1", "https://player.vimeo.com/video/552?h=bbbbbb2"]);
    check("vimeo placeholder: two unlisted embeds on one page are two captures, each under its vimeo.com/<id>",
        twoUnlisted.emits.map(e => e.msg.origin).sort().join(" ") === "https://vimeo.com/551 https://vimeo.com/552",
        JSON.stringify(twoUnlisted.emits.map(e => e.msg.origin)));
    const failed = await placeholders(49, ["https://player.vimeo.com/video/404"]);
    const retried = await placeholders(49, ["https://player.vimeo.com/video/404"]);
    check("vimeo placeholder: a refused embed is tried again on the next report",
        failed.emits.length === 0 && failed.fetches.length === 1 && retried.fetches.length === 1,
        JSON.stringify([failed.fetches.length, retried.fetches.length]));
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
        // The poster is the channel-id thumbnail, never the user id's (404)
        // nor livestream.thumbnail (AccessDenied on the real CDN).
        check("kick: live title — category + channel-id thumbnail",
            m.description === "Playing games — Just Chatting"
                && m.img === "https://web.kick.com/api/v1/channels/4242/thumbnail.jpeg",
            JSON.stringify([m.description, m.img]));
    }

    // The same channel in a SECOND tab inside the burst window is that tab's
    // own capture (a claim keyed by channel alone gives tab 58 nothing for
    // 10 s).
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
// Acast — the embed player's feeder API JSON (a publisher embed's shape, with
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

    // 7. A click-to-load embed the page has not loaded yet: looked up by the
    //    embed's ids before any tap; a show-only embed is not read; the
    //    player's media fetch after a tap is quiet.
    const EPP = "6a0000000000000000000007";
    ACAST_FEEDER[`/api/v1/shows/${SHOW}/episodes/${EPP}`] = episode(EPP, "Episodio siete", { show });
    const phCalls = acastFeederCalls.length;
    const ph = await placeholderDispatch(76, [`https://embed.acast.com/${SHOW}/${EPP}?cover=false`,
        `https://embed.acast.com/${SHOW}`]);
    check("acast placeholder: an embed not loaded yet is captured, titled, before the tap",
        ph.length === 1 && ph[0].msg.name === "Episodio siete" && ph[0].msg.tabId === 76
            && acastFeederCalls.length === phCalls + 1,
        JSON.stringify([ph.map(s => s.msg.name), acastFeederCalls.slice(phCalls)]));
    const tap = await drive(media(EPP), "media", 76, "ac10");
    check("acast placeholder: the player's media fetch after a tap is quiet", tap.emits.length === 0,
        tap.emits.length);
}

// ---------------------------------------------------------------------------
// Click-to-load placeholders for Apple Podcasts, Rumble and Spotify embeds
// (common.js `player-placeholders`): captured before the tap, anonymously,
// from the same lookups the players use; shapes that are not one clip are
// not read.
// ---------------------------------------------------------------------------
{
    const before = placeholderFetches.length;
    const apple = await placeholderDispatch(77, [
        "https://embed.podcasts.apple.com/us/podcast/sample-show/id123?i=4004&itsct=podcast_box",
        "https://embed.podcasts.apple.com/us/podcast/sample-show/id123"]);
    check("apple placeholder: an episode embed is captured before the tap, under its podcasts.apple.com page",
        apple.length === 1 && apple[0].msg.type === "media" && apple[0].msg.tabId === 77
            && apple[0].msg.origin === "https://podcasts.apple.com/us/podcast/sample-show/id123?i=4004&itsct=podcast_box",
        JSON.stringify(apple.map(s => [s.msg.type, s.msg.origin])));
    const rumble = await placeholderDispatch(78, ["https://rumble.com/embed/v9zz/?pub=4"]);
    check("rumble placeholder: an embed is captured before the tap from its embedJS",
        rumble.length === 1 && rumble[0].msg.type === "hls-master"
            && rumble[0].msg.origin === "https://rumble.com/v9zz-placeholder.html",
        JSON.stringify(rumble.map(s => [s.msg.type, s.msg.origin])));
    const spotify = await placeholderDispatch(79, [
        "https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC?utm_source=generator",
        "https://open.spotify.com/embed/playlist/37i9dQZEVXbMDoHDwVN2tF"]);
    check("spotify placeholder: a track embed is captured before the tap, a playlist is not read",
        spotify.length === 1 && spotify[0].msg.name === "Placeholder Song"
            && !placeholderFetches.slice(before).some(f => f.url.includes("/playlist/")),
        JSON.stringify([spotify.map(s => s.msg.name), placeholderFetches.slice(before).map(f => f.url)]));
    const fetchedNow = placeholderFetches.slice(before);
    check("placeholder: every provider's own fetch is anonymous",
        fetchedNow.length === 3 && fetchedNow.every(f => f.credentials === "omit"), JSON.stringify(fetchedNow));
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
    // Vimeo's is blocking only to open a response filter; its synchronous
    // return is pinned in the Vimeo section. Its wire master only observes.
    const vimeo = regOf("listenerVimeo");
    check("audit: Vimeo config listener is blocking (it reads the response) and type-scoped",
        !!vimeo && (vimeo.extra || []).includes("blocking") && vimeo.filter?.types?.length === 3, JSON.stringify([vimeo?.extra, vimeo?.filter?.types]));
    const vimeoMaster = regOf("listenerVimeoMaster");
    check("audit: Vimeo wire-master listener registered (observe-only, not blocking)",
        !!vimeoMaster && !(vimeoMaster.extra || []).includes("blocking"), JSON.stringify(vimeoMaster?.extra));
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
    // is per tab, a global one would suppress the backbone everywhere.
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

// X: a tweet shown INSIDE another one (a quote, a repost's original, a reply
// on a status page) is credited to its OWN tweet. Reading only the outer
// tweet sends a quoted video through Layer 3, titled by the page's handle (the
// quoting account) or "X video". Replies are not captured before they play;
// Layer 3 titles them from what the response said.
{
    const mp4 = (id, w, h) => ({ content_type: "video/mp4", bitrate: h * 1000,
        url: `https://video.twimg.com/amplify_video/${id}/vid/avc1/${w}x${h}/c${h}.mp4?tag=14` });
    const master = (id) => `https://video.twimg.com/amplify_video/${id}/pl/m${id}.m3u8?tag=14&v=cfc`;
    const media = (id) => ({ id_str: id, type: "video",
        media_url_https: `https://pbs.twimg.com/amplify_video_thumb/${id}/img/p${id}.jpg`,
        video_info: { duration_millis: 21000, variants: [
            { content_type: "application/x-mpegURL", url: master(id) }, mp4(id, 320, 568), mp4(id, 478, 850)] } });
    const tweet = (restId, user, text, mediaList, extra) => ({ __typename: "Tweet", rest_id: restId,
        core: { user_results: { result: { core: { screen_name: user } } } },
        legacy: { id_str: restId, full_text: text, ...(mediaList ? { extended_entities: { media: mediaList } } : {}) },
        ...extra });
    const entry = (t) => ({ content: { itemContent: { tweet_results: { result: t } } } });
    const timeline = (...ts) => JSON.stringify({ data: { home: { home_timeline_urt: { instructions: [
        { type: "TimelineAddEntries", entries: ts.map(entry) }] } } } });
    const detail = (...ts) => JSON.stringify({ data: { threaded_conversation_with_injections_v2: { instructions: [
        { type: "TimelineAddEntries", entries: ts.map(entry) }] } } });
    const homeUrl = "https://x.com/i/api/graphql/abc123/HomeTimeline?variables=%7B%7D";
    const detailUrl = (focal) => `https://x.com/i/api/graphql/abc123/TweetDetail?variables=${encodeURIComponent(JSON.stringify({ focalTweetId: focal }))}`;

    // A quote in the feed: the quoting tweet has no video, the quoted one does.
    const quoted = tweet("7101", "quotedauthor", "Flooded street, filmed this morning https://t.co/a1", [media("510101")]);
    const quoting = tweet("7102", "quoter", "Look at this", null, { quoted_status_result: { result: quoted } });
    const q = await drive(homeUrl, "xmlhttprequest", 80, "twQuoteFeed", timeline(quoting));
    check("twitter-nested: a quoted tweet's video in the feed is captured",
        q.emits.length === 1, JSON.stringify(q.emits.map(e => e.msg.origin)));
    if (q.emits.length === 1) {
        const m = q.emits[0].msg;
        check("twitter-nested: …under the QUOTED tweet, titled and credited by it",
            m.origin === "https://x.com/quotedauthor/status/7101" && m.name === "Flooded street, filmed this morning"
                && m.description === "@quotedauthor",
            JSON.stringify([m.origin, m.name, m.description]));
    }

    // A repost: the clip belongs to the original, not to the reposter's "RT @…" wrapper.
    const original = tweet("7103", "origauthor", "The river at noon https://t.co/b2", [media("510103")]);
    const repost = tweet("7104", "reposter", "RT @origauthor: The river at noon", [media("510103")],
        { legacy: { id_str: "7104", full_text: "RT @origauthor: The river at noon",
            extended_entities: { media: [media("510103")] }, retweeted_status_result: { result: original } } });
    const r = await drive(homeUrl, "xmlhttprequest", 80, "twRepostFeed", timeline(repost));
    check("twitter-nested: a repost's video is captured once, under the original tweet",
        r.emits.length === 1 && r.emits[0].msg.origin === "https://x.com/origauthor/status/7103"
            && r.emits[0].msg.name === "The river at noon",
        JSON.stringify(r.emits.map(e => [e.msg.origin, e.msg.name])));
    // A repost whose original is unreadable (withheld, a tombstone) still captures the wrapper's copy.
    const rt2 = tweet("7106", "reposter", "RT @gone: clip", [media("510106")],
        { legacy: { id_str: "7106", full_text: "RT @gone: clip", extended_entities: { media: [media("510106")] },
            retweeted_status_result: { result: { __typename: "TweetTombstone" } } } });
    const r2 = await drive(homeUrl, "xmlhttprequest", 80, "twRepostGone", timeline(rt2));
    check("twitter-nested: a repost whose original is unreadable falls back to the repost",
        r2.emits.length === 1 && r2.emits[0].msg.origin === "https://x.com/reposter/status/7106",
        JSON.stringify(r2.emits.map(e => e.msg.origin)));

    // A status page (SPA navigation, no SSR read): the focal tweet and the
    // tweet it quotes are captured, the reply below is not.
    const focalQuoted = tweet("7107", "otherauthor", "Original footage https://t.co/c3", [media("510107")]);
    const focal = tweet("7108", "pageowner", "Our crew on site https://t.co/c4", [media("510108")],
        { quoted_status_result: { result: focalQuoted } });
    const reply = tweet("7109", "replier", "@pageowner Same here, from the bridge https://t.co/c5", [media("510109")]);
    const d = await drive(detailUrl("7108"), "xmlhttprequest", 81, "twDetailNested", detail(focal, reply));
    const origins = d.emits.map(e => e.msg.origin).sort();
    check("twitter-nested: a status page captures its tweet and the tweet it quotes, not the replies",
        JSON.stringify(origins) === JSON.stringify(["https://x.com/otherauthor/status/7107", "https://x.com/pageowner/status/7108"]),
        JSON.stringify(origins));

    // The user plays the reply: its master is captured by Layer 3, titled by
    // the REPLY's tweet, not by the page's handle.
    const played = await drive(master("510109"), "xmlhttprequest", 81, "twReplyPlayed");
    check("twitter-nested: a reply played on a status page is titled by its own tweet",
        played.emits.length === 1 && played.emits[0].msg.type === "hls-master"
            && played.emits[0].msg.name === "@pageowner Same here, from the bridge"
            && played.emits[0].msg.description === "@replier"
            && played.emits[0].msg.duration === 21000
            && played.emits[0].msg.img === "https://pbs.twimg.com/amplify_video_thumb/510109/img/p510109.jpg",
        JSON.stringify(played.emits.map(e => [e.msg.type, e.msg.name, e.msg.description, e.msg.duration, e.msg.img])));
    // The focal and quoted clips' masters stay quiet: they were captured richly.
    const focalMaster = await drive(master("510107"), "xmlhttprequest", 81, "twQuotedMaster");
    check("twitter-nested: a captured quote's master is not captured again off the wire",
        focalMaster.emits.length === 0, JSON.stringify(focalMaster.emits.map(e => e.msg.name)));
    // Nothing known about a clip: Layer 3 keeps its old generic title.
    const unknown = await drive(master("510199"), "xmlhttprequest", 82, "twUnknownMaster");
    check("twitter-nested: a clip no response described keeps the generic title",
        unknown.emits.length === 1 && unknown.emits[0].msg.description === "x.com",
        JSON.stringify(unknown.emits.map(e => [e.msg.name, e.msg.description])));
}

// The wire fallback's poster comes from the page's own poster fetch, kept
// WHOLE: X's posters are extensionless and the bare path 404s without its
// ?format=&name= query.
{
    const thumb = "https://pbs.twimg.com/amplify_video_thumb/500020/img/AbCdEfGh?format=webp&name=large";
    await drive(thumb, "image", 76, "twThumbQ");
    const master = await drive("https://video.twimg.com/amplify_video/500020/pl/q1w2e3.m3u8?tag=16",
        "xmlhttprequest", 76, "twThumbMaster");
    check("twitter: the wire fallback's poster keeps its ?format=&name= query",
        master.emits.length === 1 && master.emits[0].msg.img === thumb,
        JSON.stringify(master.emits.map(s => s.msg.img)));
}

// X embeds: a tweet embedded in another site comes from ONE call,
// cdn.syndication.twimg.com/tweet-result (ids, names and text here are made
// up). Without the embed reader these clips reach the sheet only through the
// wire fallback, titled "X video".
{
    const mp4 = (id, w, h, br) => ({ bitrate: br, content_type: "video/mp4",
        url: `https://video.twimg.com/ext_tw_video/${id}/pu/vid/avc1/${w}x${h}/clip${h}.mp4?tag=12` });
    const m3u8 = (id) => ({ content_type: "application/x-mpegURL",
        url: `https://video.twimg.com/ext_tw_video/${id}/pu/pl/master.m3u8?tag=12&v=1` });
    const videoMedia = (id) => ({ type: "video", expanded_url: `https://x.com/SampleShow/status/x/video/1`,
        media_url_https: `https://pbs.twimg.com/ext_tw_video_thumb/${id}/pu/img/poster.jpg`,
        video_info: { aspect_ratio: [16, 9], duration_millis: 37170,
            variants: [m3u8(id), mp4(id, 480, 270, 256000), mp4(id, 640, 360, 832000), mp4(id, 1280, 720, 2176000)] } });
    const embed = (idStr, text, media, extra) => JSON.stringify({ __typename: "Tweet", id_str: idStr, lang: "es", text,
        user: { id_str: "1", name: "Sample Show", screen_name: "SampleShow" },
        mediaDetails: media, photos: [], ...extra });
    const url = (id) => `https://cdn.syndication.twimg.com/tweet-result?features=tfw_timeline_list%3A&id=${id}&lang=es&token=abc`;

    const one = await drive(url("7001"), "xmlhttprequest", 90, "twEmbed",
        embed("7001", ".@guest debuta como DJ en el cierre de temporada #SampleTag https://t.co/AbCdEf", [videoMedia("600001")]));
    check("twitter-embed: the embedded tweet's video is captured", one.emits.length === 1, one.emits.length);
    if (one.emits.length === 1) {
        const m = one.emits[0].msg;
        check("twitter-embed: titled by the tweet's text, its t.co link dropped",
            m.name === ".@guest debuta como DJ en el cierre de temporada #SampleTag", m.name);
        check("twitter-embed: the author's handle is the description", m.description === "@SampleShow", m.description);
        check("twitter-embed: origin is the tweet, poster and duration carried",
            m.origin === "https://x.com/SampleShow/status/7001"
                && m.img === "https://pbs.twimg.com/ext_tw_video_thumb/600001/pu/img/poster.jpg"
                && m.duration === 37170,
            JSON.stringify([m.origin, m.img, m.duration]));
        check("twitter-embed: every mp4 rendition is a variant, the HLS master is not",
            m.variants.length === 3 && m.variants.every(v => v.url.includes(".mp4")),
            JSON.stringify(m.variants.map(v => v.url)));
    }
    // The player then fetches the clip's master: already captured in this
    // tab, so the wire fallback stays quiet.
    const master = await drive("https://video.twimg.com/ext_tw_video/600001/pu/pl/master.m3u8?tag=12&v=1",
        "xmlhttprequest", 90, "twEmbedMaster");
    check("twitter-embed: the clip's master is not captured again off the wire", master.emits.length === 0,
        JSON.stringify(master.emits.map(s => s.msg.name)));

    // A quoted tweet carrying its own video is its own capture, named by its own text.
    const quoted = await drive(url("7002"), "xmlhttprequest", 91, "twEmbedQuoted",
        embed("7002", "Look at this https://t.co/Zz", [], { quoted_tweet: JSON.parse(
            embed("7003", "The quoted clip https://t.co/Yy", [videoMedia("600003")])) }));
    check("twitter-embed: a quoted tweet's video is captured under the quoted tweet",
        quoted.emits.length === 1 && quoted.emits[0].msg.origin === "https://x.com/SampleShow/status/7003"
            && quoted.emits[0].msg.name === "The quoted clip",
        JSON.stringify(quoted.emits.map(s => [s.msg.origin, s.msg.name])));

    // A reply: the embed renders the tweet it answers (parent) above it, video
    // included, and the player fetches that video's master. The parent is its
    // own capture, titled and credited to the parent's author, never to the
    // reply; and its master stays quiet off the wire (else the parent lands
    // as an untitled "X video" tile).
    const parentTweet = JSON.parse(embed("7010", "Rain over the beach, filmed from a house https://t.co/Pa",
        [videoMedia("600010")]));
    parentTweet.user = { id_str: "2", name: "Weather Desk", screen_name: "WeatherDesk" };
    const reply = await drive(url("7011"), "xmlhttprequest", 94, "twEmbedReply",
        embed("7011", "@WeatherDesk Where is this?", [], { in_reply_to_status_id_str: "7010", parent: parentTweet }));
    check("twitter-embed: a reply's parent video is captured under the parent tweet",
        reply.emits.length === 1 && reply.emits[0].msg.origin === "https://x.com/WeatherDesk/status/7010"
            && reply.emits[0].msg.name === "Rain over the beach, filmed from a house"
            && reply.emits[0].msg.description === "@WeatherDesk",
        JSON.stringify(reply.emits.map(s => [s.msg.origin, s.msg.name, s.msg.description])));
    const parentMaster = await drive("https://video.twimg.com/ext_tw_video/600010/pu/pl/master.m3u8?tag=12&v=1",
        "xmlhttprequest", 94, "twEmbedReplyMaster");
    check("twitter-embed: the parent's master is not captured again off the wire", parentMaster.emits.length === 0,
        JSON.stringify(parentMaster.emits.map(s => s.msg.name)));
    // A reply with its own video AND a parent with one: two captures, each its own.
    const both = await drive(url("7013"), "xmlhttprequest", 95, "twEmbedReplyBoth",
        embed("7013", "My view of it https://t.co/Rb", [videoMedia("600013")],
            { parent: JSON.parse(embed("7012", "The original clip https://t.co/Rc", [videoMedia("600012")])) }));
    check("twitter-embed: a reply with a video and a parent with one are two captures",
        both.emits.length === 2
            && both.emits.some(s => s.msg.origin.endsWith("/7013") && s.msg.variants[0].url.includes("/600013/"))
            && both.emits.some(s => s.msg.origin.endsWith("/7012") && s.msg.variants[0].url.includes("/600012/")),
        JSON.stringify(both.emits.map(s => [s.msg.origin, s.msg.variants[0].url])));

    // No words but the link: the handle titles it, never an empty name.
    const bare = await drive(url("7004"), "xmlhttprequest", 92, "twEmbedBare",
        embed("7004", "https://t.co/Qq", [videoMedia("600004")]));
    check("twitter-embed: a tweet with no text is titled by its author",
        bare.emits.length === 1 && bare.emits[0].msg.name === "@SampleShow",
        JSON.stringify(bare.emits.map(s => s.msg.name)));

    // A photo-only tweet emits nothing.
    const photo = await drive(url("7005"), "xmlhttprequest", 93, "twEmbedPhoto",
        embed("7005", "Just a photo https://t.co/Pp", [{ type: "photo", media_url_https: "https://pbs.twimg.com/media/x.jpg" }]));
    check("twitter-embed: a photo-only tweet captures nothing", photo.emits.length === 0, photo.emits.length);
}

// X: the streamed SSR document carries each GraphQL body as a JSON STRING,
// and a logged-out page fires no GraphQL XHR, so the document is the only
// source. A status document emits its tweet and remembers the reply for the
// wire fallback; a PROFILE document (never a /status/ URL) emits every video
// of its timeline; a document with no store emits nothing.
{
    const mp4 = (id, w, h) => ({ content_type: "video/mp4", bitrate: h * 1000,
        url: `https://video.twimg.com/amplify_video/${id}/vid/avc1/${w}x${h}/d${h}.mp4?tag=29` });
    const master = (id) => `https://video.twimg.com/amplify_video/${id}/pl/d${id}.m3u8?tag=29&v=cfc`;
    const media = (id, ms) => ({ id_str: id, type: "video",
        media_url_https: `https://pbs.twimg.com/amplify_video_thumb/${id}/img/d${id}.jpg`,
        video_info: { duration_millis: ms, variants: [
            { content_type: "application/x-mpegURL", url: master(id) }, mp4(id, 576, 1024), mp4(id, 320, 568)] } });
    const tweet = (restId, user, text, mediaList) => ({ __typename: "Tweet", rest_id: restId,
        core: { user_results: { result: { __typename: "User", core: { screen_name: user } } } },
        details: { full_text: text }, legacy: { lang: "es" }, media_entities2: mediaList || [] });
    const sensitive = (t) => ({ __typename: "TweetWithVisibilityResults",
        media_visibility_results: { blurred_image_interstitial: { text: { text: "Sensitive" } } }, tweet: t });
    const idOf = (t) => t.tweet?.rest_id || t.rest_id;
    const item = (t) => ({ content: { __typename: "TimelineTimelineItem",
        content: { __typename: "TimelineTweet", tweet_results: { rest_id: idOf(t), result: t } } } });
    const conversation = (entries) => ({ threaded_conversation_with_injections_v2: {
        instructions: [{ __typename: "TimelineAddEntries", entries }] } });
    const detailBody = (focal, ...replies) => ({ data: { ...conversation([item(focal),
        { content: { __typename: "TimelineTimelineModule",
            items: replies.map(r => ({ item: { content: { tweet_results: { rest_id: idOf(r), result: r } } } })) } }]),
        tweet_result_by_rest_id: { rest_id: idOf(focal), result: focal } } });
    const restIdBody = (focal) => ({ data: { tweet_result_by_rest_id: { rest_id: idOf(focal), result: focal } } });
    const timelineBody = (...ts) => ({ data: { user_result_by_screen_name: { result: { __typename: "User",
        profile_user_originals_timeline: { timeline: { instructions: [
            { __typename: "TimelineClearCache" },
            { __typename: "TimelineAddEntries", entries: ts.map(item) },
            { __typename: "TimelineTerminateTimeline", direction: "Top" }] } } } } } });
    const js = (o) => JSON.stringify(JSON.stringify(o)); // a JS string literal, quotes escaped
    const completed = (n, id, body) =>
        `{kind:"GraphQLRequestStream.Completed",requestId:${n},key:"k${n}{}",result:$R[${id}]={kind:"Result.Ok",value:${js(body)}}}`;
    const doc = (routerBody, ...streamed) =>
        `<html><head><script data-tsr-stream-part="">(self.$R=self.$R||{})["tsr"]=[];self.$_TSR={};$_TSR.router=($R=>$R[0]={manifest:void 0,matches:$R[1]=[],dehydratedData:$R[2]={messagesBeforeDehydration:$R[3]=[$R[4]={kind:"GraphQLRequestStream.Started",requestId:0,key:"k0{}",name:"Q"}${routerBody ? ",$R[5]=" + completed(0, 6, routerBody) : ""}],graphqlRequestStream:$R[7]=($R[8]=() => { const b = []; return { next(v) { b.push(v); } }; })()}})($R["tsr"]);</script>`
        + streamed.map((b, i) => `<script>($R=>$R[8].next($R[${20 + i * 2}]=${completed(i + 1, 21 + i * 2, b)}))($R["tsr"]);</script>`).join("")
        + `</head><body></body></html>`;
    const emitted = (r) => r.emits.map(e => [e.msg.type, e.msg.origin, e.msg.name]);

    // A status page: the router dehydrates the focal query, a streamed part
    // carries the conversation (the focal again + a reply with its own video).
    const focal = sensitive(tweet("8108", "pageowner", "Our crew on site \u26a0\ufe0f https://t.co/d1", [media("610108", 97266)]));
    const reply = tweet("8109", "replier", "@pageowner From the bridge https://t.co/d2", [media("610109", 5000)]);
    const status = await drive("https://x.com/pageowner/status/8108", "main_frame", 84, "twDocStatus",
        doc(restIdBody(focal), detailBody(focal, reply)));
    check("twitter-doc: the document listener reads a status page", status.matched >= 1, status.matched);
    check("twitter-doc: a status document whose bodies are JSON strings captures its tweet once, under its own origin",
        status.emits.length === 1 && status.emits[0].msg.type === "variants"
            && status.emits[0].msg.origin === "https://x.com/pageowner/status/8108",
        JSON.stringify(emitted(status)));
    if (status.emits.length === 1) {
        const m = status.emits[0].msg;
        check("twitter-doc: …titled by its text, credited to its author, with its poster and duration",
            m.name === "Our crew on site \u26a0\ufe0f" && m.description === "@pageowner" && m.duration === 97266
                && m.img === "https://pbs.twimg.com/amplify_video_thumb/610108/img/d610108.jpg",
            JSON.stringify([m.name, m.description, m.duration, m.img]));
        check("twitter-doc: every mp4 rendition is a variant, the master is not",
            m.variants.length === 2 && m.variants.every(v => v.url.includes("/vid/avc1/")),
            JSON.stringify(m.variants.map(v => v.url)));
    }
    // The reply plays: its master is titled by the REPLY, remembered from the
    // streamed conversation; the page's own master stays quiet.
    const played = await drive(master("610109"), "xmlhttprequest", 84, "twDocReplyPlayed");
    check("twitter-doc: a reply played on the page is titled by its own tweet, remembered from the streamed conversation",
        played.emits.length === 1 && played.emits[0].msg.type === "hls-master"
            && played.emits[0].msg.name === "@pageowner From the bridge" && played.emits[0].msg.description === "@replier",
        JSON.stringify(played.emits.map(e => [e.msg.type, e.msg.name, e.msg.description])));
    const own = await drive(master("610108"), "xmlhttprequest", 84, "twDocOwnMaster");
    check("twitter-doc: the tweet's own master is not captured again off the wire", own.emits.length === 0, JSON.stringify(emitted(own)));

    // Only the focal query's body (no conversation yet): the focal key is read directly.
    const only = await drive("https://x.com/pageowner/status/8110", "main_frame", 85, "twDocRestIdOnly",
        doc(restIdBody(sensitive(tweet("8110", "pageowner", "Only the focal query https://t.co/d3", [media("610110", 3000)])))));
    check("twitter-doc: a document carrying only the focal query's body still captures its tweet",
        only.emits.length === 1 && only.emits[0].msg.origin === "https://x.com/pageowner/status/8110", JSON.stringify(emitted(only)));

    // A conversation body with no focal key, the reply listed FIRST: the
    // status URL names the tweet, so the reply is not taken for it.
    const focal2 = tweet("8112", "pageowner", "The tweet the page is about https://t.co/d4", [media("610112", 8000)]);
    const reply2 = tweet("8113", "replier", "@pageowner first in the list https://t.co/d5", [media("610113", 4000)]);
    const conv = await drive("https://x.com/pageowner/status/8112", "main_frame", 86, "twDocConv",
        doc(null, { data: conversation([item(reply2), item(focal2)]) }));
    check("twitter-doc: with no focal key the status URL names the tweet, not the reply listed first",
        conv.emits.length === 1 && conv.emits[0].msg.origin === "https://x.com/pageowner/status/8112", JSON.stringify(emitted(conv)));

    // A profile page, logged out: the timeline is in the document and no
    // GraphQL XHR follows. Every video tweet is its own capture.
    const p1 = tweet("8201", "pageowner", "First clip https://t.co/p1", [media("620201", 54500)]);
    const p2 = tweet("8202", "pageowner", "A photo", [{ id_str: "620202", type: "photo", media_url_https: "https://pbs.twimg.com/media/x.jpg" }]);
    const p3 = sensitive(tweet("8203", "pageowner", "Third clip https://t.co/p3", [media("620203", 41014)]));
    const profile = await drive("https://x.com/pageowner", "main_frame", 87, "twDocProfile", doc(null, timelineBody(p1, p2, p3)));
    check("twitter-doc: the document listener reads a profile page, not only a status page", profile.matched >= 1, profile.matched);
    const profileOrigins = profile.emits.map(e => e.msg.origin).sort();
    check("twitter-doc: a profile document emits every video of its timeline, each under its own tweet",
        JSON.stringify(profileOrigins) === JSON.stringify(["https://x.com/pageowner/status/8201", "https://x.com/pageowner/status/8203"])
            && profile.emits.every(e => e.msg.type === "variants"),
        JSON.stringify(emitted(profile)));
    const autoplayed = await drive(master("620201"), "xmlhttprequest", 87, "twDocProfileMaster");
    check("twitter-doc: a master the profile page plays is not captured again off the wire", autoplayed.emits.length === 0, JSON.stringify(emitted(autoplayed)));

    // A document with no store (a settings page, a login wall) emits nothing.
    const plain = await drive("https://x.com/settings/account", "main_frame", 88, "twDocPlain", "<html><body>no store here</body></html>");
    check("twitter-doc: a document with no store emits nothing", plain.emits.length === 0, JSON.stringify(emitted(plain)));
}

// Bluesky: every video of a response is cached BEFORE any emit awaits, so a
// master the player fetches during the emit round trips is enriched, not
// generic (a generic emit would win the origin dedup).
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
// playlist would complete it with a non-master URL), and the tab's NEWEST
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

// YouTube embed facades (youtube-facade.js): the handler claims a YouTube
// player URL the content script reported and hands the video to the youtube@
// extension over runtime.sendMessage(extensionId, …); the reply decides
// whether the placeholder claim is kept. Mutants that must fail: a handler
// that keeps the claim on a false reply (the retry check, 2 fail) and an id
// test that takes any path segment ("videoseries", 2 fail). The moved-tab
// check is the queue's own guard: a handler cannot be mutated past it.
{
    const handed = [];
    let reply = true;
    const realSend = browser.runtime.sendMessage;
    const realGet = browser.tabs.get;
    browser.runtime.sendMessage = async (ext, msg) => { handed.push({ ext, msg }); return reply; };
    browser.tabs.get = async (id) => ({ id, incognito: false, url: "https://news.example/post/1" });
    const ids = () => handed.map(h => h.msg.videoId);

    await placeholderDispatch(60, [
        "https://www.youtube.com/embed/dQw4w9WgXcQ?feature=oembed",
        "https://www.youtube.com/embed/videoseries?list=PLabc",
        "https://player.vimeo.com/api/player.js",
    ], "https://news.example/post/1");
    const h0 = handed[0];
    check("youtube facade: the embed's video is handed to youtube@ with the tab and the page",
        handed.length === 1 && h0.ext === "youtube@solarized.dev" && h0.msg.kind === "youtube-facade"
        && h0.msg.videoId === "dQw4w9WgXcQ" && h0.msg.tabId === 60 && h0.msg.pageUrl === "https://news.example/post/1"
        && h0.msg.embedUrl === "https://www.youtube.com/embed/dQw4w9WgXcQ?feature=oembed", JSON.stringify(handed));
    await placeholderDispatch(60, ["https://www.youtube.com/embed/dQw4w9WgXcQ?feature=oembed"], "https://news.example/post/1");
    check("youtube facade: the same video reported again on the same page is handed over once",
        ids().filter(v => v === "dQw4w9WgXcQ").length === 1, JSON.stringify(ids()));

    reply = false;
    await placeholderDispatch(61, ["https://youtu.be/9bZkp7q19f0"], "https://news.example/post/1");
    await placeholderDispatch(61, ["https://youtu.be/9bZkp7q19f0"], "https://news.example/post/1");
    check("youtube facade: a hand-over youtube@ refused (no streams) is retried on the next report",
        ids().filter(v => v === "9bZkp7q19f0").length === 2, JSON.stringify(ids()));

    reply = true;
    await placeholderDispatch(62, [
        "https://www.youtube-nocookie.com/embed/abcdefghijk?rel=0",
        "https://m.youtube.com/watch?v=ABCDEFGHIJK&t=10",
        "https://www.youtube.com/shorts/aaaaaaaaaaa",
        "https://www.youtube.com/embed/tooshort",
    ], "https://news.example/post/1");
    const shapes = ids().slice(-3);
    check("youtube facade: nocookie embeds, watch links and shorts name their video; a malformed id does not",
        shapes.join(",") === "abcdefghijk,ABCDEFGHIJK,aaaaaaaaaaa" && !ids().includes("tooshort"), JSON.stringify(ids()));

    browser.tabs.get = async (id) => ({ id, incognito: false, url: "https://news.example/elsewhere" });
    await placeholderDispatch(63, ["https://www.youtube.com/embed/zzzzzzzzzzz"], "https://news.example/post/1");
    check("youtube facade: a tab that left the page hands nothing over", !ids().includes("zzzzzzzzzzz"), JSON.stringify(ids()));

    browser.runtime.sendMessage = realSend;
    browser.tabs.get = realGet;
}

console.log(failures ? `\nparsers-replay: ${failures} FAILURE(S)` : "\nparsers-replay: all checks passed");
process.exit(failures ? 1 : 0);
