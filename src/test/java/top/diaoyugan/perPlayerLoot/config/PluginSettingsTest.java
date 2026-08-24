package top.diaoyugan.perPlayerLoot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {
    @Test
    void parsesAndClampsConfigurationOnce() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("personal-drop-timeout-seconds", 0L);
        config.set("personal-drop-timeout-action", "expire");
        config.set("advanced-logging.max-file-size-mb", 0);
        config.set("advanced-logging.retained-files", 500);
        config.set("protect-natural-loot-containers-from-destruction", false);

        PluginSettings settings = PluginSettings.from(config);

        assertFalse(settings.containers().protectDestruction());
        assertEquals(1L, settings.personalDrops().timeoutSeconds());
        assertEquals(PluginSettings.TimeoutAction.EXPIRE, settings.personalDrops().timeoutAction());
        assertEquals(1, settings.advancedLogging().maxFileSizeMb());
        assertEquals(100, settings.advancedLogging().retainedFiles());
        assertTrue(settings.frames().lootMaterials().isEmpty());
    }

    @Test
    void invalidTimeoutActionFallsBackToRecover() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("personal-drop-timeout-action", "delete-everything");
        assertEquals(
            PluginSettings.TimeoutAction.RECOVER,
            PluginSettings.from(config).personalDrops().timeoutAction()
        );
    }
}
