package com.solarized.firedown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for parsing + version-gating the update status.json
 * descriptor (UpdateManifest). This is the logic that decides whether the app
 * downloads/prompts at all, and it runs on untrusted remote JSON, so the
 * contract is pinned here: a well-formed NEWER descriptor is acted on, and
 * anything malformed or incomplete parses to null and is treated like a failed
 * fetch (no action).
 */
public class UpdateManifestTest {

    private static final String VALID =
            "{\"versionCode\":1200,\"versionName\":\"3.4.0\","
            + "\"updateUrl\":\"https://www.firedown.app/firedown.apk\","
            + "\"sha256\":\"abc123\"}";

    @Test
    public void parsesAllFields() {
        UpdateManifest m = UpdateManifest.parse(VALID);
        assertNotNull(m);
        assertEquals(1200, m.versionCode);
        assertEquals("3.4.0", m.versionName);
        assertEquals(1, m.downloadUrls.size());
        assertEquals("https://www.firedown.app/firedown.apk", m.downloadUrls.get(0));
        assertEquals("abc123", m.sha256);
        assertEquals("", m.changelog); // absent -> empty, never null
    }

    @Test
    public void parsesChangelogWhenPresent() {
        String json = "{\"versionCode\":1,\"versionName\":\"v\","
                + "\"updateUrl\":\"https://x/y.apk\",\"sha256\":\"a\","
                + "\"changelog\":\"line one\\nline two\"}";
        UpdateManifest m = UpdateManifest.parse(json);
        assertNotNull(m);
        assertEquals("line one\nline two", m.changelog);
    }

    @Test
    public void parsesFallbackMirrorsInOrder() {
        String json = "{\"versionCode\":1200,\"versionName\":\"v\","
                + "\"updateUrl\":\"https://primary/app.apk\","
                + "\"updateUrlFallbacks\":[\"https://mirror1/app.apk\",\"https://mirror2/app.apk\"],"
                + "\"sha256\":\"a\"}";
        UpdateManifest m = UpdateManifest.parse(json);
        assertNotNull(m);
        assertEquals(3, m.downloadUrls.size());
        assertEquals("https://primary/app.apk", m.downloadUrls.get(0));
        assertEquals("https://mirror1/app.apk", m.downloadUrls.get(1));
        assertEquals("https://mirror2/app.apk", m.downloadUrls.get(2));
    }

    @Test
    public void fallbackListSkipsBlanksAndDuplicatePrimary() {
        String json = "{\"versionCode\":1,\"versionName\":\"v\","
                + "\"updateUrl\":\"https://primary/app.apk\","
                + "\"updateUrlFallbacks\":[\"\",\"https://primary/app.apk\",\"https://mirror/app.apk\"],"
                + "\"sha256\":\"a\"}";
        UpdateManifest m = UpdateManifest.parse(json);
        assertNotNull(m);
        // blank skipped, duplicate-of-primary skipped -> primary + mirror only
        assertEquals(2, m.downloadUrls.size());
        assertEquals("https://primary/app.apk", m.downloadUrls.get(0));
        assertEquals("https://mirror/app.apk", m.downloadUrls.get(1));
    }

    @Test
    public void missingFallbacksJustHasPrimary() {
        UpdateManifest m = UpdateManifest.parse(VALID);
        assertNotNull(m);
        assertEquals(1, m.downloadUrls.size());
    }

    @Test
    public void newerVersionIsDetected() {
        UpdateManifest m = UpdateManifest.parse(VALID);
        assertNotNull(m);
        assertTrue(m.isNewerThan(1199));    // installed older -> update
        assertFalse(m.isNewerThan(1200));   // same version -> no update
        assertFalse(m.isNewerThan(1201));   // installed newer (local build) -> no update
    }

    @Test
    public void nullAndBlankInputParseToNull() {
        assertNull(UpdateManifest.parse(null));
        assertNull(UpdateManifest.parse(""));
        assertNull(UpdateManifest.parse("   "));
    }

    @Test
    public void malformedJsonParsesToNull() {
        assertNull(UpdateManifest.parse("not json at all"));
        assertNull(UpdateManifest.parse("{\"versionCode\":"));   // truncated
    }

    @Test
    public void missingRequiredFieldParsesToNull() {
        // No versionCode
        assertNull(UpdateManifest.parse(
                "{\"versionName\":\"3.4.0\",\"updateUrl\":\"https://x/y.apk\",\"sha256\":\"a\"}"));
        // No updateUrl
        assertNull(UpdateManifest.parse(
                "{\"versionCode\":1,\"versionName\":\"3.4.0\",\"sha256\":\"a\"}"));
        // No sha256
        assertNull(UpdateManifest.parse(
                "{\"versionCode\":1,\"versionName\":\"3.4.0\",\"updateUrl\":\"https://x/y.apk\"}"));
    }

    @Test
    public void emptyUrlOrShaParsesToNull() {
        assertNull(UpdateManifest.parse(
                "{\"versionCode\":1,\"versionName\":\"v\",\"updateUrl\":\"\",\"sha256\":\"a\"}"));
        assertNull(UpdateManifest.parse(
                "{\"versionCode\":1,\"versionName\":\"v\",\"updateUrl\":\"https://x/y.apk\",\"sha256\":\"\"}"));
    }

    // ── patches and the compressed APK ────────────────────────────────────

