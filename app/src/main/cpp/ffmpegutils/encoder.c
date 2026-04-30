/*
 * encoder.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#include <stdlib.h>
#include <stdio.h>

#include <libavutil/opt.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avassert.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <pthread.h>

#include <android/log.h>
#include <jni.h>

#include "helpers.h"
#include "encoder.h"
#include "utils.h"
#include "queue.h"

#define LOG_LEVEL 0
#define FFMPEG_LOG_LEVEL AV_LOG_QUIET
#define LOG_TAG "encoder.c"
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}
#define LOGW(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__);}

#define UPDATE_TIME_US 50000ll

#define BUFFER_SIZE 1500

#define STREAM_UNKNOWN (-1)


struct Encoder {
    struct AVFormatContext *input_format_ctx;
    struct AVFormatContext *output_format_ctx;
    AVCodecContext *input_codec_ctx;
    AVCodecContext *output_codec_ctx;

    AVStream *input_stream;

    AVFilterContext *buffersink_ctx;
    AVFilterContext *buffersrc_ctx;
    AVFilterGraph *filter_graph;

    AVPacket *enc_packet;

    AVFrame *audio_frame;
    AVFrame *filtered_frame;

    Queue *packets;

    AVIOInterruptCB interrupt_callback;

    int input_stream_numbers[MAX_STREAMS];
    int64_t next_dts[MAX_STREAMS];
    int64_t current_recording_time[MAX_STREAMS];

    int capture_streams_no;
    int video_stream_no;
    int audio_stream_no;

    int eof;
    int mux_error;

    int64_t last_updated_time;
    int64_t pts;

    pthread_t transcode_thread;

    pthread_mutex_t mutex_operation;
    pthread_mutex_t mutex_queue;
    pthread_cond_t cond_queue;

    int transcode_thread_created;

    /* [BUG FIX] interrupt was a plain int, like in the original downloader
     * before it was fixed. The interrupt callback runs on the FFmpeg
     * read/seek worker, the JNI interrupt/stop calls write from the Java
     * thread, and the transcode thread reads it inside its decode/encode
     * inner loops. Without volatile, the compiler is free to hoist the
     * read out of those loops. */
    volatile int interrupt;
    int stop;
    int stop_stream;

    JavaVM *get_javavm;
    jobject thiz;
    jmethodID encoder_on_progress_update_method;
    jmethodID encoder_on_download_started_method;
    jmethodID encoder_on_download_finished_method;
};


struct State {
    struct Encoder *encoder;
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


enum EncoderErrors {
    ERROR_NO_ERROR = 0,
    ERROR_ENOENT,
    ERROR_COULD_NOT_ATTACH_THREAD,
    ERROR_COULD_NOT_GET_JAVA_VM,
    ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAMS,
    ERROR_COULD_NOT_INIT_PATHS,
    ERROR_COULD_NOT_CREATE_AVCONTEXT,
    ERROR_COULD_NOT_ALLOC_FRAME,
    ERROR_COULD_NOT_ALLOC_PACKET,
    ERROR_COULD_NOT_FIND_AAC,
    ERROR_COULD_NOT_CREATE_AAC_CONTEXT,
    ERROR_COULD_NOT_OPEN_CODEC,
    ERROR_WHILE_DECODING_AUDIO_FRAME,
    ERROR_WHILE_ENCODING_AUDIO_FRAME,
    ERROR_COULD_NOT_FIND_STREAMS,
    ERROR_COULD_NOT_CREATE_OUTPUT_FORMAT,
    ERROR_COULD_NOT_OPEN_VIDEO_FILE,
    ERROR_COULD_NOT_WRITE_HEADER,
    ERROR_COULD_NOT_PREPARE_PACKETS_QUEUE,
    ERROR_COULD_NOT_DETACH_THREAD,
    ERROR_COULD_NOT_JOIN_PTHREAD,
    ERROR_COULD_NOT_INIT_PTHREAD_ATTR,
    ERROR_COULD_NOT_CREATE_PTHREAD,
    ERROR_COULD_NOT_DESTROY_PTHREAD_ATTR,
    ERROR_NOT_FOUND_ENCODER_CLASS,
    ERROR_NOT_FOUND_M_NATIVE_ENCODER_STARTED_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_ENCODER_FINISHED_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_ENCODER_PROGRESS_METHOD,
    ERROR_NOT_FOUND_M_NATIVE_ENCODER_FIELD,
    ERROR_COULD_NOT_ALLOCATE_ENCODER,
    ERROR_COULD_NOT_CREATE_GLOBAL_REF,
    ERROR_COULD_NOT_OPEN_STREAM,
    ERROR_COULD_NOT_READ
};

void *encoder_fill_packet(struct State *state) {
    struct PacketData *packet_data = av_mallocz(sizeof(struct PacketData));
    if (packet_data == NULL) {
        return NULL;
    }
    packet_data->packet = av_packet_alloc();
    if (packet_data->packet == NULL) {
        free(packet_data);
        return NULL;
    }
    return packet_data;

}

void encoder_free_packet(struct State *state, struct PacketData *elem) {
    av_packet_free(&elem->packet);
    free(elem);
}


void encoder_init_filters_free(struct Encoder *encoder){
    if(encoder->filter_graph != NULL){
        avfilter_graph_free(&encoder->filter_graph);
        encoder->filter_graph = NULL;
    }
}

void encoder_alloc_queues_free(struct State *state) {
    struct Encoder *encoder = state->encoder;
    if (encoder->packets != NULL) {
        queue_free(encoder->packets, &encoder->mutex_queue,
                   &encoder->cond_queue, state);
        encoder->packets = NULL;
    }
}


int encoder_alloc_queues(struct State *state) {

    struct Encoder *encoder = state->encoder;
    int error = ERROR_NO_ERROR;
    encoder->packets = queue_init_with_custom_lock(BUFFER_SIZE,
                                                   (queue_fill_func)
                                                           encoder_fill_packet,
                                                   (queue_free_func)
                                                           encoder_free_packet,
                                                   state, state,
                                                   &encoder->mutex_queue,
                                                   &encoder->cond_queue);
    if (encoder->packets == NULL) {
        error = -ERROR_COULD_NOT_PREPARE_PACKETS_QUEUE;
    }
    return error;

}


void encoder_prepare(struct Encoder *encoder) {
    pthread_mutex_lock(&encoder->mutex_queue);
    encoder->stop = FALSE;
    pthread_cond_broadcast(&encoder->cond_queue);
    pthread_mutex_unlock(&encoder->mutex_queue);
}

void encoder_prepare_free(struct Encoder *encoder) {
    pthread_mutex_lock(&encoder->mutex_queue);
    encoder->stop = TRUE;
    pthread_cond_broadcast(&encoder->cond_queue);
    pthread_mutex_unlock(&encoder->mutex_queue);
}


QueueCheckFuncRet encoder_read_from_stream_check_func(Queue *queue,
                                                      struct Encoder *encoder,
                                                      int *ret) {

    if (queue == NULL) {
        *ret = READ_FROM_STREAM_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }
    if (encoder->mux_error) {
        *ret = READ_FROM_STREAM_CHECK_MSG_MUX_ERROR;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }
    if (encoder->stop) {
        *ret = READ_FROM_STREAM_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }

    return QUEUE_CHECK_FUNC_RET_TEST;
}


QueueCheckFuncRet encoder_transcode_queue_check_func(Queue *queue,
                                                     struct Encoder *encoder, int *ret) {

    if (queue == NULL) {
        *ret = MUXER_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }

    if (encoder->stop_stream) {
        *ret = MUXER_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }

    if (encoder->stop) {
        *ret = MUXER_CHECK_MSG_STOP;
        return QUEUE_CHECK_FUNC_RET_SKIP;
    }


