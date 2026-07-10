package top.diaoyugan.perPlayerLoot;

import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;

interface PersonalEntityVisibilityAdapter extends AutoCloseable {

    void hideEntityFromOtherPlayers(Entity entity, Player owner);

    void showEntityToOwner(Entity entity, Player owner);

    void sendEmptyItemFrameToOwner(ItemFrame itemFrame, Player owner);

    void resendClaimedFrameViews(Player player);

    void unregisterEntity(UUID entityUuid);

    @Override
    void close();
}
