import µb from './background.js';

import { hostnameFromURI } from './uri-utils.js';

import {
    permanentSwitches,
    sessionSwitches,
} from './filtering-engines.js';

import { PageStore } from './pagestore.js';

/******************************************************************************/

(() => {

    const port = browser.runtime.connectNative("ublock");

    port.onMessage.addListener(response => {
        port.postMessage({ listener: "ublock", message: response });
        if (Object.hasOwn(response, "ads")) {
            toggleAds({ enable: response.ads });
        } else if (Object.hasOwn(response, "javascript")) {
            toggleJavascript({ enable: response.javascript });
        } else if (Object.hasOwn(response, "media")) {
            toggleMedia({ enable: response.media });
        } else if (Object.hasOwn(response, "fonts")) {
            toggleFonts({ enable: response.fonts });
        } else if (Object.hasOwn(response, "cookies")) {
            toggleCookieNotices({ enable: response.cookies });
        } else if (Object.hasOwn(response, "update")) {
            updateState();
        } else if (response.clearTopTrackers === true) {
            // Java side asked us to wipe the per-host map. One-tap
            // user action from the sheet's 'Clear' button. Recording
            // continues — disabling the feature entirely is a future
            // Settings-level control, not an in-sheet one.
            clearTrackerBlocks();
        }
    });

    // EasyList – Cookie Notices. Marked preferred:true in upstream assets.json
    // for the "EasyList/uBO – Cookie Notices" parent group.
    const COOKIE_NOTICE_LISTS = [
        'fanboy-cookiemonster',    // EasyList - Cookie Notices (preferred in uBO)
        'ublock-cookies-adguard',  // uBlock filters - Cookie Notices (AdGuard-based)
    ];

    /**************************************************************************
     * Startup migration: heal installations where selectedFilterLists was
     * corrupted to [] by a prior bug. Without this, uBO boots with zero
     * lists selected; network filtering appears to work because engines
     * deserialize from the on-disk selfie (start.js line 454), but any
     * subsequent save or recompile drops the engine to ~2k rules instead
     * of the ~60k+ a default 9-list selection produces.
     *
     * Reads assets.json directly rather than µb.availableFilterLists[k].off
     * because that in-memory flag is derived from selectedFilterLists at
     * load time (storage.js line 852) — when selection is corrupted, every
     * asset reports off=true, making the in-memory view useless for
     * determining the true default-on state.
     *************************************************************************/
    async function ensureDefaultListsSelected() {
        await µb.isReadyPromise;

        if (Object.keys(µb.availableFilterLists).length === 0) { return; }
        if (µb.selectedFilterLists.length >= 3) { return; }

        console.log('[firedown-migration] repopulating defaults (current=' + µb.selectedFilterLists.length + ')');

        let assetsJson;
        try {
            const response = await fetch(µb.assetsJsonPath);
            assetsJson = await response.json();
        } catch (e) {
            console.log('[firedown-migration] assets.json fetch failed: ' + (e && e.message));
            return;
        }

        // Preserve whatever is already selected (e.g. fanboy-cookiemonster
        // that the user previously enabled), then add every list that ships
        // as default-on in assets.json.
        const defaults = new Set(µb.selectedFilterLists);
        for (const [key, asset] of Object.entries(assetsJson)) {
            if (asset.content !== 'filters') { continue; }
            if (asset.off === true) { continue; }
            defaults.add(key);
        }

        // applyFilterListSelection (storage.js line 481): computes new
        // selection, purges removed lists from cache, calls
        // saveSelectedFilterLists(result) at line 554 which both assigns
        // µb.selectedFilterLists and persists to storage.local.
        //
        // DO NOT call saveSelectedFilterLists() without arguments afterward.
        // Its signature is (newKeys, append); a bare call sets selection to [].
        µb.applyFilterListSelection({ toSelect: Array.from(defaults) });
        await µb.loadFilterLists();

        console.log('[firedown-migration] done, ' + µb.selectedFilterLists.length + ' lists selected');
    }

    // Exposed so toggleCookieNotices gates on startup migration instead of
    // racing it. Other toggles don't touch selectedFilterLists and don't need
    // to wait.
    const defaultsReady = ensureDefaultListsSelected().catch(e =>
        console.log('[firedown-migration] failed: ' + (e && e.message)));

    /**************************************************************************
     * Cookie-notice toggle — global filter-list selection.
     * Mutates µb.selectedFilterLists. Must wait for migration.
     *************************************************************************/
    async function toggleCookieNotices(message) {
        await defaultsReady;

        const enable = message.enable === true;

        // Short-circuit if uBlock is already in the desired state. The
        // on-connect handshake in GeckoRuntimeHelper.onConnect pushes
        // {cookies:true} every time the port attaches, which happens at
        // least once per app launch — without this gate every launch
        // would force-reload the active tab even though nothing changed.
        const haveCookieLists = COOKIE_NOTICE_LISTS.every(
            k => µb.selectedFilterLists.includes(k)
        );
        if (enable === haveCookieLists) {
            return;
        }

        const details = enable
            ? { toSelect: COOKIE_NOTICE_LISTS, merge: true }
            : { toRemove: COOKIE_NOTICE_LISTS };

        µb.applyFilterListSelection(details);
        await µb.loadFilterLists();

        const tab = await vAPI.tabs.getCurrent();
        if (tab instanceof Object) {
            vAPI.tabs.reload(tab.id);
        }
    }

    /**************************************************************************
     * Per-tab net-filtering switch — the "ads" toggle.
     *
     * Writes to µb.netWhitelist (a Map of hostname→directives, see ublock.js
     * line 127). Completely independent from selectedFilterLists and filter
     * compilation — this is a per-URL whitelist layer on top of the
     * filtering engine. No need to wait for defaultsReady.
     *************************************************************************/
    async function toggleAds(message) {
        const newState = message.enable === true;
        const tab = await vAPI.tabs.getCurrent();
        if (tab instanceof Object === false) { return; }

        // Use normalURL (= tabContext.normalURL) as the popup does, so the
        // URL passed to toggleNetFilteringSwitch is consistent with what
        // getNetFilteringSwitch will later look up. Scope '' → hostname
        // directive (ublock.js line 141), matching the popup default.
        const pageURL = µb.normalizeTabURL(tab.id, tab.url);

        // Go through pageStore so the net-filtering cache is invalidated,
        // matching the upstream popup flow in messaging.js toggleNetFiltering.
        const pageStore = µb.pageStoreFromTabId(tab.id);
        if (pageStore) {
            pageStore.toggleNetFilteringSwitch(pageURL, '', newState);
            µb.updateToolbarIcon(tab.id, 0b111);
        } else {
            µb.toggleNetFilteringSwitch(pageURL, '', newState);
        }

        // Push the new firewall state back to Java immediately. tab.js
        // onActivated only fires on tab *switch*, not on a same-tab reload,
        // so without this the dialog's switch would lag one tab-switch
        // behind the real state.
        updateState();

        vAPI.tabs.reload(tab.id);
    }

    /**************************************************************************
     * Per-hostname session switches — javascript / media / fonts.
     *
     * Writes to sessionSwitches / permanentSwitches (DynamicSwitchRuleFiltering
     * instances in filtering-engines.js). Independent from both
     * selectedFilterLists and netWhitelist. No need to wait for defaultsReady.
     *************************************************************************/
    async function toggleHostnameSwitch(switchName, enable) {
        const tab = await vAPI.tabs.getCurrent();
        if (tab instanceof Object === false) { return; }
        const hostname = hostnameFromURI(µb.normalizeTabURL(tab.id, tab.url));
        sessionSwitches.toggle(switchName, hostname, enable ? 1 : 0);
        if (permanentSwitches.copyRules(sessionSwitches, hostname)) {
            µb.saveHostnameSwitches();
        }
        vAPI.tabs.reload(tab.id);
    }

    async function toggleJavascript(message) {
        await toggleHostnameSwitch('no-scripting', message.enable === true);
    }

    async function toggleMedia(message) {
        await toggleHostnameSwitch('no-large-media', message.enable === true);
    }

    async function toggleFonts(message) {
        await toggleHostnameSwitch('no-remote-fonts', message.enable === true);
    }

    /**************************************************************************
     * State readback to native side. Fired on {update:true} message from Java.
     *************************************************************************/
    async function updateState() {
        const tab = await vAPI.tabs.getCurrent();
        if (tab instanceof Object === false) { return; }
        const currentState = µb.getNetFilteringSwitch(tab.url);
        const cookieNoticesBlocked = COOKIE_NOTICE_LISTS.some(
            k => µb.selectedFilterLists.includes(k)
        );
        browser.runtime.sendNativeMessage("ublock", {
            firewall: {
                activated: currentState,
                cookies: cookieNoticesBlocked,
            }
        });
        // Piggyback the cumulative-blocked counter on this trigger so
        // the Home 'trackers blocked' card stays in sync without an
        // extra round-trip. uBlock owns the storage; we just relay.
        pushCumulativeStats();
    }

    /**************************************************************************
     * Pushes µb.requestStats.blockedCount (cumulative since install) to the
     * native side. Read by GeckoUblockHelper to drive the Home 'trackers
     * blocked' card. Sent on extension load + every firewall update + on a
     * 60-second interval so the card refreshes while the user browses without
     * polling uBlock internals from Java.
     *************************************************************************/
    function pushCumulativeStats() {
        try {
            const stats = µb.requestStats;
            if (!stats || typeof stats.blockedCount !== 'number') { return; }
            browser.runtime.sendNativeMessage("ublock", {
                cumulativeBlocked: stats.blockedCount
            });
        } catch (_) { /* extension still loading, retry on next tick */ }
    }

    /**************************************************************************
     * Per-category blocked counters.
     *
     * uBlock's static engine throws away filter-list origin at compile time —
     * reverse-lookup against the raw lists is too expensive to do per-request
     * — so we can't bucket by 'Trackers / Ads / Cookie notices' the way the
     * desktop logger UI does. Instead we bucket by request type (fctxt.itype),
     * which is already on the filtering context. The four buckets map to a
     * privacy story the TrackersInfoSheet can render directly:
     *
     *   scripts — SCRIPT, INLINE_SCRIPT. Analytics, tracker libs, ad SDKs.
     *   pixels  — IMAGE, IMAGESET, BEACON, PING. Tracking pixels and
     *             telemetry beacons.
     *   frames  — SUB_FRAME, OBJECT. Embedded ad iframes / plugins.
     *   other   — XHR, fetch, fonts, media, websocket, CSP, stylesheet, etc.
     *
     * Counts are aggregated at journalProcess time (the existing 10s-ish
     * batched commit point) rather than per-request, so the hook overhead
     * matches uBO's own update cadence. Persisted to vAPI.storage.local so
     * the numbers survive across app launches.
     *************************************************************************/
    const CATEGORY_STORAGE_KEY = 'firedownCategoryBlocked';
    const CATEGORY_NAMES = ['scripts', 'pixels', 'frames', 'other'];

    // Mirror filtering-context.js' itype bit positions. Duplicated here so
    // we don't have to widen pagestore.js' export surface.
    const ITYPE_BEACON        = 1 <<  0;
    const ITYPE_IMAGE         = 1 <<  4;
    const ITYPE_OBJECT        = 1 <<  7;
    const ITYPE_PING          = 1 <<  8;
    const ITYPE_SCRIPT        = 1 <<  9;
    const ITYPE_SUB_FRAME     = 1 << 11;
    const ITYPE_INLINE_SCRIPT = 1 << 15;

    const categoryCounters = { scripts: 0, pixels: 0, frames: 0, other: 0 };
    let categoryDirty = false;

    function bucketItype(itype) {
        if (itype === ITYPE_SCRIPT || itype === ITYPE_INLINE_SCRIPT) {
            return 'scripts';
        }
        if (itype === ITYPE_IMAGE || itype === ITYPE_BEACON || itype === ITYPE_PING) {
            return 'pixels';
        }
        if (itype === ITYPE_SUB_FRAME || itype === ITYPE_OBJECT) {
            return 'frames';
        }
        return 'other';
    }

    function pushCategoryStats() {
        try {
            browser.runtime.sendNativeMessage("ublock", {
                categoryBlocked: { ...categoryCounters }
            });
        } catch (_) { /* port not ready, picked up on next push */ }
    }

    async function saveCategoryStats() {
        if (!categoryDirty) { return; }
        categoryDirty = false;
        try {
            await vAPI.storage.set({ [CATEGORY_STORAGE_KEY]: { ...categoryCounters } });
        } catch (_) { /* best-effort; numbers are recoverable from next run */ }
    }

    // Restore persisted counters before installing the hook so we don't
    // start from zero on every cold start. Push immediately after so the
    // Java side has the cached totals before the first journalProcess
    // tick lands.
    vAPI.storage.get(CATEGORY_STORAGE_KEY).then(bin => {
        if (bin instanceof Object && bin[CATEGORY_STORAGE_KEY] instanceof Object) {
            const saved = bin[CATEGORY_STORAGE_KEY];
            for (const name of CATEGORY_NAMES) {
                if (typeof saved[name] === 'number' && saved[name] >= 0) {
                    categoryCounters[name] = saved[name];
                }
            }
            pushCategoryStats();
        }
    });

    /**************************************************************************
     * Top trackers list.
     *
     * Persistent map of {thirdPartyHostname: blockedCount} drawn from the
     * same journal triples we bucket for the category breakdown. The
     * TrackersInfoSheet renders the top 10 entries; first 'meaningful' use
     * appears once the map has at least three entries.
     *
     * Privacy contract:
     *   - Hard incognito carve-out. Tabs known to be private mode never
     *     contribute to the map. incognitoTabIds is seeded at startup from
     *     vAPI.tabs.query and kept in sync with browser.tabs.onCreated /
     *     .onRemoved listeners that read tab.incognito.
     *   - Data lives in vAPI.storage.local. Never crosses the native port
     *     except as an aggregated top-N list (no full map to Java side).
     *   - User can wipe the map in one tap from inside the sheet's 'Clear'
     *     button. Recording itself continues — toggling the feature off
     *     entirely is a future Settings-level control, not an in-sheet one.
     *
     * Eviction: TRACKER_MAP_CAP = 500. On overflow, drop the entry with the
     * smallest count. Approximates LFU without bookkeeping cost.
     *************************************************************************/
    const TRACKER_MAP_CAP = 500;
    const TOP_TRACKERS_PUSH = 10;
    const TRACKER_STORAGE_KEY = 'firedownTrackerBlocks';

    const trackerBlocks = new Map();
    const incognitoTabIds = new Set();
    let trackerDirty = false;

    // Seed and maintain incognitoTabIds. The journal hook gates on
    // incognitoTabIds.has(tabId) synchronously, so the set has to be
    // kept current — async vAPI.tabs.get(tabId) inside the hook would
    // race the journal commit. onCreated/onRemoved keep it in sync;
    // the initial query covers tabs that already exist when the
    // extension loads.
    function seedIncognitoTabs() {
        if (!vAPI.tabs || typeof vAPI.tabs.query !== 'function') { return; }
        vAPI.tabs.query({}).then(tabs => {
            if (Array.isArray(tabs) === false) { return; }
            for (const t of tabs) {
                if (t && t.incognito === true) { incognitoTabIds.add(t.id); }
            }
        }).catch(() => {});
    }
    if (browser.tabs && browser.tabs.onCreated) {
        browser.tabs.onCreated.addListener(tab => {
            if (tab && tab.incognito === true) { incognitoTabIds.add(tab.id); }
        });
        browser.tabs.onRemoved.addListener(tabId => {
            incognitoTabIds.delete(tabId);
        });
    }
    seedIncognitoTabs();

    function recordTrackerBlock(hostname) {
        if (!hostname || hostname === '') { return; }
        trackerBlocks.set(hostname, (trackerBlocks.get(hostname) || 0) + 1);
        trackerDirty = true;
        if (trackerBlocks.size <= TRACKER_MAP_CAP) { return; }
        // Overflow: evict the lowest-count entry. Walking the map is
        // O(n) but n ≤ 501 and eviction is rare (only when crossing
        // the cap), so the amortised cost is fine.
        let lowestHost = null;
        let lowestCount = Infinity;
        for (const [h, c] of trackerBlocks) {
            if (c < lowestCount) { lowestCount = c; lowestHost = h; }
        }
        if (lowestHost !== null) { trackerBlocks.delete(lowestHost); }
    }

    function topTrackersList() {
        const arr = Array.from(trackerBlocks, ([host, count]) => ({ host, count }));
        arr.sort((a, b) => b.count - a.count);
        return arr.slice(0, TOP_TRACKERS_PUSH);
    }

    function pushTopTrackers() {
        try {
            browser.runtime.sendNativeMessage("ublock", {
                topTrackers: topTrackersList(),
            });
        } catch (_) { /* port not ready */ }
    }

    async function saveTrackerState() {
        if (!trackerDirty) { return; }
        trackerDirty = false;
        try {
            const obj = {};
            for (const [h, c] of trackerBlocks) { obj[h] = c; }
            await vAPI.storage.set({ [TRACKER_STORAGE_KEY]: obj });
        } catch (_) { /* best-effort */ }
    }

    function clearTrackerBlocks() {
        trackerBlocks.clear();
        trackerDirty = true;
        saveTrackerState();
        pushTopTrackers();
    }

    // Restore persisted state.
    vAPI.storage.get(TRACKER_STORAGE_KEY).then(bin => {
        if (bin instanceof Object) {
            const map = bin[TRACKER_STORAGE_KEY];
            if (map instanceof Object) {
                for (const [h, c] of Object.entries(map)) {
                    if (typeof c === 'number' && c > 0) { trackerBlocks.set(h, c); }
                }
            }
        }
        pushTopTrackers();
    });

    // Hook journalProcess so we get the same (hostname, blocked, itype)
    // triples uBlock uses to update requestStats. Iterating the journal
    // before the original runs is safe — the original only mutates at
    // the end (journal.length = 0). One pass over the full journal so
    // entries before the navigation pivot (which uBlock still folds
    // into the cumulative count) are bucketed too.
    const originalJournalProcess = PageStore.prototype.journalProcess;
    PageStore.prototype.journalProcess = function() {
        const journal = this.journal;
        const isIncognito = incognitoTabIds.has(this.tabId);
        for (let i = 0; i < journal.length; i += 3) {
            if (journal[i + 1] !== 1) { continue; } // allowed
            categoryCounters[bucketItype(journal[i + 2])] += 1;
            categoryDirty = true;
            // Top trackers carve-out: only record from non-incognito tabs.
            // The category bucket above still counts incognito blocks
            // because it's an aggregate non-identifying number; the
            // tracker map is per-hostname and gets the harder boundary.
            if (isIncognito === false) {
                recordTrackerBlock(journal[i + 0]);
            }
        }
        return originalJournalProcess.call(this);
    };

    // Initial push as soon as the extension is ready, then hook
    // µb.updateToolbarIcon — uBlock calls it on every badge change,
    // which happens as new blocks land — so the Home 'trackers
    // blocked' card refreshes the moment the user returns from a
    // browsing tab instead of waiting for the 60s interval.
    //
    // Debounced 250ms so an ad-heavy page (dozens of blocks in a
    // burst) coalesces into a single native message rather than
    // storming the bus. The 60s interval is kept as a backstop for
    // anything that bypasses updateToolbarIcon. Category counters
    // piggyback on the same triggers — they're updated in the
    // journalProcess hook above, so any signal that's good enough
    // to refresh the cumulative total is good enough to refresh
    // the category breakdown.
    µb.isReadyPromise.then(() => {
        pushCumulativeStats();
        pushCategoryStats();
        pushTopTrackers();
        let pushTimer = null;
        const originalUpdateToolbarIcon = µb.updateToolbarIcon;
        if (typeof originalUpdateToolbarIcon === 'function') {
            µb.updateToolbarIcon = function(tabId, newParts) {
                const result = originalUpdateToolbarIcon.call(µb, tabId, newParts);
                if (pushTimer === null) {
                    pushTimer = setTimeout(() => {
                        pushTimer = null;
                        pushCumulativeStats();
                        pushCategoryStats();
                        pushTopTrackers();
                    }, 250);
                }
                return result;
            };
        }
    });
    setInterval(() => {
        pushCumulativeStats();
        pushCategoryStats();
        pushTopTrackers();
        saveCategoryStats();
        saveTrackerState();
    }, 60_000);

})();