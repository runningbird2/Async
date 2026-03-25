package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;

public interface AsyncMonsterGlobalCapControl {

    void async$setMonsterCountOffset(int offset);

    int async$getAtomicMobCount(MobCategory category);

    int async$getEffectiveMobCount(MobCategory category);

    int async$getGlobalMobCap(MobCategory category);

    int async$getSpawnableChunkCount();
}
