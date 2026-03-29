package com.axalotl.async.common.spawn;

import net.minecraft.world.level.NaturalSpawner;
import org.jetbrains.annotations.Nullable;

public interface AsyncServerChunkCacheSpawnStateAccess {

    @Nullable
    NaturalSpawner.SpawnState async$getLastSpawnState();

    default @Nullable NaturalSpawner.SpawnState async$getMobcapDebugSpawnState() {
        return this.async$getLastSpawnState();
    }

    default @Nullable AsyncSpawnStateMobcapAccess async$getMobcapDebugAccess() {
        NaturalSpawner.SpawnState spawnState = this.async$getMobcapDebugSpawnState();
        return spawnState instanceof AsyncSpawnStateMobcapAccess access ? access : null;
    }
}
