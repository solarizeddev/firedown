/*
 * downloader.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 *
 * ----------------------------------------------------------------------------
 * Threading model
 * ----------------------------------------------------------------------------
 *
 *  jni_downloader_start (caller thread, typically a background download thread)
 *    │
 *    ├── downloader_set_data_source (under mutex_operation)
 *    │     opens inputs, finds streams, creates output, allocates queue,
 *    │     spawns the mux thread.
 *    │
 *    └── downloader_read (still on caller thread)
 *          reads packets from inputs round-robin and pushes them into the queue.
 *          On EOF / interrupt / mux error, signals the mux thread to drain
 *          and waits for it to finish.
 *
 *  downloader_mux (spawned thread)
 *      writes header → pops packets → muxes → av_write_trailer → exits.
 *
 *  jni_downloader_stop (called from any thread, typically a UI/service thread):
 *      sets `interrupt` + `stop`, broadcasts the condvar, returns immediately.
 *      Does NOT join — that's done from jni_downloader_dealloc on the
 *      original caller thread.
 *
 * Locking
 *   mutex_operation : serialises start / stop / dealloc against each other.
 *                     Held only by control-plane code, never by I/O loops.
 *   mutex_queue     : protects all packet-queue state and all the boolean
 *                     coordination flags below. Held by both reader and muxer.
 *   cond_queue      : broadcast on every state transition that another thread
 *                     might be waiting on (queue space, queue items, EOF,
 *                     stop request, thread exit).
 *
 * Flag semantics (all guarded by mutex_queue except where noted)
 *   interrupt          : volatile, lock-free. Read from the FFmpeg interrupt
 *                        callback which can fire while another thread holds
 *                        mutex_queue, so we cannot take the lock there.
 *                        Strictly monotonic FALSE → TRUE.
 *   stop               : "tear everything down" — set by stop()/dealloc().
 *   stop_stream        : "the read side has stopped producing, muxer should
 *                        drain and exit". Cleared at the start of each run,
 *                        set when read loop exits.
 *   muxing_thread_created : the mux thread is alive (set before pthread_create
 *                        returns success path; cleared by mux thread on exit).
 *   read_thread_created   : the read loop is currently executing.
 *   eof                : EOF packet has been pushed onto the queue.
 *   input_eof[i]       : input i has been fully drained.
 *
 * ----------------------------------------------------------------------------
 */

#include <stdlib.h>
#include <stdio.h>

#include <libavutil/opt.h>
#include <libavutil/avutil.h>
#include <libavutil/avstring.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avassert.h>
#include <pthread.h>

#include <android/log.h>
#include <jni.h>

#include "helpers.h"
#include "downloader.h"
#include "utils.h"
#include "queue.h"


#define LOG_LEVEL 0
#define FFMPEG_LOG_LEVEL AV_LOG_QUIET
#define LOG_TAG "downloader.c"
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}
#define LOGW(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__);}

#define UPDATE_TIME_US 50000ll
/* Consecutive progress ticks (UPDATE_TIME_US each) a stream's recording-time
 * accumulator may fail to advance before it is treated as stalled and dropped
 * from the muxed-position min (see downloader_muxed_position). Sized to tolerate
 * a brief gap between an audio stream's bursty packets (~21 ms AAC frames vs the
 * 50 ms tick) while still reacting promptly to a genuine freeze. */
#define PROGRESS_STALL_TICKS 5

#define BUFFER_SIZE 1500

#define STREAM_UNKNOWN (-1)

/* Sleep granularity when all inputs are blocked but not yet EOF. */
#define READ_BACKOFF_NS (10 * 1000 * 1000L) /* 10 ms */


struct Downloader {
    /* ---- input/output ffmpeg state ---- */
    struct AVFormatContext *input_format_ctx[MAX_DOWNLOAD_INPUTS];
    int nb_inputs;

    struct AVFormatContext *output_format_ctx;
    AVCodecContext *input_codec_ctxs[MAX_STREAMS];
    AVStream *input_streams[MAX_STREAMS];

    int input_stream_numbers[MAX_STREAMS];
    int input_source_index[MAX_STREAMS];
    /* Fixed media type per capture slot, recorded at find time. The reader
     * routes packets by this (not only by the frozen input-stream index) so an
     * HLS discontinuity that renumbers the underlying MPEG-TS streams can't
     * misroute a packet — see the match loop in downloader_read. */
    enum AVMediaType capture_codec_type[MAX_STREAMS];
    int64_t last_mux_dts[MAX_STREAMS];
    int64_t current_recording_time[MAX_STREAMS];
    int64_t filter_in_rescale_delta_last[MAX_STREAMS];
    int64_t start_time;

    /* Progress-only state (mux thread, no lock). Tracks whether each stream's
     * recording-time accumulator is still advancing so a stalled track can be
     * excluded from the muxed-position min, plus the last reported position so
     * that exclusion can never step the bar backward. */
    int64_t progress_prev_recording_time[MAX_STREAMS];
    int progress_stall_ticks[MAX_STREAMS];
    int64_t progress_last_position;

    int capture_streams_no;
    int video_stream_no;
    int audio_stream_no;
    int disable_audio;
    int disable_video;

    int input_eof[MAX_DOWNLOAD_INPUTS];
    int eof;
    int mux_error;

    int64_t last_updated_time;
    int64_t current_size;
    int64_t total_size;

    /* ---- packet queue ---- */
    Queue *packets;

    /* ---- threading ---- */
    pthread_t muxing_thread;
    pthread_mutex_t mutex_operation; /* serialises start/stop/dealloc */
    pthread_mutex_t mutex_queue; /* guards everything below */
    pthread_cond_t cond_queue;

    int muxing_thread_created;
    int read_thread_created;
    volatile int interrupt; /* lock-free: see header comment */
    int stop;
    int stop_stream;

    AVIOInterruptCB interrupt_callback;

    /* ---- JNI ---- */
    JavaVM *get_javavm;
    jobject thiz;
    jmethodID downloader_on_progress_update_method;
    jmethodID downloader_on_download_started_method;
    jmethodID downloader_on_download_finished_method;
};


struct State {
    struct Downloader *downloader;
    JNIEnv *env;
};

struct PacketData {
    int end_of_stream;
    AVPacket *packet;
};


enum ReadFromStreamCheckMsg {
    READ_FROM_STREAM_CHECK_MSG_STOP = 0,
    READ_FROM_STREAM_CHECK_MSG_SKIP,
    READ_FROM_STREAM_CHECK_MSG_MUX_ERROR
};

enum MuxerCheckMsg {
    MUXER_CHECK_MSG_STOP = 0,
    MUXER_CHECK_MSG_FLUSH,
    MUXER_CHECK_MSG_SKIP
};


enum DownloaderErrors {
    ERROR_NO_ERROR = 0,
    ERROR_ENOENT,
    ERROR_COULD_NOT_ATTACH_THREAD,
    ERROR_COULD_NOT_GET_JAVA_VM,
    ERROR_COULD_NOT_SET_JAVA_VM,
    ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAMS,
    ERROR_COULD_NOT_INIT_PATHS,
    ERROR_COULD_NOT_CREATE_AVCONTEXT,
    ERROR_COULD_NOT_FIND_STREAMS,
    ERROR_COULD_NOT_CREATE_OUTPUT_FORMAT,
    ERROR_COULD_NOT_OPEN_INPUT,
    ERROR_COULD_NOT_WRITE_HEADER,
    ERROR_COULD_NOT_PREPARE_PACKETS_QUEUE,
    ERROR_COULD_NOT_DETACH_THREAD,
    ERROR_COULD_NOT_JOIN_PTHREAD,
    ERROR_COULD_NOT_INIT_PTHREAD_ATTR,
    ERROR_COULD_NOT_CREATE_PTHREAD,
    ERROR_COULD_NOT_DESTROY_PTHREAD_ATTR,
    ERROR_NOT_FOUND_DOWNLOADER_CLASS,
    ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_STARTED_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_FINSIHED_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_PROGRESS_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_FIELD,
    ERROR_COULD_NOT_ALLOCATE_DOWNLOADER,
    ERROR_COULD_NOT_CREATE_GLOBAL_REF,
    ERROR_COULD_NOT_OPEN_STREAM,
    ERROR_COULD_NOT_READ
};


/* =========================================================================
 * Stop-coordination helpers
 *
 * These wrap the "set flag + broadcast" idiom that previously lived inline
 * (sometimes via the misnamed utils_assign_to_no_boolean wrapper).
 * Centralising it removes a class of "forgot to broadcast" bugs and gives
 * each operation a name that says what it means.
 * ========================================================================= */

/* Lock-free read used from the FFmpeg interrupt callback.
 * `interrupt` is volatile; `stop`/`stop_stream` may be torn here, but a
 * stale read at worst delays cancellation by one packet — the next check
 * a few microseconds later will see the new value. */
static int downloader_should_abort(const struct Downloader *d) {
    return d->interrupt || d->stop || d->stop_stream;
}

static void downloader_request_stop_locked(struct Downloader *d) {
    d->stop_stream = TRUE;
    pthread_cond_broadcast(&d->cond_queue);
}

static void downloader_clear_stop(struct Downloader *d) {
    pthread_mutex_lock(&d->mutex_queue);
    d->stop_stream = FALSE;
    pthread_cond_broadcast(&d->cond_queue);
    pthread_mutex_unlock(&d->mutex_queue);
}

static void downloader_mark_muxer_exited(struct Downloader *d) {
    pthread_mutex_lock(&d->mutex_queue);
    d->muxing_thread_created = FALSE;
    pthread_cond_broadcast(&d->cond_queue);
    pthread_mutex_unlock(&d->mutex_queue);
}

/* Caller must hold mutex_queue. Wait for the mux thread to exit (or to
 * never have started). */
static void downloader_wait_for_muxer_exit_locked(struct Downloader *d) {
    while (d->muxing_thread_created && d->stop_stream) {
        pthread_cond_wait(&d->cond_queue, &d->mutex_queue);
    }
}


/* =========================================================================
 * Queue glue
 * ========================================================================= */

void *downloader_fill_packet(struct State *state) {
    struct PacketData *packet_data = av_mallocz(sizeof(struct PacketData));
    if (packet_data == NULL)
        return NULL;
    packet_data->packet = av_packet_alloc();
    if (packet_data->packet == NULL) {
        av_free(packet_data);
        return NULL;
    }
    return packet_data;
}

