#version 150

#moj_import <realcamera:dof_common.glsl>

// Half-resolution colour (average of the 2x2 block) and the signed blur radius in alpha.
uniform sampler2D DiffuseSampler;
uniform sampler2D CocSampler;
uniform vec2 InSize;
uniform float FullMaxRadius; // full-resolution pixels (the radius texture)
uniform float MaxRadius;     // half-resolution pixels

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 o = 0.5 / InSize;
    vec2 t[4] = vec2[](texCoord + vec2(-o.x, -o.y), texCoord + vec2(o.x, -o.y),
                       texCoord + vec2(-o.x, o.y), texCoord + vec2(o.x, o.y));
    vec3 c = vec3(0.0);
    float nearest = FullMaxRadius;
    for (int i = 0; i < 4; i++) {
        c += texture(DiffuseSampler, t[i]).rgb;
        // the signed radius grows with the distance: the nearest of the four decides, so a foreground edge
        // keeps its blur
        nearest = min(nearest, rcDecodeRadius(texture(CocSampler, t[i]).rg, FullMaxRadius));
    }
    float r = nearest * 0.5;
    fragColor = vec4(c * 0.25, 0.5 + 0.5 * clamp(r / MaxRadius, -1.0, 1.0));
}
