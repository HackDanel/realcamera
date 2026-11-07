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
    /** Two rows: optics (lens, adapter, teleconverter, filter) and power/storage (flash, battery, grip, card). */
    public static final int[] SLOT_X = {26, 62, 98, 134, 26, 62, 98, 134};
    public static final int[] SLOT_Y = {17, 17, 17, 17, 48, 48, 48, 48};

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
            addSlot(new Slot(parts, i, SLOT_X[i], SLOT_Y[i]) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return accepts(part, stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public boolean mayPickup(Player p) {
                    // a built-in lens stays where it is
                    return !(part == CameraParts.SLOT_LENS && body().map(b -> b.hasFixedLens()).orElse(false))
                            && !(part == CameraParts.SLOT_CARD && body().map(b -> b.internalStorage()).orElse(false));
                }

                @Override
                public void onTake(Player p, ItemStack stack) {
                    if (part == CameraParts.SLOT_CARD) {
                        openBack(p, stack);
                    }
                    super.onTake(p, stack);
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
            case CameraParts.SLOT_LENS -> !body.get().hasFixedLens() && stack.getItem() instanceof LensItem && Specs.lens(r, stack)
                    .map(l -> OpticalChain.incompatibility(body.get(), l, adapter, tc) == null).orElse(false);
            case CameraParts.SLOT_ADAPTER -> stack.is(RCItems.MOUNT_ADAPTER.get()) && Specs.adapter(r, stack)
                    .map(a -> OpticalChain.incompatibility(body.get(), lens, a, tc) == null).orElse(false);
            case CameraParts.SLOT_TC -> stack.is(RCItems.TELECONVERTER.get()) && Specs.teleconverter(r, stack)
                    .map(t -> OpticalChain.incompatibility(body.get(), lens, adapter, t) == null).orElse(false);
            case CameraParts.SLOT_FILTER -> stack.is(RCItems.FILTER.get()) && Specs.filter(r, stack)
                    .map(f -> lens == null || f.fits(lens.filterMm())).orElse(false);
            case CameraParts.SLOT_FLASH -> stack.is(RCItems.FLASH.get()) && Specs.flash(r, stack).isPresent();
            case CameraParts.SLOT_GRIP -> stack.is(RCItems.BATTERY_GRIP.get()) && Specs.grip(r, stack)
                    .map(g -> g.bodies().contains(player.getItemInHand(hand).get(RCComponents.BODY.get()))).orElse(false);
            case CameraParts.SLOT_BATTERY -> stack.getItem() instanceof BatteryItem
                    && Objects.equals(stack.get(RCComponents.BATTERY_TYPE.get()), body.get().batteryType());
            default -> body.get().isFilm()
                    ? stack.getItem() instanceof dev.hackdanel.realcamera.item.FilmRollItem
                        && dev.hackdanel.realcamera.item.FilmRollItem.roll(stack).state() <= dev.hackdanel.realcamera.component.FilmRoll.LOADED
                        && dev.hackdanel.realcamera.item.FilmRollItem.roll(stack).format().equals(body.get().film().get().format())
                    : stack.getItem() instanceof MemoryCardItem
                        && Specs.card(r, stack).map(c -> body.get().cardForms().contains(c.form())).orElse(false);
        };
    }

    /**
     * Taking film out of the camera. Rewound film is safe. A roll still on the take-up spool is ruined
     * where the light reaches it: in the light (level > 3) it gets fogged; in the dark it is wound back by
     * hand. An unused roll goes back to fresh.
     */
    public static void openBack(Player p, ItemStack stack) {
        var roll = stack.get(RCComponents.FILM.get());
        if (roll == null || roll.state() != dev.hackdanel.realcamera.component.FilmRoll.LOADED) return;
        if (roll.shot() == 0) {
            stack.set(RCComponents.FILM.get(), roll.withState(dev.hackdanel.realcamera.component.FilmRoll.FRESH));
            return;
        }
        var next = roll.withState(dev.hackdanel.realcamera.component.FilmRoll.EXPOSED);
        // 120 film is not rewound: it is wound on to the end onto the take-up spool, its paper backing keeps it safe
        if (!"120".equals(roll.format()) && !p.level().isClientSide && p.level().getMaxLocalRawBrightness(p.blockPosition()) > 3) {
            next = next.fog();
            p.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.realcamera.film_fogged"), true);
        }
        stack.set(RCComponents.FILM.get(), next);
    }

    private void save() {
        if (loading) {
            return;
        }
        ItemStack camera = player.getItemInHand(hand);
        if (!CameraHelper.isCamera(camera)) {
            return;
        }
        CameraParts next = CameraParts.EMPTY;
        for (int i = 0; i < PART_SLOTS; i++) {
            next = next.with(i, parts.getItem(i));
        }
        boolean lensChanged = !ItemStack.matches(CameraHelper.parts(camera).lens(), next.lens());
        CameraHelper.setParts(camera, next);
        if (lensChanged) {
            dev.hackdanel.realcamera.gameplay.WearEvents.lensChanged(player, camera);
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
        if (index < PART_SLOTS) {
            if (index == CameraParts.SLOT_CARD && p.getInventory().getFreeSlot() >= 0) {
                openBack(p, stack);
            }
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
