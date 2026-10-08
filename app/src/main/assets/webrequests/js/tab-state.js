// Per-tab state for the whole background page — ONE container per tab, dropped
// whole when the tab closes.
//
// Everything the catcher and the parsers remember ABOUT a tab used to live in
// a dozen module-scope maps keyed "tabId|something" (or by URL with a tabId in
// the value): the child playlists of a master the tab read, the player
// claims, the frame captions, the scraped-URL dedup, the snapshot gate, the
// tab-URL cache, the request-header cache, the parsers' SPA decisions. Each
// had its own cap, TTL and — sometimes — a purge loop in tabs.onRemoved; the
// ones without a purge kept a closed tab's entries until their TTL or cap, and
// the header cache, keyed by URL alone, served a private tab's Cookie to a
// regular tab's same-URL capture. Here every one of them is a field of the
// tab's TabState, so "tab closed → everything about it is gone" is structural,
// and a tab has exactly one browsing mode, so a cache that lives in the tab
// cannot cross modes.
//
// UNKNOWN_TAB (-1) is a pseudo-tab for facts recorded without a tab (a
// service-worker-originated request, a bridge sender with no tab). Lookups
// that used to treat -1 as a wildcard still do: see the callers' "-1 on
// either side" rules.
import { ClaimSet, MetaCache } from './bounded.js';

const UNKNOWN_TAB = -1;
const TAB_STATE_MAX = 256;   // far above any real tab count; the cap is only a bound

class TabState {
  constructor(tabId) {
    this.tabId = tabId;
    this.incognito = null;                                   // learned from the first request / sender seen
    this.urls = new MetaCache(64, 30_000);                   // url and origin+path → last seen (tab-URL cache)
    this.headers = new MetaCache(512, 10 * 60 * 1000);       // url → { headers, fromExtensionContext }
    this.hlsChildren = new MetaCache(512, 30 * 60 * 1000);   // child playlist url → master url
    this.claimedFrames = new ClaimSet(10 * 60 * 1000, 128);  // frame document url (player claims)
    this.claimedUrls = new ClaimSet(10 * 60 * 1000, 512);    // media url (player claims)
    this.claimWaiters = new Map();                           // frame url → [resolve] (each waiter has its own timer)
    this.frameCaptions = new MetaCache(128, 10 * 60 * 1000); // iframe src (no fragment) → caption
    this.scraped = new ClaimSet(Infinity, 1024);             // content-script-reported urls (FIFO dedup)
    this.claims = new Map();                                 // name → ClaimSet (parser per-tab decisions)
    this.snapshot = null;                                    // { at, timer } while a snapshot capture is live
  }
}

const tabStates = new MetaCache(TAB_STATE_MAX);   // tabId → TabState; lifetime = the tab

function tabKey(tabId) {
  return (typeof tabId === 'number' && tabId >= 0) ? tabId : UNKNOWN_TAB;
}

// The tab's state, created on first use.
function tabState(tabId) {
  const id = tabKey(tabId);
  let s = tabStates.get(id);
  if (!s) {
    s = new TabState(id);
    tabStates.set(id, s);
  }
  return s;
}

// The tab's state if it exists — a read that must not create one.
function peekTabState(tabId) {
  return tabStates.get(tabKey(tabId));
}

function* allTabStates() {
  for (const [, s] of tabStates) yield s;
}

// A parser's per-tab ClaimSet (its SPA / emit decisions), created on first use
// with the given bounds and dropped with the tab.
function tabClaims(tabId, name, ttlMs, max) {
  const s = tabState(tabId);
  let c = s.claims.get(name);
  if (!c) {
    c = new ClaimSet(ttlMs, max);
    s.claims.set(name, c);
  }
  return c;
}

// Remember a URL (and its origin+path) as seen in a tab.
function rememberTabUrl(tabId, url) {
  if (!url || typeof tabId !== 'number' || tabId < 0) return;
  const s = tabState(tabId);
  const now = Date.now();
  s.urls.set(url, now);
  try {
    const u = new URL(url);
    s.urls.set(u.origin + u.pathname, now);
  } catch (_) { /* not a URL */ }
}

// The tab a URL (or origin+path) was last seen in — newest wins. Bounded by
// the tab count.
function tabIdForUrl(key) {
  let best = -1;
  let bestTs = -1;
  for (const s of allTabStates()) {
    if (s.tabId < 0) continue;
    const ts = s.urls.get(key);
    if (ts !== undefined && ts > bestTs) { best = s.tabId; bestTs = ts; }
  }
  return best;
}

// [url, lastSeen] of one tab, oldest first.
function* tabUrls(tabId) {
  const s = peekTabState(tabId);
  if (!s) return;
  yield* s.urls;
}

// [url, tabId, lastSeen] across every tab.
function* allTabUrls() {
  for (const s of allTabStates()) {
    if (s.tabId < 0) continue;
    for (const [url, ts] of s.urls) yield [url, s.tabId, ts];
  }
}

const removedHooks = [];
// A hook for state that is not IN the TabState but is still about a tab
// (requests.js's origin→tab resolution cache).
function onTabRemoved(fn) { removedHooks.push(fn); }

browser.tabs.onRemoved.addListener((tabId) => {
  const s = tabStates.get(tabId);
  if (s) {
    // A sub-frame report parked on a player claim that can never land now.
    for (const list of s.claimWaiters.values()) for (const resolve of list) resolve(false);
    if (s.snapshot) clearTimeout(s.snapshot.timer);
    tabStates.delete(tabId);
  }
  for (const fn of removedHooks) {
    try { fn(tabId); } catch (_) { /* a hook must not take the others down */ }
  }
});

export function __tabStateCount() { return tabStates.size; }

export { UNKNOWN_TAB, TabState, tabState, peekTabState, allTabStates, tabClaims,
         rememberTabUrl, tabIdForUrl, tabUrls, allTabUrls, onTabRemoved };
