// LIVE canary — checks whether the parsers still work against TODAY'S real
// upstream endpoints, by fetching the actual APIs over the network and running
// the REAL registered listeners on the live bodies (the replay harness, with
// real fetch). This is the "did the site change shape?" check the offline
// suites deliberately cannot be: those pin our code against recorded shapes;
// this one pins it against the internet, right now.
//
// Scope is the SESSION-FREE sites only — the ones whose capture path works
// logged-out over a plain fetch:
//   dailymotion — embed config + details + signed-master round trip (pinned id)
//   bluesky     — public searchPosts feed, self-discovering (no pinned post)
//   vimeo       — player config of a long-lived public video (pinned id)
//   apple       — iTunes Lookup deep-link path, episode self-discovered
//   rumble      — embedJS, embed id self-discovered from the homepage
//   deezer      — the GUEST gw-light two-step (getUserData → pageTrack) for a
//                 pinned track, then the real listener on the live song body.
//                 Measures the gateway SHAPE only: the parser's login gate is a
//                 session concern the canary can't have, so cookies.getAll hands
//                 it a stub `arl`; nothing is ever downloaded.
// Instagram / Threads / Facebook / TikTok / Niconico / Twitch(live) / Kick are
// EXCLUDED on purpose: login-walled or anti-bot-walled, so a bare fetch gets a
// gated shell — a canary there would only measure the wall, not the parser
// (the falsely-exonerating-repro trap). For those, the oracle is the app on a
// device, and the repro tool is a fresh HAR through the replay harnesses.
//
// Run MANUALLY from any normal network:  node scripts/live-canary.mjs
// NEVER in CI — live sites rate-limit, geo-gate and flake. Statuses:
//   PASS  the real listener extracted a real video from the live response
//   FAIL  endpoint reachable and media-bearing, but the REAL code extracted
//         nothing → the site (or our parser) changed — investigate with a HAR
//   SKIP  network unreachable / pinned content gone / sample had no media —
//         inconclusive, NOT a failure (exit stays 0)
// Exit code is 1 only when at least one canary FAILs.
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const parserDir = join(scriptDir, "..", "app/src/main/assets/webrequests/js/parsers");

// Pinned ids — long-lived public content; override when one dies:
//   DM_ID:    any public Dailymotion video id (the default is from the marca HAR)
//   VIMEO_ID: any public, embeddable Vimeo video (default: Vimeo's own player
//             announcement, public since 2013)
//   APPLE_ID: any long-running podcast SHOW id (an episode is discovered live)
const DM_ID = process.env.DM_ID || "xb1k5fe";
const VIMEO_ID = process.env.VIMEO_ID || "76979871";
const APPLE_ID = process.env.APPLE_ID || "1200361736";
//   DZ_ID:    any public Deezer track id (default: Daft Punk — "Harder, Better,
//             Faster, Stronger", the example id in Deezer's own API docs)
const DZ_ID = process.env.DZ_ID || "3135556";

const UA = "Mozilla/5.0 (Android 12; Mobile; rv:154.0) Gecko/154.0 Firefox/154.0";

// ---------------------------------------------------------------------------
// browser stub + fake filterResponseData — the replay harness, except fetch
// stays REAL (wrapped only to carry a browser UA + a timeout, the same UA the
// extension's own fetches ride on-device).
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
    // The Deezer parser gates its emit on an `arl` (signed-in) cookie. The
    // canary measures the gateway SHAPE, not auth, so hand it a stub arl for
    // deezer.com only; every other site sees an empty jar as before.
    cookies: {
        onChanged: evt("cookies.onChanged"),
        getAll: async ({ url } = {}) => (url && url.includes("deezer.com")) ? [{ name: "arl", value: "canary" }] : [],
    },
    storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
};

Object.defineProperty(globalThis, "navigator", {
    value: { userAgent: UA, languages: ["en-US"] },
    configurable: true,
});

const realFetch = globalThis.fetch;
globalThis.fetch = (url, opts = {}) => realFetch(url, {
    signal: AbortSignal.timeout(20000),
    ...opts,
    headers: { "User-Agent": UA, "Accept-Language": "en-US,en;q=0.9", ...(opts.headers || {}) },
});

