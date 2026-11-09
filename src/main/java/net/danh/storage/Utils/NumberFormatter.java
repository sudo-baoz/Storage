package net.danh.storage.Utils;

import net.danh.storage.Manager.LanguageManager;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Display-only number formatting (1500 -> 1.5K). Never feed the result back into a balance, price or save:
 * transactions always use the real numbers.
 */
public final class NumberFormatter {

    public static final String VAULT = "vault";
    public static final String PLAYERPOINTS = "playerpoints";
    public static final String EXP = "exp";
    public static final String ITEMS = "items";
    public static final String CAPACITY = "storage-capacity";

    private static final String[] KEYS = {"thousand", "million", "billion", "trillion"};
    private static final String[] DEFAULT_SUFFIX = {"K", "M", "B", "T"};
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private NumberFormatter() {
    }

    /**
     * Compact form when enabled for {@code category}, otherwise the plain locale form. Read from config.yml on every
     * call, so a reload applies at once.
     */
    public static String format(String category, double value) {
        FileConfiguration config = File.getConfig();
        String base = "number-format.compact.";
        boolean enabled = config != null && config.getBoolean(base + "enabled", true)
                && config.getBoolean(base + "apply-to." + category, category.equals(VAULT) || category.equals(PLAYERPOINTS));
        if (!enabled) {
            return plain(category, value);
        }
        int places = Math.max(0, Math.min(6, config.getInt(base + "decimal-places", 2)));
        String[] suffixes = new String[4];
        for (int i = 0; i < 4; i++) {
            String configured = config.getString(base + "suffixes." + KEYS[i], DEFAULT_SUFFIX[i]);
            suffixes[i] = configured == null || configured.isBlank() ? DEFAULT_SUFFIX[i] : configured;
        }
        return compact(value, places, config.getBoolean(base + "trim-trailing-zeros", true), suffixes);
    }

    private static String plain(String category, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        return category.equals(VAULT) ? LanguageManager.formatDecimal(value) : LanguageManager.formatNumber((long) value);
    }

    /**
     * Cuts (never rounds up) to {@code places} decimals, so 999,999 shows as 999.99K and never as an amount the
     * player does not have; a value only changes unit when it really reaches it.
     */
    public static String compact(double value, int places, boolean trim, String[] suffixes) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "-";
        }
        BigDecimal number = BigDecimal.valueOf(value);
        String sign = number.signum() < 0 ? "-" : "";
        number = number.abs();
        int unit = 0;
        BigDecimal scaled = number;
        while (unit < suffixes.length && scaled.compareTo(THOUSAND) >= 0) {
            scaled = scaled.divide(THOUSAND);
            unit++;
        }
        scaled = scaled.setScale(places, RoundingMode.DOWN);
        String text = trim ? scaled.stripTrailingZeros().toPlainString() : scaled.toPlainString();
        return sign + text + (unit == 0 ? "" : suffixes[unit - 1]);
    }
}
