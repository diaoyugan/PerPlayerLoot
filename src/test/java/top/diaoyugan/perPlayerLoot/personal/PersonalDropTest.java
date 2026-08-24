package top.diaoyugan.perPlayerLoot.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class PersonalDropTest {
    @Test
    void ownsDefensiveCopiesOfMutableValues() {
        ItemStack item = mock(ItemStack.class);
        ItemStack ownedItem = mock(ItemStack.class);
        ItemStack returnedItem = mock(ItemStack.class);
        when(item.clone()).thenReturn(ownedItem);
        when(ownedItem.clone()).thenReturn(returnedItem);
        when(returnedItem.getAmount()).thenReturn(2);
        Location location = new Location(null, 1, 2, 3);
        PersonalDrop drop = new PersonalDrop(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), item, location, 1L, PersonalDropState.PENDING
        );
        location.setX(99);
        assertEquals(2, drop.itemStack().getAmount());
        assertEquals(1, drop.spawnLocation().getX());

        drop.spawnLocation().setX(88);
        assertEquals(2, drop.itemStack().getAmount());
        assertEquals(1, drop.spawnLocation().getX());
    }
}
