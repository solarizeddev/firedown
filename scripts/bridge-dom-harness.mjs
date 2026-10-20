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
import { loadPlaywright, PLAYWRIGHT_HINT } from "./load-playwright.mjs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const bridgePath = process.argv[2] || join(root, "app/src/main/assets/webrequests/js/page-state-bridge.js");
const bridge = readFileSync(bridgePath, "utf8");

const chromium = (await loadPlaywright())?.chromium;
if (!chromium) {
  console.log(`SKIP: playwright not installed (${PLAYWRIGHT_HINT})`);
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
  // One player that moves on to the next clip (a playlist, "up next") after
  // the timed passes: the second clip is a capture of its own.
  "/swap": `<video-js id="p" class="video-js"><video class="vjs-tech" preload="none"></video></video-js>
    <script>
      window.wrappedJSObject = window;
      let cur = null;
      const pl = { id: () => "p", el: () => document.getElementById("p"),
        currentSources: () => cur ? [{ src: cur, type: "application/x-mpegURL" }] : [],
        currentSrc: () => cur || "", poster: () => "/img/p.jpg" };
      window.videojs = function () {}; window.videojs.getAllPlayers = () => [pl];
      const tech = document.querySelector("#p video");
      setTimeout(() => { cur = location.origin + "/hls/clipA/master.m3u8?tok=1"; tech.src = "blob:a"; }, 300);
      setTimeout(() => { cur = location.origin + "/hls/clipA/master.m3u8?tok=2"; tech.src = "blob:a2"; }, 4500);
      setTimeout(() => { cur = location.origin + "/hls/clipB/master.m3u8?tok=3"; tech.src = "blob:b"; }, 5200);
    </script>`,
  // A pre-roll ad handed to the player's media element while its API still
  // lists the content (IMA / VAST on mobile).
  "/preroll": `<video-js id="b" class="video-js"><video class="vjs-tech" preload="none"></video></video-js>
    <script>
      window.wrappedJSObject = window;
      const pl = { id: () => "b", el: () => document.getElementById("b"),
        currentSources: () => [{ src: location.origin + "/hls/content/master.m3u8", type: "application/x-mpegURL" }],
        currentSrc: () => location.origin + "/hls/content/master.m3u8", poster: () => "/img/b.jpg",
        mediainfo: { id: "6407071494112", name: "Calles inundadas en Miramar" } };
      window.videojs = function () {}; window.videojs.getAllPlayers = () => [pl];
      setTimeout(() => { document.querySelector("#b video").src = "/ads/creative/15s_preroll.mp4"; }, 4600);
    </script>`,
  // An SSR DASH page (the bilibili shape) whose title is still the site name
  // when its <video> gets a source; the real title lands later.
  "/sitename": `<meta property="og:title" content="127">
    <video id="v" preload="none"></video>
    <script>
      window.wrappedJSObject = window;
      document.title = "127";
      window.__initialState = { player: { playUrl: { dash: {
        video: [{ baseUrl: "https://upos.example/v.m4s", bandwidth: 900000, width: 1280, height: 720, codecs: "avc1" }],
        audio: [{ baseUrl: "https://upos.example/a.m4s", bandwidth: 128000, codecs: "mp4a" }], duration: 60 } } } };
      setTimeout(() => { document.getElementById("v").src = "blob:x"; }, 300);
      setTimeout(() => { document.title = "The real episode title"; document.querySelector('meta[property="og:title"]').content = "The real episode title"; }, 2000);
    </script>`,
  // A JW embed whose CDN signs the PATH, beside another media element; and
  // one that names its clip.
  "/signedpath": `<div class="jwplayer"><video class="jw-video" preload="none"></video></div><audio id="ding" preload="none"></audio>
    <script>
      window.wrappedJSObject = window;
      window.jwplayer = () => ({ getPlaylist: () => [{ title: "Clip", file: location.origin + "/exp=1760000000~hmac=abcdef0123/clip42/master.m3u8" }] });
    </script>`,
  "/jwmedia": `<div class="jwplayer"><video class="jw-video" preload="none"></video></div><audio id="ding" preload="none"></audio>
    <script>
      window.wrappedJSObject = window;
      window.jwplayer = () => ({ getPlaylist: () => [{ title: "Clip", mediaid: "EvU8KrK5", file: location.origin + "/exp=1/EvU8KrK5.m3u8" }] });
    </script>`,
  // The Daily Mail's front page: each <video> carries its player's config as
  // JSON in data-opts, the clip's title included, and the page title is the
  // site's. Then a config nesting the title under "media", and one whose only
  // name is the player's own.
  "/dmhome": `<meta property="og:title" content="UK Home | Daily Mail Online">
    <div class="vjs-video-container"><video class="video-js" preload="none" poster="/img/d1.jpg"
      data-opts='{"linkBaseURL":"/news/a1.html#v-1","title":"Police officer shot in city centre","src":"/m/d1.mp4","videoId":"4716928555074797746"}'>
      <source type="video/mp4" src="/m/d1.mp4"></video></div>
    <div class="vjs-video-container"><video class="video-js" preload="none" poster="/img/d2.jpg"
      data-plyr-config='{"autoplay":false,"media":{"name":"Storm closes roads in the north"}}'>
      <source type="video/mp4" src="/m/d2.mp4"></video></div>
    <div class="vjs-video-container"><video class="video-js" preload="none" poster="/img/d3.jpg"
      data-setup='{"name":"heroPlayer","fluid":true}'>
      <source type="video/mp4" src="/m/d3.mp4"></video></div>`,
  // A placeholder label on every clip (higgsfield.ai's <video aria-label=
  // "Genjutsu example">, here in Finnish): each clip takes its figure's
  // caption. Two cuts of one story keep the title they share.
  "/sharedlabel": `<meta property="og:title" content="Explore | Site">
    ${[["e1", "Kettu juoksee lumessa"], ["e2", "Revontulet Lapissa"], ["e3", "Jäänmurtaja satamassa"]].map(([id, c]) =>
      `<div data-player="1"><figure><video aria-label="Esimerkki" preload="none" src="/m/${id}.mp4"></video>`
      + `<figcaption>${c}</figcaption></figure></div>`).join("")}
    <div data-player="1"><video aria-label="Tulvat Valenciassa" preload="none" src="/m/cut-v.mp4"></video></div>
    <div data-player="1"><video aria-label="Tulvat Valenciassa" preload="none" src="/m/cut-h.mp4"></video></div>`,
  // Page state that names its clips (device audit 2026-10-20): Reuters' Arc
  // state, each clip {title, thumbnail, source:{hls}}; an ABC News clip whose
  // HLS and CMAF masters sit apart under one headline; NBC's __NEXT_DATA__
  // with a clip's renditions in "videoAssets"; and a section holding two
  // untitled clips under its own title.
  "/state": `<meta property="og:title" content="Front | Site">
    <script>
      window.wrappedJSObject = window;
      window.Fusion = { globalContent: { videos: [
        { _id: "r1", title: "Reuters clip one about markets", thumbnail: { url: "/img/r1.jpg" },
          source: { hls: location.origin + "/m/r1/master.m3u8" } },
        { _id: "r2", title: "Reuters clip two about elections", thumbnail: { url: "/img/r2.jpg" },
          source: { hls: location.origin + "/m/r2/master.m3u8" } }] } };
      window.abcState = { page: { content: { video: { headline: "Alaska storm surge in pictures",
        playlists: { hls: { url: location.origin + "/m/abc/hls/master.m3u8" },
          cmaf: { url: location.origin + "/m/abc/cmaf/master.m3u8" } } } } } };
      window.__NEXT_DATA__ = { props: { pageProps: { items: [
        { headline: { primary: "Flamingo stolen from the zoo" }, videoAssets: [
          { format: "M3U", publicUrl: location.origin + "/m/nbc1/index.m3u8", height: 1080 },
          { format: "MPEG4", publicUrl: location.origin + "/m/nbc1_4500.mp4", height: 1080 }] }] } } };
      window.rail = { section: { title: "Top videos", hero: { hls: location.origin + "/m/w1/master.m3u8" },
        promo: { hls: location.origin + "/m/w2/master.m3u8" } } };
      // as.com's podcast players: one config per player, alike in all but
      // the file (device audit 2026-10-20, second run).
      window.corePlayers = { list: [
        { src: location.origin + "/m/asp1.mp3", type: "audio/mpeg", autoplay: false },
        { src: location.origin + "/m/asp2.mp3", type: "audio/mpeg", autoplay: false }] };
    </script>`,
};
const waits = { "/late": 6200, "/churn": 6400, "/many": 15500, "/swap": 6200, "/preroll": 5600, "/sitename": 4600 };

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
  return sent.filter((m) => m && (m.kind === "page-state-progressive" || m.kind === "page-state-hls"
    || m.kind === "page-state-media"));
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

