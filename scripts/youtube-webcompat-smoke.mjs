// youtube-webcompat-smoke.mjs: the youtube@ extension's background-play
// override (content.js, its first block) and webcompat.js (Firefox's
// performance.now precision fix and its idle keep-alive), run in a vm with
// a stub page. The content scripts and the page share one context here,
// a stand-in for the Xray boundary: what the page world defines is checked
// through the page's own reads.
//
//   node scripts/youtube-webcompat-smoke.mjs
//
// Then planted bugs must each fail it.

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.join(here, '../app/src/main/assets/youtube');
const WEBCOMPAT = readFileSync(path.join(dir, 'webcompat.js'), 'utf8');
const CONTENT = readFileSync(path.join(dir, 'content.js'), 'utf8');

// A small event model: window capture listeners, then the target's
// listeners and on<type> handler, then window bubble listeners.
function makeTarget() {
    return { listeners: [], addEventListener(type, fn, opts) {
        const capture = opts === true || !!(opts && opts.capture);
        this.listeners.push({ type, fn, capture });
    } };
}

function makePage({ host = 'm.youtube.com', pathname = '/watch', top = true, nowValues = null } = {}) {
    const state = { injected: 0, intervals: [], dispatched: [], hidden: false };
    const win = makeTarget();
    const doc = makeTarget();
    win.window = win;
    win.self = win;
    win.top = top ? win : {};
    win.location = { hostname: host, pathname, hash: '', href: `https://${host}${pathname}` };
    win.browser = { runtime: { sendMessage() {} } };
    win.setInterval = (fn, ms) => { state.intervals.push({ fn, ms }); return state.intervals.length; };
    class KeyboardEvent { constructor(type, init) { this.type = type; Object.assign(this, init); } }
    win.KeyboardEvent = KeyboardEvent;
    class Performance { now() { return nowValues ? nowValues.shift() : 1.5; } }
    win.performance = new Performance();
    Object.defineProperty(doc, 'hidden', { get: () => state.hidden, configurable: true });
    Object.defineProperty(doc, 'visibilityState', { get: () => (state.hidden ? 'hidden' : 'visible'), configurable: true });
    doc.head = null;
    doc.dispatchEvent = (e) => { state.dispatched.push(e); return true; };
    const ctx = vm.createContext(win);
    doc.documentElement = { appendChild(node) { state.injected++; vm.runInContext(node.textContent, ctx); } };
    doc.createElement = () => ({ textContent: '', remove() {} });
    win.document = doc;
    // Fires a visibilitychange the way Gecko does; returns which page
    // listeners ran.
    const fire = (type = 'visibilitychange') => {
        const ran = [];
        const ev = { type, stopped: false, stopImmediatePropagation() { this.stopped = true; } };
        const run = (list, capture) => {
            for (const l of list) {
                if (ev.stopped) return;
                if (l.type === type && l.capture === capture) l.fn(ev);
            }
        };
        run(win.listeners, true);
        if (!ev.stopped) run(doc.listeners, true);
        if (!ev.stopped) run(doc.listeners, false);
        if (!ev.stopped && typeof doc['on' + type] === 'function') doc['on' + type](ev);
        if (!ev.stopped) run(win.listeners, false);
        return ev.stopped;
    };
    return { win, doc, ctx, state, fire };
}

