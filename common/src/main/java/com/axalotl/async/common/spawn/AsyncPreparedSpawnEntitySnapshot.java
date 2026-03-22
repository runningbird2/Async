package com.axalotl.async.common.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.Objects;

public record AsyncPreparedSpawnEntitySnapshot(
        BlockPos blockPos,
        long chunkPosLong,
        EntityType<?> entityType,
        MobCategory category,
        boolean countsTowardLocalCap
) {

    public AsyncPreparedSpawnEntitySnapshot {
        blockPos = Objects.requireNonNull(blockPos, "blockPos").immutable();
        entityType = Objects.requireNonNull(entityType, "entityType");
        category = Objects.requireNonNull(category, "category");
    }
}
