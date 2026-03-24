package com.axalotl.async.common.mixin.world;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

    // Keep vanilla queue/set behavior for chunk future updates and ticket releases.
}
