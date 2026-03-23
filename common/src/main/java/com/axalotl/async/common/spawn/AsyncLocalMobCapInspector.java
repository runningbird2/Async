package com.axalotl.async.common.spawn;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public interface AsyncLocalMobCapInspector {

    List<ServerPlayer> async$getPlayersNear(ChunkPos chunkPos);

    int async$getMobCount(ServerPlayer player, MobCategory mobCategory);
}
