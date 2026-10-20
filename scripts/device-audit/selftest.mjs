#!/usr/bin/env node
// selftest.mjs — the device-audit judge on synthetic runs, shaped exactly as
// SiteAuditTest writes them (site.json, entities.json, emits.json,
// census.json, around.json, requests.json). No phone, no browser:
//
//   node scripts/device-audit/selftest.mjs
//
// Run it after changing a rule in judge.mjs.

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { judgeSite } from "./judge.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
let failed = 0;
let passed = 0;
function check(name, ok, detail) {
  if (ok) {
    passed++;
  } else {
    failed++;
    console.log(`FAIL ${name}${detail ? " — " + detail : ""}`);
  }
}

function writeSite(dir, files) {
  fs.mkdirSync(dir, { recursive: true });
  const defaults = { "site.json": {}, "entities.json": [], "emits.json": [],
    "census.json": { frames: [], census: [] }, "around.json": [], "requests.json": { tab: [], extension: [] } };
  for (const [name, value] of Object.entries({ ...defaults, ...files })) {
    fs.writeFileSync(path.join(dir, name), JSON.stringify(value));
  }
}

const topFrame = (census) => ({ frameId: 0, parentFrameId: -1, url: census.url, result: census });
const tile = (o) => ({ uid: o.uid, shown: o.shown ?? o.name, name: o.name, url: o.url, origin: o.origin || "",
  mime: o.mime || "video/mp4", video: o.audio ? false : true, audio: !!o.audio, square: !!o.square,
  filenameLike: !!o.filenameLike, thumbnail: o.thumbnail || "", namedByPage: !!o.namedByPage,
  variants: o.variants || [] });

const root = fs.mkdtempSync(path.join(os.tmpdir(), "device-audit-selftest-"));
const run = path.join(root, "run");
const older = path.join(root, "older");

// 1. Two clips named after the page; the right name is in a data-* config.
writeSite(path.join(run, "01-dailymail"), {
  "site.json": { url: "https://news.example/", location: "https://news.example/" },
  "entities.json": [
    tile({ uid: 1, name: "UK Home | Daily News", url: "https://cdn.example/v/480x270_MP4_111.mp4" }),
    tile({ uid: 2, name: "UK Home | Daily News", url: "https://cdn.example/v/480x270_MP4_222.mp4" }),
  ],
  "census.json": { frames: [{ frameId: 0, parentFrameId: -1, url: "https://news.example/" }],
    census: [topFrame({ url: "https://news.example/", title: "UK Home | Daily News", ogTitle: "", siteName: "Daily News",
      text: "News", media: [], iframes: [], players: 2 })] },
  "around.json": [{ frameId: 0, url: "https://news.example/", result: { matches: [
    { key: "1", attr: "data-opts", tag: "video", path: "div.shorts > video",
      card: "<div class=\"shorts\"><video data-opts=\"…\"></video></div>",
      data: [{ level: 1, tag: "ul", name: "data-shorts", value: "[{\"title\":\"Police officer shot in city centre\",\"src\":\"x\"}]" }] },
  ], media: [] } }],
});

// 2. A file-named tile whose titled copy came later and was dropped.
writeSite(path.join(run, "02-cnn"), {
  "site.json": { url: "https://cnn.example/", location: "https://cnn.example/" },
  "entities.json": [tile({ uid: 7, name: "loop_720.mp4", filenameLike: true, shown: "Video on cnn.example",
    url: "https://media.example/loop_720.mp4" })],
  "emits.json": [
    { type: "media", url: "https://media.example/loop_720.mp4", name: "loop_720.mp4", listener: "onHeadersReceived", tabId: 5, _t: 1000 },
    { type: "media", url: "https://media.example/loop_720.mp4", name: "Fires spread across California", listener: "contentScript", tabId: 5, _t: 1400 },
  ],
  "census.json": { frames: [], census: [topFrame({ url: "https://cnn.example/", title: "CNN", ogTitle: "CNN",
    siteName: "CNN", text: "News", media: [
      { tag: "video", src: "https://media.example/loop_720.mp4", sources: [], w: 300, h: 170, poster: "" },
      { tag: "video", src: "https://media.example/other.mp4", sources: [], w: 300, h: 170, poster: "" },
    ], iframes: [
      { src: "https://player.vimeo.com/video/76979871", loaded: true },
      { src: "https://geo.dailymotion.com/player/x1.html?video=x9abcde", loaded: false, placeholder: true },
    ], players: 0 })] },
  "requests.json": { tab: [{ u: "https://media.example/other.mp4", err: "NS_ERROR_ABORT" }], extension: [] },
});

