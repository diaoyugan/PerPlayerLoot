package top.diaoyugan.perPlayerLoot.listener;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.config.ProtectionPolicy;
import top.diaoyugan.perPlayerLoot.logging.LogDescriptions;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropManager;

/** Detects, protects, and claims naturally generated loot item frames. */
public final class ItemFrameLootListener implements Listener {

    private static final byte TRUE = 1;

    private final PerPlayerLoot plugin;
    private final PersonalDropManager personalDropManager;
    private final NamespacedKey playerManagedFrameKey;
    private final NamespacedKey legacyPlayerPlacedFrameKey;

    public ItemFrameLootListener(final PerPlayerLoot plugin, final PersonalDropManager personalDropManager) {
        this.plugin = plugin;
        this.personalDropManager = personalDropManager;
        this.playerManagedFrameKey = new NamespacedKey(plugin, "player_managed_frame");
        this.legacyPlayerPlacedFrameKey = new NamespacedKey(plugin, "player_placed_frame");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        event.getEntity().getPersistentDataContainer().set(
            this.playerManagedFrameKey,
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
        if (!(event.getRightClicked() instanceof ItemFrame itemFrame)
            || itemFrame.getItem().getType() != Material.AIR) {
            return;
        }
        ItemStack handItem = event.getPlayer().getInventory().getItem(event.getHand());
        if (handItem == null || handItem.getType() == Material.AIR) {
            return;
        }
        itemFrame.getPersistentDataContainer().set(
            this.playerManagedFrameKey,
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
            if (canDestroy(player)) {
                return;
            }
            event.setCancelled(true);
            Messages.send(player, Messages.NO_FRAME_DESTROY_PERMISSION);
            this.plugin.logAdvanced(
                "Blocked natural loot frame destruction: player=%s, frameUuid=%s, location=%s",
                LogDescriptions.player(player), itemFrame.getUniqueId(), LogDescriptions.location(itemFrame.getLocation())
            );
            return;
        }
        if (this.plugin.settings().frames().protectDestruction()
            && !this.plugin.settings().frames().allowDestruction()) {
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
            if (canDestroy(player)) {
                return;
            }
            event.setCancelled(true);
            this.personalDropManager.createDrop(player, itemFrame);
            return;
        }
        if (this.plugin.settings().frames().protectDestruction()
            && !this.plugin.settings().frames().allowDestruction()) {
            event.setCancelled(true);
        }
    }

    private boolean isNaturalLootFrame(final ItemFrame itemFrame) {
        if (itemFrame.getItem().getType() == Material.AIR) {
            return false;
        }
        PersistentDataContainer data = itemFrame.getPersistentDataContainer();
        return !data.has(this.playerManagedFrameKey, PersistentDataType.BYTE)
            && !data.has(this.legacyPlayerPlacedFrameKey, PersistentDataType.BYTE)
            && this.plugin.settings().frames().lootMaterials().contains(itemFrame.getItem().getType());
    }

    private boolean canDestroy(final Player player) {
        return ProtectionPolicy.canDestroyFrame(
            this.plugin.settings().frames(),
            player.isSneaking(),
            player.hasPermission("perplayerloot.destroy.frames")
        );
    }
}
