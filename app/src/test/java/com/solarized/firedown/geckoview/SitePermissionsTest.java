package com.solarized.firedown.geckoview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The rules of the shield sheet's "Site permissions" list (SitePermissions). */
public class SitePermissionsTest {

    private static final int DRM = 6;
    private static final int AUTOPLAY = 5;
    private static final int NOTIFICATION = 1;
    private static final int PROMPT = 3;

    private static SitePermissions.Stored<String> s(int type, int value, String third, String id) {
        return new SitePermissions.Stored<>(type, value, third, id);
    }

    @Test
    public void nothingStoredIsEmpty() {
        assertTrue(SitePermissions.of(null).isEmpty());
        assertTrue(SitePermissions.<String>of(Collections.emptyList()).isEmpty());
    }

    @Test
    public void onlyAnswersOfTheListedKindsAreShown() {
        SitePermissions<String> p = SitePermissions.of(Arrays.asList(
                s(DRM, PROMPT, null, "drm"),
                s(AUTOPLAY, SitePermissions.VALUE_ALLOW, null, "autoplay"),
                s(NOTIFICATION, SitePermissions.VALUE_DENY, null, "notif"),
                s(SitePermissions.TYPE_PERSISTENT_STORAGE, PROMPT, null, "storage-prompt")));
        assertTrue(p.isEmpty());
    }

    @Test
    public void rowsAreOrderedLocationDataThenEmbeddedSites() {
        SitePermissions<String> p = SitePermissions.of(Arrays.asList(
                s(SitePermissions.TYPE_STORAGE_ACCESS, SitePermissions.VALUE_ALLOW, "https://zeta.example", "z"),
                s(SitePermissions.TYPE_PERSISTENT_STORAGE, SitePermissions.VALUE_ALLOW, null, "data"),
                s(SitePermissions.TYPE_STORAGE_ACCESS, SitePermissions.VALUE_ALLOW, "https://comments.example", "c"),
                s(SitePermissions.TYPE_LOCATION, SitePermissions.VALUE_DENY, null, "loc")));
        assertEquals(4, p.rows.size());
        assertEquals(SitePermissions.TYPE_LOCATION, p.rows.get(0).type);
        assertFalse(p.rows.get(0).allowed);
        assertEquals(SitePermissions.TYPE_PERSISTENT_STORAGE, p.rows.get(1).type);
        assertEquals("comments.example", p.rows.get(2).thirdPartyHost);
        assertEquals("zeta.example", p.rows.get(3).thirdPartyHost);
        assertEquals(3, p.allowedCount);
        assertEquals(1, p.blockedCount);
    }

    @Test
    public void duplicatesAreOneRowTheLatestWinsAndResetForgetsBoth() {
        SitePermissions<String> p = SitePermissions.of(Arrays.asList(
                s(SitePermissions.TYPE_LOCATION, SitePermissions.VALUE_ALLOW, null, "old"),
                s(SitePermissions.TYPE_LOCATION, SitePermissions.VALUE_DENY, null, "new")));
        assertEquals(1, p.rows.size());
        assertFalse(p.rows.get(0).allowed);
        assertEquals(Arrays.asList("old", "new"), p.rows.get(0).handles);
    }

    @Test
    public void storageAccessIsOneRowPerEmbeddedSite() {
        SitePermissions<String> p = SitePermissions.of(Arrays.asList(
                s(SitePermissions.TYPE_STORAGE_ACCESS, SitePermissions.VALUE_ALLOW, "https://www.comments.example", "a"),
                s(SitePermissions.TYPE_STORAGE_ACCESS, SitePermissions.VALUE_ALLOW, "https://comments.example^privateBrowsingId=1", "b"),
                s(SitePermissions.TYPE_STORAGE_ACCESS, SitePermissions.VALUE_ALLOW, "https://other.example:8443", "c")));
        assertEquals(2, p.rows.size());
        assertEquals("comments.example", p.rows.get(0).thirdPartyHost);
        assertEquals(2, p.rows.get(0).handles.size());
        assertEquals("other.example", p.rows.get(1).thirdPartyHost);
    }

    @Test
    public void storageAccessWithNoOriginStillShows() {
        SitePermissions<String> p = SitePermissions.of(Collections.singletonList(
                s(SitePermissions.TYPE_STORAGE_ACCESS, SitePermissions.VALUE_ALLOW, null, "x")));
        assertEquals(1, p.rows.size());
        assertNull(p.rows.get(0).thirdPartyHost);
    }

    @Test
    public void nullEntriesAreSkipped() {
        List<SitePermissions.Stored<String>> list = new ArrayList<>();
        list.add(null);
        list.add(s(SitePermissions.TYPE_PERSISTENT_STORAGE, SitePermissions.VALUE_ALLOW, null, "d"));
        assertEquals(1, SitePermissions.of(list).rows.size());
    }

    @Test
    public void hostOfReadsEveryOriginShape() {
        assertEquals("a.example", SitePermissions.hostOf("https://a.example"));
        assertEquals("a.example", SitePermissions.hostOf("https://A.Example:443/"));
        assertEquals("a.example", SitePermissions.hostOf("https://www.a.example^userContextId=2"));
        assertEquals("[::1]", SitePermissions.hostOf("http://[::1]:8080"));
        assertEquals("a.example", SitePermissions.hostOf("https://user@a.example/path"));
        assertEquals("www", SitePermissions.hostOf("https://www"));
        assertNull(SitePermissions.hostOf(null));
        assertNull(SitePermissions.hostOf("https://"));
    }
}
