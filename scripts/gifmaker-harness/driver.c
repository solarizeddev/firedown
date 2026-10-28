/*
 * Drives the REAL gifmaker.c (compiled from app/src, never copied) through
 * its JNI entry points with a stub JNIEnv, on the host FFmpeg build.
 *
 * usage: driver <in> <out> <startMs> <endMs> <fps> <width> [interruptAfterMs]
 *
 * interruptAfterMs -1 (or absent): never; "before": interrupt() before
 * start() is called, the Cancel that lands before the task got going.
 *
 * Prints one key=value line per fact: ret, wall time, time spent in each
 * progress phase, the time from interrupt() to start() returning, and the
 * peak resident memory (sampled every 20 ms, so it sees the peak inside
 * start(), not only after).
 */
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "gifmaker.h"

/* --- what gifmaker.c links against besides FFmpeg --------------------- */

jfieldID java_get_field(JNIEnv *env, char *class_name, JavaField field) {
    (void) env; (void) class_name; (void) field;
    return (jfieldID) 1;
}

jmethodID java_get_method(JNIEnv *env, jclass clazz, JavaMethod method) {
    (void) env; (void) clazz;
    return (jmethodID) method.name;
}

void utils_ffmpeg_log_callback(void *avcl, int level, const char *fmt, va_list vl) {
    (void) avcl; (void) level; (void) fmt; (void) vl;
}

/* --- the stub JNIEnv ---------------------------------------------------- */

struct FakeObject {
    jlong native;
};

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static double g_t0;
static volatile double g_last_progress_at;
static volatile int g_last_percent = -1;
/* The percent before the latest callback: gifmaker's closing call always
 * reports the whole range, so this is where the bar stood before it. */
static volatile int g_prev_percent = -1;
static volatile double g_first_encode_at; /* first callback past 95% */
static int g_progress_calls;
static int g_backward_steps;      /* a percent lower than the one before */
static int g_full_before_finish;  /* callbacks at 100% before finished */
static int g_started_calls;
static int g_finished_calls;
static int g_verbose;

static jint JNICALL fake_get_java_vm(JNIEnv *env, JavaVM **vm) {
    (void) env;
    *vm = (JavaVM *) 1;
    return 0;
}

static jobject JNICALL fake_new_global_ref(JNIEnv *env, jobject obj) {
    (void) env;
    return obj;
}

static void JNICALL fake_delete_ref(JNIEnv *env, jobject obj) {
    (void) env; (void) obj;
}

static jclass JNICALL fake_find_class(JNIEnv *env, const char *name) {
    (void) env; (void) name;
    return (jclass) 1;
}

static void JNICALL fake_set_long_field(JNIEnv *env, jobject obj, jfieldID f, jlong v) {
    (void) env; (void) f;
    ((struct FakeObject *) obj)->native = v;
}

static jlong JNICALL fake_get_long_field(JNIEnv *env, jobject obj, jfieldID f) {
    (void) env; (void) f;
    return ((struct FakeObject *) obj)->native;
}

static void JNICALL fake_call_void_method(JNIEnv *env, jobject obj, jmethodID m, ...) {
    const char *name = (const char *) m;
    (void) env; (void) obj;
    if (strcmp(name, "gifMakerProgress") == 0) {
        va_list ap;
        jlong cur;
        jlong total;
        int percent;
        double t;
        va_start(ap, m);
        cur = va_arg(ap, jlong);
        total = va_arg(ap, jlong);
        va_end(ap);
        g_progress_calls++;
        percent = total > 0 ? (int) (cur * 100 / total) : 0;
        if (percent < g_last_percent) {
            g_backward_steps++;
        }
        if (percent >= 100 && g_finished_calls == 0) {
            g_full_before_finish++;
        }
        t = now_s() - g_t0;
        if (percent > 95 && g_first_encode_at == 0) {
            g_first_encode_at = t;
        }
        if (g_verbose && percent / 5 != g_last_percent / 5) {
            fprintf(stderr, "  %7.2fs  %3d%%\n", t, percent);
        }
        g_prev_percent = g_last_percent;
        g_last_percent = percent;
        g_last_progress_at = t;
    } else if (strcmp(name, "gifMakerStarted") == 0) {
        g_started_calls++;
    } else if (strcmp(name, "gifMakerFinished") == 0) {
        g_finished_calls++;
    }
}

static jboolean JNICALL fake_exception_check(JNIEnv *env) {
    (void) env;
    return JNI_FALSE;
}

static void JNICALL fake_exception_clear(JNIEnv *env) {
    (void) env;
}

static const char *JNICALL fake_get_string_utf_chars(JNIEnv *env, jstring s, jboolean *copy) {
    (void) env;
    if (copy != NULL) {
        *copy = JNI_FALSE;
    }
    return (const char *) s;
}

static void JNICALL fake_release_string_utf_chars(JNIEnv *env, jstring s, const char *c) {
    (void) env; (void) s; (void) c;
}

static struct JNINativeInterface_ g_fns;
static JNIEnv g_env_value;
static JNIEnv *g_env = &g_env_value;
static struct FakeObject g_obj;

/* --- memory sampling ---------------------------------------------------- */

static volatile int g_sampling = 1;
static long g_peak_rss_kb;

