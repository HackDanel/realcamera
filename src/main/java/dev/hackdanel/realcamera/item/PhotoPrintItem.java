package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** A printed photo. Renders the actual picture (client BEWLR); right click opens a full-screen view. */
public class PhotoPrintItem extends Item {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public PhotoPrintItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.has(RCComponents.PRINT.get())) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            ClientBridge.get().openPrintViewer(stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        PrintData print = stack.get(RCComponents.PRINT.get());
        if (print == null) {
            return;
        }
        if (!print.caption().isEmpty()) {
            lines.add(Component.literal("«" + print.caption() + "»").withStyle(ChatFormatting.WHITE));
        }
        lines.add(Component.translatable("tooltip.realcamera.photo_author", print.photo().authorName(),
                DATE.format(Instant.ofEpochMilli(print.photo().createdMillis()))).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(print.photo().exif().camera()).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("tooltip.realcamera.paper." + print.paperSize())
                .append(", ").append(Component.translatable("tooltip.realcamera.finish." + print.finish()))
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
