package android.net;
import java.net.URI;
import java.util.*;
/** Functional stub: enough of android.net.Uri for path segments + one query param. */
public class Uri {
    private final URI u;
    private Uri(URI u){ this.u = u; }
    public static Uri parse(String s){ return new Uri(URI.create(s)); }
    public List<String> getPathSegments(){
        List<String> out = new ArrayList<>();
        String p = u.getPath(); if (p == null) return out;
        for (String s : p.split("/")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    public String getQueryParameter(String key){
        String q = u.getRawQuery(); if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(key)) return eq < 0 ? "" : pair.substring(eq + 1);
        }
        return null;
    }
}
