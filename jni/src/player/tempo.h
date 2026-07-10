#ifndef DASHCHAN_TEMPO_H
#define DASHCHAN_TEMPO_H

#include <stdint.h>

typedef struct TempoProcessor TempoProcessor;

TempoProcessor * tempoProcessorCreate(int sampleRate, int channels, int speed);
void tempoProcessorFree(TempoProcessor ** processor);
int tempoProcessorPush(TempoProcessor * processor, const uint8_t * buffer, int samples);
int tempoProcessorFinish(TempoProcessor * processor);
int tempoProcessorPull(TempoProcessor * processor, uint8_t ** buffer, int * size, int * samples);

#endif
