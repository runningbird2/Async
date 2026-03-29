package com.axalotl.async.common.spawn;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

public interface AsyncSpawnStateMobcapAccess extends AsyncMonsterGlobalCapControl {

    int async$getLocalMobCount(ServerPlayer player, MobCategory category);

    int async$getLocalMobHeadroom(ServerPlayer player, MobCategory category);

    int async$getMinLocalMobHeadroom(MobCategory category, ChunkPos chunkPos);

    default int async$getLocalMobLimit(MobCategory category) {
        return category.getMaxInstancesPerChunk();
    }

    default boolean async$isLocalMobCapReached(ServerPlayer player, MobCategory category) {
        return this.async$getLocalMobHeadroom(player, category) <= 0;
    }
}
