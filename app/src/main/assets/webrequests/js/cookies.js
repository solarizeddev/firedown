import { DEBUG } from './debug.js';

/**
 * Handle cookie requests from the native app.
 * Returns the cookie header string via native messaging.
 */
export async function handleCookieRequest(msg) {
    if (!msg.url) return;
    try {
        // Java names the browsing mode of the tab the request came from; the
        // default jar is read only when it doesn't (an older caller).
        const query = { url: msg.url };
        if (typeof msg.incognito === "boolean") {
            query.storeId = msg.incognito ? "firefox-private" : "firefox-default";
        }
        const cookies = await browser.cookies.getAll(query);
        const cookieHeader = cookies
            .map(c => `${c.name}=${c.value}`)
            .join("; ");
        return {
            type: "cookiesResult",
            url: msg.url,
            id: msg.id,
            cookieHeader: cookieHeader
        };
    } catch (e) {
        if (DEBUG) console.error("Cookie fetch failed:", e);
        return null;
    }
}