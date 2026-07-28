package com.solarized.firedown.utils;


import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;


import com.solarized.firedown.data.di.NetworkModule;


import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.solarized.firedown.okhttp.SafeHeaders;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public class WebUtils {

    private static final String TAG = WebUtils.class.getName();

    private static final Set<String> RANGE_PARAMS = Set.of("bytes", "bytestart", "byteend", "range", "_HLS_msn", "_HLS_part", "start_seq", "r_range");

    private static final Pattern META_PATTERN = Pattern.compile(
            "<meta\\s[^>]*?(?:property|name)\\s*=\\s*[\"']([^\"']*)[\"'][^>]*?content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern META_PATTERN_REVERSED = Pattern.compile(
            "<meta\\s[^>]*?content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*?(?:property|name)\\s*=\\s*[\"']([^\"']*)[\"'][^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SHREDDIT_PATTERN = Pattern.compile(
            "<shreddit-title\\s[^>]*?title\\s*=\\s*[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE
    );


    /*
     * ── Bounded whole-body reads ────────────────────────────────────────
     *
     * ResponseBody.string()/bytes() are UNBOUNDED — they buffer whatever the
     * server chose to send. Every caller below passes a REMOTE url we do not
     * control (an HLS master, an SVG, a page we scrape a title from, a JSON
     * API), so a url that has rotated onto the media itself, an oversized
     * error page, or a hostile/compressed-bomb response allocates the entire
     * body in one go — .string() peaking at roughly the byte count plus two
     * bytes per char. On the 256 MB heap of issue #300 that is a direct OOM,
     * and unlike a leak it needs no accumulation to get there.
     *
     * The bound is applied with Response.peekBody(n), which buffers at most n
     * bytes from a fresh peek of the source. Two properties make it the right
     * tool: it never touches the socket beyond n, and the body it returns
     * carries the original Content-Type, so charset handling stays byte-for-
     * byte what .string() already did. It also sits AFTER GzipInterceptor in
     * the chain, so the cap counts DECOMPRESSED bytes — which is what makes
     * it a defence against a compressed bomb rather than a formality.
     *
     * HttpDownloadStrategy's manifest sniff already reads this way
     * (peekBody(2048)); these are the remaining unbounded reads.
     */

    /** Playlists, SVGs, JSON API replies — content we parse whole. */
    private static final long MAX_TEXT_BODY_BYTES = 8L * 1024 * 1024;

    /** HTML we only scrape metadata out of. */
    private static final long MAX_HTML_BODY_BYTES = 2L * 1024 * 1024;

    /** Binary API replies (Mega's file-attribute server). */
    private static final long MAX_BINARY_BODY_BYTES = 8L * 1024 * 1024;

    /**
     * Read a body whole, or refuse it.
     *
     * For content we hand to a PARSER, a truncated prefix is worse than
     * nothing: half an m3u8 enumerates a partial, plausible-looking variant
     * list and nothing downstream can tell it was cut. So an over-cap body
     * yields null and the caller falls back to its empty result.
     *
     * @return the body text, or null if it exceeds {@code maxBytes}
     */
    private static String readWholeOrNull(Response response, long maxBytes, String what)
            throws IOException {
        // maxBytes + 1 so "exactly at the cap" is distinguishable from
        // "overflowed" without a second peek.
        ResponseBody peeked = response.peekBody(maxBytes + 1);
        if (peeked.contentLength() > maxBytes) {
            Log.w(TAG, what + ": body over " + maxBytes + " bytes — refusing to buffer it");
            return null;
        }
        return peeked.string();
    }

    /**
     * Read up to {@code maxBytes} of a body, keeping whatever prefix arrives.
     *
     * The opposite trade from {@link #readWholeOrNull}, and only correct for
     * SCRAPING: og:/twitter: tags live in the head, so a truncated page still
     * yields the right title far more often than it yields a wrong one, and
     * the alternative on an oversized page is no metadata at all.
     */
    private static String readPrefix(Response response, long maxBytes, String what)
            throws IOException {
        ResponseBody peeked = response.peekBody(maxBytes);
        if (peeked.contentLength() >= maxBytes) {
            Log.w(TAG, what + ": body hit the " + maxBytes
                    + " byte cap — scraping the prefix only");
        }
        return peeked.string();
    }

    public static String bodyToString(final RequestBody request) {
        try {
            if (request == null) {
                return "";
            }
            final Buffer buffer = new Buffer();
            request.writeTo(buffer);
            return buffer.readUtf8();
        } catch (final IOException e) {
            return "did not work";
        }
    }

    /*This is blunter but honest — if it has spaces, a human wrote it.
    If it doesn't, it's probably a URL slug or CDN hash.
    I'd go with this one. Simple, no false positives.*/
    public static boolean isUrlDerivedName(String name) {
        if (TextUtils.isEmpty(name)) return true;
        // Names extracted from URLs never contain spaces
        return !name.contains(" ");
    }

    /**
     * Maximum number of characters we keep from a page title before
     * truncating. 80 is a balance between "still readable" and "won't
     * trip the 255-byte filesystem limit even after UTF-8 expansion".
     */
    private static final int MAX_TITLE_FILENAME_LEN = 80;

    /**
     * Cleans a page title so it's safe to use as (or as part of) a
     * filename across Android, Windows, macOS, and common cloud-sync
     * targets (Google Drive / OneDrive / Dropbox round-trip).
     *
     * Operations, in order:
     *   1. Strip control chars and zero-width unicode (a lot of CMSes
     *      emit U+200B / U+FEFF / trailing emoji modifiers).
     *   2. Replace Windows-reserved characters (\ / : * ? " &lt; &gt; |)
     *      with a single space. Android allows ":" but external sync
     *      to Windows/SD/cloud doesn't.
     *   3. Strip common site-name suffix tails ("— YouTube",
     *      "- Twitter", "| Vimeo"). Driven off the page's hostname so
     *      we don't mis-strip legitimate text.
     *   4. Collapse whitespace runs.
     *   5. Cap length to {@value #MAX_TITLE_FILENAME_LEN} chars on a
     *      word boundary where possible.
     *
     * Returns null if the input is empty, becomes empty after cleaning,
     * or — after sanitisation — looks indistinguishable from a URL slug
     * (callers fall back to the resource name in that case).
     */
    public static String sanitizeTitleForFilename(String title, String hostname) {
        if (TextUtils.isEmpty(title)) return null;

        // 1. Strip what is not really a character (controls, zero-width, bidi,
        //    non-BMP). Done first so nothing invisible hides inside the
        //    separator run the next step matches on.
        String s = FileUriHelper.stripInvisible(title);

        // 2. Site-name suffix ("… | bilibili", "… - YouTube"), pulled off the
        //    hostname's middle label and matched only at the END of the title.
        //
        //    This MUST run BEFORE illegal characters are replaced. '|' and ':'
        //    are themselves illegal, so replacing them first turned the
        //    separator into a space and this pattern then matched nothing —
        //    silently, and for the two most common separators there are. The
        //    result was that "Title - Site" was cleaned while "Title | Site"
        //    and "Title : Site" kept the site noise forever.
        String siteName = extractSiteName(hostname);
        if (siteName != null) {
            // (?i) for case-insensitive; allow surrounding whitespace.
            // The class covers hyphen, en/em dash, pipe, colon, middle dot and
            // bullet — the separators sites actually use in <title>.
            String pattern = "(?i)\\s*[-\u2013\u2014|:\u00B7\u2022]\\s*" + Pattern.quote(siteName) + "\\s*$";
            s = s.replaceAll(pattern, "");
        }

        // 3. NOW make the remaining characters filesystem-safe.
        s = FileUriHelper.replaceIllegalChars(s);

        // 4. Collapse whitespace.
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;

        // 5. Length cap on word boundary.
        if (s.length() > MAX_TITLE_FILENAME_LEN) {
            String trunc = s.substring(0, MAX_TITLE_FILENAME_LEN);
            int lastSpace = trunc.lastIndexOf(' ');
            if (lastSpace > MAX_TITLE_FILENAME_LEN / 2) {
                trunc = trunc.substring(0, lastSpace);
            }
            s = trunc.trim();
        }

        // Title that survived but still looks slug-y — caller is better off
        // with the URL-derived resource name.
        if (looksLikeSlug(s)) return null;
        return s;
    }

    /**
     * Whether a cleaned title still looks like a URL slug rather than a real
     * title: no spaces AND all-lowercase ASCII AND short.
     *
     * All three parts matter together. This used to delegate to
     * {@link #isUrlDerivedName}, which tests only the no-spaces half — so every
     * legitimate ONE-WORD title was thrown away and replaced by the URL slug.
     * That hit hardest right after a successful site-suffix strip, which is
     * precisely when a one-word result is most likely ("Clip | YouTube" cleaned
     * to "Clip", then discarded). isUrlDerivedName itself is unchanged: "no
     * spaces means it came from a URL" is the right test for its own callers,
     * it was just never the test this wanted.
     */
    private static boolean looksLikeSlug(String s) {
        return s.length() <= 16 && s.matches("[a-z0-9._-]+");
    }

    private static String extractSiteName(String hostname) {
        if (TextUtils.isEmpty(hostname)) return null;
        String host = hostname.toLowerCase();
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.startsWith("m.")) host = host.substring(2);
        int dot = host.indexOf('.');
        if (dot <= 0) return null;
        String label = host.substring(0, dot);
        if (label.length() < 2) return null;
        // Special-case "x" since it's not very evocative as a suffix
        // and would mis-match on a lot of titles ending with " - X".
        // Drop the suffix-stripping for it.
        if ("x".equals(label)) return null;
        return label.substring(0, 1).toUpperCase() + label.substring(1);
    }

    public static String decodeString(String URL)
    {

        String urlString="";
        try {
            if(BuildUtils.hasAndroidTiramisu()){
                urlString = URLDecoder.decode(URL, StandardCharsets.UTF_8);
            }else{
                urlString = URLDecoder.decode(URL,"UTF-8");
            }
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block

        }
        return urlString;

    }


    public static String deParameterize(String uri) {

        if(uri == null)
            return uri;

        int questionMarkIndex = uri.lastIndexOf('?');

        // If there are no query parameters, return the original URI
        if (questionMarkIndex == -1) {
            return uri;
        }

        String baseUrl = uri.substring(0, questionMarkIndex);
        String queryString = uri.substring(questionMarkIndex + 1);
        String[] params = queryString.split("&");

        StringJoiner newQueryString = new StringJoiner("&");

        for (String p : params) {
            int equalIndex = p.indexOf('=');
            String key = (equalIndex == -1) ? p : p.substring(0, equalIndex);

            // Only add the parameter back if it is NOT in our blacklist
            if (!RANGE_PARAMS.contains(key)) {
                newQueryString.add(p);
            }
        }

        String resultQuery = newQueryString.toString();

        // Return base URL + the filtered query string (if any remains)
        return resultQuery.isEmpty() ? baseUrl : baseUrl + "?" + resultQuery;
    }



    public static String getProtocolUrl(String url1) {
        try {
            URL url = new URL(url1);
            String protocol = url.getProtocol();
            String authority = url.getAuthority();
            return String.format("%s://%s", protocol, authority);
        } catch (MalformedURLException | IllegalArgumentException e) {
            Log.w(TAG, "getProcolUrl", e);
        }
        return url1;
    }


    public static String getString(String url, Map<String, String> headers) {
        Log.d(TAG, "getString: " + url);
        Response httpResponse = null;
        ResponseBody responseBody = null;
        String string = "";
        try {

            Request request = new Request.Builder()
                    .headers(SafeHeaders.of(headers))
                    .url(url)
                    .build();

            httpResponse = NetworkModule.requireClient().newCall(request).execute();

            responseBody = httpResponse.body();

            // Callers feed this straight to a parser (the HLS master in
            // GeckoInspectTask.processHlsMaster, an SVG) — a truncated prefix
            // would parse "successfully" into a wrong answer, so over-cap
            // bodies fall through to the empty return below.
            String whole = readWholeOrNull(httpResponse, MAX_TEXT_BODY_BYTES, "getString");
            if (whole != null) {
                return whole;
            }

        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            Log.w(TAG, "getString", e);
        } finally {
            if(responseBody != null)
                responseBody.close();
            if (httpResponse != null)
                httpResponse.close();

        }
        return string;
    }


    public static String getTitle(String url) {
        Log.d(TAG, "getTitle: " + url);
        Response httpResponse = null;
        ResponseBody responseBody = null;
        String title = "";
        try {

            Request request = new Request.Builder()
                    .url(url)
                    .build();
            httpResponse = NetworkModule.requireClient().newCall(request).execute();
            responseBody = httpResponse.body();
            Log.d(TAG, "getTitle mimeType: " + responseBody.contentType());
            MediaType mediaType = responseBody.contentType();
            if (mediaType == null || !mediaType.toString().contains(FileUriHelper.MIMETYPE_HTML)) {
                Log.w(TAG, "getTitle incorrect mime");
                return "";
            }
            // Metadata scrape, not a parse: keep the prefix of an oversized
            // page rather than dropping it (see readPrefix). This also bounds
            // the three DOTALL regexes below, which run over the whole string.
            String html = readPrefix(httpResponse, MAX_HTML_BODY_BYTES, "getTitle");

            String ogTitle = null;
            String ogDescription = null;
            String twitterDescription = null;

            Matcher m = META_PATTERN.matcher(html);
            while (m.find()) {
                String key = m.group(1);
                String value = m.group(2);
                if (key == null || value == null) continue;
                switch (key.trim().toLowerCase()) {
                    case "og:title" -> ogTitle = value;
                    case "og:description" -> ogDescription = value;
                    case "twitter:description" -> twitterDescription = value;
                }
            }

            m = META_PATTERN_REVERSED.matcher(html);
            while (m.find()) {
                String value = m.group(1);
                String key = m.group(2);
                if (key == null || value == null) continue;
                key = key.trim().toLowerCase();
                if (ogTitle == null && key.equals("og:title")) ogTitle = value;
                if (ogDescription == null && key.equals("og:description")) ogDescription = value;
                if (twitterDescription == null && key.equals("twitter:description")) twitterDescription = value;
            }

            if (ogTitle != null) title = ogTitle;
            else if (ogDescription != null) title = ogDescription;
            else if (twitterDescription != null) title = twitterDescription;

            m = SHREDDIT_PATTERN.matcher(html);
            if (m.find() && m.group(1) != null) {
                title = m.group(1);
            }

            Log.d(TAG, "WebUtils title: " + title);
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            Log.w(TAG, "getTitle", e);
        } finally {
            if (responseBody != null) responseBody.close();
            if (httpResponse != null) httpResponse.close();
        }
        return title;
    }


    public static String getMimeType(String url, Map<String, String> headers) {
        Response response = null;
        ResponseBody responseBody = null;
        try {

            Request request = new Request.Builder()
                    .headers(SafeHeaders.of(headers))
                    .url(url)
                    .build();

            response = NetworkModule.requireClient().newCall(request).execute();

            responseBody = response.body();

            MediaType mediaType = responseBody.contentType();

            if (mediaType == null) {
                return FileUriHelper.MIMETYPE_UNKNOWN;
            }
            return mediaType.toString();
        }catch (IOException e){
            Log.e(TAG, "getMimeType", e);
        } finally {
            if(responseBody != null){
                responseBody.close();
            }
            if(response != null)
                response.close();
        }
        return FileUriHelper.MIMETYPE_UNKNOWN;

    }


    public static String getFileNameFromURL(String url) {

        try {

            if (TextUtils.isEmpty(url)) {
                return UUID.randomUUID().toString();
            }

            URL resource = new URL(url);
            String host = resource.getHost();
            if (!host.isEmpty() && url.endsWith(host)) {
                // handle ...example.com
                return UUID.randomUUID().toString();
            }

            // find end index for ?
            int lastQMPos = url.lastIndexOf('?');
            if (lastQMPos > 0) {
                url = url.substring(0, lastQMPos);
            }

            // find end index for #
            int lastHashPos = url.lastIndexOf('#');
            if (lastHashPos > 0) {
                url = url.substring(0, lastHashPos);
            }

            int startIndex = url.lastIndexOf('/') + 1;

            if(startIndex > 0){
                url = url.substring(startIndex);
            }


            if(TextUtils.isEmpty(url)){
                return UUID.randomUUID().toString();
            }

            return url;

        } catch (MalformedURLException | StringIndexOutOfBoundsException e) {
            Log.w(TAG, "getFileNameFromURL", e);
        }
        return UUID.randomUUID().toString();
    }


    public static String getFileNameFromDisposition(String content){

        Log.d(TAG, "getFileNameFromDisposition: " + content);

        if(content != null && content.contains("filename=")){
            return content.replaceFirst("(?i)^.*filename=\"?([^\"]+)\"?.*$", "$1");
        }

        return null;
    }


    public static String getMimeType(ResponseBody body) {
        if (body == null) {
            return FileUriHelper.MIMETYPE_UNKNOWN;
        }
        MediaType mediaType = body.contentType();
        if (mediaType == null) {
            return FileUriHelper.MIMETYPE_UNKNOWN;
        }
        String mime = mediaType.toString();
        int index = mime.indexOf(";");
        if(index > 0){
            mime = mime.substring(0, index);
        }
        return mime;
    }


    public static String postContent(String url, String post, Map<String, String> headers) throws IOException, IllegalArgumentException {        Response httpResponse = null;
        try {

            RequestBody reqbody = null;

            if (TextUtils.isEmpty(post)) {
                reqbody = RequestBody.create(new byte[0], null);
            } else {
                reqbody = RequestBody.create(post, MediaType.parse("text/plain"));
            }

            Request request = new Request.Builder()
                    .headers(SafeHeaders.of(headers))
                    .url(url)
                    .post(reqbody)
                    .header("Content-Length", String.valueOf(reqbody.contentLength()))
                    .build();

            httpResponse = NetworkModule.requireClient().newCall(request).execute();

            // JSON API replies (Mega, GeckoInspectTask) — parsed whole, so an
            // over-cap body is refused rather than truncated.
            String whole = readWholeOrNull(httpResponse, MAX_TEXT_BODY_BYTES, "postContent");
            if (whole == null) {
                throw new IOException("postContent: response body over "
                        + MAX_TEXT_BODY_BYTES + " bytes");
            }
            return whole;

        } finally {
            if (httpResponse != null)
                httpResponse.close();
        }
    }


    /**
     * Binary POST — raw bytes in, raw bytes out. Unlike {@link #postContent}
     * (which calls {@code body.string()} and would corrupt non-UTF-8 data), this
     * preserves the response verbatim. Used for Mega's file-attribute server,
     * which speaks a binary framing (handle + length + AES-CBC JPEG), not text.
     */
    public static byte[] postBytes(String url, byte[] body) throws IOException, IllegalArgumentException {
        Response response = null;
        try {
            RequestBody reqBody = RequestBody.create(body != null ? body : new byte[0], null);
            Request request = new Request.Builder()
                    .url(url)
                    .post(reqBody)
                    .build();
            response = NetworkModule.requireClient().newCall(request).execute();
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return null;
            }
            // Mega's file-attribute replies are a handful of KB; the cap is
            // only here so a rotated/hostile endpoint cannot hand us an
            // arbitrarily large byte[].
            ResponseBody peeked = response.peekBody(MAX_BINARY_BODY_BYTES + 1);
            if (peeked.contentLength() > MAX_BINARY_BODY_BYTES) {
                throw new IOException("postBytes: response body over "
                        + MAX_BINARY_BODY_BYTES + " bytes");
            }
            return peeked.bytes();
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    public static String getDomainName(String url) {
        try{
            if(!URLUtil.isValidUrl(url))
                return url;
            URI uri = new URI(url);
            String host = uri.getHost();
            return host.replaceFirst("^(www\\.)", "");
        }catch(NullPointerException | URISyntaxException | IllegalStateException | IllegalArgumentException e){
            try{
                URL aurl = new URL(url);
                return aurl.getHost();
            }catch(MalformedURLException e1){
                Log.e(TAG,"getDomainName", e1);
            }

        }
        return url;
    }

    public static boolean isBlob(String url){
        return !TextUtils.isEmpty(url) && url.startsWith("blob:");
    }

    public static long getLengthFromHeaders(String contentLength){
        try{
            if(contentLength != null){
                return Long.parseLong(contentLength);
            }
        }catch (NumberFormatException e){
            Log.w(TAG, "getLengthFromHeaders");
        }
        return 0;
    }

    public static String getUriPath(String url){
        try{
            URI uri = new URI(url);
            return uri.getPath();
        }catch(NullPointerException | URISyntaxException e){
            return url.replaceFirst("^(http[s]?://www\\.|http[s]?://|www\\.)","");
        }
    }

    public static String getSchemeDomainName(String url) {
        try{
            URL aurl = new URL(url);
            String authority = aurl.getAuthority();
            String protocol = aurl.getProtocol();
            Log.d(TAG, "getSchemeDomainName:" + authority + " protocol: " + protocol);
            if (protocol != null && authority != null) {
                return String.format("%s://%s", protocol, authority);
            } else {
                return url;
            }
        }catch(MalformedURLException  e){
            Log.e(TAG, "getSchemeDomainName", e);
        }
        return url;
    }


    public static String getUriNoScheme(String url) {
        try{
            URI uri = new URI(url);
            return uri.getHost();
        }catch(NullPointerException | URISyntaxException e){
            try{
                URL aurl = new URL(url);
                return aurl.getHost();
            }catch(MalformedURLException e1){
                Log.e(TAG,"getDomainName", e1);
            }

        }
        return url;
    }


}
