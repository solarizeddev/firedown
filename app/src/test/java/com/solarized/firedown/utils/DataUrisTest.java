package com.solarized.firedown.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * {@link DataUris} on the JVM: the header a row or a Parcel keeps, and the
 * decode a download runs. Pure Java, so it runs with
 * {@code ./gradlew testDebugUnitTest} and under the Android Studio debugger.
 */
public class DataUrisTest {

    private static final String PNG_HEADER = "data:image/png;base64,";

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void isDataUriIgnoresCaseAndRefusesOtherSchemes() {
        assertTrue(DataUris.isDataUri("data:text/plain,hi"));
        assertTrue(DataUris.isDataUri("DATA:text/plain,hi"));
        assertFalse(DataUris.isDataUri("https://example.test/data:x"));
        assertFalse(DataUris.isDataUri("dat"));
        assertFalse(DataUris.isDataUri(null));
    }

    @Test
    public void stripPayloadKeepsTheHeaderThroughTheComma() {
        assertEquals(PNG_HEADER, DataUris.stripPayload(PNG_HEADER + "iVBORw0KGgo="));
        assertEquals(PNG_HEADER, DataUris.stripPayload(PNG_HEADER));
        assertEquals("data:,", DataUris.stripPayload("data:,hello"));
    }

    @Test
    public void stripPayloadLeavesOtherUrlsAlone() {
        assertEquals("https://example.test/a.mp4", DataUris.stripPayload("https://example.test/a.mp4"));
        assertNull(DataUris.stripPayload(null));
        assertEquals("", DataUris.stripPayload(""));
    }

    @Test
    public void stripPayloadCapsAHeaderWithNoComma() {
        String noComma = "data:" + repeat('x', 1000);
        String kept = DataUris.stripPayload(noComma);
        assertEquals(DataUris.MAX_HEADER_CHARS, kept.length());
        assertEquals(noComma.substring(0, DataUris.MAX_HEADER_CHARS), kept);
    }

    @Test
    public void stripPayloadCapsAHeaderWhoseCommaIsPastTheCap() {
        String longMeta = "data:text/plain;name=" + repeat('y', 500) + ",payload";
        assertEquals(DataUris.MAX_HEADER_CHARS, DataUris.stripPayload(longMeta).length());
    }

    @Test
    public void hasPayloadNeedsBytesAfterTheComma() {
        assertTrue(DataUris.hasPayload(PNG_HEADER + "AAAA"));
        assertFalse(DataUris.hasPayload(PNG_HEADER));
        assertFalse(DataUris.hasPayload("data:text/plain"));
        assertFalse(DataUris.hasPayload("data:text/plain,#only-a-fragment"));
        assertFalse(DataUris.hasPayload("https://example.test/,x"));
        assertFalse(DataUris.hasPayload(null));
    }

    @Test
    public void mediaTypeIsLowercasedWithoutParameters() {
        assertEquals("image/png", DataUris.mediaType("data:IMAGE/PNG;base64,AAAA"));
        assertEquals("image/svg+xml", DataUris.mediaType("data:image/svg+xml;charset=utf-8,%3Csvg%2F%3E"));
        assertEquals("text/plain", DataUris.mediaType("data:,hello"));
        assertEquals("text/plain", DataUris.mediaType("data:;base64,aGk="));
        assertEquals("text/plain", DataUris.mediaType("data:charset=utf-8,hello"));
        assertNull(DataUris.mediaType("https://example.test/a.png"));
    }

    @Test
    public void isBase64ReadsTheTokenThatClosesTheMetadata() {
        assertTrue(DataUris.isBase64(PNG_HEADER + "AAAA"));
        assertTrue(DataUris.isBase64("data:image/png; base64,AAAA"));
        assertTrue(DataUris.isBase64("data:image/png;BASE64,AAAA"));
        assertFalse(DataUris.isBase64("data:text/plain,hello"));
        assertFalse(DataUris.isBase64("data:text/plain;charset=base64,hello"));
        assertFalse(DataUris.isBase64("https://example.test/;base64,x"));
    }

