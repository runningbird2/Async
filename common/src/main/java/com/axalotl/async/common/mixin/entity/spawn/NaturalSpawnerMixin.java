package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.platform.PlatformUtils;
import com.axalotl.async.common.spawn.AsyncSpawnCapMarkingContext;
import com.axalotl.async.common.spawn.AsyncSpawnStateMobcapAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Optional;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @WrapMethod(method = "spawnForChunk")
    private static void async$spawnForChunk(
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawner.SpawnState spawnState,
            List<MobCategory> categories,
            Operation<Void> original
    ) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("spawner");

        AsyncSpawnStateMobcapAccess mobcapAccess = (AsyncSpawnStateMobcapAccess) spawnState;
        NaturalSpawnerSpawnStateInvoker spawnStateInvoker = (NaturalSpawnerSpawnStateInvoker) spawnState;
        ChunkPos chunkPos = chunk.getPos();

        for (MobCategory category : categories) {
            int maxSpawns = async$getLocalMaxSpawns(mobcapAccess, category, chunkPos);
            if (maxSpawns <= 0) {
                continue;
            }

            if (maxSpawns == Integer.MAX_VALUE) {
                AsyncSpawnCapMarkingContext.push();
                try {
                    NaturalSpawner.spawnCategoryForChunk(
                            category,
                            level,
                            chunk,
                            spawnStateInvoker::async$invokeCanSpawn,
                            spawnStateInvoker::async$invokeAfterSpawn
                    );
                } finally {
                    AsyncSpawnCapMarkingContext.pop();
                }
                continue;
            }

            async$spawnCategoryForChunk(category, level, chunk, spawnStateInvoker, maxSpawns);
        }

        profiler.pop();
    }

    private static int async$getLocalMaxSpawns(
            AsyncSpawnStateMobcapAccess mobcapAccess,
            MobCategory category,
            ChunkPos chunkPos
    ) {
        if (SharedConstants.DEBUG_IGNORE_LOCAL_MOB_CAP) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, mobcapAccess.async$getMinLocalMobHeadroom(category, chunkPos));
    }

    private static void async$spawnCategoryForChunk(
            MobCategory category,
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawnerSpawnStateInvoker spawnStateInvoker,
            int maxSpawns
    ) {
        BlockPos randomPos = NaturalSpawnerInvoker.async$invokeGetRandomPosWithin(level, chunk);
        if (randomPos.getY() >= level.getMinY() + 1) {
            async$spawnCategoryForPosition(category, level, chunk, randomPos, spawnStateInvoker, maxSpawns);
        }
    }

    private static void async$spawnCategoryForPosition(
            MobCategory category,
            ServerLevel level,
            ChunkAccess chunk,
            BlockPos pos,
            NaturalSpawnerSpawnStateInvoker spawnStateInvoker,
            int maxSpawns
    ) {
        StructureManager structureManager = level.structureManager();
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        int y = pos.getY();
        BlockState blockState = chunk.getBlockState(pos);
        if (blockState.isRedstoneConductor(chunk, pos)) {
            return;
        }

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int spawnedTotal = 0;

        for (int packAttempt = 0; packAttempt < 3 && spawnedTotal < maxSpawns; packAttempt++) {
            int x = pos.getX();
            int z = pos.getZ();
            MobSpawnSettings.SpawnerData spawnerData = null;
            SpawnGroupData spawnGroupData = null;
            int packSize = Mth.ceil(level.random.nextFloat() * 4.0F);
            int spawnedInGroup = 0;

            for (int attempt = 0; attempt < packSize && spawnedTotal < maxSpawns; attempt++) {
                x += level.random.nextInt(6) - level.random.nextInt(6);
                z += level.random.nextInt(6) - level.random.nextInt(6);
                mutablePos.set(x, y, z);
                double spawnX = x + 0.5;
                double spawnZ = z + 0.5;
                Player player = level.getNearestPlayer(spawnX, y, spawnZ, -1.0, false);
                if (player == null) {
                    continue;
                }

                double distance = player.distanceToSqr(spawnX, y, spawnZ);
                if (!NaturalSpawnerInvoker.async$invokeIsRightDistanceToPlayerAndSpawnPoint(level, chunk, mutablePos, distance)) {
                    continue;
                }

                if (spawnerData == null) {
                    Optional<MobSpawnSettings.SpawnerData> optional = NaturalSpawnerInvoker.async$invokeGetRandomSpawnMobAt(
                            level,
                            structureManager,
                            chunkGenerator,
                            category,
                            level.random,
                            mutablePos
                    );
                    if (optional.isEmpty()) {
                        break;
                    }

                    spawnerData = optional.get();
                    packSize = spawnerData.minCount() + level.random.nextInt(1 + spawnerData.maxCount() - spawnerData.minCount());
                }

                if (!NaturalSpawnerInvoker.async$invokeIsValidSpawnPostitionForType(
                        level,
                        category,
                        structureManager,
                        chunkGenerator,
                        spawnerData,
                        mutablePos,
                        distance
                ) || !spawnStateInvoker.async$invokeCanSpawn(spawnerData.type(), mutablePos, chunk)) {
                    continue;
                }

                Mob mob = NaturalSpawnerInvoker.async$invokeGetMobForSpawn(level, spawnerData.type());
                if (mob == null) {
                    return;
                }

                mob.snapTo(spawnX, y, spawnZ, level.random.nextFloat() * 360.0F, 0.0F);
                if (!NaturalSpawnerInvoker.async$invokeIsValidPositionForMob(level, mob, distance)) {
                    continue;
                }

                spawnGroupData = mob.finalizeSpawn(
                        level,
                        level.getCurrentDifficultyAt(mob.blockPosition()),
                        EntitySpawnReason.NATURAL,
                        spawnGroupData
                );
                AsyncSpawnCapMarkingContext.push();
                try {
                    level.addFreshEntityWithPassengers(mob);
                } finally {
                    AsyncSpawnCapMarkingContext.pop();
                }
                if (!mob.isRemoved()) {
                    spawnedTotal++;
                    spawnedInGroup++;
                    spawnStateInvoker.async$invokeAfterSpawn(mob, chunk);
                    if (spawnedTotal >= maxSpawns || spawnedTotal >= PlatformUtils.getMaxSpawnClusterSize(mob)) {
                        return;
                    }
                    if (mob.isMaxGroupSizeReached(spawnedInGroup)) {
                        break;
                    }
                }
            }
        }
    }

    @WrapMethod(method = "spawnMobsForChunkGeneration")
    private static void async$markChunkGenerationSpawns(
            net.minecraft.world.level.ServerLevelAccessor levelAccessor,
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome,
            ChunkPos chunkPos,
            net.minecraft.util.RandomSource randomSource,
            Operation<Void> original
    ) {
        AsyncSpawnCapMarkingContext.push();
        try {
            original.call(levelAccessor, biome, chunkPos, randomSource);
        } finally {
            AsyncSpawnCapMarkingContext.pop();
        }
    }

}

