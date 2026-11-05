package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.BatteryTypeSpec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class BatteryItem extends Item {
    public BatteryItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceKey<BatteryTypeSpec> key = stack.get(RCComponents.BATTERY_TYPE.get());
        return key != null ? SpecNames.name("battery_type", key) : super.getName(stack);
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
        float f = c == null ? 0 : c.fraction();
        return Mth.hsvToRgb(f / 3f, 1f, 1f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        BatteryCharge c = stack.get(RCComponents.CHARGE.get());
        if (c != null) {
            lines.add(Component.translatable("tooltip.realcamera.charge", c.percent(), c.charge(), c.capacity())
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
