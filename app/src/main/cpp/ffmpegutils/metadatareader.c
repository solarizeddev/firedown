/*
 * metadatareader.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>

#include <android/log.h>
#include <jni.h>

#include "helpers.h"
#include "metadatareader.h"
#include "utils.h"


#define LOG_LEVEL 0
#define FFMPEG_LOG_LEVEL AV_LOG_QUIET
#define LOG_TAG "metadatareader.c"
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}
#define LOGW(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__);}


#define INPUTSTREAM_BUFFER_SIZE 65536

#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libavutil/avutil.h>
#include <libavutil/avstring.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>


#define STREAM_UNKNOWN (-1)


/**
 * Opaque context passed to the AVIO read/seek callbacks for InputStream-based
 * inputs. Each open input gets its own StreamContext so the callbacks can route
 * to the correct Java InputStream via the stream_index.
 */
struct StreamContext {
    struct MetadataReader *reader;
    int stream_index; // 0..nb_inputs-1
    jobject direct_buf; // [OOM FIX] pre-allocated DirectByteBuffer, reused per read
    uint8_t *direct_buf_ptr; // native pointer backing direct_buf
    int direct_buf_size; // current capacity of direct_buf
};


struct MetadataReader{

    // Multi-input support: up to MAX_METADATA_STREAMS format contexts
    struct AVFormatContext *format_ctx[MAX_METADATA_STREAMS];
    int nb_inputs; // how many are actually open (1 for legacy single-input calls)

    // Per-input stream context for AVIO callbacks (only used for InputStream paths)
    struct StreamContext stream_contexts[MAX_METADATA_STREAMS];

    uint8_t *g_buffer;

    volatile int interrupt;

    AVIOInterruptCB interrupt_callback;

    jmethodID init_bitmap_method;

    jmethodID render_bitmap_method;

    jmethodID error_bitmap_method;

    jmethodID set_duration_method;

    jmethodID set_stream_info_method;

    jmethodID set_meta_data_method;

    jmethodID set_input_format_name_method;

    jmethodID read_input_stream_method;

    jmethodID seek_input_stream_method;

    jobject thiz;

    JavaVM *get_javavm;
};


/* Note: this file used to define a `struct State` bundling MetadataReader+JNIEnv
 * for use as the AVIO interrupt callback's opaque pointer. That pattern was
 * unsafe because State was stack-allocated in JNI entry frames; the callback
 * now takes the heap-allocated MetadataReader directly, so State is unused. */


enum MetadataErrors {
    ERROR_NO_ERROR = 0,

    ERROR_COULD_NOT_ATTACH_THREAD,
    ERROR_COULD_NOT_DETACH_THREAD,
    ERROR_COULD_NOT_GET_JAVA_VM,

    ERROR_NOT_FOUND_METADATA_CLASS,
    ERROR_NOT_FOUND_SET_STREAM_INFO_NATIVE_METHOD,
    ERROR_NOT_FOUND_SET_META_DATA_NATIVE_METHOD,
    ERROR_NOT_FOUND_BITMAP_RENDER_NATIVE_METHOD,
    ERROR_NOT_FOUND_BITMAP_ERROR_NATIVE_METHOD,
    ERROR_NOT_FOUND_INIT_BITMAP_NATIVE_METHOD,
    ERROR_NOT_FOUND_INIT_SET_DURATION_METHOD,
    ERROR_NOT_FOUND_SET_INPUT_FORMAT_NAME_METHOD,

    ERROR_COULD_NOT_ALLOCATE_READER,
    ERROR_COULD_NOT_ALLOCATE_CODEC_CTX,
    ERROR_COULD_NOT_PARSE_CODEC_CTX,

    ERROR_COULD_NOT_INIT_BITMAP_BUFFER,
    ERROR_COULD_NOT_GET_BITMAP_ADDRESS,
    ERROR_COULD_NOT_ALLOCATE_DST_BUFFER,
    ERROR_COULD_NOT_GET_PIX_FMT,
    ERROR_COULD_NOT_ALLOCATE_PACKET,

    ERROR_CREATE_AVCONTEXT,
    ERROR_FIND_STREAM_INFO,
    ERROR_REPORT_INFO,
    ERROR_COULD_NOT_ALLOCATE_FRAME,
    ERROR_FAILED_TO_DECODE_FRAME,
    ERROR_COULD_NOT_OPEN_INPUT,

    ERROR_CREATE_AVIOCONTEXT,
    ERROR_NOT_FOUND_INPUSTREAM_SEEK_METHOD,
    ERROR_NOT_FOUND_INPUSTREAM_READ_METHOD,

    ERROR_COULD_NOT_ALLOCATE_MEMORY,
    ERROR_NOT_FOUND_M_NATIVE_READER_FIELD,
    ERROR_COULD_NOT_CREATE_GLOBAL_REF,

    ERROR_TOO_MANY_INPUTS,
    ERROR_INPUT_COUNT_MISMATCH,

};


/* [BUG FIX] The opaque pointer must be the heap-allocated MetadataReader,
 * not a transient `struct State` from a JNI entry frame. The previous version
 * stored a pointer to a stack variable into the AVFormatContext; if any
 * AVFormatContext outlived its JNI call the callback would dereference
 * garbage. With the heap pointer the callback is always safe. */
static int metadatareader_ctx_interrupt_callback(void *p) {
    struct MetadataReader *metadataReader = (struct MetadataReader *) p;
    if (metadataReader == NULL || metadataReader->interrupt) {
        return 1;
    }
    return 0;
}


/* [BUG FIX] Do NOT reset `interrupt` here. This function is called once per
 * input inside the open loop; the previous version cleared the flag on every
 * call, which silently undid a stop() request that arrived between opening
 * input N and input N+1. The flag is now cleared exactly once at the start
 * of each extraction, in the JNI entry points. */
int metadatareader_create_interrupt_callback(struct MetadataReader *metadataReader, int input_index) {
    LOGI(3, "metadatareader_ctx_interrupt_setup input_index=%d", input_index);
    metadataReader->interrupt_callback = (AVIOInterruptCB){
            metadatareader_ctx_interrupt_callback, metadataReader
    };
    metadataReader->format_ctx[input_index]->interrupt_callback = metadataReader->interrupt_callback;
    metadataReader->format_ctx[input_index]->flags |= AVFMT_FLAG_NONBLOCK;
    return 0;
}


/* =========================================================================
 * Thread-local JNIEnv with auto-detach
 *
 * The AVIO read/seek callbacks may be invoked by FFmpeg from a thread that
 * isn't attached to the JVM. The previous version of get_jni_env attached
 * but never detached, leaking the JNIEnv when FFmpeg created its own
 * helper threads. We now register a pthread destructor that calls
 * DetachCurrentThread when the thread exits.
 *
 * State stored under the key is just the JavaVM* — non-NULL means "this
 * thread was attached by us and should be detached on exit". Threads that
 * were already attached (the JNI caller thread) leave the key NULL and
 * are not detached.
 * ========================================================================= */

