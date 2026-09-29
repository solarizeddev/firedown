package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.ui.CloudMark;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.SelectionStyling;
import com.solarized.firedown.utils.Utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Rows of the Storage REVIEW list (StorageReviewFragment): finished, non-vault
 * downloads, largest first, ALWAYS in selection mode.
 *
 * <p>Its own row ({@code item_storage_review}, the Files-by-Google "Large
 * files" shape): a real CHECKBOX on the left, the thumbnail and name /
 * {@code MIME · domain} / facts column, and the SIZE right-aligned at title
 * weight. It shares the Downloads row's parts — the thumbnail path
 * ({@link GlideHelper#load}), the domain label
 * ({@link DownloadItemAdapter#domainLabel}), the inline cloud mark
 * ({@link CloudMark}), the {@link SelectionStyling} wash — but it is NOT the
 * Downloads adapter: that one is a paging adapter with section headers, five
 * status states, grid/dense layouts and a fragment round-trip per click.
 * This list has one state (finished), one presentation and one job (tick,
 * then delete), so it is a plain {@code ListAdapter} with its own small
 * selection set.
 *
 * <p><b>Selection is the resting state.</b> The checkbox is there from the
 * first frame, a tap anywhere on the row toggles it. It shipped first as the
 * Downloads row with the empty check RING in the ⋮ slot — which read as a
 * radio button (single choice), the opposite of what this screen asks — and
 * with the size as the first token of the 11sp facts line, which read as a
 * Downloads list rather than a size list. Every comparable review screen
 * (Files, iOS "Review Large Attachments", Signal's tile badge) makes the size
 * the row's loudest number; so does this one now.
 */
public class StorageReviewAdapter extends ListAdapter<DownloadEntity, StorageReviewAdapter.Holder> {

    private static final DiffUtil.ItemCallback<DownloadEntity> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull DownloadEntity a, @NonNull DownloadEntity b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull DownloadEntity a, @NonNull DownloadEntity b) {
                    return a.getFileSize() == b.getFileSize()
                            && a.getFileDate() == b.getFileDate()
                            && TextUtils.equals(a.getFileName(), b.getFileName());
                }
            };

    /** Payload for a selection-only rebind: no thumbnail reload, no text. */
    private static final Object PAYLOAD_SELECTION = new Object();

    private final Context mContext;
    private final Consumer<Set<Integer>> mOnSelectionChanged;
    private final RequestOptions mRequestOptions = new RequestOptions();
    private final CloudMark mCloudMark;
    private final int mDefaultBg;
    private final int mSelectedBg;

    private final Set<Integer> mSelected = new HashSet<>();
    @NonNull private Set<String> mBackedUpKeys = Collections.emptySet();

    public StorageReviewAdapter(@NonNull Context context,
                                @NonNull Consumer<Set<Integer>> onSelectionChanged) {
        super(DIFF);
        mContext = context;
        mOnSelectionChanged = onSelectionChanged;
        mCloudMark = new CloudMark(context);
        // Transparent at rest (the page already paints colorSurface); the wash
        // layers primaryContainer over that same surface (the Downloads row).
        mDefaultBg = Color.TRANSPARENT;
        mSelectedBg = SelectionStyling.selectedCardWashOver(context,
                com.google.android.material.R.attr.colorSurface);
    }

    /** Content keys of the files in the cloud backup — drives the inline mark. */
    public void setBackedUpKeys(@NonNull Set<String> keys) {
        if (mBackedUpKeys.equals(keys)) {
            return;
        }
        mBackedUpKeys = keys;
        notifyItemRangeChanged(0, getItemCount());
    }

    @NonNull
    public Set<Integer> getSelectedIds() {
        return Collections.unmodifiableSet(mSelected);
    }

    public boolean isSelected(@NonNull DownloadEntity entity) {
        return mSelected.contains(entity.getId());
    }

    public boolean isBackedUp(@NonNull DownloadEntity entity) {
        if (mBackedUpKeys.isEmpty() || entity.isFileSafe()) {
            return false;
        }
        return mBackedUpKeys.contains(
                CloudBackupManager.contentKey(entity.getFileName(), entity.getFileSize()));
    }

    /** Select every row, or clear when every row is already selected. */
    public void toggleSelectAll() {
        int n = getItemCount();
        if (n == 0) {
            return;
        }
        boolean all = mSelected.size() == n;
        mSelected.clear();
        if (!all) {
            for (int i = 0; i < n; i++) {
                mSelected.add(getItem(i).getId());
            }
        }
        notifyItemRangeChanged(0, n, PAYLOAD_SELECTION);
        mOnSelectionChanged.accept(getSelectedIds());
    }

    public void clearSelection() {
        if (mSelected.isEmpty()) {
            return;
        }
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        mOnSelectionChanged.accept(getSelectedIds());
    }

    /** Drop ids that are no longer in the list (after a delete). */
    public void retainSelection(@NonNull Set<Integer> liveIds) {
        if (mSelected.retainAll(liveIds)) {
            mOnSelectionChanged.accept(getSelectedIds());
        }
    }

    private void toggle(int position) {
        if (position == RecyclerView.NO_POSITION || position >= getItemCount()) {
            return;
        }
        int id = getItem(position).getId();
        if (!mSelected.remove(id)) {
            mSelected.add(id);
        }
        notifyItemChanged(position, PAYLOAD_SELECTION);
        mOnSelectionChanged.accept(getSelectedIds());
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_storage_review, parent, false);
        Holder holder = new Holder(view);
        holder.item.setOnClickListener(v -> toggle(holder.getBindingAdapterPosition()));
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            bindSelection(holder, getItem(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        DownloadEntity entity = getItem(position);
        bindSelection(holder, entity);

        String name = entity.getFileName();
        holder.fileName.setText(name);
        holder.fileName.setVisibility(TextUtils.isEmpty(name) ? View.GONE : View.VISIBLE);

        String mime = entity.getFileMimeType();
        String mimeLabel = FileUriHelper.getLongMimeText(mContext, mime);
        holder.mimeText.setText(mimeLabel == null ? "" : mimeLabel + " · ");
        holder.mimeText.setVisibility(mimeLabel == null ? View.GONE : View.VISIBLE);
        String domain = DownloadItemAdapter.domainLabel(mContext, entity);
        holder.fileUrl.setText(domain);
        holder.fileUrl.setVisibility(TextUtils.isEmpty(domain) ? View.GONE : View.VISIBLE);

        String facts = facts(entity, mime);
        holder.statusText.setTextColor(MaterialColors.getColor(holder.statusText,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        if (isBackedUp(entity)) {
            holder.statusText.setText(mCloudMark.leading(holder.statusText, facts));
            // The span is invisible to TalkBack; say the state out loud.
            holder.statusText.setContentDescription(
                    mContext.getString(R.string.cloud_backed_up_desc) + ", " + facts);
        } else {
            holder.statusText.setText(facts);
            holder.statusText.setContentDescription(null);
        }
        holder.size.setText(Utils.getFileSize(entity.getFileSize()));

        GlideHelper.load(entity, mRequestOptions, holder.image);
    }

    private void bindSelection(Holder holder, DownloadEntity entity) {
        boolean on = mSelected.contains(entity.getId());
        holder.check.setChecked(on);
        holder.item.setCardBackgroundColor(on ? mSelectedBg : mDefaultBg);
        holder.item.setContentDescription(mContext.getString(
                on ? R.string.storage_review_row_selected : R.string.storage_review_row_unselected,
                entity.getFileName()));
    }

    /** '{@code 3:51 · 20 May 2026}' — the size has its own column. */
    private static String facts(DownloadEntity entity, @Nullable String mime) {
        StringBuilder label = new StringBuilder();
        String secondary;
        if (FileUriHelper.isVideo(mime) || FileUriHelper.isAudio(mime)) {
            secondary = DateUtils.compactDuration(entity.getDurationFormatted());
        } else if (FileUriHelper.isImage(mime) || FileUriHelper.isSVG(mime)) {
            secondary = entity.getFileResolution();
        } else {
            secondary = entity.getFileLanguage();
        }
        if (!TextUtils.isEmpty(secondary)) {
            label.append(secondary).append(" · ");
        }
        label.append(DateUtils.getFileDate(entity.getFileDate()));
        return label.toString();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView item;
        final MaterialCheckBox check;
        final AppCompatImageView image;
        final TextView fileName;
        final TextView mimeText;
        final TextView fileUrl;
        final TextView statusText;
        final TextView size;

        Holder(@NonNull View view) {
            super(view);
            item = view.findViewById(R.id.item);
            check = view.findViewById(R.id.review_check);
            image = view.findViewById(R.id.image);
            fileName = view.findViewById(R.id.file_name);
            mimeText = view.findViewById(R.id.mime_text);
            fileUrl = view.findViewById(R.id.file_url);
            statusText = view.findViewById(R.id.status_text);
            size = view.findViewById(R.id.review_size);
            image.setClipToOutline(true);
        }
    }
}
