/*
 * thumbnailer.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#include <stdlib.h>
#include <stdio.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#include "helpers.h"
#include "thumbnailer.h"
#include "utils.h"

#define LOG_LEVEL 0
#define FFMPEG_LOG_LEVEL AV_LOG_QUIET
#define LOG_TAG "thumbnailer.c"
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}
#define LOGW(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__);}


#include <libavutil/samplefmt.h>
#include <libavutil/avutil.h>
#include <libavutil/avstring.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>


#define STREAM_UNKNOWN (-1)


/**
 * Perform thumbnailing when the input is a video
 *
 * @param ec extraction context to use
 */


struct Thumbnail{

    struct AVFormatContext *format_ctx;

    uint8_t *g_buffer;

    jmethodID bitmap_render_method;

    jmethodID bitmap_init_method;

    jmethodID bitmap_read_input_stream_method;

    jmethodID bitmap_seek_input_stream_method;

    JavaVM *get_javavm;

    jobject thiz;

    int custom_avio; /* [FIX BUG 2] track whether we created the AVIO context */

    /* [BUG FIX] Cached jbyteArray reused across read callbacks. The previous
     * implementation called NewByteArray + DeleteLocalRef on every read; for
     * a typical probe-and-decode FFmpeg makes hundreds of small reads, which
     * thrashed the JNI local-ref table and added pressure on the GC. We keep
     * a global ref sized to the largest seen request and reuse it. */
    jbyteArray read_buf;
    int read_buf_size;
};


enum ThumbnailErrors {
    ERROR_NO_ERROR = 0,

    ERROR_COULD_NOT_ATTACH_THREAD,
    ERROR_COULD_NOT_DETACH_THREAD,
    ERROR_COULD_NOT_GET_JAVA_VM,

    ERROR_COULD_NOT_ALLOCATE_THUMBNAIL,
    ERROR_COULD_NOT_ALLOCATE_CODEC_CTX,
    ERROR_COULD_NOT_PARSE_CODEC_CTX,
    ERROR_COULD_NOT_ALLOCATE_PACKET,

    ERROR_COULD_NOT_GET_PIX_FMT,

    ERROR_CREATE_AVCONTEXT,
    ERROR_CREATE_AVCONTEXT_FD,
    ERROR_CREATE_AVCONTEXT_FD_NULL,
    ERROR_CREATE_AVIOCONTEXT,
    ERROR_CREATE_AVIOCONTEXT_NULL_STREAM,
    ERROR_FIND_STREAM_INFO,
    ERROR_COULD_NOT_ALLOCATE_FRAME,
    ERROR_FAILED_TO_DECODE_FRAME,
    ERROR_COULD_NOT_OPEN_INPUT,

    ERROR_COULD_NOT_INIT_BITMAP_BUFFER,
    ERROR_COULD_NOT_GET_BITMAP_ADDRESS,

    ERROR_NOT_FOUND_THUMBNAILER_CLASS,
    ERROR_NOT_FOUND_INPUSTREAM_READ_METHOD,
    ERROR_NOT_FOUND_INPUSTREAM_SEEK_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_THUMBNAILER_FIELD_JAVA_BITMAP_INIT,
    ERROR_NOT_FOUND_M_NATIVE_THUMBNAILER_FIELD_JAVA_BITMAP_RENDER,
    ERROR_NOT_FOUND_M_NATIVE_THUMBNAILER_FIELD,
    ERROR_COULD_NOT_CREATE_GLOBAL_REF,


};


static int64_t seek_input_stream(void *opaque, int64_t seekPos, int whence){

    struct Thumbnail *thumbnail = (struct Thumbnail *) opaque;

    JNIEnv *env;

    int ret = (*thumbnail->get_javavm)->GetEnv(thumbnail->get_javavm, (void **)&env, JNI_VERSION_1_6);

    if(ret < 0){
        LOGI(1,"seek_input_stream null JNIEnv");
        return AVERROR_EXTERNAL;
    }

    LOGI(6, "seek_input_stream: %"PRId64 " whence %d", seekPos, whence);

    jlong seek_result = (*env)->CallLongMethod(env, thumbnail->thiz,
                                               thumbnail->bitmap_seek_input_stream_method, seekPos, whence);

    /* [NEW FIX] Check for pending Java exception from the seek callback */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE(1, "seek_input_stream Java exception in seek callback");
        return AVERROR_EXTERNAL;
    }

    LOGI(6, "seek_input_stream seek_pos: %"PRId64, seek_result);

    return seek_result;

}