    private static String hex(char c) {
        char[] chars = new char[64];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String patch(int from, String fromSha, String urls, String sha) {
        return "{\"fromVersionCode\":" + from + ",\"fromSha256\":\"" + fromSha + "\",\"urls\":" + urls
                + ",\"sha256\":\"" + sha + "\",\"size\":123}";
    }

    private static String withBlocks(String blocks) {
        return "{\"versionCode\":1206,\"versionName\":\"v\",\"updateUrl\":\"https://x/a.apk\","
                + "\"sha256\":\"abc\"," + blocks + "}";
    }

    @Test
    public void aDescriptorWithoutTheNewBlocksHasNone() {
        UpdateManifest m = UpdateManifest.parse(VALID);
        assertNotNull(m);
        assertTrue(m.patches.isEmpty());
        assertNull(m.compressed);
        assertNull(m.patchFrom(hex('a')));
    }

    @Test
    public void parsesPatchesAndTheCompressedApk() {
        UpdateManifest m = UpdateManifest.parse(withBlocks(
                "\"patches\":[" + patch(1205, hex('A'), "[\"https://gh/p\",\"https://m/p\",\"https://gh/p\"]", hex('B')) + "],"
                + "\"compressed\":{\"format\":\"xz\",\"urls\":[\"https://gh/x\"],\"sha256\":\"" + hex('c') + "\",\"size\":70}"));
        assertNotNull(m);
        assertEquals(1, m.patches.size());
        UpdateManifest.Artifact p = m.patches.get(0);
        assertEquals(1205, p.fromVersionCode);
        assertEquals(hex('a'), p.fromSha256);   // lower-cased
        assertEquals(hex('b'), p.sha256);
        assertEquals(2, p.urls.size());         // duplicate dropped
        assertEquals(123, p.size);
        assertEquals(p, m.patchFrom(hex('A')));
        assertEquals(p, m.patchFrom(hex('a')));
        assertNull(m.patchFrom(hex('b')));
        assertNotNull(m.compressed);
        assertEquals(hex('c'), m.compressed.sha256);
        assertNull(m.compressed.fromSha256);
        assertEquals(70, m.compressed.size);
    }

    @Test
    public void onlyTheFirstThreePatchEntriesAreRead() {
        StringBuilder list = new StringBuilder();
        char[] froms = {'1', '2', '3', '4', '5'};
        for (int i = 0; i < froms.length; i++) {
            if (i > 0) {
                list.append(',');
            }
            list.append(patch(1200 + i, hex(froms[i]), "[\"https://gh/p" + i + "\"]", hex('b')));
        }
        UpdateManifest m = UpdateManifest.parse(withBlocks("\"patches\":[" + list + "]"));
        assertNotNull(m);
        assertEquals(UpdateManifest.MAX_PATCHES, m.patches.size());
        assertNotNull(m.patchFrom(hex('3')));
        assertNull(m.patchFrom(hex('4')));
        assertNull(m.patchFrom(hex('5')));
    }

    @Test
    public void malformedEntriesAreSkippedAndNeverSinkTheDescriptor() {
        String good = patch(1205, hex('a'), "[\"https://gh/p\"]", hex('b'));
        String[] bad = {
                patch(1204, "short", "[\"https://gh/p\"]", hex('b')),        // fromSha256 not 64 hex
                patch(1204, hex('a'), "[\"https://gh/p\"]", "nothex"),        // sha256
                patch(1204, hex('a'), "[\"http://gh/p\"]", hex('b')),         // not https
                patch(1204, hex('a'), "[]", hex('b')),                          // no URL
                "{\"fromSha256\":\"" + hex('a') + "\",\"sha256\":\"" + hex('b') + "\"}", // no urls key
                "\"a string\"",
                "null",
        };
        for (String b : bad) {
            UpdateManifest m = UpdateManifest.parse(withBlocks("\"patches\":[" + b + "," + good + "]"));
            assertNotNull(b, m);
            assertEquals(b, 1, m.patches.size());
            assertEquals(1205, m.patches.get(0).fromVersionCode);
            assertEquals(1, m.downloadUrls.size());
        }
        // Not even an array: ignored.
        UpdateManifest m = UpdateManifest.parse(withBlocks("\"patches\":{\"a\":1}"));
        assertNotNull(m);
        assertTrue(m.patches.isEmpty());
    }

    @Test
    public void onlyAnXzCompressedApkIsUsed() {
        String[] unusable = {
                "{\"format\":\"zstd\",\"urls\":[\"https://gh/x\"],\"sha256\":\"" + hex('c') + "\"}",
                "{\"urls\":[\"https://gh/x\"],\"sha256\":\"" + hex('c') + "\"}",
                "{\"format\":\"xz\",\"urls\":[\"https://gh/x\"],\"sha256\":\"short\"}",
                "{\"format\":\"xz\",\"urls\":[\"ftp://gh/x\"],\"sha256\":\"" + hex('c') + "\"}",
                "\"xz\"",
        };
        for (String c : unusable) {
            UpdateManifest m = UpdateManifest.parse(withBlocks("\"compressed\":" + c));
            assertNotNull(c, m);
            assertNull(c, m.compressed);
        }
    }

    @Test
    public void aNegativeSizeReadsAsUnknown() {
        UpdateManifest m = UpdateManifest.parse(withBlocks("\"compressed\":{\"format\":\"xz\","
                + "\"urls\":[\"https://gh/x\"],\"sha256\":\"" + hex('c') + "\",\"size\":-5}"));
        assertNotNull(m);
        assertNotNull(m.compressed);
        assertEquals(0, m.compressed.size);
    }
}
