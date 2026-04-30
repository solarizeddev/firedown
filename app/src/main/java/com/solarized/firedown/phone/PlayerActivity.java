package com.solarized.firedown.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;

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

    private DownloadEntity mDownloadEntity;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (BuildUtils.hasAndroidP()) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        mDownloadEntity = getDownloadEntity();

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


    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        if(mDownloadEntity != null && !mDownloadEntity.isFileEncrypted())
            getMenuInflater().inflate(R.menu.menu_player, menu);
        return true;
    }

    @NonNull
    private DownloadEntity getDownloadEntity() {
        Intent intent = getIntent();

        Bundle bundle = intent.getExtras();

        DownloadEntity downloadEntity = bundle != null ? bundle.getParcelable(Keys.ITEM_ID) : null;

        if(downloadEntity == null){

            String action = intent.getAction();

            Uri uri = intent.getData();

            if(uri != null && action != null && action.equals(Intent.ACTION_VIEW)){

                downloadEntity = new DownloadEntity();

                downloadEntity.setFilePath(ContentUriUtils.getPath(this, uri));

                downloadEntity.setFileMimeType(intent.getType());

                intent.getExtras();

            }else{
                throw new RuntimeException("DownloadEntity can not be null");
            }

        }
        return downloadEntity;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
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
