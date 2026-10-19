// sim.mjs — one page through the WHOLE capture stack, the way a tab on the
// device runs it: the page in a real browser (Playwright), the REAL content
// script and page-state bridge injected in every frame, and the REAL
// background module graph (the generic catcher and every parser) in this Node
// process, fed as Firefox would feed it:
//   - the wire: every request of every frame goes through the registered
//     webRequest listeners (real match patterns and resource types), with the
//     frame ids, statuses and headers the browser saw, and each response body
//     handed to the filters a listener opened (filterResponseData);
//   - the content scripts: what they send (runtime.sendMessage) reaches the
//     background's runtime.onMessage with the sender's real frame id and URL,
//     the moment they send it;
//   - get-page-metadata: the background's tabs.sendMessage is answered by the
//     content script's responder IN THE TARGET FRAME, on the page's own clock
//     (so the 300 ms race and the late wait are the real ones);
//   - the background's own fetches (HEAD probes, a parser's config lookup)
//     go to the network and are shown to the listeners as extension requests.
// What reaches "native" is what the Captured sheet would list.
//
// har-replay.mjs (content scripts, reports dispatched afterwards) and
// har-wire-replay.mjs (the wire alone, no browser) each run one half; this
// runs both at once, which is what a site audit needs: a wire capture that
// races the content script's titled report is exactly the bug class those
// two cannot see together.
//
// The background graph is module-global, so ONE page per process: the CLI
// (scripts/site-audit.mjs) spawns a process per site.
//
// Modes: live (record = write a HAR of the run, Playwright's .har.zip, and the
// background's own fetches beside it, so the run can be replayed offline),
// replay of such a recording (offline, deterministic enough to diff), or a
// replay of a HAR exported from Firefox on the phone (plain .har JSON).
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { inflateRawSync } from "node:zlib";
import { AD_HOST_RE } from "./judge.mjs";
import { loadPlaywright, PLAYWRIGHT_HINT } from "../load-playwright.mjs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "../..");
const ext = join(root, "app/src/main/assets/webrequests");
export const FIREFOX_ANDROID_UA =
  "Mozilla/5.0 (Android 15; Mobile; rv:144.0) Gecko/144.0 Firefox/144.0";
const TAB = 1;
const EXT_ORIGIN = "moz-extension://firedown-sim/_generated_background_page.html";

export { loadPlaywright } from "../load-playwright.mjs";

// ── Firefox webRequest resource types from Playwright's ──────────────────
const TYPE_BY_RESOURCE = {
  stylesheet: "stylesheet", image: "image", media: "media", font: "font", script: "script",
  texttrack: "media", xhr: "xmlhttprequest", fetch: "xmlhttprequest", eventsource: "xmlhttprequest",
  websocket: "websocket", manifest: "web_manifest", ping: "beacon", other: "other",
};
// Chromium's network error names, as Gecko's (requests.js keys a blocker's
// refusal on NS_ERROR_ABORT; a page abort is NS_BINDING_ABORTED).
function geckoError(text) {
  if (!text) return "NS_ERROR_FAILURE";
  if (/ERR_ABORTED|NS_BINDING_ABORTED/.test(text)) return "NS_BINDING_ABORTED";
  if (/BLOCKED_BY_CLIENT/.test(text)) return "NS_ERROR_ABORT";
  if (/NAME_NOT_RESOLVED|UNKNOWN_HOST/.test(text)) return "NS_ERROR_UNKNOWN_HOST";
  if (/TIMED_OUT|TIMEOUT/.test(text)) return "NS_ERROR_NET_TIMEOUT";
  return "NS_ERROR_FAILURE";
}

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

// What runs in every frame before the extension's scripts: the `browser`
// they talk to. A report goes to the __fdReport binding (Playwright tells the
// binding which frame called it); the content script's onMessage listeners
// are kept so the background's get-page-metadata can be answered in-frame.
const PAGE_STUB = `(() => {
  const listeners = [];
  const queue = [];
  const send = (m) => {
    let copy;
    try { copy = JSON.parse(JSON.stringify(m)); } catch (e) { return; }
    if (typeof window.__fdReport === "function") { window.__fdReport(copy); return; }
    queue.push(copy);
    const retry = () => {
      if (typeof window.__fdReport !== "function") { setTimeout(retry, 20); return; }
      while (queue.length) window.__fdReport(queue.shift());
    };
    if (queue.length === 1) setTimeout(retry, 20);
  };
  Object.defineProperty(window, "__fdAsk", { value: async (m) => {
    for (const fn of listeners) {
      let r;
      try { r = fn(m, {}, () => {}); } catch (e) { continue; }
      if (r !== undefined) return await r;
    }
    return undefined;
  } });
  window.wrappedJSObject = window;
  window.browser = { runtime: {
    sendNativeMessage: () => Promise.resolve(false),
    sendMessage: (m) => { send(m); return Promise.resolve(false); },
    onMessage: { addListener: (fn) => { listeners.push(fn); } },
    getURL: (p) => p },
    tabs: { sendMessage: () => Promise.resolve() } };
})();`;

