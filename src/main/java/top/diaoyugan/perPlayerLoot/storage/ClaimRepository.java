package top.diaoyugan.perPlayerLoot.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

/** Durable frame/archaeology claims backed by an event-thread-safe in-memory index. */
final class ClaimRepository {
    private final SqliteDatabase database;
    private final ClaimIndex frameIndex = new ClaimIndex();
    private final ClaimIndex brushableIndex = new ClaimIndex();

    ClaimRepository(final SqliteDatabase database) { this.database = database; }

    void loadIndexes() throws SQLException {
        this.frameIndex.clear();
        this.brushableIndex.clear();
        try (Statement statement = this.database.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT frame_uuid, player_uuid FROM frame_claims")) {
            while (rows.next()) this.frameIndex.add(rows.getString(1), UUID.fromString(rows.getString(2)));
        }
        try (Statement statement = this.database.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT block_key, player_uuid FROM brushable_claims")) {
            while (rows.next()) this.brushableIndex.add(rows.getString(1), UUID.fromString(rows.getString(2)));
        }
    }

    boolean hasFrame(final UUID frameId, final UUID playerId) {
        return this.frameIndex.contains(frameId.toString(), playerId);
    }

    void setFrame(final UUID frameId, final UUID playerId) throws SQLException {
        try (PreparedStatement statement = this.database.connection().prepareStatement(
            "INSERT OR IGNORE INTO frame_claims(frame_uuid, player_uuid) VALUES(?, ?)"
        )) {
            statement.setString(1, frameId.toString());
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
        recordFrame(frameId, playerId);
    }

    boolean insertFrame(final Connection connection, final UUID frameId, final UUID playerId) throws SQLException {
        return insert(connection, "INSERT OR IGNORE INTO frame_claims(frame_uuid, player_uuid) VALUES(?, ?)",
            frameId.toString(), playerId);
    }

    void recordFrame(final UUID frameId, final UUID playerId) {
        this.frameIndex.add(frameId.toString(), playerId);
    }

    boolean hasBrushable(final String blockKey, final UUID playerId) {
        return this.brushableIndex.contains(blockKey, playerId);
    }

    void setBrushable(final String blockKey, final UUID playerId) throws SQLException {
        try (PreparedStatement statement = this.database.connection().prepareStatement(
            "INSERT OR IGNORE INTO brushable_claims(block_key, player_uuid) VALUES(?, ?)"
        )) {
            statement.setString(1, blockKey);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
        recordBrushable(blockKey, playerId);
    }

    boolean insertBrushable(
        final Connection connection, final String blockKey, final UUID playerId
    ) throws SQLException {
        return insert(connection, "INSERT OR IGNORE INTO brushable_claims(block_key, player_uuid) VALUES(?, ?)",
            blockKey, playerId);
    }

    void recordBrushable(final String blockKey, final UUID playerId) {
        this.brushableIndex.add(blockKey, playerId);
    }

    void removeBrushable(final String blockKey) throws SQLException {
        try (PreparedStatement statement = this.database.connection().prepareStatement(
            "DELETE FROM brushable_claims WHERE block_key = ?"
        )) {
            statement.setString(1, blockKey);
            statement.executeUpdate();
        }
        this.brushableIndex.removeSource(blockKey);
    }

    Set<UUID> frameIds(final UUID playerId) {
        return this.frameIndex.sourcesAsUuidsForPlayer(playerId);
    }

    void close() {
        this.frameIndex.clear();
        this.brushableIndex.clear();
    }

    private static boolean insert(
        final Connection connection, final String sql, final String source, final UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source);
            statement.setString(2, playerId.toString());
            return statement.executeUpdate() != 0;
        }
    }
}
