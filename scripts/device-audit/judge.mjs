#!/usr/bin/env node
// judge.mjs — reads what the device audit (SiteAuditTest, run by
// scripts/device-audit.sh) recorded on the phone and says, without a person
// looking, what is wrong with each site's Captured sheet. The phone only
// collects; every rule lives here, so a rule can change and a run can be
// judged again without the phone:
//
//   node scripts/device-audit/judge.mjs build/device-audit/<run>
//   node scripts/device-audit/judge.mjs <run> --compare <older run>
//   node scripts/device-audit/judge.mjs <run> --only lasprovincias --verbose
//
// Writes <run>/<site>/judged.json and <run>/report.md, prints the report.
//
// What it flags, per tile the sheet shows (entities.json: the repository's
// own list, with the title the sheet shows, so the dedup and the
// "Video on <host>" rule are the app's, not a copy):
//   untitled    the name is a file name, so the sheet shows "Video on <host>"
//   generic     a fallback or a control's label: "Vimeo video", "Video Player"
//   site-name   the site's own name
//   page-title  the page's title, on a page with several clips
//   duplicate   two or more tiles with one name and one shape (a portrait
//               and a landscape cut of one story are two files, a note)
// and, from emits.json (every capture message, the dropped copies too):
//   lost-name   a later copy of the same clip carried a good name and was
//               dropped by the dedup (CNN's untitled wire copy won the race)
// and, from census.json (every frame's media elements and embeds):
//   missed-*    a <video>/<audio> playing a file nothing captured, more
//               streaming players than video tiles, an embed with no capture
// For each problem it prints where the clip sits (around.json) and the names
// the page has near it: the heading of its card, an alt, a data-* config's
// "title". When a rule is wrong for a real page, fix the rule, never with a
// host check.

import fs from "node:fs";
import path from "node:path";

