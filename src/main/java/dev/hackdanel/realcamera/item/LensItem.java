package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.LensSpec;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class LensItem extends Item {
    public LensItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceKey<LensSpec> key = stack.get(RCComponents.LENS.get());
        return key != null ? SpecNames.name("lens", key) : super.getName(stack);
    }

    public static String focalLabel(LensSpec lens) {
        return lens.isZoom()
                ? String.format(Locale.ROOT, "%.0f-%.0f mm", lens.focalMin(), lens.focalMax())
                : String.format(Locale.ROOT, "%.0f mm", lens.focalMin());
    }

    public static String apertureLabel(LensSpec lens) {
        return lens.apertureWide() == lens.apertureTele()
                ? String.format(Locale.ROOT, "f/%s", trim(lens.apertureWide()))
                : String.format(Locale.ROOT, "f/%s-%s", trim(lens.apertureWide()), trim(lens.apertureTele()));
    }

    private static String trim(float f) {
        return f == Math.floor(f) ? String.valueOf((int) f) : String.format(Locale.ROOT, "%.1f", f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        Specs.lens(context.registries(), stack).ifPresent(lens -> {
            lines.add(Component.literal(focalLabel(lens) + "  " + apertureLabel(lens)).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tooltip.realcamera.mount", SpecNames.mount(lens.mount(), "apsc".equals(lens.imageCircle())))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable(lens.filterMm() > 0 ? "tooltip.realcamera.lens_details" : "tooltip.realcamera.lens_details_nofilter",
                    String.format(Locale.ROOT, "%.2f", lens.minFocusM()), lens.blades(), lens.filterMm())
                    .withStyle(ChatFormatting.DARK_GRAY));
            if (lens.oisStops() > 0) {
                lines.add(Component.translatable("tooltip.realcamera.ois", trim(lens.oisStops()))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        });
    }
}
