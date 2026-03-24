package com.axalotl.async.common.spawn;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public interface AsyncChunkMapSpawnInspector {

    List<ServerPlayer> async$getPlayersCloseForSpawningDirect(ChunkPos chunkPos);
}
