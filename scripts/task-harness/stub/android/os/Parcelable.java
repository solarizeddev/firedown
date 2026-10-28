package android.os;
/** Stand-in for android.os.Parcelable: an Intent crossing to a service goes
 *  through system_server, so the service receives UNPARCELLED COPIES of every
 *  Parcelable extra. parcelCopy() is how the stub Intent reproduces that. */
public interface Parcelable {
    Parcelable parcelCopy();
}
