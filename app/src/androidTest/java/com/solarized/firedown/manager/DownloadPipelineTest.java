package com.solarized.firedown.manager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.utils.DataUris;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import dagger.hilt.android.EntryPointAccessors;

/**
 * Three downloads through the REAL service — {@link RunnableManager},
 * {@link DownloadEngine}, {@link DownloadTask}, {@link HttpDownloadStrategy},
 * Room — started the way the UI starts them ({@link DownloadIntents}), each
 * checked where its file landed and what its row says:
 * <ul>
 *   <li>a private tab's download ({@code saveToVault}) lands in the
 *       app-private safe folder, never beside the public downloads;</li>
 *   <li>a regular download lands in the download folder;</li>
 *   <li>a {@code data:} URL too large for the Intent is decoded in-process:
 *       the file holds the payload, the row keeps only the header, and the
 *       origin is the page the URL came from.</li>
 * </ul>
 * The bytes come from {@link DownloadEngineStressTest.ByteServer} on
 * 127.0.0.1 (no network), compared byte for byte against its pattern. The
 * service must stop itself once the queue is empty.
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant, device
 * attached) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.manager.DownloadPipelineTest
 * </pre>
 * Rows and files are removed through the service's own delete at the end.
 */
@RunWith(AndroidJUnit4.class)
public class DownloadPipelineTest {

    private static final long FINISH_MS = 90_000;
    private static final int SIZE = 96 * 1024;