    return QUEUE_CHECK_FUNC_RET_TEST;
}


void encoder_find_stream_info_free(struct Encoder *encoder) {


}

void encoder_create_context_free(struct Encoder *encoder) {

    LOGI (7, "encoder_create_context_free remove_output_context");
    if (encoder->output_format_ctx != NULL) {
        if ((encoder->output_format_ctx->pb != NULL) &&
            !(encoder->output_format_ctx->oformat->flags & AVFMT_NOFILE))
            avio_closep(&encoder->output_format_ctx->pb);
        avformat_free_context(encoder->output_format_ctx);
        encoder->output_format_ctx = NULL;
    }
}


int encoder_create_encoder_context(struct Encoder *encoder) {

    int err = ERROR_NO_ERROR;
    int ret;
    const AVCodec *output_codec = avcodec_find_encoder(AV_CODEC_ID_AAC);

    if (output_codec == NULL) {
        LOGE (1, "Could not find AAC Codec\n");
        err = -ERROR_COULD_NOT_FIND_AAC;
        goto end;
    }

    encoder->output_codec_ctx = avcodec_alloc_context3(output_codec);

    if (encoder->output_codec_ctx == NULL) {
        LOGE (1, "Could not find create AAC format context\n");
        err = -ERROR_COULD_NOT_CREATE_AAC_CONTEXT;
        goto end;
    }


    encoder->output_codec_ctx->sample_rate = encoder->input_codec_ctx->sample_rate;

    /* [BUG FIX] Was: encoder->output_codec_ctx->sample_fmt = output_codec->sample_fmts[0];
     * AVCodec::sample_fmts is deprecated as of FFmpeg 7.1 and will be removed.
     * The helper handles both new and old FFmpeg, and returns AV_SAMPLE_FMT_NONE
     * if the codec advertises no usable format — which we now treat as an
     * error rather than blindly trusting the first entry. */
    encoder->output_codec_ctx->sample_fmt = utils_select_sample_fmt(output_codec);
    if (encoder->output_codec_ctx->sample_fmt == AV_SAMPLE_FMT_NONE) {
        LOGE(1, "AAC encoder advertises no sample format\n");
        err = -ERROR_COULD_NOT_OPEN_CODEC;
        goto end;
    }

    encoder->output_codec_ctx->time_base = (AVRational){1, encoder->output_codec_ctx->sample_rate};

    utils_select_channel_layout(output_codec, &encoder->output_codec_ctx->ch_layout);

    ret = avcodec_open2(encoder->output_codec_ctx, output_codec, NULL);

    if (ret < 0) {
        LOGE(1, "Could not open output codec (error '%s')",av_err2str(ret));
        err = -ERROR_COULD_NOT_OPEN_CODEC;
        goto end;
    }

    end:

    return err;
}

int encoder_create_context(struct Encoder *encoder,
                           const char *file_output_path, AVDictionary *metadata) {

    if (avformat_alloc_output_context2(&encoder->output_format_ctx, NULL, NULL, file_output_path) <
        0) {
        LOGE (1, "Could not create Output format AVContext\n");
        return -ERROR_COULD_NOT_CREATE_AVCONTEXT;
    }

    encoder->output_format_ctx->metadata = metadata;

    return ERROR_NO_ERROR;
}


int encoder_create_output_format(struct Encoder *encoder,
                                 const char *file_output_path) {

    const AVOutputFormat *fmt;

    LOGI (2, "encoder_create_output_format %s", file_output_path);

    if ((fmt = av_guess_format(NULL, file_output_path, NULL)) == NULL) {
        LOGE (2, "Could not guess AVOutputFormat %s\n", file_output_path);
        return -ERROR_COULD_NOT_CREATE_OUTPUT_FORMAT;
    }

    encoder->output_format_ctx->oformat = fmt;

    return ERROR_NO_ERROR;
}


int encoder_init_filters(struct Encoder *encoder){

    char args[512];
    char filter_desc[512];
    int ret = 0;
    const AVFilter *buffersrc = NULL;
    const AVFilter *buffersink = NULL;
    AVFilterContext *buffersrc_ctx = NULL;
    AVFilterContext *buffersink_ctx = NULL;
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs = avfilter_inout_alloc();
    AVFilterGraph *filter_graph = avfilter_graph_alloc();
    AVCodecContext *dec_ctx = encoder->input_codec_ctx;
    AVCodecContext *enc_ctx = encoder->output_codec_ctx;


    if (!outputs || !inputs || !filter_graph) {
        ret = AVERROR(ENOMEM);
        goto end;
    }

    if (dec_ctx->codec_type == AVMEDIA_TYPE_AUDIO) {
        buffersrc = avfilter_get_by_name("abuffer");
        buffersink = avfilter_get_by_name("abuffersink");
        if (!buffersrc || !buffersink) {
            LOGE( 1, "filtering source or sink element not found\n");
            ret = AVERROR_UNKNOWN;
            goto end;
        }

        if (dec_ctx->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC)
            av_channel_layout_default(&dec_ctx->ch_layout, dec_ctx->ch_layout.nb_channels);

        ret = snprintf(args, sizeof(args),
                       "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=",
                       dec_ctx->time_base.num, dec_ctx->time_base.den, dec_ctx->sample_rate,
                       av_get_sample_fmt_name(dec_ctx->sample_fmt));

        av_channel_layout_describe(&dec_ctx->ch_layout, args + ret, sizeof(args) - ret);

        ret = avfilter_graph_create_filter(&buffersrc_ctx, buffersrc, "in",
                                           args, NULL, filter_graph);
        if (ret < 0) {
            LOGE( 1, "Cannot create audio buffer source\n");
            goto end;
        }

        ret = avfilter_graph_create_filter(&buffersink_ctx, buffersink, "out",
                                           NULL, NULL, filter_graph);
        if (ret < 0) {
            LOGE( 1, "Cannot create audio buffer sink\n");
            goto end;
        }

    } else {
        ret = AVERROR_UNKNOWN;
        goto end;
    }

    /* Endpoints for the filter graph. */
    outputs->name = av_strdup("in");
    outputs->filter_ctx = buffersrc_ctx;
    outputs->pad_idx = 0;
    outputs->next = NULL;

    inputs->name = av_strdup("out");
    inputs->filter_ctx = buffersink_ctx;
    inputs->pad_idx = 0;
    inputs->next = NULL;

    if (!outputs->name || !inputs->name) {
        ret = AVERROR(ENOMEM);
        goto end;
    }

    /* Use aformat filter to enforce encoder's sample format, sample rate,
     * and channel layout — this works across all FFmpeg versions without
     * needing to set buffersink options directly. */
    snprintf(filter_desc, sizeof(filter_desc),
             "aformat=sample_fmts=%s:sample_rates=%d:channel_layouts=stereo,"
             "asetnsamples=n=%d:p=0",
             av_get_sample_fmt_name(enc_ctx->sample_fmt),
             enc_ctx->sample_rate,
             enc_ctx->frame_size);

    LOGI(1, "encoder_init_filters filter_desc: %s", filter_desc);

    if ((ret = avfilter_graph_parse_ptr(filter_graph, filter_desc,
                                        &inputs, &outputs, NULL)) < 0)
        goto end;

    if ((ret = avfilter_graph_config(filter_graph, NULL)) < 0)
        goto end;

    /* Fill FilteringContext */
    encoder->buffersrc_ctx = buffersrc_ctx;
    encoder->buffersink_ctx = buffersink_ctx;
    encoder->filter_graph = filter_graph;

    end:
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);

    return ret;

}

int encoder_init_output_streams(struct Encoder *encoder) {

    AVFormatContext *output_format_ctx = encoder->output_format_ctx;
    AVCodecContext *output_codec_ctx = encoder->output_codec_ctx;
    AVStream *ost;

    int ret = ERROR_NO_ERROR;

    ost = avformat_new_stream(output_format_ctx, NULL);

    if (!ost) {
        LOGE(1, "encoder_init_output_streams failed allocating output stream")
        ret = -ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAMS;
        goto end;
    }

    ost->time_base = output_codec_ctx->time_base;

    ret = avcodec_parameters_from_context(ost->codecpar, output_codec_ctx);

    if (ret < 0) {
        LOGE(1, "encoder_init_output_streams failed to copy codec parameters");
        ret = -ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAMS;
        goto end;
    }

#if LOG_LEVEL > 1
    av_dump_format(output_format_ctx, 0, output_format_ctx->url, 1);
#endif

    end:

    return ret;
}

