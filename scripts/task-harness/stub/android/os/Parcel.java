package android.os;
import java.util.ArrayList;
import java.util.List;
/** Sizes a list the way a Parcel would, near enough (two bytes per char of
 *  each item's toString); readArrayList hands back UNPARCELLED COPIES, as
 *  a real Parcel does. */
public class Parcel {
    private int size;
    private List<?> written;
    public static Parcel obtain() { return new Parcel(); }
    public void writeList(List<?> list) {
        size += 4;
        for (Object o : list) size += 4 + 2 * String.valueOf(o).length();
        written = list;
    }
    public void setDataPosition(int pos) {}
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ArrayList readArrayList(ClassLoader loader) {
        if (written == null) return null;
        ArrayList copy = new ArrayList(written.size());
        for (Object o : written) copy.add(o instanceof Parcelable p ? p.parcelCopy() : o);
        return copy;
    }
    public int dataSize() { return size; }
    public void recycle() {}
}
