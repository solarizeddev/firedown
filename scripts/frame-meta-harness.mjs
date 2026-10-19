// frame-meta-harness.mjs — per-clip capture metadata across FRAMES, in a real
// browser. The REAL js/content-script.js runs in every frame of locally served
// pages (a stubbed `browser`), and the REAL pickCaptureMeta from requests.js
// ranks what it answers, so a check fails exactly when a capture would be
// misnamed on a device.
//
// Why a browser and not the smoke's vm: the bug this pins lives BETWEEN frames
// (a clip played in an iframe, declared by the page that embeds it — the
// firedown.app /demo showcase titled every clip "Firedown · demo"), and only a
// real DOM has frames, origins, base URLs and sandboxing.
//
// Engine caveat: this runs Chromium, where the script is a page script. On a
// device it is a Firefox content script reading through Xray wrappers; the
// same-origin parent read is the one behaviour to confirm there
// (adb logcat, or a firedown.app/demo capture showing per-clip names).
//
// Needs Playwright + its Chromium (`npm i -g playwright && npx playwright
// install chromium`); SKIPs cleanly without them.
//   node scripts/frame-meta-harness.mjs [path/to/content-script.js]
// The optional path runs another revision (proving the checks have teeth).
import { readFileSync } from "node:fs";
import http from "node:http";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ext = join(root, "app/src/main/assets/webrequests");
const csPath = process.argv[2] || join(ext, "js/content-script.js");
const cs = readFileSync(csPath, "utf8");

// pickCaptureMeta is a pure exported function; lift it out of the module
// source rather than import requests.js (which registers every listener).
const reqSrc = readFileSync(join(ext, "js/requests.js"), "utf8");
const pickSrc = reqSrc.match(/export function pickCaptureMeta\(meta\) \{[\s\S]*?\n\}\n/);
if (!pickSrc) {
  console.error("FAIL: pickCaptureMeta not found in requests.js");
  process.exit(1);
}
const pickCaptureMeta = new Function(`${pickSrc[0].replace(/^export /, "")}; return pickCaptureMeta;`)();

async function loadChromium() {
  try {
    return (await import("playwright")).chromium;
  } catch (_) { /* fall through to the global install */ }
  try {
    const globalRoot = execFileSync("npm", ["root", "-g"], { encoding: "utf8" }).trim();
    return createRequire(join(globalRoot, "noop.js"))("playwright").chromium;
  } catch (_) {
    return null;
  }
}

const chromium = await loadChromium();
if (!chromium) {
  console.log("SKIP: playwright not installed (npm i -g playwright)");
  process.exit(0);
}

// ── Pages, keyed by host + path. a.test and b.test are two ORIGINS on one
// server (Chromium maps *.test to 127.0.0.1). ──
const P = "__PORT__";
const A = `http://a.test:${P}`;
const B = `http://b.test:${P}`;
const block = (items) =>
  `<script type="application/firedown+json">${JSON.stringify(items)}</script>`;
const ld = (obj) => `<script type="application/ld+json">${JSON.stringify(obj)}</script>`;
const html = (title, head, body) =>
  `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>${head}</head><body>${body}</body></html>`;

// A Video.js player root as the page has it once the player is set up: the
// root labelled "Video Player", a tech <video> with no URL of its own (MSE),
// and a control bar of titled buttons.
const vjsRoot = (id, poster) =>
  `<video-js id="${id}" class="video-js" aria-label="Video Player">`
  + `<video id="${id}_html5_api" class="vjs-tech"${poster ? ` poster="${poster}"` : ""}></video>`
  + `<div class="vjs-control-bar"><button title="Play"></button>`
  + `<button title="Picture-in-Picture"></button><button title="Seek to live, currently behind live"></button></div>`
  + `</video-js>`;
