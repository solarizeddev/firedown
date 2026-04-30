// =============================================================================
// YouTube Solver Extension — Background Shell
// Bundled in APK via ensureBuiltIn. Rarely needs updating.
// The solver logic (preprocessPlayer, string table parsing, probe generation)
// is fetched remotely from firedown.app and cached in browser.storage.local.
// =============================================================================

const SOLVER_URL = "https://raw.githubusercontent.com/firedown-app/firedown-solver/refs/heads/main/solver.js";
const SOLVER_CACHE_KEY = "yt-solver-module";
const SOLVER_CHECK_INTERVAL = 6 * 60 * 60 * 1000; // 6 hours
const PLAYER_CACHE_KEY = "yt-player-cache";
const CIPHER_OPS_CACHE_KEY = "yt-cipher-ops-cache";

// Set to true to bypass all caches and always fetch fresh solver + re-solve player
const TEST_MODE = false;



let solverModule = null;

async function loadSolver() {
    if (solverModule && !TEST_MODE) return solverModule;

    if (TEST_MODE) {
        try { await browser.storage.local.remove([SOLVER_CACHE_KEY, PLAYER_CACHE_KEY, CIPHER_OPS_CACHE_KEY]); } catch (e) {}
    }

    let cached = null;
    if (!TEST_MODE) {
        try {
            const stored = await browser.storage.local.get(SOLVER_CACHE_KEY);
            cached = stored[SOLVER_CACHE_KEY] || null;
        } catch (e) {}
    }

    const isStale = TEST_MODE || !cached || (Date.now() - (cached.fetchedAt || 0)) > SOLVER_CHECK_INTERVAL;
    if (isStale) {
        try {
            const resp = await fetch(SOLVER_URL, { cache: "no-cache" });
            if (resp.ok) {
                const code = await resp.text();
                if (code.includes("SOLVER_VERSION") && code.includes("preprocessPlayer")) {
                    const versionMatch = code.match(/SOLVER_VERSION\s*=\s*(\d+)/);
                    const remoteVersion = versionMatch ? parseInt(versionMatch[1]) : 0;
                    const cachedVersion = cached?.version || 0;
                    if (remoteVersion >= cachedVersion || TEST_MODE) {
                        cached = { code, version: remoteVersion, fetchedAt: Date.now() };
                        await browser.storage.local.set({ [SOLVER_CACHE_KEY]: cached });
                        console.log(`[Solver] ${TEST_MODE ? 'TEST: fetched' : 'Updated'} remote solver v${remoteVersion}`);
                    }
                }
            }
        } catch (e) {
            console.log("[Solver] Fetch failed, using cached/bundled:", e.message);
        }
    }

    if (cached?.code) {
        try {
            solverModule = loadSolverCode(cached.code);
            console.log(`[Solver] Loaded solver v${cached.version}`);
            return solverModule;
        } catch (e) {
            console.warn("[Solver] Cached solver failed:", e.message);
        }
    }

    console.log("[Solver] Using bundled fallback");
    solverModule = loadSolverCode(BUNDLED_SOLVER);
    return solverModule;
}

function loadSolverCode(code) {
    const exports = {};
    new Function("exports", code + "\nexports.preprocessPlayer=preprocessPlayer;exports.preprocessCipher=typeof preprocessCipher!=='undefined'?preprocessCipher:null;exports.SOLVER_VERSION=typeof SOLVER_VERSION!=='undefined'?SOLVER_VERSION:0;")(exports);
    return exports;
}

// Bundled solver fallback — embedded copy of solver.js
// Updated with APK releases. Used when remote fetch fails and no cache exists.

