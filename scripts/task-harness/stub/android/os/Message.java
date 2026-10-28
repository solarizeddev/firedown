package android.os;
public class Message {
    public int what, arg1, arg2;
    public Object obj;
    Handler target;
    Runnable callback;
}
