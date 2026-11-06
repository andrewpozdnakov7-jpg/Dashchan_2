#include "util.h"

#include <stdlib.h>
#include <string.h>

void queueInit(Queue * queue) {
	memset(queue, 0, sizeof(Queue));
}

void queueClear(Queue * queue, void callback(void * data)) {
	QueueItem * queueItem = queue->first;
	while (queueItem) {
		if (callback && queueItem->data) {
			callback(queueItem->data);
		}
		QueueItem * nextQueueItem = queueItem->next;
		free(queueItem);
		queueItem = nextQueueItem;
	}
	queue->first = NULL;
	queue->last = NULL;
	queue->count = 0;
}

int queueAdd(Queue * queue, void * data) {
	QueueItem * queueItem = malloc(sizeof(QueueItem));
	if (!queueItem) return 0;
	queueItem->data = data;
	queueItem->next = NULL;
	if (queue->last) {
		queue->last->next = queueItem;
	} else {
		queue->first = queueItem;
	}
	queue->last = queueItem;
	queue->count++;
	return 1;
}

void * queueGet(Queue * queue) {
	void * data = NULL;
	QueueItem * queueItem = queue->first;
	if (queueItem) {
		queue->first = queueItem->next;
		if (!queue->first) {
			queue->last = NULL;
		}
		queue->count--;
		data = queueItem->data;
		free(queueItem);
	}
	return data;
}

void blockingQueueInit(BlockingQueue * blockingQueue) {
	memset(blockingQueue, 0, sizeof(BlockingQueue));
	queueInit(&blockingQueue->queue);
	pthread_cond_init(&blockingQueue->cond, NULL);
	pthread_mutex_init(&blockingQueue->mutex, NULL);
}

void blockingQueueInterrupt(BlockingQueue * blockingQueue) {
	pthread_mutex_lock(&blockingQueue->mutex);
	blockingQueue->interrupted = 1;
	pthread_cond_broadcast(&blockingQueue->cond);
	pthread_mutex_unlock(&blockingQueue->mutex);
}

void blockingQueueClear(BlockingQueue * blockingQueue, void callback(void * data)) {
	pthread_mutex_lock(&blockingQueue->mutex);
	queueClear(&blockingQueue->queue, callback);
	pthread_mutex_unlock(&blockingQueue->mutex);
}

void blockingQueueDestroy(BlockingQueue * blockingQueue, void callback(void * data)) {
	blockingQueueClear(blockingQueue, callback);
	pthread_cond_destroy(&blockingQueue->cond);
	pthread_mutex_destroy(&blockingQueue->mutex);
}

int blockingQueueAdd(BlockingQueue * blockingQueue, void * data) {
	pthread_mutex_lock(&blockingQueue->mutex);
	int added = queueAdd(&blockingQueue->queue, data);
	pthread_cond_broadcast(&blockingQueue->cond);
	pthread_mutex_unlock(&blockingQueue->mutex);
	return added;
}

void * blockingQueueGet(BlockingQueue * blockingQueue, int wait) {
	void * data = NULL;
	pthread_mutex_lock(&blockingQueue->mutex);
	while (!blockingQueue->interrupted) {
		if (blockingQueue->queue.first) {
			data = queueGet(&blockingQueue->queue);
			break;
		} else if (wait) {
			pthread_cond_wait(&blockingQueue->cond, &blockingQueue->mutex);
		} else {
			break;
		}
	}
	pthread_mutex_unlock(&blockingQueue->mutex);
	return data;
}

int blockingQueueCount(BlockingQueue * blockingQueue) {
	return blockingQueue->queue.count;
}

static void bufferQueuePut(Queue * queue, BufferItem * item) {
	QueueItem * node = &item->queueItem;
	node->data = item;
	node->next = NULL;
	if (queue->last) queue->last->next = node;
	else queue->first = node;
	queue->last = node;
	queue->count++;
}

static BufferItem * bufferQueueTake(Queue * queue) {
	QueueItem * node = queue->first;
	if (!node) return NULL;
	queue->first = node->next;
	if (!queue->first) queue->last = NULL;
	queue->count--;
	node->next = NULL;
	return node->data;
}

