package top.diaoyugan.perPlayerLoot.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Durable claims exposed through a bounded cache of currently loaded chunks. */
final class ClaimRepository {
    enum State { UNLOADED, LOADING, READY, FAILED }

    private static final long[] RETRY_DELAYS_TICKS = {20L, 40L, 100L};

    private final SqliteDatabase database;
    private final Logger logger;
    private final Consumer<Runnable> mainThreadExecutor;
    private final BiConsumer<Runnable, Long> retryScheduler;
    private final Map<ChunkKey, CacheEntry> chunks = new ConcurrentHashMap<>();
    private final List<Consumer<ChunkKey>> readyListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generations = new AtomicLong();

    ClaimRepository(
        final SqliteDatabase database,
        final Logger logger,
        final Consumer<Runnable> mainThreadExecutor
    ) {
        this(database, logger, mainThreadExecutor, (task, delay) -> { });
    }

    ClaimRepository(
        final SqliteDatabase database,
        final Logger logger,
        final Consumer<Runnable> mainThreadExecutor,
        final BiConsumer<Runnable, Long> retryScheduler
    ) {
        this.database = database;
        this.logger = logger;
        this.mainThreadExecutor = mainThreadExecutor;
        this.retryScheduler = retryScheduler;
    }

    void loadChunk(final ChunkKey key, final Set<UUID> frameIds) {
        long generation = this.generations.incrementAndGet();
        this.chunks.put(key, new CacheEntry(generation, State.LOADING, null));
        startLoad(key, frameIds, generation, 0);
    }

    private void startLoad(
        final ChunkKey key,
        final Set<UUID> frameIds,
        final long generation,
        final int attempt
    ) {
        this.database.submit(connection -> readChunk(connection, key, frameIds)).whenComplete((claims, failure) -> {
            AtomicBoolean published = new AtomicBoolean();
            AtomicBoolean failedCurrentGeneration = new AtomicBoolean();
            this.chunks.computeIfPresent(key, (ignored, current) -> {
                if (current.generation() != generation) return current;
                if (failure != null) {
                    failedCurrentGeneration.set(true);
                    return new CacheEntry(generation, State.FAILED, null);
                }
                published.set(true);
                return new CacheEntry(generation, State.READY, claims);
            });
            if (failure != null && failedCurrentGeneration.get()) {
                Level level = attempt < RETRY_DELAYS_TICKS.length ? Level.WARNING : Level.SEVERE;
                this.logger.log(
                    level,
                    "Could not load claim cache for chunk " + key + " (attempt " + (attempt + 1) + ").",
                    failure
                );
                scheduleRetry(key, frameIds, generation, attempt);
            } else if (published.get()) {
                this.mainThreadExecutor.accept(() -> {
                    CacheEntry current = this.chunks.get(key);
                    if (current == null || current.generation() != generation || current.state() != State.READY) {
                        return;
                    }
                    for (Consumer<ChunkKey> listener : this.readyListeners) listener.accept(key);
                });
            }
        });
    }

    private void scheduleRetry(
        final ChunkKey key,
        final Set<UUID> frameIds,
        final long generation,
        final int failedAttempt
    ) {
        if (failedAttempt >= RETRY_DELAYS_TICKS.length) return;
        this.retryScheduler.accept(() -> {
            AtomicBoolean retry = new AtomicBoolean();
            this.chunks.computeIfPresent(key, (ignored, current) -> {
                if (current.generation() != generation || current.state() != State.FAILED) return current;
                retry.set(true);
                return new CacheEntry(generation, State.LOADING, null);
            });
            if (retry.get()) startLoad(key, frameIds, generation, failedAttempt + 1);
        }, RETRY_DELAYS_TICKS[failedAttempt]);
    }

    void unloadChunk(final ChunkKey key) {
        this.chunks.remove(key);
    }

    State state(final ChunkKey key) {
        CacheEntry entry = this.chunks.get(key);
        return entry == null ? State.UNLOADED : entry.state();
    }

    boolean hasFrame(final ChunkKey key, final UUID frameId, final UUID playerId) {
        ChunkClaims claims = readyClaims(key);
        return claims != null && claims.hasFrame(frameId, playerId);
    }

    boolean hasBrushable(final StoredBlock block, final UUID playerId) {
        ChunkClaims claims = readyClaims(block.chunkKey());
        return claims != null && claims.hasBrushable(block.position(), playerId);
    }

