package com.solarized.firedown.sync;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * A scripted mint for JVM tests: an OkHttp interceptor that answers every call
 * from a handler instead of the network, recording each request's path and
 * body. Real {@link MintClient} on top, so the parsing, the slug switch and
 * the retry interceptor are the shipped ones.
 */
final class FakeMint {

    static final class Recorded {
        final String path;
        final String body;
        Recorded(String path, String body) {
            this.path = path;
            this.body = body;
        }
    }

    static final class Reply {
        final int code;
        final String json;
        Reply(int code, String json) {
            this.code = code;
            this.json = json;
        }
    }

    final List<Recorded> requests = new ArrayList<>();
    private final Function<Recorded, Reply> handler;

    FakeMint(Function<Recorded, Reply> handler) {
        this.handler = handler;
    }

    OkHttpClient client() {
        return new OkHttpClient.Builder().addInterceptor(new Interceptor() {
            @NonNull
            @Override
            public Response intercept(@NonNull Chain chain) throws IOException {
                Request req = chain.request();
                String body = "";
                if (req.body() != null) {
                    Buffer buf = new Buffer();
                    req.body().writeTo(buf);
                    body = buf.readUtf8();
                }
                Recorded rec = new Recorded(req.url().encodedPath(), body);
                requests.add(rec);
                Reply reply;
                try {
                    reply = handler.apply(rec);
                } catch (PaymentNetworkTest.UncheckedIo cut) {
                    // A scripted network failure: surfaces to the client exactly
                    // as OkHttp would surface a dropped socket.
                    throw (IOException) cut.getCause();
                }
                return new Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(reply.code)
                        .message(reply.code >= 400 ? "error" : "OK")
                        .body(ResponseBody.create(reply.json.getBytes(StandardCharsets.UTF_8),
                                MediaType.parse("application/json")))
                        .build();
            }
        }).build();
    }

    MintClient mint() {
        return new MintClient(client(), "https://mint.test");
    }
}
