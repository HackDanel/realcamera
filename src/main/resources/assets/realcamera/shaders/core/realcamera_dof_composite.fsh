#version 150

#moj_import <realcamera:dof_common.glsl>

// Full resolution: the sharp picture where it is in focus, the blurred half-resolution one where the blur
// (this pixel's own, or a blurred foreground spilling over it) is wider than about a pixel.
uniform sampler2D DiffuseSampler;
uniform sampler2D CocSampler;
uniform sampler2D BlurSampler;
uniform float BlurMaxRadius; // full-resolution pixels

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 sharp = texture(DiffuseSampler, texCoord).rgb;
    vec4 blurred = texture(BlurSampler, texCoord);
    float own = abs(rcDecodeRadius(texture(CocSampler, texCoord).rg, BlurMaxRadius));
    float reach = max(own, blurred.a * BlurMaxRadius);
    float w = smoothstep(0.6, 2.0, reach);
    fragColor = vec4(mix(sharp, blurred.rgb, w), 1.0);
}
