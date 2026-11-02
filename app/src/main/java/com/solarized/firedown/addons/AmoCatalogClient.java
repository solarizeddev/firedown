package com.solarized.firedown.addons;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.di.Qualifiers;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the official list ({@link AmoCatalog}) when the Add-ons screen
 * opens, never in the background. A copy is kept for two days (Firefox's
 * cache age) and shown at once; when the list cannot be fetched the last
 * copy is shown, however old.
 */
@Singleton
public class AmoCatalogClient {

    private static final String TAG = "AmoCatalog";
    private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(2);
    /** A page of 50 add-ons in one language is ~100 KB; anything near this is not one. */
    private static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAGES = 4;
    private static final String DIR = "addons";

    /** What the screen shows: the list, and whether it is still loading or failed. */
    public static final class State {
        @NonNull public final List<AmoCatalog.Entry> entries;
        public final boolean loading;
        public final boolean failed;

        State(@NonNull List<AmoCatalog.Entry> entries, boolean loading, boolean failed) {
            this.entries = entries;
            this.loading = loading;
            this.failed = failed;
        }
    }

    private final Context mContext;
    private final OkHttpClient mClient;
    private final Executor mNetworkExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> mState =
            new MutableLiveData<>(new State(Collections.emptyList(), false, false));
    private boolean mLoading;

    @Inject
    public AmoCatalogClient(@ApplicationContext Context context,
                            OkHttpClient client,
                            @Qualifiers.Network Executor networkExecutor) {
        mContext = context;
        mClient = client;
        mNetworkExecutor = networkExecutor;
    }

    @NonNull
    public LiveData<State> getState() {
        return mState;
    }

    /** Loads the list; {@code force} skips a fresh copy (pull to refresh). Main thread. */
    public void load(boolean force) {
        if (mLoading) {
            return;
        }
        mLoading = true;
        State current = mState.getValue();
        List<AmoCatalog.Entry> shown = current == null ? Collections.emptyList() : current.entries;
        mState.setValue(new State(shown, true, false));
        final String language = Locale.getDefault().getLanguage();
        mNetworkExecutor.execute(() -> {
            List<AmoCatalog.Entry> entries = null;
            boolean failed = false;
            File cache = cacheFile(language);
            if (!force && cache.isFile()
                    && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS) {
                entries = readCache(cache, language);
            }
            if (entries == null) {
                try {
                    entries = fetch(language, cache);
                } catch (IOException | JSONException e) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "fetch failed", e);
                    }
                    entries = cache.isFile() ? readCache(cache, language) : null;
                    failed = entries == null;
                }
            }
            List<AmoCatalog.Entry> result = entries == null ? Collections.emptyList() : entries;
            boolean failedFinal = failed;
            postState(new State(result, false, failedFinal));
        });
    }

    private void postState(@NonNull State state) {
        mMainHandler.post(() -> {
            mLoading = false;
            mState.setValue(state);
        });
    }

    @NonNull
    private List<AmoCatalog.Entry> fetch(@NonNull String language, @NonNull File cache)
            throws IOException, JSONException {
        List<AmoCatalog.Page> pages = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        String url = AmoCatalog.collectionUrl(language);
        int count = 0;
        while (url != null && count < MAX_PAGES) {
            String body = get(url);
            AmoCatalog.Page page = AmoCatalog.parse(body, language);
            pages.add(page);
            bodies.add(body);
            count++;
            url = AmoCatalog.isTrustedPageUrl(page.next) ? page.next : null;
        }
        writeCache(cache, bodies);
        return AmoCatalog.merge(pages);
    }

    @NonNull
    private String get(@NonNull String url) throws IOException {
        Request request = new Request.Builder().url(url).header("Accept", "application/json").build();
        try (Response response = mClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            try (InputStream in = body.byteStream()) {
                return readCapped(in);
            }
        }
    }

    @NonNull
    private static String readCapped(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        while (true) {
            int read = in.read(buf);
            if (read < 0) {
                break;
            }
            total += read;
            if (total > MAX_PAGE_BYTES) {
                throw new IOException("catalog page too large");
            }
            out.write(buf, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    @NonNull
    private File cacheFile(@NonNull String language) {
        String safe = language.replaceAll("[^a-zA-Z_-]", "");
        if (safe.isEmpty()) {
            safe = "default";
        }
        return new File(new File(mContext.getFilesDir(), DIR), "catalog-" + safe + ".txt");
    }

    /** The pages' bodies, one per line-delimited record: length, newline, body. */
    private static void writeCache(@NonNull File cache, @NonNull List<String> bodies) {
        File dir = cache.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(cache.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            for (String body : bodies) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                out.write((bytes.length + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
            }
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cache)) {
            tmp.delete();
        }
    }

    @Nullable
    private static List<AmoCatalog.Entry> readCache(@NonNull File cache, @NonNull String language) {
        try {
            byte[] all = Files.readAllBytes(cache.toPath());
            List<AmoCatalog.Page> pages = new ArrayList<>();
            int pos = 0;
            while (pos < all.length) {
                int newline = pos;
                while (newline < all.length && all[newline] != '\n') {
                    newline++;
                }
                if (newline >= all.length) {
                    return null;
                }
                int length = Integer.parseInt(new String(all, pos, newline - pos, StandardCharsets.US_ASCII));
                int start = newline + 1;
                if (length < 0 || start + length > all.length) {
                    return null;
                }
                String body = new String(all, start, length, StandardCharsets.UTF_8);
                pages.add(AmoCatalog.parse(body, language));
                pos = start + length;
            }
            return pages.isEmpty() ? null : AmoCatalog.merge(pages);
        } catch (IOException | JSONException | NumberFormatException e) {
            return null;
        }
    }

    /** Drops the cached list (the user cleared browsing data). */
    public void clearCache() {
        mNetworkExecutor.execute(() -> {
            File dir = new File(mContext.getFilesDir(), DIR);
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.getName().startsWith("catalog-")) {
                    f.delete();
                }
            }
        });
    }
}
