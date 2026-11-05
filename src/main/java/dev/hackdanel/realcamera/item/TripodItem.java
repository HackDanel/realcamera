package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.entity.TripodEntity;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class TripodItem extends Item {
    public TripodItem(Properties properties) {
        super(properties.stacksTo(4));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getClickedFace() != Direction.UP) {
            return InteractionResult.FAIL;
        }
        Level level = ctx.getLevel();
        Vec3 pos = ctx.getClickLocation();
        if (!level.isClientSide) {
            float yaw = ctx.getPlayer() == null ? 0 : ctx.getPlayer().getYRot();
            TripodEntity tripod = new TripodEntity(level, pos, yaw);
            level.addFreshEntity(tripod);
            level.playSound(null, tripod.blockPosition(), SoundEvents.ARMOR_STAND_PLACE, tripod.getSoundSource(), 1f, 1.2f);
        }
        ctx.getItemInHand().consume(1, ctx.getPlayer());
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.realcamera.tripod").withStyle(ChatFormatting.DARK_AQUA));
    }
}
