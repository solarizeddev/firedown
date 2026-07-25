package com.solarized.firedown.utils;

import android.content.Context;

import com.solarized.firedown.R;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class DateUtils {

    /**
     * Trims the padding off a stored {@code HH:MM:SS} duration for display:
     * zero hours drop entirely, and the leading field loses its zero pad —
     * {@code 00:00:39} → {@code 0:39}, {@code 01:10:27} → {@code 1:10:27}.
     * Hours survive whenever they're nonzero, so nothing becomes ambiguous.
     *
     * <p>Lives here, shared, because BOTH surfaces that show a duration must
     * trim it the same way: the Downloads row/tile (via
     * {@code DownloadItemAdapter.secondaryMetaLabel}) and the Captured sheet's
     * duration tag (via {@code BrowserOptionAdapter.bindSingleTag}). They read
     * from different sources — an entity field and a persisted
     * {@code FFmpegTagEntity} — so a private copy in one adapter is exactly how
     * the two drifted apart the first time.
     *
     * <p>Display only. The stored value is untouched: it is also what
     * {@code DownloadDiffCallback} compares, what the post-download metadata
     * refresh rewrites, and what the info dialog shows in full (a detail view,
     * not a scan surface). Anything not in the three-field shape is returned
     * verbatim rather than guessed at.
     */
    public static String compactDuration(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        String[] parts = formatted.split(":");
        if (parts.length != 3) {
            return formatted;
        }
        int first = "00".equals(parts[0]) ? 1 : 0;
        StringBuilder out = new StringBuilder(formatted.length());
        for (int i = first; i < parts.length; i++) {
            if (i > first) {
                out.append(':');
            }
            String part = parts[i];
            if (i == first && part.length() == 2 && part.charAt(0) == '0') {
                part = part.substring(1);
            }
            out.append(part);
        }
        return out.toString();
    }

    public static String getFileDate(long date){
        Date lastModDate = new Date(date);
        return SimpleDateFormat.getDateInstance().format(lastModDate);
    }

    public static String getFileDateSimple(Context context, long timestamp, Locale locale){
        Date lastModDate = new Date(timestamp);
        if(isNow(timestamp)){
            return context.getString(R.string.interval_now);
        }else if(isWithin(timestamp, TimeUnit.HOURS.toMillis(1))){
            int minutes = convertDeltaToMinutes(timestamp);
            return context.getString(R.string.interval_minutes_ago, minutes);
        }else if(isWithin(timestamp, TimeUnit.DAYS.toMillis(1))){
            int hours = convertDeltaToHours(timestamp);
            return context.getResources().getQuantityString(R.plurals.hours_ago, hours, hours);
        }else if(isWithin(timestamp, TimeUnit.DAYS.toMillis(6))){
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEE", locale);
            return simpleDateFormat.format(lastModDate);
        }else if(isWithin(timestamp, TimeUnit.DAYS.toMillis(365))){
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MMM d", locale);
            return simpleDateFormat.format(lastModDate);
        }else{
            return SimpleDateFormat.getDateInstance(DateFormat.MEDIUM).format(lastModDate);
        }

    }

    private static int convertDeltaToMinutes(long timestamp) {
        return (int)TimeUnit.MILLISECONDS.toMinutes((System.currentTimeMillis() - timestamp));
    }

    private static int convertDeltaToHours(long timestamp) {
        return (int)TimeUnit.MILLISECONDS.toHours((System.currentTimeMillis() - timestamp));
    }

    private static boolean isNow(long timestamp){
        return isWithin(timestamp, TimeUnit.MINUTES.toMillis(1));
    }

    private static boolean isWithin(long timestamp, long duration) {
        return System.currentTimeMillis() - timestamp <= duration;
    }
}
