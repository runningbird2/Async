package com.axalotl.async.common.spawn;

import net.minecraft.world.entity.MobCategory;

import java.util.List;
import java.util.Objects;

/**
 * Immutable async-planned natural spawn work ready for a later main-thread
 * commit pass.
 */
public record AsyncSpawnPlan(
        AsyncSpawnSnapshot snapshot,
        AsyncSpawnChunkOrder chunkOrder,
        List<AsyncSpawnChunkPlan> chunkPlans
) {

    public AsyncSpawnPlan {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        chunkOrder = Objects.requireNonNull(chunkOrder, "chunkOrder");
        chunkPlans = List.copyOf(Objects.requireNonNull(chunkPlans, "chunkPlans"));
    }

    public long gameTime() {
        return this.snapshot.gameTime();
    }

    public long timeInhabited() {
        return this.snapshot.timeInhabited();
    }

    public int spawnableChunkCount() {
        return this.snapshot.spawnableChunkCount();
    }

    public List<MobCategory> categories() {
        return this.snapshot.categories();
    }

    public boolean mobsEnabled() {
        return this.snapshot.mobsEnabled();
    }

    public boolean spawnFriendlies() {
        return this.snapshot.spawnFriendlies();
    }

    public boolean spawnEnemies() {
        return this.snapshot.spawnEnemies();
    }

    public boolean spawnPersistent() {
        return this.snapshot.spawnPersistent();
    }

    public boolean isEmpty() {
        return this.chunkPlans.isEmpty();
    }
}
