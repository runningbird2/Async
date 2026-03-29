package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;

public interface AsyncMobCounts {

    // Counts are indexed by MobCategory.ordinal().
    void async$addCounts(int[] categoryCountsByOrdinal);

    int async$getCount(MobCategory category);

    default int async$getHeadroom(MobCategory category) {
        return category.getMaxInstancesPerChunk() - this.async$getCount(category);
    }

    default boolean async$isAtCap(MobCategory category) {
        return this.async$getHeadroom(category) <= 0;
    }
}
