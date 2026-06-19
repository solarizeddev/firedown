package com.solarized.firedown.utils;

import android.text.Html;
import android.text.TextUtils;

import com.solarized.firedown.data.entity.WebBookmarkEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Netscape Bookmark File Format — the de-facto interchange format every browser
 * (Firefox, Chrome, Edge, Safari…) reads and writes for "Export/Import
 * bookmarks". Pure (no DB / Android-context dependency beyond {@link Html} for
 * entity (un)escaping) so it stays unit-testable and the repository owns the
 * IO + threading + uid assignment.
 *
 * <p>Export shape (one flat list — Firedown has no folder hierarchy):</p>
 * <pre>
 *   &lt;!DOCTYPE NETSCAPE-Bookmark-file-1&gt;
 *   &lt;META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8"&gt;
 *   &lt;TITLE&gt;Bookmarks&lt;/TITLE&gt;
 *   &lt;H1&gt;Bookmarks&lt;/H1&gt;
 *   &lt;DL&gt;&lt;p&gt;
 *       &lt;DT&gt;&lt;A HREF="https://example.com" ADD_DATE="1700000000"&gt;Example&lt;/A&gt;
 *   &lt;/DL&gt;&lt;p&gt;
 * </pre>
 *
 * <p>Import is tolerant: it scans for {@code <A HREF="…">title</A>} anchors
 * anywhere in the document (folders / {@code <H3>} headers are simply ignored,
 * flattening any hierarchy), keeps only http/https links, decodes HTML entities
 * in the title, and reads {@code ADD_DATE} / {@code ICON_URI} when present. The
 * returned entities carry NO uid — the repository assigns the canonical id from
 * the URL on insert so an import merges with existing bookmarks by URL.</p>
 */
public final class BookmarkHtml {

    private BookmarkHtml() {}

    // Tolerant anchor scan: capture the tag's attributes and the inner label.
    private static final Pattern A_TAG =
            Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    private static final Pattern HREF =
            Pattern.compile("(?is)\\bhref\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ADD_DATE =
            Pattern.compile("(?is)\\badd_date\\s*=\\s*\"(\\d+)\"");
    private static final Pattern ICON_URI =
            Pattern.compile("(?is)\\bicon_uri\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");

    /** Serialize the bookmarks to a Netscape bookmark HTML document. */
    public static String export(List<WebBookmarkEntity> bookmarks) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n");
        sb.append("<!-- This is an automatically generated file. It will be read and overwritten. -->\n");
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n");
        sb.append("<TITLE>Bookmarks</TITLE>\n");
        sb.append("<H1>Bookmarks</H1>\n");
        sb.append("<DL><p>\n");
        if (bookmarks != null) {
            for (WebBookmarkEntity b : bookmarks) {
                if (b == null || TextUtils.isEmpty(b.getUrl())) continue;
                long addDateSec = (b.getDate() > 0 ? b.getDate() : System.currentTimeMillis()) / 1000L;
                String title = b.getTitle();
                if (TextUtils.isEmpty(title)) title = b.getUrl();
                sb.append("    <DT><A HREF=\"").append(escape(b.getUrl()))
                        .append("\" ADD_DATE=\"").append(addDateSec).append("\"");
                if (!TextUtils.isEmpty(b.getIcon())) {
                    sb.append(" ICON_URI=\"").append(escape(b.getIcon())).append("\"");
                }
                sb.append(">").append(escape(title)).append("</A>\n");
            }
        }
        sb.append("</DL><p>\n");
        return sb.toString();
    }

    /**
     * Parse anchors out of a Netscape bookmark HTML document. uid is left unset
     * (the repository assigns the canonical id from the URL); only http/https
     * links are kept.
     */
    public static List<WebBookmarkEntity> parse(String html) {
        List<WebBookmarkEntity> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return out;

        Matcher m = A_TAG.matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            String inner = m.group(2);

            Matcher h = HREF.matcher(attrs);
            if (!h.find()) continue;
            String url = unescape(h.group(1)).trim();
            if (TextUtils.isEmpty(url)) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) continue;

            WebBookmarkEntity e = new WebBookmarkEntity();
            e.setFileUrl(url);

            String title = unescape(stripTags(inner)).trim();
            e.setFileTitle(TextUtils.isEmpty(title) ? url : title);

            long date = System.currentTimeMillis();
            Matcher d = ADD_DATE.matcher(attrs);
            if (d.find()) {
                try {
                    date = Long.parseLong(d.group(1)) * 1000L;
                } catch (NumberFormatException ignore) {
                    // keep "now"
                }
            }
            e.setFileDate(date);

            Matcher ic = ICON_URI.matcher(attrs);
            if (ic.find()) {
                e.setFileIcon(unescape(ic.group(1)).trim());
            }

            out.add(e);
        }
        return out;
    }

    private static String escape(String s) {
        return Html.escapeHtml(s == null ? "" : s);
    }

    private static String stripTags(String s) {
        return s == null ? "" : TAGS.matcher(s).replaceAll("");
    }

    private static String unescape(String s) {
        if (TextUtils.isEmpty(s) || s.indexOf('&') < 0) return s == null ? "" : s;
        return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
    }
}
