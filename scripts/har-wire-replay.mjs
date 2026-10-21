// har-wire-replay.mjs — replay a HAR's REQUESTS through the real background
// webRequest listeners (the generic catcher in requests.js and every parser
// module), and print what would reach the Java side: each native capture with
// its name, picture and origin.
//
// har-replay.mjs is the other half: it runs the page's own JS in Chromium with
// the content scripts and feeds what THEY report to the background. This one
// needs no browser — it walks the HAR's entries in order and fires, per
// request, onBeforeRequest → onSendHeaders → onHeadersReceived (feeding the
// body to every response filter the listeners opened) → onResponseStarted →
// onCompleted, with real match-pattern + resource-type filtering. It is what
// to run when a report is "the Captured sheet shows junk / duplicates": the
// output names the listener behind every tile, before any device round trip
// (an embed's master plus its HLS init segments as blank VIDEO/AUDIO tiles).
//
// For a whole site judged on the phone itself (the real GeckoView, the real
// extensions and repository), use scripts/device-audit.sh.
//
// What is inferred, not recorded: the resource type comes from the request's
// Sec-Fetch-Dest (a HAR has no webRequest type); frames from document entries
// and the Referer that names them; the tab's page from the HAR's page record
// (--url overrides). The parsers' own fetches are answered from the HAR (a URL
// it does not hold gets a 404), and delivered to the listeners the way Firefox
// shows an extension its own requests. A `get-page-metadata` query is answered
// from the frame document's <title> and og:image when the HAR holds it. A HAR
// holds the user's session: keep it out of the repo.
//
//   node scripts/har-wire-replay.mjs <file.har> [--url PAGE] [--debug] [--wait MS]
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ext = join(root, "app/src/main/assets/webrequests");

const args = process.argv.slice(2);
const opts = { url: null, debug: false, wait: 6000 };
let harPath = null;
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === "--url") opts.url = args[++i];
  else if (a === "--debug") opts.debug = true;
  else if (a === "--wait") opts.wait = Number(args[++i]);
  else harPath = a;
}
if (!harPath) {
  console.log("usage: node scripts/har-wire-replay.mjs <file.har> [--url PAGE] [--debug] [--wait MS]");
  process.exit(2);
}

const har = JSON.parse(readFileSync(harPath, "utf8"));
const entries = har.log.entries;

const bodyOf = (e) => {
  const c = e.response?.content || {};
  if (typeof c.text !== "string") return new Uint8Array(0);
  return c.encoding === "base64" ? new Uint8Array(Buffer.from(c.text, "base64")) : new TextEncoder().encode(c.text);
};
const headerOf = (list, name) => (list || []).find((h) => h.name.toLowerCase() === name)?.value;
const toHeaders = (list) => (list || []).map((h) => ({ name: h.name, value: h.value }));

// Per URL the largest body the HAR holds (the parsers' own fetches read it).
const byUrl = new Map();
for (const e of entries) {
  const prev = byUrl.get(e.request.url);
  if (!prev || bodyOf(e).length > bodyOf(prev).length) byUrl.set(e.request.url, e);
}

const firstUrl = (s) => (typeof s === "string" && /^https?:\/\//.test(s) ? s : null);
const pageUrl = opts.url || firstUrl(har.log.pages?.[0]?.title) || entries[0]?.request.url;
const TAB = 1;

// ── browser stub ─────────────────────────────────────────────────────────
const registrations = [];
const evt = (path) => ({ addListener(fn, filter, extra) { registrations.push({ path, fn, filter, extra }); } });
const native = [];
const filters = new Map();

function patternMatches(pattern, url) {
  if (pattern === "<all_urls>") return /^(https?|wss?|ftp|data|file):/.test(url);
  const m = pattern.match(/^(\*|https?|wss?):\/\/([^/]+)(\/.*)$/);
  if (!m) return false;
  let u;
  try { u = new URL(url); } catch (_) { return false; }
  const scheme = u.protocol.replace(":", "");
  if (m[1] === "*" ? !["http", "https", "ws", "wss"].includes(scheme) : m[1] !== scheme) return false;
  let host = m[2];
  let optSub = "";
  if (host === "*") host = "[^/]+";
  else {
    if (host.startsWith("*.")) { optSub = "([^.]+\\.)*"; host = host.slice(2); }
    host = host.replace(/\./g, "\\.").replace(/\*/g, "[^.]*");
  }
  if (!new RegExp("^" + optSub + host + "$").test(u.hostname)) return false;
  const pathRe = "^" + m[3].replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$";
  return new RegExp(pathRe).test(u.pathname + u.search);
}
function listeners(path, url, type) {
  return registrations.filter((r) => r.path === path
    && (!r.filter?.types || r.filter.types.includes(type))
    && (r.filter?.urls || ["<all_urls>"]).some((p) => patternMatches(p, url)));
}

// Frames: 0 is the page; each sub_frame document opens the next id, and a
// request is filed under the newest frame whose origin its Referer names.
const frames = [{ frameId: 0, parentFrameId: -1, url: pageUrl }];
function frameFor(referer) {
  if (!referer) return frames[0];
  let origin;
  try { origin = new URL(referer).origin; } catch (_) { return frames[0]; }
  for (let i = frames.length - 1; i >= 0; i--) {
    try { if (new URL(frames[i].url).origin === origin) return frames[i]; } catch (_) { /* opaque */ }
  }
  return frames[0];
}

function metaFor(frameUrl) {
  const e = byUrl.get(frameUrl);
  if (!e) return {};
  const html = new TextDecoder().decode(bodyOf(e));
  const title = /<title[^>]*>([^<]*)<\/title>/i.exec(html)?.[1]?.trim();
  const img = /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)/i.exec(html)?.[1];
  return { title, ogTitle: title, image: img, url: frameUrl };
}

