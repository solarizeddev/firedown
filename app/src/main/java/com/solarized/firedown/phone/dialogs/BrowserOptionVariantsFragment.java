package com.solarized.firedown.phone.dialogs;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AudioTrackEntity;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDownloadViewModel;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.adapters.BrowserOptionAudioTrackAdapter;
import com.solarized.firedown.ui.adapters.BrowserOptionCaptionAdapter;
import com.solarized.firedown.ui.adapters.BrowserOptionVariantAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class BrowserOptionVariantsFragment extends BaseFocusFragment implements OnItemClickListener, View.OnClickListener {

    private BrowserDownloadEntity mEntity;

    private BrowserOptionVariantAdapter mAdapter;

    /** Multi-select adapter for the captions section. Null when the video
     *  has no captured caption tracks; the section is hidden in that case. */
    @Nullable private BrowserOptionCaptionAdapter mCaptionAdapter;

    /** Single-select adapter for the audio-track section. Null unless the
     *  video is multi-audio-track (YouTube auto-dubbing); hidden otherwise. */
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

        bindUrlActions(toolbar);
        bindAudioTrackSection(view);
        bindCaptionsSection(view);

        return view;
    }

    /**
     * Copy/Share URL toolbar overflow (issue #302): hands the captured
     * resource's URL to the user without downloading — internet-radio /
     * accessibility / "open it in VLC" cases. Inflated ONLY when the capture
     * carries a copyable URL at all (see {@link #findCopyableUrl}), so
     * SABR/Mega/merged-pair captures show no ⋮. The URL is resolved at CLICK
     * time, so it follows the currently selected quality.
     */
    private void bindUrlActions(Toolbar toolbar) {
        if (findCopyableUrl() == null) {
            return;
        }
        toolbar.inflateMenu(R.menu.menu_browser_variants);
        // "Open in another app" only when something on the device would take
        // the VIEW intent — resolveActivity, the deeplink snackbar's gating
        // rule (no dead-end chooser on players-less devices). Probed once at
        // bind: the answer depends on installed apps + coarse mime, not on
        // which variant is selected.
        MenuItem open = toolbar.getMenu().findItem(R.id.action_open_external);
        if (open != null && !canOpenExternally()) {
            open.setVisible(false);
        }
        toolbar.setOnMenuItemClickListener(item -> {
            String url = findCopyableUrl();
            if (url == null) {
                return false;
            }
            int id = item.getItemId();
            if (id == R.id.action_copy_url) {
                copyUrl(url);
                return true;
            }
            if (id == R.id.action_share_url) {
                shareUrl(url);
                return true;
            }
            if (id == R.id.action_open_external) {
                openExternal(url);
                return true;
            }
            return false;
        });
    }

    /**
     * The URL these actions expose: the SELECTED variant's stream URL when it
     * is copyable, else the first copyable variant, else the entity's primary
     * URL. "Copyable" means self-contained enough that pasting it elsewhere is
     * honest: a plain http(s) URL with no separate audio half. Excluded by
     * construction, not by host list:
     * <ul>
     *   <li>SABR — the media URLs are EMPTY (the real address is a ustreamer
     *       config + PO token no external player can use); the type gate is
     *       belt-and-braces over the empty-URL check.</li>
     *   <li>MEGA — the URL alone yields AES-CTR ciphertext; only the strategy
     *       holding the per-file key can decrypt it.</li>
     *   <li>Merged pairs (Bilibili/Instagram DASH) — one URL is half the
     *       media (a silent video track).</li>
     * </ul>
     * Signed/expiring CDN URLs are deliberately NOT excluded: a freshly copied
     * one often still plays if used promptly, and the failure in an external
     * player is immediate and self-explanatory — where hiding the action for
     * whole sites would be a host list to maintain.
     */
    private @Nullable String findCopyableUrl() {
        UrlType type = UrlType.getType(mEntity.getType());
        if (type == UrlType.SABR || type == UrlType.MEGA) {
            return null;
        }
        if (mAdapter != null) {
            String selected = copyableStreamUrl(mAdapter.getSelectedStream());
            if (selected != null) {
                return selected;
            }
        }
        List<FFmpegEntity> streams = mEntity.getStreams();
        if (streams != null) {
            for (FFmpegEntity stream : streams) {
                String url = copyableStreamUrl(stream);
                if (url != null) {
                    return url;
                }
            }
        }
        return copyableUrl(mEntity.getFileUrl(), null);
    }

    private static @Nullable String copyableStreamUrl(@Nullable FFmpegEntity stream) {
        if (stream == null) {
            return null;
        }
        return copyableUrl(stream.getStreamUrl(), stream.getStreamAudioUrl());
    }

    private static @Nullable String copyableUrl(@Nullable String url, @Nullable String audioUrl) {
        if (TextUtils.isEmpty(url) || !TextUtils.isEmpty(audioUrl)) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        return url;
    }

    private void copyUrl(String url) {
        // A clipboard write is a binder call into system_server; a dying
        // clipboard service must never crash the app (the AutoCompleteView
        // hardening rule).
        try {
            ClipboardManager cm = (ClipboardManager)
                    mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.capture_copy_url), url));
        } catch (RuntimeException ignored) {
            return;
        }
        // Android 13+ draws its own clipboard confirmation overlay; a toast on
        // top of it is a duplicate, so confirm only below that.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(mActivity, R.string.capture_url_copied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void shareUrl(String url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(send, null));
        } catch (RuntimeException ignored) {
            // No resolvable share target — nothing useful to do.
        }
    }

    /**
     * Hands the URL to an external player via ACTION_VIEW — the ceiling of what
     * Android can pass across apps: the platform intent contract carries a URI
     * + MIME only, headers were never standardized into it. The one de-facto
     * convention is MX Player's {@code "headers"} String[] extra (alternating
     * key/value, adopted by a few MX-API players; VLC has no intent header
     * channel at all), attached here as a free upgrade where supported and
     * ignored everywhere else. {@code Cookie} is deliberately never exported —
     * session credentials must not leave the app for an arbitrary third-party
     * player.
     */
    private void openExternal(String url) {
        Intent view = buildExternalViewIntent(url);
        String[] headers = externalHeaders();
        if (headers != null) {
            view.putExtra("headers", headers);
        }
        try {
            startActivity(Intent.createChooser(view, getString(R.string.open_with)));
        } catch (RuntimeException ignored) {
            // Chooser race / no target — nothing useful to do.
        }
    }

    private boolean canOpenExternally() {
        String url = findCopyableUrl();
        if (url == null) {
            return false;
        }
        return buildExternalViewIntent(url)
                .resolveActivity(mActivity.getPackageManager()) != null;
    }

    private Intent buildExternalViewIntent(String url) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(Uri.parse(url), externalMimeType());
        return view;
    }

    /**
     * The MIME the VIEW intent advertises. The capture's own video/audio mime
     * passes through; everything else (manifest mimes, octet-stream, the
     * obfuscated-manifest text/html class) coarsens to {@code video/*} — a
     * precise-but-obscure type would empty the chooser on players that only
     * register the wildcard media types, and the receiving player sniffs the
     * stream itself anyway.
     */
    private String externalMimeType() {
        String mime = mEntity.getMimeType();
        if (!TextUtils.isEmpty(mime)
                && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
            return mime;
        }
        return "video/*";
    }

    /**
     * The capture's cached request headers in the MX-Player intent-API shape
     * (alternating key/value), minus {@code Cookie}. Null when nothing useful
     * remains.
     */
    private @Nullable String[] externalHeaders() {
        Map<String, String> headers = Utils.stringToMap(mEntity.getFileHeaders());
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        List<String> flat = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (TextUtils.isEmpty(key) || "cookie".equalsIgnoreCase(key)
                    || TextUtils.isEmpty(entry.getValue())) {
                continue;
            }
            flat.add(key);
            flat.add(entry.getValue());
        }
        if (flat.isEmpty()) {
            return null;
        }
        return flat.toArray(new String[0]);
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

        RecyclerView tracksRecycler = root.findViewById(R.id.audio_track_recycler);
        mAudioTrackAdapter = new BrowserOptionAudioTrackAdapter(tracks);
        tracksRecycler.setAdapter(mAudioTrackAdapter);
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
        RecyclerView captionsRecycler = root.findViewById(R.id.captions_recycler);

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
        captionsRecycler.setAdapter(mCaptionAdapter);
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
