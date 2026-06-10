package com.solarized.firedown.crash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;

import java.io.File;
import java.util.Collections;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Bottom sheet that surfaces a captured Java crash on the next
 * launch. Extends {@link BaseBottomSheetDialogFragment} so width /
 * height caps, rotation handling, and system-bar insets come for
 * free — matches every other sheet in the app.
 *
 * <p>Three actions:
 * <ul>
 *   <li><b>Report</b> — opens the pre-filled GitHub new-issue URL
 *       in the system browser; the URL body is capped at ~6KB, so we
 *       also copy the full trace to the clipboard as a paste-in
 *       fallback for long traces.</li>
 *   <li><b>Copy</b> — clipboard only.</li>
 *   <li><b>Dismiss</b> — closes without sending.</li>
 * </ul>
 *
 * <p>Pending files are deleted in {@link #onDismiss(DialogInterface)}
 * so swipe-down, back press, and tap-outside all sweep the same way
 * the buttons do.</p>
 *
 * <p>If there are multiple pending crashes we show the newest one and
 * sweep the rest on dismiss — multiple pendings almost always share
 * a root cause.</p>
 */
@AndroidEntryPoint
public class CrashReportSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "CrashReportSheet";

    @Nullable
    private CrashReport mReport;
    @NonNull
    private List<File> mPending = Collections.emptyList();

    /**
     * Shows the sheet if at least one pending report exists. Safe to
     * call from {@code onResume} on every activity — idempotent via
     * the {@code findFragmentByTag} check, and after the user actions
     * the sheet the pending files are deleted so subsequent calls
     * bail at the {@code pending.isEmpty()} guard.
     */
    public static void showIfPending(@NonNull Context context,
                                     @NonNull FragmentManager fm) {
        List<File> pending = CrashStorage.listPending(context);
        Log.i(TAG, "showIfPending: pending=" + pending.size()
                + " stateSaved=" + fm.isStateSaved()
                + " alreadyShown=" + (fm.findFragmentByTag(TAG) != null));
        if (pending.isEmpty()) return;
        if (fm.findFragmentByTag(TAG) != null) return;
        if (fm.isStateSaved()) return;
        new CrashReportSheet().show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mPending = CrashStorage.listPending(requireContext());
        if (mPending.isEmpty()) {
            dismissAllowingStateLoss();
            return null;
        }
        mReport = CrashStorage.read(mPending.get(0));
        if (mReport == null) {
            // Corrupt file — drop it and bail.
            CrashStorage.delete(mPending.get(0));
            dismissAllowingStateLoss();
            return null;
        }
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_crash_report,
                container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mReport == null) return;

        TextView subtitle = view.findViewById(R.id.crash_subtitle);
        TextView trace = view.findViewById(R.id.crash_trace);

        CharSequence when = DateUtils.getRelativeTimeSpanString(
                mReport.timestamp, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        String subtitleText = when + " · v" + mReport.versionName
                + " · " + mReport.type + "/" + mReport.origin;
        if (mPending.size() > 1) {
            subtitleText += "  (+" + (mPending.size() - 1) + " more)";
        }
        subtitle.setText(subtitleText);
        trace.setText(mReport.trace);

        MaterialButton report = view.findViewById(R.id.crash_report);
        MaterialButton copy = view.findViewById(R.id.crash_copy);
        MaterialButton dismiss = view.findViewById(R.id.crash_dismiss);

        report.setOnClickListener(v -> onReport());
        copy.setOnClickListener(v -> onCopy());
        dismiss.setOnClickListener(v -> dismissAllowingStateLoss());
    }

    private void onReport() {
        if (mReport == null) return;
        // Stash the full trace on the clipboard so the user can paste
        // it into the issue body if GitHub's URL-length cap truncated
        // the version we sent inline.
        copyToClipboard(CrashReportUrlBuilder.fullText(mReport));

        Uri url = CrashReportUrlBuilder.build(mReport);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                    R.string.crash_sheet_open_failed, Toast.LENGTH_LONG).show();
            return;
        }
        dismissAllowingStateLoss();
    }

    private void onCopy() {
        if (mReport == null) return;
        copyToClipboard(CrashReportUrlBuilder.fullText(mReport));
        Toast.makeText(requireContext(),
                R.string.crash_sheet_copied, Toast.LENGTH_SHORT).show();
        dismissAllowingStateLoss();
    }

    /**
     * Any dismissal — Report/Copy/Dismiss button, swipe-down, back
     * press, tap outside — sweeps the pending files. Without this
     * override, the non-button paths left files on disk and the
     * sheet popped on every subsequent activity's onResume.
     */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        for (File f : mPending) CrashStorage.delete(f);
        super.onDismiss(dialog);
    }

    private void copyToClipboard(@NonNull String text) {
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Firedown crash", text));
        }
    }
}
