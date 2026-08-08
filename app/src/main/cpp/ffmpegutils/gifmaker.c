/*
 * gifmaker.c
 *
 * Copyright (c) 2026 info@solarized.dev
 *
 * SPDX-License-Identifier: MIT
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <inttypes.h>

#include <libavutil/opt.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
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
#include "gifmaker.h"
#include "utils.h"

#define LOG_LEVEL 0
#define FFMPEG_LOG_LEVEL AV_LOG_QUIET
#define LOG_TAG "gifmaker.c"
#define LOGI(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__);}
#define LOGE(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__);}
#define LOGW(level, ...) if (level <= LOG_LEVEL) {__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__);}

#define UPDATE_TIME_US 50000ll

#define DEFAULT_FPS 12
#define DEFAULT_WIDTH 320
#define MAX_FPS 30
#define MIN_WIDTH 16
#define MAX_WIDTH 1024

/* Source-side frame sampling rate. Fixed because varying it doesn't
 * change playback speed — only smoothness — and 12 fps is the sweet
 * spot for GIF size vs motion quality. The user's "speed" selection
 * controls the per-frame delay on the output side instead, so the
 * GIF's wall-clock duration scales with the chosen playback fps. */
#define SAMPLING_FPS 12


struct GifMaker {
    AVFormatContext *input_format_ctx;
    AVFormatContext *output_format_ctx;
    AVCodecContext *input_codec_ctx;
    AVCodecContext *output_codec_ctx;

    AVStream *input_stream;
    int video_stream_index;

    AVFilterContext *buffersrc_ctx;
    AVFilterContext *buffersink_ctx;
    AVFilterGraph *filter_graph;

    AVPacket *dec_packet;
    AVPacket *enc_packet;
    AVFrame *decoded_frame;
    AVFrame *filtered_frame;

    AVIOInterruptCB interrupt_callback;

    int64_t start_us;
    int64_t end_us;
    int64_t duration_us;
    int64_t last_progress_us;

    int fps;
    int width;

    /* Counts frames as they leave the filter graph and enter the
     * encoder. Used to derive each frame's PTS in the encoder's 1/100
     * time_base directly, so the per-frame delay reflects the user's
     * playback fps regardless of the filter's sampling rate. */
    int64_t out_frame_count;

    int header_written;

    /* See encoder.c — interrupt is read in tight inner loops and from the
     * FFmpeg interrupt callback while jni_gifmaker_interrupt writes from
     * the Java thread. volatile so the compiler doesn't hoist the read. */
    volatile int interrupt;

    pthread_mutex_t mutex_operation;

    JavaVM *get_javavm;
    jobject thiz;
    jmethodID gifmaker_on_progress_method;
    jmethodID gifmaker_on_started_method;
    jmethodID gifmaker_on_finished_method;
};


enum GifMakerErrors {
    ERROR_NO_ERROR = 0,
    ERROR_COULD_NOT_ALLOCATE_GIFMAKER,
    ERROR_COULD_NOT_GET_JAVA_VM,
    ERROR_COULD_NOT_CREATE_GLOBAL_REF,
    ERROR_NOT_FOUND_GIFMAKER_CLASS,
    ERROR_NOT_FOUND_M_NATIVE_FIELD,
    ERROR_NOT_FOUND_PROGRESS_METHOD,
    ERROR_NOT_FOUND_STARTED_METHOD,
    ERROR_NOT_FOUND_FINISHED_METHOD,
    ERROR_COULD_NOT_INIT_PTHREAD_ATTR,
    ERROR_COULD_NOT_INIT_PATHS,
    ERROR_COULD_NOT_OPEN_INPUT,
    ERROR_COULD_NOT_FIND_STREAM_INFO,
    ERROR_COULD_NOT_FIND_VIDEO_STREAM,
    ERROR_COULD_NOT_OPEN_DECODER,
    ERROR_COULD_NOT_CREATE_OUTPUT_CTX,
    ERROR_COULD_NOT_FIND_GIF_ENCODER,
    ERROR_COULD_NOT_OPEN_GIF_ENCODER,
    ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAM,
    ERROR_COULD_NOT_INIT_FILTERS,
    ERROR_COULD_NOT_OPEN_OUTPUT,
    ERROR_COULD_NOT_WRITE_HEADER,
    ERROR_COULD_NOT_ALLOC_FRAME,
    ERROR_COULD_NOT_ALLOC_PACKET,
    ERROR_INTERRUPTED,
    ERROR_WHILE_DECODING,
    ERROR_WHILE_FILTERING,
    ERROR_WHILE_ENCODING
};


static int gifmaker_ctx_interrupt_callback(void *p) {
    struct GifMaker *gif = (struct GifMaker *) p;
    return gif->interrupt ? 1 : 0;
}


