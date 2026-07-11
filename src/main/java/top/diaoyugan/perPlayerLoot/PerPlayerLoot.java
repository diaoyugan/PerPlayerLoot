package top.diaoyugan.perPlayerLoot;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class PerPlayerLoot extends JavaPlugin {

    private LootStorage lootStorage;
    private PersonalEntityVisibilityAdapter visibilityAdapter;
    private PersonalDropManager personalDropManager;

    @Override
    public void onEnable() {
        reloadPluginConfiguration();

        this.lootStorage = new LootStorage(this);
        this.lootStorage.load();

        NamespacedKey playerPlacedFrameKey = new NamespacedKey(this, "player_managed_frame");
        NamespacedKey legacyPlayerPlacedFrameKey = new NamespacedKey(this, "player_placed_frame");
        this.visibilityAdapter = PersonalEntityVisibilityAdapters.create(this, this.lootStorage);
        this.personalDropManager = new PersonalDropManager(this, this.lootStorage, this.visibilityAdapter);
        this.personalDropManager.restoreOnlinePlayerDrops();

        LootListener lootListener = new LootListener(
            this,
            this.lootStorage,
            playerPlacedFrameKey,
            legacyPlayerPlacedFrameKey,
            this.personalDropManager
        );
        getServer().getPluginManager().registerEvents(
            lootListener,
            this
        );
        lootListener.tagLoadedLootContainers();

        registerCommand(
            "perplayerloot",
            "Reload PerPlayerLoot configuration and language files.",
            List.of("ppl"),
            new PerPlayerLootCommand(this)
        );
    }

    @Override
    public void onDisable() {
        if (this.personalDropManager != null) {
            this.personalDropManager.recoverAllActiveDrops();
        }
        if (this.visibilityAdapter != null) {
            this.visibilityAdapter.close();
        }
        if (this.lootStorage != null) {
            this.lootStorage.save();
        }
    }

    void reloadPluginConfiguration() {
        ConfigUpdater.update(this);
        reloadConfig();
        Messages.load(this);
    }
}
