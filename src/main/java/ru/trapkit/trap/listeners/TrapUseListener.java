package ru.trapkit.trap.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ru.trapkit.trap.TrapPlugin;
import ru.trapkit.trap.util.Messages;

public class TrapUseListener implements Listener {

    private final TrapPlugin plugin;

    public TrapUseListener(TrapPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ПКМ по другому игроку (или по себе) — ловушка ставится на цель.
     */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }

        Player user = event.getPlayer();
        ItemStack hand = user.getInventory().getItemInMainHand();

        if (!plugin.getTrapItemManager().isTrapItem(hand)) {
            return;
        }

        event.setCancelled(true);
        springTrap(user, target, hand);
    }

    /**
     * ПКМ по блоку или просто в воздух (нет цели-игрока рядом) —
     * ловушка ставится прямо на игрока, который использовал предмет.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
            }
            default -> {
                return;
            }
        }

        Player user = event.getPlayer();
        ItemStack hand = user.getInventory().getItemInMainHand();

        if (!plugin.getTrapItemManager().isTrapItem(hand)) {
            return;
        }

        event.setCancelled(true);
        springTrap(user, user, hand);
    }

    private void springTrap(Player user, Player target, ItemStack hand) {
        if (plugin.getRoomManager().isTrapped(target)) {
            return;
        }

        hand.setAmount(hand.getAmount() - 1);

        plugin.getRoomManager().trapPlayer(target);

        if (target.equals(user)) {
            Messages.send(user, "trapped");
        } else {
            Messages.send(user, "trapped-other", "%player%", target.getName());
            Messages.send(target, "trapped");
        }
    }
}
