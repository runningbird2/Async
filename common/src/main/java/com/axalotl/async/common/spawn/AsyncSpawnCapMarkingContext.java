package com.axalotl.async.common.spawn;

public final class AsyncSpawnCapMarkingContext {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AsyncSpawnCapMarkingContext() {
    }

    public static void push() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void pop() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static Scope enter() {
        push();
        return Scope.INSTANCE;
    }

    public enum Scope implements AutoCloseable {
        INSTANCE;

        @Override
        public void close() {
            pop();
        }
    }
}
