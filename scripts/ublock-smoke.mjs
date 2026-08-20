// Smoke test for the bundled uBlock Origin build (app/src/main/assets/ublock).
//
// Run with:  node scripts/ublock-smoke.mjs [path-to-bundle]
//
// Three suites, all driving the REAL shipped code:
//
// 1) GRAPH/BOOT — vm-loads the classic scripts background.html lists (lz4,
//    vapi.js) and imports the full ES-module background graph (start.js +
//    firedown.js) under a stubbed `browser` whose fetch serves the bundle
//    from disk — so uBO's real start sequence runs to completion: it reads
//    the real assets.json, compiles the real bundled filter lists, and
//    resolves µb.isReadyPromise. Catches broken imports/exports, a module
//    throwing at evaluation, a Firedown patch referencing a renamed symbol,
//    and a bundle whose shipped lists can't actually compile.
//
// 2) BRIDGE — drives the REAL firedown.js native-port handlers through the
//    captured "ublock" port, end to end against the booted µb:
//      - per-page ads toggle ({ads:false/true} → µb.netWhitelist, firewall
//        push, tab reload),
//      - cookie-notice toggle ({cookies:true/false} → the two cookie lists
//        enter/leave µb.selectedFilterLists with a REAL engine recompile),
//      - blocked-request counting (a real PageStore journal → per-page
//        blocked-host tally via {requestPageBlocks}, cumulativeBlocked +
//        categoryBlocked + topTrackers pushes, per-tab reset on top-level
//        navigation commit),
//    and then cross-checks the JAVA side of the contract: every key
//    GeckoRuntimeHelper sends on the port is handled by firedown.js, and
//    every key GeckoRuntimeHelper/GeckoUblockHelper read appears in the
//    messages the JS actually emitted during this run.
//
// 3) CNAME — drives the REAL vAPI.Net subclass in js/vapi-background-ext.js
//    with the REAL bundled public-suffix list through the CNAME-uncloak
//    decision table. This pins the ignore-list POLARITY: the bundle shipped
//    for a long time with `cnameIgnoreList.test(cn) === false`, which
//    INVERTED the guard — uncloaking was skipped for every CNAME *not* on
//    the ignore list, i.e. the feature was effectively disabled and cloaked
//    trackers sailed through first-party. Upstream semantics (what these
//    assertions encode): ignore-listed CNAMEs are SKIPPED, everything else
//    is uncloaked. Run against the pre-fix file, the uncloak cases fail —
//    that inversion is the bug this suite exists to keep out.
//
// Suite order matters: CNAME last, because it replaces vAPI.Net with a stub
// base class (the graph's own instance is untouched, but bridge asserts run
// against the booted graph and go first).

import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { registerHooks } from 'node:module';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const bundle = resolve(here, process.argv[2] ?? '../app/src/main/assets/ublock');

// uBO code dynamic-imports extension-absolute specifiers ('/js/…'), which the
// browser resolves against the extension origin but node resolves against the
// filesystem root. Rewrite them into the bundle.
registerHooks({
    resolve(specifier, context, nextResolve) {
        if ( specifier.startsWith('/js/') || specifier.startsWith('/lib/') ) {
            return {
                url: pathToFileURL(`${bundle}${specifier}`).href,
                shortCircuit: true,
            };
        }
        return nextResolve(specifier, context);
    },
});
const javaDir = resolve(here, '../app/src/main/java/com/solarized/firedown');

let passed = 0;
let failed = 0;
const assert = (cond, label) => {
    if ( cond ) {
        passed += 1;
        console.log(`  ok  ${label}`);
    } else {
        failed += 1;
        console.log(`FAIL  ${label}`);
    }
};

const sleep = ms => new Promise(r => setTimeout(r, ms));

// Settle helper: wait until `cond()` is true or timeout. The bridge paths
// are async chains (awaits on tabs.query, storage, list recompiles), so
// asserts poll instead of racing a fixed sleep.
async function until(cond, timeoutMs, label) {
    const deadline = Date.now() + timeoutMs;
    while ( Date.now() < deadline ) {
        if ( cond() ) { return true; }
        await sleep(50);
    }
    console.log(`  ..  timed out waiting for: ${label}`);
    return false;
}

/******************************************************************************/
/* Browser stub                                                                */
/*                                                                             */
/* Purposeful pieces (storage, tabs, fetch, native messaging) are concrete;   */
/* everything else falls through to a permissive proxy: any property path     */
/* resolves to a callable that returns a Promise of another proxy, and        */
/* `then` reads as undefined so awaiting a leaf is safe.                      */
/******************************************************************************/

