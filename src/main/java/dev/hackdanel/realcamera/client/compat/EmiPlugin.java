package dev.hackdanel.realcamera.client.compat;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** EMI: the photo workshop, film development and darkroom enlarging. Only loaded by EMI itself. */
@EmiEntrypoint
public class EmiPlugin implements dev.emi.emi.api.EmiPlugin {
    private static final java.util.Map<ResourceLocation, EmiRecipeCategory> CATEGORIES = new java.util.HashMap<>();

    /** Autotest probe: recipes EMI shows in a category. */
    public static long count(ResourceLocation id) {
        EmiRecipeCategory c = CATEGORIES.get(id);
        return c == null ? -1 : dev.emi.emi.api.EmiApi.getRecipeManager().getRecipes(c).size();
    }

    public static void open(ResourceLocation id) {
        EmiRecipeCategory c = CATEGORIES.get(id);
        if (c != null) dev.emi.emi.api.EmiApi.displayRecipeCategory(c);
    }

    @Override
    public void register(EmiRegistry registry) {
        for (var item : RecipeViews.subtypedItems()) {
            registry.setDefaultComparison(item, Comparison.compareData(s -> RecipeViews.subtype(s.getItemStack())));
        }
        for (ResourceLocation id : RecipeViews.CATEGORIES) {
            EmiRecipeCategory category = new EmiRecipeCategory(id, EmiStack.of(RecipeViews.icon(id))) {
                @Override
                public Component getName() {
                    return RecipeViews.title(id);
                }
            };
            registry.addCategory(category);
            CATEGORIES.put(id, category);
            for (var w : RecipeViews.workstations(id)) {
                registry.addWorkstation(category, EmiStack.of(w));
            }
            for (RecipeViews.View view : RecipeViews.all(id)) {
                registry.addRecipe(new Recipe(category, view));
            }
        }
    }

    private static final class Recipe extends BasicEmiRecipe {
        private final RecipeViews.View view;

        Recipe(EmiRecipeCategory category, RecipeViews.View view) {
            super(category, view.id(), 128, 52);
            this.view = view;
            for (var slot : view.inputs()) {
                inputs.add(EmiIngredient.of(slot.stream().map(EmiStack::of).toList(), slot.isEmpty() ? 1 : slot.get(0).getCount()));
            }
            outputs.add(EmiStack.of(view.output()));
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            for (int i = 0; i < inputs.size(); i++) {
                widgets.addSlot(inputs.get(i), (i % 3) * 18, (i / 3) * 18);
            }
            widgets.addFillingArrow(62, 9, view.ticks() * 50);
            widgets.addSlot(outputs.get(0), 92, 5).large(true).recipeContext(this);
            widgets.addText(Component.literal(String.format(Locale.ROOT, "%.1f s", view.ticks() / 20f)), 60, 28, 0xFF808080, false);
            widgets.addText(view.note(), 0, 42, 0xFF606060, false);
        }
    }
}
