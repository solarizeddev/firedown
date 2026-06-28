// snapshot.js — "Save snapshot": a self-contained HTML archive of the page.
//
// WHAT THIS IS
// A SingleFile-style page archiver. On demand it freezes the *live, post-JS*
// DOM of the top document, inlines every reachable sub-resource (CSS, images,
// fonts, the favicon) as data: URIs, neuters scripts, and writes ONE
// self-contained .html the user can keep "to eternity" — it opens in any
// browser years from now with zero network, no dead APIs, no SPA empty-shell.
//
// WHY A DOM FREEZE, NOT "download the JS/CSS"
// Preserving raw JS and hoping it re-executes offline is the fragile path:
// SPAs re-run, hit dead endpoints and render nothing; auth-gated fetches fail.
// What actually survives is the *rendered* DOM serialized into one file. So we
// capture the result, not the machinery — scripts are stripped on purpose.
//
// DELIVERY — why a blob <a download>, not a native message of the bytes
// A finished archive can be tens of MB. Rather than marshal that across the
// native-messaging bridge and hand-roll a public-folder write + a download-DB
// row in Java, we let the page itself initiate a download of a Blob via an
// <a download> click. GeckoView routes a download-attribute navigation through
// ContentDelegate.onExternalResponse — the SAME funnel "save image"/"save link"
// already use — so the snapshot rides the existing pipeline (download dialog →
// GeckoStreamStrategy → scoped-storage write in Download/Firedown → a real
// download entry) for free. This is exactly how SingleFile saves in Firefox,
// and GeckoView shares Gecko's download handling.
//
// WHY BACKGROUND-FETCH THE RESOURCES (the CORS reason)
// A content script's own fetch() is subject to CORS: a cross-origin image/font
// with no CORS headers comes back opaque and unreadable, so we could never turn
// it into a data: URI from here. The extension BACKGROUND (requests.js) holds
// <all_urls> host permission, so its fetch reads cross-origin bytes directly.
// We therefore collect resource URLs here and ask the background to fetch+encode
// them (kind:'snapshot-fetch'), then substitute the returned data: URIs. Same
// split SingleFile uses (content script + privileged background fetch).
//
// TRIGGER PATH
// Java (popup row) → GeckoRuntimeHelper.captureSnapshot() → "browser" port →
// requests.js relays kind:'snapshot-capture' to this tab's content script.
//
// SCOPE / KNOWN LIMITS (deliberate, do not "fix" by removing the caps)
//  - Top frame only (manifest all_frames defaults false). Cross-origin player
//    iframes serialize as their placeholder — a snapshot, not a mirror.
//  - Lazy/virtualized content captures only what has rendered (SingleFile has
//    the same limit — it's the price of freezing live state, not a bug).
//  - Resource inlining is bounded (count/per-resource/total byte caps,
//    per-fetch timeout). On a cap hit the archive still saves with the
//    un-inlined URLs left absolute (works online, degrades gracefully).