const BUNDLED_SOLVER = "// =============================================================================\n// YouTube N-Parameter Solver — Remote Module v12\n// Hosted at: https://github.com/solarizeddev/firedown-solver\n//\n// Design principle: minimize structural assumptions about the player source.\n// YouTube rotates base.js frequently — every assumption we make is a future\n// breakage waiting to happen. This solver relies on RUNTIME behavior (calling\n// candidate functions and checking outputs) rather than source-code pattern\n// matching wherever possible.\n//\n// v9:  Runtime-based candidate detection via func.toString()\n// v10: Bit-reversal r-scan (no maxR), r-outer loop, cipher !_newCh filter\n// v11: Runtime candidate enumeration, brace-walk IIFE detection, dual-quote\n//      'use strict', arithmetic-agnostic base scan.\n// v12: N-param architecture changed in player 1bb6ee63 (Apr 2026) — the\n//      standalone `f(r, p, x)` n-transform is gone. The transform now lives\n//      inside the URL parser class (historically `g.t_`). `TP(40, 1409, x)`\n//      and similar multi-dispatch helpers pass the old XOR probe's shape\n//      checks but return `\"undefined\" + x` when called with only 3 args,\n//      producing garbage transforms that make videoplayback reject the URL.\n//\n//      Three changes:\n//\n//      1. PRIMARY STRATEGY FOR N-PARAM — URL-class discovery. After the\n//         player IIFE finishes initializing, walk runtime objects\n//         (_yt_player, globalThis, nested namespaces up to depth 2) looking\n//         for a 2-arg constructor whose instance has a `.get(\"n\")` method\n//         that deterministically transforms our test input into a valid-\n//         looking n-value. No source pattern matching — pure behavioral\n//         detection. Works regardless of what YouTube names the class\n//         (g.t_, g.xY, _yt_player.Zz, whatever).\n//\n//      2. CALL-SITE EXTRACTION FOR XOR DISPATCHERS — the cipher function on\n//         this player is `kp(1, 7337, s)` (equivalently kp(10, 7330, s) —\n//         same T = V^Y). Previous probes tried to derive Y from XOR constants\n//         in the function body, but with Y never appearing inside kp's body\n//         (only T does) that derivation is impossible. Instead, we scan the\n//         player source once for `NAME(INT, INT, ...)` literal call sites\n//         and test each pair. 21 distinct names × a handful of pairs each,\n//         ~25ms scan. Replaces the 256×N-candidate bit-reversal for the\n//         common case.\n//\n//      3. XOR-PROBE VALIDATION HARDENING — both strategies above share new\n//         validators that would have rejected the `TP` false positive:\n//           a) input must not appear as a substring of output\n//           b) output must not start with \"undefined\", \"null\", \"NaN\",\n//              \"[object\", or other stringified-junk markers\n//           c) cipher test inputs are now fully distinct (v11 shared the\n//              middle and a cipher that scrambled the middle identically\n//              produced _v1 === _v3, failing the validator)\n//         Bit-reversal bit-scan remains as a fallback for older players\n//         where call sites aren't literal.\n// =============================================================================\nvar SOLVER_VERSION = 12;\n\nvar SETUP_CODE = [\n    'if(typeof globalThis.XMLHttpRequest===\"undefined\"){globalThis.XMLHttpRequest={prototype:{}};}',\n    'globalThis.location={hash:\"\",host:\"www.youtube.com\",hostname:\"www.youtube.com\",',\n    'href:\"https://www.youtube.com/watch?v=yt-dlp-wins\",origin:\"https://www.youtube.com\",password:\"\",',\n    'pathname:\"/watch\",port:\"\",protocol:\"https:\",search:\"?v=yt-dlp-wins\",username:\"\",',\n    'assign:function(){},replace:function(){},reload:function(){},toString:function(){return this.href;}};',\n    'var window=globalThis;',\n    'if(typeof globalThis.document===\"undefined\"){globalThis.document=Object.create(null);}',\n    'if(typeof globalThis.navigator===\"undefined\"){globalThis.navigator={userAgent:\"\"};}',\n    'if(typeof globalThis.self===\"undefined\"){globalThis.self=globalThis;}',\n    'if(typeof globalThis.addEventListener===\"undefined\"){globalThis.addEventListener=function(){};}',\n    'if(typeof globalThis.removeEventListener===\"undefined\"){globalThis.removeEventListener=function(){};}',\n    'if(typeof globalThis.setTimeout===\"undefined\"){globalThis.setTimeout=function(f){try{f();}catch(e){}};}',\n    'if(typeof globalThis.clearTimeout===\"undefined\"){globalThis.clearTimeout=function(){};}',\n    'if(typeof globalThis.setInterval===\"undefined\"){globalThis.setInterval=function(){return 0;};}',\n    'if(typeof globalThis.clearInterval===\"undefined\"){globalThis.clearInterval=function(){};}',\n    'if(typeof globalThis.requestAnimationFrame===\"undefined\"){globalThis.requestAnimationFrame=function(){};}',\n    'if(typeof globalThis.getComputedStyle===\"undefined\"){globalThis.getComputedStyle=function(){return{opacity:\"1\"};};}',\n].join('\\n');\n\n/**\n * Find 'use strict' in either quote style.\n */\nfunction findUseStrict(data) {\n    var single = data.indexOf(\"'use strict';\");\n    var dbl = data.indexOf('\"use strict\";');\n    if (single === -1) return dbl;\n    if (dbl === -1) return single;\n    return Math.min(single, dbl);\n}\n\nfunction useStrictLen(data, idx) {\n    if (idx < 0) return 0;\n    if (data.substring(idx, idx + 13) === \"'use strict';\") return 13;\n    if (data.substring(idx, idx + 13) === '\"use strict\";') return 13;\n    return 0;\n}\n\n/**\n * Find the string table variable and the index of \"split\" in it.\n * Used only by the XOR fallback probe — the URL-class path doesn't need it.\n */\nfunction findStringTable(data) {\n    var chunk = data.substring(0, 5000);\n    var splitCalls = chunk.matchAll(/\\.split\\((['\"])(.)(\\1)\\)/g);\n    for (var sc of splitCalls) {\n        var delimiter = sc[2], splitPos = sc.index;\n        var before = data.substring(0, splitPos);\n        var lastVar = null;\n        for (var vm of before.matchAll(/var\\s+(\\w+)=(['\"])/g)) lastVar = vm;\n        if (!lastVar) continue;\n        var content = data.substring(lastVar.index + lastVar[0].length, splitPos);\n        var quote = lastVar[2];\n        if (content.endsWith(quote)) content = content.slice(0, -1);\n        content = content.replace(new RegExp('\\\\\\\\' + quote.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&'), 'g'), quote);\n        var entries = content.split(delimiter);\n        var si = entries.indexOf('split');\n        if (si >= 0 && entries.length > 10) return { tableVar: lastVar[1], splitIdx: si };\n    }\n    var arrRx = /var\\s+(\\w+)=\\[/g, am;\n    while ((am = arrRx.exec(chunk)) !== null) {\n        var start = am.index + am[0].length - 1;\n        var arrChunk = data.substring(start, start + 2000);\n        var d = 0, p = 0;\n        while (p < arrChunk.length) { if (arrChunk[p] === '[') d++; else if (arrChunk[p] === ']') { d--; if (d === 0) break; } p++; }\n        var entries = [], sm, strRx = /\"((?:[^\"\\\\]|\\\\.)*)\"/g;\n        while ((sm = strRx.exec(arrChunk.substring(0, p))) !== null) entries.push(sm[1]);\n        if (entries.length > 10) { var si = entries.indexOf('split'); if (si >= 0) return { tableVar: am[1], splitIdx: si }; }\n    }\n    return null;\n}\n\n/**\n * Find all literal (INT, INT, ...) call sites for every short identifier in\n * the source. Returns `{ name -> [[V, Y], ...] }`.\n *\n * Single-pass optimization: one regex scans the entire source, matching any\n * `NAME(INT, INT, ...)` pattern where NAME is ≤8 chars. This runs in ~25ms\n * on a 2.7MB base.js. Alternative per-name scanning (4891 names) took 11s.\n *\n * Rationale: on player 1bb6ee63, the cipher helper kp is invoked as\n * `kp(1, 7337, s)`, `kp(4, 7340, s)`, `kp(10, 7330, s)` — literal pairs\n * embedded at the call site. The earlier probe tried to derive Y from XOR\n * constants in the function body (`Y ^ V ^ NNNN = target_m_index`), but with\n * `T = Y ^ V` as the actual dispatch key and `Y` itself never appearing in\n * the body, that derivation is impossible.\n *\n * Call-site extraction gives us the exact literal pairs YouTube's code uses,\n * so we only need to test a handful of (V, Y) per candidate instead of\n * brute-forcing 256 × N bases.\n *\n * The `names` argument is ignored beyond the short-identifier filter — we\n * return sites for any identifier found, and the probe's `_resolveFn` handles\n * name-to-function lookup at runtime.\n */\nfunction findCallSites(data, names) {\n    var rx = /\\b([a-zA-Z_$][\\w$]{0,7})\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,/g;\n    var byName = Object.create(null);\n    var m;\n    while ((m = rx.exec(data)) !== null) {\n        var name = m[1];\n        var V = parseInt(m[2]);\n        var Y = parseInt(m[3]);\n        if (!byName[name]) byName[name] = new Set();\n        byName[name].add(V + ',' + Y);\n    }\n    // Materialize to arrays of [V, Y] tuples.\n    var out = {};\n    var keys = Object.keys(byName);\n    for (var i = 0; i < keys.length; i++) {\n        var k = keys[i];\n        var arr = Array.from(byName[k]).map(function(s) {\n            var p = s.split(',');\n            return [parseInt(p[0]), parseInt(p[1])];\n        });\n        if (arr.length > 0) out[k] = arr;\n    }\n    return out;\n}\n\n/**\n * Find the largest var chain in the IIFE scope — static helper for fallback.\n */\nfunction findVarChain(data) {\n    var varIdx = 0;\n    var biggest = { start: -1, count: 0, end: 0 };\n    while (true) {\n        var nextVar = data.indexOf('var ', varIdx);\n        if (nextVar === -1 || nextVar > 25000) break;\n        var pos = nextVar + 4;\n        var commas = 0, bd = 0, pd = 0, inStr = false, sq = '';\n        while (pos < data.length && pos - nextVar < 200000) {\n            var ch = data[pos];\n            if (inStr) { if (ch === sq && data[pos - 1] !== '\\\\') inStr = false; }\n            else {\n                if (ch === '\"' || ch === \"'\") { inStr = true; sq = ch; }\n                else if (ch === '{') bd++;\n                else if (ch === '}') bd--;\n                else if (ch === '(') pd++;\n                else if (ch === ')') pd--;\n                else if (ch === ',' && bd === 0 && pd === 0) commas++;\n                else if (ch === ';' && bd === 0 && pd === 0) break;\n            }\n            pos++;\n        }\n        if (commas > biggest.count) biggest = { start: nextVar, count: commas, end: pos };\n        varIdx = nextVar + 4;\n    }\n\n    if (biggest.count >= 50) {\n        var chain = data.substring(biggest.start + 4, biggest.end);\n        var names = [], depth = 0, start = 0, inStr = false, sq = '';\n        for (var i = 0; i < chain.length; i++) {\n            var ch = chain[i];\n            if (inStr) { if (ch === sq && chain[i - 1] !== '\\\\') inStr = false; }\n            else {\n                if (ch === '\"' || ch === \"'\") { inStr = true; sq = ch; }\n                else if (ch === '{' || ch === '(' || ch === '[') depth++;\n                else if (ch === '}' || ch === ')' || ch === ']') depth--;\n                else if (ch === ',' && depth === 0) {\n                    var name = chain.substring(start, i).trim();\n                    var eq = name.indexOf('=');\n                    if (eq !== -1) name = name.substring(0, eq).trim();\n                    if (name && /^[\\w$]+$/.test(name)) names.push(name);\n                    start = i + 1;\n                }\n            }\n        }\n        var last = chain.substring(start).trim();\n        var eq = last.indexOf('=');\n        if (eq !== -1) last = last.substring(0, eq).trim();\n        if (last && /^[\\w$]+$/.test(last)) names.push(last);\n        if (names.length > 0) return names;\n    }\n\n    var fallbackNames = new Set();\n    var patterns = [\n        /(?:^|[^a-zA-Z0-9_$])([a-zA-Z_$][\\w$]*)\\s*=\\s*function\\s*\\(/g,\n        /function\\s+([a-zA-Z_$][\\w$]*)\\s*\\(/g,\n        /(?:^|[^a-zA-Z0-9_$])var\\s+([a-zA-Z_$][\\w$]*)\\s*=\\s*function\\s*\\(/g\n    ];\n    for (var pi = 0; pi < patterns.length; pi++) {\n        var rx = patterns[pi], fm;\n        while ((fm = rx.exec(data)) !== null) {\n            if (fm[1] && fm[1].length <= 8) fallbackNames.add(fm[1]);\n        }\n    }\n    return fallbackNames.size > 0 ? Array.from(fallbackNames) : [];\n}\n\n/**\n * Find the IIFE close position — walks known wrapper patterns, falls back\n * to generic `})(` matching.\n */\nfunction findIifeClose(data) {\n    var patterns = [\n        '})(_yt_player)',\n        ').call(this)',\n        '}).call(this)',\n        '})(this)',\n        '})()',\n        '})('\n    ];\n    for (var i = 0; i < patterns.length; i++) {\n        var idx = data.lastIndexOf(patterns[i]);\n        if (idx !== -1) return idx;\n    }\n    return -1;\n}\n\n/**\n * Build runtime probe for n-param discovery.\n *\n * Strategy order (inside the probe):\n *\n *   Phase A — URL-class discovery (primary for modern players):\n *     Walk _yt_player, globalThis, and nested namespaces up to depth 2.\n *     For each function whose toString() is short (<200 chars, no Promise/\n *     yield/async markers) and whose arity is 2, instantiate it with a\n *     googlevideo URL carrying a test `n` value and call `.get(\"n\")` on\n *     the instance. Accept if the result is deterministic, input-dependent,\n *     and doesn't contain the input as a substring. This finds the URL\n *     parser class regardless of its name.\n *\n *   Phase B — XOR dispatcher fallback (legacy players):\n *     Only runs if Phase A found nothing. Enumerates candidate functions\n *     via static var-chain names + runtime globalThis/_yt_player walk,\n *     scans toString() for table-access patterns (XOR, sub, add), then\n *     bit-reversal r-scan across 0–255 testing each candidate/base pair.\n *     Accepts only when the output passes all of:\n *       - typeof === \"string\"\n *       - deterministic across repeated calls\n *       - new chars appear (not a passthrough)\n *       - input is NOT a substring of output  ← v12 fix\n *       - output doesn't start with \"undefined\"/\"null\"/\"NaN\"/\"[object\"\n *\n * The cache fast-path now records which strategy was used so we skip\n * straight to the correct one on subsequent calls with the same player.\n *\n * @param {string} mode - \"n\" or \"sig\"\n * @param {string[]} varNames - static var-chain names (may be empty)\n * @param {string} tableVar - XOR string table var name (for fallback probe)\n * @param {number} splitIdx - index of \"split\" in string table (for fallback)\n * @param {object} callSites - map of candidate name → [[V,Y], ...] literal pairs\n * @param {object|null} cache - { strategy, funcName, r, p, ctorPath } from prior solve\n */\nfunction buildRuntimeProbe(mode, varNames, tableVar, splitIdx, callSites, cache) {\n    var isNParam = mode !== 'sig';\n    var resultKey = isNParam ? 'n' : 'sig';\n    var nameKey = isNParam ? '_nName' : '_sigName';\n\n    // ----- Cache fast-path ------------------------------------------------\n    if (cache && cache.strategy === 'url-class' && cache.ctorPath && isNParam) {\n        return _buildUrlClassFastPath(cache.ctorPath);\n    }\n    // Legacy v7-shaped caches (no `strategy` field but `funcName` present)\n    // are treated as implicit XOR. Lets existing cached entries in storage.local\n    // keep working after the solver upgrade, instead of silently falling through\n    // to full re-solve on every first load post-upgrade.\n    if (cache && cache.funcName && (cache.strategy === 'xor' || !cache.strategy)) {\n        return _buildXorFastPath(mode, cache);\n    }\n\n    // ----- Phase A: URL-class discovery (n-param only) --------------------\n    var phaseA = isNParam ? _buildUrlClassScan() : '';\n\n    // ----- Phase B: XOR fallback ------------------------------------------\n    var phaseB = _buildXorScan(mode, varNames, tableVar, splitIdx, callSites);\n\n    // Skip Phase B if Phase A found something (n-param only)\n    if (isNParam) {\n        return phaseA + '\\nif(!_result.' + resultKey + '){\\n' + phaseB + '\\n}\\n';\n    }\n    return phaseB;\n}\n\n/**\n * Phase A: walk runtime objects, find URL-class constructor.\n * Produces a probe body that sets `_result.n` and `_result._nName` on success.\n */\nfunction _buildUrlClassScan() {\n    return [\n        '// ===== Phase A: URL-class discovery =====',\n        'var _T1=\"wapK3U_wOyBVm5K\", _T2=\"ABCDEFGH12345678\";',\n        'var _URL1=\"https://rr1.googlevideo.com/videoplayback?n=\"+_T1;',\n        'var _URL2=\"https://rr1.googlevideo.com/videoplayback?n=\"+_T2;',\n        '// Pre-filter: looks like a short URL-parser constructor.',\n        'function _looksLikeUrlCtor(c){',\n        '  try {',\n        '    if (typeof c !== \"function\") return false;',\n        '    if (c.length !== 2) return false;',\n        '    var s = Function.prototype.toString.call(c);',\n        '    if (s.length > 200) return false;',\n        '    if (/Promise|yield|async|generator|this\\\\.W\\\\./.test(s)) return false;',\n        '    return true;',\n        '  } catch(e) { return false; }',\n        '}',\n        '// Junk markers — outputs starting with these are stringified undefined/null/objects.',\n        'var _JUNK=[\"undefined\",\"null\",\"NaN\",\"[object\"];',\n        'function _isJunk(v){ for (var j=0;j<_JUNK.length;j++) if (v.indexOf(_JUNK[j])===0) return true; return false; }',\n        '// Full behavioral test.',\n        'function _testCtor(c){',\n        '  if (!_looksLikeUrlCtor(c)) return null;',\n        '  var i1,n1;',\n        '  try { i1 = new c(_URL1, true); } catch(e) { return null; }',\n        '  try {',\n        '    if (!i1 || typeof i1.get !== \"function\") return null;',\n        '    n1 = i1.get(\"n\");',\n        '    if (typeof n1 !== \"string\" || n1.length < 5 || n1.length > 200) return null;',\n        '    if (n1 === _T1 || n1.indexOf(_T1) !== -1 || _isJunk(n1)) return null;',\n        '  } catch(e) { return null; }',\n        '  try {',\n        '    var n1b = new c(_URL1, true).get(\"n\");',\n        '    if (n1 !== n1b) return null;',\n        '    var n2 = new c(_URL2, true).get(\"n\");',\n        '    if (typeof n2 !== \"string\" || n2 === n1 || n2 === _T2) return null;',\n        '    if (n2.indexOf(_T2) !== -1 || _isJunk(n2)) return null;',\n        '    return n1;',\n        '  } catch(e) { return null; }',\n        '}',\n        '// Walk runtime objects looking for a ctor that passes _testCtor.',\n        'var _scanned = new Set(); var _foundA = null;',\n        'function _walkA(obj, path, depth) {',\n        '  if (!obj || depth > 2 || _foundA) return;',\n        '  if (_scanned.has(obj)) return; _scanned.add(obj);',\n        '  var keys; try { keys = Object.keys(obj); } catch(e) { return; }',\n        '  for (var i=0; i<keys.length && !_foundA; i++) {',\n        '    var k = keys[i], v;',\n        '    try { v = obj[k]; } catch(e) { continue; }',\n        '    if (typeof v === \"function\") {',\n        '      var r; try { r = _testCtor(v); } catch(e) { r = null; }',\n        '      if (r !== null) { _foundA = { ctor: v, path: path+k, sample: r }; return; }',\n        '    } else if (typeof v === \"object\" && v !== null && depth < 2) {',\n        '      _walkA(v, path+k+\".\", depth+1);',\n        '    }',\n        '  }',\n        '}',\n        '// Try _yt_player first (most likely home), then globalThis.',\n        'try { if (typeof _yt_player !== \"undefined\") _walkA(_yt_player, \"_yt_player.\", 1); } catch(e) {}',\n        'if (!_foundA) { try { _walkA(globalThis, \"\", 0); } catch(e) {} }',\n        'if (_foundA) {',\n        '  (function(c){',\n        '    _result.n = function(x){',\n        '      try { return new c(\"https://rr1.googlevideo.com/videoplayback?n=\"+x, true).get(\"n\"); }',\n        '      catch(e) { return null; }',\n        '    };',\n        '  })(_foundA.ctor);',\n        '  _result._nName = \"UrlClass(\" + _foundA.path + \")\";',\n        '  _result._nStrategy = \"url-class\";',\n        '  _result._nCtorPath = _foundA.path;',\n        '  // Unified cache object — background.js can store this directly without',\n        '  // parsing _nName. Includes strategy tag so the right fast-path is used.',\n        '  _result._nCache = { strategy: \"url-class\", ctorPath: _foundA.path };',\n        '}'\n    ].join('\\n');\n}\n\n/**\n * Cache fast-path for URL-class: re-resolve the constructor by path and\n * wrap it, without re-walking. Falls through to phase A scan if resolution\n * fails (e.g. the player was rebuilt with different names).\n */\nfunction _buildUrlClassFastPath(ctorPath) {\n    return [\n        '// ===== URL-class fast-path =====',\n        'try {',\n        '  var _parts = ' + JSON.stringify(ctorPath) + '.split(\".\");',\n        '  var _obj = null;',\n        '  if (_parts[0] === \"_yt_player\") { _obj = _yt_player; _parts.shift(); }',\n        '  else { _obj = globalThis; }',\n        '  for (var _i=0; _i<_parts.length && _obj; _i++) {',\n        '    _obj = _obj[_parts[_i]];',\n        '  }',\n        '  if (typeof _obj === \"function\") {',\n        '    // Validate it still works',\n        '    var _t = new _obj(\"https://rr1.googlevideo.com/videoplayback?n=wapK3U_wOyBVm5K\", true);',\n        '    var _n1 = _t.get(\"n\");',\n        '    if (typeof _n1 === \"string\" && _n1 !== \"wapK3U_wOyBVm5K\" && _n1.indexOf(\"wapK3U_wOyBVm5K\") === -1 && _n1.indexOf(\"undefined\") !== 0) {',\n        '      (function(c){',\n        '        _result.n = function(x){',\n        '          try { return new c(\"https://rr1.googlevideo.com/videoplayback?n=\"+x, true).get(\"n\"); }',\n        '          catch(e) { return null; }',\n        '        };',\n        '      })(_obj);',\n        '      _result._nName = \"UrlClass(\" + ' + JSON.stringify(ctorPath) + ' + \")\";',\n        '      _result._nStrategy = \"url-class\";',\n        '      _result._nCtorPath = ' + JSON.stringify(ctorPath) + ';',\n        '      _result._nCache = { strategy: \"url-class\", ctorPath: ' + JSON.stringify(ctorPath) + ' };',\n        '    }',\n        '  }',\n        '} catch(e) {}',\n        // If fast-path failed, fall through to full phase A discovery\n        'if (!_result.n) {',\n        _buildUrlClassScan(),\n        '}'\n    ].join('\\n');\n}\n\n/**\n * Phase B: candidate probe for XOR-dispatched functions.\n *\n * Two sub-strategies, tried in order:\n *\n *   B.1 Call-site literal extraction (primary):\n *       For each candidate, test each literal (V, Y) pair extracted from\n *       the source. This is fast (tens of calls total) and deterministic —\n *       if YouTube ships `kp(1, 7337, s)` in the player source, we find it\n *       immediately without guessing Y.\n *\n *   B.2 Bit-reversal base scan (fallback):\n *       v10/v11 logic — iterate r ∈ 0..255, for each candidate try\n *       p = base ^ r for every extracted NNNN base. Still useful for older\n *       players where call sites weren't literal or the candidate was\n *       inlined.\n *\n * Both sub-strategies use the same validators:\n *   - typeof === \"string\"\n *   - deterministic across repeated calls\n *   - input is NOT a substring of output (kills the \"undefined\"+input false\n *     positive from player 1bb6ee63's TP)\n *   - output doesn't start with \"undefined\"/\"null\"/\"NaN\"/\"[object\"\n *   - n-param: new chars appear; cipher: same charset (permutation)\n */\nfunction _buildXorScan(mode, varNames, tableVar, splitIdx, callSites) {\n    var resultKey = mode === 'sig' ? 'sig' : 'n';\n    var nameKey = mode === 'sig' ? '_sigName' : '_nName';\n    var isNParam = mode !== 'sig';\n    var namesJSON = JSON.stringify(varNames || []);\n    var callSitesJSON = JSON.stringify(callSites || {});\n\n    // Test inputs: must differ in every position or near-every position,\n    // otherwise a cipher that happens to scramble away the overlapping middle\n    // produces identical outputs for both and fails the `_v3 !== _v1` check.\n    // v11 used strings that shared the middle — that's why kp(10,7330) on the\n    // new player was silently rejected.\n    var t1 = isNParam\n        ? 'ABCDEFGHabcdefg1'\n        : 'AOq0QJ8wRAIgTXjVbFq4RE0_C3YYzJ-j-rVqGi25Oj_bm9c3x2CiqKICIFfBKjR5Q3iBvFHIqZLqhY1jQ9o5a_FV8WNi9Z2v3BdMAhIARbCqF0FHn4';\n    var t2 = isNParam\n        ? 'ZYXWVUTS98765432'\n        : 'Zz9aB8cD7eF6gH5iJ4kL3mN2oP1qR0sT-_ZY9XW8VU7TS6RQ5PO4NM3LK2JI1HG0FEDCBA-_abcdefghijklmnopqrstuvwxyz012345AABbCc9XZw';\n    var minBases = isNParam ? 1 : 5;\n    var minSrcLen = isNParam ? 100 : 500;\n\n    // Content validator per mode. Shared by B.1 and B.2.\n    var validate;\n    if (isNParam) {\n        validate = '&&_newCh(_t1,_v1)&&_v1.indexOf(_t1)===-1&&!_isJunkB(_v1)&&_v3.indexOf(_t2)===-1&&!_isJunkB(_v3)';\n    } else {\n        validate = '&&_v1.length>=20&&_v1.length<=_t1.length&&_v1.length>=_t1.length-10&&!_newCh(_t1,_v1)&&!_isJunkB(_v1)';\n    }\n\n    return [\n        '// ===== Phase B: XOR-dispatch candidate probe =====',\n        'var _vn=' + namesJSON + ';',\n        'var _cs=' + callSitesJSON + ';',\n        'var _tv=' + JSON.stringify(tableVar) + ';',\n        'var _si=' + splitIdx + ';',\n        'var _t1=\"' + t1 + '\",_t2=\"' + t2 + '\";',\n        'function _newCh(a,b){var s=new Set(a.split(\"\"));for(var i=0;i<b.length;i++)if(!s.has(b[i]))return true;return false;}',\n        'var _JUNKB=[\"undefined\",\"null\",\"NaN\",\"[object\"];',\n        'function _isJunkB(v){ for (var j=0;j<_JUNKB.length;j++) if (v.indexOf(_JUNKB[j])===0) return true; return false; }',\n        'function _br8(n){n=((n&240)>>4)|((n&15)<<4);n=((n&204)>>2)|((n&51)<<2);return((n&170)>>1)|((n&85)<<1);}',\n        'function _resolveFn(name){',\n        '  try{var f=eval(name);if(typeof f===\"function\")return f;}catch(e){}',\n        '  try{if(typeof _yt_player!==\"undefined\"&&_yt_player&&typeof _yt_player[name]===\"function\")return _yt_player[name];}catch(e){}',\n        '  try{if(typeof globalThis[name]===\"function\")return globalThis[name];}catch(e){}',\n        '  return null;',\n        '}',\n        // Output-shape validator: centralizes the check used by both sub-strategies.\n        'function _checkOutput(f, r, p) {',\n        '  try {',\n        '    var _v1 = f(r, p, _t1);',\n        '    if (typeof _v1 !== \"string\" || _v1 === _t1 || _v1.length === 0 || _v1.length > 300) return false;',\n        '    var _v2 = f(r, p, _t1);',\n        '    if (_v1 !== _v2) return false;',\n        '    var _v3 = f(r, p, _t2);',\n        '    if (typeof _v3 !== \"string\" || _v3 === _v1) return false;',\n        '    return true' + validate + ';',\n        '  } catch(e) { return false; }',\n        '}',\n        '',\n        '// ----- B.1: Call-site literal extraction -----',\n        'var _csKeys = Object.keys(_cs);',\n        'for (var _csi = 0; _csi < _csKeys.length && !_result.' + resultKey + '; _csi++) {',\n        '  var _nm = _csKeys[_csi];',\n        '  var _f = _resolveFn(_nm);',\n        '  if (!_f) continue;',\n        '  var _pairs = _cs[_nm];',\n        '  for (var _pi = 0; _pi < _pairs.length; _pi++) {',\n        '    var _V = _pairs[_pi][0], _Y = _pairs[_pi][1];',\n        '    if (_checkOutput(_f, _V, _Y)) {',\n        '      (function(f, r, p) { _result.' + resultKey + ' = function(x) { return f(r, p, x); }; })(_f, _V, _Y);',\n        '      _result.' + nameKey + ' = _nm + \"(\" + _V + \",\" + _Y + \",x) [call-site]\";',\n        (isNParam ? '      _result._nStrategy = \"xor-callsite\";' : '      _result._sigStrategy = \"xor-callsite\";'),\n        '      _result._' + (isNParam ? 'n' : 'sig') + 'FuncName = _nm;',\n        '      _result._' + (isNParam ? 'n' : 'sig') + 'R = _V;',\n        '      _result._' + (isNParam ? 'n' : 'sig') + 'P = _Y;',\n        '      _result._' + (isNParam ? 'n' : 'sig') + 'Cache = { strategy: \"xor\", funcName: _nm, r: _V, p: _Y };',\n        '      break;',\n        '    }',\n        '  }',\n        '}',\n        '',\n        '// ----- B.2: Bit-reversal base scan (fallback) -----',\n        'if (!_result.' + resultKey + ') {',\n        '  function _getBases(src){',\n        '    var bases=new Set(),m;',\n        '    var rxXor=new RegExp(_tv+\"\\\\\\\\[\\\\\\\\w+\\\\\\\\^(\\\\\\\\d+)\\\\\\\\]\",\"g\");',\n        '    var rxXorRev=new RegExp(_tv+\"\\\\\\\\[(\\\\\\\\d+)\\\\\\\\^\\\\\\\\w+\\\\\\\\]\",\"g\");',\n        '    var rxSub=new RegExp(_tv+\"\\\\\\\\[\\\\\\\\w+\\\\\\\\-(\\\\\\\\d+)\\\\\\\\]\",\"g\");',\n        '    var rxAdd=new RegExp(_tv+\"\\\\\\\\[\\\\\\\\w+\\\\\\\\+(\\\\\\\\d+)\\\\\\\\]\",\"g\");',\n        '    while((m=rxXor.exec(src))!==null)bases.add(parseInt(m[1])^_si);',\n        '    while((m=rxXorRev.exec(src))!==null)bases.add(parseInt(m[1])^_si);',\n        '    while((m=rxSub.exec(src))!==null)bases.add(parseInt(m[1]));',\n        '    while((m=rxAdd.exec(src))!==null)bases.add(parseInt(m[1]));',\n        '    // Also include raw constants (no XOR with _si) for call-site-style patterns.',\n        '    var rxRaw=/\\\\^(\\\\d+)/g;',\n        '    while((m=rxRaw.exec(src))!==null){var n=parseInt(m[1]);if(n<20000)bases.add(n);}',\n        '    return bases;',\n        '  }',\n        '  function _getCandidateNames(){',\n        '    var names=_vn.slice();',\n        '    var seen=new Set(names);',\n        '    try{',\n        '      if(typeof _yt_player!==\"undefined\"&&_yt_player){',\n        '        for(var k in _yt_player){if(!seen.has(k)){names.push(k);seen.add(k);}}',\n        '      }',\n        '    }catch(e){}',\n        '    try{',\n        '      for(var k2 in globalThis){if(!seen.has(k2)){names.push(k2);seen.add(k2);}}',\n        '    }catch(e){}',\n        '    return names;',\n        '  }',\n        '',\n        '  var _cands=[];',\n        '  var _allNames=_getCandidateNames();',\n        '  var _seenFns=new Set();',\n        '  for(var _i=0;_i<_allNames.length;_i++){',\n        '    var _ff=_resolveFn(_allNames[_i]);',\n        '    if(!_ff||_ff.length<3)continue;',\n        '    if(_seenFns.has(_ff))continue;',\n        '    _seenFns.add(_ff);',\n        '    var _s;try{_s=_ff.toString();}catch(e){continue;}',\n        '    if(_s.length<' + minSrcLen + ')continue;',\n        '    var _bs=_getBases(_s);',\n        '    if(_bs.size<' + minBases + ')continue;',\n        '    _cands.push({f:_ff,nm:_allNames[_i],bs:Array.from(_bs)});',\n        '  }',\n        '',\n        '  for(var _ri=0;_ri<256&&!_result.' + resultKey + ';_ri++){',\n        '    var _r=_br8(_ri);',\n        '    for(var _ci=0;_ci<_cands.length&&!_result.' + resultKey + ';_ci++){',\n        '      var _c=_cands[_ci];',\n        '      for(var _bi=0;_bi<_c.bs.length;_bi++){',\n        '        var _p=_c.bs[_bi]^_r;',\n        '        if (_checkOutput(_c.f, _r, _p)) {',\n        '          (function(f,r,p){_result.' + resultKey + '=function(x){return f(r,p,x);};})(_c.f,_r,_p);',\n        '          _result.' + nameKey + '=_c.nm+\"(\"+_r+\",\"+_p+\",x) [bit-scan]\";',\n        (isNParam ? '          _result._nStrategy=\"xor-scan\";' : '          _result._sigStrategy=\"xor-scan\";'),\n        '          _result._' + (isNParam ? 'n' : 'sig') + 'FuncName=_c.nm;',\n        '          _result._' + (isNParam ? 'n' : 'sig') + 'R=_r;',\n        '          _result._' + (isNParam ? 'n' : 'sig') + 'P=_p;',\n        '          _result._' + (isNParam ? 'n' : 'sig') + 'Cache = { strategy: \"xor\", funcName: _c.nm, r: _r, p: _p };',\n        '          break;',\n        '        }',\n        '      }',\n        '    }',\n        '  }',\n        '}'\n    ].filter(Boolean).join('\\n');\n}\n\n/**\n * Cache fast-path for XOR dispatcher.\n */\nfunction _buildXorFastPath(mode, cache) {\n    var isNParam = mode !== 'sig';\n    var resultKey = isNParam ? 'n' : 'sig';\n    var nameKey = isNParam ? '_nName' : '_sigName';\n    var testVal = isNParam\n        ? '\"ABCDEFGHabcdefg1\"'\n        : '\"AOq0QJ8wRAIgTXjVbFq4RE0_C3YYzJ-j-rVqGi25Oj_bm9c3x2CiqKICIFfBKjR5Q3iBvFHIqZLqhY1jQ9o5a_FV8WNi9Z2v3BdMAhIARbCqF0FHn4\"';\n    var testVal2 = isNParam\n        ? '\"ZYXWVUTS98765432\"'\n        : '\"Zz9aB8cD7eF6gH5iJ4kL3mN2oP1qR0sT-_ZY9XW8VU7TS6RQ5PO4NM3LK2JI1HG0FEDCBA-_abcdefghijklmnopqrstuvwxyz012345AABbCc9XZw\"';\n    return [\n        '// ===== XOR fast-path =====',\n        'try{',\n        '  var _cf=(function(n){try{return eval(n);}catch(e){return null;}})(\"' + cache.funcName + '\");',\n        '  if(!_cf){_cf=(typeof _yt_player!==\"undefined\"&&_yt_player&&_yt_player[\"' + cache.funcName + '\"])||null;}',\n        '  if(typeof _cf===\"function\"){',\n        '    var _cv1=_cf(' + cache.r + ',' + cache.p + ',' + testVal + ');',\n        '    var _cv2=_cf(' + cache.r + ',' + cache.p + ',' + testVal + ');',\n        '    var _cv3=_cf(' + cache.r + ',' + cache.p + ',' + testVal2 + ');',\n        // Substring + junk rejection inside the fast-path too, so a stale\n        // cache doesn't silently promote a bad function after a player rotation.\n        (isNParam\n            ? '    var _ok = typeof _cv1===\"string\"&&_cv1===_cv2&&_cv1!==' + testVal + '&&_cv3!==_cv1&&_cv1.indexOf(' + testVal + '.slice(1,-1))===-1&&_cv1.indexOf(\"undefined\")!==0&&_cv1.indexOf(\"null\")!==0;'\n            : '    var _ok = typeof _cv1===\"string\"&&_cv1===_cv2&&_cv1!==' + testVal + '&&_cv3!==_cv1&&_cv1.indexOf(\"undefined\")!==0;'),\n        '    if(_ok){',\n        '      _result.' + resultKey + '=function(x){return _cf(' + cache.r + ',' + cache.p + ',x);};',\n        '      _result.' + nameKey + '=\"' + cache.funcName + '(' + cache.r + ',' + cache.p + ',x)\";',\n        (isNParam\n            ? '      _result._nStrategy=\"xor\"; _result._nFuncName=\"' + cache.funcName + '\"; _result._nR=' + cache.r + '; _result._nP=' + cache.p + '; _result._nCache = { strategy: \"xor\", funcName: \"' + cache.funcName + '\", r: ' + cache.r + ', p: ' + cache.p + ' };'\n            : '      _result._sigStrategy=\"xor\"; _result._sigFuncName=\"' + cache.funcName + '\"; _result._sigR=' + cache.r + '; _result._sigP=' + cache.p + '; _result._sigCache = { strategy: \"xor\", funcName: \"' + cache.funcName + '\", r: ' + cache.r + ', p: ' + cache.p + ' };'),\n        '    }',\n        '  }',\n        '}catch(e){}'\n    ].filter(Boolean).join('\\n');\n}\n\n/**\n * Assemble executable code: SETUP + player + probe.\n */\nfunction assembleCode(data, probeBody) {\n    var iifeCloseIdx = findIifeClose(data);\n    if (iifeCloseIdx === -1) {\n        return SETUP_CODE + '\\n' + data + '\\n;(function(){' + probeBody + '})();';\n    }\n    var strictIdx = findUseStrict(data);\n    var strictLen = useStrictLen(data, strictIdx);\n    var afterStrict = strictIdx !== -1 ? strictIdx + strictLen : data.indexOf('{') + 1;\n    return {\n        direct: SETUP_CODE + '\\n' +\n            data.substring(0, iifeCloseIdx) + '\\n' +\n            probeBody + '\\n' +\n            data.substring(iifeCloseIdx),\n        wrapped: SETUP_CODE + '\\n' +\n            data.substring(0, afterStrict) + '\\ntry{\\n' +\n            data.substring(afterStrict, iifeCloseIdx) + '\\n}catch(_e){}\\n' +\n            probeBody + '\\n' +\n            data.substring(iifeCloseIdx)\n    };\n}\n\n/**\n * Main entry point for n-parameter solving.\n * @param {string} data - Full player.js source.\n * @param {object|null} solvedCache - Cache from previous solve:\n *     { strategy: \"url-class\", ctorPath: \"...\" } or\n *     { strategy: \"xor\", funcName: \"...\", r: N, p: N }\n * @returns {string} Code ready for Function(\"_result\", code)(resultObj)\n */\nfunction preprocessPlayer(data, solvedCache) {\n    var table = findStringTable(data);\n    if (!table) {\n        var usIdx = findUseStrict(data);\n        if (usIdx > 0 && usIdx < 10000) table = findStringTable(data.substring(usIdx));\n    }\n    var varNames = findVarChain(data) || [];\n    // Extract literal call sites for every candidate name. Cheap (a single\n    // regex per name), covers the typical `kp(1, 7337, s)` invocation pattern.\n    var callSites = findCallSites(data, varNames);\n    // URL-class path doesn't need the string table at all, so we proceed even\n    // if the fallback is unusable.\n    var tableVar = table ? table.tableVar : '';\n    var splitIdx = table ? table.splitIdx : 0;\n\n    var probeBody = buildRuntimeProbe('n', varNames, tableVar, splitIdx, callSites, solvedCache);\n    var code = assembleCode(data, probeBody);\n\n    if (typeof code === 'string') return code;\n    var usIdx = findUseStrict(data);\n    return (usIdx > 1000 && usIdx < 10000) ? code.direct : code.wrapped;\n}\n\n/**\n * Cipher entry point — unchanged approach (XOR dispatcher scan), but with\n * the same substring/junk validators so a multi-dispatch helper that\n * happens to have the right shape can't get through.\n */\nfunction preprocessCipher(data, cipherCache) {\n    var table = findStringTable(data);\n    if (!table) {\n        var usIdx = findUseStrict(data);\n        if (usIdx > 0 && usIdx < 10000) table = findStringTable(data.substring(usIdx));\n    }\n    var varNames = findVarChain(data) || [];\n    var callSites = findCallSites(data, varNames);\n    if (!table) return SETUP_CODE + '\\n' + data + '\\n/* solver: no string table */';\n\n    var probeBody = buildRuntimeProbe('sig', varNames, table.tableVar, table.splitIdx, callSites, cipherCache);\n    var code = assembleCode(data, probeBody);\n    if (typeof code === 'string') return code;\n\n    var usIdx = findUseStrict(data);\n    return (usIdx > 1000 && usIdx < 10000) ? code.direct : code.wrapped;\n}\n";




