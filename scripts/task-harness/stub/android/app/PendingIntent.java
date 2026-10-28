package android.app;
import android.content.Context;
import android.content.Intent;
public class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 0x08000000;
    public static final int FLAG_IMMUTABLE = 0x04000000;
    public final String kind; public final int requestCode; public final Intent intent; public final int flags;
    private PendingIntent(String kind, int requestCode, Intent intent, int flags) {
        this.kind = kind; this.requestCode = requestCode; this.intent = intent; this.flags = flags;
    }
    public static PendingIntent getActivity(Context c, int requestCode, Intent intent, int flags) { return new PendingIntent("activity", requestCode, intent, flags); }
    public static PendingIntent getService(Context c, int requestCode, Intent intent, int flags) { return new PendingIntent("service", requestCode, intent, flags); }
}
