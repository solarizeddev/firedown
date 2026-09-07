package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.solarized.firedown.Preferences;

/**
 * The post-redeem bookkeeping of a credit purchase, shared by the two settlers
 * — the buy wizard's poll loop ({@code BuyCreditViewModel}) and the background
 * {@link CreditSettleWorker} — so a credit that both of them redeem (the wizard
 * is open while the periodic job fires) is booked EXACTLY ONCE.
 *
 * <p>The redeem itself is idempotent server-side (storage burns
 * {@code sha256(secret)}; a second redeem returns {@code credit-spent}, which
 * both settlers treat as applied), but the client-side bookkeeping is not: the
 * plan merge ACCUMULATES into the stored plan shape, so booking one credit
 * twice would double-count it on the status hero. Hence {@link #commitRedeemed}
 * runs under a process-wide lock and re-reads the persisted record inside it —
 * whoever clears the record first has booked the credit, the other finds it
 * gone and books nothing. Both WorkManager and the ViewModel run in this one
 * process, so a static lock is sufficient.
 */
public final class CreditSettlement {

    private static final Object LOCK = new Object();

    private CreditSettlement() {
    }

    /**
     * The two things the commit touches, behind an interface so the
     * once-only rule is unit-testable with an in-memory store under real
     * thread contention ({@code CreditSettlementConcurrencyTest}): the
     * persisted record, and the bookkeeping that must run exactly once.
     */
    public interface Books {
        /** The stored pending record, or null. */
        @Nullable
        PendingPurchase load();

        /** Forgets the stored record. */
        void clear();

        /** The once-only bookkeeping for a credit of this shape. */
        void book(int sizeGb, int durationMonths);
    }

    /**
     * Books a redeemed (or {@code credit-spent}) credit: merges the plan shape
     * into the status-hero prefs, marks Cloud Backup enabled, snapshots the
     * pre-purchase runway for the "+N added" receipt, and clears the pending
     * record. Returns {@code false} — and applies NOTHING — when the record for
     * {@code quoteIdHex} is no longer stored, i.e. another settler already
     * committed this same credit.
     */
    public static boolean commitRedeemed(Context context, SharedPreferences prefs,
                                         CloudBackupManager cloud, String quoteIdHex,
                                         int sizeGb, int durationMonths) {
        return commitRedeemed(androidBooks(context, prefs, cloud), quoteIdHex, sizeGb, durationMonths);
    }

    /** The once-only commit over any {@link Books}: whoever finds the record
     *  still stored books and clears it; everyone else finds it gone. */
    public static boolean commitRedeemed(Books books, String quoteIdHex,
                                         int sizeGb, int durationMonths) {
        synchronized (LOCK) {
            PendingPurchase stored = books.load();
            if (stored == null || !stored.quoteIdHex.equals(quoteIdHex)) {
                return false;
            }
            books.book(sizeGb, durationMonths);
            books.clear();
            return true;
        }
    }

    /** The production {@link Books}: the keystore-backed record and the
     *  prefs/CloudBackupManager bookkeeping. */
    static Books androidBooks(Context context, SharedPreferences prefs, CloudBackupManager cloud) {
        return new Books() {
            @Override
            public PendingPurchase load() {
                return PendingPurchase.load(context);
            }

            @Override
            public void clear() {
                PendingPurchase.clear(context);
            }

            @Override
            public void book(int sizeGb, int durationMonths) {
                bookOnAndroid(prefs, cloud, sizeGb, durationMonths);
            }
        };
    }

    private static void bookOnAndroid(SharedPreferences prefs, CloudBackupManager cloud,
                                      int sizeGb, int durationMonths) {
        if (sizeGb > 0 && durationMonths > 0) {
            // ACCUMULATE with any previously stored plan instead of
            // overwriting: the server-side balance SUMS across purchases
            // (AddCredit is a += upsert), so an overwrite made a stacking
            // buyer's hero under-report what they paid for (second purchase
            // replaced the shown plan). The merge mirrors how the metered
            // server actually drains the balance (bytes × time): the size
            // cap is the LARGEST size bought (the most the user may fill —
            // it feeds the usage bar's denominator), and the duration is
            // the combined GB-month total re-expressed at that size (it
            // feeds the runway tick count). 50 GB×12mo bought twice →
            // 50 GB×24mo; 50 GB×12mo + 20 GB×3mo = 660 GB-months →
            // 50 GB×13mo. A first purchase (nothing stored) reduces to the
            // plain write. deleteAllData still clears both keys.
            int mergedSize = sizeGb;
            int mergedMonths = durationMonths;
            int oldSize = prefs.getInt(Preferences.CLOUD_PLAN_SIZE_GB, 0);
            int oldMonths = prefs.getInt(Preferences.CLOUD_PLAN_DURATION_MONTHS, 0);
            if (oldSize > 0 && oldMonths > 0) {
                long totalGbMonths = (long) oldSize * oldMonths
                        + (long) sizeGb * durationMonths;
                mergedSize = Math.max(oldSize, sizeGb);
                mergedMonths = (int) Math.max(1,
                        Math.round((double) totalGbMonths / mergedSize));
            }
            prefs.edit()
                    .putInt(Preferences.CLOUD_PLAN_SIZE_GB, mergedSize)
                    .putInt(Preferences.CLOUD_PLAN_DURATION_MONTHS, mergedMonths)
                    .apply();
        }
        // A redeemed credit means Cloud Backup is IN USE, even before the
        // first file is backed up. Without this the flag stayed false until a
        // successful backup, so a paid plan was invisible (status hero showed
        // "nothing backed up yet" with no balance, home line hidden, Downloads
        // overflow routed to setup) AND a bookmark-sync sign-out would wipe
        // the shared code — the only key to the paid balance.
        cloud.markEnabled();
        // Snapshot the PRE-purchase runway for the Cloud hero's one-shot
        // "+N added" receipt (SyncSettingsFragment#applyCreditDelta compares
        // it against the next fresh quota). Written ONLY when the before is
        // KNOWN: with an empty/stale cache the chip could otherwise claim the
        // account's whole runway was "added" by this purchase. The
        // known-before requirement also naturally silences the receipt on a
        // FIRST purchase from unfunded, where the hero itself appearing is
        // the event.
        CloudBackupManager.Status lastStatus = cloud.lastStatus();
        int beforeMonths = CloudBackupManager.runwayMonths(
                lastStatus != null ? lastStatus.quota : null);
        if (beforeMonths >= 0) {
            prefs.edit()
                    .putInt(Preferences.CLOUD_TOPUP_BEFORE_MONTHS, beforeMonths)
                    .putBoolean(Preferences.CLOUD_TOPUP_SHOWN, false)
                    .apply();
        }
    }
}
