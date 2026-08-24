package top.diaoyugan.perPlayerLoot.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

/** Routes destruction, merge, explosion, and hopper protection independently from inventory opening. */
public final class ContainerProtectionListener implements Listener {
    private final LootListener containers;

    public ContainerProtectionListener(final LootListener containers) { this.containers = containers; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) { this.containers.protectBlockBreak(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestPlace(final BlockPlaceEvent event) { this.containers.protectChestPlacement(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) { this.containers.cleanupBlockExplosion(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) { this.containers.cleanupEntityExplosion(event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplodeProtection(final BlockExplodeEvent event) {
        this.containers.protectBlockExplosion(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplodeProtection(final EntityExplodeEvent event) {
        this.containers.protectEntityExplosion(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(final InventoryMoveItemEvent event) { this.containers.protectInventoryMove(event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperInventorySearch(final HopperInventorySearchEvent event) {
        this.containers.protectHopperSearch(event);
    }
}
