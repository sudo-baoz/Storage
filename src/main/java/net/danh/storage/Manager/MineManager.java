package net.danh.storage.Manager;

import net.danh.storage.API.StorageHookAPI;
import net.danh.storage.API.events.StorageDepositEvent;
import net.danh.storage.API.events.StorageToggleEvent;
import net.danh.storage.API.events.StorageWithdrawEvent;
import net.danh.storage.Database.PlayerData;
import net.danh.storage.Manager.Island.IslandStorageManager;
import net.danh.storage.Manager.SpecialMaterial.SpecialMaterialManager;
import net.danh.storage.NMS.NMSAssistant;
import net.danh.storage.Storage;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.MaterialUtils;
import net.danh.storage.Utils.Number;
import net.danh.storage.Utils.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

public class MineManager {

    private static final NMSAssistant NMS = new NMSAssistant();
    private static final boolean IS_LEGACY = NMS.isVersionLessThanOrEqualTo(12);
    private static final boolean IS_BEFORE_9 = NMS.isVersionLessThan(9);
    private static final Map<InvLookupKey, String> inventoryDropLookup =
            new HashMap<>();
    private static final String STORAGE_LIMIT_PERMISSION_PREFIX =
            "storage.storage.";
    private static final String STORAGE_LIMIT_PERMISSION_MAX_PREFIX =
            "storage.storage.max.";
    private static final HashMap<String, Set<String>> disabledAutoPickupItems =
            new HashMap<>();
    private static final String TOGGLE_DATA_PREFIX = ";toggle:";
    private static final String MAX_OVERRIDE_DATA_PREFIX = ";maxoverride:";
    private static final String AUTO_SELL_DATA_PREFIX = ";autosell:";
    private static final HashMap<UUID, Integer> maxOverrideData =
            new HashMap<>();
    private static final String PLACED_BLOCKS_FILE_NAME = "placed-blocks.yml";
    private static final String PLACED_BLOCKS_ROOT = "worlds";
    private static final int COORD_OFFSET_26 = 1 << 25;
    private static final int Y_OFFSET_12 = 1 << 11;
    private static final long SAVE_DELAY_TICKS = 20L * 60L;
    private static final Map<UUID, Set<Long>> placedBlocks = new HashMap<>();
    private static final HashMap<String, Set<String>> autoSellItems = new HashMap<>();
    private static final Set<String> pendingAutoSell = new HashSet<>();
    private static final Map<String, Long> lastAutoSellAt = new HashMap<>();
    public static HashMap<String, Integer> playerdata = new HashMap<>();
    public static HashMap<UUID, Integer> playermaxdata = new HashMap<>();
    public static HashMap<String, String> blocksdata = new HashMap<>();
    public static HashMap<String, String> blocksdrop = new HashMap<>();
    public static HashMap<String, String> blocksdropAutoSmelt = new HashMap<>();
    public static HashMap<UUID, Boolean> toggle = new HashMap<>();
    private static boolean placedBlocksLoaded;
    private static boolean placedBlocksDirty;
    private static boolean placedBlocksSaveScheduled;

    /**
     * Storage row each online player loaded: the island owner's name for island members, the own name otherwise.
     */
    private static final Map<UUID, String> activeKey = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Name of the storage row the player is using right now (shared island storage or personal storage).
     */
    public static @NotNull String storageKey(@NotNull Player p) {
        return activeKey.getOrDefault(p.getUniqueId(), p.getName());
    }

    /**
     * Same lookup for APIs that only know a name: online players use their loaded row, offline players follow
     * their island if they have one.
     */
    private static @NotNull String resolveStorageName(@NotNull String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return storageKey(online);
        }
        if (!IslandStorageManager.isEnabled()) {
            return playerName;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        return IslandStorageManager.resolveKey(offline.getUniqueId(), playerName);
    }

    private static boolean isKeyInUse(@NotNull String key, @Nullable UUID except) {
        return activeKey.entrySet().stream()
                .anyMatch(e -> e.getValue().equals(key) && !e.getKey().equals(except));
    }

    /**
     * Re-evaluates which storage the player must use after their island membership changed, saving the old
     * storage first. Does nothing when the player is not loaded or the storage did not change.
     */
    public static void refreshStorageOwner(@NotNull Player p) {
        String oldKey = activeKey.get(p.getUniqueId());
        if (oldKey == null || oldKey.equals(IslandStorageManager.resolveKey(p))) {
            return;
        }
        savePlayerData(p);
        cleanupPlayerData(p);
        loadPlayerData(p);
    }

    /**
     * Sells are limited for island members the owner did not allow. Sends the denial message.
     *
     * @return true when the player may NOT sell
     */
    public static boolean denySell(@NotNull Player p) {
        if (IslandStorageManager.canSell(p)) {
            return false;
        }
        p.sendMessage(ChatUtils.colorize(File.getMessage().getString("user.island.sell_denied")));
        return true;
    }

    public static int getPlayerBlock(@NotNull Player p, String material) {
        return playerdata.getOrDefault(storageKey(p) + "_" + material, 0);
    }

    public static void loadPlacedBlocks() {
        placedBlocks.clear();
        placedBlocksDirty = false;
        placedBlocksSaveScheduled = false;
        placedBlocksLoaded = true;

        Storage plugin = Storage.getStorage();
        if (plugin == null) {
            return;
        }
        java.io.File dataFolder = plugin.getDataFolder();
        if (dataFolder == null) {
            return;
        }
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            return;
        }

        java.io.File file = new java.io.File(dataFolder, PLACED_BLOCKS_FILE_NAME);
        if (!file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.contains(PLACED_BLOCKS_ROOT)) {
            return;
        }

