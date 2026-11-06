package dev.hackdanel.realcamera.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Block entity with an item inventory that is saved, dropped on break and exposed to a menu. */
public abstract class MachineBlockEntity extends BlockEntity implements MenuProvider {
    protected final ItemStackHandler items;

    protected MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int slots) {
        super(type, pos, state);
        this.items = new ItemStackHandler(slots) {
            @Override
            public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
                return MachineBlockEntity.this.canTake(slot) ? super.extractItem(slot, amount, simulate)
                        : net.minecraft.world.item.ItemStack.EMPTY;
            }

            @Override
            protected void onContentsChanged(int slot) {
                setChanged();
                MachineBlockEntity.this.onItemsChanged(slot);
            }

            @Override
            public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) {
                return MachineBlockEntity.this.isItemValid(slot, stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return MachineBlockEntity.this.slotLimit(slot);
            }
        };
    }

    public ItemStackHandler items() {
        return items;
    }

    protected void onItemsChanged(int slot) {}

    /** Whether a slot's item may be taken out right now (e.g. not while a process runs). */
    protected boolean canTake(int slot) {
        return true;
    }

    protected boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) {
        return true;
    }

    protected int slotLimit(int slot) {
        return 64;
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < items.getSlots(); i++) {
            Block.popResource(level, pos, items.getStackInSlot(i));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("items", items.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("items")) {
            int slots = items.getSlots();
            items.deserializeNBT(registries, tag.getCompound("items"));
            if (items.getSlots() < slots) { // saved before the machine got another slot: keep the items, add the slot
                var kept = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                for (int i = 0; i < items.getSlots(); i++) kept.add(items.getStackInSlot(i));
                items.setSize(slots);
                for (int i = 0; i < kept.size(); i++) items.setStackInSlot(i, kept.get(i));
            }
        }
    }
}
