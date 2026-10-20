// load-playwright.mjs — finds Playwright for the browser harnesses,
// wherever it was installed, in this order:
//   1. the repo's own node_modules (or NODE_PATH);
//   2. the private folder scripts/setup-playwright.sh installs into,
//      ${XDG_CACHE_HOME:-~/.cache}/firedown-playwright: no root, nothing
//      global, nothing in the repo; its browsers are in browsers/ beside the
//      package, so PLAYWRIGHT_BROWSERS_PATH is pointed there (unless set);
//   3. npm's global prefix (`npm i -g playwright`, where that is writable).
// Returns the module, or null so a harness can SKIP with PLAYWRIGHT_HINT.
//
// chromium.launch() starts whichever of Playwright's two Chromium builds is
// there: the headless shell (the default headless launch) or the full
// Chromium (channel "chromium"). An install that got one and not the other
// (an interrupted download) still runs.
//
// FIREDOWN_CHROMIUM=/path/to/chromium makes every chromium.launch() use that
// binary instead of the Chromium Playwright downloaded: for a distribution
// Playwright builds nothing for, where its fallback build will not start. A
// Chromium far from the version this Playwright was built against may not
// speak its protocol; Playwright's own build is the tested path.
import { createRequire } from "node:module";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export const PLAYWRIGHT_HINT = "sh scripts/setup-playwright.sh";

export function privatePlaywrightDir() {
  return join(process.env.XDG_CACHE_HOME || join(homedir(), ".cache"), "firedown-playwright");
}

// Where the module came from and its version, for a run's header line.
let source = null;

export function playwrightSource() {
  return source;
}

let loaded;

export async function loadPlaywright() {
  if (loaded === undefined) {
    loaded = await find();
    if (loaded && process.env.FIREDOWN_CHROMIUM) {
      useChromiumOverride(loaded, process.env.FIREDOWN_CHROMIUM);
    } else if (loaded) {
      useEitherChromiumBuild(loaded);
    }
  }
  return loaded;
}

async function find() {
  try {
    const pw = await import("playwright");
    source = describe(createRequire(import.meta.url), "node_modules");
    return pw;
  } catch (_) { /* not next to the repo */ }
  for (const dir of [privatePlaywrightDir(), npmGlobalRoot()]) {
    if (!dir) {
      continue;
    }
    const base = dir.endsWith("node_modules") ? dir : join(dir, "node_modules");
    if (!existsSync(join(base, "playwright"))) {
      continue;
    }
    try {
      // Read by Playwright when it loads, so it is set first.
      const browsers = join(dir, "browsers");
      if (dir === privatePlaywrightDir() && !process.env.PLAYWRIGHT_BROWSERS_PATH && existsSync(browsers)) {
        process.env.PLAYWRIGHT_BROWSERS_PATH = browsers;
      }
      const req = createRequire(join(base, "noop.js"));
      const pw = req("playwright");
      source = describe(req, dir);
      return pw;
    } catch (_) { /* a broken install: try the next place */ }
  }
  return null;
}

function npmGlobalRoot() {
  try {
    return execFileSync("npm", ["root", "-g"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
  } catch (_) {
    return null;
  }
}

function describe(req, where) {
  let version = "?";
  try {
    version = req("playwright/package.json").version;
  } catch (_) { /* an exports map without package.json */ }
  return `playwright ${version} (${where})`;
}

function useEitherChromiumBuild(pw) {
  if (!pw.chromium) {
    return;
  }
  const launch = pw.chromium.launch.bind(pw.chromium);
  pw.chromium.launch = async (options = {}) => {
    try {
      return await launch(options);
    } catch (e) {
      if (options.executablePath || !/Executable doesn't exist/.test(String(e?.message))) {
        throw e;
      }
      const other = { ...options };
      if (other.channel === "chromium") {
        delete other.channel;
      } else if (!other.channel) {
        other.channel = "chromium";
      } else {
        throw e;
      }
      // The first error names the missing build, the clearer of the two.
      return launch(other).catch(() => { throw e; });
    }
  };
}

function useChromiumOverride(pw, exe) {
  if (!pw.chromium) {
    return;
  }
  const launch = pw.chromium.launch.bind(pw.chromium);
  // A channel names one of Playwright's own builds; with a binary of our own
  // it would only conflict.
  pw.chromium.launch = (options = {}) => {
    const { channel, ...rest } = options;
    return launch({ ...rest, executablePath: rest.executablePath || exe });
  };
  source = `${source}, chromium ${exe}`;
}