const AUDIO_RE = /\.(?:mp3|m4a|aac|ogg|oga|opus|flac|wav|weba)(?:[?#]|$)/i;
const GENERIC_RE = new RegExp([
  "^(?:vimeo|dailymotion|bluesky|x|twitter|twitch|kick|rumble|niconico|substack|tiktok|instagram|facebook|youtube) (?:video|clip|post)$",
  "^acast episode$", "^apple podcasts episode$",
  "^(?:video|audio|clip|podcast|episode|media|player|untitled|sin t[ií]tulo)$",
  "^(?:video|media) player$", "^reproductor(?: de (?:v[ií]deo|audio))?$", "^lecteur(?: vid[ée]o)?$",
  "^videoplayer$", "^(?:play|pause|reproducir|watch|ver|ver v[ií]deo|play video|watch video|lire la vid[ée]o)$",
  "^(?:advertisement|publicidad|anuncio|ad)$",
].join("|"), "i");
const AD_HOST_RE = new RegExp("(?:^|\\.)(?:" + [
  "doubleclick\\.net", "googlesyndication\\.com", "googleadservices\\.com", "imasdk\\.googleapis\\.com",
  "2mdn\\.net", "googletagservices\\.com", "adservice\\.google\\.[a-z.]+", "amazon-adsystem\\.com", "adnxs\\.com",
  "criteo\\.(?:com|net)", "pubmatic\\.com", "rubiconproject\\.com", "openx\\.net", "smartadserver\\.com", "sascdn\\.com",
  "teads\\.tv", "outbrain\\.com", "taboola\\.com", "spotxchange\\.com", "springserve\\.com", "vidazoo\\.com",
  "aniview\\.com", "seedtag\\.com", "richaudience\\.com", "innovid\\.com", "flashtalking\\.com", "celtra\\.com",
  "adform\\.net", "adsrvr\\.org", "casalemedia\\.com", "yieldmo\\.com", "unrulymedia\\.com", "tremorhub\\.com",
  "rtbhouse\\.com", "sunmedia\\.tv", "moatads\\.com", "adsafeprotected\\.com", "doubleverify\\.com",
  "serving-sys\\.com", "smilewanted\\.com", "adition\\.com",
].join("|") + ")$", "i");
const EMBED_RE = /(?:youtube(?:-nocookie)?\.com\/embed|player\.vimeo\.com\/video|dailymotion\.com\/(?:embed|player)|geo\.dailymotion\.com\/player|cdn\.jwplayer\.com\/players|content\.jwplatform\.com|players\.brightcove\.net|player\.twitch\.tv|clips\.twitch\.tv\/embed|facebook\.com\/plugins\/video|tiktok\.com\/embed|open\.spotify\.com\/embed\/(?:track|episode)|embed\.acast\.com|embed\.podcasts\.apple\.com|rumble\.com\/embed|w\.soundcloud\.com\/player|fast\.wistia\.(?:net|com)\/embed|iframe\.mediadelivery\.net|embed\.ted\.com|bandcamp\.com\/EmbeddedPlayer|player\.kick\.com|ok\.ru\/videoembed|vk\.com\/video_ext|streamable\.com\/[eo]\/|loom\.com\/embed|ivoox\.com\/player|omny\.fm\/shows\/[^/]+\/[^/]+\/embed|podbean\.com\/player|player\.megaphone\.fm)/i;
// A page the phone was not shown: a bot check, an error page, a block.
const WALL_RE = /just a moment|checking your browser|verify(?:ing)? (?:you are|that you are) (?:a )?human|are you a robot|unusual traffic|attention required|access denied|request blocked|enable javascript and cookies|captcha|pardon our interruption|403 forbidden|bot detection/i;
const CONTROL_RE = /^(?:play|pause|mute|unmute|close|share|more|menu|next|previous|replay|fullscreen|settings|reproducir|pausa|cerrar|compartir|ver m[aá]s|leer m[aá]s|read more|see more|watch|ver|subscribe|suscr[ií]bete|log ?in|sign ?in|accept|aceptar)$/i;
// A player's own chrome: its sliders, clocks and buttons (as.com's podcast
// player labels its progress bar "Progreso de la reproducción" and its clock
// "Tiempo actual: 00:00"). Never a clip's name, so never offered as one.
const PLAYER_UI_RE = /^(?:progreso|progress|tiempo|current time|time|duraci[oó]n|duration|total|volumen?|volume|control de volumen|silenciar|mute|unmute|skip|seek|rewind|fast ?forward|avanzar|retroceder|playback|velocidad|speed|subt[ií]tulos|subtitles|captions|calidad|quality|pantalla completa|full ?screen|bot[oó]n|button|picture.in.picture|cast|airplay|chromecast)\b|\b\d{1,2}:\d{2}\b/i;

export const norm = (s) => String(s || "").normalize("NFKC").toLowerCase().replace(/\s+/g, " ")
  .replace(/^[\s"'“”«»]+|[\s"'“”«».:;,!?|·-]+$/g, "").trim();
const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch (_) { return ""; } };
const bare = (u) => {
  let s = String(u || "");
  const cut = s.search(/[?#]/);
  if (cut >= 0) s = s.slice(0, cut);
  return s.replace(/\/$/, "").replace(/^(\w+:\/\/)www\./i, "$1");
};
// A clip's shape from its renditions: "portrait", "landscape", "square",
// or "" when no rendition states a size (a capture the probe never read).
function orientationOf(e) {
  for (const v of e.variants || []) {
    if (v && v.w > 0 && v.h > 0) return v.h > v.w ? "portrait" : v.w > v.h ? "landscape" : "square";
  }
  return "";
}

const isAd = (u) => AD_HOST_RE.test(hostOf(u)) || /\/(?:ads?|adserver|vast|vpaid)\//i.test(String(u));
const clip = (s, n) => { const t = String(s || ""); return t.length > n ? t.slice(0, n) + "…" : t; };

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (_) {
    return fallback;
  }
}

// The id an embed URL names its clip by (a video= query or a path segment).
export function embedToken(src) {
  try {
    const u = new URL(src, "https://x.invalid/");
    for (const k of ["video", "v", "id", "i", "videoId", "episode"]) {
      const v = u.searchParams.get(k);
      if (v && v.length >= 4) return v;
    }
    const segs = u.pathname.split("/").filter(Boolean);
    for (let i = segs.length - 1; i >= 0; i--) {
      const s = segs[i].replace(/\.(?:html?|js)$/i, "");
      if (s.length >= 5 && /\d|[A-Z]/.test(s) && !/^(?:embed|player|video|iframe|index)$/i.test(s)) return s;
    }
  } catch (_) { /* not a URL */ }
  return "";
}

const emitUrls = (m) => {
  const out = [];
  if (typeof m.url === "string") out.push(m.url);
  if (typeof m.videoUrl === "string") out.push(m.videoUrl);
  for (const v of Array.isArray(m.variants) ? m.variants : []) {
    if (v && typeof v.url === "string") out.push(v.url);
    if (v && typeof v.audioUrl === "string") out.push(v.audioUrl);
  }
  return out.filter(Boolean);
};
const emitKind = (m) => {
  const t = String(m.type || "");
  const u = emitUrls(m)[0] || "";
  if (t === "image" || t === "svg") return "image";
  if (t === "subtitle" || t === "timedtext" || /\.(?:vtt|srt|ttml|dfxp)(?:[?#]|$)/i.test(u)) return "subtitle";
  return "media";
};
const emitVia = (m) => m.listener ? `catcher (${m.listener})` : (m.type ? `parser/bridge (${m.type})` : "?");

// Names the page has around a clip: headings and title-ish attributes in its
// card, titles in its data-* config and in the inline script that names it.
function candidateNames(matches, avoid) {
  const out = [];
  const seen = new Set(avoid.map(norm));
  const add = (text, where) => {
    const t = String(text || "").replace(/<[^>]+>/g, " ").replace(/&amp;/g, "&").replace(/&quot;/g, "\"")
      .replace(/&#39;|&apos;/g, "'").replace(/&nbsp;/g, " ").replace(/\s+/g, " ").trim();
    if (t.length < 6 || t.length > 220) return;
    if (CONTROL_RE.test(t) || GENERIC_RE.test(t) || PLAYER_UI_RE.test(t) || /^https?:/i.test(t) || !/\p{L}{3}/u.test(t)) return;
    const n = norm(t);
    if (seen.has(n)) return;
    seen.add(n);
    out.push({ text: t, where });
  };
  for (const m of matches) {
    const card = m.card || "";
    for (const h of card.matchAll(/<h[1-6][^>]*>([\s\S]*?)<\/h[1-6]>/gi)) add(h[1], "card heading");
    for (const a of card.matchAll(/<[^>]+class="[^"]*(?:title|headline|hed)[^"]*"[^>]*>([^<]{6,220})</gi)) add(a[1], "card title element");
    for (const a of card.matchAll(/\s(?:data-title|data-video-title|data-headline|aria-label|title|alt)="([^"]{6,220})"/gi)) add(a[1], "card attribute");
    for (const d of m.data || []) {
      if (/^(?:data-title|data-video-title|data-headline|title|aria-label|alt)$/i.test(d.name)) add(d.value, `${d.name} on <${d.tag}>`);
      for (const j of String(d.value || "").matchAll(/"(?:title|headline|videoTitle|name)"\s*:\s*"([^"]{6,220})"/g)) add(j[1], `${d.name} JSON on <${d.tag}>`);
    }
    for (const j of String(m.snippet || "").matchAll(/"(?:title|headline|videoTitle|name)"\s*:\s*"([^"]{6,220})"/g)) {
      add(j[1], m.attr === "#script" ? "inline script beside the URL" : `${m.attr} beside the URL`);
    }
  }
  return out.slice(0, 5);
}

export function judgeSite(dir) {
  const site = readJson(path.join(dir, "site.json"), {});
  const entities = readJson(path.join(dir, "entities.json"), []);
  const emits = readJson(path.join(dir, "emits.json"), []);
  const census = readJson(path.join(dir, "census.json"), { frames: [], census: [] });
  const around = readJson(path.join(dir, "around.json"), []);
  const requests = readJson(path.join(dir, "requests.json"), {});

  const frames = (census.census || []).map((c) => ({ ...(c.result || {}), frameId: c.frameId,
    parentFrameId: c.parentFrameId, error: c.error })).filter((f) => f.url || f.error);
  const top = frames.find((f) => f.frameId === 0) || {};
  const out = { url: site.url, location: site.location || "", title: top.title || site.title || "",
    ms: site.ms || 0, issues: [], notes: [], tiles: [] };
  if (site.error) out.error = site.error;

  // A page the phone was not shown is not judged.
  const wallText = `${top.title || ""} ${top.text || ""}`;
  if (site.error || !top.url) {
    out.state = "failed";
    out.notes.push({ kind: "failed", text: site.error ? clip(site.error, 200) : "the page answered nothing (no census)" });
    return out;
  }
  if (WALL_RE.test(wallText) && entities.length === 0) {
    out.state = "blocked";
    out.notes.push({ kind: "blocked", text: `bot wall or block page: "${clip(top.title || top.text, 120)}"` });
    return out;
  }

  const matchesByKey = new Map();
  for (const f of around) {
    for (const m of (f.result && f.result.matches) || []) {
      (matchesByKey.get(m.key) ?? matchesByKey.set(m.key, []).get(m.key)).push({ ...m, frameUrl: f.url });
    }
  }

  const media = entities.filter((e) => (e.video || e.audio) && !e.square && !isAd(e.url));
  const pictures = entities.filter((e) => e.square);
  const pageNames = new Set([top.title, top.ogTitle].map(norm).filter(Boolean));
  const siteNames = new Set([top.siteName].map(norm).filter(Boolean));
  for (const t of [top.title, top.ogTitle]) {
    const parts = String(t || "").split(/\s+[|·–—-]\s+/);
    if (parts.length > 1) siteNames.add(norm(parts[parts.length - 1]));
  }
  const multi = media.length >= 2;

  // Each emit, matched to the tile that holds its URL.
  const mediaEmits = emits.filter((m) => emitKind(m) === "media" && emitUrls(m).length);
  const tileOf = (m) => {
    const urls = emitUrls(m).map(bare);
    return media.find((e) => [e.url, ...(e.variants || []).flatMap((v) => [v.url, v.audioUrl])]
      .filter(Boolean).map(bare).some((u) => urls.includes(u)));
  };
  const firstEmitAt = new Map();
  for (const m of mediaEmits) {
    const t = tileOf(m);
    if (t && !firstEmitAt.has(t.uid)) firstEmitAt.set(t.uid, m._t || 0);
  }

  for (const e of media) {
    const tile = { uid: e.uid, kind: e.video ? "video" : "audio", shown: e.shown || e.name, name: e.name,
      url: e.url, variants: (e.variants || []).length, orient: orientationOf(e), issues: [] };
    const n = norm(e.name);
    if (e.filenameLike) tile.issues.push("untitled");
    else if (GENERIC_RE.test(String(e.name || "").trim())) tile.issues.push("generic");
    else if (siteNames.has(n)) tile.issues.push("site-name");
    else if (multi && pageNames.has(n)) tile.issues.push("page-title");
    if (tile.issues.length) {
      const first = firstEmitAt.get(e.uid) || 0;
      const better = mediaEmits.find((m) => tileOf(m) === e && m.name && norm(m.name) !== n
        && !GENERIC_RE.test(m.name) && !pageNames.has(norm(m.name)) && !siteNames.has(norm(m.name))
        && !/^\S+\.(?:mp4|m3u8|mpd|webm|mp3|m4a)$/i.test(m.name.trim()));
      if (better) tile.lostName = { name: better.name, via: emitVia(better), laterMs: (better._t || 0) - first };
      const matches = matchesByKey.get(String(e.uid)) || [];
      tile.where = matches.slice(0, 2).map((m) => ({ path: m.path, attr: m.attr, frame: m.frameUrl }));
      tile.pageHas = candidateNames(matches, [...pageNames, ...siteNames, e.name]);
    }
    out.tiles.push(tile);
  }
  const byName = new Map();
  for (const t of out.tiles) {
    const key = norm(t.shown);
    if (key) (byName.get(key) ?? byName.set(key, []).get(key)).push(t);
  }
  for (const [, all] of byName) {
    if (all.length < 2) continue;
    // Two CUTS of one story share its headline: a vertical edit beside the
    // landscape one, each its own file with its own ladder (NBC's
    // f_vert_… and f_mo_… masters, Reuters' 1080x1920 and 1920x1080
    // playlists, device audit 2026-10-20). Two tiles are right there, so a
    // group splits by orientation, and only tiles of ONE orientation are a
    // duplicate. A tile whose orientation is unknown could be either, so a
    // group holding one is judged whole, as before.
    const cuts = new Map();
    for (const t of all) (cuts.get(t.orient) ?? cuts.set(t.orient, []).get(t.orient)).push(t);
    const groups = cuts.has("") ? [all] : [...cuts.values()];
    if (groups.length > 1) {
      out.notes.push({ kind: "cuts", text: `${groups.length} cuts of "${clip(all[0].shown, 90)}" (${[...cuts.keys()].join(" + ")})` });
    }
    for (const list of groups) {
      if (list.length < 2) continue;
      for (const t of list) if (!t.issues.includes("duplicate")) t.issues.push("duplicate");
      out.issues.push({ kind: "duplicate", text: `${list.length} tiles named "${list[0].shown}"`, urls: list.map((t) => t.url) });
    }
  }
  for (const t of out.tiles) {
    for (const k of t.issues) {
      if (k === "duplicate") continue;
      out.issues.push({ kind: k, text: `${t.kind} shown as "${t.shown}"`, url: t.url, lostName: t.lostName,
        where: t.where, pageHas: t.pageHas });
    }
  }

  // Emits that never became a tile: the native side refused them (probe,
  // validity) or folded them; worth knowing when a clip is missing.
  const orphan = mediaEmits.filter((m) => !tileOf(m) && !emitUrls(m).some(isAd));
  if (orphan.length) {
    out.notes.push({ kind: "not-listed", text: `${orphan.length} media capture message(s) left no tile`,
      urls: orphan.slice(0, 6).map((m) => clip(emitUrls(m)[0], 160)) });
  }

  // ── the census: players the captures don't account for ──
  const captured = new Set();
  for (const e of entities) for (const u of [e.url, ...(e.variants || []).flatMap((v) => [v.url, v.audioUrl])]) if (u) captured.add(bare(u));
  for (const m of emits) for (const u of emitUrls(m)) captured.add(bare(u));
  const posters = new Set([...entities.map((e) => e.thumbnail), ...emits.map((m) => m.img)].filter(Boolean).map(bare));
  const reqLog = [...(requests.tab || []), ...(requests.extension || [])];
  const fate = (u) => {
    const b = bare(u);
    const hit = reqLog.find((r) => bare(r.u) === b);
    if (!hit) return "never requested";
    if (hit.err) return hit.err === "NS_ERROR_ABORT" ? "blocked (NS_ERROR_ABORT, uBlock)" : hit.err;
    return `HTTP ${hit.s || "?"}${hit.ct ? " " + hit.ct : ""}`;
  };
  let streaming = 0;
  const streamPosters = [];
  for (const f of frames) {
    for (const el of f.media || []) {
      const srcs = [el.src, ...(el.sources || [])].filter(Boolean);
      if (srcs.some(isAd)) continue;
      if (el.tag === "video" && el.w * el.h > 0 && (el.w < 80 || el.h < 45)) continue;
      const http = srcs.filter((s) => /^https?:/.test(s));
      if (http.length) {
        if (http.some((s) => captured.has(bare(s)))) continue;
        if (el.poster && posters.has(bare(el.poster))) continue;
        out.issues.push({ kind: `missed-${el.tag}`, possible: true,
          text: `<${el.tag}> playing ${clip(http[0], 140)} has no capture (${fate(http[0])})`, frame: f.url });
      } else if (/^blob:/.test(el.src || "") || (el.inPlayer && !el.src)) {
        if (el.tag === "video" && el.duration > 0 && el.duration < 4) continue;
        streaming++;
        if (el.poster) streamPosters.push(el.poster);
      }
    }
  }
  const videoTiles = out.tiles.filter((t) => t.kind === "video").length;
  if (streaming > videoTiles) {
    out.issues.push({ kind: "missed-player", possible: true,
      text: `${streaming} streaming player(s) on the page, ${videoTiles} video tile(s)`, posters: streamPosters.slice(0, 3) });
  }
  const tokens = [...entities.flatMap((e) => [e.url, e.origin, ...(e.variants || []).map((v) => v.url)]),
    ...emits.flatMap((m) => [...emitUrls(m), m.origin, m.originUrl])].filter(Boolean).map(String);
  for (const f of frames) {
    for (const ifr of f.iframes || []) {
      if (!EMBED_RE.test(ifr.src) || isAd(ifr.src)) continue;
      const token = embedToken(ifr.src);
      if (token && tokens.some((u) => u.includes(token))) continue;
      out.issues.push({ kind: "missed-embed", possible: true,
        text: `${ifr.placeholder ? "click-to-load " : ifr.loaded ? "" : "lazy "}embed ${clip(ifr.src, 140)} has no capture`, frame: f.url });
    }
  }

  const unnamed = pictures.filter((p) => !p.namedByPage);
  if (pictures.length) out.notes.push({ kind: "pictures", text: `${pictures.length - unnamed.length} of ${pictures.length} pictures named by the page` });
  const consent = site.consent || [];
  if (consent.length) out.notes.push({ kind: "consent", text: `consent: ${consent.map((c) => c.button).join(", ")}` });
  out.state = out.issues.some((i) => !i.possible) ? "bad" : out.issues.length ? "possible" : "ok";
  return out;
}

// ── a run ────────────────────────────────────────────────────────────────

function siteDirs(run) {
  return fs.readdirSync(run, { withFileTypes: true }).filter((d) => d.isDirectory()
    && fs.existsSync(path.join(run, d.name, "site.json"))).map((d) => d.name).sort();
}

function formatSite(j, verbose) {
  const mark = { ok: "✓", possible: "?", bad: "✗", blocked: "!", failed: "!" }[j.state] || "·";
  const lines = [];
  const real = j.issues.filter((i) => !i.possible).length;
  const head = j.state === "blocked" || j.state === "failed"
    ? `${mark} ${j.url} — ${j.notes[0] ? j.notes[0].text : j.state}`
    : `${mark} ${j.url} — ${j.tiles.length} tile(s)${real ? `, ${real} problem(s)` : ""}${j.issues.length - real ? `, ${j.issues.length - real} possible miss(es)` : ""}`;
  lines.push(head);
  // Issues that say the same thing are one line with a count (NBC's 44
  // clips all "shown as" the page's title).
  const groups = [];
  const byKey = new Map();
  for (const i of j.issues) {
    const key = JSON.stringify([i.kind, i.text, i.lostName || null, i.pageHas || []]);
    const g = byKey.get(key);
    if (g) {
      g.count++;
      g.urls.push(i.url);
    } else {
      const ng = { issue: i, count: 1, urls: [i.url] };
      byKey.set(key, ng);
      groups.push(ng);
    }
  }
  for (const { issue: i, count, urls } of groups) {
    lines.push(`    ${i.kind}: ${i.text}${count > 1 ? ` (×${count})` : ""}`);
    if (i.lostName) lines.push(`      a copy named "${i.lostName.name}" came ${i.lostName.laterMs} ms later via ${i.lostName.via} and was dropped`);
    for (const c of i.pageHas || []) lines.push(`      page has: "${clip(c.text, 120)}" (${c.where})`);
    if (verbose) for (const w of i.where || []) lines.push(`      at: ${clip(w.path, 160)} [${w.attr}]`);
    if (verbose) for (const u of urls) if (u) lines.push(`      url: ${clip(u, 200)}`);
    for (const u of (verbose ? i.urls || [] : [])) lines.push(`      url: ${clip(u, 200)}`);
  }
  if (verbose) {
    for (const t of j.tiles) lines.push(`    · ${t.kind}: "${clip(t.shown, 90)}"${t.variants > 1 ? ` (${t.variants} qualities)` : ""}`);
    for (const n of j.notes) lines.push(`    note: ${n.text}`);
  }
  return lines.join("\n");
}

const tileKey = (t) => bare(t.url);

function compare(after, before) {
  const lines = [];
  const b = new Map(before.tiles.map((t) => [tileKey(t), t]));
  const a = new Map(after.tiles.map((t) => [tileKey(t), t]));
  for (const [k, t] of a) {
    const old = b.get(k);
    if (!old) lines.push(`    new: ${t.kind} "${clip(t.shown, 90)}"`);
    else if (old.shown !== t.shown) lines.push(`    renamed: "${clip(old.shown, 70)}" → "${clip(t.shown, 70)}"`);
  }
  for (const [k, t] of b) if (!a.has(k)) lines.push(`    gone: ${t.kind} "${clip(t.shown, 90)}"`);
  const ib = before.issues.filter((i) => !i.possible).length;
  const ia = after.issues.filter((i) => !i.possible).length;
  if (ib !== ia) lines.push(`    problems: ${ib} → ${ia}`);
  return lines;
}

function main(argv) {
  const args = [...argv];
  const flag = (name) => {
    const i = args.indexOf(name);
    if (i < 0) return null;
    args.splice(i, 1);
    return true;
  };
  const option = (name) => {
    const i = args.indexOf(name);
    if (i < 0 || i + 1 >= args.length) return null;
    const v = args[i + 1];
    args.splice(i, 2);
    return v;
  };
  const verbose = flag("--verbose") || flag("-v");
  const only = option("--only");
  const against = option("--compare");
  const run = args[0];
  if (!run || !fs.existsSync(run)) {
    console.error("usage: node scripts/device-audit/judge.mjs <run dir> [--compare <older run dir>] [--only TEXT] [--verbose]");
    process.exit(2);
  }
  const meta = readJson(path.join(run, "run.json"), {});
  const results = [];
  for (const name of siteDirs(run)) {
    const j = judgeSite(path.join(run, name));
    if (only && !String(j.url).includes(only) && !name.includes(only)) continue;
    j.dir = name;
    fs.writeFileSync(path.join(run, name, "judged.json"), JSON.stringify(j, null, 2));
    results.push(j);
  }
  const count = (s) => results.filter((r) => r.state === s).length;
  const header = [
    `# Device audit ${path.basename(path.resolve(run))}`,
    "",
    `${meta.device || "device ?"} · Firedown ${meta.app || "?"} · ${results.length} site(s): `
      + `${count("ok")} clean, ${count("bad")} with problems, ${count("possible")} with possible misses, `
      + `${count("blocked")} blocked, ${count("failed")} failed`,
    "",
  ];
  const olderByUrl = new Map();
  if (against) {
    for (const name of siteDirs(against)) {
      const site = readJson(path.join(against, name, "site.json"), {});
      if (site.url) olderByUrl.set(site.url, path.join(against, name));
    }
  }
  const body = [];
  const order = { bad: 0, possible: 1, blocked: 2, failed: 3, ok: 4 };
  for (const j of [...results].sort((x, y) => (order[x.state] ?? 5) - (order[y.state] ?? 5))) {
    body.push(formatSite(j, verbose));
    if (against) {
      const old = olderByUrl.get(j.url);
      if (old) {
        const diff = compare(j, judgeSite(old));
        body.push(diff.length ? "  vs before:\n" + diff.join("\n") : "  vs before: no change");
      } else {
        body.push("  vs before: not in the older run");
      }
    }
  }
  const text = body.join("\n") + "\n";
  if (!only) fs.writeFileSync(path.join(run, "report.md"), header.join("\n") + "```\n" + text + "```\n");
  process.stdout.write(header.join("\n") + text);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main(process.argv.slice(2));
}
