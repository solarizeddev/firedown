package com.solarized.firedown.utils;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

public class ContentUriUtils {
    public static final String FALLBACK_COPY_FOLDER = "upload_part";

    private static final String TAG = ContentUriUtils.class.getSimpleName();

    private static final String CONTENT_SCHEME = "content";


    public static @Nullable String getOriginalFilePathFromUri(final Context context, final Uri uri) throws IllegalArgumentException {
        return "file://" + getPath(context, uri);

    }


    @SuppressLint("NewApi")
    public static String getPath(final Context context, final Uri uri) {

        String selection = null;
        String[] selectionArgs = null;

        if (isExternalStorageDocument(uri)) {
            final String docId = DocumentsContract.getDocumentId(uri);
            final String[] split = docId.split(":");
            final String type = split[0];

            String fullPath = getPathFromExtSD(split);

            if (fullPath == null || !fileExists(fullPath)) {
                Log.d(TAG, "Copy files as a fallback");
                fullPath = copyFileToInternalStorage(context, uri, FALLBACK_COPY_FOLDER);
            }

            if (!fullPath.equals("")) {
                return fullPath;
            } else {
                return null;
            }
        }


        // DownloadsProvider

        if (isDownloadsDocument(uri)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final String id;
                try (Cursor cursor = context.getContentResolver().query(uri, new String[]{
                        MediaStore.MediaColumns.DISPLAY_NAME
                }, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String fileName = cursor.getString(0);
                        String path = Environment.getExternalStorageDirectory().toString() + "/Download/" + fileName;
                        if (!TextUtils.isEmpty(path)) {
                            return path;
                        }
                    }
                }
                id = DocumentsContract.getDocumentId(uri);

                if (!TextUtils.isEmpty(id)) {
                    if (id.startsWith("raw:")) {
                        return id.replaceFirst("raw:", "");
                    }
                    String[] contentUriPrefixesToTry = new String[]{
                            "content://downloads/public_downloads",
                            "content://downloads/my_downloads"
                    };

                    for (String contentUriPrefix : contentUriPrefixesToTry) {
                        try {
                            final Uri contentUri = ContentUris.withAppendedId(Uri.parse(contentUriPrefix), Long.parseLong(id));

                            return getDataColumn(context, contentUri, null, null);
                        } catch (NumberFormatException e) {
                            //In Android 8 and Android P the id is not a number
                            return uri.getPath().replaceFirst("^/document/raw:", "").replaceFirst("^raw:", "");
                        }
                    }
                }
            } else {
                final String id = DocumentsContract.getDocumentId(uri);

                if (id.startsWith("raw:")) {
                    return id.replaceFirst("raw:", "");
                }
                Uri contentUri = null;
                try {
                    contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), Long.parseLong(id));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "getPath", e);
                }

                if (contentUri != null)
                    return getDataColumn(context, contentUri, null, null);
            }
        }


        // MediaProvider
        if (isMediaDocument(uri)) {
            final String docId = DocumentsContract.getDocumentId(uri);
            final String[] split = docId.split(":");
            final String type = split[0];

            Log.d(TAG, "MEDIA DOCUMENT TYPE: " + type);

            Uri contentUri = null;

            if ("image".equals(type)) {
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if ("video".equals(type)) {
                contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else if ("audio".equals(type)) {
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            } else if ("document".equals(type)) {
                contentUri = MediaStore.Files.getContentUri(MediaStore.getVolumeName(uri));
            }

            selection = "_id=?";
            selectionArgs = new String[]{
                    split[1]
            };


            return getDataColumn(context, contentUri, selection, selectionArgs);
        }

        if (isGoogleDriveUri(uri)) {
            return getDriveFilePath(context, uri);
        }

        if (isWhatsAppFile(uri)) {
            return getFilePathForWhatsApp(context, uri);
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            if (isGooglePhotosUri(uri)) {
                return uri.getLastPathSegment();
            }

            if (isGoogleDriveUri(uri)) {
                return getDriveFilePath(context, uri);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // return getFilePathFromURI(context,uri);
                return copyFileToInternalStorage(context, uri, FALLBACK_COPY_FOLDER);
                // return getRealPathFromURI(context,uri);
            } else {
                return getDataColumn(context, uri, null, null);
            }

        }

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }else if("http".equalsIgnoreCase(uri.getScheme())){
            return uri.toString();
        }else if("https".equalsIgnoreCase(uri.getScheme())){
            return uri.toString();
        }


        return copyFileToInternalStorage(context, uri, FALLBACK_COPY_FOLDER);
    }

    private static boolean fileExists(String filePath) {
        File file = new File(filePath);

        return file.exists();
    }

    private static String getPathFromExtSD(String[] pathData) {
        final String type = pathData[0];
        final String relativePath = File.separator + pathData[1];
        String fullPath = "";


        Log.d(TAG, "MEDIA EXTSD TYPE: " + type);
        Log.d(TAG, "Relative path: " + relativePath);
        // on my Sony devices (4.4.4 & 5.1.1), `type` is a dynamic string
        // something like "71F8-2C0A", some kind of unique id per storage
        // don't know any API that can get the root path of that storage based on its id.
        //
        // so no "primary" type, but let the check here for other devices
        if ("primary".equalsIgnoreCase(type)) {
            fullPath = Environment.getExternalStorageDirectory() + relativePath;
            if (fileExists(fullPath)) {
                return fullPath;
            }
        }

        if ("home".equalsIgnoreCase(type)) {
            fullPath = "/storage/emulated/0/Documents" + relativePath;
            if (fileExists(fullPath)) {
                return fullPath;
            }
        }

        // Environment.isExternalStorageRemovable() is `true` for external and internal storage
        // so we cannot relay on it.
        //
        // instead, for each possible path, check if file exists
        // we'll start with secondary storage as this could be our (physically) removable sd card
        fullPath = System.getenv("SECONDARY_STORAGE") + relativePath;
        if (fileExists(fullPath)) {
            return fullPath;
        }

        fullPath = System.getenv("EXTERNAL_STORAGE") + relativePath;
        if (fileExists(fullPath)) {
            return fullPath;
        }

        return null;
    }

    private static String getDriveFilePath(Context context, Uri uri) {
        Cursor returnCursor = context.getContentResolver().query(uri, null, null, null, null);
        /*
         * Get the column indexes of the data in the Cursor,
         *     * move to the first row in the Cursor, get the data,
         *     * and display it.
         * */
        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
        returnCursor.moveToFirst();
        String name = (returnCursor.getString(nameIndex));
        String size = (Long.toString(returnCursor.getLong(sizeIndex)));
        File file = new File(context.getCacheDir(), name);
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(file);
            int read = 0;
            int maxBufferSize = 1024 * 1024;
            int bytesAvailable = inputStream.available();

            //int bufferSize = 1024;
            int bufferSize = Math.min(bytesAvailable, maxBufferSize);

            final byte[] buffers = new byte[bufferSize];
            while ((read = inputStream.read(buffers)) != -1) {
                outputStream.write(buffers, 0, read);
            }
            Log.e(TAG, "Size " + file.length());
            inputStream.close();
            outputStream.close();
            Log.e(TAG, "Path " + file.getPath());
            Log.e(TAG, "Size " + file.length());
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }finally {
            returnCursor.close();
        }

        return file.getPath();
    }

    /***
     * Used for Android Q+
     * @param uri
     * @param newDirName if you want to create a directory, you can set this variable
     * @return
     */
    private static String copyFileToInternalStorage(Context context, Uri uri, String newDirName) {
        Uri returnUri = uri;

        Cursor returnCursor = context.getContentResolver().query(returnUri, new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        }, null, null, null);


        /*
         * Get the column indexes of the data in the Cursor,
         *     * move to the first row in the Cursor, get the data,
         *     * and display it.
         * */
        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
        returnCursor.moveToFirst();
        String name = (returnCursor.getString(nameIndex));
        String size = (Long.toString(returnCursor.getLong(sizeIndex)));

        File output;
        if (!newDirName.equals("")) {
            String random_collision_avoidance = UUID.randomUUID().toString();

            File dir = new File(context.getFilesDir() + File.separator + newDirName + File.separator + random_collision_avoidance);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            output = new File(context.getFilesDir() + File.separator + newDirName + File.separator + random_collision_avoidance + File.separator + name);
        } else {
            output = new File(context.getFilesDir() + File.separator + name);
        }

        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(output);
            int read = 0;
            int bufferSize = 1024;
            final byte[] buffers = new byte[bufferSize];

            while ((read = inputStream.read(buffers)) != -1) {
                outputStream.write(buffers, 0, read);
            }

            inputStream.close();
            outputStream.close();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }

        return output.getPath();
    }

    private static String getFilePathForWhatsApp(Context context, Uri uri) {
        return copyFileToInternalStorage(context, uri, "whatsapp");
    }

    private static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection,
                    selection, selectionArgs, null);

            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }

        return null;
    }

    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    private static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }

    public static boolean isWhatsAppFile(Uri uri) {
        return "com.whatsapp.provider.media".equals(uri.getAuthority());
    }

    private static boolean isGoogleDriveUri(Uri uri) {
        return "com.google.android.apps.docs.storage".equals(uri.getAuthority()) || "com.google.android.apps.docs.storage.legacy".equals(uri.getAuthority());
    }

    public static boolean isContentUri(Uri uri) {
        return uri != null && uri.getScheme() != null && CONTENT_SCHEME.equals(uri.getScheme());
    }

    public static boolean isContentUri(String sUri) {
        return sUri != null && sUri.startsWith(CONTENT_SCHEME);
    }
}