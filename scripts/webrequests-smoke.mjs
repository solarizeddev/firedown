#!/usr/bin/env node
// Smoke test for the webrequests extension background modules.
//
// Imports the whole background module graph (js/parsers/index.js, which pulls
// requests.js / regex.js / parser-blocklist.js / cookies.js / debug.js) under
// a stubbed `browser` API. This verifies, without a device:
//   - every module parses as an ES module (strict mode included),
//   - every `import { x } from ...` has a matching export (ESM link-time
//     check — a missing export aborts the import),
//   - all top-level listener registration runs without throwing,
//   - the listener-registration counts match the expected inventory, so a
//     refactor can't silently drop a webRequest hook,
//   - the message router received every expected kind, and the SPA registry
//     every site handler.
//
// Run from the repo root:  node scripts/webrequests-smoke.mjs
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ext = join(root, "app/src/main/assets/webrequests");

// ---------------------------------------------------------------------------
// browser.* stub — records listener registrations, answers the few calls that
// run at import time (tabs.query in boot.js, sendNativeMessage in debug.js).
// ---------------------------------------------------------------------------
const registrations = {};
function evt(path) {
  return {
    addListener(fn) {
      (registrations[path] ??= []).push(fn);
    },
  };
}

globalThis.browser = {
  runtime: {
    sendNativeMessage: async () => false,
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
    filterResponseData() { throw new Error("filterResponseData must not run at import time"); },
  },
  webNavigation: {
    onHistoryStateUpdated: evt("webNavigation.onHistoryStateUpdated"),
  },
  tabs: {
    onUpdated: evt("tabs.onUpdated"),
    onRemoved: evt("tabs.onRemoved"),
    onActivated: evt("tabs.onActivated"),
    query: async () => [],
    get: async () => ({ incognito: false }),
    sendMessage: async () => {},
  },
  cookies: {
    onChanged: evt("cookies.onChanged"),
    getAll: async () => [],
  },
  storage: {
    local: { get: async () => ({}), set: async () => {}, remove: async () => {} },
  },
};

// The router/SPA registries live in module scope; observe them through the
// recorded runtime.onMessage and tabs.onUpdated listeners instead of widening
// the modules' export surface for tests.
await import(pathToFileURL(join(ext, "js/parsers/index.js")));

// Give boot.js's fire-and-forget handleExistingTabs() a tick to settle.
await new Promise((r) => setTimeout(r, 20));

// ---------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------
let failures = 0;
function expect(cond, label) {
  if (cond) {
    console.log("ok  ", label);
  } else {
    console.error("FAIL", label);
    failures++;
  }
}

const count = (path) => (registrations[path] ?? []).length;

// Inventory of listener registrations across the background module graph
// (js/parsers/* + requests.js + cookies.js + debug.js). Update deliberately
// when adding/removing a listener — that's the point of the check.
expect(count("webRequest.onBeforeRequest") === 35, `webRequest.onBeforeRequest registrations == 35 (got ${count("webRequest.onBeforeRequest")})`);
expect(count("webRequest.onSendHeaders") === 2, `webRequest.onSendHeaders registrations == 2 (got ${count("webRequest.onSendHeaders")})`);
expect(count("webRequest.onHeadersReceived") === 2, `webRequest.onHeadersReceived registrations == 2 (got ${count("webRequest.onHeadersReceived")})`);
expect(count("webRequest.onResponseStarted") === 1, `webRequest.onResponseStarted registrations == 1 (got ${count("webRequest.onResponseStarted")})`);
expect(count("webRequest.onErrorOccurred") === 1, `webRequest.onErrorOccurred registrations == 1 (got ${count("webRequest.onErrorOccurred")})`);
expect(count("webRequest.onCompleted") === 2, `webRequest.onCompleted registrations == 2 (got ${count("webRequest.onCompleted")})`);
expect(count("runtime.onMessage") === 2, `runtime.onMessage listeners == 2 — parsers router + requests.js (got ${count("runtime.onMessage")})`);
expect(count("tabs.onUpdated") === 2, `tabs.onUpdated listeners == 2 — parsers/common + requests.js (got ${count("tabs.onUpdated")})`);
expect(count("webNavigation.onHistoryStateUpdated") === 1, `webNavigation.onHistoryStateUpdated == 1 (got ${count("webNavigation.onHistoryStateUpdated")})`);
expect(count("cookies.onChanged") === 1, `cookies.onChanged == 1 (got ${count("cookies.onChanged")})`);

