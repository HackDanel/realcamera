package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.ChargerBlockEntity;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

public class ChargerMenu extends MachineMenu<ChargerBlockEntity> {
    public ChargerMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null);
    }

    public ChargerMenu(int id, Inventory inv, ChargerBlockEntity be) {
        this(id, inv, be.getBlockPos(), be);
    }

    private ChargerMenu(int id, Inventory inv, net.minecraft.core.BlockPos pos, ChargerBlockEntity be) {
        super(RCMenus.CHARGER.get(), id, inv, pos, be, ChargerBlockEntity.SLOTS);
        for (int i = 0; i < ChargerBlockEntity.SLOTS; i++) {
            addMachineSlot(i, 53 + i * 18, 35);
        }
        addPlayerInventory(inv, 84);
    }

    public boolean powered() {
        return player.level().hasNeighborSignal(pos);
    }
}