// =============================================================================
// CONSTANTS & CONFIGURATION
// =============================================================================

const VIDEO_CACHE_LIMIT = 256;
const YT_VIDEO_ID_REGEX = /(?:v=|shorts\/|embed\/|live\/|\/v\/|\/e\/|youtu\.be\/|videoId%22%3A%22|videoId%22:%22)([a-zA-Z0-9_-]{11})/;
const YT_PLAYER_URL_REGEX = /"jsUrl":"([^"]+\/player\/([a-zA-Z0-9]+)\/[^"]+)"|src="([^"]+\/player\/([a-zA-Z0-9]+)\/[^"]+)"/;
// Player variant priority — TV player is preferred for signatureTimestamp extraction.
// The n-param and cipher solvers may not find functions in current player versions
// (YouTube has removed these protections from URL-based streaming as of ace4b2f8),
// but the TV player is smaller and faster to fetch than base.js.
const PREFERRED_VARIANTS = ['main', 'tv', 'tv_es6'];

// Page fetch headers (Safari UA — matches yt-dlp web_safari)
const PAGE_FETCH_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-us,en;q=0.5",
    "Sec-Fetch-Mode": "navigate",
    "Accept-Encoding": "gzip, deflate",
    "Connection": "keep-alive"
};

// TVHTML5 client — used for solver/player JS only (n-parameter)
// Stream URLs from TVHTML5 may be DRM-protected or signatureCipher-only
const TVHTML5_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown),gzip(gfe)";
const TVHTML5_CLIENT = {
    clientName: "TVHTML5",
    clientVersion: "7.20260323.17.00",
    userAgent: TVHTML5_USER_AGENT,
    hl: "en",
    timeZone: "UTC",
    utcOffsetMinutes: 0,
    osName: "Unknown_0",
    osVersion: "",
    originalUrl: "https://www.youtube.com/tv",
    platform: "TV",
    platformDetail: "PLATFORM_DETAIL_TV",
    theme: "CLASSIC",
    clientFormFactor: "UNKNOWN_FORM_FACTOR",
    webpSupport: false,
    browserName: "Cobalt",
    browserVersion: "25.lts.30.1034943-gold",
    deviceMake: "Unknown",
    deviceModel: "Unknown",
    chipset: "unknown_0",
    acceptHeader: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    configInfo: {},
    tvAppInfo: {
        appQuality: "TV_APP_QUALITY_FULL_ANIMATION",
        systemIntegrator: "unknown",
        releaseVehicle: "COBALT_RELEASE_VEHICLE_LEGACY_THIRD_PARTY"
    }
};

// WEB client — used for SABR streaming URL + ustreamer config
// Returns serverAbrStreamingUrl and videoPlaybackUstreamerConfig
// Formats may lack direct URLs (SABR-only) but provide format metadata
const WEB_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)";
const WEB_CLIENT = {
    clientName: "WEB_EMBEDDED_PLAYER",
    clientVersion: "2.20260331.00.00",
    hl: "en",
    timeZone: "UTC",
    utcOffsetMinutes: 0
};

