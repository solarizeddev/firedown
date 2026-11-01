package android.database;
public interface Cursor extends java.io.Closeable {
    boolean moveToFirst();
    int getColumnIndex(String name);
    int getInt(int column);
    @Override void close();
}
