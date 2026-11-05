package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.menu.CameraBagMenu;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

/** Padded bag with 12 compartments for photo gear only. */
public class CameraBagItem extends Item {
    public CameraBagItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack bag = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new CameraBagMenu(id, inv, hand), bag.getHoverName()),
                    buf -> buf.writeEnum(hand));
        }
        return InteractionResultHolder.sidedSuccess(bag, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        ItemContainerContents contents = stack.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
        long n = contents.nonEmptyStream().count();
        lines.add(Component.translatable("tooltip.realcamera.bag", n, CameraBagMenu.SLOTS).withStyle(ChatFormatting.GRAY));
    }
}
