package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;

public interface AsyncMobCountsInspector {

    int async$getCount(MobCategory mobCategory);
}
