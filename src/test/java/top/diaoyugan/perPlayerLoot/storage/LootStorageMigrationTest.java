package top.diaoyugan.perPlayerLoot.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.diaoyugan.perPlayerLoot.PerPlayerLoot;
import top.diaoyugan.perPlayerLoot.config.PluginSettings;

class LootStorageMigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void normalizesLegacyContainerRowsIntoOneSourceRowPerContainer() throws Exception {
        Path databaseFile = this.temporaryDirectory.resolve("loot-data.sqlite");
        UUID worldId = UUID.randomUUID();
        String containerKey = worldId + ";34;70;-17";
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            connection.createStatement().execute("""
                CREATE TABLE container_inventories(
                    container_key TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    contents BLOB NOT NULL,
                    world_uuid TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    block_x INTEGER,
                    block_y INTEGER,
                    block_z INTEGER,
                    entity_uuid TEXT,
                    PRIMARY KEY(container_key, player_uuid)
                )
                """);
            try (var insert = connection.prepareStatement("""
                INSERT INTO container_inventories VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """)) {
                for (int index = 0; index < 3; index++) {
                    insert.setString(1, containerKey);
                    insert.setString(2, UUID.randomUUID().toString());
                    insert.setBytes(3, new byte[] {(byte) index});
                    insert.setString(4, worldId.toString());
                    insert.setInt(5, 2);
                    insert.setInt(6, -2);
                    insert.setInt(7, 34);
                    insert.setInt(8, 70);
                    insert.setInt(9, -17);
                    insert.executeUpdate();
                }
            }
        }

        PerPlayerLoot plugin = mock(PerPlayerLoot.class);
        when(plugin.getDataFolder()).thenReturn(this.temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        PluginSettings settings = mock(PluginSettings.class);
        when(settings.database()).thenReturn(new PluginSettings.Database(""));
        when(plugin.settings()).thenReturn(settings);

        LootStorage storage = new LootStorage(plugin);
        storage.load();
        storage.save();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            Set<String> columns = new HashSet<>();
            try (var rows = connection.createStatement().executeQuery("PRAGMA table_info(container_inventories)")) {
                while (rows.next()) columns.add(rows.getString("name"));
            }
            assertEquals(Set.of("container_key", "player_uuid", "contents"), columns);

            try (var rows = connection.createStatement().executeQuery("SELECT * FROM container_sources")) {
                assertTrue(rows.next());
                assertEquals(containerKey, rows.getString("container_key"));
                assertEquals(worldId.toString(), rows.getString("world_uuid"));
                assertEquals(2, rows.getInt("chunk_x"));
                assertEquals(-2, rows.getInt("chunk_z"));
                assertFalse(rows.next());
            }
            try (var rows = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM container_inventories"
            )) {
                rows.next();
                assertEquals(3, rows.getInt(1));
            }
        }
    }
}