void downloader_free_packet(struct State *state, struct PacketData *elem) {
    if (elem == NULL)
        return;
    av_packet_free(&elem->packet);
    av_free(elem);
}

void downloader_alloc_queues_free(struct State *state) {
    struct Downloader *downloader = state->downloader;
    if (downloader->packets != NULL) {
        queue_free(downloader->packets, &downloader->mutex_queue,
                   &downloader->cond_queue, state);
        downloader->packets = NULL;
    }
}

int downloader_alloc_queues(struct State *state) {
    struct Downloader *downloader = state->downloader;
    downloader->packets = queue_init_with_custom_lock(BUFFER_SIZE,
                                                      (queue_fill_func) downloader_fill_packet,
                                                      (queue_free_func) downloader_free_packet,
                                                      state, state,
                                                      &downloader->mutex_queue,
                                                      &downloader->cond_queue);
    return downloader->packets == NULL ? -ERROR_COULD_NOT_PREPARE_PACKETS_QUEUE : ERROR_NO_ERROR;
}


/* downloader_prepare / _prepare_free toggle the global `stop` flag for the
 * whole downloader (distinct from `stop_stream` which only affects the
 * mux thread). */
void downloader_prepare(struct Downloader *downloader) {
    pthread_mutex_lock(&downloader->mutex_queue);
    downloader->stop = FALSE;
    pthread_cond_broadcast(&downloader->cond_queue);
    pthread_mutex_unlock(&downloader->mutex_queue);
}

void downloader_prepare_free(struct Downloader *downloader) {
    pthread_mutex_lock(&downloader->mutex_queue);
    downloader->stop = TRUE;
    pthread_cond_broadcast(&downloader->cond_queue);
    pthread_mutex_unlock(&downloader->mutex_queue);
}


QueueCheckFuncRet downloader_read_from_stream_check_func(Queue *queue,
                                                         struct Downloader *downloader,
                                                         int *ret) {
    if (queue == NULL) {
        *ret = READ_FROM_STREAM_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }
    if (downloader->mux_error) {
        *ret = READ_FROM_STREAM_CHECK_MSG_MUX_ERROR;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }
    if (downloader_should_abort(downloader)) {
        *ret = READ_FROM_STREAM_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }
    return QUEUE_CHECK_FUNC_RET_TEST;
}

QueueCheckFuncRet downloader_mux_queue_check_func(Queue *queue,
                                                  struct Downloader *downloader,
                                                  int *ret) {
    if (queue == NULL || downloader->stop_stream || downloader->stop) {
        *ret = MUXER_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }
    return QUEUE_CHECK_FUNC_RET_TEST;
}


/* =========================================================================
 * Setup / teardown of ffmpeg objects
 * ========================================================================= */

void downloader_find_stream_info_free(struct Downloader *downloader) {
    /* Currently a no-op: avformat_find_stream_info has no separate teardown.
     * Kept as a named hook for symmetry with the rest of the *_free family. */
    (void) downloader;
}

void downloader_create_context_free(struct Downloader *downloader) {
    LOGI(7, "downloader_create_context_free remove_output_context");
    if (downloader->output_format_ctx != NULL) {
        if (downloader->output_format_ctx->oformat != NULL &&
            !(downloader->output_format_ctx->oformat->flags & AVFMT_NOFILE) &&
            downloader->output_format_ctx->pb != NULL) {
            avio_close(downloader->output_format_ctx->pb);
        }
        avformat_free_context(downloader->output_format_ctx);
        downloader->output_format_ctx = NULL;
    }
}

void downloader_init_offset(struct Downloader *downloader) {
    AVStream *ist;
    downloader->start_time = INT64_MAX;
    for (int i = 0; i < downloader->capture_streams_no; i++) {
        ist = downloader->input_streams[i];
        if (ist->discard == AVDISCARD_ALL || ist->start_time == AV_NOPTS_VALUE)
            continue;
        downloader->start_time = FFMIN(downloader->start_time,
                                       av_rescale_q(ist->start_time, ist->time_base, AV_TIME_BASE_Q));
        LOGI(2, "downloader_init_offset start_time %"PRId64" from input[%d]",
             downloader->start_time, downloader->input_source_index[i]);
    }
    /* If no stream had a usable start time, normalise to 0 so the offset
     * subtraction in the mux loop stays sane. */
    if (downloader->start_time == INT64_MAX)
        downloader->start_time = 0;
}

int downloader_create_context(struct Downloader *downloader,
                              const char *file_output_path, AVDictionary *metadata) {
    if (avformat_alloc_output_context2(&downloader->output_format_ctx, NULL, NULL, file_output_path) < 0) {
        LOGE(1, "Could not create Output format AVContext\n");
        av_dict_free(&metadata);
        return -ERROR_COULD_NOT_CREATE_AVCONTEXT;
    }
    /* Ownership of `metadata` transfers to the format context. */
    downloader->output_format_ctx->metadata = metadata;
    return ERROR_NO_ERROR;
}

int downloader_create_output_format(struct Downloader *downloader,
                                    const char *file_output_path) {
    const AVOutputFormat *fmt;
    LOGI(2, "downloader_create_output_format %s", file_output_path);
    if ((fmt = av_guess_format(NULL, file_output_path, NULL)) == NULL) {
        LOGE(2, "Could not guess AVOutputFormat %s\n", file_output_path);
        return -ERROR_COULD_NOT_CREATE_OUTPUT_FORMAT;
    }
    downloader->output_format_ctx->oformat = fmt;
    return ERROR_NO_ERROR;
}

int downloader_init_output_streams(struct Downloader *downloader) {
    AVFormatContext *output_format_ctx = downloader->output_format_ctx;
    AVStream *ost, *ist;
    AVCodecParameters *in_codecpar;
    int ret = ERROR_NO_ERROR;

    for (int i = 0; i < downloader->capture_streams_no; i++) {
        ist = downloader->input_streams[i];
        in_codecpar = ist->codecpar;
        LOGI(2, "downloader_init_output_streams %d", i);

        if (in_codecpar->codec_type != AVMEDIA_TYPE_AUDIO &&
            in_codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
            continue;
        }

        ost = avformat_new_stream(output_format_ctx, NULL);
        if (!ost) {
            LOGE(1, "downloader_init_output_streams failed allocating output stream");
            ret = -ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAMS;
            goto end;
        }

        ret = avcodec_parameters_copy(ost->codecpar, in_codecpar);
        if (ret < 0) {
            LOGE(1, "downloader_init_output_streams failed to copy codec parameters");
            ret = -ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAMS;
            goto end;
        }

        ost->time_base = ist->time_base;
        ost->codecpar->codec_tag = 0;
        ost->id = (int) output_format_ctx->nb_streams - 1;

        LOGI(1, "downloader_init_output_streams input_stream->timebase %f output_stream->timebase %f",
             av_q2d(ist->time_base), av_q2d(ost->time_base));
    }

#if LOG_LEVEL > 1
    av_dump_format(output_format_ctx, 0, output_format_ctx->url, 1);
#endif

    end:
    return ret;
}

int downloader_find_streams(struct Downloader *downloader,
                            enum AVMediaType codec_type, const int *recommended_per_input) {
    AVStream *stream;
    AVCodecContext *dec_ctx = NULL;
    AVDictionary *opts = NULL;
    const AVCodec *dec = NULL;
    int streams_no = downloader->capture_streams_no;
    int related_stream = -1;
    int bn_stream = -1;
    int source_input = -1;
    int ret;

    LOGI(3, "downloader_find_stream type=%d", codec_type);

    /* For audio, prefer a stream from the same input as the chosen video. */
    if (codec_type == AVMEDIA_TYPE_AUDIO && downloader->video_stream_no >= 0) {
        related_stream = downloader->input_stream_numbers[downloader->video_stream_no];
    }

    for (int i = 0; i < downloader->nb_inputs; i++) {
        int recommended = (recommended_per_input != NULL) ? recommended_per_input[i] : -1;

        /* Hard selection (e.g. user picked a specific HLS rendition). */
        if (recommended >= 0 && recommended < downloader->input_format_ctx[i]->nb_streams) {
            AVFormatContext *rctx = downloader->input_format_ctx[i];
            AVStream *candidate = rctx->streams[recommended];
            int accept = (candidate->codecpar->codec_type == codec_type);

            /* Program affinity for audio — mirror ffmpeg's own stream selection
             * (`ffmpeg -i master.m3u8 -c copy`). An HLS master exposes one
             * AVProgram per variant; for a muxed ladder each program holds that
             * rung's video AND audio. The Java side pairs the audio index by
             * bitrate, which on a master (per-stream bitrates all report 0)
             * collapses to the FIRST audio — so every quality but the lowest is
             * paired with an audio stream from a DIFFERENT program than the chosen
             * video. Honouring that index keeps two child playlists live, and
             * ffmpeg then muxes two unrelated encoders into one output track: the
             * read loop's type-match flip-flops between the two same-type streams
             * ("discontinuity remap" thrash), two PTS epochs land on one track
             * (the "clamping dts" flood), and the file is unplayable.
             *
             * So when the recommended audio sits in a different program than the
             * already-selected video, defer to av_find_best_stream with
             * related_stream = the video: with wanted<0 it scopes the search to
             * the video's AVProgram (av_find_program_from_stream) and returns the
             * audio muxed with that exact rung — precisely how ffmpeg's CLI pairs
             * them. Scoped to a real multi-program master in the SAME input, so a
             * single-rendition child (Twitch/Kick, one program) and separate-audio
             * inputs (DASH/YouTube, different input) are untouched; and only
             * applied when the video's program actually has an audio stream (a
             * video-only program keeps the recommended index rather than dropping
             * audio). */
            if (accept && codec_type == AVMEDIA_TYPE_AUDIO
                    && downloader->video_stream_no >= 0
                    && i == downloader->input_source_index[downloader->video_stream_no]
                    && rctx->nb_programs > 1) {
                AVProgram *video_program = av_find_program_from_stream(rctx, NULL, related_stream);
                AVProgram *audio_program = av_find_program_from_stream(rctx, NULL, recommended);
                if (video_program != NULL && video_program != audio_program) {
                    int related_audio = av_find_best_stream(rctx, AVMEDIA_TYPE_AUDIO,
                                                            -1, related_stream, NULL, 0);
                    if (related_audio >= 0) {
                        bn_stream = related_audio;
                        source_input = i;
                        LOGI(3, "downloader_find_stream type=%d requested audio=%d crosses the video's variant program; using program-paired audio=%d in input[%d]",
                             codec_type, recommended, related_audio, i);
                        break;
                    }
                    LOGI(3, "downloader_find_stream type=%d requested audio=%d crosses program but video program has no audio; keeping requested",
                         codec_type, recommended);
                }
            }

            if (accept) {
                bn_stream = recommended;
                source_input = i;
                LOGI(3, "downloader_find_stream type=%d using requested stream=%d in input[%d]",
                     codec_type, recommended, i);
                break;
            }
            LOGI(3, "downloader_find_stream type=%d requested=%d wrong type %d in input[%d], falling back",
                 codec_type, recommended, candidate->codecpar->codec_type, i);
        }

        int candidate = av_find_best_stream(downloader->input_format_ctx[i], codec_type,
                                            -1, related_stream, NULL, 0);
        if (candidate >= 0) {
            bn_stream = candidate;
            source_input = i;
            LOGI(3, "downloader_find_stream type=%d auto-selected stream=%d in input[%d]",
                 codec_type, candidate, i);
            break;
        }
    }

    if (bn_stream < 0) {
        LOGE(1, "downloader_find_stream no stream found for type %d", codec_type);
        return STREAM_UNKNOWN;
    }

    stream = downloader->input_format_ctx[source_input]->streams[bn_stream];
    stream->discard = AVDISCARD_DEFAULT;

    dec_ctx = avcodec_alloc_context3(NULL);
    if (!dec_ctx) {
        LOGE(1, "downloader_find_stream avcodec_alloc_context3 failed");
        return STREAM_UNKNOWN;
    }

    ret = avcodec_parameters_to_context(dec_ctx, stream->codecpar);
    if (ret < 0) {
        LOGE(1, "downloader_find_stream avcodec_parameters_to_context failed");
        goto error;
    }

    dec_ctx->pkt_timebase = stream->time_base;
    av_opt_set_int(dec_ctx, "refcounted_frames", 0, 0);
    av_opt_set(dec_ctx, "threads", "auto", 0);

    dec = avcodec_find_decoder(dec_ctx->codec_id);
    if (!dec) {
        LOGE(1, "downloader_find_stream avcodec_find_decoder failed");
        goto error;
    }

    if (avcodec_open2(dec_ctx, dec, &opts) < 0) {
        LOGE(1, "downloader_find_stream avcodec_open2 failed");
        goto error;
    }
    av_dict_free(&opts);

    downloader->input_streams[streams_no] = stream;
    downloader->input_codec_ctxs[streams_no] = dec_ctx;
    downloader->input_stream_numbers[streams_no] = bn_stream;
    downloader->input_source_index[streams_no] = source_input;
    downloader->capture_codec_type[streams_no] = codec_type;
    downloader->capture_streams_no += 1;

    LOGI(2, "downloader_find_stream type=%d input[%d] stream_idx=%d -> capture[%d]",
         codec_type, source_input, bn_stream, streams_no);
    return streams_no;

    error:
    av_dict_free(&opts);
    avcodec_free_context(&dec_ctx);
    return STREAM_UNKNOWN;
}

