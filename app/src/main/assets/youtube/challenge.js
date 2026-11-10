/**
 * challenge.js — reads the BotGuard challenge a YouTube page carries inline.
 *
 * YouTube's own player no longer asks for its attestation challenge: the
 * page's HTML carries it (`bgChallenge`: interpreter URL, program, global
 * name), inside a JS string literal whose quotes and braces are written as
 * \x22 / \x7b escapes, and the player attests with that one. The hidden
 * token page (content.js) takes the same challenge the same way, so its
 * token is made from what a real page of this client is handed today.
 *
 * Loaded before content.js on the same pages; defines one pure function.
 */

/**
 * @param {string} html a YouTube page
 * @returns {{bgChallenge: object, clientVersion: string|null}|null}
 */
function fdExtractInlineChallenge(html) {
    if (typeof html !== 'string') return null;
    const at = html.indexOf('bgChallenge');
    if (at < 0) return null;

    // A challenge program is tens of KB; the window is far past it.
    let text = html.substring(at, at + 400000);

    // Inside a JS string literal: undo the literal's escapes first. What is
    // left is JSON text (its own \uXXXX escapes are JSON.parse's to read).
    if (text.startsWith('bgChallenge\\x22') || text.startsWith('bgChallenge\\"')) {
        text = text.replace(/\\x([0-9a-fA-F]{2})|\\(.)/g, (m, hex, ch) => {
            if (hex) return String.fromCharCode(parseInt(hex, 16));
            return ch === 'n' ? '\n' : ch === 't' ? '\t' : ch === 'r' ? '\r' : ch;
        });
    }

    const open = text.indexOf('{');
    if (open < 0 || open > 20) return null;

    // The object's end: braces outside strings.
    let depth = 0;
    let inString = false;
    let end = -1;
    for (let i = open; i < text.length; i++) {
        const c = text.charCodeAt(i);
        if (inString) {
            if (c === 92) i++;                    // backslash: skip the next char
            else if (c === 34) inString = false;  // "
        } else if (c === 34) {
            inString = true;
        } else if (c === 123) {
            depth++;
        } else if (c === 125) {
            depth--;
            if (depth === 0) { end = i; break; }
        }
    }
    if (end < 0) return null;

    let challenge;
    try { challenge = JSON.parse(text.substring(open, end + 1)); }
    catch (e) { return null; }
    if (!challenge || typeof challenge.program !== 'string' || !challenge.program
            || typeof challenge.globalName !== 'string' || !challenge.globalName) {
        return null;
    }

    const version = html.match(/["']INNERTUBE_CLIENT_VERSION["']\s*:\s*["']([0-9][0-9.]{5,30})["']/)
        || html.match(/INNERTUBE_CLIENT_VERSION\\x22:\\x22([0-9][0-9.]{5,30})\\x22/);
    return { bgChallenge: challenge, clientVersion: version ? version[1] : null };
}
