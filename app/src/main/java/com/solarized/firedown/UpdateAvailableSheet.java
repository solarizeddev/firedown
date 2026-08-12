package com.solarized.firedown;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;

import java.io.File;

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
 * snoozed update triggers it) and follows the CrashReportSheet pattern:
 * idempotent {@link #showIfReady} from {@code onResume}, crash sheet has
 * priority.
 *
 * <p>A dismissal SNOOZES rather than suppresses — see {@link #onDismiss}. At
 * most {@link #MAX_PROMPTS} appearances per version, {@link #SNOOZE_INTERVAL_MS}
 * apart, then quiet for good.
 */
@AndroidEntryPoint
public class UpdateAvailableSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "UpdateAvailableSheet";
    // CrashReportSheet's own tag (private there) — the crash sheet is also shown
    // from BaseActivity.onResume and takes priority over this one.
    private static final String CRASH_SHEET_TAG = "CrashReportSheet";
    private static final String ARG_PREVIEW = "preview";

    /**
     * How long a dismissal holds the sheet back. "Later" has to MEAN later, or
     * the button is a lie — and for the user this sheet exists for (the one who
     * denied notifications, and so has no other surface), a permanent
     * suppression from one tap re-opens the very gap the sheet was built to
     * close: the update sits verified on disk, and nothing ever offers it
     * again.
     */
    private static final long SNOOZE_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /**
     * Total times the sheet may appear for ONE version, after which a dismissal
     * is permanent. A reminder that never stops is nagging, and this sheet is
     * modal — so it gets the initial showing plus two reminders, then goes
     * quiet and leaves the notification (and Settings) to carry the update.
     */
    private static final int MAX_PROMPTS = 3;

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
        // Snoozed for THIS version and the snooze hasn't elapsed. A different
        // stored version means a newer update arrived — show it regardless of
        // how the previous one was dismissed.
        if (prefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, -1) == readyVc
                && System.currentTimeMillis()
                        < prefs.getLong(Keys.UPDATE_PROMPT_SNOOZE_UNTIL, 0L)) {
            return;
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
        String changelog;
        if (preview) {
            // Prefer the REAL changelog/version the worker last stored from
            // status.json; fall back to a fabricated sample only on a fresh
            // install before any check has run (or a status.json with no
            // changelog field).
            String storedChangelog = UpdateDownloader.getLastChangelog(context);
            String storedName = UpdateDownloader.getLastChangelogName(context);
            if (!storedChangelog.isEmpty()) {
                name = storedName.isEmpty()
                        ? getString(R.string.app_name) + " (preview)" : storedName;
                changelog = storedChangelog;
            } else {
                name = getString(R.string.app_name) + " (preview)";
                changelog = "• Faster, more reliable updates\n"
                        + "• Fixed video capture on some sites\n"
                        + "• Smaller download size";
            }
        } else {
            mReadyVersionCode = UpdateDownloader.readyVersionCode(context);
            name = UpdateDownloader.readyVersionName(context);
            changelog = UpdateDownloader.getChangelogFor(context, mReadyVersionCode);
        }
        String shown = (name == null || name.isEmpty())
                ? getString(R.string.app_name) : name;

        // The version being installed — prominent, and carrying the DOWNLOAD
        // SIZE. The size used to hang off the line below ("Current version
        // 1.1.87 · 205 MB"), which reads as the size of what you already have;
        // it is the size of the APK waiting to be installed, so it belongs to
        // the new version's line. (Skipped in preview — no real file there.)
        TextView version = view.findViewById(R.id.update_sheet_version);
        File apk = Preferences.getUpdateApkFile(context);
        long bytes = (apk != null && apk.exists()) ? apk.length() : 0L;
        if (bytes > 0) {
            version.setText(getString(R.string.update_available_sheet_version_size,
                    shown, Formatter.formatShortFileSize(context, bytes)));
        } else {
            version.setText(shown);
        }

        // ...and the version it replaces.
        TextView current = view.findViewById(R.id.update_sheet_current);
        current.setText(getString(R.string.update_available_sheet_current, App.getVersionName()));

        // "What's new" header + release notes — both shown only when status.json
        // carries a changelog for this version.
        TextView whatsNew = view.findViewById(R.id.update_sheet_whats_new);
        LinearLayout changelogContainer = view.findViewById(R.id.update_sheet_changelog);
        boolean hasChangelog = changelog != null && !changelog.trim().isEmpty();
        if (hasChangelog) {
            fillChangelog(changelogContainer, changelog.trim());
        }
        int changelogVisibility = hasChangelog ? View.VISIBLE : View.GONE;
        whatsNew.setVisibility(changelogVisibility);
        changelogContainer.setVisibility(changelogVisibility);

        MaterialButton install = view.findViewById(R.id.update_sheet_install);
        // In preview there's no real APK on disk, so Install just closes.
        install.setOnClickListener(v -> {
            if (preview) {
                dismissAllowingStateLoss();
            } else {
                onInstall(name);
            }
        });

        // No decline button on purpose — the drag handle, back and tap-outside
        // dismiss, and onDismiss snoozes all of them identically. See the
        // footer comment in fragment_dialog_update_available.xml.
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
     * Populates the changelog container with one row per "• " bullet, separated
     * by a real top margin. Spacing is a view margin (not line spacing) because
     * the Material3 textAppearance's lineHeight overrides lineSpacingExtra/
     * Multiplier — so those have no effect, but margins always work. Each row
     * also gets a hanging indent so a wrapped bullet aligns under its text.
     */
    private void fillChangelog(LinearLayout container, String raw) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        int gap = Math.round(12 * getResources().getDisplayMetrics().density);
        boolean first = true;
        for (String line : raw.split("\n")) {
            String item = line.trim();
            if (item.isEmpty()) {
                continue;
            }
            TextView row = (TextView) inflater.inflate(
                    R.layout.item_update_changelog_bullet, container, false);
            // Flush-left bullets — wrapped lines return to the margin (no hanging
            // indent), matching the Mercurygram changelog style.
            row.setText(item);
            if (!first) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
                lp.topMargin = gap;
                row.setLayoutParams(lp);
            }
            container.addView(row);
            first = false;
        }
    }

    /**
     * Any dismissal — Install, swipe-down, back, tap-outside — SNOOZES the
     * sheet for this version so it can't re-pop on the next resume, and the
     * {@link #MAX_PROMPTS}th one makes that permanent.
     *
     * <p>It used to suppress permanently on the FIRST dismissal. That is worst
     * for the user the sheet exists for — POST_NOTIFICATIONS denied, so no
     * notification either — who swipes it away meaning "not right now" and is
     * never offered the update again, with the verified APK sitting on disk.
     * Rescheduling is also what lets the sheet carry no decline button: a
     * dismissal gesture now means the same "later" a button would have, so
     * the button was only a second name for it (see the layout's footer
     * comment for the misclick objection that finished it off).
     *
     * <p>Every exit path snoozes, INSTALL INCLUDED, and that is deliberate
     * rather than sloppy: a successful install needs no suppression at all
     * (the new versionCode fails {@code showIfReady}'s newer-than-installed
     * check, so the sheet can never return for it), while an install the user
     * cancels at Android's package-installer prompt is exactly a case that
     * SHOULD be offered again tomorrow. The snooze also stops the sheet
     * re-popping on the resume that follows the installer prompt.
     */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        Context context = getContext();
        if (context != null && mReadyVersionCode > 0) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            // A different stored version = the first dismissal of this update:
            // start its count fresh rather than inheriting the previous one's.
            int count = prefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, -1) == mReadyVersionCode
                    ? prefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_COUNT, 0) + 1
                    : 1;
            long until = count >= MAX_PROMPTS
                    ? Long.MAX_VALUE
                    : System.currentTimeMillis() + SNOOZE_INTERVAL_MS;
            prefs.edit()
                    .putInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, mReadyVersionCode)
                    .putInt(Keys.UPDATE_PROMPT_SNOOZE_COUNT, count)
                    .putLong(Keys.UPDATE_PROMPT_SNOOZE_UNTIL, until)
                    .apply();
        }
        super.onDismiss(dialog);
    }
}
