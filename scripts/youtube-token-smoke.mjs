// Runs the REAL youtube/background.js in a vm and drives the parts that take
// the page player's PO token and the page's own watch answer:
//   - the protobuf reader and the base64url encoder;
//   - the stream-request listener (which requests yield a token, when it is
//     sent to the native side, what is ignored);
//   - the watch-answer interceptor (get_watch's list, /player's object, bad
//     bodies) and the stream id it remembers;
//   - the wait for the page's own watch answer.
//
//   node scripts/youtube-token-smoke.mjs [path/to/background.js]
//
// No network. processVideo itself is not driven here.
import fs from "node:fs";
import vm from "node:vm";

const path = process.argv[2] || new URL("../app/src/main/assets/youtube/background.js", import.meta.url).pathname;
const src = fs.readFileSync(path, "utf8");

const ev = () => { const listeners = []; return { listeners,
  addListener(fn, filter, extra) { listeners.push({ fn, filter, extra }); }, removeListener() {}, hasListener() { return false; } }; };
const native = [];
const filters = [];
const browser = {
  webRequest: { onBeforeRequest: ev(), onBeforeSendHeaders: ev(), onHeadersReceived: ev(), onSendHeaders: ev(),
    onCompleted: ev(), onErrorOccurred: ev(),
    filterResponseData() { const f = { written: [], closed: false, write(d) { this.written.push(d); }, close() { this.closed = true; } }; filters.push(f); return f; } },
  tabs: { onUpdated: ev(), onRemoved: ev(), onActivated: ev(), query: async () => [], get: async () => ({}) },
  runtime: { onMessage: ev(), onMessageExternal: ev(), onConnect: ev(), getURL: (p) => p,
    sendNativeMessage: async (_app, msg) => { if (msg && msg.kind === "get-debug-flag") return true; native.push(msg); return null; },
    connectNative() { return { onMessage: ev(), onDisconnect: ev(), postMessage() {}, disconnect() {} }; } },
  storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
  cookies: { getAll: async () => [] },
  webNavigation: { onCommitted: ev(), onHistoryStateUpdated: ev(), onCompleted: ev() },
};
const ctx = { browser, console: { log() {}, warn() {}, error() {}, info() {}, debug() {} },
  fetch: async () => { throw new Error("offline"); },
  URL, URLSearchParams, TextDecoder, TextEncoder, AbortSignal, AbortController, atob, btoa,
  setTimeout, clearTimeout, setInterval: (fn, ms) => { const h = setInterval(fn, ms); h.unref(); return h; }, clearInterval };
vm.createContext(ctx);
vm.runInContext(src + `\n;globalThis.__t = { protoBytesField, base64Url, rememberStreamId, streamIdToVideo,
  waitForTabWatchResponse, watchRequestStarted, watchRequestEnded, takeInterceptedResponse, watchPending };`, ctx);
const T = ctx.__t;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ok = 0, bad = 0;
const check = (name, cond, detail) => {
  if (cond) { ok++; console.log("PASS", name); } else { bad++; console.log("FAIL", name, detail ?? ""); }
};

// ── a protobuf writer of our own, to build what the page would send ──────
const varint = (n) => { const o = []; let v = n >>> 0; do { let b = v & 0x7f; v >>>= 7; if (v) b |= 0x80; o.push(b); } while (v); return o; };
const len = (field, bytes) => [...varint((field << 3) | 2), ...varint(bytes.length), ...bytes];
const vint = (field, n) => [...varint(field << 3), ...varint(n)];
const fix32 = (field) => [...varint((field << 3) | 5), 1, 2, 3, 4];
const fix64 = (field) => [...varint((field << 3) | 1), 1, 2, 3, 4, 5, 6, 7, 8];
const u8 = (a) => Uint8Array.from(a);
const token = (n, fill = 0x41) => Array.from({ length: n }, (_, i) => (fill + i) & 0xff);
// A stream request body: some state, then streamer context (19) holding
// client info (1), the token (2) and a playback cookie (3).
const sabrBody = (tok, { withToken = true, pad = 0 } = {}) => u8([
  ...vint(1, 7), ...len(2, token(10)), ...fix32(4), ...fix64(6), ...len(5, token(pad)),
  ...len(19, [...len(1, token(30)), ...(withToken ? len(2, tok) : []), ...len(3, [])]),
]);