int encoder_find_streams(struct Encoder *encoder,
                         enum AVMediaType codec_type, int recommended_stream_no) {

    AVStream *stream;
    const AVCodec *dec = NULL;
    AVCodecContext *dec_ctx = NULL;
    AVDictionary *opts = NULL;
    int streams_no = encoder->capture_streams_no;
    int related_stream = -1;
    int bn_stream;
    int ret;
    int found = STREAM_UNKNOWN;

    LOGI(3, "encoder_find_stream, type: %d, recommended stream: %d", codec_type,
         recommended_stream_no);

    if (codec_type == AVMEDIA_TYPE_AUDIO && encoder->video_stream_no >= 0) {
        related_stream = encoder->input_stream_numbers[encoder->video_stream_no];
    }

    bn_stream = av_find_best_stream(encoder->input_format_ctx, codec_type, recommended_stream_no,
                                    related_stream, NULL, 0);

    if (bn_stream < 0) {
        LOGE(1, "encoder_find_stream, bn_stream < 0 trying with unknown");
        bn_stream = av_find_best_stream(encoder->input_format_ctx, codec_type, -1, -1, NULL, 0);
        if (bn_stream < 0) {
            LOGE(1, "encoder_find_stream, bn_stream < 0");
            goto error;
        }
    }

    stream = encoder->input_format_ctx->streams[bn_stream];
    stream->discard = AVDISCARD_DEFAULT;

    dec_ctx = avcodec_alloc_context3(NULL);

    if (!dec_ctx) {
        LOGE(1, "encoder_find_stream error avcodec_alloc_context3");
        goto error;
    }

    ret = avcodec_parameters_to_context(dec_ctx, stream->codecpar);

    if (ret < 0) {
        LOGE(1, "encoder_find_stream error avcodec_parameters_to_context");
        goto error; /* [BUG FIX] was: return STREAM_UNKNOWN — leaked dec_ctx */
    }

    dec_ctx->pkt_timebase = stream->time_base;

    av_opt_set_int(dec_ctx, "refcounted_frames", 0, 0);
    av_opt_set(dec_ctx, "threads", "auto", 0);

    dec = avcodec_find_decoder(dec_ctx->codec_id);

    if (!dec) {
        LOGE(1, "encoder_find_stream error finding codec");
        goto error; /* [BUG FIX] was: return STREAM_UNKNOWN — leaked dec_ctx */
    }

    if (avcodec_open2(dec_ctx, dec, &opts) < 0) {
        LOGE(1, "encoder_find_stream error opening codec");
        goto error; /* [BUG FIX] was: return STREAM_UNKNOWN — leaked dec_ctx and opts */
    }

    encoder->input_stream = stream;
    encoder->input_codec_ctx = dec_ctx;
    encoder->input_stream_numbers[streams_no] = bn_stream;
    encoder->capture_streams_no += 1;
    found = streams_no;
    dec_ctx = NULL; /* ownership transferred to encoder */

    error:
    if (opts != NULL)
        av_dict_free(&opts);
    if (dec_ctx != NULL)
        avcodec_free_context(&dec_ctx);
    return found;
}

void encoder_open_stream_free(struct Encoder *encoder, int stream_no) {

    AVCodecContext **ctx = &encoder->input_codec_ctx;

    if (*ctx != NULL) {
        avcodec_free_context(ctx);
        *ctx = NULL;
    }
}

void encoder_find_streams_free(struct Encoder *encoder) {
    int capture_streams_no = encoder->capture_streams_no;
    int i;
    for (i = 0; i < capture_streams_no; ++i) {
        encoder_open_stream_free(encoder, i);
    }
    encoder->capture_streams_no = 0;
    encoder->video_stream_no = -1;
    encoder->audio_stream_no = -1;
}

int encoder_open_output(struct Encoder *encoder,
                        const char *file_output_path) {

    char errbuf[128];
    const char *errbuf_ptr = errbuf;
    int ret;

    if ((ret = avio_open2(&encoder->output_format_ctx->pb, file_output_path,
                          AVIO_FLAG_WRITE, NULL, NULL)) < 0) {


        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            errbuf_ptr = strerror(AVUNERROR(ret));

        LOGE (1,
              "encoder_open_output Could not open output file: %s (%d: %s)\n",
              file_output_path, ret, errbuf_ptr);

        return ret;
    }

    return 0;
}


void encoder_create_encoder_context_free(struct Encoder *encoder) {

    LOGI (7, "encoder_create_encoder_context_free");

    if (encoder->output_codec_ctx != NULL) {
        avcodec_free_context(&encoder->output_codec_ctx);
        encoder->output_codec_ctx = NULL;
    }
}

void encoder_open_input_free(struct Encoder *encoder) {

    LOGI (7, "encoder_open_input_free close_file");

    if (encoder->input_format_ctx != NULL) {
        avformat_close_input(&encoder->input_format_ctx);
        encoder->input_format_ctx = NULL;
    }

}

void encoder_alloc_frames_free(struct Encoder *encoder) {

    LOGI (7, "encoder_alloc_frames_free");

    if (encoder->audio_frame != NULL) {
        av_frame_free(&encoder->audio_frame);
    }

    if (encoder->filtered_frame != NULL) {
        av_frame_free(&encoder->filtered_frame);
    }

    if(encoder->enc_packet != NULL){
        av_packet_free(&encoder->enc_packet);
        encoder->enc_packet = NULL;
    }

}


int encoder_open_input(struct Encoder *encoder,
                       AVDictionary **dictionary, const char *file_path) {

    int ret;
    int err = ERROR_NO_ERROR;
    char errbuf[128];
    const char *errbuf_ptr = errbuf;

    /* [BUG FIX] dictionary was previously taken by value, so when this
     * function called av_dict_free(&dictionary) at the end the caller's
     * slot still pointed at the freed memory. Take the dict by pointer
     * so we can null-out the caller's slot too. */

    utils_set_dict_options(dictionary);

    LOGI (3, "encoder_open_input video file: %s \n", file_path);

    if ((ret = avformat_open_input(&encoder->input_format_ctx, file_path, NULL, dictionary)) < 0) {

        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            errbuf_ptr = strerror(AVUNERROR(ret));

        LOGE (1, "encoder_open_input Could not open video file: %s (%d: %s)\n",
              file_path, ret, errbuf_ptr);

        err = -ERROR_COULD_NOT_OPEN_VIDEO_FILE;
    }

    /* avformat_open_input may have consumed entries from *dictionary
     * leaving residue we must free; it does NOT null-out the slot. */
    av_dict_free(dictionary);

    return err;
}


int encoder_threads_free(struct Encoder *encoder) {

    int ret;
    int err = ERROR_NO_ERROR;

    LOGI (1, "encoder_threads_free start_thread mux_thread %d", encoder->transcode_thread_created);

    if (encoder->transcode_thread_created) {
        LOGI (1, "encoder_threads_free mux_thread");
        ret = pthread_join(encoder->transcode_thread, NULL);
        encoder->transcode_thread_created = FALSE;
        LOGI (1, "encoder_threads_free mux_thread finished");
        if (ret) {
            err = ERROR_COULD_NOT_JOIN_PTHREAD;
        }
    }

    return err;

}


