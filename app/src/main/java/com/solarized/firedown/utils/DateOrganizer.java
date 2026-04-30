package com.solarized.firedown.utils;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;

import org.apache.commons.lang3.time.DateUtils;

import java.util.Date;

public class DateOrganizer {

    public static final int CAT_NONE = 0;
    public static final int CAT_TODAY = 1;
    public static final int CAT_YESTERDAY = 2;
    public static final int CAT_WEEK = 3;
    public static final int CAT_MONTH = 4;
    public static final int CAT_OLDER = 5;

    boolean isWeek;
    boolean isMonth;
    boolean isToday;
    boolean isYesterday;
    boolean isOlder;

    public void reset(){
        isMonth = isOlder = isToday = isWeek = isYesterday = false;
    }

    public int getCategory(long milliseconds) {
        long now = System.currentTimeMillis();
        long todayStart = now - (now % Preferences.ONE_DAY_INTERVAL);
        long yesterdayStart = todayStart - Preferences.ONE_DAY_INTERVAL;
        long weekStart = todayStart - Preferences.ONE_WEEK_INTERVAL;
        long monthStart = todayStart - Preferences.THIRTY_DAYS_INTERVAL;

        if (milliseconds >= todayStart) return CAT_TODAY;
        if (milliseconds >= yesterdayStart) return CAT_YESTERDAY;
        if (milliseconds >= weekStart) return CAT_WEEK;
        if (milliseconds >= monthStart) return CAT_MONTH;
        return CAT_OLDER;
    }

    public int getResIdForCategory(int category) {
        return switch (category) {
            case CAT_TODAY -> R.string.interval_today;
            case CAT_YESTERDAY -> R.string.interval_yesterday;
            case CAT_WEEK -> R.string.interval_week;
            case CAT_MONTH -> R.string.interval_month;
            case CAT_OLDER -> R.string.interval_older;
            default -> 0;
        };
    }

    public boolean isSameDay(long before, long after){
        return DateUtils.isSameDay(new Date(before), new Date(after));
    }


    public boolean isToday(long milliseconds) {
        if(!isToday){
            long now = System.currentTimeMillis();
            long todayStart = now - (now % Preferences.ONE_DAY_INTERVAL);
            boolean result = milliseconds >= todayStart;
            if(result) isToday = true;
            return result;
        }
        return false;
    }


    public boolean isYesterday(long milliseconds) {
        if(!isYesterday){
            long now = System.currentTimeMillis();
            long todayStart = now - (now % Preferences.ONE_DAY_INTERVAL);
            long yesterdayStart = todayStart - Preferences.ONE_DAY_INTERVAL;
            boolean result = milliseconds >= yesterdayStart && milliseconds < todayStart;
            if(result) isYesterday = true;
            return result;
        }
        return false;
    }

    public boolean isSevenDaysRange(long milliseconds){
        if(!isWeek){
            long now = System.currentTimeMillis();
            long todayStart = now - (now % Preferences.ONE_DAY_INTERVAL);
            long yesterdayStart = todayStart - Preferences.ONE_DAY_INTERVAL;
            long weekStart = todayStart - Preferences.ONE_WEEK_INTERVAL;
            boolean result = milliseconds >= weekStart && milliseconds < yesterdayStart;
            if(result) isWeek = true;
            return result;
        }
        return false;
    }

    public boolean isThirtyDaysRange(long milliseconds){
        if(!isMonth){
            long now = System.currentTimeMillis();
            long todayStart = now - (now % Preferences.ONE_DAY_INTERVAL);
            long weekStart = todayStart - Preferences.ONE_WEEK_INTERVAL;
            long monthStart = todayStart - Preferences.THIRTY_DAYS_INTERVAL;
            boolean result = milliseconds >= monthStart && milliseconds < weekStart;
            if(result) isMonth = true;
            return result;
        }
        return false;

    }

    public boolean isOlder(long milliseconds){
        if(!isOlder){
            long now = System.currentTimeMillis();
            long todayStart = now - (now % Preferences.ONE_DAY_INTERVAL);
            long monthStart = todayStart - Preferences.THIRTY_DAYS_INTERVAL;
            boolean result = milliseconds < monthStart;
            if(result) isOlder = true;
            return result;
        }
        return false;

    }
}
