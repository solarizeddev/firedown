package com.solarized.firedown.geckoview;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.solarized.firedown.App;
import com.solarized.firedown.R;

import org.apache.commons.lang3.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Locale;


public class GeckoError {

    private static final String TAG =  GeckoError.class.getSimpleName();

    private static final String HTML_RESOURCE_FILE = "error_page_js.html";


    public enum ErrorType {
        UNKNOWN(
                R.string.errorpages_generic_title,
                R.string.errorpages_generic_message
                ),
        ERROR_SECURITY_SSL(
                R.string.errorpages_security_ssl_title,
                R.string.errorpages_security_ssl_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_SECURITY_BAD_CERT(
                R.string.errorpages_security_bad_cert_title,
                R.string.errorpages_security_bad_cert_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_NET_INTERRUPT(
                R.string.errorpages_net_interrupt_title,
                R.string.errorpages_net_interrupt_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_NET_TIMEOUT(
                R.string.errorpages_net_timeout_title,
                R.string.errorpages_net_timeout_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_CONNECTION_REFUSED(
                R.string.errorpages_connection_failure_title,
                R.string.errorpages_connection_failure_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_UNKNOWN_SOCKET_TYPE(
                R.string.errorpages_unknown_socket_type_title,
                R.string.errorpages_unknown_socket_type_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_REDIRECT_LOOP(
                R.string.errorpages_redirect_loop_title,
                R.string.errorpages_redirect_loop_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_OFFLINE(
                R.string.errorpages_offline_title,
                R.string.errorpages_offline_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_PORT_BLOCKED(
                R.string.errorpages_port_blocked_title,
                R.string.errorpages_port_blocked_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_NET_RESET(
                R.string.errorpages_net_reset_title,
                R.string.errorpages_net_reset_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_UNSAFE_CONTENT_TYPE(
                R.string.errorpages_unsafe_content_type_title,
                R.string.errorpages_unsafe_content_type_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_CORRUPTED_CONTENT(
                R.string.errorpages_corrupted_content_title,
                R.string.errorpages_corrupted_content_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_CONTENT_CRASHED(
                R.string.errorpages_content_crashed_title,
                R.string.errorpages_content_crashed_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_INVALID_CONTENT_ENCODING(
                R.string.errorpages_invalid_content_encoding_title,
                R.string.errorpages_invalid_content_encoding_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_UNKNOWN_HOST(
                R.string.errorpages_unknown_host_title,
                R.string.errorpages_unknown_host_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_NO_INTERNET(
                R.string.errorpages_no_internet_title,
                R.string.errorpages_no_internet_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_MALFORMED_URI(
                R.string.errorpages_malformed_uri_title,
                R.string.errorpages_malformed_uri_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_UNKNOWN_PROTOCOL(
                R.string.errorpages_unknown_protocol_title,
                R.string.errorpages_unknown_protocol_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_FILE_NOT_FOUND(
                R.string.errorpages_file_not_found_title,
                R.string.errorpages_file_not_found_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_FILE_ACCESS_DENIED(
                R.string.errorpages_file_access_denied_title,
                R.string.errorpages_file_access_denied_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_PROXY_CONNECTION_REFUSED(
                R.string.errorpages_proxy_connection_refused_title,
                R.string.errorpages_proxy_connection_refused_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_UNKNOWN_PROXY_HOST(
                R.string.errorpages_unknown_proxy_host_title,
                R.string.errorpages_unknown_proxy_host_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_SAFEBROWSING_MALWARE_URI(
                R.string.errorpages_safe_browsing_malware_uri_title,
                R.string.errorpages_safe_browsing_malware_uri_message
                ),
        ERROR_SAFEBROWSING_UNWANTED_URI(
                R.string.errorpages_safe_browsing_unwanted_uri_title,
                R.string.errorpages_safe_browsing_unwanted_uri_message
                ),
        ERROR_SAFEBROWSING_HARMFUL_URI(
                R.string.errorpages_safe_harmful_uri_title,
                R.string.errorpages_safe_harmful_uri_message
                ),
        ERROR_SAFEBROWSING_PHISHING_URI(
                R.string.errorpages_safe_phishing_uri_title,
                R.string.errorpages_safe_phishing_uri_message
                ),
        ERROR_HTTPS_ONLY(
                R.string.errorpages_httpsonly_title,
                R.string.errorpages_httpsonly_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                ),
        ERROR_BAD_HSTS_CERT(
                R.string.errorpages_security_bad_hsts_cert_title,
                R.string.errorpages_security_bad_hsts_cert_message,
                R.string.error_image_res,
                R.string.error_image_footer_res
                );

        private final int titleRes;
        private final int messageRes;
        private final int refreshButtonRes;
        @Nullable
        private final Integer imageNameRes;

        @Nullable
        private final Integer imageFooterNameRes;

        public final int getTitleRes() {
            return this.titleRes;
        }

        public final int getMessageRes() {
            return this.messageRes;
        }

        public final int getRefreshButtonRes() {
            return this.refreshButtonRes;
        }

        @Nullable
        public final Integer getImageNameRes() {
            return this.imageNameRes;
        }

        @Nullable
        public final Integer getImageFooterNameRes() {
            return this.imageFooterNameRes;
        }

        private ErrorType(@StringRes int titleRes, @StringRes int messageRes, @Nullable @StringRes Integer imageNameRes, @Nullable @StringRes Integer imageFooterNameRes) {
            this.titleRes = titleRes;
            this.messageRes = messageRes;
            this.refreshButtonRes = R.string.errorpages_page_refresh;
            this.imageNameRes = imageNameRes;
            this.imageFooterNameRes = imageFooterNameRes;
        }

        private ErrorType(@StringRes int titleRes, @StringRes int messageRes) {
            this.titleRes = titleRes;
            this.messageRes = messageRes;
            this.refreshButtonRes = R.string.errorpages_page_refresh;
            this.imageNameRes = null;
            this.imageFooterNameRes = null;
        }

    }


    private static String urlEncode(String s) {
        try{
            return URLEncoder.encode(s, Charset.defaultCharset().name());
        }catch(UnsupportedEncodingException e){
            Log.e(TAG, "urlEncode", e);
        }
        return s;
    }

    public static String createUrlEncodedErrorPage(Context context, ErrorType errorType, String uri){
        String title = context.getString(R.string.browser_error_image_title);
        String subtitle = context.getString(errorType.getTitleRes());
        String button = context.getString(errorType.getRefreshButtonRes());
        String description = context.getString(errorType.messageRes, uri);
        String imageName = (errorType.getImageNameRes() != null) ? context.getString(errorType.getImageNameRes()) + ".svg" : "";
        String imageFooterName = (errorType.getImageFooterNameRes() != null) ? context.getString(errorType.getImageFooterNameRes()) + ".svg" : "";
        String continueHttpButton = context.getString(R.string.errorpages_httpsonly_button);
        String badCertAdvanced = context.getString(R.string.errorpages_security_bad_cert_advanced);
        String badCertTechInfo = "";
        if(errorType == ErrorType.ERROR_SECURITY_BAD_CERT){
            badCertTechInfo = String.format(Locale.getDefault(), context.getString(
                            R.string.errorpages_security_bad_cert_techInfo),
                    App.getApplicationName(),
                    uri);
        }else if(errorType == ErrorType.ERROR_BAD_HSTS_CERT){
            badCertTechInfo = String.format(Locale.getDefault(), context.getString(
                            R.string.errorpages_security_bad_hsts_cert_techInfo2),
                    StringUtils.strip(uri, "/"),
                    App.getApplicationName());
        }



        String badCertGoBack = context.getString(R.string.errorpages_security_bad_cert_back);

        String badCertAcceptTemporary = context.getString(R.string.errorpages_security_bad_cert_accept_temporary);

        String showSSLAdvanced = errorType == ErrorType.ERROR_SECURITY_BAD_CERT ?
                String.valueOf(true) : String.valueOf(false);

        String showHSTSAdvanced =  errorType == ErrorType.ERROR_BAD_HSTS_CERT ?
                String.valueOf(true) : String.valueOf(false);

        String showContinueHttp = String.valueOf(errorType == ErrorType.ERROR_HTTPS_ONLY);

        String urlEncodedErrorPage = "resource://android/assets/error/" + HTML_RESOURCE_FILE +"?" +
                "&title=" + urlEncode(title) +
                "&subtitle=" + urlEncode(subtitle) +
                "&button=" + urlEncode(button) +
                "&description=" + urlEncode(description) +
                "&image=" + urlEncode(imageName) +
                "&imageFooter=" + urlEncode(imageFooterName) +
                "&showSSL=" + urlEncode(showSSLAdvanced) +
                "&showHSTS=" + urlEncode(showHSTSAdvanced) +
                "&badCertAdvanced=" + urlEncode(badCertAdvanced) +
                "&badCertTechInfo=" + urlEncode(badCertTechInfo) +
                "&badCertGoBack=" + urlEncode(badCertGoBack) +
                "&badCertAcceptTemporary=" + urlEncode(badCertAcceptTemporary) +
                "&showContinueHttp=" + urlEncode(showContinueHttp) +
                "&continueHttpButton=" + urlEncode(continueHttpButton);

        urlEncodedErrorPage = urlEncodedErrorPage
                .replace(urlEncode("<ul>"), urlEncode("<ul role=\"presentation\">"));

        return urlEncodedErrorPage;

    }

}
