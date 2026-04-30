package com.solarized.firedown.utils;

import android.content.Context;

import com.solarized.firedown.R;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class DateUtils {

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
