package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

public class AssemblyStationMenu extends MachineMenu<AssemblyStationBlockEntity> {
    private final ContainerData data;

    public AssemblyStationMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null, new SimpleContainerData(2));
    }

    public AssemblyStationMenu(int id, Inventory inv, AssemblyStationBlockEntity be, ContainerData data) {
        this(id, inv, be.getBlockPos(), be, data);
    }

    private AssemblyStationMenu(int id, Inventory inv, net.minecraft.core.BlockPos pos, AssemblyStationBlockEntity be,
                                ContainerData data) {
        super(RCMenus.ASSEMBLY_STATION.get(), id, inv, pos, be, 7);
        this.data = data;
        for (int i = 0; i < AssemblyStationBlockEntity.INPUTS; i++) {
            addMachineSlot(i, 26 + (i % 3) * 18, 17 + (i / 3) * 18);
        }
        addMachineSlot(AssemblyStationBlockEntity.OUTPUT, 134, 26);
        addPlayerInventory(inv, 84);
        addDataSlots(data);
    }

    /** 0..1 */
    public float progress() {
        int max = data.get(1);
        return max <= 0 ? 0 : (float) data.get(0) / max;
    }
}
