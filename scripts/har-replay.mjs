// har-replay.mjs — replay a HAR in a real browser with the extension's capture
// scripts, and (optionally) through the REAL background code, to see what a
// page would put in the Captured sheet.
//
// The page is loaded at its own URL in Chromium; every request is answered
// from the HAR (a request the HAR does not hold is aborted), so the site's own
// JS runs against the responses the device saw. The REAL content-script.js and
// page-state-bridge.js are injected at document start with a stubbed
// `browser` that records what they send. With --background, those messages
// are then dispatched to the REAL background module graph (stubbed browser,
// HEAD probes answered 200) and the native emits are printed — name, picture,
// clip id — with the reasons the catcher rejected anything.
//
// For a whole site judged on the phone itself (the real GeckoView, the real
// extensions and repository), use scripts/device-audit.sh.
//
// This is how the lasprovincias.es report (HAR 26-10-19) was found: the
// page-state bridge's DOM read posted the podcast <audio>s with the page's
// title and logo and claimed them, so the content script's well-named
// reports were rejected; and its Brightcove players, set up 8 s in, were
// never read at all. Read the output against CLAUDE.md "Debugging video not
// captured".
//
// Caveats. Chromium runs the scripts as page scripts (no Xray; the stub sets
// window.wrappedJSObject = window), and a site's player may pick another
// source in Chromium than in Firefox (Brightcove handed its tech the mp4
// here). Anything the HAR lacks — a consent SDK blocked on the device, a
// stream fetched only on play — is absent: use --init to stand in for it
// (e.g. load a player script late, the way the device did). A HAR holds the
// user's session (cookies, tokens): keep it out of the repo.
//
// Needs Playwright + its Chromium (`sh scripts/setup-playwright.sh`); SKIPs
// without them.
//   node scripts/har-replay.mjs <page.har> [--wait MS] [--init FILE.js]...
//        [--background] [--url PAGE] [--cs content-script.js] [--bridge page-state-bridge.js]
//        [--images]
// --images lists each reported picture with the name the content script gave
// it. The page's long tasks (over 50 ms) are printed at the end either way:
// run once with --cs pointing at another revision to compare what a change
// costs the page.
import { readFileSync } from "node:fs";
import { loadPlaywright, PLAYWRIGHT_HINT } from "./load-playwright.mjs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ext = join(root, "app/src/main/assets/webrequests");

const args = process.argv.slice(2);
const opts = { wait: 15000, init: [], background: false, url: null,
  cs: join(ext, "js/content-script.js"), bridge: join(ext, "js/page-state-bridge.js") };
let harPath = null;
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === "--wait") opts.wait = Number(args[++i]);
  else if (a === "--init") opts.init.push(readFileSync(args[++i], "utf8"));
  else if (a === "--background") opts.background = true;
  else if (a === "--images") opts.images = true;
  else if (a === "--url") opts.url = args[++i];
  else if (a === "--cs") opts.cs = args[++i];
  else if (a === "--bridge") opts.bridge = args[++i];
  else harPath = a;
}
if (!harPath) {
  console.log("usage: node scripts/har-replay.mjs <page.har> [--wait MS] [--init FILE.js]... [--background] [--url PAGE]");
  process.exit(2);
}

const chromium = (await loadPlaywright())?.chromium;
if (!chromium) {
  console.log(`SKIP: playwright not installed (${PLAYWRIGHT_HINT})`);
  process.exit(0);
}

