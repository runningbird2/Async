package com.axalotl.async.common.spawn;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MonsterDespawnAreaTelemetry {

    private static final int MAX_OWNER_ENTRIES = 6;
    private static final int MAX_TYPE_ENTRIES = 3;

    private static long totalChecks;
    private static long totalRemoved;
    private static long noOwnerChecks;
    private static long noOwnerRemoved;
    private static final Map<String, OwnerStats> ownerStats = new HashMap<>();

    private MonsterDespawnAreaTelemetry() {
    }

    public static synchronized void recordCheck(Entity entity) {
        record(entity, false);
    }

    public static synchronized void recordRemoved(Entity entity) {
        record(entity, true);
    }

    public static synchronized String describeAndReset() {
        if (totalChecks == 0L && totalRemoved == 0L) {
            return "monsterDespawnOwners=idle";
        }

        List<Map.Entry<String, OwnerStats>> topOwners = new ArrayList<>(ownerStats.entrySet());
        topOwners.sort(
                Comparator.<Map.Entry<String, OwnerStats>>comparingLong(entry -> entry.getValue().checks).reversed()
                        .thenComparing((left, right) -> Long.compare(right.getValue().removed, left.getValue().removed))
        );

        StringBuilder builder = new StringBuilder();
        builder.append("monsterDespawnOwners[checks=")
                .append(totalChecks)
                .append(", removed=")
                .append(totalRemoved)
                .append(", noOwnerChecks=")
                .append(noOwnerChecks)
                .append(", noOwnerRemoved=")
                .append(noOwnerRemoved)
                .append(", top=[");

        int written = 0;
        for (Map.Entry<String, OwnerStats> entry : topOwners) {
            if (written > 0) {
                builder.append(", ");
            }
            OwnerStats stats = entry.getValue();
            builder.append(entry.getKey())
                    .append("@(")
                    .append(stats.lastChunkX)
                    .append(",")
                    .append(stats.lastChunkZ)
                    .append(")")
                    .append(":checks=")
                    .append(stats.checks)
                    .append(",removed=")
                    .append(stats.removed)
                    .append(",checkTypes=")
                    .append(formatTopTypes(stats.checkTypes))
                    .append(",removedTypes=")
                    .append(formatTopTypes(stats.removedTypes));
            written++;
            if (written >= MAX_OWNER_ENTRIES) {
                break;
            }
        }
        builder.append("]]");

        String result = builder.toString();
        totalChecks = 0L;
        totalRemoved = 0L;
        noOwnerChecks = 0L;
        noOwnerRemoved = 0L;
        ownerStats.clear();
        return result;
    }

    private static void record(Entity entity, boolean removed) {
        ServerPlayer owner = findNearestPlayer(entity);
        if (owner == null) {
            if (removed) {
                totalRemoved++;
                noOwnerRemoved++;
            } else {
                totalChecks++;
                noOwnerChecks++;
            }
            return;
        }

        ChunkPos ownerChunk = owner.chunkPosition();
        OwnerStats stats = ownerStats.computeIfAbsent(owner.getScoreboardName(), ignored -> new OwnerStats());
        stats.lastChunkX = ownerChunk.x;
        stats.lastChunkZ = ownerChunk.z;
        String entityType = formatType(entity);

        if (removed) {
            totalRemoved++;
            stats.removed++;
            stats.removedTypes.merge(entityType, 1L, Long::sum);
        } else {
            totalChecks++;
            stats.checks++;
            stats.checkTypes.merge(entityType, 1L, Long::sum);
        }
    }

    private static ServerPlayer findNearestPlayer(Entity entity) {
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        ServerPlayer nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private static String formatType(Entity entity) {
        Identifier key = EntityType.getKey(entity.getType());
        return key != null ? key.toString() : entity.getType().toString();
    }

    private static String formatTopTypes(Map<String, Long> typeCounts) {
        if (typeCounts.isEmpty()) {
            return "[]";
        }

        List<Map.Entry<String, Long>> entries = new ArrayList<>(typeCounts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        StringBuilder builder = new StringBuilder("[");
        int written = 0;
        for (Map.Entry<String, Long> entry : entries) {
            if (written > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append("=").append(entry.getValue());
            written++;
            if (written >= MAX_TYPE_ENTRIES) {
                break;
            }
        }
        builder.append("]");
        return builder.toString();
    }

    private static final class OwnerStats {
        private long checks;
        private long removed;
        private int lastChunkX;
        private int lastChunkZ;
        private final Map<String, Long> checkTypes = new HashMap<>();
        private final Map<String, Long> removedTypes = new HashMap<>();
    }
}
