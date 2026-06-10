package com.solarized.firedown.lanshare;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Locale;

/**
 * The HTML pages {@link LanShareServer} serves — Firedown-styled, dark,
 * dependency-free (inline CSS, no JS beyond the PIN form's input hop).
 * English-only by design for v1: the RECEIVER's locale is unknown (any
 * browser on the LAN), so localizing to the sender's locale would be wrong
 * as often as right.
 *
 * <p>Every interpolated value goes through {@link #escapeHtml} — file names
 * are user/site-controlled.
 */
final class LanSharePages {

    private LanSharePages() {
    }

    // Palette + type mirror the firedown.app marketing site (style.css):
    // warm off-white content background, the signature coral brand gradient
    // (#d44e86 → #f06560 → #f5a97a), IBM Plex Sans / Mono with a system
    // fallback (the LAN receiver may be offline, so the @import degrades
    // gracefully).
    private static final String STYLE =
            "<style>"
            + "@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@500;600&family=IBM+Plex+Sans:ital,wght@0,300;0,400;0,600;1,600&display=swap');"
            + "*{box-sizing:border-box}"
            + "body{background:#f7f3ef;color:#1e1c2e;font-family:'IBM Plex Sans',system-ui,sans-serif;"
            + "margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;"
            + "padding:0 20px 40px}"
            // Signature coral gradient hero with the site's radial glow overlay.
            + ".hero{width:100%;position:relative;"
            + "background:radial-gradient(ellipse at 72% 28%,rgba(255,255,255,.14),transparent 60%),"
            + "linear-gradient(150deg,#d44e86 0%,#f06560 55%,#f5a97a 100%);"
            + "display:flex;align-items:center;justify-content:center;"
            + "padding:38px 20px 50px;margin-bottom:-26px}"
            + ".brand{display:flex;align-items:center;gap:12px}"
            + ".flame{width:40px;height:40px;border-radius:11px;background:#fff;"
            + "display:flex;align-items:center;justify-content:center;color:#f06560;font-weight:700;font-size:24px}"
            + ".brand span{font-size:22px;font-weight:600;letter-spacing:-.01em;color:#fff}"
            + ".card{width:100%;max-width:460px;text-align:center;background:#fff;"
            + "border-radius:24px;padding:32px 26px;box-shadow:0 16px 60px rgba(30,28,46,.16)}"
            + ".eyebrow{font-family:'IBM Plex Mono',monospace;font-size:.7rem;font-weight:600;"
            + "letter-spacing:.18em;text-transform:uppercase;color:#d44e86;margin-bottom:10px}"
            + "h1{font-weight:600;font-size:22px;margin:4px 0 8px;color:#1e1c2e;letter-spacing:-.02em}"
            + "p{color:#4a475e;font-size:13.5px;line-height:1.6;margin:0 0 24px}"
            + "input[type=text]{width:190px;text-align:center;letter-spacing:12px;font-size:28px;"
            + "font-family:'IBM Plex Mono',monospace;background:#f7f3ef;border:2px solid rgba(30,28,46,.12);"
            + "border-radius:14px;color:#1e1c2e;padding:13px 0 13px 12px;outline:none;margin-bottom:24px}"
            + "input[type=text]:focus{border-color:#f06560}"
            + "button,.dl{background:linear-gradient(135deg,#d44e86,#f06560);color:#fff;font-weight:600;"
            + "border:0;border-radius:24px;padding:14px 40px;font-size:15px;cursor:pointer;"
            + "text-decoration:none;display:inline-block;box-shadow:0 8px 28px rgba(212,78,134,.32)}"
            + "button:hover,.dl:hover{transform:translateY(-2px)}"
            + ".file{display:flex;align-items:center;gap:16px;text-align:left;background:#faf7f4;"
            + "border:1px solid rgba(30,28,46,.09);border-radius:16px;padding:16px 18px;margin:0 0 14px}"
            + ".file .ic{width:54px;height:54px;border-radius:12px;"
            + "background:linear-gradient(135deg,#d44e86,#f06560);"
            + "flex:none;display:flex;align-items:center;justify-content:center;font-size:24px}"
            + ".file .meta{flex:1;min-width:0}.file .n{font-size:15px;color:#1e1c2e;word-break:break-word}"
            + ".file .s{font-size:12px;color:#8a8898;margin-top:3px;font-family:'IBM Plex Mono',monospace}"
            + ".file .dl{padding:10px 22px;font-size:13.5px;flex:none}"
            + ".foot{color:#8a8898;font-size:11.5px;margin-top:28px;line-height:1.6}"
            + ".lock{color:#d44e86}.err{color:#c0436b;font-size:13px;margin:-12px 0 18px;font-weight:600}"
            // "Get Firedown" cross-promo: a receiver on a plain browser (esp.
            // Android, without the app) can grab it; on a device that then has
            // Firedown the QR resolves in-app next time. Secondary styling so
            // it never competes with the Download CTA.
            + ".get{display:block;margin-top:22px;color:#d44e86;font-size:13px;font-weight:600;"
            + "text-decoration:none}.get span{color:#8a8898;font-weight:400}"
            + "</style>";