const swap = results["/swap"].filter((m) => m.kind === "page-state-hls");
check("swap: a player's next clip is a capture of its own, its re-signed first clip is not",
  swap.length === 2 && /clipA/.test(swap[0].payload.url) && /clipB/.test(swap[1].payload.url),
  JSON.stringify(swap.map((m) => [m.payload.url.replace(base, ""), m.payload.clipId])));
check("swap: the next clip carries its own identity, the first keeps the page's",
  swap.length === 2 && !swap[0].payload.clipId && swap[1].payload.clipId,
  JSON.stringify(swap.map((m) => m.payload.clipId)));
const preroll = results["/preroll"];
check("preroll: an ad in the player's media element is not a clip",
  !preroll.some((m) => JSON.stringify(m.payload).includes("15s_preroll")),
  JSON.stringify(preroll.map((m) => [m.kind, m.payload.url || m.payload.variants?.[0]?.url])));
check("preroll: the player's content is", preroll.some((m) => m.kind === "page-state-hls" && /content/.test(m.payload.url)));
const sitename = results["/sitename"].filter((m) => m.kind === "page-state-media");
check("sitename: an early media start does not send an SSR set under the site name",
  sitename.length === 1 && sitename[0].payload.title === "The real episode title",
  JSON.stringify(sitename.map((m) => m.payload.title)));