void encoder_stop_without_lock(struct State *state) {

    struct Encoder *encoder = state->encoder;
    LOGI (1, "encoder_stop_without_lock prepare_free");
    encoder_prepare_free(encoder);
    LOGI (1, "encoder_stop_without_lock threads_free");
    encoder_threads_free(encoder);
    LOGI (1, "encoder_stop_without_lock alloc_queues_free");
    encoder_alloc_queues_free(state);
    LOGI (1, "encoder_init_filters_free");
    encoder_init_filters_free(encoder);
    LOGI (1, "encoder_stop_without_lock find_streams_free");
    encoder_find_streams_free(encoder);
    LOGI (1, "encoder_stop_without_lock find_stream_info_free");
    encoder_find_stream_info_free(encoder);
    LOGI (1, "encoder_alloc_frames_free");
    encoder_alloc_frames_free(encoder);
    LOGI (1, "encoder_create_encoder_context_free");
    encoder_create_encoder_context_free(encoder);
    LOGI (1, "encoder_stop_without_lock input_free");
    encoder_open_input_free(encoder);
    LOGI (1, "encoder_stop_without_lock context_free");
    encoder_create_context_free(encoder);
    LOGI (1, "encoder_stop_without_lock end");

}


static int encoder_ctx_interrupt_callback(void *p) {
    int ret = 0;
    struct Encoder *encoder = (struct Encoder *) p;
    LOGI (10, "encoder_ctx_interrupt_callback");
    if (encoder->interrupt) {
        ret = 1;
    }
    return ret;
}


int encoder_create_interrupt_callback(struct Encoder *encoder) {
    LOGI (3, "encoder_ctx_interrupt_setup");
    encoder->interrupt = FALSE;
    encoder->interrupt_callback = (AVIOInterruptCB) {encoder_ctx_interrupt_callback, encoder};
    encoder->input_format_ctx->interrupt_callback = encoder->interrupt_callback;
    encoder->input_format_ctx->flags |= AVFMT_FLAG_NONBLOCK;
    return 0;
}


int jni_encoder_init(JNIEnv *env, jobject thiz) {

    jclass encoder_runnable_class = NULL;
    struct Encoder *encoder = NULL;
    jfieldID m_native_encoder_field = NULL;
    int err = ERROR_NO_ERROR;
    int ret = 0;
    int mutex_queue_inited = FALSE;
    int mutex_operation_inited = FALSE;
    int cond_queue_inited = FALSE;

    encoder = calloc(1, sizeof(struct Encoder));

    if (encoder == NULL) {
        err = -ERROR_COULD_NOT_ALLOCATE_ENCODER;
        goto end;
    }

    ret = (*env)->GetJavaVM(env, &encoder->get_javavm);

    if (ret) {
        err = -ERROR_COULD_NOT_GET_JAVA_VM;
        goto free_encoder;
    }

    encoder->thiz = (*env)->NewGlobalRef(env, thiz);

    if (encoder->thiz == NULL) {
        err = -ERROR_COULD_NOT_CREATE_GLOBAL_REF;
        goto free_encoder;
    }

    m_native_encoder_field = java_get_field(env,
                                            encoder_runnable_class_path_name,
                                            encoder_m_native);
    if (m_native_encoder_field == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_ENCODER_FIELD;
        goto free_encoder;
    }

    /* [BUG FIX] The original called SetLongField HERE, before the method
     * lookups below could fail. On failure, free_encoder would call
     * free(encoder) but the Java field still held a dangling pointer; a
     * subsequent JNI call from Java would dereference freed memory.
     * The publish is now deferred to the end, after all fallible setup. */

    encoder_runnable_class =
            (*env)->FindClass(env, encoder_runnable_class_path_name);

    if (encoder_runnable_class == NULL) {
        err = -ERROR_NOT_FOUND_ENCODER_CLASS;
        LOGE (1, "Could not find encoder_runnable_class\n");
        goto free_encoder;
    }

    encoder->encoder_on_progress_update_method =
            java_get_method(env, encoder_runnable_class, encoderProgress);

    if (encoder->encoder_on_progress_update_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_ENCODER_PROGRESS_METHOD;
        LOGE (1, "Could not find method encoderProgress\n");
        goto free_encoder;
    }

    encoder->encoder_on_download_started_method =
            java_get_method(env, encoder_runnable_class, encoderStarted);

    if (encoder->encoder_on_download_started_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_ENCODER_STARTED_METHOD;
        LOGE (1, "Could not find method encoderStarted\n");
        goto free_encoder;
    }

    encoder->encoder_on_download_finished_method =
            java_get_method(env, encoder_runnable_class, encoderFinished);

    if (encoder->encoder_on_download_finished_method == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_ENCODER_FINISHED_METHOD;
        LOGE (1, "Could not find method encoderFinished\n");
        goto free_encoder;
    }

    (*env)->DeleteLocalRef(env, encoder_runnable_class);
    encoder_runnable_class = NULL;

    if (pthread_mutex_init(&encoder->mutex_queue, NULL) != 0) {
        err = -ERROR_COULD_NOT_INIT_PTHREAD_ATTR;
        goto free_encoder;
    }
    mutex_queue_inited = TRUE;

    if (pthread_mutex_init(&encoder->mutex_operation, NULL) != 0) {
        err = -ERROR_COULD_NOT_INIT_PTHREAD_ATTR;
        goto free_encoder;
    }
    mutex_operation_inited = TRUE;

    if (pthread_cond_init(&encoder->cond_queue, NULL) != 0) {
        err = -ERROR_COULD_NOT_INIT_PTHREAD_ATTR;
        goto free_encoder;
    }
    cond_queue_inited = TRUE;

    encoder->stop = FALSE;

    av_log_set_callback(utils_ffmpeg_log_callback);
    av_log_set_level(FFMPEG_LOG_LEVEL);

    /* All fallible setup has succeeded — publish to Java. From this point
     * on, the Java side may call any of our JNI entry points and they will
     * find a fully-initialised Encoder. */
    (*env)->SetLongField(env, thiz, m_native_encoder_field, (jlong) encoder);

    goto end;

    free_encoder:

    if (cond_queue_inited)
        pthread_cond_destroy(&encoder->cond_queue);
    if (mutex_operation_inited)
        pthread_mutex_destroy(&encoder->mutex_operation);
    if (mutex_queue_inited)
        pthread_mutex_destroy(&encoder->mutex_queue);

    if (encoder->thiz != NULL) {
        (*env)->DeleteGlobalRef(env, encoder->thiz);
        encoder->thiz = NULL;
    }

    if (encoder_runnable_class != NULL) {
        (*env)->DeleteLocalRef(env, encoder_runnable_class);
    }

    free(encoder);

    end:

    return err;
}


struct Encoder *get_encoder_field(JNIEnv *env, jobject thiz) {

    jfieldID m_native_encoder_field = java_get_field(env, encoder_runnable_class_path_name,
                                                     encoder_m_native);

    jlong pointer = (*env)->GetLongField(env, thiz, m_native_encoder_field);

    return (struct Encoder *) pointer;
}


void clean_encoder_field(JNIEnv *env, jobject thiz) {

    jfieldID m_native_encoder_field = java_get_field(env, encoder_runnable_class_path_name,
                                                     encoder_m_native);

    (*env)->SetLongField(env, thiz, m_native_encoder_field, 0);

}


int encoder_write_frame(struct Encoder *encoder, AVFrame *filtered_frame){

    int ret;
    AVPacket *enc_pkt = encoder->enc_packet;

    LOGI(6, "Encoding frame\n");

    av_packet_unref(enc_pkt);

    ret = avcodec_send_frame(encoder->output_codec_ctx, filtered_frame);

    if (ret < 0)
        return ret;

    while (ret >= 0) {

        ret = avcodec_receive_packet(encoder->output_codec_ctx, enc_pkt);


        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
            return 0;

        /* prepare packet for muxing */
        enc_pkt->stream_index = 0;
        av_packet_rescale_ts(enc_pkt,
                             encoder->output_codec_ctx->time_base,
                             encoder->output_format_ctx->streams[0]->time_base);

        LOGI(6, "Muxing frame\n");
        /* mux encoded frame */
        ret = av_interleaved_write_frame(encoder->output_format_ctx, enc_pkt);
    }

    return ret;

}

