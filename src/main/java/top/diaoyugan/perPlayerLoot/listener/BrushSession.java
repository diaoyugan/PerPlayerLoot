package top.diaoyugan.perPlayerLoot.listener;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

record BrushSession(int brushCount, long lastBrushTick, List<ItemStack> loot, Location blockLocation) {

    BrushSession {
        loot = loot.stream().map(ItemStack::clone).toList();
        blockLocation = blockLocation.clone();
    }

    @Override
    public List<ItemStack> loot() {
        return this.loot.stream().map(ItemStack::clone).toList();
    }

    @Override
    public Location blockLocation() {
        return this.blockLocation.clone();
    }
}
