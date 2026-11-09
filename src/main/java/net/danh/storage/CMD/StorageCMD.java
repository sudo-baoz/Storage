package net.danh.storage.CMD;

import net.danh.storage.API.CMDBase;
import net.danh.storage.Manager.LanguageManager;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class StorageCMD extends CMDBase {

    private final CommandManager commandManager;

    public StorageCMD(String name) {
        super(name);
        this.commandManager = new CommandManager();
    }

    @Override
    public void execute(@NotNull CommandSender sender, String[] args) {
        LanguageManager.setContext(sender);
        try {
            commandManager.handleCommand(sender, args);
        } finally {
            LanguageManager.clearContext();
        }
    }

    @Override
    public List<String> TabComplete(@NotNull CommandSender sender, String[] args) {
        LanguageManager.setContext(sender);
        try {
            return commandManager.getTabCompletions(sender, args);
        } finally {
            LanguageManager.clearContext();
        }
    }
}