void downloader_open_stream_free(struct Downloader *downloader, int stream_no) {
    AVCodecContext **ctx = &downloader->input_codec_ctxs[stream_no];
    if (*ctx != NULL) {
        avcodec_free_context(ctx);
        *ctx = NULL;
    }
}

void downloader_find_streams_free(struct Downloader *downloader) {
    for (int i = 0; i < downloader->capture_streams_no; ++i) {
        downloader_open_stream_free(downloader, i);
    }
    downloader->capture_streams_no = 0;
    downloader->video_stream_no = -1;
    downloader->audio_stream_no = -1;
    downloader->disable_audio = FALSE;
    downloader->disable_video = FALSE;
}

int downloader_open_output(struct Downloader *downloader,
                           const char *file_output_path) {
    char errbuf[128];
    const char *errbuf_ptr = errbuf;
    int ret = avio_open2(&downloader->output_format_ctx->pb, file_output_path,
                         AVIO_FLAG_WRITE, NULL, NULL);
    if (ret < 0) {
        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            errbuf_ptr = strerror(AVUNERROR(ret));
        LOGE(1, "downloader_open_output could not open output: %s (%d: %s)\n",
             file_output_path, ret, errbuf_ptr);
        return ret;
    }
    return 0;
}

void downloader_open_input_free(struct Downloader *downloader) {
    LOGI(7, "downloader_open_input_free close_file");
    for (int i = 0; i < downloader->nb_inputs; i++) {
        if (downloader->input_format_ctx[i] != NULL) {
            avformat_close_input(&downloader->input_format_ctx[i]);
        }
    }
    downloader->nb_inputs = 0;
}

/* Takes ownership of *dictionary regardless of success/failure and NULLs the
 * caller's pointer. avformat_open_input mutates the dict (consumes recognised
 * keys); we always free whatever's left. */
int downloader_open_input(struct Downloader *downloader,
                          int input_index, AVDictionary **dictionary, const char *file_path) {
    int ret;
    int err = ERROR_NO_ERROR;
    char errbuf[128];

    utils_set_dict_options(dictionary);

    LOGI(3, "downloader_open_input[%d] file: %s\n", input_index, file_path);

    if ((ret = avformat_open_input(&downloader->input_format_ctx[input_index],
                                   file_path, NULL, dictionary)) < 0) {
        const char *errbuf_ptr = errbuf;
        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            errbuf_ptr = strerror(AVUNERROR(ret));
        LOGE(3, "downloader_open_input could not open: %s (%d: %s)\n",
             file_path, ret, errbuf_ptr);
        err = -ERROR_COULD_NOT_OPEN_INPUT;
    }

    av_dict_free(dictionary); /* dictionary is now NULL */
    return err;
}


/* =========================================================================
 * Thread teardown
 *
 * Caller MUST have already set `stop = TRUE` (or `interrupt = TRUE`) and
 * broadcast cond_queue, otherwise the mux thread can be wedged in
 * pthread_cond_wait and pthread_join will deadlock. downloader_prepare_free
 * does this; the dealloc path also sets `interrupt` first.
 * ========================================================================= */
int downloader_threads_free(struct Downloader *downloader) {
    int ret;
    int err = ERROR_NO_ERROR;

    LOGI(1, "downloader_threads_free mux_thread=%d", downloader->muxing_thread_created);

    if (downloader->muxing_thread_created) {
        ret = pthread_join(downloader->muxing_thread, NULL);
        /* The mux thread sets muxing_thread_created = FALSE itself before
         * exiting, but we set it again here to cover the (impossible-in-
         * practice) case where pthread_join returns before that store is
         * observable. After pthread_join there are no other threads that
         * could read it. */
        downloader->muxing_thread_created = FALSE;
        if (ret) err = ERROR_COULD_NOT_JOIN_PTHREAD;
        LOGI(1, "downloader_threads_free mux_thread joined");
    }

    LOGI(1, "downloader_threads_free read_thread=%d", downloader->read_thread_created);
    pthread_mutex_lock(&downloader->mutex_queue);
    while (downloader->read_thread_created) {
        pthread_cond_wait(&downloader->cond_queue, &downloader->mutex_queue);
    }
    pthread_mutex_unlock(&downloader->mutex_queue);
    LOGI(1, "downloader_threads_free read_thread done");

    return err;
}


void downloader_stop_without_lock(struct State *state) {
    struct Downloader *downloader = state->downloader;

    LOGI(1, "downloader_stop_without_lock prepare_free");
    downloader_prepare_free(downloader);
    LOGI(1, "downloader_stop_without_lock threads_free");
    downloader_threads_free(downloader);
    LOGI(1, "downloader_stop_without_lock alloc_queues_free");
    downloader_alloc_queues_free(state);
    LOGI(1, "downloader_stop_without_lock find_streams_free");
    downloader_find_streams_free(downloader);
    LOGI(1, "downloader_stop_without_lock find_stream_info_free");
    downloader_find_stream_info_free(downloader);
    LOGI(1, "downloader_stop_without_lock input_free");
    downloader_open_input_free(downloader);
    LOGI(1, "downloader_stop_without_lock context_free");
    downloader_create_context_free(downloader);

    /* Reset per-download state so the next download starts clean. No other
     * threads can touch the downloader at this point (we just joined them
     * all), so a plain assignment is safe. */
    downloader->eof = FALSE;
    downloader->mux_error = 0;
    downloader->current_size = 0;
    downloader->last_updated_time = 0;
    downloader->start_time = INT64_MAX;
    downloader->stop_stream = FALSE;
    downloader->progress_last_position = 0;
    for (int i = 0; i < MAX_STREAMS; i++) {
        downloader->last_mux_dts[i] = 0;
        downloader->current_recording_time[i] = 0;
        downloader->filter_in_rescale_delta_last[i] = 0;
        downloader->progress_prev_recording_time[i] = 0;
        downloader->progress_stall_ticks[i] = 0;
    }
    for (int i = 0; i < MAX_DOWNLOAD_INPUTS; i++) {
        downloader->input_eof[i] = FALSE;
    }
}


/* =========================================================================
 * Interrupt callback (called by FFmpeg from inside av_read_frame /
 * avformat_open_input). Must NOT take mutex_queue: the calling thread
 * may already hold it.
 * ========================================================================= */
static int downloader_ctx_interrupt_callback(void *p) {
    struct Downloader *downloader = (struct Downloader *) p;
    LOGI(10, "downloader_ctx_interrupt_callback");
    if (downloader == NULL || downloader->interrupt) {
        LOGI(1, "downloader_ctx_interrupt_callback INTERRUPT");
        return 1;
    }
    return 0;
}

