package com.solarized.firedown.utils;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.solarized.firedown.R;
import com.solarized.firedown.manager.UrlParser;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;

public class FileUriHelper {

    private static final String TAG = FileUriHelper.class.getSimpleName();

    private static final int MAX_FILENAME_LENGTH = 150;

    private static final Pattern URL_ENCODED = Pattern.compile("%[0-9A-Fa-f]{2}");

    private static final String RESERVED_CHARS = "[|\\?*<\":>+\\[\\]\\/\\#$%!=€@^&~`\uFFFD]";
    private static final String NON_BMP = "[^\\u0000-\\uFFFF]";

    private static final String REPLACE_ALL = "(/|^-$|\\r\\n|\\r|\\n)";

    public static final String MIMETYPE_VTT = "text/vtt";

    public static final String MIMETYPE_SRT = "text/srt";

    public static final String MIMETYPE_TXT = "text/plain";

    public static final String MIMETYPE_MP4 = "video/mp4";

    public static final String MIMETYPE_AUDIO_MP4 = "audio/mp4";

    public static final String MIMETYPE_WEBM = "video/webm";

    public static final String MIMETYPE_WEBM_AUDIO = "audio/webm";

    public static final String MIMETYPE_TS = "video/mp2t";

    public static final String MIMETYPE_MKV = "video/mkv";

    public static final String MIMETYPE_M4S = "video/iso.segment";

    public static final String MIMETYPE_TS_2 = "video/MP2T";

    public static final String MIMETYPE_HTML = "text/html";

    public static final String MIMETYPE_BMP = "image/bmp";

    public static final String MIMETYPE_3GP_VIDEO = "video/3gpp";

    public static final String MIMETYPE_3GP_AUDIO = "audio/3gpp";

    public static final String MIMETYPE_3GP2_VIDEO = "video/3gpp2";

    public static final String MIMETYPE_3GP2_AUDIO = "audio/3gpp2";

    public static final String MIMETYPE_OGG = "application/ogg";

    public static final String MIMETYPE_VIDEO_OGG = "video/ogg";

    public static final String MIMETYPE_AUDIO_OGG = "audio/ogg";

    public static final String MIMETYPE_JPG = "image/jpg";

    public static final String MIMETYPE_JPEG = "image/jpeg";

    public static final String MIMETYPE_PNG = "image/png";

    public static final String MIMETYPE_A_PNG = "image/apng";

    public static final String MIMETYPE_WEBP = "image/webp";

    public static final String MIMETYPE_GIF = "image/gif";

    public static final String MIMETYPE_AVIF = "image/avif";

    public static final String MIMETYPE_HEIC = "image/heic";

    public static final String MIMETYPE_HEIF = "image/heif";

    public static final String MIMETYPE_MPD = "application/dash+xml";

    public static final String MIMETYPE_M3U8 = "application/vnd.apple.mpegurl";

    public static final String MIMETYPE_APPLICATION_X_MPGEURL = "application/x-mpegURL";

    public static final String MIMETYPE_APPLICATION_X_MPGEURL_2 = "application/x-mpegurl";

    public static final String MIMETYPE_AUDIO_X_MPGEURL = "audio/MP2T";

    public static final String MIMETYPE_TORRENT = "application/x-bittorrent";

    public static final String MIMETYPE_MAGNET = "application/x-bittorrent;x-scheme-handler/magnet;";

    public static final String MIMETYPE_PHP = "application/php";

    public static final String MIMETYPE_X_PHP = "x/php";

    public static final String MIMETYPE_APK = "application/vnd.android.package-archive";

    public static final String MIMETYPE_X_APK = "application/xapk-package-archive";

    public static final String MIMETYPE_M_APK = "application/vnd.apkm";

    public static final String MIMETYPE_SVG = "image/svg+xml";

    public static final String MIMETYPE_EXE = "application/x-msdownload";

    public static final String MIMETYPE_PDF = "application/pdf";

    public static final String MIMETYPE_X_PDF = "application/x-pdf";

    public static final String MIMETYPE_MATROSKA = "video/x-matroska";

    public static final String MIMETYPE_UNKNOWN = "application/octet-stream";

    public static final String MIMETYPE_UNKNOWN_2 = "binary/octet-stream";

    public static final String MIMETYPE_UNKNOWN_3 = "text/plain";

    public static final String MIMETYPE_UNKNOWN_4 = "text/plain; charset=utf-8";

    public static final String MIMETYPE_UNKNOWN_5 = "text/plain;charset=UTF-8";

