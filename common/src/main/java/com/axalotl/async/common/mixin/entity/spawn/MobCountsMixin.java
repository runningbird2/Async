package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.spawn.AsyncMobCounts;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicIntegerArray;

@Mixin(LocalMobCapCalculator.MobCounts.class)
public class MobCountsMixin implements AsyncMobCounts {

    @Unique
    private final AtomicIntegerArray async$atomicCounts = new AtomicIntegerArray(MobCategory.values().length);

    @WrapMethod(method = "add")
    private void async$add(MobCategory category, Operation<Void> original) {
        async$atomicCounts.incrementAndGet(category.ordinal());
    }

    @WrapMethod(method = "canSpawn")
    private boolean async$canSpawn(MobCategory category, Operation<Boolean> original) {
        return async$atomicCounts.get(category.ordinal()) < category.getMaxInstancesPerChunk();
    }

    @Override
    public void async$addCounts(int[] categoryCounts) {
        int limit = Math.min(categoryCounts.length, MobCategory.values().length);
        for (int i = 0; i < limit; i++) {
            int count = categoryCounts[i];
            if (count != 0) {
                async$atomicCounts.addAndGet(i, count);
            }
        }
    }

    @Override
    public int async$getCount(MobCategory category) {
        return this.async$atomicCounts.get(category.ordinal());
    }
}