int downloader_create_interrupt_callback(struct Downloader *downloader) {
    LOGI(3, "downloader_create_interrupt_callback");
    /* [BUG FIX] Do NOT reset `interrupt` here. This function is called
     * from inside the downloader_set_data_source pipeline, which checks
     * `interrupt` between every step. Clearing the flag here silently
     * undid a stop request that arrived between alloc_input_contexts
     * and create_interrupt_callback — the rest of the pipeline (open
     * inputs, find streams, alloc queues, start mux thread) then ran
     * in spite of the user having requested cancellation. Mirrors the
     * same fix already applied to metadatareader.c — the flag is now
     * cleared exactly once, in jni_downloader_start, before the
     * mutex_operation acquire. */
    downloader->interrupt_callback = (AVIOInterruptCB) {
            downloader_ctx_interrupt_callback, downloader
    };
    for (int i = 0; i < downloader->nb_inputs; i++) {
        downloader->input_format_ctx[i]->interrupt_callback = downloader->interrupt_callback;
        downloader->input_format_ctx[i]->flags |= AVFMT_FLAG_NONBLOCK;
    }
    return 0;
}


/* =========================================================================
 * Init / accessors
 * ========================================================================= */

int jni_downloader_init(JNIEnv *env, jobject thiz) {
    struct Downloader *downloader = NULL;
    jclass downloader_runnable_class = NULL;
    int err = ERROR_NO_ERROR;
    int ret;

    downloader = av_calloc(1, sizeof(struct Downloader));
    if (downloader == NULL) {
        err = -ERROR_COULD_NOT_ALLOCATE_DOWNLOADER;
        goto fail;
    }

    ret = (*env)->GetJavaVM(env, &downloader->get_javavm);
    if (ret) {
        err = -ERROR_COULD_NOT_GET_JAVA_VM;
        goto fail;
    }

    downloader->thiz = (*env)->NewGlobalRef(env, thiz);
    if (downloader->thiz == NULL) {
        err = -ERROR_COULD_NOT_CREATE_GLOBAL_REF;
        goto fail;
    }

    jfieldID m_native_downloader_field = java_get_field(env,
                                                        downloader_runnable_class_path_name,
                                                        downloader_m_native);
    if (m_native_downloader_field == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_FIELD;
        goto fail;
    }

    downloader_runnable_class = (*env)->FindClass(env, downloader_runnable_class_path_name);
    if (downloader_runnable_class == NULL) {
        err = -ERROR_NOT_FOUND_DOWNLOADER_CLASS;
        LOGE(1, "Could not find downloader class\n");
        goto fail;
    }

    downloader->downloader_on_progress_update_method =
            java_get_method(env, downloader_runnable_class, downloadProgress);
    if (downloader->downloader_on_progress_update_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_PROGRESS_METHOD;
        goto fail;
    }

    downloader->downloader_on_download_started_method =
            java_get_method(env, downloader_runnable_class, downloadStarted);
    if (downloader->downloader_on_download_started_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_STARTED_METHOD;
        goto fail;
    }

    downloader->downloader_on_download_finished_method =
            java_get_method(env, downloader_runnable_class, downloadFinished);
    if (downloader->downloader_on_download_finished_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_DOWNLOADER_FINSIHED_METHOD;
        goto fail;
    }

    pthread_mutex_init(&downloader->mutex_queue, NULL);
    pthread_mutex_init(&downloader->mutex_operation, NULL);
    pthread_cond_init (&downloader->cond_queue, NULL);

    downloader->stop = FALSE;

    av_log_set_callback(utils_ffmpeg_log_callback);
    av_log_set_level(FFMPEG_LOG_LEVEL);

    /* Commit the pointer to Java only after every fallible step has succeeded. */
    (*env)->SetLongField(env, thiz, m_native_downloader_field, (jlong) downloader);
    (*env)->DeleteLocalRef(env, downloader_runnable_class);
    return ERROR_NO_ERROR;

    fail:
    if (downloader_runnable_class != NULL)
        (*env)->DeleteLocalRef(env, downloader_runnable_class);
    if (downloader != NULL) {
        if (downloader->thiz != NULL)
            (*env)->DeleteGlobalRef(env, downloader->thiz);
        av_free(downloader);
    }
    return err;
}


struct Downloader *get_downloader_field(JNIEnv *env, jobject thiz) {
    jfieldID f = java_get_field(env, downloader_runnable_class_path_name, downloader_m_native);
    return (struct Downloader *) (uintptr_t) (*env)->GetLongField(env, thiz, f);
}

void clean_downloader_field(JNIEnv *env, jobject thiz) {
    jfieldID f = java_get_field(env, downloader_runnable_class_path_name, downloader_m_native);
    (*env)->SetLongField(env, thiz, f, 0);
}


/* Progress reporting mode — fixed for the whole download (see downloader_mux). */
enum ProgressMode { PROGRESS_TIME, PROGRESS_SIZE, PROGRESS_NONE };

/* Muxed position common to every stream — the minimum of the per-stream
 * accumulators that are still ADVANCING. With split audio+video the accumulators
 * advance at different rates, so the min is the position every active track has
 * reached: monotonic (the min of non-decreasing series is non-decreasing) and a
 * measure of how much complete output exists. For a single stream it is just
 * that stream's value.
 *
 * A stream is dropped from the min once it stalls for PROGRESS_STALL_TICKS
 * consecutive ticks (hysteresis, so a momentary gap between an audio stream's
 * bursty packets does not bounce it in and out). This is what keeps a spliced
 * VOD's progress moving: the post-discontinuity audio packets are demuxed with
 * duration 0, so the audio accumulator FREEZES while video keeps advancing —
 * without the exclusion, min() would pin progress to the frozen audio track.
 * A final non-regress clamp guarantees the reported position never steps back
 * when a stream flips between stalled and advancing.
 *
 * Mutates progress-only state; called once per progress tick from the mux
 * thread, so the stall counters are in tick units and need no lock. */
static int64_t downloader_muxed_position(struct Downloader *downloader) {
    int64_t pos;
    int64_t cur;
    int counted;
    int s;

    /* Update per-stream stall tracking: growth resets the counter, no growth
     * since the previous tick increments it. */
    for (s = 0; s < downloader->capture_streams_no; s++) {
        cur = downloader->current_recording_time[s];
        if (cur > downloader->progress_prev_recording_time[s]) {
            downloader->progress_stall_ticks[s] = 0;
        } else {
            downloader->progress_stall_ticks[s] += 1;
        }
        downloader->progress_prev_recording_time[s] = cur;
    }

    /* Min over the streams that are not (yet) considered stalled. */
    pos = INT64_MAX;
    counted = 0;
    for (s = 0; s < downloader->capture_streams_no; s++) {
        if (downloader->progress_stall_ticks[s] >= PROGRESS_STALL_TICKS) {
            continue;
        }
        if (downloader->current_recording_time[s] < pos) {
            pos = downloader->current_recording_time[s];
        }
        counted += 1;
    }

    /* Every stream stalled (a genuine pause, or before any data has flowed):
     * fall back to the plain min over all streams. */
    if (counted == 0) {
        pos = downloader->current_recording_time[0];
        for (s = 1; s < downloader->capture_streams_no; s++) {
            if (downloader->current_recording_time[s] < pos) {
                pos = downloader->current_recording_time[s];
            }
        }
    }

    /* Never regress. */
    if (pos < downloader->progress_last_position) {
        pos = downloader->progress_last_position;
    }
    downloader->progress_last_position = pos;
    return pos;
}

/* Per-tick progress diagnostics (compiled in only at LOG_LEVEL >= 1; the
 * whole function and its call site vanish at the default LOG_LEVEL 0, so the
 * mux hot path pays nothing — not even the call + argument marshalling — for
 * logging that isn't built). "trigger" is the stream of the packet that fired
 * this tick; the per-stream lines show each accumulator so the A/V divergence
 * — and that the TIME value tracks the lagging track — stays visible. */
#if LOG_LEVEL >= 1
static void downloader_log_progress(const struct Downloader *downloader,
                                     enum ProgressMode progress_mode,
                                     enum AVMediaType trigger_type,
                                     int64_t progress_value,
                                     int64_t progress_total) {
    const char *mode_tag;
    const char *trigger_tag;
    double pct;
    int s;

    mode_tag = progress_mode == PROGRESS_TIME ? "TIME"
             : progress_mode == PROGRESS_SIZE ? "SIZE" : "NONE";
    trigger_tag = "?";
    if (trigger_type == AVMEDIA_TYPE_VIDEO) {
        trigger_tag = "V";
    } else if (trigger_type == AVMEDIA_TYPE_AUDIO) {
        trigger_tag = "A";
    }
    pct = 0.0;
    if (progress_total > 0) {
        pct = 100.0 * (double) progress_value / (double) progress_total;
    }
    LOGI(1, "downloader_mux progress: mode=%s trigger=%s val=%"PRId64" total=%"PRId64" (%.1f%%) size=%"PRId64,
         mode_tag, trigger_tag, progress_value, progress_total, pct,
         downloader->current_size);
    for (s = 0; s < downloader->capture_streams_no; s++) {
        enum AVMediaType t = downloader->input_codec_ctxs[s]->codec_type;
        const char *stream_tag = "?";
        if (t == AVMEDIA_TYPE_VIDEO) {
            stream_tag = "V";
        } else if (t == AVMEDIA_TYPE_AUDIO) {
            stream_tag = "A";
        }
        LOGI(1, "downloader_mux progress:   stream[%d] type=%s acc=%"PRId64,
             s, stream_tag, downloader->current_recording_time[s]);
    }
}
#endif


/* =========================================================================
 * Mux thread
 *
 * Lifecycle:
 *   1. Attach to JVM.
 *   2. Clear stop_stream (we're starting fresh).
 *   3. Write header.
 *   4. Loop: pop packet, transform timestamps, av_interleaved_write_frame,
 *            occasionally call back into Java with progress.
 *   5. On stop / EOF / error: drain anything left in the queue, set
 *      stop_stream = TRUE so the read thread can wake and proceed,
 *      write trailer, callback finished, detach, set
 *      muxing_thread_created = FALSE.
 *
 * Lock discipline:
 *   The pop helper releases the lock on success and the caller unlocks
 *   on the normal path. The `stop:` label is reached from three places,
 *   each of which arranges for the lock to be held on entry.
 * ========================================================================= */
