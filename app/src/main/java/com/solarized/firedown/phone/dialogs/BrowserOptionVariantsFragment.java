package com.solarized.firedown.phone.dialogs;


import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AudioTrackEntity;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDownloadViewModel;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.adapters.BrowserOptionAudioTrackAdapter;
import com.solarized.firedown.ui.adapters.BrowserOptionCaptionAdapter;
import com.solarized.firedown.ui.adapters.BrowserOptionVariantAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.FragmentArgs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


public class BrowserOptionVariantsFragment extends BaseFocusFragment implements OnItemClickListener, View.OnClickListener {

    private BrowserDownloadEntity mEntity;

    private BrowserOptionVariantAdapter mAdapter;

    /** Multi-select chip controller for the captions section. Null when the
     *  video has no captured caption tracks; the section is hidden then. */
    @Nullable private BrowserOptionCaptionAdapter mCaptionAdapter;

    /** Single-select chip controller for the audio-track section. Null unless
     *  the video is multi-audio-track (YouTube auto-dubbing); hidden else. */
    @Nullable private BrowserOptionAudioTrackAdapter mAudioTrackAdapter;

    private FragmentsOptionsViewModel mFragmentsViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, BrowserDownloadEntity.class);
        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);
        // Null on restore is handled in onCreateView — pop back to the
        // previous destination since the variant grid has nothing to show.
    }


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        if (mEntity == null) {
            // Args lost on restore. Defer dispatchCancel() onto the next
            // main-thread tick so we don't re-enter the parent's child
            // FragmentManager while it's still executing the transaction
            // that produced this onCreateView. The holder sheet observes
            // the cancel event and pops us off its child stack.
            new Handler(Looper.getMainLooper()).post(this::dispatchCancel);
            return null;
        }

        View view = inflater.inflate(R.layout.fragment_dialog_browser_options_variants, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        Toolbar toolbar = view.findViewById(R.id.toolbar);

        toolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);
        toolbar.setNavigationOnClickListener(v -> dispatchCancel());

        view.findViewById(R.id.cancel_button).setOnClickListener(this);
        view.findViewById(R.id.button).setOnClickListener(this);

        mAdapter = new BrowserOptionVariantAdapter(mEntity.getStreams(), this);
        recyclerView.setAdapter(mAdapter);

        bindAudioTrackSection(view);
        bindCaptionsSection(view);

        return view;
    }

    /**
     * Populates the audio-track single-select section from the entity's
     * captured track list. Hidden for everything but multi-audio-track
     * videos (YouTube auto-dubbing). The original-language track arrives
     * first and preselected, so a no-op confirms the source language and
     * picking a dub swaps the download's audio (see dispatchDownload).
     */
    private void bindAudioTrackSection(View root) {
        View section = root.findViewById(R.id.audio_track_section);
        List<AudioTrackEntity> tracks = mEntity.getAudioTracks();

        if (tracks == null || tracks.size() < 2) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);

        mAudioTrackAdapter = new BrowserOptionAudioTrackAdapter(tracks);
        mAudioTrackAdapter.attachTo(root.findViewById(R.id.audio_track_chips));
    }

    /**
     * Populates the captions multi-select section from the in-memory repo
     * (entities sharing the parent video's origin and matching a subtitle
     * mime). Hides the whole section when the video has no captured tracks
     * — most non-YouTube origins, and YouTube videos with captions disabled.
     *
     * <p>Pre-checks the row whose language matches the device locale, so
     * the user's most likely choice is one tap away and a no-op confirms it.
     * English isn't pre-checked separately to avoid surprising downloads.</p>
     */
    private void bindCaptionsSection(View root) {
        View section = root.findViewById(R.id.captions_section);
        ChipGroup captionsChips = root.findViewById(R.id.captions_chips);

        BrowserDownloadViewModel browserVm =
                new ViewModelProvider(mActivity).get(BrowserDownloadViewModel.class);
        List<BrowserDownloadEntity> captions =
                browserVm.subtitlesForOrigin(mEntity.getFileOrigin());

        if (captions.isEmpty()) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);

        mCaptionAdapter = new BrowserOptionCaptionAdapter(captions);
        // Pre-check the device locale's language. YouTube ships languageCode
        // as either a bare code ("en") or with a region tag ("es-419"); seed
        // both shapes plus the language root so a Spanish device matches an
        // "es" track even when the device locale is "es-ES".
        Locale locale = Locale.getDefault();
        List<String> preselect = new ArrayList<>(Arrays.asList(
                locale.toLanguageTag(),
                locale.getLanguage()
        ));
        mCaptionAdapter.preselectLanguages(preselect);
        mCaptionAdapter.attachTo(captionsChips);
    }


    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;
        if (resId == R.id.file_variants_item) {
            mAdapter.setSelected(position);
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.cancel_button) {
            dispatchCancel();
        } else {
            dispatchDownload();
        }
    }

    private void dispatchCancel() {
        OptionEntity optionEntity = new OptionEntity();
        optionEntity.setId(R.id.cancel_button);
        mFragmentsViewModel.onOptionsSelected(optionEntity);
    }

    private void dispatchDownload() {
        FFmpegEntity selectedStream = mAdapter.getSelectedStream();

        // Build an immutable DownloadRequest from the entity + selected stream
        DownloadRequest request = DownloadRequest.from(mEntity, selectedStream);

        // Apply a non-default audio track choice on top. Every variant carries
        // the ORIGINAL track's audio (background.js selectDefaultAudio), so
        // only a deliberate switch needs an override — swap the direct audio
        // URL (FFmpegMergeStrategy path) and the SABR audio FormatId + track
        // id (SabrStrategy path) for the chosen track's rendition.
        if (mAudioTrackAdapter != null && mAudioTrackAdapter.isNonDefaultSelected()) {
            AudioTrackEntity track = mAudioTrackAdapter.getSelectedTrack();
            if (track != null) {
                DownloadRequest.Builder builder = request.toBuilder();
                if (!TextUtils.isEmpty(track.getUrl())) {
                    builder.audioUrl(track.getUrl());
                }
                if (track.getItag() > 0) {
                    builder.sabrAudioItag(track.getItag())
                            .sabrAudioLastModified(track.getLastModified())
                            .sabrAudioXtags(track.getXtags())
                            .sabrAudioTrackId(track.getId());
                }
                request = builder.build();
            }
        }

        OptionEntity optionEntity = new OptionEntity();
        optionEntity.setId(R.id.button);
        optionEntity.setDownloadRequest(request);
        // Pass the entity too — the holder forwards it to SaveFileDialog
        // when "Ask filename" is on, so the dialog can display the
        // pre-filled name without rehydrating from the request alone.
        optionEntity.setBrowserDownloadEntity(mEntity);
        optionEntity.setAction(IntentActions.DOWNLOAD_START);

        // Selected captions ride alongside via the existing downloadRequests
        // batch field. The holder fragment fires these as a batch after the
        // video, bypassing the SaveFileDialog filename prompt — captions
        // already have meaningful "<Title> [lang].srt" names from the parser
        // and prompting per-track would be hostile UX.
        if (mCaptionAdapter != null) {
            List<BrowserDownloadEntity> selectedCaptions = mCaptionAdapter.getSelected();
            if (!selectedCaptions.isEmpty()) {
                ArrayList<DownloadRequest> captionRequests = new ArrayList<>(selectedCaptions.size());
                for (BrowserDownloadEntity caption : selectedCaptions) {
                    captionRequests.add(DownloadRequest.from(caption));
                }
                optionEntity.setDownloadRequests(captionRequests);
            }
        }

        mFragmentsViewModel.onOptionsSelected(optionEntity);
    }
}
