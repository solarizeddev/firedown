/*
 * downloader.h
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef H_DOWNLOADER
#define H_DOWNLOADER

// Maximum number of input URLs that can be merged (e.g. video-only + audio-only)
#define MAX_DOWNLOAD_INPUTS 3


static char *downloader_runnable_class_path_name = "com/solarized/firedown/ffmpegutils/FFmpegDownloader";
static JavaField downloader_m_native = {"mNativeDownloader", "J"};
static JavaMethod downloadProgress = {"downloadProgress", "(JJ)V"};
static JavaMethod downloadStarted = {"downloadStarted", "()V"};
static JavaMethod downloadFinished = {"downloadFinished", "()V"};

int jni_downloader_init(JNIEnv* env, jobject thiz);
void jni_downloader_dealloc(JNIEnv *env, jobject thiz);
int jni_downloader_start(JNIEnv *env, jobject thiz, jobjectArray jurls, jobjectArray jdicts,
						 jobject metadata, jstring outputPath, jintArray videoStreams,
						 jintArray audioStreams, jlong totalLength);
void jni_downloader_stop(JNIEnv *env, jobject thiz);


static JNINativeMethod downloader_methods[] = {

		{"initDownloader", "()I", (void*) jni_downloader_init},
		{"startDownloader", "([Ljava/lang/String;[Ljava/util/Map;Ljava/util/Map;Ljava/lang/String;[I[IJ)I", (void*) jni_downloader_start},
		{"stopDownloader", "()V", (void*) jni_downloader_stop},
		{"deallocDownloader", "()V", (void*) jni_downloader_dealloc},
};


#endif