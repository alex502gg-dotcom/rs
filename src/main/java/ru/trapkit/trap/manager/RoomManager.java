package ru.trapkit.trap.manager;

import org.bukkit.Bukkit;
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

/**
 * Отвечает за постройку временной комнаты вокруг игрока и
 * её точное восстановление (блок-в-блок) по истечении времени —
 * мир не остаётся деформированным ни на один блок.
 *
 * Комната состоит из полой внутренней области (по умолчанию 3x3x3,
 * где реально стоит игрок) и обрамляющей её оболочки толщиной в
 * один блок (по умолчанию), оформленной декоративными материалами.
 * Внутрь добавляется невидимый источник света (блок LIGHT), чтобы
 * внутри комнаты не было кромешной тьмы.
 */
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
        if (isTrapped(target)) {
            return;
        }

        Block feet = target.getLocation().getBlock();
        World world = feet.getWorld();

        Material cornerMat = matFromConfig("trap.materials.corner", Material.CHISELED_POLISHED_BLACKSTONE);
        Material wallMat = matFromConfig("trap.materials.wall", Material.POLISHED_BLACKSTONE);
        Material stairMat = matFromConfig("trap.materials.stair", Material.POLISHED_BLACKSTONE_STAIRS);

        boolean lightEnabled = plugin.getConfig().getBoolean("trap.light-source", true);
        int lightLevel = Math.max(0, Math.min(15, plugin.getConfig().getInt("trap.light-level", 15)));

        int interiorSize = Math.max(1, plugin.getConfig().getInt("trap.interior-size", 3));
        int wallThickness = Math.max(1, plugin.getConfig().getInt("trap.wall-thickness", 1));

        // Внутренняя полая зона: interiorHalf блоков в каждую сторону по X/Z от игрока.
        int interiorHalf = (interiorSize - 1) / 2;
        int outerHalf = interiorHalf + wallThickness;

        // По вертикали ноль (dy = 0) — это блок, где сейчас стоят ноги игрока,
        // поэтому пол кладём ниже его ног, а не на их уровне.
        int yMin = -wallThickness;
        int yInteriorMax = interiorSize - 1;
        int yMax = yInteriorMax + wallThickness;

        int baseX = feet.getX();
        int baseY = feet.getY();
        int baseZ = feet.getZ();

        List<SavedBlock> saved = new ArrayList<>();

        for (int dx = -outerHalf; dx <= outerHalf; dx++) {
            for (int dy = yMin; dy <= yMax; dy++) {
                for (int dz = -outerHalf; dz <= outerHalf; dz++) {

                    boolean isXEdge = (dx == -outerHalf || dx == outerHalf);
                    boolean isZEdge = (dz == -outerHalf || dz == outerHalf);
                    boolean isYEdge = (dy == yMin || dy == yMax);

                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    saved.add(new SavedBlock(block.getLocation(), block.getBlockData()));

                    if (!isXEdge && !isYEdge && !isZEdge) {
                        // Внутренняя полая зона — полностью освобождаем место
                        // (на случай если комната ставится внутри земли/стены).
                        safeSet(block, Material.AIR.createBlockData());
                        continue;
                    }

                    boolean isCorner3D = isXEdge && isYEdge && isZEdge;
                    boolean isVerticalPillar = isXEdge && isZEdge && !isYEdge;
                    boolean isFloorCeilingEdge = isYEdge && (isXEdge ^ isZEdge);

                    BlockData newData;
                    if (isCorner3D || isVerticalPillar) {
                        newData = cornerMat.createBlockData();
                    } else if (isFloorCeilingEdge) {
                        newData = buildStairData(stairMat, dy == yMin, dx, dz, isXEdge);
                    } else {
                        newData = wallMat.createBlockData();
                    }

                    safeSet(block, newData);
                }
            }
        }

        if (lightEnabled) {
            // Невидимый источник света у потолка внутри полой зоны —
            // у блока LIGHT нет модели и хитбокса, комната по-прежнему
            // выглядит как сплошная конструкция, но внутри видно.
            Block lightBlock = world.getBlockAt(baseX, baseY + yInteriorMax, baseZ);
            BlockData lightData = Material.LIGHT.createBlockData();
            if (lightData instanceof Levelled levelled) {
                levelled.setLevel(Math.min(lightLevel, levelled.getMaximumLevel()));
                lightData = levelled;
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
        if (isXEdge) {
            return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void restore(UUID uuid) {
        ActiveRoom room = activeRooms.remove(uuid);
        if (room == null) {
            return;
        }
        // Восстанавливаем в обратном порядке, блок-в-блок,
        // без физических обновлений, чтобы исчезновение комнаты
        // было мгновенным и не вызывало побочных эффектов.
        // Каждый блок оборачиваем в try/catch (см. safeSet) —
        // ошибка на одном блоке больше не прерывает откат остальных.
        for (int i = room.blocks.size() - 1; i >= 0; i--) {
            SavedBlock sb = room.blocks.get(i);
            Block block = sb.location.getBlock();
            safeSet(block, sb.data);
        }
    }

    /**
     * Безопасно применяет BlockData к блоку. Любая ошибка (например,
     * блок временно недоступен из-за другого плагина/чанка) логируется,
     * но не прерывает выполнение всей операции постройки/восстановления.
     */
    private void safeSet(Block block, BlockData data) {
        try {
            block.setBlockData(data, false);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось изменить блок на " + formatLocation(block) + ": " + ex.getMessage());
        }
    }

    private String formatLocation(Block block) {
        return block.getWorld().getName() + " (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")";
    }

    /**
     * Принудительно и немедленно восстанавливает все активные комнаты
     * (используется при отключении плагина/перезапуске сервера).
     */
    public void restoreAllImmediately() {
        for (UUID uuid : new ArrayList<>(activeRooms.keySet())) {
            ActiveRoom room = activeRooms.get(uuid);
            if (room != null && room.task != null) {
                room.task.cancel();
            }
            restore(uuid);
        }
    }

    private Material matFromConfig(String path, Material fallback) {
        String name = plugin.getConfig().getString(path, fallback.name());
        Material material = Material.matchMaterial(name);
        return material != null ? material : fallback;
    }

    private record SavedBlock(Location location, BlockData data) {
    }

    private record ActiveRoom(List<SavedBlock> blocks, BukkitTask task) {
    }
}