const signed = results["/signedpath"].filter((m) => m.kind === "page-state-hls");
check("signed path: the clip's key leaves the signature out",
  signed.length === 1 && signed[0].payload.clipId && !/exp=|hmac/.test(signed[0].payload.clipId),
  JSON.stringify(signed.map((m) => m.payload.clipId)));
const jwmedia = results["/jwmedia"].filter((m) => m.kind === "page-state-hls");
check("jw: a JW item's mediaid names the clip", jwmedia.length === 1 && jwmedia[0].payload.clipId === "EvU8KrK5",
  JSON.stringify(jwmedia.map((m) => m.payload.clipId)));

const shared = results["/sharedlabel"].filter((m) => m.kind === "page-state-progressive");
const sharedBy = (id) => shared.find((m) => m.payload.variants.some((v) => v.url.endsWith("/m/" + id + ".mp4")));
check("shared label: a label three clips carry names none; each takes its figure's caption",
  sharedBy("e2")?.payload.title === "Revontulet Lapissa" && sharedBy("e3")?.payload.title === "Jäänmurtaja satamassa",
  JSON.stringify(shared.map((m) => [files(m), m.payload.title])));
check("shared label: two cuts of one story keep the title they share",
  sharedBy("cut-v")?.payload.title === "Tulvat Valenciassa" && sharedBy("cut-h")?.payload.title === "Tulvat Valenciassa",
  JSON.stringify(shared.map((m) => [files(m), m.payload.title])));
