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

// Native emits (sendNative → runtime.sendNativeMessage) recorded so a dispatch
// test can assert what actually reaches the Java side.
const nativeSent = [];

globalThis.browser = {
  runtime: {
    sendNativeMessage: async (app, msg) => { nativeSent.push({ app, msg }); return false; },
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
    filterResponseData() { throw new Error("filterResponseData must not run at import time"); },
  },
  webNavigation: {
    onHistoryStateUpdated: evt("webNavigation.onHistoryStateUpdated"),
    getFrame: async ({ frameId }) => ({ frameId, parentFrameId: frameId > 0 ? 0 : -1 }),
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
expect(count("webRequest.onBeforeRequest") === 43, `webRequest.onBeforeRequest registrations == 43 (got ${count("webRequest.onBeforeRequest")})`);
// The snapshot archiver's Referer rewrite for its own privileged fetches
// (requests.js snapshotReferers) — the one blocking onBeforeSendHeaders.
expect(count("webRequest.onBeforeSendHeaders") === 1, `webRequest.onBeforeSendHeaders registrations == 1 (got ${count("webRequest.onBeforeSendHeaders")})`);
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
  "https://shows.acast.com/el-mundo-al-dia/episodes/sanchez-la-vivienda-y-las-elecciones",
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
expect(!spaThrew, "SPA handlers run for all six registered sites");

// ---------------------------------------------------------------------------
// page-state-progressive AUDIO group (the podverse.fm case): a declared-audio
// variant (audioOnly — the bridge's AUDIO_RE hit, e.g. __NEXT_DATA__.mediaUrl)
// must ride through handlePageStateProgressive with audioOnly intact (Java's
// skip-probe branch keys the entity's audio typing off it — a video/mp4-stamped
// podcast mp3 was the bug), the state duration auto-setting skipProbe, and the
// <audio>-element header shape (Sec-Fetch-Dest: audio) instead of the video one.
// ---------------------------------------------------------------------------
nativeSent.length = 0;
for (const listener of registrations["runtime.onMessage"]) {
  listener({
    kind: "page-state-progressive",
    payload: {
      variants: [{ url: "https://api.example.com/feed/podcast/1/abc.mp3", width: 0, height: 0, audioOnly: true }],
      origin: "https://podcast.example.com/episode/xyz",
      title: "Episode title",
      durationMs: 903000,
      ua: "UA-string",
      lang: "en-US",
    },
  }, { tab: { id: 3, url: "https://podcast.example.com/episode/xyz" } });
}
await new Promise((r) => setTimeout(r, 30));
const audioMsg = nativeSent.map((n) => n.msg)
  .find((m) => m && m.type === "variants" && /abc\.mp3/.test(m.url ?? ""));
expect(!!audioMsg, "page-state audio: native variants message emitted");
expect(audioMsg?.variants?.[0]?.audioOnly === true, "page-state audio: variant keeps audioOnly");
expect(audioMsg?.skipProbe === true, "page-state audio: state duration auto-sets skipProbe");
const audioHdrs = Object.fromEntries((audioMsg?.requestHeaders ?? []).map((h) => [h.name, h.value]));
expect(audioHdrs["Sec-Fetch-Dest"] === "audio",
  `page-state audio: <audio>-element Sec-Fetch-Dest (got ${audioHdrs["Sec-Fetch-Dest"]})`);
expect((audioHdrs["Accept"] ?? "").startsWith("audio/"), "page-state audio: <audio>-element Accept");

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
  parseTwitterSsrRecords, parseTwitterSsrStreamValues, collectTwitterSsrTweets,
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

// Twitter/X STREAMED-router SSR (shape b, HAR 26-09-29): no class="$tsr", no
// relayRecords, no __ref. A data-tsr-stream-part script declares the shared
// `$R["tsr"]` table and `$_TSR.router=($R=>$R[0]={...})($R["tsr"])` whose
// loader inlines the RESOLVED TweetResultByRestId result — beside live JS
// (arrow functions, `new`, an IIFE) the parser must skip without ending the
// object — then plain <script>s push the TweetDetail conversation into the
// same table (`$R[52].next(...)`, a back-reference ACROSS scripts) with the
// focal tweet again (a `$R[10]` back-ref), the quoted tweet, and a REPLY that
// carries a video but is not the page's tweet. Expected: focal + quoted, the
// reply excluded, the keys after the embedded functions preserved.
const streamFixture = `<html><head><script nonce="x" data-tsr-stream-part="">(self.$R=self.$R||{})["tsr"]=[];self.$_TSR={h(){this.hydrated=!0},buffer:[]};$_TSR.router=($R=>$R[0]={manifest:void 0,matches:$R[1]=[$R[2]={i:"__root__ {}",s:"success",ssr:!0},$R[3]={i:" $username status $id",s:"success",l:$R[4]={articleDocument:null,tweetResult:$R[5]={kind:"GraphQLRequestStream.Completed",requestId:0,key:"9UstBOIauk{\\"restId\\":\\"111\\"}",result:$R[6]={kind:"Result.Ok",value:$R[7]={data:$R[8]={tweet_result_by_rest_id:$R[9]={id:"VHdlZXQ=",rest_id:"111",result:$R[10]={__typename:"Tweet",core:$R[11]={user_results:$R[12]={id:"VXNlcg==",result:$R[13]={__typename:"User",core:$R[14]={name:"Jack",screen_name:"jack"},rest_id:"9"}}},details:$R[15]={full_text:"streamed clip https://t.co/x",hashtag_entities:$R[16]=[]},is_translatable:!1,legacy:$R[17]={lang:"en",possibly_sensitive:!1},media_entities2:$R[18]=[$R[19]={allow_download_status:$R[20]={allow_download:!0},id_str:"777",media_url_https:"https://pbs.twimg.com/amplify_video_thumb/777/img/t.jpg",original_info:$R[21]={height:480,width:576},type:"video",video_info:$R[22]={duration_millis:23080,variants:$R[23]=[$R[24]={content_type:"application/x-mpegURL",url:"https://video.twimg.com/amplify_video/777/pl/m.m3u8?tag=29"},$R[25]={bitrate:832000,content_type:"video/mp4",url:"https://video.twimg.com/amplify_video/777/vid/avc1/576x480/v.mp4?tag=29"}]}}],quoted_tweet_results:$R[26]={id:"VHdlZXRz",result:$R[27]={__typename:"Tweet",rest_id:"222",core:$R[28]={user_results:$R[29]={result:$R[30]={__typename:"User",core:$R[31]={screen_name:"alice"}}}},details:$R[32]={full_text:"quoted, no video"},media_entities2:$R[33]=[]}},rest_id:"111",views:$R[34]={count:"9"}}}}}}},graphqlRequestStream:$R[50]=($R[51]=(stream) => new ReadableStream({ start(controller) { stream.on({ next(value) { try { controller.enqueue(value); } catch (_e) {} }, return() { controller.close(); } }); } }))($R[52]=($R[53]=() => { const buffer = []; const listeners = []; let alive = true; return { next(v) { buffer.push(v); }, on(l) { listeners.push(l); return () => { alive = false; }; } }; })()),trailing:!0}}],ssrFlag:!0})($R["tsr"]);{let s=document.currentScript,p;while((p=s.previousElementSibling)&&p.hasAttribute('data-tsr-stream-part'))p.remove();s.remove()}</script>`
  + `<script nonce="x">($R=>$R[52].next($R[60]={kind:"GraphQLRequestStream.Started",requestId:1}))($R["tsr"]);</script>`
  + `<script nonce="x">($R=>$R[52].next($R[61]={kind:"GraphQLRequestStream.Completed",requestId:1,key:"sbmV{\\"focalTweetId\\":\\"111\\"}",result:$R[62]={kind:"Result.Ok",value:$R[63]={data:$R[64]={threaded_conversation_with_injections_v2:$R[65]={instructions:$R[66]=[$R[67]={type:"TimelineAddEntries",entries:$R[68]=[$R[69]={entryId:"tweet-111",content:$R[70]={itemContent:$R[71]={__typename:"TimelineTweet",tweet_results:$R[72]={id:"VHdlZXQ=",rest_id:"111",result:$R[10]}}}},$R[73]={entryId:"conversationthread-1",content:$R[74]={items:$R[75]=[$R[76]={item:$R[77]={itemContent:$R[78]={tweet_results:$R[79]={rest_id:"999",result:$R[80]={__typename:"Tweet",rest_id:"999",core:$R[81]={user_results:$R[82]={result:$R[83]={__typename:"User",core:$R[84]={screen_name:"replier"}}}},details:$R[85]={full_text:"a reply with its own video"},media_entities2:$R[86]=[$R[87]={media_url_https:"https://pbs.twimg.com/amplify_video_thumb/999/img/r.jpg",type:"video",video_info:$R[88]={duration_millis:5000,variants:$R[89]=[$R[90]={bitrate:832000,content_type:"video/mp4",url:"https://video.twimg.com/amplify_video/999/vid/avc1/640x360/r.mp4"}]}}]}}}}]}}]}]},tweet_result_by_rest_id:$R[91]={id:"VHdlZXQ=",rest_id:"111",result:$R[10]}}}}}))($R["tsr"]);</script>`
  + `<script nonce="x">($R=>$R[52].return(void 0))($R["tsr"]);$_TSR.e();</script></head><body></body></html>`;

expect(Object.keys(parseTwitterSsrRecords(streamFixture)).length === 0, "ssr-stream: no relayRecords store (shape b, not a)");
const streamValues = parseTwitterSsrStreamValues(streamFixture);
expect(streamValues.length >= 3, `ssr-stream: top-level values parsed across scripts (got ${streamValues.length})`);
expect(streamValues[0]?.matches?.[1]?.l?.trailing === true && streamValues[0]?.ssrFlag === true,
  "ssr-stream: keys AFTER the embedded arrow functions / IIFE survive (balanced JS skip)");
expect(streamValues[0]?.matches?.[1]?.l?.tweetResult?.result?.value?.data?.tweet_result_by_rest_id?.result?.rest_id === "111",
  "ssr-stream: focal tweet reachable through the router loader");
const streamDetails = { url: "https://x.com/jack/status/111", documentUrl: "https://x.com/jack/status/111", type: "main_frame", tabId: 1 };
const streamTweets = collectTwitterSsrTweets(streamFixture, streamDetails);
expect(streamTweets.length === 2, `ssr-stream: focal + quoted, reply excluded (got ${streamTweets.length}: ${streamTweets.map(t => t.rest_id).join(",")})`);
expect(streamTweets.map(t => t.rest_id).sort().join(",") === "111,222", `ssr-stream: ids are focal+quoted (got ${streamTweets.map(t => t.rest_id).join(",")})`);
expect(!streamTweets.some(t => t.rest_id === "999"), "ssr-stream: the conversation REPLY's video is not collected (focal-only)");
const stt = extractTweetCapture(streamTweets[0], streamDetails);
expect(stt?.screenName === "jack" && stt?.tweetId === "111", `ssr-stream: author + id (got ${stt?.screenName}/${stt?.tweetId})`);
expect(stt?.text === "streamed clip https://t.co/x", `ssr-stream: details.full_text (got ${JSON.stringify(stt?.text)})`);
expect(stt?.imageUrl === "https://pbs.twimg.com/amplify_video_thumb/777/img/t.jpg", "ssr-stream: media_url_https thumbnail");
const stmp4 = (stt?.media?.[0]?.video_info?.variants || []).filter(v => v.content_type === "video/mp4");
expect(stmp4.length === 1 && stmp4[0].url.includes("/amplify_video/777/vid/avc1/576x480/"), `ssr-stream: progressive mp4 variant (got ${stmp4.length})`);
expect(extractTweetCapture(streamTweets[1], streamDetails) === null, "ssr-stream: quoted tweet without video yields no capture");

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

// ---------------------------------------------------------------------------
// classifyByUrl decision table (the generic catcher's admission test).
//
// The load-bearing case is the EXTENSIONLESS image: Google Maps place photos
// (lh3.googleusercontent.com/p/…=w426-h240-k-no) carry no extension, so
// getTypeFromUrl yields nothing and only the webRequest-type fallback admits
// them. That fallback accepted 'image' but not 'imageset' (what Firefox
// reports for <img srcset>/<picture> loads) — extensionless srcset images
// were silently dropped on the wire path. Run against the pre-fix code, the
// imageset cases fail.
// ---------------------------------------------------------------------------
{
  const { classifyByUrl } = await import(pathToFileURL(join(ext, "js/requests.js")));
  const LH3 = "https://lh3.googleusercontent.com/p/AF1QipTEST=w1600-h1000-k-no";

  let d = { url: LH3, type: "imageset" };
  expect(classifyByUrl(d) === true, "classify: extensionless imageset admitted (Maps photo shape)");
  expect(d.type === "image", `classify: imageset normalized to image (got ${d.type})`);

  d = { url: LH3, type: "image" };
  expect(classifyByUrl(d) === true && d.type === "image",
    "classify: extensionless plain image admitted");

  d = { url: "https://cdn.example.com/photo.jpg?sig=abc", type: "imageset" };
  expect(classifyByUrl(d) === true && d.type === "image",
    "classify: extensioned srcset image admitted via extension");

  d = { url: LH3, type: "script" };
  expect(classifyByUrl(d) === false,
    "classify: extensionless non-media type still rejected");


  d = { url: "https://cdn.example.com/clip", type: "media" };
  expect(classifyByUrl(d) === true && d.type === "media",
    "classify: extensionless media element load admitted");
}

// ---------------------------------------------------------------------------
// Content-script DOM scan — the REAL js/content-script.js under a stubbed
// document. Drives the images-detected pipeline end to end: initial scan,
// inline background-image extraction (the Google-Maps class — the photo is a
// `<div style="background-image:url(…)">`, no <img> anywhere, and a SW/cache-
// served copy never crosses webRequest, so this scan is the ONLY net), and
// the MutationObserver's style-attribute routing for lazy-assigned tiles.
// ---------------------------------------------------------------------------
{
  const vm = await import("node:vm");

  const batches = [];
  browser.runtime.sendMessage = async (msg) => {
    if (msg?.kind === "images-detected") batches.push(msg.urls);
    return false;
  };

  const el = (tagName, props = {}) => ({
    nodeType: 1,
    tagName,
    style: {},
    getAttribute: () => null,
    querySelectorAll: () => [],
    ...props,
  });

  const LH3_A = "https://lh3.googleusercontent.com/p/AF1QipAAA=w426-h240-k-no";
  const LH3_B = "https://lh3.googleusercontent.com/p/AF1QipBBB=w1600-h1000-k-no";
  const bgDiv = el("DIV", { style: { backgroundImage: `url("${LH3_A}")` } });
  // Multi-layer background: a gradient layer (no url()) plus a data: URL —
  // queue() must keep neither.
  const noiseDiv = el("DIV", {
    style: { backgroundImage: 'linear-gradient(red, blue), url("data:image/png;base64,AAAA")' },
  });
  const colorDiv = el("DIV", { style: {} });    // background-color only
  const img = el("IMG", { src: "https://cdn.example.com/hero.jpg", currentSrc: "" });

  const docRoot = el("HTML", {
    querySelectorAll: (sel) => {
      if (sel === "img") return [img];
      if (sel.includes("background")) return [bgDiv, noiseDiv, colorDiv];
      return [];
    },
  });

  let moCallback = null;
  const sandboxDoc = {
    readyState: "complete",
    documentElement: docRoot,
    body: docRoot,
    addEventListener: () => {},
    querySelectorAll: () => [],
    querySelector: () => null,
  };
  const prev = {
    document: globalThis.document, window: globalThis.window,
    location: globalThis.location, MutationObserver: globalThis.MutationObserver,
  };
  globalThis.document = sandboxDoc;
  globalThis.window = { addEventListener: () => {} };
  globalThis.location = new URL("https://maps.example.com/place/test");
  globalThis.MutationObserver = class {
    constructor(cb) { moCallback = cb; }
    observe() {}
    disconnect() {}
  };

  try {
    const { readFileSync } = await import("node:fs");
    vm.runInThisContext(
      readFileSync(join(ext, "js/content-script.js"), "utf8"),
      { filename: "js/content-script.js" }
    );

    // Initial scan batch (BATCH_MS = 200).
    await new Promise((r) => setTimeout(r, 350));
    const first = batches.flat();
    expect(first.includes(LH3_A),
      "cs-scan: inline background-image URL captured (Maps photo class)");
    expect(first.includes("https://cdn.example.com/hero.jpg"),
      "cs-scan: <img> src still captured");
    expect(!first.some((u) => u.startsWith("data:")),
      "cs-scan: data: background layer filtered out");
    expect(!first.some((u) => u.includes("gradient")),
      "cs-scan: gradient layer yields no URL");

    // Style-attribute mutation on a NEW element — the lazy gallery tile.
    expect(typeof moCallback === "function", "cs-scan: MutationObserver armed");
    const lazyTile = el("DIV", { style: { backgroundImage: `url(${LH3_B})` } });
    moCallback([{ type: "attributes", attributeName: "style", target: lazyTile }]);
    await new Promise((r) => setTimeout(r, 350));
    expect(batches.flat().includes(LH3_B),
      "cs-scan: style mutation captures lazily-assigned background photo");
  } finally {
    globalThis.document = prev.document;
    globalThis.window = prev.window;
    globalThis.location = prev.location;
    globalThis.MutationObserver = prev.MutationObserver;
  }
}

// ---------------------------------------------------------------------------
// Deezer — gateway song walk + format pick + cardinal-rule block (real walker)
// ---------------------------------------------------------------------------
const { collectSongs, pickFormat } = await import(pathToFileURL(join(ext, "js/parsers/deezer.js")));

// A deezer.pageAlbum-shaped gw-light response: songs nested under
// results.SONGS.data[], each with SNG_ID + TRACK_TOKEN + FILESIZE_* + cover md5.
// Includes a token-less related stub that must NOT be captured.
const dzGateway = {
  error: [],
  results: {
    DATA: { ALB_TITLE: "Some Album" },
    SONGS: {
      data: [
        { SNG_ID: "123456", SNG_TITLE: "First Track", ART_NAME: "An Artist",
          ALB_PICTURE: "aabbccddeeff00112233445566778899", DURATION: "215",
          TRACK_TOKEN: "tok-1", FILESIZE_FLAC: "0", FILESIZE_MP3_320: "8200000",
          FILESIZE_MP3_128: "3400000" },
        { SNG_ID: "789012", SNG_TITLE: "Lossless Track", ART_NAME: "An Artist",
          ALB_PICTURE: "99887766554433221100ffeeddccbbaa", DURATION: "184",
          TRACK_TOKEN: "tok-2", FILESIZE_FLAC: "27000000", FILESIZE_MP3_320: "7000000" },
        // Related-item shell: has a title but NO token → not playable, skip.
        { SNG_ID: "555555", SNG_TITLE: "Preview Stub" }
      ]
    }
  }
};
const dzSongs = collectSongs(dzGateway.results);
expect(dzSongs.length === 2, `deezer: 2 playable songs walked, stub skipped (got ${dzSongs.length})`);
expect(dzSongs[0].SNG_ID === "123456", "deezer: first SNG_ID walked from nested SONGS.data");
expect(pickFormat(dzSongs[0]).fmt === "MP3_320",
  `deezer: picks best available (MP3_320, no FLAC) (got ${pickFormat(dzSongs[0]).fmt})`);
expect(pickFormat(dzSongs[1]).fmt === "FLAC",
  `deezer: picks FLAC when present (got ${pickFormat(dzSongs[1]).fmt})`);
// Dedup within a walk: the same SNG_ID appearing twice yields one entry.
expect(collectSongs({ a: dzGateway.results.SONGS.data[0], b: dzGateway.results.SONGS.data[0] }).length === 1,
  "deezer: duplicate SNG_ID collapses within a walk");

// Cardinal rule: the encrypted media CDN is block-listed (a bare capture there
// would save undecryptable ciphertext); the 30s preview host is NOT.
expect(matchInParserBlocklist("https://e-cdns-proxy-a.dzcdn.net/mobile/1/abcdef0123456789"),
  "deezer: e-cdns-proxy CDN is parser-block-listed");
expect(matchInParserBlocklist("https://cdns-proxy-b.dzcdn.net/media/1/deadbeef"),
  "deezer: cdns-proxy CDN is parser-block-listed");
expect(!matchInParserBlocklist("https://cdns-preview-a.dzcdn.net/stream/c-preview.mp3"),
  "deezer: 30s preview host is NOT block-listed");
expect(!matchInParserBlocklist("https://e-cdns-images.dzcdn.net/images/cover/x/500x500.jpg"),
  "deezer: images CDN is NOT block-listed");

// ---------------------------------------------------------------------------
// HLS master → child suppression + WebVTT sprite verdict + redirect gate (the
// pure halves of the three lasprovincias.es fixes; the listeners around them
// are counted in the inventory above).
{
  const { parseHlsMasterChildren, decideVtt, isRedirectStatus } = await import(pathToFileURL(join(ext, "js/requests.js")));
  const master = [
    "#EXTM3U",
    '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",URI="audio/en.m3u8"',
    "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=910000,RESOLUTION=356x642",
    "https://videos-cloudfront-usp.jwpsrv.com/6aaba593_sig/sites/x/media/EvU8KrK5/manifest.ism/manifest-audio_0=112084-video_0=783797.m3u8",
    "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=400000",
    "rel/low.m3u8#frag",
    '#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI="iframes.m3u8"',
    "",
  ].join("\n");
  const kids = parseHlsMasterChildren(master, "https://cdn.jwplayer.com/manifests/EvU8KrK5.m3u8");
  expect(kids.length === 4, `hls-master: four children parsed (got ${kids.length}: ${kids.join(" ")})`);
  expect(kids.includes("https://videos-cloudfront-usp.jwpsrv.com/6aaba593_sig/sites/x/media/EvU8KrK5/manifest.ism/manifest-audio_0=112084-video_0=783797.m3u8"),
    "hls-master: absolute STREAM-INF child kept verbatim");
  expect(kids.includes("https://cdn.jwplayer.com/manifests/rel/low.m3u8"), "hls-master: relative child resolved against the master, fragment dropped");
  expect(kids.includes("https://cdn.jwplayer.com/manifests/audio/en.m3u8"), "hls-master: EXT-X-MEDIA URI child collected");
  expect(kids.includes("https://cdn.jwplayer.com/manifests/iframes.m3u8"), "hls-master: I-FRAME child collected");
  const media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n";
  expect(parseHlsMasterChildren(media, "https://h/x.m3u8").length === 0, "hls-master: a media playlist yields no children");

  expect(decideVtt("WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nEvU8KrK5-120.jpg#xywh=0,0,120,68\n") === "sprite",
    "vtt: JW strip (xywh cue) is a sprite");
  expect(decideVtt("WEBVTT\n\n1\n00:00:00.000 --> 00:00:04.000\nhttps://cdn.example/sheet.png\n") === "sprite",
    "vtt: bare image-URL cue is a sprite");
  expect(decideVtt("WEBVTT\n\n1\n00:00:00.000 --> 00:00:04.000\nHello world\n") === "text",
    "vtt: a text cue is captions");
  expect(decideVtt("WEBVTT\nKind: captions\n\nNOTE something\n\n") === "more",
    "vtt: header/NOTE only → undecided");
  expect(decideVtt("WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nSee https://example.com/a.png for details\n") === "text",
    "vtt: a sentence mentioning an image is still text");

  expect(isRedirectStatus(301) && isRedirectStatus(302) && isRedirectStatus(307) && isRedirectStatus(308),
    "redirect gate: 301/302/307/308 rejected");
  expect(!isRedirectStatus(200) && !isRedirectStatus(206) && !isRedirectStatus(304) && !isRedirectStatus(403) && !isRedirectStatus(undefined),
    "redirect gate: 200/206/304/403/undefined pass");
}

// End to end through the REAL recorded listeners: a JW-shaped master lands as
// one media capture, the rendition playlist the player fetches next (listed in
// that master's body) is dropped, a redirect hop emits nothing, and a .vtt is
// emitted or dropped on its BODY (captions vs thumbnail sprite).
{
  // This stub fires EVERY recorded listener with no URL-pattern filtering
  // (the browser would only call the ones whose patterns match), so a parser's
  // unconditional filterResponseData may arm on the same requestId — keep one
  // filter list per request and feed them all; the parsers' filters no-op on
  // a body that isn't theirs.
  const filters = new Map();
  browser.webRequest.filterResponseData = (requestId) => {
    const f = { ondata: null, onstop: null, onerror: null, write() {}, close() {} };
    (filters.get(requestId) ?? filters.set(requestId, []).get(requestId)).push(f);
    return f;
  };
  const headersReceived = registrations["webRequest.onHeadersReceived"];
  const beforeRequest = registrations["webRequest.onBeforeRequest"];
  const feed = (requestId, body) => {
    const list = filters.get(requestId);
    if (!list || list.length === 0) return false;
    for (const f of list) {
      if (f.ondata) f.ondata({ data: new TextEncoder().encode(body).buffer });
      if (f.onstop) f.onstop();
    }
    return true;
  };
  const settle = () => new Promise((r) => setTimeout(r, 120));
  const mediaEmits = () => nativeSent.filter((s) => s.app === "browser" && s.msg && s.msg.url);
  const base = { tabId: 7, frameId: 3, method: "GET", documentUrl: "https://content.jwplatform.com/players/EvU8KrK5-CvpF1PaY.html",
    originUrl: "https://content.jwplatform.com/players/EvU8KrK5-CvpF1PaY.html" };
  const MASTER = "https://cdn.jwplayer.com/manifests/EvU8KrK5.m3u8";
  const CHILD = "https://videos-cloudfront-usp.jwpsrv.com/6aaba593_sig/sites/vyE59J9e/media/EvU8KrK5/manifest.ism/manifest-audio_0=112084-video_0=783797.m3u8";
  const masterBody = `#EXTM3U\n#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=910000,RESOLUTION=356x642\n${CHILD}\n`;
  const ct = (v, len) => [{ name: "content-type", value: v }, ...(len ? [{ name: "content-length", value: String(len) }] : [])];

  // Master: onHeadersReceived → the sniff listener arms the reader, processResponse emits.
  let before = mediaEmits().length;
  for (const fn of headersReceived) fn({ ...base, requestId: "m1", url: MASTER, type: "xmlhttprequest", statusCode: 200,
    responseHeaders: ct("application/vnd.apple.mpegurl; charset=utf-8", masterBody.length) });
  expect(feed("m1", masterBody), "e2e: master response filter armed (body read for its children)");
  await settle();
  expect(mediaEmits().length === before + 1 && mediaEmits().at(-1).msg.url === MASTER && mediaEmits().at(-1).msg.type === "media",
    "e2e: the master itself is emitted as media");

  // Child: listed by that master, same tab → dropped.
  before = mediaEmits().length;
  for (const fn of headersReceived) fn({ ...base, requestId: "c1", url: CHILD, type: "xmlhttprequest", statusCode: 200,
    responseHeaders: ct("application/vnd.apple.mpegurl", 1366) });
  feed("c1", "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n");
  await settle();
  expect(mediaEmits().length === before, `e2e: rendition playlist listed by the master is NOT emitted (got ${mediaEmits().length - before} emits)`);

  // Same child URL in ANOTHER tab (whose master body was never read) → still captured.
  before = mediaEmits().length;
  for (const fn of headersReceived) fn({ ...base, tabId: 8, requestId: "c2", url: CHILD, type: "xmlhttprequest", statusCode: 200,
    responseHeaders: ct("application/vnd.apple.mpegurl", 1366) });
  feed("c2", "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n");
  await settle();
  expect(mediaEmits().length === before + 1, "e2e: the same child in a tab that never read the master still captures");

  // Redirect hop: a 301 .vtt (JW strips → assets-jpcust) emits nothing.
  before = mediaEmits().length;
  for (const fn of headersReceived) fn({ ...base, requestId: "r1", url: "https://cdn.jwplayer.com/strips/EvU8KrK5-120.vtt", type: "xmlhttprequest",
    statusCode: 301, responseHeaders: [{ name: "location", value: "https://assets-jpcust.jwpsrv.com/strips/EvU8KrK5-120.vtt" }] });
  await settle();
  expect(mediaEmits().length === before, "e2e: a 301 redirect hop is not captured");

  // Redirect TARGET of a parser-owned URL (Acast: the parser emits sphinx's
  // media.mp3, which 302s the <audio> element to a stitched copy on a host
  // no block rule names) → same requestId, so not captured either.
  const SPHINX = "https://sphinx.acast.com/p/open/s/69e1e5256e5b90839adefeaf/e/6ac2cca50d8a484144a38dde/media.mp3";
  const STITCHED = "https://stitch.audio-cdn.example/livestitches/0a1b2c3d.mp3";
  const acastBase = { ...base, documentUrl: "https://embed.acast.com/69e1e5256e5b90839adefeaf/6ac2cca50d8a484144a38dde",
    originUrl: "https://embed.acast.com/69e1e5256e5b90839adefeaf/6ac2cca50d8a484144a38dde" };
  const { __requestRecord: record } = await import(pathToFileURL(join(ext, "js/requests.js")));
  before = mediaEmits().length;
  for (const fn of headersReceived) fn({ ...acastBase, requestId: "a1", url: SPHINX, type: "media",
    statusCode: 302, responseHeaders: [{ name: "location", value: STITCHED }] });
  expect(record("a1")?.parserOwned === true, "e2e: a block-listed redirect hop is remembered on its chain's record");
  for (const fn of headersReceived) fn({ ...acastBase, requestId: "a1", url: STITCHED, type: "media",
    statusCode: 200, responseHeaders: ct("audio/mpeg", 11024927) });
  await settle();
  expect(mediaEmits().length === before, "e2e: the redirect target of a parser-owned URL is not captured");
  // Lifetime: the chain's end forgets it (onCompleted / onErrorOccurred), and
  // a block-listed 200 — no later response can share its id — is never
  // remembered at all (the map used to fill to its cap with those).
  for (const fn of registrations["webRequest.onCompleted"]) fn({ ...acastBase, requestId: "a1", url: STITCHED, statusCode: 200 });
  expect(record("a1") === undefined, "e2e: chain completion drops the record (parser-owned mark included)");
  for (const fn of headersReceived) fn({ ...acastBase, requestId: "a3", url: SPHINX, type: "media",
    statusCode: 302, responseHeaders: [{ name: "location", value: STITCHED }] });
  for (const fn of registrations["webRequest.onErrorOccurred"]) fn({ ...acastBase, requestId: "a3", url: STITCHED, error: "NS_BINDING_ABORTED" });
  expect(record("a3") === undefined, "e2e: an aborted chain drops its record");
  for (const fn of headersReceived) fn({ ...acastBase, requestId: "a4", url: SPHINX, type: "media",
    statusCode: 200, responseHeaders: ct("audio/mpeg", 11024927) });
  await settle();
  expect(!record("a4")?.parserOwned, "e2e: a block-listed 200 (no redirect) is not marked parser-owned");
  // Control: the same target reached WITHOUT the parser-owned hop (another
  // requestId) is ordinary media and still captures — the block is the chain,
  // not the host.
  before = mediaEmits().length;
  for (const fn of headersReceived) fn({ ...acastBase, requestId: "a2", url: STITCHED, type: "media",
    statusCode: 200, responseHeaders: ct("audio/mpeg", 11024927) });
  await settle(); await settle();
  expect(mediaEmits().length === before + 1 && mediaEmits().at(-1).msg.url === STITCHED,
    "e2e: the same target under an unrelated request still captures");

  // Sprite .vtt: onBeforeRequest arms the sniff, the body is xywh cues → dropped.
  before = mediaEmits().length;
  const SPRITE = "https://assets-jpcust.jwpsrv.com/strips/EvU8KrK5-120.vtt";
  for (const fn of beforeRequest) fn({ ...base, requestId: "v1", url: SPRITE, type: "xmlhttprequest" });
  const emitting = headersReceived.map((fn) => fn({ ...base, requestId: "v1", url: SPRITE, type: "xmlhttprequest", statusCode: 200,
    responseHeaders: ct("text/vtt", 200) }));
  expect(feed("v1", "WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nEvU8KrK5-120.jpg#xywh=0,0,120,68\n"), "e2e: .vtt body sniff armed");
  await Promise.all(emitting); await settle();
  expect(mediaEmits().length === before, "e2e: a thumbnail-sprite .vtt is NOT captured as a subtitle");

  // Caption .vtt: text cues → captured as subtitle.
  before = mediaEmits().length;
  const CAPS = "https://cdn.example.net/captions/en.vtt";
  for (const fn of beforeRequest) fn({ ...base, requestId: "v2", url: CAPS, type: "xmlhttprequest" });
  const emitting2 = headersReceived.map((fn) => fn({ ...base, requestId: "v2", url: CAPS, type: "xmlhttprequest", statusCode: 200,
    responseHeaders: ct("text/vtt", 200) }));
  feed("v2", "WEBVTT\n\n1\n00:00:00.000 --> 00:00:04.000\nHola\n");
  await Promise.all(emitting2); await settle();
  const last = mediaEmits().at(-1);
  expect(mediaEmits().length === before + 1 && last.msg.url === CAPS && last.msg.type === "subtitle",
    "e2e: a caption .vtt is still captured as a subtitle");
}

// Response-body readers are BOUNDED (common.js FILTER_BODY_MAX_BYTES): every
// chunk is still written straight through to the page, but a body over the cap
// is not kept — filterResponseText answers null, readFilteredBody makes no
// callback, collectFilteredResponse rejects. They used to buffer every byte and
// only check sizes afterwards (acast.js measured the decoded string), so a
// host-wide pattern held whatever it matched in full, several times over.
{
  const { filterResponseText, readFilteredBody, collectFilteredResponse, FILTER_BODY_MAX_BYTES } =
    await import(pathToFileURL(join(ext, "js/parsers/common.js")));
  const realCreate = browser.webRequest.filterResponseData;
  let lastFilter = null;
  browser.webRequest.filterResponseData = () => {
    const f = { ondata: null, onstop: null, onerror: null, written: 0, closed: false,
      write(d) { this.written += d.byteLength; }, close() { this.closed = true; } };
    lastFilter = f;
    return f;
  };
  const feed = (f, chunks) => {
    for (const c of chunks) f.ondata({ data: c.buffer.slice(0) });
    f.onstop();
  };
  const tick = () => new Promise((r) => setTimeout(r, 10));
  const small = [new TextEncoder().encode('{"a":'), new TextEncoder().encode('1}')];
  const half = Math.ceil(FILTER_BODY_MAX_BYTES / 2) + 1024;
  const big = [new Uint8Array(half), new Uint8Array(half)];   // > cap in total, < cap each
  const bigBytes = half * 2;

  let got = "unset";
  filterResponseText({ requestId: "fc1", url: "https://x.example/a" }, (t) => { got = t; });
  feed(lastFilter, small);
  await tick();
  expect(got === '{"a":1}' && lastFilter.written === 7, "filter-cap: filterResponseText under the cap reads the body");

  got = "unset";
  filterResponseText({ requestId: "fc2", url: "https://x.example/b" }, (t) => { got = t; });
  const f2 = lastFilter;
  feed(f2, big);
  await tick();
  expect(got === null, "filter-cap: filterResponseText over the cap answers null (not the body)");
  expect(f2.written === bigBytes && f2.closed, "filter-cap: an over-cap body is still passed through byte-for-byte");

  let calls = 0;
  readFilteredBody({ requestId: "fc3", url: "https://x.example/c" }, "SMOKE", "cap", () => { calls++; });
  const f3 = lastFilter;
  feed(f3, big);
  await tick();
  expect(calls === 0 && f3.written === bigBytes, "filter-cap: readFilteredBody over the cap makes no callback, still passes through");
  let bodyText = null;
  readFilteredBody({ requestId: "fc4", url: "https://x.example/d" }, "SMOKE", "cap", (t) => { bodyText = t; });
  feed(lastFilter, small);
  await tick();
  expect(bodyText === '{"a":1}', "filter-cap: readFilteredBody under the cap reads the body");

  const over = collectFilteredResponse({ requestId: "fc5", url: "https://x.example/e" });
  const f5 = lastFilter;
  feed(f5, big);
  let rejected = false;
  try { await over; } catch (_) { rejected = true; }
  expect(rejected && f5.written === bigBytes, "filter-cap: collectFilteredResponse over the cap rejects, still passes through");
  const under = collectFilteredResponse({ requestId: "fc6", url: "https://x.example/f" });
  feed(lastFilter, small);
  expect((await under) === '{"a":1}', "filter-cap: collectFilteredResponse under the cap resolves the body");

  browser.webRequest.filterResponseData = realCreate;
}

// Player claims (page-state bridge ↔ generic catcher). One JW embed frame
// declares one clip three ways (the player's HLS master read by the bridge,
// the document's og:video + twitter:player:stream mp4s scraped by the content
// script); the bridge's claim collapses the frame to ONE entity in either
// arrival order, a wire fetch of a claimed rendition is dropped, an unclaimed
// sub-frame report is forwarded once the grace lapses, and a top-frame
// report is never held.
{
  const { __setPlayerClaimGraceMs } = await import(pathToFileURL(join(ext, "js/requests.js")));
  __setPlayerClaimGraceMs(250);
  const onMessage = registrations["runtime.onMessage"];
  const dispatch = (msg, sender) => { for (const l of onMessage) { try { l(msg, sender, () => {}); } catch (e) { console.error("  dispatch threw", e.message); } } };
  const headersReceived = registrations["webRequest.onHeadersReceived"];
  // The content-script path HEAD-probes an unseen URL for headers; there is no
  // network here — fail it fast (the path then forwards header-less, as on a
  // device with the CDN blocked).
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("offline"); };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const emitted = (url) => nativeSent.some((s) => s.app === "browser" && s.msg && s.msg.url === url);
  const TOP = "https://www.lasprovincias.es/comunitat/lluvias.html";
  const sender = (frameId, url) => ({ tab: { id: 9, url: TOP }, frameId, url });
  const ct = (v, len) => [{ name: "content-type", value: v }, { name: "content-length", value: String(len) }];

  // 1) Claim first (the on-device order): the bridge's master + its folded
  //    mp4 sibling, then the scrape reports the sibling AND an alias URL the
  //    bridge never saw — both dropped (url claim, frame claim).
  const F1 = "https://content.jwplatform.com/players/MUcbnbqY-CvpF1PaY.html";
  const M1 = "https://cdn.jwplayer.com/manifests/MUcbnbqY.m3u8";
  const S1 = "https://cdn.jwplayer.com/videos/MUcbnbqY-ypQMtiJ2.mp4";
  const A1 = "https://cdn.jwplayer.com/videos/MUcbnbqY-640.mp4";
  dispatch({ kind: "page-state-hls", payload: { url: M1, origin: F1, title: "6aaaf4bb4d3859992c30a371", siblings: [S1] } }, sender(3, F1));
  await wait(50);
  dispatch({ kind: "images-detected", urls: [S1, A1] }, sender(3, F1));
  await wait(400);
  expect(!emitted(S1), "claim: a scraped mp4 the bridge folded into the master entity is not emitted");
  expect(!emitted(A1), "claim: a scraped mp4 ALIAS from a claimed player frame is not emitted");
  // The player fetching a claimed rendition on play → dropped on the wire too.
  for (const fn of headersReceived) fn({ tabId: 9, frameId: 3, method: "GET", documentUrl: F1, originUrl: F1, requestId: "pw1",
    url: S1, type: "media", statusCode: 206, responseHeaders: ct("video/mp4", 2949348) });
  await wait(150);
  expect(!emitted(S1), "claim: the wire fetching a claimed rendition on play is not emitted");
  // Same URL in ANOTHER tab → its own capture.
  for (const fn of headersReceived) fn({ tabId: 10, frameId: 3, method: "GET", documentUrl: F1, originUrl: F1, requestId: "pw2",
    url: S1, type: "media", statusCode: 206, responseHeaders: ct("video/mp4", 2949348) });
  await wait(150);
  expect(emitted(S1), "claim: the same rendition in a tab with no claim still captures");

  // 2) Scrape first: the report is HELD, the claim lands within the grace,
  //    the report is dropped.
  const F2 = "https://content.jwplatform.com/players/EvU8KrK5-CvpF1PaY.html";
  const A2 = "https://cdn.jwplayer.com/videos/EvU8KrK5-640.mp4";
  dispatch({ kind: "images-detected", urls: [A2] }, sender(4, F2));
  await wait(100);
  expect(!emitted(A2), "claim: a sub-frame video report waits for the frame's player claim");
  dispatch({ kind: "page-state-progressive", payload: { variants: [{ url: "https://cdn.jwplayer.com/videos/EvU8KrK5-ypQMtiJ2.mp4", width: 0, height: 362 }],
    origin: F2, title: "t", siblings: [] } }, sender(4, F2));
  await wait(300);
  expect(!emitted(A2), "claim: a claim landing during the grace drops the held report");

  // 3) No claim: forwarded once the grace lapses.
  const F3 = "https://embed.example.org/player/1";
  const U3 = "https://cdn.example.org/clip-1.mp4";
  dispatch({ kind: "images-detected", urls: [U3] }, sender(5, F3));
  await wait(100);
  expect(!emitted(U3), "claim: an unclaimed sub-frame report is still held inside the grace");
  await wait(350);
  expect(emitted(U3), "claim: an unclaimed sub-frame report is forwarded after the grace");

  // 4) Top-frame report: never held (an article holds many clips).
  const U4 = "https://cdn.example.org/top-clip.mp4";
  dispatch({ kind: "images-detected", urls: [U4] }, sender(0, TOP));
  await wait(100);
  expect(emitted(U4), "claim: a top-frame video report is forwarded at once");

  globalThis.fetch = realFetch;
}

// Host-page caption for a filename-titled embed (requests.js frameCaptions).
// The top frame reports the paragraph beside each iframe; a page-state emit
// from that frame whose title is the upload filename takes it, a real title
// is left alone, and a frame nobody captioned keeps its filename.
{
  const { isFilenameLikeTitle } = await import(pathToFileURL(join(ext, "js/requests.js")));
  expect(isFilenameLikeTitle("6aaaf5204d3859992c30a380"), "filename-like: a bare upload hash");
  expect(isFilenameLikeTitle("6aaaf5204d3859992c30a380.mp4"), "filename-like: a hash with its extension");
  expect(isFilenameLikeTitle("VID-20260916-WA0012"), "filename-like: a phone clip name");
  expect(isFilenameLikeTitle(""), "filename-like: empty");
  expect(!isFilenameLikeTitle("Rescates en Torrent tras la tormenta"), "not filename-like: a sentence");
  expect(!isFilenameLikeTitle("WONDERFUL"), "not filename-like: one plain word");
  expect(!isFilenameLikeTitle("Episode 12"), "not filename-like: a short title with a number");

  const onMessage = registrations["runtime.onMessage"];
  const dispatch = (msg, sender) => { for (const l of onMessage) { try { l(msg, sender, () => {}); } catch (e) { console.error("  dispatch threw", e.message); } } };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const TOP = "https://www.lasprovincias.es/comunitat/lluvias.html";
  const F1 = "https://content.jwplatform.com/players/EvU8KrK5-CvpF1PaY.html";
  const F2 = "https://content.jwplatform.com/players/OLtSoiwG-CvpF1PaY.html";
  const F3 = "https://content.jwplatform.com/players/Ija0lQrE-CvpF1PaY.html";
  const CAP = "En Torrent se vuelve a vivir la pesadilla con rescates de personas de sus vehículos inundados.";
  const parserEmits = () => nativeSent.filter((s) => s.app === "parser" && s.msg && s.msg.name);
  dispatch({ kind: "frame-captions", items: [{ src: F1, title: CAP }, { src: F2, title: "Así, se ven también las calles de Catarroja:" }] },
    { tab: { id: 11, url: TOP }, frameId: 0, url: TOP });
  await wait(30);
  let before = parserEmits().length;
  dispatch({ kind: "page-state-hls", payload: { url: "https://cdn.jwplayer.com/manifests/EvU8KrK5.m3u8", origin: F1, title: "6aaaf5204d3859992c30a380", siblings: [] } },
    { tab: { id: 11, url: TOP }, frameId: 3, url: F1 });
  await wait(150);
  let last = parserEmits().at(-1);
  expect(parserEmits().length === before + 1 && last.msg.name === CAP, `caption: a filename-titled embed takes the host page's caption (got "${last && last.msg.name}")`);
  before = parserEmits().length;
  dispatch({ kind: "page-state-hls", payload: { url: "https://cdn.jwplayer.com/manifests/OLtSoiwG.m3u8", origin: F2, title: "Rescates en Catarroja", siblings: [] } },
    { tab: { id: 11, url: TOP }, frameId: 4, url: F2 });
  await wait(150);
  last = parserEmits().at(-1);
  expect(parserEmits().length === before + 1 && last.msg.name === "Rescates en Catarroja", "caption: an embed with a real title keeps it");
  before = parserEmits().length;
  dispatch({ kind: "page-state-progressive", payload: { variants: [{ url: "https://cdn.jwplayer.com/videos/Ija0lQrE-ypQMtiJ2.mp4", width: 0, height: 362 }], origin: F3, title: "6aaaefff9f832cbde0a47373", siblings: [] } },
    { tab: { id: 11, url: TOP }, frameId: 5, url: F3 });
  await wait(150);
  last = parserEmits().at(-1);
  expect(parserEmits().length === before + 1 && last.msg.name === "6aaaefff9f832cbde0a47373", "caption: a frame nobody captioned keeps its filename title");
  // The caption is per TAB: the same iframe src in another tab is not captioned.
  before = parserEmits().length;
  dispatch({ kind: "page-state-hls", payload: { url: "https://cdn.jwplayer.com/manifests/EvU8KrK5.m3u8", origin: F1, title: "6aaaf5204d3859992c30a380", siblings: [] } },
    { tab: { id: 12, url: TOP }, frameId: 3, url: F1 });
  await wait(150);
  last = parserEmits().at(-1);
  expect(parserEmits().length === before + 1 && last.msg.name === "6aaaf5204d3859992c30a380", "caption: keyed per tab");
}

// snapshotRefererFor — the Referer the archiver's privileged re-fetch carries,
// mirroring what the PAGE's own request sent (Gecko's
// strict-origin-when-cross-origin default): full URL same-origin, origin-only
// cross-origin, nothing for a non-http page. A referer-less fetch is what
// blanked every rasterized page image of a ReadCube ePDF archive.
// ---------------------------------------------------------------------------
{
  const { snapshotRefererFor } = await import(pathToFileURL(join(ext, "js/requests.js")));
  expect(snapshotRefererFor("https://cdn.example.net/p/1.png?sig=x", "https://www.readcube.com/articles/1?t=2#frag")
    === "https://www.readcube.com/", "snapshot referer: cross-origin → origin + '/'");
  expect(snapshotRefererFor("https://www.readcube.com/assets/a.css", "https://www.readcube.com/articles/1?t=2#frag")
    === "https://www.readcube.com/articles/1?t=2", "snapshot referer: same-origin → full URL, fragment dropped");
  expect(snapshotRefererFor("https://cdn.example.net/x", "about:blank") === null,
    "snapshot referer: non-http page → none");
  expect(snapshotRefererFor("https://cdn.example.net/x", undefined) === null,
    "snapshot referer: missing page → none");
}


// ---------------------------------------------------------------------------
// Audit regression net (2026-10): the requests.js hub.
// ---------------------------------------------------------------------------
{
  const { __requestRecord, __snapshotCaptureCount, __listenerStats } = await import(pathToFileURL(join(ext, "js/requests.js")));
  const headersReceived = registrations["webRequest.onHeadersReceived"];
  const responseStarted = registrations["webRequest.onResponseStarted"];
  const onMessage = registrations["runtime.onMessage"];
  const portMessage = registrations["port.onMessage"];
  const settle = (ms = 150) => new Promise((r) => setTimeout(r, ms));
  const emits = () => nativeSent.filter((s) => s.app === "browser" && s.msg && s.msg.url);
  const ct = (v, len) => [{ name: "content-type", value: v }, ...(len ? [{ name: "content-length", value: String(len) }] : [])];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("offline"); };

  // ONE emit per response although processResponse listens on two events.
  const MP4 = "https://media.example/clips/one.mp4";
  const ev = { tabId: 7, frameId: 0, method: "GET", requestId: "dup1", url: MP4, type: "media", statusCode: 200, incognito: false,
    documentUrl: "https://host.example/page", originUrl: "https://host.example/page", responseHeaders: ct("video/mp4", 5000000) };
  let before = emits().length;
  // On a device the second copy lands milliseconds after the first, while the
  // first is parked in its metadata query (a 300 ms race) — so the metadata
  // query is made SLOW here and the second copy arrives a macrotask later.
  // A claim taken late (at the send) would STILL emit once — the copies
  // serialize — but the second copy would run the whole tab + metadata round
  // trip for nothing; the synchronous decide rejects it before any of that,
  // so exactly ONE metadata query is made per chain.
  const realSendMessage = browser.tabs.sendMessage;
  const stats0 = __listenerStats();
  let metaQueries = 0;
  browser.tabs.sendMessage = (tabId, msg) => { if (msg?.kind === "get-page-metadata" && msg.mediaUrl === MP4) metaQueries++; return new Promise((r) => setTimeout(() => r(null), 80)); };
  for (const fn of headersReceived) fn(ev);
  await settle(20);
  for (const fn of responseStarted) fn(ev);
  await settle(200);
  browser.tabs.sendMessage = realSendMessage;
  expect(emits().length === before + 1, `hub: a response seen by onHeadersReceived AND onResponseStarted (20 ms apart, metadata query slow) emits ONCE (got ${emits().length - before})`);
  expect(metaQueries === 1, `hub: the second listener's copy is rejected before any round trip — one metadata query per chain (got ${metaQueries})`);
  let stats = __listenerStats();
  expect(stats.responseStartedSkipped === stats0.responseStartedSkipped + 1 && stats.responseStartedOnly === stats0.responseStartedOnly
      && __requestRecord("dup1")?.decided?.action === "emit",
    "hub: the chain record remembers onHeadersReceived's decision and onResponseStarted returns on it (no second decision)");
  // A response that reaches onResponseStarted ALONE (the belt's reason to exist) still captures, and is counted.
  before = emits().length;
  for (const fn of responseStarted) fn({ ...ev, requestId: "rs-only", url: "https://media.example/clips/started-only.mp4" });
  await settle();
  stats = __listenerStats();
  expect(emits().length === before + 1 && stats.responseStartedOnly === stats0.responseStartedOnly + 1,
    "hub: a response onHeadersReceived never saw is captured by onResponseStarted and counted as started-only");
  for (const fn of registrations["webRequest.onCompleted"]) fn({ requestId: "rs-only", url: "https://media.example/clips/started-only.mp4", statusCode: 200 });
  // A redirect chain is two responses under ONE requestId. The hop's rejection
  // is memoized under the HOP's URL — so if the target's onHeadersReceived is
  // ever missed (the belt's case), the hop's memo must not swallow the target
  // when it reaches onResponseStarted alone.
  const TGT = "https://cdn.example/clips/target.mp4";
  before = emits().length;
  for (const fn of headersReceived) fn({ ...ev, requestId: "rd1", url: "https://media.example/clips/hop.mp4", statusCode: 302,
    responseHeaders: [{ name: "location", value: TGT }] });
  expect(__requestRecord("rd1")?.decided?.reason === "redirect", "hub: the hop's rejection is remembered on the chain record");
  for (const fn of responseStarted) fn({ ...ev, requestId: "rd1", url: TGT });
  await settle();
  expect(emits().length === before + 1 && emits().at(-1).msg.url === TGT,
    "hub: the memo is keyed by URL too — a hop's rejection never swallows the target reaching onResponseStarted alone");
  for (const fn of registrations["webRequest.onCompleted"]) fn({ requestId: "rd1", url: TGT, statusCode: 200 });
  expect(__requestRecord("dup1")?.emittedUrl === MP4, "hub: the emit claim is the chain record's emittedUrl");
  for (const fn of registrations["webRequest.onCompleted"]) fn({ requestId: "dup1", url: MP4, statusCode: 200 });
  expect(__requestRecord("dup1") === undefined, "hub: completion drops the record (emit claim included)");

  // One record per chain: facts recorded by different events (the .vtt arm at
  // onBeforeRequest, the headers at onSendHeaders) land on the SAME record, and
  // the tab closing drops every record of that tab.
  for (const fn of registrations["webRequest.onBeforeRequest"]) fn({ requestId: "v1", url: "https://cdn.example/subs/en.vtt", type: "other", tabId: 77, method: "GET" });
  for (const fn of registrations["webRequest.onSendHeaders"]) fn({ requestId: "v1", url: "https://cdn.example/subs/en.vtt", type: "other", tabId: 77, method: "GET",
    documentUrl: "https://host.example/page", originUrl: "https://host.example/page", requestHeaders: [{ name: "Accept", value: "*/*" }] });
  const v1 = __requestRecord("v1");
  expect(!!v1 && v1.vttVerdict instanceof Promise && v1.sent?.requestHeaders?.length === 1 && v1.tabId === 77,
    "record: the VTT arm and the headers snapshot share one chain record");
  for (const fn of registrations["tabs.onRemoved"]) fn(77);
  expect(__requestRecord("v1") === undefined, "record: closing the tab drops its chain records");

  // A tab that closed while the emit was in flight: dropped, not sent dead.
  const realGet = browser.tabs.get;
  browser.tabs.get = async (id) => { if (id === 99) throw new Error("Invalid tab ID: 99"); return { incognito: false }; };
  before = emits().length;
  for (const fn of headersReceived) fn({ ...ev, tabId: 99, requestId: "gone1", url: "https://media.example/clips/two.mp4" });
  await settle();
  browser.tabs.get = realGet;
  expect(emits().length === before, "hub: a capture whose tab is gone is not sent");

  // The URL header cache is scoped to the browsing mode that filled it.
  const IMG = "https://cdn.example/photos/private-only.jpg";
  for (const fn of registrations["webRequest.onSendHeaders"]) fn({ requestId: "priv1", url: IMG, type: "image", tabId: 20, incognito: true, method: "GET",
    documentUrl: "https://site.example/", originUrl: "https://site.example/",
    requestHeaders: [{ name: "Cookie", value: "sid=private-session" }, { name: "Accept", value: "image/avif,image/webp,*/*" }] });
  const report = (tabId, incognito) => {
    for (const fn of onMessage) fn({ kind: "images-detected", urls: [IMG] },
      { tab: { id: tabId, url: "https://site.example/", incognito }, frameId: 0, url: "https://site.example/" });
  };
  before = emits().length;
  report(21, false);                 // a REGULAR tab's content script reports the same URL
  await settle(400);
  const regular = emits().slice(before).find((s) => s.msg.url === IMG);
  expect(!!regular && !(regular.msg.requestHeaders || []).some((h) => h.name.toLowerCase() === "cookie"),
    "hub: a private tab's cached Cookie never reaches a regular tab's capture");
  before = emits().length;
  report(20, true);                  // the SAME tab that fetched it: its own headers apply
  await settle(400);
  const priv = emits().slice(before).find((s) => s.msg.url === IMG);
  expect(!!priv && (priv.msg.requestHeaders || []).some((h) => h.name.toLowerCase() === "cookie"),
    "hub: the fetching tab's own capture keeps its cached request headers");

  // Snapshot: the privileged fetch + the frame handshake are gated on a LIVE capture in the tab.
  const senderOf = (tabId, frameId = 0) => ({ tab: { id: tabId, url: "https://page.example/" }, frameId, url: "https://page.example/" });
  const ask = async (msg, sender) => {
    for (const fn of onMessage) { const r = fn(msg, sender); if (r && typeof r.then === "function") return await r; }
    return undefined;
  };
  const streamOf = (chunks) => new ReadableStream({ start(c) { for (const ch of chunks) c.enqueue(ch); c.close(); } });
  let fetchCalls = 0;
  globalThis.fetch = async () => { fetchCalls++; return { ok: true, headers: new Headers({ "content-type": "text/css" }), body: streamOf([new TextEncoder().encode("body{}")]) }; };
  let r = await ask({ kind: "snapshot-fetch", url: "https://cdn.example/a.css", as: "text", referrer: "https://page.example/" }, senderOf(5));
  expect(r && r.ok === false && fetchCalls === 0, "snapshot: the fetch is refused (never made) for a tab with no live capture");
  expect((await ask({ kind: "snapshot-frame-allowed" }, senderOf(5, 2))) === false, "snapshot: a frame request is refused with no live capture");
  for (const fn of portMessage) fn({ type: "capture-snapshot", tabId: 5 });   // the popup trigger
  await settle(20);
  expect(__snapshotCaptureCount() === 1, "snapshot: the relayed trigger arms a capture for the tab");
  r = await ask({ kind: "snapshot-fetch", url: "https://cdn.example/a.css", as: "text", referrer: "https://page.example/" }, senderOf(5));
  expect(r && r.ok === true && r.text === "body{}" && fetchCalls === 1, "snapshot: the capturing tab's fetch is served");
  expect((await ask({ kind: "snapshot-frame-allowed" }, senderOf(5, 2))) === true, "snapshot: a child frame of the capturing tab may serialize");
  expect((await ask({ kind: "snapshot-frame-allowed" }, senderOf(6, 2))) === false, "snapshot: another tab's frame may not");
  // Over-cap body: cut off at the cap, never buffered whole.
  let pulled = 0;
  globalThis.fetch = async () => ({ ok: true, headers: new Headers({ "content-type": "application/octet-stream" }),
    body: new ReadableStream({ pull(c) { pulled++; if (pulled > 40) { c.close(); return; } c.enqueue(new Uint8Array(1024 * 1024)); } }) });
  r = await ask({ kind: "snapshot-fetch", url: "https://cdn.example/big.bin", referrer: "https://page.example/" }, senderOf(5));
  expect(r && r.tooBig === true && pulled <= 15, `snapshot: an over-cap body is cut off at the cap (pulled ${pulled} MB chunks, not 40)`);
  // Declared length over the cap: refused before a reader is even taken.
  // (A ReadableStream pulls once on construction to fill its queue, so the
  // reader hand-off — not pull() — is what proves nobody read the body.)
  let readers = 0;
  globalThis.fetch = async () => ({ ok: true, headers: new Headers({ "content-type": "video/mp4", "content-length": String(900 * 1024 * 1024) }),
    body: { getReader() { readers++; return streamOf([new Uint8Array(8)]).getReader(); } } });
  r = await ask({ kind: "snapshot-fetch", url: "https://cdn.example/huge.mp4", referrer: "https://page.example/" }, senderOf(5));
  expect(r && r.tooBig === true && readers === 0, "snapshot: a declared over-cap length is refused without reading the body");
  // Frame relay: a child's archive reaches its PARENT frame's content script only.
  const sent = [];
  const realSend = browser.tabs.sendMessage;
  browser.tabs.sendMessage = async (tabId, msg, opts) => { sent.push({ tabId, msg, opts }); };
  browser.webNavigation.getFrame = async ({ frameId }) => ({ frameId, parentFrameId: frameId === 3 ? 1 : (frameId === 1 ? 0 : -1) });
  await ask({ kind: "snapshot-frame-relay", rid: "r1", phase: "reply", html: "<html>child</html>" }, senderOf(5, 3));
  await settle(30);
  expect(sent.length === 1 && sent[0].tabId === 5 && sent[0].opts.frameId === 1 && sent[0].msg.kind === "snapshot-frame-result"
    && sent[0].msg.rid === "r1" && sent[0].msg.html === "<html>child</html>",
    "snapshot: a child's archive is relayed to its parent frame's content script");
  await ask({ kind: "snapshot-frame-relay", rid: "r2", phase: "reply", html: "x" }, senderOf(6, 3));
  await settle(30);
  expect(sent.length === 1, "snapshot: a frame in a non-capturing tab relays nothing");
  await ask({ kind: "snapshot-done" }, senderOf(5, 0));
  expect(__snapshotCaptureCount() === 0 && (await ask({ kind: "snapshot-frame-allowed" }, senderOf(5, 2))) === false,
    "snapshot: the top frame's done report ends the capture");
  browser.tabs.sendMessage = realSend;
  globalThis.fetch = realFetch;
}

