package net.danh.storage.Manager.Island;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.*;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import net.danh.storage.Manager.Island.IslandStorageManager.IslandInfo;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Storage;
import net.danh.storage.Utils.File;
import net.danh.storage.Utils.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SuperiorSkyblock2 bridge. Only load this class when the plugin is present.
 */
public final class SuperiorSkyblockHook implements IslandStorageManager.Provider, Listener {

    /**
     * SuperiorSkyblock events fire before the change is applied, so wait before re-reading membership.
     */
    private static final long REFRESH_DELAY_TICKS = 5L;
    private static boolean attached;

    private SuperiorSkyblockHook() {
    }

    public static void attach(@NotNull Storage plugin) {
        if (attached || !File.getConfig().getBoolean("island_storage.enabled", true)) {
            return;
        }
        attached = true;
        IslandStorageManager.load(new java.io.File(plugin.getDataFolder(), "island-members.yml"));
        IslandStorageManager.setDefaultMemberSell(
                () -> File.getConfig().getBoolean("island_storage.default_member_sell", false));
        SuperiorSkyblockHook hook = new SuperiorSkyblockHook();
        IslandStorageManager.setProvider(hook);
        plugin.registerEvents(hook);
        // Players already online when the hook attaches (late enable) must move to their island storage.
        Bukkit.getOnlinePlayers().forEach(MineManager::refreshStorageOwner);
        plugin.getLogger().info("Hook with SuperiorSkyblock2: island members share one storage");
    }

    @Override
    public @Nullable IslandInfo find(@NotNull UUID playerId) {
        SuperiorPlayer player = SuperiorSkyblockAPI.getPlayer(playerId);
        Island island = player == null ? null : player.getIsland();
        if (island == null || island.getOwner() == null) {
            return null;
        }
        SuperiorPlayer owner = island.getOwner();
        return new IslandInfo(island.getUniqueId(), owner.getUniqueId(), owner.getName());
    }

    @Override
    public @NotNull Map<UUID, String> members(@NotNull IslandInfo island) {
        SuperiorPlayer owner = SuperiorSkyblockAPI.getPlayer(island.ownerId());
        Island real = owner == null ? null : owner.getIsland();
        Map<UUID, String> result = new LinkedHashMap<>();
        if (real != null) {
            for (SuperiorPlayer member : real.getIslandMembers(true)) {
                result.put(member.getUniqueId(), member.getName());
            }
        }
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(IslandJoinEvent event) {
        refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(IslandQuitEvent event) {
        IslandStorageManager.clearMember(event.getIsland().getUniqueId(), event.getPlayer().getUniqueId());
        refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(IslandKickEvent event) {
        IslandStorageManager.clearMember(event.getIsland().getUniqueId(), event.getTarget().getUniqueId());
        refreshLater(event.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransfer(IslandTransferEvent event) {
        refreshLater(event.getIsland().getIslandMembers(true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(IslandDisbandEvent event) {
        IslandStorageManager.clearIsland(event.getIsland().getUniqueId());
        refreshLater(event.getIsland().getIslandMembers(true));
    }

    private static void refreshLater(List<SuperiorPlayer> players) {
        players.forEach(SuperiorSkyblockHook::refreshLater);
    }

    private static void refreshLater(SuperiorPlayer superiorPlayer) {
        Player player = superiorPlayer.asPlayer();
        if (player == null) {
            return;
        }
        SchedulerUtil.runTaskLater(Storage.getStorage(), player, () -> {
            if (player.isOnline()) {
                MineManager.refreshStorageOwner(player);
            }
        }, REFRESH_DELAY_TICKS);
    }
}
