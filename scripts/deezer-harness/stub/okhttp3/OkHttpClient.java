package okhttp3;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable stand-in: the test installs a Responder that answers each request. */
public class OkHttpClient {
    public interface Responder { Response respond(Request request, int callIndex) throws IOException; }
    public static volatile Responder responder = (r,i) -> Response.ok(new byte[0]);
    public static final AtomicInteger calls = new AtomicInteger();
    public static void reset(){ calls.set(0); responder = (r,i) -> Response.ok(new byte[0]); }

    public OkHttpClient(){}
    public Builder newBuilder(){ return new Builder(); }
    public Call newCall(Request request){
        return new Call() {
            public Response execute() throws IOException {
                return responder.respond(request, calls.getAndIncrement());
            }
            public void cancel(){}
        };
    }
    public static class Builder {
        public Builder readTimeout(long t, TimeUnit u){ return this; }
        public Builder writeTimeout(long t, TimeUnit u){ return this; }
        public Builder connectTimeout(long t, TimeUnit u){ return this; }
        public Builder callTimeout(long t, TimeUnit u){ return this; }
        public Builder retryOnConnectionFailure(boolean b){ return this; }
        public OkHttpClient build(){ return new OkHttpClient(); }
    }
}
