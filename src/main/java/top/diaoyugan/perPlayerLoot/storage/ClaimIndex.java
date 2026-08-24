package top.diaoyugan.perPlayerLoot.storage;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory claim index used by gameplay events; SQLite remains the durable source of truth. */
final class ClaimIndex {
    private final Set<ClaimKey> claims = ConcurrentHashMap.newKeySet();

    boolean contains(final String source, final UUID playerId) {
        return this.claims.contains(new ClaimKey(source, playerId));
    }

    void add(final String source, final UUID playerId) {
        this.claims.add(new ClaimKey(source, playerId));
    }

    void removeSource(final String source) {
        this.claims.removeIf(claim -> claim.source().equals(source));
    }

    Set<UUID> sourcesAsUuidsForPlayer(final UUID playerId) {
        Set<UUID> result = ConcurrentHashMap.newKeySet();
        for (ClaimKey claim : this.claims) {
            if (claim.playerId().equals(playerId)) result.add(UUID.fromString(claim.source()));
        }
        return result;
    }

    void clear() { this.claims.clear(); }

    private record ClaimKey(String source, UUID playerId) { }
}
