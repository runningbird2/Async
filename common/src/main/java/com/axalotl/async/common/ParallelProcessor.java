package com.axalotl.async.common;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.parallelised.utils.PortalTeleportationManager;
import lombok.Setter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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

public class ParallelProcessor {
    public static final Logger LOGGER = LogManager.getLogger(ParallelProcessor.class);

    @Setter
    private static MinecraftServer server;

    public static MinecraftServer getServer() {
        return server;
    }

    public static final AtomicInteger currentEntities = new AtomicInteger();
    private static final AtomicInteger threadPoolID = new AtomicInteger();
    public static ExecutorService tickPool;
    private static final Set<UUID> blacklistedEntity = ConcurrentHashMap.newKeySet();
    private static final Map<String, Set<WeakReference<Thread>>> mcThreadTracker = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> IS_POOL_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
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
        int submittedUntil = 0;
        try {
            for (int i = 0; i < asyncEntities.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, asyncEntities.size());
                List<Entity> chunk = asyncEntities.subList(i, end);
                Future<Void> future = (Future<Void>) tickPool.submit(() -> {
                    for (Entity entity : chunk) {
                        if (entity.isRemoved()) continue;
                        performAsyncEntityTick(world, entity);
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

        List<Future<Void>> futures = new ArrayList<>();
        int submittedUntil = 0;
        try {
            for (int i = 0; i < entities.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, entities.size());
                List<Entity> chunk = entities.subList(i, end);
                Future<Void> future = (Future<Void>) tickPool.submit(() -> {
                    for (Entity entity : chunk) {
                        if (entity.isRemoved()) continue;
                        entity.checkDespawn();
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

        return AsyncConfig.disabled ||
                entity instanceof Projectile ||
                entity instanceof AbstractMinecart ||
                entity instanceof ServerPlayer ||
                BLOCKED_ENTITIES.contains(entity.getClass()) ||
                blacklistedEntity.contains(entityId) ||
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

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void stop() {
        isShuttingDown = true;
        shutdownExecutor("tickPool", tickPool);
        AsyncConfig.clearCaches();
        blacklistedEntity.clear();
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
}
