package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity;
import dev.hackdanel.realcamera.component.FilmRoll;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCMenus;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

public class EnlargerMenu extends MachineMenu<EnlargerBlockEntity> {
    private final ContainerData data;

    public EnlargerMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null, new SimpleContainerData(6));
    }

    public EnlargerMenu(int id, Inventory inv, EnlargerBlockEntity be, ContainerData data) {
        this(id, inv, be.getBlockPos(), be, data);
    }

    private EnlargerMenu(int id, Inventory inv, BlockPos pos, EnlargerBlockEntity be, ContainerData data) {
        super(RCMenus.ENLARGER.get(), id, inv, pos, be, EnlargerBlockEntity.SLOTS);
        this.data = data;
        addMachineSlot(EnlargerBlockEntity.SLOT_FILM, 8, 18);
        addMachineSlot(EnlargerBlockEntity.SLOT_PAPER, 8, 54);
        addMachineSlot(EnlargerBlockEntity.SLOT_OUTPUT, 152, 54);
        addPlayerInventory(inv, 84);
        addDataSlots(data);
    }

    public int selected() { return data.get(0); }
    public int exposure() { return data.get(1); }
    public int grade() { return data.get(2); }
    public int progress() { return data.get(3); }
    public int frameCount() { return data.get(4); }
    public boolean dark() { return data.get(5) == 1; }

    @Nullable
    public UUID rollId() {
        FilmRoll r = machineItem(EnlargerBlockEntity.SLOT_FILM).get(RCComponents.FILM.get());
        return r == null ? null : r.id().orElse(null);
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (be != null) be.handleButton(p, id);
        return true;
    }
}