await sleep(10);

// 1. the reader
{
  const t = token(85);
  const body = sabrBody(t);
  const st = T.protoBytesField(body, 19);
  const got = st && T.protoBytesField(st, 2);
  check("reader: finds the token under 19.2 past varint, fixed32 and fixed64 fields",
    got && got.length === 85 && got[0] === t[0] && got[84] === t[84]);
  check("reader: a field that is absent is null", T.protoBytesField(body, 77) === null);
  check("reader: an empty field is an empty array, not null", T.protoBytesField(st, 3)?.length === 0);
  check("reader: a truncated body is null", T.protoBytesField(body.subarray(0, body.length - 20), 19) === null);
  check("reader: a length running past the end is null", T.protoBytesField(u8([...varint((19 << 3) | 2), 100, 1, 2]), 19) === null);
  check("reader: an unknown wire type is null", T.protoBytesField(u8([(1 << 3) | 3, 0, ...len(19, [1])]), 19) === null);
  check("reader: an empty body is null", T.protoBytesField(u8([]), 19) === null);
  check("reader: a field number above 15 (two-byte key) is found", T.protoBytesField(u8(len(300, [9, 9])), 300)?.length === 2);
  check("reader: the first of two same-numbered fields is the one returned",
    T.protoBytesField(u8([...len(19, [1]), ...len(19, [2, 2])]), 19)?.length === 1);
}

// 2. the encoder
{
  const e = T.base64Url(u8([0xfb, 0xff, 0xfe, 0x00]));
  check("encoder: url-safe alphabet, no padding", e === "-__-AA", e);
  const t = token(85);
  const round = Buffer.from(T.base64Url(u8(t)), "base64url");
  check("encoder: 85 bytes round-trip to 114 characters", T.base64Url(u8(t)).length === 114 && round.equals(Buffer.from(t)));
  check("encoder: empty input is an empty string", T.base64Url(u8([])) === "");
}

// 3. the stream-request listener
const streamL = browser.webRequest.onBeforeRequest.listeners.find((l) => l.filter?.urls?.some((u) => u.includes("googlevideo")));
check("listener: registered for googlevideo stream requests, with the request body, not blocking",
  !!streamL && streamL.extra.includes("requestBody") && !streamL.extra.includes("blocking"));
const url = (id) => `https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&id=${id}&sabr=1`;
const req = (id, body, over = {}) => ({ method: "POST", tabId: 3, url: url(id),
  requestBody: body ? { raw: [{ bytes: body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) }] } : undefined, ...over });
