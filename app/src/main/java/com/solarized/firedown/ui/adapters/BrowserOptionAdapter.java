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
import com.solarized.firedown.utils.SelectionStyling;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


public class BrowserOptionAdapter extends GridListBaseAdapter<BrowserDownloadEntity, BrowserOptionAdapter.ViewHolder> {

    private static final String TAG = BrowserOptionAdapter.class.getName();

    private final OnItemClickListener mOnItemClickListener;

    private final HashSet<Integer> mSelected = new HashSet<>();

    private final RequestOptions mRequestOptions;

    private boolean mActionMode;

    /** origin → number of subtitle tracks captured for that video. Supplied by
     *  the fragment from the unfiltered repository so the CC badge is correct
     *  regardless of the active chip filter. Empty until set. */
    private Map<String, Integer> mSubtitleCounts = Collections.emptyMap();

    /** When true, the mime chip is hidden — set while a single-type filter
     *  chip is active (e.g. the Images view), where stamping "IMAGE" on every
     *  tile is pure redundancy with the chip rail. */
    private boolean mSuppressMime;


    public BrowserOptionAdapter(Context context, @NonNull DiffUtil.ItemCallback<BrowserDownloadEntity> diffCallback,
                                OnItemClickListener onItemClickListener, boolean list) {
        super(diffCallback);
        mOnItemClickListener = onItemClickListener;
        mList = list;
        RoundedCorners roundedCorners = new RoundedCorners(
                context.getResources().getDimensionPixelOffset(R.dimen.card_radius));
        mRequestOptions = new RequestOptions().apply(RequestOptions.bitmapTransform(roundedCorners));
    }


    /**
     * Supply the origin → subtitle-count map. Call before/alongside
     * {@link #submitList} so the CC badge binds with fresh counts. A null
     * argument clears the map.
     */
    public void setSubtitleCounts(@Nullable Map<String, Integer> counts) {
        mSubtitleCounts = counts != null ? counts : Collections.emptyMap();
    }

    /**
     * SILENT combined setter for the filter-driven presentation flags
     * (mime suppression + dense mosaic). Used by the downloads observer so
     * the flags flip together with the NEW list's commit rather than
     * instantly: the chip tap's requery is async, and a notifying setter
     * fired from the tap rebinds the OLD list in the new presentation
     * first (the images briefly re-render as normal span-2 tiles before
     * the videos arrive). The caller owns the rebind — when this returns
     * true, submit the new list with a commit callback that updates the
     * span count and calls notifyDataSetChanged, so items that survived
     * the diff take the new presentation too.
     *
     * @return whether either flag actually changed.
     */
    public boolean setPresentation(boolean suppressMime, boolean dense) {
        boolean changed = mSuppressMime != suppressMime || mDense != dense;
        mSuppressMime = suppressMime;
        mDense = dense;
        return changed;
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
        int layoutRes;
        if (viewType == TYPE_LIST) {
            layoutRes = R.layout.fragment_browser_options_item_list;
        } else if (viewType == TYPE_GRID_DENSE) {
            // Images-only filter active: square bare tile (thumbnail +
            // selection checkmark only) — see the layout's header comment.
            layoutRes = R.layout.fragment_browser_options_item_dense;
        } else {
            layoutRes = R.layout.fragment_browser_options_item;
        }
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
        // List mirrors Downloads/Bookmarks/History: a tonal WASH on the card
        // root (no stroke, no row reflow) plus the check overlaid in the
        // more-button's slot (swapped for the button in action mode — see the
        // more-button handling below). Grid/dense keep their corner check +
        // 2dp stroke: their tiles have no more-button slot to borrow and a
        // full-tile wash would fight the thumbnail.
        int colorSelected = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT);
        boolean washSelected = mActionMode && selected;

        if (holder.isList) {
            // Wash layered over surfaceContainerLow (this row's resting bg,
            // unlike Downloads' transparent card) so the selected tint reads
            // the same against the bottom sheet.
            holder.item.setCardBackgroundColor(washSelected
                    ? SelectionStyling.selectedCardWashOver(context,
                            com.google.android.material.R.attr.colorSurfaceContainerLow)
                    : MaterialColors.getColor(context,
                            com.google.android.material.R.attr.colorSurfaceContainerLow,
                            Color.TRANSPARENT));
        } else {
            holder.item.setStrokeColor(washSelected ? colorSelected : Color.TRANSPARENT);
        }
        holder.checkedView.setVisibility(mActionMode ? View.VISIBLE : View.GONE);

        if (mActionMode) {
            holder.checkedView.setImageDrawable(selected
                    ? Utils.tintDrawableColor(context, R.drawable.ic_baseline_check_circle_24, colorSelected)
                    : Utils.tintDrawableColor(context, R.drawable.radio_button_unchecked_24, colorSelected));
        } else {
            holder.checkedView.setImageDrawable(null);
        }

