/*
 * helpers.h
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef HELPERS_H_
#define HELPERS_H_

#include <jni.h>

typedef struct {
    const char* name;
    const char* signature;
} JavaMethod;

typedef struct {
    char* name;
    char* signature;
} JavaField;


extern JavaMethod empty_constructor;


// InterruptedException
extern char *interrupted_exception_class_path_name;

// RuntimeException
extern char *runtime_exception_class_path_name;

// NotPlayingException
extern char *not_playing_exception_class_path_name;

// Object
extern char *object_class_path_name;

// HashMap
extern char *hash_map_class_path_name;
extern char *map_class_path_name;
extern JavaMethod map_key_set;
extern JavaMethod map_get;
extern JavaMethod map_put;


// Set
extern char *set_class_path_name;
extern JavaMethod set_iterator;

// Iterator
extern char *iterator_class_path_name;
extern JavaMethod iterator_next;
extern JavaMethod iterator_has_next;

jfieldID java_get_field(JNIEnv *env, char * class_name, JavaField field);
jmethodID java_get_method(JNIEnv *env, jclass class, JavaMethod method);
jmethodID java_get_static_method(JNIEnv *env, jclass class, JavaMethod method);



#endif /* HELPERS_H_ */
