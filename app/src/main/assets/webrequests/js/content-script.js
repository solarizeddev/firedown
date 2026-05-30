// content-script.js — runs in every page
// Scans the DOM for image URLs (including those served from service-worker
// cache, which webRequest cannot see) and forwards them to the background.
// Also exposes a "get-page-metadata" message endpoint that the background
// uses to enrich intercepted media downloads with a descriptive filename
// (page title + meta description). Lives in the page context so it sees
// JS-rendered titles that a server-side fetch wouldn't.

console.log('[cs] loaded', location.href);

// ---------------------------------------------------------------------------
// WebAssembly unavailability detector
// WASM is disabled by default in Firedown for privacy (javascript.options.wasm
// = false). Some sites (kick.com, codepen, figma) hard-require it and break.
//
// WebExtension content scripts run in an *isolated* JS world: wrapping
// `console.error` or `window.WebAssembly` here does NOT intercept the page's
// own console / globals — those live in the page world. Only window-level DOM
// events are shared. The first version of this detector ran the wrappers in
// the isolated world and missed every error from kick.com's player, even
// though `[cs] loaded` proved the script was injected.
//
// Fix: inject a <script> tag whose textContent runs in the page world. That
// script wraps the page's console.error, hooks window.error /
// unhandledrejection, and dispatches a CustomEvent on document when it spots
// a WASM-related message. The isolated-world listener below picks up the
// event and forwards it through the native messaging port to Java.
// ---------------------------------------------------------------------------
(() => {
  const SIGNAL_EVENT = '__firedown_wasm_unavailable__';

  // --- Isolated-world receiver: page → content script → native ----------
  let reported = false;
  document.addEventListener(SIGNAL_EVENT, (e) => {
    if (reported) return;
    reported = true;
    // CustomEvent.detail crosses the Xray boundary as a wrapped object; pull
    // the string fields defensively. location.href works because the content
    // script's window IS the page's window (just an isolated wrapper).
    const detail = (e && e.detail && (e.detail.detail || e.detail.wrappedJSObject?.detail)) || '';
    const payload = {
      kind: 'wasm-unavailable',
      listener: 'wasmUnavailable',
      url: location.href,
      detail: String(detail).slice(0, 200),
    };
    console.log('[cs] wasm-unavailable reporting', location.href, detail);
    try {
      const r = browser.runtime.sendNativeMessage('browser', payload);
      if (r && r.catch) r.catch(() => fallback(payload));
    } catch (_) { fallback(payload); }
  }, true);

  function fallback(payload) {
    try { browser.runtime.sendMessage(payload); } catch (_) {}
  }

  // The probe signals this the moment it runs; if we never hear it, the page
  // refused even the external moz-extension script (CSP) and wasm detection is
  // off on this page — surfaced by the timeout warning below.
  let probeRan = false;
  document.addEventListener('__firedown_probe_alive__', () => {
    probeRan = true;
  }, { capture: true, once: true });

  // --- Synchronous pre-arm of the WebAssembly read-trap -------------------
  // The external probe below loads async, so a very early page script could
  // read WebAssembly before it arms. Content scripts run synchronously at
  // document_start, before any page script, so additionally install the
  // read-trap right now via Firefox's Xray wrappedJSObject — no <script>, so
  // it's also immune to the page CSP. Strictly additive and fully guarded:
  // only acts when wasm is disabled (the global is removed), and silently
  // falls back to the external probe if any Xray API is missing or throws.
  // Sets a flag so the external probe doesn't re-install (and doesn't trip
  // this getter by reading the global).
  try {
    if (typeof exportFunction === 'function') {
      const pageWin = window.wrappedJSObject;
      if (pageWin && typeof pageWin.WebAssembly === 'undefined' && !pageWin.__firedown_wasm_prearmed) {
        let prearmFired = false;
        const getter = exportFunction(function () {
          if (!prearmFired) {
            prearmFired = true;
            try {
              document.dispatchEvent(new CustomEvent('__firedown_wasm_unavailable__', {
                detail: { detail: 'WebAssembly read (pre-arm)' }
              }));
            } catch (_) {}
          }
          return undefined;
        }, pageWin);
        Object.defineProperty(pageWin, 'WebAssembly', { configurable: true, get: getter });
        pageWin.__firedown_wasm_prearmed = true;
      }
    }
  } catch (_) { /* Xray unavailable / refused — the external probe handles it */ }

  // --- Page-world probe injection -----------------------------------------
  // The probe must run in the PAGE world to hook the page's WebAssembly /
  // console / error surfaces. We load it as an EXTERNAL moz-extension:
  // web_accessible_resource <script>, NOT inline: strict-CSP sites (x.com
  // ships script-src with a nonce / strict-dynamic, which voids
  // 'unsafe-inline') refuse an injected inline <script>, so the old
  // textContent probe never executed there. Extension-origin resource loads
  // bypass the page CSP, so the external probe runs everywhere.
  try {
    const s = document.createElement('script');
    s.src = browser.runtime.getURL('js/wasm-probe.js');
    s.async = false; // execute ASAP, before the page's own scripts use wasm
    s.onload = () => s.remove();
    (document.documentElement || document.head || document.body).appendChild(s);
  } catch (e) {
    console.log('[cs] wasm probe injection failed:', e && e.message);
  }

  // CSP-block detector: if the probe never signals within 1.5s, even the
  // external moz-extension script was refused — we'd need a deeper hook.
  setTimeout(() => {
    if (!probeRan) {
      console.log('[cs] WASM-DEBUG: page-world probe DID NOT RUN within 1.5s on '
        + location.href + ' — even the external web_accessible_resource script '
        + 'was blocked. Page CSP is rejecting moz-extension: scripts too.');
    }
  }, 1500);
})();


// Top-frame metadata responder. We only answer in the top frame so the
// background's tabs.sendMessage (which broadcasts to all frames in a tab
// by default) doesn't return an iframe's title instead of the page's.
if (window === window.top) {
  // Walk the page's JSON-LD blocks for a VideoObject (schema.org). On video
  // SPAs (YouTube, etc.) the <title>/og: tags are often generic or stale
  // ("YouTube") while the JSON-LD VideoObject carries the real, current
  // video name + description — so for media captures this is the most
  // accurate source. Returns {name, description} or null.
  //
  // Robust against the shapes seen in the wild:
  //   - a bare object, an array of objects, or nodes under @graph
  //   - a VideoObject *nested* as a property (WebPage.video, mainEntity,
  //     ItemList.itemListElement[].item, …) rather than top-level — so we
  //     recurse the whole tree, depth-limited, instead of only scanning the
  //     first level
  //   - @type as a string OR an array of strings
  //   - name/description as a plain string, a localized {@value:"…"} object,
  //     or an array of either (take the first usable string)
  // Defensive throughout: each script block is parsed in isolation (one
  // malformed block must not kill the rest), recursion is depth- and
  // breadth-bounded so a pathological page can't hang the responder, and we
  // prefer a VideoObject that has a name, accepting description-only last.
  const MAX_LD_DEPTH = 8;
  const MAX_LD_NODES = 5000;

  const ldString = (v) => {
    if (typeof v === 'string') return v.trim();
    if (Array.isArray(v)) {
      for (const item of v) {
        const s = ldString(item);
        if (s) return s;
      }
      return '';
    }
    if (v && typeof v === 'object' && typeof v['@value'] === 'string') {
      return v['@value'].trim();
    }
    return '';
  };

  const isVideoType = (t) =>
    t === 'VideoObject' || (Array.isArray(t) && t.includes('VideoObject'));

  const readVideoJsonLd = () => {
    const scripts = document.querySelectorAll('script[type="application/ld+json"]');
    let descriptionOnly = null; // fallback if no node has a name
    let budget = MAX_LD_NODES;

    // Returns a {name, description} with a name as soon as one is found
    // (best result); otherwise records a description-only candidate and
    // keeps looking. Iterative stack walk with a visited set to bound cost
    // and survive cyclic references.
    const search = (root) => {
      const stack = [{ node: root, depth: 0 }];
      const seen = new Set();
      while (stack.length) {
        if (budget-- <= 0) break;
        const { node, depth } = stack.pop();
        if (!node || typeof node !== 'object' || depth > MAX_LD_DEPTH) continue;
        if (seen.has(node)) continue;
        seen.add(node);

        if (isVideoType(node['@type'])) {
          const name = ldString(node.name);
          const description = ldString(node.description);
          if (name) return { name, description };
          if (description && !descriptionOnly) {
            descriptionOnly = { name: '', description };
          }
        }

        // Descend into children (array entries and object property values).
        if (Array.isArray(node)) {
          for (const item of node) {
            if (item && typeof item === 'object') stack.push({ node: item, depth: depth + 1 });
          }
        } else {
          for (const key in node) {
            if (key === '@type') continue;
            const val = node[key];
            if (val && typeof val === 'object') stack.push({ node: val, depth: depth + 1 });
          }
        }
      }
      return null;
    };

    for (const s of scripts) {
      let data;
      try {
        data = JSON.parse(s.textContent);
      } catch (_) {
        continue;
      }
      const hit = search(data);
      if (hit) return hit;
    }
    return descriptionOnly;
  };

  browser.runtime.onMessage.addListener((msg, sender) => {
    if (!msg || msg.kind !== 'get-page-metadata') return;
    const meta = (selector, attr) => {
      const el = document.querySelector(selector);
      return el && el.getAttribute(attr) ? el.getAttribute(attr).trim() : '';
    };
    // og: and twitter: properties are sometimes carried on name= instead of
    // property= (and vice-versa) depending on the site's templating — accept
    // either so we don't miss a tag over an attribute-name technicality.
    const ogp = (prop) =>
      meta(`meta[property="${prop}"]`, 'content') || meta(`meta[name="${prop}"]`, 'content');
    const videoLd = readVideoJsonLd();
    return Promise.resolve({
      url: location.href,
      title: document.title || '',
      description: ogp('description'),
      ogTitle: ogp('og:title'),
      ogDescription: ogp('og:description'),
      twitterTitle: ogp('twitter:title'),
      twitterDescription: ogp('twitter:description'),
      // JSON-LD VideoObject + og:video:* — most accurate on video SPAs, where
      // <title>/og:title are often the generic site name. og:video:title is
      // rarer than og:title but, when present, is video-specific so we rank it
      // above the page-level og:title in the consumer.
      ogVideoTitle: ogp('og:video:title'),
      videoLdName: videoLd ? videoLd.name : '',
      videoLdDescription: videoLd ? videoLd.description : '',
    });
  });
}

