package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.parallelised.ConcurrentCollections;
import com.axalotl.async.common.parallelised.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.async.common.spawn.AsyncChunkMapSpawnInspector;
import com.axalotl.async.common.spawn.AsyncLocalMobCapCalculator;
import com.axalotl.async.common.spawn.AsyncMobCounts;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(LocalMobCapCalculator.class)
public abstract class LocalMobCapCalculatorMixin implements AsyncLocalMobCapCalculator {
    @Shadow @Final @Mutable
    private Map<ServerPlayer, LocalMobCapCalculator.MobCounts> playerMobCounts;

    @Shadow @Final @Mutable
    private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;

    @Shadow @Final
    private ChunkMap chunkMap;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void async$initConcurrentState(CallbackInfo ci) {
        this.playerMobCounts = ConcurrentCollections.newHashMap();
        this.playersNearChunk = new Long2ObjectConcurrentHashMap<>();
    }

    @Inject(method = "getPlayersNear", at = @At("RETURN"), cancellable = true)
    private void async$onGetMobSpawnablePlayers(ChunkPos pos, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        if (cir.getReturnValue() == null) {
            cir.setReturnValue(List.of());
        }
    }

    @Override
    public void async$applyChunkCounts(Long2ObjectMap<int[]> chunkMobCounts) {
        this.playersNearChunk.clear();
        this.playerMobCounts.clear();
        Map<ServerPlayer, int[]> pendingPlayerCounts = new HashMap<>();

        for (Long2ObjectMap.Entry<int[]> entry : Long2ObjectMaps.fastIterable(chunkMobCounts)) {
            long chunkLong = entry.getLongKey();
            ChunkPos chunkPos = new ChunkPos(chunkLong);
            List<ServerPlayer> players = this.async$getOrLoadPlayersNearChunk(chunkPos);
            if (players.isEmpty()) {
                continue;
            }
            int[] counts = entry.getValue();
            for (ServerPlayer player : players) {
                int[] playerCounts = pendingPlayerCounts.computeIfAbsent(
                        player,
                        ignored -> new int[net.minecraft.world.entity.MobCategory.values().length]
                );
                int limit = Math.min(playerCounts.length, counts.length);
                for (int i = 0; i < limit; i++) {
                    playerCounts[i] += counts[i];
                }
            }
        }

        for (Map.Entry<ServerPlayer, int[]> entry : pendingPlayerCounts.entrySet()) {
            LocalMobCapCalculator.MobCounts mobCounts = MobCountsConstructorInvoker.async$createMobCounts();
            ((AsyncMobCounts) mobCounts).async$addCounts(entry.getValue());
            this.playerMobCounts.put(entry.getKey(), mobCounts);
        }
    }

    @Override
    public int async$getMobCount(ServerPlayer player, net.minecraft.world.entity.MobCategory category) {
        LocalMobCapCalculator.MobCounts mobCounts = this.playerMobCounts.get(player);
        if (mobCounts == null) {
            return 0;
        }
        return ((AsyncMobCounts) mobCounts).async$getCount(category);
    }

    @Override
    public int async$getMobHeadroom(ServerPlayer player, net.minecraft.world.entity.MobCategory category) {
        return category.getMaxInstancesPerChunk() - this.async$getMobCount(player, category);
    }

    @Override
    public int async$getMinMobHeadroom(net.minecraft.world.entity.MobCategory category, ChunkPos chunkPos) {
        List<ServerPlayer> players = this.async$getOrLoadPlayersNearChunk(chunkPos);
        if (players.isEmpty()) {
            return 0;
        }

        int minHeadroom = Integer.MAX_VALUE;
        for (ServerPlayer player : players) {
            minHeadroom = Math.min(minHeadroom, this.async$getMobHeadroom(player, category));
        }
        return minHeadroom;
    }

    @Unique
    private List<ServerPlayer> async$getOrLoadPlayersNearChunk(ChunkPos chunkPos) {
        long chunkLong = chunkPos.toLong();
        List<ServerPlayer> players = this.playersNearChunk.get(chunkLong);
        if (players != null) {
            return players;
        }

        players = this.chunkMap.getPlayersCloseForSpawning(chunkPos);
        if ((players == null || players.isEmpty()) && this.chunkMap instanceof AsyncChunkMapSpawnInspector inspector) {
            List<ServerPlayer> directPlayers = inspector.async$getPlayersCloseForSpawningDirect(chunkPos);
            if (directPlayers != null && !directPlayers.isEmpty()) {
                players = directPlayers;
            }
        }
        if (players == null) {
            players = List.of();
        }

        this.playersNearChunk.put(chunkLong, players);
        return players;
    }
}
