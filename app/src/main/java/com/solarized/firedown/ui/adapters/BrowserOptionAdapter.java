package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
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
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.FFmpegTagEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;
import java.util.List;


public class BrowserOptionAdapter extends GridListBaseAdapter<BrowserDownloadEntity, BrowserOptionAdapter.ViewHolder> {

    private static final String TAG = BrowserOptionAdapter.class.getName();

    private final OnItemClickListener mOnItemClickListener;

    private final HashSet<Integer> mSelected = new HashSet<>();

    private final RequestOptions mRequestOptions;

    private boolean mActionMode;


    public BrowserOptionAdapter(Context context, @NonNull DiffUtil.ItemCallback<BrowserDownloadEntity> diffCallback,
                                OnItemClickListener onItemClickListener, boolean list) {
        super(diffCallback);
        mOnItemClickListener = onItemClickListener;
        mList = list;
        RoundedCorners roundedCorners = new RoundedCorners(
                context.getResources().getDimensionPixelOffset(R.dimen.card_radius));
        mRequestOptions = new RequestOptions().apply(RequestOptions.bitmapTransform(roundedCorners));
    }


    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        GlideHelper.clearSafe(holder.image);
    }

    // ── ViewHolder creation ──────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes = viewType == TYPE_LIST
                ? R.layout.fragment_browser_options_item_list
                : R.layout.fragment_browser_options_item;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view, mOnItemClickListener, viewType == TYPE_LIST);
    }


    // ── Binding ──────────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BrowserDownloadEntity entity = getItem(position);
        Context context = holder.itemView.getContext();

        String fileOrigin = entity.getFileOrigin();
        String fileUrl = entity.getFileUrl();
        String mimeType = resolveMimeType(entity.getMimeType(), fileUrl);
        String domain = WebUtils.getDomainName(TextUtils.isEmpty(fileOrigin) ? fileUrl : fileOrigin);
        String key = String.valueOf(entity.getUid());

        boolean selected = mSelected.contains(entity.getUid());
        boolean hasVariants = entity.getHasVariants();

        // ── Selection state ──────────────────────────────────────────────
        int colorSelected = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT);

        holder.item.setStrokeColor(mActionMode && selected ? colorSelected : Color.TRANSPARENT);
        holder.checkedView.setVisibility(mActionMode ? View.VISIBLE : View.GONE);

        if (mActionMode) {
            holder.checkedView.setImageDrawable(selected
                    ? Utils.tintDrawableColor(context, R.drawable.ic_baseline_check_circle_24, colorSelected)
                    : Utils.tintDrawableColor(context, R.drawable.radio_button_unchecked_24, colorSelected));
        } else {
            holder.checkedView.setImageDrawable(null);
        }

        // ── Common bindings ──────────────────────────────────────────────
        holder.mimeText.setText(FileUriHelper.getLongMimeText(context, entity.getMimeType()));

        RequestOptions options = mRequestOptions
                .set(GlideRequestOptions.MIMETYPE, mimeType)
                .set(GlideRequestOptions.FILEPATH, fileUrl)
                .set(GlideRequestOptions.HEADERS, entity.getFileHeaders())
                .set(GlideRequestOptions.KEY, key);
        GlideHelper.load(entity, options, holder.image);

        // ── Tags ─────────────────────────────────────────────────────────
        bindTags(context, holder, entity);

        // ── Layout-specific bindings ─────────────────────────────────────
        if (holder.isList) {
            holder.setTextOrHide(holder.fileName, entity.getFileName());
            holder.setTextOrHide(holder.fileUrl, domain);
            holder.more.setVisibility(hasVariants ? View.VISIBLE : View.GONE);
        } else {
            int variantVisibility = !mActionMode && hasVariants ? View.VISIBLE : View.GONE;
            holder.more.setEnabled(!mActionMode);
            holder.more.setVisibility(variantVisibility);
            holder.more.setIconTint(ColorStateList.valueOf(Color.WHITE));
            if (holder.dimView != null) {
                holder.dimView.setVisibility(variantVisibility);
            }
        }
    }


    // ── Tag binding (works for both grid and list) ───────────────────────

    /**
     * Routes each typed tag to the correct static TextView. Both layouts expose
     * the same tag_quality / tag_duration IDs.
     *
     * <p>Label resolution happens here rather than at inspect time so that
     * locale-dependent labels (currently only {@link FFmpegTagEntity#TYPE_ADAPTIVE})
     * pick up the current configuration instead of whatever was active when
     * the tag was first persisted.
     */
    private void bindTags(@NonNull Context context,
                          @NonNull ViewHolder holder,
                          @NonNull BrowserDownloadEntity entity) {
        if (holder.tagQuality == null || holder.tagDuration == null) return;

        // Reset
        holder.tagQuality.setVisibility(View.GONE);
        holder.tagDuration.setVisibility(View.GONE);
        if (holder.tagSeparator != null) {
            holder.tagSeparator.setVisibility(View.GONE);
        }

        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null || tags.isEmpty()) return;

        for (FFmpegTagEntity tag : tags) {
            bindSingleTag(context, holder, tag);
        }

        // Show separator only when both slots are visible
        if (holder.tagSeparator != null
                && holder.tagQuality.getVisibility() == View.VISIBLE
                && holder.tagDuration.getVisibility() == View.VISIBLE) {
            holder.tagSeparator.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Renders a single tag into the appropriate view slot based on its type.
     * TYPE_ADAPTIVE resolves its label from resources; all other types use the
     * tag's persisted text.
     */
    private void bindSingleTag(@NonNull Context context,
                               @NonNull ViewHolder holder,
                               @NonNull FFmpegTagEntity tag) {
        switch (tag.getType()) {
            case FFmpegTagEntity.TYPE_DURATION:
                if (!TextUtils.isEmpty(tag.getText())) {
                    holder.tagDuration.setText(tag.getText());
                    holder.tagDuration.setVisibility(View.VISIBLE);
                }
                break;

            case FFmpegTagEntity.TYPE_ADAPTIVE:
                // Locale-dependent label — resolved at render time, not stored
                holder.tagQuality.setText(context.getString(R.string.download_adaptative));
                holder.tagQuality.setVisibility(View.VISIBLE);
                break;

            case FFmpegTagEntity.TYPE_QUALITY:
            case FFmpegTagEntity.TYPE_RESOLUTION:
                if (!TextUtils.isEmpty(tag.getText())) {
                    holder.tagQuality.setText(tag.getText());
                    holder.tagQuality.setVisibility(View.VISIBLE);
                }
                break;

            case FFmpegTagEntity.TYPE_UNKNOWN:
            default:
                // Graceful fallback: render in the quality slot if still empty.
                // Iteration order matters — a TYPE_UNKNOWN tag processed before
                // a TYPE_QUALITY tag will be overwritten by it (desired).
                if (!TextUtils.isEmpty(tag.getText())
                        && holder.tagQuality.getVisibility() == View.GONE) {
                    holder.tagQuality.setText(tag.getText());
                    holder.tagQuality.setVisibility(View.VISIBLE);
                }
                break;
        }
    }


    // ── Selection (keyed by UID) ─────────────────────────────────────────

    public void toggleSelected(int position) {
        int uid = getItem(position).getUid();
        if (!mSelected.remove(uid)) {
            mSelected.add(uid);
        }
        notifyItemChanged(position);
    }

    public boolean isSelected(int position) {
        return mSelected.contains(getItem(position).getUid());
    }

    public int getSelectedSize() {
        return mSelected.size();
    }

    public HashSet<Integer> getSelected() {
        return mSelected;
    }

    public void clearSelection() {
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public void selectAll() {
        for (int i = 0; i < getItemCount(); i++) {
            mSelected.add(getItem(i).getUid());
        }
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isSelectionEmpty() {
        return mSelected.isEmpty();
    }

    public void setActionMode(boolean value) {
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount());
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    private static String resolveMimeType(@Nullable String mimeType, String fileUrl) {
        if (TextUtils.isEmpty(mimeType) || FileUriHelper.isMimeTypeUnknown(mimeType)) {
            return FileUriHelper.getMimeTypeFromFile(fileUrl);
        }
        return mimeType;
    }


    // ── Unified ViewHolder ───────────────────────────────────────────────

    public static class ViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener {

        final boolean isList;
        final OnItemClickListener listener;

        // Common views (present in both layouts)
        final MaterialCardView item;
        final AppCompatImageView image;
        final AppCompatImageView checkedView;
        final TextView mimeText;
        final MaterialButton more;
        final TextView tagQuality;
        final TextView tagDuration;
        final View tagSeparator;

        // List-only views (null in grid mode)
        @Nullable final TextView fileName;
        @Nullable final TextView fileUrl;

        // Grid-only views (null in list mode)
        @Nullable final View dimView;


        ViewHolder(View view, OnItemClickListener onItemClickListener, boolean isList) {
            super(view);
            this.isList = isList;
            this.listener = onItemClickListener;

            // Common
            item = view.findViewById(R.id.item);
            image = view.findViewById(R.id.image);
            checkedView = view.findViewById(R.id.item_download_more_checked);
            mimeText = view.findViewById(R.id.mime_text);
            more = view.findViewById(R.id.item_download_more);
            tagQuality = view.findViewById(R.id.tag_quality);
            tagDuration = view.findViewById(R.id.tag_duration);
            tagSeparator = view.findViewById(R.id.tag_separator);

            // List-only
            fileName = view.findViewById(R.id.file_name);
            fileUrl = view.findViewById(R.id.file_url);

            // Grid-only
            dimView = view.findViewById(R.id.dim_view);

            // Image clipping
            image.setClipToOutline(true);

            // Click listeners
            if (more != null) {
                more.setOnClickListener(this);
                Utils.expandTouchArea(more);
            }
            item.setOnClickListener(this);
            item.setOnLongClickListener(this);
        }

        void setTextOrHide(@Nullable TextView tv, @Nullable String text) {
            if (tv == null) return;
            tv.setText(text);
        }

        @Override
        public void onClick(View v) {
            int position = getAbsoluteAdapterPosition();
            if (listener != null && position != RecyclerView.NO_POSITION) {
                listener.onItemClick(position, v.getId());
            }
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getAbsoluteAdapterPosition();
            if (listener != null && position != RecyclerView.NO_POSITION) {
                listener.onLongClick(position, v.getId());
                return true;
            }
            return false;
        }
    }
}