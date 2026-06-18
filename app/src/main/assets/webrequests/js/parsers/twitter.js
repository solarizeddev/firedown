// Twitter / X parser — split verbatim out of the former parser-background.js.
import { log, sendVariants, sendSubtitles, urlToTabCache, cacheTabUrl, readFilteredJson, readFilteredBody, enumerateMasterNative } from './common.js';

// ============================================================================
// Twitter / X
// ============================================================================

// GraphQL operation STEMS, matched as substrings of the request URL (the op
// name is in the path: /graphql/<hash>/<OpName>). Stems, not exact names, so a
// minor X rename (HomeTimeline -> HomeTimeline_v2, TweetDetail -> TweetDetailV2)
// still gets read — operation-name churn is one of X's regular breakages, and a
// listener that never reads the response can't extract from it. The classifier
// (collectTweetResults / extractTweetCapture) self-filters: a matched op that
// turns out to carry no tweet media emits nothing, so a loose match only costs a
// body read, never a bad capture.
//
// "tweet" = resolves to ONE focal tweet (open-tweet / embed) — we pick the focal
// one, not thread replies. "timeline" = many tweets (feed / profile / search /
// list / bookmarks / likes) — we emit every video tweet so scrolling surfaces
// them without opening each. The "Timeline" stem alone covers Home/HomeLatest/
// SearchTimeline/ListLatestTweetsTimeline/CommunityTweetsTimeline.
const TWITTER_TWEET_QUERY_STEMS = ["TweetResultByRestId", "TweetDetail"];
const TWITTER_TIMELINE_QUERY_STEMS = [
    "Timeline", "UserTweets", "UserMedia",
    "Bookmarks", "Likes", "TweetActivity", "ImmersiveMedia"
];

function twitterQueryKind(url) {
    if (TWITTER_TWEET_QUERY_STEMS.some(q => url.includes(q))) return "tweet";
    if (TWITTER_TIMELINE_QUERY_STEMS.some(q => url.includes(q))) return "timeline";
    return null;
}

