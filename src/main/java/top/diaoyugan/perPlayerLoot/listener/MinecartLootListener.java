package top.diaoyugan.perPlayerLoot.listener;

import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.loot.LootTable;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.logging.LogDescriptions;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

/** Owns chest-minecart interaction and destruction events. */
public final class MinecartLootListener implements Listener {

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final LootListener containers;

    public MinecartLootListener(
        final PerPlayerLoot plugin,
        final LootStorage storage,
        final LootListener containers
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.containers = containers;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof StorageMinecart minecart)) {
            return;
        }
        LootTable lootTable = minecart.getLootTable();
        if (lootTable == null) {
            this.containers.cleanupLostLootMinecart(minecart);
            return;
        }
        this.containers.tagLootMinecart(minecart, lootTable);
        event.setCancelled(true);
        this.containers.openMinecartContainer(event.getPlayer(), minecart, lootTable);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final VehicleDamageEvent event) {
        if (!(event.getVehicle() instanceof StorageMinecart minecart)
            || !this.containers.isManagedNaturalLootMinecart(minecart)) {
            return;
        }
        if (event.getAttacker() instanceof Player player) {
            if (this.containers.canDestroyNaturalLootContainer(player)) {
                return;
            }
            event.setCancelled(true);
            Messages.send(player, Messages.NO_CONTAINER_DESTROY_PERMISSION);
            this.plugin.logAdvanced(
                "Blocked natural chest minecart damage: player=%s, entityUuid=%s, location=%s",
                LogDescriptions.player(player), minecart.getUniqueId(), LogDescriptions.location(minecart.getLocation())
            );
            return;
        }
        if (this.containers.isContainerDestructionProtectionEnabled()
            && !this.plugin.settings().containers().allowDestruction()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDestroy(final VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof StorageMinecart minecart)
            || !this.containers.isManagedNaturalLootMinecart(minecart)) {
            return;
        }
        if (event.getAttacker() instanceof Player player) {
            if (!this.containers.canDestroyNaturalLootContainer(player)) {
                event.setCancelled(true);
                Messages.send(player, Messages.NO_CONTAINER_DESTROY_PERMISSION);
                this.plugin.logAdvanced(
                    "Blocked natural chest minecart destruction: player=%s, entityUuid=%s, location=%s",
                    LogDescriptions.player(player), minecart.getUniqueId(), LogDescriptions.location(minecart.getLocation())
                );
                return;
            }
        } else if (this.containers.isContainerDestructionProtectionEnabled()
            && !this.plugin.settings().containers().allowDestruction()) {
            event.setCancelled(true);
            return;
        }
        String containerKey = LootListener.entityContainerKey(minecart.getUniqueId());
        this.containers.closeVirtualContainerViews(containerKey);
        this.storage.removeContainerData(containerKey);
        String actor = event.getAttacker() instanceof Player player
            ? LogDescriptions.player(player)
            : String.valueOf(event.getAttacker());
        this.plugin.logAdvanced(
            "Natural chest minecart destroyed: actor=%s, entityUuid=%s, sourceKey=%s, location=%s; personal data removed",
            actor, minecart.getUniqueId(), containerKey, LogDescriptions.location(minecart.getLocation())
        );
    }
}
