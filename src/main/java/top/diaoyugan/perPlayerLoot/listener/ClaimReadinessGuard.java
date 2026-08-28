package top.diaoyugan.perPlayerLoot.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import top.diaoyugan.perPlayerLoot.message.Messages;
import top.diaoyugan.perPlayerLoot.storage.LootStorage;

/** Fails closed while a loaded Minecraft chunk's PPL claim view is not ready. */
public final class ClaimReadinessGuard implements Listener {
    private static final long MESSAGE_COOLDOWN_NANOS = 1_500_000_000L;

    private final LootStorage storage;
    private final Map<UUID, Notice> lastMessages = new HashMap<>();

    public ClaimReadinessGuard(final LootStorage storage) {
        this.storage = storage;
    }

    public boolean allow(final Player player, final Location location) {
        if (this.storage.isClaimChunkReady(location)) return true;
        showUnavailableMessage(player, this.storage.isClaimChunkFailed(location));
        return false;
    }

    public boolean allow(final Player player, final Chunk chunk) {
        if (this.storage.isClaimChunkReady(chunk)) return true;
        showUnavailableMessage(player, this.storage.isClaimChunkFailed(chunk));
        return false;
    }

    public boolean isReady(final Chunk chunk) {
        return this.storage.isClaimChunkReady(chunk);
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        this.lastMessages.remove(event.getPlayer().getUniqueId());
    }

    private void showUnavailableMessage(final Player player, final boolean failed) {
        long now = System.nanoTime();
        String message = failed ? Messages.CLAIM_CHUNK_FAILED : Messages.CLAIM_CHUNK_LOADING;
        Notice previous = this.lastMessages.get(player.getUniqueId());
        if (previous != null && previous.message().equals(message)
            && now - previous.timestamp() < MESSAGE_COOLDOWN_NANOS) return;
        this.lastMessages.put(player.getUniqueId(), new Notice(message, now));
        Messages.sendActionBar(player, message);
    }

    private record Notice(String message, long timestamp) { }
}
