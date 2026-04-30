package com.solarized.firedown.data.models;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.settings.ui.ValidationResult;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

@HiltViewModel
public class EditPreferenceViewModel extends ViewModel {
    private final OkHttpClient okHttpClient;
    private final MutableLiveData<ValidationResult> status = new MutableLiveData<>();

    @Inject
    public EditPreferenceViewModel(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    public LiveData<ValidationResult> getStatus() { return status; }

    public void validateDohProvider(String url) {

        //String url = String.format("https://%s/dns-query", ip);

        // 1. A basic client to handle the DoH queries

        String normalizedUrl = appendDnsPath(url);

        // 2. The DNS provider pointing to 1.1.1.1
        Dns dnsProvider = new DnsOverHttps.Builder()
                .client(okHttpClient)
                .url(HttpUrl.get(normalizedUrl))
                .build();

        // 3. The main client using our custom DNS
        OkHttpClient client = new OkHttpClient.Builder()
                .dns(dnsProvider)
                .connectTimeout(5, TimeUnit.SECONDS) // Short timeout for health check
                .build();

        Request request = new Request.Builder()
                .url("https://google.com") // Try to resolve a known domain
                .head()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("DNS_CHECK", "DNS Server "+ url + " is unreachable or failing: " + e.getMessage());
                status.postValue(new ValidationResult(ValidationResult.DohStatus.ERROR, null));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful()) {
                    status.postValue(new ValidationResult(ValidationResult.DohStatus.SUCCESS, normalizedUrl));
                    Log.d("DNS_CHECK", "DNS Server "+ url + " is working perfectly!");
                }else{
                    status.postValue(new ValidationResult(ValidationResult.DohStatus.ERROR, null));
                }
                response.close();
            }
        });
    }

    public String appendDnsPath(String urlString) {
        HttpUrl url = HttpUrl.parse(urlString);
        if (url == null) return urlString; // Or handle invalid URL

        // Check if the last path segment is already "dns-query"
        if (!"dns-query".equals(url.pathSegments().get(url.pathSize() - 1))) {
            return url.newBuilder()
                    .addPathSegment("dns-query")
                    .build()
                    .toString();
        }

        return url.toString();
    }
}