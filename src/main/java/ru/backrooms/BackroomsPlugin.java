package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Painting;
import org.bukkit.Art;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class BackroomsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private String worldName;
    private int originX, originZ, levelSpacing, floorY, ceilingY;
    private boolean generationEnabled;
    private final Set<String> builtChunks = new HashSet<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, String> returnWorlds = new HashMap<>();
    private final Map<UUID, Long> portalCooldowns = new HashMap<>();
    private NamespacedKey lootMarker;
    private File playersDataFile;
    private FileConfiguration playersData;
    private final Set<String> trackedPlayerNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Random npcRandom = new Random();
    private final Map<String, Location> cloneLastLocations = new HashMap<>();
    private final Map<String, Long> cloneBehaviorUntil = new HashMap<>();
    private final Map<String, Long> cloneRespawnAt = new HashMap<>();
    private final Map<String, String> cloneModes = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        lootMarker = new NamespacedKey(this, "loot_populated");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("backrooms")).setExecutor(this);
        if (getCommand("br") != null) getCommand("br").setExecutor(this);
        loadTrackedPlayers();
        for (String name : getConfig().getStringList("clones.names")) {
            if (name != null && !name.isBlank()) trackedPlayerNames.add(name.trim());
        }
        if (getConfig().getStringList("clones.names").isEmpty()) {
            trackedPlayerNames.add("7gh");
            trackedPlayerNames.add("Alon");
            trackedPlayerNames.add("Voredishn");
        }
        if (getConfig().getBoolean("clones.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(this, this::spawnTrackedClones, 60L);
            Bukkit.getScheduler().runTaskTimer(this, this::tickCloneBehavior, 100L, 20L);
            if (!getServer().getPluginManager().isPluginEnabled("Citizens")) {
                getLogger().warning("NPC-клоны включены в config.yml, но Citizens не установлен. Установи совместимую версию Citizens для Paper 1.21.4, иначе игрокоподобные NPC и скины создать нельзя.");
            }
        }
        getLogger().info("Backrooms enabled. Configured world: " + worldName);
    }

    private void loadTrackedPlayers() {
        playersDataFile = new File(getDataFolder(), "players.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        playersData = YamlConfiguration.loadConfiguration(playersDataFile);
        for (String name : playersData.getStringList("seen-player-names")) {
            if (name != null && !name.isBlank()) trackedPlayerNames.add(name.trim());
        }
    }

    private void saveTrackedPlayers() {
        if (playersData == null) return;
        playersData.set("seen-player-names", new ArrayList<>(trackedPlayerNames));
        try { playersData.save(playersDataFile); }
        catch (IOException ex) { getLogger().warning("Не удалось сохранить список игроков: " + ex.getMessage()); }
    }

    @EventHandler
    public void onPlayerJoinTrack(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("clones.auto-track-players", true)) return;
        String name = event.getPlayer().getName();
        if (trackedPlayerNames.add(name)) saveTrackedPlayers();
        if (getConfig().getBoolean("clones.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> spawnCloneIfMissing(name), 100L);
        }
    }

    private void spawnTrackedClones() {
        if (!getConfig().getBoolean("clones.enabled", true)) return;
        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        int limit = Math.max(1, getConfig().getInt("clones.max-npcs", 16));
        int count = 0;
        for (String name : new ArrayList<>(trackedPlayerNames)) {
            if (count++ >= limit) break;
            spawnCloneIfMissing(name);
        }
    }

    private void spawnCloneIfMissing(String playerName) {
        if (!getConfig().getBoolean("clones.enabled", true) || !generationEnabled) return;
        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
            Object npcIterable = registry;
            if (npcIterable instanceof Iterable<?> iterable) {
                for (Object npc : iterable) {
                    Object existingName = npc.getClass().getMethod("getName").invoke(npc);
                    if (playerName.equalsIgnoreCase(String.valueOf(existingName))) return;
                }
            }
            int radiusChunks = Math.max(2, getConfig().getInt("clones.spawn-radius-chunks", 12));
            int chunkX = Math.floorDiv(originX, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
            int chunkZ = Math.floorDiv(originZ, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
            buildChunk(world, chunkX, chunkZ);
            Location spawn = findNpcSpawnLocation(world, chunkX, chunkZ);
            Method createNpc = null;
            for (Method method : registry.getClass().getMethods()) {
                if (method.getName().equals("createNPC") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == EntityType.class
                    && method.getParameterTypes()[1] == String.class) { createNpc = method; break; }
            }
            if (createNpc == null) throw new NoSuchMethodException("NPCRegistry.createNPC(EntityType, String)");
            Object npc = createNpc.invoke(registry, EntityType.PLAYER, playerName);
            try {
                Class<?> skinTrait = Class.forName("net.citizensnpcs.trait.SkinTrait");
                Object trait = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, skinTrait);
                skinTrait.getMethod("setSkinName", String.class).invoke(trait, playerName);
            } catch (ReflectiveOperationException skinError) {
                getLogger().warning("Не удалось назначить скин для NPC " + playerName + ": " + skinError.getMessage());
            }
            // Клон можно атаковать. Citizens управляет обычным уроном/смертью NPC.
            try { npc.getClass().getMethod("setProtected", boolean.class).invoke(npc, false); } catch (ReflectiveOperationException ignored) { }
            npc.getClass().getMethod("spawn", Location.class).invoke(npc, spawn);
            cloneLastLocations.put(playerName.toLowerCase(Locale.ROOT), spawn.clone());
            cloneModes.put(playerName.toLowerCase(Locale.ROOT), "wander");
            cloneBehaviorUntil.put(playerName.toLowerCase(Locale.ROOT), System.currentTimeMillis() + 3000L);
            getLogger().info("Создан NPC-клон игрока " + playerName + " в Backrooms.");
        } catch (ClassNotFoundException ex) {
            getLogger().warning("Citizens API не найдена; NPC-клоны не созданы.");
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Не удалось создать NPC-клон " + playerName + ": " + ex.getMessage());
        }
    }

    /**
     * Rare, unsettling NPC behavior: wander between nearby points, stare at a player,
     * then disappear and return later. Reflection keeps Citizens an optional dependency.
     */
    private void tickCloneBehavior() {
        if (!getConfig().getBoolean("clones.enabled", true) || !generationEnabled
                || !getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);
            if (!(registry instanceof Iterable<?> iterable)) return;
            long now = System.currentTimeMillis();
            for (Object npc : iterable) {
                String name = String.valueOf(npc.getClass().getMethod("getName").invoke(npc));
                String key = name.toLowerCase(Locale.ROOT);
                if (!trackedPlayerNames.contains(name) && !getConfig().getStringList("clones.names").contains(name)) continue;
                boolean spawned = Boolean.TRUE.equals(npc.getClass().getMethod("isSpawned").invoke(npc));
                Long respawnAt = cloneRespawnAt.get(key);
                if (!spawned) {
                    // If a player killed the NPC, let it return only after a long, random pause.
                    if (respawnAt == null) {
                        cloneRespawnAt.put(key, now + 45000L + npcRandom.nextInt(90000));
                        continue;
                    }
                    if (now >= respawnAt) {
                        Location base = findRandomCloneLocation(world);
                        if (base != null) {
                            npc.getClass().getMethod("spawn", Location.class).invoke(npc, base);
                            cloneLastLocations.put(key, base.clone());
                            cloneModes.put(key, "wander");
                            cloneBehaviorUntil.put(key, now + 3000L);
                            cloneRespawnAt.remove(key);
                        }
                    }
                    continue;
                }
                Object entityObj = npc.getClass().getMethod("getEntity").invoke(npc);
                if (!(entityObj instanceof org.bukkit.entity.Entity entity)) continue;
                Location current = entity.getLocation();
                if (!current.getWorld().equals(world)) continue;
                cloneLastLocations.put(key, current.clone());

                Player nearest = null;
                double nearestDistance = Double.MAX_VALUE;
                for (Player player : world.getPlayers()) {
                    double distance = player.getLocation().distanceSquared(current);
                    if (distance < nearestDistance) { nearestDistance = distance; nearest = player; }
                }
                String mode = cloneModes.getOrDefault(key, "wander");
                long until = cloneBehaviorUntil.getOrDefault(key, 0L);

                // While staring, keep its head aimed at the player. When the stare ends,
                // the clone usually vanishes; occasionally it turns and walks away.
                if ("stare".equals(mode)) {
                    if (nearest != null && nearestDistance <= 18 * 18) faceNpc(npc, nearest.getEyeLocation());
                    if (now < until) continue;
                    if (npcRandom.nextDouble() < 0.72) {
                        npc.getClass().getMethod("despawn").invoke(npc);
                        cloneRespawnAt.put(key, now + 12000L + npcRandom.nextInt(30000));
                        cloneModes.put(key, "hidden");
                    } else {
                        wanderNpc(npc, current, world);
                        cloneModes.put(key, "wander");
                        cloneBehaviorUntil.put(key, now + 8000L + npcRandom.nextInt(12000));
                    }
                    continue;
                }

                // A clone only acts when a player is reasonably close; sightings stay rare.
                if (nearest != null && nearestDistance <= 18 * 18 && now >= until) {
                    if (npcRandom.nextDouble() < 0.42) {
                        faceNpc(npc, nearest.getEyeLocation());
                        cloneModes.put(key, "stare");
                        cloneBehaviorUntil.put(key, now + 1800L + npcRandom.nextInt(2600));
                    } else if (npcRandom.nextDouble() < 0.38) {
                        // Disappear briefly when noticed, then return to a nearby location.
                        npc.getClass().getMethod("despawn").invoke(npc);
                        cloneRespawnAt.put(key, now + 12000L + npcRandom.nextInt(26000));
                        cloneModes.put(key, "hidden");
                        continue;
                    } else {
                        wanderNpc(npc, current, world);
                        cloneModes.put(key, "wander");
                        cloneBehaviorUntil.put(key, now + 7000L + npcRandom.nextInt(9000));
                    }
                } else if (now >= until) {
                    wanderNpc(npc, current, world);
                    cloneModes.put(key, "wander");
                    cloneBehaviorUntil.put(key, now + 9000L + npcRandom.nextInt(16000));
                }

            }
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            // Keep the server running even if a Citizens API version differs.
            getLogger().fine("NPC behavior tick skipped: " + ex.getMessage());
        }
    }

    private Location findRandomCloneLocation(World world) {
        int radiusChunks = Math.max(2, getConfig().getInt("clones.spawn-radius-chunks", 12));
        int cx = Math.floorDiv(originX, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
        int cz = Math.floorDiv(originZ, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
        buildChunk(world, cx, cz);
        return findNpcSpawnLocation(world, cx, cz);
    }

    private void faceNpc(Object npc, Location target) {
        try { npc.getClass().getMethod("faceLocation", Location.class).invoke(npc, target); }
        catch (ReflectiveOperationException ignored) { }
    }

    private void wanderNpc(Object npc, Location current, World world) {
        try {
            int radius = 6 + npcRandom.nextInt(13);
            int x = current.getBlockX() + npcRandom.nextInt(radius * 2 + 1) - radius;
            int z = current.getBlockZ() + npcRandom.nextInt(radius * 2 + 1) - radius;
            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
            buildChunk(world, cx, cz);
            Location target = findNpcSpawnLocation(world, cx, cz);
            cloneLastLocations.put(String.valueOf(npc.getClass().getMethod("getName").invoke(npc)).toLowerCase(Locale.ROOT), target.clone());
            Object navigator = npc.getClass().getMethod("getNavigator").invoke(npc);
            navigator.getClass().getMethod("setTarget", Location.class).invoke(navigator, target);
            // Vary pace so they do not move like identical scripted mannequins.
            try {
                Object params = navigator.getClass().getMethod("getLocalParameters").invoke(navigator);
                params.getClass().getMethod("speedModifier", float.class).invoke(params, 0.65f + npcRandom.nextFloat() * 0.45f);
            } catch (ReflectiveOperationException ignored) { }
        } catch (ReflectiveOperationException ignored) { }
    }

    private Location findNpcSpawnLocation(World world, int chunkX, int chunkZ) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        List<Location> candidates = new ArrayList<>();
        for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
            Block floor = world.getBlockAt(baseX + x, floorY, baseZ + z);
            Block feet = world.getBlockAt(baseX + x, floorY + 1, baseZ + z);
            Block head = world.getBlockAt(baseX + x, floorY + 2, baseZ + z);
            if (floor.getType().isSolid() && feet.getType().isAir() && head.getType().isAir()) {
                candidates.add(new Location(world, baseX + x + 0.5, floorY + 1, baseZ + z + 0.5));
            }
        }
        if (!candidates.isEmpty()) return candidates.get(npcRandom.nextInt(candidates.size()));
        return new Location(world, baseX + 8.5, floorY + 1, baseZ + 8.5);
    }

    private void readConfig() {
        FileConfiguration c = getConfig();
        worldName = c.getString("world-name", "flat");
        originX = c.getInt("origin-x", 0);
        originZ = c.getInt("origin-z", 0);
        levelSpacing = Math.max(1024, c.getInt("level-spacing", 4096));
        floorY = c.getInt("floor-y", 60);
        ceilingY = Math.max(floorY + 5, c.getInt("ceiling-y", 68));
        generationEnabled = c.getBoolean("generation-enabled", false);
    }

    // Do not save the entire config on shutdown: doing so can overwrite manual
    // edits made while the server is running. Settings are saved when commands
    // intentionally change them.

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("backrooms.admin")) {
            sender.sendMessage(ChatColor.RED + "Все команды Backrooms доступны только операторам (OP).");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/backrooms generate — включить генерацию в существующем мире");
            sender.sendMessage(ChatColor.YELLOW + "/backrooms stop — выключить генерацию");
            sender.sendMessage(ChatColor.YELLOW + "/br level <0-10|0.1-10.1|exit> — телепорт (только OP)");
            sender.sendMessage(ChatColor.YELLOW + "/backrooms info — показать состояние");
            return true;
        }
        if (args[0].equalsIgnoreCase("generate")) {
            if (!sender.hasPermission("backrooms.admin")) {
                sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                return true;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Мир '" + worldName + "' не загружен.");
                sender.sendMessage(ChatColor.YELLOW + "Проверь точное имя папки мира и что этот мир загружен сервером.");
                getLogger().warning("Configured world '" + worldName + "' is not loaded. Check plugins/Backrooms/config.yml and the world's folder name.");
                return true;
            }
            generationEnabled = true;
            getConfig().set("generation-enabled", true);
            saveConfig();
            // Generate current area and surrounding chunks for online players in configured world.
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(world)) buildAround(p.getLocation());
            }
            sender.sendMessage(ChatColor.GREEN + "Генерация Backrooms включена в мире " + worldName + ".");
            sender.sendMessage(ChatColor.GRAY + "Добавлены уровни 0–10, подуровни 0.1–10.1 и редкий уровень EXIT. Встречаемость уровней перемешана по карте.");
            Bukkit.getScheduler().runTaskLater(this, this::spawnTrackedClones, 40L);
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("backrooms.admin")) {
                sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                return true;
            }
            generationEnabled = false;
            getConfig().set("generation-enabled", false);
            saveConfig();
            sender.sendMessage(ChatColor.YELLOW + "Генерация Backrooms выключена.");
            return true;
        }
        if (args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.YELLOW + "Мир: " + worldName + " | генерация: " + (generationEnabled ? "включена" : "выключена"));
            return true;
        }
        if (args[0].equalsIgnoreCase("level")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Использование: /br level <0-10|0.1-10.1|exit>");
                return true;
            }
            int targetLevel;
            if (args[1].equalsIgnoreCase("exit")) targetLevel = 22;
            else {
                try {
                    String[] parts = args[1].split("\\.", -1);
                    int main = Integer.parseInt(parts[0]);
                    if (main < 0 || main > 10 || parts.length > 2 || (parts.length == 2 && !parts[1].equals("1"))) throw new NumberFormatException();
                    targetLevel = main * 2 + (parts.length == 2 ? 1 : 0);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Уровень должен быть от 0 до 10, например 3.1, или exit.");
                    return true;
                }
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Мир '" + worldName + "' не загружен.");
                return true;
            }
            Location target = findLevelLocation(world, targetLevel, sender instanceof Player ? ((Player) sender).getLocation() : new Location(world, originX, floorY + 1, originZ));
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Не удалось найти область уровня. Попробуй ещё раз.");
                return true;
            }
            int cx = target.getBlockX() >> 4, cz = target.getBlockZ() >> 4;
            builtChunks.remove(world.getName() + ":" + cx + ":" + cz);
            buildChunk(world, cx, cz);
            target.setY(floorY + 1);
            if (sender instanceof Player player) {
                player.teleport(target);
                player.sendMessage(ChatColor.GREEN + "Телепортировано на " + levelName(targetLevel) + ".");
            } else sender.sendMessage(ChatColor.GREEN + "Координаты уровня " + levelName(targetLevel) + ": " + target.getBlockX() + ", " + target.getBlockZ());
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Неизвестная команда. Используй /backrooms.");
        return true;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!generationEnabled || !event.getWorld().getName().equalsIgnoreCase(worldName)) return;
        String key = event.getWorld().getName() + ":" + event.getChunk().getX() + ":" + event.getChunk().getZ();
        if (builtChunks.add(key)) {
            int x = event.getChunk().getX(), z = event.getChunk().getZ();
            Bukkit.getScheduler().runTask(this, () -> buildChunk(event.getWorld(), x, z));
        }
    }

    private void buildAround(Location location) {
        World world = location.getWorld();
        if (world == null) return;
        int cx = location.getChunk().getX(), cz = location.getChunk().getZ();
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                String key = world.getName() + ":" + x + ":" + z;
                if (builtChunks.add(key)) {
                    final int bx = x, bz = z;
                    Bukkit.getScheduler().runTask(this, () -> buildChunk(world, bx, bz));
                }
            }
        }
    }

    private void buildChunk(World world, int chunkX, int chunkZ) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        int level = getLevelAt(baseX + 8, baseZ + 8);
        int theme = themeFor(level);
        Random random = new Random(hash(chunkX, chunkZ, level));
        Material wall = wallForTheme(theme);
        Material floor = floorForTheme(theme);
        Material ceiling = ceilingForTheme(theme);

        // Make the chunk interior into a room/corridor volume. Floor and ceiling are rebuilt,
        // while existing chunks are only touched once per plugin runtime.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.getBlockAt(baseX + x, floorY, baseZ + z).setType(floor, false);
                world.getBlockAt(baseX + x, ceilingY, baseZ + z).setType(ceiling, false);
                for (int y = floorY + 1; y < ceilingY; y++) {
                    world.getBlockAt(baseX + x, y, baseZ + z).setType(Material.AIR, false);
                }
            }
        }

        // Use a stable hash for each shared chunk boundary so openings align across chunks.
        for (int i = 0; i < 16; i++) {
            if (!edgeOpen(chunkX, chunkZ, 0) || i < 6 || i > 9) wallColumn(world, baseX + i, baseZ, wall);
            if (!edgeOpen(chunkX, chunkZ + 1, 0) || i < 6 || i > 9) wallColumn(world, baseX + i, baseZ + 15, wall);
            if (!edgeOpen(chunkX, chunkZ, 1) || i < 6 || i > 9) wallColumn(world, baseX, baseZ + i, wall);
            if (!edgeOpen(chunkX + 1, chunkZ, 1) || i < 6 || i > 9) wallColumn(world, baseX + 15, baseZ + i, wall);
        }

        // Occasional ceiling lights.
        for (int i = 0; i < 2 + random.nextInt(3); i++) {
            int lx = baseX + 2 + random.nextInt(12);
            int lz = baseZ + 2 + random.nextInt(12);
            world.getBlockAt(lx, ceilingY, lz).setType(Material.SEA_LANTERN, false);
        }

        // Add deterministic room variations: partial partitions, pillars, carpet patches and clutter.
        // Every chunk gets a different layout while keeping a walkable route through the room.
        decorateRoom(world, baseX, baseZ, level, random, wall, floor);

        // Portals spawn rarely in random generated sections, never at a fixed hub.
        // The seed is stable, so reloading a chunk does not move its portal.
        if (random.nextInt(20) == 0) {
            int portalX = baseX + 6 + random.nextInt(4);
            int portalZ = baseZ + 6 + random.nextInt(4);
            buildPortal(world, portalX, portalZ, level);
        }
        // Final pass: fill all newly placed containers after the entire room layout is complete.
        Bukkit.getScheduler().runTaskLater(this, () -> populateContainersInChunk(world, chunkX, chunkZ, level), 2L);
    }

    private void buildPortal(World world, int x, int z, int level) {
        // A 2-wide x 3-high portal made of purple stained glass inside a crying-obsidian frame.
        // It is built in the hub area; standing on the portal glass triggers a level transfer.
        int bottom = floorY + 1;
        for (int dx = 0; dx < 4; dx++) {
            world.getBlockAt(x + dx, bottom, z).setType(Material.CRYING_OBSIDIAN, false);
            world.getBlockAt(x + dx, bottom + 4, z).setType(Material.CRYING_OBSIDIAN, false);
        }
        for (int dy = 1; dy <= 3; dy++) {
            world.getBlockAt(x, bottom + dy, z).setType(Material.CRYING_OBSIDIAN, false);
            world.getBlockAt(x + 3, bottom + dy, z).setType(Material.CRYING_OBSIDIAN, false);
            for (int dx = 1; dx <= 2; dx++) {
                world.getBlockAt(x + dx, bottom + dy, z).setType(Material.PURPLE_STAINED_GLASS, false);
            }
        }
        world.getBlockAt(x + 1, bottom, z + 1).setType(Material.POLISHED_BLACKSTONE, false);
        world.getBlockAt(x + 2, bottom, z + 1).setType(Material.POLISHED_BLACKSTONE, false);
    }

    @EventHandler
    public void onPortalStep(PlayerMoveEvent event) {
        if (!generationEnabled || event.getTo() == null) return;
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equalsIgnoreCase(worldName)) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        // Portals are vertical glass panels, so check feet, head and the block below,
        // not only the block at the player's feet.
        Location to = event.getTo();
        // Stained glass is solid, so the player cannot enter the portal block itself.
        // Detect the portal surface in a small radius around the player instead.
        boolean nearPortal = false;
        int px = to.getBlockX(), py = to.getBlockY(), pz = to.getBlockZ();
        for (int ox = -1; ox <= 1 && !nearPortal; ox++) {
            for (int oy = 0; oy <= 1 && !nearPortal; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (player.getWorld().getBlockAt(px + ox, py + oy, pz + oz).getType() == Material.PURPLE_STAINED_GLASS) {
                        nearPortal = true;
                        break;
                    }
                }
            }
        }
        if (!nearPortal) return;

        long now = System.currentTimeMillis();
        Long last = portalCooldowns.get(player.getUniqueId());
        if (last != null && now - last < 1500) return;
        portalCooldowns.put(player.getUniqueId(), now);

        int currentLevel = getLevelAt(to.getBlockX(), to.getBlockZ());
        // Portals do not follow a fixed sequence. They lead to a random level;
        // the unique EXIT is a very rare destination.
        Random portalRandom = new Random(now ^ player.getUniqueId().getMostSignificantBits());
        int nextLevel = portalRandom.nextInt(100) == 0 ? 22 : portalRandom.nextInt(22);
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        // Each level occupies its own X zone. Arrive in a safe, freshly generated section;
        // portals themselves are discovered organically in random chunks.
        Location target = findLevelLocation(world, nextLevel, to);
        if (target == null) return;
        int destinationChunkX = target.getBlockX() >> 4;
        int destinationChunkZ = target.getBlockZ() >> 4;
        buildChunk(world, destinationChunkX, destinationChunkZ);
        target.getBlock().setType(Material.AIR, false);
        target.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        player.teleport(target);
        if (player.isOp()) player.sendMessage(ChatColor.LIGHT_PURPLE + "[Backrooms] Портал перенёс тебя на " + levelName(nextLevel) + ".");
    }

    private void decorateRoom(World world, int baseX, int baseZ, int level, Random random, Material wall, Material floor) {
        int theme = themeFor(level);
        Material accent = accentForTheme(theme);
        // One of several layout patterns per chunk. Keep a two-block-high passage clear.
        int pattern = random.nextInt(6);
        if (pattern == 0) {
            int x = baseX + 4 + random.nextInt(5);
            for (int z = baseZ + 3; z <= baseZ + 12; z++) {
                if (z >= baseZ + 7 && z <= baseZ + 9) continue;
                for (int y = floorY + 1; y < ceilingY; y++) world.getBlockAt(x, y, z).setType(wall, false);
            }
        } else if (pattern == 1) {
            for (int x = baseX + 3; x <= baseX + 12; x++) {
                if (x >= baseX + 7 && x <= baseX + 9) continue;
                for (int y = floorY + 1; y < ceilingY - 1; y++) world.getBlockAt(x, y, baseZ + 5).setType(wall, false);
            }
        } else if (pattern == 2) {
            for (int[] pos : new int[][]{{3,3},{12,3},{3,12},{12,12}}) {
                for (int y = floorY + 1; y < Math.min(ceilingY, floorY + 4); y++) world.getBlockAt(baseX + pos[0], y, baseZ + pos[1]).setType(accent, false);
            }
        } else if (pattern == 3) {
            Material carpet = theme == 2 || theme == 5 ? Material.RED_CARPET : (theme == 1 || theme == 3 ? Material.GRAY_CARPET : Material.YELLOW_CARPET);
            int x0 = baseX + 4 + random.nextInt(3), z0 = baseZ + 4 + random.nextInt(3);
            for (int x = x0; x < x0 + 4; x++) for (int z = z0; z < z0 + 4; z++) world.getBlockAt(x, floorY + 1, z).setType(carpet, false);
        } else if (pattern == 4) {
            // Low abandoned desk / cabinet cluster.
            for (int x = baseX + 5; x <= baseX + 7; x++) {
                world.getBlockAt(x, floorY + 1, baseZ + 10).setType(accent, false);
                world.getBlockAt(x, floorY + 2, baseZ + 10).setType(Material.OAK_SLAB, false);
            }
        } else {
            // A short L-shaped divider, with an opening to walk around it.
            for (int z = baseZ + 4; z <= baseZ + 9; z++) for (int y = floorY + 1; y < ceilingY; y++) world.getBlockAt(baseX + 10, y, z).setType(wall, false);
            for (int x = baseX + 6; x <= baseX + 10; x++) for (int y = floorY + 1; y < ceilingY; y++) world.getBlockAt(x, y, baseZ + 4).setType(wall, false);
            for (int y = floorY + 1; y < ceilingY; y++) world.getBlockAt(baseX + 10, y, baseZ + 7).setType(Material.AIR, false);
        }

        // Small ceiling fixtures and occasional floor stains add visual variety.
        if (random.nextBoolean()) {
            int lx = baseX + 5 + random.nextInt(6), lz = baseZ + 5 + random.nextInt(6);
            world.getBlockAt(lx, ceilingY, lz).setType(Material.SEA_LANTERN, false);
        }
        // Use one coherent room theme per section. Avoid layering random props over
        // shelves/desks, which previously made rooms look cluttered and accidental.
        if (random.nextInt(100) < 72) {
            int roomType = random.nextInt(3);
            if (roomType == 0) buildWarehouse(world, baseX, baseZ, level, random);
            else if (roomType == 1) buildOffice(world, baseX, baseZ, level, random);
            else buildLibrary(world, baseX, baseZ, level, random);
        } else {
            addRoomDecor(world, baseX, baseZ, level, random, accent);
            addLevelSpecificDecor(world, baseX, baseZ, level, random);
        }
    }

    private void addRoomDecor(World world, int baseX, int baseZ, int level, Random random, Material accent) {
        // A few props per room, with lots of empty rooms for atmosphere.
        int props = random.nextInt(100) < 78 ? 3 + random.nextInt(5) : random.nextInt(2);
        for (int i = 0; i < props; i++) {
            int x = baseX + 2 + random.nextInt(12);
            int z = baseZ + 2 + random.nextInt(12);
            if (!world.getBlockAt(x, floorY + 1, z).getType().isAir()) continue;
            int theme = themeFor(level);
            Material prop = switch (theme) {
                case 1 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.FURNACE;
                    case 3 -> Material.IRON_BLOCK; case 4 -> Material.CHAIN; case 5 -> Material.LIGHTNING_ROD;
                    default -> Material.CRAFTING_TABLE;
                };
                case 2 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.SOUL_LANTERN;
                    case 3 -> Material.CRYING_OBSIDIAN; case 4 -> Material.CHAIN; case 5 -> Material.FURNACE;
                    default -> Material.POLISHED_BLACKSTONE_BRICKS;
                };
                case 3 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.COPPER_BLOCK;
                    case 3 -> Material.LIGHTNING_ROD; case 4 -> Material.CUT_COPPER; case 5 -> Material.BLAST_FURNACE;
                    default -> Material.IRON_BARS;
                };
                case 4 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.MOSS_BLOCK;
                    case 3 -> Material.VINE; case 4 -> Material.MOSSY_COBBLESTONE; case 5 -> Material.LANTERN;
                    default -> Material.ROOTED_DIRT;
                };
                case 5 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.CRYING_OBSIDIAN;
                    case 3 -> Material.CHAIN; case 4 -> Material.SCULK; case 5 -> Material.SOUL_LANTERN;
                    default -> Material.POLISHED_BLACKSTONE_BRICKS;
                };
                default -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.FURNACE;
                    case 3 -> Material.CRAFTING_TABLE; case 4 -> Material.OAK_STAIRS; case 5 -> Material.FLOWER_POT;
                    default -> Material.BROWN_TERRACOTTA;
                };
            };
            world.getBlockAt(x, floorY + 1, z).setType(prop, false);
            if (prop == Material.CHEST || prop == Material.BARREL) fillLoot(world.getBlockAt(x, floorY + 1, z), level, random);
            if (prop == Material.IRON_BLOCK && random.nextBoolean())
                world.getBlockAt(x, floorY + 2, z).setType(Material.IRON_BARS, false);
        }
        // Rare small abandoned utility nook.
        if (random.nextInt(9) == 0) {
            int x = baseX + 5 + random.nextInt(4), z = baseZ + 5 + random.nextInt(4);
            int theme = themeFor(level);
            Material base = theme >= 2 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.BROWN_TERRACOTTA;
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 2; dz++)
                world.getBlockAt(x + dx, floorY + 1, z + dz).setType(base, false);
            world.getBlockAt(x + 1, floorY + 2, z).setType(Material.FURNACE, false);
            Material storage = random.nextBoolean() ? Material.CHEST : Material.BARREL;
            world.getBlockAt(x + 2, floorY + 2, z + 1).setType(storage, false);
            fillLoot(world.getBlockAt(x + 2, floorY + 2, z + 1), level, random);
        }
        // Very rare paintings on suitable wall blocks only.
        if (random.nextInt(18) == 0) {
            int x = baseX + 1 + random.nextInt(14), z = baseZ + 1 + random.nextInt(14);
            int y = floorY + 2;
            if (isWall(world.getBlockAt(x, y, z).getType())) {
                try {
                    Painting painting = world.spawn(new Location(world, x + 0.5, y + 0.1, z + 0.5), Painting.class);
                    painting.setFacingDirection(BlockFace.SOUTH, true);
                    painting.setArt(Art.KEBAB, true);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void addLevelSpecificDecor(World world, int bx, int bz, int level, Random random) {
        // Level-specific visual language; sublevels inherit their parent theme with variations.
        int sublevelVariant = level % 2;
        level = themeFor(level);
        int clusters = 2 + random.nextInt(4) + sublevelVariant;
        for (int i = 0; i < clusters; i++) {
            int x = bx + 2 + random.nextInt(12), z = bz + 2 + random.nextInt(12);
            if (!world.getBlockAt(x, floorY + 1, z).getType().isAir()) continue;
            if (level == 0) {
                if (random.nextBoolean()) {
                    world.getBlockAt(x, floorY + 1, z).setType(Material.OAK_PLANKS, false);
                    world.getBlockAt(x, floorY + 2, z).setType(Material.OAK_SLAB, false);
                    if (random.nextBoolean()) world.getBlockAt(x + 1, floorY + 1, z).setType(Material.BOOKSHELF, false);
                } else {
                    world.getBlockAt(x, floorY + 1, z).setType(Material.YELLOW_CARPET, false);
                    if (random.nextInt(4) == 0) world.getBlockAt(x, floorY + 2, z).setType(Material.FLOWER_POT, false);
                }
            } else if (level == 1 || level == 3) {
                Material[] industrial = level == 3
                    ? new Material[]{Material.CUT_COPPER, Material.COPPER_BLOCK, Material.LIGHTNING_ROD, Material.CHAIN, Material.BLAST_FURNACE, Material.BARREL}
                    : new Material[]{Material.IRON_BLOCK, Material.IRON_BARS, Material.CHAIN, Material.BLAST_FURNACE, Material.COPPER_BLOCK, Material.BARREL};
                Material m = industrial[random.nextInt(industrial.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (random.nextBoolean()) world.getBlockAt(x, floorY + 2, z).setType(Material.IRON_BARS, false);
            } else if (level == 4) {
                Material[] dark = {Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.VINE, Material.ROOTED_DIRT, Material.LANTERN, Material.BARREL};
                Material m = dark[random.nextInt(dark.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (m == Material.MOSS_BLOCK && random.nextBoolean()) world.getBlockAt(x, floorY + 2, z).setType(Material.VINE, false);
            } else if (level == 5) {
                Material[] dark = {Material.CRYING_OBSIDIAN, Material.POLISHED_BLACKSTONE_BRICKS, Material.CHAIN, Material.SOUL_LANTERN, Material.SCULK, Material.OBSIDIAN};
                Material m = dark[random.nextInt(dark.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (m == Material.SOUL_LANTERN) world.getBlockAt(x, floorY + 2, z).setType(Material.CHAIN, false);
            } else {
                Material[] dark = {Material.SOUL_SAND, Material.CRYING_OBSIDIAN, Material.POLISHED_BLACKSTONE_BRICKS, Material.CHAIN, Material.SOUL_LANTERN, Material.SCULK};
                Material m = dark[random.nextInt(dark.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (m == Material.SOUL_SAND && random.nextBoolean()) world.getBlockAt(x, floorY + 2, z).setType(Material.SOUL_LANTERN, false);
            }
        }
        // Level 1 and 2 get actual light sources, not unpowered redstone lamps.
        int lights = 2 + random.nextInt(3);
        for (int i = 0; i < lights; i++) {
            int x = bx + 2 + random.nextInt(12), z = bz + 2 + random.nextInt(12);
            Material light = (level == 2 || level == 5) ? Material.SOUL_LANTERN : (level == 3 || level == 4 ? Material.LANTERN : Material.SEA_LANTERN);
            world.getBlockAt(x, ceilingY, z).setType(light, false);
        }
    }

    private void buildWarehouse(World world, int bx, int bz, int level, Random random) {
        int levelIndex = level;
        level = themeFor(level);
        Material shelf = (level == 2 || level == 5) ? Material.POLISHED_BLACKSTONE_BRICKS : ((level == 1 || level == 3) ? Material.IRON_BLOCK : Material.SPRUCE_PLANKS);
        // Parallel low shelves with a clear aisle; storage is always interactive and stocked.
        for (int row = 0; row < 2; row++) {
            int z = bz + 4 + row * 7;
            for (int x = bx + 3; x <= bx + 12; x++) {
                if (x == bx + 7 || x == bx + 8) continue;
                world.getBlockAt(x, floorY + 1, z).setType(shelf, false);
                if (random.nextInt(100) < 65) {
                    Material storage = random.nextBoolean() ? Material.BARREL : Material.CHEST;
                    Block b = world.getBlockAt(x, floorY + 2, z);
                    b.setType(storage, false);
                    fillLoot(b, levelIndex, random);
                }
                if (random.nextInt(100) < 45) world.getBlockAt(x, floorY + 3, z).setType(level == 0 ? Material.BOOKSHELF : Material.IRON_BARS, false);
            }
        }
        if (random.nextBoolean()) {
            Block crate = world.getBlockAt(bx + 6 + random.nextInt(4), floorY + 1, bz + 8);
            crate.setType(Material.CHEST, false);
            fillLoot(crate, levelIndex, random);
        }
    }

    private void buildOffice(World world, int bx, int bz, int level, Random random) {
        int levelIndex = level;
        level = themeFor(level);
        Material desk = (level == 2 || level == 5) ? Material.DARK_OAK_PLANKS : Material.OAK_PLANKS;
        // Three workstations, chairs and filing cabinets. Keep the central route open.
        for (int i = 0; i < 3; i++) {
            int x = bx + 3 + i * 4;
            int z = bz + (i % 2 == 0 ? 4 : 11);
            world.getBlockAt(x, floorY + 1, z).setType(desk, false);
            world.getBlockAt(x + 1, floorY + 1, z).setType(Material.OAK_STAIRS, false);
            world.getBlockAt(x, floorY + 2, z).setType(random.nextBoolean() ? Material.LECTERN : Material.COMPOSTER, false);
            if (random.nextBoolean()) {
                Block filing = world.getBlockAt(x + 1, floorY + 1, z + 1);
                filing.setType(random.nextBoolean() ? Material.BARREL : Material.CHEST, false);
                fillLoot(filing, levelIndex, random);
            }
        }
        for (int x = bx + 3; x <= bx + 12; x += 3) {
            world.getBlockAt(x, floorY + 1, bz + 2).setType(level == 1 ? Material.IRON_BLOCK : Material.BOOKSHELF, false);
        }
    }

    private void buildLibrary(World world, int bx, int bz, int level, Random random) {
        int levelIndex = level;
        level = themeFor(level);
        Material shelf = Material.BOOKSHELF;
        // Two book stacks form a small library with a reading table and occasional supply chest.
        for (int z : new int[]{bz + 3, bz + 5, bz + 11, bz + 13}) {
            for (int x = bx + 3; x <= bx + 12; x++) {
                if (x == bx + 7 || x == bx + 8) continue;
                world.getBlockAt(x, floorY + 1, z).setType(shelf, false);
                if (random.nextBoolean()) world.getBlockAt(x, floorY + 2, z).setType(shelf, false);
            }
        }
        world.getBlockAt(bx + 7, floorY + 1, bz + 8).setType(Material.SPRUCE_PLANKS, false);
        world.getBlockAt(bx + 8, floorY + 1, bz + 8).setType(Material.SPRUCE_PLANKS, false);
        if (random.nextInt(100) < 70) {
            Block hidden = world.getBlockAt(bx + 10, floorY + 1, bz + 8);
            hidden.setType(random.nextBoolean() ? Material.CHEST : Material.BARREL, false);
            fillLoot(hidden, levelIndex, random);
        }
    }

    private void fillLoot(Block block, int level, Random random) {
        long lootSeed = random.nextLong();
        Bukkit.getScheduler().runTaskLater(this, () -> fillLootNow(block, level, new Random(lootSeed)), 2L);
    }

    private void populateContainersInChunk(World world, int chunkX, int chunkZ, int level) {
        int bx = chunkX << 4, bz = chunkZ << 4;
        for (int x = bx; x < bx + 16; x++) {
            for (int z = bz; z < bz + 16; z++) {
                for (int y = floorY + 1; y < ceilingY; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getState() instanceof org.bukkit.block.Container) {
                        fillLootNow(b, level, new Random(hash(x, z, level + 911)));
                    }
                }
            }
        }
    }

    private void fillLootNow(Block block, int level, Random random) {
        if (!(block.getState() instanceof org.bukkit.block.Container container)) return;
        TileState tile = container;
        org.bukkit.inventory.Inventory inv = container.getInventory();
        // Older versions could mark an empty chest as populated. Refill those legacy containers.
        if (tile.getPersistentDataContainer().has(lootMarker, PersistentDataType.BYTE)) {
            if (!inv.isEmpty()) return;
            tile.getPersistentDataContainer().remove(lootMarker);
        }
        if (!inv.isEmpty()) {
            // Preserve manually placed/server loot, but do not mark empty containers as populated.
            tile.getPersistentDataContainer().set(lootMarker, PersistentDataType.BYTE, (byte) 1);
            container.update(true, true);
            return;
        }
        inv.clear();
        // Always place several real resources, plus system/virus notes and occasional special books.
        int entries = 12 + random.nextInt(7);
        for (int i = 0; i < entries; i++) {
            ItemStack stack = makeLootItem(level, random);
            int slot = random.nextInt(inv.getSize());
            for (int tries = 0; tries < inv.getSize() && inv.getItem(slot) != null; tries++) slot = (slot + 1) % inv.getSize();
            if (inv.getItem(slot) == null) inv.setItem(slot, stack);
        }
        // Every container receives a guaranteed note, so there is always something to read.
        int noteSlot = random.nextInt(inv.getSize());
        for (int tries = 0; tries < inv.getSize() && inv.getItem(noteSlot) != null; tries++) noteSlot = (noteSlot + 1) % inv.getSize();
        if (inv.getItem(noteSlot) == null) inv.setItem(noteSlot, makeNote(level, random));
        tile.getPersistentDataContainer().set(lootMarker, PersistentDataType.BYTE, (byte) 1);
        container.update(true, true);
    }

    private ItemStack makeLootItem(int level, Random random) {
        int theme = themeFor(level);
        if (random.nextInt(100) < 14) return makeStoryBook(random.nextBoolean());
        if (random.nextInt(100) < 24) return makeNote(level, random);
        int lootLevel = level == 22 ? 11 : level / 2;
        Material[] pool = switch (lootLevel) {
            case 0 -> new Material[]{Material.BREAD, Material.COOKIE, Material.PAPER, Material.MAP, Material.COMPASS, Material.STRING, Material.LEATHER, Material.GLASS_BOTTLE, Material.COPPER_INGOT, Material.OAK_PLANKS};
            case 1 -> new Material[]{Material.BREAD, Material.PAPER, Material.BOOK, Material.IRON_NUGGET, Material.COPPER_INGOT, Material.STRING, Material.CARROT, Material.COOKIE, Material.FEATHER};
            case 2 -> new Material[]{Material.IRON_INGOT, Material.COPPER_INGOT, Material.REDSTONE, Material.RAIL, Material.COAL, Material.RAW_IRON, Material.FLINT, Material.LEVER, Material.REPEATER, Material.CHAIN};
            case 3 -> new Material[]{Material.REDSTONE, Material.IRON_INGOT, Material.COPPER_INGOT, Material.GUNPOWDER, Material.RAIL, Material.FURNACE, Material.IRON_NUGGET, Material.TORCH, Material.CHAIN};
            case 4 -> new Material[]{Material.BONE, Material.COAL, Material.STRING, Material.SPIDER_EYE, Material.ENDER_PEARL, Material.GOLD_NUGGET, Material.OBSIDIAN, Material.SCULK, Material.ARROW};
            case 5 -> new Material[]{Material.SOUL_SAND, Material.BONE, Material.GLOWSTONE_DUST, Material.BLACK_DYE, Material.CRYING_OBSIDIAN, Material.GOLD_INGOT, Material.ENDER_PEARL, Material.FERMENTED_SPIDER_EYE};
            case 6 -> new Material[]{Material.PAPER, Material.MAP, Material.COMPASS, Material.CLOCK, Material.WRITABLE_BOOK, Material.BOOK, Material.INK_SAC, Material.STRING};
            case 7 -> new Material[]{Material.IRON_INGOT, Material.COPPER_INGOT, Material.REDSTONE, Material.QUARTZ, Material.REPEATER, Material.COMPARATOR, Material.OBSERVER, Material.LEVER};
            case 8 -> new Material[]{Material.GOLD_NUGGET, Material.AMETHYST_SHARD, Material.GLOWSTONE_DUST, Material.SCULK, Material.ECHO_SHARD, Material.BONE, Material.ENDER_PEARL};
            case 9 -> new Material[]{Material.IRON_INGOT, Material.GOLD_INGOT, Material.REDSTONE, Material.ENDER_PEARL, Material.GHAST_TEAR, Material.PHANTOM_MEMBRANE, Material.CLOCK};
            case 10 -> new Material[]{Material.DIAMOND, Material.GOLD_INGOT, Material.OBSIDIAN, Material.ENDER_PEARL, Material.BLAZE_POWDER, Material.GLOWSTONE_DUST, Material.SCULK_CATALYST};
            default -> new Material[]{Material.DIAMOND, Material.ECHO_SHARD, Material.NETHERITE_SCRAP, Material.GOLD_INGOT, Material.CRYING_OBSIDIAN, Material.TOTEM_OF_UNDYING, Material.ENDER_PEARL, Material.NETHERITE_INGOT, Material.ENCHANTED_GOLDEN_APPLE};
        };
        Material material = pool[random.nextInt(pool.length)];
        ItemStack item = new ItemStack(material, 1 + random.nextInt(theme >= 4 ? 3 : 8));
        ItemMeta meta = item.getItemMeta();
        if (meta != null && (material == Material.PAPER || material == Material.BOOK || material == Material.WRITABLE_BOOK)) {
            meta.setDisplayName(random.nextBoolean() ? ChatColor.GRAY + "Обрывок системного журнала" : ChatColor.DARK_RED + "Заражённый фрагмент данных");
            meta.setLore(List.of(ChatColor.DARK_GRAY + "Найдено: " + levelName(level), ChatColor.GRAY + "Содержимое может быть повреждено."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeNote(int level, Random random) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            boolean virus = random.nextBoolean();
            meta.setDisplayName((virus ? ChatColor.DARK_RED : ChatColor.AQUA) + (virus ? "[ВИРУС] Перехваченная записка" : "[СИСТЕМА] Отчёт восстановления"));
            List<String> lines = new ArrayList<>();
            lines.add(ChatColor.GRAY + "Источник: " + (virus ? "UNKNOWN.EXE" : "BACKROOMS OS"));
            lines.add(ChatColor.DARK_GRAY + "Уровень: " + levelName(level));
            meta.setLore(lines);
            paper.setItemMeta(meta);
        }
        return paper;
    }

    private ItemStack makeStoryBook(boolean driver) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            if (driver) {
                meta.setTitle("ДРАЙВЕР // KERNEL");
                meta.setAuthor("BACKROOMS SYSTEM");
                meta.setPages(List.of(
                    "[СИСТЕМА: ДРАЙВЕР]

Устройство обнаружено: LIMINAL_NODE_07
Статус: нестабилен

Код восстановления:
BR-07-4F-A19C

Не запускайте модуль повторно без резервной копии.",
                    "// driver_patch.sys
LOAD SECTOR 07
CHECKSUM: 9A-00-BC-17

Если коридор повторяется трижды — драйвер не найден. Ищите терминал рядом с работающим светом."
                ));
            } else {
                meta.setTitle("АНТИВИРУС // QUARANTINE");
                meta.setAuthor("UNKNOWN PROCESS");
                meta.setPages(List.of(
                    "[АНТИВИРУС: КАРАНТИН]

Обнаружена угроза: MIRROR.VIR
Уровень риска: КРИТИЧЕСКИЙ

Ключ сигнатуры:
VIR-13-0X-NULL

Не доверяйте сообщениям, которые приходят из пустых комнат.",
                    "scan --deep /level/*
quarantine --isolate MIRROR.VIR
restore --safe-mode

ОШИБКА: процесс уже имеет права администратора.

Если вы читаете это, не подключайте неизвестные диски."
                ));
            }
            book.setItemMeta(meta);
        }
        return book;
    }

    private int themeFor(int levelIndex) {
        if (levelIndex == 22) return 5; // unique EXIT level uses the rarest/darkest theme
        return Math.max(0, Math.min(5, (levelIndex / 2) % 6));
    }
    private String levelName(int levelIndex) {
        if (levelIndex == 22) return "EXIT — единственный уровень-выход";
        int main = levelIndex / 2;
        return "Уровень " + main + (levelIndex % 2 == 1 ? ".1 (подуровень)" : "");
    }

    private Location findLevelLocation(World world, int wantedLevel, Location near) {
        Random random = new Random(System.nanoTime() ^ (near.getBlockX() * 31L) ^ near.getBlockZ());
        int nearRegionX = Math.floorDiv(near.getBlockX() - originX, levelSpacing);
        int nearRegionZ = Math.floorDiv(near.getBlockZ() - originZ, levelSpacing);
        for (int i = 0; i < 5000; i++) {
            int rx = nearRegionX + random.nextInt(401) - 200;
            int rz = nearRegionZ + random.nextInt(401) - 200;
            int sampleX = originX + rx * levelSpacing + levelSpacing / 2;
            int sampleZ = originZ + rz * levelSpacing + levelSpacing / 2;
            if (getLevelAt(sampleX, sampleZ) == wantedLevel) {
                return new Location(world, sampleX + random.nextInt(9) - 4 + 0.5, floorY + 1, sampleZ + random.nextInt(9) - 4 + 0.5, 0f, 0f);
            }
        }
        // Fallback: deterministic region search, especially useful for the rare EXIT.
        for (int radius = 0; radius < 2000; radius++) {
            int rx = nearRegionX + radius;
            int rz = nearRegionZ + (radius * 37 % 1999) - 999;
            int sampleX = originX + rx * levelSpacing + levelSpacing / 2;
            int sampleZ = originZ + rz * levelSpacing + levelSpacing / 2;
            if (getLevelAt(sampleX, sampleZ) == wantedLevel)
                return new Location(world, sampleX + 0.5, floorY + 1, sampleZ + 0.5, 0f, 0f);
        }
        return null;
    }
    private Material wallForTheme(int theme) {
        return switch (theme) { case 1 -> Material.SMOOTH_STONE; case 2 -> Material.DEEPSLATE_BRICKS; case 3 -> Material.BRICKS; case 4 -> Material.MOSSY_COBBLESTONE; case 5 -> Material.POLISHED_BLACKSTONE_BRICKS; default -> Material.YELLOW_TERRACOTTA; };
    }
    private Material floorForTheme(int theme) {
        return switch (theme) { case 1 -> Material.GRAY_CONCRETE; case 2 -> Material.POLISHED_DEEPSLATE; case 3 -> Material.POLISHED_BASALT; case 4 -> Material.MOSS_BLOCK; case 5 -> Material.BLACKSTONE; default -> Material.YELLOW_WOOL; };
    }
    private Material ceilingForTheme(int theme) {
        return switch (theme) { case 1 -> Material.SMOOTH_SANDSTONE; case 2 -> Material.DEEPSLATE_TILES; case 3 -> Material.IRON_BLOCK; case 4 -> Material.MOSSY_COBBLESTONE; case 5 -> Material.POLISHED_BLACKSTONE; default -> Material.SMOOTH_SANDSTONE; };
    }
    private Material accentForTheme(int theme) {
        return switch (theme) { case 1 -> Material.IRON_BLOCK; case 2 -> Material.POLISHED_BLACKSTONE_BRICKS; case 3 -> Material.CUT_COPPER; case 4 -> Material.MOSS_BLOCK; case 5 -> Material.CRYING_OBSIDIAN; default -> Material.BROWN_TERRACOTTA; };
    }

    private boolean edgeOpen(int boundaryChunkX, int boundaryChunkZ, int axis) {
        return Math.floorMod(hash(boundaryChunkX, boundaryChunkZ, axis), 5) != 0;
    }

    private void wallColumn(World world, int x, int z, Material wall) {
        for (int y = floorY + 1; y < ceilingY; y++) world.getBlockAt(x, y, z).setType(wall, false);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!generationEnabled || !player.getWorld().getName().equalsIgnoreCase(worldName)) return;
        Block block = event.getBlock();
        if (block.getY() <= floorY || block.getY() >= ceilingY) {
            event.setCancelled(true);
            return;
        }
        if (!isWall(block.getType())) return;

        // Only the block the player actually breaks is removed. If it is on an outer
        // wall, generate a room immediately behind that single opening.
        int localX = Math.floorMod(block.getX(), 16);
        int localZ = Math.floorMod(block.getZ(), 16);
        boolean edgeX = localX == 0 || localX == 15;
        boolean edgeZ = localZ == 0 || localZ == 15;
        // Interior dividers also reveal rooms; the generator infers the wall direction.
        final Block broken = block;
        Bukkit.getScheduler().runTask(this, () -> generateRoomBehind(broken));
        if (player.isOp()) player.sendMessage(ChatColor.GRAY + "[Backrooms] За проломом появляется новая комната. Разрушается только выбранный блок.");
    }

    private void generateRoomBehind(Block opening) {
        World world = opening.getWorld();
        int dx = 0, dz = 0;
        int localX = Math.floorMod(opening.getX(), 16);
        int localZ = Math.floorMod(opening.getZ(), 16);
        if (localX == 0) dx = -1;
        else if (localX == 15) dx = 1;
        else if (localZ == 0) dz = -1;
        else if (localZ == 15) dz = 1;
        else {
            boolean alongX = isWall(opening.getRelative(-1, 0, 0).getType()) || isWall(opening.getRelative(1, 0, 0).getType());
            boolean alongZ = isWall(opening.getRelative(0, 0, -1).getType()) || isWall(opening.getRelative(0, 0, 1).getType());
            if (alongX && !alongZ) dz = 1;
            else if (alongZ && !alongX) dx = 1;
            else if (alongX) dz = 1;
            else dx = 1;
        }

        int level = getLevelAt(opening.getX(), opening.getZ());
        Random random = new Random(hash(opening.getX(), opening.getZ(), level + 71));
        int theme = themeFor(level);
        Material floor = floorForTheme(theme);
        Material wall = wallForTheme(theme);
        Material ceiling = ceilingForTheme(theme);

        // Some discoveries lead to a room; others lead into a longer corridor network.
        boolean corridor = random.nextInt(100) < 42;
        int depthMax = corridor ? 26 + random.nextInt(13) : 13;
        int halfWidth = corridor ? (random.nextBoolean() ? 2 : 3) : 6;
        for (int depth = 1; depth <= depthMax; depth++) {
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int x = opening.getX() + dx * depth + (dz != 0 ? side : 0);
                int z = opening.getZ() + dz * depth + (dx != 0 ? side : 0);
                world.getBlockAt(x, floorY, z).setType(floor, false);
                world.getBlockAt(x, ceilingY, z).setType(ceiling, false);
                for (int y = floorY + 1; y < ceilingY; y++) {
                    boolean sideWall = Math.abs(side) == halfWidth;
                    boolean farWall = !corridor && depth == depthMax;
                    boolean nearWall = depth == 1 && side != 0;
                    if (sideWall || farWall || nearWall) world.getBlockAt(x, y, z).setType(wall, false);
                    else world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        if (corridor) {
            // Cross passages branch off the new corridor, creating links to further sections.
            int branches = 1 + random.nextInt(3);
            for (int b = 0; b < branches; b++) {
                int branchDepth = 5 + random.nextInt(Math.max(1, depthMax - 9));
                int sideDir = random.nextBoolean() ? 1 : -1;
                int startX = opening.getX() + dx * branchDepth;
                int startZ = opening.getZ() + dz * branchDepth;
                for (int length = 1; length <= 8 + random.nextInt(9); length++) {
                    for (int side = -1; side <= 1; side++) {
                        int x = startX + (dz != 0 ? sideDir * length : side);
                        int z = startZ + (dx != 0 ? sideDir * length : side);
                        world.getBlockAt(x, floorY, z).setType(floor, false);
                        world.getBlockAt(x, ceilingY, z).setType(ceiling, false);
                        for (int y = floorY + 1; y < ceilingY; y++) {
                            boolean edge = Math.abs(side) == 1;
                            boolean end = length == 8 + 0; // The corridor is intentionally left open at most ends.
                            if (edge && y < ceilingY - 1) world.getBlockAt(x, y, z).setType(wall, false);
                            else world.getBlockAt(x, y, z).setType(Material.AIR, false);
                        }
                    }
                }
            }
        } else {
            int cx = opening.getX() + dx * 7;
            int cz = opening.getZ() + dz * 7;
            if (random.nextBoolean()) {
                Material accent = theme >= 2 ? Material.CHAIN : Material.OAK_LOG;
                for (int y = floorY + 1; y < floorY + 4 && y < ceilingY; y++) world.getBlockAt(cx, y, cz).setType(accent, false);
            } else {
                world.getBlockAt(cx, floorY + 1, cz).setType(theme >= 2 ? Material.RED_CARPET : Material.BROWN_CARPET, false);
            }
            addRoomDecor(world, cx - 8, cz - 8, level, random, wall);
        }
        int lightX = opening.getX() + dx * Math.min(depthMax / 2, 8);
        int lightZ = opening.getZ() + dz * Math.min(depthMax / 2, 8);
        world.getBlockAt(lightX, ceilingY, lightZ).setType(Material.SEA_LANTERN, false);
    }

    private boolean isWall(Material m) {
        return m == Material.YELLOW_TERRACOTTA || m == Material.SMOOTH_STONE
                || m == Material.DEEPSLATE_BRICKS || m == Material.GRAY_CONCRETE
                || m == Material.BRICKS || m == Material.MOSSY_COBBLESTONE
                || m == Material.POLISHED_BLACKSTONE_BRICKS;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!generationEnabled || !p.getWorld().getName().equalsIgnoreCase(worldName)) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepLevel(true);

        // Save the player's normal-world return point. Prefer their bed spawn; fall back to
        // the location where they entered Backrooms if the bed is unavailable.
        Location bed = p.getBedSpawnLocation();
        if (bed != null && bed.getWorld() != null) {
            returnLocations.put(p.getUniqueId(), bed);
            returnWorlds.put(p.getUniqueId(), bed.getWorld().getName());
        } else {
            Location fallback = returnLocations.get(p.getUniqueId());
            if (fallback == null || fallback.getWorld() == null) {
                fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            returnLocations.put(p.getUniqueId(), fallback);
            returnWorlds.put(p.getUniqueId(), fallback.getWorld().getName());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Location target = returnLocations.get(p.getUniqueId());
        if (target != null && target.getWorld() != null) {
            event.setRespawnLocation(target);
            Bukkit.getScheduler().runTask(this, () -> {
                returnLocations.remove(p.getUniqueId());
                returnWorlds.remove(p.getUniqueId());
            });
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (event.getFrom().getName().equalsIgnoreCase(worldName)
                && !p.getWorld().getName().equalsIgnoreCase(worldName)) {
            // Track the last normal-world location for fallback when a bed isn't set.
            returnLocations.put(p.getUniqueId(), p.getLocation());
        }
    }

    private int getLevelAt(int x, int z) {
        int regionX = Math.floorDiv(x - originX, levelSpacing);
        int regionZ = Math.floorDiv(z - originZ, levelSpacing);
        // The EXIT is a single rare region in the procedural world, not part of the normal rotation.
        if (Math.floorMod(hash(regionX, regionZ, 77123), 1000) == 0) return 22;
        // A deterministic pseudo-random permutation distributes levels/sublevels across the map.
        return Math.floorMod((int) Math.floorMod(hash(regionX, regionZ, 991), 22), 22);
    }

    private long hash(int x, int z, int salt) {
        long v = ((long)x * 341873128712L) ^ ((long)z * 132897987541L) ^ ((long)salt * 42317861L);
        v ^= v >>> 13;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 16;
        return v;
    }
}
