package com.solarized.firedown.glide;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ApkIconDecoder implements ResourceDecoder<InputStream, Bitmap> {

    private static final int DEFAULT_ICON_SIZE = 192;
    // ZIP local file header magic: PK\x03\x04
    private static final byte[] APK_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private final Context context;
    private final BitmapPool bitmapPool;

    public ApkIconDecoder(@NonNull Context context, @NonNull BitmapPool bitmapPool) {
        this.context = context.getApplicationContext();
        this.bitmapPool = bitmapPool;
    }

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) throws IOException {
        byte[] header = new byte[4];
        int read = 0;
        while (read < 4) {
            int n = source.read(header, read, 4 - read);
            if (n == -1) return false;
            read += n;
        }
        return header[0] == APK_MAGIC[0]
                && header[1] == APK_MAGIC[1]
                && header[2] == APK_MAGIC[2]
                && header[3] == APK_MAGIC[3];
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(
            @NonNull InputStream source, int width, int height, @NonNull Options options
    ) throws IOException {
        File temp = File.createTempFile("glide_apk_", ".apk", context.getCacheDir());
        try {
            // Write full stream to temp file for PackageManager
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = source.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }

            String filePath = temp.getAbsolutePath();
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(filePath, PackageManager.GET_ACTIVITIES);
            if (info == null || info.applicationInfo == null) {
                return null;
            }

            info.applicationInfo.sourceDir = filePath;
            info.applicationInfo.publicSourceDir = filePath;

            Drawable icon;
            try {
                icon = pm.getApplicationIcon(info.applicationInfo);
            } catch (Exception e) {
                return null;
            }

            int targetW = width > 0 ? width : DEFAULT_ICON_SIZE;
            int targetH = height > 0 ? height : DEFAULT_ICON_SIZE;

            Bitmap bitmap = drawableToBitmap(icon, targetW, targetH);
            return BitmapResource.obtain(bitmap, bitmapPool);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    @NonNull
    private Bitmap drawableToBitmap(@NonNull Drawable drawable, int canvasWidth, int canvasHeight) {
        if (canvasWidth <= 0) canvasWidth = DEFAULT_ICON_SIZE;
        if (canvasHeight <= 0) canvasHeight = DEFAULT_ICON_SIZE;

        int intrinsicW = drawable.getIntrinsicWidth();
        int intrinsicH = drawable.getIntrinsicHeight();
        if (intrinsicW <= 0) intrinsicW = DEFAULT_ICON_SIZE;
        if (intrinsicH <= 0) intrinsicH = DEFAULT_ICON_SIZE;

        // Cap icon to 50% of canvas so it doesn't dominate
        float maxIconW = canvasWidth * 0.5f;
        float maxIconH = canvasHeight * 0.5f;

        float scale = Math.min(maxIconW / intrinsicW, maxIconH / intrinsicH);

        int iconW = Math.round(intrinsicW * scale);
        int iconH = Math.round(intrinsicH * scale);

        int left = (canvasWidth - iconW) / 2;
        int top = (canvasHeight - iconH) / 2;

        Bitmap bitmap = bitmapPool.get(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0x00000000);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(left, top, left + iconW, top + iconH);
        drawable.draw(canvas);
        return bitmap;
    }
}