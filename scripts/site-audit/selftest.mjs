// selftest.mjs — the site audit, tested: the judge's rules on synthetic
// results (no browser), then a LIVE audit of local fixture pages (a front
// page whose clips have no titles of their own, a consent wall in front of
// a player, a cross-origin embed, a streaming player nothing captures), a
// replay of that recording against the same code (nothing may change), and
// a replay with a broken content script (the replay must call it worse).
//
//   node scripts/site-audit/selftest.mjs
// Needs Playwright + Chromium for the browser half (SKIPs it without).
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { judge, sheetOf, capturesOf, embedToken } from "./judge.mjs";
import { loadPlaywright } from "./sim.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "../..");
let pass = 0;
let fail = 0;
const check = (name, ok, detail = "") => {
  if (ok) pass++;
  else fail++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${ok || !detail ? "" : `\n      ${detail}`}`);
};

// ── 1. the judge, on synthetic results ───────────────────────────────────
const top = (extra = {}) => ({ frameId: 0, parentFrameId: -1, url: "https://news.example/", title: "Home | News Example",
  ogTitle: "Home | News Example", siteName: "News Example", media: [], iframes: [], players: 0, ...extra });
const emit = (at, msg, frameId = 0) => ({ at, msg: { type: "media", tabId: 1, ...msg }, frameId, via: "requests.js" });
{
  const r = judge({ url: "https://news.example/", finalUrl: "https://news.example/", census: [top()], native: [
    emit(10, { url: "https://cdn.example/a.mp4", name: "Floods hit the coast", img: "https://cdn.example/a.jpg" }),
    emit(20, { url: "https://cdn.example/b.mp4", name: "Home | News Example" }),
    emit(30, { url: "https://cdn.example/c.mp4", name: "Home | News Example" }),
    emit(40, { url: "https://cdn.example/d.mp4", name: "6aaaf5204d3859992c30a380.mp4" }),
    emit(50, { url: "https://cdn.example/e.mp4", name: "Video Player" }),
    emit(60, { url: "https://cdn.example/f.mp4", name: "News Example" }),
    emit(70, { url: "https://cdn.example/g/master.m3u8", type: "hls-master", origin: "https://news.example/", clipId: "g" }),
  ] });
  const by = (u) => r.media.find((c) => c.url.includes(u));
  check("judge: a clip with its own title has no issue", by("a.mp4").issues.length === 0, JSON.stringify(by("a.mp4").issues));
  check("judge: the page title on a multi-clip page is flagged", by("b.mp4").issues.includes("page-title"));
  check("judge: two tiles with one name are flagged duplicate, once per name", by("b.mp4").issues.includes("duplicate")
    && by("c.mp4").issues.includes("duplicate")
    && r.issues.filter((i) => i.kind === "duplicate" && i.text.includes("Home | News Example")).length === 1);
  check("judge: a file name and no name both read 'Video on <host>': a duplicate too",
    by("d.mp4").issues.includes("duplicate") && by("master.m3u8").issues.includes("duplicate"));
  check("judge: an upload file name is flagged", by("d.mp4").issues.includes("filename"));
  check("judge: a player's label is flagged generic", by("e.mp4").issues.includes("generic"));
  check("judge: the site's name is flagged", by("f.mp4").issues.includes("site-name"));
  check("judge: no name reads 'Video on <host>' and is flagged", by("master.m3u8").issues.includes("untitled")
    && by("master.m3u8").shown === "Video on news.example", by("master.m3u8").shown);
}
{
  // The same URL twice is ONE tile (first wins), so no duplicate; when the
  // first had no name and the second did, the judge says so.
  const all = capturesOf({ native: [
    emit(10, { url: "https://cdn.example/a.mp4", listener: "onHeadersReceived" }),
    emit(90, { url: "https://cdn.example/a.mp4", name: "Floods hit the coast", listener: "contentScript" }),
  ], census: [top()] });
  const { listed, dropped } = sheetOf(all.media);
  check("sheet: a URL's second capture is dropped (first wins)", listed.length === 1 && dropped.length === 1 && !listed[0].name);
  const r = judge({ url: "https://news.example/", census: [top()], native: [
    emit(10, { url: "https://cdn.example/a.mp4", listener: "onHeadersReceived" }),
    emit(90, { url: "https://cdn.example/a.mp4#t=1", name: "Floods hit the coast", listener: "contentScript" }),
  ] });
  check("judge: the dropped copy's better name is reported", r.media.length === 1 && r.media[0].issues.includes("untitled")
    && /came 80 ms later/.test(r.issues[0].text) && !r.media[0].issues.includes("duplicate"), r.issues[0]?.text);
  const cover = sheetOf(capturesOf({ native: [
    emit(10, { url: "https://cdn.example/v_720p.mp4", name: "Clip" }),
    emit(20, { url: "https://cdn.example/v_1080p.mp4", type: "variants", name: "Clip",
      variants: [{ url: "https://cdn.example/v_1080p.mp4" }, { url: "https://cdn.example/v_720p.mp4" }] }),
  ], census: [top()] }).media);
  check("sheet: a capture with renditions replaces a single one of them", cover.listed.length === 1 && cover.listed[0].variants === 2);
  // Daily Mail's front page: one clip at four sizes, each a separate single
  // capture. The repository folds them into one tile with a quality picker
  // (CaptureRenditions.otherRendition); another clip, another host or another
  // length stays its own tile.
  const dm = (n, size, at, extra = {}) => emit(at, { url: `https://video.example.com/mol/${n}/${size}_MP4_${n}.mp4`, ...extra });
  const ladder = sheetOf(capturesOf({ native: [
    dm(111, "480x270", 10, { name: "Police officer shot" }), dm(111, "640x360", 20), dm(111, "960x540", 30),
    dm(111, "1024x576", 40), dm(222, "640x360", 50),
    emit(60, { url: "https://other.example/mol/111/960x540_MP4_111.mp4" }),
    dm(111, "1280x720", 70, { duration: 90000 }), dm(111, "1920x1080", 80, { duration: 30000 }),
  ], census: [top()] }).media);
  const clip = ladder.listed.find((c) => c.url.includes("/111/480x270"));
  check("sheet: renditions of one clip fold into one tile; another clip, host or length does not",
    ladder.listed.length === 4 && clip && clip.variants === 5 && clip.name === "Police officer shot"
    && ladder.dropped.filter((d) => d.folded).length === 4,
    ladder.listed.map((c) => `${c.url} ×${c.variants}`).join(", "));
}
{
  // The census side: a <video> file nobody captured, a streaming player,
  // an embed (captured by its id), an ad, YouTube.
  const census = [top({ media: [
    { tag: "video", src: "https://cdn.example/lost.mp4", sources: [], w: 320, h: 180, duration: 30 },
    { tag: "video", src: "https://pubads.g.doubleclick.net/ad.mp4", sources: [], w: 320, h: 180, duration: 15 },
    { tag: "video", src: "blob:https://news.example/1", sources: [], w: 320, h: 180, duration: 60 },
  ], iframes: [
    { src: "https://player.vimeo.com/video/76979871?h=8272103f6e", loaded: true },
    { src: "https://geo.dailymotion.com/player/x8abc.html?video=x9kq2zz", loaded: false },
    { src: "https://www.youtube.com/embed/dQw4w9WgXcQ", loaded: true },
  ] })];
  const r = judge({ url: "https://news.example/", census, native: [
    emit(10, { url: "https://cdn.example/other.mp4", name: "A clip" }),
    emit(20, { url: "https://dm.example/master.m3u8", type: "hls-master", origin: "https://www.dailymotion.com/video/x9kq2zz", name: "DM clip" }),
  ] });
  const kinds = r.issues.map((i) => i.kind);
  check("census: a <video> file with no capture is a possible miss", r.issues.some((i) => i.kind === "missed-video" && i.text.includes("lost.mp4")));
  check("census: an ad's video is not", !r.issues.some((i) => i.text.includes("doubleclick")));
  check("census: one streaming player and one capture from its page is accounted for", kinds.includes("missed-player") === false,
    "one MSE player, one capture from the page: accounted for");
  check("census: an embed with no capture is a possible miss", r.issues.some((i) => i.kind === "missed-embed" && i.text.includes("vimeo")));
  check("census: an embed captured under its id is accounted for", !r.issues.some((i) => i.text.includes("dailymotion")));
  check("census: YouTube embeds are a note, not a miss", r.notes.some((n) => n.kind === "youtube-embed") && !r.issues.some((i) => i.text.includes("youtube")));
  check("census: embed ids", embedToken("https://player.vimeo.com/video/76979871?h=8272103f6e") === "76979871"
    && embedToken("https://geo.dailymotion.com/player/x8abc.html?video=x9kq2zz") === "x9kq2zz");
  const r2 = judge({ url: "https://news.example/", census: [top({ media: [
    { tag: "video", src: "blob:https://news.example/1", sources: [], w: 320, h: 180, duration: 60 },
    { tag: "video", src: "blob:https://news.example/2", sources: [], w: 320, h: 180, duration: 90 },
  ] })], native: [emit(10, { url: "https://cdn.example/x.m3u8", name: "One" })] });
  check("census: two streaming players and one capture is a possible miss", r2.issues.some((i) => i.kind === "missed-player"));
  const r3 = judge({ url: "https://news.example/", census: [top({ media: [
    { tag: "video", src: "https://cdn.example/clip_720.mp4", sources: [], w: 320, h: 180, duration: 60 },
  ] })], native: [emit(10, { url: "https://cdn.example/clip/master.m3u8", type: "hls-master", origin: "https://news.example/", clipId: "c1", name: "A clip" })],
  reports: [{ kind: "page-state-hls", m: { kind: "page-state-hls", payload: { url: "https://cdn.example/clip/master.m3u8",
    siblings: ["https://cdn.example/clip_720.mp4"] } } }] });
  const r4 = judge({ url: "https://news.example/", census: [top()], native: [
    emit(10, { url: "https://cdn.example/a.mp4", name: "Floods hit the coast" }),
    emit(20, { url: "https://s0.2mdn.net/videoplayback/ad.mp4", name: "Home | News Example" }),
  ] });
  check("judge: an ad host's video is left out, as uBlock leaves it out", r4.media.length === 1 && !r4.issues.length
    && r4.notes.some((n) => n.kind === "ads"), JSON.stringify({ media: r4.media.length, issues: r4.issues }));
  check("census: a file the bridge folded into its capture is not a miss", !r3.issues.some((i) => i.kind.startsWith("missed")),
    JSON.stringify(r3.issues));
}