// Message router: feed each expected kind through the recorded onMessage
// listeners with an empty payload — a registered handler must swallow it
// silently (fire-and-forget, payload-shape bail), an unregistered kind would
// fall through to requests.js. We verify registration indirectly: the router
// throws on DUPLICATE registration, so a successful import already proves
// each kind registered at most once; here we prove the dispatch path doesn't
// throw for every known kind.
const kinds = [
  { kind: "page-state-media", payload: null },
  { kind: "page-state-progressive", payload: null },
  { kind: "page-state-hls", payload: null },
  { kind: "mega-folder", payload: null },
  { kind: "mega-file", payload: null },
  { type: "instagram_intercept", payload: null },
];
for (const msg of kinds) {
  let threw = false;
  for (const listener of registrations["runtime.onMessage"]) {
    try {
      listener(msg, { tab: { id: 1, url: "https://example.com/" } });
    } catch (e) {
      threw = true;
      console.error("  dispatch threw for", JSON.stringify(msg), e.message);
    }
  }
  expect(!threw, `message dispatch survives kind=${msg.kind ?? msg.type}`);
}

// SPA registry: drive the recorded tabs.onUpdated listeners with site URLs and
// make sure none throw (each site's checkAndProcess handler runs).
const spaUrls = [
  "https://www.instagram.com/reel/ABC123/",
  "https://www.facebook.com/watch?v=1",
  "https://kick.com/somestreamer",
  "https://www.twitch.tv/somestreamer",
  "https://www.dailymotion.com/video/x8abcd",
];
let spaThrew = false;
for (const url of spaUrls) {
  for (const listener of registrations["tabs.onUpdated"]) {
    try {
      listener(1, { url }, { id: 1, url, incognito: false });
    } catch (e) {
      spaThrew = true;
      console.error("  tabs.onUpdated threw for", url, e.message);
    }
  }
}
expect(!spaThrew, "SPA handlers run for all five registered sites");

// ---------------------------------------------------------------------------
// Pure-function checks — the point of the module split: extraction logic is
// importable, so a HAR-replay test can run the REAL code (CLAUDE.md's
// "reproduce the parser's exact algorithm against the HAR bytes" rule)
// instead of a copy-pasted simulation.
// ---------------------------------------------------------------------------
const { parseHlsMaster, decodeHtmlEntities } = await import(
  pathToFileURL(join(ext, "js/parsers/common.js"))
);

const master = [
  "#EXTM3U",
  '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="en",URI="audio/hi.m3u8"',
  '#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,URI="iframe.m3u8"',
  '#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.64002a,mp4a.40.2",AUDIO="aud1"',
  "v1080.m3u8",
  '#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud1"',
  "v720.m3u8",
].join("\n");
const variants = parseHlsMaster(master, "https://cdn.example.com/live/master.m3u8");
expect(variants.length === 2, `parseHlsMaster: 2 variants (got ${variants.length})`);
expect(variants[0]?.height === 1080 && variants[0]?.url === "https://cdn.example.com/live/v1080.m3u8",
  "parseHlsMaster: best-first with resolved URL");
expect(variants[0]?.audioUrl === "https://cdn.example.com/live/audio/hi.m3u8",
  "parseHlsMaster: split audio group resolved");
expect(variants[0]?.videoCodec === "h264" && variants[0]?.audioCodec === "aac",
  "parseHlsMaster: codecs mapped");

expect(decodeHtmlEntities("&#x41c;&amp;&#1052; &hellip;") === "М&М …",
  "decodeHtmlEntities: hex/named/decimal references");

