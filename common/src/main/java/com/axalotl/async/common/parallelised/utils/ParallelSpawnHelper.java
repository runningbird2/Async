package com.axalotl.async.common.parallelised.utils;

import com.axalotl.async.common.ParallelProcessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ParallelSpawnHelper {

    private ParallelSpawnHelper() {
    }

    private static final int SPAWN_GRAIN = 256;

    public static final class SpawnResult {
        public final Object2IntOpenHashMap<MobCategory> mobCounts = new Object2IntOpenHashMap<>();
        public final ArrayList<ChargeEntry> charges = new ArrayList<>();
        public final Long2ObjectOpenHashMap<int[]> chunkMobCounts = new Long2ObjectOpenHashMap<>();

        public void merge(SpawnResult other) {
            for (var entry : other.mobCounts.object2IntEntrySet()) {
                this.mobCounts.addTo(entry.getKey(), entry.getIntValue());
            }
            this.charges.addAll(other.charges);

            for (Long2ObjectMap.Entry<int[]> entry : other.chunkMobCounts.long2ObjectEntrySet()) {
                int[] target = this.chunkMobCounts.computeIfAbsent(
                        entry.getLongKey(),
                        ignored -> new int[MobCategory.values().length]
                );
                int[] counts = entry.getValue();
                int limit = Math.min(target.length, counts.length);
                for (int i = 0; i < limit; i++) {
                    target[i] += counts[i];
                }
            }
        }
    }

    public record ChargeEntry(BlockPos pos, double charge) {
    }

    public static SpawnResult collectSpawnData(Entity[] entities, NaturalSpawner.ChunkGetter chunkGetter) {
        int length = entities.length;
        if (length <= SPAWN_GRAIN || ParallelProcessor.getSpawnExecutor() == null) {
            return computeRange(entities, chunkGetter, 0, length);
        }

        int poolSize = ParallelProcessor.getPoolSize();
        int chunkSize = Math.max(SPAWN_GRAIN, length / poolSize);

        List<CompletableFuture<SpawnResult>> futures = new ArrayList<>();
        for (int i = 0; i < length; i += chunkSize) {
            int from = i;
            int to = Math.min(i + chunkSize, length);
            futures.add(ParallelProcessor.supplySpawnTask(
                    () -> computeRange(entities, chunkGetter, from, to)
            ));
        }

        SpawnResult merged = new SpawnResult();
        for (CompletableFuture<SpawnResult> future : futures) {
            merged.merge(future.join());
        }
        return merged;
    }

    private static SpawnResult computeRange(
            Entity[] entities,
            NaturalSpawner.ChunkGetter chunkGetter,
            int from,
            int to
    ) {
        SpawnResult result = new SpawnResult();
        for (int i = from; i < to; i++) {
            processEntity(entities[i], chunkGetter, result);
        }
        return result;
    }

    private static void processEntity(Entity entity, NaturalSpawner.ChunkGetter chunkGetter, SpawnResult result) {
        if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
            return;
        }

        MobCategory category = entity.getType().getCategory();
        if (category == MobCategory.MISC) {
            return;
        }

        BlockPos blockPos = entity.blockPosition();
        chunkGetter.query(net.minecraft.world.level.ChunkPos.asLong(blockPos), chunk -> {
            MobSpawnSettings.MobSpawnCost cost = NaturalSpawner.getRoughBiome(blockPos, chunk)
                    .getMobSettings()
                    .getMobSpawnCost(entity.getType());
            if (cost != null) {
                result.charges.add(new ChargeEntry(blockPos, cost.charge()));
            }

            result.mobCounts.addTo(category, 1);
            if (entity instanceof Mob) {
                long chunkLong = chunk.getPos().toLong();
                int[] counts = result.chunkMobCounts.computeIfAbsent(
                        chunkLong,
                        ignored -> new int[MobCategory.values().length]
                );
                counts[category.ordinal()]++;
            }
        });
    }
}