static int read_input_stream(void *opaque, uint8_t *buf, int buf_size)
{

    struct Thumbnail *thumbnail = (struct Thumbnail *) opaque;

    JNIEnv *env;

    int ret = (*thumbnail->get_javavm)->GetEnv(thumbnail->get_javavm, (void **)&env, JNI_VERSION_1_6);

    if(ret < 0){
        LOGI(1,"read_input_stream null JNIEnv");
        return AVERROR_EXTERNAL;
    }

    LOGI(6, "read_input_stream buf_size: %d ", buf_size);

    /* [BUG FIX] Reuse a cached jbyteArray. Allocate (or grow) only when the
     * requested size exceeds the current cache. The cache is a global ref so
     * it survives across calls; freed in dealloc. */
    if (thumbnail->read_buf == NULL || thumbnail->read_buf_size < buf_size) {
        if (thumbnail->read_buf != NULL) {
            (*env)->DeleteGlobalRef(env, thumbnail->read_buf);
            thumbnail->read_buf = NULL;
            thumbnail->read_buf_size = 0;
        }
        jbyteArray local_array = (*env)->NewByteArray(env, buf_size);
        if (local_array == NULL) {
            LOGE(1, "read_input_stream NewByteArray failed");
            return AVERROR(ENOMEM);
        }
        thumbnail->read_buf = (*env)->NewGlobalRef(env, local_array);
        (*env)->DeleteLocalRef(env, local_array);
        if (thumbnail->read_buf == NULL) {
            return AVERROR(ENOMEM);
        }
        thumbnail->read_buf_size = buf_size;
    }

    int bytes_read = (*env)->CallIntMethod(env, thumbnail->thiz,
                                           thumbnail->bitmap_read_input_stream_method,
                                           thumbnail->read_buf, 0, buf_size);

    /* [NEW FIX] Check for pending Java exception from the read callback */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE(1, "read_input_stream Java exception in read callback");
        return AVERROR_EXTERNAL;
    }

    /* [BUG FIX] Distinguish EOF (Java -1) from would-block (0). The previous
     * code mapped only negative to EOF and returned 0 unchanged, which AVIO
     * treats as ambiguous. */
    if (bytes_read < 0) {
        LOGE(1, "read_input_stream EOF");
        return AVERROR_EOF;
    }
    if (bytes_read == 0) {
        return AVERROR(EAGAIN);
    }

    jbyte *dataPtr = (*env)->GetByteArrayElements(env, thumbnail->read_buf, NULL);
    if (dataPtr == NULL) {
        return AVERROR(ENOMEM);
    }
    memcpy(buf, dataPtr, bytes_read);
    (*env)->ReleaseByteArrayElements(env, thumbnail->read_buf, dataPtr, JNI_ABORT);

    return bytes_read;
}

int create_thumbnail(JNIEnv *env, jobject thiz, struct Thumbnail *thumbnail, AVFrame *frame, AVCodecContext *codec_ctx) {

    enum AVPixelFormat pix_fmt = codec_ctx->pix_fmt;
    struct SwsContext *scaler_ctx = NULL;
    AVFrame *dst_scaled_frame = NULL;
    /* [FIX BUG 9] removed unused dst_buffer allocation */
    int thumbnail_wdith = 0;
    int thumbnail_height = 0;
    int err = ERROR_NO_ERROR;
    jobject buf = NULL;
    int buffer_size;

    thumbnail_wdith = codec_ctx->width;
    thumbnail_height = codec_ctx->height;

    LOGI(3, "create_thumbnail width: %d, height: %d, pix_fmt: %d", frame->linesize[0], frame->linesize[1], frame->linesize[2]);

    buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, thumbnail_wdith, thumbnail_height, 1);

    if ((buf = (*env)->CallObjectMethod(env, thiz, thumbnail->bitmap_init_method,
                                        buffer_size, thumbnail_wdith, thumbnail_height)) == NULL) {

        LOGE(2, "create_thumbnail failed to allocate the java destination buffer\n");
        /* [BUG FIX] bitmap_init_method calls Bitmap.createBitmap, which can
         * throw OutOfMemoryError for large dimensions. Clear any pending
         * exception so subsequent JNI calls don't fail in confusing ways. */
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        err = -ERROR_COULD_NOT_INIT_BITMAP_BUFFER;
        goto end;

    }

    if ((thumbnail->g_buffer = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf)) == NULL) {
        LOGE(2, "create_thumbnail failed to get Bitmap address\n");
        err = -ERROR_COULD_NOT_GET_BITMAP_ADDRESS;
        goto end;
    }

    if (NULL == (dst_scaled_frame = av_frame_alloc())) {
        LOGE(2, "create_thumbnail failed to allocate the destination image frame\n");
        err = -ERROR_COULD_NOT_ALLOCATE_FRAME;
        goto end;
    }


    LOGI(3, "create_thumbnail width: %d, height: %d, pix_fmt: %d", codec_ctx->width, codec_ctx->height, pix_fmt);

    if(pix_fmt < 0){
        LOGE(2, "create_thumbnail failed error to retrieve pix_fmt\n");
        err = -ERROR_COULD_NOT_GET_PIX_FMT;
        goto end;
    }

    av_image_fill_arrays(dst_scaled_frame->data, dst_scaled_frame->linesize,
                         (const unsigned char *) thumbnail->g_buffer, AV_PIX_FMT_RGBA,
                         thumbnail_wdith, thumbnail_height, 1);

    LOGI(6, "Using slow conversion");



    scaler_ctx = sws_getCachedContext(scaler_ctx, codec_ctx->width, codec_ctx->height,
                                      pix_fmt, thumbnail_wdith, thumbnail_height,
                                      AV_PIX_FMT_RGBA,
                                      SWS_FAST_BILINEAR, NULL, NULL, NULL);

    if (scaler_ctx == NULL) {
        LOGE(1, "could not initialize conversion context from: %d"
                ", to :%d\n", pix_fmt, AV_PIX_FMT_RGBA);
        err = -ERROR_COULD_NOT_GET_PIX_FMT;
        goto end;
    }

    sws_scale(scaler_ctx, (const uint8_t **) frame->data, frame->linesize,
              0, codec_ctx->height, dst_scaled_frame->data, dst_scaled_frame->linesize);

    (*env)->CallVoidMethod(env, thiz, thumbnail->bitmap_render_method);

    /* [BUG FIX] bitmap_render does Bitmap.copyPixelsFromBuffer which can
     * throw IllegalStateException if the Bitmap was recycled. Clear the
     * exception so cleanup JNI calls below don't see a pending one. */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE(1, "create_thumbnail Java exception in render");
    }

    end:


    if (buf != NULL){
        (*env)->DeleteLocalRef(env, buf);
    }

    if (dst_scaled_frame != NULL) {
        av_frame_free(&dst_scaled_frame);
    }

    if (scaler_ctx != NULL) {
        sws_freeContext(scaler_ctx);
    }


    LOGI(7, "create_thumbnail end\n");

    return err;
}


