package androidx.work;
public final class Data {
    final java.util.Map<String, Object> map = new java.util.HashMap<>();
    public long getLong(String k, long d) { Object v = map.get(k); return v == null ? d : (Long) v; }
    public static final class Builder {
        private final Data d = new Data();
        public Builder putLong(String k, long v) { d.map.put(k, v); return this; }
        public Builder putBoolean(String k, boolean v) { d.map.put(k, v); return this; }
        public Builder putString(String k, String v) { d.map.put(k, v); return this; }
        public Data build() { return d; }
    }
}
