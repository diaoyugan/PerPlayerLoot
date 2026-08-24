package top.diaoyugan.perPlayerLoot.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, validated configuration snapshot replaced atomically on reload. */
public record PluginSettings(
    ContainerProtection containers,
    FrameProtection frames,
    BrushableProtection brushables,
    Database database,
    PersonalDrops personalDrops,
    AdvancedLogging advancedLogging
) {

    public enum TimeoutAction {
        RECOVER,
        EXPIRE
    }

    public record ContainerProtection(
        boolean protectDestruction,
        boolean allowDestruction,
        boolean protectMerging,
        boolean allowMerging,
        boolean protectHoppers
    ) {
    }

    public record FrameProtection(
        boolean protectDestruction,
        boolean allowDestruction,
        boolean allowSneakDestruction,
        Set<Material> lootMaterials
    ) {
        public FrameProtection {
            lootMaterials = Collections.unmodifiableSet(new HashSet<>(lootMaterials));
        }
    }

    public record BrushableProtection(
        boolean protectDestruction,
        boolean allowDestruction,
        boolean allowSneakDestruction
    ) {
    }

    public record Database(String password) {
        public Database {
            password = password == null ? "" : password;
        }
    }

    public record PersonalDrops(long timeoutSeconds, TimeoutAction timeoutAction) {
    }

    public record AdvancedLogging(boolean enabled, int maxFileSizeMb, int retainedFiles) {
    }

    public static PluginSettings from(final FileConfiguration config) {
        Set<Material> lootMaterials = new HashSet<>();
        for (String name : config.getStringList("loot-frame-materials")) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isItem()) {
                lootMaterials.add(material);
            }
        }

        String timeoutName = config.getString("personal-drop-timeout-action", "RECOVER");
        TimeoutAction timeoutAction;
        try {
            timeoutAction = TimeoutAction.valueOf(
                (timeoutName == null ? "RECOVER" : timeoutName).toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            timeoutAction = TimeoutAction.RECOVER;
        }

        long timeoutSeconds = Math.max(1L, config.getLong("personal-drop-timeout-seconds", 300L));
        int maxFileSizeMb = Math.max(1, config.getInt("advanced-logging.max-file-size-mb", 10));
        int retainedFiles = Math.clamp(config.getInt("advanced-logging.retained-files", 5), 1, 100);

        return new PluginSettings(
            new ContainerProtection(
                config.getBoolean("protect-natural-loot-containers-from-destruction", true),
                config.getBoolean("allow-destroy-natural-loot-containers", false),
                config.getBoolean("protect-natural-loot-containers-from-merging", true),
                config.getBoolean("allow-merge-natural-loot-containers", false),
                config.getBoolean("protect-natural-loot-containers-from-hoppers", true)
            ),
            new FrameProtection(
                config.getBoolean("protect-natural-loot-frames-from-destruction", true),
                config.getBoolean("allow-destroy-natural-loot-frames", false),
                config.getBoolean("allow-sneak-destroy-natural-loot-frames", false),
                lootMaterials
            ),
            new BrushableProtection(
                config.getBoolean("protect-natural-loot-brushables-from-destruction", true),
                config.getBoolean("allow-destroy-natural-loot-brushables", false),
                config.getBoolean("allow-sneak-destroy-natural-loot-brushables", false)
            ),
            new Database(config.getString("database.password", "")),
            new PersonalDrops(timeoutSeconds, timeoutAction),
            new AdvancedLogging(
                config.getBoolean("advanced-logging.enabled", false),
                maxFileSizeMb,
                retainedFiles
            )
        );
    }

}
