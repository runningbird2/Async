package com.axalotl.async.common.spawn;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AsyncPreparedLocalMobCapState {
    private final Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot;
    private final Map<ServerPlayer, int[]> playerMobCounts;

    public AsyncPreparedLocalMobCapState(
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot,
            Map<ServerPlayer, int[]> playerMobCounts
    ) {
        this.playersNearChunkSnapshot = async$copyPlayersNearChunkSnapshot(playersNearChunkSnapshot);
        this.playerMobCounts = async$copyPlayerMobCounts(playerMobCounts);
    }

    public boolean canSpawn(MobCategory category, ChunkPos chunkPos) {
        List<ServerPlayer> players = this.playersNearChunkSnapshot.get(chunkPos.toLong());
        if (players == null || players.isEmpty()) {
            return false;
        }

        int categoryIndex = category.ordinal();
        int maxInstances = category.getMaxInstancesPerChunk();
        for (ServerPlayer player : players) {
            int[] counts = this.playerMobCounts.get(player);
            if (counts == null || counts[categoryIndex] < maxInstances) {
                return true;
            }
        }

        return false;
    }

    public void addMob(ChunkPos chunkPos, MobCategory category) {
        List<ServerPlayer> players = this.playersNearChunkSnapshot.get(chunkPos.toLong());
        if (players == null || players.isEmpty()) {
            return;
        }

        int categoryIndex = category.ordinal();
        for (ServerPlayer player : players) {
            int[] counts = this.playerMobCounts.computeIfAbsent(player, ignored -> new int[MobCategory.values().length]);
            counts[categoryIndex]++;
        }
    }

    private static Long2ObjectOpenHashMap<List<ServerPlayer>> async$copyPlayersNearChunkSnapshot(
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot
    ) {
        Long2ObjectOpenHashMap<List<ServerPlayer>> copy = new Long2ObjectOpenHashMap<>(
                Objects.requireNonNull(playersNearChunkSnapshot, "playersNearChunkSnapshot")
        );
        for (Long2ObjectMap.Entry<List<ServerPlayer>> entry : Long2ObjectMaps.fastIterable(copy)) {
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
