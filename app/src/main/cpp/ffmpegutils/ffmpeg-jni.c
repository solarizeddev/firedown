/*
 * ffmpeg-jni.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

/*android specific headers*/
#include <jni.h>
#include <android/log.h>
/*standard library*/
#include <time.h>
#include <math.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <inttypes.h>
#include <unistd.h>
#include <assert.h>


#include "helpers.h"
#include "downloader.h"
#include "thumbnailer.h"
#include "metadatareader.h"
#include "encoder.h"
#include "libavcodec/jni.h"

/*for android logs*/
#define LOG_TAG "FFmpegUtils"
#define LOG_LEVEL 0
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}

#ifndef NELEM
#define NELEM(x) ((int)(sizeof(x) / sizeof((x)[0])))
#endif

static int register_native_methods(JNIEnv* env,
		const char* class_name,
		JNINativeMethod* methods,
		int num_methods)
{
	jclass clazz;

	clazz = (*env)->FindClass(env, class_name);
	if (clazz == NULL) {
		fprintf(stderr, "Native registration unable to find class '%s'\n",
				class_name);
		return JNI_FALSE;
	}
	if ((*env)->RegisterNatives(env, clazz, methods, num_methods) < 0) {
		fprintf(stderr, "RegisterNatives failed for '%s'\n", class_name);
		return JNI_FALSE;
	}

	return JNI_TRUE;
}

jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
	JNIEnv* env = NULL;
	jint result = -1;

	if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK) {
		fprintf(stderr, "ERROR: GetEnv failed\n");
		goto bail;
	}

	assert(env != NULL);

    result = av_jni_set_java_vm(vm, NULL);

    if(result) {
        fprintf(stderr, "Could not attach VM\n");
        goto bail;
    }
	
	if (!register_native_methods(env,
			downloader_runnable_class_path_name,
			downloader_methods,
			NELEM(downloader_methods))) {
		fprintf(stderr, "ERROR: Exif native Downloader registration failed\n");
		goto bail;
	}

	if (!register_native_methods(env,
								thumbnail_runnable_class_path_name,
								thumbnail_methods,
								NELEM(thumbnail_methods))) {
		fprintf(stderr, "ERROR: Exif native Thumbnailer registration failed\n");
		goto bail;
	}

	if (!register_native_methods(env,
								metadatareader_runnable_class_path_name,
								metadatareader_methods,
								NELEM(metadatareader_methods))) {
		fprintf(stderr, "ERROR: Exif native MetadataReader registration failed\n");
		goto bail;
	}

	if (!register_native_methods(env,
								encoder_runnable_class_path_name,
								encoder_methods,
								NELEM(encoder_methods))) {
		fprintf(stderr, "ERROR: Exif native Encoder registration failed\n");
		goto bail;
	}

	/* success -- return valid version number */
	result = JNI_VERSION_1_6;

bail:
	return result;
}

void JNI_OnUnload(JavaVM *vm, void *reserved)
{

}


