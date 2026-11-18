package com.solarized.firedown.addons;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * One "Check for updates" of every add-on, summed into ONE message: a
 * message per add-on replaced the one before it, so only the last could be
 * read. Pure Java (the add-ons harness runs it); AddonManager feeds it the
 * answers and maps a {@link Result} to a snackbar.
 *
 * <p>The rules, first match wins:
 * <ol>
 *   <li>one or more failed: "Couldn't update X" / "Couldn't update some add-ons";
 *   <li>one or more updated: "X updated" / "Add-ons updated" (a failure is
 *       said first: an updated add-on shows its new version on its row);
 *   <li>the check timed out with answers missing: "Couldn't check";
 *   <li>an update the user declined: nothing (its row says it waits);
 *   <li>otherwise "No add-on updates found" — never "up to date": Gecko
 *       answers a FAILED check (server down, a network that does not reach
 *       the internet) as "no update", so that is all the answers prove.
 * </ol>
 * The caller says "No connection" before asking at all when there is no
 * network. An answer that arrives after a timeout ended the check is said
 * on its own when it is an update or a failure; anything else is dropped.
 * Main thread only.
 */
public final class AddonCheckTally {

    public static final int UPDATED_ONE = 1;
    public static final int UPDATED_SEVERAL = 2;
    public static final int FAILED_ONE = 3;
    public static final int FAILED_SEVERAL = 4;
    public static final int COULD_NOT_CHECK = 5;
    public static final int NONE_FOUND = 6;

    /** What to say: {@code name} is set for the _ONE kinds. */
    public static final class Result {
        public final int kind;
        @Nullable public final String name;

        Result(int kind, @Nullable String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    private final Set<String> mPending = new HashSet<>();
    /** Ids whose update question is on screen: the timeout waits for the user. */
    private final Set<String> mAsking = new HashSet<>();
    /** Ids still unanswered when the timeout ended the check. */
    private final Set<String> mTimedOut = new HashSet<>();
    private int mUpdated;
    private int mFailed;
    private int mDeclined;
    @Nullable private String mUpdatedName;
    @Nullable private String mFailedName;
    private boolean mFinished;

    /** {@code ids}: every add-on asked; none asked is a check already over. */
    public AddonCheckTally(@NonNull Iterable<String> ids) {
        for (String id : ids) {
            mPending.add(id);
        }
    }

    public boolean isFinished() {
        return mFinished;
    }

    /** True while the user has an update question in front of them. */
    public boolean isAskingUser() {
        return !mAsking.isEmpty();
    }

    /**
     * The user answered {@code id}'s question: the timeout runs again (Gecko
     * may never finish that update, and the row must not spin for good).
     */
    public void userAnswered(@NonNull String id) {
        mAsking.remove(id);
    }

    /** True when the timeout ended the check before {@code id} answered. */
    public boolean timedOutWaitingFor(@NonNull String id) {
        return mTimedOut.contains(id);
    }

    /** Ids still unanswered when the timeout ended the check (empty otherwise). */
    @NonNull
    public Set<String> timedOut() {
        return new HashSet<>(mTimedOut);
    }

    /** The update for {@code id} asks for new permissions; its sheet is up. */
    public void askingUser(@NonNull String id) {
        if (!mFinished && mPending.contains(id)) {
            mAsking.add(id);
        }
    }

    @Nullable
    public Result updated(@NonNull String id, @NonNull String name) {
        if (mFinished) {
            return new Result(UPDATED_ONE, name);
        }
        if (!mPending.contains(id)) {
            return null;
        }
        mUpdated++;
        mUpdatedName = name;
        return answered(id);
    }

    /** No update, or the add-on was removed while the check ran. */
    @Nullable
    public Result noUpdate(@NonNull String id) {
        if (mFinished || !mPending.contains(id)) {
            return null;
        }
        return answered(id);
    }

    @Nullable
    public Result failed(@NonNull String id, @NonNull String name) {
        if (mFinished) {
            return new Result(FAILED_ONE, name);
        }
        if (!mPending.contains(id)) {
            return null;
        }
        mFailed++;
        mFailedName = name;
        return answered(id);
    }

    /** The user said no to the update's question (its row says it waits). */
    @Nullable
    public Result declined(@NonNull String id) {
        if (mFinished || !mPending.contains(id)) {
            return null;
        }
        mDeclined++;
        return answered(id);
    }

    /**
     * Ends the check: on the last answer, or on the caller's timeout (which
     * should not call it while {@link #isAskingUser}). Null: nothing to say,
     * or already ended.
     */
    @Nullable
    public Result finish(boolean timedOut) {
        if (mFinished) {
            return null;
        }
        mFinished = true;
        mAsking.clear();
        if (timedOut) {
            mTimedOut.addAll(mPending);
        }
        if (mFailed == 1) {
            return new Result(FAILED_ONE, mFailedName);
        }
        if (mFailed > 1) {
            return new Result(FAILED_SEVERAL, null);
        }
        if (mUpdated == 1) {
            return new Result(UPDATED_ONE, mUpdatedName);
        }
        if (mUpdated > 1) {
            return new Result(UPDATED_SEVERAL, null);
        }
        if (timedOut && !mPending.isEmpty()) {
            return new Result(COULD_NOT_CHECK, null);
        }
        if (mDeclined > 0) {
            return null;
        }
        return new Result(NONE_FOUND, null);
    }

    @Nullable
    private Result answered(@NonNull String id) {
        mPending.remove(id);
        mAsking.remove(id);
        return mPending.isEmpty() ? finish(false) : null;
    }
}
