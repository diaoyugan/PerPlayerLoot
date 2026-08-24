package top.diaoyugan.perPlayerLoot.personal;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrushableBlock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;

public interface PersonalEntityVisibilityAdapter extends AutoCloseable {

    void hideEntityFromOtherPlayers(Entity entity, Player owner);

    void showEntityToOwner(Entity entity, Player owner);

    void sendEmptyItemFrameToOwner(ItemFrame itemFrame, Player owner);

    void registerFrameClaim(UUID frameUuid, UUID playerUuid);

    void resendClaimedFrameViews(Player player);

    boolean sendBrushablePreview(Player player, Location location, BrushableBlock brushable, BlockFace brushFace);

    void unregisterEntity(UUID entityUuid);

    @Override
    void close();
}

