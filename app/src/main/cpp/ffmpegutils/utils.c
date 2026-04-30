/*
 * utils.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#include <android/log.h>

#include "utils.h"
#include "helpers.h"


#define LOG_LEVEL 0
#define LOG_TAG "utils.c"
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}
#define LOGW(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__);}


/*
 * Map an FFmpeg log level to an Android log priority.
 *
 * FFmpeg log levels are *higher = more verbose*:
 *    AV_LOG_QUIET   = -8   (no output)
 *    AV_LOG_PANIC   =  0
 *    AV_LOG_FATAL   =  8
 *    AV_LOG_ERROR   = 16
 *    AV_LOG_WARNING = 24
 *    AV_LOG_INFO    = 32
 *    AV_LOG_VERBOSE = 40
 *    AV_LOG_DEBUG   = 48
 *    AV_LOG_TRACE   = 56
 *
 * The previous version of this function used `level > AV_LOG_*` comparisons
 * starting from AV_LOG_DEBUG, which mapped almost everything to
 * ANDROID_LOG_DEBUG. The correct mapping walks from least-verbose
 * (most severe) to most-verbose using `<=`.
 */
void utils_ffmpeg_log_callback(void *avcl, int level, const char *fmt,
                               va_list vl) {
    if (level > av_log_get_level())
        return;

    int android_level;
    if (level <= AV_LOG_PANIC) android_level = ANDROID_LOG_FATAL;
    else if (level <= AV_LOG_FATAL) android_level = ANDROID_LOG_FATAL;
    else if (level <= AV_LOG_ERROR) android_level = ANDROID_LOG_ERROR;
    else if (level <= AV_LOG_WARNING) android_level = ANDROID_LOG_WARN;
    else if (level <= AV_LOG_INFO) android_level = ANDROID_LOG_INFO;
    else if (level <= AV_LOG_VERBOSE) android_level = ANDROID_LOG_VERBOSE;
    else android_level = ANDROID_LOG_DEBUG;

    __android_log_vprint(android_level, "ffmpeg", fmt, vl);
}


AVDictionary *utils_read_dictionary(JNIEnv *env, jobject jdictionary) {
    AVDictionary *opts = NULL;

    jclass map_class = (*env)->FindClass(env, map_class_path_name);
    jclass set_class = (*env)->FindClass(env, set_class_path_name);
    jclass iterator_class = (*env)->FindClass(env, iterator_class_path_name);

    jmethodID map_key_set_method = java_get_method(env, map_class, map_key_set);
    jmethodID map_get_method = java_get_method(env, map_class, map_get);
    jmethodID set_iterator_method = java_get_method(env, set_class, set_iterator);
    jmethodID iterator_next_method = java_get_method(env, iterator_class, iterator_next);
    jmethodID iterator_has_next_method = java_get_method(env, iterator_class, iterator_has_next);

    jobject jkey_set = (*env)->CallObjectMethod(env, jdictionary, map_key_set_method);
    jobject jiterator = (*env)->CallObjectMethod(env, jkey_set, set_iterator_method);

    while ((*env)->CallBooleanMethod(env, jiterator, iterator_has_next_method)) {
        jobject jkey = (*env)->CallObjectMethod(env, jiterator, iterator_next_method);
        jobject jvalue = (*env)->CallObjectMethod(env, jdictionary, map_get_method, jkey);

        const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
        const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);

        LOGI(2, "utils_read_dictionary set key: %s value: %s", key, value);

        if (av_dict_set(&opts, key, value, 0) < 0) {
            LOGE(2, "utils_read_dictionary: could not set key");
        }

        (*env)->ReleaseStringUTFChars(env, jkey, key);
        (*env)->ReleaseStringUTFChars(env, jvalue, value);
        (*env)->DeleteLocalRef(env, jkey);
        (*env)->DeleteLocalRef(env, jvalue);
    }

    (*env)->DeleteLocalRef(env, jiterator);
    (*env)->DeleteLocalRef(env, jkey_set);
    (*env)->DeleteLocalRef(env, map_class);
    (*env)->DeleteLocalRef(env, set_class);
    (*env)->DeleteLocalRef(env, iterator_class);

    return opts;
}


