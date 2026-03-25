package com.axalotl.async.common.spawn;

import com.axalotl.async.common.AsyncSpawnCacheMissException;
import com.axalotl.async.common.ParallelProcessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.concurrent.CancellationException;

public final class AsyncPreparedSpawnStateBuilder {

    private AsyncPreparedSpawnStateBuilder() {
    }

    public static AsyncPreparedSpawnState build(
            List<AsyncPreparedSpawnEntitySnapshot> entities,
            ServerLevel level
    ) {
        async$abortIfCancelled();
        PotentialCalculator spawnPotential = new PotentialCalculator();
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts = new Object2IntOpenHashMap<>(MobCategory.values().length);
        Long2ObjectOpenHashMap<int[]> chunkMobCounts = new Long2ObjectOpenHashMap<>();

        for (AsyncPreparedSpawnEntitySnapshot entity : entities) {
            async$abortIfCancelled();
            if (!entity.countsTowardSpawnCap()) {
                continue;
            }

            BlockPos blockPos = entity.blockPos();
            LevelChunk chunk = level.getChunkSource().getChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4);
            if (chunk == null) {
                throw new AsyncSpawnCacheMissException();
            }

            MobSpawnSettings.MobSpawnCost mobSpawnCost = NaturalSpawner.getRoughBiome(blockPos, chunk)
                    .getMobSettings()
                    .getMobSpawnCost(entity.entityType());
            if (mobSpawnCost != null) {
                spawnPotential.addCharge(blockPos, mobSpawnCost.charge());
            }

            MobCategory category = entity.category();
            mobCategoryCounts.addTo(category, 1);
            chunkMobCounts.computeIfAbsent(entity.chunkPosLong(), ignored -> new int[MobCategory.values().length])[category.ordinal()]++;
        }

        return new AsyncPreparedSpawnState(
                mobCategoryCounts,
                spawnPotential,
                chunkMobCounts
        );
    }

    private static void async$abortIfCancelled() {
        if (ParallelProcessor.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled async prepared spawn-state build");
        }
    }
}
