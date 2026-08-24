package top.diaoyugan.perPlayerLoot.personal;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrushableBlock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

/** Coordinates independent ProtocolLib concerns without performing database work in packet callbacks. */
final class ProtocolLibPersonalEntityVisibilityAdapter implements PersonalEntityVisibilityAdapter {

    private final ProtocolManager protocolManager;
    private final PersonalEntityPacketVisibility entities;
    private final ItemFrameViewController itemFrames;
    private final BrushablePreviewController brushables;
    private PacketAdapter packetAdapter;

    ProtocolLibPersonalEntityVisibilityAdapter(final PerPlayerLoot plugin, final LootStorage storage) {
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.entities = new PersonalEntityPacketVisibility(plugin, this.protocolManager);
        this.itemFrames = new ItemFrameViewController(plugin, storage, this.protocolManager);
        this.brushables = new BrushablePreviewController(plugin);
        registerPacketListeners(plugin);
    }

    @Override public void hideEntityFromOtherPlayers(final Entity entity, final Player owner) {
        this.entities.hideFromOtherPlayers(entity, owner);
    }

    @Override public void showEntityToOwner(final Entity entity, final Player owner) {
        this.entities.showToOwner(entity, owner);
    }

    @Override public void sendEmptyItemFrameToOwner(final ItemFrame frame, final Player owner) {
        this.itemFrames.sendEmpty(frame, owner);
    }

    @Override public void registerFrameClaim(final UUID frameUuid, final UUID playerUuid) {
        this.itemFrames.registerClaim(frameUuid, playerUuid);
    }

    @Override public void resendClaimedFrameViews(final Player player) {
        this.itemFrames.resendClaimedViews(player);
    }

    @Override public boolean sendBrushablePreview(
        final Player player, final Location location, final BrushableBlock brushable, final BlockFace brushFace
    ) {
        return this.brushables.sendPreview(player, location, brushable, brushFace);
    }

    @Override public void unregisterEntity(final UUID entityUuid) {
        this.entities.unregister(entityUuid);
    }

    @Override public void close() {
        if (this.packetAdapter != null) {
            this.protocolManager.removePacketListener(this.packetAdapter);
            this.packetAdapter = null;
        }
        this.entities.close();
        this.itemFrames.close();
        this.brushables.close();
    }

    private void registerPacketListeners(final PerPlayerLoot plugin) {
        List<PacketType> packetTypes = List.of(
            PacketType.Play.Server.TILE_ENTITY_DATA, PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_METADATA, PacketType.Play.Server.ENTITY_VELOCITY,
            PacketType.Play.Server.ENTITY_TELEPORT, PacketType.Play.Server.ENTITY_POSITION_SYNC,
            PacketType.Play.Server.REL_ENTITY_MOVE, PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
            PacketType.Play.Server.ENTITY_LOOK, PacketType.Play.Server.ENTITY_HEAD_ROTATION,
            PacketType.Play.Server.ENTITY_STATUS
        );
        this.packetAdapter = new PacketAdapter(plugin, ListenerPriority.HIGHEST, packetTypes) {
            @Override public void onPacketSending(final PacketEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.TILE_ENTITY_DATA) {
                    brushables.rewriteOutgoingPreview(event);
                    return;
                }
                UUID ownerId = entities.ownerForPacket(event);
                if (ownerId != null && !ownerId.equals(event.getPlayer().getUniqueId())) {
                    event.setCancelled(true);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                    itemFrames.rewriteClaimedMetadata(event);
                }
            }
        };
        this.protocolManager.addPacketListener(this.packetAdapter);
    }
}
