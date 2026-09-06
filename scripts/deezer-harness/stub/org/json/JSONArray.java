package org.json;
import java.util.*;
public class JSONArray {
    final ArrayList<Object> list = new ArrayList<>();
    public JSONArray(){}
    public JSONArray put(Object v){ list.add(v); return this; }
    public int length(){ return list.size(); }
    public JSONObject getJSONObject(int i) throws JSONException {
        Object v = list.get(i); if (!(v instanceof JSONObject)) throw new JSONException("not an object at " + i); return (JSONObject) v;
    }
    public JSONObject optJSONObject(int i){ Object v = i < list.size() ? list.get(i) : null; return v instanceof JSONObject ? (JSONObject) v : null; }
    @Override public String toString(){ StringBuilder b = new StringBuilder(); JSONObject.write(this, b); return b.toString(); }
}
