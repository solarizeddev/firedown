package com.solarized.firedown.crash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Bottom sheet that surfaces a captured Java crash on the next
 * launch. Extends {@link BaseBottomSheetDialogFragment} so width /
 * height caps, rotation handling, and system-bar insets come for
 * free — matches every other sheet in the app.
 *
 * <p>Four actions:
 * <ul>
 *   <li><b>Send report</b> — the filled hero: one-tap anonymous POST of
 *       the stored report JSON to the api's /v1/crash collector
 *       ({@link CrashUploader}) — no GitHub account, no paste. Success
 *       toasts and dismisses (which sweeps the pending files); failure
 *       keeps the sheet up so Copy/Report remain the fallback.</li>
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

    /** Absolute paths of the pending reports, handed to the sheet by
     *  {@link #showIfPending} so its own view creation doesn't re-scan
     *  the disk on the main thread. */
    private static final String ARG_PENDING_PATHS = "pending_paths";

    /** Shared app OkHttp client — the anonymous one-tap send. */
    @Inject
    OkHttpClient mHttpClient;

    @Nullable
    private CrashReport mReport;
    @NonNull
    private List<File> mPending = Collections.emptyList();

    /**
     * Shows the sheet if at least one pending report exists. Safe to
     * call from {@code onResume} on every activity — idempotent via
     * the {@code findFragmentByTag} check, and after the user actions
     * the sheet the pending files are deleted so subsequent calls
     * find nothing pending.
     *
     * <p>The disk scan ({@link CrashStorage#listPending}) runs on
     * {@code diskExecutor}, NOT the main thread — this is called from
     * every activity's {@code onResume}, and the common case (no pending
     * crash) would otherwise touch disk on the UI thread on every resume
     * (a StrictMode {@code DiskReadViolation}). The found paths are passed
     * to the sheet as arguments so its {@code onCreateView} reads them
     * instead of scanning again.</p>
     */
    public static void showIfPending(@NonNull Context context,
                                     @NonNull FragmentManager fm,
                                     @NonNull Executor diskExecutor) {
        // Cheap main-thread pre-checks — bail before touching disk at all.
        if (fm.isStateSaved()) return;
        if (fm.findFragmentByTag(TAG) != null) return;
        diskExecutor.execute(() -> {
            List<File> pending = CrashStorage.listPending(context);
            if (pending.isEmpty()) return;
            String[] paths = new String[pending.size()];
            for (int i = 0; i < pending.size(); i++) {
                paths[i] = pending.get(i).getAbsolutePath();
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.i(TAG, "showIfPending: pending=" + paths.length
                        + " stateSaved=" + fm.isStateSaved()
                        + " alreadyShown=" + (fm.findFragmentByTag(TAG) != null));
                // Re-check on the main thread: state may have changed
                // during the async gap (activity paused / another sheet shown).
                if (fm.isStateSaved()) return;
                if (fm.findFragmentByTag(TAG) != null) return;
                CrashReportSheet sheet = new CrashReportSheet();
                Bundle args = new Bundle();
                args.putStringArray(ARG_PENDING_PATHS, paths);
                sheet.setArguments(args);
                sheet.show(fm, TAG);
            });
        });
    }

    /**
     * Pending reports for this sheet — from the {@link #ARG_PENDING_PATHS}
     * arguments {@link #showIfPending} set (which survive recreation). Falls
     * back to a fresh {@link CrashStorage#listPending} scan only if the args
     * are somehow absent, so a rare recreation without them still works.
     */
    @NonNull
    private List<File> resolvePending() {
        Bundle args = getArguments();
        if (args != null) {
            String[] paths = args.getStringArray(ARG_PENDING_PATHS);
            if (paths != null) {
                List<File> list = new ArrayList<>(paths.length);
                for (String p : paths) list.add(new File(p));
                return list;
            }
        }
        return CrashStorage.listPending(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mPending = resolvePending();
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

        MaterialButton send = view.findViewById(R.id.crash_send);
        MaterialButton report = view.findViewById(R.id.crash_report);
        MaterialButton copy = view.findViewById(R.id.crash_copy);
        MaterialButton dismiss = view.findViewById(R.id.crash_dismiss);

        send.setOnClickListener(v -> onSend(send));
        report.setOnClickListener(v -> onReport());
        copy.setOnClickListener(v -> onCopy());
        dismiss.setOnClickListener(v -> dismissAllowingStateLoss());
    }

    /**
     * One-tap anonymous send. The button disables for the in-flight window so
     * a double-tap can't POST twice; on failure it re-enables and the sheet
     * stays up — Copy and Report remain the fallback, and the pending files
     * are NOT swept (only a dismissal sweeps, and only success dismisses).
     */
    private void onSend(@NonNull MaterialButton send) {
        if (mReport == null) {
            return;
        }
        send.setEnabled(false);
        CrashUploader.send(mHttpClient, mReport, ok -> {
            if (!isAdded()) {
                return;
            }
            if (ok) {
                Toast.makeText(requireContext(),
                        R.string.crash_sheet_sent, Toast.LENGTH_SHORT).show();
                dismissAllowingStateLoss();
            } else {
                send.setEnabled(true);
                Toast.makeText(requireContext(),
                        R.string.crash_sheet_send_failed, Toast.LENGTH_LONG).show();
            }
        });
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
