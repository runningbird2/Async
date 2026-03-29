package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;

public interface AsyncMonsterGlobalCapControl {

    void async$setMonsterCountOffset(int offset);

    int async$getAtomicMobCount(MobCategory category);

    int async$getEffectiveMobCount(MobCategory category);

    int async$getGlobalMobCap(MobCategory category);

    int async$getSpawnableChunkCount();

    default int async$getRemainingGlobalMobCapacity(MobCategory category) {
        return this.async$getGlobalMobCap(category) - this.async$getEffectiveMobCount(category);
    }

    default boolean async$isGlobalMobCapReached(MobCategory category) {
        return this.async$getRemainingGlobalMobCapacity(category) <= 0;
    }
}
