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

// The responder block's metadata builder, handed to the scanner below so a
// reported <video>/<audio> URL carries its metadata in the report itself (see
// flush()). Null until the block has run.
let collectMediaMeta = null;
// Whether a <video>/<audio> element is a page player's that the page-state
// bridge reports itself (see videoJsOwns). Null until the block has run.
let pagePlayerOwns = null;
// The responder block's image namer (buildImageMetadata), handed to the
// scanner the same way. Null until the block has run.
let collectImageMeta = null;

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
  // SCOPE: when the captured URL's own <video> element is known (scope), the
  // poster is read ONLY from THAT element's player root (.jwplayer / .video-js
  // / .plyr via closest()) — on a page with several players the page-wide
  // scan below returned the FIRST player's poster for every clip, the same
  // first-element bug the <video poster> read had. No player root around the
  // bound element → '' (never fall back to another player's poster; an empty
  // poster makes native decode this clip's own frame, which is per-clip
  // correct). The page-wide scan remains for the no-bound-element case only.
  function posterFromPlayerBg(scope) {
    const sels = ['.jw-preview', '.vjs-poster', '.plyr__poster', '.video-js .vjs-poster'];
    let root = document;
    if (scope) {
      let playerRoot = null;
      try {
        playerRoot = scope.closest ? scope.closest('.jwplayer, .video-js, .plyr') : null;
      } catch (_) { playerRoot = null; }
      if (!playerRoot) return '';
      root = playerRoot;
    }
    for (let i = 0; i < sels.length; i++) {
      let els;
      try { els = root.querySelectorAll(sels[i]); } catch (_) { continue; }
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
  // Computed in the frame that owns the media (the query is frame-targeted);
  // only an AudioObject DECLARATION may come from a same-origin parent, see
  // declaringDocs below.
  // `base` is the document a relative URL belongs to: a declaration read from
  // a parent frame resolves against THAT document, not this frame's location.
  const absUrl = (u, base) => {
    try { return new URL(u, base || location.href).href; } catch (_) { return ''; }
  };
  // Compare two URLs ignoring the #fragment (signed URLs differ only there).
  // `base` resolves a relative `a` (a declared contentUrl); `b` is the
  // captured media URL, already absolute.
  const sameUrl = (a, b, base) => {
    const x = absUrl(a, base);
    const y = absUrl(b);
    if (!x || !y) return false;
    return x.split('#')[0] === y.split('#')[0];
  };
  // A declared thumbnail made absolute against its declaring document, so a
  // relative one is fetchable by the native side. Empty stays empty (a bare
  // `new URL('', base)` would turn "no thumbnail" into the page URL).
  const absThumb = (u, base) => (u ? (absUrl(u, base) || u) : '');

  // The documents that may DECLARE a clip played in this frame, most specific
  // first: this frame's own, then each SAME-ORIGIN ancestor's. A gallery that
  // shows its clips inside an iframe declares them in the page that embeds
  // the iframe — firedown.app's own homepage lists the /demo showcase clips in
  // its firedown+json block — and since the metadata query became
  // frame-targeted (every frame answers for its own media), the frame that
  // holds the <video> is the one asked, so a top-frame-only read titled every
  // demo clip with the iframe's own <title> ("Firedown · demo").
  // Only PER-URL declarations read these (a contentUrl that IS the captured
  // URL — exact, so it cannot be another clip's). Page-level fields (og:title,
  // <title>, the first VideoObject) stay this frame's: a parent's headline is
  // NOT the clip's, the shared-page-title bug the frame-targeting removed.
  // Same origin only, checked explicitly rather than trusted to the engine's
  // access check: a cross-origin ancestor is skipped (its document throws or
  // is refused) and the walk climbs past it, so a same-origin top page behind
  // a cross-origin middle frame still counts — the page itself could read it.
  // A sandboxed frame's origin is opaque ("null") and never matches.
  const MAX_DECLARING_ANCESTORS = 4;
  const originOf = (w) => {
    let o = '';
    try { o = w.origin; } catch (_) { o = ''; }
    if (!o) {
      try { o = w.location.origin; } catch (_) { o = ''; }
    }
    return (typeof o === 'string') ? o : '';
  };
  const declaringDocs = () => {
    const out = [{ doc: document, base: document.baseURI || location.href }];
    const selfOrigin = originOf(window);
    if (!selfOrigin || selfOrigin === 'null') return out;
    let w = window;
    for (let i = 0; i < MAX_DECLARING_ANCESTORS; i++) {
      let parent = null;
      try { parent = w.parent; } catch (_) { parent = null; }
      if (!parent || parent === w) break;
      w = parent;
      if (originOf(w) !== selfOrigin) continue;
      let doc = null;
      let base = '';
      try {
        doc = w.document;
        base = doc.baseURI;
      } catch (_) {
        doc = null;
      }
      if (doc && base) out.push({ doc, base });
    }
    return out;
  };

  // Generic depth/breadth-bounded JSON-LD tree walk: calls visit(node) on every
  // object node and returns the first truthy result (shares the caps the video
  // walk above uses; budget is per-call). Used by the audio reader below.
  const eachJsonLdNode = (visit, doc) => {
    const scripts = (doc || document).querySelectorAll('script[type="application/ld+json"]');
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
  // Read from this frame's document first, then same-origin ancestors
  // (declaringDocs): a clip played in an iframe is often declared by the page
  // that embeds it.
  const readVideoJsonLdByUrl = (mediaUrl) => {
    if (!mediaUrl) return null;
    const docs = declaringDocs();
    for (let i = 0; i < docs.length; i++) {
      const { doc, base } = docs[i];
      const hit = eachJsonLdNode((node) => {
        if (!isVideoType(node['@type'])) return null;
        const contentUrl = ldString(node.contentUrl) || ldString(node.url);
        if (!contentUrl || !sameUrl(contentUrl, mediaUrl, base)) return null;
        return {
          name: ldString(node.name),
          description: ldString(node.description),
          thumbnail: absThumb(ldImage(node.thumbnailUrl) || ldImage(node.thumbnail), base),
        };
      }, doc);
      if (hit) return hit;
    }
    return null;
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
  // multi-clip page gets its own title + thumbnail. firedown.app's /demo
  // showcase is the case it exists for, and there the clips play inside an
  // IFRAME while the block sits in the embedding homepage — so this reads
  // this frame's document first, then same-origin ancestors (declaringDocs).
  // Every block in a document is read (a page may carry more than one), each
  // parsed in isolation so one malformed block can't hide the rest. Returns
  // {name, description, thumbnail} or null.
  const readDeclaredMediaByUrl = (mediaUrl) => {
    if (!mediaUrl) return null;
    const docs = declaringDocs();
    for (let d = 0; d < docs.length; d++) {
      const { doc, base } = docs[d];
      let blocks;
      try {
        blocks = doc.querySelectorAll('script[type="application/firedown+json"]');
      } catch (_) {
        continue;
      }
      for (let b = 0; b < blocks.length; b++) {
        let list;
        try {
          list = JSON.parse(blocks[b].textContent);
        } catch (_) {
          continue;
        }
        if (!Array.isArray(list)) continue;
        for (let i = 0; i < list.length; i++) {
          const item = list[i];
          if (!item || typeof item !== 'object') continue;
          const contentUrl = ldString(item.contentUrl) || ldString(item.url);
          if (!contentUrl || !sameUrl(contentUrl, mediaUrl, base)) continue;
          return {
            name: ldString(item.name),
            description: ldString(item.description),
            thumbnail: absThumb(ldImage(item.thumbnail) || ldImage(item.thumbnailUrl), base),
          };
        }
      }
    }
    return null;
  };

  const isAudioType = (t) =>
    t === 'AudioObject' || (Array.isArray(t) && t.includes('AudioObject'));

  // A schema.org AudioObject whose contentUrl (or url) IS the captured media —
  // the page declaring "this URL is the audio content". Returns its
  // {name, description, thumbnail} so the consumer can prefer it over the
  // generic page title; null if no AudioObject points at this URL.
  // Like the video reader: this frame first, then same-origin ancestors (an
  // article declaring the audio its embedded player plays).
  const readAudioJsonLd = (mediaUrl) => {
    const docs = declaringDocs();
    for (let i = 0; i < docs.length; i++) {
      const { doc, base } = docs[i];
      const hit = eachJsonLdNode((node) => {
        if (!isAudioType(node['@type'])) return null;
        const contentUrl = ldString(node.contentUrl) || ldString(node.url);
        if (!contentUrl || !sameUrl(contentUrl, mediaUrl, base)) return null;
        return {
          name: ldString(node.name),
          description: ldString(node.description),
          thumbnail: absThumb(ldImage(node.thumbnailUrl) || ldImage(node.thumbnail), base),
        };
      }, doc);
      if (hit) return hit;
    }
    return null;
  };

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

  // Whether an <audio>/<video> element plays the captured URL (src attribute,
  // currentSrc or a <source> child).
  const elementPlays = (el, mediaUrl) => {
    if (el.getAttribute('src') && sameUrl(el.src, mediaUrl)) return true;
    if (el.currentSrc && sameUrl(el.currentSrc, mediaUrl)) return true;
    const sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
    for (let j = 0; j < sources.length; j++) {
      if (sameUrl(sources[j].src, mediaUrl)) return true;
    }
    return false;
  };

  // Whether `node` holds a media element other than `el` that is not a second
  // copy of the same clip. A card can carry two elements for one clip
  // (lasprovincias.es renders each podcast's <audio> once in the card and
  // again in a cookie-wall template that unwraps beside it), and that copy
  // must not read as a second clip. An element with no source counts as
  // another clip: it is a player that has not loaded yet.
  const holdsAnotherClip = (node, el, mediaUrl) => {
    const els = node.querySelectorAll('audio, video');
    for (let i = 0; i < els.length; i++) {
      if (els[i] === el) continue;
      if (elementPlays(els[i], mediaUrl)) continue;
      return true;
    }
    return false;
  };

  // ── The page player playing a URL its element does not show ──
  // A Video.js player (Brightcove's is one) fed an HLS/DASH manifest plays it
  // through MSE: its <video> carries a blob: src or none, so findBoundMedia cannot tie
  // the manifest the wire saw to an element, and the capture fell back to the
  // page's FIRST <video poster> and og:title. lasprovincias.es (HAR 26-10-19):
  // ten Brightcove players on the front page, four captured, all four named
  // "Las Provincias - Diario de noticias…" and all four showing the poster of
  // the first player on the page. The player itself knows: currentSources()
  // lists the manifest, poster() its picture, and Brightcove's
  // mediainfo.name the clip's title. Read through the Xray waiver (the
  // page-state bridge's mechanism; plain `window` where there is none),
  // primitives and index loops only. A URL matched only by its path (another
  // query on the same manifest) counts when exactly one player has it.
  const MAX_PLAYERS_READ = 64;
  const MAX_PLAYER_SOURCES = 32;

  const readStr = (o, key) => {
    try {
      const v = o[key];
      return typeof v === 'string' ? v : '';
    } catch (_) {
      return '';
    }
  };

  const callStr = (o, fn) => {
    try {
      const v = typeof o[fn] === 'function' ? o[fn]() : null;
      return typeof v === 'string' ? v : '';
    } catch (_) {
      return '';
    }
  };

  const pathOf = (u) => {
    try {
      const x = new URL(u, location.href);
      return x.origin + x.pathname;
    } catch (_) {
      return '';
    }
  };

  const httpUrl = (u) => {
    const abs = u ? absUrl(u) : '';
    return /^https?:\/\//i.test(abs) ? abs : '';
  };

  const listVideoJsPlayers = () => {
    let pw = null;
    try { pw = window.wrappedJSObject || window; } catch (_) { pw = null; }
    let vjs = null;
    try { vjs = pw ? pw.videojs : null; } catch (_) { vjs = null; }
    if (typeof vjs !== 'function') return [];
    let all = null;
    try { all = typeof vjs.getAllPlayers === 'function' ? vjs.getAllPlayers() : null; } catch (_) { all = null; }
    let n = 0;
    try { n = (all && typeof all.length === 'number') ? all.length : 0; } catch (_) { n = 0; }
    const out = [];
    for (let i = 0; i < n && out.length < MAX_PLAYERS_READ; i++) {
      let pl = null;
      try { pl = all[i]; } catch (_) { pl = null; }
      if (pl) out.push(pl);
    }
    return out;
  };

  const playerSourceUrls = (pl) => {
    const out = [];
    let srcs = null;
    try { srcs = typeof pl.currentSources === 'function' ? pl.currentSources() : null; } catch (_) { srcs = null; }
    let n = 0;
    try { n = (srcs && typeof srcs.length === 'number') ? srcs.length : 0; } catch (_) { n = 0; }
    for (let i = 0; i < n && i < MAX_PLAYER_SOURCES; i++) {
      let s = null;
      try { s = srcs[i]; } catch (_) { s = null; }
      const u = s ? readStr(s, 'src') : '';
      if (u) out.push(u);
    }
    const cur = callStr(pl, 'currentSrc');
    if (cur) out.push(cur);
    return out;
  };

  // { root, media, name, poster } of a Video.js player. The root is looked up
  // by id in this script's own document, so every DOM read after this one is
  // an ordinary content-script read, not a waived one.
  const describeVideoJsPlayer = (pl) => {
    const id = callStr(pl, 'id');
    let root = null;
    try { root = id ? document.getElementById(id) : null; } catch (_) { root = null; }
    let media = null;
    try { media = root ? root.querySelector('video, audio') : null; } catch (_) { media = null; }
    let info = null;
    try { info = pl.mediainfo || null; } catch (_) { info = null; }
    const name = info ? usableCardLabel(readStr(info, 'name')) : '';
    const poster = httpUrl(callStr(pl, 'poster')) || (info ? httpUrl(readStr(info, 'poster')) : '');
    return { root, media, name, poster };
  };

  // The Video.js player whose root holds `el` (its <video> tech), or null.
  const videoJsPlayerOf = (el) => {
    const root = playerRootOf(el);
    if (!root || !root.id || !root.classList || !root.classList.contains('video-js')) return null;
    const players = listVideoJsPlayers();
    for (let i = 0; i < players.length; i++) {
      if (callStr(players[i], 'id') === root.id) return players[i];
    }
    return null;
  };

  // A Video.js player's own <video> tech, once the player holds its sources,
  // is the page-state bridge's to report: it reads the player's API — every
  // source (the HLS master beside the mp4 the tech was handed), the clip's
  // name, its poster — and reports the clip as one capture, claiming the rest.
  // Reported here as well, the tech's mp4 raced the bridge's report of the
  // same clip, a second row whenever the HEAD probe answered first.
  const videoJsOwns = (el) => {
    const pl = videoJsPlayerOf(el);
    return !!pl && playerSourceUrls(pl).length > 0;
  };
  pagePlayerOwns = videoJsOwns;

  const findPagePlayer = (mediaUrl) => {
    const players = listVideoJsPlayers();
    if (!players.length) return null;
    const wantPath = pathOf(mediaUrl);
    let byPath = null;
    let pathHits = 0;
    for (let i = 0; i < players.length; i++) {
      const urls = playerSourceUrls(players[i]);
      let exact = false;
      let path = false;
      for (let j = 0; j < urls.length; j++) {
        if (sameUrl(urls[j], mediaUrl)) {
          exact = true;
        } else if (wantPath && pathOf(urls[j]) === wantPath) {
          path = true;
        }
      }
      if (exact) return describeVideoJsPlayer(players[i]);
      if (path) {
        pathHits++;
        byPath = players[i];
      }
    }
    return pathHits === 1 ? describeVideoJsPlayer(byPath) : null;
  };

  // ── Per-clip title on a MULTI-video page (card shape) ──
  // A grid of clips (higgsfield.ai/explore, HAR 26-10-09) carries its
  // per-clip metadata only in a JSON feed the catcher rejects; in the DOM each
  // clip is a CARD — an aria-hidden, poster-less <video> beside a poster <img>
  // (removed once the clip plays) and a labelled <button aria-label="View
  // Countryside Duo"> covering the card. With nothing clip-specific in the
  // responder, every clip took og:title + og:image: one title and one picture
  // for twenty videos. Page-level fields describe the PAGE, which equals the
  // clip only on a single-video page, so these readers apply only when the
  // frame holds more than one <video>.
  //
  // The title is the bound element's own aria-label/title, else a labelled
  // link/button/figcaption in its card, else the card's first heading: walk
  // up at most CARD_MAX_LEVELS ancestors, and stop the moment an ancestor
  // holds another clip — past that point a label belongs to a container of
  // several clips, and handing it to one would be the first-element bug
  // again (the julianc.net poster case). Control labels ("Play", "Mute
  // video", "More options") are not titles and are skipped. A label is taken
  // verbatim: the site's own wording ("View X") is not ours to strip.
  //
  // A news front page's card is a heading beside the clip, not a labelled
  // control (lasprovincias.es: <h2> + <audio> under one div, the picture one
  // level up), hence the heading pass. Everything inside a player root is
  // the PLAYER's: its control buttons ("Play", "Rewind 10 Seconds", the
  // longest of which used to win) and its root label (Video.js labels every
  // player "Video Player"), so the walk starts at the root and skips
  // candidates inside one.
  const CARD_MAX_LEVELS = 4;
  // Hard bound on the ancestors a walk climbs, counting the free wrappers
  // (see cardAncestors).
  const CARD_MAX_HOPS = 12;
  // A control's label: the fallback when the card names the clip nowhere else.
  const CARD_LABELLED_SEL = 'a[aria-label], a[title], button[aria-label], button[title], '
    + '[role="button"][aria-label], [role="link"][aria-label]';
  // An attribute a site keeps the clip's title in (lasprovincias.es: the share
  // button's data-title; its visible title is a <p class="v-a-t">, a class no
  // rule can read).
  const CARD_DATA_TITLE_SEL = '[data-title], [data-video-title], [data-headline]';
  // A caption: the clip's own description, read without its credits.
  const CARD_CAPTION_SEL = 'figcaption, [itemprop="caption"], [class*="caption" i]';
  const CAPTION_TOKEN_RE = /(?:^|[_-])caption(?:$|[_-])/;
  // What names a card: a heading, a schema.org headline or name, or an element
  // whose class or test id says title / headline / heading / hed (BEM
  // `card__title`, CNN's `container__headline-text`, Condé Nast's camelCase
  // `SummaryItemHedBase`, BBC's data-testid="card-headline"). The CSS only
  // prefilters; titleElement decides.
  const CARD_HEADING_SEL = 'h1, h2, h3, h4, h5, h6, [role="heading"], [itemprop="headline"], [itemprop="name"], '
    + '[class*="title" i], [class*="headline" i], [class*="heading" i], [class*="hed" i], '
    + '[data-testid*="title" i], [data-testid*="headline" i], [data-testid*="heading" i]';
  const TITLE_TOKEN_RE = /(?:^|[_-])(?:title|headline|heading|hed)(?:$|[_-])/;
  // A subtitle, kicker or eyebrow is not the title ("subtitle" itself never
  // matches: no separator before "title"), nor is a modifier saying a card
  // HAS one (`card--with-title`, `has-title`).
  const NOT_TITLE_TOKEN_RE = /(?:^|[_-])(?:sub|kicker|eyebrow|over|pre|super|section|tooltip|modal|has|with|without|no|show|hide|is)[_-]*(?:title|headline|heading|hed)/;
  // schema.org items whose `name` is not a clip's title.
  const NOT_TITLE_ITEM_RE = /(?:Person|Organization|Organisation|Place|Brand|Rating|Offer|ImageObject|WebSite|WebPage|BreadcrumbList|ListItem)$/i;
  const NOT_TITLE_ITEMPROP_RE = /^(?:author|creator|publisher|contributor|copyrightHolder|sourceOrganization|provider|producer|director|actor|image|thumbnail|logo)$/i;
  // An image alt is a title only when it reads like one (several words, not
  // a file name: CNN's are "bad audience still 1.jpg").
  const ALT_FILE_RE = /\.(?:jpe?g|png|gif|webp|avif|svg|bmp|tiff?|heic)\b/i;
  // A label made only of words for a KIND of picture says what it is, never
  // what it shows: the Daily Mail's video poster is <img alt="Hero video
  // poster">, which named its clip. English and the languages Firedown ships.
  const PICTURE_KIND_RE = new RegExp('^(?:(?:' + [
    '\\d+', 'an?', 'the', 'of', 'for', 'de', 'del', 'la', 'el', 'le', 'du', 'da', 'do', 'di', 'von', 'van', 'hero', 'main', 'lead', 'featured', 'default', 'cover', 'video', 'clip', 'media',
    'poster', 'thumbnail', 'thumb', 'image', 'img', 'photo', 'photograph', 'picture', 'pic', 'still',
    'frame', 'preview', 'placeholder', 'banner', 'icon', 'logo', 'avatar', 'gallery', 'slide',
    'imagen', 'foto', 'miniatura', 'portada', 'vídeo', 'imagem', 'capa', 'bild', 'vorschau',
    'immagine', 'anteprima', 'copertina', 'vignette', 'aperçu', 'afbeelding', 'obraz', 'zdjęcie',
    'изображение', 'фото', 'превью', 'görsel', 'resim', 'kapak',
  ].join('|') + ')(?:[\\s_-]+|$))+$', 'i');
  const pictureKindLabel = (t) => PICTURE_KIND_RE.test((t || '').trim());
  // Inside a story link, the element that holds its headline, most specific
  // first (CNN: <span class="container__headline-text" data-editable="headline">
  // inside a <div class="container__headline"> that also holds a "Video" label).
  const LINK_HEADLINE_SELS = ['[itemprop="headline"]', '[data-editable="headline"]',
    '[class*="headline-text"]', 'h1, h2, h3, h4, h5, h6, [role="heading"]', '[class*="headline"]'];
  // How far up a clip's story link may sit (CNN: video, its container, the
  // interactive-video, the media, the media wrapper, then the link).
  const STORY_LINK_MAX_LEVELS = 6;
  // A photo or video CREDIT ("BFMTV / Reuters", "Getty Images") says who shot
  // the clip, never what it shows. CNN's card caption is nothing but one
  // (<figcaption class="interactive-video__credit">), which titled every looping
  // video on its front page with its agency.
  const CREDIT_CLASS_RE = /(?:^|[\s_-])(?:credits?|copyright|attribution|byline)(?:$|[\s_-])/i;
  const CREDIT_ITEMPROP_RE = /^(?:creditText|copyrightHolder|copyrightNotice|author)$/i;
  const PLAYER_ROOT_SEL = '.video-js, .jwplayer, .plyr';
  const SECTIONING_SEL = 'section, article, aside, nav, header, footer';
  // A node whose text runs past this is an article body or a page section,
  // not a card: a heading AFTER the clip there is a subheading.
  const CARD_TEXT_MAX = 400;
  const CARD_IMAGE_MIN_PX = 64;
  const CARD_CONTROL_VERBS = [
    'play', 'pause', 'mute', 'unmute', 'stop', 'replay', 'close', 'share', 'like', 'unlike',
    'save', 'download', 'copy', 'delete', 'remove', 'report', 'more', 'more options', 'options',
    'menu', 'settings', 'next', 'previous', 'prev', 'back', 'forward', 'skip', 'fullscreen',
    'full screen', 'exit full ?screen', 'enter full ?screen', 'picture in picture', 'expand',
    'collapse', 'open', 'view', 'watch', 'see', 'show', 'read', 'volume', 'captions', 'subtitles',
    'loading',
  ];
  const CARD_CONTROL_NOUNS = [
    'video', 'clip', 'media', 'audio', 'sound', 'track', 'more', 'all', 'now', 'details',
    'button', 'options', 'menu', 'post',
  ];
  // A control verb alone or with a generic object ("Play", "Mute the video").
  // "View Countryside Duo" does not match: the object is the clip's name.
  const CARD_CONTROL_RE = new RegExp('^(?:' + CARD_CONTROL_VERBS.join('|') + ')'
    + '(?:\\s+(?:the\\s+|this\\s+)?(?:' + CARD_CONTROL_NOUNS.join('|') + '))?$', 'i');

  const countVideos = (root) => root.querySelectorAll('video').length;
  // requests.js' urlIsStandaloneAudio, by extension.
  const AUDIO_URL_RE = /^https?:\/\/[^/]+\/[^?#]*\.(?:mp3|m4a|aac|wav|weba|opus|flac|oga|midi?)(?:[?#]|$)/i;

  // A label that can serve as a clip title, or ''.
  const usableCardLabel = (raw) => {
    const t = (raw || '').replace(/\s+/g, ' ').trim();
    if (t.length < 3 || t.length > 200) return '';
    if (CARD_CONTROL_RE.test(t)) return '';
    if (/^https?:\/\//i.test(t)) return '';
    return t;
  };

  const ownCardLabel = (el) =>
    usableCardLabel(el.getAttribute('aria-label')) || usableCardLabel(el.getAttribute('title'));

  // A player's config kept as JSON in a data attribute names its clip:
  // Video.js reads its options from data-setup, Plyr from data-plyr-config,
  // the Daily Mail's player from data-opts (every <video> of its front page
  // carries {"title":"…","src":"…mp4",…}, and every clip took the page's
  // title "UK Home | Daily Mail Online"). Any data-* attribute holding a JSON
  // object counts; the shape decides: a string title (or videoTitle,
  // headline) at the top, or a title or name under media / video / metadata
  // / mediainfo / clip. page-state-bridge.js reads the same (configTitleFor
  // there).
  const CONFIG_TITLE_KEYS = ['title', 'videoTitle', 'video_title', 'headline'];
  const CONFIG_NEST_KEYS = ['media', 'video', 'metadata', 'mediainfo', 'clip'];
  const CONFIG_ATTR_MAX = 100000;
  const configTitleFor = (el) => {
    const attrs = el && el.attributes;
    if (!attrs) return '';
    const str = (o, k) => (o && typeof o[k] === 'string' ? usableCardLabel(o[k]) : '');
    for (let i = 0; i < attrs.length && i < 40; i++) {
      const a = attrs[i];
      if (!/^data-/i.test(a.name)) continue;
      const v = a.value || '';
      if (v.length > CONFIG_ATTR_MAX || !/^\s*\{/.test(v)) continue;
      let o;
      try {
        o = JSON.parse(v);
      } catch (_) {
        continue;
      }
      if (!o || typeof o !== 'object' || Array.isArray(o)) continue;
      for (const k of CONFIG_TITLE_KEYS) {
        const t = str(o, k);
        if (t) return t;
      }
      for (const n of CONFIG_NEST_KEYS) {
        const inner = o[n];
        if (!inner || typeof inner !== 'object' || Array.isArray(inner)) continue;
        const t = str(inner, 'title') || str(inner, 'name');
        if (t) return t;
      }
    }
    return '';
  };

  // The config title of a media element or of the player root around it.
  const playerConfigTitle = (el) => {
    if (!el) return '';
    const own = configTitleFor(el);
    if (own) return own;
    const root = playerRootOf(el);
    return root && root !== el ? configTitleFor(root) : '';
  };

  const isCreditNode = (n) => n.nodeType === 1
    && (CREDIT_CLASS_RE.test(n.getAttribute('class') || '') || CREDIT_ITEMPROP_RE.test(n.getAttribute('itemprop') || ''));

  // A node's text without the credits inside it (a caption that ends in its
  // photographer's name keeps the caption).
  const textWithoutCredits = (root) => {
    if (isCreditNode(root)) return '';
    let out = '';
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => {
        if (n.nodeType === 3) return NodeFilter.FILTER_ACCEPT;
        return isCreditNode(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_SKIP;
      },
    });
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      out += n.nodeValue;
      if (out.length > 2000) break;
    }
    return out;
  };

  // camelCase → kebab, so "SummaryItemHedBase" reads as summary-item-hed-base.
  const kebabToken = (t) => t.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
  const tokensSay = (value, re, notRe) => {
    const toks = (value || '').split(/\s+/);
    for (let i = 0; i < toks.length; i++) {
      const k = kebabToken(toks[i]);
      if (k && re.test(k) && !(notRe && notRe.test(k))) return true;
    }
    return false;
  };

  // True when `el` names something (see CARD_HEADING_SEL).
  const titleElement = (el) => {
    if (/^H[1-6]$/.test(el.tagName) || el.getAttribute('role') === 'heading') return true;
    const prop = el.getAttribute('itemprop');
    if (prop === 'headline') return true;
    if (prop === 'name') {
      // A clip's or story's name, not its author's or publisher's.
      const scope = el.parentElement ? el.parentElement.closest('[itemscope]') : null;
      if (!scope) return false;
      if (NOT_TITLE_ITEM_RE.test(scope.getAttribute('itemtype') || '')) return false;
      if (NOT_TITLE_ITEMPROP_RE.test(scope.getAttribute('itemprop') || '')) return false;
      return true;
    }
    return tokensSay(el.getAttribute('class'), TITLE_TOKEN_RE, NOT_TITLE_TOKEN_RE)
      || tokensSay(el.getAttribute('data-testid'), TITLE_TOKEN_RE, NOT_TITLE_TOKEN_RE);
  };

  // The text a title element carries (a microdata <meta> carries it in content).
  const titleText = (el) => usableCardLabel(el.tagName === 'META'
    ? el.getAttribute('content')
    : textWithoutCredits(el));

  // The text an element's aria-labelledby points at, or ''.
  const labelledByText = (el) => {
    const ids = (el.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean);
    let out = '';
    for (let i = 0; i < ids.length && i < 4; i++) {
      const ref = document.getElementById(ids[i]);
      if (ref) out += ' ' + textWithoutCredits(ref);
    }
    return usableCardLabel(out);
  };

  const playerRootOf = (el) => {
    try {
      return el.closest ? el.closest(PLAYER_ROOT_SEL) : null;
    } catch (_) {
      return null;
    }
  };

  // Where a card walk starts: the element itself, or the player root around it.
  const cardStart = (el) => playerRootOf(el) || el;

  // A card label or heading that a walk may take: visible, and not part of a
  // player's own chrome.
  const cardCandidate = (c) => c.getAttribute('aria-hidden') !== 'true' && !playerRootOf(c);

  // The title a story link gives its story: its own label, else its headline
  // element, else its whole text.
  const storyLinkTitle = (a) => {
    const own = ownCardLabel(a);
    if (own) return own;
    for (const sel of LINK_HEADLINE_SELS) {
      const h = a.querySelector(sel);
      if (!h || !cardCandidate(h)) continue;
      const t = usableCardLabel(textWithoutCredits(h));
      if (t) return t;
    }
    return usableCardLabel(textWithoutCredits(a));
  };

  const linkTarget = (a) => {
    const href = a.getAttribute('href') || '';
    if (!href || href.startsWith('#') || /^javascript:/i.test(href)) return '';
    return (a.href || '').split('#')[0];
  };

  // A clip INSIDE a link to its story is a news card's picture link; the
  // card's headline is the OTHER link to the same story beside it (CNN's
  // front page: a media link around the looping <video> and a text link
  // with the headline, both to the article). The picture link's own text is
  // the credit at best. '' when the clip is in no story link, or no twin
  // lies within a card's reach.
  const storyTwinTitleFor = (start, holdsOther) => {
    let wrap = null;
    let n = start.parentElement;
    for (let level = 0; level < STORY_LINK_MAX_LEVELS && n && n !== document.body; level++) {
      if (n.tagName === 'A' && n.hasAttribute('href')) {
        wrap = n;
        break;
      }
      n = n.parentElement;
    }
    // The picture link's own label is a control's wording ("Play: <title>"):
    // the walk takes it only when nothing in the card names the clip.
    if (!wrap || holdsOther(wrap)) return '';
    const target = linkTarget(wrap);
    if (!target) return '';
    let node = wrap.parentElement;
    for (let level = 0; level < 3 && node && node !== document.body; level++) {
      if (holdsOther(node)) return '';
      const links = node.querySelectorAll('a[href]');
      for (let i = 0; i < links.length; i++) {
        const a = links[i];
        if (a === wrap || a.contains(start) || linkTarget(a) !== target || !cardCandidate(a)) continue;
        const t = storyLinkTitle(a);
        if (t) return t;
      }
      node = node.parentElement;
    }
    return '';
  };

  // Does `node` hold anything beside the branch `from` we climbed out of?
  const addsBeyond = (node, from) => {
    if (node.childElementCount > 1) return true;
    for (let t = node.firstChild; t; t = t.nextSibling) {
      if (t !== from && t.nodeType === 3 && t.nodeValue.trim()) return true;
    }
    return false;
  };

  // The ancestors a card walk visits, nearest first. Only an ancestor that
  // adds something beside the clip's branch spends one of the
  // CARD_MAX_LEVELS: a wrapper holding nothing else is free (CNN nests its
  // looping <video> five wrappers deep, so a four-level budget never reached
  // the card).
  function* cardAncestors(start) {
    let from = start;
    let node = start.parentElement;
    let level = 0;
    for (let hops = 0; hops < CARD_MAX_HOPS && node && node !== document.body
      && node !== document.documentElement; hops++) {
      yield node;
      if (addsBeyond(node, from)) {
        level++;
        if (level >= CARD_MAX_LEVELS) return;
      }
      from = node;
      node = node.parentElement;
    }
  }

  // A caption in `node` that describes the clip, read without its credits.
  const cardCaptionFor = (node) => {
    const caps = node.querySelectorAll(CARD_CAPTION_SEL);
    for (let i = 0; i < caps.length; i++) {
      const c = caps[i];
      if (!cardCandidate(c)) continue;
      if (c.tagName !== 'FIGCAPTION' && c.getAttribute('itemprop') !== 'caption'
        && !tokensSay(c.getAttribute('class'), CAPTION_TOKEN_RE)) continue;
      const t = usableCardLabel(textWithoutCredits(c));
      if (t) return t;
    }
    return '';
  };

  // A control's label in `node` (the link or button WRAPPING the clip
  // included): several candidates (a title link beside "Like"/"Share" that
  // slipped the control filter) → the longest, since a clip title outruns a
  // control verb.
  const cardLabelFor = (node) => {
    let best = node.matches('a, button, [role="button"], [role="link"]') ? ownCardLabel(node) : '';
    const cands = node.querySelectorAll(CARD_LABELLED_SEL);
    for (let i = 0; i < cands.length; i++) {
      if (!cardCandidate(cands[i])) continue;
      const t = ownCardLabel(cands[i]);
      if (t.length > best.length) best = t;
    }
    return best;
  };

  const cardDataTitleFor = (node) => {
    const own = [node].concat(Array.from(node.querySelectorAll(CARD_DATA_TITLE_SEL)));
    for (let i = 0; i < own.length; i++) {
      const c = own[i];
      if (!cardCandidate(c)) continue;
      const t = usableCardLabel(c.getAttribute('data-title') || c.getAttribute('data-video-title')
        || c.getAttribute('data-headline') || '');
      if (t && !ALT_FILE_RE.test(t)) return t;
    }
    return '';
  };

  const cardAltFor = (node) => {
    const imgs = node.querySelectorAll('img[alt]');
    for (let i = 0; i < imgs.length; i++) {
      if (!cardCandidate(imgs[i])) continue;
      const t = usableCardLabel(imgs[i].getAttribute('alt'));
      if (t && /\S\s+\S/.test(t) && !ALT_FILE_RE.test(t) && !pictureKindLabel(t)) return t;
    }
    return '';
  };

  // The clip's title in its card, or ''. holdsOther(node) says when an
  // ancestor already holds another clip; the walk stops there.
  //
  // Ranked by WHAT names the clip, then by distance. A card's title element
  // (cardHeadingFor) or caption wins at the nearest ancestor that has one; a
  // control's label only when the card has neither, because a label is a
  // control's wording (vozpopuli.com: the card link reads "Reproducir: <title>",
  // the <h2> beside it the title). Then a data-title, then the card image's
  // alt (several words, not a file name). A label is still taken verbatim:
  // "View Countryside Duo" (higgsfield.ai, no heading in the card) stays.
  // skipOwn leaves out the element's own label (a picture's alt and title
  // were read already); noLabel leaves out the control-label fallback (a
  // picture's description is never a button's wording).
  const cardTitleFor = (el, holdsOther, skipOwn, noLabel) => {
    const start = cardStart(el);
    if (start === el && !skipOwn) {
      const own = ownCardLabel(el) || labelledByText(el);
      if (own) return own;
    }
    const twin = storyTwinTitleFor(start, holdsOther);
    if (twin) return twin;
    let label = '';
    let dataTitle = '';
    let alt = '';
    for (const node of cardAncestors(start)) {
      if (holdsOther(node)) break;
      const named = labelledByText(node);
      if (named) return named;
      const head = cardHeadingFor(node, start);
      if (head) return head;
      const cap = cardCaptionFor(node);
      if (cap) return cap;
      if (!label) label = cardLabelFor(node);
      if (!dataTitle) dataTitle = cardDataTitleFor(node);
      if (!alt) alt = cardAltFor(node);
    }
    return (noLabel ? '' : label) || dataTitle || alt;
  };

  // The heading that names the clip inside `node`, or ''. The NEAREST one
  // before the clip; a heading after it only in a card-sized node (the
  // picture-then-title card). A heading inside a section, aside or the like
  // that does not hold the clip belongs to something else. Taking the node's
  // first heading titled an article's clip with a subheading below it
  // ("Background") or a "Most read" box beside it, above og:title.
  // Title elements nest (CNN: a headline <div> holding a "Video" label and
  // the headline <span>); the innermost one is the title, so an element
  // inside the one already taken replaces it.
  const cardHeadingFor = (node, start) => {
    const heads = node.querySelectorAll(CARD_HEADING_SEL);
    let before = '';
    let after = '';
    let afterEl = null;
    for (let i = 0; i < heads.length; i++) {
      const h = heads[i];
      if (!cardCandidate(h) || h.contains(start) || !titleElement(h)) continue;
      const section = h.closest(SECTIONING_SEL);
      if (section && section !== node && node.contains(section) && !section.contains(start)) continue;
      const t = titleText(h);
      if (!t) continue;
      if (h.compareDocumentPosition(start) & Node.DOCUMENT_POSITION_FOLLOWING) {
        before = t;
      } else if (!afterEl || afterEl.contains(h)) {
        after = t;
        afterEl = h;
      }
    }
    if (before) return before;
    if (after && (node.textContent || '').length <= CARD_TEXT_MAX) return after;
    return '';
  };

  // The card's picture, for a clip that has no poster of its own and no frame
  // to decode (an <audio>): the first visible http(s) <img> in the card,
  // icons skipped. Same walk and stop rule as the title.
  const cardImageFor = (el, holdsOther) => {
    for (const node of cardAncestors(cardStart(el))) {
      if (holdsOther(node)) return '';
      const imgs = node.querySelectorAll('img');
      for (let i = 0; i < imgs.length; i++) {
        const im = imgs[i];
        if (!cardCandidate(im)) continue;
        // A size we cannot tell (not loaded, no width attribute) is not taken
        // over the page image: that is what an inline icon looks like. Nor is
        // an SVG, a card's icon format.
        const w = im.naturalWidth || parseInt(im.getAttribute('width') || '', 10) || 0;
        if (w < CARD_IMAGE_MIN_PX) continue;
        const u = httpUrl(im.currentSrc || im.getAttribute('src') || '');
        if (u && !/\.svg(?:[?#]|$)/i.test(u)) return u;
      }
    }
    return '';
  };

  // ── An IMAGE's own name ──
  // A captured picture was named by its URL ("2026-09-29t084140z-1203290914-
  // rc2usnaifcu…", "WhatsApp Image 2026-10-19 at 06.46.34 (3)-U2111…"):
  // nothing asked the page. The page names it: the alt it wrote to stand in
  // for it, its title, the caption of the figure it is the only picture of,
  // and for a card picture with none of those the card it sits in (the same
  // walk that names a clip). Built in the scanner's flush and carried in the
  // report, as a clip's metadata is; requests.js pickImageMeta checks it
  // again. The description is what the name left out: the caption with its
  // credit, the whole alt when the name had to be cut, else the card's title.
  const IMAGE_NAME_MAX = 100;
  const IMAGE_DESCRIPTION_MAX = 500;
  // A credit says who took the picture, never what it shows. A parenthesis
  // after a sentence's end is one ("…inundada. (LP)", "…2026. (Photo by
  // AHMAD GHARABLI / AFP via Getty Images)"); inside the sentence it is
  // content ("…en Fuenlabrada (Madrid).", "La Marina Alta (Alicante)"). A
  // parenthesis that SAYS photo or credit, a "Photo: X" tail and a © tail are
  // credits wherever they end the text.
  const CREDIT_AFTER_SENTENCE_RE = /([.!?…])\s*\([^()]{1,120}\)\s*$/;
  const CREDIT_SAYS_RE = /\s*\(\s*(?:photos?|fotos?|fotografía|images?|imagen|pictures?|credits?|crédito|©|copyright)\b[^()]{0,120}\)\s*$/i;
  const CREDIT_TAIL_RE = /(?:^|\s[|–—-]\s|[.!?]\s+)(?:photo(?:graph)?s?|fotos?|fotografía|images?|imagen|credits?|crédito|source|fuente)\s*(?:by|de|:)\s*[^.!?]{1,80}$/i;
  const COPYRIGHT_TAIL_RE = /\s*©.{1,80}$/;
  // An agency dateline before the description ("SAN MARCOS, TEXAS -
  // OCTOBER 15: Democratic U.S. Senate Candidate…", Getty's alts on CNN).
  // "San Diego, California - September 04: …" too: the month and the colon
  // after the day are what make it a dateline.
  const DATELINE_RE = new RegExp('^\\p{L}[\\p{L} .,\'’-]{2,60}\\s[-–—]\\s(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?'
    + '|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)'
    + '\\.?\\s+\\d{1,2}(?:,\\s*\\d{4})?\\s*:\\s+', 'iu');
  const TRAILING_PUNCT_RE = /[\s.,;:·|–—-]+$/;
  // How many card walks one scan batch may spend: a front page reports a
  // hundred pictures at once, and a walk climbs the DOM. A picture that
  // needs a NAME draws on its own budget, one that only wants a description
  // on a smaller one, so descriptions never starve names.
  const IMAGE_NAME_WALKS = 60;
  const IMAGE_DESCRIPTION_WALKS = 30;

  const cleanImageText = (raw) => {
    let t = (raw || '').replace(/\s+/g, ' ').trim();
    if (!t) return '';
    t = t.replace(DATELINE_RE, '');
    for (let i = 0; i < 2; i++) {
      t = t.replace(CREDIT_SAYS_RE, '').replace(CREDIT_AFTER_SENTENCE_RE, '$1')
        .replace(COPYRIGHT_TAIL_RE, '').replace(CREDIT_TAIL_RE, '').trim();
    }
    return t.replace(TRAILING_PUNCT_RE, '').trim();
  };

  // Page text that can name a picture, cleaned, or ''.
  const usableImageName = (raw) => {
    const t = cleanImageText(raw);
    if (t.length < 2) return '';
    if (pictureKindLabel(t) || CARD_CONTROL_RE.test(t)) return '';
    if (ALT_FILE_RE.test(t) || /^https?:\/\//i.test(t)) return '';
    // A machine token ("rc2usnaifcu20260929", "U201291241-1200x840") is no name.
    if (!/\s/.test(t) && t.length >= 12 && (t.match(/\d/g) || []).length >= 3) return '';
    if (!/\p{L}/u.test(t)) return '';
    return t;
  };

  // At most IMAGE_NAME_MAX characters, cut at a word, no half surrogate pair.
  const clipImageName = (t) => {
    if (t.length <= IMAGE_NAME_MAX) return t;
    let cut = t.slice(0, IMAGE_NAME_MAX);
    const sp = cut.lastIndexOf(' ');
    if (sp > IMAGE_NAME_MAX * 0.6) cut = cut.slice(0, sp);
    if (/[\uD800-\uDBFF]$/.test(cut)) cut = cut.slice(0, -1);
    return cut.replace(TRAILING_PUNCT_RE, '');
  };

  const imageSrcOf = (el) => (el.tagName === 'IMG' ? (el.currentSrc || el.getAttribute('src') || '') : '');

  // Another picture in `node` than `el`: a figure or card of several
  // pictures names none of them. An icon (a known width under
  // CARD_IMAGE_MIN_PX), an SVG, a data: placeholder and a copy of the same
  // file do not count.
  const holdsOtherImage = (node, el) => {
    const imgs = node.querySelectorAll('img');
    if (imgs.length > 50) return true;
    const mine = imageSrcOf(el);
    for (let i = 0; i < imgs.length; i++) {
      const im = imgs[i];
      if (im === el) continue;
      const w = im.naturalWidth || parseInt(im.getAttribute('width') || '', 10) || 0;
      if (w && w < CARD_IMAGE_MIN_PX) continue;
      const u = im.currentSrc || im.getAttribute('src') || '';
      if (/^data:|\.svg(?:[?#]|$)/i.test(u)) continue;
      if (mine && u === mine) continue;
      return true;
    }
    return false;
  };

  // The caption of the figure `el` is the only picture of: {name, full}, or
  // null. An aria-hidden caption counts (a site hides it from screen readers
  // because the alt says the same); the credit stays in `full`.
  const imageCaptionFor = (el) => {
    let fig = null;
    try {
      fig = el.closest('figure');
    } catch (_) {
      fig = null;
    }
    if (!fig || holdsOtherImage(fig, el)) return null;
    const caps = fig.querySelectorAll(CARD_CAPTION_SEL);
    for (let i = 0; i < caps.length; i++) {
      const c = caps[i];
      if (playerRootOf(c)) continue;
      if (c.tagName !== 'FIGCAPTION' && c.getAttribute('itemprop') !== 'caption'
        && !tokensSay(c.getAttribute('class'), CAPTION_TOKEN_RE)) continue;
      const full = (c.textContent || '').replace(/\s+/g, ' ').trim();
      const name = usableImageName(textWithoutCredits(c));
      if (name || full) return { name, full };
    }
    return null;
  };

  // A reported picture's {kind:'image', name, description}, or null.
  // `budget` is the batch's {name, description} card-walk allowance.
  const buildImageMetadata = (el, budget) => {
    if (!el || !el.getAttribute) return null;
    let name = '';
    let whole = '';
    const own = [el.getAttribute('alt'), el.getAttribute('title'), el.getAttribute('aria-label')];
    for (let i = 0; i < own.length && !name; i++) {
      name = usableImageName(own[i]);
      if (name) whole = own[i].replace(/\s+/g, ' ').trim();
    }
    if (!name) {
      const lb = labelledByText(el);
      name = usableImageName(lb);
      if (name) whole = lb;
    }
    const caption = imageCaptionFor(el);
    if (!name && caption && caption.name) {
      name = caption.name;
      whole = caption.full;
    }
    // A picture too small to show anything (a known width under 64 px) is
    // an icon or a pixel: no walk for it.
    const w = el.tagName === 'IMG'
      ? (el.naturalWidth || parseInt(el.getAttribute('width') || '', 10) || 0) : 0;
    const walkable = !(w && w < CARD_IMAGE_MIN_PX);
    // The picture's own title, when the alt named it and the title says more
    // (vozpopuli: alt "Petrolero", title "Un buque petrolero").
    const ownTitle = name ? usableImageName(el.getAttribute('title')) : '';
    const clippedSoFar = name ? clipImageName(name) : '';
    const described = (caption && caption.full && cleanImageText(caption.full) !== clippedSoFar)
      || (name && clippedSoFar !== name) || (ownTitle && ownTitle !== clippedSoFar);
    let card = '';
    const pool = name ? 'description' : 'name';
    if (walkable && !described && budget && budget[pool] > 0) {
      budget[pool]--;
      try {
        card = usableImageName(cardTitleFor(el, (node) => holdsOtherImage(node, el), true, pool === 'description'));
      } catch (_) {
        card = '';
      }
    }
    if (!name && card) {
      name = card;
      whole = card;
      card = '';
    }
    if (!name) return null;
    const clipped = clipImageName(name);
    let description = '';
    const cands = [caption ? caption.full : '', clipped !== name ? whole : '', ownTitle, card];
    for (let i = 0; i < cands.length && !description; i++) {
      const d = cands[i] || '';
      if (d && d !== clipped && cleanImageText(d) !== clipped) description = d;
    }
    return {
      kind: 'image',
      name: clipped,
      description: description.slice(0, IMAGE_DESCRIPTION_MAX),
    };
  };
  collectImageMeta = buildImageMetadata;
  // The scanner asks for a fresh allowance per batch.
  buildImageMetadata.budget = () => ({ name: IMAGE_NAME_WALKS, description: IMAGE_DESCRIPTION_WALKS });

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

  // Everything the background knows about a captured URL's page and element.
  // Two callers: the get-page-metadata responder (wire captures) and the
  // scanner's report (DOM captures, see flush()). mediaUrl may be empty.
  const buildPageMetadata = (mediaUrl) => {
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
    // Poster for the captured video, so the native side can use it as the
    // capture's thumbnail instead of decoding a frame from the media with
    // FFmpeg. el.poster reflects the attribute and resolves it to an absolute
    // URL. PER-URL FIRST: the <video>/<source> element BOUND to the captured
    // URL (findBoundMedia — the same match the audio-role logic uses) gives
    // each clip its OWN poster. This used to take the FIRST <video poster> on
    // the page for every capture, which is right on a single-video page and
    // wrong on every multi-video one: a portfolio page with four self-hosted
    // <video src=… poster=…> clips landed all four with clip #1's poster
    // (julianc.net, HAR-verified — distinct motion-0N-poster.jpg per clip,
    // one thumbnail in the sheet). The first-poster read stays as the
    // fallback for a clip played without a findable element on a
    // single-video page, then the player-container background, then
    // og:image / JSON-LD below. Three regimes, decided by what the captured
    // URL could be tied to:
    //   bound element → its poster attribute, else its OWN player's poster
    //     container, else '' — NEVER another clip's poster. A poster-less
    //     clip with an empty img makes the native side decode a frame from
    //     the media itself, which is per-clip correct (at the cost of a
    //     probe); borrowing the page's first poster was a wrong picture.
    //   a page player playing the URL through MSE (findPagePlayer) → its
    //     element, as above, then the player's own poster().
    //   no bound element (MSE/blob src with no readable player, or a clip
    //     played without a DOM node) → on a single-video page the first
    //     <video poster>, then the first player container's background; on a
    //     multi-video page nothing: the first poster belongs to whichever
    //     player comes first, which is how lasprovincias.es gave four clips
    //     the picture of a fifth.
    const multiVideo = countVideos(document) > 1;
    let boundEl = mediaUrl ? findBoundMedia(mediaUrl) : null;
    // The page player playing the URL: the one whose tech element is bound
    // (its name still says which clip it is), else the one whose sources
    // list the URL its element does not show.
    let player = null;
    if (boundEl) {
      const own = videoJsPlayerOf(boundEl);
      player = own ? describeVideoJsPlayer(own) : null;
    } else if (mediaUrl) {
      player = findPagePlayer(mediaUrl);
    }
    if (player && player.media && !boundEl) boundEl = player.media;
    let poster = '';
    if (boundEl) {
      poster = (boundEl.poster ? boundEl.poster : '') || posterFromPlayerBg(boundEl);
    }
    if (!poster && player) poster = player.poster;
    if (!boundEl && !player && !multiVideo) {
      const videoEl = document.querySelector('video[poster]');
      poster = (videoEl && videoEl.poster ? videoEl.poster : '') || posterFromPlayerBg(null);
    }
    const ms = readMediaSession();
    // Multi-video page (see cardTitleFor): a clip with no poster of its own
    // must not take the page's og:image / page VideoObject thumbnail — that is
    // the same picture for every clip. posterlessClip tells the consumer to
    // leave img empty so the native side decodes THIS clip's frame. That
    // holds for a clip no element shows, too, unless it is an audio file
    // (no frame to decode) or a player published what is playing.
    const boundVideo = boundEl ? boundEl.tagName === 'VIDEO' : !!player;
    const unboundClip = !boundEl && !player && !!mediaUrl
      && !AUDIO_URL_RE.test(mediaUrl) && !ms.title;
    const posterlessClip = multiVideo && !poster && (boundVideo || unboundClip);
    // The clip's own title on a multi-clip page: its player's name for it
    // (playerTitle), else its card's label or heading (cardTitle). An
    // <audio> in a list of clips (a news front page's podcast cards) takes
    // its card's picture as well, having no poster and no frame.
    let playerTitle = '';
    let cardTitle = '';
    let cardImage = '';
    if (multiVideo && boundVideo) {
      playerTitle = (player ? player.name : '') || playerConfigTitle(boundEl)
        || playerConfigTitle(player ? player.root : null);
      const walkFrom = boundEl || (player ? player.root : null);
      if (!playerTitle && walkFrom) {
        cardTitle = cardTitleFor(walkFrom, (node) => countVideos(node) > 1);
      }
    } else if (boundEl && boundEl.tagName === 'AUDIO' && holdsAnotherClip(document, boundEl, mediaUrl)) {
      const otherClip = (node) => holdsAnotherClip(node, boundEl, mediaUrl);
      cardTitle = cardTitleFor(boundEl, otherClip);
      cardImage = cardImageFor(boundEl, otherClip);
    }
    // When the background passes the captured media URL, classify whether a
    // standalone audio file is the page's main content (enrich) or incidental
    // (keep the filename) — see resolveAudioContent. Non-audio captures ignore
    // these fields.
    const audio = mediaUrl ? resolveAudioContent(mediaUrl) : { role: 'unknown' };
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
    const videoMatch = mediaUrl
      ? (readDeclaredMediaByUrl(mediaUrl) || readVideoJsonLdByUrl(mediaUrl))
      : null;
    return {
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
      // The URL's own player's name for it, on a multi-video page
      // (findPagePlayer) — ranked with the other per-URL titles.
      playerTitle,
      // Per-clip card label on a multi-clip page (cardTitleFor) — ranked
      // above the page-level title chain in the consumer.
      cardTitle,
      // Thumbnail sources, most-specific first (consumer ranks poster highest).
      poster,
      // The card's picture for an <audio> on a multi-clip page (cardImageFor).
      cardImage,
      // Bound <video> on a multi-video page with no poster of its own: the
      // consumer skips the page-level thumbnails (og:image, page VideoObject).
      posterlessClip,
      ogImage: ogp('og:image:secure_url') || ogp('og:image'),
      videoLdThumbnail: (videoLd && videoLd.thumbnail) || '',
    };
  };
  collectMediaMeta = buildPageMetadata;

  browser.runtime.onMessage.addListener((msg) => {
    if (!msg || msg.kind !== 'get-page-metadata') return;
    return Promise.resolve(buildPageMetadata(msg.mediaUrl));
  });
}

(() => {
  const seen = new Set();
  const BATCH_MS = 200;
  let pending = [];
  let flushTimer = null;
  // URLs queued from a <video>/<audio> element or one of its <source>s —
  // media even when the URL carries no extension (a tokenized src).
  const mediaQueued = new Set();
  // The element each image URL was reported from, until its batch is sent
  // (buildImageMetadata names the picture from it).
  const imageEls = new Map();
  const MEDIA_URL_RE = /\.(?:mp4|m4v|mov|m3u8|m3u|mpd|webm|mkv|m4a|mp3|aac|flac|wav|opus|weba|oga|ogg)(?:[?#]|$)/i;

  // A media URL's metadata travels WITH its report, so the background needs no
  // get-page-metadata round trip for it. That round trip is answered on the
  // page's main thread and loses its 300 ms race while the page is still
  // loading: on higgsfield.ai/explore the clips reported during hydration took
  // 318-414 ms to answer and landed titled with their CDN hash, while the same
  // query answered in 11-31 ms a few seconds later. Built here, in a timer
  // task with no deadline, the answer is the one the responder would give.
  function metadataFor(batch) {
    let out = null;
    const budget = collectImageMeta ? collectImageMeta.budget() : null;
    for (let i = 0; i < batch.length; i++) {
      const url = batch[i];
      const el = imageEls.get(url);
      imageEls.delete(url);
      const media = mediaQueued.has(url) || MEDIA_URL_RE.test(url);
      let m = null;
      try {
        if (media) {
          m = collectMediaMeta ? collectMediaMeta(url) : null;
        } else if (el && el.isConnected && collectImageMeta) {
          m = collectImageMeta(el, budget);
        }
      } catch (e) {
        clog('[cs] metadata failed:', e?.message);
        m = null;
      }
      if (!m) continue;
      if (!out) out = {};
      out[url] = m;
    }
    return out;
  }

  function flush() {
    flushTimer = null;
    if (pending.length === 0) return;
    const batch = pending;
    pending = [];
    const meta = metadataFor(batch);
    clog('[cs] sending batch of', batch.length);
    try {
      const p = browser.runtime.sendMessage({ kind: 'images-detected', urls: batch, meta });
      if (p && p.catch) p.catch((e) => clog('[cs] send rejected:', e?.message));
    } catch (e) {
      clog('[cs] send threw:', e?.message);
    }
  }

  function queue(url, fromMediaEl, el) {
    if (!url || seen.has(url)) return;
    if (!/^https?:/i.test(url)) return;
    seen.add(url);
    if (fromMediaEl) mediaQueued.add(url);
    else if (el) imageEls.set(url, el);
    pending.push(url);
    if (!flushTimer) flushTimer = setTimeout(flush, BATCH_MS);
  }

  function reportImg(img) {
    if (!img) return;
    queue(img.currentSrc || img.src, false, img);
  }

  function reportSource(source) {
    if (!source) return;
    const srcset = source.srcset || source.getAttribute('srcset');
    const mediaParent = source.parentElement && /^(?:VIDEO|AUDIO)$/.test(source.parentElement.tagName)
      ? source.parentElement : null;
    // A <picture>'s <source> is one more file of its <img>, which names it.
    const pictureImg = !mediaParent && source.parentElement && source.parentElement.tagName === 'PICTURE'
      ? source.parentElement.querySelector('img') : null;
    if (srcset) {
      srcset.split(',').forEach((part) => {
        const url = part.trim().split(/\s+/)[0];
        if (url) queue(url, false, pictureImg);
      });
    }
    if (mediaParent && pagePlayerOwns && pagePlayerOwns(mediaParent)) return;
    if (source.src) queue(source.src, !!mediaParent);
  }

  // Tier-A passive media: a <video>/<audio> with a direct file src (not a
  // blob:/MediaSource handle) declares the media URL right in the DOM, so we
  // can capture it without the user pressing play. queue() filters non-http
  // (so a blob: currentSrc is ignored), and the background reclassifies the
  // .mp4/.m3u8 by extension into a media capture. currentSrc reflects the
  // element's resolved source after <source> selection.
  function reportMediaEl(el) {
    if (!el) return;
    if (pagePlayerOwns && pagePlayerOwns(el)) return;
    // el.src reflects an EMPTY src attribute as the document's own URL, so
    // a placeholder <audio src=""> reported the page itself as media (a HEAD
    // probe of the page, then a native probe of its HTML).
    const attr = el.getAttribute('src');
    const src = el.currentSrc || (attr ? el.src : '');
    if (src) queue(src, true);
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
      // The CSSOM keeps a relative url() as written; resolved here, or a
      // site-relative picture never passed queue()'s http(s) test.
      let u = m[2];
      try {
        u = new URL(u, document.baseURI).href;
      } catch (_) {
        u = m[2];
      }
      queue(u, false, el);
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
    // queue()'s ^https?: filter accepts them. A JSON string also escapes
    // characters as \uXXXX (Vimeo's inline playerConfig writes every '&' as
    // &): left in, the URL is not the one the page fetches, so it never
    // matches the URL a parser claimed or the wire's copy.
    let s = u.replace(/\\\//g, '/')
      .replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
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

  // ---- Click-to-load player placeholders --------------------------------
  // A page can show a clip as a picture with a play button and load the
  // player iframe only on a tap (vozpopuli.com: <a data-embed-url=
  // "https://geo.dailymotion.com/player/<cfg>.html?video=<id>">), or hold a
  // lazy iframe that loads its player only when scrolled into view. No player
  // exists until then, so nothing reaches the wire. Report the player URL each
  // one DECLARES, any host; the background hands it to the parser that owns
  // the host (parsers/common.js registerPlaceholderHandler) and ignores the
  // rest. Two kinds, the explicit ones first so a page's lazy iframes can
  // never crowd them out of the budget:
  // - explicit facades: [data-embed-url], <lite-vimeo videoid [videohash]>;
  // - lazy iframes (lazysizes/lozad data-src, WP Rocket data-lazy-src, a3
  //   data-src over a data: gif) whose src is not loaded yet (absent, empty,
  //   about:blank, a data: placeholder), and only when the URL looks like a
  //   player (embed/player/video/…): lazy AD slots use the same attribute.
  // NEVER a consent-gated lazy iframe (Klaro's data-name, OneTrust's
  // optanon-category, Cookiebot's data-cookieconsent, Complianz,
  // Didomi, iubenda, Usercentrics, CookieYes markers, and their own
  // data-cookieblock-src / data-src-cmplz / uc-src / suppressedsrc
  // attributes, which are not read at all): the site is holding that player
  // back until the user agrees to the provider, and fetching the provider's
  // config ourselves would contact it anyway.
  // Each URL once per document page, MAX_PLACEHOLDERS in all; re-checked
  // (debounced) when nodes are added, for carousels and embeds built later.
  const EXPLICIT_PLACEHOLDER_SEL = '[data-embed-url], lite-vimeo[videoid]';
  const LAZY_IFRAME_SEL = 'iframe[data-src], iframe[data-lazy-src]';
  const MAX_PLACEHOLDERS = 24;
  const MAX_PLACEHOLDER_URL = 2048;
  const PLAYER_URL_HINT_RE = /embed|player|video|watch|clip|podcast|episode/i;
  const CONSENT_ATTRS = ['data-name', 'data-cookieconsent', 'data-category', 'data-service',
    'data-vendor', 'data-cookieyes', 'data-iub-purposes', 'data-usercentrics', 'data-cmplz-target'];
  const CONSENT_CLASS_RE = /(?:^|\s)(?:optanon-category-|cmplz-|_iub_cs|cookieblock|cookieconsent|uc-embedding|brlbs)/i;
  const sentPlaceholders = new Set();
  let placeholderPage = '';
  let placeholderTimer = null;

  function consentGated(el) {
    for (let i = 0; i < CONSENT_ATTRS.length; i++) {
      if (el.hasAttribute(CONSENT_ATTRS[i])) return true;
    }
    return CONSENT_CLASS_RE.test(el.getAttribute('class') || '');
  }

  function absolutePlaceholderUrl(value) {
    if (!value || value.length > MAX_PLACEHOLDER_URL) return null;
    try {
      const u = new URL(value, document.baseURI);
      return (u.protocol === 'https:' || u.protocol === 'http:') ? u.href : null;
    } catch (e) {
      return null;
    }
  }

  // A site's own facade, not a consent tool's: the consent markers are read on
  // lazy iframes only (generic names like data-category can sit on a card).
  function explicitPlaceholderUrl(el) {
    if (el.tagName === 'LITE-VIMEO') {
      const id = el.getAttribute('videoid') || '';
      if (!/^\d{3,12}$/.test(id)) return null;
      const hash = el.getAttribute('videohash') || '';
      return `https://player.vimeo.com/video/${id}` + (/^[0-9a-f]{6,20}$/i.test(hash) ? `?h=${hash}` : '');
    }
    return absolutePlaceholderUrl(el.getAttribute('data-embed-url'));
  }

  function lazyIframeUrl(el) {
    if (consentGated(el)) return null;
    const live = (el.getAttribute('src') || '').trim();
    if (live && !/^(?:about:blank|data:)/i.test(live)) return null; // loading its player already
    const url = absolutePlaceholderUrl(el.getAttribute('data-src') || el.getAttribute('data-lazy-src'));
    if (!url) return null;
    let shape = '';
    try {
      const u = new URL(url);
      shape = u.hostname + u.pathname;
    } catch (e) {
      return null;
    }
    return PLAYER_URL_HINT_RE.test(shape) ? url : null;
  }

  function reportPlaceholders() {
    placeholderTimer = null;
    // An SPA's next article lives in the same document: its own budget, and
    // its placeholders are its own (the background claims per page).
    const here = location.href.split('#')[0];
    if (here !== placeholderPage) {
      placeholderPage = here;
      sentPlaceholders.clear();
    }
    const urls = [];
    const take = (sel, urlOf) => {
      let els;
      try { els = document.querySelectorAll(sel); } catch (e) { return; }
      for (let i = 0; i < els.length && sentPlaceholders.size < MAX_PLACEHOLDERS; i++) {
        let url = null;
        try { url = urlOf(els[i]); } catch (e) { url = null; }
        if (!url || sentPlaceholders.has(url)) continue;
        sentPlaceholders.add(url);
        urls.push(url);
      }
    };
    take(EXPLICIT_PLACEHOLDER_SEL, explicitPlaceholderUrl);
    take(LAZY_IFRAME_SEL, lazyIframeUrl);
    if (!urls.length) return;
    clog('[cs] player placeholders:', urls.length);
    try {
      const p = browser.runtime.sendMessage({ kind: 'player-placeholders', urls });
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* background not reachable */ }
  }

  function schedulePlaceholders() {
    if (placeholderTimer) return;
    if (sentPlaceholders.size >= MAX_PLACEHOLDERS && location.href.split('#')[0] === placeholderPage) return;
    placeholderTimer = setTimeout(reportPlaceholders, 500);
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
      schedulePlaceholders();
    });
  } else {
    scrapeEmbeddedMedia();
    schedulePlaceholders();
  }
  window.addEventListener('load', () => {
    scan(document.documentElement);
    scrapeEmbeddedMedia();
    schedulePlaceholders();
  });

  // ---- Host-page captions for embedded players (TOP frame only) --------
  // A hosted player embed can carry NO usable title of its own — JW Platform
  // on lasprovincias.es titles the iframe document with the upload filename
  // (`6aaaf5204d3859992c30a380.mp4`) — while the clip's description sits
  // right here in the host page, in the live-blog paragraph before the
  // <figure> that holds the iframe. The iframe is cross-origin, so only this
  // frame can read it. For every http(s) <iframe> report (src → caption) to
  // the background, which substitutes it ONLY for a capture from that frame
  // whose own title is filename-like (requests.js withFrameCaption) — a real
  // embed title is never replaced, so this cannot mislabel anything that was
  // titled correctly before. Caption sources, in order: the iframe's own
  // title/aria-label/data-title, the enclosing <figure>'s <figcaption>, then
  // the nearest preceding text block (p / heading / figcaption) within a few
  // ancestors — the shape of a caption written above an embed. Bounded and
  // debounced; iframes injected later (a cookie wall unwrapping them, lazy
  // embeds) are picked up by the MutationObserver below.
  const MAX_FRAME_CAPTIONS = 60;
  const CAPTION_MIN_CHARS = 12;
  const sentFrameCaptions = new Map(); // src -> caption last sent
  let frameCaptionTimer = null;

  function cleanCaption(s) {
    return (typeof s === 'string') ? s.replace(/\s+/g, ' ').trim().slice(0, 200) : '';
  }

  function captionForFrame(el) {
    let t;
    try {
      t = cleanCaption(el.getAttribute('title')) || cleanCaption(el.getAttribute('aria-label'))
        || cleanCaption(el.getAttribute('data-title'));
      if (t) return t;
      const fig = el.closest ? el.closest('figure') : null;
      const cap = fig && fig.querySelector ? fig.querySelector('figcaption') : null;
      if (cap) { t = cleanCaption(cap.textContent); if (t.length >= CAPTION_MIN_CHARS) return t; }
      let node = el;
      for (let depth = 0; depth < 5 && node && node !== document.body; depth++) {
        let sib = node.previousElementSibling;
        for (let hops = 0; sib && hops < 3; hops++) {
          if (/^(?:P|H[1-6]|FIGCAPTION)$/.test(sib.tagName || '')) {
            t = cleanCaption(sib.textContent);
            if (t.length >= CAPTION_MIN_CHARS) return t;
          }
          sib = sib.previousElementSibling;
        }
        node = node.parentElement;
      }
    } catch (e) { /* a detached or exotic element — no caption */ }
    return '';
  }

  function reportFrameCaptions() {
    frameCaptionTimer = null;
    let frames;
    try { frames = document.querySelectorAll('iframe[src]'); } catch (e) { return; }
    const items = [];
    for (let i = 0; i < frames.length && items.length < MAX_FRAME_CAPTIONS; i++) {
      const el = frames[i];
      let src;
      try { src = el.src; } catch (e) { continue; }
      if (!src || !/^https?:/i.test(src)) continue;
      const title = captionForFrame(el);
      if (!title || sentFrameCaptions.get(src) === title) continue;
      sentFrameCaptions.set(src, title);
      items.push({ src, title });
    }
    if (!items.length) return;
    clog('[cs] frame captions:', items.length);
    try {
      const p = browser.runtime.sendMessage({ kind: 'frame-captions', items });
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* background not reachable */ }
  }

  function scheduleFrameCaptions() {
    if (window !== window.top) return;
    if (!frameCaptionTimer) frameCaptionTimer = setTimeout(reportFrameCaptions, 300);
  }

  function touchesIframe(node) {
    try {
      if (!node || node.nodeType !== 1) return false;
      if (node.tagName === 'IFRAME') return true;
      return !!(node.querySelector && node.querySelector('iframe'));
    } catch (e) { return false; }
  }

  if (window === window.top) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', scheduleFrameCaptions);
    } else {
      scheduleFrameCaptions();
    }
    window.addEventListener('load', scheduleFrameCaptions);
  }

  // Watch for DOM changes
  const mo = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === 'childList') {
        m.addedNodes.forEach(scan);
        if (m.addedNodes.length) schedulePlaceholders();
        if (window === window.top) {
          for (const n of m.addedNodes) { if (touchesIframe(n)) { scheduleFrameCaptions(); break; } }
        }
      } else if (m.type === 'attributes') {
        const t = m.target;
        if (m.attributeName === 'src' && t.tagName === 'IFRAME') scheduleFrameCaptions();
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