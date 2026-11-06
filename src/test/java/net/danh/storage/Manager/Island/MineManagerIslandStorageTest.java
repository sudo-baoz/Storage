package net.danh.storage.Manager.Island;

import net.danh.storage.Database.Database;
import net.danh.storage.Database.PlayerData;
import net.danh.storage.Manager.Island.IslandStorageManager.IslandInfo;
import net.danh.storage.Manager.MineManager;
import net.danh.storage.Storage;
import net.danh.storage.Utils.ChatUtils;
import net.danh.storage.Utils.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.danh.storage.Manager.Island.IslandStorageManagerTest.player;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Drives the real MineManager load/save/cleanup code with a fake island plugin and a mocked database.
 */
class MineManagerIslandStorageTest {

    private static final String COAL = "COAL;0";

    private final UUID islandId = UUID.randomUUID();
    private final Player owner = player("Owner");
    private final Player member = player("Member");
    private final Set<UUID> inIsland = new HashSet<>();
    private final YamlConfiguration config = new YamlConfiguration();
    private final YamlConfiguration messages = new YamlConfiguration();
    private final Database db = mock(Database.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    private MockedStatic<File> fileMock;
    private MockedStatic<ChatUtils> chatMock;
    private Database previousDb;

    @BeforeAll
    static void fakeServer() {
        // MineManager reads the server version in its static initializer.
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
            when(server.getBukkitVersion()).thenReturn("1.20.4-R0.1-SNAPSHOT");
            when(server.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
            Bukkit.setServer(server);
        }
    }

    @BeforeEach
    void setUp() {
        doReturn(new PlayerData("Owner", "{COAL;0=50}", 1000, true)).when(db).getData("Owner");
        doReturn(new PlayerData("Member", "{COAL;0=7}", 1000, true)).when(db).getData("Member");
        previousDb = Storage.db;
        Storage.db = db;
        config.set("settings.default_max_storage", 1000);
        config.set("settings.max_storage_mode", "DATABASE");
        messages.set("user.island.sell_denied", "denied");
        fileMock = mockStatic(File.class);
        fileMock.when(File::getConfig).thenReturn(config);
        fileMock.when(File::getMessage).thenReturn(messages);
        fileMock.when(() -> File.resolveMaxStorage(any(), anyString(), anyInt(), anyInt())).thenCallRealMethod();
        chatMock = mockStatic(ChatUtils.class);
        chatMock.when(() -> ChatUtils.colorize(anyString())).thenAnswer(i -> i.getArgument(0));

        MineManager.addPluginBlocks(COAL);
        inIsland.add(owner.getUniqueId());
        inIsland.add(member.getUniqueId());
        IslandInfo info = new IslandInfo(islandId, owner.getUniqueId(), "Owner");
        IslandStorageManager.setProvider(new IslandStorageManager.Provider() {
            @Override
            public IslandInfo find(UUID playerId) {
                return inIsland.contains(playerId) ? info : null;
            }

            @Override
            public Map<UUID, String> members(IslandInfo island) {
                return Map.of(owner.getUniqueId(), "Owner", member.getUniqueId(), "Member");
            }
        });
    }

    @AfterEach
    void tearDown() {
        MineManager.cleanupPlayerData(owner);
        MineManager.cleanupPlayerData(member);
        MineManager.blocksdata.clear();
        IslandStorageManager.reset();
        Storage.db = previousDb;
        chatMock.close();
        fileMock.close();
    }

    @Test
    void memberUsesTheOwnerRowAndSeesTheSameItems() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);

