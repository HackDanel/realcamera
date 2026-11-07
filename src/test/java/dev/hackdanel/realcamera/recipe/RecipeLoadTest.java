package dev.hackdanel.realcamera.recipe;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Every workshop recipe must load in the game: at most 6 ingredients (the workshop's slots), known items, and a
 * result count the item can stack to (a film roll, a body or a lens never stacks). A recipe that fails here is
 * silently skipped by the game, so the item cannot be crafted.
 */
class RecipeLoadTest {
    private static final Path RECIPES = Path.of(System.getProperty("realcamera.projectDir", "."))
            .resolve("src/main/resources/data/realcamera/recipe");

    @Test
    void everyAssemblyRecipeDecodes() throws IOException {
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        List<String> problems = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(RECIPES)) {
            for (Path p : files.sorted().toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if (!"realcamera:assembly".equals(json.get("type").getAsString())) continue;
                checked++;
                var result = AssemblyRecipe.CODEC.codec().parse(ops, json);
                if (result.error().isPresent()) {
                    problems.add(p.getFileName() + ": " + result.error().get().message());
                    continue;
                }
                AssemblyRecipe r = result.getOrThrow();
                if (r.result().getCount() > r.result().getMaxStackSize()) {
                    problems.add(p.getFileName() + ": makes " + r.result().getCount() + " of an item that stacks to "
                            + r.result().getMaxStackSize());
                }
                for (var ing : r.inputs()) {
                    if (ing.ingredient().isEmpty()) problems.add(p.getFileName() + ": unknown ingredient");
                }
            }
        }
        assertTrue(checked > 100, "only " + checked + " recipes checked");
        assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("realcamera", "film_roll")),
                "mod items are not registered in the test environment");
        assertTrue(problems.isEmpty(), problems.size() + " recipes do not load:\n" + String.join("\n", problems));
    }
}
