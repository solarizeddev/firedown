package com.solarized.firedown.data.workers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.TestListenableWorkerBuilder;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.MessageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import dagger.hilt.android.EntryPointAccessors;

/**
 * The REAL {@link MediaListenerWorker} against the REAL download database,
 * run directly through WorkManager's test builder (no WorkManager
 * scheduling, no Hilt injection — the repository is handed to the
 * constructor the way Hilt would). Seeds rows and files, runs
 * {@code doWork()}, reads the rows back:
 * <ul>
 *   <li>a vault row whose file sits in the public download folder is moved
 *       into the safe folder and re-pointed (path, name, picture);</li>
 *   <li>a name already taken in the safe folder gets the {@code (1)}
 *       counter, and the file that held the name is untouched;</li>
 *   <li>a regular row and an already-safe row are left where they are;</li>
 *   <li>a FINISHED row whose app-private file is gone becomes
 *       ERROR / FILE_NOT_FOUND, and heals back once the file is there.</li>
 * </ul>
 * The worker is the one the app enqueues on every Downloads resume, so it
 * also walks the device's real rows (marking a missing file the way the
 * resume would); rows this test seeds use an unreachable URL and are removed.
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.data.workers.MediaListenerWorkerTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class MediaListenerWorkerTest {

    private static final String URL_PREFIX = "http://test.invalid/media-listener-worker/";

    private Context mApp;
    private DownloadDataRepository mRepo;
    private String mSafeRoot;
    private String mDownloadRoot;
    private final List<DownloadEntity> mSeeded = new ArrayList<>();
    private final List<File> mFiles = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RepositoryEntryPoint ep = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class);
        mRepo = ep.getDownloadDataRepository();
        assertNotNull(mRepo);
        StoragePaths.ensureDownloadPath(mApp);
        StoragePaths.ensureSafePath(mApp);
        mSafeRoot = StoragePaths.getSafePath(mApp);
        mDownloadRoot = StoragePaths.getDownloadPath(mApp);
        // The worker makes no move while the engine runs (a vault download in
        // flight has claimed its path); a running download on the device
        // would turn every move below into a no-op.
        assertFalse("close the app's downloads first: the engine is running",
                RunnableManager.isRunning());
    }

    @After
    public void tearDown() throws Exception {
        List<DownloadEntity> rows = new ArrayList<>();
        for (DownloadEntity seeded : mSeeded) {
            DownloadEntity current = mRepo.findByIdSync(seeded.getId());
            rows.add(current != null ? current : seeded);
        }
        if (!rows.isEmpty()) {
            CountDownLatch done = new CountDownLatch(1);
            mRepo.deleteDownloads(rows, removed -> done.countDown());
            done.await(30, TimeUnit.SECONDS);
        }
        for (File f : mFiles) {
            f.delete();
        }
        for (DownloadEntity row : rows) {
            if (row.getFilePath() != null) {
                new File(row.getFilePath()).delete();
            }
        }
    }

    @Test
    public void aVaultRowOutsideTheSafeFolderIsMovedIn() throws Exception {
        String stem = "mlw-vault-" + System.nanoTime();
        byte[] bytes = bytes(4096, 1);
        File source = write(new File(mDownloadRoot, stem + ".bin"), bytes);
        DownloadEntity row = seed(stem, source, true, Download.FINISHED);

        runWorker();

        DownloadEntity moved = awaitRow(row.getId(),
                e -> e.getFilePath() != null && e.getFilePath().startsWith(mSafeRoot + File.separator));
        assertEquals(stem + ".bin", moved.getFileName());
        assertEquals(new File(mSafeRoot, stem + ".bin").getAbsolutePath(), moved.getFilePath());
        assertEquals("the picture path follows the file", moved.getFilePath(), moved.getFileImg());
        assertTrue(moved.isFileSafe());
        assertEquals(Download.FINISHED, moved.getFileStatus());
        assertFalse("the public copy is gone", source.exists());
        File target = new File(moved.getFilePath());
        mFiles.add(target);
        assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void aTakenNameInTheSafeFolderGetsACounter() throws Exception {
        String stem = "mlw-taken-" + System.nanoTime();
        byte[] bytes = bytes(2048, 2);
        File source = write(new File(mDownloadRoot, stem + ".bin"), bytes);
        File occupant = write(new File(mSafeRoot, stem + ".bin"), new byte[]{7});
        DownloadEntity row = seed(stem, source, true, Download.FINISHED);

        runWorker();

        DownloadEntity moved = awaitRow(row.getId(),
                e -> e.getFilePath() != null && e.getFilePath().startsWith(mSafeRoot + File.separator));
        assertEquals(stem + "(1).bin", moved.getFileName());
        File target = new File(moved.getFilePath());
        mFiles.add(target);
        assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
        assertEquals("the file that held the name is untouched", 1, occupant.length());
        assertFalse(source.exists());
    }

    @Test
    public void aRegularRowAndAnAlreadySafeRowStayWhereTheyAre() throws Exception {
        String regularStem = "mlw-regular-" + System.nanoTime();
        File regularFile = write(new File(mDownloadRoot, regularStem + ".bin"), bytes(1024, 3));
        DownloadEntity regular = seed(regularStem, regularFile, false, Download.FINISHED);

        String safeStem = "mlw-safe-" + System.nanoTime();
        File safeFile = write(new File(mSafeRoot, safeStem + ".bin"), bytes(1024, 4));
        DownloadEntity safe = seed(safeStem, safeFile, true, Download.FINISHED);

        runWorker();
        // addSync lands on the disk executor; give a wrong write time to show.
        Thread.sleep(1500);

        DownloadEntity regularNow = mRepo.findByIdSync(regular.getId());
        assertEquals(regularFile.getAbsolutePath(), regularNow.getFilePath());
        assertEquals(Download.FINISHED, regularNow.getFileStatus());
        assertFalse(regularNow.isFileSafe());
        assertTrue(regularFile.exists());

        DownloadEntity safeNow = mRepo.findByIdSync(safe.getId());
        assertEquals(safeFile.getAbsolutePath(), safeNow.getFilePath());
        assertEquals(Download.FINISHED, safeNow.getFileStatus());
        assertTrue(safeFile.exists());
    }

    @Test
    public void aMissingPrivateFileIsMarkedAndHealsBack() throws Exception {
        String stem = "mlw-missing-" + System.nanoTime();
        File missing = new File(mApp.getFilesDir(), stem + ".bin");
        mFiles.add(missing);
        assertFalse(missing.exists());
        DownloadEntity row = seed(stem, missing, false, Download.FINISHED);

        runWorker();
        DownloadEntity marked = awaitRow(row.getId(), e -> e.getFileStatus() == Download.ERROR);
        assertEquals(MessageHelper.FILE_NOT_FOUND, marked.getFileErrorType());

        write(missing, bytes(512, 5));
        runWorker();
        DownloadEntity healed = awaitRow(row.getId(), e -> e.getFileStatus() == Download.FINISHED);
        assertEquals(MessageHelper.NO_ERROR, healed.getFileErrorType());
    }

    // ---------------------------------------------------------------------

    private void runWorker() {
        MediaListenerWorker worker = TestListenableWorkerBuilder.from(mApp, MediaListenerWorker.class)
                .setWorkerFactory(new WorkerFactory() {
                    @Nullable
                    @Override
                    public ListenableWorker createWorker(@NonNull Context appContext,
                                                         @NonNull String workerClassName,
                                                         @NonNull WorkerParameters workerParameters) {
                        return new MediaListenerWorker(appContext, workerParameters, mRepo);
                    }
                })
                .build();
        ListenableWorker.Result result = worker.doWork();
        assertEquals(ListenableWorker.Result.success(), result);
    }

    private DownloadEntity seed(String stem, File file, boolean safe, int status) throws InterruptedException {
        DownloadEntity entity = new DownloadEntity();
        entity.setId(("media-listener-worker:" + stem).hashCode());
        entity.setFileType(UrlType.FILE.getValue());
        entity.setFileUrl(URL_PREFIX + stem);
        entity.setFileOriginUrl(URL_PREFIX + stem);
        entity.setFileName(file.getName());
        entity.setFilePath(file.getAbsolutePath());
        entity.setFileImg(file.getAbsolutePath());
        entity.setFileMimeType("application/octet-stream");
        entity.setFileStatus(status);
        entity.setFileErrorType(MessageHelper.NO_ERROR);
        entity.setFileSafe(safe);
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileSize(file.exists() ? file.length() : 0);
        mSeeded.add(entity);
        mRepo.addSync(entity);
        assertTrue("seed row did not land", waitUntil(() -> mRepo.findByIdSync(entity.getId()) != null, 10_000));
        return entity;
    }

    private File write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs());
        }
        Files.write(file.toPath(), bytes);
        mFiles.add(file);
        return file;
    }

    private static byte[] bytes(int n, int seed) {
        byte[] out = new byte[n];
        new Random(seed).nextBytes(out);
        return out;
    }

    private interface RowCondition {
        boolean holds(DownloadEntity row);
    }

    private DownloadEntity awaitRow(int id, RowCondition condition) throws InterruptedException {
        assertTrue("row " + id + " never reached the expected state", waitUntil(() -> {
            DownloadEntity e = mRepo.findByIdSync(id);
            return e != null && condition.holds(e);
        }, 15_000));
        return mRepo.findByIdSync(id);
    }

    private static boolean waitUntil(BooleanSupplier condition, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }
}