// The census a judge compares the captures with: every frame's media
// elements (shadow roots included), player containers, embed iframes, and
// the page-level names a clip must NOT carry on a multi-video page.
const CENSUS_FN = `(() => {
  const walk = (rootNode, fn, depth = 0) => {
    if (depth > 12) return;
    const all = rootNode.querySelectorAll ? rootNode.querySelectorAll("*") : [];
    for (const el of all) {
      fn(el);
      if (el.shadowRoot) walk(el.shadowRoot, fn, depth + 1);
    }
  };
  const meta = (p) => {
    const el = document.querySelector('meta[property="' + p + '"], meta[name="' + p + '"]');
    return el ? (el.getAttribute("content") || "").trim() : "";
  };
  const media = [];
  const iframes = [];
  let players = 0;
  const PLAYER_SEL = ".video-js, .jwplayer, .jw-wrapper, .plyr, video-js, [data-player], .bc-player, [class*=\\"brightcove\\"], .vjs-tech";
  walk(document, (el) => {
    const tag = el.tagName;
    if (tag === "VIDEO" || tag === "AUDIO") {
      const srcEl = el.querySelector("source[src]");
      const src = el.currentSrc || el.getAttribute("src") || (srcEl ? srcEl.src : "") || "";
      const r = el.getBoundingClientRect();
      media.push({
        tag: tag.toLowerCase(),
        src: src.slice(0, 400),
        sources: Array.from(el.querySelectorAll("source[src]")).map((s) => s.src.slice(0, 400)).slice(0, 8),
        w: Math.round(r.width), h: Math.round(r.height),
        poster: (el.getAttribute("poster") || "").slice(0, 300),
        duration: isFinite(el.duration) ? Math.round(el.duration) : 0,
        controls: el.hasAttribute("controls"),
        muted: el.muted, loop: el.loop, autoplay: el.autoplay,
        inPlayer: !!(el.closest && el.closest(PLAYER_SEL)),
        hiddenByAria: !!(el.closest && el.closest("[aria-hidden=true]")),
      });
    } else if (tag === "IFRAME") {
      const src = el.src || el.getAttribute("data-src") || el.getAttribute("data-lazy-src") || "";
      const r = el.getBoundingClientRect();
      iframes.push({ src: src.slice(0, 400), title: (el.getAttribute("title") || "").slice(0, 120),
        w: Math.round(r.width), h: Math.round(r.height), loaded: !!el.src && el.src !== "about:blank" });
    }
    // Click-to-load placeholders: the player URL they declare, as a lazy
    // embed (see "Click-to-load player placeholders" in CLAUDE.md).
    let declared = el.getAttribute && el.getAttribute("data-embed-url");
    if (!declared && (tag === "LITE-VIMEO" || tag === "LITE-YOUTUBE") && el.getAttribute("videoid")) {
      declared = (tag === "LITE-VIMEO" ? "https://player.vimeo.com/video/" : "https://www.youtube.com/embed/") + el.getAttribute("videoid");
    }
    if (declared) {
      const r = el.getBoundingClientRect();
      iframes.push({ src: declared.slice(0, 400), title: (el.getAttribute("title") || el.getAttribute("aria-label") || "").slice(0, 120),
        w: Math.round(r.width), h: Math.round(r.height), loaded: false, placeholder: true });
    }
    if (el.matches && el.matches(PLAYER_SEL) && !(el.parentElement && el.parentElement.closest(PLAYER_SEL))) players++;
  });
  return {
    url: location.href,
    title: document.title || "",
    ogTitle: meta("og:title"),
    siteName: meta("og:site_name") || meta("application-name"),
    media, iframes, players,
    images: document.images.length,
  };
})()`;

