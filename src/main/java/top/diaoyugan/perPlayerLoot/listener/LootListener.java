package top.diaoyugan.perPlayerLoot.listener;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Nameable;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lidded;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.config.ProtectionPolicy;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.logging.LogDescriptions;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropManager;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;
import top.diaoyugan.perPlayerLoot.storage.LootStorage.StoredContainer;
import top.diaoyugan.perPlayerLoot.storage.ChunkKey;

public final class LootListener implements Listener {

    private static final byte TRUE = 1;
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH,
        BlockFace.EAST,
        BlockFace.SOUTH,
        BlockFace.WEST
    };

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final NamespacedKey lootContainerTableKey;
    private final NamespacedKey lootContainerSeedKey;
    private final Map<String, Integer> openContainerCounts = new HashMap<>();
    private final Map<ChunkKey, Long> containerCleanupGenerations = new HashMap<>();
    private final Map<ChunkKey, Set<String>> loadedContainerSources = new HashMap<>();
    private final Map<UUID, Long> containerOpenGenerations = new HashMap<>();
    private long nextContainerCleanupGeneration;
    private long nextContainerOpenGeneration;

    public LootListener(
        final PerPlayerLoot plugin,
        final LootStorage storage
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.lootContainerTableKey = new NamespacedKey(plugin, "loot_container_table");
        this.lootContainerSeedKey = new NamespacedKey(plugin, "loot_container_seed");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        this.containerOpenGenerations.remove(event.getPlayer().getUniqueId());

        tagNaturalLootChestsNearPlacement(event);

        Block block = event.getClickedBlock();
        BlockState state = block.getState();
        if (!(state instanceof Container) || !(state instanceof Lootable)) {
            return;
        }

        ChestResolution resolution = resolveChest(block);
        switch (resolution.type()) {
            case SINGLE -> {
                event.setCancelled(true);
                openSingleContainer(event.getPlayer(), resolution.lootParts().getFirst());
            }
            case NATURAL_DOUBLE -> {
                event.setCancelled(true);
                openNaturalDoubleChest(event.getPlayer(), resolution.lootParts());
            }
            case MIXED_DOUBLE -> {
                if (!this.plugin.settings().containers().protectMerging()) {
                    // With merge protection fully disabled, let vanilla open the real mixed chest.
                    // It may consume the loot table, so discard virtual data after the interaction finishes.
                    for (LootContainerPart part : resolution.lootParts()) {
                        Bukkit.getScheduler().runTask(this.plugin, () -> cleanupLostLootContainer(part.block()));
                    }
                    break;
                }
                separateChestPair(resolution.blocks().get(0), resolution.blocks().get(1));

                LootContainerPart clickedPart = resolution.lootParts().getFirst();
                if (clickedPart.block().equals(block)) {
                    event.setCancelled(true);
                    openSingleContainer(event.getPlayer(), resolveSinglePart(block));
                }
            }
            case UNMANAGED -> cleanupLostLootContainer(block);
        }
    }

    void protectBlockBreak(final BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        String containerKey = containerKey(event.getBlock().getLocation());
        if (!isManagedNaturalLootContainer(state)
            && !hasLoadedContainerSource(event.getBlock().getLocation(), containerKey)) {
            return;
        }

        if (canDestroyNaturalLootContainer(event.getPlayer())) {
            closeVirtualContainerViews(containerKey);
            removeStoredContainerData(containerKey, event.getBlock().getLocation());
            this.plugin.logAdvanced(
                "Natural loot container destroyed: player=%s, block=%s, sourceKey=%s, location=%s; personal data removed",
                LogDescriptions.player(event.getPlayer()),
                event.getBlock().getType(),
                containerKey,
                LogDescriptions.location(event.getBlock().getLocation())
            );
            return;
        }

        event.setCancelled(true);
        Messages.send(event.getPlayer(), Messages.NO_CONTAINER_DESTROY_PERMISSION);
        this.plugin.logAdvanced(
            "Blocked natural loot container destruction: player=%s, block=%s, sourceKey=%s, location=%s",
            LogDescriptions.player(event.getPlayer()),
            event.getBlock().getType(),
            containerKey,
            LogDescriptions.location(event.getBlock().getLocation())
        );
    }

    void protectChestPlacement(final BlockPlaceEvent event) {
        if (canMergeNaturalLootContainer(event.getPlayer())) {
            return;
        }

        Block placedBlock = event.getBlockPlaced();
        if (!(placedBlock.getBlockData() instanceof Chest)) {
            return;
        }

        for (BlockFace face : HORIZONTAL_FACES) {
            Block adjacentBlock = placedBlock.getRelative(face);
            if (adjacentBlock.getType() != placedBlock.getType()
                || !(adjacentBlock.getBlockData() instanceof Chest)
                || !isManagedNaturalLootContainer(adjacentBlock.getState())) {
                continue;
            }

            // Match vanilla sneak-placement behavior without cancelling the placement.
            separateChestPair(placedBlock, adjacentBlock);
            // Paper may finish neighbor-state updates after BlockPlaceEvent listeners return.
            Bukkit.getScheduler().runTask(
                this.plugin,
                () -> separateChestPair(placedBlock, adjacentBlock)
            );
            return;
        }
    }

    private void tagNaturalLootChestsNearPlacement(final PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || (item.getType() != Material.CHEST && item.getType() != Material.TRAPPED_CHEST)) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        tagNaturalLootChestNeighbors(clickedBlock);
        tagNaturalLootChestNeighbors(clickedBlock.getRelative(event.getBlockFace()));
    }

    private void tagNaturalLootChestNeighbors(final Block center) {
        for (BlockFace face : HORIZONTAL_FACES) {
            Block block = center.getRelative(face);
            BlockState state = block.getState();
            if (state.getBlockData() instanceof Chest
                && state instanceof Lootable lootable
                && lootable.getLootTable() != null) {
                tagLootContainer(state, lootable.getLootTable());
            }
        }
    }

    private static void separateChestPair(final Block placedBlock, final Block adjacentBlock) {
        if (placedBlock.getType() != adjacentBlock.getType()
            || !(placedBlock.getBlockData() instanceof Chest placedChest)
            || !(adjacentBlock.getBlockData() instanceof Chest adjacentChest)) {
            return;
        }

        placedChest.setType(Chest.Type.SINGLE);
        placedBlock.setBlockData(placedChest, false);
        adjacentChest.setType(Chest.Type.SINGLE);
        adjacentBlock.setBlockData(adjacentChest, false);
    }

    void cleanupBlockExplosion(final BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            cleanupDestroyedLootContainer(block);
        }
    }

    void cleanupEntityExplosion(final EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            cleanupDestroyedLootContainer(block);
        }
    }

    void protectBlockExplosion(final BlockExplodeEvent event) {
        protectNaturalLootContainersFromExplosion(event.blockList());
    }

    void protectEntityExplosion(final EntityExplodeEvent event) {
        protectNaturalLootContainersFromExplosion(event.blockList());
    }

    void protectInventoryMove(final InventoryMoveItemEvent event) {
        if (!this.plugin.settings().containers().protectHoppers()) {
            cleanupLostLootContainerData(event.getSource());
            cleanupLostLootContainerData(event.getDestination());
            return;
        }

        if (isProtectedLootContainerInventory(event.getSource())
            || isProtectedLootContainerInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    void protectHopperSearch(final HopperInventorySearchEvent event) {
        if (!this.plugin.settings().containers().protectHoppers()) {
            return;
        }

        // Stop hoppers before they receive the inventory; moving items can consume vanilla loot tables.
        Inventory inventory = event.getInventory();
        if ((inventory != null && isProtectedLootContainerInventory(inventory))
            || isManagedNaturalLootContainer(event.getSearchBlock().getState())) {
            event.setInventory(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(final ChunkLoadEvent event) {
        tagLootContainers(event.getChunk());
        tagLootMinecarts(event.getChunk());
        scheduleOrphanContainerCleanup(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(final ChunkUnloadEvent event) {
        ChunkKey key = ChunkKey.of(event.getChunk());
        this.containerCleanupGenerations.remove(key);
        this.loadedContainerSources.remove(key);
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        this.containerOpenGenerations.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnyEntityInteract(final PlayerInteractEntityEvent event) {
        this.containerOpenGenerations.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnyInventoryOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            this.containerOpenGenerations.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkPopulate(final ChunkPopulateEvent event) {
        // Generated tile entities can be added after ChunkLoadEvent has already fired.
        tagLootContainers(event.getChunk());
        tagLootMinecarts(event.getChunk());
    }

    public void tagLoadedLootContainers() {
        // POSTWORLD startup can see chunks that loaded before this listener was registered.
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                tagLootContainers(chunk);
                tagLootMinecarts(chunk);
                scheduleOrphanContainerCleanup(chunk);
            }
        }
    }

    public void cleanupOrphanContainerDataForLoadedChunks(
        final java.util.function.IntConsumer completion
    ) {
        this.storage.getAllContainerDataAsync().whenComplete((containers, queryFailure) -> {
            if (queryFailure != null) {
                this.plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not query stored container sources for cleanup.",
                    queryFailure
                );
                if (this.plugin.isEnabled()) Bukkit.getScheduler().runTask(this.plugin, () -> completion.accept(-1));
                return;
            }
            if (!this.plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                List<String> orphanKeys = new java.util.ArrayList<>();
                for (StoredContainer container : containers) {
                    World world = Bukkit.getWorld(container.worldId());
                    if (world == null) {
                        orphanKeys.add(container.containerKey());
                    } else if (world.isChunkLoaded(container.chunkX(), container.chunkZ())
                        && isOrphanContainerData(world, container)) {
                        orphanKeys.add(container.containerKey());
                    }
                }
                this.storage.removeContainerDataAsync(orphanKeys).whenComplete((ignored, deleteFailure) -> {
                    if (!this.plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(
                        this.plugin,
                        () -> completion.accept(deleteFailure == null ? orphanKeys.size() : -1)
                    );
                    if (deleteFailure != null) {
                        this.plugin.getLogger().log(
                            java.util.logging.Level.WARNING,
                            "Could not remove orphaned container data.",
                            deleteFailure
                        );
                    }
                });
            });
        });
    }

    private void scheduleOrphanContainerCleanup(final Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        long generation = ++this.nextContainerCleanupGeneration;
        this.containerCleanupGenerations.put(key, generation);
        this.storage.getContainerDataInChunkAsync(key.worldId(), key.chunkX(), key.chunkZ())
            .whenComplete((containers, failure) -> {
                if (failure != null) {
                    this.plugin.getLogger().log(
                        java.util.logging.Level.WARNING,
                        "Could not inspect stored container sources in chunk " + key + ".",
                        failure
                    );
                    return;
                }
                if (!this.plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(
                    this.plugin,
                    () -> finishOrphanContainerCleanup(key, generation, containers)
                );
            });
    }

    private void finishOrphanContainerCleanup(
        final ChunkKey key,
        final long generation,
        final List<StoredContainer> containers
    ) {
        if (!Long.valueOf(generation).equals(this.containerCleanupGenerations.get(key))) return;
        World world = Bukkit.getWorld(key.worldId());
        if (world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) return;

        Set<String> sourceKeys = new HashSet<>();
        for (StoredContainer container : containers) sourceKeys.add(container.containerKey());
        this.loadedContainerSources.put(key, sourceKeys);

        List<String> orphanKeys = new java.util.ArrayList<>();
        for (StoredContainer container : containers) {
            if (isOrphanContainerData(world, container)) orphanKeys.add(container.containerKey());
        }
        if (orphanKeys.isEmpty()) return;
        this.storage.removeContainerDataAsync(orphanKeys).whenComplete((ignored, failure) -> {
            if (failure != null) {
                this.plugin.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Could not remove orphaned container data in chunk " + key + ".",
                    failure
                );
            } else {
                runOnMain(() -> {
                    Set<String> current = this.loadedContainerSources.get(key);
                    if (current != null) current.removeAll(orphanKeys);
                });
            }
        });
    }

    private boolean isOrphanContainerData(final World world, final StoredContainer container) {
        if (container.blockY() < world.getMinHeight() || container.blockY() >= world.getMaxHeight()) return true;
        Block block = world.getBlockAt(container.blockX(), container.blockY(), container.blockZ());
        return !isManagedNaturalLootContainer(block.getState());
    }

    private void tagLootContainers(final Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
                tagLootContainer(state, lootable.getLootTable());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PerPlayerLootInventoryHolder holder)) {
            return;
        }

        ItemStack[] combinedContents = event.getInventory().getContents();
        String playerName = event.getPlayer().getName();
        for (InventoryPart part : holder.parts()) {
            ItemStack[] contents = Arrays.copyOfRange(
                combinedContents,
                part.offset(),
                part.offset() + part.size()
            );
            this.storage.setContainerInventoryAsync(part.containerKey(), holder.playerId(), contents)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        this.plugin.getLogger().log(
                            java.util.logging.Level.SEVERE,
                            "Could not save personal loot inventory " + part.containerKey() + ".",
                            failure
                        );
                    } else {
                        runOnMain(() -> {
                            if (part.location() != null) {
                                rememberLoadedContainerSource(part.location(), part.containerKey());
                            }
                            if (this.plugin.isAdvancedLoggingEnabled()) {
                                this.plugin.logAdvanced(
                                    "Saved personal loot inventory: player=%s, sourceKey=%s, location=%s, slots=%d, items=[%s]",
                                    LogDescriptions.player(playerName, holder.playerId()),
                                    part.containerKey(),
                                    LogDescriptions.location(part.location()),
                                    part.size(),
                                    LogDescriptions.items(contents)
                                );
                            }
                        });
                    }
                });
            if (part.location() != null) {
                closeContainerLid(part.containerKey(), part.location());
            }
        }
    }

    void openMinecartContainer(
        final Player player,
        final StorageMinecart minecart,
        final LootTable lootTable
    ) {
        String containerKey = entityContainerKey(minecart.getUniqueId());
        int size = minecart.getInventory().getSize();
        long openGeneration = beginContainerOpen(player);
        loadOrGenerate(
            player,
            containerKey,
            minecart.getLocation(),
            lootTable,
            size,
            "CHEST_MINECART entityUuid=" + minecart.getUniqueId(),
            () -> isCurrentContainerOpen(player, openGeneration) && canStillOpen(player, minecart.getLocation())
                && minecart.isValid() && isManagedNaturalLootMinecart(minecart),
            contents -> {
                PerPlayerLootInventoryHolder holder = new PerPlayerLootInventoryHolder(
                    player.getUniqueId(),
                    List.of(new InventoryPart(containerKey, null, 0, size))
                );
                Component title = minecart.customName();
                if (title == null) title = Component.translatable("entity.minecraft.chest_minecart");
                Inventory inventory = Bukkit.createInventory(holder, size, title);
                inventory.setContents(contents);
                player.openInventory(inventory);
            }
        );
    }

    private void openSingleContainer(
        final Player player,
        final LootContainerPart part
    ) {
        Block block = part.block();
        if (!(block.getState() instanceof Container container)) {
            return;
        }

        int size = container.getInventory().getSize();
        long openGeneration = beginContainerOpen(player);
        loadOrGeneratePart(player, part, size, () -> isCurrentContainerOpen(player, openGeneration)
            && canStillOpen(player, block.getLocation())
            && isManagedNaturalLootContainer(block.getState()),
            contents -> {
                BlockState currentState = block.getState();
                if (!(currentState instanceof Container currentContainer)) return;
                PerPlayerLootInventoryHolder holder = new PerPlayerLootInventoryHolder(
                    player.getUniqueId(),
                    List.of(new InventoryPart(part.containerKey(), block.getLocation(), 0, size))
                );
                Component customName = currentContainer instanceof Nameable nameable ? nameable.customName() : null;
                Inventory inventory = customName == null
                    ? Bukkit.createInventory(holder, size, Component.translatable(containerTitleKey(block.getType(), size)))
                    : Bukkit.createInventory(holder, size, customName);
                inventory.setContents(contents);
                player.openInventory(inventory);
                openContainerLid(part.containerKey(), block.getLocation());
            }
        );
    }

    private void openNaturalDoubleChest(final Player player, final List<LootContainerPart> parts) {
        ItemStack[][] loaded = new ItemStack[parts.size()][];
        AtomicInteger remaining = new AtomicInteger(parts.size());
        long openGeneration = beginContainerOpen(player);
        BooleanSupplier valid = () -> isCurrentContainerOpen(player, openGeneration)
            && canStillOpen(player, parts.getFirst().block().getLocation())
            && parts.stream().allMatch(part -> isManagedNaturalLootContainer(part.block().getState()));
        for (int index = 0; index < parts.size(); index++) {
            int partIndex = index;
            loadOrGeneratePart(player, parts.get(index), 27, valid, contents -> {
                loaded[partIndex] = contents;
                if (remaining.decrementAndGet() != 0 || !valid.getAsBoolean()) return;
                PerPlayerLootInventoryHolder holder = new PerPlayerLootInventoryHolder(
                    player.getUniqueId(),
                    List.of(
                        new InventoryPart(parts.get(0).containerKey(), parts.get(0).block().getLocation(), 0, 27),
                        new InventoryPart(parts.get(1).containerKey(), parts.get(1).block().getLocation(), 27, 27)
                    )
                );
                Component customName = customName(parts.get(0).block());
                if (customName == null) customName = customName(parts.get(1).block());
                Inventory combined = customName == null
                    ? Bukkit.createInventory(holder, 54, Component.translatable("container.chestDouble"))
                    : Bukkit.createInventory(holder, 54, customName);
                for (int loadedIndex = 0; loadedIndex < loaded.length; loadedIndex++) {
                    for (int slot = 0; slot < loaded[loadedIndex].length; slot++) {
                        combined.setItem(loadedIndex * 27 + slot, loaded[loadedIndex][slot]);
                    }
                }
                player.openInventory(combined);
                for (LootContainerPart loadedPart : parts) {
                    openContainerLid(loadedPart.containerKey(), loadedPart.block().getLocation());
                }
            });
        }
    }

    private void loadOrGeneratePart(
        final Player player,
        final LootContainerPart part,
        final int size,
        final BooleanSupplier sourceValid,
        final Consumer<ItemStack[]> completion
    ) {
        loadOrGenerate(
            player,
            part.containerKey(),
            part.block().getLocation(),
            part.lootTable(),
            size,
            part.block().getType().name(),
            sourceValid,
            completion
        );
    }

    private void loadOrGenerate(
        final Player player,
        final String containerKey,
        final Location location,
        final LootTable lootTable,
        final int size,
        final String sourceDescription,
        final BooleanSupplier sourceValid,
        final Consumer<ItemStack[]> completion
    ) {
        UUID playerId = player.getUniqueId();
        this.storage.getContainerInventoryDataAsync(containerKey, playerId).whenComplete((serialized, readFailure) ->
            runOnMain(() -> {
                if (readFailure != null) {
                    this.plugin.getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not load personal loot inventory " + containerKey + ".",
                        readFailure
                    );
                    return;
                }
                if (!sourceValid.getAsBoolean()) return;
                if (serialized != null) {
                    ItemStack[] contents;
                    try {
                        contents = this.storage.decodeContainerInventory(serialized, size);
                    } catch (RuntimeException exception) {
                        this.plugin.getLogger().log(
                            java.util.logging.Level.SEVERE,
                            "Could not decode personal loot inventory " + containerKey + ".",
                            exception
                        );
                        return;
                    }
            if (this.plugin.isAdvancedLoggingEnabled()) {
                this.plugin.logAdvanced(
                    "Opened stored personal loot inventory: player=%s, source=%s, sourceKey=%s, location=%s, "
                        + "lootTable=%s, slots=%d, items=[%s]",
                    LogDescriptions.player(player),
                    sourceDescription,
                    containerKey,
                    LogDescriptions.location(location),
                    lootTable.getKey(),
                    size,
                    LogDescriptions.items(contents)
                );
            }
                    completion.accept(contents);
                    return;
                }

                Inventory generated = Bukkit.createInventory(null, size);
                lootTable.fillInventory(
                    generated,
                    new Random(seed(containerKey, playerId)),
                    new LootContext.Builder(location).killer(player).build()
                );
                ItemStack[] contents = generated.getContents();
                this.storage.setContainerInventoryAsync(containerKey, playerId, contents)
                    .whenComplete((ignored, writeFailure) -> runOnMain(() -> {
                        if (writeFailure != null) {
                            this.plugin.getLogger().log(
                                java.util.logging.Level.SEVERE,
                                "Could not create personal loot inventory " + containerKey + ".",
                                writeFailure
                            );
                            return;
                        }
                        if (!sourceValid.getAsBoolean()) return;
                        if (!containerKey.startsWith("entity;")) {
                            rememberLoadedContainerSource(location, containerKey);
                        }
                        if (this.plugin.isAdvancedLoggingEnabled()) {
                            this.plugin.logAdvanced(
                "Created personal loot inventory: player=%s, source=%s, sourceKey=%s, location=%s, "
                    + "lootTable=%s, slots=%d, items=[%s]",
                LogDescriptions.player(player),
                sourceDescription,
                containerKey,
                LogDescriptions.location(location),
                lootTable.getKey(),
                size,
                LogDescriptions.items(contents)
            );
                        }
                        completion.accept(contents);
                    }));
            })
        );
    }

    private void runOnMain(final Runnable task) {
        if (this.plugin.isEnabled()) Bukkit.getScheduler().runTask(this.plugin, task);
    }

    private long beginContainerOpen(final Player player) {
        long generation = ++this.nextContainerOpenGeneration;
        this.containerOpenGenerations.put(player.getUniqueId(), generation);
        return generation;
    }

    private boolean isCurrentContainerOpen(final Player player, final long generation) {
        return player.isOnline()
            && Long.valueOf(generation).equals(this.containerOpenGenerations.get(player.getUniqueId()));
    }

    private static boolean canStillOpen(final Player player, final Location source) {
        World sourceWorld = source.getWorld();
        return sourceWorld != null && player.getWorld().equals(sourceWorld)
            && player.getLocation().distanceSquared(source) <= 64.0;
    }

    private static Component customName(final Block block) {
        BlockState state = block.getState();
        return state instanceof Nameable nameable ? nameable.customName() : null;
    }

    private void openContainerLid(final String containerKey, final Location location) {
        int openCount = this.openContainerCounts.getOrDefault(containerKey, 0);
        this.openContainerCounts.put(containerKey, openCount + 1);

        if (openCount > 0) {
            return;
        }

        BlockState state = location.getBlock().getState();
        if (state instanceof Lidded lidded) {
            lidded.open();
        }
    }

    private void closeContainerLid(final String containerKey, final Location location) {
        int openCount = this.openContainerCounts.getOrDefault(containerKey, 0) - 1;
        if (openCount > 0) {
            this.openContainerCounts.put(containerKey, openCount);
            return;
        }

        this.openContainerCounts.remove(containerKey);

        BlockState state = location.getBlock().getState();
        if (state instanceof Lidded lidded) {
            lidded.close();
        }
    }

    void closeVirtualContainerViews(final String containerKey) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory topInventory = player.getOpenInventory().getTopInventory();
            if (!(topInventory.getHolder() instanceof PerPlayerLootInventoryHolder holder)) {
                continue;
            }
            if (holder.parts().stream().anyMatch(part -> part.containerKey().equals(containerKey))) {
                player.closeInventory();
            }
        }
    }

    private boolean isProtectedLootContainerInventory(final Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest doubleChest) {
            boolean leftProtected = isProtectedLootContainerHolder(doubleChest.getLeftSide());
            boolean rightProtected = isProtectedLootContainerHolder(doubleChest.getRightSide());
            return leftProtected || rightProtected;
        }

        return isProtectedLootContainerHolder(holder);
    }

    private boolean isProtectedLootContainerHolder(final InventoryHolder holder) {
        if (holder instanceof StorageMinecart minecart) {
            LootTable lootTable = minecart.getLootTable();
            if (lootTable != null) {
                tagLootMinecart(minecart, lootTable);
                return true;
            }
            String containerKey = entityContainerKey(minecart.getUniqueId());
            if (hasManagedLootMinecartTag(minecart)) {
                cleanupLostLootMinecart(minecart);
                return true;
            }
            return false;
        }

        if (!(holder instanceof BlockInventoryHolder blockHolder)) {
            return false;
        }

        BlockState state = blockHolder.getBlock().getState();
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            tagLootContainer(state, lootable.getLootTable());
            return true;
        }

        String key = containerKey(blockHolder.getBlock().getLocation());
        if (hasManagedLootContainerTag(state)
            || hasLoadedContainerSource(blockHolder.getBlock().getLocation(), key)) {
            cleanupLostLootContainer(blockHolder.getBlock());
            return true;
        }
        return false;
    }

    private void cleanupLostLootContainerData(final Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest doubleChest) {
            cleanupLostLootContainerData(doubleChest.getLeftSide());
            cleanupLostLootContainerData(doubleChest.getRightSide());
            return;
        }

        cleanupLostLootContainerData(holder);
    }

    private void cleanupLostLootContainerData(final InventoryHolder holder) {
        if (holder instanceof StorageMinecart minecart) {
            LootTable lootTable = minecart.getLootTable();
            if (lootTable != null) {
                tagLootMinecart(minecart, lootTable);
            } else {
                cleanupLostLootMinecart(minecart);
            }
            return;
        }

        if (!(holder instanceof BlockInventoryHolder blockHolder)) {
            return;
        }

        Block block = blockHolder.getBlock();
        BlockState state = block.getState();
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            tagLootContainer(state, lootable.getLootTable());
            return;
        }

        cleanupLostLootContainer(block);
    }

    private ChestResolution resolveChest(final Block clickedBlock) {
        BlockState clickedState = clickedBlock.getState();
        LootContainerPart clickedPart = resolveLootContainerPart(clickedBlock);
        if (!(clickedState.getBlockData() instanceof Chest chestData)
            || chestData.getType() == Chest.Type.SINGLE) {
            return clickedPart == null
                ? new ChestResolution(ChestResolutionType.UNMANAGED, List.of(clickedBlock), List.of())
                : new ChestResolution(ChestResolutionType.SINGLE, List.of(clickedBlock), List.of(clickedPart));
        }

        if (!(clickedState instanceof Container container)
            || !(container.getInventory().getHolder(false) instanceof DoubleChest doubleChest)
            || !(doubleChest.getLeftSide() instanceof BlockInventoryHolder leftHolder)
            || !(doubleChest.getRightSide() instanceof BlockInventoryHolder rightHolder)) {
            return new ChestResolution(ChestResolutionType.UNMANAGED, List.of(clickedBlock), List.of());
        }

        Block leftBlock = leftHolder.getBlock();
        Block rightBlock = rightHolder.getBlock();
        LootContainerPart leftPart = resolveLootContainerPart(leftBlock);
        LootContainerPart rightPart = resolveLootContainerPart(rightBlock);
        List<Block> blocks = List.of(leftBlock, rightBlock);

        if (leftPart != null && rightPart != null) {
            return new ChestResolution(
                ChestResolutionType.NATURAL_DOUBLE,
                blocks,
                List.of(leftPart, rightPart)
            );
        }
        if (leftPart != null || rightPart != null) {
            return new ChestResolution(
                ChestResolutionType.MIXED_DOUBLE,
                blocks,
                List.of(leftPart != null ? leftPart : rightPart)
            );
        }
        return new ChestResolution(ChestResolutionType.UNMANAGED, blocks, List.of());
    }

    private LootContainerPart resolveSinglePart(final Block block) {
        LootContainerPart part = resolveLootContainerPart(block);
        if (part == null) {
            throw new IllegalStateException("Managed loot chest lost its loot table while being separated.");
        }
        return part;
    }

    private LootContainerPart resolveLootContainerPart(final Block block) {
        BlockState state = block.getState();
        if (!(state instanceof Container) || !(state instanceof Lootable lootable)) {
            return null;
        }

        LootTable lootTable = lootable.getLootTable();
        if (lootTable == null) {
            return null;
        }
        tagLootContainer(state, lootTable);
        return new LootContainerPart(block, lootTable, containerKey(block.getLocation()));
    }

    private boolean isManagedNaturalLootContainer(final BlockState state) {
        if (!(state instanceof Container) || !(state instanceof Lootable lootable)) {
            return false;
        }

        LootTable lootTable = lootable.getLootTable();
        if (lootTable != null) {
            tagLootContainer(state, lootTable);
            return true;
        }
        return hasManagedLootContainerTag(state);
    }

    private void tagLootContainer(final BlockState state, final LootTable lootTable) {
        if (!(state instanceof TileState tileState)) {
            return;
        }

        String lootTableKey = lootTable.getKey().toString();
        long seed = state instanceof Lootable lootable ? lootable.getSeed() : 0L;
        PersistentDataContainer dataContainer = tileState.getPersistentDataContainer();
        if (lootTableKey.equals(dataContainer.get(this.lootContainerTableKey, PersistentDataType.STRING))
            && (!(state instanceof Lootable)
                || dataContainer.getOrDefault(this.lootContainerSeedKey, PersistentDataType.LONG, 0L) == seed)) {
            return;
        }

        // Persist enough information to keep protecting the block after vanilla clears its loot table.
        dataContainer.set(this.lootContainerTableKey, PersistentDataType.STRING, lootTableKey);
        if (state instanceof Lootable) {
            dataContainer.set(this.lootContainerSeedKey, PersistentDataType.LONG, seed);
        }
        tileState.update(false, false);
    }

    private void tagLootMinecarts(final Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof StorageMinecart minecart && minecart.getLootTable() != null) {
                tagLootMinecart(minecart, minecart.getLootTable());
            }
        }
    }

    void tagLootMinecart(final StorageMinecart minecart, final LootTable lootTable) {
        PersistentDataContainer dataContainer = minecart.getPersistentDataContainer();
        String lootTableKey = lootTable.getKey().toString();
        long seed = minecart.getSeed();
        if (lootTableKey.equals(dataContainer.get(this.lootContainerTableKey, PersistentDataType.STRING))
            && dataContainer.getOrDefault(this.lootContainerSeedKey, PersistentDataType.LONG, 0L) == seed) {
            return;
        }
        dataContainer.set(this.lootContainerTableKey, PersistentDataType.STRING, lootTableKey);
        dataContainer.set(this.lootContainerSeedKey, PersistentDataType.LONG, seed);
    }

    private boolean hasManagedLootMinecartTag(final StorageMinecart minecart) {
        return minecart.getPersistentDataContainer().has(this.lootContainerTableKey, PersistentDataType.STRING);
    }

    boolean isManagedNaturalLootMinecart(final StorageMinecart minecart) {
        LootTable lootTable = minecart.getLootTable();
        if (lootTable != null) {
            tagLootMinecart(minecart, lootTable);
            return true;
        }
        return hasManagedLootMinecartTag(minecart);
    }

    void cleanupLostLootMinecart(final StorageMinecart minecart) {
        if (minecart.getLootTable() != null) {
            tagLootMinecart(minecart, minecart.getLootTable());
            return;
        }
        String containerKey = entityContainerKey(minecart.getUniqueId());
        if (!hasManagedLootMinecartTag(minecart)) {
            return;
        }
        minecart.getPersistentDataContainer().remove(this.lootContainerTableKey);
        minecart.getPersistentDataContainer().remove(this.lootContainerSeedKey);
        this.storage.removeContainerData(containerKey);
    }

    private boolean hasManagedLootContainerTag(final BlockState state) {
        if (!(state instanceof TileState tileState)) {
            return false;
        }
        return tileState.getPersistentDataContainer().has(this.lootContainerTableKey, PersistentDataType.STRING);
    }

    private void cleanupLostLootContainer(final Block block) {
        BlockState state = block.getState();
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            tagLootContainer(state, lootable.getLootTable());
            return;
        }

        String containerKey = containerKey(block.getLocation());
        if (!hasManagedLootContainerTag(state)
            && !hasLoadedContainerSource(block.getLocation(), containerKey)) {
            return;
        }

        // Losing plugin management must never modify the container's physical inventory.
        // Only remove PerPlayerLoot metadata and the corresponding database records.
        if (state instanceof TileState tileState) {
            tileState.getPersistentDataContainer().remove(this.lootContainerTableKey);
            tileState.getPersistentDataContainer().remove(this.lootContainerSeedKey);
            tileState.update(false, false);
        }
        removeStoredContainerData(containerKey, block.getLocation());
        this.plugin.logAdvanced(
            "Loot container left personal management: block=%s, sourceKey=%s, location=%s; personal data removed",
            block.getType(),
            containerKey,
            LogDescriptions.location(block.getLocation())
        );
    }

    private void cleanupDestroyedLootContainer(final Block block) {
        BlockState state = block.getState();
        String containerKey = containerKey(block.getLocation());
        if (isManagedNaturalLootContainer(state)
            || hasLoadedContainerSource(block.getLocation(), containerKey)) {
            closeVirtualContainerViews(containerKey);
            removeStoredContainerData(containerKey, block.getLocation());
            this.plugin.logAdvanced(
                "Natural loot container removed by explosion: block=%s, sourceKey=%s, location=%s; personal data removed",
                block.getType(),
                containerKey,
                LogDescriptions.location(block.getLocation())
            );
        }
    }

    private void protectNaturalLootContainersFromExplosion(final List<Block> blocks) {
        if (!isContainerDestructionProtectionEnabled()
            || this.plugin.settings().containers().allowDestruction()) {
            return;
        }
        blocks.removeIf(block -> {
            String key = containerKey(block.getLocation());
            boolean protectedContainer = isManagedNaturalLootContainer(block.getState())
                || hasLoadedContainerSource(block.getLocation(), key);
            if (protectedContainer) {
                this.plugin.logAdvanced(
                    "Protected natural loot container from explosion: block=%s, sourceKey=%s, location=%s",
                    block.getType(),
                    key,
                    LogDescriptions.location(block.getLocation())
                );
            }
            return protectedContainer;
        });
    }

    boolean canDestroyNaturalLootContainer(final Player player) {
        return ProtectionPolicy.canDestroyContainer(
            this.plugin.settings().containers(),
            player.hasPermission("perplayerloot.destroy.containers")
        );
    }

    private boolean canMergeNaturalLootContainer(final Player player) {
        return ProtectionPolicy.canMergeContainer(
            this.plugin.settings().containers(),
            player.hasPermission("perplayerloot.merge.containers")
        );
    }

    boolean isContainerDestructionProtectionEnabled() {
        return this.plugin.settings().containers().protectDestruction();
    }

    private static String containerKey(final Location location) {
        World world = location.getWorld();
        String worldId = world == null ? "unknown" : world.getUID().toString();
        return worldId + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }

    private boolean hasLoadedContainerSource(final Location location, final String containerKey) {
        World world = location.getWorld();
        if (world == null) return false;
        ChunkKey key = new ChunkKey(
            world.getUID(),
            Math.floorDiv(location.getBlockX(), 16),
            Math.floorDiv(location.getBlockZ(), 16)
        );
        Set<String> sources = this.loadedContainerSources.get(key);
        return sources != null && sources.contains(containerKey);
    }

    private void rememberLoadedContainerSource(final Location location, final String containerKey) {
        World world = location.getWorld();
        if (world == null) return;
        ChunkKey key = new ChunkKey(
            world.getUID(),
            Math.floorDiv(location.getBlockX(), 16),
            Math.floorDiv(location.getBlockZ(), 16)
        );
        if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) return;
        this.loadedContainerSources.computeIfAbsent(key, ignored -> new HashSet<>()).add(containerKey);
    }

    private void removeStoredContainerData(final String containerKey, final Location location) {
        this.storage.removeContainerDataAsync(List.of(containerKey)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                this.plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not remove stored container data " + containerKey + ".",
                    failure
                );
                return;
            }
            runOnMain(() -> {
                World world = location.getWorld();
                if (world == null) return;
                ChunkKey key = new ChunkKey(
                    world.getUID(),
                    Math.floorDiv(location.getBlockX(), 16),
                    Math.floorDiv(location.getBlockZ(), 16)
                );
                Set<String> sources = this.loadedContainerSources.get(key);
                if (sources != null) sources.remove(containerKey);
            });
        });
    }

    static String entityContainerKey(final UUID entityId) {
        return "entity;" + entityId;
    }

    private static long seed(final String containerKey, final UUID playerId) {
        long result = 1125899906842597L;
        result = 31 * result + containerKey.hashCode();
        result = 31 * result + playerId.getMostSignificantBits();
        result = 31 * result + playerId.getLeastSignificantBits();
        return result;
    }

    private static String containerTitleKey(final Material material, final int size) {
        return switch (material) {
            case BARREL -> "container.barrel";
            case CHEST, TRAPPED_CHEST -> "container.chest";
            case DISPENSER -> "container.dispenser";
            case DROPPER -> "container.dropper";
            case HOPPER -> "container.hopper";
            case SHULKER_BOX, WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX, LIGHT_BLUE_SHULKER_BOX,
                YELLOW_SHULKER_BOX, LIME_SHULKER_BOX, PINK_SHULKER_BOX, GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX,
                CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX, BROWN_SHULKER_BOX, GREEN_SHULKER_BOX,
                RED_SHULKER_BOX, BLACK_SHULKER_BOX -> "container.shulkerBox";
            default -> "container.generic_9x" + Math.max(1, size / 9);
        };
    }

    private enum ChestResolutionType {
        SINGLE,
        NATURAL_DOUBLE,
        MIXED_DOUBLE,
        UNMANAGED
    }

    private record ChestResolution(
        ChestResolutionType type,
        List<Block> blocks,
        List<LootContainerPart> lootParts
    ) {
    }

    private record LootContainerPart(
        Block block,
        LootTable lootTable,
        String containerKey
    ) {
    }
}

