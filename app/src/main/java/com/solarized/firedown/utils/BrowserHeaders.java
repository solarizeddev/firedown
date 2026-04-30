package com.solarized.firedown.utils;


import java.util.Objects;

import okhttp3.Request;

public class BrowserHeaders {

    private static final String TAG = BrowserHeaders.class.getSimpleName();


    private static final String USER_AGENT_STRING = "Mozilla/5.0 (Windows NT 10.0; rv:122.0) Gecko/20100101 Firefox/149.0";

    public static final String ACCEPT = "Accept";

    public static final String ACCEPT_ENCODING = "Accept-Encoding";

    public static final String ACCEPT_LANGUAGE = "Accept-Language";
    public static final String ACCEPT_RANGES = "Accept-Ranges";

    public static final String RANGES = "Range";

    public static final String COOKIE = "Cookie";

    public static final String LOCATION = "Location";

    public static final String CONNECTION = "Connection";

    public static final String CONTENT_ENCODING = "Content-Encoding";

    public static final String CONTENT_LENGTH = "Content-Length";

    public static final String CONTENT_DISPOSITION = "Content-Disposition";

    public static final String CONTENT_TYPE = "Content-Type";

    public static final String HOST = "Host";

    public static final String REFERER = "Referer";

    public static final String ORIGIN = "Origin";

    public static final String USER_AGENT = "User-Agent";

    public static final String X_REQUEST_ID = "X-Request-ID";

    public static final String X_APP_VERSION = "X-App-Version-Code";
    public static final String SEC_FETCH_SITE = "Sec-Fetch-Site";


    public static boolean isSecSameSite(Request request){
        return Objects.equals(request.header(BrowserHeaders.SEC_FETCH_SITE), "same-origin");
    }

    public static boolean hasHeader(Request request, String value){
        return request.header(value) != null;
    }

    public static String getDefaultUserAgentString() {
            return USER_AGENT_STRING;
    }


}