@Mixin(NaturalSpawner.class)
interface NaturalSpawnerInvoker {

    @Invoker("getRandomPosWithin")
    static BlockPos async$invokeGetRandomPosWithin(net.minecraft.world.level.Level level, LevelChunk chunk) {
        throw new AssertionError();
    }

    @Invoker("isRightDistanceToPlayerAndSpawnPoint")
    static boolean async$invokeIsRightDistanceToPlayerAndSpawnPoint(
            ServerLevel level,
            ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            double distance
    ) {
        throw new AssertionError();
    }

    @Invoker("isValidSpawnPostitionForType")
    static boolean async$invokeIsValidSpawnPostitionForType(
            ServerLevel level,
            MobCategory category,
            StructureManager structureManager,
            ChunkGenerator chunkGenerator,
            MobSpawnSettings.SpawnerData spawnerData,
            BlockPos.MutableBlockPos pos,
            double distance
    ) {
        throw new AssertionError();
    }

    @Invoker("getMobForSpawn")
    static Mob async$invokeGetMobForSpawn(ServerLevel level, EntityType<?> entityType) {
        throw new AssertionError();
    }

    @Invoker("isValidPositionForMob")
    static boolean async$invokeIsValidPositionForMob(ServerLevel level, Mob mob, double distance) {
        throw new AssertionError();
    }

    @Invoker("getRandomSpawnMobAt")
    static Optional<MobSpawnSettings.SpawnerData> async$invokeGetRandomSpawnMobAt(
            ServerLevel level,
            StructureManager structureManager,
            ChunkGenerator chunkGenerator,
            MobCategory category,
            net.minecraft.util.RandomSource random,
            BlockPos pos
    ) {
        throw new AssertionError();
    }
}

@Mixin(NaturalSpawner.SpawnState.class)
interface NaturalSpawnerSpawnStateInvoker {

    @Invoker("afterSpawn")
    void async$invokeAfterSpawn(Mob mob, ChunkAccess chunk);

    @Invoker("canSpawn")
    boolean async$invokeCanSpawn(EntityType<?> entityType, BlockPos pos, ChunkAccess chunk);
}
