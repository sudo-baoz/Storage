package net.danh.storage.CMD.handler.user;

import net.danh.storage.CMD.handler.BaseCommand;
import net.danh.storage.GUI.UpgradeGUI;
import net.danh.storage.Manager.StorageUpgrade;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /storage upgrade: opens the capacity upgrade menu. Buying a level is checked separately per level.
 */
public class UpgradeCommand extends BaseCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "upgrade.command.player-only");
            return;
        }
        if (!player.hasPermission("storage.upgrade.open")) {
            sendMessage(sender, "upgrade.command.no-permission");
            return;
        }
        if (args.length > 0) {
            sendMessage(sender, "upgrade.command.usage");
            return;
        }
        if (!StorageUpgrade.isEnabled()) {
            sendMessage(sender, "upgrade.command.disabled");
            return;
        }
        UpgradeGUI.show(player);
    }

    @Override
    public List<String> getTabCompletions(CommandSender sender, String[] args) {
        return List.of();
    }

    // null: the permission is checked in execute() so the player gets the upgrade-specific message.
    @Override
    public String getPermission() {
        return null;
    }

    @Override
    public String getUsage() {
        return "/storage upgrade";
    }

    @Override
    public String getDescription() {
        return "Open the storage upgrade menu";
    }
}
