package net.malfact.fixtrialspawners;

import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import org.bukkit.*;
import org.bukkit.block.TrialSpawner;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.LootTables;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class FixTrialSpawners extends JavaPlugin {


    @Override
    public void onEnable() {
        String worldFolder = Bukkit.getWorlds().getFirst().getWorldFolder().getPath();

        Thread thread = new Thread(() -> {
            getComponentLogger().warn(":: <<Started Reading Region Files>> ::");
            var chunks = readRegionFolder(Paths.get(worldFolder + "/region"));
            getComponentLogger().warn(":: <<Finished Reading Region Files>> | {} Chunks Scheduled", chunks.size());

            var runnable = new ChunkRunnable(Bukkit.getWorlds().getFirst(), new ArrayList<>(chunks.values()));
            runnable.runTaskTimer(this, 1L, 1L);

        });
        thread.start();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
    }

    private class ChunkRunnable extends BukkitRunnable {

        private final World world;
        private final List<ChunkData> chunks;

        private int runningChunks = 0;

        public ChunkRunnable(World world, List<ChunkData> chunks) {
            this.world = world;
            this.chunks = chunks;
        }

        @Override
        public void run() {
            getComponentLogger().warn(":: [ {} ] Chunks Working / [ {} ] Remaining ::", runningChunks, chunks.size());
            if (runningChunks <= 0 && chunks.isEmpty()) {
                getComponentLogger().warn(":: Finished Repairing Trial Spawners! ::");
                getComponentLogger().warn(":: Stop the Server and Remove this plugin! ::");
                this.cancel();
                return;
            }

            if (this.isCancelled()) {
                getComponentLogger().warn(":: Chunk Repairs Canceled before Completion ::");
                return;
            }

            while (runningChunks < 20 && !chunks.isEmpty()) {
                var chunkData = chunks.removeFirst();
                var future = world.getChunkAtAsync(chunkData.x, chunkData.z);
                future.thenAccept(chunk -> {
                    getComponentLogger().info("  Repairing [ {} ] Trial Spawners in Chunk <{},{}>", chunkData.count(), chunk.getX(), chunk.getZ());
                    while (chunkData.hasNext()) {
                        var data = chunkData.next();
                        try {
                            var state = chunk.getBlock(data.x, data.y, data.z).getState();
                            if (state instanceof TrialSpawner spawner) {
                                repairSpawner(spawner, data);
                            } else {
                                getComponentLogger().info("  | >> Mismatched {} for {}", data.spawnerType, state.getType());
                            }
                        } catch (Exception e) {
                            getComponentLogger().error("  | >> Error while Repairing {} {}", data, e.getMessage());
                        }
                    }
                    runningChunks--;
                });

                runningChunks++;
            }
        }
    }

//    private List<SpawnerData> readRegionFolder(Path folder) {
    private Map<String, ChunkData> readRegionFolder(Path folder) {
        File dir = folder.toFile();

        Map<String, ChunkData> chunks = new HashMap<>();

        if (!dir.exists() || !dir.isDirectory())
//            return new ArrayList<>();
            return chunks;

        File[] files = dir.listFiles();

        if (files == null)
//            return new ArrayList<>();
            return chunks;

//        List<SpawnerData> spawners = new ArrayList<>();

        int i = 1;
        int count = files.length;
        for (File file : files) {
            try {
                var nbt = McaFileHelpers.readAuto(file);
                if (nbt instanceof McaRegionFile mca) {
                    getComponentLogger().warn("Reading Region File [ {} / {} ]: {} ",  i, count, file.getName());
                    readRegionFile(mca, chunks);
                }
            } catch (IOException e) {
                getComponentLogger().error("Error Reading Region File: {}", file.getName());
            }
            i++;
        }

//        return spawners;a
        return chunks;
    }

    private static final Map<String, SpawnerType> SPAWNER_MAP = Map.ofEntries(
        Map.entry("minecraft:trial_chambers/spawner/breeze/breeze", SpawnerType.BREEZE),
        Map.entry("minecraft:trial_chambers/spawner/melee/husk", SpawnerType.HUSK),
        Map.entry("minecraft:trial_chambers/spawner/melee/spider", SpawnerType.SPIDER),
        Map.entry("minecraft:trial_chambers/spawner/melee/zombie", SpawnerType.ZOMBIE),
        Map.entry("minecraft:trial_chambers/spawner/ranged/poison_skeleton", SpawnerType.POISON_SKELETON),
        Map.entry("minecraft:trial_chambers/spawner/ranged/skeleton", SpawnerType.SKELETON),
        Map.entry("minecraft:trial_chambers/spawner/ranged/stray", SpawnerType.STRAY),
        Map.entry("minecraft:trial_chambers/spawner/slow_ranged/poison_skeleton", SpawnerType.SLOW_POISON_SKELETON),
        Map.entry("minecraft:trial_chambers/spawner/slow_ranged/skeleton", SpawnerType.SLOW_SKELETON),
        Map.entry("minecraft:trial_chambers/spawner/slow_ranged/stray", SpawnerType.SLOW_STRAY),
        Map.entry("minecraft:trial_chambers/spawner/small_melee/baby_zombie", SpawnerType.BABY_ZOMBIE),
        Map.entry("minecraft:trial_chambers/spawner/small_melee/cave_spider", SpawnerType.CAVE_SPIDER),
        Map.entry("minecraft:trial_chambers/spawner/small_melee/silverfish", SpawnerType.SILVERFISH),
        Map.entry("minecraft:trial_chambers/spawner/small_melee/slime", SpawnerType.SLIME)
    );

//    private List<SpawnerData> readRegionFile(McaRegionFile mca) {
    private void readRegionFile(McaRegionFile mca, Map<String, ChunkData> chunks) {
        if (mca == null)
            return;

        mca.forEach(chunk -> {
            if (chunk == null)
                return;

            var structures = chunk.getStructures();
            var starts = structures.getCompoundTag("starts");
            if (!starts.containsKey("minecraft:trial_chambers"))
                return;

            var structure = starts.getCompoundTag("minecraft:trial_chambers");
            var children = structure.getCompoundList("Children");
            children.forEach(child -> {
                var room = child.getCompoundTag("pool_element").getString("location", "");
                if (!SPAWNER_MAP.containsKey(room))
                    return;

                var rotation = child.getString("rotation", "NONE");
                int dX = rotation.equals("CLOCKWISE_90") || rotation.equals("CLOCKWISE_180") ? -1 : 1;
                int dZ = rotation.equals("COUNTERCLOCKWISE_90") || rotation.equals("CLOCKWISE_180") ? -1 : 1;

                int x = child.getInt("PosX") + dX;
                int y = child.getInt("PosY") + 1;
                int z = child.getInt("PosZ") + dZ;

//                var spawner = new SpawnerData(x, y, z, SPAWNER_MAP.get(room));
//                spawners.add(spawner);
                int chunkX = Math.floorDiv(x, 16);
                int chunkZ = Math.floorDiv(z, 16);
                var chunkData = chunks.computeIfAbsent(chunkX + "," + chunkZ, k -> new ChunkData(chunkX, chunkZ));

                int relativeX = x - chunkX * 16;
                int relativeZ = z - chunkZ * 16;
                var spawner = new SpawnerData(relativeX, y, relativeZ, SPAWNER_MAP.get(room));
                chunkData.add(spawner);

                getComponentLogger().info("  | > Found Trial Spawner: Chunk: {} | {}", chunkData, spawner);
            });
        });

//        return spawners;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void repairSpawner(TrialSpawner spawner, SpawnerData data) {
        var normalConfig = spawner.getNormalConfiguration();
        var ominousConfig = spawner.getOminousConfiguration();

        // Drops
        normalConfig.setPossibleRewards(
            Map.of(
                LootTables.TRIAL_CHAMBER_KEY.getLootTable(), 3,
                LootTables.TRIAL_CHAMBER_CONSUMABLES.getLootTable(), 7
            )
        );
        ominousConfig.setPossibleRewards(
            Map.of(
                LootTables.OMINOUS_TRIAL_CHAMBER_KEY.getLootTable(), 3,
                LootTables.OMINOUS_TRIAL_CHAMBER_CONSUMABLES.getLootTable(), 7
            )
        );

        // Values
        var multiplier = data.spawnerType.canWearArmor ? 1f : 2f;
        normalConfig.setBaseSpawnsBeforeCooldown(data.spawnerType.tm * multiplier);
        normalConfig.setAdditionalSpawnsBeforeCooldown(data.spawnerType.atm * multiplier);
        normalConfig.setBaseSimultaneousEntities(data.spawnerType.sm);
        normalConfig.setAdditionalSimultaneousEntities(data.spawnerType.asm);

        ominousConfig.setBaseSpawnsBeforeCooldown(data.spawnerType.tm * multiplier);
        ominousConfig.setAdditionalSpawnsBeforeCooldown(data.spawnerType.atm * multiplier);
        ominousConfig.setBaseSimultaneousEntities(data.spawnerType.sm);
        ominousConfig.setAdditionalSimultaneousEntities(data.spawnerType.asm);

        // Spawns
        if (!data.spawnerType.canWearArmor) {
            normalConfig.setSpawnedType(data.spawnerType.entityType);
            ominousConfig.setSpawnedType(data.spawnerType.entityType);
        } else {
            World world = spawner.getWorld();
            assert data.spawnerType.entityType.getEntityClass() != null;
            var entity = world.createEntity(spawner.getLocation(), data.spawnerType.entityType.getEntityClass());
            var lootTable = data.spawnerType.category == Category.RANGED ? LootTables.EQUIPMENT_TRIAL_CHAMBER_RANGED.getLootTable() : LootTables.EQUIPMENT_TRIAL_CHAMBER_MELEE.getLootTable();
            var entry = new SpawnerEntry(Objects.requireNonNull(entity.createSnapshot()), 1, null, new SpawnerEntry.Equipment(lootTable, Map.of()));
            normalConfig.setSpawnedEntity(entry);
            ominousConfig.setSpawnedEntity(entry);
        }

        spawner.setCooldownLength(0);
        var blockData = spawner.getBlockData();
        if (blockData instanceof org.bukkit.block.data.type.TrialSpawner spawnerData) {
            spawnerData.setTrialSpawnerState(org.bukkit.block.data.type.TrialSpawner.State.WAITING_FOR_PLAYERS);
            spawner.setBlockData(blockData);
        }

        getComponentLogger().info("  | Repaired {}", data);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void readSpawner(TrialSpawner spawner) {
        var logger = getComponentLogger();

        var config = spawner.getNormalConfiguration();
        logger.info("Trial Spawner::");
        logger.info("---Normal---");
        logger.info("  Type: {}", config.getSpawnedType());
        logger.info("  Base Simultaneous Entities       {}", config.getBaseSimultaneousEntities());
        logger.info("  Additional Simultaneous Entities {}", config.getAdditionalSimultaneousEntities());
        logger.info("  Base Spawns Before Cooldown      {}", config.getAdditionalSpawnsBeforeCooldown());
        logger.info("  Additional pawns Before Cooldown {}", config.getBaseSimultaneousEntities());

        logger.info("---Ominous---");
        config = spawner.getOminousConfiguration();
        logger.info("  Type: {}", config.getSpawnedType());
        logger.info("  Base Simultaneous Entities       {}", config.getBaseSimultaneousEntities());
        logger.info("  Additional Simultaneous Entities {}", config.getAdditionalSimultaneousEntities());
        logger.info("  Base Spawns Before Cooldown      {}", config.getAdditionalSpawnsBeforeCooldown());
        logger.info("  Additional pawns Before Cooldown {}", config.getBaseSimultaneousEntities());
    }

    private record SpawnerData(int x, int y, int z, SpawnerType spawnerType) {
        @Override
        public String toString() {
            return spawnerType + " @ <" + x + ", " + y + ", " + z + ">";
        }
    }

    private enum SpawnerType {
        BREEZE              (EntityType.BREEZE,     Category.BREEZE, false, 2f, 1f, 1f, 0.5f),
        HUSK                (EntityType.HUSK,       Category.MELEE),
        SPIDER              (EntityType.SPIDER,     Category.MELEE, false),
        ZOMBIE              (EntityType.ZOMBIE,     Category.MELEE),
        POISON_SKELETON     (EntityType.BOGGED,     Category.RANGED),
        SKELETON            (EntityType.SKELETON,   Category.RANGED),
        STRAY               (EntityType.STRAY,      Category.RANGED),
        SLOW_POISON_SKELETON(EntityType.BOGGED,     Category.RANGED, 6f, 2f, 34f, 2f),
        SLOW_SKELETON       (EntityType.SKELETON,   Category.RANGED, 6f, 2f, 34f, 2f),
        SLOW_STRAY          (EntityType.STRAY,      Category.RANGED, 6f, 2f, 34f, 2f),
        BABY_ZOMBIE         (EntityType.ZOMBIE,     Category.SMALL_MELEE, 6f, 2f, 2f, 0.5f),
        CAVE_SPIDER         (EntityType.CAVE_SPIDER,Category.SMALL_MELEE, false),
        SILVERFISH          (EntityType.SILVERFISH, Category.SMALL_MELEE, false),
        SLIME               (EntityType.SLIME,      Category.SMALL_MELEE, false);

        public final EntityType entityType;
        public final Category category;
        public final boolean canWearArmor;
        public final float tm;
        public final float atm;
        public final float sm;
        public final float asm;

        SpawnerType(EntityType entityType, Category category) {
            this(entityType, category, true);
        }

        SpawnerType(EntityType entityType, Category category, boolean canWearArmor) {
            this(entityType, category, canWearArmor, 6f, 2f, 3f, 0.5f);
        }

        SpawnerType(EntityType entityType, Category category, float tm, float atm, float sm, float asm) {
            this(entityType, category, true, tm, atm, sm, asm);
        }

        SpawnerType(EntityType entityType, Category category, boolean canWearArmor, float tm, float atm, float sm, float asm) {
            this.entityType = entityType;
            this.category = category;
            this.canWearArmor = canWearArmor;
            this.tm = tm;
            this.atm = atm;
            this.sm = sm;
            this.asm = asm;
        }
    }

    private enum Category {
        BREEZE,
        SMALL_MELEE,
        MELEE,
        RANGED
    }

    private static class ChunkData {
        public final int x;
        public final int z;

        private final List<SpawnerData> spawners;

        public ChunkData(int x, int z) {
            this.x = x;
            this.z = z;
            this.spawners = new ArrayList<>();
        }

        public boolean hasNext() {
            return !spawners.isEmpty();
        }

        @NotNull
        public SpawnerData next() {
            return spawners.removeFirst();
        }

        public void add(SpawnerData data) {
            spawners.add(data);
        }

        public int count() {
            return spawners.size();
        }

        @Override
        public String toString() {
            return "<" + x + ", " + z + ">";
        }
    }
}