void *downloader_mux(void *data) {
    struct Downloader *downloader = data;
    struct PacketData *packet_data;
    struct PacketData *to_free;
    AVDictionary *dict = NULL;
    Queue *queue = downloader->packets;
    AVStream *input_stream;
    AVStream *output_stream;
    enum AVMediaType codec_type;
    AVPacket *pkt;
    JNIEnv *env;
    int stream_no;
    int interrupt_ret;
    int64_t current_time;
    int64_t time_diff;
    int64_t recording_time;
    int64_t progress_total;
    int64_t progress_value;
    const char *input_format_name;
    int is_hls;
    int is_dash;
    enum ProgressMode progress_mode;
    int err = ERROR_NO_ERROR;
    char thread_title[] = "downloader_mux";
    JavaVMAttachArgs thread_spec = {JNI_VERSION_1_6, thread_title, NULL};

    LOGI(10, "downloader_mux init");

    jint ret = (*downloader->get_javavm)->AttachCurrentThread(downloader->get_javavm,
                                                              &env, &thread_spec);
    if (ret || env == NULL) {
        err = -ERROR_COULD_NOT_ATTACH_THREAD;
        goto exit_no_detach;
    }

    downloader_clear_stop(downloader);

    /* Pick the longest input duration as the progress denominator. */
    recording_time = AV_NOPTS_VALUE;
    for (int i = 0; i < downloader->nb_inputs; i++) {
        int64_t d = downloader->input_format_ctx[i]->duration;
        if (d != AV_NOPTS_VALUE && (recording_time == AV_NOPTS_VALUE || d > recording_time))
            recording_time = d;
    }

    /* HLS/DASH detection is fixed for the whole download (the input format
     * doesn't change mid-stream), so derive it once here instead of on every
     * progress tick. It gates the size-based fallback: an HLS/DASH probe's
     * Content-Length is the playlist byte size, not the media size, so it must
     * never be used as a progress denominator. */
    input_format_name = downloader->input_format_ctx[0]->iformat->name;
    is_hls = av_strncasecmp(input_format_name, "hls", 3) == 0;
    is_dash = av_strncasecmp(input_format_name, "dash", 4) == 0;

    /* Progress mode is fixed for the whole download — duration, input format
     * and known total size don't change mid-stream — so decide it once here
     * instead of re-deriving the branch on every tick:
     *   TIME  report the muxed position vs. duration (HLS/DASH VOD and any
     *         input that reports a duration — the normal path);
     *   SIZE  duration unknown but a real Content-Length is known: bytes muxed
     *         vs. total. Progressive only — an HLS/DASH probe's Content-Length
     *         is the playlist size, not the media size;
     *   NONE  neither known: report indeterminate (AV_NOPTS_VALUE). */
    if (recording_time > 0) {
        progress_mode = PROGRESS_TIME;
        progress_total = recording_time;
    } else if (downloader->total_size > 0 && !is_hls && !is_dash) {
        progress_mode = PROGRESS_SIZE;
        progress_total = downloader->total_size;
    } else {
        progress_mode = PROGRESS_NONE;
        progress_total = AV_NOPTS_VALUE;
    }

    av_dict_set(&dict, "movflags", "faststart", 0);
    ret = avformat_write_header(downloader->output_format_ctx, &dict);
    av_dict_free(&dict);
    if (ret < 0) {
        LOGE(2, "downloader_mux write_header error");
        err = -ERROR_COULD_NOT_WRITE_HEADER;
        downloader->mux_error = err;
        goto detach_current_thread;
    }

    for (;;) {
        LOGI(10, "downloader_mux loop");

        pthread_mutex_lock(&downloader->mutex_queue);

        packet_data = queue_pop_start_already_locked(&queue,
                                                     &downloader->mutex_queue,
                                                     &downloader->cond_queue,
                                                     (QueueCheckFunc) downloader_mux_queue_check_func,
                                                     downloader,
                                                     (void **) &interrupt_ret);

        if (packet_data == NULL || packet_data->packet == NULL) {
            /* Skipped without popping — no queue_pop_finish needed. */
            av_assert0(interrupt_ret == MUXER_CHECK_MSG_STOP);
            goto stop; /* lock still held */
        }

        pthread_mutex_unlock(&downloader->mutex_queue);

        if (packet_data->end_of_stream) {
            LOGI(1, "downloader_mux end_of_stream");
            queue_pop_finish(queue, &downloader->mutex_queue, &downloader->cond_queue);
            pthread_mutex_lock(&downloader->mutex_queue);
            goto stop;
        }

        pkt = packet_data->packet;
        stream_no = pkt->stream_index; /* tagged by reader as the capture index */

        if (stream_no < 0 || stream_no >= downloader->capture_streams_no) {
            LOGW(1, "downloader_mux unknown capture stream %d, skipping", stream_no);
            av_packet_unref(pkt);
            queue_pop_finish(queue, &downloader->mutex_queue, &downloader->cond_queue);
            continue;
        }

        input_stream = downloader->input_streams[stream_no];
        output_stream = downloader->output_format_ctx->streams[stream_no];
        codec_type = downloader->input_codec_ctxs[stream_no]->codec_type;

        downloader->current_size += pkt->size;
        downloader->current_recording_time[stream_no] +=
                av_rescale_q(pkt->duration, input_stream->time_base, AV_TIME_BASE_Q);

        /* Shift timestamps so the output starts at zero. */
        pkt->time_base = input_stream->time_base;
        if (pkt->dts != AV_NOPTS_VALUE)
            pkt->dts += av_rescale_q(-downloader->start_time, AV_TIME_BASE_Q, pkt->time_base);
        if (pkt->pts != AV_NOPTS_VALUE)
            pkt->pts += av_rescale_q(-downloader->start_time, AV_TIME_BASE_Q, pkt->time_base);

        if (output_stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            if (pkt->dts == AV_NOPTS_VALUE) {
                /* av_rescale_delta() av_assert0()s that its input timestamp is
                 * not AV_NOPTS_VALUE and abort()s (SIGABRT) otherwise. Audio
                 * packets CAN arrive with no timestamp right after an HLS
                 * EXT-X-DISCONTINUITY — observed on spliced Kick VODs, where the
                 * first audio packet of the post-splice segment had
                 * pts=dts=AV_NOPTS_VALUE. Synthesise a monotonic dts in the output
                 * timebase from the last muxed one instead of rescaling. (The
                 * generic no-PTS synthesis further down runs only AFTER this
                 * rescale, too late to prevent the abort.) */
                if (downloader->last_mux_dts[stream_no] != AV_NOPTS_VALUE)
                    pkt->dts = downloader->last_mux_dts[stream_no] + 1;
                else
                    pkt->dts = 0;
                pkt->pts = pkt->dts;
                pkt->duration = 0;
            } else {
                /* Use av_rescale_delta to preserve accuracy with coarse audio timebases. */
                int duration = av_get_audio_frame_duration2(output_stream->codecpar, pkt->size);
                if (!duration)
                    duration = output_stream->codecpar->frame_size;

                pkt->dts = av_rescale_delta(pkt->time_base, pkt->dts,
                                            (AVRational) {1, output_stream->codecpar->sample_rate},
                                            duration,
                                            &downloader->filter_in_rescale_delta_last[stream_no],
                                            output_stream->time_base);
                pkt->pts = pkt->dts;
                pkt->duration = av_rescale_q(pkt->duration, input_stream->time_base,
                                             output_stream->time_base);
            }
        } else {
            av_packet_rescale_ts(pkt, pkt->time_base, output_stream->time_base);
        }

        pkt->time_base = output_stream->time_base;
        pkt->stream_index = output_stream->id;

        /* dts > pts: bogus, fix it (mirrors the heuristic in ffmpeg.c) */
        if (pkt->dts != AV_NOPTS_VALUE && pkt->pts != AV_NOPTS_VALUE && pkt->dts > pkt->pts) {
            int64_t last1 = downloader->last_mux_dts[stream_no] + 1;
            LOGW(6, "downloader_mux invalid DTS:%"PRId64" PTS:%"PRId64", repairing\n", pkt->dts, pkt->pts);
            pkt->pts = pkt->dts =
                    pkt->pts + pkt->dts + last1
                    - FFMIN3(pkt->pts, pkt->dts, last1)
                    - FFMAX3(pkt->pts, pkt->dts, last1);
        }

        /* Monotonically increasing dts */
        if ((codec_type == AVMEDIA_TYPE_AUDIO || codec_type == AVMEDIA_TYPE_VIDEO ||
             codec_type == AVMEDIA_TYPE_SUBTITLE) &&
            pkt->dts != AV_NOPTS_VALUE &&
            downloader->last_mux_dts[stream_no] != AV_NOPTS_VALUE) {
            int64_t max = downloader->last_mux_dts[stream_no] + 1;
            if (pkt->dts < max) {
                LOGW(6, "downloader_mux clamping dts to %"PRId64"\n", max);
                if (pkt->pts >= pkt->dts)
                    pkt->pts = FFMAX(pkt->pts, max);
                pkt->dts = max;
            }
        }

        if (pkt->dts == AV_NOPTS_VALUE || pkt->pts == AV_NOPTS_VALUE) {
            LOGE(1, "downloader_mux nopts, synthesising");
            pkt->dts = pkt->pts = downloader->last_mux_dts[stream_no] + 1;
        }

        downloader->last_mux_dts[stream_no] = pkt->dts;

        ret = av_interleaved_write_frame(downloader->output_format_ctx, pkt);
        if (ret < 0) {
            LOGE(1, "downloader_mux write_frame error: %s", av_err2str(ret));
            av_packet_unref(pkt);
            /* Drop the packet from the queue and transition to stop. */
            queue_pop_finish(queue, &downloader->mutex_queue, &downloader->cond_queue);
            pthread_mutex_lock(&downloader->mutex_queue);
            downloader->mux_error = ret;
            goto stop;
        }

        av_packet_unref(pkt);
        queue_pop_finish(queue, &downloader->mutex_queue, &downloader->cond_queue);

        /* ---- Progress callback throttled to UPDATE_TIME_US ---- */
        current_time = av_gettime_relative();
        time_diff = downloader->last_updated_time - current_time;

        if (time_diff > UPDATE_TIME_US || time_diff < -UPDATE_TIME_US) {
            downloader->last_updated_time = current_time;

            switch (progress_mode) {
                case PROGRESS_TIME:
                    progress_value = downloader_muxed_position(downloader);
                    /* Upper-clamp to the total — the symmetric partner of the
                     * non-regress (lower) clamp in downloader_muxed_position.
                     * The denominator (progress_total) is ffmpeg's PROBE-estimated
                     * duration, computed once from input_format_ctx->duration. On a
                     * discontinuity-spliced VOD (Twitch/Kick ad-stitching) the
                     * MPEG-TS PTS RESETS at every EXT-X-DISCONTINUITY, so
                     * find_stream_info's pts-span estimate UNDERESTIMATES the true
                     * length, while current_recording_time accumulates the real
                     * summed pkt->duration and grows past it — driving the reported
                     * position over 100% (observed at 165%+ and climbing). The
                     * accumulator is the better measure of muxed output, but it must
                     * never be reported above the total: the bar saturates at 100%
                     * (matching the recording_time/recording_time completion
                     * callback) instead of showing a nonsensical >100%. */
                    if (progress_total > 0 && progress_value > progress_total) {
                        progress_value = progress_total;
                    }
                    break;
                case PROGRESS_SIZE:
                    progress_value = downloader->current_size;
                    break;
                default:
                    progress_value = AV_NOPTS_VALUE;
                    break;
            }

#if LOG_LEVEL >= 1
            downloader_log_progress(downloader, progress_mode, codec_type,
                                    progress_value, progress_total);
#endif

            (*env)->CallVoidMethod(env, downloader->thiz,
                                   downloader->downloader_on_progress_update_method,
                                   progress_value, progress_total);

            /* Java callbacks may throw; clear so subsequent JNI calls don't blow up. */
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                LOGE(1, "downloader_mux exception in progress callback");
            }
        }
    }

    /* ===== stop: invariant — mutex_queue is held on entry ===== */
    stop:
    LOGI(3, "downloader_mux stop");

    /* Drain anything still queued and set stop_stream so the reader can
     * see the muxer is shutting down. */
    while ((to_free = queue_pop_start_already_locked_non_block(queue)) != NULL) {
        if (!to_free->end_of_stream)
            av_packet_unref(to_free->packet);
        queue_pop_finish_already_locked(queue, &downloader->mutex_queue, &downloader->cond_queue);
    }
    downloader_request_stop_locked(downloader);
    pthread_mutex_unlock(&downloader->mutex_queue);

    detach_current_thread:
    if (!err && !downloader->mux_error) {
        av_write_trailer(downloader->output_format_ctx);
    }

    if (downloader->interrupt == FALSE && downloader->thiz != NULL) {
        if (recording_time > 0) {
            (*env)->CallVoidMethod(env, downloader->thiz,
                                   downloader->downloader_on_progress_update_method,
                                   recording_time, recording_time);
            /* [BUG FIX] Each Java callback needs its own exception
             * check. The single check at the bottom only covered the
             * finished method — if progress throws, finished was
             * issued with a pending exception, which JNI escalates
             * to "JNI ERROR (app bug): accessed stale Object". */
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                LOGE(1, "downloader_mux exception in progress callback");
            }
        }
        (*env)->CallVoidMethod(env, downloader->thiz,
                               downloader->downloader_on_download_finished_method);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE(1, "downloader_mux exception in finished callback");
        }
    }

    LOGI(3, "downloader_mux detach");
    ret = (*downloader->get_javavm)->DetachCurrentThread(downloader->get_javavm);
    if (ret && !err)
        err = ERROR_COULD_NOT_DETACH_THREAD;

    exit_no_detach:
    downloader_mark_muxer_exited(downloader);
    LOGI(3, "downloader_mux finished err=%d", err);
    return NULL;
}


