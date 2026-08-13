package com.solarized.firedown.utils;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Strips extras from an INBOUND intent that Android's saved-state machinery
 * cannot serialize, before anything can copy them into a state Bundle.
 *
 * <h3>The crash this exists for</h3>
 * <pre>
 * java.lang.IllegalArgumentException: Illegal value type android.util.SparseArray
 *     for key "androidx.browser.customtabs.extra.COLOR_SCHEME_PARAMS"
 *   androidx.core.os.BundleKt.bundleOf:105
 *   androidx.lifecycle.internal.SavedStateHandleImpl.savedStateProvider$lambda$0:165
 *   androidx.lifecycle.SavedStateHandlesProvider.saveState:173
 *   androidx.savedstate.internal.SavedStateRegistryImpl.performSave:139
 *   androidx.activity.ComponentActivity.onSaveInstanceState:374
 * </pre>
 * (retraced from a release build; the raw trace is obfuscated).
 *
 * <h3>The path, end to end</h3>
 * Firedown registers as a browser, so any app opening a link through
 * {@code CustomTabsIntent} launches us with that library's styling extras
 * attached — and {@code EXTRA_COLOR_SCHEME_PARAMS} is a
 * {@code SparseArray<Bundle>}. {@code ComponentActivity
 * .getDefaultViewModelCreationExtras()} then puts {@code intent.extras} in
 * as {@code DEFAULT_ARGS_KEY}, which makes the whole inbound extras bundle
 * the initial contents of every {@code SavedStateHandle} created from this
 * Activity. On save, {@code SavedStateHandleImpl}'s provider rebuilds those
 * contents with {@code androidx.core.os.bundleOf}, whose type switch covers
 * Parcelable, arrays, CharSequence and so on — but has no case for
 * {@link SparseArray}, so it throws rather than storing.
 *
 * <p>The crash therefore lands in {@code onSaveInstanceState}, i.e. on
 * BACKGROUNDING: the user follows a link from another app, presses home,
 * and Firedown dies. Nothing of ours is on the stack, and we never read the
 * offending extra — we are simply the browser that was handed it.</p>
 *
 * <h3>Why strip, rather than defend at the SavedStateHandle</h3>
 * The intent is the single choke point: every ViewModel built from this
 * Activity draws its default args from it, so removing the value once here
 * covers all of them, whereas guarding downstream would mean intercepting a
 * framework class we do not construct. It also keeps working if a future
 * androidx version re-bundles those extras somewhere else. And the value is
 * genuinely worthless to us: Custom Tabs styling — toolbar colours,
 * share-menu flags, session bindings — is inert to a GeckoView browser that
 * renders its own chrome. All we want from these intents is the URI (and
 * for SEND, the text).
 *
 * <h3>Scope</h3>
 * Only {@link SparseArray} is removed. It is the one type that reaches an
 * Activity through an ordinary launch and that {@code bundleOf} rejects —
 * everything the Custom Tabs and Share paths otherwise carry (String, int,
 * Bundle, Parcelable, PendingIntent) is handled. Deliberately NOT a
 * broad allow-list of extras we recognise: an inbound intent legitimately
 * carries things we do not read but the system does (referrer, EXTRA_STREAM
 * grants), and dropping those to fix a crash would trade one bug for a
 * subtler one. Firedown itself never puts a SparseArray in an intent, so
 * nothing of ours is at risk.
 */
public final class IntentSanitizer {

    private static final String TAG = "IntentSanitizer";

    private IntentSanitizer() {}

    /**
     * Removes un-serializable extras from {@code intent}, in place.
     *
     * <p>Safe to call on any intent, including one with no extras, and safe
     * to call repeatedly — the second pass finds nothing.</p>
     *
     * @return {@code true} if anything was removed (debug logging only).
     */
    public static boolean stripUnsavableExtras(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        final Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (RuntimeException e) {
            // Unparcelling a hostile or version-skewed extra can throw
            // (BadParcelableException for a class we don't have). An intent
            // we can't read is one we can't sanitize; leave it be rather
            // than crash in the fixer.
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "could not read extras", e);
            }
            return false;
        }
        if (extras == null || extras.isEmpty()) {
            return false;
        }

        // Collect first, remove second: removing while iterating keySet()
        // would ConcurrentModificationException.
        List<String> doomed = null;
        for (String key : extras.keySet()) {
            final Object value;
            try {
                value = extras.get(key);
            } catch (RuntimeException e) {
                continue;   // same reasoning as above, per key
            }
            if (value instanceof SparseArray) {
                if (doomed == null) {
                    doomed = new ArrayList<>(2);
                }
                doomed.add(key);
            }
        }
        if (doomed == null) {
            return false;
        }
        for (String key : doomed) {
            intent.removeExtra(key);
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "dropped un-serializable extra: " + key);
            }
        }
        return true;
    }
}
