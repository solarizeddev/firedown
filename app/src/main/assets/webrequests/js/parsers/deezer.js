// Deezer parser — two independent paths, see the headers below:
//   1. FULL TRACKS  (www.deezer.com gw-light gateway, needs a signed-in session)
//   2. WIDGET PREVIEWS (widget.deezer.com / api.deezer.com, NO login at all)
import { log, sendNative, resolveTabId, readFilteredJson, decodeHtmlEntities } from './common.js';

// ============================================================================
// Deezer  —  https://www.deezer.com  (full tracks, NOT the 30s preview)
// ============================================================================
//
// Deezer is NOT DRM in the EME/Widevine sense — there is no license server and
// no per-session key. A track streams as Blowfish-CBC ciphertext (every THIRD
// 2048-byte block enciphered, the other two-thirds plaintext) whose key is
// DERIVED CLIENT-SIDE from MD5(SNG_ID) XOR'd with a hardcoded, static secret.
// The key is user-independent and computable offline — the same reason deemix /
// d-fi work. So a captured CDN URL alone is undownloadable (the wire serves
// ciphertext), but WITH the SNG_ID the bytes decrypt deterministically. This is
// the exact Mega shape: a capture that carries side-channel key material and a
// dedicated Java strategy (DeezerStrategy) that resolves the real URL and
// decrypts the stream. The Blowfish key derivation lives in DeezerCrypto.
//
// WHAT THE PARSER DOES (capture side). The web player asks its gateway
// (POST www.deezer.com/ajax/gw-light.php?method=…) for song data on load/play —
// deezer.pageTrack / song.getListData / deezer.pageAlbum / deezer.pagePlaylist /
// search.music all return song objects carrying SNG_ID, SNG_TITLE, ART_NAME,
// ALB_PICTURE (cover md5), DURATION, and the per-account FILESIZE_* fields. We
// filterResponseData that gateway response (read-only, byte-exact — the Threads
// doc-filter pattern), walk it for song objects, and emit ONE entity per track.
//
// WHAT RIDES ON THE EMIT. Only the stable inputs, mirroring Mega's `?fk=` trick:
// a synthetic URL  https://www.deezer.com/track/<SNG_ID>?fmt=<FMT>  (SNG_ID is
// the whole decrypt input; it's also the stable dedup identity), plus the live
// browser SESSION COOKIE for www.deezer.com (arl + sid — pulled with the
// privileged browser.cookies.getAll so HttpOnly arl is included). We deliberately
// do NOT bake the CDN URL or a track_token into the capture: both expire, and the
// download can happen much later. DeezerStrategy re-mints everything at download
// time from the durable cookie + SNG_ID (getUserData → song.getData → get_url),
// so a deferred download can't go stale.
//
// FORMAT. We pick the best format the file EXISTS in from FILESIZE_* (FLAC >
// MP3_320 > MP3_128) and encode it in the URL; the strategy steps DOWN the ladder
// if the account's license doesn't grant it (FILESIZE reflects the file, not the
// entitlement), so a free account still gets MP3_128 rather than failing.
//
// CEILING (state honestly): this needs a LOGGED-IN, STREAMING-ENTITLED session in
// the in-app browser. Logged out, gw-light answers USER_ID 0 with every streaming
// flag false and get_url serves no FULL source at all — the only audio a guest can
// have is the 30s preview, which the WIDGET path below captures. 320/FLAC need
// Premium/HiFi. The one maintenance liability is the static Blowfish secret in
// DeezerCrypto: if Deezer ever rotates it, downloads decrypt to garbage — that's
// the first suspect for any regression.
//
// Cardinal rule: the Deezer media CDN (e-cdns-proxy-*.dzcdn.net) is block-listed
// in parser-blocklist.js (`deezer`) — not for dedup but because a bare catcher
// capture there would save UNDECRYPTABLE ciphertext (same "block a harmful
// capture" rationale as Mega). The 30s preview host is a different CDN and is
// left alone (logged-out preview capture still works generically).

const GW_LIGHT_PATTERNS = [
    "*://www.deezer.com/ajax/gw-light.php*"
];

// A playlist/album gateway response inlines its whole track list; cap the
// per-response emit defensively so a huge playlist can't flood the sheet.
const MAX_DEEZER_TRACKS = 200;

// Bounded walk of the gateway JSON — song objects live at different depths per
// method (results.DATA, results.SONGS.data[], results.data[], results.TRACK.data[]…),
// so we shape-match instead of hardcoding each method's layout. Xray is not a
// concern here (this is a plain JSON.parse result, not a page-world object), but
// the caps keep a pathological response cheap.
const WALK_MAX_DEPTH = 12;
const WALK_MAX_NODES = 30000;

