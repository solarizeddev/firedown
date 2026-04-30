package com.solarized.firedown.utils;

import android.os.Build;

public class BuildUtils {


    public static boolean hasAndroidR() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }


    public static boolean hasAndroidS() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static boolean hasAndroid12() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2;
    }

    public static boolean hasAndroid14() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    public static boolean hasAndroid15() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    public static boolean hasAndroidQ() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static boolean hasAndroidP() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }


    public static boolean hasAndroidTiramisu() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

}
