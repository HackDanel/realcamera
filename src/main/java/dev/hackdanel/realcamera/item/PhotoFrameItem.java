package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.component.FrameData;
import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.entity.PhotoFrameEntity;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

public class PhotoFrameItem extends Item {
    public static final FrameData DEFAULT = new FrameData(1, 1, "oak");

    public PhotoFrameItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    public static FrameData frame(ItemStack stack) {
        return stack.getOrDefault(RCComponents.FRAME.get(), DEFAULT);
    }

    @Override
    public Component getName(ItemStack stack) {
        FrameData f = frame(stack);
        return Component.translatable("item.realcamera.photo_frame.named", f.width(), f.height(),
                Component.translatable("frame_material.realcamera." + f.material()));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Direction face = ctx.getClickedFace();
        if (face.getAxis().isVertical()) {
            return InteractionResult.FAIL;
        }
        BlockPos pos = ctx.getClickedPos().relative(face);
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        if (player != null && !player.mayUseItemAt(pos, face, stack)) {
            return InteractionResult.FAIL;
        }
        Level level = ctx.getLevel();
        PhotoFrameEntity frame = new PhotoFrameEntity(level, pos, face, frame(stack));
        if (!frame.survives()) {
            return InteractionResult.CONSUME;
        }
        if (!level.isClientSide) {
            frame.playPlacementSound();
            level.gameEvent(player, GameEvent.ENTITY_PLACE, frame.position());
            level.addFreshEntity(frame);
        }
        stack.consume(1, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.realcamera.frame_hint").withStyle(ChatFormatting.DARK_AQUA));
        FrameData f = frame(stack);
        String paper = PaperData.smallestFor(f.width(), f.height());
        lines.add((paper.equals(PaperData.SIZES[0]) ? Component.translatable("tooltip.realcamera.frame_paper.any")
                : Component.translatable("tooltip.realcamera.frame_paper", Component.translatable("tooltip.realcamera.paper." + paper)))
                .withStyle(ChatFormatting.GRAY));
    }
}
