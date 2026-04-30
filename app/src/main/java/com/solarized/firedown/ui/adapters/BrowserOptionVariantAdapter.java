package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.solarized.firedown.R;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.ui.OnItemClickListener;

import java.util.ArrayList;
import java.util.List;


public class BrowserOptionVariantAdapter extends RecyclerView.Adapter<BrowserOptionVariantAdapter.VariantHolder> {

    private static final String PAYLOAD_SELECTION = "selection";

    private final ArrayList<FFmpegEntity> mVariants;
    private final OnItemClickListener mOnItemClickListener;
    private int mSelectedPosition;


    public BrowserOptionVariantAdapter(ArrayList<FFmpegEntity> variants, OnItemClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
        variants.sort(FFmpegUtils.FFmpegEntityComparator);
        mVariants = new ArrayList<>(variants);
        mSelectedPosition = 0;
        for (int i = 0; i < mVariants.size(); i++) {
            Log.d("VariantAdapter", "pos=" + i + " info=" + mVariants.get(i).getInfo()
                    + " videoNum=" + mVariants.get(i).getVideoStreamNumber()
                    + " audioNum=" + mVariants.get(i).getAudioStreamNumber());
        }
    }

    @NonNull
    @Override
    public VariantHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.fragment_dialog_browser_options_item_variant, parent, false);
        return new VariantHolder(view, mOnItemClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull VariantHolder holder, int position) {
        FFmpegEntity entity = mVariants.get(position);
        boolean selected = mSelectedPosition == position;

        holder.bindTitle(entity);
        holder.bindStreamType(entity);
        holder.bindInfo(entity);
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
        private final MaterialRadioButton radioButton;
        private final TextView streamTitle;
        private final Chip streamTypeChip;
        private final TextView streamInfo;

        VariantHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            View item = view.findViewById(R.id.file_variants_item);
            radioButton = view.findViewById(R.id.radio_button);
            streamTitle = view.findViewById(R.id.stream_title);
            streamTypeChip = view.findViewById(R.id.stream_type_chip);
            streamInfo = view.findViewById(R.id.stream_info);
            item.setOnClickListener(this);
        }


        void bindTitle(FFmpegEntity entity) {
            String description = entity.getStreamDescription();
            streamTitle.setText(description != null ? description : "");
        }


        void bindStreamType(FFmpegEntity entity) {
            Context context = itemView.getContext();
            boolean audioOnly = entity.isAudioOnly();
            boolean videoOnly = entity.isVideoOnly();

            if (audioOnly) {
                int bgColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiaryContainer, 0);
                int onColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnTertiaryContainer, 0);
                streamTypeChip.setText(context.getString(R.string.stream_type_audio));
                applyChipColor(streamTypeChip,
                        bgColor,
                        onColor);
                streamTypeChip.setChipStrokeColor(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0), 80)));
                streamTypeChip.setChipStrokeWidth(1f);
            } else if (videoOnly) {
                int bgColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondaryContainer, 0);
                int onColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer, 0);
                streamTypeChip.setText(context.getString(R.string.stream_type_video));
                applyChipColor(streamTypeChip,
                        bgColor,
                        onColor);
                streamTypeChip.setChipStrokeColor(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(onColor, 80)));
                streamTypeChip.setChipStrokeWidth(1f);
            } else {
                int bgColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0);
                int onColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
                streamTypeChip.setText(context.getString(R.string.stream_type_muxed));
                applyChipColor(streamTypeChip,
                        bgColor,
                        onColor);
                streamTypeChip.setChipStrokeColor(ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(onColor, 80)));
                streamTypeChip.setChipStrokeWidth(1f);
            }

            applyChipColor(streamTypeChip,
                    MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0),
                    MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, 0));


        }


        void bindInfo(FFmpegEntity entity) {
            String info = entity.getCodecLabel();
            if (info != null && !info.isEmpty()) {
                streamInfo.setVisibility(View.VISIBLE);
                streamInfo.setText(info);
            } else {
                streamInfo.setVisibility(View.GONE);
            }
        }


        void bindSelection(boolean selected) {
            radioButton.setChecked(selected);
            itemView.setActivated(selected);
        }


        private void applyChipColor(Chip chip, int bgColor, int textColor) {
            chip.setChipBackgroundColor(ColorStateList.valueOf(bgColor));
            chip.setTextColor(textColor);
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