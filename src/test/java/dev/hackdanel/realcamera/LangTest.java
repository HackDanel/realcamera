package dev.hackdanel.realcamera;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Russian and English have the same keys, and every complete translation key written in the code exists. */
class LangTest {
    private static final Path PROJECT = Path.of(System.getProperty("realcamera.projectDir", "."));
    private static final Path LANG = PROJECT.resolve("src/main/resources/assets/realcamera/lang");
    private static final Pattern KEY = Pattern.compile("\"((?:message|tooltip|screen|key|gui|menu|container|recipe_viewer|commands|"
            + "subtitles|item|block|advancements)\\.realcamera\\.[a-z0-9_.]*[a-z0-9_])\"");

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(LANG.resolve(code + ".json"))).getAsJsonObject();
    }

    @Test
    void russianAndEnglishHaveTheSameKeys() throws IOException {
        Set<String> en = new TreeSet<>(lang("en_us").keySet()), ru = new TreeSet<>(lang("ru_ru").keySet());
        Set<String> onlyEn = new TreeSet<>(en), onlyRu = new TreeSet<>(ru);
        onlyEn.removeAll(ru);
        onlyRu.removeAll(en);
        assertTrue(onlyEn.isEmpty() && onlyRu.isEmpty(), "only en: " + onlyEn + "\nonly ru: " + onlyRu);
    }

    @Test
    void keysUsedInCodeExist() throws IOException {
        JsonObject en = lang("en_us");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PROJECT.resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(p));
                while (m.find()) {
                    if (!en.has(m.group(1))) missing.add(m.group(1) + "  (" + p.getFileName() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), String.join("\n", new TreeSet<>(missing)));
    }
}
