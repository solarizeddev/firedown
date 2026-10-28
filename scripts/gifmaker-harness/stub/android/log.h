/* Host stand-in for <android/log.h>: gifmaker.c's LOG macros compile to
 * nothing at its LOG_LEVEL 0, but the header and the call must exist. */
#ifndef HARNESS_ANDROID_LOG_H
#define HARNESS_ANDROID_LOG_H
#include <stdio.h>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) \
    (fprintf(stderr, "[%s] ", tag), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif
