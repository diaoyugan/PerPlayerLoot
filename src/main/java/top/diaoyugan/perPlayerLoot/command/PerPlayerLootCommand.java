package top.diaoyugan.perPlayerLoot.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.listener.LootListener;
import top.diaoyugan.perPlayerLoot.message.Messages;

public final class PerPlayerLootCommand implements BasicCommand {

    private final PerPlayerLoot plugin;
    private final LootListener lootListener;

    public PerPlayerLootCommand(final PerPlayerLoot plugin, final LootListener lootListener) {
        this.plugin = plugin;
        this.lootListener = lootListener;
    }

    @Override
    public void execute(final @NotNull CommandSourceStack source, final @NotNull String @NonNull [] args) {
        CommandSender sender = source.getSender();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.plugin.reloadPluginConfiguration();
            Messages.send(sender, Messages.RELOAD_SUCCESS);
            return;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("cleanup") && args[1].equalsIgnoreCase("containers")) {
            int removed = this.lootListener.cleanupOrphanContainerDataForLoadedChunks();
            sender.sendMessage(Component.text("Removed " + removed + " orphaned loot container storage entr"
                + (removed == 1 ? "y." : "ies.")));
            return;
        }

        Messages.send(sender, Messages.RELOAD_USAGE);
    }

    @Override
    public @NotNull Collection<String> suggest(final @NotNull CommandSourceStack source, final @NotNull String @NonNull [] args) {
        if (args.length == 1) {
            return List.of("reload", "cleanup").stream()
                .filter(suggestion -> suggestion.startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cleanup") && "containers".startsWith(args[1].toLowerCase())) {
            return List.of("containers");
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return "perplayerloot.admin";
    }
}

