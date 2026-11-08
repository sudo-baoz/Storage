package net.danh.storage.Manager;

import net.danh.storage.Manager.Island.IslandStorageManager;
import net.danh.storage.Storage;
import net.danh.storage.Utils.File;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Capacity upgrade levels from config.yml (storage_upgrade.levels.N.{capacity,cost,permission}).
 * The level belongs to the storage (island owner's key for islands), never to the clicking player.
 */
public final class StorageUpgrade {

    private StorageUpgrade() {
    }

    public static boolean isEnabled() {
        return File.getConfig().getBoolean("storage_upgrade.enabled", true);
    }

    public static int maxLevel() {
        ConfigurationSection levels = File.getConfig().getConfigurationSection("storage_upgrade.levels");
        int max = 0;
        if (levels != null) {
            for (String key : levels.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key);
                    if (levels.getInt(key + ".capacity", 0) > 0) {
                        max = Math.max(max, level);
                    }
                } catch (NumberFormatException ignored) {
                    // Not a level number.
                }
            }
        }
        return max;
    }

    /**
     * Capacity granted by a level; 0 for level 0 or an undefined level (base capacity then applies).
     */
    public static int capacity(int level) {
        if (level <= 0 || !isEnabled()) {
            return 0;
        }
        return Math.max(0, File.getConfig().getInt("storage_upgrade.levels." + level + ".capacity", 0));
    }

    public static double cost(int level) {
        return Math.max(0, File.getConfig().getDouble("storage_upgrade.levels." + level + ".cost", 0));
    }

    public static @NotNull String permission(int level) {
        return File.getConfig().getString("storage_upgrade.levels." + level + ".permission", "");
    }

    public static @NotNull Result upgrade(@NotNull Player player) {
        if (!isEnabled()) {
            return Result.DISABLED;
        }
        if (!IslandStorageManager.isOwnerOrSolo(player)) {
            return Result.NOT_OWNER;
        }
        String key = MineManager.storageKey(player);
        int next = StorageSettings.getLevel(key) + 1;
        if (next > maxLevel() || capacity(next) <= 0) {
            return Result.MAX_LEVEL;
        }
        String permission = permission(next);
        if (!permission.isEmpty() && !player.hasPermission(permission)) {
            return Result.NO_PERMISSION;
        }
        double cost = cost(next);
        if (cost > 0 && !Storage.isVaultHooked()) {
            return Result.NO_ECONOMY;
        }
        // Charge first; the level is only raised after the money has really been taken.
        if (!Storage.withdrawFromVault(player, cost)) {
            return Result.NO_MONEY;
        }
        StorageSettings.setLevel(key, next);
        MineManager.savePlayerData(player);
        return Result.SUCCESS;
    }

    public enum Result {SUCCESS, DISABLED, MAX_LEVEL, NO_PERMISSION, NO_MONEY, NOT_OWNER, NO_ECONOMY}
}
