package net.danh.storage.Utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.danh.storage.Storage.getStorage;

public final class AutoPickupCache {

    // Storage (BlockBreak)
    private static volatile boolean storageActionBarEnabled = false;
    private static volatile boolean storageTitleEnabled = false;
    private static volatile boolean preventRebreak = false;
    private static volatile Set<String> storageBlacklist = Collections.emptySet();
    private static volatile Set<String> storageFortuneWhitelist = Collections.emptySet();








    // MMOItems autosmelt hook
    private static volatile boolean mmoitemsAutoSmeltEnabled = true;

    // Keep internal; all construction prevented
    private AutoPickupCache() {
    }

    public static void reload() {
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = File.getConfig();

            //Storage
            preventRebreak = cfg.getBoolean("prevent_rebreak", false);
            storageActionBarEnabled = cfg.getBoolean("mine.actionbar.enable", false);
            storageTitleEnabled = cfg.getBoolean("mine.title.enable", false);
            storageBlacklist = immutableSet(cfg.getStringList("blacklist_world"));
            storageFortuneWhitelist = immutableSet(cfg.getStringList("whitelist_fortune"));
            mmoitemsAutoSmeltEnabled = cfg.getBoolean("hooks.mmoitems_autosmelt.enabled", true);


            NotificationQueue.reload();

        } catch (Exception ex) {
            getStorage().getLogger()
                    .warning("[AutoPickupCache] Failed to reload cache: " + ex.getMessage());
        }
    }

    @NotNull
    private static Set<String> immutableSet(@Nullable List<String> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(list));
    }


    // Public getters


    public static boolean isPreventRebreak() {
        return preventRebreak;
    }

    public static boolean isStorageActionBarEnabled() {
        return storageActionBarEnabled;
    }

    public static boolean isStorageTitleEnabled() {
        return storageTitleEnabled;
    }

    public static boolean isMmoitemsAutoSmeltEnabled() {
        return mmoitemsAutoSmeltEnabled;
    }

    public static boolean isStorageWorldBlacklisted(@NotNull String worldName) {
        return !storageBlacklist.isEmpty() && storageBlacklist.contains(worldName);
    }

    public static boolean isFortuneWhitelisted(@NotNull String materialName) {
        return storageFortuneWhitelist.contains(materialName);
    }
}
