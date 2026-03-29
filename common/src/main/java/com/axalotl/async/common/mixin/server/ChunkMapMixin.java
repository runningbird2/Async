package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.parallelised.ConcurrentList;
import com.axalotl.async.common.parallelised.fastutil.Int2ObjectConcurrentHashMap;
import com.axalotl.async.common.spawn.AsyncChunkMapSpawnInspector;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mixin(value = ChunkMap.class, priority = 1500)
public abstract class ChunkMapMixin extends SimpleRegionStorage implements ChunkHolder.PlayerProvider, AsyncChunkMapSpawnInspector {

    @Shadow @Final @Mutable
    private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;

    @Shadow @Final @Mutable
    private List<ChunkGenerationTask> pendingGenerationTasks;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Shadow @Final
    private ServerLevel level;

    @Shadow @Final @Mutable
    private LongSet chunksToEagerlySave;

    public ChunkMapMixin(RegionStorageInfo info, Path folder, DataFixer fixerUpper, boolean sync, DataFixTypes dataFixType) {
        super(info, folder, fixerUpper, sync, dataFixType);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void replaceConVars(CallbackInfo ci) {
        entityMap = new Int2ObjectConcurrentHashMap<>();
        pendingGenerationTasks = new ConcurrentList<>();
        chunksToEagerlySave = LongSets.synchronize(new LongLinkedOpenHashSet());
    }

    @WrapMethod(method = "addEntity")
    private synchronized void addEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    @WrapMethod(method = "removeEntity")
    private synchronized void removeEntity(Entity entity, Operation<Void> original) {
        original.call(entity);
    }

    @WrapMethod(method = "releaseGeneration")
    private synchronized void releaseGeneration(GenerationChunkHolder chunk, Operation<Void> original) {
        original.call(chunk);
    }

    @Inject(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;pauseInIde(Ljava/lang/Throwable;)Ljava/lang/Throwable;"), cancellable = true)
    private void skipThrowLoadEntity(Entity entity, CallbackInfo ci) {
        ci.cancel();
    }

    @Override
    public List<ServerPlayer> async$getPlayersCloseForSpawningDirect(ChunkPos chunkPos) {
        return this.async$collectPlayersCloseForSpawning(chunkPos);
    }

    @Unique
    private List<ServerPlayer> async$collectPlayersCloseForSpawning(ChunkPos chunkPos) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : this.level.players()) {
            if (async$isPlayerCloseEnoughForSpawning(player, chunkPos)) {
                players.add(player);
            }
        }
        return players;
    }

    @Unique
    private static boolean async$isPlayerCloseEnoughForSpawning(ServerPlayer player, ChunkPos chunkPos) {
        if (player.isSpectator()) {
            return false;
        }

        double chunkCenterX = SectionPos.sectionToBlockCoord(chunkPos.x, 8);
        double chunkCenterZ = SectionPos.sectionToBlockCoord(chunkPos.z, 8);
        double deltaX = chunkCenterX - player.getX();
        double deltaZ = chunkCenterZ - player.getZ();
        return deltaX * deltaX + deltaZ * deltaZ < 16384.0;
    }
}
