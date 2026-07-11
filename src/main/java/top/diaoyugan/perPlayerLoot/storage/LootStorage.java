package top.diaoyugan.perPlayerLoot.storage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.personal.PersonalDrop;
import top.diaoyugan.perPlayerLoot.personal.PersonalDropState;

public final class LootStorage {

    private final PerPlayerLoot plugin;
    private final File databaseFile;
    private Connection connection;

    public LootStorage(final PerPlayerLoot plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "loot-data.sqlite");
    }

    public void load() {
        if (!this.plugin.getDataFolder().exists() && !this.plugin.getDataFolder().mkdirs()) {
            this.plugin.getLogger().warning("Could not create plugin data folder.");
        }

        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.databaseFile.getAbsolutePath());
            try (Statement statement = this.connection.createStatement()) {
                applyDatabasePassword(statement);
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }
            createTables();
            migrateYamlIfNeeded();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open SQLite loot storage.", exception);
        }
    }

    public void save() {
        if (this.connection == null) {
            return;
        }

        try {
            this.connection.close();
        } catch (SQLException exception) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not close SQLite loot storage.", exception);
        } finally {
            this.connection = null;
        }
    }

    public boolean hasContainerInventory(final String containerKey, final UUID playerId) {
        String sql = "SELECT 1 FROM container_inventories WHERE container_key = ? AND player_uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, containerKey);
            statement.setString(2, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public ItemStack[] getContainerInventory(final String containerKey, final UUID playerId, final int size) {
        String sql = "SELECT contents FROM container_inventories WHERE container_key = ? AND player_uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, containerKey);
            statement.setString(2, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new ItemStack[size];
                }

                ItemStack[] storedContents = deserializeItems(resultSet.getBytes("contents"));
                ItemStack[] contents = new ItemStack[size];
                System.arraycopy(storedContents, 0, contents, 0, Math.min(storedContents.length, size));
                return contents;
            }
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public void setContainerInventory(final String containerKey, final UUID playerId, final ItemStack[] contents) {
        String sql = """
            INSERT INTO container_inventories(container_key, player_uuid, contents)
            VALUES(?, ?, ?)
            ON CONFLICT(container_key, player_uuid) DO UPDATE SET contents = excluded.contents
            """;
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, containerKey);
            statement.setString(2, playerId.toString());
            statement.setBytes(3, serializeItems(contents));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public boolean hasContainerData(final String containerKey) {
        String sql = "SELECT 1 FROM container_inventories WHERE container_key = ? LIMIT 1";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, containerKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public void removeContainerData(final String containerKey) {
        try (PreparedStatement statement = connection().prepareStatement("DELETE FROM container_inventories WHERE container_key = ?")) {
            statement.setString(1, containerKey);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public boolean hasClaimedFrame(final UUID frameId, final UUID playerId) {
        String sql = "SELECT 1 FROM frame_claims WHERE frame_uuid = ? AND player_uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, frameId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public void setClaimedFrame(final UUID frameId, final UUID playerId) {
        String sql = "INSERT OR IGNORE INTO frame_claims(frame_uuid, player_uuid) VALUES(?, ?)";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, frameId.toString());
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public void savePersonalDrop(final PersonalDrop drop) {
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
        Location location = drop.spawnLocation();
        World world = location.getWorld();
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, drop.entityId().toString());
            statement.setString(2, drop.ownerId().toString());
            statement.setString(3, drop.lootSourceId().toString());
            statement.setBytes(4, serializeItem(drop.itemStack()));
            statement.setString(5, world == null ? null : world.getUID().toString());
            statement.setDouble(6, location.getX());
            statement.setDouble(7, location.getY());
            statement.setDouble(8, location.getZ());
            statement.setFloat(9, location.getYaw());
            statement.setFloat(10, location.getPitch());
            statement.setLong(11, drop.creationTimestamp());
            statement.setString(12, drop.state().name());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public void setPersonalDropState(final UUID dropId, final PersonalDropState state) {
        String sql = "UPDATE personal_drops SET state = ? WHERE entity_uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, state.name());
            statement.setString(2, dropId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public void removePersonalDrop(final UUID dropId) {
        try (PreparedStatement statement = connection().prepareStatement("DELETE FROM personal_drops WHERE entity_uuid = ?")) {
            statement.setString(1, dropId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public List<PersonalDrop> getDropsForOwner(final UUID ownerId, final PersonalDropState... states) {
        List<PersonalDrop> drops = new ArrayList<>();
        for (PersonalDrop drop : getPersonalDrops(states)) {
            if (drop.ownerId().equals(ownerId)) {
                drops.add(drop);
            }
        }
        return drops;
    }

    public List<PersonalDrop> getPersonalDrops(final PersonalDropState... states) {
        Set<PersonalDropState> allowedStates = Set.of(states);
        List<PersonalDrop> drops = new ArrayList<>();
        String sql = "SELECT * FROM personal_drops";
        try (PreparedStatement statement = connection().prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                PersonalDrop drop = readPersonalDrop(resultSet);
                if (drop != null && allowedStates.contains(drop.state())) {
                    drops.add(drop);
                }
            }
            return drops;
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    public Set<UUID> getClaimedFrameIds(final UUID playerId) {
        Set<UUID> frameIds = new HashSet<>();
        String sql = "SELECT frame_uuid FROM frame_claims WHERE player_uuid = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    frameIds.add(UUID.fromString(resultSet.getString("frame_uuid")));
                }
            }
            return frameIds;
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection().createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS container_inventories (
                    container_key TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    contents BLOB NOT NULL,
                    PRIMARY KEY(container_key, player_uuid)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS frame_claims (
                    frame_uuid TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    PRIMARY KEY(frame_uuid, player_uuid)
                )
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
        }
    }

    private void applyDatabasePassword(final Statement statement) throws SQLException {
        String password = this.plugin.getConfig().getString("database.password", "");
        if (password == null || password.isBlank()) {
            return;
        }

        statement.execute("PRAGMA key = '" + password.replace("'", "''") + "'");
    }

    private void migrateYamlIfNeeded() {
        File yamlFile = new File(this.plugin.getDataFolder(), "loot-data.yml");
        File migratedMarker = new File(this.plugin.getDataFolder(), "loot-data.yml.migrated");
        if (!yamlFile.exists() || migratedMarker.exists() || hasAnyData()) {
            return;
        }

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(yamlFile);
        migrateContainers(yaml);
        migrateFrameClaims(yaml);
        migratePersonalDrops(yaml);

        if (!yamlFile.renameTo(migratedMarker)) {
            this.plugin.getLogger().warning("Migrated loot-data.yml to SQLite, but could not rename the old file.");
        }
    }

    private boolean hasAnyData() {
        try (Statement statement = connection().createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT
                   (SELECT COUNT(*) FROM container_inventories)
                   + (SELECT COUNT(*) FROM frame_claims)
                   + (SELECT COUNT(*) FROM personal_drops) AS total
                 """)) {
            return resultSet.next() && resultSet.getLong("total") > 0;
        } catch (SQLException exception) {
            throw storageException(exception);
        }
    }

    private void migrateContainers(final FileConfiguration yaml) {
        if (!yaml.isConfigurationSection("containers")) {
            return;
        }

        for (String escapedContainerKey : yaml.getConfigurationSection("containers").getKeys(false)) {
            String containerKey = escapedContainerKey.replace("%2E", ".");
            String containerPath = "containers." + escapedContainerKey;
            if (!yaml.isConfigurationSection(containerPath)) {
                continue;
            }
            for (String playerId : yaml.getConfigurationSection(containerPath).getKeys(false)) {
                List<?> storedItems = yaml.getList(containerPath + "." + playerId + ".contents", List.of());
                ItemStack[] contents = new ItemStack[storedItems.size()];
                for (int slot = 0; slot < storedItems.size(); slot++) {
                    Object storedItem = storedItems.get(slot);
                    if (storedItem instanceof ItemStack itemStack) {
                        contents[slot] = itemStack;
                    }
                }
                setContainerInventory(containerKey, UUID.fromString(playerId), contents);
            }
        }
    }

    private void migrateFrameClaims(final FileConfiguration yaml) {
        if (!yaml.isConfigurationSection("frames")) {
            return;
        }

        for (String frameId : yaml.getConfigurationSection("frames").getKeys(false)) {
            String claimedPath = "frames." + frameId + ".claimed";
            if (!yaml.isConfigurationSection(claimedPath)) {
                continue;
            }
            UUID frameUuid = UUID.fromString(frameId);
            for (String playerId : yaml.getConfigurationSection(claimedPath).getKeys(false)) {
                if (yaml.getBoolean(claimedPath + "." + playerId, false)) {
                    setClaimedFrame(frameUuid, UUID.fromString(playerId));
                }
            }
        }
    }

    private void migratePersonalDrops(final FileConfiguration yaml) {
        if (!yaml.isConfigurationSection("drops")) {
            return;
        }

        for (String dropId : yaml.getConfigurationSection("drops").getKeys(false)) {
            String path = "drops." + dropId;
            ItemStack itemStack = yaml.getItemStack(path + ".item");
            Location location = readYamlLocation(yaml, path + ".location");
            if (itemStack == null || location == null) {
                continue;
            }
            PersonalDrop drop = new PersonalDrop(
                UUID.fromString(dropId),
                UUID.fromString(yaml.getString(path + ".owner")),
                UUID.fromString(yaml.getString(path + ".source")),
                itemStack,
                location,
                yaml.getLong(path + ".created"),
                PersonalDropState.valueOf(yaml.getString(path + ".state", "RECOVERED"))
            );
            savePersonalDrop(drop);
        }
    }

    private PersonalDrop readPersonalDrop(final ResultSet resultSet) throws SQLException {
        World world = Bukkit.getWorld(UUID.fromString(resultSet.getString("world_uuid")));
        if (world == null) {
            return null;
        }

        Location location = new Location(
            world,
            resultSet.getDouble("x"),
            resultSet.getDouble("y"),
            resultSet.getDouble("z"),
            resultSet.getFloat("yaw"),
            resultSet.getFloat("pitch")
        );
        return new PersonalDrop(
            UUID.fromString(resultSet.getString("entity_uuid")),
            UUID.fromString(resultSet.getString("owner_uuid")),
            UUID.fromString(resultSet.getString("source_uuid")),
            deserializeItem(resultSet.getBytes("item")),
            location,
            resultSet.getLong("created"),
            PersonalDropState.valueOf(resultSet.getString("state"))
        );
    }

    private static Location readYamlLocation(final FileConfiguration yaml, final String path) {
        String worldId = yaml.getString(path + ".world");
        if (worldId == null) {
            return null;
        }

        World world = Bukkit.getWorld(UUID.fromString(worldId));
        if (world == null) {
            return null;
        }
        return new Location(
            world,
            yaml.getDouble(path + ".x"),
            yaml.getDouble(path + ".y"),
            yaml.getDouble(path + ".z"),
            (float) yaml.getDouble(path + ".yaw"),
            (float) yaml.getDouble(path + ".pitch")
        );
    }

    private Connection connection() {
        if (this.connection == null) {
            throw new IllegalStateException("Loot storage is not loaded.");
        }
        return this.connection;
    }

    private static byte[] serializeItems(final ItemStack[] items) {
        return ItemStack.serializeItemsAsBytes(items);
    }

    private static ItemStack[] deserializeItems(final byte[] bytes) {
        try {
            return ItemStack.deserializeItemsFromBytes(bytes);
        } catch (RuntimeException exception) {
            // Older plugin versions stored BukkitObjectOutputStream blobs; keep them readable.
            return legacyDeserialize(bytes, ItemStack[].class);
        }
    }

    private static byte[] serializeItem(final ItemStack item) {
        return item.serializeAsBytes();
    }

    private static ItemStack deserializeItem(final byte[] bytes) {
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException exception) {
            // Older plugin versions stored BukkitObjectOutputStream blobs; keep them readable.
            return legacyDeserialize(bytes, ItemStack.class);
        }
    }

    @SuppressWarnings("deprecation")
    private static <T> T legacyDeserialize(final byte[] bytes, final Class<T> type) {
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream objectStream = new BukkitObjectInputStream(byteStream)) {
            return type.cast(objectStream.readObject());
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Could not deserialize loot data.", exception);
        }
    }

    private IllegalStateException storageException(final SQLException exception) {
        return new IllegalStateException("SQLite loot storage operation failed.", exception);
    }
}