static void gifmaker_release_pipeline(struct GifMaker *gif) {

    if (gif->filter_graph != NULL) {
        avfilter_graph_free(&gif->filter_graph);
        gif->filter_graph = NULL;
        gif->buffersrc_ctx = NULL;
        gif->buffersink_ctx = NULL;
    }

    if (gif->decoded_frame != NULL) av_frame_free(&gif->decoded_frame);
    if (gif->filtered_frame != NULL) av_frame_free(&gif->filtered_frame);
    if (gif->dec_packet != NULL) av_packet_free(&gif->dec_packet);
    if (gif->enc_packet != NULL) av_packet_free(&gif->enc_packet);

    if (gif->input_codec_ctx != NULL) avcodec_free_context(&gif->input_codec_ctx);
    if (gif->output_codec_ctx != NULL) avcodec_free_context(&gif->output_codec_ctx);

    if (gif->output_format_ctx != NULL) {
        if (gif->output_format_ctx->pb != NULL &&
            !(gif->output_format_ctx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&gif->output_format_ctx->pb);
        }
        avformat_free_context(gif->output_format_ctx);
        gif->output_format_ctx = NULL;
    }

    if (gif->input_format_ctx != NULL) {
        avformat_close_input(&gif->input_format_ctx);
        gif->input_format_ctx = NULL;
    }

    gif->input_stream = NULL;
    gif->video_stream_index = -1;
    gif->header_written = 0;
}


static int gifmaker_open_input(struct GifMaker *gif, const char *file_path) {

    int ret;
    char errbuf[128];
    const char *errbuf_ptr = errbuf;

    if ((gif->input_format_ctx = avformat_alloc_context()) == NULL) {
        return -ERROR_COULD_NOT_OPEN_INPUT;
    }

    gif->interrupt_callback = (AVIOInterruptCB) {gifmaker_ctx_interrupt_callback, gif};
    gif->input_format_ctx->interrupt_callback = gif->interrupt_callback;

    if ((ret = avformat_open_input(&gif->input_format_ctx, file_path, NULL, NULL)) < 0) {
        if (av_strerror(ret, errbuf, sizeof(errbuf)) < 0)
            errbuf_ptr = strerror(AVUNERROR(ret));
        LOGE(1, "gifmaker_open_input failed: %s (%d: %s)", file_path, ret, errbuf_ptr);
        return -ERROR_COULD_NOT_OPEN_INPUT;
    }

    if (avformat_find_stream_info(gif->input_format_ctx, NULL) < 0) {
        LOGE(1, "gifmaker_open_input avformat_find_stream_info failed");
        return -ERROR_COULD_NOT_FIND_STREAM_INFO;
    }

    return ERROR_NO_ERROR;
}


static int gifmaker_open_decoder(struct GifMaker *gif) {

    int video_index;
    AVStream *stream;
    const AVCodec *dec;
    AVCodecContext *dec_ctx;
    int ret;

    video_index = av_find_best_stream(gif->input_format_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, &dec, 0);

    if (video_index < 0 || dec == NULL) {
        LOGE(1, "gifmaker_open_decoder no video stream");
        return -ERROR_COULD_NOT_FIND_VIDEO_STREAM;
    }

    stream = gif->input_format_ctx->streams[video_index];

    dec_ctx = avcodec_alloc_context3(dec);
    if (dec_ctx == NULL) {
        LOGE(1, "gifmaker_open_decoder avcodec_alloc_context3 failed");
        return -ERROR_COULD_NOT_OPEN_DECODER;
    }

    if ((ret = avcodec_parameters_to_context(dec_ctx, stream->codecpar)) < 0) {
        LOGE(1, "gifmaker_open_decoder avcodec_parameters_to_context failed");
        avcodec_free_context(&dec_ctx);
        return -ERROR_COULD_NOT_OPEN_DECODER;
    }

    dec_ctx->pkt_timebase = stream->time_base;
    av_opt_set(dec_ctx, "threads", "auto", 0);

    if ((ret = avcodec_open2(dec_ctx, dec, NULL)) < 0) {
        LOGE(1, "gifmaker_open_decoder avcodec_open2 failed");
        avcodec_free_context(&dec_ctx);
        return -ERROR_COULD_NOT_OPEN_DECODER;
    }

    /* Mark every other stream as discard to keep av_read_frame light. */
    for (int i = 0; i < gif->input_format_ctx->nb_streams; ++i) {
        gif->input_format_ctx->streams[i]->discard =
                (i == video_index) ? AVDISCARD_DEFAULT : AVDISCARD_ALL;
    }

    gif->input_codec_ctx = dec_ctx;
    gif->input_stream = stream;
    gif->video_stream_index = video_index;

    return ERROR_NO_ERROR;
}


