package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Objects;

/**
 * Immutable main-thread snapshot of the data needed to prepare a natural spawn
 * plan without touching live server state.
 */
public final class AsyncSpawnRequest {
    private final NaturalSpawner.SpawnState spawnState;
    private final List<LevelChunk> chunks;
    private final boolean doMobSpawning;
    private final boolean spawnEnemies;
    private final boolean spawnPersistent;
    private final long timeInhabited;
    private final List<MobCategory> categories;
    private final AsyncSpawnSnapshot snapshot;

    public AsyncSpawnRequest(
            NaturalSpawner.SpawnState spawnState,
            List<LevelChunk> chunks,
            boolean doMobSpawning,
            boolean spawnEnemies,
            boolean spawnPersistent,
            long timeInhabited
    ) {
        this.spawnState = Objects.requireNonNull(spawnState, "spawnState");
        this.chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        this.doMobSpawning = doMobSpawning;
        this.spawnEnemies = spawnEnemies;
        this.spawnPersistent = spawnPersistent;
        this.timeInhabited = timeInhabited;
        this.categories = doMobSpawning
                ? List.copyOf(NaturalSpawner.getFilteredSpawningCategories(spawnState, true, spawnEnemies, spawnPersistent))
                : List.of();
        this.snapshot = AsyncSpawnSnapshot.capture(
                0L,
                timeInhabited,
                this.chunks.size(),
                doMobSpawning,
                true,
                spawnEnemies,
                spawnPersistent,
                timeInhabited,
                this.categories,
                this.chunks
        );
    }

    public boolean hasChunks() {
        return !this.chunks.isEmpty();
    }

    public boolean hasWork() {
        return this.doMobSpawning && !this.categories.isEmpty() && !this.chunks.isEmpty();
    }

    public NaturalSpawner.SpawnState spawnState() {
        return this.spawnState;
    }

    public List<LevelChunk> chunks() {
        return this.chunks;
    }

    public boolean doMobSpawning() {
        return this.doMobSpawning;
    }

    public boolean spawnEnemies() {
        return this.spawnEnemies;
    }

    public boolean spawnPersistent() {
        return this.spawnPersistent;
    }

    public long timeInhabited() {
        return this.timeInhabited;
    }

    public List<MobCategory> categories() {
        return this.categories;
    }

    public AsyncSpawnSnapshot snapshot() {
        return this.snapshot;
    }
}
