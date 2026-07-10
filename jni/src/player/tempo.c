#include "tempo.h"

#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>

#include <errno.h>
#include <stdio.h>
#include <string.h>

struct TempoProcessor {
	AVFilterGraph * graph;
	AVFilterContext * source;
	AVFilterContext * sink;
	AVFrame * outputFrame;
	int sampleRate;
	int channels;
	int64_t inputSamples;
	int finished;
};

static int addTempoFilter(TempoProcessor * processor, AVFilterContext ** previous,
		double factor, int index) {
	const AVFilter * atempo = avfilter_get_by_name("atempo");
	if (!atempo) {
		return AVERROR_FILTER_NOT_FOUND;
	}
	char name[24];
	char options[32];
	snprintf(name, sizeof(name), "atempo%d", index);
	snprintf(options, sizeof(options), "tempo=%.8f", factor);
	AVFilterContext * filter = NULL;
	int result = avfilter_graph_create_filter(&filter, atempo,
			name, options, NULL, processor->graph);
	if (result >= 0) {
		result = avfilter_link(*previous, 0, filter, 0);
	}
	if (result >= 0) {
		*previous = filter;
	}
	return result;
}

TempoProcessor * tempoProcessorCreate(int sampleRate, int channels, int speed) {
	if (sampleRate <= 0 || (channels != 1 && channels != 2) || speed <= 0 || speed == 1000) {
		return NULL;
	}
	TempoProcessor * processor = av_mallocz(sizeof(TempoProcessor));
	if (!processor) {
		return NULL;
	}
	processor->sampleRate = sampleRate;
	processor->channels = channels;
	processor->graph = avfilter_graph_alloc();
	processor->outputFrame = av_frame_alloc();
	if (!processor->graph || !processor->outputFrame) {
		tempoProcessorFree(&processor);
		return NULL;
	}

	char sourceOptions[192];
	snprintf(sourceOptions, sizeof(sourceOptions),
			"time_base=1/%d:sample_rate=%d:sample_fmt=s16:channel_layout=%s",
			sampleRate, sampleRate, channels == 2 ? "stereo" : "mono");
	const AVFilter * abuffer = avfilter_get_by_name("abuffer");
	const AVFilter * abuffersink = avfilter_get_by_name("abuffersink");
	if (!abuffer || !abuffersink) {
		tempoProcessorFree(&processor);
		return NULL;
	}
	int result = avfilter_graph_create_filter(&processor->source, abuffer,
			"source", sourceOptions, NULL, processor->graph);
	if (result < 0) {
		tempoProcessorFree(&processor);
		return NULL;
	}
	result = avfilter_graph_create_filter(&processor->sink, abuffersink,
			"sink", NULL, NULL, processor->graph);
	if (result < 0) {
		tempoProcessorFree(&processor);
		return NULL;
	}

	AVFilterContext * previous = processor->source;
	double remaining = speed / 1000.0;
	int index = 0;
	while (result >= 0 && remaining > 2.0) {
		result = addTempoFilter(processor, &previous, 2.0, index++);
		remaining /= 2.0;
	}
	while (result >= 0 && remaining < 0.5) {
		result = addTempoFilter(processor, &previous, 0.5, index++);
		remaining /= 0.5;
	}
	if (result >= 0) {
		result = addTempoFilter(processor, &previous, remaining, index);
	}
	if (result >= 0) {
		result = avfilter_link(previous, 0, processor->sink, 0);
	}
	if (result >= 0) {
		result = avfilter_graph_config(processor->graph, NULL);
	}
	if (result < 0) {
		tempoProcessorFree(&processor);
		return NULL;
	}
	return processor;
}

void tempoProcessorFree(TempoProcessor ** processor) {
	if (processor && *processor) {
		TempoProcessor * value = *processor;
		av_frame_free(&value->outputFrame);
		avfilter_graph_free(&value->graph);
		av_free(value);
		*processor = NULL;
	}
}

int tempoProcessorPush(TempoProcessor * processor, const uint8_t * buffer, int samples) {
	if (!processor || !buffer || samples <= 0 || processor->finished) {
		return AVERROR(EINVAL);
	}
	AVFrame * frame = av_frame_alloc();
	if (!frame) {
		return AVERROR(ENOMEM);
	}
	frame->format = AV_SAMPLE_FMT_S16;
	frame->sample_rate = processor->sampleRate;
	frame->nb_samples = samples;
	frame->pts = processor->inputSamples;
	av_channel_layout_default(&frame->ch_layout, processor->channels);
	int result = av_frame_get_buffer(frame, 0);
	if (result >= 0) {
		int size = av_samples_get_buffer_size(NULL, processor->channels, samples, AV_SAMPLE_FMT_S16, 1);
		if (size < 0) {
			result = size;
		} else {
			memcpy(frame->data[0], buffer, size);
			result = av_buffersrc_add_frame_flags(processor->source, frame, 0);
		}
	}
	if (result >= 0) {
		processor->inputSamples += samples;
	}
	av_frame_free(&frame);
	return result;
}

int tempoProcessorFinish(TempoProcessor * processor) {
	if (!processor || processor->finished) {
		return 0;
	}
	int result = av_buffersrc_add_frame_flags(processor->source, NULL, 0);
	if (result >= 0) {
		processor->finished = 1;
	}
	return result;
}

int tempoProcessorPull(TempoProcessor * processor, uint8_t ** buffer, int * size, int * samples) {
	if (!processor || !buffer || !size || !samples) {
		return AVERROR(EINVAL);
	}
	*buffer = NULL;
	*size = 0;
	*samples = 0;
	av_frame_unref(processor->outputFrame);
	int result = av_buffersink_get_frame(processor->sink, processor->outputFrame);
	if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
		return 0;
	}
	if (result < 0) {
		return result;
	}
	AVFrame * frame = processor->outputFrame;
	if (frame->format != AV_SAMPLE_FMT_S16 || frame->ch_layout.nb_channels != processor->channels) {
		av_frame_unref(frame);
		return AVERROR(EINVAL);
	}
	int outputSize = av_samples_get_buffer_size(NULL, processor->channels,
			frame->nb_samples, AV_SAMPLE_FMT_S16, 1);
	if (outputSize < 0) {
		av_frame_unref(frame);
		return outputSize;
	}
	uint8_t * output = av_malloc(outputSize);
	if (!output) {
		av_frame_unref(frame);
		return AVERROR(ENOMEM);
	}
	memcpy(output, frame->data[0], outputSize);
	*buffer = output;
	*size = outputSize;
	*samples = frame->nb_samples;
	av_frame_unref(frame);
	return 1;
}
