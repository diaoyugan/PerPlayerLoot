package top.diaoyugan.perPlayerLoot.storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable, thread-safe claims for one currently loaded Minecraft chunk. */
final class ChunkClaims {
    private final Map<BlockPos, Set<UUID>> brushables = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> frames = new ConcurrentHashMap<>();

    boolean hasBrushable(final BlockPos position, final UUID playerId) {
        Set<UUID> players = this.brushables.get(position);
        return players != null && players.contains(playerId);
    }

    boolean hasFrame(final UUID frameId, final UUID playerId) {
        Set<UUID> players = this.frames.get(frameId);
        return players != null && players.contains(playerId);
    }

    void addBrushable(final BlockPos position, final UUID playerId) {
        this.brushables.computeIfAbsent(position, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    void addFrame(final UUID frameId, final UUID playerId) {
        this.frames.computeIfAbsent(frameId, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    void removeFrame(final UUID frameId) {
        this.frames.remove(frameId);
    }

    void removeBrushable(final BlockPos position) {
        this.brushables.remove(position);
    }
}
