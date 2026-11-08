#version 150

#moj_import <realcamera:dof_common.glsl>

// Right after the level is drawn (the depth buffer is cleared before the hand and the GUI, and a shader pack
// only finishes its picture later): the signed blur radius of every pixel, in full-resolution pixels.
// With Layers = 1 the depth of field looks through water, stained glass and ice (DepthLayers): where a translucent
// surface lies in front of the opaque world the opaque depth is used; particles keep their own depth.
uniform sampler2D DepthSampler;   // everything the level drew (Fabulous: the particles' target)
uniform sampler2D OpaqueSampler;  // the opaque world
uniform sampler2D SurfaceSampler; // the opaque world with translucent block surfaces
uniform float Layers;
uniform float MaxRadius;

in vec2 texCoord;
out vec4 fragColor;

bool nearer(float a, float b) {
    return a < b * 0.99 - 20.0; // mm
}

float depthMm() {
    float full = rcDistanceMm(texture(DepthSampler, texCoord).r);
    if (Layers < 0.5) return full;
    float surface = rcDistanceMm(texture(SurfaceSampler, texCoord).r);
    float opaque = rcDistanceMm(texture(OpaqueSampler, texCoord).r);
    if (full < 0.0 || surface < 0.0 || opaque < 0.0) return full;
    full = min(full, surface);
    if (nearer(full, surface)) return full; // a particle
    if (nearer(surface, opaque)) return opaque; // through water, glass or ice
    return full;
}

void main() {
    float r = rcSignedBlurPx(depthMm(), rcFocusMm(texCoord)) * 0.5;
    fragColor = vec4(rcEncodeRadius(clamp(r, -MaxRadius, MaxRadius), MaxRadius), 0.0, 1.0);
}
