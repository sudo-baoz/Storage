package net.danh.storage.Manager;

import net.danh.storage.Database.PlayerData;
import net.danh.storage.Manager.UpgradeRequirements.Cost;
import net.danh.storage.Manager.UpgradeRequirements.Evaluation;
import net.danh.storage.Manager.UpgradeRequirements.Kind;
import net.danh.storage.Manager.UpgradeRequirements.Step;
import net.danh.storage.Manager.Island.IslandStorageManager;
import net.danh.storage.Storage;
import net.danh.storage.Utils.File;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Capacity upgrades from upgrades.yml: up to 6 sequential levels, each with its own capacity, currency (Vault or
 * PlayerPoints), price, permission and icon. The level belongs to the storage (the island owner's key for islands).
 * A bad config never throws: invalid levels or slots are skipped or replaced by defaults, with a warning.
 */
public final class StorageUpgrade {

    public static final int MAX_LEVELS = 6;
    private static final Map<String, Integer> DEFAULT_SLOTS = new LinkedHashMap<>();
    private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();
    private static volatile Snapshot snapshot;

    static {
        String[] names = {"level-1", "level-2", "level-3", "level-4", "level-5", "level-6", "back", "close"};
        int[] slots = {11, 13, 15, 29, 31, 33, 39, 41};
        for (int i = 0; i < names.length; i++) {
            DEFAULT_SLOTS.put(names[i], slots[i]);
        }
    }

    private StorageUpgrade() {
    }

    public record LevelInfo(int level, int capacity, Cost cost, String permission, String material,
                            int customModelData) {
    }

    private record Snapshot(boolean enabled, List<LevelInfo> levels, Map<String, Integer> slots, int size,
                            boolean confirm, boolean sequential, boolean refresh, boolean sound) {
    }

    /**
     * Why a purchase was (not) possible.
     */
    public enum Result {
        SUCCESS, DISABLED, NOT_OWNER, ALREADY_OWNED, LOCKED, INVALID, NO_PERMISSION, NO_PROVIDER, NO_MONEY, NO_ITEMS,
        NO_EXP, NO_RESOURCES, BAD_CONFIG, PAYMENT_FAILED, BUSY, SAVE_FAILED, MAX_LEVEL
    }

    // ---------- config ----------

    // upgrades.yml before it is loaded (early startup, tests) behaves as an empty config: no levels.
    private static FileConfiguration cfg() {
        FileConfiguration loaded = File.getUpgradesConfig();
        return loaded != null ? loaded : new org.bukkit.configuration.file.YamlConfiguration();
    }

    public static void reload() {
        snapshot = parse(cfg());
    }

    private static Snapshot get() {
        Snapshot current = snapshot;
        if (current == null) {
            current = parse(cfg());
            snapshot = current;
        }
        return current;
    }

    private static Snapshot parse(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("settings.enabled", true);
        // settings.max-level is no longer read: an old migrated value of 3 hid levels 4-6.
        List<LevelInfo> levels = new ArrayList<>();
        int previousCapacity = 0;
        for (int level = 1; level <= MAX_LEVELS; level++) {
            String path = "levels." + level + ".";
            int capacity = cfg.getInt(path + "capacity", 0);
            if (capacity <= 0) {
                log(Level.WARNING, "upgrades.yml: levels." + level + ".capacity is missing or not positive; level "
                        + level + " and the levels after it are not available.");
                break;
            }
            if (capacity <= previousCapacity) {
                // Not fatal: the storage keeps the larger of the base and level capacity.
                log(Level.WARNING, "upgrades.yml: level " + level + " has capacity " + capacity
                        + " which is not above level " + (level - 1) + " (" + previousCapacity + ").");
            }
            Cost cost = UpgradeRequirements.parse(cfg.getConfigurationSection("levels." + level), level,
                    message -> log(Level.WARNING, "upgrades.yml: " + message));
            levels.add(new LevelInfo(level, capacity, cost, cfg.getString(path + "permission", ""),
                    cfg.getString(path + "material", "PAPER"), Math.max(0, cfg.getInt(path + "custom-model-data", 0))));
            previousCapacity = Math.max(previousCapacity, capacity);
        }
        int size = cfg.getInt("gui.size", 45);
        if (size < 9 || size > 54 || size % 9 != 0) {
            log(Level.WARNING, "upgrades.yml: gui.size must be 9, 18, ..., 54; using 45.");
            size = 45;
        }
        return new Snapshot(enabled, List.copyOf(levels), validSlots(cfg, size), size,
                cfg.getBoolean("settings.confirm-purchase", true),
                cfg.getBoolean("settings.require-sequential-upgrade", true),
                cfg.getBoolean("settings.refresh-gui-after-upgrade", true),
                cfg.getBoolean("settings.play-sound", true) && cfg.getBoolean("sounds.enabled", true));
    }

    // Slots must be inside the menu and unique, otherwise the whole layout falls back to the defaults.
    private static Map<String, Integer> validSlots(FileConfiguration cfg, int size) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        for (Map.Entry<String, Integer> entry : DEFAULT_SLOTS.entrySet()) {
            int slot = cfg.getInt("gui.slots." + entry.getKey(), entry.getValue());
            if (slot < 0 || slot >= size || !used.add(slot)) {
                log(Level.WARNING, "upgrades.yml: gui.slots." + entry.getKey() + " = " + slot
                        + " is outside the menu or duplicated; the default layout is used.");
                return new LinkedHashMap<>(DEFAULT_SLOTS);
            }
            slots.put(entry.getKey(), slot);
        }
        return slots;
    }

    private static void log(Level level, String message) {
        if (Storage.getStorage() != null) {
            Storage.getStorage().getLogger().log(level, message);
        }
    }

    // ---------- queries ----------

    public static boolean isEnabled() {
        return get().enabled();
    }

    public static boolean confirmPurchase() {
        return get().confirm();
    }

    public static boolean refreshAfterUpgrade() {
        return get().refresh();
    }

    public static int guiSize() {
        return get().size();
    }

    public static int slot(String name) {
        return get().slots().getOrDefault(name, DEFAULT_SLOTS.getOrDefault(name, 0));
    }

    public static int maxLevel() {
        return get().levels().size();
    }

    public static @Nullable LevelInfo info(int level) {
        List<LevelInfo> levels = get().levels();
        return level >= 1 && level <= levels.size() ? levels.get(level - 1) : null;
    }

    /**
     * Capacity granted by a level; 0 for level 0 or an undefined level (the base capacity then applies).
     */
    public static int capacity(int level) {
        LevelInfo info = info(level);
        return info == null || !isEnabled() ? 0 : info.capacity();
    }

    public static ConfigurationSection decoration() {
        return cfg().getConfigurationSection("gui.decoration");
    }

    public static int currentLevel(@NotNull Player player) {
        return StorageSettings.getLevel(MineManager.storageKey(player));
    }

    public static void playSound(@NotNull Player player, @NotNull String name) {
        if (!get().sound()) {
            return;
        }
        ConfigurationSection section = cfg().getConfigurationSection("sounds." + name);
        if (section != null && section.getString("sound") != null) {
            SoundManager.playSound(player, section.getString("sound"), (float) section.getDouble("volume", 1.0),
                    (float) section.getDouble("pitch", 1.0));
        }
    }

    // ---------- purchase ----------

    /**
     * Side-effect free check of whether {@code target} can be bought right now.
     */
    public static @NotNull Result check(@NotNull Player player, int target) {
        if (!isEnabled()) {
            return Result.DISABLED;
        }
        LevelInfo info = info(target);
        if (info == null) {
            return Result.INVALID;
        }
        int current = currentLevel(player);
        if (target <= current) {
            return current >= maxLevel() ? Result.MAX_LEVEL : Result.ALREADY_OWNED;
        }
        if (get().sequential() ? target != current + 1 : false) {
            return Result.LOCKED;
        }
        if (!IslandStorageManager.isOwnerOrSolo(player)) {
            return Result.NOT_OWNER;
        }
        if (!info.permission().isEmpty() && !player.hasPermission(info.permission())) {
            return Result.NO_PERMISSION;
        }
        if (!info.cost().valid()) {
            return Result.BAD_CONFIG;
        }
        return result(UpgradeRequirements.evaluate(player, info.cost()));
    }

    static Result result(Evaluation evaluation) {
        if (evaluation.providerMissing()) {
            return Result.NO_PROVIDER;
        }
        boolean items = false, exp = false, money = false;
        for (UpgradeRequirements.Line line : evaluation.lines()) {
            if (line.ok()) {
                continue;
            }
            items |= line.kind() == Kind.ITEM;
            exp |= line.kind() == Kind.EXP_LEVELS || line.kind() == Kind.EXP_POINTS;
            money |= line.kind() == Kind.VAULT || line.kind() == Kind.POINTS;
        }
        int missing = (items ? 1 : 0) + (exp ? 1 : 0) + (money ? 1 : 0);
        if (missing == 0) {
            return Result.SUCCESS;
        }
        return missing > 1 ? Result.NO_RESOURCES : items ? Result.NO_ITEMS : exp ? Result.NO_EXP : Result.NO_MONEY;
    }

    /**
     * Charges once, raises the level and saves; if the saved row does not carry the new level the level is rolled
     * back and the payment refunded. The payment and the database are two systems, so this is best effort, not atomic.
     */
    public static @NotNull Result purchase(@NotNull Player player, int target) {
        if (!BUSY.add(player.getUniqueId())) {
            return Result.BUSY;
        }
        try {
            Result checked = check(player, target);
            if (checked != Result.SUCCESS) {
                return checked;
            }
            LevelInfo info = Objects.requireNonNull(info(target));
            String key = MineManager.storageKey(player);
            int previous = StorageSettings.getLevel(key);

            // check() above proved everything is there; each step is reversible, so a failure puts it all back.
            List<Step> done = new ArrayList<>();
            for (Step step : UpgradeRequirements.steps(player, info.cost())) {
                boolean taken;
                try {
                    taken = step.take().getAsBoolean();
                } catch (RuntimeException e) {
                    log(Level.SEVERE, "Upgrade payment error for " + player.getName() + " (" + step.name() + "): " + e);
                    taken = false;
                }
                if (!taken) {
                    log(Level.WARNING, "Upgrade payment step failed for " + player.getName() + " (level " + target
                            + ", " + step.name() + "); undoing " + done.size() + " step(s).");
                    undo(player, done, target);
                    return Result.PAYMENT_FAILED;
                }
                done.add(step);
            }
            StorageSettings.setLevel(key, target);
            if (!saved(player, key, target)) {
                StorageSettings.setLevel(key, previous);
                log(Level.WARNING, "Upgrade save failed for " + player.getName() + " (level " + target + ").");
                undo(player, done, target);
                return Result.SAVE_FAILED;
            }
            log(Level.INFO, player.getName() + " upgraded storage to level " + target + " paying "
                    + info.cost());
            return Result.SUCCESS;
        } finally {
            BUSY.remove(player.getUniqueId());
        }
    }

    private static void undo(Player player, List<Step> done, int target) {
        for (int i = done.size() - 1; i >= 0; i--) {
            Step step = done.get(i);
            boolean back;
            try {
                back = step.undo().getAsBoolean();
            } catch (RuntimeException e) {
                back = false;
            }
            if (!back) {
                log(Level.SEVERE, "REFUND FAILED for " + player.getName() + " (upgrade level " + target + "): "
                        + step.name() + " must be returned manually");
            }
        }
    }

    // The save methods swallow database errors, so read the row back and make sure the level really is there.
    private static boolean saved(Player player, String key, int level) {
        try {
            MineManager.savePlayerData(player);
            PlayerData row = Storage.db.getData(key);
            return row != null && row.data() != null && row.data().contains(";upgrade:" + level);
        } catch (RuntimeException e) {
            log(Level.SEVERE, "Could not save the upgrade level: " + e.getMessage());
            return false;
        }
    }
}
