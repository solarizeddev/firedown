package com.solarized.firedown.phone.dialogs;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.UpdateAvailableSheet;
import com.solarized.firedown.UpdateDownloader;

/**
 * The ⋮ menus' "Update ready" row (item_popup_update_ready), shared by the
 * browser and home menus: shown while a verified update newer than this build
 * waits, and a tap closes the menu and opens the update sheet on purpose.
 */
final class PopupUpdateRow {

    private PopupUpdateRow() {}

    /**
     * @param closeMenu pops the menu's own back-stack entry
     */
    static void bind(@NonNull View root, @NonNull FragmentActivity activity,
                     @NonNull Runnable closeMenu) {
        View group = root.findViewById(R.id.popup_update_group);
        if (group == null) {
            return;
        }
        boolean ready = UpdateDownloader.isVerifiedReady(activity, App.getVersionCode() + 1);
        group.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (!ready) {
            return;
        }
        String name = UpdateDownloader.readyVersionName(activity);
        TextView sub = root.findViewById(R.id.popup_update_sub);
        sub.setText(name == null || name.isEmpty() ? activity.getString(R.string.app_name) : name);
        root.findViewById(R.id.popup_update).setOnClickListener(v -> {
            closeMenu.run();
            UpdateAvailableSheet.openOnPurpose(activity, activity.getSupportFragmentManager());
        });
    }
}
