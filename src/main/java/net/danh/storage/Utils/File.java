package net.danh.storage.Utils;

import com.tchristofferson.configupdater.ConfigUpdater;
import net.danh.storage.Manager.ConvertOreManager;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.SpecialMaterial.SpecialMaterialManager;
import net.danh.storage.Storage;
import net.xconfig.bukkit.model.SimpleConfigurationManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

public class File {

    private static void mergeMissingKeys(
            FileConfiguration currentConfig,
            FileConfiguration defaultConfig) {
        if (currentConfig == null || defaultConfig == null) {
            return;
        }

        Set<String> keys = defaultConfig.getKeys(true);
        for (String key : keys) {
            if (currentConfig.contains(key)) {
                continue;
            }

            Object value = defaultConfig.get(key);
            if (value != null) {
                currentConfig.set(key, value);
            }
        }
    }

    private static void updateVersionedConfig(
            String fileName,
            String versionKey,
            String logName,
            String... ignoredSections) {
        java.io.File configFile = new java.io.File(
                Storage.getStorage().getDataFolder(),
                fileName);
        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        Objects.requireNonNull(
                                Storage.getStorage().getResource(fileName)),
                        StandardCharsets.UTF_8));
        FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(
                configFile);

        int defaultVersion = defaultConfig.getInt(versionKey);
        int currentVersion = currentConfig.contains(versionKey)
                ? currentConfig.getInt(versionKey)
                : 0;

        if (defaultVersion <= currentVersion) {
            return;
        }

        Storage.getStorage().getLogger().log(
                Level.WARNING,
                "Your " + logName + " is updating from v" + currentVersion
                        + " to v" + defaultVersion + "...");

        mergeMissingKeys(currentConfig, defaultConfig);

        try {
            currentConfig.set(versionKey, defaultVersion);
            currentConfig.save(configFile);

            if (ignoredSections != null && ignoredSections.length > 0) {
                ConfigUpdater.update(
                        Storage.getStorage(),
                        fileName,
                        configFile,
                        ignoredSections);
            } else {
                ConfigUpdater.update(Storage.getStorage(), fileName, configFile);
            }

            FileConfiguration updatedConfig = YamlConfiguration.loadConfiguration(
                    configFile);
            if (updatedConfig.getInt(versionKey) != defaultVersion) {
                updatedConfig.set(versionKey, defaultVersion);
                updatedConfig.save(configFile);
            }

            Storage.getStorage().getLogger().log(
                    Level.WARNING,
                    "Your " + logName
                            + " have been updated successful to v"
                            + defaultVersion);
        } catch (IOException e) {
            Storage.getStorage().getLogger().log(
                    Level.SEVERE,
                    "Failed to update " + logName + " file",
                    e);
        }

        getFileSetting().reload(fileName);
    }

    public static int resolveMaxStorage(
            FileConfiguration config,
            String modePath,
            int databaseValue,
            int permissionValue) {
        if (config == null) {
            return permissionValue;
        }

        String mode = config.getString(modePath, "PERMISSION");
        if (mode == null) {
            mode = "PERMISSION";
        }
        mode = mode.trim().toUpperCase(Locale.ROOT);

        if ("DATABASE".equals(mode)) {
            return databaseValue;
        }
        if ("MAX".equals(mode)) {
            return Math.max(databaseValue, permissionValue);
        }
        if ("MIN".equals(mode)) {
            return Math.min(databaseValue, permissionValue);
        }

        return permissionValue;
    }

    public static SimpleConfigurationManager getFileSetting() {
        return SimpleConfigurationManager.get();
    }

    public static FileConfiguration getConfig() {
        return getFileSetting().get("config.yml");
    }

    /**
     * Messages of the current language (see {@link LanguageManager}).
     */
    public static FileConfiguration getMessage() {
        return LanguageManager.messages();
    }

    private static volatile FileConfiguration bundledEnglish;

    /**
     * Bundled English texts: used before the language files are loaded (early startup, unit tests).
     */
    public static FileConfiguration getLegacyMessage() {
        FileConfiguration cached = bundledEnglish;
        if (cached == null) {
            java.io.InputStream in = File.class.getClassLoader().getResourceAsStream("lang/en_US.yml");
            cached = in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            bundledEnglish = cached;
        }
        return cached;
    }

    public static java.util.List<String> guiFileNames() {
        return java.util.List.of("storage", "items", "transfer", "transfer-multi", "convert-ore", "view-storage",
                "recipe-list", "recipe-editor", "recipe-editor-list", "material-selection", "material-editor",
                "confirmation", "storage-tools");
    }

    public static FileConfiguration getGUIStorage() {
        return getFileSetting().get("GUI/storage.yml");
    }

    public static FileConfiguration getItemStorage() {
        return getFileSetting().get("GUI/items.yml");
    }

    public static FileConfiguration getConvertOreConfig() {
        return getFileSetting().get("GUI/convert-ore.yml");
    }

    public static FileConfiguration getViewStorageConfig() {
        return getFileSetting().get("GUI/view-storage.yml");
    }

    public static FileConfiguration getEventConfig() {
        return getFileSetting().get("events.yml");
    }

    public static FileConfiguration getEnchantsConfig() {
        return getFileSetting().get("enchants.yml");
    }

    public static FileConfiguration getSpecialMaterialConfig() {
        return getFileSetting().get("special_material.yml");
    }

    public static FileConfiguration getUpgradesConfig() {
        return getFileSetting().get("upgrades.yml");
    }

    public static FileConfiguration getFriendStorageConfig() {
        return getFileSetting().get("friendstorage.yml");
    }


    public static FileConfiguration getCraftingConfig() {
        return getFileSetting().get("crafting.yml");
    }

    public static void saveCraftingConfig() {
        getFileSetting().save("crafting.yml");
    }

    public static FileConfiguration getRecipeListGUIConfig() {
        return getFileSetting().get("GUI/recipe-list.yml");
    }

    public static FileConfiguration getRecipeEditorGUIConfig() {
        return getFileSetting().get("GUI/recipe-editor.yml");
    }

    public static FileConfiguration getRecipeEditorListGUIConfig() {
        return getFileSetting().get("GUI/recipe-editor-list.yml");
    }

    public static FileConfiguration getMaterialSelectionGUIConfig() {
        return getFileSetting().get("GUI/material-selection.yml");
    }

    public static FileConfiguration getMaterialEditorGUIConfig() {
        return getFileSetting().get("GUI/material-editor.yml");
    }

    public static FileConfiguration getStorageToolsConfig() {
        return getFileSetting().get("GUI/storage-tools.yml");
    }

    public static FileConfiguration getConfirmationGUIConfig() {
        return getFileSetting().get("GUI/confirmation.yml");
    }

    /**
     * First run with upgrades.yml: carry the admin's old storage_upgrade levels from config.yml over, so their
     * prices and capacities are not lost (old upgrades only had Vault prices).
     */
    private static void migrateLegacyUpgrades() {
        java.io.File dataFolder = Storage.getStorage().getDataFolder();
        java.io.File target = new java.io.File(dataFolder, "upgrades.yml");
        java.io.File legacy = new java.io.File(dataFolder, "config.yml");
        if (target.exists() || !legacy.isFile()) {
            return;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
        org.bukkit.configuration.ConfigurationSection levels = old.getConfigurationSection("storage_upgrade.levels");
        if (levels == null || levels.getKeys(false).isEmpty()) {
            return;
        }
        try {
            Storage.getStorage().saveResource("upgrades.yml", false);
            YamlConfiguration upgrades = YamlConfiguration.loadConfiguration(target);
            int migrated = 0;
            for (String key : levels.getKeys(false)) {
                int level;
                try {
                    level = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (level < 1 || level > 6) {
                    continue;
                }
                String path = "levels." + level + ".";
                upgrades.set(path + "capacity", levels.getInt(key + ".capacity"));
                // The bundled file already has "requirements"; replace all of it so only the old Vault cost applies.
                upgrades.set(path + "requirements", null);
                upgrades.set(path + "requirements.vault", levels.getDouble(key + ".cost"));
                upgrades.set(path + "requirements.playerpoints", 0);
                upgrades.set(path + "requirements.exp.type", "LEVELS");
                upgrades.set(path + "requirements.exp.amount", 0);
                upgrades.set(path + "requirements.items", new java.util.ArrayList<String>());
                upgrades.set(path + "permission", levels.getString(key + ".permission", ""));
                migrated = Math.max(migrated, level);
            }
            if (migrated > 0) {
                upgrades.set("settings.enabled", old.getBoolean("storage_upgrade.enabled", true));
                upgrades.save(target);
                Storage.getStorage().getLogger().info("Migrated storage_upgrade from config.yml into upgrades.yml ("
                        + migrated + " levels).");
            }
        } catch (IOException e) {
            Storage.getStorage().getLogger().warning("Could not migrate storage_upgrade: " + e.getMessage());
        }
    }

    public static void updateUpgradesConfig() {
        updateVersionedConfig("upgrades.yml", "upgrades_version", "upgrades config");
        StorageUpgrade.reload();
    }

    public static void loadFiles() {
        migrateLegacyUpgrades();
        getFileSetting().build("", false, "config.yml", "events.yml", "enchants.yml",
                "special_material.yml", "crafting.yml",
                "friendstorage.yml", "upgrades.yml");
        copyExampleFiles();
        AutoPickupCache.reload();
    }

    public static void reloadFiles() {
        getFileSetting().reload("config.yml", "events.yml", "enchants.yml", "special_material.yml",
                "crafting.yml", "friendstorage.yml", "upgrades.yml", "GUI/storage.yml", "GUI/items.yml",
                "GUI/transfer.yml", "GUI/transfer-multi.yml", "GUI/convert-ore.yml", "GUI/view-storage.yml",
                "GUI/recipe-list.yml", "GUI/recipe-editor.yml", "GUI/recipe-editor-list.yml",
                "GUI/material-selection.yml", "GUI/material-editor.yml", "GUI/confirmation.yml", "GUI/storage-tools.yml");
        for (Player p : Bukkit.getOnlinePlayers()) {
            MineManager.savePlayerData(p);
            MineManager.loadPlayerData(p);
        }
        LanguageManager.reload();
        StorageUpgrade.reload();
        ConvertOreManager.loadConvertOptions();
        SpecialMaterialManager.loadSpecialMaterials();
        AutoPickupCache.reload();
    }

    /**
     * The redesigned main GUI adds new buttons. An older GUI/storage.yml (no pickup_filter button, or still a groundstore button) is moved aside
     * as storage.yml.old and replaced by the new default; customised files are never lost.
     */
    private static void upgradeStorageGui() {
        java.io.File gui = new java.io.File(Storage.getStorage().getDataFolder(), "GUI/storage.yml");
        YamlConfiguration current = gui.isFile() ? YamlConfiguration.loadConfiguration(gui) : null;
        if (current == null || (current.contains("items.pickup_filter") && !current.contains("items.groundstore_item"))) {
            return;
        }
        try {
            java.nio.file.Files.move(gui.toPath(), new java.io.File(gui.getParentFile(), "storage.yml.old").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Storage.getStorage().saveResource("GUI/storage.yml", false);
            Storage.getStorage().getLogger().warning("GUI/storage.yml upgraded to the new layout; the old file was kept as GUI/storage.yml.old");
        } catch (java.io.IOException e) {
            Storage.getStorage().getLogger().warning("Could not upgrade GUI/storage.yml: " + e.getMessage());
        }
    }

    public static void loadGUI() {
        upgradeStorageGui();
        getFileSetting().build("", false, "GUI/storage.yml", "GUI/items.yml", "GUI/transfer.yml",
                "GUI/transfer-multi.yml", "GUI/convert-ore.yml", "GUI/view-storage.yml", "GUI/recipe-list.yml",
                "GUI/recipe-editor.yml", "GUI/recipe-editor-list.yml", "GUI/material-selection.yml",
                "GUI/material-editor.yml", "GUI/confirmation.yml", "GUI/storage-tools.yml");
    }

    public static void updateConfig() {
        updateVersionedConfig(
                "config.yml",
                "config_version",
                "config",
                "items",
                "blocks",
                "worth");
    }

    public static void updateEventConfig() {
        updateVersionedConfig("events.yml", "config_version", "events config");
    }

    public static void updateEnchantConfig() {
        updateVersionedConfig("enchants.yml", "enchant_version", "enchants config");
    }

    public static void updateSpecialMaterialConfig() {
        updateVersionedConfig(
                "special_material.yml",
                "special_materials_version",
                "special materials config");
        SpecialMaterialManager.loadSpecialMaterials();
    }

    public static void updateFriendStorageConfig() {
        updateVersionedConfig(
                "friendstorage.yml",
                "friendstorage_version",
                "friendstorage config");
    }

    public static void updateCraftingConfig() {
        updateVersionedConfig(
                "crafting.yml",
                "crafting_version",
                "crafting config");
    }

    public static void saveEnchantConfig() {
        try {
            java.io.File configFile = new java.io.File(Storage.getStorage().getDataFolder(), "enchants.yml");
            getEnchantsConfig().save(configFile);
        } catch (IOException e) {
            Storage.getStorage().getLogger().log(Level.SEVERE, "Failed to save enchants.yml config", e);
        }
    }

    private static void copyExampleFiles() {
        java.io.File exampleFile = new java.io.File(Storage.getStorage().getDataFolder(), "particles-examples.yml");

        // Only copy if file doesn't exist to avoid overwriting user modifications
        if (!exampleFile.exists()) {
            try {
                Storage.getStorage().saveResource("particles-examples.yml", false);
                Storage.getStorage().getLogger().log(Level.INFO,
                        "Created particles-examples.yml - Check this file for Advanced Geometric Patterns examples!");
            } catch (Exception e) {
                Storage.getStorage().getLogger().log(Level.WARNING,
                        "Could not create particles-examples.yml: " + e.getMessage());
            }
        }
    }
}