static long rss_kb(void) {
    long pages = 0;
    long resident = 0;
    FILE *f = fopen("/proc/self/statm", "r");
    if (f == NULL) {
        return 0;
    }
    if (fscanf(f, "%ld %ld", &pages, &resident) != 2) {
        resident = 0;
    }
    fclose(f);
    return resident * (sysconf(_SC_PAGESIZE) / 1024);
}

static void *sampler(void *arg) {
    (void) arg;
    while (g_sampling) {
        long kb = rss_kb();
        if (kb > g_peak_rss_kb) {
            g_peak_rss_kb = kb;
        }
        usleep(20000);
    }
    return NULL;
}

/* --- the run ------------------------------------------------------------ */

struct StartArgs {
    const char *in;
    const char *out;
    jlong start_ms;
    jlong end_ms;
    jint fps;
    jint width;
    int ret;
    volatile int done;
    double done_at;
};

static void *start_thread(void *p) {
    struct StartArgs *a = (struct StartArgs *) p;
    a->ret = jni_gifmaker_start(g_env, (jobject) &g_obj, (jstring) a->in, (jstring) a->out,
                                a->start_ms, a->end_ms, a->fps, a->width);
    a->done_at = now_s() - g_t0;
    a->done = 1;
    return NULL;
}

int main(int argc, char **argv) {
    struct StartArgs a;
    pthread_t worker;
    pthread_t sampler_thread;
    long interrupt_after_ms = -1;
    int interrupt_before_start = 0;
    double interrupted_at = 0;
    int ret;

    if (argc < 7) {
        fprintf(stderr, "usage: %s in out startMs endMs fps width [interruptAfterMs]\n", argv[0]);
        return 2;
    }
    g_verbose = getenv("GIF_VERBOSE") != NULL;

    memset(&a, 0, sizeof(a));
    a.in = argv[1];
    a.out = argv[2];
    a.start_ms = atoll(argv[3]);
    a.end_ms = atoll(argv[4]);
    a.fps = atoi(argv[5]);
    a.width = atoi(argv[6]);
    if (argc > 7) {
        if (strcmp(argv[7], "before") == 0) {
            interrupt_before_start = 1;
        } else {
            interrupt_after_ms = atol(argv[7]);
        }
    }

    g_fns.GetJavaVM = fake_get_java_vm;
    g_fns.NewGlobalRef = fake_new_global_ref;
    g_fns.DeleteGlobalRef = fake_delete_ref;
    g_fns.DeleteLocalRef = fake_delete_ref;
    g_fns.FindClass = fake_find_class;
    g_fns.SetLongField = fake_set_long_field;
    g_fns.GetLongField = fake_get_long_field;
    g_fns.CallVoidMethod = fake_call_void_method;
    g_fns.ExceptionCheck = fake_exception_check;
    g_fns.ExceptionClear = fake_exception_clear;
    g_fns.GetStringUTFChars = fake_get_string_utf_chars;
    g_fns.ReleaseStringUTFChars = fake_release_string_utf_chars;
    g_env_value = &g_fns;

    ret = jni_gifmaker_init(g_env, (jobject) &g_obj);
    if (ret != 0) {
        printf("init=%d\n", ret);
        return 1;
    }

    if (interrupt_before_start) {
        jni_gifmaker_interrupt(g_env, (jobject) &g_obj);
    }

    pthread_create(&sampler_thread, NULL, sampler, NULL);
    g_t0 = now_s();
    pthread_create(&worker, NULL, start_thread, &a);

    while (!a.done) {
        double t = now_s() - g_t0;
        if (interrupt_after_ms >= 0 && interrupted_at == 0 && t * 1000.0 >= interrupt_after_ms) {
            jni_gifmaker_interrupt(g_env, (jobject) &g_obj);
            interrupted_at = t;
        }
        usleep(2000);
    }
    pthread_join(worker, NULL);
    g_sampling = 0;
    pthread_join(sampler_thread, NULL);

    jni_gifmaker_stop(g_env, (jobject) &g_obj);
    jni_gifmaker_dealloc(g_env, (jobject) &g_obj);

    printf("ret=%d\n", a.ret);
    printf("wall_s=%.2f\n", a.done_at);
    printf("decode_phase_s=%.2f\n", g_first_encode_at > 0 ? g_first_encode_at : a.done_at);
    printf("encode_phase_s=%.2f\n", g_first_encode_at > 0 ? a.done_at - g_first_encode_at : 0.0);
    printf("peak_rss_mb=%ld\n", g_peak_rss_kb / 1024);
    printf("progress_calls=%d last_percent=%d started=%d finished=%d\n",
           g_progress_calls, g_last_percent, g_started_calls, g_finished_calls);
    printf("backward_steps=%d\n", g_backward_steps);
    /* The completion call itself is one; more means the bar sat at 100%
     * while the GIF was still being made. */
    printf("full_before_finish=%d\n", g_full_before_finish);
    printf("percent_before_last=%d\n", g_prev_percent);
    if (interrupted_at > 0) {
        printf("interrupt_at_s=%.2f\n", interrupted_at);
        printf("interrupt_latency_s=%.3f\n", a.done_at - interrupted_at);
    }
    return 0;
}
