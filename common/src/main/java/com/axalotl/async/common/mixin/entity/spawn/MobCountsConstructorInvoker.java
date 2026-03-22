package com.axalotl.async.common.mixin.entity.spawn;

import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LocalMobCapCalculator.MobCounts.class)
public interface MobCountsConstructorInvoker {

    @Invoker("<init>")
    static LocalMobCapCalculator.MobCounts async$createMobCounts() {
        throw new AssertionError();
    }
}
