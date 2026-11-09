package net.danh.storage.Manager;

import net.danh.storage.Manager.CurrencyProvider.Currency;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What a storage level costs (Vault, PlayerPoints, EXP and items, in any mix), how to read it from upgrades.yml,
 * whether a player has it, and the reversible steps that take it.
 * Items come only from the main inventory (slots 0-35) and only plain vanilla stacks (no item meta) count.
 */
public final class UpgradeRequirements {

    public static final double MAX_MONEY = 1_000_000_000_000d;
    public static final int MAX_ITEM_AMOUNT = 36 * 64;
    private static final int MAX_EXP_LEVELS = 10_000;
    private static final long MAX_EXP_POINTS = 1_000_000_000L;

    private UpgradeRequirements() {
    }

    public enum ExpType {LEVELS, POINTS}

    public enum Kind {VAULT, POINTS, EXP_LEVELS, EXP_POINTS, ITEM}

    public record ItemReq(Material material, int amount) {
    }

    /**
     * {@code valid=false} means the config for this level is broken: it can't be bought (never for free).
     */
    public record Cost(boolean valid, double vault, long points, ExpType expType, long exp, List<ItemReq> items) {
        public static final Cost INVALID = new Cost(false, 0, 0, ExpType.LEVELS, 0, List.of());
    }

    public record Line(Kind kind, Material material, double current, double required) {
        public boolean ok() {
            return current + 1e-9 >= required;
        }
    }

    public record Evaluation(List<Line> lines, boolean providerMissing) {
        public boolean ok() {
            return !providerMissing && lines.stream().allMatch(Line::ok);
        }
    }

    /**
     * One reversible payment action; {@code take} must leave nothing changed when it returns false.
     */
    public record Step(String name, BooleanSupplier take, BooleanSupplier undo) {
    }

    // ---------- config ----------

