package com.axalotl.async.common.spawn;

import com.axalotl.async.common.ParallelProcessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class AsyncPreparedSpawnStateBuilder {

    private AsyncPreparedSpawnStateBuilder() {
    }

    public static AsyncPreparedSpawnState build(
            int spawnableChunkCount,
            List<AsyncPreparedSpawnEntitySnapshot> entities,
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot,
            AsyncPreparedFullChunkSnapshot fullChunkSnapshot
    ) {
        async$abortIfCancelled();
        PotentialCalculator spawnPotential = new PotentialCalculator();
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts = new Object2IntOpenHashMap<>(MobCategory.values().length);
        Map<ServerPlayer, int[]> playerMobCounts = new IdentityHashMap<>();

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

            MobCategory category = entity.category();
            mobCategoryCounts.addTo(category, 1);

            if (!entity.countsTowardLocalCap()) {
                continue;
            }

            List<ServerPlayer> players = playersNearChunkSnapshot.get(entity.chunkPosLong());
            if (players == null || players.isEmpty()) {
                continue;
            }

            for (ServerPlayer player : players) {
                playerMobCounts.computeIfAbsent(player, ignored -> new int[MobCategory.values().length])[category.ordinal()]++;
            }
        }

        return new AsyncPreparedSpawnState(
                spawnableChunkCount,
                mobCategoryCounts,
                spawnPotential,
                new Long2ObjectOpenHashMap<>(playersNearChunkSnapshot),
                playerMobCounts
        );
    }

    private static void async$abortIfCancelled() {
        if (ParallelProcessor.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled async prepared spawn-state build");
        }
    }
}
