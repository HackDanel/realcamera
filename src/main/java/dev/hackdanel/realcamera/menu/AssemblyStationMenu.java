package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class AssemblyStationMenu extends MachineMenu<AssemblyStationBlockEntity> {
    /** Player inventory top: lowered to make room for the recipe choice row. */
    public static final int INV_TOP = 104;
    /** Choice row: cells of 18 px from x = CHOICE_X, y = CHOICE_Y. */
    public static final int CHOICE_X = 26, CHOICE_Y = 66, CHOICE_CELLS = 7;

    private final ContainerData data;

    public AssemblyStationMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null, new SimpleContainerData(AssemblyStationBlockEntity.DATA_COUNT));
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
        addPlayerInventory(inv, INV_TOP);
        addDataSlots(data);
    }

    /** How many recipes the inputs match (synced). */
    public int choiceCount() {
        return data.get(2);
    }

    /** Index of the running recipe among the matches, -1 if none (synced). */
    public int choiceIndex() {
        return data.get(3);
    }

    /** Machine input stacks as the client sees them (for listing the matching recipes). */
    public java.util.List<ItemStack> inputs() {
        java.util.List<ItemStack> list = new java.util.ArrayList<>(AssemblyStationBlockEntity.INPUTS);
        for (int i = 0; i < AssemblyStationBlockEntity.INPUTS; i++) {
            list.add(items.getStackInSlot(i));
        }
        return list;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (be != null && id >= 0) {
            be.select(id);
            return true;
        }
        return false;
    }

    /** 0..1 */
    public float progress() {
        int max = data.get(1);
        return max <= 0 ? 0 : (float) data.get(0) / max;
    }
}
