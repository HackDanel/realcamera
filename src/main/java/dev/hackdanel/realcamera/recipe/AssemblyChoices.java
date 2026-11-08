package dev.hackdanel.realcamera.recipe;

import dev.hackdanel.realcamera.registry.RCRecipes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * Every workshop recipe the inputs can make, in a fixed order shared by the server (which recipe runs)
 * and the client (the choice row in the screen): recipes that use up more items first (the inputs may
 * hold extra items, so a recipe that uses only some of them is the less likely intent), then by id.
 * Many recipes share their ingredients (all step-up rings, filter sizes of one kind), so the player
 * picks one of them in the screen.
 */
public final class AssemblyChoices {
    private static final Comparator<RecipeHolder<AssemblyRecipe>> ORDER =
            Comparator.<RecipeHolder<AssemblyRecipe>>comparingInt(h -> -itemCount(h.value()))
                    .thenComparing(h -> h.id().toString());

    private AssemblyChoices() {
    }

    public static List<RecipeHolder<AssemblyRecipe>> find(RecipeManager recipes, AssemblyInput input, Level level) {
        List<RecipeHolder<AssemblyRecipe>> list = new ArrayList<>(
                recipes.getRecipesFor(RCRecipes.ASSEMBLY_TYPE.get(), input, level));
        list.sort(ORDER);
        return list;
    }

    private static int itemCount(AssemblyRecipe recipe) {
        int n = 0;
        for (SizedIngredient ing : recipe.inputs()) {
            n += ing.count();
        }
        return n;
    }
}
