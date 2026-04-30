/*
 * helpers.h
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef FIREDOWN_METADATAREADER_H
#define FIREDOWN_METADATAREADER_H

// Maximum number of input streams that can be merged (e.g. video-only + audio-only)
#define MAX_METADATA_STREAMS 3

// FFmpegStreamInfo.CodeType
enum CodecType {
    CODEC_TYPE_UNKNOWN = 0,
    CODEC_TYPE_AUDIO = 1,
    CODEC_TYPE_VIDEO = 2,
    CODEC_TYPE_SUBTITLE = 3,
    CODEC_TYPE_ATTACHMENT = 4,
    CODEC_TYPE_NB = 5,
    CODEC_TYPE_DATA = 6,
};




// FFmpegStreamInfo
static char *stream_info_class_path_name = "com/solarized/firedown/ffmpegutils/FFmpegStreamInfo";
static JavaMethod stream_info_set_metadata = {"setMetadata", "(Ljava/util/Map;)V"};
static JavaMethod stream_info_set_media_type_internal = {"setMediaTypeInternal", "(I)V"};
static JavaMethod stream_info_set_stream_number = {"setStreamNumber", "(I)V"};
static JavaMethod stream_info_set_selected_stream_number = {"setSelectedStream", "(I)V"};
static JavaMethod stream_info_set_frame_rate = {"setFrameRate", "(I)V"};
static JavaMethod stream_info_set_width = {"setWidth", "(I)V"};
static JavaMethod stream_info_set_height = {"setHeight", "(I)V"};
static JavaMethod stream_info_set_encoded_width = {"setEncodedWidth", "(I)V"};
static JavaMethod stream_info_set_encoded_height = {"setEncodedHeight", "(I)V"};
static JavaMethod stream_info_pixel_format = {"setPixelFormat", "(I)V"};
static JavaMethod stream_info_sample_format = {"setSampleFormat", "(I)V"};
static JavaMethod stream_info_set_sampling_rate = {"setSamplingRate", "(I)V"};
static JavaMethod stream_info_set_codec_name = {"setCodecName", "(Ljava/lang/String;)V"};
static JavaMethod stream_read_input = {"readInputStream", "(ILjava/nio/ByteBuffer;I)I"};
static JavaMethod stream_seek_input = {"seekInputStream", "(IJI)J"};

static char *metadatareader_runnable_class_path_name = "com/solarized/firedown/ffmpegutils/FFmpegMetaDataReader";
static JavaMethod metadatareader_set_duration = {"setDuration", "(J)V"};
static JavaMethod metadatareader_set_meta_data = {"setMetaData", "(Ljava/util/Map;)V"};
static JavaMethod metadatareader_set_streams_info = {"setStreamsInfo", "([Lcom/solarized/firedown/ffmpegutils/FFmpegStreamInfo;)V"};
static JavaMethod metadatareader_set_input_format_name = {"setInputFormatName", "(Ljava/lang/String;)V"};
static JavaField metadatareader_m_native_reader = {"mNativeMetadataReader", "J"};
static JavaMethod metadatareader_bitmap_render = {"bitmapRender", "()V"};
static JavaMethod metadatareader_bitmap_error = {"bitmapError", "()V"};
static JavaMethod metadatareader_bitmap_init = {"bitmapInit", "(III)Ljava/nio/ByteBuffer;"};


void jni_dealloc_metadatareader(JNIEnv *env, jobject thiz);
void jni_stop_metadatareader(JNIEnv *env, jobject thiz);
int jni_extract_metadata(JNIEnv *env, jobject thiz, jobjectArray jurls, jobjectArray jdicts, jboolean extract);
int jni_extract_metadata_inputstream(JNIEnv *env, jobject thiz, jobjectArray istreams, jobjectArray filenames, jboolean extract);
int jni_init_metadatareader(JNIEnv* env, jobject thiz);


static JNINativeMethod metadatareader_methods[] = {

        {"initMetadataReader", "()I", (void*) jni_init_metadatareader},
        {"extractMetadata", "([Ljava/lang/String;[Ljava/util/Map;Z)I", (void*) jni_extract_metadata},
        {"extractMetadataInputStream", "([Ljava/io/InputStream;[Ljava/lang/String;Z)I", (void*) jni_extract_metadata_inputstream},
        {"stopMetadataReader", "()V", (void*) jni_stop_metadatareader},
        {"deallocMetadataReader", "()V", (void*) jni_dealloc_metadatareader},
};


#endif //FIREDOWN_METADATAREADER_H