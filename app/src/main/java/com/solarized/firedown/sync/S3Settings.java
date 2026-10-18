package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The saved S3 bucket. It lives in the backup-EXCLUDED
 * {@code secret_shared_prefs} beside the recovery code, with BOTH keys (the
 * access key id and the secret) wrapped under the same keystore key
 * ({@link SyncSecrets#seal}), the way {@link WebDavSettings} keeps the WebDAV
 * password: neither a cloud backup of the app's settings nor a raw prefs dump
 * carries them, and a restore onto a new phone signs in again. The endpoint,
 * region, bucket, prefix and addressing are kept plain: they are what the
 * Backup screen names and what the sign-in page prefills, and reading them
 * costs no keystore call.
 *
 * <p>Saving one does not by itself make the bucket THE backup place: the
 * stored kind does ({@link BackupStores#place}), which
 * {@code CloudBackupManager.adoptS3} sets with the save; choosing another
 * place {@link #clear}s this (the bucket and its objects are never touched).
 * {@link #generation()} changes on every save and clear, so a cached store
 * knows when it is stale without unwrapping a key.
 */
public final class S3Settings {

    private static final String KEY_ENDPOINT = "firedown.backup.s3.endpoint";
    private static final String KEY_REGION = "firedown.backup.s3.region";
    private static final String KEY_BUCKET = "firedown.backup.s3.bucket";
    private static final String KEY_PREFIX = "firedown.backup.s3.prefix";
    private static final String KEY_ACCESS_KEY = "firedown.backup.s3.access_key";
    private static final String KEY_SECRET = "firedown.backup.s3.secret";
    private static final String KEY_PATH_STYLE = "firedown.backup.s3.path_style";
    private static final String KEY_ADDRESSING = "firedown.backup.s3.addressing";
    private static final String KEY_PIN = "firedown.backup.s3.pin";

    private static final AtomicInteger GENERATION = new AtomicInteger();

    private final SharedPreferences prefs;

    public S3Settings(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(SyncSecrets.PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a bucket is saved (cheap: no keystore). */
    public boolean isConfigured() {
        return prefs.contains(KEY_ENDPOINT);
    }

    /** The saved endpoint, for labels and the prefill (cheap). */
    @Nullable
    public String endpoint() {
        return prefs.getString(KEY_ENDPOINT, null);
    }

    /** The saved region (cheap). */
    @Nullable
    public String region() {
        return prefs.getString(KEY_REGION, null);
    }

    /** The saved bucket (cheap). */
    @Nullable
    public String bucket() {
        return prefs.getString(KEY_BUCKET, null);
    }

    /** The saved key prefix, "" for none (cheap). */
    @NonNull
    public String prefix() {
        return prefs.getString(KEY_PREFIX, "");
    }

    /** The addressing the user chose (cheap). */
    @NonNull
    public S3Config.Addressing addressing() {
        return S3Config.Addressing.of(prefs.getString(KEY_ADDRESSING, null));
    }

    /** The saved bucket with its keys unwrapped, or null when none is saved
     *  or a key can no longer be unwrapped (the keystore key is gone). Off
     *  the main thread. */
    @Nullable
    public S3Config load() {
        String endpoint = prefs.getString(KEY_ENDPOINT, null);
        String region = prefs.getString(KEY_REGION, null);
        String bucket = prefs.getString(KEY_BUCKET, null);
        if (endpoint == null || region == null || bucket == null) {
            return null;
        }
        String accessKey = unseal(prefs.getString(KEY_ACCESS_KEY, null));
        String secret = unseal(prefs.getString(KEY_SECRET, null));
        if (accessKey == null || secret == null) {
            return null;
        }
        return new S3Config(endpoint, region, bucket, prefix(), accessKey, secret,
                prefs.getBoolean(KEY_PATH_STYLE, true), addressing(),
                prefs.getString(KEY_PIN, null));
    }

    @Nullable
    private static String unseal(@Nullable String sealed) {
        byte[] plain = SyncSecrets.open(sealed);
        if (plain == null) {
            return null;
        }
        String s = new String(plain, StandardCharsets.UTF_8);
        Arrays.fill(plain, (byte) 0);
        return s;
    }

    private static String seal(String value) {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        String sealed = SyncSecrets.seal(plain);
        Arrays.fill(plain, (byte) 0);
        return sealed;
    }

    /** Saves {@code config}. {@code apply()} is enough: every reader shares
     *  this process's prefs instance, which changes at once. */
    public void save(@NonNull S3Config config) {
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_ENDPOINT, config.endpoint)
                .putString(KEY_REGION, config.region)
                .putString(KEY_BUCKET, config.bucket)
                .putString(KEY_PREFIX, config.prefix)
                .putString(KEY_ACCESS_KEY, seal(config.accessKeyId))
                .putString(KEY_SECRET, seal(config.secret))
                .putBoolean(KEY_PATH_STYLE, config.pathStyle)
                .putString(KEY_ADDRESSING, config.addressing.value);
        if (config.pinSha256 == null) {
            edit.remove(KEY_PIN);
        } else {
            edit.putString(KEY_PIN, config.pinSha256);
        }
        edit.apply();
        GENERATION.incrementAndGet();
    }

    /** Forgets the bucket: every saved field, the keys included. Nothing in
     *  the bucket is touched. */
    public void clear() {
        if (!isConfigured() && !prefs.contains(KEY_SECRET) && !prefs.contains(KEY_ACCESS_KEY)) {
            return;
        }
        prefs.edit().remove(KEY_ENDPOINT).remove(KEY_REGION).remove(KEY_BUCKET)
                .remove(KEY_PREFIX).remove(KEY_ACCESS_KEY).remove(KEY_SECRET)
                .remove(KEY_PATH_STYLE).remove(KEY_ADDRESSING).remove(KEY_PIN)
                .apply();
        GENERATION.incrementAndGet();
    }

    /** Changes on every {@link #save} and {@link #clear} in this process. */
    static int generation() {
        return GENERATION.get();
    }
}