int encoder_filter_audio(struct Encoder *encoder, AVFrame *audio_frame) {

    int ret;


    ret = av_buffersrc_add_frame_flags(encoder->buffersrc_ctx,
                                       audio_frame, 0);
    if (ret < 0) {
        LOGE(1, "Error while feeding the filtergraph\n");
        return ret;
    }


    while (1) {
        LOGI(6, "Pulling filtered frame from filters\n");
        ret = av_buffersink_get_frame(encoder->buffersink_ctx,
                                      encoder->filtered_frame);
        if (ret < 0) {
            /* if no more frames for output - returns AVERROR(EAGAIN)
             * if flushed and no more frames for output - returns AVERROR_EOF
             * rewrite retcode to 0 to show it as normal procedure completion
             */
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
                ret = 0;
            break;
        }

        if(encoder->interrupt){
            LOGE(1, "Error encoder_filter_audio exit requested\n");
            ret = -ERROR_ENOENT;
            return ret;
        }

        encoder->filtered_frame->pict_type = AV_PICTURE_TYPE_NONE;
        ret = encoder_write_frame(encoder, encoder->filtered_frame);
        av_frame_unref(encoder->filtered_frame);
        if (ret < 0)
            break;
    }


    return ret;

}

void *encoder_transcode(void *data) {

    int err = ERROR_NO_ERROR;
    struct Encoder *encoder = data;
    struct PacketData *packet_data;
    struct PacketData *to_free;
    enum AVMediaType codec_type;
    Queue *queue = encoder->packets;
    AVCodecContext *input_codec_ctx;
    AVFrame *audio_frame = encoder->audio_frame;
    AVStream *input_stream;
    AVStream *output_stream;
    AVPacket *pkt;
    JNIEnv *env;
    int stream_no;
    int interrupt_ret;
    int64_t current_time;
    int64_t time_diff;
    int64_t recording_time;
    int pkt_consumed = 0;
    char thread_title[256];
    sprintf(thread_title, "encoder_transcode");


    JavaVMAttachArgs thread_spec = {JNI_VERSION_1_6, thread_title, NULL};

    jint ret = (*encoder->get_javavm)->AttachCurrentThread(encoder->get_javavm,
                                                           &env, &thread_spec);

    if (ret || env == NULL) {
        err = -ERROR_COULD_NOT_ATTACH_THREAD;
        goto end;
    }

    pthread_mutex_lock(&encoder->mutex_queue);
    encoder->stop_stream = FALSE;
    pthread_cond_broadcast(&encoder->cond_queue);
    pthread_mutex_unlock(&encoder->mutex_queue);

    recording_time = encoder->input_format_ctx->duration;

    if (avformat_write_header(encoder->output_format_ctx, NULL) < 0) {
        LOGE (2, "encoder_transcode write header error");
        err = -ERROR_COULD_NOT_WRITE_HEADER;
        encoder->mux_error = err;
        goto detach_current_thread;
    }


    for (;;) {

        pthread_mutex_lock(&encoder->mutex_queue);


        packet_data = queue_pop_start_already_locked(&queue,
                                                     &encoder->mutex_queue,
                                                     &encoder->cond_queue,
                                                     (QueueCheckFunc) encoder_transcode_queue_check_func,
                                                     encoder,
                                                     (void **) &interrupt_ret);

        if (packet_data == NULL) {
            if (interrupt_ret == MUXER_CHECK_MSG_STOP) {
                goto stop;
            } else {
                av_assert0(0);
            }
        }

        pthread_mutex_unlock(&encoder->mutex_queue);


        if (packet_data->end_of_stream || encoder->interrupt) {
            LOGI (1, "encoder_transcode end_of_stream");
            queue_pop_finish(queue, &encoder->mutex_queue, &encoder->cond_queue);
            pthread_mutex_lock(&encoder->mutex_queue);
            goto stop;
        }


        pkt = packet_data->packet;

        for (stream_no = 0; stream_no < encoder->capture_streams_no; ++stream_no) {
            LOGI (5, "encoder_transcode looking for stream %d", stream_no)
            if (pkt->stream_index == encoder->input_stream_numbers[stream_no]) {
                LOGI (6, "encoder_transcode stream found [%d]", stream_no);
                break;
            }
        }

        if (stream_no >= encoder->capture_streams_no) {
            LOGW(2, "encoder_transcode stream not found for index %d", pkt->stream_index);
            av_packet_unref(pkt);
            queue_pop_finish(queue, &encoder->mutex_queue, &encoder->cond_queue);
            goto end_loop;
        }

        input_stream = encoder->input_stream;
        output_stream = encoder->output_format_ctx->streams[stream_no];
        codec_type = encoder->input_codec_ctx->codec_type;

        LOGW(6, "encoder_transcode %s input_stream->time_base %f output_stream->time_base %f",
             codec_type == AVMEDIA_TYPE_AUDIO ? "AUDIO" : "VIDEO", av_q2d(input_stream->time_base),
             av_q2d(output_stream->time_base));

        if (codec_type != AVMEDIA_TYPE_AUDIO) {
            LOGW(2, "encoder_transcode incorrect codec_type");
            goto end_loop;
        }

        if (input_stream->codecpar->sample_rate) {
            encoder->next_dts[stream_no] +=
                    ((int64_t) AV_TIME_BASE * input_stream->codecpar->frame_size) /
                    input_stream->codecpar->sample_rate;
        } else {
            encoder->next_dts[stream_no] += av_rescale_q(pkt->duration,
                                                         input_stream->time_base,
                                                         AV_TIME_BASE_Q);
        }

        LOGI (6, "encoder_transcode AUDIO start_time %"PRId64, input_stream->start_time);


        encoder->current_recording_time[stream_no] += av_rescale_q(pkt->duration,
                                                                   input_stream->time_base,
                                                                   AV_TIME_BASE_Q);

        LOGI (6, "encoder_transcode AUDIO recording_time %" PRId64 " current_recording_time %" PRId64, recording_time,
              encoder->current_recording_time[stream_no]);

        //Do decoding

        input_codec_ctx = encoder->input_codec_ctx;

        while (!pkt_consumed) {

            ret = avcodec_send_packet(input_codec_ctx, pkt);

            if (ret == AVERROR(EAGAIN) || ret == AVERROR(EOF)) {
                ret = 0;
            }else if (ret < 0) {
                LOGE(1, "player_decode_audio avcodec_send_packet %d\n", ret);
                err = -ERROR_WHILE_DECODING_AUDIO_FRAME;
                goto error;
            } else {
                pkt_consumed = 1;
            }

            while (ret >= 0) {

                ret = avcodec_receive_frame(input_codec_ctx, audio_frame);

                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    goto skip_frame;
                } else if (ret < 0) {
                    LOGE(1, "player_decode_audio avcodec_receive_frame %d\n", ret);
                    err = -ERROR_WHILE_DECODING_AUDIO_FRAME;
                    goto error;
                } else if (encoder->interrupt) {
                    LOGE(1, "player_decode_audio avcodec_receive_frame %d\n", ret);
                    err = -ERROR_ENOENT;
                    goto error;
                } else {
                    ret = encoder_filter_audio(encoder, audio_frame);
                    if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
                        LOGE(1, "player_decode_audio parse_audio_frame %d\n", ret);
                        err = -ERROR_WHILE_ENCODING_AUDIO_FRAME;
                        goto error;
                    }
                }

            }
        }

        skip_frame:

        pkt_consumed = 0;

        av_packet_unref(pkt);

        queue_pop_finish(queue, &encoder->mutex_queue, &encoder->cond_queue);

        current_time = av_gettime_relative();

        time_diff = encoder->last_updated_time - current_time;

        if (time_diff > UPDATE_TIME_US || time_diff < -UPDATE_TIME_US) {
            encoder->last_updated_time = current_time;
            (*env)->CallVoidMethod(env, encoder->thiz, encoder->encoder_on_progress_update_method,
                                   encoder->current_recording_time[stream_no], recording_time);
        }

        goto end_loop;

        error:

        queue_pop_finish(queue, &encoder->mutex_queue, &encoder->cond_queue);

        pthread_mutex_lock(&encoder->mutex_queue);

        stop:

        LOGI (3, "encoder_transcode stop");

        encoder_filter_audio(encoder, NULL);

        encoder_write_frame(encoder ,NULL);

        while ((to_free = queue_pop_start_already_locked_non_block(queue)) != NULL) {
            if (!to_free->end_of_stream) {
                av_packet_unref(to_free->packet);
            }
            queue_pop_finish_already_locked(queue, &encoder->mutex_queue,
                                            &encoder->cond_queue);
        }
        encoder->stop_stream = TRUE;
        pthread_cond_broadcast(&encoder->cond_queue);
        pthread_mutex_unlock(&encoder->mutex_queue);

        goto detach_current_thread;


        end_loop:
        continue;


    }


    detach_current_thread:


    if (!err) {
        av_write_trailer(encoder->output_format_ctx);
    }

    if (encoder->interrupt == FALSE && encoder->thiz != NULL) {
        (*env)->CallVoidMethod(env, encoder->thiz,
                               encoder->encoder_on_progress_update_method, recording_time, recording_time);
        (*env)->CallVoidMethod(env, encoder->thiz,
                               encoder->encoder_on_download_finished_method);
    }

    LOGI (3, "encoder_transcode finish");

    ret = (*encoder->get_javavm)->DetachCurrentThread(encoder->get_javavm);

    if (ret && !err) {
        err = ERROR_COULD_NOT_DETACH_THREAD;
        LOGE (4, "encoder_transcode error %d", err);
    }

    pthread_mutex_lock(&encoder->mutex_queue);
    encoder->transcode_thread_created = FALSE;
    pthread_cond_broadcast(&encoder->cond_queue);
    pthread_mutex_unlock(&encoder->mutex_queue);

    end:
    return NULL;


}


