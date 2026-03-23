package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.spawn.AsyncLocalMobCapCalculator;
import com.axalotl.async.common.spawn.AsyncLocalMobCapInspector;
import com.axalotl.async.common.spawn.AsyncMobCounts;
import com.axalotl.async.common.spawn.AsyncMobCountsInspector;
import com.axalotl.async.common.spawn.LocalMobCapTelemetry;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;

@Mixin(LocalMobCapCalculator.class)
public abstract class LocalMobCapCalculatorMixin implements AsyncLocalMobCapCalculator, AsyncLocalMobCapInspector {
    @Shadow @Final
    private Map<ServerPlayer, LocalMobCapCalculator.MobCounts> playerMobCounts;

    @Shadow @Final
    private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;

    @Shadow @Final
    private ChunkMap chunkMap;

    @Override
    public List<ServerPlayer> async$getPlayersNear(ChunkPos chunkPos) {
        long chunkLong = chunkPos.toLong();
        return this.playersNearChunk.computeIfAbsent(chunkLong, ignored -> this.chunkMap.getPlayersCloseForSpawning(chunkPos));
    }

    @Override
    public int async$getMobCount(ServerPlayer player, MobCategory mobCategory) {
        LocalMobCapCalculator.MobCounts mobCounts = this.playerMobCounts.get(player);
        if (mobCounts == null) {
            return 0;
        }
        return ((AsyncMobCountsInspector) mobCounts).async$getCount(mobCategory);
    }

    @Override
    public void async$applyChunkCounts(Long2ObjectMap<int[]> chunkMobCounts) {
        this.playersNearChunk.clear();
        this.playerMobCounts.clear();
        for (Long2ObjectMap.Entry<int[]> entry : Long2ObjectMaps.fastIterable(chunkMobCounts)) {
            long chunkLong = entry.getLongKey();
            ChunkPos chunkPos = new ChunkPos(chunkLong);
            List<ServerPlayer> players = this.async$getPlayersNear(chunkPos);
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

    @WrapMethod(method = "canSpawn")
    private boolean async$traceCanSpawn(MobCategory mobCategory, ChunkPos chunkPos, Operation<Boolean> original) {
        boolean allowed = original.call(mobCategory, chunkPos);
        if (mobCategory == MobCategory.MONSTER) {
            LocalMobCapTelemetry.recordMonsterCheck(chunkPos, this.async$getPlayersNear(chunkPos), this, allowed);
        }
        return allowed;
    }
}
