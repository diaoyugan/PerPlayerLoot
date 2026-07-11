package top.diaoyugan.perPlayerLoot.personal;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public record PersonalDrop(
    UUID entityId,
    UUID ownerId,
    UUID lootSourceId,
    ItemStack itemStack,
    Location spawnLocation,
    long creationTimestamp,
    PersonalDropState state
) {

    PersonalDrop withEntityId(final UUID newEntityId) {
        return new PersonalDrop(
            newEntityId,
            this.ownerId,
            this.lootSourceId,
            this.itemStack,
            this.spawnLocation,
            this.creationTimestamp,
            this.state
        );
    }

    PersonalDrop withState(final PersonalDropState newState) {
        return new PersonalDrop(
            this.entityId,
            this.ownerId,
            this.lootSourceId,
            this.itemStack,
            this.spawnLocation,
            this.creationTimestamp,
            newState
        );
    }
}

