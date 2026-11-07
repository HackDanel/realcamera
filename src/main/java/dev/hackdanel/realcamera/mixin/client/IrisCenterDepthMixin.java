package dev.hackdanel.realcamera.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shader packs focus their own depth of field on {@code centerDepthSmooth}, the depth under the crosshair. While
 * the camera is raised the pack should focus where the lens is focused instead (the AF point, the focus ring), or
 * its blur follows the crosshair in manual focus and disagrees with the photo. Only applied when Iris is present.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.CenterDepthSampler", remap = false)
public abstract class IrisCenterDepthMixin {
    @Shadow(remap = false)
    private int texture;
    @Shadow(remap = false)
    private int altTexture;

    @Inject(method = "sampleCenterDepth", at = @At("TAIL"), remap = false, require = 0)
    private void realcamera$focusDepth(CallbackInfo ci) {
        float depth = dev.hackdanel.realcamera.client.render.ShaderCompat.packFocusDepth();
        if (Float.isNaN(depth)) return;
        dev.hackdanel.realcamera.client.render.ShaderCompat.writeDepth1x1(texture, depth);
        dev.hackdanel.realcamera.client.render.ShaderCompat.writeDepth1x1(altTexture, depth);
    }
}
