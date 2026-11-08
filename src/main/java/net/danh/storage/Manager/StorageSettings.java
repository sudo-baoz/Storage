package net.danh.storage.Manager;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-storage settings that live in the same data string as the item amounts (no schema change):
 * upgrade level, sort mode, auto-pickup filter and the order items were first added.
 * Keyed by storage key, so every member of an island shares one set.
 */
public final class StorageSettings {

    private static final String UPGRADE = ";upgrade:";
    private static final String SORT = ";sort:";
    private static final String FILTER = ";pickupfilter:";
    private static final String ORDER = ";order:";
    // Material keys contain ';' (COAL;0), so a token ends at the next ";word:" marker, not at the next ';'.
    private static final Pattern NEXT_TOKEN = Pattern.compile(";[a-z_]+:");
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private StorageSettings() {
    }

    private static State state(@NotNull String key) {
        return STATES.computeIfAbsent(key, k -> new State());
    }

    public static int getLevel(@NotNull String key) {
        return state(key).level;
    }

    public static void setLevel(@NotNull String key, int level) {
        state(key).level = Math.max(0, level);
    }

    public static @NotNull SortMode getSortMode(@NotNull String key) {
        return state(key).sort;
    }

    public static void setSortMode(@NotNull String key, @NotNull SortMode mode) {
        state(key).sort = mode;
    }

    public static @NotNull Set<String> getFilter(@NotNull String key) {
        return Collections.unmodifiableSet(state(key).filter);
    }

    public static boolean isFiltered(@NotNull String key, @NotNull String material) {
        State s = STATES.get(key);
        return s == null || s.filter.isEmpty() || s.filter.contains(material);
    }

    /**
     * @return true if the material is now part of the filter
     */
    public static boolean toggleFilter(@NotNull String key, @NotNull String material) {
        Set<String> filter = state(key).filter;
        if (!filter.remove(material)) {
            filter.add(material);
            return true;
        }
        return false;
    }

    public static void clearFilter(@NotNull String key) {
        state(key).filter.clear();
    }

    /**
     * Remember the first time a material was stored; later removals keep its position.
     */
    public static void noteAdded(@NotNull String key, @NotNull String material) {
        state(key).order.add(material);
    }

    public static @NotNull List<String> insertionOrder(@NotNull String key) {
        return new ArrayList<>(state(key).order);
    }

    public static void remove(@NotNull String key) {
        STATES.remove(key);
    }

    public static void clearAll() {
        STATES.clear();
    }

    public static void load(@NotNull String key, String data) {
        State s = new State();
        if (data != null) {
            String level = token(data, UPGRADE);
            if (level != null) {
                try {
                    s.level = Math.max(0, Integer.parseInt(level.trim()));
                } catch (NumberFormatException ignored) {
                    // Corrupt value: stay on level 0 rather than failing the whole load.
                }
            }
            String sort = token(data, SORT);
            if (sort != null) {
                try {
                    s.sort = SortMode.valueOf(sort.trim());
                } catch (IllegalArgumentException ignored) {
                    // Unknown mode (older/newer version): keep the default.
                }
            }
            s.filter.addAll(list(token(data, FILTER)));
            s.order.addAll(list(token(data, ORDER)));
        }
        STATES.put(key, s);
    }

    /**
     * Tokens to append right after the item map, before the older suffixes.
     */
    public static @NotNull String serialize(@NotNull String key) {
        State s = STATES.get(key);
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (s.level > 0) {
            out.append(UPGRADE).append(s.level);
        }
        out.append(SORT).append(s.sort.name());
        if (!s.filter.isEmpty()) {
            out.append(FILTER).append(String.join(",", s.filter));
        }
        if (!s.order.isEmpty()) {
            out.append(ORDER).append(String.join(",", s.order));
        }
        return out.toString();
    }

    private static String token(String data, String prefix) {
        int idx = data.indexOf(prefix);
        if (idx < 0) {
            return null;
        }
        int start = idx + prefix.length();
        Matcher next = NEXT_TOKEN.matcher(data);
        int end = next.find(start) ? next.start() : data.length();
        return data.substring(start, end);
    }

    private static List<String> list(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        // Items are "NAME;data": split on ',' only.
        for (String raw : value.split(",")) {
            String item = raw.trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    public enum SortMode {TYPE, NAME_ASC, NAME_DESC, AMOUNT, INSERTION}

    private static final class State {
        private int level;
        private SortMode sort = SortMode.INSERTION;
        private final Set<String> filter = new LinkedHashSet<>();
        private final Set<String> order = new LinkedHashSet<>();
    }
}
