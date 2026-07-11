package top.diaoyugan.perPlayerLoot.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;

public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static void update(final PerPlayerLoot plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
            return;
        }

        YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
        YamlConfiguration defaultConfig = loadDefaultConfig(plugin);
        YamlConfiguration updatedConfig = new YamlConfiguration();

        copyKnownKeys(defaultConfig, currentConfig, updatedConfig, "");
        try {
            updatedConfig.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not update config.yml.", exception);
        }
    }

    private static YamlConfiguration loadDefaultConfig(final PerPlayerLoot plugin) {
        try (InputStream inputStream = plugin.getResource("config.yml")) {
            if (inputStream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not read bundled config.yml.", exception);
            return new YamlConfiguration();
        }
    }

    private static void copyKnownKeys(
        final ConfigurationSection defaults,
        final ConfigurationSection current,
        final ConfigurationSection target,
        final String parentPath
    ) {
        for (String key : defaults.getKeys(false)) {
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection child = target.createSection(key);
                copyKnownKeys(
                    defaults.getConfigurationSection(key),
                    current == null ? null : current.getConfigurationSection(key),
                    child,
                    path
                );
                continue;
            }

            Object value = current != null && current.contains(key, true)
                ? current.get(key)
                : defaults.get(key);
            target.set(key, value);
        }
    }
}

