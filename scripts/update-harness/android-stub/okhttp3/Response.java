package okhttp3;
public final class Response implements java.io.Closeable {
    public boolean isSuccessful() { return false; }
    public int code() { return 0; }
    public ResponseBody body() { return null; }
    public void close() {}
}
