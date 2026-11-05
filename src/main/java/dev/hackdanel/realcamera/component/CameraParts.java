package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** What is mounted in / inserted into a camera body. Empty stacks mean "nothing there". */
public record CameraParts(ItemStack lens, ItemStack battery, ItemStack card) {
    public static final CameraParts EMPTY = new CameraParts(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    public static final Codec<CameraParts> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("lens", ItemStack.EMPTY).forGetter(CameraParts::lens),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("battery", ItemStack.EMPTY).forGetter(CameraParts::battery),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("card", ItemStack.EMPTY).forGetter(CameraParts::card)
    ).apply(i, CameraParts::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraParts> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::lens,
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::battery,
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::card,
            CameraParts::new);

    public static final int SLOT_LENS = 0, SLOT_BATTERY = 1, SLOT_CARD = 2, SLOTS = 3;

    public ItemStack get(int slot) {
        return switch (slot) {
            case SLOT_LENS -> lens;
            case SLOT_BATTERY -> battery;
            default -> card;
        };
    }

    public CameraParts with(int slot, ItemStack stack) {
        ItemStack copy = stack.copy();
        return switch (slot) {
            case SLOT_LENS -> new CameraParts(copy, battery.copy(), card.copy());
            case SLOT_BATTERY -> new CameraParts(lens.copy(), copy, card.copy());
            default -> new CameraParts(lens.copy(), battery.copy(), copy);
        };
    }

    // ItemStack has identity equality; compare contents so component updates are detected correctly.
    @Override
    public boolean equals(Object o) {
        return o instanceof CameraParts p && ItemStack.matches(lens, p.lens) && ItemStack.matches(battery, p.battery)
                && ItemStack.matches(card, p.card);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * ItemStack.hashItemAndComponents(lens) + ItemStack.hashItemAndComponents(battery))
                + ItemStack.hashItemAndComponents(card);
    }
}