// The page's videojs global: getAllPlayers() with each player's manifest,
// poster and (when named) Brightcove-style mediainfo.
const vjsScript = (players) => `<script>
  window.videojs = function () {};
  const all = ${JSON.stringify(players)}.map(([id, name, poster]) => ({
    id: () => id,
    currentSources: () => [
      { src: "http://example.invalid/http-copy/" + id + "/master.m3u8", type: "application/x-mpegURL" },
      { src: location.origin + "/assets/clips/" + id + "/master.m3u8", type: "application/x-mpegURL" }],
    currentSrc: () => location.origin + "/assets/clips/" + id + "/master.m3u8",
    poster: () => poster,
    mediainfo: name ? { name, poster } : undefined,
  }));
  window.videojs.getAllPlayers = () => all;
</script>`;
// A podcast card: the picture in a figure, the title in a heading beside the
// <audio>, and a second <audio> for the same episode (the unwrapped template).
const podcastCard = (id, title) =>
  `<article><figure><a href="/${id}"><picture><img src="/img/${id}.png" width="366" alt="${title}">`
  + `</picture></a></figure><div><p>PODCAST | Episodio</p><h2><a href="/${id}">${title}</a></h2>`
  + `<audio preload="none" src="/assets/clips/${id}.mp3"></audio>`
  + `<div><div><div><audio preload="none" src="/assets/clips/${id}.mp3"></audio></div></div></div>`
  + `<p>Resumen del episodio</p></div></article>`;

const SITE = {
  // 1. The firedown.app shape: the homepage declares the clips, an iframe at
  //    /demo/index.html plays them through ../ paths. A malformed block comes
  //    first (one bad block must not hide the next); a path-relative
  //    VideoObject contentUrl belongs to the HOMEPAGE's base; an AudioObject
  //    declares the iframe's audio; a srcdoc child plays a declared clip too.
  [`a.test/home.html`]: html("Home",
    `<meta property="og:title" content="Home OG"><meta property="og:image" content="/og.png">`
    + `<script type="application/firedown+json">[{"contentUrl": oops</script>`
    + block([
      { contentUrl: "/assets/clips/a.mp4", name: "Clip A", thumbnail: "/assets/clips/a.jpg" },
      { contentUrl: "/assets/clips/own.mp4", name: "Parent Own" },
      { contentUrl: "/assets/clips/sd.mp4", name: "Clip SD" },
    ])
    + ld({ "@context": "https://schema.org", "@type": "VideoObject", name: "Clip B",
      contentUrl: "assets/clips/b.mp4", thumbnailUrl: "thumbs/b.jpg" })
    + ld({ "@context": "https://schema.org", "@type": "AudioObject", name: "Clip C",
      contentUrl: "/assets/clips/c.mp3" }),
    `<iframe src="demo/index.html" title="Demo"></iframe>`
    + `<iframe srcdoc="<video src='/assets/clips/sd.mp4'></video>"></iframe>`),
  [`a.test/demo/index.html`]: html("Demo frame",
    block([{ contentUrl: "../assets/clips/own.mp4", name: "Frame Own" }]),
    `<video src="../assets/clips/a.mp4" poster="../assets/clips/a-frame.jpg"></video>`
    + `<video src="../assets/clips/b.mp4"></video>`
    + `<video src="../assets/clips/own.mp4"></video>`
    + `<video src="../assets/clips/d.mp4"></video>`
    + `<audio src="../assets/clips/c.mp3"></audio>`
    + `<iframe src="inner.html"></iframe>`),
  [`a.test/demo/inner.html`]: html("Inner", "", `<video src="../assets/clips/a.mp4"></video>`),

  // 2. A cross-origin parent declaring this frame's clip: never read.
  [`b.test/xhome.html`]: html("X home",
    block([{ contentUrl: `${A}/assets/clips/x.mp4`, name: "Leak X" }])
    + ld({ "@type": "VideoObject", name: "Leak X LD", contentUrl: `${A}/assets/clips/x.mp4` }),
    `<iframe src="${A}/xframe.html"></iframe>`),
  [`a.test/xframe.html`]: html("X frame", "", `<video src="/assets/clips/x.mp4"></video>`),

  // 3. A same-origin top behind a CROSS-origin middle frame: the walk skips
  //    the middle (never reads its block) and still reaches the top.
  [`a.test/mhome.html`]: html("M home",
    block([{ contentUrl: "/assets/clips/m.mp4", name: "Clip M" }]),
    `<iframe src="${B}/middle.html"></iframe>`),
  [`b.test/middle.html`]: html("Middle",
    block([{ contentUrl: `${A}/assets/clips/m.mp4`, name: "Middle Says" }]),
    `<iframe src="${A}/minner.html"></iframe>`),
  [`a.test/minner.html`]: html("M inner", "", `<video src="/assets/clips/m.mp4"></video>`),

  // 4. A sandboxed (opaque-origin) frame of the SAME site: not the page's
  //    origin any more, so the parent's block is not its to read.
  [`a.test/shome.html`]: html("S home",
    block([{ contentUrl: "/assets/clips/s.mp4", name: "Leak S" }]),
    `<iframe sandbox="allow-scripts" src="/sframe.html"></iframe>`),
  [`a.test/sframe.html`]: html("S frame", "", `<video src="/assets/clips/s.mp4"></video>`),

  // 5. A news front page (lasprovincias.es, HAR 26-10-19): Video.js players
  //    playing HLS through MSE (the <video> shows no URL the wire saw), one
  //    with the API shape of Brightcove's (mediainfo.name), podcast cards
  //    whose <audio> sits beside a heading with the picture one level up and
  //    a second copy of the same <audio> (a cookie-wall template unwrapped),
  //    a placeholder card, and two JW-style players whose control buttons
  //    must never title a clip.
  [`a.test/front.html`]: html("Front Page - Site",
    `<meta property="og:title" content="Front Page - Site"><meta property="og:image" content="/logo.png">`,
    `<article><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("v0", "/img/v0.jpg")}</div></div></figure>`
    + `<div><h2><a href="/a0">Rios desbordados en la Safor</a></h2></div></article>`
    + `<ul><li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("s1", "/img/s1.jpg")}</div></div></figure>`
    + `<p>Calles inundadas</p></div></li>`
    + `<li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("s2", "/img/s2.jpg")}</div></div></figure></div></li>`
    + `<li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("s3", "")}</div></div></figure>`
    + `<h2>Gandia bajo el agua</h2></div></li></ul>`
    + podcastCard("ep3", "Un podcast con ninos: igualdad y salud")
    + podcastCard("ep4", "Un podcast con ninos: tecnologia")
    + `<article><h2>Un podcast con ninos: salud mental</h2><audio preload="none" src=""></audio></article>`
    + `<div class="card"><div class="jwplayer"><video src="/assets/clips/jw1.mp4"></video>`
    + `<div role="button" aria-label="Rewind 10 Seconds"></div><div role="button" aria-label="Closed Captions"></div></div>`
    + `<h3>Clip uno del dia</h3></div>`
    + `<div class="card"><div class="jwplayer"><video src="/assets/clips/jw2.mp4"></video>`
    + `<div role="button" aria-label="Rewind 10 Seconds"></div></div><h3>Clip dos del dia</h3></div>`
    + vjsScript([["v0", "Lluvias torrenciales en Oliva", "/img/v0.jpg"],
      ["s1", "Calles inundadas en Miramar", "/img/s1.jpg"],
      ["s2", "Noche complicada en Bellreguard", "/img/s2.jpg"],
      ["s3", null, "/img/s3.jpg"]])),

  // 6. The same shapes on single-clip pages: the page's own fields stay.
  [`a.test/single.html`]: html("Article",
    `<meta property="og:title" content="Article headline"><meta property="og:image" content="/lead.jpg">`,
    `<figure>${vjsRoot("only", "/img/only.jpg")}</figure>`
    + vjsScript([["only", "Clip name", "/img/only.jpg"]])),
  [`a.test/solo.html`]: html("Podcast",
    `<meta property="og:title" content="Episode page"><meta property="og:image" content="/show.jpg">`,
    podcastCard("solo", "Episode heading")),
};

