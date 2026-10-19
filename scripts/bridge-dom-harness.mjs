// bridge-dom-harness.mjs — the page-state bridge's DOM read in a real browser.
// The REAL js/page-state-bridge.js runs on locally served pages (a stubbed
// `browser` records what it sends), and each check names what the background
// would receive for one shape of <video>:
//
//   - a <video> whose <source>s declare a quality ladder (size / label /
//     data-quality, or 720p-style file names) is ONE capture with every
//     rendition, on any page — the content script reports those <source>s
//     one by one, and without this each was its own capture with no quality
//     picker (a 1.2.2 report: "I'll need to choose one at random");
//   - format fallbacks (webm + mp4 of one size) and a lone src stay with the
//     content script: the bridge sends nothing for them outside a player;
//   - inside a recognised player every <video> is read, ladder or not;
//   - Video.js players (Brightcove's, lasprovincias.es) are each their own
//     capture, named by the player's clip name and pictured by the player's
//     own poster, never the page's og:image or Video.js's "Video Player";
//   - players set up AFTER the timed passes (lasprovincias.es: 8-13 s, behind
//     its cookie consent) are read when their media starts loading, each
//     master names its clip (clipId) so the background keeps them apart, an
//     HLS playlist is posted rather than the DASH manifest listed beside it,
//     and the player's own mp4 tech is not posted as a second clip;
//   - on a page holding several clips, a plain <audio> outside any player is
//     the content script's (it names it from its card), never posted here.
//
// Engine caveat: Chromium runs the script as a page script with no
// wrappedJSObject (the page-world readers stay silent); the DOM read is plain
// DOM and behaves the same in a Firefox content script. The /vjs page sets
// window.wrappedJSObject = window itself, so the Video.js reader runs there.
//
// Needs Playwright + its Chromium; SKIPs cleanly without them.
//   node scripts/bridge-dom-harness.mjs [path/to/page-state-bridge.js]
// The optional path runs another revision (proving the checks have teeth).
import { readFileSync } from "node:fs";
import http from "node:http";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const bridgePath = process.argv[2] || join(root, "app/src/main/assets/webrequests/js/page-state-bridge.js");
const bridge = readFileSync(bridgePath, "utf8");

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

const STUB = `<script>
window.__sent = [];
window.browser = { runtime: {
  sendNativeMessage: () => Promise.resolve(false),
  sendMessage: (m) => { window.__sent.push(m); return Promise.resolve(); }
} };
</script>`;

