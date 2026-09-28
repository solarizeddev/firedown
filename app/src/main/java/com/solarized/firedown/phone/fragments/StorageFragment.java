package com.solarized.firedown.phone.fragments;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.StatFs;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.entity.MimeUsageEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.ui.StorageBarView;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.List;

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
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mToolbar.setNavigationOnClickListener(v ->
                NavigationUtils.popBackStackSafe(mNavController, R.id.storage));

        view.findViewById(R.id.storage_review).setOnClickListener(v -> reviewStorage());

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
    public void onDestroyView() {
        super.onDestroyView();
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
