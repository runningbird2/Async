package com.axalotl.async.common.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Mob;

public interface MinecraftPlatform {
    boolean hasPermission(CommandSourceStack source, String node, int level);

    int getMaxSpawnClusterSize(Mob mob);
}
