package top.diaoyugan.perPlayerLoot.listener;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class PerPlayerLootInventoryHolder implements InventoryHolder {

    private final String containerKey;
    private final UUID playerId;
    private final Location containerLocation;

    PerPlayerLootInventoryHolder(
        final String containerKey,
        final UUID playerId,
        final Location containerLocation
    ) {
        this.containerKey = containerKey;
        this.playerId = playerId;
        this.containerLocation = containerLocation.clone();
    }

    String containerKey() {
        return this.containerKey;
    }

    UUID playerId() {
        return this.playerId;
    }

    Location containerLocation() {
        return this.containerLocation.clone();
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Virtual holder does not own a fixed inventory.");
    }
}

