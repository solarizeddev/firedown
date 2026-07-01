// Spotify embed parser.
import { log, tryParseJson, sendNative, resolveTabId, readFilteredBody } from './common.js';

// ============================================================================
// Spotify  —  https://open.spotify.com/embed/{track,episode,playlist,album,show}
// ============================================================================
//
// Publishers embed a Spotify widget as a cross-origin iframe
// (open.spotify.com/embed/<type>/<id>, e.g. elmundo.es embeds a "Top 100" music
// playlist). What that widget actually PLAYS — logged out, and even logged in,
// because embeds are preview-only by Spotify's design — is a ~30s, NON-DRM
// MP3_96 clip per track from p.scdn.co/mp3-preview/<hash>. The full track is
// auth-gated DRM on an encrypted CDN and never appears as a plain file; the
// preview is the only capturable audio. (This is the case CLAUDE.md's Spotify
// note used to rule a parser out for. The embed shape below is clean enough —
// and the "name each preview" gap real enough — that the decision was revisited;
// see the note there.)
//
// WHY A PARSER (vs. the generic catcher). The generic catcher DOES grab a
// preview when the user presses play (the wire sees the p.scdn.co 206), but it
// can't NAME it: the embed sets navigator.mediaSession.metadata only on
// `state_changed` (AFTER the MP3 is fetched) and keys it by the track URI, not
// the preview URL — so the catcher's one-shot capture-time metadata query lands
// before the per-track title exists and falls back to the iframe's page-level
// og:title, i.e. the PLAYLIST name shared by every clip. Meanwhile the correct
// per-track mapping (previewUrl -> {title, artist, cover}) is inlined,
// deterministically and pre-play, in the embed HTML's __NEXT_DATA__:
//
//   props.pageProps.state.data.entity = {
//     type, name, coverArt:{sources:[{url}]},
//     trackList: [ { uri:"spotify:track:<id>", title, subtitle /*artist*/,
//                    duration /*FULL track ms, NOT the preview*/,
//                    audioPreview:{ format:"MP3_96", url:"…/mp3-preview/…" } }, … ]
//   }
//
// The __NEXT_DATA__ is a <script type="application/json"> the HTML parser keeps
// as raw text (Next.js unicode-escapes, no HTML entities to decode), so it reads
// clean. We filterResponseData the embed document (write-through, byte-exact —
// same stock-GeckoView pattern as Threads' doc filter; the embed loads as a
// sub_frame, and also as main_frame if the URL is opened directly) and emit one
// titled audio entry per previewable track.
//
// Emit shape: type:"media" (audio), NO duration and NO skipProbe. The trackList
// `duration` is the FULL track length (e.g. 3:29) — passing it would mislabel a
// 30s clip — and the preview URL is EXTENSIONLESS, so processMediaSkipProbe
// would fall back to the probe anyway. Letting the native metadatareader probe
// the tiny clip confirms it's audio and yields the true ~30s duration. Each
// track is emitted under its own open.spotify.com/<type>/<id> origin so it lands
// as its own entity; the repository dedups by URL, so a re-read of the same
// embed (refresh/SPA) can't duplicate.
//
// Cardinal rule: p.scdn.co/mp3-preview is block-listed (parser-blocklist.js
// `spotify`) so the generic catcher doesn't ALSO grab a bare, untitled copy when
// the widget fetches the same preview on play. The block governs capture only —
// the widget's own playback is untouched.

const SPOTIFY_EMBED_PATTERNS = [
    "*://open.spotify.com/embed/*"
];

// A playlist embed inlines its whole track list (100 for a "Top 100"); cap the
// per-document emit defensively so a pathological blob can't flood the sheet.
const MAX_SPOTIFY_TRACKS = 200;

// Recently-processed embed URLs (30s TTL) — the widget can re-request its own
// document (refresh / SPA remount); a re-emit is harmless (URL dedup in the
// repository) but re-probing every clip is wasteful and noisy.
const processedEmbeds = new Set();

// Largest cover source (sources are unordered; some carry null width). Falls
// back to the last entry when no width is known.
function coverFromSources(coverArt) {
    const sources = coverArt?.sources;
    if (!Array.isArray(sources) || sources.length === 0) return undefined;
    let best = sources[0];
    for (const s of sources) {
        if ((s?.width || 0) > (best?.width || 0)) best = s;
    }
    return best?.url || sources[sources.length - 1]?.url || undefined;
}

