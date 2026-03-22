package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.spawn.AsyncLocalMobCapCalculator;
import com.axalotl.async.common.spawn.AsyncMobCounts;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;

@Mixin(LocalMobCapCalculator.class)
public abstract class LocalMobCapCalculatorMixin implements AsyncLocalMobCapCalculator {
    @Shadow @Final
    private Map<ServerPlayer, LocalMobCapCalculator.MobCounts> playerMobCounts;

    @Shadow @Final
    private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;

    @Override
    public void async$applyChunkCounts(
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot,
            Long2ObjectMap<int[]> chunkMobCounts
    ) {
        this.playersNearChunk.clear();
        this.playerMobCounts.clear();

        for (Long2ObjectMap.Entry<List<ServerPlayer>> entry : Long2ObjectMaps.fastIterable(playersNearChunkSnapshot)) {
            this.playersNearChunk.put(entry.getLongKey(), entry.getValue());
        }

        for (Long2ObjectMap.Entry<int[]> entry : Long2ObjectMaps.fastIterable(chunkMobCounts)) {
            long chunkLong = entry.getLongKey();
            List<ServerPlayer> players = playersNearChunkSnapshot.get(chunkLong);
            if (players == null || players.isEmpty()) {
                continue;
            }
            int[] counts = entry.getValue();
            for (ServerPlayer player : players) {
                LocalMobCapCalculator.MobCounts mobCounts = this.playerMobCounts.computeIfAbsent(player, ignored -> MobCountsConstructorInvoker.async$createMobCounts());
                ((AsyncMobCounts) mobCounts).async$addCounts(counts);
            }
        }
    }
}