// Recently-emitted SNG_IDs (30s TTL): the gateway re-requests the same song data
// on refresh / SPA re-render; a re-emit is harmless (URL dedup in the repository)
// but re-processing every track is wasteful and noisy.
const processedTracks = new Set();

// Is this object a Deezer song we can capture? It must carry a numeric SNG_ID
// and a TRACK_TOKEN string (the presence of a token is what distinguishes a
// real, playable track from a bare search stub / related-item shell).
export function isSongObject(o) {
    return o && typeof o === "object"
        && (typeof o.SNG_ID === "string" || typeof o.SNG_ID === "number")
        && typeof o.SNG_TITLE === "string" && o.SNG_TITLE.length > 0
        // A TRACK_TOKEN is what separates a real, playable track from a bare
        // search / related-item shell. The strategy re-mints a fresh token at
        // download time, so we don't KEEP this one — its presence is only the
        // "this is a capturable track" signal. Contexts that surface playable
        // tracks (page/album/playlist/track) all inline it.
        && typeof o.TRACK_TOKEN === "string" && o.TRACK_TOKEN.length > 0;
}

// Collect every distinct song object in the response (bounded). Deduped by
// SNG_ID within this walk; the caller adds the cross-response TTL dedup.
export function collectSongs(root) {
    const out = [];
    const seen = new Set();
    let budget = WALK_MAX_NODES;
    const visit = (node, depth) => {
        if (budget <= 0 || depth > WALK_MAX_DEPTH || node == null) return;
        budget--;
        if (Array.isArray(node)) {
            for (let i = 0; i < node.length && out.length < MAX_DEEZER_TRACKS; i++) {
                visit(node[i], depth + 1);
            }
            return;
        }
        if (typeof node !== "object") return;
        if (isSongObject(node)) {
            const id = String(node.SNG_ID);
            if (!seen.has(id)) {
                seen.add(id);
                out.push(node);
                if (out.length >= MAX_DEEZER_TRACKS) return;
            }
            // A song object can still nest others (e.g. FALLBACK) — keep walking.
        }
        const keys = Object.keys(node);
        for (let i = 0; i < keys.length && out.length < MAX_DEEZER_TRACKS; i++) {
            visit(node[keys[i]], depth + 1);
        }
    };
    visit(root, 0);
    return out;
}

// Best format the file EXISTS in (the strategy degrades down the ladder if the
// account isn't entitled). FILESIZE_* is a string/number; >0 means present.
export function pickFormat(song) {
    const size = (k) => {
        const v = song[k];
        const n = typeof v === "number" ? v : parseInt(v, 10);
        return Number.isFinite(n) ? n : 0;
    };
    if (size("FILESIZE_FLAC") > 0) return { fmt: "FLAC", size: size("FILESIZE_FLAC") };
    if (size("FILESIZE_MP3_320") > 0) return { fmt: "MP3_320", size: size("FILESIZE_MP3_320") };
    if (size("FILESIZE_MP3_256") > 0) return { fmt: "MP3_256", size: size("FILESIZE_MP3_256") };
    return { fmt: "MP3_128", size: size("FILESIZE_MP3_128") };
}

// Cover art: ALB_PICTURE is an md5 handle served from the images CDN at a
// requested size. 500x500 is a good grid thumbnail.
function coverUrl(song) {
    const md5 = song.ALB_PICTURE || song.ART_PICTURE;
    if (typeof md5 !== "string" || !md5) return undefined;
    const kind = song.ALB_PICTURE ? "cover" : "artist";
    return `https://e-cdns-images.dzcdn.net/images/${kind}/${md5}/500x500-000000-80-0-0.jpg`;
}

// The live browser session cookie for www.deezer.com. browser.cookies.getAll is
// privileged (host-permitted via <all_urls>), so it includes the HttpOnly `arl`
// that page JS can't read — which is exactly what the strategy needs to re-mint
// tokens at download time. Returns a Cookie header string, or "" when there is
// no LOGGED-IN session.
//
// "Logged in" means an `arl` cookie is present — NOT merely "some cookies
// exist". A logged-out (guest) visitor still carries `sid`/`dzr_uniq_id`/consent
// cookies, so a "jar non-empty" test would capture every track a guest browses
// past into an entity that can never download (get_url refuses FULL media on a
// guest license → a permanent error row). `arl` is the login remember-me token
// and only exists for a signed-in account, so it is the honest gate: no arl →
// emit nothing, and the generic catcher keeps the 30s preview a guest can get.
export function isLoggedInCookieJar(cookies) {
    if (!Array.isArray(cookies)) return false;
    for (let i = 0; i < cookies.length; i++) {
        const c = cookies[i];
        if (c && c.name === "arl" && typeof c.value === "string" && c.value.length > 0) return true;
    }
    return false;
}

