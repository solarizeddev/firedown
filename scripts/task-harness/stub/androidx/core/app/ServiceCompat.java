package androidx.core.app;
import android.app.Notification;
import android.app.Service;
import android.os.Build;
/** androidx's dispatch: the typed startForeground from Q on, the int-flag
 *  stopForeground from N on. */
public final class ServiceCompat {
    public static final int STOP_FOREGROUND_REMOVE = 1;
    public static final int STOP_FOREGROUND_DETACH = 2;
    private ServiceCompat() {}
    public static void startForeground(Service service, int id, Notification notification, int foregroundServiceType) {
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(id, notification, foregroundServiceType);
        } else {
            service.startForeground(id, notification);
        }
    }
    public static void stopForeground(Service service, int flags) {
        if (Build.VERSION.SDK_INT >= 24) {
            service.stopForeground(flags);
        } else {
            service.stopForeground((flags & STOP_FOREGROUND_REMOVE) != 0);
        }
    }
}
