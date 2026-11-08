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
 * sun height, weather and moon phase; block light gives indoor/torch levels. Calibration against real light:
 * clear noon EV 15, overcast 13, rain 11-12, storm ~10, sunset ~11, end of civil twilight ~0.5, full moon -2.5,
 * new moon (starlight) -9, open shade -2.5 EV below the sun, a room a few blocks from a window -7, torch-lit
 * room ~6. Sky light falls off much faster than the game draws it: one block under an overhang is real open shade.
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
            double night = -9 + 6.5 * Math.sqrt(moon); // new moon -9 (starlight), quarter about -5.7, full -2.5
            ev = Math.max(night, 11 + 100 * elevation); // sun 6 degrees down: about EV 0.5
        }
        ev -= 3.5 * level.getRainLevel(partialTick) + 1.5 * level.getThunderLevel(partialTick);
        return ev;
    }

    /**
     * Colour temperature of the daylight (K): warm low sun, neutral midday, cool overcast; moonlight is
     * reflected sunlight (about 4100 K). Shade (sky light below 15) is lit by blue sky only.
     */
    public static double daylightK(ClientLevel level, float partialTick, int skyLight) {
        if (!level.dimensionType().hasSkyLight()) {
            return 5000;
        }
        double elevation = Math.cos(level.getSunAngle(partialTick));
        double k;
        if (elevation >= 0) {
            k = 3300 + 2200 * Math.sqrt(Mth.clamp(elevation / 0.35, 0, 1));
        } else {
            k = elevation > -0.1 ? 3300 + 8000 * -elevation : 4100; // blue hour, then moonlight
        }
        double overcast = Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        k += (6500 - k) * overcast;
        if (skyLight < 15 && elevation >= 0) {
            k += (7500 - k) * Mth.clamp((15 - skyLight) / 2.0, 0, 1) * (1 - overcast * 0.6);
        }
        return k;
    }

    /** Fire (torches, lanterns, lava) is about 1900 K. */
    public static final double FIRE_K = 1900;

    /**
     * How the game itself lit a point (0..1): the light map curve of its sky and block light. A photo exposed
     * correctly for a point lit this dimly by the game must come out this much brighter than the game frame.
     */
    public static double gameLight(ClientLevel level, BlockPos pos) {
        float sky = level.getBrightness(LightLayer.SKY, pos) * level.getSkyDarken(1f);
        float block = level.getBrightness(LightLayer.BLOCK, pos);
        float f = Math.max(sky, block) / 15f;
        return 0.05 + 0.95 * f / (4 - 3 * f);
    }

    /**
     * Reflectance proxy of what a ray hit (its map colour's luminance, in linear light), relative to the 0.3 a
     * typical Minecraft scene averages: a reflected-light meter reads snow brighter and coal darker than the light
     * falling on them, so it underexposes snow and overexposes dark subjects like a real one. EV offset.
     */
    public static double reflectanceEv(ClientLevel level, HitResult hit) {
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS) {
            return 0;
        }
        var state = level.getBlockState(bhr.getBlockPos());
        int rgb = state.getMapColor(level, bhr.getBlockPos()).col;
        if (rgb == 0) return 0;
        double r = lin(((rgb >> 16) & 255) / 255.0), g = lin(((rgb >> 8) & 255) / 255.0), b = lin((rgb & 255) / 255.0);
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return Mth.clamp(Math.log(Math.max(y, 0.01) / 0.3) / Math.log(2), -2.5, 1.8);
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
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
        double b = block / 15.0;
        double lum = Math.pow(2, daylightEv + skyFalloffEv(sky)) + Math.pow(2, 7) * b * b * b + Math.pow(2, ambientEv)
                + Math.pow(2, -10);
        return Math.log(lum) / Math.log(2);
    }

    /**
     * How much darker than open daylight a point with this sky light is: about -2.6 EV one block under an overhang
     * (open shade), -6.6 three blocks in, -10 in a room's depths, nothing at all without sky light.
     */
    static double skyFalloffEv(int sky) {
        if (sky <= 0) return -40;
        return -2.6 * Math.pow(15 - sky, 0.85);
    }

    /** True if the brightest light at this point is a block (torches...), used for auto white balance. */
    public static boolean blockLit(ClientLevel level, BlockPos pos, double daylightEv) {
        return fireShare(level, pos, daylightEv) > 0.5;
    }

    /** Share of the light at a point that comes from blocks (fire) rather than the sky, 0..1. */
    public static double fireShare(ClientLevel level, BlockPos pos, double daylightEv) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        double b = level.getBrightness(LightLayer.BLOCK, pos) / 15.0;
        double fire = Math.pow(2, 7) * b * b * b, daylight = Math.pow(2, daylightEv + skyFalloffEv(sky));
        return fire / Math.max(1e-12, fire + daylight);
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
        return pointEv(level, litPos(level, hit), daylightEv, ambientEv);
    }

    /**
     * The position whose light lights what a ray hit: the block in front of the hit face. Stairs, slabs and other
     * partial blocks (roofs!) store no light in their own voxel, so when the front block is one, the brightest of its
     * neighbours is used; reading the stored 0 there made a roof meter many stops darker than it is.
     */
    public static BlockPos litPos(ClientLevel level, HitResult hit) {
        BlockPos pos = hit instanceof BlockHitResult bhr
                ? bhr.getBlockPos().relative(bhr.getDirection())
                : BlockPos.containing(hit.getLocation());
        if (level.getBlockState(pos).isAir()) {
            return pos;
        }
        BlockPos best = pos;
        int bestLight = Math.max(level.getBrightness(LightLayer.SKY, pos), level.getBrightness(LightLayer.BLOCK, pos));
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = pos.relative(d);
            int l = Math.max(level.getBrightness(LightLayer.SKY, n), level.getBrightness(LightLayer.BLOCK, n));
            if (l > bestLight) {
                bestLight = l;
                best = n;
            }
        }
        return best;
    }
}