int bufferQueueInit(BufferQueue * bufferQueue, int bufferSize, int maxCount) {
	queueInit(&bufferQueue->freeQueue);
	queueInit(&bufferQueue->busyQueue);
	bufferQueue->bufferSize = bufferSize;
	if (bufferSize <= 0 || maxCount <= 0) return 0;
	for (int i = 0; i < maxCount; i++) {
		BufferItem * bufferItem = malloc(sizeof(BufferItem));
		uint8_t * buffer = bufferItem ? malloc(bufferSize) : NULL;
		if (!bufferItem || !buffer) {
			free(bufferItem);
			bufferQueueDestroy(bufferQueue, NULL);
			return 0;
		}
		bufferItem->buffer = buffer;
		bufferItem->bufferSize = bufferSize;
		bufferItem->dataSize = 0;
		bufferItem->extra = NULL;
		bufferQueuePut(&bufferQueue->freeQueue, bufferItem);
	}
	return 1;
}

void bufferQueueClear(BufferQueue * bufferQueue, void callback(BufferItem * bufferItem)) {
	while (1) {
		BufferItem * bufferItem = bufferQueueTake(&bufferQueue->busyQueue);
		if (!bufferItem) {
			break;
		}
		if (callback) {
			callback(bufferItem);
		}
		bufferQueuePut(&bufferQueue->freeQueue, bufferItem);
	}
}

void bufferQueueDestroy(BufferQueue * bufferQueue, void callback(BufferItem * bufferItem)) {
	bufferQueueClear(bufferQueue, callback);
	BufferItem * item;
	while ((item = bufferQueueTake(&bufferQueue->freeQueue))) {
		free(item->buffer);
		free(item);
	}
	bufferQueue->bufferSize = 0;
}

void bufferQueueExtend(BufferQueue * bufferQueue, int bufferSize)
{
	if (bufferSize > bufferQueue->bufferSize) {
		bufferQueue->bufferSize = bufferSize;
	}
}

BufferItem * bufferQueuePrepare(BufferQueue * bufferQueue, int * allocationFailed) {
	*allocationFailed = 0;
	BufferItem * bufferItem = bufferQueue->freeQueue.first ? bufferQueue->freeQueue.first->data : NULL;
	if (bufferItem && bufferItem->bufferSize < bufferQueue->bufferSize) {
		uint8_t * buffer = realloc(bufferItem->buffer, bufferQueue->bufferSize);
		if (!buffer) {
			*allocationFailed = 1;
			return NULL; // Keep the old allocation and its queue node for a later retry.
		}
		bufferItem->buffer = buffer;
		bufferItem->bufferSize = bufferQueue->bufferSize;
	}
	if (bufferItem) {
		bufferQueueTake(&bufferQueue->freeQueue);
		bufferItem->dataSize = 0;
	}
	return bufferItem;
}

void bufferQueueAdd(BufferQueue * bufferQueue, BufferItem * bufferItem) {
	bufferQueuePut(&bufferQueue->busyQueue, bufferItem);
}

BufferItem * bufferQueueSeize(BufferQueue * bufferQueue) {
	return bufferQueueTake(&bufferQueue->busyQueue);
}

void bufferQueueRelease(BufferQueue * bufferQueue, BufferItem * bufferItem) {
	bufferQueuePut(&bufferQueue->freeQueue, bufferItem);
}

int bufferQueueCount(BufferQueue * bufferQueue) {
	return bufferQueue->busyQueue.count;
}

int64_t getTime(void) {
	struct timeval now;
	gettimeofday(&now, NULL);
	return (int64_t) now.tv_sec * 1000 + now.tv_usec / 1000;
}

int64_t getTimeUs(void) {
	struct timeval now;
	gettimeofday(&now, NULL);
	return (int64_t) now.tv_sec * 1000000 + now.tv_usec;
}

void condBroadcastLocked(pthread_cond_t * cond, pthread_mutex_t * mutex) {
	pthread_mutex_lock(mutex);
	pthread_cond_broadcast(cond);
	pthread_mutex_unlock(mutex);
}

int condSleepUntilMs(pthread_cond_t * cond, pthread_mutex_t * mutex, int64_t timeMs) {
	struct timespec wait;
	wait.tv_sec = timeMs / 1000;
	wait.tv_nsec = (timeMs % 1000) * 1000000;
	pthread_cond_timedwait(cond, mutex, &wait);
	struct timeval now;
	gettimeofday(&now, NULL);
	int64_t nowMs = (int64_t) now.tv_sec * 1000 + (now.tv_usec + 999) / 1000;
	return nowMs >= timeMs;
}

int32_t min32(int32_t a, int32_t b) {
	return a < b ? a : b;
}

int64_t min64(int64_t a, int64_t b) {
	return a < b ? a : b;
}

int64_t max64(int64_t a, int64_t b) {
	return a > b ? a : b;
}
