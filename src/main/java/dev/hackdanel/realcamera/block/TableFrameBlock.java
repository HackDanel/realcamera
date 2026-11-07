package dev.hackdanel.realcamera.block;

import com.mojang.serialization.MapCodec;
import dev.hackdanel.realcamera.blockentity.DisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A small frame with a back strut that stands on a table or shelf; turns portrait for tall prints. Like a mob head
 * it stands in 16 directions: FACING is the quarter and TURN adds 0..3 steps of 22.5 degrees clockwise (seen from
 * above). Turned frames have an empty block model; {@code DisplayRenderer} draws them at their angle.
 */
public class TableFrameBlock extends DisplayBlock {
    public static final MapCodec<TableFrameBlock> CODEC = simpleCodec(TableFrameBlock::new);
    public static final BooleanProperty PORTRAIT = BooleanProperty.create("portrait");
    public static final IntegerProperty TURN = IntegerProperty.create("turn", 0, 3);
    private static final VoxelShape SHAPE = Block.box(3, 0, 6.5, 13, 11, 11);

    public TableFrameBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PORTRAIT, false).setValue(TURN, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PORTRAIT, TURN);
    }

    /** Direction the front faces, in 1/16 turns, Minecraft yaw convention (0 = south, 4 = west). */
    public static int segment(BlockState state) {
        return state.getValue(FACING).get2DDataValue() * 4 + state.getValue(TURN);
    }

    /** Yaw the front faces, degrees (0 = south, 90 = west). */
    public static float yaw(BlockState state) {
        return segment(state) * 22.5f;
    }

    private static BlockState withSegment(BlockState state, int segment) {
        int s = Math.floorMod(segment, 16);
        return state.setValue(FACING, Direction.from2DDataValue(s / 4)).setValue(TURN, s % 4);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // face the player, like a head: their yaw + 180, snapped to 22.5 degrees
        return withSegment(defaultBlockState(), RotationSegment.convertToSegment(ctx.getRotation() + 180));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return withSegment(state, rotation.rotate(segment(state), 16));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return withSegment(state, mirror.mirror(segment(state), 16));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return PhotoStandBlock.rotated(SHAPE, state.getValue(FACING));
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return Block.canSupportCenter(level, pos.below(), Direction.UP)
                || !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor,
                                     net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == Direction.DOWN && !canSurvive(state, level, pos)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayBlockEntity(pos, state);
    }
}