const pages = {
  "/ladder": `<video controls>
      <source src="/m/clip_1080p.mp4" type="video/mp4" size="1080">
      <source src="/m/clip_720p.mp4" type="video/mp4" size="720">
    </video>`,
  "/labels": `<video controls>
      <source src="/m/a91f3c.mp4" type="video/mp4" label="720p">
      <source src="/m/b72c0d.mp4" type="video/mp4" label="480p">
    </video>`,
  "/names": `<video controls>
      <source src="/m/clip_480p.mp4" type="video/mp4">
      <source src="/m/clip_1080p.mp4" type="video/mp4">
    </video>`,
  "/formats": `<video controls>
      <source src="/m/clip.webm" type="video/webm">
      <source src="/m/clip.mp4" type="video/mp4">
    </video>`,
  "/single": `<video controls src="/m/clip_720p.mp4"></video>`,
  "/two-videos": `<video controls src="/m/one_720p.mp4"></video>
    <video controls src="/m/two_1080p.mp4"></video>`,
  "/player": `<div class="video-js"><video controls src="/m/plain.mp4"></video></div>`,
  // Three Brightcove-style players (the API shape of Video.js 8: getAllPlayers,
  // currentSources, poster, localize, the root labelled "Video Player"),
  // playing HLS through MSE, on a page whose og:image is the site logo.
  "/vjs": `<meta property="og:image" content="/logo.png">
    <video-js id="p1" class="video-js" aria-label="Reproductor de vídeo"><video class="vjs-tech"></video></video-js>
    <video-js id="p2" class="video-js" aria-label="Reproductor de vídeo"><video class="vjs-tech"></video></video-js>
    <video-js id="p3" class="video-js" aria-label="Reproductor de vídeo"><video class="vjs-tech"></video></video-js>
    <script>
      window.wrappedJSObject = window;
      const mk = (id, name, poster) => ({
        id: () => id,
        el: () => document.getElementById(id),
        currentSources: () => [{ src: location.origin + "/m/" + id + "/master.m3u8", type: "application/x-mpegURL" }],
        currentSrc: () => location.origin + "/m/" + id + "/master.m3u8",
        poster: () => poster,
        mediainfo: name ? { name, poster } : undefined,
        localize: (k) => (k === "Video Player" ? "Reproductor de vídeo" : k),
      });
      const players = [mk("p1", "Calles inundadas en Miramar", "/img/p1.jpg"),
        mk("p2", "Lluvias torrenciales en Oliva", "/img/p2.jpg"),
        mk("p3", null, "/img/p3.jpg")];
      window.videojs = function () {};
      window.videojs.getAllPlayers = () => players;
    </script>`,
  // A news front page whose Brightcove players are set up 4.6 s in, after the
  // last timed pass: each tech is handed its mp4 then (which fires
  // loadstart), and each player lists DASH before HLS, as Brightcove does.
  // A podcast card's plain <audio> sits outside any player.
  "/late": `<meta property="og:image" content="/logo.png">
    <article><h2>Podcast semanal</h2><audio preload="none" src="/m/ep1.mp3"></audio></article>
    <article><h2>Otro episodio</h2><audio preload="none" src="/m/ep2.mp3"></audio></article>
    <video-js id="l1" class="video-js" data-player="x" aria-label="Video Player"><video class="vjs-tech" preload="none"></video></video-js>
    <video-js id="l2" class="video-js" data-player="x" aria-label="Video Player"><video class="vjs-tech" preload="none"></video></video-js>
    <script>
      window.wrappedJSObject = window;
      setTimeout(() => {
        const mk = (id, vid, name) => ({
          id: () => id,
          el: () => document.getElementById(id),
          currentSources: () => [
            { src: location.origin + "/m/" + id + ".mp4", type: "video/mp4" },
            { src: "https://dash.invalid/" + id + "/manifest.mpd", type: "application/dash+xml" },
            { src: location.origin + "/m/" + id + "/master.m3u8", type: "application/x-mpegURL" }],
          currentSrc: () => location.origin + "/m/" + id + ".mp4",
          poster: () => "/img/" + id + ".jpg",
          mediainfo: { id: vid, name, poster: "/img/" + id + ".jpg" },
        });
        const players = [mk("l1", "6407071494112", "Calles inundadas en Miramar"),
          mk("l2", "6407070026112", "Noche complicada en Bellreguard")];
        window.videojs = function () {};
        window.videojs.getAllPlayers = () => players;
        for (const id of ["l1", "l2"]) {
          document.querySelector("#" + id + " video").src = "/m/" + id + ".mp4";
        }
      }, 4600);
    </script>`,
  // A Video.js player set up while an ad slot keeps restarting its clip
  // every 150 ms: media starts all the time, so a read timer that a new
  // event pushes back never fires.
  "/churn": `<video id="ad" muted preload="none"></video>
    <video-js id="c1" class="video-js"><video class="vjs-tech" preload="none"></video></video-js>
    <script>
      window.wrappedJSObject = window;
      let n = 0;
      setTimeout(() => setInterval(() => { document.getElementById("ad").src = "/m/ad" + (n++ % 3) + ".mp4"; }, 150), 4500);
      setTimeout(() => {
        window.videojs = function () {};
        window.videojs.getAllPlayers = () => [{
          id: () => "c1", el: () => document.getElementById("c1"),
          currentSources: () => [{ src: location.origin + "/m/c1/master.m3u8", type: "application/x-mpegURL" }],
          currentSrc: () => location.origin + "/m/c1/master.m3u8",
          poster: () => "/img/c1.jpg",
          mediainfo: { id: "6407079990112", name: "Clip entre anuncios", poster: "/img/c1.jpg" },
        }];
        document.querySelector("#c1 video").src = "/m/c1.mp4";
      }, 4700);
    </script>`,
  // A live blog that builds each embed as it scrolls into view: sixteen
  // players, one every 300 ms, each its own read. Past the first twelve the
  // reads slow down; they must not stop.
  "/many": `<script>
      window.wrappedJSObject = window;
      const players = [];
      window.videojs = function () {};
      window.videojs.getAllPlayers = () => players;
      for (let i = 0; i < 16; i++) {
        setTimeout(() => {
          const id = "m" + i;
          const root = document.createElement("video-js");
          root.id = id;
          root.className = "video-js";
          const tech = document.createElement("video");
          tech.className = "vjs-tech";
          tech.preload = "none";
          root.appendChild(tech);
          document.body.appendChild(root);
          players.push({
            id: () => id, el: () => root,
            currentSources: () => [{ src: location.origin + "/m/" + id + "/master.m3u8", type: "application/x-mpegURL" }],
            currentSrc: () => location.origin + "/m/" + id + "/master.m3u8",
            poster: () => "/img/" + id + ".jpg",
            mediainfo: { id: String(6407080000112 + i), name: "Directo " + i, poster: "/img/" + id + ".jpg" },
          });
          tech.src = "/m/" + id + ".mp4";
        }, 4500 + 300 * i);
      }
    </script>`,
};
const waits = { "/late": 6200, "/churn": 6400, "/many": 15500 };

