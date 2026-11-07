package dev.hackdanel.realcamera.client.compat;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity;
import dev.hackdanel.realcamera.component.FilmRoll;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.recipe.AssemblyRecipe;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCRecipes;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * What the recipe viewers (JEI, EMI, REI — see the plugins next to this class) show, built once from the client's
 * recipes and registries: workshop recipes, film development per process and darkroom enlarging per paper.
 * Plain data so the three plugins stay thin.
 */
public final class RecipeViews {
    private RecipeViews() {}

    public static final ResourceLocation ASSEMBLY = RealCamera.id("assembly");
    public static final ResourceLocation DEVELOPING = RealCamera.id("developing");
    public static final ResourceLocation ENLARGING = RealCamera.id("enlarging");

    /** One recipe as slots: each input slot lists the stacks that fit (with the count needed). */
    public record View(ResourceLocation id, List<List<ItemStack>> inputs, ItemStack output, int ticks, Component note) {}

    public static ItemStack[] workstations(ResourceLocation category) {
        if (category.equals(ASSEMBLY)) return new ItemStack[]{new ItemStack(RCItems.ASSEMBLY_STATION.get())};
        if (category.equals(DEVELOPING)) return new ItemStack[]{new ItemStack(RCItems.DEVELOPING_TANK.get()),
                new ItemStack(RCItems.CHANGING_BAG.get())};
        return new ItemStack[]{new ItemStack(RCItems.ENLARGER.get()), new ItemStack(RCItems.SAFELIGHT.get())};
    }

    public static ItemLike icon(ResourceLocation category) {
        if (category.equals(ASSEMBLY)) return RCItems.ASSEMBLY_STATION.get();
        if (category.equals(DEVELOPING)) return RCItems.DEVELOPING_TANK.get();
        return RCItems.ENLARGER.get();
    }

    public static Component title(ResourceLocation category) {
        return Component.translatable("recipe_viewer.realcamera." + category.getPath());
    }

    public static List<View> assembly() {
        var level = Minecraft.getInstance().level;
        List<View> out = new ArrayList<>();
        if (level == null) return out;
        for (var holder : level.getRecipeManager().getAllRecipesFor(RCRecipes.ASSEMBLY_TYPE.get())) {
            AssemblyRecipe r = holder.value();
            List<List<ItemStack>> inputs = new ArrayList<>();
            for (var ing : r.inputs()) {
                inputs.add(Arrays.stream(ing.getItems()).map(s -> s.copyWithCount(ing.count())).toList());
            }
            Component note = r.repair().isEmpty() ? Component.empty()
                    : Component.translatable("recipe_viewer.realcamera.repair." + r.repair());
            out.add(new View(holder.id(), inputs, r.result().copy(), r.time(), note));
        }
        out.sort(java.util.Comparator.comparing(v -> v.id().getPath()));
        return out;
    }

    /** (Synthetic recipes: their ids start with "/", as EMI asks.) Exposed rolls of every stock of a process + its chemistry kit -> the developed roll. */
    public static List<View> developing() {
        var level = Minecraft.getInstance().level;
        List<View> out = new ArrayList<>();
        if (level == null) return out;
        var stocks = level.registryAccess().registryOrThrow(RCRegistries.FILM_STOCK);
        for (String process : new String[]{"c41", "e6", "bw"}) {
            ItemStack chemistry = new ItemStack(switch (process) {
                case "c41" -> RCItems.CHEMISTRY_C41.get();
                case "e6" -> RCItems.CHEMISTRY_E6.get();
                default -> RCItems.CHEMISTRY_BW.get();
            });
            for (var entry : stocks.entrySet()) {
                if (!entry.getValue().process().equals(process)) continue;
                ItemStack exposed = RCStacks.film(entry.getKey(), "135");
                exposed.set(RCComponents.FILM.get(), FilmRoll.fresh("135").withState(FilmRoll.EXPOSED).withShot(36));
                ItemStack developed = exposed.copy();
                developed.set(RCComponents.FILM.get(), FilmRoll.fresh("135").withState(FilmRoll.DEVELOPED).withShot(36));
                out.add(new View(RealCamera.id("/developing/" + entry.getKey().location().getPath()),
                        List.of(List.of(exposed), List.of(chemistry)), developed, 1200,
                        Component.translatable("recipe_viewer.realcamera.process." + process)));
            }
        }
        return out;
    }

