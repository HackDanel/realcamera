package dev.hackdanel.realcamera.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** REI: the photo workshop, film development and darkroom enlarging. Only loaded by REI itself. */
@me.shedaniel.rei.forge.REIPluginClient
public class ReiPlugin implements REIClientPlugin {
    /** Autotest probe: displays REI has in a category. */
    public static long count(ResourceLocation id) {
        return DisplayRegistry.getInstance().get(CategoryIdentifier.of(id)).size();
    }

    public static void open(ResourceLocation id) {
        me.shedaniel.rei.api.client.view.ViewSearchBuilder.builder().addCategory(CategoryIdentifier.of(id)).open();
    }

    @Override
    public void registerItemComparators(ItemComparatorRegistry registry) {
        registry.register((context, stack) -> RecipeViews.subtype(stack).hashCode(), RecipeViews.subtypedItems());
    }

    @Override
    public void registerCategories(CategoryRegistry registry) {
        for (ResourceLocation id : RecipeViews.CATEGORIES) {
            registry.add(new Category(id));
            for (var w : RecipeViews.workstations(id)) {
                registry.addWorkstations(CategoryIdentifier.of(id), EntryStacks.of(w));
            }
        }
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        for (ResourceLocation id : RecipeViews.CATEGORIES) {
            for (RecipeViews.View view : RecipeViews.all(id)) {
                registry.add(new Display(id, view));
            }
        }
    }

    private static final class Display extends BasicDisplay {
        private final ResourceLocation category;
        final RecipeViews.View view;

        Display(ResourceLocation category, RecipeViews.View view) {
            super(view.inputs().stream().map(EntryIngredients::ofItemStacks).toList(),
                    List.of(EntryIngredients.of(view.output())), java.util.Optional.of(view.id()));
            this.category = category;
            this.view = view;
        }

        @Override
        public CategoryIdentifier<?> getCategoryIdentifier() {
            return CategoryIdentifier.of(category);
        }
    }

    private static final class Category implements DisplayCategory<Display> {
        private final ResourceLocation id;

        Category(ResourceLocation id) {
            this.id = id;
        }

        @Override
        public CategoryIdentifier<? extends Display> getCategoryIdentifier() {
            return CategoryIdentifier.of(id);
        }

        @Override
        public Component getTitle() {
            return RecipeViews.title(id);
        }

        @Override
        public Renderer getIcon() {
            return EntryStacks.of(RecipeViews.icon(id));
        }

        @Override
        public int getDisplayHeight() {
            return 62;
        }

        @Override
        public List<Widget> setupDisplay(Display display, Rectangle bounds) {
            List<Widget> w = new ArrayList<>();
            w.add(Widgets.createRecipeBase(bounds));
            int x = bounds.x + 6, y = bounds.y + 6;
            List<EntryIngredient> in = display.getInputEntries();
            for (int i = 0; i < in.size(); i++) {
                w.add(Widgets.createSlot(new Point(x + (i % 3) * 18, y + (i / 3) * 18)).entries(in.get(i)).markInput());
            }
            w.add(Widgets.createArrow(new Point(x + 62, y + 9)));
            w.add(Widgets.createResultSlotBackground(new Point(x + 96, y + 10)));
            w.add(Widgets.createSlot(new Point(x + 96, y + 10)).entries(display.getOutputEntries().get(0)).disableBackground().markOutput());
            w.add(Widgets.createLabel(new Point(x + 60, y + 30),
                    Component.literal(String.format(Locale.ROOT, "%.1f s", display.view.ticks() / 20f))).leftAligned().noShadow().color(0xFF808080));
            w.add(Widgets.createLabel(new Point(x, y + 42), display.view.note()).leftAligned().noShadow().color(0xFF606060));
            return w;
        }
    }
}
