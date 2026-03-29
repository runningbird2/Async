package com.axalotl.async.common.commands;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.platform.Permission;
import com.axalotl.async.common.spawn.AsyncMobcapTrackedMob;
import com.axalotl.async.common.spawn.AsyncServerChunkCacheSpawnStateAccess;
import com.axalotl.async.common.spawn.AsyncSpawnStateMobcapAccess;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axalotl.async.common.ParallelProcessor.getPoolSize;
import static com.axalotl.async.common.commands.AsyncCommand.prefix;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class StatsCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerStatus(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root.then(literal("stats").requires(Permission.require("command.statistics", 0))
                .executes(cmdCtx -> {
                    showGeneralStats(cmdCtx.getSource());
                    return 1;
                })
                .then(literal("entity")
                        .executes(cmdCtx -> {
                            showEntityStats(cmdCtx.getSource(), 0);
                            return 1;
                        })
                        .then(argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(cmdCtx -> {
                                    int count = IntegerArgumentType.getInteger(cmdCtx, "count");
                                    showEntityStats(cmdCtx.getSource(), count);
                                    return 1;
                                })))
                .then(literal("breakdown")
                        .executes(cmdCtx -> {
                            showMobcapBreakdown(cmdCtx.getSource(), cmdCtx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(argument("player", EntityArgument.player())
                                .executes(cmdCtx -> {
                                    showMobcapBreakdown(cmdCtx.getSource(), EntityArgument.getPlayer(cmdCtx, "player"));
                                    return 1;
                                })))
                .then(literal("mobcap")
                        .executes(cmdCtx -> {
                            showMobcapStats(cmdCtx.getSource(), cmdCtx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(argument("player", EntityArgument.player())
                                .executes(cmdCtx -> {
                                    showMobcapStats(cmdCtx.getSource(), EntityArgument.getPlayer(cmdCtx, "player"));
                                    return 1;
                                }))));
    }

    private static void showGeneralStats(CommandSourceStack source) {
        MinecraftServer server = source.getServer();

        int totalEntities = 0;
        for (var world : server.getAllLevels()) {
            for (var entity : world.getAllEntities()) {
                if (entity.isAlive()) {
                    totalEntities++;
                }
            }
        }

        int threads = getPoolSize();

        boolean enabled = !AsyncConfig.disabled;
        boolean asyncSpawn = AsyncConfig.enableAsyncSpawn;
        boolean asyncRandomTicks = AsyncConfig.enableAsyncRandomTicks;

        MutableComponent message = prefix.copy()
                .append(Component.literal("Performance Statistics").withStyle(ChatFormatting.GOLD))

                .append(Component.literal("\nStatus: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(enabled ? "Enabled" : "Disabled")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nAsync Spawn: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(asyncSpawn ? "Enabled" : "Disabled")
                        .withStyle(asyncSpawn ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nAsync Random Ticks: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(asyncRandomTicks ? "Enabled" : "Disabled")
                        .withStyle(asyncRandomTicks ? ChatFormatting.GREEN : ChatFormatting.RED))

                .append(Component.literal("\nEntities: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(totalEntities)).withStyle(ChatFormatting.GREEN))

                .append(Component.literal("\nMax Threads: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(threads)).withStyle(ChatFormatting.YELLOW));

        source.sendSuccess(() -> message, false);
    }

    private static void showEntityStats(CommandSourceStack source, int topCount) {
        MinecraftServer server = source.getServer();
        server.execute(() -> {
            Map<EntityType<?>, Integer> entityTypeCounts = new HashMap<>();
            Map<EntityType<?>, Boolean> entityTypeAsync = new HashMap<>();
            AtomicInteger totalEntities = new AtomicInteger(0);
            AtomicInteger totalAsyncEntities = new AtomicInteger(0);

            MutableComponent message = prefix.copy()
                    .append(Component.literal("Entity Statistics").withStyle(ChatFormatting.GOLD));

            server.getAllLevels().forEach(world -> {
                String worldName = world.dimensionTypeRegistration().getRegisteredName();
                AtomicInteger worldCount = new AtomicInteger(0);
                AtomicInteger asyncCount = new AtomicInteger(0);

                world.getAllEntities().forEach(entity -> {
                    if (entity.isAlive()) {
                        EntityType<?> entityType = entity.getType();
                        worldCount.incrementAndGet();
                        totalEntities.incrementAndGet();
                        entityTypeCounts.merge(entityType, 1, Integer::sum);

                        boolean isAsync = !ParallelProcessor.shouldTickSynchronously(entity);
                        entityTypeAsync.put(entityType, isAsync);

                        if (isAsync) {
                            asyncCount.incrementAndGet();
                            totalAsyncEntities.incrementAndGet();
                        }
                    }
                });

                message.append(Component.literal("\n" + worldName + ": ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(String.valueOf(worldCount.get())).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" entities (").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.valueOf(asyncCount.get())).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" async)").withStyle(ChatFormatting.GRAY));
            });

            message.append(Component.literal("\nTotal Entities: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(totalEntities.get())).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(totalAsyncEntities.get())).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" async)").withStyle(ChatFormatting.GRAY));

            if (topCount > 0 && !entityTypeCounts.isEmpty()) {
                message.append(Component.literal("\n\nTop " + topCount + " Entity Types:").withStyle(ChatFormatting.GOLD));

                final int[] rank = {1};
                entityTypeCounts.entrySet().stream()
                        .sorted(Map.Entry.<EntityType<?>, Integer>comparingByValue().reversed())
                        .limit(topCount)
                        .forEach(entry -> {
                            EntityType<?> type = entry.getKey();
                            int count = entry.getValue();
                            boolean isAsync = entityTypeAsync.getOrDefault(type, false);

                            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                            String name = id.getPath();

                            message.append(Component.literal("\n" + rank[0] + ". ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.GREEN))
                                    .append(Component.literal(" [").withStyle(ChatFormatting.DARK_GRAY))
                                    .append(Component.literal(isAsync ? "async" : "sync")
                                            .withStyle(isAsync ? ChatFormatting.AQUA : ChatFormatting.RED))
                                    .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));

                            rank[0]++;
                        });
            }

            source.sendSuccess(() -> message, false);
        });
    }

    private static void showMobcapStats(CommandSourceStack source, ServerPlayer target) {
        NaturalSpawner.SpawnState spawnState = ((AsyncServerChunkCacheSpawnStateAccess) target.level().getChunkSource()).async$getLastSpawnState();
        if (!(spawnState instanceof AsyncSpawnStateMobcapAccess mobcapAccess)) {
            source.sendFailure(prefix.copy()
                    .append(Component.literal("Mobcap state is not available right now.").withStyle(ChatFormatting.RED)));
            return;
        }

        ChunkPos chunkPos = target.chunkPosition();
        MutableComponent message = prefix.copy()
                .append(Component.literal("Mobcaps for ").withStyle(ChatFormatting.WHITE))
                .append(target.getDisplayName().copy().withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\nWorld: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(target.level().dimension().identifier().toString()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\nChunk: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(chunkPos.x + ", " + chunkPos.z).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nSpawnable Chunks: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(mobcapAccess.async$getSpawnableChunkCount())).withStyle(ChatFormatting.GREEN));

        for (MobCategory category : MobCategory.values()) {
            if (category == MobCategory.MISC) {
                continue;
            }

            int localLimit = category.getMaxInstancesPerChunk();
            int localCount = mobcapAccess.async$getLocalMobCount(target, category);
            int headroom = mobcapAccess.async$getLocalMobHeadroom(target, category);
            int globalCount = mobcapAccess.async$getEffectiveMobCount(category);
            int globalLimit = mobcapAccess.async$getGlobalMobCap(category);

            message.append(Component.literal("\n" + async$formatCategoryName(category) + ": ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("local ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(localCount)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(String.valueOf(localLimit)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("  remaining ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(headroom)).withStyle(headroom > 0 ? ChatFormatting.AQUA : ChatFormatting.RED))
                    .append(Component.literal("  global ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.valueOf(globalCount)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(String.valueOf(globalLimit)).withStyle(ChatFormatting.GREEN));
        }

        source.sendSuccess(() -> message, false);
    }

    private static void showMobcapBreakdown(CommandSourceStack source, ServerPlayer target) {
        AsyncServerChunkCacheSpawnStateAccess chunkSourceAccess = (AsyncServerChunkCacheSpawnStateAccess) target.level().getChunkSource();
        NaturalSpawner.SpawnState spawnState = chunkSourceAccess.async$getLastSpawnState();
        AsyncSpawnStateMobcapAccess mobcapAccess = spawnState instanceof AsyncSpawnStateMobcapAccess access ? access : null;

        List<ServerPlayer> players = new ArrayList<>(target.level().players());
        int softDespawnDistance = MobCategory.MONSTER.getNoDespawnDistance();
        int hardDespawnDistance = MobCategory.MONSTER.getDespawnDistance();
        double softDespawnDistanceSqr = (double) softDespawnDistance * softDespawnDistance;
        double hardDespawnDistanceSqr = (double) hardDespawnDistance * hardDespawnDistance;
        int totalMonsters = 0;
        int currentCountedMonsters = 0;
        int excludedPersistentMonsters = 0;
        int flaggedTrackedMonsters = 0;
        int flaggedCountedMonsters = 0;
        int flaggedExcludedMonsters = 0;
        int unflaggedCountedMonsters = 0;
        int zeroNearbyMonsters = 0;
        int singleNearbyMonsters = 0;
        int sharedNearbyMonsters = 0;
        int maxNearbyPlayers = 0;
        int countedWithinSoftDespawn = 0;
        int countedSoftToHardDespawn = 0;
        int countedBeyondHardDespawn = 0;
        int countedBeyondHardDespawnTicking = 0;
        int countedBeyondHardDespawnNonTicking = 0;
        int zeroNearbyCountedMonsters = 0;
        int zeroNearbyCountedBeyondHardDespawn = 0;
        int zeroNearbyCountedBeyondHardTicking = 0;
        List<String> zeroNearbyHardDespawnSamples = new ArrayList<>();

        for (Entity entity : target.level().getAllEntities()) {
            if (!entity.isAlive() || entity.getType().getCategory() != MobCategory.MONSTER) {
                continue;
            }

            totalMonsters++;

            ChunkPos entityChunkPos = new ChunkPos(entity.blockPosition());
            long entityChunkLong = entityChunkPos.toLong();
            int nearbyPlayers = async$countPlayersCloseForSpawning(players, entityChunkPos);
            maxNearbyPlayers = Math.max(maxNearbyPlayers, nearbyPlayers);
            if (nearbyPlayers <= 0) {
                zeroNearbyMonsters++;
            } else if (nearbyPlayers == 1) {
                singleNearbyMonsters++;
            } else {
                sharedNearbyMonsters++;
            }

            boolean excludedByPersistence = entity instanceof Mob mob
                    && (mob.isPersistenceRequired() || mob.requiresCustomPersistence());
            boolean flaggedTracked = entity instanceof Mob mob
                    && ((AsyncMobcapTrackedMob) mob).async$countsTowardSpawnCap();

            if (flaggedTracked) {
                flaggedTrackedMonsters++;
            }

            if (excludedByPersistence) {
                excludedPersistentMonsters++;
                if (flaggedTracked) {
                    flaggedExcludedMonsters++;
                }
                continue;
            }

            currentCountedMonsters++;
            if (flaggedTracked) {
                flaggedCountedMonsters++;
            } else {
                unflaggedCountedMonsters++;
            }

            NearestPlayerDistance nearestPlayer = async$findNearestSpawningPlayer(players, entity);
            double nearestDistanceSqr = nearestPlayer != null ? nearestPlayer.distanceSqr() : Double.POSITIVE_INFINITY;
            boolean beyondHardDespawn = nearestDistanceSqr > hardDespawnDistanceSqr;
            boolean beyondSoftDespawn = nearestDistanceSqr > softDespawnDistanceSqr;
            boolean tickingChunk = chunkSourceAccess.async$hasTickingChunk(entityChunkLong);
            boolean fullChunkLoaded = chunkSourceAccess.async$hasFullChunk(entityChunkLong);

            if (beyondHardDespawn) {
                countedBeyondHardDespawn++;
                if (tickingChunk) {
                    countedBeyondHardDespawnTicking++;
                } else if (fullChunkLoaded) {
                    countedBeyondHardDespawnNonTicking++;
                }
            } else if (beyondSoftDespawn) {
                countedSoftToHardDespawn++;
            } else {
                countedWithinSoftDespawn++;
            }

            if (nearbyPlayers <= 0) {
                zeroNearbyCountedMonsters++;
                if (beyondHardDespawn) {
                    zeroNearbyCountedBeyondHardDespawn++;
                    if (tickingChunk) {
                        zeroNearbyCountedBeyondHardTicking++;
                    }
                    if (zeroNearbyHardDespawnSamples.size() < 5) {
                        String typeName = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
                        String nearestPlayerName = nearestPlayer != null ? nearestPlayer.player().getScoreboardName() : "none";
                        double nearestDistance = nearestPlayer != null ? Math.sqrt(nearestDistanceSqr) : Double.POSITIVE_INFINITY;
                        zeroNearbyHardDespawnSamples.add(
                                typeName
                                        + " @ " + entity.blockPosition().getX() + ", " + entity.blockPosition().getY() + ", " + entity.blockPosition().getZ()
                                        + " chunk " + entityChunkPos.x + ", " + entityChunkPos.z
                                        + " nearest " + nearestPlayerName + "=" + async$formatDistance(nearestDistance)
                                        + " full=" + fullChunkLoaded
                                        + " ticking=" + tickingChunk
                        );
                    }
                }
            }
        }

        int localLimit = MobCategory.MONSTER.getMaxInstancesPerChunk();
        int playersAtLocalCap = 0;
        int summedLocalMonsterCounts = 0;
        List<Map.Entry<ServerPlayer, Integer>> localMonsterCounts = new ArrayList<>();

        if (mobcapAccess != null) {
            for (ServerPlayer player : players) {
                int localCount = mobcapAccess.async$getLocalMobCount(player, MobCategory.MONSTER);
                summedLocalMonsterCounts += localCount;
                if (localCount >= localLimit) {
                    playersAtLocalCap++;
                }
                localMonsterCounts.add(Map.entry(player, localCount));
            }
            localMonsterCounts.sort(Comparator
                    .<Map.Entry<ServerPlayer, Integer>>comparingInt(Map.Entry::getValue)
                    .reversed()
                    .thenComparing(entry -> entry.getKey().getScoreboardName(), String.CASE_INSENSITIVE_ORDER));
        }

        MutableComponent message = prefix.copy()
                .append(Component.literal("Monster Mobcap Breakdown").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\nWorld: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(target.level().dimension().identifier().toString()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\nPlayers: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(players.size())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nAlive Monsters: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(totalMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nCounted By Current Spawn State: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(currentCountedMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nExcluded By Persistence: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(excludedPersistentMonsters)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\nMarked Natural/ChunkGen: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(flaggedTrackedMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nMarked And Counted Now: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(flaggedCountedMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nMarked But Excluded Now: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(flaggedExcludedMonsters)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\nUnmarked But Counted Now: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(unflaggedCountedMonsters)).withStyle(unflaggedCountedMonsters > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
                .append(Component.literal("\nCounted Distance Bands: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("<= " + softDespawnDistance + "=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(countedWithinSoftDespawn)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  " + (softDespawnDistance + 1) + "-" + hardDespawnDistance + "=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(countedSoftToHardDespawn)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  >" + hardDespawnDistance + "=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(countedBeyondHardDespawn)).withStyle(countedBeyondHardDespawn > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
                .append(Component.literal("\nCounted Beyond Hard Despawn: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(countedBeyondHardDespawn)).withStyle(countedBeyondHardDespawn > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
                .append(Component.literal("  ticking=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(countedBeyondHardDespawnTicking)).withStyle(countedBeyondHardDespawnTicking > 0 ? ChatFormatting.RED : ChatFormatting.GREEN))
                .append(Component.literal("  full-only=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(countedBeyondHardDespawnNonTicking)).withStyle(countedBeyondHardDespawnNonTicking > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
                .append(Component.literal("\nNearby Player Coverage: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("0=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(zeroNearbyMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  1=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(singleNearbyMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  2+=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(sharedNearbyMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nZero-Coverage Counted Now: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(zeroNearbyCountedMonsters)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\nZero-Coverage Counted Beyond Hard: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(zeroNearbyCountedBeyondHardDespawn)).withStyle(zeroNearbyCountedBeyondHardDespawn > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN))
                .append(Component.literal("  ticking=").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(zeroNearbyCountedBeyondHardTicking)).withStyle(zeroNearbyCountedBeyondHardTicking > 0 ? ChatFormatting.RED : ChatFormatting.GREEN))
                .append(Component.literal("\nMax Nearby Players On A Monster Chunk: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(maxNearbyPlayers)).withStyle(ChatFormatting.GREEN));

        if (mobcapAccess != null) {
            int globalCount = mobcapAccess.async$getEffectiveMobCount(MobCategory.MONSTER);
            int globalLimit = mobcapAccess.async$getGlobalMobCap(MobCategory.MONSTER);
            int spawnableChunks = mobcapAccess.async$getSpawnableChunkCount();

            message.append(Component.literal("\nSpawn State Monster Global: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(globalCount)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(String.valueOf(globalLimit)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("\nSpawnable Chunks: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(spawnableChunks)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("\nSummed Per-Player Local Monsters: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(summedLocalMonsterCounts)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("\nPlayers At Local Cap: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(String.valueOf(playersAtLocalCap)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(String.valueOf(players.size())).withStyle(ChatFormatting.GREEN));

            if (!localMonsterCounts.isEmpty()) {
                message.append(Component.literal("\nTop Local Monster Counts:").withStyle(ChatFormatting.GOLD));
                int entriesToShow = Math.min(10, localMonsterCounts.size());
                for (int i = 0; i < entriesToShow; i++) {
                    Map.Entry<ServerPlayer, Integer> entry = localMonsterCounts.get(i);
                    message.append(Component.literal("\n" + (i + 1) + ". ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(entry.getKey().getScoreboardName()).withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(String.valueOf(entry.getValue())).withStyle(ChatFormatting.GREEN))
                            .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                            .append(Component.literal(String.valueOf(localLimit)).withStyle(ChatFormatting.GREEN));
                }
            }
        } else {
            message.append(Component.literal("\nSpawn State: ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("unavailable").withStyle(ChatFormatting.RED));
        }

        if (!zeroNearbyHardDespawnSamples.isEmpty()) {
            message.append(Component.literal("\nZero-Coverage > Hard Despawn Samples:").withStyle(ChatFormatting.GOLD));
            for (int i = 0; i < zeroNearbyHardDespawnSamples.size(); i++) {
                message.append(Component.literal("\n" + (i + 1) + ". ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(zeroNearbyHardDespawnSamples.get(i)).withStyle(ChatFormatting.YELLOW));
            }
        }

        source.sendSuccess(() -> message, false);
    }

    private static int async$countPlayersCloseForSpawning(List<ServerPlayer> players, ChunkPos chunkPos) {
        double chunkCenterX = SectionPos.sectionToBlockCoord(chunkPos.x, 8);
        double chunkCenterZ = SectionPos.sectionToBlockCoord(chunkPos.z, 8);
        int nearbyPlayers = 0;

        for (ServerPlayer player : players) {
            if (player.isSpectator()) {
                continue;
            }

            double deltaX = chunkCenterX - player.getX();
            double deltaZ = chunkCenterZ - player.getZ();
            if (deltaX * deltaX + deltaZ * deltaZ < 16384.0) {
                nearbyPlayers++;
            }
        }

        return nearbyPlayers;
    }

    private static NearestPlayerDistance async$findNearestSpawningPlayer(List<ServerPlayer> players, Entity entity) {
        ServerPlayer nearestPlayer = null;
        double nearestDistanceSqr = Double.POSITIVE_INFINITY;

        for (ServerPlayer player : players) {
            if (player.isSpectator()) {
                continue;
            }

            double distanceSqr = player.distanceToSqr(entity);
            if (distanceSqr < nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearestPlayer = player;
            }
        }

        if (nearestPlayer == null) {
            return null;
        }
        return new NearestPlayerDistance(nearestPlayer, nearestDistanceSqr);
    }

    private static String async$formatDistance(double distance) {
        if (!Double.isFinite(distance)) {
            return "inf";
        }
        return String.format(Locale.ROOT, "%.1f", distance);
    }

    private static String async$formatCategoryName(MobCategory category) {
        String[] parts = category.getName().split("_");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private record NearestPlayerDistance(ServerPlayer player, double distanceSqr) {
    }
}