// ── The HAR as a response table: per URL the largest body; a 304 or a
// status-0 entry with a body becomes a 200 (the device served it from cache).
const har = JSON.parse(readFileSync(harPath, "utf8"));
const entries = har.log.entries;
const byUrl = new Map();
for (const e of entries) {
  const c = e.response.content || {};
  if (!c.text) continue;
  const prev = byUrl.get(e.request.url);
  if (prev && prev.text.length >= c.text.length) continue;
  const status = (e.response.status === 304 || !e.response.status) ? 200 : e.response.status;
  byUrl.set(e.request.url, { status, mime: c.mimeType || "application/octet-stream", text: c.text, enc: c.encoding });
}
// The page: --url, else the HAR's own page record (Firefox titles it with the
// URL), else the first entry. The first ENTRY is not always the page — an
// embed the page prefetched can come first (vozpopuli.com, HAR 26-10-19).
const pageTitle = ((har.log.pages || [])[0] || {}).title;
const pageUrl = opts.url
  || (/^https?:\/\//.test(pageTitle || "") && byUrl.has(pageTitle) ? pageTitle : entries[0].request.url);
const pageEntry = entries.find((e) => e.request.url === pageUrl) || entries[0];
const ua = ((pageEntry.request.headers || []).find((h) => /^user-agent$/i.test(h.name)) || {}).value;

const STUB = `window.__reports = []; window.__t0 = Date.now(); window.__long = [];
try {
  new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__long.push(Math.round(e.duration)); })
    .observe({ type: "longtask", buffered: true });
} catch (e) {}
window.wrappedJSObject = window;
window.browser = { runtime: {
  sendNativeMessage: () => Promise.resolve(false),
  sendMessage: (m) => { window.__reports.push({ t: Date.now() - window.__t0, m: JSON.parse(JSON.stringify(m)) }); return Promise.resolve(false); },
  onMessage: { addListener: () => {} } },
  tabs: { sendMessage: () => Promise.resolve() } };`;

const browser = await chromium.launch({ args: ["--no-proxy-server"] });
const ctx = await browser.newContext({ ...(ua ? { userAgent: ua } : {}), viewport: { width: 412, height: 900 } });
const missed = [];
await ctx.route("**/*", (r) => {
  const u = r.request().url();
  const hit = byUrl.get(u);
  if (!hit) {
    missed.push(u);
    return r.abort();
  }
  const body = hit.enc === "base64" ? Buffer.from(hit.text, "base64") : Buffer.from(hit.text, "utf8");
  return r.fulfill({ status: hit.status, contentType: hit.mime, body, headers: { "access-control-allow-origin": "*" } });
});
const page = await ctx.newPage();
await page.addInitScript(STUB);
for (const s of opts.init) await page.addInitScript(s);
// The content script's top level declares lets; a block keeps them off the
// page's globals.
await page.addInitScript(`(() => {\n${readFileSync(opts.cs, "utf8")}\n})();`);
await page.addInitScript(readFileSync(opts.bridge, "utf8"));
await page.goto(pageUrl, { waitUntil: "domcontentloaded" });
await page.waitForTimeout(opts.wait);
const reports = await page.evaluate(() => window.__reports);
const longTasks = await page.evaluate(() => window.__long);
await browser.close();

const { pickCaptureMeta, pickImageMeta } = await (async () => {
  globalThis.browser = makeBackgroundStub().browser;
  return import(pathToFileURL(join(ext, "js/requests.js")));
})();

console.log(`page: ${pageUrl}  (${entries.length} HAR entries, ${missed.length} requests not in the HAR)`);
console.log("\n── what the page's scripts reported ──");
for (const r of reports) {
  const m = r.m;
  if (m.kind === "images-detected") {
    const media = m.urls.filter((u) => m.meta && m.meta[u] && m.meta[u].kind !== "image");
    const named = m.urls.filter((u) => m.meta && m.meta[u] && m.meta[u].kind === "image");
    console.log(`${String(r.t).padStart(6)} ms  content script: ${m.urls.length} URL(s), ${media.length} media, ${named.length} named picture(s)`);
    if (opts.images) {
      for (const u of named) {
        const p = pickImageMeta(m.meta[u]);
        console.log(`           [img] ${u.slice(0, 100)}\n             name=${JSON.stringify(p.name)}${p.description ? ` description=${JSON.stringify(p.description.slice(0, 100))}` : ""}`);
      }
    }
    for (const u of media) {
      const p = pickCaptureMeta(m.meta[u]);
      console.log(`           ${u.slice(0, 110)}\n             name=${JSON.stringify(p.name)} img=${p.img ? p.img.slice(0, 80) : "(none)"}`);
    }
  } else if (m.kind === "player-placeholders") {
    console.log(`${String(r.t).padStart(6)} ms  content script: ${m.urls.length} player placeholder(s)`);
    for (const u of m.urls) console.log(`           ${u.slice(0, 110)}`);
  } else if (m.kind && m.kind.startsWith("page-state")) {
    const p = m.payload || {};
    const url = p.url || (p.variants && p.variants[0] && p.variants[0].url) || "";
    console.log(`${String(r.t).padStart(6)} ms  bridge ${m.kind}: ${url.slice(0, 100)}\n             title=${JSON.stringify(p.title)} img=${(p.img || "").slice(0, 80)}${p.clipId ? ` clip=${p.clipId}` : ""}`);
  }
}

if (opts.background) {
  const { nativeSent, registrations } = globalThis.__harBg;
  await import(pathToFileURL(join(ext, "js/parsers/index.js")));
  await new Promise((r) => setTimeout(r, 50));
  const sender = { tab: { id: 7, url: pageUrl, incognito: false }, frameId: 0, url: pageUrl };
  for (const r of reports) {
    for (const fn of registrations["runtime.onMessage"] || []) {
      try { fn(r.m, sender, () => {}); } catch (e) { console.log("dispatch threw", e.message); }
    }
    await new Promise((res) => setTimeout(res, 20));
  }
  await new Promise((r) => setTimeout(r, 3000));
  console.log("\n── what the background sent to native (images left out) ──");
  for (const s of nativeSent) {
    const m = s.msg || {};
    if (m.kind || m.type === "image" || m.type === "svg") continue;
    const u = m.url || (m.variants && m.variants[0] && m.variants[0].url) || "";
    if (!u) continue;
    console.log(`  ${m.type}: ${String(u).slice(0, 100)}\n    name=${JSON.stringify(m.name)} img=${String(m.img || "").slice(0, 80)}${m.clipId ? ` clip=${m.clipId}` : ""}`);
  }
  const own = globalThis.__harBg.fetches.filter((f) => f.method !== "HEAD");
  if (own.length) {
    console.log("\n── what the background fetched itself (answered 200, empty) ──");
    for (const f of own) console.log(`  ${f.method} ${f.url.slice(0, 120)}`);
  }
  const rejects = {};
  for (const line of globalThis.__harBg.logs) {
    const k = /\[req\] (reject:[\w-]+)/.exec(line);
    if (k) rejects[k[1]] = (rejects[k[1]] || 0) + 1;
  }
  console.log("\n── catcher rejections ──");
  for (const [k, n] of Object.entries(rejects)) console.log(`  ${k}: ${n}`);
}
console.log(`\n── the page's long tasks (ms): ${longTasks.length ? longTasks.join(", ") : "none"}`);
process.exit(0);

// The background's browser.* stub (the smoke's shape): debug logging on so
// the catcher's decisions can be counted, HEAD probes answered 200.
function makeBackgroundStub() {
  const registrations = {};
  const evt = (p) => ({ addListener(fn) { (registrations[p] ??= []).push(fn); } });
  const nativeSent = [];
  const logs = [];
  const realLog = console.log;
  console.log = (...a) => {
    const line = a.map((x) => (typeof x === "string" ? x : JSON.stringify(x))).join(" ");
    if (/^\[(req|\d\d:)/.test(line)) { logs.push(line); return; }
    realLog(...a);
  };
  const fetches = [];
  globalThis.fetch = async (url, init) => {
    fetches.push({ url: String(url), method: (init && init.method) || "GET" });
    return new Response(null, { status: 200, headers: {
      "content-type": /\.mp3|\.m4a|\.aac/.test(String(url)) ? "audio/mpeg" : "video/mp4" } });
  };
  globalThis.__harBg = { registrations, nativeSent, logs, fetches };
  return { browser: {
    runtime: {
      sendNativeMessage: async (app, msg) => {
        if (msg && msg.kind === "get-debug-flag") return true;
        nativeSent.push({ app, msg });
        return false;
      },
      onMessage: evt("runtime.onMessage"),
      connectNative: () => ({ onMessage: evt("port.onMessage"), onDisconnect: evt("port.onDisconnect"), postMessage() {} }),
    },
    webRequest: Object.assign(Object.fromEntries(["onBeforeRequest", "onBeforeSendHeaders", "onSendHeaders",
      "onHeadersReceived", "onResponseStarted", "onCompleted", "onErrorOccurred"].map((k) => [k, evt(`webRequest.${k}`)])),
    { filterResponseData() { throw new Error("no filterResponseData in a replay"); } }),
    webNavigation: { onHistoryStateUpdated: evt("webNavigation.onHistoryStateUpdated"),
      getFrame: async ({ frameId }) => ({ frameId, parentFrameId: -1 }) },
    tabs: { onUpdated: evt("tabs.onUpdated"), onRemoved: evt("tabs.onRemoved"), onActivated: evt("tabs.onActivated"),
      query: async () => [], get: async (id) => ({ id, url: pageUrl, incognito: false }), sendMessage: async () => null },
    cookies: { onChanged: evt("cookies.onChanged"), getAll: async () => [] },
    storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
  } };
}
