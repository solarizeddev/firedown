package com.solarized.firedown.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;


import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class BrowserContextActions {

    private static final String TAG = BrowserContextActions.class.getSimpleName();

    private final Executor mainExecutor;

    private final OkHttpClient mOkHttpClient;

    @Inject
    public BrowserContextActions(OkHttpClient client, @Qualifiers.MainThread Executor mainExecutor) {
        mOkHttpClient = client;
        this.mainExecutor = mainExecutor;
    }

    public void launchContextOption(BaseActivity activity, String url, int id){

        if(!AppLinkUseCases.isHttpSupported(url)){
            String string = activity.getString(R.string.error_protocol, Uri.parse(url).getScheme());
            Snackbar snackbar = Snackbar.make(activity.getSnackAnchorView(),  string, Snackbar.LENGTH_LONG);
            snackbar.show();
            return;
        }


        Request request = new Request.Builder().url(url).build();
        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "sharImage onFailure", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {

                if(!response.isSuccessful())
                    return;

                ResponseBody responseBody = response.body();

                File outFile = new File(StoragePaths.getCachePath(activity), UUID.randomUUID().toString());

                try {
                    FileUtils.createParentDirectories(outFile);
                    FileUtils.copyToFile(responseBody.byteStream(), outFile);
                } catch (IOException e) {
                    Log.w(TAG, "shareImage", e);
                } finally {
                    response.close();
                    responseBody.close();
                }
                if(id == R.string.contextmenu_share_image){
                    mainExecutor.execute(() -> launchShareIntent(activity, outFile));
                }else if(id == R.string.contextmenu_copy_image){
                    mainExecutor.execute(() -> copyToClipboard(activity, outFile));
                }

            }
        });
    }

    public void copyToClipboard(BaseActivity activity, File file){
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file) ;
            ClipboardManager mClipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newUri(activity.getContentResolver(), activity.getString(R.string.share_image), uri);
            mClipboard.setPrimaryClip(clip);
    }

    public void launchShareIntent(BaseActivity activity, File file){
            String filePath = file.getAbsolutePath();
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file) ;
            Intent intent = new ShareCompat.IntentBuilder(activity)
                    .setType(FileUriHelper.getMimeTypeFromFile(file.getName()))
                    .setChooserTitle(activity.getString(R.string.share_image))
                    .setText(FilenameUtils.getName(filePath))
                    .setStream(uri)
                    .createChooserIntent();
            activity.startActivity(intent);
    }

    public void copyToClipboard(BaseActivity activity, String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        if(!BuildUtils.hasAndroid12()){
            Snackbar snackbar = Snackbar.make(activity.getSnackAnchorView(), R.string.contextmenu_snackbar_link_copied, Snackbar.LENGTH_LONG);
            snackbar.setAnchorView(R.id.anchor_view);
            snackbar.show();
        }
    }
}
