package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** One CMYK cartridge. Ink is measured in units; a 10x15 print at full coverage uses 1 unit. */
public class InkCartridgeItem extends Item {
    public static final int CAPACITY = 100;
    public final int channel; // PrintData.INK_C / INK_M / INK_Y / INK_K
    private final int color;

    public InkCartridgeItem(Properties properties, int channel, int color) {
        super(properties.stacksTo(1));
        this.channel = channel;
        this.color = color;
    }

    public static int ink(ItemStack stack) {
        return stack.getOrDefault(RCComponents.INK.get(), CAPACITY);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13f * ink(stack) / CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return color;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.realcamera.ink", ink(stack), CAPACITY).withStyle(ChatFormatting.GRAY));
    }
}
