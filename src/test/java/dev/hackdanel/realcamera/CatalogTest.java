package dev.hackdanel.realcamera;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Consistency of the datapack catalog: every reference resolves, adapters are physically possible, every
 * entry has a model and names in both languages, and every non-built-in item can be crafted.
 */
class CatalogTest {
    private static final Path PROJECT = Path.of(System.getProperty("realcamera.projectDir", "."));
    private static final Path DATA = PROJECT.resolve("src/main/resources/data/realcamera/realcamera");
    private static final Path ASSETS = PROJECT.resolve("src/main/resources/assets/realcamera");
    private static final Path RECIPES = PROJECT.resolve("src/main/resources/data/realcamera/recipe");

    private static Map<String, JsonObject> load(String registry) throws IOException {
        Map<String, JsonObject> out = new HashMap<>();
        try (Stream<Path> files = Files.list(DATA.resolve(registry))) {
            for (Path p : files.toList()) {
                String key = "realcamera:" + p.getFileName().toString().replace(".json", "");
                out.put(key, JsonParser.parseString(Files.readString(p)).getAsJsonObject());
            }
        }
        return out;
    }

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(ASSETS.resolve("lang/" + code + ".json"))).getAsJsonObject();
    }

    private static void modelExists(String rl, List<String> problems, String who) {
        String path = rl.substring(rl.indexOf(':') + 1);
        if (!Files.exists(ASSETS.resolve("models/" + path + ".json"))) {
            problems.add(who + ": missing model " + rl);
        }
    }

    @Test
    void everyReferenceResolves() throws IOException {
        var mounts = load("mount");
        var batteries = load("battery_type");
        var profiles = load("color_profile");
        var lenses = load("lens");
        var bodies = load("camera_body");
        var cards = load("memory_card_type");
        List<String> problems = new ArrayList<>();
        java.util.Set<String> forms = new java.util.HashSet<>();
        cards.values().forEach(c -> forms.add(c.get("form").getAsString()));
        for (var e : bodies.entrySet()) {
            JsonObject b = e.getValue();
            if (!mounts.containsKey(b.get("mount").getAsString())) problems.add(e.getKey() + ": mount");
            if (!batteries.containsKey(b.get("battery_type").getAsString())) problems.add(e.getKey() + ": battery");
            for (JsonElement p : b.getAsJsonArray("color_profiles")) {
                if (!profiles.containsKey(p.getAsString())) problems.add(e.getKey() + ": profile " + p);
            }
            if (b.has("card_forms")) {
                for (JsonElement f : b.getAsJsonArray("card_forms")) {
                    if (!forms.contains(f.getAsString())) problems.add(e.getKey() + ": no card of form " + f);
                }
            }
            if (b.has("fixed_lens")) {
                JsonObject l = lenses.get(b.get("fixed_lens").getAsString());
                if (l == null) problems.add(e.getKey() + ": fixed lens");
                else if (!l.get("mount").getAsString().equals(b.get("mount").getAsString())) problems.add(e.getKey() + ": fixed lens mount");
            }
            // every interchangeable-lens body has at least one native lens
            String mount = b.get("mount").getAsString();
            if (lenses.values().stream().noneMatch(l -> l.get("mount").getAsString().equals(mount))) {
                problems.add(e.getKey() + ": no lens for " + mount);
            }
            modelExists(b.get("model").getAsString(), problems, e.getKey());
        }
        for (var e : lenses.entrySet()) {
            JsonObject l = e.getValue();
            if (!mounts.containsKey(l.get("mount").getAsString())) problems.add(e.getKey() + ": mount");
            modelExists(l.get("model").getAsString(), problems, e.getKey());
            modelExists(l.get("model").getAsString() + "_mounted", problems, e.getKey());
            float wide = l.get("aperture_wide").getAsFloat(), min = l.has("aperture_min") ? l.get("aperture_min").getAsFloat() : 22;
            if (min < wide) problems.add(e.getKey() + ": aperture range");
        }
        for (var e : load("mount_adapter").entrySet()) {
            JsonObject a = e.getValue();
            JsonObject from = mounts.get(a.get("from").getAsString()), to = mounts.get(a.get("to").getAsString());
            if (from == null || to == null) {
                problems.add(e.getKey() + ": mount");
                continue;
            }
            if (from.get("flange_mm").getAsFloat() <= to.get("flange_mm").getAsFloat()) {
                problems.add(e.getKey() + ": physically impossible (flange)");
            }
            modelExists(a.get("model").getAsString() + "_mounted", problems, e.getKey());
        }
        for (var e : load("teleconverter").entrySet()) {
            if (!mounts.containsKey(e.getValue().get("mount").getAsString())) problems.add(e.getKey() + ": mount");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(bodies.size() >= 30, "bodies " + bodies.size());
        assertTrue(lenses.size() >= 50, "lenses " + lenses.size());
    }

    @Test
    void everyEntryHasNamesAndRecipes() throws IOException {
        JsonObject en = lang("en_us"), ru = lang("ru_ru");
        String recipes;
        try (Stream<Path> files = Files.list(RECIPES)) {
            StringBuilder sb = new StringBuilder();
            for (Path p : files.toList()) sb.append(Files.readString(p));
            recipes = sb.toString();
        }
        List<String> problems = new ArrayList<>();
        for (String reg : List.of("camera_body", "lens", "mount_adapter", "teleconverter", "battery_type", "mount")) {
            for (var e : load(reg).entrySet()) {
                String key = "realcamera." + reg + "." + e.getKey().replace(':', '.');
                if (!en.has(key)) problems.add("en " + key);
                if (!ru.has(key)) problems.add("ru " + key);
                boolean builtIn = reg.equals("lens") && e.getValue().get("mount").getAsString().equals("realcamera:fixed");
                if (!reg.equals("mount") && !builtIn && !recipes.contains("\"" + e.getKey() + "\"")) {
                    problems.add("no recipe for " + e.getKey());
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Issues #5, #8: crop Canon bodies and APS-C Canon lenses show EF-S / RF-S (SpecNames.mount), not EF / RF. */
    @Test
    void canonApscMountsHaveTheirNames() throws IOException {
        JsonObject en = lang("en_us"), ru = lang("ru_ru");
        for (String mount : List.of("canon_ef", "canon_rf")) {
            String key = "realcamera.mount.realcamera." + mount + ".apsc";
            String name = en.has(key) ? en.get(key).getAsString() : "";
            assertTrue(name.endsWith("-S") && ru.has(key), key + " = " + name);
        }
    }
}