    /**
     * Reads {@code requirements}; a level without it keeps its old {@code currency}/{@code price} so no server
     * gets free upgrades. Anything malformed makes the level unbuyable and says why through {@code warn}.
     */
    public static Cost parse(ConfigurationSection level, int number, Consumer<String> warn) {
        String where = "level " + number + ": ";
        ConfigurationSection req = level.getConfigurationSection("requirements");
        if (req == null) {
            return parseLegacy(level, where, warn);
        }
        boolean ok = true;
        double vault = number(req, "vault", MAX_MONEY, where, warn);
        double points = number(req, "playerpoints", Integer.MAX_VALUE, where, warn);
        ExpType type = ExpType.LEVELS;
        double exp = 0;
        ConfigurationSection expSection = req.getConfigurationSection("exp");
        if (expSection != null) {
            String raw = expSection.getString("type", "LEVELS");
            try {
                type = ExpType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn.accept(where + "requirements.exp.type '" + raw + "' must be LEVELS or POINTS.");
                ok = false;
            }
            exp = number(expSection, "amount", type == ExpType.LEVELS ? MAX_EXP_LEVELS : MAX_EXP_POINTS, where, warn);
        }
        Map<Material, Integer> items = new LinkedHashMap<>();
        for (Map<?, ?> entry : req.getMapList("items")) {
            Object name = entry.get("material");
            Object amount = entry.get("amount");
            Material material = name == null ? null : Material.getMaterial(name.toString().trim().toUpperCase(Locale.ROOT));
            if (material == null || material.name().endsWith("AIR") || material.isLegacy()) {
                warn.accept(where + "requirements.items has an unknown material '" + name + "'.");
                ok = false;
                continue;
            }
            if (!(amount instanceof Number n) || n.intValue() < 1 || n.doubleValue() > MAX_ITEM_AMOUNT) {
                warn.accept(where + "requirements.items " + material + " needs an amount from 1 to " + MAX_ITEM_AMOUNT + ".");
                ok = false;
                continue;
            }
            items.merge(material, n.intValue(), Integer::sum);
        }
        List<ItemReq> list = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : items.entrySet()) {
            if (e.getValue() > MAX_ITEM_AMOUNT) {
                warn.accept(where + "requirements.items " + e.getKey() + " totals more than " + MAX_ITEM_AMOUNT + ".");
                ok = false;
            }
            list.add(new ItemReq(e.getKey(), e.getValue()));
        }
        if (Double.isNaN(vault) || Double.isNaN(points) || Double.isNaN(exp)) {
            return Cost.INVALID;
        }
        return ok ? new Cost(true, vault, (long) Math.floor(points), type, (long) Math.floor(exp), List.copyOf(list))
                : Cost.INVALID;
    }

    private static Cost parseLegacy(ConfigurationSection level, String where, Consumer<String> warn) {
        String raw = level.getString("currency", "VAULT");
        Currency currency;
        try {
            currency = Currency.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept(where + "unknown currency '" + raw + "'; it can't be bought.");
            return Cost.INVALID;
        }
        double price = number(level, "price", MAX_MONEY, where, warn);
        if (Double.isNaN(price)) {
            return Cost.INVALID;
        }
        return currency == Currency.VAULT
                ? new Cost(true, price, 0, ExpType.LEVELS, 0, List.of())
                : new Cost(true, 0, (long) Math.floor(price), ExpType.LEVELS, 0, List.of());
    }

    // NaN = invalid (already reported); a missing key is 0.
    private static double number(ConfigurationSection s, String key, double max, String where, Consumer<String> warn) {
        if (!s.contains(key)) {
            return 0;
        }
        Object value = s.get(key);
        double d = value instanceof Number n ? n.doubleValue() : Double.NaN;
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > max) {
            warn.accept(where + key + " = '" + value + "' must be a number from 0 to " + (long) max + ".");
            return Double.NaN;
        }
        return d;
    }

    // ---------- what the player has ----------

    public static Evaluation evaluate(Player player, Cost cost) {
        List<Line> lines = new ArrayList<>();
        boolean missing = false;
        if (cost.vault() > 0) {
            double balance = balance(player, Currency.VAULT);
            missing |= balance < 0;
            lines.add(new Line(Kind.VAULT, null, Math.max(balance, 0), cost.vault()));
        }
        if (cost.points() > 0) {
            double balance = balance(player, Currency.PLAYERPOINTS);
            missing |= balance < 0;
            lines.add(new Line(Kind.POINTS, null, Math.max(balance, 0), cost.points()));
        }
        if (cost.exp() > 0) {
            boolean levels = cost.expType() == ExpType.LEVELS;
            lines.add(new Line(levels ? Kind.EXP_LEVELS : Kind.EXP_POINTS, null,
                    levels ? player.getLevel() : totalExp(player), cost.exp()));
        }
        for (ItemReq item : cost.items()) {
            lines.add(new Line(Kind.ITEM, item.material(), count(player, item.material()), item.amount()));
        }
        return new Evaluation(lines, missing);
    }

    // -1 when the provider is missing or can't be read.
    private static double balance(Player player, Currency currency) {
        CurrencyProvider provider = CurrencyProvider.of(currency);
        return provider.available() ? provider.balance(player) : -1;
    }

    // ---------- items ----------

    private static boolean plain(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && !stack.hasItemMeta();
    }

    public static int count(Player player, Material material) {
        PlayerInventory inventory = player.getInventory();
        long total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (plain(stack, material)) {
                total += stack.getAmount();
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * Removes exactly {@code amount}; nothing is touched unless the whole amount is there.
     */
    public static boolean removeItems(Player player, Material material, int amount) {
        if (count(player, material) < amount) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        int left = amount;
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!plain(stack, material)) {
                continue;
            }
            int taken = Math.min(left, stack.getAmount());
            left -= taken;
            if (taken == stack.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - taken);
                inventory.setItem(slot, stack);
            }
        }
        return left == 0;
    }

    // addItem splits oversized amounts itself; what does not fit is dropped at the player's feet, never lost.
    static boolean giveBack(Player player, Material material, int amount) {
        Map<Integer, ItemStack> rest = player.getInventory().addItem(new ItemStack(material, amount));
        for (ItemStack extra : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        return true;
    }

    // ---------- experience ----------

    /**
     * Points needed to reach {@code level} from 0 (vanilla formulas).
     */
    static long levelPoints(int level) {
        if (level <= 16) {
            return (long) level * level + 6L * level;
        }
        if (level <= 31) {
            return Math.round(2.5 * level * level - 40.5 * level + 360);
        }
        return Math.round(4.5 * level * level - 162.5 * level + 2220);
    }

    // Points between this level and the next.
    static int expToNext(int level) {
        return level <= 15 ? 2 * level + 7 : level <= 30 ? 5 * level - 38 : 9 * level - 158;
    }

    /**
     * Real experience points: whole levels plus the progress bar. {@code getTotalExperience()} drifts when other
     * plugins change levels, so it is not used.
     */
    public static long totalExp(Player player) {
        int level = player.getLevel();
        return levelPoints(level) + Math.round(player.getExp() * expToNext(level));
    }

    private static boolean takeExp(Player player, ExpType type, long amount) {
        if (type == ExpType.LEVELS) {
            if (player.getLevel() < amount) {
                return false;
            }
            player.setLevel(player.getLevel() - (int) amount);
            return true;
        }
        long remaining = totalExp(player) - amount;
        if (remaining < 0) {
            return false;
        }
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        player.giveExp((int) remaining);
        return true;
    }

    // ---------- payment ----------

    /**
     * The payment as ordered, reversible steps: items, experience, Vault, PlayerPoints.
     */
    public static List<Step> steps(Player player, Cost cost) {
        List<Step> steps = new ArrayList<>();
        for (ItemReq item : cost.items()) {
            steps.add(new Step(item.amount() + " " + item.material(),
                    () -> removeItems(player, item.material(), item.amount()),
                    () -> giveBack(player, item.material(), item.amount())));
        }
        if (cost.exp() > 0) {
            int[] before = new int[2];
            float[] progress = new float[1];
            steps.add(new Step(cost.exp() + " exp " + cost.expType(), () -> {
                before[0] = player.getLevel();
                before[1] = player.getTotalExperience();
                progress[0] = player.getExp();
                return takeExp(player, cost.expType(), cost.exp());
            }, () -> {
                player.setLevel(before[0]);
                player.setExp(progress[0]);
                player.setTotalExperience(before[1]);
                return true;
            }));
        }
        addMoney(steps, player, Currency.VAULT, cost.vault());
        addMoney(steps, player, Currency.PLAYERPOINTS, cost.points());
        return steps;
    }

    private static void addMoney(List<Step> steps, Player player, Currency currency, double amount) {
        if (amount > 0) {
            CurrencyProvider provider = CurrencyProvider.of(currency);
            steps.add(new Step(amount + " " + currency, () -> provider.withdraw(player, amount),
                    () -> provider.deposit(player, amount)));
        }
    }
}
