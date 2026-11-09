package net.danh.storage.GUI;

import org.junit.jupiter.api.Test;

import static net.danh.storage.GUI.UpgradeGUI.State.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeStateTest {

    @Test
    void ownedAvailableLockedAndMax() {
        assertEquals(AVAILABLE, UpgradeGUI.stateOf(1, 0, 6));
        assertEquals(LOCKED, UpgradeGUI.stateOf(2, 0, 6));
        assertEquals(OWNED, UpgradeGUI.stateOf(1, 2, 6));
        assertEquals(AVAILABLE, UpgradeGUI.stateOf(3, 2, 6));
        assertEquals(LOCKED, UpgradeGUI.stateOf(5, 2, 6));
        assertEquals(OWNED, UpgradeGUI.stateOf(5, 6, 6));
        assertEquals(MAX, UpgradeGUI.stateOf(6, 6, 6));
        assertEquals(AVAILABLE, UpgradeGUI.stateOf(6, 5, 6));
    }
}
