package dev.hackdanel.realcamera.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.registry.RCRecipes;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * Photo workshop recipe: a shapeless list of ingredients with counts, a result that may carry data
 * components (e.g. which camera model), and an assembly time in ticks.
 */
public record AssemblyRecipe(List<SizedIngredient> inputs, ItemStack result, int time, String repair)
        implements Recipe<AssemblyInput> {
    /**
     * repair: "" for normal recipes; "shutter", "electronics", "lens" or "filter" make the result a
     * repaired copy of the matching input item (keeping its components).
     */
    public static final MapCodec<AssemblyRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            SizedIngredient.FLAT_CODEC.listOf(1, 6).fieldOf("ingredients").forGetter(AssemblyRecipe::inputs),
            ItemStack.STRICT_CODEC.fieldOf("result").forGetter(AssemblyRecipe::result),
            Codec.INT.optionalFieldOf("time", 100).forGetter(AssemblyRecipe::time),
            Codec.STRING.optionalFieldOf("repair", "").forGetter(AssemblyRecipe::repair)
    ).apply(i, AssemblyRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> STREAM_CODEC = StreamCodec.composite(
            SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), AssemblyRecipe::inputs,
            ItemStack.STREAM_CODEC, AssemblyRecipe::result,
            ByteBufCodecs.VAR_INT, AssemblyRecipe::time,
            ByteBufCodecs.STRING_UTF8, AssemblyRecipe::repair,
            AssemblyRecipe::new);

    @Override
    public boolean matches(AssemblyInput input, Level level) {
        return allocate(input) != null;
    }

    /**
     * Greedy assignment of ingredient counts to input slots.
     * @return per-slot amounts to consume, or null if the recipe does not match
     */
    public int[] allocate(AssemblyInput input) {
        int[] remaining = new int[input.size()];
        for (int s = 0; s < input.size(); s++) {
            remaining[s] = input.getItem(s).getCount();
        }
        int[] take = new int[input.size()];
        for (SizedIngredient ing : inputs) {
            int need = ing.count();
            for (int s = 0; s < input.size() && need > 0; s++) {
                ItemStack stack = input.getItem(s);
                if (remaining[s] > 0 && ing.ingredient().test(stack)) {
                    int n = Math.min(need, remaining[s]);
                    remaining[s] -= n;
                    take[s] += n;
                    need -= n;
                }
            }
            if (need > 0) {
                return null;
            }
        }
        return take;
    }

    @Override
    public ItemStack assemble(AssemblyInput input, HolderLookup.Provider registries) {
        if (repair.isEmpty()) {
            return result.copy();
        }
        for (int s = 0; s < input.size(); s++) {
            ItemStack stack = input.getItem(s);
            if (!stack.is(result.getItem())) continue;
            ItemStack fixed = stack.copyWithCount(1);
            var wear = fixed.getOrDefault(dev.hackdanel.realcamera.registry.RCComponents.WEAR.get(),
                    dev.hackdanel.realcamera.component.CameraWear.NEW);
            switch (repair) {
                case "shutter" -> {
                    fixed.set(dev.hackdanel.realcamera.registry.RCComponents.WEAR.get(), wear.withJammed(false));
                    fixed.set(dev.hackdanel.realcamera.registry.RCComponents.SHUTTER_COUNT.get(), 0);
                }
                case "electronics" -> fixed.set(dev.hackdanel.realcamera.registry.RCComponents.WEAR.get(),
                        wear.withDead(false).withMoisture(0));
                default -> fixed.remove(dev.hackdanel.realcamera.registry.RCComponents.DAMAGE.get());
            }
            return fixed;
        }
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        inputs.forEach(s -> list.add(s.ingredient()));
        return list;
    }

    @Override
    public boolean isSpecial() {
        return true; // keep out of the vanilla recipe book
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return RCRecipes.ASSEMBLY_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return RCRecipes.ASSEMBLY_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<AssemblyRecipe> {
        @Override
        public MapCodec<AssemblyRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
