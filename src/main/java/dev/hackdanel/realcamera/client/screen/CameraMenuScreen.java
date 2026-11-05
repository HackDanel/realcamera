package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.client.RCKeys;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.hud.ViewfinderHud;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.ColorProfileSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** The camera's menu (G): settings that live in menus on a real body rather than on dials. */
public class CameraMenuScreen extends Screen {
    private final InteractionHand hand;
    private CameraSettings settings;
    private final List<Row> rows = new ArrayList<>();

    private record Row(String key, Function<CameraSettings, Component> value, UnaryOperator<CameraSettings> next) {}

    public CameraMenuScreen(InteractionHand hand) {
        super(Component.translatable("gui.realcamera.camera_menu"));
        this.hand = hand;
    }

    private ItemStack camera() {
        return minecraft == null || minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemInHand(hand);
    }

    @Override
    protected void init() {
        settings = Viewfinder.isActive() ? Viewfinder.settings() : CameraHelper.settings(camera());
        CameraBodySpec body = Specs.body(minecraft.level.registryAccess(), camera()).orElse(null);
        int profiles = body == null ? 1 : Math.max(1, body.colorProfiles().size());
        rows.clear();
        rows.add(new Row("mode", s -> Component.translatable("gui.realcamera.mode." + s.mode()),
                s -> s.withMode((s.mode() + 1) % CameraSettings.MODE_COUNT)));
        rows.add(new Row("focus_mode", s -> Component.translatable("gui.realcamera.focus_mode." + s.focusMode()),
                s -> s.withFocusMode((s.focusMode() + 1) % CameraSettings.FOCUS_COUNT)));
        rows.add(new Row("metering", s -> Component.translatable("gui.realcamera.metering." + s.metering()),
                s -> s.withMetering((s.metering() + 1) % CameraSettings.METER_COUNT)));
        rows.add(new Row("white_balance", s -> Component.literal(ViewfinderHud.wbLabel(s)),
                s -> s.withWhiteBalance((s.whiteBalance() + 1) % CameraSettings.WB_COUNT, s.kelvin())));
        rows.add(new Row("kelvin", s -> Component.literal(s.kelvin() + " K"),
                s -> s.withWhiteBalance(s.whiteBalance(), s.kelvin() >= 10000 ? 2500 : s.kelvin() + 500)));
        rows.add(new Row("quality", s -> Component.translatable("gui.realcamera.quality." + s.quality()),
                s -> s.withQuality((s.quality() + 1) % CameraSettings.QUALITY_COUNT)));
        rows.add(new Row("profile", s -> profileName(body, s.profile()), s -> s.withProfile((s.profile() + 1) % profiles)));
        rows.add(new Row("disp", s -> Component.translatable("gui.realcamera.disp." + s.disp()),
                s -> s.withDisp((s.disp() + 1) % CameraSettings.DISP_COUNT)));

        int y = height / 2 - rows.size() * 12;
        for (Row row : rows) {
            final Row r = row;
            Button b = Button.builder(label(r), btn -> {
                settings = r.next().apply(settings);
                Viewfinder.setSettingsAndSend(hand, settings);
                btn.setMessage(label(r));
            }).bounds(width / 2 - 110, y, 220, 20).build();
            addRenderableWidget(b);
            y += 24;
        }
    }

    private Component profileName(CameraBodySpec body, int index) {
        if (body == null) return Component.literal("-");
        return Specs.profile(minecraft.level.registryAccess(), body, index)
                .map(ColorProfileSpec::name).<Component>map(Component::literal).orElse(Component.literal("-"));
    }

    private Component label(Row r) {
        return Component.translatable("gui.realcamera.setting." + r.key()).append(": ").append(r.value().apply(settings));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (RCKeys.MENU.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - rows.size() * 12 - 16, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
