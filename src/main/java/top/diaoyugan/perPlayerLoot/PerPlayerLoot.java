package top.diaoyugan.perPlayerLoot;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import top.diaoyugan.perPlayerLoot.command.PerPlayerLootCommand;
import top.diaoyugan.perPlayerLoot.config.ConfigUpdater;
import top.diaoyugan.perPlayerLoot.config.PluginSettings;
import top.diaoyugan.perPlayerLoot.listener.LootListener;
import top.diaoyugan.perPlayerLoot.listener.BrushableLootListener;
import top.diaoyugan.perPlayerLoot.listener.ItemFrameLootListener;
import top.diaoyugan.perPlayerLoot.listener.MinecartLootListener;
import top.diaoyugan.perPlayerLoot.listener.ContainerProtectionListener;
import top.diaoyugan.perPlayerLoot.logging.AdvancedLogger;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropManager;
import top.diaoyugan.perPlayerLoot.personal.PersonalEntityVisibilityAdapter;
import top.diaoyugan.perPlayerLoot.personal.PersonalEntityVisibilityAdapters;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

public final class PerPlayerLoot extends JavaPlugin {

    private LootStorage lootStorage;
    private PersonalEntityVisibilityAdapter visibilityAdapter;
    private PersonalDropManager personalDropManager;
    private AdvancedLogger advancedLogger;
    private volatile PluginSettings settings;
    private BrushableLootListener brushableLootListener;

    @Override
    public void onEnable() {
        reloadPluginConfiguration();
        this.advancedLogger = new AdvancedLogger(this);
        this.advancedLogger.reload();

        this.lootStorage = new LootStorage(this);
        this.lootStorage.load();

        this.visibilityAdapter = PersonalEntityVisibilityAdapters.create(this, this.lootStorage);
        this.personalDropManager = new PersonalDropManager(this, this.lootStorage, this.visibilityAdapter);
        this.personalDropManager.start();
        this.personalDropManager.restoreOnlinePlayerDrops();

        LootListener lootListener = new LootListener(
            this,
            this.lootStorage
        );
        getServer().getPluginManager().registerEvents(
            lootListener,
            this
        );
        lootListener.tagLoadedLootContainers();
        getServer().getPluginManager().registerEvents(
            new ItemFrameLootListener(this, this.personalDropManager),
            this
        );
        getServer().getPluginManager().registerEvents(
            new MinecartLootListener(this, this.lootStorage, lootListener),
            this
        );
        getServer().getPluginManager().registerEvents(new ContainerProtectionListener(lootListener), this);

        this.brushableLootListener = new BrushableLootListener(
            this,
            this.lootStorage,
            this.personalDropManager
        );
        getServer().getPluginManager().registerEvents(this.brushableLootListener, this);
        this.brushableLootListener.start();
        this.brushableLootListener.tagLoadedBrushables();

        registerCommand(
            "perplayerloot",
            "Reload PerPlayerLoot or clean up orphaned container data.",
            List.of("ppl"),
            new PerPlayerLootCommand(this, lootListener)
        );
    }

    @Override
    public void onDisable() {
        if (this.personalDropManager != null) {
            this.personalDropManager.recoverAllActiveDrops();
            this.personalDropManager.close();
        }
        if (this.brushableLootListener != null) {
            this.brushableLootListener.close();
        }
        if (this.visibilityAdapter != null) {
            this.visibilityAdapter.close();
        }
        if (this.lootStorage != null) {
            this.lootStorage.save();
        }
        if (this.advancedLogger != null) {
            this.advancedLogger.close();
        }
    }

    public void reloadPluginConfiguration() {
        ConfigUpdater.update(this);
        reloadConfig();
        this.settings = PluginSettings.from(getConfig());
        Messages.load(this);
        if (this.advancedLogger != null) {
            this.advancedLogger.reload();
        }
    }

    public void logAdvanced(final String format, final Object... arguments) {
        if (this.advancedLogger != null) {
            this.advancedLogger.log(format, arguments);
        }
    }

    public boolean isAdvancedLoggingEnabled() {
        return this.advancedLogger != null && this.advancedLogger.isEnabled();
    }

    public PluginSettings settings() {
        return this.settings;
    }
}
