package com.solarized.firedown.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;

import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.io.InputStream;


public class HtmlViewerActivity extends BaseActivity {


    private static final String TAG = HtmlViewerActivity.class.getName();

    /*
     * The WebView that is placed in this Activity
     */
    private WebView mWebView;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_viewer);

        mWebView = findViewById(R.id.webview);

        Toolbar toolbar = findViewById(R.id.toolbar);

        toolbar.setNavigationOnClickListener(v -> finish());

        // Configure the webview
        WebSettings s = mWebView.getSettings();

        s.setUseWideViewPort(false);
        s.setBlockNetworkLoads(true);

        // Javascript is purposely disabled, so that nothing can be
        // automatically run.
        s.setJavaScriptEnabled(false);



        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Apply the insets as a margin to the view. This solution sets only the
            // bottom, left, and right dimensions, but you can apply whichever insets are
            // appropriate to your layout. You can also update the view padding if that's
            // more appropriate.
            v.setPadding(insets.left, insets.top, insets.right, 0);


            return WindowInsetsCompat.CONSUMED;
        });


        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Dispatch insets because they aren't applied when the page first loads
                view.requestApplyInsets();
            }
        });


        // Check the intent for the content to view
        Intent intent = getIntent();

        Uri uri = intent.getData();

        assert uri != null;

        toolbar.setTitle(FilenameUtils.getName(uri.getPath()));

        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is != null) {
                String sb = Utils.readInputStream(is);
                mWebView.loadData(sb, FileUriHelper.MIMETYPE_TXT, "UTF-8");
                //mWebView.loadUrl("file:///android_asset/renderer/index.html");
            }
        } catch (IOException e) {
            Log.e(TAG, "Load File Error", e);
        }


    }


    @Override
    protected void onStop() {
        super.onStop();
        mWebView.stopLoading();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWebView.destroy();
    }


}