// Twitter/X streaming-SSR extractor — runs the REAL walker (parseTwitterSsrRecords
// / collectTwitterSsrTweets imported from twitter.js) against a serialized-store
// fixture that mirrors X's <script class="$tsr"> Relay format: unquoted keys,
// minified booleans !0/!1, `void 0`, the $R[n]= capture / $R[n] back-reference
// scheme, quoted "client:..." record-id keys, and __ref/__refs record links. This
// is the HAR-replay guard for the bug fixed here (single-tweet pages SSR the video
// into the document and fire no GraphQL XHR), per CLAUDE.md's "run the real code
// against the bytes" rule.
const {
  parseTwitterSsrRecords, collectTwitterSsrTweets,
  collectTweetResults, extractTweetCapture,
  twimgMediaId, isTwitterMasterUrl,
} = await import(
  pathToFileURL(join(ext, "js/parsers/twitter.js"))
);
const ssrFixture = `<html><body><script class="$tsr">($R=>$R[0]={dehydratedData:$R[1]={relayRecords:$R[2]={`
  + `"client:root":$R[3]={__id:"client:root",__typename:"__Root","tweet_result_by_rest_id(rest_id:111)":$R[4]={__ref:"TweetResults:111"}},`
  + `"TweetResults:111":$R[5]={__id:"TweetResults:111",__typename:"TweetResults",rest_id:"111",result:$R[6]={__ref:"Tweet:111"}},`
  + `"Tweet:111":$R[7]={__id:"Tweet:111",__typename:"Tweet",rest_id:"111",conversation_muted:!1,core:$R[8]={__ref:"client:Tweet:111:core"},details:$R[9]={__ref:"client:Tweet:111:details"},media_entities2:$R[10]={__refs:$R[11]=["client:Tweet:111:media:0"]}},`
  + `"client:Tweet:111:core":$R[12]={__id:"client:Tweet:111:core",__typename:"TweetCore",user_results:$R[13]={__ref:"UserResults:9"}},`
  + `"UserResults:9":$R[14]={__id:"UserResults:9",__typename:"UserResults",result:$R[15]={__ref:"User:9"}},`
  + `"User:9":$R[16]={__id:"User:9",__typename:"User",rest_id:"9",core:$R[17]={__id:"client:User:9:core",__typename:"UserCore",screen_name:"jack",name:"Jack"}},`
  + `"client:Tweet:111:details":$R[18]={__id:"client:Tweet:111:details",__typename:"TBirdData",full_text:"hello world",ssr:void 0},`
  + `"client:Tweet:111:media:0":$R[19]={__id:"client:Tweet:111:media:0",__typename:"ApiMedia",media_url_https:"https://pbs.twimg.com/t.jpg",video_info:$R[20]={__ref:"client:Tweet:111:media:0:video_info"}},`
  + `"client:Tweet:111:media:0:video_info":$R[21]={__id:"client:Tweet:111:media:0:video_info",__typename:"ApiMediaEntityVideoInfo",duration_millis:12345,variants:$R[22]={__refs:$R[23]=["client:Tweet:111:media:0:video_info:variants:0","client:Tweet:111:media:0:video_info:variants:1"]}},`
  + `"client:Tweet:111:media:0:video_info:variants:0":$R[24]={__typename:"ApiMediaEntityVideoVariant",bitrate:void 0,content_type:"application/x-mpegURL",url:"https://video.twimg.com/amplify_video/111/pl/x.m3u8?tag=27"},`
  + `"client:Tweet:111:media:0:video_info:variants:1":$R[25]={__typename:"ApiMediaEntityVideoVariant",bitrate:832000,content_type:"video/mp4",url:"https://video.twimg.com/amplify_video/111/vid/avc1/550x360/y.mp4?tag=27"}`
  + `}}})(self.$R=self.$R||{});</script></body></html>`;

const ssrRecords = parseTwitterSsrRecords(ssrFixture);
expect(Object.keys(ssrRecords).length === 11, `ssr: relayRecords parsed (got ${Object.keys(ssrRecords).length})`);

// collectTwitterSsrTweets returns RESOLVED Tweet records (new media_entities2
// shape); they go through the same shape-tolerant extractTweetCapture as GraphQL.
const ssrDetails = { url: "https://x.com/jack/status/111", documentUrl: "https://x.com/jack/status/111", type: "main_frame", tabId: 1 };
const ssrTweets = collectTwitterSsrTweets(ssrFixture, ssrDetails);
expect(ssrTweets.length === 1, `ssr: one focal tweet (got ${ssrTweets.length})`);
const st = extractTweetCapture(ssrTweets[0], ssrDetails);
expect(st?.screenName === "jack", `ssr: author from core.user_results (got ${st?.screenName})`);
expect(st?.tweetId === "111", `ssr: tweet id (got ${st?.tweetId})`);
expect(st?.text === "hello world", `ssr: details.full_text (got ${JSON.stringify(st?.text)})`);
expect(st?.imageUrl === "https://pbs.twimg.com/t.jpg", "ssr: media_url_https thumbnail");
const mp4 = (st?.media?.[0]?.video_info?.variants || []).filter(v => v.content_type === "video/mp4");
expect(mp4.length === 1 && mp4[0].url === "https://video.twimg.com/amplify_video/111/vid/avc1/550x360/y.mp4?tag=27",
  `ssr: progressive mp4 variant recovered (got ${mp4.length})`);
