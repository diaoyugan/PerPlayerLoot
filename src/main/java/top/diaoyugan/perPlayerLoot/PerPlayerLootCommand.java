package top.diaoyugan.perPlayerLoot;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

final class PerPlayerLootCommand implements BasicCommand {

    private final PerPlayerLoot plugin;

    PerPlayerLootCommand(final PerPlayerLoot plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final @NotNull CommandSourceStack source, final @NotNull String @NonNull [] args) {
        CommandSender sender = source.getSender();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.plugin.reloadPluginConfiguration();
            Messages.send(sender, Messages.RELOAD_SUCCESS);
            return;
        }

        Messages.send(sender, Messages.RELOAD_USAGE);
    }

    @Override
    public @NotNull Collection<String> suggest(final @NotNull CommandSourceStack source, final @NotNull String @NonNull [] args) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return "perplayerloot.reload";
    }
}
