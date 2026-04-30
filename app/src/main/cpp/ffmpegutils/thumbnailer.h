/*
 * thumbnailer.h
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef H_THUMBNAILER
#define H_THUMBNAILER

#include <libavutil/opt.h>
#include <libavutil/avutil.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>


void jni_dealloc_thumbnailer(JNIEnv *env, jobject thiz);
int jni_extract_bitmap (JNIEnv * env, jobject thiz, jlong stream_pos);
int jni_extract_bitmap_setdata_source(JNIEnv * env, jobject thiz, jstring uri, jobject dict);
int jni_extract_bitmap_setdata_source_inputstream(JNIEnv * env, jobject thiz, jobject stream, jobject dict);
int jni_extract_bitmap_setdata_source_fd(JNIEnv * env, jobject thiz, jobject jdescriptor, jobject dict);
int jni_init_thumbnailer(JNIEnv* env, jobject thiz);


static char *thumbnail_runnable_class_path_name = "com/solarized/firedown/ffmpegutils/FFmpegThumbnailer";
static JavaField thumbnail_m_native_thumbnailer = {"mNativeThumbnailer", "J"};
static JavaMethod bitmapReadInputStream = {"bitmapReadInputStream", "([BII)I"};
static JavaMethod bitmapSeekInputStream = {"bitmapSeekInputStream", "(JI)J"};
static JavaMethod bitmapRender = {"bitmapRender", "()V"};
static JavaMethod bitmapInit = {"bitmapInit", "(III)Ljava/nio/ByteBuffer;"};


static JNINativeMethod thumbnail_methods[] = {

		{"initThumbnailer", "()I", (void*) jni_init_thumbnailer},
		{"bitmapSetDataSource", "(Ljava/lang/String;Ljava/util/Map;)I", (void*) jni_extract_bitmap_setdata_source},
		{"bitmapSetDataSourceInputStream", "(Ljava/io/InputStream;Ljava/util/Map;)I", (void*) jni_extract_bitmap_setdata_source_inputstream},
		{"bitmapSetDataSourceFileDescriptor", "(Ljava/io/FileDescriptor;Ljava/util/Map;)I", (void*) jni_extract_bitmap_setdata_source_fd},
		{"bitmapExtract", "(J)I", (void*) jni_extract_bitmap},
		{"deallocThumbnailer", "()V", (void*) jni_dealloc_thumbnailer},
};

#endif