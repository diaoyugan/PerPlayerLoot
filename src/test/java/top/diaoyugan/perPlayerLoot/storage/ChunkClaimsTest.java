package top.diaoyugan.perPlayerLoot.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkClaimsTest {
    @Test
    void indexesClaimsBySourceAndPlayerAndRemovesOneBrushableInConstantTime() {
        ChunkClaims claims = new ChunkClaims();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID frame = UUID.randomUUID();
        BlockPos blockA = new BlockPos(1, 2, 3);
        BlockPos blockB = new BlockPos(4, 5, 6);

        claims.addFrame(frame, playerA);
        claims.addFrame(frame, playerB);
        claims.addBrushable(blockA, playerA);
        claims.addBrushable(blockB, playerA);

        assertTrue(claims.hasFrame(frame, playerA));
        assertTrue(claims.hasFrame(frame, playerB));
        assertTrue(claims.hasBrushable(blockB, playerA));
        claims.removeBrushable(blockA);
        assertFalse(claims.hasBrushable(blockA, playerA));
        assertTrue(claims.hasBrushable(blockB, playerA));
    }
}
