package com.solarized.firedown.addons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The add-on rules that decide what may install and what a prompt says:
 * built-in IDs refused, Mozilla's list parsed, an .xpi's manifest read, the
 * permission lines worded and capped. The fuller set, with mutants, is
 * {@code sh scripts/addons-harness/run.sh}.
 */
public class AddonsPureTest {

    @Test
    public void builtInIdsAreRefused() {
        // The official uBlock Origin installs as an ordinary add-on.
        assertFalse(AddonPolicy.isBuiltInId("uBlock0@raymondhill.net"));
        assertFalse(AddonPolicy.isBuiltInId("UBLOCK0@RAYMONDHILL.NET"));
        assertTrue(AddonPolicy.isBuiltInId("downloader@solarized.dev"));
        assertTrue(AddonPolicy.isBuiltInId("future@solarized.dev"));
        assertTrue(AddonPolicy.isBuiltInId("icons@mozac.org"));
        assertFalse(AddonPolicy.isBuiltInId("x@solarized.dev.example.com"));
        assertFalse(AddonPolicy.isBuiltInId("{aecec67f-0d10-4fa7-b7c7-609a2db280cf}"));
        assertFalse(AddonPolicy.isBuiltInId(null));
    }

    @Test
    public void ublockIsRecognised() {
        assertTrue(AddonPolicy.isUblockId("uBlock0@raymondhill.net"));
        assertFalse(AddonPolicy.isUblockId("{aecec67f-0d10-4fa7-b7c7-609a2db280cf}"));
        assertFalse(AddonPolicy.isUblockId("youtube@solarized.dev"));
    }

