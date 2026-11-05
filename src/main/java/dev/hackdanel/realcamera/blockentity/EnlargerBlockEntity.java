package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.component.FilmRoll;
import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.gameplay.Darkroom;
import dev.hackdanel.realcamera.item.FilmRollItem;
import dev.hackdanel.realcamera.item.PhotoPaperItem;
import dev.hackdanel.realcamera.menu.EnlargerMenu;
import dev.hackdanel.realcamera.photo.FilmLab;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Enlarger: projects a frame of a developed roll onto darkroom paper. Exposure (lighter/darker) and the
 * contrast grade of black-and-white paper are set before exposing. Paper only survives under a safelight.
 */
public class EnlargerBlockEntity extends MachineBlockEntity {
    public static final int SLOT_FILM = 0, SLOT_PAPER = 1, SLOT_OUTPUT = 2, SLOTS = 3;
    public static final int EXPOSE_TICKS = 60;
    public static final int B_PREV = 0, B_NEXT = 1, B_DARKER = 2, B_LIGHTER = 3, B_SOFTER = 4, B_HARDER = 5, B_EXPOSE = 6;
    public static final String[] DARKROOM_PAPERS = {"rc_bw", "fiber_bw", "ra4"};

    private int selected, exposure, grade = 2, progress = -1;
    private boolean fogged, waiting;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return switch (i) {
                case 0 -> selected;
                case 1 -> exposure;
                case 2 -> grade;
                case 3 -> progress;
                case 4 -> frames().size();
                default -> level != null && !level.isClientSide && Darkroom.darkCached(level, worldPosition, true) ? 1 : 0;
            };
        }

        @Override
        public void set(int i, int v) {
            switch (i) {
                case 0 -> selected = v;
                case 1 -> exposure = v;
                case 2 -> grade = v;
                case 3 -> progress = v;
                default -> {}
            }
        }

        @Override
        public int getCount() {
            return 6;
        }
    };

    public EnlargerBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.ENLARGER.get(), pos, state, SLOTS);
    }

    public static boolean darkroomPaper(ItemStack stack) {
        PaperData p = stack.get(RCComponents.PAPER.get());
        return p != null && List.of(DARKROOM_PAPERS).contains(p.finish());
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_FILM -> stack.getItem() instanceof FilmRollItem && FilmRollItem.roll(stack).state() == FilmRoll.DEVELOPED;
            case SLOT_PAPER -> stack.getItem() instanceof PhotoPaperItem && darkroomPaper(stack);
            default -> false;
        };
    }

    @Override
    protected int slotLimit(int slot) {
        return slot == SLOT_PAPER ? 64 : 1;
    }

    @Override
    protected void onItemsChanged(int slot) {
        if (slot == SLOT_FILM) selected = 0;
    }

    /** Developed frames of the roll in the carrier. */
    public List<UUID> frames() {
        ItemStack film = items.getStackInSlot(SLOT_FILM);
        FilmRoll r = film.get(RCComponents.FILM.get());
        if (r == null || r.id().isEmpty() || level == null || level.getServer() == null) return List.of();
        PhotoIndex index = PhotoStorage.index(level.getServer());
        List<UUID> out = new ArrayList<>();
        for (UUID id : PhotoStorage.cards(level.getServer()).photos(r.id().get())) {
            PhotoIndex.Entry e = index.get(id);
            if (e != null && !e.latent() && !e.revoked()) out.add(id);
        }
        return out;
    }

    @Nullable
    public UUID rollId() {
        FilmRoll r = items.getStackInSlot(SLOT_FILM).get(RCComponents.FILM.get());
        return r == null ? null : r.id().orElse(null);
    }

    public void handleButton(Player player, int id) {
        if (progress >= 0 || waiting) return;
        int n = frames().size();
        switch (id) {
            case B_PREV -> selected = n == 0 ? 0 : Math.floorMod(selected - 1, n);
            case B_NEXT -> selected = n == 0 ? 0 : Math.floorMod(selected + 1, n);
            case B_DARKER -> exposure = Math.max(-4, exposure - 1);
            case B_LIGHTER -> exposure = Math.min(4, exposure + 1);
            case B_SOFTER -> grade = Math.max(0, grade - 1);
            case B_HARDER -> grade = Math.min(5, grade + 1);
            case B_EXPOSE -> {
                if (n == 0 || items.getStackInSlot(SLOT_PAPER).isEmpty() || !items.getStackInSlot(SLOT_OUTPUT).isEmpty()) return;
                fogged = !Darkroom.dark(level, worldPosition, true);
                if (fogged) {
                    player.displayClientMessage(Component.translatable("message.realcamera.enlarger.fogged"), true);
                }
                progress = 0;
                level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 1.5f);
            }
            default -> {}
        }
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EnlargerBlockEntity be) {
        if (be.progress < 0) return;
        if (++be.progress >= EXPOSE_TICKS) {
            be.progress = -1;
            be.expose();
        }
        be.setChanged();
    }

    private void expose() {
        List<UUID> list = frames();
        ItemStack paperStack = items.getStackInSlot(SLOT_PAPER);
        PaperData paper = paperStack.get(RCComponents.PAPER.get());
        if (list.isEmpty() || paper == null || level.getServer() == null) return;
        UUID frame = list.get(Math.min(selected, list.size() - 1));
        items.extractItem(SLOT_PAPER, 1, false);
        waiting = true;
        var server = level.getServer();
        FilmLab.enlarge(server, frame, new UUID(0, 0), exposure, grade, paper.finish(), fogged, meta -> {
            waiting = false;
            if (meta.isEmpty() || isRemoved()) return;
            PhotoIndex index = PhotoStorage.index(server);
            PhotoIndex.Entry e = index.get(meta.get().id());
            if (e != null) index.put(e.withPrints(1));
            ItemStack print = new ItemStack(RCItems.PHOTO_PRINT.get());
            print.set(RCComponents.PRINT.get(), new PrintData(meta.get(), paper.size(), paper.finish(), 0, ""));
            items.setStackInSlot(SLOT_OUTPUT, print);
            level.playSound(null, worldPosition, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 0.8f);
        });
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("selected", selected);
        tag.putInt("exposure", exposure);
        tag.putInt("grade", grade);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        selected = tag.getInt("selected");
        exposure = tag.getInt("exposure");
        grade = tag.contains("grade") ? tag.getInt("grade") : 2;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.realcamera.enlarger");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new EnlargerMenu(id, inv, this, data);
    }
}
