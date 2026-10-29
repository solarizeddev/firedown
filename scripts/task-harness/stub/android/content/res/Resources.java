package android.content.res;
import java.lang.reflect.Field;
/** Plurals resolved to "<R name>:<quantity>", so the harness can assert
 *  WHICH plural a notification uses and with what count. */
public class Resources {
    public static final Resources INSTANCE = new Resources();
    public String getQuantityString(int id, int quantity, Object... args) {
        try {
            Class<?> c = Class.forName("com.solarized.firedown.R$plurals");
            for (Field f : c.getFields()) if (f.getInt(null) == id) return f.getName() + ":" + quantity;
        } catch (ReflectiveOperationException ignored) { }
        throw new IllegalArgumentException("Resources$NotFoundException: Plurals resource ID #0x" + Integer.toHexString(id));
    }
}
