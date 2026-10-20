// Firedown site audit — DEBUG BUILDS ONLY (app/src/debug/assets/siteaudit).
// SiteAuditTest installs this extension for a device audit and uninstalls it
// after it; the app never registers it. It does nothing on its own: the test
// sends commands over the "siteaudit" native port and this page answers.
//
//   hello                               → { version }
//   tabs                                → [{ id, url, active, title }]
//   frames   { tabId }                  → [{ frameId, parentFrameId, url }]
//   run      { tabId, op, args, top }   → [{ frameId, parentFrameId, url, result | error }]
//   requests { tabId }                  → { tab: [...], extension: [...] }, cleared
//   forget   { tabId }                  → drops what is kept for the tab
//
// Replies are { id, ok: true, reply } or { id, ok: false, error }. The page
// work (consent, scroll, census, the markup around a clip) is content.js's.
// The request log observes only: no listener here is blocking, so the pages
// load exactly as they would without it.

"use strict";

const VERSION = browser.runtime.getManifest().version;
const MAX_REQUESTS = 4000;
const URL_MAX = 600;
const OP_TIMEOUT_MS = { scroll: 40000, consent: 4000, census: 10000, around: 15000 };
const DEFAULT_OP_TIMEOUT_MS = 8000;

// ── the native port ─────────────────────────────────────────────────────
// The test sets the message delegate after installing the extension, so the
// first connects fail; retry every second for ten minutes, then every 30 s
// (a test that crashed before uninstalling leaves only that behind).

let port = null;
let lastTry = 0;
const loadedAt = Date.now();

function connect() {
  if (port) {
    return;
  }
  lastTry = Date.now();
  let p;
  try {
    p = browser.runtime.connectNative("siteaudit");
  } catch (e) {
    return;
  }
  port = p;
  p.onMessage.addListener((msg) => {
    handle(msg);
  });
  p.onDisconnect.addListener(() => {
    if (port === p) {
      port = null;
    }
  });
}

setInterval(() => {
  if (port) {
    return;
  }
  if (Date.now() - loadedAt > 600000 && Date.now() - lastTry < 30000) {
    return;
  }
  connect();
}, 1000);
connect();

function post(message) {
  if (!port) {
    return;
  }
  try {
    port.postMessage(message);
  } catch (e) {
    // The port died between the command and the reply; the test times out.
  }
}

async function handle(msg) {
  const id = msg && msg.id;
  try {
    const reply = await dispatch(msg || {});
    post({ id, ok: true, reply: reply === undefined ? null : reply });
  } catch (e) {
    post({ id, ok: false, error: String((e && e.message) || e) });
  }
}

async function dispatch(msg) {
  switch (msg.cmd) {
    case "hello":
      return { version: VERSION };
    case "tabs": {
      const tabs = await browser.tabs.query({});
      return tabs.map((t) => ({ id: t.id, url: t.url || "", active: !!t.active, title: t.title || "" }));
    }
    case "frames":
      return frames(msg.tabId);
    case "run":
      return run(msg.tabId, msg.op, msg.args || {}, !!msg.top);
    case "requests": {
      const tab = logs.get(msg.tabId) || [];
      const extension = logs.get(-1) || [];
      logs.delete(msg.tabId);
      logs.delete(-1);
      return { tab, extension, dropped: dropped.get(msg.tabId) || 0 };
    }
    case "forget":
      logs.delete(msg.tabId);
      dropped.delete(msg.tabId);
      return true;
    default:
      throw new Error("unknown command " + msg.cmd);
  }
}

async function frames(tabId) {
  const list = (await browser.webNavigation.getAllFrames({ tabId })) || [];
  return list.map((f) => ({ frameId: f.frameId, parentFrameId: f.parentFrameId, url: f.url || "" }));
}

function withTimeout(promise, ms) {
  let timer = null;
  const timeout = new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error("timeout after " + ms + " ms")), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

// Runs one content.js op in the tab's frames (or the top frame only), all at
// once. A frame with no content script (about:blank, a frame still loading)
// answers with an error, not a hang.
async function run(tabId, op, args, top) {
  let list;
  if (top) {
    list = [{ frameId: 0, parentFrameId: -1, url: "" }];
  } else {
    list = await frames(tabId);
  }
  const ms = OP_TIMEOUT_MS[op] || DEFAULT_OP_TIMEOUT_MS;
  const results = await Promise.all(list.map(async (f) => {
    const out = { frameId: f.frameId, parentFrameId: f.parentFrameId, url: f.url };
    try {
      const reply = await withTimeout(
        browser.tabs.sendMessage(tabId, { siteaudit: 1, op, args }, { frameId: f.frameId }), ms);
      out.result = reply === undefined ? null : reply;
    } catch (e) {
      out.error = String((e && e.message) || e);
    }
    return out;
  }));
  return results;
}

// ── the request log ─────────────────────────────────────────────────────
// Every request a tab makes, and the extensions' own (tabId -1: HEAD probes,
// a parser's config lookups), with what became of it: status, type, length,
// cache, error, redirect target.

const logs = new Map();
const dropped = new Map();
const open = new Map();

function clip(u) {
  const s = String(u || "");
  return s.length > URL_MAX ? s.slice(0, URL_MAX) + "…" : s;
}

function push(tabId, record) {
  let list = logs.get(tabId);
  if (!list) {
    list = [];
    logs.set(tabId, list);
  }
  if (list.length >= MAX_REQUESTS) {
    dropped.set(tabId, (dropped.get(tabId) || 0) + 1);
    return;
  }
  list.push(record);
}

function header(headers, name) {
  for (const h of headers || []) {
    if (h.name && h.name.toLowerCase() === name) {
      return h.value || "";
    }
  }
  return "";
}

browser.webRequest.onBeforeRequest.addListener((d) => {
  const record = { t: Math.round(d.timeStamp), m: d.method, u: clip(d.url), type: d.type, f: d.frameId };
  if (d.tabId < 0 && d.originUrl) {
    record.from = clip(d.originUrl);
  }
  if (open.size > 6000) {
    let n = 0;
    for (const key of open.keys()) {
      open.delete(key);
      n++;
      if (n >= 1000) {
        break;
      }
    }
  }
  open.set(d.requestId, record);
  push(d.tabId, record);
}, { urls: ["<all_urls>"] });

browser.webRequest.onBeforeRedirect.addListener((d) => {
  const record = open.get(d.requestId);
  if (record) {
    record.s = d.statusCode;
    record.to = clip(d.redirectUrl);
  }
}, { urls: ["<all_urls>"] });

browser.webRequest.onCompleted.addListener((d) => {
  const record = open.get(d.requestId);
  open.delete(d.requestId);
  if (!record) {
    return;
  }
  record.s = d.statusCode;
  const type = header(d.responseHeaders, "content-type");
  if (type) {
    record.ct = type.slice(0, 80);
  }
  const length = header(d.responseHeaders, "content-length");
  if (length) {
    record.len = Number(length) || 0;
  }
  if (d.fromCache) {
    record.cache = true;
  }
}, { urls: ["<all_urls>"] }, ["responseHeaders"]);

browser.webRequest.onErrorOccurred.addListener((d) => {
  const record = open.get(d.requestId);
  open.delete(d.requestId);
  if (record) {
    record.err = d.error;
  }
}, { urls: ["<all_urls>"] });

browser.tabs.onRemoved.addListener((tabId) => {
  logs.delete(tabId);
  dropped.delete(tabId);
});
