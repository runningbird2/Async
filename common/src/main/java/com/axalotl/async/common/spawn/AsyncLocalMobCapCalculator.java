package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public interface AsyncLocalMobCapCalculator {

    void async$applyChunkCounts(
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot,
            Long2ObjectMap<int[]> chunkMobCounts
    );
}
