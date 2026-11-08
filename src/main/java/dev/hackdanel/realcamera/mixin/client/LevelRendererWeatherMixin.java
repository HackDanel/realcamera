package dev.hackdanel.realcamera.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Fast/Fancy graphics draw rain and snow without writing depth, so a raindrop right in front of the lens had the
 * depth of whatever lay behind it and stayed a sharp streak in the photo. While the viewfinder or a capture wants
 * depth (no shader pack, see {@code DepthLayers}), the streaks write it like Fabulous graphics do. Only the visible
 * part of a streak writes depth (the particle shader discards transparent texels), and nothing of the level is drawn
 * after the weather that it could hide; the hand comes after a depth clear.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWeatherMixin {
    @ModifyArg(method = "renderSnowAndRain",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;depthMask(Z)V"),
            require = 0)
    private boolean realcamera$weatherDepth(boolean mask) {
        return mask || dev.hackdanel.realcamera.client.render.DepthLayers.weatherDepth();
    }
}
