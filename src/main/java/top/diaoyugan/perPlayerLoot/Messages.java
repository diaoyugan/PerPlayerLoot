package top.diaoyugan.perPlayerLoot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class Messages {

    static final String FRAME_ALREADY_CLAIMED = "message.perplayerloot.frame_already_claimed";
    static final String FRAME_ALREADY_CLAIMED_CANNOT_PLACE = "message.perplayerloot.frame_already_claimed_cannot_place";
    static final String PERSONAL_DROPS_DISABLED = "message.perplayerloot.personal_drops_disabled";
    static final String NO_CONTAINER_DESTROY_PERMISSION = "message.perplayerloot.no_container_destroy_permission";
    static final String NO_FRAME_DESTROY_PERMISSION = "message.perplayerloot.no_frame_destroy_permission";
    static final String RELOAD_SUCCESS = "message.perplayerloot.reload_success";
    static final String RELOAD_USAGE = "message.perplayerloot.reload_usage";

    private static final Type LANG_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();
    private static final Map<String, Map<String, String>> LANGUAGES = new HashMap<>();

    private Messages() {
    }

    static void load(final PerPlayerLoot plugin) {
        LANGUAGES.clear();
        File languageFolder = new File(plugin.getDataFolder(), "lang");
        if (!languageFolder.exists() && !languageFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create language folder.");
        }
        loadBundledLanguage(plugin, "en_us");
        loadBundledLanguage(plugin, "zh_cn");
        releaseDefaultLanguage(plugin, languageFolder, "en_us");
        releaseDefaultLanguage(plugin, languageFolder, "zh_cn");

        File[] files = languageFolder.listFiles((folder, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            loadLanguage(plugin, file);
        }
    }

    static void send(final Player player, final String key) {
        player.sendMessage(Component.text(resolve(player, key)));
    }

    static void send(final CommandSender sender, final String key) {
        if (sender instanceof Player player) {
            send(player, key);
        } else {
            sender.sendMessage(resolve("en_us", key));
        }
    }

    private static String resolve(final Player player, final String key) {
        String locale = normalizeLocale(player.locale());
        Map<String, String> selectedLanguage = LANGUAGES.get(locale);
        if (selectedLanguage != null && selectedLanguage.containsKey(key)) {
            return selectedLanguage.get(key);
        }

        Map<String, String> english = LANGUAGES.get("en_us");
        if (english != null && english.containsKey(key)) {
            return english.get(key);
        }
        return key;
    }

    private static String resolve(final String locale, final String key) {
        Map<String, String> selectedLanguage = LANGUAGES.get(locale);
        if (selectedLanguage != null && selectedLanguage.containsKey(key)) {
            return selectedLanguage.get(key);
        }

        Map<String, String> english = LANGUAGES.get("en_us");
        if (english != null && english.containsKey(key)) {
            return english.get(key);
        }
        return key;
    }

    private static void loadLanguage(final PerPlayerLoot plugin, final File file) {
        String locale = file.getName().substring(0, file.getName().length() - ".json".length()).toLowerCase(Locale.ROOT);
        try (InputStream inputStream = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            Map<String, String> entries = new Gson().fromJson(reader, LANG_TYPE);
            LANGUAGES.computeIfAbsent(locale, ignored -> new HashMap<>()).putAll(entries == null ? Map.of() : entries);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not load language file: " + file.getName());
        }
    }

    private static void loadBundledLanguage(final PerPlayerLoot plugin, final String locale) {
        String resourcePath = "assets/perplayerloot/lang/" + locale + ".json";
        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                plugin.getLogger().warning("Missing bundled language file: " + resourcePath);
                return;
            }
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                Map<String, String> entries = new Gson().fromJson(reader, LANG_TYPE);
                LANGUAGES.put(locale, entries == null ? new HashMap<>() : new HashMap<>(entries));
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not load bundled language file: " + locale + ".json");
        }
    }

    private static String normalizeLocale(final Locale locale) {
        return locale.toLanguageTag().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void releaseDefaultLanguage(
        final PerPlayerLoot plugin,
        final File languageFolder,
        final String locale
    ) {
        File targetFile = new File(languageFolder, locale + ".json");
        if (targetFile.exists()) {
            return;
        }

        String resourcePath = "assets/perplayerloot/lang/" + locale + ".json";
        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                plugin.getLogger().warning("Missing bundled language file: " + resourcePath);
                return;
            }
            try (OutputStream outputStream = new FileOutputStream(targetFile)) {
                inputStream.transferTo(outputStream);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not release language file: " + targetFile.getName());
        }
    }
}
