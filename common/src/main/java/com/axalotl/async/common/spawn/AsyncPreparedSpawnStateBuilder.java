package com.axalotl.async.common.spawn;

import com.axalotl.async.common.ParallelProcessor;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.List;
import java.util.concurrent.CancellationException;

public final class AsyncPreparedSpawnStateBuilder {

    private AsyncPreparedSpawnStateBuilder() {
    }

    public static AsyncPreparedSpawnState build(
            int spawnableChunkCount,
            List<AsyncPreparedSpawnEntitySnapshot> entities,
            AsyncPreparedFullChunkSnapshot fullChunkSnapshot
    ) {
        async$abortIfCancelled();
        PotentialCalculator spawnPotential = new PotentialCalculator();
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts = new Object2IntOpenHashMap<>(MobCategory.values().length);

        for (AsyncPreparedSpawnEntitySnapshot entity : entities) {
            async$abortIfCancelled();
            BlockPos blockPos = entity.blockPos();
            var chunk = fullChunkSnapshot.get(entity.chunkPosLong());
            if (chunk == null) {
                continue;
            }

            MobSpawnSettings.MobSpawnCost mobSpawnCost = NaturalSpawner.getRoughBiome(blockPos, chunk)
                    .getMobSettings()
                    .getMobSpawnCost(entity.entityType());
            if (mobSpawnCost != null) {
                spawnPotential.addCharge(blockPos, mobSpawnCost.charge());
            }

            mobCategoryCounts.addTo(entity.category(), 1);
        }

        return new AsyncPreparedSpawnState(
                spawnableChunkCount,
                mobCategoryCounts,
                spawnPotential
        );
    }

    private static void async$abortIfCancelled() {
        if (ParallelProcessor.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled async prepared spawn-state build");
        }
    }
}
