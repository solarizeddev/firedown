package okhttp3;
import java.io.*;
public class ResponseBody implements Closeable {
    private final byte[] data;
    public ResponseBody(byte[] d){ data = d; }
    public InputStream byteStream(){ return new ByteArrayInputStream(data); }
    public byte[] bytes(){ return data; }
    public long contentLength(){ return data.length; }
    public String string(){ return new String(data); }
    public void close(){}
}
