/*
 * encoder.h
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef H_ENCODER
#define H_ENCODER

static char *encoder_runnable_class_path_name = "com/solarized/firedown/ffmpegutils/FFmpegEncoder";
static JavaField encoder_m_native = {"mNativeEncoder", "J"};
static JavaMethod encoderProgress = {"encoderProgress", "(JJ)V"};
static JavaMethod encoderStarted = {"encoderStarted", "()V"};
static JavaMethod encoderFinished = {"encoderFinished", "()V"};

int jni_encoder_init(JNIEnv *env, jobject thiz);
void jni_encoder_dealloc(JNIEnv *env, jobject thiz);
int jni_encoder_start(JNIEnv *env, jobject thiz, jobject dictionary, jobject metadata, jstring filePath,
					  jstring outputPath, int video_stream_no, int audio_stream_no);
void jni_encoder_stop(JNIEnv *env, jobject thiz);
void jni_encoder_interrupt(JNIEnv *env, jobject thiz);

static JNINativeMethod encoder_methods[] = {
		{"initEncoder", "()I", (void *) jni_encoder_init},
		{"startEncoder", "(Ljava/util/Map;Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;II)I", (void *) jni_encoder_start},
		{"stopEncoder", "()V", (void *) jni_encoder_stop},
		{"interruptEncoder", "()V", (void *) jni_encoder_interrupt},
		{"deallocEncoder", "()V", (void *) jni_encoder_dealloc},
};

#endif