    public static final String MIMETYPE_AUDIO_AAC = "audio/aac";

    public static final String MIMETYPE_AUDIO_MPEGURL = "audio/mpegurl";

    public static final String MIMETYPE_AUDIO_X_MPEGURL = "audio/x-mpegurl";
    public static final String MIMETYPE_AUDIO_WAV = "audio/wav";

    public static final String MIMETYPE_APPLICATION_MPEGURL = "application/mpegurl";

    public static final String MIMETYPE_APPLICATION_M3U = "application/m3u";

    public static final String MIMETYPE_APPLICATION_M3U8 = "application/m3u8";

    public static final String MIMETYPE_XPI = "application/x-xpinstall";

    public static final String MIMETYPE_AUDIO_M3U = "audio/m3u";

    public static final String MIMETYPE_AUDIO_X_M3U = "audio/x-m3u";

    public static final String MIMETYPE_DASH = "application/dash+xml";

    public static final String MIMETYPE_BINARY = "application/octet-stream";

    public static final String MIMETYPE_ISO = "application/x-iso9660-image";

    public static final String MIMETYPE_CD_IMAGE = "application/x-cd-image";

    public static final String AUDIO_AAC = "audio/aac";

    public static final String AUDIO_MP3 = "audio/mpeg";

    public static final String AUDIO_MP3_2 = "audio/x-mpeg-3";

    public static final String AUDIO_MP3_3 = "audio/mp3";

    public static final String AUDIO_MP3_4 = "audio/mpeg3";

    public static final String AUDIO_MP3_5 = "audio/x-mpeg";

    public static final String AUDIO_MP3_6 = "application/x-mpeg";

    public static final String MIMETYPE_X_FLV = "video/x-flv";

    public static final String MIMETYPE_RAR = "application/x-rar-compressed";

    public static final String MIMETYPE_RAR_2 = "application/rar";

    public static final String MIMETYPE_RAR_3 = "application/x-rar";

    public static final String MIMETYPE_ZIP = "application/x-zip-compressed";

    public static final String MIMETYPE_ZIP_2 = "application/zip";

    public static final String MIMETYPE_ZIP_3 = "application/x-zip";

    public static final String MIMETYPE_7Z = "application/x-7z-compressed";

    public static final String MIMETYPE_BZ = "application/x-bzip";

    public static final String MIMETYPE_BZ2 = "application/x-bzip2";

    public static final String MIMETYPE_GZ = "application/gzip";

    public static final String MIMETYPE_ICO = "image/vnd.microsoft.icon";

    public static final String MIMETYPE_JSON = "application/json";

    public static final String MIMETYPE_QUICKTIME = "video/quicktime";

    public static final String MIMETYPE_AVI = "video/x-msvideo";

    public static final String MIMETYPE_WMV = "video/x-ms-wmv";

    public static final String MIMETYPE_DAT = "zz-application/zz-winassoc-dat";

    public static final String MIMETYPE_3GP = "video/3gpp";

    public static final String MIMETYPE_NFO = "text/x-nfo";

    public static final String MIMETYPE_SFV = "text/x-sfv";

    public static final String MIMETYPE_JAVASCRIPT = "application/javascript ";

    public static final String MIMETYPE_BINARY_M3U8 = "application/octet-stream-m3u8";

    public static final String MIMETYPE_BINARY_OCTET = "binary/octet-stream";

    public static final String MIMETYPE_FORCED = "application/force-download";

    public static final String MIMETYPE_CSO = "application/x-cso";

    public static final String MIMETYPE_M3U8_CAPITAL = "application/vnd.apple.mpegURL";

    public static final int MIN_LENGTH = 1024; //1KB

    private static final List<String> MIMETYPES_FFMPEG = Arrays.asList(MIMETYPE_APPLICATION_X_MPGEURL,
            MIMETYPE_APPLICATION_X_MPGEURL_2,
            MIMETYPE_M3U8,
            MIMETYPE_M3U8_CAPITAL,
            MIMETYPE_TS,
            MIMETYPE_TS_2,
            MIMETYPE_MPD,
            MIMETYPE_AUDIO_MPEGURL,
            MIMETYPE_AUDIO_X_MPEGURL,
            MIMETYPE_APPLICATION_MPEGURL,
            MIMETYPE_APPLICATION_M3U,
            MIMETYPE_APPLICATION_M3U8,
            MIMETYPE_AUDIO_M3U,
            MIMETYPE_DASH,
            MIMETYPE_AUDIO_X_M3U,
            MIMETYPE_BINARY,
            MIMETYPE_BINARY_M3U8,
            MIMETYPE_HTML);


