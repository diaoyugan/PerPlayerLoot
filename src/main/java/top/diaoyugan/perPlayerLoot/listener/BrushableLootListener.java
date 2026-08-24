package top.diaoyugan.perPlayerLoot.listener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.config.ProtectionPolicy;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.logging.LogDescriptions;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropManager;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

/** Keeps archaeology blocks intact while tracking vanilla-paced brushing per player. */
public final class BrushableLootListener implements Listener {

    private static final int REQUIRED_BRUSHES = 10;
    private static final long RESET_AFTER_TICKS = 40L;

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final PersonalDropManager personalDropManager;
    private final NamespacedKey lootTableKey;
    private final NamespacedKey lootSeedKey;
    private final Map<BrushSessionKey, BrushSession> sessions = new HashMap<>();
    private BukkitTask cleanupTask;

    public BrushableLootListener(
        final PerPlayerLoot plugin,
        final LootStorage storage,
        final PersonalDropManager personalDropManager
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.personalDropManager = personalDropManager;
        this.lootTableKey = new NamespacedKey(plugin, "brushable_loot_table");
        this.lootSeedKey = new NamespacedKey(plugin, "brushable_loot_seed");
    }

    public void start() {
        if (this.cleanupTask == null) {
            this.cleanupTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::cleanupExpiredSessions, 20L, 20L);
        }
    }

    public void close() {
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
            this.cleanupTask = null;
        }
        this.sessions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrushProgress(final EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof BrushableBlock brushable) || !isNaturalBrushable(state)) {
            return;
        }

        if (!(event.getEntity() instanceof Player player)) {
            if (isDestructionProtectionEnabled()
                && !this.plugin.settings().brushables().allowDestruction()) {
                event.setCancelled(true);
            } else {
                cleanup(block);
            }
            return;
        }
        LootTable lootTable = brushable.getLootTable();
        if (lootTable == null) {
            return;
        }

        // The vanilla brush animation, particles, and sound have already happened. Cancelling
        // only this state mutation leaves the block visually unbrushed for every player.
        event.setCancelled(true);
        tagBrushable(brushable);

        String blockKey = blockKey(block.getLocation());
        UUID playerId = player.getUniqueId();
        if (this.storage.hasClaimedBrushable(blockKey, playerId)) {
            this.sessions.remove(new BrushSessionKey(playerId, blockKey));
            Messages.send(player, Messages.BRUSHABLE_ALREADY_CLAIMED);
            this.plugin.logAdvanced(
                "Rejected duplicate suspicious-block claim: player=%s, block=%s, sourceKey=%s, location=%s",
                LogDescriptions.player(player),
                block.getType(),
                blockKey,
                LogDescriptions.location(block.getLocation())
            );
            return;
        }
        if (!this.personalDropManager.isEnabled()) {
            Messages.send(player, Messages.PERSONAL_DROPS_DISABLED);
            this.plugin.logAdvanced(
                "Could not create suspicious-block personal loot because ProtocolLib support is unavailable: "
                    + "player=%s, block=%s, sourceKey=%s, location=%s",
                LogDescriptions.player(player),
                block.getType(),
                blockKey,
                LogDescriptions.location(block.getLocation())
            );
            return;
        }

        long gameTime = block.getWorld().getGameTime();
        BrushSessionKey sessionKey = new BrushSessionKey(playerId, blockKey);
        BrushSession previous = this.sessions.get(sessionKey);
        boolean resetSession = previous == null || gameTime - previous.lastBrushTick() >= RESET_AFTER_TICKS;
        int brushCount = resetSession ? 1 : previous.brushCount() + 1;
        List<ItemStack> loot = resetSession
            ? populateLoot(player, block, lootTable, blockKey)
            : previous.loot();
        this.sessions.put(sessionKey, new BrushSession(brushCount, gameTime, loot, block.getLocation()));
        scheduleBrushPreview(
            player,
            block,
            sessionKey,
            gameTime,
            loot,
            brushCount,
            brushFace(player, block)
        );
        this.plugin.logAdvanced(
            "Advanced archaeology progress: player=%s, block=%s, sourceKey=%s, progress=%d/%d, location=%s",
            LogDescriptions.player(player),
            block.getType(),
            blockKey,
            brushCount,
            REQUIRED_BRUSHES,
            LogDescriptions.location(block.getLocation())
        );
        if (brushCount < REQUIRED_BRUSHES) {
            return;
        }

        this.sessions.remove(sessionKey);
        claimLoot(player, block, lootTable, blockKey, loot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isNaturalBrushable(block.getState())) {
            return;
        }

        if (!canDestroy(event.getPlayer())) {
            event.setCancelled(true);
            Messages.send(event.getPlayer(), Messages.NO_BRUSHABLE_DESTROY_PERMISSION);
            this.plugin.logAdvanced(
                "Blocked suspicious-block destruction: player=%s, block=%s, sourceKey=%s, location=%s",
                LogDescriptions.player(event.getPlayer()),
                block.getType(),
                blockKey(block.getLocation()),
                LogDescriptions.location(block.getLocation())
            );
            return;
        }
        this.plugin.logAdvanced(
            "Natural suspicious block destroyed: player=%s, block=%s, sourceKey=%s, location=%s; claims removed",
            LogDescriptions.player(event.getPlayer()),
            block.getType(),
            blockKey(block.getLocation()),
            LogDescriptions.location(block.getLocation())
        );
        cleanup(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        protectOrCleanupExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        protectOrCleanupExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(final ChunkLoadEvent event) {
        tagBrushables(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkPopulate(final ChunkPopulateEvent event) {
        tagBrushables(event.getChunk());
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.sessions.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public void tagLoadedBrushables() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                tagBrushables(chunk);
            }
        }
    }

    private void claimLoot(
        final Player player,
        final Block block,
        final LootTable lootTable,
        final String blockKey,
        final List<ItemStack> loot
    ) {
        if (loot.isEmpty()) {
            this.plugin.getLogger().warning(
                "The archaeology loot table returned no items for " + LogDescriptions.player(player)
                    + " at " + LogDescriptions.location(block.getLocation()) + "; the block was not claimed."
            );
            resetBrushPreview(player, block);
            return;
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        Vector outward = player.getEyeLocation().toVector().subtract(center.toVector());
        if (outward.lengthSquared() < 0.001) {
            outward = new Vector(0, 0.15, 0.1);
        } else {
            outward.normalize();
        }
        Location spawnLocation = center.clone().add(outward.clone().multiply(0.65)).setDirection(outward);
        Vector velocity = outward.clone().multiply(0.12).setY(0.12);
        UUID sourceId = UUID.nameUUIDFromBytes(("brushable;" + blockKey).getBytes(StandardCharsets.UTF_8));
        if (!this.personalDropManager.createBrushableDrops(
            player,
            blockKey,
            sourceId,
            loot,
            spawnLocation,
            velocity,
            block.getType().name() + " sourceKey=" + blockKey
        )) {
            resetBrushPreview(player, block);
            return;
        }

        // Reproduce vanilla's completion particles/sound only for the claiming player;
        // unlike vanilla, this effect packet does not replace the suspicious block.
        player.playEffect(block.getLocation(), Effect.BRUSH_BLOCK_COMPLETE, block.getBlockData());
        Bukkit.getScheduler().runTask(this.plugin, () -> resetBrushPreview(player, block));

        EquipmentSlot activeHand = player.getActiveItemHand();
        if (activeHand != null) {
            player.damageItemStack(activeHand, 1);
        }
        this.plugin.logAdvanced(
            "Completed personal archaeology claim: player=%s, block=%s, sourceKey=%s, location=%s, "
                + "lootTable=%s, items=[%s]",
            LogDescriptions.player(player),
            block.getType(),
            blockKey,
            LogDescriptions.location(block.getLocation()),
            lootTable.getKey(),
            LogDescriptions.items(loot.toArray(ItemStack[]::new))
        );
    }

    private void protectOrCleanupExplosion(final java.util.List<Block> blocks) {
        boolean protect = isDestructionProtectionEnabled();
        boolean allowDestroy = this.plugin.settings().brushables().allowDestruction();
        blocks.removeIf(block -> {
            if (!isNaturalBrushable(block.getState())) {
                return false;
            }
            if (!protect || allowDestroy) {
                cleanup(block);
                return false;
            }
            return true;
        });
    }

    private void cleanup(final Block block) {
        String key = blockKey(block.getLocation());
        this.storage.removeBrushableClaims(key);
        this.sessions.keySet().removeIf(session -> session.blockKey().equals(key));
    }

    private void tagBrushables(final Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof BrushableBlock brushable && brushable.getLootTable() != null) {
                tagBrushable(brushable);
            }
        }
    }

    private void tagBrushable(final BrushableBlock brushable) {
        LootTable lootTable = brushable.getLootTable();
        if (lootTable == null) {
            return;
        }
        PersistentDataContainer data = brushable.getPersistentDataContainer();
        String tableName = lootTable.getKey().toString();
        long lootSeed = brushable.getSeed();
        if (tableName.equals(data.get(this.lootTableKey, PersistentDataType.STRING))
            && data.getOrDefault(this.lootSeedKey, PersistentDataType.LONG, 0L) == lootSeed) {
            return;
        }
        data.set(this.lootTableKey, PersistentDataType.STRING, tableName);
        data.set(this.lootSeedKey, PersistentDataType.LONG, lootSeed);
        brushable.update(false, false);
    }

    private boolean isNaturalBrushable(final BlockState state) {
        if (!(state instanceof BrushableBlock brushable)) {
            return false;
        }
        return brushable.getLootTable() != null
            || brushable.getPersistentDataContainer().has(this.lootTableKey, PersistentDataType.STRING);
    }

    private boolean canDestroy(final Player player) {
        return ProtectionPolicy.canDestroyBrushable(
            this.plugin.settings().brushables(),
            player.isSneaking(),
            player.hasPermission("perplayerloot.destroy.brushables")
        );
    }

    private boolean isDestructionProtectionEnabled() {
        return this.plugin.settings().brushables().protectDestruction();
    }

    private static String blockKey(final Location location) {
        return location.getWorld().getUID() + ";" + location.getBlockX() + ";"
            + location.getBlockY() + ";" + location.getBlockZ();
    }

    private static List<ItemStack> populateLoot(
        final Player player,
        final Block block,
        final LootTable lootTable,
        final String blockKey
    ) {
        Collection<ItemStack> generatedLoot = lootTable.populateLoot(
            new Random(seed(blockKey, player.getUniqueId())),
            new LootContext.Builder(block.getLocation())
                .lootedEntity(player)
                .killer(player)
                .build()
        );
        List<ItemStack> loot = new ArrayList<>(generatedLoot.size());
        for (ItemStack item : generatedLoot) {
            if (item != null && !item.getType().isAir()) {
                loot.add(item.clone());
            }
        }
        return loot;
    }

    private void scheduleBrushPreview(
        final Player player,
        final Block block,
        final BrushSessionKey sessionKey,
        final long brushTick,
        final List<ItemStack> loot,
        final int brushCount,
        final BlockFace brushFace
    ) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            BrushSession current = this.sessions.get(sessionKey);
            if (current == null || current.lastBrushTick() != brushTick || current.brushCount() != brushCount) {
                return;
            }
            boolean directionInjected = sendBrushPreview(player, block, loot, brushCount, brushFace);
            this.plugin.logAdvanced(
                "Sent personal archaeology item preview: player=%s, block=%s, progress=%d/%d, face=%s, "
                    + "directionInjected=%s, item=%s, location=%s",
                LogDescriptions.player(player),
                block.getType(),
                brushCount,
                REQUIRED_BRUSHES,
                brushFace,
                directionInjected,
                loot.isEmpty() ? "AIR" : LogDescriptions.item(loot.get(0)),
                LogDescriptions.location(block.getLocation())
            );
        });
    }

    private boolean sendBrushPreview(
        final Player player,
        final Block block,
        final List<ItemStack> loot,
        final int brushCount,
        final BlockFace brushFace
    ) {
        if (block.getBlockData() instanceof org.bukkit.block.data.Brushable brushableData) {
            org.bukkit.block.data.Brushable personalData =
                (org.bukkit.block.data.Brushable) brushableData.clone();
            int dusted = brushCount >= 6 ? 3 : brushCount >= 3 ? 2 : 1;
            dusted = Math.min(personalData.getMaximumDusted(), dusted);
            personalData.setDusted(dusted);
            player.sendBlockChange(block.getLocation(), personalData);
        }

        boolean directionInjected = false;
        if (!loot.isEmpty()) {
            BlockState previewState = block.getState();
            if (previewState instanceof BrushableBlock previewBrushable) {
                previewBrushable.setLootTable(null);
                previewBrushable.setItem(loot.get(0).clone());
                directionInjected = this.personalDropManager.sendBrushablePreview(
                    player,
                    block.getLocation(),
                    previewBrushable,
                    brushFace
                );
            }
        }
        return directionInjected;
    }

    private static BlockFace brushFace(final Player player, final Block block) {
        BlockFace targetFace = player.getTargetBlockFace(6);
        if (targetFace == BlockFace.UP || targetFace == BlockFace.DOWN
            || targetFace == BlockFace.NORTH || targetFace == BlockFace.SOUTH
            || targetFace == BlockFace.EAST || targetFace == BlockFace.WEST) {
            return targetFace;
        }

        Vector relative = player.getEyeLocation().toVector()
            .subtract(block.getLocation().add(0.5, 0.5, 0.5).toVector());
        double x = Math.abs(relative.getX());
        double y = Math.abs(relative.getY());
        double z = Math.abs(relative.getZ());
        if (y >= x && y >= z) {
            return relative.getY() >= 0 ? BlockFace.UP : BlockFace.DOWN;
        }
        if (x >= z) {
            return relative.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return relative.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void cleanupExpiredSessions() {
        var iterator = this.sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BrushSessionKey, BrushSession> entry = iterator.next();
            BrushSession session = entry.getValue();
            Location location = session.blockLocation();
            World world = location.getWorld();
            if (world == null || world.getGameTime() - session.lastBrushTick() < RESET_AFTER_TICKS) {
                continue;
            }
            iterator.remove();
            Player player = Bukkit.getPlayer(entry.getKey().playerId());
            if (player != null) {
                resetBrushPreview(player, location.getBlock());
            }
        }
    }

    private static void resetBrushPreview(final Player player, final Block block) {
        if (!player.isOnline() || !block.getChunk().isLoaded()) {
            return;
        }
        player.sendBlockChange(block.getLocation(), block.getBlockData());
        BlockState currentState = block.getState();
        if (currentState instanceof BrushableBlock currentBrushable) {
            player.sendBlockUpdate(block.getLocation(), currentBrushable);
        }
    }

    private static long seed(final String sourceKey, final UUID playerId) {
        long result = 1125899906842597L;
        result = 31 * result + sourceKey.hashCode();
        result = 31 * result + playerId.getMostSignificantBits();
        return 31 * result + playerId.getLeastSignificantBits();
    }

}
