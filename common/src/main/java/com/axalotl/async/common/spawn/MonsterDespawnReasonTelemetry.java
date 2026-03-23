package com.axalotl.async.common.spawn;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MonsterDespawnReasonTelemetry {

    private static final int MAX_TOP_ENTRIES = 6;
    private static final Map<Reason, Long> totals = new EnumMap<>(Reason.class);
    private static final Map<Reason, Map<String, Long>> ownerReasonCounts = new EnumMap<>(Reason.class);
    private static final Map<String, OwnerSnapshot> ownerSnapshots = new HashMap<>();

    static {
        for (Reason reason : Reason.values()) {
            totals.put(reason, 0L);
            ownerReasonCounts.put(reason, new HashMap<>());
        }
    }

    private MonsterDespawnReasonTelemetry() {
    }

    public static Probe probe(Mob mob, int noActionTime) {
        if (mob.getType().getCategory() != MobCategory.MONSTER) {
            return Probe.SKIP;
        }

        if (mob.level().getDifficulty() == Difficulty.PEACEFUL && !mob.getType().isAllowedInPeaceful()) {
            return new Probe(Reason.PEACEFUL_DISCARD, null, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }

        if (mob.isPersistenceRequired() || mob.requiresCustomPersistence()) {
            return new Probe(Reason.PERSISTENT_SKIP, null, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }

        Player nearest = mob.level().getNearestPlayer(mob, -1.0D);
        if (nearest == null) {
            return new Probe(Reason.NO_PLAYER, null, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }

        String ownerName = nearest.getScoreboardName();
        ChunkPos ownerChunk = nearest instanceof ServerPlayer serverPlayer
                ? serverPlayer.chunkPosition()
                : new ChunkPos(nearest.blockPosition());
        double distanceToPlayer = nearest.distanceToSqr(mob);
        MobCategory category = mob.getType().getCategory();
        int despawnDistance = category.getDespawnDistance();
        int noDespawnDistance = category.getNoDespawnDistance();
        double despawnDistanceSq = (double) despawnDistance * despawnDistance;
        double noDespawnDistanceSq = (double) noDespawnDistance * noDespawnDistance;
        boolean removableWhenFar = mob.removeWhenFarAway(distanceToPlayer);

        if (distanceToPlayer > despawnDistanceSq) {
            return new Probe(removableWhenFar ? Reason.HARD_DISCARD : Reason.HARD_BLOCKED, ownerName, ownerChunk.x, ownerChunk.z);
        }

        if (noActionTime > 600 && distanceToPlayer > noDespawnDistanceSq) {
            return new Probe(Reason.RANDOM_ELIGIBLE, ownerName, ownerChunk.x, ownerChunk.z);
        }

        if (distanceToPlayer < noDespawnDistanceSq) {
            return new Probe(Reason.WITHIN_NO_DESPAWN, ownerName, ownerChunk.x, ownerChunk.z);
        }

        return new Probe(Reason.OUTSIDE_NO_DESPAWN_WAITING, ownerName, ownerChunk.x, ownerChunk.z);
    }

    public static synchronized void record(Probe probe, Mob mob) {
        if (probe == Probe.SKIP) {
            return;
        }

        Reason reason = probe.reason;
        if (reason == Reason.RANDOM_ELIGIBLE) {
            reason = mob.isRemoved() ? Reason.RANDOM_DISCARD : Reason.RANDOM_HELD;
        } else if (reason == Reason.HARD_DISCARD && !mob.isRemoved()) {
            reason = Reason.HARD_BLOCKED;
        }

        totals.merge(reason, 1L, Long::sum);
        if (probe.ownerName == null) {
            return;
        }

        ownerReasonCounts.get(reason).merge(probe.ownerName, 1L, Long::sum);
        ownerSnapshots.put(probe.ownerName, new OwnerSnapshot(probe.ownerChunkX, probe.ownerChunkZ));
    }

    public static synchronized String describeAndReset() {
        StringBuilder builder = new StringBuilder("monsterDespawnReasons[");
        boolean wroteAny = false;
        for (Reason reason : Reason.values()) {
            long count = totals.get(reason);
            if (count <= 0L) {
                continue;
            }
            if (wroteAny) {
                builder.append(", ");
            }
            wroteAny = true;
            builder.append(reason.label).append("=").append(count);
        }

        if (!wroteAny) {
            builder.append("idle");
        } else {
            builder.append(", topWithin=").append(formatTopOwners(ownerReasonCounts.get(Reason.WITHIN_NO_DESPAWN)));
            builder.append(", topWaiting=").append(formatTopOwners(ownerReasonCounts.get(Reason.OUTSIDE_NO_DESPAWN_WAITING)));
            builder.append(", topRandomEligible=").append(formatTopOwners(ownerReasonCounts.get(Reason.RANDOM_HELD)));
            builder.append(", topRandomDiscard=").append(formatTopOwners(ownerReasonCounts.get(Reason.RANDOM_DISCARD)));
            builder.append(", topHardDiscard=").append(formatTopOwners(ownerReasonCounts.get(Reason.HARD_DISCARD)));
        }
        builder.append("]");

        String result = builder.toString();
        reset();
        return result;
    }

    private static void reset() {
        for (Reason reason : Reason.values()) {
            totals.put(reason, 0L);
            ownerReasonCounts.get(reason).clear();
        }
        ownerSnapshots.clear();
    }

    private static String formatTopOwners(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "[]";
        }

        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());

        StringBuilder builder = new StringBuilder("[");
        int written = 0;
        for (Map.Entry<String, Long> entry : entries) {
            if (written > 0) {
                builder.append(", ");
            }
            OwnerSnapshot snapshot = ownerSnapshots.get(entry.getKey());
            builder.append(entry.getKey())
                    .append("=")
                    .append(entry.getValue());
            if (snapshot != null) {
                builder.append("@(")
                        .append(snapshot.chunkX)
                        .append(",")
                        .append(snapshot.chunkZ)
                        .append(")");
            }
            written++;
            if (written >= MAX_TOP_ENTRIES) {
                break;
            }
        }
        builder.append("]");
        return builder.toString();
    }

    public record Probe(Reason reason, String ownerName, int ownerChunkX, int ownerChunkZ) {
        private static final Probe SKIP = new Probe(Reason.SKIP, null, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public enum Reason {
        SKIP("skip"),
        PEACEFUL_DISCARD("peacefulDiscard"),
        PERSISTENT_SKIP("persistentSkip"),
        NO_PLAYER("noPlayer"),
        WITHIN_NO_DESPAWN("withinNoDespawn"),
        OUTSIDE_NO_DESPAWN_WAITING("outsideNoDespawnWaiting"),
        RANDOM_ELIGIBLE("randomEligible"),
        RANDOM_HELD("randomHeld"),
        RANDOM_DISCARD("randomDiscard"),
        HARD_DISCARD("hardDiscard"),
        HARD_BLOCKED("hardBlocked");

        private final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    private record OwnerSnapshot(int chunkX, int chunkZ) {
    }
}
