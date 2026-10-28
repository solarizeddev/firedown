package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
/** The lifecycle surface TaskManager overrides and calls. Every call into the
 *  "system" goes through Sys, which applies Android's rules (stopSelfResult
 *  only stops on the newest startId, startForeground refused in the
 *  background, a dead service's calls ignored). */
public abstract class Service extends Context {
    public static final int START_STICKY = 1, START_NOT_STICKY = 2, START_REDELIVER_INTENT = 3;
    public static final int STOP_FOREGROUND_REMOVE = 1, STOP_FOREGROUND_DETACH = 2;
    volatile Sys.Record record;
    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    public void onDestroy() {}
    public abstract IBinder onBind(Intent intent);
    public void onTimeout(int startId) {}
    public void onTimeout(int startId, int fgsType) {}
    public final void startForeground(int id, Notification n) { Sys.get().startForeground(this, id, n, -1); }
    public final void startForeground(int id, Notification n, int type) { Sys.get().startForeground(this, id, n, type); }
    public final void stopForeground(int flags) { Sys.get().stopForeground(this, flags); }
    public final void stopForeground(boolean remove) { stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH); }
    public final void stopSelf() { Sys.get().stopSelfResult(this, -1); }
    public final void stopSelf(int startId) { Sys.get().stopSelfResult(this, startId); }
    public final boolean stopSelfResult(int startId) { return Sys.get().stopSelfResult(this, startId); }
    public final Sys.Record harnessRecord() { return record; }
}
