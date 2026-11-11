// youtube-pip-smoke.mjs: the youtube@ extension's pip-fix.js (mobile YouTube
// in a picture-in-picture window) run as the browser runs it. The content
// script injects a page-world <script>; here a stub document runs that
// script's text in the same vm context, a stand-in for the page.
//
//   node scripts/youtube-pip-smoke.mjs
//
// Checks the gate (host, top frame), the outer size and exitFullscreen in a
// full window, in the small window, in landscape fullscreen, and the
// threshold read from the page's config (not cached before the config
// exists). Then planted bugs must each fail it.

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = readFileSync(path.join(here, '../app/src/main/assets/youtube/pip-fix.js'), 'utf8');

function makePage({ host = 'm.youtube.com', top = true, ytcfgRatio = null, defineYtcfg = true } = {}) {
    const state = { ow: 384, oh: 853, exits: 0, injected: false };
    class Document {}
    Document.prototype.exitFullscreen = function () { state.exits++; return Promise.resolve(); };
    const win = { screen: { width: 384, height: 853 }, Document, location: { hostname: host } };
    Object.defineProperty(win, 'outerWidth', { get() { return state.ow; }, configurable: true, enumerable: true });
    Object.defineProperty(win, 'outerHeight', { get() { return state.oh; }, configurable: true, enumerable: true });
    win.window = win;
    win.top = top ? win : {};
    const setYtcfg = (ratio) => {
        const flags = ratio == null ? 'a=1' : `a=1&html5_picture_in_picture_logging_onresize_ratio=${ratio}&b=2`;
        win.ytcfg = { get: () => ({ WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH: { serializedExperimentFlags: flags } }) };
    };
    if (defineYtcfg) setYtcfg(ytcfgRatio);
    const ctx = vm.createContext(win);
    win.document = {
        head: null,
        documentElement: {
            appendChild(node) {
                state.injected = true;
                vm.runInContext(node.textContent, ctx);
            },
        },
        createElement() { return { textContent: '', remove() {} }; },
    };
    return { win, ctx, state, doc: new Document(), setYtcfg };
}

function runContentScript(source, page) {
    vm.runInContext(source, page.ctx);
}

let failures = 0;
function check(name, ok) {
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}`);
    if (!ok) failures++;
}

function suite(source, quiet) {
    let failed = 0;
    const c = quiet ? (n, ok) => { if (!ok) failed++; } : (n, ok) => { check(n, ok); if (!ok) failed++; };

    // Gate.
    for (const [label, opts] of [['www.youtube.com', { host: 'www.youtube.com' }], ['an iframe', { top: false }]]) {
        const p = makePage(opts);
        runContentScript(source, p);
        c(`gate: nothing injected on ${label}`, !p.state.injected);
    }

    // Full window, small window, landscape.
    {
        const p = makePage();
        runContentScript(source, p);
        c('gate: injected on the top frame of m.youtube.com', p.state.injected);
        c('full window: real outer size', p.win.outerWidth === 384 && p.win.outerHeight === 853);
        p.doc.exitFullscreen();
        c('full window: exitFullscreen works', p.state.exits === 1);
        p.state.ow = 193; p.state.oh = 108;
        c('pip window: outer size is the screen', p.win.outerWidth === 384 && p.win.outerHeight === 853);
        const r = p.doc.exitFullscreen();
        c('pip window: exitFullscreen ignored', p.state.exits === 1 && r === undefined);
        p.state.ow = 853; p.state.oh = 384;
        c('landscape fullscreen: real outer size', p.win.outerWidth === 853 && p.win.outerHeight === 384);
        p.doc.exitFullscreen();
        c('landscape fullscreen: exitFullscreen works', p.state.exits === 2);
        p.state.ow = 384; p.state.oh = 400;
        c('half the screen (split view): real outer size', p.win.outerHeight === 400);
    }

    // The page's own threshold.
    {
        const p = makePage({ ytcfgRatio: 0.5 });
        runContentScript(source, p);
        p.state.ow = 384; p.state.oh = 320; // 37.5% of the screen
        c('ratio 0.5 from the config: a 37% window is the PiP window', p.win.outerHeight === 853);
    }
    {
        const p = makePage({ defineYtcfg: false });
        runContentScript(source, p);
        p.state.ow = 384; p.state.oh = 320;
        c('no config yet: default ratio, a 37% window is a normal window', p.win.outerHeight === 320);
        p.setYtcfg(0.5);
        c('config arrives later: its ratio is read, not the cached default', p.win.outerHeight === 853);
    }
    {
        const p = makePage({ ytcfgRatio: null });
        runContentScript(source, p);
        p.state.ow = 384; p.state.oh = 320;
        c('config without the flag: default ratio 0.33', p.win.outerHeight === 320);
    }
    return failed;
}

suite(SOURCE, false);

// Planted bugs: each must fail the suite.
const mutants = [
    ['caches the ratio before the config exists',
        s => s.replace("return 0.33;\n            }", "ratio = 0.33; return ratio;\n            }")],
    ['no exitFullscreen override',
        s => s.replace("return inPip() ? undefined : exit.apply(this, arguments);", "return exit.apply(this, arguments);")],
    ['outer size not spoofed',
        s => s.replace("return inPip() ? screen.height : outerH.call(window);", "return outerH.call(window);")],
    ['no host gate',
        s => s.replace("location.hostname === 'm.youtube.com' && ", "")],
    ['ratio flag ignored',
        s => s.replace("flag = m && parseFloat(m[1]);", "flag = 0;")],
];
for (const [name, mutate] of mutants) {
    const mutated = mutate(SOURCE);
    if (mutated === SOURCE) {
        console.log(`FAIL mutant "${name}" did not apply`);
        failures++;
        continue;
    }
    let failed;
    try {
        failed = suite(mutated, true);
    } catch (e) {
        failed = 1;
    }
    check(`mutant "${name}" is caught`, failed > 0);
}

console.log(failures === 0 ? '\nall checks passed' : `\n${failures} check(s) failed`);
process.exit(failures === 0 ? 0 : 1);
