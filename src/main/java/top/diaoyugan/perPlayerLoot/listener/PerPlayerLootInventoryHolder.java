package top.diaoyugan.perPlayerLoot.listener;

import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class PerPlayerLootInventoryHolder implements InventoryHolder {

    private final UUID playerId;
    private final List<InventoryPart> parts;

    PerPlayerLootInventoryHolder(
        final UUID playerId,
        final List<InventoryPart> parts
    ) {
        this.playerId = playerId;
        this.parts = List.copyOf(parts);
    }

    UUID playerId() {
        return this.playerId;
    }

    List<InventoryPart> parts() {
        return this.parts;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Virtual holder does not own a fixed inventory.");
    }
}

