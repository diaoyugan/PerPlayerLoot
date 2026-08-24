package top.diaoyugan.perPlayerLoot.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Compact, stable descriptions used by advanced audit messages. */
public final class LogDescriptions {

    private LogDescriptions() {
    }

    public static String player(final Player player) {
        return player.getName() + " (" + player.getUniqueId() + ")";
    }

    public static String player(final String name, final UUID playerId) {
        return name + " (" + playerId + ")";
    }

    public static String location(final Location location) {
        if (location == null) {
            return "none";
        }
        World world = location.getWorld();
        String worldDescription = world == null
            ? "unknown"
            : world.getName() + " (" + world.getUID() + ")";
        return worldDescription + " @ "
            + String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", location.getX(), location.getY(), location.getZ());
    }

    public static String item(final ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "empty";
        }
        return item.getType().name() + " x" + item.getAmount();
    }

    public static String items(final ItemStack[] contents) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            totals.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        if (totals.isEmpty()) {
            return "empty";
        }
        return totals.entrySet().stream()
            .map(entry -> entry.getKey() + " x" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
    }
}