const makeLeaf = () => {
    const fn = function() { return Promise.resolve(makeLeaf()); };
    return new Proxy(fn, {
        get(target, prop) {
            if ( prop === 'then' ) { return undefined; }
            if ( prop === Symbol.toPrimitive ) { return () => ''; }
            if ( prop === 'addListener' ) { return () => {}; }
            if ( prop === 'hasListener' ) { return () => false; }
            if ( prop === 'removeListener' ) { return () => {}; }
            return makeLeaf();
        },
        apply() { return Promise.resolve(makeLeaf()); },
    });
};

const manifest = JSON.parse(readFileSync(`${bundle}/manifest.json`, 'utf8'));

// --- real in-memory storage areas (uBO persists list selection + counters) --
const makeStore = () => {
    const data = new Map();
    return {
        get(keys) {
            const out = {};
            if ( keys === null || keys === undefined ) {
                for ( const [ k, v ] of data ) { out[k] = v; }
            } else if ( typeof keys === 'string' ) {
                if ( data.has(keys) ) { out[keys] = data.get(keys); }
            } else if ( Array.isArray(keys) ) {
                for ( const k of keys ) {
                    if ( data.has(k) ) { out[k] = data.get(k); }
                }
            } else if ( keys instanceof Object ) {
                for ( const [ k, dflt ] of Object.entries(keys) ) {
                    out[k] = data.has(k) ? data.get(k) : dflt;
                }
            }
            return Promise.resolve(out);
        },
        set(bin) {
            for ( const [ k, v ] of Object.entries(bin) ) { data.set(k, v); }
            return Promise.resolve();
        },
        remove(keys) {
            for ( const k of [].concat(keys) ) { data.delete(k); }
            return Promise.resolve();
        },
        clear() { data.clear(); return Promise.resolve(); },
    };
};
const storageLocal = makeStore();

// storage.session gets its own real store: a leaf-proxy fallback here poisons
// uBO's loadLocalSettings — the proxy is `instanceof Object` with a truthy
// .requestStats, so requestStats.blockedCount ends up a STRING and every
// increment concatenates ("0" + 4 = "04"), which then fails firedown.js'
// typeof-number gate and cumulativeBlocked is never pushed.
const sessionLocal = makeStore();

// --- tabs: one active regular tab the toggles operate on ---------------------
const TEST_TAB_ID = 1001;
const TEST_TAB_URL = 'https://www.example.com/page';
const testTab = {
    id: TEST_TAB_ID, url: TEST_TAB_URL, active: true,
    incognito: false, windowId: 1, status: 'complete', discarded: false,
};
const reloadedTabs = [];
const navCommittedListeners = [];
// Concrete pieces backed by the permissive proxy: any property this table
// doesn't define falls through to a callable leaf, so a module touching an
// event we didn't anticipate never crashes the harness.
const withLeafFallback = table => new Proxy(table, {
    get(t, p) {
        if ( p in t ) { return t[p]; }
        if ( p === 'then' ) { return undefined; }
        return makeLeaf();
    },
});

const tabsStub = withLeafFallback({
    query: () => Promise.resolve([ testTab ]),
    get: id => Promise.resolve(id === TEST_TAB_ID ? testTab : undefined),
    reload: id => { reloadedTabs.push(id); return Promise.resolve(); },
    update: () => Promise.resolve(testTab),
    sendMessage: () => Promise.resolve(),
});

// --- native messaging: capture the "ublock" port + every outbound message ---
const nativeOut = [];
const ublockPort = { listeners: [], posted: [] };
const runtimeStub = new Proxy(makeLeaf(), {
    get(t, p) {
        if ( p === 'getManifest' ) { return () => manifest; }
        if ( p === 'getURL' ) {
            return s => `moz-extension://smoke/${String(s ?? '').replace(/^\//, '')}`;
        }
        if ( p === 'id' ) { return 'uBlock0@raymondhill.net'; }
        if ( p === 'connectNative' ) {
            return name => {
                const port = {
                    onMessage: { addListener: fn => {
                        if ( name === 'ublock' ) { ublockPort.listeners.push(fn); }
                    } },
                    onDisconnect: { addListener: () => {} },
                    postMessage: msg => { if ( name === 'ublock' ) { ublockPort.posted.push(msg); } },
                };
                return port;
            };
        }
        if ( p === 'sendNativeMessage' ) {
            return (name, msg) => {
                if ( name === 'ublock' ) { nativeOut.push(msg); }
                return Promise.resolve();
            };
        }
        if ( p === 'then' ) { return undefined; }
        return makeLeaf();
    },
});