AVDictionary* set_dict_options(JNIEnv *env, jobject jdict){

    AVDictionaryEntry *tag = NULL;
    AVDictionary *dictionary = NULL;

    if(jdict == NULL)
        return NULL;

    dictionary = utils_read_dictionary(env, jdict);

    utils_set_dict_options(&dictionary);

    while ((tag = av_dict_get(dictionary, "", tag, AV_DICT_IGNORE_SUFFIX))) {
        LOGI(1, "set_dict_options %s=%s\n", tag->key, tag->value);
    }

    return dictionary;

}


int jni_init_thumbnailer(JNIEnv *env, jobject thiz) {

    int err = ERROR_NO_ERROR;

    int ret;

    jfieldID thumbnail_m_native_thumbnailer_field = NULL;

    jclass thumbnail_runnable_class = NULL;

    struct Thumbnail* thumbnail = NULL;

    thumbnail = av_malloc(sizeof(struct Thumbnail));

    if (thumbnail == NULL) {
        /* [BUG FIX] All other error paths return negative codes. The previous
         * version returned positive ERROR_COULD_NOT_ALLOCATE_THUMBNAIL, which
         * a future caller checking `< 0` would silently treat as success. */
        err = -ERROR_COULD_NOT_ALLOCATE_THUMBNAIL;
        goto free_thumbnail;

    }

    memset(thumbnail, 0, sizeof(struct Thumbnail));


    ret = (*env)->GetJavaVM(env, &thumbnail->get_javavm);

    if (ret) {
        err = -ERROR_COULD_NOT_GET_JAVA_VM;
        goto free_thumbnail;
    }

    thumbnail->thiz = (*env)->NewGlobalRef(env, thiz);

    if (thumbnail->thiz == NULL) {
        err = -ERROR_COULD_NOT_CREATE_GLOBAL_REF;
        goto free_thumbnail;
    }

    thumbnail_m_native_thumbnailer_field = java_get_field(env,
                                                          thumbnail_runnable_class_path_name,
                                                          thumbnail_m_native_thumbnailer);

    if (thumbnail_m_native_thumbnailer_field == NULL) {
        /* [BUG FIX] Was returning positive value; same fix as above. */
        err = -ERROR_NOT_FOUND_M_NATIVE_THUMBNAILER_FIELD;
        goto free_thumbnail;
    }

    /* [BUG FIX] DO NOT publish the pointer to Java yet. The previous version
     * called SetLongField here, then proceeded to do method lookups that
     * could fail and free the struct via `goto free_thumbnail`. The Java field
     * was left holding a dangling pointer; the next JNI method call would
     * dereference freed memory. We now SetLongField only after all lookups
     * have succeeded. */

    thumbnail_runnable_class = (*env)->FindClass(env, thumbnail_runnable_class_path_name);

    if (thumbnail_runnable_class == NULL) {
        err = -ERROR_NOT_FOUND_THUMBNAILER_CLASS;
        LOGE(1, "Could not find thumbnail_runnable_class\n");
        goto free_thumbnail;
    }

    thumbnail->bitmap_init_method = java_get_method(env, thumbnail_runnable_class, bitmapInit);

    if (thumbnail->bitmap_init_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_THUMBNAILER_FIELD_JAVA_BITMAP_INIT;
        LOGE(1, "Could not find method javaBitmapInit\n");
        goto free_thumbnail;

    }

    thumbnail->bitmap_render_method = java_get_method(env, thumbnail_runnable_class,
                                                      bitmapRender);


    if (thumbnail->bitmap_render_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_THUMBNAILER_FIELD_JAVA_BITMAP_RENDER;
        LOGE(1, "Could not find method javaBitmapRender\n");
        goto free_thumbnail;

    }

    thumbnail->bitmap_read_input_stream_method = java_get_method(env, thumbnail_runnable_class,
                                                                 bitmapReadInputStream);

    if (thumbnail->bitmap_read_input_stream_method == NULL) {
        err = -ERROR_NOT_FOUND_INPUSTREAM_READ_METHOD;
        LOGE(1, "Could not find method readInputStream\n");
        goto free_thumbnail;

    }

    thumbnail->bitmap_seek_input_stream_method = java_get_method(env, thumbnail_runnable_class,
                                                                 bitmapSeekInputStream);

    if (thumbnail->bitmap_seek_input_stream_method == NULL) {
        err = -ERROR_NOT_FOUND_INPUSTREAM_SEEK_METHOD;
        LOGE(1, "Could not find method seekInputStream\n");
        goto free_thumbnail;

    }

    /* [FIX BUG 2] init custom_avio flag */
    thumbnail->custom_avio = FALSE;

    avformat_network_init();

    /* [BUG FIX] Publish to Java only after every fallible step succeeded. */
    (*env)->SetLongField(env, thiz, thumbnail_m_native_thumbnailer_field,
                         (jlong) thumbnail);

    (*env)->DeleteLocalRef(env, thumbnail_runnable_class);

    goto end;

    free_thumbnail:


    if (thumbnail_runnable_class != NULL) {
        (*env)->DeleteLocalRef(env, thumbnail_runnable_class);
    }

    if (thumbnail != NULL ) {

        if (thumbnail->thiz != NULL) {

            (*env)->DeleteGlobalRef(env, thumbnail->thiz);
            thumbnail->thiz = NULL;
        }

        av_free(thumbnail);
    }

    /* [BUG FIX] Zero the Java field on failure. Otherwise a previous successful
     * init followed by some other failure path (theoretical, given we no longer
     * SetLongField early) could leave a stale value. Cheap and defensive. */
    if (thumbnail_m_native_thumbnailer_field != NULL) {
        (*env)->SetLongField(env, thiz, thumbnail_m_native_thumbnailer_field, 0);
    }

    end:


    return err;
}


