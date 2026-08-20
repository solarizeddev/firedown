// Smoke test for the bundled uBlock Origin build (app/src/main/assets/ublock).
//
// Run with:  node scripts/ublock-smoke.mjs [path-to-bundle]
//
// Two suites:
//
// 1) GRAPH — vm-loads the classic scripts background.html lists (lz4, vapi.js)
//    and then imports the full ES-module background graph (start.js, which
//    pulls in every engine module, and firedown.js, the native bridge) under a
//    permissive stubbed `browser`. Catches what the Threads/webrequests smoke
//    catches for the other extension: broken imports/exports, a module that
//    throws at evaluation time, a Firedown patch referencing a symbol upstream
//    renamed, syntax errors (ES modules need module parsing — plain --check
//    rejects `import`).
//
// 2) CNAME — drives the REAL vAPI.Net subclass in js/vapi-background-ext.js
//    (dynamic import of the shipped file, stub base class) through the
//    CNAME-uncloak decision table. This pins the ignore-list POLARITY: the
//    bundle shipped for a long time with `cnameIgnoreList.test(cn) === false`,
//    which INVERTED the guard — uncloaking was skipped for every CNAME *not*
//    on the ignore list, i.e. the feature was effectively disabled and
//    cloaked trackers (metrics.example.com CNAME-aliasing tracker.evil.com)
//    sailed through first-party. Upstream semantics (what these assertions
//    encode): ignore-listed CNAMEs are SKIPPED, everything else is uncloaked.
//    Run against the pre-fix file, cases 2 and 3 fail — that inversion is the
//    bug this suite exists to keep out.

import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const bundle = resolve(here, process.argv[2] ?? '../app/src/main/assets/ublock');

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

/******************************************************************************/
/* Permissive `browser` stub: any property path resolves to a callable proxy; */
/* calling one returns a real Promise of another (await-safe: `then` reads    */
/* as undefined on the value itself). Listener registrations are recorded.    */
/******************************************************************************/

const listenerCount = { n: 0 };

const makeLeaf = () => {
    const fn = function() { return Promise.resolve(makeLeaf()); };
    return new Proxy(fn, {
        get(target, prop) {
            if ( prop === 'then' ) { return undefined; }
            if ( prop === Symbol.toPrimitive ) { return () => ''; }
            if ( prop === 'addListener' ) {
                return () => { listenerCount.n += 1; };
            }
            if ( prop === 'hasListener' ) { return () => false; }
            if ( prop === 'removeListener' ) { return () => {}; }
            return makeLeaf();
        },
        apply() { return Promise.resolve(makeLeaf()); },
    });
};

const manifest = JSON.parse(readFileSync(`${bundle}/manifest.json`, 'utf8'));

const browserStub = makeLeaf();
const fixedBrowser = new Proxy(browserStub, {
    get(target, prop) {
        switch ( prop ) {
        case 'runtime':
            return new Proxy(makeLeaf(), {
                get(t, p) {
                    if ( p === 'getManifest' ) { return () => manifest; }
                    if ( p === 'getURL' ) { return s => `moz-extension://smoke/${String(s).replace(/^\//, '')}`; }
                    if ( p === 'id' ) { return 'uBlock0@raymondhill.net'; }
                    if ( p === 'connectNative' ) {
                        return () => ({
                            onMessage: { addListener: () => { listenerCount.n += 1; } },
                            onDisconnect: { addListener: () => {} },
                            postMessage: () => {},
                        });
                    }
                    if ( p === 'sendNativeMessage' ) { return () => Promise.resolve(); }
                    if ( p === 'then' ) { return undefined; }
                    return makeLeaf()[p] ?? makeLeaf();
                },
            });
        case 'i18n':
            return {
                getMessage: k => k,
                getUILanguage: () => 'en-US',
            };
        case 'storage':
            return {
                local: {
                    get: () => Promise.resolve({}),
                    set: () => Promise.resolve(),
                    remove: () => Promise.resolve(),
                    clear: () => Promise.resolve(),
                },
                onChanged: { addListener: () => {} },
            };
        case 'dns':
            return { resolve: () => Promise.resolve() };
        case 'then':
            return undefined;
        default:
            return browserStub[prop];
        }
    },
});

/******************************************************************************/
/* Suite 1: load the background graph                                          */
/******************************************************************************/

async function suiteGraph() {
    console.log('[graph] loading classic scripts + module graph');

    const sandbox = globalThis;
    sandbox.browser = fixedBrowser;
    sandbox.chrome = fixedBrowser;
    if ( sandbox.self === undefined ) { sandbox.self = sandbox; }
    if ( sandbox.window === undefined ) { sandbox.window = sandbox; }
    // vapi.js consults navigator/userAgent; node has a navigator global but
    // no `location`.
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
    if ( sandbox.indexedDB === undefined ) {
        sandbox.indexedDB = { open: () => ({}), deleteDatabase: () => ({}) };
    }
    if ( sandbox.XMLHttpRequest === undefined ) {
        sandbox.XMLHttpRequest = class { open() {} send() {} addEventListener() {} };
    }
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
    } catch (reason) {
        graphError = reason;
    }
    assert(graphError === null,
        `background module graph loads (start.js + firedown.js)${graphError ? ` — ${graphError.message}` : ''}`);
    assert(listenerCount.n > 0, 'graph registered at least one listener');
}

/******************************************************************************/
/* Suite 2: CNAME uncloak decision table (real vapi-background-ext.js)        */
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
    // every uncloak. Feed it the bundled PSL snapshot so the guard runs for
    // real.
    const { default: publicSuffixList } =
        await import(pathToFileURL(`${bundle}/lib/publicsuffixlist/publicsuffixlist.js`).href);
    const { default: punycode } =
        await import(pathToFileURL(`${bundle}/lib/punycode.js`).href);
    publicSuffixList.parse(
        readFileSync(`${bundle}/assets/thirdparties/publicsuffix.org/list/effective_tld_names.dat`, 'utf8'),
        punycode.toASCII
    );

    // Cache-bust so this import re-evaluates against OUR stub base class even
    // if the graph suite already pulled the file in.
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
    await suiteCname();
} catch (reason) {
    failed += 1;
    console.log(`FAIL  suite threw: ${reason?.stack ?? reason}`);
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
