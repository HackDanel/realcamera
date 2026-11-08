package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.FilmRoll;
import dev.hackdanel.realcamera.gameplay.Darkroom;
import dev.hackdanel.realcamera.item.FilmRollItem;
import dev.hackdanel.realcamera.menu.DevelopingTankMenu;
import dev.hackdanel.realcamera.photo.FilmLab;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
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
 * Developing tank: an exposed (rewound) roll + the chemistry kit for its process (C-41, E-6, B&W).
 * Loading the reel needs darkness or a changing bag; otherwise the whole roll is fogged. Push/pull is
 * chosen before starting.
 */
public class DevelopingTankBlockEntity extends MachineBlockEntity {
    public static final int SLOT_ROLL = 0, SLOT_CHEMISTRY = 1, SLOT_BAG = 2, SLOT_OUTPUT = 3, SLOTS = 4;
    /** About a minute; the autotest runs the lab ten times faster. */
    public static final int DEVELOP_TICKS = Boolean.getBoolean("realcamera.fastLab") ? 120 : 1200;
    public static final int BUTTON_START = 0, BUTTON_PUSH = 1;

    private int progress = -1, push, fog;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return switch (i) {
                case 0 -> progress;
                case 1 -> push;
                default -> level != null && !level.isClientSide && Darkroom.darkCached(level, worldPosition, false) ? 1 : 0;
            };
        }

        @Override
        public void set(int i, int v) {
            if (i == 0) progress = v;
            else if (i == 1) push = v;
        }

        @Override
        public int getCount() {
            return 3;
        }
    };

    public DevelopingTankBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.DEVELOPING_TANK.get(), pos, state, SLOTS);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_ROLL -> stack.getItem() instanceof FilmRollItem && FilmRollItem.roll(stack).state() == FilmRoll.EXPOSED;
            case SLOT_CHEMISTRY -> stack.is(RCItems.CHEMISTRY_C41.get()) || stack.is(RCItems.CHEMISTRY_E6.get())
                    || stack.is(RCItems.CHEMISTRY_BW.get());
            case SLOT_BAG -> stack.is(RCItems.CHANGING_BAG.get());
            default -> false;
        };
    }

    @Override
    protected int slotLimit(int slot) {
        return 1;
    }

    /** Which chemistry the roll in the tank needs (c41 / e6 / bw), or "". */
    private String process() {
        ItemStack roll = items.getStackInSlot(SLOT_ROLL);
        return level == null ? "" : Specs.film(level.registryAccess(), roll).map(f -> f.process()).orElse("");
    }

    private boolean chemistryMatches() {
        ItemStack c = items.getStackInSlot(SLOT_CHEMISTRY);
        return switch (process()) {
            case "c41" -> c.is(RCItems.CHEMISTRY_C41.get());
            case "e6" -> c.is(RCItems.CHEMISTRY_E6.get());
            case "bw" -> c.is(RCItems.CHEMISTRY_BW.get());
            default -> false;
        };
    }

    public void handleButton(Player player, int id) {
        if (progress >= 0) return;
        if (id == BUTTON_PUSH) {
            push = push >= 2 ? -1 : push + 1;
        } else if (id == BUTTON_START) {
            ItemStack roll = items.getStackInSlot(SLOT_ROLL);
            if (roll.isEmpty() || !items.getStackInSlot(SLOT_OUTPUT).isEmpty()) return;
            if (!chemistryMatches()) {
                player.displayClientMessage(Component.translatable("message.realcamera.tank.wrong_chemistry"), true);
                return;
            }
            boolean safe = !items.getStackInSlot(SLOT_BAG).isEmpty() || Darkroom.dark(level, worldPosition, false);
            FilmRoll r = FilmRollItem.roll(roll);
            fog = !safe ? FilmLab.FOG_ALL : r.fogged() ? FilmLab.FOG_BACK_OPENED : FilmLab.FOG_NONE;
            if (!safe) {
                player.displayClientMessage(Component.translatable("message.realcamera.tank.fogged"), true);
            }
            progress = 0;
            developer = player.getUUID();
            level.playSound(null, worldPosition, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6f, 1.3f);
        }
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DevelopingTankBlockEntity be) {
        if (be.progress < 0) return;
        if (be.items.getStackInSlot(SLOT_ROLL).isEmpty()) {
            be.progress = -1;
            return;
        }
        be.progress++;
        if (be.progress % 200 == 100) {
            // agitation
            level.playSound(null, pos, SoundEvents.BOAT_PADDLE_WATER, SoundSource.BLOCKS, 0.4f, 1.4f);
        }
        if (be.progress >= DEVELOP_TICKS) {
            be.finish();
        }
        be.setChanged();
    }

    /**
     * The roll, the chemistry in the tank and the changing bag it was loaded with stay put until the process is
     * done (issue #30: the chemistry could be taken out mid-process and was never used up).
     */
    @Override
    protected boolean canTake(int slot) {
        return slot == SLOT_OUTPUT || progress < 0;
    }

    /** Breaking the tank open mid-process exposes the half-developed film to light. */
    @Override
    public void dropContents(Level level, BlockPos pos) {
        if (progress >= 0) {
            ItemStack roll = items.getStackInSlot(SLOT_ROLL).copy();
            FilmRoll r = FilmRollItem.roll(roll);
            roll.set(RCComponents.FILM.get(), r.developed(push).withState(FilmRoll.RUINED).fog());
            items.setStackInSlot(SLOT_ROLL, roll);
            progress = -1;
        }
        super.dropContents(level, pos);
    }

    private java.util.UUID developer;

    private void finish() {
        if (developer != null && level.getServer() != null
                && level.getServer().getPlayerList().getPlayer(developer) instanceof net.minecraft.server.level.ServerPlayer sp) {
            dev.hackdanel.realcamera.gameplay.PhotoEvents.count(sp, "film_developed");
        }
        developer = null;
        ItemStack roll = items.getStackInSlot(SLOT_ROLL).copy();
        FilmRoll r = FilmRollItem.roll(roll);
        int fogMode = fog;
        int pushStops = push;
        progress = -1;
        items.setStackInSlot(SLOT_ROLL, ItemStack.EMPTY);
        ItemStack chem = items.getStackInSlot(SLOT_CHEMISTRY).copy();
        chem.setDamageValue(chem.getDamageValue() + 1);
        items.setStackInSlot(SLOT_CHEMISTRY, chem.getDamageValue() >= chem.getMaxDamage() ? ItemStack.EMPTY : chem);
        roll.set(RCComponents.FILM.get(), fogMode == FilmLab.FOG_ALL
                ? r.developed(pushStops).withState(FilmRoll.RUINED).fog() : r.developed(pushStops));
        if (r.id().isPresent() && level.getServer() != null) {
            boolean mono = Specs.film(level.registryAccess(), roll).map(f -> f.monochrome()).orElse(false);
            FilmLab.developRoll(level.getServer(), r.id().get(), pushStops, fogMode, mono, () -> {});
        }
        items.setStackInSlot(SLOT_OUTPUT, roll);
        level.playSound(null, worldPosition, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8f, 1.2f);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("progress", progress);
        tag.putInt("push", push);
        tag.putInt("fog", fog);
        if (developer != null) tag.putUUID("developer", developer);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.contains("progress") ? tag.getInt("progress") : -1;
        push = tag.getInt("push");
        fog = tag.getInt("fog");
        developer = tag.hasUUID("developer") ? tag.getUUID("developer") : null;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.realcamera.developing_tank");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new DevelopingTankMenu(id, inv, this, data);
    }
}