static int gifmaker_open_output(struct GifMaker *gif, const char *output_path) {

    const AVCodec *enc;
    AVCodecContext *enc_ctx;
    AVStream *out_stream;
    int target_w;
    int target_h;
    int src_w = gif->input_codec_ctx->width;
    int src_h = gif->input_codec_ctx->height;
    int ret;

    if (avformat_alloc_output_context2(&gif->output_format_ctx, NULL, "gif", output_path) < 0
        || gif->output_format_ctx == NULL) {
        LOGE(1, "gifmaker_open_output avformat_alloc_output_context2 failed");
        return -ERROR_COULD_NOT_CREATE_OUTPUT_CTX;
    }

    enc = avcodec_find_encoder(AV_CODEC_ID_GIF);

    if (enc == NULL) {
        LOGE(1, "gifmaker_open_output GIF encoder not found");
        return -ERROR_COULD_NOT_FIND_GIF_ENCODER;
    }

    enc_ctx = avcodec_alloc_context3(enc);
    if (enc_ctx == NULL) {
        LOGE(1, "gifmaker_open_output avcodec_alloc_context3 failed");
        return -ERROR_COULD_NOT_OPEN_GIF_ENCODER;
    }

    /* Compute target dimensions: requested width, height preserved by aspect,
     * snapped to even values to keep scaler happy. If source dimensions are
     * unknown, fall back to defaults. */
    if (src_w <= 0 || src_h <= 0) {
        src_w = DEFAULT_WIDTH;
        src_h = (DEFAULT_WIDTH * 9) / 16;
    }

    target_w = gif->width > 0 ? gif->width : DEFAULT_WIDTH;
    if (target_w < MIN_WIDTH) target_w = MIN_WIDTH;
    if (target_w > MAX_WIDTH) target_w = MAX_WIDTH;
    /* Don't upscale beyond the source. */
    if (target_w > src_w) target_w = src_w;
    target_w &= ~1;
    if (target_w < MIN_WIDTH) target_w = MIN_WIDTH;

    target_h = (int) (((int64_t) target_w * src_h) / src_w);
    target_h &= ~1;
    if (target_h < 2) target_h = 2;

    enc_ctx->width = target_w;
    enc_ctx->height = target_h;
    enc_ctx->pix_fmt = AV_PIX_FMT_PAL8;
    /* GIF stores frame delays in centiseconds (1/100 s) in the wire
     * format — see gif_write_header in libavformat/gifenc.c which forces
     * the muxer's stream time_base to {1, 100}. Set the encoder's
     * time_base to the same unit so frame PTS map directly to delay
     * stops without rounding through an intermediate rate.
     *
     * Previously this was {1, fps}: the encoder still produced delays
     * proportional to fps, but the slowest setting (6 fps) and the
     * fastest (25 fps) ended up looking similar because frames were
     * being dropped by the fps= filter rather than the delays differing.
     * Pinning the encoder to centiseconds and rescaling each frame's
     * PTS to that base makes the per-frame delay explicit, so the
     * playback speed actually changes. */
    enc_ctx->time_base = (AVRational) {1, 100};
    enc_ctx->framerate = (AVRational) {gif->fps, 1};
    enc_ctx->sample_aspect_ratio = (AVRational) {1, 1};

    if (gif->output_format_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if ((ret = avcodec_open2(enc_ctx, enc, NULL)) < 0) {
        LOGE(1, "gifmaker_open_output avcodec_open2 failed (%d)", ret);
        avcodec_free_context(&enc_ctx);
        return -ERROR_COULD_NOT_OPEN_GIF_ENCODER;
    }

    out_stream = avformat_new_stream(gif->output_format_ctx, NULL);
    if (out_stream == NULL) {
        LOGE(1, "gifmaker_open_output avformat_new_stream failed");
        avcodec_free_context(&enc_ctx);
        return -ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAM;
    }

    if ((ret = avcodec_parameters_from_context(out_stream->codecpar, enc_ctx)) < 0) {
        LOGE(1, "gifmaker_open_output avcodec_parameters_from_context failed");
        avcodec_free_context(&enc_ctx);
        return -ERROR_COULD_NOT_ALLOCATE_OUTPUT_STREAM;
    }

    out_stream->time_base = enc_ctx->time_base;

    if (!(gif->output_format_ctx->oformat->flags & AVFMT_NOFILE)) {
        if ((ret = avio_open(&gif->output_format_ctx->pb, output_path, AVIO_FLAG_WRITE)) < 0) {
            LOGE(1, "gifmaker_open_output avio_open failed (%d) %s", ret, output_path);
            avcodec_free_context(&enc_ctx);
            return -ERROR_COULD_NOT_OPEN_OUTPUT;
        }
    }

    gif->output_codec_ctx = enc_ctx;

    return ERROR_NO_ERROR;
}


static int gifmaker_init_filters(struct GifMaker *gif) {

    char args[512];
    char filter_desc[1024];
    int ret = 0;
    const AVFilter *buffersrc;
    const AVFilter *buffersink;
    AVFilterContext *buffersrc_ctx = NULL;
    AVFilterContext *buffersink_ctx = NULL;
    AVFilterInOut *outputs = NULL;
    AVFilterInOut *inputs = NULL;
    AVFilterGraph *filter_graph = NULL;
    AVCodecContext *dec_ctx = gif->input_codec_ctx;
    AVCodecContext *enc_ctx = gif->output_codec_ctx;
    AVRational time_base = gif->input_stream->time_base;

    enum AVPixelFormat pix_fmts[] = {AV_PIX_FMT_PAL8, AV_PIX_FMT_NONE};

    buffersrc = avfilter_get_by_name("buffer");
    buffersink = avfilter_get_by_name("buffersink");
    filter_graph = avfilter_graph_alloc();
    outputs = avfilter_inout_alloc();
    inputs = avfilter_inout_alloc();

    if (buffersrc == NULL || buffersink == NULL || filter_graph == NULL
        || outputs == NULL || inputs == NULL) {
        ret = AVERROR(ENOMEM);
        goto end;
    }

    snprintf(args, sizeof(args),
             "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d",
             dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt,
             time_base.num, time_base.den,
             dec_ctx->sample_aspect_ratio.num > 0 ? dec_ctx->sample_aspect_ratio.num : 1,
             dec_ctx->sample_aspect_ratio.den > 0 ? dec_ctx->sample_aspect_ratio.den : 1);

    if ((ret = avfilter_graph_create_filter(&buffersrc_ctx, buffersrc, "in",
                                            args, NULL, filter_graph)) < 0) {
        LOGE(1, "gifmaker_init_filters create buffersrc failed");
        goto end;
    }

    /* Buffersink needs pix_fmts set BEFORE init. avfilter_graph_create_filter
     * allocates and inits in one call, so the option is no longer settable
     * by the time it returns. Use the alloc → set → init pattern instead so
     * we can constrain the sink to PAL8, matching the GIF encoder. */
    buffersink_ctx = avfilter_graph_alloc_filter(filter_graph, buffersink, "out");
    if (buffersink_ctx == NULL) {
        ret = AVERROR(ENOMEM);
        LOGE(1, "gifmaker_init_filters alloc buffersink failed");
        goto end;
    }

    /* FFmpeg 9.0: av_opt_set_int_list and buffersink's binary "pix_fmts"
     * option were removed (deprecated since 7.1). The replacement is the
     * typed array option "pixel_formats" set via av_opt_set_array(), which
     * takes an element count instead of an AV_PIX_FMT_NONE terminator. */
    if ((ret = av_opt_set_array(buffersink_ctx, "pixel_formats",
                                AV_OPT_SEARCH_CHILDREN, 0,
                                sizeof(pix_fmts)/sizeof(pix_fmts[0]) - 1,
                                AV_OPT_TYPE_PIXEL_FMT, pix_fmts)) < 0) {
        LOGE(1, "gifmaker_init_filters set pix_fmts failed");
        goto end;
    }

    if ((ret = avfilter_init_str(buffersink_ctx, NULL)) < 0) {
        LOGE(1, "gifmaker_init_filters init buffersink failed");
        goto end;
    }

    /* Single-pass palettegen + paletteuse via split. The split filter
     * duplicates each frame into two branches: one feeds palettegen which
     * accumulates statistics and emits a single palette frame on EOF; the
     * other feeds paletteuse which buffers frames until that palette
     * arrives, then maps each frame onto it. Output is PAL8 — exactly what
     * the GIF encoder consumes.
     *
     * Sampling fps is fixed at SAMPLING_FPS regardless of the user's
     * speed selection. Playback speed comes from the per-frame delay
     * we set on the encoder side (gif->fps cs/frame), so changing fps
     * here would just change smoothness without affecting wall-clock
     * duration. */
    snprintf(filter_desc, sizeof(filter_desc),
             "fps=%d,scale=%d:%d:flags=lanczos,split[s0][s1];"
             "[s0]palettegen=stats_mode=full[p];"
             "[s1][p]paletteuse=dither=sierra2_4a",
             SAMPLING_FPS, enc_ctx->width, enc_ctx->height);

    LOGI(1, "gifmaker filter chain: %s", filter_desc);

    outputs->name = av_strdup("in");
    outputs->filter_ctx = buffersrc_ctx;
    outputs->pad_idx = 0;
    outputs->next = NULL;

    inputs->name = av_strdup("out");
    inputs->filter_ctx = buffersink_ctx;
    inputs->pad_idx = 0;
    inputs->next = NULL;

    if (outputs->name == NULL || inputs->name == NULL) {
        ret = AVERROR(ENOMEM);
        goto end;
    }

    if ((ret = avfilter_graph_parse_ptr(filter_graph, filter_desc,
                                        &inputs, &outputs, NULL)) < 0) {
        LOGE(1, "gifmaker_init_filters parse failed (%d)", ret);
        goto end;
    }

    if ((ret = avfilter_graph_config(filter_graph, NULL)) < 0) {
        LOGE(1, "gifmaker_init_filters config failed (%d)", ret);
        goto end;
    }

    gif->buffersrc_ctx = buffersrc_ctx;
    gif->buffersink_ctx = buffersink_ctx;
    gif->filter_graph = filter_graph;
    filter_graph = NULL;

    end:
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);
    if (filter_graph != NULL) avfilter_graph_free(&filter_graph);

    return ret < 0 ? -ERROR_COULD_NOT_INIT_FILTERS : ERROR_NO_ERROR;
}


