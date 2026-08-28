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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.storage.ChunkKey;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

final class ItemFrameViewController implements AutoCloseable {
    private final PerPlayerLoot plugin;
    private final LootStorage storage;
    private final ProtocolManager protocolManager;
    private final Map<Class<?>, Integer> itemIndexes = new ConcurrentHashMap<>();
    private final Consumer<ChunkKey> chunkReadyListener;

    ItemFrameViewController(
        final PerPlayerLoot plugin, final LootStorage storage, final ProtocolManager protocolManager
    ) {
        this.plugin = plugin;
        this.storage = storage;
        this.protocolManager = protocolManager;
        this.chunkReadyListener = this::resendClaimedViewsInChunk;
        this.storage.addClaimChunkReadyListener(this.chunkReadyListener);
    }

    void resendClaimedViews(final Player player) {
        for (Chunk chunk : player.getSentChunks()) {
            if (!this.storage.isClaimChunkReady(chunk)) continue;
            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof ItemFrame frame
                    && this.storage.hasClaimedFrame(frame, player.getUniqueId())) {
                    sendEmpty(frame, player);
                }
            }
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
        if (!(entity instanceof ItemFrame itemFrame)
            || !this.storage.hasClaimedFrame(itemFrame, event.getPlayer().getUniqueId())) return;
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

    private void resendClaimedViewsInChunk(final ChunkKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) return;
        Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
        java.util.Collection<Player> viewers = chunk.getPlayersSeeingChunk();
        if (viewers.isEmpty()) return;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemFrame frame)) continue;
            for (Player player : viewers) {
                if (this.storage.hasClaimedFrame(frame, player.getUniqueId())) sendEmpty(frame, player);
            }
        }
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
        this.storage.removeClaimChunkReadyListener(this.chunkReadyListener);
        this.itemIndexes.clear();
    }
}
