package org.mozilla.geckoview;
import org.json.JSONObject;
import java.util.*;
public class WebExtension {
    public interface PortDelegate {
        void onPortMessage(Object message, Port port);
        void onDisconnect(Port port);
    }
    /** Stands in for the content script's end of the native port. */
    public static class Port {
        public final String sender;
        private PortDelegate delegate;
        /** Every mint request Java sent over this port. */
        public final List<JSONObject> sent = Collections.synchronizedList(new ArrayList<>());
        /** When false the page simply never answers — the wedged-page case. */
        public volatile boolean autoReply = true;
        /** When set, the page answers with this error instead of a token. */
        public volatile String replyError = null;
        public volatile String tokenPrefix = "TOK";
        /** Simulate Gecko rejecting a post on a dead port. */
        public volatile boolean throwOnPost = false;
        public Port(String sender){ this.sender = sender; }
        public void setDelegate(PortDelegate d){ this.delegate = d; }
        public void postMessage(JSONObject msg){
            if (throwOnPost) throw new IllegalStateException("port is dead (test)");
            sent.add(msg);
            if (!autoReply) return;
            String rid = msg.optString("requestId","");
            boolean fresh = msg.optBoolean("forceFresh", false);
            JSONObject reply = new JSONObject();
            try {
                reply.put("type","mintResult");
                reply.put("requestId", rid);
                if (replyError != null) reply.put("error", replyError);
                else reply.put("token", tokenPrefix + (fresh ? "-fresh-" : "-") + msg.optString("videoId","") + "-" + sent.size());
            } catch (Exception e) { throw new RuntimeException(e); }
            deliver(reply);
        }
        /** Deliver a message from the page to Java (on another thread, like Gecko). */
        public void deliver(JSONObject json){
            PortDelegate d = delegate;
            if (d != null) d.onPortMessage(json, this);
        }
        public void ready(){
            JSONObject r = new JSONObject();
            try { r.put("type","ready"); } catch (Exception e) { throw new RuntimeException(e); }
            deliver(r);
        }
        public void disconnect(){
            PortDelegate d = delegate;
            if (d != null) d.onDisconnect(this);
        }
    }
}
