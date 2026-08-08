package top.diaoyugan.perPlayerLoot.listener;

import org.bukkit.Location;

record InventoryPart(
    String containerKey,
    Location location,
    int offset,
    int size
) {

    InventoryPart {
        location = location.clone();
    }

    @Override
    public Location location() {
        return this.location.clone();
    }
}
