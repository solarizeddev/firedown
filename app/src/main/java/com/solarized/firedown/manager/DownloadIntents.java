package com.solarized.firedown.manager;

import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * The one door from the UI to {@link RunnableManager} for starting downloads,
 * and the engine's reader for what it sent.
 *
 * <p>A started service's Intent crosses Binder, whose transaction buffer is
 * 1 MB for the whole process, so an extra is sized for an address and a few
 * headers — and a {@link DownloadRequest} is not always that: a {@code data:}
 * URL IS its file (an inline image of a few megabytes), and a header set or
 * a cookie jar can be large. Shipped as
 * {@code TransactionTooLargeException: data parcel size 4570572 bytes} out of
 * {@code startService}. So the request rides the Intent only while its
 * parcel is under {@link #MAX_INLINE_BYTES}; a bigger one is PARKED here, in
 * the process both sides share (the service declares no
 * {@code android:process}), and the Intent carries its token. An ordinary
 * request keeps the exact Intent shape it always had.</p>
 *
 * <p>The park is bounded in COUNT, the only bound memory needs: a batch
 * nobody collects (the service never started) is evicted by later parks,
 * never by a timer. A token that outlives the process — the system
 * redelivering a pending start after a death — finds nothing, and that start
 * is dropped with a log; with the request on the Intent it would have
 * crashed instead.</p>
 */
public final class DownloadIntents {

    private static final String TAG = DownloadIntents.class.getSimpleName();

    /**
     * Largest request parcel the Intent carries itself. A plain request is a
     * few KB; Binder's buffer is 1 MB, shared by every transaction in flight.
     */
    static final int MAX_INLINE_BYTES = 128 * 1024;

    /** Parked batches kept for a service that has not read them yet. */
    static final int MAX_PARKED = 16;

    private static final LinkedHashMap<String, ArrayList<DownloadRequest>> sParked =
            new LinkedHashMap<>();

    private DownloadIntents() {
    }

    public static void start(@NonNull Context context, @NonNull DownloadRequest request) {
        start(context, Collections.singletonList(request));
    }

    public static void start(@NonNull Context context, @NonNull List<DownloadRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        ArrayList<DownloadRequest> list = new ArrayList<>(requests);
        Intent intent = new Intent(context, RunnableManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START);
        String token = null;
        int bytes = parcelSize(list);
        if (bytes <= MAX_INLINE_BYTES) {
            if (list.size() == 1) {
                intent.putExtra(Keys.DOWNLOAD_REQUEST, list.get(0));
            } else {
                intent.putParcelableArrayListExtra(Keys.DOWNLOAD_REQUEST_LIST, list);
            }
        } else {
            token = park(list);
            intent.putExtra(Keys.DOWNLOAD_REQUEST_TOKEN, token);
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "start: " + list.size() + " request(s) parcel to " + bytes
                        + " bytes, handed over in-process");
            }
        }
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            // A start the system refused (the app in the background) never
            // reads its token; a batch parked for it would wait for eviction.
            if (token != null) {
                take(token);
            }
            throw e;
        }
    }

    /** True when the Intent names requests at all, by whichever carrier. */
    public static boolean carriesRequests(@NonNull Intent intent) {
        return intent.hasExtra(Keys.DOWNLOAD_REQUEST_TOKEN)
                || intent.hasExtra(Keys.DOWNLOAD_REQUEST)
                || intent.hasExtra(Keys.DOWNLOAD_REQUEST_LIST);
    }

    /**
     * Every request the Intent names, in order. Empty for an Intent carrying
     * none, and for a token this process no longer holds.
     */
    @NonNull
    public static List<DownloadRequest> read(@NonNull Intent intent) {
        String token = intent.getStringExtra(Keys.DOWNLOAD_REQUEST_TOKEN);
        if (token != null) {
            ArrayList<DownloadRequest> parked = take(token);
            if (parked == null) {
                Log.w(TAG, "read: nothing parked for the token (a start redelivered"
                        + " after the process died?)");
                return Collections.emptyList();
            }
            return parked;
        }
        if (intent.hasExtra(Keys.DOWNLOAD_REQUEST)) {
            DownloadRequest request = intent.getParcelableExtra(Keys.DOWNLOAD_REQUEST);
            if (request == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(request);
        }
        if (intent.hasExtra(Keys.DOWNLOAD_REQUEST_LIST)) {
            ArrayList<DownloadRequest> list =
                    intent.getParcelableArrayListExtra(Keys.DOWNLOAD_REQUEST_LIST);
            if (list == null) {
                return Collections.emptyList();
            }
            return list;
        }
        return Collections.emptyList();
    }

    /** What the Intent would carry: the requests parcelled the way an extra parcels them. */
    static int parcelSize(@NonNull List<DownloadRequest> requests) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeTypedList(requests);
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    private static String park(@NonNull ArrayList<DownloadRequest> requests) {
        String token = UUID.randomUUID().toString();
        synchronized (sParked) {
            Iterator<String> oldest = sParked.keySet().iterator();
            while (sParked.size() >= MAX_PARKED && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
            sParked.put(token, requests);
        }
        return token;
    }

    @Nullable
    private static ArrayList<DownloadRequest> take(@NonNull String token) {
        synchronized (sParked) {
            return sParked.remove(token);
        }
    }
}
