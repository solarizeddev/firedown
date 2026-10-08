// Runs the REAL youtube/background.js in a vm and drives getFromPrepared() —
// the one place the n-param/cipher solvers execute the YouTube player — with
// scripted "players" that start timer chains at init, the way the on-device
// SyntaxError flood (background.js line 267 > Function, every ~4.6 ms) says
// the real player does. Pins the scheduler quarantine: after a solve, nothing
// the player scheduled may run in the background page; the page's own
// schedulers come back intact, even when the player throws or replaces them.
//
//   node scripts/youtube-solver-smoke.mjs [path/to/background.js]
//
// No network: the solve input is the scripted player, not a real base.js.
import fs from "node:fs";
import vm from "node:vm";

const path = process.argv[2] || new URL("../app/src/main/assets/youtube/background.js", import.meta.url).pathname;
const src = fs.readFileSync(path, "utf8");

const ev = () => ({ addListener() {}, removeListener() {}, hasListener() { return false; } });
const logs = [];
const reported = [];               // what Gecko would print as [JavaScript Error]
const handles = new Set();
const fire = (ctx, fn) => () => {
  try { typeof fn === "function" ? fn() : vm.runInContext(String(fn), ctx); }
  catch (e) { reported.push(`${e.name}: ${e.message}`); }
};
const browser = {
  webRequest: { onBeforeRequest: ev(), onBeforeSendHeaders: ev(), onHeadersReceived: ev(), onSendHeaders: ev(),
    onCompleted: ev(), onErrorOccurred: ev(), filterResponseData() { throw new Error("n/a"); } },
  tabs: { onUpdated: ev(), onRemoved: ev(), onActivated: ev(), query: async () => [], get: async () => ({}) },
  runtime: { onMessage: ev(), onConnect: ev(), getURL: (p) => p,
    sendNativeMessage: async (_app, msg) => (msg && msg.kind === "get-debug-flag") ? true : null,
    connectNative() { return { onMessage: ev(), onDisconnect: ev(), postMessage() {}, disconnect() {} }; } },
  storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
  cookies: { getAll: async () => [] },
  webNavigation: { onCommitted: ev(), onHistoryStateUpdated: ev(), onCompleted: ev() },
};
const ctx = {
  browser,
  console: { log: (...a) => logs.push(a.join(" ")), warn() {}, error() {}, info() {}, debug() {} },
  fetch: async () => { throw new Error("offline"); },
  URL, URLSearchParams, TextDecoder, TextEncoder, AbortSignal, AbortController, atob, btoa,
  clearTimeout: (h) => { clearTimeout(h); handles.delete(h); },
  clearInterval: (h) => { clearInterval(h); handles.delete(h); },
};
ctx.setTimeout = (fn, ms) => { const h = setTimeout(fire(ctx, fn), ms || 0); handles.add(h); return h; };
ctx.setInterval = (fn, ms) => { const h = setInterval(fire(ctx, fn), Math.max(ms || 0, 1)); handles.add(h); return h; };
ctx.requestAnimationFrame = (fn) => ctx.setTimeout(fn, 16);
const real = { setTimeout: ctx.setTimeout, setInterval: ctx.setInterval, requestAnimationFrame: ctx.requestAnimationFrame };
vm.createContext(ctx);
vm.runInContext(src + "\n;globalThis.__gfp = getFromPrepared;", ctx);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ok = 0, bad = 0;
const check = (name, cond, detail) => {
  if (cond) { ok++; console.log("PASS", name); } else { bad++; console.log("FAIL", name, detail ?? ""); }
};
const restored = () => ctx.setTimeout === real.setTimeout && ctx.setInterval === real.setInterval
  && ctx.requestAnimationFrame === real.requestAnimationFrame;
const playerErrors = () => reported.filter((r) => r.startsWith("SyntaxError")).length;

await sleep(10); // the DEBUG flag resolves asynchronously

// 1. Init chains through every door: property access, bare identifier,
//    interval, rAF — each tick throws the device's SyntaxError.
ctx.__ticks = 0;
logs.length = 0;
const r1 = ctx.__gfp(`var _yt_player={};(function(g){var window=this;
  window.setInterval(function(){ __ticks++; Function("a+"); }, 0);
  setTimeout(function t(){ __ticks++; setTimeout(t, 0); Function("b+"); }, 0);
  window.requestAnimationFrame(function(){ __ticks++; });
  _result.id = setTimeout(function(){}, 0);
  _result.n = function(x){ return x + "!"; };
}).call(this);`);
await sleep(80);
check("init chains never run after the solve", ctx.__ticks === 0, `ticks=${ctx.__ticks}`);
check("no SyntaxError flood from player callbacks", playerErrors() === 0, `reported=${playerErrors()}`);
check("the solve result still works", r1.n && r1.n("abc") === "abc!");
check("quarantined ids are outside the real timer range", typeof r1.id === "number" && r1.id >= 0x40000000, r1.id);
check("page schedulers restored after the solve", restored());
check("debug log names what was quarantined", logs.some((l) => l.includes("[Solver] quarantined") && l.includes("setInterval")),
  logs.join(" | "));

// 2. References the player captured at init, and a chain it starts from a
//    microtask AFTER the run through a bare identifier.
ctx.__ticks = 0;
const before2 = playerErrors();
const r2 = ctx.__gfp(`(function(){ var st = this.setTimeout, st2 = setTimeout;
  Promise.resolve().then(function(){ setTimeout(function t(){ __ticks++; setTimeout(t, 0); Function("c+"); }, 0); });
  _result.n = function(x){ st(function(){ __ticks++; }, 0); st2(function(){ __ticks++; }, 0); return x; };
}).call(this);`);
r2.n("x");
await sleep(80);
check("captured references and a microtask-started chain stay inert", ctx.__ticks === 0, `ticks=${ctx.__ticks}`);
check("no errors from them either", playerErrors() === before2);

// 3. A player that does not compile (the truncated base.js case): the throw
//    still reaches the caller, and the page's schedulers come back.
let threw = null;
try { ctx.__gfp("var a = ("); } catch (e) { threw = e; }
check("a non-compiling player still throws to the caller", threw && threw.name === "SyntaxError", threw && threw.name);
check("schedulers restored after a throw", restored());
let shellFired = false;
ctx.setTimeout(() => { shellFired = true; }, 0);
await sleep(20);
check("the page's own timers still fire", shellFired);

// 4. A player that replaces the page's scheduler on globalThis.
ctx.__gfp(`globalThis.setTimeout = function(){ return -1; }; _result.n = function(x){ return x; };`);
check("a scheduler the player installs is undone", restored());

// 5. Every solver run goes through the quarantine.
const sites = (src.match(/Function\("_result"/g) || []).length;
check("exactly one Function(\"_result\", …) site", sites === 1, `sites=${sites}`);

for (const h of handles) { clearTimeout(h); clearInterval(h); }
console.log(bad ? `FAILED ${bad} of ${ok + bad}` : `all ${ok} passed`);
process.exit(bad ? 1 : 0);
