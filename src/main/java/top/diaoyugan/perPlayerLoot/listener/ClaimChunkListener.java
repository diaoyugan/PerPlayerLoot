package top.diaoyugan.perPlayerLoot.listener;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

/** Aligns the bounded claim cache with the Bukkit chunk and entity lifecycle. */
public final class ClaimChunkListener implements Listener {
    private final LootStorage storage;

    public ClaimChunkListener(final LootStorage storage) {
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(final ChunkLoadEvent event) {
        this.storage.loadChunkClaims(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        // Entity UUIDs are required to locate and spatially backfill pre-migration frame claims.
        boolean containsFrame = false;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame) {
                containsFrame = true;
                break;
            }
        }
        if (!containsFrame) return;
        this.storage.loadChunkClaims(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(final ChunkUnloadEvent event) {
        this.storage.unloadChunkClaims(event.getChunk());
    }

    public void loadAlreadyLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) this.storage.loadChunkClaims(chunk);
        }
    }
}
