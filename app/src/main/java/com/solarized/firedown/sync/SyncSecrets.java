package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * At-rest storage for the bookmarks-sync recovery code. The code is the only
 * secret on the device that the whole identity (and the data key) derives from,
 * so it lives in the backup-EXCLUDED {@code secret_shared_prefs} file (see the
 * Auto Backup include-list) AND is wrapped with an AndroidKeyStore AES-256-GCM
 * key (hardware-backed where available) so a raw prefs dump doesn't reveal it.
 *
 * <p>Format of the stored value: base64({@code iv(12) || ciphertext+tag}).</p>
 */
public final class SyncSecrets {

    private static final String PREFS = "secret_shared_prefs";
    private static final String KEY_RECOVERY = "firedown.sync.recovery_code";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "firedown.sync.wrap.v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    public SyncSecrets(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a recovery code is currently stored (sync is set up). */
    public boolean hasCode() {
        return prefs.contains(KEY_RECOVERY);
    }

    /** Persists the recovery-code bytes (keystore-wrapped). */
    public void store(byte[] code) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // AndroidKeyStore GCM keys mandate a RANDOMIZED IV the keystore picks
            // itself (setRandomizedEncryptionRequired defaults true) — passing a
            // caller-supplied IV throws "Caller-provided IV not permitted". So
            // init WITHOUT a spec and read the generated IV back (12 bytes for
            // GCM, matching IV_BYTES); only DECRYPT supplies the stored IV.
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(code);
            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
            prefs.edit().putString(KEY_RECOVERY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("store recovery code failed", e);
        }
    }

    /** Returns the recovery-code bytes, or null if none stored / unreadable. */
    public byte[] load() {
        String stored = prefs.getString(KEY_RECOVERY, null);
        if (stored == null) {
            return null;
        }
        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            if (blob.length <= IV_BYTES) {
                return null;
            }
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), spec);
            return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    /** Wipes the stored code (sign-out). The keystore alias is left in place
     *  (harmless; reused if the user re-enables). */
    public void clear() {
        prefs.edit().remove(KEY_RECOVERY).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = getKey();
        if (existing != null) {
            return existing;
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    private SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        return null;
    }

    /** Scrubs a byte array in place (best-effort). */
    public static void wipe(byte[] b) {
        if (b != null) {
            Arrays.fill(b, (byte) 0);
        }
    }
}