async function deezerSessionCookie() {
    try {
        const cookies = await browser.cookies.getAll({ url: "https://www.deezer.com/" });
        if (!isLoggedInCookieJar(cookies)) return "";
        return cookies.map(c => `${c.name}=${c.value}`).join("; ");
    } catch (e) {
        log("DEEZER", "cookie fetch failed", e && e.message);
        return "";
    }
}

function listenerDeezerGateway(details) {
    readFilteredJson(details, "DEEZER", "gw-light", async (json, bytes) => {
        const songs = collectSongs(json && json.results !== undefined ? json.results : json);
        log("DEEZER", `gateway: ${bytes} bytes, ${songs.length} song(s)`, {
            url: details.url.slice(0, 120)
        });
        if (songs.length === 0) return;

        const cookie = await deezerSessionCookie();
        if (!cookie) {
            // No session → the strategy can't authenticate get_url; a logged-out
            // visitor only has the 30s preview, which the generic catcher grabs.
            log("DEEZER", "no www.deezer.com session cookie — skipping (logged out?)");
            return;
        }

        const tabId = await resolveTabId(details);
        let emitted = 0;
        for (const song of songs) {
            const sngId = String(song.SNG_ID);
            const key = "deezer:" + sngId;
            if (processedTracks.has(key)) continue;
            processedTracks.add(key);
            setTimeout(() => processedTracks.delete(key), 30000);

            const { fmt, size } = pickFormat(song);
            const durationSec = parseInt(song.DURATION, 10);
            const title = decodeHtmlEntities(song.SNG_TITLE);
            const artist = song.ART_NAME ? decodeHtmlEntities(song.ART_NAME) : undefined;

            const message = {
                // Synthetic capture URL: SNG_ID is the decrypt input AND the
                // stable dedup identity; fmt tells the strategy which ladder rung
                // to request. www.deezer.com/track/<id> is also the real page URL,
                // so it reads correctly as the entity's origin.
                url: `https://www.deezer.com/track/${sngId}?fmt=${fmt}`,
                type: "deezer",
                origin: `https://www.deezer.com/track/${sngId}`,
                tabId,
                request: details.requestId,
                name: title,
                description: artist,
                img: coverUrl(song),
                // Deezer DURATION is whole seconds → ms (the emit contract).
                duration: Number.isFinite(durationSec) && durationSec > 0
                    ? durationSec * 1000 : undefined,
                // Best-known encrypted file size for the chosen fmt (UI only; the
                // decrypted output is the same length, Blowfish is a stream-length
                // no-op). The strategy overwrites it with the real Content-Length.
                size: size > 0 ? size : undefined,
                // The durable session — DeezerStrategy authenticates every gateway
                // call and get_url with this (see the cookie note above).
                cookie
            };
            for (const k of Object.keys(message)) {
                if (message[k] === undefined) delete message[k];
            }
            sendNative(message);
            emitted++;
        }
        log("DEEZER", `emitted ${emitted} track(s)`, { tabId });
    });
    return {};
}

