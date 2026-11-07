package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.camera.CameraHelper;

import dev.hackdanel.realcamera.blockentity.ComputerBlockEntity;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.client.editor.EditSession;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.menu.ComputerMenu;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Computer catalogue: thumbnails of the selected card (A or B), star ratings, delete, copy/move to the
 * other card, format, and "Edit" which opens the editor.
 */
public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
    private static final int GRID_X = 32, GRID_Y = 30, COLS = 4, ROWS = 2, THUMB_W = 48, THUMB_H = 32, CELL_W = 52,
            CELL_H = 46;
    private int card; // 0 = A, 1 = B
    private int page;
    @Nullable
    private UUID selected;
    private long formatArmedUntil;
    private long lastClick;
    private Button[] actionButtons = new Button[0];
    private Button formatButton;

    public ComputerScreen(ComputerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 248;
        imageHeight = 232;
        inventoryLabelX = ComputerMenu.INV_LEFT;
        inventoryLabelY = ComputerMenu.INV_TOP - 11;
    }

    @Override
    protected void init() {
        super.init();
        int l = leftPos, t = topPos;
        addRenderableWidget(Button.builder(Component.literal("A"), b -> switchCard(0)).bounds(l + GRID_X, t + 14, 20, 14).build());
        addRenderableWidget(Button.builder(Component.literal("B"), b -> switchCard(1)).bounds(l + GRID_X + 22, t + 14, 20, 14).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> page = Math.max(0, page - 1))
                .bounds(l + 200, t + 14, 16, 14).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> page = Math.min(pages() - 1, page + 1))
                .bounds(l + 218, t + 14, 16, 14).build());
        int by = t + GRID_Y + ROWS * CELL_H;
        Button edit = Button.builder(Component.translatable("gui.realcamera.computer.edit"), b -> openEditor())
                .bounds(l + GRID_X, by, 50, 14).build();
        Button copy = Button.builder(Component.translatable("gui.realcamera.computer.copy"), b -> action(Payloads.PC_COPY, 0))
                .bounds(l + GRID_X + 52, by, 50, 14).build();
        Button move = Button.builder(Component.translatable("gui.realcamera.computer.move"), b -> action(Payloads.PC_MOVE, 0))
                .bounds(l + GRID_X + 104, by, 50, 14).build();
        Button delete = Button.builder(Component.translatable("gui.realcamera.computer.delete"), b -> {
            action(Payloads.PC_DELETE, 0);
            selected = null;
        }).bounds(l + GRID_X + 156, by, 50, 14).build();
        actionButtons = new Button[]{edit, copy, move, delete};
        for (Button b : actionButtons) addRenderableWidget(b);
        formatButton = addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.computer.format"), b -> {
            if (System.currentTimeMillis() < formatArmedUntil) {
                action(Payloads.PC_FORMAT, 0);
                formatArmedUntil = 0;
                selected = null;
            } else {
                formatArmedUntil = System.currentTimeMillis() + 3000;
            }
        }).bounds(l + 4, t + ComputerMenu.INV_TOP, 70, 14).build());
    }

    private void switchCard(int c) {
        card = c;
        page = 0;
        selected = null;
    }

    @Nullable
    private UUID cardId() {
        return menu.cardId(card);
    }

    private List<PhotoMeta> photos() {
        UUID id = cardId();
        if (id == null || !readable(card)) return List.of();
        List<PhotoMeta> l = CardListings.get(id);
        return l == null ? List.of() : l;
    }

    private boolean readable(int slot) {
        ItemStack stack = menu.machineItem(slot);
        if (stack.isEmpty() || minecraft == null || minecraft.level == null) return false;
        if (CameraHelper.usbCard(stack) != null) return true; // USB: no card reader needed
        if (menu.isFilm(slot)) return menu.machineItem(ComputerBlockEntity.SLOT_SCANNER).is(RCItems.FILM_SCANNER.get());
        String form = Specs.card(minecraft.level.registryAccess(), stack).map(MemoryCardTypeSpec::form).orElse("sd");
        return form.equals("sd") || menu.machineItem(ComputerBlockEntity.SLOT_READER).is(RCItems.CFEXPRESS_READER.get());
    }

    private int pages() {
        return Math.max(1, (photos().size() + COLS * ROWS - 1) / (COLS * ROWS));
    }

    private void action(int action, int value) {
        if (cardId() == null || (selected == null && action != Payloads.PC_FORMAT)) return;
        PacketDistributor.sendToServer(new Payloads.ComputerAction(menu.pos(), action, card,
                selected == null ? new UUID(0, 0) : selected, value));
        CardListings.refresh(cardId());
    }

    @Nullable
    private PhotoMeta selectedMeta() {
        for (PhotoMeta m : photos()) {
            if (m.id().equals(selected)) return m;
        }
        return null;
    }

    private void openEditor() {
        PhotoMeta m = selectedMeta();
        if (m == null || minecraft == null) return;
        // frames of a film can't be written back: save edits onto the card in the other slot
        int target = menu.isFilm(card) ? 1 - card : card;
        minecraft.setScreen(new EditorScreen(this, new EditSession(m, menu.pos(), target)));
    }

    @Override
    protected void containerTick() {
        boolean has = selectedMeta() != null;
        for (Button b : actionButtons) b.active = has;
        if (actionButtons.length == 4) {
            boolean other = menu.cardId(1 - card) != null || !menu.machineItem(1 - card).isEmpty();
            actionButtons[1].active = has && other && readable(1 - card);
            actionButtons[2].active = has && other && readable(1 - card);
        }
        formatButton.active = cardId() != null && readable(card) && !menu.isFilm(card);
        if (actionButtons.length == 4 && menu.isFilm(card)) actionButtons[3].active = false;
        formatButton.setMessage(Component.translatable(System.currentTimeMillis() < formatArmedUntil
                ? "gui.realcamera.computer.format_confirm" : "gui.realcamera.computer.format"));
        page = Math.min(page, pages() - 1);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<PhotoMeta> list = photos();
        int base = page * COLS * ROWS;
        for (int i = 0; i < COLS * ROWS && base + i < list.size(); i++) {
            int cx = leftPos + GRID_X + (i % COLS) * CELL_W, cy = topPos + GRID_Y + (i / COLS) * CELL_H;
            PhotoMeta m = list.get(base + i);
            // stars under the thumbnail
            if (my >= cy + THUMB_H + 2 && my < cy + THUMB_H + 10 && mx >= cx && mx < cx + 45) {
                int stars = (int) ((mx - cx) / 9) + 1;
                int now = CardListings.rating(m.id());
                int value = stars == now ? 0 : stars;
                selected = m.id();
                CardListings.setRating(m.id(), value);
                action(Payloads.PC_RATE, value);
                return true;
            }
            if (mx >= cx && mx < cx + THUMB_W && my >= cy && my < cy + THUMB_H) {
                long now = System.currentTimeMillis();
                boolean dbl = m.id().equals(selected) && now - lastClick < 350;
                selected = m.id();
                lastClick = now;
                if (dbl) openEditor();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int l = leftPos, t = topPos;
        UiDraw.panel(g, l, t, imageWidth, imageHeight);
        // slots with labels
        UiDraw.slot(g, l + ComputerMenu.SLOT_X, t + ComputerMenu.CARD_A_Y);
        UiDraw.slot(g, l + ComputerMenu.SLOT_X, t + ComputerMenu.CARD_B_Y);
        UiDraw.slot(g, l + ComputerMenu.SLOT_X, t + ComputerMenu.READER_Y);
        UiDraw.slot(g, l + ComputerMenu.SLOT_X, t + ComputerMenu.SCANNER_Y);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                UiDraw.slot(g, l + ComputerMenu.INV_LEFT + col * 18, t + ComputerMenu.INV_TOP + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            UiDraw.slot(g, l + ComputerMenu.INV_LEFT + col * 18, t + ComputerMenu.INV_TOP + 58);
        }
        g.drawString(font, "A", l + 10, t + ComputerMenu.CARD_A_Y - 10, card == 0 ? 0xFF2050C0 : 0xFF404040, false);
        g.drawString(font, "B", l + 10, t + ComputerMenu.CARD_B_Y - 10, card == 1 ? 0xFF2050C0 : 0xFF404040, false);
        g.drawString(font, "CFx", l + 6, t + ComputerMenu.READER_Y - 10, 0xFF404040, false);
        g.drawString(font, "\u25A4", l + 26, t + ComputerMenu.SCANNER_Y + 4, 0xFF404040, false);
        // monitor
        int mx0 = l + GRID_X - 3, my0 = t + GRID_Y - 3, mw = COLS * CELL_W + 4, mh = ROWS * CELL_H + 4;
        UiDraw.inset(g, mx0, my0, mw, mh, 0xFF1C2233);
        List<PhotoMeta> list = photos();
        if (cardId() == null && menu.machineItem(card).isEmpty()) {
            center(g, Component.translatable("gui.realcamera.computer.insert_card"), mx0, my0, mw, mh);
        } else if (!readable(card)) {
            center(g, Component.translatable(menu.isFilm(card) ? "gui.realcamera.computer.need_scanner"
                    : "gui.realcamera.computer.need_reader"), mx0, my0, mw, mh);
        } else if (list.isEmpty()) {
            center(g, Component.translatable("gui.realcamera.printer.no_photos"), mx0, my0, mw, mh);
        }
        int base = page * COLS * ROWS;
        for (int i = 0; i < COLS * ROWS && base + i < list.size(); i++) {
            PhotoMeta m = list.get(base + i);
            int cx = l + GRID_X + (i % COLS) * CELL_W, cy = t + GRID_Y + (i / COLS) * CELL_H;
            if (m.id().equals(selected)) {
                g.fill(cx - 2, cy - 2, cx + THUMB_W + 2, cy + THUMB_H + 12, 0xFF4A6FB0);
            }
            UiDraw.photo(g, m.id(), cx, cy, THUMB_W, THUMB_H);
            if (m.editedFrom().isPresent()) {
                g.fill(cx + THUMB_W - 7, cy + 1, cx + THUMB_W - 1, cy + 7, 0xFFE0A030);
            }
            int rating = CardListings.rating(m.id());
            for (int s = 0; s < 5; s++) {
                g.drawString(font, "★", cx + s * 9, cy + THUMB_H + 2, s < rating ? 0xFFFFD040 : 0xFF505868, false);
            }
        }
    }

    private void center(GuiGraphics g, Component c, int x, int y, int w, int h) {
        g.drawString(font, c, x + (w - font.width(c)) / 2, y + h / 2 - 4, 0xFF8890A0, false);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 4, 0xFF404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF404040, false);
        List<PhotoMeta> list = photos();
        String pg = (page + 1) + "/" + pages();
        g.drawString(font, pg, 198 - font.width(pg), 17, 0xFF404040, false);
        // card usage
        ItemStack stack = CameraHelper.slotCard(menu.machineItem(card));
        CardData d = stack.get(RCComponents.CARD.get());
        if (d != null && minecraft != null && minecraft.level != null) {
            long cap = Specs.card(minecraft.level.registryAccess(), stack).map(MemoryCardTypeSpec::capacityBytes).orElse(0L);
            String use = String.format(Locale.ROOT, "%d · %.1f/%d GB", list.size(), d.usedBytes() / 1e9, cap / 1_000_000_000L);
            g.drawString(font, use, GRID_X + 46, 17, 0xFF404040, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        List<PhotoMeta> list = photos();
        int base = page * COLS * ROWS;
        for (int i = 0; i < COLS * ROWS && base + i < list.size(); i++) {
            int cx = leftPos + GRID_X + (i % COLS) * CELL_W, cy = topPos + GRID_Y + (i / COLS) * CELL_H;
            if (mouseX >= cx && mouseX < cx + THUMB_W && mouseY >= cy && mouseY < cy + THUMB_H) {
                PhotoMeta m = list.get(base + i);
                List<Component> lines = new java.util.ArrayList<>();
                lines.add(Component.literal(m.exif().camera()));
                lines.add(Component.literal(m.exif().lens()).withStyle(net.minecraft.ChatFormatting.GRAY));
                lines.add(Component.literal(String.format(Locale.ROOT, "F%.1f  ISO %d  %.0f mm  %d×%d", m.exif().fNumber(),
                        m.exif().iso(), m.exif().focal(), m.width(), m.height())).withStyle(net.minecraft.ChatFormatting.GRAY));
                if (m.editedFrom().isPresent()) {
                    lines.add(Component.translatable("gui.realcamera.computer.edited").withStyle(net.minecraft.ChatFormatting.GOLD));
                }
                g.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }
}