expect(st?.media?.[0]?.video_info?.duration_millis === 12345, "ssr: duration_millis");

// Shape-tolerant extractor on the OLD GraphQL layout (the shape the home feed
// still uses): legacy.extended_entities.media + core.user_results screen_name.
// Proves one extractor serves both surfaces (Layer 1).
const gqlResponse = { data: { home: { home_timeline_urt: { instructions: [{ entries: [
  { content: { itemContent: { tweet_results: { result: {
    __typename: "Tweet", rest_id: "222",
    core: { user_results: { result: { core: { screen_name: "alice" } } } },
    legacy: { full_text: "feed clip", extended_entities: { media: [
      { media_url_https: "https://pbs.twimg.com/ext_tw_video_thumb/333/pu/img/a.jpg",
        video_info: { duration_millis: 5000, variants: [
          { content_type: "application/x-mpegURL", url: "https://video.twimg.com/ext_tw_video/333/pu/pl/m.m3u8" },
          { content_type: "video/mp4", bitrate: 832000, url: "https://video.twimg.com/ext_tw_video/333/pu/vid/avc1/640x360/v.mp4?tag=12" },
        ] } } ] } },
  } } } } },
] }] } } } };
const gqlDetails = { url: "https://x.com/home", documentUrl: "https://x.com/home", type: "xmlhttprequest", tabId: 5 };
const gqlResults = [];
collectTweetResults(gqlResponse.data, gqlResults, new Set());
expect(gqlResults.length === 1, `gql: collectTweetResults found tweet (got ${gqlResults.length})`);
const gt = extractTweetCapture(gqlResults[0], gqlDetails);
expect(gt?.screenName === "alice", `gql: author from core.user_results (got ${gt?.screenName})`);
expect(gt?.tweetId === "222", `gql: tweet id (got ${gt?.tweetId})`);
expect(gt?.text === "feed clip", `gql: legacy.full_text (got ${JSON.stringify(gt?.text)})`);
expect(gt?.imageUrl === "https://pbs.twimg.com/ext_tw_video_thumb/333/pu/img/a.jpg", "gql: extended_entities thumbnail");
const gmp4 = (gt?.media?.[0]?.video_info?.variants || []).filter(v => v.content_type === "video/mp4");
expect(gmp4.length === 1, `gql: progressive mp4 from extended_entities (got ${gmp4.length})`);

// media-id + master-URL helpers (Layer 3 dedup / gating).
expect(twimgMediaId("https://video.twimg.com/amplify_video/444/vid/avc1/960x628/x.mp4?tag=27") === "444", "media-id: amplify_video");
expect(twimgMediaId("https://video.twimg.com/ext_tw_video/555/pu/vid/avc1/1080x1920/x.mp4") === "555", "media-id: ext_tw_video");
expect(twimgMediaId("https://pbs.twimg.com/ext_tw_video_thumb/555/pu/img/x.jpg") === "555", "media-id: thumb links to video id");
expect(isTwitterMasterUrl("https://video.twimg.com/amplify_video/444/pl/VbJ6.m3u8?tag=27&v=bfe") === true, "master-url: master matched");
expect(isTwitterMasterUrl("https://video.twimg.com/amplify_video/444/pl/avc1/550x360/c.m3u8") === false, "master-url: child playlist excluded");

// Telegram (t.me) post-page extractor — runs the REAL helpers from telegram.js
// against a fixture mirroring the tgme widget markup (og: meta + <video src> +
// <time message_video_duration>), the HAR-replay guard per CLAUDE.md. Covers a
// single video, an album (two <video>s), and the og:video-only poster fallback.
const {
  TELEGRAM_POST_RE, TELEGRAM_FEED_RE, metaContent, collectVideoSrcs, collectDurations, parseClock,
  extractMessageText, extractAuthor, collectThumbs, titleFromPost,
  unwrapFeedBody, splitMessages,
} = await import(pathToFileURL(join(ext, "js/parsers/telegram.js")));
const { matchInParserBlocklist } = await import(pathToFileURL(join(ext, "js/parser-blocklist.js")));

