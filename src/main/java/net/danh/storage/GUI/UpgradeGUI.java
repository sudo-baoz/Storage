package net.danh.storage.GUI;

import net.danh.storage.Manager.Island.IslandStorageManager;
import net.danh.storage.Manager.ItemManager;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Copper Golem (Shift + left click) menu: shows the capacity level and buys the next one.
 */
public class UpgradeGUI extends StorageToolGUI {

    public UpgradeGUI(Player player) {
        super(player, "upgrade");
    }

    @Override
    protected void build(Inventory inventory) {
        String key = MineManager.storageKey(player);
        int level = StorageSettings.getLevel(key);
        int maxLevel = StorageUpgrade.maxLevel();
        int next = level + 1;
        long capacity = MineManager.getMaxBlock(player);

        ConfigurationSection info = config.getConfigurationSection("info");
        if (info != null) {
            put(inventory, info.getInt("slot"), ItemManager.getItemConfigWithPlaceholders(player, info,
                    "#level#", String.valueOf(level), "#max_level#", String.valueOf(maxLevel),
                    "#capacity#", format(capacity), "#used#", format(MineManager.getTotalStored(player))), null);
        }

        ConfigurationSection upgrade = config.getConfigurationSection("upgrade");
        if (upgrade != null) {
            String status;
            if (!StorageUpgrade.isEnabled()) {
                status = LanguageManager.guiString(upgrade, "status_disabled");
            } else if (next > maxLevel) {
                status = LanguageManager.guiString(upgrade, "status_max");
            } else if (!IslandStorageManager.isOwnerOrSolo(player)) {
                status = LanguageManager.guiString(upgrade, "status_owner");
            } else {
                status = LanguageManager.guiString(upgrade, "status_available");
            }
            boolean hasNext = StorageUpgrade.isEnabled() && next <= maxLevel;
            put(inventory, upgrade.getInt("slot"), ItemManager.getItemConfigWithPlaceholders(player, upgrade,
                    "#next_level#", hasNext ? String.valueOf(next) : "-",
                    "#next_capacity#", hasNext ? format(Math.max(capacity, StorageUpgrade.capacity(next))) : "-",
                    "#capacity#", format(capacity),
                    "#cost#", hasNext ? format((long) StorageUpgrade.cost(next)) : "-",
                    "#status#", status), (p, click) -> buy());
        }
        addBack(inventory);
    }

    private void buy() {
        StorageUpgrade.Result result = StorageUpgrade.upgrade(player);
        String key = MineManager.storageKey(player);
        int level = StorageSettings.getLevel(key);
        String path = "storage_tools.upgrade." + result.name().toLowerCase(java.util.Locale.ROOT);
        String text = File.getMessage().getString(path, "#prefix# " + result);
        player.sendMessage(ChatUtils.colorize(text
                .replace("#level#", String.valueOf(level))
                .replace("#capacity#", format(MineManager.getMaxBlock(player)))
                .replace("#cost#", format((long) StorageUpgrade.cost(level + 1)))));
        reopen(new UpgradeGUI(player));
    }

    static String format(long value) {
        return net.danh.storage.Manager.LanguageManager.formatNumber(value);
    }
}
