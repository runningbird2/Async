package com.axalotl.async.common.mixin.server;

import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SimulationChunkTracker.class)
public class SimulationChunkTrackerMixin {

    // Keep the simulation/entity-ticking tracker on vanilla storage.
}
