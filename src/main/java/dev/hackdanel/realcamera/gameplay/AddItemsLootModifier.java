package dev.hackdanel.realcamera.gameplay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.RealCamera;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Structure loot (plan §8.3): adds camera gear to chests. Each entry rolls on its own; at most
 * {@code max_items} entries are added per chest. Contents and chances live in
 * data/realcamera/loot_modifiers/*.json (tools/gen_loot.py), so they can be tuned without code.
 */
public class AddItemsLootModifier extends LootModifier {
    public record Entry(ItemStack stack, float chance) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ItemStack.CODEC.fieldOf("item").forGetter(Entry::stack),
                Codec.FLOAT.fieldOf("chance").forGetter(Entry::chance)
        ).apply(i, Entry::new));
    }

    public static final MapCodec<AddItemsLootModifier> CODEC = RecordCodecBuilder.mapCodec(i -> codecStart(i).and(i.group(
            Entry.CODEC.listOf().fieldOf("entries").forGetter(m -> m.entries),
            Codec.INT.optionalFieldOf("max_items", 2).forGetter(m -> m.maxItems)
    )).apply(i, AddItemsLootModifier::new));

    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> REGISTER =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, RealCamera.MODID);
    public static final Supplier<MapCodec<AddItemsLootModifier>> ADD_ITEMS = REGISTER.register("add_items", () -> CODEC);

    private final List<Entry> entries;
    private final int maxItems;

    public AddItemsLootModifier(LootItemCondition[] conditions, List<Entry> entries, int maxItems) {
        super(conditions);
        this.entries = entries;
        this.maxItems = maxItems;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        int added = 0;
        for (Entry e : entries) {
            if (added >= maxItems) break;
            if (context.getRandom().nextFloat() < e.chance()) {
                loot.add(e.stack().copy());
                added++;
            }
        }
        return loot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
