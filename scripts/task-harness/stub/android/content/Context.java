package android.content;
import android.app.Sys;
import android.content.res.Resources;
import android.os.PowerManager;
import java.lang.reflect.Field;
/** What the task service reads: strings (resolved to their R names, so the
 *  harness can assert WHICH string a notification uses), the power service,
 *  and startService, which goes through the system model like the real one. */
public class Context {
    public static final String POWER_SERVICE = "power";
    public static final String NOTIFICATION_SERVICE = "notification";
    private static final PowerManager POWER = new PowerManager();
    public Context getApplicationContext() { return this; }
    public String getPackageName() { return "com.solarized.firedown"; }
    public Object getSystemService(String name) { return POWER_SERVICE.equals(name) ? POWER : null; }
    public final String getString(int id) {
        try {
            Class<?> c = Class.forName("com.solarized.firedown.R$string");
            for (Field f : c.getFields()) if (f.getInt(null) == id) return f.getName();
        } catch (ReflectiveOperationException ignored) { }
        throw new IllegalArgumentException("Resources$NotFoundException: String resource ID #0x" + Integer.toHexString(id));
    }
    public Resources getResources() { return Resources.INSTANCE; }
    public Object startService(Intent intent) { return Sys.get().startService(intent, false); }
}
