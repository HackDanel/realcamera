package dev.hackdanel.realcamera.client.camera;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Temperature of the image sensor, which drives the dark current of long exposures (it doubles about every 6.5 °C).
 * The camera sits at the air temperature of where it is (biome, cooler at night); live view and long exposures keep
 * the sensor powered and warm it by several degrees over minutes, an optical viewfinder much less. One state for the
 * camera in use: a sensor takes minutes to warm up or cool down, longer than swapping cameras.
 */
public final class SensorHeat {
    /** Time constant of warming and cooling, seconds. */
    private static final double TAU_S = 600;
    private static double sensorC = Double.NaN;

    private SensorHeat() {}

    /** Air temperature (°C) at a position: the biome's temperature, a few degrees less at night. */
    public static double ambientC(ClientLevel level, BlockPos pos) {
        float biome = level.getBiome(pos).value().getBaseTemperature();
        double c = biome * 25; // plains 0.8 -> 20 °C, snowy 0 -> 0 °C, desert 2.0 -> hot
        if (level.dimensionType().hasSkyLight()) {
            double day = Math.cos(level.getSunAngle(1f)); // 1 at noon, -1 at midnight
            c += 4 * day - 2;
        }
        return Mth.clamp(c, -25, 42);
    }

    /**
     * One client tick (1/20 s). powered: the sensor is on (live view on an electronic viewfinder or screen);
     * exposing: a long exposure is running.
     */
    public static void tick(ClientLevel level, BlockPos pos, boolean powered, boolean exposing) {
        double air = ambientC(level, pos);
        if (Double.isNaN(sensorC)) sensorC = air;
        double target = air + (exposing ? 12 : powered ? 8 : 0);
        sensorC += (target - sensorC) * (0.05 / TAU_S);
    }

    public static double sensorC() {
        return Double.isNaN(sensorC) ? 20 : sensorC;
    }
}
