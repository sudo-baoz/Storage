package net.danh.storage.Manager;

import net.danh.storage.Utils.File;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafter button: turns stored items into blocks directly inside the virtual storage (never touches the
 * player's inventory). Recipes come from config.yml crafter.recipes and are handled in config order.
 */
public final class StorageCrafter {

    private StorageCrafter() {
    }

    public static boolean isEnabled() {
        return File.getConfig().getBoolean("crafter.enabled", true);
    }

    public static @NotNull String permission() {
        return File.getConfig().getString("crafter.permission", "storage.donate.craft");
    }

    public static boolean hasPermission(@NotNull Player player) {
        String permission = permission();
        return permission.isEmpty() || player.hasPermission(permission);
    }

    /**
     * Crafts exactly one block of the first recipe (config order) that has enough ingredients.
     *
     * @return the number of blocks crafted (0 or 1)
     */
    public static int craftOne(@NotNull Player player) {
        for (Recipe recipe : recipes()) {
            if (MineManager.getPlayerBlock(player, recipe.input()) >= recipe.amount() && convert(player, recipe, 1)) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * Crafts every block that the stored ingredients allow, recipe by recipe in config order.
     *
     * @return the total number of blocks crafted
     */
    public static int craftAll(@NotNull Player player) {
        int crafted = 0;
        for (Recipe recipe : recipes()) {
            int times = MineManager.getPlayerBlock(player, recipe.input()) / recipe.amount();
            if (times > 0 && convert(player, recipe, times)) {
                crafted += times;
            }
        }
        return crafted;
    }

    // Remove the ingredients and add the blocks together; if the add is refused the ingredients are restored,
    // so nothing is lost or duplicated.
    private static boolean convert(Player player, Recipe recipe, int times) {
        int used = Math.multiplyExact(recipe.amount(), times);
        if (!MineManager.removeBlockAmount(player, recipe.input(), used, false)) {
            return false;
        }
        if (MineManager.addBlockAmount(player, recipe.output(), times, false)) {
            return true;
        }
        MineManager.addBlockAmount(player, recipe.input(), used, false);
        return false;
    }

    private static List<Recipe> recipes() {
        List<Recipe> result = new ArrayList<>();
        ConfigurationSection section = File.getConfig().getConfigurationSection("crafter.recipes");
        if (section == null) {
            return result;
        }
        for (String input : section.getKeys(false)) {
            String output = section.getString(input + ".block");
            int amount = section.getInt(input + ".amount", 9);
            // Only recipes whose items exist in this storage and make sense are usable.
            if (output != null && amount > 0 && MineManager.getPluginBlocks().contains(input)
                    && MineManager.getPluginBlocks().contains(output)) {
                result.add(new Recipe(input, output, amount));
            }
        }
        return result;
    }

    private record Recipe(String input, String output, int amount) {
    }
}
