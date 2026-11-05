package dev.hackdanel.realcamera.client.camera;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Converts Minecraft lighting into physical scene brightness (EV at ISO 100). Sky light follows the
 * sun height, weather and moon phase; block light gives indoor/torch levels. Calibration (plan §3.3):
 * clear noon ~EV 15, sunset ~11, moonlit night ~0, torch-lit room ~6.
 */
public final class SceneMeter {
    private SceneMeter() {}

    /** Brightness of open daylight right now, EV100. */
    public static double daylightEv(ClientLevel level, float partialTick) {
        if (!level.dimensionType().hasSkyLight()) {
            return -10;
        }
        double elevation = Math.cos(level.getSunAngle(partialTick));
        double ev;
        if (elevation >= 0) {
            ev = 11 + 4 * Math.sqrt(Mth.clamp(elevation / 0.4, 0, 1));
        } else {
            float moon = DimensionType.MOON_BRIGHTNESS_PER_PHASE[level.getMoonPhase()];
            double night = -3 + 3.5 * moon;
            ev = Math.max(night, 11 + 48 * elevation);
        }
        ev -= 1.6 * level.getRainLevel(partialTick) + 1.4 * level.getThunderLevel(partialTick);
        return ev;
    }

    /** Ambient brightness of dimensions without a sky. */
    public static double ambientEv(ClientLevel level) {
        if (level.dimensionType().hasSkyLight()) {
            return -6;
        }
        return level.dimensionType().hasCeiling() ? 6.5 : 5.0; // Nether : End
    }

    /** Scene EV at a position from its sky and block light levels. */
    public static double pointEv(ClientLevel level, BlockPos pos, double daylightEv, double ambientEv) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        double s = sky / 15.0, b = block / 15.0;
        double lum = Math.pow(2, daylightEv) * s * s * s + Math.pow(2, 7) * b * b * b + Math.pow(2, ambientEv) + Math.pow(2, -6);
        return Math.log(lum) / Math.log(2);
    }

    /** True if the brightest light at this point is a block (torches...), used for auto white balance. */
    public static boolean blockLit(ClientLevel level, BlockPos pos, double daylightEv) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        double s = sky / 15.0, b = block / 15.0;
        return Math.pow(2, 7) * b * b * b > Math.pow(2, daylightEv) * s * s * s;
    }

    /** EV of whatever a ray hit: the lit air block in front of the hit face, or the sky. */
    public static double sampleEv(ClientLevel level, HitResult hit, Vec3 dir, double daylightEv, double ambientEv) {
        if (hit.getType() == HitResult.Type.MISS) {
            if (!level.dimensionType().hasSkyLight()) {
                return ambientEv;
            }
            // The sky is about one stop brighter than a sunlit subject; less near the horizon.
            return daylightEv + Mth.clamp(dir.y * 2, 0, 1);
        }
        BlockPos pos = hit instanceof BlockHitResult bhr
                ? bhr.getBlockPos().relative(bhr.getDirection())
                : BlockPos.containing(hit.getLocation());
        return pointEv(level, pos, daylightEv, ambientEv);
    }
}
