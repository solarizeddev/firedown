package org.json;
import java.util.*;
public class JSONObject {
    private final Map<String,Object> m = new LinkedHashMap<>();
    public JSONObject put(String k, Object v) throws JSONException { m.put(k,v); return this; }
    public JSONObject put(String k, boolean v) throws JSONException { m.put(k,v); return this; }
    public String optString(String k, String d){ Object v=m.get(k); return v==null?d:String.valueOf(v); }
    public boolean optBoolean(String k, boolean d){ Object v=m.get(k); return v instanceof Boolean?(Boolean)v:d; }
    public String toString(){ return m.toString(); }
}
