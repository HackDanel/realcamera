package dev.hackdanel.realcamera.client.compat;

import dev.hackdanel.realcamera.RealCamera;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** JEI: the photo workshop, film development and darkroom enlarging. Only loaded by JEI itself. */
@mezz.jei.api.JeiPlugin
public class JeiPlugin implements IModPlugin {
    private static final Map<ResourceLocation, RecipeType<RecipeViews.View>> TYPES = new HashMap<>();

    static RecipeType<RecipeViews.View> type(ResourceLocation id) {
        return TYPES.computeIfAbsent(id, k -> RecipeType.create(k.getNamespace(), k.getPath(), RecipeViews.View.class));
    }

    @Override
    public ResourceLocation getPluginUid() {
        return RealCamera.id("jei_plugin");
    }

    /** For the autotest: JEI's runtime once it is ready. */
    public static mezz.jei.api.runtime.IJeiRuntime runtime;

    @Override
    public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    /** Autotest probe: how many recipes JEI shows in a category. */
    public static long count(ResourceLocation id) {
        return runtime == null ? -1 : runtime.getRecipeManager().createRecipeLookup(type(id)).get().count();
    }

    public static void open(ResourceLocation id) {
        if (runtime != null) runtime.getRecipesGui().showTypes(java.util.List.of(type(id)));
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        ISubtypeInterpreter<ItemStack> byComponent = new ISubtypeInterpreter<>() {
            @Override
            public Object getSubtypeData(ItemStack stack, UidContext context) {
                String s = RecipeViews.subtype(stack);
                return s.isEmpty() ? null : s;
            }

            @Override
            public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
                return RecipeViews.subtype(stack);
            }
        };
        for (var item : RecipeViews.subtypedItems()) {
            registration.registerSubtypeInterpreter(item, byComponent);
        }
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper gui = registration.getJeiHelpers().getGuiHelper();
        for (ResourceLocation id : RecipeViews.CATEGORIES) {
            registration.addRecipeCategories(new Category(id, gui));
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (ResourceLocation id : RecipeViews.CATEGORIES) {
            registration.addRecipes(type(id), RecipeViews.all(id));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (ResourceLocation id : RecipeViews.CATEGORIES) {
            registration.addRecipeCatalysts(type(id), RecipeViews.workstations(id));
        }
    }

    /** Up to six inputs in a 3×2 grid, an arrow, the result; time and a note underneath. */
    private static final class Category implements IRecipeCategory<RecipeViews.View> {
        private final ResourceLocation id;
        private final IDrawable icon, arrow;

        Category(ResourceLocation id, IGuiHelper gui) {
            this.id = id;
            this.icon = gui.createDrawableItemLike(RecipeViews.icon(id));
            this.arrow = gui.getRecipeArrow();
        }

        @Override
        public RecipeType<RecipeViews.View> getRecipeType() {
            return type(id);
        }

        @Override
        public Component getTitle() {
            return RecipeViews.title(id);
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public int getWidth() {
            return 128;
        }

        @Override
        public int getHeight() {
            return 52;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, RecipeViews.View recipe, IFocusGroup focuses) {
            for (int i = 0; i < recipe.inputs().size(); i++) {
                builder.addSlot(RecipeIngredientRole.INPUT, 1 + (i % 3) * 18, 1 + (i / 3) * 18)
                        .addIngredients(VanillaTypes.ITEM_STACK, recipe.inputs().get(i)).setStandardSlotBackground();
            }
            builder.addSlot(RecipeIngredientRole.OUTPUT, 96, 10).addItemStack(recipe.output()).setOutputSlotBackground();
        }

        @Override
        public void draw(RecipeViews.View recipe, IRecipeSlotsView slots, GuiGraphics g, double mouseX, double mouseY) {
            arrow.draw(g, 64, 10);
            var font = Minecraft.getInstance().font;
            g.drawString(font, String.format(Locale.ROOT, "%.1f s", recipe.ticks() / 20f), 62, 28, 0xFF808080, false);
            g.drawString(font, recipe.note(), 1, 42, 0xFF606060, false);
        }
    }
}