        // ── Common bindings ──────────────────────────────────────────────
        // List mode renders mime as a text label that prefixes the
        // domain ('VÍDEO · m.youtube.com'); grid renders it as a filled
        // chip in the bottom metadata row. Hide the view entirely if the
        // mime resolves to empty so the row doesn't render a stray ' · '.
        // The dense tile carries no mime view at all (null-guarded — the
        // active images filter already states the type).
        if (holder.mimeText != null) {
            String mimeLabel = FileUriHelper.getLongMimeText(context, entity.getMimeType());
            if (mSuppressMime || TextUtils.isEmpty(mimeLabel)) {
                // Suppressed while a single-type filter is active (chip rail
                // already states the type), or when the mime resolves to empty.
                holder.mimeText.setVisibility(View.GONE);
            } else {
                holder.mimeText.setVisibility(View.VISIBLE);
                holder.mimeText.setText(holder.isList ? mimeLabel + " · " : mimeLabel);
            }
        }

        RequestOptions options = mRequestOptions
                .set(GlideRequestOptions.MIMETYPE, mimeType)
                .set(GlideRequestOptions.FILEPATH, fileUrl)
                .set(GlideRequestOptions.HEADERS, entity.getFileHeaders())
                .set(GlideRequestOptions.KEY, key);
        GlideHelper.load(entity, options, holder.image);

        // ── Tags ─────────────────────────────────────────────────────────
        bindTags(context, holder, entity);

        // Title: list shows it as the primary row text; grid as a one-line
        // overlay in the bottom scrim. In the GRID, hide it for self-identifying
        // image tiles (incl. GIF/SVG) — same rule as Downloads: the thumbnail IS
        // the content and the name is almost always a junk slug, so the title is
        // just ink over the picture. Everything else (audio/video/docs/subs) and
        // the list always keep it; a non-empty real title in a space-less script
        // is still shown (we don't junk-test the name itself).
        if (!holder.isList && FileUriHelper.isImage(mimeType)) {
            holder.setTextOrHide(holder.fileName, null);
        } else {
            holder.setTextOrHide(holder.fileName, entity.getFileName());
        }

        // ── Layout-specific bindings ─────────────────────────────────────
        // The dense tile has no variants/more button (null-guarded): images
        // carry no variants, and the per-item affordances live behind tap
        // (download) and long-press (selection).
        if (holder.isList) {
            holder.setTextOrHide(holder.fileUrl, domain);
            if (holder.more != null) {
                // In action mode the check overlays the more-button slot, so
                // keep the button present-but-INVISIBLE: it holds the slot
                // width so the check lands in place and the row doesn't
                // reflow (mirrors fragment_download_item's action-button swap).
                // Outside action mode it shows only when the entity has
                // selectable variants.
                holder.more.setVisibility(mActionMode ? View.INVISIBLE
                        : (hasVariants ? View.VISIBLE : View.GONE));
            }
        } else if (holder.more != null) {
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
     * the same tag_quality / tag_duration IDs. All tag labels are the tag's
     * pre-resolved persisted text.
     */
    private void bindTags(@NonNull Context context,
                          @NonNull ViewHolder holder,
                          @NonNull BrowserDownloadEntity entity) {
        // tagDuration is the one slot present in both layouts; tagQuality is
        // omitted from the grid tile (no room beside the title), so it's
        // null-checked everywhere below rather than required up front.
        if (holder.tagDuration == null) return;

        // Reset
        if (holder.tagQuality != null) {
            holder.tagQuality.setVisibility(View.GONE);
        }
        holder.tagDuration.setVisibility(View.GONE);
        if (holder.tagSeparator != null) {
            holder.tagSeparator.setVisibility(View.GONE);
        }
        if (holder.tagCc != null) {
            holder.tagCc.setVisibility(View.GONE);
        }
        if (holder.tagCcSeparator != null) {
            holder.tagCcSeparator.setVisibility(View.GONE);
        }

        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags != null && !tags.isEmpty()) {
            for (FFmpegTagEntity tag : tags) {
                bindSingleTag(context, holder, tag);
            }

            // Show separator only when both slots are visible
            if (holder.tagSeparator != null
                    && holder.tagQuality != null
                    && holder.tagQuality.getVisibility() == View.VISIBLE
                    && holder.tagDuration.getVisibility() == View.VISIBLE) {
                holder.tagSeparator.setVisibility(View.VISIBLE);
            }
        }

        bindCaptionBadge(context, holder, entity);
    }

