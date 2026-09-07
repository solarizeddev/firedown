package android.content;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
/** Map-backed Intent: exactly the extras surface DownloadEngine reads. */
public class Intent {
    private String action;
    private final Map<String, Object> extras = new HashMap<>();
    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent setAction(String a) { action = a; return this; }
    public String getAction() { return action; }
    public Intent putExtra(String k, Object v) { extras.put(k, v); return this; }
    public boolean hasExtra(String k) { return extras.containsKey(k); }
    @SuppressWarnings("unchecked")
    public <T> T getParcelableExtra(String k) { return (T) extras.get(k); }
    @SuppressWarnings("unchecked")
    public <T> ArrayList<T> getParcelableArrayListExtra(String k) { return (ArrayList<T>) extras.get(k); }
}