// Consent walls hold players back until the user agrees (lasprovincias.es:
// its Brightcove players load ~13 s in, after the wall). A user would agree;
// so does the audit, in every frame (Sourcepoint and others draw the wall in
// an iframe). Known buttons first, then any button whose whole text is an
// "accept" phrase.
const CONSENT_FN = `(() => {
  const SELECTORS = [
    "#onetrust-accept-btn-handler", "#didomi-notice-agree-button", "button.didomi-button-highlight",
    "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll", "#CybotCookiebotDialogBodyButtonAccept",
    ".qc-cmp2-summary-buttons button[mode=primary]", "button.fc-cta-consent", "#L2AGLb",
    ".cmpboxbtnyes", "#cmpwelcomebtnyes", "#truste-consent-button", "button.sp_choice_type_11",
    "button[data-testid=uc-accept-all-button]", "#accept-choices", ".iubenda-cs-accept-btn",
    "button.cky-btn-accept", "#ccc-notify-accept", ".cmplz-accept", "#cookie_action_close_header",
    "button[mode=primary][aria-label*=ccept]", "#pmConsentWall .pmConsentWall-button",
  ];
  const TEXT = /^\\s*(accept( all| all cookies| cookies| and close| & close)?|i accept|agree( and close| to all)?|i agree|allow( all)?( cookies)?|ok,? (i agree|got it)|got it|consent|continue with recommended cookies|aceptar( y cerrar| y continuar| todo| todas| todas las cookies| cookies)?|acepto|estoy de acuerdo|de acuerdo|consentir|permitir todo|accepter( et fermer| tout| les cookies)?|tout accepter|j'accepte|j’accepte|alle akzeptieren|akzeptieren( und schließen)?|zustimmen|alle zulassen|einverstanden|accetta( tutto| e chiudi)?|accetto|aceitar( tudo| todos| e fechar)?|concordo|aceptar todas las cookies|aceptar cookies|zgadzam się|akceptuję|accepteren|alles accepteren|godkänn alla|accepter alle|hyväksy kaikki)\\s*[.!]?\\s*$/i;
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    const s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none" && s.opacity !== "0";
  };
  const roots = [document];
  for (const el of document.querySelectorAll("*")) if (el.shadowRoot) roots.push(el.shadowRoot);
  for (const r of roots) {
    for (const sel of SELECTORS) {
      let el = null;
      try { el = r.querySelector(sel); } catch (e) { el = null; }
      if (el && visible(el)) { el.click(); return sel; }
    }
  }
  for (const r of roots) {
    for (const el of r.querySelectorAll("button, a, [role=button], input[type=button], input[type=submit]")) {
      const t = (el.innerText || el.value || el.getAttribute("aria-label") || "").trim();
      if (t.length > 60 || !TEXT.test(t) || !visible(el)) continue;
      el.click();
      return "text:" + t;
    }
  }
  return "";
})()`;

// Scroll the page through, a step at a time, so lazy players and lazy
// iframes (IntersectionObserver, loading=lazy) come into existence, then
// back to the top.
const SCROLL_FN = `(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const height = () => Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
  let y = 0;
  for (let i = 0; i < 30 && y < height(); i++) {
    y += Math.round(innerHeight * 0.8);
    scrollTo(0, y);
    await sleep(400);
  }
  scrollTo(0, 0);
  return { steps: Math.round(y / Math.max(1, innerHeight * 0.8)), height: height() };
})()`;

