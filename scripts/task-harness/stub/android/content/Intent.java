package android.content;
import android.os.Parcelable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
/** Map-backed Intent. Typed getters follow Bundle's rules: a value of the
 *  wrong type reads as the default (Bundle logs a ClassCastException and
 *  returns the default), so an int put under a long key is NOT read back. */
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    private String action;
    private int flags;
    private Class<?> component;
    private final Map<String, Object> extras = new LinkedHashMap<>();
    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent(Context context, Class<?> target) { component = target; }
    public Class<?> getComponentClass() { return component; }
    public Intent setAction(String a) { action = a; return this; }
    public String getAction() { return action; }
    public Intent setFlags(int f) { flags = f; return this; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public int getFlags() { return flags; }
    public Intent putExtra(String k, long v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, int v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, boolean v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, Parcelable v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, Serializable v) { extras.put(k, v); return this; }
    public Intent putParcelableArrayListExtra(String k, ArrayList<? extends Parcelable> v) { extras.put(k, v); return this; }
    public boolean hasExtra(String k) { return extras.containsKey(k); }
    public String getStringExtra(String k) { Object o = extras.get(k); return o instanceof String s ? s : null; }
    public long getLongExtra(String k, long def) { Object o = extras.get(k); return o instanceof Long l ? l : def; }
    public int getIntExtra(String k, int def) { Object o = extras.get(k); return o instanceof Integer i ? i : def; }
    @SuppressWarnings("unchecked")
    public <T> ArrayList<T> getParcelableArrayListExtra(String k) { Object o = extras.get(k); return o instanceof ArrayList<?> l ? (ArrayList<T>) l : null; }
    /** What the service receives after the trip through system_server: every
     *  Parcelable (alone or in a list) unparcelled as a NEW object, a list as
     *  a new list (a null element stays null), primitives and strings as is. */
    public Intent parcelCopy() {
        Intent c = new Intent(action);
        c.flags = flags;
        c.component = component;
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Parcelable p) {
                v = p.parcelCopy();
            } else if (v instanceof ArrayList<?> l) {
                ArrayList<Object> copy = new ArrayList<>(l.size());
                for (Object o : l) copy.add(o instanceof Parcelable p ? p.parcelCopy() : o);
                v = copy;
            }
            c.extras.put(e.getKey(), v);
        }
        return c;
    }
    @Override public String toString() { return "Intent{" + action + "}"; }
}
