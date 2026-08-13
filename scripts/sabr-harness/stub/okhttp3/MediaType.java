package okhttp3;
public class MediaType {
    public final String value;
    private MediaType(String v){ this.value = v; }
    public static MediaType get(String v){ return new MediaType(v); }
    public static MediaType parse(String v){ return new MediaType(v); }
    public String toString(){ return value; }
}
