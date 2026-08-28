package top.diaoyugan.perPlayerLoot.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.ItemFrame;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.personal.PersonalDrop;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropState;

public final class LootStorage {

    private final PerPlayerLoot plugin;
    private final SqliteDatabase database;
    private final ClaimRepository claims;

    public LootStorage(final PerPlayerLoot plugin) {
        this.plugin = plugin;
        this.database = new SqliteDatabase(new File(plugin.getDataFolder(), "loot-data.sqlite"), plugin.getLogger());
        this.claims = new ClaimRepository(
            this.database,
            plugin.getLogger(),
            task -> {
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
            },
            (task, delayTicks) -> {
                if (plugin.isEnabled()) Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        );
    }

    public void load() {
        if (!this.plugin.getDataFolder().exists() && !this.plugin.getDataFolder().mkdirs()) {
            this.plugin.getLogger().warning("Could not create plugin data folder.");
        }

        try {
            this.database.open(this.plugin.settings().database().password());
            this.database.transaction(ignored -> {
                createTables();
                return null;
            });
            new LegacyYamlMigrator(this.plugin, this).migrateIfNeeded();
            removeTerminalPersonalDrops();
        } catch (SQLException exception) {
            this.database.close();
            throw new IllegalStateException("Could not open SQLite loot storage.", exception);
        } catch (RuntimeException exception) {
            this.database.close();
            throw exception;
        }
    }

    public void save() {
        this.claims.close();
        this.database.close();
    }

    /** Returns the stored blob on the DB executor; Bukkit item decoding stays on the caller thread. */
    public CompletableFuture<byte[]> getContainerInventoryDataAsync(
        final String containerKey,
        final UUID playerId
    ) {
        return this.database.submit(database -> {
            String sql = "SELECT contents FROM container_inventories WHERE container_key = ? AND player_uuid = ?";
            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setString(1, containerKey);
                statement.setString(2, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getBytes("contents") : null;
                }
            }
        });
    }

    public ItemStack[] decodeContainerInventory(final byte[] serialized, final int size) {
        ItemStack[] storedContents = ItemStackCodec.deserializeItems(serialized);
        ItemStack[] contents = new ItemStack[size];
        System.arraycopy(storedContents, 0, contents, 0, Math.min(storedContents.length, size));
        return contents;
    }

