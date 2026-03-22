package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.spawn.AsyncPreparedSpawnEntitySnapshot;
import com.axalotl.async.common.spawn.AsyncPreparedSpawnState;
import com.axalotl.async.common.spawn.AsyncPreparedSpawnStateBuilder;
import com.axalotl.async.common.spawn.AsyncPreparedSpawnStateTask;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin extends ChunkSource {

    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final Thread mainThread;
    @Shadow @Final public ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    @Shadow @Final private ServerLevel level;

    @Shadow public abstract @Nullable ChunkHolder getVisibleChunkIfPresent(long pos);
    @Shadow protected abstract CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int x, int z, ChunkStatus leastStatus, boolean create);
    @Shadow public abstract void tickSpawningChunk(LevelChunk chunk, long timeInhabited, List<MobCategory> spawnCategories, NaturalSpawner.SpawnState spawnState);

    @Unique private volatile @Nullable AsyncPreparedSpawnStateTask async$preparedSpawnStateTask;
    @Unique private long async$spawnStateTick;

    @Unique private static final long INITIAL_PARK_NS = 50_000L;
    @Unique private static final long MAX_PARK_NS = 1_000_000L;

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void async$getChunk(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() == this.mainThread) return;

        long pos = ChunkPos.asLong(x, z);
        ChunkHolder holder = this.getVisibleChunkIfPresent(pos);

        if (holder != null) {
            ChunkAccess ready = async$extractReady(holder, leastStatus);
            if (ready != null) {
                cir.setReturnValue(ready);
                return;
            }

            CompletableFuture<?> existingFuture = async$findPendingFuture(holder, leastStatus);
            if (existingFuture != null) {
                cir.setReturnValue(async$awaitWithProbing(existingFuture, pos, leastStatus));
                return;
            }
        }

        if (!create) {
            cir.setReturnValue(null);
            return;
        }
        CompletableFuture<?> ticketTask = CompletableFuture.runAsync(() -> this.getChunkFutureMainThread(x, z, leastStatus, true), this.mainThreadProcessor);
        cir.setReturnValue(async$awaitAfterTicket(ticketTask, pos, leastStatus));
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void async$getChunkNow(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
        if (Thread.currentThread() == this.mainThread) return;

        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (holder == null) {
            cir.setReturnValue(null);
            return;
        }

        ChunkAccess chunk = holder.getChunkIfPresent(ChunkStatus.FULL);
        if (chunk instanceof LevelChunk lc) {
            cir.setReturnValue(lc);
            return;
        }

        cir.setReturnValue(holder.getTickingChunk());
    }

    @Unique
    private @Nullable ChunkAccess async$awaitAfterTicket(CompletableFuture<?> ticketTask, long pos, ChunkStatus status) {
        long sleepNs = INITIAL_PARK_NS;
        for (;;) {
            async$abortChunkWaitIfShuttingDown();
            if (ticketTask.isCompletedExceptionally()) {
                ticketTask.join();
            }

            ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
            if (holder != null) {
                ChunkAccess ready = async$extractReady(holder, status);
                if (ready != null) return ready;

                CompletableFuture<?> directFuture = async$findPendingFuture(holder, status);
                if (directFuture != null) {
                    return async$awaitWithProbing(directFuture, pos, status);
                }
                ChunkAccess fromHolder = async$extractCompleted(holder, status);
                if (fromHolder != null) return fromHolder;
            }

            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }
    }

    @Unique
    private static @Nullable CompletableFuture<?> async$findPendingFuture(ChunkHolder holder, ChunkStatus status) {
        AtomicReferenceArray<?> futures = holder.futures;
        CompletableFuture<?> genFuture = (CompletableFuture<?>) futures.get(status.getIndex());
        if (genFuture != null && !genFuture.isDone()) {
            return genFuture;
        }
        if (status == ChunkStatus.FULL) {
            CompletableFuture<?> fullFuture = holder.getFullChunkFuture();
            if (!fullFuture.isDone()) {
                return fullFuture;
            }
        }
        return null;
    }

    @Unique
    private @Nullable ChunkAccess async$awaitWithProbing(CompletableFuture<?> future, long pos, ChunkStatus status) {
        long sleepNs = INITIAL_PARK_NS;
        while (!future.isDone()) {
            async$abortChunkWaitIfShuttingDown();
            ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
            if (holder != null) {
                ChunkAccess ready = async$extractReady(holder, status);
                if (ready != null) return ready;
            }
            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }
        return async$extractFromFuture(future);
    }

    @Unique
    private static void async$abortChunkWaitIfShuttingDown() {
        if (ParallelProcessor.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelled async chunk wait during shutdown");
        }
    }

    @Unique
    private static @Nullable ChunkAccess async$extractReady(ChunkHolder holder, ChunkStatus status) {
        ChunkAccess chunk = holder.getChunkIfPresent(status);
        if (chunk != null) return async$unwrap(chunk);

        chunk = holder.getChunkIfPresentUnchecked(status);
        if (chunk != null) return async$unwrap(chunk);
        if (status == ChunkStatus.FULL) {
            LevelChunk ticking = holder.getTickingChunk();
            if (ticking != null) return ticking;
        }
        return null;
    }

    @Unique
    private static @Nullable ChunkAccess async$extractCompleted(ChunkHolder holder, ChunkStatus status) {
        AtomicReferenceArray<?> futures = holder.futures;
        CompletableFuture<?> future = (CompletableFuture<?>) futures.get(status.getIndex());
        if (future != null && future.isDone()) {
            return async$extractFromFuture(future);
        }
        if (status == ChunkStatus.FULL) {
            CompletableFuture<?> fullFuture = holder.getFullChunkFuture();
            if (fullFuture.isDone()) {
                return async$extractFromFuture(fullFuture);
            }
        }
        return null;
    }

    @Unique
    private static @Nullable ChunkAccess async$extractFromFuture(CompletableFuture<?> future) {
        Object raw = future.join();
        if (raw instanceof ChunkResult<?> result) {
            Object chunk = result.orElse(null);
            if (chunk instanceof ChunkAccess ca) return async$unwrap(ca);
        }
        return null;
    }

    @Unique
    private static ChunkAccess async$unwrap(ChunkAccess chunk) {
        if (chunk instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        return chunk;
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"
            )
    )
    private NaturalSpawner.SpawnState async$wrapCreateState(
            int count,
            Iterable<Entity> entities,
            NaturalSpawner.ChunkGetter chunkGetter,
            LocalMobCapCalculator calculator,
            Operation<NaturalSpawner.SpawnState> original
    ) {
        long currentTick = ++this.async$spawnStateTick;
        if (AsyncConfig.disabled || !AsyncConfig.enableAsyncSpawn) {
            async$preparedSpawnStateTask = null;
            return original.call(count, entities, chunkGetter, calculator);
        }

        List<AsyncPreparedSpawnEntitySnapshot> entitySnapshot = async$capturePreparedSpawnEntities(entities);
        Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot = async$capturePlayersNearChunkSnapshot();
        NaturalSpawner.SpawnState preparedState = async$consumePreparedSpawnState(currentTick);
        if (preparedState != null) {
            async$schedulePreparedSpawnState(currentTick + 1L, count, entitySnapshot, playersNearChunkSnapshot);
            return preparedState;
        }

        NaturalSpawner.SpawnState state = original.call(count, entities, chunkGetter, calculator);
        async$schedulePreparedSpawnState(currentTick + 1L, count, entitySnapshot, playersNearChunkSnapshot);
        return state;
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V")
    )
    private void async$wrapBlockTicking(ChunkMap instance, Consumer<LevelChunk> consumer, Operation<Void> original) {
        if (AsyncConfig.disabled || !AsyncConfig.enableAsyncRandomTicks) {
            original.call(instance, consumer);
            return;
        }
        if (ParallelProcessor.isShuttingDown()) {
            return;
        }

        List<LevelChunk> chunks = new ArrayList<>();
        original.call(instance, (Consumer<LevelChunk>) chunks::add);

        if (chunks.isEmpty()) return;

        int poolSize = ParallelProcessor.getPoolSize();
        int batchSize = Math.max(1, (chunks.size() + poolSize - 1) / poolSize);
        List<Future<?>> futures = new ArrayList<>();
        int submittedUntil = 0;

        try {
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int start = i;
                int end = Math.min(i + batchSize, chunks.size());
                futures.add(ParallelProcessor.tickPool.submit(() -> {
                    for (int j = start; j < end; j++) {
                        consumer.accept(chunks.get(j));
                    }
                    return null;
                }));
                submittedUntil = end;
            }
        } catch (RejectedExecutionException e) {
            if (!ParallelProcessor.isShuttingDown()) {
                ParallelProcessor.LOGGER.warn("Async random-tick batching unavailable, falling back to synchronous execution", e);
            }
            for (int i = submittedUntil; i < chunks.size(); i++) {
                consumer.accept(chunks.get(i));
            }
        }

        async$awaitFutures(futures, "async random-tick batching");
    }

    @Unique
    private void async$awaitFutures(List<? extends Future<?>> futures, String actionName) {
        boolean allDone;
        do {
            allDone = futures.stream().allMatch(Future::isDone);
            if (!allDone) {
                if (ParallelProcessor.isShuttingDown()) {
                    return;
                }
                boolean pumped = false;
                for (ServerLevel lvl : ParallelProcessor.getServer().getAllLevels()) {
                    pumped |= lvl.getChunkSource().pollTask();
                }
                if (!pumped) {
                    Thread.onSpinWait();
                }
            }
        } while (!allDone);

        for (Future<?> future : futures) {
            if (ParallelProcessor.isShuttingDown() && !future.isDone()) {
                return;
            }
            try {
                future.get();
            } catch (Exception e) {
                ParallelProcessor.LOGGER.error("Error while waiting for {}", actionName, e);
            }
        }
    }

    @Unique
    private @Nullable NaturalSpawner.SpawnState async$consumePreparedSpawnState(long currentTick) {
        AsyncPreparedSpawnStateTask task = async$preparedSpawnStateTask;
        if (task == null) {
            return null;
        }

        if (task.targetTick() < currentTick) {
            task.future().cancel(true);
            async$preparedSpawnStateTask = null;
            return null;
        }

        if (task.targetTick() != currentTick || !task.future().isDone()) {
            return null;
        }

        async$preparedSpawnStateTask = null;
        try {
            return task.future().get().toSpawnState(this.chunkMap);
        } catch (CancellationException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            ParallelProcessor.LOGGER.error("Error while preparing async spawn state, falling back to synchronous createState", e);
            return null;
        }
    }

    @Unique
    private void async$schedulePreparedSpawnState(
            long targetTick,
            int spawnableChunkCount,
            List<AsyncPreparedSpawnEntitySnapshot> entitySnapshot,
            Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot
    ) {
        if (ParallelProcessor.isShuttingDown()) {
            async$preparedSpawnStateTask = null;
            return;
        }

        AsyncPreparedSpawnStateTask existingTask = async$preparedSpawnStateTask;
        if (existingTask != null) {
            if (existingTask.targetTick() >= targetTick) {
                return;
            }
            if (!existingTask.future().isDone()) {
                existingTask.future().cancel(true);
                async$preparedSpawnStateTask = null;
            }
        }

        try {
            Future<AsyncPreparedSpawnState> future = ParallelProcessor.tickPool.submit(
                    () -> {
                        try {
                            return AsyncPreparedSpawnStateBuilder.build(
                                    spawnableChunkCount,
                                    entitySnapshot,
                                    playersNearChunkSnapshot,
                                    this.level
                            );
                        } catch (CancellationException e) {
                            throw e;
                        } catch (Throwable t) {
                            ParallelProcessor.LOGGER.error("Error while building async prepared spawn state", t);
                            throw t;
                        }
                    }
            );
            async$preparedSpawnStateTask = new AsyncPreparedSpawnStateTask(targetTick, future);
        } catch (RejectedExecutionException e) {
            ParallelProcessor.LOGGER.warn("Async spawn-state build unavailable, falling back to synchronous createState", e);
            async$preparedSpawnStateTask = null;
        }
    }

    @Unique
    private static List<AsyncPreparedSpawnEntitySnapshot> async$capturePreparedSpawnEntities(Iterable<Entity> entities) {
        List<AsyncPreparedSpawnEntitySnapshot> entitySnapshot = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
                continue;
            }

            EntityType<?> entityType = entity.getType();
            MobCategory category = entityType.getCategory();
            if (category == MobCategory.MISC) {
                continue;
            }

            BlockPos blockPos = entity.blockPosition();
            entitySnapshot.add(new AsyncPreparedSpawnEntitySnapshot(
                    blockPos.immutable(),
                    ChunkPos.asLong(blockPos),
                    entityType,
                    category,
                    entity instanceof Mob
            ));
        }
        return entitySnapshot;
    }

    @Unique
    private Long2ObjectOpenHashMap<List<ServerPlayer>> async$capturePlayersNearChunkSnapshot() {
        Long2ObjectOpenHashMap<List<ServerPlayer>> playersNearChunkSnapshot = new Long2ObjectOpenHashMap<>();
        for (ServerPlayer player : this.level.players()) {
            if (player.isSpectator()) {
                continue;
            }

            ChunkPos playerChunkPos = player.chunkPosition();
            for (int dx = -NaturalSpawner.SPAWN_DISTANCE_CHUNK; dx <= NaturalSpawner.SPAWN_DISTANCE_CHUNK; dx++) {
                for (int dz = -NaturalSpawner.SPAWN_DISTANCE_CHUNK; dz <= NaturalSpawner.SPAWN_DISTANCE_CHUNK; dz++) {
                    ChunkPos chunkPos = new ChunkPos(playerChunkPos.x + dx, playerChunkPos.z + dz);
                    if (!async$isPlayerCloseEnoughForSpawning(player.position(), chunkPos)) {
                        continue;
                    }

                    playersNearChunkSnapshot.computeIfAbsent(chunkPos.toLong(), ignored -> new ArrayList<>()).add(player);
                }
            }
        }
        return playersNearChunkSnapshot;
    }

    @Unique
    private static boolean async$isPlayerCloseEnoughForSpawning(Vec3 playerPosition, ChunkPos chunkPos) {
        double chunkCenterX = SectionPos.sectionToBlockCoord(chunkPos.x, 8);
        double chunkCenterZ = SectionPos.sectionToBlockCoord(chunkPos.z, 8);
        double deltaX = chunkCenterX - playerPosition.x;
        double deltaZ = chunkCenterZ - playerPosition.z;
        return deltaX * deltaX + deltaZ * deltaZ < 16384.0;
    }
}
