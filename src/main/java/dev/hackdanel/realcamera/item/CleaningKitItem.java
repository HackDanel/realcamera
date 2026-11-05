package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.component.CameraWear;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Blower and swabs: use with the camera in the other hand to remove sensor dust and dry it. */
public class CleaningKitItem extends Item {
    public CleaningKitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack kit = player.getItemInHand(hand);
        ItemStack camera = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (!CameraHelper.isCamera(camera)) {
            player.displayClientMessage(Component.translatable("message.realcamera.cleaning.no_camera"), true);
            return InteractionResultHolder.fail(kit);
        }
        if (!level.isClientSide) {
            CameraWear wear = camera.getOrDefault(RCComponents.WEAR.get(), CameraWear.NEW);
            camera.set(RCComponents.WEAR.get(), wear.clean());
            kit.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            level.playSound(null, player.blockPosition(), SoundEvents.BRUSH_GENERIC, player.getSoundSource(), 1f, 1.4f);
            player.displayClientMessage(Component.translatable("message.realcamera.cleaning.done"), true);
        }
        return InteractionResultHolder.sidedSuccess(kit, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.realcamera.cleaning_kit").withStyle(ChatFormatting.DARK_AQUA));
    }
}
