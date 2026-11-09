package net.danh.storage.GUI;

import net.danh.storage.Manager.UpgradeRequirements;
import net.danh.storage.Manager.UpgradeRequirements.Line;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Manager.StorageUpgrade.LevelInfo;
import net.danh.storage.Manager.StorageUpgrade.Result;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.MaterialUtils;
import net.danh.storage.Utils.NumberFormatter;
import net.danh.storage.Utils.SchedulerUtil;
import net.danh.storage.Storage;
import org.bukkit.Material;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copper Golem (Shift + left click) menu: up to six upgrade levels on a fixed symmetric layout plus back/close.
 * Everything a player needs to decide (capacity, price, currency, status) is in the lore of each level.
 */
public class UpgradeGUI extends StorageToolGUI {

    enum State {OWNED, MAX, AVAILABLE, LOCKED}

    public UpgradeGUI(Player player) {
        super(player, new MemoryConfiguration());
    }

    /**
     * The single way to open this menu (Copper Golem Shift+click and /storage upgrade).
     */
    public static void show(Player player) {
        StorageUpgrade.playSound(player, "open");
        net.danh.storage.Manager.SoundManager.setShouldPlayCloseSound(player, false);
        player.openInventory(new UpgradeGUI(player).getInventory(net.danh.storage.Utils.SoundContext.SILENT));
    }

    @Override
    protected int rows() {
        return StorageUpgrade.guiSize() / 9;
    }

    @Override
    protected String title() {
        return File.getMessage().getString("upgrade_gui.title", "Upgrade");
    }

    @Override
    protected ConfigurationSection decoration() {
        return StorageUpgrade.decoration();
    }

    @Override
    protected void build(Inventory inventory) {
        int current = StorageUpgrade.currentLevel(player);
        int max = StorageUpgrade.maxLevel();
        for (int level = 1; level <= StorageUpgrade.MAX_LEVELS; level++) {
            LevelInfo info = StorageUpgrade.info(level);
            if (info == null) {
                continue; // level not configured: its slot stays decoration
            }
            State state = stateOf(level, current, max);
            ItemStack item = levelItem(info, state, current);
            final int target = level;
            put(inventory, StorageUpgrade.slot("level-" + level), item, (p, click) -> onLevelClick(target, state));
        }
        put(inventory, StorageUpgrade.slot("back"), simple(Material.ARROW, "upgrade_gui.back"),
                (p, click) -> openStorage());
        put(inventory, StorageUpgrade.slot("close"), simple(Material.BARRIER, "upgrade_gui.close"),
                (p, click) -> player.closeInventory());
    }

    static State stateOf(int level, int current, int max) {
        if (level == max && current >= max) {
            return State.MAX;
        }
        if (level <= current) {
            return State.OWNED;
        }
        return level == current + 1 ? State.AVAILABLE : State.LOCKED;
    }

    private void onLevelClick(int level, State state) {
        switch (state) {
            case AVAILABLE -> {
                Result check = StorageUpgrade.check(player, level);
                if (check != Result.SUCCESS) {
                    reply(check, level);
                    StorageUpgrade.playSound(player, "fail");
                } else if (StorageUpgrade.confirmPurchase()) {
                    reopen(new UpgradeConfirmGUI(player, level));
                } else {
                    buy(player, level);
                }
            }
            case LOCKED -> {
                reply(Result.LOCKED, level);
                StorageUpgrade.playSound(player, "fail");
            }
            default -> {
                reply(state == State.MAX ? Result.MAX_LEVEL : Result.ALREADY_OWNED, level);
                StorageUpgrade.playSound(player, "fail");
            }
        }
    }

