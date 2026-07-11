package top.diaoyugan.perPlayerLoot.personal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
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
import org.bukkit.util.Vector;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

public final class PersonalDropManager implements Listener {

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final PersonalEntityVisibilityAdapter visibilityAdapter;
    private final Map<UUID, PersonalDrop> activeDrops = new HashMap<>();

    public PersonalDropManager(
        final PerPlayerLoot plugin,
        final LootStorage storage,
        final PersonalEntityVisibilityAdapter visibilityAdapter
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.visibilityAdapter = visibilityAdapter;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startTimeoutTask();
    }

    public boolean isEnabled() {
        return this.visibilityAdapter != null;
    }

    public boolean createDrop(final Player player, final ItemFrame itemFrame) {
        if (!isEnabled()) {
            Messages.send(player, Messages.PERSONAL_DROPS_DISABLED);
            return false;
        }

        UUID sourceId = itemFrame.getUniqueId();
        UUID playerId = player.getUniqueId();
        if (this.storage.hasClaimedFrame(sourceId, playerId) || hasActiveDrop(playerId, sourceId)) {
            Messages.send(player, Messages.FRAME_ALREADY_CLAIMED);
            this.visibilityAdapter.sendEmptyItemFrameToOwner(itemFrame, player);
            return false;
        }

        ItemStack loot = itemFrame.getItem().clone();
        if (loot.getType().isAir()) {
            return false;
        }

        this.storage.setClaimedFrame(sourceId, playerId);
        Location spawnLocation = dropLocation(itemFrame);
        PersonalDrop storedDrop = new PersonalDrop(
            UUID.randomUUID(),
            playerId,
            sourceId,
            loot,
            spawnLocation,
            System.currentTimeMillis(),
            PersonalDropState.ACTIVE
        );
        spawnDrop(storedDrop, player);
        this.visibilityAdapter.sendEmptyItemFrameToOwner(itemFrame, player);
        player.playSound(itemFrame.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 1.0F, 1.0F);
        return true;
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

    private void spawnDrop(final PersonalDrop storedDrop, final Player owner) {
        Item item = storedDrop.spawnLocation().getWorld().dropItem(storedDrop.spawnLocation(), storedDrop.itemStack(), droppedItem -> {
            droppedItem.setOwner(owner.getUniqueId());
            droppedItem.setThrower(owner.getUniqueId());
            droppedItem.setCanMobPickup(false);
            droppedItem.setPickupDelay(10);
            droppedItem.setWillAge(false);
            droppedItem.setUnlimitedLifetime(true);
            droppedItem.setVelocity(dropVelocity(storedDrop.spawnLocation().getDirection()));
        });

        PersonalDrop activeDrop = storedDrop.withEntityId(item.getUniqueId());
        this.activeDrops.put(item.getUniqueId(), activeDrop);
        this.storage.savePersonalDrop(activeDrop);
        this.visibilityAdapter.hideEntityFromOtherPlayers(item, owner);
        this.visibilityAdapter.showEntityToOwner(item, owner);
    }

    private void restoreDrops(final Player player) {
        for (PersonalDrop drop : this.storage.getDropsForOwner(
            player.getUniqueId(),
            PersonalDropState.ACTIVE,
            PersonalDropState.RECOVERED
        )) {
            UUID previousEntityId = drop.entityId();
            spawnDrop(drop.withState(PersonalDropState.ACTIVE), player);
            this.storage.removePersonalDrop(previousEntityId);
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
    }

    private void markPickedUp(final UUID entityId) {
        PersonalDrop drop = this.activeDrops.remove(entityId);
        if (drop == null) {
            return;
        }

        this.storage.setPersonalDropState(entityId, PersonalDropState.PICKED_UP);
        if (this.visibilityAdapter != null) {
            this.visibilityAdapter.unregisterEntity(entityId);
        }
    }

    private boolean hasActiveDrop(final UUID ownerId, final UUID sourceId) {
        for (PersonalDrop drop : this.activeDrops.values()) {
            if (drop.ownerId().equals(ownerId) && drop.lootSourceId().equals(sourceId)) {
                return true;
            }
        }
        return false;
    }

    private void startTimeoutTask() {
        Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            long timeoutMillis = Math.max(1L, this.plugin.getConfig().getLong("personal-drop-timeout-seconds", 300L)) * 1000L;
            long now = System.currentTimeMillis();
            for (PersonalDrop drop : List.copyOf(this.activeDrops.values())) {
                if (now - drop.creationTimestamp() >= timeoutMillis) {
                    timeoutDrop(drop);
                }
            }
        }, 20L * 30L, 20L * 30L);
    }

    private void timeoutDrop(final PersonalDrop drop) {
        String action = this.plugin.getConfig().getString("personal-drop-timeout-action", "RECOVER");
        if ("EXPIRE".equalsIgnoreCase(action)) {
            this.activeDrops.remove(drop.entityId());
            this.storage.setPersonalDropState(drop.entityId(), PersonalDropState.EXPIRED);
            if (this.visibilityAdapter != null) {
                this.visibilityAdapter.unregisterEntity(drop.entityId());
            }
            Item item = findItem(drop.entityId());
            if (item != null) {
                item.remove();
            }
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

