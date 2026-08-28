package top.diaoyugan.perPlayerLoot.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseTest {
    @TempDir Path temporaryDirectory;

    @Test
    void closeIsSafeBeforeOpenAndIdempotent() {
        SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve("never-opened.sqlite").toFile(), Logger.getAnonymousLogger()
        );
        database.close();
        database.close();
    }

    @Test
    void rejectsOpeningTheSameConnectionTwice() throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve("open-once.sqlite").toFile(), Logger.getAnonymousLogger()
        )) {
            database.open("");
            assertThrows(java.sql.SQLException.class, () -> database.open(""));
        }
    }

    @Test
    void commitsSuccessfulTransactionAndRollsBackFailedTransaction() throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve("loot.sqlite").toFile(), Logger.getAnonymousLogger()
        )) {
            database.open("");
            database.execute(connection -> {
                connection.createStatement().execute(
                    "CREATE TABLE claims(source TEXT NOT NULL, player TEXT NOT NULL, PRIMARY KEY(source, player))"
                );
                return null;
            });
            database.transaction(connection -> {
                connection.createStatement().execute("INSERT INTO claims VALUES('frame', 'player-a')");
                return null;
            });
            assertEquals(1, rowCount(database));

            assertThrows(IllegalStateException.class, () -> database.transaction(connection -> {
                connection.createStatement().execute("INSERT INTO claims VALUES('brushable', 'player-b')");
                connection.createStatement().execute("INSERT INTO missing_table VALUES(1)");
                return null;
            }));
            assertEquals(1, rowCount(database));
        }
    }

    @Test
    void serializesSubmittedOperationsOnOneDatabaseThread() throws Exception {
        try (SqliteDatabase database = openClaimDatabase()) {
            Set<Thread> threads = ConcurrentHashMap.newKeySet();
            CompletableFuture<?>[] operations = new CompletableFuture<?>[32];
            for (int index = 0; index < operations.length; index++) {
                operations[index] = database.submit(connection -> {
                    threads.add(Thread.currentThread());
                    return null;
                });
            }
            CompletableFuture.allOf(operations).join();
            assertEquals(1, threads.size());
            assertTrue(threads.iterator().next().getName().contains("PerPlayerLoot-SQLite"));
        }
    }

    @Test
    void commitsAndRollsBackSubmittedTransactions() throws Exception {
        try (SqliteDatabase database = openClaimDatabase()) {
            database.submitTransaction(connection -> {
                connection.createStatement().execute("INSERT INTO claims VALUES('async', 'player-a')");
                return null;
            }).join();
            assertEquals(1, count(database, "claims"));

            assertThrows(java.util.concurrent.CompletionException.class, () ->
                database.submitTransaction(connection -> {
                    connection.createStatement().execute("INSERT INTO claims VALUES('rolled-back', 'player-b')");
                    connection.createStatement().execute("INSERT INTO missing_table VALUES(1)");
                    return null;
                }).join()
            );
            assertEquals(1, count(database, "claims"));
        }
    }

    @Test
    void claimAndRecoverableDropCommitTogetherAndDuplicateClaimCreatesNothing() throws Exception {
        try (SqliteDatabase database = openClaimDatabase()) {
            assertTrue(claimWithDrop(database, "source-a", "player-a", "drop-a"));
            assertFalse(claimWithDrop(database, "source-a", "player-a", "drop-b"));
            assertEquals(1, count(database, "claims"));
            assertEquals(1, count(database, "drops"));
        }
    }

    @Test
    void failedDropInsertRollsBackClaimReservation() throws Exception {
        try (SqliteDatabase database = openClaimDatabase()) {
            assertThrows(IllegalStateException.class, () -> database.transaction(connection -> {
                connection.createStatement().execute("INSERT INTO claims VALUES('source-b', 'player-b')");
                connection.createStatement().execute("INSERT INTO drops VALUES(NULL, 'source-b')");
                return null;
            }));
            assertEquals(0, count(database, "claims"));
            assertEquals(0, count(database, "drops"));
        }
    }

    private static int rowCount(final SqliteDatabase database) throws Exception {
        return database.execute(connection -> {
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM claims")) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private SqliteDatabase openClaimDatabase() throws Exception {
        SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve(UUID.randomUUID() + ".sqlite").toFile(), Logger.getAnonymousLogger()
        );
        database.open("");
        database.execute(connection -> {
            connection.createStatement().execute(
                "CREATE TABLE claims(source TEXT NOT NULL, player TEXT NOT NULL, PRIMARY KEY(source, player))"
            );
            connection.createStatement().execute(
                "CREATE TABLE drops(id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL)"
            );
            return null;
        });
        return database;
    }

    private static boolean claimWithDrop(
        final SqliteDatabase database, final String source, final String player, final String drop
    ) {
        return database.transaction(connection -> {
            try (var claim = connection.prepareStatement("INSERT OR IGNORE INTO claims VALUES(?, ?)")) {
                claim.setString(1, source);
                claim.setString(2, player);
                if (claim.executeUpdate() == 0) return false;
            }
            try (var personalDrop = connection.prepareStatement("INSERT INTO drops VALUES(?, ?)")) {
                personalDrop.setString(1, drop);
                personalDrop.setString(2, source);
                personalDrop.executeUpdate();
            }
            return true;
        });
    }

    private static int count(final SqliteDatabase database, final String table) throws Exception {
        String sql = switch (table) {
            case "claims" -> "SELECT COUNT(*) FROM claims";
            case "drops" -> "SELECT COUNT(*) FROM drops";
            default -> throw new IllegalArgumentException(table);
        };
        return database.execute(connection -> {
            try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }
}