    @Test
    public void blockersAndTheShield() {
        assertTrue(AddonPolicy.blocksRequests(Arrays.asList("webRequest", "webRequestBlocking", "<all_urls>"), null));
        assertTrue(AddonPolicy.blocksRequests(Collections.singletonList("declarativeNetRequest"), null));
        assertFalse(AddonPolicy.blocksRequests(Collections.singletonList("webRequestBlocking"),
                Collections.singletonList("https://www.youtube.com/*")));
        assertFalse(AddonPolicy.blocksRequests(Arrays.asList("storage", "tabs"),
                Collections.singletonList("<all_urls>")));
        assertTrue(AddonPolicy.isBlocker("uBlock0@raymondhill.net", false, Boolean.FALSE));
        assertTrue(AddonPolicy.isBlocker("firefox@ghostery.com", true, null));
        assertFalse(AddonPolicy.isBlocker("magnolia@12.34", true, null));
        assertFalse(AddonPolicy.isBlocker("firefox@ghostery.com", false, null));
        assertFalse(AddonPolicy.isBlocker("firefox@ghostery.com", true, Boolean.FALSE));
        assertTrue(AddonPolicy.isBlocker("x@y", true, Boolean.TRUE));
        assertTrue(AddonPolicy.offersShieldChoice("magnolia@12.34", true, true, null));
        assertFalse(AddonPolicy.offersShieldChoice("addon@darkreader.org", true, false, null));
        assertFalse(AddonPolicy.offersShieldChoice("uBlock0@raymondhill.net", true, true, null));
        assertFalse(AddonPolicy.actionTitleSaysMore("uBlock Origin (12)", "uBlock Origin", "12"));
        assertFalse(AddonPolicy.actionTitleSaysMore("uBlock Origin", "uBlock Origin", null));
        assertTrue(AddonPolicy.actionTitleSaysMore("Paused on this site", "Some Add-on", "3"));
        assertTrue(AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", "uBlock Origin (off)"));
        assertFalse(AddonPolicy.saysOffForSite("uBlock0@raymondhill.net", "uBlock Origin (12)"));
        assertFalse(AddonPolicy.saysOffForSite("other@x", "Other (off)"));
        assertEquals(AddonPolicy.SHIELD_NO_BLOCKER, AddonPolicy.shieldState(Collections.emptyList()));
        assertEquals(AddonPolicy.SHIELD_PROTECTED, AddonPolicy.shieldState(Collections.singletonList(false)));
        assertEquals(AddonPolicy.SHIELD_OFF_FOR_SITE, AddonPolicy.shieldState(Arrays.asList(false, true)));
    }

    @Test
    public void tabsUpdateLoadsWebPagesAndTheAddonsOwnPagesOnly() {
        String base = "moz-extension://abc/";
        assertTrue(AddonPolicy.mayLoadInTab("https://example.com/", base));
        assertTrue(AddonPolicy.mayLoadInTab(base + "options.html", base));
        assertFalse(AddonPolicy.mayLoadInTab("moz-extension://other/x.html", base));
        assertFalse(AddonPolicy.mayLoadInTab("javascript:alert(1)", base));
        assertFalse(AddonPolicy.mayLoadInTab("about:config", base));
    }

    @Test
    public void catalogKeepsSignedExtensionsAndPicksTheLanguage() throws Exception {
        String body = "{\"next\":null,\"results\":["
                + "{\"addon\":{\"guid\":\"a@x\",\"type\":\"extension\",\"name\":{\"en-US\":\"A\",\"es\":\"A es\"},"
                + "\"summary\":\"s\",\"current_version\":{\"version\":\"1\",\"files\":[{\"url\":\"https://x/a.xpi\","
                + "\"permissions\":[\"tabs\"]}]}}},"
                + "{\"addon\":{\"guid\":\"t@x\",\"type\":\"statictheme\",\"name\":\"T\",\"summary\":\"\","
                + "\"current_version\":{\"version\":\"1\",\"files\":[{\"url\":\"https://x/t.xpi\"}]}}},"
                + "{\"addon\":{\"guid\":\"h@x\",\"type\":\"extension\",\"name\":\"H\",\"summary\":\"\","
                + "\"current_version\":{\"version\":\"1\",\"files\":[{\"url\":\"http://x/h.xpi\"}]}}}"
                + "]}";
        AmoCatalog.Page page = AmoCatalog.parse(body, "es-ES");
        assertEquals(1, page.entries.size());
        assertEquals("a@x", page.entries.get(0).id);
        assertEquals("A es", page.entries.get(0).name);
        assertEquals(Collections.singletonList("tabs"), page.entries.get(0).permissions);
        assertNull(page.next);
    }

    @Test
    public void catalogUrlIsTheOfficialAndroidCollection() {
        assertTrue(AmoCatalog.collectionUrl("en").startsWith(
                "https://services.addons.mozilla.org/api/v4/accounts/account/mozilla/collections/"
                        + "Extensions-for-Android/addons/"));
        assertFalse(AmoCatalog.isTrustedPageUrl("https://evil.example/next"));
    }

    @Test
    public void manifestSaysIdAndroidAndSignature() throws Exception {
        File xpi = zip("{\"manifest_version\":2,\"name\":\"N\",\"version\":\"1\","
                + "\"browser_specific_settings\":{\"gecko\":{\"id\":\"n@example.org\"},\"gecko_android\":{}}}", true);
        XpiManifest.Info info = XpiManifest.read(xpi);
        assertEquals("n@example.org", info.id);
        assertTrue(info.declaresAndroid);
        assertTrue(info.signed);
        assertFalse(info.selfUpdates);

        File plain = zip("{\"manifest_version\":3,\"name\":\"N\",\"version\":\"1\"}", false);
        XpiManifest.Info p = XpiManifest.read(plain);
        assertNull(p.id);
        assertFalse(p.declaresAndroid);
        assertFalse(p.signed);
    }

    @Test
    public void aFileWithoutAManifestIsNotAnAddon() throws Exception {
        File f = File.createTempFile("nomanifest", ".xpi");
        f.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try {
            XpiManifest.read(f);
            fail("expected NotAnAddonException");
        } catch (XpiManifest.NotAnAddonException expected) {
            // expected
        }
    }

    @Test
    public void permissionLinesFollowFirefox() {
        List<AddonPermissions.Line> all = AddonPermissions.describe(
                Arrays.asList("storage", "tabs", "<all_urls>"), null);
        assertEquals(2, all.size());
        assertEquals(AddonPermissions.ALL_SITES, all.get(0).kind);
        assertEquals("tabs", all.get(1).arg);

        List<String> sites = Arrays.asList("https://a.example/*", "https://b.example/*", "https://c.example/*",
                "https://d.example/*", "https://e.example/*", "https://f.example/*");
        List<AddonPermissions.Line> capped = AddonPermissions.describe(null, sites);
        assertEquals(5, capped.size());
        assertEquals(AddonPermissions.MORE_SITES, capped.get(4).kind);
        assertEquals(2, capped.get(4).count);

        assertTrue(AddonPermissions.dataCollection(Collections.singletonList("none")).isEmpty());
    }

    @Test
    public void siteAccessListsGrantedSitesAndOneAllSitesSwitch() {
        AddonPermissions.SiteAccess access = AddonPermissions.siteAccess(
                Arrays.asList("*://*/*", "<all_urls>", "https://b.example/*"),
                Arrays.asList("*://*.a.example/*", "http://b.example/*"));
        assertEquals(2, access.allSitesPatterns.size());
        assertFalse(access.allSitesGranted);
        assertEquals(2, access.sites.size());
        assertEquals("a.example", access.sites.get(0).label);
        assertTrue(access.sites.get(0).granted);
        assertEquals("b.example", access.sites.get(1).label);
        assertEquals(2, access.sites.get(1).patterns.size());
        assertTrue(access.sites.get(1).granted);
    }

    private static File zip(String manifest, boolean signed) throws Exception {
        File f = File.createTempFile("addon", ".xpi");
        f.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (signed) {
                out.putNextEntry(new ZipEntry("META-INF/mozilla.rsa"));
                out.write(new byte[]{1, 2, 3});
                out.closeEntry();
            }
        }
        return f;
    }
}