struct Thumbnail *thumbnail_get_thumbnailer_field(JNIEnv *env, jobject thiz) {

    jfieldID thumbnail_m_native_thumbnailer_field = java_get_field(env,
                                                                   thumbnail_runnable_class_path_name,
                                                                   thumbnail_m_native_thumbnailer);
    jlong pointer = (*env)->GetLongField(env, thiz, thumbnail_m_native_thumbnailer_field);

    return (struct Thumbnail *) pointer;
}


/**
 * [FIX BUG 11] Helper to clean up format_ctx and custom AVIO if present.
 * Safe to call when format_ctx is NULL.
 */
static void thumbnail_close_format_ctx(struct Thumbnail *thumbnail) {

    if (thumbnail == NULL || thumbnail->format_ctx == NULL)
        return;

    /* [FIX BUG 2] Only free the AVIO buffer if we allocated it ourselves */
    if (thumbnail->custom_avio && thumbnail->format_ctx->pb != NULL) {
        av_freep(&thumbnail->format_ctx->pb->buffer);
        avio_context_free(&thumbnail->format_ctx->pb);
    }

    /* [FIX BUG 1] avformat_close_input calls avformat_free_context internally
     * and sets the pointer to NULL — no need to call avformat_free_context after. */
    avformat_close_input(&(thumbnail->format_ctx));

    thumbnail->custom_avio = FALSE;
}


/* [BUG FIX] Helper to zero the Java mNativeThumbnailer field, mirroring the
 * pattern in downloader.c and metadatareader.c. Without this, a double-call
 * to release() (or any other path that re-fetches the pointer after dealloc)
 * would dereference freed memory. */
