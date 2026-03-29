package com.axalotl.async.common;

public final class AsyncSpawnCacheMissException extends RuntimeException {
    private static final long NO_CHUNK_POS = Long.MIN_VALUE;

    private final String reason;
    private final long chunkPos;

    public AsyncSpawnCacheMissException() {
        this("unspecified", NO_CHUNK_POS);
    }

    public AsyncSpawnCacheMissException(String reason, long chunkPos) {
        super(null, null, false, false);
        this.reason = reason;
        this.chunkPos = chunkPos;
    }

    public String async$getReason() {
        return this.reason;
    }

    public long async$getChunkPos() {
        return this.chunkPos;
    }

    public boolean async$hasChunkPos() {
        return this.chunkPos != NO_CHUNK_POS;
    }
}