/* Push a sentinel packet that tells the muxer "no more data is coming",
 * then wait for the muxer to exit. */
enum ReadFromStreamCheckMsg downloader_push_end_of_stream(struct Downloader *downloader) {
    struct PacketData *packet_data;
    Queue *queue;
    int to_write;
    int interrupt_ret;

    pthread_mutex_lock(&downloader->mutex_queue);

    queue = downloader->packets;
    packet_data = queue_push_start_already_locked(queue,
                                                  &downloader->mutex_queue,
                                                  &downloader->cond_queue, &to_write,
                                                  (QueueCheckFunc) downloader_read_from_stream_check_func,
                                                  downloader,
                                                  (void **) &interrupt_ret);
    if (packet_data == NULL) {
        pthread_mutex_unlock(&downloader->mutex_queue);
        if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_STOP) return READ_FROM_STREAM_CHECK_MSG_STOP;
        if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_MUX_ERROR) return READ_FROM_STREAM_CHECK_MSG_MUX_ERROR;
        av_assert0(0);
    }

    packet_data->end_of_stream = TRUE;
    queue_push_finish_already_locked(queue, &downloader->mutex_queue,
                                     &downloader->cond_queue, to_write);
    downloader->eof = TRUE;

    /* Wait for the mux thread to finish processing what's left and exit.
     * The previous incarnation of this loop had no terminating broadcast
     * because it relied on the mux thread setting muxing_thread_created
     * outside the lock, which created a missed-wakeup window. The mux
     * thread now sets it under the lock and broadcasts. */
    LOGI(3, "downloader_push_end_of_stream waiting for muxer to drain");
    while (downloader->muxing_thread_created && !downloader->stop) {
        pthread_cond_wait(&downloader->cond_queue, &downloader->mutex_queue);
    }
    pthread_mutex_unlock(&downloader->mutex_queue);
    return READ_FROM_STREAM_CHECK_MSG_STOP;
}


int downloader_start_threads(struct Downloader *downloader) {
    pthread_attr_t attr;
    int err = ERROR_NO_ERROR;

    if (pthread_attr_init(&attr)) {
        return -ERROR_COULD_NOT_INIT_PTHREAD_ATTR;
    }

    /* Set the flag BEFORE pthread_create. If we set it after, the new
     * thread can run, exit, and clear the flag before we ever set it,
     * leaving us thinking the thread is alive when it isn't. */
    downloader->muxing_thread_created = TRUE;

    if (pthread_create(&downloader->muxing_thread, &attr, downloader_mux, downloader)) {
        downloader->muxing_thread_created = FALSE;
        err = -ERROR_COULD_NOT_CREATE_PTHREAD;
    }

    if (pthread_attr_destroy(&attr) && !err)
        err = -ERROR_COULD_NOT_DESTROY_PTHREAD_ATTR;

    return err;
}


int downloader_find_stream_info(struct Downloader *downloader) {
    int err;
    for (int i = 0; i < downloader->nb_inputs; i++) {
        if ((err = avformat_find_stream_info(downloader->input_format_ctx[i], NULL)) < 0) {
            LOGE(1, "Could not find stream info for input %d\n", i);
            return -ERROR_COULD_NOT_OPEN_STREAM;
        }
        for (int s = 0; s < downloader->input_format_ctx[i]->nb_streams; s++) {
            downloader->input_format_ctx[i]->streams[s]->discard = AVDISCARD_ALL;
        }
    }
    return ERROR_NO_ERROR;
}


int downloader_alloc_input_contexts(struct Downloader *downloader) {
    for (int i = 0; i < downloader->nb_inputs; i++) {
        if ((downloader->input_format_ctx[i] = avformat_alloc_context()) == NULL)
            return AVERROR(ENOMEM);
    }
    return ERROR_NO_ERROR;
}


/* =========================================================================
 * Public stop / dealloc entry points
 * ========================================================================= */

void jni_downloader_dealloc(JNIEnv *env, jobject thiz) {
    struct Downloader *downloader = get_downloader_field(env, thiz);
    LOGI(1, "jni_downloader_dealloc");

    if (downloader != NULL) {
        struct State state = {.downloader = downloader, .env = env};
        downloader->interrupt = TRUE;

        pthread_mutex_lock(&downloader->mutex_operation);
        downloader_stop_without_lock(&state);
        pthread_mutex_unlock(&downloader->mutex_operation);

        if (downloader->thiz != NULL) {
            (*env)->DeleteGlobalRef(env, downloader->thiz);
            downloader->thiz = NULL;
        }

        pthread_cond_destroy (&downloader->cond_queue);
        pthread_mutex_destroy(&downloader->mutex_queue);
        pthread_mutex_destroy(&downloader->mutex_operation);
        av_free(downloader);
    }

    clean_downloader_field(env, thiz);
    LOGI(1, "jni_downloader_dealloc done");
}


void jni_downloader_stop(JNIEnv *env, jobject thiz) {
    struct Downloader *downloader = get_downloader_field(env, thiz);
    LOGI(1, "jni_downloader_stop");
    if (downloader == NULL) {
        LOGW(1, "jni_downloader_stop already freed");
        return;
    }

    /* Async stop: only signal. The download thread itself does the join
     * later, in jni_downloader_dealloc. Joining from this caller (typically
     * the service handler thread) caused ANRs. */
    downloader->interrupt = TRUE;

    pthread_mutex_lock(&downloader->mutex_queue);
    downloader->stop = TRUE;
    pthread_cond_broadcast(&downloader->cond_queue);
    pthread_mutex_unlock(&downloader->mutex_queue);
}


/* =========================================================================
 * Read loop — runs on the JNI caller thread.
 *
 * Reads packets from each input round-robin, finds which captured stream
 * each packet belongs to, and pushes onto the queue. On EOF / interrupt /
 * mux error: signals the mux thread, waits for it to exit, then returns.
 * ========================================================================= */
