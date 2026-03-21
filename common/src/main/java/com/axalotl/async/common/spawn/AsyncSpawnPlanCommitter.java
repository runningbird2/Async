package com.axalotl.async.common.spawn;

import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongFunction;

/**
 * Main-thread helper that resolves planned chunk positions back into live chunks and applies them in plan order.
 */
public final class AsyncSpawnPlanCommitter {

    private AsyncSpawnPlanCommitter() {
    }

    public static AsyncSpawnCommitResult commit(
            AsyncSpawnPlan plan,
            BiConsumer<AsyncSpawnChunkPlan, LevelChunk> chunkCommitter
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(chunkCommitter, "chunkCommitter");

        int committed = 0;
        int skipped = 0;
        int limit = Math.min(plan.chunkPlans().size(), plan.chunks().size());

        for (int i = 0; i < limit; i++) {
            chunkCommitter.accept(plan.chunkPlans().get(i), plan.chunks().get(i));
            committed++;
        }

        skipped = plan.chunkPlans().size() - committed;
        return new AsyncSpawnCommitResult(plan.chunkPlans().size(), committed, skipped);
    }

    public static AsyncSpawnCommitResult commit(
            AsyncSpawnPlan plan,
            LongFunction<LevelChunk> chunkResolver,
            BiConsumer<AsyncSpawnChunkPlan, LevelChunk> chunkCommitter
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(chunkResolver, "chunkResolver");
        Objects.requireNonNull(chunkCommitter, "chunkCommitter");

        int committed = 0;
        int skipped = 0;

        for (AsyncSpawnChunkPlan chunkPlan : plan.chunkPlans()) {
            LevelChunk chunk = chunkResolver.apply(chunkPlan.chunkPos());
            if (chunk == null) {
                skipped++;
                continue;
            }

            chunkCommitter.accept(chunkPlan, chunk);
            committed++;
        }

        return new AsyncSpawnCommitResult(plan.chunkPlans().size(), committed, skipped);
    }
}
