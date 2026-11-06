package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.item.InkCartridgeItem;
import dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity;
import dev.hackdanel.realcamera.item.MemoryCardItem;
import dev.hackdanel.realcamera.item.PhotoPaperItem;
import dev.hackdanel.realcamera.menu.PrinterMenu;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Photo printer: card + paper + CMYK cartridges in, prints out. */
public class PrinterBlockEntity extends MachineBlockEntity {
    public static final int SLOT_CARD = 0, SLOT_PAPER = 1, SLOT_INK_FIRST = 2, SLOT_OUTPUT = 6, SLOTS = 7;
    public static final int PRINT_TICKS = 60;
    public static final int BUTTON_PRINT = 0, BUTTON_PREV = 1, BUTTON_NEXT = 2;
    private static final int[] INK_BITS = {PrintData.INK_C, PrintData.INK_M, PrintData.INK_Y, PrintData.INK_K};

    private int progress = -1; // -1 = idle
    private int selected;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int i) {
            return switch (i) {
                case 0 -> progress;
                case 1 -> selected;
                default -> photoCount();
            };
        }

        @Override
        public void set(int i, int v) {
            if (i == 0) progress = v;
            else if (i == 1) selected = v;
        }

        @Override
        public int getCount() {
            return 3;
        }
    };

    public PrinterBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.PRINTER.get(), pos, state, SLOTS);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_CARD -> stack.getItem() instanceof MemoryCardItem || dev.hackdanel.realcamera.camera.CameraHelper.usbCard(stack) != null;
            case SLOT_PAPER -> stack.getItem() instanceof PhotoPaperItem && !EnlargerBlockEntity.darkroomPaper(stack);
            case SLOT_OUTPUT -> false;
            default -> stack.getItem() instanceof InkCartridgeItem ink
                    && ink.channel == INK_BITS[slot - SLOT_INK_FIRST];
        };
    }

    @Override
    protected int slotLimit(int slot) {
        return slot == SLOT_PAPER ? 64 : 1;
    }

    @Override
    protected void onItemsChanged(int slot) {
        if (slot == SLOT_CARD) {
            selected = 0;
            progress = -1;
        }
    }

    public UUID cardId() {
        ItemStack slot = items.getStackInSlot(SLOT_CARD);
        ItemStack inside = dev.hackdanel.realcamera.camera.CameraHelper.usbCard(slot); // a camera over USB/PictBridge
        CardData d = (inside != null ? inside : slot).get(RCComponents.CARD.get());
        return d == null ? null : d.id().orElse(null);
    }

    private List<UUID> photos() {
        UUID card = cardId();
        if (card == null || level == null || level.getServer() == null) {
            return List.of();
        }
        return PhotoStorage.cards(level.getServer()).photos(card);
    }

    private int photoCount() {
        return level != null && !level.isClientSide ? photos().size() : 0;
    }

    public boolean printing() {
        return progress >= 0;
    }

    public void handleButton(int id) {
        int count = photos().size();
        switch (id) {
            case BUTTON_PREV -> selected = count == 0 ? 0 : Math.floorMod(selected - 1, count);
            case BUTTON_NEXT -> selected = count == 0 ? 0 : Math.floorMod(selected + 1, count);
            case BUTTON_PRINT -> {
                if (progress < 0 && canStart()) {
                    progress = 0;
                }
            }
            default -> {}
        }
        setChanged();
    }

    private boolean canStart() {
        List<UUID> list = photos();
        return selected < list.size() && items.getStackInSlot(SLOT_OUTPUT).isEmpty()
                && items.getStackInSlot(SLOT_PAPER).has(RCComponents.PAPER.get());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PrinterBlockEntity be) {
        if (be.progress < 0) {
            return;
        }
        if (!be.canStart()) {
            be.progress = -1;
            return;
        }
        if (be.progress == 0) {
            level.playSound(null, pos, SoundEvents.UI_LOOM_TAKE_RESULT, SoundSource.BLOCKS, 0.7f, 0.6f);
        }
        if (++be.progress >= PRINT_TICKS) {
            be.progress = -1;
            be.finishPrint(level.getServer());
        }
        be.setChanged();
    }

    private void finishPrint(MinecraftServer server) {
        UUID photoId = photos().get(selected);
        PhotoIndex index = PhotoStorage.index(server);
        PhotoIndex.Entry entry = index.get(photoId);
        if (entry == null || entry.revoked()) {
            return;
        }
        ItemStack paperStack = items.getStackInSlot(SLOT_PAPER);
        PaperData paper = paperStack.get(RCComponents.PAPER.get());
        double[] coverage = inkCoverage(server, photoId);
        int missing = 0;
        for (int c = 0; c < 4; c++) {
            int need = (int) Math.ceil(coverage[c] * paper.inkCost());
            ItemStack cart = items.getStackInSlot(SLOT_INK_FIRST + c);
            int have = cart.isEmpty() ? 0 : InkCartridgeItem.ink(cart);
            if (have < need || cart.isEmpty()) {
                missing |= INK_BITS[c];
            }
            if (!cart.isEmpty() && need > 0) {
                ItemStack copy = cart.copy();
                copy.set(RCComponents.INK.get(), Math.max(0, have - need));
                items.setStackInSlot(SLOT_INK_FIRST + c, copy);
            }
        }
        items.extractItem(SLOT_PAPER, 1, false);
        ItemStack print = new ItemStack(RCItems.PHOTO_PRINT.get());
        print.set(RCComponents.PRINT.get(), new PrintData(entry.meta(), paper.size(), paper.finish(), missing, ""));
        items.setStackInSlot(SLOT_OUTPUT, print);
        index.put(entry.withPrints(entry.prints() + 1));
        level.playSound(null, worldPosition, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1.2f);
    }

    /** Average C, M, Y, K demand (0..1) of the stored image, from a heavily subsampled decode. */
    private static double[] inkCoverage(MinecraftServer server, UUID id) {
        double[] cmyk = {0.3, 0.3, 0.3, 0.3};
        try {
            byte[] data = PhotoStorage.read(server, id);
            try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
                ImageReader reader = readers.next();
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(16, 16, 0, 0);
                BufferedImage img = reader.read(0, param);
                reader.dispose();
                double c = 0, m = 0, y = 0, k = 0;
                int n = img.getWidth() * img.getHeight();
                for (int py = 0; py < img.getHeight(); py++) {
                    for (int px = 0; px < img.getWidth(); px++) {
                        int rgb = img.getRGB(px, py);
                        double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
                        double kk = 1 - Math.max(r, Math.max(g, b));
                        double d = 1 - kk + 1e-6;
                        c += (1 - r - kk) / d;
                        m += (1 - g - kk) / d;
                        y += (1 - b - kk) / d;
                        k += kk;
                    }
                }
                cmyk = new double[]{c / n, m / n, y / n, k / n};
            }
        } catch (IOException | RuntimeException e) {
            RealCamera.LOGGER.warn("Ink coverage for {} failed, using default", id, e);
        }
        return cmyk;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("selected", selected);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        selected = tag.getInt("selected");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.realcamera.photo_printer");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new PrinterMenu(id, inv, this, data);
    }
}
