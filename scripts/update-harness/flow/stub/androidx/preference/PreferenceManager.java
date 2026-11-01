package androidx.preference;
public class PreferenceManager {
    public static android.content.SharedPreferences prefs = new android.content.SharedPreferences();
    public static android.content.SharedPreferences getDefaultSharedPreferences(android.content.Context c) { return prefs; }
}
