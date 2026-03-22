package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public final class AsyncPreparedFullChunkSnapshot {

    private final Long2ObjectOpenHashMap<LevelChunk> chunks;

    public AsyncPreparedFullChunkSnapshot(Long2ObjectMap<LevelChunk> chunks) {
        this.chunks = new Long2ObjectOpenHashMap<>(Objects.requireNonNull(chunks, "chunks"));
    }

    public @Nullable LevelChunk get(long chunkPosLong) {
        return this.chunks.get(chunkPosLong);
    }
}