// Default all_frames is false, so this only injects in the top frame; the guard
// is belt-and-braces in case the manifest ever changes.
if (window.top === window.self) {
  // Debug flag, resolved from BuildConfig.DEBUG via the native bridge — every
  // log goes through slog() so release builds stay silent (CLAUDE.md "Logging
  // discipline"). Boot-time logs before the async reply simply don't print.
  let DEBUG = false;
  browser.runtime.sendNativeMessage('browser', { kind: 'get-debug-flag' })
    .then((r) => { DEBUG = (r === true); }, () => {});
  const slog = (...args) => { if (DEBUG) console.log('[snapshot]', ...args); };

  // Bounds. A snapshot is a deliberate, user-initiated save, so these are
  // generous — but a pathological page (an infinite-image gallery, a 100 MB
  // font) must not OOM the content process or hang the save forever.
  const MAX_RESOURCES = 400;             // distinct sub-resources inlined
  const MAX_TOTAL_DATAURI_CHARS = 80 * 1024 * 1024; // ~60 MB of binary
  const CSS_IMPORT_DEPTH = 4;            // @import nesting we follow

  let capturing = false;

  browser.runtime.onMessage.addListener((msg) => {
    if (msg?.kind !== 'snapshot-capture') return;
    // Re-entrancy guard: a double tap (or a relay that fanned out to frames)
    // must not start two concurrent serializations of the same page.
    if (capturing) return;
    capturing = true;
    slog('capture requested', location.href);
    captureSnapshot()
      .catch((e) => slog('capture failed', e?.message))
      .finally(() => { capturing = false; });
  });

  // ---------------------------------------------------------------------------
  // Privileged background fetch (bypasses CORS — see header).
  // Returns a data: URI for binary, or text for as:'text'. null on any failure.
  // ---------------------------------------------------------------------------
  async function fetchDataUri(url) {
    try {
      const r = await browser.runtime.sendMessage({ kind: 'snapshot-fetch', url });
      if (r && r.ok && r.dataUri) return r.dataUri;
    } catch (e) {
      slog('fetch fail', url, e?.message);
    }
    return null;
  }

  async function fetchText(url) {
    try {
      const r = await browser.runtime.sendMessage({ kind: 'snapshot-fetch', url, as: 'text' });
      if (r && r.ok && typeof r.text === 'string') return r.text;
    } catch (e) {
      slog('fetch-text fail', url, e?.message);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Capture
  // ---------------------------------------------------------------------------
  async function captureSnapshot() {
    const pageUrl = location.href;

    // Per-capture resource cache + total-size accounting. cache maps an
    // absolute URL → its data: URI (or null when it failed / was over budget),
    // so a resource referenced many times is fetched once.
    const cache = new Map();
    const budget = { chars: 0 };

    const abs = (u, base) => {
      try { return new URL(u, base).href; } catch { return null; }
    };

    // Inline one resource URL to a data: URI, honouring caches + budget.
    async function inlineUrl(raw, base) {
      const url = abs(raw, base);
      if (!url || !/^https?:/i.test(url)) return null;
      if (cache.has(url)) return cache.get(url);
      if (cache.size >= MAX_RESOURCES || budget.chars >= MAX_TOTAL_DATAURI_CHARS) {
        cache.set(url, null);
        return null;
      }
      const dataUri = await fetchDataUri(url);
      if (dataUri) budget.chars += dataUri.length;
      cache.set(url, dataUri);
      return dataUri;
    }

    // Rewrite a CSS body: follow @import (bounded depth) and inline url(...)
    // refs (images, fonts) to data: URIs, resolving relative to the sheet's
    // own base URL (NOT the page) so url(./x.png) in a CDN sheet resolves
    // against the CDN, like the browser does.
    async function inlineCss(cssText, baseUrl, depth) {
      if (typeof cssText !== 'string' || !cssText) return '';

      if (depth < CSS_IMPORT_DEPTH) {
        const imports = [...cssText.matchAll(
          /@import\s+(?:url\(\s*)?["']?([^"')]+)["']?\s*\)?[^;]*;/gi)];
        for (const m of imports) {
          const importedUrl = abs(m[1], baseUrl);
          let replacement = '';
          if (importedUrl) {
            const sub = await fetchText(importedUrl);
            if (sub != null) replacement = await inlineCss(sub, importedUrl, depth + 1);
          }
          cssText = cssText.split(m[0]).join(replacement);
        }
      }

      const urls = [...cssText.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/gi)];
      for (const m of urls) {
        const raw = m[1];
        if (/^data:/i.test(raw) || raw.startsWith('#')) continue; // data:/SVG-frag
        const dataUri = await inlineUrl(raw, baseUrl);
        if (dataUri) cssText = cssText.split(m[0]).join(`url("${dataUri}")`);
      }
      return cssText;
    }

    // 1) Deep-clone the live <html>, then copy dynamic state the clone misses
    //    (cloneNode snapshots attributes, not live form values / canvas pixels /
    //    the responsive image actually chosen). Parallel-walk live↔clone — a
    //    deep clone preserves child order, so index alignment holds.
    const clone = document.documentElement.cloneNode(true);
    syncDynamicState(document.documentElement, clone);

    // 2) Strip the machinery: scripts (we keep the rendered result, not the
    //    re-runnable app) and offline-useless resource hints.
    clone.querySelectorAll('script').forEach((n) => n.remove());
    clone.querySelectorAll(
      'link[rel~="preload" i],link[rel~="prefetch" i],link[rel~="modulepreload" i],link[rel~="dns-prefetch" i],link[rel~="preconnect" i]'
    ).forEach((n) => n.remove());

    // 3) Inline external stylesheets → <style>; rewrite inline <style> bodies.
    for (const link of [...clone.querySelectorAll('link[rel~="stylesheet" i][href]')]) {
      const href = abs(link.getAttribute('href'), pageUrl);
      const css = href ? await fetchText(href) : null;
      const style = clone.ownerDocument.createElement('style');
      style.textContent = css != null ? await inlineCss(css, href, 0) : '';
      const media = link.getAttribute('media');
      if (media) style.setAttribute('media', media);
      link.replaceWith(style);
    }
    for (const style of [...clone.querySelectorAll('style')]) {
      style.textContent = await inlineCss(style.textContent, pageUrl, 0);
    }

    // 4) Inline the favicon(s) so the archived tab still has its icon.
    for (const link of [...clone.querySelectorAll('link[rel~="icon" i][href],link[rel~="apple-touch-icon" i][href]')]) {
      const dataUri = await inlineUrl(link.getAttribute('href'), pageUrl);
      if (dataUri) link.setAttribute('href', dataUri);
    }

    // 5) Inline images. syncDynamicState stamped the *displayed* source on each
    //    <img> as data-fd-src (currentSrc resolves srcset/<picture>); inline it,
    //    then drop srcset/<source> so the browser can't re-pick a non-inlined
    //    candidate offline.
    for (const img of [...clone.querySelectorAll('img[data-fd-src]')]) {
      const dataUri = await inlineUrl(img.getAttribute('data-fd-src'), pageUrl);
      if (dataUri) img.setAttribute('src', dataUri);
      img.removeAttribute('srcset');
      img.removeAttribute('data-fd-src');
      img.removeAttribute('loading');
    }
    clone.querySelectorAll('picture source, img + source, source[srcset]').forEach((n) => {
      if (n.tagName === 'SOURCE') n.remove();
    });
    // <svg><image href> and bare <image> elements.
    for (const im of [...clone.querySelectorAll('image[href],image[*|href]')]) {
      const href = im.getAttribute('href') || im.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
      const dataUri = await inlineUrl(href, pageUrl);
      if (dataUri) im.setAttribute('href', dataUri);
    }

    // 6) Inline url(...) inside inline style="" attributes (background images).
    for (const el of [...clone.querySelectorAll('[style*="url(" i]')]) {
      const rewritten = await inlineCss(el.getAttribute('style'), pageUrl, CSS_IMPORT_DEPTH);
      el.setAttribute('style', rewritten);
    }

    // 7) Head hygiene: guarantee UTF-8, add a <base> so any URLs we left
    //    relative still resolve to the source site, and a provenance meta.
    const head = clone.querySelector('head') || clone.insertBefore(
      clone.ownerDocument.createElement('head'), clone.firstChild);
    if (!head.querySelector('meta[charset]')) {
      const m = clone.ownerDocument.createElement('meta');
      m.setAttribute('charset', 'utf-8');
      head.insertBefore(m, head.firstChild);
    }
    if (!head.querySelector('base[href]')) {
      const base = clone.ownerDocument.createElement('base');
      base.setAttribute('href', pageUrl);
      // After charset, before the rest, so relative refs resolve correctly.
      head.insertBefore(base, head.querySelector('meta[charset]')?.nextSibling || head.firstChild);
    }
    const prov = clone.ownerDocument.createElement('meta');
    prov.setAttribute('name', 'fd-archived-from');
    prov.setAttribute('content', pageUrl);
    head.appendChild(prov);

    // 8) Serialize + save.
    const html = '<!DOCTYPE html>\n' + clone.outerHTML;
    const filename = makeFilename(document.title, location.hostname);
    slog('serialized', filename, html.length, 'chars,', cache.size, 'resources');
    triggerDownload(html, filename);
  }

  // Copy live-only state onto the clone: chosen responsive image source, form
  // field values, canvas pixels. Recursive parallel walk (clone mirrors live
  // structure). Passwords/file inputs are intentionally NOT captured.
  function syncDynamicState(live, clone) {
    if (!live || !clone) return;
    const tag = live.nodeName;

    if (tag === 'IMG') {
      const cur = live.currentSrc || live.src || '';
      if (cur) clone.setAttribute('data-fd-src', cur);
    } else if (tag === 'INPUT') {
      const type = (live.type || '').toLowerCase();
      if (type === 'checkbox' || type === 'radio') {
        if (live.checked) clone.setAttribute('checked', '');
        else clone.removeAttribute('checked');
      } else if (type !== 'password' && type !== 'file') {
        clone.setAttribute('value', live.value ?? '');
      }
    } else if (tag === 'TEXTAREA') {
      clone.textContent = live.value ?? '';
    } else if (tag === 'SELECT') {
      const i = live.selectedIndex;
      const opts = clone.querySelectorAll('option');
      opts.forEach((o) => o.removeAttribute('selected'));
      if (i >= 0 && opts[i]) opts[i].setAttribute('selected', '');
    } else if (tag === 'CANVAS') {
      // Same-origin canvases serialize to a PNG; a tainted (cross-origin)
      // canvas throws on toDataURL — leave it as an empty canvas in that case.
      try {
        const d = live.toDataURL();
        const img = clone.ownerDocument.createElement('img');
        img.setAttribute('src', d);
        if (live.width) img.setAttribute('width', String(live.width));
        if (live.height) img.setAttribute('height', String(live.height));
        clone.replaceWith(img);
        return; // children are irrelevant once replaced
      } catch { /* tainted — skip */ }
    }

    const lc = live.children;
    const cc = clone.children;
    if (lc && cc) {
      const n = Math.min(lc.length, cc.length);
      for (let i = 0; i < n; i++) syncDynamicState(lc[i], cc[i]);
    }
  }

  // Derive a safe, readable .html filename from the page title (hostname
  // fallback). Strips path separators / reserved chars, collapses whitespace,
  // caps length. The download dialog still lets the user rename.
  function makeFilename(title, hostname) {
    let base = (title || '').trim();
    if (!base) base = (hostname || 'page').replace(/^www\./, '');
    base = base
      .replace(/[\\/:*?"<>| -]+/g, '_')
      .replace(/\s+/g, ' ')
      .replace(/^[.\s_]+|[.\s_]+$/g, '')
      .slice(0, 120)
      .trim();
    if (!base) base = 'snapshot';
    return base + '.html';
  }

  // Save via a blob <a download> click → GeckoView onExternalResponse → the
  // existing download pipeline (see header). The object URL is kept alive for
  // a minute so GeckoView can read the whole stream before it's revoked.
  function triggerDownload(html, filename) {
    const blob = new Blob([html], { type: 'text/html' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.rel = 'noopener';
    a.style.display = 'none';
    (document.body || document.documentElement).appendChild(a);
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
    setTimeout(() => {
      try { a.remove(); URL.revokeObjectURL(url); } catch { /* gone already */ }
    }, 60000);
  }
}