static void clean_thumbnail_field(JNIEnv *env, jobject thiz) {
    jfieldID f = java_get_field(env,
                                thumbnail_runnable_class_path_name,
                                thumbnail_m_native_thumbnailer);
    if (f != NULL) {
        (*env)->SetLongField(env, thiz, f, 0);
    }
}


void jni_dealloc_thumbnailer(JNIEnv *env, jobject thiz) {

    struct Thumbnail *thumbnail = thumbnail_get_thumbnailer_field(env, thiz);

    if(thumbnail != NULL) {

        /* [FIX BUG 11] Clean up format_ctx if still allocated (setDataSource
         * succeeded but getBitmap was never called, or caller released early). */
        thumbnail_close_format_ctx(thumbnail);

        thumbnail->g_buffer = NULL;

        /* [BUG FIX] Release cached read buffer global ref. */
        if (thumbnail->read_buf != NULL) {
            (*env)->DeleteGlobalRef(env, thumbnail->read_buf);
            thumbnail->read_buf = NULL;
            thumbnail->read_buf_size = 0;
        }

        if (thumbnail->thiz != NULL) {
            (*env)->DeleteGlobalRef(env, thumbnail->thiz);
            thumbnail->thiz = NULL;
        }

        /* [BUG FIX] av_free was outside the null-check in the original.
         * av_free(NULL) is benign, but moving it inside makes the lifetime
         * obvious and pairs with the field zero-out below. */
        av_free(thumbnail);
    }

    /* [BUG FIX] Zero the Java field so a stray second call sees NULL. */
    clean_thumbnail_field(env, thiz);
}


int jni_extract_bitmap_setdata_source_inputstream(JNIEnv * env, jobject thiz, jobject jstream, jobject jdict){

    struct Thumbnail *thumbnail = thumbnail_get_thumbnailer_field(env, thiz);
    AVDictionary *dictionary = NULL;
    AVIOContext *avio_ctx = NULL;
    uint8_t *avio_ctx_buffer = NULL;
    int avio_ctx_buffer_size = 4096;
    int format_opened = FALSE; /* [FIX BUG 3] track whether avformat_open_input succeeded */
    char errbuf[128];
    int ret;
    int err = ERROR_NO_ERROR;

    if (thumbnail == NULL) {
        return -ERROR_COULD_NOT_ALLOCATE_THUMBNAIL;
    }

    /* [BUG FIX] Defensive close of any prior format_ctx so a second
     * setDataSource doesn't leak the previous one. */
    thumbnail_close_format_ctx(thumbnail);

    if(jstream == NULL) {
        LOGE(1, "jni_extract_bitmap_setdata_source_inputstream NULL stream\n");
        err = -ERROR_CREATE_AVIOCONTEXT_NULL_STREAM;
        goto error;
    }

    dictionary = set_dict_options(env, jdict);

    if (NULL == (thumbnail->format_ctx = avformat_alloc_context())) {
        LOGE(1, "jni_extract_bitmap_setdata_source_inputstream Could not create AVContext\n");
        err = -ERROR_CREATE_AVCONTEXT;
        goto error;
    }

    LOGI(1, "jni_extract_bitmap_setdata_source_inputstream enable AVIO");

    avio_ctx_buffer = av_malloc(avio_ctx_buffer_size);

    if (!avio_ctx_buffer) {
        err = AVERROR(ENOMEM);
        goto error;
    }

    if (NULL == (avio_ctx = avio_alloc_context(avio_ctx_buffer, avio_ctx_buffer_size,
                                               0, thumbnail, &read_input_stream, NULL,
                                               &seek_input_stream))) {
        LOGE(1, "jni_extract_bitmap_setdata_source_inputstream Could not create AVIOCONTEXT\n");
        err = -ERROR_CREATE_AVIOCONTEXT;
        goto error;
    }

    thumbnail->format_ctx->pb = avio_ctx;

    ret = avformat_open_input(&thumbnail->format_ctx, NULL, NULL, &dictionary);

    if (ret < 0) {

        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            strerror(AVUNERROR(ret));

        LOGE(1, "jni_extract_bitmap_setdata_source_inputstream Could not open input stream (%d: %s)\n", ret, errbuf);

        err = -ERROR_COULD_NOT_OPEN_INPUT;

        goto error;

    }

    /* [FIX BUG 3] After avformat_open_input succeeds, it owns the AVIO buffer.
     * NULL out avio_ctx so the error path doesn't double-free it —
     * cleanup is now via avformat_close_input / thumbnail_close_format_ctx. */
    format_opened = TRUE;
    avio_ctx = NULL;

    /* [FIX BUG 2] Mark that we created a custom AVIO context */
    thumbnail->custom_avio = TRUE;

    if (avformat_find_stream_info(thumbnail->format_ctx, NULL) < 0) {
        LOGE(7, "jni_extract_bitmap_setdata_source_inputstream failed to read stream info\n");
        err = -ERROR_FIND_STREAM_INFO;
        goto error;
    }

    LOGI(1, "jni_extract_bitmap_setdata_source_inputstream success");

    goto end;

    error:


    LOGE(1, "jni_extract_bitmap_setdata_source_inputstream error");

    if (format_opened) {
        /* [FIX BUG 3] avformat_open_input succeeded — use the unified cleanup
         * which handles custom AVIO via the custom_avio flag. */
        thumbnail_close_format_ctx(thumbnail);
    } else {
        /* avformat_open_input did NOT succeed — we still own avio_ctx and its buffer */
        if (avio_ctx != NULL) {
            av_freep(&avio_ctx->buffer);
            avio_context_free(&avio_ctx);
        } else if (avio_ctx_buffer != NULL) {
            /* avio_alloc_context failed, buffer was never attached */
            av_freep(&avio_ctx_buffer);
        }

        if (thumbnail->format_ctx != NULL) {
            /* [FIX BUG 1] avformat_close_input handles free_context internally */
            avformat_close_input(&(thumbnail->format_ctx));
        }

        thumbnail->custom_avio = FALSE;
    }

    end:


    if(dictionary != NULL){
        av_dict_free(&dictionary);
    }

    LOGI(1, "jni_extract_bitmap_setdata_source_inputstream end");

    return err;
}