// 3. A bot wall.
writeSite(path.join(run, "03-wall"), {
  "site.json": { url: "https://walled.example/" },
  "census.json": { frames: [], census: [topFrame({ url: "https://walled.example/", title: "Just a moment...",
    text: "Checking your browser before accessing", media: [], iframes: [] })] },
});

// 4. A site that failed outright.
writeSite(path.join(run, "04-failed"), { "site.json": { url: "https://down.example/", error: "no tab for null" } });

// 5. A clean page: one titled clip, an embed it captured, an ad clip left out.
writeSite(path.join(run, "05-clean"), {
  "site.json": { url: "https://clean.example/" },
  "entities.json": [
    tile({ uid: 9, name: "The mayor speaks", url: "https://player.vimeo.com/play/76979871/master.m3u8",
      origin: "https://vimeo.com/76979871" }),
    tile({ uid: 10, name: "Video", url: "https://ad.doubleclick.net/vast/creative.mp4" }),
  ],
  "census.json": { frames: [], census: [topFrame({ url: "https://clean.example/", title: "Clean news", ogTitle: "",
    siteName: "", text: "News", media: [{ tag: "video", src: "blob:https://clean.example/1", sources: [], w: 320,
      h: 180, poster: "", inPlayer: true, duration: 120 }],
    iframes: [{ src: "https://player.vimeo.com/video/76979871", loaded: true }] })] },
});

// 6. Two streaming players, one video tile.
writeSite(path.join(run, "06-players"), {
  "site.json": { url: "https://players.example/" },
  "entities.json": [tile({ uid: 11, name: "Match highlights", url: "https://bc.example/master.m3u8" })],
  "census.json": { frames: [], census: [topFrame({ url: "https://players.example/", title: "Sport", text: "x",
    media: [
      { tag: "video", src: "blob:https://players.example/a", w: 320, h: 180, inPlayer: true, duration: 90, poster: "p1.jpg" },
      { tag: "video", src: "blob:https://players.example/b", w: 320, h: 180, inPlayer: true, duration: 95, poster: "p2.jpg" },
    ], iframes: [] })] },
});

// 7. Three clips shown with the page's title; a podcast player whose card
//    holds its own chrome (a progress slider, a clock) beside the play button.
writeSite(path.join(run, "07-podcasts"), {
  "site.json": { url: "https://sport.example/" },
  "entities.json": [
    tile({ uid: 21, name: "Sport Daily - Noticias", url: "https://cdn.sport.example/a1.mp3", audio: true, mime: "audio/mpeg" }),
    tile({ uid: 22, name: "Sport Daily - Noticias", url: "https://cdn.sport.example/a2.mp3", audio: true, mime: "audio/mpeg" }),
    tile({ uid: 23, name: "Sport Daily - Noticias", url: "https://cdn.sport.example/a3.mp3", audio: true, mime: "audio/mpeg" }),
  ],
  "census.json": { frames: [], census: [topFrame({ url: "https://sport.example/", title: "Sport Daily - Noticias",
    ogTitle: "Sport Daily - Noticias", siteName: "Sport Daily", text: "x", media: [], iframes: [] })] },
  "around.json": [{ frameId: 0, url: "https://sport.example/", result: { matches: [
    { key: "21", attr: "src", tag: "audio", path: "div.core > audio",
      card: "<div class=\"core\"><audio src=\"a1.mp3\"></audio><button aria-label=\"Reproducir Axel Torres y el sueño del Real Madrid\"></button>"
        + "<div role=\"slider\" aria-label=\"Progreso de la reproducción\"></div><span aria-label=\"Tiempo actual: 00:00\">00:00</span>"
        + "<span aria-label=\"Duración total: 14:09\">14:09</span></div>", data: [] },
  ], media: [] } }],
});

// The older run: CNN's clip was untitled there too, under another name.
writeSite(path.join(older, "01-cnn"), {
  "site.json": { url: "https://cnn.example/" },
  "entities.json": [tile({ uid: 7, name: "loop_720.mp4", filenameLike: true, shown: "Video on media.example",
    url: "https://media.example/loop_720.mp4" })],
  "census.json": { frames: [], census: [topFrame({ url: "https://cnn.example/", title: "CNN", text: "x", media: [], iframes: [] })] },
});