static int gifmaker_alloc_frames(struct GifMaker *gif) {

    gif->decoded_frame = av_frame_alloc();
    gif->filtered_frame = av_frame_alloc();
    gif->dec_packet = av_packet_alloc();
    gif->enc_packet = av_packet_alloc();

    if (gif->decoded_frame == NULL || gif->filtered_frame == NULL) {
        return -ERROR_COULD_NOT_ALLOC_FRAME;
    }
    if (gif->dec_packet == NULL || gif->enc_packet == NULL) {
        return -ERROR_COULD_NOT_ALLOC_PACKET;
    }
    return ERROR_NO_ERROR;
}


/* Reserve the tail of the progress bar for the palette/encode burst that
 * fires at EOF — palettegen buffers every input frame and only emits its
 * palette once the source signals EOF, so paletteuse + the GIF encoder do
 * all their real work after decoding finishes. Without this, the bar sat
 * at 0 for the entire decode pass, then jumped to 100. */
#define DECODE_PROGRESS_FRACTION 95

enum ProgressPhase {
    PROGRESS_PHASE_DECODE,
    PROGRESS_PHASE_ENCODE
};

static void gifmaker_publish_progress(JNIEnv *env, struct GifMaker *gif,
                                      int64_t current_us, enum ProgressPhase phase) {

    int64_t total = gif->end_us > gif->start_us ? gif->end_us - gif->start_us : gif->duration_us;

    if (total <= 0) return;

    int64_t elapsed = current_us - gif->start_us;
    if (elapsed < 0) elapsed = 0;
    if (elapsed > total) elapsed = total;

    /* Decode pass owns 0..DECODE_PROGRESS_FRACTION%; encode burst owns the
     * remainder. Without the phase split, the encode burst re-emitted the
     * same 0..95% range and the bar visibly refilled twice. */
    if (phase == PROGRESS_PHASE_DECODE) {
        elapsed = (elapsed * DECODE_PROGRESS_FRACTION) / 100;
    } else {
        int64_t decode_part = (total * DECODE_PROGRESS_FRACTION) / 100;
        int64_t encode_part = total - decode_part;
        elapsed = decode_part + (elapsed * encode_part) / total;
    }

    int64_t now = av_gettime_relative();
    if (gif->last_progress_us != 0 && (now - gif->last_progress_us) < UPDATE_TIME_US) {
        return;
    }
    gif->last_progress_us = now;

    if (gif->thiz != NULL) {
        (*env)->CallVoidMethod(env, gif->thiz, gif->gifmaker_on_progress_method,
                               (jlong) elapsed, (jlong) total);
        /* [BUG FIX] Java callbacks may throw; clear so subsequent JNI
         * calls don't abort with "JNI ERROR (app bug): accessed stale
         * Object" on a pending exception. */
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE(1, "gifmaker exception in progress callback");
        }
    }
}


