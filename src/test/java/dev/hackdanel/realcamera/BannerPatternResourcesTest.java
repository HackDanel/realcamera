package dev.hackdanel.realcamera;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Resource-side wiring for the loom: item model, both pattern atlases, tag and recipe. */
class BannerPatternResourcesTest {
    private static final Path PROJECT = Path.of(System.getProperty("realcamera.projectDir", "."));
    private static final Path RESOURCES = PROJECT.resolve("src/main/resources");
    private static final List<String> COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    @Test
    void everyBannerPatternIsUsable() throws IOException {
        JsonObject en = lang("en_us"), ru = lang("ru_ru");
        List<String> problems = new ArrayList<>();
        for (String id : List.of("aperture_star", "shutter_125")) {
            require("assets/realcamera/models/item/banner_pattern_" + id + ".json", problems);
            require("assets/realcamera/textures/item/banner_pattern_" + id + ".png", problems);
            requireMask("assets/realcamera/textures/entity/banner/" + id + ".png", problems);
            requireMask("assets/realcamera/textures/entity/shield/" + id + ".png", problems);
            require("data/realcamera/banner_pattern/" + id + ".json", problems);
            require("data/realcamera/tags/banner_pattern/pattern_item/" + id + ".json", problems);
            require("data/realcamera/recipe/banner_pattern_" + id + ".json", problems);
            JsonObject registry = json("data/realcamera/banner_pattern/" + id + ".json");
            if (!registry.get("asset_id").getAsString().equals("realcamera:" + id)) {
                problems.add("wrong banner asset " + id);
            }
            JsonObject recipe = json("data/realcamera/recipe/banner_pattern_" + id + ".json");
            if (!recipe.get("type").getAsString().equals("minecraft:crafting_shapeless")) {
                problems.add("banner pattern is not crafted at a vanilla crafting table: " + id);
            }
            String base = "block.realcamera.banner." + id;
            if (!en.has(base) || !ru.has(base)) problems.add("missing base translation " + base);
            for (String color : COLORS) {
                String key = base + "." + color;
                if (!en.has(key) || !ru.has(key)) problems.add("missing colored translation " + key);
            }
            String desc = "item.realcamera.banner_pattern_" + id + ".desc";
            if (!en.has(desc) || !ru.has(desc)) problems.add("missing item description " + desc);
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static JsonObject lang(String code) throws IOException {
        return json("assets/realcamera/lang/" + code + ".json");
    }

    private static JsonObject json(String path) throws IOException {
        return JsonParser.parseString(Files.readString(RESOURCES.resolve(path))).getAsJsonObject();
    }

    private static void require(String path, List<String> problems) {
        if (!Files.exists(RESOURCES.resolve(path))) problems.add("missing " + path);
    }

    private static void requireMask(String path, List<String> problems) throws IOException {
        Path file = RESOURCES.resolve(path);
        if (!Files.exists(file)) {
            problems.add("missing " + path);
            return;
        }
        var image = ImageIO.read(file.toFile());
        if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
            problems.add("banner mask is not 64x64: " + path);
            return;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0 && (x > 20 || y > 40)) {
                    problems.add("banner mask leaves the visible cloth area: " + path);
                    return;
                }
            }
        }
    }
}
