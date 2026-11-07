package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

/**
 * Flash, filter, step-up ring or battery grip (catalog entry chosen by component). Flashes and grips carry a charge.
 * A step-up ring and a filter screw together in any inventory slot, the camera's filter slot included: right-click
 * one with the other (in the camera's filter slot a left click does it too, where the vanilla swap would be refused).
 * The joined item is the filter carrying the ring ({@link RCComponents#STEP_UP_RING}); right-clicking it with an
 * empty cursor unscrews them again, leaving whichever part still fits the slot.
 */
public class AccessoryItem extends Item {
    private final String registry;

    public AccessoryItem(Properties properties, String registry) {
        super(properties.stacksTo(1));
        this.registry = registry;
    }

    private ResourceKey<?> key(ItemStack stack) {
        return switch (registry) {
            case "flash_type" -> stack.get(RCComponents.FLASH_TYPE.get());
            case "filter_type" -> stack.get(RCComponents.FILTER_TYPE.get());
            case "step_up_ring" -> stack.get(RCComponents.STEP_UP_RING.get());
            default -> stack.get(RCComponents.GRIP_TYPE.get());
        };
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceKey<?> key = key(stack);
        Component base = key != null ? SpecNames.name(registry, key) : super.getName(stack);
        Float damage = stack.get(RCComponents.DAMAGE.get());
        if (damage != null && damage > 0) {
            return Component.translatable("item.realcamera.cracked", base);
        }
        return base;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return stack.has(RCComponents.CHARGE.get());
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        BatteryCharge c = stack.get(RCComponents.CHARGE.get());
        return c == null ? 0 : Math.round(13f * c.fraction());
    }

    @Override
    public int getBarColor(ItemStack stack) {
        BatteryCharge c = stack.get(RCComponents.CHARGE.get());
        return Mth.hsvToRgb((c == null ? 0 : c.fraction()) / 3f, 1f, 1f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var r = context.registries();
        switch (registry) {
            case "flash_type" -> Specs.flash(r, stack).ifPresent(f -> lines.add(Component.translatable("tooltip.realcamera.flash",
                    String.format(Locale.ROOT, "%.0f", f.gn()), String.format(Locale.ROOT, "%.1f", f.recycleS()))
                    .withStyle(ChatFormatting.GRAY)));
            case "filter_type" -> Specs.filter(r, stack).ifPresent(f -> {
                lines.add(Component.translatable("tooltip.realcamera.filter." + f.kind(),
                        String.format(Locale.ROOT, f.lightLossEv() % 1 == 0 ? "%.0f" : "%.1f", f.lightLossEv()), f.diameterMm())
                        .withStyle(ChatFormatting.GRAY));
                Specs.stepUpRing(r, stack).ifPresentOrElse(g -> {
                    lines.add(Component.translatable("tooltip.realcamera.filter_on_ring", g.inMm(), g.outMm())
                            .withStyle(ChatFormatting.GRAY));
                    lines.add(Component.translatable("tooltip.realcamera.filter_ring_off").withStyle(ChatFormatting.DARK_GRAY));
                }, () -> lines.add(Component.translatable("tooltip.realcamera.filter_fits", f.diameterMm())
                        .withStyle(ChatFormatting.DARK_GRAY)));
            });
            case "step_up_ring" -> Specs.stepUpRing(r, stack).ifPresent(g -> {
                lines.add(Component.translatable("tooltip.realcamera.step_up_ring", g.inMm(), g.outMm())
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("tooltip.realcamera.step_up_ring_use").withStyle(ChatFormatting.DARK_GRAY));
            });
            default -> Specs.grip(r, stack).ifPresent(g -> lines.add(Component.translatable("tooltip.realcamera.grip",
                    SpecNames.name("battery_type", g.batteryType())).withStyle(ChatFormatting.GRAY)));
        }
        BatteryCharge c = stack.get(RCComponents.CHARGE.get());
        if (c != null) {
            lines.add(Component.translatable("tooltip.realcamera.charge", c.percent(), c.charge(), c.capacity())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    // ------------------------------------------------------------------ step-up rings (issue #7)

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack carried, Slot slot, ClickAction action, Player player,
                                            SlotAccess access) {
        if (!slot.allowModification(player)) {
            return false;
        }
        if (carried.isEmpty()) {
            return action == ClickAction.SECONDARY && unscrew(stack, slot, player, access);
        }
        ItemStack joined = screwTogether(player.level().registryAccess(), stack, carried);
        // a right click joins them; a left click only where the carried part could not be swapped in anyway
        if (joined == null || (action == ClickAction.PRIMARY && slot.mayPlace(carried)) || !slot.mayPlace(joined)) {
            return false;
        }
        slot.set(joined);
        access.set(ItemStack.EMPTY);
        player.playSound(SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.6f, 1.6f);
        return true;
    }

    /** The filter of the pair carrying the ring of the pair, or null unless one is a bare filter the ring takes. */
    @Nullable
    public static ItemStack screwTogether(HolderLookup.Provider r, ItemStack a, ItemStack b) {
        ItemStack filter = a.is(RCItems.FILTER.get()) ? a : b, ring = filter == a ? b : a;
        if (!filter.is(RCItems.FILTER.get()) || !ring.is(RCItems.STEP_UP_RING.get()) || filter.has(RCComponents.STEP_UP_RING.get())) {
            return null;
        }
        var f = Specs.filter(r, filter).orElse(null);
        var g = Specs.stepUpRing(r, ring).orElse(null);
        if (f == null || g == null || !g.takes(f.diameterMm())) {
            return null;
        }
        ItemStack joined = filter.copyWithCount(1);
        joined.set(RCComponents.STEP_UP_RING.get(), ring.get(RCComponents.STEP_UP_RING.get()));
        return joined;
    }

    /** Takes the ring off a filter: the part that still fits the slot stays, the other one goes to the cursor. */
    private static boolean unscrew(ItemStack filter, Slot slot, Player player, SlotAccess access) {
        var key = filter.get(RCComponents.STEP_UP_RING.get());
        if (!filter.is(RCItems.FILTER.get()) || key == null) {
            return false;
        }
        ItemStack bare = filter.copy();
        bare.remove(RCComponents.STEP_UP_RING.get());
        ItemStack ring = RCStacks.stepUpRing(key);
        if (slot.mayPlace(bare)) {
            slot.set(bare);
            access.set(ring);
        } else if (slot.mayPlace(ring)) {
            slot.set(ring);
            access.set(bare);
        } else {
            return false;
        }
        player.playSound(SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.6f, 1.3f);
        return true;
    }
}
