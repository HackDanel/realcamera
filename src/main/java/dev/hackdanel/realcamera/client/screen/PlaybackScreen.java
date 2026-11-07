package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.RCKeys;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** The camera's rear screen: browse the card, see shooting data, delete shots. */
public class PlaybackScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final dev.hackdanel.realcamera.camera.CameraRef hand;
    private int index = Integer.MAX_VALUE; // start at the newest shot
    private boolean confirmDelete;
    private Button deleteButton;

    public PlaybackScreen(dev.hackdanel.realcamera.camera.CameraRef hand) {
        super(Component.translatable("gui.realcamera.playback"));
        this.hand = hand;
    }

    private UUID cardId() {
        if (minecraft == null || minecraft.player == null) return null;
        ItemStack cam = Viewfinder.camera();
        if (!CameraHelper.isCamera(cam)) return null;
        CardData d = CameraHelper.parts(cam).card().get(RCComponents.CARD.get());
        return d == null ? null : d.id().orElse(null);
    }

    /** A memory card is in the camera (a fresh, never-used card has no id yet but is still a card). */
    private boolean hasCard() {
        if (minecraft == null || minecraft.player == null) return false;
        return CameraHelper.usbCard(Viewfinder.camera()) != null;
    }

    private List<PhotoMeta> photos() {
        UUID card = cardId();
        if (card == null) return List.of();
        List<PhotoMeta> list = CardListings.get(card);
        return list == null ? List.of() : list;
    }

    @Override
    protected void init() {
        int y = height - 26;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> step(-1)).bounds(width / 2 - 110, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> step(1)).bounds(width / 2 + 90, y, 20, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.delete"), b -> delete())
                .bounds(width / 2 - 40, y, 80, 20).build());
    }

    private void step(int d) {
        List<PhotoMeta> list = photos();
        if (list.isEmpty()) return;
        index = Math.floorMod(Math.min(index, list.size() - 1) + d, list.size());
        confirmDelete = false;
    }

    private void delete() {
        List<PhotoMeta> list = photos();
        if (list.isEmpty()) return;
        if (!confirmDelete) {
            confirmDelete = true;
            return;
        }
        confirmDelete = false;
        PhotoMeta p = list.get(Math.min(index, list.size() - 1));
        PacketDistributor.sendToServer(new Payloads.DeletePhoto(hand, p.id()));
        UUID card = cardId();
        if (card != null) CardListings.refresh(card);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_LEFT) {
            step(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            step(1);
            return true;
        }
        if (RCKeys.PLAYBACK.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        step(sy > 0 ? -1 : 1);
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        deleteButton.setMessage(Component.translatable(confirmDelete ? "gui.realcamera.delete_confirm" : "gui.realcamera.delete"));
        List<PhotoMeta> list = photos();
        if (!hasCard()) {
            g.drawCenteredString(font, Component.translatable("message.realcamera.no_card"), width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        if (list.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.realcamera.no_photos"), width / 2, height / 2, 0xFFAAAAAA);
            return;
        }
        int i = Math.min(index, list.size() - 1);
        PhotoMeta p = list.get(i);
        int top = 22, bottom = height - 64;
        UiDraw.photo(g, p.id(), 20, top, width - 40, bottom - top);
        g.drawString(font, (i + 1) + "/" + list.size(), 20, 8, 0xFFFFFFFF, true);
        g.drawString(font, DATE.format(Instant.ofEpochMilli(p.createdMillis())), width - 20 - font.width("0000-00-00 00:00"), 8,
                0xFFFFFFFF, true);
        PhotoMeta.Exif x = p.exif();
        String line = String.format(Locale.ROOT, "%s   F%.1f   ISO %d   %.0f mm   %s", shutter(x.shutter()), x.fNumber(),
                x.iso(), x.focal(), ExposureScales.evCompLabel(x.evComp()));
        g.drawCenteredString(font, line, width / 2, bottom + 6, 0xFFFFFFFF);
        g.drawCenteredString(font, x.camera() + "  ·  " + x.lens(), width / 2, bottom + 18, 0xFFAAAAAA);
    }

    static String shutter(double t) {
        return ExposureScales.SHUTTER_LABEL[ExposureScales.nearestShutter(t)];
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open(net.minecraft.client.Minecraft mc) {
        mc.setScreen(new PlaybackScreen(Viewfinder.ref()));
    }
}