const sent = () => native.filter((m) => m.kind === "player-potoken");
{
  T.rememberStreamId(url("o-AAA"), "vidAAAAAAAA");
  const t1 = token(85, 0x10), t2 = token(85, 0x40);
  streamL.fn(req("o-AAA", sabrBody(t1)));
  await sleep(0);
  check("listener: a known stream's token goes to the native side for its video",
    sent().length === 1 && sent()[0].videoId === "vidAAAAAAAA"
    && Buffer.from(sent()[0].token, "base64url").equals(Buffer.from(t1)), JSON.stringify(sent()));
  streamL.fn(req("o-AAA", sabrBody(t1)));
  check("listener: the same token again is not sent twice", sent().length === 1);
  streamL.fn(req("o-AAA", sabrBody(t2)));
  check("listener: a new token for the video is sent", sent().length === 2 && Buffer.from(sent()[1].token, "base64url")[0] === 0x40);
  const n = sent().length;
  streamL.fn(req("o-ZZZ", sabrBody(token(85, 0x50))));
  check("listener: a stream of an unknown video is ignored", sent().length === n);
  streamL.fn(req("o-AAA", sabrBody(token(85, 0x51)), { method: "GET" }));
  check("listener: a GET is ignored", sent().length === n);
  streamL.fn(req("o-AAA", sabrBody(token(85, 0x52)), { tabId: -1 }));
  check("listener: a request from no tab (our own) is ignored", sent().length === n);
  streamL.fn(req("o-AAA", null));
  check("listener: a request with no body is ignored", sent().length === n);
  streamL.fn(req("o-AAA", sabrBody(token(85, 0x53), { withToken: false })));
  check("listener: a body with no token is ignored", sent().length === n);
  streamL.fn(req("o-AAA", sabrBody(token(8, 0x54))));
  check("listener: a token too short to be one is ignored", sent().length === n);
  streamL.fn(req("o-AAA", sabrBody(token(85, 0x55), { pad: 70000 })));
  check("listener: an oversized body is not read", sent().length === n);
  streamL.fn(req("o-AAA", u8([1, 2, 3, 250, 250, 250])));
  check("listener: a body that is not protobuf is ignored", sent().length === n);
  streamL.fn({ method: "POST", tabId: 3, url: "not a url", requestBody: { raw: [] } });
  check("listener: a malformed URL does not throw", sent().length === n);
  // split across two raw parts, as a browser may hand it over
  const whole = sabrBody(token(85, 0x60));
  const a = whole.slice(0, 40), b = whole.slice(40);
  streamL.fn({ method: "POST", tabId: 3, url: url("o-AAA"), requestBody: { raw: [{ bytes: a.buffer }, { file: "x" }, { bytes: b.buffer }] } });
  check("listener: a body in several parts is joined", sent().length === n + 1 && Buffer.from(sent()[n].token, "base64url")[0] === 0x60);
  // two videos at once keep their own tokens
  T.rememberStreamId(url("o-BBB"), "vidBBBBBBBB");
  streamL.fn(req("o-BBB", sabrBody(token(85, 0x70))));
  check("listener: a second video's token is filed under that video", sent().at(-1).videoId === "vidBBBBBBBB");
  streamL.fn(req("o-BBB", sabrBody(token(85, 0x60))));
  check("listener: the same bytes for another video are still sent (dedup is per video)", sent().at(-1).videoId === "vidBBBBBBBB" && sent().length === n + 3);
}

// 4. the stream-id map is bounded
{
  for (let i = 0; i < 40; i++) T.rememberStreamId(url("o-N" + i), "vidN" + i);
  check("map: bounded at 32, oldest dropped", T.streamIdToVideo.size === 32 && !T.streamIdToVideo.has("o-AAA") && T.streamIdToVideo.has("o-N39"));
  T.rememberStreamId("", "x"); T.rememberStreamId("garbage", "x"); T.rememberStreamId(url(""), "x");
  check("map: a URL with no id adds nothing", T.streamIdToVideo.size === 32);
}

// 5. the watch-answer interceptor
const watchL = browser.webRequest.onBeforeRequest.listeners.find((l) => l.filter?.urls?.some((u) => u.includes("get_watch")));
check("interceptor: matches get_watch and /player", !!watchL && watchL.filter.urls.some((u) => u.includes("/player")));
const player = (vid, id, status = "OK") => ({ playabilityStatus: { status }, videoDetails: { videoId: vid },
  streamingData: { serverAbrStreamingUrl: url(id), adaptiveFormats: [{ itag: 137 }], videoPlaybackUstreamerConfig: "cfg" } });
const feed = async (bodyText, tabId = -1, how = "stop") => {
  filters.length = 0;
  watchL.fn({ method: "POST", tabId, requestId: "r" + Math.random(), url: "https://m.youtube.com/youtubei/v1/get_watch" });
  const f = filters[0];
  const bytes = new TextEncoder().encode(bodyText);
  f.ondata({ data: bytes.slice(0, 10).buffer }); f.ondata({ data: bytes.slice(10).buffer });
  if (how === "stop") f.onstop(); else f.onerror();
  return f;
};
{
  const f = await feed(JSON.stringify([{ responseContext: { visitorData: "VD1" }, playerResponse: player("vidWATCH001", "o-W1") }, { watchNextResponse: {} }]));
  check("interceptor: the body is passed through to the page untouched and the filter closed", f.written.length === 2 && f.closed);
  const got = T.takeInterceptedResponse("vidWATCH001");
  check("interceptor: get_watch's list yields the player response", got?.videoDetails?.videoId === "vidWATCH001");
  check("interceptor: ...with the list part's visitor data carried over", got?.responseContext?.visitorData === "VD1");
  check("interceptor: ...and its stream id remembered for the token", T.streamIdToVideo.get("o-W1") === "vidWATCH001");
  await feed(JSON.stringify(player("vidPLAYER01", "o-P1")));
  check("interceptor: /player's plain object still works", T.takeInterceptedResponse("vidPLAYER01")?.videoDetails?.videoId === "vidPLAYER01");
  await feed(JSON.stringify({ playerResponse: player("vidWRAPPED1", "o-R1") }));
  check("interceptor: an object holding playerResponse works", T.takeInterceptedResponse("vidWRAPPED1") !== null);
  await feed(JSON.stringify([{ playerResponse: player("vidBLOCKED1", "o-X1", "LOGIN_REQUIRED") }]));
  check("interceptor: a response that is not playable is not kept", T.takeInterceptedResponse("vidBLOCKED1") === null && !T.streamIdToVideo.has("o-X1"));
  await feed(JSON.stringify([{ watchNextResponse: {} }]));
  await feed("not json at all");
  await feed("[]");
  check("interceptor: lists without a player response and bad bodies are ignored without throwing", true);
  filters.length = 0;
  watchL.fn({ method: "GET", tabId: 1, requestId: "g", url: "https://m.youtube.com/youtubei/v1/get_watch" });
  check("interceptor: a GET is left alone", filters.length === 0);
}