static pthread_key_t mdr_jvm_key;
static pthread_once_t mdr_jvm_key_once = PTHREAD_ONCE_INIT;

static void mdr_detach_jvm(void *value) {
    JavaVM *jvm = (JavaVM *) value;
    if (jvm != NULL) {
        (*jvm)->DetachCurrentThread(jvm);
    }
}

static void mdr_make_jvm_key(void) {
    pthread_key_create(&mdr_jvm_key, mdr_detach_jvm);
}

static JNIEnv *get_jni_env(JavaVM *jvm) {
    JNIEnv *env;
    int status = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status == JNI_EDETACHED) {
        if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != 0) {
            return NULL;
        }
        /* Mark this thread for detach-on-exit. We only set the key here,
         * after a successful attach we performed ourselves; threads that
         * arrived already attached never have the key set, so they aren't
         * detached out from under their owner. */
        pthread_once(&mdr_jvm_key_once, mdr_make_jvm_key);
        pthread_setspecific(mdr_jvm_key, jvm);
        return env;
    }
    return NULL;
}


/**
 * AVIO seek callback for InputStream inputs.
 * The opaque pointer is a StreamContext which carries the stream_index
 * so the Java side knows which InputStream to seek.
 */
static int64_t seek_input_stream(void *opaque, int64_t seekPos, int whence){

    struct StreamContext *sc = (struct StreamContext *) opaque;
    struct MetadataReader *metadata = sc->reader;
    JNIEnv *env;

    env = get_jni_env(metadata->get_javavm);

    if (!env)
        return AVERROR_EXTERNAL;

    LOGI(6, "seek_input_stream[%d]: %"PRId64 " whence %d", sc->stream_index, seekPos, whence);

    jlong seek_result = (*env)->CallLongMethod(env, metadata->thiz,
                                               metadata->seek_input_stream_method,
                                               (jint) sc->stream_index, seekPos, whence);

    /* [FIX] Check for pending Java exception from seek callback */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE(1, "seek_input_stream[%d] Java exception in seek callback", sc->stream_index);
        return AVERROR_EXTERNAL;
    }

    LOGI(6, "seek_input_stream[%d] seek_pos: %"PRId64, sc->stream_index, seek_result);

    return seek_result;

}

/**
 * AVIO read callback for InputStream inputs.
 * Routes to Java readInputStream(int streamIndex, ByteBuffer, int length).
 *
 * [OOM FIX] Reuses a pre-allocated DirectByteBuffer per StreamContext instead
 * of allocating a new one per call. The old approach created thousands of
 * DirectByteBuffer objects during format probing, overwhelming the GC's
 * Cleaner/finalizer thread and causing OOM.
 */
static int read_input_stream(void *opaque, uint8_t *buf, int buf_size) {
    struct StreamContext *sc = (struct StreamContext *) opaque;
    struct MetadataReader *metadata = sc->reader;
    JNIEnv *env;

    env = get_jni_env(metadata->get_javavm);

    if (!env)
        return AVERROR_EXTERNAL;

    /* Reuse or create the DirectByteBuffer for this stream context.
     * We only reallocate if the AVIO buffer pointer or size changed,
     * which happens at most once (when avio_alloc_context sets up). */
    if (sc->direct_buf == NULL || sc->direct_buf_ptr != buf || sc->direct_buf_size < buf_size) {
        /* Free old global ref if pointer/size changed */
        if (sc->direct_buf != NULL) {
            (*env)->DeleteGlobalRef(env, sc->direct_buf);
            sc->direct_buf = NULL;
        }

        jobject local_buf = (*env)->NewDirectByteBuffer(env, buf, buf_size);
        if (!local_buf) {
            return AVERROR(ENOMEM);
        }
        sc->direct_buf = (*env)->NewGlobalRef(env, local_buf);
        (*env)->DeleteLocalRef(env, local_buf);

        if (!sc->direct_buf) {
            return AVERROR(ENOMEM);
        }

        sc->direct_buf_ptr = buf;
        sc->direct_buf_size = buf_size;
    }

    int bytes_read = (*env)->CallIntMethod(env, metadata->thiz,
                                           metadata->read_input_stream_method,
                                           (jint) sc->stream_index,
                                           sc->direct_buf, buf_size);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return AVERROR_EXTERNAL;
    }

    /* [BUG FIX] Java's ReadableByteChannel.read returns -1 at EOF, 0 only
     * on rare non-blocking paths. The previous code mapped both to
     * AVERROR_EOF, which would prematurely truncate the stream if the
     * channel ever returned 0. Now: positive → bytes read, negative → EOF
     * (Java's -1 or our explicit AVERROR_EOF), zero → retry signal. */
    if (bytes_read > 0) {
        return bytes_read;
    }
    if (bytes_read < 0) {
        return AVERROR_EOF;
    }
    return AVERROR(EAGAIN);
}


