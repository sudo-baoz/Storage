package net.danh.storage.Manager.Island;

import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageCrafter;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageSettings.SortMode;
import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Utils.File;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static net.danh.storage.Manager.Island.IslandStorageManagerTest.player;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Total capacity (all items share one limit), the settings stored with the data, the Crafter and upgrade levels.
 */
class TotalCapacityTest {

    private static final String IRON = "IRON_INGOT;0";
    private static final String IRON_BLOCK = "IRON_BLOCK;0";
    private static final String COAL = "COAL;0";
    private static final String STONE = "STONE;0";
    private static final String COBBLE = "COBBLESTONE;0";

    private final YamlConfiguration config = new YamlConfiguration();
    private final YamlConfiguration upgrades = new YamlConfiguration();
    private final Player p = player("Miner");
    private MockedStatic<File> fileMock;

    @BeforeAll
    static void fakeServer() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
            when(server.getBukkitVersion()).thenReturn("1.20.4-R0.1-SNAPSHOT");
            when(server.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
            Bukkit.setServer(server);
        }
    }

    @BeforeEach
    void setUp() {
        config.set("settings.default_max_storage", 1000);
        config.set("crafter.permission", "storage.donate.craft");
        config.set("crafter.recipes." + IRON + ".block", IRON_BLOCK);
        config.set("crafter.recipes." + IRON + ".amount", 9);
        upgrades.set("settings.enabled", true);
        upgrades.set("levels.1.capacity", 5000);
        upgrades.set("levels.2.capacity", 20000);
        fileMock = mockStatic(File.class);
        fileMock.when(File::getConfig).thenReturn(config);
        fileMock.when(File::getUpgradesConfig).thenReturn(upgrades);
        StorageUpgrade.reload();
        for (String material : new String[]{IRON, IRON_BLOCK, COAL, COBBLE}) {
            MineManager.addPluginBlocks(material);
        }
        // Two blocks that drop the same item, like STONE and COBBLESTONE -> COBBLESTONE;0.
        MineManager.blocksdata.put(STONE, COBBLE);
    }

    @AfterEach
    void tearDown() {
        MineManager.cleanupPlayerData(p);
        MineManager.playerdata.clear();
        MineManager.blocksdata.clear();
        StorageSettings.clearAll();
        fileMock.close();
    }

    @Test
    void allItemsShareOneLimit() {
        assertTrue(MineManager.addBlockAmount(p, COAL, 600, false));
        assertTrue(MineManager.addBlockAmount(p, IRON, 600, false));

        assertEquals(600, MineManager.getPlayerBlock(p, COAL));
        assertEquals(400, MineManager.getPlayerBlock(p, IRON), "only what still fits is stored");
        assertEquals(1000, MineManager.getTotalStored(p));
        assertEquals(0, MineManager.getFreeSpace(p));
        assertFalse(MineManager.addBlockAmount(p, COBBLE, 1, false));
    }

    @Test
    void withdrawingFreesSpaceAndDuplicateDropsAreCountedOnce() {
        MineManager.addBlockAmount(p, COBBLE, 100, false);
        assertEquals(100, MineManager.getTotalStored(p), "STONE and COBBLESTONE share one item");

        MineManager.removeBlockAmount(p, COBBLE, 40, false);
        assertEquals(940, MineManager.getFreeSpace(p));
    }

    @Test
    void oldOverLimitDataIsKeptButBlocksNewItems() {
        MineManager.setBlock(p, COAL, 1500);
        MineManager.setBlock(p, IRON, 300);

        assertEquals(1500, MineManager.getPlayerBlock(p, COAL));
        assertEquals(0, MineManager.getFreeSpace(p));
        assertFalse(MineManager.addBlockAmount(p, IRON, 10, false));

        MineManager.removeBlockAmount(p, COAL, 1000, false);
        assertEquals(200, MineManager.getFreeSpace(p), "500 coal + 300 iron stored, 1000 allowed");
    }

    @Test
    void totalDoesNotOverflowAnInt() {
        MineManager.setBlock(p, COAL, Integer.MAX_VALUE);
        MineManager.setBlock(p, IRON, Integer.MAX_VALUE);

        assertEquals(2L * Integer.MAX_VALUE, MineManager.getTotalStored(p));
        assertEquals(0, MineManager.getFreeSpace(p));
    }

    @Test
    void upgradeLevelRaisesTheTotalCapacity() {
        assertEquals(1000, MineManager.getMaxBlock(p));
        StorageSettings.setLevel(MineManager.storageKey(p), 1);
        assertEquals(5000, MineManager.getMaxBlock(p));
        StorageSettings.setLevel(MineManager.storageKey(p), 2);
        assertEquals(20000, MineManager.getMaxBlock(p));
        assertEquals(2, StorageUpgrade.maxLevel());
        assertEquals(0, StorageUpgrade.capacity(3), "undefined level grants nothing");
    }

    @Test
    void craftingTurnsNineIngotsIntoOneBlockAndKeepsTheRest() {
        MineManager.setBlock(p, IRON, 64);

        assertEquals(1, StorageCrafter.craftOne(p));
        assertEquals(55, MineManager.getPlayerBlock(p, IRON));
        assertEquals(1, MineManager.getPlayerBlock(p, IRON_BLOCK));

        assertEquals(6, StorageCrafter.craftAll(p));
        assertEquals(1, MineManager.getPlayerBlock(p, IRON), "64 ingots -> 7 blocks, 1 left over");
        assertEquals(7, MineManager.getPlayerBlock(p, IRON_BLOCK));
        assertEquals(0, StorageCrafter.craftAll(p));
        assertEquals(0, StorageCrafter.craftOne(p));
    }

    @Test
    void crafterNeedsTheDonatePermission() {
        assertFalse(StorageCrafter.hasPermission(p));
        when(p.hasPermission("storage.donate.craft")).thenReturn(true);
        assertTrue(StorageCrafter.hasPermission(p));
    }

    @Test
    void settingsSurviveASaveAndLoadWithMaterialKeysContainingSemicolons() {
        String key = "Owner";
        StorageSettings.setLevel(key, 2);
        StorageSettings.setSortMode(key, SortMode.AMOUNT);
        StorageSettings.toggleFilter(key, "WHEAT;0");
        StorageSettings.toggleFilter(key, "WHEAT_SEEDS;0");
        StorageSettings.noteAdded(key, "COAL;0");
        StorageSettings.noteAdded(key, "IRON_INGOT;0");
        String data = "{COAL;0=5, IRON_INGOT;0=7}" + StorageSettings.serialize(key) + ";autopickupoff:COAL;0;toggle:true";

        StorageSettings.remove(key);
        StorageSettings.load(key, data);

        assertEquals(2, StorageSettings.getLevel(key));
        assertEquals(SortMode.AMOUNT, StorageSettings.getSortMode(key));
        assertTrue(StorageSettings.getFilter(key).contains("WHEAT;0"));
        assertTrue(StorageSettings.getFilter(key).contains("WHEAT_SEEDS;0"));
        assertEquals(java.util.List.of("COAL;0", "IRON_INGOT;0"), StorageSettings.insertionOrder(key));
    }

    @Test
    void oldOrCorruptDataFallsBackToDefaults() {
        StorageSettings.load("Old", "{COAL;0=5};toggle:true");
        assertEquals(0, StorageSettings.getLevel("Old"));
        assertEquals(SortMode.INSERTION, StorageSettings.getSortMode("Old"));
        assertTrue(StorageSettings.getFilter("Old").isEmpty());

        StorageSettings.load("Bad", "{};upgrade:abc;sort:NOPE;toggle:true");
        assertEquals(0, StorageSettings.getLevel("Bad"));
        assertEquals(SortMode.INSERTION, StorageSettings.getSortMode("Bad"));
    }

    @Test
    void emptyFilterAcceptsEverythingAndAFilterOnlyItsItems() {
        String key = "Farmer";
        assertTrue(StorageSettings.isFiltered(key, "WHEAT;0"));

        StorageSettings.toggleFilter(key, "WHEAT;0");
        StorageSettings.toggleFilter(key, "WHEAT_SEEDS;0");
        assertTrue(StorageSettings.isFiltered(key, "WHEAT;0"));
        assertTrue(StorageSettings.isFiltered(key, "WHEAT_SEEDS;0"));
        assertFalse(StorageSettings.isFiltered(key, "COAL;0"));

        StorageSettings.clearFilter(key);
        assertTrue(StorageSettings.isFiltered(key, "COAL;0"));
    }
}