// ── the run ───────────────────────────────────────────────────────────────
// opts: url, mode ('live' | 'replay'), har (replay: .har.zip of a recording,
// or a Firefox .har), record (live: path of the .har.zip to write), fetchLog
// (record: path to write; replay: path to read), browser ('chromium' |
// 'firefox'), headed, wait, consent, scroll, adblock, cs, bridge, ua, timeout.
export async function simulate(opts) {
  const t0 = Date.now();
  const timeline = [];
  const mark = (what) => timeline.push({ ms: Date.now() - t0, what });
  const errors = [];

  const pw = await loadPlaywright();
  if (!pw) return { skipped: `playwright not installed (${PLAYWRIGHT_HINT})` };

  // ── replay sources ──
  let harJson = null;           // a Firefox HAR (plain JSON, or zipped)
  let byUrl = null;             // its response table
  // A .zip is either this tool's own recording (Playwright's: bodies kept
  // as files beside the HAR, replayed by routeFromHAR) or a Firefox export
  // zipped (one .har, bodies inline).
  let harIsZip = false;
  if (opts.mode === "replay" && opts.har && /\.zip$/i.test(opts.har)) {
    const buf = readFileSync(opts.har);
    const entries = zipEntries(buf);
    const harEntry = entries.find((e) => /\.har$/i.test(e.name));
    if (!harEntry) return { error: `${opts.har}: no .har inside` };
    const parsed = JSON.parse(zipRead(buf, harEntry).toString("utf8"));
    if ((parsed.log?.entries || []).some((e) => e.response?.content?._file)) harIsZip = true;
    else harJson = parsed;
  } else if (opts.mode === "replay" && opts.har) {
    harJson = JSON.parse(readFileSync(opts.har, "utf8"));
  }
  if (harJson) {
    byUrl = new Map();
    for (const e of harJson.log.entries) {
      const c = e.response.content || {};
      const prev = byUrl.get(e.request.url);
      const len = c.text ? c.text.length : 0;
      if (prev && (prev.response.content?.text?.length || 0) >= len) continue;
      byUrl.set(e.request.url, e);
    }
  }
  const recordedFetches = (opts.mode === "replay" && opts.fetchLog && existsSync(opts.fetchLog))
    ? JSON.parse(readFileSync(opts.fetchLog, "utf8")) : [];
  const fetchLog = [];
  const warnings = [];
  const pageUrlFromHar = () => {
    if (!harJson) return null;
    const title = (harJson.log.pages || [])[0]?.title;
    if (/^https?:\/\//.test(title || "") && byUrl.has(title)) return title;
    const first = harJson.log.entries[0]?.request.url || null;
    if (/^https?:\/\//.test(title || "")) {
      warnings.push(`the HAR does not hold the page itself (${title}): it was exported after the page loaded,`
        + ` so its first document is replayed instead (${first}). Reload the page with the network panel open, then export.`);
    }
    return first;
  };
  const startUrl = opts.url || pageUrlFromHar();
  if (!startUrl) return { error: "no URL to open" };
  const harUa = harJson
    ? ((harJson.log.entries.find((e) => e.request.url === startUrl) || harJson.log.entries[0])
      ?.request.headers || []).find((h) => /^user-agent$/i.test(h.name))?.value
    : null;
  const ua = opts.ua || harUa || FIREFOX_ANDROID_UA;

  // ── the background, stubbed `browser` first, then the real graph ──
  const registrations = [];
  const evt = (path) => ({ addListener(fn, filter, extra) { registrations.push({ path, fn, filter, extra }); } });
  const native = [];
  const logs = [];
  const filters = new Map();
  let page = null;
  const frameIds = new Map();     // Frame → id
  const framesById = new Map();   // id → Frame
  let nextFrameId = 1;
  const urlFrame = new Map();     // a URL the page fetched or reported → the frame id
  const reports = [];
  const realLog = console.log;
  const realWarn = console.warn;
  console.log = (...a) => logs.push(a.map((x) => (typeof x === "string" ? x : safeJson(x))).join(" "));
  console.warn = console.log;

  const idOf = (frame) => {
    if (!frame) return 0;
    if (page && frame === page.mainFrame()) return 0;
    let id = frameIds.get(frame);
    if (id === undefined) {
      id = nextFrameId++;
      frameIds.set(frame, id);
      framesById.set(id, frame);
    }
    return id;
  };
  const frameInfo = (id) => {
    const f = id === 0 ? page?.mainFrame() : framesById.get(id);
    if (!f) return null;
    const parent = f.parentFrame();
    return { frameId: id, parentFrameId: parent ? idOf(parent) : -1, url: f.url() };
  };
  const listenersFor = (path, url, type) => registrations.filter((r) => r.path === path
    && (!r.filter?.types || r.filter.types.includes(type))
    && (r.filter?.urls || ["<all_urls>"]).some((p) => patternMatches(p, url)));
  const pending = [];
  const fire = (path, url, type, details) => {
    for (const r of listenersFor(path, url, type)) {
      try {
        const out = r.fn(details);
        if (out && typeof out.then === "function") pending.push(out.catch((e) => errors.push(`${path}: ${e?.message}`)));
      } catch (e) {
        errors.push(`${path} listener threw: ${e?.message}`);
      }
    }
  };
  const feedFilters = (requestId, body) => {
    const list = filters.get(requestId) || [];
    filters.delete(requestId);
    for (const f of list) {
      try {
        if (body && body.length) {
          const u8 = new Uint8Array(body);
          f.ondata?.({ data: u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength) });
        }
        f.onstop?.();
      } catch (e) {
        errors.push(`filter threw: ${e?.message}`);
      }
    }
  };
  const failFilters = (requestId) => {
    const list = filters.get(requestId) || [];
    filters.delete(requestId);
    for (const f of list) {
      try { f.onerror?.(); } catch (_) { /* a filter's own bug */ }
    }
  };

  // The extension's own requests: answered by the network (live) or the
  // recording (replay), and SHOWN to the listeners the way Firefox shows an
  // extension its own requests (tab -1, a moz-extension origin) — the HEAD
  // probe files its headers through onSendHeaders, and a parser's filters
  // see its own fetch.
  let ownSeq = 0;
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input?.url || input);
    const method = (init.method || "GET").toUpperCase();
    const requestId = `own${++ownSeq}`;
    const base = { requestId, url, method, type: "xmlhttprequest", tabId: -1, frameId: 0, parentFrameId: -1,
      documentUrl: EXT_ORIGIN, originUrl: EXT_ORIGIN, timeStamp: Date.now() };
    fire("webRequest.onBeforeRequest", url, "xmlhttprequest", base);
    const requestHeaders = [{ name: "User-Agent", value: ua }, { name: "Accept", value: "*/*" }];
    if (init.referrer && /^https?:/.test(init.referrer)) requestHeaders.push({ name: "Referer", value: init.referrer });
    fire("webRequest.onBeforeSendHeaders", url, "xmlhttprequest", { ...base, requestHeaders });
    fire("webRequest.onSendHeaders", url, "xmlhttprequest", { ...base, requestHeaders });
    let status = 0;
    let headers = [];
    let body = new Uint8Array(0);
    try {
      if (opts.mode === "replay") {
        const hit = recordedFetches.find((f) => f.url === url && f.method === method && !f.used);
        if (hit) {
          hit.used = true;
          status = hit.status;
          headers = hit.headers || [];
          body = hit.body ? new Uint8Array(Buffer.from(hit.body, "base64")) : new Uint8Array(0);
        } else if (byUrl && byUrl.has(url)) {
          const e = byUrl.get(url);
          status = e.response.status === 304 || !e.response.status ? 200 : e.response.status;
          headers = (e.response.headers || []).map((h) => ({ name: h.name, value: h.value }));
          const c = e.response.content || {};
          if (method !== "HEAD" && c.text) body = new Uint8Array(c.encoding === "base64" ? Buffer.from(c.text, "base64") : Buffer.from(c.text, "utf8"));
        } else {
          status = method === "HEAD" ? 200 : 404;
        }
      } else {
        const h = Object.fromEntries(requestHeaders.map((x) => [x.name, x.value]));
        if (init.headers) {
          const extra = init.headers instanceof Headers ? Object.fromEntries(init.headers) : init.headers;
          Object.assign(h, extra);
        }
        const resp = await realFetch(url, { method, headers: h, body: init.body, redirect: "follow",
          signal: init.signal || AbortSignal.timeout(20000) });
        status = resp.status;
        headers = [...resp.headers].map(([name, value]) => ({ name, value }));
        if (method !== "HEAD") body = new Uint8Array(await resp.arrayBuffer());
        fetchLog.push({ url, method, status, headers,
          body: body.length && body.length <= 4 * 1024 * 1024 ? Buffer.from(body).toString("base64") : "" });
      }
    } catch (e) {
      fire("webRequest.onErrorOccurred", url, "xmlhttprequest", { ...base, error: geckoError(e?.message) });
      failFilters(requestId);
      if (opts.mode !== "replay") fetchLog.push({ url, method, status: 0, headers: [], body: "" });
      throw e;
    }
    const done = { ...base, statusCode: status, responseHeaders: headers, fromCache: false };
    fire("webRequest.onHeadersReceived", url, "xmlhttprequest", done);
    feedFilters(requestId, body);
    fire("webRequest.onResponseStarted", url, "xmlhttprequest", done);
    fire("webRequest.onCompleted", url, "xmlhttprequest", done);
    return new Response(method === "HEAD" || status === 204 || status === 304 ? null : body,
      { status: status || 200, headers: headers.filter((x) => !/^(content-encoding|transfer-encoding)$/i.test(x.name)).map((x) => [x.name, x.value]) });
  };
  Object.defineProperty(globalThis, "navigator", {
    value: { userAgent: ua, languages: ["en-US", "en"], language: "en-US" }, configurable: true, writable: true,
  });

  globalThis.browser = {
    runtime: {
      sendNativeMessage: async (app, msg) => {
        if (msg?.kind === "get-debug-flag") return true;
        native.push({ app, msg: safeClone(msg), at: Date.now() - t0, via: viaOf(new Error().stack) });
        return false;
      },
      sendMessage: async () => undefined,
      onMessage: evt("runtime.onMessage"),
      connectNative: () => ({ onMessage: evt("port.onMessage"), onDisconnect: evt("port.onDisconnect"), postMessage() {} }),
      getURL: (p) => `moz-extension://firedown-sim/${p}`,
    },
    webRequest: {
      ...Object.fromEntries(["onBeforeRequest", "onBeforeSendHeaders", "onSendHeaders", "onHeadersReceived",
        "onResponseStarted", "onCompleted", "onErrorOccurred"].map((k) => [k, evt(`webRequest.${k}`)])),
      filterResponseData(requestId) {
        const f = { ondata: null, onstop: null, onerror: null, status: "transferringdata",
          write() {}, close() {}, disconnect() {}, suspend() {}, resume() {} };
        (filters.get(requestId) ?? filters.set(requestId, []).get(requestId)).push(f);
        return f;
      },
    },
    webNavigation: {
      onHistoryStateUpdated: evt("webNavigation.onHistoryStateUpdated"),
      getFrame: async ({ frameId }) => frameInfo(frameId),
    },
    tabs: {
      onUpdated: evt("tabs.onUpdated"), onRemoved: evt("tabs.onRemoved"), onActivated: evt("tabs.onActivated"),
      query: async () => [tabObj()],
      get: async (id) => (id === TAB ? tabObj() : Promise.reject(new Error("no such tab"))),
      sendMessage: async (tabId, msg, o) => {
        const id = o?.frameId ?? 0;
        const frame = id === 0 ? page?.mainFrame() : framesById.get(id);
        if (!frame || frame.isDetached()) throw new Error("Could not establish connection. Receiving end does not exist.");
        return frame.evaluate((m) => (window.__fdAsk ? window.__fdAsk(m) : undefined), msg);
      },
    },
    cookies: { onChanged: evt("cookies.onChanged"), getAll: async () => [] },
    storage: { local: { get: async () => ({}), set: async () => {}, remove: async () => {} } },
  };
  function tabObj() {
    return { id: TAB, url: page ? page.url() : startUrl, title: "", incognito: false, active: true, windowId: 1 };
  }

  try {
    await import(pathToFileURL(join(ext, "js/parsers/index.js")));
    await sleep(50);
  } catch (e) {
    console.log = realLog;
    console.warn = realWarn;
    return { error: `background graph failed to load: ${e?.stack || e}` };
  }
  for (const r of registrations.filter((x) => x.path === "tabs.onActivated")) {
    try { r.fn({ tabId: TAB, previousTabId: -1, windowId: 1 }); } catch (_) { /* ignore */ }
  }

  // ── the browser ──
  const browserType = opts.browser === "firefox" ? pw.firefox : pw.chromium;
  const launch = { headless: !opts.headed };
  if (opts.browser !== "firefox") {
    launch.args = ["--autoplay-policy=no-user-gesture-required", "--mute-audio"];
    if (opts.mode === "replay") launch.args.push("--no-proxy-server");
  } else {
    launch.firefoxUserPrefs = { "media.autoplay.default": 0, "media.volume_scale": "0.0" };
  }
  let browser;
  try {
    // Chromium's full browser in its new headless mode is harder for a bot
    // wall to tell from a person than the headless shell; the shell is the
    // fallback when only it is installed.
    if (opts.browser !== "firefox" && opts.mode !== "replay") {
      browser = await browserType.launch({ ...launch, channel: "chromium" }).catch(() => null);
    }
    if (!browser) browser = await browserType.launch(launch);
  } catch (e) {
    console.log = realLog;
    console.warn = realWarn;
    return { error: `could not launch ${opts.browser || "chromium"}: ${e?.message}` };
  }
  const ctxOpts = { userAgent: ua, viewport: { width: 412, height: 900 }, ignoreHTTPSErrors: false };
  if (opts.browser !== "firefox") Object.assign(ctxOpts, { isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  if (opts.record) ctxOpts.recordHar = { path: opts.record, content: "attach", mode: "full" };
  if (opts.locale) ctxOpts.locale = opts.locale;
  const ctx = await browser.newContext(ctxOpts);

  // The phone runs uBlock; ad tech never loads there. Refused the way uBlock
  // refuses (ERR_BLOCKED_BY_CLIENT, seen by the background as NS_ERROR_ABORT,
  // which is what its blocked-URL rule keys on). A recording holds the
  // refusals, so a replay sees the same page.
  if (opts.mode !== "replay" && opts.adblock !== false) {
    await ctx.route((u) => AD_HOST_RE.test(u.hostname), (route) => route.abort("blockedbyclient"));
  }
  if (opts.mode === "replay") {
    if (harIsZip) {
      await ctx.routeFromHAR(opts.har, { notFound: "abort" });
    } else {
      await ctx.route("**/*", (route) => {
        const req = route.request();
        const e = byUrl.get(req.url());
        const c = e?.response?.content || {};
        if (!e || !c.text) return route.abort();
        const status = (e.response.status === 304 || !e.response.status) ? 200 : e.response.status;
        const body = c.encoding === "base64" ? Buffer.from(c.text, "base64") : Buffer.from(c.text, "utf8");
        const headers = {};
        for (const h of e.response.headers || []) {
          if (/^(content-encoding|content-length|transfer-encoding|set-cookie)$/i.test(h.name)) continue;
          headers[h.name.toLowerCase()] = h.value;
        }
        headers["access-control-allow-origin"] = headers["access-control-allow-origin"] || "*";
        return route.fulfill({ status: status >= 300 && status < 400 && !headers.location ? 200 : status,
          contentType: c.mimeType || "application/octet-stream", body, headers });
      });
    }
  }

  await ctx.exposeBinding("__fdReport", (source, m) => onReport(source.frame, m));
  await ctx.addInitScript(PAGE_STUB);
  // The content script's top level declares lets; a block keeps them off
  // the page's globals.
  await ctx.addInitScript(`(() => {\n${readFileSync(opts.cs || join(ext, "js/content-script.js"), "utf8")}\n})();`);
  await ctx.addInitScript(readFileSync(opts.bridge || join(ext, "js/page-state-bridge.js"), "utf8"));
  page = await ctx.newPage();
  // A pop-up (an ad, a "watch on the site" window) is another tab; close it.
  ctx.on("page", (p) => { if (p !== page) p.close().catch(() => {}); });

  function onReport(frame, m) {
    if (!m || typeof m !== "object") return;
    const frameId = idOf(frame);
    const frameUrl = frame.url();
    reports.push({ ms: Date.now() - t0, frameId, frameUrl, kind: m.kind, m });
    for (const u of reportedUrls(m)) if (!urlFrame.has(u)) urlFrame.set(u, frameId);
    const sender = { tab: tabObj(), frameId, url: frameUrl, id: "downloader@solarized.dev", envType: "content_child" };
    for (const r of registrations.filter((x) => x.path === "runtime.onMessage")) {
      try {
        const out = r.fn(m, sender, () => {});
        if (out && typeof out.then === "function") pending.push(out.catch((e) => errors.push(`onMessage: ${e?.message}`)));
      } catch (e) {
        errors.push(`onMessage threw: ${e?.message}`);
      }
    }
  }

  // ── the wire ──
  const reqIds = new WeakMap();
  const chains = new Map();
  let reqSeq = 0;
  const chain = (rid, fn) => {
    const next = (chains.get(rid) || Promise.resolve()).then(fn).catch((e) => errors.push(`wire ${rid}: ${e?.message}`));
    chains.set(rid, next);
    pending.push(next);
    return next;
  };
  const detailsOf = (req) => {
    let frame = null;
    try { frame = req.frame(); } catch (_) { frame = null; }
    const rtype = req.resourceType();
    const isNav = req.isNavigationRequest() && rtype === "document";
    const isMain = isNav && frame === page.mainFrame();
    const type = isNav ? (isMain ? "main_frame" : "sub_frame") : (TYPE_BY_RESOURCE[rtype] || "other");
    const fid = idOf(frame);
    const parent = frame ? frame.parentFrame() : null;
    const parentUrl = parent ? parent.url() : undefined;
    const frameUrl = frame ? frame.url() : undefined;
    return {
      type,
      frameId: isMain ? 0 : fid,
      parentFrameId: isNav ? (isMain ? -1 : (parent ? idOf(parent) : 0)) : (frame && parent ? idOf(parent) : -1),
      documentUrl: isMain ? undefined : (isNav ? parentUrl : frameUrl),
      originUrl: isMain ? undefined : (isNav ? parentUrl : frameUrl),
    };
  };
  page.on("request", (req) => {
    const url = req.url();
    if (!/^https?:/.test(url)) return;
    const from = req.redirectedFrom();
    const rid = from && reqIds.has(from) ? reqIds.get(from) : `r${++reqSeq}`;
    reqIds.set(req, rid);
    const d = { requestId: rid, url, method: req.method(), tabId: TAB, timeStamp: Date.now(), ...detailsOf(req) };
    if (!urlFrame.has(url)) urlFrame.set(url, d.frameId);
    req.__fd = d;
    fire("webRequest.onBeforeRequest", url, d.type, d);
    chain(rid, async () => {
      let requestHeaders = [];
      try { requestHeaders = (await req.headersArray()).map((h) => ({ name: h.name, value: h.value })); } catch (_) { /* gone */ }
      fire("webRequest.onBeforeSendHeaders", url, d.type, { ...d, requestHeaders });
      fire("webRequest.onSendHeaders", url, d.type, { ...d, requestHeaders });
    });
  });
  page.on("response", (res) => {
    const req = res.request();
    const d = req.__fd;
    if (!d) return;
    chain(d.requestId, async () => {
      let responseHeaders = [];
      try { responseHeaders = (await res.headersArray()).map((h) => ({ name: h.name, value: h.value })); } catch (_) { /* gone */ }
      const done = { ...d, statusCode: res.status(), responseHeaders, fromCache: false };
      req.__fdDone = done;
      fire("webRequest.onHeadersReceived", d.url, d.type, done);
      fire("webRequest.onResponseStarted", d.url, d.type, done);
    });
  });
  page.on("requestfinished", (req) => {
    const d = req.__fd;
    if (!d) return;
    chain(d.requestId, async () => {
      const done = req.__fdDone || { ...d, statusCode: 200, responseHeaders: [] };
      if (done.statusCode >= 300 && done.statusCode < 400) return;
      if (filters.has(d.requestId)) {
        let body = null;
        try {
          const res = await req.response();
          body = res ? await res.body() : null;
        } catch (_) { body = null; }
        if (body) feedFilters(d.requestId, body);
        else failFilters(d.requestId);
      }
      fire("webRequest.onCompleted", d.url, d.type, done);
    });
  });
  page.on("requestfailed", (req) => {
    const d = req.__fd;
    if (!d) return;
    chain(d.requestId, async () => {
      failFilters(d.requestId);
      fire("webRequest.onErrorOccurred", d.url, d.type, { ...d, error: geckoError(req.failure()?.errorText) });
    });
  });
  page.on("framenavigated", (frame) => {
    if (frame !== page.mainFrame()) return;
    const url = frame.url();
    for (const r of registrations.filter((x) => x.path === "tabs.onUpdated")) {
      try { r.fn(TAB, { url, status: "loading" }, tabObj()); } catch (_) { /* ignore */ }
    }
  });
  page.on("load", () => {
    for (const r of registrations.filter((x) => x.path === "tabs.onUpdated")) {
      try { r.fn(TAB, { status: "complete" }, tabObj()); } catch (_) { /* ignore */ }
    }
  });

  // ── drive the page ──
  const consentClicks = [];
  const tryConsent = async () => {
    for (const f of page.frames()) {
      if (f.isDetached()) continue;
      try {
        const hit = await f.evaluate(CONSENT_FN);
        if (hit) consentClicks.push({ ms: Date.now() - t0, frame: f.url().slice(0, 120), hit });
      } catch (_) { /* a frame navigating away */ }
    }
  };
  let scrollInfo = null;
  try {
    mark("goto");
    await page.goto(startUrl, { waitUntil: "domcontentloaded", timeout: opts.timeout || 45000 });
    mark("domcontentloaded");
    if (opts.consent !== false) {
      for (const at of [1500, 4000, 8000]) {
        await sleep(Math.max(0, at - (Date.now() - t0 - (timeline[1]?.ms || 0))));
        await tryConsent();
      }
      mark("consent");
    }
    if (opts.scroll !== false) {
      scrollInfo = await page.evaluate(SCROLL_FN).catch(() => null);
      mark("scrolled");
      if (opts.consent !== false) await tryConsent();
    }
    await sleep(opts.wait ?? 10000);
    mark("waited");
  } catch (e) {
    errors.push(`page: ${e?.message?.split("\n")[0]}`);
  }

  // The census, every frame.
  const census = [];
  for (const f of page.frames()) {
    if (f.isDetached()) continue;
    try {
      const c = await f.evaluate(CENSUS_FN);
      c.frameId = idOf(f);
      const parent = f.parentFrame();
      c.parentFrameId = parent ? idOf(parent) : -1;
      census.push(c);
    } catch (_) { /* cross-process frame gone */ }
  }
  mark("census");
  // Let the background finish what it started (the late metadata wait, a
  // held sub-frame report, a parser's lookup) while the page can still answer.
  await Promise.race([Promise.allSettled(pending), sleep(8000)]);
  await sleep(1500);
  await Promise.race([Promise.allSettled(pending), sleep(4000)]);
  mark("settled");
  const finalUrl = page.url();
  try { await ctx.close(); } catch (e) { errors.push(`close: ${e?.message}`); }
  try { await browser.close(); } catch (_) { /* ignore */ }
  for (const r of registrations.filter((x) => x.path === "tabs.onRemoved")) {
    try { r.fn(TAB, { windowId: 1, isWindowClosing: false }); } catch (_) { /* ignore */ }
  }
  if (opts.record && opts.fetchLog && opts.mode !== "replay") {
    writeFileSync(opts.fetchLog, JSON.stringify(fetchLog));
  }
  console.log = realLog;
  console.warn = realWarn;

  const rejects = {};
  for (const line of logs) {
    const k = /\[req\] (reject:[\w-]+)/.exec(line);
    if (k) rejects[k[1]] = (rejects[k[1]] || 0) + 1;
  }
  return {
    url: startUrl, finalUrl, ua, browser: opts.browser || "chromium", mode: opts.mode,
    ms: Date.now() - t0, timeline, consentClicks, scroll: scrollInfo,
    native: native.map((n) => ({ ...n, frameId: frameOfMsg(n.msg, urlFrame) })),
    reports: reports.map((r) => ({ ms: r.ms, frameId: r.frameId, frameUrl: r.frameUrl, kind: r.kind, m: r.m })),
    census, rejects, errors, warnings, logs,
  };
}

// ── helpers ───────────────────────────────────────────────────────────────
// A minimal ZIP reader (stored and deflated entries, no ZIP64): enough for a
// HAR zipped by Firefox or recorded by Playwright, without a dependency.
function zipEntries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("not a zip file");
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let n = 0; n < count && buf.readUInt32LE(off) === 0x02014b50; n++) {
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    out.push({ name: buf.toString("utf8", off + 46, off + 46 + nameLen), method: buf.readUInt16LE(off + 10),
      csize: buf.readUInt32LE(off + 20), local: buf.readUInt32LE(off + 42) });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}
