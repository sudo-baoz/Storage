package net.danh.storage.CMD.handler.user;

import net.danh.storage.CMD.handler.BaseCommand;
import net.danh.storage.GUI.PersonalStorage;
import net.danh.storage.Manager.LanguageManager;
import net.danh.storage.Utils.SoundContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * /storage lang [code | list | reload]
 */
public class LangCommand extends BaseCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            String code = LanguageManager.resolve(sender instanceof Player player ? player : null);
            sendMessage(sender, "language.current", "#language#", code);
            return;
        }
        String sub = args[0];
        if (sub.equalsIgnoreCase("list")) {
            sendMessage(sender, "language.list_header");
            for (String code : LanguageManager.available()) {
                sendMessage(sender, "language.list_entry", "#language#", code);
            }
            return;
        }
        if (sub.equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("storage.language.reload")) {
                sendMessage(sender, "admin.no_permission");
                return;
            }
            LanguageManager.reload();
            // The message below is sent in the sender's language, which may just have changed content.
            LanguageManager.setContext(sender);
            sendMessage(sender, "language.reloaded");
            return;
        }
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (!LanguageManager.isPerPlayer()) {
            sendMessage(sender, "language.per_player_disabled");
            return;
        }
        if (!LanguageManager.setPlayerLanguage(player, sub)) {
            sendMessage(sender, "language.invalid", "#language#", sub);
            return;
        }
        // Messages and menus built from now on use the new language.
        LanguageManager.setContext(player);
        sendMessage(sender, "language.changed", "#language#", LanguageManager.resolve(player));
        refreshOpenStorage(player);
    }

    // Re-open the main storage on the same page so an open menu switches language at once.
    private void refreshOpenStorage(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof PersonalStorage) {
            player.openInventory(new PersonalStorage(player, PersonalStorage.getPlayerCurrentPage(player))
                    .getInventory(SoundContext.SILENT));
        }
    }

    @Override
    public List<String> getTabCompletions(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(LanguageManager.available());
            options.add("list");
            if (sender.hasPermission("storage.language.reload")) {
                options.add("reload");
            }
            StringUtil.copyPartialMatches(args[0], options, completions);
        }
        return completions;
    }

    @Override
    public String getPermission() {
        return "storage.language";
    }

    @Override
    public String getUsage() {
        return "/storage lang [code|list|reload]";
    }

    @Override
    public String getDescription() {
        return "Change language";
    }
}