    void setFrame(final Connection connection, final UUID frameId, final UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO frame_claims(frame_uuid, player_uuid, world_uuid, chunk_x, chunk_z)
            VALUES(?, ?, NULL, NULL, NULL)
            """)) {
            statement.setString(1, frameId.toString());
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
    }

    boolean insertFrame(
        final Connection connection,
        final ChunkKey key,
        final UUID frameId,
        final UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO frame_claims(frame_uuid, player_uuid, world_uuid, chunk_x, chunk_z)
            VALUES(?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, frameId.toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, key.worldId().toString());
            statement.setInt(4, key.chunkX());
            statement.setInt(5, key.chunkZ());
            return statement.executeUpdate() != 0;
        }
    }

    void recordFrame(final ChunkKey key, final UUID frameId, final UUID playerId) {
        ChunkClaims claims = readyClaims(key);
        if (claims != null) claims.addFrame(frameId, playerId);
    }

    java.util.concurrent.CompletableFuture<Void> removeFrame(final ChunkKey key, final UUID frameId) {
        return this.database.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM frame_claims WHERE frame_uuid = ?"
            )) {
                statement.setString(1, frameId.toString());
                statement.executeUpdate();
            }
            return null;
        }).thenRun(() -> {
            ChunkClaims claims = readyClaims(key);
            if (claims != null) claims.removeFrame(frameId);
        });
    }

    void setBrushable(final Connection connection, final StoredBlock block, final UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO brushable_claims(block_key, player_uuid, world_uuid, chunk_x, chunk_z)
            VALUES(?, ?, ?, ?, ?)
            """)) {
            bindBrushable(statement, block, playerId);
            statement.executeUpdate();
        }
        recordBrushable(block, playerId);
    }

    boolean insertBrushable(
        final Connection connection,
        final StoredBlock block,
        final UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO brushable_claims(block_key, player_uuid, world_uuid, chunk_x, chunk_z)
            VALUES(?, ?, ?, ?, ?)
            """)) {
            bindBrushable(statement, block, playerId);
            return statement.executeUpdate() != 0;
        }
    }

    void recordBrushable(final StoredBlock block, final UUID playerId) {
        ChunkClaims claims = readyClaims(block.chunkKey());
        if (claims != null) claims.addBrushable(block.position(), playerId);
    }

    void removeBrushable(final Connection connection, final StoredBlock block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM brushable_claims WHERE block_key = ?"
        )) {
            statement.setString(1, block.blockKey());
            statement.executeUpdate();
        }
        ChunkClaims claims = readyClaims(block.chunkKey());
        if (claims != null) claims.removeBrushable(block.position());
    }

    void addReadyListener(final Consumer<ChunkKey> listener) {
        this.readyListeners.add(listener);
    }

    void removeReadyListener(final Consumer<ChunkKey> listener) {
        this.readyListeners.remove(listener);
    }

    void close() {
        this.readyListeners.clear();
        this.chunks.clear();
    }

    private ChunkClaims readChunk(
        final Connection connection,
        final ChunkKey key,
        final Set<UUID> frameIds
    ) throws SQLException {
        ChunkClaims claims = new ChunkClaims();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT block_key, player_uuid
            FROM brushable_claims
            WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?
            """)) {
            bindChunk(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) readBrushableRow(rows, key, claims);
            }
        }

        StringBuilder sql = new StringBuilder("""
            SELECT frame_uuid, player_uuid
            FROM frame_claims
            WHERE (world_uuid = ? AND chunk_x = ? AND chunk_z = ?)
            """);
        if (!frameIds.isEmpty()) {
            sql.append(" OR frame_uuid IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(frameIds.size(), "?")));
            sql.append(')');
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindChunk(statement, key);
            int parameter = 4;
            for (UUID frameId : frameIds) statement.setString(parameter++, frameId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) readFrameRow(rows, claims);
            }
        }
        backfillFrameChunks(connection, key, frameIds);
        return claims;
    }

    private void readBrushableRow(
        final ResultSet rows,
        final ChunkKey expectedChunk,
        final ChunkClaims claims
    ) throws SQLException {
        String blockKey = rows.getString("block_key");
        String playerId = rows.getString("player_uuid");
        try {
            StoredBlock block = StoredBlock.fromKey(blockKey);
            if (!block.chunkKey().equals(expectedChunk)) {
                this.logger.warning("Skipping brushable claim with mismatched chunk fields: " + blockKey);
                return;
            }
            claims.addBrushable(block.position(), UUID.fromString(playerId));
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING, "Skipping corrupt brushable claim row " + blockKey + "/" + playerId + ".", exception);
        }
    }

    private void readFrameRow(final ResultSet rows, final ChunkClaims claims) throws SQLException {
        String frameId = rows.getString("frame_uuid");
        String playerId = rows.getString("player_uuid");
        try {
            claims.addFrame(UUID.fromString(frameId), UUID.fromString(playerId));
        } catch (RuntimeException exception) {
            this.logger.log(Level.WARNING, "Skipping corrupt frame claim row " + frameId + "/" + playerId + ".", exception);
        }
    }

    private static void backfillFrameChunks(
        final Connection connection,
        final ChunkKey key,
        final Set<UUID> frameIds
    ) throws SQLException {
        if (frameIds.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE frame_claims SET world_uuid = ?, chunk_x = ?, chunk_z = ? WHERE frame_uuid = ?
            """)) {
            for (UUID frameId : frameIds) {
                statement.setString(1, key.worldId().toString());
                statement.setInt(2, key.chunkX());
                statement.setInt(3, key.chunkZ());
                statement.setString(4, frameId.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private ChunkClaims readyClaims(final ChunkKey key) {
        CacheEntry entry = this.chunks.get(key);
        return entry != null && entry.state() == State.READY ? entry.claims() : null;
    }

    private static void bindChunk(final PreparedStatement statement, final ChunkKey key) throws SQLException {
        statement.setString(1, key.worldId().toString());
        statement.setInt(2, key.chunkX());
        statement.setInt(3, key.chunkZ());
    }

    private static void bindBrushable(
        final PreparedStatement statement,
        final StoredBlock block,
        final UUID playerId
    ) throws SQLException {
        statement.setString(1, block.blockKey());
        statement.setString(2, playerId.toString());
        statement.setString(3, block.chunkKey().worldId().toString());
        statement.setInt(4, block.chunkKey().chunkX());
        statement.setInt(5, block.chunkKey().chunkZ());
    }

    private record CacheEntry(long generation, State state, ChunkClaims claims) { }

    record StoredBlock(String blockKey, ChunkKey chunkKey, BlockPos position) {
        static StoredBlock fromKey(final String blockKey) {
            String[] parts = blockKey.split(";");
            if (parts.length != 4) throw new IllegalArgumentException("Invalid block key: " + blockKey);
            UUID worldId = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new StoredBlock(
                blockKey,
                new ChunkKey(worldId, Math.floorDiv(x, 16), Math.floorDiv(z, 16)),
                new BlockPos(x, y, z)
            );
        }
    }
}
