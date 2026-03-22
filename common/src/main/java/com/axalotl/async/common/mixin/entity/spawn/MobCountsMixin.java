package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.spawn.AsyncMobCounts;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LocalMobCapCalculator.MobCounts.class)
public abstract class MobCountsMixin implements AsyncMobCounts {

    @Shadow @Final
    private Object2IntMap<MobCategory> counts;

    @Override
    public void async$addCounts(int[] categoryCounts) {
        MobCategory[] values = MobCategory.values();
        int limit = Math.min(categoryCounts.length, values.length);
        for (int i = 0; i < limit; i++) {
            int count = categoryCounts[i];
            if (count != 0) {
                MobCategory category = values[i];
                this.counts.put(category, this.counts.getOrDefault(category, 0) + count);
            }
        }
    }
}