expect(TELEGRAM_POST_RE.test("https://t.me/WatcherGuru/14028"), "tg: post URL matches");
expect(TELEGRAM_POST_RE.test("https://t.me/s/WatcherGuru/14028?embed=1"), "tg: /s/ + query matches");
expect(!TELEGRAM_POST_RE.test("https://t.me/WatcherGuru"), "tg: bare channel feed does not match");
// The embed widget iframe (a sub_frame) carries the <video> when the landing
// main_frame does not — its URL must match so the listener processes it.
expect(TELEGRAM_POST_RE.test("https://t.me/WatcherGuru/14028?embed=1&mode=tme"),
  "tg: ?embed=1&mode=tme widget URL matches (sub_frame capture path)");
const tgm = "https://t.me/WatcherGuru/14028".match(TELEGRAM_POST_RE);
expect(tgm[1] === "WatcherGuru" && tgm[2] === "14028", `tg: channel+id captured (got ${tgm[1]}/${tgm[2]})`);

const tgSingle = `<html><head>`
  + `<meta property="og:title" content="Watcher Guru">`
  + `<meta property="og:description" content="Breaking &amp; news">`
  + `<meta property="og:image" content="https://cdn4.cdn-telegram.org/file/poster.jpg">`
  + `</head><body><div class="tgme_widget_message_video_wrap">`
  + `<video class="tgme_widget_message_video js-message_video" poster="https://cdn4.cdn-telegram.org/file/poster.jpg" `
  + `src="https://cdn4.cdn-telegram.org/file/abc.mp4?token=XYZ"></video>`
  + `<time class="message_video_duration js-message_video_duration">1:23</time>`
  + `</div></body></html>`;
expect(metaContent(tgSingle, "og:title") === "Watcher Guru", "tg: og:title");
expect(metaContent(tgSingle, "og:image") === "https://cdn4.cdn-telegram.org/file/poster.jpg", "tg: og:image");
const tgSrcs = collectVideoSrcs(tgSingle);
expect(tgSrcs.length === 1 && tgSrcs[0] === "https://cdn4.cdn-telegram.org/file/abc.mp4?token=XYZ",
  `tg: single video src (got ${JSON.stringify(tgSrcs)})`);
expect(collectDurations(tgSingle)[0] === 83, `tg: duration 1:23 -> 83 (got ${collectDurations(tgSingle)[0]})`);

const tgAlbum = `<video src="https://cdn4.telesco.pe/file/one.mp4"></video>`
  + `<time class="message_video_duration">0:30</time>`
  + `<video src="https://cdn4.telesco.pe/file/two.mp4"></video>`
  + `<time class="message_video_duration">2:05:09</time>`;
const albumSrcs = collectVideoSrcs(tgAlbum);
expect(albumSrcs.length === 2, `tg: album two videos (got ${albumSrcs.length})`);
const albumDurs = collectDurations(tgAlbum);
expect(albumDurs[0] === 30 && albumDurs[1] === 7509, `tg: album durations (got ${JSON.stringify(albumDurs)})`);

// Poster-only page: no <video src>, recover from og:video.
const tgPosterOnly = `<meta property="og:video" content="https://cdn4.cdn-telegram.org/file/v.mp4">`
  + `<meta property="og:video:width" content="1280">`
  + `<meta property="og:video:height" content="720">`;
expect(collectVideoSrcs(tgPosterOnly).length === 0, "tg: poster-only has no <video src>");
expect(metaContent(tgPosterOnly, "og:video") === "https://cdn4.cdn-telegram.org/file/v.mp4", "tg: og:video fallback");
expect(parseInt(metaContent(tgPosterOnly, "og:video:height"), 10) === 720, "tg: og:video:height");
expect(parseClock("not-a-time") === 0 && parseClock("") === 0, "tg: bad clock -> 0");

// og:video:secure_url / og:video:url are spec'd siblings of og:video; the
// poster-only path falls back to them when the legacy og:video is absent.
const tgPosterSecure = `<meta property="og:video:secure_url" content="https://cdn4.telesco.pe/file/sec.mp4">`;
expect(!metaContent(tgPosterSecure, "og:video"), "tg: no legacy og:video on secure-only page");
expect(metaContent(tgPosterSecure, "og:video:secure_url") === "https://cdn4.telesco.pe/file/sec.mp4",
  "tg: og:video:secure_url fallback");
