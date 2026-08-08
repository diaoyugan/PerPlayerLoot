package top.diaoyugan.perPlayerLoot.listener;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
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
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropManager;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;
import top.diaoyugan.perPlayerLoot.storage.LootStorage.StoredContainer;

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
    private final NamespacedKey playerPlacedFrameKey;
    private final NamespacedKey legacyPlayerPlacedFrameKey;
    private final NamespacedKey lootContainerTableKey;
    private final NamespacedKey lootContainerSeedKey;
    private final PersonalDropManager personalDropManager;
    private final Map<String, Integer> openContainerCounts = new HashMap<>();

    public LootListener(
        final PerPlayerLoot plugin,
        final LootStorage storage,
        final NamespacedKey playerPlacedFrameKey,
        final NamespacedKey legacyPlayerPlacedFrameKey,
        final PersonalDropManager personalDropManager
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.playerPlacedFrameKey = playerPlacedFrameKey;
        this.legacyPlayerPlacedFrameKey = legacyPlayerPlacedFrameKey;
        this.lootContainerTableKey = new NamespacedKey(plugin, "loot_container_table");
        this.lootContainerSeedKey = new NamespacedKey(plugin, "loot_container_seed");
        this.personalDropManager = personalDropManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        String containerKey = containerKey(event.getBlock().getLocation());
        if (!isManagedNaturalLootContainer(state) && !this.storage.hasContainerData(containerKey)) {
            return;
        }

        if (canDestroyNaturalLootContainer(event.getPlayer())) {
            this.storage.removeContainerData(containerKey);
            return;
        }

        event.setCancelled(true);
        Messages.send(event.getPlayer(), Messages.NO_CONTAINER_DESTROY_PERMISSION);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestPlace(final BlockPlaceEvent event) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            cleanupDestroyedLootContainer(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            cleanupDestroyedLootContainer(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        // Player-placed frames must never be treated as natural loot frames.
        event.getEntity().getPersistentDataContainer().set(
            this.playerPlacedFrameKey,
            PersistentDataType.BYTE,
            TRUE
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClaimedFrameInteract(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame itemFrame) || !isNaturalLootFrame(itemFrame)) {
            return;
        }
        if (!this.personalDropManager.hasClaimedOrActiveDrop(event.getPlayer(), itemFrame)) {
            return;
        }

        // The client sees claimed frames as empty, but the server still has the real item.
        // Cancel here so right-clicking cannot rotate it or place a player item into it.
        event.setCancelled(true);
        ItemStack handItem = event.getPlayer().getInventory().getItem(event.getHand());
        if (handItem != null && !handItem.getType().isAir()) {
            Messages.send(event.getPlayer(), Messages.FRAME_ALREADY_CLAIMED_CANNOT_PLACE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame itemFrame)) {
            return;
        }
        if (itemFrame.getItem().getType() != Material.AIR) {
            return;
        }

        ItemStack handItem = event.getPlayer().getInventory().getItem(event.getHand());
        if (handItem == null || handItem.getType() == Material.AIR) {
            return;
        }

        // Mark frames when a player inserts an item into an existing empty frame.
        itemFrame.getPersistentDataContainer().set(
            this.playerPlacedFrameKey,
            PersistentDataType.BYTE,
            TRUE
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(final HangingBreakByEntityEvent event) {
        Hanging entity = event.getEntity();
        if (!(entity instanceof ItemFrame itemFrame) || !isNaturalLootFrame(itemFrame)) {
            return;
        }

        if (event.getRemover() instanceof Player player) {
            if (canDestroyNaturalLootFrame(player)) {
                return;
            }
            event.setCancelled(true);
            Messages.send(player, Messages.NO_FRAME_DESTROY_PERMISSION);
            return;
        }

        if (!this.plugin.getConfig().getBoolean("allow-destroy-natural-loot-frames", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameDamaged(final EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ItemFrame itemFrame) || !isNaturalLootFrame(itemFrame)) {
            return;
        }

        if (event.getDamager() instanceof Player player) {
            if (canDestroyNaturalLootFrame(player)) {
                return;
            }
            event.setCancelled(true);
            this.personalDropManager.createDrop(player, itemFrame);
            return;
        }

        if (!this.plugin.getConfig().getBoolean("allow-destroy-natural-loot-frames", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(final InventoryMoveItemEvent event) {
        if (!this.plugin.getConfig().getBoolean("protect-natural-loot-containers-from-hoppers", true)) {
            cleanupLostLootContainerData(event.getSource());
            cleanupLostLootContainerData(event.getDestination());
            return;
        }

        if (isProtectedLootContainerInventory(event.getSource())
            || isProtectedLootContainerInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperInventorySearch(final HopperInventorySearchEvent event) {
        if (!this.plugin.getConfig().getBoolean("protect-natural-loot-containers-from-hoppers", true)) {
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
        cleanupOrphanContainerData(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkPopulate(final ChunkPopulateEvent event) {
        // Generated tile entities can be added after ChunkLoadEvent has already fired.
        tagLootContainers(event.getChunk());
    }

    public void tagLoadedLootContainers() {
        // POSTWORLD startup can see chunks that loaded before this listener was registered.
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                tagLootContainers(chunk);
                cleanupOrphanContainerData(chunk);
            }
        }
    }

    public int cleanupOrphanContainerDataForLoadedChunks() {
        int removed = 0;
        for (StoredContainer container : this.storage.getAllContainerData()) {
            World world = Bukkit.getWorld(container.worldId());
            if (world == null) {
                this.storage.removeContainerData(container.containerKey());
                removed++;
                continue;
            }
            if (!world.isChunkLoaded(container.chunkX(), container.chunkZ())) {
                continue;
            }
            if (removeOrphanContainerData(world, container)) {
                removed++;
            }
        }
        return removed;
    }

    private void cleanupOrphanContainerData(final Chunk chunk) {
        for (StoredContainer container : this.storage.getContainerDataInChunk(
            chunk.getWorld().getUID(),
            chunk.getX(),
            chunk.getZ()
        )) {
            removeOrphanContainerData(chunk.getWorld(), container);
        }
    }

    private boolean removeOrphanContainerData(final World world, final StoredContainer container) {
        if (container.blockY() < world.getMinHeight() || container.blockY() >= world.getMaxHeight()) {
            this.storage.removeContainerData(container.containerKey());
            return true;
        }

        Block block = world.getBlockAt(container.blockX(), container.blockY(), container.blockZ());
        if (isManagedNaturalLootContainer(block.getState())) {
            return false;
        }

        this.storage.removeContainerData(container.containerKey());
        return true;
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
        for (InventoryPart part : holder.parts()) {
            ItemStack[] contents = Arrays.copyOfRange(
                combinedContents,
                part.offset(),
                part.offset() + part.size()
            );
            this.storage.setContainerInventory(part.containerKey(), holder.playerId(), contents);
            closeContainerLid(part.containerKey(), part.location());
        }
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
        PerPlayerLootInventoryHolder holder = new PerPlayerLootInventoryHolder(
            player.getUniqueId(),
            List.of(new InventoryPart(part.containerKey(), block.getLocation(), 0, size))
        );
        Component customName = container instanceof Nameable nameable ? nameable.customName() : null;
        Inventory inventory = customName == null
            ? Bukkit.createInventory(holder, size, Component.translatable(containerTitleKey(block.getType(), size)))
            : Bukkit.createInventory(holder, size, customName);

        inventory.setContents(loadOrGeneratePart(player, part, size));

        player.openInventory(inventory);
        openContainerLid(part.containerKey(), block.getLocation());
    }

    private void openNaturalDoubleChest(final Player player, final List<LootContainerPart> parts) {
        PerPlayerLootInventoryHolder holder = new PerPlayerLootInventoryHolder(
            player.getUniqueId(),
            List.of(
                new InventoryPart(parts.get(0).containerKey(), parts.get(0).block().getLocation(), 0, 27),
                new InventoryPart(parts.get(1).containerKey(), parts.get(1).block().getLocation(), 27, 27)
            )
        );
        Component customName = customName(parts.get(0).block());
        if (customName == null) {
            customName = customName(parts.get(1).block());
        }
        Inventory combined = customName == null
            ? Bukkit.createInventory(holder, 54, Component.translatable("container.chestDouble"))
            : Bukkit.createInventory(holder, 54, customName);

        for (int index = 0; index < parts.size(); index++) {
            ItemStack[] contents = loadOrGeneratePart(player, parts.get(index), 27);
            int offset = index * 27;
            for (int slot = 0; slot < contents.length; slot++) {
                combined.setItem(offset + slot, contents[slot]);
            }
        }

        player.openInventory(combined);
        for (LootContainerPart part : parts) {
            openContainerLid(part.containerKey(), part.block().getLocation());
        }
    }

    private ItemStack[] loadOrGeneratePart(
        final Player player,
        final LootContainerPart part,
        final int size
    ) {
        UUID playerId = player.getUniqueId();
        if (this.storage.hasContainerInventory(part.containerKey(), playerId)) {
            return this.storage.getContainerInventory(part.containerKey(), playerId, size);
        }

        Inventory generated = Bukkit.createInventory(null, size);
        part.lootTable().fillInventory(
            generated,
            new Random(seed(part.containerKey(), playerId)),
            new LootContext.Builder(part.block().getLocation()).killer(player).build()
        );
        ItemStack[] contents = generated.getContents();
        this.storage.setContainerInventory(part.containerKey(), playerId, contents);
        return contents;
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

    private boolean isNaturalLootFrame(final ItemFrame itemFrame) {
        if (itemFrame.getItem().getType() == Material.AIR) {
            return false;
        }

        PersistentDataContainer dataContainer = itemFrame.getPersistentDataContainer();
        if (dataContainer.has(this.playerPlacedFrameKey, PersistentDataType.BYTE)
            || dataContainer.has(this.legacyPlayerPlacedFrameKey, PersistentDataType.BYTE)) {
            return false;
        }

        return lootFrameMaterials().contains(itemFrame.getItem().getType());
    }

    private Set<Material> lootFrameMaterials() {
        Set<Material> materials = new HashSet<>();
        FileConfiguration config = this.plugin.getConfig();
        for (String materialName : config.getStringList("loot-frame-materials")) {
            Material material = Material.matchMaterial(materialName);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
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
        if (!(holder instanceof BlockInventoryHolder blockHolder)) {
            return false;
        }

        BlockState state = blockHolder.getBlock().getState();
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            tagLootContainer(state, lootable.getLootTable());
            return true;
        }

        if (hasManagedLootContainerTag(state)
            || this.storage.hasContainerData(containerKey(blockHolder.getBlock().getLocation()))) {
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
        if (!hasManagedLootContainerTag(state) && !this.storage.hasContainerData(containerKey)) {
            return;
        }

        // Losing plugin management must never modify the container's physical inventory.
        // Only remove PerPlayerLoot metadata and the corresponding database records.
        if (state instanceof TileState tileState) {
            tileState.getPersistentDataContainer().remove(this.lootContainerTableKey);
            tileState.getPersistentDataContainer().remove(this.lootContainerSeedKey);
            tileState.update(false, false);
        }
        this.storage.removeContainerData(containerKey);
    }

    private void cleanupDestroyedLootContainer(final Block block) {
        BlockState state = block.getState();
        if (isManagedNaturalLootContainer(state) || this.storage.hasContainerData(containerKey(block.getLocation()))) {
            this.storage.removeContainerData(containerKey(block.getLocation()));
        }
    }

    private boolean canDestroyNaturalLootContainer(final Player player) {
        return this.plugin.getConfig().getBoolean("allow-destroy-natural-loot-containers", false)
            || player.hasPermission("perplayerloot.destroy.containers");
    }

    private boolean canMergeNaturalLootContainer(final Player player) {
        return this.plugin.getConfig().getBoolean("allow-merge-natural-loot-containers", false)
            || player.hasPermission("perplayerloot.merge.containers");
    }

    private boolean canDestroyNaturalLootFrame(final Player player) {
        return this.plugin.getConfig().getBoolean("allow-destroy-natural-loot-frames", false)
            || (player.isSneaking()
                && (this.plugin.getConfig().getBoolean("allow-sneak-destroy-natural-loot-frames", false)
                    || player.hasPermission("perplayerloot.destroy.frames")));
    }

    private static String containerKey(final Location location) {
        World world = location.getWorld();
        String worldId = world == null ? "unknown" : world.getUID().toString();
        return worldId + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
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