    /**
     * Pays and applies a level, tells the player what happened and re-opens the menu.
     */
    static void buy(Player player, int level) {
        Result result = StorageUpgrade.purchase(player, level);
        sendResult(player, result, level);
        StorageUpgrade.playSound(player, result == Result.SUCCESS ? "success" : "fail");
        if (result == Result.SUCCESS && !StorageUpgrade.refreshAfterUpgrade()) {
            return;
        }
        // Next tick: this runs inside the click event of the menu that is being replaced.
        SchedulerUtil.runTask(Storage.getStorage(), player, () -> {
            if (player.isOnline()) {
                net.danh.storage.Manager.SoundManager.setShouldPlayCloseSound(player, false);
                player.openInventory(new UpgradeGUI(player).getInventory(net.danh.storage.Utils.SoundContext.SILENT));
            }
        });
    }

    private void reply(Result result, int level) {
        sendResult(player, result, level);
    }

    static void sendResult(Player player, Result result, int level) {
        String key = switch (result) {
            case SUCCESS -> "success";
            case MAX_LEVEL -> "max_level";
            case ALREADY_OWNED -> "already_owned";
            case LOCKED -> "locked";
            case NO_PERMISSION -> "no_permission";
            case NO_PROVIDER -> "no_provider";
            case NO_MONEY -> "no_money";
            case NO_ITEMS -> "no_items";
            case NO_EXP -> "no_exp";
            case NO_RESOURCES -> "no_resources";
            case BAD_CONFIG -> "bad_config";
            case PAYMENT_FAILED -> "payment_failed";
            case NOT_OWNER -> "not_owner";
            case DISABLED -> "disabled";
            case BUSY -> "busy";
            case SAVE_FAILED -> "save_failed";
            case INVALID -> "invalid";
        };
        String text = File.getMessage().getString("storage_tools.upgrade." + key, "#prefix# " + key);
        player.sendMessage(ChatUtils.colorize(replace(player, text, level)));
    }

    // ---------- items ----------

    private ItemStack levelItem(LevelInfo info, State state, int current) {
        String key = state.name().toLowerCase(Locale.ROOT);
        ItemStack item = icon(info.material(), info.customModelData());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String statusKey = "";
        if (state == State.AVAILABLE) {
            statusKey = switch (StorageUpgrade.check(player, info.level())) {
                case SUCCESS -> "ready";
                case NO_MONEY, NO_ITEMS, NO_EXP, NO_RESOURCES -> "insufficient";
                case NO_PERMISSION -> "no_permission";
                case NOT_OWNER -> "not_owner";
                case DISABLED -> "disabled";
                case BAD_CONFIG -> "bad_config";
                default -> "no_provider";
            };
        }
        Map<String, String> values = values(info.level(), current);
        values.put("#status#", statusKey.isEmpty() ? "" : File.getMessage().getString("upgrade_gui.status." + statusKey, ""));
        values.put("#hint#", statusKey.equals("ready") ? File.getMessage().getString("upgrade_gui.hint.ready", "") : "");
        meta.setDisplayName(fill(File.getMessage().getString("upgrade_gui.name." + key, "&7Level #level#"), values));
        List<String> lore = expand(player, File.getMessage().getStringList("upgrade_gui.lore." + key), values, info);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        if (state == State.AVAILABLE || state == State.MAX) {
            glow(item);
        }
        return item;
    }

