package top.diaoyugan.perPlayerLoot;

import java.util.logging.Level;
import org.bukkit.Bukkit;

final class PersonalEntityVisibilityAdapters {

    private PersonalEntityVisibilityAdapters() {
    }

    static PersonalEntityVisibilityAdapter create(final PerPlayerLoot plugin, final LootStorage storage) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            plugin.getLogger().warning("ProtocolLib is not installed or not enabled. Personal item-frame drops are disabled.");
            return null;
        }

        try {
            return new ProtocolLibPersonalEntityVisibilityAdapter(plugin, storage);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not hook ProtocolLib. Personal item-frame drops are disabled.", exception);
            return null;
        }
    }
}
