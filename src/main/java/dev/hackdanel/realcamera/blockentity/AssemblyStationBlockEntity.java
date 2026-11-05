package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.menu.AssemblyStationMenu;
import dev.hackdanel.realcamera.recipe.AssemblyInput;
import dev.hackdanel.realcamera.recipe.AssemblyRecipe;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCRecipes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Photo workshop: 6 input slots, 1 output, runs assembly recipes over time. */
public class AssemblyStationBlockEntity extends MachineBlockEntity {
    public static final int INPUTS = 6, OUTPUT = 6;

    private int progress;
    private int maxProgress;
    private boolean dirty = true;
    private RecipeHolder<AssemblyRecipe> current;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return i == 0 ? progress : maxProgress;
        }

        @Override
        public void set(int i, int v) {
            if (i == 0) progress = v; else maxProgress = v;
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    public AssemblyStationBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.ASSEMBLY_STATION.get(), pos, state, 7);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return slot != OUTPUT;
    }

    @Override
    protected void onItemsChanged(int slot) {
        dirty = true;
    }

    private AssemblyInput input() {
        List<ItemStack> list = new ArrayList<>(INPUTS);
        for (int i = 0; i < INPUTS; i++) {
            list.add(items.getStackInSlot(i));
        }
        return new AssemblyInput(list);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AssemblyStationBlockEntity be) {
        if (be.dirty) {
            be.dirty = false;
            AssemblyInput input = be.input();
            Optional<RecipeHolder<AssemblyRecipe>> found = level.getRecipeManager()
                    .getRecipeFor(RCRecipes.ASSEMBLY_TYPE.get(), input, level);
            RecipeHolder<AssemblyRecipe> next = found.orElse(null);
            if (next != be.current) {
                be.current = next;
                be.progress = 0;
                be.maxProgress = next == null ? 0 : next.value().time();
            }
        }
        if (be.current == null) {
            return;
        }
        AssemblyRecipe recipe = be.current.value();
        ItemStack result = recipe.assemble(be.input(), level.registryAccess());
        ItemStack out = be.items.getStackInSlot(OUTPUT);
        boolean fits = out.isEmpty() || (ItemStack.isSameItemSameComponents(out, result)
                && out.getCount() + result.getCount() <= out.getMaxStackSize());
        if (!fits) {
            return;
        }
        if (++be.progress >= be.maxProgress) {
            int[] take = recipe.allocate(be.input());
            if (take == null) {
                be.dirty = true;
                return;
            }
            for (int s = 0; s < INPUTS; s++) {
                if (take[s] > 0) {
                    be.items.extractItem(s, take[s], false);
                }
            }
            if (out.isEmpty()) {
                be.items.setStackInSlot(OUTPUT, result);
            } else {
                out.grow(result.getCount());
                be.items.setStackInSlot(OUTPUT, out);
            }
            be.progress = 0;
            be.dirty = true;
            level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.6f, 1.3f);
        }
        be.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt("progress");
        dirty = true;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.realcamera.assembly_station");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new AssemblyStationMenu(id, inv, this, data);
    }
}
