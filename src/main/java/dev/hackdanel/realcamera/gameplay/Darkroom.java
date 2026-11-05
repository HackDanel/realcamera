package dev.hackdanel.realcamera.gameplay;

import dev.hackdanel.realcamera.registry.RCBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Is it dark enough to handle film or photo paper here? Minecraft only knows light levels, not colours, so
 * the red safelight is told apart by scanning for light sources: daylight and every lamp except the
 * safelight must stay below level 4 at the spot. Film is panchromatic, so for film even the safelight
 * counts (pass {@code safelightOk = false}).
 */
public final class Darkroom {
    private static final int RADIUS = 14;

    private Darkroom() {}

    private record Key(BlockPos pos, boolean safelightOk) {}

    private record Cached(long time, boolean dark) {}

    private static final java.util.Map<Key, Cached> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Same as {@link #dark} but re-checked at most once a second (for menus that show it every tick). */
    public static boolean darkCached(Level level, BlockPos pos, boolean safelightOk) {
        Key k = new Key(pos.immutable(), safelightOk);
        long now = level.getGameTime();
        Cached c = CACHE.get(k);
        if (c != null && now - c.time() < 20 && now >= c.time()) return c.dark();
        if (CACHE.size() > 256) CACHE.clear();
        boolean d = dark(level, pos, safelightOk);
        CACHE.put(k, new Cached(now, d));
        return d;
    }

    public static boolean dark(Level level, BlockPos pos, boolean safelightOk) {
        int sky = level.getBrightness(LightLayer.SKY, pos) - level.getSkyDarken();
        if (sky > 3) return false;
        if (!safelightOk) {
            // film is panchromatic: even a dim safelight nearby fogs it
            return level.getBrightness(LightLayer.BLOCK, pos) <= 3 && !safelightNear(level, pos);
        }
        if (level.getBrightness(LightLayer.BLOCK, pos) <= 3) return true;
        // some block light reaches the spot: acceptable only if it all comes from safelights
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (dist > RADIUS) continue;
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState s = level.getBlockState(m);
                    int emission = s.getLightEmission(level, m);
                    if (emission <= 3 || s.is(RCBlocks.SAFELIGHT.get())) continue;
                    if (emission - dist > 3) return false;
                }
            }
        }
        return true;
    }

    private static boolean safelightNear(Level level, BlockPos pos) {
        int r = 5;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > r) continue;
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getBlockState(m).is(RCBlocks.SAFELIGHT.get())) return true;
                }
            }
        }
        return false;
    }
}
