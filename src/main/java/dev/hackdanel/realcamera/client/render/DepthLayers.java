package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The depth buffer at two moments of the level render, so the depth of field can see through water, stained glass
 * and ice (they write the depth of their own surface) and still give particles their own depth.
 *
 * <p>Fast/Fancy graphics draw everything into the main target: its depth is copied after the block entities (the
 * opaque world) and after the translucent blocks (plus water, glass, ice and the solid particles drawn before them);
 * at the end of the level it also holds the translucent particles, and rain and snow: vanilla draws weather without
 * writing depth on Fast/Fancy, so while depth is wanted {@code LevelRendererWeatherMixin} turns the depth writes on
 * (nothing else of the level is drawn after it; the hand comes after a depth clear). Fabulous graphics draw translucent
 * blocks and particles into their own targets, each starting from a copy of the opaque depth, and weather into one
 * that starts empty: those targets are used as they are. With a shader pack none of this applies (the pack decides
 * what its depth buffer holds).
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class DepthLayers {
    private static RenderTarget opaque, surface;
    private static boolean opaqueReady, surfaceReady;

    private DepthLayers() {}

    /** Wanted this frame: the viewfinder or a capture needs depth, without a shader pack. */
    private static boolean wanted() {
        return (Viewfinder.isActive() || PhotoCapture.capturing()) && !ShaderCompat.packInUse();
    }

    /** Rain and snow should write depth (Fast/Fancy draw them without): the depth of field blurs them by their own. */
    public static boolean weatherDepth() {
        return wanted() && !ShaderCompat.shadowPass();
    }

    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        opaqueReady = false;
        surfaceReady = false;
    }

    @SubscribeEvent
    static void onLevelStage(RenderLevelStageEvent event) {
        if (!wanted() || ShaderCompat.shadowPass() || Minecraft.useShaderTransparency()) return;
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            opaque = copy(opaque);
            opaqueReady = opaque != null;
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS && opaqueReady) {
            surface = copy(surface);
            surfaceReady = surface != null;
        }
    }

    private static RenderTarget copy(@Nullable RenderTarget into) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        try {
            if (into == null || into.width != main.width || into.height != main.height
                    || into.isStencilEnabled() != main.isStencilEnabled()) {
                if (into != null) into.destroyBuffers();
                into = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                if (main.isStencilEnabled()) into.enableStencil();
            }
            into.copyDepthFrom(main);
            main.bindWrite(false);
            return into;
        } catch (RuntimeException e) {
            RealCamera.LOGGER.warn("Could not copy the depth buffer", e);
            main.bindWrite(false);
            return null;
        }
    }

    /**
     * Depth texture of the opaque world (no water, glass, ice or particles) for this frame, or -1. Under Fabulous
     * graphics that is the main target's own depth.
     */
    public static int opaqueTexture() {
        if (!wanted()) return -1;
        if (Minecraft.useShaderTransparency()) {
            return Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
        }
        return opaqueReady && surfaceReady ? opaque.getDepthTextureId() : -1;
    }

    /** Depth texture with the translucent blocks' surfaces (and, on Fast/Fancy, solid particles), or -1. */
    public static int surfaceTexture() {
        if (!wanted()) return -1;
        if (Minecraft.useShaderTransparency()) {
            return sameSize(Minecraft.getInstance().levelRenderer.getTranslucentTarget());
        }
        return opaqueReady && surfaceReady ? surface.getDepthTextureId() : -1;
    }

    /**
     * Depth texture with everything the level drew (what the main target holds on Fast/Fancy); under Fabulous the
     * particles' own target (opaque world + particles). -1 if unknown.
     */
    public static int particlesTexture() {
        if (!wanted()) return -1;
        if (Minecraft.useShaderTransparency()) {
            return sameSize(Minecraft.getInstance().levelRenderer.getParticlesTarget());
        }
        return Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
    }

    /** Fabulous only: rain and snow alone (their target has no world in it, empty = far), or -1. */
    public static int weatherTexture() {
        if (!wanted() || !Minecraft.useShaderTransparency()) return -1;
        return sameSize(Minecraft.getInstance().levelRenderer.getWeatherTarget());
    }

    /** A Fabulous target's depth texture if it matches the main target (a capture resizes only the main one). */
    private static int sameSize(@Nullable RenderTarget t) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        return t == null || t.width != main.width || t.height != main.height ? -1 : t.getDepthTextureId();
    }
}
