package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/**
 * The world's light levels around the camera for the electronic finder: sky and block light of every voxel in a box
 * {@link #SX} x {@link #SY} x {@link #SZ} blocks around the camera, as a texture the {@code realcamera_evf} shader
 * reads per pixel (the pixel's world position from the depth buffer, nudged half a block off its surface). A light
 * map cast on a coarse grid of rays left soft blotches wherever the light changes within a few blocks (the shade
 * under a tree, the edge of a torch's light); per voxel the shading follows the blocks as the game's own does.
 *
 * <p>The box is filled a few horizontal slices per tick and only shown once complete, with the origin it was filled
 * for; the next fill starts at once (light changes show within {@link #TICKS} ticks).
 */
public final class LightVolume {
    public static final int SX = 96, SY = 40, SZ = 96;
    /** Horizontal slices are tiled into a 2D texture, this many per row. */
    public static final int TILES = 8;
    private static final int TICKS = 16;

    /** A complete box: its minimum corner (block coordinates) and version. */
    public record Box(int ox, int oy, int oz, int version) {}

    @Nullable
    private static DynamicTexture texture;
    @Nullable
    private static Box shown;
    @Nullable
    private static NativeImage building;
    private static int bx, by, bz, nextSlice, version;

    private LightVolume() {}

    /** The complete box the texture holds, or null. */
    @Nullable
    public static Box box() {
        return shown;
    }

    /** The texture of the complete box (valid while {@link #box()} is not null). */
    public static int textureId() {
        return texture == null ? 0 : texture.getId();
    }

    /** Forget everything (the finder no longer simulates the light). */
    public static void clear() {
        shown = null;
        if (building != null) {
            building.close();
            building = null;
        }
    }

    /** Fills the next slices of the box being built; uploads it when complete. Client thread. */
    public static void tick(Minecraft mc) {
        var level = mc.level;
        if (level == null) return;
        int w = SX * TILES, h = SZ * ((SY + TILES - 1) / TILES);
        if (building == null) {
            building = new NativeImage(w, h, true);
            BlockPos c = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition());
            bx = c.getX() - SX / 2;
            by = c.getY() - SY / 2;
            bz = c.getZ() - SZ / 2;
            nextSlice = 0;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int slices = (SY + TICKS - 1) / TICKS;
        for (int y = nextSlice; y < Math.min(SY, nextSlice + slices); y++) {
            int tx = (y % TILES) * SX, ty = (y / TILES) * SZ;
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    pos.set(bx + x, by + y, bz + z);
                    int sky = level.getBrightness(LightLayer.SKY, pos);
                    int block = level.getBrightness(LightLayer.BLOCK, pos);
                    // full opaque cubes hold no light of their own: the shader leaves them out of its interpolation
                    boolean open = !level.getBlockState(pos).isSolidRender(level, pos);
                    building.setPixelRGBA(tx + x, ty + z, 0xFF000000 | (open ? 255 : 0) << 16 | block * 17 << 8 | sky * 17);
                }
            }
        }
        nextSlice += slices;
        if (nextSlice >= SY) {
            if (texture == null || texture.getPixels() == null || texture.getPixels().getWidth() != w
                    || texture.getPixels().getHeight() != h) {
                if (texture != null) texture.close();
                texture = new DynamicTexture(w, h, false);
            }
            texture.getPixels().copyFrom(building);
            texture.upload();
            texture.setFilter(false, false);
            shown = new Box(bx, by, bz, ++version);
            building.close();
            building = null;
        }
    }
}
