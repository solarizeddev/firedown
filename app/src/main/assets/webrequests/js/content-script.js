// content-script.js — runs in every page
// Scans the DOM for image URLs (including those served from service-worker
// cache, which webRequest cannot see) and forwards them to the background.
// Also exposes a "get-page-metadata" message endpoint that the background
// uses to enrich intercepted media downloads with a descriptive filename
// (page title + meta description). Lives in the page context so it sees
// JS-rendered titles that a server-side fetch wouldn't.

// Debug flag, resolved from BuildConfig.DEBUG via the native bridge — every
// log goes through clog() so release builds stay silent (CLAUDE.md "Logging
// discipline"). A handful of boot-time logs land before the async reply, which
// is fine: those simply don't print.
let DEBUG = false;
browser.runtime.sendNativeMessage('browser', { kind: 'get-debug-flag' })
    .then(r => { DEBUG = (r === true); }, () => {});
const clog = (...args) => { if (DEBUG) console.log(...args); };

clog('[cs] loaded', location.href);

// The WebAssembly unavailability detector (settings/privacy support, not
// capture) lives in its own content script: js/wasm-watch.js (page-world half:
// js/wasm-probe.js). It was split out of this file so capture code and
// settings-feature code stay separate.


// Per-frame metadata responder. Runs in EVERY frame (a bare block, NOT gated to
// window.top) because an embedded media player lives in its own iframe with its
// own document, mediaSession and <audio> binding. The background targets the
// query to the frame that OWNS the captured media (browser.tabs.sendMessage
// frameId = the request's frameId), so each frame answers for its OWN audio —
// giving every clip its own title/thumbnail instead of one shared page card.
// Targeting by frameId is what makes this safe (no cross-frame contamination);
// the old top-frame-only gate existed only because the query used to broadcast.
{
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

  // thumbnailUrl / thumbnail can be a URL string, an array of URL strings, an
  // ImageObject ({url}), or an array of those — take the first usable URL.
  // (ldString can't read ImageObject.url; that's why this is separate.)
  const ldImage = (v) => {
    if (typeof v === 'string') return v.trim();
    if (Array.isArray(v)) {
      for (const item of v) {
        const s = ldImage(item);
        if (s) return s;
      }
      return '';
    }
    if (v && typeof v === 'object') {
      if (typeof v.url === 'string') return v.url.trim();
      if (typeof v['@value'] === 'string') return v['@value'].trim();
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
          // thumbnailUrl is the schema.org canonical; some pages use thumbnail.
          const thumbnail = ldImage(node.thumbnailUrl) || ldImage(node.thumbnail);
          if (name) return { name, description, thumbnail };
          if (description && !descriptionOnly) {
            descriptionOnly = { name: '', description, thumbnail };
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

  // Most JS players DON'T render their poster as a <video poster> attribute —
  // they paint it as a CSS background-image on an overlay div (JWPlayer
  // .jw-preview, Video.js .vjs-poster, Plyr .plyr__poster, …). When the generic
  // catcher is the winning capturer for such a player (its API isn't readable,
  // or it wins the dedup race against the page-state bridge), <video poster> is
  // empty and the clip lands with no thumbnail even though one is plainly on the
  // page. Read the computed background-image of the known player poster
  // containers (most-specific first) and parse its url(...). Conservative: only
  // a handful of recognised player classes, and a match still REQUIRES a real
  // http(s) url() — a gradient/none/data: background is ignored — so this can't
  // grab a decorative element's background. Ranks BELOW <video poster> (a real
  // poster attribute, when present, is authoritative). og:image / JSON-LD remain
  // the page-level fallbacks below.
  function posterFromPlayerBg() {
    const sels = ['.jw-preview', '.vjs-poster', '.plyr__poster', '.video-js .vjs-poster'];
    for (let i = 0; i < sels.length; i++) {
      let els;
      try { els = document.querySelectorAll(sels[i]); } catch (_) { continue; }
      for (let j = 0; j < els.length; j++) {
        let bg;
        try { bg = getComputedStyle(els[j]).backgroundImage; } catch (_) { bg = ''; }
        if (!bg || bg === 'none') continue;
        // backgroundImage can be a comma list / carry quotes; take the first
        // http(s) url(...).
        const m = /url\(\s*["']?(https?:\/\/[^"')]+)["']?\s*\)/i.exec(bg);
        if (m && m[1]) return m[1];
      }
    }
    return '';
  }

  // --- Audio role discrimination -----------------------------------------
  // The generic catcher suppresses the page-title override for standalone
  // AUDIO files because most are incidental (a notification ding, UI sfx,
  // background music) and would otherwise inherit the page headline
  // (series.ly /audio/notification.mp3 → "The Breadwinner"). But a real
  // main-content audio clip (an interview/podcast embedded in a news article)
  // SHOULD inherit the page metadata. We can only tell the two apart by the
  // audio's ROLE on the page, not its URL — so when the background asks about
  // a specific media URL we compute that role here, in the page context.
  //   'content'    — the page presents this URL as main content: declared as an
  //                  AudioObject/og:audio, OR bound to an <audio>/<video> DOM
  //                  element (even a hidden, controls-less one — the standard
  //                  custom-UI podcast player, e.g. podverse) → enrich it.
  //   'unknown'    — no element and no declaration (e.g. a `new Audio()` sound,
  //                  which has no DOM node — every incidental UI ding/notification
  //                  is played this way) → conservative default (suppress).
  // We deliberately do NOT require the bound element to be "user-facing"
  // (controls / on-screen size): real podcast & article players hide a native
  // <audio> behind their own React/JS controls, so requiring controls misses
  // them. The genuinely-incidental case has no DOM element at all, so the mere
  // presence of a bound element is itself the content signal.
  // Top-frame only, like the rest of this responder; an audio element inside a
  // cross-origin iframe is not inspected (rare for article audio).
  const absUrl = (u) => {
    try { return new URL(u, location.href).href; } catch (_) { return ''; }
  };
  // Compare two URLs ignoring the #fragment (signed URLs differ only there).
  const sameUrl = (a, b) => {
    const x = absUrl(a);
    const y = absUrl(b);
    if (!x || !y) return false;
    return x.split('#')[0] === y.split('#')[0];
  };

  // Generic depth/breadth-bounded JSON-LD tree walk: calls visit(node) on every
  // object node and returns the first truthy result (shares the caps the video
  // walk above uses; budget is per-call). Used by the audio reader below.
  const eachJsonLdNode = (visit) => {
    const scripts = document.querySelectorAll('script[type="application/ld+json"]');
    let budget = MAX_LD_NODES;
    const walk = (root) => {
      const stack = [{ node: root, depth: 0 }];
      const seen = new Set();
      while (stack.length) {
        if (budget-- <= 0) break;
        const { node, depth } = stack.pop();
        if (!node || typeof node !== 'object' || depth > MAX_LD_DEPTH) continue;
        if (seen.has(node)) continue;
        seen.add(node);
        const hit = visit(node);
        if (hit) return hit;
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
      const hit = walk(data);
      if (hit) return hit;
    }
    return null;
  };

  // A schema.org VideoObject whose contentUrl (or url) IS the captured media —
  // the page declaring "this URL is the video content". Mirrors readAudioJsonLd
  // (below) for video, and is the per-URL counterpart to the page-level
  // readVideoJsonLd above: that one returns the FIRST VideoObject for EVERY
  // capture, so a page with several clips (a gallery, a showcase of demo
  // videos) collapses to one shared title/thumbnail. Matching contentUrl to the
  // captured URL instead gives each clip its OWN name + thumbnail. Returns
  // {name, description, thumbnail} or null if no VideoObject points at this URL.
  // Top-frame only like the rest of this responder — but that still covers a
  // clip captured from a same-origin iframe, because the background passes the
  // captured media URL and the top frame's JSON-LD can declare it.
  const readVideoJsonLdByUrl = (mediaUrl) => {
    if (!mediaUrl) return null;
    return eachJsonLdNode((node) => {
      if (!isVideoType(node['@type'])) return null;
      const contentUrl = ldString(node.contentUrl) || ldString(node.url);
      if (!contentUrl || !sameUrl(contentUrl, mediaUrl)) return null;
      return {
        name: ldString(node.name),
        description: ldString(node.description),
        thumbnail: ldImage(node.thumbnailUrl) || ldImage(node.thumbnail),
      };
    });
  };

  // Per-URL clip metadata a page declares in a Firedown-specific JSON block
  // (`<script type="application/firedown+json">` — a list of
  // {contentUrl, name, thumbnail, description?}). This is the counterpart to the
  // ld+json VideoObject reader above, for pages that show SEVERAL clips and must
  // NOT use schema.org for it: a real ld+json VideoObject list is read
  // page-level by older builds (readVideoJsonLd returns ONE for every capture)
  // and is crawler-visible (a gallery's demo clips become indexable site video).
  // The custom MIME is inert to both — invisible to search engines and to the
  // page-level reader — while we read it here by contentUrl, so each clip on a
  // multi-clip page (firedown.app's own /demo showcase) gets its own title +
  // thumbnail. Top-frame only like the rest of this responder, which still
  // covers a clip captured from a same-origin iframe (the background passes the
  // captured URL and the top frame declares it). Returns {name, description,
  // thumbnail} or null.
  const readDeclaredMediaByUrl = (mediaUrl) => {
    if (!mediaUrl) return null;
    let list;
    try {
      const el = document.querySelector('script[type="application/firedown+json"]');
      if (!el) return null;
      list = JSON.parse(el.textContent);
    } catch (_) {
      return null;
    }
    if (!Array.isArray(list)) return null;
    for (let i = 0; i < list.length; i++) {
      const item = list[i];
      if (!item || typeof item !== 'object') continue;
      const contentUrl = ldString(item.contentUrl) || ldString(item.url);
      if (!contentUrl || !sameUrl(contentUrl, mediaUrl)) continue;
      return {
        name: ldString(item.name),
        description: ldString(item.description),
        thumbnail: ldImage(item.thumbnail) || ldImage(item.thumbnailUrl),
      };
    }
    return null;
  };

  const isAudioType = (t) =>
    t === 'AudioObject' || (Array.isArray(t) && t.includes('AudioObject'));

  // A schema.org AudioObject whose contentUrl (or url) IS the captured media —
  // the page declaring "this URL is the audio content". Returns its
  // {name, description, thumbnail} so the consumer can prefer it over the
  // generic page title; null if no AudioObject points at this URL.
  const readAudioJsonLd = (mediaUrl) => eachJsonLdNode((node) => {
    if (!isAudioType(node['@type'])) return null;
    const contentUrl = ldString(node.contentUrl) || ldString(node.url);
    if (!contentUrl || !sameUrl(contentUrl, mediaUrl)) return null;
    return {
      name: ldString(node.name),
      description: ldString(node.description),
      thumbnail: ldImage(node.thumbnailUrl) || ldImage(node.thumbnail),
    };
  });

  // og:audio / og:audio:url / og:audio:secure_url pointing at the captured URL.
  const ogAudioMatches = (mediaUrl) => {
    const props = ['og:audio', 'og:audio:url', 'og:audio:secure_url'];
    for (const p of props) {
      const el = document.querySelector(`meta[property="${p}"]`)
        || document.querySelector(`meta[name="${p}"]`);
      const c = el && el.getAttribute('content');
      if (c && sameUrl(c, mediaUrl)) return true;
    }
    return false;
  };

  // The <audio>/<video> element whose src/currentSrc/<source> resolves to the
  // captured URL, or null (the URL was played without a DOM element).
  const findBoundMedia = (mediaUrl) => {
    const els = document.querySelectorAll('audio, video');
    for (let i = 0; i < els.length; i++) {
      const el = els[i];
      if (sameUrl(el.currentSrc, mediaUrl) || sameUrl(el.src, mediaUrl)) return el;
      const sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
      for (let j = 0; j < sources.length; j++) {
        if (sameUrl(sources[j].src, mediaUrl)) return el;
      }
    }
    return null;
  };

  // Decide the role of a captured audio URL on this page (see the block above).
  const resolveAudioContent = (mediaUrl) => {
    if (!mediaUrl) return { role: 'unknown' };
    // Declared content wins and carries its own metadata.
    const audioLd = readAudioJsonLd(mediaUrl);
    if (audioLd) {
      return {
        role: 'content',
        name: audioLd.name,
        description: audioLd.description,
        thumbnail: audioLd.thumbnail,
      };
    }
    if (ogAudioMatches(mediaUrl)) return { role: 'content' };
    // A bound <audio>/<video> DOM element ⇒ the page embedded a player for this
    // URL ⇒ main content (hidden/controls-less custom players included). The
    // incidental case (a `new Audio()` ding) binds no element, so it falls
    // through to 'unknown' and stays suppressed.
    if (findBoundMedia(mediaUrl)) return { role: 'content' };
    return { role: 'unknown' };
  };

  // The MediaSession the page published for whatever is playing RIGHT NOW —
  // the per-track title + artwork a player exposes to the OS / lock screen
  // (navigator.mediaSession.metadata). This is the precise per-item source: an
  // embedded player updates it as the user moves between clips, so a page with
  // several audios gives each its own title/thumbnail. Read defensively (the
  // whole API and each field may be absent) and pick the largest artwork.
  const readMediaSession = () => {
    let m = null;
    try { m = navigator.mediaSession && navigator.mediaSession.metadata; } catch (_) { m = null; }
    if (!m) return { title: '', artist: '', artwork: '' };
    let title = '';
    let artist = '';
    try { title = (m.title || '').trim(); } catch (_) {}
    try { artist = (m.artist || '').trim(); } catch (_) {}
    let artwork = '';
    try {
      const list = m.artwork || [];
      let bestArea = -1;
      for (let i = 0; i < list.length; i++) {
        const a = list[i];
        if (!a || !a.src) continue;
        // sizes is "WxH" (largest wins; "any"/missing → 0, so a sized entry
        // beats it but an only-"any" icon is still taken as a last resort).
        let area = 0;
        const wh = /^(\d+)x(\d+)$/i.exec((a.sizes || '').split(' ')[0]);
        if (wh) area = (+wh[1]) * (+wh[2]);
        if (area >= bestArea) { bestArea = area; artwork = a.src; }
      }
    } catch (_) {}
    return { title, artist, artwork: absUrl(artwork) || artwork };
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
    // Poster for the page's video, so the native side can use it as the
    // capture's thumbnail instead of decoding a frame from the media with
    // FFmpeg. el.poster reflects the attribute and resolves it to an absolute
    // URL. We take the first <video poster>; on a single-video page (the common
    // case, same scope as the page-level title/description below) that is the
    // captured clip's poster. og:image / JSON-LD thumbnailUrl are the fallbacks.
    const videoEl = document.querySelector('video[poster]');
    const poster = (videoEl && videoEl.poster ? videoEl.poster : '') || posterFromPlayerBg();
    // When the background passes the captured media URL, classify whether a
    // standalone audio file is the page's main content (enrich) or incidental
    // (keep the filename) — see resolveAudioContent. Non-audio captures ignore
    // these fields.
    const ms = readMediaSession();
    const audio = msg.mediaUrl ? resolveAudioContent(msg.mediaUrl) : { role: 'unknown' };
    // A player that published a now-playing MediaSession title is actively
    // presenting real content, so it counts as 'content' even when the URL has
    // no findable <audio> element in this frame (a `new Audio()`-driven embed).
    // An incidental UI ding never sets mediaSession, so this can't mislabel one.
    const audioRole = (audio.role === 'content' || ms.title) ? 'content' : audio.role;
    // Per-URL clip match (a clip whose declared contentUrl IS the captured URL) —
    // the most specific metadata possible, so it outranks the page-level poster
    // and og:title in the consumer. Gives each clip on a multi-clip page its own
    // title + thumbnail instead of the shared page card. Prefer the Firedown JSON
    // block (multi-clip pages use it so older builds / crawlers aren't tripped),
    // then fall back to a schema.org VideoObject for ordinary single-video sites.
    const videoMatch = msg.mediaUrl
      ? (readDeclaredMediaByUrl(msg.mediaUrl) || readVideoJsonLdByUrl(msg.mediaUrl))
      : null;
    return Promise.resolve({
      url: location.href,
      title: document.title || '',
      audioRole: audioRole,
      audioLdName: audio.name || '',
      audioLdDescription: audio.description || '',
      audioLdThumbnail: audio.thumbnail || '',
      // Per-item now-playing metadata (most specific for an embedded player).
      mediaSessionTitle: ms.title,
      mediaSessionArtist: ms.artist,
      mediaSessionArtwork: ms.artwork,
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
      // Per-URL VideoObject match — clip-specific, ranked above the page-level
      // fields in the consumer (requests.js).
      videoLdMatchName: videoMatch ? videoMatch.name : '',
      videoLdMatchDescription: videoMatch ? videoMatch.description : '',
      videoLdMatchThumbnail: videoMatch ? videoMatch.thumbnail : '',
      // Thumbnail sources, most-specific first (consumer ranks poster highest).
      poster,
      ogImage: ogp('og:image:secure_url') || ogp('og:image'),
      videoLdThumbnail: (videoLd && videoLd.thumbnail) || '',
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
    clog('[cs] sending batch of', batch.length);
    try {
      const p = browser.runtime.sendMessage({ kind: 'images-detected', urls: batch });
      if (p && p.catch) p.catch((e) => clog('[cs] send rejected:', e?.message));
    } catch (e) {
      clog('[cs] send threw:', e?.message);
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

  // Tier-A passive media: a <video>/<audio> with a direct file src (not a
  // blob:/MediaSource handle) declares the media URL right in the DOM, so we
  // can capture it without the user pressing play. queue() filters non-http
  // (so a blob: currentSrc is ignored), and the background reclassifies the
  // .mp4/.m3u8 by extension into a media capture. currentSrc reflects the
  // element's resolved source after <source> selection.
  function reportMediaEl(el) {
    if (!el) return;
    const src = el.currentSrc || el.src || el.getAttribute('src');
    if (src) queue(src);
  }

  // CSS background photos — the Google-Maps class. App-like galleries render
  // their content images as `<div style="background-image:url(…)">` (Maps'
  // place-photo carousel/lightbox: extensionless lh3.googleusercontent.com
  // URLs), so no <img> exists for the element scan to see, and a service-
  // worker/cache-served copy never crosses webRequest either — this is the
  // only net for them. INLINE style only, on purpose: JS-assigned per-item
  // photos are set via el.style / style="…" (which is exactly the dynamic-
  // gallery pattern), while a class-styled background is a design asset, not
  // content — and reading it would mean getComputedStyle on every element in
  // the subtree. el.style.backgroundImage is already parsed/normalized by the
  // CSSOM (quotes canonicalized, shorthand `background:` resolved), so the
  // regex only splits multiple url() layers. queue() drops non-http(s), so
  // data:/gradient layers fall out for free.
  const CSS_URL_RE = /url\(\s*(['"]?)([^'")]+)\1\s*\)/g;
  function reportBgImage(el) {
    if (!el || !el.style) return;
    const bg = el.style.backgroundImage;
    if (!bg || bg === 'none') return;
    CSS_URL_RE.lastIndex = 0;
    let m = CSS_URL_RE.exec(bg);
    while (m !== null) {
      queue(m[2]);
      m = CSS_URL_RE.exec(bg);
    }
  }

  function scan(root) {
    if (!root || root.nodeType !== 1) return;
    if (root.tagName === 'IMG') reportImg(root);
    else if (root.tagName === 'SOURCE') reportSource(root);
    else if (root.tagName === 'VIDEO' || root.tagName === 'AUDIO') reportMediaEl(root);
    else reportBgImage(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('img').forEach(reportImg);
      root.querySelectorAll('source').forEach(reportSource);
      root.querySelectorAll('video, audio').forEach(reportMediaEl);
      // Substring match keeps the selector cheap; reportBgImage no-ops on a
      // background-color-only style.
      root.querySelectorAll('[style*="background"]').forEach(reportBgImage);
    }
  }

  // -------------------------------------------------------------------------
  // Passive embedded-media scrape (no playback required)
  //
  // Many sites inline the real progressive/HLS URL in the page source but only
  // *fetch* it when the user presses play — so the wire (webRequest) source
  // never fires and nothing is captured. The element scan above covers
  // <video>/<source> with a direct src; this covers the two remaining shapes:
  //
  //   Tier A (declared, low noise): og:video* / twitter:player:stream meta
  //     tags and JSON-LD VideoObject.contentUrl — each explicitly names "the
  //     page's video".
  //   Tier B (targeted): a media-extension URL inside an inline <script> that
  //     sits next to a media-ish JSON/JS key (url/contentUrl/file/src/hls/…),
  //     e.g. window.dataLayer.push({video:{url:"…mp4"}}). The key-proximity
  //     requirement keeps us off the many unrelated absolute URLs in ad /
  //     analytics blobs; the media extension itself already excludes most junk.
  //
  // Everything queued here rides the same images-detected path: the background
  // reclassifies by extension into a media capture, HEAD-probes it for headers/
  // cookies, applies the parser block-list (so parser-owned sites don't dupe),
  // and the repository dedups against a later wire capture if the user does play.
  // -------------------------------------------------------------------------
  const MEDIA_EXT = 'mp4|m4v|mov|m3u8|m3u|mpd|webm|mkv|m4a|mp3|aac|flac|wav|opus|weba|ts';
  // A media key (allow-listed) → quoted/bare media URL. Keys are the ones that
  // in practice hold a playable URL; pairing the key with a media extension is
  // what makes the blind script scan "targeted". `\\/` handling below covers
  // URLs embedded as escaped JSON strings ("https:\/\/…").
  const SCRIPT_MEDIA_RE = new RegExp(
    '["\']?(?:contentUrl|playable_url(?:_quality_hd)?|playableUrl|playUrl|playurl|' +
      'mediaUrl|videoUrl|manifestUrl|streamUrl|hlsUrl|dashUrl|src|source|file|url|hls|dash|stream)' +
      '["\']?\\s*[:=]\\s*["\']' +
      '((?:https?:)?\\\\?/\\\\?/[^"\'\\s]+?\\.(?:' + MEDIA_EXT + ')(?:\\?[^"\'\\s]*)?)["\']',
    'gi'
  );

  const SCRIPT_SCAN_BUDGET = 4_000_000; // total chars of inline script scanned
  const MAX_SCRIPT_MEDIA = 40;          // cap emitted URLs per scrape pass
  const scrapedScripts = new WeakSet(); // each inline script scanned once

  function unescapeUrl(u) {
    // JSON-embedded URLs carry escaped slashes ("https:\/\/…"); a few also
    // arrive protocol-relative ("//host/…"). Normalise both to an https URL so
    // queue()'s ^https?: filter accepts them.
    let s = u.replace(/\\\//g, '/');
    if (s.startsWith('//')) s = 'https:' + s;
    return s;
  }

  function scrapeMetaTags() {
    // og:video / og:video:secure_url / twitter:player:stream — the standard
    // "this page is a video" declarations. property= and name= are both used
    // in the wild, so accept either.
    const props = [
      'og:video', 'og:video:url', 'og:video:secure_url',
      'twitter:player:stream',
    ];
    for (const p of props) {
      const el = document.querySelector(
        `meta[property="${p}"], meta[name="${p}"]`
      );
      const content = el && el.getAttribute('content');
      // og:video is often an embed *page* (text/html); only queue when it
      // actually looks like a media file — otherwise the wire/HTML path owns it.
      if (content && new RegExp('\\.(?:' + MEDIA_EXT + ')(?:[?#]|$)', 'i').test(content)) {
        queue(content);
      }
    }
  }

  function scrapeJsonLdMedia() {
    // JSON-LD VideoObject.contentUrl is the actual media file (embedUrl is a
    // player page, so we skip it). Bounded, defensive parse — one bad block
    // must not kill the rest.
    const scripts = document.querySelectorAll('script[type="application/ld+json"]');
    let budget = 4000; // node walk cap, mirrors readVideoJsonLd's spirit
    const visit = (node, depth) => {
      if (!node || typeof node !== 'object' || depth > 8 || budget-- <= 0) return;
      if (Array.isArray(node)) {
        for (const it of node) visit(it, depth + 1);
        return;
      }
      const t = node['@type'];
      if ((t === 'VideoObject' || (Array.isArray(t) && t.includes('VideoObject')))
          && typeof node.contentUrl === 'string') {
        queue(node.contentUrl.trim());
      }
      for (const k in node) {
        const v = node[k];
        if (v && typeof v === 'object') visit(v, depth + 1);
      }
    };
    for (const s of scripts) {
      let data;
      try { data = JSON.parse(s.textContent); } catch (_) { continue; }
      visit(data, 0);
    }
  }

  function scrapeInlineScripts() {
    // Targeted Tier B: walk inline <script> text for media URLs that sit next
    // to a media key. Bounded by a global char budget and a per-pass emit cap,
    // and each script element is scanned at most once.
    let budget = SCRIPT_SCAN_BUDGET;
    let emitted = 0;
    const scripts = document.querySelectorAll('script:not([src])');
    for (const s of scripts) {
      if (emitted >= MAX_SCRIPT_MEDIA || budget <= 0) break;
      if (scrapedScripts.has(s)) continue;
      scrapedScripts.add(s);
      const text = s.textContent;
      if (!text) continue;
      // Cheap reject: skip scripts that mention no media extension at all.
      if (!/\.(?:mp4|m4v|mov|m3u8|m3u|mpd|webm|mkv|m4a|mp3|aac|flac|wav|opus|weba|ts)\b/i.test(text)) {
        continue;
      }
      const slice = text.length > budget ? text.slice(0, budget) : text;
      budget -= slice.length;
      SCRIPT_MEDIA_RE.lastIndex = 0;
      let m;
      while ((m = SCRIPT_MEDIA_RE.exec(slice)) !== null) {
        const url = unescapeUrl(m[1]);
        if (/^https?:/i.test(url)) {
          queue(url);
          if (++emitted >= MAX_SCRIPT_MEDIA) break;
        }
      }
    }
  }

  function scrapeEmbeddedMedia() {
    try { scrapeMetaTags(); } catch (_) {}
    try { scrapeJsonLdMedia(); } catch (_) {}
    try { scrapeInlineScripts(); } catch (_) {}
  }

  // Initial scan
  scan(document.documentElement);

  // Re-scan at key lifecycle events. The embedded-media scrape runs at
  // DOMContentLoaded/load (not document_start) because the meta tags, JSON-LD
  // and inline data blobs land during parse — and not on every mutation,
  // since these SSR shapes are present at first load (the element-level scan
  // below still covers SPA-injected <video> nodes via the MutationObserver).
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      scan(document.documentElement);
      scrapeEmbeddedMedia();
    });
  } else {
    scrapeEmbeddedMedia();
  }
  window.addEventListener('load', () => {
    scan(document.documentElement);
    scrapeEmbeddedMedia();
  });

  // Watch for DOM changes
  const mo = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === 'childList') {
        m.addedNodes.forEach(scan);
      } else if (m.type === 'attributes') {
        const t = m.target;
        // A style mutation can carry a fresh background-image on ANY element
        // (the lazy-loaded gallery tile pattern), so route it by the mutated
        // attribute, not the tag.
        if (m.attributeName === 'style') reportBgImage(t);
        else if (t.tagName === 'IMG') reportImg(t);
        else if (t.tagName === 'SOURCE') reportSource(t);
        else if (t.tagName === 'VIDEO' || t.tagName === 'AUDIO') reportMediaEl(t);
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
      // 'style' feeds reportBgImage — dynamic galleries assign each tile's
      // photo by rewriting the inline background-image (the Google-Maps
      // class), which never touches src/srcset.
      attributeFilter: ['src', 'srcset', 'style'],
    });
  }
  startObserver();

  // Catch images that finish loading after insertion (lazy load)
  document.addEventListener('load', (e) => {
    if (e.target && e.target.tagName === 'IMG') reportImg(e.target);
  }, true);

  clog('[cs] setup complete');
})();