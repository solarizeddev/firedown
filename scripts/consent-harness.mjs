// The cookie-banner extension end to end, in a real page: the package's own
// content script (js/autoconsent-content.js) runs in Chromium against sample
// consent banners, talking to the REAL background module (js/background.js,
// in node) through a stand-in for the extension messaging. The background's
// "eval" runs the library's snippet in the page.
//
//   node scripts/consent-harness.mjs        (SKIPs without Playwright)
//
// Pages:
//   cookieyes    — a CookieYes banner (DOM rule): "Reject" must be clicked,
//                  never "Accept".
//   cookielaw    — a Cookie Law Info bar whose detection and reject are
//                  page-world checks (window.CLI): the eval path.
//   off          — the CookieYes page with the switch off: nothing clicked.
//   plain        — a page with no banner: nothing clicked, nothing left hidden.
//
// Chromium runs the content script as a page script (no isolated world); the
// MAIN-world part is the background's executeScript, which the stand-in maps
// to the page. What only a device confirms: Firefox's content-script world
// and scripting.executeScript({world: "MAIN"}) in GeckoView.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadPlaywright } from './load-playwright.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const ext = join(here, '../app/src/main/assets/consent');

const playwright = await loadPlaywright();
if (!playwright) {
  console.log('SKIP consent-harness: Playwright not available');
  process.exit(0);
}

let failures = 0;
function expect(cond, label) {
  if (cond) {
    console.log('ok  ', label);
  } else {
    console.error('FAIL', label);
    failures++;
  }
}

// ── The background, with a messaging stand-in ───────────────────────────
const portListeners = [];
const bgListeners = [];
let currentPage = null;

globalThis.browser = {
  runtime: {
    sendNativeMessage: async () => false,
    connectNative: () => ({ onMessage: { addListener: (fn) => portListeners.push(fn) } }),
    onMessage: { addListener: (fn) => bgListeners.push(fn) },
    getURL: (path) => pathToFileURL(join(ext, path)).href,
  },
  tabs: {
    sendMessage: async (tabId, msg) => {
      if (currentPage) await currentPage.evaluate((m) => window.__deliver(m), msg).catch(() => {});
    },
  },
  scripting: {
    executeScript: async ({ func }) => {
      const result = await currentPage.evaluate(func);
      return [{ result }];
    },
  },
};
globalThis.fetch = async (url) => {
  const body = readFileSync(fileURLToPath(url), 'utf8');
  return { json: async () => JSON.parse(body) };
};
await import(pathToFileURL(join(ext, 'js/background.js')));
const config = (enabled) => portListeners.forEach((fn) => fn({ type: 'config', enabled }));

// ── Pages ───────────────────────────────────────────────────────────────
const STYLE = '<style>.bar{position:fixed;bottom:0;left:0;right:0;padding:16px;background:#eee}</style>';
const PAGES = {
  cookieyes: `<!doctype html><html><head><title>News</title>${STYLE}</head><body>
    <main id="content"><h1>Article</h1><p>Text.</p></main>
    <div class="cky-consent-container bar">
      <p>We use cookies.</p>
      <button data-cky-tag="accept-button" onclick="window.__choice='accept';this.parentNode.remove()">Accept all</button>
      <button data-cky-tag="reject-button" onclick="window.__choice='reject';this.parentNode.remove()">Reject all</button>
    </div></body></html>`,
  cookielaw: `<!doctype html><html><head><title>Shop</title>${STYLE}
    <script>window.CLI = { disableAllCookies() { window.__cliDisabled = true; },
      reject_close() { window.__cliRejected = true; }, accept_close() { window.__cliAccepted = true; } };</script>
    </head><body><main id="content"><h1>Shop</h1></main>
    <div id="cookie-law-info-bar" class="bar"><span>Cookies.</span>
      <a data-cli_action="accept" onclick="window.CLI.accept_close()">Accept</a></div></body></html>`,
  plain: `<!doctype html><html><head><title>Plain</title></head><body>
    <main id="content"><h1>Nothing to consent to</h1></main></body></html>`,
};

const content = readFileSync(join(ext, 'js/autoconsent-content.js'), 'utf8');
const browserApp = await playwright.chromium.launch();
const context = await browserApp.newContext();
await context.route('https://consent.test/**', (route) => {
  const name = new URL(route.request().url()).pathname.slice(1);
  route.fulfill({ status: 200, contentType: 'text/html', body: PAGES[name] || PAGES.plain });
});

async function open(name) {
  const page = await context.newPage();
  currentPage = page;
  await page.exposeBinding('__toBackground', (source, msg) => {
    const frameId = source.frame === page.mainFrame() ? 0 : 1;
    for (const fn of bgListeners) fn(msg, { tab: { id: 1 }, frameId, url: source.frame.url() });
  });
  // The extension messaging the content script uses, then the script itself.
  await page.addInitScript(() => {
    const listeners = [];
    window.__deliver = (m) => { for (const fn of listeners) fn(m); };
    window.chrome = {
      runtime: {
        sendMessage: (m) => window.__toBackground(m),
        onMessage: { addListener: (fn) => listeners.push(fn) },
        connect: () => ({}),
      },
    };
  });
  await page.addInitScript({ content });
  await page.goto(`https://consent.test/${name}`);
  return page;
}

async function poll(page, fn, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await page.evaluate(fn).catch(() => false)) return true;
    await new Promise((r) => setTimeout(r, 100));
  }
  return false;
}

try {
  config(true);

  let page = await open('cookieyes');
  expect(await poll(page, () => window.__choice === 'reject', 10000), 'cookieyes: "Reject all" is clicked');
  expect(await page.evaluate(() => window.__choice !== 'accept'), 'cookieyes: "Accept all" is never clicked');
  expect(await page.evaluate(() => getComputedStyle(document.getElementById('content')).display !== 'none'),
    'cookieyes: the page itself stays visible');
  await page.close();

  page = await open('cookielaw');
  expect(await poll(page, () => window.__cliRejected === true, 10000),
    'cookielaw: detected and rejected through the page-world checks (eval)');
  expect(await page.evaluate(() => !window.__cliAccepted), 'cookielaw: never accepted');
  expect(await poll(page, () => {
    const bar = document.getElementById('cookie-law-info-bar');
    return !bar || getComputedStyle(bar).display === 'none' || bar.offsetParent === null;
  }, 3000), 'cookielaw: the bar is hidden');
  await page.close();

  config(false);
  page = await open('cookieyes');
  await new Promise((r) => setTimeout(r, 4000));
  expect(await page.evaluate(() => window.__choice === undefined), 'off: with the switch off nothing is clicked');
  expect(await page.evaluate(() => getComputedStyle(document.querySelector('.cky-consent-container')).display !== 'none'),
    'off: the banner stays as the site made it');
  await page.close();

  config(true);
  page = await open('plain');
  await new Promise((r) => setTimeout(r, 4000));
  expect(await page.evaluate(() => getComputedStyle(document.getElementById('content')).display !== 'none'
    && getComputedStyle(document.body).overflow !== 'hidden'), 'plain: a page with no banner is left as it is');
  await page.close();
} finally {
  await browserApp.close();
}

if (failures) {
  console.error(`\n${failures} failure(s)`);
  process.exit(1);
}
console.log('\nconsent-harness: all checks passed');
process.exit(0);
