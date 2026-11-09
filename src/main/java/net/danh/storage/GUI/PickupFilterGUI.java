package net.danh.storage.GUI;

import net.danh.storage.Manager.ItemManager;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.Number;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Hopper menu: the items the storage may auto-pickup. An empty filter keeps the default (everything is picked up).
 */
public class PickupFilterGUI extends StorageToolGUI {

    private final int page;

    public PickupFilterGUI(Player player, int page) {
        super(player, "filter");
        this.page = Math.max(0, page);
    }

    @Override
    protected void build(Inventory inventory) {
        String key = MineManager.storageKey(player);
        List<Integer> slots = new ArrayList<>();
        for (String raw : config.getString("item_slots", "").split(",")) {
            if (!raw.trim().isEmpty()) {
                slots.add(Number.getInteger(raw.trim()));
            }
        }
        List<String> materials = new ArrayList<>(MineManager.getOrderedPluginBlocks());
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (int) Math.ceil(materials.size() / (double) perPage));
        int shown = Math.min(page, pages - 1);

        ConfigurationSection itemSection = config.getConfigurationSection("item");
        for (int i = 0; i < perPage && shown * perPage + i < materials.size(); i++) {
            String material = materials.get(shown * perPage + i);
            boolean inFilter = StorageSettings.getFilter(key).contains(material);
            String name = File.getConfig().getString("items." + material, material.split(";")[0]);
            ItemStack item = ItemManager.getItemConfigWithPlaceholders(player, material, name, itemSection,
                    "#status#", LanguageManager.guiString(itemSection, inFilter ? "status_in" : "status_out"));
            put(inventory, slots.get(i), item, (p, click) -> toggle(material, name, shown));
        }

        ConfigurationSection info = config.getConfigurationSection("info");
        if (info != null) {
            put(inventory, info.getInt("slot"), ItemManager.getItemConfigWithPlaceholders(player, info,
                    "#count#", String.valueOf(StorageSettings.getFilter(key).size())), null);
        }
        ConfigurationSection clear = config.getConfigurationSection("clear");
        if (clear != null) {
            put(inventory, clear.getInt("slot"), ItemManager.getItemConfig(clear), (p, click) -> clear(shown));
        }
        if (shown > 0) {
            navigation(inventory, "previous_page", shown, pages, shown - 1);
        }
        if (shown < pages - 1) {
            navigation(inventory, "next_page", shown, pages, shown + 1);
        }
        addBack(inventory);
    }

    private void navigation(Inventory inventory, String tag, int shown, int pages, int target) {
        ConfigurationSection section = config.getConfigurationSection(tag);
        if (section != null) {
            put(inventory, section.getInt("slot"), ItemManager.getItemConfigWithPlaceholders(player, section,
                            "#current_page#", String.valueOf(shown + 1), "#total_pages#", String.valueOf(pages)),
                    (p, click) -> reopen(new PickupFilterGUI(player, target)));
        }
    }

    private void toggle(String material, String name, int shown) {
        String key = MineManager.storageKey(player);
        boolean added = StorageSettings.toggleFilter(key, material);
        MineManager.savePlayerData(player);
        player.sendMessage(ChatUtils.colorize(File.getMessage().getString(
                        added ? "storage_tools.filter.added" : "storage_tools.filter.removed", "#prefix# #item#")
                .replace("#item#", ChatUtils.colorizewp(player, name))));
        reopen(new PickupFilterGUI(player, shown));
    }

    private void clear(int shown) {
        StorageSettings.clearFilter(MineManager.storageKey(player));
        MineManager.savePlayerData(player);
        player.sendMessage(ChatUtils.colorize(File.getMessage()
                .getString("storage_tools.filter.cleared", "#prefix# Filter cleared")));
        reopen(new PickupFilterGUI(player, shown));
    }
}