/* Pull every frame the encoder is willing to give us right now and mux it.
 * Called both per-frame and on flush (frame == NULL). */
static int gifmaker_drain_encoder(struct GifMaker *gif, AVFrame *frame) {

    int ret;
    AVPacket *pkt = gif->enc_packet;
    AVStream *out_stream = gif->output_format_ctx->streams[0];

    ret = avcodec_send_frame(gif->output_codec_ctx, frame);
    if (ret < 0) {
        LOGE(1, "gifmaker_drain_encoder send_frame failed (%d)", ret);
        return -ERROR_WHILE_ENCODING;
    }

    while (1) {
        av_packet_unref(pkt);
        ret = avcodec_receive_packet(gif->output_codec_ctx, pkt);

        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            return ERROR_NO_ERROR;
        }
        if (ret < 0) {
            LOGE(1, "gifmaker_drain_encoder receive_packet failed (%d)", ret);
            return -ERROR_WHILE_ENCODING;
        }

        pkt->stream_index = 0;
        av_packet_rescale_ts(pkt, gif->output_codec_ctx->time_base, out_stream->time_base);

        ret = av_interleaved_write_frame(gif->output_format_ctx, pkt);
        if (ret < 0) {
            LOGE(1, "gifmaker_drain_encoder write_frame failed (%d)", ret);
            return -ERROR_WHILE_ENCODING;
        }
    }
}


/* Pull every filtered frame the graph has buffered, and forward to the
 * encoder. EAGAIN means we need to push more input; EOF means the graph
 * has drained (only happens after we flush the source). */
static int gifmaker_drain_filter(JNIEnv *env, struct GifMaker *gif) {

    int ret;

    while (1) {
        if (gif->interrupt) return -ERROR_INTERRUPTED;

        ret = av_buffersink_get_frame(gif->buffersink_ctx, gif->filtered_frame);

        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            return ret;
        }
        if (ret < 0) {
            LOGE(1, "gifmaker_drain_filter buffersink_get_frame failed (%d)", ret);
            return -ERROR_WHILE_FILTERING;
        }

        gif->filtered_frame->pict_type = AV_PICTURE_TYPE_NONE;

        /* Progress for the encode burst: the filter graph re-stamps frames
         * to its own output time_base (1/SAMPLING_FPS), so rescale from
         * there before we overwrite pts for the encoder. */
        AVRational filter_tb = av_buffersink_get_time_base(gif->buffersink_ctx);
        gifmaker_publish_progress(env, gif,
                                  gif->start_us +
                                  av_rescale_q(gif->filtered_frame->pts,
                                               filter_tb,
                                               AV_TIME_BASE_Q),
                                  PROGRESS_PHASE_ENCODE);

        /* Set the frame's PTS in the encoder's 1/100 cs time_base
         * directly from the output frame counter, spaced by 100/fps cs.
         * This decouples sampling fps (fixed in the filter chain at
         * SAMPLING_FPS, controls smoothness) from playback fps
         * (gif->fps, controls per-frame delay).
         *
         * Earlier versions just rescaled the filter's PTS to the encoder
         * time_base — that produced different per-frame delays for
         * different fps settings, but since the filter was *also* set to
         * sample at gif->fps, every speed produced (frames × delay) =
         * (source_duration), so all GIFs played for the same wall clock.
         * The user saw "no speed difference between medium and very
         * fast" because there genuinely wasn't one. */
        gif->filtered_frame->pts =
                av_rescale(gif->out_frame_count, 100, gif->fps);
        gif->out_frame_count++;

        if ((ret = gifmaker_drain_encoder(gif, gif->filtered_frame)) < 0) {
            av_frame_unref(gif->filtered_frame);
            return ret;
        }

        av_frame_unref(gif->filtered_frame);
    }
}