    /**
     * Shows a "CC N" badge on a video row when the parser captured subtitle
     * tracks for that video. Count comes from {@link #mSubtitleCounts} (built
     * from the unfiltered repository), keyed by origin. The badge must land on
     * the VIDEO only: captions belong to a video, and the count is keyed on the
     * page origin, so every other capture from the same page (thumbnail-sprite
     * images, a player-logo SVG, poster images, audio) shares that origin and
     * would otherwise wrongly inherit the badge — the "CC 1 on a PNG / SVG" bug.
     */
    private void bindCaptionBadge(@NonNull Context context,
                                  @NonNull ViewHolder holder,
                                  @NonNull BrowserDownloadEntity entity) {
        if (holder.tagCc == null) return;
        // Captions attach to a video; gate strictly on the video mime so a
        // same-origin image/SVG/audio capture never inherits the count.
        if (!FileUriHelper.isVideo(entity.getMimeType())) return;

        String origin = entity.getFileOrigin();
        if (TextUtils.isEmpty(origin)) return;

        Integer count = mSubtitleCounts.get(origin);
        if (count == null || count <= 0) return;

        holder.tagCc.setText(context.getString(R.string.caption_count_badge, count));
        holder.tagCc.setVisibility(View.VISIBLE);

        // List layout separates tags with a "·"; show the CC separator only
        // when a quality/duration tag precedes it. Grid has no separator view.
        if (holder.tagCcSeparator != null
                && ((holder.tagQuality != null && holder.tagQuality.getVisibility() == View.VISIBLE)
                    || holder.tagDuration.getVisibility() == View.VISIBLE)) {
            holder.tagCcSeparator.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Renders a single tag into the appropriate view slot based on its type.
     * All types use the tag's persisted text.
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

            case FFmpegTagEntity.TYPE_QUALITY:
                // Video quality (e.g. "1080p") — shown in BOTH layouts. In the
                // grid it leads the meta row as "[mime] quality · duration"
                // (the separator is bound only when duration is also present).
                if (holder.tagQuality != null && !TextUtils.isEmpty(tag.getText())) {
                    holder.tagQuality.setText(tag.getText());
                    holder.tagQuality.setVisibility(View.VISIBLE);
                }
                break;

            case FFmpegTagEntity.TYPE_RESOLUTION:
                // Image dimensions (e.g. "320x180") — useful at a glance and
                // compact, so shown in both layouts.
                if (holder.tagQuality != null && !TextUtils.isEmpty(tag.getText())) {
                    holder.tagQuality.setText(tag.getText());
                    holder.tagQuality.setVisibility(View.VISIBLE);
                }
                break;

            case FFmpegTagEntity.TYPE_LANGUAGE:
                // Subtitle language (e.g. "English", "English (auto)"). Subtitle
                // rows carry no quality/duration, so the quality slot is free;
                // shown in both layouts so the language is visible at a glance.
                if (holder.tagQuality != null && !TextUtils.isEmpty(tag.getText())) {
                    holder.tagQuality.setText(tag.getText());
                    holder.tagQuality.setVisibility(View.VISIBLE);
                }
                break;

            case FFmpegTagEntity.TYPE_UNKNOWN:
            default:
                // Graceful fallback: render in the quality slot if still empty.
                // Iteration order matters — a TYPE_UNKNOWN tag processed before
                // a TYPE_QUALITY tag will be overwritten by it (desired).
                if (holder.tagQuality != null
                        && !TextUtils.isEmpty(tag.getText())
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

    /**
     * Falls back to extension-based detection when the entity's stored
     * mime type is missing or generic. Public so the RecyclerView
     * preloader in BrowserOptionFragment can compute the same RequestOptions
     * key the bind path uses — otherwise the preload-warmed bitmap and
     * the bind-time request resolve to different cache keys.
     */
    public static String resolveMimeType(@Nullable String mimeType, String fileUrl) {
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

        // Common views (present in the list and grid layouts; the DENSE
        // grid tile is thumbnail + checkmark only, so everything textual
        // and the more button are null there)
        final MaterialCardView item;
        final AppCompatImageView image;
        final AppCompatImageView checkedView;
        @Nullable final TextView mimeText;
        @Nullable final MaterialButton more;
        @Nullable final TextView tagQuality;
        @Nullable final TextView tagDuration;
        @Nullable final View tagSeparator;
        @Nullable final TextView tagCc;
        @Nullable final View tagCcSeparator;

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
            tagCc = view.findViewById(R.id.tag_cc);
            tagCcSeparator = view.findViewById(R.id.tag_cc_separator);

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
            if (TextUtils.isEmpty(text)) {
                tv.setVisibility(View.GONE);
                return;
            }
            tv.setVisibility(View.VISIBLE);
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