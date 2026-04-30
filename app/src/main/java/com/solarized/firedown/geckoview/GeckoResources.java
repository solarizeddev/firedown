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

        return  RESOURCE_ONBOARDING +"?" +
                "&title=" + Utils.urlEncode(title) +
                "&button=" + Utils.urlEncode(button) +
                "&description=" + Utils.urlEncode(description) +
                "&message=" + Utils.urlEncode(message) +
                "&tv=" + Utils.urlEncode(String.valueOf(false));
    }

    public static boolean isAboutOnboarding(String url){
        return url != null && url.contains(ABOUT_ONBOARDING);
    }

    public static boolean isOnboarding(String url){
        return url != null && url.contains(RESOURCE_ONBOARDING);
    }
}
