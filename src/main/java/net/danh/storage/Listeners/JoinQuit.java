package net.danh.storage.Listeners;

import net.danh.storage.Enchant.HasteEnchant;
import net.danh.storage.Enchant.MultiplierEnchant;
import net.danh.storage.Enchant.TNTEnchant;
import net.danh.storage.Enchant.VeinMinerEnchant;
import net.danh.storage.GUI.*;
import net.danh.storage.GUI.Crafting.RecipeEditorGUI;
import net.danh.storage.Manager.Crafting.CraftingManager;
import net.danh.storage.Manager.Crafting.RecipeEditManager;
import net.danh.storage.Manager.Friend.FriendManager;
import net.danh.storage.Manager.*;
import net.danh.storage.Manager.SpecialMaterial.SpecialMaterialManager;
import net.danh.storage.Storage;
import net.danh.storage.Utils.NotificationQueue;
import net.danh.storage.Utils.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

public class JoinQuit implements Listener {

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent e) {
        Player p = e.getPlayer();
        MineManager.loadPlayerData(p);
        FriendManager.loadPlayerDataAsync(p, () -> {
            if (p.isOnline()) {
                FriendManager.notifyPendingRequests(p);
            }
        });

        SchedulerUtil.runTaskLater(Storage.getStorage(), p, () -> {
            if (!p.isOnline()) {
                return;
            }
            MineManager.scheduleAutoSellOnJoin(p);
        }, 1L);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent e) {
        Player p = e.getPlayer();
        MineManager.savePlayerData(p);
        MineManager.cleanupPlayerData(p);
        FriendManager.cleanupPlayerData(p);

        SpecialMaterialManager.cleanupPlayerData(p);
        PersonalStorage.playerCurrentPage.remove(p.getUniqueId());
        ChatListener.chat_return_page.remove(p.getUniqueId());


        // Cleanup transfer data
        TransferGUI.setWaitingForInput(p, false);
        TransferManager.cancelTransfer(p);

        // Cleanup transfer GUI data (TransferGUI and TransferMultiGUI auto-cleanup on
        // close)

        // Cleanup storage full notification data
        StorageFullNotificationManager.removePlayer(p);

        // Cleanup pending pickup notifications
        NotificationQueue.cleanup(p);

        // Cleanup enchant cooldown data
        TNTEnchant.clearPlayerCooldown(p);
        HasteEnchant.clearPlayerCooldown(p);
        MultiplierEnchant.clearPlayerCooldown(p);
        VeinMinerEnchant.clearPlayerCooldown(p);

        // Cleanup sound tracking data
        SoundManager.cleanupPlayer(p);

        // Cleanup particle tracking data
        ParticleManager.cleanupPlayer(p);

        // Cleanup crafting data
        CraftingManager.cancelCrafting(p);
        ChatListener.craftingRequests.remove(p.getUniqueId());

        // Cleanup recipe editing data
        RecipeEditManager.cancelEdit(p);
        RecipeEditorGUI.cleanupBackup(p.getUniqueId());

        // Cleanup remaining chat data
        ChatListener.chat_deposit.remove(p.getUniqueId());
        ChatListener.chat_withdraw.remove(p.getUniqueId());
        ChatListener.chat_sell.remove(p.getUniqueId());
        ChatListener.chat_convert_from.remove(p.getUniqueId());
        ChatListener.chat_convert_to.remove(p.getUniqueId());

        // Cleanup GUI page tracking
        ViewStorageGUI.playerCurrentPage.remove(p.getUniqueId());
        ConvertOreGUI.playerCurrentPage.remove(p.getUniqueId());

        // Cleanup GUI item mapper
        GUI.getItemMapper().remove(p.getUniqueId());
    }
}
