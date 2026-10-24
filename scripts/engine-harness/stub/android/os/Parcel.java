package android.os;
import java.util.List;
/** Sizes a typed list the way a Parcel would, near enough: two bytes per char of each item's strings. */
public class Parcel {
    private int size;
    public static Parcel obtain() { return new Parcel(); }
    public void writeTypedList(List<?> list) {
        size += 4;
        for (Object o : list) size += 4 + 2 * String.valueOf(o).length();
    }
    public int dataSize() { return size; }
    public void recycle() {}
}
