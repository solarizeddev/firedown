package com.solarized.firedown;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * In-app "update available" bottom sheet, shown on resume when a SHA+signature
 * verified update APK is downloaded and ready to install.
 *
 * Why this exists (the notifications-denied gap): the install prompt is a
 * notification, and on Android 13+ a user who denied POST_NOTIFICATIONS never
 * sees it — the entire update path goes silent. This sheet is the fallback
 * surface: it reads the same verified "ready" record UpdateDownloader writes and
 * offers the install in-app, so a notification denial no longer means the user
 * can never update. It's modal but infrequent (only a ready, newer, not-yet
 * dismissed update triggers it) and follows the CrashReportSheet pattern:
 * idempotent {@link #showIfReady} from {@code onResume}, crash sheet has
 * priority.
 */
@AndroidEntryPoint
public class UpdateAvailableSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "UpdateAvailableSheet";
    // CrashReportSheet's own tag (private there) — the crash sheet is also shown
    // from BaseActivity.onResume and takes priority over this one.
    private static final String CRASH_SHEET_TAG = "CrashReportSheet";
    private static final String ARG_PREVIEW = "preview";

    private int mReadyVersionCode;

    /**
     * Shows the sheet if a verified update strictly newer than the installed
     * version is ready and the user hasn't already dismissed it for that
     * version. Safe to call from every activity's {@code onResume} — idempotent
     * via the tag check and self-bailing when nothing is ready.
     */
    public static void showIfReady(@NonNull Context context, @NonNull FragmentManager fm) {
        if (fm.isStateSaved()) return;
        if (fm.findFragmentByTag(TAG) != null) return;
        if (fm.findFragmentByTag(CRASH_SHEET_TAG) != null) return;

        // isVerifiedReady(installed + 1) ⇒ readyVersion >= installed+1 ⇒ strictly
        // newer than installed, with a file actually on disk.
        if (!UpdateDownloader.isVerifiedReady(context, App.getVersionCode() + 1)) {
            return;
        }
        int readyVc = UpdateDownloader.readyVersionCode(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getInt(Keys.UPDATE_PROMPT_DISMISSED_VERSION, -1) == readyVc) {
            return; // user chose "Later" for this exact version
        }
        new UpdateAvailableSheet().show(fm, TAG);
    }

    /**
     * DEBUG-only: force-show the sheet with a fabricated version, BYPASSING the
     * verified-ready gate, so the layout can be previewed without a real
     * download. No-op in release builds. To try it, temporarily add to an
     * activity's onResume (e.g. BaseActivity):
     *   UpdateAvailableSheet.showPreview(getSupportFragmentManager());
     * In preview, "Install" just dismisses (there is no real APK on disk).
     */
    public static void showPreview(@NonNull FragmentManager fm) {
        if (!BuildConfig.DEBUG) return;
        if (fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) return;
        UpdateAvailableSheet sheet = new UpdateAvailableSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_PREVIEW, true);
        sheet.setArguments(args);
        sheet.show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_update_available, container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = requireContext();

        boolean preview = getArguments() != null && getArguments().getBoolean(ARG_PREVIEW, false);

        // Process death between show() and here, or a just-installed update, can
        // leave nothing ready — bail rather than show an empty sheet. Skipped in
        // preview mode, which fabricates a name purely for layout testing.
        if (!preview && !UpdateDownloader.isVerifiedReady(context, App.getVersionCode() + 1)) {
            dismissAllowingStateLoss();
            return;
        }

        String name;
        if (preview) {
            name = getString(R.string.app_name) + " (preview)";
        } else {
            mReadyVersionCode = UpdateDownloader.readyVersionCode(context);
            name = UpdateDownloader.readyVersionName(context);
        }
        String shown = (name == null || name.isEmpty())
                ? getString(R.string.app_name) : name;

        TextView body = view.findViewById(R.id.update_sheet_body);
        body.setText(getString(R.string.update_available_sheet_body, shown));

        MaterialButton install = view.findViewById(R.id.update_sheet_install);
        MaterialButton later = view.findViewById(R.id.update_sheet_later);
        // In preview there's no real APK on disk, so Install just closes.
        install.setOnClickListener(v -> {
            if (preview) {
                dismissAllowingStateLoss();
            } else {
                onInstall(name);
            }
        });
        later.setOnClickListener(v -> dismissAllowingStateLoss());
    }

    private void onInstall(String name) {
        // UpdateInstaller.install does disk IO (reads the APK into a
        // PackageInstaller session) — off the main thread. Use the application
        // context so the worker thread can't outlive and leak this fragment.
        Context app = requireContext().getApplicationContext();
        new Thread(() -> UpdateInstaller.install(app, name)).start();
        dismissAllowingStateLoss();
    }

    /**
     * Any dismissal — Install, Later, swipe-down, back, tap-outside — suppresses
     * the sheet for THIS version so it doesn't re-pop on every resume. A newer
     * version resets it (the stored value is the dismissed versionCode), and the
     * notification still carries the update either way.
     */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        Context context = getContext();
        if (context != null && mReadyVersionCode > 0) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putInt(Keys.UPDATE_PROMPT_DISMISSED_VERSION, mReadyVersionCode)
                    .apply();
        }
        super.onDismiss(dialog);
    }
}
