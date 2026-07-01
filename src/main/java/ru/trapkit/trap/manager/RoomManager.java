package ru.trapkit.trap.manager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.trapkit.trap.TrapPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class RoomManager {

    private final TrapPlugin plugin;
    private final Map<UUID, ActiveRoom> activeRooms = new ConcurrentHashMap<>();

    public RoomManager(TrapPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isTrapped(Player player) {
        return activeRooms.containsKey(player.getUniqueId());
    }

    public void trapPlayer(Player target) {
        if (isTrapped(target)) return;

        // Сохраняем все данные ДО любых изменений мира
        Location origin = target.getLocation().clone();
        World world = origin.getWorld();

        Material cornerMat  = matFromConfig("trap.materials.corner", Material.CHISELED_POLISHED_BLACKSTONE);
        Material wallMat    = matFromConfig("trap.materials.wall",   Material.POLISHED_BLACKSTONE);
        Material stairMat   = matFromConfig("trap.materials.stair",  Material.POLISHED_BLACKSTONE_STAIRS);

        boolean lightEnabled = plugin.getConfig().getBoolean("trap.light-source", true);
        int lightLevel       = Math.max(0, Math.min(15, plugin.getConfig().getInt("trap.light-level", 15)));

        int interiorSize  = Math.max(1, plugin.getConfig().getInt("trap.interior-size",   3));
        int wallThickness = Math.max(1, plugin.getConfig().getInt("trap.wall-thickness",  1));

        int interiorHalf = (interiorSize - 1) / 2;
        int outerHalf    = interiorHalf + wallThickness;

        // dy=0 — ноги игрока, пол кладём под них
        int yMin         = -wallThickness;
        int yInteriorMax = interiorSize - 1;
        int yMax         = yInteriorMax + wallThickness;

        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();

        List<SavedBlock> saved = new ArrayList<>();

        // ── Сохраняем оригинальные данные ВСЕХ блоков, которые затронет ловушка ──
        for (int dx = -outerHalf; dx <= outerHalf; dx++) {
            for (int dy = yMin; dy <= yMax; dy++) {
                for (int dz = -outerHalf; dz <= outerHalf; dz++) {
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    // getBlockData() гарантированно возвращает snapshot, не ссылку
                    saved.add(new SavedBlock(
                            baseX + dx, baseY + dy, baseZ + dz, world,
                            block.getBlockData().clone()
                    ));
                }
            }
        }

        // ── Строим ловушку ──
        for (int dx = -outerHalf; dx <= outerHalf; dx++) {
            for (int dy = yMin; dy <= yMax; dy++) {
                for (int dz = -outerHalf; dz <= outerHalf; dz++) {
                    boolean isXEdge = (dx == -outerHalf || dx == outerHalf);
                    boolean isZEdge = (dz == -outerHalf || dz == outerHalf);
                    boolean isYEdge = (dy == yMin       || dy == yMax);

                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);

                    // Полая внутренняя зона — чистим даже если там была земля/камень
                    if (!isXEdge && !isYEdge && !isZEdge) {
                        safeSet(block, Material.AIR.createBlockData());
                        continue;
                    }

                    boolean isCorner3D     = isXEdge && isYEdge && isZEdge;
                    boolean isVertPillar   = isXEdge && isZEdge && !isYEdge;
                    boolean isHorizEdge    = isYEdge && (isXEdge ^ isZEdge);

                    BlockData newData;
                    if (isCorner3D || isVertPillar) {
                        newData = cornerMat.createBlockData();
                    } else if (isHorizEdge) {
                        newData = buildStairData(stairMat, dy == yMin, dx, dz, isXEdge);
                    } else {
                        newData = wallMat.createBlockData();
                    }

                    safeSet(block, newData);
                }
            }
        }

        // Невидимый блок света у потолка (нет модели, нет хитбокса)
        if (lightEnabled) {
            Block lightBlock = world.getBlockAt(baseX, baseY + yInteriorMax, baseZ);
            BlockData lightData = Material.LIGHT.createBlockData();
            if (lightData instanceof Levelled lev) {
                lev.setLevel(Math.min(lightLevel, lev.getMaximumLevel()));
                lightData = lev;
            }
            safeSet(lightBlock, lightData);
        }

        int durationTicks = plugin.getConfig().getInt("trap.duration-seconds", 10) * 20;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> restore(target.getUniqueId()),
                Math.max(1L, durationTicks)
        );

        activeRooms.put(target.getUniqueId(), new ActiveRoom(saved, task));
    }

    // ── Восстановление ──────────────────────────────────────────────────────────

    private void restore(UUID uuid) {
        ActiveRoom room = activeRooms.remove(uuid);
        if (room == null) return;

        plugin.getLogger().info("[TrapKit] Восстанавливаю " + room.blocks.size() + " блоков для " + uuid);

        List<SavedBlock> failed = new ArrayList<>();

        for (SavedBlock sb : room.blocks) {
            if (!restoreBlock(sb)) {
                failed.add(sb);
            }
        }

        // Второй проход для блоков, которые не восстановились с первого раза
        if (!failed.isEmpty()) {
            plugin.getLogger().warning("[TrapKit] Второй проход для " + failed.size() + " блоков...");
            for (SavedBlock sb : failed) {
                if (!restoreBlock(sb)) {
                    plugin.getLogger().severe("[TrapKit] Не удалось восстановить блок: " +
                            sb.worldName() + " " + sb.x() + " " + sb.y() + " " + sb.z() +
                            " (оригинал: " + sb.data().getAsString() + ")");
                }
            }
        }
    }

    /**
     * Восстанавливает один блок. Перед восстановлением убеждается,
     * что нужный чанк загружен — это главная причина "зависших" блоков.
     * @return true если успешно
     */
    private boolean restoreBlock(SavedBlock sb) {
        try {
            World world = Bukkit.getWorld(sb.worldName());
            if (world == null) {
                plugin.getLogger().warning("[TrapKit] Мир не найден: " + sb.worldName());
                return false;
            }

            // Принудительно загружаем чанк, если он выгружен
            int chunkX = sb.x() >> 4;
            int chunkZ = sb.z() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ, false);
            }

            Block block = world.getBlockAt(sb.x(), sb.y(), sb.z());
            block.setBlockData(sb.data().clone(), false);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[TrapKit] Ошибка при восстановлении блока: " + ex.getMessage(), ex);
            return false;
        }
    }

    public void restoreAllImmediately() {
        for (UUID uuid : new ArrayList<>(activeRooms.keySet())) {
            ActiveRoom room = activeRooms.get(uuid);
            if (room != null && room.task() != null) {
                room.task().cancel();
            }
            restore(uuid);
        }
    }

    // ── Вспомогательные методы ──────────────────────────────────────────────────

    private BlockData buildStairData(Material stairMat, boolean isFloor, int dx, int dz, boolean isXEdge) {
        BlockData data = stairMat.createBlockData();
        if (data instanceof Stairs stairs) {
            stairs.setHalf(isFloor ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
            stairs.setShape(Stairs.Shape.STRAIGHT);
            stairs.setFacing(outwardFace(dx, dz, isXEdge));
            return stairs;
        }
        return data;
    }

    private BlockFace outwardFace(int dx, int dz, boolean isXEdge) {
        if (isXEdge) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void safeSet(Block block, BlockData data) {
        try {
            block.setBlockData(data, false);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[TrapKit] Не удалось поставить блок: " + block.getLocation() + ": " + ex.getMessage());
        }
    }

    private Material matFromConfig(String path, Material fallback) {
        String name = plugin.getConfig().getString(path, fallback.name());
        Material mat = Material.matchMaterial(name);
        return mat != null ? mat : fallback;
    }

    // ── Records ─────────────────────────────────────────────────────────────────

    /**
     * Хранит координаты отдельно от World-ссылки (ищем мир по имени в момент
     * восстановления) — это защищает от проблем с устаревшими World-объектами.
     */
    private record SavedBlock(int x, int y, int z, World world, BlockData data) {
        String worldName() { return world.getName(); }
    }

    private record ActiveRoom(List<SavedBlock> blocks, BukkitTask task) {}
}