enum ReadFromStreamCheckMsg encoder_push_end_of_stream(struct Encoder *encoder) {

    struct PacketData *packet_data;
    Queue *queue;
    int to_write;
    int interrupt_ret;

    pthread_mutex_lock(&encoder->mutex_queue);

    queue = encoder->packets;

    packet_data = queue_push_start_already_locked(queue,
                                                  &encoder->mutex_queue,
                                                  &encoder->cond_queue, &to_write,
                                                  (QueueCheckFunc) encoder_read_from_stream_check_func,
                                                  encoder,
                                                  (void **) &interrupt_ret);
    if (packet_data == NULL) {
        pthread_mutex_unlock(&encoder->mutex_queue);
        if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_STOP) return READ_FROM_STREAM_CHECK_MSG_STOP;
        if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_MUX_ERROR) return READ_FROM_STREAM_CHECK_MSG_MUX_ERROR;
        av_assert0(0);
    }

    packet_data->end_of_stream = TRUE;
    LOGI(3, "encoder_push_end_of_stream sending end_of_stream packet");

    queue_push_finish_already_locked(queue, &encoder->mutex_queue,
                                     &encoder->cond_queue, to_write);

    encoder->eof = TRUE;

    /* Wait for the transcode thread to drain whatever is in the queue and
     * exit. The transcode thread clears transcode_thread_created under the
     * lock and broadcasts (see end of encoder_transcode), so we will be
     * woken up once it's done. The previous incarnation used a for(;;)
     * with the check before the wait, which is functionally equivalent
     * but harder to recognise as the standard while-cond-wait pattern. */
    LOGI(3, "encoder_push_end_of_stream waiting for transcoder to drain");
    while (encoder->transcode_thread_created && !encoder->stop) {
        pthread_cond_wait(&encoder->cond_queue, &encoder->mutex_queue);
    }
    pthread_mutex_unlock(&encoder->mutex_queue);
    return READ_FROM_STREAM_CHECK_MSG_STOP;
}


int encoder_read(struct Encoder *encoder) {

    struct PacketData *packet_data;
    Queue *queue = encoder->packets;
    AVPacket *pkt = av_packet_alloc();
    JNIEnv *env;
    AVFormatContext *input_format_ctx = encoder->input_format_ctx;
    enum ReadFromStreamCheckMsg msg;
    struct timespec timeToWait;
    struct timeval now;
    char errbuf[256];
    int interrupt_ret;
    int to_write;
    int stream_no;
    int err = ERROR_NO_ERROR;
    int ret;


    LOGI (3, "encoder_read output_format_ctx->filename %s", encoder->output_format_ctx->url);

    if (!pkt) {
        LOGE(1, "encoder_read could not allocate packet");
        return -ERROR_COULD_NOT_ALLOC_PACKET;
    }

    for (;;) {

        ret = av_read_frame(input_format_ctx, pkt);

        if (ret < 0) {

            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGW(3,
                 "encoder_read_from_stream stream end ret value: %d message: \"%s\"  file name: %s",
                 ret, errbuf, encoder->output_format_ctx->url);


            if ((ret == AVERROR_EOF || avio_feof(input_format_ctx->pb)) && !encoder->eof) {
                msg = encoder_push_end_of_stream(encoder);
                /* push_end_of_stream returns with the lock released; it has
                 * already waited for the transcode thread to drain. We can
                 * skip straight to end. */
                goto end;
            } else if (ret == AVERROR(EINVAL)) {
                msg = encoder_push_end_of_stream(encoder);
                goto end;
            }

            if (input_format_ctx->pb && input_format_ctx->pb->error) {
                LOGW(3, "encoder_read input error");
                /* No lock held on this path. Acquire the operation_queue
                 * lock so exit_loop can use a single unlock. */
                pthread_mutex_lock(&encoder->mutex_queue);
                goto exit_loop;
            }

            LOGW(3, "encoder_read cond wait");

            /* [BUG FIX] tv_nsec was computed as `(tv_usec + 10000) * 1000`
             * which overflows the legal tv_nsec range (must be < 1e9) when
             * tv_usec is close to 1e6. Normalise via a single
             * av_gettime-derived deadline instead of wrestling with the
             * struct timeval / timespec mismatch. */
            gettimeofday(&now, NULL);
            int64_t deadline_us = (int64_t) now.tv_sec * 1000000ll
                                  + now.tv_usec
                                  + 10000ll; /* 10 ms */
            timeToWait.tv_sec = (time_t) (deadline_us / 1000000ll);
            timeToWait.tv_nsec = (long) ((deadline_us % 1000000ll) * 1000ll);
            pthread_mutex_lock(&encoder->mutex_queue);
            pthread_cond_timedwait(&encoder->cond_queue, &encoder->mutex_queue, &timeToWait);
            pthread_mutex_unlock(&encoder->mutex_queue);
            continue;
        } else {
            encoder->eof = FALSE;
        }


        LOGI (8, "encoder_read Read frame %s", encoder->output_format_ctx->url);
        pthread_mutex_lock(&encoder->mutex_queue);

        queue = NULL;

        LOGI (8,
              "encoder_read looking for stream packet.stream_index %d audio_stream_no %d video_stream_no %d",
              pkt->stream_index, encoder->audio_stream_no,
              encoder->video_stream_no);

        for (stream_no = 0; stream_no < encoder->capture_streams_no; ++stream_no) {
            LOGI (5, "encoder_read looking for stream %d", stream_no)
            if (pkt->stream_index ==
                encoder->input_stream_numbers[stream_no]) {
                queue = encoder->packets;
                LOGI (6, "encoder_read stream found [%d]", stream_no);
                break;
            }
        }

        if (queue == NULL) {
            LOGI (6, "encoder_read stream not found");
            goto skip_loop;
        }

        LOGI (10, "encoder_read waiting for queue");
        packet_data = queue_push_start_already_locked(queue,
                                                      &encoder->mutex_queue,
                                                      &encoder->cond_queue,
                                                      &to_write,
                                                      (QueueCheckFunc) encoder_read_from_stream_check_func,
                                                      encoder,
                                                      (void **) &interrupt_ret);

        if (packet_data == NULL) {
            if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_STOP) {
                LOGI (2, "encoder_read queue interrupt stop");
                goto exit_loop;
            } else if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_MUX_ERROR) {
                LOGI (2, "encoder_read queue interrupt mux");
                err = -ERROR_COULD_NOT_READ;
                goto exit_loop;
            } else if (interrupt_ret == READ_FROM_STREAM_CHECK_MSG_SKIP) {
                LOGI(2, "encoder_read interrupt skip");
                goto skip_loop;
            } else {
                av_assert0(0);
            }
        }

        pthread_mutex_unlock(&encoder->mutex_queue);
        packet_data->end_of_stream = FALSE;
        av_packet_ref(packet_data->packet, pkt);
        av_packet_unref(pkt);

        queue_push_finish(queue, &encoder->mutex_queue,
                          &encoder->cond_queue, to_write);

        goto end_loop;

        exit_loop:
        /* exit_loop is reached with mutex_queue HELD (the queue-interrupt
         * paths above and the pb-error path which now acquires the lock
         * before jumping). The EOF / EINVAL paths skip exit_loop entirely
         * because encoder_push_end_of_stream already does the
         * wait-for-thread-drain for those cases. */
        LOGI (2, "encoder_read stop");
        av_packet_unref(pkt);

        av_read_pause(encoder->input_format_ctx);

        /* Request the transcode thread to stop and wait for it to exit.
         * The thread clears transcode_thread_created under the lock and
         * broadcasts on its way out, so this loop terminates. The
         * previous incarnation tested a confusing inverted predicate
         * via utils_if_element_has_value; we now check the simple
         * condition directly. */
        encoder->stop_stream = TRUE;
        pthread_cond_broadcast(&encoder->cond_queue);

        LOGI (4, "encoder_read wait for transcode thread to finish");

        while (encoder->transcode_thread_created && !encoder->stop) {
            pthread_cond_wait(&encoder->cond_queue, &encoder->mutex_queue);
        }

        LOGI (4, "encoder_read flush internal buffers");
        if (encoder->input_codec_ctx != NULL && encoder->input_codec_ctx->codec != NULL)
            avcodec_flush_buffers(encoder->input_codec_ctx);

        pthread_cond_broadcast(&encoder->cond_queue);
        pthread_mutex_unlock(&encoder->mutex_queue);

        goto end;


        skip_loop:

        av_packet_unref(pkt);
        pthread_mutex_unlock(&encoder->mutex_queue);

        end_loop:
        continue;

    }

    end:

    LOGI (3, "encoder_read finish");

    av_packet_free(&pkt);

    return err;

}