void utils_set_dict_options(AVDictionary **dictionary) {
    if (av_dict_set(dictionary, "http_persistent", "0", AV_DICT_DONT_OVERWRITE) < 0) {
        LOGE(1, "utils_set_dict_options error http_persistent");
    }
    if (av_dict_set(dictionary, "pattern_type", "none", AV_DICT_DONT_OVERWRITE) < 0) {
        LOGE(1, "utils_set_dict_options error pattern_type");
    }
    if (av_dict_set(dictionary, "allowed_extensions", "ALL", AV_DICT_DONT_OVERWRITE) < 0) {
        LOGE(1, "utils_set_dict_options error allowed_extensions");
    }
}


/* ============================================================
 * Used by metadatareader.c (not by the downloader).
 * Kept here because both modules link against utils.
 * ============================================================ */

/*
 * Wrap a C string into a java/lang/String constructed from a UTF-8 byte
 * array. Used to expose AVDictionaryEntry tag keys/values to Java.
 */
jobject utils_read_av_entry(JNIEnv *env, char *entry) {
    jstring object = NULL;
    jstring strEncode = NULL;
    jclass cls = NULL;
    jmethodID ctor;

    size_t size = strlen(entry);
    jbyteArray array = (*env)->NewByteArray(env, (jsize) size);

    if ((*env)->ExceptionCheck(env) || array == NULL) {
        goto end;
    }

    (*env)->SetByteArrayRegion(env, array, 0, (jsize) size, (const jbyte *) entry);

    strEncode = (*env)->NewStringUTF(env, "UTF-8");
    if ((*env)->ExceptionCheck(env) || strEncode == NULL) {
        goto end;
    }

    cls = (*env)->FindClass(env, "java/lang/String");
    ctor = (*env)->GetMethodID(env, cls, "<init>", "([BLjava/lang/String;)V");

    object = (jstring) (*env)->NewObject(env, cls, ctor, array, strEncode);
    if ((*env)->ExceptionCheck(env) || object == NULL) {
        goto end;
    }

    end:
    if (cls != NULL) {
        (*env)->DeleteLocalRef(env, cls);
    }
    return object;
}


/*
 * Send-then-receive helper around the modern avcodec API. Returns 0 on
 * EAGAIN/EOF (with *got_frame untouched as 0), the avcodec_receive_frame
 * return value when a frame is produced, or a negative error otherwise.
 *
 * The outer while(1) is intentionally entered once: the inner loop returns
 * directly. The structure leaves room for future retry logic without
 * changing call sites.
 */
int utils_decode_frame(AVCodecContext *codec_ctx, AVFrame *frame,
                       int *got_frame, AVPacket *packet) {
    int ret;
    *got_frame = 0;

    while (1) {
        ret = avcodec_send_packet(codec_ctx, packet);
        if (ret < 0) {
            if (ret == AVERROR(EAGAIN)) {
                return 0;
            }
            LOGE(1, "utils_decode_frame avcodec_send_packet %s\n", av_err2str(ret));
            return ret;
        }

        while (1) {
            ret = avcodec_receive_frame(codec_ctx, frame);
            if (ret < 0) {
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    return 0;
                }
            }
            *got_frame = 1;
            return ret;
        }
    }
}


/* ============================================================
 * Used by thumbnailer.c.
 * ============================================================ */

/*
 * Read the underlying integer file descriptor out of a java.io.FileDescriptor
 * by reflecting on its private "descriptor" field. Returns -1 if the field
 * cannot be located or fileDescriptor is NULL.
 */
