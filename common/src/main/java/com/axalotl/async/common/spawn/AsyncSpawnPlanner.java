package com.axalotl.async.common.spawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Conservative async planner that only snapshots commit order and category
 * state. Richer per-chunk proposals can be layered on top of the same plan
 * envelope later.
 */
public final class AsyncSpawnPlanner {

    private AsyncSpawnPlanner() {
    }

    public static AsyncSpawnPlan plan(AsyncSpawnSnapshot snapshot) {
        return plan(snapshot, AsyncSpawnChunkOrder.PRESERVE_SNAPSHOT);
    }

    public static AsyncSpawnPlan plan(AsyncSpawnSnapshot snapshot, AsyncSpawnChunkOrder chunkOrder) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(chunkOrder, "chunkOrder");

        if (snapshot.chunkPositions().isEmpty()) {
            return new AsyncSpawnPlan(snapshot, chunkOrder, List.of());
        }

        List<Long> orderedPositions = switch (chunkOrder) {
            case PRESERVE_SNAPSHOT -> snapshot.chunkPositions();
            case DETERMINISTIC_SHUFFLE -> shuffled(snapshot.chunkPositions(), snapshot.orderingSeed());
        };

        List<AsyncSpawnChunkPlan> chunkPlans = new ArrayList<>(orderedPositions.size());
        for (int i = 0; i < orderedPositions.size(); i++) {
            chunkPlans.add(new AsyncSpawnChunkPlan(i, orderedPositions.get(i), AsyncSpawnChunkPlan.CommitMode.PASS_THROUGH));
        }

        return new AsyncSpawnPlan(snapshot, chunkOrder, chunkPlans);
    }

    private static List<Long> shuffled(List<Long> chunkPositions, long orderingSeed) {
        List<Long> shuffled = new ArrayList<>(chunkPositions);
        Collections.shuffle(shuffled, new Random(orderingSeed));
        return List.copyOf(shuffled);
    }
}
