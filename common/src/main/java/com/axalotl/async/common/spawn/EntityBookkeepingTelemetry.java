package com.axalotl.async.common.spawn;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.Visibility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityBookkeepingTelemetry {

    private static final int MAX_TOP_REASONS = 8;
    private static final int MAX_TOP_TYPES = 6;
    private static final int MAX_TOP_CHUNKS = 6;

    private static final Map<String, Long> reasonCounts = new HashMap<>();
    private static final Map<String, Long> typeCounts = new HashMap<>();
    private static final Map<Long, Long> chunkCounts = new HashMap<>();

    private EntityBookkeepingTelemetry() {
    }

    public static synchronized void recordSectionRemoveDirect(EntityAccess entity) {
        record("sectionRemoveDirect", entity);
    }

    public static synchronized void recordSectionRemoveFallbackAttempt(EntityAccess entity) {
        record("sectionRemoveFallbackAttempt", entity);
    }

    public static synchronized void recordSectionRemoveFallbackResolved(EntityAccess entity) {
        record("sectionRemoveFallbackResolved", entity);
    }

    public static synchronized void recordSectionRemoveFallbackMiss(EntityAccess entity, String reason) {
        record("sectionRemoveFallbackMiss:" + reason, entity);
    }

    public static synchronized void recordPendingRemovalMoveSkip(EntityAccess entity) {
        record("pendingRemovalMoveSkip", entity);
    }

    public static synchronized void recordVisibilityFallback(EntityAccess entity, Visibility fallback) {
        record("visibilityFallback:" + fallback.name().toLowerCase(), entity);
    }

    public static synchronized void recordLookupDuplicateUuid(EntityAccess entity) {
        record("lookupDuplicateUuid", entity);
    }

    public static synchronized void recordLookupRemoveMissingUuid(EntityAccess entity, boolean staleById) {
        record(staleById ? "lookupRemoveMissingUuidWithById" : "lookupRemoveMissingUuid", entity);
    }

    public static synchronized void recordLookupRemoveIdMismatch(EntityAccess entity, boolean staleById) {
        record(staleById ? "lookupRemoveIdMismatchWithById" : "lookupRemoveIdMismatch", entity);
    }

    public static synchronized void recordLookupStaleById(EntityAccess entity) {
        record("lookupStaleById", entity);
    }

    public static synchronized String describeAndReset() {
        if (reasonCounts.isEmpty()) {
            return "entityBookkeeping=idle";
        }

        StringBuilder builder = new StringBuilder("entityBookkeeping[reasons=");
        builder.append(formatTop(reasonCounts, MAX_TOP_REASONS, false));
        builder.append(", topTypes=").append(formatTop(typeCounts, MAX_TOP_TYPES, false));
        builder.append(", topChunks=").append(formatTop(chunkCounts, MAX_TOP_CHUNKS, true));
        builder.append("]");

        String result = builder.toString();
        reasonCounts.clear();
        typeCounts.clear();
        chunkCounts.clear();
        return result;
    }

    private static void record(String reason, EntityAccess entityAccess) {
        Entity entity = asMonsterEntity(entityAccess);
        if (entity == null) {
            return;
        }

        reasonCounts.merge(reason, 1L, Long::sum);
        typeCounts.merge(formatType(entity), 1L, Long::sum);
        chunkCounts.merge(entity.chunkPosition().toLong(), 1L, Long::sum);
    }

    private static Entity asMonsterEntity(EntityAccess entityAccess) {
        if (!(entityAccess instanceof Entity entity)) {
            return null;
        }
        return entity.getType().getCategory() == MobCategory.MONSTER ? entity : null;
    }

    private static String formatType(Entity entity) {
        Identifier key = EntityType.getKey(entity.getType());
        return key != null ? key.toString() : entity.getType().toString();
    }

    private static String formatTop(Map<?, Long> counts, int limit, boolean chunkKeys) {
        if (counts.isEmpty()) {
            return "[]";
        }

        List<Map.Entry<?, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));

        StringBuilder builder = new StringBuilder("[");
        int written = 0;
        for (Map.Entry<?, Long> entry : entries) {
            if (written > 0) {
                builder.append(", ");
            }

            if (chunkKeys) {
                long chunkKey = (Long) entry.getKey();
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                builder.append("(")
                        .append(chunkPos.x)
                        .append(",")
                        .append(chunkPos.z)
                        .append(")");
            } else {
                builder.append(entry.getKey());
            }

            builder.append("=").append(entry.getValue());
            written++;
            if (written >= limit) {
                break;
            }
        }
        builder.append("]");
        return builder.toString();
    }
}
