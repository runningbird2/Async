package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.spawn.AsyncMobCounts;
import com.axalotl.async.common.spawn.AsyncMobCountsInspector;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LocalMobCapCalculator.MobCounts.class)
public abstract class MobCountsMixin implements AsyncMobCounts, AsyncMobCountsInspector {

    @Shadow
    private Object2IntMap<MobCategory> counts;

    @Shadow
    public abstract void add(MobCategory mobCategory);

    @Override
    public void async$addCounts(int[] categoryCounts) {
        MobCategory[] values = MobCategory.values();
        int limit = Math.min(categoryCounts.length, values.length);
        for (int i = 0; i < limit; i++) {
            int count = categoryCounts[i];
            if (count != 0) {
                MobCategory category = values[i];
                for (int j = 0; j < count; j++) {
                    this.add(category);
                }
            }
        }
    }

    @Override
    public int async$getCount(MobCategory mobCategory) {
        return this.counts.getOrDefault(mobCategory, 0);
    }
}
