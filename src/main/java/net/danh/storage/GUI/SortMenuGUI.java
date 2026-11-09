package net.danh.storage.GUI;

import net.danh.storage.Manager.ItemManager;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageSettings.SortMode;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Book menu: pick how the main storage lists its items. The choice is saved with the storage data.
 */
public class SortMenuGUI extends StorageToolGUI {

    public SortMenuGUI(Player player) {
        super(player, "sort");
    }

    @Override
    protected void build(Inventory inventory) {
        String key = MineManager.storageKey(player);
        SortMode current = StorageSettings.getSortMode(key);
        ConfigurationSection modes = config.getConfigurationSection("modes");
        if (modes != null) {
            for (SortMode mode : SortMode.values()) {
                ConfigurationSection section = modes.getConfigurationSection(mode.name());
                if (section == null) {
                    continue;
                }
                boolean selected = mode == current;
                ItemStack item = ItemManager.getItemConfigWithPlaceholders(player, section, "#status#",
                        LanguageManager.guiString(config, selected ? "status_selected" : "status_unselected"));
                if (selected && item != null) {
                    glow(item);
                }
                put(inventory, section.getInt("slot"), item, (p, click) -> choose(mode, LanguageManager.guiString(section, "name")));
            }
        }
        addBack(inventory);
    }

    private void choose(SortMode mode, String name) {
        String key = MineManager.storageKey(player);
        if (StorageSettings.getSortMode(key) != mode) {
            StorageSettings.setSortMode(key, mode);
            MineManager.savePlayerData(player);
            player.sendMessage(ChatUtils.colorize(File.getMessage()
                    .getString("storage_tools.sort.changed", "#prefix# &aSorting changed to &e#mode#&a.")
                    .replace("#mode#", ChatUtils.colorizewp(player, name))));
        }
        // Re-open so the selection marker moves; the main storage reads the saved mode when opened.
        reopen(new SortMenuGUI(player));
    }

    private static void glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        // XEnchantment resolves the enchantment name across versions (DURABILITY vs UNBREAKING).
        com.cryptomorin.xseries.XEnchantment.matchXEnchantment("UNBREAKING")
                .map(com.cryptomorin.xseries.XEnchantment::getEnchant)
                .ifPresent(enchant -> meta.addEnchant(enchant, 1, true));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }
}