        assertEquals("Owner", MineManager.storageKey(member));
        assertEquals(50, MineManager.getPlayerBlock(member, COAL));
        verify(db, never()).getData("Member");
    }

    @Test
    void depositsAndWithdrawalsOfEitherMemberChangeOnePool() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);

        assertTrue(MineManager.addBlockAmount(member, COAL, 25, false));
        assertEquals(75, MineManager.getPlayerBlock(owner, COAL));
        assertTrue(MineManager.removeBlockAmount(owner, COAL, 30, false));
        assertEquals(45, MineManager.getPlayerBlock(member, COAL));
    }

    @Test
    void memberJoiningLaterDoesNotOverwriteUnsavedIslandItems() {
        MineManager.loadPlayerData(owner);
        MineManager.addBlockAmount(owner, COAL, 20, false);

        MineManager.loadPlayerData(member);

        assertEquals(70, MineManager.getPlayerBlock(member, COAL));
    }

    @Test
    void storageLimitStillApplies() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);

        assertTrue(MineManager.addBlockAmount(member, COAL, 5000, false));
        assertEquals(1000, MineManager.getPlayerBlock(owner, COAL));
        assertFalse(MineManager.addBlockAmount(owner, COAL, 1, false));
    }

    @Test
    void memberSavesIntoTheOwnerRowNeverTheirOwn() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);
        MineManager.addBlockAmount(member, COAL, 5, false);

        MineManager.savePlayerData(member);

        ArgumentCaptor<PlayerData> saved = ArgumentCaptor.forClass(PlayerData.class);
        verify(db).updateTable(saved.capture());
        assertEquals("Owner", saved.getValue().player());
        assertTrue(saved.getValue().data().contains("COAL;0=55"), saved.getValue().data());
    }

    @Test
    void islandItemsStayLoadedUntilTheLastMemberLeaves() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);

        MineManager.cleanupPlayerData(owner);
        assertEquals(50, MineManager.getPlayerBlock(member, COAL));

        MineManager.cleanupPlayerData(member);
        assertFalse(MineManager.playerdata.containsKey("Owner_" + COAL));
    }

    @Test
    void playerOutsideAnIslandKeepsPersonalStorage() {
        inIsland.remove(member.getUniqueId());
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);

        assertEquals("Member", MineManager.storageKey(member));
        assertEquals(7, MineManager.getPlayerBlock(member, COAL));
        assertEquals(50, MineManager.getPlayerBlock(owner, COAL));
    }

    @Test
    void leavingTheIslandSavesThenSwitchesToPersonalStorage() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);
        MineManager.addBlockAmount(member, COAL, 5, false);

        inIsland.remove(member.getUniqueId());
        MineManager.refreshStorageOwner(member);

        assertEquals("Member", MineManager.storageKey(member));
        assertEquals(7, MineManager.getPlayerBlock(member, COAL));
        assertEquals(55, MineManager.getPlayerBlock(owner, COAL));
        ArgumentCaptor<PlayerData> saved = ArgumentCaptor.forClass(PlayerData.class);
        verify(db).updateTable(saved.capture());
        assertEquals("Owner", saved.getValue().player());
    }

    @Test
    void joiningAnIslandSwitchesToTheOwnerStorage() {
        inIsland.remove(member.getUniqueId());
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);
        assertEquals(7, MineManager.getPlayerBlock(member, COAL));

        inIsland.add(member.getUniqueId());
        MineManager.refreshStorageOwner(member);

        assertEquals("Owner", MineManager.storageKey(member));
        assertEquals(50, MineManager.getPlayerBlock(member, COAL));
    }

    @Test
    void sellIsBlockedForMembersUntilTheOwnerAllowsIt() {
        assertTrue(MineManager.denySell(member));
        verify(member).sendMessage("denied");
        assertFalse(MineManager.denySell(owner));

        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), true);
        assertFalse(MineManager.denySell(member));
    }

    @Test
    void memberCannotEnableAutoSellWithoutSellRight() {
        MineManager.loadPlayerData(owner);
        MineManager.loadPlayerData(member);

        assertFalse(MineManager.toggleItemAutoSell(member, COAL));
        assertFalse(MineManager.isAutoSellEnabledForItem(owner, COAL));
    }
}
