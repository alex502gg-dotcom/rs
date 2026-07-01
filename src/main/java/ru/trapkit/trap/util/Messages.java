package ru.trapkit.trap.util;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import ru.trapkit.trap.TrapPlugin;

public final class Messages {

    private Messages() {
    }

    public static String raw(String path) {
        return TrapPlugin.getInstance().getConfig().getString("messages." + path, "");
    }

    /**
     * Отправляет сообщение из config.yml (messages.<path>), подставляя
     * пары вида "%плейсхолдер%", "значение" и применяя цветовой код &.
     */
    public static void send(CommandSender sender, String path, String... replacements) {
        String prefix = TrapPlugin.getInstance().getConfig().getString("messages.prefix", "");
        String message = prefix + raw(path);

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }

        message = message.replace('&', '§');
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }
}
