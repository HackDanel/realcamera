package dev.hackdanel.realcamera.client.camera;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Camera subject detection: finds people (players, villagers, humanoid mobs), animals, birds and
 * vehicles in the frame, depending on what the body can recognise ("none", "face", "eye", "animal",
 * "bird", "vehicle" — each level includes the previous ones), and picks the most prominent visible one.
 */
public final class SubjectDetector {
    public enum Kind { HUMAN, ANIMAL, BIRD, VEHICLE }

    /** A detected subject with its eye/centre point and screen box (frame coords, -1..1, y up). */
    public record Subject(Entity entity, Kind kind, Vec3 point, double distance, float x0, float y0, float x1, float y1,
                          boolean eye) {
        public float cx() {
            return (x0 + x1) / 2;
        }

        public float cy() {
            return (y0 + y1) / 2;
        }
    }

    private SubjectDetector() {}

    static int level(String detect) {
        return switch (detect) {
            case "face" -> 1;
            case "eye" -> 2;
            case "animal" -> 3;
            case "bird" -> 4;
            case "vehicle" -> 5;
            default -> 0;
        };
    }

    @Nullable
    static Kind kindOf(Entity e, int level) {
        if (e instanceof Player || e instanceof AbstractVillager
                || (e instanceof Monster m && m.getBbHeight() > 1.4 && m.getBbHeight() < 2.6)) {
            return level >= 1 ? Kind.HUMAN : null;
        }
        if (e instanceof Parrot || e instanceof Chicken || e instanceof Bee || e instanceof FlyingAnimal) {
            return level >= 4 ? Kind.BIRD : null;
        }
        if (e instanceof Animal) {
            return level >= 3 ? Kind.ANIMAL : null;
        }
        if (e instanceof AbstractMinecart || e instanceof Boat) {
            return level >= 5 ? Kind.VEHICLE : null;
        }
        return null;
    }

    /**
     * @param area restrict to |u|,|v| <= area (1 = whole frame)
     * @param cu   preferred centre (AF point / zone centre)
     */
    @Nullable
    public static Subject detect(ClientLevel level, Entity viewer, Camera camera, double vfovDeg, double aspect,
                                 String detect, double area, double cu, double cv) {
        int lvl = level(detect);
        if (lvl == 0) {
            return null;
        }
        Vec3 origin = camera.getPosition();
        Vector3f f = camera.getLookVector(), u = camera.getUpVector(), l = camera.getLeftVector();
        Vec3 fw = new Vec3(f.x(), f.y(), f.z()), up = new Vec3(u.x(), u.y(), u.z()), right = new Vec3(-l.x(), -l.y(), -l.z());
        double tanV = Math.tan(Math.toRadians(vfovDeg) / 2), tanH = tanV * aspect;
        List<Entity> candidates = level.getEntities(viewer, new AABB(origin, origin).inflate(96),
                e -> e instanceof LivingEntity || e instanceof AbstractMinecart || e instanceof Boat);
        Subject best = null;
        double bestScore = 0;
        for (Entity e : candidates) {
            Kind kind = kindOf(e, lvl);
            if (kind == null || !e.isAlive() || e.isInvisible()) continue;
            boolean eyeAf = lvl >= 2 && kind != Kind.VEHICLE;
            Vec3 point = eyeAf ? e.getEyePosition() : e.getBoundingBox().getCenter();
            double[] p = project(point.subtract(origin), fw, up, right, tanH, tanV);
            if (p == null || Math.abs(p[0]) > area || Math.abs(p[1]) > area) continue;
            // visible? (blocks only; another entity in front is acceptable)
            if (level.clip(new ClipContext(origin, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer))
                    .getType() != HitResult.Type.MISS) continue;
            AABB box = e.getBoundingBox();
            double[] a = project(new Vec3(box.minX, box.minY, box.minZ).subtract(origin), fw, up, right, tanH, tanV);
            double[] b = project(new Vec3(box.maxX, box.maxY, box.maxZ).subtract(origin), fw, up, right, tanH, tanV);
            double[] c = project(new Vec3(box.minX, box.maxY, box.maxZ).subtract(origin), fw, up, right, tanH, tanV);
            double[] d = project(new Vec3(box.maxX, box.minY, box.minZ).subtract(origin), fw, up, right, tanH, tanV);
            if (a == null || b == null || c == null || d == null) continue;
            float x0 = (float) Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0]));
            float x1 = (float) Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0]));
            float y0 = (float) Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]));
            float y1 = (float) Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1]));
            double size = (y1 - y0) * (x1 - x0);
            double off = Math.hypot(p[0] - cu, p[1] - cv);
            double score = size * (kind == Kind.HUMAN ? 1.5 : 1) / (0.3 + off);
            if (score > bestScore) {
                bestScore = score;
                double dist = point.subtract(origin).dot(fw);
                best = new Subject(e, kind, point, dist, x0, y0, x1, y1, eyeAf);
            }
        }
        return best;
    }

    /** Frame coordinates (-1..1, y up) of a camera-relative point, or null when behind the camera. */
    @Nullable
    static double[] project(Vec3 rel, Vec3 fw, Vec3 up, Vec3 right, double tanH, double tanV) {
        double z = rel.dot(fw);
        if (z <= 0.05) return null;
        return new double[]{rel.dot(right) / z / tanH, rel.dot(up) / z / tanV};
    }

    /** Re-locate a tracked entity (AF-C tracking). */
    @Nullable
    public static Subject track(ClientLevel level, Entity viewer, Camera camera, double vfovDeg, double aspect, Entity target,
                                boolean eye) {
        if (!target.isAlive() || target.level() != level) return null;
        Vec3 origin = camera.getPosition();
        Vector3f f = camera.getLookVector(), u = camera.getUpVector(), l = camera.getLeftVector();
        Vec3 fw = new Vec3(f.x(), f.y(), f.z()), up = new Vec3(u.x(), u.y(), u.z()), right = new Vec3(-l.x(), -l.y(), -l.z());
        double tanV = Math.tan(Math.toRadians(vfovDeg) / 2), tanH = tanV * aspect;
        Vec3 point = eye ? target.getEyePosition() : target.getBoundingBox().getCenter();
        double[] p = project(point.subtract(origin), fw, up, right, tanH, tanV);
        if (p == null || Math.abs(p[0]) > 1 || Math.abs(p[1]) > 1) return null;
        AABB box = target.getBoundingBox();
        double[] a = project(new Vec3(box.minX, box.minY, box.minZ).subtract(origin), fw, up, right, tanH, tanV);
        double[] b = project(new Vec3(box.maxX, box.maxY, box.maxZ).subtract(origin), fw, up, right, tanH, tanV);
        if (a == null || b == null) return null;
        Kind kind = target instanceof Player || target instanceof AbstractVillager ? Kind.HUMAN : Kind.ANIMAL;
        return new Subject(target, kind, point, point.subtract(origin).dot(fw), (float) Math.min(a[0], b[0]),
                (float) Math.min(a[1], b[1]), (float) Math.max(a[0], b[0]), (float) Math.max(a[1], b[1]), eye);
    }
}
