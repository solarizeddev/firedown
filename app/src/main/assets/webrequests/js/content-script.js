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
// The card walk (cardTitleFor below) for an iframe, handed to the scanner's
// frame captions. Null until the block has run.
let frameCardTitleOf = null;

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
// document.querySelectorAll never looks. The scanner records every shadow
// root it finds (closed ones through Firefox's extension-only
// openOrClosedShadowRoot) so the scan, the element binding and the card walk
// reach into them. Hosts are looked for among autonomous custom elements (a
// tag with a hyphen); a built-in element hosting a root is not looked at.
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
  // SPAs the <title>/og: tags are often generic or stale while the VideoObject
  // carries the current video's name + description, so for media captures this
  // is the most accurate source. Returns {name, description} or null.
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
  // A standalone AUDIO file takes the page's metadata only when the page
  // presents it as content: most standalone audio is a ding, a UI sound or
  // background music. Told apart by the audio's ROLE, not its URL:
  //   'content' — declared (AudioObject/og:audio) or bound to an <audio>/<video>
  //               element, even a hidden one behind custom controls → enrich;
  //   'unknown' — no element and no declaration (a `new Audio()` sound) →
  //               suppress.
  // Computed in the frame that owns the media; only an AudioObject DECLARATION
  // may come from a same-origin parent (declaringDocs). `base` is the document
  // a relative URL belongs to.
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
  // first: this frame's own, then each SAME-ORIGIN ancestor's (a page can
  // declare the clips its iframe plays). Only PER-URL declarations read them,
  // so a parent never names another clip; page-level fields stay this frame's.
  // Same origin is checked explicitly: a cross-origin ancestor is skipped and
  // the walk climbs past it. A sandboxed frame's origin ("null") never matches.
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
  // (`<script type="application/firedown+json">`, a list of
  // {contentUrl, name, thumbnail, description?}), for a page of several clips
  // that must not list them as schema.org VideoObjects (crawlers index those,
  // and older builds read the first one for every capture). Read by contentUrl,
  // this frame first, then same-origin ancestors (declaringDocs); every block,
  // each parsed alone. Returns {name, description, thumbnail} or null.
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
  // query, every parameter of one in the other with the same value (a player
  // appends its own parameters to the source it was handed). requests.js
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
  // An unfilled template around a clip: a text slot whose whole text is one
  // bracketed phrase ("[Video title playing]") beside a <video> that has
  // never played. A player's modal is loaded with its template's own example
  // file until a clip is opened in it; that file is no clip of the page.
  const TEMPLATE_SLOT_RE = /^\[[^[\]]{2,80}\]$/;
  const templateClip = (el) => {
    if (!el || !el.paused || el.currentTime > 0) return false;
    let n = parentOf(el);
    for (let level = 0; level < 2 && n && n !== document.body; level++) {
      const all = n.querySelectorAll('*');
      for (let i = 0; i < all.length && i < 60; i++) {
        const x = all[i];
        if (x === el || x.children.length) continue;
        const t = (x.textContent || '').replace(/\s+/g, ' ').trim();
        if (TEMPLATE_SLOT_RE.test(t) && wordsOf(t).length >= 3) return true;
      }
      n = parentOf(n);
    }
    return false;
  };

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
  // copy of the same clip (a card can render one clip's element twice). An
  // element with no source is another clip, a player not loaded yet, unless
  // the page does not render it.
  const holdsAnotherClip = (node, el, mediaUrl) => {
    const els = queryDeep(node, 'audio, video');
    for (let i = 0; i < els.length; i++) {
      if (els[i] === el) continue;
      if (elementPlays(els[i], mediaUrl)) continue;
      if (!elementUrls(els[i]).length && !factsOf(els[i]).rendered) continue;
      return true;
    }
    return false;
  };

  // ── The page player playing a URL its element does not show ──
  // A Video.js player fed an HLS/DASH manifest plays it through MSE: its
  // <video> carries a blob: src or none, so findBoundMedia cannot tie the
  // manifest to an element. The player knows: currentSources() lists the
  // manifest, poster() its picture, mediainfo.name the clip's title. Read
  // through the Xray waiver (plain `window` where there is none), primitives
  // and index loops only. A URL matched only by its path counts when exactly
  // one player has it.
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
  // Page-level fields describe the PAGE, which is the clip only on a
  // single-video page, so these readers apply only when the frame holds more
  // than one <video>. The title is the bound element's own aria-label/title,
  // else what its card shows as a title (cardTitleFor): at most
  // CARD_MAX_LEVELS ancestors up, stopping at one that holds another clip.
  // A small control's label ("Play") is skipped by its place and size, not its
  // words; a label is taken verbatim. A player root's contents are its chrome,
  // so the walk starts at the root and skips candidates inside one.
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
  // a file name).
  const ALT_FILE_RE = /\.(?:jpe?g|png|gif|webp|avif|svg|bmp|tiff?|heic)\b/i;
  // How far up a clip's story link may sit.
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
  // "3 из 8"), alone or in brackets after a title. The joining word is the
  // page's language's; its length and the numbers around it are what count.
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
  // by slashes ("Name / Agency").
  const creditText = (t) => {
    const s = String(t || '');
    return s.indexOf('©') >= 0 || /(?:^|\s)@[\p{L}\p{N}_.]{2,}/u.test(s)
      || (/\S\s+\/\s+\S/.test(s) && wordsOf(s).length <= 8);
  };

  // ── How the page SHOWS a text ──
  // Rendered or not, its size, weight and case, and whether it is laid out
  // as a box of its own: read once per element per second (one answer, one
  // scan batch) from the computed style.
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
    f = { rendered: true, size: 0, weight: 400, upper: false, spaced: false, box: false };
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
      f.box = cs.display !== 'inline' && cs.display !== 'contents';
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
  // it does not declare (a headline <span> at 18 px on a 16 px page).
  const prominent = (f) => f.size >= 1.12 * basePx() || (f.weight >= 600 && f.size >= 0.95 * basePx());
  // Fine print: a credit, a byline, a timestamp.
  const finePrint = (f) => f.size > 0 && f.size < 0.8 * basePx();
  // A kicker, an eyebrow, a label over a title: a few words in capitals (the
  // stylesheet's or the text's own) or letter-spaced ("• VIDEO", "OPINION",
  // "10.000 PASOS EN CEUTA").
  const allCapsText = (t) => /\p{Lu}[^\p{Ll}]*\p{Lu}/u.test(t) && t === t.toUpperCase();
  const kickerLike = (f, t) => wordsOf(t).length <= 4 && (f.upper || f.spaced || allCapsText(t));

  // ── The page's own vocabulary ──
  // The words its class names, ids, test ids and custom elements' names are
  // made of (BEM, kebab, snake and camelCase split; a token with a digit, a
  // CSS-module hash, skipped), shadow roots included, with how many elements
  // use each. A short label made only of these words is a developer's name
  // for a ROLE, in whatever language the developer names things
  // (alt="Hero video poster" inside .hero .player). Three words at most: a
  // CMS can write a story's tags into class names (tag-taylor-swift).
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
      els = queryDeep(document, '[class], [id], [data-testid]');
    } catch (_) {
      els = [];
    }
    for (let i = 0; i < els.length && i < DEV_VOCAB_MAX_ELEMENTS; i++) {
      const el = els[i];
      const raw = (el.getAttribute('class') || '') + ' ' + (el.getAttribute('id') || '') + ' '
        + (el.getAttribute('data-testid') || '') + (el.localName.indexOf('-') > 0 ? ' ' + el.localName : '');
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
  // A story's URL often spells its headline. A short text a link carries that
  // shares no word with that slug ("Read more", in any language) is not the
  // story's name; a URL with no slug says nothing. Only for a control's
  // wording (a link's label of four words at most, a plain link text of two),
  // never for a text shown as a title: headlines are rewritten after the URL
  // is fixed.
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
    // A carousel's position after the title is not part of it.
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

  // A player's config kept as JSON in a data attribute names its clip
  // (Video.js reads data-setup, Plyr data-plyr-config, others their own). Any
  // data-* attribute holding a JSON object counts; the shape decides: a string
  // title (or videoTitle, headline) at the top, or a title or name under media
  // / video / metadata / mediainfo / clip. page-state-bridge.js reads the same
  // (configTitleFor there).
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
  // ancestor's data-* attribute, or a framework's state in a
  // <script type="application/json">. Find the object holding the clip's URL,
  // climb to the clip's own object (through a list of its RENDITIONS, never
  // into a list of other clips) and read what names it: a title (a string, or
  // {primary|basic|…}), else the story it links to. page-state-bridge.js names
  // page-world state with the same climb (clipMetaFromPath there).
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
  // or two levels in.
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
  // A clip's description beside its title: plain text, not markup.
  const JSON_DESCRIPTION_KEYS = ['description', 'summary', 'abstract'];
  const jsonDescriptionOf = (o) => {
    for (const k of JSON_DESCRIPTION_KEYS) {
      const v = o[k];
      if (typeof v !== 'string' || /<[a-z][^>]*>/i.test(v)) continue;
      const t = v.replace(/\s+/g, ' ').trim();
      if (t.length >= 20) return t.slice(0, 1000);
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
    const info = { title: '', story: '', poster: '', description: '', anchor: null };
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
        info.description = jsonDescriptionOf(node);
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
        clip = { urls: [], meta: { title: meta.title, story: meta.story, poster: meta.poster,
          description: meta.description } };
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
  // that names the element's clip, or null.
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

  // The headline the page shows for a story a clip's JSON links to, or ''.
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
  // on a page of several clips names. Words only: a token with a digit is
  // dropped, and so is a TEMPLATE's run at either end ("poster-…", "…-thumb",
  // in any language): one to three words that three or more DIFFERENT pictures
  // begin or end with. A picture is its words, so one photo in several crops
  // counts once; a word in the middle belongs to the story. A name made only
  // of class-name words is a role (developerLabel); fewer than three words
  // left is no name.
  const FILE_AFFIX_SHARED = 3;
  const FILE_AFFIX_MAX_WORDS = 3;
  const fileWordsOf = (u) => {
    let name = '';
    try {
      name = decodeURIComponent(new URL(u, location.href).pathname.split('/').pop() || '');
    } catch (_) {
      return [];
    }
    return name.replace(/\.[a-z0-9]{2,5}$/i, '').split(/[-_+.\s]+/).filter((w) => /^\p{L}{2,}$/u.test(w));
  };
  // The runs of words (folded, space-joined) three or more different pictures
  // of the page begin or end with.
  let fileAffixes = { at: 0, page: '', runs: null };
  const pictureFileAffixes = () => {
    const now = Date.now();
    if (fileAffixes.runs && fileAffixes.page === location.href && now - fileAffixes.at < 2000) return fileAffixes.runs;
    const names = new Set();
    let els = [];
    try {
      els = queryDeep(document, 'img, video[poster], source[srcset]');
    } catch (_) {
      els = [];
    }
    for (let i = 0; i < els.length && i < 2000 && names.size < 600; i++) {
      const el = els[i];
      let u = '';
      if (el.tagName === 'VIDEO') u = el.poster;
      else if (el.tagName === 'IMG') u = el.currentSrc || el.src;
      else u = (el.getAttribute('srcset') || '').trim().split(/\s+/)[0];
      const words = u ? fileWordsOf(u).map(foldWord) : [];
      if (words.length) names.add(words.join(' '));
    }
    const counts = new Map();
    for (const name of names) {
      const ws = name.split(' ');
      const runs = new Set();
      for (let n = 1; n <= FILE_AFFIX_MAX_WORDS && n <= ws.length; n++) {
        runs.add(ws.slice(0, n).join(' '));
        runs.add(ws.slice(ws.length - n).join(' '));
      }
      for (const r of runs) counts.set(r, (counts.get(r) || 0) + 1);
    }
    const runs = new Set();
    for (const [r, c] of counts) {
      if (c >= FILE_AFFIX_SHARED) runs.add(r);
    }
    fileAffixes = { at: now, page: location.href, runs };
    return runs;
  };
  // `words` without the longest template run at its start and at its end.
  const stripFileAffixes = (words, runs) => {
    let out = words;
    for (const fromStart of [true, false]) {
      for (let n = Math.min(FILE_AFFIX_MAX_WORDS, out.length); n >= 1; n--) {
        const part = fromStart ? out.slice(0, n) : out.slice(out.length - n);
        if (runs.has(part.map(foldWord).join(' '))) {
          out = fromStart ? out.slice(n) : out.slice(0, out.length - n);
          break;
        }
      }
    }
    return out;
  };
  const posterSlugTitle = (posterUrl) => {
    if (!posterUrl) return '';
    const words = stripFileAffixes(fileWordsOf(posterUrl), pictureFileAffixes());
    if (words.length < 3 || words.join('').length < 12) return '';
    const text = words.join(' ');
    if (developerLabel(text)) return '';
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
  // it too (a placeholder several players share). A second element for the
  // same file is the same clip.
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

  // A MediaSession title another clip's element or player carries in its own
  // label (<video title>, a player root's aria-label) is that clip's: a page
  // player publishes its session whether or not it is the element playing.
  const sessionTitleOfOtherClip = (title, el) => {
    const fold = (x) => (x || '').toLowerCase().replace(/[\s‘’'"]+/g, ' ').trim();
    const t = fold(title);
    if (t.length < 8) return false;
    const mine = mediaUrlsOf(el);
    const els = allMediaElements();
    for (let i = 0; i < els.length && i < 200; i++) {
      const other = els[i];
      if (other === el || mediaUrlsOf(other).some((u) => mine.includes(u))) continue;
      const root = other.closest ? other.closest(PLAYER_ROOT_SEL) : null;
      const labels = [other.getAttribute('title'), other.getAttribute('aria-label'),
        root ? root.getAttribute('aria-label') : ''];
      if (labels.some((l) => l && fold(l).indexOf(t) >= 0)) return true;
    }
    return false;
  };

  // Block-level elements: text on either side of one reads as two phrases.
  const BLOCK_TEXT_TAGS =/^(?:P|DIV|LI|H[1-6]|SECTION|ARTICLE|HEADER|FOOTER|FIGCAPTION|BLOCKQUOTE|DT|DD|TD|TH|BR|UL|OL)$/;
  // Never text a reader takes for a name.
  const UNIT_SKIP_SEL = 'script, style, noscript, template, svg, video, audio, iframe, object, canvas, select, textarea, input';
  // A control: its text says what it DOES, not what the card is.
  const CONTROL_SEL = 'button, summary, [role="button"], [role="switch"], [role="tab"], [role="menuitem"], '
    + '[role="menuitemcheckbox"], [role="menuitemradio"], [role="option"], [role="checkbox"], [role="radio"], '
    + '[role="slider"], [role="spinbutton"], [aria-pressed]';

  // Whether `n`, inside a text the page shows as one (`unitFacts` is how
  // that text is shown), is an ASIDE to it: a credit (schema.org's, or one
  // by its shape), a duration, a note or credit in smaller type, a part the
  // page keeps hidden, or (unless keepKickers) a kicker in capitals inside a
  // headline box or a word the page repeats in every card. `unit` is how the
  // whole text is shown: its facts, and whether it is all capitals itself.
  const asideIn = (n, unit, keepKickers) => {
    if (CREDIT_ITEMPROP_RE.test(n.getAttribute('itemprop') || '')) return true;
    const t = (n.textContent || '').replace(/\s+/g, ' ').trim();
    if (!t || t.length > 200) return false;
    if (metadataText(t) || creditText(t)) return true;
    const f = factsOf(n);
    if (unit.facts.rendered && !f.rendered) return true;
    const words = wordsOf(t).length;
    if (words <= 6 && f.size > 0 && unit.facts.size > 0 && f.size < 0.85 * unit.facts.size) return true;
    return !keepKickers && words <= 4 && (kickerApart(f, t, unit) || sharedText(n, t));
  };
  // A kicker inside a text is styled APART from it: in capitals or spaced
  // where the rest is not. A heading in capitals throughout holds none.
  const kickerApart = (f, t, unit) => (f.upper && !unit.facts.upper)
    || (f.spaced && !unit.facts.spaced) || (allCapsText(t) && !unit.caps);

  // The text of `root` a reader takes as one phrase: its asides left out,
  // text in separate boxes kept apart. A box is a block element, or any
  // element the stylesheet lays out as one (a <span> set display:block).
  const textOf = (root, keepKickers) => {
    let out = '';
    let lastBlock = null;
    const unit = {
      facts: factsOf(root),
      caps: allCapsText((root.textContent || '').replace(/\s+/g, ' ').trim()),
    };
    let walker;
    try {
      walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
        acceptNode: (n) => {
          if (n.nodeType === 3) return NodeFilter.FILTER_ACCEPT;
          if (n.matches(UNIT_SKIP_SEL) || asideIn(n, unit, keepKickers)) return NodeFilter.FILTER_REJECT;
          return NodeFilter.FILTER_SKIP;
        },
      });
    } catch (_) {
      return (root.textContent || '').replace(/\s+/g, ' ').trim();
    }
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      let block = n.parentElement;
      while (block && block !== root && !BLOCK_TEXT_TAGS.test(block.tagName) && !factsOf(block).box) {
        block = block.parentElement;
      }
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

  // Hidden by the page itself: the hidden attribute or an inline display:none.
  const hiddenNode = (c) => !!(c.hidden
    || (c.style && (c.style.display === 'none' || c.style.visibility === 'hidden')));

  // A card label or heading that a walk may take: visible, and not part of a
  // player's own chrome.
  const cardCandidate = (c) => c.getAttribute('aria-hidden') !== 'true' && !hiddenNode(c)
    && !playerRootOf(c);

  // The length of the text a reader sees in `root`, counted up to `limit`:
  // no script, style or template text, no SVG titles, no media element's
  // fallback text and nothing hidden (textContent counts all of those).
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
  // it the page shows as the title, else its plain text. A short text whose
  // words the story's URL does not spell is not the story's name (slugDenies).
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
  // The same label on links to three or more different pages, on the controls
  // of three or more different clips, on three or more clips' own elements or
  // as the alt of three or more pictures is a control's wording or a
  // placeholder, in any language. Two is not enough: a story's portrait and
  // landscape cuts share a title. Iframe titles count from two, their numbers
  // left out ("Embedded content 1", "… 2"), and so do a control's ("Slide 1"
  // … "Slide 9"). "Different" is the thing: a link's target, a clip's or
  // picture's file, an iframe's src, and for a control the clip or picture it
  // sits with. Only LABELS are judged (aria-label, title, a link's or
  // control's text, an alt), never headings, captions or declared titles. A
  // page of one or two clips is read by structure instead (cardLabelFor,
  // slugDenies). One census per kind per page per second.
  const LABEL_CENSUS_SEL = {
    link: 'a[href], [role="link"]',
    control: 'button, summary, [role="button"], [role="switch"], [role="tab"], [role="menuitem"], '
      + '[role="checkbox"], [role="option"], input[type="button"], input[type="submit"], '
      + 'a[href^="#"], a[href^="javascript:" i], [role="link"]:not(a)',
    media: 'video[aria-label], video[title], audio[aria-label], audio[title]',
    picture: 'img[alt]',
    frame: 'iframe[title], iframe[aria-label]',
    item: 'article[aria-label], figure[aria-label], li[aria-label], [role="article"][aria-label], '
      + '[role="group"][aria-label], [role="figure"][aria-label], [role="listitem"][aria-label]',
  };
  const LABEL_CENSUS_MAX = 5000;
  const LABEL_CENSUS_TTL_MS = 1000;
  const SHARED_LABEL_MIN = 3;
  const SHARED_FRAME_TITLE_MIN = 2;
  // Elements of one label looked at to count its subjects, at most.
  const SHARED_LABEL_PROBE = 40;
  let labelCensus = { at: 0, page: '', kinds: new Map(), affixes: new Map() };

  const labelKindOf = (el) => {
    const tag = el.tagName;
    if (tag === 'VIDEO' || tag === 'AUDIO') return 'media';
    if (tag === 'IMG') return 'picture';
    if (tag === 'IFRAME') return 'frame';
    if ((tag === 'A' || el.getAttribute('role') === 'link') && linkTarget(el)) return 'link';
    // An item's own name (itemLabel): an article's, a figure's, a group's.
    if (el.matches(ITEM_SEL) && !el.matches(CONTROL_SEL)) return 'item';
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
    if (kind === 'item') {
      // What the item holds: its clip or picture, else the story it links to.
      const m = queryDeep(el, 'video, audio, img')[0];
      if (m) return m.tagName === 'IMG' ? pictureSubject(m) : mediaSubject(m);
      const a = el.querySelector('a[href]');
      return (a && linkTarget(a)) || el;
    }
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
      labelCensus = { at: Date.now(), page: location.href, kinds: new Map(), affixes: new Map() };
    }
    let groups = labelCensus.kinds.get(kind);
    if (!groups) {
      groups = buildLabelGroups(kind);
      labelCensus.kinds.set(kind, groups);
    }
    const g = groups.get(key);
    return g ? groupShared(g, kind) : false;
  };
  // Whether a census group's label is on several different things.
  const groupShared = (g, kind) => {
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

  // ── The template around a label ──
  // The words a page wraps every label of one shape in ("<title> video",
  // "Vertical <title> (2 de 10)"): a run of one to three words (a number
  // counts as any number) that four in five labels of one shape (tag and
  // role), three at least, begin or end with. Left out when two words or more
  // remain. Developer and shared labels are not counted; a picture's alt never
  // loses words. Read from the label census (sharedLabel builds it).
  const LABEL_AFFIX_MAX_WORDS = 3;
  const LABEL_AFFIX_SHARE = 0.8;
  const LABEL_SEPARATORS_RE = /^[\s:|·•\-–—,;/]+|[\s:|·•\-–—,;/]+$/g;
  const affixWord = (w) => (/\d/.test(w) ? '#' : foldWord(w));
  const labelShapeOf = (el) => el.tagName + '|' + (el.getAttribute('role') || '');
  const labelAffixRuns = (kind, shape) => {
    const key = kind + '|' + shape;
    let runs = labelCensus.affixes.get(key);
    if (runs) return runs;
    runs = new Set();
    const groups = labelCensus.kinds.get(kind);
    if (groups) {
      const counts = new Map();
      let labels = 0;
      for (const [text, g] of groups) {
        if (!g.els.some((e) => labelShapeOf(e) === shape)) continue;
        const ws = wordsOf(text).map(affixWord);
        if (ws.length < 2 || groupShared(g, kind) || developerLabel(text)) continue;
        labels++;
        const mine = new Set();
        for (let n = 1; n <= LABEL_AFFIX_MAX_WORDS && n < ws.length; n++) {
          mine.add('<' + ws.slice(0, n).join(' '));
          mine.add('>' + ws.slice(ws.length - n).join(' '));
        }
        for (const r of mine) counts.set(r, (counts.get(r) || 0) + 1);
      }
      for (const [r, c] of counts) {
        if (c >= SHARED_LABEL_MIN && c >= LABEL_AFFIX_SHARE * labels) runs.add(r);
      }
    }
    labelCensus.affixes.set(key, runs);
    return runs;
  };
  const stripLabelAffix = (el, t) => {
    const kind = labelKindOf(el);
    if (kind === 'picture') return t;
    const runs = labelAffixRuns(kind, labelShapeOf(el));
    if (!runs.size) return t;
    const tokens = Array.from(t.matchAll(/[\p{L}\p{N}]+/gu));
    let start = 0;
    let end = t.length;
    // Cut where the template's words begin and end, so the title keeps its
    // own punctuation ("No, Really, Is A.I. a Bubble? video").
    for (let n = Math.min(LABEL_AFFIX_MAX_WORDS, tokens.length - 1); n >= 1; n--) {
      if (runs.has('<' + tokens.slice(0, n).map((m) => affixWord(m[0])).join(' '))) {
        start = tokens[n - 1].index + tokens[n - 1][0].length;
        break;
      }
    }
    for (let n = Math.min(LABEL_AFFIX_MAX_WORDS, tokens.length - 1); n >= 1; n--) {
      if (runs.has('>' + tokens.slice(tokens.length - n).map((m) => affixWord(m[0])).join(' '))) {
        end = tokens[tokens.length - n].index;
        break;
      }
    }
    if (start === 0 && end === t.length) return t;
    const out = end > start ? t.slice(start, end).replace(LABEL_SEPARATORS_RE, '') : '';
    return wordsOf(out).length >= 2 ? out : t;
  };

  // `t` when it is a label of `el` the page gives to nothing else, else '';
  // without the template the page wraps every label of its shape in.
  const ownLabel = (el, t) => (t && !sharedLabel(el, t) ? stripLabelAffix(el, t) : '');

  // ── A text the page shows in several cards names none ──
  // The label census, for the words a page SHOWS: the same short text in
  // three or more different cards is a kicker, a call to action, a credit or
  // a status, in any language. A different card is a different picture, clip
  // or story (labelSubjectOf, then a link's target). One census per page per
  // second.
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
  // card's headline is the OTHER link to the same story beside it. '' when
  // the clip is in no story link, or no twin lies within a card's reach.
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

  // Whether a clip sits inside a link to ANOTHER page: that story's teaser,
  // not this page's own video.
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

  // A clip inside a <button>: a card's preview, played in the control that
  // opens the clip (a carousel of vertical videos). An article's own player
  // is never a button's content.
  const inCardButton = (start) => {
    let n = parentOf(start);
    for (let level = 0; level < STORY_LINK_MAX_LEVELS && n && n !== document.body; level++) {
      if (n.tagName === 'BUTTON') return true;
      n = parentOf(n);
    }
    return false;
  };

  // A link to another page that NAMES a story in the clip's card: a title
  // element that is or holds such a link, or a labelled link with no text of
  // its own laid over the card. A card-sized node only: an article body links
  // everywhere, and its own clip keeps the page's title.
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
  // player shows for the one clip it plays. A heading or a caption first;
  // else a control's label long enough to carry a title (LONE_LABEL_MIN_WORDS,
  // "Play <title>"). A shorter label is an action's wording, and the page's
  // title names the page's only player better.
  const LONE_LABEL_MIN_WORDS = 8;
  const ownCardHeadingFor = (el, holdsOther) => {
    const start = cardStart(el);
    let label = '';
    for (const node of cardAncestors(start)) {
      if (holdsOther(node)) break;
      if (visibleTextLength(node, CARD_TEXT_MAX) > CARD_TEXT_MAX) return playerItemBeside(node, start) || label;
      const head = levelNames(node, start, true).title;
      if (head) return head;
      const cap = cardCaptionFor(node);
      if (cap) return cap;
      if (!label) {
        const l = cardLabelFor(node, start);
        if (wordsOf(l).length >= LONE_LABEL_MIN_WORDS) label = l;
      }
    }
    return label;
  };

  // A link on this page to the media file itself (a podcast widget's
  // download button), or null: the link's card names the file.
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
      if (sameUrl(abs, mediaUrl) || refinedUrl(abs, mediaUrl) || linkAddsQuery(abs, mediaUrl)) return a;
    }
    return null;
  };
  // The one element whose data-* attribute holds the file's own id (its name
  // without the extension), or null: a radio's play button carrying
  // data-audioid="<id>" beside the bulletin it plays. An id a page reuses
  // for nothing else: six characters or more, a digit among them.
  const elementForFileId = (mediaUrl) => {
    const stem = lastSegment(mediaUrl).replace(/\.[a-z0-9]{2,4}$/i, '');
    if (stem.length < 6 || !/\d/.test(stem) || /["'\\%]/.test(stem)) return null;
    let hits;
    try {
      hits = document.evaluate(`//*[@*[starts-with(name(), "data-")] = "${stem}"]`, document, null,
        XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
    } catch (_) {
      return null;
    }
    return hits.snapshotLength === 1 ? hits.snapshotItem(0) : null;
  };
  // A link to the file with parameters of its own (?dl=true&…): same origin
  // and path, every parameter of the file's URL in the link's.
  const linkAddsQuery = (href, mediaUrl) => {
    let x;
    let y;
    try {
      x = new URL(href, location.href);
      y = new URL(mediaUrl, location.href);
    } catch (_) {
      return false;
    }
    if (x.origin !== y.origin || x.pathname !== y.pathname) return false;
    for (const [k, v] of y.searchParams) {
      if (x.searchParams.getAll(k).indexOf(v) < 0) return false;
    }
    return true;
  };

  // The title a clip with none in its own card takes from ANOTHER element on
  // the page showing the same picture: a lightbox whose slides carry no title,
  // opened from a carousel whose cards do (poster.jpg?width=480 on the card,
  // ?width=720 in the lightbox). Same file = same origin and path; several
  // elements giving different titles answer nothing.
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
  // CARD_MAX_LEVELS: a wrapper holding nothing else is free.
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
  // caption that is only a credit is none.
  const cardCaptionFor = (node) => {
    const caps = node.querySelectorAll(CARD_CAPTION_SEL);
    for (let i = 0; i < caps.length; i++) {
      const c = caps[i];
      if (!cardCandidate(c)) continue;
      const t = usableCardLabel(textOf(c));
      if (!t || creditText(t) || sharedText(c, t)) continue;
      // One word, or a few in capitals: a credit, not a description.
      const f = factsOf(c);
      if (wordsOf(t).length < 2 || kickerLike(f, t) || (finePrint(f) && wordsOf(t).length <= 6)) continue;
      return t;
    }
    return '';
  };

  // A control's label in `node` (the link or button WRAPPING the clip
  // included), when it names the card rather than an action: the control
  // holds the clip or a picture (the card IS the control), or lies over most
  // of the clip. Any other control's label is the card's when the page holds
  // three clips or more: the label census has already dropped what every card
  // repeats, so one no other card repeats is the card's own. On a page of one
  // or two clips only a label longer than an action names its card. Several →
  // the longest.
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

  // The control that holds the clip is its card (a carousel card's button
  // playing its preview): its label names the clip as an item's would. When
  // the label holds the alt of the control's picture, the alt is the name and
  // the label's other words are the control's wording ("Preview: <title>.
  // Play full screen"). A short label with no picture in it ("Play video", a
  // player wrapping its <video> in a button) is the control's wording: the
  // walk goes on, and the label stays a last resort.
  const heldControlName = (node, start) => {
    if (!node.matches(CONTROL_SEL) || !holdsAcrossShadow(node, start)) return '';
    const label = cardLabelFor(node, start);
    if (!label) return '';
    const alt = cardAltFor(node);
    const fold = (x) => x.toLowerCase().replace(/\s+/g, ' ').trim();
    if (alt && fold(label).indexOf(fold(alt)) >= 0) return alt;
    return wordsOf(label).length >= CONTROL_NAME_MIN_WORDS ? label : '';
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
  // clip: then the control IS the card.
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
        // Hidden from readers by the page: a duplicate, a decoration, a credit.
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
  // <header> or <footer> belongs to the article or section it sits in (a
  // card's own header holds its title); one that sits in none is the page's.
  // With wholeCard, `node` is one player's card and a section in it is the
  // card's part, unless it links to other pages (a list of stories).
  const inOtherSection = (el, node, anchor, wholeCard) => {
    let s = el.closest(SECTIONING_SEL);
    if (!s || s === node || !node.contains(s)) return false;
    if (/^(?:HEADER|FOOTER)$/.test(s.tagName)) {
      const owner = s.parentElement ? s.parentElement.closest('section, article, aside, nav') : null;
      if (owner && (owner === node || owner.contains(node))) return false;
      if (owner && node.contains(owner)) s = owner;
    }
    if (s.contains(anchor)) return false;
    return !wholeCard || linksElsewhere(s);
  };
  const linksElsewhere = (root) => {
    const links = root.querySelectorAll('a[href]');
    for (let i = 0; i < links.length; i++) {
      const target = linkTarget(links[i]);
      if (target && target !== location.href.split('#')[0]) return true;
    }
    return false;
  };

  // Of `units`, what names a clip:
  //   title  — a declared title or a text shown larger or bolder than the
  //            page's: in a card-sized node the most prominent, before the
  //            clip on a tie; in a bigger one (an article, a section) only a
  //            declared one BEFORE the clip, the nearest (a heading after it
  //            there is a subheading, and the first heading of a bigger node
  //            titled an article's clip with "Background" or "Most read");
  //   plain  — in a card-sized node, the text the page shows as plain text,
  //            two words or more, a phrase of three or more first;
  //   hidden — the same among the texts the page keeps hidden (a caption
  //            shown on hover);
  //   kicker — a phrase of three or more words in capitals, the last resort.
  // A heading inside a section, aside or the like that does not hold the
  // clip belongs to something else (unless wholeCard: `node` is one card,
  // its sections its parts, as long as they link to no other page).
  const pickUnits = (units, anchor, cardSized, wholeCard, node) => {
    const out = { title: '', plain: '', hidden: '', kicker: '' };
    let best = null;
    let before = null;
    let plain = null;
    for (const el of units) {
      if (node && inOtherSection(el, node, anchor, wholeCard)) continue;
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

  // An audio player's own card at a level too big to read whole (its
  // schedule or station list beside it): the one item (an <article>) just
  // before the branch holding the player, card-sized, linking to no other
  // page. A bar with no text between them is passed over.
  const playerItemBeside = (node, start) => {
    if (visibleTextLength(node, CARD_TEXT_MAX) <= CARD_TEXT_MAX) return '';
    let branch = anchorIn(node, start);
    while (branch && branch.parentElement !== node) branch = branch.parentElement;
    if (!branch) return '';
    let sib = branch.previousElementSibling;
    for (let hops = 0; sib && hops < 3; hops++, sib = sib.previousElementSibling) {
      const items = sib.matches('article, [role="article"]') ? [sib] : Array.from(sib.querySelectorAll('article, [role="article"]'));
      if (!items.length) {
        if (visibleTextLength(sib, 1) > 0) return '';
        continue;
      }
      if (items.length !== 1) return '';
      const item = items[0];
      if (visibleTextLength(item, CARD_TEXT_MAX) > CARD_TEXT_MAX || linksElsewhere(item)) return '';
      const u = pickUnits(textUnitsIn(item, null), null, true, true, item);
      return u.title || u.plain || '';
    }
    return '';
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
  // label that names the card (cardLabelFor), its hidden text, its picture's
  // alt (several words, not a file name), a kicker of three words or more. A
  // label is taken verbatim ("View <title>" stays).
  // skipOwn leaves out the element's own label (a picture's alt and title
  // were read already); noLabel leaves out the control-label fallback (a
  // picture's description is never a button's wording); wholeCard reads a
  // section beside the clip as part of its card (see pickUnits).
  const cardTitleFor = (el, holdsOther, skipOwn, noLabel, wholeCard) => {
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
    let show = null;
    const clip = el.tagName === 'VIDEO' || el.tagName === 'AUDIO';
    for (const node of cardAncestors(start)) {
      if (holdsOther(node)) {
        const first = show === null;
        show = clip ? slideshowAround(node, el, start, show) : null;
        if (!show) {
          const listed = clip && first ? slideListedCaptionFor(node, start) : '';
          if (listed) return listed;
          break;
        }
      }
      const named = labelledByText(node) || itemLabel(node) || heldControlName(node, start);
      if (named) return named;
      const level = levelNames(node, start, !!wholeCard);
      if (level.title) return level.title;
      const item = wholeCard ? playerItemBeside(node, start) : '';
      if (item) return item;
      const cap = cardCaptionFor(node);
      if (cap) return clipText(cap);
      if (level.plain) return clipText(level.plain);
      if (!label) label = cardLabelFor(node, start);
      if (!hidden) hidden = level.hidden;
      if (!alt) alt = cardAltFor(node);
      if (!kicker) kicker = level.kicker;
    }
    return (noLabel ? '' : label) || clipText(hidden) || alt || kicker;
  };
  frameCardTitleOf = (el) => cardTitleFor(el,
    (node) => queryDeep(node, 'iframe, video, audio').some((x) => x !== el), true);

  // A caption or a card's text as a clip's name: without a credit after its
  // last sentence ("… across France. (Agency)") or a © tail, and without
  // closing punctuation.
  const clipText = (t) => (t ? (t.replace(CREDIT_AFTER_SENTENCE_RE, '$1').replace(COPYRIGHT_TAIL_RE, '')
    .replace(TRAILING_PUNCT_RE, '').trim() || t) : t);

  // ── A slideshow's caption ──
  // An ancestor holding another clip ends the walk: it is a list of cards,
  // and its text names them all. Not when it is a SLIDESHOW showing this
  // clip: no other clip of it is shown, and their slides carry no text of
  // their own; the text beside the slides (a caption rewritten as they
  // change) then describes the slide shown. A slide not shown when its clip
  // is found is named when it plays (unnamedClips). `show` is the slideshow
  // an earlier level was found to be: a bigger node holding no clip outside
  // it is still that slideshow's.
  const shownIn = (el, node) => {
    try {
      if (typeof el.checkVisibility === 'function' && !el.checkVisibility({
        checkVisibilityCSS: true, visibilityProperty: true, opacityProperty: true, checkOpacity: true,
      })) return false;
      const a = el.getBoundingClientRect();
      const b = node.getBoundingClientRect();
      const area = a.width * a.height;
      if (area < 1) return false;
      const ix = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
      const iy = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
      return ix * iy >= 0.5 * area;
    } catch (_) {
      return false;
    }
  };
  const slideshowAround = (node, el, start, show) => {
    const others = queryDeep(node, 'audio, video').filter((o) => o !== el && !start.contains(o));
    if (show) return others.every((o) => show.contains(o)) ? show : null;
    if (!shownIn(el, node)) return null;
    for (const o of others) {
      if (shownIn(o, node)) return null;
      let slide = o;
      while (slide && parentOf(slide) !== node) slide = parentOf(slide);
      if (!slide || slide.contains(start) || visibleTextLength(slide, 1) > 0) return null;
    }
    return node;
  };

  // ── A slideshow's listing ──
  // A slideshow that shows one slide at a time can keep every slide's
  // caption in an inline script of its own, as a list in slide order it
  // writes into one caption box as the slides change: a slide not shown
  // holds no text, and its clip is found with none until it plays. The list
  // is told by the slides that show a PICTURE: at each of those slots the
  // entry at the same index carries the picture's alt (the one text the
  // page writes twice), so the list is the slides' own and its entry at the
  // clip's slot names the clip. The entry text the pictures match (the entry
  // itself, or one key of an entry object) is what is read at the clip's
  // slot; no key is known in advance.
  const SLIDE_LIST_SCRIPT_MAX = 200_000;
  const SLIDE_LIST_LEVELS = 6;
  const SLIDE_LISTS_PER_SCRIPT = 8;
  const SLIDE_LIST_NODES = 20_000;
  const slideListsOf = new WeakMap(); // script → the data lists its text holds
  const slideListedCaptionFor = (node, start) => {
    const slots = node.children ? Array.from(node.children) : [];
    if (slots.length < 2 || slots.length > 200) return '';
    let slide = start;
    while (slide && parentOf(slide) !== node) slide = parentOf(slide);
    const index = slots.indexOf(slide);
    if (index < 0) return '';
    const fold = (x) => (x || '').replace(/\s+/g, ' ').trim();
    const alts = slots.map((s, i) => {
      if (i === index || queryDeep(s, 'video, audio').length) return '';
      const im = s.querySelector('img');
      const alt = im ? fold(im.getAttribute('alt')) : '';
      return wordsOf(alt).length >= 3 ? alt : '';
    });
    if (!alts.some(Boolean)) return '';
    // The script sits inside the slideshow's root or right beside it (it
    // finds the slideshow as the element before itself): the scripts in each
    // ancestor and the inline scripts next to it, never the whole page's.
    let ancestor = node;
    for (let i = 0; i < SLIDE_LIST_LEVELS && ancestor && ancestor !== document.body; i++) {
      const scripts = Array.from(ancestor.querySelectorAll('script:not([src])'));
      for (const dir of ['nextElementSibling', 'previousElementSibling']) {
        for (let s = ancestor[dir]; s && s.tagName === 'SCRIPT'; s = s[dir]) {
          if (!s.hasAttribute('src')) scripts.push(s);
        }
      }
      for (const script of scripts) {
        for (const list of slideListsIn(script)) {
          if (list.length !== slots.length) continue;
          const name = listedSlideName(list, alts, index, fold);
          if (name) return name;
        }
      }
      ancestor = parentOf(ancestor);
    }
    return '';
  };
  // The entry text the picture slots' alts all match, read at `index`: every
  // picture slot with an alt must match its entry under the same key, and at
  // least one must.
  const listedSlideName = (list, alts, index, fold) => {
    const textsOf = (e) => {
      if (typeof e === 'string') return [['', fold(e)]];
      if (!e || typeof e !== 'object' || Array.isArray(e)) return [];
      return Object.keys(e).filter((k) => typeof e[k] === 'string').map((k) => [k, fold(e[k])]);
    };
    let key = null;
    let matched = 0;
    for (let i = 0; i < list.length; i++) {
      if (!alts[i]) continue;
      const hit = textsOf(list[i]).find((x) => x[1] === alts[i]);
      if (!hit || (key !== null && key !== hit[0])) return '';
      key = hit[0];
      matched++;
    }
    if (!matched) return '';
    const own = textsOf(list[index]).find((x) => x[0] === key);
    const t = own ? own[1] : '';
    return wordsOf(t).length >= 2 ? clipText(t) : '';
  };
  // The arrays of strings or objects a script's text holds as data,
  // wherever a value may start (after `=`, `(`, `,` or `:`), parsed once per
  // script and bounded: a script too long is not read, a parse ends at the
  // first token that is not data, and at most twice the text is walked.
  const slideListsIn = (script) => {
    let lists = slideListsOf.get(script);
    if (lists) return lists;
    lists = [];
    const text = script.textContent || '';
    if (text.length <= SLIDE_LIST_SCRIPT_MAX) {
      const budget = { n: SLIDE_LIST_NODES };
      let spent = 0;
      let i = text.indexOf('[');
      while (i >= 0 && spent < text.length * 2 && budget.n > 0 && lists.length < SLIDE_LISTS_PER_SCRIPT) {
        let b = i - 1;
        while (b >= 0 && /\s/.test(text[b])) b--;
        if (b < 0 || /[=(,:]/.test(text[b])) {
          const r = parseJsData(text, i, budget);
          spent += r.end - i;
          if (r.ok && Array.isArray(r.value) && r.value.length >= 2
            && r.value.every((e) => typeof e === 'string' || (!!e && typeof e === 'object' && !Array.isArray(e)))) {
            lists.push(r.value);
          }
          if (r.ok) i = r.end - 1;
        }
        i = text.indexOf('[', i + 1);
      }
    }
    slideListsOf.set(script, lists);
    return lists;
  };
  // A JavaScript array or object literal written as DATA: strings in any
  // quote, numbers, true/false/null/undefined, identifier or string keys,
  // comments and trailing commas. An expression or a call in it ends the
  // parse ({ ok: false }); `end` is how far it read either way.
  const parseJsData = (text, at, budget) => {
    let i = at;
    const fail = () => { throw new Error('data'); };
    const ws = () => {
      for (;;) {
        const c = text[i];
        if (c === ' ' || c === '\n' || c === '\r' || c === '\t') i++;
        else if (c === '/' && text[i + 1] === '/') { while (i < text.length && text[i] !== '\n') i++; }
        else if (c === '/' && text[i + 1] === '*') { const e = text.indexOf('*/', i + 2); i = e < 0 ? text.length : e + 2; }
        else return;
      }
    };
    const str = () => {
      const q = text[i++];
      let out = '';
      while (i < text.length) {
        const c = text[i++];
        if (c === q) return out;
        if (c === '\n' && q !== '`') fail();
        if (c === '\\') {
          const e = text[i++];
          if (e === 'n') out += '\n';
          else if (e === 't') out += '\t';
          else if (e === 'r') out += '\r';
          else if (e === 'u') { out += String.fromCharCode(parseInt(text.slice(i, i + 4), 16) || 0); i += 4; }
          else if (e === 'x') { out += String.fromCharCode(parseInt(text.slice(i, i + 2), 16) || 0); i += 2; }
          else if (e !== '\n' && e !== '\r') out += e;
        } else if (q === '`' && c === '$' && text[i] === '{') fail();
        else out += c;
      }
      fail();
    };
    const value = () => {
      if (--budget.n < 0) fail();
      ws();
      const c = text[i];
      if (c === '[') {
        i++;
        const arr = [];
        for (;;) {
          ws();
          if (text[i] === ']') { i++; return arr; }
          arr.push(value());
          ws();
          if (text[i] === ',') { i++; continue; }
          if (text[i] === ']') { i++; return arr; }
          fail();
        }
      }
      if (c === '{') {
        i++;
        const obj = Object.create(null);
        for (;;) {
          ws();
          if (text[i] === '}') { i++; return obj; }
          let k;
          if (text[i] === '"' || text[i] === "'") k = str();
          else {
            const m = /^[A-Za-z_$][\w$]*/.exec(text.slice(i, i + 64));
            if (!m) fail();
            k = m[0];
            i += k.length;
          }
          ws();
          if (text[i] !== ':') fail();
          i++;
          obj[k] = value();
          ws();
          if (text[i] === ',') { i++; continue; }
          if (text[i] === '}') { i++; return obj; }
          fail();
        }
      }
      if (c === '"' || c === "'" || c === '`') return str();
      const m = /^(?:-?\d+(?:\.\d+)?(?:e[+-]?\d+)?|true|false|null|undefined)(?![\w$])/i.exec(text.slice(i, i + 40));
      if (!m) fail();
      i += m[0].length;
      const w = m[0].toLowerCase();
      if (w === 'true') return true;
      if (w === 'false') return false;
      if (w === 'null' || w === 'undefined') return null;
      return Number(w);
    };
    try {
      const v = value();
      return { ok: true, value: v, end: i };
    } catch (_) {
      return { ok: false, value: null, end: Math.max(i, at + 1) };
    }
  };

  // The accessible name an item around the clip carries: an article, a
  // figure, a group, a list item (<div role="group" aria-label="<title>">).
  // A landmark's name names a part of the page, not a clip; a control's is
  // cardLabelFor's.
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
  // A picture's URL is no name. The page names it: the alt it wrote to stand in
  // for it, its title, the caption of the figure it is the only picture of,
  // and for a card picture with none of those the card it sits in (the same
  // walk that names a clip). Built in the scanner's flush and carried in the
  // report, as a clip's metadata is; requests.js pickImageMeta checks it
  // again. The description is what the name left out: the caption with its
  // credit, the whole alt when the name had to be cut, else the card's title.
  const IMAGE_NAME_MAX = 100;
  const IMAGE_DESCRIPTION_MAX = 500;
  // A credit says who took the picture, never what it shows. A parenthesis
  // after a sentence's end is one ("… flooded. (Photo by Name / Agency)");
  // inside the sentence it is content ("… in Fuenlabrada (Madrid)."). So is
  // a © tail, and a "Word: Names" tail after a sentence ("… Photo: Jane Doe"):
  // a colon, then only capitalised names. A sentence after a colon is content.
  const CREDIT_AFTER_SENTENCE_RE = /([.!?…])\s*\([^()]{1,120}\)\s*$/;
  const COPYRIGHT_TAIL_RE = /\s*©.{1,80}$/;
  const CREDIT_COLON_TAIL_RE = /([.!?…])\s+[\p{L}]{2,15}\s*:\s*((?:[\p{Lu}\p{N}@][^\s/]*(?:\s*\/\s*|\s+)?){1,6})$/u;
  // An agency dateline before the description ("SAN MARCOS, TEXAS -
  // OCTOBER 15: …"): a place with a comma, a dash, a word and a day, a colon.
  // The month is whatever word the agency's language uses.
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
    // A MARK (an icon, a logo, a glyph) is shown under 64 px on its shorter
    // side, or is an SVG whose size the page does not declare. Its own alt,
    // title or label names it; the text AROUND it is about something else (a
    // footer's links, an ad's pitch), so it gets no card walk. An SVG of a
    // declared picture size is a picture like any other.
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
    // The picture's own title, when the alt named it and the title says more.
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
    // thumbnail instead of decoding a frame. Decided by what the captured URL
    // could be tied to:
    //   bound element (findBoundMedia) → its poster attribute, else its OWN
    //     player's poster container, else '' (NEVER another clip's poster: an
    //     empty img makes the native side decode the clip's own frame);
    //   a page player playing the URL through MSE (findPagePlayer) → its
    //     element, as above, then the player's own poster();
    //   no bound element → on a single-video page the first <video poster>, then
    //     the first player container's background; on a multi-video page
    //     nothing: the first poster belongs to another clip.
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
    // The MediaSession describes whatever is playing NOW: on a page of several
    // videos it names the captured clip only when the clip's own element is the
    // one playing.
    const sessionDescribes = !multiVideo || (!!boundEl && !boundEl.paused);
    let ms = sessionDescribes ? readMediaSession() : { title: '', artist: '', artwork: '' };
    if (multiVideo && ms.title && sessionTitleOfOtherClip(ms.title, boundEl)) {
      ms = { title: '', artist: '', artwork: '' };
    }
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
      && (inStoryLink(cardStart(boundEl)) || inCardButton(cardStart(boundEl))
        || cardStoryLink(cardStart(boundEl), moreThanOneVideo));
    // A file no element plays but the page links to (a widget's download
    // button) or names by its id: that element stands for it, unless the
    // page's JSON names it.
    const jsonNames = !!json && !!(json.title || json.story);
    const fileLink = !boundEl && !player && mediaUrl && !jsonNames
      ? (linkToFile(mediaUrl) || elementForFileId(mediaUrl)) : null;
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
      // An <audio> on a page with other clips: its card names it. An audio
      // player is a widget, never an article, so a section beside the <audio>
      // is the player's own part.
      const otherClip = (node) => holdsAnotherClip(node, boundEl, mediaUrl);
      cardTitle = (json && json.title) || (json && json.story ? storyTitleFor(json.story) : '')
        || cardTitleFor(boundEl, otherClip, false, false, true);
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
    } else if (!boundEl && !player && jsonNames) {
      // A URL the page's JSON lists with no element showing it: the JSON names it.
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
      // The description the page's JSON gives the clip it names.
      jsonDescription: json && (json.title || json.story) ? (json.description || '') : '',
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
      templateClip: boundEl ? templateClip(boundEl) : false,
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
  // loading; built here, in a timer task with no deadline, the answer is the
  // one the responder would give.
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
      if (media && !clipNamedBy(m)) rememberUnnamed(url);
      if (!out) out = {};
      out[url] = m;
    }
    return out;
  }

  // ── A clip named once it plays ──
  // Some pages say more about a clip once it plays: a slideshow's caption
  // follows the slide shown, a player is labelled "Video player" until it
  // loads its clip. A clip reported with nothing of its own (the page's title
  // at best) is looked at again when it starts playing, and a name found then
  // goes to the background (clip-named), which renames a capture listed with
  // the page's title or no name (BrowserDownloadRepository). The look waits
  // RENAME_SETTLE_MS for a crossfade to end; a clip paused by then has moved
  // on.
  const UNNAMED_CLIPS_MAX = 200;
  const RENAME_RETRY_MS = 5000;
  const RENAME_SETTLE_MS = 1000;
  const unnamedClips = new Map();   // url → when it was last looked at again
  const clipNamedBy = (m) => !!(m && (m.audioLdName || m.videoLdMatchName || m.playerTitle
    || m.mediaSessionTitle || m.cardTitle || m.posterTitle));
  function rememberUnnamed(url) {
    if (unnamedClips.has(url)) return;
    if (unnamedClips.size >= UNNAMED_CLIPS_MAX) unnamedClips.delete(unnamedClips.keys().next().value);
    unnamedClips.set(url, 0);
  }
  function nameOnPlay(el, urls) {
    if (el.paused || !el.isConnected) return;
    for (const url of urls) {
      if (!unnamedClips.has(url)) continue;
      let m = null;
      try {
        m = collectMediaMeta(url);
      } catch (_) {
        m = null;
      }
      if (!clipNamedBy(m)) continue;
      unnamedClips.delete(url);
      clog('[cs] named on play', url);
      try {
        const p = browser.runtime.sendMessage({ kind: 'clip-named', url, meta: m });
        if (p && p.catch) p.catch(() => {});
      } catch (_) {}
    }
  }
  function onPlaying(e) {
    const el = e.target;
    if (!el || !collectMediaMeta || !unnamedClips.size || (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO')) return;
    const urls = [el.currentSrc, el.getAttribute('src') ? el.src : ''];
    const sources = el.querySelectorAll('source[src]');
    for (let i = 0; i < sources.length; i++) urls.push(sources[i].src);
    const now = Date.now();
    const due = [];
    for (const url of urls) {
      if (!url || due.indexOf(url) >= 0 || !unnamedClips.has(url) || now - unnamedClips.get(url) < RENAME_RETRY_MS) continue;
      unnamedClips.set(url, now);
      due.push(url);
    }
    if (due.length) setTimeout(() => nameOnPlay(el, due), RENAME_SETTLE_MS);
  }
  document.addEventListener('playing', onPlaying, true);

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
    // An empty src attribute reflects as the document's own URL, as on <audio>
    // (reportMediaEl).
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
  // quality from it; the files beside it are fallbacks for a browser without
  // HLS/DASH, so the manifest is reported alone. Sources that are all files
  // (a resolution ladder) are reported as before and folded by the repository.
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

  // CSS background photos. An app-like gallery can render its photos as
  // `<div style="background-image:url(…)">` with extensionless URLs, so no
  // <img> exists for the scan, and a cached copy never crosses webRequest.
  // INLINE style only: a class-styled background is a design asset, and
  // reading it would cost getComputedStyle per element. The CSSOM has already
  // normalized el.style.backgroundImage, so the regex only splits url()
  // layers; queue() drops non-http(s) ones.
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
    // queue()'s ^https?: filter accepts them. A JSON string can also escape
    // characters as \uXXXX ('&' as &): left in, the URL is not the one the
    // page fetches, so it never matches a parser's claim or the wire's copy.
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

  // Tier B for JSON: a <script type="application/json"> holding a framework's
  // state is read AS JSON, so each clip it lists is reported once (its
  // manifest, else its first file) and named by its own entry (jsonClips). The
  // text scan below would report every URL on its own, titled with the page.
  // A script that does not parse is left to it.
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
  // `=`, a `(` or a `,` that is an object or array literal and parses as JSON
  // (server-rendered state such as `window.state={…};`, or an argument after
  // others: `widget("render", "box", {…})`). An inline bundle
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
  // `<=`, `!=`), a `(` or a `,` and can start JSON, paired with the bracket that
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
        stack.push((before === '(' || before === ',' || assigned) && jsonStart(text, i) ? i : -1);
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
  // player iframe only on a tap (<a data-embed-url="…/player…?video=<id>">),
  // or hold a lazy iframe that loads its player only when scrolled into view.
  // No player exists until then, so nothing reaches the wire. Report the
  // player URL each one DECLARES, any host; the background hands it to the
  // parser that owns the host (parsers/common.js registerPlaceholderHandler)
  // and ignores the rest. Two kinds, the explicit ones first so a page's lazy
  // iframes can
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
  // A hosted player embed can carry no usable title of its own (its document
  // titled with the upload's file name) while the clip's description sits in
  // the host page, near the <figure> holding the iframe. The iframe is
  // cross-origin, so only this frame can read it. For every http(s) <iframe>
  // report (src → caption) to the background, which uses it ONLY for a capture
  // from that frame whose own title is filename-like (requests.js
  // withFrameCaption). Sources, in order: the figure's <figcaption>, the
  // iframe's own title/aria-label/data-title unless generic, then the nearest
  // preceding text block within a few ancestors. Iframes added later are
  // picked up by the MutationObserver below.
  const MAX_FRAME_CAPTIONS = 60;
  const CAPTION_MIN_CHARS = 12;
  const sentFrameCaptions = new Map(); // src -> caption last sent
  let frameCaptionTimer = null;

  function cleanCaption(s) {
    return (typeof s === 'string') ? s.replace(/\s+/g, ' ').trim().slice(0, 200) : '';
  }

  // An iframe title that only says what KIND of frame it is: once the
  // provider's name (the frame's own host) is taken out, nothing is left but
  // a number or the page's own class-name words ("<Provider> video player").
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
      // The figure's caption first: what the editor wrote about what the frame
      // shows. A frame's own title is often its generator's ("Embedded content
      // 1") or its provider's.
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
      // A frame that is only a player for a file handed in its own URL
      // (…/iframe-video/?src=<mp4>) is named by its card.
      if (frameCardTitleOf && frameHoldsMediaUrl(el.src)) {
        t = cleanCaption(frameCardTitleOf(el));
        if (t.length >= CAPTION_MIN_CHARS) return t;
      }
    } catch (e) { /* a detached or exotic element — no caption */ }
    return '';
  }

  function frameHoldsMediaUrl(src) {
    let u;
    try {
      u = new URL(src);
    } catch (_) {
      return false;
    }
    for (const v of u.searchParams.values()) {
      if (/^https?:\/\//i.test(v) && MEDIA_URL_RE.test(v)) return true;
    }
    return false;
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
    // photo by rewriting the inline background-image, which never touches
    // src/srcset.
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
        // A media event is not composed: a clip in a shadow root plays
        // unseen by the document's listener.
        r.addEventListener('playing', onPlaying, true);
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