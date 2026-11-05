package dev.hackdanel.realcamera.client.camera;

import dev.hackdanel.realcamera.processing.ImageParams;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Where the sun is in the frame and whether it shines into the lens (for flare). */
public final class SunTracker {
    private SunTracker() {}

    /** Unit vector towards the sun: overhead at noon, rising in the east (+x). */
    public static Vec3 sunDirection(ClientLevel level, float partialTick) {
        double a = level.getSunAngle(partialTick);
        return new Vec3(-Math.sin(a), Math.cos(a), 0).normalize();
    }

    public static ImageParams.Sun sun(ClientLevel level, Entity viewer, Camera camera, double vfovDeg, double aspect) {
        if (!level.dimensionType().hasSkyLight() || level.getRainLevel(1f) > 0.2f) {
            return ImageParams.NO_SUN;
        }
        Vec3 dir = sunDirection(level, 1f);
        if (dir.y < -0.02) {
            return ImageParams.NO_SUN;
        }
        Vector3f f = camera.getLookVector(), u = camera.getUpVector(), l = camera.getLeftVector();
        Vec3 fw = new Vec3(f.x(), f.y(), f.z()), up = new Vec3(u.x(), u.y(), u.z()), right = new Vec3(-l.x(), -l.y(), -l.z());
        double z = dir.dot(fw);
        if (z <= 0.1) {
            return ImageParams.NO_SUN;
        }
        double tanV = Math.tan(Math.toRadians(vfovDeg) / 2), tanH = tanV * aspect;
        double x = dir.dot(right) / z / tanH, y = dir.dot(up) / z / tanV;
        double outside = Math.max(0, Math.max(Math.abs(x), Math.abs(y)) - 1);
        if (outside > 0.6) {
            return ImageParams.NO_SUN;
        }
        Vec3 origin = camera.getPosition();
        HitResult hit = level.clip(new ClipContext(origin, origin.add(dir.scale(256)), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, viewer));
        if (hit.getType() != HitResult.Type.MISS) {
            return ImageParams.NO_SUN;
        }
        double strength = Math.max(0, 1 - outside / 0.6) * Math.min(1, dir.y * 4 + 0.6);
        return new ImageParams.Sun(x, y, strength);
    }
}
