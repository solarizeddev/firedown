// site-audit.mjs — visit popular sites with the REAL capture code and report,
// with no person looking, every clip that would land in the Captured sheet
// with a wrong title, and every player that would land in it not at all.
//
// It replaces the loop "browse a site on the phone → see a bad title → export
// a HAR → debug the HAR": one command visits every site in
// scripts/site-audit/sites.txt (or the URLs given), RECORDS each visit, and
// judges what was captured (scripts/site-audit/judge.mjs: untitled, file-name,
// generic, site-name, page-title, duplicate titles; possible misses). The
// recording replays offline, so a fix can be checked against every site ever
// recorded before it ships.
//
// Each site runs in its own process (sim.mjs: the page in a real browser,
// the REAL content script and page-state bridge in every frame, the REAL
// background graph fed with the page's wire traffic). Needs Playwright and
// its Chromium: `sh scripts/setup-playwright.sh` installs both in your home
// folder, no root (add `firefox` for --browser firefox). --chromium PATH uses
// the system Chromium instead of Playwright's (a distribution Playwright has
// no build for). A live run needs a normal network (not the agent sandbox).
//
//   node scripts/site-audit.mjs                       every site in sites.txt
//   node scripts/site-audit.mjs https://a.example/ …  just these
//        [--sites FILE] [--only TEXT] [--out DIR] [--jobs N]
//        [--browser chromium|firefox] [--chromium PATH] [--headed] [--wait MS] [--locale es-ES]
//        [--no-consent] [--no-scroll] [--no-adblock] [--verbose] [--cs FILE] [--bridge FILE]
//        (--cs / --bridge run another revision of the content script or the
//        bridge, e.g. `git show main:…/content-script.js > /tmp/cs.js`)
//   node scripts/site-audit.mjs --replay [RUN_DIR] [--only TEXT] [--accept] [--verbose]
//        replays a recorded run offline against the CURRENT code and compares
//        each site with what was judged when it was recorded; exits 1 when a
//        site got worse. --accept makes the new results the baseline.
//   node scripts/site-audit.mjs --har PHONE.har [--url PAGE] [--verbose]
//        the same pipeline over a HAR exported from Firefox on the phone.
//
// Output: build/site-audit/<date-time>/ (gitignored): per site a folder with
// page.har.zip (the recording, no personal session: a fresh browser profile),
// fetches.json (the background's own requests), judged.json, result.json and
// log.txt (the background's debug log); and report.md for the whole run.
// To hand a failing site over for debugging, zip its folder.
//
// What it is not: the device. The engine is Chromium or Playwright's
// Firefox, not GeckoView; uBlock is not loaded (ads play, which the judge
// skips by host); YouTube's own extension is not loaded (its embeds are
// listed as notes); the native side (probe, file names) is not run — the
// sheet's tile title is derived the way CaptureSections derives it.
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from "node:fs";
import { spawn } from "node:child_process";
import { dirname, join, resolve, basename } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const self = fileURLToPath(import.meta.url);

const args = process.argv.slice(2);
const opts = { jobs: 3, browser: null, headed: false, wait: 10000, consent: true, scroll: true, adblock: true, verbose: false,
  sites: join(here, "site-audit/sites.txt"), urls: [], only: null, out: null, replay: null, accept: false,
  har: null, url: null, one: null, locale: null, cs: null, bridge: null };
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === "--one") opts.one = args[++i];
  else if (a === "--jobs") opts.jobs = Math.max(1, Number(args[++i]) || 1);
  else if (a === "--browser") opts.browser = args[++i];
  else if (a === "--chromium") opts.chromium = resolve(args[++i]);
  else if (a === "--headed") opts.headed = true;
  else if (a === "--wait") opts.wait = Number(args[++i]);
  else if (a === "--no-consent") opts.consent = false;
  else if (a === "--no-scroll") opts.scroll = false;
  else if (a === "--no-adblock") opts.adblock = false;
  else if (a === "--verbose" || a === "-v") opts.verbose = true;
  else if (a === "--sites") opts.sites = args[++i];
  else if (a === "--only") opts.only = args[++i];
  else if (a === "--out") opts.out = args[++i];
  else if (a === "--locale") opts.locale = args[++i];
  else if (a === "--accept") opts.accept = true;
  else if (a === "--har") opts.har = args[++i];
  else if (a === "--url") opts.url = args[++i];
  else if (a === "--cs") opts.cs = resolve(args[++i]);
  else if (a === "--bridge") opts.bridge = resolve(args[++i]);
  else if (a === "--replay") opts.replay = (args[i + 1] && !args[i + 1].startsWith("--")) ? args[++i] : "";
  else if (a === "--help" || a === "-h") { usage(); process.exit(0); }
  else if (/^https?:\/\//.test(a)) opts.urls.push(a);
  else { console.log(`unknown argument: ${a}`); usage(); process.exit(2); }
}

