// Requests a blocker refused, counted for the address bar's shield sheet and
// Settings → Privacy → "Trackers and ads blocked" — whichever blocker did it.
//
// The ad blocker is an ordinary add-on now (the official uBlock Origin, or
// whatever the user installs), and an add-on cannot talk to the app. But this
// extension already sees every request a blocker cancels: an extension's
// webRequest cancel surfaces here as onErrorOccurred NS_ERROR_ABORT, and
// Gecko's own content blocking as a URL-classifier *_URI error. So the count
// is taken here, once per request, however many blockers refused it.
//
// Two figures:
//   - the PAGE tally of the tab on screen (a count and its hosts), reset when
//     the tab's top document commits, sent whenever it changes;
//   - the lifetime / today totals, sent as DELTAS that Java adds to what it
//     has persisted, regular tabs only (a private tab's hosts never reach a
//     stored list).
import { tabState, peekTabState } from './tab-state.js';

// An extension's cancel (NS_ERROR_ABORT — a page or navigation abort is
// NS_BINDING_ABORTED and does not match) or Gecko's content blocking.
export const BLOCKED_REQUEST_ERROR_RE =
  /^NS_ERROR_(ABORT|(TRACKING|CRYPTOMINING|FINGERPRINTING|SOCIALTRACKING|EMAILTRACKING|MALWARE|PHISHING|UNWANTED|HARMFUL|BLOCKED)_URI)$/;

const FLUSH_MS = 1000;          // a burst of blocks is one message
const MAX_PAGE_HOSTS = 200;     // per tab; past it the count still grows
const MAX_DELTA_HOSTS = 500;    // per flush
const PAGE_ITEMS_SENT = 100;    // the sheet's list

let activeTabId = -1;
let pending = null;             // totals not yet sent
let pageDirty = false;
let timer = null;

// The four rows of Settings' "Today by request type".
export function bucketOf(type) {
  switch (type) {
    case 'script':
      return 'scripts';
    case 'image':
    case 'imageset':
    case 'beacon':
    case 'ping':
      return 'pixels';
    case 'sub_frame':
    case 'object':
      return 'frames';
    default:
      return 'other';
  }
}

function hostOf(url) {
  try {
    return new URL(url).hostname;
  } catch (_) {
    return '';
  }
}

function emptyPage() {
  return { count: 0, hosts: new Map() };
}

function pageOf(state) {
  if (!state.pageBlocked) state.pageBlocked = emptyPage();
  return state.pageBlocked;
}

// True when a finished request was refused by a blocker.
export function isBlockedRequest(data) {
  return typeof data.tabId === 'number' && data.tabId >= 0
    && /^https?:/i.test(data.url || '')
    && BLOCKED_REQUEST_ERROR_RE.test(data.error || '');
}

// onErrorOccurred of a request isBlockedRequest accepted.
export function countBlocked(data) {
  // A refused document is a page the blocker replaced with its own, not a
  // request on the page.
  if (data.type === 'main_frame') return;
  const state = tabState(data.tabId);
  if (typeof data.incognito === 'boolean') state.incognito = data.incognito;
  const host = hostOf(data.url);
  const page = pageOf(state);
  page.count++;
  if (host) {
    const n = page.hosts.get(host);
    if (n !== undefined) page.hosts.set(host, n + 1);
    else if (page.hosts.size < MAX_PAGE_HOSTS) page.hosts.set(host, 1);
  }
  // Unknown mode counts as private: nothing about it is stored.
  if (state.incognito === false) {
    if (!pending) {
      pending = { blocked: 0, scripts: 0, pixels: 0, frames: 0, other: 0, hosts: new Map() };
    }
    pending.blocked++;
    pending[bucketOf(data.type)]++;
    if (host) {
      const n = pending.hosts.get(host);
      if (n !== undefined) pending.hosts.set(host, n + 1);
      else if (pending.hosts.size < MAX_DELTA_HOSTS) pending.hosts.set(host, 1);
    }
  }
  if (data.tabId === activeTabId) pageDirty = true;
  schedule(FLUSH_MS);
}

// The tab on screen changed: its tally replaces the last one at once.
export function setActiveTab(tabId) {
  if (typeof tabId !== 'number' || tabId === activeTabId) return;
  activeTabId = tabId;
  pageDirty = true;
  schedule(0);
}

// A new top document: its page starts at zero.
export function resetPage(tabId) {
  const state = peekTabState(tabId);
  if (state) state.pageBlocked = emptyPage();
  if (tabId === activeTabId) {
    pageDirty = true;
    schedule(0);
  }
}

function schedule(ms) {
  if (timer !== null) {
    if (ms > 0) return;
    clearTimeout(timer);
  }
  timer = setTimeout(flush, ms);
}

function topItems(hosts, max) {
  const rows = [];
  for (const [host, count] of hosts) rows.push({ host, count });
  rows.sort((a, b) => b.count - a.count);
  return rows.length > max ? rows.slice(0, max) : rows;
}

export function pageSnapshot(tabId, incognito) {
  const state = peekTabState(tabId);
  const page = state && state.pageBlocked ? state.pageBlocked : emptyPage();
  return {
    tabId,
    incognito,
    count: page.count,
    items: topItems(page.hosts, PAGE_ITEMS_SENT),
  };
}

// The tab's mode: learned from its requests, else asked once.
async function modeOf(tabId) {
  const state = tabState(tabId);
  if (typeof state.incognito !== 'boolean') {
    try {
      const tab = await browser.tabs.get(tabId);
      if (typeof tab?.incognito === 'boolean') state.incognito = tab.incognito;
    } catch (_) { /* a tab that went away */ }
  }
  // Unknown counts as private: a regular sheet never shows its numbers.
  return state.incognito !== false;
}

export async function flush() {
  timer = null;
  const message = { listener: 'blockedStats' };
  if (pending) {
    message.totals = {
      blocked: pending.blocked,
      scripts: pending.scripts,
      pixels: pending.pixels,
      frames: pending.frames,
      other: pending.other,
      hosts: topItems(pending.hosts, MAX_DELTA_HOSTS),
    };
    pending = null;
  }
  const pageTab = pageDirty ? activeTabId : -1;
  pageDirty = false;
  if (pageTab >= 0) {
    message.page = pageSnapshot(pageTab, await modeOf(pageTab));
  }
  if (!message.totals && !message.page) return null;
  browser.runtime.sendNativeMessage('browser', message).catch(() => {});
  return message;
}

browser.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId === 0) resetPage(details.tabId);
});
