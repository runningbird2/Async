package com.axalotl.async.common.spawn;

import net.minecraft.world.level.NaturalSpawner;
import org.jetbrains.annotations.Nullable;

public interface AsyncServerChunkCacheSpawnStateAccess {

    @Nullable
    NaturalSpawner.SpawnState async$getLastSpawnState();

    boolean async$hasFullChunk(long chunkPos);

    boolean async$hasTickingChunk(long chunkPos);
}
