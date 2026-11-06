#version 150

#moj_import <realcamera:dof_common.glsl>

// Right after the level is drawn (the depth buffer is cleared before the hand and the GUI, and a shader pack
// only finishes its picture later): the signed blur radius of every pixel, in full-resolution pixels.
uniform sampler2D DepthSampler;
uniform float MaxRadius;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float r = rcSignedBlurPx(rcDistanceMm(texture(DepthSampler, texCoord).r), rcFocusMm(texCoord)) * 0.5;
    fragColor = vec4(rcEncodeRadius(clamp(r, -MaxRadius, MaxRadius), MaxRadius), 0.0, 1.0);
}