let ownSeq = 0;
globalThis.fetch = async (input, init = {}) => {
  const url = String(input);
  for (const r of listeners("webRequest.onBeforeRequest", url, "xmlhttprequest")) {
    try { r.fn({ url, type: "xmlhttprequest", tabId: -1, frameId: 0, method: init.method || "GET", requestId: `own${++ownSeq}` }); } catch (_) { /* a listener's own bug */ }
  }
  const e = byUrl.get(url);
  if (init.method === "HEAD") return new Response(null, { status: 200, headers: e ? Object.fromEntries(toHeaders(e.response.headers).map((h) => [h.name, h.value])) : {} });
  if (!e || !e.response.status) {
    console.log(`  [fetch] ${url.slice(0, 120)} → 404 (not in the HAR)`);
    return new Response("", { status: 404 });
  }
  console.log(`  [fetch] ${url.slice(0, 120)} → ${e.response.status}`);
  return new Response(bodyOf(e), { status: e.response.status });
};
Object.defineProperty(globalThis, "navigator", {
  value: { userAgent: "Mozilla/5.0 (Android 12; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0", languages: ["en-US"] },
  configurable: true,
});

globalThis.browser = {
  runtime: {
    sendNativeMessage: async (app, msg) => {
      if (msg?.kind === "get-debug-flag") return opts.debug;
      native.push({ app, msg });
      return false;
    },
    onMessage: evt("runtime.onMessage"),
    connectNative: () => ({ onMessage: evt("port.onMessage"), onDisconnect: evt("port.onDisconnect"), postMessage() {} }),
    getURL: (p) => `moz-extension://replay/${p}`,
  },
  webRequest: {
    onBeforeRequest: evt("webRequest.onBeforeRequest"),
    onBeforeSendHeaders: evt("webRequest.onBeforeSendHeaders"),
    onSendHeaders: evt("webRequest.onSendHeaders"),
    onHeadersReceived: evt("webRequest.onHeadersReceived"),
    onResponseStarted: evt("webRequest.onResponseStarted"),
    onCompleted: evt("webRequest.onCompleted"),
    onErrorOccurred: evt("webRequest.onErrorOccurred"),
    filterResponseData(requestId) {
      const f = { ondata: null, onstop: null, onerror: null, write() {}, close() {}, disconnect() {} };
      (filters.get(requestId) ?? filters.set(requestId, []).get(requestId)).push(f);
      return f;
    },
  },
  webNavigation: {
    onHistoryStateUpdated: evt("webNavigation.onHistoryStateUpdated"),
    getFrame: async ({ frameId }) => frames.find((f) => f.frameId === frameId) || null,
  },
  tabs: {
    onUpdated: evt("tabs.onUpdated"), onRemoved: evt("tabs.onRemoved"), onActivated: evt("tabs.onActivated"),
    query: async () => [{ id: TAB, url: pageUrl, incognito: false, active: true }],
    get: async (id) => ({ id, url: pageUrl, incognito: false, active: true }),
    sendMessage: async (tabId, msg, o) => {
      if (msg?.kind !== "get-page-metadata") return undefined;
      const frame = frames.find((f) => f.frameId === (o?.frameId ?? 0)) || frames[0];
      return metaFor(frame.url);
    },
  },
  cookies: { onChanged: evt("cookies.onChanged"), getAll: async () => [] },
  storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
};

