package com.axalotl.async.common.parallelised.utils;

import net.minecraft.world.entity.Mob;

public interface AsyncNavigationTracker {

    boolean async$isNavigationActive(Mob mob);
}
