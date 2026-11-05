package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.MachineBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Shared plumbing for block menus: machine slots first, then the player inventory.
 * On the client the block entity may not exist yet when the "open screen" packet arrives (the
 * block update can come later), so the client side works on its own handler that the server
 * keeps in sync through the slots. Only the server side holds the real block entity.
 */
public abstract class MachineMenu<T extends MachineBlockEntity> extends AbstractContainerMenu {
    @Nullable
    protected final T be;
    protected final IItemHandler items;
    protected final BlockPos pos;
    protected final Player player;
    protected final int machineSlots;

    protected MachineMenu(MenuType<?> type, int id, Inventory inv, BlockPos pos, @Nullable T be, int slots) {
        super(type, id);
        this.be = be;
        this.pos = pos;
        this.player = inv.player;
        this.items = be != null ? be.items() : new ItemStackHandler(slots);
        this.machineSlots = slots;
    }

    /** Server-side block entity; null on the client. */
    @Nullable
    public T blockEntity() {
        return be;
    }

    public BlockPos pos() {
        return pos;
    }

    public ItemStack machineItem(int slot) {
        return items.getStackInSlot(slot);
    }

    protected void addMachineSlot(int slot, int x, int y) {
        addSlot(new SlotItemHandler(items, slot, x, y));
    }

    protected void addPlayerInventory(Inventory inv, int top) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, top + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, 8 + col * 18, top + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, machineSlots, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player p) {
        if (be != null && be.isRemoved()) {
            return false;
        }
        return p.distanceToSqr(pos.getCenter()) <= 64;
    }
}
