package com.solarized.firedown.utils;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Patterns;

import androidx.annotation.VisibleForTesting;
import androidx.core.text.TextDirectionHeuristicCompat;
import androidx.core.text.TextDirectionHeuristicsCompat;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlStringUtils {

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    private static final int UNICODE_CHARACTER_CLASS = 0x100;

    // To run tests on a non-Android device (like a computer), Pattern.compile
    // requires a flag to enable unicode support. Set a value like flags here with a local
    // copy of UNICODE_CHARACTER_CLASS. Use a local copy because that constant is not
    // available on Android platforms < 24 (Fenix targets 21). At runtime this is not an issue
    // because, again, Android REs are always unicode compliant.
    // NB: The value has to go through an intermediate variable; otherwise, the linter will
    // complain that this value is not one of the predefined enums that are allowed.
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    private static final int flags = 0;

    private static final String HTTP = "http://";

    private static final String HTTPS = "https://";

    private static final String FILE = "file://";

    private static final String WWW = "www.";

    private static final String DATA = "data:text/html";

    private static final String RESOURCE = "resource://";

    private static final String ABOUT_BLANK = "about:blank";

    private static final String VIEW_SOURCE = "view-source:";

    private static final String MOZ_EXTENSION = "moz-extension://";

    private static final Pattern SVG_PATTERN = Pattern.compile("https?://.*\\.(:?svg)(?:\\?.*|$)");

    private static final Pattern ICO_PATTERN = Pattern.compile("https?://.*\\.(:?ico)(?:\\?.*|$)");

    private static final Pattern ADAPTATIVE_PATTERN = Pattern.compile("https?://.*\\.(:?m3u8)(?:\\?.*|$)");

    // Be lenient about what is classified as potentially a URL.
    // (\w+-+)*\w+(://[/]*|:|\.)(\w+-+)*\w+([\S&&[^\w-]]\S*)?
    // -------                 -------
    // 0 or more pairs of consecutive word letters or dashes
    //        ---                     ---
    // followed by at least a single word letter.
    // -----------             ----------
    // Combined, that means "w", "w-w", "w-w-w", etc match, but "w-", "w-w-", "w-w-w-" do not.
    //          --------------
    // That surrounds :, :// or .
    //                                                    -
    // At the end, there may be an optional
    //                                    ------------
    // non-word, non-- but still non-space character (e.g., ':', '/', '.', '?' but not 'a', '-', '\t')
    //                                                ---
    // and 0 or more non-space characters.
    //
    // These are some (odd) examples of valid urls according to this pattern:
    // c-c.com
    // c-c-c-c.c-c-c
    // c-http://c.com
    // about-mozilla:mozilla
    // c-http.d-x
    // www.c-
    // 3-3.3
    // www.c-c.-
    //
    // There are some examples of non-URLs according to this pattern:
    // -://x.com
    // -x.com
    // http://www-.com
    // www.c-c-
    // 3-3

    private static final Pattern isURLLenient = Pattern.compile(
            "^\\s*(\\w+-+)*\\w+(://[/]*|:|\\.)(\\w+-+)*\\w+([\\S&&[^\\w-]]\\S*)?\\s*$", flags);

    /**
     * Determine whether a string is a URL.
     *
     * This method performs a lenient check to determine whether a string is a URL. Anything that
     * contains a :, ://, or . and has no internal spaces is potentially a URL. If you need a
     * stricter check, consider using isURLLikeStrict().
     */
    public static boolean isURLLike(String string){
        return string != null && isURLLenient.matcher(string).matches();
    }

    public static boolean isURLDataLike(String string){
        return string != null && string.startsWith(DATA);
    }

    public static boolean isURLResouceLike(String string){
        return string != null && string.startsWith(RESOURCE);
    }

    public static boolean isURLFileLike(String string){
        return string != null && string.startsWith(FILE);
    }

    public static boolean isMozExtensionLike(String string){
        return string != null && string.startsWith(MOZ_EXTENSION);
    }

    public static boolean isAdaptive(String string){
        return string != null && ADAPTATIVE_PATTERN.matcher(string).matches();
    }

    public static boolean isSVGLike(String string){
        return string != null && SVG_PATTERN.matcher(string).matches();
    }

    public static boolean isICOLike(String string){
        return string != null && ICO_PATTERN.matcher(string).matches();
    }

    public static boolean isAboutBlank(String string){
        return string != null && string.startsWith(ABOUT_BLANK);
    }

    public static boolean isViewSource(String string){
        return string != null && string.startsWith(VIEW_SOURCE);
    }

    /**
     * Normalizes a URL String.
     */
    public static String toNormalizedURL(String string) {
        String trimmedInput = string.trim();
        Uri uri = Uri.parse(trimmedInput);
        if (TextUtils.isEmpty(uri.getScheme())) {
            uri = Uri.parse("https://" + trimmedInput);
        } else {
            uri = uri.normalizeScheme();
        }
        return uri.toString();
    }


    public static String toNormalizedQUERY(String string) {
        String trimmedInput = string.trim();
        Uri uri = Uri.parse(trimmedInput);
        if (TextUtils.isEmpty(uri.getScheme())) {
            uri = Uri.parse("http%://" + trimmedInput + "%") ;
        } else {
            uri = uri.normalizeScheme();
        }
        return uri.toString();
    }

    public static String toWWWNormalizedQUERY(String string) {
        String trimmedInput = string.trim();
        Uri uri = Uri.parse(trimmedInput);
        if (TextUtils.isEmpty(uri.getScheme())) {
            uri = Uri.parse("http%://www." + trimmedInput + "%") ;
        } else {
            uri = uri.normalizeScheme();
        }
        return uri.toString();
    }


    /**
     * Generates a shorter version of the provided URL for display purposes by stripping it of
     * https/http and/or WWW prefixes and/or trailing slash when applicable.
     *
     * The returned text will always be displayed from left to right.
     * If the directionality would otherwise be RTL "\u200E" will be prepended to the result to force LTR.
     */
    public CharSequence toDisplayUrl(CharSequence originalUrl, TextDirectionHeuristicCompat textDirectionHeuristic){

        if(textDirectionHeuristic == null){
            textDirectionHeuristic = TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR;
        }

        CharSequence strippedText = maybeStripTrailingSlash(maybeStripUrlProtocol(originalUrl));

        if (!TextUtils.isEmpty(strippedText) && textDirectionHeuristic.isRtl(strippedText, 0, 1)) {
            return "\u200E" + strippedText;
        }
        return strippedText;

    }

    private CharSequence maybeStripUrlProtocol(CharSequence url) {
        CharSequence noPrefixUrl = url;
        if (url.toString().startsWith(HTTPS)) {
            noPrefixUrl = maybeStripUrlSubDomain(url.toString().replaceFirst(HTTPS, ""));
        } else if (url.toString().startsWith(HTTP)) {
            noPrefixUrl = maybeStripUrlSubDomain(url.toString().replaceFirst(HTTP, ""));
        }
        return noPrefixUrl;
    }

    private CharSequence maybeStripUrlSubDomain(CharSequence url) {
        if (url.toString().startsWith(WWW)) {
            return url.toString().replaceFirst(WWW, "");
        }
        return url;
    }

    private CharSequence maybeStripTrailingSlash(CharSequence url) {
        return StringUtils.stripEnd(url.toString(), "/");
    }

    /**
     * Determines whether a string is http or https URL
     */
    public static boolean isHttpOrHttps(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("http:") || url.startsWith("https:"));
    }

    /**
     * Determine whether a string is a valid search query URL.
     */
    public static boolean isValidSearchQueryUrl(String url) {
        String trimmedUrl = StringUtils.strip(url, " ");
        if (!trimmedUrl.matches("^.+?://.+?")) {
            // UI hint url doesn't have http scheme, so add it if necessary
            trimmedUrl = "http://" + trimmedUrl;
        }
        boolean isNetworkUrl = isHttpOrHttps(trimmedUrl);
        boolean containsToken = trimmedUrl.contains("%s");
        return isNetworkUrl && containsToken;
    }

    public static String extractUrlFromText(String text) {
        if(TextUtils.isEmpty(text))
            return text;
        Matcher m = Patterns.WEB_URL.matcher(text);
        if(m.find())
            return m.group();
        return text;
    }

}
