package net.danh.storage.Manager.Island;

import net.danh.storage.Database.Database;
import net.danh.storage.Database.PlayerData;
import net.danh.storage.Manager.CurrencyProvider;
import net.danh.storage.Manager.CurrencyProvider.Currency;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageUpgrade;
import net.danh.storage.Manager.StorageUpgrade.Result;
import net.danh.storage.Manager.UpgradeRequirements;
import net.danh.storage.Manager.UpgradeRequirements.Cost;
import net.danh.storage.Storage;
import net.danh.storage.Utils.File;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static net.danh.storage.Manager.Island.IslandStorageManagerTest.player;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Items, experience and money as upgrade requirements: parsing, checking, taking and putting back.
 */
class UpgradeRequirementsTest {

    private static final String EVERYTHING = "requirements:\n"
            + "  vault: 100\n"
            + "  playerpoints: 10\n"
            + "  exp: {type: LEVELS, amount: 5}\n"
            + "  items:\n"
            + "    - {material: DIAMOND, amount: 32}\n";

    private final YamlConfiguration config = new YamlConfiguration();
    private final YamlConfiguration upgrades = new YamlConfiguration();
    private final Player p = player("Miner");
    private final Database db = mock(Database.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    private final ItemStack[] slots = new ItemStack[36];
    private final List<String> warnings = new ArrayList<>();
    private final Money vault = new Money();
    private final Money points = new Money();
    private int level;
    private float exp;
    private int total;
    private boolean dbKeepsUpgrade = true;
    private MockedStatic<File> fileMock;
    private Database previousDb;

    private static final class Money implements CurrencyProvider {
        boolean available = true;
        boolean failWithdraw;
        double balance;

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
            if (failWithdraw || balance < amount) {
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
        fileMock = mockStatic(File.class);
        fileMock.when(File::getConfig).thenReturn(config);
        fileMock.when(File::getUpgradesConfig).thenReturn(upgrades);
        fileMock.when(() -> File.resolveMaxStorage(any(), anyString(), anyInt(), anyInt())).thenCallRealMethod();
        CurrencyProvider.register(Currency.VAULT, vault);
        CurrencyProvider.register(Currency.PLAYERPOINTS, points);
        previousDb = Storage.db;
        Storage.db = db;
        when(db.getData(anyString())).thenAnswer(i -> new PlayerData(i.getArgument(0),
                "{}" + (dbKeepsUpgrade ? ";upgrade:" + StorageSettings.getLevel(i.getArgument(0)) : ""), 1000, true));
        when(p.hasPermission(anyString())).thenReturn(false);
        wireInventory();
        wireExperience();
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

    // ---------- fakes ----------

    private ItemStack stack(Material material, int amount, boolean meta) {
        ItemStack stack = mock(ItemStack.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        AtomicInteger count = new AtomicInteger(amount);
        when(stack.getType()).thenReturn(material);
        when(stack.hasItemMeta()).thenReturn(meta);
        when(stack.getAmount()).thenAnswer(i -> count.get());
        doAnswer(i -> {
            count.set(i.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        return stack;
    }

    private void give(int slot, Material material, int amount, boolean meta) {
        slots[slot] = stack(material, amount, meta);
    }

    private int held(Material material) {
        int sum = 0;
        for (ItemStack s : slots) {
            if (s != null && s.getType() == material) {
                sum += s.getAmount();
            }
        }
        return sum;
    }

    private void wireInventory() {
        PlayerInventory inventory = mock(PlayerInventory.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(p.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(i -> slots.clone());
        when(inventory.getItem(anyInt())).thenAnswer(i -> slots[(int) i.getArgument(0)]);
        doAnswer(i -> {
            slots[i.getArgument(0)] = i.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(i -> {
            ItemStack given = i.getArgument(0);
            for (int s = 0; s < slots.length; s++) {
                if (slots[s] == null) {
                    slots[s] = stack(given.getType(), given.getAmount(), false);
                    return new HashMap<Integer, ItemStack>();
                }
            }
            return new HashMap<>(java.util.Map.of(0, given));
        });
    }

    private static int toNext(int level) {
        return level <= 15 ? 2 * level + 7 : level <= 30 ? 5 * level - 38 : 9 * level - 158;
    }

    private void wireExperience() {
        when(p.getLevel()).thenAnswer(i -> level);
        when(p.getExp()).thenAnswer(i -> exp);
        when(p.getTotalExperience()).thenAnswer(i -> total);
        doAnswer(i -> {
            level = i.getArgument(0);
            return null;
        }).when(p).setLevel(anyInt());
        doAnswer(i -> {
            exp = i.getArgument(0);
            return null;
        }).when(p).setExp(anyFloat());
        doAnswer(i -> {
            total = i.getArgument(0);
            return null;
        }).when(p).setTotalExperience(anyInt());
        doAnswer(i -> { // vanilla level-up rules
            int pending = i.getArgument(0);
            float points = exp * toNext(level) + pending;
            while (points >= toNext(level)) {
                points -= toNext(level);
                level++;
            }
            exp = points / toNext(level);
            total += pending;
            return null;
        }).when(p).giveExp(anyInt());
    }

    private YamlConfiguration yaml(String text) {
        YamlConfiguration section = new YamlConfiguration();
        try {
            section.loadFromString(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return section;
    }

    private Cost cost(String text) {
        return UpgradeRequirements.parse(yaml(text), 1, warnings::add);
    }

    private void configureLevel1(String text) {
        upgrades.set("levels.1.capacity", 5000);
        upgrades.set("levels.1.requirements", yaml(text).getConfigurationSection("requirements"));
        StorageUpgrade.reload();
    }

    // ---------- parsing ----------

    @Test
    void parsesEveryKindAndMergesRepeatedMaterials() {
        Cost c = cost("requirements:\n"
                + "  vault: 50000\n"
                + "  playerpoints: 5\n"
                + "  exp: {type: POINTS, amount: 1000}\n"
                + "  items:\n"
                + "    - {material: diamond, amount: 20}\n"
                + "    - {material: DIAMOND, amount: 12}\n"
                + "    - {material: NETHERITE_INGOT, amount: 100}\n");
        assertTrue(c.valid(), warnings.toString());
        assertEquals(50000, c.vault());
        assertEquals(5, c.points());
        assertEquals(UpgradeRequirements.ExpType.POINTS, c.expType());
        assertEquals(1000, c.exp());
        assertEquals(2, c.items().size());
        assertEquals(32, c.items().get(0).amount(), "same material is added together");
        assertEquals(100, c.items().get(1).amount(), "more than one stack is fine");
    }

    @Test
    void badValuesMakeTheLevelUnbuyableInsteadOfFree() {
        assertFalse(cost("requirements: {vault: -5}").valid());
        assertFalse(cost("requirements: {vault: abc}").valid());
        assertFalse(cost("requirements: {playerpoints: .nan}").valid());
        assertFalse(cost("requirements: {vault: .inf}").valid());
        assertFalse(cost("requirements: {exp: {type: BANANA, amount: 5}}").valid());
        assertFalse(cost("requirements: {items: [{material: NOT_A_THING, amount: 5}]}").valid());
        assertFalse(cost("requirements: {items: [{material: DIAMOND, amount: 0}]}").valid());
        assertFalse(cost("requirements: {items: [{material: DIAMOND, amount: 99999}]}").valid());
        assertFalse(warnings.isEmpty(), "each problem is reported");
    }

    @Test
    void oldCurrencyAndPriceKeepTheirCost() {
        Cost vaultCost = cost("currency: VAULT\nprice: 1500.5");
        assertEquals(1500.5, vaultCost.vault());
        assertEquals(0, vaultCost.points());
        Cost pointsCost = cost("currency: PLAYERPOINTS\nprice: 120");
        assertEquals(0, pointsCost.vault());
        assertEquals(120, pointsCost.points());
        assertFalse(cost("currency: GEMS\nprice: 5").valid());
    }

    @Test
    void whenBothExistRequirementsWinSoNothingIsChargedTwice() {
        Cost c = cost("currency: VAULT\nprice: 999\nrequirements: {vault: 10}");
        assertEquals(10, c.vault());
        assertEquals(0, c.points());
    }

    // ---------- items ----------

    @Test
    void stacksAreAddedAndExactlyTheRequiredAmountIsTaken() {
        give(0, Material.DIAMOND, 20, false);
        give(5, Material.DIAMOND, 12, false);
        give(9, Material.DIAMOND, 8, false);

        assertEquals(40, UpgradeRequirements.count(p, Material.DIAMOND));
        assertTrue(UpgradeRequirements.removeItems(p, Material.DIAMOND, 32));

        assertEquals(8, held(Material.DIAMOND));
        assertNull(slots[0]);
        assertNull(slots[5]);
        assertEquals(8, slots[9].getAmount());
    }

    @Test
    void oneItemShortTakesNothing() {
        give(0, Material.DIAMOND, 20, false);
        give(1, Material.DIAMOND, 11, false);

        assertFalse(UpgradeRequirements.removeItems(p, Material.DIAMOND, 32));

        assertEquals(31, held(Material.DIAMOND));
    }

    @Test
    void renamedOrCustomItemsDoNotCount() {
        give(0, Material.DIAMOND, 16, false);
        give(1, Material.DIAMOND, 64, true);

        assertEquals(16, UpgradeRequirements.count(p, Material.DIAMOND));
        assertFalse(UpgradeRequirements.removeItems(p, Material.DIAMOND, 17));
        assertEquals(80, held(Material.DIAMOND));
    }

    // ---------- experience ----------

    @Test
    void totalExperienceFollowsTheVanillaCurve() {
        level = 16;
        assertEquals(352, UpgradeRequirements.totalExp(p));
        level = 17;
        assertEquals(394, UpgradeRequirements.totalExp(p));
        level = 30;
        assertEquals(1395, UpgradeRequirements.totalExp(p));
        level = 31;
        assertEquals(1507, UpgradeRequirements.totalExp(p));
        level = 10;
        exp = 0.5f;
        total = 9999; // getTotalExperience() is not trusted
        assertEquals(160 + 14, UpgradeRequirements.totalExp(p));
    }

    @Test
    void experienceLevelsAreNotExperiencePoints() {
        level = 20;
        Cost levels = cost("requirements: {exp: {type: LEVELS, amount: 30}}");
        Cost pointsCost = cost("requirements: {exp: {type: POINTS, amount: 30}}");

        assertFalse(UpgradeRequirements.evaluate(p, levels).ok(), "level 20 is not 30 levels");
        assertTrue(UpgradeRequirements.evaluate(p, pointsCost).ok(), "level 20 is far more than 30 points");
    }

    @Test
    void takingLevelsKeepsTheProgressBar() {
        level = 10;
        exp = 0.5f;
        var steps = UpgradeRequirements.steps(p, cost("requirements: {exp: {type: LEVELS, amount: 3}}"));

        assertTrue(steps.get(0).take().getAsBoolean());
        assertEquals(7, level);
        assertEquals(0.5f, exp);
        assertTrue(steps.get(0).undo().getAsBoolean());
        assertEquals(10, level);
    }

    @Test
    void takingPointsLeavesExactlyTheRemainderAndCanBeUndone() {
        level = 10;
        exp = 0.5f; // 174 points
        var steps = UpgradeRequirements.steps(p, cost("requirements: {exp: {type: POINTS, amount: 100}}"));

        assertTrue(steps.get(0).take().getAsBoolean());
        assertEquals(74, UpgradeRequirements.totalExp(p));
        assertEquals(6, level);

        assertTrue(steps.get(0).undo().getAsBoolean());
        assertEquals(10, level);
        assertEquals(0.5f, exp);
        assertEquals(174, UpgradeRequirements.totalExp(p));
    }

    @Test
    void notEnoughPointsTakesNothing() {
        level = 5;
        exp = 0f; // 55 points
        var steps = UpgradeRequirements.steps(p, cost("requirements: {exp: {type: POINTS, amount: 56}}"));

        assertFalse(steps.get(0).take().getAsBoolean());
        assertEquals(5, level);
    }

    // ---------- whole purchase ----------

    private void richPlayer() {
        vault.balance = 500;
        points.balance = 50;
        level = 12;
        exp = 0.25f;
        java.util.Arrays.fill(slots, null);
        give(0, Material.DIAMOND, 20, false);
        give(1, Material.DIAMOND, 15, false);
    }

    private void assertUntouched(int expectedLevel) {
        assertEquals(500, vault.balance);
        assertEquals(50, points.balance);
        assertEquals(expectedLevel, level);
        assertEquals(35, held(Material.DIAMOND));
        assertEquals(0, StorageUpgrade.currentLevel(p));
    }

    @Test
    void payingWithEverythingTakesExactlyWhatIsAskedAndRaisesTheLevel() {
        configureLevel1(EVERYTHING);
        richPlayer();

        assertEquals(Result.SUCCESS, StorageUpgrade.purchase(p, 1));

        assertEquals(1, StorageUpgrade.currentLevel(p));
        assertEquals(400, vault.balance);
        assertEquals(40, points.balance);
        assertEquals(7, level);
        assertEquals(0.25f, exp);
        assertEquals(3, held(Material.DIAMOND));
        assertEquals(Result.MAX_LEVEL, StorageUpgrade.purchase(p, 1));
        assertEquals(400, vault.balance, "a second click charges nothing");
    }

    @Test
    void missingAnyOneRequirementTakesNothingAtAll() {
        configureLevel1(EVERYTHING);

        richPlayer();
        level = 4;
        assertEquals(Result.NO_EXP, StorageUpgrade.purchase(p, 1));
        assertUntouched(4);

        richPlayer();
        slots[1] = null; // only 20 diamonds left
        assertEquals(Result.NO_ITEMS, StorageUpgrade.purchase(p, 1));
        assertEquals(500, vault.balance);
        assertEquals(50, points.balance);
        assertEquals(12, level);
        assertEquals(20, held(Material.DIAMOND));

        richPlayer();
        vault.balance = 99;
        assertEquals(Result.NO_MONEY, StorageUpgrade.purchase(p, 1));
        assertEquals(99, vault.balance);
        assertEquals(50, points.balance);
        assertEquals(35, held(Material.DIAMOND));
        assertEquals(12, level);

        richPlayer();
        level = 1;
        vault.balance = 0;
        assertEquals(Result.NO_RESOURCES, StorageUpgrade.purchase(p, 1));
        assertEquals(0, StorageUpgrade.currentLevel(p));
    }

    @Test
    void aPaymentThatFailsHalfWayPutsEverythingBack() {
        configureLevel1(EVERYTHING);
        richPlayer();
        points.failWithdraw = true; // items, exp and Vault were already taken

        assertEquals(Result.PAYMENT_FAILED, StorageUpgrade.purchase(p, 1));

        assertUntouched(12);
        assertEquals(0.25f, exp);
    }

    @Test
    void aFailedSaveReturnsItemsExperienceAndMoney() {
        configureLevel1(EVERYTHING);
        richPlayer();
        dbKeepsUpgrade = false;

        assertEquals(Result.SAVE_FAILED, StorageUpgrade.purchase(p, 1));

        assertUntouched(12);
        assertEquals(0.25f, exp);
    }

    @Test
    void missingProviderBlocksOnlyLevelsThatNeedIt() {
        configureLevel1("requirements: {items: [{material: DIAMOND, amount: 1}]}");
        vault.available = false;
        points.available = false;
        give(0, Material.DIAMOND, 1, false);

        assertEquals(Result.SUCCESS, StorageUpgrade.check(p, 1), "items only: no economy needed");

        configureLevel1("requirements: {vault: 10, items: [{material: DIAMOND, amount: 1}]}");
        assertEquals(Result.NO_PROVIDER, StorageUpgrade.purchase(p, 1));
        assertEquals(1, held(Material.DIAMOND));
    }

    @Test
    void aLevelWithABrokenRequirementCannotBeBought() {
        configureLevel1("requirements: {vault: -1}");
        vault.balance = 1_000_000;

        assertEquals(Result.BAD_CONFIG, StorageUpgrade.purchase(p, 1));
        assertEquals(0, StorageUpgrade.currentLevel(p));
    }
}
