package com.solarized.firedown.ffmpegutils;

import android.graphics.Bitmap;
import android.util.Log;


import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;

public class FFmpegThumbnailer {

    private static final String TAG = FFmpegThumbnailer.class.getSimpleName();

    private static final boolean SUPPORTED = FFmpegLoader.ensureLoaded();

    private long mNativeThumbnailer;

    private Bitmap mBitmap;

    private ByteBuffer mByteBuffer;

    private InputStream mInputStream;

    private long mReadPosition;

    private long mStreamLength;


    public FFmpegThumbnailer(){
        if(!SUPPORTED){
            Log.w(TAG, "init system NOT SUPPORTED");
            return;
        }
        initThumbnailer();
    }


    public int setDataSource(InputStream inputStream, Map<String, String> dictionary){
        if(!SUPPORTED){
            Log.w(TAG, "getBitmap NOT SUPPORTED");
            return FFmpegConstants.UNSUPPORTED;
        }
        mInputStream = inputStream;
        mStreamLength = Long.MAX_VALUE;
        return bitmapSetDataSourceInputStream(inputStream, dictionary);
    }

    public int setDataSource(FileDescriptor fileDescriptor,  Map<String, String> dictionary){
        if(!SUPPORTED){
            Log.w(TAG, "getBitmap NOT SUPPORTED");
            return FFmpegConstants.UNSUPPORTED;
        }
        return bitmapSetDataSourceFileDescriptor(fileDescriptor, dictionary);
    }

    public int setDataSource(String filePath,  Map<String, String> dictionary){
        if(!SUPPORTED){
            Log.w(TAG, "getBitmap NOT SUPPORTED");
            return FFmpegConstants.UNSUPPORTED;
        }
        return bitmapSetDataSource(filePath, dictionary);
    }

    public Bitmap getBitmap(long streamPos){
        if(!SUPPORTED){
            Log.w(TAG, "getBitmap NOT SUPPORTED");
            return null;
        }
        bitmapExtract(streamPos);
        return mBitmap;
    }

    public void release(){
        try{
            if(mInputStream != null){
                mInputStream.close();
            }
        }catch(IOException e){
            Log.e(TAG, "release", e);
        }

        if(mByteBuffer != null){
            mByteBuffer.clear();
            mByteBuffer = null;
        }
        if(!SUPPORTED){
            Log.w(TAG, "dealloc system NOT SUPPORTED");
            return;
        }
        deallocThumbnailer();
    }


    private long bitmapSeekInputStream(long seekPos, int whence) {

        try {

            Log.d(TAG, "seekInputStream whence: " + whence + " readPos: " + mReadPosition + " length: " + mStreamLength + " seekPos: " + seekPos) ;

            if (mInputStream == null) {
                return FFmpegConstants.FFMPEG_AVERROR_EOF;
            }

            if(whence == FFmpegConstants.AVSEEK_SIZE) {
                Log.d(TAG, "seekInputStream size: " + (mStreamLength == Long.MAX_VALUE ? mReadPosition : mStreamLength));
                return mStreamLength;
            }

            long skipped = 0;

            switch (whence) {
                case FFmpegConstants.SEEK_SET:
                    /* [BUG FIX] Was: skipped = is.skip(seekPos); mReadPosition = seekPos;
                     * That assumed skip() always reaches the target, but skip() is
                     * allowed to skip fewer bytes than requested even when not at EOF.
                     * Now we reset, loop until target reached, and report the real
                     * position. */
                    mInputStream.reset();
                    mReadPosition = 0;
                    skipped = skipFully(mInputStream, seekPos);
                    mReadPosition = skipped;
                    break;
                case FFmpegConstants.SEEK_CUR:
                    /* [BUG FIX] Was: is.skip(seekPos) without looping. */
                    skipped = skipFully(mInputStream, seekPos);
                    mReadPosition += skipped;
                    break;
                case FFmpegConstants.SEEK_END:
                    /* [BUG FIX] Was: skipped = is.skip(mStreamLength); mReadPosition = mStreamLength;
                     * That overstated the position when mStreamLength was Long.MAX_VALUE
                     * or when the skip was partial. Now we report the actual end. */
                    skipped = skipFully(mInputStream, mStreamLength);
                    mReadPosition += skipped;
                    break;
            }

            Log.d(TAG, "seekInputStream skipped: " + skipped + " whence: " + whence + " readPos: " + mReadPosition + " length: " + mStreamLength + " seekPos: " + seekPos) ;

            return mReadPosition;
        } catch (IOException e) {
            Log.e(TAG, "seekInputStream", e);
        }
        return FFmpegConstants.FFMPEG_AVERROR_EOF;
    }

    /**
     * InputStream.skip is allowed to skip fewer bytes than requested even when
     * not at EOF. FFmpeg expects the seek target to actually be reached, so we
     * loop until we've skipped the full amount or skip() returns 0.
     */
    private static long skipFully(InputStream is, long n) throws IOException {
        if (n <= 0)
            return 0;
        long remaining = n;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped <= 0)
                break;
            remaining -= skipped;
        }
        return n - remaining;
    }

    private int bitmapReadInputStream(byte[] buffer, int offset, int length){
        try{
            if(mInputStream != null){
                //Offset always 0
                int readPosition = mInputStream.read(buffer,offset, length);
                Log.d(TAG, "readInputStream readPos: " + readPosition + " last: " + mReadPosition + " length: " + mStreamLength);
                if (readPosition > 0) {
                    mReadPosition += readPosition;
                } else if (readPosition < 0 && mStreamLength == Long.MAX_VALUE) {
                    mStreamLength = mReadPosition;
                }
                return readPosition;
            }
        }catch(IOException e){
            Log.e(TAG, "readInputStream", e);
        }
        return FFmpegConstants.FFMPEG_AVERROR_EOF;
    }

    private void bitmapRender() {
        mBitmap.copyPixelsFromBuffer(mByteBuffer);
    }

    private ByteBuffer bitmapInit(int size, int width, int height) {
        if(width <= 0  || height <= 0)
            return null;
        mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mByteBuffer = ByteBuffer.allocateDirect(size);
        return mByteBuffer;
    }

    private native int bitmapExtract(long streamPos);

    private native int bitmapSetDataSourceInputStream(InputStream inputStream, Map<String, String> dictionary);

    private native int bitmapSetDataSourceFileDescriptor(FileDescriptor descriptor, Map<String, String> dictionary);

    private native int bitmapSetDataSource(String mFilePath, Map<String, String> dictionary);

    private native int initThumbnailer();

    private native void deallocThumbnailer();


}