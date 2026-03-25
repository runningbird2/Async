package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

public interface AsyncLocalMobCapCalculator {

    void async$applyChunkCounts(Long2ObjectMap<int[]> chunkMobCounts);

    int async$getMobCount(net.minecraft.server.level.ServerPlayer player, MobCategory category);

    int async$getMobHeadroom(net.minecraft.server.level.ServerPlayer player, MobCategory category);

    int async$getMinMobHeadroom(MobCategory category, ChunkPos chunkPos);
}
