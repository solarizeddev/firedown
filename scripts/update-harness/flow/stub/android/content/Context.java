package android.content;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;

/** Just what the update classes use, backed by fields the harness sets. */
public class Context {
    public static final String DOWNLOAD_SERVICE = "download";
    public File externalFilesDir;
    public File filesDir;
    public Object downloadManager;
    public ApplicationInfo applicationInfo = new ApplicationInfo();
    public PackageManager packageManager = new PackageManager();

    public Object getSystemService(String name) {
        return DOWNLOAD_SERVICE.equals(name) ? downloadManager : null;
    }

    public File getExternalFilesDir(String type) {
        return externalFilesDir;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }

    public PackageManager getPackageManager() {
        return packageManager;
    }

    public String getPackageName() {
        return "com.solarized.firedown";
    }

    public Context getApplicationContext() {
        return this;
    }
}
