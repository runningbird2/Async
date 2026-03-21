package com.axalotl.async.common.spawn;

import net.minecraft.world.level.ChunkPos;

import java.util.Objects;

/**
 * Immutable per-chunk commit unit prepared off-thread and later resolved back to a live chunk on the main thread.
 */
public record AsyncSpawnChunkPlan(int sequence, long chunkPos, CommitMode commitMode) {

    public AsyncSpawnChunkPlan {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        commitMode = Objects.requireNonNull(commitMode, "commitMode");
    }

    public int chunkX() {
        return ChunkPos.getX(this.chunkPos);
    }

    public int chunkZ() {
        return ChunkPos.getZ(this.chunkPos);
    }

    public enum CommitMode {
        /**
         * Defer to the existing synchronous chunk-level natural spawning path during commit.
         */
        PASS_THROUGH
    }
}
