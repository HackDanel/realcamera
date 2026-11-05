package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Flash, filter or battery grip (catalog entry chosen by component). Flashes and grips carry a charge. */
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
            case "filter_type" -> Specs.filter(r, stack).ifPresent(f -> lines.add(Component.translatable(
                    "tooltip.realcamera.filter." + f.kind(), String.format(Locale.ROOT, "%.0f", f.lightLossEv()), f.diameterMm())
                    .withStyle(ChatFormatting.GRAY)));
            default -> Specs.grip(r, stack).ifPresent(g -> lines.add(Component.translatable("tooltip.realcamera.grip",
                    SpecNames.name("battery_type", g.batteryType())).withStyle(ChatFormatting.GRAY)));
        }
        BatteryCharge c = stack.get(RCComponents.CHARGE.get());
        if (c != null) {
            lines.add(Component.translatable("tooltip.realcamera.charge", c.percent(), c.charge(), c.capacity())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
