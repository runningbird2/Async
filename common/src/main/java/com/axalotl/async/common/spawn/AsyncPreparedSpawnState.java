package com.axalotl.async.common.spawn;

import com.axalotl.async.common.mixin.entity.spawn.SpawnStateConstructorInvoker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AsyncPreparedSpawnState(
        int spawnableChunkCount,
        Object2IntOpenHashMap<MobCategory> mobCategoryCounts,
        PotentialCalculator spawnPotential,
        Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot,
        Map<ServerPlayer, int[]> playerMobCounts
) {

    public AsyncPreparedSpawnState {
        mobCategoryCounts = new Object2IntOpenHashMap<>(Objects.requireNonNull(mobCategoryCounts, "mobCategoryCounts"));
        spawnPotential = Objects.requireNonNull(spawnPotential, "spawnPotential");
        playersNearChunkSnapshot = async$copyPlayersNearChunkSnapshot(playersNearChunkSnapshot);
        playerMobCounts = async$copyPlayerMobCounts(playerMobCounts);
    }

    public NaturalSpawner.SpawnState toSpawnState(ChunkMap chunkMap) {
        LocalMobCapCalculator localMobCapCalculator = new LocalMobCapCalculator(chunkMap);
        NaturalSpawner.SpawnState spawnState = SpawnStateConstructorInvoker.async$createSpawnState(
                this.spawnableChunkCount,
                new Object2IntOpenHashMap<>(this.mobCategoryCounts),
                this.spawnPotential,
                localMobCapCalculator
        );
        ((AsyncSpawnStateLocalCapAccessor) spawnState).async$setLocalMobCapState(
                new AsyncPreparedLocalMobCapState(this.playersNearChunkSnapshot, this.playerMobCounts)
        );
        return spawnState;
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

    private static Map<ServerPlayer, int[]> async$copyPlayerMobCounts(
            Map<ServerPlayer, int[]> playerMobCounts
    ) {
        Map<ServerPlayer, int[]> copy = new IdentityHashMap<>();
        for (Map.Entry<ServerPlayer, int[]> entry : Objects.requireNonNull(playerMobCounts, "playerMobCounts").entrySet()) {
            copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "playerMobCounts entry").clone());
        }
        return copy;
    }
}
