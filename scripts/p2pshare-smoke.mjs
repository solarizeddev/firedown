// Smoke test for the p2pshare engine background script — run with:
//   node scripts/p2pshare-smoke.mjs
//
// Loads assets/p2pshare/background.js in a vm context under a stubbed
// `browser` (catches syntax errors, a broken port hookup, a dead command
// route) and exercises the pure pieces that don't need a real
// RTCPeerConnection: the ready/ensure handshake (including the
// location.reload() path for the pref-gated-global case) and the
// FDS1./FDR1. code codec round-trip (deflate + base64url).
//
// The WebRTC/DataChannel halves can only be proven on-device — this guards
// the protocol plumbing around them.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app/src/main/assets/p2pshare/background.js"), "utf8");

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
const posted = [];
const portListeners = [];
let reloaded = false;

const port = {
  postMessage: (message) => posted.push(message),
  onMessage: { addListener: (fn) => portListeners.push(fn) },
  onDisconnect: { addListener: () => {} },
};

const context = vm.createContext({
  browser: {
    runtime: {
      sendNativeMessage: async () => false, // get-debug-flag → release mode
      connectNative: () => port,
    },
  },
  location: { reload: () => { reloaded = true; } },
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
  // RTCPeerConnection deliberately ABSENT — mirrors a background page that
  // loaded while media.peerconnection.enabled was off.
});

vm.runInContext(source, context, { filename: "background.js" });

const send = (message) => portListeners.forEach((fn) => fn(message));

// ── ready / ensure handshake ───────────────────────────────────────────────
check("port connected at load", portListeners.length === 1);
check("ready posted at load", posted.length === 1 && posted[0].type === "ready");
check("ready reports rtc unavailable", posted[0].rtc === false);

posted.length = 0;
send({ type: "ensure" });
check("ensure answered", posted.length === 1 && posted[0].type === "ensure-result");
check("ensure reports not ok", posted[0].ok === false);
await sleep(120);
check("page reloads when RTC is pref-gated away", reloaded === true);

// ── bad-code handling (soft error, no session) ─────────────────────────────
posted.length = 0;
send({ type: "recv-start", code: "garbage", device: "test", stun: "" });
await sleep(50);
check("bad offer code → bad-code error",
    posted.length === 1 && posted[0].type === "error" && posted[0].code === "bad-code");

// ── code codec round-trip ──────────────────────────────────────────────────
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
check("wrong prefix rejected", badDecode === "bad-prefix");

// ── stop is safe with no session ───────────────────────────────────────────
posted.length = 0;
send({ type: "stop" });
check("stop answers closed", posted.length === 1 && posted[0].type === "state" && posted[0].state === "closed");

process.exit(failures === 0 ? 0 : 1);
