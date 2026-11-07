package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.client.hud.FrameSampler;
import dev.hackdanel.realcamera.config.ClientConfig;
import dev.hackdanel.realcamera.optics.Optics;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Live depth of field in the viewfinder: the world is blurred as the photo will be, from the depth buffer, the
 * focal length, the set aperture and the focus distance (which moves with the AF motor and the focus ring), so
 * focusing is visible. Right after the level is drawn the depth buffer becomes per-pixel blur radii; then, on the
 * finished picture (at once without a shader pack, before the GUI with one), half-resolution colour + radius,
 * a disc blur on it, and a full-resolution mix of the sharp and the blurred picture.
 *
 * <p>Off while a photo is taken (the photo gets its own, exact depth of field), behind a rangefinder (its
 * window shows everything sharp), when disabled in the config, and with a shader pack that does not leave the scene
 * depth in the depth buffer ({@link ShaderCompat#depthTrusted}). A shader pack with its own depth of field blurs on
 * top of this one.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class DofPreview {
    /**
     * Largest blur radius drawn, as a fraction of the screen height. The photo allows up to 30% of the width; 15% of
     * the height covers every real portrait/telephoto background (85 mm f/1.8 at 2 m: ~4.5%, 200 mm f/2 at 3 m: ~15%),
     * so the viewfinder shows the same blur as the photo.
     */
    private static final float MAX_RADIUS_OF_HEIGHT = 0.15f;
    /** Largest radius the blur pass works with, in its own (downscaled) pixels: picks the downscale factor. */
    private static final float BLUR_PASS_RADIUS = 20f;
    /** Upper bound on the blur pass's samples per pixel. */
    private static final int MAX_SAMPLES = 200;

    @Nullable
    private static ShaderInstance coc, prep, blur, composite;
    @Nullable
    private static TextureTarget radii, half, halfBlurred, full;
    /** The level's projection of this frame (the GUI replaces the current one). */
    private static final Matrix4f projection = new Matrix4f();
    /** Blur radii of this frame are computed and waiting for the shader pack's picture. */
    private static boolean radiiReady, sampledAfterPack;
    private static float maxFull = 16;
    /** Largest blur radius this frame can have (background at infinity, or a foreground at 0.3 m), full-res pixels. */
    private static float maxRadiusNow = 16;

    private DofPreview() {}

    /** The level's projection of the last frame (for converting distances to depth-buffer values). */
    public static Matrix4f levelProjection() {
        return projection;
    }

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) throws IOException {
        var res = event.getResourceProvider();
        event.registerShader(new ShaderInstance(res, rl("realcamera_dof_coc"), DefaultVertexFormat.BLIT_SCREEN), s -> coc = s);
        event.registerShader(new ShaderInstance(res, rl("realcamera_dof_prep"), DefaultVertexFormat.BLIT_SCREEN), s -> prep = s);
        event.registerShader(new ShaderInstance(res, rl("realcamera_dof_blur"), DefaultVertexFormat.BLIT_SCREEN), s -> blur = s);
        event.registerShader(new ShaderInstance(res, rl("realcamera_dof_composite"), DefaultVertexFormat.BLIT_SCREEN),
                s -> composite = s);
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(RealCamera.MODID, path);
    }

    /**
     * End of the level render: the depth buffer still holds the world (it is cleared before the hand and the GUI).
     * Turn it into blur radii now. Without a shader pack the picture is final too, so blur it right away; a shader
     * pack (Iris) draws its picture after this, so then the blur waits for the GUI.
     */
    @SubscribeEvent
    static void onLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || ShaderCompat.shadowPass()) {
            return;
        }
        radiiReady = false;
        projection.set(event.getProjectionMatrix());
        boolean pack = shaderPackInUse();
        if (pack && Viewfinder.isActive() && !PhotoCapture.capturing()) {
            // does this pack leave the real scene depth in the buffer? (if not, no live blur)
            ShaderCompat.probeDepth(Minecraft.getInstance().getMainRenderTarget(), projection);
        }
        if (!pack) {
            FrameSampler.sample(projection); // the sharp frame, for the histogram and the AF
        }
        if (!enabled()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main.width < 4 || main.height < 4) {
            return;
        }
        try {
            computeRadii(main);
            radiiReady = true;
            if (!pack) {
                blur(main);
            }
        } finally {
            restore(main);
        }
    }

    /** With a shader pack: its finished picture, before the GUI is drawn over it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onGuiPre(RenderGuiEvent.Pre event) {
        afterShaderPack();
    }

    /** With a shader pack and the GUI hidden (F1) there is no GUI event: the frame's end, before it is shown. */
    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        if (Minecraft.getInstance().options.hideGui) {
            afterShaderPack();
        }
    }

    private static void afterShaderPack() {
        if (!shaderPackInUse() || Minecraft.getInstance().level == null) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (!sampledAfterPack) {
            sampledAfterPack = true;
            FrameSampler.sample(projection);
        }
        if (!radiiReady || !enabled() || radii == null || radii.width != main.width || radii.height != main.height) {
            return;
        }
        radiiReady = false;
        try {
            blur(main);
        } finally {
            restore(main);
        }
    }

    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        sampledAfterPack = false;
        radiiReady = false;
    }

    private static boolean enabled() {
        return Viewfinder.isActive() && !PhotoCapture.capturing() && coc != null && prep != null && blur != null
                && composite != null && ClientConfig.LIVE_DEPTH_OF_FIELD.get() && ShaderCompat.depthTrusted()
                && !"rangefinder".equals(Viewfinder.body().viewfinder()) && Viewfinder.exposure() != null;
    }

    /** Pass 1: depth to signed blur radius, full resolution. */
    private static void computeRadii(RenderTarget main) {
        int w = main.width, h = main.height;
        radii = ensure(radii, w, h, GL11.GL_NEAREST);
        Matrix4f inv = new Matrix4f(projection).invert();
        float m11 = projection.m11();
        float focal = Viewfinder.focal();
        float fNumber = (float) Viewfinder.exposure().fNumber();
        float focusMm = Math.max(1, Viewfinder.liveFocusDistance()) * 1000;
        // screen pixels per mm on the sensor: the frame's height in pixels over the sensor's height
        double frameTan = Math.tan(Math.toRadians(Viewfinder.sensorVfov()) / 2);
        float pxPerMm = (float) (h * m11 * frameTan / Viewfinder.sensor().heightMm());
        float tilt = Viewfinder.chain().tiltDeg(Viewfinder.settings());
        float invHinge = (float) (Math.sin(Math.toRadians(tilt)) / focal);
        maxFull = Math.max(2, h * MAX_RADIUS_OF_HEIGHT);
        double focusM = focusMm / 1000.0;
        double worst = Math.max(Optics.blurCircleMm(focal, fNumber, focusM, 1e6),
                Optics.blurCircleMm(focal, fNumber, focusM, Math.min(0.3, focusM)));
        maxRadiusNow = tilt != 0 ? maxFull : (float) Math.min(maxFull, worst * pxPerMm * 0.5);

        prepareState();
        coc.setSampler("DepthSampler", main.getDepthTextureId());
        coc.safeGetUniform("InvProj").set(inv.m22(), inv.m32(), inv.m23(), inv.m33());
        coc.safeGetUniform("Lens").set(focal, fNumber, focusMm, pxPerMm);
        coc.safeGetUniform("Tilt").set(invHinge, 1 / m11);
        coc.safeGetUniform("MaxRadius").set(maxFull);
        draw(coc, radii);
    }

    /**
     * Passes 2-4: downscaled colour + radius, disc blur, full-resolution mix; written back to the screen. The blur
     * runs at 1/2 resolution for small radii and at 1/4 or 1/8 when the background blurs a lot (wide open, long lens):
     * a heavily blurred picture loses nothing at low resolution and the cost per pixel stays bounded.
     */
    private static void blur(RenderTarget main) {
        int w = main.width, h = main.height;
        int scale = 2;
        while (scale < 8 && maxRadiusNow / scale > BLUR_PASS_RADIUS) scale *= 2;
        int hw = Math.max(1, w / scale), hh = Math.max(1, h / scale);
        half = ensure(half, hw, hh, GL11.GL_NEAREST);
        halfBlurred = ensure(halfBlurred, hw, hh, GL11.GL_LINEAR);
        full = ensure(full, w, h, GL11.GL_NEAREST);
        float maxHalf = maxFull / scale;
        // spiral step: the sample count grows with radius² / (2 · step)
        float radScale = Math.max(0.6f, maxHalf * maxHalf / (2f * MAX_SAMPLES));

        prepareState();
        prep.setSampler("DiffuseSampler", main.getColorTextureId());
        prep.setSampler("CocSampler", radii.getColorTextureId());
        prep.safeGetUniform("InSize").set((float) w, (float) h);
        prep.safeGetUniform("FullMaxRadius").set(maxFull);
        prep.safeGetUniform("MaxRadius").set(maxHalf);
        prep.safeGetUniform("Scale").set((float) scale);
        draw(prep, half);

        blur.setSampler("DiffuseSampler", half.getColorTextureId());
        blur.safeGetUniform("OutSize").set((float) hw, (float) hh);
        blur.safeGetUniform("MaxRadius").set(maxHalf);
        blur.safeGetUniform("RadScale").set(radScale);
        draw(blur, halfBlurred);

        composite.setSampler("DiffuseSampler", main.getColorTextureId());
        composite.setSampler("CocSampler", radii.getColorTextureId());
        composite.setSampler("BlurSampler", halfBlurred.getColorTextureId());
        composite.safeGetUniform("BlurMaxRadius").set(maxFull);
        draw(composite, full);

        main.bindWrite(true);
        full.blitToScreen(w, h);
    }

    private static void prepareState() {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
    }

    private static void restore(RenderTarget main) {
        main.bindWrite(true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static TextureTarget ensure(@Nullable TextureTarget t, int w, int h, int filter) {
        if (t == null) {
            t = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            t.setFilterMode(filter);
        } else if (t.width != w || t.height != h) {
            t.resize(w, h, Minecraft.ON_OSX);
            t.setFilterMode(filter);
        }
        return t;
    }

    private static void draw(ShaderInstance shader, RenderTarget out) {
        out.bindWrite(true);
        shader.apply();
        BufferBuilder b = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        b.addVertex(0f, 0f, 0f);
        b.addVertex(1f, 0f, 0f);
        b.addVertex(1f, 1f, 0f);
        b.addVertex(0f, 1f, 0f);
        BufferUploader.draw(b.buildOrThrow());
        shader.clear();
    }

    /** An Iris shader pack is active: it finishes the picture after the level render, so the blur waits for the GUI. */
    public static boolean shaderPackInUse() {
        return ShaderCompat.packInUse();
    }
}
