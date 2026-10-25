package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("backrooms")).setExecutor(this);
        getLogger().info("Backrooms enabled. Configured world: " + worldName);
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

    @Override
    public void onDisable() {
        getConfig().set("generation-enabled", generationEnabled);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("backrooms.admin")) {
            sender.sendMessage(ChatColor.RED + "Все команды Backrooms доступны только операторам (OP).");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/br generate — включить генерацию в существующем мире");
            sender.sendMessage(ChatColor.YELLOW + "/br stop — выключить генерацию");
            sender.sendMessage(ChatColor.YELLOW + "/br level <0|1|2> — перейти на уровень");
            sender.sendMessage(ChatColor.YELLOW + "/br info — показать состояние");
            return true;
        }
        if (args[0].equalsIgnoreCase("generate")) {
            if (!sender.hasPermission("backrooms.admin")) {
                sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
                return true;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Мир '" + worldName + "' не загружен. Исправь world-name в plugins/Backrooms/config.yml.");
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
            sender.sendMessage(ChatColor.GRAY + "Новые секции будут строиться по мере загрузки чанков. Уровни: /br level 0, 1, 2.");
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
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Команда доступна игроку.");
                return true;
            }
            if (!generationEnabled) {
                p.sendMessage(ChatColor.RED + "Сначала администратор должен выполнить /br generate.");
                return true;
            }
            if (args.length < 2 || !Set.of("0", "1", "2").contains(args[1])) {
                p.sendMessage(ChatColor.YELLOW + "Использование: /br level <0|1|2>");
                return true;
            }
            World targetWorld = Bukkit.getWorld(worldName);
            if (targetWorld == null) {
                p.sendMessage(ChatColor.RED + "Мир не загружен.");
                return true;
            }
            int level = Integer.parseInt(args[1]);
            int x = originX + (level * levelSpacing);
            Location target = new Location(targetWorld, x + 0.5, floorY + 1, originZ + 0.5);
            buildAround(target);
            p.teleport(target);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Переход на уровень " + level + ".");
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Неизвестная команда. Используй /br.");
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
        Random random = new Random(hash(chunkX, chunkZ, level));
        Material wall = switch (level) {
            case 1 -> Material.SMOOTH_STONE;
            case 2 -> Material.DEEPSLATE_BRICKS;
            default -> Material.YELLOW_TERRACOTTA;
        };
        Material floor = switch (level) {
            case 1 -> Material.GRAY_CONCRETE;
            case 2 -> Material.POLISHED_DEEPSLATE;
            default -> Material.YELLOW_CARPET;
        };
        Material ceiling = level == 2 ? Material.DEEPSLATE_TILES : Material.SMOOTH_SANDSTONE;

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
            world.getBlockAt(lx, ceilingY, lz).setType(level == 2 ? Material.REDSTONE_LAMP : Material.SEA_LANTERN, false);
        }

        // Put a physical portal in the hub chunk for each level.
        int hubX = originX + level * levelSpacing;
        int hubChunkX = Math.floorDiv(hubX, 16);
        int hubChunkZ = Math.floorDiv(originZ, 16);
        if (chunkX == hubChunkX && chunkZ == hubChunkZ) {
            buildPortal(world, hubX + 2, originZ + 2, level);
        }
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

        Material atFeet = event.getTo().getBlock().getType();
        Material belowFeet = event.getTo().clone().subtract(0, 1, 0).getBlock().getType();
        if (atFeet != Material.PURPLE_STAINED_GLASS && belowFeet != Material.PURPLE_STAINED_GLASS) return;

        int currentLevel = getLevelAt(event.getTo().getBlockX(), event.getTo().getBlockZ());
        int nextLevel = (currentLevel + 1) % 3;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        int targetX = originX + nextLevel * levelSpacing + 5;
        int targetZ = originZ + 5;
        Location target = new Location(world, targetX + 0.5, floorY + 1, targetZ + 0.5);
        buildAround(target);
        buildChunk(world, Math.floorDiv(targetX, 16), Math.floorDiv(targetZ, 16));
        buildPortal(world, originX + nextLevel * levelSpacing + 2, originZ + 2, nextLevel);
        player.teleport(target);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "Портал перенёс тебя на уровень " + nextLevel + ".");
    }

    private boolean edgeOpen(int boundaryChunkX, int boundaryChunkZ, int axis) {
        return Math.floorMod(hash(boundaryChunkX, boundaryChunkZ, axis), 5) != 0;
    }

    private void wallColumn(World world, int x, int z, Material wall) {
        for (int y = floorY + 1; y < ceilingY; y++) world.getBlockAt(x, y, z).setType(wall, false);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!generationEnabled || !p.getWorld().getName().equalsIgnoreCase(worldName)) return;
        Block b = event.getBlock();
        if (b.getY() <= floorY || b.getY() >= ceilingY) {
            event.setCancelled(true);
            return;
        }
        Material type = b.getType();
        if (type == Material.YELLOW_TERRACOTTA || type == Material.SMOOTH_STONE
                || type == Material.DEEPSLATE_BRICKS || type == Material.GRAY_CONCRETE) {
            event.setCancelled(true);
            openWall(b);
            p.sendMessage(ChatColor.GRAY + "За стеной открылся проход.");
        }
    }

    private void openWall(Block b) {
        // Carve a short corridor through the wall and a room beyond it.
        int dx = 0, dz = 0;
        if (isWall(b.getRelative(1, 0, 0).getType()) || isWall(b.getRelative(-1, 0, 0).getType())) dz = 1;
        else dx = 1;
        for (int d = -1; d <= 9; d++) {
            for (int w = -2; w <= 2; w++) {
                for (int y = floorY + 1; y < ceilingY; y++) {
                    int x = b.getX() + dx * d + (dx == 0 ? w : 0);
                    int z = b.getZ() + dz * d + (dz == 0 ? w : 0);
                    b.getWorld().getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private boolean isWall(Material m) {
        return m == Material.YELLOW_TERRACOTTA || m == Material.SMOOTH_STONE
                || m == Material.DEEPSLATE_BRICKS || m == Material.GRAY_CONCRETE;
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
        int relative = x - originX;
        int level = Math.floorDiv(relative, levelSpacing);
        return Math.max(0, Math.min(2, level));
    }

    private long hash(int x, int z, int salt) {
        long v = ((long)x * 341873128712L) ^ ((long)z * 132897987541L) ^ ((long)salt * 42317861L);
        v ^= v >>> 13;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 16;
        return v;
    }
}
