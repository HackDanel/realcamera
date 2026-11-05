package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.PrinterBlockEntity;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

public class PrinterMenu extends MachineMenu<PrinterBlockEntity> {
    private final ContainerData data;

    public PrinterMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null, new SimpleContainerData(3));
    }

    public PrinterMenu(int id, Inventory inv, PrinterBlockEntity be, ContainerData data) {
        this(id, inv, be.getBlockPos(), be, data);
    }

    private PrinterMenu(int id, Inventory inv, net.minecraft.core.BlockPos pos, PrinterBlockEntity be, ContainerData data) {
        super(RCMenus.PRINTER.get(), id, inv, pos, be, PrinterBlockEntity.SLOTS);
        this.data = data;
        addMachineSlot(PrinterBlockEntity.SLOT_CARD, 8, 18);
        addMachineSlot(PrinterBlockEntity.SLOT_PAPER, 8, 54);
        for (int i = 0; i < 4; i++) {
            addMachineSlot(PrinterBlockEntity.SLOT_INK_FIRST + i, 116 + (i % 2) * 18, 18 + (i / 2) * 18);
        }
        addMachineSlot(PrinterBlockEntity.SLOT_OUTPUT, 152, 54);
        addPlayerInventory(inv, 84);
        addDataSlots(data);
    }

    public int progress() {
        return data.get(0);
    }

    public int selected() {
        return data.get(1);
    }

    public int photoCount() {
        return data.get(2);
    }

    /** Card in the printer, read from the synced slot (works on both sides). */
    public java.util.UUID cardId() {
        var d = machineItem(PrinterBlockEntity.SLOT_CARD).get(dev.hackdanel.realcamera.registry.RCComponents.CARD.get());
        return d == null ? null : d.id().orElse(null);
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (be != null) {
            be.handleButton(id);
        }
        return true;
    }
}
