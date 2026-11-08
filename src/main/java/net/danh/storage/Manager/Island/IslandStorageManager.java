package net.danh.storage.Manager.Island;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Shared island storage: every member of a skyblock island uses the storage row of the island owner.
 * The island plugin is hidden behind {@link Provider} so this class never touches SuperiorSkyblock classes.
 */
public final class IslandStorageManager {

    private static final Map<UUID, Map<UUID, Boolean>> SELL_OVERRIDES = new ConcurrentHashMap<>();
    private static volatile Provider provider;
    private static volatile BooleanSupplier defaultMemberSell = () -> false;
    private static volatile File file;

    private IslandStorageManager() {
    }

    public static void setProvider(@Nullable Provider newProvider) {
        provider = newProvider;
    }

    public static boolean isEnabled() {
        return provider != null;
    }

    public static void setDefaultMemberSell(@NotNull BooleanSupplier supplier) {
        defaultMemberSell = supplier;
    }

    public static @Nullable IslandInfo find(@NotNull UUID playerId) {
        Provider current = provider;
        return current == null ? null : current.find(playerId);
    }

    public static @NotNull Map<UUID, String> members(@NotNull IslandInfo island) {
        Provider current = provider;
        return current == null ? Map.of() : current.members(island);
    }

    /**
     * Name of the storage row the player must use: the island owner's name, or the player's own name.
     */
    public static @NotNull String resolveKey(@NotNull Player player) {
        IslandInfo island = find(player.getUniqueId());
        return island == null ? player.getName() : island.ownerName();
    }

    /**
     * Same as {@link #resolveKey(Player)} for a player who may be offline.
     */
    public static @NotNull String resolveKey(@NotNull UUID playerId, @NotNull String playerName) {
        IslandInfo island = find(playerId);
        return island == null ? playerName : island.ownerName();
    }

    public static boolean canSell(@NotNull Player player) {
        IslandInfo island = find(player.getUniqueId());
        if (island == null || island.ownerId().equals(player.getUniqueId())) {
            return true;
        }
        return isSellAllowed(island.islandId(), player.getUniqueId());
    }

    /**
     * True for the island owner and for players without an island (they own their personal storage).
     */
    public static boolean isOwnerOrSolo(@NotNull Player player) {
        IslandInfo island = find(player.getUniqueId());
        return island == null || island.ownerId().equals(player.getUniqueId());
    }

    public static boolean isSellAllowed(@NotNull UUID islandId, @NotNull UUID memberId) {
        Map<UUID, Boolean> overrides = SELL_OVERRIDES.get(islandId);
        Boolean value = overrides == null ? null : overrides.get(memberId);
        return value != null ? value : defaultMemberSell.getAsBoolean();
    }

    public static void setSellAllowed(@NotNull UUID islandId, @NotNull UUID memberId, boolean allowed) {
        SELL_OVERRIDES.computeIfAbsent(islandId, id -> new ConcurrentHashMap<>()).put(memberId, allowed);
        save();
    }

    /**
     * Forget a member's override (left, kicked) so a later rejoin starts from the default again.
     */
    public static void clearMember(@NotNull UUID islandId, @NotNull UUID memberId) {
        Map<UUID, Boolean> overrides = SELL_OVERRIDES.get(islandId);
        if (overrides != null && overrides.remove(memberId) != null) {
            save();
        }
    }

    public static void clearIsland(@NotNull UUID islandId) {
        if (SELL_OVERRIDES.remove(islandId) != null) {
            save();
        }
    }

    public static void load(@NotNull File source) {
        file = source;
        SELL_OVERRIDES.clear();
        if (!source.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source);
        var islands = yaml.getConfigurationSection("islands");
        if (islands == null) {
            return;
        }
        for (String islandKey : islands.getKeys(false)) {
            var members = islands.getConfigurationSection(islandKey);
            if (members == null) {
                continue;
            }
            try {
                UUID islandId = UUID.fromString(islandKey);
                for (String memberKey : members.getKeys(false)) {
                    SELL_OVERRIDES.computeIfAbsent(islandId, id -> new ConcurrentHashMap<>())
                            .put(UUID.fromString(memberKey), members.getBoolean(memberKey));
                }
            } catch (IllegalArgumentException ignored) {
                // Skip hand-edited entries that are not UUIDs.
            }
        }
    }

    private static synchronized void save() {
        File target = file;
        if (target == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        SELL_OVERRIDES.forEach((islandId, members) -> members.forEach(
                (memberId, allowed) -> yaml.set("islands." + islandId + "." + memberId, allowed)));
        try {
            yaml.save(target);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save " + target, e);
        }
    }

    /**
     * Test hook: forget all state.
     */
    static void reset() {
        SELL_OVERRIDES.clear();
        provider = null;
        file = null;
        defaultMemberSell = () -> false;
    }

    public record IslandInfo(UUID islandId, UUID ownerId, String ownerName) {
    }

    public interface Provider {
        /**
         * The island the player belongs to (as owner or member), or null.
         */
        @Nullable IslandInfo find(@NotNull UUID playerId);

        /**
         * Every member of the island including the owner, uuid -> name.
         */
        @NotNull Map<UUID, String> members(@NotNull IslandInfo island);
    }
}
