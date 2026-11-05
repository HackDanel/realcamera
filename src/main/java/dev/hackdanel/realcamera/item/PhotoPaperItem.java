package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class PhotoPaperItem extends Item {
    public PhotoPaperItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        PaperData p = stack.get(RCComponents.PAPER.get());
        if (p != null) {
            lines.add(Component.translatable("tooltip.realcamera.paper." + p.size())
                    .append(", ").append(Component.translatable("tooltip.realcamera.finish." + p.finish()))
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
