package android.content;
/** The engine only ever asks for the application context and hands it on. */
public class Context {
    public Context getApplicationContext() { return this; }
}
