package dev.hackdanel.realcamera.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Red darkroom safelight: lights the room without fogging photo paper (film is still not safe). */
public class SafelightBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 8, 11);

    public SafelightBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }
}
