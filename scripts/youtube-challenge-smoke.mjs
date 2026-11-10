// The inline BotGuard challenge reader (assets/youtube/challenge.js), run in
// a vm against the shapes a YouTube page carries it in.
//
//   node scripts/youtube-challenge-smoke.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = fs.readFileSync(path.join(root, 'app/src/main/assets/youtube/challenge.js'), 'utf8');
const ctx = {};
vm.createContext(ctx);
vm.runInContext(src + ';this.extract = fdExtractInlineChallenge;', ctx);
const extract = ctx.extract;

let pass = 0, fail = 0;
function check(name, ok, detail = '') {
    if (ok) { pass++; console.log('PASS  ' + name); }
    else { fail++; console.log('FAIL  ' + name + (detail ? '  [' + detail + ']' : '')); }
}

const challenge = {
    interpreterUrl: { privateDoNotAccessOrElseTrustedResourceUrlWrappedValue: '//www.google.com/js/th/AbC-123_x.js' },
    interpreterHash: 'AbC-123_x',
    program: 'Zq+IevAycl83/RBO7HVwx{Ub}gBH"AbOD==',
    globalName: 'trayride',
    clientExperimentsStateBlob: '[null,null,[]]'
};
// As a page writes it: JSON inside a JS string literal, every quote, brace
// and slash escaped, a JSON & arriving as \\u0026.
function inJsString(obj) {
    return JSON.stringify(obj)
        .replace(/&/g, '\\u0026')
        .replace(/\\/g, '\\\\')
        .replace(/"/g, '\\x22')
        .replace(/\{/g, '\\x7b').replace(/\}/g, '\\x7d')
        .replace(/\//g, '\\/');
}
const wrapper = { challenge: 'a=6&a2=10&b=xyz&c=1&d=2', bgChallenge: challenge, after: { x: 1 } };
const escapedPage = '<html><script>ytcfg.set({"INNERTUBE_CLIENT_VERSION":"2.20261106.00.00"});'
    + "var d = '" + inJsString(wrapper) + "';</script></html>";
const plainPage = '<html><script>var x = {"INNERTUBE_CLIENT_VERSION":"2.20261106.00.00","att":'
    + JSON.stringify(wrapper) + '};</script></html>';

for (const [name, page] of [['escaped in a JS string', escapedPage], ['plain JSON', plainPage]]) {
    const r = extract(page);
    check(name + ': found', !!r);
    check(name + ': the program is byte-exact, braces and quotes inside it included',
        !!r && r.bgChallenge.program === challenge.program, r && r.bgChallenge.program);
    check(name + ': interpreter URL and global name',
        !!r && r.bgChallenge.globalName === 'trayride'
            && r.bgChallenge.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue
                === '//www.google.com/js/th/AbC-123_x.js');
    check(name + ': stops at the challenge object (the next key is not part of it)',
        !!r && r.bgChallenge.after === undefined && Object.keys(r.bgChallenge).length === 5);
    check(name + ': client version', !!r && r.clientVersion === '2.20261106.00.00', r && r.clientVersion);
}

check('a page with no challenge answers null', extract('<html>consent</html>') === null);
check('a page cut inside the challenge answers null',
    extract(escapedPage.substring(0, escapedPage.indexOf('trayride'))) === null);
check('a challenge with no program answers null',
    extract('{"bgChallenge":{"globalName":"x","interpreterUrl":{}}}') === null);
check('a mention that is not the object answers null',
    extract('var s = "bgChallenge is read from the page somewhere far away {";') === null);
check('not a string answers null', extract(null) === null && extract(undefined) === null);

console.log('\n' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
