package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.MimeUsageEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.ui.StorageBarView;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Storage — how much space the (finished, non-vault) downloads take, by type.
 * Modelled on Signal's Manage storage screen: headline total, one stacked
 * per-type bar, a labelled legend, and "Review storage".
 *
 * <p>The screen is a DOOR back into the Downloads list, never a second file
 * browser: a legend row returns its filter chip id and "Review storage"
 * returns SORT_SIZE + a cleared filter, both through the Downloads entry's
 * SavedStateHandle (the sort-dialog handshake, handled in
 * BaseDownloadFragment's resume observer). So "what is eating my space" ends
 * in the real list, with its selection/delete/backup actions.
 *
 * <p>Types map 1:1 onto the Downloads filter chips and use the SAME
 * predicates ({@link Sorting#getPredicateDownloads}'s rules via
 * FileUriHelper) so a row's size is exactly what its chip then shows. What no
 * listed chip owns — GIF, subtitles, APKs, archives, unknown mimes — folds
 * into a neutral "Other" row that isn't tappable (the dataviz rule: a residual
 * bucket, never a generated extra hue).
 *
 * <p>The vault is excluded on purpose: this screen is not device-auth gated,
 * and showing the Safe Folder's size here would reveal it (the same
 * "vault never leaves its gate" contract as backup/P2P). The footnote says so.
 *
 * <p><b>Free up space</b> (Signal's "Optimize storage"): when some finished
 * downloads are already in the cloud backup, a card offers to remove their
 * phone copies — they stay in Backups, where they can be streamed or restored.
 * The candidates are the manifest's content keys ({@link
 * CloudBackupManager#contentKey}, the same name+size match the Downloads cloud
 * mark uses) intersected with downloads whose file is really on disk (a
 * missing file frees nothing). Two safety rules, both load-bearing:
 * <ul>
 *   <li>The card shows ONLY on positive evidence — {@code loadBackedUpKeys}
 *       returns an empty set when offline or not set up, so an unknown never
 *       reads as "backed up".</li>
 *   <li>The manifest is RE-PULLED at confirm time and the delete is the
 *       intersection of what the user confirmed with what is still backed up
 *       now. A backup removed meanwhile (another device, the Backups list)
 *       must not turn "free up space" into deleting a file's last copy; an
 *       unreachable manifest deletes nothing.</li>
 * </ul>
 * Deletion goes through the normal download delete ({@link
 * TaskRepository#requestDelete} → the download service), in chunks so a
 * large selection can't overflow the intent's Binder transaction.
 */
@AndroidEntryPoint
public class StorageFragment extends BaseFocusFragment {

    /** Bar/legend order: biggest-usually first, residual last. */
    private enum Type {
        VIDEO(R.string.sort_by_type_video, R.color.storage_video, R.id.chip_video),
        IMAGES(R.string.sort_by_type_images, R.color.storage_images, R.id.chip_image),
        AUDIO(R.string.sort_by_type_audio, R.color.storage_audio, R.id.chip_audio),
        DOCS(R.string.sort_by_type_docs, R.color.storage_docs, R.id.chip_doc),
        OTHER(R.string.storage_other, R.color.storage_other, View.NO_ID);

        @StringRes final int label;
        @ColorRes final int color;
        final int chipId;

        Type(@StringRes int label, @ColorRes int color, int chipId) {
            this.label = label;
            this.color = color;
            this.chipId = chipId;
        }
    }

    @Inject
    DownloadDataRepository mRepository;

    @Inject
    Sorting mSorting;

    @Inject
    CloudBackupManager mCloudBackup;

    @Inject
    TaskRepository mTaskRepository;

    @Inject
    @Qualifiers.HeavyIO
    Executor mHeavyExecutor;

    /** Entities per delete intent — keeps each Binder transaction small. */
    private static final int DELETE_CHUNK = 50;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Orders offload scans: a stale result (an older scan finishing after a
     *  newer one, or after the view died) is dropped. */
    private int mOffloadGen;

    private final List<DownloadEntity> mOffloadCandidates = new ArrayList<>();
    private long mOffloadBytes;

    private View mOffloadCard;
    private TextView mOffloadSummary;
    private Button mOffloadButton;

    private TextView mTotal;
    private TextView mDetail;
    private StorageBarView mBar;
    private LinearLayout mLegend;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_storage, container, false);
        mToolbar = view.findViewById(R.id.toolbar);
        mTotal = view.findViewById(R.id.storage_total);
        mDetail = view.findViewById(R.id.storage_detail);
        mBar = view.findViewById(R.id.storage_bar);
        mLegend = view.findViewById(R.id.storage_legend);
        mOffloadCard = view.findViewById(R.id.storage_offload_card);
        mOffloadSummary = view.findViewById(R.id.storage_offload_summary);
        mOffloadButton = view.findViewById(R.id.storage_offload_button);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mToolbar.setNavigationOnClickListener(v ->
                NavigationUtils.popBackStackSafe(mNavController, R.id.storage));

        view.findViewById(R.id.storage_review).setOnClickListener(v -> reviewStorage());
        mOffloadButton.setOnClickListener(v -> confirmOffload());

        View scroll = view.findViewById(R.id.storage_scroll);
        int basePadding = scroll.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return windowInsets;
        });

        mRepository.getRegularUsageByMime().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Resume, not view creation: a backup made (or removed) while away
        // changes the candidates.
        refreshOffload();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mOffloadGen++;
        mOffloadCard = null;
        mOffloadSummary = null;
        mOffloadButton = null;
        mTotal = null;
        mDetail = null;
        mBar = null;
        mLegend = null;
    }

    private void render(@Nullable List<MimeUsageEntity> rows) {
        if (mLegend == null) {
            return;
        }
        Type[] types = Type.values();
        long[] bytes = new long[types.length];
        int[] files = new int[types.length];
        long totalBytes = 0;
        int totalFiles = 0;
        if (rows != null) {
            for (MimeUsageEntity row : rows) {
                int i = classify(row.mime).ordinal();
                bytes[i] += Math.max(0, row.bytes);
                files[i] += row.files;
                totalBytes += Math.max(0, row.bytes);
                totalFiles += row.files;
            }
        }

        mTotal.setText(Formatter.formatShortFileSize(requireContext(), totalBytes));
        String count = getResources().getQuantityString(
                R.plurals.settings_cloud_backup_file_count, totalFiles, totalFiles);
        long free = freeBytes();
        mDetail.setText(free > 0
                ? count + " · " + getString(R.string.storage_free,
                        Formatter.formatShortFileSize(requireContext(), free))
                : count);

        int[] colors = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            colors[i] = ContextCompat.getColor(requireContext(), types[i].color);
        }
        mBar.setSegments(bytes, colors);
        mBar.setContentDescription(mTotal.getText());

        mLegend.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < types.length; i++) {
            // Empty types are left out entirely — a "Documents · 0 B" row is
            // noise, and it has no segment in the bar to key.
            if (files[i] == 0) {
                continue;
            }
            Type type = types[i];
            View row = inflater.inflate(R.layout.item_storage_legend, mLegend, false);
            row.findViewById(R.id.storage_legend_dot)
                    .setBackgroundTintList(ColorStateList.valueOf(colors[i]));
            ((TextView) row.findViewById(R.id.storage_legend_name)).setText(type.label);
            String size = Formatter.formatShortFileSize(requireContext(), bytes[i])
                    + " · " + getResources().getQuantityString(
                            R.plurals.settings_cloud_backup_file_count, files[i], files[i]);
            ((TextView) row.findViewById(R.id.storage_legend_size)).setText(size);
            if (type.chipId != View.NO_ID) {
                row.setBackgroundResource(resolveSelectableBackground());
                row.setOnClickListener(v -> returnToDownloads(type.chipId, false));
            }
            mLegend.addView(row);
        }
    }

    /** Mime → type, with the Downloads chips' exact predicates. */
    private static Type classify(@Nullable String mime) {
        if (TextUtils.isEmpty(mime)) {
            return Type.OTHER;
        }
        if (FileUriHelper.isVideo(mime)) {
            return Type.VIDEO;
        }
        if (FileUriHelper.isAudio(mime)) {
            return Type.AUDIO;
        }
        if (FileUriHelper.isImage(mime) && !FileUriHelper.isGIF(mime)) {
            return Type.IMAGES;
        }
        if (FileUriHelper.isDoc(mime) && !FileUriHelper.isSubtitle(mime)) {
            return Type.DOCS;
        }
        return Type.OTHER;
    }

    /** Free bytes on the volume the downloads folder lives on; -1 unknown. */
    private long freeBytes() {
        try {
            StatFs stat = new StatFs(StoragePaths.getDownloadPath(requireContext()));
            return stat.getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private int resolveSelectableBackground() {
        TypedValue value = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    // ── Free up space ──────────────────────────────────────────────────

    private void refreshOffload() {
        if (!mCloudBackup.isSetUp()) {
            showOffload(new ArrayList<>());
            return;
        }
        final int gen = ++mOffloadGen;
        mCloudBackup.loadBackedUpKeys(keys ->
                scanCandidates(keys, gen, this::showOffload));
    }

    /** Off-main: backed-up downloads whose file is on disk; result posted to
     *  the main thread only while {@code gen} is still current. */
    private void scanCandidates(Set<String> keys, int gen,
                                Consumer<List<DownloadEntity>> onResult) {
        if (gen != mOffloadGen) {
            return;
        }
        if (keys.isEmpty()) {
            onResult.accept(new ArrayList<>());
            return;
        }
        mHeavyExecutor.execute(() -> {
            List<DownloadEntity> found = new ArrayList<>();
            for (DownloadEntity e : mRepository.getRegularFinishedSync()) {
                if (!keys.contains(CloudBackupManager.contentKey(e.getFileName(), e.getFileSize()))) {
                    continue;
                }
                String path = e.getFilePath();
                if (path == null) {
                    continue;
                }
                File file = new File(path);
                if (file.isFile() && file.length() > 0) {
                    found.add(e);
                }
            }
            mMainHandler.post(() -> {
                if (gen == mOffloadGen) {
                    onResult.accept(found);
                }
            });
        });
    }

    private void showOffload(List<DownloadEntity> candidates) {
        mOffloadCandidates.clear();
        mOffloadCandidates.addAll(candidates);
        mOffloadBytes = 0;
        for (DownloadEntity e : candidates) {
            mOffloadBytes += Math.max(0, e.getFileSize());
        }
        if (mOffloadCard == null) {
            return;
        }
        if (candidates.isEmpty() || mOffloadBytes <= 0) {
            mOffloadCard.setVisibility(View.GONE);
            return;
        }
        String size = Formatter.formatShortFileSize(requireContext(), mOffloadBytes);
        int n = candidates.size();
        mOffloadSummary.setText(getResources().getQuantityString(
                R.plurals.storage_offload_summary, n, n, size));
        mOffloadButton.setText(getString(R.string.storage_offload_button, size));
        mOffloadButton.setEnabled(true);
        mOffloadCard.setVisibility(View.VISIBLE);
    }

    private void confirmOffload() {
        if (mOffloadCandidates.isEmpty()) {
            return;
        }
        final List<DownloadEntity> confirmed = new ArrayList<>(mOffloadCandidates);
        int n = confirmed.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getResources().getQuantityString(
                        R.plurals.storage_offload_confirm_title, n, n))
                .setMessage(R.string.storage_offload_confirm_message)
                .setPositiveButton(R.string.storage_offload_confirm_positive,
                        (d, w) -> performOffload(confirmed))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void performOffload(List<DownloadEntity> confirmed) {
        if (mOffloadButton != null) {
            mOffloadButton.setEnabled(false);
        }
        final Context app = requireContext().getApplicationContext();
        final int gen = ++mOffloadGen;
        // Re-verify against a FRESH manifest: only what the user confirmed AND
        // is still backed up right now is removed. An unreachable manifest
        // yields an empty key set, which deletes nothing.
        mCloudBackup.loadBackedUpKeys(keys -> scanCandidates(keys, gen, fresh -> {
            Set<Integer> stillBacked = new HashSet<>();
            for (DownloadEntity e : fresh) {
                stillBacked.add(e.getId());
            }
            ArrayList<DownloadEntity> toDelete = new ArrayList<>();
            long bytes = 0;
            for (DownloadEntity e : confirmed) {
                if (stillBacked.contains(e.getId())) {
                    toDelete.add(e);
                    bytes += Math.max(0, e.getFileSize());
                }
            }
            if (toDelete.isEmpty()) {
                showOffload(fresh);
                snack(getString(R.string.storage_offload_unverified), false);
                return;
            }
            for (int i = 0; i < toDelete.size(); i += DELETE_CHUNK) {
                ArrayList<DownloadEntity> chunk = new ArrayList<>(
                        toDelete.subList(i, Math.min(i + DELETE_CHUNK, toDelete.size())));
                mTaskRepository.requestDelete(app, chunk);
            }
            showOffload(new ArrayList<>());
            snack(getString(R.string.storage_offload_done,
                    Formatter.formatShortFileSize(app, bytes)), true);
        }));
    }

    private void snack(String text, boolean withBackupsAction) {
        View root = getView();
        if (root == null) {
            return;
        }
        Snackbar bar = makeSnackbar(root, text, false);
        if (withBackupsAction) {
            bar.setAction(R.string.cloud_backup_files_title, v -> {
                Intent intent = new Intent(requireContext(), SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES, true);
                startActivity(intent);
            });
        }
        bar.show();
    }

    /** Largest files first, across every type. */
    private void reviewStorage() {
        mSorting.saveCurrentSortingLocal(Sorting.SORT_SIZE);
        returnToDownloads(View.NO_ID, true);
    }

    private void returnToDownloads(int chipId, boolean sortBySize) {
        NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
        if (previous != null) {
            previous.getSavedStateHandle().set(IntentActions.STORAGE_FILTER, chipId);
            if (sortBySize) {
                OptionEntity option = new OptionEntity();
                option.setId(Sorting.SORT_SIZE);
                previous.getSavedStateHandle().set(IntentActions.DOWNLOAD_SORT, option);
            }
        }
        NavigationUtils.popBackStackSafe(mNavController, R.id.storage);
    }
}
