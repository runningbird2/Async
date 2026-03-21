package com.axalotl.async.common.spawn;

/**
 * Summary of main-thread plan application.
 */
public record AsyncSpawnCommitResult(int plannedChunks, int committedChunks, int skippedChunks) {

    public AsyncSpawnCommitResult {
        if (plannedChunks < 0) {
            throw new IllegalArgumentException("plannedChunks must be >= 0");
        }
        if (committedChunks < 0) {
            throw new IllegalArgumentException("committedChunks must be >= 0");
        }
        if (skippedChunks < 0) {
            throw new IllegalArgumentException("skippedChunks must be >= 0");
        }
        if (committedChunks + skippedChunks != plannedChunks) {
            throw new IllegalArgumentException("committedChunks + skippedChunks must equal plannedChunks");
        }
    }

    public boolean fullyCommitted() {
        return this.skippedChunks == 0;
    }
}
