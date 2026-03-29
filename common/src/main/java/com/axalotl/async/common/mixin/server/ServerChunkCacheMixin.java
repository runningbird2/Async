package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.AsyncSpawnCacheMissException;
import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.spawn.AsyncMonsterGlobalCapControl;
import com.axalotl.async.common.spawn.AsyncServerChunkCacheSpawnStateAccess;
import com.axalotl.async.common.spawn.AsyncSpawnPhaseContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

@Mixin(value = ServerChunkCache.class, priority = 1500)
public abstract class ServerChunkCacheMixin extends ChunkSource implements AsyncServerChunkCacheSpawnStateAccess {

    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final Thread mainThread;
    @Shadow @Final public ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    @Shadow @Final private DistanceManager distanceManager;
    @Shadow @Final private ServerLevel level;
    @Shadow private volatile NaturalSpawner.@Nullable SpawnState lastSpawnState;
    @Shadow private boolean spawnEnemies;
    @Shadow @Final @Mutable private Set<ChunkHolder> chunkHoldersToBroadcast;
    @Shadow @Final private List<LevelChunk> spawningChunks;

    @Shadow public abstract @Nullable ChunkHolder getVisibleChunkIfPresent(long pos);
    @Shadow protected abstract CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int x, int z, ChunkStatus leastStatus, boolean create);
    @Shadow protected abstract void getFullChunk(long chunkPos, Consumer<LevelChunk> fullChunkGetter);
    @Shadow public abstract void tickSpawningChunk(LevelChunk chunk, long timeInhabited, List<MobCategory> spawnCategories, NaturalSpawner.SpawnState spawnState);

    @Unique private volatile CompletableFuture<Void> async$spawnFuture;
    @Unique private long async$capturedTimeDiff;
    @Unique private boolean async$spawnLaunchedThisTick;
    @Unique private long async$disableAsyncSpawnUntilTick;

    @Unique private static final long INITIAL_PARK_NS = 50_000L;
    @Unique private static final long MAX_PARK_NS = 1_000_000L;
    @Unique private static final long SPAWN_WAIT_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
    @Unique private static final long SPAWN_FALLBACK_SUMMARY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);
    @Unique private static final int SPAWN_DISABLE_COOLDOWN_TICKS = 20;

    @Unique private long async$spawnFallbackSummaryWindowStartNanos;
    @Unique private int async$spawnFallbackSummaryCount;
    @Unique private final Map<String, Integer> async$spawnMissReasonCounts = new LinkedHashMap<>();
    @Unique private final Map<String, Long> async$spawnMissReasonSampleChunks = new LinkedHashMap<>();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void async$replaceWithConcurrentSet(CallbackInfo ci) {
        this.chunkHoldersToBroadcast = ConcurrentHashMap.newKeySet();
    }

    @Override
    public @Nullable NaturalSpawner.SpawnState async$getLastSpawnState() {
        return this.lastSpawnState;
    }

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
            if (AsyncSpawnPhaseContext.isActive()) {
                throw async$spawnMiss(async$describeChunkWaitReason(holder, leastStatus), pos);
            }
            cir.setReturnValue(null);
            return;
        }

        CompletableFuture<?> ticketTask = CompletableFuture.runAsync(
                () -> this.getChunkFutureMainThread(x, z, leastStatus, true),
                this.mainThreadProcessor
        );
        cir.setReturnValue(async$awaitAfterTicket(ticketTask, pos, leastStatus));
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void async$getChunkNow(int chunkX, int chunkZ, CallbackInfoReturnable<LevelChunk> cir) {
        if (Thread.currentThread() == this.mainThread) return;

        long pos = ChunkPos.asLong(chunkX, chunkZ);
        ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
        if (holder == null) {
            if (AsyncSpawnPhaseContext.isActive()) {
                throw async$spawnMiss("visible chunk holder missing", pos);
            }
            cir.setReturnValue(null);
            return;
        }

        ChunkAccess chunk = holder.getChunkIfPresent(ChunkStatus.FULL);
        if (chunk instanceof LevelChunk lc) {
            cir.setReturnValue(lc);
            return;
        }

        LevelChunk tickingChunk = holder.getTickingChunk();
        if (tickingChunk != null) {
            cir.setReturnValue(tickingChunk);
            return;
        }

        if (AsyncSpawnPhaseContext.isActive()) {
            throw async$spawnMiss(async$describeChunkWaitReason(holder, ChunkStatus.FULL), pos);
        }
        cir.setReturnValue(null);
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
        long startNanos = System.nanoTime();
        long sleepNs = INITIAL_PARK_NS;
        while (!future.isDone()) {
            ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
            if (holder != null) {
                ChunkAccess ready = async$extractReady(holder, status);
                if (ready != null) {
                    return ready;
                }
            }
            if (AsyncSpawnPhaseContext.isActive() && System.nanoTime() - startNanos >= SPAWN_WAIT_TIMEOUT_NANOS) {
                throw async$spawnMiss(async$describeChunkWaitReason(holder, status), pos);
            }
            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }
        return async$extractFromFuture(future);
    }

    @Unique
    private @Nullable ChunkAccess async$awaitAfterTicket(CompletableFuture<?> ticketTask, long pos, ChunkStatus status) {
        long startNanos = System.nanoTime();
        long sleepNs = INITIAL_PARK_NS;
        for (;;) {
            if (ticketTask.isCompletedExceptionally()) {
                ticketTask.join();
            }

            ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
            if (holder != null) {
                ChunkAccess ready = async$extractReady(holder, status);
                if (ready != null) {
                    return ready;
                }

                CompletableFuture<?> directFuture = async$findPendingFuture(holder, status);
                if (directFuture != null) {
                    return async$awaitWithProbing(directFuture, pos, status);
                }

                ChunkAccess fromHolder = async$extractCompleted(holder, status);
                if (fromHolder != null) {
                    return fromHolder;
                }
            }

            if (AsyncSpawnPhaseContext.isActive() && System.nanoTime() - startNanos >= SPAWN_WAIT_TIMEOUT_NANOS) {
                throw async$spawnMiss(async$describeChunkWaitReason(holder, status), pos);
            }

            LockSupport.parkNanos(sleepNs);
            sleepNs = Math.min(sleepNs << 1, MAX_PARK_NS);
        }
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
            if (chunk instanceof ChunkAccess chunkAccess) {
                return async$unwrap(chunkAccess);
            }
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
    private NaturalSpawner.SpawnState async$captureTimeAndPassthrough(
            int count,
            Iterable<Entity> entities,
            NaturalSpawner.ChunkGetter chunkGetter,
            LocalMobCapCalculator calculator,
            Operation<NaturalSpawner.SpawnState> original,
            ProfilerFiller profiler,
            long timeDiff
    ) {
        this.async$capturedTimeDiff = timeDiff;
        this.async$spawnLaunchedThisTick = false;
        this.async$spawnFuture = null;
        return original.call(count, entities, chunkGetter, calculator);
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;tickSpawningChunk(Lnet/minecraft/world/level/chunk/LevelChunk;JLjava/util/List;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V"
            )
    )
    private void async$interceptTickSpawning(
            ServerChunkCache instance,
            LevelChunk chunk,
            long timeInhabited,
            List<MobCategory> categories,
            NaturalSpawner.SpawnState state,
            Operation<Void> original
    ) {
        if (AsyncConfig.disabled || !AsyncConfig.enableAsyncSpawn || async$shouldUseSyncSpawnThisTick()) {
            original.call(instance, chunk, timeInhabited, categories, state);
            return;
        }

        if (this.async$spawnLaunchedThisTick) {
            return;
        }
        this.async$spawnLaunchedThisTick = true;

        List<LevelChunk> list = this.spawningChunks;
        if (list == null || list.isEmpty()) {
            return;
        }

        LevelChunk[] chunks = list.toArray(new LevelChunk[0]);
        for (int i = chunks.length - 1; i > 0; i--) {
            int j = this.level.random.nextInt(i + 1);
            LevelChunk tmp = chunks[i];
            chunks[i] = chunks[j];
            chunks[j] = tmp;
        }

        long timeDiff = this.async$capturedTimeDiff;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunks.length * 9);
        for (LevelChunk spawningChunk : chunks) {
            ChunkPos cp = spawningChunk.getPos();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    set.add(ChunkPos.asLong(cp.x + dx, cp.z + dz));
                }
            }
        }
        ParallelProcessor.spawnableChunkPositions = set;

        int poolSize = Math.max(1, ParallelProcessor.getEffectiveSpawnPoolSize());
        int batchSize = Math.max(4, (chunks.length + poolSize - 1) / poolSize);
        int batchCount = (chunks.length + batchSize - 1) / batchSize;
        CompletableFuture<?>[] futures = new CompletableFuture<?>[batchCount];
        ServerLevel level = this.level;

        for (int b = 0, idx = 0; b < chunks.length; b += batchSize, idx++) {
            int start = b;
            int end = Math.min(b + batchSize, chunks.length);
            futures[idx] = ParallelProcessor.submitSpawnTask(() -> {
                AsyncSpawnPhaseContext.push();
                try {
                    for (int i = start; i < end; i++) {
                        LevelChunk spawningChunk = chunks[i];
                        spawningChunk.incrementInhabitedTime(timeDiff);
                        NaturalSpawner.spawnForChunk(level, spawningChunk, state, categories);
                    }
                } finally {
                    AsyncSpawnPhaseContext.pop();
                }
            });
        }

        this.async$spawnFuture = CompletableFuture.allOf(futures);
    }

    @WrapOperation(
            method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"
            )
    )
    private void async$waitSpawnThenBlockTick(ChunkMap instance, Consumer<LevelChunk> consumer, Operation<Void> original) {
        async$flushSpawnFallbackSummaryIfDue();

        CompletableFuture<Void> spawnFuture = this.async$spawnFuture;
        if (spawnFuture != null) {
            try {
                async$pumpUntilDone(spawnFuture);
                if (spawnFuture.isCompletedExceptionally()) {
                    spawnFuture.join();
                }
            } catch (Throwable throwable) {
                async$handleAsyncSpawnFailure(throwable);
            } finally {
                this.async$spawnFuture = null;
                this.async$spawnLaunchedThisTick = false;
                ParallelProcessor.spawnableChunkPositions = null;
            }
        }

        if (AsyncConfig.disabled || !AsyncConfig.enableAsyncRandomTicks) {
            original.call(instance, consumer);
            return;
        }

        List<LevelChunk> tickChunks = new ArrayList<>();
        original.call(instance, (Consumer<LevelChunk>) tickChunks::add);
        if (tickChunks.isEmpty()) {
            return;
        }

        int poolSize = Math.max(1, ParallelProcessor.getPoolSize());
        int batchSize = Math.max(1, (tickChunks.size() + poolSize - 1) / poolSize);
        int batchCount = (tickChunks.size() + batchSize - 1) / batchSize;
        CompletableFuture<?>[] futures = new CompletableFuture<?>[batchCount];

        for (int b = 0, idx = 0; b < tickChunks.size(); b += batchSize, idx++) {
            int start = b;
            int end = Math.min(b + batchSize, tickChunks.size());
            futures[idx] = CompletableFuture.runAsync(() -> {
                for (int j = start; j < end; j++) {
                    consumer.accept(tickChunks.get(j));
                }
            }, ParallelProcessor.tickPool);
        }

        async$pumpUntilDone(CompletableFuture.allOf(futures));
    }

    @Unique
    private boolean async$shouldUseSyncSpawnThisTick() {
        return this.level.getGameTime() < this.async$disableAsyncSpawnUntilTick;
    }

    @Unique
    private void async$handleAsyncSpawnFailure(Throwable throwable) {
        AsyncSpawnCacheMissException miss = async$findSpawnMiss(throwable);
        synchronized (this) {
            long now = System.nanoTime();
            if (this.async$spawnFallbackSummaryWindowStartNanos == 0L) {
                this.async$spawnFallbackSummaryWindowStartNanos = now;
            }
            this.async$spawnFallbackSummaryCount++;
            if (miss != null) {
                async$recordSpawnMissReasonLocked(miss);
            } else {
                async$recordSpawnMissReasonLocked(new AsyncSpawnCacheMissException("error", Long.MIN_VALUE));
            }
        }

        this.async$disableAsyncSpawnUntilTick = Math.max(
                this.async$disableAsyncSpawnUntilTick,
                this.level.getGameTime() + SPAWN_DISABLE_COOLDOWN_TICKS
        );

        if (miss == null && !ParallelProcessor.isAbortThrowable(throwable)) {
            ParallelProcessor.LOGGER.error("Async spawn execution failed in {}", this.level.dimension(), throwable);
        }
    }

    @Unique
    private @Nullable AsyncSpawnCacheMissException async$findSpawnMiss(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof AsyncSpawnCacheMissException miss) {
                return miss;
            }
            throwable = throwable.getCause();
        }
        return null;
    }

    @Unique
    private AsyncSpawnCacheMissException async$spawnMiss(String reason, long chunkPos) {
        return new AsyncSpawnCacheMissException(reason, chunkPos);
    }

    @Unique
    private String async$describeChunkWaitReason(@Nullable ChunkHolder holder, ChunkStatus status) {
        if (holder == null) {
            return "visible chunk holder missing";
        }
        if (status == ChunkStatus.FULL) {
            if (holder.getTickingChunk() != null) {
                return "ticking chunk fallback used";
            }
            if (!holder.getFullChunkFuture().isDone()) {
                return "visible chunk full future not ready and no ticking chunk";
            }
            return "visible chunk has neither full nor ticking chunk";
        }
        return "visible chunk not ready for " + status;
    }

    @Unique
    private void async$pumpUntilDone(CompletableFuture<?> future) {
        while (!future.isDone()) {
            boolean pumped = false;
            for (ServerLevel serverLevel : ParallelProcessor.getServer().getAllLevels()) {
                pumped |= serverLevel.getChunkSource().pollTask();
            }
            if (!pumped) {
                Thread.onSpinWait();
            }
        }
        if (future.isCompletedExceptionally()) {
            future.join();
        }
    }

    @Unique
    private synchronized void async$recordSpawnMissReasonLocked(AsyncSpawnCacheMissException miss) {
        this.async$spawnMissReasonCounts.merge(miss.async$getReason(), 1, Integer::sum);
        if (miss.async$hasChunkPos()) {
            this.async$spawnMissReasonSampleChunks.putIfAbsent(miss.async$getReason(), miss.async$getChunkPos());
        }
    }

    @Unique
    private synchronized void async$flushSpawnFallbackSummaryIfDue() {
        String schedulingSummary = ParallelProcessor.maybeDrainSpawnSchedulingSummary(SPAWN_FALLBACK_SUMMARY_INTERVAL_NANOS);
        if (schedulingSummary != null) {
            ParallelProcessor.LOGGER.warn(
                    "Async spawn task scheduling over the last 5 minutes: {}",
                    schedulingSummary
            );
        }

        long windowStartNanos = this.async$spawnFallbackSummaryWindowStartNanos;
        if (windowStartNanos == 0L) {
            return;
        }
        long elapsedNanos = System.nanoTime() - windowStartNanos;
        if (elapsedNanos < SPAWN_FALLBACK_SUMMARY_INTERVAL_NANOS) {
            return;
        }

        int totalFallbacks = this.async$spawnFallbackSummaryCount;
        String missSummary = async$describeSpawnMissReasonsLocked();
        this.async$spawnFallbackSummaryWindowStartNanos = 0L;
        this.async$spawnFallbackSummaryCount = 0;
        this.async$spawnMissReasonCounts.clear();
        this.async$spawnMissReasonSampleChunks.clear();

        if (totalFallbacks <= 0) {
            return;
        }

        ParallelProcessor.LOGGER.warn(
                "Async spawn fell back off same-tick parallel spawning {} time(s) in {} over the last 5 minutes",
                totalFallbacks,
                this.level.dimension()
        );
        if (!"unavailable".equals(missSummary)) {
            ParallelProcessor.LOGGER.warn(
                    "Async spawn miss reasons in {}: {}",
                    this.level.dimension(),
                    missSummary
            );
        }
        ParallelProcessor.LOGGER.warn(
                "Async spawn fallback global mobcaps in {}: {}",
                this.level.dimension(),
                async$describeGlobalMobcaps(this.lastSpawnState)
        );
    }

    @Unique
    private synchronized String async$describeSpawnMissReasonsLocked() {
        if (this.async$spawnMissReasonCounts.isEmpty()) {
            return "unavailable";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, Integer> entry : this.async$spawnMissReasonCounts.entrySet()) {
            StringBuilder detail = new StringBuilder()
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue());
            Long sampleChunk = this.async$spawnMissReasonSampleChunks.get(entry.getKey());
            if (sampleChunk != null) {
                ChunkPos pos = new ChunkPos(sampleChunk);
                detail.append(" (sampleChunk=").append(pos.x).append(',').append(pos.z).append(')');
            }
            joiner.add(detail.toString());
        }
        return joiner.toString();
    }

    @Unique
    private String async$describeGlobalMobcaps(@Nullable NaturalSpawner.SpawnState spawnState) {
        if (!(spawnState instanceof AsyncMonsterGlobalCapControl mobcapControl)) {
            return "unavailable";
        }

        StringJoiner joiner = new StringJoiner(", ");
        joiner.add("spawnableChunks=" + mobcapControl.async$getSpawnableChunkCount());
        for (MobCategory category : MobCategory.values()) {
            if (category == MobCategory.MISC) {
                continue;
            }
            joiner.add(category.getName() + "="
                    + mobcapControl.async$getEffectiveMobCount(category)
                    + "/"
                    + mobcapControl.async$getGlobalMobCap(category));
        }
        return joiner.toString();
    }
}