function usage() {
  console.log(`usage:
  node scripts/site-audit.mjs [URL...] [--sites FILE] [--only TEXT] [--out DIR] [--jobs N]
       [--browser chromium|firefox] [--chromium PATH] [--headed] [--wait MS] [--locale xx-XX] [--no-consent] [--no-scroll] [--no-adblock] [--verbose]
       [--cs FILE] [--bridge FILE]
  node scripts/site-audit.mjs --replay [RUN_DIR] [--only TEXT] [--accept] [--verbose]
  node scripts/site-audit.mjs --har FILE.har [--url PAGE] [--verbose]`);
}

if (opts.one) {
  await runOne(JSON.parse(opts.one));
  process.exit(0);
}

// --chromium reaches every site's process through the environment, which
// is where load-playwright.mjs reads it.
if (opts.chromium) {
  if (!existsSync(opts.chromium)) { console.log(`--chromium: no such file: ${opts.chromium}`); process.exit(2); }
  process.env.FIREDOWN_CHROMIUM = opts.chromium;
}
const { compare } = await import("./site-audit/judge.mjs");
const { loadPlaywright, playwrightSource, PLAYWRIGHT_HINT } = await import("./load-playwright.mjs");
const pw = await loadPlaywright();
if (!pw) {
  console.log(`playwright is not installed: run ${PLAYWRIGHT_HINT}`);
  process.exit(2);
}
// Chromium by default: it is what every Playwright install has, and the
// path this tool was tested on. Firefox (--browser firefox) is the closer
// engine to GeckoView and worth a run when a site behaves differently on
// the phone, but it has not been run here (the agent sandbox cannot
// download it).
if (!opts.browser) opts.browser = "chromium";

// One launch before any site, so a missing or broken browser is one clear
// line instead of the same long error from every site.
{
  const browserType = opts.browser === "firefox" ? pw.firefox : pw.chromium;
  try {
    const b = await browserType.launch();
    await b.close();
  } catch (e) {
    // Playwright's own advice comes boxed in ║ lines; it names npx commands
    // that need the global install, so it is left out.
    const first = String(e?.message || e).split("\n")
      .filter((l) => l.trim() && !/^\s*[║╔╚═]/.test(l)).slice(0, 4).join("\n  ");
    console.log(`${opts.browser} will not start:\n  ${first}`);
    console.log(opts.browser === "firefox"
      ? `install it with: ${PLAYWRIGHT_HINT} firefox`
      : `install it with: ${PLAYWRIGHT_HINT}\nor use the system one: --chromium /usr/bin/chromium`);
    process.exit(2);
  }
  console.log(`${playwrightSource()}\n`);
}