const server = http.createServer((req, res) => {
  const body = pages[req.url];
  if (body === undefined) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(`<!doctype html><html><head><title>Clip page</title>${STUB}<script>${bridge}</script></head>`
    + `<body>${body}</body></html>`);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let pass = 0;
let fail = 0;
const check = (name, ok, detail) => {
  if (ok) pass++;
  else { fail++; console.log("FAIL " + name + (detail ? "  " + detail : "")); }
};

async function sentOn(path) {
  const page = await browser.newPage();
  await page.goto(base + path);
  // The bridge's passes: immediate, DOMContentLoaded, t500, t1500, t4000.
  await page.waitForTimeout(waits[path] || 4400);
  const sent = await page.evaluate(() => window.__sent);
  await page.close();
  return sent.filter((m) => m && (m.kind === "page-state-progressive" || m.kind === "page-state-hls"));
}

const paths = Object.keys(pages);
const results = Object.fromEntries(await Promise.all(paths.map(async (p) => [p, await sentOn(p)])));
await browser.close();
server.close();

const heights = (m) => m.payload.variants.map((v) => v.height).join(",");
const files = (m) => m.payload.variants.map((v) => v.url.replace(base + "/m/", "")).join(",");

const ladder = results["/ladder"];
check("ladder: one capture with both renditions, best first",
  ladder.length === 1 && ladder[0].kind === "page-state-progressive" && heights(ladder[0]) === "1080,720",
  JSON.stringify(ladder.map((m) => m.payload && m.payload.variants)));
check("ladder: the renditions are the <source> files",
  ladder.length === 1 && files(ladder[0]) === "clip_1080p.mp4,clip_720p.mp4");
const labels = results["/labels"];
check("labels: hash-named files with label= are one capture",
  labels.length === 1 && heights(labels[0]) === "720,480");
const names = results["/names"];
check("names: 480p / 1080p file names are a ladder too",
  names.length === 1 && heights(names[0]) === "1080,480");
check("formats: webm + mp4 of one size is not a ladder", results["/formats"].length === 0,
  JSON.stringify(results["/formats"]));
check("single: a lone src stays with the content script", results["/single"].length === 0);
check("two videos: two clips are never one ladder", results["/two-videos"].length === 0,
  JSON.stringify(results["/two-videos"]));
const player = results["/player"];
check("player: inside a player every <video> is still read",
  player.length === 1 && files(player[0]) === "plain.mp4");

const vjs = results["/vjs"].filter((m) => m.kind === "page-state-hls");
const vjsBy = (id) => vjs.find((m) => m.payload.url === `${base}/m/${id}/master.m3u8`);
check("videojs: every player is its own capture", vjs.length === 3,
  JSON.stringify(vjs.map((m) => m.payload.url)));
check("videojs: named by the player's clip name",
  vjsBy("p1")?.payload.title === "Calles inundadas en Miramar"
    && vjsBy("p2")?.payload.title === "Lluvias torrenciales en Oliva",
  JSON.stringify(vjs.map((m) => m.payload.title)));
check("videojs: pictured by the player's own poster, not og:image",
  vjsBy("p1")?.payload.img === `${base}/img/p1.jpg` && vjsBy("p2")?.payload.img === `${base}/img/p2.jpg`,
  JSON.stringify(vjs.map((m) => m.payload.img)));
check("videojs: the root's \"Video Player\" label is never a title",
  vjsBy("p3") && vjsBy("p3").payload.title !== "Reproductor de vídeo",
  JSON.stringify(vjsBy("p3")?.payload.title));
check("videojs: on a page of several players each master names its clip",
  vjs.length === 3 && new Set(vjs.map((m) => m.payload.clipId)).size === 3 && vjs.every((m) => m.payload.clipId),
  JSON.stringify(vjs.map((m) => m.payload.clipId)));

const late = results["/late"];
const lateHls = late.filter((m) => m.kind === "page-state-hls");
const lateBy = (id) => lateHls.find((m) => m.payload.clipId && m.payload.title && m.payload.url.includes("/" + id + "/"));
check("late: players set up after the timed passes are captured when their media loads",
  lateHls.length === 2 && lateBy("l1") && lateBy("l2"),
  JSON.stringify(late.map((m) => [m.kind, m.payload.url || m.payload.variants?.[0]?.url])));
check("late: each is named by its player and keyed by the player's clip id",
  lateBy("l1")?.payload.title === "Calles inundadas en Miramar" && lateBy("l1")?.payload.clipId === "6407071494112"
    && lateBy("l2")?.payload.clipId === "6407070026112",
  JSON.stringify(lateHls.map((m) => [m.payload.title, m.payload.clipId])));
check("late: the HLS playlist is posted, not the DASH manifest listed first",
  lateHls.every((m) => /\/master\.m3u8$/.test(m.payload.url)), JSON.stringify(lateHls.map((m) => m.payload.url)));
check("late: the player's mp4 tech is not posted as a clip of its own",
  late.every((m) => m.kind !== "page-state-progressive" || !/\/m\/l[12]\.mp4/.test(m.payload.variants[0].url)),
  JSON.stringify(late.filter((m) => m.kind === "page-state-progressive").map((m) => m.payload.variants[0].url)));
check("late: a plain <audio> outside any player is left to the content script",
  late.every((m) => !JSON.stringify(m.payload).includes("ep1.mp3") || m.kind === "page-state-hls"),
  JSON.stringify(late.filter((m) => m.kind === "page-state-progressive").map((m) => m.payload.variants[0].url)));

const churn = results["/churn"].filter((m) => m.kind === "page-state-hls");
check("late: a player set up while other media keeps starting is still read",
  churn.length === 1 && churn[0].payload.clipId === "6407079990112",
  JSON.stringify(results["/churn"].map((m) => [m.kind, m.payload.url || m.payload.variants?.[0]?.url])));
const many = results["/many"].filter((m) => m.kind === "page-state-hls");
check("late: players keep being read past the first twelve reads",
  many.length === 16 && new Set(many.map((m) => m.payload.clipId)).size === 16,
  `${many.length} posted: ${JSON.stringify(many.map((m) => m.payload.title))}`);

console.log(`${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);
