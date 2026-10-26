package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Painting;
import org.bukkit.Art;
import org.bukkit.block.BlockFace;
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
    private final Map<UUID, Long> portalCooldowns = new HashMap<>();

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
            sender.sendMessage(ChatColor.YELLOW + "Прямой телепорт отключён: уровни находятся через физические порталы, размещённые в случайных секциях.");
            sender.sendMessage(ChatColor.GRAY + "Исследуй коридоры и комнаты, чтобы найти портал.");
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
            default -> Material.YELLOW_WOOL;
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
            world.getBlockAt(lx, ceilingY, lz).setType(Material.SEA_LANTERN, false);
        }

        // Add deterministic room variations: partial partitions, pillars, carpet patches and clutter.
        // Every chunk gets a different layout while keeping a walkable route through the room.
        decorateRoom(world, baseX, baseZ, level, random, wall, floor);

        // Portals spawn rarely in random generated sections, never at a fixed hub.
        // The seed is stable, so reloading a chunk does not move its portal.
        if (random.nextInt(28) == 0) {
            int portalX = baseX + 6 + random.nextInt(4);
            int portalZ = baseZ + 6 + random.nextInt(4);
            buildPortal(world, portalX, portalZ, level);
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
        int nextLevel = (currentLevel + 1) % 3;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        // Each level occupies its own X zone. Arrive in a safe, freshly generated section;
        // portals themselves are discovered organically in random chunks.
        int targetX = originX + nextLevel * levelSpacing + 32 + new Random().nextInt(256);
        int targetZ = originZ + 32 + new Random().nextInt(256);
        Location target = new Location(world, targetX + 0.5, floorY + 1, targetZ + 0.5, 0f, 0f);
        int destinationChunkX = target.getBlockX() >> 4;
        int destinationChunkZ = target.getBlockZ() >> 4;
        buildChunk(world, destinationChunkX, destinationChunkZ);
        target.getBlock().setType(Material.AIR, false);
        target.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        player.teleport(target);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "Портал перенёс тебя на уровень " + nextLevel + ".");
    }

    private void decorateRoom(World world, int baseX, int baseZ, int level, Random random, Material wall, Material floor) {
        Material accent = switch (level) {
            case 1 -> Material.IRON_BLOCK;
            case 2 -> Material.POLISHED_BLACKSTONE_BRICKS;
            default -> Material.BROWN_TERRACOTTA;
        };
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
            Material carpet = level == 2 ? Material.RED_CARPET : (level == 1 ? Material.GRAY_CARPET : Material.YELLOW_CARPET);
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
            Material prop = switch (level) {
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
            Material base = level == 2 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.BROWN_TERRACOTTA;
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
        // Level-specific visual language: office clutter on 0, industrial equipment on 1,
        // dark abandoned infrastructure on 2. Decoration is dense but leaves a route open.
        int clusters = 2 + random.nextInt(4);
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
            } else if (level == 1) {
                Material[] industrial = {Material.IRON_BLOCK, Material.IRON_BARS, Material.CHAIN, Material.BLAST_FURNACE, Material.COPPER_BLOCK, Material.BARREL};
                Material m = industrial[random.nextInt(industrial.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (random.nextBoolean()) world.getBlockAt(x, floorY + 2, z).setType(Material.IRON_BARS, false);
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
            Material light = level == 2 ? Material.SOUL_LANTERN : (level == 1 ? Material.SEA_LANTERN : Material.SEA_LANTERN);
            world.getBlockAt(x, ceilingY, z).setType(light, false);
        }
    }

    private void buildWarehouse(World world, int bx, int bz, int level, Random random) {
        Material shelf = level == 2 ? Material.POLISHED_BLACKSTONE_BRICKS : (level == 1 ? Material.IRON_BLOCK : Material.SPRUCE_PLANKS);
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
                    fillLoot(b, level, random);
                }
                if (random.nextInt(100) < 45) world.getBlockAt(x, floorY + 3, z).setType(level == 0 ? Material.BOOKSHELF : Material.IRON_BARS, false);
            }
        }
        if (random.nextBoolean()) {
            Block crate = world.getBlockAt(bx + 6 + random.nextInt(4), floorY + 1, bz + 8);
            crate.setType(Material.CHEST, false);
            fillLoot(crate, level, random);
        }
    }

    private void buildOffice(World world, int bx, int bz, int level, Random random) {
        Material desk = level == 2 ? Material.DARK_OAK_PLANKS : Material.OAK_PLANKS;
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
                fillLoot(filing, level, random);
            }
        }
        for (int x = bx + 3; x <= bx + 12; x += 3) {
            world.getBlockAt(x, floorY + 1, bz + 2).setType(level == 1 ? Material.IRON_BLOCK : Material.BOOKSHELF, false);
        }
    }

    private void buildLibrary(World world, int bx, int bz, int level, Random random) {
        Material shelf = level == 2 ? Material.BOOKSHELF : Material.BOOKSHELF;
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
            fillLoot(hidden, level, random);
        }
    }

    private void fillLoot(Block block, int level, Random random) {
        // Defer inventory population until after all block placement for this room finishes.
        // This prevents later generation updates from replacing the container's block state
        // and leaving visually present chests/barrels empty.
        long lootSeed = random.nextLong();
        Bukkit.getScheduler().runTask(this, () -> fillLootNow(block, level, new Random(lootSeed)));
    }

    private void fillLootNow(Block block, int level, Random random) {
        org.bukkit.block.Container container;
        if (block.getState() instanceof org.bukkit.block.Container c) {
            container = c;
        } else {
            return;
        }
        org.bukkit.inventory.Inventory inv = container.getInventory();
        inv.clear();
        // Fill a meaningful portion of the container, not just one or two random slots.
        int entries = 5 + random.nextInt(7);
        for (int i = 0; i < entries; i++) {
            Material item;
            int amount = 1;
            if (level == 0) {
                Material[] pool = {Material.PAPER, Material.MAP, Material.COMPASS, Material.COOKIE, Material.BREAD,
                    Material.BROWN_MUSHROOM, Material.STRING, Material.LEATHER, Material.GLASS_BOTTLE, Material.YELLOW_DYE,
                    Material.ROTTEN_FLESH, Material.STICK, Material.FEATHER, Material.BOOK, Material.WRITABLE_BOOK,
                    Material.COOKED_BEEF, Material.CARROT, Material.POTATO, Material.OAK_PLANKS, Material.COPPER_INGOT};
                item = pool[random.nextInt(pool.length)]; amount = 1 + random.nextInt(5);
            } else if (level == 1) {
                Material[] pool = {Material.IRON_INGOT, Material.COPPER_INGOT, Material.REDSTONE, Material.RAIL,
                    Material.COAL, Material.RAW_IRON, Material.FLINT, Material.BREAD, Material.FURNACE,
                    Material.IRON_NUGGET, Material.STRING, Material.GUNPOWDER, Material.CHAIN, Material.LEVER,
                    Material.REPEATER, Material.TORCH, Material.COOKED_BEEF, Material.COPPER_BLOCK};
                item = pool[random.nextInt(pool.length)]; amount = 1 + random.nextInt(6);
            } else {
                Material[] pool = {Material.COAL, Material.SOUL_SAND, Material.BONE, Material.STRING, Material.SPIDER_EYE,
                    Material.ENDER_PEARL, Material.GOLD_NUGGET, Material.OBSIDIAN, Material.SCULK, Material.ROTTEN_FLESH,
                    Material.POTION, Material.GLOWSTONE_DUST, Material.BONE_MEAL, Material.ARROW, Material.FERMENTED_SPIDER_EYE,
                    Material.BLACK_DYE, Material.CRYING_OBSIDIAN, Material.GOLD_INGOT};
                item = pool[random.nextInt(pool.length)]; amount = 1 + random.nextInt(4);
            }
            org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(item, amount);
            int slot;
            int attempts = 0;
            do {
                slot = random.nextInt(inv.getSize());
                attempts++;
            } while (inv.getItem(slot) != null && attempts < inv.getSize() * 2);
            if (inv.getItem(slot) == null) inv.setItem(slot, stack);
        }
        container.update(true, false);
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
        player.sendMessage(ChatColor.GRAY + "За проломом появляется новая комната. Разрушается только выбранный блок.");
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
        Material floor = switch (level) {
            case 1 -> Material.GRAY_CONCRETE;
            case 2 -> Material.POLISHED_DEEPSLATE;
            default -> Material.YELLOW_WOOL;
        };
        Material wall = switch (level) {
            case 1 -> Material.SMOOTH_STONE;
            case 2 -> Material.DEEPSLATE_BRICKS;
            default -> Material.YELLOW_TERRACOTTA;
        };
        Material ceiling = level == 2 ? Material.DEEPSLATE_TILES : Material.SMOOTH_SANDSTONE;

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
                Material accent = level == 2 ? Material.CHAIN : Material.OAK_LOG;
                for (int y = floorY + 1; y < floorY + 4 && y < ceilingY; y++) world.getBlockAt(cx, y, cz).setType(accent, false);
            } else {
                world.getBlockAt(cx, floorY + 1, cz).setType(level == 2 ? Material.RED_CARPET : Material.BROWN_CARPET, false);
            }
            addRoomDecor(world, cx - 8, cz - 8, level, random, wall);
        }
        int lightX = opening.getX() + dx * Math.min(depthMax / 2, 8);
        int lightZ = opening.getZ() + dz * Math.min(depthMax / 2, 8);
        world.getBlockAt(lightX, ceilingY, lightZ).setType(Material.SEA_LANTERN, false);
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
