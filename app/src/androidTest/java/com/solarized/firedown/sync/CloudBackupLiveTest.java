package com.solarized.firedown.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * OPT-IN on-device end-to-end tests of the Cloud Backup CORE (no UI) against the
 * LIVE storage service — the abuse/race/edge scenarios that can only be proven
 * against the real deployed binary: delete↔backup loops, two-device races,
 * connection-lost uploads, and a modified client's forged credit. Each test uses
 * a fresh THROWAWAY account (own recovery code) and erases it afterwards
 * (right-to-erasure endpoint), so nothing accumulates server-side.
 *
 * <p>Skipped unless you pass {@code -e storageLiveTest 1} — never runs in a normal
 * test pass. Requires the storage-api build that includes the atomic manifest
 * upsert (the concurrent first-push fix); on an older binary
 * {@link #concurrentFirstPushExactlyOneWins} will fail with multiple winners —
 * which is exactly the regression it exists to catch.
 *
 * <p>Run one test at a time (recommended — each registers via the PoW +
 * Cloudflare-rate-limited endpoints):
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.sync.CloudBackupLiveTest#backupRestoreRoundTrip \
 *     -Pandroid.testInstrumentationRunnerArguments.storageLiveTest=1
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class CloudBackupLiveTest {

    private static final String TAG = "STORAGE-LIVE";
    private static final String STORAGE = "https://storage.firedown.app";
    private static final String MINT = "https://mint.firedown.app";

    private OkHttpClient mHttp;
    private StorageApiClient mStorage;
    private SyncIdentity mId;
    private final List<File> mTempFiles = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        String opt = InstrumentationRegistry.getArguments().getString("storageLiveTest");
        Assume.assumeTrue("opt-in only: pass -e storageLiveTest 1", "1".equals(opt));
        mHttp = new OkHttpClient();
        mStorage = new StorageApiClient(mHttp, STORAGE);
        // Fresh throwaway account per test — no cross-test state, and the PoW
        // register exercises the real challenge flow every run.
        mId = SyncIdentity.fromCode(SyncIdentity.generateRecoveryCode());
        mStorage.register(mId);
        Log.i(TAG, "registered throwaway account " + mId.accountBase32());
    }

    @After
    public void tearDown() {
        for (File f : mTempFiles) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        if (mStorage != null && mId != null) {
            try {
                mStorage.deleteAccount(mId); // erase the throwaway account + objects
            } catch (Exception e) {
                Log.w(TAG, "cleanup deleteAccount failed (harmless): " + e);
            }
        }
    }

    // ---- helpers ----

    /** A temp file of {@code size} deterministic pseudo-random bytes (seeded, so a
     *  corrupted restore diff is reproducible). */
    private File tempFile(String name, int size, long seed) throws IOException {
        File dir = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir();
        File f = new File(dir, name);
        Random rnd = new Random(seed);
        byte[] buf = new byte[64 * 1024];
        try (FileOutputStream out = new FileOutputStream(f)) {
            int written = 0;
            while (written < size) {
                rnd.nextBytes(buf);
                int n = Math.min(buf.length, size - written);
                out.write(buf, 0, n);
                written += n;
            }
        }
        mTempFiles.add(f);
        return f;
    }

    private static byte[] sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    private VaultEntry findByName(List<VaultEntry> entries, String name) {
        for (VaultEntry e : entries) {
            if (name.equals(e.name)) {
                return e;
            }
        }
        return null;
    }

    // ---- the scenarios ----

    /**
     * The core promise: backup → manifest lists it → restore is byte-identical →
     * delete → gone (manifest AND object). Multi-chunk on purpose (9 MiB &gt; the
     * 8 MiB chunk size) so the chunk split/reassembly + per-chunk encryption are
     * exercised, not just the single-chunk fast path.
     */
    @Test
    public void backupRestoreRoundTrip() throws Exception {
        VaultEngine engine = new VaultEngine(mStorage, mId);
        File src = tempFile("live-roundtrip.bin", 9 * 1024 * 1024, 42);
        byte[] wantHash = sha256(src);

        VaultEntry entry = engine.backupFile(src, "application/octet-stream", null);
        assertEquals(src.length(), entry.size);
        assertEquals(2, entry.chunkCount);

        List<VaultEntry> manifest = engine.loadManifest();
        assertNotNull("backed-up file missing from manifest", findByName(manifest, src.getName()));

        File dst = tempFile("live-roundtrip-restored.bin", 0, 0);
        engine.restoreFile(entry, dst);
        assertEquals(src.length(), dst.length());
        assertArrayEquals("restored bytes differ from the original", wantHash, sha256(dst));

        engine.deleteEntry(entry);
        assertEquals("manifest not empty after delete", 0, engine.loadManifest().size());
        try {
            mStorage.getObject(mId, entry.objectId);
            fail("deleted object still served");
        } catch (StorageApiClient.FatalException expected) {
            Log.i(TAG, "deleted object correctly refused: " + expected.slug);
        }
    }

    /** Tapping "Back up to cloud" twice on the same download must be a no-op —
     *  same objectId back, ONE manifest entry, no second object billed. */
    @Test
    public void duplicateBackupIsNoOp() throws Exception {
        VaultEngine engine = new VaultEngine(mStorage, mId);
        File src = tempFile("live-dup.bin", 256 * 1024, 7);

        VaultEntry first = engine.backupFile(src, "application/octet-stream", null);
        VaultEntry second = engine.backupFile(src, "application/octet-stream", null);
        assertEquals("re-backup created a second object", first.objectId, second.objectId);
        assertEquals(1, engine.loadManifest().size());
    }

    /**
     * The delete↔upload loop: backup → delete → backup again, three rounds — the
     * "going back and forth without waiting" usage. Every round must end in a
     * consistent state (exactly one manifest entry, a FRESH objectId — the deleted
     * object can never be resurrected), and the final entry must restore.
     */
    @Test
    public void deleteBackupLoop() throws Exception {
        VaultEngine engine = new VaultEngine(mStorage, mId);
        File src = tempFile("live-loop.bin", 512 * 1024, 99);
        byte[] wantHash = sha256(src);

        String previousObjectId = null;
        for (int round = 0; round < 3; round++) {
            VaultEntry entry = engine.backupFile(src, "application/octet-stream", null);
            if (previousObjectId != null) {
                assertNotEquals("round " + round + " reused the deleted object id",
                        previousObjectId, entry.objectId);
            }
            List<VaultEntry> manifest = engine.loadManifest();
            assertEquals("round " + round + ": manifest entry count", 1, manifest.size());

            engine.deleteEntry(entry);
            assertEquals("round " + round + ": manifest not empty after delete",
                    0, engine.loadManifest().size());
            previousObjectId = entry.objectId;
        }

        // Final backup stays, and its bytes still come back intact.
        VaultEntry last = engine.backupFile(src, "application/octet-stream", null);
        File dst = tempFile("live-loop-restored.bin", 0, 0);
        engine.restoreFile(last, dst);
        assertArrayEquals(wantHash, sha256(dst));
    }

    /**
     * Two devices back up the SAME file content at once (same recovery code, two
     * engines, two threads). The commit-time dedup (`commitDeduped`) must leave
     * EXACTLY ONE manifest entry for that name+size — the losing upload's object
     * is dropped as an orphan, never a duplicate row — and both callers get the
     * same winning entry back. The winner must restore.
     */
    @Test
    public void concurrentSameFileBackupDedups() throws Exception {
        File src = tempFile("live-race.bin", 384 * 1024, 1234);
        byte[] wantHash = sha256(src);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<VaultEntry>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                // Each "device" gets its own engine + client (own connection state).
                VaultEngine engine = new VaultEngine(new StorageApiClient(mHttp, STORAGE), mId);
                results.add(pool.submit(() -> {
                    start.await();
                    return engine.backupFile(src, "application/octet-stream", null);
                }));
            }
            start.countDown();
            VaultEntry a = results.get(0).get();
            VaultEntry b = results.get(1).get();

            assertEquals("the two racers must converge on ONE committed entry",
                    a.objectId, b.objectId);
            VaultEngine engine = new VaultEngine(mStorage, mId);
            List<VaultEntry> manifest = engine.loadManifest();
            assertEquals("manifest gained a duplicate from the race", 1, manifest.size());

            File dst = tempFile("live-race-restored.bin", 0, 0);
            engine.restoreFile(manifest.get(0), dst);
            assertArrayEquals("the surviving object must be the real bytes",
                    wantHash, sha256(dst));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The manifest OCC first-push race at the RAW API layer: N concurrent
     * prevVersion=0 pushes on a fresh account. Exactly ONE may win; the rest get
     * a version conflict. This is the end-to-end twin of the server's
     * TestConcurrentManifestOCC (which caught a real lost-update bug in the
     * SELECT-FOR-UPDATE upsert) — multiple winners here means the deployed binary
     * predates the atomic-upsert fix.
     */
    @Test
    public void concurrentFirstPushExactlyOneWins() throws Exception {
        final int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            Random rnd = new Random(5);
            for (int i = 0; i < workers; i++) {
                byte[] blob = new byte[64];
                rnd.nextBytes(blob);
                StorageApiClient client = new StorageApiClient(mHttp, STORAGE);
                futures.add(pool.submit(() -> {
                    start.await();
                    StorageApiClient.ManifestPut put = client.pushManifest(mId, blob, 0);
                    if (put.ok) {
                        ok.incrementAndGet();
                    } else {
                        conflict.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            assertEquals("exactly one first-push may win (lost-update!)", 1, ok.get());
            assertEquals(workers - 1, conflict.get());
            assertEquals("final manifest version must be 1",
                    1, mStorage.pullManifest(mId).version);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Connection lost mid-upload: the object was created and one of two chunks
     * uploaded, then the client died. Complete must refuse (409 upload-incomplete),
     * the object must stay invisible (GET refuses, manifest untouched) — and
     * server-side the 6h pending reaper eventually frees it (covered by the Go
     * TestReapPendingAbandoned; unobservable from here).
     */
    @Test
    public void interruptedUploadIsNeverVisible() throws Exception {
        StorageApiClient.CreatedObject created =
                mStorage.createObject(mId, 128 * 1024, 2);
        assertEquals(2, created.uploadUrls.size());
        byte[] half = new byte[64 * 1024];
        new Random(3).nextBytes(half);
        mStorage.putChunk(created.uploadUrls.get(0), half);
        // ...connection dies here; chunk 1 never uploads...

        try {
            mStorage.completeObject(mId, created.objectId);
            fail("complete committed a half-uploaded object");
        } catch (StorageApiClient.FatalException expected) {
            Log.i(TAG, "half-uploaded complete correctly refused: " + expected.slug);
        }
        try {
            mStorage.getObject(mId, created.objectId);
            fail("pending (never-completed) object served for download");
        } catch (StorageApiClient.FatalException expected) {
            // still pending → refused
        }
        assertTrue("manifest must be untouched by the dead upload",
                mStorage.pullManifest(mId).notFound);
    }

    /** A sealed object must never get a new writable URL: refresh-upload-urls is
     *  for resuming a PENDING upload only; once committed it must 409 — otherwise
     *  a modified client could re-open the overwrite window on committed chunks. */
    @Test
    public void refreshRefusedOnceCommitted() throws Exception {
        VaultEngine engine = new VaultEngine(mStorage, mId);
        File src = tempFile("live-sealed.bin", 64 * 1024, 11);
        VaultEntry entry = engine.backupFile(src, "application/octet-stream", null);

        try {
            mStorage.refreshUploadUrls(mId, entry.objectId);
            fail("refresh-upload-urls handed out writable URLs for a COMMITTED object");
        } catch (StorageApiClient.FatalException expected) {
            Log.i(TAG, "post-commit refresh correctly refused: " + expected.slug);
        }
    }

    /**
     * The modified-client forgery: credits are blind-signed bearer tokens, so a
     * client that never paid can only present garbage. Both shapes must be
     * refused as PERMANENT errors (never accepted, never "retry later"):
     * a fabricated signature under a REAL mint keyset, and an unknown keyset id.
     * (The pure math — why forging without the mint's key is impossible — is
     * BlindSignatureForgeryTest; this proves the live redeem path enforces it.)
     */
    @Test
    public void forgedCreditRejected() throws Exception {
        Random rnd = new Random(8);
        byte[] secret = new byte[32];
        rnd.nextBytes(secret);
        byte[] fakeSig = new byte[256];
        rnd.nextBytes(fakeSig);

        // Real keyset id, forged signature.
        MintClient mint = new MintClient(mHttp, MINT);
        List<MintClient.Keyset> keys = mint.fetchKeys();
        Assume.assumeTrue("mint advertises no keysets", !keys.isEmpty());
        MintClient.Keyset target = keys.get(0);
        for (MintClient.Keyset k : keys) {
            if (k.active) {
                target = k; // prefer an active keyset — the realistic forgery target
                break;
            }
        }
        String realKeyset = hex(target.id);
        try {
            mStorage.redeemCredit(mId, realKeyset, hex(secret), hex(fakeSig));
            fail("server accepted a forged credit (FREE MONEY)");
        } catch (StorageApiClient.FatalException expected) {
            Log.i(TAG, "forged signature correctly refused: " + expected.slug);
        }

        // Unknown keyset id entirely.
        try {
            mStorage.redeemCredit(mId, "00000000deadbeef", hex(secret), hex(fakeSig));
            fail("server accepted a credit under an unknown keyset");
        } catch (StorageApiClient.FatalException expected) {
            Log.i(TAG, "unknown keyset correctly refused: " + expected.slug);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
