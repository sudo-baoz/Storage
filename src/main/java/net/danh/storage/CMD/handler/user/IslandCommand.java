package net.danh.storage.CMD.handler.user;

import net.danh.storage.CMD.handler.BaseCommand;
import net.danh.storage.Manager.Island.IslandStorageManager;
import net.danh.storage.Manager.Island.IslandStorageManager.IslandInfo;
import net.danh.storage.Utils.File;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /storage island info | sell &lt;player&gt; &lt;on|off&gt; - island owner manages what members may do with the shared storage.
 */
public class IslandCommand extends BaseCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;

        if (!IslandStorageManager.isEnabled()) {
            sendMessage(sender, "user.island.disabled");
            return;
        }
        IslandInfo island = IslandStorageManager.find(player.getUniqueId());
        if (island == null) {
            sendMessage(sender, "user.island.not_in_island");
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sendInfo(player, island);
            return;
        }
        if (args[0].equalsIgnoreCase("sell") && args.length == 3) {
            setSell(player, island, args[1], args[2]);
            return;
        }
        sendUsage(sender);
    }

    private void sendInfo(Player player, IslandInfo island) {
        boolean owner = island.ownerId().equals(player.getUniqueId());
        sendMessage(player, "user.island.info_header", "#owner#", island.ownerName());
        sendMessage(player, "user.island.info_you",
                new String[]{"#role#", "#status#"},
                new String[]{File.getMessage().getString(owner ? "user.island.role_owner" : "user.island.role_member", ""),
                        sellStatus(IslandStorageManager.canSell(player))});
        if (!owner) {
            return;
        }
        for (Map.Entry<UUID, String> member : IslandStorageManager.members(island).entrySet()) {
            if (member.getKey().equals(island.ownerId())) {
                continue;
            }
            sendMessage(player, "user.island.info_member",
                    new String[]{"#player#", "#status#"},
                    new String[]{member.getValue(),
                            sellStatus(IslandStorageManager.isSellAllowed(island.islandId(), member.getKey()))});
        }
    }

    private void setSell(Player owner, IslandInfo island, String targetName, String value) {
        if (!island.ownerId().equals(owner.getUniqueId())) {
            sendMessage(owner, "user.island.not_owner");
            return;
        }
        boolean allow;
        if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("allow")) {
            allow = true;
        } else if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("deny")) {
            allow = false;
        } else {
            sendUsage(owner);
            return;
        }

        UUID targetId = null;
        String shownName = targetName;
        for (Map.Entry<UUID, String> member : IslandStorageManager.members(island).entrySet()) {
            if (member.getValue().equalsIgnoreCase(targetName)) {
                targetId = member.getKey();
                shownName = member.getValue();
                break;
            }
        }
        if (targetId == null) {
            sendMessage(owner, "user.island.not_member", "#player#", targetName);
            return;
        }
        if (targetId.equals(island.ownerId())) {
            sendMessage(owner, "user.island.cannot_target_owner");
            return;
        }

        IslandStorageManager.setSellAllowed(island.islandId(), targetId, allow);
        sendMessage(owner, allow ? "user.island.sell_allowed" : "user.island.sell_revoked", "#player#", shownName);
    }

    private String sellStatus(boolean allowed) {
        return File.getMessage().getString(allowed ? "user.island.status_yes" : "user.island.status_no", "");
    }

    @Override
    public List<String> getTabCompletions(CommandSender sender, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], List.of("info", "sell"), result);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("sell") && sender instanceof Player player) {
            IslandInfo island = IslandStorageManager.find(player.getUniqueId());
            if (island != null && island.ownerId().equals(player.getUniqueId())) {
                StringUtil.copyPartialMatches(args[1],
                        IslandStorageManager.members(island).values().stream()
                                .filter(name -> !name.equalsIgnoreCase(player.getName())).toList(), result);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
            StringUtil.copyPartialMatches(args[2], List.of("on", "off"), result);
        }
        return result;
    }

    @Override
    public String getPermission() {
        return "storage.island";
    }

    @Override
    public String getUsage() {
        return "/storage island [info | sell <player> <on|off>]";
    }

    @Override
    public String getDescription() {
        return "View the shared island storage and manage which members may sell";
    }
}
