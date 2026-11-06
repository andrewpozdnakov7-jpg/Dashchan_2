#ifndef PLAYER_UTIL_H
#define PLAYER_UTIL_H

#include <pthread.h>
#include <stdint.h>
#include <android/log.h>

#define UNUSED __attribute__((unused))

#ifdef DEBUG_VERBOSE
#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "Player", __VA_ARGS__)
#else
#define LOG(...)
#endif
#define LOGP(...) __android_log_print(ANDROID_LOG_DEBUG, "Player", __VA_ARGS__)

typedef struct QueueItem QueueItem;
typedef struct Queue Queue;

struct QueueItem {
	void * data;
	QueueItem * next;
};

struct Queue {
	QueueItem * first;
	QueueItem * last;
	int count;
};

void queueInit(Queue * queue);
void queueClear(Queue * queue, void callback(void * data));
void queueDestroy(Queue * queue, void callback(void * data));
int queueAdd(Queue * queue, void * data);
void * queueGet(Queue * queue);

typedef struct BlockingQueue BlockingQueue;

struct BlockingQueue {
	Queue queue;
	int interrupted;
	pthread_cond_t cond;
	pthread_mutex_t mutex;
};

void blockingQueueInit(BlockingQueue * blockingQueue);
void blockingQueueInterrupt(BlockingQueue * blockingQueue);
void blockingQueueClear(BlockingQueue * blockingQueue, void callback(void * data));
void blockingQueueDestroy(BlockingQueue * blockingQueue, void callback(void * data));
int blockingQueueAdd(BlockingQueue * blockingQueue, void * data);
void * blockingQueueGet(BlockingQueue * blockingQueue, int wait);
int blockingQueueCount(BlockingQueue * blockingQueue);

typedef struct BufferItem BufferItem;
typedef struct BufferQueue BufferQueue;

struct BufferItem {
	QueueItem queueItem; // Reused between free/busy queues; no per-frame allocation.
	uint8_t * buffer;
	int bufferSize;
	int dataSize;
	void * extra;
};

struct BufferQueue {
	Queue freeQueue;
	Queue busyQueue;
	int bufferSize;
};

int bufferQueueInit(BufferQueue * bufferQueue, int bufferSize, int maxCount);
void bufferQueueClear(BufferQueue * bufferQueue, void callback(BufferItem * bufferItem));
void bufferQueueDestroy(BufferQueue * bufferQueue, void callback(BufferItem * bufferItem));
void bufferQueueExtend(BufferQueue * bufferQueue, int bufferSize);
BufferItem * bufferQueuePrepare(BufferQueue * bufferQueue, int * allocationFailed);
void bufferQueueAdd(BufferQueue * bufferQueue, BufferItem * bufferItem);
BufferItem * bufferQueueSeize(BufferQueue * bufferQueue);
void bufferQueueRelease(BufferQueue * bufferQueue, BufferItem * bufferItem);
int bufferQueueCount(BufferQueue * bufferQueue);

int64_t getTime(void);
int64_t getTimeUs(void);

void condBroadcastLocked(pthread_cond_t * cond, pthread_mutex_t * mutex);
int condSleepUntilMs(pthread_cond_t * cond, pthread_mutex_t * mutex, int64_t timeMs);

int32_t min32(int32_t a, int32_t b);
int64_t min64(int64_t a, int64_t b);
int64_t max64(int64_t a, int64_t b);

#endif // PLAYER_UTIL_H
