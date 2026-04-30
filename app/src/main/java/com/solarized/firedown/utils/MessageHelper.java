package com.solarized.firedown.utils;

import com.solarized.firedown.R;

import java.net.HttpURLConnection;

public class MessageHelper {

    public static final int NO_ERROR = 0;

    public static final int IOEXCEPTION = 1;

    public static final int MALFORMED_URL = 2;

    public static final int NOSPACELEFT = 3;

    public static final int CHECK_INTERNET_CONNECTION = 4;

    public static final int EXTERNAL_STORAGE = 5;

    public static final int BAD_REQUEST = 6;

    public static final int NEEDS_LOGIN = 7;

    public static final int LOCAL_IOEXCEPTION = 8;

    public static final int SECURITY_EXCEPTION = 9;

    public static final int CIPHER_EXCEPTION = 10;

    public static final int SECURITY_IO_EXCEPTION = 11;

    public static final int FILE_NOT_FOUND = 12;

    public static final int ENOSPC = 13;

    public static final int EEXIST = 14;

    public static int getResourceIdFromCode(int error) {
        return switch (error) {
            case BAD_REQUEST, HttpURLConnection.HTTP_BAD_REQUEST -> R.string.error_http_400;
            case HttpURLConnection.HTTP_UNAUTHORIZED -> R.string.error_http_401;
            case HttpURLConnection.HTTP_FORBIDDEN -> R.string.error_http_403;
            case HttpURLConnection.HTTP_NOT_FOUND -> R.string.error_http_404;
            case HttpURLConnection.HTTP_CLIENT_TIMEOUT -> R.string.error_http_408;
            case HttpURLConnection.HTTP_INTERNAL_ERROR -> R.string.error_http_500;
            case HttpURLConnection.HTTP_BAD_GATEWAY -> R.string.error_http_502;
            case HttpURLConnection.HTTP_UNAVAILABLE -> R.string.error_http_503;
            case NOSPACELEFT, ENOSPC -> R.string.error_no_space_left;
            case MALFORMED_URL -> R.string.error_malformed_url;
            case IOEXCEPTION -> R.string.error_io_exception;
            case EEXIST -> R.string.error_file_exists;
            case CHECK_INTERNET_CONNECTION -> R.string.error_check_internet_connection;
            case EXTERNAL_STORAGE -> R.string.error_external_storage;
            case LOCAL_IOEXCEPTION -> R.string.error_opening_file;
            case SECURITY_EXCEPTION -> R.string.error_security;
            case CIPHER_EXCEPTION -> R.string.error_cipher;
            case SECURITY_IO_EXCEPTION -> R.string.error_security_io_exception;
            case FILE_NOT_FOUND -> R.string.error_file_not_found;
            case NEEDS_LOGIN -> R.string.error_needs_login;
            default -> R.string.error_http_400;
        };

    }


}