int encoder_start_threads(struct Encoder *encoder) {

    pthread_attr_t attr;
    int ret;
    int err = ERROR_NO_ERROR;
    int attr_inited = FALSE;

    ret = pthread_attr_init(&attr);
    if (ret) {
        err = -ERROR_COULD_NOT_INIT_PTHREAD_ATTR;
        goto end;
    }
    attr_inited = TRUE;

    /* [BUG FIX] The original set transcode_thread_created = TRUE *after*
     * pthread_create, lock-free. The transcode thread can run to completion
     * (e.g. on an early write_header failure that immediately goes to
     * detach_current_thread) and clear the flag before this code sets it,
     * causing the flag to read TRUE for a thread that has already exited.
     * Now we set it to TRUE under the lock before pthread_create, so the
     * thread function sees a coherent value, and we clear it back to FALSE
     * if pthread_create itself fails. */
    pthread_mutex_lock(&encoder->mutex_queue);
    encoder->transcode_thread_created = TRUE;
    pthread_mutex_unlock(&encoder->mutex_queue);

    ret = pthread_create(&encoder->transcode_thread, &attr, encoder_transcode,
                         encoder);

    if (ret) {
        pthread_mutex_lock(&encoder->mutex_queue);
        encoder->transcode_thread_created = FALSE;
        pthread_cond_broadcast(&encoder->cond_queue);
        pthread_mutex_unlock(&encoder->mutex_queue);
        err = -ERROR_COULD_NOT_CREATE_PTHREAD;
        goto end;
    }

    end:

    if (attr_inited) {
        ret = pthread_attr_destroy(&attr);
        if (ret && !err) {
            err = -ERROR_COULD_NOT_DESTROY_PTHREAD_ATTR;
        }
    }

    return err;
}

int encoder_find_stream_info(struct Encoder *encoder) {
    // find video informations
    int err = ERROR_NO_ERROR;
    int stream_no;

    if ((err = avformat_find_stream_info(encoder->input_format_ctx, NULL)) < 0) {
        LOGE (1, "Could not open stream\n");
        err = -ERROR_COULD_NOT_OPEN_STREAM;
    }

    for (stream_no = 0; stream_no < encoder->input_format_ctx->nb_streams; stream_no++) {
        AVStream *stream = encoder->input_format_ctx->streams[stream_no];
        stream->discard = AVDISCARD_ALL;
    }

    return err;
}

int encoder_alloc_frames(struct Encoder *encoder) {

    encoder->audio_frame = av_frame_alloc();

    if (encoder->audio_frame == NULL) {
        LOGE(1, "encoder_alloc_frames audio frame");
        return -ERROR_COULD_NOT_ALLOC_FRAME;
    }

    encoder->filtered_frame = av_frame_alloc();

    if (encoder->filtered_frame == NULL) {
        LOGE(1, "encoder_alloc_frames filtered frame");
        return -ERROR_COULD_NOT_ALLOC_FRAME;
    }

    encoder->enc_packet = av_packet_alloc();

    if(encoder->enc_packet == NULL){
        LOGE(1, "encoder_alloc_packer");
        return -ERROR_COULD_NOT_ALLOC_PACKET;
    }

    return ERROR_NO_ERROR;
}

int encoder_alloc_input_context(struct Encoder *encoder) {
    if ((encoder->input_format_ctx = avformat_alloc_context()) == NULL)
        return AVERROR(ENOMEM);

    return ERROR_NO_ERROR;
}


void encoder_stop(struct State *state) {

    struct Encoder *encoder = state->encoder;

    if (encoder == NULL || encoder->thiz == NULL) {
        LOGI (4, "encoder_stop already freed");
        return;
    }

    LOGI (3, "encoder_stop interrupt");
    encoder->interrupt = TRUE;
    LOGI (3, "encoder_stop stop_without_lock");

    pthread_mutex_lock(&encoder->mutex_operation);
    encoder_stop_without_lock(state);
    pthread_mutex_unlock(&encoder->mutex_operation);

    LOGI (3, "encoder_stop stop_without_lock finished");

}