    private ItemStack simple(Material fallback, String path) {
        ItemStack item = new ItemStack(fallback);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatUtils.colorizewp(player, File.getMessage().getString(path + ".name", "")));
            List<String> lore = new ArrayList<>();
            for (String line : File.getMessage().getStringList(path + ".lore")) {
                lore.add(ChatUtils.colorizewp(player, line));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack icon(String material, int customModelData) {
        ItemStack item = MaterialUtils.createItem(material);
        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(Material.PAPER);
        }
        if (customModelData > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(customModelData);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    // ---------- text ----------

    Map<String, String> values(int level, int current) {
        return valuesFor(player, level, current);
    }

    static Map<String, String> valuesFor(Player player, int level, int current) {
        LevelInfo info = StorageUpgrade.info(level);
        long currentCapacity = MineManager.getMaxBlock(player);
        long levelCapacity = info == null ? 0 : info.capacity();
        // Capacity never goes down: a level below the base capacity adds nothing (see MineManager.getMaxBlock).
        long newCapacity = Math.max(currentCapacity, levelCapacity);
        Map<String, String> values = new java.util.HashMap<>();
        values.put("#level#", String.valueOf(level));
        values.put("#current_level#", String.valueOf(current));
        values.put("#required_level#", String.valueOf(Math.max(0, level - 1)));
        values.put("#capacity#", NumberFormatter.format(NumberFormatter.CAPACITY, levelCapacity));
        values.put("#current_capacity#", NumberFormatter.format(NumberFormatter.CAPACITY, currentCapacity));
        values.put("#new_capacity#", NumberFormatter.format(NumberFormatter.CAPACITY, newCapacity));
        values.put("#increase#", NumberFormatter.format(NumberFormatter.CAPACITY, Math.max(0, newCapacity - currentCapacity)));
        return values;
    }

    /**
     * Fills the placeholders of a lore template: {@code #requirements#} becomes one line per active requirement
     * and {@code #hint#} disappears when empty.
     */
    static List<String> expand(Player player, List<String> template, Map<String, String> values, LevelInfo info) {
        List<String> lore = new ArrayList<>();
        for (String line : template) {
            String trimmed = line.trim();
            if (trimmed.equals("#hint#") && values.getOrDefault("#hint#", "").isEmpty()) {
                continue; // the click hint only shows when the level can be bought
            }
            if (trimmed.equals("#requirements#")) {
                lore.addAll(requirementLines(player, info));
            } else {
                lore.add(ChatUtils.colorizewp(player, replaceAll(line, values)));
            }
        }
        return lore;
    }

    static List<String> requirementLines(Player player, LevelInfo info) {
        List<String> lines = new ArrayList<>();
        if (!info.cost().valid()) {
            return lines;
        }
        for (Line line : UpgradeRequirements.evaluate(player, info.cost()).lines()) {
            String name = switch (line.kind()) {
                case VAULT -> "vault";
                case POINTS -> "playerpoints";
                case EXP_LEVELS -> "exp-levels";
                case EXP_POINTS -> "exp-points";
                case ITEM -> "item";
            };
            String mark = File.getMessage().getString(line.ok() ? "upgrade.requirements.mark-ok"
                    : "upgrade.requirements.mark-missing", line.ok() ? "&a✔ " : "&c✘ ");
            String text = File.getMessage().getString("upgrade.requirements." + name, name);
            String category = switch (line.kind()) {
                case VAULT -> NumberFormatter.VAULT;
                case POINTS -> NumberFormatter.PLAYERPOINTS;
                case EXP_LEVELS, EXP_POINTS -> NumberFormatter.EXP;
                case ITEM -> NumberFormatter.ITEMS;
            };
            text = text.replace("#item#", line.material() == null ? "" : itemName(line.material()))
                    .replace("#current#", NumberFormatter.format(category, line.current()))
                    .replace("#required#", NumberFormatter.format(category, line.required()));
            lines.add(ChatUtils.colorizewp(player, mark + text));
        }
        return lines;
    }

    // lang/<code>.yml may name an item (upgrade.items.DIAMOND); otherwise "Iron Ingot" from IRON_INGOT.
    private static String itemName(Material material) {
        String configured = File.getMessage().getString("upgrade.items." + material.name());
        if (configured != null) {
            return configured;
        }
        StringBuilder name = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            name.append(name.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    private String fill(String text, Map<String, String> values) {
        return ChatUtils.colorizewp(player, replaceAll(text, values));
    }

    static String replaceAll(String text, Map<String, String> values) {
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String replace(Player player, String text, int level) {
        return replaceAll(text, valuesFor(player, level, StorageUpgrade.currentLevel(player)));
    }
}
