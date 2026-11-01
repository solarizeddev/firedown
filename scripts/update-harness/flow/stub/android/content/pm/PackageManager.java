package android.content.pm;
public class PackageManager {
    public long lastUpdateTime = 1000L;
    public static class NameNotFoundException extends Exception {}
    public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
        PackageInfo info = new PackageInfo();
        info.lastUpdateTime = lastUpdateTime;
        return info;
    }
}
