// Startup — split out of the former parser-background.js. The per-site
// checkAndProcessXxxUrl calls moved into their site modules, which register
// them on common.js's SPA registry; existing-tab processing iterates that
// same registry, so a new site parser only registers once and is covered
// both for live navigation and for tabs already open at extension boot.
import { log, cacheTabUrl, __tabStateCount, runSpaHandlers } from './common.js';

async function handleExistingTabs() {
    try {
        const tabs = await browser.tabs.query({});
        for (const tab of tabs) {
            if (tab.url && tab.id >= 0) {
                cacheTabUrl(tab.url, tab.id);
                runSpaHandlers(tab.url, tab.id);
            }
        }
        log("INIT", `Cached URLs for ${__tabStateCount()} tab state(s) from ${tabs.length} existing tabs`);
    } catch (e) {
        log("INIT", `Error checking existing tabs`, e.message);
    }
}

log("INIT", `Video parser extension loaded (Instagram, Facebook, Twitter/X, Vimeo, Kick, Twitch, Dailymotion)`);
handleExistingTabs();