static int gifmaker_decode_packet(JNIEnv *env, struct GifMaker *gif, AVPacket *pkt) {

    int ret;
    int64_t frame_pts_us;

    ret = avcodec_send_packet(gif->input_codec_ctx, pkt);
    if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
        LOGE(1, "gifmaker_decode_packet send_packet failed (%d)", ret);
        return -ERROR_WHILE_DECODING;
    }

    while (1) {
        if (gif->interrupt) return -ERROR_INTERRUPTED;

        ret = avcodec_receive_frame(gif->input_codec_ctx, gif->decoded_frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            return ERROR_NO_ERROR;
        }
        if (ret < 0) {
            LOGE(1, "gifmaker_decode_packet receive_frame failed (%d)", ret);
            return -ERROR_WHILE_DECODING;
        }

        if (gif->decoded_frame->pts == AV_NOPTS_VALUE) {
            gif->decoded_frame->pts = gif->decoded_frame->best_effort_timestamp;
        }

        frame_pts_us = av_rescale_q(gif->decoded_frame->pts,
                                    gif->input_stream->time_base, AV_TIME_BASE_Q);

        /* Drop frames before the requested start. We seek to the nearest
         * keyframe before start_us, so a few pre-start frames will arrive
         * here. */
        if (frame_pts_us + AV_TIME_BASE / gif->fps < gif->start_us) {
            av_frame_unref(gif->decoded_frame);
            continue;
        }

        /* Stop as soon as we cross the requested end. The rest of the
         * input is irrelevant; the caller will close the input cleanly. */
        if (gif->end_us > 0 && frame_pts_us >= gif->end_us) {
            av_frame_unref(gif->decoded_frame);
            return AVERROR_EOF;
        }

        ret = av_buffersrc_add_frame_flags(gif->buffersrc_ctx, gif->decoded_frame,
                                           AV_BUFFERSRC_FLAG_KEEP_REF);
        av_frame_unref(gif->decoded_frame);

        /* palettegen swallows every frame and emits nothing until EOF,
         * so the filter-drain path below produces no output during decode.
         * Drive the progress bar from the input side here — that's where
         * the real time is being spent anyway. */
        gifmaker_publish_progress(env, gif, frame_pts_us, PROGRESS_PHASE_DECODE);

        if (ret < 0) {
            LOGE(1, "gifmaker_decode_packet buffersrc_add_frame failed (%d)", ret);
            return -ERROR_WHILE_FILTERING;
        }

        ret = gifmaker_drain_filter(env, gif);
        if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
            return ret;
        }
    }
}


static int gifmaker_run(JNIEnv *env, struct GifMaker *gif) {

    int ret;
    int err = ERROR_NO_ERROR;
    AVPacket *pkt = gif->dec_packet;

    if (gif->thiz != NULL) {
        (*env)->CallVoidMethod(env, gif->thiz, gif->gifmaker_on_started_method);
        /* [BUG FIX] Clear pending exception so subsequent JNI calls
         * (avformat_write_header itself doesn't, but the encode loop
         * has many) don't abort. */
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE(1, "gifmaker exception in started callback");
        }
    }

    if (avformat_write_header(gif->output_format_ctx, NULL) < 0) {
        LOGE(1, "gifmaker_run write_header failed");
        return -ERROR_COULD_NOT_WRITE_HEADER;
    }
    gif->header_written = 1;

    if (gif->start_us > 0) {
        int64_t seek_target = av_rescale_q(gif->start_us, AV_TIME_BASE_Q,
                                           gif->input_stream->time_base);
        if (av_seek_frame(gif->input_format_ctx, gif->video_stream_index,
                          seek_target, AVSEEK_FLAG_BACKWARD) < 0) {
            LOGW(1, "gifmaker_run seek failed; starting from 0");
        } else {
            avcodec_flush_buffers(gif->input_codec_ctx);
        }
    }

    while (1) {
        if (gif->interrupt) {
            err = -ERROR_INTERRUPTED;
            goto end;
        }

        ret = av_read_frame(gif->input_format_ctx, pkt);
        if (ret == AVERROR_EOF) {
            break;
        }
        if (ret < 0) {
            LOGE(1, "gifmaker_run read_frame failed (%d)", ret);
            err = -ERROR_WHILE_DECODING;
            goto end;
        }

        if (pkt->stream_index != gif->video_stream_index) {
            av_packet_unref(pkt);
            continue;
        }

        ret = gifmaker_decode_packet(env, gif, pkt);
        av_packet_unref(pkt);

        if (ret == AVERROR_EOF) {
            /* Reached requested end_us — stop reading, flush below. */
            break;
        }
        if (ret < 0) {
            err = ret;
            goto end;
        }
    }

    /* Flush decoder. */
    if ((ret = gifmaker_decode_packet(env, gif, NULL)) < 0 && ret != AVERROR_EOF) {
        err = ret;
        goto end;
    }

    /* Flush filter — this triggers palettegen to emit its palette and
     * paletteuse to consume it, finally producing PAL8 frames. */
    if ((ret = av_buffersrc_add_frame_flags(gif->buffersrc_ctx, NULL, 0)) < 0) {
        LOGE(1, "gifmaker_run flush buffersrc failed (%d)", ret);
        err = -ERROR_WHILE_FILTERING;
        goto end;
    }

    ret = gifmaker_drain_filter(env, gif);
    if (ret < 0 && ret != AVERROR_EOF && ret != AVERROR(EAGAIN)) {
        err = ret;
        goto end;
    }

    /* Flush encoder. */
    if ((ret = gifmaker_drain_encoder(gif, NULL)) < 0) {
        err = ret;
        goto end;
    }

    end:
    if (gif->header_written && err >= 0) {
        av_write_trailer(gif->output_format_ctx);
    }

    if (err >= 0 && gif->thiz != NULL) {
        int64_t total = gif->end_us > gif->start_us ? gif->end_us - gif->start_us
                                                    : gif->duration_us;
        (*env)->CallVoidMethod(env, gif->thiz, gif->gifmaker_on_progress_method,
                               (jlong) total, (jlong) total);
        /* [BUG FIX] Each Java callback needs its own exception check.
         * Without one between progress and finished, a throw in
         * progress would make the finished call abort with
         * "JNI ERROR (app bug): accessed stale Object". */
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE(1, "gifmaker exception in finish-progress callback");
        }
        (*env)->CallVoidMethod(env, gif->thiz, gif->gifmaker_on_finished_method);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            LOGE(1, "gifmaker exception in finished callback");
        }
    }

    return err;
}


