package com.solarized.firedown.utils;
public class WebUtils {
    public static String getFileNameFromURL(String url) {
        if (url == null) return "";
        int q = url.indexOf('?'); if (q >= 0) url = url.substring(0, q);
        int s = url.lastIndexOf('/');
        return s >= 0 ? url.substring(s + 1) : url;
    }
}
