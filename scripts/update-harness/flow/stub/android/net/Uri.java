package android.net;
public final class Uri {
    private final String s;
    private Uri(String s) { this.s = s; }
    public static Uri parse(String s) {
        if (s == null || !s.contains("://")) { throw new IllegalArgumentException("Can only download HTTP/HTTPS URIs: " + s); }
        return new Uri(s);
    }
    @Override public String toString() { return s; }
}
