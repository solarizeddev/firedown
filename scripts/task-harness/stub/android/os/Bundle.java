package android.os;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
/** Map-backed Bundle: the surface ItemLists uses. */
public class Bundle {
    private final Map<String, Object> map = new HashMap<>();
    public void putString(String k, String v) { map.put(k, v); }
    public String getString(String k) { Object o = map.get(k); return o instanceof String s ? s : null; }
    public void putParcelableArrayList(String k, ArrayList<? extends Parcelable> v) { map.put(k, v); }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String k) { return (ArrayList<T>) map.get(k); }
    public boolean containsKey(String k) { return map.containsKey(k); }
    public void setClassLoader(ClassLoader loader) {}
}
