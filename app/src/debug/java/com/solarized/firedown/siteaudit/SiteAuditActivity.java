package com.solarized.firedown.siteaudit;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoView;

/**
 * Debug builds only: the screen the device audit (SiteAuditTest) browses in.
 * A status line over a real {@link GeckoView}, so every page is laid out and
 * painted at the phone's own size — lazy players, IntersectionObservers and
 * consent walls behave as they do in a tab — and the test can take a picture
 * of what the user would have seen. It holds no session of its own: the test
 * opens each site's session on the app's runtime and hands it to the view.
 */
public class SiteAuditActivity extends Activity {

    private GeckoView mGeckoView;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null) {
            keyguard.requestDismissKeyguard(this, null);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.BLACK);

        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics()));
        mStatus.setPadding(pad, pad, pad, pad);
        mStatus.setMaxLines(3);
        mStatus.setText("Site audit");

        mGeckoView = new GeckoView(this);

        root.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mGeckoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    public GeckoView getGeckoView() {
        return mGeckoView;
    }

    public void setStatus(String text) {
        mStatus.setText(text);
    }
}
