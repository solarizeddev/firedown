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

    private static final String STYLE =
            "<style>"
            + "body{background:#141218;color:#e6e0e9;font-family:Roboto,system-ui,sans-serif;"
            + "margin:0;display:flex;justify-content:center;padding:48px 20px}"
            + ".card{width:100%;max-width:460px;text-align:center}"
            + ".brand{display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:26px}"
            + ".flame{width:30px;height:30px;border-radius:8px;background:linear-gradient(160deg,#ffb74d,#ff7043);"
            + "display:flex;align-items:center;justify-content:center;color:#3e2200;font-weight:700;font-size:17px}"
            + ".brand span{font-size:17px;font-weight:500;letter-spacing:.4px}"
            + "h1{font-weight:400;font-size:21px;margin:6px 0 8px}"
            + "p{color:#9a93a5;font-size:13.5px;line-height:1.6;margin:0 0 24px}"
            + "input[type=text]{width:170px;text-align:center;letter-spacing:10px;font-size:26px;"
            + "background:#211f26;border:1.5px solid #49454f;border-radius:12px;color:#e6e0e9;"
            + "padding:12px 0 12px 10px;outline:none;margin-bottom:22px}"
            + "input[type=text]:focus{border-color:#ffb74d}"
            + "button,.dl{background:#ffb74d;color:#3e2200;font-weight:500;border:0;border-radius:24px;"
            + "padding:13px 38px;font-size:15px;cursor:pointer;text-decoration:none;display:inline-block}"
            + ".file{display:flex;align-items:center;gap:16px;text-align:left;background:#211f26;"
            + "border-radius:16px;padding:16px 18px;margin:0 0 14px}"
            + ".file .ic{width:54px;height:54px;border-radius:12px;background:linear-gradient(135deg,#46395c,#2a2336);"
            + "flex:none;display:flex;align-items:center;justify-content:center;font-size:22px}"
            + ".file .meta{flex:1;min-width:0}.file .n{font-size:15px;word-break:break-word}"
            + ".file .s{font-size:12px;color:#9a93a5;margin-top:3px}"
            + ".file .dl{padding:9px 20px;font-size:13.5px;flex:none}"
            + ".foot{color:#6f6879;font-size:11.5px;margin-top:30px;line-height:1.6}"
            + ".lock{color:#7ad97a}.err{color:#f2b8b5;font-size:13px;margin:-12px 0 18px}"
            + "</style>";

    private static String shell(String inner) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Firedown</title>" + STYLE + "</head><body><div class=\"card\">"
                + "<div class=\"brand\"><div class=\"flame\">F</div><span>Firedown</span></div>"
                + inner + "</div></body></html>";
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