    private Context mApp;
    private DownloadDataRepository mRepo;
    private DownloadEngineStressTest.ByteServer mServer;
    private ActivityScenario<DownloadsActivity> mScenario;
    /** The file_url of every row this test created (a data: row keeps its header). */
    private final List<String> mUrls = new ArrayList<>();
    /** The origin of every data: row, which is what tells one apart. */
    private final List<String> mOrigins = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RepositoryEntryPoint ep = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class);
        mRepo = ep.getDownloadDataRepository();
        assertNotNull(mRepo);
        mServer = new DownloadEngineStressTest.ByteServer();
        mServer.start();
        // The app must be in the foreground for startService → startForeground
        // to be allowed on Android 12+ (the stress test's reasoning).
        mScenario = ActivityScenario.launch(new Intent(mApp, DownloadsActivity.class));
    }

    @After
    public void tearDown() throws Exception {
        try {
            List<DownloadEntity> ours = ours();
            if (!ours.isEmpty()) {
                Intent delete = new Intent(mApp, RunnableManager.class);
                delete.setAction(IntentActions.DOWNLOAD_DELETE);
                delete.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, new ArrayList<>(ours));
                mApp.startService(delete);
                waitUntil(() -> ours().isEmpty(), 30_000);
                for (DownloadEntity e : ours) {
                    if (e.getFilePath() != null) {
                        new File(e.getFilePath()).delete();
                    }
                }
            }
        } finally {
            if (mServer != null) {
                mServer.stop();
            }
            if (mScenario != null) {
                mScenario.close();
            }
        }
    }

    @Test
    public void aVaultDownloadLandsInTheSafeFolder() throws Exception {
        String url = serverUrl("vault");
        DownloadIntents.start(mApp, request(url).saveToVault(true).build());

        DownloadEntity row = awaitFinished(url, null);
        String safeRoot = StoragePaths.getSafePath(mApp);
        assertTrue(safeRoot.startsWith(mApp.getFilesDir().getAbsolutePath()));
        assertTrue(row.isFileSafe());
        assertTrue("vault file at " + row.getFilePath(),
                row.getFilePath().startsWith(safeRoot + File.separator));
        assertFalse(row.getFilePath().startsWith(StoragePaths.getDownloadPath(mApp) + File.separator));
        assertPatternFile(row.getFilePath(), url);
        awaitIdle();
    }

    @Test
    public void aRegularDownloadLandsInTheDownloadFolder() throws Exception {
        String url = serverUrl("regular");
        DownloadIntents.start(mApp, request(url).build());

        DownloadEntity row = awaitFinished(url, null);
        assertFalse(row.isFileSafe());
        assertTrue("download at " + row.getFilePath(),
                row.getFilePath().startsWith(StoragePaths.getDownloadPath(mApp) + File.separator));
        assertPatternFile(row.getFilePath(), url);
        awaitIdle();
    }

    @Test
    public void anOversizedDataUrlIsDecodedInProcess() throws Exception {
        byte[] payload = new byte[200 * 1024];
        new Random(42).nextBytes(payload);
        String header = "data:application/octet-stream;base64,";
        String url = header + Base64.getEncoder().encodeToString(payload);
        String origin = "https://example.test/gallery/" + System.nanoTime();
        String name = "pipeline-data-" + System.nanoTime() + ".bin";
        mUrls.add(header);
        mOrigins.add(origin);

        // Over the inline bound, so the request is parked and the Intent
        // carries a token the engine redeems in-process.
        DownloadIntents.start(mApp, new DownloadRequest.Builder(url)
                .name(name)
                .origin(origin)
                .mimeType("application/octet-stream")
                .fileType(UrlType.FILE.getValue())
                .build());

        DownloadEntity row = awaitFinished(header, origin);
        assertEquals("the row keeps the header only", header, row.getFileUrl());
        assertEquals(DataUris.stripPayload(url), row.getFileUrl());
        assertEquals(origin, row.getOriginUrl());
        assertTrue(row.getFilePath().startsWith(StoragePaths.getDownloadPath(mApp) + File.separator));
        File file = new File(row.getFilePath());
        assertTrue(file.exists());
        assertArrayEquals(payload, Files.readAllBytes(file.toPath()));
        awaitIdle();
    }

    // ---------------------------------------------------------------------

    private String serverUrl(String kind) {
        String name = "pipeline-" + kind + "-" + System.nanoTime() + ".bin";
        String url = mServer.base() + "/" + kind + ".bin?size=" + SIZE + "&delay=0&name=" + name;
        mUrls.add(url);
        return url;
    }

    private static DownloadRequest.Builder request(String url) {
        String name = url.substring(url.indexOf("name=") + 5);
        return new DownloadRequest.Builder(url)
                .name(name)
                .mimeType("application/octet-stream")
                .fileType(UrlType.FILE.getValue());
    }

    private DownloadEntity rowFor(String url, String origin) {
        for (DownloadEntity e : mRepo.getAllRawList()) {
            if (!url.equals(e.getFileUrl())) {
                continue;
            }
            if (origin == null || origin.equals(e.getOriginUrl())) {
                return e;
            }
        }
        return null;
    }

    private DownloadEntity awaitFinished(String url, String origin) throws InterruptedException {
        assertTrue("no terminal row for " + url, waitUntil(() -> {
            DownloadEntity e = rowFor(url, origin);
            return e != null && (e.getFileStatus() == Download.FINISHED || e.getFileStatus() == Download.ERROR);
        }, FINISH_MS));
        DownloadEntity row = rowFor(url, origin);
        assertEquals("row ended in ERROR, type " + row.getFileErrorType(),
                Download.FINISHED, row.getFileStatus());
        assertNotNull(row.getFilePath());
        return row;
    }

    private void awaitIdle() throws InterruptedException {
        assertTrue("the service must stop itself once idle",
                waitUntil(() -> !RunnableManager.isRunning(), 20_000));
    }

    private void assertPatternFile(String path, String url) throws IOException {
        File file = new File(path);
        assertTrue("file at " + path, file.exists());
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertEquals(SIZE, bytes.length);
        int salt = mServer.saltOf(url);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != DownloadEngineStressTest.ByteServer.pattern(i, salt)) {
                throw new AssertionError("byte " + i + " of " + path + " differs from the served pattern");
            }
        }
    }

    private List<DownloadEntity> ours() {
        List<DownloadEntity> out = new ArrayList<>();
        for (DownloadEntity e : mRepo.getAllRawList()) {
            String url = e.getFileUrl();
            if (url == null) {
                continue;
            }
            if (mUrls.contains(url) && (!DataUris.isDataUri(url) || mOrigins.contains(e.getOriginUrl()))) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean waitUntil(BooleanSupplier condition, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(250);
        }
        return true;
    }
}
