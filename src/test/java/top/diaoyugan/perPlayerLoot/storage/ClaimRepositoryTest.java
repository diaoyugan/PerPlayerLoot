package top.diaoyugan.perPlayerLoot.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaimRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void loadsOnlyRequestedChunkSkipsDirtyRowsAndEvictsOnUnload() throws Exception {
        try (SqliteDatabase database = openDatabase()) {
            UUID world = UUID.randomUUID();
            UUID player = UUID.randomUUID();
            UUID frame = UUID.randomUUID();
            ChunkKey requested = new ChunkKey(world, 2, -3);
            ChunkKey other = new ChunkKey(world, 40, 50);
            insertBrushable(database, world + ";32;70;-48", player.toString(), requested);
            insertBrushable(database, world + ";640;70;800", player.toString(), other);
            insertBrushable(database, world + ";33;70;-47", "not-a-uuid", requested);
            insertFrame(database, frame, player, requested);

            ClaimRepository repository = new ClaimRepository(database, Logger.getAnonymousLogger(), Runnable::run);
            repository.loadChunk(requested, Set.of(frame));
            awaitState(repository, requested, ClaimRepository.State.READY);

            assertTrue(repository.hasBrushable(
                ClaimRepository.StoredBlock.fromKey(world + ";32;70;-48"), player
            ));
            assertFalse(repository.hasBrushable(
                ClaimRepository.StoredBlock.fromKey(world + ";640;70;800"), player
            ));
            assertTrue(repository.hasFrame(requested, frame, player));

            repository.removeFrame(requested, frame).join();
            assertFalse(repository.hasFrame(requested, frame, player));
            int remainingFrames = database.execute(connection -> {
                try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM frame_claims")) {
                    rows.next();
                    return rows.getInt(1);
                }
            });
            assertEquals(0, remainingFrames);

            repository.unloadChunk(requested);
            assertEquals(ClaimRepository.State.UNLOADED, repository.state(requested));
            assertFalse(repository.hasFrame(requested, frame, player));
        }
    }

    @Test
    void retriesFailedChunkLoadAndPublishesRecoveredData() throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve("retry.sqlite").toFile(), Logger.getAnonymousLogger()
        )) {
            database.open("");
            LinkedBlockingQueue<Runnable> retries = new LinkedBlockingQueue<>();
            ClaimRepository repository = new ClaimRepository(
                database,
                Logger.getAnonymousLogger(),
                Runnable::run,
                (task, delay) -> retries.add(task)
            );
            ChunkKey key = new ChunkKey(UUID.randomUUID(), 4, 5);
            repository.loadChunk(key, Set.of());
            awaitState(repository, key, ClaimRepository.State.FAILED);

            database.execute(connection -> {
                connection.createStatement().execute("""
                    CREATE TABLE frame_claims(
                        frame_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, world_uuid TEXT,
                        chunk_x INTEGER, chunk_z INTEGER, PRIMARY KEY(frame_uuid, player_uuid)
                    )
                    """);
                connection.createStatement().execute("""
                    CREATE TABLE brushable_claims(
                        block_key TEXT NOT NULL, player_uuid TEXT NOT NULL, world_uuid TEXT,
                        chunk_x INTEGER, chunk_z INTEGER, PRIMARY KEY(block_key, player_uuid)
                    )
                    """);
                return null;
            });

            Runnable retry = retries.poll(5, TimeUnit.SECONDS);
            assertTrue(retry != null);
            retry.run();
            awaitState(repository, key, ClaimRepository.State.READY);
        }
    }

    @Test
    void lateQueryCannotRecreateAnUnloadedGhostChunk() throws Exception {
        try (SqliteDatabase database = openDatabase()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            database.submit(connection -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            ClaimRepository repository = new ClaimRepository(database, Logger.getAnonymousLogger(), Runnable::run);
            ChunkKey key = new ChunkKey(UUID.randomUUID(), 1, 1);
            repository.loadChunk(key, Set.of());
            repository.unloadChunk(key);
            release.countDown();
            database.execute(connection -> null);

            assertEquals(ClaimRepository.State.UNLOADED, repository.state(key));
        }
    }

    private SqliteDatabase openDatabase() throws Exception {
        SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve(UUID.randomUUID() + ".sqlite").toFile(),
            Logger.getAnonymousLogger()
        );
        database.open("");
        database.execute(connection -> {
            connection.createStatement().execute("""
                CREATE TABLE frame_claims(
                    frame_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, world_uuid TEXT,
                    chunk_x INTEGER, chunk_z INTEGER, PRIMARY KEY(frame_uuid, player_uuid)
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE brushable_claims(
                    block_key TEXT NOT NULL, player_uuid TEXT NOT NULL, world_uuid TEXT,
                    chunk_x INTEGER, chunk_z INTEGER, PRIMARY KEY(block_key, player_uuid)
                )
                """);
            return null;
        });
        return database;
    }

    private static void insertBrushable(
        final SqliteDatabase database,
        final String blockKey,
        final String playerId,
        final ChunkKey chunk
    ) {
        database.execute(connection -> {
            try (var statement = connection.prepareStatement(
                "INSERT INTO brushable_claims VALUES(?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, blockKey);
                statement.setString(2, playerId);
                statement.setString(3, chunk.worldId().toString());
                statement.setInt(4, chunk.chunkX());
                statement.setInt(5, chunk.chunkZ());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void insertFrame(
        final SqliteDatabase database,
        final UUID frameId,
        final UUID playerId,
        final ChunkKey chunk
    ) {
        database.execute(connection -> {
            try (var statement = connection.prepareStatement(
                "INSERT INTO frame_claims VALUES(?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, frameId.toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, chunk.worldId().toString());
                statement.setInt(4, chunk.chunkX());
                statement.setInt(5, chunk.chunkZ());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void awaitState(
        final ClaimRepository repository,
        final ChunkKey key,
        final ClaimRepository.State expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (repository.state(key) != expected && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(expected, repository.state(key));
    }
}
