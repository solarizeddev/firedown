package okhttp3;
import java.io.*;
import java.util.*;
public class Response implements Closeable {
    private final int code; private final String message;
    private final ResponseBody body; private final Map<String,String> headers;
    public Response(int code, String message, byte[] body, Map<String,String> headers){
        this.code=code; this.message=message; this.body=new ResponseBody(body==null?new byte[0]:body);
        this.headers = headers==null? new LinkedHashMap<>() : headers;
    }
    public static Response ok(byte[] body){ return new Response(200,"OK",body,null); }
    public boolean isSuccessful(){ return code>=200 && code<300; }
    public int code(){ return code; }
    public String message(){ return message; }
    public ResponseBody body(){ return body; }
    public String header(String n){ return headers.get(n); }
    public String header(String n,String d){ return headers.getOrDefault(n,d); }
    public void close(){ body.close(); }
}