// ---------------------------------------------------------------------------
// Bounded-state primitives (common.js ClaimSet / MetaCache) — the one shape
// every parser claim set and cache is built on. Time is driven by hand.
// ---------------------------------------------------------------------------
{
  const { ClaimSet, MetaCache } = await import(pathToFileURL(join(ext, "js/parsers/common.js")));
  const realNow = Date.now;
  let t = 1_000_000;
  Date.now = () => t;
  try {
    const c = new ClaimSet(1000, 3);
    expect(c.claim("a") === true && c.claim("a") === false, "claimset: check-and-claim is one step (second claim refused)");
    t += 999;
    expect(c.has("a") && c.claim("a") === false, "claimset: a live claim is not refreshed by a refused claim");
    t += 2;
    expect(!c.has("a") && c.claim("a") === true, "claimset: an expired claim can be claimed again");
    c.release("a");
    expect(!c.has("a"), "claimset: release forgets the key");
    const d = new ClaimSet(1000, 3);
    d.add("x"); t += 1; d.add("y"); t += 1; d.add("z"); t += 1; d.add("w");
    expect(d.size === 3 && !d.has("x") && d.has("y") && d.has("w"), "claimset: the FIFO cap evicts the OLDEST once exceeded");
    d.add("y"); t += 1; d.add("v");
    expect(d.has("y") && !d.has("z") && d.has("v"), "claimset: re-adding a key moves it to the tail (recency), so the next eviction takes the stale one");
    const e = new ClaimSet(1000, 3);
    e.add("old1"); e.add("old2"); t += 1500; e.add("fresh1"); e.add("fresh2");
    expect(e.size <= 3 && e.has("fresh1") && e.has("fresh2") && !e.has("old1") && !e.has("old2"),
      "claimset: expired entries are evicted before live ones when the cap is hit");
    expect([...e.keys()].every((k) => k.startsWith("fresh")), "claimset: keys() yields live entries only");
    const f = new ClaimSet(Infinity, 2);
    f.add("p"); t += 10_000_000; f.add("q");
    expect(f.has("p") && f.has("q"), "claimset: an Infinity TTL never expires (FIFO cap only)");
    const g = new ClaimSet(1000, 8);
    expect(g.claim("k", 10) === true, "claimset: per-call TTL accepted");
    t += 11;
    expect(g.claim("k") === true, "claimset: …and honoured over the default");

    const m = new MetaCache(2, 1000);
    m.set("a", { v: 1 }); t += 1; m.set("b", { v: 2 });
    expect(m.get("a")?.v === 1 && m.has("b"), "metacache: set/get");
    m.set("c", { v: 3 });
    expect(m.size === 2 && m.get("a") === undefined && m.get("c")?.v === 3, "metacache: FIFO cap keeps the newest");
    m.set("b", { v: 22 }); m.set("d", { v: 4 });
    expect(m.get("b")?.v === 22 && m.get("c") === undefined, "metacache: set on an existing key moves it to the tail");
    t += 1001;
    expect(m.get("b") === undefined && m.size <= 2, "metacache: a TTL'd entry reads back as absent once expired");
    const n = new MetaCache(8);
    n.set("x", 1); n.set("y", 2); t += 10_000_000;
    expect([...n].map(([k]) => k).join(",") === "x,y", "metacache: no TTL → entries live until the cap; iterator yields [key, value]");
    n.delete("x");
    expect(!n.has("x") && n.has("y"), "metacache: delete");
  } finally {
    Date.now = realNow;
  }
}

