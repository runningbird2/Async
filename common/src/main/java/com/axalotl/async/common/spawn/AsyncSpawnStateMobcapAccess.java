package com.axalotl.async.common.spawn;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

public interface AsyncSpawnStateMobcapAccess extends AsyncMonsterGlobalCapControl {

    int async$getLocalMobCount(ServerPlayer player, MobCategory category);

    int async$getLocalMobHeadroom(ServerPlayer player, MobCategory category);

    int async$getMinLocalMobHeadroom(MobCategory category, ChunkPos chunkPos);
}
