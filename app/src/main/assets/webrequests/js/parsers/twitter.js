// Twitter / X parser — split verbatim out of the former parser-background.js.
import { log, sendVariants, sendSubtitles, cacheTabUrl, readFilteredJson, readFilteredBody, enumerateMasterNative, MetaCache, tabUrls, allTabUrls, tabClaims, tabOfDetails } from './common.js';

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
    // Last resort: the capturing tab's own URLs first, then any tab's.
    const scoped = (typeof details.tabId === "number" && details.tabId >= 0) ? [...tabUrls(details.tabId)].map(([u]) => u) : [];
    for (const url of scoped.concat([...allTabUrls()].map(([u]) => u))) {
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
    const direct = parsed.data?.tweetResult?.result || parsed.data?.tweet?.result
        || parsed.data?.tweet_result_by_rest_id?.result;
    if (direct) return direct;

    let focalTweetId = null;
    try {
        const m = details.url.match(/[?&]variables=([^&]+)/);
        if (m) focalTweetId = JSON.parse(decodeURIComponent(m[1])).focalTweetId || null;
    } catch (e) { /* malformed variables — fall through to heuristics */ }
    // A document request carries no variables: its status URL names the tweet.
    if (!focalTweetId) focalTweetId = details.url?.match(/\/status\/(\d+)/)?.[1] || null;

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

// Media-ids the RICH parser (GraphQL/SSR) emitted IN A TAB, so that tab's
// wire-master fallback can skip a media already richly captured there (no
// progressive-vs-HLS duplicate). Per tab: this was one global set with no TTL,
// so a media captured in one tab was skipped by the backbone in every other
// tab until 512 newer media pushed it out — a tab showing a cached/SPA tweet
// (the backbone's whole reason to exist) captured nothing. No TTL, FIFO cap,
// dropped with the tab.
const TWITTER_RICH_CAPTURED_MAX = 512;
function richCaptured(tabId) {
    return tabClaims(tabId, "tw-rich", Infinity, TWITTER_RICH_CAPTURED_MAX);
}
function markRichCaptured(id, tabId) {
    if (id) richCaptured(tabId).add(id);
}
// The backbone's tab, plus the UNKNOWN_TAB pseudo-tab a rich emit whose
// request carried no tab is marked under (the -1 wildcard convention).
function isRichCaptured(id, tabId) {
    return richCaptured(tabId).has(id) || (tabId !== -1 && richCaptured(-1).has(id));
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
    "tweet_results", "reply_to_results", "reply_to_user_results",
    // the embed endpoint's (cdn.syndication.twimg.com/tweet-result) own names
    "quoted_tweet", "parent"
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

// `details` is null for a tweet shown INSIDE another (a quote, a retweet's
// original, a reply): the page's URL names the tweet the page is about, not
// this one, so its handle would credit the clip to the wrong account.
function tweetScreenName(tweet, result, details) {
    const userResult = tweet.core?.user_results?.result
        || result?.core?.user_results?.result;
    return userResult?.core?.screen_name
        || userResult?.legacy?.screen_name
        || (details ? extractScreenNameFromUrl(details) : null)
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
// no video media. `nested` = the tweet is shown inside another one (see
// tweetScreenName). Exported for the HAR-replay smoke test.
function extractTweetCapture(result, details, nested) {
    const tweet = unwrapTweet(result);
    if (!tweet || typeof tweet !== "object") return null;
    const media = findTweetMedia(tweet);
    if (!media.length || !media.some(m => m.video_info?.variants)) return null;
    return {
        screenName: tweetScreenName(tweet, result, nested ? null : details),
        tweetId: tweet.rest_id || tweet.legacy?.id_str,
        text: tweetText(tweet),
        imageUrl: tweetThumbnail(tweet, media),
        media
    };
}

// A clip's title is the tweet's own words: the t.co links (the media's own
// link and any others) are dropped and the whitespace collapsed. The
// author's handle would title every clip of an account the same; it goes to
// the description.
function tweetTitle(text) {
    return String(text || "").replace(/https?:\/\/t\.co\/\S+/g, " ").replace(/\s+/g, " ").trim();
}

function tweetOriginUrl(screenName, tweetId) {
    return screenName && screenName !== "unknown"
        ? `https://x.com/${screenName}/status/${tweetId}`
        : `https://x.com/i/status/${tweetId}`;
}

// A media's twimg id: the numeric id in its variant URLs (the same id the
// player's master, segments and poster carry, so it is what Layer 3 looks up),
// else the entity's own id_str.
function tweetMediaId(m) {
    const variants = m?.video_info?.variants;
    if (Array.isArray(variants)) {
        for (const v of variants) {
            const id = twimgMediaId(v?.url);
            if (id) return id;
        }
    }
    return m?.id_str || null;
}

// What every tweet seen in ANY response says about its own videos, keyed by
// twimg media id: title, author, poster, duration. Layer 3 reads
// it, so a video no rich layer emitted (a reply the user plays, a tweet shown
// inside another that a layer did not reach, an HLS-only media) is still
// titled by ITS tweet when the player fetches the master. Without it Layer 3
// has only the page URL's handle, or "X video" off x.com. Facts about content,
// so shared across tabs; bounded by count (MetaCache's FIFO cap).
const TWITTER_META_CACHE_MAX = 1024;
const twitterMetaCache = new MetaCache(TWITTER_META_CACHE_MAX);

// Remember a capture context's videos without emitting them. Returns how many.
function rememberTweetMedia(ctx) {
    if (!ctx || !Array.isArray(ctx.media) || !ctx.tweetId) return 0;
    const knownAuthor = !!ctx.screenName && ctx.screenName !== "unknown";
    const title = tweetTitle(ctx.text);
    let n = 0;
    for (const m of ctx.media) {
        if (!m?.video_info?.variants) continue;
        const id = tweetMediaId(m);
        if (!id) continue;
        twitterMetaCache.set(id, {
            name: title || (knownAuthor ? `@${ctx.screenName}` : ""),
            description: knownAuthor ? `@${ctx.screenName}` : "",
            img: m.media_url_https || ctx.imageUrl,
            duration: m.video_info.duration_millis || 0,
        });
        n++;
    }
    return n;
}

// Shared emit core: turn a normalised capture context — media array plus author
// / text / thumbnail — into download variants and send them. Returns true if at
// least one video was emitted.
function emitTweetMedia(details, ctx) {
    const { screenName, tweetId, text, imageUrl, media } = ctx;
    if (!media || media.length === 0) return false;
    rememberTweetMedia(ctx);
    const knownAuthor = !!screenName && screenName !== "unknown";
    const originUrl = tweetOriginUrl(screenName, tweetId);
    const title = tweetTitle(text);

    let emitted = false;
    for (let i = 0; i < media.length; i++) {
        const m = media[i];
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
        // Only a media that actually emits is marked rich-captured: an
        // HLS-only media (no video/mp4 variant) used to be marked here and
        // then skipped by Layer 3 too, so nothing ever captured it.
        const mediaId = twimgMediaId(variants[0].url) || m.id_str || m.media_key || String(i);
        for (const v of variants) markRichCaptured(twimgMediaId(v.url), tabOfDetails(details));
        // Do NOT hardcode skipProbe. Let sendVariants auto-enable it when
        // duration > 0 (the normal case — duration_millis is present, so the
        // probe is skipped exactly as before). When duration_millis is absent or
        // 0 (animated-GIF-as-video, some embed/SPA response shapes), leaving
        // skipProbe off lets the capture-time ffmpeg probe backfill the real
        // duration — otherwise the capture would carry NO duration tag.
        // One dedup key PER MEDIA: every video of a multi-video tweet shares
        // the tweet's origin, and the origin-keyed default dropped videos
        // 2–4 as "already sent" — with their ids already marked rich-captured,
        // so Layer 3 skipped them on play as well, and the clips were lost
        // entirely (video.twimg.com is block-listed). The same media seen
        // again (TweetDetail + the timeline + the SSR document) still
        // collapses: its id is the same in every response.
        sendVariants(details, {
            variants,
            origin: originUrl,
            dedupKey: `${originUrl}#${mediaId}`,
            description: knownAuthor ? `@${screenName}` : "",
            // Each video's own poster: imageUrl is the tweet's FIRST media's.
            img: m.media_url_https || imageUrl,
            name: title || (knownAuthor ? `@${screenName}` : "X video"),
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

// The tweets one GraphQL tweet result puts on screen: a RETWEET shows its
// original (own author, text and id — the wrapper's text is "RT @…" and its id
// is the retweeter's), and a QUOTE shows the quoted tweet inside it, with its
// own video. A layer that read only the outer tweet left the quoted video to
// Layer 3, titled by the page URL's handle (the quoting account) or "X video"
// — the same miss as the embed's reply `parent`. `wrapper` is the retweet to
// fall back to when its original carries no readable video.
function shownTweets(result) {
    const tweet = unwrapTweet(result);
    if (!tweet || typeof tweet !== "object") return [];
    const original = tweet.legacy?.retweeted_status_result?.result
        || tweet.retweeted_status_result?.result;
    const out = original
        ? [{ result: original, nested: true, wrapper: result }]
        : [{ result, nested: false }];
    const main = unwrapTweet(original || result);
    const quoted = main?.quoted_status_result?.result;
    if (quoted) out.push({ result: quoted, nested: true });
    return out;
}

function shownCapture(details, shown) {
    return extractTweetCapture(shown.result, details, shown.nested)
        || (shown.wrapper ? extractTweetCapture(shown.wrapper, details, false) : null);
}

// Emit one tweet result and the tweets it shows (GraphQL or SSR shape).
// Returns true if at least one video was emitted. emitTweetMedia records the
// media ids it captured, so Layer 3 doesn't re-capture them as an HLS entity.
function emitTwitterTweetVideos(details, result) {
    let emitted = false;
    for (const shown of shownTweets(result)) {
        const ctx = shownCapture(details, shown);
        if (ctx && emitTweetMedia(details, ctx)) emitted = true;
    }
    return emitted;
}

// Remember, without emitting, every video of every tweet a response carries
// (replies and a thread's parents on a status page: capturing each before it
// plays would bury the tweet the user opened). Layer 3 titles them from the
// cache if the user plays one. Returns how many videos were remembered.
function rememberTwitterTweetVideos(details, results) {
    let n = 0;
    for (const result of results) {
        for (const shown of shownTweets(result)) {
            n += rememberTweetMedia(shownCapture(details, shown));
        }
    }
    return n;
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
    // Single-tweet view (TweetResultByRestId / TweetDetail): the focal tweet
    // is emitted, the rest of the conversation only remembered for Layer 3.
    const conversation = [];
    collectTweetResults(parsed.data, conversation, new Set());
    const remembered = rememberTwitterTweetVideos(details, conversation);
    if (remembered) log("TWITTER", `conversation: ${remembered} video(s) remembered for the wire fallback`);
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

// Read the GraphQL response INLINE as it streams through
// (collectFilteredResponse). A re-fetch of the request would double every
// request (risking x.com's rate limit), need the auth/CSRF headers, and break
// POST timelines (a GET replay drops the body). filterResponseData
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
// Layer 1b — embedded tweets (cdn.syndication.twimg.com/tweet-result)
// ============================================================================
// A tweet embedded in another site (<blockquote class="twitter-tweet"> +
// platform.twitter.com/widgets.js) is rendered by the embed iframe from ONE
// JSON call per tweet, cdn.syndication.twimg.com/tweet-result?id=<id>, which
// is the whole tweet: text, user, and mediaDetails in the same media-entity
// shape as GraphQL's legacy.extended_entities.media (video_info.variants with
// every mp4 rendition, duration_millis, media_url_https). No GraphQL call is
// made, so without this listener an embedded clip reaches the sheet only
// through the wire-master fallback below, titled "X video". Two other tweets
// ride inside, each
// with its own mediaDetails and each emitted as its own capture: the tweet it
// quotes (quoted_tweet) and, for a reply, the tweet it answers (parent). The
// embed renders a reply BELOW its parent, so the parent's video plays in the
// article too, so it is read as well.

// Normalise one tweet-result object into the capture context emitTweetMedia
// consumes, or null when it holds no video. Exported for the replay test.
function extractSyndicationCapture(tweet, details) {
    if (!tweet || typeof tweet !== "object") return null;
    const media = Array.isArray(tweet.mediaDetails) && tweet.mediaDetails.length
        ? tweet.mediaDetails
        : findTweetMedia(tweet);
    if (!media.some(m => m?.video_info?.variants)) return null;
    return {
        screenName: tweet.user?.screen_name || "unknown",
        tweetId: tweet.id_str,
        text: tweet.text || "",
        imageUrl: tweetThumbnail(tweet, media) || tweet.video?.poster,
        media
    };
}

// The tweets one tweet-result body renders: the embedded tweet, then the
// tweet it quotes and the tweet it answers, each with its own quote and
// parent. Depth-capped, and each tweet once (a parent can quote the tweet the
// reply also quotes). Exported for the replay test.
const SYNDICATION_NESTED_DEPTH = 2;
function syndicationTweets(root) {
    const out = [];
    const seen = new Set();
    const visit = (tweet, depth) => {
        if (!tweet || typeof tweet !== "object" || depth > SYNDICATION_NESTED_DEPTH) return;
        const id = tweet.id_str;
        if (id) {
            if (seen.has(id)) return;
            seen.add(id);
        }
        out.push(tweet);
        visit(tweet.quoted_tweet, depth + 1);
        visit(tweet.parent, depth + 1);
    };
    visit(root, 0);
    return out;
}

function listenerTwitterSyndication(details) {
    readFilteredJson(details, "TWITTER", "embed", (parsed) => {
        let emitted = 0;
        for (const tweet of syndicationTweets(parsed)) {
            const ctx = extractSyndicationCapture(tweet, details);
            if (ctx && emitTweetMedia(details, ctx)) emitted++;
        }
        log("TWITTER", `embed: ${emitted} tweet(s) with video`, { id: parsed?.id_str });
    });
    return {};
}

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitterSyndication,
    { urls: ["*://cdn.syndication.twimg.com/tweet-result*"], types: ["xmlhttprequest"] },
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
// beside the GraphQL listener above. Both emit the same origin
// (x.com/<screen>/status/<id>), so the per-origin dedup (alreadySent) collapses
// them when both fire on one tweet.
//
// TWO SSR shapes exist, and the document parser reads BOTH (HAR 26-09-29 is
// the second; the first is the 2025 store this path was written against):
//   (a) the NORMALISED Relay store — <script class="$tsr"> carrying a flat
//       `relayRecords:{...}` map linked by __ref/__refs, focal tweet reached
//       from "client:root".tweet_result_by_rest_id(...). parseTwitterSsrRecords.
//   (b) the STREAMED router — a <script data-tsr-stream-part> declaring the
//       shared capture table `(self.$R=self.$R||{})["tsr"]` and
//       `$_TSR.router=($R=>$R[0]={...})($R["tsr"])`, whose route-match loader
//       data inlines the RESOLVED GraphQL results (`{kind:"GraphQLRequestStream
//       .Completed", key:"<queryId>{...variables}", result:{value:{data:{
//       tweet_result_by_rest_id:{rest_id, result:<Tweet>}}}}}`), followed by
//       further plain <script>s that push more results into the same table
//       (`($R=>$R[104].next($R[107]={...}))($R["tsr"])` — the TweetDetail
//       conversation: focal + quoted + REPLIES). No __ref anywhere — every
//       link is inline, and `$R[n]` back-references cross SCRIPT boundaries
//       (the table is shared), so the capture table is kept across all tsr
//       scripts of one document, parsed in document order.
//       parseTwitterSsrStreamValues. The loader object ALSO holds live JS
//       (arrow functions building a ReadableStream, `new ...`), which is why
//       parseRelayValue skips an unknown token as a balanced JS expression
//       instead of stopping the enclosing object short.
//   (c) the streamed router with each GraphQL body as a JSON STRING: the
//       same `GraphQLRequestStream.Completed` messages (in the router's
//       dehydrated data and in the plain scripts that follow), but
//       `result:{kind:"Result.Ok",value:"{\"data\":{…}}"}` — the body
//       serialized as a string the key walk of (b) cannot see into (the
//       Instagram Bloks-payload class). collectTwitterSsrBodies parses every
//       such value and processTwitterDocument feeds each through the GraphQL
//       path's processTwitterResponse, so a TweetDetail body emits the focal
//       tweet and REMEMBERS its replies for the wire fallback, and a
//       PROFILE document, whose timeline body lists every tweet, emits every
//       video. Logged out, a profile page fires no GraphQL XHR at all, which
//       is why the document listener reads EVERY x.com main_frame, not only
//       the status page (the marker gate keeps the rest cheap).
//   Shape (b) shipped while (a) still had a test: the smoke fixture mirrored
//   (a), the real document no longer matched `class="$tsr"` / `relayRecords`,
//   the listener parsed nothing and logged "0/0 tweet(s)". Capture survived
//   only because the logged-out page ALSO fired TweetDetail (api.x.com) — on a
//   page that doesn't, the video would have been lost (video.twimg.com is
//   parser-block-listed). Shape (c) shipped the same way: every body a
//   string, "0/0 tweet(s)", and the video reached the sheet only through the
//   wire fallback under the page's handle. All three shapes have a smoke
//   fixture.
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
    if (c === "-" || c === "+" || c === "." || (c >= "0" && c <= "9")) return parseRelayNumber(src, state);
    // Anything else is live JS the streamed router embeds beside the data
    // (an arrow function, `new ReadableStream(...)`, an IIFE). Skip it as one
    // balanced expression so the ENCLOSING object keeps parsing; the value is
    // undefined. Before this, the object ended at the first such value and
    // every later key was lost.
    return skipJsExpression(src, state);
}

// Advance past one JS expression: balanced ()/[]/{} with string literals
// skipped, stopping at a top-level `,` `}` `]` `)` or the end. Bounded by the
// script length. Returns undefined (the value is not data we can use).
function skipJsExpression(src, state) {
    let depth = 0;
    while (state.i < src.length) {
        const c = src[state.i];
        if (c === '"' || c === "'" || c === "`") {
            state.i++;
            while (state.i < src.length && src[state.i] !== c) {
                if (src[state.i] === "\\") state.i++;
                state.i++;
            }
            state.i++;
            continue;
        }
        if (c === "(" || c === "[" || c === "{") depth++;
        else if (c === ")" || c === "]" || c === "}") {
            if (depth === 0) break;
            depth--;
        } else if (c === "," && depth === 0) break;
        state.i++;
    }
    return undefined;
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
    if (j === state.i) return skipJsExpression(src, state); // never stall on an unknown token
    state.i = j;
    if (t.endsWith("n")) return Number(t.slice(0, -1)); // BigInt literal
    return Number(t);
}

// Pull every SSR script body out of the document, in document order: the
// <script class="$tsr"> store scripts (shape a) AND every inline script that
// touches the shared `$R["tsr"]` capture table / `$_TSR` (shape b — the
// data-tsr-stream-part router plus the plain streamed parts that follow it,
// which carry no distinguishing attribute at all). SSR is streamed, so there
// are several; shape (a) merges relayRecords across them, shape (b) needs
// them ALL parsed in order for its cross-script back-references.
function extractTsrScripts(html) {
    const out = [];
    const re = /<script\b[^>]*>/g;
    let m;
    while ((m = re.exec(html)) !== null) {
        const start = m.index + m[0].length;
        const end = html.indexOf("</script>", start);
        if (end <= start) continue;
        const isStore = /\bclass="\$tsr"/.test(m[0]);
        const body = html.slice(start, end);
        if (isStore || body.indexOf('$R["tsr"]') !== -1 || body.indexOf("$_TSR") !== -1) {
            out.push(body);
        }
        re.lastIndex = end;
    }
    return out;
}

// Shape (b): parse every `$R[n]=<value>` capture site across the tsr scripts
// with ONE shared capture table (the page's `$R["tsr"]`), skipping the JS glue
// between values (`($R=>`, `.next(`, `)($R["tsr"])`). Returns the top-level
// values in document order — nested captures are consumed by their parent's
// parse and never re-parsed. Exported for the smoke test.
function parseTwitterSsrStreamValues(html) {
    const values = [];
    const R = {};
    for (const script of extractTsrScripts(html)) {
        if (script.indexOf('$R["tsr"]') === -1 && script.indexOf("$_TSR") === -1) continue;
        const state = { i: 0, R };
        while (state.i < script.length) {
            const at = script.indexOf("$R[", state.i);
            if (at === -1) break;
            state.i = at;
            let v;
            try { v = parseRelayValue(script, state); }
            catch (e) { break; }
            if (v && typeof v === "object") values.push(v);
            if (state.i <= at) state.i = at + 3; // never stall
        }
    }
    return values;
}

// Bounded walk over the streamed values for every `tweet_result_by_rest_id`
// node (the focal tweet's query result — inlined once by the router's
// TweetResultByRestId loader and again by the streamed TweetDetail; both are
// the SAME tweet and dedup on rest_id). Deliberately NOT `tweet_results` — in
// the streamed TweetDetail those are the conversation's REPLIES, and this
// path is focal-only like shape (a).
function collectStreamFocalNodes(values) {
    const out = [];
    const seen = new Set();
    let budget = 60000;
    const walk = (node, depth) => {
        if (budget-- <= 0 || depth > 40 || !node || typeof node !== "object") return;
        if (seen.has(node)) return;
        seen.add(node);
        if (Array.isArray(node)) {
            for (const v of node) walk(v, depth + 1);
            return;
        }
        for (const k in node) {
            if (k === "tweet_result_by_rest_id" && node[k] && typeof node[k] === "object") out.push(node[k]);
            else walk(node[k], depth + 1);
        }
    };
    for (const v of values) walk(v, 0);
    return out;
}

// Shape (c): every `{kind:"Result.Ok", value:"<JSON>"}` in the streamed
// values whose string names a tweet result, parsed as the GraphQL body it
// is. One bounded walk over the values (the router's dehydrated messages and
// the streamed parts alike), a marker test before each parse, a size cap per
// body. Exported for the smoke test.
const TWITTER_SSR_BODY_MAX = 4 * 1024 * 1024;
function collectTwitterSsrBodies(values) {
    const out = [];
    const seen = new Set();
    let budget = 60000;
    const walk = (node, depth) => {
        if (budget-- <= 0 || depth > 40 || !node || typeof node !== "object") return;
        if (seen.has(node)) return;
        seen.add(node);
        if (Array.isArray(node)) {
            for (const v of node) walk(v, depth + 1);
            return;
        }
        if (node.kind === "Result.Ok" && typeof node.value === "string") {
            const s = node.value;
            if (s.length <= TWITTER_SSR_BODY_MAX && s.charCodeAt(0) === 0x7b /* { */ && s.indexOf("tweet_result") !== -1) {
                try {
                    const body = JSON.parse(s);
                    if (body && typeof body === "object" && body.data && typeof body.data === "object") out.push(body);
                } catch (e) { /* not a JSON body */ }
            }
            return;
        }
        for (const k in node) walk(node[k], depth + 1);
    };
    for (const v of values) walk(v, 0);
    return out;
}

// The processTwitterResponse branch a streamed body takes: a single-tweet
// view (the focal result, a conversation) emits the focal tweet only and
// remembers the rest; anything else (a profile's timeline) emits every video.
function twitterBodyKind(body) {
    const d = body.data;
    return (d.tweet_result_by_rest_id || d.tweetResult || d.tweet || d.threaded_conversation_with_injections_v2)
        ? "tweet" : "timeline";
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
function collectTwitterSsrTweets(html, details, values = null) {
    const out = [];
    const seenIds = new Set();
    const add = (tweet) => {
        if (!tweet || seenIds.has(tweet.rest_id)) return;
        seenIds.add(tweet.rest_id);
        out.push(tweet);
    };

    // Shape (a): the normalised store.
    const records = parseTwitterSsrRecords(html);
    const root = records["client:root"];
    const focalKey = root ? Object.keys(root).find(k => k.startsWith("tweet_result_by_rest_id")) : null;
    if (root && focalKey) {
        const resolve = makeRelayResolver(records);
        const focal = unwrapSsrTweet(resolve(root[focalKey]));
        if (focal) {
            add(focal);
            add(unwrapSsrTweet(focal.quoted_tweet_results)); // already resolved by resolve()
            return out;
        }
    }

    // Shape (b): the streamed router — results are already resolved.
    for (const node of collectStreamFocalNodes(values || parseTwitterSsrStreamValues(html))) {
        const focal = unwrapSsrTweet(node);
        if (!focal) continue;
        add(focal);
        add(unwrapSsrTweet(focal.quoted_tweet_results));
    }
    return out;
}

function processTwitterDocument(details, html) {
    // Cheap gate: the store marker is absent on non-tweet documents and on the
    // ad/tracker iframes this never matches anyway (main_frame only).
    if (!html || (html.indexOf("relayRecords") === -1 && html.indexOf('$R["tsr"]') === -1)) return;
    let bodies = [];
    let tweets;
    try {
        const values = html.indexOf('$R["tsr"]') === -1 ? [] : parseTwitterSsrStreamValues(html);
        bodies = collectTwitterSsrBodies(values);
        tweets = collectTwitterSsrTweets(html, details, values);
    } catch (e) {
        log("TWITTER", "ssr doc parse threw", { error: e.message });
        return;
    }
    // Shape (c): each streamed body is the GraphQL response the page would
    // otherwise have fetched, and takes that path (focal emitted, replies
    // remembered; a timeline emitted whole). A tweet both carry collapses on
    // its media id.
    for (const body of bodies) {
        processTwitterResponse(details, twitterBodyKind(body), body);
    }
    let withVideo = 0;
    for (const t of tweets) {
        if (emitTwitterTweetVideos(details, t)) withVideo++;
    }
    log("TWITTER", `ssr doc: ${bodies.length} streamed body(ies), ${withVideo}/${tweets.length} inline tweet(s) with video`);
}

// Read the main_frame document RESPONSE (not the DOM — the SSR <script> can be
// consumed/replaced during hydration) via filterResponseData.
function listenerTwitterDocument(details) {
    if (details.type !== "main_frame") return {};
    if (details.tabId >= 0) cacheTabUrl(details.url, details.tabId);
    readFilteredBody(details, "TWITTER", "ssr doc", (html) => {
        processTwitterDocument(details, html);
    });
    return {};
}

// Every document on both hosts, main_frame only: a logged-out profile page
// renders its whole timeline into the document and fires no GraphQL XHR, so
// a status-only match loses every video on it. The marker gate in
// processTwitterDocument keeps a document with no store cheap.
const TWITTER_DOCUMENT_URLS = [
    "*://x.com/*",
    "*://twitter.com/*"
];

browser.webRequest.onBeforeRequest.addListener(
    listenerTwitterDocument,
    { urls: TWITTER_DOCUMENT_URLS, types: ["main_frame"] },
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
// change.
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
// The URL is kept WHOLE, query included: X's posters are extensionless now
// (pbs.twimg.com/amplify_video_thumb/<id>/img/<key>?format=webp&name=large) and
// the bare path 404s, so the stripped copy this used to keep was a thumbnail
// that could never load (on-device logcat, 2026-10-18). The query is the
// format and size, not a rotating token.
const TWITTER_THUMB_CACHE_MAX = 512;
const twitterThumbCache = new MetaCache(TWITTER_THUMB_CACHE_MAX);
function listenerTwitterThumb(details) {
    const id = twimgMediaId(details.url);
    if (id) twitterThumbCache.set(id, details.url.split("#")[0]);
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
    if (id && isRichCaptured(id, details.tabId)) return {};  // rich parser already captured here -> no dup

    const stripped = details.url.split(/[?#]/)[0];     // stable per-media dedup key (tag query rotates)
    // The tweet this video belongs to, when any response carried it (see
    // twitterMetaCache); the page URL's handle is a last resort, and it is
    // wrong for a quote or a reply on someone else's status page.
    const meta = id ? twitterMetaCache.get(id) : null;
    const screenName = meta ? null : extractScreenNameFromUrl(details);
    log("TWITTER", "wire-master fallback", { id, url: stripped.slice(0, 90), meta: !!meta });
    enumerateMasterNative(details, {
        url: details.url,
        origin: stripped,
        name: meta?.name || screenName || "X video",
        description: meta?.description || "x.com",
        img: (id ? twitterThumbCache.get(id) : undefined) || meta?.img,
        duration: meta?.duration || 0,
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
    collectTweetResults, extractTweetCapture, extractSyndicationCapture, syndicationTweets, tweetTitle,
    shownTweets,
    parseTwitterSsrRecords, parseTwitterSsrStreamValues, collectTwitterSsrTweets, collectTwitterSsrBodies,
    twimgMediaId, isTwitterMasterUrl,
};