// ---------------------------------------------------------------------------
// TabState (tab-state.js) — everything the background remembers ABOUT a tab is
// one container, dropped whole on tabs.onRemoved. Driven through the REAL
// listeners: HLS children, player claims (+ a parked waiter), frame captions,
// the header cache (+ the HEAD probe's filing), the snapshot gate, the parsers'
// per-tab claims and the tab-URL cache.
// ---------------------------------------------------------------------------
{
  const ts = await import(pathToFileURL(join(ext, "js/tab-state.js")));
  const { __setPlayerClaimGraceMs } = await import(pathToFileURL(join(ext, "js/requests.js")));
  const onMessage = registrations["runtime.onMessage"];
  const headersReceived = registrations["webRequest.onHeadersReceived"];
  const sendHeaders = registrations["webRequest.onSendHeaders"];
  const portMessage = registrations["port.onMessage"];
  const removed = registrations["tabs.onRemoved"];
  const closeTab = (id) => { for (const fn of removed) fn(id); };
  const dispatch = (msg, sender) => { for (const l of onMessage) { try { l(msg, sender, () => {}); } catch (e) { console.error("  dispatch threw", e.message); } } };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const ct = (v, len) => [{ name: "content-type", value: v }, ...(len ? [{ name: "content-length", value: String(len) }] : [])];
  const emitsOf = (url) => nativeSent.filter((s) => s.app === "browser" && s.msg && s.msg.url === url);
  const filters = new Map();
  const realFilter = browser.webRequest.filterResponseData;
  browser.webRequest.filterResponseData = (requestId) => {
    const f = { ondata: null, onstop: null, onerror: null, write() {}, close() {} };
    (filters.get(requestId) ?? filters.set(requestId, []).get(requestId)).push(f);
    return f;
  };
  const feed = (requestId, body) => {
    for (const f of filters.get(requestId) ?? []) {
      if (f.ondata) f.ondata({ data: new TextEncoder().encode(body).buffer });
      if (f.onstop) f.onstop();
    }
  };
  const realFetch = globalThis.fetch;
  // No network here: the content-script path's HEAD probe fails fast (the
  // report then forwards header-less) until the header-cache part below
  // installs the probe stub.
  globalThis.fetch = async () => { throw new Error("offline"); };
  try {
    // --- HLS children: per tab, gone with the tab -------------------------
    const PAGE = "https://player.example/embed/1";
    const MASTER = "https://cdn.example/manifests/ts-master.m3u8";
    const CHILD = "https://cdn.example/renditions/ts-master-720.m3u8";
    const req = (tabId, requestId, url, type, extra = {}) => ({ tabId, frameId: 0, method: "GET", documentUrl: PAGE, originUrl: PAGE, requestId, url, type, statusCode: 200, ...extra });
    for (const fn of headersReceived) fn(req(60, "tm1", MASTER, "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 120) }));
    feed("tm1", `#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=910000,RESOLUTION=1280x720\n${CHILD}\n`);
    await wait(150);
    expect(ts.peekTabState(60)?.hlsChildren.has(CHILD) === true, "tabstate: a master's children are recorded in the reading tab's state");
    let n = emitsOf(CHILD).length;
    for (const fn of headersReceived) fn(req(60, "tc1", CHILD, "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 300) }));
    feed("tc1", "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n");
    await wait(150);
    expect(emitsOf(CHILD).length === n, "tabstate: the tab's own child rendition is dropped");
    closeTab(60);
    expect(ts.peekTabState(60) === undefined, "tabstate: closing the tab drops its state");
    n = emitsOf(CHILD).length;
    for (const fn of headersReceived) fn(req(60, "tc2", CHILD, "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 300) }));
    feed("tc2", "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n");
    await wait(150);
    expect(emitsOf(CHILD).length === n + 1, "tabstate: after the close the same child in a reused tab id captures (its master was never read there)");

    // --- -1 wildcard: a master read with no tab covers every tab; a child with
    //     no tab is checked against every tab's masters ----------------------
    const CHILD2 = "https://cdn.example/renditions/anon-480.m3u8";
    for (const fn of headersReceived) fn(req(-1, "tm2", "https://cdn.example/manifests/anon.m3u8", "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 120) }));
    feed("tm2", `#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480\n${CHILD2}\n`);
    await wait(150);
    n = emitsOf(CHILD2).length;
    for (const fn of headersReceived) fn(req(61, "tc3", CHILD2, "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 300) }));
    feed("tc3", "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n");
    await wait(150);
    expect(emitsOf(CHILD2).length === n, "tabstate: a master read without a tab covers every tab's children (-1 wildcard)");
    const CHILD3 = "https://cdn.example/renditions/tab62-360.m3u8";
    for (const fn of headersReceived) fn(req(62, "tm3", "https://cdn.example/manifests/tab62.m3u8", "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 120) }));
    feed("tm3", `#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360\n${CHILD3}\n`);
    await wait(150);
    n = emitsOf(CHILD3).length;
    for (const fn of headersReceived) fn(req(-1, "tc4", CHILD3, "xmlhttprequest", { responseHeaders: ct("application/vnd.apple.mpegurl", 300) }));
    feed("tc4", "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXT-X-ENDLIST\n");
    await wait(150);
    expect(emitsOf(CHILD3).length === n, "tabstate: a child fetched without a tab is checked against every tab's masters (-1 wildcard)");

    // --- Player claims: per tab, gone with the tab; a parked waiter is released
    const F = "https://content.jwplatform.com/players/TabState1-CvpF1PaY.html";
    const R = "https://cdn.jwplayer.com/videos/TabState1-ypQMtiJ2.mp4";
    const sender = (tabId, frameId, url) => ({ tab: { id: tabId, url: "https://host.example/article", incognito: false }, frameId, url });
    dispatch({ kind: "page-state-progressive", payload: { variants: [{ url: R, width: 0, height: 362 }], origin: F, title: "t", siblings: [] } }, sender(63, 3, F));
    await wait(150);
    expect(ts.peekTabState(63)?.claimedUrls.has(R) === true && ts.peekTabState(63)?.claimedFrames.has(F) === true,
      "tabstate: a page-state emit claims its URLs and frame in the emitting tab's state");
    n = emitsOf(R).length;
    for (const fn of headersReceived) fn(req(63, "tp1", R, "media", { statusCode: 206, responseHeaders: ct("video/mp4", 2949348) }));
    await wait(150);
    expect(emitsOf(R).length === n, "tabstate: the wire fetching a claimed rendition in that tab is dropped");
    closeTab(63);
    n = emitsOf(R).length;
    for (const fn of headersReceived) fn(req(63, "tp2", R, "media", { statusCode: 206, responseHeaders: ct("video/mp4", 2949348) }));
    await wait(150);
    expect(emitsOf(R).length === n + 1, "tabstate: after the close the claim is gone with the tab");
    // A sub-frame video report parked on a claim that never comes: the tab
    // closing releases it at once (not at the grace timeout).
    __setPlayerClaimGraceMs(3000);
    const A = "https://cdn.jwplayer.com/videos/TabState2-640.mp4";
    const F2 = "https://content.jwplatform.com/players/TabState2-CvpF1PaY.html";
    n = emitsOf(A).length;
    dispatch({ kind: "images-detected", urls: [A] }, sender(64, 4, F2));
    await wait(80);
    expect(emitsOf(A).length === n && (ts.peekTabState(64)?.claimWaiters.size ?? 0) === 1, "tabstate: a sub-frame video report is parked as a waiter in the tab's state");
    closeTab(64);
    await wait(250);
    // The released waiter lets the report run on (here the tabs.get stub still
    // answers for 64; on a device the dead-tab guard then drops it). A waiter
    // left parked would surface only at the 3 s grace.
    expect(emitsOf(A).length === n + 1, "tabstate: closing the tab releases the parked waiter at once (not at the 3 s grace)");
    __setPlayerClaimGraceMs(250);

    // --- Frame captions: gone with the tab --------------------------------
    const TOP = "https://www.lasprovincias.es/comunitat/ts.html";
    const F3 = "https://content.jwplatform.com/players/TabState3-CvpF1PaY.html";
    const CAP = "Rescates en Torrent tras la tormenta de esta madrugada.";
    const parserEmits = () => nativeSent.filter((s) => s.app === "parser" && s.msg && s.msg.name);
    dispatch({ kind: "frame-captions", items: [{ src: F3, title: CAP }] }, { tab: { id: 65, url: TOP }, frameId: 0, url: TOP });
    await wait(30);
    expect(ts.peekTabState(65)?.frameCaptions.get(F3) === CAP, "tabstate: frame captions live in the tab's state");
    let before = parserEmits().length;
    dispatch({ kind: "page-state-hls", payload: { url: "https://cdn.jwplayer.com/manifests/TabState3.m3u8", origin: F3, title: "6aaaf5204d3859992c30a381", siblings: [] } },
      { tab: { id: 65, url: TOP }, frameId: 3, url: F3 });
    await wait(150);
    expect(parserEmits().length === before + 1 && parserEmits().at(-1).msg.name === CAP, "tabstate: the caption titles the frame's emit");
    closeTab(65);
    before = parserEmits().length;
    dispatch({ kind: "page-state-hls", payload: { url: "https://cdn.jwplayer.com/manifests/TabState3b.m3u8", origin: F3, title: "6aaaf5204d3859992c30a382", siblings: [] } },
      { tab: { id: 65, url: TOP }, frameId: 3, url: F3 });
    await wait(150);
    expect(parserEmits().length === before + 1 && parserEmits().at(-1).msg.name === "6aaaf5204d3859992c30a382",
      "tabstate: after the close the caption AND the tab's sent-origin dedup are gone with the tab (a reused id re-emits the same origin)");

    // --- Header cache: per tab, the probe files under the asking tab ------
    let probes = 0;
    globalThis.fetch = async (url) => {
      probes++;
      for (const fn of sendHeaders) fn({ requestId: `probe-${probes}`, url: String(url), type: "xmlhttprequest", tabId: -1, method: "HEAD",
        documentUrl: "moz-extension://abc/_generated_background_page.html", originUrl: "moz-extension://abc/_generated_background_page.html",
        requestHeaders: [{ name: "Cookie", value: "sid=default-jar" }, { name: "Accept", value: "*/*" }, { name: "Origin", value: "moz-extension://abc" }] });
      return { ok: true, status: 200, headers: new Headers({ "content-type": "image/jpeg", "content-length": "4096" }) };
    };
    const IMG = "https://cdn.example/photos/probe-filed.jpg";
    const report = (tabId, incognito) => dispatch({ kind: "images-detected", urls: [IMG] }, { tab: { id: tabId, url: "https://site.example/", incognito }, frameId: 0, url: "https://site.example/" });
    report(66, false);
    await wait(300);
    let e = emitsOf(IMG).at(-1);
    const names = (x) => (x?.msg?.requestHeaders || []).map((h) => h.name.toLowerCase());
    expect(probes === 1 && ts.peekTabState(66)?.headers.get(IMG)?.fromExtensionContext === true,
      "tabstate: the HEAD probe's headers are filed under the tab that asked for it");
    const origin = (e?.msg?.requestHeaders || []).find((h) => h.name.toLowerCase() === "origin")?.value;
    expect(!!e && names(e).includes("cookie") && names(e).includes("accept") && origin === "https://site.example",
      `tabstate: a regular tab's capture carries the probe's headers, Cookie included, Origin re-stamped to the page's (got ${origin})`);
    report(67, true);
    await wait(300);
    e = emitsOf(IMG).at(-1);
    expect(probes === 2, "tabstate: another tab finds nothing in ITS cache and probes for itself");
    expect(!!e && !names(e).includes("cookie") && names(e).includes("accept"),
      "tabstate: a private tab's capture gets the probe entry without the default jar's Cookie");
    closeTab(66);
    expect(ts.peekTabState(66) === undefined, "tabstate: the header cache goes with the tab");

    // --- Snapshot gate: gone with the tab ----------------------------------
    for (const fn of portMessage) fn({ type: "capture-snapshot", tabId: 68 });
    await wait(20);
    expect(!!ts.peekTabState(68)?.snapshot, "tabstate: the snapshot capture is armed in the tab's state");
    closeTab(68);
    expect(ts.peekTabState(68) === undefined, "tabstate: closing the tab ends its snapshot capture");

    // --- Parser per-tab claims + the tab-URL cache -------------------------
    expect(ts.tabClaims(69, "x", 60_000, 8).claim("a") === true && ts.tabClaims(69, "x", 60_000, 8).claim("a") === false,
      "tabstate: tabClaims is a per-(tab, name) ClaimSet");
    expect(ts.tabClaims(70, "x", 60_000, 8).claim("a") === true, "tabstate: another tab's claims are its own");
    closeTab(69);
    expect(ts.tabClaims(69, "x", 60_000, 8).claim("a") === true, "tabstate: a closed tab's claims are gone");
    const realNow = Date.now;
    let t = 1_700_000_000_000;
    Date.now = () => t;
    try {
      const U = "https://www.twitch.tv/somechannel";
      ts.rememberTabUrl(71, U); t += 10;
      ts.rememberTabUrl(72, U); t += 10;
      expect(ts.tabIdForUrl(U) === 72 && ts.tabIdForUrl("https://www.twitch.tv/somechannel") === 72, "tabstate: tabIdForUrl → the tab that saw the URL most recently");
      closeTab(72);
      expect(ts.tabIdForUrl(U) === 71, "tabstate: a closed tab no longer resolves a URL");
      t += 31_000;
      expect(ts.tabIdForUrl(U) === -1, "tabstate: the tab-URL cache keeps its 30 s TTL");
    } finally {
      Date.now = realNow;
    }
    closeTab(61); closeTab(62); closeTab(67); closeTab(70); closeTab(71);
  } finally {
    browser.webRequest.filterResponseData = realFilter;
    globalThis.fetch = realFetch;
  }
}

// ---------------------------------------------------------------------------
// decideCapture — the catcher's accept/reject table as a synchronous VALUE.
// Every reason is driven directly; the one side effect (a decision to emit
// claims the chain) is pinned, and so is that a rejection never claims.
// ---------------------------------------------------------------------------
{
  const { decideCapture, claimPlayerMedia, __requestRecord } = await import(pathToFileURL(join(ext, "js/requests.js")));
  const headersReceived = registrations["webRequest.onHeadersReceived"];
  const ct = (v, len) => [{ name: "content-type", value: v }, ...(len ? [{ name: "content-length", value: String(len) }] : [])];
  const PAGE = "https://host.example/watch/1";
  const wire = (requestId, url, extra = {}) => ({ requestId, url, type: "media", method: "GET", tabId: 80, frameId: 0, statusCode: 200,
    documentUrl: PAGE, originUrl: PAGE, responseHeaders: ct("video/mp4", 1000), ...extra });
  const decide = (data, listener = "onHeadersReceived", skip = false) => decideCapture(data, listener, skip);
  const filters = new Map();
  const realFilter = browser.webRequest.filterResponseData;
  browser.webRequest.filterResponseData = (requestId) => {
    const f = { ondata: null, onstop: null, onerror: null, write() {}, close() {} };
    (filters.get(requestId) ?? filters.set(requestId, []).get(requestId)).push(f);
    return f;
  };
  try {
    let r = decide(wire("d-ext", "https://media.example/clips/a.mp4", { documentUrl: "moz-extension://abc/bg.html", originUrl: "moz-extension://abc/bg.html" }));
    expect(!(r instanceof Promise) && r.action === "reject" && r.reason === "ext-context", "decide: synchronous; the extension's own probe response is rejected (ext-context)");
    r = decide(wire("d-js", "https://static.example/app.js", { type: "script", responseHeaders: ct("application/javascript", 1000) }));
    expect(r.action === "reject" && r.reason === "classify", "decide: a non-media response fails classification");
    r = decide(wire("d-pb", "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/abc.mp4"));
    expect(r.action === "reject" && r.reason === "classify", "decide: a parser-block-listed URL fails classification");
    r = decide(wire("d-302", "https://media.example/clips/moved.mp4", { statusCode: 302, responseHeaders: [{ name: "location", value: "https://cdn.example/moved.mp4" }, ...ct("video/mp4", 0)] }));
    expect(r.action === "reject" && r.reason === "redirect", "decide: a redirect hop is rejected");
    expect(!__requestRecord("d-302")?.emittedUrl, "decide: a rejected response never claims the chain");
    // An HLS child of a master this tab read (the master goes through the real listener so the reader arms).
    const MASTER = "https://cdn.example/manifests/decide.m3u8";
    const CHILD = "https://cdn.example/renditions/decide-720.m3u8";
    for (const fn of headersReceived) fn(wire("d-m", MASTER, { tabId: 81, type: "xmlhttprequest", responseHeaders: ct("application/vnd.apple.mpegurl", 120) }));
    for (const f of filters.get("d-m") ?? []) { if (f.ondata) f.ondata({ data: new TextEncoder().encode(`#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=910000,RESOLUTION=1280x720\n${CHILD}\n`).buffer }); if (f.onstop) f.onstop(); }
    await new Promise((res) => setTimeout(res, 120));
    r = decide(wire("d-c1", CHILD, { tabId: 81, type: "xmlhttprequest", responseHeaders: ct("application/vnd.apple.mpegurl", 300) }));
    expect(r.action === "reject" && r.reason === "hls-child-of-master", "decide: a rendition listed by a master the tab read is rejected");
    r = decide(wire("d-c2", CHILD, { tabId: 82, type: "xmlhttprequest", responseHeaders: ct("application/vnd.apple.mpegurl", 300) }));
    expect(r.action === "emit" && r.hold === false, "decide: the same rendition in a tab that never read the master emits");
    // Player claims.
    const F = "https://content.jwplatform.com/players/Decide1-CvpF1PaY.html";
    const R = "https://cdn.jwplayer.com/videos/Decide1-ypQMtiJ2.mp4";
    const A = "https://cdn.jwplayer.com/videos/Decide1-640.mp4";
    claimPlayerMedia(83, F, [R]);
    r = decide(wire("d-pc", R, { tabId: 83 }));
    expect(r.action === "reject" && r.reason === "player-claimed-url", "decide: the wire fetching a bridge-claimed rendition is rejected");
    const cs = (requestId, url, extra = {}) => ({ requestId, url, type: "media", method: "GET", tabId: 83, frameId: 3, frameUrl: F,
      documentUrl: PAGE, originUrl: PAGE, responseHeaders: [], ...extra });
    r = decide(cs("cs-d1", A), "contentScript");
    expect(r.action === "reject" && r.reason === "player-claimed-frame", "decide: a sub-frame video report from a frame already claimed is rejected without a wait");
    r = decide(cs("cs-d2", A, { tabId: 84 }), "contentScript");
    expect(r.action === "emit" && r.hold === true, "decide: a sub-frame video report from an unclaimed frame emits after the bounded HOLD");
    r = decide(cs("cs-d3", A, { tabId: 84, frameId: 0, frameUrl: undefined }), "contentScript");
    expect(r.action === "emit" && r.hold === false, "decide: a top-frame report is never held");
    r = decide(cs("cs-d4", "https://cdn.example/audio/intro.mp3", { tabId: 84, responseHeaders: ct("audio/mpeg", 1000) }), "contentScript");
    expect(r.action === "emit" && r.hold === false, "decide: standalone audio in a sub-frame is never held");
    // The dual-listener claim: the second copy of one chain is rejected, synchronously.
    r = decide(wire("d-dup", "https://media.example/clips/dup.mp4"));
    const r2 = decide(wire("d-dup", "https://media.example/clips/dup.mp4"), "onResponseStarted");
    expect(r.action === "emit" && r2.action === "reject" && r2.reason === "already-emitted" && __requestRecord("d-dup")?.emittedUrl === "https://media.example/clips/dup.mp4",
      "decide: a decision to emit claims the chain; the other listener's copy is rejected");
    r = decide(wire("d-sniff", "https://cdn.example/play/stream", { type: "xmlhttprequest", responseHeaders: ct("text/html", 500) }), "manifestSniff", true);
    expect(r.action === "emit", "decide: skipClassify (the manifest body-sniff's proven manifest) bypasses the classifier only");
  } finally {
    browser.webRequest.filterResponseData = realFilter;
  }
}

// ---------------------------------------------------------------------------
// Bounded under load, and nothing outlives what produced it — the whole point
// of the 2026-10 restructuring, driven through the REAL listeners: a flood of
// open chains across five tabs stays under the record cap and still emits each
// media response ONCE; completing the chains empties the records; closing the
// tabs drops every TabState the flood created; per-tab caches hold their caps.
// ---------------------------------------------------------------------------
{
  const { __requestRecordCount, __requestRecord, __listenerStats } = await import(pathToFileURL(join(ext, "js/requests.js")));
  const ts = await import(pathToFileURL(join(ext, "js/tab-state.js")));
  const sendHeaders = registrations["webRequest.onSendHeaders"];
  const headersReceived = registrations["webRequest.onHeadersReceived"];
  const responseStarted = registrations["webRequest.onResponseStarted"];
  const completed = registrations["webRequest.onCompleted"];
  const removed = registrations["tabs.onRemoved"];
  const onMessage = registrations["runtime.onMessage"];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("offline"); };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const ct = (v) => [{ name: "content-type", value: v }, { name: "content-length", value: "4096" }];
  const TABS = [90, 91, 92, 93, 94];
  const PAGE = (t) => `https://flood.example/tab${t}/`;
  const N = 3000;
  // Earlier sections leave a few never-completed chains behind; the flood's
  // FIFO cap may evict them, so the after-completion bound is "at most the
  // baseline", and the flood's own ids are checked by name.
  const records0 = __requestRecordCount();
  const states0 = ts.__tabStateCount();
  const mediaEmits0 = nativeSent.filter((s) => s.app === "browser" && s.msg && /flood\.example\/clips\//.test(s.msg.url || "")).length;
  try {
    for (let i = 0; i < N; i++) {
      const tab = TABS[i % TABS.length];
      const kind = i % 3;   // 0 image, 1 script (rejected), 2 media (emits)
      const url = kind === 0 ? `https://cdn.flood.example/img/${i}.jpg`
        : kind === 1 ? `https://cdn.flood.example/js/${i}.js`
        : `https://cdn.flood.example/clips/${i}.mp4`;
      const type = kind === 0 ? "image" : kind === 1 ? "script" : "media";
      const base = { requestId: `fl${i}`, url, type, method: "GET", tabId: tab, frameId: 0, incognito: false,
        documentUrl: PAGE(tab), originUrl: PAGE(tab) };
      for (const fn of sendHeaders) fn({ ...base, requestHeaders: [{ name: "Cookie", value: `sid=${tab}` }, { name: "Accept", value: "*/*" }] });
      const resp = { ...base, statusCode: 200, responseHeaders: ct(kind === 0 ? "image/jpeg" : kind === 1 ? "application/javascript" : "video/mp4") };
      for (const fn of headersReceived) fn(resp);
      for (const fn of responseStarted) fn(resp);
    }
    await wait(600);
    const open = __requestRecordCount();
    expect(open <= 1024 && open > records0, `leak: ${N} open chains stay under the record cap (records=${open})`);
    const mediaEmits = nativeSent.filter((s) => s.app === "browser" && s.msg && /flood\.example\/clips\//.test(s.msg.url || "")).length - mediaEmits0;
    expect(mediaEmits === N / 3, `leak: under the flood every media response emitted exactly once (${mediaEmits} of ${N / 3})`);
    expect(TABS.every((t) => (ts.peekTabState(t)?.headers.size ?? 999) <= 512), "leak: the per-tab header cache holds its 512 cap under 600 requests per tab");
    for (let i = 0; i < N; i++) for (const fn of completed) fn({ requestId: `fl${i}`, url: "https://cdn.flood.example/x", statusCode: 200, tabId: TABS[i % TABS.length] });
    expect(__requestRecordCount() <= records0 && __requestRecord("fl0") === undefined && __requestRecord("fl1500") === undefined && __requestRecord(`fl${N - 1}`) === undefined,
      `leak: completing every chain empties the flood's records (total=${__requestRecordCount()}, baseline=${records0})`);
    // The content-script scrape dedup: 1500 distinct URLs into one tab, FIFO-capped.
    for (let b = 0; b < 15; b++) {
      const urls = Array.from({ length: 100 }, (_, k) => `https://cdn.flood.example/scrape/${b * 100 + k}.jpg`);
      for (const fn of onMessage) { try { fn({ kind: "images-detected", urls }, { tab: { id: 90, url: PAGE(90), incognito: false }, frameId: 0, url: PAGE(90) }, () => {}); } catch (_) {} }
    }
    await wait(800);
    expect((ts.peekTabState(90)?.scraped.size ?? 999) <= 1024, `leak: the per-tab scrape dedup holds its 1024 cap after 1500 reports (size=${ts.peekTabState(90)?.scraped.size})`);
    for (const t of TABS) for (const fn of removed) fn(t);
    expect(ts.__tabStateCount() === states0, `leak: closing the flood's tabs drops every TabState it created (left=${ts.__tabStateCount() - states0})`);
    expect(TABS.every((t) => ts.peekTabState(t) === undefined), "leak: no closed tab keeps a state");
    const stats = __listenerStats();
    expect(stats.responseStartedSkipped >= N - 100, `leak: the second listener's copies were skipped on the memo, not re-decided (skipped=${stats.responseStartedSkipped})`);
  } finally {
    globalThis.fetch = realFetch;
  }
}

// A URL whose page request a blocker refused is never requested again by the
// page-scan path (requests.js images-detected): no HEAD probe — it runs in
// extension context, which uBlock cannot see — and no forward, so the native
// probe doesn't fetch it either. On-device case: TikTok's monitor/collect
// beacon, refused with NS_ERROR_ABORT, then HEAD-probed with cookies.
{
  const ts = await import(pathToFileURL(join(ext, "js/tab-state.js")));
  const onMessage = registrations["runtime.onMessage"];
  const errorOccurred = registrations["webRequest.onErrorOccurred"];
  const removed = registrations["tabs.onRemoved"];
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const probed = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url) => { probed.push(String(url)); throw new Error("offline"); };
  const emitted = (url) => nativeSent.some((s) => s.app === "browser" && s.msg && s.msg.url === url);
  const PAGE = "https://www.tiktok.example/";
  const report = (tabId, urls) => {
    for (const fn of onMessage) {
      try { fn({ kind: "images-detected", urls }, { tab: { id: tabId, url: PAGE, incognito: false }, frameId: 0, url: PAGE }, () => {}); } catch (_) {}
    }
  };
  const fail = (tabId, url, error) => {
    for (const fn of errorOccurred) fn({ requestId: `blk-${tabId}-${url}`, url, tabId, type: "image", error });
  };
  try {
    const BEACON = "https://www.tiktok.example/node/extra/api/monitor/collect?event=t0";
    const PHOTO = "https://cdn.tiktok.example/avatar/1.jpeg";
    const TRACKER = "https://pixel.tracker.example/p.gif";
    const CANCELLED = "https://cdn.tiktok.example/lazy/2.jpeg";
    fail(81, BEACON, "NS_ERROR_ABORT");               // uBlock's cancel
    fail(81, TRACKER, "NS_ERROR_TRACKING_URI");       // Gecko tracking protection
    fail(81, CANCELLED, "NS_BINDING_ABORTED");        // the page's own abort — NOT a block
    report(81, [BEACON, TRACKER, PHOTO, CANCELLED]);
    await wait(150);
    expect(!probed.includes(BEACON) && !emitted(BEACON), "blocked: a uBlock-refused URL is neither HEAD-probed nor forwarded");
    expect(!probed.includes(TRACKER) && !emitted(TRACKER), "blocked: a tracking-protection-refused URL is neither HEAD-probed nor forwarded");
    expect(probed.includes(PHOTO) && emitted(PHOTO), "blocked: an unblocked image in the same report still probes and forwards");
    expect(probed.includes(CANCELLED) && emitted(CANCELLED), "blocked: a page-aborted request (NS_BINDING_ABORTED) is not treated as blocked");
    // Per tab: the same beacon reported by a tab whose page never had it
    // refused is that tab's ordinary capture.
    report(82, [BEACON]);
    await wait(150);
    expect(probed.includes(BEACON) && emitted(BEACON), "blocked: another tab with no refusal still captures the URL");
    // Our own probe failing (extension context, tab -1) blocks nothing.
    const PROBE_FAIL = "https://cdn.tiktok.example/probe/3.jpeg";
    fail(-1, PROBE_FAIL, "NS_ERROR_ABORT");
    report(83, [PROBE_FAIL]);
    await wait(150);
    expect(emitted(PROBE_FAIL), "blocked: a failure with no tab (our own HEAD probe) blocks nothing");
    for (const fn of removed) fn(81);
    expect(ts.peekTabState(81) === undefined, "blocked: the blocked set goes with the tab");
  } finally {
    globalThis.fetch = realFetch;
    for (const fn of removed) { fn(82); fn(83); }
  }
}

if (failures) {
  console.error(`\n${failures} failure(s)`);
  process.exit(1);
}

console.log("\nsmoke: all checks passed");
process.exit(0);
