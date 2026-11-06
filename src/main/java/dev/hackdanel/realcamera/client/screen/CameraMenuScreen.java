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
    private final dev.hackdanel.realcamera.camera.CameraRef hand;
    private CameraSettings settings;
    private final List<Row> rows = new ArrayList<>();

    private record Row(String key, Function<CameraSettings, Component> value, UnaryOperator<CameraSettings> next) {}

    public CameraMenuScreen(dev.hackdanel.realcamera.camera.CameraRef hand) {
        super(Component.translatable("gui.realcamera.camera_menu"));
        this.hand = hand;
    }

    private ItemStack camera() {
        return Viewfinder.camera();
    }

    @Override
    protected void init() {
        settings = Viewfinder.isActive() ? Viewfinder.settings() : CameraHelper.settings(camera());
        CameraBodySpec body = Specs.body(minecraft.level.registryAccess(), camera()).orElse(null);
        int profiles = body == null ? 1 : Math.max(1, body.colorProfiles().size());
        rows.clear();
        rows.add(new Row("mode", s -> Component.translatable("gui.realcamera.mode." + (body == null ? s.mode() : body.coerceMode(s.mode()))),
                s -> body == null ? s.withMode((s.mode() + 1) % CameraSettings.MODE_COUNT)
                        : s.withMode(dev.hackdanel.realcamera.client.camera.Viewfinder.nextMode(body, s.mode()))));
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
        rows.add(new Row("shutter_type", s -> Component.translatable("gui.realcamera.shutter_type." + (body == null ? s.shutterType() : body.coerceShutterType(s.shutterType()))),
                s -> body == null ? s.withShutterType((s.shutterType() + 1) % CameraSettings.SHUTTER_TYPE_COUNT)
                        : s.withShutterType(dev.hackdanel.realcamera.client.camera.Viewfinder.nextShutterType(body, s.shutterType()))));
        rows.add(new Row("drive", s -> Component.translatable("gui.realcamera.drive." + s.drive()),
                s -> s.withDrive((s.drive() + 1) % CameraSettings.DRIVE_COUNT)));
        rows.add(new Row("file_format", s -> Component.translatable("gui.realcamera.file_format." + s.fileFormat()),
                s -> s.withFileFormat((s.fileFormat() + 1) % CameraSettings.FORMAT_COUNT)));
        rows.add(new Row("af_area", s -> Component.translatable("gui.realcamera.af_area." + s.afArea()),
                s -> s.withAfArea((s.afArea() + 1) % CameraSettings.AF_AREA_COUNT)));
        rows.add(new Row("subject_detect", s -> onOff(s.subjectDetect()), s -> s.withSubjectDetect(!s.subjectDetect())));
        rows.add(new Row("peaking", s -> onOff(s.peaking()), s -> s.withPeaking(!s.peaking())));
        rows.add(new Row("stabilization", s -> onOff(s.stabilization()), s -> s.withStabilization(!s.stabilization())));
        rows.add(new Row("crop_mode", s -> Component.translatable("gui.realcamera.crop_mode." + s.cropMode()),
                s -> s.withCropMode((s.cropMode() + 1) % CameraSettings.CROP_COUNT)));
        rows.add(new Row("flash_mode", s -> Component.translatable("gui.realcamera.flash_mode." + s.flashMode()),
                s -> s.withFlashMode((s.flashMode() + 1) % CameraSettings.FLASH_MODE_COUNT)));
        rows.add(new Row("flash_comp", s -> Component.literal(dev.hackdanel.realcamera.optics.ExposureScales.evCompLabel(s.flashComp())),
                s -> s.withFlashComp(s.flashComp() >= 6 ? -6 : s.flashComp() + 1)));
        rows.add(new Row("lens_correction", s -> onOff(s.lensCorrection()), s -> s.withLensCorrection(!s.lensCorrection())));

        boolean film = body != null && body.isFilm();
        if (film) {
            // nothing digital on a film camera: no file format, picture profiles or white balance
            java.util.Set<String> digital = java.util.Set.of("white_balance", "kelvin", "quality", "profile", "shutter_type",
                    "file_format", "crop_mode", "subject_detect", "peaking", "lens_correction", "stabilization");
            rows.removeIf(r -> digital.contains(r.key()));
        }
        // two columns so all settings fit on small screens
        int perColumn = (rows.size() + 1) / 2;
        int top = Math.max(24, height / 2 - perColumn * 11);
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            int col = i / perColumn, row = i % perColumn;
            Button b = Button.builder(label(r), btn -> {
                settings = r.next().apply(settings);
                Viewfinder.setSettingsAndSend(hand, settings);
                btn.setMessage(label(r));
            }).bounds(width / 2 - 204 + col * 208, top + row * 22, 200, 20).build();
            addRenderableWidget(b);
        }
        if (film) {
            addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.rewind"), btn -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.hackdanel.realcamera.network.Payloads.RewindFilm(hand));
                onClose();
            }).bounds(width / 2 - 100, top + perColumn * 22 + 4, 200, 20).build());
        }
    }

    private Component profileName(CameraBodySpec body, int index) {
        if (body == null) return Component.literal("-");
        return Specs.profile(minecraft.level.registryAccess(), body, index)
                .map(ColorProfileSpec::name).<Component>map(Component::literal).orElse(Component.literal("-"));
    }

    private static Component onOff(boolean v) {
        return Component.translatable(v ? "options.on" : "options.off");
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
        int perColumn = (rows.size() + 1) / 2;
        g.drawCenteredString(font, title, width / 2, Math.max(24, height / 2 - perColumn * 11) - 14, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
