// Smoke test for the p2pshare engine page script — run with:
//   node scripts/p2pshare-smoke.mjs
//
// Loads assets/p2pshare/engine-page.js in a vm context under a stubbed
// `window` (the engine is now a PAGE-WORLD script that talks to the bridge
// content script via window.postMessage, not a native port). Exercises the
// __init handshake, the soft bad-code path, the FDS1./FDR1. code codec, and
// the stop teardown. The WebRTC/DataChannel halves can only be proven
// on-device — this guards the protocol plumbing around them.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app/src/main/assets/p2pshare/engine-page.js"), "utf8");

let failures = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log(`ok    ${name}`);
  } else {
    failures++;
    console.log(`FAIL  ${name}${detail ? " — " + detail : ""}`);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ── stubs ──────────────────────────────────────────────────────────────────
const messageListeners = [];
const postedEvents = []; // engine "evt" payloads (what the bridge would relay)
let helloSeen = false;

const location = { origin: "http://127.0.0.1:53535", pathname: "/engine" };

const windowStub = {
  location,
  addEventListener: (type, fn) => {
    if (type === "message") { messageListeners.push(fn); }
  },
  postMessage: (msg) => {
    if (!msg) { return; }
    if (msg.p2p === "hello") { helloSeen = true; }
    else if (msg.p2p === "evt") { postedEvents.push(msg.data); }
  },
};

const context = vm.createContext({
  window: windowStub,
  location,
  console,
  setTimeout,
  clearTimeout,
  TextEncoder,
  TextDecoder,
  Blob,
  Response,
  CompressionStream,
  DecompressionStream,
  btoa: (text) => Buffer.from(text, "binary").toString("base64"),
  atob: (text) => Buffer.from(text, "base64").toString("binary"),
  // RTCPeerConnection deliberately ABSENT — the plumbing paths under test
  // don't construct one; the real thing only works on-device.
});

vm.runInContext(source, context, { filename: "engine-page.js" });

// Deliver a Java->page command exactly as the bridge would.
const sendCommand = (cmd) =>
  messageListeners.forEach((fn) => fn({ source: windowStub, data: { p2p: "cmd", data: cmd } }));

// ── load + __init handshake ─────────────────────────────────────────────────
check("engine says hello on load", helloSeen === true);
check("engine registered a message listener", messageListeners.length === 1);
check("no events before init", postedEvents.length === 0);

sendCommand({ type: "__init", debug: false });
check("__init triggers ready", postedEvents.length === 1 && postedEvents[0].type === "ready");

// ── bad-code handling (soft error, no session) ──────────────────────────────
postedEvents.length = 0;
sendCommand({ type: "recv-start", code: "garbage", stun: "" });
await sleep(50);
check("bad offer code -> bad-code error",
    postedEvents.length === 1 && postedEvents[0].type === "error" && postedEvents[0].code === "bad-code");

// ── offer-parsed carries the answer-return URL (single-scan flow) ──────────
postedEvents.length = 0;
const offerWithAns = await vm.runInContext(`
  encodeCode(OFFER_PREFIX, { v: 1, sdp: "v=0\\r\\n", name: "a.bin", size: 5,
      mime: "application/octet-stream", dev: "Test",
      ans: "http://192.168.1.2:40000/answer?t=aa" })
`, context);
sendCommand({ type: "recv-start", code: offerWithAns, stun: "" });
await sleep(50);
check("offer-parsed surfaces ans",
    postedEvents.length === 1 && postedEvents[0].type === "offer-parsed"
        && postedEvents[0].ans === "http://192.168.1.2:40000/answer?t=aa"
        && postedEvents[0].name === "a.bin");
// A pre-single-scan offer (no ans field) must surface an EMPTY ans, not fail.
postedEvents.length = 0;
const offerNoAns = await vm.runInContext(`
  encodeCode(OFFER_PREFIX, { v: 1, sdp: "v=0\\r\\n", name: "b.bin", size: 5,
      mime: "application/octet-stream", dev: "Test" })
`, context);
sendCommand({ type: "recv-start", code: offerNoAns, stun: "" });
await sleep(50);
check("offer without ans -> empty ans",
    postedEvents.length === 1 && postedEvents[0].type === "offer-parsed"
        && postedEvents[0].ans === "");
sendCommand({ type: "stop" });
await sleep(20);

// ── code codec round-trip ───────────────────────────────────────────────────
const codecProbe = `
  (async () => {
    const payload = { v: 1, sdp: "v=0\\r\\n" + "a=candidate".repeat(40), name: "clip.mp4", size: 12345, mime: "video/mp4", dev: "Pixel" };
    const code = await encodeCode(OFFER_PREFIX, payload);
    const back = await decodeCode(OFFER_PREFIX, code);
    return { code, back };
  })()
`;
const { code, back } = await vm.runInContext(codecProbe, context);
check("code carries the FDS1. prefix", code.startsWith("FDS1."));
check("code is QR-sized", code.length < 2500, `length ${code.length}`);
check("codec round-trips", JSON.stringify(back).includes('"clip.mp4"') && back.size === 12345);
const badDecode = await vm.runInContext(
    `decodeCode(ANSWER_PREFIX, ${JSON.stringify(code)}).then(() => "decoded", (e) => e.message)`, context);
check("wrong prefix rejected", badDecode === "bad-code");

// ── iceServers sanitizer: keep valid stun:/turn:, drop junk, preserve creds ──
// Java hands the engine a fully-formed RTCIceServer[]; sanitizeIceServers is
// the guard against a bad settings value breaking the ctor. Exercise it.
const iceClean = await vm.runInContext(`sanitizeIceServers([
  { urls: "stun:stun.cloudflare.com:3478" },
  { urls: ["turn:relay.example:80","turn:relay.example:443"], username: "user", credential: "pass" },
  { urls: "https://evil.example/not-ice" },
  { urls: "" },
  { nope: 1 },
  { urls: "turn:custom:3478", username: "u", credential: "p" }
])`, context);
check("ice sanitizer keeps only valid stun/turn entries",
    iceClean.length === 3
    && iceClean[0].urls[0] === "stun:stun.cloudflare.com:3478"
    && iceClean[1].urls.length === 2 && iceClean[1].username === "user"
    && iceClean[2].urls[0] === "turn:custom:3478" && iceClean[2].credential === "p");
check("ice sanitizer normalizes single url to array",
    Array.isArray(iceClean[0].urls) && iceClean[0].urls.length === 1);

// ── stop is safe with no session ────────────────────────────────────────────
postedEvents.length = 0;
sendCommand({ type: "stop" });
check("stop answers closed",
    postedEvents.length === 1 && postedEvents[0].type === "state" && postedEvents[0].state === "closed");

process.exit(failures === 0 ? 0 : 1);
