package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.R;

/**
 * Back from a page the shield sheet opened returns to the shield sheet.
 * <p>
 * The sheet's rows open their pages (the blocked hosts, the certificate,
 * Site permissions, Clear browsing data, a blocker's popup) in place of the
 * sheet, which pops itself on the way: two stacked sheets would show at
 * once. So the page, left with Back or its Cancel button, sends this result
 * and the browser opens the shield sheet again
 * ({@code BrowserFragment.reopenShieldSheet}), as Chrome's and Firefox's
 * site panels go back from a sub-page. A tap outside or a swipe down closes
 * everything, as it closes their panels: those mean "get me out", and a
 * second sheet to dismiss was the cost of treating them as Back. A page left
 * by its own action (Reset all, Delete, the add-on closing its popup)
 * returns nowhere.
 */
public final class ShieldReturn {

    /** Fragment result key the browser listens on. */
    public static final String RESULT_KEY = "shield_return";

    /** Result extra: the add-on whose popup was left (a blocker row's). */
    public static final String EXTRA_ADDON_ID = "addon_id";

    /** Result extra: that blocker's state for the site when its row was tapped. */
    public static final String EXTRA_BLOCKER_STATE = "blocker_state";

    private ShieldReturn() {
    }

    /**
     * How a page was left. {@link #watch} is called from the page's
     * {@code onStart}: a bottom sheet records a tap outside it and a drag
     * (Back reaches a sheet as a cancel with neither: with predictive back
     * Material hides the sheet itself, so a back callback could miss it); a
     * centred dialog records Back through its own dispatcher.
     */
    public static final class Exit {

        @Nullable
        private Dialog mWatched;
        private boolean mAway;
        private boolean mBack;

        public void watch(@Nullable Dialog dialog) {
            if (dialog == null || dialog == mWatched) {
                return;
            }
            mWatched = dialog;
            mAway = false;
            mBack = false;
            if (dialog instanceof BottomSheetDialog sheet) {
                View outside = sheet.findViewById(R.id.touch_outside);
                if (outside != null) {
                    // Material's own click listener still cancels the sheet.
                    outside.setOnTouchListener((v, event) -> {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            mAway = true;
                        }
                        return false;
                    });
                }
                sheet.getBehavior().addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {
                        if (newState == BottomSheetBehavior.STATE_DRAGGING) {
                            mAway = true;
                        } else if (newState == BottomSheetBehavior.STATE_EXPANDED
                                || newState == BottomSheetBehavior.STATE_COLLAPSED
                                || newState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                            // A drag that settled back did not close it.
                            mAway = false;
                        }
                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                    }
                });
            } else if (dialog instanceof ComponentDialog component) {
                component.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        mBack = true;
                        setEnabled(false);
                        component.cancel();
                    }
                });
            }
        }

        /** A Cancel button counts as Back. */
        public void markBack() {
            mBack = true;
        }

        /** Whether the page was left with Back (or its Cancel button). */
        public boolean leftWithBack(boolean cancelled) {
            if (mBack) {
                return true;
            }
            return cancelled && mWatched instanceof BottomSheetDialog && !mAway;
        }
    }

    /**
     * Called from the page's {@code onDismiss}: sends the result when the
     * user left it with Back, never on a rotation's dismiss.
     */
    public static void onDismiss(@NonNull Fragment page, boolean back) {
        onDismiss(page, back, new Bundle());
    }

    /** As {@link #onDismiss(Fragment, boolean)}, with extras for the browser. */
    public static void onDismiss(@NonNull Fragment page, boolean back, @NonNull Bundle result) {
        if (!back || !page.isAdded()) {
            return;
        }
        Activity activity = page.getActivity();
        if (activity == null || activity.isChangingConfigurations()) {
            return;
        }
        page.getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
    }
}
