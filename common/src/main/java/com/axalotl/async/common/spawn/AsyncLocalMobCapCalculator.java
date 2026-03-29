package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

public interface AsyncLocalMobCapCalculator {

    void async$applyChunkCounts(Long2ObjectMap<int[]> chunkMobCounts);

    int async$getMobCount(ServerPlayer player, MobCategory category);

    int async$getMobHeadroom(ServerPlayer player, MobCategory category);

    int async$getMinMobHeadroom(MobCategory category, ChunkPos chunkPos);

    default int async$getMobLimit(MobCategory category) {
        return category.getMaxInstancesPerChunk();
    }
}
