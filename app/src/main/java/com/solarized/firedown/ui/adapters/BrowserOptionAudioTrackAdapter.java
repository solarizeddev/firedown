package com.solarized.firedown.ui.adapters;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.radiobutton.MaterialRadioButton;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AudioTrackEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-select list of audio tracks for a multi-audio-track video (YouTube
 * auto-dubbing), shown inside the variant picker between the quality variants
 * and the captions. Rows reuse the quality row layout (radio + title + meta)
 * so the sections read as one; clicks are handled internally (unlike the
 * quality adapter's fragment round-trip) because both lists share the row
 * layout's view id and a shared listener couldn't tell them apart.
 *
 * <p>The list arrives original-track-first (background.js
 * buildAudioTrackOptions), so position 0 is the preselected default and a
 * no-op confirms the original language.</p>
 */
public class BrowserOptionAudioTrackAdapter
        extends RecyclerView.Adapter<BrowserOptionAudioTrackAdapter.ViewHolder> {

    private static final String PAYLOAD_SELECTION = "selection";

    private final List<AudioTrackEntity> mTracks;
    private int mSelectedPosition = 0;

    public BrowserOptionAudioTrackAdapter(@NonNull List<AudioTrackEntity> tracks) {
        mTracks = new ArrayList<>(tracks);
    }

    /** The track the user has selected (position 0 = the original default). */
    @Nullable
    public AudioTrackEntity getSelectedTrack() {
        if (mSelectedPosition < 0 || mSelectedPosition >= mTracks.size()) return null;
        return mTracks.get(mSelectedPosition);
    }

    /** True when the selection differs from the preselected default track. */
    public boolean isNonDefaultSelected() {
        return mSelectedPosition > 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.fragment_dialog_browser_options_item_variant, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AudioTrackEntity track = mTracks.get(position);

        String name = track.getName();
        if (TextUtils.isEmpty(name)) {
            // Fall back to the localized language name resolved from the
            // track id's BCP-47 part ("en-US.4" → "English (United States)").
            String code = track.getLanguageCode();
            name = !TextUtils.isEmpty(code)
                    ? Locale.forLanguageTag(code).getDisplayName()
                    : track.getId();
        }
        holder.title.setText(name);

        // Meta line: BCP-47 code, plus an explicit "Original" mark on the
        // default row — the display name usually says it, but not in every
        // locale, and the mark is what tells the dub rows apart at a glance.
        StringBuilder meta = new StringBuilder();
        String code = track.getLanguageCode();
        if (!TextUtils.isEmpty(code)) {
            meta.append(code);
        }
        if (track.isOriginal()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(holder.itemView.getResources()
                    .getString(R.string.audio_track_original_label));
        }
        if (meta.length() == 0) {
            holder.meta.setVisibility(View.GONE);
        } else {
            holder.meta.setVisibility(View.VISIBLE);
            holder.meta.setText(meta.toString());
        }

        holder.bindSelection(mSelectedPosition == position);
        holder.itemView.setOnClickListener(v -> {
            int p = holder.getAdapterPosition();
            if (p == RecyclerView.NO_POSITION || p == mSelectedPosition) return;
            int previous = mSelectedPosition;
            mSelectedPosition = p;
            notifyItemChanged(previous, PAYLOAD_SELECTION);
            notifyItemChanged(p, PAYLOAD_SELECTION);
        });
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
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
        return mTracks.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialRadioButton radio;
        final TextView title;
        final TextView meta;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            radio = itemView.findViewById(R.id.radio_button);
            title = itemView.findViewById(R.id.stream_title);
            meta = itemView.findViewById(R.id.stream_info);
        }

        void bindSelection(boolean selected) {
            radio.setChecked(selected);
            itemView.setActivated(selected);
        }
    }
}