int downloader_read(struct Downloader *downloader) {
    struct PacketData *packet_data;
    Queue *queue;
    AVPacket packet, *pkt = &packet;
    char errbuf[256];
    int interrupt_ret;
    int to_write;
    int stream_no;
    int err = ERROR_NO_ERROR;
    int ret;
    int all_eof;
    AVStream *pkt_stream;
    enum AVMediaType pkt_type;

    /* read_thread_created is now set by jni_downloader_start before
     * it releases mutex_operation, so dealloc's threads-free wait sees
     * us in time. The previous set-on-entry here left a window where
     * a racing dealloc would skip the wait and destroy mutex_queue
     * out from under the lock at line 1303. */

    LOGI(3, "downloader_read output=%s nb_inputs=%d",
         downloader->output_format_ctx->url, downloader->nb_inputs);

    for (int i = 0; i < downloader->nb_inputs; i++)
        downloader->input_eof[i] = FALSE;

    for (;;) {
        /* Top-of-loop interrupt check.
         *
         * The per-input check below sits AFTER the input_eof skip, so if
         * every input has already EOF'd (e.g. okhttp got InterruptedIOException
         * and av_read_frame returned AVERROR_EOF for the only input) we'd
         * never reach the interrupt check inside the inner loop. The
         * post-loop "all-eof, push EOS and exit" block also wouldn't fire
         * because it requires !interrupt && !stop. Net result: infinite
         * busy-spin after a stop request that arrives at-or-after EOF on
         * every input. Catch the wakeup signal here at the top so the loop
         * can exit before re-entering the inputs.
         */
        if (downloader->interrupt || downloader->stop)
            goto exit_loop_lock;

        int any_read = FALSE;

        for (int input_idx = 0; input_idx < downloader->nb_inputs; input_idx++) {

            if (downloader->input_eof[input_idx])
                continue;

            if (downloader->interrupt || downloader->stop)
                goto exit_loop_lock;

            AVFormatContext *fmt_ctx = downloader->input_format_ctx[input_idx];

            ret = av_read_frame(fmt_ctx, pkt);
            if (ret < 0) {
                av_strerror(ret, errbuf, sizeof(errbuf));
                LOGW(3, "downloader_read input[%d] ret=%d msg=\"%s\"", input_idx, ret, errbuf);

                if (ret == AVERROR_EOF || (fmt_ctx->pb && avio_feof(fmt_ctx->pb)) || ret == AVERROR(EINVAL)) {
                    downloader->input_eof[input_idx] = TRUE;
                    continue;
                }
                if (fmt_ctx->pb && fmt_ctx->pb->error) {
                    LOGW(3, "downloader_read input[%d] pb error", input_idx);
                    downloader->input_eof[input_idx] = TRUE;
                }
                /* Other transient error: fall through and try next input. */
                continue;
            }

            any_read = TRUE;

            /* Match packet → capture stream.
             *
             * The slot was bound at find time to a specific (input,
             * input-stream-index). But an HLS EXT-X-DISCONTINUITY can splice in a
             * segment from a different encoder whose MPEG-TS PIDs differ, and the
             * mpegts demuxer then RENUMBERS / REUSES AVStream indices across the
             * splice. Observed on Kick VODs: a ~2 s CloudFront preroll carries
             * audio on pid 0x101, while the main IVS content carries VIDEO on pid
             * 0x101 — reusing AVStream index 1. A frozen index map then feeds the
             * new H264 packets into the AUDIO capture, and the mp4 muxer's
             * auto-inserted aac_adtstoasc bitstream filter aborts the whole
             * download ("Error parsing ADTS frame header").
             *
             * So match by the capture's CODEC TYPE (fixed for the whole download)
             * against the packet's CURRENT input-stream type, and re-bind the
             * slot's input-stream index when the splice moved it. firedown
             * captures exactly one stream per type (one video + one audio; split
             * audio/video are separate inputs, disambiguated by input_source_index
             * below), so a type maps to a single capture unambiguously — a multi-
             * resolution ABR master is never one input here, each rendition is its
             * own playlist and we download one. A foreign stream whose type matches
             * no capture (e.g. the main content's timed_id3 data track) matches
             * nothing and is dropped, exactly as before. */
            pkt_stream = NULL;
            pkt_type = AVMEDIA_TYPE_UNKNOWN;
            if (pkt->stream_index >= 0 && pkt->stream_index < (int) fmt_ctx->nb_streams) {
                pkt_stream = fmt_ctx->streams[pkt->stream_index];
                pkt_type = pkt_stream->codecpar->codec_type;
            }

            pthread_mutex_lock(&downloader->mutex_queue);

            queue = NULL;
            for (stream_no = 0; stream_no < downloader->capture_streams_no; ++stream_no) {
                if (input_idx != downloader->input_source_index[stream_no])
                    continue;
                if (pkt_type != downloader->capture_codec_type[stream_no])
                    continue;
                /* Packet belongs to this capture's type. Follow a discontinuity
                 * remap by re-pointing the slot at the packet's current input
                 * stream. Only input_stream_numbers is updated (read by this
                 * reader thread alone); input_streams[] is deliberately left as-is
                 * — the muxer reads it without the queue lock for the stream
                 * timebase, and an MPEG-TS discontinuity keeps the 1/90000
                 * timebase, so the stored stream stays valid for that use. */
                if (downloader->input_stream_numbers[stream_no] != pkt->stream_index) {
                    LOGW(2, "downloader_read re-bind capture[%d] type=%d input-stream %d -> %d (discontinuity remap)",
                         stream_no, pkt_type,
                         downloader->input_stream_numbers[stream_no], pkt->stream_index);
                    downloader->input_stream_numbers[stream_no] = pkt->stream_index;
                }
                queue = downloader->packets;
                break;
            }
            if (queue == NULL) {
                av_packet_unref(pkt);
                pthread_mutex_unlock(&downloader->mutex_queue);
                continue;
            }

            if (downloader->interrupt) {
                av_packet_unref(pkt);
                goto exit_loop; /* lock held */
            }

            packet_data = queue_push_start_already_locked(queue,
                                                          &downloader->mutex_queue,
                                                          &downloader->cond_queue, &to_write,
                                                          (QueueCheckFunc) downloader_read_from_stream_check_func,
                                                          downloader,
                                                          (void **) &interrupt_ret);
            if (packet_data == NULL) {
                av_packet_unref(pkt);
                if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_STOP) {
                    goto exit_loop;
                } else if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_MUX_ERROR) {
                    err = -ERROR_COULD_NOT_READ;
                    goto exit_loop;
                } else if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_SKIP) {
                    pthread_mutex_unlock(&downloader->mutex_queue);
                    continue;
                }
                av_assert0(0);
            }

            pthread_mutex_unlock(&downloader->mutex_queue);

            packet_data->end_of_stream = FALSE;
            av_packet_ref(packet_data->packet, pkt);
            av_packet_unref(pkt);
            /* Tag with capture stream number so the muxer matches unambiguously. */
            packet_data->packet->stream_index = stream_no;

            queue_push_finish(queue, &downloader->mutex_queue,
                              &downloader->cond_queue, to_write);
        }

        /* Did we get all-the-way through every input without reading? */
        if (!any_read && !downloader->interrupt && !downloader->stop) {
            all_eof = TRUE;
            for (int j = 0; j < downloader->nb_inputs; j++) {
                if (!downloader->input_eof[j]) { all_eof = FALSE; break; }
            }
            if (all_eof && !downloader->eof) {
                downloader_push_end_of_stream(downloader);
                pthread_mutex_lock(&downloader->mutex_queue);
                goto exit_loop;
            }

            /* Brief wait so we don't busy-spin on transient errors. */
            struct timespec deadline;
            struct timeval now;
            gettimeofday(&now, NULL);
            deadline.tv_sec = now.tv_sec;
            deadline.tv_nsec = now.tv_usec * 1000L + READ_BACKOFF_NS;
            if (deadline.tv_nsec >= 1000000000L) {
                deadline.tv_sec += deadline.tv_nsec / 1000000000L;
                deadline.tv_nsec %= 1000000000L;
            }
            pthread_mutex_lock(&downloader->mutex_queue);
            pthread_cond_timedwait(&downloader->cond_queue, &downloader->mutex_queue, &deadline);
            pthread_mutex_unlock(&downloader->mutex_queue);
        }
        continue;

        exit_loop_lock:
        pthread_mutex_lock(&downloader->mutex_queue);
        exit_loop:
        LOGI(2, "downloader_read exit_loop");
        av_packet_unref(pkt);

        for (int i = 0; i < downloader->nb_inputs; i++)
            av_read_pause(downloader->input_format_ctx[i]);

        downloader_request_stop_locked(downloader);
        downloader_wait_for_muxer_exit_locked(downloader);

        for (stream_no = 0; stream_no < downloader->capture_streams_no; ++stream_no) {
            if (downloader->input_codec_ctxs[stream_no]->codec != NULL)
                avcodec_flush_buffers(downloader->input_codec_ctxs[stream_no]);
        }

        pthread_cond_broadcast(&downloader->cond_queue);
        pthread_mutex_unlock(&downloader->mutex_queue);
        goto end;
    }

    end:
    /* Set under lock so the broadcast can't be missed by a waiter that
     * raced ahead and would otherwise sleep forever. */
    pthread_mutex_lock(&downloader->mutex_queue);
    downloader->read_thread_created = FALSE;
    pthread_cond_broadcast(&downloader->cond_queue);
    pthread_mutex_unlock(&downloader->mutex_queue);
    LOGI(3, "downloader_read done");
    return err;
}


/* =========================================================================
 * Pipeline setup
 * ========================================================================= */

int downloader_set_data_source(struct State *state, AVDictionary **dicts, int nb_dicts,
                               AVDictionary *metadata, const char **file_paths, int nb_urls,
                               const char *output_path,
                               const int *video_streams_per_input,
                               const int *audio_streams_per_input) {
    struct Downloader *downloader = state->downloader;
    int err = ERROR_NO_ERROR;
    int metadata_consumed = FALSE;

    downloader_stop_without_lock(state);
    downloader->nb_inputs = nb_urls;

    (*state->env)->CallVoidMethod(state->env, downloader->thiz,
                                  downloader->downloader_on_download_started_method);
    if ((*state->env)->ExceptionCheck(state->env)) {
        (*state->env)->ExceptionClear(state->env);
        LOGE(1, "downloader_set_data_source exception in started callback");
    }

    if (downloader->interrupt || (err = downloader_alloc_input_contexts(downloader)) < 0) goto error;
    if (downloader->interrupt || (err = downloader_create_interrupt_callback(downloader)) < 0) goto error;

    /* downloader_open_input now takes ownership of dicts[i] regardless of
     * outcome and NULLs the pointer, so we don't need a separate
     * "consumed" tracking array. */
    for (int i = 0; i < nb_urls; i++) {
        if (downloader->interrupt) goto error;
        if ((err = downloader_open_input(downloader, i, &dicts[i], file_paths[i])) < 0)
            goto error;
    }

    if (downloader->interrupt || (err = downloader_find_stream_info(downloader)) < 0) goto error;

#if LOG_LEVEL >= 3
    for (int i = 0; i < nb_urls; i++)
        av_dump_format(downloader->input_format_ctx[i], i, file_paths[i], FALSE);
#endif

    if (downloader->interrupt ||
        (downloader->video_stream_no = downloader_find_streams(downloader, AVMEDIA_TYPE_VIDEO,
                                                               video_streams_per_input)) < 0) {
        downloader->disable_video = TRUE;
    }
    if (downloader->interrupt ||
        (downloader->audio_stream_no = downloader_find_streams(downloader, AVMEDIA_TYPE_AUDIO,
                                                               audio_streams_per_input)) < 0) {
        downloader->disable_audio = TRUE;
    }

    if (downloader->disable_audio && downloader->disable_video) {
        err = -ERROR_COULD_NOT_FIND_STREAMS;
        goto error;
    }

    downloader_init_offset(downloader);

    if (downloader->interrupt ||
        (err = downloader_create_context(downloader, output_path, metadata)) < 0) {
        metadata_consumed = TRUE; /* create_context freed it on error */
        goto error;
    }
    metadata_consumed = TRUE; /* and on success ownership transferred */

    if (downloader->interrupt || (err = downloader_create_output_format(downloader, output_path)) < 0) goto error;
    if (downloader->interrupt || (err = downloader_open_output(downloader, output_path)) < 0) goto error;
    if (downloader->interrupt || (err = downloader_alloc_queues(state)) < 0) goto error;
    if (downloader->interrupt || (err = downloader_init_output_streams(downloader)) < 0) goto error;

    downloader_prepare(downloader);

    if (downloader->interrupt || (err = downloader_start_threads(downloader)) < 0) goto error;

    LOGI(1, "downloader_set_data_source ok");
    return ERROR_NO_ERROR;

    error:
    LOGE(1, "downloader_set_data_source error %d", err);
    downloader_prepare_free(downloader);
    downloader_threads_free(downloader);
    downloader_find_streams_free(downloader);
    downloader_alloc_queues_free(state);
    downloader_find_stream_info_free(downloader);
    downloader_open_input_free(downloader);
    downloader_create_context_free(downloader);

    if (!metadata_consumed)
        av_dict_free(&metadata);

    /* Any dicts not yet handed off to open_input (i.e. still non-NULL) must be freed. */
    for (int i = 0; i < nb_dicts; i++) {
        if (dicts[i] != NULL)
            av_dict_free(&dicts[i]);
    }
    return err;
}


