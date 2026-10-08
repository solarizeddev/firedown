// Smoke test for the built-in icons extension's content script — run with:
//   node scripts/icons-smoke.mjs
//
// Loads assets/icons/icons.js in a vm context under a stubbed window /
// document / browser and checks what it sends to the app: one message at
// load, nothing on a normal pageshow, and a FRESH message when the page is
// restored from the back/forward cache (pageshow with persisted=true). The
// bfcache resend is what keeps a tab's favicon after a cross-host Back: the
// app clears the tab icon when the tab leaves a site, and a restored page is
// the same document, so the load-time send never runs again.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app/src/main/assets/icons/icons.js"), "utf8");

let failures = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log(`ok    ${name}`);
  } else {
    failures++;
    console.log(`FAIL  ${name}${detail ? " — " + detail : ""}`);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ── stubs ──────────────────────────────────────────────────────────────────
class DOMTokenList {
  constructor(values) { this.values = values; }
  [Symbol.iterator]() { return this.values[Symbol.iterator](); }
}

// The page's <head>: rel → [{href, sizes, type}], and meta property/name → content.
const page = {
  links: {
    "shortcut icon": [{ href: "https://abs.twimg.com/favicons/twitter.3.ico", sizes: new DOMTokenList([]) }],
    "apple-touch-icon": [{ href: "https://abs.twimg.com/icon-ios.png", sizes: new DOMTokenList(["192x192"]) }],
  },
  metas: { "og:image": "https://pbs.twimg.com/card.jpg" },
};

const location = { href: "https://x.com/home" };
const sent = [];
const pageshowListeners = [];
const consoleCalls = [];
let rejectNext = false;

const documentStub = {
  location,
  title: "Home / X",
  querySelectorAll(selector) {
    let m = selector.match(/^link\[rel="(.+)"\]$/);
    if (m) {
      return (page.links[m[1]] || []).map((l) => ({ href: l.href, sizes: l.sizes, type: "" }));
    }
    m = selector.match(/^meta\[(?:property|name)="(.+)"\]$/);
    if (m) {
      const content = page.metas[m[1]];
      return content ? [{ content }] : [];
    }
    return [];
  },
};

const windowStub = {
  addEventListener(type, fn) {
    if (type === "pageshow") pageshowListeners.push(fn);
  },
};

const browserStub = {
  runtime: {
    sendNativeMessage(app, message) {
      sent.push({ app, message: JSON.parse(JSON.stringify(message)) });
      if (rejectNext) {
        rejectNext = false;
        return Promise.reject(new Error("port closed"));
      }
      return Promise.resolve(undefined);
    },
  },
};

const consoleStub = new Proxy({}, { get: (_, k) => (...a) => consoleCalls.push([k, a]) });

const unhandled = [];
process.on("unhandledRejection", (reason) => unhandled.push(reason));

const context = vm.createContext({
  window: windowStub,
  document: documentStub,
  browser: browserStub,
  console: consoleStub,
  DOMTokenList,
});
vm.runInContext(source, context, { filename: "icons.js" });

function firePageshow(persisted) {
  for (const fn of pageshowListeners) fn({ type: "pageshow", persisted });
}

// ── load ───────────────────────────────────────────────────────────────────
check("load: exactly one message", sent.length === 1, `sent ${sent.length}`);
const first = sent[0] && sent[0].message;
check("load: sent to the \"icons\" native app", sent[0] && sent[0].app === "icons");
check("load: carries the page url and title",
  first && first.url === "https://x.com/home" && first.title === "Home / X");
const ico = first && first.icons.find((i) => i.type === "shortcut icon");
check("load: collects the standard favicon", ico && ico.href.endsWith("twitter.3.ico"));
const apple = first && first.icons.find((i) => i.type === "apple-touch-icon");
check("load: keeps declared sizes as a list", apple && JSON.stringify(apple.sizes) === '["192x192"]');
check("load: collects og:image (the app scores it out, but it is reported)",
  first && first.icons.some((i) => i.type === "og:image"));

// ── a normal pageshow (fires on every load) must not double-send ──────────
firePageshow(false);
check("pageshow persisted=false: no resend", sent.length === 1, `sent ${sent.length}`);

// ── bfcache restore: resend, re-collected from the CURRENT document ────────
location.href = "https://x.com/home?restored=1";
page.links["shortcut icon"][0].href = "https://abs.twimg.com/favicons/twitter-pip.3.ico";
firePageshow(true);
check("pageshow persisted=true: resends", sent.length === 2, `sent ${sent.length}`);
const second = sent[1] && sent[1].message;
check("resend: names the restored document's current url",
  second && second.url === "https://x.com/home?restored=1");
check("resend: re-reads the head (not a cached list)",
  second && second.icons.some((i) => i.href.endsWith("twitter-pip.3.ico")));

// ── a failed send is swallowed, silently ──────────────────────────────────
rejectNext = true;
firePageshow(true);
await sleep(10);
check("rejected send: no unhandled rejection", unhandled.length === 0, String(unhandled[0]));
check("no console output (release builds must be silent)", consoleCalls.length === 0,
  JSON.stringify(consoleCalls[0]));

console.log(failures ? `\n${failures} FAILED` : "\nall passed");
process.exit(failures ? 1 : 0);
