package net.danh.storage.Manager;

import net.danh.storage.Storage;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.SchedulerUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Central i18n. Languages live in plugins/Storage/lang/&lt;code&gt;.yml (UTF-8) and hold both the chat messages
 * (root keys, the same keys the old message.yml used) and the GUI texts (gui.&lt;file&gt;.&lt;path&gt;.name|lore|title|status...).
 * <p>
 * Existing code keeps calling {@code File.getMessage()}; that now returns the config of the "current" language.
 * The current language is a thread-local set around commands, GUI clicks, chat input and block breaks (and carried
 * over to tasks scheduled through {@link SchedulerUtil}), so a message goes out in its receiver's language without
 * touching every call site. Anything outside such a context uses the server default.
 */
public final class LanguageManager {

    private static final Pattern CODE = Pattern.compile("[a-z]{2,3}_[A-Z]{2}");
    private static final String[] BUNDLED = {"en_US", "vi_VN"};
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    private static final Map<UUID, String> PLAYER_LANG = new ConcurrentHashMap<>();
    private static final Map<String, Layered> LANGS = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Map<ConfigurationSection, String> GUI_IDS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static volatile boolean loaded;

    private LanguageManager() {
    }

    // ---------- loading ----------

    public static synchronized void load() {
        Storage plugin = Storage.getStorage();
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "lang");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create the lang folder.");
        }
        migrateLegacyMessages(plugin, dir);
        for (String code : BUNDLED) {
            mergeBundled(plugin, new java.io.File(dir, code + ".yml"), "lang/" + code + ".yml");
        }

        Map<String, YamlConfiguration> raw = new LinkedHashMap<>();
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files);
            for (java.io.File file : files) {
                String code = file.getName().substring(0, file.getName().length() - 4);
                if (!CODE.matcher(code).matches()) {
                    continue;
                }
                try {
                    // loadConfiguration(File) reads UTF-8; a broken YAML is logged and skipped, never fatal.
                    YamlConfiguration yaml = new YamlConfiguration();
                    yaml.load(file);
                    raw.put(code, yaml);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Invalid language file " + file.getName() + ": " + e.getMessage());
                }
            }
        }
        YamlConfiguration fallback = raw.get(fallbackCode());
        Map<String, Layered> built = new LinkedHashMap<>();
        for (Map.Entry<String, YamlConfiguration> entry : raw.entrySet()) {
            built.put(entry.getKey(), new Layered(entry.getKey(), entry.getValue(),
                    entry.getKey().equals(fallbackCode()) ? null : fallback));
        }
        LANGS.clear();
        LANGS.putAll(built);
        GUI_IDS.clear();
        MISSING_LOGGED.clear();
        loadPlayers();
        loaded = true;
        plugin.getLogger().info("Loaded languages: " + String.join(", ", available()) + " (default " + defaultCode() + ")");
    }

    public static void reload() {
        load();
    }

    // One-time: the admin's old message.yml becomes the base of lang/en_US.yml so their wording is not lost.
    private static void migrateLegacyMessages(Storage plugin, java.io.File dir) {
        java.io.File target = new java.io.File(dir, "en_US.yml");
        java.io.File legacy = new java.io.File(plugin.getDataFolder(), "message.yml");
        if (target.exists() || !legacy.isFile()) {
            return;
        }
        try {
            YamlConfiguration base = loadResource(plugin, "lang/en_US.yml");
            YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
            for (String key : old.getKeys(true)) {
                if (!old.isConfigurationSection(key) && !key.equals("message_version") && base.contains(key)) {
                    base.set(key, old.get(key));
                }
            }
            base.save(target);
            plugin.getLogger().info("Migrated message.yml into lang/en_US.yml (message.yml itself is left untouched).");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not migrate message.yml: " + e.getMessage());
        }
    }

    // Create the bundled file, or add keys a newer version introduced; admin edits are never overwritten.
    private static void mergeBundled(Storage plugin, java.io.File file, String resource) {
        try {
            if (!file.exists()) {
                plugin.saveResource(resource, false);
                return;
            }
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
            YamlConfiguration bundled = loadResource(plugin, resource);
            boolean changed = false;
            for (String key : bundled.getKeys(true)) {
                if (!bundled.isConfigurationSection(key) && !current.contains(key)) {
                    current.set(key, bundled.get(key));
                    changed = true;
                }
            }
            if (changed) {
                current.save(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not update " + file.getName() + ": " + e.getMessage());
        }
    }

    private static YamlConfiguration loadResource(Storage plugin, String resource) {
        java.io.InputStream in = plugin.getResource(resource);
        if (in == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    // ---------- settings ----------

    public static @NotNull String defaultCode() {
        return normalize(File.getConfig().getString("language.default", "en_US"), "en_US");
    }

    public static @NotNull String fallbackCode() {
        return normalize(File.getConfig().getString("language.fallback", "en_US"), "en_US");
    }

    public static boolean isPerPlayer() {
        return File.getConfig().getBoolean("language.per-player", true);
    }

    public static boolean useClientLocale() {
        return File.getConfig().getBoolean("language.use-client-locale", false);
    }

    public static @NotNull List<String> available() {
        List<String> configured = new ArrayList<>();
        for (String code : File.getConfig().getStringList("language.available")) {
            String normalized = normalize(code, null);
            if (normalized != null && LANGS.containsKey(normalized)) {
                configured.add(normalized);
            }
        }
        return configured.isEmpty() ? new ArrayList<>(LANGS.keySet()) : configured;
    }

    /**
     * "vi_vn", "vi-VN" and "VI_VN" become "vi_VN"; a bare "vi" matches the first language that starts with it.
     */
    public static String normalize(@Nullable String raw, @Nullable String def) {
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        String[] parts = raw.trim().replace('-', '_').split("_");
        if (parts.length >= 2) {
            return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
        }
        String lang = parts[0].toLowerCase(Locale.ROOT);
        for (String code : LANGS.keySet()) {
            if (code.startsWith(lang + "_")) {
                return code;
            }
        }
        return def;
    }

    // ---------- player language ----------

    /**
     * Manual choice, then client locale (when enabled), then the server default, then the fallback.
     */
    public static @NotNull String resolve(@Nullable Player player) {
        if (player != null && isPerPlayer()) {
            String chosen = PLAYER_LANG.get(player.getUniqueId());
            if (chosen != null && LANGS.containsKey(chosen)) {
                return chosen;
            }
            if (useClientLocale()) {
                String client = normalize(clientLocale(player), null);
                if (client != null && available().contains(client)) {
                    return client;
                }
            }
        }
        String def = defaultCode();
        return LANGS.containsKey(def) ? def : fallbackCode();
    }

    @SuppressWarnings("deprecation")
    private static String clientLocale(Player player) {
        try {
            return player.getLocale();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static boolean setPlayerLanguage(@NotNull Player player, @NotNull String code) {
        String normalized = normalize(code, null);
        if (normalized == null || !available().contains(normalized)) {
            return false;
        }
        PLAYER_LANG.put(player.getUniqueId(), normalized);
        savePlayersAsync();
        return true;
    }

    private static java.io.File playersFile() {
        return new java.io.File(Storage.getStorage().getDataFolder(), "player-languages.yml");
    }

    private static void loadPlayers() {
        PLAYER_LANG.clear();
        java.io.File file = playersFile();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                String code = normalize(players.getString(key + ".language"), null);
                if (code != null) {
                    PLAYER_LANG.put(UUID.fromString(key), code);
                }
            } catch (IllegalArgumentException ignored) {
                // Hand-edited entry that is not a UUID.
            }
        }
    }

    // Written only when somebody changes language (never when a GUI opens); the snapshot is taken on the caller's
    // thread and written off-thread, replacing the file in one move so a crash cannot leave it half written.
    private static void savePlayersAsync() {
        YamlConfiguration yaml = new YamlConfiguration();
        PLAYER_LANG.forEach((uuid, code) -> yaml.set("players." + uuid + ".language", code));
        String snapshot = yaml.saveToString();
        Runnable write = () -> {
            java.io.File file = playersFile();
            java.io.File temp = new java.io.File(file.getParentFile(), file.getName() + ".tmp");
            try {
                java.nio.file.Files.writeString(temp.toPath(), snapshot, StandardCharsets.UTF_8);
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Storage.getStorage().getLogger().warning("Could not save player-languages.yml: " + e.getMessage());
            }
        };
        if (Storage.getStorage().isEnabled()) {
            SchedulerUtil.runTaskAsynchronously(Storage.getStorage(), write);
        } else {
            write.run();
        }
    }

    // ---------- current language (thread-local) ----------

    public static void setContext(@Nullable CommandSender sender) {
        if (sender instanceof Player player) {
            CONTEXT.set(resolve(player));
        } else {
            CONTEXT.remove();
        }
    }

    public static void clearContext() {
        CONTEXT.remove();
    }

    /**
     * Wraps a task so it runs in the language that was current when it was scheduled.
     */
    public static Runnable carryContext(Runnable task) {
        String code = CONTEXT.get();
        if (code == null || task == null) {
            return task;
        }
        return () -> {
            String previous = CONTEXT.get();
            CONTEXT.set(code);
            try {
                task.run();
            } finally {
                if (previous == null) {
                    CONTEXT.remove();
                } else {
                    CONTEXT.set(previous);
                }
            }
        };
    }

    public static @NotNull String currentCode() {
        String code = CONTEXT.get();
        if (code != null && LANGS.containsKey(code)) {
            return code;
        }
        String def = defaultCode();
        return LANGS.containsKey(def) ? def : fallbackCode();
    }

    // ---------- lookups ----------

    /**
     * Number with the thousands separator of the current language (vi: 2.450, en: 2,450).
     */
    public static @NotNull String formatNumber(long value) {
        String[] parts = currentCode().split("_");
        Locale locale = parts.length == 2 ? new Locale(parts[0], parts[1]) : Locale.US;
        return java.text.NumberFormat.getIntegerInstance(locale).format(value);
    }

    /**
     * The messages of the current language, or the legacy message.yml before the manager is loaded.
     */
    public static @NotNull FileConfiguration messages() {
        if (!loaded) {
            return File.getLegacyMessage();
        }
        Layered layered = LANGS.get(currentCode());
        if (layered == null) {
            layered = LANGS.get(fallbackCode());
        }
        return layered != null ? layered : File.getLegacyMessage();
    }

    public static @NotNull FileConfiguration messages(@Nullable Player player) {
        if (!loaded) {
            return File.getLegacyMessage();
        }
        Layered layered = LANGS.get(resolve(player));
        return layered != null ? layered : messages();
    }

    /**
     * GUI text (name, lore, title, status...) of a GUI config section in the current language; the GUI yml itself
     * is the last fallback, so an untranslated key still shows its original text.
     */
    public static @Nullable String guiString(@NotNull ConfigurationSection section, @NotNull String field) {
        String key = guiKey(section, field);
        if (key != null && loaded) {
            Layered layered = LANGS.get(currentCode());
            if (layered != null) {
                Object value = layered.getQuiet(key);
                if (value instanceof String text) {
                    return text;
                }
            }
        }
        return section.getString(field);
    }

    public static @NotNull List<String> guiList(@NotNull ConfigurationSection section, @NotNull String field) {
        String key = guiKey(section, field);
        if (key != null && loaded) {
            Layered layered = LANGS.get(currentCode());
            if (layered != null) {
                Object value = layered.getQuiet(key);
                if (value instanceof List<?> list) {
                    List<String> result = new ArrayList<>();
                    for (Object line : list) {
                        result.add(String.valueOf(line));
                    }
                    return result;
                }
            }
        }
        return section.getStringList(field);
    }

    private static String guiKey(ConfigurationSection section, String field) {
        ConfigurationSection root = section.getRoot();
        if (root == null) {
            return null;
        }
        String id = GUI_IDS.get(root);
        if (id == null) {
            id = findGuiId(root);
            if (id == null) {
                return null;
            }
            GUI_IDS.put(root, id);
        }
        String path = section.getCurrentPath();
        return "gui." + id + (path == null || path.isEmpty() ? "" : "." + path) + "." + field;
    }

    private static String findGuiId(ConfigurationSection root) {
        for (String file : File.guiFileNames()) {
            if (File.getFileSetting().get("GUI/" + file + ".yml") == root) {
                return file;
            }
        }
        return null;
    }

    // ---------- config layered over a fallback ----------

    /**
     * One language file; a missing key falls back to the fallback language and is logged once.
     */
    static final class Layered extends YamlConfiguration {
        private final String code;
        private final YamlConfiguration primary;
        private final YamlConfiguration fallback;

        Layered(String code, YamlConfiguration primary, YamlConfiguration fallback) {
            this.code = code;
            this.primary = primary;
            this.fallback = fallback;
        }

        Object getQuiet(String path) {
            return primary.get(path);
        }

        @Override
        public Object get(@NotNull String path, Object def) {
            Object value = primary.get(path);
            if (value != null) {
                return value;
            }
            if (fallback != null) {
                value = fallback.get(path);
                if (value != null) {
                    warnMissing(path);
                    return value;
                }
            }
            return def;
        }

        @Override
        public Object get(@NotNull String path) {
            return get(path, null);
        }

        @Override
        public boolean contains(@NotNull String path) {
            return get(path, null) != null;
        }

        @Override
        public boolean contains(@NotNull String path, boolean ignoreDefault) {
            return get(path, null) != null;
        }

        @Override
        public boolean isSet(@NotNull String path) {
            return get(path, null) != null;
        }

        @Override
        public ConfigurationSection getConfigurationSection(@NotNull String path) {
            Object value = get(path, null);
            return value instanceof ConfigurationSection section ? section : null;
        }

        private void warnMissing(String path) {
            if (MISSING_LOGGED.add(code + ":" + path) && MISSING_LOGGED.size() <= 50 && Storage.getStorage() != null) {
                Storage.getStorage().getLogger().warning("Language " + code + " is missing '" + path
                        + "', using " + fallbackCode());
            }
        }
    }
}
