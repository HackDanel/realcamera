package dev.hackdanel.realcamera.block;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.blockentity.DisplayBlockEntity;
import dev.hackdanel.realcamera.item.PhotoPrintItem;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Shared behaviour of the table frame and the photo stand: right-click with a print puts it in,
 * right-click with an empty hand looks at it, sneak + right-click takes it out.
 */
public abstract class DisplayBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    protected DisplayBlock(Properties properties) {
        super(properties);
    }

    /** The position of the block entity (the lower half for the two-block stand). */
    protected BlockPos holderPos(BlockState state, BlockPos pos) {
        return pos;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    private DisplayBlockEntity holder(Level level, BlockState state, BlockPos pos) {
        return level.getBlockEntity(holderPos(state, pos)) instanceof DisplayBlockEntity be ? be : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof PhotoPrintItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        DisplayBlockEntity be = holder(level, state, pos);
        if (be == null || !be.print().isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            be.setPrint(stack);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                dev.hackdanel.realcamera.gameplay.PhotoEvents.count(sp, "frames_hung");
            }
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1, 1);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        DisplayBlockEntity be = holder(level, state, pos);
        if (be == null || be.print().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                ItemStack out = be.print().copy();
                be.setPrint(ItemStack.EMPTY);
                if (!player.getInventory().add(out)) {
                    player.drop(out, false);
                }
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1, 1);
            }
        } else if (level.isClientSide) {
            ClientBridge.get().openPrintViewer(be.print());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof DisplayBlockEntity be
                && !be.print().isEmpty()) {
            Block.popResource(level, pos, be.print().copy());
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    protected static Direction facing(BlockState state) {
        return state.getValue(FACING);
    }
}
