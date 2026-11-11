// pip-layout-harness.mjs: the stylesheet the capture extension puts on a tab
// in a picture-in-picture window (requests.js PIP_LAYOUT_CSS: the fullscreen
// video and nothing over it) in real Chromium.
//
//   node scripts/pip-layout-harness.mjs
//
// A player page shaped like a mobile video site: the <video> in a wrapper
// with a z-index of its own, the controls in a sibling above it, a scrim
// drawn as a pseudo-element. The sheet goes on the page as the extension
// puts it on a tab in the window (Chromium has no user-origin sheet from a
// page; an author sheet with the same rules stands in). Checks what is
// hidden, what the centre of the window hits, and that nothing changes
// outside fullscreen or on a fullscreen element with no video. The off/on
// messaging is the smoke's pip-layout section. Planted bugs must each fail
// it. SKIPs (exit 0) without Playwright.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { loadPlaywright, PLAYWRIGHT_HINT } from './load-playwright.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const REQUESTS = readFileSync(path.join(here, '../app/src/main/assets/webrequests/js/requests.js'), 'utf8');
const match = REQUESTS.match(/export const PIP_LAYOUT_CSS = ([\s\S]+?\}');/);
if (!match) {
    console.log('FAIL: PIP_LAYOUT_CSS not found in requests.js');
    process.exit(1);
}
const SOURCE = new Function('return ' + match[1])();

const chromium = (await loadPlaywright())?.chromium;
if (!chromium) {
    console.log(`SKIP: playwright not installed (${PLAYWRIGHT_HINT})`);
    process.exit(0);
}

const PAGE = `<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>
  body { margin: 0; }
  #player { position: relative; width: 400px; height: 225px; background: #000; }
  #player::after { content: ''; position: absolute; inset: 0; z-index: 60; background: rgba(0,0,0,.5); }
  .vc { position: relative; z-index: 10; width: 100%; height: 100%; }
  video { width: 100%; height: 100%; display: block; }
  .controls { position: absolute; inset: 0; z-index: 50; }
  .controls button { width: 100%; height: 100%; visibility: visible !important; }
  #gallery { width: 200px; height: 100px; }
</style></head><body>
<div id="player"><div class="vc"><video id="v"></video></div>
  <div class="controls"><button id="pause">pause</button></div></div>
<div id="gallery"><img id="pic" alt="" width="10" height="10"><span id="label">photo</span></div>
<button id="fsPlayer">player</button><button id="fsGallery">gallery</button>
<script>
  document.getElementById('fsPlayer').onclick = () => document.getElementById('player').requestFullscreen();
  document.getElementById('fsGallery').onclick = () => document.getElementById('gallery').requestFullscreen();
  window.__sheet = (css) => {
    let el = document.getElementById('pip-sheet');
    if (css == null) { if (el) el.remove(); return; }
    if (!el) { el = document.createElement('style'); el.id = 'pip-sheet'; document.head.appendChild(el); }
    el.textContent = css;
  };
</script></body></html>`;

// The button's own visibility: visible !important stands for a player that
// forces its controls on: the extension's sheet is user-origin and wins over
// it in Gecko; here the stand-in author sheet wins by coming later.
const MUTANTS = {
    'hides the video\'s ancestors': (s) => s.replace(':not(video):not(:has(video))', ':not(video)'),
    'pseudo-elements left': (s) => s.replace(/, :fullscreen:has\(video\)::before.*\*::after/, ''),
    'no fullscreen gate': (s) => s.split(':fullscreen:has(video)').join('body'),
    'no video gate': (s) => s.split(':fullscreen:has(video)').join(':fullscreen'),
};

async function run(browser, css) {
    const page = await browser.newPage({ viewport: { width: 412, height: 915 } });
    await page.setContent(PAGE);
    const failures = [];
    const check = (ok, name) => { if (!ok) failures.push(name); };
    const state = () => page.evaluate(() => {
        const vis = (sel) => getComputedStyle(document.querySelector(sel)).visibility;
        const r = document.getElementById('player').getBoundingClientRect();
        const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return {
            fs: document.fullscreenElement && document.fullscreenElement.id,
            controls: vis('.controls'),
            button: vis('#pause'),
            vc: vis('.vc'),
            video: vis('#v'),
            player: vis('#player'),
            after: getComputedStyle(document.getElementById('player'), '::after').visibility,
            label: vis('#label'),
            hit: hit && hit.id,
        };
    });
    const sheet = (on) => page.evaluate((c) => window.__sheet(c), on ? css : null);

    await sheet(true);
    let s = await state();
    check(s.controls === 'visible' && s.after === 'visible' && s.label === 'visible',
        'sheet on, not fullscreen: nothing hidden');
    await sheet(false);

    await page.click('#fsPlayer');
    await page.waitForFunction(() => document.fullscreenElement != null);
    s = await state();
    check(s.fs === 'player', 'fullscreen entered');
    check(s.controls === 'visible' && s.button === 'visible', 'fullscreen, no sheet: controls stay');

    await sheet(true);
    s = await state();
    check(s.controls === 'hidden' && s.button === 'hidden', 'PiP: controls hidden, a forced-visible one too');
    check(s.after === 'hidden', 'PiP: scrim pseudo-element hidden');
    check(s.video === 'visible' && s.vc === 'visible' && s.player === 'visible', 'PiP: the video and its ancestors stay');
    check(s.hit === 'v', 'PiP: the centre of the window is the video, got ' + s.hit);

    await sheet(false);
    s = await state();
    check(s.controls === 'visible' && s.after === 'visible', 'sheet off: controls back');

    await page.evaluate(() => document.exitFullscreen());
    await page.waitForFunction(() => document.fullscreenElement == null);
    await page.click('#fsGallery');
    await page.waitForFunction(() => document.fullscreenElement != null);
    await sheet(true);
    s = await state();
    check(s.label === 'visible', 'fullscreen with no video, sheet on: nothing hidden');

    await page.close();
    return failures;
}

const browser = await chromium.launch();
let bad = 0;
const base = await run(browser, SOURCE);
for (const f of base) {
    console.log('FAIL ' + f);
}
bad += base.length;
console.log(base.length === 0 ? 'ok: pip-layout sheet' : `${base.length} failure(s)`);

for (const [name, mutate] of Object.entries(MUTANTS)) {
    const mutated = mutate(SOURCE);
    if (mutated === SOURCE) {
        console.log(`MUTANT NOT APPLIED: ${name}`);
        bad++;
        continue;
    }
    const failures = await run(browser, mutated);
    if (failures.length === 0) {
        console.log(`MUTANT SURVIVED: ${name}`);
        bad++;
    } else {
        console.log(`mutant killed: ${name} (${failures[0]})`);
    }
}
await browser.close();
process.exit(bad === 0 ? 0 : 1);
