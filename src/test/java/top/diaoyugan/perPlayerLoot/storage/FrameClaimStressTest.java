package top.diaoyugan.perPlayerLoot.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("stress")
class FrameClaimStressTest {
    @TempDir Path temporaryDirectory;

    @Test
    @Timeout(180)
    void repeatedTwoPlayerClaimsNeverCreateMoreThanOneDropPerPlayerAndFrame() throws Exception {
        int frameCount = Integer.getInteger("ppl.stress.frames", 10_000);
        int replays = Integer.getInteger("ppl.stress.replays", 8);
        long seed = Long.getLong("ppl.stress.seed", System.nanoTime());
        SplittableRandom random = new SplittableRandom(seed);
        UUID[] players = {UUID.randomUUID(), UUID.randomUUID()};
        long started = System.nanoTime();

        try (SqliteDatabase database = new SqliteDatabase(
            this.temporaryDirectory.resolve("frame-claim-stress.sqlite").toFile(),
            Logger.getAnonymousLogger()
        )) {
            database.open("");
            createTables(database);
            ClaimRepository claims = new ClaimRepository(database, Logger.getAnonymousLogger(), Runnable::run);
            ChunkKey chunkKey = new ChunkKey(UUID.randomUUID(), 12, -34);
            claims.loadChunk(chunkKey, Set.of());
            while (claims.state(chunkKey) == ClaimRepository.State.LOADING) Thread.onSpinWait();
            assertEquals(ClaimRepository.State.READY, claims.state(chunkKey));
            int successfulClaims = 0;
            int rejectedClaims = 0;

            int[] attempts = new int[Math.multiplyExact(players.length, replays)];
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                UUID frameId = UUID.randomUUID();
                fillAttempts(attempts, players.length);
                shuffle(attempts, random);
                for (int playerIndex : attempts) {
                    UUID playerId = players[playerIndex];
                    boolean claimed = claimWithDrop(database, claims, chunkKey, frameId, playerId);
                    if (claimed) {
                        successfulClaims++;
                        claims.recordFrame(chunkKey, frameId, playerId);
                    } else {
                        rejectedClaims++;
                    }
                }
                for (UUID playerId : players) {
                    assertTrue(claims.hasFrame(chunkKey, frameId, playerId), () -> diagnostic(
                        seed, frameCount, replays, "missing in-memory claim for " + frameId + "/" + playerId
                    ));
                }
            }

            int expectedClaims = Math.multiplyExact(frameCount, players.length);
            assertEquals(expectedClaims, successfulClaims, diagnostic(
                seed, frameCount, replays, "unexpected successful claim count=" + successfulClaims
            ));
            assertEquals(Math.multiplyExact(frameCount, attempts.length) - expectedClaims, rejectedClaims);
            assertEquals(expectedClaims, count(database, "frame_claims"));
            assertEquals(expectedClaims, count(database, "stress_drops"));

            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            System.out.printf(
                "Frame claim stress passed: seed=%d, frames=%d, attempts=%d, claims=%d, rejected=%d, elapsed=%dms%n",
                seed,
                frameCount,
                Math.multiplyExact(frameCount, attempts.length),
                successfulClaims,
                rejectedClaims,
                elapsed.toMillis()
            );
        }
    }

    private static boolean claimWithDrop(
        final SqliteDatabase database,
        final ClaimRepository claims,
        final ChunkKey chunkKey,
        final UUID frameId,
        final UUID playerId
    ) {
        return database.transaction(connection -> {
            if (!claims.insertFrame(connection, chunkKey, frameId, playerId)) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO stress_drops(drop_uuid, frame_uuid, player_uuid) VALUES(?, ?, ?)"
            )) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, frameId.toString());
                statement.setString(3, playerId.toString());
                statement.executeUpdate();
            }
            return true;
        });
    }

    private static void createTables(final SqliteDatabase database) throws Exception {
        database.execute(connection -> {
            connection.createStatement().execute("""
                CREATE TABLE frame_claims(
                    frame_uuid TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    world_uuid TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    PRIMARY KEY(frame_uuid, player_uuid)
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE brushable_claims(
                    block_key TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    world_uuid TEXT,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    PRIMARY KEY(block_key, player_uuid)
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE stress_drops(
                    drop_uuid TEXT NOT NULL PRIMARY KEY,
                    frame_uuid TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    UNIQUE(frame_uuid, player_uuid)
                )
                """);
            return null;
        });
    }

    private static void fillAttempts(final int[] attempts, final int playerCount) {
        for (int index = 0; index < attempts.length; index++) {
            attempts[index] = index % playerCount;
        }
    }

    private static void shuffle(final int[] values, final SplittableRandom random) {
        for (int index = values.length - 1; index > 0; index--) {
            int replacement = random.nextInt(index + 1);
            int value = values[index];
            values[index] = values[replacement];
            values[replacement] = value;
        }
    }

    private static int count(final SqliteDatabase database, final String table) throws Exception {
        String sql = switch (table) {
            case "frame_claims" -> "SELECT COUNT(*) FROM frame_claims";
            case "stress_drops" -> "SELECT COUNT(*) FROM stress_drops";
            default -> throw new IllegalArgumentException(table);
        };
        return database.execute(connection -> {
            try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private static String diagnostic(
        final long seed,
        final int frameCount,
        final int replays,
        final String detail
    ) {
        return "seed=" + seed + ", frames=" + frameCount + ", replays=" + replays + ", " + detail;
    }
}