function extractScreenNameFromUrl(details) {
    const urls = [details.originUrl, details.url, details.documentUrl].filter(Boolean);
    for (const url of urls) {
        const match = url.match(/x\.com\/([A-Za-z0-9_]+)\/status\//);
        if (match?.[1] && match[1] !== "i") return match[1];
    }
    for (const [url] of urlToTabCache) {
        const match = url.match(/x\.com\/([A-Za-z0-9_]+)\/status\//);
        if (match?.[1] && match[1] !== "i") return match[1];
    }
    return null;
}

// Unwrap a tweet_results.result that may be a TweetWithVisibilityResults
// wrapper (its real tweet lives under .tweet) or a raw Tweet.
function unwrapTweet(result) {
    return result?.tweet || result;
}

// Collect EVERY tweet_results.result reachable in a GraphQL response — works
// across all the timeline/conversation shapes (TweetDetail's
// threaded_conversation_with_injections_v2, HomeTimeline's
// home.home_timeline_urt, UserTweets' user.result.timeline_v2, search, etc.)
// by recursively scanning for the tweet_results.result key rather than hard-
// coding each instruction path. Deduped by rest_id.
function collectTweetResults(node, out, seen) {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
        for (const v of node) collectTweetResults(v, out, seen);
        return;
    }
    const r = node.tweet_results && node.tweet_results.result;
    if (r) {
        const t = unwrapTweet(r);
        const id = t?.rest_id || t?.legacy?.id_str;
        if (id && !seen.has(id)) { seen.add(id); out.push(r); }
    }
    for (const k in node) {
        // tweet_results handled above; recurse the rest.
        if (k === "tweet_results") continue;
        collectTweetResults(node[k], out, seen);
    }
}

// Pick the single focal tweet for TweetResultByRestId / TweetDetail. Prefers
// the focalTweetId from the request variables so a reply's video in the thread
// isn't grabbed instead of the tweet the user opened.
function extractTwitterFocalResult(parsed, details) {
    const direct = parsed.data?.tweetResult?.result || parsed.data?.tweet?.result;
    if (direct) return direct;

    let focalTweetId = null;
    try {
        const m = details.url.match(/[?&]variables=([^&]+)/);
        if (m) focalTweetId = JSON.parse(decodeURIComponent(m[1])).focalTweetId || null;
    } catch (e) { /* malformed variables — fall through to heuristics */ }

    const candidates = [];
    collectTweetResults(parsed.data, candidates, new Set());
    if (candidates.length === 0) return null;

    if (focalTweetId) {
        const focal = candidates.find(r => {
            const t = unwrapTweet(r);
            return (t?.rest_id || t?.legacy?.id_str) === focalTweetId;
        });
        if (focal) return focal;
    }
    // No focalTweetId match: prefer the first candidate that actually has video
    // (shape-tolerant — findTweetMedia covers extended_entities/media_entities2).
    const withVideo = candidates.find(r => {
        const media = findTweetMedia(unwrapTweet(r));
        return media.some(med => med.video_info?.variants);
    });
    return withVideo || candidates[0];
}

// Scan a Twitter media object for subtitle / closed-caption track URLs.
// Twitter doesn't expose these consistently — modern uploaded videos often
// burn captions into the pixels, and auto-generated captions load via a
// separate player request that the generic webrequest interceptor catches
// passively when the user plays the video. This scanner picks up the cases
// where the GraphQL response DOES include track metadata, so the captions
// are discoverable even if the user never hits play.
//
// Known/observed locations (defensive — none are guaranteed):
//   m.video_info.subtitles[]
//   m.closed_captions[]
//   m.additional_media_info.subtitles[]
// Entry shape is normalised to { url, language, label }.
function extractTwitterSubtitles(m) {
    const candidates = [];
    const push = (arr) => { if (Array.isArray(arr)) candidates.push(...arr); };
    push(m?.video_info?.subtitles);
    push(m?.closed_captions);
    push(m?.additional_media_info?.subtitles);

    const out = [];
    for (const c of candidates) {
        if (!c) continue;
        const url = c.url || c.uri || c.src || c.captions_url;
        if (!url || !/^https?:/i.test(url)) continue;
        const language = c.language || c.lang || c.locale || c.bcp47 || null;
        const label = c.display_name || c.label || c.name || null;
        out.push({ url, language, label });
    }
    return out;
}

// ---------------------------------------------------------------------------
// Shared: twimg media-id + the rich-capture dedup set (used by the wire-master
// fallback in Layer 3 below). EVERY twimg video/thumb URL — progressive mp4,
// HLS master, .m4s segment, poster image — embeds the SAME media-id:
//   video.twimg.com/{amplify_video,ext_tw_video,tweet_video}/<id>/...
//   pbs.twimg.com/{amplify_video_thumb,ext_tw_video_thumb,tweet_video_thumb}/<id>/...
// so it links the player's wire requests to the rich parser's capture with no
// dependence on page-state shape. (ext_tw_video carries an extra /pu/ segment;
// the id is still the first numeric path component.)
const TWIMG_MEDIA_ID_RE = /\/(?:amplify_video|ext_tw_video|tweet_video)(?:_thumb)?\/(\d+)\//;
function twimgMediaId(url) {
    const m = TWIMG_MEDIA_ID_RE.exec(url || "");
    return m ? m[1] : null;
}

// Media-ids the RICH parser (GraphQL/SSR) emitted, so the wire-master fallback
// can skip a media already richly captured (no progressive-vs-HLS duplicate).
// Insertion-ordered FIFO trim — capture is recent-biased.
const twitterRichCaptured = new Set();
const TWITTER_RICH_CAPTURED_MAX = 512;
function markRichCaptured(id) {
    if (!id) return;
    if (twitterRichCaptured.size >= TWITTER_RICH_CAPTURED_MAX) {
        twitterRichCaptured.delete(twitterRichCaptured.values().next().value);
    }
    twitterRichCaptured.add(id);
}

// ---------------------------------------------------------------------------
// Layer 1 — shape-tolerant extraction. X churns its field LAYOUT (the break that
// started this: legacy.extended_entities.media -> media_entities2; full_text ->
// details.full_text; user fields legacy.* -> core.*), AND the two live surfaces
// currently DISAGREE (the HomeTimeline XHR still uses extended_entities while the
// SSR single-tweet uses media_entities2). So media/metadata are read by SHAPE,
// from a list of known locations with a bounded shape-scan fallback, rather than
// one hardcoded path — one extractor serves the GraphQL tweet_results.result AND
// the resolved SSR Tweet, and survives the next rename.

// Tweet fields that are THEMSELVES other tweets — never descend into them in the
// shape scan (they're separate entities, captured on their own; descending would
// attribute a quoted/reply video to the wrong tweet).
const TWEET_NESTED_TWEET_KEYS = new Set([
    "quoted_tweet_results", "quoted_status_result", "retweeted_status_result",
    "tweet_results", "reply_to_results", "reply_to_user_results"
]);

// Bounded DFS for the first array whose elements look like media entities (carry
// video_info.variants). Depth-capped, skips nested-tweet keys. The fallback when
// none of the known media keys match — so a future key rename still captures.
function scanForMediaArray(node, depth) {
    if (depth > 4 || !node || typeof node !== "object") return null;
    if (Array.isArray(node)) {
        if (node.some(it => it && typeof it === "object" && it.video_info?.variants)) return node;
        for (const v of node) {
            const r = scanForMediaArray(v, depth + 1);
            if (r) return r;
        }
        return null;
    }
    for (const k in node) {
        if (TWEET_NESTED_TWEET_KEYS.has(k)) continue;
        const r = scanForMediaArray(node[k], depth + 1);
        if (r) return r;
    }
    return null;
}

// The tweet's own media array, regardless of layout. Known locations first
// (newest -> oldest), then the shape scan.
function findTweetMedia(tweet) {
    if (!tweet || typeof tweet !== "object") return [];
    const direct = tweet.media_entities2
        || tweet.legacy?.extended_entities?.media
        || tweet.extended_entities?.media;
    if (Array.isArray(direct) && direct.length) return direct;
    return scanForMediaArray(tweet, 0) || [];
}

function tweetScreenName(tweet, result, details) {
    const userResult = tweet.core?.user_results?.result
        || result?.core?.user_results?.result;
    return userResult?.core?.screen_name
        || userResult?.legacy?.screen_name
        || extractScreenNameFromUrl(details)
        || "unknown";
}

function tweetText(tweet) {
    return tweet.legacy?.full_text
        || tweet.details?.full_text
        || tweet.note_tweet?.note_tweet_results?.result?.text
        || "";
}

function tweetThumbnail(tweet, media) {
    const img = media?.[0]?.media_url_https;
    if (img) return img;
    const bindings = tweet.card?.legacy?.binding_values;
    const keys = ["thumbnail_image_original", "player_image_large", "player_image",
                  "summary_photo_image_original", "thumbnail_image"];
    for (const key of keys) {
        const url = bindings?.find(b => b.key === key)?.value?.image_value?.url;
        if (url) return url;
    }
    return undefined;
}

// Normalise one tweet (GraphQL tweet_results.result OR a resolved SSR Tweet) into
// the common capture context emitTweetMedia consumes. Returns null when there is
// no video media. Exported for the HAR-replay smoke test.
function extractTweetCapture(result, details) {
    const tweet = unwrapTweet(result);
    if (!tweet || typeof tweet !== "object") return null;
    const media = findTweetMedia(tweet);
    if (!media.length || !media.some(m => m.video_info?.variants)) return null;
    return {
        screenName: tweetScreenName(tweet, result, details),
        tweetId: tweet.rest_id || tweet.legacy?.id_str,
        text: tweetText(tweet),
        imageUrl: tweetThumbnail(tweet, media),
        media
    };
}

// Shared emit core: turn a normalised capture context — media array plus author
// / text / thumbnail — into download variants and send them. Returns true if at
// least one video was emitted.
function emitTweetMedia(details, { screenName, tweetId, text, imageUrl, media }) {
    if (!media || media.length === 0) return false;
    const originUrl = (screenName && screenName !== "unknown")
        ? `https://x.com/${screenName}/status/${tweetId}`
        : `https://x.com/i/status/${tweetId}`;

    let emitted = false;
    for (const m of media) {
        if (!m.video_info?.variants) continue;
        const variants = m.video_info.variants
            .filter(v => v.content_type === "video/mp4")
            .map(v => {
                const wh = v.url.match(/\/(\d+)x(\d+)\//);
                // Twitter progressive mp4s are always H.264/AAC. Supplying the
                // codecs here lets the Java side trust the parser metadata and
                // skip the capture-time ffmpeg probe (the variant already carries
                // url + resolution, and duration comes from duration_millis). A
                // single progressive .mp4 has no separate audio, so
                // VariantProcessor keeps it on the byte-exact HttpDownloadStrategy
                // (type FILE), not an ffmpeg remux.
                return {
                    url: v.url,
                    width: wh ? parseInt(wh[1]) : 0,
                    height: wh ? parseInt(wh[2]) : 0,
                    bitrate: v.bitrate || 0,
                    videoCodec: "h264",
                    audioCodec: "aac"
                };
            });
        if (variants.length === 0) continue;
        emitted = true;
        // Do NOT hardcode skipProbe. Let sendVariants auto-enable it when
        // duration > 0 (the normal case — duration_millis is present, so the
        // probe is skipped exactly as before). When duration_millis is absent or
        // 0 (animated-GIF-as-video, some embed/SPA response shapes), leaving
        // skipProbe off lets the capture-time ffmpeg probe backfill the real
        // duration — otherwise Twitter would uniquely emit NO duration tag while
        // every other progressive parser (Instagram/Threads/Facebook) recovers it.
        sendVariants(details, {
            variants,
            origin: originUrl,
            description: text || "",
            img: imageUrl,
            name: screenName || "unknown",
            duration: m.video_info.duration_millis || 0
        });

        const subtitles = extractTwitterSubtitles(m);
        if (subtitles.length > 0) {
            log("TWITTER", `found ${subtitles.length} subtitle track(s)`, { origin: originUrl });
            sendSubtitles(details, { subtitles, origin: originUrl });
        }
    }
    return emitted;
}

// Normalise one tweet (GraphQL or SSR shape) and emit. Returns true if at least
// one video was emitted. Records the media-ids it captured so the wire-master
// fallback (Layer 3) knows not to re-capture them as a duplicate HLS entity.
function emitTwitterTweetVideos(details, result) {
    const ctx = extractTweetCapture(result, details);
    if (!ctx) return false;
    for (const m of ctx.media) {
        if (!Array.isArray(m.video_info?.variants)) continue;
        for (const v of m.video_info.variants) markRichCaptured(twimgMediaId(v.url));
    }
    return emitTweetMedia(details, ctx);
}

// Process one already-parsed GraphQL response, branching on query kind.
function processTwitterResponse(details, kind, parsed) {
    if (kind === "timeline") {
        // Feed / profile / search: emit every video tweet in the timeline.
        const results = [];
        collectTweetResults(parsed.data, results, new Set());
        let withVideo = 0;
        for (const r of results) {
            if (emitTwitterTweetVideos(details, r)) withVideo++;
        }
        log("TWITTER", `timeline: ${withVideo}/${results.length} tweet(s) with video`);
        return;
    }
    // Single-tweet view (TweetResultByRestId / TweetDetail).
    const rawResult = extractTwitterFocalResult(parsed, details);
    if (!rawResult) {
        log("TWITTER", "no focal result", {
            topKeys: Object.keys(parsed.data || {}),
            errors: parsed.errors ? parsed.errors.map(e => e.message) : null
        });
        return;
    }
    const ok = emitTwitterTweetVideos(details, rawResult);
    log("TWITTER", ok ? "focal: video emitted" : "focal: no video in tweet");
}

// Logged-out / embeds use api.x.com/graphql/; signed-in TweetDetail and the
// timelines use x.com/i/api/graphql/. Match both hosts + legacy twitter.com.
const TWITTER_GRAPHQL_URLS = [
    "*://api.x.com/graphql/*",
    "*://x.com/i/api/graphql/*",
    "*://api.twitter.com/graphql/*",
    "*://twitter.com/i/api/graphql/*"
];

// Read the GraphQL response INLINE as it streams through, the same way the
// Instagram / Vimeo paths do (collectFilteredResponse). This replaces the old
// re-fetch-the-request approach, which: doubled every request (risking x.com's
// rate limit), had to copy auth/CSRF headers, and broke on POST timelines
// because a GET replay dropped the body (the "VPN" bug). filterResponseData
// taps the user's own authenticated response, so method/body/auth are never
// our concern and there are zero extra requests.
function listenerTwitterGraphql(details) {
    const kind = twitterQueryKind(details.url);
    if (!kind) return {};

    readFilteredJson(details, "TWITTER", kind, (parsed) => {
        processTwitterResponse(details, kind, parsed);
    });

    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitterGraphql,
    { urls: TWITTER_GRAPHQL_URLS, types: ["xmlhttprequest"] },
    ["blocking"]
);

// ============================================================================
// X streaming-SSR document path (no GraphQL XHR)
// ============================================================================
// The single-tweet page (x.com/<user>/status/<id>) no longer fires a GraphQL
// XHR (TweetResultByRestId / TweetDetail): X server-side-renders the tweet into
// the main_frame DOCUMENT, inside <script class="$tsr">, as a normalised Relay
// store. Confirmed from a HAR — the document carried video_info / media_entities2
// while the only graphql requests were the login user_flow.json. Because
// video.twimg.com is parser-block-listed, a miss here loses the video ENTIRELY
// (the generic catcher can't grab it either), so this is a second capture path
// beside the GraphQL listener above — exactly like Threads' doc filter beside
// its API filter, and Bluesky's two hls paths. Both emit the same origin
// (x.com/<screen>/status/<id>), so the per-origin dedup (alreadySent) collapses
// them when both fire on one tweet.
//
// The store is serialized as a JS OBJECT LITERAL, not JSON, so JSON.parse and
// the GraphQL walkers (collectTweetResults — tweet_results.result /
// legacy.extended_entities) don't apply. Quirks of the format, all handled by
// parseRelayValue:
//   - unquoted identifier object keys (quoted keys still appear — the
//     "client:..." record ids, and the tweet_result_by_rest_id(...) field key);
//   - `void 0` for undefined and the minified booleans `!0`/`!1`;
//   - a `$R[n]=value` / `$R[n]` capture+back-reference scheme that dedups
//     repeated VALUES within ONE serialization (distinct from record links).
// Records are a FLAT map (the relayRecords object) keyed by id; they link to one
// another by string `__ref` (one id) / `__refs` (id array). Those links are
// resolved against the flat map, NOT via $R. The new SSR field layout differs
// from the GraphQL `legacy.*` shape: a tweet's media is `media_entities2[]`, its
// text is `details.full_text`, and the author is
// `core.user_results.result.core.screen_name` (the same `core` location the
// GraphQL path already reads). Per-media-item shape (video_info.variants
// {content_type,url,bitrate}, media_url_https) is identical, so emitTweetMedia
// is shared.

// Recursive-descent parser for the restricted grammar above. Returns the value
// starting at `state.i`, advancing the index. `state.R` is the per-parse $R
// capture table (numeric id -> already-parsed value). Bounded by the input
// length (the document is a single bounded response body).
function parseRelayValue(src, state) {
    skipWs(src, state);
    // $R[n]  (back-reference)  or  $R[n]=<value>  (capture).
    if (src.startsWith("$R[", state.i)) {
        const close = src.indexOf("]", state.i);
        const n = src.slice(state.i + 3, close);
        state.i = close + 1;
        skipWs(src, state);
        if (src[state.i] === "=") {
            state.i++;
            const v = parseRelayValue(src, state);
            state.R[n] = v;
            return v;
        }
        return state.R[n]; // may be undefined if it points outside relayRecords
    }
    const c = src[state.i];
    if (c === "{") return parseRelayObject(src, state);
    if (c === "[") return parseRelayArray(src, state);
    if (c === '"') return parseRelayString(src, state);
    if (src.startsWith("!0", state.i)) { state.i += 2; return true; }   // minified true
    if (src.startsWith("!1", state.i)) { state.i += 2; return false; }  // minified false
    if (src.startsWith("void 0", state.i)) { state.i += 6; return undefined; }
    if (src.startsWith("true", state.i)) { state.i += 4; return true; }
    if (src.startsWith("false", state.i)) { state.i += 5; return false; }
    if (src.startsWith("null", state.i)) { state.i += 4; return null; }
    return parseRelayNumber(src, state);
}

function skipWs(src, state) {
    while (state.i < src.length) {
        const c = src[state.i];
        if (c === " " || c === "\n" || c === "\t" || c === "\r") state.i++;
        else break;
    }
}

function parseRelayObject(src, state) {
    const o = {};
    state.i++; // {
    skipWs(src, state);
    if (src[state.i] === "}") { state.i++; return o; }
    while (state.i < src.length) {
        skipWs(src, state);
        let key;
        if (src[state.i] === '"') {
            key = parseRelayString(src, state);
        } else {
            let j = state.i;
            while (j < src.length && src[j] !== ":" && !/\s/.test(src[j])) j++;
            key = src.slice(state.i, j);
            state.i = j;
        }
        skipWs(src, state);
        state.i++; // :
        o[key] = parseRelayValue(src, state);
        skipWs(src, state);
        if (src[state.i] === ",") { state.i++; continue; }
        if (src[state.i] === "}") { state.i++; break; }
        break; // unexpected token — stop this object (caller decides)
    }
    return o;
}

function parseRelayArray(src, state) {
    const a = [];
    state.i++; // [
    skipWs(src, state);
    if (src[state.i] === "]") { state.i++; return a; }
    while (state.i < src.length) {
        a.push(parseRelayValue(src, state));
        skipWs(src, state);
        if (src[state.i] === ",") { state.i++; skipWs(src, state); continue; }
        if (src[state.i] === "]") { state.i++; break; }
        break;
    }
    return a;
}

function parseRelayString(src, state) {
    state.i++; // opening "
    let s = "";
    while (state.i < src.length) {
        const c = src[state.i];
        if (c === "\\") {
            const n = src[state.i + 1];
            if (n === "n") s += "\n";
            else if (n === "t") s += "\t";
            else if (n === "r") s += "\r";
            else if (n === "u") { s += String.fromCharCode(parseInt(src.slice(state.i + 2, state.i + 6), 16)); state.i += 4; }
            else s += n; // \" \\ \/ etc. -> the literal char
            state.i += 2;
            continue;
        }
        if (c === '"') { state.i++; break; }
        s += c;
        state.i++;
    }
    return s;
}

function parseRelayNumber(src, state) {
    let j = state.i;
    while (j < src.length && /[-+0-9.eEnx]/.test(src[j])) j++;
    const t = src.slice(state.i, j);
    state.i = j;
    if (t.endsWith("n")) return Number(t.slice(0, -1)); // BigInt literal
    return Number(t);
}

// Pull every <script class="$tsr"> body out of the document. SSR is streamed,
// so there can be more than one; we parse relayRecords from each that has it
// and merge into one flat map (later scripts add/override).
function extractTsrScripts(html) {
    const out = [];
    const re = /<script[^>]*\bclass="\$tsr"[^>]*>/g;
    let m;
    while ((m = re.exec(html)) !== null) {
        const start = m.index + m[0].length;
        const end = html.indexOf("</script>", start);
        if (end > start) out.push(html.slice(start, end));
    }
    return out;
}

// Parse the flat relayRecords map (id -> record) out of the document's $tsr
// scripts. Merged across scripts. Returns {} when none present.
function parseTwitterSsrRecords(html) {
    const records = {};
    for (const script of extractTsrScripts(html)) {
        const at = script.indexOf("relayRecords:");
        if (at === -1) continue;
        const state = { i: at + "relayRecords:".length, R: {} };
        let map;
        try { map = parseRelayValue(script, state); }
        catch (e) { continue; }
        if (map && typeof map === "object") {
            for (const id in map) records[id] = map[id];
        }
    }
    return records;
}

// Resolve a record's __ref / __refs links against the flat store, recursively
// inlining the referenced records. Cycle-guarded (a record can't appear twice on
// one branch) and depth-capped.
function makeRelayResolver(records) {
    function resolve(node, depth, seen) {
        if (depth > 40 || node === null || typeof node !== "object") return node;
        if (Array.isArray(node)) return node.map(v => resolve(v, depth + 1, seen));
        if (typeof node.__ref === "string") {
            if (seen.has(node.__ref)) return null;
            const t = records[node.__ref];
            return t ? resolve(t, depth + 1, new Set(seen).add(node.__ref)) : null;
        }
        if (Array.isArray(node.__refs)) {
            return node.__refs.map(id => {
                if (seen.has(id)) return null;
                const t = records[id];
                return t ? resolve(t, depth + 1, new Set(seen).add(id)) : null;
            });
        }
        const o = {};
        for (const k in node) o[k] = resolve(node[k], depth + 1, seen);
        return o;
    }
    return (node) => resolve(node, 0, new Set());
}

// Follow result / tweet wrappers (TweetResults.result, TweetWithVisibilityResults
// .tweet) down to the underlying Tweet record. Returns null if there isn't one.
function unwrapSsrTweet(node) {
    let n = node;
    let hops = 0;
    while (n && typeof n === "object" && n.__typename !== "Tweet" && hops < 6) {
        if (n.result) n = n.result;
        else if (n.tweet) n = n.tweet;
        else break;
        hops++;
    }
    return (n && typeof n === "object" && n.__typename === "Tweet") ? n : null;
}

// Resolve the focal tweet (and a quoted tweet, if present) from the SSR store and
// return the RESOLVED Tweet records — fed through the same shape-tolerant
// extractTweetCapture/emitTwitterTweetVideos as the GraphQL path. Single-tweet
// pages SSR only the focal tweet's conversation root here; replies load later as
// XHRs (the GraphQL listener), so this stays focal-only — it does NOT scan the
// whole store, which would grab reply videos.
function collectTwitterSsrTweets(html, details) {
    const records = parseTwitterSsrRecords(html);
    const root = records["client:root"];
    if (!root) return [];
    const focalKey = Object.keys(root).find(k => k.startsWith("tweet_result_by_rest_id"));
    if (!focalKey) return [];

    const resolve = makeRelayResolver(records);
    const focal = unwrapSsrTweet(resolve(root[focalKey]));
    if (!focal) return [];

    const out = [];
    const seenIds = new Set();
    const add = (tweet) => {
        if (!tweet || seenIds.has(tweet.rest_id)) return;
        seenIds.add(tweet.rest_id);
        out.push(tweet);
    };
    add(focal);
    add(unwrapSsrTweet(focal.quoted_tweet_results)); // already resolved by resolve()
    return out;
}

function processTwitterDocument(details, html) {
    // Cheap gate: the store marker is absent on non-tweet documents and on the
    // ad/tracker iframes this never matches anyway (main_frame only).
    if (!html || html.indexOf("relayRecords") === -1) return;
    let tweets;
    try {
        tweets = collectTwitterSsrTweets(html, details);
    } catch (e) {
        log("TWITTER", "ssr doc parse threw", { error: e.message });
        return;
    }
    let withVideo = 0;
    for (const t of tweets) {
        if (emitTwitterTweetVideos(details, t)) withVideo++;
    }
    log("TWITTER", `ssr doc: ${withVideo}/${tweets.length} tweet(s) with video`);
}

// Read the main_frame document RESPONSE (not the DOM — the SSR <script> can be
// consumed/replaced during hydration, the Threads lesson) via filterResponseData.
function listenerTwitterDocument(details) {
    if (details.type !== "main_frame") return {};
    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);
    readFilteredBody(details, "TWITTER", "ssr doc", (html) => {
        processTwitterDocument(details, html);
    });
    return {};
}

// Match the single-tweet page on both hosts. main_frame only.
const TWITTER_PAGE_URLS = [
    "*://x.com/*/status/*",
    "*://twitter.com/*/status/*"
];

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitterDocument,
    { urls: TWITTER_PAGE_URLS, types: ["main_frame"] },
    ["blocking"]
);