/* [FIX BUG 6] Changed jstring jdescriptor -> jobject jdescriptor to match
 * the actual JNI signature (Ljava/io/FileDescriptor;) */
int jni_extract_bitmap_setdata_source_fd(JNIEnv * env, jobject thiz, jobject jdescriptor, jobject jdict){

    struct Thumbnail *thumbnail = thumbnail_get_thumbnailer_field(env, thiz);
    AVDictionary *dictionary = NULL;
    char errbuf[128];
    char fd_str[128];
    int ret;
    int err = ERROR_NO_ERROR;

    if (thumbnail == NULL) {
        return -ERROR_COULD_NOT_ALLOCATE_THUMBNAIL;
    }

    /* [BUG FIX] Defensive close of any prior format_ctx. */
    thumbnail_close_format_ctx(thumbnail);

    if(jdescriptor == NULL){
        LOGE(1, "jni_extract_bitmap_setdata_source_fd Could not create AVContext\n");
        err = -ERROR_CREATE_AVCONTEXT_FD_NULL;
        goto error;
    }

    int fd = utils_jni_get_file_descriptor(env, jdescriptor);

    if(fd < 0){
        LOGE(1, "jni_extract_bitmap_setdata_source_fd Could not create AVContext\n");
        err = -ERROR_CREATE_AVCONTEXT_FD;
        goto error;
    }

    snprintf(fd_str,128, "%d", fd);

    LOGI(1, "jni_extract_bitmap_setdata_source_fd: %s\n", fd_str);

    dictionary = set_dict_options(env, jdict);

    if(av_dict_set(&dictionary, "fd", fd_str, AV_DICT_IGNORE_SUFFIX) < 0){
        LOGE(1, "jni_extract_bitmap_setdata_source_fd error dict fd");
    }

    if (NULL == (thumbnail->format_ctx = avformat_alloc_context())) {
        LOGE(1, "jni_extract_bitmap_setdata_source_fd Could not create AVContext\n");
        err = -ERROR_CREATE_AVCONTEXT;
        goto error;
    }

    ret = avformat_open_input(&thumbnail->format_ctx, "fd:", NULL, &dictionary);

    if (ret < 0) {

        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            strerror(AVUNERROR(ret));

        LOGE(1, "jni_extract_bitmap_setdata_source_fd Could not open fd (%d: %s)\n", ret, errbuf);

        err = -ERROR_COULD_NOT_OPEN_INPUT;
        goto error;

    }

    if (avformat_find_stream_info(thumbnail->format_ctx, NULL) < 0) {
        LOGE(7, "jni_extract_bitmap_setdata_source_fd failed to read stream info\n");
        err = -ERROR_FIND_STREAM_INFO;
        goto error;
    }

    LOGI(1, "jni_extract_bitmap_setdata_source_fd success");

    goto end;

    error:


    LOGE(1, "jni_extract_bitmap_setdata_source_fd error");

    /* [FIX BUG 1] avformat_close_input handles free_context internally */
    if (thumbnail->format_ctx != NULL) {
        avformat_close_input(&(thumbnail->format_ctx));
    }

    end:


    if(dictionary != NULL){
        av_dict_free(&dictionary);
    }

    LOGI(1, "jni_extract_bitmap_setdata_source_fd end");

    return err;
}

