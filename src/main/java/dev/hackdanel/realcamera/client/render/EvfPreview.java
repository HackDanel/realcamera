package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.client.hud.FrameSampler;
import dev.hackdanel.realcamera.client.hud.ViewfinderHud;
import dev.hackdanel.realcamera.processing.ImageParams;
import dev.hackdanel.realcamera.processing.LiveView;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

/**
 * Exposure simulation of an electronic viewfinder: the screen shows what the photo will make of the game's picture,
 * pixel by pixel ({@link LiveView}, shader {@code realcamera_evf}): the brightening of a dimly drawn scene (by the
 * game's light curve, or with a shader pack from what the pack drew, like the photo), the exposure error, filters,
 * vignetting, the sensor's noise at this ISO, clipping, white balance, highlight roll-off and the colour profile.
 * Runs on the finished picture after the live depth of field ({@link DofPreview} calls it), before the GUI.
 *
 * <p>Off on optical finders and rangefinders (they show the world as it is), on film bodies, and while a photo is
 * being captured.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class EvfPreview {
    @Nullable
    private static ShaderInstance shader;
    @Nullable
    private static TextureTarget out;
    /** The look of the current frame, or null when the screen shows the game's picture unchanged. */
    @Nullable
    private static LiveView look;
    private static boolean appliedThisFrame;
    /** Brightening of a shader pack's picture, smoothed (the pack's picture is measured every few frames). */
    private static double packRelight = 1;
    private static int frame;

    private EvfPreview() {}

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(RealCamera.MODID, "realcamera_evf"), DefaultVertexFormat.BLIT_SCREEN),
                s -> shader = s);
    }

    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        appliedThisFrame = false;
        look = null;
    }

    /** The screen of this frame already shows the photo's look (the HUD must not darken or brighten it again). */
    public static boolean applied() {
        return appliedThisFrame;
    }

    /** The photo's look shown this frame, for the histogram; null when not simulated. */
    @Nullable
    public static LiveView look() {
        return look;
    }

    /** The camera shows its picture on a screen: an electronic viewfinder, a hybrid one, or only a rear screen. */
    public static boolean electronicFinder() {
        String type = Viewfinder.body().viewfinder();
        return "evf".equals(type) || "none".equals(type) || "hybrid".equals(type);
    }

    private static boolean enabled() {
        return shader != null && Viewfinder.isActive() && !PhotoCapture.capturing() && Viewfinder.exposure() != null
                && electronicFinder() && !Viewfinder.body().isFilm() && Minecraft.getInstance().level != null;
    }

    /** Called once the picture of this frame is final (and blurred by the live depth of field). */
    static void apply(RenderTarget main) {
        if (appliedThisFrame || !enabled() || main.width < 4 || main.height < 4) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int w = main.width, h = main.height;
        double aspect = Viewfinder.sensor().aspect();
        double fw = w, fh = h;
        if ((double) w / h >= aspect) fw = h * aspect;
        else fh = w / aspect;
        LiveView view = view(mc, (int) Math.round(fw), (int) Math.round(fh), aspect);
        if (view == null) {
            return;
        }
        look = view;
        appliedThisFrame = true;
        frame++;

        out = ensure(out, w, h);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        ShaderInstance s = shader;
        s.setSampler("DiffuseSampler", main.getColorTextureId());
        s.safeGetUniform("FrameRect").set((float) ((w - fw) / 2 / w), (float) ((h - fh) / 2 / h), (float) (fw / w),
                (float) (fh / h));
        s.safeGetUniform("FrameAspect").set((float) aspect);
        // the magnifier shows the middle of the frame: vignetting and the graduated filter as they are there
        s.safeGetUniform("Zoom").set(Viewfinder.magnified() ? (float) Viewfinder.MAGNIFY : 1f);
        s.safeGetUniform("Filter").set((float) view.filterR(), (float) view.filterG(), (float) view.filterB());
        s.safeGetUniform("Gain").set((float) view.gain());
        s.safeGetUniform("Gnd").set((float) view.gndStops());
        s.safeGetUniform("Vignette").set((float) view.vignetteEv(), (float) view.correctEv());
        s.safeGetUniform("Saturation").set((float) view.saturation());
        var c = view.colour();
        s.safeGetUniform("WhiteBalance").set((float) c.wbR(), (float) c.wbG(), (float) c.wbB());
        s.safeGetUniform("Knee").set((float) view.knee());
        s.safeGetUniform("Tint").set((float) c.tintR(), (float) c.tintG(), (float) c.tintB());
        s.safeGetUniform("Profile").set((float) c.saturation(), (float) c.contrast(), (float) c.shadowLift(),
                c.monochrome() ? 1f : 0f);
        var n = view.noise();
        s.safeGetUniform("Noise").set((float) n.shot(), (float) n.read2(), (float) n.chroma(), (float) n.avg());
        s.safeGetUniform("NoiseRow").set((float) n.rowSigma(), (float) (frame % 997));
        out.bindWrite(true);
        s.apply();
        BufferBuilder b = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        b.addVertex(0f, 0f, 0f);
        b.addVertex(1f, 0f, 0f);
        b.addVertex(1f, 1f, 0f);
        b.addVertex(0f, 1f, 0f);
        BufferUploader.draw(b.buildOrThrow());
        s.clear();

        main.bindWrite(true);
        out.blitToScreen(w, h);
        main.bindWrite(true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /**
     * The photo's parameters for the current settings, built like the photo's ({@link PhotoCapture#digitalParams}),
     * and the brightening the photo will apply to the game's picture.
     */
    @Nullable
    private static LiveView view(Minecraft mc, int fw, int fh, double aspect) {
        var exp = Viewfinder.exposure();
        var settings = Viewfinder.settings();
        var regs = mc.level.registryAccess();
        var parts = CameraHelper.parts(Viewfinder.camera());
        var filter = Specs.filter(regs, parts.filter()).orElse(null);
        double fr = 1, fg = 1, fb = 1;
        if (filter != null && "color".equals(filter.kind())) {
            fr = filter.r();
            fg = filter.g();
            fb = filter.b();
        }
        var extras = new ImageParams.Extras(0, 1, false, fr, fg, fb, 0, 0, new float[0])
                .withFilterEffects(0, filter != null && "gnd".equals(filter.kind()) ? filter.gndStops() : 0, 0);
        var profile = Specs.profile(regs, Viewfinder.body(), settings.profile()).orElse(null);
        ImageParams p = PhotoCapture.digitalParams(Viewfinder.chain(), settings, exp, Viewfinder.focal(),
                Viewfinder.liveFocusDistance(), ViewfinderHud.previewEv(exp, settings), Viewfinder.meteredK(), extras,
                profile, Math.max(1, fw), Math.max(1, fh));
        // the same brightening as the photo (PhotoCapture.relight / ShaderCompat.packRelight)
        double vanilla = Math.max(1, Math.min(16, 1 / Math.max(0.01, Viewfinder.meteredGameLight())));
        double relight = vanilla;
        if (ShaderCompat.packInUse()) {
            double measured = FrameSampler.meteredLuminance(Viewfinder.meterSurfaceWeights(), aspect);
            // nothing to measure (only sky, unknown reflectance): the light curve, as the photo does
            double target = ShaderCompat.packRelight(measured, Viewfinder.meteredAlbedo(), vanilla);
            packRelight = Math.exp(Math.log(packRelight) + (Math.log(target) - Math.log(packRelight)) * 0.2);
            relight = packRelight;
        }
        LiveView view = LiveView.of(p, relight, settings.lensCorrection());
        if (frame % 40 == 0) {
            RealCamera.LOGGER.debug("EVF: relight x{} (light curve x{}, pack {}), exposure {} EV, gain x{}", relight,
                    vanilla, ShaderCompat.packInUse(), ViewfinderHud.previewEv(exp, settings), view.gain());
        }
        return view;
    }

    private static TextureTarget ensure(@Nullable TextureTarget t, int w, int h) {
        if (t == null) {
            t = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            t.setFilterMode(GL11.GL_NEAREST);
        } else if (t.width != w || t.height != h) {
            t.resize(w, h, Minecraft.ON_OSX);
            t.setFilterMode(GL11.GL_NEAREST);
        }
        return t;
    }
}