/* ---------------- JNI entry points ---------------- */

int jni_gifmaker_init(JNIEnv *env, jobject thiz) {

    jclass clazz = NULL;
    struct GifMaker *gif = NULL;
    jfieldID m_native_field = NULL;
    int err = ERROR_NO_ERROR;
    int mutex_inited = FALSE;

    gif = calloc(1, sizeof(struct GifMaker));
    if (gif == NULL) {
        err = -ERROR_COULD_NOT_ALLOCATE_GIFMAKER;
        goto end;
    }

    gif->video_stream_index = -1;

    if ((*env)->GetJavaVM(env, &gif->get_javavm) != 0) {
        err = -ERROR_COULD_NOT_GET_JAVA_VM;
        goto free_gif;
    }

    gif->thiz = (*env)->NewGlobalRef(env, thiz);
    if (gif->thiz == NULL) {
        err = -ERROR_COULD_NOT_CREATE_GLOBAL_REF;
        goto free_gif;
    }

    m_native_field = java_get_field(env, gifmaker_runnable_class_path_name, gifmaker_m_native);
    if (m_native_field == NULL) {
        err = -ERROR_NOT_FOUND_M_NATIVE_FIELD;
        goto free_gif;
    }

    clazz = (*env)->FindClass(env, gifmaker_runnable_class_path_name);
    if (clazz == NULL) {
        err = -ERROR_NOT_FOUND_GIFMAKER_CLASS;
        goto free_gif;
    }

    gif->gifmaker_on_progress_method = java_get_method(env, clazz, gifMakerProgress);
    if (gif->gifmaker_on_progress_method == NULL) {
        err = -ERROR_NOT_FOUND_PROGRESS_METHOD;
        goto free_gif;
    }

    gif->gifmaker_on_started_method = java_get_method(env, clazz, gifMakerStarted);
    if (gif->gifmaker_on_started_method == NULL) {
        err = -ERROR_NOT_FOUND_STARTED_METHOD;
        goto free_gif;
    }

    gif->gifmaker_on_finished_method = java_get_method(env, clazz, gifMakerFinished);
    if (gif->gifmaker_on_finished_method == NULL) {
        err = -ERROR_NOT_FOUND_FINISHED_METHOD;
        goto free_gif;
    }

    (*env)->DeleteLocalRef(env, clazz);
    clazz = NULL;

    if (pthread_mutex_init(&gif->mutex_operation, NULL) != 0) {
        err = -ERROR_COULD_NOT_INIT_PTHREAD_ATTR;
        goto free_gif;
    }
    mutex_inited = TRUE;

    av_log_set_callback(utils_ffmpeg_log_callback);
    av_log_set_level(FFMPEG_LOG_LEVEL);

    /* Publish only after every fallible step succeeded — same defensive
     * pattern as encoder.c so a failed init never leaves the Java field
     * pointing at half-built state. */
    (*env)->SetLongField(env, thiz, m_native_field, (jlong) gif);
    goto end;

    free_gif:
    if (mutex_inited) pthread_mutex_destroy(&gif->mutex_operation);
    if (gif->thiz != NULL) (*env)->DeleteGlobalRef(env, gif->thiz);
    if (clazz != NULL) (*env)->DeleteLocalRef(env, clazz);
    free(gif);

    end:
    return err;
}


static struct GifMaker *get_gifmaker_field(JNIEnv *env, jobject thiz) {
    jfieldID f = java_get_field(env, gifmaker_runnable_class_path_name, gifmaker_m_native);
    return (struct GifMaker *) (*env)->GetLongField(env, thiz, f);
}


static void clean_gifmaker_field(JNIEnv *env, jobject thiz) {
    jfieldID f = java_get_field(env, gifmaker_runnable_class_path_name, gifmaker_m_native);
    (*env)->SetLongField(env, thiz, f, 0);
}


