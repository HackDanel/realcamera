package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.component.AlbumData;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.item.PhotoAlbumItem;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.registry.RCItems;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Photo album: a spread of two pages, one print per page with its caption. In hand you can insert
 * prints (from the other hand or the inventory), take them out and write captions; on a lectern it is
 * read-only with a "take" button.
 */
public class AlbumScreen extends Screen {
    private static final int PAGE_W = 150;
    private int pageH = 190;
    @Nullable
    private final InteractionHand hand;
    @Nullable
    private final BlockPos lectern;
    private List<PrintData> lecternPrints;
    private int spread;
    private final EditBox[] captions = new EditBox[2];

    private AlbumScreen(@Nullable InteractionHand hand, @Nullable BlockPos lectern, List<PrintData> lecternPrints) {
        super(Component.translatable("item.realcamera.photo_album"));
        this.hand = hand;
        this.lectern = lectern;
        this.lecternPrints = lecternPrints;
    }

    public static AlbumScreen inHand(InteractionHand hand) {
        return new AlbumScreen(hand, null, List.of());
    }

    public static AlbumScreen onLectern(BlockPos pos, List<PrintData> prints) {
        return new AlbumScreen(null, pos, prints);
    }

    private boolean editable() {
        return hand != null;
    }

    private List<PrintData> prints() {
        if (hand == null) return lecternPrints;
        ItemStack album = minecraft == null || minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemInHand(hand);
        return album.is(RCItems.PHOTO_ALBUM.get()) ? PhotoAlbumItem.data(album).prints() : List.of();
    }

    private int spreads() {
        int slots = Math.min(AlbumData.CAPACITY, prints().size() + (editable() ? 1 : 0));
        return Math.max(1, (slots + 1) / 2);
    }

    private int left() {
        return width / 2 - PAGE_W;
    }

    private int top() {
        return Math.max(14, (height - pageH) / 2 - 10);
    }