const tgPosterUrl = `<meta content="https://cdn4.telesco.pe/file/u.mp4" property="og:video:url">`;
expect(metaContent(tgPosterUrl, "og:video:url") === "https://cdn4.telesco.pe/file/u.mp4",
  "tg: og:video:url fallback (content-then-property order)");

// Duration↔video pairing is index-based and trusted ONLY when the counts match
// (the listener's `durationsAligned` guard). A photo+video album yields fewer
// duration <time>s than the page has media wraps but exactly one per VIDEO, so a
// post with N <video src> and M message_video_duration <time>s only pairs when
// N === M; a mismatch must fall back to 0 (probe) rather than mispair.
const tgMixed = `<video src="https://cdn4.telesco.pe/file/clip.mp4"></video>`
  + `<time class="message_video_duration">0:30</time>`
  + `<time class="message_video_duration">0:09</time>`; // stray extra <time>
const mixedSrcs = collectVideoSrcs(tgMixed);
const mixedDurs = collectDurations(tgMixed);
expect(mixedSrcs.length === 1 && mixedDurs.length === 2, `tg: mixed counts (got ${mixedSrcs.length}/${mixedDurs.length})`);
expect((mixedDurs.length === mixedSrcs.length) === false, "tg: mismatched counts are NOT treated as aligned");
expect((albumSrcs.length === albumDurs.length) === true, "tg: matched album counts ARE aligned");

// The emitted .mp4 must be parser-block-listed (cardinal rule) so the generic
// catcher can't double-capture; the poster .jpg must NOT be blocked.
expect(matchInParserBlocklist("https://cdn4.cdn-telegram.org/file/abc.mp4?token=XYZ"),
  "tg: emitted .mp4 is parser-block-listed");
expect(matchInParserBlocklist("https://cdn4.telesco.pe/file/two.mp4"),
  "tg: legacy telesco.pe .mp4 is parser-block-listed");
expect(!matchInParserBlocklist("https://cdn4.cdn-telegram.org/file/poster.jpg"),
  "tg: poster .jpg is NOT block-listed");

// Embed-widget metadata extraction (the iframe carries the <video> but NO og:
// tags, so title/author/thumb come from the tgme widget markup). Mirrors the
// real WatcherGuru/14028 embed HTML from the user HAR.
const tgEmbed = `<a class="tgme_widget_message_owner_name" href="https://t.me/WatcherGuru"><span dir="auto">Watcher Guru</span></a>`
  + `<i class="tgme_widget_message_video_thumb" style="background-image:url('https://cdn4.telesco.pe/file/THUMB.jpg')"></i>`
  + `<video src="https://cdn4.telesco.pe/file/431eee6783.mp4?token=AB" class="tgme_widget_message_video js-message_video"></video>`
  + `<div class="tgme_widget_message_text js-message_text" dir="auto"><b>JUST IN:</b> GTA 6 pre-orders officially begin on June 25.<br/><br/><a href="https://t.me/WatcherGuru">@WatcherGuru</a></div>`
  + `<time class="message_video_duration js-message_video_duration">0:32</time>`;
expect(extractAuthor(tgEmbed) === "Watcher Guru", `tg: extractAuthor (got ${JSON.stringify(extractAuthor(tgEmbed))})`);
expect(extractMessageText(tgEmbed) === "JUST IN: GTA 6 pre-orders officially begin on June 25.\n@WatcherGuru",
  `tg: extractMessageText (got ${JSON.stringify(extractMessageText(tgEmbed))})`);
// Title composes "<Channel> — <post text first line>".
expect(titleFromPost(extractMessageText(tgEmbed), extractAuthor(tgEmbed)) === "Watcher Guru — JUST IN: GTA 6 pre-orders officially begin on June 25.",
  `tg: title is "Channel — post text" (got ${JSON.stringify(titleFromPost(extractMessageText(tgEmbed), extractAuthor(tgEmbed)))})`);
expect(collectThumbs(tgEmbed)[0] === "https://cdn4.telesco.pe/file/THUMB.jpg",
  `tg: collectThumbs (got ${JSON.stringify(collectThumbs(tgEmbed))})`);
