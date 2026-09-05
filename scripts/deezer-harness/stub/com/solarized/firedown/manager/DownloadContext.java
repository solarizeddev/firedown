package com.solarized.firedown.manager;
import java.io.File; import java.util.Map;
import okhttp3.OkHttpClient;
public class DownloadContext {
    public OkHttpClient client = new OkHttpClient(); public File outputFile; public boolean interrupted; public Map<String,String> headers;
    public OkHttpClient getOkHttpClient(){ return client; }
    public File getOutputFile(){ return outputFile; }
    public boolean isInterrupted(){ return interrupted; }
    public Map<String,String> getHeaders(){ return headers; }
}