// 6. waiting for the page's own watch answer
{
  let t0 = Date.now();
  await T.waitForTabWatchResponse(-1);
  check("wait: no tab, no wait", Date.now() - t0 < 50);
  t0 = Date.now();
  await T.waitForTabWatchResponse(21);
  let dt = Date.now() - t0;
  check("wait: a tab that makes no watch request is given 1.5 s, then the old path", dt >= 1400 && dt < 2200, dt + "ms");
  // the request is already in flight
  T.watchRequestStarted(22);
  t0 = Date.now();
  setTimeout(() => T.watchRequestEnded(22), 200);
  await T.waitForTabWatchResponse(22);
  dt = Date.now() - t0;
  check("wait: an answer in flight is waited for and no longer", dt >= 150 && dt < 600, dt + "ms");
  check("wait: ...and the pending entry is gone", !T.watchPending.has(22));
  // the request starts after we began waiting
  t0 = Date.now();
  setTimeout(() => T.watchRequestStarted(23), 300);
  setTimeout(() => T.watchRequestEnded(23), 500);
  await T.waitForTabWatchResponse(23);
  dt = Date.now() - t0;
  check("wait: a request that starts while waiting is picked up", dt >= 450 && dt < 1000, dt + "ms");
  // two waiters on one tab
  T.watchRequestStarted(24);
  setTimeout(() => T.watchRequestEnded(24), 150);
  t0 = Date.now();
  await Promise.all([T.waitForTabWatchResponse(24), T.waitForTabWatchResponse(24)]);
  check("wait: two waiters on one tab both return", Date.now() - t0 < 600);
  // the interceptor ends the wait on every exit
  for (const [how, body, name] of [["stop", JSON.stringify([{ playerResponse: player("v1", "o-1", "ERROR") }]), "an unplayable answer"],
      ["stop", "garbage", "an unreadable answer"], ["error", "x".repeat(20), "a failed read"]]) {
    const tab = 30 + Math.floor(Math.random() * 1000);
    const p = (async () => { filters.length = 0; })();
    await p;
    watchL.fn({ method: "POST", tabId: tab, requestId: "w" + tab, url: "https://m.youtube.com/youtubei/v1/get_watch" });
    const f = filters[0];
    const waiter = T.waitForTabWatchResponse(tab);
    t0 = Date.now();
    f.ondata({ data: new TextEncoder().encode(body).buffer });
    if (how === "stop") f.onstop(); else f.onerror();
    await waiter;
    check(`wait: ${name} ends the wait at once`, Date.now() - t0 < 300 && !T.watchPending.has(tab));
  }
  // a closed tab releases its waiter
  T.watchRequestStarted(40);
  const w = T.waitForTabWatchResponse(40);
  t0 = Date.now();
  for (const l of browser.tabs.onRemoved.listeners) l.fn(40);
  await w;
  check("wait: closing the tab ends the wait", Date.now() - t0 < 300 && !T.watchPending.has(40));
}

console.log(`${ok} passed, ${bad} failed`);
process.exit(bad ? 1 : 0);
