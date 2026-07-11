package com.debugbridge.core.script;

import groovy.lang.Closure;
import java.util.ArrayDeque;
import java.util.Deque;

/** Per-lease LIFO cleanup stack exposed to Groovy as {@code cleanup}. */
public final class CleanupRegistry {
    private final Deque<Closure<?>> callbacks = new ArrayDeque<>();

    public synchronized int add(Closure<?> callback) {
        if (callback == null) throw new IllegalArgumentException("cleanup callback is required");
        callbacks.push(callback);
        return callbacks.size();
    }

    public synchronized int size() {
        return callbacks.size();
    }

    public void runAll() {
        while (true) {
            Closure<?> callback;
            synchronized (this) {
                callback = callbacks.pollFirst();
            }
            if (callback == null) return;
            try {
                callback.call();
            } catch (Throwable ignored) {
                // One failed cleanup must not prevent the remaining callbacks.
            }
        }
    }
}
