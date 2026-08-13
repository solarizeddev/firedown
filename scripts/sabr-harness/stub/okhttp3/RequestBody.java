package okhttp3;
public class RequestBody {
    public final byte[] content; public final MediaType type;
    private RequestBody(byte[] c, MediaType t){ content=c; type=t; }
    public static RequestBody create(byte[] c, MediaType t){ return new RequestBody(c,t); }
    public static RequestBody create(MediaType t, byte[] c){ return new RequestBody(c,t); }
}