// ============================================================================
// Layer 3 — wire-master backbone (the reliability floor, page-agnostic)
// ============================================================================
// The rich parsers above (GraphQL + SSR) give full metadata but key off X's
// page-state SHAPE, so a deep rewrite blinds them — and because video.twimg.com
// is parser-block-listed, the generic catcher can't pick up the slack, so the
// video would be lost ENTIRELY. This layer is the floor: it captures the HLS
// MASTER the player fetches whenever a video plays/autoplays — a request keyed
// off the invariant URL format, not page state, so it survives any SSR/GraphQL
// change. Same pattern as Bluesky's listenerBskyMaster.
//
// It is the FALLBACK, not a second capture: it skips any media-id the rich
// parser already emitted (markRichCaptured), so when the rich path works there is
// no progressive-vs-HLS duplicate. When the rich path is stale it still captures
// the played video as HLS, enriched with a real thumbnail from twitterThumbCache
// (the poster the page fetched, see below) and the author from the page URL —
// generic title only.

// media-id -> poster URL, observed from the page's OWN poster fetches on the
// wire. The poster (<video poster>) is fetched before/at play, so it's cached by
// the time the master fires. Independent of the rich parser, so it gives the
// fallback a thumbnail even when page-state parsing is fully broken.
const twitterThumbCache = new Map();
const TWITTER_THUMB_CACHE_MAX = 512;
function listenerTwitterThumb(details) {
    const id = twimgMediaId(details.url);
    if (id) {
        if (twitterThumbCache.size >= TWITTER_THUMB_CACHE_MAX) {
            twitterThumbCache.delete(twitterThumbCache.keys().next().value);
        }
        twitterThumbCache.set(id, details.url.split(/[?#]/)[0]);
    }
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitterThumb,
    { urls: ["*://pbs.twimg.com/*_video_thumb/*"], types: ["image", "imageset", "xmlhttprequest", "object", "other"] },
    []
);

// The HLS master is /pl/<hash>.m3u8 (one segment after /pl/); the per-quality
// CHILD playlists are /pl/avc1/... and /pl/mp4a/... (a subdir after /pl/), so
// `[^/]+\.m3u8` matches the master only and never a child.
const TWITTER_MASTER_RE = /\/pl\/[^/]+\.m3u8(?:[?#]|$)/;
function isTwitterMasterUrl(url) {
    return TWITTER_MASTER_RE.test(url || "");
}

function listenerTwitterMaster(details) {
    if (details.tabId < 0) return {};                 // page player only, not our own probe
    if (!isTwitterMasterUrl(details.url)) return {};   // master, not a child playlist
    const id = twimgMediaId(details.url);
    if (id && twitterRichCaptured.has(id)) return {};  // rich parser already captured -> no dup

    const stripped = details.url.split(/[?#]/)[0];     // stable per-media dedup key (tag query rotates)
    const screenName = extractScreenNameFromUrl(details);
    log("TWITTER", "wire-master fallback", { id, url: stripped.slice(0, 90) });
    enumerateMasterNative(details, {
        url: details.url,
        origin: stripped,
        name: screenName || "X video",
        description: "x.com",
        img: id ? twitterThumbCache.get(id) : undefined,
        requestHeaders: [{ name: "Referer", value: "https://x.com/" }],
    });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitterMaster,
    { urls: ["*://video.twimg.com/*/pl/*"], types: ["xmlhttprequest", "media", "object", "other"] },
    []
);

// Exported for the HAR-replay smoke test (run the REAL extractor against fixtures
// / HAR bytes, not a copy-paste). Not used by index.js.
export {
    collectTweetResults, extractTweetCapture,
    parseTwitterSsrRecords, collectTwitterSsrTweets,
    twimgMediaId, isTwitterMasterUrl,
};

