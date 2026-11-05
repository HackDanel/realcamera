package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * What is mounted in / inserted into a camera body. Optical chain from the body outwards:
 * body -> mount adapter -> teleconverter -> lens. Empty stacks mean "nothing there".
 */
public record CameraParts(ItemStack lens, ItemStack adapter, ItemStack teleconverter, ItemStack battery, ItemStack card) {
    public static final CameraParts EMPTY = new CameraParts(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY);

    public static final Codec<CameraParts> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("lens", ItemStack.EMPTY).forGetter(CameraParts::lens),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("adapter", ItemStack.EMPTY).forGetter(CameraParts::adapter),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("teleconverter", ItemStack.EMPTY).forGetter(CameraParts::teleconverter),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("battery", ItemStack.EMPTY).forGetter(CameraParts::battery),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("card", ItemStack.EMPTY).forGetter(CameraParts::card)
    ).apply(i, CameraParts::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraParts> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::lens,
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::adapter,
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::teleconverter,
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::battery,
            ItemStack.OPTIONAL_STREAM_CODEC, CameraParts::card,
            CameraParts::new);

    public static final int SLOT_LENS = 0, SLOT_ADAPTER = 1, SLOT_TC = 2, SLOT_BATTERY = 3, SLOT_CARD = 4, SLOTS = 5;

    public ItemStack get(int slot) {
        return switch (slot) {
            case SLOT_LENS -> lens;
            case SLOT_ADAPTER -> adapter;
            case SLOT_TC -> teleconverter;
            case SLOT_BATTERY -> battery;
            default -> card;
        };
    }

    public CameraParts with(int slot, ItemStack stack) {
        ItemStack[] a = {lens.copy(), adapter.copy(), teleconverter.copy(), battery.copy(), card.copy()};
        a[slot] = stack.copy();
        return new CameraParts(a[0], a[1], a[2], a[3], a[4]);
    }

    // ItemStack has identity equality; compare contents so component updates are detected correctly.
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CameraParts p)) return false;
        for (int i = 0; i < SLOTS; i++) {
            if (!ItemStack.matches(get(i), p.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < SLOTS; i++) {
            h = 31 * h + ItemStack.hashItemAndComponents(get(i));
        }
        return h;
    }
}