// Stream download headers — NO cookies, NO Origin/Referer to googlevideo CDN.
// Auth is baked into URL params (sig, lsig, bui, spc).
// Sending youtube.com cookies to googlevideo.com triggers 403.
// yt-dlp confirmed: only UA + Accept + identity encoding sent to CDN.
function YT_STREAM_HEADERS() {
    return [
        { name: "User-Agent", value: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36" },
        { name: "Accept", value: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
        { name: "Accept-Language", value: "en-us,en;q=0.5" },
        { name: "Sec-Fetch-Mode", value: "navigate" },
        { name: "Accept-Encoding", value: "identity" },
        { name: "Connection", value: "keep-alive" }
    ];
}

/**
 * Returns the browser's actual request headers captured from onBeforeSendHeaders.
 * Falls back to hardcoded YT_STREAM_HEADERS() if not yet captured.
 * These headers are sent to Java for CDN requests (SABR, direct download).
 */
function getBrowserHeaders() {
    return capturedBrowserHeaders || YT_STREAM_HEADERS();
}

// =============================================================================
// STATE
// =============================================================================

const videoCache = new Map();
let youtubeCookie = "";
let cachedSolvers = null;
let cachedPlayerVersion = null;
let cachedCipherOps = null;        // { operations, dispatcherName } from cipher module
let cachedCipherPlayerVersion = null;
let cachedVisitorData = null;
let cachedSapiSid = null;       // SAPISID cookie value
let cachedAuthUser = "0";       // authuser value

// TV session state — populated by bootstrapTvSession()
let tvSessionCookies = "";      // cookies from /tv page (DEVICE_INFO, __Secure-YT_TVFAS, etc.)
let tvRolloutToken = null;      // rolloutToken from /tv page
let tvDeviceExperimentId = null; // deviceExperimentId (from DEVICE_INFO cookie)
let tvClientVersion = null;     // clientVersion from /tv page ytcfg (INNERTUBE_CLIENT_VERSION)
let tvClickTrackingParams = null; // clickTrackingParams from /tv page
let tvSessionTimestamp = 0;     // when session was established
const TV_SESSION_TTL = 4 * 60 * 60 * 1000; // 4 hours

// Intercepted player API responses: videoId -> { playerResponse, timestamp }
// Populated by filterResponseData on /youtubei/v1/player
const interceptedResponses = new Map();
const INTERCEPT_TTL = 60 * 1000; // 60 seconds

// Browser request headers captured from onBeforeSendHeaders on youtube.com XHR calls.
// These are the actual headers the browser sends — User-Agent, Accept-Language, etc.
// Passed to Java for SABR CDN requests (CDN validates UA/Origin match the client session).
// Excludes Cookie (would cause 403 on googlevideo CDN) and other hop-by-hop headers.
let capturedBrowserHeaders = null;

// =============================================================================
// SOLVER MANAGEMENT
// =============================================================================

function getFromPrepared(code) {
    const resultObj = { n: null, sig: null };
    Function("_result", code)(resultObj);
    return resultObj;
}

async function getOrCreateSolvers(playerSource, playerVersion) {
    if (cachedSolvers && cachedPlayerVersion === playerVersion) {
        // Only return memory cache if solver actually works
        if (cachedSolvers.n) {
            console.log(`[Solver] Memory cache hit for ${playerVersion}`);
            return cachedSolvers;
        }
        console.log(`[Solver] Memory cache has no working solver, re-solving ${playerVersion}`);
    }

    let solvedCache = null;
    try {
        const stored = await browser.storage.local.get(PLAYER_CACHE_KEY);
        if (stored[PLAYER_CACHE_KEY]?.version === playerVersion) {
            solvedCache = stored[PLAYER_CACHE_KEY];
            // Logging adapts to strategy: URL-class caches print ctorPath,
            // XOR caches print funcName(r,p,n) as before.
            if (solvedCache.strategy === "url-class") {
                console.log(`[Solver] Storage cache hit for ${solvedCache.version}: UrlClass(${solvedCache.ctorPath})`);
            } else {
                console.log(`[Solver] Storage cache hit for ${solvedCache.version}: ${solvedCache.funcName}(${solvedCache.r},${solvedCache.p},n)`);
            }
        }
    } catch (e) {
        console.warn("[Solver] Storage read error:", e);
    }

    if (!playerSource) {
        console.warn("[Solver] No player source available");
        return null;
    }

    const solver = await loadSolver();
    console.log(`[Solver] Processing player ${playerVersion}...`);
    const preprocessedCode = solver.preprocessPlayer(playerSource, solvedCache);
    const solvers = getFromPrepared(preprocessedCode);

    // If cached params failed, retry from scratch (full candidate search)
    if (!solvers.n && solvedCache) {
        console.warn(`[Solver] Cached params failed for ${playerVersion}, retrying full solve...`);
        try {
            await browser.storage.local.remove(PLAYER_CACHE_KEY);
        } catch (e) {}
        const freshCode = solver.preprocessPlayer(playerSource, null);
        const freshSolvers = getFromPrepared(freshCode);
        if (freshSolvers.n) {
            console.log(`[Solver] Full re-solve succeeded: ${freshSolvers._nName}`);
            cachedSolvers = freshSolvers;
            cachedPlayerVersion = playerVersion;

            // Cache the new params. Prefer the solver's unified _nCache object
            // (v12+); fall back to parsing _nName for older solvers.
            const entry = buildCacheEntry(freshSolvers, playerVersion);
            if (entry) {
                try {
                    await browser.storage.local.set({ [PLAYER_CACHE_KEY]: entry });
                    console.log(`[Solver] Re-cached params for ${playerVersion}`);
                } catch (e) {}
            }
            console.log(`[Solver] Ready: hasN=${!!freshSolvers.n}${freshSolvers.n ? ', func=' + freshSolvers._nName : ' (normal for direct-URL player versions)'}`);
            return freshSolvers;
        }
        console.warn("[Solver] Full re-solve also failed");
    }

    cachedSolvers = solvers;
    cachedPlayerVersion = playerVersion;

    if (solvers.n && !solvedCache) {
        const entry = buildCacheEntry(solvers, playerVersion);
        if (entry) {
            try {
                await browser.storage.local.set({ [PLAYER_CACHE_KEY]: entry });
                console.log(`[Solver] Cached params for ${playerVersion}`);
            } catch (e) {
                console.warn("[Solver] Storage write error:", e);
            }
        }
    }

    console.log(`[Solver] Ready: hasN=${!!solvers.n}${solvers.n ? ', func=' + solvers._nName : ' (normal for direct-URL player versions)'}`);
    return solvers;
}

// Build a storage-cache entry from the solver's result object.
//
// The shell treats the solver's cache objects as OPAQUE. Whatever the solver
// puts in `_nCache` (or `_sigCache` for cipher) gets stored verbatim alongside
// the player version — the shell never introspects field names or strategy
// tags. This means new solver strategies (AST-based parsing, future URL-class
// variants, etc.) can ship via the remote solver module without touching the
// shell: the solver emits a new-shape cache object, we store it, we hand it
// back on the next solve, the solver's fast-path reads it.
//
// The only thing the shell knows about is the legacy v7 format, which
// predates `_nCache` and only exposed `_nName` as a string like "kp(1,7337,n)".
// That format is parsed via parseLegacyNameString — isolated so it can be
// deleted once no users have pre-v12 cached entries (probably 1-2 release
// cycles after v12 ships).
function buildCacheEntry(solvers, playerVersion) {
    if (solvers._nCache && typeof solvers._nCache === "object") {
        return Object.assign({ version: playerVersion }, solvers._nCache);
    }
    const legacy = parseLegacyNameString(solvers._nName, /*suffix=*/"n");
    return legacy ? Object.assign({ version: playerVersion }, legacy) : null;
}

function buildCipherCacheEntry(result, baseVersion) {
    if (result._sigCache && typeof result._sigCache === "object") {
        return Object.assign({ version: baseVersion }, result._sigCache);
    }
    const legacy = parseLegacyNameString(result._sigName, /*suffix=*/"s");
    return legacy ? Object.assign({ version: baseVersion }, legacy) : null;
}

// Parse a v7-era solver name string like "kp(1,7337,n)" or "kp(1,7337,s)"
// into a legacy cache object. Returns null if the string doesn't match the
// legacy XOR format (e.g., it's a URL-class name like "UrlClass(_yt_player.t_)"
// from a solver that forgot to emit _nCache).
//
// The v12 solver treats { funcName, r, p } without a `strategy` field as
// implicit XOR, so this legacy entry works as a cache hit on next solve.
function parseLegacyNameString(name, suffix) {
    if (!name) return null;
    const rx = new RegExp("^([\\w$]+)\\((\\d+),(\\d+),(?:" + suffix + "|x)\\)$");
    const m = name.match(rx);
    if (!m) return null;
    return { funcName: m[1], r: parseInt(m[2]), p: parseInt(m[3]) };
}

// =============================================================================
// CIPHER OPERATIONS MANAGEMENT
// =============================================================================

// The signature cipher function lives in the 'main' player variant (base.js),
// NOT in the TV variant used by the n-param solver. The TV player intentionally
// strips signatureCipher fields because it lacks the decryption code.
// We need to fetch base.js separately when cipher-protected formats are encountered.

let cachedMainPlayerSource = null;
let cachedMainPlayerVersion = null;

async function fetchMainPlayer(playerVersion) {
    // Strip variant suffix if present (e.g. "abc123-tv" -> "abc123")
    const baseVersion = playerVersion.replace(/-(?:tv|tv_es6|main)$/, '');

    if (cachedMainPlayerSource && cachedMainPlayerVersion === baseVersion) {
        console.log(`[Cipher] Main player cache hit for ${baseVersion}`);
        return cachedMainPlayerSource;
    }

    const url = getPlayerUrlForVariant(baseVersion, 'main');
    console.log(`[Cipher] Fetching main player for cipher: ${url}`);
    try {
        const resp = await fetch(url);
        if (resp.ok) {
            const source = await resp.text();
            if (source.length > 10000) {
                cachedMainPlayerSource = source;
                cachedMainPlayerVersion = baseVersion;
                console.log(`[Cipher] Fetched main player ${baseVersion} - ${Math.round(source.length / 1024)}KB`);
                return source;
            }
        }
    } catch (e) {
        console.warn(`[Cipher] Main player fetch failed: ${e.message}`);
    }
    return null;
}

async function getOrCreateCipherOps(playerVersion) {
    // Use base version (without variant suffix) as cache key for cipher ops
    const baseVersion = playerVersion.replace(/-(?:tv|tv_es6|main)$/, '');

    if (cachedCipherOps && cachedCipherPlayerVersion === baseVersion) {
        if (cachedCipherOps.sig) {
            console.log(`[Cipher] Memory cache hit for ${baseVersion}`);
            return cachedCipherOps;
        }
        console.log(`[Cipher] Memory cache has no working cipher, re-solving ${baseVersion}`);
    }

    // Check storage cache — stores the solver's unified _sigCache object when
    // available (v12+) or the legacy {funcName, r, p} shape otherwise.
    let cipherCache = null;
    if (!TEST_MODE) {
        try {
            const stored = await browser.storage.local.get(CIPHER_OPS_CACHE_KEY);
            if (stored[CIPHER_OPS_CACHE_KEY]?.version === baseVersion) {
                cipherCache = stored[CIPHER_OPS_CACHE_KEY];
                if (cipherCache.strategy === "url-class") {
                    console.log(`[Cipher] Storage cache hit for ${baseVersion}: UrlClass(${cipherCache.ctorPath})`);
                } else {
                    console.log(`[Cipher] Storage cache hit for ${baseVersion}: ${cipherCache.funcName}(${cipherCache.r},${cipherCache.p},s)`);
                }
            }
        } catch (e) {
            console.warn("[Cipher] Storage read error:", e);
        }
    }

    // Cipher function lives in main (base.js), not TV variant
    const mainSource = await fetchMainPlayer(playerVersion);
    if (!mainSource) {
        console.warn("[Cipher] No main player source available");
        return null;
    }

    const solver = await loadSolver();
    if (!solver.preprocessCipher) {
        console.warn("[Cipher] Solver does not support preprocessCipher (needs v6+)");
        return null;
    }

    console.log(`[Cipher] Processing main player ${baseVersion}...`);
    const preprocessedCode = solver.preprocessCipher(mainSource, cipherCache);
    const resultObj = { n: null, sig: null };
    Function("_result", preprocessedCode)(resultObj);

    // If cached params failed, retry from scratch
    if (!resultObj.sig && cipherCache) {
        console.warn(`[Cipher] Cached params failed for ${baseVersion}, retrying full solve...`);
        try { await browser.storage.local.remove(CIPHER_OPS_CACHE_KEY); } catch (e) {}
        const freshCode = solver.preprocessCipher(mainSource, null);
        const freshResult = { n: null, sig: null };
        Function("_result", freshCode)(freshResult);
        if (freshResult.sig) {
            console.log(`[Cipher] Full re-solve succeeded: ${freshResult._sigName}`);
            resultObj.sig = freshResult.sig;
            resultObj._sigName = freshResult._sigName;
            // Carry over the unified cache object from the fresh result so
            // the storage write below uses the correct fields.
            if (freshResult._sigCache) resultObj._sigCache = freshResult._sigCache;
        } else {
            console.warn("[Cipher] Full re-solve also failed");
        }
    }

    if (!resultObj.sig) {
        console.log("[Cipher] No cipher function found (normal for direct-URL player versions)");
        return null;
    }

    console.log(`[Cipher] Found: ${resultObj._sigName}`);

    // Cache params for fast path. Prefer the solver's unified _sigCache object
    // (v12+); fall back to regex-parsing _sigName for older solvers.
    const cacheEntry = buildCipherCacheEntry(resultObj, baseVersion);
    if (cacheEntry) {
        try {
            await browser.storage.local.set({ [CIPHER_OPS_CACHE_KEY]: cacheEntry });
            console.log(`[Cipher] Cached params for ${baseVersion}`);
        } catch (e) {
            console.warn("[Cipher] Storage write error:", e);
        }
    }

    cachedCipherOps = resultObj;
    cachedCipherPlayerVersion = baseVersion;
    return resultObj;
}

// =============================================================================
// URL PARAMETER EXTRACTION & REPLACEMENT
// =============================================================================

function extractUrlParams(urlString) {
    const result = { n: null, sig: null };
    try {
        const url = new URL(urlString);
        const pathSegments = url.pathname.split('/');
        if (url.searchParams.has('n')) {
            result.n = url.searchParams.get('n');
        } else {
            const nIdx = pathSegments.indexOf('n');
            if (nIdx !== -1 && nIdx + 1 < pathSegments.length) result.n = pathSegments[nIdx + 1];
        }
        for (const paramName of ['sig', 'signature', 's']) {
            if (url.searchParams.has(paramName)) { result.sig = url.searchParams.get(paramName); break; }
            const idx = pathSegments.indexOf(paramName);
            if (idx !== -1 && idx + 1 < pathSegments.length) { result.sig = pathSegments[idx + 1]; break; }
        }
    } catch (e) {
        const nMatch = urlString.match(/[?&/]n[=/]([^&/]+)/);
        if (nMatch) result.n = nMatch[1];
        for (const p of ['sig', 'signature', 's']) {
            const m = urlString.match(new RegExp(`[?&/]${p}[=/]([^&/]+)`));
            if (m) { result.sig = m[1]; break; }
        }
    }
    return result;
}

function replaceUrlParams(urlString, newValues) {
    try {
        const url = new URL(urlString);
        const pathSegments = url.pathname.split('/');
        if (newValues.n) {
            if (url.searchParams.has('n')) {
                console.log(`[Replace] n: ${url.searchParams.get('n')} -> ${newValues.n}`);
                url.searchParams.set('n', newValues.n);
            } else {
                const nIdx = pathSegments.indexOf('n');
                if (nIdx !== -1 && nIdx + 1 < pathSegments.length) {
                    console.log(`[Replace] n (path): ${pathSegments[nIdx + 1]} -> ${newValues.n}`);
                    pathSegments[nIdx + 1] = newValues.n;
                    url.pathname = pathSegments.join('/');
                }
            }
        }
        if (newValues.sig) {
            for (const paramName of ['sig', 'signature', 's']) {
                if (url.searchParams.has(paramName)) {
                    console.log(`[Replace] ${paramName}: ${url.searchParams.get(paramName).substring(0, 20)}... -> ${newValues.sig.substring(0, 20)}...`);
                    url.searchParams.set(paramName, newValues.sig);
                    break;
                }
                const idx = pathSegments.indexOf(paramName);
                if (idx !== -1 && idx + 1 < pathSegments.length) {
                    pathSegments[idx + 1] = newValues.sig;
                    url.pathname = pathSegments.join('/');
                    break;
                }
            }
        }
        return url.toString();
    } catch (e) {
        return urlString;
    }
}

// Cache solved n-params: same n value across all format URLs for a given video
const nParamCache = new Map();
const N_PARAM_CACHE_MAX = 32;

function transformUrl(urlString, solvers) {
    const params = extractUrlParams(urlString);
    if (!params.n && !params.sig) return urlString;
    const newValues = {};
    if (params.n && solvers.n) {
        try {
            // Check cache first — YouTube uses the same n across all format URLs
            let newN = nParamCache.get(params.n);
            if (!newN) {
                newN = solvers.n(params.n);
                if (newN && newN !== params.n) {
                    if (nParamCache.size >= N_PARAM_CACHE_MAX) nParamCache.clear();
                    nParamCache.set(params.n, newN);
                    console.log(`[Transform] n: ${params.n} -> ${newN} (solved)`);
                }
            }
            if (newN && newN !== params.n) newValues.n = newN;
        } catch (e) { console.warn("[Transform] n-param error:", e); }
    }
    return Object.keys(newValues).length > 0 ? replaceUrlParams(urlString, newValues) : urlString;
}

// =============================================================================
// VIDEO TRACKING
// =============================================================================

const VIDEO_CACHE_TTL = 60 * 1000; // 60 seconds — allow re-processing after this

function extractVideoId(url) {
    const match = url.match(YT_VIDEO_ID_REGEX);
    return match ? match[1] : null;
}

function trackVideo(tabId, videoId) {
    const key = `${tabId}:${videoId}`;
    const cached = videoCache.get(key);
    if (cached && (Date.now() - cached) < VIDEO_CACHE_TTL) return false;
    if (videoCache.size >= VIDEO_CACHE_LIMIT) {
        videoCache.delete(videoCache.keys().next().value);
    }
    videoCache.set(key, Date.now());
    console.log(`[Cache] New video: ${videoId} (tab ${tabId})`);
    return true;
}

function clearTabFromCache(tabId) {
    for (const key of videoCache.keys()) {
        if (key.startsWith(`${tabId}:`)) videoCache.delete(key);
    }
}

// =============================================================================
// PLAYER.JS FETCHING
// =============================================================================

function getPlayerUrlForVariant(version, variant) {
    const paths = {
        'tv': 'tv-player-ias.vflset/tv-player-ias.js',
        'tv_es6': 'tv-player-ias_es6.vflset/tv-player-ias_es6.js',
        'main': 'player_ias.vflset/en_US/base.js',
    };
    return `https://www.youtube.com/s/player/${version}/${paths[variant] || paths['tv']}`;
}

const PINNED_PLAYER_VERSION = null;

async function fetchAndCachePlayer(html) {
    const playerUrlMatch = html.match(YT_PLAYER_URL_REGEX);
    if (!playerUrlMatch) { console.warn("[Player] Could not find player URL"); return null; }

    const originalUrlPath = playerUrlMatch[1] || playerUrlMatch[3];
    const actualVersion = playerUrlMatch[2] || playerUrlMatch[4];
    const version = PINNED_PLAYER_VERSION || actualVersion;

    for (const variant of PREFERRED_VARIANTS) {
        const url = getPlayerUrlForVariant(version, variant);
        console.log(`[Player] Trying ${variant}: ${url}`);
        try {
            const resp = await fetch(url);
            if (resp.ok) {
                const source = await resp.text();
                if (source.length > 10000) {
                    // Include variant in version key so cache doesn't collide between main/tv
                    const versionKey = `${version}-${variant}`;
                    console.log(`[Player] Fetched ${versionKey} - ${Math.round(source.length / 1024)}KB`);
                    return { source, version: versionKey };
                }
            }
        } catch (e) { console.warn(`[Player] ${variant} failed:`, e.message); }
    }

    try {
        const originalUrl = originalUrlPath.startsWith('http') ? originalUrlPath : `https://www.youtube.com${originalUrlPath}`;
        const resp = await fetch(originalUrl);
        if (resp.ok) {
            const source = await resp.text();
            return { source, version: actualVersion };
        }
    } catch (e) {}
    return null;
}

// =============================================================================
// SAPISIDHASH AUTH — matches yt-dlp's Authorization header computation
// =============================================================================

// Pure JS SHA-1 for environments where crypto.subtle is unavailable (GeckoView bg)
function sha1Pure(str) {
    const utf8 = unescape(encodeURIComponent(str));
    const data = [];
    for (let i = 0; i < utf8.length; i++) data.push(utf8.charCodeAt(i));

    // Pre-processing
    data.push(0x80);
    const bitLen = (utf8.length) * 8;
    while ((data.length % 64) !== 56) data.push(0);
    // Append length as 64-bit big-endian
    for (let i = 56; i >= 0; i -= 8) data.push((bitLen >>> i) & 0xff);

    let h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;

    for (let offset = 0; offset < data.length; offset += 64) {
        const w = new Array(80);
        for (let i = 0; i < 16; i++) {
            w[i] = (data[offset + i * 4] << 24) | (data[offset + i * 4 + 1] << 16) |
                   (data[offset + i * 4 + 2] << 8) | data[offset + i * 4 + 3];
        }
        for (let i = 16; i < 80; i++) {
            const t = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
            w[i] = (t << 1) | (t >>> 31);
        }

        let a = h0, b = h1, c = h2, d = h3, e = h4;
        for (let i = 0; i < 80; i++) {
            let f, k;
            if (i < 20)      { f = (b & c) | ((~b) & d); k = 0x5A827999; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
            else              { f = b ^ c ^ d; k = 0xCA62C1D6; }
            const temp = (((a << 5) | (a >>> 27)) + f + e + k + w[i]) | 0;
            e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = temp;
        }
        h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0; h4 = (h4 + e) | 0;
    }

    const hex = (n) => ((n >>> 0).toString(16)).padStart(8, "0");
    return hex(h0) + hex(h1) + hex(h2) + hex(h3) + hex(h4);
}

async function computeSapiSidHash(sapiSid, origin) {
    const timestamp = Math.floor(Date.now() / 1000);
    const input = `${timestamp} ${sapiSid} ${origin}`;

    let hashHex;
    try {
        // Try SubtleCrypto first (available in some WebExtension contexts)
        const data = new TextEncoder().encode(input);
        const hashBuffer = await crypto.subtle.digest("SHA-1", data);
        hashHex = Array.from(new Uint8Array(hashBuffer)).map(b => b.toString(16).padStart(2, "0")).join("");
    } catch (e) {
        // Fallback to pure JS SHA-1 (GeckoView background scripts)
        hashHex = sha1Pure(input);
    }

    return `${timestamp}_${hashHex}`;
}

async function buildAuthHeaders() {
    const headers = [];
    const origin = "https://www.youtube.com";

    // cachedSapiSid is set by captureCookies from onBeforeSendHeaders
    // (this is the only reliable source — browser.cookies API misses HttpOnly cookies)
    if (cachedSapiSid) {
        const hash = await computeSapiSidHash(cachedSapiSid, origin);
        // yt-dlp sends all three SAPISIDHASH variants
        const authValue = `SAPISIDHASH ${hash} SAPISID1PHASH ${hash} SAPISID3PHASH ${hash}`;
        headers.push({ name: "Authorization", value: authValue });
        headers.push({ name: "X-Goog-Authuser", value: cachedAuthUser });
        console.log(`[Auth] SAPISIDHASH computed (ts=${hash.split("_")[0]})`);
    } else {
        console.warn("[Auth] No SAPISID available — request will be unauthenticated");
    }

    return headers;
}

// =============================================================================
// VISITOR ID — yt-dlp extracts from __Secure-YEC cookie
// =============================================================================

async function getVisitorId() {
    // Priority 1: __Secure-YEC cookie (yt-dlp's source)
    try {
        const yecCookies = await browser.cookies.getAll({ domain: ".youtube.com", name: "__Secure-YEC" });
        if (yecCookies.length > 0 && yecCookies[0].value) {
            return yecCookies[0].value;
        }
    } catch (e) {}

    // Priority 2: VISITOR_DATA from page HTML (already cached)
    if (cachedVisitorData) return cachedVisitorData;

    // Priority 3: VISITOR_INFO1_LIVE cookie
    try {
        const viCookies = await browser.cookies.getAll({ domain: ".youtube.com", name: "VISITOR_INFO1_LIVE" });
        if (viCookies.length > 0 && viCookies[0].value) return viCookies[0].value;
    } catch (e) {}

    return "";
}

// =============================================================================
// TV SESSION BOOTSTRAP — visit /tv to collect session cookies
// yt-dlp does this before every TVHTML5 innertube request.
// The CDN validates that stream tokens match a valid TV session.
// Without DEVICE_INFO, __Secure-ROLLOUT_TOKEN, __Secure-YT_TVFAS
// cookies, the innertube API returns URLs with tokens the CDN rejects (403).
// =============================================================================

async function bootstrapTvSession() {
    // Return cached session if still fresh
    if (tvSessionCookies && (Date.now() - tvSessionTimestamp) < TV_SESSION_TTL) {
        console.log("[TVSession] Using cached session");
        return true;
    }

    console.log("[TVSession] Bootstrapping TV session...");

    // Merge browser cookies with minimal fallback
    let baseCookies = "PREF=hl=en&tz=UTC; SOCS=CAI";
    if (youtubeCookie) {
        baseCookies = youtubeCookie;
    } else {
        try {
            const cookies = await browser.cookies.getAll({ domain: ".youtube.com" });
            if (cookies.length > 0) baseCookies = cookies.map(c => `${c.name}=${c.value}`).join("; ");
        } catch (e) {}
    }


    const tvHeaders = [
        { name: "User-Agent", value: TVHTML5_USER_AGENT },
        { name: "Accept", value: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
        { name: "Accept-Language", value: "en-us,en;q=0.5" },
        { name: "Sec-Fetch-Mode", value: "navigate" },
        { name: "Accept-Encoding", value: "gzip, deflate" },
        { name: "Connection", value: "keep-alive" },
        { name: "Cookie", value: baseCookies }
    ];

    try {
        const resp = await nativeFetch("https://www.youtube.com/tv", tvHeaders, { timeoutMs: 15000 });
        if (!resp?.html || resp.html.length < 1000) {
            console.warn("[TVSession] Empty or short response");
            return false;
        }

        // Extract Set-Cookie values from response (Java returns setCookies array)
        // Merge response cookies with base cookies
        let sessionCookies = baseCookies;
        if (resp.setCookies && Array.isArray(resp.setCookies)) {
            // setCookies are full Set-Cookie header values like "NAME=VALUE; Path=/; ..."
            // Extract just the name=value part (before first ;)
            const newCookies = resp.setCookies
                .map(sc => sc.split(";")[0].trim())
                .filter(Boolean)
                .join("; ");
            if (newCookies) {
                sessionCookies = mergeCookies(baseCookies, newCookies);
                console.log(`[TVSession] Got ${resp.setCookies.length} Set-Cookie headers from /tv`);
            }
        }

        // Extract rolloutToken from /tv HTML
        const rolloutMatch = resp.html.match(/rolloutToken["']\s*:\s*["']([^"']+)["']/);
        if (rolloutMatch) {
            tvRolloutToken = rolloutMatch[1];
            console.log(`[TVSession] rolloutToken: ${tvRolloutToken.substring(0, 40)}...`);
        }

        // Extract INNERTUBE_CLIENT_VERSION from /tv ytcfg (yt-dlp uses this instead of hardcoded)
        const cvMatch = resp.html.match(/INNERTUBE_CLIENT_VERSION["']\s*:\s*["']([^"']+)["']/) ||
                        resp.html.match(/"clientVersion"\s*:\s*"([^"]+)"/);
        if (cvMatch) {
            tvClientVersion = cvMatch[1];
            console.log(`[TVSession] clientVersion: ${tvClientVersion}`);
        }

        // Extract clickTrackingParams from /tv page (yt-dlp includes this)
        const ctpMatch = resp.html.match(/clickTrackingParams["']\s*:\s*["']([^"']+)["']/);
        if (ctpMatch) {
            tvClickTrackingParams = ctpMatch[1];
            console.log(`[TVSession] clickTrackingParams: ${tvClickTrackingParams.substring(0, 30)}...`);
        }

        // Extract DEVICE_INFO cookie value for deviceExperimentId
        const deviceInfoMatch = sessionCookies.match(/(?:^|;\s*)DEVICE_INFO=([^;]+)/);
        if (deviceInfoMatch) {
            tvDeviceExperimentId = decodeURIComponent(deviceInfoMatch[1]);
            console.log(`[TVSession] deviceExperimentId: ${tvDeviceExperimentId.substring(0, 30)}...`);
        }

        // Extract visitorData from /tv page if we don't have it yet
        if (!cachedVisitorData) {
            const vdMatch = resp.html.match(/["']VISITOR_DATA["']\s*:\s*["']([^"']+)["']/) ||
                            resp.html.match(/"visitorData"\s*:\s*"([^"]+)"/);
            if (vdMatch) {
                cachedVisitorData = vdMatch[1];
                console.log(`[TVSession] VISITOR_DATA from /tv: ${cachedVisitorData.substring(0, 30)}...`);
            }
        }

        // Also try to get __Secure-ROLLOUT_TOKEN from cookies if not in HTML
        if (!tvRolloutToken) {
            const rtMatch = sessionCookies.match(/(?:^|;\s*)__Secure-ROLLOUT_TOKEN=([^;]+)/);
            if (rtMatch) {
                tvRolloutToken = decodeURIComponent(rtMatch[1]);
                console.log(`[TVSession] rolloutToken from cookie: ${tvRolloutToken.substring(0, 40)}...`);
            }
        }

        // Fallback: use browser.cookies API to capture cookies set by /tv response
        // nativeFetch goes through OkHttp which may not return Set-Cookie headers
        try {
            const tvCookies = await browser.cookies.getAll({ domain: ".youtube.com" });
            if (tvCookies.length > 0) {
                const apiCookieStr = tvCookies.map(c => `${c.name}=${c.value}`).join("; ");
                sessionCookies = mergeCookies(sessionCookies, apiCookieStr);

                // Re-check for TV-specific cookies from API
                if (!tvDeviceExperimentId) {
                    const di = tvCookies.find(c => c.name === "DEVICE_INFO");
                    if (di) {
                        tvDeviceExperimentId = di.value;
                        console.log(`[TVSession] deviceExperimentId from cookie API: ${tvDeviceExperimentId.substring(0, 30)}...`);
                    }
                }
                if (!tvRolloutToken) {
                    const rt = tvCookies.find(c => c.name === "__Secure-ROLLOUT_TOKEN");
                    if (rt) {
                        tvRolloutToken = decodeURIComponent(rt.value);
                        console.log(`[TVSession] rolloutToken from cookie API: ${tvRolloutToken.substring(0, 40)}...`);
                    }
                }
            }
        } catch (e) {
            console.log(`[TVSession] browser.cookies fallback failed: ${e.message}`);
        }

        tvSessionCookies = sessionCookies;
        tvSessionTimestamp = Date.now();

        const hasDeviceInfo = sessionCookies.includes("DEVICE_INFO=");
        const hasRollout = sessionCookies.includes("__Secure-ROLLOUT_TOKEN=");
        const hasTvFas = sessionCookies.includes("__Secure-YT_TVFAS=");
        console.log(`[TVSession] Session established: DEVICE_INFO=${hasDeviceInfo}, ROLLOUT_TOKEN=${hasRollout}, YT_TVFAS=${hasTvFas}, cookies=${sessionCookies.split(";").length}`);

        return true;
    } catch (e) {
        console.warn(`[TVSession] Bootstrap failed: ${e.message}`);
        return false;
    }
}

/**
 * Merge two cookie strings, with newer values overriding older ones.
 */
function mergeCookies(base, additions) {
    const cookieMap = new Map();
    for (const str of [base, additions]) {
        if (!str) continue;
        for (const part of str.split(";")) {
            const eq = part.indexOf("=");
            if (eq > 0) {
                const name = part.substring(0, eq).trim();
                const value = part.substring(eq + 1).trim();
                if (name && value) cookieMap.set(name, value);
            }
        }
    }
    return Array.from(cookieMap.entries()).map(([k, v]) => `${k}=${v}`).join("; ");
}

// =============================================================================
// TVHTML5 INNERTUBE API — matches yt-dlp "tv downgraded player API" request
// =============================================================================

async function fetchTvHtml5Stream(videoId, sigTimestamp, cipherOps) {
    console.log(`[TVHTML5] Fetching player API for ${videoId}`);

    // Bootstrap TV session — required for valid CDN stream tokens
    await bootstrapTvSession();

    const visitorId = await getVisitorId();

    const payload = {
        context: {
            client: {
                ...TVHTML5_CLIENT,
                // Override clientVersion with dynamic value from /tv page if available
                ...(tvClientVersion ? { clientVersion: tvClientVersion } : {}),
                ...(cachedVisitorData ? { visitorData: cachedVisitorData } : {}),
                ...(tvDeviceExperimentId ? { deviceExperimentId: tvDeviceExperimentId } : {}),
                ...(tvRolloutToken ? { rolloutToken: tvRolloutToken } : {})
            },
            user: { lockedSafetyMode: false },
            request: { useSsl: true },
            ...(tvClickTrackingParams ? { clickTracking: { clickTrackingParams: tvClickTrackingParams } } : {})
        },
        videoId,
        playbackContext: {
            contentPlaybackContext: {
                html5Preference: "HTML5_PREF_WANTS",
                ...(sigTimestamp ? { signatureTimestamp: sigTimestamp } : {})
            }
        },
        contentCheckOk: true,
        racyCheckOk: true
    };

    // Use TV session cookies (includes DEVICE_INFO, __Secure-ROLLOUT_TOKEN, __Secure-YT_TVFAS)
    // Fall back to browser cookies if TV session not available
    let cookieString = "";
    let cookieCount = 0;

    if (tvSessionCookies) {
        cookieString = tvSessionCookies;
        cookieCount = cookieString.split(";").length;
        console.log(`[TVHTML5] Using TV session cookies (${cookieCount} entries)`);
    } else if (youtubeCookie) {
        cookieString = youtubeCookie;
        cookieCount = cookieString.split(";").length;
    } else {
        let cookies;
        try { cookies = await browser.cookies.getAll({ domain: ".youtube.com" }); }
        catch (e) { cookies = []; }
        cookieString = cookies.map(c => `${c.name}=${c.value}`).join("; ");
        cookieCount = cookies.length;
    }

    // Diagnostic: check for critical TV session cookies
    const hasDeviceInfo = cookieString.includes("DEVICE_INFO=");
    const hasRollout = cookieString.includes("__Secure-ROLLOUT_TOKEN=");
    const hasTvFas = cookieString.includes("__Secure-YT_TVFAS=");
    const hasLogin = cookieString.includes("LOGIN_INFO=");
    const hasSAPISID = cookieString.includes("SAPISID=");
    console.log(`[TVHTML5] Cookies: ${cookieCount} entries, DEVICE_INFO=${hasDeviceInfo}, ROLLOUT=${hasRollout}, TVFAS=${hasTvFas}, LOGIN=${hasLogin}, SAPISID=${hasSAPISID}`);

    const apiUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    const headers = [
        { name: "User-Agent", value: TVHTML5_USER_AGENT },
        { name: "Accept", value: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
        { name: "Accept-Language", value: "en-us,en;q=0.5" },
        { name: "Sec-Fetch-Mode", value: "navigate" },
        { name: "Content-Type", value: "application/json" },
        { name: "X-Youtube-Client-Name", value: "7" },
        { name: "X-Youtube-Client-Version", value: tvClientVersion || TVHTML5_CLIENT.clientVersion },
        { name: "Origin", value: "https://www.youtube.com" },
        { name: "X-Origin", value: "https://www.youtube.com" },
        { name: "Accept-Encoding", value: "gzip, deflate" },
        { name: "Connection", value: "keep-alive" },
    ];

    if (visitorId) headers.push({ name: "X-Goog-Visitor-Id", value: visitorId });
    if (cookieString) headers.push({ name: "Cookie", value: cookieString });

    // Add SAPISIDHASH authorization (critical for authenticated format access)
    const authHeaders = await buildAuthHeaders();
    headers.push(...authHeaders);

    // Diagnostic: log what we're sending
    const hasAuth = headers.some(h => h.name === "Authorization");
    const hasCookie = headers.some(h => h.name === "Cookie");
    const hasVisitor = headers.some(h => h.name === "X-Goog-Visitor-Id");
    console.log(`[TVHTML5] Request: auth=${hasAuth}, cookies=${hasCookie}(${cookieCount}), visitor=${hasVisitor}, sts=${sigTimestamp}, rollout=${!!tvRolloutToken}, deviceExp=${!!tvDeviceExperimentId}`);

    try {
        const resp = await nativeFetch(apiUrl, headers, {
            method: "POST",
            body: JSON.stringify(payload),
            timeoutMs: 15000
        });

        if (!resp?.html) {
            console.warn("[TVHTML5] Empty response");
            return null;
        }

        const playerResponse = JSON.parse(resp.html);
        const status = playerResponse.playabilityStatus?.status;
        console.log(`[TVHTML5] playabilityStatus: ${status}`);

        if (status !== "OK") {
            console.warn(`[TVHTML5] Rejected: ${playerResponse.playabilityStatus?.reason || status}`);
            return null;
        }

        const streamingData = playerResponse.streamingData;
        const videoDetails = playerResponse.videoDetails;
        const isLive = videoDetails?.isLive === true;
        const meta = {
            title: videoDetails?.title,
            description: videoDetails?.shortDescription,
            duration: parseInt(videoDetails?.lengthSeconds || "0", 10) * 1000,
            isLive,
            hlsManifestUrl: streamingData?.hlsManifestUrl || null,
            // SABR fields — for Java SabrDownloader
            serverAbrStreamingUrl: streamingData?.serverAbrStreamingUrl || null,
            videoPlaybackUstreamerConfig: streamingData?.videoPlaybackUstreamerConfig
                || playerResponse?.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig
                || null
        };

        if (isLive) {
            console.log(`[TVHTML5] Live stream detected`);
        }

        const adaptiveFormats = streamingData?.adaptiveFormats || [];
        const formats = streamingData?.formats || [];

        // Log format summary
        const resolutions = adaptiveFormats
            .filter(f => f.mimeType?.startsWith("video/") && f.url)
            .map(f => `${f.height}p/${f.itag}`)
            .join(", ");
        console.log(`[TVHTML5] Formats: ${adaptiveFormats.length} adaptive, ${formats.length} muxed`);
        console.log(`[TVHTML5] Video streams: ${resolutions}`);

        // Build variants from adaptive formats (unrestricted codec selection)
        const variants = buildAdaptiveVariants(adaptiveFormats, cipherOps);

        if (variants.length > 0) {
            console.log(`[TVHTML5] Built ${variants.length} adaptive variant(s)`);
            return { variants, ...meta };
        }

        // Fallback: itag 18 (360p muxed)
        const itag18 = formats.find(f => f.itag === 18);
        if (itag18) {
            let itag18Url = itag18.url || null;
            if (!itag18Url && itag18.signatureCipher && cipherOps && typeof cipherOps.sig === 'function') {
                try {
                    const parsed = parseSignatureCipherInline(itag18.signatureCipher);
                    if (parsed) { const dec = cipherOps.sig(parsed.s); if (dec && dec !== parsed.s) { const sep = parsed.url.indexOf("?") !== -1 ? "&" : "?"; itag18Url = parsed.url + sep + encodeURIComponent(parsed.sp) + "=" + encodeURIComponent(dec); } }
                } catch (e) {}
            }
            if (itag18Url) {
                console.log(`[TVHTML5] Got itag 18 fallback${itag18.url ? '' : ' (cipher resolved)'}`);
                return { url: itag18Url, ...meta };
            }
        }

        console.warn("[TVHTML5] No stream URL in response");
        return null;
    } catch (e) {
        console.warn(`[TVHTML5] Fetch error: ${e.message}`);
        return null;
    }
}

/**
 * Build variant pairs from YouTube adaptive formats.
 * Filters to mp4-muxable codecs: h264 and AV1 video, AAC audio.
 * AV1 fills resolutions above 1080p (4K, 1440p) where h264 isn't available.
 * One variant per unique resolution, paired with best AAC audio.
 * Returns array of { url, audioUrl, width, height, videoCodec, audioCodec, itag, audioItag }
 */
// =============================================================================
// WEB CLIENT INNERTUBE API — fetches SABR streaming URL + format metadata
// The web client returns serverAbrStreamingUrl and videoPlaybackUstreamerConfig
// which are needed by the Java SabrDownloader.
// =============================================================================

async function fetchWebStream(videoId, sigTimestamp) {
    console.log(`[WEB] Fetching player API for ${videoId}`);
    const visitorId = await getVisitorId();

    const payload = {
        context: {
            client: {
                ...WEB_CLIENT,
                ...(cachedVisitorData ? { visitorData: cachedVisitorData } : {})
            },
            user: { lockedSafetyMode: false },
            request: { useSsl: true }
        },
        videoId,
        playbackContext: {
            contentPlaybackContext: {
                html5Preference: "HTML5_PREF_WANTS",
                ...(sigTimestamp ? { signatureTimestamp: sigTimestamp } : {})
            }
        },
        contentCheckOk: true,
        racyCheckOk: true
    };

    let cookieString = "";
    if (youtubeCookie) {
        cookieString = youtubeCookie;
    } else {
        try {
            const cookies = await browser.cookies.getAll({ domain: ".youtube.com" });
            if (cookies.length > 0) cookieString = cookies.map(c => `${c.name}=${c.value}`).join("; ");
        } catch (e) {}
    }
    if (!cookieString) cookieString = "PREF=hl=en&tz=UTC; SOCS=CAI";

    const apiUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    const headers = [
        { name: "User-Agent", value: WEB_USER_AGENT },
        { name: "Accept", value: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" },
        { name: "Accept-Language", value: "en-us,en;q=0.5" },
        { name: "Content-Type", value: "application/json" },
        { name: "X-Youtube-Client-Name", value: "56" },
        { name: "X-Youtube-Client-Version", value: WEB_CLIENT.clientVersion },
        { name: "Origin", value: "https://www.youtube.com" },
        { name: "X-Origin", value: "https://www.youtube.com" },
        { name: "Accept-Encoding", value: "gzip, deflate" },
        { name: "Connection", value: "keep-alive" },
    ];

    if (visitorId) headers.push({ name: "X-Goog-Visitor-Id", value: visitorId });
    if (cookieString) headers.push({ name: "Cookie", value: cookieString });

    const authHeaders = await buildAuthHeaders();
    headers.push(...authHeaders);

    console.log(`[WEB] Request: visitor=${!!visitorId}, sts=${sigTimestamp}`);

    try {
        const resp = await nativeFetch(apiUrl, headers, {
            method: "POST",
            body: JSON.stringify(payload),
            timeoutMs: 15000
        });

        if (!resp?.html) {
            console.warn("[WEB] Empty response");
            return null;
        }

        const playerResponse = JSON.parse(resp.html);
        const status = playerResponse.playabilityStatus?.status;
        console.log(`[WEB] playabilityStatus: ${status}`);

        if (status !== "OK") {
            console.warn(`[WEB] Rejected: ${playerResponse.playabilityStatus?.reason || status}`);
            return null;
        }

        const streamingData = playerResponse.streamingData;
        const videoDetails = playerResponse.videoDetails;

        const serverAbrStreamingUrl = streamingData?.serverAbrStreamingUrl || null;
        const videoPlaybackUstreamerConfig = playerResponse?.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig || null;
        const adaptiveFormats = streamingData?.adaptiveFormats || [];

        console.log(`[WEB] SABR URL: ${serverAbrStreamingUrl ? serverAbrStreamingUrl.substring(0, 80) + '...' : 'NOT AVAILABLE'}`);
        console.log(`[WEB] Ustreamer config: ${videoPlaybackUstreamerConfig ? videoPlaybackUstreamerConfig.length + ' chars' : 'NOT AVAILABLE'}`);
        console.log(`[WEB] Adaptive formats: ${adaptiveFormats.length}`);

        // Collect format metadata for SABR (may not have direct URLs)
        const sabrFormats = [];
        for (const fmt of adaptiveFormats) {
            const mime = fmt.mimeType || "";
            const codecMatch = mime.match(/codecs="([^"]+)"/);
            const codec = codecMatch ? codecMatch[1].split(",")[0].trim() : "";
            sabrFormats.push({
                itag: fmt.itag,
                lastModified: fmt.lastModified || "0",
                xtags: fmt.xtags || "",
                mimeType: mime,
                codec,
                width: fmt.width || 0,
                height: fmt.height || 0,
                bitrate: fmt.bitrate || 0,
                fps: fmt.fps || 0,
                sampleRate: fmt.audioSampleRate || "0",
                channels: fmt.audioChannels || 0,
                contentLength: fmt.contentLength || "0",
                audioTrackId: fmt.audioTrack?.id || ""
            });
        }

        const resolutions = sabrFormats
            .filter(f => f.mimeType.startsWith("video/"))
            .map(f => `${f.height}p/${f.itag}/${f.codec.substring(0, 4)}`)
            .join(", ");
        console.log(`[WEB] Video formats: ${resolutions}`);

        return {
            title: videoDetails?.title,
            description: videoDetails?.shortDescription,
            duration: parseInt(videoDetails?.lengthSeconds || "0", 10) * 1000,
            isLive: videoDetails?.isLive === true,
            serverAbrStreamingUrl,
            videoPlaybackUstreamerConfig,
            sabrFormats
        };
    } catch (e) {
        console.warn(`[WEB] Fetch error: ${e.message}`);
        return null;
    }
}

// Inline signatureCipher parser — used when cipher.js module isn't loaded.
// Parses: s=SCRAMBLED&sp=sig&url=BASE_URL
function parseSignatureCipherInline(cipherString) {
    if (!cipherString) return null;
    try {
        const params = {};
        for (const part of cipherString.split("&")) {
            const eq = part.indexOf("=");
            if (eq > 0) {
                params[decodeURIComponent(part.substring(0, eq))] = decodeURIComponent(part.substring(eq + 1));
            }
        }
        if (!params.s || !params.url) return null;
        return { s: params.s, sp: params.sp || "sig", url: params.url };
    } catch (e) { return null; }
}

function buildAdaptiveVariants(adaptiveFormats, cipherOps) {
    const videoStreams = [];
    const audioStreams = [];

    // cipherOps.sig is a function(s) that decrypts a scrambled signature
    // parseSignatureCipher/buildCipherUrl from cipher module handle URL assembly
    const hasCipher = cipherOps && typeof cipherOps.sig === 'function';
    if (hasCipher) {
    }

    let cipherResolved = 0;
    let cipherFailed = 0;

    for (const fmt of adaptiveFormats) {
                // Resolve URL: direct url takes priority, then signatureCipher decryption
        let streamUrl = fmt.url || null;

        if (!streamUrl && fmt.signatureCipher && hasCipher) {
            try {
                // Parse signatureCipher: s=SCRAMBLED&sp=sig&url=BASE_URL
                const parsed = parseSignatureCipherInline(fmt.signatureCipher);
                if (parsed) {
                    const decrypted = cipherOps.sig(parsed.s);
                    if (decrypted && decrypted !== parsed.s) {
                        const sep = parsed.url.indexOf("?") !== -1 ? "&" : "?";
                        streamUrl = parsed.url + sep + encodeURIComponent(parsed.sp) + "=" + encodeURIComponent(decrypted);
                        cipherResolved++;

                    } else {
                        cipherFailed++;
                    }
                } else {
                    cipherFailed++;
                }
            } catch (e) {
                cipherFailed++;
            }
        }

        if (!streamUrl) continue;

        const mime = fmt.mimeType || "";
        const codecMatch = mime.match(/codecs="([^"]+)"/);
        const codec = codecMatch ? codecMatch[1].split(",")[0].trim() : "";

        if (mime.startsWith("video/")) {
            // Allow h264 (avc1 — native mp4) and AV1 (av01 — mp4 compatible on modern devices)
            // Skip VP9 — poor MP4 container compatibility, requires MKV/WebM
            const cl = codec.toLowerCase();
            if (!cl.startsWith("avc") && !cl.startsWith("av01")) continue;
            videoStreams.push({
                url: streamUrl,
                itag: fmt.itag,
                width: fmt.width || 0,
                height: fmt.height || 0,
                bitrate: fmt.bitrate || 0,
                fps: fmt.fps || 0,
                codec,
                lastModified: fmt.lastModified || "0",
                xtags: fmt.xtags || ""
            });
        } else if (mime.startsWith("audio/")) {
            // Only AAC (mp4a) — universally safe in MP4 container
            // Skip Opus — requires WebM/MKV
            if (!codec.toLowerCase().startsWith("mp4a")) continue;
            audioStreams.push({
                url: streamUrl,
                itag: fmt.itag,
                bitrate: fmt.bitrate || 0,
                sampleRate: parseInt(fmt.audioSampleRate || "0", 10),
                channels: fmt.audioChannels || 0,
                codec,
                lastModified: fmt.lastModified || "0",
                xtags: fmt.xtags || ""
            });
        }
    }

    if (cipherResolved > 0 || cipherFailed > 0) {
        console.log(`[Cipher] Resolved ${cipherResolved} signatureCipher URLs (${cipherFailed} failed)`);
    }

    if (videoStreams.length === 0 || audioStreams.length === 0) return [];

    // Sort audio by bitrate descending — best quality first
    audioStreams.sort((a, b) => b.bitrate - a.bitrate);
    const bestAudio = audioStreams[0];

    // Sort video: by height desc, then fps desc, then prefer AV1 > H264 at same height
    // (AV1 has better quality/bitrate ratio at 4K)
    videoStreams.sort((a, b) => {
        if (a.height !== b.height) return b.height - a.height;
        if (a.fps !== b.fps) return b.fps - a.fps;
        // AV1 > H264 preference (better compression at high res)
        const isAV1 = (c) => c.toLowerCase().startsWith("av01") ? 1 : 0;
        if (isAV1(a.codec) !== isAV1(b.codec)) return isAV1(b.codec) - isAV1(a.codec);
        return b.bitrate - a.bitrate;
    });

    // Deduplicate by height — one variant per resolution
    const seenHeights = new Set();
    const variants = [];

    for (const video of videoStreams) {
        if (seenHeights.has(video.height)) continue;
        seenHeights.add(video.height);

        variants.push({
            url: video.url,
            audioUrl: bestAudio.url,
            width: video.width,
            height: video.height,
            videoCodec: video.codec,
            audioCodec: bestAudio.codec,
            itag: video.itag,
            audioItag: bestAudio.itag,
            // SABR FormatId fields
            videoLastModified: video.lastModified,
            videoXtags: video.xtags,
            audioLastModified: bestAudio.lastModified,
            audioXtags: bestAudio.xtags
        });
    }

    return variants;
}

// =============================================================================
// MAIN VIDEO PROCESSING
// =============================================================================

// =============================================================================
// NATIVE PORT — bidirectional messaging with Java (OkHttp fetch)
// =============================================================================

let youtubePort = null;
const pendingFetches = new Map(); // requestId -> { resolve, reject, timer }

function connectPort() {
    try {
        youtubePort = browser.runtime.connectNative("youtube");
        youtubePort.onMessage.addListener((msg) => {
            if (msg.type === "fetchResult" && msg.requestId) {
                const pending = pendingFetches.get(msg.requestId);
                if (pending) {
                    clearTimeout(pending.timer);
                    pendingFetches.delete(msg.requestId);
                    pending.resolve(msg);
                }
            }
        });
        youtubePort.onDisconnect.addListener(() => {
            console.warn("[Port] Disconnected (onDisconnect fired)");
            youtubePort = null;
            // Reject all pending fetches
            for (const [id, pending] of pendingFetches) {
                clearTimeout(pending.timer);
                pending.reject(new Error("Port disconnected"));
            }
            pendingFetches.clear();
        });
        console.log("[Port] Connected to native youtube port");
    } catch (e) {
        console.warn("[Port] Failed to connect:", e.message);
        youtubePort = null;
    }
}

function nativeFetch(url, headers, options = {}) {
    const { method = "GET", body = null, timeoutMs = 15000 } = options;
    return new Promise((resolve, reject) => {
        if (!youtubePort) connectPort();
        if (!youtubePort) { reject(new Error("No port")); return; }

        const requestId = `fetch-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        const timer = setTimeout(() => {
            pendingFetches.delete(requestId);
            // Treat a timeout as "the port may be silently broken". After
            // GeckoView session-store faults (`WindowEventDispatcher: win is null`),
            // outbound port.postMessage still succeeds but inbound replies
            // never arrive — onDisconnect doesn't fire either. Force-recycle
            // the port so the NEXT nativeFetch starts on a fresh channel.
            // Reject all in-flight fetches so they don't sit forever on the
            // dead channel; the rejection lets processVideo's catch block run
            // and the EXIT instrumentation actually fires.
            console.warn(`[Port] Fetch timeout for ${url.substring(0, 80)} — recycling port`);
            try {
                if (youtubePort) youtubePort.disconnect();
            } catch (e) {}
            // Reject any other pending fetches (they're using the same dead port)
            for (const [id, pending] of pendingFetches) {
                clearTimeout(pending.timer);
                pending.reject(new Error("Port recycled after timeout"));
            }
            pendingFetches.clear();
            youtubePort = null;
            // Reconnect immediately so the next call has a working port
            connectPort();
            reject(new Error("Fetch timeout"));
        }, timeoutMs);

        pendingFetches.set(requestId, { resolve, reject, timer });

        const msg = { type: "fetch", requestId, url, headers };
        if (method !== "GET") msg.method = method;
        if (body) msg.body = body;
        try {
            console.log(`[Port] postMessage fetch ${requestId} → ${url.substring(0, 80)}`);
            youtubePort.postMessage(msg);
        } catch (e) {
            // postMessage threw synchronously — port is in a bad state
            clearTimeout(timer);
            pendingFetches.delete(requestId);
            console.warn(`[Port] postMessage threw: ${e.message} — recycling`);
            try { if (youtubePort) youtubePort.disconnect(); } catch (_) {}
            youtubePort = null;
            connectPort();
            reject(e);
        }
    });
}

// =============================================================================
// MAIN VIDEO PROCESSING
// =============================================================================

/**
 * Send a message to the native YouTube handler with incognito flag resolved from the tab.
 */
async function sendYouTubeNative(message) {
    if (message.incognito === undefined) {
        const tabId = message.tabId;
        if (tabId >= 0) {
            try {
                const tab = await browser.tabs.get(tabId);
                message.incognito = tab?.incognito || false;
            } catch (e) {
                message.incognito = false;
            }
        } else {
            message.incognito = false;
        }
    }
    return browser.runtime.sendNativeMessage("youtube", message);
}

async function processVideo(details, videoId) {
    // Universal canary gate. Multiple paths trigger processVideo
    // (tabs.onUpdated, webRequest.onHeadersReceived, intercept, embed,
    // content-script messages). Only the tabs.onUpdated path was checking
    // for yt-dlp-wins. The others would happily run the full pipeline for
    // it, including setTimeout-based waits and nativeFetch — all wasted
    // work for a URL YouTube always returns ERROR for.
    //
    // More importantly: every call here costs an entry in the JS event
    // loop's pending-callback queue. Reproductions show that subsequent
    // setTimeout callbacks stop firing after a yt-dlp-wins processVideo
    // is in flight, breaking the extension for real video clicks.
    if (videoId === 'yt-dlp-wins') {
        console.log(`[Process] Skipping yt-dlp-wins canary (videoId always returns ERROR)`);
        return;
    }

    const _t0 = Date.now();
    console.log(`[Process] Starting for ${videoId}`);
    let _stage = 'init';
    let _exitMode = 'unknown';
    try {
        const videoUrl = `https://www.youtube.com/watch?v=${videoId}`;
        // MWEB URL for HTML fetch — must match the browser's context (m.youtube.com)
        // to get a serverAbrStreamingUrl with c=MWEB that works with mobile origin/headers.
        // Desktop www.youtube.com gives c=WEB URLs which reject MWEB SABR requests.
        const mwebVideoUrl = `https://m.youtube.com/watch?v=${videoId}`;

        // Cookie wait removed. The youtubeCookie global is populated at extension
        // startup by the browser-state capture path; if it's missing, content.js
        // hasn't run yet on any tab, which means there's nothing to wait for.
        // Previously this used setTimeout(300ms) as a buffer, but setTimeout
        // becomes unreliable in this WebExtension after GeckoView session-store
        // faults (eventDispatcher = undefined breaks the macrotask scheduler).
        // The HTML fetch path will fall back to minimal cookies if youtubeCookie
        // is empty, so a missing cookie at this point isn't fatal.
        _stage = 'cookie-check';

        // =====================================================================
        // Strategy 1: Use intercepted /player API response (SPA navigations)
        // The filterResponseData interceptor captures the browser's own API
        // call — fully authenticated, zero extra network calls.
        // =====================================================================

        let playerResponse = null;
        let streamSource = null;

        // Check if interceptor already captured a response
        let intercepted = interceptedResponses.get(videoId);
        if (intercepted && Date.now() - intercepted.timestamp < INTERCEPT_TTL) {
            playerResponse = intercepted.playerResponse;
            interceptedResponses.delete(videoId);
            streamSource = "intercepted";
            console.log(`[Process] Using intercepted API response for ${videoId}`);
        }

        // Intercept-wait removed. In practice, every observed processVideo flow
        // uses streamSource="html" — the player API XHR interceptor never fires
        // for MWEB because YouTube embeds playerResponse in the page HTML, not
        // in a separate XHR. The 800ms setTimeout wait was dead time that also
        // depended on setTimeout's macrotask scheduler — which is the FIRST
        // thing to break when GeckoView's eventDispatcher faults after a
        // PoToken-tab-destruction race. By skipping the wait entirely, we go
        // straight to HTML fetch and avoid the setTimeout dependency.
        //
        // The intercept fast-path is still checked SYNCHRONOUSLY above for the
        // case where the XHR did arrive before processVideo ran. We just don't
        // wait for one that hasn't arrived yet.
        _stage = 'intercept-skipped';

        // =====================================================================
        // Strategy 2: Extract from page HTML (first page load)
        // First navigation to youtube.com has ytInitialPlayerResponse embedded
        // in the HTML — no XHR call, so the interceptor doesn't fire.
        // Also needed for visitor data extraction.
        // =====================================================================

        let html = null;

        // For intercepted responses, we still need the HTML for n-param solving.
        // Without this, the SABR URL goes out throttled and YouTube returns 403.
        if (playerResponse && streamSource?.startsWith("intercepted")) {
            try {
                const mwebUrl = `https://m.youtube.com/watch?v=${videoId}`;
                const cookieStr = youtubeCookie || "PREF=hl=en&tz=UTC; SOCS=CAI";
                _stage = 'html-for-nsolve';
                const nativeResp = await nativeFetch(mwebUrl, [
                    { name: "User-Agent", value: "Mozilla/5.0 (Android 16; Mobile; rv:149.0) Gecko/149.0 Firefox/149.0" },
                    { name: "Accept-Language", value: "en-US,en;q=0.5" },
                    { name: "Cookie", value: cookieStr }
                ]);
                if (nativeResp?.html && nativeResp.html.length > 10000) {
                    html = nativeResp.html;
                    console.log(`[Process] Fetched HTML for n-param solving (${videoId})`);
                }
            } catch (e) {
                console.warn(`[Process] HTML fetch for n-param failed: ${e.message}`);
            }
        }

        if (!playerResponse) {
            _stage = 'cookies-collect';
            let browserCookies = "";
            if (youtubeCookie) {
                browserCookies = youtubeCookie;
            } else {
                // Time-bound the cookies API. browser.cookies.getAll can hang
                // indefinitely after the GeckoView session-store fault — and
                // since this runs before any nativeFetch, a hang here means
                // [Port] postMessage is never reached.
                try {
                    const cookies = await Promise.race([
                        browser.cookies.getAll({ domain: ".youtube.com" }),
                        new Promise((_, rej) => setTimeout(() => rej(new Error("cookies.getAll timeout")), 1500))
                    ]);
                    if (cookies && cookies.length > 0) browserCookies = cookies.map(c => `${c.name}=${c.value}`).join("; ");
                } catch (e) {
                    console.warn(`[Process] cookies.getAll failed/timed out: ${e.message}`);
                }
            }
            _stage = 'cookies-done';

            const minimalCookies = "PREF=hl=en&tz=UTC; SOCS=CAI";

            function buildHeaders(cookieString) {
                return [
                    // Use mobile Firefox UA to get MWEB response with c=MWEB streaming URL
                    { name: "User-Agent", value: "Mozilla/5.0 (Android 16; Mobile; rv:149.0) Gecko/149.0 Firefox/149.0" },
                    { name: "Accept", value: PAGE_FETCH_HEADERS["Accept"] },
                    { name: "Accept-Language", value: PAGE_FETCH_HEADERS["Accept-Language"] },
                    { name: "Sec-Fetch-Mode", value: PAGE_FETCH_HEADERS["Sec-Fetch-Mode"] },
                    { name: "Accept-Encoding", value: PAGE_FETCH_HEADERS["Accept-Encoding"] },
                    { name: "Connection", value: PAGE_FETCH_HEADERS["Connection"] },
                    { name: "Cookie", value: cookieString }
                ];
            }

            const cookieStrategies = browserCookies
                ? [browserCookies, minimalCookies]
                : [minimalCookies];

            for (const cookieString of cookieStrategies) {
                try {
                    // Fetch m.youtube.com to get MWEB playerResponse with c=MWEB streaming URL.
                    // Tightened timeout from default 15s → 8s. The native fetch uses OkHttp
                    // directly (independent of GeckoView tabs/sessions), so a stuck reply
                    // means the network or YouTube itself is slow. 8s is plenty for any
                    // working response and lets the queue recover faster when YouTube is
                    // throttling the device or DNS is misbehaving.
                    _stage = 'html-fetch';
                    const nativeResp = await nativeFetch(mwebVideoUrl, buildHeaders(cookieString), { timeoutMs: 8000 });
                    if (!nativeResp?.html || nativeResp.html.length < 10000) continue;

                    const prMatch = nativeResp.html.match(/ytInitialPlayerResponse\s*=\s*({.+?});/s);
                    if (!prMatch) continue;

                    const pr = JSON.parse(prMatch[1]);
                    const status = pr.playabilityStatus?.status;

                    if (status === "OK") {
                        html = nativeResp.html;
                        playerResponse = pr;
                        streamSource = "html";
                        console.log(`[Process] Extracted playerResponse from HTML for ${videoId}`);
                        break;
                    }
                    console.log(`[Process] HTML status: ${status} with ${cookieString === browserCookies ? 'browser' : 'minimal'} cookies`);
                } catch (e) {
                    console.log(`[Process] HTML fetch failed: ${e.message}`);
                }
            }

            // JS fetch fallback for HTML
            if (!playerResponse) {
                try {
                    const response = await fetch(mwebVideoUrl, { headers: {
                        ...PAGE_FETCH_HEADERS,
                        "User-Agent": "Mozilla/5.0 (Android 16; Mobile; rv:149.0) Gecko/149.0 Firefox/149.0",
                        "Cookie": browserCookies || minimalCookies
                    } });
                    if (response.ok) {
                        const text = await response.text();
                        const prMatch = text.match(/ytInitialPlayerResponse\s*=\s*({.+?});/s);
                        if (prMatch) {
                            const pr = JSON.parse(prMatch[1]);
                            if (pr.playabilityStatus?.status === "OK") {
                                html = text;
                                playerResponse = pr;
                                streamSource = "html-jsfetch";
                            }
                        }
                    }
                } catch (e) {}
            }
        }

        // Extract visitor data from HTML (always useful for fallback auth)
        if (html) {
            const vdMatch = html.match(/["']VISITOR_DATA["']\s*:\s*["']([^"']+)["']/) || html.match(/"visitorData"\s*:\s*"([^"]+)"/);
            if (vdMatch) { cachedVisitorData = vdMatch[1]; }
        }
        // Also from playerResponse
        if (playerResponse?.responseContext?.visitorData) {
            cachedVisitorData = playerResponse.responseContext.visitorData;
        }

        let videoTitle = playerResponse?.videoDetails?.title || "Unknown";
        let videoDescription = playerResponse?.videoDetails?.shortDescription || "";
        const thumbnails = playerResponse?.videoDetails?.thumbnail?.thumbnails;
        const thumbnailUrl = thumbnails?.length ? thumbnails[thumbnails.length - 1].url : "";

        // =====================================================================
        // Process the player response (intercepted or HTML)
        // Build variants + SABR data from whatever we got
        // =====================================================================

        if (playerResponse?.streamingData) {
            const streamingData = playerResponse.streamingData;
            const adaptiveFormats = streamingData.adaptiveFormats || [];
            const formats = streamingData.formats || [];
            const hlsManifestUrl = streamingData.hlsManifestUrl || null;

            const urlCount = adaptiveFormats.filter(f => f.url).length;
            const cipherCount = adaptiveFormats.filter(f => f.signatureCipher).length;
            console.log(`[Process] streamingData [${streamSource}]: ${adaptiveFormats.length} adaptive (${urlCount} url, ${cipherCount} cipher), SABR=${!!streamingData.serverAbrStreamingUrl}`);

            // Detect live stream
            const isLive = playerResponse.videoDetails?.isLive === true;

            if (isLive && hlsManifestUrl) {
                // Live → send HLS
                //
                // HLS manifest URLs contain an n-param (the throttling token)
                // embedded as /n/VALUE/ in the path. googlevideo chunk hosts
                // (rr*.googlevideo.com) reject segment fetches with a raw,
                // untransformed n by returning 403 Forbidden with empty body.
                // The manifest host (manifest.googlevideo.com) is lenient on
                // the manifest request itself but bakes our n into the segment
                // URLs it generates in the m3u8 response.
                //
                // Strategy: solve the n on the top-level manifest URL before
                // handing it to the native side. If googlevideo's manifest
                // server propagates the transformed n into its segment URLs
                // (likely, since it signs URLs for whatever n was requested),
                // every segment fetch will also have the transformed n.
                let finalManifestUrl = hlsManifestUrl;
                try {
                    if (html) {
                        const playerInfo = await fetchAndCachePlayer(html);
                        if (playerInfo) {
                            const solvers = await getOrCreateSolvers(playerInfo.source, playerInfo.version);
                            if (solvers?.n) {
                                const transformed = transformUrl(hlsManifestUrl, solvers);
                                if (transformed !== hlsManifestUrl) {
                                    finalManifestUrl = transformed;
                                    console.log(`[Process] HLS manifest n-param transformed for live ${videoId}`);
                                } else {
                                    console.warn(`[Process] HLS manifest had no n-param or transform failed for ${videoId}`);
                                }
                            } else {
                                console.warn(`[Process] No n-solver available for live ${videoId}`);
                            }
                        }
                    }
                } catch (e) {
                    console.warn(`[Process] HLS n-transform error: ${e.message}`);
                }

                const streamHeaders = getBrowserHeaders();
                const duration = parseInt(playerResponse.videoDetails?.lengthSeconds || "0", 10) * 1000;
                const message = {
                    type: "media", url: finalManifestUrl, origin: videoUrl,
                    name: videoTitle, description: videoDescription, img: thumbnailUrl,
                    duration, headers: streamHeaders,
                    tabId: details._resolvedTabId ?? details.tabId,
                    request: details.requestId
                };
                await sendYouTubeNative(message);
                console.log(`[Process] Sent HLS to native: ${videoId} [${streamSource}]`);
                return;
            }

            // Build SABR data
            let sabrData = null;
            const sabrUrl = streamingData.serverAbrStreamingUrl || null;
            const sabrConfig = streamingData.videoPlaybackUstreamerConfig
                || playerResponse?.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig
                || null;

            if (sabrUrl && sabrConfig) {
                const sabrFormats = [];
                for (const fmt of adaptiveFormats) {
                    const mime = fmt.mimeType || "";
                    const codecMatch = mime.match(/codecs="([^"]+)"/);
                    const codec = codecMatch ? codecMatch[1].split(",")[0].trim() : "";
                    sabrFormats.push({
                        itag: fmt.itag,
                        lastModified: fmt.lastModified || "0",
                        xtags: fmt.xtags || "",
                        mimeType: mime, codec,
                        width: fmt.width || 0, height: fmt.height || 0,
                        bitrate: fmt.bitrate || 0, fps: fmt.fps || 0,
                        sampleRate: fmt.audioSampleRate || "0",
                        channels: fmt.audioChannels || 0,
                        contentLength: fmt.contentLength || "0",
                        audioTrackId: fmt.audioTrack?.id || ""
                    });
                }
                sabrData = {
                    serverAbrStreamingUrl: sabrUrl,
                    videoPlaybackUstreamerConfig: sabrConfig,
                    formats: sabrFormats,
                    visitorData: cachedVisitorData || null,
                    clientVersion: null
                };
                // Extract client version for CDN cver= matching
                // Tier 1: HTML page (MWEB / direct fetch path)
                if (html) {
                    const cvMatch = html.match(/["']INNERTUBE_CLIENT_VERSION["']\s*:\s*["']([^"']+)["']/)
                        || html.match(/"clientVersion"\s*:\s*"([^"]+)"/);
                    if (cvMatch) {
                        sabrData.clientVersion = cvMatch[1];
                    }
                }
                // Tier 2: intercepted playerResponse metadata (SPA / XHR path)
                if (!sabrData.clientVersion && playerResponse?.responseContext?.serviceTrackingParams) {
                    for (const stp of playerResponse.responseContext.serviceTrackingParams) {
                        const cvParam = stp.params?.find(p => p.key === "cver");
                        if (cvParam?.value) {
                            sabrData.clientVersion = cvParam.value;
                            break;
                        }
                    }
                }
                // Tier 3: hardcoded WEB_CLIENT constant (safety net)
                if (!sabrData.clientVersion) {
                    sabrData.clientVersion = WEB_CLIENT.clientVersion;
                }
                console.log(`[Process] SABR data ready: ${sabrFormats.length} formats (cver=${sabrData.clientVersion})`);
            }

            // Try building variants with direct URLs
            let variants = null;
            let cipherOps = null;

            // For intercepted/HTML responses, formats may have signatureCipher
            // Try solving cipher if we have cipher ops available
            if (cipherCount > 0 && html) {
                try {
                    const playerInfo = await fetchAndCachePlayer(html);
                    if (playerInfo) {
                        cipherOps = await getOrCreateCipherOps(playerInfo.version);
                    }
                } catch (e) {}
            }

            if (urlCount > 0 || cipherCount > 0) {
                variants = buildAdaptiveVariants(adaptiveFormats, cipherOps);

                // Uncap n-parameter if we have solver
                if (variants.length > 0 && html) {
                    try {
                        const playerInfo = await fetchAndCachePlayer(html);
                        if (playerInfo) {
                            const solvers = await getOrCreateSolvers(playerInfo.source, playerInfo.version);
                            if (solvers?.n) {
                                for (const v of variants) {
                                    try {
                                        if (v.url) v.url = transformUrl(v.url, solvers);
                                        if (v.audioUrl) v.audioUrl = transformUrl(v.audioUrl, solvers);
                                    } catch (e) {}
                                }
                                console.log(`[Process] Uncapped ${variants.length} variant URLs`);
                            }
                            // Also solve n-param in SABR URL
                            if (sabrData && solvers?.n) {
                                try {
                                    sabrData.serverAbrStreamingUrl = transformUrl(sabrData.serverAbrStreamingUrl, solvers);
                                    console.log(`[Process] SABR URL n-param solved`);
                                } catch (e) {
                                    console.warn(`[Process] Failed to solve SABR URL n-param: ${e.message}`);
                                }
                            }
                        }
                    } catch (e) {}
                }
            }

            // Send variants + SABR data
            if (variants && variants.length > 0) {
                // Generate PO token for SABR downloads — attestation wall can hit
                // mid-stream (StreamProtectionStatus status=3) and kill the download
                // after ~3 segments if no poToken is present in the protobuf.
                if (sabrData) {
                    const vd = cachedVisitorData || sabrData.visitorData;
                    if (vd) {
                        try {
                            const t0 = Date.now();
                            console.log(`[Process] Generating PO token for ${videoId}...`);
                            const poTokenStr = await generatePoToken(vd, videoId);
                            if (poTokenStr) {
                                sabrData.poToken = poTokenStr;
                                console.log(`[Process] PO token: ${poTokenStr.length} chars (${Date.now() - t0}ms)`);
                            }
                        } catch (e) {
                            console.warn(`[Process] PO token failed: ${e.message} (SABR may hit attestation wall)`);
                        }
                    }
                }

                const streamHeaders = getBrowserHeaders();
                const duration = parseInt(playerResponse.videoDetails?.lengthSeconds || "0", 10) * 1000;
                const message = {
                    type: "variants", url: variants[0].url || videoUrl, origin: videoUrl,
                    name: videoTitle, description: videoDescription, img: thumbnailUrl,
                    duration, headers: streamHeaders,
                    tabId: details._resolvedTabId ?? details.tabId,
                    request: details.requestId,
                    variants, sabr: sabrData
                };
                await sendYouTubeNative(message);
                console.log(`[Process] Sent ${variants.length} variant(s) to native: ${videoId} [${streamSource}]${sabrData ? ' (SABR' + (sabrData.poToken ? '+POT' : '') + ')' : ''}`);
                _exitMode = 'sent-variants';
                return;
            }

            // SABR-only (no direct URLs — MWEB response has SABR data but formats lack URLs)
            if (sabrData && urlCount === 0) {
                // N-param transform is REQUIRED for SABR URLs.
                if (html) {
                    try {
                        const playerInfo = await fetchAndCachePlayer(html);
                        if (playerInfo) {
                            const solvers = await getOrCreateSolvers(playerInfo.source, playerInfo.version);
                            if (solvers?.n) {
                                const oldUrl = sabrData.serverAbrStreamingUrl;
                                sabrData.serverAbrStreamingUrl = transformUrl(sabrData.serverAbrStreamingUrl, solvers);
                                if (sabrData.serverAbrStreamingUrl !== oldUrl) {
                                    console.log(`[Process] SABR URL n-param solved`);
                                } else {
                                    console.warn(`[Process] SABR URL n-param unchanged after transform!`);
                                }
                            } else {
                                console.warn(`[Process] No n-param solver available - SABR will likely fail with 403`);
                            }
                        }
                    } catch (e) {
                        console.warn(`[Process] Failed to solve SABR URL n-param: ${e.message}`);
                    }
                }

                // Generate PO token
                const vd = cachedVisitorData || sabrData.visitorData;
                if (vd) {
                    try {
                        const t0 = Date.now();
                        console.log(`[Process] Generating PO token for ${videoId}...`);
                        const poTokenStr = await generatePoToken(vd, videoId);
                        if (poTokenStr) {
                            sabrData.poToken = poTokenStr;
                            console.log(`[Process] PO token: ${poTokenStr.length} chars (${Date.now() - t0}ms)`);
                        }
                    } catch (e) {
                        console.warn(`[Process] PO token failed: ${e.message}`);
                    }
                }

                const sabrVariants = buildSabrOnlyVariants(sabrData);
                if (sabrVariants.length > 0) {
                    const streamHeaders = getBrowserHeaders();
                    const duration = parseInt(playerResponse.videoDetails?.lengthSeconds || "0", 10) * 1000;
                    const message = {
                        type: "variants", url: videoUrl, origin: videoUrl,
                        name: videoTitle, description: videoDescription, img: thumbnailUrl,
                        duration, headers: streamHeaders,
                        tabId: details._resolvedTabId ?? details.tabId,
                        request: details.requestId,
                        variants: sabrVariants, sabr: sabrData
                    };
                    await sendYouTubeNative(message);
                    console.log(`[Process] Sent ${sabrVariants.length} SABR variant(s) to native: ${videoId} [${streamSource}] (SABR-only${sabrData.poToken ? '+POT' : ''})`);
                    _exitMode = 'sent-sabr-only';
                    return;
                }

                if (!sabrData.poToken) {
                    console.log(`[Process] No PO token and no variants for ${videoId} — giving up`);
                }
            }

            // itag 18 fallback from current response
            const itag18 = formats.find(f => f.itag === 18 && f.url);
            if (itag18) {
                const streamHeaders = getBrowserHeaders();
                const duration = parseInt(playerResponse.videoDetails?.lengthSeconds || "0", 10) * 1000;
                const message = {
                    type: "media", url: itag18.url, origin: videoUrl,
                    name: videoTitle, description: videoDescription, img: thumbnailUrl,
                    duration, headers: streamHeaders,
                    tabId: details._resolvedTabId ?? details.tabId,
                    request: details.requestId
                };
                await sendYouTubeNative(message);
                console.log(`[Process] Sent itag 18 to native: ${videoId} [${streamSource}]`);
                return;
            }
        }

        // =====================================================================
        // End of extraction strategies.
        //
        // Previously this fell through to an ANDROID_VR innertube fallback,
        // but YouTube has tightened that client: in practice it returns
        // playabilityStatus=ERROR for every video the web client couldn't
        // already extract (including age-gated, region-locked, and private
        // videos). Removing it eliminates ~200ms of useless latency and the
        // confusing `[ANDROID_VR] Rejected` errors in logs.
        //
        // If HTML extraction yielded nothing usable, we're done. The native
        // downloader will surface the failure to the user.
        // =====================================================================

        console.error(`[Process] No streams available from any source for ${videoId}`);
        _exitMode = 'no-streams';

    } catch (e) {
        _exitMode = 'error';
        console.error(`[Process] Error for ${videoId} (stage=${_stage}):`, e);
    } finally {
        if (_exitMode === 'unknown') _exitMode = 'returned';
        const _dt = Date.now() - _t0;
        // Always emit one line per processVideo call so silent hangs/returns
        // are visible. If the function ran to a normal "Sent N variant(s)"
        // success, _exitMode stays 'returned' but the dt is small. If a
        // nativeFetch hung and never resolved, _dt will be huge or this line
        // simply never appears (which is itself a strong signal).
        console.log(`[Process] EXIT ${videoId} stage=${_stage} mode=${_exitMode} dt=${_dt}ms`);
    }
}

/**
 * Build variants from SABR format metadata when no direct URLs are available.
 * Used when the intercepted web response has SABR data but formats lack URLs.
 */
function buildSabrOnlyVariants(sabrData) {
    const audioFormats = sabrData.formats.filter(f =>
        f.mimeType.startsWith("audio/") && f.codec.toLowerCase().startsWith("mp4a"));
    const videoFormats = sabrData.formats.filter(f =>
        f.mimeType.startsWith("video/") && (f.codec.toLowerCase().startsWith("avc") || f.codec.toLowerCase().startsWith("av01")));

    if (audioFormats.length === 0 || videoFormats.length === 0) return [];

    audioFormats.sort((a, b) => b.bitrate - a.bitrate);
    const bestAudio = audioFormats[0];

    videoFormats.sort((a, b) => b.height - a.height || b.bitrate - a.bitrate);
    const seenH = new Set();
    const variants = [];

    for (const vf of videoFormats) {
        if (seenH.has(vf.height)) continue;
        seenH.add(vf.height);
        variants.push({
            url: "", audioUrl: "",
            width: vf.width, height: vf.height,
            videoCodec: vf.codec, audioCodec: bestAudio.codec,
            itag: vf.itag, audioItag: bestAudio.itag,
            videoLastModified: vf.lastModified, videoXtags: vf.xtags,
            audioLastModified: bestAudio.lastModified, audioXtags: bestAudio.xtags
        });
    }
    return variants;
}

// =============================================================================
// PLAYER API RESPONSE INTERCEPTOR
// Intercepts the browser's own /youtubei/v1/player XHR responses using
// filterResponseData. The browser handles all auth — we just read the data.
// This gives us SABR URL + ustreamer config + format metadata for free.
//
// Three scenarios:
// 1. SPA navigation (most common): browser fires XHR to /player → we capture it
// 2. First page load: playerResponse is embedded in HTML (ytInitialPlayerResponse)
//    → no XHR, interceptor doesn't fire, processVideo falls back to HTML extraction
// 3. Embedded iframe: /embed/ID loads its own /player XHR → we capture it
// =============================================================================

browser.webRequest.onBeforeRequest.addListener(
    (details) => {
        if (details.method !== "POST") return;

        const filter = browser.webRequest.filterResponseData(details.requestId);
        const chunks = [];

        filter.ondata = event => {
            chunks.push(new Uint8Array(event.data));
            filter.write(event.data); // pass through unmodified to browser
        };

        filter.onstop = () => {
            filter.close();
            try {
                const totalLength = chunks.reduce((sum, c) => sum + c.length, 0);
                const combined = new Uint8Array(totalLength);
                let offset = 0;
                for (const chunk of chunks) {
                    combined.set(chunk, offset);
                    offset += chunk.length;
                }

                const responseText = new TextDecoder().decode(combined);
                const playerResponse = JSON.parse(responseText);

                if (playerResponse?.playabilityStatus?.status !== "OK") return;

                const videoId = playerResponse?.videoDetails?.videoId;
                if (!videoId) return;

                const streamingData = playerResponse.streamingData;
                if (!streamingData) return;

                const adaptiveCount = streamingData.adaptiveFormats?.length || 0;
                const hasSabr = !!streamingData.serverAbrStreamingUrl;
                const hasUstreamer = !!(streamingData.videoPlaybackUstreamerConfig
                    || playerResponse?.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig);

                console.log(`[Intercept] Captured player response for ${videoId}`
                    + ` (${adaptiveCount} formats, SABR=${hasSabr}, ustreamer=${hasUstreamer})`);

                // Store for processVideo to pick up
                interceptedResponses.set(videoId, {
                    playerResponse,
                    timestamp: Date.now()
                });

                // Clean old entries
                for (const [id, entry] of interceptedResponses) {
                    if (Date.now() - entry.timestamp > INTERCEPT_TTL) {
                        interceptedResponses.delete(id);
                    }
                }

                // Trigger processing if tabs.onUpdated already fired for this video
                // (SPA navigations: tab URL changes → processVideo starts → waits for intercept)
                // If processVideo hasn't been triggered yet, tabs.onUpdated will handle it
                const tabId = details.tabId;
                if (tabId >= 0) {
                    setTimeout(() => {
                        if (trackVideo(tabId, videoId)) {
                            console.log(`[Intercept] Triggering processVideo for ${videoId}`);
                            processVideo({
                                url: `https://www.youtube.com/watch?v=${videoId}`,
                                tabId,
                                _resolvedTabId: tabId,
                                requestId: `intercept-${tabId}-${Date.now()}`
                            }, videoId);
                        }
                    }, 100);
                }
            } catch (e) {
                // Not a player response or parse error — ignore
            }
        };

        filter.onerror = () => {
            try { filter.close(); } catch (e) {}
        };
    },
    {
        urls: [
            "*://*.youtube.com/youtubei/v1/player*",
            "*://youtubei.googleapis.com/youtubei/v1/player*"
        ],
        types: ["xmlhttprequest"]
    },
    ["blocking", "requestBody"]
);

// =============================================================================
// LISTENERS
// =============================================================================

const urlToTabCache = new Map();
const URL_TAB_CACHE_TTL = 30 * 1000;

browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.url || changeInfo.status === "complete") {
        const url = tab.url;
        if (url) {
            urlToTabCache.set(url, { tabId, timestamp: Date.now() });
            try { const u = new URL(url); urlToTabCache.set(u.origin + u.pathname, { tabId, timestamp: Date.now() }); } catch {}
        }
    }
    // Trigger on URL change (SPA navigation) or page complete (reload/fresh load)
    const triggerUrl = changeInfo.url || (changeInfo.status === "complete" ? tab.url : null);
    if (triggerUrl) {
        const videoId = extractVideoId(triggerUrl);
        // Skip the "yt-dlp-wins" canary probe: the native FireDown app navigates
        // tab 10001 to https://m.youtube.com/watch?v=yt-dlp-wins as a health
        // check. It's exactly 11 alphanumeric chars so the video ID regex
        // matches it, but YouTube always returns ERROR for this ID. No point
        // running the full extraction pipeline just to fail at every stage.
        if (videoId && videoId !== 'yt-dlp-wins' && trackVideo(tabId, videoId)) {
            processVideo({ url: triggerUrl, tabId, _resolvedTabId: tabId, requestId: `tab-${tabId}-${Date.now()}` }, videoId);
        }
    }
});

browser.tabs.onRemoved.addListener((tabId) => {
    clearTabFromCache(tabId);
    for (const [url, entry] of urlToTabCache) { if (entry.tabId === tabId) urlToTabCache.delete(url); }
    // Clean up PO token tab reference if closed externally or by GeckoView
    if (tabId === poTokenTabId) {
        poTokenTabId = null;
        // If we were waiting for this tab's content script to signal ready,
        // resolve immediately so ensurePoTokenTab detects the tab is gone
        // instead of waiting for the full init timeout.
        if (poTokenReadyResolve) poTokenReadyResolve();
        // Reject any pending PO token requests — the page context is dead,
        // so the result callback will never fire. Without this, requests
        // hang for the full 20s timeout. This happens when GeckoView tears
        // down the tab window after sendMessage succeeded but before the
        // BotGuard VM finished minting.
        for (const [rid, entry] of pendingPoTokenRequests) {
            clearTimeout(entry.timer);
            entry.reject(new Error('PO token tab died during mint'));
        }
        pendingPoTokenRequests.clear();
    }
});

setInterval(() => {
    const now = Date.now();
    for (const [url, entry] of urlToTabCache) { if (now - entry.timestamp > URL_TAB_CACHE_TTL) urlToTabCache.delete(url); }
}, URL_TAB_CACHE_TTL);

async function resolveTabId(details) {
    if (details.tabId >= 0) return details.tabId;
    if (details._resolvedTabId >= 0) return details._resolvedTabId;

    // 1. Check URL-to-tab cache (populated by onUpdated)
    const urlsToCheck = [details.originUrl, details.url, details.documentUrl].filter(Boolean);
    for (const url of urlsToCheck) {
        const cached = urlToTabCache.get(url);
        if (cached && Date.now() - cached.timestamp < URL_TAB_CACHE_TTL) { details._resolvedTabId = cached.tabId; return cached.tabId; }
        try { const base = new URL(url); const bc = urlToTabCache.get(base.origin + base.pathname);
            if (bc && Date.now() - bc.timestamp < URL_TAB_CACHE_TTL) { details._resolvedTabId = bc.tabId; return bc.tabId; }
        } catch {}
    }

    try {
        // 2. For embeds: match the parent page URL (originUrl/documentUrl) against
        //    all open tabs. This avoids the active-tab race condition when the user
        //    switches tabs between the iframe request and the async query.
        const parentUrls = [details.originUrl, details.documentUrl].filter(Boolean);
        if (parentUrls.length > 0) {
            const allTabs = await browser.tabs.query({ currentWindow: true });
            for (const pUrl of parentUrls) {
                try {
                    const pOrigin = new URL(pUrl).origin;
                    const match = allTabs.find(t => t.url && t.url.startsWith(pOrigin));
                    if (match) { details._resolvedTabId = match.id; return match.id; }
                } catch {}
            }
        }

        // 3. Fall back to active tab
        const active = await browser.tabs.query({ active: true, currentWindow: true });
        if (active.length > 0) { details._resolvedTabId = active[0].id; return active[0].id; }

        // 4. Last resort: find any YouTube tab
        const tabs = await browser.tabs.query({ url: "*://*.youtube.com/*" });
        if (tabs.length > 0) { const t = tabs[0]; details._resolvedTabId = t.id; return t.id; }
    } catch (e) {}
    return -1;
}

browser.webRequest.onHeadersReceived.addListener(
    async (details) => {
        const videoId = extractVideoId(details.url);
        if (!videoId) return;
        const tabId = await resolveTabId(details);
        if (trackVideo(tabId, videoId)) { details._resolvedTabId = tabId; processVideo(details, videoId); }
    },
    { urls: [
        "*://*.youtube.com/watch*",
        "*://*.youtube.com/embed/*",
        "*://*.youtube.com/shorts/*",
        "*://*.youtube.com/v/*",
        "*://*.youtube.com/e/*",
        "*://*.youtube.com/live/*",
        "*://*.youtu.be/*",
        "*://youtubei.googleapis.com/*",
        "*://*.youtube-nocookie.com/embed/*",
        "*://*.youtube-nocookie.com/v/*",
        "*://*.youtube-nocookie.com/e/*"
    ], types: ["main_frame", "sub_frame", "xmlhttprequest"] }
);

function captureBrowserState(details) {
    const headers = details.requestHeaders;
    if (!headers) return;

    // Capture cookies (for SAPISID auth)
    const cookieHeader = headers.find(h => h.name.toLowerCase() === "cookie");
    if (cookieHeader?.value) {
        const newCookie = cookieHeader.value.trim();
        if (!youtubeCookie) {
            console.log(`[Auth] Cookie captured (${newCookie.length} chars)`);
        }
        youtubeCookie = newCookie;
        const sapisidMatch = newCookie.match(/(?:^|;\s*)SAPISID=([^;]+)/);
        if (sapisidMatch) cachedSapiSid = sapisidMatch[1];
    }

    // Capture browser headers for SABR CDN requests.
    // Only from XHR (xmlhttprequest) to get the headers the YouTube player uses,
    // not navigation headers. Skip Cookie/Host/Content-* — managed separately.
    if (details.type === "xmlhttprequest") {
        const SKIP = new Set(["cookie", "host", "content-type", "content-length",
            "accept-encoding", "connection", "te", "pragma", "cache-control"]);
        const filtered = headers
            .filter(h => !SKIP.has(h.name.toLowerCase()))
            .map(h => ({ name: h.name, value: h.value }));
        if (filtered.length > 0) {
            capturedBrowserHeaders = filtered;
        }
    }
}
browser.webRequest.onBeforeSendHeaders.addListener(captureBrowserState, { urls: ["*://*.youtube.com/*"] }, ["requestHeaders"]);

// =============================================================================
// EMBED IFRAME DETECTION
// webNavigation.onCompleted fires reliably for sub_frame navigations in
// GeckoView, unlike webRequest.onHeadersReceived which may be skipped for
// cross-origin iframes when the parent page is not in host_permissions.
// =============================================================================

browser.webNavigation.onCompleted.addListener(
    (details) => {
        // Only care about iframes (frameId > 0), top-level is handled by tabs.onUpdated
        if (details.frameId === 0) return;
        const videoId = extractVideoId(details.url);
        if (!videoId) return;
        const tabId = details.tabId;
        console.log(`[Embed] webNavigation.onCompleted: video ${videoId} in iframe (tab ${tabId}, frame ${details.frameId})`);
        if (trackVideo(tabId, videoId)) {
            processVideo({ url: details.url, tabId, _resolvedTabId: tabId, requestId: `embed-${tabId}-${Date.now()}` }, videoId);
        }
    },
    { url: [
        { hostContains: "youtube.com" },
        { hostContains: "youtube-nocookie.com" },
        { hostContains: "youtu.be" }
    ] }
);

// Content-script fallback: if content.js detects it's inside an embed iframe,
// it can send { type: "embedVideo", url: location.href } to trigger processing.
browser.runtime.onMessage.addListener((msg, sender) => {
    if (msg?.type === "embedVideo" && msg.url) {
        const videoId = extractVideoId(msg.url);
        if (!videoId) return;
        const tabId = sender.tab?.id ?? -1;
        console.log(`[Embed] Content script reported embed: ${videoId} (tab ${tabId})`);
        if (trackVideo(tabId, videoId)) {
            processVideo({ url: msg.url, tabId, _resolvedTabId: tabId, requestId: `cs-embed-${tabId}-${Date.now()}` }, videoId);
        }
    }
});

// =============================================================================
// PO TOKEN GENERATION
// BotGuard runs in a hidden youtube.com/robots.txt tab (no CSP).
// Content script injects bgutils + BotGuard runner at document_start,
// then sends a 'poTokenTabReady' message back to signal readiness.
// We send generatePoToken messages to that tab and receive results back.
// Minter is cached ~5h in the page context — subsequent mints are instant.
//
// Tab lifecycle: created lazily on first generatePoToken() call and KEPT
// ALIVE for the duration of the extension session. Destroying the tab
// after each mint races with GeckoView's session-store update logic and
// leaves the eventDispatcher in a faulted state, which silently breaks
// setTimeout in this WebExtension. See the long comment in generatePoToken
// for the full root-cause explanation.
//
// The tab is only re-created if:
//   - ensurePoTokenTab's ping fails (content script unresponsive)
//   - GeckoView destroys the tab on its own (onRemoved fires)
//   - The extension reloads
//
// GeckoView on Android can tear down background tab windows aggressively.
// To handle this, ensurePoTokenTab waits for a ready signal from content.js
// rather than a blind sleep — if the tab dies before content.js signals,
// we detect it immediately via the timeout or onRemoved listener.
// =============================================================================

let poTokenTabId = null;
const PO_TOKEN_TAB_INIT_TIMEOUT = 8000;   // max wait for content script ready signal
const pendingPoTokenRequests = new Map(); // requestId → { resolve, reject, timer }

// Resolves when content.js on robots.txt sends 'poTokenTabReady'.
// Set by ensurePoTokenTab, resolved by onMessage listener.
let poTokenReadyResolve = null;

// ---- PO Token Cache ----
// A successfully minted PO token is reused across videos for the same visitor
// session. The BotGuard minter in content.js caches for 5 hours; the minted
// token itself is valid for about 6 hours. We cache the token in background.js
// so subsequent videos get it instantly (0ms) without needing the robots.txt tab.
//
// On cache miss or expiry, we try to mint via tab. If that fails (GeckoView
// killed the tab), we generate a cold-start placeholder token that satisfies
// YouTube's attestation check for initial segments while a real token is
// minted asynchronously in the background.
const PO_TOKEN_CACHE_TTL = 6 * 60 * 60 * 1000; // 6 hours
let poTokenCache = null; // { token, visitorData, timestamp }

/**
 * Generate a cold-start PO token entirely in background.js (no tab needed).
 * This is a placeholder token that YouTube accepts for initial SABR segments.
 * It buys time while a real BotGuard-minted token is generated asynchronously.
 *
 * Port of bgutils-js generateColdStartToken — pure math, no BotGuard VM.
 */
function generateColdStartPoToken(identifier) {
    const encoded = new TextEncoder().encode(identifier);
    if (encoded.length > 118) return null; // too long for cold-start format
    const timestamp = Math.floor(Date.now() / 1000);
    const keys = [Math.floor(Math.random() * 256), Math.floor(Math.random() * 256)];
    const header = new Uint8Array([keys[0], keys[1], 0, 1,
        (timestamp >> 24) & 0xff, (timestamp >> 16) & 0xff,
        (timestamp >> 8) & 0xff, timestamp & 0xff]);
    const packet = new Uint8Array(2 + header.length + encoded.length);
    packet[0] = 34;
    packet[1] = header.length + encoded.length;
    packet.set(header, 2);
    packet.set(encoded, 2 + header.length);
    // XOR scramble payload with 2-byte key
    const payload = packet.subarray(2);
    for (let i = 2; i < payload.length; i++) payload[i] ^= payload[i % 2];
    // Base64url encode
    const b64 = btoa(String.fromCharCode(...packet));
    return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

/**
 * Remove a PO token tab immediately, cleaning up all state.
 * Used after successful mint and on init failure to avoid leaking a GeckoSession.
 * Handles GeckoView builds where tabs.remove may not be supported.
 */
function removePoTokenTab(tabId) {
    if (poTokenTabId === tabId) poTokenTabId = null;
    if (tabId) {
        try {
            // tabs.remove returns a Promise — catch its rejection to avoid
            // uncaught errors in GeckoView builds that don't support it.
            const p = browser.tabs.remove(tabId);
            if (p && typeof p.catch === 'function') p.catch(() => {});
        } catch (e) {}
    }
}

async function ensurePoTokenTab() {
    // Fast path: tab already alive — verify content script is still responsive.
    // tabs.get succeeds even when GeckoView has torn down the window (tab exists
    // but page context is dead), so we probe with a ping message instead.
    if (poTokenTabId) {
        try {
            await browser.tabs.sendMessage(poTokenTabId, { type: 'ping' });
            return poTokenTabId;
        } catch (e) {
            // Content script is dead — clean up and create a fresh tab
            console.log(`[PoToken] Tab ${poTokenTabId} unresponsive, removing`);
            removePoTokenTab(poTokenTabId);
        }
    }

    // Clean up any orphaned robots.txt tabs (dead windows from previous runs)
    const existing = await browser.tabs.query({ url: '*://*.youtube.com/robots.txt' });
    for (const t of existing) {
        try { await browser.tabs.remove(t.id); } catch (e) {}
    }

    // Create new tab and wait for content.js ready signal (not a blind sleep).
    // content.js sends { type: 'poTokenTabReady' } after injecting bgutils + runner.
    let createdTabId = null;
    try {
        const tab = await browser.tabs.create({ url: 'https://www.youtube.com/robots.txt', active: false });
        createdTabId = tab.id;
        poTokenTabId = createdTabId;
        console.log(`[PoToken] Created robots.txt tab: ${createdTabId}`);

        // Wait for content script ready signal or timeout
        const ready = await new Promise((resolve) => {
            const timeout = setTimeout(() => {
                poTokenReadyResolve = null;
                resolve(false);
            }, PO_TOKEN_TAB_INIT_TIMEOUT);

            poTokenReadyResolve = () => {
                clearTimeout(timeout);
                poTokenReadyResolve = null;
                resolve(true);
            };
        });

        if (!ready) {
            // Content script never signaled — tab was likely killed by GeckoView.
            // Clean up to avoid leaking a dead GeckoSession.
            console.warn('[PoToken] Tab init timed out (content script never ready), removing tab');
            removePoTokenTab(createdTabId);
            return null;
        }

        // Verify the tab is still alive after the ready signal
        // (it could have been removed between signal and here)
        if (poTokenTabId !== createdTabId) {
            // onRemoved fired during init — tab is gone
            console.warn('[PoToken] Tab removed during init');
            return null;
        }

        console.log(`[PoToken] Tab ${createdTabId} ready`);
        return createdTabId;
    } catch (e) {
        console.warn(`[PoToken] tabs.create failed: ${e.message}`);
        removePoTokenTab(createdTabId);
        return null;
    }
}

// Handle messages from content script on robots.txt
browser.runtime.onMessage.addListener((msg) => {
    // Ready signal: content.js has injected bgutils + BotGuard runner
    if (msg?.type === 'poTokenTabReady') {
        if (poTokenReadyResolve) poTokenReadyResolve();
        return;
    }

    // Token result: content.js forwarding BotGuard mint result
    if (msg?.type === 'poTokenResult' && msg.data) {
        const rid = msg.data.requestId;
        const pending = rid ? pendingPoTokenRequests.get(rid) : null;

        // If no matching requestId, resolve any pending request (backward compat)
        const entry = pending || pendingPoTokenRequests.values().next().value;
        if (!entry) return;

        const key = pending ? rid : pendingPoTokenRequests.keys().next().value;
        clearTimeout(entry.timer);
        pendingPoTokenRequests.delete(key);

        if (msg.data.error) {
            entry.reject(new Error(msg.data.error));
        } else if (msg.data.token) {
            entry.resolve(msg.data.token);
        } else {
            entry.reject(new Error('Empty PO token result'));
        }
    }
});

/**
 * Generate a content-bound PO token for a video.
 *
 * Uses a three-tier strategy:
 * 1. Cache hit: return immediately (0ms) if a valid token exists for this visitor
 * 2. Tab mint: create robots.txt tab, run BotGuard VM, mint fresh token (~400ms)
 * 3. Cold-start fallback: generate a placeholder token in background.js (~0ms)
 *    that YouTube accepts for initial SABR segments
 *
 * Successfully minted tokens are cached for 6 hours and reused across videos.
 * This eliminates the per-video tab creation that GeckoView kept killing.
 *
 * @param {string} visitorData - Base64 visitor data
 * @param {string} videoId - YouTube video ID (used as content binding)
 * @returns {Promise<string>} PO token string
 */
async function generatePoToken(visitorData, videoId) {
    // Tier 1: return cached token if still valid for this visitor session
    if (poTokenCache &&
        poTokenCache.visitorData === visitorData &&
        (Date.now() - poTokenCache.timestamp) < PO_TOKEN_CACHE_TTL) {
        console.log(`[PoToken] Cache hit (age ${Math.round((Date.now() - poTokenCache.timestamp) / 1000)}s)`);
        return poTokenCache.token;
    }

    // Tier 2: try to mint via BotGuard tab
    try {
        const token = await mintPoTokenViaTab(visitorData, videoId);
        // Cache the successfully minted token
        poTokenCache = { token, visitorData, timestamp: Date.now() };
        console.log(`[PoToken] Minted and cached (${token.length} chars)`);
        // Keep the tab alive. Destroying it after each mint races with
        // GeckoView's session-store update, leaving a stale tab reference
        // whose `win` is null. That fault propagates through GeckoView's
        // eventDispatcher, which the WebExtension's macrotask scheduler
        // depends on — meaning setTimeout silently stops firing in
        // background.js. Symptoms: second click after a download silently
        // hangs because await new Promise(setTimeout 800) never resolves.
        //
        // The tab is hidden (active: false), uses no CPU when idle, and
        // costs ~10MB. Across the session that's a lot less expensive than
        // the bugs caused by destroy/recreate cycles. The minter inside
        // content.js already caches the BotGuard VM for ~5 hours, so
        // subsequent mints on the same tab are instant anyway.
        //
        // The tab is only torn down if (a) GeckoView kills it on its own
        // (handled via onRemoved → poTokenTabId = null), (b) ensurePoTokenTab
        // pings it and finds the content script unresponsive, or (c) the
        // extension is reloaded. In all three cases ensurePoTokenTab will
        // create a fresh one on the next call.
        return token;
    } catch (e) {
        console.warn(`[PoToken] Tab mint failed: ${e.message}`);
    }

    // Tier 3: cold-start placeholder token (no tab needed, instant)
    const identifier = videoId || visitorData;
    const coldToken = generateColdStartPoToken(identifier);
    if (coldToken) {
        // Cache it too — better than nothing, and avoids repeated tab failures
        poTokenCache = { token: coldToken, visitorData, timestamp: Date.now() };
        console.log(`[PoToken] Using cold-start token (${coldToken.length} chars)`);
        return coldToken;
    }

    throw new Error('PO token generation failed (all tiers)');
}

/**
 * Internal: mint a PO token via the BotGuard robots.txt tab.
 * Separated from generatePoToken so the cache/fallback logic stays clean.
 * Tab cleanup is handled by the caller — this function only mints.
 */
async function mintPoTokenViaTab(visitorData, videoId) {
    const tabId = await ensurePoTokenTab();
    if (!tabId) throw new Error('No robots.txt tab available');

    const requestId = `pot-${Date.now()}-${(Math.random() * 1e6 | 0).toString(36)}`;

    const token = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            pendingPoTokenRequests.delete(requestId);
            reject(new Error('PO token timeout (20s)'));
        }, 20000);

        pendingPoTokenRequests.set(requestId, { resolve, reject, timer });

        browser.tabs.sendMessage(tabId, {
            type: 'generatePoToken',
            data: { videoId, visitorData: visitorData || '', requestId }
        }).catch(async (e) => {
            // Tab might have died — try recreating once
            console.warn(`[PoToken] Send failed, recreating tab: ${e.message}`);
            removePoTokenTab(tabId);
            try {
                const newTabId = await ensurePoTokenTab();
                if (newTabId) {
                    await browser.tabs.sendMessage(newTabId, {
                        type: 'generatePoToken',
                        data: { videoId, visitorData: visitorData || '', requestId }
                    });
                    return;
                }
            } catch (e2) { /* fall through */ }
            clearTimeout(timer);
            pendingPoTokenRequests.delete(requestId);
            reject(new Error('PO token tab unavailable'));
        });
    });

    return token;
}


// =============================================================================
// INIT
// =============================================================================

console.log("[Init] YouTube SABR extension loaded (intercept + HTML)");

connectPort();
loadSolver().then(s => console.log(`[Init] Solver ready (v${s.SOLVER_VERSION})`)).catch(e => console.warn("[Init] Solver pre-load failed:", e.message));

(async () => {
    try {
        const tabs = await browser.tabs.query({});
        for (const tab of tabs) {
            if (tab.url) {
                urlToTabCache.set(tab.url, { tabId: tab.id, timestamp: Date.now() });
                try { const u = new URL(tab.url); urlToTabCache.set(u.origin + u.pathname, { tabId: tab.id, timestamp: Date.now() }); } catch {}
            }
        }
        console.log(`[Init] Cached ${urlToTabCache.size} URLs from ${tabs.length} existing tabs`);
    } catch (e) {}
})();