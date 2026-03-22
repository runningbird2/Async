package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.spawn.AsyncLocalMobCapCalculator;
import com.axalotl.async.common.spawn.AsyncMobCounts;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.server.level.ChunkMap;
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

    @Shadow @Final
    private ChunkMap chunkMap;

    @Override
    public void async$applyPlayerSnapshot(
            Long2ObjectMap<List<ServerPlayer>> playersNearChunkSnapshot,
            Object2ObjectMap<ServerPlayer, int[]> playerMobCountsSnapshot
    ) {
        this.playersNearChunk.clear();
        for (Long2ObjectMap.Entry<List<ServerPlayer>> entry : Long2ObjectMaps.fastIterable(playersNearChunkSnapshot)) {
            this.playersNearChunk.put(entry.getLongKey(), entry.getValue());
        }

        this.playerMobCounts.clear();
        for (Object2ObjectMap.Entry<ServerPlayer, int[]> entry : playerMobCountsSnapshot.object2ObjectEntrySet()) {
            LocalMobCapCalculator.MobCounts mobCounts = MobCountsConstructorInvoker.async$createMobCounts();
            ((AsyncMobCounts) mobCounts).async$addCounts(entry.getValue());
            this.playerMobCounts.put(entry.getKey(), mobCounts);
        }
    }
}