const j1 = judgeSite(path.join(run, "01-dailymail"));
check("page-title: both clips flagged", j1.issues.filter((i) => i.kind === "page-title").length === 2, JSON.stringify(j1.issues.map((i) => i.kind)));
check("duplicate: one shared-name issue", j1.issues.filter((i) => i.kind === "duplicate").length === 1);
const fromConfig = j1.issues.find((i) => i.kind === "page-title" && i.url.includes("111"));
check("page has: the config title is offered", !!fromConfig && fromConfig.pageHas.some((c) => c.text === "Police officer shot in city centre"),
  JSON.stringify(fromConfig && fromConfig.pageHas));
check("state bad", j1.state === "bad");

const j2 = judgeSite(path.join(run, "02-cnn"));
const untitled = j2.issues.find((i) => i.kind === "untitled");
check("untitled: a file-named tile", !!untitled);
check("lost-name: the dropped titled copy is named", untitled && untitled.lostName && untitled.lostName.name === "Fires spread across California"
  && untitled.lostName.laterMs === 400, JSON.stringify(untitled && untitled.lostName));
const missed = j2.issues.find((i) => i.kind === "missed-video");
check("missed-video: the uncaptured file", !!missed && missed.text.includes("other.mp4"));
check("missed-video: says uBlock blocked it", !!missed && missed.text.includes("NS_ERROR_ABORT"));
check("captured file is not a miss", !j2.issues.some((i) => i.kind === "missed-video" && i.text.includes("loop_720")));
check("missed-embed: vimeo and the click-to-load dailymotion", j2.issues.filter((i) => i.kind === "missed-embed").length === 2,
  JSON.stringify(j2.issues.map((i) => i.text)));
check("missed-embed: placeholder labelled", j2.issues.some((i) => i.kind === "missed-embed" && i.text.startsWith("click-to-load")));

const j3 = judgeSite(path.join(run, "03-wall"));
check("blocked: bot wall not judged", j3.state === "blocked" && j3.issues.length === 0);
const j4 = judgeSite(path.join(run, "04-failed"));
check("failed: error reported", j4.state === "failed" && j4.notes[0].text.includes("no tab"));

const j5 = judgeSite(path.join(run, "05-clean"));
check("clean: no issues", j5.state === "ok", JSON.stringify(j5.issues));
check("clean: ad clip not a tile", j5.tiles.length === 1);

const j6 = judgeSite(path.join(run, "06-players"));
check("missed-player: two streaming players, one tile", j6.issues.some((i) => i.kind === "missed-player"));
check("missed-player is possible only", j6.state === "possible");

const j7 = judgeSite(path.join(run, "07-podcasts"));
const pod = j7.issues.find((i) => i.kind === "page-title" && (i.pageHas || []).length);
const offered = pod ? pod.pageHas.map((c) => c.text) : [];
check("page has: the play button's label with the title is offered", offered.some((t) => t.startsWith("Reproducir Axel Torres")),
  JSON.stringify(offered));
check("page has: a player's slider and clocks are not names", !offered.some((t) => /Progreso|Tiempo actual|Duración total/.test(t)),
  JSON.stringify(offered));

// The CLI: report written, the older run compared by URL.
const report = execFileSync(process.execPath, [path.join(here, "judge.mjs"), run, "--compare", older, "--verbose"],
  { encoding: "utf8" });
check("report: renamed against the older run", report.includes("renamed: \"Video on media.example\" → \"Video on cnn.example\""), report);
check("report: a site not in the older run says so", report.includes("not in the older run"));
check("report.md written", fs.existsSync(path.join(run, "report.md")));
check("judged.json written", fs.existsSync(path.join(run, "02-cnn", "judged.json")));
check("report: bad sites first", report.indexOf("news.example") < report.indexOf("clean.example"));
const plain = execFileSync(process.execPath, [path.join(here, "judge.mjs"), run], { encoding: "utf8" });
check("report: identical issues collapse to one line with a count",
  /page-title: audio shown as "Sport Daily - Noticias" \(×2\)/.test(plain), plain.split("\n").filter((l) => l.includes("Sport Daily")).join(" | "));

fs.rmSync(root, { recursive: true, force: true });
console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
