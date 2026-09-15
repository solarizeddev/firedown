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
// FRAMES — the content is often NOT in the top document
// The script runs in EVERY frame (manifest all_frames). Only the top frame
// takes the popup trigger (the background relays it with frameId 0); each
// <iframe> in the document is then serialized by ITS OWN copy of this script
// and embedded as srcdoc. The top asks a child through
// iframe.contentWindow.postMessage — element-to-content mapping by
// construction, cross-origin included, no frameId bookkeeping — and accepts
// only a reply whose event.source is that very contentWindow and whose
// nonce matches. The reason this exists: sites that render the whole page
// inside a cross-origin iframe. Springer's ePDF (ReadCube's SharedIt reader)
// is a 340-byte shell whose entire content is an iframe from readcube.com;
// the top-frame-only archive was an EMPTY document, and the shell's
// <noscript><meta http-equiv=refresh> then fired in the JS-off viewer and
// tried the network (the "ERR_CACHE_MISS" archive). Nested frames recurse to
// MAX_FRAME_DEPTH; a frame that never answers (no content script — a
// sandboxed or about:blank frame — or a hung one) keeps its original src
// after FRAME_REPLY_TIMEOUT_MS, so the archive still saves.
//
// SHADOW DOM + CONSTRUCTED STYLESHEETS — where a component app keeps its DOM
// cloneNode/outerHTML see only LIGHT DOM: a web component's shadow tree is
// dropped wholesale, and CSS a framework installs through adoptedStyleSheets
// (Lit, and most component libraries) exists in no <style> element at all.
// A page built that way archived as its chrome with empty content. So the
// paired walk serializes every shadow root — open AND closed, through
// Firefox's extension-only openOrClosedShadowRoot — as a declarative
// <template shadowrootmode> (Chromium 111+, the WebView viewer), with the
// root's adopted sheets flattened into a <style> at its head, and the
// document's own adopted sheets into <head>. Every later inlining pass runs
// over the clone PLUS every template's content (qsa()), because
// querySelectorAll never descends into template contents.
//
// DEFERRED CONTENT — scroll it into existence BEFORE freezing
// A DOM freeze captures what has rendered, and a lazy page renders on demand:
// loading="lazy" images, IntersectionObserver loaders, a paginated reader
// that fills each page slot only once it scrolls into view (ReadCube's mobile
// ePDF: 33 page containers, an <img> in the two that had loaded, a spinner in
// the other 31 — the archive was honest and useless). So before the clone,
// loadDeferredContent() steps every scroll container (the document plus the
// largest overflow:auto/scroll elements) through its full extent, viewport
// by viewport, restores the original scroll position, and waits for the
// network to go quiet (no incomplete <img>, no new resource entries). This is
// SingleFile's "load deferred images" pass. The scan and the quiet wait walk
// SHADOW ROOTS (liveElements()) — document.querySelectorAll and
// document.images never enter one, and a component app keeps its scroller
// inside one: ReadCube's entire reader, pager included, sits under a single
// shadow root while its host document has no overflow at all (the viewer is
// position:fixed), so a light-DOM scan found nothing to scroll and the pass
// was a silent no-op on exactly the page it was written for (shipped once). Bounded by time (a top budget, a
// smaller per-child one — the parent's reply timeout must exceed the child's
// budget plus its serialization), by step count and by container count, so
// an infinite-scroll feed gets ONE pass to its current bottom, not forever.
// Every frame runs it for its own document, which is what reaches a reader
// living in a cross-origin iframe.
//
// SCOPE / KNOWN LIMITS (deliberate, do not "fix" by removing the caps)
//  - Virtualized content that UNMOUNTS what scrolls away is still captured
//    only as the window mounted around the restored scroll position; nothing
//    a DOM freeze can do about a list that recycles its rows.
//  - <noscript> blocks and <meta http-equiv=refresh> are STRIPPED: the archive
//    is script-free and opens with JS off, which ACTIVATES every noscript
//    fallback — typically a "redirect elsewhere" / "enable JS" placeholder
//    that replaces the captured content.
//  - Resource inlining is bounded (count/per-resource/total byte caps,
//    per-fetch timeout). On a cap hit the archive still saves with the
//    un-inlined URLs left absolute (works online, degrades gracefully).

