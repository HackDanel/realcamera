package dev.hackdanel.realcamera.client.camera;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
    /** Entity each cell of the last grid hit (null = block or sky). */
    private Entity[] cellEntity;
    private int lastGw, lastGh;

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

    /** The world point at normalised frame coordinates u,v whose depth along the axis is {@code planeDistance}. */
    public Vec3 point(double u, double v, double planeDistance) {
        Vec3 dir = direction(u, v);
        return origin.add(dir.scale(planeDistance / Math.max(1e-3, dir.dot(axis))));
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

    /**
     * The light-emitting blocks under the pixels of the frame, from the per-pixel depth (metres along the axis, the
     * frame's size): each pixel's surface point is found from the depth and the block just behind it looked up.
     * Torches, lanterns, glowstone, lava and fire are found however thin they are (no ray has to hit them). With a
     * depth buffer that holds something else (a shader pack) the points land in air and nothing is found.
     *
     * @param drawn how bright the game drew each pixel (linear, brightest channel); only pixels from
     *              {@link dev.hackdanel.realcamera.processing.LightSources#CANDIDATE} are looked up
     * @return null if no pixel lies on a light source drawn like one
     */
    @org.jetbrains.annotations.Nullable
    public dev.hackdanel.realcamera.processing.LightSources.Found emitters(float[] depth, int w, int h,
                                                                           java.util.function.IntToDoubleFunction drawn) {
        int[] block = null;
        float[] bright = null;
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap ids = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.ints.IntArrayList levels = new it.unimi.dsi.fastutil.ints.IntArrayList();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long lastPos = Long.MIN_VALUE;
        int lastId = -1;
        double ox = origin.x, oy = origin.y, oz = origin.z;
        for (int y = 0; y < h; y++) {
            double v = 1 - (y + 0.5) * 2 / h;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float d = depth[i];
                if (d <= 0 || d >= 9_999) continue;
                float b = (float) drawn.applyAsDouble(i);
                if (b < dev.hackdanel.realcamera.processing.LightSources.CANDIDATE) continue;
                double u = (x + 0.5) * 2 / w - 1;
                double dx = forward.x + right.x * u * tanH + up.x * v * tanV;
                double dy = forward.y + right.y * u * tanH + up.y * v * tanV;
                double dz = forward.z + right.z * u * tanH + up.z * v * tanV;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len;
                dy /= len;
                dz /= len;
                double along = dx * axis.x + dy * axis.y + dz * axis.z;
                if (along < 1e-3) continue;
                double t = d / along + 0.02; // just behind the surface: inside the block that drew it
                pos.set(Math.floor(ox + dx * t), Math.floor(oy + dy * t), Math.floor(oz + dz * t));
                long key = pos.asLong();
                if (key != lastPos) {
                    lastPos = key;
                    lastId = ids.getOrDefault(key, -2);
                    if (lastId == -2) {
                        int level = this.level.getBlockState(pos).getLightEmission(this.level, pos);
                        lastId = level > 0 ? levels.size() : -1;
                        if (level > 0) levels.add(level);
                        ids.put(key, lastId);
                    }
                }
                if (lastId < 0) continue;
                if (block == null) {
                    block = new int[w * h];
                    java.util.Arrays.fill(block, -1);
                    bright = new float[w * h];
                }
                block[i] = lastId;
                bright[i] = b;
            }
        }
        return block == null ? null : dev.hackdanel.realcamera.processing.LightSources.share(block, levels.toIntArray(), bright);
    }

    /** Depth map (metres along the axis) on a gw x gh grid, row-major top-down. Sky = 10 km. */
    public float[] depthGrid(int gw, int gh) {
        return grids(gw, gh, false)[0];
    }

    /**
     * Depth, optionally how brightly the game lit each surface (0..1, the light map curve of the air block in
     * front of the hit; lets the flash recover surface colour), where a ray hit an entity's box (1) rather
     * than a block or the sky (0), and what kind of surface it hit ({@code LayeredDepth.WATER} for a fluid,
     * {@code CLEAR} for a translucent block such as stained glass or ice, else {@code NONE}).
     */
    public float[][] grids(int gw, int gh, boolean withLight) {
        float[] depth = new float[gw * gh];
        float[] light = withLight ? new float[gw * gh] : null;
        float[] entity = new float[gw * gh];
        float[] kind = new float[gw * gh];
        float[] ev = withLight ? new float[gw * gh] : null;
        double day = SceneMeter.daylightEv(level, 1f), ambient = SceneMeter.ambientEv(level);
        cellEntity = new Entity[gw * gh];
        lastGw = gw;
        lastGh = gh;
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
                if (hit instanceof EntityHitResult eh) cellEntity[y * gw + x] = eh.getEntity();
                if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                    kind[y * gw + x] = surfaceKind(bh);
                }
                if (light != null) {
                    light[y * gw + x] = hit.getType() == HitResult.Type.MISS ? 1f : lightAt(hit, skyFactor);
                    ev[y * gw + x] = hit.getType() == HitResult.Type.MISS ? Float.NaN
                            : (float) SceneMeter.sampleEv(level, hit, dir, day, ambient);
                }
            }
        }
        return new float[][]{depth, light, entity, kind, ev};
    }

    private float surfaceKind(BlockHitResult hit) {
        var pos = hit.getBlockPos();
        if (!level.getFluidState(pos).isEmpty()) {
            return dev.hackdanel.realcamera.processing.LayeredDepth.WATER;
        }
        var block = level.getBlockState(pos).getBlock();
        boolean clear = block instanceof net.minecraft.world.level.block.HalfTransparentBlock
                || block instanceof net.minecraft.world.level.block.IronBarsBlock // glass panes
                || block instanceof net.minecraft.world.level.block.BeaconBlock;
        return clear ? dev.hackdanel.realcamera.processing.LayeredDepth.CLEAR : dev.hackdanel.realcamera.processing.LayeredDepth.NONE;
    }

    /**
     * The entities the last grid hit that move relative to the ground, for the motion blur: per cell the index of the
     * mover (-1 none), per mover its velocity along the frame axes (m/s) and the depth range its box spans. Null if
     * nothing moves. A rider and its mount move with the camera: their velocity relative to it is zero.
     */
    @org.jetbrains.annotations.Nullable
    public dev.hackdanel.realcamera.processing.ImageParams.MotionField motionField() {
        if (cellEntity == null) return null;
        java.util.Map<Entity, Integer> index = new java.util.HashMap<>();
        java.util.List<dev.hackdanel.realcamera.processing.ImageParams.Mover> movers = new java.util.ArrayList<>();
        short[] cells = new short[cellEntity.length];
        java.util.Arrays.fill(cells, (short) -1);
        Vec3 right = this.right, up = this.up;
        for (int i = 0; i < cellEntity.length; i++) {
            Entity e = cellEntity[i];
            if (e == null) continue;
            Integer id = index.get(e);
            if (id == null) {
                Vec3 v = Viewfinder.velocity(e);
                if (v.lengthSqr() < 0.05 * 0.05 || movers.size() >= 32) {
                    id = -1;
                } else {
                    AABB box = e.getBoundingBox();
                    double near = Double.MAX_VALUE, far = -Double.MAX_VALUE;
                    for (int c = 0; c < 8; c++) {
                        Vec3 corner = new Vec3((c & 1) == 0 ? box.minX : box.maxX, (c & 2) == 0 ? box.minY : box.maxY,
                                (c & 4) == 0 ? box.minZ : box.maxZ);
                        double d = corner.subtract(origin).dot(axis);
                        near = Math.min(near, d);
                        far = Math.max(far, d);
                    }
                    id = movers.size();
                    movers.add(new dev.hackdanel.realcamera.processing.ImageParams.Mover(v.dot(right), v.dot(up),
                            v.dot(forward), (float) near, (float) far));
                }
                index.put(e, id);
            }
            cells[i] = (short) (int) id;
        }
        return movers.isEmpty() ? null : new dev.hackdanel.realcamera.processing.ImageParams.MotionField(lastGw, lastGh, cells, movers);
    }

    private float lightAt(HitResult hit, float skyFactor) {
        net.minecraft.core.BlockPos pos = SceneMeter.litPos(level, hit);
        float sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) * skyFactor;
        float block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        float f = Math.max(sky, block) / 15f;
        return 0.05f + 0.95f * f / (4 - 3 * f);
    }
}
