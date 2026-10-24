package com.solarized.firedown.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * {@link DownloadIntents}: what the start Intent carries, and what the engine
 * reads back. A real {@link Parcel} sizes the request, so this needs a device,
 * but no service runs: {@code startService} is intercepted and the Intent
 * examined in place.
 *
 * <p>Run from Android Studio (right-click the class → Run/Debug on the debug
 * variant with a device attached), or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.manager.DownloadIntentsTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class DownloadIntentsTest {

    private CapturingContext mContext;

    @Before
    public void setUp() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        mContext = new CapturingContext(app);
    }

    /** Keeps the Intent a start would have sent, and refuses the start when told to. */
    private static final class CapturingContext extends ContextWrapper {
        Intent started;
        RuntimeException refuse;

        CapturingContext(Context base) {
            super(base);
        }

        @Override
        public ComponentName startService(Intent service) {
            started = service;
            if (refuse != null) {
                throw refuse;
            }
            return service.getComponent();
        }
    }

    private static DownloadRequest request(String url, String name) {
        return new DownloadRequest.Builder(url)
                .name(name)
                .mimeType("application/octet-stream")
                .fileType(UrlType.FILE.getValue())
                .build();
    }

    /** A data: URL whose base64 payload alone is well past the inline bound. */
    private static String oversizedDataUrl() {
        byte[] bytes = new byte[2 * DownloadIntents.MAX_INLINE_BYTES];
        new Random(7).nextBytes(bytes);
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static int parcelBytes(Intent intent) {
        Parcel parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void aPlainRequestRidesTheIntent() {
        String url = "https://example.test/clip.mp4";
        DownloadIntents.start(mContext, request(url, "clip.mp4"));

        Intent sent = mContext.started;
        assertNotNull(sent);
        assertEquals(IntentActions.DOWNLOAD_START, sent.getAction());
        assertTrue(sent.hasExtra(Keys.DOWNLOAD_REQUEST));
        assertFalse(sent.hasExtra(Keys.DOWNLOAD_REQUEST_LIST));
        assertFalse(sent.hasExtra(Keys.DOWNLOAD_REQUEST_TOKEN));
        assertTrue(DownloadIntents.carriesRequests(sent));

        List<DownloadRequest> read = DownloadIntents.read(sent);
        assertEquals(1, read.size());
        assertEquals(url, read.get(0).getUrl());
        // An inline request is read as often as the Intent is delivered.
        assertEquals(1, DownloadIntents.read(sent).size());
    }

    @Test
    public void severalRequestsRideAsAListInOrder() {
        DownloadIntents.start(mContext, Arrays.asList(
                request("https://example.test/one.mp4", "one.mp4"),
                request("https://example.test/two.mp4", "two.mp4")));

        Intent sent = mContext.started;
        assertTrue(sent.hasExtra(Keys.DOWNLOAD_REQUEST_LIST));
        assertFalse(sent.hasExtra(Keys.DOWNLOAD_REQUEST));
        assertFalse(sent.hasExtra(Keys.DOWNLOAD_REQUEST_TOKEN));

        List<DownloadRequest> read = DownloadIntents.read(sent);
        assertEquals(2, read.size());
        assertEquals("https://example.test/one.mp4", read.get(0).getUrl());
        assertEquals("https://example.test/two.mp4", read.get(1).getUrl());
    }

    @Test
    public void anOversizedRequestIsParkedAndReadOnce() {
        String url = oversizedDataUrl();
        DownloadRequest request = request(url, "inline.bin");
        assertTrue(DownloadIntents.parcelSize(Arrays.asList(request)) > DownloadIntents.MAX_INLINE_BYTES);

        DownloadIntents.start(mContext, request);

        Intent sent = mContext.started;
        assertTrue(sent.hasExtra(Keys.DOWNLOAD_REQUEST_TOKEN));
        assertFalse(sent.hasExtra(Keys.DOWNLOAD_REQUEST));
        assertFalse(sent.hasExtra(Keys.DOWNLOAD_REQUEST_LIST));
        assertTrue(DownloadIntents.carriesRequests(sent));
        // The Intent itself stays small: a token, not the file.
        assertTrue("intent parcels to " + parcelBytes(sent) + " bytes",
                parcelBytes(sent) < DownloadIntents.MAX_INLINE_BYTES);

        List<DownloadRequest> read = DownloadIntents.read(sent);
        assertEquals(1, read.size());
        assertEquals(url, read.get(0).getUrl());
        // The token is single-use: a redelivery finds nothing, and is dropped.
        assertTrue(DownloadIntents.read(sent).isEmpty());
    }

    @Test
    public void aRefusedStartUnparksItsBatch() {
        mContext.refuse = new IllegalStateException("not allowed from the background");
        try {
            DownloadIntents.start(mContext, request(oversizedDataUrl(), "inline.bin"));
            fail("the refusal should propagate");
        } catch (IllegalStateException expected) {
            // the caller sees the system's answer
        }
        Intent sent = mContext.started;
        assertNotNull(sent);
        assertTrue(sent.hasExtra(Keys.DOWNLOAD_REQUEST_TOKEN));
        assertTrue("a refused start's batch must not wait for eviction",
                DownloadIntents.read(sent).isEmpty());
    }

    @Test
    public void anIntentWithNoRequestsReadsEmpty() {
        Intent bare = new Intent(IntentActions.DOWNLOAD_START);
        assertFalse(DownloadIntents.carriesRequests(bare));
        assertTrue(DownloadIntents.read(bare).isEmpty());
    }

    @Test
    public void parcelSizeFollowsThePayload() {
        List<DownloadRequest> small = Arrays.asList(
                request("https://example.test/a.mp4", "a.mp4"),
                request("https://example.test/b.mp4", "b.mp4"));
        assertTrue(DownloadIntents.parcelSize(small) <= DownloadIntents.MAX_INLINE_BYTES);
        assertTrue(DownloadIntents.parcelSize(Arrays.asList(request(oversizedDataUrl(), "c.bin")))
                > DownloadIntents.MAX_INLINE_BYTES);
    }
}
