package net.danh.storage.GUI;

import net.danh.storage.GUI.manager.IGUI;
import net.danh.storage.GUI.manager.InteractiveItem;
import net.danh.storage.Manager.ItemManager;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.SoundManager;
import net.danh.storage.Manager.StorageCrafter;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageSettings.SortMode;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.Number;
import net.danh.storage.Utils.SoundContext;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class PersonalStorage implements IGUI {

    public static HashMap<UUID, Integer> playerCurrentPage = new HashMap<>();
    private final Player p;
    private final FileConfiguration config;
    private int currentPage;

    public PersonalStorage(Player p) {
        this(p, 0);
    }

    public PersonalStorage(Player p, int page) {
        this.p = p;
        this.currentPage = Math.max(0, page);
        config = File.getGUIStorage();
        playerCurrentPage.put(p.getUniqueId(), this.currentPage);
    }

    public static int getPlayerCurrentPage(Player player) {
        if (player == null) return 0;
        return playerCurrentPage.getOrDefault(player.getUniqueId(), 0);
    }

    /**
     * Display order of the item list; this never changes the stored amounts.
     * Ties always fall back to the stored insertion order (then config order), so equal items never shuffle.
     */
    static List<String> sortedItems(String key, List<String> configOrder, java.util.function.Function<String, String> name,
                                    java.util.function.ToIntFunction<String> amount) {
        List<String> inserted = StorageSettings.insertionOrder(key);
        List<String> base = new ArrayList<>();
        for (String item : inserted) {
            if (configOrder.contains(item)) base.add(item);
        }
        for (String item : configOrder) {
            if (!base.contains(item)) base.add(item);
        }
        SortMode mode = StorageSettings.getSortMode(key);
        if (mode == SortMode.INSERTION) {
            return base;
        }
        Comparator<String> comparator;
        switch (mode) {
            case TYPE -> comparator = Comparator.comparing(i -> i.split(";")[0], String.CASE_INSENSITIVE_ORDER);
            case NAME_ASC -> comparator = Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER);
            case NAME_DESC -> comparator = Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER).reversed();
            default -> comparator = Comparator.comparingInt(amount::applyAsInt).reversed();
        }
        // List.sort is stable, so equal elements keep their insertion order from `base`.
        base.sort(comparator);
        return base;
    }

    private static String plain(Player player, String text) {
        return ChatColor.stripColor(ChatUtils.colorizewp(player, text));
    }

    private static String format(long value) {
        return net.danh.storage.Manager.LanguageManager.formatNumber(value);
    }

    @NotNull
    @Override
    public Inventory getInventory() {
        return getInventory(SoundContext.INITIAL_OPEN);
    }

    @NotNull
    @Override
    public Inventory getInventory(SoundContext context) {
        SoundManager.playItemSound(p, config, "gui_open_sound", context);
        Inventory inventory = Bukkit.createInventory(this, config.getInt("size") * 9,
                ChatUtils.colorizewp(p, Objects.requireNonNull(net.danh.storage.Manager.LanguageManager.guiString(config, "title"))
                        .replace("#player#", p.getName())));
        String key = MineManager.storageKey(p);
        List<String> item_list = sortedItems(key, MineManager.getOrderedPluginBlocks(),
                k -> plain(p, File.getConfig().getString("items." + k, k.split(";")[0])),
                k -> MineManager.getPlayerBlock(p, MineManager.getMaterial(k)));

        List<String> storageSlots = slotsOf("storage_item");
        int itemsPerPage = Math.max(1, storageSlots.size());
        int totalPages = Math.max(1, (int) Math.ceil((double) item_list.size() / itemsPerPage));
        int displayPage = Math.min(currentPage, totalPages - 1);
        currentPage = displayPage;
        playerCurrentPage.put(p.getUniqueId(), displayPage);

        ConfigurationSection items = Objects.requireNonNull(config.getConfigurationSection("items"));
        for (String tag : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(tag);
            if (section == null) continue;
            switch (tag.toLowerCase(Locale.ROOT)) {
                case "storage_item" -> addStorageItems(inventory, item_list, storageSlots, displayPage, itemsPerPage);
                case "toggle_item" -> addToggle(inventory, tag, section);
                case "convert_button" -> addCrafter(inventory, tag, section);
                case "pickup_filter" -> addButton(inventory, tag, section, ItemManager.getItemConfig(p, section),
                        (player, click) -> open(player, new PickupFilterGUI(player, 0)));
                case "sorting_options" -> addButton(inventory, tag, section, ItemManager.getItemConfigWithPlaceholders(p,
                                section, "#sort_mode#", sortLabel(StorageSettings.getSortMode(key))),
                        (player, click) -> open(player, new SortMenuGUI(player)));
                case "previous_page" -> {
                    if (displayPage > 0) {
                        addButton(inventory, tag, section, navigation(tag, displayPage, totalPages),
                                (player, click) -> player.openInventory(
                                        new PersonalStorage(p, currentPage - 1).getInventory(SoundContext.SILENT)));
                    }
                }
                case "next_page" -> {
                    if (displayPage < totalPages - 1) {
                        addButton(inventory, tag, section, navigation(tag, displayPage, totalPages),
                                (player, click) -> player.openInventory(
                                        new PersonalStorage(p, currentPage + 1).getInventory(SoundContext.SILENT)));
                    }
                }
                default -> addDecoration(inventory, section);
            }
        }
        return inventory;
    }

    private String sortLabel(SortMode mode) {
        ConfigurationSection label = File.getStorageToolsConfig().getConfigurationSection("sort.modes." + mode.name());
        return label == null ? mode.name() : ChatUtils.colorizewp(p, net.danh.storage.Manager.LanguageManager.guiString(label, "name"));
    }

    private List<String> slotsOf(String tag) {
        List<String> result = new ArrayList<>();
        String raw = config.getString("items." + tag + ".slot");
        if (raw != null) {
            for (String part : raw.replace(" ", "").split(",")) {
                if (!part.isEmpty()) result.add(part);
            }
        }
        return result;
    }

    private void open(Player player, IGUI gui) {
        SoundManager.setShouldPlayCloseSound(player, false);
        player.openInventory(gui.getInventory(SoundContext.SILENT));
    }

    private void refresh(Player player) {
        SoundManager.setShouldPlayCloseSound(player, false);
        player.openInventory(new PersonalStorage(p, currentPage).getInventory(SoundContext.SILENT));
    }

    private void addButton(Inventory inventory, String tag, ConfigurationSection section, ItemStack stack,
                           java.util.function.BiConsumer<Player, ClickType> action) {
        if (stack == null) return;
        for (String slot : slotsOf(tag)) {
            InteractiveItem item = new InteractiveItem(stack.clone(), Number.getInteger(slot)).onClick((player, click) -> {
                SoundManager.playItemSound(player, config, "items." + tag, SoundContext.INITIAL_OPEN);
                action.accept(player, click);
            });
            inventory.setItem(item.getSlot(), item);
        }
    }

    private void addDecoration(Inventory inventory, ConfigurationSection section) {
        ItemStack stack = ItemManager.getItemConfig(section);
        for (String slot : section.getString("slot", "").replace(" ", "").split(",")) {
            if (slot.isEmpty()) continue;
            InteractiveItem item = new InteractiveItem(stack.clone(), Number.getInteger(slot));
            inventory.setItem(item.getSlot(), item);
        }
    }

    private void addStorageItems(Inventory inventory, List<String> itemList, List<String> slotList,
                                 int displayPage, int itemsPerPage) {
        int startIndex = displayPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, itemList.size());
        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= slotList.size()) break;
            String itemKey = itemList.get(i);
            String material = MineManager.getMaterial(itemKey);
            String name = File.getConfig().getString("items." + itemKey);
            String autopickupStatus = File.getMessage().getString(MineManager.isAutoPickupEnabledForItem(p, material)
                    ? "user.status.status_on" : "user.status.status_off");
            ItemStack itemStack = ItemManager.getItemConfigWithPlaceholders(p, material,
                    name != null ? name : itemKey.split(";")[0],
                    config.getConfigurationSection("items.storage_item"),
                    "#autopickup_status#", autopickupStatus);
            if (itemStack == null || itemStack.getType() == Material.AIR) continue;

            InteractiveItem interactiveItem = new InteractiveItem(itemStack, Number.getInteger(slotList.get(slotIndex)))
                    .onClick((player, clickType) -> {
                        SoundManager.playItemSound(player, config, "items.storage_item", SoundContext.INITIAL_OPEN);
                        SoundManager.setShouldPlayCloseSound(player, false);
                        if (clickType == ClickType.DROP) {
                            boolean enabled = MineManager.toggleItemAutoPickup(p, material);
                            String status = File.getMessage().getString(enabled
                                    ? "user.status.status_on" : "user.status.status_off");
                            String itemName = name != null ? name : itemKey.split(";")[0];
                            String template = File.getMessage().getString("user.status.item_toggle");
                            p.sendMessage(ChatUtils.colorize((template != null ? template
                                    : "#prefix# &bAuto-pickup for #item#: #status#")
                                    .replace("#item#", itemName).replace("#status#", status)));
                            refresh(player);
                            return;
                        }
                        player.openInventory(new ItemStorage(p, material, currentPage).getInventory(SoundContext.SILENT));
                    });
            inventory.setItem(interactiveItem.getSlot(), interactiveItem);
        }
    }

    /**
     * Copper Golem: left click toggles auto-pickup, Shift + left click opens the upgrade menu.
     * Shift is checked first so one click never does both.
     */
    private void addToggle(Inventory inventory, String tag, ConfigurationSection section) {
        boolean enabled = MineManager.getToggleStatus(p);
        ItemStack stack = ItemManager.getItemConfigWithMaterial(p, section,
                section.getString(enabled ? "material" : "material_disabled", section.getString("material")),
                "#status#", ItemManager.getStatus(p),
                "#used#", format(MineManager.getTotalStored(p)),
                "#capacity#", format(MineManager.getMaxBlock(p)));
        addButton(inventory, tag, section, stack, (player, click) -> {
            if (click == ClickType.SHIFT_LEFT) {
                open(player, new UpgradeGUI(player));
            } else if (click == ClickType.LEFT) {
                MineManager.setToggleStatus(p, !MineManager.getToggleStatus(p));
                MineManager.savePlayerData(p);
                p.sendMessage(ChatUtils.colorize(Objects.requireNonNull(File.getMessage().getString("user.status.toggle"))
                        .replace("#status#", ItemManager.getStatus(p))));
                refresh(player);
            }
        });
    }

    /**
     * Crafter: left click crafts one block, Shift + left click crafts everything possible (Donate permission).
     */
    private void addCrafter(Inventory inventory, String tag, ConfigurationSection section) {
        addButton(inventory, tag, section, ItemManager.getItemConfig(p, section), (player, click) -> {
            if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT) return;
            if (!StorageCrafter.isEnabled()) {
                send("storage_tools.crafter.disabled");
                return;
            }
            if (!StorageCrafter.hasPermission(player)) {
                send("storage_tools.crafter.no_permission");
                return;
            }
            boolean all = click == ClickType.SHIFT_LEFT;
            int crafted = all ? StorageCrafter.craftAll(p) : StorageCrafter.craftOne(p);
            if (crafted <= 0) {
                send("storage_tools.crafter.nothing");
                return;
            }
            MineManager.savePlayerData(p);
            p.sendMessage(ChatUtils.colorize(File.getMessage().getString(all
                            ? "storage_tools.crafter.crafted_all" : "storage_tools.crafter.crafted_one",
                    "#prefix# Crafted #amount# blocks").replace("#amount#", String.valueOf(crafted))));
            refresh(player);
        });
    }

    private void send(String path) {
        p.sendMessage(ChatUtils.colorize(File.getMessage().getString(path, "#prefix# " + path)));
    }

    public Player getPlayer() {
        return p;
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    private ItemStack navigation(String itemTag, int currentPage, int totalPages) {
        ConfigurationSection section = config.getConfigurationSection("items." + itemTag);
        if (section == null) return null;

        return ItemManager.getItemConfigWithPlaceholders(p, section,
                "#current_page#", String.valueOf(currentPage + 1),
                "#total_pages#", String.valueOf(totalPages));
    }
}
