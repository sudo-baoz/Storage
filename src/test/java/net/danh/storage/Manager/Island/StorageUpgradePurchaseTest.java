package net.danh.storage.Manager.Island;

import net.danh.storage.Database.Database;
import net.danh.storage.Database.PlayerData;
import net.danh.storage.Manager.CurrencyProvider;
import net.danh.storage.Manager.CurrencyProvider.Currency;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Manager.StorageUpgrade.Result;
import net.danh.storage.Storage;
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
 * The upgrade rules, the payment flow and the config validation, with fake economies and a mocked database.
 */
class StorageUpgradePurchaseTest {

    private final YamlConfiguration config = new YamlConfiguration();
    private final YamlConfiguration upgrades = new YamlConfiguration();
    private final Player p = player("Miner");
    private final Fake vault = new Fake(true, 0);
    private final Fake points = new Fake(true, 0);
    private final Database db = mock(Database.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    private MockedStatic<File> fileMock;
    private Database previousDb;
    private boolean dbKeepsUpgrade = true;

    private static final class Fake implements CurrencyProvider {
        boolean available;
        double balance;

        Fake(boolean available, double balance) {
            this.available = available;
            this.balance = balance;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public double balance(Player player) {
            return balance;
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            if (balance < amount) {
                return false;
            }
            balance -= amount;
            return true;
        }

        @Override
        public boolean deposit(Player player, double amount) {
            balance += amount;
            return true;
        }
    }

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
        int[] capacity = {10000, 25000, 50000, 100000, 250000, 500000};
        for (int i = 0; i < 6; i++) {
            String path = "levels." + (i + 1) + ".";
            upgrades.set(path + "capacity", capacity[i]);
            upgrades.set(path + "currency", i < 3 ? "VAULT" : "PLAYERPOINTS");
            upgrades.set(path + "price", i < 3 ? 1000.5 * (i + 1) : 100 * (i - 2));
        }
        fileMock = mockStatic(File.class);
        fileMock.when(File::getConfig).thenReturn(config);
        fileMock.when(File::getUpgradesConfig).thenReturn(upgrades);
        fileMock.when(() -> File.resolveMaxStorage(any(), anyString(), anyInt(), anyInt())).thenCallRealMethod();
        StorageUpgrade.reload();
        CurrencyProvider.register(Currency.VAULT, vault);
        CurrencyProvider.register(Currency.PLAYERPOINTS, points);
        previousDb = Storage.db;
        Storage.db = db;
        when(db.getData(anyString())).thenAnswer(i -> new PlayerData(i.getArgument(0),
                "{}" + (dbKeepsUpgrade ? ";upgrade:" + StorageSettings.getLevel(i.getArgument(0)) : ""), 1000, true));
        when(p.hasPermission(anyString())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        CurrencyProvider.register(Currency.VAULT, null);
        CurrencyProvider.register(Currency.PLAYERPOINTS, null);
        StorageSettings.clearAll();
        MineManager.cleanupPlayerData(p);
        Storage.db = previousDb;
        fileMock.close();
    }

    @Test
    void levelsMustBeBoughtOneByOne() {
        vault.balance = 1_000_000;
        assertEquals(Result.LOCKED, StorageUpgrade.check(p, 2));
        assertEquals(Result.SUCCESS, StorageUpgrade.check(p, 1));
        assertEquals(Result.INVALID, StorageUpgrade.check(p, 7));
    }

    @Test
    void buyingChargesOnceRaisesCapacityAndCannotBeRepeated() {
        vault.balance = 5000;

        assertEquals(Result.SUCCESS, StorageUpgrade.purchase(p, 1));

        assertEquals(3999.5, vault.balance, 1e-9);
        assertEquals(1, StorageUpgrade.currentLevel(p));
        assertEquals(10000, MineManager.getMaxBlock(p));
        assertEquals(Result.ALREADY_OWNED, StorageUpgrade.purchase(p, 1));
        assertEquals(3999.5, vault.balance, 1e-9, "no second charge");
    }

    @Test
    void notEnoughMoneyChangesNothing() {
        vault.balance = 10;

        assertEquals(Result.NO_MONEY, StorageUpgrade.purchase(p, 1));

        assertEquals(10, vault.balance, 1e-9);
        assertEquals(0, StorageUpgrade.currentLevel(p));
    }

    @Test
    void missingProviderBlocksOnlyTheLevelsThatUseIt() {
        vault.available = false;
        points.available = false;
        vault.balance = 1_000_000;

        assertEquals(Result.NO_PROVIDER, StorageUpgrade.purchase(p, 1));
        assertEquals(1_000_000, vault.balance, 1e-9);

        vault.available = true;
        assertEquals(Result.SUCCESS, StorageUpgrade.purchase(p, 1));
    }

    @Test
    void permissionIsRequiredWhenConfigured() {
        upgrades.set("levels.1.permission", "storage.upgrade.vip");
        StorageUpgrade.reload();
        vault.balance = 5000;

        assertEquals(Result.NO_PERMISSION, StorageUpgrade.purchase(p, 1));
        when(p.hasPermission("storage.upgrade.vip")).thenReturn(true);
        assertEquals(Result.SUCCESS, StorageUpgrade.purchase(p, 1));
    }

    @Test
    void failedSaveRollsTheLevelBackAndRefunds() {
        vault.balance = 5000;
        dbKeepsUpgrade = false;

        assertEquals(Result.SAVE_FAILED, StorageUpgrade.purchase(p, 1));

        assertEquals(0, StorageUpgrade.currentLevel(p));
        assertEquals(5000, vault.balance, 1e-9, "payment refunded");
    }

    @Test
    void sixLevelsEndAtMaxAndPointsPriceIsWhole() {
        vault.balance = 1_000_000;
        points.balance = 10_000;
        for (int level = 1; level <= 6; level++) {
            assertEquals(Result.SUCCESS, StorageUpgrade.purchase(p, level), "level " + level);
        }
        assertEquals(500000, MineManager.getMaxBlock(p));
        assertEquals(Result.MAX_LEVEL, StorageUpgrade.check(p, 6));
        assertEquals(10_000 - 100 - 200 - 300, points.balance, 1e-9);
        assertEquals(Result.INVALID, StorageUpgrade.check(p, 7));
    }

    @Test
    void invalidConfigIsRepairedNotFatal() {
        upgrades.set("settings.max-level", 3);  // legacy value must not hide levels 4-6
        upgrades.set("levels.3.capacity", 100); // lower than level 2: warned, still available
        upgrades.set("gui.slots.back", 11);     // duplicates the level-1 slot: defaults are used
        StorageUpgrade.reload();

        assertEquals(6, StorageUpgrade.maxLevel());
        upgrades.set("levels.5.capacity", 0);   // missing capacity ends the table at level 4
        StorageUpgrade.reload();
        assertEquals(4, StorageUpgrade.maxLevel());
        assertEquals(39, StorageUpgrade.slot("back"));
        assertEquals(11, StorageUpgrade.slot("level-1"));

        upgrades.set("levels.1.currency", "GEMS");
        StorageUpgrade.reload();
        vault.balance = 1_000_000;
        assertEquals(Result.BAD_CONFIG, StorageUpgrade.check(p, 1));
    }
}
