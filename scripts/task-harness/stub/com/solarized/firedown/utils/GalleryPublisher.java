package com.solarized.firedown.utils;
import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public final class GalleryPublisher {
    public static final List<String> PUBLISHED = Collections.synchronizedList(new ArrayList<>());
    public static final List<String> FORGOTTEN = Collections.synchronizedList(new ArrayList<>());
    public static void publishIfEnabled(Context context, String filePath, String mimeType) { PUBLISHED.add(filePath); }
    public static void forget(Context context, String filePath) { FORGOTTEN.add(filePath); }
}
