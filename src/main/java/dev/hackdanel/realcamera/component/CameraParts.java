package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * What is mounted in / inserted into a camera body. Optical chain from the body outwards:
 * body -> mount adapter -> teleconverter -> lens -> filter; plus hot-shoe flash, battery, battery
 * grip and memory card. Empty stacks mean "nothing there".
 */
public record CameraParts(ItemStack lens, ItemStack adapter, ItemStack teleconverter, ItemStack filter, ItemStack flash,
                          ItemStack battery, ItemStack grip, ItemStack card) {
    public static final int SLOT_LENS = 0, SLOT_ADAPTER = 1, SLOT_TC = 2, SLOT_FILTER = 3, SLOT_FLASH = 4,
            SLOT_BATTERY = 5, SLOT_GRIP = 6, SLOT_CARD = 7, SLOTS = 8;
    private static final String[] NAMES = {"lens", "adapter", "teleconverter", "filter", "flash", "battery", "grip", "card"};

    public static final CameraParts EMPTY = new CameraParts(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    public static final Codec<CameraParts> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[0], ItemStack.EMPTY).forGetter(CameraParts::lens),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[1], ItemStack.EMPTY).forGetter(CameraParts::adapter),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[2], ItemStack.EMPTY).forGetter(CameraParts::teleconverter),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[3], ItemStack.EMPTY).forGetter(CameraParts::filter),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[4], ItemStack.EMPTY).forGetter(CameraParts::flash),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[5], ItemStack.EMPTY).forGetter(CameraParts::battery),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[6], ItemStack.EMPTY).forGetter(CameraParts::grip),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf(NAMES[7], ItemStack.EMPTY).forGetter(CameraParts::card)
    ).apply(i, CameraParts::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraParts> STREAM_CODEC =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(SLOTS)).map(
                    l -> new CameraParts(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5), l.get(6), l.get(7)),
                    p -> java.util.List.of(p.lens, p.adapter, p.teleconverter, p.filter, p.flash, p.battery, p.grip, p.card));

    public ItemStack get(int slot) {
        return switch (slot) {
            case SLOT_LENS -> lens;
            case SLOT_ADAPTER -> adapter;
            case SLOT_TC -> teleconverter;
            case SLOT_FILTER -> filter;
            case SLOT_FLASH -> flash;
            case SLOT_BATTERY -> battery;
            case SLOT_GRIP -> grip;
            default -> card;
        };
    }

    public CameraParts with(int slot, ItemStack stack) {
        ItemStack[] a = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) a[i] = get(i).copy();
        a[slot] = stack.copy();
        return new CameraParts(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
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
