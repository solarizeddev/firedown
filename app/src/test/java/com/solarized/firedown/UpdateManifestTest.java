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
}
