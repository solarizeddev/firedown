// judge.mjs — decide, without a person looking, what is wrong with what a
// page captured. Pure: it reads a simulate() result (sim.mjs) and returns the
// captures as the Captured sheet would show them, each with its problems,
// plus the page's possible misses.
//
// The rules are the symptoms every report so far came down to:
//   untitled    no name: the sheet shows "Video on <host>" (CNN's wire loops)
//   filename    a URL slug or upload name: "6aaaf5204d3859992c30a380.mp4", "src"
//   generic     a fallback or a control's label: "Vimeo video", "Video Player",
//               "Reproducir", "Play video"
//   site-name   the site's own name (og:site_name), on any page
//   page-title  the page's <title>/og:title on a page with several clips
//               (Daily Mail: "UK Home | Daily Mail Online" on every clip)
//   duplicate   two or more tiles with one name: either several clips named
//               alike, or one clip captured twice — both are bugs a user sees
//   missed-*    the page holds a player the captures don't account for:
//               a <video>/<audio> with a file no capture lists, a frame with
//               more MSE players than captures, a video embed with none
// A note is information, not a failure: a clip with no picture (the app
// decodes a frame), pictures named by URL, YouTube embeds (the youtube@
// extension, not simulated here).
//
// Every rule is a heuristic; the report says "possible" for the census side.
// When a rule is wrong for a page, fix the rule here with a case in
// selftest.mjs, never a host check.