    private static final List<String> MIMETYPES_UNKNOWN = Arrays.asList(MIMETYPE_UNKNOWN,
            MIMETYPE_UNKNOWN_2,
            MIMETYPE_UNKNOWN_3,
            MIMETYPE_UNKNOWN_4,
            MIMETYPE_UNKNOWN_5);

    private static final List<String> MIMETYPES_ANDROID = Arrays.asList(MIMETYPE_BINARY,
            MIMETYPE_BINARY_OCTET,
            MIMETYPE_APK,
            MIMETYPE_X_APK,
            MIMETYPE_M_APK);

    private static final List<String> MIMETYPES_BINARY = Arrays.asList(MIMETYPE_BINARY,
            MIMETYPE_BINARY_OCTET);

    private static final List<String> MIMETYPES_FORCED = Arrays.asList(MIMETYPE_BINARY,
            MIMETYPE_HTML,
            MIMETYPE_BINARY_OCTET,
            MIMETYPE_FORCED);

    private static final List<String> MIMETYPES_HTML = Collections.singletonList(MIMETYPE_HTML);

    private static final List<String> MIMETYPES_AUDIO = Arrays.asList(AUDIO_MP3,
            AUDIO_MP3_2,
            AUDIO_MP3_3,
            AUDIO_MP3_4,
            AUDIO_MP3_5,
            AUDIO_MP3_6,
            AUDIO_AAC);


    private static final List<String> MIMETYPES_VIDEO = Arrays.asList(MIMETYPE_M3U8,
            MIMETYPE_APPLICATION_M3U,
            MIMETYPE_APPLICATION_M3U8,
            MIMETYPE_BINARY_M3U8,
            MIMETYPE_APPLICATION_X_MPGEURL,
            MIMETYPE_APPLICATION_X_MPGEURL_2,
            MIMETYPE_AUDIO_MPEGURL,
            MIMETYPE_AUDIO_M3U,
            MIMETYPE_AUDIO_X_M3U,
            MIMETYPE_AUDIO_X_MPEGURL,
            MIMETYPE_APPLICATION_MPEGURL,
            MIMETYPE_MPD);

    private static final List<String> MIMETYPES_HLS = Arrays.asList(MIMETYPE_M3U8,
            MIMETYPE_APPLICATION_M3U,
            MIMETYPE_APPLICATION_M3U8,
            MIMETYPE_BINARY_M3U8,
            MIMETYPE_APPLICATION_X_MPGEURL,
            MIMETYPE_APPLICATION_X_MPGEURL_2,
            MIMETYPE_AUDIO_MPEGURL,
            MIMETYPE_AUDIO_M3U,
            MIMETYPE_AUDIO_X_M3U,
            MIMETYPE_BINARY_OCTET,
            MIMETYPE_UNKNOWN,
            MIMETYPE_HTML,
            MIMETYPE_AUDIO_X_MPEGURL);


    private static final List<String> MIMETYPES_DASH = List.of(MIMETYPE_DASH);


    public static boolean isSize(long length) {
        if (length < 0)
            return true;
        return length >= MIN_LENGTH;
    }

