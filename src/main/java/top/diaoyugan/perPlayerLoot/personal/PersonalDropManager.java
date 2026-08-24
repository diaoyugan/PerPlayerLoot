package top.diaoyugan.perPlayerLoot.personal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrushableBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.logging.LogDescriptions;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

public final class PersonalDropManager implements Listener {

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final PersonalEntityVisibilityAdapter visibilityAdapter;
    private final Map<UUID, PersonalDrop> activeDrops = new HashMap<>();
    private BukkitTask timeoutTask;

    public PersonalDropManager(
        final PerPlayerLoot plugin,
        final LootStorage storage,
        final PersonalEntityVisibilityAdapter visibilityAdapter
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.visibilityAdapter = visibilityAdapter;
    }

    /** Starts event handling and timeout maintenance after construction is complete. */
    public void start() {
        if (this.timeoutTask != null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.timeoutTask = Bukkit.getScheduler().runTaskTimer(
            this.plugin,
            this::expireTimedOutDrops,
            20L * 30L,
            20L * 30L
        );
    }

    public void close() {
        if (this.timeoutTask != null) {
            this.timeoutTask.cancel();
            this.timeoutTask = null;
        }
    }

    public boolean isEnabled() {
        return this.visibilityAdapter != null;
    }

    public boolean sendBrushablePreview(
        final Player player,
        final Location location,
        final BrushableBlock brushable,
        final BlockFace brushFace
    ) {
        return this.visibilityAdapter != null
            && this.visibilityAdapter.sendBrushablePreview(player, location, brushable, brushFace);
    }

    public boolean createDrop(final Player player, final ItemFrame itemFrame) {
        if (!isEnabled()) {
            Messages.send(player, Messages.PERSONAL_DROPS_DISABLED);
            this.plugin.logAdvanced(
                "Could not create item-frame personal drop because ProtocolLib support is unavailable: player=%s, frameUuid=%s, location=%s",
                LogDescriptions.player(player),
                itemFrame.getUniqueId(),
                LogDescriptions.location(itemFrame.getLocation())
            );
            return false;
        }

        UUID sourceId = itemFrame.getUniqueId();
        UUID playerId = player.getUniqueId();
        if (this.storage.hasClaimedFrame(sourceId, playerId) || hasActiveDrop(playerId, sourceId)) {
            Messages.send(player, Messages.FRAME_ALREADY_CLAIMED);
            this.visibilityAdapter.sendEmptyItemFrameToOwner(itemFrame, player);
            this.plugin.logAdvanced(
                "Rejected duplicate item-frame claim: player=%s, frameUuid=%s, location=%s",
                LogDescriptions.player(player),
                itemFrame.getUniqueId(),
                LogDescriptions.location(itemFrame.getLocation())
            );
            return false;
        }

        ItemStack loot = itemFrame.getItem().clone();
        if (loot.getType().isAir()) {
            return false;
        }

        Location spawnLocation = dropLocation(itemFrame);
        PersonalDrop pendingDrop = pendingDrop(player, sourceId, loot, spawnLocation);
        if (!this.storage.claimFrameWithDrop(sourceId, playerId, pendingDrop)) {
            Messages.send(player, Messages.FRAME_ALREADY_CLAIMED);
            return false;
        }
        this.visibilityAdapter.registerFrameClaim(sourceId, playerId);
        try {
            spawnAndLog(
                player,
                pendingDrop,
                dropVelocity(spawnLocation.getDirection()),
                "ITEM_FRAME entityUuid=" + itemFrame.getUniqueId()
            );
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(
                java.util.logging.Level.SEVERE,
                "Could not spawn a personal item-frame drop; it remains recoverable in storage.",
                exception
            );
        }
        this.visibilityAdapter.sendEmptyItemFrameToOwner(itemFrame, player);
        player.playSound(itemFrame.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 1.0F, 1.0F);
        return true;
    }

    public boolean createDrop(
        final Player player,
        final UUID sourceId,
        final ItemStack loot,
        final Location spawnLocation,
        final Vector velocity,
        final String sourceDescription
    ) {
        if (!isEnabled() || loot.getType().isAir()) {
            return false;
        }

        PersonalDrop storedDrop = pendingDrop(player, sourceId, loot, spawnLocation);
        this.storage.savePersonalDrop(storedDrop);
        spawnAndLog(player, storedDrop, velocity, sourceDescription);
        return true;
    }

    public boolean createBrushableDrops(
        final Player player,
        final String blockKey,
        final UUID sourceId,
        final List<ItemStack> loot,
        final Location spawnLocation,
        final Vector velocity,
        final String sourceDescription
    ) {
        List<PersonalDrop> pendingDrops = loot.stream()
            .filter(item -> item != null && !item.getType().isAir())
            .map(item -> pendingDrop(player, sourceId, item, spawnLocation))
            .toList();
        if (!isEnabled() || pendingDrops.isEmpty()) {
            return false;
        }
        if (!this.storage.claimBrushableWithDrops(blockKey, player.getUniqueId(), pendingDrops)) {
            return false;
        }
        for (PersonalDrop pendingDrop : pendingDrops) {
            try {
                spawnAndLog(player, pendingDrop, velocity, sourceDescription);
            } catch (RuntimeException exception) {
                this.plugin.getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not spawn a personal archaeology drop; it remains recoverable in storage.",
                    exception
                );
            }
        }
        return true;
    }

