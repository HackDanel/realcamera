#version 150

#moj_import <realcamera:dof_common.glsl>

// Downscaled colour (average of the Scale x Scale block) and the signed blur radius in alpha. The blur pass runs
// at 1/2, 1/4 or 1/8 resolution so that wide-open backgrounds blur as much as in the photo at a bounded cost.
uniform sampler2D DiffuseSampler;
uniform sampler2D CocSampler;
uniform vec2 InSize;
uniform float FullMaxRadius; // full-resolution pixels (the radius texture)
uniform float MaxRadius;     // pixels of the downscaled image
uniform float Scale;         // 2, 4 or 8

in vec2 texCoord;
out vec4 fragColor;

void main() {
    int n = int(Scale + 0.5);
    vec2 px = 1.0 / InSize;
    // top-left full-resolution pixel centre of this block
    vec2 origin = texCoord - px * (Scale * 0.5 - 0.5);
    vec3 c = vec3(0.0);
    float nearest = FullMaxRadius;
    for (int y = 0; y < 8; y++) {
        if (y >= n) break;
        for (int x = 0; x < 8; x++) {
            if (x >= n) break;
            vec2 t = origin + vec2(float(x), float(y)) * px;
            c += texture(DiffuseSampler, t).rgb;
            // the signed radius grows with the distance: the nearest of the block decides, so a foreground edge
            // keeps its blur
            nearest = min(nearest, rcDecodeRadius(texture(CocSampler, t).rg, FullMaxRadius));
        }
    }
    float r = nearest / Scale;
    fragColor = vec4(c / float(n * n), 0.5 + 0.5 * clamp(r / MaxRadius, -1.0, 1.0));
}
