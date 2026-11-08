package com.solarized.firedown.addons;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Add-on updates that wait for the user because they ask for new
 * permissions. Pure Java (the add-ons harness drives it), owned and saved
 * by {@link AddonManager}.
 *
 * <p>Per add-on: the VERSION that was offered, whether the user has SEEN
 * the question (answered it either way), and whether the notification was
 * actually POSTED. "Waiting" is what the Settings rows say; "needs
 * attention" (waiting and not seen) is what the dots and the notification
 * show, so a user who said no is not nagged; a different version arms them
 * again. A notification that could not be posted (no permission, the
 * channel off) stays due, so it comes once notifications are allowed.
 */
public final class AddonUpdateReviews {

    /** How often a background check looks again at an add-on whose update waits. */
    static final long RECHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(7);

    private static final class Entry {
        @NonNull String version;
        boolean shown;
        boolean notified;

        Entry(@NonNull String version, boolean shown, boolean notified) {
            this.version = version;
            this.shown = shown;
            this.notified = notified;
        }
    }

    private final Map<String, Entry> mEntries = new LinkedHashMap<>();

    /** The saved form: one add-on per line, {@code id TAB version TAB shown TAB notified}. */
    @NonNull
    public static AddonUpdateReviews decode(@Nullable String text) {
        AddonUpdateReviews reviews = new AddonUpdateReviews();
        if (text == null || text.isEmpty()) {
            return reviews;
        }
        for (String line : text.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4 || fields[0].isEmpty()) {
                continue;
            }
            reviews.mEntries.put(fields[0],
                    new Entry(fields[1], "1".equals(fields[2]), "1".equals(fields[3])));
        }
        return reviews;
    }

    /**
     * The marks of a build that stored only ids: no version, not seen, and
     * counted as notified (that build posted the notification, or could not
     * and would not have tried again).
     */
    @NonNull
    public static AddonUpdateReviews fromLegacy(@Nullable Collection<String> ids) {
        AddonUpdateReviews reviews = new AddonUpdateReviews();
        if (ids != null) {
            for (String id : ids) {
                if (usable(id)) {
                    reviews.mEntries.put(id, new Entry("", false, true));
                }
            }
        }
        return reviews;
    }

    @NonNull
    public String encode() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            Entry entry = e.getValue();
            out.append(e.getKey()).append('\t').append(entry.version).append('\t')
                    .append(entry.shown ? '1' : '0').append('\t').append(entry.notified ? '1' : '0');
        }
        return out.toString();
    }

    /**
     * A background check refused an update of {@code id} to {@code version}
     * because it asks for more. A new mark, or another version than the one
     * marked, needs the user's attention again. Returns whether anything
     * changed.
     */
    public boolean offer(@NonNull String id, @Nullable String version) {
        if (!usable(id)) {
            return false;
        }
        String v = clean(version);
        Entry entry = mEntries.get(id);
        if (entry == null) {
            mEntries.put(id, new Entry(v, false, false));
            return true;
        }
        if (v.isEmpty() || v.equals(entry.version)) {
            return false;
        }
        if (entry.version.isEmpty()) {
            // A mark from before versions were kept: the same update, most
            // likely; learn its version without asking again.
            entry.version = v;
            return true;
        }
        entry.version = v;
        entry.shown = false;
        entry.notified = false;
        return true;
    }

    /**
     * The user saw the question for {@code id} and did not update (Cancel,
     * Back). The update still waits; nothing points at it any more.
     */
    public boolean answered(@NonNull String id, @Nullable String version) {
        if (!usable(id)) {
            return false;
        }
        String v = clean(version);
        Entry entry = mEntries.get(id);
        if (entry == null) {
            mEntries.put(id, new Entry(v, true, true));
            return true;
        }
        boolean changed = !entry.shown || !entry.notified || (!v.isEmpty() && !v.equals(entry.version));
        if (!v.isEmpty()) {
            entry.version = v;
        }
        entry.shown = true;
        entry.notified = true;
        return changed;
    }

    /** The notification now names these add-ons. */
    public boolean markNotified(@NonNull Collection<String> ids) {
        boolean changed = false;
        for (String id : ids) {
            Entry entry = mEntries.get(id);
            if (entry != null && !entry.notified) {
                entry.notified = true;
                changed = true;
            }
        }
        return changed;
    }

    /** Updated, removed, or no update offered any more. */
    public boolean remove(@NonNull String id) {
        return mEntries.remove(id) != null;
    }

    /** Drops the marks of add-ons that are not installed (a restored or edited profile). */
    public boolean retainOnly(@NonNull Collection<String> installed) {
        boolean changed = false;
        Iterator<String> it = mEntries.keySet().iterator();
        while (it.hasNext()) {
            if (!installed.contains(it.next())) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public boolean isWaiting(@NonNull String id) {
        return mEntries.containsKey(id);
    }

    public boolean needsAttention(@NonNull String id) {
        Entry entry = mEntries.get(id);
        return entry != null && !entry.shown;
    }

    /** Add-ons whose update waits and whose question the user has not seen, in the order marked. */
    @NonNull
    public List<String> attention() {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Entry> e : mEntries.entrySet()) {
            if (!e.getValue().shown) {
                ids.add(e.getKey());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /** An add-on needs attention and no notification has named it yet. */
    public boolean notificationDue() {
        for (Entry entry : mEntries.values()) {
            if (!entry.shown && !entry.notified) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return mEntries.isEmpty();
    }

    /**
     * Whether a background check should look again at add-ons whose update
     * waits: a refused update is downloaded before it is refused, so not
     * every day. A last recheck in the future is a clock that moved back.
     */
    public static boolean recheckDue(long lastRecheck, long now) {
        return now < lastRecheck || now - lastRecheck >= RECHECK_INTERVAL_MS;
    }

    @NonNull
    private static String clean(@Nullable String version) {
        if (version == null) {
            return "";
        }
        String v = version.trim();
        return v.indexOf('\t') >= 0 || v.indexOf('\n') >= 0 ? "" : v;
    }

    private static boolean usable(@Nullable String id) {
        return id != null && !id.isEmpty() && id.indexOf('\t') < 0 && id.indexOf('\n') < 0;
    }
}