await import(pathToFileURL(join(ext, "js/parsers/index.js")));
const { __setPlayerClaimGraceMs } = await import(pathToFileURL(join(ext, "js/requests.js")));
__setPlayerClaimGraceMs(1500);
const tick = (ms = 25) => new Promise((r) => setTimeout(r, ms));
await tick(50);

const TYPE_BY_DEST = {
  document: "main_frame", iframe: "sub_frame", frame: "sub_frame", empty: "xmlhttprequest",
  video: "media", audio: "media", track: "media", image: "image", script: "script", style: "stylesheet",
  font: "font", object: "object", embed: "object", manifest: "other", worker: "script", serviceworker: "script",
};
function typeOf(e, i) {
  const dest = (headerOf(e.request.headers, "sec-fetch-dest") || "").toLowerCase();
  if (TYPE_BY_DEST[dest]) return TYPE_BY_DEST[dest];
  return i === 0 ? "main_frame" : "other";
}

const fire = (path, url, type, details) => {
  const out = [];
  for (const r of listeners(path, url, type)) {
    try { out.push(r.fn(details)); } catch (err) { console.log(`  listener threw on ${path}: ${err.message}`); }
  }
  return out;
};

console.log(`page: ${pageUrl}\nentries: ${entries.length}\n`);
let req = 0;
const pending = [];
for (const [i, e] of entries.entries()) {
  const url = e.request.url;
  if (!/^https?:/.test(url)) continue;
  const type = typeOf(e, i);
  const referer = headerOf(e.request.headers, "referer");
  let frame = frameFor(referer);
  if (type === "sub_frame") {
    frames.push({ frameId: frames.length, parentFrameId: frame.frameId, url });
    frame = frames[frames.length - 1];
  }
  const requestId = `r${++req}`;
  const base = {
    requestId, url, type, method: e.request.method, tabId: TAB,
    frameId: type === "main_frame" ? 0 : frame.frameId,
    parentFrameId: type === "sub_frame" ? frame.parentFrameId : (frame.frameId === 0 ? -1 : frame.parentFrameId),
    documentUrl: type === "main_frame" ? undefined : (type === "sub_frame" ? frames[frame.parentFrameId]?.url : frame.url),
    originUrl: type === "sub_frame" ? frames[frame.parentFrameId]?.url : (type === "main_frame" ? referer : frame.url),
    timeStamp: Date.parse(e.startedDateTime) || Date.now(),
  };
  const status = e.response.status;
  pending.push(...fire("webRequest.onBeforeRequest", url, type, base));
  const requestHeaders = toHeaders(e.request.headers);
  pending.push(...fire("webRequest.onBeforeSendHeaders", url, type, { ...base, requestHeaders }));
  pending.push(...fire("webRequest.onSendHeaders", url, type, { ...base, requestHeaders }));
  if (!status) {
    for (const f of filters.get(requestId) || []) f.onerror?.();
    pending.push(...fire("webRequest.onErrorOccurred", url, type, { ...base, error: "NS_BINDING_ABORTED" }));
    await tick();
    continue;
  }
  const responseHeaders = toHeaders(e.response.headers);
  const done = { ...base, statusCode: status, responseHeaders, fromCache: false };
  pending.push(...fire("webRequest.onHeadersReceived", url, type, done));
  const body = bodyOf(e);
  for (const f of filters.get(requestId) || []) {
    try {
      if (body.length) f.ondata?.({ data: body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) });
      f.onstop?.();
    } catch (err) { console.log(`  filter threw: ${err.message}`); }
  }
  pending.push(...fire("webRequest.onResponseStarted", url, type, done));
  pending.push(...fire("webRequest.onCompleted", url, type, done));
  await tick();
}
await Promise.allSettled(pending);
await tick(opts.wait);

const captures = native.filter((n) => n.msg && n.msg.kind !== "get-debug-flag" && (n.msg.url || n.msg.type));
console.log(`\n${captures.length} native message(s):`);
for (const { app, msg } of captures) {
  const short = (s, n = 110) => (s ? String(s).slice(0, n) : "");
  console.log(`- [${app}] type=${msg.type ?? msg.kind} name="${short(msg.name, 60)}"`);
  console.log(`    url: ${short(msg.url, 160)}`);
  if (msg.origin) console.log(`    origin: ${short(msg.origin)}`);
  if (msg.img || msg.thumbnail) console.log(`    picture: ${short(msg.img || msg.thumbnail)}`);
  if (msg.duration) console.log(`    duration: ${msg.duration}`);
  if (msg.size) console.log(`    size: ${msg.size}`);
}
process.exit(0);
