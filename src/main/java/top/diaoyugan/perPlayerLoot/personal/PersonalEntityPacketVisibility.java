package top.diaoyugan.perPlayerLoot.personal;

import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;

final class PersonalEntityPacketVisibility implements AutoCloseable {
    private final PerPlayerLoot plugin;
    private final ProtocolManager protocolManager;
    private final Map<UUID, UUID> entityOwners = new ConcurrentHashMap<>();

    PersonalEntityPacketVisibility(final PerPlayerLoot plugin, final ProtocolManager protocolManager) {
        this.plugin = plugin;
        this.protocolManager = protocolManager;
    }

    void hideFromOtherPlayers(final Entity entity, final Player owner) {
        this.entityOwners.put(entity.getUniqueId(), owner.getUniqueId());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(owner.getUniqueId())) player.showEntity(this.plugin, entity);
            else player.hideEntity(this.plugin, entity);
        }
        this.protocolManager.updateEntity(entity, List.of(owner));
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!entity.isValid()) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.getUniqueId().equals(owner.getUniqueId())) player.hideEntity(this.plugin, entity);
            }
        });
    }

    void showToOwner(final Entity entity, final Player owner) {
        this.protocolManager.updateEntity(entity, List.of(owner));
    }

    UUID ownerForPacket(final PacketEvent event) {
        Integer entityId = readEntityId(event.getPacket());
        if (entityId == null) return null;
        Entity entity = this.protocolManager.getEntityFromID(event.getPlayer().getWorld(), entityId);
        return entity == null ? null : this.entityOwners.get(entity.getUniqueId());
    }

    void unregister(final UUID entityUuid) { this.entityOwners.remove(entityUuid); }
    @Override public void close() { this.entityOwners.clear(); }

    static Integer readEntityId(final PacketContainer packet) {
        try { return packet.getIntegers().read(0); }
        catch (RuntimeException exception) { return null; }
    }
}
