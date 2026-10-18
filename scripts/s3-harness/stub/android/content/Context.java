package android.content;

/** Harness stand-in: the two calls the settings classes make. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public abstract Context getApplicationContext();

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
