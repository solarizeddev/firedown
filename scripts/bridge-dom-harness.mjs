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
//   - inside a recognised player every <video> is read, ladder or not.
//
// Engine caveat: Chromium runs the script as a page script with no
// wrappedJSObject (the page-world readers stay silent); the DOM read is plain
// DOM and behaves the same in a Firefox content script.
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
};

const server = http.createServer((req, res) => {
  const body = pages[req.url];
  if (body === undefined) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { "content-type": "text/html" });
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
  await page.waitForTimeout(4400);
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

console.log(`${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);
