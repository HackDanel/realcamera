package dev.hackdanel.realcamera.block;

import com.mojang.serialization.MapCodec;
import dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class DevelopingTankBlock extends MachineBlock {
    public static final MapCodec<DevelopingTankBlock> CODEC = simpleCodec(DevelopingTankBlock::new);
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 9, 12);

    public DevelopingTankBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DevelopingTankBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, RCBlockEntities.DEVELOPING_TANK.get(), DevelopingTankBlockEntity::serverTick);
    }
}