int jni_extract_bitmap_setdata_source(JNIEnv * env, jobject thiz, jstring uri, jobject jdict){

    struct Thumbnail *thumbnail = thumbnail_get_thumbnailer_field(env, thiz);

    const char *file_path = NULL;
    AVDictionary *dict = NULL;
    AVDictionaryEntry *tag = NULL;
    char errbuf[128];
    int ret;
    int err = ERROR_NO_ERROR;

    if (thumbnail == NULL) {
        return -ERROR_COULD_NOT_ALLOCATE_THUMBNAIL;
    }

    /* [BUG FIX] Defensive close of any prior format_ctx. */
    thumbnail_close_format_ctx(thumbnail);

    if (jdict != NULL) {
        dict = utils_read_dictionary(env, jdict);
        utils_set_dict_options(&dict);
    }

    /* [FIX BUG 5] Guard against NULL dict — av_dict_get handles it but
     * make intent clear */
    if (dict != NULL) {
        while ((tag = av_dict_get(dict, "", tag, AV_DICT_IGNORE_SUFFIX))) {
            LOGI(1, "jni_extract_bitmap_setdata_source %s=%s\n", tag->key, tag->value);
        }
    }

    if(uri != NULL){
        file_path = (*env)->GetStringUTFChars(env, uri, NULL);
    }

    if (NULL == (thumbnail->format_ctx = avformat_alloc_context())) {
        LOGE(1, "jni_extract_bitmap_setdata_source Could not create AVContext\n");
        err = -ERROR_CREATE_AVCONTEXT;
        goto error;
    }

    ret = avformat_open_input(&thumbnail->format_ctx, file_path, NULL, &dict);

    if (ret < 0) {

        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            strerror(AVUNERROR(ret));

        LOGE(1, "jni_extract_bitmap_setdata_source Could not open input stream (%d: %s)\n", ret, errbuf);

        err = -ERROR_COULD_NOT_OPEN_INPUT;
        goto error;

    }

    if (avformat_find_stream_info(thumbnail->format_ctx, NULL) < 0) {
        LOGE(7, "jni_extract_bitmap_setdata_source failed to read stream info\n");
        err = -ERROR_FIND_STREAM_INFO;
        goto error;
    }

    LOGI(1, "jni_extract_bitmap_setdata_source success");

    goto end;

    error:


    LOGE(1, "jni_extract_bitmap_setdata_source error");

    /* [FIX BUG 1] avformat_close_input handles free_context internally */
    if (thumbnail->format_ctx != NULL) {
        avformat_close_input(&(thumbnail->format_ctx));
    }

    end:


    if(dict != NULL){
        av_dict_free(&dict);
    }

    /* [FIX BUG 4] Release JNI string — was leaked on every call */
    if(file_path != NULL){
        (*env)->ReleaseStringUTFChars(env, uri, file_path);
    }

    LOGI(1, "jni_extract_bitmap_setdata_source end: %d", err);

    return err;
}


