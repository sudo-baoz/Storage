package net.danh.storage.Listeners;

import com.cryptomorin.xseries.XEnchantment;
import net.danh.storage.Enchant.HasteEnchant;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Enchant.MultiplierEnchant;
import net.danh.storage.Enchant.TNTEnchant;
import net.danh.storage.Enchant.VeinMinerEnchant;
import net.danh.storage.Manager.EnchantManager;
import net.danh.storage.Manager.Event.EventManager;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.SpecialMaterial.SpecialMaterialManager;
import net.danh.storage.Manager.StorageFullNotificationManager;
import net.danh.storage.NMS.NMSAssistant;
import net.danh.storage.Storage;
import net.danh.storage.Utils.AutoPickupCache;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.NotificationQueue;
import net.danh.storage.Utils.Number;
import net.danh.storage.WorldGuard.WorldGuard;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.MetadataValue;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BlockBreak implements Listener {

    private static final long MMOITEMS_CAPTURE_TTL_MS = 2000L;
    private static final long CLEANUP_INTERVAL_MS = 1000L;
    private static final boolean CAN_DISABLE_DROPS = new NMSAssistant().isVersionGreaterThanOrEqualTo(12);

    private final Map<MmoitemsCaptureKey, MmoitemsCapture> mmoitemsCapture = new HashMap<>();
    private long lastCleanupMs = 0L;

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBreakCapture(@NotNull BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block block = e.getBlock();
        boolean breakable = MineManager.checkBreak(block);
        if (!breakable) {
            return;
        }
        if (!MineManager.getToggleStatus(p)) {
            return;
        }
        if (Storage.isWorldGuardInstalled()) {
            if (!WorldGuard.handleForLocation(p, block.getLocation())) {
                return;
            }
        }
        boolean preventRebreak = AutoPickupCache.isPreventRebreak();
        boolean placedBlock = isPlacedBlock(block);
        if (preventRebreak && !placedBlock) {
            placedBlock = MineManager.isPersistPlacedBlock(block);
        }
        if (preventRebreak && placedBlock) {
            MineManager.unmarkPersistPlacedBlock(block);
            return;
        }
        if (AutoPickupCache.isStorageWorldBlacklisted(p.getWorld().getName())) {
            return;
        }

        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isMmoitemsAutoSmeltTool(tool)) {
            return;
        }
        String drop = MineManager.getDrop(block, true);
        if (drop == null) {
            return;
        }
        if (!MineManager.isAutoPickupEnabledForItem(p, drop)) {
            return;
        }

        maybeCleanupCaptures();
        mmoitemsCapture.put(new MmoitemsCaptureKey(
                        p.getUniqueId(),
                        block.getWorld().getUID(),
                        block.getX(),
                        block.getY(),
                        block.getZ()),
                new MmoitemsCapture(
                        block.getType().name(),
                        MineManager.isBefore9() ? (short) block.getData() : 0,
                        drop,
                        SpecialMaterialManager.getBlockKey(block),
                        System.currentTimeMillis()));
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onBreak(@NotNull BlockBreakEvent e) {
        LanguageManager.setContext(e.getPlayer());
        try {
            onBreakHandler(e);
        } finally {
            LanguageManager.clearContext();
        }
    }

    private void onBreakHandler(@NotNull BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block block = e.getBlock();
        boolean breakable = MineManager.checkBreak(block);
        boolean inv_full = (p.getInventory().firstEmpty() == -1);
        String specialSource = SpecialMaterialManager.getBlockKey(block);
        boolean specialStored = false;
        String specialDrop = MineManager.getDrop(block);
        boolean specialAutoPickup = MineManager.getToggleStatus(p) && specialDrop != null
                && MineManager.isAutoPickupEnabledForItem(p, specialDrop);
        if (Storage.isWorldGuardInstalled()) {
            if (!WorldGuard.handleForLocation(p, block.getLocation())) {
                return;
            }
        }
        boolean preventRebreak = AutoPickupCache.isPreventRebreak();
        boolean placedBlock = isPlacedBlock(block);
        if (preventRebreak && !placedBlock) {
            placedBlock = MineManager.isPersistPlacedBlock(block);
        }
        if (preventRebreak && placedBlock) {
            MineManager.unmarkPersistPlacedBlock(block);
            return;
        }
        if (AutoPickupCache.isStorageWorldBlacklisted(p.getWorld().getName())) {
            return;
        }

        if (e.isCancelled()) {
            if (handleMmoitemsAutoSmeltCancelledBreak(p, block)) {
                return;
            }
            return;
        }

        if (MineManager.getToggleStatus(p) && breakable) {
            if (inv_full) {
                processInventoryItems(p);
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            boolean preferAutoSmelt = isMmoitemsAutoSmeltTool(hand);
            String drop = MineManager.getDrop(block, preferAutoSmelt);
            if (drop != null) {
                if (!MineManager.isAutoPickupEnabledForItem(p, drop)) {
                    return;
                }
                // Every vanilla drop of the block, sorted by what the storage does with it. Items the storage
                // doesn't take must still reach the world, because setDropItems(false) removes all of them.
                List<ItemStack> natural = collectDrops(block, hand);
                int fromDrops = 0;
                Map<String, ItemStack> extras = new LinkedHashMap<>();
                List<ItemStack> keep = new ArrayList<>();
                ItemStack primaryTemplate = null;
                for (ItemStack stack : natural) {
                    String key = MineManager.getItemStackDrop(stack);
                    if (drop.equals(key)) {
                        fromDrops += stack.getAmount();
                        if (primaryTemplate == null) primaryTemplate = stack.clone();
                    } else if (key != null && !preferAutoSmelt && !stack.hasItemMeta()) {
                        extras.merge(key, stack.clone(), (a, b) -> {
                            a.setAmount(a.getAmount() + b.getAmount());
                            return a;
                        });
                    } else {
                        keep.add(stack);
                    }
                }
                if (fromDrops == 0) {
                    // Config maps this block to an item it doesn't vanilla-drop: keep the original behaviour.
                    extras.clear();
                    keep.clear();
                }
                int baseAmount = fromDrops > 0 ? fromDrops : getDropAmount(block, hand);
                int amount;
                Enchantment fortune = XEnchantment.FORTUNE.get();
                if (hand == null || hand.getType().name().equals("AIR") || hand.getAmount() <= 0 || fortune == null
                        || !hand.containsEnchantment(fortune)) {
                    amount = baseAmount;
                } else {
                    if (AutoPickupCache.isFortuneWhitelisted(block.getType().name())) {
                        int base = baseAmount;
                        amount = Number.getRandomInteger(base,
                                base + hand.getEnchantmentLevel(fortune) + 2);
                    } else {
                        amount = baseAmount;
                    }
                }

                // Apply multiplier enchant if present
                if (hand != null && !hand.getType().name().equals("AIR") && hand.getAmount() > 0
                        && EnchantManager.hasEnchant(hand, "multiplier")) {
                    int multiplierLevel = EnchantManager.getEnchantLevel(hand, "multiplier");
                    amount = MultiplierEnchant.calculateMultipliedAmount(p, amount, multiplierLevel);
                }

                int bonusAmount = 0;
                if (!placedBlock) {
                    bonusAmount = EventManager.calculateDoubleDropBonus(amount);
                }
                int totalAmount = amount + bonusAmount;

                int storedBefore = MineManager.getPlayerBlock(p, drop);
                boolean stored = MineManager.addBlockAmount(p, drop, totalAmount);
                specialStored = stored;
                if (stored) {
                    EventManager.onPlayerMine(p, drop, amount);
                    boolean actionBarEnabled = AutoPickupCache.isStorageActionBarEnabled();
                    boolean titleEnabled = AutoPickupCache.isStorageTitleEnabled();
                    if (actionBarEnabled || titleEnabled) {
                        String name = File.getConfig().getString("items." + drop);
                        String itemName = name != null ? name : drop.replace("_", " ");
                        String suffix = bonusAmount > 0 ? " (+" + bonusAmount + " bonus)" : "";
                        int newStoredAmount = MineManager.getTotalStoredInt(p);
                        int maxStorage = MineManager.getMaxBlock(p);
                        NotificationQueue.add(p, NotificationQueue.TYPE_STORAGE, drop, itemName,
                                totalAmount, suffix, newStoredAmount, maxStorage,
                                actionBarEnabled, titleEnabled);
                    }

                    if (CAN_DISABLE_DROPS) {
                        e.setDropItems(false);
                        Location where = block.getLocation().add(0.5, 0.5, 0.5);
                        // A nearly full storage only takes what fits; the rest must stay in the world.
                        int taken = Math.max(0, MineManager.getPlayerBlock(p, drop) - storedBefore);
                        if (taken < amount && primaryTemplate != null) {
                            dropAmount(where, primaryTemplate, amount - taken);
                        }
                        for (Map.Entry<String, ItemStack> extra : extras.entrySet()) {
                            storeExtraDrop(p, where, extra.getKey(), extra.getValue());
                        }
                        for (ItemStack stack : keep) {
                            dropAmount(where, stack, stack.getAmount());
                        }
                    }
                } else {
                    StorageFullNotificationManager.sendStorageFullNotification(p);
                }
            }
        }

        // Roll the original block once, before an enchant can change it to AIR.
        if (breakable) {
            SpecialMaterialManager.checkSpecialMaterialDrop(p, block, specialSource, block.getLocation(),
                    null, specialStored, specialAutoPickup);
        }

        // Trigger enchants regardless of autopickup status
        if (breakable) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().name().equals("AIR") && hand.getAmount() > 0) {
                if (EnchantManager.hasEnchant(hand, "tnt")) {
                    int enchantLevel = EnchantManager.getEnchantLevel(hand, "tnt");
                    TNTEnchant.triggerExplosion(p, block.getLocation(), enchantLevel);
                }

                if (EnchantManager.hasEnchant(hand, "haste")) {
                    int enchantLevel = EnchantManager.getEnchantLevel(hand, "haste");
                    HasteEnchant.triggerHaste(p, enchantLevel);
                }

                if (EnchantManager.hasEnchant(hand, "veinminer")) {
                    int enchantLevel = EnchantManager.getEnchantLevel(hand, "veinminer");
                    VeinMinerEnchant.triggerVeinMiner(p, block.getLocation(), enchantLevel);
                }
            }
        }

    }

    private void processInventoryItems(Player player) {
        final PlayerInventory inv = player.getInventory();
        final ItemStack[] items = inv.getContents();
        boolean inventoryChanged = false;

        for (int i = 0; i < items.length; i++) {
            final ItemStack itemStack = items[i];
            if (itemStack != null) {
                String drop = MineManager.getItemStackDrop(itemStack);
                if (drop != null) {
                    int amount = itemStack.getAmount();
                    if (MineManager.addBlockAmount(player, drop, amount)) {
                        items[i] = null;
                        inventoryChanged = true;
                    }
                }
            }
        }

        if (inventoryChanged) {
            inv.setContents(items);
            if (MineManager.isBefore9()) {
                player.updateInventory();
            }
        }
    }

    public void removeItems(Player player, ItemStack itemStack, long amount) {
        final PlayerInventory inv = player.getInventory();
        final ItemStack[] items = inv.getContents();
        int c = 0;
        for (int i = 0; i < items.length; ++i) {
            final ItemStack is = items[i];
            if (is != null) {
                if (itemStack != null) {
                    if (is.isSimilar(itemStack)) {
                        if (c + is.getAmount() > amount) {
                            final long canDelete = amount - c;
                            is.setAmount((int) (is.getAmount() - canDelete));
                            items[i] = is;
                            break;
                        }
                        c += is.getAmount();
                        items[i] = null;
                    }
                }
            }
        }
        inv.setContents(items);
        if (MineManager.isBefore9()) {
            player.updateInventory();
        }
    }

    private List<ItemStack> collectDrops(Block block, ItemStack tool) {
        List<ItemStack> result = new ArrayList<>();
        if (block == null) {
            return result;
        }
        Collection<ItemStack> drops;
        if (tool == null) {
            drops = block.getDrops();
        } else {
            try {
                drops = block.getDrops(tool);
            } catch (Exception ignored) {
                drops = block.getDrops();
            }
        }
        for (ItemStack itemStack : drops) {
            if (itemStack != null && itemStack.getType() != Material.AIR && itemStack.getAmount() > 0) {
                result.add(itemStack);
            }
        }
        return result;
    }

    // Stores one extra drop (e.g. wheat seeds next to wheat) when the player's storage accepts it
    // (auto-pickup on, in the filter, free space); whatever is not stored is dropped back unchanged.
    private void storeExtraDrop(Player p, Location where, String key, ItemStack stack) {
        int amount = stack.getAmount();
        int taken = 0;
        if (MineManager.isAutoPickupEnabledForItem(p, key)) {
            int before = MineManager.getPlayerBlock(p, key);
            if (MineManager.addBlockAmount(p, key, amount)) {
                taken = Math.max(0, MineManager.getPlayerBlock(p, key) - before);
            }
        }
        if (taken < amount) {
            dropAmount(where, stack, amount - taken);
        }
    }

    private void dropAmount(Location where, ItemStack template, int amount) {
        int remaining = amount;
        int max = Math.max(1, template.getMaxStackSize());
        while (remaining > 0 && where.getWorld() != null) {
            ItemStack part = template.clone();
            part.setAmount(Math.min(max, remaining));
            where.getWorld().dropItemNaturally(where, part);
            remaining -= part.getAmount();
        }
    }

    private int getDropAmount(Block block, ItemStack tool) {
        if (block == null) {
            return 0;
        }

        int amount = 0;
        for (ItemStack itemStack : collectDrops(block, tool)) {
            amount += itemStack.getAmount();
        }

        if (amount > 0) {
            return amount;
        }

        // Some blocks (e.g. glass/panes without Silk Touch) have no item drops,
        // but storage mapping expects a fixed item amount.
        String drop = MineManager.getDrop(block);
        if (drop != null) {
            return 1;
        }
        return 0;
    }

    public boolean isPlacedBlock(Block b) {
        if (b == null) {
            return false;
        }

        List<MetadataValue> metaDataValues = b.getMetadata("PlacedBlock");
        for (MetadataValue value : metaDataValues) {
            if (value != null && value.asBoolean()) {
                return true;
            }
        }

        if (b.hasMetadata("placed")) {
            for (MetadataValue value : b.getMetadata("placed")) {
                if (value != null && value.asBoolean()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean handleMmoitemsAutoSmeltCancelledBreak(
            @NotNull Player player,
            @NotNull Block block) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isMmoitemsAutoSmeltTool(tool)) {
            return false;
        }

        maybeCleanupCaptures();
        MmoitemsCaptureKey key = new MmoitemsCaptureKey(
                player.getUniqueId(),
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ());
        MmoitemsCapture capture = mmoitemsCapture.remove(key);
        if (capture == null) {
            return false;
        }
        if (!MineManager.isAutoPickupEnabledForItem(player, capture.dropKey)) {
            return false;
        }

        ItemStack generated = generateMmoitemsAutoSmeltDrop(
                capture.materialName);
        if (generated == null || generated.getAmount() <= 0) {
            return false;
        }

        int fortuneLevel = 0;
        Enchantment fortune = XEnchantment.FORTUNE.get();
        if (fortune != null && tool != null
                && !tool.getType().name().equals("AIR")
                && tool.getAmount() > 0
                && tool.containsEnchantment(fortune)) {
            fortuneLevel = tool.getEnchantmentLevel(fortune);
        }

        generated = generateMmoitemsAutoSmeltDrop(capture.materialName,
                fortuneLevel);
        if (generated == null || generated.getAmount() <= 0) {
            return false;
        }

        removeNearbyDroppedItems(block, generated);

        String dropKey = MineManager.getItemStackDrop(generated);
        if (dropKey == null) {
            dropKey = capture.dropKey;
        }
        if (dropKey == null) {
            return false;
        }

        int amount = generated.getAmount();
        boolean stored = MineManager.addBlockAmount(player, dropKey, amount);
        if (!stored) {
            StorageFullNotificationManager.sendStorageFullNotification(player);
            return true;
        }

        EventManager.onPlayerMine(player, dropKey, amount);
        boolean actionBarEnabled = AutoPickupCache.isStorageActionBarEnabled();
        boolean titleEnabled = AutoPickupCache.isStorageTitleEnabled();
        if (actionBarEnabled || titleEnabled) {
            String name = File.getConfig().getString("items." + dropKey);
            String itemName = name != null ? name : dropKey.replace("_", " ");
            int newStoredAmount = MineManager.getTotalStoredInt(player);
            int maxStorage = MineManager.getMaxBlock(player);
            NotificationQueue.add(player, NotificationQueue.TYPE_STORAGE, dropKey, itemName,
                    amount, "", newStoredAmount, maxStorage, actionBarEnabled, titleEnabled);
        }
        // Only the successfully captured and stored MMOItems path is eligible;
        // arbitrary cancelled break events must never produce special rewards.
        SpecialMaterialManager.checkSpecialMaterialDrop(player, block,
                capture.sourceKey, block.getLocation(), null, true, true);
        return true;
    }

    private boolean isMmoitemsAutoSmeltTool(ItemStack tool) {
        if (tool == null || tool.getType().name().equals("AIR")
                || tool.getAmount() <= 0) {
            return false;
        }
        if (!AutoPickupCache.isMmoitemsAutoSmeltEnabled()) {
            return false;
        }
        try {
            if (!Storage.isMMOItemsInstalled()) {
                return false;
            }
            if (!Storage.isMythicLibInstalled()) {
                return false;
            }
            io.lumine.mythic.lib.api.item.NBTItem nbtItem = io.lumine.mythic.lib.MythicLib.plugin.getVersion()
                    .getWrapper().getNBTItem(tool);
            return nbtItem != null && nbtItem.getBoolean(
                    "MMOITEMS_AUTOSMELT");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ItemStack generateMmoitemsAutoSmeltDrop(String materialName,
                                                    int fortuneLevel) {
        if (materialName == null || materialName.isEmpty()) {
            return null;
        }
        try {
            if (!Storage.isMythicLibInstalled()) {
                return null;
            }
            org.bukkit.Material mat = org.bukkit.Material.getMaterial(
                    materialName);
            if (mat == null) {
                return null;
            }
            io.lumine.mythic.lib.version.OreDrops drops = io.lumine.mythic.lib.MythicLib.plugin.getVersion()
                    .getWrapper().getOreDrops(mat);
            if (drops == null) {
                return null;
            }
            return drops.generate(Math.max(0, fortuneLevel));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ItemStack generateMmoitemsAutoSmeltDrop(String materialName) {
        return generateMmoitemsAutoSmeltDrop(materialName, 0);
    }

    private void removeNearbyDroppedItems(@NotNull Block block,
                                          @NotNull ItemStack target) {
        if (target.getAmount() <= 0) {
            return;
        }
        int remaining = target.getAmount();

        for (Entity entity : block.getWorld().getNearbyEntities(
                block.getLocation().add(0.5, 0.5, 0.5),
                1.5,
                1.5,
                1.5)) {
            if (!(entity instanceof Item itemEntity)) {
                continue;
            }
            if (entity.getTicksLived() > 2) {
                continue;
            }
            ItemStack stack = itemEntity.getItemStack();
            if (stack == null) {
                continue;
            }
            if (stack.getType() != target.getType()) {
                continue;
            }

            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (take >= stack.getAmount()) {
                itemEntity.remove();
            } else {
                stack.setAmount(stack.getAmount() - take);
                itemEntity.setItemStack(stack);
            }

            if (remaining <= 0) {
                break;
            }
        }
    }

    private void maybeCleanupCaptures() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupMs = now;
        mmoitemsCapture.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs > MMOITEMS_CAPTURE_TTL_MS);
    }

    private record MmoitemsCaptureKey(UUID playerId, UUID worldId, int x, int y, int z) {

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MmoitemsCaptureKey that)) {
                return false;
            }
            return x == that.x
                    && y == that.y
                    && z == that.z
                    && playerId.equals(that.playerId)
                    && worldId.equals(that.worldId);
        }

    }

    private record MmoitemsCapture(String materialName, short data, String dropKey, String sourceKey,
                                   long createdAtMs) {
    }
}
