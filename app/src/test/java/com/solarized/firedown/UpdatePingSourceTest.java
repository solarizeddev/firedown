package com.solarized.firedown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdatePingSourceTest {

    private static final String OWN = "com.solarized.firedown";

    @Test
    public void storesAreNamed() {
        assertEquals("fdroid", UpdatePingSource.bucketFor("org.fdroid.fdroid", OWN));
        assertEquals("fdroid", UpdatePingSource.bucketFor("org.fdroid.basic", OWN));
        assertEquals("droidify", UpdatePingSource.bucketFor("com.looker.droidify", OWN));
        assertEquals("neostore", UpdatePingSource.bucketFor("com.machiav3lli.fdroid", OWN));
        assertEquals("obtainium", UpdatePingSource.bucketFor("dev.imranr.obtainium", OWN));
        assertEquals("obtainium", UpdatePingSource.bucketFor("dev.imranr.obtainium.fdroid", OWN));
        assertEquals("play", UpdatePingSource.bucketFor("com.android.vending", OWN));
    }

    @Test
    public void manualInstallsAreSideloads() {
        assertEquals("sideload", UpdatePingSource.bucketFor(null, OWN));
        assertEquals("sideload", UpdatePingSource.bucketFor("", OWN));
        assertEquals("sideload",
                UpdatePingSource.bucketFor("com.google.android.packageinstaller", OWN));
        assertEquals("sideload", UpdatePingSource.bucketFor("com.android.packageinstaller", OWN));
    }

    @Test
    public void selfUpdateIsApp() {
        assertEquals("app", UpdatePingSource.bucketFor(OWN, OWN));
        assertEquals("app", UpdatePingSource.bucketFor(OWN + ".debug", OWN + ".debug"));
    }

    @Test
    public void unknownInstallerNeverSendsItsName() {
        String bucket = UpdatePingSource.bucketFor("com.example.rare.installer", OWN);
        assertEquals("other", bucket);
        assertTrue(UpdatePingSource.isKnown(bucket));
    }

    @Test
    public void everyBucketIsAListedValue() {
        String[] installers = {null, "", OWN, "org.fdroid.fdroid", "com.looker.droidify",
                "com.machiav3lli.fdroid", "dev.imranr.obtainium", "com.android.vending",
                "com.android.packageinstaller", "com.android.shell", "x"};
        for (String installer : installers) {
            assertTrue(installer, UpdatePingSource.isKnown(UpdatePingSource.bucketFor(installer, OWN)));
        }
        assertFalse(UpdatePingSource.isKnown("com.android.shell"));
        assertFalse(UpdatePingSource.isKnown(null));
    }

    @Test
    public void firstReadingIsKept() {
        assertEquals("fdroid", UpdatePingSource.choose(null, "fdroid"));
        // A self-update later must not overwrite where the install came from.
        assertEquals("fdroid", UpdatePingSource.choose("fdroid", "app"));
        assertEquals("sideload", UpdatePingSource.choose("sideload", "app"));
        assertEquals("sideload", UpdatePingSource.choose("sideload", "obtainium"));
    }

    @Test
    public void unknownOriginIsReplacedByARealOne() {
        assertEquals("app", UpdatePingSource.choose(null, "app"));
        assertEquals("app", UpdatePingSource.choose("app", "app"));
        assertEquals("fdroid", UpdatePingSource.choose("app", "fdroid"));
        assertEquals("sideload", UpdatePingSource.choose("app", "sideload"));
        // A value a later build dropped is read again.
        assertEquals("obtainium", UpdatePingSource.choose("retired", "obtainium"));
    }
}
