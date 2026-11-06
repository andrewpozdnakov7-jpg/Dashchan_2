// Android/NDK test executable, separate from the APK. Allocation failure injection
// covers the production queue implementation. Not compiled or run in source-only mode.
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/time.h>

static int failAt = -1;
static int allocationIndex;
static int liveAllocations;

static int failAllocation(void) { return allocationIndex++ == failAt; }
static void * testMalloc(size_t size) {
	if (failAllocation()) return NULL;
	void * result = malloc(size);
	if (result) liveAllocations++;
	return result;
}
static void * testRealloc(void * pointer, size_t size) {
	if (failAllocation()) return NULL;
	int wasNull = pointer == NULL;
	void * result = realloc(pointer, size);
	if (result && wasNull) liveAllocations++;
	return result;
}
static void testFree(void * pointer) {
	if (pointer) liveAllocations--;
	free(pointer);
}

#define malloc testMalloc
#define realloc testRealloc
#define free testFree
#include "../src/player/util.c"
#undef malloc
#undef realloc
#undef free

static int cleaned;
static void cleanupExtra(BufferItem * item) {
	assert(item->extra == (void *) (uintptr_t) 1);
	item->extra = NULL;
	cleaned++;
}

int main(void) {
	Queue basic;
	queueInit(&basic);
	failAt = 0;
	assert(!queueAdd(&basic, (void *) (uintptr_t) 1));
	assert(basic.count == 0 && basic.first == NULL && basic.last == NULL);
	failAt = -1;
	assert(queueAdd(&basic, (void *) (uintptr_t) 1));
	assert(queueGet(&basic) == (void *) (uintptr_t) 1 && liveAllocations == 0);
	for (int failure = 0; failure < 6; failure++) {
		BufferQueue queue;
		failAt = failure;
		allocationIndex = 0;
		assert(!bufferQueueInit(&queue, 16, 3));
		assert(queue.freeQueue.count == 0 && queue.busyQueue.count == 0);
		assert(liveAllocations == 0);
		bufferQueueDestroy(&queue, NULL); // Failed initialization remains destroyable.
	}
	BufferQueue queue;
	failAt = -1;
	assert(bufferQueueInit(&queue, 16, 3));
	assert(liveAllocations == 6);
	BufferItem * first = queue.freeQueue.first->data;
	first->buffer[0] = 42;
	uint8_t * old = first->buffer;
	bufferQueueExtend(&queue, 32);
	failAt = allocationIndex;
	int failed;
	assert(bufferQueuePrepare(&queue, &failed) == NULL && failed);
	assert(first->buffer == old && first->bufferSize == 16 && old[0] == 42);
	assert(queue.freeQueue.count == 3 && liveAllocations == 6);
	failAt = -1;
	BufferItem * a = bufferQueuePrepare(&queue, &failed);
	assert(a == first && !failed && a->bufferSize == 32 && a->buffer[0] == 42);
	BufferItem * b = bufferQueuePrepare(&queue, &failed);
	BufferItem * c = bufferQueuePrepare(&queue, &failed);
	assert(b && c);
	assert(bufferQueuePrepare(&queue, &failed) == NULL && !failed);
	int count = allocationIndex;
	bufferQueueAdd(&queue, a);
	bufferQueueAdd(&queue, b);
	assert(bufferQueueSeize(&queue) == a);
	bufferQueueRelease(&queue, a);
	bufferQueueRelease(&queue, c);
	b->extra = (void *) (uintptr_t) 1;
	bufferQueueClear(&queue, cleanupExtra);
	assert(cleaned == 1 && queue.freeQueue.count == 3 && queue.busyQueue.count == 0);
	assert(allocationIndex == count); // Moving frames cannot allocate or fail under pressure.
	bufferQueueDestroy(&queue, NULL);
	assert(liveAllocations == 0);
	return 0;
}