    /** A developed roll + darkroom paper -> a print (B&W papers print in black and white). */
    public static List<View> enlarging() {
        var level = Minecraft.getInstance().level;
        List<View> out = new ArrayList<>();
        if (level == null) return out;
        List<ItemStack> rolls = new ArrayList<>();
        level.registryAccess().registryOrThrow(RCRegistries.FILM_STOCK).registryKeySet().forEach(k -> {
            ItemStack roll = RCStacks.film(k, "135");
            roll.set(RCComponents.FILM.get(), FilmRoll.fresh("135").withState(FilmRoll.DEVELOPED).withShot(36));
            rolls.add(roll);
        });
        for (String paper : EnlargerBlockEntity.DARKROOM_PAPERS) {
            out.add(new View(RealCamera.id("/enlarging/" + paper), List.of(rolls, List.of(RCStacks.paper("10x15", paper, 1))),
                    new ItemStack(RCItems.PHOTO_PRINT.get()), 100,
                    Component.translatable("tooltip.realcamera.finish." + paper)));
        }
        return out;
    }

    public static List<View> all(ResourceLocation category) {
        if (category.equals(ASSEMBLY)) return assembly();
        if (category.equals(DEVELOPING)) return developing();
        return enlarging();
    }

    public static final ResourceLocation[] CATEGORIES = {ASSEMBLY, DEVELOPING, ENLARGING};

    /** Items whose concrete model lives in a component: viewers must tell e.g. two camera bodies apart. */
    public static net.minecraft.world.item.Item[] subtypedItems() {
        return new net.minecraft.world.item.Item[]{RCItems.CAMERA_BODY.get(), RCItems.LENS.get(), RCItems.BATTERY.get(),
                RCItems.MEMORY_CARD.get(), RCItems.MOUNT_ADAPTER.get(), RCItems.TELECONVERTER.get(), RCItems.FLASH.get(),
                RCItems.FILTER.get(), RCItems.STEP_UP_RING.get(), RCItems.BATTERY_GRIP.get(), RCItems.FILM_ROLL.get(), RCItems.PHOTO_PAPER.get(),
                RCItems.PHOTO_FRAME.get()};
    }

    /** The identity of a catalog stack ("" = plain item): model key, film stock and format, paper, frame size. */
    public static String subtype(ItemStack s) {
        StringBuilder b = new StringBuilder();
        for (var type : List.of(RCComponents.BODY.get(), RCComponents.LENS.get(), RCComponents.BATTERY_TYPE.get(),
                RCComponents.CARD_TYPE.get(), RCComponents.ADAPTER.get(), RCComponents.TELECONVERTER.get(),
                RCComponents.FLASH_TYPE.get(), RCComponents.FILTER_TYPE.get(), RCComponents.STEP_UP_RING.get(), RCComponents.GRIP_TYPE.get(),
                RCComponents.FILM_STOCK.get())) {
            Object v = s.get(type);
            if (v instanceof net.minecraft.resources.ResourceKey<?> k) b.append(k.location()).append(';');
        }
        FilmRoll roll = s.get(RCComponents.FILM.get());
        if (roll != null) b.append(roll.format()).append('/').append(roll.state()).append(';');
        var paper = s.get(RCComponents.PAPER.get());
        if (paper != null) b.append(paper.size()).append('/').append(paper.finish()).append(';');
        var frame = s.get(RCComponents.FRAME.get());
        if (frame != null) b.append(frame.width()).append('x').append(frame.height()).append('/').append(frame.material());
        return b.toString();
    }
}
