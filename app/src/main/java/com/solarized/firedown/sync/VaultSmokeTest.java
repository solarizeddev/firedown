package com.solarized.firedown.sync;

import android.content.Context;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * Debug-only end-to-end smoke test for the encrypted storage vault. It drives the
 * real client path against the LIVE {@code storage.firedown.app} server — register
 * → chunk+encrypt → presigned PUT → complete → manifest, then restore + verify +
 * delete — so a developer can confirm the whole round-trip on-device before any
 * real vault UI exists.
 *
 * <p><b>Identity:</b> it reuses the SAME recovery code the bookmark sync stores
 * ({@link SyncSecrets}), so the storage account resolves to the same
 * {@link SyncIdentity} (one recovery code → one account on every service, just a
 * distinct content key via the {@code firedown/storage/v1} HKDF info string). When
 * no bookmark code is present (sync not set up) it falls back to a throwaway
 * generated code so the test can still run standalone — that registers a fresh,
 * unrelated account it then cleans up after itself.
 *
 * <p>Never wired into a release build — the only caller is gated on
 * {@code BuildConfig.DEBUG}.
 */
public final class VaultSmokeTest {

    private VaultSmokeTest() {
    }

    /** Outcome of {@link #run}: a human-readable line for a snackbar/log. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /**
     * Runs the full backup → restore → verify → delete round-trip. Blocking — call
     * from a background thread. Never throws; failures come back as
     * {@code Result.ok == false} with the reason.
     *
     * @param context    any Context (used only for the cache dir + recovery code)
     * @param http       the shared OkHttp client
     * @param storageUrl base URL of the storage service (e.g. STORAGE_DEFAULT_BACKEND)
     */
    public static Result run(Context context, OkHttpClient http, String storageUrl) {
        byte[] code = loadOrGenerateCode(context);
        boolean throwaway = !new SyncSecrets(context).hasCode();
        File temp = null;
        File restored = null;
        VaultEntry entry = null;
        VaultEngine engine;
        try {
            SyncIdentity identity = SyncIdentity.fromCode(code);
            StorageApiClient api = new StorageApiClient(http, storageUrl);
            engine = new VaultEngine(api, identity);

            // A small but multi-byte payload so chunking + GCM framing actually run.
            byte[] payload = new byte[64 * 1024];
            new SecureRandom().nextBytes(payload);
            temp = File.createTempFile("vault-smoke", ".bin", context.getCacheDir());
            try (OutputStream os = new FileOutputStream(temp)) {
                os.write(payload);
            }

            entry = engine.backupFile(temp, "application/octet-stream", null);

            // Pull the manifest back fresh and confirm our entry is present (proves
            // the OCC manifest push landed, not just the object upload).
            List<VaultEntry> manifest = engine.loadManifest();
            boolean inManifest = false;
            for (VaultEntry e : manifest) {
                if (entry.objectId.equals(e.objectId)) {
                    inManifest = true;
                    break;
                }
            }
            if (!inManifest) {
                return new Result(false, "FAIL: object not in restored manifest");
            }

            restored = File.createTempFile("vault-smoke-out", ".bin", context.getCacheDir());
            engine.restoreFile(entry, restored);

            byte[] roundTripped = readAll(restored);
            if (!Arrays.equals(payload, roundTripped)) {
                return new Result(false, "FAIL: restored bytes differ ("
                        + roundTripped.length + " vs " + payload.length + ")");
            }

            engine.deleteEntry(entry);
            entry = null; // deleted cleanly — don't double-delete in finally

            return new Result(true, "Vault OK"
                    + (throwaway ? " (throwaway identity)" : " (bookmark identity)")
                    + " — " + payload.length + "B round-tripped");
        } catch (Exception e) {
            // Best-effort cleanup of a half-uploaded object so a failed run doesn't
            // leak quota on the account.
            if (entry != null) {
                try {
                    new VaultEngine(new StorageApiClient(http, storageUrl),
                            SyncIdentity.fromCode(code)).deleteEntry(entry);
                } catch (Exception ignored) {
                    // already failing; nothing more to do
                }
            }
            return new Result(false, "FAIL: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        } finally {
            SyncSecrets.wipe(code);
            deleteQuietly(temp);
            deleteQuietly(restored);
        }
    }

    /** The bookmark recovery code if set up, else a fresh throwaway one. */
    private static byte[] loadOrGenerateCode(Context context) {
        byte[] stored = new SyncSecrets(context).load();
        if (stored != null && stored.length == SyncIdentity.RECOVERY_CODE_BYTES) {
            return stored;
        }
        return SyncIdentity.generateRecoveryCode();
    }

    private static byte[] readAll(File file) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