    private void spawnAndLog(
        final Player player,
        final PersonalDrop storedDrop,
        final Vector velocity,
        final String sourceDescription
    ) {
        PersonalDrop activeDrop = spawnDrop(storedDrop, player, velocity);
        this.plugin.logAdvanced(
            "Created personal item drop: player=%s, source=%s, sourceUuid=%s, dropEntityUuid=%s, location=%s, item=%s",
            LogDescriptions.player(player),
            sourceDescription,
            storedDrop.lootSourceId(),
            activeDrop.entityId(),
            LogDescriptions.location(storedDrop.spawnLocation()),
            LogDescriptions.item(storedDrop.itemStack())
        );
    }

    public boolean hasClaimedOrActiveDrop(final Player player, final ItemFrame itemFrame) {
        UUID sourceId = itemFrame.getUniqueId();
        UUID playerId = player.getUniqueId();
        return this.storage.hasClaimedFrame(sourceId, playerId) || hasActiveDrop(playerId, sourceId);
    }

    public void restoreOnlinePlayerDrops() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreDrops(player);
            if (this.visibilityAdapter != null) {
                this.visibilityAdapter.resendClaimedFrameViews(player);
            }
        }
    }

    public void recoverAllActiveDrops() {
        for (PersonalDrop drop : List.copyOf(this.activeDrops.values())) {
            recoverDrop(drop, true);
        }
        for (PersonalDrop drop : this.storage.getPersonalDrops(PersonalDropState.ACTIVE)) {
            this.storage.setPersonalDropState(drop.entityId(), PersonalDropState.RECOVERED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(final EntityPickupItemEvent event) {
        PersonalDrop drop = this.activeDrops.get(event.getItem().getUniqueId());
        if (drop == null) {
            return;
        }

        if (!(event.getEntity() instanceof Player player) || !drop.ownerId().equals(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItemMonitor(final EntityPickupItemEvent event) {
        PersonalDrop drop = this.activeDrops.get(event.getItem().getUniqueId());
        if (drop == null) {
            return;
        }

        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!event.getItem().isValid() || event.getItem().isDead()) {
                markPickedUp(drop.entityId());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryPickupItem(final InventoryPickupItemEvent event) {
        if (this.activeDrops.containsKey(event.getItem().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageEvent event) {
        if (this.activeDrops.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityCombust(final EntityCombustEvent event) {
        if (this.activeDrops.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        recoverDrops(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            restoreDrops(event.getPlayer());
            if (this.visibilityAdapter != null) {
                this.visibilityAdapter.resendClaimedFrameViews(event.getPlayer());
            }
        }, 20L);
    }

    private PersonalDrop spawnDrop(final PersonalDrop storedDrop, final Player owner) {
        return spawnDrop(storedDrop, owner, dropVelocity(storedDrop.spawnLocation().getDirection()));
    }

    private PersonalDrop spawnDrop(final PersonalDrop storedDrop, final Player owner, final Vector velocity) {
        Item item = storedDrop.spawnLocation().getWorld().dropItem(storedDrop.spawnLocation(), storedDrop.itemStack(), droppedItem -> {
            droppedItem.setOwner(owner.getUniqueId());
            droppedItem.setThrower(owner.getUniqueId());
            droppedItem.setCanMobPickup(false);
            droppedItem.setPickupDelay(10);
            droppedItem.setWillAge(false);
            droppedItem.setUnlimitedLifetime(true);
            droppedItem.setVelocity(velocity.clone());
        });

        PersonalDrop activeDrop = storedDrop.withEntityId(item.getUniqueId());
        try {
            this.storage.replacePersonalDrop(storedDrop.entityId(), activeDrop);
        } catch (RuntimeException exception) {
            item.remove();
            throw exception;
        }
        this.activeDrops.put(item.getUniqueId(), activeDrop);
        this.visibilityAdapter.hideEntityFromOtherPlayers(item, owner);
        this.visibilityAdapter.showEntityToOwner(item, owner);
        return activeDrop;
    }

    private void restoreDrops(final Player player) {
        for (PersonalDrop drop : this.storage.getDropsForOwner(
            player.getUniqueId(),
            PersonalDropState.ACTIVE,
            PersonalDropState.PENDING,
            PersonalDropState.RECOVERED
        )) {
            UUID previousEntityId = drop.entityId();
            Item previousEntity = findItem(previousEntityId);
            if (previousEntity != null) {
                previousEntity.remove();
            }
            PersonalDrop restoredDrop = spawnDrop(drop.withState(PersonalDropState.ACTIVE), player);
            this.plugin.logAdvanced(
                "Restored personal item drop: player=%s, sourceUuid=%s, previousEntityUuid=%s, dropEntityUuid=%s, "
                    + "location=%s, item=%s",
                LogDescriptions.player(player),
                drop.lootSourceId(),
                previousEntityId,
                restoredDrop.entityId(),
                LogDescriptions.location(drop.spawnLocation()),
                LogDescriptions.item(drop.itemStack())
            );
        }
    }

    private void recoverDrops(final UUID ownerId, final boolean removeEntity) {
        for (PersonalDrop drop : List.copyOf(this.activeDrops.values())) {
            if (drop.ownerId().equals(ownerId)) {
                recoverDrop(drop, removeEntity);
            }
        }
    }

    private void recoverDrop(final PersonalDrop drop, final boolean removeEntity) {
        this.activeDrops.remove(drop.entityId());
        this.storage.setPersonalDropState(drop.entityId(), PersonalDropState.RECOVERED);
        if (this.visibilityAdapter != null) {
            this.visibilityAdapter.unregisterEntity(drop.entityId());
        }

        if (removeEntity) {
            Item item = findItem(drop.entityId());
            if (item != null) {
                item.remove();
            }
        }
        this.plugin.logAdvanced(
            "Recovered personal item drop: ownerUuid=%s, sourceUuid=%s, dropEntityUuid=%s, location=%s, item=%s",
            drop.ownerId(),
            drop.lootSourceId(),
            drop.entityId(),
            LogDescriptions.location(drop.spawnLocation()),
            LogDescriptions.item(drop.itemStack())
        );
    }

    private void markPickedUp(final UUID entityId) {
        PersonalDrop drop = this.activeDrops.remove(entityId);
        if (drop == null) {
            return;
        }

        this.storage.removePersonalDrop(entityId);
        if (this.visibilityAdapter != null) {
            this.visibilityAdapter.unregisterEntity(entityId);
        }
        this.plugin.logAdvanced(
            "Personal item drop picked up: ownerUuid=%s, sourceUuid=%s, dropEntityUuid=%s, location=%s, item=%s",
            drop.ownerId(),
            drop.lootSourceId(),
            drop.entityId(),
            LogDescriptions.location(drop.spawnLocation()),
            LogDescriptions.item(drop.itemStack())
        );
    }

    private boolean hasActiveDrop(final UUID ownerId, final UUID sourceId) {
        for (PersonalDrop drop : this.activeDrops.values()) {
            if (drop.ownerId().equals(ownerId) && drop.lootSourceId().equals(sourceId)) {
                return true;
            }
        }
        return false;
    }

    private void expireTimedOutDrops() {
        long timeoutMillis = this.plugin.settings().personalDrops().timeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (PersonalDrop drop : List.copyOf(this.activeDrops.values())) {
            if (now - drop.creationTimestamp() >= timeoutMillis) {
                timeoutDrop(drop);
            }
        }
    }

    private static PersonalDrop pendingDrop(
        final Player player,
        final UUID sourceId,
        final ItemStack loot,
        final Location spawnLocation
    ) {
        return new PersonalDrop(
            UUID.randomUUID(),
            player.getUniqueId(),
            sourceId,
            loot,
            spawnLocation,
            System.currentTimeMillis(),
            PersonalDropState.PENDING
        );
    }

    private void timeoutDrop(final PersonalDrop drop) {
        if (this.plugin.settings().personalDrops().timeoutAction()
            == top.diaoyugan.perPlayerLoot.config.PluginSettings.TimeoutAction.EXPIRE) {
            this.activeDrops.remove(drop.entityId());
            this.storage.removePersonalDrop(drop.entityId());
            if (this.visibilityAdapter != null) {
                this.visibilityAdapter.unregisterEntity(drop.entityId());
            }
            Item item = findItem(drop.entityId());
            if (item != null) {
                item.remove();
            }
            this.plugin.logAdvanced(
                "Expired personal item drop permanently: ownerUuid=%s, sourceUuid=%s, dropEntityUuid=%s, "
                    + "location=%s, item=%s",
                drop.ownerId(),
                drop.lootSourceId(),
                drop.entityId(),
                LogDescriptions.location(drop.spawnLocation()),
                LogDescriptions.item(drop.itemStack())
            );
            return;
        }

        recoverDrop(drop, true);
    }

    private Item findItem(final UUID entityId) {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            org.bukkit.entity.Entity entity = world.getEntity(entityId);
            if (entity instanceof Item item) {
                return item;
            }
        }
        return null;
    }

    private static Location dropLocation(final ItemFrame itemFrame) {
        Location base = itemFrame.getLocation().add(0.5, 0.5, 0.5);
        Vector direction = faceVector(itemFrame.getFacing());
        return base.add(direction.clone().multiply(0.35)).setDirection(direction);
    }

    private static Vector dropVelocity(final Vector direction) {
        if (direction.lengthSquared() < 0.001) {
            return new Vector(0, 0.12, 0.12);
        }
        return direction.normalize().multiply(0.12).setY(0.12);
    }

    private static Vector faceVector(final BlockFace face) {
        return new Vector(face.getModX(), face.getModY(), face.getModZ());
    }
}

