package android.content;

import java.util.HashMap;
import java.util.Map;

/** An in-memory SharedPreferences: apply() is immediate, which is what the code relies on in-process. */
public class SharedPreferences {
    public final Map<String, Object> values = new HashMap<>();

    public long getLong(String k, long d) { Object v = values.get(k); return v == null ? d : (Long) v; }
    public int getInt(String k, int d) { Object v = values.get(k); return v == null ? d : (Integer) v; }
    public String getString(String k, String d) { Object v = values.get(k); return v == null ? d : (String) v; }
    public boolean getBoolean(String k, boolean d) { Object v = values.get(k); return v == null ? d : (Boolean) v; }
    public boolean contains(String k) { return values.containsKey(k); }
    public Editor edit() { return new Editor(); }

    public class Editor {
        private final Map<String, Object> puts = new HashMap<>();
        private final java.util.Set<String> removes = new java.util.HashSet<>();
        public Editor putLong(String k, long v) { puts.put(k, v); removes.remove(k); return this; }
        public Editor putInt(String k, int v) { puts.put(k, v); removes.remove(k); return this; }
        public Editor putString(String k, String v) { puts.put(k, v); removes.remove(k); return this; }
        public Editor putBoolean(String k, boolean v) { puts.put(k, v); removes.remove(k); return this; }
        public Editor remove(String k) { removes.add(k); puts.remove(k); return this; }
        public void apply() { for (String k : removes) { values.remove(k); } values.putAll(puts); }
        public boolean commit() { apply(); return true; }
    }
}
