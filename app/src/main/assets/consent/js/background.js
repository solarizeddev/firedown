// Answers cookie consent pop-ups with "reject" (opt-out), hiding the ones that
// offer no reject, with DuckDuckGo's autoconsent (MPL-2.0). The two library
// files are the npm package's, verbatim (scripts/update-autoconsent.sh):
//   js/autoconsent-content.js — the package's Firefox add-on content script
//     (dist/addon-firefox/content.bundle.js): an AutoConsent instance per
//     frame that asks this page for its rules ("init"), detects the consent
//     tool, clicks through to reject, and asks this page to run a page-world
//     check when a rule needs one ("eval").
//   js/autoconsent.esm.js — the library, for filterCompactRules and the
//     page-world checks (evalSnippets).
//   rules/compact-rules.json — the rules.
// This file is the background half the package's own add-on has: answer
// "init" with the rules that apply to the frame's URL and the config, and run
// "eval" snippets in the page's MAIN world.
//
// The user's switch (Settings → Privacy → "Reject cookie banners") arrives
// from the app over the "consent" native port, on connect and on every
// change. Until it has arrived a frame is not answered, and a frame that is
// not answered does nothing (autoconsent waits for its config).
import { evalSnippets, filterCompactRules } from './autoconsent.esm.js';

let DEBUG = false;
browser.runtime.sendNativeMessage('consent', { kind: 'get-debug-flag' })
  .then((value) => { DEBUG = value === true; })
  .catch(() => {});

function log(...args) {
  if (DEBUG) console.log('[consent]', ...args);
}

// How long a frame waits for the app's switch on a cold start.
const CONFIG_WAIT_MS = 5000;

let enabled = null;
let resolveConfigured;
const configured = new Promise((resolve) => { resolveConfigured = resolve; });

function onPortMessage(msg) {
  if (msg && msg.type === 'config') {
    enabled = msg.enabled === true;
    log('enabled', enabled);
    resolveConfigured();
  }
}

try {
  const port = browser.runtime.connectNative('consent');
  port.onMessage.addListener(onPortMessage);
} catch (e) {
  log('no native port', e);
}

let rulesPromise = null;

function compactRules() {
  if (!rulesPromise) {
    rulesPromise = fetch(browser.runtime.getURL('rules/compact-rules.json'))
      .then((response) => response.json())
      .catch((e) => {
        rulesPromise = null;
        throw e;
      });
  }
  return rulesPromise;
}

function waitForConfig() {
  if (enabled !== null) return Promise.resolve(true);
  return Promise.race([
    configured.then(() => true),
    new Promise((resolve) => setTimeout(() => resolve(false), CONFIG_WAIT_MS)),
  ]);
}

export function autoconsentConfig() {
  return {
    enabled: true,
    autoAction: 'optOut',
    enablePrehide: true,
    enableCosmeticRules: true,
    enableGeneratedRules: true,
    logs: {
      lifecycle: DEBUG,
      rulesteps: false,
      detectionsteps: false,
      evals: false,
      errors: DEBUG,
      messages: false,
      waits: false,
    },
  };
}

async function onInit(sender) {
  if (!(await waitForConfig()) || !enabled) return;
  const url = sender.url || '';
  if (!/^https?:/i.test(url)) return;
  const compact = filterCompactRules(await compactRules(), { url, mainFrame: sender.frameId === 0 });
  await browser.tabs.sendMessage(sender.tab.id, {
    type: 'initResp',
    rules: { autoconsent: [], compact },
    config: autoconsentConfig(),
  }, { frameId: sender.frameId });
}

async function onEval(msg, sender) {
  let result = false;
  const snippet = evalSnippets[msg.snippetId];
  if (typeof snippet === 'function') {
    try {
      const [res] = await browser.scripting.executeScript({
        target: { tabId: sender.tab.id, frameIds: [sender.frameId] },
        world: 'MAIN',
        func: snippet,
      });
      result = res ? res.result : false;
    } catch (e) {
      log('eval failed', msg.snippetId, e);
    }
  } else {
    log('unknown snippet', msg.snippetId);
  }
  await browser.tabs.sendMessage(sender.tab.id, { id: msg.id, type: 'evalResp', result },
    { frameId: sender.frameId });
}

// Fire-and-forget: the listener returns nothing, so it never answers the
// content script's sendMessage (autoconsent reads its replies from the
// messages sent back with tabs.sendMessage).
export function onMessage(msg, sender) {
  if (!msg || typeof msg.type !== 'string' || !sender || !sender.tab) return;
  switch (msg.type) {
    case 'init':
      onInit(sender).catch((e) => log('init failed', e));
      break;
    case 'eval':
      onEval(msg, sender).catch((e) => log('eval answer failed', e));
      break;
    case 'popupFound':
    case 'optOutResult':
    case 'autoconsentDone':
      log(msg.type, msg.cmp, msg.result, sender.url);
      break;
    case 'autoconsentError':
      log('error', msg.details, sender.url);
      break;
    default:
      break;
  }
}

browser.runtime.onMessage.addListener(onMessage);

export const __test = { onPortMessage, compactRules };
