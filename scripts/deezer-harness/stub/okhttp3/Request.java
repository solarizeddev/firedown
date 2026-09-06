package okhttp3;
import java.util.*;
public class Request {
    public final String url; public final RequestBody body; public final Map<String,String> headers;
    private Request(Builder b){ url=b.url; body=b.body; headers=b.headers; }
    public String url(){ return url; }
    public String header(String n){ return headers.get(n); }
    public static class Builder {
        String url; RequestBody body; final Map<String,String> headers = new LinkedHashMap<>();
        public Builder url(String u){ this.url=u; return this; }
        public Builder post(RequestBody b){ this.body=b; return this; }
        public Builder addHeader(String n,String v){ headers.put(n,v); return this; }
        public Builder header(String n,String v){ headers.put(n,v); return this; }
        public Request build(){ return new Request(this); }
    }
}
