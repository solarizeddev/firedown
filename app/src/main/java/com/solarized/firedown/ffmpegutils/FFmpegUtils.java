package com.solarized.firedown.ffmpegutils;


import android.text.TextUtils;
import android.util.Log;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class FFmpegUtils {

    private static final String TAG = FFmpegUtils.class.getSimpleName();

    private static final String DICT_HEADERS = "headers";

    private static final String SEPARATOR = "\r\n";


    public static int compare(String q2, String q1){
        try{
            return Integer.compare(Integer.parseInt(q2), Integer.parseInt(q1));
        }catch (NumberFormatException e){
            return 0;
        }
    }


    public static Comparator<FFmpegEntity> FFmpegEntityComparator = (o1, o2) -> {
        if(o1.getCodecType() == FFmpegStreamInfo.CodecType.VIDEO.getValue() && o2.getCodecType() == FFmpegStreamInfo.CodecType.VIDEO.getValue()){
            String q1 = o1.getInfo() != null ? CharMatcher.inRange('0', '9').retainFrom(o1.getInfo()) : null;
            String q2 = o2.getInfo() != null ? CharMatcher.inRange('0', '9').retainFrom(o2.getInfo()) : null;
            if(q1 != null && q2 != null){
                return compare(q2, q1);
            }
            return 0;
        }else if(o1.getCodecType() == FFmpegStreamInfo.CodecType.AUDIO.getValue() && o2.getCodecType() == FFmpegStreamInfo.CodecType.AUDIO.getValue()){
            String q1 = o1.getInfo().replace("Khz", "");
            String q2 = o2.getInfo().replace("Khz", "");
            return compare(q2, q1);
        }else{
            String q1 = o1.getInfo() != null  ? CharMatcher.inRange('0', '9').retainFrom(o1.getInfo()) : null;
            String q2 = o2.getInfo() != null ? CharMatcher.inRange('0', '9').retainFrom(o2.getInfo()) : null;
            if(q1 != null && q2 != null){
                return compare(q2, q1);
            }
            return 0;
        }
    };

    public static Map<String, String> buildFFmpegOptions(Map<String, String> headers) {
        if (headers == null)
            headers = new HashMap<>();

        // Ensure default User-Agent
        if (!headers.containsKey(BrowserHeaders.USER_AGENT)) {
            headers.put(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString());
        }

        String ffmpegHeaders = headersToFFmpegString(headers);

        Map<String, String> dict = new HashMap<>();
        dict.put(DICT_HEADERS, ffmpegHeaders);
        return dict;
    }

    public static void setOptions(Map<String, String> dict, String mOptions) {
        if (mOptions != null) {
            Map<String, String> options = Utils.stringToMap(mOptions);
            dict.putAll(options);
        }
    }

    private static String sanitizeValue(String value){
        String sanitizedValue = "";
        if(value != null){
            sanitizedValue = value.replaceAll("\r\n", "");
        }
        return sanitizedValue;
    }

    public static Map<String,String> stringToMap(String headers){

        Map<String, String> headersMap = new HashMap<>();

        if(headers != null){
            String[] headersRaw = headers.split(SEPARATOR);

            for(String s : headersRaw){
                String[] array = s.split("=", 2);
                if(array.length >= 2){
                    String key = array[0];
                    String value = array[1];
                    headersMap.put(key, value);
                }
            }
        }


        return headersMap;
    }

    private static String headersToFFmpegString(Map<String, String> map) {

        List<String> ffmpegList = new ArrayList<>();

        for (String key : map.keySet()) {
            String value = map.get(key);
            if (value != null) {
                value = value.replaceAll(SEPARATOR, "");
            }
            if(!TextUtils.isEmpty(key) && !TextUtils.isEmpty(value)){
                ffmpegList.add(key.trim() + "=" + value.trim());
            }
        }

        ffmpegList.sort(KeySizeComparator);

        String headersFFmpeg = Joiner.on(SEPARATOR).join(ffmpegList);

        Log.d(TAG, "utils_read_dictionary: "+ headersFFmpeg +  "len: " + headersFFmpeg.length());

        return headersFFmpeg.trim();
    }


    private static final Comparator<String> KeySizeComparator = (s, t) -> {
        Integer length1 = s.length();
        Integer length2 = t.length();
        return length1.compareTo(length2);
    };

    public static String getFileDuration(long duration) {
        if (duration <= 0) return "00:00:00.00";

        // Normalize to milliseconds:
        // FFprobe sends microseconds (> 1,000,000 for any video over 1 second)
        // JS sends milliseconds (lengthSeconds * 1000)
        // Heuristic: if value > 1,000,000 per second of a ~1s video, it's likely µs
        long ms;
        if (duration > 10_000_000) {
            // Microseconds — a 10-second video in ms would be 10,000 which is below threshold
            ms = duration / 1000;
        } else {
            // Already milliseconds
            ms = duration;
        }

        long hours = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) - TimeUnit.HOURS.toMinutes(hours);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(ms));

        if (hours > 0 || minutes > 0 || seconds > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        }

        long centis = (ms - TimeUnit.SECONDS.toMillis(TimeUnit.MILLISECONDS.toSeconds(ms))) / 10;
        return String.format(Locale.US, "%02d:%02d:%02d.%02d", hours, minutes, seconds, centis);
    }


}
