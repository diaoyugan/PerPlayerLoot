package top.diaoyugan.perPlayerLoot.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class BrushSessionTest {
    @Test
    void sessionSnapshotCannotBeMutatedThroughInputsOrAccessors() {
        ItemStack item = mock(ItemStack.class);
        ItemStack ownedItem = mock(ItemStack.class);
        ItemStack returnedItem = mock(ItemStack.class);
        when(item.clone()).thenReturn(ownedItem);
        when(ownedItem.clone()).thenReturn(returnedItem);
        when(returnedItem.getAmount()).thenReturn(1);
        Location location = new Location(null, 4, 5, 6);
        BrushSession session = new BrushSession(3, 40L, List.of(item), location);
        location.setY(50);
        assertEquals(1, session.loot().get(0).getAmount());
        assertEquals(5, session.blockLocation().getY());
        session.blockLocation().setY(70);
        assertEquals(1, session.loot().get(0).getAmount());
        assertEquals(5, session.blockLocation().getY());
    }
}
