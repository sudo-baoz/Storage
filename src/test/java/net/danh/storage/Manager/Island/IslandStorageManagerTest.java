package net.danh.storage.Manager.Island;

import net.danh.storage.Manager.Island.IslandStorageManager.IslandInfo;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class IslandStorageManagerTest {

    private final UUID islandId = UUID.randomUUID();
    private final Player owner = player("Owner");
    private final Player member = player("Member");
    private final Player stranger = player("Stranger");

    static Player player(String name) {
        Player player = mock(Player.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private void useIsland() {
        IslandInfo info = new IslandInfo(islandId, owner.getUniqueId(), "Owner");
        IslandStorageManager.setProvider(new IslandStorageManager.Provider() {
            @Override
            public IslandInfo find(UUID playerId) {
                return playerId.equals(owner.getUniqueId()) || playerId.equals(member.getUniqueId()) ? info : null;
            }

            @Override
            public Map<UUID, String> members(IslandInfo island) {
                Map<UUID, String> result = new LinkedHashMap<>();
                result.put(owner.getUniqueId(), "Owner");
                result.put(member.getUniqueId(), "Member");
                return result;
            }
        });
    }

    @AfterEach
    void tearDown() {
        IslandStorageManager.reset();
    }

    @Test
    void withoutProviderEveryoneKeepsPersonalStorageAndCanSell() {
        assertFalse(IslandStorageManager.isEnabled());
        assertEquals("Member", IslandStorageManager.resolveKey(member));
        assertTrue(IslandStorageManager.canSell(member));
    }

    @Test
    void membersResolveToOwnerStorageAndOthersStayPersonal() {
        useIsland();
        assertEquals("Owner", IslandStorageManager.resolveKey(owner));
        assertEquals("Owner", IslandStorageManager.resolveKey(member));
        assertEquals("Stranger", IslandStorageManager.resolveKey(stranger));
        assertEquals("Owner", IslandStorageManager.resolveKey(member.getUniqueId(), "Member"));
        assertEquals("Offline", IslandStorageManager.resolveKey(UUID.randomUUID(), "Offline"));
    }

    @Test
    void memberSellIsDeniedByDefaultAndOwnerAndOutsidersAlwaysSell() {
        useIsland();
        assertFalse(IslandStorageManager.canSell(member));
        assertTrue(IslandStorageManager.canSell(owner));
        assertTrue(IslandStorageManager.canSell(stranger));
    }

    @Test
    void ownerCanAllowAndRevokeSellPerMember() {
        useIsland();
        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), true);
        assertTrue(IslandStorageManager.canSell(member));
        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), false);
        assertFalse(IslandStorageManager.canSell(member));
    }

    @Test
    void configuredDefaultAppliesUntilOwnerOverrides() {
        useIsland();
        IslandStorageManager.setDefaultMemberSell(() -> true);
        assertTrue(IslandStorageManager.canSell(member));
        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), false);
        assertFalse(IslandStorageManager.canSell(member));
    }

    @Test
    void leavingResetsOverrideAndDisbandClearsIsland() {
        useIsland();
        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), true);
        IslandStorageManager.clearMember(islandId, member.getUniqueId());
        assertFalse(IslandStorageManager.canSell(member));

        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), true);
        IslandStorageManager.clearIsland(islandId);
        assertFalse(IslandStorageManager.canSell(member));
    }

    @Test
    void overridesSurviveRestart(@TempDir Path dir) {
        File file = dir.resolve("island-members.yml").toFile();
        IslandStorageManager.load(file);
        useIsland();
        IslandStorageManager.setSellAllowed(islandId, member.getUniqueId(), true);
        assertTrue(file.isFile());

        IslandStorageManager.load(file);
        assertTrue(IslandStorageManager.canSell(member));
    }

    @Test
    void corruptEntriesAreSkippedOnLoad(@TempDir Path dir) throws Exception {
        File file = dir.resolve("island-members.yml").toFile();
        Files.writeString(file.toPath(), "islands:\n  not-a-uuid:\n    also-bad: true\n");
        assertDoesNotThrow(() -> IslandStorageManager.load(file));
    }
}
