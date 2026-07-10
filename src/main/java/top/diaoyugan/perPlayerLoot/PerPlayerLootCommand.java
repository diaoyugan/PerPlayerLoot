package top.diaoyugan.perPlayerLoot;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PerPlayerLootCommand implements CommandExecutor, TabCompleter {

    private final PerPlayerLoot plugin;

    PerPlayerLootCommand(final PerPlayerLoot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
        final @NotNull CommandSender sender,
        final @NotNull Command command,
        final @NotNull String label,
        final @NotNull String[] args
    ) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.plugin.reloadPluginConfiguration();
            Messages.send(sender, Messages.RELOAD_SUCCESS);
            return true;
        }

        Messages.send(sender, Messages.RELOAD_USAGE);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
        final @NotNull CommandSender sender,
        final @NotNull Command command,
        final @NotNull String alias,
        final @NotNull String[] args
    ) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }
}
