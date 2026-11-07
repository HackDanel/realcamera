package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.CameraRays;
import dev.hackdanel.realcamera.client.camera.SceneMeter;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.network.Payloads;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Someone else's flash, as the players around see it. The pop lasts about a millisecond, but the eye integrates
 * light for about 0.1 s, so how bright it looks depends on how much light it adds to what the observer sees relative
 * to the light already there: a guide number 36 flash 10 m away lights a moonlit scene like day and is hardly
 * noticed in sunshine. The flash only lights what is in its beam and not in a shadow; looking straight into it
 * leaves a glare and, at night, an afterimage that fades over a few seconds.
 *
 * <p>Drawn over the finished picture before the GUI, so it works the same with or without a shader pack.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class FlashGlow {
    /** How long the eye integrates a short flash (s). */
    private static final double EYE_S = 0.1;
    private static final ResourceLocation GLOW = RealCamera.id("dynamic/flash_glow");
    private static boolean glowReady;

    /**
     * One pop as this player saw it.
     *
     * @param boost      how much brighter the view gets at the peak (0 = no change, 1 = twice as bright)
     * @param glare      0..1 strength of the source glare when it is in view (0 = behind a wall or facing away)
     * @param afterimage 0..1 strength of the afterimage
     * @param afterNdc   where on the screen the afterimage stays (normalised device coordinates)
     */
    private record Pop(Vec3 pos, double boost, double glare, double afterimage, float afterX, float afterY,
                       long startNanos) {}

    private static final List<Pop> pops = new ArrayList<>();
    /** Pops that came while a frame was being captured: shown when it is done, so they do not end up in the photo. */
    private record Deferred(Vec3 pos, Vec3 dir, float gn) {}
    private static final List<Deferred> deferred = new ArrayList<>();
    private static float projM00 = 1, projM11 = 1;

    private FlashGlow() {}

    /** A flash fired near this player. */
    public static void onPop(Payloads.FlashPop p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
        if (dir.lengthSqr() < 1e-6) dir = new Vec3(0, 0, 1);
        dir = dir.normalize();
        float gn = Math.max(1, p.gn());
        boolean own = p.shooterId() == mc.player.getId();
        if (!own) {
            // it may light a frame this player is exposing right now
            PhotoCapture.remoteFlash(pos, dir, gn);
        }
        if (PhotoCapture.capturing()) {
            if (deferred.size() < 8) deferred.add(new Deferred(pos, dir, gn));
            return;
        }
        show(mc, pos, dir, gn);
    }

    private static void show(Minecraft mc, Vec3 pos, Vec3 dir, float gn) {
        try {
            Pop pop = seen(mc, pos, dir, gn);
            if (pop != null) {
                pops.add(pop);
                while (pops.size() > 16) pops.removeFirst();
            }
        } catch (RuntimeException e) {
            RealCamera.LOGGER.debug("Flash glow failed", e);
        }
    }

    /** The light a flash adds at a point relative to the light there, over the eye's integration time. */
    private static double ratio(ClientLevel level, Vec3 flash, Vec3 dir, float gn, Vec3 point, double ambientEv) {
        Vec3 to = point.subtract(flash);
        double d = Math.max(0.5, to.length());
        double flashEv = Math.log(Math.pow(gn / d, 2) / EYE_S) / Math.log(2);
        return Math.pow(2, flashEv - ambientEv) * PhotoCapture.flashCone(to.dot(dir) / d);
    }

    private static boolean clear(ClientLevel level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.4) return true;
        Vec3 end = to.subtract(d.scale(0.3 / len)); // stop short of the surface itself
        return level.clip(new ClipContext(from, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    @Nullable
    private static Pop seen(Minecraft mc, Vec3 flash, Vec3 dir, float gn) {
        ClientLevel level = mc.level;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        double dist = eye.distanceTo(flash);
        if (dist > 160) return null;
        double day = SceneMeter.daylightEv(level, 1f), amb = SceneMeter.ambientEv(level);

        // What the player looks at: five points of the view. The flash lights those in its beam and in plain sight.
        double vfov = Math.toDegrees(2 * Math.atan(1 / Math.max(0.1, projM11)));
        CameraRays rays = new CameraRays(level, mc.getCameraEntity(), cam, vfov, projM11 / Math.max(0.1, projM00), 64);
        double[][] at = {{0, 0}, {-0.5, 0.5}, {0.5, 0.5}, {-0.5, -0.5}, {0.5, -0.5}};
        double sum = 0;
        for (double[] uv : at) {
            Vec3 rd = rays.direction(uv[0], uv[1]);
            HitResult hit = rays.cast(rd);
            if (hit.getType() == HitResult.Type.MISS) continue; // the sky is not lit by a flash
            Vec3 p = hit.getLocation();
            if (!clear(level, flash, p)) continue;
            sum += ratio(level, flash, dir, gn, p, SceneMeter.sampleEv(level, hit, rd, day, amb));
        }
        double r = sum / at.length;
        double boost = Math.min(4, 0.6 * Math.log(1 + r) / Math.log(2));

        // Looking into it: the source itself, if nothing is in between.
        double glare = 0, after = 0;
        float ax = 0, ay = 0;
        float[] ndc = toScreen(cam, flash);
        if (ndc != null && Math.abs(ndc[0]) < 1.3 && Math.abs(ndc[1]) < 1.3 && clear(level, eye, flash)) {
            double direct = ratio(level, flash, dir, gn, eye,
                    SceneMeter.pointEv(level, BlockPos.containing(eye), day, amb));
            // how many stops the flash outshines what the eye is adapted to: a weak or far flash in daylight is a
            // faint spark, a full-power one at night from close up is blinding
            double stops = Math.log(1 + direct) / Math.log(2);
            glare = Math.min(1, stops / 11);
            // looking into a bright flash leaves an afterimage where it was on the retina (on the screen, it moves
            // with the gaze); the brighter the flash against the adapted eye, the stronger and longer it is
            if (stops > 4 && Math.abs(ndc[0]) < 1 && Math.abs(ndc[1]) < 1) {
                after = Math.min(1, (stops - 4) / 9);
                ax = ndc[0];
                ay = ndc[1];
            }
        }
        if (boost < 0.02 && glare < 0.02 && after <= 0) return null;
        return new Pop(flash, boost, glare, after, ax, ay, System.nanoTime());
    }

    /** World point to normalised device coordinates of the current view, or null behind the camera. */
    @Nullable
    private static float[] toScreen(Camera cam, Vec3 p) {
        Vec3 rel = p.subtract(cam.getPosition());
        Vector3f f = cam.getLookVector(), u = cam.getUpVector(), l = cam.getLeftVector();
        double z = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
        if (z < 0.05) return null;
        double x = -(rel.x * l.x() + rel.y * l.y() + rel.z * l.z());
        double y = rel.x * u.x() + rel.y * u.y() + rel.z * u.z();
        return new float[]{(float) (x / z * projM00), (float) (y / z * projM11)};
    }

    @SubscribeEvent
    static void onLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && !ShaderCompat.shadowPass()) {
            Matrix4f proj = event.getProjectionMatrix();
            projM00 = proj.m00();
            projM11 = proj.m11();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onGui(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!deferred.isEmpty() && !PhotoCapture.capturing() && mc.level != null) {
            for (Deferred d : deferred) show(mc, d.pos(), d.dir(), d.gn());
            deferred.clear();
        }
        if (pops.isEmpty()) return;
        long now = System.nanoTime();
        pops.removeIf(p -> (now - p.startNanos()) / 1e9 > (p.afterimage() > 0 ? 0.3 + afterLife(p.afterimage()) : 0.4));
        if (pops.isEmpty() || PhotoCapture.capturing() || mc.level == null) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        Camera cam = mc.gameRenderer.getMainCamera();
        double boost = 0, veil = 0;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        for (Pop p : pops) {
            double t = (now - p.startNanos()) / 1e9;
            double flashEnv = Math.exp(-t / 0.05);
            boost += p.boost() * flashEnv;
            veil += 0.45 * p.glare() * p.glare() * Math.exp(-t / 0.12);
            if (p.glare() > 0 && flashEnv > 0.01) {
                float[] ndc = toScreen(cam, p.pos());
                if (ndc != null) {
                    // the source: a spark for a weak flash, a big blinding star for a strong one
                    float size = (float) (h * (0.02 + 0.33 * p.glare() * p.glare() + 0.05 * p.glare()));
                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                    sprite(g, ndc[0], ndc[1], size, w, h, 1f, 0.97f, 0.92f,
                            (float) Math.min(1, flashEnv * (0.15 + 1.2 * p.glare())));
                    if (p.glare() > 0.5) {
                        sprite(g, ndc[0], ndc[1], size * 2.5f, w, h, 1f, 0.98f, 0.95f,
                                (float) (flashEnv * (p.glare() - 0.5)));
                    }
                }
            }
            if (p.afterimage() > 0 && t > 0.15) {
                // In the dark the afterimage is positive: a soft glowing blot where the flash was, greenish-yellow
                // at first and turning purple as it fades; it stays at the same place in the eye (on the screen).
                double life = afterLife(p.afterimage());
                double fade = Math.max(0, 1 - (t - 0.15) / life);
                fade *= fade;
                float a = (float) (0.8 * Math.sqrt(p.afterimage()) * fade);
                float size = (float) (h * (0.025 + 0.09 * p.afterimage()));
                float mix = (float) Math.min(1, (t - 0.15) / (0.5 * life));
                // a dark surround first, where the retina is tired, then the glow over it
                RenderSystem.defaultBlendFunc();
                sprite(g, p.afterX(), p.afterY(), size * 1.8f, w, h, 0.05f, 0.04f, 0.08f, a * 0.35f);
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                sprite(g, p.afterX(), p.afterY(), size, w, h, 0.55f - 0.15f * mix, 0.6f - 0.4f * mix, 0.25f + 0.35f * mix, a);
            }
        }
        // The scene lit by the flash: dst * (1 + s) per pass, so dark areas stay darker than lit ones.
        double k = Math.min(4, boost);
        Matrix4f m = g.pose().last().pose();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        for (int pass = 0; pass < 3 && k > 0.01; pass++) {
            float s = (float) Math.min(1, k);
            quad(m, w, h, s, s, s, 1);
            k = (1 + k) / (1 + s) - 1;
        }
        if (veil > 0.01) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            quad(m, w, h, 1, 0.98f, 0.95f, (float) Math.min(0.7, veil));
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Seconds an afterimage of this strength lasts: a fleeting spot for a weak flash, ~8 s for a blinding one. */
    private static double afterLife(double strength) {
        return 0.8 + 7 * strength * strength;
    }

    private static void quad(Matrix4f m, int w, int h, float r, float g, float b, float a) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(m, 0, 0, 0).setColor(r, g, b, a);
        buf.addVertex(m, 0, h, 0).setColor(r, g, b, a);
        buf.addVertex(m, w, h, 0).setColor(r, g, b, a);
        buf.addVertex(m, w, 0, 0).setColor(r, g, b, a);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void sprite(GuiGraphics g, float ndcX, float ndcY, float size, int w, int h, float r, float gr,
                               float b, float a) {
        if (a <= 0.003f) return;
        ensureGlow();
        int px = Math.round((ndcX + 1) / 2 * w - size / 2), py = Math.round((1 - ndcY) / 2 * h - size / 2);
        int s = Math.max(2, Math.round(size));
        RenderSystem.setShaderColor(r, gr, b, Math.min(1, a));
        g.blit(GLOW, px, py, s, s, 0, 0, 64, 64, 64, 64);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** A soft round glow: a bright core and a wide halo. */
    private static void ensureGlow() {
        if (glowReady) return;
        glowReady = true;
        NativeImage img = new NativeImage(64, 64, false);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = (x + 0.5) / 32 - 1, dy = (y + 0.5) / 32 - 1;
                double r = Math.sqrt(dx * dx + dy * dy);
                double a = r >= 1 ? 0 : Math.pow(1 - r, 2.2) * 0.8 + Math.exp(-r * r * 60) * 0.6;
                int alpha = (int) Math.round(Math.min(1, a) * 255);
                img.setPixelRGBA(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        Minecraft.getInstance().getTextureManager().register(GLOW, new DynamicTexture(img));
    }
}