// ── modes ────────────────────────────────────────────────────────────────
async function liveMode() {
  let urls = opts.urls;
  if (!urls.length) {
    urls = readFileSync(opts.sites, "utf8").split("\n")
      .map((l) => l.replace(/#.*/, "").trim()).filter((l) => /^https?:\/\//.test(l));
  }
  if (opts.only) urls = urls.filter((u) => u.includes(opts.only));
  if (!urls.length) { console.log("no sites to visit"); process.exit(2); }
  const out = resolve(opts.out || join(root, "build/site-audit", stamp()));
  mkdirSync(out, { recursive: true });
  console.log(`site audit: ${urls.length} site(s), ${opts.browser}, ${opts.jobs} at a time → ${out}\n`);
  const slugs = new Set();
  const jobs = urls.map((url) => {
    let slug = slugOf(url);
    while (slugs.has(slug)) slug += "_";
    slugs.add(slug);
    const dir = join(out, slug);
    mkdirSync(dir, { recursive: true });
    return { url, slug, dir, cfg: { url, mode: "live", dir, record: join(dir, "page.har.zip"),
      fetchLog: join(dir, "fetches.json"), browser: opts.browser, headed: opts.headed, wait: opts.wait,
      consent: opts.consent, scroll: opts.scroll, adblock: opts.adblock, locale: opts.locale, cs: opts.cs, bridge: opts.bridge, out: "judged.json" } };
  });
  const results = await pool(jobs, async (job) => {
    const j = await child(job.cfg, job.dir);
    printSite(job, j);
    return { ...job, j };
  });
  writeFileSync(join(out, "report.md"), markdownReport(results, out));
  printSummary(results, out);
}

async function replayMode() {
  const dir = resolve(opts.replay || latestRun());
  if (!dir || !existsSync(dir)) { console.log("no recorded run to replay (run a live audit first)"); process.exit(2); }
  let sites = readdirSync(dir).filter((d) => existsSync(join(dir, d, "page.har.zip")) && existsSync(join(dir, d, "judged.json")));
  if (opts.only) sites = sites.filter((s) => s.includes(opts.only));
  console.log(`replay: ${sites.length} recorded site(s) from ${dir}, ${opts.browser}\n`);
  const results = await pool(sites, async (slug) => {
    const siteDir = join(dir, slug);
    const before = JSON.parse(readFileSync(join(siteDir, "judged.json"), "utf8"));
    const cfg = { url: before.url, mode: "replay", dir: siteDir, har: join(siteDir, "page.har.zip"),
      fetchLog: join(siteDir, "fetches.json"), browser: opts.browser, headed: opts.headed, wait: opts.wait,
      consent: opts.consent, scroll: opts.scroll, adblock: opts.adblock, locale: opts.locale, cs: opts.cs, bridge: opts.bridge, out: "replayed.json" };
    const after = await child(cfg, siteDir);
    const job = { url: before.url, slug, dir: siteDir };
    if (!after || after.error) { printSite(job, after); return { ...job, j: after, cmp: null }; }
    const cmp = compare(before, after);
    printComparison(job, before, after, cmp);
    if (opts.accept) writeFileSync(join(siteDir, "judged.json"), JSON.stringify(after, null, 1));
    return { ...job, j: after, cmp };
  });
  const worse = results.filter((r) => r.cmp && r.cmp.verdict === "worse");
  const better = results.filter((r) => r.cmp && r.cmp.verdict === "better");
  console.log(`\n${better.length} better, ${worse.length} worse, ${results.length - better.length - worse.length} same or not comparable`
    + (opts.accept ? " — accepted as the new baseline" : ""));
  process.exit(worse.length && !opts.accept ? 1 : 0);
}

async function harMode() {
  const harPath = resolve(opts.har);
  const dir = resolve(opts.out || join(root, "build/site-audit", `har-${basename(harPath).replace(/[^\w.-]+/g, "_").slice(0, 60)}`));
  mkdirSync(dir, { recursive: true });
  const cfg = { url: opts.url, mode: "replay", dir, har: harPath, browser: opts.browser, headed: opts.headed,
    wait: opts.wait, consent: opts.consent, scroll: opts.scroll, adblock: opts.adblock, locale: opts.locale, cs: opts.cs, bridge: opts.bridge, out: "judged.json" };
  const j = await child(cfg, dir);
  printSite({ url: (j && j.url) || opts.url || harPath, slug: basename(dir), dir }, j, true);
  console.log(`\nfiles: ${dir}`);
}

// ── one site, in a child process ─────────────────────────────────────────
async function runOne(cfg) {
  const { simulate } = await import("./site-audit/sim.mjs");
  const { judge: judgeFn } = await import("./site-audit/judge.mjs");
  let result;
  try {
    result = await simulate(cfg);
  } catch (e) {
    result = { error: String(e?.stack || e) };
  }
  if (result.skipped || result.error) {
    writeFileSync(join(cfg.dir, cfg.out), JSON.stringify({ url: cfg.url, error: result.error || result.skipped }));
    return;
  }
  writeFileSync(join(cfg.dir, "log.txt"), (result.logs || []).join("\n"));
  const { logs, ...rest } = result;
  writeFileSync(join(cfg.dir, cfg.out.replace(/\.json$/, ".result.json")), JSON.stringify(rest));
  const j = judgeFn(result);
  writeFileSync(join(cfg.dir, cfg.out), JSON.stringify(j, null, 1));
}

function child(cfg, dir) {
  return new Promise((done) => {
    const p = spawn(process.execPath, [self, "--one", JSON.stringify(cfg)], { stdio: ["ignore", "pipe", "pipe"] });
    let err = "";
    p.stdout.on("data", () => {});
    p.stderr.on("data", (d) => { err += d; });
    const limit = (cfg.wait || 10000) + 150000;
    const timer = setTimeout(() => {
      p.kill("SIGTERM");
      setTimeout(() => p.kill("SIGKILL"), 5000);
    }, limit);
    p.on("exit", () => {
      clearTimeout(timer);
      const file = join(dir, cfg.out);
      let j = null;
      try { j = JSON.parse(readFileSync(file, "utf8")); } catch (_) { j = null; }
      if (!j) j = { url: cfg.url, error: `the run did not finish${err ? `: ${err.trim().split("\n").slice(-3).join(" | ")}` : ""}` };
      if (err.trim()) writeFileSync(join(dir, "stderr.txt"), err);
      done(j);
    });
  });
}

async function pool(items, fn) {
  const out = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(opts.jobs, items.length) }, worker));
  return out;
}

