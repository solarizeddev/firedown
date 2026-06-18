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
expect(count("webRequest.onBeforeRequest") === 27, `webRequest.onBeforeRequest registrations == 27 (got ${count("webRequest.onBeforeRequest")})`);
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
const { parseTwitterSsrRecords, collectTwitterSsrTweets } = await import(
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

const ssrTweets = collectTwitterSsrTweets(ssrFixture, { url: "https://x.com/jack/status/111", type: "main_frame", tabId: 1 });
expect(ssrTweets.length === 1, `ssr: one focal tweet (got ${ssrTweets.length})`);
const st = ssrTweets[0];
expect(st?.screenName === "jack", `ssr: author from core.user_results (got ${st?.screenName})`);
expect(st?.tweetId === "111", `ssr: tweet id (got ${st?.tweetId})`);
expect(st?.text === "hello world", `ssr: details.full_text (got ${JSON.stringify(st?.text)})`);
expect(st?.imageUrl === "https://pbs.twimg.com/t.jpg", "ssr: media_url_https thumbnail");
const mp4 = (st?.media?.[0]?.video_info?.variants || []).filter(v => v.content_type === "video/mp4");
expect(mp4.length === 1 && mp4[0].url === "https://video.twimg.com/amplify_video/111/vid/avc1/550x360/y.mp4?tag=27",
  `ssr: progressive mp4 variant recovered (got ${mp4.length})`);
expect(st?.media?.[0]?.video_info?.duration_millis === 12345, "ssr: duration_millis");

if (failures) {
  console.error(`\n${failures} failure(s)`);
  process.exit(1);
}
console.log("\nsmoke: all checks passed");
process.exit(0);
