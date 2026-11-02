// Smoke test for the cookie-banner extension's background half
// (app/src/main/assets/consent/js/background.js): the REAL module under a
// stubbed `browser`, with the REAL autoconsent library and rules beside it.
//
//   node scripts/consent-smoke.mjs
//
// What it pins: nothing is answered before the app's switch arrives, or while
// it is off; an http(s) frame gets the rules that apply to its URL (a
// sub-frame the smaller frame set) and an opt-out config; a non-web frame is
// not answered; "eval" runs the library's own snippet in the page's MAIN
// world, in that frame, and answers with its result (false when the snippet
// is unknown or throws); the listener never answers sendMessage itself.
// The whole loop in a real page (content script, a consent banner, the
// reject click) is scripts/consent-harness.mjs.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ext = join(here, '../app/src/main/assets/consent');

const listeners = [];
const portListeners = [];
const sent = [];
const executed = [];
let executeImpl = async () => [{ result: true }];

globalThis.browser = {
  runtime: {
    sendNativeMessage: async () => false,
    connectNative: () => ({ onMessage: { addListener: (fn) => portListeners.push(fn) } }),
    onMessage: { addListener: (fn) => listeners.push(fn) },
    getURL: (path) => pathToFileURL(join(ext, path)).href,
  },
  tabs: {
    sendMessage: async (tabId, msg, options) => { sent.push({ tabId, msg, options }); },
  },
  scripting: {
    executeScript: async (details) => { executed.push(details); return executeImpl(details); },
  },
};
globalThis.fetch = async (url) => {
  const body = readFileSync(fileURLToPath(url), 'utf8');
  return { json: async () => JSON.parse(body) };
};

const bg = await import(pathToFileURL(join(ext, 'js/background.js')));
const lib = await import(pathToFileURL(join(ext, 'js/autoconsent.esm.js')));

let failures = 0;
function expect(cond, label) {
  if (cond) {
    console.log('ok  ', label);
  } else {
    console.error('FAIL', label);
    failures++;
  }
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const send = (msg, sender) => listeners.map((fn) => fn(msg, sender));
const config = (enabled) => portListeners.forEach((fn) => fn({ type: 'config', enabled }));
const page = (frameId, url) => ({ tab: { id: 7 }, frameId, url });
const SITE = 'https://news.example/article';

expect(listeners.length === 1, 'one runtime.onMessage listener');
expect(portListeners.length === 1, 'listens on the "consent" native port');

// Before the switch arrives: a frame waits, and is answered once it is on.
send({ type: 'init' }, page(0, SITE));
await wait(50);
expect(sent.length === 0, 'nothing is answered before the app says whether the switch is on');
config(true);
await wait(100);
const first = sent.shift();
expect(first && first.msg.type === 'initResp' && first.options.frameId === 0 && first.tabId === 7,
  'the waiting frame is answered once the switch arrives on');
expect(first && first.msg.config.autoAction === 'optOut' && first.msg.config.enabled === true,
  'the config rejects (optOut)');
expect(first && Array.isArray(first.msg.rules.compact.r) && first.msg.rules.compact.r.length > 100,
  `the frame gets the compact rules (${first?.msg.rules.compact.r.length})`);

// A sub-frame gets the frame set, smaller.
send({ type: 'init' }, page(3, 'https://cmp.example/frame'));
await wait(50);
const sub = sent.shift();
const rules = JSON.parse(readFileSync(join(ext, 'rules/compact-rules.json'), 'utf8'));
const expected = lib.filterCompactRules(rules, { url: 'https://cmp.example/frame', mainFrame: false });
expect(sub && sub.options.frameId === 3 && sub.msg.rules.compact.r.length === expected.r.length
  && sub.msg.rules.compact.r.length < first.msg.rules.compact.r.length,
  'a sub-frame gets the library\'s frame rules for its URL');

// Not a web page: no answer.
send({ type: 'init' }, page(0, 'about:blank'));
send({ type: 'init' }, page(0, 'moz-extension://x/page.html'));
await wait(50);
expect(sent.length === 0, 'a frame that is not http(s) is not answered');

// The switch off: no answer; on again: answered.
config(false);
send({ type: 'init' }, page(0, SITE));
await wait(50);
expect(sent.length === 0, 'with the switch off a frame is not answered (autoconsent does nothing)');
config(true);
send({ type: 'init' }, page(0, SITE));
await wait(50);
expect(sent.length === 1, 'on again: answered');
sent.length = 0;

// eval: the library's snippet, MAIN world, the sender's frame.
const snippetId = 'EVAL_COOKIE_LAW_INFO_DETECT';
executeImpl = async () => [{ result: true }];
send({ type: 'eval', id: 42, snippetId }, page(2, SITE));
await wait(50);
const ex = executed.shift();
expect(ex && ex.world === 'MAIN' && ex.func === lib.evalSnippets[snippetId]
  && ex.target.tabId === 7 && ex.target.frameIds.length === 1 && ex.target.frameIds[0] === 2,
  'eval runs the library\'s snippet in the MAIN world of the sender\'s frame');
const er = sent.shift();
expect(er && er.msg.type === 'evalResp' && er.msg.id === 42 && er.msg.result === true && er.options.frameId === 2,
  'and answers with its result');

send({ type: 'eval', id: 43, snippetId: 'EVAL_NO_SUCH_THING' }, page(0, SITE));
await wait(50);
const unknown = sent.shift();
expect(executed.length === 0 && unknown && unknown.msg.result === false,
  'an unknown snippet runs nothing and answers false');

executeImpl = async () => { throw new Error('frame gone'); };
send({ type: 'eval', id: 44, snippetId }, page(0, SITE));
await wait(50);
const thrown = sent.shift();
expect(thrown && thrown.msg.id === 44 && thrown.msg.result === false, 'a snippet that throws answers false');

// Never the responder; other kinds and senders without a tab are ignored.
const returns = send({ type: 'init' }, page(0, SITE)).concat(send({ type: 'autoconsentDone', cmp: 'x' }, page(0, SITE)));
expect(returns.every((r) => r === undefined), 'the listener never answers sendMessage itself');
await wait(50);
sent.length = 0;
send({ type: 'init' }, { frameId: 0, url: SITE });
send(null, page(0, SITE));
await wait(50);
expect(sent.length === 0, 'a message with no tab, or no message, is ignored');

if (failures) {
  console.error(`\n${failures} failure(s)`);
  process.exit(1);
}
console.log('\nconsent-smoke: all checks passed');
process.exit(0);