for (const mod of ["dailymotion", "bluesky", "vimeo", "apple-podcasts", "rumble", "deezer"]) {
    await import(pathToFileURL(join(parserDir, mod + ".js")));
}

// ---------------------------------------------------------------------------
// harness helpers (patternMatches/listenersMatching as in the replays)
// ---------------------------------------------------------------------------
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
        && (!r.filter?.types || r.filter.types.includes(type))
        && r.filter?.urls?.some(p => patternMatches(p, url)));
}
const isCapture = (s) => ["variants", "hls-master", "media", "deezer"].includes(s.msg?.type);

// Dispatch through the real matching listeners; feed a live body if given;
// then WAIT for an emit — live paths do their own sequential network fetches
// (dailymotion: details + master; vimeo: config; apple: lookup), so poll up
// to 25s instead of a fixed tick.
async function driveLive(url, type, tabId, requestId, body) {
    const matching = listenersMatching(url, type);
    const before = nativeSent.length;
    for (const r of matching) r.fn({ url, type, tabId, requestId, method: "GET" });
    if (body != null) {
        const f = filters.get(requestId);
        if (f) {
            f.ondata({ data: new TextEncoder().encode(body).buffer });
            f.onstop();
        }
    }
    const deadline = Date.now() + 25000;
    while (Date.now() < deadline) {
        const emits = nativeSent.slice(before).filter(isCapture);
        if (emits.length > 0) {
            await new Promise(r => setTimeout(r, 500)); // let siblings land
            return nativeSent.slice(before).filter(isCapture);
        }
        await new Promise(r => setTimeout(r, 250));
    }
    return [];
}

async function getLive(url, headers) {
    try {
        const resp = await globalThis.fetch(url, { headers });
        const text = await resp.text();
        return { ok: resp.ok, status: resp.status, text };
    } catch (e) {
        return { ok: false, status: 0, text: "", error: e.message };
    }
}

// POST variant for gateways that answer only to POST (Deezer's gw-light), also
// surfacing Set-Cookie so a minted guest `sid` can ride the follow-up call.
async function postLive(url, body, headers = {}) {
    try {
        const resp = await globalThis.fetch(url, {
            method: "POST", body,
            headers: { "Content-Type": "application/json", ...headers },
        });
        const text = await resp.text();
        const setCookie = typeof resp.headers.getSetCookie === "function"
            ? resp.headers.getSetCookie()
            : [resp.headers.get("set-cookie") || ""].filter(Boolean);
        return { ok: resp.ok, status: resp.status, text, setCookie };
    } catch (e) {
        return { ok: false, status: 0, text: "", setCookie: [], error: e.message };
    }
}

const results = [];
function report(site, status, detail) {
    results.push({ site, status });
    console.log(`${status.padEnd(4)} ${site}: ${detail}`);
}

// ---------------------------------------------------------------------------
// Dailymotion — the deepest canary: feed the LIVE embed config through the
// real listener, which then live-fetches /details AND the signed master and
// parses it with the real parseHlsMaster. A PASS means the whole pipeline —
// config shape, details shape, CDN auth, master format — still holds today.
// ---------------------------------------------------------------------------
{
    const cfg = await getLive(`https://geo.dailymotion.com/videos/${DM_ID}`);
    if (!cfg.ok) {
        report("dailymotion", "SKIP", `config fetch ${cfg.status || cfg.error} — network block, or pinned id gone (override with DM_ID=<id>)`);
    } else if (!cfg.text.includes(".m3u8")) {
        report("dailymotion", "FAIL", "config is 200 but carries no .m3u8 — the embed API shape moved");
    } else {
        const emits = await driveLive(`https://geo.dailymotion.com/videos/${DM_ID}?canary=1`,
            "xmlhttprequest", 200, "liveDm", cfg.text);
        const m = emits[0]?.msg;
        if (m && (m.variants?.length || m.type === "hls-master")) {
            report("dailymotion", "PASS",
                `captured "${m.name || "(untitled)"}" — ${m.variants?.length || 0} variant(s), duration ${m.duration || 0}ms`);
        } else {
            report("dailymotion", "FAIL", "live config carries a master but the real listener emitted nothing");
        }
    }
}