int jni_init_metadatareader(JNIEnv *env, jobject thiz) {

    int err = ERROR_NO_ERROR;

    jclass metadata_runnable_class = NULL;

    jobject temp_buf = NULL;

    struct MetadataReader* metadata = NULL;

    jfieldID metadata_m_native_reader_field = NULL;

    LOGI(6, "jni_init_metadatareader");

    metadata = av_calloc(1, sizeof(struct MetadataReader));

    if (metadata == NULL) {
        /* [BUG FIX] All other error paths return negative codes so Java's
         * `if (result < 0)` check fires. The previous code returned positive
         * ERROR_COULD_NOT_ALLOCATE_READER, silently swallowed by the check. */
        err = -ERROR_COULD_NOT_ALLOCATE_READER;
        goto free_metadata;
    }

    metadata->nb_inputs = 0;

    int ret = (*env)->GetJavaVM(env, &metadata->get_javavm);

    if (ret) {
        err = -ERROR_COULD_NOT_GET_JAVA_VM;
        goto free_metadata;
    }

    metadata->thiz = (*env)->NewGlobalRef(env, thiz);

    if (metadata->thiz == NULL) {
        err = -ERROR_COULD_NOT_CREATE_GLOBAL_REF;
        goto free_metadata;
    }


    metadata_m_native_reader_field = java_get_field(env,
                                                    metadatareader_runnable_class_path_name,
                                                    metadatareader_m_native_reader);
    if (metadata_m_native_reader_field == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_READER_FIELD;
        goto free_metadata;
    }


    metadata_runnable_class = (*env)->FindClass(env, metadatareader_runnable_class_path_name);

    if ((*env)->ExceptionCheck(env) || metadata_runnable_class == NULL) {
        err = -ERROR_NOT_FOUND_METADATA_CLASS;
        LOGE(1, "Could not find thumbnail_runnable_class\n");
        goto free_metadata;
    }


    metadata->read_input_stream_method = java_get_method(env, metadata_runnable_class,
                                                         stream_read_input);

    if (metadata->read_input_stream_method == NULL) {
        err = -ERROR_NOT_FOUND_INPUSTREAM_READ_METHOD;
        LOGE(1, "Could not find method readInputStream\n");
        goto free_metadata;
    }


    metadata->seek_input_stream_method = java_get_method(env, metadata_runnable_class,
                                                         stream_seek_input);

    if (metadata->seek_input_stream_method == NULL) {
        err = -ERROR_NOT_FOUND_INPUSTREAM_SEEK_METHOD;
        LOGE(1, "Could not find method seekInputStream\n");
        goto free_metadata;
    }


    metadata->set_duration_method = java_get_method(env, metadata_runnable_class,
                                                    metadatareader_set_duration);

    if (metadata->set_duration_method == NULL) {
        err = -ERROR_NOT_FOUND_INIT_SET_DURATION_METHOD;
        LOGE(1, "Could not find method setDuration\n");
        goto free_metadata;

    }

    metadata->init_bitmap_method = java_get_method(env, metadata_runnable_class,
                                                   metadatareader_bitmap_init);

    if (metadata->init_bitmap_method == NULL) {
        err = -ERROR_NOT_FOUND_INIT_BITMAP_NATIVE_METHOD;
        LOGE(1, "Could not find method javaBitmapInit\n");
        goto free_metadata;

    }

    metadata->render_bitmap_method = java_get_method(env, metadata_runnable_class,
                                                     metadatareader_bitmap_render);


    if (metadata->render_bitmap_method == NULL) {
        err = -ERROR_NOT_FOUND_BITMAP_RENDER_NATIVE_METHOD;
        LOGE(1, "Could not find method javaBitmapRender\n");
        goto free_metadata;

    }


    metadata->error_bitmap_method = java_get_method(env, metadata_runnable_class,
                                                    metadatareader_bitmap_error);

    if (metadata->error_bitmap_method == NULL) {
        err = -ERROR_NOT_FOUND_BITMAP_ERROR_NATIVE_METHOD;
        LOGE(1, "Could not find method javaBitmapError\n");
        goto free_metadata;

    }

    metadata->set_stream_info_method = java_get_method(env,
                                                       metadata_runnable_class, metadatareader_set_streams_info);
    if (metadata->set_stream_info_method == NULL) {
        err = -ERROR_NOT_FOUND_SET_STREAM_INFO_NATIVE_METHOD;
        goto free_metadata;
    }

    metadata->set_meta_data_method = java_get_method(env,
                                                     metadata_runnable_class, metadatareader_set_meta_data);
    if (metadata->set_meta_data_method == NULL) {
        err = -ERROR_NOT_FOUND_SET_META_DATA_NATIVE_METHOD;
        goto free_metadata;
    }

    metadata->set_input_format_name_method = java_get_method(env,
                                                             metadata_runnable_class, metadatareader_set_input_format_name);
    if (metadata->set_input_format_name_method == NULL) {
        err = -ERROR_NOT_FOUND_SET_INPUT_FORMAT_NAME_METHOD;
        goto free_metadata;
    }

    // All lookups succeeded — now commit the pointer to Java
    (*env)->SetLongField(env, thiz, metadata_m_native_reader_field,
                         (jlong)metadata);

    if(metadata_runnable_class != NULL){
        (*env)->DeleteLocalRef(env, metadata_runnable_class);
    }

    av_log_set_callback(utils_ffmpeg_log_callback);

    av_log_set_level(FFMPEG_LOG_LEVEL);

    goto end;

    free_metadata:


    if(temp_buf != NULL){
        (*env)->DeleteLocalRef(env, temp_buf);
    }

    if(metadata_runnable_class != NULL){
        (*env)->DeleteLocalRef(env, metadata_runnable_class);
    }

    if (metadata != NULL && metadata->thiz != NULL) {
        (*env)->DeleteGlobalRef(env, metadata->thiz);
        metadata->thiz = NULL;
    }

    if (metadata != NULL) {
        av_free(metadata);
        metadata = NULL;
    }

    // Ensure Java field is zeroed so dealloc doesn't chase a dangling pointer
    if (metadata_m_native_reader_field != NULL) {
        (*env)->SetLongField(env, thiz, metadata_m_native_reader_field, 0);
    }

    end:


    LOGI(6, "jni_init_metadatareader");

    return err;
}


struct MetadataReader *metadatareader_get_reader_field(JNIEnv *env, jobject thiz) {

    jfieldID metadatareader_m_native_reader_field = java_get_field(env,metadatareader_runnable_class_path_name,metadatareader_m_native_reader);

    jlong pointer = (*env)->GetLongField(env, thiz, metadatareader_m_native_reader_field);

    return (struct MetadataReader *) pointer;
}


void jni_stop_metadatareader(JNIEnv *env, jobject thiz) {

    struct MetadataReader *metadataReader = metadatareader_get_reader_field(env, thiz);

    if (metadataReader != NULL) {
        /* `interrupt` is volatile so this write is visible to the
         * extraction thread without locking. */
        metadataReader->interrupt = TRUE;
    }
}

void clean_metadata_field(JNIEnv *env, jobject thiz) {

    jfieldID metadata_m_native_reader_field = java_get_field(env,
                                                             metadatareader_runnable_class_path_name,
                                                             metadatareader_m_native_reader);

    (*env)->SetLongField(env, thiz, metadata_m_native_reader_field, 0);

}

void jni_dealloc_metadatareader(JNIEnv *env, jobject thiz) {

    struct MetadataReader *metadataReader = metadatareader_get_reader_field(env, thiz);

    LOGI(6, "jni_dealloc_metadatareader");

    if (metadataReader != NULL) {

        /* [OOM FIX] Clean up any lingering DirectByteBuffer global refs */
        for (int i = 0; i < MAX_METADATA_STREAMS; i++) {
            if (metadataReader->stream_contexts[i].direct_buf != NULL) {
                (*env)->DeleteGlobalRef(env, metadataReader->stream_contexts[i].direct_buf);
                metadataReader->stream_contexts[i].direct_buf = NULL;
            }
        }

        for (int i = 0; i < metadataReader->nb_inputs; i++) {
            if (metadataReader->format_ctx[i]) {
                avformat_close_input(&metadataReader->format_ctx[i]);
            }
        }

        if (metadataReader->thiz != NULL) {
            (*env)->DeleteGlobalRef(env, metadataReader->thiz);
            metadataReader->thiz = NULL;
        }

        av_free(metadataReader);

    }

    clean_metadata_field(env, thiz);
}


