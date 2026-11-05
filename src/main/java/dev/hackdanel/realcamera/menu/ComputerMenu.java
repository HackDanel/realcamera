package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.blockentity.ComputerBlockEntity;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCMenus;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;

/** Computer: card slots on the left of the screen, the catalogue drawn by the screen itself. */
public class ComputerMenu extends MachineMenu<ComputerBlockEntity> {
    /** Slot positions are relative to the screen's panel (see ComputerScreen). */
    public static final int SLOT_X = 8, CARD_A_Y = 30, CARD_B_Y = 66, READER_Y = 100, SCANNER_Y = 126, INV_LEFT = 79, INV_TOP = 150;

    public ComputerMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null);
    }

    public ComputerMenu(int id, Inventory inv, ComputerBlockEntity be) {
        this(id, inv, be.getBlockPos(), be);
    }

    private ComputerMenu(int id, Inventory inv, BlockPos pos, @Nullable ComputerBlockEntity be) {
        super(RCMenus.COMPUTER.get(), id, inv, pos, be, ComputerBlockEntity.SLOTS);
        addMachineSlot(ComputerBlockEntity.SLOT_CARD_A, SLOT_X, CARD_A_Y);
        addMachineSlot(ComputerBlockEntity.SLOT_CARD_B, SLOT_X, CARD_B_Y);
        addMachineSlot(ComputerBlockEntity.SLOT_READER, SLOT_X, READER_Y);
        addMachineSlot(ComputerBlockEntity.SLOT_SCANNER, SLOT_X, SCANNER_Y);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new net.minecraft.world.inventory.Slot(inv, col + row * 9 + 9, INV_LEFT + col * 18,
                        INV_TOP + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new net.minecraft.world.inventory.Slot(inv, col, INV_LEFT + col * 18, INV_TOP + 58));
        }
    }

    /** Card in slot 0 or 1, read from the synced slot (works on both sides). */
    @Nullable
    public UUID cardId(int slot) {
        var roll = machineItem(slot).get(RCComponents.FILM.get());
        if (roll != null) return roll.id().orElse(null);
        CardData d = machineItem(slot).get(RCComponents.CARD.get());
        return d == null ? null : d.id().orElse(null);
    }

    public boolean isFilm(int slot) {
        return machineItem(slot).has(RCComponents.FILM.get());
    }
}
