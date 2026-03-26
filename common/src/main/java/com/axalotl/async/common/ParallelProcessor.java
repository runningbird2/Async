package com.axalotl.async.common;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.parallelised.utils.AsyncNavigationTracker;
import com.axalotl.async.common.parallelised.utils.PortalTeleportationManager;
import lombok.Setter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ParallelProcessor {
    public static final Logger LOGGER = LogManager.getLogger(ParallelProcessor.class);

    @Setter
    private static MinecraftServer server;

    public static final AtomicInteger currentEntities = new AtomicInteger();
    private static final AtomicInteger threadPoolID = new AtomicInteger();
    private static final AtomicInteger spawnPoolID = new AtomicInteger();
    private static final int ASYNC_ABORT_SYNC_COOLDOWN_TICKS = 5;
    private static final long ASYNC_ABORT_FALLBACK_SUMMARY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);
    public static ExecutorService tickPool;
    public static ExecutorService spawnPool;
    private static final Set<UUID> blacklistedEntity = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> temporarilySynchronizedEntities = new ConcurrentHashMap<>();
    private static final Map<String, AsyncAbortFallbackSummary> asyncAbortFallbackSummaries = new ConcurrentHashMap<>();
    private static final Map<String, Set<WeakReference<Thread>>> mcThreadTracker = new ConcurrentHashMap<>();
    private static final ThreadLocal<ExecutionRole> executionRole = ThreadLocal.withInitial(() -> ExecutionRole.NONE);
    public static final Set<Class<?>> BLOCKED_ENTITIES = Set.of(
            FallingBlockEntity.class,
            Shulker.class,
            AbstractBoat.class
    );
    private static volatile boolean isShuttingDown = false;

    public static MinecraftServer getServer() {
        return server;
    }

    public static void setupThreadPool(int parallelism, Class<?> asyncClass) {
        PortalTeleportationManager.init(server);
        isShuttingDown = false;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(() -> {
                executionRole.set(ExecutionRole.TICK);
                try {
                    runnable.run();
                } finally {
                    executionRole.remove();
                }
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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(false);
        executor.prestartAllCoreThreads();
        tickPool = executor;

        int spawnParallelism = AsyncConfig.getSpawnParallelism(parallelism);
        ThreadFactory spawnThreadFactory = runnable -> {
            Thread thread = new Thread(() -> {
                executionRole.set(ExecutionRole.SPAWN);
                try {
                    runnable.run();
                } finally {
                    executionRole.remove();
                }
            }, "Async-Spawn-Pool-Thread-" + spawnPoolID.getAndIncrement());
            registerThread("Async-Spawn", thread);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setContextClassLoader(asyncClass.getClassLoader());
            return thread;
        };
        ThreadPoolExecutor spawnExecutor = new ThreadPoolExecutor(
                spawnParallelism,
                spawnParallelism,
                0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                spawnThreadFactory
        );
        spawnExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        spawnExecutor.allowCoreThreadTimeOut(false);
        spawnExecutor.prestartAllCoreThreads();
        spawnPool = spawnExecutor;

        LOGGER.info("Initialized Pool with {} tick threads and {} spawn threads", parallelism, spawnParallelism);
    }

    public static void registerThread(String poolName, Thread thread) {
        mcThreadTracker
                .computeIfAbsent(poolName, key -> ConcurrentHashMap.newKeySet())
                .add(new WeakReference<>(thread));
    }

    public static boolean isServerExecutionThread() {
        return executionRole.get() != ExecutionRole.NONE;
    }

    public static boolean isSpawnExecutionThread() {
        return executionRole.get() == ExecutionRole.SPAWN;
    }

    public static boolean isTickExecutionThread() {
        return executionRole.get() == ExecutionRole.TICK;
    }

    public static boolean isShuttingDown() {
        return isShuttingDown;
    }

    public static boolean isAbortThrowable(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof AsyncAbortException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    public static int getPoolSize() {
        return ((ThreadPoolExecutor) tickPool).getCorePoolSize();
    }

    public static int getSpawnPoolSize() {
        if (spawnPool instanceof ThreadPoolExecutor spawnExecutor) {
            return spawnExecutor.getCorePoolSize();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    public static void callEntityTickBatch(ServerLevel world, List<Entity> entities) {
        if (entities.isEmpty()) return;
        if (AsyncConfig.disabled) {
            entities.forEach(e -> tickSynchronously(world, e));
            return;
        }

        int poolSize = Math.max(1, getPoolSize());
        int chunkSize = Math.max(1, (entities.size() + poolSize - 1) / poolSize);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < entities.size(); i += chunkSize) {
            List<Entity> chunk = entities.subList(i, Math.min(i + chunkSize, entities.size()));
            Future<?> future = tickPool.submit(() -> {
                for (Entity entity : chunk) {
                    if (entity.isRemoved()) continue;
                    if (shouldTickSynchronously(entity)) continue;
                    try {
                        performAsyncEntityTick(world, entity);
                    } catch (AsyncAbortException ignored) {
                        markEntityForSynchronousHandling(entity, false);
                    } catch (Throwable throwable) {
                        logEntityError(entity, throwable);
                    }
                }
            });
            futures.add(future);
        }

        for (Entity entity : entities) {
            if (shouldTickSynchronously(entity)) {
                tickSynchronously(world, entity);
            }
        }

        waitForFutures(futures);

        for (Future<?> future : futures) {
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

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < entities.size(); i += chunkSize) {
            List<Entity> chunk = entities.subList(i, Math.min(i + chunkSize, entities.size()));
            Future<?> future = tickPool.submit(() -> {
                for (Entity entity : chunk) {
                    if (entity.isRemoved()) continue;
                    try {
                        entity.checkDespawn();
                    } catch (AsyncAbortException ignored) {
                        markEntityForSynchronousHandling(entity, true);
                    } catch (Throwable throwable) {
                        logDespawnError(entity, throwable);
                    }
                }
            });
            futures.add(future);
        }

        waitForFutures(futures);

        for (Future<?> future : futures) {
            if (isShuttingDown && !future.isDone()) {
                continue;
            }
            try {
                future.get();
            } catch (Exception e) {
                if (isAbortThrowable(e)) {
                    continue;
                }
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
                for (ServerLevel lvl : server.getAllLevels()) {
                    pumped |= lvl.getChunkSource().pollTask();
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

        return AsyncConfig.disabled ||
                entity instanceof Projectile ||
                entity instanceof AbstractMinecart ||
                entity instanceof ServerPlayer ||
                entity instanceof Mob mob && entity.level() instanceof AsyncNavigationTracker navigationTracker && navigationTracker.async$isNavigationActive(mob) ||
                BLOCKED_ENTITIES.contains(entity.getClass()) ||
                blacklistedEntity.contains(entityId) ||
                isTemporarilySynchronized(entityId) ||
                AsyncConfig.isEntitySynchronized(EntityType.getKey(entity.getType()));
    }

    private static void tickSynchronously(ServerLevel world, Entity entity) {
        if (entity.isRemoved()) {
            return;
        }
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
        try {
            entity.checkDespawn();
        } catch (Exception e) {
            logDespawnError(entity, e);
        }
    }

    private static void performAsyncEntityTick(ServerLevel world, Entity entity) {
        currentEntities.incrementAndGet();
        try {
            world.tickNonPassenger(entity);
        } finally {
            currentEntities.decrementAndGet();
        }
    }

    private static void markEntityForSynchronousHandling(Entity entity, boolean despawnCheck) {
        MinecraftServer currentServer = server;
        int currentTick = currentServer != null ? currentServer.getTickCount() : 0;
        int expiresAtTick = currentTick + ASYNC_ABORT_SYNC_COOLDOWN_TICKS;
        temporarilySynchronizedEntities.merge(entity.getUUID(), expiresAtTick, Math::max);
        recordAsyncAbortFallback(entity, despawnCheck);
    }

    private static boolean isTemporarilySynchronized(UUID entityId) {
        Integer expiresAtTick = temporarilySynchronizedEntities.get(entityId);
        if (expiresAtTick == null) {
            return false;
        }

        MinecraftServer currentServer = server;
        int currentTick = currentServer != null ? currentServer.getTickCount() : 0;
        if (currentTick >= expiresAtTick) {
            temporarilySynchronizedEntities.remove(entityId, expiresAtTick);
            return false;
        }
        return true;
    }

    public static void flushAsyncAbortFallbackSummariesIfDue() {
        if (asyncAbortFallbackSummaries.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        for (AsyncAbortFallbackSummary summary : asyncAbortFallbackSummaries.values()) {
            summary.flushIfDue(now);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void stop() {
        isShuttingDown = true;
        flushAsyncAbortFallbackSummaries(true);
        shutdownExecutor("spawnPool", spawnPool);
        shutdownExecutor("tickPool", tickPool);
        AsyncConfig.clearCaches();
        blacklistedEntity.clear();
        temporarilySynchronizedEntities.clear();
        asyncAbortFallbackSummaries.clear();
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

    private static void recordAsyncAbortFallback(Entity entity, boolean despawnCheck) {
        if (isShuttingDown) {
            return;
        }

        String dimensionKey = entity.level().dimension().toString();
        AsyncAbortFallbackSummary summary = asyncAbortFallbackSummaries.computeIfAbsent(
                dimensionKey,
                AsyncAbortFallbackSummary::new
        );
        summary.record(despawnCheck);
    }

    private static void flushAsyncAbortFallbackSummaries(boolean force) {
        if (asyncAbortFallbackSummaries.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        for (AsyncAbortFallbackSummary summary : asyncAbortFallbackSummaries.values()) {
            if (force) {
                summary.flushNow();
            } else {
                summary.flushIfDue(now);
            }
        }
    }

    private static final class AsyncAbortFallbackSummary {
        private final String dimensionKey;
        private long windowStartNanos;
        private int entityTickFallbacks;
        private int despawnFallbacks;

        private AsyncAbortFallbackSummary(String dimensionKey) {
            this.dimensionKey = dimensionKey;
        }

        private synchronized void record(boolean despawnCheck) {
            long now = System.nanoTime();
            if (this.windowStartNanos == 0L) {
                this.windowStartNanos = now;
            }

            if (despawnCheck) {
                this.despawnFallbacks++;
            } else {
                this.entityTickFallbacks++;
            }
        }

        private synchronized void flushIfDue(long now) {
            if (this.windowStartNanos == 0L) {
                return;
            }
            if (now - this.windowStartNanos < ASYNC_ABORT_FALLBACK_SUMMARY_INTERVAL_NANOS) {
                return;
            }
            flushLocked();
        }

        private synchronized void flushNow() {
            flushLocked();
        }

        private void flushLocked() {
            int totalFallbacks = this.entityTickFallbacks + this.despawnFallbacks;
            if (totalFallbacks <= 0) {
                this.windowStartNanos = 0L;
                return;
            }

            LOGGER.warn(
                    "Async entity work fell back to synchronous handling {} time(s) in {} over the last 5 minutes (entity ticks: {}, despawn checks: {}, current sync fallback set size: {})",
                    totalFallbacks,
                    this.dimensionKey,
                    this.entityTickFallbacks,
                    this.despawnFallbacks,
                    temporarilySynchronizedEntities.size()
            );

            this.entityTickFallbacks = 0;
            this.despawnFallbacks = 0;
            this.windowStartNanos = 0L;
        }
    }

    public static final class AsyncAbortException extends RuntimeException {
        public AsyncAbortException() {
            super(null, null, false, false);
        }
    }

    private enum ExecutionRole {
        NONE,
        TICK,
        SPAWN
    }
}