// The gateway is an xmlhttprequest POST; read it on the wire. main_frame is not
// needed — Deezer is a pure SPA, the gateway always fires as XHR.
browser.webRequest.onBeforeRequest.addListener(
    listenerDeezerGateway,
    { urls: GW_LIGHT_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);

// ============================================================================
// Deezer WIDGET  —  https://widget.deezer.com/widget/<theme>/track/<id>
// ============================================================================
//
// The embeddable widget (what publishers drop into an article, and what a
// deezer.com link previews as) plays a ~30s preview clip and needs NO LOGIN — it
// mints an ANONYMOUS bearer token (POST api.deezer.com/platform/generic/token/
// unlogged → userId 0) and calls a public REST API with it. This is the Spotify
// embed case exactly: the full track is auth-gated (see the gw-light path above),
// the preview is the only capturable audio, and it is capturable by anyone.
//
// WHY A PARSER (vs. the generic catcher). The catcher ALREADY downloads the
// preview fine — the .mp3 crosses the wire on play — but it lands UNTITLED,
// because the title never touches the audio request. The widget document is a
// Next.js shell with EMPTY pageProps (HAR-verified: `"pageProps":{}`), there are
// no og: tags, and the metadata lives only in a JSON body the catcher doesn't
// read. So the name has to come from the API, which is what this does.
//
// THE SHAPE — two separate calls, correlated by track id (HAR-verified):
//   GET api.deezer.com/platform/generic/track/<id>
//     → data:{ type:"track", id, attributes:{ title, artistName, albumName,
//              duration /* FULL track seconds, NOT the preview */, image:{...} } }
//   GET api.deezer.com/platform/generic/track/<id>/previewUrl
//     → data:{ type:"previewUrl", id, attributes:{ url: ".../<hash>.mp3?hdnea=…" } }
// Order is not guaranteed (and the metadata call is issued twice), so both are
// cached by track id and the emit fires as soon as BOTH halves are known.
//
// Emit shape mirrors spotify.js: type:"media", NO duration and NO skipProbe. The
// API `duration` is the FULL track length (226s here) and would mislabel a 30s
// clip; the preview URL's extension is .mp3 but the honest length can only come
// from the native probe of the tiny file, so let it probe.
//
// NO parser-blocklist RULE, deliberately. The preview host (cdnt-preview.dzcdn.net)
// is left un-blocked because both JSON bodies arrive BEFORE the player fetches the
// audio, so this titled emit lands FIRST and the repository dedups the catcher's
// later capture of the identical URL. Blocking would buy nothing and would turn
// any future API-shape change into a TOTAL loss of the preview, where today it
// degrades to the working-but-untitled catcher capture. Same "keep the safety
// net" reasoning as TikTok's deliberately un-blocked media host.
//
// CEILING: the preview URL is signed with a short `hdnea=exp=` window (~25 min in
// the HAR). It is emitted verbatim, so a download deferred past that expires —
// acceptable for a 30s clip the user is grabbing now, and the same trade the
// catcher already makes today.

const DZ_WIDGET_API_PATTERNS = [
    "*://api.deezer.com/platform/generic/track/*"
];

// trackId → {title, artist, cover} and trackId → previewUrl, held only until
// their partner arrives (the two calls are milliseconds apart).
const widgetMeta = new Map();
const widgetPreview = new Map();
const widgetSent = new Set();
const WIDGET_TTL = 60_000;

function rememberWidget(map, id, value) {
    map.set(id, value);
    setTimeout(() => map.delete(id), WIDGET_TTL);
}

// Largest usable cover. `full` (1200px) is wasteful for a grid tile and `tiny`
// is unusable, so prefer large → medium → full → small.
function widgetCover(image) {
    if (!image || typeof image !== "object") return undefined;
    return image.large || image.medium || image.full || image.small || undefined;
}

// Pure extractors (exported for the replay harnesses).
export function extractWidgetTrack(json) {
    const d = json && json.data;
    if (!d || d.type !== "track" || !d.id) return null;
    const a = d.attributes || {};
    if (typeof a.title !== "string" || !a.title) return null;
    return {
        id: String(d.id),
        title: decodeHtmlEntities(a.title),
        artist: typeof a.artistName === "string" && a.artistName
            ? decodeHtmlEntities(a.artistName) : undefined,
        album: typeof a.albumName === "string" && a.albumName ? a.albumName : undefined,
        cover: widgetCover(a.image),
    };
}

export function extractWidgetPreviewUrl(json) {
    const d = json && json.data;
    if (!d || d.type !== "previewUrl" || !d.id) return null;
    const url = d.attributes && d.attributes.url;
    if (typeof url !== "string" || !url) return null;
    return { id: String(d.id), url };
}

async function emitWidgetPreview(id, details) {
    if (widgetSent.has(id)) return;
    const meta = widgetMeta.get(id);
    const url = widgetPreview.get(id);
    if (!meta || !url) return;              // wait for the other half
    widgetSent.add(id);
    setTimeout(() => widgetSent.delete(id), WIDGET_TTL);

    const tabId = await resolveTabId(details);
    const message = {
        url,
        type: "media",
        // The canonical track page reads correctly as the entity's origin and
        // matches the full-track path's origin for the same song.
        origin: `https://www.deezer.com/track/${id}`,
        tabId,
        request: details.requestId,
        name: meta.title,
        description: meta.artist,
        img: meta.cover,
    };
    for (const k of Object.keys(message)) {
        if (message[k] === undefined) delete message[k];
    }
    sendNative(message);
    log("DEEZER", "widget preview emitted", { id, name: meta.title, tabId });
}

function listenerDeezerWidgetApi(details) {
    // Skip the CORS preflight (204, empty body) — only the real GET carries JSON.
    if (details.method && details.method !== "GET") return {};
    readFilteredJson(details, "DEEZER", "widget api", async (json) => {
        const track = extractWidgetTrack(json);
        if (track) {
            rememberWidget(widgetMeta, track.id, track);
            await emitWidgetPreview(track.id, details);
            return;
        }
        const preview = extractWidgetPreviewUrl(json);
        if (preview) {
            rememberWidget(widgetPreview, preview.id, preview.url);
            await emitWidgetPreview(preview.id, details);
        }
    });
    return {};
}

// Both the metadata and previewUrl calls are plain GET xmlhttprequests issued by
// the widget iframe (Origin: https://widget.deezer.com) against the public API.
browser.webRequest.onBeforeRequest.addListener(
    listenerDeezerWidgetApi,
    { urls: DZ_WIDGET_API_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"]
);
