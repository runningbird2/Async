package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

public interface AsyncLocalMobCapCalculator {

    void async$applyChunkCounts(Long2ObjectMap<int[]> chunkMobCounts);
}