        if (cfg.getConfigurationSection(PLACED_BLOCKS_ROOT) == null) {
            return;
        }
        for (String worldId : Objects.requireNonNull(cfg.getConfigurationSection(PLACED_BLOCKS_ROOT)).getKeys(false)) {
            if (worldId == null || worldId.trim().isEmpty()) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(worldId);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            List<Long> values = cfg.getLongList(PLACED_BLOCKS_ROOT + "." + worldId);
            if (values == null || values.isEmpty()) {
                continue;
            }
            placedBlocks.put(uuid, new HashSet<>(values));
        }
    }

    public static void savePlacedBlocks(boolean async) {
        if (!placedBlocksLoaded) {
            return;
        }
        if (!placedBlocksDirty) {
            return;
        }
        Storage plugin = Storage.getStorage();
        if (plugin == null) {
            return;
        }

        Map<UUID, List<Long>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, Set<Long>> entry : placedBlocks.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Set<Long> set = entry.getValue();
            if (set == null || set.isEmpty()) {
                continue;
            }
            snapshot.put(entry.getKey(), new ArrayList<>(set));
        }

        Runnable saveTask = () -> {
            java.io.File dataFolder = plugin.getDataFolder();
            if (dataFolder == null) {
                return;
            }
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                return;
            }

            java.io.File file = new java.io.File(dataFolder, PLACED_BLOCKS_FILE_NAME);
            FileConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, List<Long>> entry : snapshot.entrySet()) {
                cfg.set(PLACED_BLOCKS_ROOT + "." + entry.getKey().toString(), entry.getValue());
            }
            try {
                cfg.save(file);
            } catch (IOException ignored) {
            }
        };

        placedBlocksDirty = false;
        if (async) {
            SchedulerUtil.runTaskAsynchronously(plugin, saveTask);
        } else {
            saveTask.run();
        }
    }

    private static void schedulePlacedBlocksSave() {
        if (!placedBlocksLoaded) {
            return;
        }
        if (!placedBlocksDirty) {
            return;
        }
        if (placedBlocksSaveScheduled) {
            return;
        }
        Storage plugin = Storage.getStorage();
        if (plugin == null) {
            return;
        }
        placedBlocksSaveScheduled = true;
        SchedulerUtil.runTaskLater(plugin, () -> {
            placedBlocksSaveScheduled = false;
            savePlacedBlocks(true);
        }, SAVE_DELAY_TICKS);
    }

    private static long packBlockPos(int x, int y, int z) {
        long px = ((long) (x + COORD_OFFSET_26)) & 0x3FFFFFFL;
        long pz = ((long) (z + COORD_OFFSET_26)) & 0x3FFFFFFL;
        long py = ((long) (y + Y_OFFSET_12)) & 0xFFFL;
        return (px << 38) | (pz << 12) | py;
    }

    public static boolean isPersistPlacedBlock(@NotNull Block block) {
        if (!placedBlocksLoaded) {
            return false;
        }
        if (block.getWorld() == null) {
            return false;
        }
        Set<Long> set = placedBlocks.get(block.getWorld().getUID());
        if (set == null || set.isEmpty()) {
            return false;
        }
        return set.contains(packBlockPos(block.getX(), block.getY(), block.getZ()));
    }

    public static void markPersistPlacedBlock(@NotNull Block block) {
        if (!placedBlocksLoaded) {
            return;
        }
        if (block.getWorld() == null) {
            return;
        }
        UUID worldId = block.getWorld().getUID();
        Set<Long> set = placedBlocks.computeIfAbsent(worldId, k -> new HashSet<>());
        if (set.add(packBlockPos(block.getX(), block.getY(), block.getZ()))) {
            placedBlocksDirty = true;
            schedulePlacedBlocksSave();
        }
    }

    public static void unmarkPersistPlacedBlock(@NotNull Block block) {
        if (!placedBlocksLoaded) {
            return;
        }
        if (block.getWorld() == null) {
            return;
        }
        Set<Long> set = placedBlocks.get(block.getWorld().getUID());
        if (set == null || set.isEmpty()) {
            return;
        }
        if (set.remove(packBlockPos(block.getX(), block.getY(), block.getZ()))) {
            placedBlocksDirty = true;
            schedulePlacedBlocksSave();
        }
    }

    public static boolean hasPlayerBlock(@NotNull Player p, String material) {
        return playerdata.containsKey(storageKey(p) + "_" + material);
    }

    public static Integer getMaxStorageOverride(@NotNull Player player) {
        return maxOverrideData.get(player.getUniqueId());
    }

    public static void setMaxStorageOverride(@NotNull Player player,
                                             int maxStorage) {
        maxOverrideData.put(player.getUniqueId(), Math.max(0, maxStorage));
    }

    public static void clearMaxStorageOverride(@NotNull Player player) {
        maxOverrideData.remove(player.getUniqueId());
    }

    /**
     * Total capacity (all items together) of the storage this player uses: the configured/admin value or the
     * capacity of the purchased upgrade level, whichever is higher.
     */
    public static int getMaxBlock(Player p) {
        int base = File.getConfig().getInt("settings.default_max_storage", 100000);
        if (p == null) {
            return base;
        }
        base = playermaxdata.getOrDefault(p.getUniqueId(), base);
        return Math.max(base, StorageUpgrade.capacity(StorageSettings.getLevel(storageKey(p))));
    }

    /**
     * Sum of every item in the storage. long: many items with large amounts must not overflow an int.
     */
    public static long getTotalStored(@NotNull String key) {
        long total = 0;
        // blocksdata maps many blocks to one drop (STONE and COBBLESTONE -> COBBLESTONE;0): count each item once.
        for (String block : new HashSet<>(blocksdata.values())) {
            total += playerdata.getOrDefault(key + "_" + block, 0);
        }
        return total;
    }

    /**
     * Free capacity of another player's storage, online or not (their data must already be loaded).
     */
    public static int getFreeSpace(@NotNull String ownerName, @Nullable UUID ownerUuid) {
        String key = resolveStorageName(ownerName);
        Player online = ownerUuid == null ? null : Bukkit.getPlayer(ownerUuid);
        int max = online != null ? getMaxBlock(online)
                : Math.max(ownerUuid != null ? getMaxStorage(ownerUuid) : getMaxStorage(),
                StorageUpgrade.capacity(StorageSettings.getLevel(key)));
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) max - getTotalStored(key)));
    }

    public static int getTotalStoredInt(@NotNull Player p) {
        return (int) Math.min(Integer.MAX_VALUE, getTotalStored(p));
    }

    public static long getTotalStored(@NotNull Player p) {
        return getTotalStored(storageKey(p));
    }

    /**
     * Free capacity; 0 when the storage is full or already over the limit (old data is never cut).
     * Every add path must use this single check.
     */
    public static int getFreeSpace(@NotNull Player p) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) getMaxBlock(p) - getTotalStored(p)));
    }

    @Contract(" -> new")
    public static @NotNull List<String> getPluginBlocks() {
        return new ArrayList<>(blocksdata.values());
    }

    @Contract(" -> new")
    public static @NotNull List<String> getOrderedPluginBlocks() {
        Set<String> orderedBlocks = new LinkedHashSet<>();
        ConfigurationSection section = File.getConfig().getConfigurationSection(
                "blocks"
        );
        if (section == null) {
            return new ArrayList<>(orderedBlocks);
        }
        for (String block_break : section.getKeys(false)) {
            String item_drop = File.getConfig().getString("blocks." + block_break + ".drop");
            String item_drop_autosmelt = File.getConfig().getString(
                    "blocks." + block_break + ".drop_autosmelt"
            );

            addOrderedDrop(orderedBlocks, item_drop);
            addOrderedDrop(orderedBlocks, item_drop_autosmelt);
        }
        return new ArrayList<>(orderedBlocks);
    }

    private static void addOrderedDrop(@NotNull Set<String> orderedBlocks,
                                       String configuredDrop) {
        if (configuredDrop == null) {
            return;
        }
        if (!configuredDrop.contains(";")) {
            String material = configuredDrop + ";0";
            orderedBlocks.add(material);
            return;
        }

        String[] item_data = configuredDrop.split(";");
        if (IS_LEGACY) {
            String item_material = item_data[0] + ";" + item_data[1];
            orderedBlocks.add(item_material);
        } else {
            String material = item_data[0] + ";0";
            orderedBlocks.add(material);
        }
    }

    public static void addPluginBlocks(String material) {
        blocksdata.put(material, material);

    }

    public static @NotNull PlayerData getPlayerDatabase(@NotNull Player player) {

        PlayerData playerStats = Storage.db.getData(storageKey(player));

        if (playerStats == null) {
            boolean defaultAutoPickup = File.getConfig().getBoolean("settings.default_auto_pickup");
            playerStats = new PlayerData(storageKey(player), createNewData(), File.getConfig().getInt("settings.default_max_storage"), defaultAutoPickup);
            Storage.db.createTable(playerStats);
            toggle.put(player.getUniqueId(), defaultAutoPickup);
        } else {
            toggle.put(player.getUniqueId(), playerStats.autoPickup());
        }

        return playerStats;
    }

    public static boolean setItemAutoSell(@NotNull Player player,
                                          @NotNull String material,
                                          boolean enabled) {
        if (denySell(player)) {
            return isAutoSellEnabledForItem(player, material);
        }
        String playerName = storageKey(player);
        Set<String> set = autoSellItems.computeIfAbsent(playerName, k -> new HashSet<>());

        boolean changed;
        if (enabled) {
            changed = set.add(material);
        } else {
            changed = set.remove(material);
        }

        if (changed) {
            savePlayerData(player);
        }

        if (isAutoSellTriggerOnToggle()) {
            if (enabled && getPlayerBlock(player, material) > 0) {
                scheduleAutoSellIfNeeded(player, material);
            }
        }
        return isAutoSellEnabledForItem(player, material);
    }

    public static @NotNull Set<String> getEnabledAutoSellItems(@NotNull Player player) {
        Set<String> set = autoSellItems.get(storageKey(player));
        if (set == null || set.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(set);
    }

    public static int clearAutoSell(@NotNull Player player) {
        String playerName = storageKey(player);
        Set<String> current = autoSellItems.remove(playerName);
        int cleared = current == null ? 0 : current.size();
        if (cleared > 0) {
            savePlayerData(player);
        }

        String prefix = player.getUniqueId() + "_";
        synchronized (pendingAutoSell) {
            pendingAutoSell.removeIf(key -> key != null && key.startsWith(prefix));
        }
        synchronized (lastAutoSellAt) {
            lastAutoSellAt.keySet().removeIf(key -> key != null && key.startsWith(prefix));
        }

        return cleared;
    }

    private static @NotNull String createNewData() {
        StringBuilder mapAsString = new StringBuilder("{");
        for (String block : getPluginBlocks()) {
            mapAsString.append(block).append("=").append(0).append(", ");
        }
        if (mapAsString.length() > 1) {
            mapAsString.delete(mapAsString.length() - 2, mapAsString.length());
        }
        mapAsString.append("}");
        return mapAsString.toString();
    }

    private static void loadAutoSellItems(@NotNull String playerName,
                                          @NotNull String data) {
        autoSellItems.remove(playerName);
        int idx = data.indexOf(AUTO_SELL_DATA_PREFIX);
        if (idx < 0) {
            return;
        }

        int start = idx + AUTO_SELL_DATA_PREFIX.length();
        int end = data.indexOf(';', start);
        String enabledData = end >= 0 ? data.substring(start, end) : data.substring(start);
        if (enabledData == null || enabledData.trim().isEmpty()) {
            return;
        }

        Set<String> enabledItems = new HashSet<>();
        for (String raw : enabledData.split(",")) {
            if (raw == null) {
                continue;
            }
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (getPluginBlocks().contains(item)) {
                enabledItems.add(item);
            }
        }

        if (!enabledItems.isEmpty()) {
            autoSellItems.put(playerName, enabledItems);
        }
    }

    public static @NotNull String convertOfflineData(Player p) {
        StringBuilder mapAsString = new StringBuilder("{");
        for (String block : getPluginBlocks()) {
            if (playerdata.containsKey(storageKey(p) + "_" + block)) {
                mapAsString.append(block).append("=").append(getPlayerBlock(p, block)).append(", ");
            } else {
                mapAsString.append(block).append("=").append(0).append(", ");
            }
        }
        if (mapAsString.length() > 1) {
            mapAsString.delete(mapAsString.length() - 2, mapAsString.length());
        }
        mapAsString.append("}");
        mapAsString.append(StorageSettings.serialize(storageKey(p)));

        Set<String> disabledItems = disabledAutoPickupItems.get(storageKey(p));
        if (disabledItems != null && !disabledItems.isEmpty()) {
            StringBuilder disabledData = new StringBuilder();
            for (String key : getPluginBlocks()) {
                if (!disabledItems.contains(key)) {
                    continue;
                }
                if (disabledData.length() > 0) {
                    disabledData.append(",");
                }
                disabledData.append(key);
            }
            if (disabledData.length() > 0) {
                mapAsString.append(";autopickupoff:")
                        .append(disabledData);
            }
        }

        Set<String> enabledAutoSell = autoSellItems.get(storageKey(p));
        if (enabledAutoSell != null && !enabledAutoSell.isEmpty()) {
            StringBuilder enabledData = new StringBuilder();
            for (String key : getPluginBlocks()) {
                if (!enabledAutoSell.contains(key)) {
                    continue;
                }
                if (enabledData.length() > 0) {
                    enabledData.append(",");
                }
                enabledData.append(key);
            }
            if (enabledData.length() > 0) {
                mapAsString.append(AUTO_SELL_DATA_PREFIX)
                        .append(enabledData);
            }
        }

        mapAsString.append(TOGGLE_DATA_PREFIX)
                .append(getToggleStatus(p));

        Integer maxOverride = getMaxStorageOverride(p);
        if (maxOverride != null) {
            mapAsString.append(MAX_OVERRIDE_DATA_PREFIX)
                    .append(Math.max(0, maxOverride));
        }

        return mapAsString.toString();
    }

    public static @NotNull List<String> convertOnlineData(@NotNull String data) {
        String data_1 = data;
        int start = data.indexOf('{');
        int end = data.indexOf('}');
        if (start >= 0 && end > start) {
            data_1 = data.substring(start + 1, end);
        }
        data_1 = data_1.replace(" ", "");
        List<String> list = new ArrayList<>();
        List<String> testlist = new ArrayList<>();
        for (String blocklist : data_1.split(",")) {
            String[] block = blocklist.split("=");
            if (getPluginBlocks().contains(block[0])) {
                list.add(block[0] + ";" + block[1]);
                testlist.add(block[0]);
            }
        }
        for (String blocklist : getPluginBlocks()) {
            if (!testlist.contains(blocklist)) {
                list.add(blocklist + ";" + 0);
                testlist.add(blocklist);
            }
        }
        return list;
    }

    public static boolean isAutoPickupEnabledForItem(@NotNull Player player,
                                                     @NotNull String material) {
        if (!getToggleStatus(player)) {
            return false;
        }
        // An empty pickup filter means "everything"; a non-empty one lists the only items picked up.
        if (!StorageSettings.isFiltered(storageKey(player), material)) {
            return false;
        }
        Set<String> disabledItems = disabledAutoPickupItems.get(storageKey(player));
        if (disabledItems == null || disabledItems.isEmpty()) {
            return true;
        }
        return !disabledItems.contains(material);
    }

    public static boolean toggleItemAutoPickup(@NotNull Player player,
                                               @NotNull String material) {
        String playerName = storageKey(player);
        Set<String> disabledItems = disabledAutoPickupItems.computeIfAbsent(playerName, k -> new HashSet<>());

        if (disabledItems.contains(material)) {
            disabledItems.remove(material);
            savePlayerData(player);
            return true;
        }

        disabledItems.add(material);
        savePlayerData(player);
        return false;
    }

    private static void loadDisabledAutoPickupItems(@NotNull String playerName,
                                                    @NotNull String data) {
        disabledAutoPickupItems.remove(playerName);
        int idx = data.indexOf(";autopickupoff:");
        if (idx < 0) {
            return;
        }

        String disabledData = data.substring(idx + ";autopickupoff:"
                .length());
        if (disabledData.isEmpty()) {
            return;
        }

        Set<String> disabledItems = new HashSet<>();
        for (String raw : disabledData.split(",")) {
            if (raw == null) {
                continue;
            }
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (getPluginBlocks().contains(item)) {
                disabledItems.add(item);
            }
        }

        disabledAutoPickupItems.put(playerName, disabledItems);
    }

    public static void setBlock(@NotNull Player p, String material, int amount) {
        playerdata.put(storageKey(p) + "_" + material, amount);
    }

    public static void setBlock(Player p, @NotNull List<String> list) {
        list.forEach(block -> {
            String[] block_data = block.split(";");
            String material = block_data[0] + ";" + block_data[1];
            int amount = Number.getInteger(block_data[2]);
            setBlock(p, material, amount);
        });
    }

    public static boolean addBlockAmount(Player p, String material, int amount) {
        return addBlockAmount(p, material, amount, true);
    }

    public static boolean addBlockAmount(Player p, String material, int amount, boolean fireEvent) {
        if (amount <= 0 || !blocksdata.containsKey(material)) return false;

        int currentAmount = getPlayerBlock(p, material);
        int availableSpace = getFreeSpace(p);
        if (availableSpace <= 0) return false;

        int amountToAdd = Math.min(amount, availableSpace);
        if (amountToAdd <= 0) return false;

        if (fireEvent) {
            if (!StorageHookAPI.callBeforeDeposit(p, material, amountToAdd)) return false;

            StorageDepositEvent event = new StorageDepositEvent(p, material, amountToAdd);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            amountToAdd = Math.min(event.getAmount(), availableSpace);
        }

        playerdata.put(storageKey(p) + "_" + material, currentAmount + amountToAdd);
        StorageSettings.noteAdded(storageKey(p), material);

        if (fireEvent) {
            StorageHookAPI.callAfterDeposit(p, material, amountToAdd);
        }

        if (isAutoSellTriggerOnDeposit()) {
            scheduleAutoSellIfNeeded(p, material);
        }
        return true;
    }

    private static boolean isAutoSellTriggerOnDeposit() {
        String mode = File.getConfig().getString("auto_sell.trigger", "");
        if (mode != null && !mode.trim().isEmpty()) {
            switch (mode.trim().toUpperCase()) {
                case "BOTH":
                case "DEPOSIT_ONLY":
                    return true;
                case "TOGGLE_ONLY":
                case "DISABLED":
                    return false;
                default:
                    break;
            }
        }
        return File.getConfig().getBoolean("auto_sell.trigger_on_deposit", true);
    }

    private static boolean isAutoSellTriggerOnToggle() {
        String mode = File.getConfig().getString("auto_sell.trigger", "");
        if (mode != null && !mode.trim().isEmpty()) {
            switch (mode.trim().toUpperCase()) {
                case "BOTH":
                case "TOGGLE_ONLY":
                    return true;
                case "DEPOSIT_ONLY":
                case "DISABLED":
                    return false;
                default:
                    break;
            }
        }
        return File.getConfig().getBoolean("auto_sell.trigger_on_toggle", true);
    }

    public static void scheduleAutoSellOnJoin(@NotNull Player player) {
        if (!isAutoSellSystemEnabled()) {
            return;
        }
        if (!isAutoSellTriggerOnToggle()) {
            return;
        }

        Set<String> enabled = autoSellItems.get(storageKey(player));
        if (enabled == null || enabled.isEmpty()) {
            return;
        }

        for (String material : new HashSet<>(enabled)) {
            if (material == null || material.trim().isEmpty()) {
                continue;
            }
            if (getPlayerBlock(player, material) <= 0) {
                continue;
            }
            scheduleAutoSellIfNeeded(player, material);
        }
    }

    public static boolean isAutoSellSystemEnabled() {
        return File.getConfig().getBoolean("auto_sell.enabled", true);
    }

    public static boolean isAutoSellEnabledForItem(@NotNull Player player,
                                                   @NotNull String material) {
        if (!isAutoSellSystemEnabled()) {
            return false;
        }
        Set<String> enabled = autoSellItems.get(storageKey(player));
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        return enabled.contains(material);
    }

    public static boolean toggleItemAutoSell(@NotNull Player player,
                                             @NotNull String material) {
        if (denySell(player)) {
            return isAutoSellEnabledForItem(player, material);
        }
        String playerName = storageKey(player);
        Set<String> enabled = autoSellItems.computeIfAbsent(playerName, k -> new HashSet<>());

        boolean next;
        if (enabled.contains(material)) {
            enabled.remove(material);
            next = false;
        } else {
            enabled.add(material);
            next = true;
        }
        savePlayerData(player);

        if (isAutoSellTriggerOnToggle()) {
            if (next && getPlayerBlock(player, material) > 0) {
                scheduleAutoSellIfNeeded(player, material);
            }
        }
        return next;
    }

    private static void scheduleAutoSellIfNeeded(@NotNull Player player,
                                                 @NotNull String material) {
        if (!isAutoSellEnabledForItem(player, material) || !IslandStorageManager.canSell(player)) {
            return;
        }

        String key = player.getUniqueId() + "_" + material;
        synchronized (pendingAutoSell) {
            if (!pendingAutoSell.add(key)) {
                return;
            }
        }

        long delayTicks = resolveAutoSellDelayTicks(player);
        long delayMillis = Math.max(50L, delayTicks * 50L);
        long now = System.currentTimeMillis();
        long earliest;
        synchronized (lastAutoSellAt) {
            long last = lastAutoSellAt.getOrDefault(key, 0L);
            earliest = last <= 0L ? now : Math.max(now, last + delayMillis);
        }

        long scheduleDelayMillis = Math.max(0L, earliest - now);
        long scheduleDelayTicks = Math.max(1L, (long) Math.ceil(scheduleDelayMillis / 50D));
        SchedulerUtil.runTaskLater(Storage.getStorage(), player, () -> {
            synchronized (pendingAutoSell) {
                pendingAutoSell.remove(key);
            }

            if (!player.isOnline()) {
                return;
            }

            if (!isAutoSellEnabledForItem(player, material)) {
                return;
            }

            if (getPlayerBlock(player, material) <= 0) {
                return;
            }

            new net.danh.storage.Action.Sell(player, material, -1).doAction();

            synchronized (lastAutoSellAt) {
                lastAutoSellAt.put(key, System.currentTimeMillis());
            }
        }, scheduleDelayTicks);
    }

    private static long resolveAutoSellDelayTicks(@NotNull Player player) {
        double delaySeconds = File.getConfig().getDouble("auto_sell.default_delay", 5D);
        delaySeconds = applyAutoSellDelayPermission(player, delaySeconds);
        if (delaySeconds < 0D) {
            delaySeconds = 0D;
        }
        long ticks = (long) Math.ceil(delaySeconds * 20D);
        return Math.max(1L, ticks);
    }

    private static double applyAutoSellDelayPermission(@NotNull Player player,
                                                       double currentSeconds) {
        double best = currentSeconds;
        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            if (pai == null || !pai.getValue()) {
                continue;
            }
            String perm = pai.getPermission();
            if (perm == null) {
                continue;
            }
            if (!perm.startsWith("storage.autosell.delay.")) {
                continue;
            }
            String raw = perm.substring("storage.autosell.delay.".length()).trim();
            if (raw.isEmpty()) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (value < 0D) {
                continue;
            }
            best = Math.min(best, value);
        }
        return best;
    }

    public static int getPermissionMaxStorage(@NotNull Player player) {
        int defaultMax = File.getConfig().getInt(
                "settings.default_max_storage",
                100000
        );

        if (player.isOp() || player.hasPermission("storage.admin")) {
            return defaultMax;
        }

        int bestLimit = -1;
        int bestPriority = Integer.MIN_VALUE;

        // Numeric permission: storage.storage.max.<n>
        for (PermissionAttachmentInfo pai :
                player.getEffectivePermissions()) {
            String perm = pai.getPermission();
            if (perm == null || !pai.getValue()) {
                continue;
            }
            if (!perm.startsWith(STORAGE_LIMIT_PERMISSION_MAX_PREFIX)) {
                continue;
            }

            String numberPart = perm.substring(
                    STORAGE_LIMIT_PERMISSION_MAX_PREFIX.length()
            );
            int value;
            try {
                value = Integer.parseInt(numberPart);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (value > bestLimit) {
                bestLimit = value;
            }
        }

        // Rank permission: storage.storage.<rank>
        ConfigurationSection section =
                File.getConfig().getConfigurationSection(
                        "storage_permissions"
                );
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key == null || key.trim().isEmpty()) {
                    continue;
                }
                String permission = STORAGE_LIMIT_PERMISSION_PREFIX + key;
                if (!player.hasPermission(permission)) {
                    continue;
                }

                int limit = section.getInt(key + ".max_storage", -1);
                int priority = section.getInt(key + ".priority", 0);
                if (limit < 0) {
                    continue;
                }
                if (priority > bestPriority) {
                    bestPriority = priority;
                    bestLimit = limit;
                }
            }
        }

        return bestLimit >= 0 ? bestLimit : defaultMax;
    }

    public static boolean removeBlockAmount(Player p, String material,
                                            int amount) {
        return removeBlockAmount(p, material, amount, true);
    }

    public static boolean removeBlockAmount(Player p, String material,
                                            int amount, boolean fireEvent) {
        if (amount <= 0) return false;

        int oldData = getPlayerBlock(p, material);
        if (oldData <= 0) return false;

        int amountToRemove = amount;

        if (fireEvent) {
            if (!StorageHookAPI.callBeforeWithdraw(p, material,
                    amountToRemove)) return false;

            StorageWithdrawEvent event = new StorageWithdrawEvent(p, material,
                    amountToRemove);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            amountToRemove = event.getAmount();
        }

        playerdata.replace(storageKey(p) + "_" + material,
                Math.max(oldData - amountToRemove, 0));

        if (fireEvent) {
            StorageHookAPI.callAfterWithdraw(p, material, amountToRemove);
        }
        return true;
    }

    public static void loadPlayerData(Player p) {
        String key = IslandStorageManager.resolveKey(p);
        // Another online member already holds the live island data; reloading would overwrite unsaved changes.
        boolean firstLoad = !isKeyInUse(key, p.getUniqueId());
        activeKey.put(p.getUniqueId(), key);
        PlayerData playerData = getPlayerDatabase(p);
        SpecialMaterialManager.loadPlayerData(p, playerData.data());
        List<String> list = convertOnlineData(playerData.data());

        int databaseMax = Math.max(0, playerData.max());
        int permissionMax = Math.max(0, getPermissionMaxStorage(p));
        Integer overrideMax = null;

        if (firstLoad) {
            setBlock(p, list);
            StorageSettings.load(key, playerData.data());
        }

        String rawData = playerData.data();
        if (rawData != null && !rawData.isEmpty()) {
            if (firstLoad) {
                loadDisabledAutoPickupItems(key, rawData);
                loadAutoSellItems(key, rawData);
            }
            Integer parsedMaxOverride = parseMaxOverride(rawData);
            if (parsedMaxOverride != null) {
                overrideMax = Math.max(0, parsedMaxOverride);
                setMaxStorageOverride(p, overrideMax);
            } else {
                clearMaxStorageOverride(p);
            }
            Boolean toggleStatus = parseToggleStatus(rawData);
            if (toggleStatus != null) {
                toggle.put(p.getUniqueId(), toggleStatus);
            }
        }

        if (!toggle.containsKey(p.getUniqueId())) {
            toggle.put(p.getUniqueId(), playerData.autoPickup());
        }

        int resolvedMax;
        if (overrideMax != null) {
            resolvedMax = overrideMax;
        } else {
            resolvedMax = File.resolveMaxStorage(
                    File.getConfig(),
                    "settings.max_storage_mode",
                    databaseMax,
                    permissionMax
            );
        }
        playermaxdata.put(p.getUniqueId(), Math.max(0, resolvedMax));
    }

    public static void savePlayerData(@NotNull Player p) {
        boolean autoPickup = getToggleStatus(p);
        PlayerData existing = Storage.db.getData(storageKey(p));
        int maxToSave;
        if (existing != null) {
            maxToSave = existing.max();
        } else {
            maxToSave = File.getConfig().getInt(
                    "settings.default_max_storage",
                    100000
            );
        }
        PlayerData playerData = new PlayerData(
                storageKey(p),
                SpecialMaterialManager.mergePlayerData(p,
                        convertOfflineData(p), existing == null ? null : existing.data()),
                Math.max(0, maxToSave),
                autoPickup
        );
        if (existing == null) {
            Storage.db.createTable(playerData);
        } else {
            Storage.db.updateTable(playerData);
        }
    }

    public static void cleanupPlayerData(@NotNull Player p) {
        UUID playerId = p.getUniqueId();
        toggle.remove(playerId);
        playermaxdata.remove(playerId);
        clearMaxStorageOverride(p);

        String playerName = storageKey(p);
        activeKey.remove(playerId);
        // Island data stays in memory while another member is still online.
        if (!isKeyInUse(playerName, playerId)) {
            playerdata.entrySet().removeIf(entry -> entry.getKey().startsWith(playerName + "_"));
            disabledAutoPickupItems.remove(playerName);
            autoSellItems.remove(playerName);
            StorageSettings.remove(playerName);
        }

        String prefix = p.getUniqueId() + "_";
        synchronized (pendingAutoSell) {
            pendingAutoSell.removeIf(key -> key != null && key.startsWith(prefix));
        }
        synchronized (lastAutoSellAt) {
            lastAutoSellAt.keySet().removeIf(key -> key != null && key.startsWith(prefix));
        }
    }

    private static Integer parseMaxOverride(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        int idx = data.indexOf(MAX_OVERRIDE_DATA_PREFIX);
        if (idx < 0) {
            return null;
        }
        int start = idx + MAX_OVERRIDE_DATA_PREFIX.length();
        int end = data.indexOf(';', start);
        String raw = end >= 0 ? data.substring(start, end) : data.substring(start);
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean parseToggleStatus(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        int idx = data.indexOf(TOGGLE_DATA_PREFIX);
        if (idx < 0) {
            return null;
        }
        int start = idx + TOGGLE_DATA_PREFIX.length();
        int end = data.indexOf(';', start);
        String raw = end >= 0 ? data.substring(start, end) : data.substring(start);
        raw = raw.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return null;
        }
        return "true".equals(raw);
    }

    public static boolean getToggleStatus(@NotNull Player p) {
        UUID playerId = p.getUniqueId();
        Boolean status = toggle.get(playerId);
        if (status == null) {
            PlayerData playerData = getPlayerDatabase(p);
            Boolean parsed = parseToggleStatus(playerData.data());
            status = parsed != null ? parsed : playerData.autoPickup();
            toggle.put(playerId, status);
        }
        return status;
    }

    public static void setToggleStatus(@NotNull Player p, boolean enabled) {
        setToggleStatus(p, enabled, true);
    }

    public static void setToggleStatus(@NotNull Player p, boolean enabled, boolean fireEvent) {
        if (fireEvent) {
            if (!StorageHookAPI.callBeforeToggle(p, enabled)) return;

            StorageToggleEvent event = new StorageToggleEvent(p, enabled);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;
            enabled = event.getNewState();
        }

        toggle.put(p.getUniqueId(), enabled);

        if (fireEvent) {
            StorageHookAPI.callAfterToggle(p, enabled);
        }
    }

    public static int getMaxTransferableAmount(@NotNull Player sender, @NotNull Player receiver, @NotNull String material) {
        int senderAmount = getPlayerBlock(sender, material);
        if (senderAmount <= 0) {
            return 0;
        }

        int availableSpace = getFreeSpace(receiver);

        return Math.min(senderAmount, Math.max(0, availableSpace));
    }

    public static @NotNull Map<String, Integer> calculateOptimalMultiTransfer(@NotNull Player sender, @NotNull Player receiver, @NotNull Map<String, Integer> requestedAmounts) {
        Map<String, Integer> optimizedAmounts = new HashMap<>();

        for (Map.Entry<String, Integer> entry : requestedAmounts.entrySet()) {
            String material = entry.getKey();
            int requestedAmount = entry.getValue();

            int maxTransferable = getMaxTransferableAmount(sender, receiver, material);

            if (maxTransferable > 0) {
                int optimalAmount = Math.min(requestedAmount, maxTransferable);
                optimizedAmounts.put(material, optimalAmount);
            }
        }

        return optimizedAmounts;
    }

    public static String getDrop(@NotNull Block block) {
        return getDrop(block, false);
    }

    public static String getDrop(@NotNull Block block, boolean preferAutoSmelt) {
        String blockType = block.getType().name();
        if (blockType.equals("GLOWING_REDSTONE_ORE")) {
            blockType = "REDSTONE_ORE";
        }
        String key = blockType + ";" + (IS_LEGACY ? block.getData() : "0");

        if (preferAutoSmelt) {
            String autoDrop = blocksdropAutoSmelt.get(key);
            if (autoDrop != null) {
                return autoDrop;
            }
            autoDrop = blocksdropAutoSmelt.get(blockType);
            if (autoDrop != null) {
                return autoDrop;
            }
            if (!IS_LEGACY) {
                String altKey = blockType + ";0";
                autoDrop = blocksdropAutoSmelt.get(altKey);
                if (autoDrop != null) {
                    return autoDrop;
                }
            }
        }

        String drop = blocksdrop.get(key);
        if (drop != null) {
            return drop;
        }
        drop = blocksdrop.get(blockType);
        if (drop != null) {
            return drop;
        }
        if (!IS_LEGACY) {
            String altKey = blockType + ";0";
            drop = blocksdrop.get(altKey);
            return drop;
        }
        return null;
    }

    public static void loadBlocks() {
        if (!blocksdrop.isEmpty()) {
            blocksdrop.clear();
        }
        if (!blocksdropAutoSmelt.isEmpty()) {
            blocksdropAutoSmelt.clear();
        }
        if (!blocksdata.isEmpty()) {
            blocksdata.clear();
        }
        if (!inventoryDropLookup.isEmpty()) {
            inventoryDropLookup.clear();
        }
        ConfigurationSection section = File.getConfig().getConfigurationSection(
                "blocks"
        );
        if (section == null) {
            return;
        }
        for (String block_break : section.getKeys(false)) {
            String item_drop = File.getConfig().getString("blocks." + block_break + ".drop");
            String item_drop_autosmelt = File.getConfig().getString(
                    "blocks." + block_break + ".drop_autosmelt"
            );
            if (item_drop != null) {
                if (!item_drop.contains(";")) {
                    String normalizedDrop = item_drop + ";0";
                    addPluginBlocks(normalizedDrop);
                    blocksdrop.put(block_break, normalizedDrop);
                    addInventoryLookupEntry(normalizedDrop);
                } else {
                    if (IS_LEGACY) {
                        String[] item_data = item_drop.split(";");
                        String item_material = item_data[0] + ";" + item_data[1];
                        addPluginBlocks(item_material);
                        blocksdrop.put(block_break, item_material);
                        addInventoryLookupEntry(item_material);
                    } else {
                        String[] item_data = item_drop.split(";");
                        String normalizedDrop = item_data[0] + ";0";
                        addPluginBlocks(normalizedDrop);
                        blocksdrop.put(block_break, normalizedDrop);
                        addInventoryLookupEntry(normalizedDrop);
                    }
                }
            }

            if (item_drop_autosmelt != null) {
                if (!item_drop_autosmelt.contains(";")) {
                    String normalizedDrop = item_drop_autosmelt + ";0";
                    addPluginBlocks(normalizedDrop);
                    blocksdropAutoSmelt.put(block_break, normalizedDrop);
                    addInventoryLookupEntry(normalizedDrop);
                } else {
                    if (IS_LEGACY) {
                        String[] item_data = item_drop_autosmelt.split(";");
                        String item_material = item_data[0] + ";" + item_data[1];
                        addPluginBlocks(item_material);
                        blocksdropAutoSmelt.put(block_break, item_material);
                        addInventoryLookupEntry(item_material);
                    } else {
                        String[] item_data = item_drop_autosmelt.split(";");
                        String normalizedDrop = item_data[0] + ";0";
                        addPluginBlocks(normalizedDrop);
                        blocksdropAutoSmelt.put(block_break, normalizedDrop);
                        addInventoryLookupEntry(normalizedDrop);
                    }
                }
            }
        }
    }

    private static void addInventoryLookupEntry(String dropKey) {
        if (dropKey == null || dropKey.isEmpty()) {
            return;
        }

        String materialName = dropKey;
        short dataValue = 0;
        if (dropKey.contains(";")) {
            String[] parts = dropKey.split(";", 2);
            materialName = parts[0];
            if (IS_LEGACY && parts.length > 1) {
                dataValue = (short) Number.getInteger(parts[1]);
            }
        }

        Material material = MaterialUtils.matchMaterial(materialName);
        if (material == null) {
            return;
        }

        short durability = IS_LEGACY ? dataValue : 0;
        inventoryDropLookup.put(new InvLookupKey(material, durability), dropKey);
    }

    public static boolean checkBreak(@NotNull Block block) {
        String blockType = block.getType().name();
        if (blockType.equals("GLOWING_REDSTONE_ORE")) {
            blockType = "REDSTONE_ORE";
        }
        String dataKey = blockType + ";" + (IS_LEGACY ? block.getData() : "0");
        if (blocksdrop.containsKey(dataKey)) {
            return true;
        }
        if (blocksdropAutoSmelt.containsKey(dataKey)) {
            return true;
        }
        if (blocksdrop.containsKey(blockType)) {
            return true;
        }
        if (blocksdropAutoSmelt.containsKey(blockType)) {
            return true;
        }
        if (!IS_LEGACY && blocksdrop.containsKey(blockType + ";0")) {
            return true;
        }
        return !IS_LEGACY && blocksdropAutoSmelt.containsKey(blockType + ";0");
    }

    public static String normalizeMaterial(String material) {
        if (material == null || material.isEmpty()) {
            return material;
        }

        if (material.contains(";")) {
            return material;
        }

        if (material.contains(":")) {
            return material.replace(":", ";");
        }

        return material + ";0";
    }

    public static String getMaterial(String material) {
        String material_data = material.replace(":", ";");
        if (NMS.isVersionGreaterThanOrEqualTo(13)) {
            return material_data.split(";")[0] + ";0";
        } else {
            if (Number.getInteger(material_data.split(";")[1]) > 0) {
                return material;
            } else {
                return material_data.split(";")[0] + ";0";
            }
        }
    }

    public static String getItemStackDrop(ItemStack item) {
        if (item == null) {
            return null;
        }
        short durability = 0;
        if (IS_LEGACY) {
            durability = item.getDurability();
        }
        return inventoryDropLookup.get(new InvLookupKey(item.getType(), durability));
    }

    public static boolean isBefore9() {
        return IS_BEFORE_9;
    }

    public static int getPlayerBlock(@NotNull String playerName, @NotNull String material) {
        return playerdata.getOrDefault(resolveStorageName(playerName) + "_" + material, 0);
    }

    public static int getMaxStorage() {
        return File.getConfig().getInt("settings.default_max_storage", 100000);
    }

    public static int getMaxStorage(@NotNull UUID playerId) {
        return playermaxdata.getOrDefault(playerId, getMaxStorage());
    }

    public static boolean loadOfflinePlayerData(@NotNull String playerName) {
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            return true;
        }

        String storageName = resolveStorageName(playerName);
        for (String key : playerdata.keySet()) {
            if (key.startsWith(storageName + "_")) {
                return true;
            }
        }

        PlayerData data = Storage.db.getData(storageName);
        if (data == null) {
            return false;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer != null) {
            UUID offlineId = offlinePlayer.getUniqueId();
            if (offlineId != null) {
                playermaxdata.put(offlineId, Math.max(0, data.max()));
            }
        }

        String rawData = data.data();
        if (rawData != null && !rawData.isEmpty()) {
            loadDisabledAutoPickupItems(storageName, rawData);
        }
        StorageSettings.load(storageName, rawData);

        List<String> list = convertOnlineData(data.data());
        for (String block : list) {
            String[] block_data = block.split(";");
            if (block_data.length >= 3) {
                String material = block_data[0] + ";" + block_data[1];
                int amount = Number.getInteger(block_data[2]);
                playerdata.put(storageName + "_" + material, amount);
            }
        }

        return true;
    }

    public static void cleanupOfflinePlayerData(@NotNull String playerName) {
        // Don't cleanup if player is online
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            return;
        }

        String storageName = resolveStorageName(playerName);
        if (isKeyInUse(storageName, null)) {
            return;
        }

        playerdata.entrySet().removeIf(entry -> entry.getKey().startsWith(storageName + "_"));
        disabledAutoPickupItems.remove(storageName);
        autoSellItems.remove(storageName);
        StorageSettings.remove(storageName);
    }

    public static int getPlayerItem(@NotNull String playerName, @NotNull String material) {
        return getPlayerBlock(playerName, material);
    }

    public static void addItemAmount(@NotNull String playerName, @NotNull String material, int amount) {
        if (amount <= 0) return;
        int current = getPlayerBlock(playerName, material);
        playerdata.put(resolveStorageName(playerName) + "_" + material, current + amount);
        StorageSettings.noteAdded(resolveStorageName(playerName), material);
    }

    public static void removeItemAmount(@NotNull String playerName, @NotNull String material, int amount) {
        if (amount <= 0) return;
        int current = getPlayerBlock(playerName, material);
        playerdata.put(resolveStorageName(playerName) + "_" + material, Math.max(0, current - amount));
    }

    private record InvLookupKey(Material material, short durability) {

        private InvLookupKey(@NotNull Material material, short durability) {
            this.material = material;
            this.durability = durability;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof InvLookupKey that)) {
                return false;
            }
            return durability == that.durability && material == that.material;
        }

        @Override
        public int hashCode() {
            int result = material.hashCode();
            result = 31 * result + durability;
            return result;
        }
    }

}
