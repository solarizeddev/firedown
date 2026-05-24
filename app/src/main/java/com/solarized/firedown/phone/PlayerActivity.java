package com.solarized.firedown.phone;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.util.Rational;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ShareCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;

import java.util.ArrayList;
import java.util.List;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.phone.fragments.ImageViewerFragment;
import com.solarized.firedown.phone.fragments.MediaViewerFragment;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.ContentUriUtils;
import com.solarized.firedown.Keys;

import org.apache.commons.io.FilenameUtils;

import java.io.File;


public class PlayerActivity extends AppCompatActivity {

    private static final String TAG = PlayerActivity.class.getSimpleName();

    /**
     * Action sent by the PiP RemoteAction PendingIntents and consumed by
     * {@link #mPipReceiver}. Kept package-internal — the receiver is
     * registered as not-exported, so a unique string here is enough.
     */
    private static final String ACTION_PIP_CONTROL =
            "com.solarized.firedown.phone.PlayerActivity.PIP_CONTROL";

    /**
     * Extra slot on the PiP control intent that selects which control
     * was tapped. Only one control today (play/pause) but the constant
     * makes adding rewind/forward later a one-line change.
     */
    private static final String EXTRA_CONTROL = "extra_control";

    private static final int CONTROL_PLAY_PAUSE = 1;

    /**
     * Distinct request codes for the PendingIntents — Android caches
     * PendingIntents by (action, requestCode), and a single shared code
     * would let a "pause" intent overwrite a "play" intent that's still
     * referenced by the PiP window.
     */
    private static final int REQUEST_PLAY = 100;
    private static final int REQUEST_PAUSE = 101;

    private DownloadEntity mDownloadEntity;

    /**
     * Receiver registered only while in PiP. The PiP RemoteAction fires
     * a broadcast (not an activity intent) so the click handler can run
     * without bringing the activity back to the foreground.
     */
    private BroadcastReceiver mPipReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Opt into edge-to-edge BEFORE setContentView so the content
        // hierarchy is laid out with the correct insets dispatch from
        // the start. Replaces android:windowTranslucentNavigation /
        // windowTranslucentStatus on Theme.FireDown.Play (those legacy
        // flags made WindowInsets.systemBars().bottom report 0 to
        // child listeners, which broke every inset-based attempt to
        // keep the PlayerView controller above the nav bar).
        // statusBarColor / navigationBarColor are set transparent in
        // the theme so the bars stay see-through over the video.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_player);

