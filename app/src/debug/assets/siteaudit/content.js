// Firedown site audit — DEBUG BUILDS ONLY (app/src/debug/assets/siteaudit).
// Inert until the device audit asks: it answers { siteaudit: 1, op, args }
// from background.js in this frame, and does nothing else.
//
//   consent  click a consent wall's accept button, as a user would
//   scroll   scroll the page through so lazy players come into existence
//   census   the frame's media elements, embeds, placeholders and names
//   around   the markup around a captured clip: the elements whose
//            attributes (or inline scripts) hold its URL, poster or file
//            name, with the card they sit in and the data-* config beside
//            them, so a wrong title can be traced to where the right one is

(() => {
  "use strict";

  const PLAYER_SEL = ".video-js, .jwplayer, .jw-wrapper, .plyr, video-js, [data-player], .bc-player, "
    + "[class*=\"brightcove\"], .vjs-tech";
  const MAX_MATCHES = 24;
  const MAX_PER_NEEDLE = 3;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // Every element of the frame, open and closed shadow roots included.
  function walk(rootNode, fn, depth) {
    const level = depth || 0;
    if (level > 12) {
      return;
    }
    const all = rootNode.querySelectorAll ? rootNode.querySelectorAll("*") : [];
    for (const el of all) {
      if (fn(el) === false) {
        return;
      }
      const shadow = el.openOrClosedShadowRoot || el.shadowRoot;
      if (shadow) {
        walk(shadow, fn, level + 1);
      }
    }
  }

  function parentOf(node) {
    if (node.parentElement) {
      return node.parentElement;
    }
    const root = node.getRootNode ? node.getRootNode() : null;
    return root && root.host ? root.host : null;
  }

  function meta(name) {
    const el = document.querySelector("meta[property=\"" + name + "\"], meta[name=\"" + name + "\"]");
    return el ? (el.getAttribute("content") || "").trim() : "";
  }

  function visible(el) {
    const r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) {
      return false;
    }
    const s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none" && s.opacity !== "0";
  }

  // ── consent ─────────────────────────────────────────────────────────────
  // Consent walls hold players back until the user agrees (lasprovincias.es:
  // its Brightcove players load ~13 s in, after the wall). Known buttons
  // first, then any button whose whole text is an "accept" phrase.
  const CONSENT_SELECTORS = [
    "#onetrust-accept-btn-handler", "#didomi-notice-agree-button", "button.didomi-button-highlight",
    "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll", "#CybotCookiebotDialogBodyButtonAccept",
    ".qc-cmp2-summary-buttons button[mode=primary]", "button.fc-cta-consent", "#L2AGLb",
    ".cmpboxbtnyes", "#cmpwelcomebtnyes", "#truste-consent-button", "button.sp_choice_type_11",
    "button[data-testid=uc-accept-all-button]", "#accept-choices", ".iubenda-cs-accept-btn",
    "button.cky-btn-accept", "#ccc-notify-accept", ".cmplz-accept", "#cookie_action_close_header",
    "button[mode=primary][aria-label*=ccept]", "#pmConsentWall .pmConsentWall-button",
  ];
  const CONSENT_TEXT = new RegExp("^\\s*(accept( all| all cookies| cookies| and close| & close)?|i accept"
    + "|agree( and close| to all)?|i agree|allow( all)?( cookies)?|ok,? (i agree|got it)|got it|consent"
    + "|continue with recommended cookies|aceptar( y cerrar| y continuar| todo| todas| todas las cookies"
    + "| cookies)?|acepto|estoy de acuerdo|de acuerdo|consentir|permitir todo"
    + "|accepter( et fermer| tout| les cookies)?|tout accepter|j'accepte|j’accepte|alle akzeptieren"
    + "|akzeptieren( und schließen)?|zustimmen|alle zulassen|einverstanden|accetta( tutto| e chiudi)?"
    + "|accetto|aceitar( tudo| todos| e fechar)?|concordo|aceptar todas las cookies|aceptar cookies"
    + "|zgadzam się|akceptuję|accepteren|alles accepteren|godkänn alla|accepter alle|hyväksy kaikki)"
    + "\\s*[.!]?\\s*$", "i");

  function consent() {
    const roots = [document];
    walk(document, (el) => {
      const shadow = el.openOrClosedShadowRoot || el.shadowRoot;
      if (shadow) {
        roots.push(shadow);
      }
    });
    for (const r of roots) {
      for (const sel of CONSENT_SELECTORS) {
        let el = null;
        try {
          el = r.querySelector(sel);
        } catch (e) {
          el = null;
        }
        if (el && visible(el)) {
          el.click();
          return sel;
        }
      }
    }
    for (const r of roots) {
      const candidates = r.querySelectorAll("button, a, [role=button], input[type=button], input[type=submit]");
      for (const el of candidates) {
        const t = (el.innerText || el.value || el.getAttribute("aria-label") || "").trim();
        if (t.length > 60 || !CONSENT_TEXT.test(t) || !visible(el)) {
          continue;
        }
        el.click();
        return "text:" + t;
      }
    }
    return "";
  }

  // ── scroll ──────────────────────────────────────────────────────────────
  async function scroll() {
    const height = () => Math.max(document.documentElement.scrollHeight,
      document.body ? document.body.scrollHeight : 0);
    let y = 0;
    let steps = 0;
    while (steps < 30 && y < height()) {
      y += Math.round(innerHeight * 0.8);
      scrollTo(0, y);
      steps++;
      await sleep(500);
    }
    scrollTo(0, 0);
    return { steps, height: height() };
  }

  // ── census ──────────────────────────────────────────────────────────────
  // What the judge compares the captures with: every media element (shadow
  // roots included), player containers, embed iframes, click-to-load
  // placeholders, and the page-level names a clip must not carry on a page
  // with several clips.
  function census() {
    const media = [];
    const iframes = [];
    let players = 0;
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
          w: Math.round(r.width),
          h: Math.round(r.height),
          poster: (el.getAttribute("poster") || "").slice(0, 300),
          duration: isFinite(el.duration) ? Math.round(el.duration) : 0,
          paused: el.paused,
          controls: el.hasAttribute("controls"),
          muted: el.muted,
          loop: el.loop,
          autoplay: el.autoplay,
          inPlayer: !!(el.closest && el.closest(PLAYER_SEL)),
          hiddenByAria: !!(el.closest && el.closest("[aria-hidden=true]")),
        });
      } else if (tag === "IFRAME") {
        const src = el.src || el.getAttribute("data-src") || el.getAttribute("data-lazy-src") || "";
        const r = el.getBoundingClientRect();
        iframes.push({
          src: src.slice(0, 400),
          title: (el.getAttribute("title") || "").slice(0, 120),
          w: Math.round(r.width),
          h: Math.round(r.height),
          loaded: !!el.src && el.src !== "about:blank",
        });
      }
      let declared = el.getAttribute ? el.getAttribute("data-embed-url") : null;
      if (!declared && (tag === "LITE-VIMEO" || tag === "LITE-YOUTUBE") && el.getAttribute("videoid")) {
        declared = (tag === "LITE-VIMEO" ? "https://player.vimeo.com/video/" : "https://www.youtube.com/embed/")
          + el.getAttribute("videoid");
      }
      if (declared) {
        const r = el.getBoundingClientRect();
        iframes.push({
          src: declared.slice(0, 400),
          title: (el.getAttribute("title") || el.getAttribute("aria-label") || "").slice(0, 120),
          w: Math.round(r.width),
          h: Math.round(r.height),
          loaded: false,
          placeholder: true,
        });
      }
      if (el.matches && el.matches(PLAYER_SEL)) {
        const outer = parentOf(el);
        if (!(outer && outer.closest && outer.closest(PLAYER_SEL))) {
          players++;
        }
      }
      return true;
    });
    const body = document.body ? (document.body.innerText || "") : "";
    return {
      url: location.href,
      title: document.title || "",
      ogTitle: meta("og:title"),
      siteName: meta("og:site_name") || meta("application-name"),
      lang: document.documentElement ? (document.documentElement.getAttribute("lang") || "") : "",
      ready: document.readyState,
      text: body.replace(/\s+/g, " ").trim().slice(0, 400),
      media,
      iframes,
      players,
      images: document.images.length,
    };
  }

  // ── around ──────────────────────────────────────────────────────────────
  function pathOf(el) {
    const parts = [];
    let node = el;
    for (let i = 0; i < 10 && node && node.tagName; i++) {
      let part = node.tagName.toLowerCase();
      if (node.id) {
        part += "#" + String(node.id).slice(0, 40);
      }
      const cls = typeof node.className === "string" ? node.className.trim().split(/\s+/).filter(Boolean) : [];
      if (cls.length) {
        part += "." + cls.slice(0, 3).join(".");
      }
      parts.unshift(part);
      node = parentOf(node);
    }
    return parts.join(" > ");
  }

  // The card a clip sits in: the nearest ancestor holding some text (a
  // heading, a caption), cloned without scripts, styles and icons.
  function cardHtml(el) {
    let node = el;
    let pick = null;
    for (let i = 0; i < 8; i++) {
      const parent = parentOf(node);
      if (!parent || parent === document.documentElement || parent === document.body) {
        break;
      }
      node = parent;
      pick = node;
      const text = (node.innerText || node.textContent || "").trim();
      if (text.length >= 30) {
        break;
      }
    }
    if (!pick) {
      pick = el;
    }
    const clone = pick.cloneNode(true);
    for (const n of clone.querySelectorAll("script, style, svg, noscript, link, meta")) {
      n.remove();
    }
    for (const n of clone.querySelectorAll("*")) {
      for (const a of Array.from(n.attributes)) {
        const max = a.name.startsWith("data-") ? 2000 : 300;
        if (a.value.length > max) {
          n.setAttribute(a.name, a.value.slice(0, max) + "…");
        }
      }
    }
    let html = clone.outerHTML.replace(/\s+/g, " ");
    if (html.length > 8000) {
      html = html.slice(0, 8000) + "…";
    }
    return html;
  }

  // The element's and its ancestors' data-*, title, aria-label and alt.
  function dataAttrs(el) {
    const out = [];
    let node = el;
    for (let level = 0; level < 8 && node && node.attributes; level++) {
      for (const a of Array.from(node.attributes)) {
        const name = a.name;
        if (!(name.startsWith("data-") || name === "title" || name === "aria-label" || name === "alt")) {
          continue;
        }
        const value = a.value || "";
        if (!value) {
          continue;
        }
        const json = /^\s*[[{]/.test(value);
        const max = json ? 3000 : 400;
        out.push({ level, tag: node.tagName.toLowerCase(), name,
          value: value.length > max ? value.slice(0, max) + "…" : value });
        if (out.length >= 40) {
          return out;
        }
      }
      node = parentOf(node);
    }
    return out;
  }

  function snippet(text, at, needleLength) {
    const start = Math.max(0, at - 800);
    const end = Math.min(text.length, at + needleLength + 800);
    return (start > 0 ? "…" : "") + text.slice(start, end) + (end < text.length ? "…" : "");
  }

  function haystack(el) {
    const list = [];
    for (const a of Array.from(el.attributes || [])) {
      const v = a.value;
      if (v && v.length < 400000) {
        list.push([a.name, v]);
      }
    }
    const tag = el.tagName;
    if ((tag === "VIDEO" || tag === "AUDIO") && el.currentSrc) {
      list.push(["currentSrc", el.currentSrc]);
    }
    if (tag === "SCRIPT" && !el.src) {
      const text = el.textContent || "";
      if (text.length < 4000000) {
        list.push(["#script", text]);
      }
    }
    return list;
  }

  // needles: [{ key, values: [string] }] — a clip's URL, its path, its file
  // name, its poster. Each needle is looked for in every attribute of every
  // element (data-* config JSON included) and in inline scripts.
  function around(args) {
    const needles = (Array.isArray(args.needles) ? args.needles : []).map((n) => ({
      key: String(n.key),
      values: (Array.isArray(n.values) ? n.values : []).map((v) => String(v).toLowerCase())
        .filter((v) => v.length >= 6),
    })).filter((n) => n.values.length);
    const matches = [];
    const counts = new Map();
    const seen = new Set();
    walk(document, (el) => {
      if (matches.length >= MAX_MATCHES) {
        return false;
      }
      const hay = haystack(el);
      if (!hay.length) {
        return true;
      }
      const lowered = hay.map((h) => h[1].toLowerCase());
      for (const n of needles) {
        if ((counts.get(n.key) || 0) >= MAX_PER_NEEDLE) {
          continue;
        }
        let found = null;
        for (let i = 0; i < lowered.length && !found; i++) {
          for (const v of n.values) {
            const at = lowered[i].indexOf(v);
            if (at >= 0) {
              found = { i, at, v };
              break;
            }
          }
        }
        if (!found) {
          continue;
        }
        counts.set(n.key, (counts.get(n.key) || 0) + 1);
        seen.add(el);
        const attr = hay[found.i][0];
        const value = hay[found.i][1];
        const item = { key: n.key, attr, needle: found.v, tag: el.tagName.toLowerCase(), path: pathOf(el) };
        if (value.length > 300) {
          item.snippet = snippet(value, found.at, found.v.length);
        }
        if (attr !== "#script") {
          item.card = cardHtml(el);
          item.data = dataAttrs(el);
        }
        matches.push(item);
      }
      return true;
    });
    const media = [];
    if (args.media) {
      walk(document, (el) => {
        if (media.length >= 10) {
          return false;
        }
        if ((el.tagName === "VIDEO" || el.tagName === "AUDIO") && !seen.has(el)) {
          media.push({ tag: el.tagName.toLowerCase(), src: (el.currentSrc || el.getAttribute("src") || "").slice(0, 300),
            poster: (el.getAttribute("poster") || "").slice(0, 300), path: pathOf(el),
            card: cardHtml(el), data: dataAttrs(el) });
        }
        return true;
      });
    }
    return { url: location.href, matches, media };
  }

  const OPS = {
    consent: () => consent(),
    scroll: () => scroll(),
    census: () => census(),
    around: (args) => around(args),
  };

  browser.runtime.onMessage.addListener((msg) => {
    if (!msg || msg.siteaudit !== 1) {
      return undefined;
    }
    const op = OPS[msg.op];
    if (!op) {
      return Promise.resolve({ error: "unknown op " + msg.op });
    }
    try {
      return Promise.resolve(op(msg.args || {}));
    } catch (e) {
      return Promise.resolve({ error: String((e && e.message) || e) });
    }
  });
})();