int jni_extract_bitmap (JNIEnv * env, jobject thiz, jlong stream_pos){

    struct Thumbnail *thumbnail = thumbnail_get_thumbnailer_field(env, thiz);
    AVPacket *packet = NULL;
    AVStream *stream;
    AVCodecContext *codec_ctx = NULL;
    AVDictionary *opts = NULL;
    AVFrame *frame = NULL;
    int64_t seek_target;
    int flags = AVSEEK_FLAG_ANY;
    int ret = ERROR_NO_ERROR;
    int err;
    int got_frame = 0; /* [FIX BUG 7] initialize to avoid UB in cleanup */
    int video_stream_index;

    /* [NEW FIX] Defensive guard — format_ctx must be set by a prior setDataSource call */
    if (thumbnail == NULL || thumbnail->format_ctx == NULL) {
        LOGE(1, "jni_extract_bitmap called with NULL thumbnail or format_ctx");
        return -ERROR_CREATE_AVCONTEXT;
    }

    packet = av_packet_alloc();

    if (!packet){
        LOGE(1, "jni_extract_bitmap Could not allocate packet\n");
        ret = -ERROR_COULD_NOT_ALLOCATE_PACKET;
        goto end;
    }

    frame = av_frame_alloc();

    if (frame == NULL) {
        LOGE(1, "jni_extract_bitmap failed to allocate frame");
        ret = -ERROR_COULD_NOT_ALLOCATE_FRAME;
        goto end;
    }

    video_stream_index = av_find_best_stream(thumbnail->format_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);

    LOGI(7, "jni_extract_bitmap video_stream_index %d\n", video_stream_index);

    if (video_stream_index >= 0) {

        stream = thumbnail->format_ctx->streams[video_stream_index];

        codec_ctx = avcodec_alloc_context3(NULL);

        if (codec_ctx == NULL){
            LOGE(2, "jni_extract_bitmap failed to allocate codec_ctx\n");
            ret = -ERROR_COULD_NOT_ALLOCATE_CODEC_CTX;
            goto end;
        }

        if (avcodec_parameters_to_context(codec_ctx, stream->codecpar) < 0) {
            LOGE(2, "jni_extract_bitmap failed to copy codec_ctx\n");
            ret = -ERROR_COULD_NOT_PARSE_CODEC_CTX;
            goto end;
        }

        codec_ctx->pkt_timebase = stream->time_base;
        av_opt_set_int(codec_ctx, "refcounted_frames", 0, 0);
        av_opt_set(codec_ctx, "threads", "auto", 0);

        const AVCodec *codec = avcodec_find_decoder(codec_ctx->codec_id);

        if (codec == NULL) {
            LOGE(2, "jni_extract_bitmap failed to find decoder\n");
            /* [BUG FIX] Was returning ERROR_NO_ERROR (0). Java ignores the
             * return value, but consistency matters and a future caller
             * checking < 0 would silently miss this failure. */
            ret = -ERROR_FAILED_TO_DECODE_FRAME;
            goto end;
        }

        LOGI(1, "jni_extract_bitmap avcodec_get_name(codec->id) %s:", avcodec_get_name(codec->id));

        if (avcodec_open2(codec_ctx, codec, &opts) < 0) {
            LOGE(2, "jni_extract_bitmap failed to open codec_ctx\n");
            ret = -ERROR_FAILED_TO_DECODE_FRAME;
            goto end;
        }

        LOGI(1, "jni_extract_bitmap seek_target: %"PRId64" duration: %"PRId64, stream_pos, stream->duration);

        if(stream_pos > 0){

            seek_target = av_rescale_q(stream_pos, AV_TIME_BASE_Q,stream->time_base);

            LOGI(1, "jni_extract_bitmap seek_target adjusted: %"PRId64, seek_target);

            if (seek_target >= stream->duration){
                flags = AVSEEK_FLAG_BACKWARD;
                seek_target = stream->duration;
            }


            LOGI(1, "jni_extract_bitmap seek_target adjusted max: %"PRId64, seek_target);

            err = av_seek_frame(thumbnail->format_ctx, video_stream_index, seek_target, flags);

            if (err >= 0) {
                avcodec_flush_buffers(codec_ctx);
            }


        }

        while (1) {

            ret = av_read_frame(thumbnail->format_ctx, packet);

            /* [FIX BUG 8] Check av_read_frame return BEFORE accessing packet fields */
            if (ret < 0) {
                LOGI(1, "jni_extract_bitmap av_read_frame returned %d", ret);
                break;
            }

            LOGI(1, "jni_extract_bitmap packet index: %d index: %d" ,packet->stream_index, video_stream_index );

            if (packet->stream_index == video_stream_index) {

                if((ret = utils_decode_frame(codec_ctx, frame, &got_frame, packet)) < 0 ){
                    LOGE(1, "jni_extract_bitmap decode_frame ret: %d", ret);
                    ret = -ERROR_FAILED_TO_DECODE_FRAME;
                    av_packet_unref(packet);
                    goto end;
                }

                LOGI(1, "got_frame %d", got_frame);

                if(got_frame){
                    if (create_thumbnail(env, thiz, thumbnail, frame, codec_ctx) < 0) {
                        LOGE(1, "jni_extract_bitmap failed to create bitmap");
                        ret = -ERROR_FAILED_TO_DECODE_FRAME;
                        av_packet_unref(packet);
                        goto end;
                    }
                    av_packet_unref(packet);
                    break;
                }
                av_frame_unref(frame);
            }

            av_packet_unref(packet);
        }

    }

    end:



    if (codec_ctx != NULL) {
        if (frame != NULL) {
            utils_decode_frame(codec_ctx, frame, &got_frame, NULL);
        }
        avcodec_free_context(&codec_ctx);
    }

    if (frame != NULL) {
        av_frame_free(&frame);
    }

    if (packet != NULL){
        av_packet_free(&packet);
    }

    /* [NEW FIX] Free opts dictionary — avcodec_open2 may leave residual entries */
    if (opts != NULL){
        av_dict_free(&opts);
    }

    /* [BUG FIX] Do NOT close format_ctx here. Closing it after a single
     * getBitmap() call prevents the API from being used as
     *   setDataSource(...) → getBitmap(t1) → getBitmap(t2) → release()
     * which is the natural pattern for sampling multiple thumbnails from
     * one source. format_ctx is now closed only by:
     *   - the next setDataSource (defensive close at entry), or
     *   - jni_dealloc_thumbnailer when Java calls release().
     * Per-call codec_ctx, frame, and packet are still freed above. */

    LOGI(7, "jni_extract_bitmap free jni_extract_bitmap\n");

    return ret;

}