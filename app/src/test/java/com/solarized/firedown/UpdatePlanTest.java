package com.solarized.firedown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

/**
 * The order an update is downloaded in (UpdatePlan): a patch only for the
 * installed APK's exact hash, then the compressed APK, then the APK, which is
 * always there and always last; mirrors before the next kind; a kind that
 * failed to rebuild skipped; and the plan surviving the round trip through
 * SharedPreferences, position included.
 */
public class UpdatePlanTest {

    private static final String A = repeat('a');
    private static final String B = repeat('b');
    private static final String C = repeat('c');
    private static final String D = repeat('d');
    private static final String APK_SHA = repeat('e');
    private static final String XZ_SHA = repeat('f');

    private static String repeat(char c) {
        char[] chars = new char[64];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static final String MANIFEST = "{\"versionCode\":1206,\"versionName\":\"Firedown 1.2.6\","
            + "\"updateUrl\":\"https://gh/app.apk\",\"updateUrlFallbacks\":[\"https://fdroid/app.apk\"],"
            + "\"sha256\":\"" + APK_SHA + "\","
            + "\"patches\":["
            + "{\"fromVersionCode\":1205,\"fromSha256\":\"" + A + "\",\"urls\":[\"https://gh/p5\",\"https://m/p5\"],\"sha256\":\"" + B + "\",\"size\":5},"
            + "{\"fromVersionCode\":1204,\"fromSha256\":\"" + C + "\",\"urls\":[\"https://gh/p4\"],\"sha256\":\"" + D + "\",\"size\":4}],"
            + "\"compressed\":{\"format\":\"xz\",\"urls\":[\"https://gh/x\",\"https://m/x\"],\"sha256\":\"" + XZ_SHA + "\",\"size\":70}}";

    private static UpdateManifest manifest() {
        UpdateManifest m = UpdateManifest.parse(MANIFEST);
        assertNotNull(m);
        return m;
    }

    @Test
    public void patchForTheInstalledHashThenXzThenApk() {
        UpdatePlan plan = UpdatePlan.build(manifest(), C.toUpperCase(), EnumSet.noneOf(UpdatePlan.Kind.class));
        assertEquals(3, plan.steps().size());
        assertEquals(UpdatePlan.Kind.PATCH, plan.steps().get(0).kind);
        assertEquals(D, plan.steps().get(0).sha256);
        assertEquals(C, plan.steps().get(0).fromSha256);
        assertEquals(UpdatePlan.Kind.XZ, plan.steps().get(1).kind);
        assertEquals(XZ_SHA, plan.steps().get(1).sha256);
        assertEquals(UpdatePlan.Kind.APK, plan.steps().get(2).kind);
        assertEquals(APK_SHA, plan.steps().get(2).sha256);
        assertEquals("https://gh/p4", plan.currentUrl());
    }

    @Test
    public void noPatchWithoutAMatchingHash() {
        for (String installed : new String[] {null, "", B, APK_SHA}) {
            UpdatePlan plan = UpdatePlan.build(manifest(), installed, EnumSet.noneOf(UpdatePlan.Kind.class));
            assertEquals(2, plan.steps().size());
            assertEquals(UpdatePlan.Kind.XZ, plan.current().kind);
        }
    }

    @Test
    public void failedKindsAreSkipped() {
        UpdatePlan plan = UpdatePlan.build(manifest(), A, EnumSet.of(UpdatePlan.Kind.PATCH));
        assertEquals(UpdatePlan.Kind.XZ, plan.current().kind);
        plan = UpdatePlan.build(manifest(), A, EnumSet.of(UpdatePlan.Kind.PATCH, UpdatePlan.Kind.XZ));
        assertEquals(1, plan.steps().size());
        assertEquals(UpdatePlan.Kind.APK, plan.current().kind);
        // The APK can never be skipped.
        plan = UpdatePlan.build(manifest(), A, EnumSet.allOf(UpdatePlan.Kind.class));
        assertEquals(UpdatePlan.Kind.APK, plan.current().kind);
    }

    @Test
    public void anOldManifestIsApkOnly() {
        UpdateManifest old = UpdateManifest.parse("{\"versionCode\":1,\"versionName\":\"v\","
                + "\"updateUrl\":\"https://x/a.apk\",\"sha256\":\"abc\"}");
        assertNotNull(old);
        UpdatePlan plan = UpdatePlan.build(old, A, EnumSet.noneOf(UpdatePlan.Kind.class));
        assertEquals(1, plan.steps().size());
        assertEquals("https://x/a.apk", plan.currentUrl());
    }

    @Test
    public void mirrorsThenTheNextKindThenNothing() {
        UpdatePlan plan = UpdatePlan.build(manifest(), A, EnumSet.noneOf(UpdatePlan.Kind.class));
        String[] expected = {"https://gh/p5", "https://m/p5", "https://gh/x", "https://m/x",
                "https://gh/app.apk", "https://fdroid/app.apk"};
        assertEquals(expected[0], plan.currentUrl());
        for (int i = 1; i < expected.length; i++) {
            assertTrue(plan.nextUrl());
            assertEquals(expected[i], plan.currentUrl());
        }
        assertFalse(plan.nextUrl());
    }

    @Test
    public void aFailedStepSkipsItsOtherMirrors() {
        UpdatePlan plan = UpdatePlan.build(manifest(), A, EnumSet.noneOf(UpdatePlan.Kind.class));
        assertTrue(plan.nextStep());
        assertEquals("https://gh/x", plan.currentUrl());
        assertTrue(plan.nextUrl());
        assertTrue(plan.nextStep());
        assertEquals("https://gh/app.apk", plan.currentUrl());
        assertFalse(plan.nextStep());
    }

    @Test
    public void survivesPersistenceWithItsPosition() {
        UpdatePlan plan = UpdatePlan.build(manifest(), A, EnumSet.noneOf(UpdatePlan.Kind.class));
        plan.nextUrl();
        plan.nextUrl();
        UpdatePlan back = UpdatePlan.fromJson(plan.toJson());
        assertNotNull(back);
        assertEquals(plan.toJson(), back.toJson());
        assertEquals("https://gh/x", back.currentUrl());
        assertEquals(A, back.steps().get(0).fromSha256);
        assertEquals(5, back.steps().get(0).size);
        assertTrue(back.nextUrl());
        assertEquals("https://m/x", back.currentUrl());
    }

    @Test
    public void malformedPersistedPlansAreRefused() {
        assertNull(UpdatePlan.fromJson(null));
        assertNull(UpdatePlan.fromJson(""));
        assertNull(UpdatePlan.fromJson("not json"));
        assertNull(UpdatePlan.fromJson("{\"step\":0,\"url\":0,\"steps\":[]}"));
        String step = "{\"kind\":\"APK\",\"urls\":[\"https://a\"],\"sha256\":\"x\"}";
        assertNotNull(UpdatePlan.fromJson("{\"step\":0,\"url\":0,\"steps\":[" + step + "]}"));
        assertNull(UpdatePlan.fromJson("{\"step\":1,\"url\":0,\"steps\":[" + step + "]}"));
        assertNull(UpdatePlan.fromJson("{\"step\":0,\"url\":1,\"steps\":[" + step + "]}"));
        assertNull(UpdatePlan.fromJson("{\"step\":-1,\"url\":0,\"steps\":[" + step + "]}"));
        assertNull(UpdatePlan.fromJson("{\"step\":0,\"url\":0,\"steps\":[{\"kind\":\"NOPE\",\"urls\":[\"https://a\"],\"sha256\":\"x\"}]}"));
        assertNull(UpdatePlan.fromJson("{\"step\":0,\"url\":0,\"steps\":[{\"kind\":\"APK\",\"urls\":[],\"sha256\":\"x\"}]}"));
        // A patch step must say which APK it starts from.
        assertNull(UpdatePlan.fromJson("{\"step\":0,\"url\":0,\"steps\":[{\"kind\":\"PATCH\",\"urls\":[\"https://a\"],\"sha256\":\"x\"}]}"));
    }

    @Test
    public void aDownloadRecordedBeforePlansCarriesOver() {
        UpdatePlan plan = UpdatePlan.apkOnly(Arrays.asList("https://a", "https://b"), APK_SHA, 1);
        assertEquals("https://b", plan.currentUrl());
        assertFalse(plan.nextUrl());
        plan = UpdatePlan.apkOnly(Collections.singletonList("https://a"), APK_SHA, 7);
        assertEquals("https://a", plan.currentUrl());
    }
}