// ---------------------------------------------------------------------------
// Bluesky — self-discovering: a public search feed, walked by the real
// listener. The raw-body marker check is the discriminator: if the JSON
// carries video#view nodes and the walker extracts none, that's a FAIL; if
// the sample simply has no videos, that's a SKIP, not a verdict.
// ---------------------------------------------------------------------------
{
    const feed = await getLive("https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts?q=video&limit=50");
    if (!feed.ok) {
        report("bluesky", "SKIP", `searchPosts fetch ${feed.status || feed.error}`);
    } else if (!feed.text.includes("app.bsky.embed.video#view")) {
        report("bluesky", "SKIP", "sample feed had no video embeds (rerun, or the embed $type was renamed — check a post by hand)");
    } else {
        const emits = await driveLive("https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts?q=video&limit=50&canary=1",
            "xmlhttprequest", 201, "liveBsky", feed.text);
        if (emits.length > 0) {
            report("bluesky", "PASS", `${emits.length} video(s) captured from the live feed (first: "${emits[0].msg.name}")`);
        } else {
            report("bluesky", "FAIL", "feed carries video#view nodes but the real walker extracted none");
        }
    }
}

// ---------------------------------------------------------------------------
// Vimeo — the listener live-fetches the player config itself.
// ---------------------------------------------------------------------------
{
    const probe = await getLive(`https://player.vimeo.com/video/${VIMEO_ID}`);
    if (!probe.ok) {
        report("vimeo", "SKIP", `player page ${probe.status || probe.error} — network block, or pinned id gone (override with VIMEO_ID=<id>)`);
    } else {
        const emits = await driveLive(`https://player.vimeo.com/video/${VIMEO_ID}?canary=1`, "sub_frame", 202, "liveVimeo");
        const m = emits[0]?.msg;
        if (m) report("vimeo", "PASS", `captured "${m.name || "(untitled)"}" (${m.type})`);
        else report("vimeo", "FAIL", "player page reachable but the real listener found no config HLS — the config shape moved");
    }
}

// ---------------------------------------------------------------------------
// Apple Podcasts — discover a current episode of the pinned show via the
// public iTunes Lookup, then drive the real ?i= deep-link trigger (which does
// its own live lookup for that episode).
// ---------------------------------------------------------------------------
{
    const disc = await getLive(`https://itunes.apple.com/lookup?id=${APPLE_ID}&entity=podcastEpisode&limit=2`);
    let episodeId = null;
    try { episodeId = JSON.parse(disc.text)?.results?.find(r => r.kind === "podcast-episode")?.trackId || null; }
    catch (_) { /* handled below */ }
    if (!disc.ok || !episodeId) {
        report("apple", "SKIP", `episode discovery failed (${disc.status || disc.error}) — network block, or override APPLE_ID=<showId>`);
    } else {
        const emits = await driveLive(
            `https://podcasts.apple.com/us/podcast/canary/id${APPLE_ID}?i=${episodeId}`,
            "main_frame", 203, "liveApple");
        const m = emits[0]?.msg;
        if (m && m.url) report("apple", "PASS", `captured "${m.name}" → ${m.url.slice(0, 60)}…`);
        else report("apple", "FAIL", `lookup knows episode ${episodeId} but the real trigger emitted nothing`);
    }
}