    @Test
    public void decodeReadsBase64() {
        assertEquals("Hello", text(DataUris.decode("data:text/plain;base64,SGVsbG8=")));
    }

    @Test
    public void decodeIsForgivingAboutPaddingAndWhitespace() {
        assertEquals("Hello", text(DataUris.decode("data:text/plain;base64,SGVsbG8")));
        assertEquals("Hello", text(DataUris.decode("data:text/plain;base64,SGVs\nbG8=\r\n")));
        assertEquals("Hello", text(DataUris.decode("data:text/plain;base64,SGVs bG8=")));
    }

    @Test
    public void decodeRefusesBase64OfImpossibleLength() {
        assertThrows(IllegalArgumentException.class,
                () -> DataUris.decode("data:text/plain;base64,QQQQQ"));
    }

    @Test
    public void decodeRefusesBytesThatAreNotBase64() {
        assertThrows(IllegalArgumentException.class,
                () -> DataUris.decode("data:text/plain;base64,!!!!"));
    }

    @Test
    public void decodePercentDecodesTextPayloads() {
        byte[] svg = DataUris.decode(
                "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E");
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\"/>", text(svg));
    }

    @Test
    public void decodeKeepsALiteralPlusAndAStrayPercent() {
        assertEquals("a+b", text(DataUris.decode("data:text/plain,a+b")));
        assertEquals("100%", text(DataUris.decode("data:text/plain,100%")));
        assertEquals("%4", text(DataUris.decode("data:text/plain,%4")));
    }

    @Test
    public void decodeReassemblesUtf8() {
        assertEquals("héllo", text(DataUris.decode("data:text/plain,h%C3%A9llo")));
        assertEquals("héllo", text(DataUris.decode("data:text/plain,héllo")));
        assertEquals("😀", text(DataUris.decode("data:text/plain,😀")));
    }

    @Test
    public void decodeDropsTheFragment() {
        assertEquals("hello", text(DataUris.decode("data:text/plain,hello#frag")));
        assertEquals("Hello", text(DataUris.decode("data:text/plain;base64,SGVsbG8=#frag")));
        assertEquals("a#b", text(DataUris.decode("data:text/plain,a%23b")));
    }

    @Test
    public void decodePercentDecodesBeforeBase64() {
        // A base64 payload written with its padding percent-encoded.
        assertEquals("Hello", text(DataUris.decode("data:text/plain;base64,SGVsbG8%3D")));
    }

    @Test
    public void decodeReadsTheBase64FlagPastTheHeaderCap() {
        // The flag closes the metadata however long the parameters run; a
        // capped read of the metadata wrote this payload out as text.
        String url = "data:text/plain;name=" + repeat('x', 400) + ";base64,SGVsbG8=";
        assertTrue(url.indexOf(',') > DataUris.MAX_HEADER_CHARS);
        assertTrue(DataUris.isBase64(url));
        assertEquals("Hello", text(DataUris.decode(url)));
    }

    @Test
    public void decodeOfAHeaderOrAnotherUrlIsNull() {
        assertNull(DataUris.decode(PNG_HEADER));
        assertNull(DataUris.decode("data:text/plain,#frag"));
        assertNull(DataUris.decode("https://example.test/a.png"));
        assertNull(DataUris.decode(null));
    }

    @Test
    public void aStrippedHeaderRoundTrips() {
        String whole = PNG_HEADER + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
        String header = DataUris.stripPayload(whole);
        assertTrue(DataUris.isDataUri(header));
        assertFalse(DataUris.hasPayload(header));
        assertEquals("image/png", DataUris.mediaType(header));
        assertTrue(DataUris.isBase64(header));
        assertSame(header, DataUris.stripPayload(header));
        assertArrayEquals(DataUris.decode(whole), DataUris.decode(header + whole.substring(header.length())));
    }
}
