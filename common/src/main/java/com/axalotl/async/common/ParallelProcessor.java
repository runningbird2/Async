package com.axalotl.async.common;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.parallelised.utils.AsyncNavigationTracker;
import com.axalotl.async.common.parallelised.utils.PortalTeleportationManager;
import com.axalotl.async.common.spawn.EntityBookkeepingTelemetry;
import com.axalotl.async.common.spawn.MonsterDespawnAreaTelemetry;
import com.axalotl.async.common.spawn.MonsterDespawnReasonTelemetry;
import lombok.Setter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class ParallelProcessor {
    public static final Logger LOGGER = LogManager.getLogger(ParallelProcessor.class);
    private static final int ASYNC_ABORT_SYNC_COOLDOWN_TICKS = 5;
    private static final long FALLBACK_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_DIAGNOSTIC_SAMPLES = 8;
    private static final int MAX_TOP_DIAGNOSTIC_ENTRIES = 5;

    @Setter
    private static MinecraftServer server;

    public static MinecraftServer getServer() {
        return server;
    }

    public static final AtomicInteger currentEntities = new AtomicInteger();
    private static final AtomicInteger threadPoolID = new AtomicInteger();
    public static ExecutorService tickPool;
    private static final Set<UUID> blacklistedEntity = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> temporarilySynchronizedEntities = new ConcurrentHashMap<>();
    private static final Map<String, Set<WeakReference<Thread>>> mcThreadTracker = new ConcurrentHashMap<>();
    private static final LongAdder asyncEntityTickAbortCount = new LongAdder();
    private static final LongAdder asyncEntityTickCooldownCount = new LongAdder();
    private static final LongAdder asyncEntityTickSyncFallbackCount = new LongAdder();
    private static final LongAdder asyncEntityTickSkippedCount = new LongAdder();
    private static final LongAdder asyncEntityTickGetChunkCalls = new LongAdder();
    private static final LongAdder asyncEntityTickGetChunkHits = new LongAdder();
    private static final LongAdder asyncEntityTickGetChunkNowCalls = new LongAdder();
    private static final LongAdder asyncEntityTickGetChunkNowHits = new LongAdder();
    private static final Map<String, LongAdder> asyncEntityTickAbortReasonCounts = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> asyncEntityTickAbortEntityCounts = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> asyncEntityTickReadyMissReasonCounts = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> asyncEntityTickReadyMissEntityCounts = new ConcurrentHashMap<>();
    private static final LongAdder asyncMonsterTickCount = new LongAdder();
    private static final LongAdder syncMonsterTickCount = new LongAdder();
    private static final LongAdder monsterDespawnCheckCount = new LongAdder();
    private static final LongAdder monsterDespawnRemovedCount = new LongAdder();
    private static final Map<String, LongAdder> asyncMonsterTickEntityCounts = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> syncMonsterTickEntityCounts = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> monsterDespawnCheckEntityCounts = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> monsterDespawnRemovedEntityCounts = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> asyncEntityTickAbortSamples = new ConcurrentLinkedDeque<>();
    private static final ConcurrentLinkedDeque<String> asyncEntityTickReadyMissSamples = new ConcurrentLinkedDeque<>();
    private static final AtomicLong nextFallbackLogNanos = new AtomicLong(System.nanoTime() + FALLBACK_LOG_INTERVAL_NANOS);
    private static final ThreadLocal<Boolean> IS_POOL_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IS_ENTITY_TICK_CONTEXT = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Long> CURRENT_ENTITY_CHUNK_POS = ThreadLocal.withInitial(() -> Long.MIN_VALUE);
    private static final ThreadLocal<String> CURRENT_ENTITY_TYPE_ID = ThreadLocal.withInitial(() -> "unknown");
    public static final Set<Class<?>> BLOCKED_ENTITIES = Set.of(
            FallingBlockEntity.class,
            Shulker.class,
            AbstractBoat.class
    );
    private static volatile boolean isShuttingDown = false;

    public static void setupThreadPool(int parallelism, Class<?> asyncClass) {
        PortalTeleportationManager.init(server);
        isShuttingDown = false;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(() -> {
                IS_POOL_THREAD.set(Boolean.TRUE);
                runnable.run();
            }, "Async-Tick-Pool-Thread-" + threadPoolID.getAndIncrement());
            registerThread("Async-Tick", thread);
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setContextClassLoader(asyncClass.getClassLoader());
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory
        );
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        executor.prestartAllCoreThreads();
        tickPool = executor;
        LOGGER.info("Initialized Pool with {} threads", parallelism);
    }

    public static void registerThread(String poolName, Thread thread) {
        mcThreadTracker
                .computeIfAbsent(poolName, key -> ConcurrentHashMap.newKeySet())
                .add(new WeakReference<>(thread));
    }

    public static boolean isServerExecutionThread() {
        return IS_POOL_THREAD.get();
    }

    public static boolean isEntityTickExecutionThread() {
        return IS_ENTITY_TICK_CONTEXT.get();
    }

    public static boolean canAccessChunkForAsyncEntityTick(long chunkPosLong) {
        if (!isEntityTickExecutionThread()) {
            return true;
        }

        long currentChunkPos = CURRENT_ENTITY_CHUNK_POS.get();
        if (currentChunkPos == Long.MIN_VALUE) {
            return false;
        }

        int currentChunkX = (int) currentChunkPos;
        int currentChunkZ = (int) (currentChunkPos >> 32);
        int requestedChunkX = (int) chunkPosLong;
        int requestedChunkZ = (int) (chunkPosLong >> 32);
        return Math.abs(currentChunkX - requestedChunkX) <= 1 && Math.abs(currentChunkZ - requestedChunkZ) <= 1;
    }

    public static void recordAsyncEntityTickAbort(String reason, long requestedChunkPosLong) {
        if (!isEntityTickExecutionThread()) {
            return;
        }
        async$incrementCounter(asyncEntityTickAbortReasonCounts, reason);
        async$incrementCounter(asyncEntityTickAbortEntityCounts, CURRENT_ENTITY_TYPE_ID.get());
        async$pushSample(asyncEntityTickAbortSamples, async$formatChunkAccessSample(reason, requestedChunkPosLong));
    }

    public static void recordAsyncEntityTickReadyMiss(String reason, long requestedChunkPosLong) {
        if (!isEntityTickExecutionThread()) {
            return;
        }
        async$incrementCounter(asyncEntityTickReadyMissReasonCounts, reason);
        async$incrementCounter(asyncEntityTickReadyMissEntityCounts, CURRENT_ENTITY_TYPE_ID.get());
        async$pushSample(asyncEntityTickReadyMissSamples, async$formatChunkAccessSample(reason, requestedChunkPosLong));
    }

    public static boolean isShuttingDown() {
        return isShuttingDown;
    }

    public static int getPoolSize() {
        return ((ThreadPoolExecutor) tickPool).getCorePoolSize();
    }

    @SuppressWarnings("unchecked")
    public static void callEntityTickBatch(ServerLevel world, List<Entity> entities) {
        if (entities.isEmpty()) return;
        if (AsyncConfig.disabled) {
            entities.forEach(e -> tickSynchronously(world, e));
            return;
        }

        List<Entity> asyncEntities = new ArrayList<>();
        List<Entity> syncEntities = new ArrayList<>();
        for (Entity entity : entities) {
            if (shouldTickSynchronously(entity)) {
                syncEntities.add(entity);
            } else {
                asyncEntities.add(entity);
            }
        }

        if (asyncEntities.isEmpty()) {
            syncEntities.forEach(entity -> tickSynchronously(world, entity));
            return;
        }

        int poolSize = getPoolSize();
        int chunkSize = Math.max(1, (asyncEntities.size() + poolSize - 1) / poolSize);

        List<Future<Void>> futures = new ArrayList<>();
        Queue<Entity> syncFallbackEntities = new ConcurrentLinkedQueue<>();
        int submittedUntil = 0;
        try {
            for (int i = 0; i < asyncEntities.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, asyncEntities.size());
                List<Entity> chunk = asyncEntities.subList(i, end);
                Future<Void> future = (Future<Void>) tickPool.submit(() -> {
                    for (Entity entity : chunk) {
                        if (entity.isRemoved()) continue;
                        if (shouldTickSynchronously(entity)) {
                            syncFallbackEntities.add(entity);
                            continue;
                        }
                        try {
                            performAsyncEntityTick(world, entity);
                        } catch (AsyncAbortException ignored) {
                            asyncEntityTickAbortCount.increment();
                            markEntityForSynchronousHandling(entity);
                            asyncEntityTickSkippedCount.increment();
                        }
                    }
                });
                futures.add(future);
                submittedUntil = end;
            }
        } catch (RejectedExecutionException e) {
            if (!isShuttingDown) {
                LOGGER.warn("Async tick pool rejected entity batch; falling back to synchronous ticking", e);
            }
            syncEntities.addAll(asyncEntities.subList(submittedUntil, asyncEntities.size()));
            syncEntities.forEach(entity -> tickSynchronously(world, entity));
            waitForFutures(futures);
            for (Future<Void> future : futures) {
                if (isShuttingDown && !future.isDone()) {
                    continue;
                }
                try {
                    future.get();
                } catch (Exception futureException) {
                    LOGGER.error("Error in async entity tick", futureException);
                }
            }
            for (Entity entity : syncFallbackEntities) {
                asyncEntityTickSyncFallbackCount.increment();
                tickSynchronously(world, entity);
            }
            return;
        }

        syncEntities.forEach(entity -> tickSynchronously(world, entity));

        waitForFutures(futures);

        for (Future<Void> future : futures) {
            if (isShuttingDown && !future.isDone()) {
                continue;
            }
            try {
                future.get();
            } catch (Exception e) {
                if (isAbortThrowable(e)) {
                    continue;
                }
                LOGGER.error("Error in async entity tick", e);
            }
        }

        for (Entity entity : syncFallbackEntities) {
            asyncEntityTickSyncFallbackCount.increment();
            tickSynchronously(world, entity);
        }

        maybeLogFallbackSummary();
    }

    @SuppressWarnings("unchecked")
    public static void callEntityDespawnCheckBatch(List<Entity> entities) {
        if (entities.isEmpty()) return;
        if (AsyncConfig.disabled) {
            entities.forEach(ParallelProcessor::checkDespawnSynchronously);
            return;
        }

        int poolSize = Math.max(1, getPoolSize());
        int chunkSize = Math.max(1, (entities.size() + poolSize - 1) / poolSize);

        List<Future<Void>> futures = new ArrayList<>();
        int submittedUntil = 0;
        try {
            for (int i = 0; i < entities.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, entities.size());
                List<Entity> chunk = entities.subList(i, end);
                Future<Void> future = (Future<Void>) tickPool.submit(() -> {
                    for (Entity entity : chunk) {
                        if (entity.isRemoved()) continue;
                        recordMonsterDespawnCheck(entity);
                        entity.checkDespawn();
                        recordMonsterDespawnRemoved(entity);
                    }
                });
                futures.add(future);
                submittedUntil = end;
            }
        } catch (RejectedExecutionException e) {
            if (!isShuttingDown) {
                LOGGER.warn("Async tick pool rejected despawn batch; falling back to synchronous despawn checks", e);
            }
            entities.subList(submittedUntil, entities.size()).forEach(ParallelProcessor::checkDespawnSynchronously);
            waitForFutures(futures);
            for (Future<Void> future : futures) {
                if (isShuttingDown && !future.isDone()) {
                    continue;
                }
                try {
                    future.get();
                } catch (Exception futureException) {
                    LOGGER.error("Error in async entity despawn check", futureException);
                }
            }
            return;
        }

        waitForFutures(futures);

        for (Future<Void> future : futures) {
            if (isShuttingDown && !future.isDone()) {
                continue;
            }
            try {
                future.get();
            } catch (Exception e) {
                LOGGER.error("Error in async entity despawn check", e);
            }
        }
    }

    private static void waitForFutures(List<? extends Future<?>> futures) {
        boolean allDone;
        do {
            allDone = futures.stream().allMatch(Future::isDone);
            if (!allDone) {
                if (isShuttingDown) {
                    break;
                }
                boolean pumped = false;
                MinecraftServer currentServer = server;
                if (currentServer != null) {
                    for (ServerLevel lvl : currentServer.getAllLevels()) {
                        pumped |= lvl.getChunkSource().pollTask();
                    }
                }
                if (!pumped) {
                    Thread.onSpinWait();
                }
            }
        } while (!allDone);
    }

    public static boolean shouldTickSynchronously(Entity entity) {
        if (isShuttingDown) {
            return true;
        }
        if (entity.level().isClientSide()) {
            return true;
        }

        UUID entityId = entity.getUUID();
        Integer syncUntilTick = temporarilySynchronizedEntities.get(entityId);
        if (syncUntilTick != null) {
            MinecraftServer currentServer = server;
            if (currentServer == null || currentServer.getTickCount() < syncUntilTick) {
                return true;
            }
            temporarilySynchronizedEntities.remove(entityId, syncUntilTick);
        }

        return AsyncConfig.disabled ||
                entity instanceof Projectile ||
                entity instanceof AbstractMinecart ||
                entity instanceof ServerPlayer ||
                entity instanceof Mob mob && entity.level() instanceof AsyncNavigationTracker navigationTracker && navigationTracker.async$isNavigationActive(mob) ||
                BLOCKED_ENTITIES.contains(entity.getClass()) ||
                blacklistedEntity.contains(entityId) ||
                AsyncConfig.isEntitySynchronized(EntityType.getKey(entity.getType()));
    }

    private static void tickSynchronously(ServerLevel world, Entity entity) {
        if (entity.isRemoved()) {
            return;
        }
        async$recordMonsterSyncTick(entity);
        try {
            world.tickNonPassenger(entity);
        } catch (Exception e) {
            logEntityError(entity, e);
        }
    }

    private static void checkDespawnSynchronously(Entity entity) {
        if (entity.isRemoved()) {
            return;
        }
        recordMonsterDespawnCheck(entity);
        try {
            entity.checkDespawn();
            recordMonsterDespawnRemoved(entity);
        } catch (Exception e) {
            logDespawnError(entity, e);
        }
    }

    private static void performAsyncEntityTick(ServerLevel world, Entity entity) {
        currentEntities.incrementAndGet();
        IS_ENTITY_TICK_CONTEXT.set(Boolean.TRUE);
        CURRENT_ENTITY_CHUNK_POS.set(entity.chunkPosition().toLong());
        CURRENT_ENTITY_TYPE_ID.set(EntityType.getKey(entity.getType()).toString());
        async$recordMonsterAsyncTick(entity);
        try {
            world.tickNonPassenger(entity);
        } finally {
            CURRENT_ENTITY_CHUNK_POS.set(Long.MIN_VALUE);
            CURRENT_ENTITY_TYPE_ID.set("unknown");
            IS_ENTITY_TICK_CONTEXT.set(Boolean.FALSE);
            currentEntities.decrementAndGet();
        }
    }

    private static void markEntityForSynchronousHandling(Entity entity) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        asyncEntityTickCooldownCount.increment();
        temporarilySynchronizedEntities.put(entity.getUUID(), currentServer.getTickCount() + ASYNC_ABORT_SYNC_COOLDOWN_TICKS);
    }

    private static int pruneExpiredSynchronousCooldowns() {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            temporarilySynchronizedEntities.clear();
            return 0;
        }

        int currentTick = currentServer.getTickCount();
        temporarilySynchronizedEntities.entrySet().removeIf(entry -> entry.getValue() <= currentTick);
        return temporarilySynchronizedEntities.size();
    }

    private static void maybeLogFallbackSummary() {
        long now = System.nanoTime();
        long nextLogAt = nextFallbackLogNanos.get();
        if (now < nextLogAt) {
            return;
        }
        if (!nextFallbackLogNanos.compareAndSet(nextLogAt, now + FALLBACK_LOG_INTERVAL_NANOS)) {
            return;
        }

        long aborts = asyncEntityTickAbortCount.sumThenReset();
        long cooldowns = asyncEntityTickCooldownCount.sumThenReset();
        long syncFallbacks = asyncEntityTickSyncFallbackCount.sumThenReset();
        long skippedTicks = asyncEntityTickSkippedCount.sumThenReset();
        long getChunkCalls = asyncEntityTickGetChunkCalls.sumThenReset();
        long getChunkHits = asyncEntityTickGetChunkHits.sumThenReset();
        long getChunkNowCalls = asyncEntityTickGetChunkNowCalls.sumThenReset();
        long getChunkNowHits = asyncEntityTickGetChunkNowHits.sumThenReset();
        long asyncMonsterTicks = asyncMonsterTickCount.sumThenReset();
        long syncMonsterTicks = syncMonsterTickCount.sumThenReset();
        long despawnChecks = monsterDespawnCheckCount.sumThenReset();
        long despawnRemoved = monsterDespawnRemovedCount.sumThenReset();
        int activeCooldownEntities = pruneExpiredSynchronousCooldowns();
        String topAbortReasons = async$drainTopCounts(asyncEntityTickAbortReasonCounts);
        String topAbortEntities = async$drainTopCounts(asyncEntityTickAbortEntityCounts);
        String topReadyMissReasons = async$drainTopCounts(asyncEntityTickReadyMissReasonCounts);
        String topReadyMissEntities = async$drainTopCounts(asyncEntityTickReadyMissEntityCounts);
        String topAsyncMonsterTicks = async$drainTopCounts(asyncMonsterTickEntityCounts);
        String topSyncMonsterTicks = async$drainTopCounts(syncMonsterTickEntityCounts);
        String topMonsterDespawnChecks = async$drainTopCounts(monsterDespawnCheckEntityCounts);
        String topMonsterDespawnRemoved = async$drainTopCounts(monsterDespawnRemovedEntityCounts);
        String monsterDespawnOwners = MonsterDespawnAreaTelemetry.describeAndReset();
        String monsterDespawnReasons = MonsterDespawnReasonTelemetry.describeAndReset();
        String entityBookkeeping = EntityBookkeepingTelemetry.describeAndReset();
        String abortSamples = async$drainSamples(asyncEntityTickAbortSamples);
        String readyMissSamples = async$drainSamples(asyncEntityTickReadyMissSamples);
        if (aborts == 0L
                && cooldowns == 0L
                && syncFallbacks == 0L
                && skippedTicks == 0L
                && getChunkCalls == 0L
                && getChunkHits == 0L
                && getChunkNowCalls == 0L
                && getChunkNowHits == 0L
                && asyncMonsterTicks == 0L
                && syncMonsterTicks == 0L
                && despawnChecks == 0L
                && despawnRemoved == 0L
                && activeCooldownEntities == 0
                && topAbortReasons.equals("[]")
                && topReadyMissReasons.equals("[]")
                && topAsyncMonsterTicks.equals("[]")
                && topSyncMonsterTicks.equals("[]")
                && topMonsterDespawnChecks.equals("[]")
                && topMonsterDespawnRemoved.equals("[]")
                && monsterDespawnOwners.equals("monsterDespawnOwners=idle")
                && monsterDespawnReasons.equals("monsterDespawnReasons[idle]")
                && entityBookkeeping.equals("entityBookkeeping=idle")) {
            return;
        }

        LOGGER.info(
                "Async entity tick diagnostics in last 1m: aborts={}, cooldowns={}, skippedTicks={}, syncFallbackTicks={}, activeCooldownEntities={}, getChunkCalls={}, getChunkHits={}, getChunkNowCalls={}, getChunkNowHits={}, asyncMonsterTicks={}, syncMonsterTicks={}, monsterDespawnChecks={}, monsterDespawnRemoved={}, topAbortReasons={}, topAbortEntities={}, topReadyMissReasons={}, topReadyMissEntities={}, topAsyncMonsterTicks={}, topSyncMonsterTicks={}, topMonsterDespawnChecks={}, topMonsterDespawnRemoved={}, monsterDespawnOwners={}, monsterDespawnReasons={}, entityBookkeeping={}, abortSamples={}, readyMissSamples={}",
                aborts,
                cooldowns,
                skippedTicks,
                syncFallbacks,
                activeCooldownEntities,
                getChunkCalls,
                getChunkHits,
                getChunkNowCalls,
                getChunkNowHits,
                asyncMonsterTicks,
                syncMonsterTicks,
                despawnChecks,
                despawnRemoved,
                topAbortReasons,
                topAbortEntities,
                topReadyMissReasons,
                topReadyMissEntities,
                topAsyncMonsterTicks,
                topSyncMonsterTicks,
                topMonsterDespawnChecks,
                topMonsterDespawnRemoved,
                monsterDespawnOwners,
                monsterDespawnReasons,
                entityBookkeeping,
                abortSamples,
                readyMissSamples
        );
    }

    private static void async$incrementCounter(Map<String, LongAdder> counters, String key) {
        counters.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    private static void async$recordMonsterAsyncTick(Entity entity) {
        if (!async$isMonster(entity)) {
            return;
        }
        asyncMonsterTickCount.increment();
        async$incrementCounter(asyncMonsterTickEntityCounts, EntityType.getKey(entity.getType()).toString());
    }

    private static void async$recordMonsterSyncTick(Entity entity) {
        if (!async$isMonster(entity)) {
            return;
        }
        syncMonsterTickCount.increment();
        async$incrementCounter(syncMonsterTickEntityCounts, EntityType.getKey(entity.getType()).toString());
    }

    public static void recordMonsterDespawnCheck(Entity entity) {
        if (!async$isMonster(entity)) {
            return;
        }
        monsterDespawnCheckCount.increment();
        async$incrementCounter(monsterDespawnCheckEntityCounts, EntityType.getKey(entity.getType()).toString());
        MonsterDespawnAreaTelemetry.recordCheck(entity);
    }

    public static void recordMonsterDespawnRemoved(Entity entity) {
        if (!entity.isRemoved() || !async$isMonster(entity)) {
            return;
        }
        monsterDespawnRemovedCount.increment();
        async$incrementCounter(monsterDespawnRemovedEntityCounts, EntityType.getKey(entity.getType()).toString());
        MonsterDespawnAreaTelemetry.recordRemoved(entity);
    }

    public static void recordAsyncEntityTickGetChunkCall() {
        if (isEntityTickExecutionThread()) {
            asyncEntityTickGetChunkCalls.increment();
        }
    }

    public static void recordAsyncEntityTickGetChunkHit() {
        if (isEntityTickExecutionThread()) {
            asyncEntityTickGetChunkHits.increment();
        }
    }

    public static void recordAsyncEntityTickGetChunkNowCall() {
        if (isEntityTickExecutionThread()) {
            asyncEntityTickGetChunkNowCalls.increment();
        }
    }

    public static void recordAsyncEntityTickGetChunkNowHit() {
        if (isEntityTickExecutionThread()) {
            asyncEntityTickGetChunkNowHits.increment();
        }
    }

    private static boolean async$isMonster(Entity entity) {
        return entity.getType().getCategory() == MobCategory.MONSTER;
    }

    private static void async$pushSample(ConcurrentLinkedDeque<String> samples, String sample) {
        samples.addLast(sample);
        while (samples.size() > MAX_DIAGNOSTIC_SAMPLES) {
            samples.pollFirst();
        }
    }

    private static String async$formatChunkAccessSample(String reason, long requestedChunkPosLong) {
        long currentChunkPos = CURRENT_ENTITY_CHUNK_POS.get();
        String entityType = CURRENT_ENTITY_TYPE_ID.get();
        if (currentChunkPos == Long.MIN_VALUE) {
            return entityType + " " + reason + " requested=" + async$formatChunkPos(requestedChunkPosLong);
        }

        int currentChunkX = (int) currentChunkPos;
        int currentChunkZ = (int) (currentChunkPos >> 32);
        int requestedChunkX = (int) requestedChunkPosLong;
        int requestedChunkZ = (int) (requestedChunkPosLong >> 32);
        return entityType
                + " "
                + reason
                + " current="
                + async$formatChunkPos(currentChunkPos)
                + " requested="
                + async$formatChunkPos(requestedChunkPosLong)
                + " delta=("
                + (requestedChunkX - currentChunkX)
                + ","
                + (requestedChunkZ - currentChunkZ)
                + ")";
    }

    private static String async$formatChunkPos(long chunkPosLong) {
        return "(" + (int) chunkPosLong + "," + (int) (chunkPosLong >> 32) + ")";
    }

    private static String async$drainTopCounts(Map<String, LongAdder> counters) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        counters.forEach((key, value) -> {
            long count = value.sumThenReset();
            if (count > 0L) {
                entries.add(Map.entry(key, count));
            }
        });
        if (entries.isEmpty()) {
            return "[]";
        }
        entries.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(MAX_TOP_DIAGNOSTIC_ENTRIES, entries.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Map.Entry<String, Long> entry = entries.get(i);
            builder.append(entry.getKey()).append("=").append(entry.getValue());
        }
        builder.append("]");
        return builder.toString();
    }

    private static String async$drainSamples(ConcurrentLinkedDeque<String> samples) {
        List<String> drained = new ArrayList<>();
        String sample;
        while ((sample = samples.pollFirst()) != null) {
            drained.add(sample);
        }
        return drained.isEmpty() ? "[]" : drained.toString();
    }

    private static boolean isAbortThrowable(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof AsyncAbortException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void stop() {
        isShuttingDown = true;
        shutdownExecutor("tickPool", tickPool);
        AsyncConfig.clearCaches();
        blacklistedEntity.clear();
        temporarilySynchronizedEntities.clear();
        asyncEntityTickAbortCount.reset();
        asyncEntityTickCooldownCount.reset();
        asyncEntityTickSyncFallbackCount.reset();
        asyncEntityTickSkippedCount.reset();
        asyncEntityTickAbortReasonCounts.clear();
        asyncEntityTickAbortEntityCounts.clear();
        asyncEntityTickReadyMissReasonCounts.clear();
        asyncEntityTickReadyMissEntityCounts.clear();
        asyncEntityTickAbortSamples.clear();
        asyncEntityTickReadyMissSamples.clear();
        nextFallbackLogNanos.set(System.nanoTime() + FALLBACK_LOG_INTERVAL_NANOS);
        PortalTeleportationManager.shutdown();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void shutdownExecutor(String name, ExecutorService executor) {
        if (executor == null) {
            return;
        }

        LOGGER.info("Waiting for Async {} to shutdown...", name);
        executor.shutdown();
        try {
            if (executor.awaitTermination(60L, TimeUnit.SECONDS)) {
                return;
            }

            List<Runnable> droppedTasks = executor.shutdownNow();
            LOGGER.warn("Async {} did not shut down within 60 seconds; forcing interrupt shutdown ({} queued tasks dropped)", name, droppedTasks.size());
            if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                LOGGER.warn("Async {} is still running after forced shutdown", name);
            }
        } catch (InterruptedException e) {
            List<Runnable> droppedTasks = executor.shutdownNow();
            LOGGER.warn("Interrupted while waiting for Async {} to shutdown; forcing interrupt shutdown ({} queued tasks dropped)", name, droppedTasks.size());
            Thread.currentThread().interrupt();
        }
    }

    private static void logEntityError(Entity entity, Throwable e) {
        LOGGER.error("{} Entity Type: {}, UUID: {}", "Error during synchronous tick", entity.getType().toString(), entity.getUUID(), e);
    }

    private static void logDespawnError(Entity entity, Throwable e) {
        LOGGER.error("{} Entity Type: {}, UUID: {}", "Error during despawn check", entity.getType().toString(), entity.getUUID(), e);
    }

    public static final class AsyncAbortException extends RuntimeException {
        public AsyncAbortException() {
            super(null, null, false, false);
        }
    }
}