function zipRead(buf, e) {
  const start = e.local + 30 + buf.readUInt16LE(e.local + 26) + buf.readUInt16LE(e.local + 28);
  const data = buf.subarray(start, start + e.csize);
  return e.method === 0 ? data : inflateRawSync(data);
}
function reportedUrls(m) {
  const out = [];
  if (Array.isArray(m.urls)) out.push(...m.urls);
  const p = m.payload || {};
  if (p.url) out.push(p.url);
  for (const v of p.variants || []) if (v && v.url) out.push(v.url);
  for (const s of p.siblings || []) if (typeof s === "string") out.push(s);
  return out.filter((u) => typeof u === "string");
}
function frameOfMsg(msg, urlFrame) {
  const urls = [msg?.url, ...((msg?.variants || []).map((v) => v?.url))].filter(Boolean);
  for (const u of urls) if (urlFrame.has(u)) return urlFrame.get(u);
  return null;
}
// Which module sent a native message: the first stack frame in the
// extension's tree that is not the shared emit helpers.
function viaOf(stack) {
  const files = [];
  for (const line of String(stack || "").split("\n")) {
    const m = /webrequests\/js\/([\w/.-]+\.js)/.exec(line);
    if (m && !files.includes(m[1])) files.push(m[1]);
  }
  const own = files.find((f) => !/^parsers\/common\.js$/.test(f));
  return own || files[0] || "";
}
function safeClone(x) {
  try { return JSON.parse(JSON.stringify(x)); } catch (_) { return { unclonable: true }; }
}
function safeJson(x) {
  try { return JSON.stringify(x); } catch (_) { return String(x); }
}
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
