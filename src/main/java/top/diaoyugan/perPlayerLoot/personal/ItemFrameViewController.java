package top.diaoyugan.perPlayerLoot.personal;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

final class ItemFrameViewController implements AutoCloseable {
    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final ProtocolManager protocolManager;
    private final Map<UUID, Set<UUID>> claimsByPlayer = new ConcurrentHashMap<>();
    private final Map<Class<?>, Integer> itemIndexes = new ConcurrentHashMap<>();

    ItemFrameViewController(
        final PerPlayerLoot plugin, final LootStorage storage, final ProtocolManager protocolManager
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.protocolManager = protocolManager;
    }

    void registerClaim(final UUID frameUuid, final UUID playerUuid) {
        this.claimsByPlayer.compute(playerUuid, (ignored, existing) -> {
            Set<UUID> updated = ConcurrentHashMap.newKeySet();
            if (existing != null) updated.addAll(existing);
            updated.add(frameUuid);
            return updated;
        });
    }

    void resendClaimedViews(final Player player) {
        Set<UUID> frameIds = this.storage.getClaimedFrameIds(player.getUniqueId());
        this.claimsByPlayer.put(player.getUniqueId(), Set.copyOf(frameIds));
        for (Entity entity : player.getWorld().getEntitiesByClass(ItemFrame.class)) {
            if (frameIds.contains(entity.getUniqueId())) sendEmpty((ItemFrame) entity, player);
        }
    }

    void sendEmpty(final ItemFrame itemFrame, final Player owner) {
        try {
            OptionalInt itemIndex = itemIndex(itemFrame);
            if (itemIndex.isEmpty()) {
                this.plugin.getLogger().warning("Could not find item metadata index for item frame entity.");
                return;
            }
            PacketContainer packet = this.protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            packet.getIntegers().write(0, itemFrame.getEntityId());
            packet.getDataValueCollectionModifier().write(0, List.of(emptyValue(itemIndex.getAsInt())));
            this.protocolManager.sendServerPacket(owner, packet, false);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "Could not send personal item-frame metadata.", exception);
        }
    }

    void rewriteClaimedMetadata(final PacketEvent event) {
        Integer entityId = PersonalEntityPacketVisibility.readEntityId(event.getPacket());
        if (entityId == null) return;
        Entity entity = this.protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);
        Set<UUID> claims = this.claimsByPlayer.get(event.getPlayer().getUniqueId());
        if (!(entity instanceof ItemFrame itemFrame) || claims == null || !claims.contains(itemFrame.getUniqueId())) return;
        OptionalInt itemIndex = itemIndex(itemFrame);
        if (itemIndex.isEmpty()) return;

        List<WrappedDataValue> values;
        try { values = event.getPacket().getDataValueCollectionModifier().read(0); }
        catch (RuntimeException exception) { return; }
        List<WrappedDataValue> rewritten = new ArrayList<>(values.size() + 1);
        boolean replaced = false;
        for (WrappedDataValue value : values) {
            if (value.getIndex() == itemIndex.getAsInt()) {
                rewritten.add(emptyValue(itemIndex.getAsInt()));
                replaced = true;
            } else rewritten.add(value);
        }
        if (!replaced) rewritten.add(emptyValue(itemIndex.getAsInt()));
        PacketContainer clone = event.getPacket().shallowClone();
        clone.getDataValueCollectionModifier().write(0, rewritten);
        event.setPacket(clone);
    }

    private OptionalInt itemIndex(final ItemFrame itemFrame) {
        Integer cached = this.itemIndexes.get(itemFrame.getClass());
        if (cached != null) return OptionalInt.of(cached);
        for (WrappedWatchableObject value : new WrappedDataWatcher(itemFrame).getWatchableObjects()) {
            if (value.getValue() instanceof ItemStack) {
                this.itemIndexes.put(itemFrame.getClass(), value.getIndex());
                return OptionalInt.of(value.getIndex());
            }
        }
        return OptionalInt.empty();
    }

    private static WrappedDataValue emptyValue(final int index) {
        WrappedDataWatcher watcher = new WrappedDataWatcher();
        watcher.setItemStack(index, new ItemStack(Material.AIR), true);
        return watcher.toDataValueCollection().get(0);
    }

    @Override public void close() {
        this.claimsByPlayer.clear();
        this.itemIndexes.clear();
    }
}
