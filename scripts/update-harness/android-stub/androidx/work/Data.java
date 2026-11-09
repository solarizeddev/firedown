package androidx.work;
public final class Data {
    public long getLong(String k, long d) { return d; }
    public String getString(String k) { return null; }
    public static final class Builder {
        public Builder putLong(String k, long v) { return this; }
        public Builder putString(String k, String v) { return this; }
        public Builder putBoolean(String k, boolean v) { return this; }
        public Data build() { return null; }
    }
}