    /** Official site — hosts the download and the store links (F-Droid,
     *  Zapstore, Obtainium, direct APK). */
    private static final String WEBSITE_URL = "https://firedown.app/install";

    private static String shell(String inner) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"theme-color\" content=\"#f06560\">"
                + "<title>Firedown</title>" + STYLE + "</head><body>"
                + "<div class=\"hero\"><div class=\"brand\"><div class=\"flame\">F</div>"
                + "<span>Firedown</span></div></div>"
                + "<div class=\"card\">" + inner + "</div></body></html>";
    }

    /** PIN entry. {@code wrongAttempt} shows the error line + remaining count. */
    static String pinGate(@NonNull String deviceName, boolean wrongAttempt, int attemptsLeft) {
        StringBuilder inner = new StringBuilder()
                .append("<h1>Enter the PIN</h1>")
                .append("<p>It&#8217;s showing on the sender&#8217;s screen.</p>");
        if (wrongAttempt) {
            inner.append("<div class=\"err\">Wrong PIN — ")
                    .append(Math.max(attemptsLeft, 0))
                    .append(attemptsLeft == 1 ? " attempt" : " attempts")
                    .append(" left before this session locks</div>");
        }
        inner.append("<form method=\"post\" action=\"/pin\">")
                .append("<input type=\"text\" name=\"pin\" inputmode=\"numeric\" maxlength=\"4\" ")
                .append("autocomplete=\"one-time-code\" autofocus><br>")
                .append("<button type=\"submit\">Continue</button></form>")
                .append("<div class=\"foot\">Served by Firedown on ")
                .append(escapeHtml(deviceName))
                .append(" &middot; local network only</div>");
        return shell(inner.toString());
    }

    static String fileList(@NonNull String deviceName, @NonNull List<LanShareServer.SharedFile> files) {
        StringBuilder inner = new StringBuilder()
                .append("<h1 style=\"text-align:left\">Shared with you</h1>")
                .append("<p style=\"text-align:left\">From ").append(escapeHtml(deviceName))
                .append(" &middot; ").append(files.size())
                .append(files.size() == 1 ? " file" : " files").append("</p>");
        for (int i = 0; i < files.size(); i++) {
            LanShareServer.SharedFile shared = files.get(i);
            inner.append("<div class=\"file\"><div class=\"ic\">")
                    .append(iconFor(shared.mime))
                    .append("</div><div class=\"meta\"><div class=\"n\">")
                    .append(escapeHtml(shared.name))
                    .append("</div><div class=\"s\">")
                    .append(escapeHtml(shared.mime)).append(" &middot; ")
                    .append(formatSize(shared.file.length()))
                    .append("</div></div><a class=\"dl\" href=\"/f/").append(i)
                    .append("\" download>Download</a></div>");
        }
        inner.append("<div class=\"foot\"><span class=\"lock\">&#9679;</span> PIN-verified session ")
                .append("&middot; expires when the sender stops sharing<br>")
                .append("Transfer never leaves your local network</div>");
        inner.append("<a class=\"get\" href=\"").append(WEBSITE_URL)
                .append("\"><span>Don&#8217;t have Firedown?</span> Get the app &rarr;</a>");
        return shell(inner.toString());
    }

    static String locked() {
        return shell("<h1>Session locked</h1>"
                + "<p>Too many wrong PIN attempts.<br>"
                + "Ask the sender to stop and share again for a fresh PIN.</p>");
    }

    static String notFound() {
        return shell("<h1>Not found</h1><p>This share doesn&#8217;t have that file.</p>");
    }

    private static String iconFor(String mime) {
        if (mime == null) {
            return "&#128196;";
        }
        if (mime.startsWith("video/")) {
            return "&#127916;";
        }
        if (mime.startsWith("audio/")) {
            return "&#127911;";
        }
        if (mime.startsWith("image/")) {
            return "&#128247;";
        }
        return "&#128196;";
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        }
        if (bytes >= 1L << 10) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / (double) (1L << 10));
        }
        return bytes + " B";
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
