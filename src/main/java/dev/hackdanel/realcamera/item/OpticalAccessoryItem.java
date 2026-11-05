package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Mount adapter or teleconverter (catalog entry chosen by component). */
public class OpticalAccessoryItem extends Item {
    private final boolean teleconverter;

    public OpticalAccessoryItem(Properties properties, boolean teleconverter) {
        super(properties.stacksTo(1));
        this.teleconverter = teleconverter;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (teleconverter) {
            var key = stack.get(RCComponents.TELECONVERTER.get());
            return key != null ? SpecNames.name("teleconverter", key) : super.getName(stack);
        }
        var key = stack.get(RCComponents.ADAPTER.get());
        return key != null ? SpecNames.name("mount_adapter", key) : super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var r = context.registries();
        if (teleconverter) {
            Specs.teleconverter(r, stack).ifPresent(tc -> {
                lines.add(Component.translatable("tooltip.realcamera.tc", String.format(Locale.ROOT, "%.1f", tc.factor()),
                        String.format(Locale.ROOT, "%.1f", 2 * Math.log(tc.factor()) / Math.log(2))).withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("tooltip.realcamera.mount", SpecNames.name("mount", tc.mount()))
                        .withStyle(ChatFormatting.GRAY));
            });
        } else {
            Specs.adapter(r, stack).ifPresent(a -> {
                lines.add(Component.translatable("tooltip.realcamera.adapter", SpecNames.name("mount", a.from()),
                        SpecNames.name("mount", a.to())).withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("tooltip.realcamera.adapter_af." + a.af()).withStyle(ChatFormatting.DARK_GRAY));
            });
        }
    }
}