        // FLAG_FULLSCREEN was here previously. Removed because it was
        // deprecated in API 30 and overlaps with the per-fragment
        // WindowInsetsController calls (MediaViewerFragment toggles
        // the system bars there). Keeping both layered the legacy flag
        // on top of the modern controller and confused which source of
        // truth held the immersive state on Android 11+. Fullscreen is
        // now entirely managed by the fragment via WindowInsetsController.

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (BuildUtils.hasAndroidP()) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        loadFromIntent();
    }

    /**
     * Re-route singleTask re-launches into the same fragment-replacement
     * path onCreate uses. Without this override the activity is reused
     * with the original intent / fragment / player — visibly showing
     * the previous video at the position it was paused at when PiP was
     * dismissed. setIntent updates getIntent() so {@link #getDownloadEntity()}
     * reads the freshly supplied entity.
     */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadFromIntent();
    }

    private void loadFromIntent() {
        mDownloadEntity = getDownloadEntity();

        if (mDownloadEntity == null) {
            // Args lost on process-death restore — finish instead of
            // crashing; user lands back where they launched from.
            finish();
            return;
        }

        String fileMimeType = mDownloadEntity.getFileMimeType();

        String fileName = mDownloadEntity.getFileName();

        ActionBar actionBar = getSupportActionBar();

        if(actionBar != null) {
            actionBar.setTitle(fileName);
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        Bundle bundle = new Bundle();

        bundle.putParcelable(Keys.ITEM_ID, mDownloadEntity);

        if(FileUriHelper.isVideo(fileMimeType) || FileUriHelper.isAudio(fileMimeType)){

            MediaViewerFragment mediaViewerFragment = new MediaViewerFragment();

            mediaViewerFragment.setArguments(bundle);

            getSupportFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.content_frame, mediaViewerFragment, MediaViewerFragment.class.getSimpleName())
                    .commit();

        }else {

            ImageViewerFragment imageViewerFragment = new ImageViewerFragment();

            imageViewerFragment.setArguments(bundle);

            getSupportFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.content_frame, imageViewerFragment, ImageViewerFragment.class.getSimpleName())
                    .commit();

        }

        // Menu visibility depends on the new entity's encrypted flag, so
        // re-inflate after the replacement. No-op if menu wasn't created
        // yet (the framework will call onCreateOptionsMenu naturally).
        invalidateOptionsMenu();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        if(mDownloadEntity != null && !mDownloadEntity.isFileEncrypted())
            getMenuInflater().inflate(R.menu.menu_player, menu);
        return true;
    }

    /**
     * PiP toolbar item is visible only when:
     *   • the device supports PiP (system feature), AND
     *   • the current fragment is the video viewer (image viewer
     *     has nothing to PiP).
     * Visibility refreshes via invalidateOptionsMenu() on entity
     * replace and on the video-ready event from MediaViewerFragment.
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem pipItem = menu.findItem(R.id.action_pip);
        if (pipItem != null) {
            boolean pipSupported = getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
            MediaViewerFragment fragment = getMediaFragment();
            boolean isVideo = fragment != null && fragment.isVideoMime();
            pipItem.setVisible(pipSupported && isVideo);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Nullable
    private DownloadEntity getDownloadEntity() {
        Intent intent = getIntent();

        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            bundle.setClassLoader(DownloadEntity.class.getClassLoader());
        }

        DownloadEntity downloadEntity = bundle != null ? bundle.getParcelable(Keys.ITEM_ID) : null;

        if(downloadEntity == null){

            String action = intent.getAction();

            Uri uri = intent.getData();

            if(uri != null && action != null && action.equals(Intent.ACTION_VIEW)){

                downloadEntity = new DownloadEntity();

                downloadEntity.setFilePath(ContentUriUtils.getPath(this, uri));

                downloadEntity.setFileMimeType(intent.getType());

            }
            // null return signals loadFromIntent() to finish().
        }
        return downloadEntity;
    }


    /**
     * Called when the user presses Home (or otherwise sends the activity
     * to the background) without explicitly closing it. PiP only enters
     * here, not from onPause — that path also fires on lock-screen and
     * configuration changes where slipping into PiP would be surprising.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        MediaViewerFragment fragment = getMediaFragment();
        if (fragment != null && fragment.isVideoMime() && fragment.isPlaying()) {
            enterPipMode();
        }
    }

    /**
     * Build PiP params (aspect ratio + play/pause RemoteAction) and ask
     * the framework to enter PiP. Failures are swallowed because PiP
     * entry can be denied for reasons outside our control (e.g. system
     * setting disabled, low memory) and there's nothing useful to do.
     */
    public void enterPipMode() {
        MediaViewerFragment fragment = getMediaFragment();
        if (fragment == null) return;
        try {
            enterPictureInPictureMode(buildPipParams(fragment.isPlaying()));
        } catch (IllegalStateException ignored) {
            // PiP not supported on this device / config — no-op.
        }
    }

    /**
     * Refresh the PiP action set without re-entering PiP. Called by the
     * fragment when the player toggles between playing and paused so the
     * action icon reflects current state. setPictureInPictureParams is
     * a no-op when not in PiP, so unconditional calls are safe.
     */
    public void updatePipParams() {
        MediaViewerFragment fragment = getMediaFragment();
        if (fragment == null) return;
        try {
            setPictureInPictureParams(buildPipParams(fragment.isPlaying()));
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Flip the activity between landscape and portrait. Invoked from
     * the rotate button in the custom controller. Once requestedOrientation
     * is set the framework keeps the activity in that orientation until
     * the next call — tapping the button a second time returns to the
     * other one. PlayerActivity declares orientation /
     * screenSize / screenLayout / smallestScreenSize in
     * android:configChanges, so the activity isn't recreated; the
     * fragment / player / inset listener all survive the rotation.
     */
    public void toggleOrientation() {
        int requested = getRequestedOrientation();
        int target = (requested == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setRequestedOrientation(target);
    }

    private PictureInPictureParams buildPipParams(boolean isPlaying) {
        Rational aspect = computeAspectRatio();
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(buildPlayPauseAction(isPlaying));
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .setActions(actions);
        return builder.build();
    }

    /**
     * Clamp the aspect ratio to the framework's accepted range
     * (roughly 0.42 to 2.39). Outside that range setAspectRatio throws
     * IllegalArgumentException and the whole PiP entry fails.
     */
    private Rational computeAspectRatio() {
        MediaViewerFragment fragment = getMediaFragment();
        Rect videoRect = (fragment != null) ? fragment.getVideoBounds() : null;
        int w = (videoRect != null) ? videoRect.width() : 16;
        int h = (videoRect != null) ? videoRect.height() : 9;
        if (w <= 0 || h <= 0) { w = 16; h = 9; }

        // Framework limits: 100/239 .. 239/100. Clamp by adjusting the
        // smaller side rather than swapping aspect entirely — keeps the
        // PiP window oriented the same way as the source video.
        double ratio = (double) w / (double) h;
        if (ratio < 100.0 / 239.0) { h = (int) Math.round(w * 239.0 / 100.0); }
        else if (ratio > 239.0 / 100.0) { w = (int) Math.round(h * 239.0 / 100.0); }
        return new Rational(w, h);
    }

    private RemoteAction buildPlayPauseAction(boolean isPlaying) {
        int iconRes = isPlaying ? R.drawable.media_action_pause : R.drawable.media_action_play;
        int titleRes = isPlaying ? R.string.pip_pause : R.string.pip_play;
        int requestCode = isPlaying ? REQUEST_PAUSE : REQUEST_PLAY;

        Intent intent = new Intent(ACTION_PIP_CONTROL)
                .setPackage(getPackageName())
                .putExtra(EXTRA_CONTROL, CONTROL_PLAY_PAUSE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = getString(titleRes);
        return new RemoteAction(
                Icon.createWithResource(this, iconRes), title, title, pendingIntent);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                              @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        if (isInPictureInPictureMode) {
            // Defensive: some OEM ROMs deliver onPictureInPictureModeChanged(true)
            // twice in a row without an intervening (false). Without this
            // unregister the previous BroadcastReceiver would stay registered
            // with the system but no longer be reachable from mPipReceiver —
            // a slow leak of the activity until process death.
            if (mPipReceiver != null) {
                unregisterReceiver(mPipReceiver);
                mPipReceiver = null;
            }
            mPipReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_PIP_CONTROL.equals(intent.getAction())) return;
                    int control = intent.getIntExtra(EXTRA_CONTROL, 0);
                    if (control == CONTROL_PLAY_PAUSE) {
                        MediaViewerFragment fragment = getMediaFragment();
                        if (fragment != null) fragment.togglePlayPause();
                    }
                }
            };
            // RECEIVER_NOT_EXPORTED keeps the receiver inaccessible to other
            // apps — the only legitimate sender is our own PendingIntent.
            ContextCompat.registerReceiver(this, mPipReceiver,
                    new IntentFilter(ACTION_PIP_CONTROL),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } else {
            if (mPipReceiver != null) {
                unregisterReceiver(mPipReceiver);
                mPipReceiver = null;
            }
        }

        MediaViewerFragment fragment = getMediaFragment();
        if (fragment != null) fragment.onPipModeChanged(isInPictureInPictureMode);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            if (isInPictureInPictureMode) actionBar.hide();
            else actionBar.show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPipReceiver != null) {
            unregisterReceiver(mPipReceiver);
            mPipReceiver = null;
        }
    }

    @Nullable
    private MediaViewerFragment getMediaFragment() {
        androidx.fragment.app.Fragment f = getSupportFragmentManager()
                .findFragmentByTag(MediaViewerFragment.class.getSimpleName());
        return (f instanceof MediaViewerFragment) ? (MediaViewerFragment) f : null;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
        }else if (id == R.id.action_pip) {
            enterPipMode();
            return true;
        }else if(id == R.id.action_share) {//add the function to perform here
            String mimeType = mDownloadEntity.getFileMimeType();
            String filePath = mDownloadEntity.getFilePath();
            if(filePath != null){
                File file = new File(filePath);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                Intent intent = new ShareCompat.IntentBuilder(this)
                        .setType(mimeType)
                        .setChooserTitle(getString(R.string.share))
                        .setText(FilenameUtils.getName(filePath))
                        .setStream(uri)
                        .createChooserIntent();
                startActivity(intent);
            }else{
                Snackbar snackbar = Snackbar.make(getWindow().getDecorView(), R.string.error_unknown, Snackbar.LENGTH_LONG);
                snackbar.show();
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    



}
