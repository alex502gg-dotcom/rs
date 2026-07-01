package ru.trapkit.trap;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import ru.trapkit.trap.commands.TrapCommand;
import ru.trapkit.trap.listeners.TrapUseListener;
import ru.trapkit.trap.manager.RoomManager;
import ru.trapkit.trap.manager.TrapItemManager;

public final class TrapPlugin extends JavaPlugin {

    private static TrapPlugin instance;

    private NamespacedKey trapItemKey;
    private TrapItemManager trapItemManager;
    private RoomManager roomManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.trapItemKey = new NamespacedKey(this, "trap_item");
        this.trapItemManager = new TrapItemManager(this);
        this.roomManager = new RoomManager(this);

        TrapCommand trapCommand = new TrapCommand(this);
        var pluginCommand = getCommand("trap");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(trapCommand);
            pluginCommand.setTabCompleter(trapCommand);
        }

        getServer().getPluginManager().registerEvents(new TrapUseListener(this), this);

        getLogger().info("TrapKit включен.");
    }

    @Override
    public void onDisable() {
        if (roomManager != null) {
            // На случай выключения/перезапуска сервера —
            // моментально возвращаем все активные комнаты на место,
            // чтобы не оставить никаких следов в мире.
            roomManager.restoreAllImmediately();
        }
        getLogger().info("TrapKit выключен.");
    }

    public static TrapPlugin getInstance() {
        return instance;
    }

    public NamespacedKey getTrapItemKey() {
        return trapItemKey;
    }

    public TrapItemManager getTrapItemManager() {
        return trapItemManager;
    }

    public RoomManager getRoomManager() {
        return roomManager;
    }
}
