package com.solarized.firedown.ffmpegutils;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Only the static probe GifMakerTask calls on the finished GIF. */
public class FFmpegMetaDataReader {
    public static final List<String> PROBED = Collections.synchronizedList(new ArrayList<>());
    public static String probeImageResolution(String path) {
        PROBED.add(path);
        File f = path != null ? new File(path) : null;
        return f != null && f.isFile() && f.length() > 0 ? "240x135" : null;
    }
}
