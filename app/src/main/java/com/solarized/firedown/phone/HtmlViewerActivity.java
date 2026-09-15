package com.solarized.firedown.phone;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import android.webkit.WebResourceRequest;
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

            /**
             * The viewer shows ONE local file and never navigates away from
             * it. setBlockNetworkLoads only blocks sub-resources — a top-level
             * navigation still goes out (a tapped link, or a
             * {@code <meta http-equiv=refresh>} the archive carried) and,
             * with the network blocked, lands on WebView's own
             * "net::ERR_CACHE_MISS" error page over the archive. Reported
             * on-device from a Springer ePDF snapshot whose noscript
             * meta-refresh fired in this JS-off viewer. The serializer strips
             * those now; this is the belt for anything it misses. A web link
             * the user taps is handed to Firedown's own browser instead of
             * being swallowed; everything else stays put.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (target == null) return true;
                if (isLoadedDocument(target)) return false;
                String scheme = target.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    openInBrowser(target);
                }
                return true;
            }
        });


        // Check the intent for the content to view
        Intent intent = getIntent();

        Uri uri = intent.getData();

        assert uri != null;

        toolbar.setTitle(FilenameUtils.getName(uri.getPath()));

        // text/html → RENDER the page (a saved "Save snapshot" / web archive).
        // Load the content:// directly so a tens-of-MB self-contained file isn't
        // buffered into a String first, and so the inlined data: resources
        // render. WebView allows content access by default; JS stays disabled
        // and network blocked, which is exactly right for a snapshot (scripts
        // stripped, every resource inlined). Anything else → show the raw text
        // (subtitles, .txt) as before.
        if (FileUriHelper.MIMETYPE_HTML.equalsIgnoreCase(intent.getType())) {
            mWebView.loadUrl(uri.toString());
        } else {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    String sb = Utils.readInputStream(is);
                    mWebView.loadData(sb, FileUriHelper.MIMETYPE_TXT, "UTF-8");
                }
            } catch (IOException e) {
                Log.e(TAG, "Load File Error", e);
            }
        }


    }


    /** The archive itself (the content:// we loaded), fragment navigation included. */
    private boolean isLoadedDocument(Uri target) {
        Uri loaded = getIntent().getData();
        if (loaded == null) return false;
        return TextUtils.equals(loaded.getScheme(), target.getScheme())
                && TextUtils.equals(loaded.getAuthority(), target.getAuthority())
                && TextUtils.equals(loaded.getPath(), target.getPath());
    }

    /**
     * A link tapped inside an archive opens in Firedown's browser — the
     * app's own VIEW filter, pinned to this package so the chooser never
     * appears. Only reached for a user gesture on an http(s) link: a
     * meta-refresh is stripped by the serializer before it gets here, and a
     * script can't run in this viewer.
     */
    private void openInBrowser(Uri target) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, target);
            intent.setPackage(getPackageName());
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "openInBrowser: no handler for " + target.getScheme());
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
