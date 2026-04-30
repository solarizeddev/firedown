/*
 * utils.h
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef H_UTILS
#define H_UTILS

#include <jni.h>
#include <stdio.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/time.h>


#define MAX_STREAMS 6

#define FALSE 0
#define TRUE (!(FALSE))


AVDictionary *utils_read_dictionary(JNIEnv *env, jobject jdictionary);
void utils_set_dict_options(AVDictionary **dictionary);
void utils_ffmpeg_log_callback(void *avcl, int level, const char *fmt, va_list vl);

/* Used by metadatareader.c. */
jobject utils_read_av_entry(JNIEnv *env, char *entry);
int utils_decode_frame(AVCodecContext *codec_ctx, AVFrame *frame,
                       int *got_frame, AVPacket *packet);

/* Used by thumbnailer.c. */
int utils_jni_get_file_descriptor(JNIEnv *env, jobject fileDescriptor);

/* Used by encoder.c. */
int utils_select_channel_layout(const AVCodec *codec, AVChannelLayout *dst);
enum AVSampleFormat utils_select_sample_fmt(const AVCodec *codec);

#endif