// ── output ───────────────────────────────────────────────────────────────
const ISSUE_ORDER = ["duplicate", "page-title", "site-name", "untitled", "filename", "generic",
  "missed-player", "missed-video", "missed-audio", "missed-embed"];
function headline(job, j) {
  if (!j || j.error) return `! ${hostPath(job.url)}  — ${j?.error?.split("\n")[0] || "no result"}`;
  const video = j.media.filter((c) => c.kind === "video").length;
  const audio = j.media.filter((c) => c.kind === "audio").length;
  const names = j.issues.filter((i) => !i.possible).length;
  const misses = j.issues.filter((i) => i.possible).length;
  const mark = names ? "✗" : (misses ? "?" : "✓");
  const pics = j.pictures.total ? ` · ${j.pictures.named}/${j.pictures.total} pictures named` : "";
  return `${mark} ${hostPath(job.url).padEnd(38)} ${video} video · ${audio} audio · ${names} bad name(s)`
    + `${misses ? ` · ${misses} possible miss(es)` : ""}${pics} · ${Math.round((j.ms || 0) / 1000)} s`;
}
function printSite(job, j, always = false) {
  console.log(headline(job, j));
  if (!j || j.error) return;
  for (const w of j.warnings || []) console.log(`    warning: ${w}`);
  const issues = [...j.issues].sort((a, b) => ISSUE_ORDER.indexOf(a.kind) - ISSUE_ORDER.indexOf(b.kind));
  for (const i of issues) {
    console.log(`    ${i.kind.padEnd(13)} ${i.possible ? "(possible) " : ""}${i.text}${i.url ? `\n                  ${i.url.slice(0, 140)}` : ""}`);
  }
  if (opts.verbose || always) {
    for (const c of j.media) {
      console.log(`    ${c.issues.length ? "·" : "✓"} ${c.kind.padEnd(5)} ${JSON.stringify(c.shown).slice(0, 90)}`
        + `  ${c.img ? "picture" : "no picture"}  via ${c.via || "?"}${c.frameId ? ` (frame ${c.frameId})` : ""}\n        ${c.url.slice(0, 140)}`);
    }
    for (const n of j.notes) console.log(`    note: ${n.text}`);
    if (j.consent.length) console.log(`    consent: ${j.consent.map((c) => c.hit).join(", ")}`);
    if (j.errors.length) console.log(`    errors: ${j.errors.slice(0, 5).join(" | ")}`);
  }
}
function printComparison(job, before, after, cmp) {
  const sign = { better: "↑", worse: "↓", same: "=" }[cmp.verdict];
  console.log(`${sign} ${hostPath(job.url).padEnd(38)} captures ${cmp.mediaBefore}→${cmp.mediaAfter} · issues ${cmp.issuesBefore}→${cmp.issuesAfter}`);
  for (const ch of cmp.changes) {
    if (ch.kind === "renamed") console.log(`    renamed  ${JSON.stringify(ch.before.shown).slice(0, 60)} → ${JSON.stringify(ch.after.shown).slice(0, 60)}`);
    else if (ch.kind === "new") console.log(`    new      ${JSON.stringify(ch.after.shown).slice(0, 60)}  ${ch.after.url.slice(0, 80)}`);
    else if (ch.kind === "lost") console.log(`    lost     ${JSON.stringify(ch.before.shown).slice(0, 60)}  ${ch.before.url.slice(0, 80)}`);
    else if (ch.kind === "issues") console.log(`    issues   ${JSON.stringify(ch.after.shown).slice(0, 60)}: [${ch.before.issues}] → [${ch.after.issues}]`);
    else if (opts.verbose) console.log(`    ${ch.kind.padEnd(8)} ${JSON.stringify(ch.after.shown).slice(0, 60)}`);
  }
  if (opts.verbose) printSite(job, after);
}
function printSummary(results, out) {
  const bad = results.filter((r) => r.j && !r.j.error && r.j.issues.some((i) => !i.possible));
  const maybe = results.filter((r) => r.j && !r.j.error && !r.j.issues.some((i) => !i.possible) && r.j.issues.length);
  const failed = results.filter((r) => !r.j || r.j.error);
  console.log(`\n${results.length} site(s): ${bad.length} with bad names, ${maybe.length} with possible misses only, `
    + `${failed.length} did not load, ${results.length - bad.length - maybe.length - failed.length} clean`);
  console.log(`report: ${join(out, "report.md")}`);
  if (bad.length || maybe.length) {
    console.log(`\nto look closer at one site:   node scripts/site-audit.mjs --replay ${out} --only ${(bad[0] || maybe[0]).slug} --verbose`);
    console.log(`after a fix, check every site: node scripts/site-audit.mjs --replay ${out}`);
  }
}
function markdownReport(results, out) {
  const lines = [`# Site audit ${basename(out)}`, "", "| site | video | audio | bad names | possible misses | pictures named |", "|---|---|---|---|---|---|"];
  for (const r of results) {
    const j = r.j;
    if (!j || j.error) { lines.push(`| ${hostPath(r.url)} | — | — | did not load | | |`); continue; }
    lines.push(`| ${hostPath(r.url)} | ${j.media.filter((c) => c.kind === "video").length} | ${j.media.filter((c) => c.kind === "audio").length}`
      + ` | ${j.issues.filter((i) => !i.possible).length} | ${j.issues.filter((i) => i.possible).length} | ${j.pictures.named}/${j.pictures.total} |`);
  }
  for (const r of results) {
    const j = r.j;
    lines.push("", `## ${hostPath(r.url)}`, "", `${r.url} · folder \`${r.slug}\``, "");
    if (!j || j.error) { lines.push(`Did not load: ${(j && j.error) || "no result"}`); continue; }
    for (const i of j.issues) lines.push(`- **${i.kind}**${i.possible ? " (possible)" : ""}: ${i.text}${i.url ? ` — ${i.url}` : ""}`);
    if (!j.issues.length) lines.push("No problems found.");
    lines.push("", "| | kind | title in the sheet | picture | via | URL |", "|---|---|---|---|---|---|");
    for (const c of j.media) {
      lines.push(`| ${c.issues.length ? c.issues.join(", ") : "ok"} | ${c.kind} | ${c.shown.replace(/\|/g, "\\|")} | ${c.img ? "yes" : "no"} | ${c.via || ""} | ${c.url.slice(0, 100)} |`);
    }
    for (const n of j.notes) lines.push(`- ${n.text}`);
  }
  return lines.join("\n") + "\n";
}

