// Entry point for the per-site parsers (the former monolithic
// parser-background.js, split into ES modules). Each site module
// self-registers its webRequest/webNavigation listeners at import time and
// plugs into common.js's SPA-navigation registry and message router.
// boot.js is imported LAST: its existing-tab sweep iterates the SPA registry,
// so every site module must have registered before it runs.
import './common.js';
import './vimeo.js';
import './apple-podcasts.js';
import './newsoveraudio.js';
import './tiktok.js';
import './twitter.js';
import './bluesky.js';
import './kick.js';
import './twitch.js';
import './dailymotion.js';
import './instagram.js';
import './threads.js';
import './rumble.js';
import './page-state.js';
import './niconico.js';
import './facebook.js';
import './telegram.js';
import './videee.js';
import './spotify.js';
import './boot.js';
