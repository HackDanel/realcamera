package dev.hackdanel.realcamera.client.camera;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Rays through the camera frame, hitting blocks (incl. water surfaces) and entities. */
public final class CameraRays {
    private final ClientLevel level;
    private final Entity viewer;
    private final Vec3 origin;
    private final Vec3 forward, up, right;
    /** Axis depth is measured along: the view axis, or the level axis of a shifted lens (plane of focus stays put). */
    private Vec3 axis;
    private final double tanH, tanV;
    private final double maxDist;
    private final List<Entity> entities;
    private ClipContext.Block shape = ClipContext.Block.VISUAL;

    public CameraRays(ClientLevel level, Entity viewer, Camera camera, double vfovDeg, double aspect, double maxDist) {
        this.level = level;
        this.viewer = viewer;
        this.origin = camera.getPosition();
        Vector3f f = camera.getLookVector(), u = camera.getUpVector(), l = camera.getLeftVector();
        this.forward = new Vec3(f.x(), f.y(), f.z());
        this.up = new Vec3(u.x(), u.y(), u.z());
        this.right = new Vec3(-l.x(), -l.y(), -l.z());
        this.axis = forward;
        this.tanV = Math.tan(Math.toRadians(vfovDeg) / 2);
        this.tanH = tanV * aspect;
        this.maxDist = maxDist;
        AABB box = new AABB(origin, origin).inflate(maxDist);
        this.entities = level.getEntities(viewer, box, e -> e.isPickable() || e.isAlive() && !e.isSpectator());
    }

    public Vec3 origin() {
        return origin;
    }

    public Vec3 forward() {
        return forward;
    }

    /** The frame is rendered pitched up by {@code deg} (shifted lens): measure depth along the level axis. */
    public CameraRays withLevelAxis(double deg) {
        double b = Math.toRadians(deg);
        axis = forward.scale(Math.cos(b)).subtract(up.scale(Math.sin(b))).normalize();
        return this;
    }

    /**
     * Hit blocks by their outline (the selection box) instead of their collision shape: grass, flowers, saplings,
     * crops and torches have no collision, but autofocus, peaking and the rangefinder must see them.
     */
    public CameraRays outline() {
        shape = ClipContext.Block.OUTLINE;
        return this;
    }

    /** Unit direction for normalised frame coordinates u,v in [-1,1] (v up). */
    public Vec3 direction(double u, double v) {
        return forward.add(right.scale(u * tanH)).add(up.scale(v * tanV)).normalize();
    }

    public HitResult cast(Vec3 dir) {
        Vec3 end = origin.add(dir.scale(maxDist));
        BlockHitResult block = level.clip(new ClipContext(origin, end, shape, ClipContext.Fluid.ANY, viewer));
        double best = block.getType() == HitResult.Type.MISS ? maxDist * maxDist : block.getLocation().distanceToSqr(origin);
        HitResult result = block;
        for (Entity e : entities) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(e.getPickRadius()).clip(origin, end);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(origin);
                if (d < best) {
                    best = d;
                    result = new EntityHitResult(e, hit.get());
                }
            }
        }
        return result;
    }

    /** Distance along the optical axis (what focus and depth of field use), or +inf for sky. */
    public double planeDistance(HitResult hit, Vec3 dir) {
        if (hit.getType() == HitResult.Type.MISS) {
            return Double.POSITIVE_INFINITY;
        }
        return hit.getLocation().subtract(origin).dot(axis);
    }

    /** Depth map (metres along the axis) on a gw x gh grid, row-major top-down. Sky = 10 km. */
    public float[] depthGrid(int gw, int gh) {
        return grids(gw, gh, false)[0];
    }

    /**
     * Depth, optionally how brightly the game lit each surface (0..1, the light map curve of the air block in
     * front of the hit; lets the flash recover surface colour), and where a ray hit an entity's box (1) rather
     * than a block or the sky (0).
     */
    public float[][] grids(int gw, int gh, boolean withLight) {
        float[] depth = new float[gw * gh];
        float[] light = withLight ? new float[gw * gh] : null;
        float[] entity = new float[gw * gh];
        float skyFactor = level.getSkyDarken(1f);
        for (int y = 0; y < gh; y++) {
            double v = 1 - (y + 0.5) * 2 / gh;
            for (int x = 0; x < gw; x++) {
                double u = (x + 0.5) * 2 / gw - 1;
                Vec3 dir = direction(u, v);
                HitResult hit = cast(dir);
                double d = planeDistance(hit, dir);
                depth[y * gw + x] = (float) (Double.isInfinite(d) ? 10_000 : Math.max(0.05, d));
                entity[y * gw + x] = hit.getType() == HitResult.Type.ENTITY ? 1 : 0;
                if (light != null) {
                    light[y * gw + x] = hit.getType() == HitResult.Type.MISS ? 1f : lightAt(hit, skyFactor);
                }
            }
        }
        return new float[][]{depth, light, entity};
    }

    private float lightAt(HitResult hit, float skyFactor) {
        net.minecraft.core.BlockPos pos = hit instanceof BlockHitResult b
                ? b.getBlockPos().relative(b.getDirection())
                : net.minecraft.core.BlockPos.containing(hit.getLocation());
        float sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) * skyFactor;
        float block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        float f = Math.max(sky, block) / 15f;
        return 0.05f + 0.95f * f / (4 - 3 * f);
    }
}
