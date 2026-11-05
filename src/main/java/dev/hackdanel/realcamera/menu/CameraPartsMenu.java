package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.OpticalChain;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.item.BatteryItem;
import dev.hackdanel.realcamera.item.LensItem;
import dev.hackdanel.realcamera.item.MemoryCardItem;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCMenus;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Opened camera body: lens, battery and card slots plus the player inventory. */
public class CameraPartsMenu extends AbstractContainerMenu {
    public static final int PART_SLOTS = CameraParts.SLOTS;
    public static final int[] SLOT_X = {17, 48, 80, 112, 143};
    public static final int SLOT_Y = 30;

    private final Player player;
    private final InteractionHand hand;
    private final SimpleContainer parts = new SimpleContainer(PART_SLOTS);
    private boolean loading;

    public CameraPartsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(id, inventory, buf.readEnum(InteractionHand.class));
    }

    public CameraPartsMenu(int id, Inventory inventory, InteractionHand hand) {
        super(RCMenus.CAMERA_PARTS.get(), id);
        this.player = inventory.player;
        this.hand = hand;
        ItemStack camera = player.getItemInHand(hand);
        CameraParts current = CameraHelper.parts(camera);
        loading = true;
        for (int i = 0; i < PART_SLOTS; i++) {
            parts.setItem(i, current.get(i).copy());
        }
        loading = false;
        parts.addListener(c -> save());

        for (int i = 0; i < PART_SLOTS; i++) {
            final int part = i;
            addSlot(new Slot(parts, i, SLOT_X[i], SLOT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return accepts(part, stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        int lockedInvSlot = hand == InteractionHand.MAIN_HAND ? inventory.selected : 40;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            if (col == lockedInvSlot) {
                addSlot(new LockedSlot(inventory, col, 8 + col * 18, 142));
            } else {
                addSlot(new Slot(inventory, col, 8 + col * 18, 142));
            }
        }
    }

    private Optional<CameraBodySpec> body() {
        return Specs.body(player.level().registryAccess(), player.getItemInHand(hand));
    }

    /**
     * Mount, battery type and card form must match the body, like the real thing. Optical parts are
     * checked as a chain (body -> adapter -> teleconverter -> lens) against what is already inserted.
     */
    public boolean accepts(int part, ItemStack stack) {
        Optional<CameraBodySpec> body = body();
        if (body.isEmpty()) {
            return false;
        }
        var r = player.level().registryAccess();
        var lens = Specs.lens(r, parts.getItem(CameraParts.SLOT_LENS)).orElse(null);
        var adapter = Specs.adapter(r, parts.getItem(CameraParts.SLOT_ADAPTER)).orElse(null);
        var tc = Specs.teleconverter(r, parts.getItem(CameraParts.SLOT_TC)).orElse(null);
        return switch (part) {
            case CameraParts.SLOT_LENS -> stack.getItem() instanceof LensItem && Specs.lens(r, stack)
                    .map(l -> OpticalChain.incompatibility(body.get(), l, adapter, tc) == null).orElse(false);
            case CameraParts.SLOT_ADAPTER -> stack.is(RCItems.MOUNT_ADAPTER.get()) && Specs.adapter(r, stack)
                    .map(a -> OpticalChain.incompatibility(body.get(), lens, a, tc) == null).orElse(false);
            case CameraParts.SLOT_TC -> stack.is(RCItems.TELECONVERTER.get()) && Specs.teleconverter(r, stack)
                    .map(t -> OpticalChain.incompatibility(body.get(), lens, adapter, t) == null).orElse(false);
            case CameraParts.SLOT_BATTERY -> stack.getItem() instanceof BatteryItem
                    && Objects.equals(stack.get(RCComponents.BATTERY_TYPE.get()), body.get().batteryType());
            default -> stack.getItem() instanceof MemoryCardItem
                    && Specs.card(r, stack).map(c -> body.get().cardForms().contains(c.form())).orElse(false);
        };
    }

    private void save() {
        if (loading) {
            return;
        }
        ItemStack camera = player.getItemInHand(hand);
        if (!CameraHelper.isCamera(camera)) {
            return;
        }
        CameraHelper.setParts(camera, new CameraParts(parts.getItem(0).copy(), parts.getItem(1).copy(),
                parts.getItem(2).copy(), parts.getItem(3).copy(), parts.getItem(4).copy()));
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < PART_SLOTS) {
            if (!moveItemStackTo(stack, PART_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = false;
            for (int i = 0; i < PART_SLOTS && !moved; i++) {
                if (accepts(i, stack) && !slots.get(i).hasItem()) {
                    moved = moveItemStackTo(stack, i, i + 1, false);
                }
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
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
        return CameraHelper.isCamera(p.getItemInHand(hand));
    }

    /** The camera itself must not be moved while it is open. */
    private static final class LockedSlot extends Slot {
        LockedSlot(Inventory inv, int index, int x, int y) {
            super(inv, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
