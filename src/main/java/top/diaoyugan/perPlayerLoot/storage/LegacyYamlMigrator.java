package top.diaoyugan.perPlayerLoot.storage;

import java.io.File;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.personal.PersonalDrop;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropState;

/** One-time migration from the legacy YAML persistence format. */
final class LegacyYamlMigrator {
    private final PerPlayerLoot plugin;
    private final LootStorage storage;

    LegacyYamlMigrator(final PerPlayerLoot plugin, final LootStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    void migrateIfNeeded() {
        File source = new File(this.plugin.getDataFolder(), "loot-data.yml");
        File marker = new File(this.plugin.getDataFolder(), "loot-data.yml.migrated");
        if (!source.exists() || marker.exists() || this.storage.hasAnyData()) return;
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(source);
        migrateContainers(yaml);
        migrateFrameClaims(yaml);
        migratePersonalDrops(yaml);
        if (!source.renameTo(marker)) {
            this.plugin.getLogger().warning("Migrated loot-data.yml to SQLite, but could not rename the old file.");
        }
    }

    private void migrateContainers(final FileConfiguration yaml) {
        if (!yaml.isConfigurationSection("containers")) return;
        for (String escapedKey : yaml.getConfigurationSection("containers").getKeys(false)) {
            String key = escapedKey.replace("%2E", ".");
            String path = "containers." + escapedKey;
            if (!yaml.isConfigurationSection(path)) continue;
            for (String playerId : yaml.getConfigurationSection(path).getKeys(false)) {
                List<?> storedItems = yaml.getList(path + "." + playerId + ".contents", List.of());
                ItemStack[] contents = new ItemStack[storedItems.size()];
                for (int slot = 0; slot < storedItems.size(); slot++) {
                    if (storedItems.get(slot) instanceof ItemStack item) contents[slot] = item;
                }
                this.storage.setContainerInventory(key, UUID.fromString(playerId), contents);
            }
        }
    }

    private void migrateFrameClaims(final FileConfiguration yaml) {
        if (!yaml.isConfigurationSection("frames")) return;
        for (String frameId : yaml.getConfigurationSection("frames").getKeys(false)) {
            String claimedPath = "frames." + frameId + ".claimed";
            if (!yaml.isConfigurationSection(claimedPath)) continue;
            for (String playerId : yaml.getConfigurationSection(claimedPath).getKeys(false)) {
                if (yaml.getBoolean(claimedPath + "." + playerId, false)) {
                    this.storage.setClaimedFrame(UUID.fromString(frameId), UUID.fromString(playerId));
                }
            }
        }
    }

    private void migratePersonalDrops(final FileConfiguration yaml) {
        if (!yaml.isConfigurationSection("drops")) return;
        for (String dropId : yaml.getConfigurationSection("drops").getKeys(false)) {
            String path = "drops." + dropId;
            ItemStack item = yaml.getItemStack(path + ".item");
            Location location = readLocation(yaml, path + ".location");
            if (item == null || location == null) continue;
            this.storage.savePersonalDrop(new PersonalDrop(
                UUID.fromString(dropId),
                UUID.fromString(yaml.getString(path + ".owner")),
                UUID.fromString(yaml.getString(path + ".source")),
                item,
                location,
                yaml.getLong(path + ".created"),
                PersonalDropState.valueOf(yaml.getString(path + ".state", "RECOVERED"))
            ));
        }
    }

    private static Location readLocation(final FileConfiguration yaml, final String path) {
        String worldId = yaml.getString(path + ".world");
        if (worldId == null) return null;
        World world = Bukkit.getWorld(UUID.fromString(worldId));
        if (world == null) return null;
        return new Location(
            world, yaml.getDouble(path + ".x"), yaml.getDouble(path + ".y"), yaml.getDouble(path + ".z"),
            (float) yaml.getDouble(path + ".yaw"), (float) yaml.getDouble(path + ".pitch")
        );
    }
}
