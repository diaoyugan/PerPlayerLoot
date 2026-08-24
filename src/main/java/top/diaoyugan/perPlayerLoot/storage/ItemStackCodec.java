package top.diaoyugan.perPlayerLoot.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

/** Current Bukkit item codec with compatibility for pre-SQLite object-stream blobs. */
final class ItemStackCodec {
    private ItemStackCodec() { }
    static byte[] serializeItems(final ItemStack[] items) { return ItemStack.serializeItemsAsBytes(items); }
    static byte[] serializeItem(final ItemStack item) { return item.serializeAsBytes(); }

    static ItemStack[] deserializeItems(final byte[] bytes) {
        try { return ItemStack.deserializeItemsFromBytes(bytes); }
        catch (RuntimeException exception) { return legacyDeserialize(bytes, ItemStack[].class); }
    }

    static ItemStack deserializeItem(final byte[] bytes) {
        try { return ItemStack.deserializeBytes(bytes); }
        catch (RuntimeException exception) { return legacyDeserialize(bytes, ItemStack.class); }
    }

    @SuppressWarnings("deprecation")
    private static <T> T legacyDeserialize(final byte[] bytes, final Class<T> type) {
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream objects = new BukkitObjectInputStream(bytesIn)) {
            return type.cast(objects.readObject());
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Could not deserialize loot data.", exception);
        }
    }
}