// ── 2. live audit of local pages, replay, mutant replay ──────────────────
const pw = await loadPlaywright();
if (!pw) {
  console.log("SKIP: browser half (playwright not installed)");
  finish();
}
// One launch first: a missing or broken browser is one failure, named,
// rather than every check below failing on the same cause.
try {
  const b = await pw.chromium.launch();
  await b.close();
} catch (e) {
  const why = String(e?.message || e).split("\n").filter((l) => l.trim() && !/^\s*[║╔╚═]/.test(l)).slice(0, 4).join("\n      ");
  check("browser: chromium starts (sh scripts/setup-playwright.sh, or FIREDOWN_CHROMIUM=/usr/bin/chromium)", false, why);
  finish();
}

const MP4 = Buffer.alloc(4096, 7);
const page = (title, body, extraHead = "") => `<!doctype html><html><head><meta charset=utf-8>
<title>${title}</title>${extraHead}</head><body>${body}</body></html>`;
let phoneUa = "";
const server = createServer((req, res) => {
  const u = new URL(req.url, "http://x");
  const host = req.headers.host || "";
  const html = (s) => { res.writeHead(200, { "content-type": "text/html; charset=utf-8" }); res.end(s); };
  if (u.pathname.startsWith("/media/")) {
    const range = /bytes=(\d+)-/.exec(req.headers.range || "");
    if (range) {
      const start = Number(range[1]);
      res.writeHead(206, { "content-type": "video/mp4", "accept-ranges": "bytes",
        "content-range": `bytes ${start}-${MP4.length - 1}/${MP4.length}`, "content-length": MP4.length - start });
      return res.end(req.method === "HEAD" ? undefined : MP4.subarray(start));
    }
    res.writeHead(200, { "content-type": "video/mp4", "content-length": MP4.length, "accept-ranges": "bytes" });
    return res.end(req.method === "HEAD" ? undefined : MP4);
  }
  if (u.pathname.startsWith("/img/")) {
    res.writeHead(200, { "content-type": "image/gif" });
    return res.end(Buffer.from("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", "base64"));
  }
  const vid = (src, extra = "") => `<video src="${src}" muted autoplay playsinline width="320" height="180" ${extra}></video>`;
  if (u.pathname === "/multi/") {
    return html(page("Front page | Selftest News", `
      <article><h3>Card one headline</h3>${vid("/media/one.mp4", 'poster="/img/p1.gif"')}</article>
      <div class="module">${vid("/media/two.mp4")}</div>
      <div class="module">${vid("/media/three.mp4")}</div>`,
    '<meta property="og:title" content="Front page | Selftest News"><meta property="og:site_name" content="Selftest News">'));
  }
  if (u.pathname === "/phone/") {
    // The page names its one clip after what it sees, so the check runs
    // through the real capture path: what a site would lay itself out by.
    phoneUa = req.headers["user-agent"] || "";
    return html(page("phone", `${vid("/media/phone.mp4")}
      <script>document.title = [
        "w" + innerWidth, "coarse" + +matchMedia("(pointer: coarse)").matches, "hover" + +matchMedia("(hover: hover)").matches,
        "touch" + navigator.maxTouchPoints, "plat=" + navigator.platform, "uad" + +("userAgentData" in navigator),
        "chrome=" + typeof window.chrome, "wd" + +navigator.webdriver, "ua" + +/Android.*Mobile.*Firefox/.test(navigator.userAgent),
      ].join(" ");</script>`, '<meta name="viewport" content="width=device-width, initial-scale=1">'));
  }
  if (u.pathname === "/consent/") {
    return html(page("Clip behind a wall", `<div id="wall"><p>We use cookies.</p><button id="ok">Aceptar</button></div><div id="slot"></div>
      <script>document.getElementById("ok").onclick = () => {
        document.getElementById("wall").remove();
        const v = document.createElement("video");
        v.src = "/media/wall.mp4"; v.muted = true; v.autoplay = true; v.width = 320; v.height = 180;
        document.getElementById("slot").append(v);
      };</script>`));
  }
  if (u.pathname === "/embed/") {
    const port = host.split(":")[1];
    return html(page("An article with an embed", `<h1>An article</h1><p>Text.</p>
      <iframe src="http://localhost:${port}/player/?v=abc12345" width="320" height="200"></iframe>`));
  }
  if (u.pathname === "/player/") {
    return html(page("Embedded clip title", vid("/media/emb.mp4")));
  }
  if (u.pathname === "/mse/") {
    return html(page("Streaming page", `<h1>Live</h1><video id="v" width="320" height="180"></video>
      <script>document.getElementById("v").src = URL.createObjectURL(new MediaSource());</script>`));
  }
  res.writeHead(404);
  res.end();
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;
const base = `http://127.0.0.1:${port}`;
const out = mkdtempSync(join(tmpdir(), "site-audit-selftest-"));
// Async: this process serves the fixture pages, so it must not block while
// the audit loads them.
const audit = (extra) => new Promise((done) => {
  const p = spawn(process.execPath, [join(root, "scripts/site-audit.mjs"), ...extra,
    "--browser", "chromium", "--jobs", "4", "--wait", "2500"], { stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  p.stdout.on("data", (d) => { stdout += d; });
  p.stderr.on("data", (d) => { stderr += d; });
  p.on("exit", (status) => done({ status, stdout, stderr }));
});

const live = await audit([`${base}/multi/`, `${base}/consent/`, `${base}/embed/`, `${base}/mse/`, `${base}/phone/`, "--out", out]);
const judged = (slug) => {
  try { return JSON.parse(readFileSync(join(out, slug, "judged.json"), "utf8")); } catch (_) { return null; }
};
const multi = judged("127.0.0.1_multi");
const consent = judged("127.0.0.1_consent");
const embed = judged("127.0.0.1_embed");
const mse = judged("127.0.0.1_mse");
const phone = judged("127.0.0.1_phone");
if (!multi || multi.error) console.log(live.stdout, live.stderr);
const named = (j, u) => j?.media?.find((c) => c.url.includes(u));
check("live: the audit itself exits cleanly", live.status === 0, (live.stdout + live.stderr).slice(-1500));
check("live: three clips on the multi page, one tile each", multi?.media?.length === 3, JSON.stringify(multi?.media?.map((c) => c.url)));
check("live: the clip under a headline takes it", named(multi, "one.mp4")?.shown === "Card one headline"
  && named(multi, "one.mp4")?.issues.length === 0, JSON.stringify(named(multi, "one.mp4")));
check("live: the two clips with no title of their own are flagged (page title, duplicate)",
  ["two.mp4", "three.mp4"].every((u) => named(multi, u)?.issues.includes("page-title") && named(multi, u)?.issues.includes("duplicate")),
  JSON.stringify(multi?.media?.map((c) => [c.url.slice(-9), c.shown, c.issues])));
check("live: the wire path captures (the player's own fetch reaches the catcher)",
  multi?.media?.some((c) => /onHeadersReceived|onResponseStarted/.test(c.via)), JSON.stringify(multi?.media?.map((c) => c.via)));
check("live: the consent wall is clicked and the clip behind it captured",
  consent?.consent?.length > 0 && named(consent, "wall.mp4") && !consent.issues.some((i) => !i.possible),
  JSON.stringify({ consent: consent?.consent, media: consent?.media?.map((c) => c.url) }));
check("live: a clip in a cross-origin frame is captured from that frame, named by it",
  named(embed, "emb.mp4")?.frameId > 0 && named(embed, "emb.mp4")?.shown === "Embedded clip title",
  JSON.stringify(named(embed, "emb.mp4")));
check("live: a streaming player nothing captured is a possible miss",
  mse?.issues?.some((i) => i.kind === "missed-player"), JSON.stringify(mse?.issues));
// What Firefox for Android shows; Chromium's phone emulation left the
// platform, the touch points, userAgentData, window.chrome and webdriver
// saying desktop Chrome under automation.
const PHONE_SEES = "w412 coarse1 hover0 touch5 plat=Linux aarch64 uad0 chrome=undefined wd0 ua1";
check("live: the page sees a phone running Firefox for Android", named(phone, "phone.mp4")?.shown === PHONE_SEES
  && /Android.*Mobile.*Firefox/.test(phoneUa), JSON.stringify({ shown: named(phone, "phone.mp4")?.shown, phoneUa }));
check("live: the run is recorded (HAR + report)", (() => {
  try { readFileSync(join(out, "127.0.0.1_multi", "page.har.zip")); readFileSync(join(out, "report.md")); return true; } catch (_) { return false; }
})());

const replay = await audit(["--replay", out]);
check("replay: the same code against its own recording is not worse", replay.status === 0 && !/↓/.test(replay.stdout)
  && /replay: 5 recorded site\(s\)/.test(replay.stdout),
  replay.stdout.slice(-1500));

// A content script whose metadata is gone: every DOM title disappears.
const csPath = join(root, "app/src/main/assets/webrequests/js/content-script.js");
const cs = readFileSync(csPath, "utf8");
const brokenCs = cs.replace("collectMediaMeta = buildPageMetadata;", "collectMediaMeta = () => ({});")
  .replace("return Promise.resolve(buildPageMetadata(msg.mediaUrl));", "return Promise.resolve({});");
check("mutant: the broken content script differs", brokenCs !== cs && brokenCs.includes("collectMediaMeta = () => ({});"));
const mutantPath = join(out, "broken-content-script.js");
writeFileSync(mutantPath, brokenCs);
const mutant = await audit(["--replay", out, "--only", "multi", "--cs", mutantPath]);
check("replay: a broken content script is called worse (exit 1)", mutant.status === 1 && /↓/.test(mutant.stdout),
  mutant.stdout.slice(-1500));

server.close();
if (!fail) rmSync(out, { recursive: true, force: true });
else console.log(`(kept ${out})`);
finish();

function finish() {
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
}