    public void setContainerInventory(final String containerKey, final UUID playerId, final ItemStack[] contents) {
        UUID entityId = entityIdFromContainerKey(containerKey);
        StoredContainer storedContainer = entityId == null ? StoredContainer.fromKey(containerKey) : null;
        byte[] serialized = ItemStackCodec.serializeItems(contents);
        String sql = """
            INSERT INTO container_inventories(container_key, player_uuid, contents)
            VALUES(?, ?, ?)
            ON CONFLICT(container_key, player_uuid) DO UPDATE SET
                contents = excluded.contents
            """;
        this.database.transaction(database -> {
            upsertContainerSource(database, containerKey, storedContainer, entityId);
            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setString(1, containerKey);
                statement.setString(2, playerId.toString());
                statement.setBytes(3, serialized);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setContainerInventoryAsync(
        final String containerKey,
        final UUID playerId,
        final ItemStack[] contents
    ) {
        UUID entityId = entityIdFromContainerKey(containerKey);
        StoredContainer storedContainer = entityId == null ? StoredContainer.fromKey(containerKey) : null;
        byte[] serialized = ItemStackCodec.serializeItems(contents);
        return this.database.submitTransaction(database -> {
            upsertContainerSource(database, containerKey, storedContainer, entityId);
            try (PreparedStatement statement = database.prepareStatement("""
                INSERT INTO container_inventories(container_key, player_uuid, contents)
                VALUES(?, ?, ?)
                ON CONFLICT(container_key, player_uuid) DO UPDATE SET contents = excluded.contents
                """)) {
                statement.setString(1, containerKey);
                statement.setString(2, playerId.toString());
                statement.setBytes(3, serialized);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void removeContainerData(final String containerKey) {
        removeContainerDataAsync(List.of(containerKey)).exceptionally(failure -> {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "Could not remove stored container data " + containerKey + ".",
                failure
            );
            return null;
        });
    }

    public CompletableFuture<Void> removeContainerDataAsync(final Collection<String> containerKeys) {
        if (containerKeys.isEmpty()) return CompletableFuture.completedFuture(null);
        return this.database.submitTransaction(database -> {
            deleteContainerData(database, containerKeys);
            return null;
        });
    }

    public CompletableFuture<List<StoredContainer>> getContainerDataInChunkAsync(
        final UUID worldId,
        final int chunkX,
        final int chunkZ
    ) {
        return this.database.submit(database -> {
            String sql = """
                SELECT container_key, world_uuid, chunk_x, chunk_z, block_x, block_y, block_z
                FROM container_sources
                WHERE entity_uuid IS NULL AND world_uuid = ? AND chunk_x = ? AND chunk_z = ?
                """;
            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setString(1, worldId.toString());
                statement.setInt(2, chunkX);
                statement.setInt(3, chunkZ);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return readStoredContainers(resultSet);
                }
            }
        });
    }

    public CompletableFuture<List<StoredContainer>> getAllContainerDataAsync() {
        return this.database.submit(database -> {
            String sql = """
                SELECT container_key, world_uuid, chunk_x, chunk_z, block_x, block_y, block_z
                FROM container_sources
                WHERE entity_uuid IS NULL
                """;
            try (PreparedStatement statement = database.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                return readStoredContainers(resultSet);
            }
        });
    }

    public boolean hasClaimedFrame(final ItemFrame frame, final UUID playerId) {
        return this.claims.hasFrame(ChunkKey.of(frame.getLocation()), frame.getUniqueId(), playerId);
    }

    /** Legacy migration path; historical frame locations are backfilled when their entities load. */
    public void setClaimedFrame(final UUID frameId, final UUID playerId) {
        this.database.execute(connection -> {
            this.claims.setFrame(connection, frameId, playerId);
            return null;
        });
    }

    /** Atomically reserves a frame claim and its recoverable drop. */
    public boolean claimFrameWithDrop(final ItemFrame frame, final UUID playerId, final PersonalDrop drop) {
        ChunkKey chunkKey = ChunkKey.of(frame.getLocation());
        UUID frameId = frame.getUniqueId();
        StoredPersonalDrop storedDrop = StoredPersonalDrop.from(drop);
        boolean claimed = this.database.transaction(connection -> {
            if (!this.claims.insertFrame(connection, chunkKey, frameId, playerId)) return false;
            savePersonalDrop(connection, storedDrop);
            return true;
        });
        if (claimed) this.claims.recordFrame(chunkKey, frameId, playerId);
        return claimed;
    }

    /** Deletes all claims for a frame without blocking the server thread. */
    public CompletableFuture<Void> removeFrameClaims(final ItemFrame frame) {
        ChunkKey key = ChunkKey.of(frame.getLocation());
        UUID frameId = frame.getUniqueId();
        CompletableFuture<Void> removal = this.claims.removeFrame(key, frameId);
        removal.exceptionally(failure -> {
            this.plugin.getLogger().log(
                Level.SEVERE,
                "Could not remove claims for destroyed item frame " + frameId + ".",
                failure
            );
            return null;
        });
        return removal;
    }

    public boolean hasClaimedBrushable(final String blockKey, final UUID playerId) {
        return this.claims.hasBrushable(ClaimRepository.StoredBlock.fromKey(blockKey), playerId);
    }

    public void setClaimedBrushable(final String blockKey, final UUID playerId) {
        ClaimRepository.StoredBlock block = ClaimRepository.StoredBlock.fromKey(blockKey);
        this.database.execute(connection -> {
            this.claims.setBrushable(connection, block, playerId);
            return null;
        });
    }

    /** Atomically reserves an archaeology claim and all of its recoverable drops. */
    public boolean claimBrushableWithDrops(
        final String blockKey,
        final UUID playerId,
        final List<PersonalDrop> drops
    ) {
        ClaimRepository.StoredBlock block = ClaimRepository.StoredBlock.fromKey(blockKey);
        List<StoredPersonalDrop> storedDrops = drops.stream().map(StoredPersonalDrop::from).toList();
        boolean claimed = this.database.transaction(connection -> {
            if (!this.claims.insertBrushable(connection, block, playerId)) return false;
            for (StoredPersonalDrop drop : storedDrops) savePersonalDrop(connection, drop);
            return true;
        });
        if (claimed) this.claims.recordBrushable(block, playerId);
        return claimed;
    }

    public void removeBrushableClaims(final String blockKey) {
        ClaimRepository.StoredBlock block = ClaimRepository.StoredBlock.fromKey(blockKey);
        this.database.execute(connection -> {
            this.claims.removeBrushable(connection, block);
            return null;
        });
    }

    public boolean isClaimChunkReady(final Chunk chunk) {
        return this.claims.state(ChunkKey.of(chunk)) == ClaimRepository.State.READY;
    }

    public boolean isClaimChunkReady(final Location location) {
        return this.claims.state(ChunkKey.of(location)) == ClaimRepository.State.READY;
    }

    public boolean isClaimChunkFailed(final Chunk chunk) {
        return this.claims.state(ChunkKey.of(chunk)) == ClaimRepository.State.FAILED;
    }

    public boolean isClaimChunkFailed(final Location location) {
        return this.claims.state(ChunkKey.of(location)) == ClaimRepository.State.FAILED;
    }

    public void loadChunkClaims(final Chunk chunk) {
        Set<UUID> frameIds = new HashSet<>();
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity instanceof ItemFrame) frameIds.add(entity.getUniqueId());
        }
        this.claims.loadChunk(ChunkKey.of(chunk), Set.copyOf(frameIds));
    }

    public void unloadChunkClaims(final Chunk chunk) {
        this.claims.unloadChunk(ChunkKey.of(chunk));
    }

    public void addClaimChunkReadyListener(final java.util.function.Consumer<ChunkKey> listener) {
        this.claims.addReadyListener(listener);
    }

    public void removeClaimChunkReadyListener(final java.util.function.Consumer<ChunkKey> listener) {
        this.claims.removeReadyListener(listener);
    }

    public void savePersonalDrop(final PersonalDrop drop) {
        StoredPersonalDrop storedDrop = StoredPersonalDrop.from(drop);
        this.database.execute(connection -> {
            savePersonalDrop(connection, storedDrop);
            return null;
        });
    }

    /** Replaces a pending/recovered row with the UUID assigned to the spawned entity atomically. */
    public void replacePersonalDrop(final UUID previousDropId, final PersonalDrop activeDrop) {
        StoredPersonalDrop storedDrop = StoredPersonalDrop.from(activeDrop);
        this.database.transaction(database -> {
            savePersonalDrop(database, storedDrop);
            try (PreparedStatement statement = database.prepareStatement(
                "DELETE FROM personal_drops WHERE entity_uuid = ? AND entity_uuid <> ?"
            )) {
                statement.setString(1, previousDropId.toString());
                statement.setString(2, storedDrop.entityId());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void savePersonalDrop(final Connection database, final StoredPersonalDrop drop) throws SQLException {
        String sql = """
            INSERT INTO personal_drops(
                entity_uuid, owner_uuid, source_uuid, item, world_uuid, x, y, z, yaw, pitch, created, state
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(entity_uuid) DO UPDATE SET
                owner_uuid = excluded.owner_uuid,
                source_uuid = excluded.source_uuid,
                item = excluded.item,
                world_uuid = excluded.world_uuid,
                x = excluded.x,
                y = excluded.y,
                z = excluded.z,
                yaw = excluded.yaw,
                pitch = excluded.pitch,
                created = excluded.created,
                state = excluded.state
            """;
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            statement.setString(1, drop.entityId());
            statement.setString(2, drop.ownerId());
            statement.setString(3, drop.lootSourceId());
            statement.setBytes(4, drop.item());
            statement.setString(5, drop.worldId());
            statement.setDouble(6, drop.x());
            statement.setDouble(7, drop.y());
            statement.setDouble(8, drop.z());
            statement.setFloat(9, drop.yaw());
            statement.setFloat(10, drop.pitch());
            statement.setLong(11, drop.created());
            statement.setString(12, drop.state());
            statement.executeUpdate();
        }
    }

    public void setPersonalDropState(final UUID dropId, final PersonalDropState state) {
        if (state == PersonalDropState.PICKED_UP || state == PersonalDropState.EXPIRED) {
            removePersonalDrop(dropId);
            return;
        }

        this.database.execute(database -> {
            String sql = "UPDATE personal_drops SET state = ? WHERE entity_uuid = ?";
            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setString(1, state.name());
                statement.setString(2, dropId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void removePersonalDrop(final UUID dropId) {
        this.database.execute(database -> {
            try (PreparedStatement statement = database.prepareStatement(
                "DELETE FROM personal_drops WHERE entity_uuid = ?"
            )) {
                statement.setString(1, dropId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<PersonalDrop> getDropsForOwner(final UUID ownerId, final PersonalDropState... states) {
        return getPersonalDrops(ownerId, states);
    }

    public List<PersonalDrop> getPersonalDrops(final PersonalDropState... states) {
        return getPersonalDrops(null, states);
    }

    private List<PersonalDrop> getPersonalDrops(final UUID ownerId, final PersonalDropState... states) {
        List<StoredPersonalDrop> storedDrops = this.database.execute(database -> {
            List<StoredPersonalDrop> rows = new ArrayList<>();
            try (PreparedStatement statement = database.prepareStatement(personalDropQuery(ownerId, states))) {
                int parameterIndex = 1;
                if (ownerId != null) {
                    statement.setString(parameterIndex++, ownerId.toString());
                }
                for (PersonalDropState state : states) {
                    statement.setString(parameterIndex++, state.name());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) rows.add(readStoredPersonalDrop(resultSet));
                }
                return rows;
            }
        });

        List<PersonalDrop> drops = new ArrayList<>();
        for (StoredPersonalDrop storedDrop : storedDrops) {
            try {
                PersonalDrop drop = storedDrop.decode();
                if (drop != null) drops.add(drop);
            } catch (RuntimeException exception) {
                this.plugin.getLogger().log(
                    Level.WARNING,
                    "Skipping corrupt personal drop row " + storedDrop.entityId() + ".",
                    exception
                );
            }
        }
        return drops;
    }

    private String personalDropQuery(final UUID ownerId, final PersonalDropState... states) {
        List<String> conditions = new ArrayList<>();
        if (ownerId != null) {
            conditions.add("owner_uuid = ?");
        }
        if (states.length > 0) {
            conditions.add("state IN (" + String.join(", ", java.util.Collections.nCopies(states.length, "?")) + ")");
        }
        if (conditions.isEmpty()) {
            return "SELECT * FROM personal_drops";
        }
        return "SELECT * FROM personal_drops WHERE " + String.join(" AND ", conditions);
    }

    public void removeTerminalPersonalDrops() {
        this.database.execute(database -> {
            String sql = "DELETE FROM personal_drops WHERE state IN (?, ?)";
            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setString(1, PersonalDropState.PICKED_UP.name());
                statement.setString(2, PersonalDropState.EXPIRED.name());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void upsertContainerSource(
        final Connection database,
        final String containerKey,
        final StoredContainer block,
        final UUID entityId
    ) throws SQLException {
        String sql = """
            INSERT INTO container_sources(
                container_key, world_uuid, chunk_x, chunk_z, block_x, block_y, block_z, entity_uuid
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(container_key) DO UPDATE SET
                world_uuid = excluded.world_uuid,
                chunk_x = excluded.chunk_x,
                chunk_z = excluded.chunk_z,
                block_x = excluded.block_x,
                block_y = excluded.block_y,
                block_z = excluded.block_z,
                entity_uuid = excluded.entity_uuid
            """;
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            statement.setString(1, containerKey);
            if (block == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
                statement.setNull(3, java.sql.Types.INTEGER);
                statement.setNull(4, java.sql.Types.INTEGER);
                statement.setNull(5, java.sql.Types.INTEGER);
                statement.setNull(6, java.sql.Types.INTEGER);
                statement.setNull(7, java.sql.Types.INTEGER);
                statement.setString(8, entityId.toString());
            } else {
                statement.setString(2, block.worldId().toString());
                statement.setInt(3, block.chunkX());
                statement.setInt(4, block.chunkZ());
                statement.setInt(5, block.blockX());
                statement.setInt(6, block.blockY());
                statement.setInt(7, block.blockZ());
                statement.setNull(8, java.sql.Types.VARCHAR);
            }
            statement.executeUpdate();
        }
    }

    private static void deleteContainerData(
        final Connection database,
        final Collection<String> containerKeys
    ) throws SQLException {
        try (PreparedStatement inventories = database.prepareStatement(
            "DELETE FROM container_inventories WHERE container_key = ?"
        ); PreparedStatement sources = database.prepareStatement(
            "DELETE FROM container_sources WHERE container_key = ?"
        )) {
            for (String containerKey : containerKeys) {
                inventories.setString(1, containerKey);
                inventories.addBatch();
                sources.setString(1, containerKey);
                sources.addBatch();
            }
            inventories.executeBatch();
            sources.executeBatch();
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection().createStatement()) {
            boolean hadContainerInventories = tableExists("container_inventories");
            boolean hadContainerSources = tableExists("container_sources");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS container_inventories (
                    container_key TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    contents BLOB NOT NULL,
                    PRIMARY KEY(container_key, player_uuid)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS container_sources (
                    container_key TEXT PRIMARY KEY,
                    world_uuid TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    block_x INTEGER,
                    block_y INTEGER,
                    block_z INTEGER,
                    entity_uuid TEXT
                )
                """);
            if (hadContainerInventories && !hadContainerSources) backfillContainerSources();
            if (hasLegacyContainerLocationColumns()) rebuildContainerInventories(statement);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_container_sources_chunk
                ON container_sources(world_uuid, chunk_x, chunk_z)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_container_sources_entity
                ON container_sources(entity_uuid)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS frame_claims (
                    frame_uuid TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    world_uuid TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    PRIMARY KEY(frame_uuid, player_uuid)
                )
                """);
            addClaimLocationColumns(statement, "frame_claims");
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_frame_claims_chunk
                ON frame_claims(world_uuid, chunk_x, chunk_z)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS brushable_claims (
                    block_key TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    world_uuid TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    PRIMARY KEY(block_key, player_uuid)
                )
                """);
            if (addClaimLocationColumns(statement, "brushable_claims")) {
                backfillBrushableLocationColumns();
            }
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_brushable_claims_chunk
                ON brushable_claims(world_uuid, chunk_x, chunk_z)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS personal_drops (
                    entity_uuid TEXT PRIMARY KEY,
                    owner_uuid TEXT NOT NULL,
                    source_uuid TEXT NOT NULL,
                    item BLOB NOT NULL,
                    world_uuid TEXT,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    z REAL NOT NULL,
                    yaw REAL NOT NULL,
                    pitch REAL NOT NULL,
                    created INTEGER NOT NULL,
                    state TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_personal_drops_owner_state
                ON personal_drops(owner_uuid, state)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_personal_drops_source
                ON personal_drops(source_uuid)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_personal_drops_state
                ON personal_drops(state)
                """);
        }
    }

    private boolean addClaimLocationColumns(final Statement statement, final String tableName) throws SQLException {
        Set<String> columns = tableColumns(tableName);
        boolean migrated = !columns.contains("world_uuid")
            || !columns.contains("chunk_x")
            || !columns.contains("chunk_z");
        addColumnIfMissing(statement, tableName, columns, "world_uuid", "TEXT");
        addColumnIfMissing(statement, tableName, columns, "chunk_x", "INTEGER");
        addColumnIfMissing(statement, tableName, columns, "chunk_z", "INTEGER");
        return migrated;
    }

    private Set<String> tableColumns(final String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private boolean tableExists(final String tableName) throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            statement.setString(1, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void addColumnIfMissing(
        final Statement statement,
        final String tableName,
        final Set<String> columns,
        final String columnName,
        final String columnType
    ) throws SQLException {
        if (columns.contains(columnName)) {
            return;
        }
        statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
    }

    private void backfillBrushableLocationColumns() throws SQLException {
        List<ClaimRepository.StoredBlock> blocks = new ArrayList<>();
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery("""
                 SELECT DISTINCT block_key FROM brushable_claims
                 WHERE world_uuid IS NULL OR chunk_x IS NULL OR chunk_z IS NULL
                 """)) {
            while (rows.next()) {
                String blockKey = rows.getString("block_key");
                try {
                    blocks.add(ClaimRepository.StoredBlock.fromKey(blockKey));
                } catch (RuntimeException exception) {
                    this.plugin.getLogger().log(
                        Level.WARNING,
                        "Could not backfill corrupt brushable claim key " + blockKey + ".",
                        exception
                    );
                }
            }
        }
        try (PreparedStatement statement = connection().prepareStatement("""
            UPDATE brushable_claims SET world_uuid = ?, chunk_x = ?, chunk_z = ? WHERE block_key = ?
            """)) {
            for (ClaimRepository.StoredBlock block : blocks) {
                statement.setString(1, block.chunkKey().worldId().toString());
                statement.setInt(2, block.chunkKey().chunkX());
                statement.setInt(3, block.chunkKey().chunkZ());
                statement.setString(4, block.blockKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private boolean hasLegacyContainerLocationColumns() throws SQLException {
        Set<String> columns = tableColumns("container_inventories");
        return columns.contains("world_uuid") || columns.contains("entity_uuid");
    }

    private void backfillContainerSources() throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Statement statement = connection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT DISTINCT container_key FROM container_inventories")) {
            while (resultSet.next()) {
                keys.add(resultSet.getString("container_key"));
            }
        }
        for (String containerKey : keys) {
            try {
                UUID entityId = entityIdFromContainerKey(containerKey);
                StoredContainer block = entityId == null ? StoredContainer.fromKey(containerKey) : null;
                upsertContainerSource(connection(), containerKey, block, entityId);
            } catch (RuntimeException exception) {
                this.plugin.getLogger().log(
                    Level.WARNING,
                    "Could not migrate stored container source " + containerKey + ".",
                    exception
                );
            }
        }
    }

    private void rebuildContainerInventories(final Statement statement) throws SQLException {
        statement.execute("DROP TABLE IF EXISTS container_inventories_normalized");
        statement.execute("""
            CREATE TABLE container_inventories_normalized (
                container_key TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                contents BLOB NOT NULL,
                PRIMARY KEY(container_key, player_uuid)
            )
            """);
        statement.execute("""
            INSERT INTO container_inventories_normalized(container_key, player_uuid, contents)
            SELECT container_key, player_uuid, contents FROM container_inventories
            """);
        statement.execute("DROP TABLE container_inventories");
        statement.execute("ALTER TABLE container_inventories_normalized RENAME TO container_inventories");
    }

    boolean hasAnyData() {
        return this.database.execute(database -> {
            try (Statement statement = database.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                     SELECT
                       (SELECT COUNT(*) FROM container_inventories)
                       + (SELECT COUNT(*) FROM frame_claims)
                       + (SELECT COUNT(*) FROM brushable_claims)
                       + (SELECT COUNT(*) FROM personal_drops) AS total
                     """)) {
                return resultSet.next() && resultSet.getLong("total") > 0;
            }
        });
    }

    private static StoredPersonalDrop readStoredPersonalDrop(final ResultSet resultSet) throws SQLException {
        return new StoredPersonalDrop(
            resultSet.getString("entity_uuid"),
            resultSet.getString("owner_uuid"),
            resultSet.getString("source_uuid"),
            resultSet.getBytes("item"),
            resultSet.getString("world_uuid"),
            resultSet.getDouble("x"),
            resultSet.getDouble("y"),
            resultSet.getDouble("z"),
            resultSet.getFloat("yaw"),
            resultSet.getFloat("pitch"),
            resultSet.getLong("created"),
            resultSet.getString("state")
        );
    }

    private Connection connection() {
        return this.database.connection();
    }

    private static UUID entityIdFromContainerKey(final String containerKey) {
        if (!containerKey.startsWith("entity;")) {
            return null;
        }
        return UUID.fromString(containerKey.substring("entity;".length()));
    }

    private List<StoredContainer> readStoredContainers(final ResultSet resultSet) throws SQLException {
        List<StoredContainer> containers = new ArrayList<>();
        while (resultSet.next()) {
            try {
                containers.add(readStoredContainer(resultSet));
            } catch (RuntimeException exception) {
                this.plugin.getLogger().log(
                    Level.WARNING,
                    "Skipping corrupt stored container row " + resultSet.getString("container_key") + ".",
                    exception
                );
            }
        }
        return containers;
    }

    private record StoredPersonalDrop(
        String entityId,
        String ownerId,
        String lootSourceId,
        byte[] item,
        String worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long created,
        String state
    ) {
        private static StoredPersonalDrop from(final PersonalDrop drop) {
            Location location = drop.spawnLocation();
            World world = location.getWorld();
            return new StoredPersonalDrop(
                drop.entityId().toString(),
                drop.ownerId().toString(),
                drop.lootSourceId().toString(),
                ItemStackCodec.serializeItem(drop.itemStack()),
                world == null ? null : world.getUID().toString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                drop.creationTimestamp(),
                drop.state().name()
            );
        }

        private PersonalDrop decode() {
            World world = Bukkit.getWorld(UUID.fromString(this.worldId));
            if (world == null) return null;
            Location location = new Location(world, this.x, this.y, this.z, this.yaw, this.pitch);
            return new PersonalDrop(
                UUID.fromString(this.entityId),
                UUID.fromString(this.ownerId),
                UUID.fromString(this.lootSourceId),
                ItemStackCodec.deserializeItem(this.item),
                location,
                this.created,
                PersonalDropState.valueOf(this.state)
            );
        }
    }

    private StoredContainer readStoredContainer(final ResultSet resultSet) throws SQLException {
        String containerKey = resultSet.getString("container_key");
        String worldId = resultSet.getString("world_uuid");
        if (worldId == null || worldId.isBlank()) {
            return StoredContainer.fromKey(containerKey);
        }
        return new StoredContainer(
            containerKey,
            UUID.fromString(worldId),
            resultSet.getInt("chunk_x"),
            resultSet.getInt("chunk_z"),
            resultSet.getInt("block_x"),
            resultSet.getInt("block_y"),
            resultSet.getInt("block_z")
        );
    }

    public record StoredContainer(
        String containerKey,
        UUID worldId,
        int chunkX,
        int chunkZ,
        int blockX,
        int blockY,
        int blockZ
    ) {

        private static StoredContainer fromKey(final String containerKey) {
            String[] parts = containerKey.split(";");
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid container key: " + containerKey);
            }

            int blockX = Integer.parseInt(parts[1]);
            int blockY = Integer.parseInt(parts[2]);
            int blockZ = Integer.parseInt(parts[3]);
            return new StoredContainer(
                containerKey,
                UUID.fromString(parts[0]),
                Math.floorDiv(blockX, 16),
                Math.floorDiv(blockZ, 16),
                blockX,
                blockY,
                blockZ
            );
        }
    }
}