int encoder_set_data_source(struct State *state, AVDictionary **dictionary, AVDictionary **metadata,
                            const char *file_path, const char *output_path, int video_stream_no,
                            int audio_stream_no) {

    struct Encoder *encoder = state->encoder;
    int err = ERROR_NO_ERROR;

    encoder_stop_without_lock(state);

    //Call Start Method
    (*state->env)->CallVoidMethod(state->env, encoder->thiz,
                                  encoder->encoder_on_download_started_method);


    if (encoder->interrupt || (err = encoder_alloc_input_context(encoder)) < 0)
        goto error;

    if (encoder->interrupt || (err = encoder_create_interrupt_callback(encoder)) < 0)
        goto error;

    if (encoder->interrupt || (err = encoder_open_input(encoder, dictionary, file_path)) < 0)
        goto error;

    if (encoder->interrupt || (err = encoder_find_stream_info(encoder)) < 0)
        goto error;

#if LOG_LEVEL >1
    av_dump_format(encoder->input_format_ctx, 0, file_path, FALSE);
#endif

    if (encoder->interrupt ||
        (encoder->audio_stream_no = encoder_find_streams(encoder, AVMEDIA_TYPE_AUDIO,
                                                         audio_stream_no)) < 0) {
        err = -ERROR_COULD_NOT_FIND_STREAMS;
        goto error;
    }

    if (encoder->interrupt || (err = encoder_create_context(encoder, output_path, *metadata)) < 0) {
        goto error;
    }
    /* [BUG FIX] On success, encoder_create_context has stored *metadata
     * inside output_format_ctx->metadata, which now owns it; null the
     * caller's slot so they don't free it again. On failure,
     * avformat_alloc_output_context2 didn't run, so the caller still owns
     * *metadata and should free it. */
    *metadata = NULL;

    if (encoder->interrupt || (err = encoder_create_output_format(encoder, output_path)) < 0) {
        goto error;
    }

    if (encoder->interrupt || (err = encoder_open_output(encoder, output_path)) < 0) {
        goto error;
    }

    if (encoder->interrupt || (err = encoder_create_encoder_context(encoder)) < 0) {
        goto error;
    }

    if (encoder->interrupt || (err = encoder_alloc_frames(encoder)) < 0)
        goto error;

    if (encoder->interrupt || (err = encoder_alloc_queues(state)) < 0)
        goto error;

    if (encoder->interrupt || (err = encoder_init_output_streams(encoder)) < 0)
        goto error;


    if (encoder->interrupt || (err = encoder_init_filters(encoder)) < 0)
        goto error;


    encoder_prepare(encoder);

    if (encoder->interrupt || (err = encoder_start_threads(encoder)) < 0)
        goto error;

    goto end;

    error:

    LOGE (1, "encoder_set_data_source ERROR Exiting %d", err);
    encoder_prepare_free(encoder);
    encoder_threads_free(encoder);
    encoder_find_streams_free(encoder);
    encoder_alloc_queues_free(state);
    encoder_init_filters_free(encoder);
    encoder_find_stream_info_free(encoder);
    encoder_alloc_frames_free(encoder);
    encoder_create_encoder_context_free(encoder);
    encoder_open_input_free(encoder);
    encoder_create_context_free(encoder);

    end:


    LOGI (1, "encoder_set_data_source END");

    return err;

}

void jni_encoder_dealloc(JNIEnv *env, jobject thiz) {

    struct Encoder *encoder = get_encoder_field(env, thiz);
    struct State state = {.encoder = encoder, .env=env};

    LOGI (1, "jni_encoder_dealloc");

    if (encoder == NULL) {
        LOGI (4, "jni_encoder_dealloc already freed");
        return;
    }

    /* [BUG FIX] The original code ONLY took mutex_operation, deleted the
     * global ref, and destroyed mutexes/cond. If the transcode thread was
     * still running, several things went wrong:
     *  1. mutex_operation was held by jni_encoder_start during a synchronous
     *     encode, so this call would block forever (no interrupt signal).
     *  2. Even if it got the lock, mutex_queue and cond_queue were
     *     destroyed while the thread was still using them — undefined
     *     behaviour.
     *  3. The encoder struct was av_free'd while the thread held a pointer
     *     to it.
     *
     * The fix: interrupt first (this unblocks the FFmpeg interrupt callback
     * and any in-progress demux), then take mutex_operation and run the
     * full stop/cleanup pipeline (which joins the transcode thread), then
     * destroy mutexes and free. */
    encoder->interrupt = TRUE;

    pthread_mutex_lock(&encoder->mutex_operation);
    encoder_stop_without_lock(&state);

    if (encoder->thiz != NULL) {
        (*env)->DeleteGlobalRef(env, encoder->thiz);
        encoder->thiz = NULL;
    }

    /* Clear the Java field BEFORE destroying mutexes / freeing the struct,
     * so any concurrent JNI call from another thread sees NULL on
     * get_encoder_field and bails out before touching destroyed state. */
    clean_encoder_field(env, thiz);

    pthread_cond_destroy(&encoder->cond_queue);
    pthread_mutex_destroy(&encoder->mutex_queue);
    pthread_mutex_unlock(&encoder->mutex_operation);
    pthread_mutex_destroy(&encoder->mutex_operation);

    free(encoder);

    LOGI (1, "jni_encoder_dealloc end");
}


int jni_encoder_start(JNIEnv *env, jobject thiz, jobject dictionary, jobject metadata,
                      jstring filePath, jstring outputPath, int video_stream_no,
                      int audio_stream_no) {


    struct Encoder *encoder = get_encoder_field(env, thiz);
    struct State state = {.encoder = encoder, .env=env};
    AVDictionary *dict = NULL;
    AVDictionary *met = NULL;
    const char *file_path = NULL;
    const char *output_path = NULL;
    int ret = ERROR_NO_ERROR;
    int locked = FALSE;

    LOGI(1, "jni_encoder_start");

    /* [BUG FIX] Original did not check encoder for NULL before locking
     * &encoder->mutex_operation. If dealloc has run (clearing the Java
     * field), get_encoder_field returns NULL and we'd crash. */
    if (encoder == NULL) {
        LOGW(1, "jni_encoder_start already freed");
        return -ERROR_COULD_NOT_ALLOCATE_ENCODER;
    }

    if (dictionary != NULL) {
        dict = utils_read_dictionary(env, dictionary);
    }

    if (metadata != NULL) {
        met = utils_read_dictionary(env, metadata);
    }

    if (filePath == NULL || outputPath == NULL) {
        LOGE(1, "jni_encoder_start NULL paths");
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    file_path = (*env)->GetStringUTFChars(env, filePath, NULL);
    output_path = (*env)->GetStringUTFChars(env, outputPath, NULL);

    pthread_mutex_lock(&encoder->mutex_operation);
    locked = TRUE;

    if (encoder->interrupt ||
        (ret = encoder_set_data_source(&state, &dict, &met, file_path, output_path, video_stream_no,
                                       audio_stream_no)) < 0) {
        LOGE (2, "jni_encoder_start prepare error");
        goto end;
    }
    /* set_data_source consumed dict (encoder_open_input freed it) and
     * transferred ownership of met to output_format_ctx; both slots are
     * now NULL and we don't free them again on the way out. */

    if (encoder_read(encoder) < 0) {
        LOGE (2, "jni_encoder_start encoder read error");
        ret = -ERROR_COULD_NOT_READ;
        goto end;
    }

    end:

    /* [BUG FIX] Original released file_path/output_path even if
     * GetStringUTFChars was never called (early return on NULL paths),
     * reading uninitialised pointers. Guard each release on the C-string
     * pointer being non-NULL, not on the jstring. */
    if (file_path != NULL) {
        (*env)->ReleaseStringUTFChars(env, filePath, file_path);
    }
    if (output_path != NULL) {
        (*env)->ReleaseStringUTFChars(env, outputPath, output_path);
    }

    /* [BUG FIX] Original had paths where it returned without unlocking
     * mutex_operation (the encoder_read error branch). Centralise the
     * unlock here. */
    if (locked) {
        pthread_mutex_unlock(&encoder->mutex_operation);
    }

    /* Free any dict / metadata we read but didn't hand off to the encoder. */
    if (dict != NULL) av_dict_free(&dict);
    if (met != NULL) av_dict_free(&met);

    return ret;
}

void jni_encoder_interrupt(JNIEnv *env, jobject thiz) {

    struct Encoder *encoder = get_encoder_field(env, thiz);

    if (encoder == NULL || encoder->thiz == NULL) {
        LOGI (4, "encoder_interrupt already freed");
        return;
    }

    LOGI (3, "encoder_interrupt");

    encoder->interrupt = TRUE;

}

void jni_encoder_stop(JNIEnv *env, jobject thiz) {

    struct Encoder *encoder = get_encoder_field(env, thiz);
    struct State state = {.encoder = encoder, .env=env};

    LOGI(1, "jni_encoder_stop");

    if (encoder == NULL) {
        LOGW(1, "jni_encoder_stop already freed");
        return;
    }

    encoder_stop(&state);

    LOGI(1, "jni_encoder_stop end");
}