expect(titleFromPost(null, "Watcher Guru") === "Watcher Guru", "tg: title falls back to author for a text-less post");
expect(titleFromPost("Hello world", null) === "Hello world", "tg: title is just the text when author is unknown");
// Duration is emitted in MILLISECONDS (parseClock gives seconds); 0:32 -> 32000.
expect(collectDurations(tgEmbed)[0] === 32 && collectDurations(tgEmbed)[0] * 1000 === 32000,
  "tg: duration 0:32 -> 32s -> 32000ms");

// Channel FEED (t.me/s/<channel>): URL match, JSON-wrapped pagination unwrap,
// per-message split. Mirrors the /s/WatcherGuru?before= batches from the HAR.
expect(TELEGRAM_FEED_RE.test("https://t.me/s/WatcherGuru"), "tg-feed: bare feed URL matches");
expect(TELEGRAM_FEED_RE.test("https://t.me/s/WatcherGuru?before=14078"), "tg-feed: pagination URL matches");
expect(!TELEGRAM_FEED_RE.test("https://t.me/s/WatcherGuru/14028"), "tg-feed: single /s/ post does NOT match (post listener owns it)");
expect("https://t.me/s/WatcherGuru?before=14078".match(TELEGRAM_FEED_RE)[1] === "WatcherGuru", "tg-feed: channel captured");
// Pagination body is a JSON-encoded HTML string (escaped slashes); unwrap it.
const tgFeedJson = JSON.stringify(`<div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="WatcherGuru/14065"><a class="tgme_widget_message_owner_name"><span>Watcher Guru</span></a><a class="tgme_widget_message_video_player" href="https://t.me/WatcherGuru/14065"><i class="tgme_widget_message_video_thumb" style="background-image:url('https://cdn4.telesco.pe/file/T.jpg')"></i></a><video src="https://cdn4.telesco.pe/file/94db93594c.mp4?token=AB" class="tgme_widget_message_video"></video><div class="tgme_widget_message_text">JUST IN: Strait of Hormuz traffic.</div><time class="message_video_duration">0:15</time></div></div>`);
const tgFeedHtml = unwrapFeedBody(tgFeedJson);
expect(tgFeedHtml.includes("<video src=") && !tgFeedHtml.includes("\\/"), "tg-feed: JSON body unwrapped to real HTML");
const tgBlocks = splitMessages(tgFeedHtml);
expect(tgBlocks.length === 1, `tg-feed: one message block (got ${tgBlocks.length})`);
const fb = tgBlocks[0];
expect(collectVideoSrcs(fb)[0] === "https://cdn4.telesco.pe/file/94db93594c.mp4?token=AB", "tg-feed: block video src");
expect((fb.match(/data-post="[A-Za-z0-9_]+\/(\d+)"/) || [])[1] === "14065", "tg-feed: block post id");
expect(titleFromPost(extractMessageText(fb), extractAuthor(fb)) === "Watcher Guru — JUST IN: Strait of Hormuz traffic.",
  `tg-feed: block title (got ${JSON.stringify(titleFromPost(extractMessageText(fb), extractAuthor(fb)))})`);
expect(collectDurations(fb)[0] === 15, "tg-feed: block duration 0:15 -> 15s");
expect(unwrapFeedBody("<div>plain html</div>") === "<div>plain html</div>", "tg-feed: plain HTML passes through unwrap");

// Telegram WEB APP: the SW-virtual /stream/ URLs are non-re-fetchable, so they
// must be block-listed to keep broken (undownloadable) entries out of the
// Captured sheet. A public t.me CDN URL must still NOT be caught by this rule.
expect(matchInParserBlocklist("https://web.telegram.org/k/stream/%7B%22dcId%22%3A2%7D"),
  "tg-web: /k/stream/ is block-listed");
expect(matchInParserBlocklist("https://webk.telegram.org/stream/abc"),
  "tg-web: webk host /stream/ is block-listed");
expect(matchInParserBlocklist("https://web.telegram.org/a/stream/xyz"),
  "tg-web: /a/stream/ is block-listed");
expect(!matchInParserBlocklist("https://web.telegram.org/k/"),
  "tg-web: non-stream web app URL is NOT block-listed");

// Spotify embed extractor — runs the REAL extractSpotifyEmbedTracks against a
// fixture mirroring the open.spotify.com/embed/* __NEXT_DATA__ shape (the
// HAR-replay guard per CLAUDE.md). Covers a multi-track playlist entity, a
// DRM-only track with no audioPreview (skipped), and entity-level cover art
// fallback; plus a single-entity (track) embed with audioPreview on the entity.
const { extractSpotifyEmbedTracks } = await import(pathToFileURL(join(ext, "js/parsers/spotify.js")));

