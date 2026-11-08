package net.danh.storage.GUI;

import net.danh.storage.Manager.StorageSettings;
import net.danh.storage.Manager.StorageSettings.SortMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortOrderTest {

    private static final String KEY = "Sorter";
    private final List<String> config = List.of("COAL;0", "IRON_INGOT;0", "GOLD_INGOT;0", "DIAMOND;0");
    private final Map<String, Integer> amounts = new java.util.HashMap<>(Map.of(
            "COAL;0", 5, "IRON_INGOT;0", 50, "GOLD_INGOT;0", 5, "DIAMOND;0", 50));

    @AfterEach
    void tearDown() {
        StorageSettings.clearAll();
    }

    private List<String> sorted() {
        return PersonalStorage.sortedItems(KEY, config,
                k -> k.split(";")[0].replace("_", " "), k -> amounts.get(k));
    }

    @Test
    void insertionOrderComesFromTheStorageAndFallsBackToConfigOrder() {
        assertEquals(config, sorted());
        StorageSettings.noteAdded(KEY, "DIAMOND;0");
        StorageSettings.noteAdded(KEY, "COAL;0");
        assertEquals(List.of("DIAMOND;0", "COAL;0", "IRON_INGOT;0", "GOLD_INGOT;0"), sorted());
    }

    @Test
    void namesSortBothWays() {
        StorageSettings.setSortMode(KEY, SortMode.NAME_ASC);
        assertEquals(List.of("COAL;0", "DIAMOND;0", "GOLD_INGOT;0", "IRON_INGOT;0"), sorted());
        StorageSettings.setSortMode(KEY, SortMode.NAME_DESC);
        assertEquals(List.of("IRON_INGOT;0", "GOLD_INGOT;0", "DIAMOND;0", "COAL;0"), sorted());
    }

    @Test
    void amountSortsMostFirstAndKeepsTiesInInsertionOrder() {
        StorageSettings.noteAdded(KEY, "DIAMOND;0");
        StorageSettings.noteAdded(KEY, "IRON_INGOT;0");
        StorageSettings.noteAdded(KEY, "GOLD_INGOT;0");
        StorageSettings.noteAdded(KEY, "COAL;0");
        StorageSettings.setSortMode(KEY, SortMode.AMOUNT);

        List<String> first = sorted();
        assertEquals(List.of("DIAMOND;0", "IRON_INGOT;0", "GOLD_INGOT;0", "COAL;0"), first);
        assertEquals(first, sorted(), "reopening must not reshuffle equal items");

        amounts.put("COAL;0", 100);
        assertEquals("COAL;0", sorted().get(0), "amount changes are reflected");
    }

    @Test
    void typeGroupsByMaterialNameWithStableTies() {
        StorageSettings.setSortMode(KEY, SortMode.TYPE);
        assertEquals(List.of("COAL;0", "DIAMOND;0", "GOLD_INGOT;0", "IRON_INGOT;0"), sorted());
    }
}
