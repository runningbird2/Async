package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.AsyncSpawnCacheMissException;
import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.platform.PlatformUtils;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin extends ChunkSource {
    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    @Final
    Thread mainThread;

    @Shadow
    @Final
    public ServerChunkCache.MainThreadExecutor mainThreadProcessor;

    @Shadow
    public abstract @Nullable ChunkHolder getVisibleChunkIfPresent(long pos);

    @Shadow
    protected abstract CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int x, int z, ChunkStatus leastStatus, boolean create);

    @Shadow
    private final Set<ChunkHolder> chunkHoldersToBroadcast = ConcurrentHashMap.newKeySet();

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Shadow
    private volatile NaturalSpawner.@Nullable SpawnState lastSpawnState;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    protected abstract void getFullChunk(long chunkPos, Consumer<LevelChunk> fullChunkGetter);

    @Shadow
    private final List<LevelChunk> spawningChunks = Collections.synchronizedList(new ArrayList<>());

    @Shadow
    private boolean spawnEnemies;

    @Unique
    private boolean async$firstRunSpawnCounts = true;

    @Unique
    private final AtomicBoolean async$spawnCountsReady = new AtomicBoolean(false);

    @Unique
    private final AtomicBoolean async$forceSyncSpawnNextTick = new AtomicBoolean(false);

    @Unique
    private static final AsyncSpawnCacheMissException async$SPAWN_CACHE_MISS = new AsyncSpawnCacheMissException();

    @Unique
    private static final long async$SPAWN_FALLBACK_SUMMARY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

    @Unique
    private final AtomicInteger async$consecutiveSpawnStateFailures = new AtomicInteger(0);

    @Unique
    private final AtomicInteger async$consecutiveSpawnChunkFailures = new AtomicInteger(0);

    @Unique
    private final AtomicInteger async$spawnStateGeneration = new AtomicInteger(0);

    @Unique
    private final Object async$spawnStateLock = new Object();

    @Unique
    private long async$spawnFallbackSummaryWindowStartNanos;

    @Unique
    private int async$spawnStateFallbackSummaryCount;

    @Unique
    private int async$spawnChunkFallbackSummaryCount;

    @Shadow
    public abstract void tickSpawningChunk(LevelChunk chunk, long timeInhabited, List<MobCategory> spawnCategories, NaturalSpawner.SpawnState spawnState);

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void async$getChunk(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() == this.mainThread) return;

        ChunkAccess access = async$tryGetChunk(x, z, leastStatus);
        if (access != null) {
            cir.setReturnValue(access);
            return;
        }

        if (ParallelProcessor.isSpawnExecutionThread()) {
            if (!create) {
                cir.setReturnValue(null);
                return;
            }
            throw async$SPAWN_CACHE_MISS;
        }

        if (ParallelProcessor.isTickExecutionThread()) {
            throw new ParallelProcessor.AsyncAbortException();
        }

        CompletableFuture<ChunkResult<ChunkAccess>> future = CompletableFuture.supplyAsync(
                () -> this.getChunkFutureMainThread(x, z, leastStatus, create),
                this.mainThreadProcessor
        ).thenCompose(f -> f);

        while (!future.isDone()) {
            if (async$shouldAbortChunkWait()) {
                future.cancel(false);
                throw new ParallelProcessor.AsyncAbortException();
            }

            ChunkAccess cached = async$tryGetChunk(x, z, leastStatus);
            if (cached != null) {
                future.cancel(false);
                cir.setReturnValue(cached);
                return;
            }
            LockSupport.parkNanos(10_000);
        }

        ChunkAccess chunk = future.join().orElse(null);
        if (chunk instanceof ImposterProtoChunk imposter) {
            chunk = imposter.getWrapped();
        }
        cir.setReturnValue(chunk);
    }

    @Unique
    private @Nullable ChunkAccess async$tryGetChunk(int x, int z, ChunkStatus leastStatus) {
        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) return null;

        ChunkAccess chunk = holder.getChunkIfPresent(leastStatus);
        if (chunk != null) {
            if (chunk instanceof ImposterProtoChunk imposter) {
                return imposter.getWrapped();
            }
            return chunk;
        }

        return null;
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void shortcutGetChunkNow(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
        if (Thread.currentThread() != this.mainThread) {
            ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
            if (holder != null) {
                if (ParallelProcessor.isSpawnExecutionThread()) {
                    LevelChunk levelChunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                    cir.setReturnValue(levelChunk);
                    return;
                }

                if (ParallelProcessor.isTickExecutionThread()) {
                    LevelChunk levelChunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                    if (levelChunk != null) {
                        cir.setReturnValue(levelChunk);
                        return;
                    }
                    throw new ParallelProcessor.AsyncAbortException();
                }

                CompletableFuture<ChunkResult<ChunkAccess>> future = holder.scheduleChunkGenerationTask(ChunkStatus.FULL, this.chunkMap);
                ChunkAccess chunk = future.getNow(ChunkHolder.UNLOADED_CHUNK).orElse(null);
                if (chunk instanceof LevelChunk worldChunk) {
                    cir.setReturnValue(worldChunk);
                }
            }
        }
    }

    @Unique
    private void async$getLoadedFullChunkOrThrow(long chunkPos, Consumer<LevelChunk> fullChunkGetter) {
        ChunkHolder holder = this.getVisibleChunkIfPresent(chunkPos);
        if (holder == null) {
            throw async$SPAWN_CACHE_MISS;
        }

        LevelChunk levelChunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
        if (levelChunk == null) {
            throw async$SPAWN_CACHE_MISS;
        }
        fullChunkGetter.accept(levelChunk);
    }

    @Unique
    private NaturalSpawner.SpawnState async$createSpawnState(int naturalSpawnChunkCount) {
        NaturalSpawner.ChunkGetter chunkGetter = ParallelProcessor.isSpawnExecutionThread()
                ? this::async$getLoadedFullChunkOrThrow
                : this::getFullChunk;
        return NaturalSpawner.createState(
                naturalSpawnChunkCount,
                this.level.getAllEntities(),
                chunkGetter,
                new LocalMobCapCalculator(this.chunkMap)
        );
    }

    @Unique
    private void async$publishSpawnStateSync(int naturalSpawnChunkCount) {
        synchronized (this.async$spawnStateLock) {
            this.async$spawnStateGeneration.incrementAndGet();
            this.lastSpawnState = async$createSpawnState(naturalSpawnChunkCount);
        }
    }

    @Unique
    private int async$nextSpawnStateGeneration() {
        synchronized (this.async$spawnStateLock) {
            return this.async$spawnStateGeneration.incrementAndGet();
        }
    }

    @Unique
    private boolean async$tryPublishSpawnStateAsync(int generation, NaturalSpawner.SpawnState spawnState) {
        synchronized (this.async$spawnStateLock) {
            if (!AsyncConfig.enableAsyncSpawn || ParallelProcessor.isShuttingDown()) {
                return false;
            }
            if (this.async$spawnStateGeneration.get() != generation) {
                return false;
            }

            this.lastSpawnState = spawnState;
            return true;
        }
    }

    @Inject(method = "tickChunks()V", at = @At("TAIL"))
    private void tickChunks(CallbackInfo ci) {
        async$flushSpawnFallbackSummaryIfDue();
        if (AsyncConfig.disabled || !AsyncConfig.enableAsyncSpawn) {
            return;
        }

        if (this.async$firstRunSpawnCounts) {
            this.async$firstRunSpawnCounts = false;
            this.async$spawnCountsReady.set(true);
        }
        if (this.async$spawnCountsReady.getAndSet(false)) {
            int naturalSpawnChunkCount = this.distanceManager.getNaturalSpawnChunkCount();
            int generation = async$nextSpawnStateGeneration();
            try {
                async$getSpawnExecutor().execute(() -> {
                    try {
                        NaturalSpawner.SpawnState spawnState = async$createSpawnState(naturalSpawnChunkCount);
                        if (async$tryPublishSpawnStateAsync(generation, spawnState)) {
                            this.async$consecutiveSpawnStateFailures.set(0);
                        }
                    } catch (AsyncSpawnCacheMissException ignored) {
                        async$recordAsyncSpawnStateFailure("chunk cache miss", null);
                    } catch (ParallelProcessor.AsyncAbortException ignored) {
                    } catch (Throwable throwable) {
                        async$recordAsyncSpawnStateFailure("error", throwable);
                    } finally {
                        this.async$spawnCountsReady.set(true);
                    }
                });
            } catch (RejectedExecutionException e) {
                this.async$spawnCountsReady.set(true);
                if (!ParallelProcessor.isShuttingDown()) {
                    async$publishSpawnStateSync(naturalSpawnChunkCount);
                }
            }
        }
    }

    @WrapMethod(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V")
    private void tickChunksSpawn(ProfilerFiller profiler, long timeInhabited, Operation<Void> original) {
        profiler.push("naturalSpawnCount");
        int naturalSpawnChunkCount = this.distanceManager.getNaturalSpawnChunkCount();
        boolean forceSyncSpawn = this.async$forceSyncSpawnNextTick.getAndSet(false);

        if (AsyncConfig.disabled || !AsyncConfig.enableAsyncSpawn || this.async$firstRunSpawnCounts || forceSyncSpawn) {
            async$publishSpawnStateSync(naturalSpawnChunkCount);
            if (forceSyncSpawn) {
                this.async$consecutiveSpawnStateFailures.set(0);
            }
        }

        boolean spawnMobs = this.level.getGameRules().get(GameRules.SPAWN_MOBS);
        int randomTickSpeed = this.level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        boolean useAsyncSpawn = !AsyncConfig.disabled && AsyncConfig.enableAsyncSpawn && !forceSyncSpawn;
        List<MobCategory> spawnCategories;
        if (spawnMobs) {
            boolean rareSpawnTick = this.level.getGameTime() % 400L == 0L;
            spawnCategories = NaturalSpawner.getFilteredSpawningCategories(
                    Objects.requireNonNull(this.lastSpawnState),
                    true,
                    this.spawnEnemies,
                    rareSpawnTick
            );
        } else {
            spawnCategories = List.of();
        }

        profiler.popPush("tickSpawningChunks");

        if (useAsyncSpawn) {
            NaturalSpawner.SpawnState currentState = this.lastSpawnState;
            if (currentState != null) {
                try {
                    CompletableFuture.runAsync(() -> {
                        List<LevelChunk> chunks = new ArrayList<>();
                        this.chunkMap.collectSpawningChunks(chunks);
                        Util.shuffle(chunks, this.level.random);
                        for (LevelChunk levelChunk : chunks) {
                            if (levelChunk != null) {
                                this.tickSpawningChunk(levelChunk, timeInhabited, spawnCategories, currentState);
                            }
                        }
                    }, async$getSpawnExecutor()).whenComplete((unused, throwable) -> {
                        if (throwable == null) {
                            this.async$consecutiveSpawnChunkFailures.set(0);
                            return;
                        }
                        if (async$isSpawnCacheMiss(throwable)) {
                            async$recordAsyncSpawnChunkFailure("chunk cache miss", null);
                            return;
                        }
                        if (ParallelProcessor.isAbortThrowable(throwable)) {
                            return;
                        }
                        async$recordAsyncSpawnChunkFailure("error", throwable);
                    });
                } catch (RejectedExecutionException e) {
                    if (!ParallelProcessor.isShuttingDown()) {
                        async$runSyncSpawnChunks(profiler, timeInhabited, spawnCategories, currentState);
                    }
                }
            }
        } else {
            async$runSyncSpawnChunks(profiler, timeInhabited, spawnCategories, this.lastSpawnState);
            if (forceSyncSpawn) {
                this.async$consecutiveSpawnChunkFailures.set(0);
            }
        }

        profiler.popPush("tickTickingChunks");
        this.chunkMap.forEachBlockTickingChunk(chunk -> this.level.tickChunk(chunk, randomTickSpeed));
        if (spawnMobs) {
            profiler.popPush("customSpawners");
            this.level.tickCustomSpawners(this.spawnEnemies);
        }
        profiler.pop();
    }

    @Unique
    private Executor async$getSpawnExecutor() {
        return ParallelProcessor.spawnPool != null ? ParallelProcessor.spawnPool : ParallelProcessor.tickPool;
    }

    @Unique
    private boolean async$shouldAbortChunkWait() {
        return ParallelProcessor.isShuttingDown() || Thread.currentThread().isInterrupted();
    }

    @Unique
    private boolean async$isSpawnCacheMiss(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof AsyncSpawnCacheMissException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    @Unique
    private void async$recordAsyncSpawnStateFailure(String reason, @Nullable Throwable throwable) {
        if (ParallelProcessor.isShuttingDown() || !AsyncConfig.enableAsyncSpawn) {
            return;
        }

        this.async$forceSyncSpawnNextTick.set(true);
        int failures = this.async$consecutiveSpawnStateFailures.incrementAndGet();
        async$handleAsyncSpawnFailure("spawn state creation", failures, reason, throwable);
    }

    @Unique
    private void async$recordAsyncSpawnChunkFailure(String reason, @Nullable Throwable throwable) {
        if (ParallelProcessor.isShuttingDown() || !AsyncConfig.enableAsyncSpawn) {
            return;
        }

        this.async$forceSyncSpawnNextTick.set(true);
        int failures = this.async$consecutiveSpawnChunkFailures.incrementAndGet();
        async$handleAsyncSpawnFailure("spawn chunk execution", failures, reason, throwable);
    }

    @Unique
    private void async$handleAsyncSpawnFailure(String phase, int failures, String reason, @Nullable Throwable throwable) {
        async$recordSpawnFallbackSummary("spawn state creation".equals(phase));

        int threshold = AsyncConfig.maxConsecutiveAsyncSpawnFailures;
        if (threshold > 0 && failures >= threshold && AsyncConfig.enableAsyncSpawn) {
            AsyncConfig.enableAsyncSpawn = false;
            async$clearSpawnFallbackSummary();
            boolean persisted = async$persistAsyncSpawnDisable();
            String message = "Disabling async entity spawning after {} consecutive {} failures in {} (reason: {}). {}";
            String persistenceMessage = persisted
                    ? "Saved enableAsyncSpawn=false to config."
                    : "Runtime flag disabled, but saving config failed.";
            if (throwable != null) {
                ParallelProcessor.LOGGER.error(message, failures, phase, this.level.dimension().toString(), reason, persistenceMessage, throwable);
            } else {
                ParallelProcessor.LOGGER.error(message, failures, phase, this.level.dimension().toString(), reason, persistenceMessage);
            }
        }
    }

    @Unique
    private boolean async$persistAsyncSpawnDisable() {
        try {
            PlatformUtils.saveConfig();
            return true;
        } catch (Throwable throwable) {
            ParallelProcessor.LOGGER.error("Failed to save async spawn circuit breaker state to config", throwable);
            return false;
        }
    }

    @Unique
    private synchronized void async$recordSpawnFallbackSummary(boolean spawnStateFailure) {
        if (this.async$spawnFallbackSummaryWindowStartNanos == 0L) {
            this.async$spawnFallbackSummaryWindowStartNanos = System.nanoTime();
        }

        if (spawnStateFailure) {
            this.async$spawnStateFallbackSummaryCount++;
        } else {
            this.async$spawnChunkFallbackSummaryCount++;
        }
    }

    @Unique
    private synchronized void async$flushSpawnFallbackSummaryIfDue() {
        long windowStartNanos = this.async$spawnFallbackSummaryWindowStartNanos;
        if (windowStartNanos == 0L) {
            return;
        }

        long elapsedNanos = System.nanoTime() - windowStartNanos;
        if (elapsedNanos < async$SPAWN_FALLBACK_SUMMARY_INTERVAL_NANOS) {
            return;
        }

        int spawnStateFallbacks = this.async$spawnStateFallbackSummaryCount;
        int spawnChunkFallbacks = this.async$spawnChunkFallbackSummaryCount;
        int totalFallbacks = spawnStateFallbacks + spawnChunkFallbacks;

        this.async$spawnFallbackSummaryWindowStartNanos = 0L;
        this.async$spawnStateFallbackSummaryCount = 0;
        this.async$spawnChunkFallbackSummaryCount = 0;

        if (totalFallbacks <= 0) {
            return;
        }

        ParallelProcessor.LOGGER.warn(
                "Async spawn fell back to synchronous spawning {} time(s) in {} over the last 5 minutes (state creation: {}, chunk execution: {})",
                totalFallbacks,
                this.level.dimension().toString(),
                spawnStateFallbacks,
                spawnChunkFallbacks
        );
    }

    @Unique
    private synchronized void async$clearSpawnFallbackSummary() {
        this.async$spawnFallbackSummaryWindowStartNanos = 0L;
        this.async$spawnStateFallbackSummaryCount = 0;
        this.async$spawnChunkFallbackSummaryCount = 0;
    }

    @Unique
    private void async$runSyncSpawnChunks(ProfilerFiller profiler, long timeInhabited, List<MobCategory> spawnCategories, @Nullable NaturalSpawner.SpawnState spawnState) {
        List<LevelChunk> chunks = this.spawningChunks;
        try {
            profiler.popPush("filteringSpawningChunks");
            this.chunkMap.collectSpawningChunks(chunks);
            profiler.popPush("shuffleSpawningChunks");
            Util.shuffle(chunks, this.level.random);
            profiler.popPush("tickSpawningChunks");

            for (LevelChunk levelChunk : chunks) {
                this.tickSpawningChunk(levelChunk, timeInhabited, spawnCategories, spawnState);
            }
        } finally {
            chunks.clear();
        }
    }
}