// ── helpers ──────────────────────────────────────────────────────────────
function stamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
}
function slugOf(url) {
  try {
    const u = new URL(url);
    const path = u.pathname.replace(/\/+$/, "").replace(/\.html?$/, "").replace(/[^\w]+/g, "-").replace(/^-|-$/g, "");
    return (u.hostname.replace(/^www\./, "") + (path ? `_${path}` : "")).slice(0, 80);
  } catch (_) {
    return url.replace(/[^\w.-]+/g, "_").slice(0, 80);
  }
}
function hostPath(url) {
  try {
    const u = new URL(url);
    return (u.hostname.replace(/^www\./, "") + u.pathname.replace(/\/$/, "")).slice(0, 38);
  } catch (_) {
    return String(url).slice(0, 38);
  }
}
function latestRun() {
  const base = join(root, "build/site-audit");
  if (!existsSync(base)) return "";
  const runs = readdirSync(base).filter((d) => /^\d{4}-\d\d-\d\d_\d{4}/.test(d) && statSync(join(base, d)).isDirectory()).sort();
  return runs.length ? join(base, runs[runs.length - 1]) : "";
}

// Last, so every constant above is initialised before a mode runs.
if (opts.har) await harMode();
else if (opts.replay !== null) await replayMode();
else await liveMode();