int metadata_create_thumbnail(JNIEnv *env, jobject thiz, struct MetadataReader *metadataReader, AVFrame *frame, AVCodecContext *codec_ctx) {

    enum AVPixelFormat pix_fmt = codec_ctx->pix_fmt;
    struct SwsContext *scaler_ctx = NULL;
    AVFrame *dst_scaled_frame = NULL;
    int err = ERROR_NO_ERROR;
    jobject buf = NULL;
    int buffer_size;


    LOGI(3, "create_thumbnail width: %d, height: %d, pix_fmt: %d", frame->linesize[0], frame->linesize[1], frame->linesize[2]);

    buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, codec_ctx->width, codec_ctx->height, 1);

    if ((buf = (*env)->CallObjectMethod(env, thiz, metadataReader->init_bitmap_method,
                                        buffer_size, codec_ctx->width, codec_ctx->height)) == NULL) {
        LOGE(2, "create_thumbnail failed to allocate the java destination buffer\n");
        /* [BUG FIX] Clear any exception thrown by bitmapInit (e.g. OOM
         * creating a large Bitmap). Without this, the next JNI call would
         * fail in confusing ways. */
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        (*env)->CallVoidMethod(env, thiz, metadataReader->error_bitmap_method);
        err = -ERROR_COULD_NOT_INIT_BITMAP_BUFFER;
        goto end;

    }

    if ((metadataReader->g_buffer = (uint8_t *)(*env)->GetDirectBufferAddress(env, buf)) == NULL) {
        LOGE(2, "create_thumbnail failed to get Bitmap address\n");
        err = -ERROR_COULD_NOT_GET_BITMAP_ADDRESS;
        goto end;
    }


    if (NULL == (dst_scaled_frame = av_frame_alloc())) {
        LOGE(2, "create_thumbnail failed to allocate the destination image frame\n");
        (*env)->CallVoidMethod(env, thiz, metadataReader->error_bitmap_method);
        err = -ERROR_COULD_NOT_ALLOCATE_FRAME;
        goto end;
    }


    LOGI(3, "create_thumbnail width: %d, height: %d, pix_fmt: %d", codec_ctx->width, codec_ctx->height, pix_fmt);

    if(pix_fmt < 0){
        LOGE(2, "create_thumbnail failed error to retrieve pix_fmt\n");
        (*env)->CallVoidMethod(env, thiz, metadataReader->error_bitmap_method);
        err = -ERROR_COULD_NOT_GET_PIX_FMT;
        goto end;
    }

    av_image_fill_arrays(dst_scaled_frame->data, dst_scaled_frame->linesize,
                         (const unsigned char *) metadataReader->g_buffer, AV_PIX_FMT_RGBA,
                         codec_ctx->width, codec_ctx->height, 1);

    LOGI(6, "Using slow conversion");


    scaler_ctx = sws_getCachedContext(scaler_ctx, codec_ctx->width, codec_ctx->height,
                                      pix_fmt, codec_ctx->width, codec_ctx->height,
                                      AV_PIX_FMT_RGBA,
                                      SWS_FAST_BILINEAR, NULL, NULL, NULL);

    if (scaler_ctx == NULL) {
        LOGE(1, "could not initialize conversion context from: %d"
                ", to :%d\n", pix_fmt, AV_PIX_FMT_RGBA);
        (*env)->CallVoidMethod(env, thiz, metadataReader->error_bitmap_method);
        err = -ERROR_COULD_NOT_GET_PIX_FMT;
        goto end;
    }

    sws_scale(scaler_ctx, (const uint8_t **) frame->data, frame->linesize,
              0, codec_ctx->height, dst_scaled_frame->data, dst_scaled_frame->linesize);

    (*env)->CallVoidMethod(env, thiz, metadataReader->render_bitmap_method);

    /* [BUG FIX] Clear any exception from bitmapRender so subsequent JNI
     * calls (in cleanup) don't see a pending exception. */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE(1, "create_thumbnail Java exception in render_bitmap_method");
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


/**
 * Report stream info from ALL open format contexts merged into a single
 * FFmpegStreamInfo[] array. This is the core of the multi-input merge:
 * streams from each input are concatenated sequentially.
 */
