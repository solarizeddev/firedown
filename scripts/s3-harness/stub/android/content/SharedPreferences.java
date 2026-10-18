package android.content;

/** Harness stand-in: the calls the settings classes make. */
public interface SharedPreferences {
    String getString(String key, String defValue);

    boolean getBoolean(String key, boolean defValue);

    boolean contains(String key);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putBoolean(String key, boolean value);

        Editor remove(String key);

        void apply();

        boolean commit();
    }
}