// ---------------------------------------------------------------------------
// Rumble — discover an embed id from the live homepage, then feed the LIVE
// embedJS body through the real listener.
// ---------------------------------------------------------------------------
{
    const home = await getLive("https://rumble.com/");
    const embedId = (home.text.match(/rumble\.com\/embed\/([a-z0-9]{5,10})[/"?]/i) || [])[1] || null;
    if (!home.ok || !embedId) {
        report("rumble", "SKIP", `homepage ${home.status || home.error}${home.ok ? " — no embed id found in HTML" : ""}`);
    } else {
        const ej = await getLive(`https://rumble.com/embedJS/u3/?request=video&ver=2&v=${embedId}`);
        if (!ej.ok) {
            report("rumble", "SKIP", `embedJS fetch ${ej.status || ej.error}`);
        } else {
            const emits = await driveLive(`https://rumble.com/embedJS/u3/?request=video&ver=2&v=${embedId}&canary=1`,
                "xmlhttprequest", 204, "liveRumble", ej.text);
            const m = emits[0]?.msg;
            if (m) report("rumble", "PASS", `captured "${m.description || m.name || "(untitled)"}" (${m.type})`);
            else report("rumble", "FAIL", "embedJS is 200 but the real listener emitted nothing — the embedJS shape moved");
        }
    }
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Deezer — the GUEST gw-light two-step, then the real listener on the live
// song body. getUserData (anonymous, empty api_token) mints a guest checkForm +
// a `sid` cookie; pageTrack with that token returns the song object
// (SNG_ID / SNG_TITLE / TRACK_TOKEN — exactly the shape collectSongs walks).
// Token/flow refusals are SKIP (inconclusive — the guest flow moved, and the
// next step is a signed-in HAR through parsers-replay); a 200 song-bearing body
// the REAL listener can't extract is a FAIL. Nothing is downloaded: the probe
// stops at the capture emit.
// ---------------------------------------------------------------------------
{
    const GW = "https://www.deezer.com/ajax/gw-light.php";
    const cid = () => Math.floor(Math.random() * 1e9);
    const user = await postLive(`${GW}?method=deezer.getUserData&input=3&api_version=1.0&api_token=&cid=${cid()}`, "{}");
    let checkForm = null;
    try { checkForm = JSON.parse(user.text)?.results?.checkForm || null; } catch { /* not JSON */ }
    if (!user.ok || !checkForm) {
        report("deezer", "SKIP", `getUserData ${user.status || user.error} — network block, or the guest gateway now wants more than an empty api_token`);
    } else {
        const sid = (user.setCookie.find(c => c.startsWith("sid=")) || "").split(";")[0];
        const trackUrl = `${GW}?method=deezer.pageTrack&input=3&api_version=1.0&api_token=${encodeURIComponent(checkForm)}&cid=${cid()}`;
        const track = await postLive(trackUrl, JSON.stringify({ sng_id: DZ_ID }), sid ? { Cookie: sid } : {});
        let parsed = null;
        try { parsed = JSON.parse(track.text); } catch { /* not JSON */ }
        // gw-light: `error` is an EMPTY ARRAY on success, an OBJECT of codes on refusal.
        const refusals = parsed && parsed.error && !Array.isArray(parsed.error) ? Object.keys(parsed.error) : [];
        if (!track.ok || !parsed) {
            report("deezer", "SKIP", `pageTrack ${track.status || track.error} — network block, or pinned id gone (override with DZ_ID=<id>)`);
        } else if (refusals.length) {
            report("deezer", "SKIP", `pageTrack refused (${refusals.join(",")}) — the guest token flow changed; capture a signed-in HAR next`);
        } else if (!track.text.includes("\"SNG_ID\"")) {
            report("deezer", "FAIL", "pageTrack is 200 JSON with no SNG_ID anywhere — the song object shape moved");
        } else {
            const emits = await driveLive(`${trackUrl}&canary=1`, "xmlhttprequest", 206, "liveDeezer", track.text);
            const m = emits.find(e => e.msg?.type === "deezer" && String(e.msg.url).includes(`/track/${DZ_ID}`));
            if (m) report("deezer", "PASS", `captured "${m.msg.name}" — ${m.msg.description || "?"} (${String(m.msg.url).split("?fmt=")[1] || "?"})`);
            else report("deezer", "FAIL", "live body carries SNG_ID but the real listener emitted nothing — isSongObject/collectSongs no longer match it (TRACK_TOKEN dropped?)");
        }
    }
}

const fails = results.filter(r => r.status === "FAIL").length;
const skips = results.filter(r => r.status === "SKIP").length;
console.log(`\nlive-canary: ${results.length - fails - skips} pass, ${fails} fail, ${skips} skip`
    + (skips ? " (skips are inconclusive — network/pin issues, not verdicts)" : ""));
process.exit(fails ? 1 : 0);
