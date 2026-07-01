package ru.trapkit.trap.manager;

import org.bukkit.Bukkit;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

        World world = target.getLocation().getWorld();
        int baseX = target.getLocation().getBlockX();
        int baseY = target.getLocation().getBlockY();
        int baseZ = target.getLocation().getBlockZ();
        String worldName = world.getName();

        Material cornerMat = matFromConfig("trap.materials.corner", Material.CHISELED_POLISHED_BLACKSTONE);
        Material wallMat   = matFromConfig("trap.materials.wall",   Material.POLISHED_BLACKSTONE);
        Material stairMat  = matFromConfig("trap.materials.stair",  Material.POLISHED_BLACKSTONE_STAIRS);

        boolean lightEnabled = plugin.getConfig().getBoolean("trap.light-source", true);
        int lightLevel       = Math.max(0, Math.min(15, plugin.getConfig().getInt("trap.light-level", 15)));

        int interiorSize  = Math.max(1, plugin.getConfig().getInt("trap.interior-size", 3));
        int wallThickness = Math.max(1, plugin.getConfig().getInt("trap.wall-thickness", 1));

        int interiorHalf = (interiorSize - 1) / 2;
        int outerHalf    = interiorHalf + wallThickness;

        int yMin         = -wallThickness;
        int yInteriorMax = interiorSize - 1;
        int yMax         = yInteriorMax + wallThickness;

        // Сохраняем состояние блоков как строки (getAsString) — это единственный
        // 100% надёжный способ, гарантирующий что ссылки не устареют и данные не изменятся.
        List<SavedBlock> saved = new ArrayList<>();

        for (int dx = -outerHalf; dx <= outerHalf; dx++) {
            for (int dy = yMin; dy <= yMax; dy++) {
                for (int dz = -outerHalf; dz <= outerHalf; dz++) {

                    int wx = baseX + dx;
                    int wy = baseY + dy;
                    int wz = baseZ + dz;

                    Block block = world.getBlockAt(wx, wy, wz);

                    // Сохраняем оригинал строкой — никаких живых ссылок
                    String originalData = block.getBlockData().getAsString();
                    saved.add(new SavedBlock(wx, wy, wz, worldName, originalData));

                    // Определяем что поставить
                    boolean isXEdge = (dx == -outerHalf || dx == outerHalf);
                    boolean isZEdge = (dz == -outerHalf || dz == outerHalf);
                    boolean isYEdge = (dy == yMin       || dy == yMax);

                    boolean isShell = isXEdge || isYEdge || isZEdge;

                    if (!isShell) {
                        // Внутренняя зона — чистим место для игрока
                        block.setType(Material.AIR, false);
                        continue;
                    }

                    boolean isCorner3D   = isXEdge && isYEdge && isZEdge;
                    boolean isVertPillar = isXEdge && isZEdge && !isYEdge;
                    boolean isHorizEdge  = isYEdge && (isXEdge ^ isZEdge);

                    if (isCorner3D || isVertPillar) {
                        block.setType(cornerMat, false);
                    } else if (isHorizEdge) {
                        block.setBlockData(buildStairData(stairMat, dy == yMin, dx, dz, isXEdge), false);
                    } else {
                        block.setType(wallMat, false);
                    }
                }
            }
        }

        // Невидимый источник света у потолка внутри зоны
        if (lightEnabled) {
            Block lb = world.getBlockAt(baseX, baseY + yInteriorMax, baseZ);
            BlockData ld = Material.LIGHT.createBlockData();
            if (ld instanceof Levelled lev) {
                lev.setLevel(Math.min(lightLevel, lev.getMaximumLevel()));
                ld = lev;
            }
            lb.setBlockData(ld, false);
        }

        int durationTicks = plugin.getConfig().getInt("trap.duration-seconds", 10) * 20;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> {
                    try {
                        restore(target.getUniqueId());
                    } catch (Throwable t) {
                        // Ловим даже Error, чтобы видеть полный стектрейс в логе
                        plugin.getLogger().severe("[TrapKit] Критическая ошибка при восстановлении ловушки: " + t);
                        t.printStackTrace();
                    }
                },
                Math.max(1L, durationTicks)
        );

        activeRooms.put(target.getUniqueId(), new ActiveRoom(saved, task));
    }

    private void restore(UUID uuid) {
        ActiveRoom room = activeRooms.remove(uuid);
        if (room == null) return;

        Set<Long> affectedChunks = new HashSet<>();

        for (SavedBlock sb : room.blocks) {
            World world = Bukkit.getWorld(sb.worldName());
            if (world == null) {
                plugin.getLogger().warning("[TrapKit] Мир не найден: " + sb.worldName());
                continue;
            }

            int chunkX = sb.x() >> 4;
            int chunkZ = sb.z() >> 4;

            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ, false);
            }

            try {
                // Восстанавливаем через строку — гарантированно точный тип и состояние блока
                BlockData restored = Bukkit.createBlockData(sb.dataString());
                world.getBlockAt(sb.x(), sb.y(), sb.z()).setBlockData(restored, false);
            } catch (Throwable t) {
                plugin.getLogger().warning("[TrapKit] Не удалось восстановить блок "
                        + sb.x() + " " + sb.y() + " " + sb.z()
                        + " (" + sb.dataString() + "): " + t.getMessage());
            }

            // Запоминаем чанки для обновления клиентов
            affectedChunks.add(chunkKey(chunkX, chunkZ));
        }

        // Принудительно обновляем чанки для всех игроков поблизости,
        // чтобы клиент точно получил актуальное состояние блоков
        for (long key : affectedChunks) {
            int cx = (int)(key >> 32);
            int cz = (int)(key & 0xFFFFFFFFL);
            World w = Bukkit.getWorld(room.blocks.get(0).worldName());
            if (w != null && w.isChunkLoaded(cx, cz)) {
                w.refreshChunk(cx, cz);
            }
        }
    }

    public void restoreAllImmediately() {
        for (UUID uuid : new ArrayList<>(activeRooms.keySet())) {
            ActiveRoom room = activeRooms.get(uuid);
            if (room != null && room.task() != null) {
                room.task().cancel();
            }
            try {
                restore(uuid);
            } catch (Throwable t) {
                plugin.getLogger().severe("[TrapKit] Ошибка при экстренном восстановлении: " + t);
            }
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private BlockData buildStairData(Material mat, boolean isFloor, int dx, int dz, boolean isXEdge) {
        BlockData data = mat.createBlockData();
        if (data instanceof Stairs stairs) {
            stairs.setHalf(isFloor ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
            stairs.setShape(Stairs.Shape.STRAIGHT);
            stairs.setFacing(outwardFace(dx, dz, isXEdge));
        }
        return data;
    }

    private BlockFace outwardFace(int dx, int dz, boolean isXEdge) {
        if (isXEdge) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private Material matFromConfig(String path, Material fallback) {
        String name = plugin.getConfig().getString(path, fallback.name());
        Material mat = Material.matchMaterial(name);
        return mat != null ? mat : fallback;
    }

    private record SavedBlock(int x, int y, int z, String worldName, String dataString) {}
    private record ActiveRoom(List<SavedBlock> blocks, BukkitTask task) {}
}
