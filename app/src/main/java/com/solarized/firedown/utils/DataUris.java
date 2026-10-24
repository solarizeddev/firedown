package com.solarized.firedown.utils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * {@code data:} URLs (RFC 2397) — the one place that knows their shape.
 *
 * <p>A {@code data:} URL IS its content: an inline image of a few megabytes
 * is a URL of a few megabytes of base64. Everything that carries a URL
 * around — a service Intent, a saved-state Bundle, a database row — is sized
 * for an address, not for a file, so the payload stays where the bytes are
 * needed (the download that decodes it, the tab that holds the element) and
 * every other holder keeps only the {@link #stripPayload header}:
 * {@code data:image/png;base64,}. A Parcel of the whole URL crossed Binder's
 * 1 MB transaction buffer ({@code TransactionTooLargeException} out of
 * {@code startService}), and a row holding it would not fit a CursorWindow.</p>
 *
 * <p>Pure Java on purpose: {@code scripts/datauri-harness} drives it under a
 * JDK.</p>
 */
public final class DataUris {

    private static final String SCHEME = "data:";
    private static final String BASE64 = ";base64";
    private static final String DEFAULT_MEDIA_TYPE = "text/plain";

    /**
     * Longest header kept. A media type with its parameters is tens of
     * characters; a "header" past this is a {@code data:} URL with no comma,
     * which nothing can decode, cut so it cannot be the payload under
     * another name.
     */
    public static final int MAX_HEADER_CHARS = 256;

    private DataUris() {
    }

    public static boolean isDataUri(String url) {
        return url != null && url.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    /**
     * A {@code data:} URL cut to its header — the scheme, the media type and
     * the comma, {@code data:image/png;base64,} — and any other URL as it is.
     * This is the form a row, a Bundle or a Parcel keeps.
     */
    public static String stripPayload(String url) {
        if (!isDataUri(url)) {
            return url;
        }
        return header(url);
    }

    static String header(String url) {
        int comma = url.indexOf(',');
        int end = comma < 0 ? url.length() : comma + 1;
        if (end > MAX_HEADER_CHARS) {
            end = MAX_HEADER_CHARS;
        }
        return url.substring(0, end);
    }

    /**
     * True when the URL carries bytes to decode: a comma with something after
     * it. A header kept by {@link #stripPayload} has none.
     */
    public static boolean hasPayload(String url) {
        if (!isDataUri(url)) {
            return false;
        }
        int comma = url.indexOf(',');
        return comma >= 0 && payloadEnd(url, comma) > comma + 1;
    }

    /**
     * The media type without its parameters, lower-cased ({@code image/png});
     * RFC 2397's {@code text/plain} when the URL names none; {@code null} for a
     * URL that is not a {@code data:} URL.
     */
    public static String mediaType(String url) {
        if (!isDataUri(url)) {
            return null;
        }
        String meta = metadata(url);
        int semicolon = meta.indexOf(';');
        String type = semicolon < 0 ? meta : meta.substring(0, semicolon);
        type = type.trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || type.indexOf('/') < 0) {
            return DEFAULT_MEDIA_TYPE;
        }
        return type;
    }

    /** True when the payload is base64 — the {@code ;base64} token closes the metadata. */
    public static boolean isBase64(String url) {
        if (!isDataUri(url)) {
            return false;
        }
        String meta = metadata(url).replace(" ", "").toLowerCase(Locale.ROOT);
        return meta.endsWith(BASE64);
    }

    /**
     * What sits between the scheme and the comma — the whole of it, however
     * long: the base64 flag CLOSES the metadata, and reading a capped prefix
     * for it wrote a base64 payload out as text once the parameters ran past
     * the cap. Only a URL with no comma is cut (there is no payload to be).
     */
    private static String metadata(String url) {
        int comma = url.indexOf(',');
        int end = comma < 0 ? Math.min(url.length(), MAX_HEADER_CHARS) : comma;
        return url.substring(SCHEME.length(), end);
    }

    /**
     * Where the payload ends: a {@code #} after the comma starts the URL's
     * fragment, which is not data (the WHATWG data: URL processor reads the
     * URL with its fragment removed; base64 never holds a {@code #}, and a
     * percent-encoded payload writes one as {@code %23}).
     */
    private static int payloadEnd(String url, int comma) {
        int hash = url.indexOf('#', comma + 1);
        return hash < 0 ? url.length() : hash;
    }

    /**
     * The payload as bytes, read the way a browser reads it: percent-decoded
     * first, then — for a {@code ;base64} URL — base64-decoded forgivingly
     * (ASCII whitespace dropped, padding optional). {@code null} for a URL with
     * nothing after its comma (a fragment is not payload).
     *
     * @throws IllegalArgumentException for a base64 payload that is not base64
     */
    public static byte[] decode(String url) {
        if (!hasPayload(url)) {
            return null;
        }
        int comma = url.indexOf(',');
        byte[] bytes = percentDecode(url, comma + 1, payloadEnd(url, comma));
        if (!isBase64(url)) {
            return bytes;
        }
        return forgivingBase64(bytes);
    }

    /**
     * Percent-decodes {@code s} from {@code from} to {@code to} as bytes. Unlike
     * {@code URLDecoder}, a literal {@code +} stays a {@code +} (it is a space
     * only in form encoding, and appears literally in SVG and in base64).
     * Runs of literal characters are encoded as UTF-8 together so multi-byte
     * characters and surrogate pairs survive, and a {@code %XX} escape
     * contributes its raw byte, so a UTF-8 escape sequence reassembles.
     */
    private static byte[] percentDecode(String s, int from, int to) {
        int firstEscape = s.indexOf('%', from);
        if (firstEscape < 0 || firstEscape >= to) {
            return s.substring(from, to).getBytes(StandardCharsets.UTF_8);
        }
        int n = to;
        ByteArrayOutputStream out = new ByteArrayOutputStream(n - from);
        StringBuilder literal = new StringBuilder();
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            int hi = -1;
            int lo = -1;
            if (c == '%' && i + 2 < n) {
                hi = Character.digit(s.charAt(i + 1), 16);
                lo = Character.digit(s.charAt(i + 2), 16);
            }
            if (hi >= 0 && lo >= 0) {
                flushLiteral(out, literal);
                out.write((hi << 4) + lo);
                i += 3;
            } else {
                literal.append(c);
                i++;
            }
        }
        flushLiteral(out, literal);
        return out.toByteArray();
    }

    private static void flushLiteral(ByteArrayOutputStream out, StringBuilder literal) {
        if (literal.length() == 0) {
            return;
        }
        byte[] bytes = literal.toString().getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        literal.setLength(0);
    }

    /**
     * Base64 the way the forgiving decode reads it: ASCII whitespace is
     * dropped, trailing padding is optional, and a length that cannot be
     * base64 is refused.
     */
    private static byte[] forgivingBase64(byte[] ascii) {
        byte[] compact = new byte[ascii.length];
        int n = 0;
        for (byte b : ascii) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r' && b != '\f') {
                compact[n++] = b;
            }
        }
        while (n > 0 && compact[n - 1] == '=') {
            n--;
        }
        if (n % 4 == 1) {
            throw new IllegalArgumentException("base64 payload of impossible length");
        }
        int padded = (n + 3) / 4 * 4;
        byte[] input = new byte[padded];
        System.arraycopy(compact, 0, input, 0, n);
        for (int i = n; i < padded; i++) {
            input[i] = '=';
        }
        return Base64.getDecoder().decode(input);
    }
}
