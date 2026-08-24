package top.diaoyugan.perPlayerLoot.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimIndexTest {
    @Test
    void indexesClaimsBySourceAndPlayerAndRemovesOnlyOneSource() {
        ClaimIndex index = new ClaimIndex();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID frameA = UUID.randomUUID();
        UUID frameB = UUID.randomUUID();
        index.add(frameA.toString(), playerA);
        index.add(frameA.toString(), playerB);
        index.add(frameB.toString(), playerA);

        assertTrue(index.contains(frameA.toString(), playerA));
        assertTrue(index.sourcesAsUuidsForPlayer(playerA).contains(frameB));
        index.removeSource(frameA.toString());
        assertFalse(index.contains(frameA.toString(), playerA));
        assertFalse(index.contains(frameA.toString(), playerB));
        assertTrue(index.contains(frameB.toString(), playerA));
    }
}