{
  const IS_TOP = window.top === window.self;
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
  const FETCH_CONCURRENCY = 8;           // parallel sub-resource fetches
  // Frames: how deep the iframe recursion goes (top = 0), how long the parent
  // waits for one child's archive, and the child's own resource budget (the
  // top's budget is not shared across frames, so each child gets a quarter —
  // a page of N frames can't multiply the file N-fold past the caps).
  const MAX_FRAME_DEPTH = 3;
  // Must cover a child's lazy-load budget PLUS its serialization and its own
  // children; the Java "Saving snapshot…" snackbar gives up at 90 s, and the
  // top's own pass (LAZY_MAX_MS_TOP) runs before the children are asked.
  const FRAME_REPLY_TIMEOUT_MS = 60000;
  // Deferred-content pass (see DEFERRED CONTENT in the header).
  const LAZY_MAX_MS_TOP = 20000;      // whole pass, top frame
  const LAZY_MAX_MS_FRAME = 20000;    // whole pass, a child frame
  const LAZY_MAX_CONTAINERS = 6;      // scroll containers stepped, largest first
  const LAZY_MAX_STEPS = 300;         // viewport-sized steps per container
  const LAZY_STEP_SETTLE_MS = 300;    // after each step, for observers (and their debounces) to fire
  const LAZY_QUIET_MS = 500;          // no new resources / pending images for this long = quiet
  const LAZY_QUIET_MAX_MS = 4000;     // cap on the final quiet wait
  const LAZY_MAX_ELEMENTS = 200000;   // elements one shadow-aware walk visits before it stops
  const FRAME_DATAURI_CHARS = MAX_TOTAL_DATAURI_CHARS / 4;
  // postMessage envelope keys (page-visible on purpose — a page can read them
  // and gains nothing: the parent only accepts a reply from the exact
  // contentWindow it asked, carrying the nonce it minted).
  const FRAME_REQUEST = 'fd-snapshot-frame-request';
  const FRAME_REPLY = 'fd-snapshot-frame-reply';

  let capturing = false;

  // The popup trigger — relayed by requests.js to frameId 0 only, so this
  // fires in the top frame; the guard keeps a stray fan-out from starting a
  // second archive out of a child.
  browser.runtime.onMessage.addListener((msg) => {
    if (msg?.kind !== 'snapshot-capture') return;
    if (!IS_TOP) return;
    // Re-entrancy guard: a double tap must not start two concurrent
    // serializations of the same page.
    if (capturing) return;
    capturing = true;
    slog('capture requested', location.href);
    captureSnapshot()
      .catch((e) => slog('capture failed', e?.message))
      .finally(() => { capturing = false; });
  });

  // A parent frame asking THIS frame for its archive. Only the direct parent
  // is honoured (event.source === window.parent), and only one capture runs
  // at a time; the reply goes back to the parent with the same nonce.
  window.addEventListener('message', (event) => {
    const data = event.data;
    if (!data || data.fd !== FRAME_REQUEST) return;
    if (IS_TOP || event.source !== window.parent) return;
    const nonce = typeof data.nonce === 'string' ? data.nonce : '';
    const depth = Number.isInteger(data.depth) ? data.depth : MAX_FRAME_DEPTH;
    if (!nonce || capturing) return;
    capturing = true;
    slog('frame capture requested', location.href, 'depth', depth);
    serializeDocument({ depth, maxChars: FRAME_DATAURI_CHARS, lazyMs: LAZY_MAX_MS_FRAME })
      .then((html) => {
        window.parent.postMessage({ fd: FRAME_REPLY, nonce, html }, '*');
      })
      .catch((e) => {
        slog('frame capture failed', e?.message);
        window.parent.postMessage({ fd: FRAME_REPLY, nonce, html: null }, '*');
      })
      .finally(() => { capturing = false; });
  });

  // ---------------------------------------------------------------------------
  // Privileged background fetch (bypasses CORS — see header).
  // Returns a data: URI for binary, or text for as:'text'. null on any failure.
  // `referrer` is THIS frame's URL: the background re-fetches the resource
  // the way the page did — with the page's Referer — because a signed CDN
  // that also gates on Referer (ReadCube's rasterized page images on
  // mobile, the pixiv class) 403s a referer-less fetch, and a resource that
  // fails to inline stays an absolute URL the network-blocked viewer can't
  // load: the page renders, its content is blank.
  // ---------------------------------------------------------------------------
  async function fetchDataUri(url) {
    try {
      const r = await browser.runtime.sendMessage({ kind: 'snapshot-fetch', url, referrer: location.href });
      if (r && r.ok && r.dataUri) return r.dataUri;
    } catch (e) {
      slog('fetch fail', url, e?.message);
    }
    return null;
  }

  async function fetchText(url) {
    try {
      const r = await browser.runtime.sendMessage({ kind: 'snapshot-fetch', url, as: 'text', referrer: location.href });
      if (r && r.ok && typeof r.text === 'string') return r.text;
    } catch (e) {
      slog('fetch-text fail', url, e?.message);
    }
    return null;
  }

  // Run fn over items with at most `limit` in flight at once — a bounded worker
  // pool. This is what parallelizes resource inlining: instead of awaiting each
  // fetch serially (hundreds of round-trips back to back), up to FETCH_CONCURRENCY
  // run concurrently. The per-capture resource caps still apply because workers
  // advance incrementally (they observe the growing cache/byte budget), so the
  // bounding isn't bypassed the way a single Promise.all over everything would.
  async function mapLimit(items, limit, fn) {
    const arr = [...items];
    let cursor = 0;
    async function worker() {
      while (cursor < arr.length) {
        const idx = cursor++;
        await fn(arr[idx], idx);
      }
    }
    const workers = Math.max(1, Math.min(limit, arr.length));
    await Promise.all(Array.from({ length: workers }, worker));
  }

  // ---------------------------------------------------------------------------
  // Capture
  // ---------------------------------------------------------------------------
  async function captureSnapshot() {
    const html = await serializeDocument({ depth: 0, maxChars: MAX_TOTAL_DATAURI_CHARS, lazyMs: LAZY_MAX_MS_TOP });
    const filename = makeFilename(document.title, location.hostname);
    slog('serialized', filename, html.length, 'chars');
    triggerDownload(html, filename);
  }

  // Serialize THIS frame's document to one self-contained HTML string. Shared
  // by the top frame (which then downloads it) and by every child frame
  // (which posts it back to its parent to be embedded as srcdoc). `depth` is
  // this frame's nesting level; `maxChars` its inlined-resource budget.
  async function serializeDocument({ depth, maxChars, lazyMs }) {
    const pageUrl = location.href;

    // 0) Bring deferred content into the DOM before freezing it (see DEFERRED
    //    CONTENT in the header). Bounded; a page with nothing to scroll and
    //    nothing pending returns at once.
    await loadDeferredContent(lazyMs);

    // Per-capture resource cache + total-size accounting. cache maps an
    // absolute URL → its data: URI (or null when it failed / was over budget),
    // so a resource referenced many times is fetched once.
    const cache = new Map();
    const budget = { chars: 0 };

    const abs = (u, base) => {
      try { return new URL(u, base).href; } catch { return null; }
    };

    // Inline one resource URL to a data: URI, honouring caches + budget.
    // Returns a PROMISE (not async) and caches that promise keyed by URL, so two
    // parallel workers asking for the same URL share ONE fetch (in-flight dedup)
    // — important now that the inlining passes run concurrently via mapLimit.
    function inlineUrl(raw, base) {
      const url = abs(raw, base);
      if (!url || !/^https?:/i.test(url)) return Promise.resolve(null);
      // NEVER inline an adaptive-streaming manifest (HLS .m3u8 / DASH .mpd /
      // Smooth .ism). It only references external segments, so a manifest baked
      // into a data: URI makes the player resolve those segment URIs against
      // the data: URI — a non-hierarchical URI that crashes GeckoView's media3
      // HLS tracker (UnsupportedOperationException in
      // DefaultHlsPlaylistTracker.onLoadError → Uri.getQueryParameter) when the
      // saved snapshot is reopened. Leaving the original https manifest URL is
      // safe: it plays online and never produces a data: manifest.
      if (/\.(m3u8|m3u|mpd|ism|f4m)(?:[?#]|$)/i.test(url)) return Promise.resolve(null);
      const cached = cache.get(url);
      if (cached !== undefined) return cached;
      if (cache.size >= MAX_RESOURCES || budget.chars >= maxChars) {
        const capped = Promise.resolve(null);
        cache.set(url, capped);
        return capped;
      }
      const promise = fetchDataUri(url).then((dataUri) => {
        if (dataUri) budget.chars += dataUri.length;
        return dataUri;
      });
      cache.set(url, promise);
      return promise;
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

      // Inline every url() in parallel, then apply the substitutions. A big
      // stylesheet (icon fonts, sprite backgrounds) is the heaviest case, so
      // fetching its refs concurrently is the main speed win.
      const urls = [...cssText.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/gi)];
      const replacements = [];
      await mapLimit(urls, FETCH_CONCURRENCY, async (m) => {
        const raw = m[1];
        if (/^data:/i.test(raw) || raw.startsWith('#')) return; // data:/SVG-frag
        const dataUri = await inlineUrl(raw, baseUrl);
        if (dataUri) replacements.push([m[0], `url("${dataUri}")`]);
      });
      for (const [from, to] of replacements) cssText = cssText.split(from).join(to);
      return cssText;
    }

    // 1) Deep-clone the live <html>, then copy dynamic state the clone misses
    //    (cloneNode snapshots attributes, not live form values / canvas pixels /
    //    the responsive image actually chosen). Parallel-walk live↔clone — a
    //    deep clone preserves child order, so index alignment holds.
    const clone = document.documentElement.cloneNode(true);
    // The paired walk also collects the (live, clone) pairs of the elements
    // that need the LIVE side later — canvases (pixels) and iframes (their
    // contentWindow) — including those inside shadow roots, which no
    // querySelectorAll over the two trees could align.
    const pairs = { canvas: [], iframe: [] };
    syncDynamicState(document.documentElement, clone, pairs);

    // Selector over the clone AND every declarative shadow template's content
    // (recursively) — querySelectorAll never enters template contents, so a
    // pass written against `clone` alone would skip every shadow tree.
    const qsa = (selector) => {
      const out = [];
      for (const root of shadowRoots(clone)) out.push(...root.querySelectorAll(selector));
      return out;
    };

    // 1b) Canvases. A readable (same-origin) canvas serializes to a frozen PNG
    //     frame. A canvas tainted by cross-origin textures (e.g.
    //     midjourney.com/home's image-wall canvas) throws on toDataURL — page
    //     JS can't read a pixel, and a JS/WebGL-animated background can't be
    //     serialized — so it's left as-is (blank in the archive); we don't
    //     screenshot the app surface to fake it.
    inlineCanvases(pairs.canvas);

    // 1c) Frames: ask each child frame for its own archive and embed it as
    //     srcdoc (see FRAMES in the header). Runs on the LIVE iframes (their
    //     contentWindows).
    await inlineFrames(pairs.iframe, depth);

    // 2) Strip the machinery: scripts (we keep the rendered result, not the
    //    re-runnable app), offline-useless resource hints, and the JS-off
    //    fallbacks — <noscript> content and <meta http-equiv="refresh">. The
    //    archive opens with JavaScript OFF, which makes every <noscript> LIVE:
    //    on a JS-rendered page that is a "redirect to the real page" or an
    //    "enable JavaScript" placeholder that replaces what we captured
    //    (Springer's ePDF shell shipped a noscript meta-refresh that navigated
    //    the viewer to the network). The rendered DOM never showed them, so
    //    the archive must not either.
    qsa('script').forEach((n) => n.remove());
    qsa('noscript').forEach((n) => n.remove());
    qsa('meta[http-equiv="refresh" i]').forEach((n) => n.remove());
    qsa(
      'link[rel~="preload" i],link[rel~="prefetch" i],link[rel~="modulepreload" i],link[rel~="dns-prefetch" i],link[rel~="preconnect" i]'
    ).forEach((n) => n.remove());

    // 3) Inline external stylesheets → <style>; rewrite inline <style> bodies.
    //    First flatten the document's constructed stylesheets
    //    (document.adoptedStyleSheets — no <style> element holds them) into
    //    <head>, so the url() pass below inlines their refs too. Shadow
    //    roots' adopted sheets were handled in the paired walk.
    appendAdoptedStyles(document, clone.querySelector('head') || clone);
    for (const link of [...qsa('link[rel~="stylesheet" i][href]')]) {
      const href = abs(link.getAttribute('href'), pageUrl);
      const css = href ? await fetchText(href) : null;
      const style = clone.ownerDocument.createElement('style');
      style.textContent = css != null ? await inlineCss(css, href, 0) : '';
      const media = link.getAttribute('media');
      if (media) style.setAttribute('media', media);
      link.replaceWith(style);
    }
    for (const style of [...qsa('style')]) {
      style.textContent = await inlineCss(style.textContent, pageUrl, 0);
    }

    // 4) Inline the favicon(s) so the archived tab still has its icon.
    await mapLimit(
      [...qsa('link[rel~="icon" i][href],link[rel~="apple-touch-icon" i][href]')],
      FETCH_CONCURRENCY, async (link) => {
        const dataUri = await inlineUrl(link.getAttribute('href'), pageUrl);
        if (dataUri) link.setAttribute('href', dataUri);
      });

    // 5) Inline images. syncDynamicState stamped the *displayed* source on each
    //    <img> as data-fd-src (currentSrc resolves srcset/<picture>); inline it,
    //    then drop srcset/<source> so the browser can't re-pick a non-inlined
    //    candidate offline.
    await mapLimit([...qsa('img[data-fd-src]')], FETCH_CONCURRENCY, async (img) => {
      const dataUri = await inlineUrl(img.getAttribute('data-fd-src'), pageUrl);
      if (dataUri) img.setAttribute('src', dataUri);
      img.removeAttribute('srcset');
      img.removeAttribute('data-fd-src');
      img.removeAttribute('loading');
    });
    qsa('picture source, img + source, source[srcset]').forEach((n) => {
      if (n.tagName === 'SOURCE') n.remove();
    });

    // 5b) Inline <video>/<audio> sources + poster. syncDynamicState stamped the
    //     chosen source on the element (data-fd-media) and the poster
    //     (data-fd-poster); inline both, then drop child <source>s so the
    //     element uses our embedded src. A source over the per-resource byte
    //     cap stays a URL (works online, not offline) — background videos can
    //     be large; we don't blow the file up to embed a huge one.
    await mapLimit([...qsa('video[data-fd-media],audio[data-fd-media]')],
      FETCH_CONCURRENCY, async (media) => {
        // inlineUrl returns null for a manifest (.m3u8/.mpd — see the crash note
        // there), a blob:/MSE source, or an over-budget file; in those cases we
        // leave the element's original src/sources untouched (safe, no data:
        // manifest). Only a self-contained progressive file gets embedded.
        const dataUri = await inlineUrl(media.getAttribute('data-fd-media'), pageUrl);
        if (dataUri) {
          media.setAttribute('src', dataUri);
          media.querySelectorAll('source').forEach((s) => s.remove());
          media.removeAttribute('preload');
        }
        media.removeAttribute('data-fd-media');
      });
    await mapLimit([...qsa('video[data-fd-poster]')], FETCH_CONCURRENCY, async (video) => {
      const dataUri = await inlineUrl(video.getAttribute('data-fd-poster'), pageUrl);
      if (dataUri) video.setAttribute('poster', dataUri);
      video.removeAttribute('data-fd-poster');
    });
    // <svg><image href> and bare <image> elements.
    await mapLimit([...qsa('image[href],image[*|href]')], FETCH_CONCURRENCY, async (im) => {
      const href = im.getAttribute('href') || im.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
      const dataUri = await inlineUrl(href, pageUrl);
      if (dataUri) im.setAttribute('href', dataUri);
    });

    // 6) Inline url(...) inside inline style="" attributes (background images).
    for (const el of [...qsa('[style*="url(" i]')]) {
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

    // 8) Serialize.
    slog('serialized frame', pageUrl, 'depth', depth, cache.size, 'resources');
    return '<!DOCTYPE html>\n' + clone.outerHTML;
  }

  // Replace each <iframe>'s src in the clone with the srcdoc archive its own
  // content script produced (pairs from the paired walk). A frame past
  // MAX_FRAME_DEPTH, one with no window (display:none never attaches one
  // either way — contentWindow is null only for a detached element), or one
  // that doesn't answer in time keeps its original src: the archive degrades
  // to the old placeholder for that one frame instead of failing.
  async function inlineFrames(pairs, depth) {
    if (depth >= MAX_FRAME_DEPTH || pairs.length === 0) return;
    await mapLimit(pairs, 4, async ([lc, cc]) => {
      const win = lc.contentWindow;
      if (!win) return;
      const html = await requestFrameArchive(win, depth + 1);
      if (!html) return;
      cc.removeAttribute('src');
      cc.removeAttribute('srcdoc');
      cc.removeAttribute('loading');
      cc.setAttribute('srcdoc', html);
    });
  }

  // One request/reply round trip with a child frame's content script.
  // Resolves to the child's archive HTML, or null on timeout / a frame that
  // reported failure. The listener is scoped to THIS nonce and THIS window,
  // so a page posting look-alike messages can at worst replace the archive
  // of its own frame — content it already controls.
  function requestFrameArchive(win, depth) {
    return new Promise((resolve) => {
      const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
      let done = false;
      const finish = (html) => {
        if (done) return;
        done = true;
        window.removeEventListener('message', onMessage);
        clearTimeout(timer);
        resolve(typeof html === 'string' && html ? html : null);
      };
      const onMessage = (event) => {
        const data = event.data;
        if (!data || data.fd !== FRAME_REPLY || data.nonce !== nonce) return;
        if (event.source !== win) return;
        finish(data.html);
      };
      const timer = setTimeout(() => finish(null), FRAME_REPLY_TIMEOUT_MS);
      window.addEventListener('message', onMessage);
      try {
        win.postMessage({ fd: FRAME_REQUEST, nonce, depth }, '*');
      } catch (e) {
        slog('frame request failed', e?.message);
        finish(null);
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Deferred content (see DEFERRED CONTENT in the header)
  // ---------------------------------------------------------------------------
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  async function loadDeferredContent(maxMs) {
    const deadline = Date.now() + maxMs;
    let containers = [];
    try { containers = findScrollContainers(); } catch (e) { slog('scroll scan failed', e?.message); }
    for (const el of containers) {
      if (Date.now() >= deadline) break;
      try { await scrollThrough(el, deadline); } catch (e) { slog('scroll pass failed', e?.message); }
    }
    await waitForQuiet(Math.min(LAZY_QUIET_MAX_MS, Math.max(0, deadline - Date.now())));
  }

  // Every element of the live document, shadow trees included — open ones
  // and, through the extension-only openOrClosedShadowRoot, closed ones.
  // Depth-first in document order, bounded by LAZY_MAX_ELEMENTS so one walk
  // over a huge document stays cheap. Used wherever the pass needs to SEE
  // the page: querySelectorAll('*') / document.images stop at every shadow
  // boundary, and the scroller or the images being waited for may well be
  // behind one (ReadCube's reader is).
  function liveElements() {
    const out = [];
    const stack = [document.documentElement];
    while (stack.length > 0 && out.length < LAZY_MAX_ELEMENTS) {
      const el = stack.pop();
      if (!el) continue;
      out.push(el);
      const sr = shadowRootOf(el);
      if (sr) {
        const sk = sr.children;
        for (let i = sk.length - 1; i >= 0; i--) stack.push(sk[i]);
      }
      const kids = el.children;
      for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]);
    }
    return out;
  }

  // The document scroller plus the largest scrollable elements, on EITHER
  // axis: a {el, axis} per overflowing axis whose overflow-x/-y is
  // auto/scroll, largest overflow first, capped. Both axes matter — a
  // horizontal pager (ReadCube mobile: overflow:scroll +
  // scroll-snap-type:x, pages side by side in an inline-flex sheet) has no
  // vertical overflow at all and a y-only pass would never touch it. The
  // size test comes first because getComputedStyle is the expensive part.
  // Candidates come from liveElements(), never document.querySelectorAll —
  // the pager can sit inside a shadow root (see the header).
  function findScrollContainers() {
    const out = [];
    const docEl = document.scrollingElement || document.documentElement;
    if (docEl) {
      if (docEl.scrollHeight - window.innerHeight > 50) out.push({ el: docEl, axis: 'y', overflow: docEl.scrollHeight });
      if (docEl.scrollWidth - window.innerWidth > 50) out.push({ el: docEl, axis: 'x', overflow: docEl.scrollWidth });
    }
    const candidates = [];
    const all = liveElements();
    for (let i = 0; i < all.length; i++) {
      const el = all[i];
      if (el === docEl || el === document.body) continue;
      const oy = el.scrollHeight - el.clientHeight;
      const ox = el.scrollWidth - el.clientWidth;
      if ((oy <= 200 && ox <= 200) || el.clientHeight === 0 || el.clientWidth === 0) continue;
      const cs = getComputedStyle(el);
      if (oy > 200 && (cs.overflowY === 'auto' || cs.overflowY === 'scroll')) {
        candidates.push({ el, axis: 'y', overflow: el.scrollHeight });
      }
      if (ox > 200 && (cs.overflowX === 'auto' || cs.overflowX === 'scroll')) {
        candidates.push({ el, axis: 'x', overflow: el.scrollWidth });
      }
    }
    candidates.sort((a, b) => b.overflow - a.overflow);
    for (const c of candidates) {
      if (out.length >= LAZY_MAX_CONTAINERS) break;
      out.push(c);
    }
    return out;
  }

  // Step one scroller along one axis from its start to its CURRENT end in
  // viewport-sized increments, pausing after each so lazy loaders
  // (IntersectionObserver, scroll listeners, loading="lazy") fire, then put
  // the scroll back where the user had it. scrollTo(..., 'instant')
  // overrides a CSS scroll-behavior:smooth, whose animation would otherwise
  // lag every step and the restore. Extent that grows mid-pass is followed
  // until the caps; growth after the end is reached is deliberately not
  // chased. A position that stops moving (a scroller that refuses, a
  // snap that pins) ends the pass early rather than burning the budget.
  async function scrollThrough({ el, axis }, deadline) {
    const isDoc = el === document.scrollingElement || el === document.documentElement;
    const horizontal = axis === 'x';
    const viewport = isDoc
      ? (horizontal ? window.innerWidth : window.innerHeight)
      : (horizontal ? el.clientWidth : el.clientHeight);
    if (!(viewport > 0)) return;
    const extent = () => (horizontal ? el.scrollWidth : el.scrollHeight);
    const getPos = () => {
      if (isDoc) return horizontal ? window.scrollX : window.scrollY;
      return horizontal ? el.scrollLeft : el.scrollTop;
    };
    const setPos = (v) => {
      const target = isDoc ? window : el;
      const opts = horizontal ? { left: v, behavior: 'instant' } : { top: v, behavior: 'instant' };
      try { target.scrollTo(opts); } catch { if (isDoc) window.scrollTo(horizontal ? v : 0, horizontal ? 0 : v); else if (horizontal) el.scrollLeft = v; else el.scrollTop = v; }
    };
    const origin = getPos();
    const step = Math.max(1, Math.floor(viewport * 0.9));
    let pos = 0;
    let steps = 0;
    let lastActual = -1;
    let stalled = 0;
    try {
      while (steps < LAZY_MAX_STEPS && Date.now() < deadline) {
        setPos(pos);
        await sleep(LAZY_STEP_SETTLE_MS);
        const actual = getPos();
        if (actual === lastActual) {
          stalled++;
          if (stalled >= 3) break;
        } else {
          stalled = 0;
        }
        lastActual = actual;
        const max = Math.max(0, extent() - viewport);
        if (pos >= max) break;
        pos = Math.min(max, pos + step);
        steps++;
      }
    } finally {
      setPos(origin);
    }
    slog('scrolled', isDoc ? 'document' : el.tagName, axis, 'steps', steps);
  }

  // Resolve once nothing is pending: every <img> with a source is complete and
  // the resource-timing entry count has not moved for LAZY_QUIET_MS — or at
  // maxMs, whichever first. (The resource buffer caps at ~250 entries and
  // then stops counting; the image check still holds after that.) Images are
  // found through the shadow-aware walk, not document.images, for the same
  // reason as the scroller scan.
  async function waitForQuiet(maxMs) {
    const deadline = Date.now() + maxMs;
    let lastCount = -1;
    let quietSince = Date.now();
    while (Date.now() < deadline) {
      let pending = 0;
      const all = liveElements();
      for (let i = 0; i < all.length; i++) {
        const img = all[i];
        if (img.nodeName !== 'IMG') continue;
        if (!img.complete && (img.currentSrc || img.getAttribute('src'))) pending++;
      }
      let count = lastCount;
      try { count = performance.getEntriesByType('resource').length; } catch { /* keep */ }
      if (pending === 0 && count === lastCount) {
        if (Date.now() - quietSince >= LAZY_QUIET_MS) return;
      } else {
        quietSince = Date.now();
        lastCount = count;
      }
      await sleep(100);
    }
  }

  // Copy live-only state onto the clone: chosen responsive image source, form
  // field values, canvas pixels. Recursive parallel walk (clone mirrors live
  // structure). Passwords/file inputs are intentionally NOT captured.
  function syncDynamicState(live, clone, pairs) {
    if (!live || !clone) return;
    const tag = live.nodeName;

    if (tag === 'CANVAS') {
      pairs.canvas.push([live, clone]);
    } else if (tag === 'IFRAME') {
      pairs.iframe.push([live, clone]);
    }

    if (tag === 'IMG') {
      // currentSrc is the source the browser actually chose (srcset/<picture>).
      // For a lazy <img> not yet fetched it's empty — fall back to the common
      // lazy-load attributes so the placeholder isn't all we capture.
      let cur = live.currentSrc || live.getAttribute('src') || '';
      if (!cur || /^data:image\/(gif|svg)/i.test(cur)) {
        cur = live.getAttribute('data-src') || live.getAttribute('data-lazy-src')
          || live.getAttribute('data-original') || firstSrcsetUrl(live.getAttribute('data-srcset'))
          || firstSrcsetUrl(live.getAttribute('srcset')) || cur;
      }
      if (cur) clone.setAttribute('data-fd-src', cur);
    } else if (tag === 'VIDEO' || tag === 'AUDIO') {
      // Inline the playing source so a background/inline video survives offline.
      // currentSrc resolves a <source> list to the chosen URL. autoplay/loop/
      // muted/playsinline are copied by cloneNode, so an inlined autoplaying
      // background video keeps animating in the saved file (the closest a
      // script-free archive can get to a "live" background — a JS/WebGL canvas
      // animation can't be preserved as motion, only a frozen frame).
      const cur = live.currentSrc || live.getAttribute('src') || '';
      if (cur) clone.setAttribute('data-fd-media', cur);
      if (tag === 'VIDEO' && live.poster) clone.setAttribute('data-fd-poster', live.poster);
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
    }
    // <canvas> and <iframe> are handled in dedicated passes over the pairs
    // collected above (inlineCanvases / inlineFrames).

    const lc = live.children;
    const cc = clone.children;
    if (lc && cc) {
      const n = Math.min(lc.length, cc.length);
      for (let i = 0; i < n; i++) syncDynamicState(lc[i], cc[i], pairs);
    }

    // Shadow root LAST, so the template we prepend doesn't shift the child
    // indices the loop above just aligned. openOrClosedShadowRoot is the
    // extension-only accessor Firefox gives content scripts — it reaches a
    // CLOSED root too, which page JS (and cloneNode) never can.
    const sr = shadowRootOf(live);
    if (sr) {
      const tpl = clone.ownerDocument.createElement('template');
      const mode = sr.mode === 'closed' ? 'closed' : 'open';
      tpl.setAttribute('shadowrootmode', mode);
      tpl.setAttribute('shadowroot', mode); // pre-111 Chromium spelling
      appendAdoptedStyles(sr, tpl.content);
      const kids = sr.childNodes;
      for (let i = 0; i < kids.length; i++) {
        const c = kids[i].cloneNode(true);
        tpl.content.appendChild(c);
        if (kids[i].nodeType === 1) syncDynamicState(kids[i], c, pairs);
      }
      clone.insertBefore(tpl, clone.firstChild);
    }
  }

  function shadowRootOf(el) {
    if (!el || el.nodeType !== 1) return null;
    try {
      return el.openOrClosedShadowRoot || el.shadowRoot || null;
    } catch {
      return null;
    }
  }

  // Constructed stylesheets (document.adoptedStyleSheets / a shadow root's)
  // have no DOM representation; flatten each into a <style> appended to
  // `target` (a head, or a template's content). Rules that can't be read
  // (a cross-origin sheet throws on cssRules) are skipped, never fatal.
  function appendAdoptedStyles(owner, target) {
    let sheets = null;
    try { sheets = owner.adoptedStyleSheets; } catch { sheets = null; }
    if (!sheets || !sheets.length) return;
    for (let i = 0; i < sheets.length; i++) {
      let text = '';
      try {
        const rules = sheets[i].cssRules;
        const parts = [];
        for (let j = 0; j < rules.length; j++) parts.push(rules[j].cssText);
        text = parts.join('\n');
      } catch {
        text = '';
      }
      if (!text) continue;
      const style = target.ownerDocument.createElement('style');
      style.setAttribute('data-fd-adopted', '');
      style.textContent = text;
      target.appendChild(style);
    }
  }

  // The clone plus every declarative-shadow template's content beneath it,
  // recursively — the roots a serialization pass must cover.
  function shadowRoots(root) {
    const out = [root];
    const templates = root.querySelectorAll('template[shadowrootmode]');
    for (let i = 0; i < templates.length; i++) out.push(...shadowRoots(templates[i].content));
    return out;
  }

  // Replace each readable (same-origin) <canvas> in the clone with a static
  // <img> of its current frame. A canvas tainted by cross-origin textures
  // throws on toDataURL — page JS can't read a pixel of it. We deliberately do
  // NOT try to capture such a canvas: a JS/WebGL-animated background (e.g.
  // midjourney.com/home's image wall) can't be serialized into a static file,
  // and we don't screenshot the app surface to fake it. It's left as-is (blank
  // in the archive). Everything else in the page is still captured normally.
  function inlineCanvases(pairs) {
    for (const [lc, cc] of pairs) {
      let frame = null;
      try { frame = lc.toDataURL('image/png'); } catch { frame = null; }
      if (!frame || frame.length <= 64) continue; // tainted/blank → leave as-is
      const img = cc.ownerDocument.createElement('img');
      img.setAttribute('src', frame);
      const style = cc.getAttribute('style');
      if (style) img.setAttribute('style', style);
      const cls = cc.getAttribute('class');
      if (cls) img.setAttribute('class', cls);
      if (lc.width) img.setAttribute('width', String(lc.width));
      if (lc.height) img.setAttribute('height', String(lc.height));
      cc.replaceWith(img);
    }
  }

  // First candidate URL out of a srcset string ("a.jpg 1x, b.jpg 2x" → "a.jpg").
  // Used as a lazy-image fallback when currentSrc hasn't resolved yet.
  function firstSrcsetUrl(srcset) {
    if (!srcset) return '';
    const first = srcset.split(',')[0]?.trim();
    return first ? first.split(/\s+/)[0] : '';
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
