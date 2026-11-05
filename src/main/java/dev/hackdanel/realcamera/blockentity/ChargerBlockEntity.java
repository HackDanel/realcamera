package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.block.ChargerBlock;
import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.item.BatteryItem;
import dev.hackdanel.realcamera.menu.ChargerMenu;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Four-bay battery charger. Charges only while it receives a redstone signal. */
public class ChargerBlockEntity extends MachineBlockEntity {
    public static final int SLOTS = 4;
    /** Ticks for an empty battery to reach 100 % (2 in-game minutes... of real time: 2400 ticks). */
    public static final int FULL_CHARGE_TICKS = 2400;
    private static final int STEP_TICKS = 10;

    private int tickCounter;

    public ChargerBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.CHARGER.get(), pos, state, SLOTS);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        // batteries, battery grips and flashes (rechargeable AA cells) all carry a charge
        return stack.getItem() instanceof BatteryItem || stack.has(dev.hackdanel.realcamera.registry.RCComponents.CHARGE.get());
    }

    @Override
    protected int slotLimit(int slot) {
        return 1;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ChargerBlockEntity be) {
        boolean powered = level.hasNeighborSignal(pos);
        boolean charging = false;
        if (powered && ++be.tickCounter >= STEP_TICKS) {
            be.tickCounter = 0;
            for (int i = 0; i < SLOTS; i++) {
                ItemStack stack = be.items.getStackInSlot(i);
                BatteryCharge c = stack.get(RCComponents.CHARGE.get());
                if (c != null && c.charge() < c.capacity()) {
                    int step = Math.max(1, Math.round((float) c.capacity() * STEP_TICKS / FULL_CHARGE_TICKS));
                    ItemStack copy = stack.copy();
                    copy.set(RCComponents.CHARGE.get(), c.withCharge(c.charge() + step));
                    be.items.setStackInSlot(i, copy);
                }
            }
        }
        if (powered) {
            for (int i = 0; i < SLOTS; i++) {
                BatteryCharge c = be.items.getStackInSlot(i).get(RCComponents.CHARGE.get());
                if (c != null && c.charge() < c.capacity()) {
                    charging = true;
                    break;
                }
            }
        }
        if (state.getValue(ChargerBlock.CHARGING) != charging) {
            level.setBlock(pos, state.setValue(ChargerBlock.CHARGING, charging), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.realcamera.battery_charger");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new ChargerMenu(id, inv, this);
    }
}