    @Override
    protected void init() {
        pageH = Math.max(120, Math.min(190, height - 52));
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int l = left(), t = top();
        addRenderableWidget(Button.builder(Component.literal("<"), b -> turn(-1)).bounds(l - 26, t + pageH / 2 - 10, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> turn(1)).bounds(l + PAGE_W * 2 + 6, t + pageH / 2 - 10, 20, 20).build());
        List<PrintData> prints = prints();
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            int px = l + side * PAGE_W;
            captions[side] = null;
            if (index < prints.size()) {
                if (editable()) {
                    EditBox box = new EditBox(font, px + 12, t + pageH - 44, PAGE_W - 24, 14,
                            Component.translatable("gui.realcamera.album.caption"));
                    box.setMaxLength(64);
                    box.setValue(prints.get(index).caption());
                    box.moveCursorToStart(false);
                    box.setHint(Component.translatable("gui.realcamera.album.caption"));
                    captions[side] = box;
                    addRenderableWidget(box);
                    addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.album.take"),
                            b -> send(Payloads.ALBUM_TAKE, index, "")).bounds(px + PAGE_W / 2 - 30, t + pageH - 24, 60, 16).build());
                }
            } else if (editable() && index == prints.size() && index < AlbumData.CAPACITY) {
                addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.album.insert"),
                        b -> send(Payloads.ALBUM_INSERT, index, "")).bounds(px + PAGE_W / 2 - 50, t + pageH / 2 - 10, 100, 20).build());
            }
        }
        if (lectern != null) {
            addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.album.take_album"), b -> {
                PacketDistributor.sendToServer(new Payloads.AlbumAction(-1, lectern, Payloads.ALBUM_TAKE_FROM_LECTERN, 0, ""));
                onClose();
            }).bounds(width / 2 - 104, t + pageH + 6, 100, 18).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(lectern != null ? width / 2 + 4 : width / 2 - 50, t + pageH + 6, 100, 18).build());
    }

    private void turn(int d) {
        saveCaptions();
        spread = Math.max(0, Math.min(spreads() - 1, spread + d));
        rebuild();
    }

    private void send(int action, int index, String text) {
        if (hand == null) return;
        saveCaptions();
        PacketDistributor.sendToServer(new Payloads.AlbumAction(hand.ordinal(), BlockPos.ZERO, action, index, text));
        pendingRebuild = 4;
    }

    private int pendingRebuild;

    private void saveCaptions() {
        if (!editable()) return;
        List<PrintData> prints = prints();
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            EditBox box = captions[side];
            if (box != null && index < prints.size() && !box.getValue().strip().equals(prints.get(index).caption())) {
                PacketDistributor.sendToServer(new Payloads.AlbumAction(hand.ordinal(), BlockPos.ZERO,
                        Payloads.ALBUM_CAPTION, index, box.getValue()));
            }
        }
    }

    @Override
    public void tick() {
        // inventory updates arrive a few ticks after an insert/take: refresh the page widgets then
        if (pendingRebuild > 0 && --pendingRebuild == 0) {
            spread = Math.max(0, Math.min(spreads() - 1, spread));
            rebuild();
        }
        if (editable() && (minecraft.player == null || !minecraft.player.getItemInHand(hand).is(RCItems.PHOTO_ALBUM.get()))) {
            onClose();
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            saveCaptions();
            setFocused(null);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        List<PrintData> prints = prints();
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            int[] r = photoRect(side, index < prints.size() ? prints.get(index) : null);
            if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                saveCaptions();
                minecraft.setScreen(new PrintViewerScreen(prints.get(index), this));
                return true;
            }
        }
        return false;
    }

    @Nullable
    private int[] photoRect(int side, @Nullable PrintData p) {
        if (p == null) return null;
        int px = left() + side * PAGE_W, t = top();
        int boxW = PAGE_W - 30, boxH = pageH - 70;
        double aspect = p.photo().height() == 0 ? 1.5 : (double) p.photo().width() / p.photo().height();
        int w = (int) Math.min(boxW, boxH * aspect), h = (int) Math.round(w / aspect);
        return new int[]{px + (PAGE_W - w) / 2, t + 14 + (boxH - h) / 2, w, h};
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int l = left(), t = top();
        // leather cover and two cream pages
        g.fill(l - 6, t - 6, l + PAGE_W * 2 + 6, t + pageH + 6, 0xFF4A2A1A);
        g.fill(l, t, l + PAGE_W - 1, t + pageH, 0xFFF3EEDD);
        g.fill(l + PAGE_W + 1, t, l + PAGE_W * 2, t + pageH, 0xFFF3EEDD);
        g.fill(l + PAGE_W - 1, t, l + PAGE_W + 1, t + pageH, 0xFFB9AE8F);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        List<PrintData> prints = prints();
        int t = top();
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            int px = left() + side * PAGE_W;
            if (index < prints.size()) {
                PrintData p = prints.get(index);
                int[] r = photoRect(side, p);
                g.fill(r[0] - 3, r[1] - 3, r[0] + r[2] + 3, r[1] + r[3] + 3, 0xFFFFFFFF);
                g.fill(r[0] - 4, r[1] - 4, r[0] + r[2] + 4, r[1] - 3, 0x40000000);
                UiDraw.photo(g, p.photo().id(), r[0], r[1], r[2], r[3]);
                if (!editable()) {
                    String c = p.caption().isEmpty() ? "" : "«" + p.caption() + "»";
                    g.drawCenteredString(font, c, px + PAGE_W / 2, t + pageH - 40, 0xFF3A2A1A);
                    g.drawCenteredString(font, p.photo().authorName(), px + PAGE_W / 2, t + pageH - 26, 0xFF7A6A50);
                }
            }
            String n = String.valueOf(index + 1);
            g.drawString(font, n, side == 0 ? px + 6 : px + PAGE_W - 6 - font.width(n), t + pageH - 11, 0xFF9A8A70, false);
        }
        Component title = Component.translatable("gui.realcamera.album.count", prints.size(), AlbumData.CAPACITY);
        g.drawCenteredString(font, title, width / 2, t - 11, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        saveCaptions();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
