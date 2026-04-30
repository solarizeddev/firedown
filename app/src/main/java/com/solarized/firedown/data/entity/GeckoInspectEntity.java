package com.solarized.firedown.data.entity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Map;
public class GeckoInspectEntity {
    private String name;
    private String origin;
    private String description;
    private String url;
    private String img;
    private String geckoType;
    private String requestId;
    private JSONObject message;
    private int tabId;
    private Map<String, String> requestHeaders;
    private ArrayList<FFmpegEntity> variants;
    // SABR shared data (same for all variants of a video)
    private String sabrUrl;
    private String sabrConfig;
    private String sabrPoToken;
    private String sabrClientVersion;
    // Duration in milliseconds from innertube (lengthSeconds * 1000)
    private long duration;
    private boolean incognito;
    public String getRequestId() {
        return requestId;
    }
    public int getTabId() {
        return tabId;
    }
    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }
    public String getDescription() {
        return description;
    }
    public String getGeckoType() {
        return geckoType;
    }
    public String getName() {
        return name;
    }
    public String getOrigin() {
        return origin;
    }
    public String getUrl() {
        return url;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public void setGeckoType(String geckoType) {
        this.geckoType = geckoType;
    }
    public void setName(String name) {
        this.name = name;
    }
    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    public void setTabId(int tabId) {
        this.tabId = tabId;
    }
    public void setUrl(String url) {
        this.url = url;
    }
    public void setOrigin(String origin) {
        this.origin = origin;
    }
    public void setImg(String img) {
        this.img = img;
    }
    public String getImg() {
        return img;
    }
    public JSONObject getMessage() {
        return message;
    }
    public void setMessage(JSONObject message) {
        this.message = message;
    }
    public ArrayList<FFmpegEntity> getVariants() {
        return variants;
    }
    public void setVariants(ArrayList<FFmpegEntity> variants) {
        this.variants = variants;
    }
    public String getSabrUrl() {
        return sabrUrl;
    }
    public void setSabrUrl(String sabrUrl) {
        this.sabrUrl = sabrUrl;
    }
    public String getSabrConfig() {
        return sabrConfig;
    }
    public void setSabrConfig(String sabrConfig) {
        this.sabrConfig = sabrConfig;
    }
    public String getSabrPoToken() {
        return sabrPoToken;
    }
    public void setSabrPoToken(String sabrPoToken) {
        this.sabrPoToken = sabrPoToken;
    }
    public String getSabrClientVersion() {
        return sabrClientVersion;
    }
    public void setSabrClientVersion(String v) {
        this.sabrClientVersion = v;
    }
    public long getDuration() {
        return duration;
    }
    public void setDuration(long duration) {
        this.duration = duration;
    }
    public boolean isIncognito() {
        return incognito;
    }
    public void setIncognito(boolean incognito) {
        this.incognito = incognito;
    }
}