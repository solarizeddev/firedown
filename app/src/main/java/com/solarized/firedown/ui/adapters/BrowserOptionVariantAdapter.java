package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.data.capture.CaptureFacts;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.Utils;

import java.util.ArrayList;
import java.util.List;


public class BrowserOptionVariantAdapter extends RecyclerView.Adapter<BrowserOptionVariantAdapter.VariantHolder> {

    private static final String PAYLOAD_SELECTION = "selection";

    private final ArrayList<FFmpegEntity> mVariants;
    private final OnItemClickListener mOnItemClickListener;
    /** The capture's duration (µs): with a rendition's bitrate, its size. */
    private final long mDurationUs;
    /** The ladder mixes video codec families, so a tile names its own. */
    private final boolean mMixedCodecs;
    private int mSelectedPosition;


    public BrowserOptionVariantAdapter(ArrayList<FFmpegEntity> variants, long durationUs,
                                       OnItemClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
        variants.sort(FFmpegUtils.FFmpegEntityComparator);
        mVariants = new ArrayList<>(variants);
        mDurationUs = durationUs;
        mMixedCodecs = CaptureFacts.mixedVideoCodecs(mVariants);
        mSelectedPosition = 0;
        if (BuildConfig.DEBUG) {
            for (int i = 0; i < mVariants.size(); i++) {
                Log.d("VariantAdapter", "pos=" + i + " info=" + mVariants.get(i).getInfo()
                        + " videoNum=" + mVariants.get(i).getVideoStreamNumber()
                        + " audioNum=" + mVariants.get(i).getAudioStreamNumber()
                        + " bytes=" + mVariants.get(i).getByteSize()
                        + " bitrate=" + mVariants.get(i).getBitrate());
            }
        }
    }

    @NonNull
    @Override
    public VariantHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.fragment_dialog_browser_options_item_variant, parent, false);
        return new VariantHolder(view, mOnItemClickListener, this);
    }

    @Override
    public void onBindViewHolder(@NonNull VariantHolder holder, int position) {
        FFmpegEntity entity = mVariants.get(position);
        boolean selected = mSelectedPosition == position;

        holder.bindTitle(entity);
        holder.bindMeta(entity);
        holder.bindSelection(selected);
    }

    @Override
    public void onBindViewHolder(@NonNull VariantHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        for (Object payload : payloads) {
            if (PAYLOAD_SELECTION.equals(payload)) {
                holder.bindSelection(mSelectedPosition == position);
            }
        }
    }

    @Override
    public int getItemCount() {
        return mVariants.size();
    }


    public FFmpegEntity getSelectedStream() {
        return mVariants.get(mSelectedPosition);
    }

    public int getSelectedPosition() {
        return mSelectedPosition;
    }


    public void setSelected(int position) {
        if (position == mSelectedPosition) return;
        int previous = mSelectedPosition;
        mSelectedPosition = position;
        notifyItemChanged(previous, PAYLOAD_SELECTION);
        notifyItemChanged(position, PAYLOAD_SELECTION);
    }


    public static class VariantHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final OnItemClickListener mOnItemClickListener;
        private final BrowserOptionVariantAdapter mAdapter;
        private final TextView streamTitle;
        private final TextView streamInfo;

        VariantHolder(View view, OnItemClickListener onItemClickListener,
                      BrowserOptionVariantAdapter adapter) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            mAdapter = adapter;
            View item = view.findViewById(R.id.file_variants_item);
            streamTitle = view.findViewById(R.id.stream_title);
            streamInfo = view.findViewById(R.id.stream_info);
            item.setOnClickListener(this);
        }


        /**
         * Tile title: the COMPACT quality label — "1080p (1920 x 1080)" shows
         * as "1080p"; the full description rides in the contentDescription so
         * TalkBack loses nothing. A description with no parenthetical (a
         * generic capture's "1920 x 1080") shows as-is.
         */
        void bindTitle(FFmpegEntity entity) {
            String description = entity.getStreamDescription();
            if ((description == null || description.isEmpty()) && entity.isAudioOnly()) {
                description = itemView.getContext().getString(R.string.stream_audio_only_title);
            }
            if (description == null) {
                description = "";
            }
            String compact = description;
            int paren = compact.indexOf(" (");
            if (paren > 0) {
                compact = compact.substring(0, paren);
            }
            streamTitle.setText(compact);
            itemView.setContentDescription(description);
        }


        /**
         * Tile sub-label: the ONE fact that varies tile-to-tile and decides
         * the pick — the rendition's SIZE ("45 MB", or "~45 MB" when it is
         * bitrate × duration; CaptureFacts.variantSize). Beside it the video
         * codec family when the ladder mixes them (YouTube's AV1 rungs above
         * 1080p); one family everywhere is boilerplate. With no size the
         * codec pair stands in (near-identical type boilerplate — the old
         * "video + audio · " prefix — is dropped, same reasoning that deleted
         * the per-row filled chip before it). An audio-only or video-only
         * rendition says THAT before anything else, since it is the
         * decision-relevant deviation. Hidden entirely when there is nothing
         * to say.
         */
        void bindMeta(FFmpegEntity entity) {
            Context context = itemView.getContext();
            String codec = entity.getCodecLabel();
            CaptureFacts.Size size = CaptureFacts.variantSize(entity, mAdapter.mDurationUs);
            String sizeText = null;
            if (size != null) {
                String bytes = Utils.getFileSize(size.bytes);
                sizeText = size.estimate ? context.getString(R.string.capture_size_estimate, bytes) : bytes;
            }
            String meta;
            if (entity.isAudioOnly()) {
                meta = join(context.getString(R.string.stream_type_audio), sizeText);
            } else if (entity.isVideoOnly()) {
                meta = join(context.getString(R.string.stream_type_video), sizeText);
            } else if (sizeText != null) {
                meta = mAdapter.mMixedCodecs
                        ? join(sizeText, CaptureFacts.codecFamily(entity.getVideoCodec()))
                        : sizeText;
            } else {
                meta = codec != null ? compactCodecs(codec) : "";
            }
            if (meta.isEmpty()) {
                streamInfo.setVisibility(View.GONE);
            } else {
                streamInfo.setVisibility(View.VISIBLE);
                streamInfo.setText(meta);
            }
        }


        void bindSelection(boolean selected) {
            // The tile's fill/ink selectors key on ACTIVATED — no radio.
            itemView.setActivated(selected);
        }

        /**
         * Drops codec PARAMETER tails for the tile sub-label: SABR labels
         * arrive as "avc1.640028 / mp4a.40.2", which ellipsized mid-token on
         * a third-width tile ("mp4a…"). The FAMILY is the fact a user can
         * act on; the profile/level digits are noise at 10sp. "av01.0.08M.08"
         * → "av01", "avc1.640028 / mp4a.40.2" → "avc1 / mp4a".
         */
        private static String compactCodecs(String codec) {
            return codec.replaceAll("\\.[0-9A-Za-z.]+", "");
        }

        private static String join(String a, String b) {
            if (b == null || b.isEmpty()) return a;
            if (a == null || a.isEmpty()) return b;
            return a + " · " + b;
        }


        @Override
        public void onClick(View v) {
            int position = getAbsoluteAdapterPosition();
            if (position != RecyclerView.NO_POSITION && mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(position, v.getId());
            }
        }
    }
}