const AUDIO_RE = /\.(?:mp3|m4a|aac|ogg|oga|opus|flac|wav|weba)(?:[?#]|$)/i;
const SUBTITLE_RE = /\.(?:vtt|srt|ttml|dfxp)(?:[?#]|$)/i;
const MEDIA_EXT_RE = /\.(?:mp4|m4v|mov|webm|mkv|avi|m3u8|mpd|ts|mp3|m4a|aac|wav|flac|ogg|opus)$/i;
const SHORT_SLUG_RE = /^(?:src|master|playlist|index|manifest|video|videos|media|stream|chunklist|videoplayback|play|file|download|audio|clip|default|main|output|source)$/i;
const GENERIC_RE = new RegExp([
  "^(?:vimeo|dailymotion|bluesky|x|twitter|twitch|kick|rumble|niconico|substack|tiktok|instagram|facebook) (?:video|clip|post)$",
  "^acast episode$", "^apple podcasts episode$",
  "^(?:video|audio|clip|podcast|episode|media|player|untitled|sin t[ií]tulo)$",
  "^(?:video|media) player$", "^reproductor(?: de (?:v[ií]deo|audio))?$", "^lecteur(?: vid[ée]o)?$",
  "^videoplayer$", "^(?:play|pause|reproducir|watch|ver|ver v[ií]deo|play video|watch video|lire la vid[ée]o)$",
  "^(?:advertisement|publicidad|anuncio|ad)$", "^video on .+$", "^audio on .+$",
].join("|"), "i");
// Ad-tech hosts. On the phone uBlock blocks them, so their videos never
// reach the sheet; the audit blocks them too (sim.mjs, as uBlock does:
// NS_ERROR_ABORT) and never judges what one serves. Pure ad tech only:
// a host that also serves editorial video (Dailymotion, Connatix, Primis)
// is not here.
export const AD_HOST_RE = new RegExp("(?:^|\\.)(?:" + [
  "doubleclick\\.net", "googlesyndication\\.com", "googleadservices\\.com", "imasdk\\.googleapis\\.com",
  "2mdn\\.net", "googletagservices\\.com", "adservice\\.google\\.[a-z.]+", "amazon-adsystem\\.com", "adnxs\\.com",
  "criteo\\.(?:com|net)", "pubmatic\\.com", "rubiconproject\\.com", "openx\\.net", "smartadserver\\.com", "sascdn\\.com",
  "teads\\.tv", "outbrain\\.com", "taboola\\.com", "spotxchange\\.com", "springserve\\.com", "vidazoo\\.com",
  "aniview\\.com", "seedtag\\.com", "richaudience\\.com", "innovid\\.com", "flashtalking\\.com", "celtra\\.com",
  "adform\\.net", "adsrvr\\.org", "casalemedia\\.com", "yieldmo\\.com", "unrulymedia\\.com", "tremorhub\\.com",
  "rtbhouse\\.com", "sunmedia\\.tv", "moatads\\.com", "adsafeprotected\\.com", "doubleverify\\.com",
  "serving-sys\\.com", "smilewanted\\.com", "adition\\.com", "ads\\.[\\w-]+\\.[a-z]+",
].join("|") + ")$", "i");
export function isAdUrl(u) {
  try { return AD_HOST_RE.test(new URL(u).hostname); } catch (_) { return false; }
}
const AD_RE = { test: (u) => isAdUrl(u) || /\/(?:ads?|adserver|vast|vpaid)\//i.test(String(u)) };
// Embedded players whose clip a page owes the sheet.
const EMBED_RE = /(?:youtube(?:-nocookie)?\.com\/embed|player\.vimeo\.com\/video|dailymotion\.com\/(?:embed|player)|geo\.dailymotion\.com\/player|cdn\.jwplayer\.com\/players|content\.jwplatform\.com|players\.brightcove\.net|player\.twitch\.tv|clips\.twitch\.tv\/embed|facebook\.com\/plugins\/video|tiktok\.com\/embed|open\.spotify\.com\/embed\/(?:track|episode)|embed\.acast\.com|embed\.podcasts\.apple\.com|rumble\.com\/embed|w\.soundcloud\.com\/player|fast\.wistia\.(?:net|com)\/embed|iframe\.mediadelivery\.net|embed\.ted\.com|bandcamp\.com\/EmbeddedPlayer|player\.kick\.com|ok\.ru\/videoembed|vk\.com\/video_ext|streamable\.com\/[eo]\/|loom\.com\/embed|ivoox\.com\/player|omny\.fm\/shows\/[^/]+\/[^/]+\/embed|podbean\.com\/player|buzzsprout\.com\/\d+\/\d+\?client_source|player\.megaphone\.fm|art19\.com\/shows\/[^/]+\/episodes)/i;
const YOUTUBE_RE = /youtube(?:-nocookie)?\.com\/embed/i;

export const norm = (s) => String(s || "").normalize("NFKC").toLowerCase().replace(/[\s ]+/g, " ")
  .replace(/^[\s"'“”«»]+|[\s"'“”«».:;,!?|·-]+$/g, "").trim();

const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch (_) { return ""; } };
const pathOf = (u) => { try { const x = new URL(u); return x.origin + x.pathname; } catch (_) { return String(u || ""); } };

export function isFilenameLike(name) {
  const s = String(name || "").trim();
  if (!s) return true;
  if (MEDIA_EXT_RE.test(s)) return true;
  if (SHORT_SLUG_RE.test(s)) return true;
  if (/\s/.test(s)) return false;
  return s.length >= 12 && (s.match(/\d/g) || []).length >= 3;
}

// The Captured sheet's tile title for a name (CaptureSections): a filename
// reads "Video on <host>" / "Audio on <host>".
export function shownName(c) {
  if (c.name && !isFilenameLike(c.name)) return c.name;
  const host = hostOf(c.pageUrl || c.origin || c.url);
  return `${c.kind === "audio" ? "Audio" : "Video"} on ${host}`;
}

// The media captures of a run (pictures and subtitles kept apart).
export function capturesOf(result) {
  const out = [];
  const pictures = [];
  const subtitles = [];
  const frames = new Map((result.census || []).map((c) => [c.frameId, c]));
  for (const n of result.native || []) {
    const m = n.msg;
    if (!m || typeof m !== "object" || m.kind || m.listener === "onRemoved" || m.listener === "onActivated") continue;
    const variants = Array.isArray(m.variants) ? m.variants : [];
    const url = typeof m.url === "string" ? m.url : (variants[0] && variants[0].url) || "";
    if (!url) continue;
    const frame = n.frameId == null ? null : frames.get(n.frameId);
    const c = {
      at: n.at, type: m.type || "", url, name: (m.name || "").trim(), description: m.description || "",
      img: m.img || m.thumbnail || "", origin: m.origin || m.originUrl || "", clipId: m.clipId || "",
      duration: m.duration || 0, variants: variants.length, size: m.size || 0,
      via: m.listener ? `requests.js (${m.listener})` : (n.via || ""),
      frameId: n.frameId, frameUrl: frame ? frame.url : "",
      pageUrl: result.finalUrl || result.url,
      urls: [url, ...variants.map((v) => v && v.url).filter(Boolean), ...variants.map((v) => v && v.audioUrl).filter(Boolean)],
    };
    if (m.type === "image" || m.type === "svg") { pictures.push(c); continue; }
    if (m.type === "subtitle" || m.type === "timedtext" || SUBTITLE_RE.test(url)) { subtitles.push(c); continue; }
    const audioOnly = variants.length > 0 && variants.every((v) => v && v.audioOnly);
    c.kind = (audioOnly || AUDIO_RE.test(url)) ? "audio" : "video";
    out.push(c);
  }
  return { media: out, pictures, subtitles };
}

// The repository's dedup (BrowserDownloadRepository.isPresent): per tab, the
// FIRST capture of a URL wins — same uid (an HLS master's is its page origin
// plus clip id), the same URL but for a fragment, trailing slash or "www.",
// or a URL an earlier capture lists as a rendition. The later copy is
// dropped, its name with it: CNN's untitled wire copy beat the content
// script's titled report this way. One exception (CaptureRenditions.covers):
// a capture offering several renditions replaces a single capture of one
// of them.
export const trivial = (u) => {
  let out = String(u || "");
  const hash = out.indexOf("#");
  if (hash >= 0) out = out.slice(0, hash);
  if (out.endsWith("/")) out = out.slice(0, -1);
  return out.replace(/^(\w+:\/\/)www\./i, "$1");
};
// The repository's fold (CaptureRenditions.otherRendition): a SINGLE
// progressive video capture whose URL differs from a listed one's only in a
// resolution token of the path ("480x270_MP4_<id>.mp4" / "640x360_MP4_<id>.mp4",
// "clip_720p.mp4", "/720/") is another rendition of that clip, on the same
// page: it joins the listed capture, which keeps its name and place and
// gains a quality picker. Without it a quality ladder read as N duplicate
// tiles (Daily Mail's front page, four renditions of one clip). The
// patterns are the Java ones; keep the two in step. The Java fold also
// needs the probe's heights and durations to agree, and the audit runs no
// probe: durations are compared when both emits carry one, and a differing
// token stands in for a differing height.
const FRAME_SIZE = /(?<![a-z0-9])\d{2,5}x\d{2,5}(?![a-z0-9])/g;
const HEIGHT_P = /(?<![a-z0-9])\d{3,4}p(?:\d{2})?(?![a-z0-9])/g;
const DIMENSION = new RegExp("(?<![a-z0-9])(?:144|180|240|270|288|360|368|432|480|540|576|640|720|768|854|900"
  + "|960|1024|1080|1152|1280|1440|1600|1920|2048|2160|2560|2880|3840|4096|4320)(?![a-z0-9])", "g");
const NAMED_RUNG = /(?<![a-z0-9])(?:sd|hd|fhd|uhd|hq|lq|4k)(?![a-z0-9])/g;
const MASK = "\u0000";
const MANIFEST_RE = /\.(?:m3u8|mpd)$/i;

function hostAndPath(url) {
  const lower = String(url || "").toLowerCase();
  const m = /^https?:\/\/([^/?#]*)([^?#]*)/.exec(lower);
  if (!m) return null;
  return [m[1].replace(/^www\./, ""), m[2] || "/"];
}

const maskResolution = (path) => path.replace(FRAME_SIZE, MASK).replace(HEIGHT_P, MASK)
  .replace(DIMENSION, MASK).replace(NAMED_RUNG, MASK);

export function onlyResolutionDiffers(a, b) {
  const pa = hostAndPath(a);
  const pb = hostAndPath(b);
  if (!pa || !pb || pa[0] !== pb[0] || pa[1] === pb[1]) return false;
  const ma = maskResolution(pa[1]);
  return ma.includes(MASK) && ma === maskResolution(pb[1]);
}

// Durations in ms (the emit's unit): within 1% of the longer, at least 250 ms
// and at most 1 s apart. Unknown on either side passes: the probe would
// supply it on the phone.
function closeDurations(a, b) {
  if (!(a > 0) || !(b > 0)) return true;
  return Math.abs(a - b) <= Math.max(250, Math.min(1000, Math.max(a, b) / 100));
}

// Two WxH tokens must name one frame shape (3%); anything else passes.
function sameShape(a, b) {
  const sa = /(\d{2,5})x(\d{2,5})/.exec(hostAndPath(a)?.[1] || "");
  const sb = /(\d{2,5})x(\d{2,5})/.exec(hostAndPath(b)?.[1] || "");
  if (!sa || !sb) return true;
  const ra = sa[1] / sa[2];
  const rb = sb[1] / sb[2];
  return Math.abs(ra - rb) <= 0.03 * Math.max(ra, rb);
}

const isSingleProgressiveVideo = (c) => c.kind === "video" && c.type !== "hls-master" && c.variants <= 1
  && !MANIFEST_RE.test(hostAndPath(c.url)?.[1] || "");

function otherRendition(l, c) {
  if (!isSingleProgressiveVideo(c) || l.kind !== "video" || l.type === "hls-master"
    || MANIFEST_RE.test(hostAndPath(l.url)?.[1] || "")) return false;
  if (trivial(l.origin || l.pageUrl) !== trivial(c.origin || c.pageUrl)) return false;
  if (!closeDurations(l.duration, c.duration)) return false;
  return l.urls.some((u) => onlyResolutionDiffers(u, c.url) && sameShape(u, c.url));
}

export function sheetOf(media) {
  const listed = [];
  const dropped = [];
  for (const c of [...media].sort((a, b) => (a.at || 0) - (b.at || 0))) {
    c.uid = c.type === "hls-master" && c.origin ? `o:${c.origin}#${c.clipId || ""}` : `u:${c.url}`;
    const key = trivial(c.url);
    const i = listed.findIndex((l) => l.uid === c.uid || trivial(l.url) === key || l.urls.some((u) => trivial(u) === key)
      || (c.variants >= 2 && l.variants <= 1 && c.urls.some((u) => trivial(u) === trivial(l.url))));
    if (i < 0) {
      const f = listed.findIndex((l) => otherRendition(l, c));
      if (f >= 0) {
        const l = listed[f];
        // The fold keeps the listed name and place; a length it did not
        // know comes from the rendition (the probe would have given it).
        listed[f] = { ...l, urls: [...l.urls, c.url], variants: Math.max(l.variants, 1) + 1, duration: l.duration || c.duration };
        dropped.push({ ...c, dupOf: l.url, folded: true });
        continue;
      }
      listed.push(c);
      continue;
    }
    const l = listed[i];
    if (c.variants >= 2 && l.variants <= 1 && c.urls.some((u) => trivial(u) === trivial(l.url))) {
      listed[i] = c;
      dropped.push({ ...l, dupOf: c.url });
      continue;
    }
    dropped.push({ ...c, dupOf: l.url });
  }
  return { listed, dropped };
}

export function judge(result) {
  const all = capturesOf(result);
  const { pictures, subtitles } = all;
  const ads = all.media.filter((c) => AD_RE.test(c.url));
  const { listed: media, dropped } = sheetOf(all.media.filter((c) => !AD_RE.test(c.url)));
  const top = (result.census || []).find((c) => c.frameId === 0) || {};
  const pageNames = new Set([top.title, top.ogTitle].map(norm).filter(Boolean));
  // "Title | Site" and "Title - Site": the site's part alone is a name too.
  const siteNames = new Set([top.siteName].map(norm).filter(Boolean));
  for (const t of [top.title, top.ogTitle]) {
    const parts = String(t || "").split(/\s+[|·–—-]\s+/);
    if (parts.length > 1) siteNames.add(norm(parts[parts.length - 1]));
  }
  const issues = [];
  const notes = [];
  const multi = media.length >= 2;

  for (const c of media) {
    c.issues = [];
    c.shown = shownName(c);
    const n = norm(c.name);
    if (!c.name) c.issues.push("untitled");
    else if (isFilenameLike(c.name)) c.issues.push("filename");
    else if (GENERIC_RE.test(c.name.trim())) c.issues.push("generic");
    else if (siteNames.has(n)) c.issues.push("site-name");
    else if (multi && pageNames.has(n)) c.issues.push("page-title");
    if (c.kind === "video" && !c.img) c.notes = ["no-picture"];
    // A later copy of the same clip that carried a better name, dropped by
    // the dedup: the name was there, the order lost it.
    const better = dropped.find((d) => d.dupOf === c.url && d.name && !isFilenameLike(d.name) && norm(d.name) !== n);
    if (better && c.issues.length) c.lostName = { name: better.name, via: better.via, laterMs: (better.at || 0) - (c.at || 0) };
  }
  // Same name on several tiles.
  const byName = new Map();
  for (const c of media) {
    const key = norm(c.shown);
    if (!key) continue;
    (byName.get(key) ?? byName.set(key, []).get(key)).push(c);
  }
  for (const [, list] of byName) {
    if (list.length < 2) continue;
    for (const c of list) if (!c.issues.includes("duplicate")) c.issues.push("duplicate");
    issues.push({ kind: "duplicate", text: `${list.length} tiles named "${list[0].shown}"`,
      urls: list.map((c) => c.url) });
  }
  for (const c of media) {
    for (const k of c.issues) {
      if (k === "duplicate") continue;
      const lost = c.lostName ? ` (a copy named ${JSON.stringify(c.lostName.name)} came ${c.lostName.laterMs} ms later via ${c.lostName.via} and was dropped as a duplicate)` : "";
      issues.push({ kind: k, text: `${c.kind} named ${JSON.stringify(c.shown)}${lost}`, url: c.url });
    }
  }

  // ── the census: players the captures do not account for ──
  const captured = new Set();
  for (const c of [...media, ...dropped, ...subtitles]) for (const u of c.urls || [c.url]) { captured.add(u); captured.add(pathOf(u)); }
  // A file the page-state bridge folded into a capture (a Brightcove tech
  // playing one pmp4 rendition of the HLS master the bridge posted, listed
  // as the group's siblings and claimed) is that capture, not a miss.
  for (const r of result.reports || []) {
    if (!String(r.kind || "").startsWith("page-state")) continue;
    const p = r.m?.payload || {};
    for (const u of [p.url, ...(p.siblings || []), ...(p.variants || []).flatMap((v) => [v?.url, v?.audioUrl])]) {
      if (typeof u === "string" && u) { captured.add(u); captured.add(pathOf(u)); }
    }
  }
  const byFrame = new Map();
  for (const c of media) if (c.frameId != null) byFrame.set(c.frameId, (byFrame.get(c.frameId) || 0) + 1);
  const rejectOf = (url) => {
    for (const line of result.logs || []) {
      if (!line.includes(url.slice(0, 120))) continue;
      const m = /\[req\] (reject:[\w-]+|cs-skip:[\w-]+)/.exec(line);
      if (m) return m[1];
    }
    return "";
  };
  const descendants = (frameId) => {
    const out = new Set([frameId]);
    let grew = true;
    while (grew) {
      grew = false;
      for (const f of result.census || []) {
        if (out.has(f.parentFrameId) && !out.has(f.frameId)) { out.add(f.frameId); grew = true; }
      }
    }
    return out;
  };
  for (const frame of result.census || []) {
    let mse = 0;
    const mseEls = [];
    for (const el of frame.media || []) {
      const srcs = [el.src, ...(el.sources || [])].filter(Boolean);
      if (srcs.some((s) => AD_RE.test(s))) continue;
      const tiny = el.tag === "video" && el.w * el.h > 0 && (el.w < 80 || el.h < 45);
      if (tiny) continue;
      const http = srcs.filter((s) => /^https?:/.test(s));
      if (http.length) {
        if (http.some((s) => captured.has(s) || captured.has(pathOf(s)))) continue;
        const why = rejectOf(http[0]);
        issues.push({ kind: `missed-${el.tag}`, possible: true,
          text: `<${el.tag}> playing ${http[0].slice(0, 140)} has no capture${why ? ` (${why})` : ""}`,
          frameUrl: frame.url });
      } else if (/^blob:/.test(el.src || "") || (el.inPlayer && !el.src)) {
        if (el.tag === "video" && el.duration > 0 && el.duration < 4) continue;   // an ad bumper or a loop sprite
        mse++;
        mseEls.push(el);
      }
    }
    if (mse > 0) {
      let have = 0;
      for (const id of descendants(frame.frameId)) have += byFrame.get(id) || 0;
      if (have < mse) {
        issues.push({ kind: "missed-player", possible: true,
          text: `${mse} streaming player(s) in ${frame.frameId === 0 ? "the page" : "frame " + frame.url.slice(0, 100)}, ${have} capture(s) from it`,
          frameUrl: frame.url, posters: mseEls.map((e) => e.poster).filter(Boolean).slice(0, 3) });
      }
    }
    for (const ifr of frame.iframes || []) {
      if (!EMBED_RE.test(ifr.src)) continue;
      if (YOUTUBE_RE.test(ifr.src)) {
        notes.push({ kind: "youtube-embed", text: `YouTube embed (youtube@, not simulated): ${ifr.src.slice(0, 100)}` });
        continue;
      }
      const child = (result.census || []).find((f) => f.parentFrameId === frame.frameId && sameEmbed(f.url, ifr.src));
      let have = 0;
      if (child) for (const id of descendants(child.frameId)) have += byFrame.get(id) || 0;
      if (have) continue;
      const token = embedToken(ifr.src);
      if (token && media.some((c) => [c.url, c.origin, ...c.urls].some((u) => String(u).includes(token)))) continue;
      issues.push({ kind: "missed-embed", possible: true,
        text: `${ifr.loaded ? "" : "lazy "}embed ${ifr.src.slice(0, 140)} has no capture`, frameUrl: frame.url });
    }
  }

  const unnamedPictures = pictures.filter((p) => !p.name);
  if (pictures.length) {
    notes.push({ kind: "pictures", text: `${pictures.length - unnamedPictures.length} of ${pictures.length} pictures named` });
  }
  if (ads.length) notes.push({ kind: "ads", text: `${ads.length} capture(s) from ad hosts left out (uBlock blocks them on the phone)` });
  const noPic = media.filter((c) => c.notes?.includes("no-picture")).length;
  if (noPic) notes.push({ kind: "no-picture", text: `${noPic} video(s) without a picture (the app decodes a frame)` });
  return {
    url: result.url, finalUrl: result.finalUrl, title: top.title || "", ms: result.ms,
    media, dropped: dropped.map((d) => ({ url: d.url, name: d.name, via: d.via, at: d.at, dupOf: d.dupOf })),
    subtitles: subtitles.map(slim), pictures: { total: pictures.length, named: pictures.length - unnamedPictures.length,
      unnamed: unnamedPictures.slice(0, 15).map((p) => p.url) },
    issues, notes, consent: result.consentClicks || [], rejects: result.rejects || {}, errors: result.errors || [],
    warnings: result.warnings || [],
    players: (result.census || []).reduce((n, f) => n + (f.media || []).length, 0),
  };
}

function slim(c) {
  return { url: c.url, name: c.name, frameId: c.frameId };
}
function sameEmbed(frameUrl, src) {
  return pathOf(frameUrl) === pathOf(src) || (embedToken(src) && String(frameUrl).includes(embedToken(src)));
}
// The id an embed URL names its clip by (a path segment or video= query).
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

// ── baseline comparison (replay mode) ────────────────────────────────────
// A capture is the same capture in both runs when its clip id, or its URL
// without the query, is the same.
const keyOf = (c) => c.clipId ? `clip:${c.clipId}` : `url:${pathOf(c.url)}`;
export function compare(before, after) {
  const b = new Map(before.media.map((c) => [keyOf(c), c]));
  const a = new Map(after.media.map((c) => [keyOf(c), c]));
  const changes = [];
  for (const [k, c] of a) {
    const old = b.get(k);
    if (!old) { changes.push({ kind: "new", after: c }); continue; }
    if (old.shown !== c.shown) changes.push({ kind: "renamed", before: old, after: c });
    else if (old.issues.join() !== c.issues.join()) changes.push({ kind: "issues", before: old, after: c });
    if (!!old.img !== !!c.img) changes.push({ kind: c.img ? "picture" : "picture-lost", before: old, after: c });
  }
  for (const [k, c] of b) if (!a.has(k)) changes.push({ kind: "lost", before: c });
  const count = (r) => r.issues.length;
  const verdict = count(after) < count(before) || (count(after) === count(before) && after.media.length > before.media.length)
    ? "better" : (count(after) > count(before) || after.media.length < before.media.length ? "worse" : "same");
  return { verdict, issuesBefore: count(before), issuesAfter: count(after),
    mediaBefore: before.media.length, mediaAfter: after.media.length, changes };
}