int utils_jni_get_file_descriptor(JNIEnv *env, jobject fileDescriptor) {
    jint fd = -1;
    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");

    if (fdClass != NULL) {
        jfieldID fdField = (*env)->GetFieldID(env, fdClass, "descriptor", "I");
        if (fdField != NULL && fileDescriptor != NULL) {
            fd = (*env)->GetIntField(env, fileDescriptor, fdField);
        }
        (*env)->DeleteLocalRef(env, fdClass);
    }
    return fd;
}


/*
 * Choose a channel layout supported by the encoder. If the encoder advertises
 * a list, pick the one with the most channels; otherwise default to stereo.
 * Used by the AAC encoder path.
 *
 * FFmpeg 7.1 (libavcodec 61.12.100) deprecated AVCodec::ch_layouts in favour
 * of avcodec_get_supported_config(); the field is scheduled to be removed in
 * a future major version. We use the new API where available and fall back
 * to the legacy field on older releases. The selection logic is identical
 * either way.
 */
int utils_select_channel_layout(const AVCodec *codec, AVChannelLayout *dst) {
    const AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
    const AVChannelLayout *layouts = NULL;
    const AVChannelLayout *best_ch_layout = NULL;
    int nb_layouts = 0;
    int best_nb_channels = 0;
    int i;

#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 12, 100)
    /* Modern path: query the codec's supported configs. The function returns
     * 0 on success and writes the array pointer + element count via the
     * out-params. A NULL array means "any" — we fall back to stereo. */
    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_CHANNEL_LAYOUT,
                                     0, (const void **) &layouts, &nb_layouts) < 0
        || layouts == NULL || nb_layouts == 0) {
        return av_channel_layout_copy(dst, &stereo);
    }

    for (i = 0; i < nb_layouts; i++) {
        if (layouts[i].nb_channels > best_nb_channels) {
            best_ch_layout = &layouts[i];
            best_nb_channels = layouts[i].nb_channels;
        }
    }
#else
    /* Legacy path: walk the zero-terminated codec->ch_layouts array. */
    if (codec->ch_layouts == NULL)
        return av_channel_layout_copy(dst, &stereo);

    for (layouts = codec->ch_layouts; layouts->nb_channels; layouts++) {
        if (layouts->nb_channels > best_nb_channels) {
            best_ch_layout = layouts;
            best_nb_channels = layouts->nb_channels;
        }
    }
    (void) nb_layouts;
    (void) i;
#endif

    /* [BUG FIX] In the original, best_ch_layout could be used uninitialised
     * when the supported-layouts list was non-NULL but contained no usable
     * entry (e.g. a zero-channels sentinel as its first entry). Fall back
     * to stereo in that case. */
    if (best_ch_layout == NULL)
        return av_channel_layout_copy(dst, &stereo);

    return av_channel_layout_copy(dst, best_ch_layout);
}


/*
 * Pick a sample format the encoder supports. We just take the first one in
 * the codec's supported list — encoders typically advertise their preferred
 * format first.
 *
 * FFmpeg 7.1 (libavcodec 61.12.100) deprecated AVCodec::sample_fmts in
 * favour of avcodec_get_supported_config(AV_CODEC_CONFIG_SAMPLE_FORMAT).
 * Same migration as utils_select_channel_layout above.
 *
 * Returns AV_SAMPLE_FMT_NONE if the codec advertises no formats; the caller
 * is expected to treat that as a configuration error.
 */
enum AVSampleFormat utils_select_sample_fmt(const AVCodec *codec) {
#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 12, 100)
    const enum AVSampleFormat *fmts = NULL;
    int nb_fmts = 0;

    if (avcodec_get_supported_config(NULL, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT,
                                     0, (const void **) &fmts, &nb_fmts) < 0
        || fmts == NULL || nb_fmts == 0) {
        return AV_SAMPLE_FMT_NONE;
    }
    return fmts[0];
#else
    if (codec->sample_fmts == NULL || codec->sample_fmts[0] == AV_SAMPLE_FMT_NONE)
        return AV_SAMPLE_FMT_NONE;
    return codec->sample_fmts[0];
#endif
}