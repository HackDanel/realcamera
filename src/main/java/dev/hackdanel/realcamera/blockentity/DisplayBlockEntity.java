package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCComponents;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds one print for a table frame or a photo stand; synced to clients for rendering. */
public class DisplayBlockEntity extends BlockEntity {
    private ItemStack print = ItemStack.EMPTY;

    public DisplayBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.DISPLAY.get(), pos, state);
    }

    public ItemStack print() {
        return print;
    }

    @Nullable
    public PrintData printData() {
        return print.get(RCComponents.PRINT.get());
    }

    public void setPrint(ItemStack stack) {
        print = stack.copyWithCount(stack.isEmpty() ? 0 : 1);
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            if (state.hasProperty(dev.hackdanel.realcamera.block.TableFrameBlock.PORTRAIT)
                    && state.getValue(dev.hackdanel.realcamera.block.TableFrameBlock.PORTRAIT) != portrait()) {
                level.setBlock(worldPosition, state.setValue(dev.hackdanel.realcamera.block.TableFrameBlock.PORTRAIT,
                        portrait()), Block.UPDATE_ALL);
            }
            level.sendBlockUpdated(worldPosition, state, getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Landscape or square prints stand wide; tall ones stand portrait. */
    public boolean portrait() {
        PrintData d = printData();
        return d != null && d.photo().height() > d.photo().width();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!print.isEmpty()) {
            tag.put("print", print.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        print = tag.contains("print") ? ItemStack.parseOptional(registries, tag.getCompound("print")) : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