// spotify:track:<id> / spotify:episode:<id> -> open.spotify.com/<type>/<id>.
function spotifyUrlFromUri(uri) {
    const m = (uri || "").match(/^spotify:(track|episode):([A-Za-z0-9]+)$/);
    return m ? `https://open.spotify.com/${m[1]}/${m[2]}` : undefined;
}

// Pure extractor (exported for the smoke test's HAR replay): pull the embed's
// entity out of __NEXT_DATA__ and normalize its previewable tracks. Handles both
// a multi-track entity (playlist/album/show → entity.trackList) and a
// single-entity embed (track/episode with audioPreview on the entity itself).
// Skips any track whose audioPreview is absent (DRM-only / unavailable) and
// dedups repeated preview URLs.
export function extractSpotifyEmbedTracks(html) {
    if (typeof html !== "string") return { entity: null, tracks: [] };
    const m = html.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
    if (!m) return { entity: null, tracks: [] };
    const data = tryParseJson(m[1]);
    const entity = data?.props?.pageProps?.state?.data?.entity;
    if (!entity || typeof entity !== "object") return { entity: null, tracks: [] };

    const entityCover = coverFromSources(entity.coverArt);
    const list = Array.isArray(entity.trackList) && entity.trackList.length > 0
        ? entity.trackList
        : [entity];

    const tracks = [];
    const seen = new Set();
    for (const item of list) {
        const url = item?.audioPreview?.url;
        if (!url || typeof url !== "string") continue;   // DRM-only / no preview
        if (seen.has(url)) continue;
        seen.add(url);
        tracks.push({
            url,
            title: item.title || undefined,
            artist: item.subtitle || undefined,
            cover: coverFromSources(item.coverArt) || entityCover,
            origin: spotifyUrlFromUri(item.uri)
        });
        if (tracks.length >= MAX_SPOTIFY_TRACKS) break;
    }
    return { entity, tracks };
}

function listenerSpotifyEmbed(details) {
    readFilteredBody(details, "SPOTIFY", "embed doc filter", async (html, bytes) => {
        const { entity, tracks } = extractSpotifyEmbedTracks(html);
        log("SPOTIFY", `embed doc: ${bytes} bytes`, {
            url: details.url.slice(0, 100),
            type: entity?.type,
            name: entity?.name,
            tracks: tracks.length
        });
        if (tracks.length === 0) return;

        const embedKey = "spotify:" + details.url;
        if (processedEmbeds.has(embedKey)) return;
        processedEmbeds.add(embedKey);
        setTimeout(() => processedEmbeds.delete(embedKey), 30000);

        const tabId = await resolveTabId(details);
        // The playlist/album/show cover is the natural fallback thumbnail; a
        // single-track embed carries the album art on the entity itself.
        const fallbackImg = coverFromSources(entity?.coverArt);
        // Group the whole widget under the embedded entity's own Spotify URL when
        // a per-track URL isn't available (e.g. a bare single-entity embed).
        const fallbackOrigin = spotifyUrlFromUri(entity?.uri)
            || details.documentUrl || details.originUrl || details.url;

        for (const track of tracks) {
            const message = {
                url: track.url,
                type: "media",
                origin: track.origin || fallbackOrigin,
                tabId,
                request: details.requestId,
                name: track.title || entity?.name || undefined,
                // The artist is the "series"/subtitle equivalent shown under the
                // title (matches NOA using the publisher name as description).
                description: track.artist || undefined,
                img: track.cover || fallbackImg || undefined
            };
            for (const k of Object.keys(message)) {
                if (message[k] === undefined) delete message[k];
            }
            sendNative(message);
        }
        log("SPOTIFY", `emitted ${tracks.length} preview(s)`, { tabId });
    });
    return {};
}

// Match the embed document as a sub_frame (the embedded widget — the common
// case) and main_frame (the embed URL opened directly).
browser.webRequest.onBeforeRequest.addListener(
    listenerSpotifyEmbed,
    { urls: SPOTIFY_EMBED_PATTERNS, types: ["sub_frame", "main_frame"] },
    ["blocking"]
);
