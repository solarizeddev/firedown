package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

/**
 * Back from a page the shield sheet opened returns to the shield sheet.
 * <p>
 * The sheet's rows open their pages (the blocked hosts, the certificate,
 * Site permissions, Clear browsing data, a blocker's popup) in place of the
 * sheet, which pops itself on the way: two stacked sheets would show at
 * once. So the page, left with Back, a tap outside or its Cancel button,
 * sends this result and the browser opens the shield sheet again
 * ({@code BrowserFragment.reopenShieldSheet}), as Chrome's and Firefox's
 * site panels return from a sub-page. A page left by its own action (a
 * Reset all, Delete, the add-on closing its popup) returns nowhere.
 */
public final class ShieldReturn {

    /** Fragment result key the browser listens on. */
    public static final String RESULT_KEY = "shield_return";

    private ShieldReturn() {
    }

    /**
     * Called from the page's {@code onDismiss}: sends the result when the
     * user left it ({@code cancelled}), never on a rotation's dismiss.
     */
    public static void onDismiss(@NonNull Fragment page, boolean cancelled) {
        if (!cancelled || !page.isAdded()) {
            return;
        }
        Activity activity = page.getActivity();
        if (activity == null || activity.isChangingConfigurations()) {
            return;
        }
        page.getParentFragmentManager().setFragmentResult(RESULT_KEY, new Bundle());
    }
}
