package com.solarized.firedown.manager;


import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.DataUris;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlParser {

    private static final String TAG = UrlParser.class.getSimpleName();

    public static final Pattern FILE_PATTERN = Pattern.compile("(.*?)(:?\\((\\d+)?\\))?(\\.[^.]*)");

    public static boolean isDataUrl(String url){
        return DataUris.isDataUri(url);
    }

    /**
     * The next candidate for a taken path: {@code clip.mp4} → {@code clip(1).mp4}
     * → {@code clip(2).mp4}. The counter goes on the file NAME, before its
     * extension, and a name with no extension counts too: the old whole-path
     * regex found "the last dot" anywhere in the path, so an extensionless
     * file under a dotted directory ({@code …/com.solarized.firedown/files/safe/clip})
     * got its counter inside the package name, and an extensionless name in
     * a dotless folder came back unchanged, which spun the engine's
     * collision loop forever.
     */
    public static String parseFilePath(String filePath) {
        int slash = filePath.lastIndexOf('/');
        String dir = slash < 0 ? "" : filePath.substring(0, slash + 1);
        String name = slash < 0 ? filePath : filePath.substring(slash + 1);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot);
            name = name.substring(0, dot);
        }
        Matcher m = NAME_COUNTER.matcher(name);
        if (m.matches()) {
            try {
                name = m.group(1) + "(" + (Long.parseLong(m.group(2)) + 1) + ")";
            } catch (NumberFormatException e) {
                name = m.group(1) + "(" + (new Random().nextInt(100) + 1) + ")";
            }
        } else {
            name = name + "(1)";
        }
        return dir + name + ext;
    }

    /** {@code base(7)}: the base, the counter. */
    private static final Pattern NAME_COUNTER = Pattern.compile("^(.*?)\\((\\d{1,9})\\)$");

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

        if (type.contains("mega")) {
            // Mega.nz folder link (type "mega-folder"). Checked first so it never
            // falls through to another branch; "mega-folder" shares no substring
            // with the keywords below, but the ordering keeps it explicit.
            returnType = UrlType.MEGA;
        } else if (type.contains("deezer")) {
            // Deezer full track — a decrypt-on-download capture like Mega. Checked
            // early for the same reason (never fall through to "media").
            returnType = UrlType.DEEZER;
        } else if (type.contains("hls-master")) {
            returnType = UrlType.HLS_MASTER;
        } else if (type.contains("image")) {
            returnType = UrlType.IMAGE;
        } else if(type.contains("media") || type.contains("youtube") || type.contains("variants")){
            returnType = UrlType.MEDIA;
        } else if(type.contains("xmlhttprequest")){
            returnType = UrlType.FILE;
        } else if(type.contains("svg")){
            returnType = UrlType.SVG;
        } else if(type.contains("subtitle")){
            returnType = UrlType.SUBTITLE;
        } else if(type.contains("timedtext")){
            returnType = UrlType.TIMEDTEXT;
        } else if(type.contains("ts")){
            returnType = UrlType.TS;
        }

        Log.d(TAG, "getUrlGeckoType: " + type + " url: " + url + " returnType: " + returnType);

        return returnType;
    }


}