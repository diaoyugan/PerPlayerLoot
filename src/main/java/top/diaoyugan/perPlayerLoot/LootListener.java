package top.diaoyugan.perPlayerLoot;

import java.util.HashMap;
import java.util.HashSet;
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
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.block.TileState;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

final class LootListener implements Listener {

    private static final byte TRUE = 1;

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final NamespacedKey playerPlacedFrameKey;
    private final NamespacedKey legacyPlayerPlacedFrameKey;
    private final NamespacedKey lootContainerTableKey;
    private final NamespacedKey lootContainerSeedKey;
    private final PersonalDropManager personalDropManager;
    private final Map<String, Integer> openContainerCounts = new HashMap<>();

    LootListener(
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

        Block block = event.getClickedBlock();
        BlockState state = block.getState();
        if (!(state instanceof Container container) || !(state instanceof Lootable lootable)) {
            return;
        }

        LootTable lootTable = lootable.getLootTable();
        if (lootTable == null) {
            cleanupLostLootContainer(block);
            return;
        }

        tagLootContainer(state, lootTable);
        event.setCancelled(true);
        openPerPlayerContainer(event.getPlayer(), block, container, lootTable);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        if (!isManagedNaturalLootContainer(state)) {
            return;
        }

        if (canDestroyNaturalLootContainer(event.getPlayer())) {
            this.storage.removeContainerData(containerKey(event.getBlock().getLocation()));
            return;
        }

        event.setCancelled(true);
        Messages.send(event.getPlayer(), Messages.NO_CONTAINER_DESTROY_PERMISSION);
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
            if (player.isSneaking() && canDestroyNaturalLootFrame(player)) {
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

        if (isManagedNaturalLootContainer(event.getSearchBlock().getState())) {
            event.setInventory(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(final ChunkLoadEvent event) {
        tagLootContainers(event.getChunk());
    }

    void tagLoadedLootContainers() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                tagLootContainers(chunk);
            }
        }
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

        this.storage.setContainerInventory(
            holder.containerKey(),
            holder.playerId(),
            event.getInventory().getContents()
        );
        closeContainerLid(holder.containerKey(), holder.containerLocation());
    }

    private void openPerPlayerContainer(
        final Player player,
        final Block block,
        final Container container,
        final LootTable lootTable
    ) {
        String containerKey = containerKey(block.getLocation());
        int size = container.getInventory().getSize();
        PerPlayerLootInventoryHolder holder = new PerPlayerLootInventoryHolder(
            containerKey,
            player.getUniqueId(),
            block.getLocation()
        );
        Component customName = container instanceof Nameable nameable ? nameable.customName() : null;
        Inventory inventory = customName == null
            ? Bukkit.createInventory(holder, size, Component.translatable(containerTitleKey(block.getType(), size)))
            : Bukkit.createInventory(holder, size, customName);

        if (this.storage.hasContainerInventory(containerKey, player.getUniqueId())) {
            inventory.setContents(this.storage.getContainerInventory(containerKey, player.getUniqueId(), size));
        } else {
            lootTable.fillInventory(
                inventory,
                new Random(seed(containerKey, player.getUniqueId())),
                new LootContext.Builder(block.getLocation()).killer(player).build()
            );
            this.storage.setContainerInventory(containerKey, player.getUniqueId(), inventory.getContents());
        }

        player.openInventory(inventory);
        openContainerLid(containerKey, block.getLocation());
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
        if (!(inventory.getHolder(false) instanceof BlockInventoryHolder holder)) {
            return false;
        }

        BlockState state = holder.getBlock().getState();
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            tagLootContainer(state, lootable.getLootTable());
            return true;
        }

        if (hasManagedLootContainerTag(state) || this.storage.hasContainerData(containerKey(holder.getBlock().getLocation()))) {
            cleanupLostLootContainer(holder.getBlock());
            return true;
        }
        return false;
    }

    private void cleanupLostLootContainerData(final Inventory inventory) {
        if (!(inventory.getHolder(false) instanceof BlockInventoryHolder holder)) {
            return;
        }

        Block block = holder.getBlock();
        BlockState state = block.getState();
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            tagLootContainer(state, lootable.getLootTable());
            return;
        }

        cleanupLostLootContainer(block);
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

        if (state instanceof Container container) {
            container.getInventory().clear();
        }
        if (state instanceof TileState tileState) {
            tileState.getPersistentDataContainer().remove(this.lootContainerTableKey);
            tileState.getPersistentDataContainer().remove(this.lootContainerSeedKey);
            tileState.update(false, false);
        }
        this.storage.removeContainerData(containerKey(block.getLocation()));
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

    private boolean canDestroyNaturalLootFrame(final Player player) {
        return this.plugin.getConfig().getBoolean("allow-destroy-natural-loot-frames", false)
            || player.hasPermission("perplayerloot.destroy.frames");
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
}
