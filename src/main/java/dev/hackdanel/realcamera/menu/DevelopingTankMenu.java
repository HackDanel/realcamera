package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

public class DevelopingTankMenu extends MachineMenu<DevelopingTankBlockEntity> {
    private final ContainerData data;

    public DevelopingTankMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null, new SimpleContainerData(3));
    }

    public DevelopingTankMenu(int id, Inventory inv, DevelopingTankBlockEntity be, ContainerData data) {
        this(id, inv, be.getBlockPos(), be, data);
    }

    private DevelopingTankMenu(int id, Inventory inv, BlockPos pos, DevelopingTankBlockEntity be, ContainerData data) {
        super(RCMenus.DEVELOPING_TANK.get(), id, inv, pos, be, DevelopingTankBlockEntity.SLOTS);
        this.data = data;
        addMachineSlot(DevelopingTankBlockEntity.SLOT_ROLL, 26, 26);
        addMachineSlot(DevelopingTankBlockEntity.SLOT_CHEMISTRY, 26, 50);
        addMachineSlot(DevelopingTankBlockEntity.SLOT_BAG, 8, 26);
        addMachineSlot(DevelopingTankBlockEntity.SLOT_OUTPUT, 134, 38);
        addPlayerInventory(inv, 84);
        addDataSlots(data);
    }

    public int progress() {
        return data.get(0);
    }

    public int push() {
        return data.get(1);
    }

    public boolean dark() {
        return data.get(2) == 1;
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (be != null) be.handleButton(p, id);
        return true;
    }
}
