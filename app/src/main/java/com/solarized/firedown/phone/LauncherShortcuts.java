package com.solarized.firedown.phone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The app icon's long-press shortcuts: "New tab" and "New private tab", as
 * other browsers show. They start {@link BrowserMainActivity} with
 * {@link IntentActions#NEW_TAB} / {@link IntentActions#NEW_PRIVATE_TAB}.
 *
 * <p>DYNAMIC shortcuts, never a static {@code shortcuts.xml}: Android starts
 * a static shortcut with NEW_TASK | CLEAR_TASK, which would finish the
 * browser, and the browser finishing deletes every private tab. A dynamic
 * shortcut's intent gets NEW_TASK only, so it reaches the singleTask browser
 * like a link from another app does.</p>
 *
 * <p>Published from the browser's onCreate, and only when what the launcher
 * holds differs (the labels follow the device language): a set from the
 * background counts against Android's rate limit.</p>
 */
public final class LauncherShortcuts {

    private static final String TAG = LauncherShortcuts.class.getSimpleName();

    private static final String ID_NEW_TAB = "new_tab";
    private static final String ID_NEW_PRIVATE_TAB = "new_private_tab";

    private LauncherShortcuts() {
    }

    /**
     * Off the main thread: two binder calls. Compares labels, action and
     * component; a changed icon keeps its resource name, which Android
     * re-resolves on update.
     */
    public static void publish(@NonNull Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) {
            return;
        }
        List<ShortcutInfo> wanted = new ArrayList<>();
        wanted.add(build(context, ID_NEW_TAB, IntentActions.NEW_TAB,
                R.string.open_new_tab_2, R.drawable.ic_shortcut_new_tab, 0));
        wanted.add(build(context, ID_NEW_PRIVATE_TAB, IntentActions.NEW_PRIVATE_TAB,
                R.string.open_new_private_tab_2, R.drawable.ic_shortcut_new_private_tab, 1));
        // A launcher-side failure (a locked user before first unlock, the
        // rate limit) never reaches the app: the shortcuts are a convenience.
        try {
            if (sameAs(manager.getDynamicShortcuts(), wanted)) {
                return;
            }
            manager.setDynamicShortcuts(wanted);
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "publish failed", e);
            }
        }
    }

    @NonNull
    private static ShortcutInfo build(@NonNull Context context, @NonNull String id,
                                      @NonNull String action, @StringRes int label,
                                      @DrawableRes int icon, int rank) {
        Intent intent = new Intent(action);
        intent.setClass(context, BrowserMainActivity.class);
        String text = context.getString(label);
        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(text)
                .setLongLabel(text)
                .setIcon(Icon.createWithResource(context, icon))
                .setIntent(intent)
                .setRank(rank)
                .build();
    }

    private static boolean sameAs(@NonNull List<ShortcutInfo> current,
                                  @NonNull List<ShortcutInfo> wanted) {
        if (current.size() != wanted.size()) {
            return false;
        }
        for (ShortcutInfo want : wanted) {
            ShortcutInfo have = null;
            for (ShortcutInfo info : current) {
                if (want.getId().equals(info.getId())) {
                    have = info;
                    break;
                }
            }
            Intent haveIntent = have == null ? null : have.getIntent();
            Intent wantIntent = want.getIntent();
            if (haveIntent == null || wantIntent == null
                    || !TextUtils.equals(have.getShortLabel(), want.getShortLabel())
                    || !TextUtils.equals(haveIntent.getAction(), wantIntent.getAction())
                    || !Objects.equals(haveIntent.getComponent(), wantIntent.getComponent())) {
                return false;
            }
        }
        return true;
    }
}
