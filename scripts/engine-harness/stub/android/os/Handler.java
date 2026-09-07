package android.os;
public class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public Looper getLooper() { return looper; }
    public Message obtainMessage() { Message m = new Message(); m.target = this; return m; }
    public boolean sendMessage(Message m) { m.target = this; return looper.enqueue(m); }
    public void handleMessage(Message m) {}
}
