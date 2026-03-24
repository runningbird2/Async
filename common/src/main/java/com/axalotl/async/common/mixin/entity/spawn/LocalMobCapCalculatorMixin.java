package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.spawn.AsyncChunkMapSpawnInspector;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(LocalMobCapCalculator.class)
public class LocalMobCapCalculatorMixin {
    @Shadow
    private ChunkMap chunkMap;

    @Shadow
    private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;

    @Inject(method = "getPlayersNear", at = @At("RETURN"), cancellable = true)
    private void onGetMobSpawnablePlayers(ChunkPos pos, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        if (cir.getReturnValue() == null) {
            cir.setReturnValue(List.of());
        }
    }

    @WrapMethod(method = "getPlayersNear")
    private List<ServerPlayer> async$useDirectPlayersWhenCacheIsEmpty(ChunkPos pos, Operation<List<ServerPlayer>> original) {
        List<ServerPlayer> players = original.call(pos);
        if (players == null) {
            players = List.of();
        }
        if (AsyncConfig.disabled || !players.isEmpty()) {
            return players;
        }

        if (!(this.chunkMap instanceof AsyncChunkMapSpawnInspector inspector)) {
            return players;
        }

        List<ServerPlayer> directPlayers = inspector.async$getPlayersCloseForSpawningDirect(pos);
        if (!directPlayers.isEmpty()) {
            this.playersNearChunk.put(pos.toLong(), directPlayers);
            return directPlayers;
        }
        return players;
    }
}
