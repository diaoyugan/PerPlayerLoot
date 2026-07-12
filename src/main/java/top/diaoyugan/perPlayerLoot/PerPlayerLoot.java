package top.diaoyugan.perPlayerLoot;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import top.diaoyugan.perPlayerLoot.command.PerPlayerLootCommand;
import top.diaoyugan.perPlayerLoot.config.ConfigUpdater;
import top.diaoyugan.perPlayerLoot.listener.LootListener;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropManager;
import top.diaoyugan.perPlayerLoot.personal.PersonalEntityVisibilityAdapter;
import top.diaoyugan.perPlayerLoot.personal.PersonalEntityVisibilityAdapters;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

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
            new PerPlayerLootCommand(this, lootListener)
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

    public void reloadPluginConfiguration() {
        ConfigUpdater.update(this);
        reloadConfig();
        Messages.load(this);
    }
}