int metadata_report_info_multi(JNIEnv *env, jobject thiz, struct MetadataReader *metadataReader) {

    int err = ERROR_NO_ERROR;
    char name[300] = {0};
    AVDictionaryEntry *tag = NULL;
    enum AVPixelFormat pixel_format;
    enum AVSampleFormat sample_format;
    jobjectArray array = NULL;

    jclass stream_info_class = (*env)->FindClass(env,
                                                 stream_info_class_path_name);

    if((*env)->ExceptionCheck(env) || stream_info_class == NULL){
        err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
        goto end;
    }

    jmethodID stream_info_set_metadata_method = java_get_method(env,
                                                                stream_info_class,
                                                                stream_info_set_metadata);
    jmethodID stream_info_set_media_type_internal_method = java_get_method(env,
                                                                           stream_info_class,
                                                                           stream_info_set_media_type_internal);
    jmethodID stream_info_set_stream_number_method = java_get_method(env,
                                                                     stream_info_class,
                                                                     stream_info_set_stream_number);

    jmethodID stream_info_set_stream_selected_number_method = java_get_method(env,
                                                                              stream_info_class,
                                                                              stream_info_set_selected_stream_number);

    jmethodID stream_info_set_stream_frame_rate_method = java_get_method(env,
                                                                         stream_info_class,
                                                                         stream_info_set_frame_rate);

    jmethodID stream_info_set_stream_width_method = java_get_method(env,
                                                                    stream_info_class,
                                                                    stream_info_set_width);

    jmethodID stream_info_set_stream_height_method = java_get_method(env,
                                                                     stream_info_class,
                                                                     stream_info_set_height);

    jmethodID stream_info_set_stream_encoded_width_method = java_get_method(env,
                                                                            stream_info_class,
                                                                            stream_info_set_encoded_width);

    jmethodID stream_info_set_stream_encoded_height_method = java_get_method(env,
                                                                             stream_info_class,
                                                                             stream_info_set_encoded_height);

    jmethodID stream_info_set_stream_pixel_format_method = java_get_method(env,
                                                                           stream_info_class,
                                                                           stream_info_pixel_format);

    jmethodID stream_info_set_stream_sample_format_method = java_get_method(env,
                                                                            stream_info_class,
                                                                            stream_info_sample_format);

    jmethodID stream_info_set_stream_sampling_rate_method = java_get_method(env,
                                                                            stream_info_class,
                                                                            stream_info_set_sampling_rate);

    jmethodID stream_info_set_stream_codec_name_method = java_get_method(env,
                                                                         stream_info_class,
                                                                         stream_info_set_codec_name);

    jmethodID stream_info_constructor = java_get_method(env, stream_info_class,
                                                        empty_constructor);

    jclass hash_map_class = (*env)->FindClass(env, hash_map_class_path_name);

    if((*env)->ExceptionCheck(env) || hash_map_class == NULL){
        err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
        goto free_streaminfo_class;
    }

    jmethodID hash_map_constructor = java_get_method(env, hash_map_class,
                                                     empty_constructor);

    jclass map_class = (*env)->FindClass(env, map_class_path_name);

    if((*env)->ExceptionCheck(env) || map_class == NULL){
        err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
        goto free_hashmap_class;
    }

    jmethodID map_put_method = java_get_method(env, map_class, map_put);

    // Count total streams across all inputs
    int total_streams = 0;
    for (int i = 0; i < metadataReader->nb_inputs; i++) {
        total_streams += metadataReader->format_ctx[i]->nb_streams;
    }

    int size = (jsize) total_streams + 1;

    array = (*env)->NewObjectArray(env, size,
                                   stream_info_class, NULL);

    if ((*env)->ExceptionCheck(env) || array == NULL) {
        err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
        goto free_map_class;
    }


    // Merged metadata map across all inputs
    jobject meta_map = (*env)->NewObject(env, hash_map_class,
                                         hash_map_constructor);

    if ((*env)->ExceptionCheck(env) || meta_map == NULL) {
        err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
        goto free_map;
    }

    // Collect container-level metadata from all inputs
    for (int i = 0; i < metadataReader->nb_inputs; i++) {
        tag = NULL;
        while ((tag = av_dict_get(metadataReader->format_ctx[i]->metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
            jobject key = utils_read_av_entry(env, tag->key);
            jobject value = utils_read_av_entry(env, tag->value);

            if (key && value) {
                jobject previous = (*env)->CallObjectMethod(env, meta_map, map_put_method, key, value);
                if (previous)
                    (*env)->DeleteLocalRef(env, previous);
            }

            if (key) (*env)->DeleteLocalRef(env, key);
            if (value) (*env)->DeleteLocalRef(env, value);
        }
    }

    (*env)->CallVoidMethod(env, thiz, metadataReader->set_meta_data_method, meta_map);

    // Iterate all streams across all inputs, using a global index for the Java array
    int global_stream_idx = 0;

    for (int input_idx = 0; input_idx < metadataReader->nb_inputs && err == ERROR_NO_ERROR; input_idx++) {

        AVFormatContext *fmt_ctx = metadataReader->format_ctx[input_idx];

        for (int stream_no = 0; stream_no < fmt_ctx->nb_streams && err == ERROR_NO_ERROR; stream_no++) {

            /* [OOM FIX] Push a local ref frame to prevent accumulation across
             * many streams. 32 slots covers: stream_info, map, codec_name,
             * metadata key/value pairs, and some headroom. */
            if ((*env)->PushLocalFrame(env, 32) < 0) {
                err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
                break;
            }

            AVStream *stream = fmt_ctx->streams[stream_no];
            AVCodecParameters *codec = stream->codecpar;
            AVDictionary *metadata = stream->metadata;
            int selected_stream = 0;
            int fps = 0;


            jobject stream_info = (*env)->NewObject(env, stream_info_class,
                                                    stream_info_constructor);

            if ((*env)->ExceptionCheck(env) || stream_info == NULL) {
                err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
                (*env)->PopLocalFrame(env, NULL);
                break;
            }


            jobject map = (*env)->NewObject(env, hash_map_class,
                                            hash_map_constructor);

            if ((*env)->ExceptionCheck(env) || map == NULL) {
                err = -ERROR_COULD_NOT_ALLOCATE_MEMORY;
                (*env)->PopLocalFrame(env, NULL);
                break;
            }

            enum CodecType codec_type = CODEC_TYPE_UNKNOWN;
            if (codec->codec_type == AVMEDIA_TYPE_AUDIO) {
                codec_type = CODEC_TYPE_AUDIO;
            } else if (codec->codec_type == AVMEDIA_TYPE_VIDEO) {
                codec_type = CODEC_TYPE_VIDEO;
            } else if (codec->codec_type == AVMEDIA_TYPE_SUBTITLE) {
                codec_type = CODEC_TYPE_SUBTITLE;
            } else if (codec->codec_type == AVMEDIA_TYPE_ATTACHMENT) {
                codec_type = CODEC_TYPE_ATTACHMENT;
            } else if (codec->codec_type == AVMEDIA_TYPE_NB) {
                codec_type = CODEC_TYPE_NB;
            } else if (codec->codec_type == AVMEDIA_TYPE_DATA) {
                codec_type = CODEC_TYPE_DATA;
            }


            jobject codec_name = (*env)->NewStringUTF(env, avcodec_get_name(codec->codec_id));

            if (stream_info_set_stream_codec_name_method != NULL) {
                (*env)->CallVoidMethod(env, stream_info,
                                       stream_info_set_stream_codec_name_method, (jstring) codec_name);
            }

            (*env)->DeleteLocalRef(env, codec_name);

            if (stream_info_set_media_type_internal_method != NULL) {
                (*env)->CallVoidMethod(env, stream_info,
                                       stream_info_set_media_type_internal_method, (jint) codec_type);
            }

            // Use global_stream_idx so Java sees a unified numbering across all inputs
            if (stream_info_set_stream_number_method != NULL) {
                (*env)->CallVoidMethod(env, stream_info,
                                       stream_info_set_stream_number_method, (jint) global_stream_idx);
            }

            if (stream_info_set_stream_number_method != NULL) {
                (*env)->CallVoidMethod(env, stream_info,
                                       stream_info_set_stream_selected_number_method, (jint) selected_stream);
            }


            if (codec->codec_type == AVMEDIA_TYPE_VIDEO) {

                fps = (int)av_q2d(stream->r_frame_rate);

                if (stream_info_set_stream_frame_rate_method != NULL) {
                    (*env)->CallVoidMethod(env, stream_info,
                                           stream_info_set_stream_frame_rate_method, (jint) fps);
                }

                if (stream_info_set_stream_width_method != NULL) {
                    (*env)->CallVoidMethod(env, stream_info,
                                           stream_info_set_stream_width_method, (jint) codec->width);
                }

                if (stream_info_set_stream_height_method != NULL) {
                    (*env)->CallVoidMethod(env, stream_info,
                                           stream_info_set_stream_height_method, (jint) codec->height);
                }

                if (stream_info_set_stream_pixel_format_method != NULL) {

                    pixel_format = codec->format;

                    av_get_pix_fmt_string(name,sizeof(name),pixel_format);

                    LOGI(3, "jni_extract_metadata pixel format: %s pixel: %d\n", name, pixel_format);

                    (*env)->CallVoidMethod(env, stream_info,
                                           stream_info_set_stream_pixel_format_method, (jint) pixel_format);
                }

            }

            if (codec->codec_type == AVMEDIA_TYPE_AUDIO) {

                if (stream_info_set_stream_sampling_rate_method != NULL) {
                    (*env)->CallVoidMethod(env, stream_info,
                                           stream_info_set_stream_sampling_rate_method, (jint) codec->sample_rate);
                }

                if (stream_info_set_stream_sample_format_method != NULL) {

                    sample_format = codec->format;

                    LOGI(3, "jni_extract_metadata sample format: %s sample: %d\n", av_get_sample_fmt_name(sample_format), sample_format);

                    (*env)->CallVoidMethod(env, stream_info,
                                           stream_info_set_stream_sample_format_method, (jint) sample_format);
                }

            }

            tag = NULL;
            while ((tag = av_dict_get(metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
                jobject key = utils_read_av_entry(env, tag->key);
                jobject value = utils_read_av_entry(env, tag->value);

                if (key && value) {
                    /* [FIX BUG 8] Write stream metadata into per-stream map, not meta_map */
                    jobject previous = (*env)->CallObjectMethod(env, map, map_put_method, key, value);
                    if (previous)
                        (*env)->DeleteLocalRef(env, previous);
                }

                if (key)
                    (*env)->DeleteLocalRef(env, key);
                if (value)
                    (*env)->DeleteLocalRef(env, value);
            }

            if (stream_info_set_metadata_method != NULL) {
                (*env)->CallVoidMethod(env, stream_info,
                                       stream_info_set_metadata_method, map);
            }


            (*env)->SetObjectArrayElement(env, array, global_stream_idx, stream_info);

            /* [OOM FIX] Pop the local frame — all local refs created inside
             * the iteration (stream_info, map, codec_name, metadata keys/values)
             * are released in one shot. The objects stored in the array survive
             * because SetObjectArrayElement created strong refs in the array. */
            (*env)->PopLocalFrame(env, NULL);

            global_stream_idx++;
        }
    }


    if (err == ERROR_NO_ERROR && metadataReader->set_stream_info_method != NULL) {
        (*env)->CallVoidMethod(env, thiz, metadataReader->set_stream_info_method, array);
    }

    (*env)->DeleteLocalRef(env, meta_map);
    free_map:

    (*env)->DeleteLocalRef(env, array);
    free_map_class:

    (*env)->DeleteLocalRef(env, map_class);
    free_hashmap_class:

    (*env)->DeleteLocalRef(env, hash_map_class);
    free_streaminfo_class:

    (*env)->DeleteLocalRef(env, stream_info_class);
    end:

    return err;
}


/**
 * Legacy single-input report_info — delegates to multi with nb_inputs=1.
 */
int metadata_report_info(JNIEnv *env, jobject thiz, struct MetadataReader *metadataReader) {
    return metadata_report_info_multi(env, thiz, metadataReader);
}


/**
 * Extract bitmap thumbnail from the first input that has a video stream.
 * input_index specifies which format_ctx to use.
 */
int metadata_extract_bitmap_from(JNIEnv *env, jobject thiz, struct MetadataReader *metadataReader, int input_index) {

    AVPacket *packet = NULL;
    AVStream *stream = NULL;
    AVCodecContext *codec_ctx = NULL;
    AVDictionary *opts = NULL;
    AVFrame *frame = NULL;
    int ret;
    int err = ERROR_NO_ERROR;
    int got_frame = 0;
    int video_stream_index;

    AVFormatContext *fmt_ctx = metadataReader->format_ctx[input_index];

    video_stream_index = av_find_best_stream(fmt_ctx, AVMEDIA_TYPE_VIDEO, -1, -1,
                                             NULL, 0);

    if (video_stream_index < 0)
        return err;

    LOGI(3, "jni_extract_bitmap video_stream_index %d input %d\n", video_stream_index, input_index);

    for (int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (i != video_stream_index) {
            fmt_ctx->streams[i]->discard = AVDISCARD_ALL;
        }
    }

    packet = av_packet_alloc();

    if (packet == NULL) {
        LOGE(1, "extract_bitmap Could not allocate packet\n");
        err = -ERROR_COULD_NOT_ALLOCATE_PACKET;
        goto end;
    }

    frame = av_frame_alloc();

    if (frame == NULL) {
        LOGE(1, "extract_bitmap failed to allocate frame");
        err = -ERROR_COULD_NOT_ALLOCATE_FRAME;
        goto end;
    }

    stream = fmt_ctx->streams[video_stream_index];

    codec_ctx = avcodec_alloc_context3(NULL);

    if (codec_ctx == NULL) {
        LOGE(2, "jni_extract_bitmap failed to allocate codec_ctx\n");
        err = -ERROR_COULD_NOT_ALLOCATE_CODEC_CTX;
        goto end;
    }

    if (avcodec_parameters_to_context(codec_ctx, stream->codecpar) < 0) {
        LOGE(2, "jni_extract_bitmap failed to copy codec_ctx\n");
        err = -ERROR_COULD_NOT_PARSE_CODEC_CTX;
        goto end;
    }

    codec_ctx->pkt_timebase = stream->time_base;

    av_opt_set_int(codec_ctx, "refcounted_frames", 0, 0);
    av_opt_set(codec_ctx, "threads", "auto", 0);

    const AVCodec *codec = avcodec_find_decoder(codec_ctx->codec_id);

    if (codec == NULL) {
        LOGE(2, "jni_extract_bitmap failed to find decoder\n");
        err = -ERROR_FAILED_TO_DECODE_FRAME;
        goto end;
    }

    if (avcodec_open2(codec_ctx, codec, &opts) < 0) {
        LOGE(2, "jni_extract_bitmap failed to open codec_ctx\n");
        err = -ERROR_FAILED_TO_DECODE_FRAME;
        goto end;
    }

    if (fmt_ctx->pb)
        fmt_ctx->pb->eof_reached = 0;


    while (av_read_frame(fmt_ctx, packet) >= 0) {
        if (packet->stream_index == video_stream_index) {
            ret = utils_decode_frame(codec_ctx, frame, &got_frame, packet);
            if (got_frame) {
                metadata_create_thumbnail(env, thiz, metadataReader, frame, codec_ctx);
                av_packet_unref(packet);
                break;
            }
        }
        av_packet_unref(packet);
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

    if (packet != NULL) {
        av_packet_free(&packet);
    }

    av_dict_free(&opts);

    LOGI(3, "jni_extract_bitmap free jni_extract_bitmap\n");

    return err;

}

/**
 * Legacy wrapper: extract bitmap from the first (and only) input.
 */
int metadata_extract_bitmap(JNIEnv *env, jobject thiz, struct MetadataReader *metadataReader) {
    // Find the first input that has a video stream
    for (int i = 0; i < metadataReader->nb_inputs; i++) {
        if (av_find_best_stream(metadataReader->format_ctx[i], AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0) >= 0) {
            return metadata_extract_bitmap_from(env, thiz, metadataReader, i);
        }
    }
    return ERROR_NO_ERROR;
}


/**
 * Compute the maximum duration across all open inputs.
 */
static int64_t metadata_get_max_duration(struct MetadataReader *metadataReader) {
    int64_t max_duration = AV_NOPTS_VALUE;

    for (int i = 0; i < metadataReader->nb_inputs; i++) {
        int64_t d = metadataReader->format_ctx[i]->duration;
        if (d != AV_NOPTS_VALUE && d > 0) {
            if (max_duration == AV_NOPTS_VALUE || d > max_duration) {
                max_duration = d;
            }
        }
    }

    return max_duration;
}


/**
 * Close all open format contexts and reset nb_inputs.
 */
static void metadata_close_all_inputs(struct MetadataReader *metadataReader) {
    for (int i = 0; i < metadataReader->nb_inputs; i++) {
        if (metadataReader->format_ctx[i] != NULL) {
            // avformat_close_input frees the context and sets the pointer to NULL
            avformat_close_input(&metadataReader->format_ctx[i]);
        }
    }
    metadataReader->nb_inputs = 0;
}


// ============================================================================
// JNI entry points
// ============================================================================

/**
 * Extract metadata from one or more URL inputs and merge their streams.
 * For a single input, Java passes a length-1 array.
 *
 * @param jurls     String[] of URLs
 * @param jdicts    Map<String,String>[] of per-URL dictionaries (nullable entries ok)
 * @param jextract  whether to extract a bitmap thumbnail
 */
int jni_extract_metadata(JNIEnv *env, jobject thiz, jobjectArray jurls, jobjectArray jdicts, jboolean jextract) {

    struct MetadataReader *metadataReader = metadatareader_get_reader_field(env, thiz);
    AVDictionary *dicts[MAX_METADATA_STREAMS] = {NULL};
    char *file_paths[MAX_METADATA_STREAMS] = {NULL};
    jstring jstrings[MAX_METADATA_STREAMS] = {NULL};
    char errbuf[128];
    int ret;
    int err = ERROR_NO_ERROR;
    int extract = jextract == JNI_TRUE ? TRUE : FALSE;
    int nb_urls = 0;
    int nb_dicts = 0;

    if (metadataReader == NULL) {
        return -ERROR_COULD_NOT_ALLOCATE_READER;
    }

    /* [BUG FIX] Clear interrupt exactly once at the start of an extraction.
     * The previous code cleared it inside metadatareader_create_interrupt_callback
     * which fired per input — so a stop() racing in mid-loop was lost. */
    metadataReader->interrupt = FALSE;

    if (jurls == NULL) {
        err = -ERROR_COULD_NOT_OPEN_INPUT;
        goto end;
    }

    nb_urls = (*env)->GetArrayLength(env, jurls);
    nb_dicts = jdicts != NULL ? (*env)->GetArrayLength(env, jdicts) : 0;

    if (nb_urls <= 0) {
        err = -ERROR_COULD_NOT_OPEN_INPUT;
        goto end;
    }

    if (nb_urls > MAX_METADATA_STREAMS) {
        LOGE(1, "jni_extract_metadata too many inputs: %d (max %d)\n", nb_urls, MAX_METADATA_STREAMS);
        err = -ERROR_TOO_MANY_INPUTS;
        goto end;
    }

    metadataReader->nb_inputs = nb_urls;

    // Open each input
    for (int i = 0; i < nb_urls; i++) {

        jstrings[i] = (jstring)(*env)->GetObjectArrayElement(env, jurls, i);

        if (jstrings[i] == NULL) {
            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }

        file_paths[i] = (char *)(*env)->GetStringUTFChars(env, jstrings[i], NULL);

        if (file_paths[i] == NULL) {
            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }

        if (NULL == (metadataReader->format_ctx[i] = avformat_alloc_context())) {
            LOGE(1, "jni_extract_metadata Could not create AVContext for input %d\n", i);
            err = -ERROR_CREATE_AVCONTEXT;
            goto end;
        }

        // Read per-input dictionary if available
        if (jdicts != NULL && i < nb_dicts) {
            jobject jdict = (*env)->GetObjectArrayElement(env, jdicts, i);
            if (jdict != NULL) {
                dicts[i] = utils_read_dictionary(env, jdict);
                utils_set_dict_options(&dicts[i]);
                (*env)->DeleteLocalRef(env, jdict);
            }
        }

        metadatareader_create_interrupt_callback(metadataReader, i);

        LOGI(1, "jni_extract_metadata open input[%d]: %s", i, file_paths[i]);

        if ((ret = avformat_open_input(&metadataReader->format_ctx[i], file_paths[i], NULL, &dicts[i])) < 0) {

            const char *errbuf_ptr = errbuf;

            if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
                errbuf_ptr = strerror(AVUNERROR(ret));

            LOGE(3, "jni_extract_metadata Could not open input[%d]: %s (%d: %s)\n",
                 i, file_paths[i], ret, errbuf_ptr);

            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }

        if (avformat_find_stream_info(metadataReader->format_ctx[i], NULL) < 0) {
            LOGE(1, "jni_extract_metadata failed to read stream info for input %d\n", i);
            err = -ERROR_FIND_STREAM_INFO;
            goto end;
        }
    }

    // Use the format name from the first input (primary)
    if (metadataReader->set_input_format_name_method != NULL) {
        jstring jstrBuf = (*env)->NewStringUTF(env, metadataReader->format_ctx[0]->iformat->name);
        (*env)->CallVoidMethod(env, thiz, metadataReader->set_input_format_name_method, jstrBuf);
        (*env)->DeleteLocalRef(env, jstrBuf);
    }

    if (extract && metadata_extract_bitmap(env, thiz, metadataReader) < 0) {
        LOGE(1, "jni_extract_metadata error extracting bitmap\n");
    }

    if (metadata_report_info(env, thiz, metadataReader) < 0) {
        LOGE(1, "jni_extract_metadata error reporting input info\n");
        err = -ERROR_REPORT_INFO;
        goto end;
    }

    int64_t duration = metadata_get_max_duration(metadataReader);

    LOGI(6, "jni_extract_metadata duration %"PRId64, duration);

    if (metadataReader->set_duration_method != NULL) {
        (*env)->CallVoidMethod(env, thiz, metadataReader->set_duration_method,
                               duration != AV_NOPTS_VALUE ? duration : (int64_t)0);
    }

    end:


    for (int i = 0; i < MAX_METADATA_STREAMS; i++) {
        if (dicts[i] != NULL) {
            av_dict_free(&dicts[i]);
        }
    }

    metadata_close_all_inputs(metadataReader);

    for (int i = 0; i < nb_urls; i++) {
        if (file_paths[i] != NULL && jstrings[i] != NULL) {
            (*env)->ReleaseStringUTFChars(env, jstrings[i], file_paths[i]);
        }
        if (jstrings[i] != NULL) {
            (*env)->DeleteLocalRef(env, jstrings[i]);
        }
    }

    LOGI(6, "jni_extract_metadata done err=%d\n", err);

    return err;
}


/**
 * Extract metadata from multiple InputStream inputs and merge their streams.
 *
 * @param istreams   InputStream[] of input streams
 * @param filenames  String[] of filenames (for format probing hints)
 * @param jextract   whether to extract a bitmap thumbnail
 */
int jni_extract_metadata_inputstream(JNIEnv *env, jobject thiz, jobjectArray istreams, jobjectArray filenames, jboolean jextract) {

    struct MetadataReader *metadataReader = metadatareader_get_reader_field(env, thiz);
    AVIOContext *avio_ctxs[MAX_METADATA_STREAMS] = {NULL};
    const char *file_paths[MAX_METADATA_STREAMS] = {NULL};
    jstring jfilenames[MAX_METADATA_STREAMS] = {NULL};
    char errbuf[128];
    int ret;
    int err = ERROR_NO_ERROR;
    int extract = jextract == JNI_TRUE ? TRUE : FALSE;
    int nb_streams = 0;
    int nb_filenames = 0;

    if (metadataReader == NULL) {
        return -ERROR_COULD_NOT_ALLOCATE_READER;
    }

    /* [BUG FIX] Clear interrupt exactly once. See jni_extract_metadata. */
    metadataReader->interrupt = FALSE;

    if (istreams == NULL || filenames == NULL) {
        err = -ERROR_COULD_NOT_OPEN_INPUT;
        goto end;
    }

    nb_streams = (*env)->GetArrayLength(env, istreams);
    nb_filenames = (*env)->GetArrayLength(env, filenames);

    if (nb_streams <= 0) {
        err = -ERROR_COULD_NOT_OPEN_INPUT;
        goto end;
    }

    if (nb_streams != nb_filenames) {
        LOGE(1, "jni_extract_metadata_inputstream streams(%d) != filenames(%d)\n", nb_streams, nb_filenames);
        err = -ERROR_INPUT_COUNT_MISMATCH;
        goto end;
    }

    if (nb_streams > MAX_METADATA_STREAMS) {
        LOGE(1, "jni_extract_metadata_inputstream too many inputs: %d (max %d)\n", nb_streams, MAX_METADATA_STREAMS);
        err = -ERROR_TOO_MANY_INPUTS;
        goto end;
    }

    metadataReader->nb_inputs = nb_streams;

    // Open each InputStream input
    for (int i = 0; i < nb_streams; i++) {

        jfilenames[i] = (jstring)(*env)->GetObjectArrayElement(env, filenames, i);

        if (jfilenames[i] == NULL) {
            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }

        file_paths[i] = (*env)->GetStringUTFChars(env, jfilenames[i], NULL);

        if (file_paths[i] == NULL) {
            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }

        /* The Java side has already populated mInputStreams[i] / mInputChannels[i]
         * before calling us. We don't need the InputStream object on the C side —
         * the read/seek callbacks use the stream_index to route back into Java.
         * We just verify the array slot is non-null as a sanity check. */
        jobject stream_obj = (*env)->GetObjectArrayElement(env, istreams, i);
        if (stream_obj == NULL) {
            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }
        (*env)->DeleteLocalRef(env, stream_obj);

        // Set up per-input stream context
        metadataReader->stream_contexts[i].reader = metadataReader;
        metadataReader->stream_contexts[i].stream_index = i;

        if (NULL == (metadataReader->format_ctx[i] = avformat_alloc_context())) {
            LOGE(1, "jni_extract_metadata_inputstream Could not create AVContext for input %d\n", i);
            err = -ERROR_CREATE_AVCONTEXT;
            goto end;
        }

        metadatareader_create_interrupt_callback(metadataReader, i);

        uint8_t *avio_ctx_buffer = av_malloc(INPUTSTREAM_BUFFER_SIZE);

        if (!avio_ctx_buffer) {
            err = AVERROR(ENOMEM);
            goto end;
        }

        avio_ctxs[i] = avio_alloc_context(avio_ctx_buffer, INPUTSTREAM_BUFFER_SIZE,
                                          0, &metadataReader->stream_contexts[i],
                                          &read_input_stream, NULL,
                                          &seek_input_stream);

        if (avio_ctxs[i] == NULL) {
            LOGE(1, "jni_extract_metadata_inputstream Could not create AVIOCONTEXT for input %d\n", i);
            av_free(avio_ctx_buffer);
            err = -ERROR_CREATE_AVIOCONTEXT;
            goto end;
        }

        metadataReader->format_ctx[i]->pb = avio_ctxs[i];

        LOGI(1, "jni_extract_metadata_inputstream open input[%d]: %s", i, file_paths[i]);

        if ((ret = avformat_open_input(&metadataReader->format_ctx[i], file_paths[i], NULL, NULL)) < 0) {

            const char *errbuf_ptr = errbuf;

            if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
                errbuf_ptr = strerror(AVUNERROR(ret));

            LOGE(3, "jni_extract_metadata_inputstream Could not open input[%d]: (%d: %s)\n",
                 i, ret, errbuf_ptr);

            err = -ERROR_COULD_NOT_OPEN_INPUT;
            goto end;
        }

        if (avformat_find_stream_info(metadataReader->format_ctx[i], NULL) < 0) {
            LOGE(1, "jni_extract_metadata_inputstream failed to read stream info for input %d\n", i);
            err = -ERROR_FIND_STREAM_INFO;
            goto end;
        }
    }

    // Use the format name from the first input (primary)
    if (metadataReader->set_input_format_name_method != NULL) {
        jstring jstrBuf = (*env)->NewStringUTF(env, metadataReader->format_ctx[0]->iformat->name);
        (*env)->CallVoidMethod(env, thiz, metadataReader->set_input_format_name_method, jstrBuf);
        (*env)->DeleteLocalRef(env, jstrBuf);
    }

    if (extract && metadata_extract_bitmap(env, thiz, metadataReader) < 0) {
        LOGE(1, "jni_extract_metadata_inputstream error extracting bitmap\n");
    }

    if (metadata_report_info(env, thiz, metadataReader) < 0) {
        LOGE(1, "jni_extract_metadata_inputstream error reporting input info\n");
        err = -ERROR_REPORT_INFO;
        goto end;
    }

    int64_t duration = metadata_get_max_duration(metadataReader);

    LOGI(6, "jni_extract_metadata_inputstream duration %"PRId64, duration);

    if (metadataReader->set_duration_method != NULL) {
        (*env)->CallVoidMethod(env, thiz, metadataReader->set_duration_method,
                               duration != AV_NOPTS_VALUE ? duration : (int64_t)0);
    }

    end:


    metadata_close_all_inputs(metadataReader);

    for (int i = 0; i < MAX_METADATA_STREAMS; i++) {
        /* Free the AVIO context and its buffer first, then release the
         * DirectByteBuffer global ref that pointed into it. */
        if (avio_ctxs[i] != NULL) {
            av_freep(&avio_ctxs[i]->buffer);
            avio_context_free(&avio_ctxs[i]);
        }
        /* [OOM FIX] Free the pre-allocated DirectByteBuffer global ref */
        if (metadataReader->stream_contexts[i].direct_buf != NULL) {
            (*env)->DeleteGlobalRef(env, metadataReader->stream_contexts[i].direct_buf);
            metadataReader->stream_contexts[i].direct_buf = NULL;
            metadataReader->stream_contexts[i].direct_buf_ptr = NULL;
            metadataReader->stream_contexts[i].direct_buf_size = 0;
        }
    }

    for (int i = 0; i < nb_streams; i++) {
        if (file_paths[i] != NULL && jfilenames[i] != NULL) {
            (*env)->ReleaseStringUTFChars(env, jfilenames[i], file_paths[i]);
        }
        if (jfilenames[i] != NULL) {
            (*env)->DeleteLocalRef(env, jfilenames[i]);
        }
    }

    LOGI(6, "jni_extract_metadata_inputstream done err=%d\n", err);

    return err;
}