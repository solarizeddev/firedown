package android.os;
import java.util.ArrayList;
import java.util.List;
/** Sizes a list the way a Parcel would, near enough: two bytes per char of
 *  each item's strings. readArrayList hands back what was written. */
public class Parcel {
    private int size;
    private List<?> written;
    public static Parcel obtain() { return new Parcel(); }
    public void writeTypedList(List<?> list) {
        size += 4;
        for (Object o : list) size += 4 + 2 * String.valueOf(o).length();
    }
    public void writeList(List<?> list) {
        writeTypedList(list);
        written = list;
    }
    public void setDataPosition(int pos) {}
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ArrayList readArrayList(ClassLoader loader) { return written == null ? null : new ArrayList(written); }
    public int dataSize() { return size; }
    public void recycle() {}
}
