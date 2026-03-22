package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public interface AsyncLocalMobCapCalculator {

    void async$applyPlayerSnapshot(
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot,
            Object2ObjectMap<ServerPlayer, int[]> playerMobCountsSnapshot
    );
}
