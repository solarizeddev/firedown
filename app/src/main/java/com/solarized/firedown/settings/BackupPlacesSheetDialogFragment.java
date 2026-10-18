package com.solarized.firedown.settings;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.navigation.NavBackStackEntry;

import com.solarized.firedown.R;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;
import com.solarized.firedown.sync.BackupPlaces;
import com.solarized.firedown.utils.SheetGroups;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Where to back up", two pages in one sheet. The first lists one card per
 * KIND of place (cloud storage, a WebDAV server, an S3 bucket, the SD card /
 * USB drive while one is in, "Somewhere else"). Cloud storage opens the
 * second, "Find your cloud app": a picture of the system picker with its ☰
 * ringed and its drawer open, one line saying what to tap, and "Open the
 * picker". The ☰ step is where people get stuck, so it gets the room of a
 * page right before the picker opens instead of a line squeezed into a list
 * row.
 *
 * <p>The sheet only CHOOSES: the folder pickers are registered on
 * {@link SyncSettingsFragment}, so the pick goes back to it through its
 * back-stack entry's saved-state handle ({@link #RESULT}, an int
 * {@code PLACE_*}) and the screen opens the picker or the sign-in page.
 * Back on the second page returns to the first; the page survives a
 * rotation.
 */
@AndroidEntryPoint
public class BackupPlacesSheetDialogFragment extends BaseBottomSheetDialogFragment {

    /** Saved-state key {@link SyncSettingsFragment} observes; value is a PLACE_*. */
    public static final String RESULT = "backup_place";
    public static final int PLACE_CLOUD = 1;
    public static final int PLACE_WEBDAV = 2;
    public static final int PLACE_VOLUME = 3;
    public static final int PLACE_OTHER = 4;
    public static final int PLACE_S3 = 5;

    private static final String STATE_GUIDE = "backup_places_guide";
    private static final long PAGE_FADE_MS = 150;

    private NestedScrollView mScroll;
    private View mListPage;
    private View mGuidePage;
    private TextView mGuideTitle;
    private boolean mGuideShown;

    /** Back on the guide page goes to the list, not out of the sheet. */
    private final OnBackPressedCallback mGuideBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            showGuide(false);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_backup_places, container, false);
        Context context = inflater.getContext();
        mScroll = mView.findViewById(R.id.places_scroll);
        mListPage = mView.findViewById(R.id.places_list_page);
        mGuidePage = mView.findViewById(R.id.places_guide_page);
        mGuideTitle = mView.findViewById(R.id.places_guide_title);

        mView.findViewById(R.id.place_cloud).setOnClickListener(v -> showGuide(true));
        mView.findViewById(R.id.place_webdav).setOnClickListener(v -> choose(PLACE_WEBDAV));
        mView.findViewById(R.id.place_s3).setOnClickListener(v -> choose(PLACE_S3));
        mView.findViewById(R.id.place_other).setOnClickListener(v -> choose(PLACE_OTHER));
        mView.findViewById(R.id.places_guide_back).setOnClickListener(v -> showGuide(false));
        mView.findViewById(R.id.places_guide_open).setOnClickListener(v -> choose(PLACE_CLOUD));

        // Asked when the sheet opens and again when the pick lands (the screen
        // reads the volume's own picker intent then): a card pulled in between
        // just falls back to the plain picker.
        BackupPlaces.Volume volume = BackupPlaces.removable(context);
        View volumeCard = mView.findViewById(R.id.place_volume);
        if (volume != null) {
            String label = TextUtils.isEmpty(volume.label)
                    ? getString(R.string.folder_backup_provider_sd) : volume.label;
            ((TextView) mView.findViewById(R.id.place_volume_title)).setText(label);
            ((TextView) mView.findViewById(R.id.place_volume_sub)).setText(volume.intent != null
                    ? getString(R.string.folder_backup_place_removable_sub)
                    : getString(R.string.folder_backup_place_volume_sub, label));
            volumeCard.setOnClickListener(v -> choose(PLACE_VOLUME));
            volumeCard.setVisibility(View.VISIBLE);
        }
        SheetGroups.applyCorners(mView.findViewById(R.id.places_group));

        mGuideShown = savedInstanceState != null && savedInstanceState.getBoolean(STATE_GUIDE);
        applyPage(false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Dialog dialog = getDialog();
        if (dialog instanceof ComponentDialog) {
            ((ComponentDialog) dialog).getOnBackPressedDispatcher()
                    .addCallback(getViewLifecycleOwner(), mGuideBack);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_GUIDE, mGuideShown);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mScroll = null;
        mListPage = null;
        mGuidePage = null;
        mGuideTitle = null;
    }

    private void showGuide(boolean show) {
        if (show == mGuideShown) {
            return;
        }
        mGuideShown = show;
        applyPage(true);
    }

    /** Shows the current page; {@code animate} fades the incoming one in. */
    private void applyPage(boolean animate) {
        if (mListPage == null || mGuidePage == null) {
            return;
        }
        View in = mGuideShown ? mGuidePage : mListPage;
        View out = mGuideShown ? mListPage : mGuidePage;
        out.setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        mGuideBack.setEnabled(mGuideShown);
        if (mScroll != null) {
            mScroll.scrollTo(0, 0);
        }
        if (animate) {
            in.setAlpha(0f);
            in.animate().alpha(1f).setDuration(PAGE_FADE_MS).start();
            if (mGuideShown && mGuideTitle != null) {
                mGuideTitle.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
            }
        }
    }

    /**
     * Pops FIRST, then hands the pick to the screen: the screen may navigate
     * on it (the WebDAV sign-in page is an action of settings_sync), which
     * only resolves once settings_sync is the current destination again.
     */
    private void choose(int place) {
        if (mNavController == null) {
            dismissAllowingStateLoss();
            return;
        }
        NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
        mNavController.popBackStack();
        if (previous != null) {
            previous.getSavedStateHandle().set(RESULT, place);
        }
    }
}