/* =========================================================================
 * JNI start entry point
 * ========================================================================= */
int jni_downloader_start(JNIEnv *env, jobject thiz, jobjectArray jurls, jobjectArray jdicts,
                         jobject metadata, jstring outputPath, jintArray jVideoStreams,
                         jintArray jAudioStreams, jlong totalLength) {
    struct Downloader *downloader = get_downloader_field(env, thiz);
    struct State state = {.downloader = downloader, .env = env};

    AVDictionary *dicts[MAX_DOWNLOAD_INPUTS] = {NULL};
    AVDictionary *met = NULL;
    const char *file_paths[MAX_DOWNLOAD_INPUTS] = {NULL};
    jstring jstrings[MAX_DOWNLOAD_INPUTS] = {NULL};
    int video_streams[MAX_DOWNLOAD_INPUTS];
    int audio_streams[MAX_DOWNLOAD_INPUTS];
    jint *jvideo_data = NULL;
    jint *jaudio_data = NULL;
    const char *output_path = NULL;
    int nb_urls = 0;
    int nb_jdicts = 0;
    int ret = ERROR_NO_ERROR;

    for (int i = 0; i < MAX_DOWNLOAD_INPUTS; i++) {
        video_streams[i] = -1;
        audio_streams[i] = -1;
    }

    downloader->total_size = totalLength > 0 ? totalLength : AV_NOPTS_VALUE;
    LOGI(1, "jni_downloader_start");

    if (jurls == NULL) {
        LOGE(1, "jni_downloader_start NULL urls");
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    nb_urls = (*env)->GetArrayLength(env, jurls);
    nb_jdicts = jdicts != NULL ? (*env)->GetArrayLength(env, jdicts) : 0;

    if (nb_urls <= 0 || nb_urls > MAX_DOWNLOAD_INPUTS) {
        LOGE(1, "jni_downloader_start invalid nb_urls=%d", nb_urls);
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    if (outputPath == NULL) {
        LOGE(1, "jni_downloader_start NULL outputPath");
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    output_path = (*env)->GetStringUTFChars(env, outputPath, NULL);
    if (output_path == NULL) {
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    for (int i = 0; i < nb_urls; i++) {
        jstrings[i] = (jstring) (*env)->GetObjectArrayElement(env, jurls, i);
        if (jstrings[i] == NULL) {
            LOGE(1, "jni_downloader_start NULL url at index %d", i);
            ret = -ERROR_COULD_NOT_INIT_PATHS;
            goto end;
        }
        file_paths[i] = (*env)->GetStringUTFChars(env, jstrings[i], NULL);
        if (file_paths[i] == NULL) {
            ret = -ERROR_COULD_NOT_INIT_PATHS;
            goto end;
        }
        if (jdicts != NULL && i < nb_jdicts) {
            jobject jdict = (*env)->GetObjectArrayElement(env, jdicts, i);
            if (jdict != NULL) {
                dicts[i] = utils_read_dictionary(env, jdict);
                (*env)->DeleteLocalRef(env, jdict);
            }
        }
    }

    if (jVideoStreams != NULL) {
        int len = (*env)->GetArrayLength(env, jVideoStreams);
        jvideo_data = (*env)->GetIntArrayElements(env, jVideoStreams, NULL);
        if (jvideo_data != NULL) {
            for (int i = 0; i < len && i < nb_urls; i++) video_streams[i] = jvideo_data[i];
        }
    }
    if (jAudioStreams != NULL) {
        int len = (*env)->GetArrayLength(env, jAudioStreams);
        jaudio_data = (*env)->GetIntArrayElements(env, jAudioStreams, NULL);
        if (jaudio_data != NULL) {
            for (int i = 0; i < len && i < nb_urls; i++) audio_streams[i] = jaudio_data[i];
        }
    }

    if (metadata != NULL) met = utils_read_dictionary(env, metadata);

    pthread_mutex_lock(&downloader->mutex_operation);

    /* Clear `interrupt` once here, under mutex_operation, after we've
     * checked for a stop request that arrived before we acquired the
     * lock. Anything that arrives during set_data_source / prepare /
     * read needs to honour the flag, so this is the only place that
     * resets it — the create_interrupt_callback step downstream used
     * to clear it per-input and silently lost stop requests. */
    if (downloader->interrupt) {
        LOGE(2, "jni_downloader_start interrupted before start");
        pthread_mutex_unlock(&downloader->mutex_operation);
        goto end;
    }
    downloader->interrupt = FALSE;

    ret = downloader_set_data_source(&state, dicts, nb_urls, met,
                                     file_paths, nb_urls, output_path,
                                     video_streams, audio_streams);
    /* Ownership transferred regardless of success: set_data_source's error
     * path frees them, success path stashed them inside ffmpeg state. */
    for (int i = 0; i < nb_urls; i++) dicts[i] = NULL;
    met = NULL;

    if (ret < 0) {
        LOGE(2, "jni_downloader_start prepare error");
        pthread_mutex_unlock(&downloader->mutex_operation);
        goto end;
    }

    /* Stop arrived during set_data_source — bail BEFORE setting
     * read_thread_created. set_data_source's interrupt-bail path goes to its
     * `error:` label without assigning `err`, so it returns 0; the `ret < 0`
     * check above misses it. If we then mark the read thread alive and skip
     * downloader_read (the original `interrupt || downloader_read(...)`
     * short-circuit), nothing clears read_thread_created → jni_downloader_dealloc's
     * threads_free waits on cond_queue forever for a thread that never ran.
     * That's the Stop/Delete hang on HLS downloads.
     *
     * The race the read_thread_created=TRUE-under-mutex_operation guard was
     * written to close (dealloc seeing FALSE between unlock and downloader_read
     * entry, then freeing state out from under us) is still closed: we only
     * unlock mutex_operation in this bail branch, and we don't touch any of
     * the state dealloc would race to free. */
    if (downloader->interrupt) {
        LOGE(2, "jni_downloader_start interrupted before read");
        pthread_mutex_unlock(&downloader->mutex_operation);
        ret = -ERROR_COULD_NOT_READ;
        goto end;
    }

    /* [BUG FIX] Mark the read thread alive BEFORE releasing
     * mutex_operation, mirroring how muxing_thread_created is set
     * before pthread_create.
     *
     * Race window the previous code left open:
     *   1. jni_downloader_start unlocks mutex_operation here.
     *   2. Another thread enters jni_downloader_dealloc, takes
     *      mutex_operation, calls downloader_threads_free, sees
     *      read_thread_created == FALSE (the read fn hasn't reached
     *      its own set yet), returns immediately, then runs
     *      pthread_cond_destroy / pthread_mutex_destroy and av_free.
     *   3. We reach downloader_read, which tries
     *      pthread_mutex_lock(&downloader->mutex_queue) — destroyed
     *      mutex → FORTIFY abort.
     *
     * Setting the flag under mutex_operation ties it into the same
     * happens-before chain dealloc waits on. */
    pthread_mutex_lock(&downloader->mutex_queue);
    downloader->read_thread_created = TRUE;
    pthread_mutex_unlock(&downloader->mutex_queue);

    pthread_mutex_unlock(&downloader->mutex_operation);

    /* downloader_read clears read_thread_created on exit (under mutex,
     * with a cond_broadcast), so dealloc's wait will wake. If an interrupt
     * is already set on entry it goes straight to its exit path. */
    if (downloader_read(downloader) < 0) {
        LOGE(2, "jni_downloader_start read error");
        ret = -ERROR_COULD_NOT_READ;
        goto end;
    }

    end:
    /* Defensive: free anything we still own. */
    for (int i = 0; i < MAX_DOWNLOAD_INPUTS; i++) {
        if (dicts[i] != NULL) av_dict_free(&dicts[i]);
    }
    if (met != NULL) av_dict_free(&met);

    for (int i = 0; i < nb_urls; i++) {
        if (file_paths[i] != NULL && jstrings[i] != NULL)
            (*env)->ReleaseStringUTFChars(env, jstrings[i], file_paths[i]);
        if (jstrings[i] != NULL)
            (*env)->DeleteLocalRef(env, jstrings[i]);
    }
    if (outputPath != NULL && output_path != NULL)
        (*env)->ReleaseStringUTFChars(env, outputPath, output_path);

    if (jvideo_data != NULL)
        (*env)->ReleaseIntArrayElements(env, jVideoStreams, jvideo_data, JNI_ABORT);
    if (jaudio_data != NULL)
        (*env)->ReleaseIntArrayElements(env, jAudioStreams, jaudio_data, JNI_ABORT);

    return ret;
}