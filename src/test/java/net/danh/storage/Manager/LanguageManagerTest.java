package net.danh.storage.Manager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LanguageManagerTest {

    private static YamlConfiguration lang(String code) {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
    }

    private static Map<String, Object> leaves(YamlConfiguration yaml) {
        Map<String, Object> result = new TreeMap<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                result.put(key, yaml.get(key));
            }
        }
        return result;
    }

    @Test
    void bothLanguagesHaveTheSameKeysWithTheSameKinds() {
        Map<String, Object> en = leaves(lang("en_US"));
        Map<String, Object> vi = leaves(lang("vi_VN"));
        assertFalse(en.isEmpty());
        assertEquals(en.keySet(), vi.keySet(), "vi_VN must contain exactly the keys of en_US");
        for (String key : en.keySet()) {
            assertEquals(en.get(key) instanceof List, vi.get(key) instanceof List, "list/string mismatch at " + key);
        }
    }

    @Test
    void translationsKeepTheirPlaceholders() {
        Pattern placeholder = Pattern.compile("#[A-Za-z_]+#");
        Map<String, Object> en = leaves(lang("en_US"));
        Map<String, Object> vi = leaves(lang("vi_VN"));
        for (String key : en.keySet()) {
            if (key.endsWith(".help")) {
                continue; // command help translates the argument names
            }
            assertEquals(placeholders(placeholder, en.get(key)), placeholders(placeholder, vi.get(key)), key);
        }
    }

    private static List<String> placeholders(Pattern pattern, Object value) {
        List<String> found = new ArrayList<>();
        for (Object line : value instanceof List<?> list ? list : List.of(value)) {
            var matcher = pattern.matcher(String.valueOf(line));
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        Collections.sort(found);
        return found;
    }

    @Test
    void vietnameseTextIsReadAsUtf8() {
        assertEquals("&8Kho đồ | #player#", lang("vi_VN").getString("gui.storage.title"));
        assertFalse(lang("vi_VN").getString("user.status.status_on").contains("�"));
    }

    @Test
    void localeCodesAreNormalised() {
        assertEquals("vi_VN", LanguageManager.normalize("vi_vn", null));
        assertEquals("vi_VN", LanguageManager.normalize("vi-VN", null));
        assertEquals("en_US", LanguageManager.normalize("EN_us", null));
        assertEquals("fallback", LanguageManager.normalize("  ", "fallback"));
    }

    @Test
    void missingKeysFallBackToTheOtherLanguage() {
        YamlConfiguration english = new YamlConfiguration();
        english.set("messages.full", "Storage is full");
        english.set("messages.only_english", "English only");
        YamlConfiguration vietnamese = new YamlConfiguration();
        vietnamese.set("messages.full", "Kho đã đầy");

        LanguageManager.Layered layered = new LanguageManager.Layered("vi_VN", vietnamese, english);

        assertEquals("Kho đã đầy", layered.getString("messages.full"));
        assertEquals("English only", layered.getString("messages.only_english"));
        assertTrue(layered.contains("messages.only_english"));
        assertNull(layered.getString("messages.nowhere"));
    }
}