const dm = results["/dmhome"].filter((m) => m.kind === "page-state-progressive");
const dmBy = (id) => dm.find((m) => m.payload.variants.some((v) => v.url.endsWith("/m/" + id + ".mp4")));
check("config: a clip is named by its player's data-* config, not the page",
  dmBy("d1")?.payload.title === "Police officer shot in city centre",
  JSON.stringify(dm.map((m) => [files(m), m.payload.title])));
check("config: a title nested under media names the clip too",
  dmBy("d2")?.payload.title === "Storm closes roads in the north",
  JSON.stringify(dm.map((m) => [files(m), m.payload.title])));
check("config: a player's own top-level name is no title",
  dmBy("d3") && dmBy("d3").payload.title !== "heroPlayer",
  JSON.stringify(dmBy("d3")?.payload.title));

check("player: a clip posted with the page's title says so (titleFromPage)",
  player.length === 1 && player[0].payload.titleFromPage === true, JSON.stringify(player[0]?.payload.titleFromPage));
check("videojs: a clip its player names does not ask (no titleFromPage)",
  vjsBy("p1") && !vjsBy("p1").payload.titleFromPage);

const st = results["/state"].filter((m) => m.kind === "page-state-hls");
const stBy = (part) => st.filter((m) => m.payload.url.includes(part) || (m.payload.siblings || []).some((u) => u.includes(part)));
check("state: each clip in page state is named by its own object, not the page",
  stBy("/r1/")[0]?.payload.title === "Reuters clip one about markets"
    && stBy("/r2/")[0]?.payload.title === "Reuters clip two about elections" && !stBy("/r1/")[0]?.payload.titleFromPage,
  JSON.stringify(st.map((m) => [m.payload.url.replace(base, ""), m.payload.title])));
check("state: ...and pictured by its own thumbnail",
  stBy("/r1/")[0]?.payload.img === `${base}/img/r1.jpg`, JSON.stringify(stBy("/r1/")[0]?.payload.img));
check("state: an HLS and a CMAF master of one clip are one capture, named by the clip",
  stBy("/abc/").length === 1 && stBy("/abc/")[0].payload.title === "Alaska storm surge in pictures"
    && stBy("/abc/")[0].payload.siblings.length === 2,
  JSON.stringify(stBy("/abc/").map((m) => [m.payload.url.replace(base, ""), m.payload.title])));
check("state: renditions listed under any name are one clip, named by its headline",
  stBy("nbc1").length === 1 && /nbc1\/index\.m3u8$/.test(stBy("nbc1")[0].payload.url)
    && stBy("nbc1")[0].payload.title === "Flamingo stolen from the zoo",
  JSON.stringify(results["/state"].filter((m) => JSON.stringify(m.payload).includes("nbc1"))
    .map((m) => [m.kind, m.payload.url || m.payload.variants?.[0]?.url, m.payload.title])));
check("state: two untitled clips under a titled section are two captures, the section names neither",
  stBy("/w1/").length === 1 && stBy("/w2/").length === 1
    && stBy("/w1/")[0].payload.title !== "Top videos" && stBy("/w1/")[0].payload.titleFromPage === true,
  JSON.stringify(st.filter((m) => /\/w[12]\//.test(m.payload.url)).map((m) => m.payload.title)));

const asPosts = results["/state"].filter((m) => /asp[12]\.mp3/.test(JSON.stringify(m.payload)));
check("state: two players' configs alike in all but the file are not one clip's renditions (as.com)",
  !asPosts.some((m) => /asp1\.mp3/.test(JSON.stringify(m.payload)) && /asp2\.mp3/.test(JSON.stringify(m.payload))),
  JSON.stringify(asPosts.map((m) => [m.kind, files(m), m.payload.title])));

console.log(`${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);