    public static boolean isHls(String mimetype) {
        for (String mime : MIMETYPES_HLS) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDash(String mimetype) {
        for (String mime : MIMETYPES_DASH) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }


    public static boolean isPackageAndroid(String mimetype) {
        for (String mime : MIMETYPES_ANDROID) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }


    public static boolean isUnkown(String mimetype) {
        if(mimetype == null)
            return false;
        for (String mime : MIMETYPES_UNKNOWN) {
            if (mimetype.contains(mime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFFmpeg(String mimetype) {
        if(mimetype == null)
            return false;
        for (String mime : MIMETYPES_FFMPEG) {
            if (mimetype.contains(mime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMimeTypeFiltered(String mimetype) {
        return switch (mimetype) {
            case FileUriHelper.MIMETYPE_M4S -> true;
            case FileUriHelper.MIMETYPE_TS -> true;
            case FileUriHelper.MIMETYPE_TS_2 -> true;
            default -> false;
        };
    }


    public static boolean isBinary(String mimetype) {
        for (String mime : MIMETYPES_BINARY) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMimeTypeForced(String mimetype) {
        for (String mime : MIMETYPES_FORCED) {
            if (mimetype.contains(mime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVideo(String mimetype) {
        if (mimetype == null)
            return false;
        if (mimetype.contains("video"))
            return true;
        for (String mime : MIMETYPES_VIDEO) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }


    public static boolean isPng(String mimetype) {
        if (mimetype == null)
            return false;
        return mimetype.equalsIgnoreCase(MIMETYPE_PNG);
    }
    public static boolean isPhp(String mimetype) {
        if (mimetype == null)
            return false;
        return mimetype.contains(MIMETYPE_PHP) || mimetype.contains(MIMETYPE_X_PHP);
    }

    public static boolean isPdf(String mimetype) {
        if (mimetype == null)
            return false;
        return mimetype.contains(MIMETYPE_PDF) || mimetype.contains(MIMETYPE_X_PDF);
    }

    public static boolean isAudio(String mimetype) {
        if (mimetype == null)
            return false;
        if (mimetype.contains("audio"))
            return true;
        for (String mime : MIMETYPES_AUDIO) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHtml(String mimetype) {
        for (String mime : MIMETYPES_HTML) {
            if (mimetype.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }


    public static List<String> splitFilePath(final File f) {
        if (f == null) {
            throw new NullPointerException();
        }
        final List<String> result = new ArrayList<String>();
        File temp = f.getAbsoluteFile();
        while (temp != null) {
            result.add(0, temp.getName());
            temp = temp.getParentFile();
        }
        return result;
    }


    public static String getMimeTypeFromFile(String url) {
        String mimeType = MIMETYPE_BINARY;
        String extension = "";
        int paramsIndex = url.indexOf("?");
        if (paramsIndex > 0)
            url = url.substring(0, paramsIndex);
        int dotIndex = url.lastIndexOf(".");
        if (dotIndex >= 0) {
            extension = url.substring(dotIndex + 1);
            if (extension.contains("html")) {
                mimeType = MIMETYPE_HTML;
            } else if (extension.contains("php")) {
                mimeType = MIMETYPE_PHP;
            } else if (extension.contains("lrc")) {
                mimeType = MIMETYPE_TXT;
            } else if (extension.contains("exe")) {
                mimeType = MIMETYPE_EXE;
            } else if (extension.contains("bin")) {
                mimeType = MIMETYPE_BINARY;
            } else if (extension.contains("ogg")) {
                mimeType = MIMETYPE_OGG;
            } else if (extension.contains("mkv")) {
                mimeType = MIMETYPE_MATROSKA;
            } else if (extension.contains("pdf")) {
                mimeType = MIMETYPE_PDF;
            } else if (extension.contains("mp4")) {
                mimeType = MIMETYPE_MP4;
            } else if (extension.contains("iso")) {
                mimeType = MIMETYPE_ISO;
            } else if (extension.contains("mp3")) {
                mimeType = AUDIO_MP3;
            } else if (extension.contains("7z")) {
                mimeType = MIMETYPE_7Z;
            } else if (extension.contains("bz")) {
                mimeType = MIMETYPE_BZ;
            } else if (extension.contains("vtt")) {
                mimeType = MIMETYPE_VTT;
            } else if (extension.contains("srt")) {
                mimeType = MIMETYPE_SRT;
            } else if (extension.contains("bz2")) {
                mimeType = MIMETYPE_BZ2;
            } else if (extension.contains("gz")) {
                mimeType = MIMETYPE_GZ;
            } else if (extension.contains("ico")) {
                mimeType = MIMETYPE_ICO;
            } else if (extension.contains("gif")) {
                mimeType = MIMETYPE_GIF;
            } else if (extension.contains("heic")) {
                mimeType = MIMETYPE_HEIC;
            } else if (extension.contains("heif")) {
                mimeType = MIMETYPE_HEIF;
            }else if (extension.contains("avif")) {
                mimeType = MIMETYPE_AVIF;
            } else if (extension.contains("webp")) {
                mimeType = MIMETYPE_WEBP;
            } else if (extension.contains("js")) {
                mimeType = MIMETYPE_JAVASCRIPT;
            } else if (extension.contains("json")) {
                mimeType = MIMETYPE_JSON;
            } else if (extension.contains("flv")) {
                mimeType = MIMETYPE_X_FLV;
            } else if (extension.contains("mov")) {
                mimeType = MIMETYPE_QUICKTIME;
            } else if (extension.contains("avi")) {
                mimeType = MIMETYPE_AVI;
            } else if (extension.contains("wmv")) {
                mimeType = MIMETYPE_WMV;
            } else if (extension.contains("dat")) {
                mimeType = MIMETYPE_DAT;
            } else if (extension.contains("3gp")) {
                mimeType = MIMETYPE_3GP;
            } else if (extension.contains("jpeg")) {
                mimeType = MIMETYPE_JPEG;
            } else if (extension.contains("jpg")) {
                mimeType = MIMETYPE_JPG;
            } else if (extension.contains("m3u8")) {
                mimeType = MIMETYPE_M3U8;
            } else if (extension.contains("rar")) {
                mimeType = MIMETYPE_RAR;
            } else if (extension.contains("zip")) {
                mimeType = MIMETYPE_ZIP;
            } else if (extension.contains("mpd")) {
                mimeType = MIMETYPE_MPD;
            } else if (extension.contains("apk")) {
                mimeType = MIMETYPE_APK;
            } else if (extension.contains("xapk")) {
                mimeType = MIMETYPE_X_APK;
            } else if (extension.contains("xapkn")) {
                mimeType = MIMETYPE_M_APK;
            } else if (extension.contains("ts")) {
                mimeType = MIMETYPE_TS;
            } else if (extension.contains("nfo")) {
                mimeType = MIMETYPE_NFO;
            } else if (extension.contains("sfv")) {
                mimeType = MIMETYPE_SFV;
            } else if (extension.contains("cso")) {
                mimeType = MIMETYPE_CSO;
            } else if (extension.contains("xpi")) {
                mimeType = MIMETYPE_XPI;
            } else if (extension.contains("wav")) {
                mimeType = MIMETYPE_AUDIO_WAV;
            } else if (extension.contains("weba")) {
                mimeType = MIMETYPE_WEBM_AUDIO;
            } else if (extension.contains("bmp")) {
                mimeType = MIMETYPE_BMP;
            } else if (extension.contains("3gp")) {
                mimeType = MIMETYPE_3GP_VIDEO;
            } else if (extension.contains("3g2")) {
                mimeType = MIMETYPE_3GP2_VIDEO;
            } else if (extension.contains("apng")) {
                mimeType = MIMETYPE_A_PNG;
            } else {
                Log.d(TAG, "getMimeTypeFromFile extension: " + extension + " mimetype: " + MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mimeType == null) {
                    mimeType = MIMETYPE_BINARY;
                }
            }

        }
        return mimeType;
    }

    public static String deleteNameCounter(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return fileName;
        }
        return fileName.replaceAll(" ?\\(\\d+\\)", "");
    }

    public static String getShortMimeText(String mime) {
        if (TextUtils.isEmpty(mime)) {
            return "bin";
        }
        String ext = getFileExtensionFromMimeType(mime);
        if (!ext.equals("bin") && !ext.equals("txt")) {
            return ext;
        }
        int max_length = 5;
        String short_mime = mime;
        int slashIndex = mime.lastIndexOf("/");
        if (slashIndex > 0)
            short_mime = mime.substring(slashIndex + 1);
        if (short_mime.length() > max_length)
            short_mime = short_mime.substring(0, max_length);
        return short_mime;
    }

    public static String getLongMimeText(Context context, String mime) {
        if(isImage(mime) && !isSVG(mime) && !isGIF(mime)){
            return context.getString(R.string.media_type_image);
        }else if(isVideo(mime)){
            return context.getString(R.string.media_type_video);
        }else if(isAudio(mime)){
            return context.getString(R.string.media_type_audio);
        }else if(isGIF(mime)){
            return context.getString(R.string.media_type_gif);
        }else if(isSVG(mime)){
            return context.getString(R.string.media_type_svg);
        }else if(isApk(mime)){
            return context.getString(R.string.media_type_apk);
        }else if(isCompressed(mime)){
            return context.getString(R.string.media_type_zip);
        }else{
            return String.format("%s %s", getShortMimeText(mime).toUpperCase(), context.getString(R.string.media_type_file));
        }
    }

    //https://android.googlesource.com/platform/frameworks/base/+/7b2f8b8/core/jni/android/graphics/BitmapFactory.cpp
    public static boolean isImage(String mimeType) {
        return Strings.CI.containsAny(mimeType, "image");
    }

    public static boolean isImage(MediaType mediaType) {
        return mediaType != null && Strings.CI.containsAny(mediaType.toString(), "image");
    }

    public static boolean isCompressed(String mimeType) {
        return mimeType != null && (mimeType.contains("zip")
                || mimeType.contains("rar")
                || mimeType.contains("compressed")
                || mimeType.contains("application/tar")
                || mimeType.contains("application/x-xz")
                || mimeType.contains("gzip"));
    }

    public static boolean isDoc(String mimeType) {
        return mimeType != null && (mimeType.contains("text")
                || mimeType.contains("csv")
                || mimeType.contains("pdf")
                || mimeType.contains("msword")
                || mimeType.contains("openxmlformats")
                || mimeType.contains("ms-")
                || mimeType.contains("epub")
                || mimeType.contains("calendar")
                || mimeType.contains("vnd.ms")
                || mimeType.contains("xml")
                || mimeType.contains("officedocument"));
    }

    public static boolean isGIF(String mimeType) {
        return Strings.CI.containsAny(mimeType, "image/gif");
    }

    public static boolean isCaption(String mimeType) {
        return Strings.CI.containsAny(mimeType, "text/vtt") || Strings.CI.containsAny(mimeType, "text/plain");
    }

    public static boolean isSVG(String mimeType) {
        return Strings.CI.containsAny(mimeType, "svg+xml");
    }

    public static boolean isSubtitle(String mimeType) {
        return Strings.CI.containsAny(mimeType, "text/vtt") ||
                Strings.CI.containsAny(mimeType, "text/srt") ||
                Strings.CI.containsAny(mimeType, "application/vtt") ||
                Strings.CI.containsAny(mimeType, "application/x-subrip");
    }

    public static boolean isWEP(String mimeType) {
        return Strings.CI.containsAny(mimeType, "image/webp");
    }

    public static boolean isMimeTypeUnknown(String mimeType) {
        return mimeType != null && (mimeType.equals(FileUriHelper.MIMETYPE_UNKNOWN)
                || mimeType.equals(FileUriHelper.MIMETYPE_UNKNOWN_2)
                || mimeType.equals(FileUriHelper.MIMETYPE_UNKNOWN_3)
                || mimeType.equals(FileUriHelper.MIMETYPE_UNKNOWN_4));
    }

    public static boolean isMimeTypeMedia(String mimeType) {
        return mimeType != null && (mimeType.contains("application/dash")
                || mimeType.contains("mpeg")
                || mimeType.contains("m3u")
                || mimeType.contains("flv")
                || mimeType.contains("dash+xml")
                || mimeType.contains("video/")
                || mimeType.contains("audio/"));
    }

    public static String getFileExtensionFromData(String url){
        Matcher m = UrlParser.DATA_PATTERN.matcher(url);
        if(m.find()){
            String extractedMimeType = m.group(0);
            return getFileExtensionFromMimeType(extractedMimeType);
        }
        return "txt";
    }

    public static String getFileExtensionFromMimeType(String mime) {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (extension == null) {
            //This list will be long
            if (mime == null) {
                extension = "bin";
            } else {
                if (mime.contains(MIMETYPE_HTML)) {
                    extension = "html";
                } else if (mime.contains(MIMETYPE_PHP) || mime.contains(MIMETYPE_X_PHP)) {
                    extension = "php";
                } else if (mime.contains(MIMETYPE_EXE)) {
                    extension = "exe";
                } else if (mime.contains(MIMETYPE_VTT)) {
                    extension = "vtt";
                } else if (mime.contains(MIMETYPE_SRT)) {
                    extension = "srt";
                } else if (mime.contains(MIMETYPE_OGG) || mime.contains(MIMETYPE_AUDIO_OGG) || mime.contains(MIMETYPE_VIDEO_OGG)) {
                    extension = "ogg";
                } else if (mime.contains(MIMETYPE_MATROSKA) || mime.contains(MIMETYPE_MKV)) {
                    extension = "mkv";
                } else if (mime.contains(MIMETYPE_PDF)) {
                    extension = "pdf";
                } else if (mime.contains(MIMETYPE_APPLICATION_X_MPGEURL) || (mime.contains(MIMETYPE_APPLICATION_X_MPGEURL_2) || mime.contains(MIMETYPE_M4S) || mime.contains(MIMETYPE_MP4))) {
                    extension = "mp4";
                } else if (mime.contains(MIMETYPE_AUDIO_X_MPEGURL) || mime.contains(MIMETYPE_AUDIO_MPEGURL)) {
                    extension = "m3u";
                } else if (mime.contains(MIMETYPE_ISO) || mime.contains(MIMETYPE_CD_IMAGE)) {
                    extension = "iso";
                } else if (mime.contains(AUDIO_MP3) || mime.contains(AUDIO_MP3_2) || mime.contains(AUDIO_MP3_3) || mime.contains(AUDIO_MP3_4) || mime.contains(AUDIO_MP3_5) || mime.contains(AUDIO_MP3_6)) {
                    extension = "mp3";
                } else if (mime.contains(MIMETYPE_7Z)) {
                    extension = "7z";
                } else if (mime.contains(MIMETYPE_BZ)) {
                    extension = "bz";
                } else if (mime.contains(MIMETYPE_BZ2)) {
                    extension = "bz2";
                } else if (mime.contains(MIMETYPE_GZ)) {
                    extension = "gz";
                } else if (mime.contains(MIMETYPE_ICO)) {
                    extension = "ico";
                } else if (mime.contains(MIMETYPE_GIF)) {
                    extension = "gif";
                } else if (mime.contains(MIMETYPE_AVIF)) {
                    extension = "avif";
                } else if (mime.contains(MIMETYPE_JAVASCRIPT)) {
                    extension = "js";
                } else if (mime.contains(MIMETYPE_JSON)) {
                    extension = "json";
                } else if (mime.contains(MIMETYPE_X_FLV)) {
                    extension = "flv";
                } else if (mime.contains(MIMETYPE_QUICKTIME)) {
                    extension = "mov";
                } else if (mime.contains(MIMETYPE_AVI)) {
                    extension = "avi";
                } else if (mime.contains(MIMETYPE_WMV)) {
                    extension = "wmv";
                } else if (mime.contains(MIMETYPE_DAT)) {
                    extension = "dat";
                } else if (mime.contains(MIMETYPE_3GP)) {
                    extension = "3gp";
                } else if (mime.contains(MIMETYPE_JPEG)) {
                    extension = "jpeg";
                } else if (mime.contains(MIMETYPE_JPG)) {
                    extension = "jpg";
                } else if (mime.contains(MIMETYPE_WEBP)) {
                    extension = "wepb";
                } else if (mime.contains(MIMETYPE_M3U8) || mime.contains(MIMETYPE_APPLICATION_M3U8) || mime.contains(MIMETYPE_BINARY_M3U8)) {
                    extension = "m3u8";
                } else if (mime.contains(MIMETYPE_RAR) || mime.contains(MIMETYPE_RAR_2) || mime.contains(MIMETYPE_RAR_3)) {
                    extension = "rar";
                } else if (mime.contains(MIMETYPE_ZIP) || mime.contains(MIMETYPE_ZIP_2) || mime.contains(MIMETYPE_ZIP_3)) {
                    extension = "zip";
                } else if (mime.contains(MIMETYPE_MPD)) {
                    extension = "mpd";
                } else if (mime.contains(MIMETYPE_APK)) {
                    extension = "apk";
                } else if (mime.contains(MIMETYPE_X_APK)) {
                    extension = "xapk";
                } else if (mime.contains(MIMETYPE_M_APK)) {
                    extension = "apkm";
                } else if (mime.contains(MIMETYPE_TS)) {
                    extension = "ts";
                } else if (mime.contains(MIMETYPE_NFO)) {
                    extension = "nfo";
                } else if (mime.contains(MIMETYPE_SFV)) {
                    extension = "sfv";
                } else if (mime.contains(MIMETYPE_UNKNOWN)) {
                    extension = "bin";
                } else if (mime.contains(MIMETYPE_CSO)) {
                    extension = "cso";
                } else if (mime.contains(MIMETYPE_XPI)) {
                    extension = "xpi";
                }else if (mime.contains(MIMETYPE_AUDIO_WAV)) {
                    extension = "wav";
                } else if (mime.contains(MIMETYPE_WEBM_AUDIO)) {
                    extension = "weba";
                } else if (mime.contains(MIMETYPE_BMP)) {
                    extension = "bmp";
                } else if (mime.contains(MIMETYPE_3GP)) {
                    extension = "3gp";
                } else if (mime.contains(MIMETYPE_3GP2_VIDEO)) {
                    extension = "3g2";
                } else if (mime.contains(MIMETYPE_A_PNG)) {
                    extension = "apng";
                } else if (mime.contains(MIMETYPE_AUDIO_AAC)) {
                    extension = "aac";
                } else {
                    extension = "bin";
                }
            }
        }
        return extension;
    }


    public static boolean isApk(String mimeType) {
        return mimeType != null && mimeType.equals(MIMETYPE_APK);
    }

    public static boolean isText(String mimeType) {
        return mimeType != null && (mimeType.contains(MIMETYPE_TXT) || mimeType.contains(MIMETYPE_VTT));
    }


    public static boolean isXapk(String mimeType) {
        return mimeType != null && mimeType.equals(MIMETYPE_X_APK);
    }

    public static boolean isMagnetOrTorrent(String mimeType) {
        return mimeType != null && mimeType.contains("x-bittorrent");
    }

    public static boolean isApkM(String mimeType) {
        return mimeType != null && mimeType.equals(MIMETYPE_M_APK);
    }

    public static int getMimeTypeIcon(String mimeType) {

        if (TextUtils.isEmpty(mimeType) || !mimeType.contains("/"))
            return R.drawable.ic_baseline_insert_drive_file_24;

        //split mimetype
        String type = mimeType.substring(0, mimeType.indexOf("/"));
        String subtype = mimeType.substring(mimeType.indexOf("/") + 1);


        return switch (type) {
            case "image" -> R.drawable.ic_baseline_image_24;
            case "audio" -> R.drawable.ic_baseline_audiotrack_24;
            case "video" -> R.drawable.ic_movie_24;
            case "text" -> {
                if ("calendar".equals(subtype)) {
                    yield R.drawable.ic_baseline_calendar_today_24;
                }
                yield R.drawable.ic_baseline_text_snippet_24;
            }
            case "application" -> switch (subtype) {
                case "x-iso9660-image", "x-cd-image" -> R.drawable.ic_baseline_archive_24;
                case "x-bittorrent", "x-bittorrent;x-scheme-handler/magnet;" ->
                        R.drawable.ic_magnet_24;
                case "pdf" -> R.drawable.ic_baseline_picture_as_pdf_24;
                case "zip", "gz", "tar", "rar", "gzip" -> R.drawable.ic_baseline_archive_24;
                case "json", "xml", "txt", "msexcel", "mspowerpoint", "msword", "xlsx", "docx",
                     "odp", "ods", "odt", "htm", "html", "shtml", "xhtml" ->
                        R.drawable.ic_baseline_text_snippet_24;
                case "vnd.android.package-archive", "xapk-package-archive" ->
                        R.drawable.baseline_android_24;
                default -> R.drawable.ic_baseline_insert_drive_file_24;
            };
            default -> R.drawable.ic_baseline_insert_drive_file_24;
        };
    }


    public static boolean isRemote(String filePath){
        return filePath != null
                && (filePath.startsWith("http://") || filePath.startsWith("https://")
                || filePath.startsWith("rtmp://") || filePath.startsWith("rtsp://")
                || filePath.startsWith("mmsh://") || filePath.startsWith("mmst://")
                || filePath.startsWith("hls://"));
    }

    public static String sanitizeFileName(String fileName) {
        if (TextUtils.isEmpty(fileName))
            return fileName;

        fileName = fileName.replaceAll(RESERVED_CHARS, "");
        fileName = fileName.replaceAll(NON_BMP, "");
        fileName = fileName.replaceAll(REPLACE_ALL, "");
        fileName = fileName.replaceAll("[\u0000-\u001F\u007F]", "");
        fileName = fileName.replaceAll("\\p{Cs}", "");
        fileName = fileName.replaceAll("[\u200B-\u200D\u2060\uFEFF\u00AD]", "");
        fileName = fileName.replaceAll("[\u202A-\u202E\u2066-\u2069]", "");
        fileName = fileName.replaceAll("\\s{2,}", " ");
        fileName = fileName.trim();
        fileName = fileName.replaceAll("^[.\\s]+|[.\\s]+$", "");

        String name = FilenameUtils.getBaseName(fileName);
        String extension = FilenameUtils.getExtension(fileName);
        String suffix = extension.isEmpty() ? "" : "." + extension;

        name = StringUtils.truncate(name, MAX_FILENAME_LENGTH);

        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        int maxNameBytes = 255 - suffixBytes;
        while (name.getBytes(StandardCharsets.UTF_8).length > maxNameBytes && name.length() > 1) {
            name = name.substring(0, name.length() - 1);
        }

        name = StringUtils.trim(name);

        if (name.isEmpty()) {
            name = "download_" + System.currentTimeMillis();
        }

        return name + suffix;
    }

    public static String checkFileExtension(String fileName, String mimeType){
        String baseName = FilenameUtils.getBaseName(fileName);
        String extName = FilenameUtils.getExtension(fileName);
        String extensionFromMimeType = FileUriHelper.getFileExtensionFromMimeType(mimeType);
        if(extName == null){
            return baseName + "." + extensionFromMimeType;
        }else if(!extensionFromMimeType.equals("bin")){
            return baseName + "." + extensionFromMimeType;
        }else{
            return baseName + "." + extName;
        }
    }

    public static String decodeName(String name) {
        if (name == null || !URL_ENCODED.matcher(name).find()) {
            return name;
        }
        try {
            return URLDecoder.decode(name, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Log.e(TAG, "decodeName", e);
        }
        return name;
    }

}
