package com.axalotl.async.common.spawn;

import com.axalotl.async.common.mixin.entity.spawn.SpawnStateConstructorInvoker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;

import java.util.List;
import java.util.Objects;

public record AsyncPreparedSpawnState(
        int spawnableChunkCount,
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts,
        PotentialCalculator spawnPotential,
        Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot,
        Object2ObjectOpenHashMap<ServerPlayer, int[]> playerMobCounts
) {

    public AsyncPreparedSpawnState {
        mobCategoryCounts = new Object2IntOpenHashMap<>(Objects.requireNonNull(mobCategoryCounts, "mobCategoryCounts"));
        spawnPotential = Objects.requireNonNull(spawnPotential, "spawnPotential");
        playersNearChunkSnapshot = async$copyPlayersNearChunkSnapshot(playersNearChunkSnapshot);
        playerMobCounts = async$copyPlayerMobCounts(playerMobCounts);
    }

    public NaturalSpawner.SpawnState toSpawnState(ChunkMap chunkMap) {
        LocalMobCapCalculator localMobCapCalculator = new LocalMobCapCalculator(chunkMap);
        ((AsyncLocalMobCapCalculator) localMobCapCalculator).async$applyPlayerSnapshot(
                this.playersNearChunkSnapshot,
                this.playerMobCounts
        );
        return SpawnStateConstructorInvoker.async$createSpawnState(
                this.spawnableChunkCount,
                new Object2IntOpenHashMap<>(this.mobCategoryCounts),
                this.spawnPotential,
                localMobCapCalculator
        );
    }

    private static Long2ObjectOpenHashMap<List<ServerPlayer>> async$copyPlayersNearChunkSnapshot(
            Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot
    ) {
        Long2ObjectOpenHashMap<List<ServerPlayer>> copy = new Long2ObjectOpenHashMap<>(
                Objects.requireNonNull(playersNearChunkSnapshot, "playersNearChunkSnapshot")
        );
        for (Long2ObjectMap.Entry<List<ServerPlayer>> entry : copy.long2ObjectEntrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        return copy;
    }

    private static Object2ObjectOpenHashMap<ServerPlayer, int[]> async$copyPlayerMobCounts(
            Object2ObjectOpenHashMap<ServerPlayer, int[]> playerMobCounts
    ) {
        Object2ObjectOpenHashMap<ServerPlayer, int[]> copy = new Object2ObjectOpenHashMap<>();
        for (Object2ObjectMap.Entry<ServerPlayer, int[]> entry : Objects.requireNonNull(playerMobCounts, "playerMobCounts").object2ObjectEntrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return copy;
    }
}
