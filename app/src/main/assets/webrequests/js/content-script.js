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
  const CARD_LABELLED_SEL = 'a[aria-label], a[title], button[aria-label], button[title], '
    + '[role="button"][aria-label], [role="link"][aria-label], figcaption';
  const CARD_HEADING_SEL = 'h1, h2, h3, h4, h5, h6, [role="heading"]';
  const PLAYER_ROOT_SEL = '.video-js, .jwplayer, .plyr';
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

  // The clip's title in its card, or ''. holdsOther(node) says when an
  // ancestor already holds another clip.
  const cardTitleFor = (el, holdsOther) => {
    const start = cardStart(el);
    if (start === el) {
      const own = ownCardLabel(el);
      if (own) return own;
    }
    let node = start.parentElement;
    for (let level = 0; level < CARD_MAX_LEVELS && node && node !== document.body; level++) {
      if (holdsOther(node)) return '';
      // A labelled link/button WRAPPING the clip names it directly.
      if (node.matches('a, button, [role="button"], [role="link"]')) {
        const wrap = ownCardLabel(node);
        if (wrap) return wrap;
      }
      // Otherwise a labelled sibling in the card. Several candidates (a title
      // link beside "Like"/"Share" that slipped the control filter) → the
      // longest, since a clip title outruns a control verb.
      let best = '';
      const cands = node.querySelectorAll(CARD_LABELLED_SEL);
      for (let i = 0; i < cands.length; i++) {
        const c = cands[i];
        if (!cardCandidate(c)) continue;
        const t = c.tagName === 'FIGCAPTION' ? usableCardLabel(c.textContent) : ownCardLabel(c);
        if (t.length > best.length) best = t;
      }
      if (best) return best;
      // Then the card's own heading, the first in document order.
      const heads = node.querySelectorAll(CARD_HEADING_SEL);
      for (let i = 0; i < heads.length; i++) {
        if (!cardCandidate(heads[i])) continue;
        const t = usableCardLabel(heads[i].textContent);
        if (t) return t;
      }
      node = node.parentElement;
    }
    return '';
  };

  // The card's picture, for a clip that has no poster of its own and no frame
  // to decode (an <audio>): the first visible http(s) <img> in the card,
  // icons skipped. Same walk and stop rule as the title.
  const cardImageFor = (el, holdsOther) => {
    let node = cardStart(el).parentElement;
    for (let level = 0; level < CARD_MAX_LEVELS && node && node !== document.body; level++) {
      if (holdsOther(node)) return '';
      const imgs = node.querySelectorAll('img');
      for (let i = 0; i < imgs.length; i++) {
        const im = imgs[i];
        if (!cardCandidate(im)) continue;
        const w = im.naturalWidth || parseInt(im.getAttribute('width') || '', 10) || 0;
        if (w && w < CARD_IMAGE_MIN_PX) continue;
        const u = httpUrl(im.currentSrc || im.getAttribute('src') || '');
        if (u) return u;
      }
      node = node.parentElement;
    }
    return '';
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
      playerTitle = player ? player.name : '';
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
  const MEDIA_URL_RE = /\.(?:mp4|m4v|mov|m3u8|m3u|mpd|webm|mkv|m4a|mp3|aac|flac|wav|opus|weba|oga|ogg)(?:[?#]|$)/i;

  // A media URL's metadata travels WITH its report, so the background needs no
  // get-page-metadata round trip for it. That round trip is answered on the
  // page's main thread and loses its 300 ms race while the page is still
  // loading: on higgsfield.ai/explore the clips reported during hydration took
  // 318-414 ms to answer and landed titled with their CDN hash, while the same
  // query answered in 11-31 ms a few seconds later. Built here, in a timer
  // task with no deadline, the answer is the one the responder would give.
  function metadataFor(batch) {
    if (!collectMediaMeta) return null;
    let out = null;
    for (let i = 0; i < batch.length; i++) {
      const url = batch[i];
      if (!mediaQueued.has(url) && !MEDIA_URL_RE.test(url)) continue;
      let m = null;
      try {
        m = collectMediaMeta(url);
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

  function queue(url, fromMediaEl) {
    if (!url || seen.has(url)) return;
    if (!/^https?:/i.test(url)) return;
    seen.add(url);
    if (fromMediaEl) mediaQueued.add(url);
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
    const mediaParent = source.parentElement && /^(?:VIDEO|AUDIO)$/.test(source.parentElement.tagName)
      ? source.parentElement : null;
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

  // ---- Click-to-load player placeholders --------------------------------
  // A page can show a clip as a picture with a play button and load the
  // player iframe only on a tap (vozpopuli.com: <a data-embed-url=
  // "https://geo.dailymotion.com/player/<cfg>.html?video=<id>">), or hold a
  // lazy <iframe data-src> with no src yet. No player exists until then, so
  // nothing reaches the wire. Report the player URL each one DECLARES, any
  // host; the background hands it to the parser that owns the host
  // (parsers/common.js registerPlaceholderHandler) and ignores the rest.
  // Each URL once per document, MAX_PLACEHOLDERS in all; re-checked (debounced)
  // when nodes are added, for carousels and embeds built after load.
  const PLACEHOLDER_SEL = '[data-embed-url],iframe[data-src]';
  const MAX_PLACEHOLDERS = 24;
  const sentPlaceholders = new Set();
  let placeholderPage = '';
  let placeholderTimer = null;

  function placeholderUrlOf(el) {
    let value = el.getAttribute('data-embed-url');
    if (!value && el.tagName === 'IFRAME') {
      const live = el.getAttribute('src');
      if (live && !/^about:blank/i.test(live)) return null; // loading its player already
      value = el.getAttribute('data-src');
    }
    if (!value) return null;
    try {
      const u = new URL(value, document.baseURI);
      return (u.protocol === 'https:' || u.protocol === 'http:') ? u.href : null;
    } catch (e) {
      return null;
    }
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
    let els;
    try { els = document.querySelectorAll(PLACEHOLDER_SEL); } catch (e) { return; }
    const urls = [];
    for (let i = 0; i < els.length && sentPlaceholders.size < MAX_PLACEHOLDERS; i++) {
      const url = placeholderUrlOf(els[i]);
      if (!url || sentPlaceholders.has(url)) continue;
      sentPlaceholders.add(url);
      urls.push(url);
    }
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