int jni_gifmaker_start(JNIEnv *env, jobject thiz, jstring filePath, jstring outputPath,
                       jlong startMs, jlong endMs, jint fps, jint width) {

    struct GifMaker *gif = get_gifmaker_field(env, thiz);
    const char *file_path = NULL;
    const char *output_path = NULL;
    int ret = ERROR_NO_ERROR;
    int locked = FALSE;

    LOGI(1, "jni_gifmaker_start fps=%d width=%d start=%" PRId64 " end=%" PRId64,
         fps, width, (int64_t) startMs, (int64_t) endMs);

    if (gif == NULL) {
        LOGW(1, "jni_gifmaker_start already freed");
        return -ERROR_COULD_NOT_ALLOCATE_GIFMAKER;
    }

    if (filePath == NULL || outputPath == NULL) {
        LOGE(1, "jni_gifmaker_start NULL paths");
        return -ERROR_COULD_NOT_INIT_PATHS;
    }

    file_path = (*env)->GetStringUTFChars(env, filePath, NULL);
    output_path = (*env)->GetStringUTFChars(env, outputPath, NULL);

    if (file_path == NULL || output_path == NULL) {
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    pthread_mutex_lock(&gif->mutex_operation);
    locked = TRUE;

    /* Reset transient state in case the caller is reusing the encoder. */
    gifmaker_release_pipeline(gif);
    gif->interrupt = FALSE;
    gif->last_progress_us = 0;
    gif->out_frame_count = 0;

    gif->fps = fps > 0 ? fps : DEFAULT_FPS;
    if (gif->fps > MAX_FPS) gif->fps = MAX_FPS;
    gif->width = width;
    gif->start_us = startMs > 0 ? (int64_t) startMs * 1000ll : 0;
    gif->end_us = endMs > 0 ? (int64_t) endMs * 1000ll : 0;

    /* Defence in depth: the UI already blocks ranges below MIN_TRIM_MS
     * (200 ms), but a stale intent or a future caller could ask for a
     * zero-length / negative range. palettegen needs at least one frame
     * to emit a palette; without that the GIF muxer writes a header and
     * trailer with no frames between them, and the resulting file fails
     * to load in every viewer. Reject before we open any contexts. */
    if (gif->end_us > 0 && gif->end_us - gif->start_us < 100000ll) {
        LOGE(1, "jni_gifmaker_start trim range too short start=%" PRId64
                " end=%" PRId64, gif->start_us, gif->end_us);
        ret = -ERROR_COULD_NOT_INIT_PATHS;
        goto end;
    }

    if ((ret = gifmaker_open_input(gif, file_path)) < 0) goto end;
    if (gif->interrupt) { ret = -ERROR_INTERRUPTED; goto end; }

    gif->duration_us = gif->input_format_ctx->duration > 0
                       ? gif->input_format_ctx->duration : 0;

    if ((ret = gifmaker_open_decoder(gif)) < 0) goto end;
    if (gif->interrupt) { ret = -ERROR_INTERRUPTED; goto end; }
    if ((ret = gifmaker_open_output(gif, output_path)) < 0) goto end;
    if (gif->interrupt) { ret = -ERROR_INTERRUPTED; goto end; }
    if ((ret = gifmaker_init_filters(gif)) < 0) goto end;
    if (gif->interrupt) { ret = -ERROR_INTERRUPTED; goto end; }
    if ((ret = gifmaker_alloc_frames(gif)) < 0) goto end;
    if (gif->interrupt) { ret = -ERROR_INTERRUPTED; goto end; }

    ret = gifmaker_run(env, gif);

    end:
    if (file_path != NULL) (*env)->ReleaseStringUTFChars(env, filePath, file_path);
    if (output_path != NULL) (*env)->ReleaseStringUTFChars(env, outputPath, output_path);

    /* Release pipeline regardless of outcome — the file_path/output_path
     * fields inside the format contexts are no longer needed and the Java
     * side will likely call free() next. */
    if (gif != NULL) {
        gifmaker_release_pipeline(gif);
    }

    if (locked) {
        pthread_mutex_unlock(&gif->mutex_operation);
    }

    return ret;
}


void jni_gifmaker_interrupt(JNIEnv *env, jobject thiz) {

    struct GifMaker *gif = get_gifmaker_field(env, thiz);
    if (gif == NULL) {
        LOGI(4, "jni_gifmaker_interrupt already freed");
        return;
    }
    LOGI(3, "jni_gifmaker_interrupt");
    gif->interrupt = TRUE;
}


void jni_gifmaker_stop(JNIEnv *env, jobject thiz) {

    struct GifMaker *gif = get_gifmaker_field(env, thiz);
    if (gif == NULL) {
        LOGW(1, "jni_gifmaker_stop already freed");
        return;
    }

    LOGI(1, "jni_gifmaker_stop");
    gif->interrupt = TRUE;

    /* Wait for any in-progress start() to bail out, then tear down the
     * pipeline. Same locking discipline as encoder.c — interrupt first
     * (this unblocks the FFmpeg interrupt callback so the start thread
     * exits its read loop), then take mutex_operation. */
    pthread_mutex_lock(&gif->mutex_operation);
    gifmaker_release_pipeline(gif);
    pthread_mutex_unlock(&gif->mutex_operation);
}


void jni_gifmaker_dealloc(JNIEnv *env, jobject thiz) {

    struct GifMaker *gif = get_gifmaker_field(env, thiz);
    if (gif == NULL) {
        LOGI(4, "jni_gifmaker_dealloc already freed");
        return;
    }

    LOGI(1, "jni_gifmaker_dealloc");

    /* Same fix that encoder.c carries: interrupt → take operation lock
     * (joins anyone still inside start) → release pipeline → drop the
     * Java field BEFORE destroying the mutex / freeing the struct, so
     * any concurrent JNI call sees NULL and bails. */
    gif->interrupt = TRUE;

    pthread_mutex_lock(&gif->mutex_operation);
    gifmaker_release_pipeline(gif);

    if (gif->thiz != NULL) {
        (*env)->DeleteGlobalRef(env, gif->thiz);
        gif->thiz = NULL;
    }

    clean_gifmaker_field(env, thiz);

    pthread_mutex_unlock(&gif->mutex_operation);
    pthread_mutex_destroy(&gif->mutex_operation);

    free(gif);

    LOGI(1, "jni_gifmaker_dealloc end");
}