let failures = 0;
function check(name, ok) {
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}`);
    if (!ok) failures++;
}

function suite(webcompat, content, quiet) {
    let failed = 0;
    const c = quiet ? (n, ok) => { if (!ok) failed++; } : (n, ok) => { check(n, ok); if (!ok) failed++; };

    // webcompat.js: where it runs.
    for (const [label, opts, expect] of [
        ['m.youtube.com', {}, true],
        ['www.youtube.com', { host: 'www.youtube.com' }, true],
        ['music.youtube.com', { host: 'music.youtube.com' }, true],
        ['the token page', { host: 'www.youtube.com', pathname: '/robots.txt' }, false],
        ['an iframe', { top: false }, false],
        ['youtube-nocookie.com', { host: 'www.youtube-nocookie.com' }, false],
        ['studio.youtube.com', { host: 'studio.youtube.com' }, false],
    ]) {
        const p = makePage(opts);
        vm.runInContext(webcompat, p.ctx);
        const ran = p.state.injected > 0 || p.state.intervals.length > 0;
        c(`webcompat: ${expect ? 'runs' : 'nothing'} on ${label}`, ran === expect);
    }

    // performance.now: equal readings made unique, order kept.
    {
        const p = makePage({ nowValues: [1.5, 1.5, 10.1, 10.1, 10.1, 10.2] });
        vm.runInContext(webcompat, p.ctx);
        const v = [p.win.performance.now(), p.win.performance.now(), p.win.performance.now(), p.win.performance.now()];
        c('performance.now: equal readings are made unique', new Set(v).size === 4);
        c('performance.now: readings keep their order', v[0] < v[1] && v[1] < v[2] && v[2] < v[3]);
        c('performance.now: a new reading is returned as is', v[0] === 10.1 && v[3] === 10.2);
    }
    {
        const p = makePage({ nowValues: [3, 3, 4, 4] });
        vm.runInContext(webcompat, p.ctx);
        c('performance.now: whole-millisecond timers are left alone', p.win.performance.now() === 4 && p.win.performance.now() === 4);
    }

    // The keep-alive.
    {
        const p = makePage();
        vm.runInContext(webcompat, p.ctx);
        const t = p.state.intervals[0];
        c('keep-alive: one timer, once a minute', p.state.intervals.length === 1 && t.ms === 60000);
        t.fn();
        c('keep-alive: nothing while the page is visible', p.state.dispatched.length === 0);
        p.state.hidden = true;
        t.fn();
        const d = p.state.dispatched;
        c('keep-alive: an Alt keydown and keyup while hidden',
            d.length === 2 && d[0].type === 'keydown' && d[1].type === 'keyup'
            && d[0].keyCode === 18 && d[0].bubbles === true);
    }

    // content.js: the background-play override.
    {
        const p = makePage({ host: 'www.youtube.com' });
        vm.runInContext(content, p.ctx);
        // A page's listeners, registered every way a page can.
        const seen = [];
        p.doc.addEventListener('visibilitychange', () => seen.push('doc'));
        p.doc.addEventListener('visibilitychange', () => seen.push('doc-capture'), true);
        p.win.addEventListener('visibilitychange', () => seen.push('win'));
        p.doc.onvisibilitychange = () => seen.push('on');
        p.state.hidden = true;
        c('override: document.hidden reads false', vm.runInContext('document.hidden', p.ctx) === false);
        c('override: visibilityState reads visible', vm.runInContext('document.visibilityState', p.ctx) === 'visible');
        p.fire();
        c('override: no page listener hears visibilitychange', seen.length === 0);
        const blur = [];
        p.win.addEventListener('blur', () => blur.push(1));
        p.win.addEventListener('focus', () => blur.push(2));
        c('override: window blur listeners are dropped, others kept',
            p.win.listeners.some(l => l.type === 'focus') && !p.win.listeners.some(l => l.type === 'blur'));
    }
    return failed;
}

suite(WEBCOMPAT, CONTENT, false);

const mutants = [
    ['webcompat: no token-page gate', 'w', s => s.replace("\n        && location.pathname !== '/robots.txt'", '')],
    ['webcompat: no host gate', 'w', s => s.replace("\n        && /^(www|m|music)\\.youtube\\.com$/.test(location.hostname)", '')],
    ['keep-alive: not gated on the real visibility', 'w', s => s.replace('if (!document.hidden) {', 'if (false) {')],
    ['performance.now: not patched', 'w', s => s.replace('perf.now = function', 'perf.unused = function')],
    ['override: no capture listener', 'c', s => s.replace("window.addEventListener(type, e => e.stopImmediatePropagation(), true);", '')],
    ['override: a bubble listener instead of capture', 'c', s => s.replace("e => e.stopImmediatePropagation(), true);", "e => e.stopImmediatePropagation(), false);")],
];
for (const [name, which, mutate] of mutants) {
    const src = which === 'w' ? WEBCOMPAT : CONTENT;
    const mutated = mutate(src);
    if (mutated === src) {
        console.log(`FAIL mutant "${name}" did not apply`);
        failures++;
        continue;
    }
    let failed;
    try {
        failed = which === 'w' ? suite(mutated, CONTENT, true) : suite(WEBCOMPAT, mutated, true);
    } catch (e) {
        failed = 1;
    }
    check(`mutant "${name}" is caught`, failed > 0);
}

console.log(failures === 0 ? '\nall checks passed' : `\n${failures} check(s) failed`);
process.exit(failures === 0 ? 0 : 1);
