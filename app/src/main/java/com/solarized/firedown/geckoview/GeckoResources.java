package com.solarized.firedown.geckoview;

import android.content.Context;

import com.solarized.firedown.R;
import com.solarized.firedown.utils.Utils;

public class GeckoResources {


    public static final String ABOUT_ONBOARDING = "about:firedown";

    private static final String RESOURCE_ONBOARDING = "resource://android/assets/firedown/firedown.html";

    public static String createFiredownTab(Context context){
        String title = context.getString(R.string.app_name);
        String button = context.getString(R.string.onboarding_button);
        String description = context.getString(R.string.onboarding_title);
        String message = context.getString(R.string.onboarding_message);
        // Supported-sites strip label rendered inside firedown.html.
        // Brand chip text is universal (YouTube / Reddit / X / …) so
        // only the heading needs translation; the chip URLs are
        // hard-coded in the HTML itself.
        String sitesLabel = context.getString(R.string.home_onboarding_sites_label);

        return  RESOURCE_ONBOARDING +"?" +
                "&title=" + Utils.urlEncode(title) +
                "&button=" + Utils.urlEncode(button) +
                "&description=" + Utils.urlEncode(description) +
                "&message=" + Utils.urlEncode(message) +
                "&sitesLabel=" + Utils.urlEncode(sitesLabel) +
                "&tv=" + Utils.urlEncode(String.valueOf(false));
    }

    public static boolean isAboutOnboarding(String url){
        return url != null && url.contains(ABOUT_ONBOARDING);
    }

    public static boolean isOnboarding(String url){
        return url != null && url.contains(RESOURCE_ONBOARDING);
    }
}
