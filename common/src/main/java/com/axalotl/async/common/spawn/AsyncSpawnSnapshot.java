package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable main-thread snapshot that is safe to hand to an async planner.
 */
public record AsyncSpawnSnapshot(
        long gameTime,
        long timeInhabited,
        int spawnableChunkCount,
        boolean mobsEnabled,
        boolean spawnFriendlies,
        boolean spawnEnemies,
        boolean spawnPersistent,
        long orderingSeed,
        List<MobCategory> categories,
        List<Long> chunkPositions
) {

    public AsyncSpawnSnapshot {
        if (spawnableChunkCount < 0) {
            throw new IllegalArgumentException("spawnableChunkCount must be >= 0");
        }
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        chunkPositions = List.copyOf(Objects.requireNonNull(chunkPositions, "chunkPositions"));
    }

    public static AsyncSpawnSnapshot capture(
            long gameTime,
            long timeInhabited,
            int spawnableChunkCount,
            boolean mobsEnabled,
            boolean spawnFriendlies,
            boolean spawnEnemies,
            boolean spawnPersistent,
            List<MobCategory> categories,
            List<LevelChunk> chunks
    ) {
        return capture(
                gameTime,
                timeInhabited,
                spawnableChunkCount,
                mobsEnabled,
                spawnFriendlies,
                spawnEnemies,
                spawnPersistent,
                gameTime,
                categories,
                chunks
        );
    }

    public static AsyncSpawnSnapshot capture(
            long gameTime,
            long timeInhabited,
            int spawnableChunkCount,
            boolean mobsEnabled,
            boolean spawnFriendlies,
            boolean spawnEnemies,
            boolean spawnPersistent,
            long orderingSeed,
            List<MobCategory> categories,
            List<LevelChunk> chunks
    ) {
        Objects.requireNonNull(chunks, "chunks");

        List<Long> chunkPositions = new ArrayList<>(chunks.size());
        for (LevelChunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks contains null");
            chunkPositions.add(chunk.getPos().toLong());
        }

        return new AsyncSpawnSnapshot(
                gameTime,
                timeInhabited,
                spawnableChunkCount,
                mobsEnabled,
                spawnFriendlies,
                spawnEnemies,
                spawnPersistent,
                orderingSeed,
                categories,
                chunkPositions
        );
    }

    public int chunkCount() {
        return this.chunkPositions.size();
    }

    public boolean hasCategories() {
        return !this.categories.isEmpty();
    }
}
