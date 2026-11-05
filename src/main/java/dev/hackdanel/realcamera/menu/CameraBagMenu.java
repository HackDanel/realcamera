package dev.hackdanel.realcamera.menu;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.item.CameraBagItem;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Camera bag: 12 slots that accept only RealCamera gear (not another bag). */
public class CameraBagMenu extends AbstractContainerMenu {
    public static final int SLOTS = 12;

    private final Player player;
    private final InteractionHand hand;
    private final SimpleContainer contents = new SimpleContainer(SLOTS);
    private boolean loading;

    public CameraBagMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readEnum(InteractionHand.class));
    }

    public CameraBagMenu(int id, Inventory inv, InteractionHand hand) {
        super(RCMenus.CAMERA_BAG.get(), id);
        this.player = inv.player;
        this.hand = hand;
        loading = true;
        NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        player.getItemInHand(hand).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        for (int i = 0; i < SLOTS; i++) contents.setItem(i, items.get(i));
        loading = false;
        contents.addListener(c -> save());
        for (int i = 0; i < SLOTS; i++) {
            addSlot(new Slot(contents, i, 26 + (i % 6) * 22, 18 + (i / 6) * 22) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return accepts(stack);
                }
            });
        }
        int locked = hand == InteractionHand.MAIN_HAND ? inv.selected : -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            final boolean lock = col == locked;
            addSlot(new Slot(inv, col, 8 + col * 18, 142) {
                @Override
                public boolean mayPickup(Player p) {
                    return !lock;
                }
            });
        }
    }

    public static boolean accepts(ItemStack stack) {
        return RealCamera.MODID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())
                && !(stack.getItem() instanceof CameraBagItem);
    }

    private void save() {
        if (loading) return;
        ItemStack bag = player.getItemInHand(hand);
        if (!(bag.getItem() instanceof CameraBagItem)) return;
        NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < SLOTS; i++) items.set(i, contents.getItem(i).copy());
        bag.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < SLOTS) {
            if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!accepts(stack) || !moveItemStackTo(stack, 0, SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player p) {
        return p.getItemInHand(hand).getItem() instanceof CameraBagItem;
    }
}
