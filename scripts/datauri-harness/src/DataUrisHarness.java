package com.solarized.firedown.harness;

import com.solarized.firedown.utils.DataUris;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Drives the REAL DataUris (copied from app/src by run.sh): which URLs are
 * data: URLs, the header a row or a Bundle keeps in place of the payload, the
 * media type, and the payload decoded the way a browser decodes it.
 */
public final class DataUrisHarness {

    private static int failures;
    private static int checks;

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": expected " + preview(expected) + ", got " + preview(actual));
        }
    }

    /** A value cut for the log: a megabyte URL is one of the inputs. */
    private static String preview(Object value) {
        String s = String.valueOf(value);
        return s.length() <= 80 ? s : s.substring(0, 80) + "... (" + s.length() + " chars)";
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** The decoded payload as text, or what the decoder refused — a value either way. */
    private static String decoded(String url) {
        try {
            return text(DataUris.decode(url));
        } catch (IllegalArgumentException e) {
            return "refused: " + e.getMessage();
        }
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    public static void main(String[] args) {
        // A megabyte image: the case that crossed Binder.
        String bigPayload = repeat('A', 4 * 1024 * 1024);
        String bigImage = "data:image/png;base64," + bigPayload;

        // What is a data: URL.
        check("http is not", false, DataUris.isDataUri("https://example.test/a.png"));
        check("null is not", false, DataUris.isDataUri(null));
        check("data: is", true, DataUris.isDataUri("data:image/png;base64,iVBORw0KGgo="));
        check("scheme case folds", true, DataUris.isDataUri("DATA:text/plain,hi"));
        check("a path mentioning data: is not", false, DataUris.isDataUri("https://example.test/data:x"));

        // The header a row or a Bundle keeps.
        check("strip: http unchanged", "https://example.test/a.png?x=1",
                DataUris.stripPayload("https://example.test/a.png?x=1"));
        check("strip: null unchanged", null, DataUris.stripPayload(null));
        check("strip: header through the comma", "data:image/png;base64,",
                DataUris.stripPayload(bigImage));
        check("strip: text payload gone too", "data:text/plain;charset=utf-8,",
                DataUris.stripPayload("data:text/plain;charset=utf-8,hello%20world"));
        check("strip: no comma, short, kept whole", "data:image/png;base64",
                DataUris.stripPayload("data:image/png;base64"));
        check("strip: no comma, long, capped", DataUris.MAX_HEADER_CHARS,
                DataUris.stripPayload("data:" + bigPayload).length());
        check("strip: a header is a header", "data:image/png;base64,",
                DataUris.stripPayload(DataUris.stripPayload(bigImage)));

        // Payload presence.
        check("payload: big image has one", true, DataUris.hasPayload(bigImage));
        check("payload: header has none", false, DataUris.hasPayload("data:image/png;base64,"));
        check("payload: no comma has none", false, DataUris.hasPayload("data:image/png;base64"));
        check("payload: http has none", false, DataUris.hasPayload("https://example.test/a.png"));

        // Media type.
        check("type: image/png", "image/png", DataUris.mediaType(bigImage));
        check("type: parameters dropped", "text/plain",
                DataUris.mediaType("data:text/plain;charset=utf-8,hi"));
        check("type: default is text/plain", "text/plain", DataUris.mediaType("data:,hi"));
        check("type: base64 alone defaults", "text/plain", DataUris.mediaType("data:;base64,aGk="));
        check("type: lower-cased", "image/svg+xml", DataUris.mediaType("data:IMAGE/SVG+XML;utf8,<svg/>"));
        check("type: http has none", null, DataUris.mediaType("https://example.test/a.png"));
        check("type: a word with no slash is no type", "text/plain", DataUris.mediaType("data:png,abc"));

        // Base64 flag.
        check("b64: flagged", true, DataUris.isBase64(bigImage));
        check("b64: not flagged", false, DataUris.isBase64("data:text/plain,hi"));
        check("b64: case and space", true, DataUris.isBase64("data:text/plain; BASE64,aGk="));
        check("b64: must close the metadata", false, DataUris.isBase64("data:text/plain;base64;charset=utf-8,aGk="));

        // Decoding, the browser's way.
        check("decode: base64", "Hello", decoded("data:text/plain;base64,SGVsbG8="));
        check("decode: base64 without padding", "Hello", decoded("data:text/plain;base64,SGVsbG8"));
        check("decode: base64 with whitespace", "Hello, world",
                decoded("data:text/plain;base64,SGVs bG8s\nIHdv\r\ncmxk"));
        check("decode: percent-encoded base64", "Hello?>",
                decoded("data:text/plain;base64,SGVsbG8%2FPg%3D%3D"));
        check("decode: percent-encoded text", "<svg>",
                decoded("data:image/svg+xml,%3Csvg%3E"));
        check("decode: literal plus kept", "a+b", decoded("data:text/plain,a+b"));
        check("decode: utf-8 escapes reassemble", "🐀",
                decoded("data:text/plain,%F0%9F%90%80"));
        check("decode: literal non-ascii kept", "café", decoded("data:text/plain,café"));
        check("decode: header alone is null", null, DataUris.decode("data:image/png;base64,"));
        check("decode: http is null", null, DataUris.decode("https://example.test/a.png"));
        check("decode: big image length", 3 * 1024 * 1024, DataUris.decode(bigImage).length);
        boolean refused = false;
        try {
            DataUris.decode("data:text/plain;base64,SGVsbG8*");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("decode: garbage base64 refused", true, refused);
        refused = false;
        try {
            DataUris.decode("data:text/plain;base64,S");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("decode: impossible length refused", true, refused);

        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