(() => {
  const seen = new Set();
  const BATCH_MS = 200;
  let pending = [];
  let flushTimer = null;

  function flush() {
    flushTimer = null;
    if (pending.length === 0) return;
    const batch = pending;
    pending = [];
    console.log('[cs] sending batch of', batch.length);
    try {
      const p = browser.runtime.sendMessage({ kind: 'images-detected', urls: batch });
      if (p && p.catch) p.catch((e) => console.log('[cs] send rejected:', e?.message));
    } catch (e) {
      console.log('[cs] send threw:', e?.message);
    }
  }

  function queue(url) {
    if (!url || seen.has(url)) return;
    if (!/^https?:/i.test(url)) return;
    seen.add(url);
    pending.push(url);
    if (!flushTimer) flushTimer = setTimeout(flush, BATCH_MS);
  }

  function reportImg(img) {
    if (!img) return;
    queue(img.currentSrc || img.src);
  }

  function reportSource(source) {
    if (!source) return;
    const srcset = source.srcset || source.getAttribute('srcset');
    if (srcset) {
      srcset.split(',').forEach((part) => {
        const url = part.trim().split(/\s+/)[0];
        if (url) queue(url);
      });
    }
    if (source.src) queue(source.src);
  }

  function scan(root) {
    if (!root || root.nodeType !== 1) return;
    if (root.tagName === 'IMG') reportImg(root);
    else if (root.tagName === 'SOURCE') reportSource(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('img').forEach(reportImg);
      root.querySelectorAll('source').forEach(reportSource);
    }
  }

  // Initial scan
  scan(document.documentElement);

  // Re-scan at key lifecycle events
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => scan(document.documentElement));
  }
  window.addEventListener('load', () => scan(document.documentElement));

  // Watch for DOM changes
  const mo = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === 'childList') {
        m.addedNodes.forEach(scan);
      } else if (m.type === 'attributes') {
        const t = m.target;
        if (t.tagName === 'IMG') reportImg(t);
        else if (t.tagName === 'SOURCE') reportSource(t);
      }
    }
  });

  function startObserver() {
    const target = document.body || document.documentElement;
    if (!target) {
      setTimeout(startObserver, 50);
      return;
    }
    mo.observe(target, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['src', 'srcset'],
    });
  }
  startObserver();

  // Catch images that finish loading after insertion (lazy load)
  document.addEventListener('load', (e) => {
    if (e.target && e.target.tagName === 'IMG') reportImg(e.target);
  }, true);

  console.log('[cs] setup complete');
})();