// placeholder-harness.mjs — the content script's click-to-load placeholder
// scan in a real browser. The REAL js/content-script.js runs on locally served
// pages (a stubbed `browser` records what it sends) and the harness checks the
// `player-placeholders` reports: which markup counts as a placeholder, which
// does not, the per-document cap, late-built carousels and SPA navigations.
// The background half (which site captures what) is pinned by
// dailymotion-replay.mjs section 9 and the parser replays.
//
// Needs Playwright + its Chromium (`npm i -g playwright`); SKIPs without them.
//   node scripts/placeholder-harness.mjs [path/to/content-script.js]
import { readFileSync } from "node:fs";
import http from "node:http";
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const csPath = process.argv[2] || join(root, "app/src/main/assets/webrequests/js/content-script.js");
const cs = readFileSync(csPath, "utf8");

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

const STUB = `window.__reports = [];
window.browser = { runtime: {
  sendNativeMessage: () => Promise.resolve(false),
  sendMessage: (m) => { if (m && m.kind === "player-placeholders") window.__reports.push(m.urls); return Promise.resolve(false); },
  onMessage: { addListener: () => {} } } };`;

const DM = (id) => `https://geo.dailymotion.com/player/x1kd82.html?video=${id}&amp;mute=true`;
const DMU = (id) => `https://geo.dailymotion.com/player/x1kd82.html?video=${id}&mute=true`;
const card = (id) => `<a href="https://dai.ly/${id}" data-embed-url="${DM(id)}" aria-label="Reproducir"><img src="/p/${id}.jpg" alt="t"></a>`;

const pages = {
  // The vozpopuli.com carousel: cards declaring their player, plus a plain
  // dai.ly link in the article text, which is not a placeholder.
  "/carousel": `<div class="carousel-items">${card("xa1")}${card("xa2")}${card("xa3")}</div>
    <p>Lee también <a href="https://dai.ly/xtext1">este vídeo</a>.</p>`,
  // Lazy iframes: only one with no live src is waiting for its player.
  "/lazy": `<iframe data-src="https://player.vimeo.com/video/111"></iframe>
    <iframe src="about:blank" data-src="https://www.dailymotion.com/embed/video/xlz2"></iframe>
    <iframe src="https://player.vimeo.com/video/333" data-src="https://player.vimeo.com/video/333"></iframe>`,
  // Values that are not loadable player URLs are never reported; a relative
  // one is resolved against the page.
  "/values": `<a data-embed-url="javascript:alert(1)">x</a><a data-embed-url="">y</a>
    <a data-embed-url="/embed/local-player?id=7">z</a>`,
  // A page declaring more placeholders than the per-document cap.
  "/many": Array.from({ length: 30 }, (_, i) => card(`xm${i}`)).join(""),
  // A carousel built after load.
  "/late": `<div id="slot"></div><script>
      setTimeout(() => { document.getElementById("slot").innerHTML = ${JSON.stringify(card("xl1") + card("xl2"))}; }, 1500);
    </script>`,
  // An SPA: the next article replaces the content in the same document and
  // carries the same carousel; it is that page's own.
  "/spa": `<div id="app">${card("xs1")}</div><script>
      setTimeout(() => {
        history.pushState({}, "", "/spa/next-article");
        document.getElementById("app").innerHTML = ${JSON.stringify(card("xs1") + card("xs2"))};
      }, 1500);
    </script>`,
};

const server = http.createServer((req, res) => {
  const body = pages[req.url.split("?")[0]];
  if (body === undefined) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(`<!doctype html><html><head><meta charset="utf-8"><title>Page</title></head><body>${body}</body></html>`);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
const ctx = await browser.newContext();
// Nothing leaves the machine: the page's own images and frames are answered locally.
await ctx.route(/^https?:\/\/(?!127\.0\.0\.1)/, (r) => r.fulfill({ status: 204, body: "" }));
await ctx.addInitScript(STUB);
await ctx.addInitScript(`(() => {\n${cs}\n})();`);

async function reportsOn(path, waitMs = 1500) {
  const page = await ctx.newPage();
  await page.goto(base + path);
  await page.waitForTimeout(waitMs);
  const reports = await page.evaluate(() => window.__reports);
  await page.close();
  return reports;
}

let pass = 0;
let fail = 0;
const check = (name, ok, detail) => {
  if (ok) pass++;
  else { fail++; console.log("FAIL " + name + (detail ? "  " + detail : "")); }
};
const flat = (reports) => reports.flat();

const carousel = flat(await reportsOn("/carousel"));
check("carousel: every card's declared player is reported",
  ["xa1", "xa2", "xa3"].every((id) => carousel.includes(DMU(id))), JSON.stringify(carousel));
check("carousel: a plain link in the text is not a placeholder",
  !carousel.some((u) => u.includes("xtext1")), JSON.stringify(carousel));
check("carousel: each URL once", carousel.length === new Set(carousel).size, JSON.stringify(carousel));

const lazy = flat(await reportsOn("/lazy"));
check("lazy: an iframe with no src reports its data-src", lazy.includes("https://player.vimeo.com/video/111"),
  JSON.stringify(lazy));
check("lazy: an about:blank iframe reports its data-src",
  lazy.includes("https://www.dailymotion.com/embed/video/xlz2"), JSON.stringify(lazy));
check("lazy: an iframe already loading its player is left to the wire",
  !lazy.some((u) => u.includes("/333")), JSON.stringify(lazy));

const values = flat(await reportsOn("/values"));
check("values: only http(s) URLs, relative ones resolved",
  values.length === 1 && values[0] === `${base}/embed/local-player?id=7`, JSON.stringify(values));

const many = flat(await reportsOn("/many"));
check("many: at most 24 per document", many.length === 24, String(many.length));

const late = flat(await reportsOn("/late", 2600));
check("late: a carousel built after load is reported",
  late.includes(DMU("xl1")) && late.includes(DMU("xl2")), JSON.stringify(late));

const spa = await reportsOn("/spa", 2600);
check("spa: the first page reports its card", spa[0] && spa[0].includes(DMU("xs1")), JSON.stringify(spa));
check("spa: the next article reports its carousel again, as its own",
  spa.length >= 2 && spa[spa.length - 1].includes(DMU("xs1")) && spa[spa.length - 1].includes(DMU("xs2")),
  JSON.stringify(spa));

await browser.close();
server.close();
console.log(`${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);
