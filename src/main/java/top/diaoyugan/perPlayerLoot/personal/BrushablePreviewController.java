package top.diaoyugan.perPlayerLoot.personal;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrushableBlock;
import org.bukkit.entity.Player;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;

final class BrushablePreviewController implements AutoCloseable {
    private final PerPlayerLoot plugin;
    private final Map<PreviewKey, Byte> pendingDirections = new ConcurrentHashMap<>();
    private final Set<PreviewKey> rewrittenPreviews = ConcurrentHashMap.newKeySet();

    BrushablePreviewController(final PerPlayerLoot plugin) { this.plugin = plugin; }

    boolean sendPreview(
        final Player player, final Location location, final BrushableBlock brushable, final BlockFace brushFace
    ) {
        PreviewKey key = PreviewKey.from(player.getUniqueId(), location);
        this.pendingDirections.put(key, directionId(brushFace));
        try {
            player.sendBlockUpdate(location, brushable);
            return this.rewrittenPreviews.remove(key);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "Could not send personal archaeology preview data.", exception);
            return false;
        } finally {
            this.pendingDirections.remove(key);
            this.rewrittenPreviews.remove(key);
        }
    }

    void rewriteOutgoingPreview(final PacketEvent event) {
        try {
            BlockPosition position = event.getPacket().getBlockPositionModifier().read(0);
            PreviewKey key = PreviewKey.from(event.getPlayer().getUniqueId(), position);
            Byte direction = this.pendingDirections.get(key);
            if (direction == null) return;
            NbtBase<?> original = event.getPacket().getNbtModifier().read(0);
            if (original == null) return;
            NbtCompound rewritten = NbtFactory.asCompound(original.deepClone());
            rewritten.put("hit_direction", direction.byteValue());
            PacketContainer clone = event.getPacket().shallowClone();
            clone.getNbtModifier().write(0, rewritten);
            event.setPacket(clone);
            this.rewrittenPreviews.add(key);
        } catch (RuntimeException exception) {
            this.plugin.getLogger().log(Level.WARNING, "Could not rewrite personal archaeology preview data.", exception);
        }
    }

    @Override public void close() {
        this.pendingDirections.clear();
        this.rewrittenPreviews.clear();
    }

    private static byte directionId(final BlockFace face) {
        return switch (face) {
            case DOWN -> 0; case UP -> 1; case NORTH -> 2;
            case SOUTH -> 3; case WEST -> 4; case EAST -> 5;
            default -> throw new IllegalArgumentException("Unsupported brush face: " + face);
        };
    }

    private record PreviewKey(UUID playerId, int x, int y, int z) {
        static PreviewKey from(final UUID playerId, final Location location) {
            return new PreviewKey(playerId, location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        static PreviewKey from(final UUID playerId, final BlockPosition position) {
            return new PreviewKey(playerId, position.getX(), position.getY(), position.getZ());
        }
    }
}
