package com.solarized.firedown.manager;


import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.utils.BuildUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlParser {

    private static final String TAG = UrlParser.class.getSimpleName();

    public static final Pattern FILE_PATTERN = Pattern.compile("(.*?)(:?\\((\\d+)?\\))?(\\.[^.]*)");

    public static final Pattern DATA_PATTERN = Pattern.compile("(?<=data:)(.*)(?=;)");

    public static boolean isDataUrl(String url){
        Matcher matcher = DATA_PATTERN.matcher(url);
        return matcher.find();
    }

    public static String parseFilePath(String filePath) {
        Matcher m = UrlParser.FILE_PATTERN.matcher(filePath);
        if (m.matches()) {// group 1 is the prefix, group 2 is the
            // number, group 3 is the suffix
            try {
                filePath = m.group(1)
                        + (m.group(3) == null ? "(" + 1 : "(" + (Long
                        .parseLong(Objects.requireNonNull(m.group(3), "10")) + 1)) + ")"
                        + (m.group(4) == null ? "" : m.group(4));
            } catch (NumberFormatException e) {
                Log.w(TAG, "setFileName", e);
                int upperbound = 100;
                Random rand = new Random();
                filePath = m.group(1) + " (" + rand.nextInt(upperbound) + (m.group(3) == null ? "" : m.group(3)) + ")";
            }
        }
        return filePath;
    }

    public static String decodeUrl(String url) {
        try {
            if(BuildUtils.hasAndroidTiramisu()){
                url = URLDecoder.decode(url, StandardCharsets.UTF_8);
            }else{
                url = URLDecoder.decode(url, StandardCharsets.UTF_8.name());
            }
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Log.e(TAG, "decodeUrl");
        }
        return url;
    }


    public static UrlType getUrlGeckoType(String url, String type) {

        UrlType returnType = UrlType.DUMMY;

        if (TextUtils.isEmpty(url)) {
            return returnType;
        }

        if (type.contains("image")) {
            returnType = UrlType.IMAGE;
        } else if(type.contains("media") || type.contains("youtube") || type.contains("variants")){
            returnType = UrlType.MEDIA;
        } else if(type.contains("xmlhttprequest")){
            returnType = UrlType.FILE;
        } else if(type.contains("svg")){
            returnType = UrlType.SVG;
        } else if(type.contains("timedtext")){
            returnType = UrlType.TIMEDTEXT;
        } else if(type.contains("ts")){
            returnType = UrlType.TS;
        }

        Log.d(TAG, "getUrlGeckoType: " + type + " url: " + url + " returnType: " + returnType);

        return returnType;
    }


}