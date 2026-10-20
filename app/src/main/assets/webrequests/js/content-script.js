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
// The responder block's reader of a page's JSON (jsonClips below), handed to
// the scanner for the inline-script scrape. Null until the block has run.
let collectJsonClips = null;
// Whether a label is one the page gives to several different things
// (sharedLabel below), handed to the scanner for the iframe captions. Null
// until the block has run.
let sharedLabelOf = null;
// Whether a short label is made only of the page's own class-name words
// (developerLabel below), handed to the scanner the same way.
let developerLabelOf = null;

// What a page's own JSON said about a clip URL the scrape found in it:
// { title, story, poster } (see jsonClips). Read by the metadata builder for
// the scrape's report and for a wire capture of the same URL later.
const jsonClipMeta = new Map();
const MAX_JSON_CLIP_META = 500;
function rememberJsonClip(url, meta) {
  if (jsonClipMeta.has(url)) jsonClipMeta.delete(url);
  jsonClipMeta.set(url, meta);
  while (jsonClipMeta.size > MAX_JSON_CLIP_META) {
    jsonClipMeta.delete(jsonClipMeta.keys().next().value);
  }
}

// ---- Shadow DOM -------------------------------------------------------------
// A web-component player keeps its <video> in a shadow root, where
// document.querySelectorAll never looks: NYT's front page plays every clip in
// a <nyt-betamax> component, and not one was reported, nor could the one
// playing be tied to its card (device audit 2026-10-20). The scanner records
// every shadow root it finds (open ones, and closed ones too through
// Firefox's extension-only openOrClosedShadowRoot) so the scan, the element
// binding and the card walk reach into them. Hosts are looked for among
// autonomous custom elements (a tag with a hyphen), the shape every player
// component has; a built-in element hosting a root is not looked at.
const knownShadowRoots = new Set();
const MAX_SHADOW_ROOTS = 300;
const shadowRootOf = (el) => {
  try {
    return el.openOrClosedShadowRoot || el.shadowRoot || null;
  } catch (_) {
    return null;
  }
};
// The parent a walk climbs to: the element's parent, or the host of the
// shadow root it sits at the top of.
const parentOf = (node) => {
  if (!node) return null;
  if (node.parentElement) return node.parentElement;
  const p = node.parentNode;
  return p && p.nodeType === 11 && p.host ? p.host : null;
};
const liveShadowRoots = () => {
  const out = [];
  for (const r of knownShadowRoots) {
    if (r.host && r.host.isConnected) out.push(r);
    else knownShadowRoots.delete(r);
  }
  return out;
};
// Whether `node` holds `inner`, the shadow roots between them included.
const holdsAcrossShadow = (node, inner) => {
  let n = inner;
  for (let i = 0; i < 200 && n; i++) {
    if (n === node) return true;
    n = parentOf(n);
  }
  return false;
};
// Every element matching `sel` under `root` (document, an element or a
// shadow root), the known shadow roots below it included.
const queryDeep = (root, sel) => {
  const out = Array.from(root.querySelectorAll(sel));
  if (!knownShadowRoots.size) return out;
  for (const r of liveShadowRoots()) {
    if (root.nodeType === 9 || holdsAcrossShadow(root, r.host)) {
      const found = r.querySelectorAll(sel);
      for (let i = 0; i < found.length; i++) out.push(found[i]);
    }
  }
  return out;
};
// The shadow roots under `root` not seen before (nested ones too), each
// handed to onRoot once.
function sweepShadowRoots(root, onRoot) {
  if (knownShadowRoots.size >= MAX_SHADOW_ROOTS || !root || !root.querySelectorAll) return;
  let els;
  try {
    els = root.querySelectorAll('*');
  } catch (_) {
    return;
  }
  for (let i = 0; i < els.length && i < 30000; i++) {
    const el = els[i];
    if (el.tagName.indexOf('-') < 0) continue;
    const r = shadowRootOf(el);
    if (!r || knownShadowRoots.has(r)) continue;
    knownShadowRoots.add(r);
    if (onRoot) onRoot(r);
    sweepShadowRoots(r, onRoot);
    if (knownShadowRoots.size >= MAX_SHADOW_ROOTS) return;
  }
}

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

  // The same file under a refined query: same origin and path, both with a
  // query, every parameter of one in the other with the same value. A player
  // appends its own parameters once it takes the source (as.com's podcast
  // player adds &dist=… to the <audio> it was handed), so the URL the bridge
  // read and the element's src name one file. requests.js
  // sameFileQueryRefined is the same rule.
  const refinedUrl = (a, b) => {
    let x;
    let y;
    try {
      x = new URL(a, location.href);
      y = new URL(b, location.href);
    } catch (_) {
      return false;
    }
    if (x.origin !== y.origin || x.pathname !== y.pathname) return false;
    const px = Array.from(x.searchParams);
    const py = Array.from(y.searchParams);
    if (!px.length || !py.length) return false;
    const small = px.length <= py.length ? px : py;
    const big = px.length <= py.length ? y.searchParams : x.searchParams;
    for (const [k, v] of small) {
      if (big.getAll(k).indexOf(v) < 0) return false;
    }
    return true;
  };

  // The URLs an element plays: its src, currentSrc and <source> children.
  const elementUrls = (el) => {
    const out = [];
    if (el.currentSrc) out.push(el.currentSrc);
    if (el.getAttribute('src')) out.push(el.src);
    const sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
    for (let j = 0; j < sources.length; j++) {
      if (sources[j].src) out.push(sources[j].src);
    }
    return out;
  };

  // The <audio>/<video> element whose src/currentSrc/<source> resolves to the
  // captured URL, or null (the URL was played without a DOM element). An
  // exact match first; then the one element playing the same file under a
  // refined query (two such elements: no answer, the URL is ambiguous).
  const findBoundMedia = (mediaUrl) => {
    const els = allMediaElements();
    for (let i = 0; i < els.length; i++) {
      const el = els[i];
      if (sameUrl(el.currentSrc, mediaUrl) || sameUrl(el.src, mediaUrl)) return el;
      const sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
      for (let j = 0; j < sources.length; j++) {
        if (sameUrl(sources[j].src, mediaUrl)) return el;
      }
    }
    let refined = null;
    for (let i = 0; i < els.length; i++) {
      if (!elementUrls(els[i]).some((u) => refinedUrl(u, mediaUrl))) continue;
      if (refined) return null;
      refined = els[i];
    }
    return refined;
  };

  // Whether an <audio>/<video> element plays the captured URL (src attribute,
  // currentSrc or a <source> child), its refined-query copy included.
  const elementPlays = (el, mediaUrl) => {
    if (el.getAttribute('src') && sameUrl(el.src, mediaUrl)) return true;
    if (el.currentSrc && sameUrl(el.currentSrc, mediaUrl)) return true;
    const sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
    for (let j = 0; j < sources.length; j++) {
      if (sameUrl(sources[j].src, mediaUrl)) return true;
    }
    return elementUrls(el).some((u) => refinedUrl(u, mediaUrl));
  };

  // Whether `node` holds a media element other than `el` that is not a second
  // copy of the same clip. A card can carry two elements for one clip
  // (lasprovincias.es renders each podcast's <audio> once in the card and
  // again in a cookie-wall template that unwraps beside it), and that copy
  // must not read as a second clip. An element with no source counts as
  // another clip: it is a player that has not loaded yet.
  const holdsAnotherClip = (node, el, mediaUrl) => {
    const els = queryDeep(node, 'audio, video');
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

  // The last segment of a URL's path as the URL parser writes it, or ''.
  // A cheap first test when looking for one file among a page's many URLs:
  // a URL that does not hold it is not that file, and finding that out is a
  // substring test instead of a parse.
  const lastSegment = (u) => {
    try {
      return new URL(u, location.href).pathname.split('/').pop() || '';
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
  // The title is the bound element's own aria-label/title, else what its
  // card shows as a title (cardTitleFor): walk up at most CARD_MAX_LEVELS
  // ancestors, and stop the moment an ancestor holds another clip — past
  // that point a label belongs to a container of several clips, and handing
  // it to one would be the first-element bug again (the julianc.net poster
  // case). A small control's label ("Play", "Mute video", "More options") is
  // what it does, not a title, and is skipped by its place and size, not its
  // words. A label is taken verbatim: the site's own wording ("View X") is
  // not ours to strip.
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
  // A caption: the clip's own description, as HTML and schema.org mark it.
  const CARD_CAPTION_SEL = 'figcaption, [itemprop="caption"]';
  // What the markup DECLARES a title: a heading, a schema.org headline or the
  // name of a creative work. A title the markup does not declare (a styled
  // <span>, a <p> whose class only its stylesheet reads) is found by how the
  // page shows it: see textUnitsIn and prominent.
  const CARD_HEADING_SEL = 'h1, h2, h3, h4, h5, h6, [role="heading"], [itemprop="headline"], [itemprop="name"]';
  // schema.org items whose `name` is not a clip's title.
  const NOT_TITLE_ITEM_RE = /(?:Person|Organization|Organisation|Place|Brand|Rating|Offer|ImageObject|WebSite|WebPage|BreadcrumbList|ListItem)$/i;
  const NOT_TITLE_ITEMPROP_RE = /^(?:author|creator|publisher|contributor|copyrightHolder|sourceOrganization|provider|producer|director|actor|image|thumbnail|logo)$/i;
  // An image alt is a title only when it reads like one (several words, not
  // a file name: CNN's are "bad audience still 1.jpg").
  const ALT_FILE_RE = /\.(?:jpe?g|png|gif|webp|avif|svg|bmp|tiff?|heic)\b/i;
  // How far up a clip's story link may sit (CNN: video, its container, the
  // interactive-video, the media, the media wrapper, then the link).
  const STORY_LINK_MAX_LEVELS = 6;
  // Who made the picture, as schema.org marks it: never what it shows.
  const CREDIT_ITEMPROP_RE = /^(?:creditText|copyrightHolder|copyrightNotice|author)$/i;
  const PLAYER_ROOT_SEL = '.video-js, .jwplayer, .plyr';
  const SECTIONING_SEL = 'section, article, aside, nav, header, footer';
  // A node whose text runs past this is an article body or a page section,
  // not a card: a heading AFTER the clip there is a subheading.
  const CARD_TEXT_MAX = 400;
  const CARD_IMAGE_MIN_PX = 64;

  // ── What a text IS, read from its shape, in any language ──
  // No word list: a list knows only the languages and the naming habits
  // someone added after an audit, and grows with every one. What a page
  // shows beside a title has shapes, a font and a place that say what it
  // is in any language; the rules below read those (CLAUDE.md "the card
  // walk reads structure, not words").
  const wordsOf = (t) => String(t || '').match(/[\p{L}\p{N}]+/gu) || [];
  // A word folded for comparing: lower case, no accents.
  const foldWord = (w) => w.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  // A carousel position: two numbers joined by a slash or by a short word,
  // the first no larger than the second ("2 de 10", "2/10", "Slide 3 of 8",
  // "3 из 8"), alone or in brackets after a title ("Vertical <title> (2 de
  // 10)", La Vanguardia). The joining word is the page's language's; its
  // length and the numbers around it are what count.
  const POSITION_RE = /^(?:\p{L}{1,12}\s+)?(\d{1,3})\s*(?:\p{L}{1,4}\.?|\/)\s*(\d{1,3})$/u;
  const POSITION_TAIL_RE = /\s*[(\[]\s*(\d{1,3})\s*(?:\p{L}{1,4}\.?|\/)\s*(\d{1,3})\s*[)\]]\s*$/u;
  const isPositionLabel = (t) => {
    const m = POSITION_RE.exec(String(t || '').trim());
    return !!m && +m[1] >= 1 && +m[1] <= +m[2];
  };
  const stripPositionTail = (t) => {
    const m = POSITION_TAIL_RE.exec(t);
    return m && +m[1] >= 1 && +m[1] <= +m[2] ? t.slice(0, m.index) : t;
  };
  // What a card shows BESIDE its title: a duration or a clock ("1:25"), a
  // count or a size with at most a short unit ("2 min", "1.2K", "34 seg"), a
  // date written mostly in digits, a bullet.
  const COUNT_RE = /^[\d.,:]+\s*\p{L}{0,4}\.?$/u;
  const metadataText = (t) => {
    const s = String(t || '').replace(/[•·|]/g, ' ').replace(/\s+/g, ' ').trim();
    if (!/\p{L}/u.test(s) || COUNT_RE.test(s)) return true;
    const digits = (s.match(/\d/g) || []).length;
    return digits >= 4 && digits >= (s.match(/\p{L}/gu) || []).length;
  };
  // A credit, in any language: a © notice, a social handle, or names joined
  // by slashes ("BFMTV / Reuters", "@eperdumence via Instagram / Cormac
  // Delaney": CNN's loop captions, which named its clips).
  const creditText = (t) => {
    const s = String(t || '');
    return s.indexOf('©') >= 0 || /(?:^|\s)@[\p{L}\p{N}_.]{2,}/u.test(s)
      || (/\S\s+\/\s+\S/.test(s) && wordsOf(s).length <= 8);
  };

  // ── How the page SHOWS a text ──
  // Rendered or not, its size, weight and case: read once per element per
  // second (one answer, one scan batch) from the computed style.
  let shownFacts = new WeakMap();
  let shownFactsAt = 0;
  const factsOf = (el) => {
    const now = Date.now();
    if (now - shownFactsAt > 1000) {
      shownFacts = new WeakMap();
      shownFactsAt = now;
    }
    let f = shownFacts.get(el);
    if (f) return f;
    f = { rendered: true, size: 0, weight: 400, upper: false, spaced: false };
    try {
      if (typeof el.checkVisibility === 'function') {
        f.rendered = el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true });
      } else {
        f.rendered = el.getClientRects().length > 0;
      }
      const cs = getComputedStyle(el);
      f.size = parseFloat(cs.fontSize) || 0;
      f.weight = parseInt(cs.fontWeight, 10) || (cs.fontWeight === 'bold' ? 700 : 400);
      f.upper = cs.textTransform === 'uppercase';
      f.spaced = f.size > 0 && parseFloat(cs.letterSpacing) > 0.05 * f.size;
    } catch (_) {
      // A detached or exotic element: read as plain text.
    }
    shownFacts.set(el, f);
    return f;
  };
  // The page's reading size: its body's font size (a 62.5% root the body
  // never sets back reads as the browser's 16 px).
  let baseFont = { at: 0, px: 16 };
  const basePx = () => {
    const now = Date.now();
    if (now - baseFont.at > 1000) {
      let px = 16;
      try {
        px = parseFloat(getComputedStyle(document.body || document.documentElement).fontSize) || 16;
      } catch (_) {
        px = 16;
      }
      baseFont = { at: now, px: px < 12 ? 16 : px };
    }
    return baseFont.px;
  };
  // Shown larger or bolder than the page's text: how a page marks a title
  // it does not declare (CNN's headline <span>, 18-20 px against a 16 px page).
  const prominent = (f) => f.size >= 1.12 * basePx() || (f.weight >= 600 && f.size >= 0.95 * basePx());
  // Fine print: a credit, a byline, a timestamp (CNN's credits are 10 px).
  const finePrint = (f) => f.size > 0 && f.size < 0.8 * basePx();
  // A kicker, an eyebrow, a label over a title: a few words in capitals (the
  // stylesheet's or the text's own) or letter-spaced ("• VIDEO", "OPINION",
  // "10.000 PASOS EN CEUTA").
  const allCapsText = (t) => /\p{Lu}[^\p{Ll}]*\p{Lu}/u.test(t) && t === t.toUpperCase();
  const kickerLike = (f, t) => wordsOf(t).length <= 4 && (f.upper || f.spaced || allCapsText(t));

  // ── The page's own vocabulary ──
  // The words its class names and ids are made of (BEM, kebab, snake and
  // camelCase split; a token with a digit, a CSS-module hash, skipped), with
  // how many elements use each. A short label made only of these words is a
  // developer's name for a ROLE, in whatever language the developer names
  // things: the Daily Mail's poster <img class="posterImage…" alt="Hero video
  // poster"> sits in a <div class="player… vjs-video-container"> in a <div
  // class="hero…">; Vimeo's player thumbnail is "video thumbnail"; a site's
  // icons are "icon". Three words at most: a CMS can write a story's tags
  // into class names (WordPress' tag-taylor-swift), and a longer text made
  // of them is that story's, not a role's.
  const DEV_VOCAB_MAX_ELEMENTS = 4000;
  let devWords = { at: 0, page: '', counts: null };
  const vocabKey = (w) => {
    const f = foldWord(w);
    return f.length > 5 ? f.slice(0, 5) : f;
  };
  const devVocabulary = () => {
    const now = Date.now();
    if (devWords.counts && devWords.page === location.href && now - devWords.at < 5000) return devWords.counts;
    const counts = new Map();
    let els = [];
    try {
      els = document.querySelectorAll('[class], [id]');
    } catch (_) {
      els = [];
    }
    for (let i = 0; i < els.length && i < DEV_VOCAB_MAX_ELEMENTS; i++) {
      const raw = (els[i].getAttribute('class') || '') + ' ' + (els[i].getAttribute('id') || '');
      const mine = new Set();
      for (const tok of raw.split(/[\s_\-.:/]+/)) {
        if (!tok || /\d/.test(tok)) continue;
        for (const w of tok.replace(/(\p{Ll})(\p{Lu})/gu, '$1 $2').split(' ')) {
          if (w.length >= 3) mine.add(vocabKey(w));
        }
      }
      for (const k of mine) counts.set(k, (counts.get(k) || 0) + 1);
    }
    devWords = { at: now, page: location.href, counts };
    return counts;
  };
  const developerLabel = (t) => {
    const ws = wordsOf(t).filter((w) => w.length >= 3);
    if (!ws.length || ws.length > 3 || wordsOf(t).length > 4) return false;
    const vocab = devVocabulary();
    return ws.every((w) => vocab.has(vocabKey(w)));
  };

  // ── Where a link points, read as words ──
  // A story's URL often spells its headline ("…/ola-violencia-juvenil-sacude-
  // centro-milan.html"). A short text a link carries that shares no word with
  // that slug is not the story's name, whatever the language: La Vanguardia's
  // "Leer la noticia relacionada", a "Ver noticia", a "Read more". A URL with
  // no such slug (ids, hashes) says nothing. Only for what reads like a
  // control's wording — a link's label of four words at most, a plain link
  // text of two — never for a text the page shows as a title: headlines are
  // rewritten after the URL is fixed (NYT's "Trumpism Is Backfiring" links to
  // …/ezra-klein-podcast-chrystia-freeland.html).
  const SLUG_MIN_WORDS = 3;
  const SLUG_CHECK_MAX_WORDS = 4;
  const SLUG_CHECK_MAX_TEXT_WORDS = 2;
  const slugWordsOf = (url) => {
    let path = '';
    try {
      path = decodeURIComponent(new URL(url, location.href).pathname);
    } catch (_) {
      return null;
    }
    let best = null;
    for (const seg of path.split('/')) {
      const ws = (seg.match(/\p{L}{3,}/gu) || []).map(foldWord);
      if (ws.length >= SLUG_MIN_WORDS && (!best || ws.length > best.length)) best = ws;
    }
    return best;
  };
  const sameStem = (a, b) => a === b || (a.length >= 5 && b.length >= 5 && a.slice(0, 5) === b.slice(0, 5));
  const slugDenies = (text, url, maxWords) => {
    if (!url || wordsOf(text).length > (maxWords || SLUG_CHECK_MAX_WORDS)) return false;
    const ws = (String(text).match(/\p{L}{3,}/gu) || []).map(foldWord);
    if (!ws.length) return false;
    const slug = slugWordsOf(url);
    if (!slug) return false;
    return !ws.some((w) => slug.some((s) => sameStem(w, s)));
  };

  const countVideos = (root) => queryDeep(root, 'video').length;
  // Every <video> and <audio> of the page, those in shadow roots included.
  const allMediaElements = () => queryDeep(document, 'audio, video');
  // requests.js' urlIsStandaloneAudio, by extension.
  const AUDIO_URL_RE = /^https?:\/\/[^/]+\/[^?#]*\.(?:mp3|m4a|aac|wav|weba|opus|flac|oga|midi?)(?:[?#]|$)/i;

  // A label that can serve as a clip title, or ''.
  const usableCardLabel = (raw) => {
    // A carousel's position after the title is not part of it: La
    // Vanguardia labels each card "Vertical <title> (2 de 10)".
    const t = stripPositionTail((raw || '').replace(/\s+/g, ' ').trim()).trim();
    if (t.length < 3 || t.length > 200) return '';
    if (isPositionLabel(t) || metadataText(t)) return '';
    if (/^https?:\/\//i.test(t)) return '';
    // A translation key the page never translated ("player.download_episode").
    if (/^[\w-]+(?:\.[\w-]+)+$/.test(t)) return '';
    if (developerLabel(t)) return '';
    return t;
  };

  const ownCardLabel = (el) => ownLabel(el, usableCardLabel(el.getAttribute('aria-label')))
    || ownLabel(el, usableCardLabel(el.getAttribute('title')));

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

  // ---- A clip's own entry in the JSON a page keeps beside it ---------------
  // One rule for every JSON that lists a clip: a list of clips in an
  // ancestor's data-* attribute (the Daily Mail's shorts carousel:
  // [{"renditions":[{"url":…mp4}],"productUrl":…,"title":" "}]), or a
  // framework's state in a <script type="application/json"> (NBC's
  // __NEXT_DATA__: {"headline":{"primary":…},"videoAssets":[{"publicUrl":…}]}).
  // Find the object holding the clip's URL, climb to the clip's own object —
  // through a list of RENDITIONS of it, never into a list of other clips —
  // and read what names it there: a title (a string, or {primary|basic|…}),
  // else the story it links to, whose headline the page shows elsewhere.
  // page-state-bridge.js names page-world state with the same climb
  // (clipMetaFromPath there).
  const JSON_TITLE_KEYS = ['title', 'headline', 'headlines', 'videoTitle', 'video_title', 'episodeTitle',
    'episode_title', 'displayTitle', 'display_title', 'name'];
  const JSON_TITLE_INNER = ['primary', 'basic', 'main', 'default', 'text', 'title', 'value', 'web'];
  const JSON_STORY_KEYS = ['productUrl', 'articleUrl', 'article_url', 'storyUrl', 'story_url', 'permalink',
    'link', 'shareUrl', 'share_url', 'canonicalUrl', 'canonical_url', 'pageUrl', 'page_url', 'webUrl'];
  const JSON_IMAGE_KEYS = ['poster', 'posterUrl', 'poster_url', 'thumbnail', 'thumbnailUrl', 'thumbnail_url',
    'thumb', 'image', 'imageUrl', 'image_url', 'cover', 'coverUrl', 'primaryImage', 'promoImage',
    'promo_image', 'artwork', 'preview'];
  // Keys only a clip has, never one of its renditions: an entry that has
  // one is a clip of its own, and the list holding it is a list of clips.
  const JSON_CLIP_KEY_RE = /^(?:guid|uuid|slug|poster\w*|thumbnail\w*|thumb|image\w*|cover\w*|description|summary|headlines?|title|name|caption|permalink|share_?url)$/i;
  // Keys a rendition has: its size, encoding, quality.
  const JSON_RENDITION_KEY_RE = /^(?:height|width|bitrate|bandwidth|avg_?bitrate|quality|format|type|mime_?type|container|video_?container|codecs?|label|size|resolution|frame_?height|frame_?width|asset_?type|stream_?type|profile|res|fps)$/i;
  // A list whose name says renditions of one clip.
  const JSON_RENDITION_LIST_RE = /(?:sources|renditions|qualities|formats|variants|levels|assets|streams|files|encodings|bitrates|playlist_?urls)$/i;
  const JSON_MEDIA_VALUE_RE = /\.(?:mp4|m4v|mov|webm|mkv|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|flac|wav)(?:[?#]|$)/i;
  const JSON_IMAGE_VALUE_RE = /\.(?:jpe?g|png|gif|webp|avif|svg|bmp|heic)(?:[?#]|$)/i;
  const JSON_QUALITY_RE = /^(?:\d{3,4}p?|[48]k|hd|sd|uhd|fhd|auto|source|original|default|high|low|medium|mobile|desktop)$/i;

  const usableJsonTitle = (v) => {
    if (typeof v !== 'string') return '';
    const t = v.replace(/\s+/g, ' ').trim();
    if (t.length < 3 || t.length > 300 || !/\p{L}\p{L}/u.test(t)) return '';
    if (/^(?:https?:)?\/\//i.test(t) || JSON_QUALITY_RE.test(t) || isPositionLabel(t) || developerLabel(t)) return '';
    if (JSON_MEDIA_VALUE_RE.test(t) || JSON_IMAGE_VALUE_RE.test(t)) return '';
    if (!/\s/.test(t) && t.length >= 12 && (t.match(/\d/g) || []).length >= 3) return '';
    return t;
  };
  const jsonTitleOf = (o) => {
    for (const k of JSON_TITLE_KEYS) {
      const v = o[k];
      if (typeof v === 'string') {
        const t = usableJsonTitle(v);
        // A bare `name` is a title only in words: a player's or a file's own
        // name is one token ("heroPlayer").
        if (t && (k !== 'name' || /\s/.test(t))) return t;
      } else if (v && typeof v === 'object' && !Array.isArray(v)) {
        for (const inner of JSON_TITLE_INNER) {
          const t = usableJsonTitle(v[inner]);
          if (t) return t;
        }
      }
    }
    return '';
  };
  // A URL a JSON value carries: the string itself, or {url|src|href|…} one
  // or two levels in (NBC: "primaryImage":{"url":{"primary":…}}).
  const jsonUrlOf = (v, depth = 0) => {
    if (typeof v === 'string') return v;
    if (!v || typeof v !== 'object' || Array.isArray(v) || depth > 1) return '';
    for (const k of ['url', 'src', 'href', 'primary', 'original', 'default', 'basic']) {
      const s = jsonUrlOf(v[k], depth + 1);
      if (s) return s;
    }
    return '';
  };
  const jsonAbsUrl = (s) => {
    if (typeof s !== 'string' || !/^(?:https?:)?\/\/|^\/[^/]/i.test(s)) return '';
    try {
      return new URL(s, location.href).href;
    } catch (_) {
      return '';
    }
  };
  const jsonImageOf = (o) => {
    for (const k of JSON_IMAGE_KEYS) {
      const u = jsonAbsUrl(jsonUrlOf(o[k]));
      if (u && !JSON_MEDIA_VALUE_RE.test(u)) return u;
    }
    return '';
  };
  const jsonStoryOf = (o) => {
    for (const k of JSON_STORY_KEYS) {
      const u = jsonAbsUrl(jsonUrlOf(o[k]));
      if (u && !JSON_MEDIA_VALUE_RE.test(u) && !JSON_IMAGE_VALUE_RE.test(u)) return u;
    }
    return '';
  };
  const jsonHasKey = (o, re) => {
    for (const k in o) {
      if (!re.test(k)) continue;
      const v = o[k];
      if (v !== null && v !== undefined && v !== '' && v !== false) return true;
    }
    return false;
  };

  // Sibling keys naming a FORMAT or quality of the same clip (an hls beside a
  // cmaf, hd beside sd), not another clip.
  const JSON_FORMAT_KEY_RE = /^(?:hls|dash|cmaf|mp4|webm|mp3|m4a|aac|progressive|adaptive|hd|sd|uhd|fhd|high|low|medium|mobile|desktop|default|main|fallback|backup|source|sources|renditions?|formats?|variants?|qualities|streams?|files?|assets?|encodings?|playlists?|manifests?|media|video|audio|urls?|\d{3,4}p?|[48]k)$/i;
  // Whether a JSON value holds a media URL within two levels.
  const jsonHoldsMedia = (v, depth, budget) => {
    if (budget.n-- <= 0 || depth > 2) return false;
    if (typeof v === 'string') return JSON_MEDIA_VALUE_RE.test(v) && /^(?:https?:)?\/\/|^\/[^/]/i.test(v);
    if (!v || typeof v !== 'object') return false;
    if (Array.isArray(v)) {
      for (let i = 0; i < v.length && i < 20; i++) {
        if (jsonHoldsMedia(v[i], depth + 1, budget)) return true;
      }
      return false;
    }
    for (const k in v) {
      if (jsonHoldsMedia(v[k], depth + 1, budget)) return true;
    }
    return false;
  };
  // Whether an object holds ANOTHER clip beside the branch `fromKey`: a
  // sibling, not named as a format of the same clip, with media of its own.
  // A climb stops there ({"title":"Top videos","hero":{…},"promo":{…}}: the
  // section's title names neither clip, and the two are not one).
  const jsonHoldsOtherClip = (o, fromKey) => {
    const budget = { n: 400 };
    for (const k in o) {
      if (k === fromKey || JSON_FORMAT_KEY_RE.test(k)) continue;
      const v = o[k];
      if (v && typeof v === 'object' && jsonHoldsMedia(v, 0, budget)) return true;
    }
    return false;
  };

  // Walks a parsed JSON value, calling onMedia(url, path) for each string
  // that is a media URL (absolute or root-relative). path holds { node, key }
  // from the root down to the container holding the URL. Bounded by budget.n
  // (nodes) and depth; returns early when onMedia returns true.
  const walkJsonMedia = (root, onMedia, budget) => {
    const path = [];
    let done = false;
    const visit = (v, key, depth) => {
      if (done || budget.n-- <= 0 || depth > 40) return;
      if (typeof v === 'string') {
        if (v.length > 8 && v.length < 4000 && JSON_MEDIA_VALUE_RE.test(v)
          && /^(?:https?:)?\/\/|^\/[^/]/i.test(v) && onMedia(v, path)) {
          done = true;
        }
        return;
      }
      if (!v || typeof v !== 'object') return;
      path.push({ node: v, key });
      if (Array.isArray(v)) {
        for (let i = 0; i < v.length && i < 2000 && !done; i++) visit(v[i], i, depth + 1);
      } else {
        for (const k in v) {
          if (done) break;
          visit(v[k], k, depth + 1);
        }
      }
      path.pop();
    };
    visit(root, null, 0);
  };

  // From the container holding a clip URL up to the clip's own object: what
  // names it ({ title, story, poster }) and the object that stands for the
  // clip (anchor), which every rendition of it shares. An array is climbed
  // out of only when it lists renditions (its entries carry a rendition's
  // keys, or its name says so); an entry with a clip's own keys is a clip in
  // a list of clips, and the list's owner names none of them. Nor is an
  // object holding another clip beside this one climbed through. The anchor
  // is the object the title was read from, else the clip's own object (the
  // first one that is not a rendition), never a parent above it: two
  // untitled clips under one wrapper are two clips.
  const jsonRenditionList = (path, j) => {
    const from = j + 1 < path.length ? path[j + 1].node : null;
    const key = String(path[j].key);
    if (from && !Array.isArray(from)) {
      if (jsonHasKey(from, JSON_CLIP_KEY_RE)) return false;
      return jsonHasKey(from, JSON_RENDITION_KEY_RE) || JSON_RENDITION_LIST_RE.test(key);
    }
    return JSON_RENDITION_LIST_RE.test(key);
  };
  const climbJsonClip = (path) => {
    const info = { title: '', story: '', poster: '', anchor: null };
    let clipObject = null;
    let objects = 0;
    for (let i = path.length - 1; i >= 0 && objects < 6; i--) {
      const node = path[i].node;
      if (Array.isArray(node)) {
        if (!jsonRenditionList(path, i)) break;
        continue;
      }
      if (objects > 0 && jsonHoldsOtherClip(node, path[i + 1].key)) break;
      objects++;
      const inRenditions = i > 0 && Array.isArray(path[i - 1].node) && jsonRenditionList(path, i - 1);
      if (!clipObject && !inRenditions) clipObject = node;
      if (!info.poster) info.poster = jsonImageOf(node);
      if (!info.story) info.story = jsonStoryOf(node);
      const t = jsonTitleOf(node);
      if (t) {
        info.title = t;
        info.anchor = node;
        break;
      }
    }
    if (!info.anchor) info.anchor = clipObject || (path.length ? path[path.length - 1].node : null);
    return info;
  };

  // The clip `urls` play, in a parsed JSON value, or null.
  const jsonClipFor = (root, urls) => {
    let found = null;
    const bare = (u) => u.split(/[?#]/)[0];
    const wanted = urls.map(bare);
    walkJsonMedia(root, (v, path) => {
      const abs = jsonAbsUrl(v);
      if (!abs || !wanted.includes(bare(abs))) return false;
      found = climbJsonClip(path);
      return true;
    }, { n: 50000 });
    return found && (found.title || found.story || found.poster) ? found : null;
  };

  // Every clip a parsed JSON value lists, its renditions grouped:
  // [{ urls, meta: { title, story, poster } }], in the order the JSON lists
  // them. A URL no clip object claims is a clip of its own.
  const jsonClips = (root, budget) => {
    const byAnchor = new Map();
    const out = [];
    walkJsonMedia(root, (v, path) => {
      const abs = jsonAbsUrl(v);
      if (!abs || !/^https?:/i.test(abs)) return false;
      const meta = climbJsonClip(path);
      const anchor = meta.anchor || path[path.length - 1].node;
      let clip = byAnchor.get(anchor);
      if (!clip) {
        clip = { urls: [], meta: { title: meta.title, story: meta.story, poster: meta.poster } };
        byAnchor.set(anchor, clip);
        out.push(clip);
      }
      if (!clip.urls.includes(abs)) clip.urls.push(abs);
      return false;
    }, budget || { n: 200000 });
    return out;
  };
  collectJsonClips = jsonClips;

  // A list of clips in a data-* attribute of the element or an ancestor
  // that names the element's clip (the Daily Mail's shorts carousel keeps
  // them on the carousel's <div data-videos>), or null.
  const parsedAttrs = new Map();
  const parseAttrJson = (v) => {
    if (parsedAttrs.has(v)) return parsedAttrs.get(v);
    let o = null;
    try {
      o = JSON.parse(v);
    } catch (_) {
      o = null;
    }
    parsedAttrs.set(v, o);
    if (parsedAttrs.size > 40) parsedAttrs.delete(parsedAttrs.keys().next().value);
    return o;
  };
  const tailOf = (u) => {
    const s = String(u || '').split(/[?#]/)[0];
    const t = s.slice(s.lastIndexOf('/') + 1);
    return t.length >= 6 ? t : '';
  };
  const ancestorJsonClip = (el, mediaUrl) => {
    const urls = [mediaUrl].concat(mediaUrlsOf(el)).filter(Boolean);
    const tails = urls.map(tailOf).filter(Boolean);
    if (!tails.length) return null;
    let node = el;
    for (let level = 0; level < 10 && node && node !== document.documentElement; level++) {
      const attrs = node.attributes;
      for (let i = 0; attrs && i < attrs.length && i < 40; i++) {
        const a = attrs[i];
        if (!/^data-/i.test(a.name)) continue;
        const v = a.value || '';
        if (v.length > 400000 || !/^\s*[[{]/.test(v) || !tails.some((t) => v.indexOf(t) >= 0)) continue;
        const parsed = parseAttrJson(v);
        const info = parsed && typeof parsed === 'object' ? jsonClipFor(parsed, urls) : null;
        if (info) return info;
      }
      node = parentOf(node);
    }
    return null;
  };

  // The headline the page shows for a story a clip's JSON links to (the
  // Daily Mail's shorts link each clip to its article, whose card on the
  // front page carries the headline), or ''.
  let storyLinks = null;
  let storyLinksAt = 0;
  const storyTitleFor = (url) => {
    let target = '';
    try {
      target = new URL(url, location.href).href.split('#')[0];
    } catch (_) {
      return '';
    }
    if (!target || target === location.href.split('#')[0]) return '';
    const now = Date.now();
    if (!storyLinks || now - storyLinksAt > 2000) {
      storyLinks = new Map();
      storyLinksAt = now;
      const links = document.querySelectorAll('a[href]');
      for (let i = 0; i < links.length && i < 5000; i++) {
        const t = linkTarget(links[i]);
        if (!t) continue;
        const list = storyLinks.get(t);
        if (list) list.push(links[i]);
        else storyLinks.set(t, [links[i]]);
      }
    }
    for (const a of storyLinks.get(target) || []) {
      if (!cardCandidate(a)) continue;
      const t = storyLinkTitle(a);
      if (t) return t;
    }
    return '';
  };

  // A poster's file name, when it reads as words, names a clip nothing else
  // on a page of several clips names: antena3.com's stories carousel shows
  // each clip as a poster under a "Ver ahora" button, and the posters are
  // perro-robot-que-ayuda-agricultores_68.jpg and the like. Words only: a
  // token with a digit is dropped, and so is a word three or more of the
  // page's picture files share (a template's "thumb", "poster", "vertical",
  // "portada", in whatever language it names its files) or one the page's
  // class names use (see developerLabel); fewer than three words
  // left is no name (a hash or "thumb-1024x576" never is).
  const FILE_WORD_SHARED = 3;
  const fileWordsOf = (u) => {
    let name = '';
    try {
      name = decodeURIComponent(new URL(u, location.href).pathname.split('/').pop() || '');
    } catch (_) {
      return [];
    }
    return name.replace(/\.[a-z0-9]{2,5}$/i, '').split(/[-_+.\s]+/).filter((w) => /^\p{L}{2,}$/u.test(w));
  };
  // word → how many different picture files of the page carry it.
  let fileWords = { at: 0, page: '', counts: null };
  const pictureFileWords = () => {
    const now = Date.now();
    if (fileWords.counts && fileWords.page === location.href && now - fileWords.at < 2000) return fileWords.counts;
    const files = new Set();
    let els = [];
    try {
      els = queryDeep(document, 'img, video[poster], source[srcset]');
    } catch (_) {
      els = [];
    }
    for (let i = 0; i < els.length && files.size < 600; i++) {
      const el = els[i];
      let u = '';
      if (el.tagName === 'VIDEO') u = el.poster;
      else if (el.tagName === 'IMG') u = el.currentSrc || el.src;
      else u = (el.getAttribute('srcset') || '').trim().split(/\s+/)[0];
      const p = u ? pathOf(u) : '';
      if (p) files.add(p);
    }
    const counts = new Map();
    for (const f of files) {
      for (const w of new Set(fileWordsOf(f).map(foldWord))) counts.set(w, (counts.get(w) || 0) + 1);
    }
    fileWords = { at: now, page: location.href, counts };
    return counts;
  };
  const posterSlugTitle = (posterUrl) => {
    if (!posterUrl) return '';
    const shared = pictureFileWords();
    const vocab = devVocabulary();
    const words = fileWordsOf(posterUrl).filter((w) => (shared.get(foldWord(w)) || 0) < FILE_WORD_SHARED
      && !vocab.has(vocabKey(w)));
    if (words.length < 3 || words.join('').length < 12) return '';
    const text = words.join(' ');
    return text.charAt(0).toUpperCase() + text.slice(1);
  };

  // The URLs a media element plays: its src, currentSrc and <source>s.
  const mediaUrlsOf = (el) => {
    const out = [];
    const add = (u) => {
      if (u && /^https?:/i.test(u) && !out.includes(u)) out.push(u);
    };
    try {
      if (el.getAttribute('src')) add(el.src);
      add(el.currentSrc);
      const sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
      for (let i = 0; i < sources.length; i++) add(sources[i].src);
    } catch (_) {}
    return out;
  };

  // A config title is the clip's only when no OTHER clip on the page carries
  // it too: cope.es gives its seven vertical clips one data-layer
  // {"title":"prueba de vídeo vertical"} (an editor's test placeholder), and
  // all seven took it over the heading in their cards (device audit
  // 2026-10-20). A second element for the same file is the same clip.
  const distinctPlayerConfigTitle = (el) => {
    const title = playerConfigTitle(el);
    if (!title) return '';
    const mine = mediaUrlsOf(el);
    const els = allMediaElements();
    for (let i = 0; i < els.length && i < 200; i++) {
      const other = els[i];
      if (other === el || playerConfigTitle(other) !== title) continue;
      const theirs = mediaUrlsOf(other);
      if (!theirs.some((u) => mine.includes(u))) return '';
    }
    return title;
  };

  // Block-level elements: text on either side of one reads as two phrases.
  const BLOCK_TEXT_TAGS = /^(?:P|DIV|LI|H[1-6]|SECTION|ARTICLE|HEADER|FOOTER|FIGCAPTION|BLOCKQUOTE|DT|DD|TD|TH|BR|UL|OL)$/;
  // Never text a reader takes for a name.
  const UNIT_SKIP_SEL = 'script, style, noscript, template, svg, video, audio, iframe, object, canvas, select, textarea, input';
  // A control: its text says what it DOES, not what the card is.
  const CONTROL_SEL = 'button, summary, [role="button"], [role="switch"], [role="tab"], [role="menuitem"], '
    + '[role="menuitemcheckbox"], [role="menuitemradio"], [role="option"], [role="checkbox"], [role="radio"], '
    + '[role="slider"], [role="spinbutton"], [aria-pressed]';

  // Whether `n`, inside a text the page shows as one (`unitFacts` is how
  // that text is shown), is an ASIDE to it: a credit (schema.org's, or one
  // by its shape), a duration, a note or a credit in smaller type (CNN's
  // <span class="image__credit"> in its gallery captions, 10 px in a 16 px
  // caption), a part the page keeps hidden, or — unless keepKickers — a
  // kicker in capitals inside a headline box ("Opinion" in NYT's) or a word
  // the page repeats in every card ("Video", CNN).
  const asideIn = (n, unitFacts, keepKickers) => {
    if (CREDIT_ITEMPROP_RE.test(n.getAttribute('itemprop') || '')) return true;
    const t = (n.textContent || '').replace(/\s+/g, ' ').trim();
    if (!t || t.length > 200) return false;
    if (metadataText(t) || creditText(t)) return true;
    const f = factsOf(n);
    if (unitFacts.rendered && !f.rendered) return true;
    const words = wordsOf(t).length;
    if (words <= 6 && f.size > 0 && unitFacts.size > 0 && f.size < 0.85 * unitFacts.size) return true;
    return !keepKickers && words <= 4 && (kickerLike(f, t) || sharedText(n, t));
  };

  // The text of `root` a reader takes as one phrase: its asides left out,
  // text in separate blocks kept apart ("Opinion" in one <div> and the
  // headline in a <p> beside it read as "OpinionSilicon Valley…").
  const textOf = (root, keepKickers) => {
    let out = '';
    let lastBlock = null;
    const rootFacts = factsOf(root);
    let walker;
    try {
      walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
        acceptNode: (n) => {
          if (n.nodeType === 3) return NodeFilter.FILTER_ACCEPT;
          if (n.matches(UNIT_SKIP_SEL) || asideIn(n, rootFacts, keepKickers)) return NodeFilter.FILTER_REJECT;
          return NodeFilter.FILTER_SKIP;
        },
      });
    } catch (_) {
      return (root.textContent || '').replace(/\s+/g, ' ').trim();
    }
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      let block = n.parentElement;
      while (block && block !== root && !BLOCK_TEXT_TAGS.test(block.tagName)) block = block.parentElement;
      if (lastBlock !== null && block !== lastBlock) out += ' ';
      lastBlock = block;
      out += n.nodeValue;
      if (out.length > 2000) break;
    }
    return out.replace(/\s+/g, ' ').trim();
  };

  // True when the markup declares `el` a title (see CARD_HEADING_SEL).
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
    return false;
  };

  // The text a title element carries (a microdata <meta> carries it in
  // content), a kicker inside it left out when the rest says something.
  const titleText = (el) => (el.tagName === 'META'
    ? usableCardLabel(el.getAttribute('content'))
    : usableCardLabel(textOf(el)) || usableCardLabel(textOf(el, true)));

  // The text an element's aria-labelledby points at, or ''.
  const labelledByText = (el) => {
    const ids = (el.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean);
    let out = '';
    for (let i = 0; i < ids.length && i < 4; i++) {
      const ref = document.getElementById(ids[i]);
      if (ref) out += ' ' + textOf(ref);
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

  // Hidden by the page itself: the hidden attribute or an inline display:none
  // (as.com's audio player keeps a "En directo" title element in every card,
  // shown only while a live stream plays).
  const hiddenNode = (c) => !!(c.hidden
    || (c.style && (c.style.display === 'none' || c.style.visibility === 'hidden')));

  // A card label or heading that a walk may take: visible, and not part of a
  // player's own chrome.
  const cardCandidate = (c) => c.getAttribute('aria-hidden') !== 'true' && !hiddenNode(c)
    && !playerRootOf(c);

  // The length of the text a reader sees in `root`, counted up to `limit`:
  // no script, style or template text, no SVG titles, no media element's
  // fallback text and nothing hidden. textContent counts all of those, and
  // a card with an inline JSON-LD script or an SVG icon read as an article
  // body: elespanol.com's video card holds 250 characters a reader sees and
  // its heading after the clip was refused as a subheading (device audit
  // 2026-10-20).
  const INVISIBLE_TEXT_SEL = 'script, style, noscript, template, svg, video, audio, [hidden]';
  const visibleTextLength = (root, limit) => {
    let n = 0;
    let walker;
    try {
      walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
        acceptNode: (x) => {
          if (x.nodeType === 3) return NodeFilter.FILTER_ACCEPT;
          if (x.matches(INVISIBLE_TEXT_SEL) || hiddenNode(x)) return NodeFilter.FILTER_REJECT;
          return NodeFilter.FILTER_SKIP;
        },
      });
    } catch (_) {
      return (root.textContent || '').length;
    }
    for (let t = walker.nextNode(); t; t = walker.nextNode()) {
      const s = t.nodeValue.trim();
      if (s) n += s.length + 1;
      if (n > limit) return n;
    }
    return n;
  };

  // The title a story link gives its story: its own label, else the text in
  // it the page shows as the title (CNN's twin link holds a "Video" label,
  // the headline and "1:30"; a headline and its description), else its plain
  // text. A short text whose words the story's URL does not spell is not the
  // story's name (slugDenies).
  const storyLinkTitle = (a) => {
    const target = linkTarget(a);
    const own = ownCardLabel(a);
    if (own && !slugDenies(own, target)) return own;
    const picked = pickUnits(textUnitsIn(a, null), null, true);
    return picked.title || picked.plain || picked.hidden;
  };

  const linkTarget = (a) => {
    const href = a.getAttribute('href') || '';
    if (!href || href.startsWith('#') || /^javascript:/i.test(href)) return '';
    return (a.href || '').split('#')[0];
  };

  // ── A label the page puts on several different things names none ──
  // The same words on links to three or more different pages ("Ver ahora" on
  // every story card of antena3.com), on the controls of three or more
  // different clips (a mute button in each of La Vanguardia's slides), on
  // three or more different clips' own elements (higgsfield.ai's
  // <video aria-label="Genjutsu example">) or as the alt of three or more
  // different pictures are a control's wording or a placeholder, in any
  // language: counting finds them where a word list has to know them first.
  // Two is not enough: a story's portrait and landscape cuts (Reuters, NBC)
  // carry the same title on two different clips. Iframe titles count from
  // two, compared with their numbers left out ("Embedded content 1" and
  // "Embedded content 2" are a generator's, so are two "YouTube video
  // player"s); so do a control's, "Slide 1" … "Slide 9" being one label.
  // What is "different": a link's target, a clip's file, a picture's file,
  // an iframe's src, and for a control the clip or picture it sits with — so
  // a carousel that clones its slides, and a story shown twice with the same
  // picture, count once. Only LABELS are judged (aria-label, title, a link's
  // or a control's text, an alt): content the page writes as a name —
  // headings, captions, JSON and config titles, declared metadata — is not.
  // A page with one or two clips is read by structure instead (where a
  // control sits and how big it is, the URL a link spells: cardLabelFor,
  // slugDenies). One census per kind per page per second, built on
  // the first question about that kind and read by every clip and picture
  // of the batch: a picture's alt never pays for the page's links.
  const LABEL_CENSUS_SEL = {
    link: 'a[href], [role="link"]',
    control: 'button, summary, [role="button"], [role="switch"], [role="tab"], [role="menuitem"], '
      + '[role="checkbox"], [role="option"], input[type="button"], input[type="submit"], '
      + 'a[href^="#"], a[href^="javascript:" i], [role="link"]:not(a)',
    media: 'video[aria-label], video[title], audio[aria-label], audio[title]',
    picture: 'img[alt]',
    frame: 'iframe[title], iframe[aria-label]',
  };
  const LABEL_CENSUS_MAX = 5000;
  const LABEL_CENSUS_TTL_MS = 1000;
  const SHARED_LABEL_MIN = 3;
  const SHARED_FRAME_TITLE_MIN = 2;
  // Elements of one label looked at to count its subjects, at most.
  const SHARED_LABEL_PROBE = 40;
  let labelCensus = { at: 0, page: '', kinds: new Map() };

  const labelKindOf = (el) => {
    const tag = el.tagName;
    if (tag === 'VIDEO' || tag === 'AUDIO') return 'media';
    if (tag === 'IMG') return 'picture';
    if (tag === 'IFRAME') return 'frame';
    if ((tag === 'A' || el.getAttribute('role') === 'link') && linkTarget(el)) return 'link';
    return 'control';
  };

  // The census key of `text` as a label of that kind: case and spacing
  // folded, a carousel position left out, a control's and an iframe's
  // numbers too.
  const labelKey = (text, kind) => {
    let t = stripPositionTail(String(text || '').replace(/\s+/g, ' ').trim()).trim().toLowerCase();
    if (kind === 'control' || kind === 'frame') t = t.replace(/\d+/g, '#');
    return t.length >= 2 && t.length <= 200 ? t : '';
  };

  // The texts an element of that kind is labelled with.
  const labelTextsOf = (el, kind) => {
    const out = [el.getAttribute('aria-label'), el.getAttribute('title')];
    if (kind === 'picture') return [el.getAttribute('alt')];
    if (kind === 'link' || kind === 'control') {
      const text = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (text.length <= 200) out.push(text);
      if (el.tagName === 'INPUT') out.push(el.getAttribute('value'));
    }
    return out;
  };

  const mediaSubject = (el) => {
    let u = el.currentSrc || (el.getAttribute('src') ? el.src : '');
    if (!u) {
      const source = el.querySelector ? el.querySelector('source[src]') : null;
      u = source ? source.src : '';
    }
    return u ? 'm:' + pathOf(u) : el;
  };
  const pictureSubject = (el) => {
    const u = el.currentSrc || (el.getAttribute('src') ? el.src : '');
    return u ? 'p:' + pathOf(u) : el;
  };

  // What a labelled element is about: see the block comment.
  const labelSubjectOf = (el, kind) => {
    if (kind === 'link') return linkTarget(el) || el;
    if (kind === 'frame') return (el.getAttribute('src') ? el.src.split('#')[0] : '') || el;
    if (kind === 'media') return mediaSubject(el);
    if (kind === 'picture') return pictureSubject(el);
    let n = parentOf(el);
    for (let level = 0; level < 6 && n && n !== document.body && n !== document.documentElement; level++) {
      const m = n.querySelector ? n.querySelector('video, audio, img') : null;
      if (m) return m.tagName === 'IMG' ? pictureSubject(m) : mediaSubject(m);
      n = parentOf(n);
    }
    return el;
  };

  // label key → { els, shared } for every element of one kind.
  const buildLabelGroups = (kind) => {
    const groups = new Map();
    let els;
    try {
      els = queryDeep(document, LABEL_CENSUS_SEL[kind]);
    } catch (_) {
      els = [];
    }
    for (let i = 0; i < els.length && i < LABEL_CENSUS_MAX; i++) {
      const el = els[i];
      if (labelKindOf(el) !== kind) continue;
      const keys = new Set();
      for (const t of labelTextsOf(el, kind)) {
        const k = labelKey(t, kind);
        if (k) keys.add(k);
      }
      for (const k of keys) {
        let g = groups.get(k);
        if (!g) {
          g = { els: [], shared: null };
          groups.set(k, g);
        }
        g.els.push(el);
      }
    }
    return groups;
  };

  // Whether `text`, as `el`'s label, is one the page puts on several
  // different things of el's kind.
  const sharedLabel = (el, text) => {
    if (!el || !el.tagName || !text) return false;
    const kind = labelKindOf(el);
    const key = labelKey(text, kind);
    if (!key) return false;
    if (labelCensus.page !== location.href || Date.now() - labelCensus.at > LABEL_CENSUS_TTL_MS) {
      labelCensus = { at: Date.now(), page: location.href, kinds: new Map() };
    }
    let groups = labelCensus.kinds.get(kind);
    if (!groups) {
      groups = buildLabelGroups(kind);
      labelCensus.kinds.set(kind, groups);
    }
    const g = groups.get(key);
    if (!g) return false;
    if (g.shared === null) {
      const min = kind === 'frame' ? SHARED_FRAME_TITLE_MIN : SHARED_LABEL_MIN;
      const subjects = new Set();
      for (let i = 0; i < g.els.length && i < SHARED_LABEL_PROBE && subjects.size < min; i++) {
        subjects.add(labelSubjectOf(g.els[i], kind));
      }
      g.shared = subjects.size >= min;
    }
    return g.shared;
  };
  sharedLabelOf = sharedLabel;
  developerLabelOf = developerLabel;

  // `t` when it is a label of `el` the page gives to nothing else, else ''.
  const ownLabel = (el, t) => (t && !sharedLabel(el, t) ? t : '');

  // ── A text the page shows in several cards names none ──
  // The label census, for the words a page SHOWS: the same short text in
  // three or more different cards is a kicker ("Video" over every clip on
  // CNN's front page), a call to action, an agency's credit, a status ("En
  // directo" in each of as.com's podcast cards) — in any language. A
  // different card is a different picture, clip or story (labelSubjectOf,
  // then the story a link points at), so a story listed three times, or a
  // carousel's cloned slide, counts once. One census per page per second,
  // built on the first question.
  const TEXT_CENSUS_MAX = 6000;
  const TEXT_KEY_MAX = 80;
  let textCensus = { at: 0, page: '', groups: null };
  const textKey = (t) => {
    const s = stripPositionTail(String(t || '').replace(/\s+/g, ' ').trim()).trim().toLowerCase();
    return s.length >= 2 && s.length <= TEXT_KEY_MAX ? s : '';
  };
  const buildTextGroups = () => {
    const groups = new Map();
    const seen = new Set();
    let walker;
    try {
      walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
    } catch (_) {
      return groups;
    }
    let n = 0;
    for (let t = walker.nextNode(); t && n < TEXT_CENSUS_MAX; t = walker.nextNode()) {
      if (!/\S/.test(t.nodeValue)) continue;
      const p = t.parentElement;
      if (!p || seen.has(p)) continue;
      seen.add(p);
      n++;
      if (/^(?:SCRIPT|STYLE|NOSCRIPT|TEMPLATE|TITLE|OPTION)$/.test(p.tagName)) continue;
      const k = textKey(p.textContent);
      if (!k) continue;
      let g = groups.get(k);
      if (!g) {
        g = { els: [], shared: null };
        groups.set(k, g);
      }
      g.els.push(p);
    }
    return groups;
  };
  const textSubjectOf = (el) => {
    const s = labelSubjectOf(el, 'control');
    if (s !== el) return s;
    const a = el.closest ? el.closest('a[href]') : null;
    return (a && linkTarget(a)) || el;
  };
  const sharedText = (el, text) => {
    if (!el || !text) return false;
    if (textCensus.page !== location.href || Date.now() - textCensus.at > LABEL_CENSUS_TTL_MS || !textCensus.groups) {
      textCensus = { at: Date.now(), page: location.href, groups: buildTextGroups() };
    }
    for (const key of new Set([textKey(text), textKey(el.textContent)])) {
      const g = key ? textCensus.groups.get(key) : null;
      if (!g) continue;
      if (g.shared === null) {
        const subjects = new Set();
        for (let i = 0; i < g.els.length && i < SHARED_LABEL_PROBE && subjects.size < SHARED_LABEL_MIN; i++) {
          subjects.add(textSubjectOf(g.els[i]));
        }
        g.shared = subjects.size >= SHARED_LABEL_MIN;
      }
      if (g.shared) return true;
    }
    return false;
  };

  // A clip INSIDE a link to its story is a news card's picture link; the
  // card's headline is the OTHER link to the same story beside it (CNN's
  // front page: a media link around the looping <video> and a text link
  // with the headline, both to the article). The picture link's own text is
  // the credit at best. '' when the clip is in no story link, or no twin
  // lies within a card's reach.
  const storyTwinTitleFor = (start, holdsOther) => {
    let wrap = null;
    let n = parentOf(start);
    for (let level = 0; level < STORY_LINK_MAX_LEVELS && n && n !== document.body; level++) {
      if (n.tagName === 'A' && n.hasAttribute('href')) {
        wrap = n;
        break;
      }
      n = parentOf(n);
    }
    // The picture link's own label is a control's wording ("Play: <title>"):
    // the walk takes it only when nothing in the card names the clip.
    if (!wrap || holdsOther(wrap)) return '';
    const target = linkTarget(wrap);
    if (!target) return '';
    let node = parentOf(wrap);
    for (let level = 0; level < 3 && node && node !== document.body; level++) {
      if (holdsOther(node)) return '';
      const links = node.querySelectorAll('a[href]');
      for (let i = 0; i < links.length; i++) {
        const a = links[i];
        if (a === wrap || holdsAcrossShadow(a, start) || linkTarget(a) !== target || !cardCandidate(a)) continue;
        const t = storyLinkTitle(a);
        if (t) return t;
      }
      node = parentOf(node);
    }
    return '';
  };

  // Whether a clip sits inside a link to ANOTHER page: a news card's
  // picture link, so the clip is that story's teaser, not this page's own
  // video (a page with one teaser clip and nothing else playing,
  // lasprovincias.es at the moment its loop was fetched).
  const inStoryLink = (start) => {
    const here = location.href.split('#')[0];
    let n = parentOf(start);
    for (let level = 0; level < STORY_LINK_MAX_LEVELS && n && n !== document.body; level++) {
      if (n.tagName === 'A' && n.hasAttribute('href')) {
        const target = linkTarget(n);
        return !!target && target !== here;
      }
      n = parentOf(n);
    }
    return false;
  };

  // A link to another page that NAMES a story in the clip's card: a title
  // element that is or holds such a link (abc.es' podcast card: <h2><a
  // href="…/nasa-busca-cuevas…">), or a labelled link with no text of its own
  // laid over the card (the Guardian's front page: <a href="…/dream-of-birds"
  // aria-label="Dream of Birds"> beside the looping <video>). A card-sized
  // node only: an article body links to other pages everywhere, and its own
  // clip keeps the page's title. With inStoryLink, what makes a lone clip a
  // teaser of another page (device audit 2026-10-20: both took the site's
  // title).
  const cardStoryLink = (start, holdsOther) => {
    const here = location.href.split('#')[0];
    for (const node of cardAncestors(start)) {
      if (holdsOther(node) || visibleTextLength(node, CARD_TEXT_MAX) > CARD_TEXT_MAX) return false;
      const links = node.querySelectorAll('a[href]');
      for (let i = 0; i < links.length && i < 40; i++) {
        const a = links[i];
        if (!cardCandidate(a)) continue;
        const target = linkTarget(a);
        if (!target || target === here) continue;
        if (!(a.textContent || '').trim()) {
          const own = ownCardLabel(a);
          if (own && !slugDenies(own, target)) return true;
          continue;
        }
        const around = a.parentElement ? a.parentElement.closest(CARD_HEADING_SEL) : null;
        if (around && node.contains(around) && titleElement(around)) return true;
        if (titleElement(a)) return true;
        const inner = a.querySelector(CARD_HEADING_SEL);
        if (inner && titleElement(inner)) return true;
        // A link the page SHOWS as the card's title (larger or bolder than
        // its text), whatever its tag.
        if (pickUnits(textUnitsIn(a, null), null, true, true, null).title) return true;
      }
    }
    return false;
  };

  // The title element of a card-sized node around `el`, or '': the name a
  // player shows for the one clip it plays (COPE's sticky radio player:
  // "Mediodía COPE" over its <audio>). Headings and captions only, never a
  // control's label.
  const ownCardHeadingFor = (el, holdsOther) => {
    const start = cardStart(el);
    for (const node of cardAncestors(start)) {
      if (holdsOther(node) || visibleTextLength(node, CARD_TEXT_MAX) > CARD_TEXT_MAX) return '';
      const head = levelNames(node, start, true).title;
      if (head) return head;
      const cap = cardCaptionFor(node);
      if (cap) return cap;
    }
    return '';
  };

  // A link on this page to the media file itself (a podcast widget's
  // download button), or null: the page presents the file, and the link's
  // card names it. Spreaker's widget names its episode beside a
  // <a class="button_download" href="…/download.mp3"> (elespanol.com, device
  // audit 2026-10-20).
  const linkToFile = (mediaUrl) => {
    const seg = lastSegment(mediaUrl);
    let links;
    try {
      links = queryDeep(document, 'a[href]');
    } catch (_) {
      return null;
    }
    for (let i = 0; i < links.length && i < 3000; i++) {
      const a = links[i];
      const href = a.getAttribute('href') || '';
      if (!href || href.startsWith('#')) continue;
      const abs = a.href || '';
      if (seg && abs.indexOf(seg) < 0) continue;
      if (sameUrl(abs, mediaUrl) || refinedUrl(abs, mediaUrl)) return a;
    }
    return null;
  };

  // The title a clip with none in its own card takes from ANOTHER element on
  // the page showing the same picture: a lightbox player whose slides carry
  // no title, opened from a carousel whose cards do. La Vanguardia's vertical
  // video carousel: each card <article role="button" aria-label="Vertical
  // <title> (2 de 10)"> holds the poster
  // cdn.jwplayer.com/v2/media/<id>/poster.jpg?width=480, and its modal plays
  // each clip under poster.jpg?width=720 beside a mute button and a "read the
  // story" link (device audit 2026-10-20). Same file = same origin and path.
  // Several elements giving different titles: no answer.
  const TWIN_PICTURE_SEL = 'img, video[poster]';
  const posterTwinTitleFor = (poster, el) => {
    const want = pathOf(poster);
    if (!want) return '';
    const seg = lastSegment(poster);
    const own = cardStart(el);
    let cands;
    try {
      cands = queryDeep(document, TWIN_PICTURE_SEL);
    } catch (_) {
      return '';
    }
    let found = '';
    let seenTwins = 0;
    for (let i = 0; i < cands.length && seenTwins < 6; i++) {
      const c = cands[i];
      if (c === el || own.contains(c)) continue;
      const pics = c.tagName === 'VIDEO' ? [c.poster] : [c.currentSrc, c.src];
      if (!pics.some((u) => u && (!seg || u.indexOf(seg) >= 0) && pathOf(u) === want)) continue;
      seenTwins++;
      const holdsOther = c.tagName === 'VIDEO'
        ? (node) => countVideos(node) > 1
        : (node) => holdsOtherImage(node, c) || countVideos(node) > 1;
      const alt = c.tagName === 'IMG' ? ownLabel(c, usableCardLabel(c.getAttribute('alt'))) : '';
      const t = (alt && /\S\s+\S/.test(alt) && !ALT_FILE_RE.test(alt) ? alt : '')
        || cardTitleFor(c, holdsOther, true);
      if (!t) continue;
      if (found && found !== t) return '';
      found = t;
    }
    return found;
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
    let node = parentOf(start);
    let level = 0;
    for (let hops = 0; hops < CARD_MAX_HOPS && node && node !== document.body
      && node !== document.documentElement; hops++) {
      yield node;
      if (addsBeyond(node, from)) {
        level++;
        if (level >= CARD_MAX_LEVELS) return;
      }
      from = node;
      node = parentOf(node);
    }
  }

  // A caption in `node` that describes the clip, read without its asides. A
  // caption that is only a credit is none (CNN's <figcaption> under each loop
  // holds nothing but the credit).
  const cardCaptionFor = (node) => {
    const caps = node.querySelectorAll(CARD_CAPTION_SEL);
    for (let i = 0; i < caps.length; i++) {
      const c = caps[i];
      if (!cardCandidate(c)) continue;
      const t = usableCardLabel(textOf(c));
      if (!t || creditText(t) || sharedText(c, t)) continue;
      // One word, or a few in capitals: an agency's credit ("Reuters", "CNN"
      // under its own loops), not a description.
      const f = factsOf(c);
      if (wordsOf(t).length < 2 || kickerLike(f, t) || (finePrint(f) && wordsOf(t).length <= 6)) continue;
      return t;
    }
    return '';
  };

  // A control's label in `node` (the link or button WRAPPING the clip
  // included), when it names the card rather than an action: the control
  // holds the clip or a picture (the card IS the control: vozpopuli's link
  // around its picture, La Vanguardia's <article role="button">), or lies
  // over most of the clip (higgsfield.ai's <button aria-label="View
  // Countryside Duo"> laid over its card). Any other control's label is
  // the card's when the page holds three clips or more: what a control
  // DOES is written on every card's control and the label census
  // (sharedLabel) has already dropped it ("Activar o silenciar sonido" on
  // each of La Vanguardia's slides), so one no other card repeats is the
  // card's own (a carousel's play buttons labelled with each clip's title).
  // On a page of one or two clips nothing can be counted: only a label
  // longer than an action reads names its card there (as.com's play button
  // "Reproducir <title>"); "Play video" or "Escucha la noticia" on a small
  // button do not. Several → the longest.
  const CONTROL_NAME_MIN_WORDS = 5;
  const coversClip = (c, start) => {
    try {
      const a = c.getBoundingClientRect();
      const b = start.getBoundingClientRect();
      const area = b.width * b.height;
      if (area < 1) return false;
      const ix = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
      const iy = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
      return ix * iy >= 0.5 * area;
    } catch (_) {
      return false;
    }
  };
  const namesCard = (c, start) => holdsAcrossShadow(c, start) || !!c.querySelector('img, picture, video')
    || coversClip(c, start);
  const cardLabelFor = (node, start) => {
    const censusCounts = allMediaElements().length >= SHARED_LABEL_MIN;
    const cands = node.matches('a, button, [role="button"], [role="link"]') ? [node] : [];
    const found = node.querySelectorAll(CARD_LABELLED_SEL);
    for (let i = 0; i < found.length; i++) cands.push(found[i]);
    let best = '';
    for (const c of cands) {
      if (!cardCandidate(c)) continue;
      const t = ownCardLabel(c);
      if (!t || t.length <= best.length) continue;
      if (slugDenies(t, linkTarget(c))) continue;
      if (wordsOf(t).length < CONTROL_NAME_MIN_WORDS && !censusCounts && !namesCard(c, start)) continue;
      best = t;
    }
    return best;
  };

  const cardAltFor = (node) => {
    const imgs = node.querySelectorAll('img[alt]');
    for (let i = 0; i < imgs.length; i++) {
      if (!cardCandidate(imgs[i])) continue;
      const t = ownLabel(imgs[i], usableCardLabel(imgs[i].getAttribute('alt')));
      if (t && /\S\s+\S/.test(t) && !ALT_FILE_RE.test(t)) return t;
    }
    return '';
  };

  // ── The text a card names its clip with ──
  // Read from how the page SHOWS each text, not from its words or its class
  // names: a declared title (a heading) or the text shown larger or bolder
  // than the page's; else the card's plain text; else its control label,
  // its hidden text, its picture's alt. What surrounds a title — a kicker
  // in capitals, a duration, a credit in small type, a control's wording, a
  // word every card repeats — has a shape, a size, a place in any language.
  //
  // The elements in `node` that carry text of their own, each read whole
  // (the inline markup inside it included), in document order; a declared
  // title read whole. A control's subtree is skipped unless it holds the
  // clip: then the control IS the card (bild.de's <a role="button"> around
  // its clip and its caption, antena3's <button> around its poster).
  const UNIT_MAX_ELEMENTS = 1500;
  const ownsText = (el) => {
    for (let c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3 && /\S/.test(c.nodeValue)) return true;
    }
    return false;
  };
  const textUnitsIn = (node, anchor) => {
    const out = [];
    let budget = UNIT_MAX_ELEMENTS;
    const visit = (el, top) => {
      if (budget-- <= 0 || el === anchor) return;
      if (!anchor || !el.contains(anchor)) {
        if (el.matches(UNIT_SKIP_SEL) || (!top && el.matches(CONTROL_SEL))) return;
        // Hidden from readers by the page: a duplicate, a decoration, a
        // credit (NYT's <figcaption aria-hidden="true"> under its loops).
        if (!top && el.getAttribute('aria-hidden') === 'true') return;
        if (titleElement(el) || ownsText(el)) {
          out.push(el);
          return;
        }
      }
      for (let c = el.firstElementChild; c; c = c.nextElementSibling) visit(c, false);
    };
    visit(node, true);
    return out;
  };
  // The declared titles in `node` (a node bigger than a card: only these
  // name a clip there).
  const headingsIn = (node, anchor) => {
    const out = [];
    const heads = node.querySelectorAll(CARD_HEADING_SEL);
    for (let i = 0; i < heads.length; i++) {
      if (!heads[i].contains(anchor) && titleElement(heads[i])) out.push(heads[i]);
    }
    return out;
  };

  // A text unit as a card names a clip with, or null when it names nothing:
  // a player's chrome, a credit, fine print, a word several cards repeat, a
  // short link text its URL contradicts.
  const unitInfo = (el) => {
    const meta = el.tagName === 'META';
    if (!meta && (el.getAttribute('aria-hidden') === 'true' || playerRootOf(el))) return null;
    const heading = titleElement(el) ? el : (el.parentElement ? el.parentElement.closest(CARD_HEADING_SEL) : null);
    const semantic = !!heading && (heading === el || titleElement(heading));
    const text = heading === el ? titleText(el) : usableCardLabel(textOf(el));
    if (!text) return null;
    const f = meta ? { rendered: true, size: basePx() * 1.2, weight: 700, upper: false, spaced: false } : factsOf(el);
    const words = wordsOf(text).length;
    if (creditText(text) || (finePrint(f) && words <= 6) || sharedText(el, text)) return null;
    const shown = semantic || prominent(f);
    const link = !shown && el.closest ? el.closest('a[href]') : null;
    if (link && slugDenies(text, linkTarget(link), SLUG_CHECK_MAX_TEXT_WORDS)) return null;
    return { el, text, f, semantic, words, kicker: !meta && kickerLike(f, text), shown };
  };
  const shownRank = (u) => u.f.size * (u.f.weight >= 600 ? 1.15 : 1) * (u.semantic ? 1.05 : 1);

  // Whether `el` sits in a section, aside or the like inside `node` that
  // does not hold the clip: it belongs to something else ("Most read"). A
  // <header> or <footer> belongs to the article or section it sits in — a
  // card's own header holds its title (El País: <article><header><h3>) — and
  // one that sits in none is the page's.
  const inOtherSection = (el, node, anchor) => {
    let s = el.closest(SECTIONING_SEL);
    if (!s || s === node || !node.contains(s)) return false;
    if (/^(?:HEADER|FOOTER)$/.test(s.tagName)) {
      const owner = s.parentElement ? s.parentElement.closest('section, article, aside, nav') : null;
      if (owner && (owner === node || owner.contains(node))) return false;
      if (owner && node.contains(owner)) s = owner;
    }
    return !s.contains(anchor);
  };

  // Of `units`, what names a clip:
  //   title  — a declared title or a text shown larger or bolder than the
  //            page's: in a card-sized node the most prominent, before the
  //            clip on a tie; in a bigger one (an article, a section) only a
  //            declared one BEFORE the clip, the nearest (a heading after it
  //            there is a subheading, and the first heading of a bigger node
  //            titled an article's clip with "Background" or "Most read");
  //   plain  — in a card-sized node, the text the page shows as plain text,
  //            two words or more, a phrase of three or more first (NPR's
  //            carousel cards, bild.de's and RTVE's clip captions);
  //   hidden — the same among the texts the page keeps hidden (a caption
  //            shown on hover);
  //   kicker — a phrase of three or more words in capitals, the last resort.
  // A heading inside a section, aside or the like that does not hold the
  // clip belongs to something else (unless wholeCard: `node` is one card,
  // its sections its parts — COPE's radio player keeps its programme in an
  // <article> beside the <audio>).
  const pickUnits = (units, anchor, cardSized, wholeCard, node) => {
    const out = { title: '', plain: '', hidden: '', kicker: '' };
    let best = null;
    let before = null;
    let plain = null;
    for (const el of units) {
      if (node && !wholeCard && inOtherSection(el, node, anchor)) continue;
      const u = unitInfo(el);
      if (!u) continue;
      if (!u.f.rendered) {
        if (cardSized && !out.hidden && u.words >= 2) out.hidden = u.text;
        continue;
      }
      if (u.kicker) {
        if (cardSized && !out.kicker && u.words >= 3) out.kicker = u.text;
        continue;
      }
      if (u.shown) {
        if (cardSized) {
          if (!best || shownRank(u) > shownRank(best) * 1.03) best = u;
        } else if (u.semantic && anchor && (el.compareDocumentPosition(anchor) & Node.DOCUMENT_POSITION_FOLLOWING)) {
          before = u;
        }
        continue;
      }
      if (cardSized && u.words >= 2 && (!plain || (u.words >= 3 && plain.words < 3))) plain = u;
    }
    out.title = (best || before || { text: '' }).text;
    out.plain = plain ? plain.text : '';
    return out;
  };

  // What `node` offers to name the clip `start` (see pickUnits).
  const levelNames = (node, start, wholeCard) => {
    const anchor = anchorIn(node, start);
    const cardSized = visibleTextLength(node, CARD_TEXT_MAX) <= CARD_TEXT_MAX;
    return pickUnits(cardSized ? textUnitsIn(node, anchor) : headingsIn(node, anchor), anchor, cardSized, wholeCard, node);
  };

  // The clip's title in its card, or ''. holdsOther(node) says when an
  // ancestor already holds another clip; the walk stops there.
  //
  // Ranked by WHAT names the clip, then by distance: at the nearest
  // ancestor that has one, a title (see pickUnits), else a caption, else
  // the card's plain text. When the card has none of those: a control's
  // label that names the card (cardLabelFor: vozpopuli.com's card link
  // reads "Reproducir: <title>", the <h2> beside it is the title), its
  // hidden text, its picture's alt (several words, not a file name), a
  // kicker of three words or more. A label is taken verbatim: "View
  // Countryside Duo" (higgsfield.ai, nothing else in the card) stays.
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
    let hidden = '';
    let alt = '';
    let kicker = '';
    for (const node of cardAncestors(start)) {
      if (holdsOther(node)) break;
      const named = labelledByText(node) || itemLabel(node);
      if (named) return named;
      const level = levelNames(node, start, false);
      if (level.title) return level.title;
      const cap = cardCaptionFor(node);
      if (cap) return cap;
      if (level.plain) return level.plain;
      if (!label) label = cardLabelFor(node, start);
      if (!hidden) hidden = level.hidden;
      if (!alt) alt = cardAltFor(node);
      if (!kicker) kicker = level.kicker;
    }
    return (noLabel ? '' : label) || hidden || alt || kicker;
  };

  // The accessible name an item around the clip carries — an article, a
  // figure, a group, a list item: NYT's <div role="group" aria-label="Trump
  // Campaigns for Republicans as Turmoil Mounts in Washington video"> around
  // each player. A landmark's name (a section, a navigation) names a part of
  // the page, not a clip; a control's is cardLabelFor's.
  const ITEM_SEL = 'article, figure, li, [role="article"], [role="group"], [role="figure"], [role="listitem"]';
  const itemLabel = (node) => (node.matches(ITEM_SEL) && !node.matches(CONTROL_SEL) && !playerRootOf(node)
    ? ownLabel(node, usableCardLabel(node.getAttribute('aria-label'))) : '');

  // The node standing for `inner` in `node`'s own tree: `inner` itself, or
  // the host of the shadow root `inner` sits in.
  const anchorIn = (node, inner) => {
    let n = inner;
    for (let i = 0; i < 50 && n; i++) {
      if (node === n || node.contains(n)) return n;
      const root = n.getRootNode ? n.getRootNode() : null;
      n = root && root.nodeType === 11 && root.host ? root.host : null;
    }
    return inner;
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
  // content ("…en Fuenlabrada (Madrid).", "La Marina Alta (Alicante)"). So is
  // a © tail, and a "Word: Names" tail after a sentence ("… Foto: EFE",
  // "… Photo: Jane Doe/AFP") — a colon, then only capitalised names: a
  // sentence after a colon ("Update: police arrested two men") is content.
  const CREDIT_AFTER_SENTENCE_RE = /([.!?…])\s*\([^()]{1,120}\)\s*$/;
  const COPYRIGHT_TAIL_RE = /\s*©.{1,80}$/;
  const CREDIT_COLON_TAIL_RE = /([.!?…])\s+[\p{L}]{2,15}\s*:\s*((?:[\p{Lu}\p{N}@][^\s/]*(?:\s*\/\s*|\s+)?){1,6})$/u;
  // An agency dateline before the description ("SAN MARCOS, TEXAS -
  // OCTOBER 15: Democratic U.S. Senate Candidate…", Getty's alts on CNN;
  // "San Diego, California - September 04: …"): a place with a comma, a
  // dash, a word and a day, a colon. The month is whatever word the agency's
  // language uses; the shape around it is what makes it a dateline.
  const DATELINE_RE = /^\p{L}[\p{L} .'’-]{1,40},\s*\p{L}[\p{L} .'’-]{1,40}\s[-–—]\s\p{L}{3,10}\.?\s+\d{1,2}(?:,\s*\d{4})?\s*:\s+/u;
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
      t = t.replace(CREDIT_AFTER_SENTENCE_RE, '$1').replace(COPYRIGHT_TAIL_RE, '')
        .replace(CREDIT_COLON_TAIL_RE, '$1').trim();
    }
    return t.replace(TRAILING_PUNCT_RE, '').trim();
  };

  // Page text that can name a picture, cleaned, or ''.
  const usableImageName = (raw) => {
    const t = cleanImageText(raw);
    if (t.length < 2) return '';
    if (isPositionLabel(t) || developerLabel(t) || creditText(t)) return '';
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
      const full = (c.textContent || '').replace(/\s+/g, ' ').trim();
      const name = usableImageName(textOf(c));
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
    // A label the page puts on other pictures too (sharedLabel: a gallery
    // whose photos all carry the story's alt, a placeholder alt) names this
    // one only when nothing below does: a caption or a card is its own.
    let shared = '';
    let sharedWhole = '';
    // A MARK — an icon, a logo, a glyph — is shown under 64 px on its
    // shorter side (lasprovincias' 16 px menu icons, its 93x14 and 207x24
    // logos), or is an SVG whose size the page does not declare. What the
    // page says about it names it (its alt, title, label: "Newsletters",
    // "Crucigrama", "Maestralia"); the text AROUND it is about something
    // else — a footer's links, an ad's pitch, "Un proyecto con" before a
    // sponsor's logo, a comment's "Responder mensaje" — so it gets no card
    // walk. An SVG of a declared picture size (vozpopuli's 400x400 game
    // glyphs) is a picture like any other. The page's size for an SVG is
    // its attributes: the file's own is whatever its root says.
    const svg = /\.svg(?:[?#]|$)/i.test(imageSrcOf(el));
    const isImg = el.tagName === 'IMG';
    const sizeOf = (attr, natural) => (isImg
      ? (parseInt(el.getAttribute(attr) || '', 10) || (svg ? 0 : natural) || 0) : 0);
    const dims = [sizeOf('width', el.naturalWidth), sizeOf('height', el.naturalHeight)].filter((x) => x > 0);
    const shorter = dims.length ? Math.min(...dims) : 0;
    const markLike = (shorter > 0 && shorter < CARD_IMAGE_MIN_PX) || (svg && !shorter);
    const own = [el.getAttribute('alt'), el.getAttribute('title'), el.getAttribute('aria-label')];
    for (let i = 0; i < own.length && !name; i++) {
      const t = usableImageName(own[i]);
      if (!t) continue;
      if (sharedLabel(el, own[i])) {
        if (!shared) {
          shared = t;
          sharedWhole = own[i].replace(/\s+/g, ' ').trim();
        }
        continue;
      }
      name = t;
      whole = own[i].replace(/\s+/g, ' ').trim();
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
    // A mark gets no walk (see markLike above).
    const walkable = !markLike;
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
    if (!name && shared) {
      name = shared;
      whole = sharedWhole;
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
    // What the page's own JSON says about the clip: a list of clips in a
    // data-* attribute around its element, or the inline JSON the scrape
    // found the URL in (see jsonClips).
    let json = null;
    if (mediaUrl) {
      json = (boundEl ? ancestorJsonClip(boundEl, mediaUrl) : null) || jsonClipMeta.get(mediaUrl) || null;
    }
    if (!poster && json && json.poster) poster = json.poster;
    if (!boundEl && !player && !multiVideo && !poster) {
      const videoEl = document.querySelector('video[poster]');
      poster = (videoEl && videoEl.poster ? videoEl.poster : '') || posterFromPlayerBg(null);
    }
    // The MediaSession describes whatever is playing NOW. On a page with one
    // video that is the clip asked about; on a page with several it is one
    // of them, so it names the captured clip only when the clip's own
    // element is the one playing. NPR's front page autoplays one clip at
    // the top of a carousel of twenty: every clip in the carousel took the
    // playing clip's title over its card's (device audit 2026-10-20).
    const sessionDescribes = !multiVideo || (!!boundEl && !boundEl.paused);
    const ms = sessionDescribes ? readMediaSession() : { title: '', artist: '', artwork: '' };
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
    // (playerTitle: the player API, its config, the page's JSON for it),
    // else its card's label or heading (cardTitle: the card walk, or the
    // headline of the story its JSON links to), else its poster's file name
    // (posterTitle). A teaser clip — one inside a link to another page — is
    // its card's even when it is the only video yet. An <audio> in a list of
    // clips (a news front page's podcast cards) takes its card's picture as
    // well, having no poster and no frame.
    let playerTitle = '';
    let cardTitle = '';
    let posterTitle = '';
    let cardImage = '';
    const moreThanOneVideo = (node) => countVideos(node) > 1;
    const teaser = !multiVideo && boundVideo && !!boundEl
      && (inStoryLink(cardStart(boundEl)) || cardStoryLink(cardStart(boundEl), moreThanOneVideo));
    // A file no element plays but the page links to (a widget's download
    // button): the link stands for it.
    const fileLink = !boundEl && !player && mediaUrl && !json ? linkToFile(mediaUrl) : null;
    if ((multiVideo || teaser) && boundVideo) {
      playerTitle = (player ? player.name : '') || distinctPlayerConfigTitle(boundEl)
        || distinctPlayerConfigTitle(player ? player.root : null) || (json ? json.title : '');
      if (!playerTitle && json && json.story) cardTitle = storyTitleFor(json.story);
      const walkFrom = boundEl || (player ? player.root : null);
      if (!playerTitle && !cardTitle && walkFrom) {
        cardTitle = cardTitleFor(walkFrom, moreThanOneVideo);
      }
      // A slide with no title of its own (a lightbox player) is named where
      // the page shows the same picture with one (the carousel card).
      if (!playerTitle && !cardTitle && multiVideo && poster && walkFrom) {
        cardTitle = posterTwinTitleFor(poster, walkFrom);
      }
      if (!playerTitle && !cardTitle && multiVideo) posterTitle = posterSlugTitle(poster);
    } else if (boundEl && boundEl.tagName === 'AUDIO' && holdsAnotherClip(document, boundEl, mediaUrl)) {
      const otherClip = (node) => holdsAnotherClip(node, boundEl, mediaUrl);
      cardTitle = (json && json.title) || (json && json.story ? storyTitleFor(json.story) : '')
        || cardTitleFor(boundEl, otherClip);
      cardImage = cardImageFor(boundEl, otherClip);
    } else if (boundEl && boundEl.tagName === 'AUDIO') {
      // The page's only <audio>: its player's own card names it when it has
      // a title (a podcast card on a front page, a radio player's programme)
      // the page's title does not already carry; on the episode's own page
      // the page's title names it. Card-sized nodes only.
      const otherClip = (node) => holdsAnotherClip(node, boundEl, mediaUrl);
      const fold = (x) => (x || '').toLowerCase().replace(/\s+/g, ' ').trim();
      const own = (json && json.title) || ownCardHeadingFor(boundEl, otherClip);
      const pageSays = fold(ogp('og:title')) + ' | ' + fold(document.title);
      cardTitle = own && pageSays.indexOf(fold(own)) < 0 ? own : '';
      if (cardTitle) {
        cardImage = cardImageFor(boundEl, (node) => otherClip(node)
          || visibleTextLength(node, CARD_TEXT_MAX) > CARD_TEXT_MAX);
      }
    } else if (!boundEl && !player && json) {
      // A URL the page's JSON lists with no element showing it (NBC's
      // __NEXT_DATA__, a podcast widget's episode): the JSON names it.
      playerTitle = json.title || '';
      if (!playerTitle && json.story) cardTitle = storyTitleFor(json.story);
    } else if (fileLink) {
      // The link's own label is the button's ("Download"); its card names
      // the file.
      cardTitle = cardTitleFor(fileLink, (node) => countVideos(node) > 0
        || queryDeep(node, 'audio').length > 0, true, true);
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
    // So is a URL the page's own JSON names as a clip.
    const audioRole = (audio.role === 'content' || ms.title || (json && (json.title || json.story))
      || (fileLink && cardTitle)) ? 'content' : audio.role;
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
      // A clip's poster file name read as words (posterSlugTitle), the last
      // per-clip name on a multi-clip page.
      posterTitle,
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
    // An empty src attribute reflects as the document's own URL, as on
    // <audio> (reportMediaEl): vozpopuli.com's hidden avatar placeholder
    // <img src=""> reported the page itself as a picture.
    queue(img.currentSrc || (img.getAttribute('src') ? img.src : ''), false, img);
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
    if (!source.src) return;
    const manifest = mediaParent ? manifestSourceOf(mediaParent) : '';
    if (manifest && manifest !== source.src) return;
    queue(source.src, !!mediaParent);
  }

  // A media element listing a manifest among its <source>s plays every
  // quality from it; the files beside it are fallbacks for a browser
  // without HLS/DASH. NYT lists each clip's master, two child playlists and
  // its mp4s: reported one by one, one clip became five rows. The manifest
  // is reported alone; sources that are all files (a resolution ladder, the
  // Daily Mail's 480x270…1024x576 mp4s) are reported as before and folded
  // by the repository.
  const MANIFEST_SOURCE_RE = /\.(?:m3u8|mpd)(?:[?#]|$)/i;
  const MANIFEST_TYPE_RE = /mpegurl|dash\+xml/i;
  function manifestSourceOf(el) {
    let sources;
    try {
      sources = el.querySelectorAll('source');
    } catch (_) {
      return '';
    }
    if (sources.length < 2) return '';
    for (let i = 0; i < sources.length; i++) {
      const src = sources[i].src;
      if (src && (MANIFEST_SOURCE_RE.test(src) || MANIFEST_TYPE_RE.test(sources[i].type || ''))) return src;
    }
    return '';
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
    const manifest = attr ? '' : manifestSourceOf(el);
    const src = manifest || el.currentSrc || (attr ? el.src : '');
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
    if (!root || (root.nodeType !== 1 && root.nodeType !== 11)) return;
    if (root.nodeType === 1) {
      if (root.tagName === 'IMG') reportImg(root);
      else if (root.tagName === 'SOURCE') reportSource(root);
      else if (root.tagName === 'VIDEO' || root.tagName === 'AUDIO') reportMediaEl(root);
      else reportBgImage(root);
    }
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

  // Tier B for JSON: a <script type="application/json"> holding a
  // framework's state (NBC's __NEXT_DATA__) is read AS JSON, so each clip it
  // lists is reported once — its manifest, else its first file; the
  // renditions beside it are left out — and named by its own entry (its
  // headline, or the story it links to: jsonClips). The text scan below
  // reported every URL on its own, titled with the page: NBC's front page
  // became forty-four rows, an HLS master and an mp4 per clip, every one
  // named "NBC News - Breaking Headlines…" (device audit 2026-10-20). A
  // script that does not parse is left to the text scan.
  const JSON_SCRIPT_SEL = 'script[type="application/json"], script[type="text/json"]';
  const JSON_CLIP_PREFER = [/\.m3u8(?:[?#]|$)/i, /\.mpd(?:[?#]|$)/i];
  function scrapeJsonScripts(state) {
    if (!collectJsonClips) return;
    let scripts;
    try {
      scripts = document.querySelectorAll(JSON_SCRIPT_SEL);
    } catch (_) {
      return;
    }
    for (const s of scripts) {
      if (state.emitted >= MAX_SCRIPT_MEDIA || state.budget <= 0) break;
      if (scrapedScripts.has(s)) continue;
      const text = s.textContent || '';
      if (!text || text.length > state.budget) continue;
      if (!/\.(?:mp4|m4v|mov|m3u8|mpd|webm|mkv|m4a|mp3|aac|flac|wav|opus|ogg)\b/i.test(text)) {
        scrapedScripts.add(s);
        continue;
      }
      let data;
      try {
        data = JSON.parse(text);
      } catch (_) {
        continue;
      }
      scrapedScripts.add(s);
      state.budget -= text.length;
      queueJsonClips(data, state);
    }
  }

  // The clips a parsed JSON value lists, queued once each with what the JSON
  // says about them (rememberJsonClip): its manifest preferred, every URL of
  // the clip marked seen so the regex scan below does not report them again.
  function queueJsonClips(data, state) {
    let clips;
    try {
      clips = collectJsonClips(data);
    } catch (_) {
      clips = [];
    }
    for (const clip of clips) {
      if (state.emitted >= MAX_SCRIPT_MEDIA) break;
      if (clip.urls.some((u) => seen.has(u))) continue;
      let best = '';
      for (const re of JSON_CLIP_PREFER) {
        best = clip.urls.find((u) => re.test(u)) || '';
        if (best) break;
      }
      if (!best) best = clip.urls[0];
      for (const u of clip.urls) rememberJsonClip(u, clip.meta);
      queue(best);
      for (const u of clip.urls) seen.add(u);
      state.emitted++;
    }
  }

  // The JSON values a JavaScript script assigns or passes: the text after an
  // `=` or a `(` that is an object or array literal and parses as JSON.
  // Server-rendered state is usually written that way, not in a JSON script
  // (Reuters: script#fusion-metadata, "Fusion.globalContent={…};", where each
  // podcast episode's mp3 sits beside its title; the regex scan below found
  // the URL and nothing named it, device audit 2026-10-20). An inline bundle
  // is minified JS full of `({` and `=[`, so the cost is kept linear in the
  // script whatever it holds: the brackets are paired in ONE pass
  // (literalSpans), a literal that cannot be JSON (`{a:1}`, `[x]`) is turned
  // away by its first character, a literal is skipped once one around it
  // parsed, and at most twice the script's length is handed to JSON.parse.
  // `visit(value)` gets each parsed object or array and answers 1 when it was
  // of use (that counts toward LITERALS_PER_SCRIPT: a bundle's `(["a"])`
  // arrays must not use up the count before the state object), 0 when not,
  // -1 to stop.
  const LITERAL_MAX = 3_000_000;
  const LITERALS_PER_SCRIPT = 8;
  const MAX_LITERAL_SPANS = 50_000;
  function eachJsonLiteral(text, visit) {
    let handed = 0;
    let used = 0;
    let inside = -1;
    for (const span of literalSpans(text)) {
      if (used >= LITERALS_PER_SCRIPT || handed > text.length * 2) return;
      const start = span[0];
      const end = span[1];
      if (start <= inside || end - start + 1 > LITERAL_MAX) continue;
      handed += end - start + 1;
      let value;
      try {
        value = JSON.parse(text.slice(start, end + 1));
      } catch (_) {
        continue;
      }
      inside = end;
      if (!value || typeof value !== 'object') continue;
      const verdict = visit(value);
      if (verdict < 0) return;
      used += verdict;
    }
  }

  // [start, end] of every `{`/`[` that follows an `=` (not `==`, `=>`, `>=`,
  // `<=`, `!=`) or a `(` and can start JSON, paired with the bracket that
  // closes it, in order of start. One pass; strings are skipped, the script's
  // single-quoted and template ones too (a `'"'` in the code before the state
  // must not turn the state inside out — JSON has no such strings of its
  // own, so this costs it nothing); an opener that never closes is left out.
  // Past MAX_LITERAL_SPANS the smallest go first: the state object closes
  // last, and a clip with a URL and a title is never a few characters.
  function literalSpans(text) {
    let spans = [];
    let minSpan = 0;
    const stack = [];
    let quote = '';
    let last = -1;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quote) {
        if (c === '\\') {
          i++;
        } else if (c === quote) {
          quote = '';
          last = i;
        }
        continue;
      }
      if (c === '"' || c === "'" || c === '`') {
        quote = c;
        continue;
      }
      if (c === '{' || c === '[') {
        const before = last >= 0 ? text[last] : '';
        const assigned = before === '=' && !/[=!<>]/.test(last > 0 ? text[last - 1] : '');
        stack.push((before === '(' || assigned) && jsonStart(text, i) ? i : -1);
      } else if (c === '}' || c === ']') {
        const open = stack.pop();
        if (open !== undefined && open >= 0 && i - open + 1 >= minSpan) {
          spans.push([open, i]);
          if (spans.length >= MAX_LITERAL_SPANS) {
            minSpan = Math.max(64, minSpan * 2);
            spans = spans.filter((x) => x[1] - x[0] + 1 >= minSpan);
          }
        }
      }
      if (c !== ' ' && c !== '\n' && c !== '\t' && c !== '\r') last = i;
    }
    spans.sort((x, y) => x[0] - y[0]);
    return spans;
  }

  // Whether the literal at `start` can be JSON at all: an object's first key
  // is quoted, an array's first item is a JSON value. `{a:1}` and `[x, y]`
  // never reach JSON.parse.
  function jsonStart(text, start) {
    let i = start + 1;
    while (i < text.length && /\s/.test(text[i])) i++;
    const c = text[i] || '';
    if (text[start] === '{') return c === '"' || c === '}';
    return /["{[\]\-0-9tfn]/.test(c);
  }

  function scrapeInlineScripts(state) {
    // Targeted Tier B: walk inline <script> text for media URLs that sit next
    // to a media key. Bounded by a global char budget and a per-pass emit cap,
    // and each script element is scanned at most once.
    const scripts = document.querySelectorAll('script:not([src])');
    for (const s of scripts) {
      if (state.emitted >= MAX_SCRIPT_MEDIA || state.budget <= 0) break;
      if (scrapedScripts.has(s)) continue;
      scrapedScripts.add(s);
      const text = s.textContent;
      if (!text) continue;
      // Cheap reject: skip scripts that mention no media extension at all.
      if (!/\.(?:mp4|m4v|mov|m3u8|m3u|mpd|webm|mkv|m4a|mp3|aac|flac|wav|opus|weba|ts)\b/i.test(text)) {
        continue;
      }
      const slice = text.length > state.budget ? text.slice(0, state.budget) : text;
      state.budget -= slice.length;
      const found = [];
      SCRIPT_MEDIA_RE.lastIndex = 0;
      let m;
      while ((m = SCRIPT_MEDIA_RE.exec(slice)) !== null && found.length < MAX_SCRIPT_MEDIA) {
        const url = unescapeUrl(m[1]);
        if (/^https?:/i.test(url) && !found.includes(url)) found.push(url);
      }
      if (!found.length) continue;
      // What the script's state literals say about those URLs: their clip's
      // title, and which URLs are one clip (its manifest is reported, not
      // its files). Only URLs the scan above found are reported: the
      // literals name, they never widen the scan.
      if (collectJsonClips) {
        eachJsonLiteral(slice, (data) => {
          let clips;
          try {
            clips = collectJsonClips(data);
          } catch (_) {
            clips = [];
          }
          let named = 0;
          for (const clip of clips) {
            const hits = clip.urls.filter((u) => found.includes(u));
            if (!hits.length) continue;
            named = 1;
            if (clip.urls.some((u) => seen.has(u))) continue;
            let best = '';
            for (const re of JSON_CLIP_PREFER) {
              best = hits.find((u) => re.test(u)) || '';
              if (best) break;
            }
            for (const u of clip.urls) rememberJsonClip(u, clip.meta);
            queue(best || hits[0]);
            for (const u of clip.urls) seen.add(u);
            if (++state.emitted >= MAX_SCRIPT_MEDIA) return -1;
          }
          return named;
        });
      }
      for (const url of found) {
        if (state.emitted >= MAX_SCRIPT_MEDIA) break;
        if (seen.has(url)) continue;
        queue(url);
        state.emitted++;
      }
    }
  }

  function scrapeEmbeddedMedia() {
    try { scrapeMetaTags(); } catch (_) {}
    try { scrapeJsonLdMedia(); } catch (_) {}
    const state = { budget: SCRIPT_SCAN_BUDGET, emitted: 0 };
    try { scrapeJsonScripts(state); } catch (_) {}
    try { scrapeInlineScripts(state); } catch (_) {}
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
      adoptShadowRoots();
      scrapeEmbeddedMedia();
      schedulePlaceholders();
    });
  } else {
    scrapeEmbeddedMedia();
    schedulePlaceholders();
  }
  window.addEventListener('load', () => {
    scan(document.documentElement);
    adoptShadowRoots();
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

  // An iframe title that only says what KIND of frame it is: once the
  // provider's name (the frame's own host) is taken out, nothing is left but
  // a number or the page's own class-name words ("YouTube video player",
  // "Advertisement" on a page whose classes say "advertisement").
  function genericFrameTitle(el, t) {
    let host = '';
    try { host = new URL(el.src).hostname.toLowerCase(); } catch (e) { host = ''; }
    const hostWords = host.split('.');
    const rest = (t.match(/\p{L}+/gu) || []).filter((w) => hostWords.indexOf(w.toLowerCase()) < 0).join(' ');
    if (!rest) return true;
    return !!(developerLabelOf && developerLabelOf(rest));
  }

  function captionForFrame(el) {
    let t;
    try {
      // The figure's caption first: what the editor wrote about what the
      // frame shows. A frame's own title is often its generator's ("Embedded
      // content 1", pym.js: Reuters' front page named a clip with it, device
      // audit 2026-10-20) or its provider's ("YouTube video player").
      const fig = el.closest ? el.closest('figure') : null;
      const cap = fig && fig.querySelector ? fig.querySelector('figcaption') : null;
      if (cap) { t = cleanCaption(cap.textContent); if (t.length >= CAPTION_MIN_CHARS) return t; }
      t = cleanCaption(el.getAttribute('title')) || cleanCaption(el.getAttribute('aria-label'))
        || cleanCaption(el.getAttribute('data-title'));
      if (t && !genericFrameTitle(el, t) && !(sharedLabelOf && sharedLabelOf(el, t))) return t;
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
        if (m.addedNodes.length) scheduleShadowSweep();
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

  const OBSERVE_OPTS = {
    subtree: true,
    childList: true,
    attributes: true,
    // 'style' feeds reportBgImage — dynamic galleries assign each tile's
    // photo by rewriting the inline background-image (the Google-Maps
    // class), which never touches src/srcset.
    attributeFilter: ['src', 'srcset', 'style'],
  };

  // Shadow roots (see knownShadowRoots): each new one is scanned and watched
  // like the document. A component attaches its root when it upgrades, often
  // after it was inserted, so the sweep runs again a second after the DOM
  // changes (once a second at most) and at load.
  let shadowSweepTimer = null;
  function adoptShadowRoots() {
    sweepShadowRoots(document, (r) => {
      scan(r);
      try {
        mo.observe(r, OBSERVE_OPTS);
      } catch (_) {}
    });
  }
  function scheduleShadowSweep() {
    if (shadowSweepTimer) return;
    shadowSweepTimer = setTimeout(() => {
      shadowSweepTimer = null;
      adoptShadowRoots();
    }, 1000);
  }

  function startObserver() {
    const target = document.body || document.documentElement;
    if (!target) {
      setTimeout(startObserver, 50);
      return;
    }
    mo.observe(target, OBSERVE_OPTS);
    adoptShadowRoots();
  }
  startObserver();

  // Catch images that finish loading after insertion (lazy load)
  document.addEventListener('load', (e) => {
    if (e.target && e.target.tagName === 'IMG') reportImg(e.target);
  }, true);

  clog('[cs] setup complete');
})();