package net.danh.storage.GUI;

import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Manager.StorageUpgrade.LevelInfo;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/**
 * 27-slot confirmation shown before an upgrade is paid: confirm | transaction info | cancel.
 */
public class UpgradeConfirmGUI extends StorageToolGUI {

    private final int level;

    public UpgradeConfirmGUI(Player player, int level) {
        super(player, new MemoryConfiguration());
        this.level = level;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected String title() {
        return File.getMessage().getString("upgrade_gui.confirm.title", "Confirm");
    }

    @Override
    protected ConfigurationSection decoration() {
        return StorageUpgrade.decoration();
    }

    @Override
    protected void build(Inventory inventory) {
        LevelInfo info = StorageUpgrade.info(level);
        if (info == null) {
            return;
        }
        Map<String, String> values = UpgradeGUI.valuesFor(player, level, StorageUpgrade.currentLevel(player));
        put(inventory, slot("confirm", 11), button(material("confirm", Material.EMERALD_BLOCK), "confirm", values),
                (p, click) -> UpgradeGUI.buy(player, level));
        put(inventory, slot("info", 13), infoItem(info, values), null);
        put(inventory, slot("cancel", 15), button(material("cancel", Material.REDSTONE_BLOCK), "cancel", values),
                (p, click) -> reopen(new UpgradeGUI(player)));
    }

    private int slot(String name, int fallback) {
        int slot = File.getUpgradesConfig().getInt("confirm.slots." + name, fallback);
        return slot >= 0 && slot < 27 ? slot : fallback;
    }

    private Material material(String name, Material fallback) {
        Material found = Material.matchMaterial(File.getUpgradesConfig().getString("confirm.materials." + name, ""));
        return found == null ? fallback : found;
    }

    private ItemStack infoItem(LevelInfo info, Map<String, String> values) {
        ItemStack item = UpgradeGUI.icon(info.material(), info.customModelData());
        return decorate(item, "info", values);
    }

    private ItemStack button(Material material, String key, Map<String, String> values) {
        return decorate(new ItemStack(material), key, values);
    }

    private ItemStack decorate(ItemStack item, String key, Map<String, String> values) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String path = "upgrade_gui.confirm." + key;
        meta.setDisplayName(ChatUtils.colorizewp(player, UpgradeGUI.replaceAll(File.getMessage().getString(path + ".name", key), values)));
        meta.setLore(UpgradeGUI.expand(player, File.getMessage().getStringList(path + ".lore"), values,
                StorageUpgrade.info(level)));
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }
}
