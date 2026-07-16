package com.mishiranu.dashchan.chan.fourchan;

import java.util.LinkedHashMap;
import java.util.Map;

class ThreadsWithTailCache {
    static final ThreadsWithTailCache INSTANCE = new ThreadsWithTailCache();

    private static final Object VALUE = new Object();

    private final int MAXIMUM_CAPACITY = 20;

    // actually unused because removeEldestEntry will remove before resize capacity is reached
    private final float LOAD_FACTOR = 1F;

    private final LinkedHashMap<String, Object> cache =
            new LinkedHashMap<String, Object>(MAXIMUM_CAPACITY, LOAD_FACTOR, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > MAXIMUM_CAPACITY;
        }
    };

    private ThreadsWithTailCache() {
    }

    synchronized boolean contains(String threadNumber) {
        return cache.get(threadNumber) != null;
    }

    synchronized void add(String threadNumber) {
        cache.put(threadNumber, VALUE);
    }

}