const browserStub = makeLeaf();
const fixedBrowser = new Proxy(browserStub, {
    get(target, prop) {
        switch ( prop ) {
        case 'runtime': return runtimeStub;
        case 'i18n': return withLeafFallback({ getMessage: k => k, getUILanguage: () => 'en-US' });
        case 'storage': return withLeafFallback({
            local: storageLocal,
            session: sessionLocal,
            onChanged: { addListener: () => {} },
        });
        case 'dns': return { resolve: () => Promise.resolve() };
        case 'tabs': return tabsStub;
        case 'webNavigation': return withLeafFallback({
            onCommitted: { addListener: fn => { navCommittedListeners.push(fn); } },
        });
        case 'then': return undefined;
        default: return browserStub[prop];
        }
    },
});

/******************************************************************************/
/* fetch: serve the bundle from disk so the boot reads the REAL assets.json   */
/* and compiles the REAL bundled lists. Remote URLs are refused fast so uBO   */
/* falls back to its bundled copies (offline-first, same as first run).       */
/******************************************************************************/

const realFetch = globalThis.fetch;
const contentTypeFor = p =>
    p.endsWith('.json') ? 'application/json' :
    p.endsWith('.wasm') ? 'application/wasm' :
    'text/plain';

globalThis.fetch = (input, init) => {
    const url = String(input);
    let path = null;
    if ( url.startsWith('moz-extension://smoke/') ) {
        path = url.slice('moz-extension://smoke/'.length);
    } else if ( /^(\.\/|\/|assets\/|js\/|lib\/)/.test(url) && url.startsWith('http') === false ) {
        path = url.replace(/^\.?\//, '');
    }
    if ( path !== null ) {
        path = path.split('?')[0];
        const full = `${bundle}/${path}`;
        if ( existsSync(full) === false ) {
            return Promise.reject(new TypeError(`smoke fetch: not in bundle: ${path}`));
        }
        const body = readFileSync(full);
        return Promise.resolve(new Response(body, {
            status: 200,
            headers: { 'Content-Type': contentTypeFor(path) },
        }));
    }
    // Remote list updates: refuse fast, uBO degrades to the bundled copy.
    return Promise.reject(new TypeError(`smoke fetch: remote refused: ${url}`));
};
void realFetch;

/******************************************************************************/
/* Suite 1: boot the background graph                                          */
/******************************************************************************/

let µb = null;
let snfe = null;

async function suiteGraph() {
    console.log('[graph] loading classic scripts + module graph');

    const sandbox = globalThis;
    sandbox.browser = fixedBrowser;
    sandbox.chrome = fixedBrowser;
    if ( sandbox.self === undefined ) { sandbox.self = sandbox; }
    if ( sandbox.window === undefined ) { sandbox.window = sandbox; }
    if ( typeof sandbox.addEventListener !== 'function' ) {
        sandbox.addEventListener = () => {};
        sandbox.removeEventListener = () => {};
        sandbox.dispatchEvent = () => true;
    }
    if ( sandbox.location === undefined ) {
        sandbox.location = new URL('moz-extension://smoke/background.html');
    }
    if ( sandbox.CSS === undefined ) {
        sandbox.CSS = {
            escape: s => String(s).replace(/[^a-zA-Z0-9_-]/g, c => `\\${c}`),
            supports: () => false,
        };
    }
    if ( sandbox.requestIdleCallback === undefined ) {
        sandbox.requestIdleCallback = fn => setTimeout(fn, 0);
        sandbox.cancelIdleCallback = id => clearTimeout(id);
    }
    // NO indexedDB stub on purpose: with indexedDB undefined, uBO's
    // cachestorage falls back to browser.storage.local (our real in-memory
    // store), so every boot promise actually settles. A half-stubbed
    // indexedDB left cache reads pending forever and µb never became ready.
    // assets.js fetches lists through XMLHttpRequest (not fetch) — serve the
    // bundle from disk with the same resolution rules as the fetch override:
    // local/extension URLs load, remote URLs error out so uBO falls back to
    // its bundled copies.
    sandbox.XMLHttpRequest = class {
        constructor() {
            this.listeners = new Map();
            this.status = 0;
            this.response = '';
            this.responseType = '';
        }
        addEventListener(type, fn) {
            if ( this.listeners.has(type) === false ) { this.listeners.set(type, []); }
            this.listeners.get(type).push(fn);
        }
        removeEventListener(type, fn) {
            const arr = this.listeners.get(type);
            if ( arr === undefined ) { return; }
            const i = arr.indexOf(fn);
            if ( i !== -1 ) { arr.splice(i, 1); }
        }
        dispatch(type, ev = {}) {
            for ( const fn of this.listeners.get(type) ?? [] ) { fn.call(this, ev); }
        }
        open(method, url) { this.url = String(url); }
        setRequestHeader() {}
        getResponseHeader() { return null; }
        abort() { queueMicrotask(() => this.dispatch('abort')); }
        send() {
            queueMicrotask(() => {
                const url = this.url ?? '';
                let path = null;
                if ( url.startsWith('moz-extension://smoke/') ) {
                    path = url.slice('moz-extension://smoke/'.length);
                } else if ( url.startsWith('http') === false ) {
                    path = url.replace(/^\.?\//, '');
                }
                if ( path === null ) {
                    // The two cookie-notice lists are default-off and NOT
                    // bundled — on-device they arrive over the network. Serve
                    // a synthetic list so the toggle's recompile provably
                    // lands rules in the engine; every other remote URL is
                    // refused so uBO falls back to bundled copies.
                    if ( /easylist-cookies|cookiemonster|annoyances-cookies/.test(url) ) {
                        const text = [
                            '! Title: smoke cookie list',
                            '||smoke-cookie-tracker.example^',
                            'example.com##.smoke-cookie-banner',
                        ].join('\n');
                        this.status = 200;
                        this.statusText = 'OK';
                        this.response = text;
                        this.responseText = text;
                        this.dispatch('progress', { loaded: text.length });
                        this.dispatch('load');
                        return;
                    }
                    this.dispatch('error');
                    return;
                }
                const full = `${bundle}/${path.split('?')[0]}`;
                if ( existsSync(full) === false ) {
                    this.dispatch('error');
                    return;
                }
                const text = readFileSync(full, 'utf8');
                this.status = 200;
                this.statusText = 'OK';
                this.response = text;
                this.responseText = text;
                this.dispatch('progress', { loaded: text.length });
                this.dispatch('load');
            });
        }
    };
    // vapi.js probes environment shape with instanceof against DOM
    // constructors; any constructor satisfies those probes.
    for ( const ctor of [
        'Element', 'HTMLDocument', 'XMLDocument', 'HTMLElement',
        'CustomEvent', 'MutationObserver',
    ] ) {
        if ( sandbox[ctor] === undefined ) {
            sandbox[ctor] = class {};
        }
    }
    if ( sandbox.document === undefined ) {
        // Just enough DOM for the classic scripts: lz4 reads
        // document.currentScript.src to locate its wasm sibling, and vapi.js
        // requires `document instanceof HTMLDocument`.
        const doc = new sandbox.HTMLDocument();
        Object.assign(doc, {
            currentScript: { src: 'moz-extension://smoke/lib/lz4/lz4-block-codec-any.js' },
            contentType: 'text/html',
            addEventListener: () => {},
            removeEventListener: () => {},
            createElement: () => ({ setAttribute: () => {}, style: {} }),
            documentElement: { setAttribute: () => {} },
            body: { setAttribute: () => {}, classList: { add: () => {}, toggle: () => {} } },
            querySelectorAll: () => [],
            querySelector: () => null,
        });
        sandbox.document = doc;
    }

    // Classic scripts, in background.html order.
    for ( const f of [ 'lib/lz4/lz4-block-codec-any.js', 'js/vapi.js' ] ) {
        const src = readFileSync(`${bundle}/${f}`, 'utf8');
        vm.runInThisContext(src, { filename: f });
    }
    assert(typeof sandbox.vAPI === 'object' && sandbox.vAPI !== null, 'vapi.js defined vAPI');

    // The module graph. start.js transitively imports every engine module the
    // stripped build ships; firedown.js is the native bridge. Either one
    // failing to LINK (missing file/export) or EVALUATE throws here.
    let graphError = null;
    try {
        await import(pathToFileURL(`${bundle}/js/start.js`).href);
        await import(pathToFileURL(`${bundle}/js/firedown.js`).href);
        ({ default: µb } = await import(pathToFileURL(`${bundle}/js/background.js`).href));
        ({ default: snfe } = await import(pathToFileURL(`${bundle}/js/static-net-filtering.js`).href));
    } catch (reason) {
        graphError = reason;
    }
    assert(graphError === null,
        `background module graph loads (start.js + firedown.js)${graphError ? ` — ${graphError.message}` : ''}`);

    // The manifest version must NOT read as a dev build: vapi-common flags
    // 'devbuild' when /^\d+\.\d+\.\d+\D/ matches the version — any non-digit
    // after the third numeric group, so a dotted 4th segment ("1.73.0.1")
    // qualifies — and a devbuild reads assets.dev.json, which the stripped
    // STABLE bundle does not ship: ZERO filter lists load. This is why the
    // local-revision convention is digits-only in the third segment
    // ("1.73.10"), and this assertion is what keeps it that way.
    assert(/^\d+\.\d+\.\d+\D/.test(manifest.version) === false,
        `manifest version "${manifest.version}" does not trip uBO's dev-build sniff`);
    assert(globalThis.vAPI.webextFlavor.soup.has('devbuild') === false,
        'runtime flavor is not devbuild (assets.json, not assets.dev.json)');

    // Real readiness: assets.json read, real lists compiled, selfie saved.
    const ready = await Promise.race([
        µb.isReadyPromise.then(() => true),
        sleep(90_000).then(() => false),
    ]);
    assert(ready, 'µb.isReadyPromise resolves (real list compile from bundled assets)');

    assert(Array.isArray(µb.selectedFilterLists) && µb.selectedFilterLists.length >= 5,
        `default filter-list selection populated (${µb.selectedFilterLists.length} lists)`);
    assert(µb.selectedFilterLists.includes('ublock-badware'),
        'security-critical default list selected (ublock-badware)');
    const filterCount = snfe.getFilterCount();
    assert(filterCount > 10_000,
        `static net filtering engine compiled real rules (${filterCount})`);
    assert(ublockPort.listeners.length === 1, 'firedown.js opened the "ublock" native port');
}

/******************************************************************************/
/* Suite 2: the Firedown native bridge, end to end                             */
/******************************************************************************/

const portSend = msg => {
    for ( const fn of ublockPort.listeners ) { fn(msg); }
};

const lastMessageWith = key => {
    for ( let i = nativeOut.length - 1; i >= 0; i-- ) {
        if ( Object.hasOwn(nativeOut[i], key) ) { return nativeOut[i]; }
    }
    return undefined;
};

async function suiteBridge() {
    console.log('[bridge] per-page toggle');

    // --- per-page ads toggle -------------------------------------------------
    assert(µb.getNetFilteringSwitch(TEST_TAB_URL) === true,
        'net filtering ON for the page by default');

    let mark = nativeOut.length;
    let reloads = reloadedTabs.length;
    portSend({ ads: false });
    await until(() => µb.getNetFilteringSwitch(TEST_TAB_URL) === false, 5000, 'ads off');
    assert(µb.getNetFilteringSwitch(TEST_TAB_URL) === false,
        '{ads:false} whitelists the page (net filtering OFF)');
    await until(() => nativeOut.length > mark && lastMessageWith('firewall') !== undefined, 5000, 'firewall push');
    const fw1 = lastMessageWith('firewall');
    assert(fw1 !== undefined && fw1.firewall.activated === false,
        'firewall state pushed back to Java: activated=false');
    await until(() => reloadedTabs.length > reloads, 5000, 'tab reload');
    assert(reloadedTabs.length > reloads, 'toggle reloads the affected tab');

    portSend({ ads: true });
    await until(() => µb.getNetFilteringSwitch(TEST_TAB_URL) === true, 5000, 'ads on');
    assert(µb.getNetFilteringSwitch(TEST_TAB_URL) === true,
        '{ads:true} re-enables net filtering for the page');
    const fw2 = lastMessageWith('firewall');
    assert(fw2 !== undefined && fw2.firewall.activated === true,
        'firewall state pushed back to Java: activated=true');

    // --- cookie-notice toggle ------------------------------------------------
    console.log('[bridge] cookie-notice toggle (real recompiles — takes a few seconds)');
    const COOKIE_LISTS = [ 'fanboy-cookiemonster', 'ublock-cookies-adguard' ];
    assert(COOKIE_LISTS.every(k => µb.selectedFilterLists.includes(k) === false),
        'cookie-notice lists are OFF by default');
    const baseCount = snfe.getFilterCount();

    portSend({ cookies: true });
    const enabled = await until(
        () => COOKIE_LISTS.every(k => µb.selectedFilterLists.includes(k)),
        60_000, 'cookie lists selected + recompiled');
    assert(enabled, '{cookies:true} selects BOTH cookie-notice lists');
    const fwC = lastMessageWith('firewall');
    assert(fwC !== undefined && fwC.firewall.cookies === true,
        'optimistic firewall push carries cookies=true');
    // The recompile really folded the cookie lists into the engine.
    await until(() => snfe.getFilterCount() > baseCount, 60_000, 'rule count grew');
    const grown = snfe.getFilterCount();
    assert(grown > baseCount,
        `cookie lists compiled into the engine (${baseCount} -> ${grown} rules)`);

    portSend({ cookies: false });
    const disabled = await until(
        () => COOKIE_LISTS.every(k => µb.selectedFilterLists.includes(k) === false),
        60_000, 'cookie lists deselected + recompiled');
    assert(disabled, '{cookies:false} removes both cookie-notice lists');
    // updateState() reports "on" as SOME selected — after removal it must
    // report false again on the next explicit update.
    const markU = nativeOut.length;
    portSend({ update: true });
    await until(() => nativeOut.length > markU && lastMessageWith('firewall') !== undefined, 5000, 'update push');
    const fwU = lastMessageWith('firewall');
    assert(fwU !== undefined && fwU.firewall.cookies === false,
        '{update} reads back cookies=false after disable');

    // --- blocked-request counting -------------------------------------------
    console.log('[bridge] blocked-request counting (real PageStore journal)');
    const { PageStore } = await import(pathToFileURL(`${bundle}/js/pagestore.js`).href);

    // A real PageStore for the test tab. mustLookup() synthesizes a tab
    // context for a tab the manager hasn't seen — good enough here.
    const ps = PageStore.factory(TEST_TAB_ID, { tabId: TEST_TAB_ID });
    const FC = µb.FilteringContext;
    const blockedBefore = µb.requestStats.blockedCount;

    // Journal triples: (hostname, blocked(1)/allowed(0), itype).
    ps.journal.push(
        'tracker.evil.com', 1, FC.SCRIPT,
        'tracker.evil.com', 1, FC.SCRIPT,
        'pixel.evil.com',   1, FC.PING,
        'iframe.evil.com',  1, FC.SUB_FRAME,
        'cdn.example.com',  0, FC.SCRIPT,   // allowed — must NOT be counted
    );
    ps.journalProcess();    // the firedown.js-hooked version

    assert(µb.requestStats.blockedCount === blockedBefore + 4,
        'uBlock cumulative blockedCount advanced by the journal (4 blocked)');

    // Per-page tally, the SecuritySheet drill-down round trip.
    let markP = nativeOut.length;
    portSend({ requestPageBlocks: true, tabId: TEST_TAB_ID });
    await until(() => nativeOut.length > markP && lastMessageWith('pageBlocks') !== undefined, 5000, 'pageBlocks push');
    const pb = lastMessageWith('pageBlocks');
    assert(pb !== undefined && pb.pageBlocks.tabId === TEST_TAB_ID,
        'pageBlocks reply echoes the requested tabId');
    assert(pb !== undefined && pb.pageBlocks.isIncognito === false,
        'pageBlocks reply carries isIncognito');
    const items = pb ? pb.pageBlocks.items : [];
    const byHost = Object.fromEntries(items.map(x => [ x.host, x.count ]));
    assert(byHost['tracker.evil.com'] === 2 && byHost['pixel.evil.com'] === 1 && byHost['iframe.evil.com'] === 1,
        'per-page blocked-host counts are correct');
    assert(byHost['cdn.example.com'] === undefined,
        'allowed requests are not counted');
    assert(items.length > 1 && items[0].host === 'tracker.evil.com',
        'items sorted by count descending');

    // Category + cumulative + top-trackers pushes ride the badge-update
    // debounce (250 ms).
    const markC = nativeOut.length;
    µb.updateToolbarIcon(TEST_TAB_ID, 0b111);
    await until(() => nativeOut.length > markC && lastMessageWith('categoryBlocked') !== undefined, 5000, 'category push');
    const cat = lastMessageWith('categoryBlocked');
    assert(cat !== undefined
        && cat.categoryBlocked.scripts === 2
        && cat.categoryBlocked.pixels === 1
        && cat.categoryBlocked.frames === 1
        && cat.categoryBlocked.other === 0,
        'categoryBlocked buckets: scripts=2 pixels=1 frames=1 other=0');
    const cum = lastMessageWith('cumulativeBlocked');
    assert(cum !== undefined && cum.cumulativeBlocked >= blockedBefore + 4,
        'cumulativeBlocked pushed for the Home trackers card');
    const top = lastMessageWith('topTrackers');
    assert(top !== undefined
        && top.topTrackers.some(x => x.host === 'tracker.evil.com' && x.count === 2),
        'topTrackers list carries the blocked hostname tally');

    // Per-tab reset on a real top-level navigation commit.
    for ( const fn of navCommittedListeners ) {
        fn({ tabId: TEST_TAB_ID, frameId: 0, url: 'https://www.example.com/next' });
    }
    markP = nativeOut.length;
    portSend({ requestPageBlocks: true, tabId: TEST_TAB_ID });
    await until(() => nativeOut.length > markP, 5000, 'pageBlocks after nav');
    const pb2 = lastMessageWith('pageBlocks');
    assert(pb2 !== undefined && pb2.pageBlocks.items.length === 0,
        'per-page tally resets on top-level navigation commit');

    // --- the Java half of the contract --------------------------------------
    console.log('[bridge] Java wrapper contract cross-check');
    const runtimeHelper = readFileSync(`${javaDir}/geckoview/GeckoRuntimeHelper.java`, 'utf8');
    const ublockHelper = readFileSync(`${javaDir}/geckoview/GeckoUblockHelper.java`, 'utf8');
    const firedownJs = readFileSync(`${bundle}/js/firedown.js`, 'utf8');

    // Keys Java SENDS on the port (msg.put("key", …) preceding
    // sendPortMessage("ublock", …)) must each have a firedown.js handler.
    // Only keys put into messages that go out on the "ublock" port — the
    // helper also talks to other extensions' ports on other names.
    const sentKeys = new Set();
    for ( const block of runtimeHelper.split('sendPortMessage("ublock"').slice(0, -1) ) {
        const tail = block.slice(-400);
        for ( const m of tail.matchAll(/msg\.put\("([a-zA-Z]+)"/g) ) {
            sentKeys.add(m[1]);
        }
    }
    const handledKeys = new Set();
    for ( const m of firedownJs.matchAll(/Object\.hasOwn\(response, "([a-zA-Z]+)"\)/g) ) {
        handledKeys.add(m[1]);
    }
    handledKeys.add('tabId');   // rider on requestPageBlocks, read directly
    const unhandled = [ ...sentKeys ].filter(k => handledKeys.has(k) === false);
    assert(unhandled.length === 0,
        `every key Java sends is handled by firedown.js${unhandled.length ? ` (unhandled: ${unhandled})` : ''}`);

    // Keys Java READS from ublock messages (json.has("key") in
    // handleUblockMessage + nested opt* reads) must all have been observed in
    // the messages the JS actually emitted during this run.
    const emittedTop = new Set();
    const emittedNested = new Set();
    for ( const msg of nativeOut ) {
        for ( const [ k, v ] of Object.entries(msg) ) {
            emittedTop.add(k);
            if ( v instanceof Object && Array.isArray(v) === false ) {
                for ( const kk of Object.keys(v) ) { emittedNested.add(`${k}.${kk}`); }
            }
        }
    }
    for ( const key of [ 'firewall', 'cumulativeBlocked', 'categoryBlocked', 'topTrackers', 'pageBlocks' ] ) {
        assert(runtimeHelper.includes(`json.has("${key}")`),
            `Java reads top-level key "${key}"`);
        assert(emittedTop.has(key),
            `JS emitted top-level key "${key}" during this run`);
    }
    for ( const key of [ 'firewall.activated', 'firewall.cookies', 'pageBlocks.tabId', 'pageBlocks.isIncognito', 'pageBlocks.items' ] ) {
        assert(emittedNested.has(key), `JS emitted nested key "${key}"`);
    }
    // Bucket names must agree between the JS emitter and the Java reader.
    for ( const bucket of [ 'scripts', 'pixels', 'frames', 'other' ] ) {
        assert(runtimeHelper.includes(`"${bucket}"`) || ublockHelper.includes(`"${bucket}"`),
            `Java reads categoryBlocked bucket "${bucket}"`);
    }
    // Row shape of topTrackers/pageBlocks items (host/count) as read by
    // GeckoUblockHelper.
    assert(ublockHelper.includes('optString("host"') && ublockHelper.includes('optLong("count"'),
        'Java reads {host, count} rows — matches the JS item shape');
}

/******************************************************************************/
/* Suite 3: CNAME uncloak decision table (real vapi-background-ext.js)        */
/******************************************************************************/

async function suiteCname() {
    console.log('[cname] driving vAPI.Net.cnameFromRecord / onAfterDNSResolution');

    let superCalls = 0;
    globalThis.vAPI.Net = class {
        constructor() {}
        setOptions() {}
        onBeforeSuspendableRequest(details) { superCalls += 1; return details; }
        regexFromStrList(list) {
            if ( typeof list !== 'string' || list === '' ) { return null; }
            return new RegExp(
                '(?:^|\\.)(?:' +
                list.trim().split(/\s+/).map(
                    s => s.replace(/\./g, '\\.').replace(/\*/g, '.*')
                ).join('|') +
                ')$'
            );
        }
    };

    // domainFromHostname (the 1st-party guard) goes through the real public
    // suffix list, which is empty until parsed — and an empty PSL maps EVERY
    // hostname to '', making all pairs look same-domain and vacuously skipping
    // every uncloak. The booted graph normally parses it; parse explicitly so
    // this suite also works standalone.
    const { default: publicSuffixList } =
        await import(pathToFileURL(`${bundle}/lib/publicsuffixlist/publicsuffixlist.js`).href);
    const { default: punycode } =
        await import(pathToFileURL(`${bundle}/lib/punycode.js`).href);
    publicSuffixList.parse(
        readFileSync(`${bundle}/assets/thirdparties/publicsuffix.org/list/effective_tld_names.dat`, 'utf8'),
        punycode.toASCII
    );

    // Cache-bust so this import re-evaluates against OUR stub base class even
    // though the graph suite already pulled the file in.
    const url = pathToFileURL(`${bundle}/js/vapi-background-ext.js`).href +
        `?cname-suite=${Date.now()}`;
    await import(url);

    const net = new globalThis.vAPI.Net();
    net.cnameIgnoreList = net.regexFromStrList('safecname.example');

    const details = (url, doc) => ({ url, documentUrl: doc });

    // 1. Plain cloaked tracker: third-party CNAME, not ignore-listed,
    //    subresource → MUST uncloak.
    const cn1 = net.cnameFromRecord(
        'metrics.example.com',
        { canonicalName: 'tracker.evil.com' },
        details('https://metrics.example.com/pixel.js', 'https://www.example.com/')
    );
    assert(cn1 === 'tracker.evil.com', 'third-party cloaked CNAME is uncloaked');

    // 2. Ignore-listed CNAME → MUST be skipped. (Pre-fix `=== false` code
    //    returned the cname here — inverted.)
    const cn2 = net.cnameFromRecord(
        'metrics.example.com',
        { canonicalName: 'cdn.safecname.example' },
        details('https://metrics.example.com/pixel.js', 'https://www.example.com/')
    );
    assert(cn2 === undefined, 'ignore-listed CNAME is skipped');

    // 3. Same case as 1 but phrased as the inversion's failure: the pre-fix
    //    code skipped every NON-ignore-listed cname, so cn1 came back
    //    undefined. Keep an explicit polarity probe.
    assert(cn1 !== undefined, 'ignore-list guard polarity (pre-fix code fails here)');

    // 4. First-party CNAME (same registrable domain) → skipped.
    const cn4 = net.cnameFromRecord(
        'metrics.example.com',
        { canonicalName: 'origin.example.com' },
        details('https://metrics.example.com/pixel.js', 'https://www.example.com/')
    );
    assert(cn4 === undefined, 'first-party CNAME is skipped');

    // 5. Root document's own hostname → skipped.
    const cn5 = net.cnameFromRecord(
        'www.example.com',
        { canonicalName: 'tracker.evil.com' },
        details('https://www.example.com/', undefined)
    );
    assert(cn5 === undefined, 'root-document CNAME is skipped');

    // 6. Full pipeline: a resolved dns entry with a cname rewrites the URL and
    //    replays through the base class.
    const d6 = details('https://metrics.example.com/pixel.js', 'https://www.example.com/');
    net.dnsToCache('metrics.example.com', {
        canonicalName: 'tracker.evil.com',
        addresses: [ '203.0.113.7' ],
    }, d6);
    net.onAfterDNSResolution('metrics.example.com', d6);
    assert(d6.url === 'https://tracker.evil.com/', 'uncloaked request URL rewritten to canonical host');
    assert(d6.aliasURL === 'https://metrics.example.com/pixel.js', 'original URL kept as aliasURL');
    assert(superCalls === 1, 'uncloaked request replayed through base onBeforeSuspendableRequest');

    // 7. No cname in the record → nothing rewritten, no replay.
    const d7 = details('https://plain.example.com/app.js', 'https://www.example.com/');
    net.dnsToCache('plain.example.com', { canonicalName: 'plain.example.com' }, d7);
    net.onAfterDNSResolution('plain.example.com', d7);
    assert(d7.aliasURL === undefined, 'self-canonical record is left alone');
}

/******************************************************************************/

try {
    await suiteGraph();
    if ( µb !== null && failed === 0 ) {
        await suiteBridge();
    } else if ( failed !== 0 ) {
        console.log('[bridge] SKIPPED — graph suite failed');
        failed += 1;
    }
    await suiteCname();
} catch (reason) {
    failed += 1;
    console.log(`FAIL  suite threw: ${reason?.stack ?? reason}`);
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
