package top.diaoyugan.perPlayerLoot.storage;

import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;

public record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    public static ChunkKey of(final Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    public static ChunkKey of(final Location location) {
        return new ChunkKey(
            location.getWorld().getUID(),
            Math.floorDiv(location.getBlockX(), 16),
            Math.floorDiv(location.getBlockZ(), 16)
        );
    }
}
