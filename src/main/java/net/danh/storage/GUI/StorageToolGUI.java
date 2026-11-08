package net.danh.storage.GUI;

import net.danh.storage.GUI.manager.IGUI;
import net.danh.storage.GUI.manager.InteractiveItem;
import net.danh.storage.Manager.ItemManager;
import net.danh.storage.Manager.SoundManager;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.SoundContext;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/**
 * Shared plumbing of the menus in GUI/storage-tools.yml: title/size from config, decoration filler without
 * tooltip, and a back button to the main storage. Every item is an {@link InteractiveItem}, so the click
 * listener cancels all clicks on this inventory.
 */
public abstract class StorageToolGUI implements IGUI {

    protected final Player player;
    protected final ConfigurationSection config;

    StorageToolGUI(Player player, String section) {
        this.player = player;
        this.config = File.getStorageToolsConfig().getConfigurationSection(section);
    }

    protected abstract void build(Inventory inventory);

    @NotNull
    @Override
    public Inventory getInventory() {
        return getInventory(SoundContext.INITIAL_OPEN);
    }

    @NotNull
    @Override
    public Inventory getInventory(SoundContext context) {
        int rows = Math.max(1, Math.min(6, config.getInt("size", 3)));
        Inventory inventory = Bukkit.createInventory(this, rows * 9,
                ChatUtils.colorizewp(player, config.getString("title", "Storage")));
        build(inventory);
        fillEmpty(inventory);
        return inventory;
    }

    protected void put(Inventory inventory, int slot, ItemStack item, BiConsumer<Player, ClickType> onClick) {
        if (item == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        InteractiveItem button = new InteractiveItem(item, slot);
        if (onClick != null) {
            button.onClick(onClick);
        }
        inventory.setItem(slot, button);
    }

    protected void addBack(Inventory inventory) {
        ConfigurationSection back = config.getConfigurationSection("back");
        if (back != null) {
            put(inventory, back.getInt("slot"), ItemManager.getItemConfig(back), (p, click) -> openStorage());
        }
    }

    protected void openStorage() {
        SoundManager.setShouldPlayCloseSound(player, false);
        player.openInventory(new PersonalStorage(player, PersonalStorage.getPlayerCurrentPage(player))
                .getInventory(SoundContext.SILENT));
    }

    protected void reopen(StorageToolGUI gui) {
        SoundManager.setShouldPlayCloseSound(player, false);
        player.openInventory(gui.getInventory(SoundContext.SILENT));
    }

    private void fillEmpty(Inventory inventory) {
        ConfigurationSection decorates = File.getStorageToolsConfig().getConfigurationSection("decorates");
        if (decorates == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                put(inventory, slot, ItemManager.getItemConfig(decorates), null);
            }
        }
    }
}