const server = http.createServer((req, res) => {
  const host = (req.headers.host || "").split(":")[0];
  const body = SITE[`${host}${req.url.split("?")[0]}`];
  if (body == null) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(body.split(P).join(String(server.address().port)));
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;
const a = A.replace(P, port);
const b = B.replace(P, port);

// The extension's `browser`, per frame: the responder registers on
// onMessage, the scanner's batches land in __reports.
const STUB = `window.__listeners = []; window.__reports = [];
window.browser = { runtime: {
  sendNativeMessage: () => Promise.resolve(false),
  sendMessage: (m) => { window.__reports.push(m); return Promise.resolve(false); },
  onMessage: { addListener: (f) => window.__listeners.push(f) } },
  tabs: { sendMessage: () => Promise.resolve() } };
window.__ask = async (u) => {
  for (const f of window.__listeners) {
    const r = f({ kind: 'get-page-metadata', mediaUrl: u });
    if (r !== undefined) return await r;
  }
  return null;
};`;

let failures = 0;
let checks = 0;
const expect = (ok, label, detail) => {
  checks++;
  if (ok) {
    console.log(`  ok   ${label}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? `  (${detail})` : ""}`);
  }
};

let browser;
try {
  browser = await chromium.launch({
    args: ["--host-resolver-rules=MAP *.test 127.0.0.1", "--no-proxy-server"],
  });
} catch (e) {
  console.log(`SKIP: Chromium did not launch (${e.message.split("\n")[0]})`);
  server.close();
  process.exit(0);
}

// Load a page, inject the content script into every frame, let the scanner
// flush (BATCH_MS 200), return a helper bound to that page.
async function open(url) {
  const page = await (await browser.newContext()).newPage();
  await page.route("**/*", (r) =>
    new URL(r.request().url()).hostname.endsWith(".test") ? r.continue() : r.abort());
  await page.addInitScript(STUB);
  await page.goto(url, { waitUntil: "load" });
  for (const f of page.frames()) await f.addScriptTag({ content: cs });
  await page.waitForTimeout(500);
  const frame = (pathPart) => page.frames().find((f) =>
    pathPart === "srcdoc" ? f.url() === "about:srcdoc" : f.url().endsWith(pathPart));
  const ask = async (f, clip) => {
    const meta = await f.evaluate((u) => window.__ask(u), `${a}/assets/clips/${clip}`);
    return { meta, pick: meta ? pickCaptureMeta(meta) : null };
  };
  const scanned = async (f, clip) => {
    const reports = await f.evaluate(() => window.__reports.filter((r) => r.kind === "images-detected"));
    for (const r of reports) {
      const m = r.meta && r.meta[`${a}/assets/clips/${clip}`];
      if (m) return pickCaptureMeta(m);
    }
    return null;
  };
  return { page, frame, ask, scanned };
}

try {
  console.log(`content script: ${csPath}`);

  console.log("same-origin parent (the firedown.app /demo shape)");
  {
    const { frame, ask, scanned } = await open(`${a}/home.html`);
    const demo = frame("/demo/index.html");
    const inner = frame("/demo/inner.html");
    const top = frame("/home.html");

    let r = await ask(demo, "a.mp4");
    expect(r.pick?.name === "Clip A", "iframe clip named by the parent's firedown+json (wire query)", r.pick?.name);
    expect(r.meta?.videoLdMatchThumbnail === `${a}/assets/clips/a.jpg`,
      "declared relative thumbnail made absolute against the PARENT", r.meta?.videoLdMatchThumbnail);
    const s = await scanned(demo, "a.mp4");
    expect(s?.name === "Clip A", "the DOM-scan report carries the same name", s?.name);

    r = await ask(demo, "b.mp4");
    expect(r.pick?.name === "Clip B", "path-relative VideoObject contentUrl resolved against the parent's base", r.pick?.name);
    expect(r.pick?.img === `${a}/thumbs/b.jpg`, "VideoObject thumbnail absolute against the parent", r.pick?.img);

    r = await ask(demo, "c.mp3");
    expect(r.meta?.audioLdName === "Clip C" && r.meta?.audioRole === "content",
      "AudioObject in the parent names the iframe's audio", `${r.meta?.audioLdName}/${r.meta?.audioRole}`);

    r = await ask(demo, "own.mp4");
    expect(r.pick?.name === "Frame Own", "the frame's own declaration wins over the parent's", r.pick?.name);

    r = await ask(demo, "d.mp4");
    expect(r.pick?.name === "Demo frame", "an undeclared clip keeps the FRAME's title", r.pick?.name);
    expect(r.meta?.ogTitle === "" && r.meta?.ogImage === "",
      "the parent's og:title / og:image never cross frames", `${r.meta?.ogTitle}|${r.meta?.ogImage}`);

    r = await ask(inner, "a.mp4");
    expect(r.pick?.name === "Clip A", "two frames down still reaches the declaring page", r.pick?.name);

    r = await ask(top, "a.mp4");
    expect(r.pick?.name === "Clip A", "top frame: a malformed block does not hide the next one", r.pick?.name);

    r = await ask(frame("srcdoc"), "sd.mp4");
    expect(r.pick?.name === "Clip SD", "srcdoc child (inherits the origin) reads the parent", r.pick?.name);
  }

  console.log("cross-origin parent");
  {
    const { frame, ask } = await open(`${b}/xhome.html`);
    const r = await ask(frame("/xframe.html"), "x.mp4");
    expect(r.meta !== null, "the responder still answers (the cross-origin read did not throw out)");
    expect(r.pick?.name === "X frame", "a cross-origin parent's declarations are never read", r.pick?.name);
  }

  console.log("same-origin top behind a cross-origin middle frame");
  {
    const { frame, ask } = await open(`${a}/mhome.html`);
    const r = await ask(frame("/minner.html"), "m.mp4");
    expect(r.pick?.name === "Clip M", "the walk climbs past the middle and reads the top", r.pick?.name);
  }

  console.log("sandboxed (opaque-origin) frame");
  {
    const { frame, ask } = await open(`${a}/shome.html`);
    const r = await ask(frame("/sframe.html"), "s.mp4");
    expect(r.meta !== null && r.pick?.name === "S frame",
      "a sandboxed frame does not read its parent", r.pick?.name);
  }

  console.log("news front page (Video.js players, podcast cards)");
  {
    const { frame, ask, scanned } = await open(`${a}/front.html`);
    const top = frame("/front.html");

    let r = await ask(top, "s1/master.m3u8");
    expect(r.pick?.name === "Calles inundadas en Miramar", "an MSE player's clip is named by its player", r.pick?.name);
    expect(r.pick?.img === `${a}/img/s1.jpg`, "...and pictured by its own poster, not the first player's", r.pick?.img);
    r = await ask(top, "s2/master.m3u8");
    expect(r.pick?.name === "Noche complicada en Bellreguard" && r.pick?.img === `${a}/img/s2.jpg`,
      "a second player gets its own name and poster", `${r.pick?.name} | ${r.pick?.img}`);
    r = await ask(top, "v0/master.m3u8");
    expect(r.pick?.name === "Lluvias torrenciales en Oliva", "the first player on the page keeps its own too", r.pick?.name);
    r = await ask(top, "s3/master.m3u8");
    expect(r.pick?.name === "Gandia bajo el agua",
      "a player with no clip name takes its card's heading, not a control or \"Video Player\"", r.pick?.name);
    expect(r.pick?.img === `${a}/img/s3.jpg`, "...and the player's poster() when the tech has none", r.pick?.img);

    r = await ask(top, "elsewhere/master.m3u8");
    expect(r.meta?.poster === "" && r.meta?.posterlessClip === true && r.pick?.img === "",
      "a clip no element or player shows takes no other clip's poster", `${r.meta?.poster} | ${r.pick?.img}`);
    r = await ask(top, "ding.mp3");
    expect(r.meta?.posterlessClip === false && r.pick?.img === "/logo.png",
      "an audio file no element shows keeps the page image", `${r.meta?.posterlessClip} | ${r.pick?.img}`);

    r = await ask(top, "ep3.mp3");
    expect(r.pick?.name === "Un podcast con ninos: igualdad y salud", "a podcast card's <audio> is named by its heading", r.pick?.name);
    expect(r.pick?.img === `${a}/img/ep3.png`, "...and pictured by its card's image, not the site logo", r.pick?.img);
    const sc = await scanned(top, "ep4.mp3");
    expect(sc?.name === "Un podcast con ninos: tecnologia" && sc?.img === `${a}/img/ep4.png`,
      "the DOM-scan report of another card carries its own", `${sc?.name} | ${sc?.img}`);

    r = await ask(top, "jw1.mp4");
    expect(r.pick?.name === "Clip uno del dia", "a player's control buttons never title a clip", r.pick?.name);
  }

  console.log("single-clip pages keep the page's own fields");
  {
    let o = await open(`${a}/single.html`);
    let r = await o.ask(o.frame("/single.html"), "only/master.m3u8");
    expect(r.pick?.name === "Article headline", "a single player's page keeps og:title", r.pick?.name);
    expect(r.pick?.img === `${a}/img/only.jpg`, "...with the player's poster", r.pick?.img);
    o = await open(`${a}/solo.html`);
    r = await o.ask(o.frame("/solo.html"), "solo.mp3");
    expect(r.pick?.name === "Episode page" && r.pick?.img === "/show.jpg",
      "a single podcast's page keeps og:title and og:image", `${r.pick?.name} | ${r.pick?.img}`);
  }
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures ? 1 : 0);
