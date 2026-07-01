package ru.trapkit.trap.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.trapkit.trap.TrapPlugin;

import java.util.ArrayList;
import java.util.List;

public class TrapItemManager {

    private final TrapPlugin plugin;

    public TrapItemManager(TrapPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack createTrapItem(int amount) {
        String materialName = plugin.getConfig().getString("item.material", "TRIPWIRE_HOOK");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.TRIPWIRE_HOOK;
        }

        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String rawName = plugin.getConfig().getString("item.name", "&c&lЛовушка");
            meta.displayName(legacyToComponent(rawName).decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList("item.lore")) {
                lore.add(legacyToComponent(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);

            meta.getPersistentDataContainer().set(plugin.getTrapItemKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }

        return item;
    }

    public boolean isTrapItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte value = meta.getPersistentDataContainer().get(plugin.getTrapItemKey(), PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private Component legacyToComponent(String legacy) {
        String translated = legacy.replace('&', '§');
        return LegacyComponentSerializer.legacySection().deserialize(translated);
    }
}
