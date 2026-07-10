package top.diaoyugan.perPlayerLoot;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class ProtocolLibPersonalEntityVisibilityAdapter implements PersonalEntityVisibilityAdapter {

    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final ProtocolManager protocolManager;
    private final Map<UUID, UUID> entityOwners = new ConcurrentHashMap<>();
    private final Map<Class<?>, Integer> itemFrameItemIndexes = new ConcurrentHashMap<>();
    private PacketAdapter packetAdapter;

    ProtocolLibPersonalEntityVisibilityAdapter(
        final PerPlayerLoot plugin,
        final LootStorage storage
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        registerPacketListeners();
    }

    @Override
    public void hideEntityFromOtherPlayers(final Entity entity, final Player owner) {
        this.entityOwners.put(entity.getUniqueId(), owner.getUniqueId());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(owner.getUniqueId())) {
                player.showEntity(this.plugin, entity);
            } else {
                player.hideEntity(this.plugin, entity);
            }
        }
        this.protocolManager.updateEntity(entity, List.of(owner));
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!entity.isValid()) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.getUniqueId().equals(owner.getUniqueId())) {
                    player.hideEntity(this.plugin, entity);
                }
            }
        });
    }

    @Override
    public void showEntityToOwner(final Entity entity, final Player owner) {
        this.protocolManager.updateEntity(entity, List.of(owner));
    }

    @Override
    public void sendEmptyItemFrameToOwner(final ItemFrame itemFrame, final Player owner) {
        try {
            OptionalInt itemIndex = itemFrameItemIndex(itemFrame);
            if (itemIndex.isEmpty()) {
                this.plugin.getLogger().warning("Could not find item metadata index for item frame entity.");
                return;
            }
            PacketContainer packet = this.protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            packet.getIntegers().write(0, itemFrame.getEntityId());
            packet.getDataValueCollectionModifier().write(0, List.of(emptyItemFrameDataValue(itemIndex.getAsInt())));
            this.protocolManager.sendServerPacket(owner, packet, false);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "Could not send personal item-frame metadata.", exception);
        }
    }

    @Override
    public void resendClaimedFrameViews(final Player player) {
        Set<UUID> frameIds = this.storage.getClaimedFrameIds(player.getUniqueId());
        if (frameIds.isEmpty()) {
            return;
        }

        for (Entity entity : player.getWorld().getEntitiesByClass(ItemFrame.class)) {
            if (frameIds.contains(entity.getUniqueId())) {
                sendEmptyItemFrameToOwner((ItemFrame) entity, player);
            }
        }
    }

    private void registerPacketListeners() {
        List<PacketType> entityPackets = List.of(
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_METADATA,
            PacketType.Play.Server.ENTITY_VELOCITY,
            PacketType.Play.Server.ENTITY_TELEPORT,
            PacketType.Play.Server.ENTITY_POSITION_SYNC,
            PacketType.Play.Server.REL_ENTITY_MOVE,
            PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
            PacketType.Play.Server.ENTITY_LOOK,
            PacketType.Play.Server.ENTITY_HEAD_ROTATION,
            PacketType.Play.Server.ENTITY_STATUS
        );

        this.packetAdapter = new PacketAdapter(this.plugin, ListenerPriority.HIGHEST, entityPackets) {
            @Override
            public void onPacketSending(final PacketEvent event) {
                handleOutgoingPacket(event);
            }
        };
        this.protocolManager.addPacketListener(this.packetAdapter);
    }

    @Override
    public void unregisterEntity(final UUID entityUuid) {
        this.entityOwners.remove(entityUuid);
    }

    @Override
    public void close() {
        if (this.packetAdapter != null) {
            this.protocolManager.removePacketListener(this.packetAdapter);
            this.packetAdapter = null;
        }
        this.entityOwners.clear();
    }

    private void handleOutgoingPacket(final PacketEvent event) {
        UUID ownerId = ownerForPacket(event);
        if (ownerId != null && !ownerId.equals(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            rewriteClaimedItemFrameMetadata(event);
        }
    }

    private UUID ownerForPacket(final PacketEvent event) {
        Integer entityId = readEntityId(event.getPacket());
        if (entityId == null) {
            return null;
        }

        Entity entity = this.protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);
        return entity == null ? null : this.entityOwners.get(entity.getUniqueId());
    }

    private void rewriteClaimedItemFrameMetadata(final PacketEvent event) {
        Integer entityId = readEntityId(event.getPacket());
        if (entityId == null) {
            return;
        }

        Entity entity = this.protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);
        if (!(entity instanceof ItemFrame itemFrame)
            || !this.storage.hasClaimedFrame(itemFrame.getUniqueId(), event.getPlayer().getUniqueId())) {
            return;
        }
        OptionalInt itemIndex = itemFrameItemIndex(itemFrame);
        if (itemIndex.isEmpty()) {
            return;
        }
        int itemMetadataIndex = itemIndex.getAsInt();

        List<WrappedDataValue> values;
        try {
            values = event.getPacket().getDataValueCollectionModifier().read(0);
        } catch (RuntimeException exception) {
            return;
        }

        List<WrappedDataValue> rewritten = new ArrayList<>(values.size());
        boolean replaced = false;
        for (WrappedDataValue value : values) {
            if (value.getIndex() == itemMetadataIndex) {
                rewritten.add(emptyItemFrameDataValue(itemMetadataIndex));
                replaced = true;
            } else {
                rewritten.add(value);
            }
        }

        if (!replaced) {
            rewritten.add(emptyItemFrameDataValue(itemMetadataIndex));
        }

        PacketContainer clone = event.getPacket().shallowClone();
        clone.getDataValueCollectionModifier().write(0, rewritten);
        event.setPacket(clone);
    }

    private static Integer readEntityId(final PacketContainer packet) {
        try {
            return packet.getIntegers().read(0);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static WrappedDataValue emptyItemFrameDataValue(final int itemMetadataIndex) {
        WrappedDataWatcher watcher = new WrappedDataWatcher();
        watcher.setItemStack(itemMetadataIndex, new ItemStack(Material.AIR), true);
        return watcher.toDataValueCollection().get(0);
    }

    private OptionalInt itemFrameItemIndex(final ItemFrame itemFrame) {
        Integer cachedIndex = this.itemFrameItemIndexes.get(itemFrame.getClass());
        if (cachedIndex != null) {
            return OptionalInt.of(cachedIndex);
        }

        WrappedDataWatcher watcher = new WrappedDataWatcher(itemFrame);
        for (WrappedWatchableObject watchableObject : watcher.getWatchableObjects()) {
            if (watchableObject.getValue() instanceof ItemStack) {
                int index = watchableObject.getIndex();
                this.itemFrameItemIndexes.put(itemFrame.getClass(), index);
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }
}
