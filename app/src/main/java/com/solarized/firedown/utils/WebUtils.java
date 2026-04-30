package com.solarized.firedown.utils;


import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;


import com.solarized.firedown.data.di.NetworkModule;


import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public class WebUtils {

    private static final String TAG = WebUtils.class.getName();

    private static final Set<String> RANGE_PARAMS = Set.of("bytes", "bytestart", "byteend", "range", "_HLS_msn", "_HLS_part", "start_seq", "r_range");

    private static final Pattern META_PATTERN = Pattern.compile(
            "<meta\\s[^>]*?(?:property|name)\\s*=\\s*[\"']([^\"']*)[\"'][^>]*?content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern META_PATTERN_REVERSED = Pattern.compile(
            "<meta\\s[^>]*?content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*?(?:property|name)\\s*=\\s*[\"']([^\"']*)[\"'][^>]*/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SHREDDIT_PATTERN = Pattern.compile(
            "<shreddit-title\\s[^>]*?title\\s*=\\s*[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE
    );


    public static String bodyToString(final RequestBody request) {
        try {
            if (request == null) {
                return "";
            }
            final Buffer buffer = new Buffer();
            request.writeTo(buffer);
            return buffer.readUtf8();
        } catch (final IOException e) {
            return "did not work";
        }
    }

    /*This is blunter but honest — if it has spaces, a human wrote it.
    If it doesn't, it's probably a URL slug or CDN hash.
    I'd go with this one. Simple, no false positives.*/
    public static boolean isUrlDerivedName(String name) {
        if (TextUtils.isEmpty(name)) return true;
        // Names extracted from URLs never contain spaces
        return !name.contains(" ");
    }

    public static String decodeString(String URL)
    {

        String urlString="";
        try {
            if(BuildUtils.hasAndroidTiramisu()){
                urlString = URLDecoder.decode(URL, StandardCharsets.UTF_8);
            }else{
                urlString = URLDecoder.decode(URL,"UTF-8");
            }
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block

        }
        return urlString;

    }


    public static String deParameterize(String uri) {

        if(uri == null)
            return uri;

        int questionMarkIndex = uri.lastIndexOf('?');

        // If there are no query parameters, return the original URI
        if (questionMarkIndex == -1) {
            return uri;
        }

        String baseUrl = uri.substring(0, questionMarkIndex);
        String queryString = uri.substring(questionMarkIndex + 1);
        String[] params = queryString.split("&");

        StringJoiner newQueryString = new StringJoiner("&");

        for (String p : params) {
            int equalIndex = p.indexOf('=');
            String key = (equalIndex == -1) ? p : p.substring(0, equalIndex);

            // Only add the parameter back if it is NOT in our blacklist
            if (!RANGE_PARAMS.contains(key)) {
                newQueryString.add(p);
            }
        }

        String resultQuery = newQueryString.toString();

        // Return base URL + the filtered query string (if any remains)
        return resultQuery.isEmpty() ? baseUrl : baseUrl + "?" + resultQuery;
    }



    public static String getProtocolUrl(String url1) {
        try {
            URL url = new URL(url1);
            String protocol = url.getProtocol();
            String authority = url.getAuthority();
            return String.format("%s://%s", protocol, authority);
        } catch (MalformedURLException | IllegalArgumentException e) {
            Log.w(TAG, "getProcolUrl", e);
        }
        return url1;
    }


    public static String getString(String url, Map<String, String> headers) {
        Log.d(TAG, "getString: " + url);
        Response httpResponse = null;
        ResponseBody responseBody = null;
        String string = "";
        try {

            Request request = new Request.Builder()
                    .headers(Headers.of(headers))
                    .url(url)
                    .build();

            httpResponse = NetworkModule.requireClient().newCall(request).execute();

            responseBody = httpResponse.body();

            return responseBody.string();

        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            Log.w(TAG, "getString", e);
        } finally {
            if(responseBody != null)
                responseBody.close();
            if (httpResponse != null)
                httpResponse.close();

        }
        return string;
    }


    public static String getTitle(String url) {
        Log.d(TAG, "getTitle: " + url);
        Response httpResponse = null;
        ResponseBody responseBody = null;
        String title = "";
        try {

            Request request = new Request.Builder()
                    .url(url)
                    .build();
            httpResponse = NetworkModule.requireClient().newCall(request).execute();
            responseBody = httpResponse.body();
            Log.d(TAG, "getTitle mimeType: " + responseBody.contentType());
            MediaType mediaType = responseBody.contentType();
            if (mediaType == null || !mediaType.toString().contains(FileUriHelper.MIMETYPE_HTML)) {
                Log.w(TAG, "getTitle incorrect mime");
                return "";
            }
            String html = responseBody.string();

            String ogTitle = null;
            String ogDescription = null;
            String twitterDescription = null;

            Matcher m = META_PATTERN.matcher(html);
            while (m.find()) {
                String key = m.group(1);
                String value = m.group(2);
                if (key == null || value == null) continue;
                switch (key.trim().toLowerCase()) {
                    case "og:title" -> ogTitle = value;
                    case "og:description" -> ogDescription = value;
                    case "twitter:description" -> twitterDescription = value;
                }
            }

            m = META_PATTERN_REVERSED.matcher(html);
            while (m.find()) {
                String value = m.group(1);
                String key = m.group(2);
                if (key == null || value == null) continue;
                key = key.trim().toLowerCase();
                if (ogTitle == null && key.equals("og:title")) ogTitle = value;
                if (ogDescription == null && key.equals("og:description")) ogDescription = value;
                if (twitterDescription == null && key.equals("twitter:description")) twitterDescription = value;
            }

            if (ogTitle != null) title = ogTitle;
            else if (ogDescription != null) title = ogDescription;
            else if (twitterDescription != null) title = twitterDescription;

            m = SHREDDIT_PATTERN.matcher(html);
            if (m.find() && m.group(1) != null) {
                title = m.group(1);
            }

            Log.d(TAG, "WebUtils title: " + title);
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            Log.w(TAG, "getTitle", e);
        } finally {
            if (responseBody != null) responseBody.close();
            if (httpResponse != null) httpResponse.close();
        }
        return title;
    }


    public static String getMimeType(String url, Map<String, String> headers) {
        Response response = null;
        ResponseBody responseBody = null;
        try {

            Request request = new Request.Builder()
                    .headers(Headers.of(headers))
                    .url(url)
                    .build();

            response = NetworkModule.requireClient().newCall(request).execute();

            responseBody = response.body();

            MediaType mediaType = responseBody.contentType();

            if (mediaType == null) {
                return FileUriHelper.MIMETYPE_UNKNOWN;
            }
            return mediaType.toString();
        }catch (IOException e){
            Log.e(TAG, "getMimeType", e);
        } finally {
            if(responseBody != null){
                responseBody.close();
            }
            if(response != null)
                response.close();
        }
        return FileUriHelper.MIMETYPE_UNKNOWN;

    }


    public static String getFileNameFromURL(String url) {

        try {

            if (TextUtils.isEmpty(url)) {
                return UUID.randomUUID().toString();
            }

            URL resource = new URL(url);
            String host = resource.getHost();
            if (!host.isEmpty() && url.endsWith(host)) {
                // handle ...example.com
                return UUID.randomUUID().toString();
            }

            // find end index for ?
            int lastQMPos = url.lastIndexOf('?');
            if (lastQMPos > 0) {
                url = url.substring(0, lastQMPos);
            }

            // find end index for #
            int lastHashPos = url.lastIndexOf('#');
            if (lastHashPos > 0) {
                url = url.substring(0, lastHashPos);
            }

            int startIndex = url.lastIndexOf('/') + 1;

            if(startIndex > 0){
                url = url.substring(startIndex);
            }


            if(TextUtils.isEmpty(url)){
                return UUID.randomUUID().toString();
            }

            return url;

        } catch (MalformedURLException | StringIndexOutOfBoundsException e) {
            Log.w(TAG, "getFileNameFromURL", e);
        }
        return UUID.randomUUID().toString();
    }


    public static String getFileNameFromDisposition(String content){

        Log.d(TAG, "getFileNameFromDisposition: " + content);

        if(content != null && content.contains("filename=")){
            return content.replaceFirst("(?i)^.*filename=\"?([^\"]+)\"?.*$", "$1");
        }

        return null;
    }


    public static String getMimeType(ResponseBody body) {
        if (body == null) {
            return FileUriHelper.MIMETYPE_UNKNOWN;
        }
        MediaType mediaType = body.contentType();
        if (mediaType == null) {
            return FileUriHelper.MIMETYPE_UNKNOWN;
        }
        String mime = mediaType.toString();
        int index = mime.indexOf(";");
        if(index > 0){
            mime = mime.substring(0, index);
        }
        return mime;
    }


    public static String postContent(String url, String post, Map<String, String> headers) throws IOException, IllegalArgumentException {
        Response httpResponse = null;
        try {

            RequestBody reqbody = null;

            if (TextUtils.isEmpty(post)) {
                reqbody = RequestBody.create(new byte[0], null);
            } else {
                reqbody = RequestBody.create(post, MediaType.parse("text/plain"));
            }

            Request request = new Request.Builder()
                    .headers(Headers.of(headers))
                    .url(url)
                    .post(reqbody)
                    .header("Content-Length", String.valueOf(reqbody.contentLength()))
                    .build();

            httpResponse = NetworkModule.requireClient().newCall(request).execute();

            ResponseBody responseBody = httpResponse.body();

            return responseBody.string();

        } finally {
            if (httpResponse != null)
                httpResponse.close();
        }
    }


    public static String getDomainName(String url) {
        try{
            if(!URLUtil.isValidUrl(url))
                return url;
            URI uri = new URI(url);
            String host = uri.getHost();
            return host.replaceFirst("^(www\\.)", "");
        }catch(NullPointerException | URISyntaxException | IllegalStateException | IllegalArgumentException e){
            try{
                URL aurl = new URL(url);
                return aurl.getHost();
            }catch(MalformedURLException e1){
                Log.e(TAG,"getDomainName", e1);
            }

        }
        return url;
    }

    public static boolean isBlob(String url){
        return !TextUtils.isEmpty(url) && url.startsWith("blob:");
    }

    public static long getLengthFromHeaders(String contentLength){
        try{
            if(contentLength != null){
                return Long.parseLong(contentLength);
            }
        }catch (NumberFormatException e){
            Log.w(TAG, "getLengthFromHeaders");
        }
        return 0;
    }

    public static String getUriPath(String url){
        try{
            URI uri = new URI(url);
            return uri.getPath();
        }catch(NullPointerException | URISyntaxException e){
            return url.replaceFirst("^(http[s]?://www\\.|http[s]?://|www\\.)","");
        }
    }

    public static String getSchemeDomainName(String url) {
        try{
            URL aurl = new URL(url);
            String authority = aurl.getAuthority();
            String protocol = aurl.getProtocol();
            Log.d(TAG, "getSchemeDomainName:" + authority + " protocol: " + protocol);
            if (protocol != null && authority != null) {
                return String.format("%s://%s", protocol, authority);
            } else {
                return url;
            }
        }catch(MalformedURLException  e){
            Log.e(TAG, "getSchemeDomainName", e);
        }
        return url;
    }


    public static String getUriNoScheme(String url) {
        try{
            URI uri = new URI(url);
            return uri.getHost();
        }catch(NullPointerException | URISyntaxException e){
            try{
                URL aurl = new URL(url);
                return aurl.getHost();
            }catch(MalformedURLException e1){
                Log.e(TAG,"getDomainName", e1);
            }

        }
        return url;
    }


}
