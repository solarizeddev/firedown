package com.solarized.firedown.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;


import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.solarized.firedown.Preferences;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {

    private static final String TAG = Utils.class.getSimpleName();

    private static final Pattern NUM_PATTERN = Pattern.compile("\\d+"); // Matches one or more digits

    private final static String NON_THIN = "[^iIl1\\.,']";

    public static boolean isActivityDestroyed(Context context) {
        while (context instanceof android.content.ContextWrapper wrapper) {
            if (context instanceof Activity activity) {
                return activity.isDestroyed() || activity.isFinishing();
            }
            context = wrapper.getBaseContext();
        }
        return false;
    }


    public static void expandTouchArea(View view) {
        int extra = (int) (view.getContext().getResources().getDisplayMetrics().density * Preferences.EXTRA_TOUCH_AREA_DP);
        ((View) view.getParent()).post(() -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            view.getHitRect(rect);
            rect.top -= extra;
            rect.bottom += extra;
            rect.left -= extra;
            rect.right += extra;
            ((View) view.getParent()).setTouchDelegate(new android.view.TouchDelegate(rect, view));
        });
    }

    public static boolean parseInt(String quality){
        try{
            Integer.parseInt(quality);
            return true;
        }catch(NumberFormatException e){
            return false;
        }
    }

    public static String urlEncode(String s) {
        try{
            return URLEncoder.encode(s, "UTF-8");
        }catch(UnsupportedEncodingException e){
            Log.e(TAG, "urlEncode", e);
        }
        return s;
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        Network nw = connectivityManager.getActiveNetwork();
        if (nw == null) return false;
        NetworkCapabilities actNw = connectivityManager.getNetworkCapabilities(nw);
        return actNw != null && (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));

    }

    public static boolean isFileWriteable(File file){
        boolean result = true;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("".getBytes());
        }catch(IOException e){
            Log.e(TAG, "isFileWritable", e);
            result = false;
        }finally {
            file.delete();
        }
        Log.d(TAG, "isFileWritable: " + result);
        return result;
    }

    public static DisplayMetrics getDeviceMetrics(Activity activity){
        DisplayMetrics displayMetrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics;
    }

    public static String getSignature(Method m){
        String sig;
        try {
            Field gSig = Method.class.getDeclaredField("signature");
            gSig.setAccessible(true);
            sig = (String) gSig.get(m);
            if(sig!=null) return sig;
        } catch (IllegalAccessException | NoSuchFieldException e) {
            Log.e(TAG, "getSignature", e);
        }
        StringBuilder sb = new StringBuilder("(");
        for(Class<?> c : m.getParameterTypes())
            sb.append((sig= Array.newInstance(c, 0).toString())
                    .substring(1, sig.indexOf('@')));
        return sb.append(')')
                .append(
                        m.getReturnType()==void.class?"V":
                                (sig=Array.newInstance(m.getReturnType(), 0).toString()).substring(1, sig.indexOf('@'))
                )
                .toString();
    }

    private static int textWidth(String str) {
        return str.length() - str.replaceAll(NON_THIN, "").length() / 2;
    }

    public static String prettyCount(Number number) {
        char[] suffix = {' ', 'k', 'M', 'B', 'T', 'P', 'E'};
        long numValue = number.longValue();
        int value = (int) Math.floor(Math.log10(numValue));
        int base = value / 3;
        if (value >= 3 && base < suffix.length) {
            return new DecimalFormat("#0.0").format(numValue / Math.pow(10, base * 3)) + suffix[base];
        } else {
            return new DecimalFormat("#,##0").format(numValue);
        }
    }

    public static String getUrlWithoutParams(String url){
        if(TextUtils.isEmpty(url))
            return String.valueOf(-1);
        int index = url.indexOf("?");
        if (index != -1) {
            return url.substring(0, index);
        } else {
            return url;
        }
    }

    public static void deleteDirectory(File path) {
        if(path.exists()) {
            File[] files = path.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    boolean wasSuccessful = file.delete();
                    if (wasSuccessful) {
                        Log.i(TAG, "successfully deleted: " + file.getAbsolutePath());
                    }
                }
            }
        }
        FileUtils.deleteQuietly(path);
    }

    public static String capitalize(String str)
    {
        if(str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public static String minimize(String str)
    {
        if(str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    public static int getRandomInt(int min, int max) {
        Random random = new Random();
        return random.nextInt(max - min) + min;
        //I tried another approaches here, still the same result
    }

    public static Bitmap drawableToBitmap (Drawable drawable) {
        Bitmap bitmap = null;

        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            if(bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if(drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    public static Drawable tintDrawable(Context context, int resourceDrawable, int color) {

        Resources resources = context.getResources();

        Drawable drawable = ResourcesCompat.getDrawable(resources, resourceDrawable, null);

        if(drawable != null){
            // Wrap the drawable so that future tinting calls work
            // on pre-v21 devices. Always use the returned drawable.
            drawable = DrawableCompat.wrap(drawable);

            // We can now set a tint
            DrawableCompat.setTint(drawable, ResourcesCompat.getColor(resources, color, null));
        }

        return drawable;

    }

    public static Drawable tintDrawableColor(Context context, int resourceDrawable, int colorInt) {
        Resources resources = context.getResources();
        Drawable drawable = ResourcesCompat.getDrawable(resources, resourceDrawable, null);
        if (drawable != null) {
            drawable = DrawableCompat.wrap(drawable).mutate();
            DrawableCompat.setTint(drawable, colorInt);
        }
        return drawable;
    }

    public static void printStackTrace(String tag){
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for(StackTraceElement element : stackTraceElements){
            Log.d(tag, "SESSION_printStackTrace: " +  element);
        }
    }



    public static String encodeUrl(String urlStr){
        try{
            urlStr = URLDecoder.decode(urlStr, "utf-8");
            URL url = new URL(urlStr);
            String path = url.getPath();
            if(path != null){
                String[] list = path.split("/");
                for(int i = 0; i < list.length; i++){
                    list[i] = URLEncoder.encode(list[i], "utf-8");
                }
                path = String.join("/", list);
            }
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), path, url.getQuery(), url.getRef());
            url = uri.toURL();
            return url.toString();
        }catch(URISyntaxException | MalformedURLException | UnsupportedEncodingException e){
            Log.e(TAG, "encodeUrl", e);
        }
        return urlStr;
    }

    public static String changeExtension(String filePath, String newExtension) {
        int i = filePath.lastIndexOf('.');
        if(i > 0){
            String name = FilenameUtils.getBaseName(filePath) + "." + newExtension;
            return new File(FilenameUtils.getPath(filePath), name).getAbsolutePath();
        }
        return filePath;
    }

    public static String ellipsize(String text, int max) {

        if (textWidth(text) <= max)
            return text;

        // Start by chopping off at the word before max
        // This is an over-approximation due to thin-characters...
        int end = text.lastIndexOf(' ', max - 3);

        // Just one long word. Chop it off.
        if (end == -1)
            return text.substring(0, max-3) + "...";

        // Step forward as long as textWidth allows.
        int newEnd = end;
        do {
            end = newEnd;
            newEnd = text.indexOf(' ', end + 1);

            // No more spaces.
            if (newEnd == -1)
                newEnd = text.length();

        } while (textWidth(text.substring(0, newEnd) + "...") < max);

        return text.substring(0, end) + "...";
    }

    public static String readableFileSize(long size) {
        if(size <= 0) return "0";
        final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size)/Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size/Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }


    public static int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    public static String padLeft(String s, int n) {
        return String.format("%1$" + n + "s", s);
    }

    public static String ellipsizeMiddle(String input, int max, boolean padLeft) {
        if (input == null || input.length() < max) {
            return (padLeft ? padLeft(input, max) : input);
        }//from  w  w  w  .ja  v  a 2  s . co m
        int charactersAfterEllipsis = max / 2;
        int charactersBeforeEllipsis = max - 3 - charactersAfterEllipsis;
        return input.substring(0, charactersBeforeEllipsis) + "..."
                + input.substring(input.length() - charactersAfterEllipsis);
    }


    public static String getMimeTypeFromNameUrl(String mimetype, String url, String name){
        if(FileUriHelper.isMimeTypeForced(mimetype)){
            mimetype = FileUriHelper.getMimeTypeFromFile(url);
            if(FileUriHelper.isMimeTypeForced(mimetype)){
                mimetype = FileUriHelper.getMimeTypeFromFile(name);
            }
        }
        return mimetype;
    }

    public static boolean getRandomBoolean() {
        return Math.random() < 0.5;
        //I tried another approaches here, still the same result
    }

    public static HashMap<String, String> stringToMap(String input) {

        HashMap<String, String> map = new HashMap<>();

        if(input == null || input.isEmpty())
            return map;

        String[] nameValuePairs = input.split("&");
        for (String nameValuePair : nameValuePairs) {
            String[] nameValue = nameValuePair.split("=");
            try {
                map.put(URLDecoder.decode(nameValue[0], "UTF-8"), nameValue.length > 1 ? URLDecoder.decode(
                        nameValue[1], "UTF-8") : "");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("This method requires UTF-8 encoding support", e);
            }
        }

        return map;
    }


    public static String intentToString(Intent intent) {
        if (intent == null) {
            return null;
        }

        return intent.toString() + " " + bundleToString(intent.getExtras());
    }


    public static String bundleToString(Bundle bundle) {
        StringBuilder out = new StringBuilder("Bundle[");

        if (bundle == null) {
            out.append("null");
        } else {
            boolean first = true;
            for (String key : bundle.keySet()) {
                if (!first) {
                    out.append(", ");
                }

                out.append(key).append('=');

                Object value = bundle.get(key);

                if (value instanceof int[]) {
                    out.append(Arrays.toString((int[]) value));
                } else if (value instanceof byte[]) {
                    out.append(Arrays.toString((byte[]) value));
                } else if (value instanceof boolean[]) {
                    out.append(Arrays.toString((boolean[]) value));
                } else if (value instanceof short[]) {
                    out.append(Arrays.toString((short[]) value));
                } else if (value instanceof long[]) {
                    out.append(Arrays.toString((long[]) value));
                } else if (value instanceof float[]) {
                    out.append(Arrays.toString((float[]) value));
                } else if (value instanceof double[]) {
                    out.append(Arrays.toString((double[]) value));
                } else if (value instanceof String[]) {
                    out.append(Arrays.toString((String[]) value));
                } else if (value instanceof CharSequence[]) {
                    out.append(Arrays.toString((CharSequence[]) value));
                } else if (value instanceof Parcelable[]) {
                    out.append(Arrays.toString((Parcelable[]) value));
                } else if (value instanceof Bundle) {
                    out.append(bundleToString((Bundle) value));
                } else {
                    out.append(value);
                }

                first = false;
            }
        }

        out.append("]");
        return out.toString();
    }

    public static String mapToString(Map<String, String> map) {
        StringBuilder stringBuilder = new StringBuilder();
        if(map != null && !map.isEmpty()){
            for (String key : map.keySet()) {
                if(BuildUtils.hasAndroid15()){
                    if (!stringBuilder.isEmpty()) {
                        stringBuilder.append("&");
                    }
                }else{
                    if (stringBuilder.length() > 0) {
                        stringBuilder.append("&");
                    }
                }
                String value = map.get(key);
                try {
                    stringBuilder.append((key != null ? URLEncoder.encode(key, "UTF-8") : ""));
                    stringBuilder.append("=");
                    stringBuilder.append(value != null ? URLEncoder.encode(value, "UTF-8") : "");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("This method requires UTF-8 encoding support", e);
                }
            }
        }
        return stringBuilder.toString();
    }



    public static String reverseIt(String source) {
        if(source == null){
            return "";
        }
        int i, len = source.length();
        StringBuilder dest = new StringBuilder(len);

        for (i = (len - 1); i >= 0; i--){
            dest.append(source.charAt(i));
        }

        return dest.toString();
    }



    public static String arrayListHashMapToString(ArrayList<HashMap<String, String>> list) {
        if (list != null) {
            ArrayList<String> out = new ArrayList<>();
            for(HashMap<String, String> map : list){
                out.add(Utils.mapToString(map));
            }
            return TextUtils.join("\t", out);
        }
        return "";
    }

    public static String arrayListToString(ArrayList<String> list) {
        if (list != null) {
            return TextUtils.join("\t", list);
        }
        return "";
    }

    public static ArrayList<HashMap<String, String>> stringToArrayListHashMap(String string) {
        ArrayList<HashMap<String, String>> out = new ArrayList<>();
        if (string != null) {
            ArrayList<String> list = new ArrayList<>(Arrays.asList(string.split("\t")));
            for(String s : list){
                if(!TextUtils.isEmpty(s))
                    out.add(Utils.stringToMap(s));
            }
        }
        return out;
    }

    public static ArrayList<String> stringToArrayList(String string) {
        ArrayList<String> out = new ArrayList<>();
        if (string != null) {
            return new ArrayList<>(Arrays.asList(string.split("\t")));
        }
        return out;
    }

    public static String getPostDataString(HashMap<String, String> params) throws UnsupportedEncodingException {
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (first)
                first = false;
            else
                result.append("&");

            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            result.append("=");
            result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }

        return result.toString();
    }


    public static String byte2HexFormatted(byte[] arr) {
        StringBuilder str = new StringBuilder(arr.length * 2);
        for (int i = 0; i < arr.length; i++) {
            String h = Integer.toHexString(arr[i]);
            int l = h.length();
            if (l == 1) h = "0" + h;
            if (l > 2) h = h.substring(l - 2, l);
            str.append(h.toUpperCase());
            if (i < (arr.length - 1)) str.append(':');
        }
        return str.toString();
    }


    public static int getWidth(String string) {
        if (TextUtils.isEmpty(string))
            return 0;
        try {
            //48x48
            String[] parts = string.split("x");
            if (parts.length >= 2) {
                String firstValue = parts[0]; // "48"
                return Integer.parseInt(firstValue);
            }
            Matcher matcher = NUM_PATTERN.matcher(string);
            if (matcher.find()) {
                String firstValue = matcher.group(); // "48"
                return Integer.parseInt(firstValue);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "getNumber", e);
        }
        return 0;

    }

    public static int getNumber(String string) {
        if (TextUtils.isEmpty(string))
            return 0;
        try {
            return Integer.parseInt(string.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            Log.e(TAG, "getNumber", e);
        }
        return 0;

    }



    public static String getBitrate(int rate) {
        if (rate <= 0)
            return "0 KB/s";

        final String[] units = new String[] { "B/s", "KB/s", "MB/s", "GB/s", "TB/s" };
        int digitGroups = (int) (Math.log10(rate) / Math.log10(1024));

        return new DecimalFormat("#,##0.#").format(rate / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    public static String getFileSize(long size) {
        if (size <= 0)
            return "0";

        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }



    public static String readInputStream(InputStream inputStream) throws IOException {
        byte[] formArray = new byte[inputStream.available()];
        inputStream.read(formArray);
        inputStream.close();
        return new String(formArray);
    }

    public static String AssetJSONFile(Context context, String filename) throws IOException {
        AssetManager manager = context.getAssets();
        InputStream file = manager.open(filename);
        byte[] formArray = new byte[file.available()];
        file.read(formArray);
        file.close();
        return new String(formArray);
    }


    public static String getJsonAsQueryString(String unparsedString) throws JSONException {
        StringBuilder sb = new StringBuilder();
        JSONObject json = new JSONObject(unparsedString);
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            sb.append(key);
            sb.append("=");
            sb.append(json.get(key));
            sb.append("&"); //To allow for another argument.
        }
        return sb.toString();

    }


}
