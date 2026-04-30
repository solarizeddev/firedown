/**
 * Handle cookie requests from the native app.
 * Returns the cookie header string via native messaging.
 */
export async function handleCookieRequest(msg) {
    if (!msg.url) return;
    try {
        const cookies = await browser.cookies.getAll({ url: msg.url });
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
        console.error("Cookie fetch failed:", e);
        return null;
    }
}