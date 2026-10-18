package com.solarized.firedown.sync;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Harness stand-in for the keystore wrap: a reversible marker, so the
 * harness can check that what reaches the prefs file is the SEALED form
 * (never the key in the clear) and that settings round-trip through it.
 */
final class SyncSecrets {
    static final String PREFS = "secret_shared_prefs";
    static final String SEALED_PREFIX = "sealed:";
    /** Set by the harness: the keystore key is gone, nothing unwraps. */
    static volatile boolean keyGone;

    private SyncSecrets() {
    }

    static String seal(byte[] plain) {
        byte[] flipped = plain.clone();
        for (int i = 0; i < flipped.length; i++) {
            flipped[i] ^= 0x5a;
        }
        return SEALED_PREFIX + Base64.getEncoder().encodeToString(flipped);
    }

    static byte[] open(String sealed) {
        if (sealed == null || keyGone || !sealed.startsWith(SEALED_PREFIX)) {
            return null;
        }
        byte[] b = Base64.getDecoder().decode(sealed.substring(SEALED_PREFIX.length()));
        for (int i = 0; i < b.length; i++) {
            b[i] ^= 0x5a;
        }
        return b;
    }

    static String toUtf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
