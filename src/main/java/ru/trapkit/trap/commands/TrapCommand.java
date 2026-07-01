package ru.trapkit.trap.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.trapkit.trap.TrapPlugin;
import ru.trapkit.trap.util.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TrapCommand implements CommandExecutor, TabCompleter {

    private final TrapPlugin plugin;

    public TrapCommand(TrapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            Messages.send(sender, "usage");
            return true;
        }

        if (!sender.hasPermission("trap.give")) {
            Messages.send(sender, "no-permission");
            return true;
        }

        if (args.length < 3) {
            Messages.send(sender, "usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Messages.send(sender, "player-not-found");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            Messages.send(sender, "usage");
            return true;
        }

        if (amount <= 0) {
            Messages.send(sender, "usage");
            return true;
        }

        ItemStack item = plugin.getTrapItemManager().createTrapItem(amount);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(item);
        leftover.values().forEach(stack -> target.getWorld().dropItemNaturally(target.getLocation(), stack));

        Messages.send(sender, "give-success",
                "%amount%", String.valueOf(amount),
                "%item%", item.getType().name(),
                "%player%", target.getName());

        if (!target.equals(sender)) {
            Messages.send(target, "give-received", "%amount%", String.valueOf(amount));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();

        if (args.length == 1) {
            options.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                options.add(player.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            options.addAll(List.of("1", "8", "16", "32", "64"));
        }

        String current = args[args.length - 1].toLowerCase();
        return options.stream()
                .filter(option -> option.toLowerCase().startsWith(current))
                .collect(Collectors.toList());
    }
}
