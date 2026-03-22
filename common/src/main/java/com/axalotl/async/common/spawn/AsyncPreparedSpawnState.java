package com.axalotl.async.common.spawn;

import com.axalotl.async.common.mixin.entity.spawn.SpawnStateConstructorInvoker;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;

import java.util.Objects;

public record AsyncPreparedSpawnState(
        int spawnableChunkCount,
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts,
        PotentialCalculator spawnPotential
) {

    public AsyncPreparedSpawnState {
        mobCategoryCounts = new Object2IntOpenHashMap<>(Objects.requireNonNull(mobCategoryCounts, "mobCategoryCounts"));
        spawnPotential = Objects.requireNonNull(spawnPotential, "spawnPotential");
    }

    public NaturalSpawner.SpawnState toSpawnState(LocalMobCapCalculator localMobCapCalculator) {
        return SpawnStateConstructorInvoker.async$createSpawnState(
                this.spawnableChunkCount,
                new Object2IntOpenHashMap<>(this.mobCategoryCounts),
                this.spawnPotential,
                localMobCapCalculator
        );
    }
}