function spotifyEmbedHtml(entity) {
  return `<html><body><script id="__NEXT_DATA__" type="application/json">`
    + JSON.stringify({ props: { pageProps: { state: { data: { entity } } } } })
    + `</script></body></html>`;
}

const spPlaylist = spotifyEmbedHtml({
  type: "playlist", name: "Top 100 Música actual", uri: "spotify:playlist:7GVCWJ3kvSXQKpeaRyNaqc",
  coverArt: { sources: [{ width: 640, height: 640, url: "https://image-cdn-ak.spotifycdn.com/image/cover640" }] },
  trackList: [
    { uri: "spotify:track:4LfCY65LvojKjWEnU7fNN4", title: "stupid song", subtitle: "Olivia Rodrigo",
      duration: 209680, audioPreview: { format: "MP3_96", url: "https://p.scdn.co/mp3-preview/894b272aa0422aeebe856825b55e5c736a9bc5ee" } },
    { uri: "spotify:track:aaaa", title: "PrRrr", subtitle: "judith",
      duration: 93787, audioPreview: { format: "MP3_96", url: "https://p.scdn.co/mp3-preview/35dc98219155c840243490c6d09d7c75e289d789" } },
    { uri: "spotify:track:bbbb", title: "DRM only", subtitle: "nobody", duration: 100000 }, // no audioPreview -> skipped
  ],
});
const spTracks = extractSpotifyEmbedTracks(spPlaylist).tracks;
expect(spTracks.length === 2, `spotify: DRM-only track skipped, 2 previewable (got ${spTracks.length})`);
expect(spTracks[0].url === "https://p.scdn.co/mp3-preview/894b272aa0422aeebe856825b55e5c736a9bc5ee",
  "spotify: preview URL from audioPreview");
expect(spTracks[0].title === "stupid song" && spTracks[0].artist === "Olivia Rodrigo",
  `spotify: per-track title/artist (got ${spTracks[0].title}/${spTracks[0].artist})`);
expect(spTracks[0].origin === "https://open.spotify.com/track/4LfCY65LvojKjWEnU7fNN4",
  `spotify: per-track origin from uri (got ${spTracks[0].origin})`);
expect(spTracks[0].cover === "https://image-cdn-ak.spotifycdn.com/image/cover640",
  "spotify: entity cover used as per-track fallback");

// Single-entity (track) embed: audioPreview lives on the entity, no trackList.
const spTrack = spotifyEmbedHtml({
  type: "track", name: "Solo Song", uri: "spotify:track:cccc", title: "Solo Song", subtitle: "Artist X",
  coverArt: { sources: [{ width: 300, url: "https://image-cdn-ak.spotifycdn.com/image/albumart" }] },
  duration: 180000, audioPreview: { format: "MP3_96", url: "https://p.scdn.co/mp3-preview/soloclip" },
});
const spOne = extractSpotifyEmbedTracks(spTrack).tracks;
expect(spOne.length === 1 && spOne[0].url === "https://p.scdn.co/mp3-preview/soloclip",
  `spotify: single-entity embed yields one track (got ${spOne.length})`);
expect(spOne[0].cover === "https://image-cdn-ak.spotifycdn.com/image/albumart", "spotify: single-track album art");

// A body with no __NEXT_DATA__ yields nothing (not a throw).
expect(extractSpotifyEmbedTracks("<html><body>no data</body></html>").tracks.length === 0,
  "spotify: missing __NEXT_DATA__ -> empty");

// Cardinal rule: the emitted preview host is parser-block-listed so the generic
// catcher can't double-capture the same clip on play.
expect(matchInParserBlocklist("https://p.scdn.co/mp3-preview/894b272aa0422aeebe856825b55e5c736a9bc5ee"),
  "spotify: mp3-preview is parser-block-listed");
expect(!matchInParserBlocklist("https://i.scdn.co/image/ab67616d0000b273cover.jpg"),
  "spotify: scdn image is NOT block-listed");

if (failures) {
  console.error(`\n${failures} failure(s)`);
  process.exit(1);
}
console.log("\nsmoke: all checks passed");
process.exit(0);
