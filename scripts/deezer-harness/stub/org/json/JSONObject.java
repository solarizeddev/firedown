package org.json;
import java.util.*;
public class JSONObject {
    static final Object NULL = new Object();
    final LinkedHashMap<String,Object> map = new LinkedHashMap<>();
    public JSONObject(){}
    public JSONObject(String s) throws JSONException {
        Object v = new Parser(s).value();
        if (!(v instanceof JSONObject)) throw new JSONException("not an object");
        map.putAll(((JSONObject) v).map);
    }
    public JSONObject optJSONObject(String k){ Object v = map.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k){ Object v = map.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public String optString(String k, String d){ Object v = map.get(k); return (v == null || v == NULL) ? d : String.valueOf(v); }
    public JSONObject put(String k, Object v) throws JSONException { map.put(k, v); return this; }
    @Override public String toString(){ StringBuilder b = new StringBuilder(); write(this, b); return b.toString(); }

    static void write(Object v, StringBuilder b){
        if (v == null || v == NULL) { b.append("null"); return; }
        if (v instanceof JSONObject) {
            b.append('{'); boolean first = true;
            for (Map.Entry<String,Object> e : ((JSONObject) v).map.entrySet()) {
                if (!first) b.append(','); first = false;
                str(e.getKey(), b); b.append(':'); write(e.getValue(), b);
            }
            b.append('}'); return;
        }
        if (v instanceof JSONArray) {
            b.append('['); boolean first = true;
            for (Object o : ((JSONArray) v).list) { if (!first) b.append(','); first = false; write(o, b); }
            b.append(']'); return;
        }
        if (v instanceof String) { str((String) v, b); return; }
        b.append(String.valueOf(v));
    }
    static void str(String s, StringBuilder b){
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }

    static final class Parser {
        final String s; int i = 0;
        Parser(String s){ this.s = s; }
        void ws(){ while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() throws JSONException { ws(); if (i >= s.length()) throw new JSONException("eof"); return s.charAt(i); }
        void expect(char c) throws JSONException { if (peek() != c) throw new JSONException("expected " + c + " at " + i); i++; }
        Object value() throws JSONException {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new JSONException("bad token at " + i);
            String n = s.substring(st, i);
            try { return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n); }
            catch (NumberFormatException e) { throw new JSONException("bad number " + n); }
        }
        JSONObject object() throws JSONException {
            JSONObject o = new JSONObject(); expect('{');
            if (peek() == '}') { i++; return o; }
            while (true) { String k = string(); expect(':'); o.map.put(k, value()); char c = peek(); i++; if (c == '}') return o; if (c != ',') throw new JSONException("expected , or } at " + i); }
        }
        JSONArray array() throws JSONException {
            JSONArray a = new JSONArray(); expect('[');
            if (peek() == ']') { i++; return a; }
            while (true) { a.list.add(value()); char c = peek(); i++; if (c == ']') return a; if (c != ',') throw new JSONException("expected , or ] at " + i); }
        }
        String string() throws JSONException {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new JSONException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break; case '\\': b.append('\\'); break; case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break; case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: throw new JSONException("bad escape \\" + e);
                }
            }
        }
    }
}
