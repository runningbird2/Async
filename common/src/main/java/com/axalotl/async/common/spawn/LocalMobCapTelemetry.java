package com.axalotl.async.common.spawn;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LocalMobCapTelemetry {

    private static final int MAX_TOP_ENTRIES = 6;
    private static long monsterChecks;
    private static long monsterAllowed;
    private static long monsterBlocked;
    private static long monsterNoPlayers;
    private static final Map<String, PlayerCapStats> playerStats = new HashMap<>();

    private LocalMobCapTelemetry() {
    }

    public static synchronized void recordMonsterCheck(
            ChunkPos chunkPos,
            List<ServerPlayer> players,
            AsyncLocalMobCapInspector inspector,
            boolean allowed
    ) {
        monsterChecks++;
        if (players.isEmpty()) {
            monsterNoPlayers++;
            return;
        }

        if (allowed) {
            monsterAllowed++;
        } else {
            monsterBlocked++;
        }

        int cap = MobCategory.MONSTER.getMaxInstancesPerChunk();
        for (ServerPlayer player : players) {
            int count = inspector.async$getMobCount(player, MobCategory.MONSTER);
            PlayerCapStats stats = playerStats.computeIfAbsent(player.getScoreboardName(), ignored -> new PlayerCapStats());
            stats.maxMonsterCount = Math.max(stats.maxMonsterCount, count);
            stats.lastChunkX = player.chunkPosition().x;
            stats.lastChunkZ = player.chunkPosition().z;
            if (!allowed && count >= cap) {
                stats.blockedChecks++;
            }
            if (chunkPos.x == player.chunkPosition().x && chunkPos.z == player.chunkPosition().z) {
                stats.sameChunkChecks++;
            }
        }
    }

    public static synchronized String describeAndReset() {
        if (monsterChecks == 0L) {
            return "monsterLocalCap=idle";
        }

        List<Map.Entry<String, PlayerCapStats>> topCounts = new ArrayList<>(playerStats.entrySet());
        topCounts.sort(Comparator.<Map.Entry<String, PlayerCapStats>>comparingInt(entry -> entry.getValue().maxMonsterCount).reversed());

        List<Map.Entry<String, PlayerCapStats>> topBlocked = new ArrayList<>(playerStats.entrySet());
        topBlocked.sort(Comparator.<Map.Entry<String, PlayerCapStats>>comparingLong(entry -> entry.getValue().blockedChecks).reversed());

        String summary = "monsterLocalCap[checks=" + monsterChecks
                + ", allowed=" + monsterAllowed
                + ", blocked=" + monsterBlocked
                + ", noPlayers=" + monsterNoPlayers
                + ", topCounts=" + formatEntries(topCounts, false)
                + ", topBlocked=" + formatEntries(topBlocked, true)
                + "]";

        monsterChecks = 0L;
        monsterAllowed = 0L;
        monsterBlocked = 0L;
        monsterNoPlayers = 0L;
        playerStats.clear();
        return summary;
    }

    private static String formatEntries(List<Map.Entry<String, PlayerCapStats>> entries, boolean blockedView) {
        StringBuilder builder = new StringBuilder("[");
        int written = 0;
        for (Map.Entry<String, PlayerCapStats> entry : entries) {
            PlayerCapStats stats = entry.getValue();
            if (blockedView && stats.blockedChecks <= 0L) {
                continue;
            }
            if (!blockedView && stats.maxMonsterCount <= 0) {
                continue;
            }
            if (written > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey())
                    .append("=")
                    .append(stats.maxMonsterCount)
                    .append("@(")
                    .append(stats.lastChunkX)
                    .append(",")
                    .append(stats.lastChunkZ)
                    .append(")");
            if (blockedView) {
                builder.append("x").append(stats.blockedChecks);
            }
            written++;
            if (written >= MAX_TOP_ENTRIES) {
                break;
            }
        }
        builder.append("]");
        return builder.toString();
    }

    private static final class PlayerCapStats {
        private int maxMonsterCount;
        private long blockedChecks;
        private long sameChunkChecks;
        private int lastChunkX;
        private int lastChunkZ;
    }
}
